package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.{ScenarioName, CommitHash}

/** Response DTO for `POST /w/{key}/scenarios`.
  *
  * Deliberately carries only `name`, not the composed Irmin branch
  * reference — the branch string embeds the workspace's own ID
  * (`scenarios.<workspaceId>.<name>`, DD-5), so returning it to the client
  * would put a `WorkspaceId` in wire text (2026-07-20/21 security review).
  * `X-Active-Branch` and this response both use `ScenarioName`; the server
  * composes the actual branch internally and never exposes it.
  */
final case class ScenarioResponse(name: ScenarioName.ScenarioName)

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
