package com.risquanter.register.http.errors

import sttp.tapir.Schema
import sttp.tapir.generic.auto.*
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

import com.risquanter.register.domain.errors.ValidationError
// `ValidationError` names Iron types inside its location, and a JSON body needs
// a schema for each of them as well as a codec (ADR-001 §2).
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** What a tree rejected about its own content.
  *
  * A tree's submitted fields and a submitted query fail in the same category:
  * the request was well formed and its content broke its own rules. Both
  * therefore answer one status, and they carry different structure, so the
  * status is shared by one wire case whose payload says which it was.
  */
@jsonDiscriminator("kind")
enum RejectionDetail:
  /** Field-level failures, one entry per independent problem. */
  case Fields(errors: List[ValidationError])

  /** A query the engine refused. `QueryProblem` keeps all four of its cases,
    * including `Unparseable`'s character offset, which a flat `ValidationError`
    * triple has nowhere to carry.
    */
  case Query(problem: QueryProblem)

object RejectionDetail:
  given codec: JsonCodec[RejectionDetail] = DeriveJsonCodec.gen[RejectionDetail]

  /** Declared here rather than left to automatic derivation at the use site:
    * `jsonBody` needs a schema as well as a codec, and putting it in the
    * companion is what makes one derivation serve every endpoint that carries
    * this payload (ADR-001 §2).
    */
  given schema: Schema[RejectionDetail] = Schema.derived
