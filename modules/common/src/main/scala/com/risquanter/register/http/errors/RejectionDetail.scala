package com.risquanter.register.http.errors

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

import com.risquanter.register.domain.errors.ValidationError

/** What a tree rejected about its own content.
  *
  * A tree's submitted fields and a submitted query fail in the same category:
  * the request was well formed and its content broke its own rules. Both
  * therefore answer one status, and they carry different structure, so the
  * status is shared by one wire case whose payload says which it was.
  */
@jsonDiscriminator(RejectionDetail.TagField)
enum RejectionDetail:
  /** Field-level failures, one entry per independent problem. */
  case Fields(errors: List[ValidationError])

  /** A query the engine refused. `QueryProblem` keeps all four of its cases,
    * including `Unparseable`'s character offset, which a flat `ValidationError`
    * triple has nowhere to carry.
    */
  case Query(problem: QueryProblem)

object RejectionDetail:
  /** The key naming which rejection a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. Each case's published value is its own name, which is
    * what the derived codec writes. */
  final val TagField = "kind"

  given codec: JsonCodec[RejectionDetail] = DeriveJsonCodec.gen[RejectionDetail]

  /** Declared here rather than left to automatic derivation at the use site:
    * `jsonBody` needs a schema as well as a codec, and putting it in the
    * companion is what makes one derivation serve every endpoint that carries
    * this payload (ADR-001 §2). The configuration names the discriminator, so
    * the published description states the key the decoder reads.
    *
    * The auto-derivation import is scoped to this body rather than to the file,
    * so it reaches the two cases without widening implicit scope across the
    * file. It needs no Iron schemas of its own: a validation error names its
    * location through `ErrorLocation`, which declares a schema that carries
    * them, and `QueryProblem` declares its own.
    */
  given schema: Schema[RejectionDetail] =
    import sttp.tapir.generic.auto.*
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[RejectionDetail]
