package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse}
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.{WorkspaceStore, RateLimiter}
import com.risquanter.register.domain.data.iron.{WorkspaceKey, TreeId}

/** Workspace controller.
  *
  * A6: Implements DELETE /w/{key} hard-delete endpoint.
  * A13: Workspace errors are mapped to opaque 404 via ErrorResponse.encode.
  * A27: Bootstrap endpoint uses IP-based rate limiting.
  */
class WorkspaceController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  rateLimiter: RateLimiter
) extends BaseController
    with WorkspaceEndpoints:

  private def normaliseIp(xff: Option[String]): String =
    xff.flatMap(_.split(",").headOption).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")

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

  val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic { key =>
    (for
      ids <- workspaceStore.listTrees(key)
      trees <- ZIO.foreach(ids)(riskTreeService.getById)
      existing = trees.collect { case Some(t) => SimulationResponse.fromRiskTree(t) }
    yield existing).either
  }

  val createWorkspaceTree: ServerEndpoint[Any, Task] = createWorkspaceTreeEndpoint.serverLogic {
    case (key, req) =>
      (for
        _    <- workspaceStore.resolve(key)
        tree <- riskTreeService.create(req)
        _    <- workspaceStore.addTree(key, tree.id)
      yield SimulationResponse.fromRiskTree(tree)).either
  }

  val rotateWorkspace: ServerEndpoint[Any, Task] = rotateWorkspaceKeyEndpoint.serverLogic { key =>
    (for
      newKey <- workspaceStore.rotate(key)
      ws     <- workspaceStore.resolve(newKey)
    yield WorkspaceRotateResponse(newKey, ws.expiresAt)).either
  }

  val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic { key =>
    (for
      ids <- workspaceStore.listTrees(key)
      // Best-effort cascade: ignore individual tree deletion failures
      // (tree may already be gone; cascade should not abort)
      _   <- ZIO.foreachDiscard(ids)(id => riskTreeService.delete(id).ignore)
      _   <- workspaceStore.delete(key)
    yield ()).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      bootstrapWorkspace,
      listWorkspaceTrees,
      createWorkspaceTree,
      rotateWorkspace,
      deleteWorkspace
    )

object WorkspaceController:
  /** Follows same wiring pattern as RiskTreeController.makeZIO (ADR consistency). */
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & RateLimiter, Nothing, WorkspaceController] =
    for
      riskTreeService <- ZIO.service[RiskTreeService]
      workspaceStore  <- ZIO.service[WorkspaceStore]
      rateLimiter     <- ZIO.service[RateLimiter]
    yield WorkspaceController(riskTreeService, workspaceStore, rateLimiter)
