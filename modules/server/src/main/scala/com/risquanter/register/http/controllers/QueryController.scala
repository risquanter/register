package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.domain.data.iron.TreeId
import com.risquanter.register.domain.errors.FolQueryFailure
import com.risquanter.register.http.endpoints.WorkspaceQueryEndpoints
import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.services.QueryService
import com.risquanter.register.services.workspace.WorkspaceStore

/** Query controller — wires `QueryRequest.resolve()` to `QueryService.evaluate()`.
  *
  * This controller is "dumb" per ADR-001: it calls `resolve()` at the HTTP
  * boundary, maps the library error to `FolQueryFailure`, then forwards the
  * typed `ParsedQuery` to the service. No query content inspection.
  */
class QueryController private (
  queryService: QueryService,
  workspaceStore: WorkspaceStore,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor
) extends BaseController
  with WorkspaceQueryEndpoints:

  val queryTree: ServerEndpoint[Any, Task] = queryWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, req) =>
      (for
        userId <- userCtx.extract(maybeUserId)
        _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        parsed <- ZIO.fromEither(QueryRequest.resolve(req))
                    .mapError(e => FolQueryFailure.fromQueryError(e))
        result <- queryService.evaluate(ws.id, treeId, parsed)
      yield result).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] = List(queryTree)

object QueryController:
  val makeZIO: ZIO[QueryService & WorkspaceStore & AuthorizationService & UserContextExtractor, Nothing, QueryController] =
    for
      queryService <- ZIO.service[QueryService]
      workspaceStore <- ZIO.service[WorkspaceStore]
      authzService <- ZIO.service[AuthorizationService]
      userCtx <- ZIO.service[UserContextExtractor]
    yield QueryController(queryService, workspaceStore, authzService, userCtx)
