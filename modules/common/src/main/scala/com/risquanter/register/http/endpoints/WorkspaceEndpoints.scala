package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.{StatusCode, Header, MediaType}

import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, QueryRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse, QueryResponse}
import com.risquanter.register.domain.data.{RiskTree, LECCurveResponse, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId}
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
  *   - A15: SSE scoped to workspace key (implemented in SSEEndpoints)
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
    authedBaseEndpoint
      .tag("workspaces")
      .name("listWorkspaceTrees")
      .description("List trees in workspace")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees")
      .get
      .out(jsonBody[List[SimulationResponse]])

  val createWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("createWorkspaceTree")
      .description("Create tree in workspace")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees")
      .post
      .in(jsonBody[RiskTreeDefinitionRequest])
      .out(jsonBody[SimulationResponse])

  val rotateWorkspaceKeySecretEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("rotateWorkspace")
      .description("Rotate workspace key (instant revocation)")
      .in("w" / path[WorkspaceKeySecret]("key") / "rotate")
      .post
      .out(jsonBody[WorkspaceRotateResponse])

  val deleteWorkspaceEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("deleteWorkspace")
      .description("Hard delete workspace + all associated trees")
      .in("w" / path[WorkspaceKeySecret]("key"))
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
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeById")
      .description("Get tree summary (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .get
      .out(jsonBody[Option[SimulationResponse]])

  val getWorkspaceTreeStructureEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeStructure")
      .description("Get full tree structure (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "structure")
      .get
      .out(jsonBody[Option[RiskTree]])

  val updateWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("updateWorkspaceTree")
      .description("Full replacement update of tree (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .put
      .in(jsonBody[RiskTreeUpdateRequest])
      .out(jsonBody[SimulationResponse])

  val deleteWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("deleteWorkspaceTree")
      .description("Delete a single tree from workspace")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .delete
      .out(jsonBody[SimulationResponse])

  val invalidateWorkspaceCacheEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("invalidateWorkspaceCache")
      .description("Invalidate cache for a node (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "invalidate" / path[NodeId]("nodeId"))
      .post
      .out(jsonBody[InvalidationResponse])

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  // TODO-REMOVE: No real-world clients. All LEC rendering uses lec-multi
  // (Map[NodeId, LECNodeCurve]). Remove along with LECCurveResponse,
  // RiskTreeService.getLECCurve, WorkspaceController.getLECCurve, and their tests.
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

  // ── Workspace-scoped vague quantifier query (ADR-028) ─────────────

  val queryWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("queryWorkspaceTree")
      .description("Evaluate a vague quantifier query against a risk tree")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "query")
      .post
      .in(jsonBody[QueryRequest])
      .out(jsonBody[QueryResponse])
