package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.endpoints.InvalidationResponse
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.workspace.{WorkspaceStore, RateLimiter}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId, UserId}
import com.risquanter.register.domain.errors.TreeNotInWorkspace

/** Workspace controller.
  *
  * All tree-specific operations are served exclusively under
  * `/w/{key}/...`. The old unscoped `/risk-trees/` paths were removed
  * (Option A) to prevent unauthenticated access by TreeId alone.
  *
  * Security features:
  *   - A6:  DELETE /w/{key} cascade hard-delete
  *   - A13: Workspace errors mapped to opaque 404 via ErrorResponse.encode
  *   - A27: Bootstrap endpoint uses IP-based rate limiting
  *   - All tree operations validate workspace ownership via `resolveTree`
  */
class WorkspaceController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  rateLimiter: RateLimiter,
  invalidationHandler: InvalidationHandler,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor
) extends BaseController
    with WorkspaceEndpoints:

  // ── Helpers ───────────────────────────────────────────────────────

  private def normaliseIp(xff: Option[String]): String =
    xff.flatMap(_.split(",").headOption).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")



  // ── Workspace lifecycle ───────────────────────────────────────────

  val bootstrapWorkspace: ServerEndpoint[Any, Task] = bootstrapWorkspaceEndpoint.serverLogic {
    case (xff, req) =>
      val ip = normaliseIp(xff)
      (for
        _    <- rateLimiter.checkCreate(ip)
        key  <- workspaceStore.create()
        tree <- riskTreeService.create(req)
        _    <- workspaceStore.addTree(key, tree.id)
        ws   <- workspaceStore.resolve(key)
      yield WorkspaceBootstrapResponse(
        workspaceKey = key,
        tree = SimulationResponse.fromRiskTree(tree),
        expiresAt = ws.expiresAt
      )).either
  }

  val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic {
    case (maybeUserId, key) =>
      (for
        _        <- userCtx.extract(maybeUserId)  // Wave 3: add workspace-level check after key→UUID resolution
        ids      <- workspaceStore.listTrees(key)
        trees    <- ZIO.foreach(ids)(riskTreeService.getById)
        existing  = trees.collect { case Some(t) => SimulationResponse.fromRiskTree(t) }
      yield existing).either
  }

  val createWorkspaceTree: ServerEndpoint[Any, Task] = createWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, req) =>
      (for
        _    <- userCtx.extract(maybeUserId)  // Wave 3: add workspace-level check
        _    <- workspaceStore.resolve(key)
        tree <- riskTreeService.create(req)
        _    <- workspaceStore.addTree(key, tree.id)
      yield SimulationResponse.fromRiskTree(tree)).either
  }

  val rotateWorkspace: ServerEndpoint[Any, Task] = rotateWorkspaceKeySecretEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      _      <- userCtx.extract(maybeUserId)  // Wave 3: add workspace-level check
      newKey <- workspaceStore.rotate(key)
      ws     <- workspaceStore.resolve(newKey)
    yield WorkspaceRotateResponse(newKey, ws.expiresAt)).either
  }

  val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      _   <- userCtx.extract(maybeUserId)  // Wave 3: add workspace-level check
      ids <- workspaceStore.listTrees(key)
      _   <- riskTreeService.cascadeDeleteTrees(ids)
      _   <- workspaceStore.delete(key)
    yield ()).either
  }

  val evictExpired: ServerEndpoint[Any, Task] = evictExpiredEndpoint.serverLogicSuccess { _ =>
    workspaceStore.evictExpired.map(evicted => Map("evicted" -> evicted.size))
  }

  // ── Workspace-scoped tree operations ──────────────────────────────

  val getTreeById: ServerEndpoint[Any, Task] = getWorkspaceTreeByIdEndpoint.serverLogic {
    case (maybeUserId, key, treeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        _      <- workspaceStore.resolveTree(key, treeId)
        result <- riskTreeService.getById(treeId).map(_.map(SimulationResponse.fromRiskTree))
      yield result).either
  }

  val getTreeStructure: ServerEndpoint[Any, Task] = getWorkspaceTreeStructureEndpoint.serverLogic {
    case (maybeUserId, key, treeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        result <- workspaceStore.resolveTree(key, treeId) *> riskTreeService.getById(treeId)
      yield result).either
  }

  val updateTree: ServerEndpoint[Any, Task] = updateWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, req) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        result <- workspaceStore.resolveTree(key, treeId) *>
                    riskTreeService.update(treeId, req).map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val deleteTree: ServerEndpoint[Any, Task] = deleteWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        result <- workspaceStore.resolveTree(key, treeId) *>
                    riskTreeService.delete(treeId)
                      .tap(_ => workspaceStore.removeTree(key, treeId))
                      .map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val invalidateCache: ServerEndpoint[Any, Task] = invalidateWorkspaceCacheEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        _      <- workspaceStore.resolveTree(key, treeId)
        tree   <- riskTreeService.getById(treeId).someOrFail(TreeNotInWorkspace(key, treeId))
        r      <- invalidationHandler.handleNodeChange(nodeId, tree)
      yield InvalidationResponse(
        invalidatedNodes = r.invalidatedNodes.map(_.value.toString),
        subscribersNotified = r.subscribersNotified
      )).either
  }

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  // TODO-REMOVE: No real-world clients. Remove along with LECCurveResponse,
  // getWorkspaceLECCurveEndpoint, and RiskTreeService.getLECCurve.
  @deprecated("No real-world clients. Use lec-multi or lec-chart instead.", since = "2026-04-14")
  val getLECCurve: ServerEndpoint[Any, Task] = getWorkspaceLECCurveEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, includeProvenance) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        result <- workspaceStore.resolveTree(key, treeId) *>
                    riskTreeService.getLECCurve(treeId, nodeId, includeProvenance)
      yield result).either
  }

  val probOfExceedance: ServerEndpoint[Any, Task] = getWorkspaceProbOfExceedanceEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, threshold, includeProvenance) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        result <- workspaceStore.resolveTree(key, treeId) *>
                    riskTreeService.probOfExceedance(treeId, nodeId, threshold, includeProvenance)
      yield result).either
  }

  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getWorkspaceLECCurvesMultiEndpoint.serverLogic {
    case (maybeUserId, key, treeId, includeProvenance, nodeIds) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        result <- workspaceStore.resolveTree(key, treeId) *>
                    riskTreeService.getLECCurvesMulti(treeId, nodeIds.toSet, includeProvenance)
                      .map(_.map { case (nodeId, nodeCurve) => (nodeId.value, nodeCurve) })
      yield result).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      // Workspace lifecycle
      bootstrapWorkspace,
      listWorkspaceTrees,
      createWorkspaceTree,
      rotateWorkspace,
      deleteWorkspace,
      evictExpired,
      // Tree operations (workspace-scoped)
      getTreeById,
      getTreeStructure,
      updateTree,
      deleteTree,
      invalidateCache,
      // LEC queries (workspace-scoped)
      getLECCurve,
      probOfExceedance,
      getLECCurvesMulti
    )

object WorkspaceController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & RateLimiter & InvalidationHandler & AuthorizationService & UserContextExtractor, Nothing, WorkspaceController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      rateLimiter          <- ZIO.service[RateLimiter]
      invalidationHandler  <- ZIO.service[InvalidationHandler]
      authzService         <- ZIO.service[AuthorizationService]
      userCtx              <- ZIO.service[UserContextExtractor]
    yield WorkspaceController(riskTreeService, workspaceStore, rateLimiter, invalidationHandler, authzService, userCtx)
