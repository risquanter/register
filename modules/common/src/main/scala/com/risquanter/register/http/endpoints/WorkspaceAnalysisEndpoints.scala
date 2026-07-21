package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.{Header, MediaType}

import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId, ScenarioName}
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.LECNodeCurve

/** Workspace-scoped analysis endpoints.
  *
  * All operations are served exclusively under `/w/{key}/...` to enforce
  * workspace capability checks.
  *
  * Each endpoint accepts an optional `X-Active-Branch` header (milestone-2b
  * Phase B item 4b, redesigned 2026-07-21) naming the scenario to read by
  * its `ScenarioName` — absent header targets `main` (DD-4 default). The
  * controller composes the actual Irmin branch from this name and the
  * caller's own resolved `WorkspaceId` — see `ActiveBranch.resolve`.
  */
trait WorkspaceAnalysisEndpoints extends BaseEndpoint:

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  val getWorkspaceProbOfExceedanceEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceProbOfExceedance")
      .description("Get probability of exceeding a loss threshold (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "prob-of-exceedance")
      .get
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
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
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
      .out(jsonBody[Map[NodeId, LECNodeCurve]])