package com.risquanter.register.mitigation

import zio.prelude.*
import com.risquanter.register.domain.data.{
  LeafParams, LeafStageSpec, Mitigation, MitigationApplicationRecord, MitigationSelection, MitigationSpec,
  RiskLeaf, RiskNode, RiskPortfolio, RiskTree, ScopeRestriction
}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.domain.errors.{ErrorSubject, ValidationError}
import com.risquanter.register.simulation.TrialOutcomes

/**
 * The monoid action `Mits × Tree → Tree`, decomposed:
 *
 * - `scoped` — the action's per-node decomposition: which mitigations apply to
 *   which node, in global precedence order (ascending key, id tiebreak).
 * - `effectiveTree` — the param-stage half: scoped leaves replaced by their
 *   transformed selves. Output is a normal `RiskTree` revalidated through
 *   `RiskTree.fromNodes` — closure by construction.
 * - `recordsByNode` — the result-stage half, as one record list per node.
 * - `run` — that layer's composed pipeline, applied to a node's finished
 *   `TrialOutcomes`, never to the summation step (ADR-009).
 * - `applicationRecords` — one record per applied mitigation for one
 *   resolution, describing what the application touched.
 *
 * Scope-disjoint mitigations commute (trace-monoid property); order matters
 * only where scopes overlap, where the precedence key decides.
 *
 * Override staleness (`staleOverrides`) is deliberately NOT here: it needs the
 * JVM-only content hasher and lives in the server's `MitigationStaleness`.
 */
object MitigationApplication {

  /** Per-node applicable mitigations, ascending precedence key, MitigationId
    * tiebreak. Each mitigation's node set comes from `resolvedScopes` (the
    * server-computed predicate resolution); a mitigation absent from the map
    * has an empty scope and applies nowhere. */
  def scoped(
    tree: RiskTree,
    selection: MitigationSelection,
    resolvedScopes: Map[MitigationId, Set[NodeId]]
  ): Map[NodeId, List[Mitigation]] = {
    val enabled: List[(Mitigation, Option[Set[NodeId]])] = selection match {
      case MitigationSelection.Inherent => Nil
      case MitigationSelection.Residual =>
        tree.mitigations.map(m => (m, Option.empty[Set[NodeId]])).toList
      case MitigationSelection.Selected(entries) =>
        tree.mitigations.flatMap { m =>
          entries.get(m.id).map {
            case ScopeRestriction.FullScope      => (m, Option.empty[Set[NodeId]])
            case ScopeRestriction.NodesOnly(ids) => (m, Some(ids))
          }
        }.toList
    }

    val pairs: List[(NodeId, Mitigation)] = for {
      (m, restriction) <- enabled
      resolved  = resolvedScopes.getOrElse(m.id, Set.empty[NodeId])
      effective = restriction.fold(resolved)(r => resolved.intersect(r))
      nodeId <- effective
    } yield nodeId -> m

    pairs.groupMap(_._1)(_._2).view.mapValues(inPrecedenceOrder).toMap
  }

  /** Applies every leaf-stage spec to its scoped leaves, in precedence order
    * per leaf; portfolios and out-of-scope leaves pass through. The fold
    * threads only the parameters a spec can change, so the leaf is rebuilt once
    * at the end rather than per step. A spec's refusal names the mitigation and
    * the leaf, because one leaf can carry several leaf-stage mitigations. The
    * result revalidates through `RiskTree.fromNodes` (errors accumulate). */
  def effectiveTree(
    tree: RiskTree,
    selection: MitigationSelection,
    resolvedScopes: Map[MitigationId, Set[NodeId]]
  ): Validation[ValidationError, RiskTree] = {
    val perNode = scoped(tree, selection, resolvedScopes)

    val newNodesV: Validation[ValidationError, List[RiskNode]] =
      Validation.validateAll(tree.nodes.toList.map {
        case leaf: RiskLeaf =>
          perNode.get(leaf.id).map(_.collect {
            case m @ Mitigation(_, _, _, spec: LeafStageSpec, _) => m.id -> spec
          }) match {
            case Some(specs) if specs.nonEmpty =>
              specs
                .foldLeft(Validation.succeed(LeafParams(leaf.probability, leaf.shape)): Validation[ValidationError, LeafParams]) {
                  case (accV, (mitigationId, spec)) =>
                    accV.flatMap(params =>
                      LeafStageSpec.applyTo(spec, params, ErrorSubject.MitigationAtNode(mitigationId, leaf.id)))
                }
                .map(params =>
                  RiskLeaf.fromValidated(
                    id = leaf.safeId,
                    name = leaf.safeName,
                    probability = params.probability,
                    shape = params.shape,
                    parentId = leaf.parentId,
                    seedVarId = leaf.seedVarId
                  )
                )
            case _ => Validation.succeed(leaf)
          }
        case portfolio: RiskPortfolio => Validation.succeed(portfolio)
      })

    newNodesV.flatMap { newNodes =>
      RiskTree.fromNodes(
        tree.id, tree.name, newNodes, tree.rootId,
        Some(tree.seedVarHighWater), tree.mitigations
      )
    }
  }

  /** One record per result-stage mitigation scoping each node, in precedence
    * order — the layer that node's valuation applies.
    *
    * Parameter-stage transforms are folded into the effective tree before the
    * content hash is computed, so they shape the figure the layer is applied to
    * and are never part of the layer. Each record's `resolvedScope` is the
    * mitigation's whole node set, which is why it is computed once for the tree
    * rather than per node. */
  def recordsByNode(
    scoped: Map[NodeId, List[Mitigation]]
  ): Map[NodeId, List[MitigationApplicationRecord]] = {
    val scopeOf = resolvedScopeOf(scoped)
    scoped.view.mapValues { ms =>
      ms.collect { case m @ Mitigation(_, _, _, MitigationSpec.ResultStage(_), _) =>
        MitigationApplicationRecord(m.id, m.spec, scopeOf.getOrElse(m, Set.empty), m.precedence)
      }
    }.toMap
  }

  /** The composed transform of one node's layer, applied. An empty layer
    * returns the same reference it was given. */
  def run(
    applied: List[MitigationApplicationRecord],
    outcomes: TrialOutcomes
  ): TrialOutcomes =
    applied
      .collect { case MitigationApplicationRecord(_, MitigationSpec.ResultStage(pipeline), _, _) => pipeline }
      .map(ResultTransformInterpreter.toTransform)
      .reduceOption(_.andThen(_))
      .fold(outcomes)(_.run(outcomes))

  /** One record per applied mitigation for a single resolution: the node set
    * the application actually touched under this tree version and selection. */
  def applicationRecords(
    tree: RiskTree,
    selection: MitigationSelection,
    resolvedScopes: Map[MitigationId, Set[NodeId]]
  ): List[MitigationApplicationRecord] =
    resolvedScopeOf(scoped(tree, selection, resolvedScopes)).toList
      .map { case (m, nodes) => MitigationApplicationRecord(m.id, m.spec, nodes, m.precedence) }
      .sortBy(r => (r.precedence.key, r.mitigationId.value))

  /** Invert the per-node view: each mitigation paired with every node it
    * scopes. This is a record's `resolvedScope`. */
  private def resolvedScopeOf(
    scoped: Map[NodeId, List[Mitigation]]
  ): Map[Mitigation, Set[NodeId]] =
    scoped.toList
      .flatMap { case (nodeId, ms) => ms.map(_ -> nodeId) }
      .groupMap(_._1)(_._2)
      .view.mapValues(_.toSet).toMap

  private def inPrecedenceOrder(ms: List[Mitigation]): List[Mitigation] =
    ms.sortBy(m => (m.precedence.key, m.id.value))
}
