package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{PositiveInt, WorkspaceId, TreeId}

/** Single source of truth for "delete everything belonging to a workspace" —
  * every tree, plus every scenario branch. Called by
  * `WorkspaceLifecycleController.deleteWorkspace`,
  * `WorkspaceLifecycleController.evictExpired` and `WorkspaceReaper`'s reap
  * cycle, so the composition lives in exactly one place. The two cascades
  * touch disjoint resources, so they run concurrently.
  */
object CascadeDelete:
  def workspace(
    wsId: WorkspaceId,
    treeIds: Iterable[TreeId],
    riskTreeService: RiskTreeService,
    scenarioService: ScenarioService,
    parallelism: PositiveInt
  )(using Checked[Authority]): UIO[Unit] =
    riskTreeService.cascadeDeleteTrees(wsId, treeIds, parallelism)
      .zipPar(scenarioService.cascadeDeleteScenarios(wsId, parallelism))
      .unit
