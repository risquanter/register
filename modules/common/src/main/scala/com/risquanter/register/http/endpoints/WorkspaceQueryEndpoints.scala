package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*

import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, ScenarioName}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped query endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  *
  * Accepts an optional `X-Active-Branch` header (milestone-2b Phase B item
  * 4b, redesigned 2026-07-21) naming the scenario to query by its
  * `ScenarioName` — absent header targets `main` (DD-4 default). The
  * controller composes the actual Irmin branch from this name and the
  * caller's own resolved `WorkspaceId` — see `ActiveBranch.resolve`.
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
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
      .out(jsonBody[QueryResponse])