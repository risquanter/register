package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import com.risquanter.register.domain.data.iron.{ScenarioName, BranchChoice, CommitHash}

/** Where a new scenario branches from. A required, discriminated choice — there
  * is no implicit default. `Branch` names a live branch head (`"main"` or
  * another scenario slug via `BranchChoice`); `Commit` names a specific
  * historical commit.
  *
  * The tag field is the body's only discriminator, and each case's tag value is
  * its own name, derived rather than declared.
  */
@jsonDiscriminator(ScenarioSourceDto.TagField)
sealed trait ScenarioSourceDto

object ScenarioSourceDto:
  /** The key naming which source a body states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. */
  final val TagField = "type"

  final case class Branch(name: BranchChoice) extends ScenarioSourceDto
  final case class Commit(hash: CommitHash) extends ScenarioSourceDto

  given codec: JsonCodec[ScenarioSourceDto] = DeriveJsonCodec.gen[ScenarioSourceDto]

  /** Declared rather than left to a holder's derivation, so the request body's
    * published description is the two cases rather than an unconstrained
    * object, and names the key that decides between them. */
  given schema: Schema[ScenarioSourceDto] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[ScenarioSourceDto]

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
