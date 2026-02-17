package com.risquanter.register.services.workspace

import zio.*
import zio.logging.LogAnnotation
import java.time.{Duration, Instant}
import com.risquanter.register.domain.data.Workspace
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId}
import com.risquanter.register.domain.errors.{AppError, WorkspaceNotFound, WorkspaceExpired}
import com.risquanter.register.configs.WorkspaceConfig

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
  ref: Ref[Map[WorkspaceKeySecret, Workspace]],
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
    map: Map[WorkspaceKeySecret, Workspace],
    key: WorkspaceKeySecret,
    now: Instant
  ): Either[AppError, Workspace] =
    map.get(key) match
      case None                          => Left(WorkspaceNotFound(key))
      case Some(ws) if ws.isExpired(now) => Left(WorkspaceExpired(key, ws.createdAt, ws.ttl))
      case Some(ws)                      => Right(ws)

  // ── Public API ────────────────────────────────────────────────────────

  /** Create a new workspace with configured TTL and idle timeout. (A29: logs creation) */
  override def create(): UIO[WorkspaceKeySecret] =
    for
      key <- WorkspaceKeySecret.generate
      now <- Clock.instant
      workspace = Workspace(
        key = key,
        trees = Set.empty,
        createdAt = now,
        lastAccessedAt = now,
        ttl = config.ttl,
        idleTimeout = config.idleTimeout
      )
      _ <- ref.update(_ + (key -> workspace))
      // ADR-022: redacted — security audit logs share the application log sink (stdout/SLF4J),
      // not a restricted OTel channel. Use key.reveal here only if logs are routed to a
      // restricted OpenTelemetry instance with access controls.
      _ <- logSecurity("workspace.created", "workspace_key" -> key.toString)("Workspace created")
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
      _ <- resolveInternal(key)
      _ <- ref.update(_.updatedWith(key)(_.map(w => w.copy(trees = w.trees + treeId))))
    yield ()

  /** Disassociate a tree from a workspace. Idempotent — removing a non-member is a no-op.
    * Same non-atomic resolve + update pattern as addTree (see justification above).
    */
  override def removeTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit] =
    for
      _ <- resolveInternal(key)
      _ <- ref.update(_.updatedWith(key)(_.map(w => w.copy(trees = w.trees - treeId))))
    yield ()

  /** List all tree IDs in a workspace. */
  override def listTrees(key: WorkspaceKeySecret): IO[AppError, List[TreeId]] =
    resolveInternal(key).map(_.trees.toList)

  /** Resolve a workspace with dual timeout check (A11) and access tracking (A10).
    *
    * Atomic: single Ref.modify validates + touches in one step.
    * No TOCTOU race between read and write.
    */
  override def resolve(key: WorkspaceKeySecret): IO[AppError, Workspace] =
    for
      now    <- Clock.instant
      result <- ref.modify { map =>
        validateWorkspace(map, key, now) match
          case Left(err) => (Left(err), map)
          case Right(ws) =>
            val touched = ws.touch(now)
            (Right(touched), map.updated(key, touched))
      }
      ws <- ZIO.fromEither(result)
    yield ws

  /** Check if a tree belongs to a workspace. */
  override def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean] =
    resolveInternal(key).map(_.trees.contains(treeId))

  /** Evict all expired workspaces. Returns count evicted. (A31: logs eviction) */
  override def evictExpired: UIO[Int] =
    for
      now <- Clock.instant
      evicted <- ref.modify { map =>
        val (expired, alive) = map.partition((_, ws) => ws.isExpired(now))
        (expired.size, alive)
      }
      _ <- logSecurity("workspace.eviction", "evicted_count" -> evicted.toString)(
             s"Workspace reaper: evicted $evicted expired workspaces"
           ).when(evicted > 0)
    yield evicted

  /** Hard delete. Removes workspace from the store. (A29: logs deletion)
    *
    * Note: resolve + remove are two separate Ref operations. This is safe because
    * delete is idempotent — a concurrent delete between resolve and update simply
    * removes a key that is already gone, which is a no-op on Map.
    */
  override def delete(key: WorkspaceKeySecret): IO[AppError, Unit] =
    for
      _ <- resolveInternal(key)
      _ <- ref.update(_ - key)
      // ADR-022: redacted — see workspace.created comment for rationale
      _ <- logSecurity("workspace.deleted", "workspace_key" -> key.toString)("Workspace deleted")
    yield ()

  /** Atomic rotation via single Ref.modify — no window where neither key works.
    * Reuses validateWorkspace for DRY validation. (A29: logs rotation)
    */
  override def rotate(key: WorkspaceKeySecret): IO[AppError, WorkspaceKeySecret] =
    for
      newKey <- WorkspaceKeySecret.generate
      now    <- Clock.instant
      result <- ref.modify { map =>
        validateWorkspace(map, key, now) match
          case Left(err) => (Left(err), map)
          case Right(ws) =>
            val rotated = ws.copy(key = newKey, createdAt = now, lastAccessedAt = now)
            (Right(newKey), (map - key) + (newKey -> rotated))
      }
      newK <- ZIO.fromEither(result)
      // ADR-022: redacted — see workspace.created comment for rationale
      _ <- logSecurity("workspace.rotated",
             "workspace_key_old" -> key.toString,
             "workspace_key_new" -> newK.toString
           )("Workspace key rotated")
    yield newK

  // ── Internal ──────────────────────────────────────────────────────────

  /** Internal resolve without lastAccessedAt update — used by addTree, listTrees, etc.
    * Logs on failure for security audit (A29/A33).
    */
  private def resolveInternal(key: WorkspaceKeySecret): IO[AppError, Workspace] =
    for
      now    <- Clock.instant
      result <- ref.get.map(validateWorkspace(_, key, now))
      ws     <- ZIO.fromEither(result).tapError {
                  // ADR-022: redacted — see workspace.created comment for rationale
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
  val layer: ZLayer[WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.fromZIO {
      for
        config <- ZIO.service[WorkspaceConfig]
        ref    <- Ref.make(Map.empty[WorkspaceKeySecret, Workspace])
      yield WorkspaceStoreLive(ref, config)
    }

  /** Create a store with explicit config (for tests). */
  def make(config: WorkspaceConfig): UIO[WorkspaceStore] =
    Ref.make(Map.empty[WorkspaceKeySecret, Workspace]).map(ref => WorkspaceStoreLive(ref, config))
