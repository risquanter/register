package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*

import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped query endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  */
trait WorkspaceQueryEndpoints extends BaseEndpoint:

  val queryWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("queryWorkspaceTree")
      .description("Evaluate a vague quantifier query against a risk tree")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "query")
      .post
      .in(jsonBody[QueryRequest])
      .out(jsonBody[QueryResponse])