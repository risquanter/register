package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*

import com.risquanter.register.http.requests.RiskTreeUpdateRequest
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, ScenarioName}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped tree endpoints.
  *
  * All tree-specific operations are served exclusively under `/w/{key}/...`
  * to enforce workspace capability checks.
  *
  * Each endpoint accepts an optional `X-Active-Branch` header (milestone-2b
  * Phase B item 4b, redesigned 2026-07-21) naming the scenario to read/write
  * by its `ScenarioName` — absent header targets `main` (DD-4 default),
  * unchanged from pre-Phase-B behaviour. The header never carries a raw
  * branch string: the controller composes the actual Irmin branch from this
  * name and the caller's own resolved `WorkspaceId`, so a scenario name from
  * another workspace can never resolve to that workspace's branch — see
  * `ActiveBranch.resolve`.
  */
trait WorkspaceTreeEndpoints extends BaseEndpoint:

  val getWorkspaceTreeByIdEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeById")
      .description("Get tree summary (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .get
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
      .out(jsonBody[Option[SimulationResponse]])

  val getWorkspaceTreeStructureEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceTreeStructure")
      .description("Get full tree structure (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "structure")
      .get
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
      .out(jsonBody[Option[RiskTree]])

  val updateWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("updateWorkspaceTree")
      .description("Full replacement update of tree (workspace-scoped)")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .put
      .in(jsonBody[RiskTreeUpdateRequest])
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
      .out(jsonBody[SimulationResponse])

  val deleteWorkspaceTreeEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("deleteWorkspaceTree")
      .description("Delete a single tree from workspace")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId"))
      .delete
      .in(header[Option[ScenarioName.ScenarioName]]("X-Active-Branch"))
      .out(jsonBody[SimulationResponse])