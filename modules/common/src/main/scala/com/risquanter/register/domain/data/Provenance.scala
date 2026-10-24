package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, jsonDiscriminator}
import java.time.Instant
import com.risquanter.register.domain.data.iron.{MetalogTermCount, PositiveLong, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName}

/**
 * All inputs needed to reproduce one leaf's simulation: HDR seed hierarchy, distribution
 * configuration, and execution metadata (timestamp, library version).
 *
 * The recorded seeds are the same `HdrStreams` the sampler consumed — both come from the
 * single derivation site `SeedDerivation.streams`. This record is content-only: attribution
 * is by node, via the `LossDistribution` that carries it beside its `nodeId`. A portfolio
 * has no provenance record of its own.
 *
 * The mode is not a field of its own: `distributionParams` is a tagged union, so its
 * case carries the mode and the two cannot disagree.
 */
case class NodeProvenance(
  // HDR seed hierarchy
  entityId: Long,
  occurrenceVarId: Long,   // 2 × seedVarId (even stream)
  lossVarId: Long,         // 2 × seedVarId + 1 (odd stream)
  globalSeed3: Long,
  globalSeed4: Long,

  // Distribution configuration
  distributionParams: DistributionParams,

  // Execution metadata
  timestamp: Instant,
  metalogDistributionVersion: String
)

/** Distribution-specific parameters for loss modeling, as the simulator consumed
  * them. Two variants: metalog (quantile fit) and lognormal (BCG 90% CI).
  *
  * The tag field is the payload's only discriminator: the two cases are
  * distinguished by it rather than by which fields are present. Discriminating
  * by field presence makes a malformed record of one case report the other
  * case's complaint. Its value is the case name, derived rather than declared.
  */
@jsonDiscriminator(DistributionShape.TagField)
sealed trait DistributionParams

object DistributionParams {
  import sttp.tapir.Schema

  /** Metalog quantile-function parameters. */
  final case class Metalog(
    percentiles: Vector[Double],
    quantiles: Vector[Double],
    terms: MetalogTermCount
  ) extends DistributionParams

  /** Lognormal distribution parameters using the BCG 90% CI approach. */
  final case class Lognormal(
    minLoss: PositiveLong,
    maxLoss: PositiveLong,
    confidenceInterval: Double
  ) extends DistributionParams

  object Metalog {
    private case class Raw(percentiles: Vector[Double], quantiles: Vector[Double], terms: Int)
    private object Raw { given rawCodec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw] }

    given codec: JsonCodec[Metalog] = JsonCodec(
      JsonEncoder[Raw].contramap(p => Raw(p.percentiles, p.quantiles, p.terms)),
      Raw.rawCodec.decoder.mapOrFail { raw =>
        ValidationUtil.refineMetalogTermCount(raw.terms, ErrorLocation.requestField(FieldName.Terms))
          .map(t => Metalog(raw.percentiles, raw.quantiles, t))
          .left.map(_.map(_.message).mkString("; "))
      }
    )
  }

  object Lognormal {
    private case class Raw(minLoss: Long, maxLoss: Long, confidenceInterval: Double)
    private object Raw { given rawCodec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw] }

    given codec: JsonCodec[Lognormal] = JsonCodec(
      JsonEncoder[Raw].contramap(p => Raw(p.minLoss, p.maxLoss, p.confidenceInterval)),
      Raw.rawCodec.decoder.mapOrFail { raw =>
        (ValidationUtil.refinePositiveLong(raw.minLoss, ErrorLocation.requestField(FieldName.MinLoss)),
         ValidationUtil.refinePositiveLong(raw.maxLoss, ErrorLocation.requestField(FieldName.MaxLoss))) match {
          case (Right(min), Right(max)) => Right(Lognormal(min, max, raw.confidenceInterval))
          case (Left(e1), Left(e2))     => Left((e1 ++ e2).map(_.message).mkString("; "))
          case (Left(e), _)             => Left(e.map(_.message).mkString("; "))
          case (_, Left(e))             => Left(e.map(_.message).mkString("; "))
        }
      }
    )
  }

  given codec: JsonCodec[DistributionParams] = DeriveJsonCodec.gen[DistributionParams]

  /** Declared rather than left to a holder's derivation: auto-derivation driven
    * from a record holding this trait does not descend into it, so a holder
    * would publish an unconstrained object instead of the two modes. The
    * configuration names the discriminator, so the document states the key the
    * decoder reads rather than leaving a reader to infer the mode from which
    * fields are present. */
  given schema: Schema[DistributionParams] =
    import sttp.tapir.generic.auto.*
    import sttp.tapir.generic.Configuration
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    given Configuration = Configuration.default.withDiscriminator(DistributionShape.TagField)
    Schema.derived[DistributionParams]
}

object NodeProvenance {
  import sttp.tapir.Schema

  given codec: JsonCodec[NodeProvenance] = DeriveJsonCodec.gen[NodeProvenance]
  // The published description is the record's real field list, with the
  // parameter union described as the coproduct it is, through the schema
  // `DistributionParams` declares. The auto-derivation import is scoped to this
  // body rather than to the file, so it does not widen implicit scope.
  given schema: Schema[NodeProvenance] =
    import sttp.tapir.generic.auto.*
    Schema.derived[NodeProvenance]
}
