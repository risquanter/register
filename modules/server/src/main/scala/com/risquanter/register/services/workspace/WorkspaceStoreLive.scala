package com.risquanter.register.services.workspace

import zio.*
import java.time.Instant
import com.risquanter.register.domain.data.WorkspaceRecord
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, WorkspaceKeyHash, WorkspaceKeySecret, SeedEntityId, ValidationMessages}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, ValidationFailed, ValidationError, ValidationErrorCode, WorkspaceExpired, WorkspaceNotFound}
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

  /** Create a new workspace with configured TTL and idle timeout. Logs a
    * creation event.
    *
    * seedEntityId: None assigns from the fixed-base counter, which is
    * deterministic per fresh store; Some(v) provides it — rejected when a live
    * workspace holds v, and the counter is bumped past v. Assignment and
    * uniqueness check are atomic in a single Ref.modify.
    */
  override def create(seedEntityId: Option[SeedEntityId.SeedEntityId]): IO[AppError, WorkspaceKeySecret] =
    for
      key <- WorkspaceKeyCrypto.generate
      sid <- IdGenerators.nextId.orDie
      now <- Clock.instant
      keyHash = WorkspaceKeyCrypto.hash(key)
      result <- ref.modify { state =>
        resolveSeedEntityId(state, seedEntityId) match
          case Left(err) => (Left(err), state)
          case Right((entityId, nextCounter)) =>
            val workspace = WorkspaceRecord(
              id = WorkspaceId(sid),
              keyHash = keyHash,
              trees = Set.empty,
              createdAt = now,
              lastAccessedAt = now,
              ttl = config.ttl,
              idleTimeout = config.idleTimeout,
              seedEntityId = entityId
            )
            (Right(workspace), state.copy(
              byHash = state.byHash + (keyHash -> workspace),
              nextSeedEntityId = nextCounter
            ))
      }
      workspace <- ZIO.fromEither(result)
      _ <- logSecurity("workspace.created", "workspace_id" -> workspace.id.value)("Workspace created")
    yield key

  /** Pure: resolve the new workspace's seedEntityId against current state.
    * Returns (entityId, counter value after this creation).
    */
  private def resolveSeedEntityId(
    state: WorkspaceStoreLive.State,
    provided: Option[SeedEntityId.SeedEntityId]
  ): Either[AppError, (SeedEntityId.SeedEntityId, Long)] =
    provided match
      case Some(id) =>
        if state.byHash.values.exists(_.seedEntityId.value == id.value) then
          Left(ValidationFailed(List(ValidationError(
            field = "workspace.seedEntityId",
            code = ValidationErrorCode.DUPLICATE_VALUE,
            message = ValidationMessages.seedEntityIdInUse(id.value)
          ))))
        else
          Right((id, math.max(state.nextSeedEntityId, id.value + 1)))
      case None =>
        SeedEntityId.fromLong(state.nextSeedEntityId) match
          case Right(id) => Right((id, state.nextSeedEntityId + 1))
          case Left(_) => Left(RepositoryFailure(
            s"seedEntityId assignment space exhausted at ${state.nextSeedEntityId}"
          ))

  /** Associate a tree with a workspace, refusing one beyond the tree ceiling.
    *
    * Resolve, capacity check and write are one `Ref.modify`, so concurrent
    * calls cannot all pass a ceiling only one of them may pass. Re-associating
    * a tree the workspace already holds stays allowed at the ceiling.
    */
  override def addTree(key: WorkspaceKeySecret, treeId: TreeId)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit] =
    for
      now     <- Clock.instant
      keyHash  = WorkspaceKeyCrypto.hash(key)
      result  <- ref.modify { state =>
                   validateWorkspace(state.byHash, keyHash, key, now) match
                     case Left(err) => (Left(err), state)
                     case Right(ws) =>
                       WorkspaceStore.treeCapacity(ws, treeId, config.maxTreesPerWorkspace) match
                         case Left(err) => (Left(err), state)
                         case Right(_)  =>
                           val updated = ws.copy(trees = ws.trees + treeId)
                           (Right(()), state.copy(byHash = state.byHash.updated(keyHash, updated)))
                 }
      _       <- ZIO.fromEither(result).tapError(logResolveFailure(key))
    yield ()

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op.
    * Same non-atomic resolve + update pattern as addTree (see justification above).
    */
  override def removeTree(key: WorkspaceKeySecret, treeId: TreeId)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit] =
    for
      _       <- resolveInternal(key)
      keyHash  = WorkspaceKeyCrypto.hash(key)
      _ <- ref.update(state =>
             state.copy(byHash = state.byHash.updatedWith(keyHash)(_.map(w => w.copy(trees = w.trees - treeId))))
           )
    yield ()

  /** List all tree IDs in a workspace. */
  override def listTrees(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, List[TreeId]] =
    resolveInternal(key).map(_.trees.toList)

  /** Refuse a creation that would exceed the tree ceiling, before the tree is
    * written.
    */
  override def checkTreeCapacity(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit] =
    resolveInternal(key).flatMap(ws =>
      ZIO.fromEither(WorkspaceStore.treeCapacityForNew(ws, config.maxTreesPerWorkspace))
    )

  /** Resolve a workspace, checking both timeouts and recording the access.
    *
    * One Ref.modify validates and touches in a single step, so there is no
    * window between the read and the write.
    */
  override def resolve(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    for
      now     <- Clock.instant
      keyHash  = WorkspaceKeyCrypto.hash(key)
      result <- ref.modify { map =>
        validateWorkspace(map.byHash, keyHash, key, now) match
          case Left(err) => (Left(err), map)
          case Right(ws) =>
            val touched = ws.touch(now)
            (Right(touched), map.copy(byHash = map.byHash.updated(keyHash, touched)))
      }
      ws <- ZIO.fromEither(result)
    yield ws

  /** Check if a tree belongs to a workspace. */
  override def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean] =
    resolveInternal(key).map(_.trees.contains(treeId))

  /** Evict all expired workspaces. Returns evicted entries for the caller's
    * cascade. Logs an eviction event.
    */
  override def evictExpired: UIO[List[WorkspaceRecord]] =
    for
      now     <- Clock.instant
      evicted <- ref.modify { map =>
        val (expired, aliveByHash) = map.byHash.partition((_, ws) => ws.isExpired(now))
        (expired.values.toList, map.copy(byHash = aliveByHash))
      }
      _ <- logSecurity("workspace.eviction", "evicted_count" -> evicted.size.toString)(
             s"Workspace reaper: evicted ${evicted.size} expired workspaces"
           ).when(evicted.nonEmpty)
    yield evicted

  /** Hard delete. Removes the workspace from the store. Logs a deletion event.
    *
    * Resolve and remove are two separate Ref operations, which is safe because
    * delete is idempotent: a concurrent delete in between removes a key that is
    * already gone, a no-op on Map.
    */
  override def delete(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, Unit] =
    for
      ws      <- resolveInternal(key)
      keyHash  = WorkspaceKeyCrypto.hash(key)
      _ <- ref.update(state => state.copy(byHash = state.byHash - keyHash))
      _ <- logSecurity("workspace.deleted", "workspace_id" -> ws.id.value)("Workspace deleted")
    yield ()

  /** Atomic rotation via one Ref.modify — no window where neither key works.
    * Validates through `validateWorkspace`. Logs a rotation event.
    */
  override def rotate(key: WorkspaceKeySecret)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): IO[AppError, WorkspaceKeySecret] =
    for
      newKey <- WorkspaceKeyCrypto.generate
      now    <- Clock.instant
      oldHash  = WorkspaceKeyCrypto.hash(key)
      newHash  = WorkspaceKeyCrypto.hash(newKey)
      result <- ref.modify { map =>
        validateWorkspace(map.byHash, oldHash, key, now) match
          case Left(err) => (Left(err), map)
          case Right(ws) =>
            val rotated = ws.copy(keyHash = newHash, createdAt = now, lastAccessedAt = now)
            (Right(newKey), map.copy(
              byHash = (map.byHash - oldHash) + (newHash -> rotated)
            ))
      }
      newK  <- ZIO.fromEither(result)
      ws    <- resolve(newK)
      _     <- logSecurity("workspace.rotated", "workspace_id" -> ws.id.value)("Workspace key rotated")
    yield newK

  // ── Internal ──────────────────────────────────────────────────────────

  /** Internal resolve without lastAccessedAt update — used by listTrees and the
    * other reads that must not count as access. Logs on failure.
    */
  private def resolveInternal(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord] =
    for
      now     <- Clock.instant
      keyHash  = WorkspaceKeyCrypto.hash(key)
      result <- ref.get.map(state => validateWorkspace(state.byHash, keyHash, key, now))
      ws     <- ZIO.fromEither(result).tapError(logResolveFailure(key))
    yield ws

  /** Security log for a failed resolve, shared by every caller that validates. */
  private def logResolveFailure(key: WorkspaceKeySecret)(error: AppError): UIO[Unit] =
    error match
      case _: WorkspaceNotFound =>
        logSecurityWarning("workspace.resolve_failed",
          "workspace_key" -> key.toString, "reason" -> "not_found"
        )("Workspace resolve failed")
      case _: WorkspaceExpired =>
        logSecurityWarning("workspace.resolve_failed",
          "workspace_key" -> key.toString, "reason" -> "expired"
        )("Workspace resolve failed")
      case _ => ZIO.unit

object WorkspaceStoreLive:
  /** Fixed counter base: fresh stores assign seedEntityIds 1, 2, 3… in creation
    * order, which is the determinism the demo suites rely on.
    */
  private val SeedEntityIdBase: Long = 1L

  private final case class State(
    byHash: Map[WorkspaceKeyHash, WorkspaceRecord],
    nextSeedEntityId: Long
  )

  private def emptyState: State = State(Map.empty, SeedEntityIdBase)

  val layer: ZLayer[WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.fromZIO {
      for
        config <- ZIO.service[WorkspaceConfig]
        ref    <- Ref.make(emptyState)
      yield WorkspaceStoreLive(ref, config)
    }

  /** Create a store with explicit config (for tests). */
  def make(config: WorkspaceConfig): UIO[WorkspaceStore] =
    Ref.make(emptyState).map(ref => WorkspaceStoreLive(ref, config))
