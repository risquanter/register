package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.auth.{BootstrapProvisioner, Checked, Permission}
import com.risquanter.register.configs.WorkspaceConfig
import com.risquanter.register.services.{RiskTreeService, ScenarioService}

/** Background daemon that periodically evicts expired workspaces.
  *
  * == Why a marker trait? ==
  * `WorkspaceReaper` is a zero-method marker trait — it exists solely so that
  * `ZLayer.make` materialises the reaper layer. ZIO's `ZLayer.make` macro only
  * constructs layers that are transitively required by the declared output type.
  * A `ZLayer[..., Nothing, Unit]` with no downstream dependents would be silently
  * skipped. By outputting a named marker type and including it in the application's
  * output type, we guarantee the daemon fiber is always started — and the layer
  * graph remains self-documenting (you can see at a glance that the application
  * includes a workspace reaper).
  *
  * == Lifecycle ==
  * Uses `ZLayer.scoped` + `forkScoped`: the reaper fiber's lifetime is tied to
  * the layer scope. On application shutdown, ZIO interrupts the fiber automatically
  * — graceful shutdown with zero manual fiber management.
  *
  * == Cascade deletion ==
  * When workspaces expire, their associated trees must be cascade-deleted to prevent
  * orphans. The reaper calls `RiskTreeService.delete` for each tree in the evicted
  * workspace, which triggers the full pipeline: repo delete → cache evict → SSE notify.
  * This mirrors `WorkspaceLifecycleController.deleteWorkspace`'s cascade pattern. Tree deletion
  * failures are ignored (best-effort) — a tree may already have been manually deleted.
  *
  * Scenario branches (`scenarios.<wsId>.*`, milestone-2b Phase B) are cascade-deleted
  * the same way via `ScenarioService.cascadeDeleteScenarios` (best-effort, same
  * single source of truth reused by `WorkspaceLifecycleController.deleteWorkspace`
  * and `evictExpired`). Without this, an expired workspace's scenario branches
  * would never be reclaimed — they'd survive in Irmin forever, unreachable by any
  * workspace key. On the in-memory backend, listing scenarios fails with
  * `ScenariosNotSupported`; that failure is swallowed the same as any other
  * best-effort cascade failure (nothing to clean up).
  *
  * == Enterprise no-op ==
  * When both `ttl` and `idleTimeout` are zero or negative, `Workspace.isExpired`
  * can never return `true`, so the reaper loop would be pure waste. The layer
  * detects this and skips the fiber entirely.
  */
trait WorkspaceReaper

object WorkspaceReaper:

  val layer: ZLayer[WorkspaceStore & WorkspaceConfig & RiskTreeService & ScenarioService & BootstrapProvisioner, Nothing, WorkspaceReaper] =
    ZLayer.scoped {
      for
        config          <- ZIO.service[WorkspaceConfig]
        store           <- ZIO.service[WorkspaceStore]
        treeService     <- ZIO.service[RiskTreeService]
        scenarioService <- ZIO.service[ScenarioService]
        provisioner     <- ZIO.service[BootstrapProvisioner]
        _           <- reapLoop(store, treeService, scenarioService, provisioner, config.reaperInterval)
                         .forkScoped
                         .when(!isNoOp(config))
        _           <- ZIO.logInfo(
                         if isNoOp(config) then "Workspace reaper: no-op (TTL and idle timeout both disabled)"
                         else s"Workspace reaper: started (interval=${config.reaperInterval})"
                       )
      yield new WorkspaceReaper {}
    }

  /** Enterprise no-op: both TTL and idleTimeout must be zero/negative.
    *
    * Mirrors `Workspace.isExpired` semantics — that method fires on *either*
    * absolute or idle timeout, so the reaper is only truly a no-op when both
    * are disabled.
    */
  private[workspace] def isNoOp(config: WorkspaceConfig): Boolean =
    (config.ttl.isZero || config.ttl.isNegative) &&
      (config.idleTimeout.isZero || config.idleTimeout.isNegative)

  /** Reap loop: evict expired workspaces, then cascade-delete their trees and
    * scenario branches.
    *
    * Best-effort cascade: each `treeService.delete(id).ignore` and each
    * `scenarioService.delete(...).ignore` swallows failures (the tree/scenario
    * may already be gone). This matches `WorkspaceLifecycleController.deleteWorkspace`.
    */
  private def reapLoop(
    store: WorkspaceStore,
    treeService: RiskTreeService,
    scenarioService: ScenarioService,
    provisioner: BootstrapProvisioner,
    interval: java.time.Duration
  ): UIO[Nothing] =
    val cycle =
      for
        evicted <- store.evictExpired
        // exempt: system maintenance — no user context; WorkspaceReaper is a background orchestrator
        given Checked[Permission.SystemMaintenance.type] <- provisioner.systemMaintenanceToken()
        _       <- ZIO.foreachDiscard(evicted)(ws =>
                     treeService.cascadeDeleteTrees(ws.id, ws.trees) *>
                     scenarioService.cascadeDeleteScenarios(ws.id))
      yield ()
    (ZIO.sleep(zio.Duration.fromJava(interval)) *> cycle).forever
