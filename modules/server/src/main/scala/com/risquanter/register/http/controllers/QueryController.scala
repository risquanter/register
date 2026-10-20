package com.risquanter.register.http.controllers

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Checked, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.domain.errors.FolQueryFailure
import com.risquanter.register.http.endpoints.WorkspaceQueryEndpoints
import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.services.QueryService
import com.risquanter.register.services.workspace.WorkspaceStore
import com.risquanter.register.http.errors.TreeScope

/** Query controller — wires `QueryRequest.resolve()` to `QueryService.evaluate()`.
  *
  * This controller is "dumb" per ADR-001: it calls `resolve()` at the HTTP
  * boundary, maps the library error to `FolQueryFailure`, then forwards the
  * typed `ParsedQuery` to the service. No query content inspection.
  *
  * Authorization layers:
  *  - Layer 0: [[WorkspaceStore.resolveTreeWorkspace]] validates the workspace key
  *    and asserts the tree belongs to that workspace.
  *  - Layer 1: [[UserContextExtractor.extract]] fails closed when `requirePresent`
  *    is injected via `register.auth.mode=identity`. The `UserId` is bound as
  *    `userId` because it is immediately consumed by the Layer 2 check.
  *  - Layer 2: `authzService.check(userId, Permission.AnalyzeRun, ResourceRef(RiskTree, treeId))`
  *    call is present in this controller, but the SpiceDB backend is not yet
  *    deployed. Currently resolved by [[AuthorizationServiceNoOp]], which
  *    always permits; full enforcement activates once SpiceDB is provisioned.
  * @see ADR-024 — Application as Pure PEP
  */
class QueryController private (
  queryService: QueryService,
  workspaceStore: WorkspaceStore,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor,
  tracing: Tracing
) extends BaseController
  with WorkspaceQueryEndpoints:

  // `wireEither` reads the active span to name the trace a failure ran under.
  private given Tracing = tracing

  val queryTree: ServerEndpoint[Any, Task] = queryWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, req, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        parsed <- ZIO.fromEither(QueryRequest.resolve(req))
                    .mapError(e => FolQueryFailure.fromQueryError(e))
        result <- queryService.evaluate(ws.id, treeId, parsed, ws.seedEntityId, branch)
      yield result).wireEither(TreeScope.InTree(treeId))
  }

  override val routes: List[ServerEndpoint[Any, Task]] = List(queryTree)

object QueryController:
  val makeZIO: ZIO[QueryService & WorkspaceStore & AuthorizationService & UserContextExtractor & Tracing, Nothing, QueryController] =
    for
      queryService <- ZIO.service[QueryService]
      workspaceStore <- ZIO.service[WorkspaceStore]
      authzService <- ZIO.service[AuthorizationService]
      userCtx <- ZIO.service[UserContextExtractor]
      tracing <- ZIO.service[Tracing]
    yield QueryController(queryService, workspaceStore, authzService, userCtx, tracing)
