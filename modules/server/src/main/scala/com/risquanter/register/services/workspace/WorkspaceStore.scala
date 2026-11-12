package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.{Expiry, WorkspaceRecord}
import com.risquanter.register.domain.data.iron.{NonNegativeInt, PositiveInt, WorkspaceKeySecret, TreeId, SeedEntityId}
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, ErrorSubject, ExpiryDetail, Failure, FieldName, FieldProblem, TrustZone,
  ValidationError
}
import com.risquanter.register.http.errors.NotFoundKind

/** Workspace lifecycle service — association/token index.
  *
  * Maps capability-key hashes to durable workspace metadata and sets of TreeIds.
  * Does NOT store or duplicate tree data. The raw workspace key is consumed
  * at the HTTP/controller layer and reduced to a hash for store lookup.
  *
  * Security properties the trait contract carries:
  * - `delete()` and `rotate()` give revocation and key rotation
  * - `resolve()` updates lastAccessedAt on success
  * - `resolve()` applies both timeouts, absolute and idle
  * - every refusal of a key or a tree encodes to one opaque 404, so a caller
  *   cannot tell which of them it was
  * - a backing store that does not answer fails with `Failure.Unanswered`
  * - implementations look up in constant time (`Map.get`, or an indexed query)
  */
trait WorkspaceStore:
  /** Create a new workspace with the configured TTL.
    *
    * `seedEntityId` is the workspace's stochastic identity on the HDR Entity
    * axis. `None` assigns the next value from the store's monotonic counter,
    * which each backend owns: a Postgres sequence, or a fixed-base in-memory
    * counter. `Some(v)` provides it explicitly;
    * fails with the caller's `Rejected` naming `SeedEntityIdInUse` when a live
    * workspace already holds `v`, and bumps the counter past `v` so later assignments cannot collide.
    *
    * Logs a creation event.
    */
  def create(seedEntityId: Option[SeedEntityId.SeedEntityId]): IO[Failure, WorkspaceKeySecret]

  /** Associate a tree with a workspace. Fails if the workspace is expired or not
    * found, or already holds the configured maximum number of trees.
    * Re-associating a tree it already holds succeeds at the maximum.
    *
    * Implementations resolve, check and write atomically.
    */
  def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using Checked[Authority]): IO[Failure, Unit]

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op. */
  def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using Checked[Authority]): IO[Failure, Unit]

  /** List all tree IDs in a workspace. Fails if expired or not found. */
  def listTrees(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, List[TreeId]]

  /** Resolve a workspace. Fails with `workspaceExpired` or `workspaceNotFound`.
    * Implements dual timeout: absolute (createdAt + ttl) AND idle (lastAccessedAt + idleTimeout).
    * Updates lastAccessedAt on successful resolution. Not-found and expired
    * encode to one constant response at the HTTP layer, so neither is
    * distinguishable by a caller.
    */
  def resolve(key: WorkspaceKeySecret): IO[Failure, WorkspaceRecord]

  /** Fails when the workspace already holds the configured maximum number of
    * trees, before any tree is written.
    *
    * `addTree` enforces the same rule, but by then a newly created tree exists
    * in storage, and refusing its association would leave it unreachable:
    * absent from `trees`, so invisible to `listTrees`, refused by
    * `resolveTreeWorkspace`, and skipped by the reaper's cascade. A caller that
    * creates a tree checks here first.
    */
  def checkTreeCapacity(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, Unit]

  /** Resolve a workspace, verify tree membership, and return the workspace.
    *
    * Fails with the caller's `NotFound` when the tree does not belong to the
    * resolved workspace, the same answer as for a key that resolves to nothing.
    */
  def resolveTreeWorkspace(key: WorkspaceKeySecret, treeId: TreeId): IO[Failure, WorkspaceRecord] =
    for
      ws <- resolve(key)
      _  <- ZIO.unless(ws.trees.contains(treeId))(ZIO.fail(Failure.NotFound(Core(
              TrustZone.Client,
              NonEmptyChunk(ErrorLocation.requestField(FieldName.Id) -> NotFoundKind.Unknown()),
              List(Diagnostic.WorkspaceRef(ws.id), Diagnostic.TreeRef(treeId))
            ))))
    yield ws

  /** Check if a tree belongs to a workspace. Lazy TTL check included. */
  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[Failure, Boolean]

  /** Resolve workspace + verify tree ownership.
    *
    * Composed from `resolveTreeWorkspace`, so it fails with the same caller's
    * `NotFound` when the tree does not belong to the workspace.
    */
  def resolveTree(key: WorkspaceKeySecret, treeId: TreeId): IO[Failure, Unit] =
    resolveTreeWorkspace(key, treeId).unit

  /** Evict all expired workspaces (absolute + idle). Returns evicted entries.
    *
    * Returns the full list of evicted `WorkspaceRecord`s so that callers
    * (e.g. `WorkspaceReaper`) can cascade-delete associated trees. Count is
    * derivable via `.size`.
    *
    * Called by both the background reaper fiber and the admin endpoint.
    * Logs an eviction event.
    *
    * Fails rather than dying when the backing store is unreachable, so the
    * reaper can log and retry on its next tick instead of its fiber ending.
    */
  def evictExpired: IO[Failure, List[WorkspaceRecord]]

  /** Hard delete. Removes workspace from the store. Tree cascade-deletion is
    * orchestrated by the controller. Logs a deletion event.
    */
  def delete(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, Unit]

  /** Atomic rotation. Generates new key, transfers all tree associations,
    * instantly invalidates old key. No grace period — old key is immediately
    * dead, new key is immediately live.
    * Returns new key. Logs a rotation event.
    */
  def rotate(key: WorkspaceKeySecret)(using Checked[Authority]): IO[Failure, WorkspaceKeySecret]

object WorkspaceStore:
  /** The security events every store implementation logs, carried under the
    * `event_type` annotation. The spellings are a contract with the log
    * pipeline, so they are declared rather than derived from the case names.
    */
  enum SecurityEvent(val eventType: String):
    case Created  extends SecurityEvent("workspace.created")
    case Deleted  extends SecurityEvent("workspace.deleted")
    case Rotated  extends SecurityEvent("workspace.rotated")
    case Eviction extends SecurityEvent("workspace.eviction")

  /** Annotation keys of a security log line. Spellings are a contract with the
    * log pipeline.
    */
  val EventTypeKey: String    = "event_type"
  val WorkspaceIdKey: String  = "workspace_id"
  val EvictedCountKey: String = "evicted_count"

  // Accessor methods for ZIO service pattern
  def create(seedEntityId: Option[SeedEntityId.SeedEntityId]): ZIO[WorkspaceStore, Failure, WorkspaceKeySecret] =
    ZIO.serviceWithZIO[WorkspaceStore](_.create(seedEntityId))

  def resolve(key: WorkspaceKeySecret): ZIO[WorkspaceStore, Failure, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolve(key))

  def checkTreeCapacity(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, Failure, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.checkTreeCapacity(key))

  /** The caller's NotFound for a workspace key that resolves to no workspace. */
  val workspaceNotFound: Failure =
    Failure.NotFound(Core(
      TrustZone.Client,
      NonEmptyChunk(ErrorLocation.requestField(FieldName.WorkspaceKey) -> NotFoundKind.Unknown()),
      List(Diagnostic.KeyUnresolved)
    ))

  /** The caller's Expired for a workspace past the deadline `expiry` names. */
  def workspaceExpired(ws: WorkspaceRecord, expiry: Expiry): Failure =
    Failure.Expired(Core(
      TrustZone.Client,
      NonEmptyChunk(ErrorLocation.requestField(FieldName.WorkspaceKey) -> ExpiryDetail(expiry.cause, ws.ttl, ws.idleTimeout)),
      List(Diagnostic.WorkspaceRef(ws.id), Diagnostic.ExpiredAt(expiry.at))
    ))

  /** The refusal a workspace already holding `limit` trees produces. A value
    * rather than a function of a record, because a backend deciding capacity
    * inside a transaction holds the count there and no record.
    */
  def maxTreesReached(limit: PositiveInt): Failure =
    Failure.rejected(
      TrustZone.Client,
      NonEmptyChunk(ValidationError(
        ErrorLocation.Field(ErrorSubject.Workspace, FieldName.Trees),
        FieldProblem.MaxTreesReached(NonNegativeInt.count(limit))
      )),
      Nil
    )

  def treeCapacityForNew(ws: WorkspaceRecord, limit: PositiveInt): Either[Failure, Unit] =
    Either.cond(ws.trees.size < limit, (), maxTreesReached(limit))

  /** Refuses a new tree association once the workspace holds the configured
    * maximum. Re-associating a tree the workspace already holds is always
    * allowed: the association is a set, so it changes nothing.
    */
  def treeCapacity(ws: WorkspaceRecord, treeId: TreeId, limit: PositiveInt): Either[Failure, Unit] =
    if ws.trees.contains(treeId) then Right(())
    else treeCapacityForNew(ws, limit)

  def resolveTreeWorkspace(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, Failure, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveTreeWorkspace(key, treeId))

  def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using p: Checked[Authority]): ZIO[WorkspaceStore, Failure, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.addTree(key, treeId))

  def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using p: Checked[Authority]): ZIO[WorkspaceStore, Failure, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.removeTree(key, treeId))

  def listTrees(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, Failure, List[TreeId]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.listTrees(key))

  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, Failure, Boolean] =
    ZIO.serviceWithZIO[WorkspaceStore](_.belongsTo(key, treeId))

  def resolveTree(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, Failure, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveTree(key, treeId))

  def evictExpired: ZIO[WorkspaceStore, Failure, List[WorkspaceRecord]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.evictExpired)

  def delete(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, Failure, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.delete(key))

  def rotate(key: WorkspaceKeySecret)(using p: Checked[Authority]): ZIO[WorkspaceStore, Failure, WorkspaceKeySecret] =
    ZIO.serviceWithZIO[WorkspaceStore](_.rotate(key))
