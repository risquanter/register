package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.{Header, MediaType}

import com.risquanter.register.domain.data.{LECCurveResponse, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped analysis endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  */
trait WorkspaceAnalysisEndpoints extends BaseEndpoint:

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  // TODO-REMOVE: No real-world clients. All LEC rendering uses lec-multi
  // (Map[NodeId, LECNodeCurve]). Remove along with LECCurveResponse,
  // RiskTreeService.getLECCurve, WorkspaceAnalysisController.getLECCurve,
  // and their tests.
  @deprecated("No real-world clients. Use lec-multi instead.", since = "2026-04-14")
  val getWorkspaceLECCurveEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurve")
      .description("[DEPRECATED] Get LEC curve for a node (workspace-scoped). Use lec-multi or lec-chart instead.")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "lec")
      .get
      .in(query[Boolean]("includeProvenance").default(false))
      .out(jsonBody[LECCurveResponse])

  val getWorkspaceProbOfExceedanceEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceProbOfExceedance")
      .description("Get probability of exceeding a loss threshold (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "prob-of-exceedance")
      .get
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .out(jsonBody[Double])

  val getWorkspaceLECCurvesMultiEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurvesMulti")
      .description("Get LEC curves for multiple nodes (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / "lec-multi")
      .post
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[List[NodeId]].description("Array of node IDs"))
      .out(jsonBody[Map[NodeId, LECNodeCurve]])