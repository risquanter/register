package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.{Header, MediaType}

import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId, CommitHash}
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.LECNodeCurve

/** Workspace-scoped analysis endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  *
  * Each endpoint carries a required `X-Branch` header (E7) naming the target
  * branch (`"main"` or a scenario name); absence is a 400. Reads accept an
  * optional `at` commit pin for point-in-time access — absent = the branch
  * head. The controller composes the actual Irmin branch from this name and
  * the caller's own resolved `WorkspaceId` — see `ActiveBranch.resolve`.
  */
trait WorkspaceAnalysisEndpoints extends BaseEndpoint:

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  val getWorkspaceProbOfExceedanceEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceProbOfExceedance")
      .description("Get probability of exceeding a loss threshold (workspace-scoped); optional `at` commit pin")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "prob-of-exceedance")
      .get
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .out(jsonBody[Double])

  val getWorkspaceLECCurvesMultiEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurvesMulti")
      .description("Get LEC curves for multiple nodes (workspace-scoped); optional `at` commit pin")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / "lec-multi")
      .post
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[List[NodeId]].description("Array of node IDs"))
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .out(jsonBody[Map[NodeId, LECNodeCurve]])