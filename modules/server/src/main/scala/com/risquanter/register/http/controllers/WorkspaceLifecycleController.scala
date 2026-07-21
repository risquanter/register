package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{ AuthorizationService, BootstrapProvisioner, Checked, Permission, UserContextExtractor }
import com.risquanter.register.auth.ResourceRef.asResource
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode, ValidationFailed}
import com.risquanter.register.http.endpoints.WorkspaceLifecycleEndpoints
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse, WorkspaceRotateResponse}
import com.risquanter.register.services.{RiskTreeService, ScenarioService}
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
  bootstrapProvisioner: BootstrapProvisioner,
  scenarioService:      ScenarioService
) extends BaseController
    with WorkspaceLifecycleEndpoints:

  private def normaliseIp(xff: Option[String]): String =
    xff.flatMap(_.split(",").headOption).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")

  val bootstrapWorkspace: ServerEndpoint[Any, Task] = bootstrapWorkspaceEndpoint.serverLogic {
    case (xff, maybeUserId, seedEntityId, req) =>
      val ip = normaliseIp(xff)
      (for
        _      <- rateLimiter.checkCreate(ip)
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission.Bootstrap.type] <- bootstrapProvisioner.bootstrapToken()
        // exempt: pre-resource-creation — no resource exists yet to check
        key    <- workspaceStore.create(seedEntityId)
        ws     <- workspaceStore.resolve(key)  // exempt: Layer 0 capability gate
        tree   <- riskTreeService.create(ws.id, req)
        _      <- workspaceStore.addTree(key, tree.id)
        _      <- bootstrapProvisioner.recordOwnership(userId, ws.id)
      yield WorkspaceBootstrapResponse(
        workspaceKey = key,
        tree = SimulationResponse.fromRiskTree(tree),
        expiresAt = ws.expiresAt,
        seedEntityId = ws.seedEntityId
      )).either
  }

  val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic {
    case (maybeUserId, key) =>
      (for
        userId   <- userCtx.requireAuthenticated(maybeUserId)
        ws       <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
        ids      <- workspaceStore.listTrees(key)
        trees    <- ZIO.foreach(ids)(id => riskTreeService.getById(ws.id, id))
        existing  = trees.collect { case Some(t) => SimulationResponse.fromRiskTree(t) }
      yield existing).either
  }

  val createWorkspaceTree: ServerEndpoint[Any, Task] = createWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, req, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        // A tree-creating write (unlike update/delete/getById) has no read step
        // that would naturally fail on a nonexistent branch — Irmin's set_tree
        // has no CAS precondition and silently vivifies a brand-new, un-forked
        // branch on first write. That would let a scenario-shaped branch come
        // into existence outside ScenarioService.create, bypassing the "creation
        // always forks at a commit" invariant (DD-5/A9 fact 3) other scenario
        // code relies on. So: require the named scenario to already exist before
        // writing to it. `main` (activeBranch = None) never needs this check.
        _      <- ZIO.foreachDiscard(activeBranch) { name =>
                    scenarioService.list(ws.id).flatMap { scenarios =>
                      ZIO.unless(scenarios.exists(_.name == name))(
                        ZIO.fail(ValidationFailed(List(ValidationError(
                          field = "X-Active-Branch",
                          code = ValidationErrorCode.NOT_FOUND,
                          message = s"Scenario '${name.value}' not found — create it via POST /scenarios before creating a tree on it"
                        ))))
                      )
                    }
                  }
        tree   <- riskTreeService.create(ws.id, req, branch)
        // addTree always fires regardless of branch — the reaper needs to know a
        // tree exists somewhere in the workspace to cascade-delete it on expiry,
        // which is correct no matter which branch it lives on. Unlike deleteTree's
        // removeTree (main-only), there is no asymmetric bookkeeping risk here.
        _      <- workspaceStore.addTree(key, tree.id)
      yield SimulationResponse.fromRiskTree(tree)).either
  }

  val rotateWorkspace: ServerEndpoint[Any, Task] = rotateWorkspaceKeySecretEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      userId <- userCtx.requireAuthenticated(maybeUserId)
      ws     <- workspaceStore.resolve(key)
      given Checked[Permission] <- authzService.check(userId, Permission.AdminWorkspace, ws.id.asResource)
      newKey <- workspaceStore.rotate(key)
      newWs  <- workspaceStore.resolve(newKey)
    yield WorkspaceRotateResponse(newKey, newWs.expiresAt)).either
  }

  val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic { case (maybeUserId, key) =>
    (for
      userId <- userCtx.requireAuthenticated(maybeUserId)
      ws     <- workspaceStore.resolve(key)
      given Checked[Permission] <- authzService.check(userId, Permission.AdminWorkspace, ws.id.asResource)
      ids    <- workspaceStore.listTrees(key)
      _      <- riskTreeService.cascadeDeleteTrees(ws.id, ids)
      _      <- scenarioService.cascadeDeleteScenarios(ws.id)
      _      <- workspaceStore.delete(key)
    yield ()).either
  }

  val evictExpired: ServerEndpoint[Any, Task] = evictExpiredEndpoint.serverLogicSuccess { _ =>
    for
      // exempt: system maintenance — no user context (ADR-030 §2; same token
      // WorkspaceReaper uses for the identical TTL-driven cascade — this
      // endpoint is the admin-triggered, on-demand equivalent of that sweep)
      given Checked[Permission.SystemMaintenance.type] <- bootstrapProvisioner.systemMaintenanceToken()
      evicted <- workspaceStore.evictExpired
      _       <- ZIO.foreachDiscard(evicted)(ws =>
                   riskTreeService.cascadeDeleteTrees(ws.id, ws.trees) *>
                   scenarioService.cascadeDeleteScenarios(ws.id))
    yield Map("evicted" -> evicted.size)
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
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & RateLimiter & UserContextExtractor & AuthorizationService & BootstrapProvisioner & ScenarioService, Nothing, WorkspaceLifecycleController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      rateLimiter          <- ZIO.service[RateLimiter]
      userCtx              <- ZIO.service[UserContextExtractor]
      authzService         <- ZIO.service[AuthorizationService]
      bootstrapProvisioner <- ZIO.service[BootstrapProvisioner]
      scenarioService      <- ZIO.service[ScenarioService]
    yield WorkspaceLifecycleController(riskTreeService, workspaceStore, rateLimiter, userCtx, authzService, bootstrapProvisioner, scenarioService)