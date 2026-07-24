package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.model.StatusCode

import com.risquanter.register.http.requests.CreateScenarioRequest
import com.risquanter.register.http.responses.{ScenarioResponse, ScenarioSummaryResponse, MergePreviewResponse, MergeScenarioResponse}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, ScenarioName, CommitHash}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Workspace-scoped scenario endpoints (DD-5/DD-10).
  *
  * Scoped to scenario lifecycle and merge (create/list/delete/merge) — these
  * endpoints identify a scenario by name and never need to know "the active
  * branch", so `X-Active-Branch` (DD-8) does not appear here. Retrofitting
  * that header onto the existing tree/query/analysis endpoints is a separate,
  * larger change (2026-07-20 scoped split — see DD-8 note in
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

  val previewScenarioMergeEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("previewScenarioMerge")
      .description("Preview merging a scenario into main: byte-level three-way conflict check against the merge base (ADR-032); changes nothing")
      .in("w" / path[WorkspaceKeySecret]("key") / "scenarios" / path[ScenarioName.ScenarioName]("name") / "merge-preview")
      .get
      .out(jsonBody[MergePreviewResponse])

  val mergeScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("mergeScenario")
      .description("Merge a scenario into main (Irmin native three-way merge); 409 with MERGE_CONFLICT when conflicting paths exist")
      .in("w" / path[WorkspaceKeySecret]("key") / "scenarios" / path[ScenarioName.ScenarioName]("name") / "merge")
      .post
      .out(jsonBody[MergeScenarioResponse])

  val deleteScenarioEndpoint =
    authedBaseEndpoint
      .tag("scenarios")
      .name("deleteScenario")
      .description("Delete a scenario. Requires If-Match with the head observed from listScenarios (CAS, DD-5 Option A)")
      .in("w" / path[WorkspaceKeySecret]("key") / "scenarios" / path[ScenarioName.ScenarioName]("name"))
      .in(header[CommitHash]("If-Match"))
      .delete
      .out(statusCode(StatusCode.NoContent))
