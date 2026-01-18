package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, JsonDecoder}
import java.time.Instant

/**
 * Per-node provenance metadata for reproducible Monte Carlo simulations.
 * 
 * Captures all information needed to exactly reproduce a risk node's simulation:
 * - HDR seed hierarchy (entityId, varIds, global seeds)
 * - Distribution configuration (type and parameters)
 * - Execution metadata (timestamp, library versions)
 * 
 * **Reproduction workflow:**
 * 1. Extract NodeProvenance from RiskTreeWithLEC response
 * 2. Reconstruct RiskSampler with exact parameters
 * 3. Re-run simulation with same nTrials and parallelism
 * 4. Verify identical outcomes
 * 
 * @param riskId Source risk identifier (e.g., "cyber-attack")
 * @param entityId Derived from riskId.hashCode.toLong (isolates random streams)
 * @param occurrenceVarId entityId.hashCode + 1000L (occurrence sampling stream)
 * @param lossVarId entityId.hashCode + 2000L (loss amount sampling stream)
 * @param globalSeed3 Global seed affecting all risks (currently 0L)
 * @param globalSeed4 Global seed affecting all risks (currently 0L)
 * @param distributionType "expert" (Metalog) or "lognormal" (BCG 90% CI)
 * @param distributionParams Type-specific parameters for loss distribution
 * @param timestamp Simulation execution timestamp
 * @param simulationUtilVersion Version of simulation-util library (HDR + Metalog)
 */
case class NodeProvenance(
  // HDR Configuration - Deterministic Random Number Generation
  riskId: String,
  entityId: Long,
  occurrenceVarId: Long,
  lossVarId: Long,
  globalSeed3: Long,
  globalSeed4: Long,
  
  // Distribution Configuration - Loss Amount Modeling
  distributionType: String,
  distributionParams: DistributionParams,
  
  // Execution Metadata
  timestamp: Instant,
  simulationUtilVersion: String
)

/**
 * Tree-level provenance metadata aggregating all node provenances.
 * 
 * Provides complete reproducibility information for an entire risk tree simulation:
 * - Global parameters (seeds, trials, parallelism)
 * - Per-node provenances indexed by riskId
 * 
 * **Usage:**
 * - Returned in RiskTreeWithLEC when ?includeProvenance=true
 * - Maps each risk node to its specific provenance
 * - Enables partial reproduction (single node or subtree)
 * 
 * @param treeId Database ID of the risk tree
 * @param globalSeeds (seed3, seed4) tuple applied to all risks
 * @param nTrials Number of Monte Carlo trials executed
 * @param parallelism Degree of parallelism used
 * @param nodeProvenances Map from riskId to NodeProvenance for each risk in tree
 */
case class TreeProvenance(
  treeId: Long,
  globalSeeds: (Long, Long),
  nTrials: Int,
  parallelism: Int,
  nodeProvenances: Map[String, NodeProvenance]
)

/**
 * Distribution-specific parameters for loss modeling.
 * 
 * Supports two distribution types:
 * 1. **Expert opinion (Metalog)**: Quantile-parameterized distribution
 *    - percentiles: Array of probability values [0.0, 1.0]
 *    - quantiles: Corresponding loss amounts
 *    - terms: Number of Metalog terms (3-16, default 9)
 * 
 * 2. **Lognormal (BCG)**: 90% confidence interval parameterization
 *    - minLoss: Lower bound (10th percentile)
 *    - maxLoss: Upper bound (90th percentile)
 *    - confidenceInterval: Always 0.90 for BCG approach
 */
sealed trait DistributionParams

/**
 * Expert opinion distribution parameters (Metalog quantile function).
 * 
 * @param percentiles Probability levels (e.g., [0.05, 0.5, 0.95])
 * @param quantiles Loss amounts at those percentiles (e.g., [1M, 5M, 25M])
 * @param terms Number of Metalog terms (3-16, higher = more flexible)
 */
case class ExpertDistributionParams(
  percentiles: Array[Double],
  quantiles: Array[Double],
  terms: Int
) extends DistributionParams

/**
 * Lognormal distribution parameters (BCG 90% CI approach).
 * 
 * @param minLoss Lower bound of 90% confidence interval (10th percentile)
 * @param maxLoss Upper bound of 90% confidence interval (90th percentile)
 * @param confidenceInterval Confidence level (always 0.90 for BCG)
 */
case class LognormalDistributionParams(
  minLoss: Long,
  maxLoss: Long,
  confidenceInterval: Double
) extends DistributionParams

object ExpertDistributionParams {
  given codec: JsonCodec[ExpertDistributionParams] = DeriveJsonCodec.gen[ExpertDistributionParams]
}

object LognormalDistributionParams {
  given codec: JsonCodec[LognormalDistributionParams] = DeriveJsonCodec.gen[LognormalDistributionParams]
}

object DistributionParams {
  // Custom JSON codec for sealed trait - try each subtype
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
  given codec: JsonCodec[NodeProvenance] = DeriveJsonCodec.gen[NodeProvenance]
}

object TreeProvenance {
  import sttp.tapir.Schema
  
  given codec: JsonCodec[TreeProvenance] = DeriveJsonCodec.gen[TreeProvenance]
  
  // TODO: Phase D - Replace with proper Schema derivation
  given schema: Schema[TreeProvenance] = Schema.any[TreeProvenance]
}
