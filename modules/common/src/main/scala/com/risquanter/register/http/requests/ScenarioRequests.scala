package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator, jsonHint}
import com.risquanter.register.domain.data.iron.{ScenarioName, BranchChoice, CommitHash}

/** Where a new scenario branches from (E5). A required, discriminated choice —
  * there is no implicit default. `Branch` names a live branch head (`"main"`
  * or another scenario slug via `BranchChoice`); `Commit` names a specific
  * historical commit (fork-from-history). Discriminator field is `"type"`,
  * values `"branch"` / `"commit"`.
  */
@jsonDiscriminator("type")
sealed trait ScenarioSourceDto

object ScenarioSourceDto:
  @jsonHint("branch") final case class Branch(name: BranchChoice) extends ScenarioSourceDto
  @jsonHint("commit") final case class Commit(hash: CommitHash) extends ScenarioSourceDto

  given codec: JsonCodec[ScenarioSourceDto] = DeriveJsonCodec.gen[ScenarioSourceDto]

/** Request body for `POST /w/{key}/scenarios`.
  *
  * `source` is a required discriminated selector (E5): fork from a branch head
  * (`Branch`, main referenced as a branch) or from a specific commit
  * (`Commit`). The controller maps it to the service-layer `ScenarioSource`.
  */
final case class CreateScenarioRequest(
  name: ScenarioName.ScenarioName,
  source: ScenarioSourceDto
)

object CreateScenarioRequest:
  given codec: JsonCodec[CreateScenarioRequest] = DeriveJsonCodec.gen[CreateScenarioRequest]
