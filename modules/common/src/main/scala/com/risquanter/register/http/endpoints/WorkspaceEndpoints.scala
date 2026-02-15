package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.StatusCode

import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse}
import com.risquanter.register.domain.data.iron.{WorkspaceKey, TreeId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped API endpoints.
  *
  * Security features represented here:
  * - A6: `DELETE /w/{key}` hard delete endpoint
  * - A27: `POST /workspaces` accepts caller IP via `X-Forwarded-For`
  */
trait WorkspaceEndpoints extends BaseEndpoint:

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
