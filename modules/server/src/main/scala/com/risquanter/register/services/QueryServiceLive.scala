package com.risquanter.register.services

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind

import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.{MitigationSelection, ScopeRestriction}
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, SeedEntityId, BranchRef, Revision}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.errors.FolQueryFailure
import com.risquanter.register.foladapter.{RiskTreeKnowledgeBase, QueryResponseBuilder}
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.services.cache.{CachedResultResolver, ScopeResolverScope, ScopeResolutionContext, ResolvedScopes}

import vql.logic.ParsedQuery
import vql.semantics.VagueSemantics
import vql.sampling.{SamplingParams, HDRConfig}
import vql.typed.{FolModel, QueryBinder, BoundQuery, BoundFormula, BoundTerm, BoundVar}

/** Live implementation of [[QueryService]] using the `vql.typed` many-sorted pipeline.
  *
  * Dependencies:
  *   - `RiskTreeRepository` for tree lookups, which also return the commit the
  *     read resolved to
  *   - `CachedResultResolver` for cache-aside simulation results, one map per referenced selection
  *   - `ScopeResolverScope` for per-workspace mitigation scope resolution
  *   - `Tracing` for OpenTelemetry spans
  */
class QueryServiceLive private (
  repo:          RiskTreeRepository,
  resolver:      CachedResultResolver,
  scopeResolver: ScopeResolverScope,
  tracing:       Tracing
) extends QueryService:

  /** Wrap body in an OTel span. */
  private def traced[A](name: String)(body: Task[A]): Task[A] =
    tracing.span(s"QueryService.$name", SpanKind.INTERNAL)(body)

  /** Alarm-on-bypass: log node and mitigation names that collided with reserved
    * FOL symbols (the supported flow gates them at the DTO boundary; any bypass
    * is observable here). */
  private def logNameCollisions(kb: RiskTreeKnowledgeBase): UIO[Unit] =
    ZIO.when(kb.riskNameCollisions.nonEmpty)(
      ZIO.logWarning(
        s"RiskTreeKnowledgeBase: ${kb.riskNameCollisions.size} node name(s) skipped " +
        s"because they collide with reserved symbols (DTO validators bypassed?): " +
        kb.riskNameCollisions.mkString(", ")
      )
    ) *> ZIO.when(kb.mitigationNameCollisions.nonEmpty)(
      ZIO.logWarning(
        s"RiskTreeKnowledgeBase: ${kb.mitigationNameCollisions.size} mitigation name(s) " +
        s"skipped because they collide with reserved symbols: " +
        kb.mitigationNameCollisions.mkString(", ")
      )
    ).unit

  /** Per-mitigation resolution drift signals (ADR-002): a stale predicate is a
    * no-op for that mitigation, not a request failure, so this only logs. */
  private def logResolutionFailures(resolved: ResolvedScopes): UIO[Unit] =
    ZIO.when(resolved.failures.nonEmpty)(
      ZIO.logWarning(
        s"MitigationScopeResolver: ${resolved.failures.size} mitigation(s) did not " +
        s"resolve against this tree version: " +
        resolved.failures.map((id, errs) => s"${id.value} -> ${errs.mkString("[", ", ", "]")}").mkString("; ")
      )
    ).unit

  override def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, seedEntityId: SeedEntityId.SeedEntityId, branch: BranchRef): Task[QueryResponse] =
    traced("evaluate") {
      for
        _ <- tracing.setAttribute("query.tree_id", treeId.value)

        // 1. Load the tree and the concrete commit it resolved to — the
        //    storage revision the scope resolver memoizes on.
        loaded <- repo.getById(wsId, treeId, Revision.Head(branch))
        treeAndHash <- loaded match
                         case Some(tc) => ZIO.succeed(tc)
                         case None =>
                           ZIO.fail(ValidationFailed(List(ValidationError(
                             field = "treeId",
                             code = ValidationErrorCode.NOT_FOUND,
                             message = s"Tree not found: ${treeId.value}"
                           ))))
        (tree, commitHash) = treeAndHash

        // 2. Resolve every mitigation's scope for this tree version. The resolver
        //    is per-workspace, so cross-workspace scope contamination is
        //    structurally impossible; the memo key is (treeId, branch, commit).
        mitResolver <- scopeResolver.resolverFor(wsId)
        resolved    <- mitResolver.resolve(ScopeResolutionContext(treeId, branch, commitHash), tree)

        allNodeIds = tree.index.nodes.keySet

        // 3. Build the tree-derived schema once and bind against its catalog to
        //    learn which selections the query references. The same schema drives
        //    the eval-time KB below, so the pre-bind catalog and the eval-time
        //    catalog are guaranteed identical by construction. A bind failure
        //    here needs no handling: precompute nothing and let `evaluateTyped`
        //    below re-bind and surface the classified error.
        schema     = RiskTreeKnowledgeBase.schemaFor(tree)
        selections = QueryBinder.bind(parsed, schema.catalog)
                       .map(b => MitigationSelectionScan.referenced(b, tree))
                       .getOrElse(Set.empty[MitigationSelection])

        // 4. Precompute one result map per referenced selection over that
        //    selection's effective tree; the content-addressed cache dedups
        //    identical effective trees. Inherent → base results; Residual/Selected
        //    → residual. No referenced selection ⇒ no simulation needed.
        resultsBySelection <- ZIO.foreach(selections.toList) { sel =>
                                resolver.ensureCachedAll(tree, allNodeIds, seedEntityId,
                                    selection = sel, resolvedScopes = resolved.appliedScopes)
                                  .tapError(e => ZIO.logWarning(s"Simulation cache unavailable for tree ${treeId.value}: ${e.getMessage}"))
                                  .mapError(_ => FolQueryFailure.SimulationNotCached(treeId): Throwable)
                                  .map(sel -> _)
                              }.map(_.toMap)
        _ <- tracing.setAttribute("query.selections", selections.size.toLong)

        // 5. Build the knowledge base from the shared schema, the selection-keyed
        //    results, and the resolved scopes.
        kb = RiskTreeKnowledgeBase(schema, resultsBySelection, resolved.appliedScopes)
        _ <- logNameCollisions(kb)
        _ <- logResolutionFailures(resolved)

        // 6. Validate catalog+model pairing (FolModel smart constructor)
        folModel <- ZIO.fromEither(FolModel(kb.catalog, kb.model))
                      .tapError(e => ZIO.logWarning(s"FolModel validation failed for tree ${treeId.value}: ${e.formatted}"))
                      .mapError(e => FolQueryFailure.fromQueryError(e))

        // 7. Evaluate via vql.typed pipeline
        queryText = parsed.toString
        _ <- tracing.setAttribute("query.text", queryText)
        output <- ZIO.fromEither(
                    VagueSemantics.evaluateTyped(
                      query = parsed,
                      folModel = folModel,
                      answerTuple = Map.empty,
                      samplingParams = SamplingParams.exact,
                      hdrConfig = HDRConfig.default
                    )
                  ).tapError(e => ZIO.logWarning(s"FOL evaluation failed for tree ${treeId.value}: ${e.formatted}"))
                   .mapError(e => FolQueryFailure.fromQueryError(e))

        // 8. Build response
        _ <- tracing.setAttribute("query.range_size", output.rangeElements.size.toLong)
        _ <- tracing.setAttribute("query.satisfying_count", output.satisfyingElements.size.toLong)
        _ <- tracing.setAttribute("query.satisfied", output.satisfied)
        _ <- tracing.setAttribute("query.proportion", output.proportion)

        response = QueryResponseBuilder.from(output, queryText)
      yield response
    }

end QueryServiceLive

object QueryServiceLive:

  val layer: ZLayer[RiskTreeRepository & CachedResultResolver & ScopeResolverScope & Tracing, Nothing, QueryService] = ZLayer {
    for
      repo          <- ZIO.service[RiskTreeRepository]
      resolver      <- ZIO.service[CachedResultResolver]
      scopeResolver <- ZIO.service[ScopeResolverScope]
      tracing       <- ZIO.service[Tracing]
    yield QueryServiceLive(repo, resolver, scopeResolver, tracing)
  }

/** Walks a bound query for the mitigation selections its value functions
  * reference, so `QueryServiceLive` can precompute exactly those result maps.
  *
  * At each `p95`/`p99`/`lec` application it inspects the mitigation-slot term:
  * the `inherent`/`residual` constants map to the aggregate valuations; a bound
  * `∃m : mitigation` variable fans out to one single-mitigation `Selected` per
  * `tree.mitigations` element — a `named_mitigation`/`mitigation_id`
  * constraint narrows which bindings satisfy the formula at evaluation, not what
  * is precomputed. A `LiteralRef` in the mitigation slot is impossible (no
  * `mitigationSort` literal validator), so it is ignored.
  */
object MitigationSelectionScan:

  private val valueFunctions: Set[String] = Set("p95", "p99", "lec")

  def referenced(bound: BoundQuery, tree: RiskTree): Set[MitigationSelection] =
    lazy val everyMitigation: Set[MitigationSelection] =
      tree.mitigations
        .map(m => MitigationSelection.Selected(Map(m.id -> ScopeRestriction.FullScope)))
        .toSet

    def fromSlot(term: BoundTerm): Set[MitigationSelection] = term match
      case BoundTerm.ConstRef(RiskTreeKnowledgeBase.InherentConst, _) => Set(MitigationSelection.Inherent)
      case BoundTerm.ConstRef(RiskTreeKnowledgeBase.ResidualConst, _) => Set(MitigationSelection.Residual)
      case BoundTerm.VarRef(BoundVar(_, sort)) if sort == RiskTreeKnowledgeBase.MitigationSort => everyMitigation
      case _ => Set.empty

    def selectionSlot(fn: String, args: List[BoundTerm]): Option[BoundTerm] = fn match
      case "p95" | "p99" => args.lift(1)
      case "lec"         => args.lift(2)
      case _             => None

    def fromTerm(term: BoundTerm): Set[MitigationSelection] = term match
      case BoundTerm.FnApp(name, args, _) =>
        val here =
          if valueFunctions.contains(name.value) then
            selectionSlot(name.value, args).map(fromSlot).getOrElse(Set.empty)
          else Set.empty
        here ++ args.flatMap(fromTerm).toSet
      case _ => Set.empty

    def fromFormula(f: BoundFormula): Set[MitigationSelection] = f match
      case BoundFormula.Atom(a)      => a.args.flatMap(fromTerm).toSet
      case BoundFormula.Not(p)       => fromFormula(p)
      case BoundFormula.And(p, q)    => fromFormula(p) ++ fromFormula(q)
      case BoundFormula.Or(p, q)     => fromFormula(p) ++ fromFormula(q)
      case BoundFormula.Imp(p, q)    => fromFormula(p) ++ fromFormula(q)
      case BoundFormula.Iff(p, q)    => fromFormula(p) ++ fromFormula(q)
      case BoundFormula.Forall(_, b) => fromFormula(b)
      case BoundFormula.Exists(_, b) => fromFormula(b)
      case BoundFormula.True | BoundFormula.False => Set.empty

    fromFormula(bound.range) ++ fromFormula(bound.scope)
