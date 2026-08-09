package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.domain.errors.ValidationError

/**
 * Per-mitigation scope restriction inside a selection (OD-3): `FullScope`
 * applies the mitigation to its whole resolved scope (the global toggle);
 * `NodesOnly` restricts application to an explicit node subset (per-node
 * enablement). A `NodesOnly` set intersects with the mitigation's resolved
 * scope at application time — ids outside the current scope no-op.
 */
sealed trait ScopeRestriction

object ScopeRestriction {
  case object FullScope extends ScopeRestriction
  final case class NodesOnly(ids: Set[NodeId]) extends ScopeRestriction

  given Equal[ScopeRestriction] = Equal.default

  private case class Raw(kind: String, ids: Option[List[String]])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[ScopeRestriction] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case FullScope      => Raw("full", None)
      case NodesOnly(ids) => Raw("nodes", Some(ids.map(_.value).toList.sorted))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("full", None) => Right(FullScope)
      case Raw("nodes", Some(rawIds)) =>
        val (errors, ids) = rawIds.partitionMap(NodeId.fromString)
        if (errors.nonEmpty) Left(errors.flatten.map(_.message).mkString("; "))
        else Right(NodesOnly(ids.toSet))
      case other => Left(s"invalid scope restriction kind '${other.kind}'")
    }
  )
}

/**
 * Which mitigations a resolution applies, each optionally scope-restricted
 * (per-(mitigation, node) enablement — OD-3 ruling). Crosses the wire in M4
 * as a request parameter. `None` is the default on every existing read path
 * (OD-5): mitigation is strictly opt-in per request.
 */
sealed trait MitigationSelection

object MitigationSelection {
  case object None extends MitigationSelection
  case object All extends MitigationSelection
  final case class Selected(entries: Map[MitigationId, ScopeRestriction]) extends MitigationSelection

  given Equal[MitigationSelection] = Equal.default

  private case class Raw(kind: String, entries: Option[Map[MitigationId, ScopeRestriction]])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[MitigationSelection] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case None              => Raw("none", scala.None)
      case All               => Raw("all", scala.None)
      case Selected(entries) => Raw("selected", Some(entries))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("none", scala.None)          => Right(None)
      case Raw("all", scala.None)           => Right(All)
      case Raw("selected", Some(entries))   => Right(Selected(entries))
      case other => Left(s"invalid mitigation selection kind '${other.kind}'")
    }
  )
}

/**
 * The monoid action `Mits × Tree → Tree`, decomposed:
 *
 * - `scoped` — the action's per-node decomposition: which mitigations apply to
 *   which node, in global precedence order (ascending key, id tiebreak).
 * - `effectiveTree` — the param-stage half: scoped leaves replaced by their
 *   transformed selves. Output is a normal `RiskTree` revalidated through
 *   `RiskTree.fromNodes` — closure by construction.
 * - `resultTransformFor` — the result-stage half for one node: the composed
 *   pipeline of every ResultStage mitigation scoping it (identity when none).
 *   The resolver applies it to a node's finished `TrialOutcomes` — the
 *   combine's operand or finished aggregate, never the summation step
 *   (ADR-009 associativity invariant).
 * - `applicationRecords` — the D-4 provenance layer for one resolution.
 * - `worseningOverrides` — nonsense-check diagnostic (staleness layer 4).
 *
 * Scope-disjoint mitigations commute (trace-monoid property); order matters
 * only where scopes overlap, where the precedence key decides.
 *
 * Staleness layer 1 (`staleOverrides`) is deliberately NOT here: it needs the
 * JVM-only content hasher and lives in the server's `MitigationStaleness`
 * (PLAN-RISKTRANSFORM OD-6).
 */
object MitigationApplication {

  /** Per-node applicable mitigations, ascending precedence key, MitigationId
    * tiebreak. Target ids that don't resolve in this tree's index no-op. */
  def scoped(tree: RiskTree, selection: MitigationSelection): Map[NodeId, List[Mitigation]] = {
    val enabled: List[(Mitigation, Option[Set[NodeId]])] = selection match {
      case MitigationSelection.None => Nil
      case MitigationSelection.All =>
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
      targetIds = m.target match { case MitigationTarget.Nodes(ids) => ids }
      resolved  = targetIds.filter(tree.index.nodes.contains)
      effective = restriction.fold(resolved)(r => resolved.intersect(r))
      nodeId <- effective
    } yield nodeId -> m

    pairs.groupMap(_._1)(_._2).view.mapValues(inPrecedenceOrder).toMap
  }

  /** Applies every LeafStage transform to its scoped leaves, in precedence
    * order per leaf; portfolios and out-of-scope leaves pass through. The
    * result revalidates through `RiskTree.fromNodes` (errors accumulate). */
  def effectiveTree(
    tree: RiskTree,
    selection: MitigationSelection
  ): Validation[ValidationError, RiskTree] = {
    val perNode = scoped(tree, selection)

    val newNodesV: Validation[ValidationError, List[RiskNode]] =
      Validation.validateAll(tree.nodes.toList.map {
        case leaf: RiskLeaf =>
          perNode.get(leaf.id).map(_.collect { case Mitigation(_, _, _, MitigationSpec.LeafStage(t, _), _) => t }) match {
            case Some(transforms) if transforms.nonEmpty =>
              transforms.foldLeft(Validation.succeed(leaf): Validation[ValidationError, RiskLeaf]) {
                (accV, t) => accV.flatMap(l => RiskLeafTransform.applyTo(t, l))
              }
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

  /** Composed result-stage transform for one node, precedence order; identity
    * when nothing result-stage scopes it. */
  def resultTransformFor(
    nodeId: NodeId,
    scoped: Map[NodeId, List[Mitigation]]
  ): RiskResultTransform =
    scoped.getOrElse(nodeId, Nil)
      .collect { case Mitigation(_, _, _, MitigationSpec.ResultStage(pipeline), _) => pipeline }
      .foldLeft(RiskResultTransform.identityTransform)((acc, p) =>
        acc.andThen(TransformPipeline.toTransform(p)))

  /** D-4 records for one resolution: per applied mitigation, the node set the
    * application actually touched under this tree version and selection. */
  def applicationRecords(
    tree: RiskTree,
    selection: MitigationSelection
  ): List[MitigationApplicationRecord] = {
    val perNode = scoped(tree, selection)
    perNode.toList
      .flatMap { case (nodeId, ms) => ms.map(_ -> nodeId) }
      .groupMap(_._1)(_._2)
      .map { case (m, nodes) => MitigationApplicationRecord(m.id, m.spec, nodes.toSet, m.precedence) }
      .toList
      .sortBy(r => (r.precedence.key, r.mitigationId.value))
  }

  /** Staleness layer 4 (nonsense check): Override mitigations whose asserted
    * post-mitigation state is not better than the target leaf's current base —
    * higher occurrence probability, or a stochastically-not-lower loss
    * distribution of the same representation (both lognormal bounds, or every
    * expert quantile over identical percentiles, at or above the base with at
    * least one strictly above). Cross-representation overrides are not
    * comparable and are never flagged. */
  def worseningOverrides(tree: RiskTree): Set[MitigationId] =
    tree.mitigations.collect {
      case m @ Mitigation(_, _, MitigationTarget.Nodes(ids), MitigationSpec.LeafStage(t, _), _)
          if ids.size == 1 =>
        tree.index.nodes.get(ids.head) match {
          case Some(leaf: RiskLeaf) if overrideWorsens(t, leaf) => Some(m.id)
          case _                                                => None
        }
    }.flatten.toSet

  private def overrideWorsens(t: RiskLeafTransform, leaf: RiskLeaf): Boolean = {
    val likelihoodWorse = t.likelihood match {
      case LikelihoodTransform.Override(p) => (p: Double) > (leaf.probability: Double)
      case _                               => false
    }
    val distributionWorse = t.distribution match {
      case DistributionTransform.Override(p) if p.distributionType.toString == leaf.distributionType.toString =>
        p.distributionType.toString match {
          case "lognormal" =>
            (p.minLoss, p.maxLoss, leaf.minLoss, leaf.maxLoss) match {
              case (Some(nMin), Some(nMax), Some(bMin), Some(bMax)) =>
                (nMin: Long) >= (bMin: Long) && (nMax: Long) >= (bMax: Long) &&
                  ((nMin: Long) > (bMin: Long) || (nMax: Long) > (bMax: Long))
              case _ => false
            }
          case _ =>
            (p.percentiles, p.quantiles, leaf.percentiles, leaf.quantiles) match {
              case (Some(nP), Some(nQ), Some(bP), Some(bQ))
                  if nP.sameElements(bP) && nQ.length == bQ.length =>
                val pairs = nQ.toSeq.lazyZip(bQ.toSeq).toSeq
                pairs.forall { case (n, b) => n >= b } && pairs.exists { case (n, b) => n > b }
              case _ => false
            }
        }
      case _ => false
    }
    likelihoodWorse || distributionWorse
  }

  private def inPrecedenceOrder(ms: List[Mitigation]): List[Mitigation] =
    ms.sortBy(m => (m.precedence.key, m.id.value))
}
