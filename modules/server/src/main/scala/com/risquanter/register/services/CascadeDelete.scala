package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId}

/** Single source of truth for "delete everything belonging to a workspace" —
  * every tree, plus every scenario branch. Previously inlined identically at
  * three call sites (`WorkspaceLifecycleController.deleteWorkspace`,
  * `WorkspaceLifecycleController.evictExpired`, `WorkspaceReaper`'s reap
  * cycle); consolidated here so the composition lives in exactly one place.
  * The two cascades touch disjoint resources, so they run concurrently.
  */
object CascadeDelete:
  def workspace(
    wsId: WorkspaceId,
    treeIds: Iterable[TreeId],
    riskTreeService: RiskTreeService,
    scenarioService: ScenarioService
  )(using Checked[Permission]): UIO[Unit] =
    riskTreeService.cascadeDeleteTrees(wsId, treeIds)
      .zipPar(scenarioService.cascadeDeleteScenarios(wsId))
      .unit
