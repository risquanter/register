package com.risquanter.register.services.cache

import zio.{UIO, NonEmptyChunk, ZIO}
import com.risquanter.register.domain.data.{RiskTree, ScopeOutcome}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash, MitigationId}
import com.risquanter.register.http.errors.BindProblem
import com.risquanter.register.http.responses.MitigationScopeView

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

  /** Wire form: one view per mitigation, in identifier order. Use only at the
    * wire boundary. */
  def toWire: List[MitigationScopeView] =
    outcomes.toList.sortBy((id, _) => id.value).map(MitigationScopeView(_, _))

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
