package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*

import com.risquanter.register.domain.data.iron.{NodeId, CommitHash, Probability}
import com.risquanter.register.domain.data.iron.given
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.http.responses.LECCurvesResponse

/** Workspace-scoped analysis endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  *
  * Each endpoint carries a required `X-Branch` header naming the target
  * branch (`"main"` or a scenario name); absence is a 400. Reads accept an
  * optional `at` commit pin for point-in-time access — absent = the branch
  * head. The controller composes the actual Irmin branch from this name and
  * the caller's own resolved `WorkspaceId` — see `ActiveBranch.resolve`.
  */
trait WorkspaceAnalysisEndpoints extends BaseEndpoint:

  private val nodesRoute = treeRoute / "nodes"

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  val getWorkspaceProbOfExceedanceEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceProbOfExceedance")
      .description("Get probability of exceeding a loss threshold (workspace-scoped); optional `at` commit pin")
      .in(nodesRoute / path[NodeId]("nodeId") / "prob-of-exceedance")
      .get
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .out(jsonBody[Probability])

  val getWorkspaceLECCurvesMultiEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurvesMulti")
      .description("Get LEC curves for multiple nodes (workspace-scoped); optional `at` commit pin")
      .in(nodesRoute / "lec-multi")
      .post
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[List[NodeId]].description("Array of node IDs"))
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .in(query[Boolean]("omitAbsent").default(false)
        .description("When true, requested node IDs absent from the tree at this revision are omitted from the result instead of failing the request (point-in-time reads)."))
      .out(jsonBody[LECCurvesResponse])