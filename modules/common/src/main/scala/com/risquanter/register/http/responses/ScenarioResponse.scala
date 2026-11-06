package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.{ScenarioName, CommitHash}

/** Response DTO for `POST /w/{key}/scenarios`.
  *
  * Deliberately carries only `name`, not the composed Irmin branch
  * reference — the branch string embeds the workspace's own ID
  * (`scenarios.<workspaceId>.<name>`), so returning it to the client
  * would put a `WorkspaceId` in wire text (ADR-036).
  * `X-Active-Branch` and this response both use `ScenarioName`; the server
  * composes the actual branch internally and never exposes it.
  */
final case class ScenarioResponse(name: ScenarioName.ScenarioName)

object ScenarioResponse:
  given codec: JsonCodec[ScenarioResponse] = DeriveJsonCodec.gen[ScenarioResponse]

/** One scenario in the answer to `GET /w/{key}/scenarios`.
  *
  * `head` is the commit the caller must echo back as `deleteScenario`'s
  * `If-Match` compare-and-set precondition.
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

/** The scenarios a workspace holds. */
final case class ScenarioListResponse(scenarios: List[ScenarioSummaryResponse])

object ScenarioListResponse:
  given codec: JsonCodec[ScenarioListResponse] = DeriveJsonCodec.gen[ScenarioListResponse]
