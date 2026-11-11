package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.auth.{BootstrapProvisioner, Checked, LifecycleMarker}
import com.risquanter.register.configs.WorkspaceConfig
import com.risquanter.register.domain.errors.Failure
import com.risquanter.register.services.{CascadeDelete, RiskTreeService, ScenarioService}

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
  * An evicted workspace's trees and scenario branches (`scenarios.<wsId>.*`) are
  * deleted through `CascadeDelete.workspace`, the same composition
  * `WorkspaceLifecycleController.deleteWorkspace` uses. Without it they would stay
  * in Irmin, unreachable by any workspace key. The cascade is best-effort and
  * swallows each deletion's failure. A tree may already have been deleted, and on
  * the in-memory backend listing scenarios fails as `NotSupported`.
  *
  * == Enterprise no-op ==
  * When both `ttl` and `idleTimeout` are zero or negative, `WorkspaceRecord.isExpired`
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
        _           <- reapLoop(store, treeService, scenarioService, provisioner, config)
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
    * Mirrors `WorkspaceRecord.isExpired` semantics — that method fires on *either*
    * absolute or idle timeout, so the reaper is only truly a no-op when both
    * are disabled.
    */
  private[workspace] def isNoOp(config: WorkspaceConfig): Boolean =
    (config.ttl.isZero || config.ttl.isNegative) &&
      (config.idleTimeout.isZero || config.idleTimeout.isNegative)

  /** Reap loop: evict expired workspaces, then cascade-delete their trees and
    * scenario branches through `CascadeDelete.workspace`.
    */
  private def reapLoop(
    store: WorkspaceStore,
    treeService: RiskTreeService,
    scenarioService: ScenarioService,
    provisioner: BootstrapProvisioner,
    config: WorkspaceConfig
  ): UIO[Nothing] =
    val cycle =
      for
        evicted <- store.evictExpired
        // exempt: system maintenance — no user context; WorkspaceReaper is a background orchestrator
        given Checked[LifecycleMarker.SystemMaintenance.type] <- provisioner.systemMaintenanceToken()
        _       <- ZIO.withParallelism(config.cascadeDeleteParallelism) {
                     ZIO.foreachParDiscard(evicted)(ws =>
                       CascadeDelete.workspace(ws.id, ws.trees, treeService, scenarioService, config.cascadeDeleteParallelism))
                   }
      yield ()
    // A failed cycle costs one interval, so it is logged and the loop carries
    // on. Ending the fiber would stop every later eviction with no symptom.
    val survivingCycle = cycle.catchAll(error =>
      ZIO.logWarning(s"Workspace eviction cycle failed, retrying at the next interval: ${error.logMessage}"))
    (ZIO.sleep(zio.Duration.fromJava(config.reaperInterval)) *> survivingCycle).forever
