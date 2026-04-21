package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*

import com.risquanter.register.http.requests.RiskTreeUpdateRequest
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped tree endpoints.
  *
  * All tree-specific operations are served exclusively under `/w/{key}/...`
  * to enforce workspace capability checks.
  */
trait WorkspaceTreeEndpoints extends BaseEndpoint:

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