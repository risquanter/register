package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.UserContextExtractor
import com.risquanter.register.http.endpoints.WorkspaceLifecycleEndpoints
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse, WorkspaceRotateResponse}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.{RateLimiter, WorkspaceStore}

/** Workspace lifecycle controller.
  *
  * Owns workspace bootstrap, workspace lifecycle, and workspace-level
  * tree listing/creation routes.
  */
class WorkspaceLifecycleController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  rateLimiter: RateLimiter,
  userCtx: UserContextExtractor
) extends BaseController
    with WorkspaceLifecycleEndpoints:

  private def normaliseIp(xff: Option[String]): String =
    xff.flatMap(_.split(",").headOption).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")

  val bootstrapWorkspace: ServerEndpoint[Any, Task] = bootstrapWorkspaceEndpoint.serverLogic {
    case (xff, req) =>
      val ip = normaliseIp(xff)
      (for
        _    <- rateLimiter.checkCreate(ip)
        key  <- workspaceStore.create()
        ws   <- workspaceStore.resolve(key)
        tree <- riskTreeService.create(ws.id, req)
        _    <- workspaceStore.addTree(key, tree.id)
      yield WorkspaceBootstrapResponse(
        workspaceKey = key,
        tree = SimulationResponse.fromRiskTree(tree),
        expiresAt = ws.expiresAt
      )).either
  }

  val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic {
    case (maybeUserId, key) =>
      (for
        _        <- userCtx.extract(maybeUserId)
        ws       <- workspaceStore.resolve(key)
        ids      <- workspaceStore.listTrees(key)
        trees    <- ZIO.foreach(ids)(id => riskTreeService.getById(ws.id, id))
        existing  = trees.collect { case Some(t) => SimulationResponse.fromRiskTree(t) }
      yield existing).either
  }

  val createWorkspaceTree: ServerEndpoint[Any, Task] = createWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, req) =>
      (for
        _    <- userCtx.extract(maybeUserId)
        ws   <- workspaceStore.resolve(key)
        tree <- riskTreeService.create(ws.id, req)
        _    <- workspaceStore.addTree(key, tree.id)
      yield SimulationResponse.fromRiskTree(tree)).either
  }

  val rotateWorkspace: ServerEndpoint[Any, Task] = rotateWorkspaceKeySecretEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      _      <- userCtx.extract(maybeUserId)
      newKey <- workspaceStore.rotate(key)
      ws     <- workspaceStore.resolve(newKey)
    yield WorkspaceRotateResponse(newKey, ws.expiresAt)).either
  }

  val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      _   <- userCtx.extract(maybeUserId)
      ws  <- workspaceStore.resolve(key)
      ids <- workspaceStore.listTrees(key)
      _   <- riskTreeService.cascadeDeleteTrees(ws.id, ids)
      _   <- workspaceStore.delete(key)
    yield ()).either
  }

  val evictExpired: ServerEndpoint[Any, Task] = evictExpiredEndpoint.serverLogicSuccess { _ =>
    workspaceStore.evictExpired.map(evicted => Map("evicted" -> evicted.size))
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      bootstrapWorkspace,
      listWorkspaceTrees,
      createWorkspaceTree,
      rotateWorkspace,
      deleteWorkspace,
      evictExpired
    )

object WorkspaceLifecycleController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & RateLimiter & UserContextExtractor, Nothing, WorkspaceLifecycleController] =
    for
      riskTreeService <- ZIO.service[RiskTreeService]
      workspaceStore  <- ZIO.service[WorkspaceStore]
      rateLimiter     <- ZIO.service[RateLimiter]
      userCtx         <- ZIO.service[UserContextExtractor]
    yield WorkspaceLifecycleController(riskTreeService, workspaceStore, rateLimiter, userCtx)