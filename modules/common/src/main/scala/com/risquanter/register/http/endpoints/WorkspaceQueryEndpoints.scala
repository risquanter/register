package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*

import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, BranchRef}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped query endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  *
  * Accepts an optional `X-Active-Branch` header (milestone-2b Phase B item
  * 4b) selecting which branch to query — absent header targets `main`
  * (DD-4 default). The controller is responsible for rejecting a branch the
  * caller's workspace does not own — see `ActiveBranch.resolve`.
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
      .in(header[Option[BranchRef]]("X-Active-Branch"))
      .out(jsonBody[QueryResponse])