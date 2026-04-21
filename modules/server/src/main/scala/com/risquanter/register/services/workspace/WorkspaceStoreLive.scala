package com.risquanter.register.services.workspace

import zio.*
import java.time.Instant
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, WorkspaceNotFound, WorkspaceExpired}
import com.risquanter.register.configs.WorkspaceConfig
import com.risquanter.register.util.IdGenerators

/** In-memory Ref-based WorkspaceStore implementation.
  *
  * Security features:
  * - A11: Dual timeout (absolute + idle) in resolve()
  * - A14: O(1) lookup via Map.get — constant-time, no early-return timing branches
  * - A29/A33: Structured security event logging on create, delete, rotate, evict
  *
  * Data is ephemeral — lost on server restart (acceptable for free-tier).
  */
final class WorkspaceStoreLive private (
  ref: Ref[WorkspaceStoreLive.State],
  config: WorkspaceConfig
) extends WorkspaceStore:

  // ── Logging helper (eliminates nested ZIO.logAnnotate repetition) ─────

  /** Structured security event log with arbitrary key-value annotations. */
  private def logSecurity(eventType: String, fields: (String, String)*)(msg: String): UIO[Unit] =
    val allAnnotations = ("event_type" -> eventType) +: fields
    allAnnotations.foldRight(ZIO.logInfo(msg): UIO[Unit]) { case ((k, v), effect) =>
      ZIO.logAnnotate(k, v)(effect)
    }

  private def logSecurityWarning(eventType: String, fields: (String, String)*)(msg: String): UIO[Unit] =
    val allAnnotations = ("event_type" -> eventType) +: fields
    allAnnotations.foldRight(ZIO.logWarning(msg): UIO[Unit]) { case ((k, v), effect) =>
      ZIO.logAnnotate(k, v)(effect)
    }

  // ── Pure validation (shared by resolveInternal and rotate) ────────────

  /** Pure workspace validation: O(1) lookup + dual timeout check.
    *
    * A14: Map.get is O(1). Both not-found and expired do identical work
    * (lookup + instant comparison), preventing timing side-channels.
    */
  private def validateWorkspace(
    map: Map[WorkspaceKeyHash, WorkspaceRecord],
    keyHash: WorkspaceKeyHash,
    key: WorkspaceKeySecret,
    now: Instant
  ): Either[AppError, WorkspaceRecord] =
    map.get(keyHash) match
      case None                          => Left(WorkspaceNotFound(key))
      case Some(ws) if ws.isExpired(now) => Left(WorkspaceExpired(key, ws.createdAt, ws.ttl))
      case Some(ws)                      => Right(ws)

  // ── Public API ────────────────────────────────────────────────────────

  /** Create a new workspace with configured TTL and idle timeout. (A29: logs creation) */
  override def create(): UIO[WorkspaceKeySecret] =
    for
      key <- WorkspaceKeySecret.generate
      sid <- IdGenerators.nextId.orDie
      now <- Clock.instant
      keyHash = WorkspaceKeyHash.fromSecret(key)
      workspace = WorkspaceRecord(
        id = WorkspaceId(sid),
        keyHash = keyHash,
        trees = Set.empty,
        createdAt = now,
        lastAccessedAt = now,
        ttl = config.ttl,
        idleTimeout = config.idleTimeout
      )
      _ <- ref.update { state =>
             state.copy(
               byHash = state.byHash + (keyHash -> workspace),
               byId = state.byId + (workspace.id -> keyHash)
             )
           }
      _ <- logSecurity("workspace.created", "workspace_id" -> workspace.id.value)("Workspace created")
    yield key

  /** Associate a tree with a workspace.
    *
    * Note: resolve + update are two separate Ref operations. This is safe because
    * addTree is append-only — a concurrent delete between resolve and update simply
    * means the updatedWith finds None and the no-op map produces no change.
    * Making this atomic via Ref.modify would add complexity for no practical gain.
    */
  override def addTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit] =
    for
      _       <- resolveInternal(key)
      keyHash  = WorkspaceKeyHash.fromSecret(key)
      _ <- ref.update(state =>
             state.copy(byHash = state.byHash.updatedWith(keyHash)(_.map(w => w.copy(trees = w.trees + treeId))))
           )
    yield ()

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op.
    * Same non-atomic resolve + update pattern as addTree (see justification above).
    */
  override def removeTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit] =
    for
      _       <- resolveInternal(key)
      keyHash  = WorkspaceKeyHash.fromSecret(key)
      _ <- ref.update(state =>
             state.copy(byHash = state.byHash.updatedWith(keyHash)(_.map(w => w.copy(trees = w.trees - treeId))))
           )
    yield ()

  /** List all tree IDs in a workspace. */
  override def listTrees(key: WorkspaceKeySecret): IO[AppError, List[TreeId]] =
    resolveInternal(key).map(_.trees.toList)

  /** Resolve a workspace with dual timeout check (A11) and access tracking (A10).
    *
    * Atomic: single Ref.modify validates + touches in one step.
    * No TOCTOU race between read and write.
    */
  override def resolve(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    for
      now     <- Clock.instant
      keyHash  = WorkspaceKeyHash.fromSecret(key)
      result <- ref.modify { map =>
        validateWorkspace(map.byHash, keyHash, key, now) match
          case Left(err) => (Left(err), map)
          case Right(ws) =>
            val touched = ws.touch(now)
            (Right(touched), map.copy(byHash = map.byHash.updated(keyHash, touched)))
      }
      ws <- ZIO.fromEither(result)
    yield ws

  /** Resolve by immutable workspace ID (delegates to key-based resolve). */
  override def resolveById(id: WorkspaceId): IO[AppError, WorkspaceRecord] =
    for
      now    <- Clock.instant
      result <- ref.modify { state =>
                  state.byId.get(id) match
                    case None =>
                      (Left(RepositoryFailure(s"Workspace id not found: ${id.value}")), state)
                    case Some(keyHash) =>
                      state.byHash.get(keyHash) match
                        case None =>
                          (Left(RepositoryFailure(s"Workspace record missing for id ${id.value}")), state)
                        case Some(ws) if ws.isExpired(now) =>
                          (Left(RepositoryFailure(s"Workspace expired for id ${id.value}")), state)
                        case Some(ws) =>
                          val touched = ws.touch(now)
                          (Right(touched), state.copy(byHash = state.byHash.updated(keyHash, touched)))
                }
      ws <- ZIO.fromEither(result)
    yield ws

  /** Check if a tree belongs to a workspace. */
  override def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean] =
    resolveInternal(key).map(_.trees.contains(treeId))

  /** Evict all expired workspaces. Returns evicted entries for cascade. (A31: logs eviction) */
  override def evictExpired: UIO[Map[WorkspaceId, WorkspaceRecord]] =
    for
      now     <- Clock.instant
      evicted <- ref.modify { map =>
        val (expired, aliveByHash) = map.byHash.partition((_, ws) => ws.isExpired(now))
        val expiredIds = expired.values.map(_.id).toSet
        val aliveById = map.byId.filter((id, _) => !expiredIds.contains(id))
        (expired.values.map(ws => ws.id -> ws).toMap, map.copy(byHash = aliveByHash, byId = aliveById))
      }
      _ <- logSecurity("workspace.eviction", "evicted_count" -> evicted.size.toString)(
             s"Workspace reaper: evicted ${evicted.size} expired workspaces"
           ).when(evicted.nonEmpty)
    yield evicted

  /** Hard delete. Removes workspace from the store. (A29: logs deletion)
    *
    * Note: resolve + remove are two separate Ref operations. This is safe because
    * delete is idempotent — a concurrent delete between resolve and update simply
    * removes a key that is already gone, which is a no-op on Map.
    */
  override def delete(key: WorkspaceKeySecret): IO[AppError, Unit] =
    for
      ws      <- resolveInternal(key)
      keyHash  = WorkspaceKeyHash.fromSecret(key)
      _ <- ref.update(state =>
             state.copy(
               byHash = state.byHash - keyHash,
               byId = state.byId - ws.id
             )
           )
      _ <- logSecurity("workspace.deleted", "workspace_id" -> ws.id.value)("Workspace deleted")
    yield ()

  /** Atomic rotation via single Ref.modify — no window where neither key works.
    * Reuses validateWorkspace for DRY validation. (A29: logs rotation)
    */
  override def rotate(key: WorkspaceKeySecret): IO[AppError, WorkspaceKeySecret] =
    for
      newKey <- WorkspaceKeySecret.generate
      now    <- Clock.instant
      oldHash  = WorkspaceKeyHash.fromSecret(key)
      newHash  = WorkspaceKeyHash.fromSecret(newKey)
      result <- ref.modify { map =>
        validateWorkspace(map.byHash, oldHash, key, now) match
          case Left(err) => (Left(err), map)
          case Right(ws) =>
            val rotated = ws.copy(keyHash = newHash, createdAt = now, lastAccessedAt = now)
            (Right(newKey), map.copy(
              byHash = (map.byHash - oldHash) + (newHash -> rotated),
              byId = map.byId.updated(ws.id, newHash)
            ))
      }
      newK  <- ZIO.fromEither(result)
      ws    <- resolve(newK)
      _     <- logSecurity("workspace.rotated", "workspace_id" -> ws.id.value)("Workspace key rotated")
    yield newK

  // ── Internal ──────────────────────────────────────────────────────────

  /** Internal resolve without lastAccessedAt update — used by addTree, listTrees, etc.
    * Logs on failure for security audit (A29/A33).
    */
  private def resolveInternal(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    for
      now     <- Clock.instant
      keyHash  = WorkspaceKeyHash.fromSecret(key)
      result <- ref.get.map(state => validateWorkspace(state.byHash, keyHash, key, now))
      ws     <- ZIO.fromEither(result).tapError {
                  case _: WorkspaceNotFound =>
                    logSecurityWarning("workspace.resolve_failed",
                      "workspace_key" -> key.toString, "reason" -> "not_found"
                    )("Workspace resolve failed")
                  case _: WorkspaceExpired =>
                    logSecurityWarning("workspace.resolve_failed",
                      "workspace_key" -> key.toString, "reason" -> "expired"
                    )("Workspace resolve failed")
                  case _ => ZIO.unit
                }
    yield ws

object WorkspaceStoreLive:
  private final case class State(
    byHash: Map[WorkspaceKeyHash, WorkspaceRecord],
    byId: Map[WorkspaceId, WorkspaceKeyHash]
  )

  val layer: ZLayer[WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.fromZIO {
      for
        config <- ZIO.service[WorkspaceConfig]
        ref    <- Ref.make(State(Map.empty, Map.empty))
      yield WorkspaceStoreLive(ref, config)
    }

  /** Create a store with explicit config (for tests). */
  def make(config: WorkspaceConfig): UIO[WorkspaceStore] =
    Ref.make(State(Map.empty, Map.empty)).map(ref => WorkspaceStoreLive(ref, config))
