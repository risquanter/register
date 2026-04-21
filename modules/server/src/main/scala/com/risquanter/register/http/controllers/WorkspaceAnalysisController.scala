package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.WorkspaceAnalysisEndpoints
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.WorkspaceStore

/** Workspace analysis controller.
  *
  * Owns workspace-scoped analysis routes.
  */
class WorkspaceAnalysisController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor
) extends BaseController
    with WorkspaceAnalysisEndpoints:

  @deprecated("No real-world clients. Use lec-multi instead.", since = "2026-04-14")
  val getLECCurve: ServerEndpoint[Any, Task] = getWorkspaceLECCurveEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, includeProvenance) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.getLECCurve(ws.id, treeId, nodeId, includeProvenance)
      yield result).either
  }

  val probOfExceedance: ServerEndpoint[Any, Task] = getWorkspaceProbOfExceedanceEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, threshold, includeProvenance) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.probOfExceedance(ws.id, treeId, nodeId, threshold, includeProvenance)
      yield result).either
  }

  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getWorkspaceLECCurvesMultiEndpoint.serverLogic {
    case (maybeUserId, key, treeId, includeProvenance, nodeIds) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.getLECCurvesMulti(ws.id, treeId, nodeIds.toSet, includeProvenance)
      yield result).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      getLECCurve,
      probOfExceedance,
      getLECCurvesMulti
    )

object WorkspaceAnalysisController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & AuthorizationService & UserContextExtractor, Nothing, WorkspaceAnalysisController] =
    for
      riskTreeService <- ZIO.service[RiskTreeService]
      workspaceStore  <- ZIO.service[WorkspaceStore]
      authzService    <- ZIO.service[AuthorizationService]
      userCtx         <- ZIO.service[UserContextExtractor]
    yield WorkspaceAnalysisController(riskTreeService, workspaceStore, authzService, userCtx)