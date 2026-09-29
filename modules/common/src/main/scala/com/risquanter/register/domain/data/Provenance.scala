package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, JsonDecoder}
import java.time.Instant
import com.risquanter.register.domain.data.iron.{PositiveInt, NonNegativeLong, ValidationUtil}
import io.github.iltotore.iron.*

/**
 * All inputs needed to reproduce one leaf's simulation: HDR seed hierarchy, distribution
 * configuration, and execution metadata (timestamp, library version).
 *
 * The recorded seeds are the same `HdrStreams` the sampler consumed — both come from the
 * single derivation site `SeedDerivation.streams`. This record is content-only: attribution
 * is by node, via the `LossDistribution` that carries it beside its `nodeId`. A portfolio
 * has no provenance record of its own.
 */
case class NodeProvenance(
  // HDR seed hierarchy
  entityId: Long,
  occurrenceVarId: Long,   // 2 × seedVarId (even stream)
  lossVarId: Long,         // 2 × seedVarId + 1 (odd stream)
  globalSeed3: Long,
  globalSeed4: Long,

  // Distribution configuration
  distributionType: String,
  distributionParams: DistributionParams,

  // Execution metadata
  timestamp: Instant,
  metalogDistributionVersion: String
)

/** Distribution-specific parameters for loss modeling. Two variants: expert (Metalog quantile-fit) and lognormal (BCG 90% CI). */
sealed trait DistributionParams

/** Metalog quantile-function parameters. */
case class ExpertDistributionParams(
  percentiles: Array[Double],
  quantiles: Array[Double],
  terms: PositiveInt
) extends DistributionParams

/** Lognormal distribution parameters using the BCG 90% CI approach. */
case class LognormalDistributionParams(
  minLoss: NonNegativeLong,
  maxLoss: NonNegativeLong,
  confidenceInterval: Double
) extends DistributionParams

object ExpertDistributionParams {
  private case class Raw(percentiles: Array[Double], quantiles: Array[Double], terms: Int)
  private object Raw { given rawCodec: JsonCodec[Raw] = DeriveJsonCodec.gen[Raw] }

  given codec: JsonCodec[ExpertDistributionParams] = JsonCodec(
    JsonEncoder[Raw].contramap(p => Raw(p.percentiles, p.quantiles, p.terms.toInt)),
    Raw.rawCodec.decoder.mapOrFail { raw =>
      ValidationUtil.refinePositiveInt(raw.terms, "terms")
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
      (ValidationUtil.refineNonNegativeLong(raw.minLoss, "minLoss"),
       ValidationUtil.refineNonNegativeLong(raw.maxLoss, "maxLoss")) match {
        case (Right(min), Right(max)) => Right(LognormalDistributionParams(min, max, raw.confidenceInterval))
        case (Left(e1), Left(e2))     => Left((e1 ++ e2).map(_.message).mkString("; "))
        case (Left(e), _)             => Left(e.map(_.message).mkString("; "))
        case (_, Left(e))             => Left(e.map(_.message).mkString("; "))
      }
    }
  )
}

object DistributionParams {
  given encoder: JsonEncoder[DistributionParams] = new JsonEncoder[DistributionParams] {
    override def unsafeEncode(a: DistributionParams, indent: Option[Int], out: zio.json.internal.Write): Unit = a match {
      case p: ExpertDistributionParams => 
        ExpertDistributionParams.codec.encoder.unsafeEncode(p, indent, out)
      case p: LognormalDistributionParams => 
        LognormalDistributionParams.codec.encoder.unsafeEncode(p, indent, out)
    }
  }
  
  given decoder: JsonDecoder[DistributionParams] = 
    ExpertDistributionParams.codec.decoder.widen[DistributionParams] <> 
    LognormalDistributionParams.codec.decoder.widen[DistributionParams]
}

object NodeProvenance {
  import sttp.tapir.Schema

  given codec: JsonCodec[NodeProvenance] = DeriveJsonCodec.gen[NodeProvenance]
  given schema: Schema[NodeProvenance] = Schema.any[NodeProvenance]
}

