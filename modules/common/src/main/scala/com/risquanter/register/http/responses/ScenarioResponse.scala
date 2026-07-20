package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.{ScenarioName, BranchRef, CommitHash}

/** Response DTO for `POST /w/{key}/scenarios`. */
final case class ScenarioResponse(name: ScenarioName.ScenarioName, branch: BranchRef)

object ScenarioResponse:
  given codec: JsonCodec[ScenarioResponse] = DeriveJsonCodec.gen[ScenarioResponse]

/** Response DTO for `GET /w/{key}/scenarios` — one entry per scenario.
  *
  * `head` is the commit the caller must echo back as `deleteScenario`'s
  * `If-Match` precondition (DD-5 CAS, locked 2026-07-20).
  *
  * A dedicated DTO rather than reusing `ScenarioService`'s own
  * `ScenarioSummary` directly: `ScenarioSummary` lives in `modules/server`
  * (service-layer type), but this endpoint definition lives in `modules/common`,
  * which is cross-compiled for the Scala.js frontend too — a server-only type
  * cannot appear in a shared endpoint signature.
  */
final case class ScenarioSummaryResponse(name: ScenarioName.ScenarioName, head: CommitHash)

object ScenarioSummaryResponse:
  given codec: JsonCodec[ScenarioSummaryResponse] = DeriveJsonCodec.gen[ScenarioSummaryResponse]
