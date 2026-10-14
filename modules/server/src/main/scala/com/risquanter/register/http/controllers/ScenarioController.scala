package com.risquanter.register.http.controllers

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Checked, Permission, UserContextExtractor}
import com.risquanter.register.auth.ResourceRef.asResource
import com.risquanter.register.http.endpoints.ScenarioEndpoints
import com.risquanter.register.http.requests.ScenarioSourceDto
import com.risquanter.register.http.responses.{ScenarioResponse, ScenarioSummaryResponse, MergePreviewResponse, MergePreviewStatus, MergeScenarioResponse}
import com.risquanter.register.domain.data.iron.BranchChoice
import com.risquanter.register.services.{ScenarioService, ScenarioSource, ScenarioMergeService, MergeConflictPath, MergePreviewResult}
import com.risquanter.register.services.workspace.WorkspaceStore

/** Scenario lifecycle controller.
  *
  * Owns workspace-scoped scenario create/list/delete. No dedicated
  * `ResourceType` for a scenario — ownership is a prefix convention
  * on the branch name, not a separate authorization resource, so every
  * operation checks against the workspace resource, mirroring
  * `WorkspaceLifecycleController`'s tree create/list/delete.
  *
  * @see ADR-024 — Application as Pure PEP
  */
class ScenarioController private (
  scenarioService:      ScenarioService,
  scenarioMergeService: ScenarioMergeService,
  workspaceStore:       WorkspaceStore,
  authzService:         AuthorizationService,
  userCtx:              UserContextExtractor,
  tracing:              Tracing
) extends BaseController
    with ScenarioEndpoints:

  // `wireEither` reads the active span to name the trace a failure ran under.
  private given Tracing = tracing

  val createScenario: ServerEndpoint[Any, Task] = createScenarioEndpoint.serverLogic {
    case (maybeUserId, key, req) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        source  = req.source match
                    case ScenarioSourceDto.Branch(BranchChoice.Main)           => ScenarioSource.Main
                    case ScenarioSourceDto.Branch(BranchChoice.Scenario(name)) => ScenarioSource.ForkOf(name)
                    case ScenarioSourceDto.Commit(hash)                        => ScenarioSource.AtCommit(hash)
        _      <- scenarioService.create(ws.id, req.name, source)
      yield ScenarioResponse(req.name)).wireEither
  }

  val listScenarios: ServerEndpoint[Any, Task] = listScenariosEndpoint.serverLogic {
    case (maybeUserId, key) =>
      (for
        userId  <- userCtx.requireAuthenticated(maybeUserId)
        ws      <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
        summaries <- scenarioService.list(ws.id)
      yield summaries.map(s => ScenarioSummaryResponse(s.name, s.head))).wireEither
  }

  val previewScenarioMerge: ServerEndpoint[Any, Task] = previewScenarioMergeEndpoint.serverLogic {
    case (maybeUserId, key, name) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewWorkspace, ws.id.asResource)
        result <- scenarioMergeService.preview(ws.id, name)
      yield toPreviewResponse(result)).wireEither
  }

  val mergeScenario: ServerEndpoint[Any, Task] = mergeScenarioEndpoint.serverLogic {
    case (maybeUserId, key, name) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        head   <- scenarioMergeService.merge(ws.id, name)
      yield MergeScenarioResponse(head)).wireEither
  }

  /** Wire mapping colocated with the routes (mirrors `NodeDiffStatus.toWire`'s
    * "cases and wire strings live together" rationale).
    */
  private def toPreviewResponse(result: MergePreviewResult): MergePreviewResponse = result match
    case MergePreviewResult.Clean =>
      MergePreviewResponse(MergePreviewStatus.Clean())
    case MergePreviewResult.Conflicts(paths) =>
      MergePreviewResponse(MergePreviewStatus.Conflicts(paths.map(MergeConflictPath.toWire)))
    case MergePreviewResult.DuplicateNames(perTree) =>
      MergePreviewResponse(MergePreviewStatus.DuplicateNames(perTree))
    case MergePreviewResult.ScenarioMissing =>
      MergePreviewResponse(MergePreviewStatus.ScenarioMissing())

  val deleteScenario: ServerEndpoint[Any, Task] = deleteScenarioEndpoint.serverLogic {
    case (maybeUserId, key, name, expectedHead) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        ws     <- workspaceStore.resolve(key)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ws.id.asResource)
        _      <- scenarioService.delete(ws.id, name, expectedHead)
      yield ()).wireEither
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(createScenario, listScenarios, previewScenarioMerge, mergeScenario, deleteScenario)

object ScenarioController:
  val makeZIO: ZIO[ScenarioService & ScenarioMergeService & WorkspaceStore & AuthorizationService & UserContextExtractor & Tracing, Nothing, ScenarioController] =
    for
      scenarioService      <- ZIO.service[ScenarioService]
      scenarioMergeService <- ZIO.service[ScenarioMergeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      authzService         <- ZIO.service[AuthorizationService]
      userCtx              <- ZIO.service[UserContextExtractor]
      tracing              <- ZIO.service[Tracing]
    yield ScenarioController(scenarioService, scenarioMergeService, workspaceStore, authzService, userCtx, tracing)
