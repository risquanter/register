package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, jsonDiscriminator, jsonHint}
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
  * them. Two variants: expert (Metalog quantile-fit) and lognormal (BCG 90% CI).
  *
  * The tag field is the payload's only discriminator: the two cases are
  * distinguished by it rather than by which fields are present. Discriminating
  * by field presence makes a malformed record of one case report the other
  * case's complaint.
  */
@jsonDiscriminator("distributionType")
sealed trait DistributionParams

/** Metalog quantile-function parameters. */
@jsonHint("expert")
final case class ExpertDistributionParams(
  percentiles: Vector[Double],
  quantiles: Vector[Double],
  terms: MetalogTermCount
) extends DistributionParams

/** Lognormal distribution parameters using the BCG 90% CI approach. */
@jsonHint("lognormal")
final case class LognormalDistributionParams(
  minLoss: PositiveLong,
  maxLoss: PositiveLong,
  confidenceInterval: Double
) extends DistributionParams

object ExpertDistributionParams {
  private case class Raw(percentiles: Vector[Double], quantiles: Vector[Double], terms: Int)
  private object Raw { given rawCodec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw] }

  given codec: JsonCodec[ExpertDistributionParams] = JsonCodec(
    JsonEncoder[Raw].contramap(p => Raw(p.percentiles, p.quantiles, p.terms)),
    Raw.rawCodec.decoder.mapOrFail { raw =>
      ValidationUtil.refineMetalogTermCount(raw.terms, ErrorLocation.requestField(FieldName.Terms))
        .map(t => ExpertDistributionParams(raw.percentiles, raw.quantiles, t))
        .left.map(_.map(_.message).mkString("; "))
    }
  )
}

object LognormalDistributionParams {
  private case class Raw(minLoss: Long, maxLoss: Long, confidenceInterval: Double)
  private object Raw { given rawCodec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw] }

  given codec: JsonCodec[LognormalDistributionParams] = JsonCodec(
    JsonEncoder[Raw].contramap(p => Raw(p.minLoss, p.maxLoss, p.confidenceInterval)),
    Raw.rawCodec.decoder.mapOrFail { raw =>
      (ValidationUtil.refinePositiveLong(raw.minLoss, ErrorLocation.requestField(FieldName.MinLoss)),
       ValidationUtil.refinePositiveLong(raw.maxLoss, ErrorLocation.requestField(FieldName.MaxLoss))) match {
        case (Right(min), Right(max)) => Right(LognormalDistributionParams(min, max, raw.confidenceInterval))
        case (Left(e1), Left(e2))     => Left((e1 ++ e2).map(_.message).mkString("; "))
        case (Left(e), _)             => Left(e.map(_.message).mkString("; "))
        case (_, Left(e))             => Left(e.map(_.message).mkString("; "))
      }
    }
  )
}

object DistributionParams {
  given codec: JsonCodec[DistributionParams] = DeriveJsonCodec.gen[DistributionParams]
}

object NodeProvenance {
  import sttp.tapir.Schema

  given codec: JsonCodec[NodeProvenance] = DeriveJsonCodec.gen[NodeProvenance]
  given schema: Schema[NodeProvenance] = Schema.any[NodeProvenance]
}
