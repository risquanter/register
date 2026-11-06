package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.schemaForCaseClass
import sttp.model.StatusCode

import com.risquanter.register.http.requests.CreateScenarioRequest
import com.risquanter.register.http.responses.{ScenarioListResponse, ScenarioResponse, MergePreviewResponse, MergeScenarioResponse}
import com.risquanter.register.domain.data.iron.{ScenarioName, CommitHash}
import com.risquanter.register.domain.errors.RequestHeaderName
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped scenario endpoints: create, list, merge preview, merge and
  * delete. They identify a scenario by name and have no target branch to state,
  * so `X-Branch` does not appear here.
  */
trait ScenarioEndpoints extends BaseEndpoint:

  private val scenariosRoute = workspaceRoute / "scenarios"

  val createScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("createScenario")
      .description("Create a scenario from a required source: a branch head (main or another scenario) or a specific commit (fork-from-history)")
      .in(scenariosRoute)
      .post
      .in(jsonBody[CreateScenarioRequest])
      .out(jsonBody[ScenarioResponse])

  val listScenariosEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("listScenarios")
      .description("List scenarios in workspace with each one's current head commit")
      .in(scenariosRoute)
      .get
      .out(jsonBody[ScenarioListResponse])

  val previewScenarioMergeEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("previewScenarioMerge")
      .description("Preview merging a scenario into main: byte-level three-way conflict check against the merge base (ADR-032); changes nothing")
      .in(scenariosRoute / path[ScenarioName.ScenarioName]("name") / "merge-preview")
      .get
      .out(jsonBody[MergePreviewResponse])

  val mergeScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("mergeScenario")
      .description("Merge a scenario into main (Irmin native three-way merge); 409 with MERGE_CONFLICT when conflicting paths exist")
      .in(scenariosRoute / path[ScenarioName.ScenarioName]("name") / "merge")
      .post
      .out(jsonBody[MergeScenarioResponse])

  val deleteScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("deleteScenario")
      .description("Delete a scenario. Requires If-Match with the head observed from listScenarios (CAS, DD-5 Option A)")
      .in(scenariosRoute / path[ScenarioName.ScenarioName]("name"))
      .in(header[CommitHash](RequestHeaderName.IfMatch.wireName))
      .delete
      .out(statusCode(StatusCode.NoContent))
