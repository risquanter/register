package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{ AuthorizationService, BootstrapProvisioner, Permission, UserContextExtractor }
import com.risquanter.register.auth.ResourceRef.asResource
import com.risquanter.register.http.endpoints.WorkspaceLifecycleEndpoints
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse, WorkspaceRotateResponse}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.{RateLimiter, WorkspaceStore}

/** Workspace lifecycle controller.
  *
  * Owns workspace bootstrap, key rotation, workspace deletion, and workspace-level
  * tree listing/creation routes.
  *
  * Authorization layers (all three now wired; SpiceDB backend is NoOp until Phase K):
  *  - Layer 0 (capability-only): [[WorkspaceStore.resolve]] (or `create`/`rotate`)
  *    validates the workspace key. `bootstrapWorkspace` is intentionally unauthenticated
  *    (it creates the credential). `evictExpired` is an internal maintenance route.
  *  - Layer 1 (identity): [[UserContextExtractor.extract]] enforces JWT presence on all
  *    workspace-keyed routes when `requirePresent` is injected via
  *    `register.auth.mode=identity`. The extracted [[UserId]] is passed to `authzService.check`.
  *  - Layer 2 (fine-grained): [[AuthorizationService.check]] enforces per-operation
  *    permissions (`ViewWorkspace`, `DesignWrite`, `AdminWorkspace`) on the resolved
  *    workspace resource. Currently wired with [[AuthorizationServiceNoOp]]
  *    (always-permit stub); live enforcement activates in Phase K.
  *
  * @see AUTHORIZATION-PLAN.md — Layered Model
  * @see ADR-024 — Application as Pure PEP
  */
class WorkspaceLifecycleController private (
  riskTreeService:      RiskTreeService,
  workspaceStore:       WorkspaceStore,
  rateLimiter:          RateLimiter,
  userCtx:              UserContextExtractor,
  authzService:         AuthorizationService,
  bootstrapProvisioner: BootstrapProvisioner
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
        userId   <- userCtx.requireAuthenticated(maybeUserId)
        ws       <- workspaceStore.resolve(key)
        _        <- authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
        ids      <- workspaceStore.listTrees(key)
        trees    <- ZIO.foreach(ids)(id => riskTreeService.getById(ws.id, id))
        existing  = trees.collect { case Some(t) => SimulationResponse.fromRiskTree(t) }
      yield existing).either
  }

  val createWorkspaceTree: ServerEndpoint[Any, Task] = createWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, req) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        _      <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        tree   <- riskTreeService.create(ws.id, req)
        _      <- workspaceStore.addTree(key, tree.id)
      yield SimulationResponse.fromRiskTree(tree)).either
  }

  val rotateWorkspace: ServerEndpoint[Any, Task] = rotateWorkspaceKeySecretEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      userId <- userCtx.requireAuthenticated(maybeUserId)
      ws     <- workspaceStore.resolve(key)
      _      <- authzService.check(userId, Permission.AdminWorkspace, ws.id.asResource)
      newKey <- workspaceStore.rotate(key)
      newWs  <- workspaceStore.resolve(newKey)
    yield WorkspaceRotateResponse(newKey, newWs.expiresAt)).either
  }

  val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      userId <- userCtx.requireAuthenticated(maybeUserId)
      ws     <- workspaceStore.resolve(key)
      _      <- authzService.check(userId, Permission.AdminWorkspace, ws.id.asResource)
      ids    <- workspaceStore.listTrees(key)
      _      <- riskTreeService.cascadeDeleteTrees(ws.id, ids)
      _      <- workspaceStore.delete(key)
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
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & RateLimiter & UserContextExtractor & AuthorizationService & BootstrapProvisioner, Nothing, WorkspaceLifecycleController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      rateLimiter          <- ZIO.service[RateLimiter]
      userCtx              <- ZIO.service[UserContextExtractor]
      authzService         <- ZIO.service[AuthorizationService]
      bootstrapProvisioner <- ZIO.service[BootstrapProvisioner]
    yield WorkspaceLifecycleController(riskTreeService, workspaceStore, rateLimiter, userCtx, authzService, bootstrapProvisioner)