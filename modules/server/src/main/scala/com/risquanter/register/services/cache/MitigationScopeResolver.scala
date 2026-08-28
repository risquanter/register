package com.risquanter.register.services.cache

import zio.{UIO, NonEmptyChunk}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash, MitigationId}

/** Names the tree version whose scopes are resolved. The owning workspace is NOT
  * a field: one resolver instance exists per workspace (`ScopeResolverScope`,
  * mirroring `CacheScope`), so the workspace IS the instance and the memo key
  * inside it is exactly (treeId, branch, revision). `revision` is the byte-level
  * Irmin commit hash, never the domain content hash — predicates reference node
  * names, which the content hash omits, so a rename changes resolution but not
  * the content hash.
  */
final case class ScopeResolutionContext(treeId: TreeId, branch: BranchRef, revision: CommitHash)

/** Why one mitigation's predicate did not resolve against this tree version. The
  * user-actionable distinctions — a renamed/deleted node, a malformed id, a type
  * error — stay separate; the structural and internal faults a create-validated
  * predicate should never reach are collapsed, because they carry no end-user
  * action.
  */
enum ScopeResolutionFailure:
  /** A `named` / bare-`Node`-slot node name no longer resolves to a tree node. */
  case UnknownNode(reference: String)
  /** A `has_id` literal is not a well-formed node id. */
  case MalformedNodeId(reference: String)
  /** A symbol used at an incompatible or conflicting sort. */
  case TypeError(detail: String)
  /** A structural mismatch against the current catalog vocabulary. */
  case MalformedPredicate(detail: String)
  /** A re-parse or evaluation fault a bound predicate should never reach. */
  case InternalError(detail: String)

/** Per-mitigation resolution outcome: a coproduct with `toEither` and wrapper
  * projections only — no `map`/`flatMap`; it is a result, not a pipeline.
  * `Failed` still contributes an empty applied scope (a stale predicate is a
  * no-op for that mitigation, never a whole-request failure), so `scopeOrEmpty`
  * is what the application algebra consumes and `failures` is the per-mitigation
  * drift signal.
  */
enum ScopeOutcome:
  case Resolved(scope: Set[NodeId])
  case Failed(errors: NonEmptyChunk[ScopeResolutionFailure])

  def toEither: Either[NonEmptyChunk[ScopeResolutionFailure], Set[NodeId]] = this match
    case Resolved(scope) => Right(scope)
    case Failed(errs)    => Left(errs)

  def scopeOrEmpty: Set[NodeId] = this match
    case Resolved(scope) => scope
    case Failed(_)       => Set.empty

  def failures: Option[NonEmptyChunk[ScopeResolutionFailure]] = this match
    case Resolved(_)  => None
    case Failed(errs) => Some(errs)

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

  def failures: Map[MitigationId, NonEmptyChunk[ScopeResolutionFailure]] =
    outcomes.collect { case (id, ScopeOutcome.Failed(errs)) => id -> errs }

trait MitigationScopeResolver:
  /** Resolve every mitigation's predicate to its applied node-id scope against
    * `tree`, memoized per tree version. `tree` must be the tree at
    * `context.revision`; the context names which version for cache identity. No
    * error channel (`UIO`): every per-mitigation failure is isolated into the
    * outcome map, and the results-free KB build plus the in-memory memo cannot
    * fault. */
  def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes]
