package com.risquanter.register.http.controllers

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Checked, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.WorkspaceAnalysisEndpoints
import com.risquanter.register.domain.data.iron.Revision
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
  *    deployed. Currently resolved by [[AuthorizationServiceNoOp]], which
  *    always permits; full enforcement activates once SpiceDB is provisioned.
  *
  * @see ADR-024 — Application as Pure PEP
  */
class WorkspaceAnalysisController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor,
  tracing: Tracing
) extends BaseController
    with WorkspaceAnalysisEndpoints:

  // `wireEither` reads the active span to name the trace a failure ran under.
  private given Tracing = tracing

  val probOfExceedance: ServerEndpoint[Any, Task] = getWorkspaceProbOfExceedanceEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, threshold, includeProvenance, activeBranch, at) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        rev     = at.fold[Revision](Revision.Head(branch))(Revision.At(_))
        result <- riskTreeService.probOfExceedance(ws.id, treeId, nodeId, threshold, ws.seedEntityId, includeProvenance, rev)
      yield result).wireEither
  }

  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getWorkspaceLECCurvesMultiEndpoint.serverLogic {
    case (maybeUserId, key, treeId, includeProvenance, nodeIds, activeBranch, at, omitAbsent) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        rev     = at.fold[Revision](Revision.Head(branch))(Revision.At(_))
        result <- riskTreeService.getLECCurvesMulti(ws.id, treeId, nodeIds.toSet, ws.seedEntityId, includeProvenance, rev, omitAbsent)
      yield result).wireEither
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      probOfExceedance,
      getLECCurvesMulti
    )

object WorkspaceAnalysisController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & AuthorizationService & UserContextExtractor & Tracing, Nothing, WorkspaceAnalysisController] =
    for
      riskTreeService <- ZIO.service[RiskTreeService]
      workspaceStore  <- ZIO.service[WorkspaceStore]
      authzService    <- ZIO.service[AuthorizationService]
      userCtx         <- ZIO.service[UserContextExtractor]
      tracing         <- ZIO.service[Tracing]
    yield WorkspaceAnalysisController(riskTreeService, workspaceStore, authzService, userCtx, tracing)