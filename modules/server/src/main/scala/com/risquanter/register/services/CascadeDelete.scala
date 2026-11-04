package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId}

/** Single source of truth for "delete everything belonging to a workspace" —
  * every tree, plus every scenario branch. Called by
  * `WorkspaceLifecycleController.deleteWorkspace`,
  * `WorkspaceLifecycleController.evictExpired` and `WorkspaceReaper`'s reap
  * cycle, so the composition lives in exactly one place. The two cascades
  * touch disjoint resources, so they run concurrently.
  */
object CascadeDelete:
  /** How many deletes one cascade runs at once: per workspace in the reaper
    * and the eviction route, per tree and per scenario inside each cascade.
    * Chosen here, not measured.
    */
  val Parallelism: Int = 8

  def workspace(
    wsId: WorkspaceId,
    treeIds: Iterable[TreeId],
    riskTreeService: RiskTreeService,
    scenarioService: ScenarioService
  )(using Checked[Authority]): UIO[Unit] =
    riskTreeService.cascadeDeleteTrees(wsId, treeIds)
      .zipPar(scenarioService.cascadeDeleteScenarios(wsId))
      .unit
