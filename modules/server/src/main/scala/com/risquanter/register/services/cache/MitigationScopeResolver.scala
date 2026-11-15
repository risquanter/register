package com.risquanter.register.services.cache

import zio.{UIO, NonEmptyChunk, ZIO}
import com.risquanter.register.domain.data.{RiskTree, Mitigation, MitigationTarget, MitigationSpec, LeafStageSpec, ScopeOutcome, TargetingPredicate}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash, MitigationId}
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase.given   // Extract[NodeId]
import com.risquanter.register.http.errors.BindProblem
import com.risquanter.register.http.responses.MitigationScopeEntry

import vql.typed.{QueryBinder, TypedSemantics, extract}
import vql.error.TypeCheckError
import _root_.parser.FOLParser
import _root_.logic.FOLUtil

/** Names the tree version whose scopes are resolved. The owning workspace is NOT
  * a field: one resolver instance exists per workspace
  * (`MitigationScopeResolverRegistry`, mirroring `ContentCacheRegistry`), so the
  * workspace is the instance. Inside it the memo is keyed by (treeId, branch);
  * `revision` is stored in the entry and checked on read, not part of the key.
  * `revision` is the byte-level Irmin commit hash, never the domain content hash
  * — predicates reference node names, which the content hash omits, so a rename
  * changes resolution but not the content hash.
  */
final case class ScopeResolutionContext(treeId: TreeId, branch: BranchRef, revision: CommitHash)

/** The per-mitigation outcome map for one tree version. Total over
  * `tree.mitigations`: every mitigation has an entry (its `MitigationTarget` is
  * always a `Predicate`).
  */
final case class ResolvedScopes(outcomes: Map[MitigationId, ScopeOutcome]):
  /** Success projection consumed by `MitigationApplication.scoped` /
    * `effectiveTree`: each mitigation's applied scope, empty for a failed one.
    * This is the `Map[MitigationId, Set[NodeId]]` the resolver edge passes into
    * the pure algebra. */
  def appliedScopes: Map[MitigationId, Set[NodeId]] =
    outcomes.view.mapValues(_.scopeOrEmpty).toMap

  def failures: Map[MitigationId, NonEmptyChunk[BindProblem]] =
    outcomes.collect { case (id, ScopeOutcome.Failed(errs)) => id -> errs }

  /** Wire form: one entry per mitigation, in identifier order. Use only at the
    * wire boundary. */
  def toWire: List[MitigationScopeEntry] =
    outcomes.toList.sortBy((id, _) => id.value).map(MitigationScopeEntry(_, _))

object ResolvedScopes:
  /** Per-mitigation drift signals (ADR-002): a predicate that no longer binds
    * makes that one mitigation a no-op, never a request failure, so this only
    * logs. */
  def logFailures(resolved: ResolvedScopes): UIO[Unit] =
    ZIO.when(resolved.failures.nonEmpty)(
      ZIO.logWarning(
        s"MitigationScopeResolver: ${resolved.failures.size} mitigation(s) did not " +
        s"resolve against this tree version: " +
        resolved.failures.map((id, errs) => s"${id.value} -> ${errs.map(BindProblem.describe).mkString("[", "; ", "]")}").mkString("; ")
      )
    ).unit

trait MitigationScopeResolver:
  /** Resolve every mitigation's predicate to its applied node-id scope against
    * `tree`, memoized per tree version. `tree` must be the tree at
    * `context.revision`; the context names which version for cache identity. No
    * error channel (`UIO`): every per-mitigation bind failure is isolated into
    * the outcome map, and the results-free KB build plus the in-memory memo
    * cannot fault. A state a stored predicate cannot reach is a defect. */
  def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes]

object MitigationScopeResolver:
  /** Resolve every mitigation's predicate against `tree` without consulting or
    * writing the memo. The write path has neither a workspace-scoped resolver
    * instance nor the commit hash a memo entry is keyed by, and resolution is
    * cheap to repeat: one knowledge-base build plus one quantifier-free
    * evaluation per mitigation.
    *
    * The knowledge base is results-free and scopes-free: the targeting
    * sublanguage admits no simulation symbol and no mitigation predicate, so an
    * empty result map and empty resolved scopes are correct and make the
    * resolved scopes a pure function of the tree version. */
  def resolveUncached(tree: RiskTree): ResolvedScopes =
    val kb = RiskTreeKnowledgeBase(RiskTreeKnowledgeBase.schemaFor(tree), Map.empty, Map.empty)
    ResolvedScopes(tree.mitigations.map(m => m.id -> resolveOne(m, tree, kb)).toMap)

  private def resolveOne(m: Mitigation, tree: RiskTree, kb: RiskTreeKnowledgeBase): ScopeOutcome =
    val predicate = m.target match { case MitigationTarget.Predicate(p) => p }
    val domain: Set[NodeId] = m.spec match          // stage-domain restriction
      case _: LeafStageSpec              => tree.index.leafIds
      case _: MitigationSpec.ResultStage => tree.index.nodes.keySet
    satisfyingIds(predicate, kb) match
      case Right(ids)     => ScopeOutcome.Resolved(ids intersect domain)
      case Left(failures) => ScopeOutcome.Failed(failures)

  /** Re-parse (source is the only stored form) → bind against this tree version's
    * catalog → evaluate to the exact satisfying set → lift each `Value` to its
    * `NodeId`. A stored predicate names a node by identifier, so a renamed node
    * still binds and a deleted node's identifier binds and matches nothing. Bind
    * fails only when the catalog changed under a stored predicate, and that
    * failure is the outcome. A predicate `TargetingPredicate.create`
    * accepted re-parses, has one free variable and evaluates once bound, so a
    * failure there, and a bind error that does not translate, is a defect and
    * throws (ADR-033). */
  private def satisfyingIds(
    predicate: TargetingPredicate, kb: RiskTreeKnowledgeBase
  ): Either[NonEmptyChunk[BindProblem], Set[NodeId]] =
    FOLParser.parse(predicate.source) match
      case Left(pe) =>
        defect(s"a stored targeting predicate does not re-parse: ${pe.getClass.getSimpleName}")
      case Right(formula) =>
        FOLUtil.fvFOL(formula).distinct match
          case variable :: Nil =>
            QueryBinder.bindSatisfyingFormula(formula, variable, kb.catalog) match
              case Left(Nil) =>
                defect("the binder refused a targeting predicate with no error")
              case Left(head :: tail) =>
                Left(NonEmptyChunk(bindProblem(head), tail.map(bindProblem)*))
              case Right((bound, boundVar)) =>
                TypedSemantics.satisfyingSet(bound, boundVar, kb.model) match
                  case Left(qe)      => defect(s"a bound targeting predicate failed to evaluate: ${qe.getClass.getSimpleName}")
                  case Right(values) => Right(values.flatMap(_.extract[NodeId].toOption))
          case _ =>
            defect("a stored targeting predicate does not have exactly one free variable")

  private def bindProblem(error: TypeCheckError): BindProblem =
    BindProblem.fromTypeCheckError(error).fold(reason => defect(s"a targeting predicate's bind error does not translate: $reason"), identity)

  private def defect(reason: String): Nothing =
    throw new IllegalStateException(reason)
