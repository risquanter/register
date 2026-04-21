package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{WorkspaceId, WorkspaceKeySecret, TreeId}
import com.risquanter.register.domain.errors.{AppError, WorkspaceNotFound, WorkspaceExpired, TreeNotInWorkspace}

/** Workspace lifecycle service — association/token index.
  *
  * Maps capability-key hashes to durable workspace metadata and sets of TreeIds.
  * Does NOT store or duplicate tree data. The raw workspace key is consumed
  * at the HTTP/controller layer and reduced to a hash for store lookup.
  *
  * Security features baked into the trait contract:
  * - A5:  `delete()` and `rotate()` — revocation and key rotation
  * - A10: `resolve()` updates lastAccessedAt on success
  * - A11: `resolve()` implements dual timeout (absolute + idle)
  * - A13: All error variants map to opaque 404 at HTTP layer
  * - A14: Implementations must use O(1) lookup (Map.get / indexed query)
  */
trait WorkspaceStore:
  /** Create a new workspace with the configured TTL.
    * Security: logs creation event (A29).
    */
  def create(): UIO[WorkspaceKeySecret]

  /** Associate a tree with a workspace. Fails if workspace expired or not found. */
  def addTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit]

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op. */
  def removeTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit]

  /** List all tree IDs in a workspace. Fails if expired or not found. */
  def listTrees(key: WorkspaceKeySecret): IO[AppError, List[TreeId]]

  /** Resolve a workspace. Fails with WorkspaceExpired or WorkspaceNotFound.
    * Implements dual timeout: absolute (createdAt + ttl) AND idle (lastAccessedAt + idleTimeout).
    * Updates lastAccessedAt on successful resolution (A10).
    * Constant response for not-found vs expired at HTTP layer (A13).
    */
  def resolve(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord]

  /** Resolve a workspace by stable identity. */
  def resolveById(id: WorkspaceId): IO[AppError, WorkspaceRecord]

  /** Resolve a workspace, verify tree membership, and return the workspace.
    *
    * Fails with `TreeNotInWorkspace` when the tree does not belong to the
    * resolved workspace.
    */
  def resolveTreeWorkspace(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, WorkspaceRecord] =
    for
      ws <- resolve(key)
      _  <- ZIO.unless(ws.trees.contains(treeId))(ZIO.fail(TreeNotInWorkspace(key, treeId)))
    yield ws

  /** Check if a tree belongs to a workspace. Lazy TTL check included. */
  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean]

  /** Resolve workspace + verify tree ownership.
    *
    * Composed from `resolve` and `belongsTo` — fails with
    * `TreeNotInWorkspace` (opaque 404 via A13) when the tree does
    * not belong to the workspace.
    */
  def resolveTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit] =
    resolveTreeWorkspace(key, treeId).unit

  /** Evict all expired workspaces (absolute + idle). Returns evicted entries.
    *
    * Returns the full `Map[WorkspaceId, WorkspaceRecord]` of evicted entries so that
    * callers (e.g. `WorkspaceReaper`) can cascade-delete associated trees. Count is
    * derivable via `.size`.
    *
    * Called by both the background reaper fiber and the admin endpoint.
    * Security: logs eviction events (A31).
    */
  def evictExpired: UIO[Map[WorkspaceId, WorkspaceRecord]]

  /** Hard delete. Removes workspace from the store.
    * Tree cascade-deletion is orchestrated by the controller (Option B).
    * Security: logs deletion event (A29).
    */
  def delete(key: WorkspaceKeySecret): IO[AppError, Unit]

  /** Atomic rotation. Generates new key, transfers all tree associations,
    * instantly invalidates old key. No grace period — old key is immediately
    * dead, new key is immediately live.
    * Returns new key.
    * Security: logs rotation event (A29).
    */
  def rotate(key: WorkspaceKeySecret): IO[AppError, WorkspaceKeySecret]

object WorkspaceStore:
  // Accessor methods for ZIO service pattern
  def create(): ZIO[WorkspaceStore, Nothing, WorkspaceKeySecret] =
    ZIO.serviceWithZIO[WorkspaceStore](_.create())

  def resolve(key: WorkspaceKeySecret): ZIO[WorkspaceStore, AppError, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolve(key))

  def resolveById(id: WorkspaceId): ZIO[WorkspaceStore, AppError, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveById(id))

  def resolveTreeWorkspace(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveTreeWorkspace(key, treeId))

  def addTree(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.addTree(key, treeId))

  def removeTree(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.removeTree(key, treeId))

  def listTrees(key: WorkspaceKeySecret): ZIO[WorkspaceStore, AppError, List[TreeId]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.listTrees(key))

  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, Boolean] =
    ZIO.serviceWithZIO[WorkspaceStore](_.belongsTo(key, treeId))

  def resolveTree(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveTree(key, treeId))

  def evictExpired: ZIO[WorkspaceStore, Nothing, Map[WorkspaceId, WorkspaceRecord]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.evictExpired)

  def delete(key: WorkspaceKeySecret): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.delete(key))

  def rotate(key: WorkspaceKeySecret): ZIO[WorkspaceStore, AppError, WorkspaceKeySecret] =
    ZIO.serviceWithZIO[WorkspaceStore](_.rotate(key))
