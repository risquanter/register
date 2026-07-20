package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.ScenarioName

/** Request body for `POST /w/{key}/scenarios`.
  *
  * `forkOf` is wire-level optionality (absent = fork from main), decoded at
  * the controller into the mandatory two-case `ScenarioSource` selector
  * (`None` -> `Main`, `Some(name)` -> `ForkOf(name)`) before reaching
  * `ScenarioService.create` — `Option` is correct here because it reflects a
  * real absence on the wire, not an internal business choice (TODO.md item 22).
  */
final case class CreateScenarioRequest(
  name: ScenarioName.ScenarioName,
  forkOf: Option[ScenarioName.ScenarioName]
)

object CreateScenarioRequest:
  given codec: JsonCodec[CreateScenarioRequest] = DeriveJsonCodec.gen[CreateScenarioRequest]
