package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.{InvalidationResponse, WorkspaceTreeEndpoints}
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.workspace.WorkspaceStore
import com.risquanter.register.domain.errors.TreeNotInWorkspace

/** Workspace tree controller.
  *
  * Owns workspace-scoped CRUD and cache invalidation for trees.
  */
class WorkspaceTreeController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  invalidationHandler: InvalidationHandler,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor
) extends BaseController
    with WorkspaceTreeEndpoints:

  val getTreeById: ServerEndpoint[Any, Task] = getWorkspaceTreeByIdEndpoint.serverLogic {
    case (maybeUserId, key, treeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.getById(ws.id, treeId).map(_.map(SimulationResponse.fromRiskTree))
      yield result).either
  }

  val getTreeStructure: ServerEndpoint[Any, Task] = getWorkspaceTreeStructureEndpoint.serverLogic {
    case (maybeUserId, key, treeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.getById(ws.id, treeId)
      yield result).either
  }

  val updateTree: ServerEndpoint[Any, Task] = updateWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, req) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.update(ws.id, treeId, req).map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val deleteTree: ServerEndpoint[Any, Task] = deleteWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        result <- riskTreeService.delete(ws.id, treeId)
                      .tap(_ => workspaceStore.removeTree(key, treeId))
                      .map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val invalidateCache: ServerEndpoint[Any, Task] = invalidateWorkspaceCacheEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        tree   <- riskTreeService.getById(ws.id, treeId).someOrFail(TreeNotInWorkspace(key, treeId))
        r      <- invalidationHandler.handleNodeChange(nodeId, tree)
      yield InvalidationResponse(
        invalidatedNodes = r.invalidatedNodes.map(_.value.toString),
        subscribersNotified = r.subscribersNotified
      )).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      getTreeById,
      getTreeStructure,
      updateTree,
      deleteTree,
      invalidateCache
    )

object WorkspaceTreeController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & InvalidationHandler & AuthorizationService & UserContextExtractor, Nothing, WorkspaceTreeController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      invalidationHandler  <- ZIO.service[InvalidationHandler]
      authzService         <- ZIO.service[AuthorizationService]
      userCtx              <- ZIO.service[UserContextExtractor]
    yield WorkspaceTreeController(riskTreeService, workspaceStore, invalidationHandler, authzService, userCtx)