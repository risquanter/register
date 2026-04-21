package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.StatusCode

import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse}
import com.risquanter.register.domain.data.iron.WorkspaceKeySecret
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace lifecycle API endpoints.
  *
  * Owns workspace bootstrap and lifecycle management plus workspace-level
  * tree creation and listing.
  */
trait WorkspaceLifecycleEndpoints extends BaseEndpoint:

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