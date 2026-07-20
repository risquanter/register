package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.StatusCode

import com.risquanter.register.http.requests.CreateScenarioRequest
import com.risquanter.register.http.responses.{ScenarioResponse, ScenarioSummaryResponse}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, ScenarioName, CommitHash}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped scenario CRUD endpoints (milestone-2b Phase B, DD-5).
  *
  * Scoped to scenario lifecycle only (create/list/delete) — these endpoints
  * identify a scenario by name and never need to know "the active branch",
  * so `X-Active-Branch` (DD-8) does not appear here. Retrofitting that header
  * onto the existing tree/query/analysis endpoints is a separate, larger
  * change (2026-07-20 scoped split — see DD-8 note in
  * milestone-2b-cache-and-decisions.md).
  */
trait ScenarioEndpoints extends BaseEndpoint:

  val createScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("createScenario")
      .description("Create a scenario, forked from main or from an existing scenario's current head")
      .in("w" / path[WorkspaceKeySecret]("key") / "scenarios")
      .post
      .in(jsonBody[CreateScenarioRequest])
      .out(jsonBody[ScenarioResponse])

  val listScenariosEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("listScenarios")
      .description("List scenarios in workspace with each one's current head commit")
      .in("w" / path[WorkspaceKeySecret]("key") / "scenarios")
      .get
      .out(jsonBody[List[ScenarioSummaryResponse]])

  val deleteScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("deleteScenario")
      .description("Delete a scenario. Requires If-Match with the head observed from listScenarios (CAS, DD-5 Option A)")
      .in("w" / path[WorkspaceKeySecret]("key") / "scenarios" / path[ScenarioName.ScenarioName]("name"))
      .in(header[CommitHash]("If-Match"))
      .delete
      .out(statusCode(StatusCode.NoContent))
