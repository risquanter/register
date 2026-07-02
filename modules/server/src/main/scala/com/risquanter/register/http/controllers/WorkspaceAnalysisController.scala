package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.WorkspaceAnalysisEndpoints
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.WorkspaceStore

/** Workspace analysis controller.
  *
  * Owns workspace-scoped analysis routes (probability of exceedance, LEC curves).
  *
  * Authorization layers:
  *  - Layer 0: [[WorkspaceStore.resolveTreeWorkspace]] validates the workspace key
  *    and asserts the tree belongs to that workspace.
  *  - Layer 1: [[UserContextExtractor.extract]] fails closed when `requirePresent`
  *    is injected via `register.auth.mode=identity`. The `UserId` is bound as
  *    `userId` because it is immediately consumed by the Layer 2 check.
  *  - Layer 2: `authzService.check(userId, Permission.AnalyzeRun, ResourceRef(RiskTree, treeId))`
  *    calls are present in this controller, but the SpiceDB backend is not yet
  *    deployed (Phase K). Currently resolved by [[AuthorizationServiceNoOp]]
  *    (always-permit). Full enforcement activates when Phase K infra is provisioned.
  *
  * @see AUTHORIZATION-PLAN.md — Layered Model
  * @see ADR-024 — Application as Pure PEP
  */
class WorkspaceAnalysisController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor
) extends BaseController
    with WorkspaceAnalysisEndpoints:

  val probOfExceedance: ServerEndpoint[Any, Task] = getWorkspaceProbOfExceedanceEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, threshold, includeProvenance) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.probOfExceedance(ws.id, treeId, nodeId, threshold, includeProvenance)
      yield result).either
  }

  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getWorkspaceLECCurvesMultiEndpoint.serverLogic {
    case (maybeUserId, key, treeId, includeProvenance, nodeIds) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.getLECCurvesMulti(ws.id, treeId, nodeIds.toSet, includeProvenance)
      yield result).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
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