package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{WorkspaceId, WorkspaceKeySecret, TreeId, SeedEntityId}
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
    *
    * `seedEntityId` is the workspace's stochastic identity (HDR Entity axis,
    * PLAN-SEED-IDENTITY §5.2). `None` assigns the next value from the store's
    * monotonic counter (contract responsibility, per backend: Postgres sequence /
    * fixed-base in-memory counter — §12.2). `Some(v)` provides it explicitly;
    * fails with ValidationFailed(DUPLICATE_VALUE) when a live workspace already
    * holds `v`, and bumps the counter past `v` so later assignments cannot collide.
    *
    * Security: logs creation event (A29).
    */
  def create(seedEntityId: Option[SeedEntityId.SeedEntityId] = None): IO[AppError, WorkspaceKeySecret]

  /** Associate a tree with a workspace. Fails if workspace expired or not found. */
  def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit]

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op. */
  def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit]

  /** List all tree IDs in a workspace. Fails if expired or not found. */
  def listTrees(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, List[TreeId]]

  /** Resolve a workspace. Fails with WorkspaceExpired or WorkspaceNotFound.
    * Implements dual timeout: absolute (createdAt + ttl) AND idle (lastAccessedAt + idleTimeout).
    * Updates lastAccessedAt on successful resolution (A10).
    * Constant response for not-found vs expired at HTTP layer (A13).
    */
  def resolve(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord]

  /** Resolve a workspace by stable identity.
    *
    * ⚠️ SECURITY WARNING — no capability check. This method authenticates
    * nothing: it trusts the caller's `WorkspaceId` outright, unlike every other
    * method on this trait, which requires presenting a valid `WorkspaceKeySecret`
    * first. `WorkspaceId` is an internal identifier — it is never returned in any
    * API response today, but it is also not designed to be unguessable (it is a
    * ULID, not a `SecureRandom` capability token), so treat it as a plain
    * database key, not a credential.
    *
    * NEVER call this with a `WorkspaceId` that originated from client input
    * (a path/query/header/body value, or anything derived from one). Doing so
    * is a direct object-level-authorization bypass (OWASP API1:2023 Broken
    * Object Level Authorization / IDOR): any caller who can name or guess a
    * `WorkspaceId` would get that workspace's record with no proof they were
    * ever issued its key.
    *
    * Existing callers only ever use `WorkspaceId` values already resolved
    * server-side from an authenticated key earlier in the same call chain
    * (internal reconciliation, tests) — never a value taken directly from a
    * request. As of 2026-07-20 this method has no controller call sites at all.
    *
    * If a future feature needs to look up a workspace by ID from a
    * caller-supplied value, that is a Decision Trigger (new authorization
    * surface) — stop and ask before wiring it to any endpoint; do not add a
    * direct call from a controller without an explicit ownership/capability
    * check at that boundary.
    */
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
    * Returns the full list of evicted `WorkspaceRecord`s so that callers
    * (e.g. `WorkspaceReaper`) can cascade-delete associated trees. Count is
    * derivable via `.size`.
    *
    * Called by both the background reaper fiber and the admin endpoint.
    * Security: logs eviction events (A31).
    */
  def evictExpired: UIO[List[WorkspaceRecord]]

  /** Hard delete. Removes workspace from the store.
    * Tree cascade-deletion is orchestrated by the controller (Option B).
    * Security: logs deletion event (A29).
    */
  def delete(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit]

  /** Atomic rotation. Generates new key, transfers all tree associations,
    * instantly invalidates old key. No grace period — old key is immediately
    * dead, new key is immediately live.
    * Returns new key.
    * Security: logs rotation event (A29).
    */
  def rotate(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, WorkspaceKeySecret]

object WorkspaceStore:
  // Accessor methods for ZIO service pattern
  def create(seedEntityId: Option[SeedEntityId.SeedEntityId] = None): ZIO[WorkspaceStore, AppError, WorkspaceKeySecret] =
    ZIO.serviceWithZIO[WorkspaceStore](_.create(seedEntityId))

  def resolve(key: WorkspaceKeySecret): ZIO[WorkspaceStore, AppError, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolve(key))

  def resolveById(id: WorkspaceId): ZIO[WorkspaceStore, AppError, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveById(id))

  def resolveTreeWorkspace(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, WorkspaceRecord] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveTreeWorkspace(key, treeId))

  def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using p: com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.addTree(key, treeId))

  def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using p: com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.removeTree(key, treeId))

  def listTrees(key: WorkspaceKeySecret)(using p: com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): ZIO[WorkspaceStore, AppError, List[TreeId]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.listTrees(key))

  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, Boolean] =
    ZIO.serviceWithZIO[WorkspaceStore](_.belongsTo(key, treeId))

  def resolveTree(key: WorkspaceKeySecret, treeId: TreeId): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.resolveTree(key, treeId))

  def evictExpired: ZIO[WorkspaceStore, Nothing, List[WorkspaceRecord]] =
    ZIO.serviceWithZIO[WorkspaceStore](_.evictExpired)

  def delete(key: WorkspaceKeySecret)(using p: com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): ZIO[WorkspaceStore, AppError, Unit] =
    ZIO.serviceWithZIO[WorkspaceStore](_.delete(key))

  def rotate(key: WorkspaceKeySecret)(using p: com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): ZIO[WorkspaceStore, AppError, WorkspaceKeySecret] =
    ZIO.serviceWithZIO[WorkspaceStore](_.rotate(key))
