package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.domain.data.Workspace
import com.risquanter.register.domain.data.iron.{WorkspaceKey, TreeId}
import com.risquanter.register.domain.errors.{AppError, WorkspaceNotFound, WorkspaceExpired, TreeNotInWorkspace}

/** Workspace lifecycle service — association/token index.
  *
  * Maps capability keys to sets of TreeIds. Does NOT store or duplicate
  * tree data. The workspace key is consumed at the HTTP/controller layer
  * and does not propagate into tree storage paths.
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
  def create(): UIO[WorkspaceKey]

  /** Associate a tree with a workspace. Fails if workspace expired or not found. */
  def addTree(key: WorkspaceKey, treeId: TreeId): IO[AppError, Unit]

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op. */
  def removeTree(key: WorkspaceKey, treeId: TreeId): IO[AppError, Unit]

  /** List all tree IDs in a workspace. Fails if expired or not found. */
  def listTrees(key: WorkspaceKey): IO[AppError, List[TreeId]]

  /** Resolve a workspace. Fails with WorkspaceExpired or WorkspaceNotFound.
    * Implements dual timeout: absolute (createdAt + ttl) AND idle (lastAccessedAt + idleTimeout).
    * Updates lastAccessedAt on successful resolution (A10).
    * Constant response for not-found vs expired at HTTP layer (A13).
    */
  def resolve(key: WorkspaceKey): IO[AppError, Workspace]

  /** Check if a tree belongs to a workspace. Lazy TTL check included. */
  def belongsTo(key: WorkspaceKey, treeId: TreeId): IO[AppError, Boolean]

  /** Evict all expired workspaces (absolute + idle). Returns count evicted.
    * Called by both the background reaper fiber and the admin endpoint.
    * Security: logs eviction events (A31).
    */
  def evictExpired: UIO[Int]

  /** Hard delete. Removes workspace from the store.
    * Tree cascade-deletion is orchestrated by the controller (Option B).
    * Security: logs deletion event (A29).
    */
  def delete(key: WorkspaceKey): IO[AppError, Unit]

  /** Atomic rotation. Generates new key, transfers all tree associations,
    * instantly invalidates old key. No grace period — old key is immediately
    * dead, new key is immediately live.
    * Returns new key.
    * Security: logs rotation event (A29).
    */
  def rotate(key: WorkspaceKey): IO[AppError, WorkspaceKey]

object WorkspaceStore:
  // Accessor methods for ZIO service pattern
  def create(): ZIO[WorkspaceStore, Nothing, WorkspaceKey] =
    ZIO.serviceWithZIO[WorkspaceStore](_.create())

  def resolve(key: WorkspaceKey): ZIO[WorkspaceStore, AppError, Workspace] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolve(key))

  def addTree(key: WorkspaceKey, treeId: TreeId): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.addTree(key, treeId))

  def removeTree(key: WorkspaceKey, treeId: TreeId): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.removeTree(key, treeId))

  def listTrees(key: WorkspaceKey): ZIO[WorkspaceStore, AppError, List[TreeId]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.listTrees(key))

  def belongsTo(key: WorkspaceKey, treeId: TreeId): ZIO[WorkspaceStore, AppError, Boolean] =
    ZIO.serviceWithZIO[WorkspaceStore](_.belongsTo(key, treeId))

  def evictExpired: ZIO[WorkspaceStore, Nothing, Int] =
    ZIO.serviceWithZIO[WorkspaceStore](_.evictExpired)

  def delete(key: WorkspaceKey): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.delete(key))

  def rotate(key: WorkspaceKey): ZIO[WorkspaceStore, AppError, WorkspaceKey] =
    ZIO.serviceWithZIO[WorkspaceStore](_.rotate(key))
