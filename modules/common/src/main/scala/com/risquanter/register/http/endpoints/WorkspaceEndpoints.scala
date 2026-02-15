package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.{StatusCode, Header, MediaType}

import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse}
import com.risquanter.register.domain.data.{RiskTree, LECCurveResponse, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{WorkspaceKey, TreeId, NodeId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped API endpoints.
  *
  * All tree-specific operations are served exclusively under
  * `/w/{key}/...` to enforce workspace capability checks. The old
  * unscoped `/risk-trees/` paths are removed (Option A — no bypass).
  *
  * Security features represented here:
  *   - A6:  `DELETE /w/{key}` hard delete endpoint
  *   - A13: Workspace errors mapped to opaque 404
  *   - A15: SSE scoped to workspace key (future)
  *   - A27: `POST /workspaces` accepts caller IP via X-Forwarded-For
  */
trait WorkspaceEndpoints extends BaseEndpoint:

  // ── Workspace lifecycle ─────────────────────────────────────────────

  val bootstrapWorkspaceEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("bootstrapWorkspace")
      .description("Create workspace + first tree")
      .in("workspaces")
      .post
      .in(header[Option[String]]("X-Forwarded-For"))
      .in(jsonBody[RiskTreeDefinitionRequest])
      .out(jsonBody[WorkspaceBootstrapResponse])

  val listWorkspaceTreesEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("listWorkspaceTrees")
      .description("List trees in workspace")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees")
      .get
      .out(jsonBody[List[SimulationResponse]])

  val createWorkspaceTreeEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("createWorkspaceTree")
      .description("Create tree in workspace")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees")
      .post
      .in(jsonBody[RiskTreeDefinitionRequest])
      .out(jsonBody[SimulationResponse])

  val rotateWorkspaceKeyEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("rotateWorkspace")
      .description("Rotate workspace key (instant revocation)")
      .in("w" / path[WorkspaceKey]("key") / "rotate")
      .post
      .out(jsonBody[WorkspaceRotateResponse])

  val deleteWorkspaceEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("deleteWorkspace")
      .description("Hard delete workspace + all associated trees")
      .in("w" / path[WorkspaceKey]("key"))
      .delete
      .out(statusCode(StatusCode.NoContent))

  val evictExpiredEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("evictExpired")
      .description("Evict all expired workspaces (admin)")
      .in("admin" / "workspaces" / "expired")
      .delete
      .out(jsonBody[Map[String, Int]])

  // ── Workspace-scoped tree operations ──────────────────────────────

  val getWorkspaceTreeByIdEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeById")
      .description("Get tree summary (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId"))
      .get
      .out(jsonBody[Option[SimulationResponse]])

  val getWorkspaceTreeStructureEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeStructure")
      .description("Get full tree structure (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId") / "structure")
      .get
      .out(jsonBody[Option[RiskTree]])

  val updateWorkspaceTreeEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("updateWorkspaceTree")
      .description("Full replacement update of tree (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId"))
      .put
      .in(jsonBody[RiskTreeUpdateRequest])
      .out(jsonBody[SimulationResponse])

  val deleteWorkspaceTreeEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("deleteWorkspaceTree")
      .description("Delete a single tree from workspace")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId"))
      .delete
      .out(jsonBody[SimulationResponse])

  val invalidateWorkspaceCacheEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("invalidateWorkspaceCache")
      .description("Invalidate cache for a node (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId") / "invalidate" / path[NodeId]("nodeId"))
      .post
      .out(jsonBody[InvalidationResponse])

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  val getWorkspaceLECCurveEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurve")
      .description("Get LEC curve for a node (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "lec")
      .get
      .in(query[Boolean]("includeProvenance").default(false))
      .out(jsonBody[LECCurveResponse])

  val getWorkspaceProbOfExceedanceEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("getWorkspaceProbOfExceedance")
      .description("Get probability of exceeding a loss threshold (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "prob-of-exceedance")
      .get
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .out(jsonBody[BigDecimal])

  val getWorkspaceLECCurvesMultiEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurvesMulti")
      .description("Get LEC curves for multiple nodes (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / "lec-multi")
      .post
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[List[NodeId]].description("Array of node IDs"))
      .out(jsonBody[Map[String, LECNodeCurve]])

  val getWorkspaceLECChartEndpoint =
    baseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECChart")
      .description("Get Vega-Lite chart spec for LEC visualization (workspace-scoped)")
      .in("w" / path[WorkspaceKey]("key") / "risk-trees" / path[TreeId]("treeId") / "lec-chart")
      .post
      .in(jsonBody[List[NodeId]].description("Array of node IDs to include in chart"))
      .out(stringBody.description("Vega-Lite JSON specification"))
      .out(header(Header.contentType(MediaType.ApplicationJson)))
