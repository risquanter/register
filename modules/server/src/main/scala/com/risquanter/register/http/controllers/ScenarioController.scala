package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Checked, Permission, UserContextExtractor}
import com.risquanter.register.auth.ResourceRef.asResource
import com.risquanter.register.http.endpoints.ScenarioEndpoints
import com.risquanter.register.http.responses.{ScenarioResponse, ScenarioSummaryResponse}
import com.risquanter.register.services.{ScenarioService, ScenarioSource}
import com.risquanter.register.services.workspace.WorkspaceStore

/** Scenario lifecycle controller (milestone-2b Phase B, DD-5).
  *
  * Owns workspace-scoped scenario create/list/delete. No dedicated
  * `ResourceType` for a scenario — DD-11 makes ownership a prefix convention
  * on the branch name, not a separate authorization resource, so every
  * operation checks against the workspace resource, mirroring
  * `WorkspaceLifecycleController`'s tree create/list/delete.
  *
  * @see AUTHORIZATION-PLAN.md — Layered Model
  * @see ADR-024 — Application as Pure PEP
  */
class ScenarioController private (
  scenarioService: ScenarioService,
  workspaceStore:  WorkspaceStore,
  authzService:    AuthorizationService,
  userCtx:         UserContextExtractor
) extends BaseController
    with ScenarioEndpoints:

  val createScenario: ServerEndpoint[Any, Task] = createScenarioEndpoint.serverLogic {
    case (maybeUserId, key, req) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        source  = req.forkOf.fold(ScenarioSource.Main)(ScenarioSource.ForkOf(_))
        _      <- scenarioService.create(ws.id, req.name, source)
      yield ScenarioResponse(req.name)).either
  }

  val listScenarios: ServerEndpoint[Any, Task] = listScenariosEndpoint.serverLogic {
    case (maybeUserId, key) =>
      (for
        userId  <- userCtx.requireAuthenticated(maybeUserId)
        ws      <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
        summaries <- scenarioService.list(ws.id)
      yield summaries.map(s => ScenarioSummaryResponse(s.name, s.head))).either
  }

  val deleteScenario: ServerEndpoint[Any, Task] = deleteScenarioEndpoint.serverLogic {
    case (maybeUserId, key, name, expectedHead) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        _      <- scenarioService.delete(ws.id, name, expectedHead)
      yield ()).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(createScenario, listScenarios, deleteScenario)

object ScenarioController:
  val makeZIO: ZIO[ScenarioService & WorkspaceStore & AuthorizationService & UserContextExtractor, Nothing, ScenarioController] =
    for
      scenarioService <- ZIO.service[ScenarioService]
      workspaceStore  <- ZIO.service[WorkspaceStore]
      authzService    <- ZIO.service[AuthorizationService]
      userCtx         <- ZIO.service[UserContextExtractor]
    yield ScenarioController(scenarioService, workspaceStore, authzService, userCtx)
