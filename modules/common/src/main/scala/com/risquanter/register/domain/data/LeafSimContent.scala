package com.risquanter.register.domain.data

import zio.json.{JsonEncoder, DeriveJsonEncoder}
import com.risquanter.register.domain.data.iron.{DistributionType, OccurrenceProbability, NonNegativeLong, PositiveInt, SeedVarId}

/**
 * The simulation-relevant projection of a `RiskLeaf` — the leaf cache-key
 * preimage (DD-16, closed 2026-07-16).
 *
 * Contains exactly the fields that determine a leaf's simulated figures:
 * the stochastic identity (`seedVarId` — the HDR Var axis the streams derive
 * from) plus occurrence probability and loss-distribution parameters.
 * `name`, ULID, and `parentId` are deliberately absent: renames and moves
 * preserve the cache, and content-identical leaves in different trees or
 * workspaces produce the same projection (cross-node cache hits).
 *
 * The leaf's `ContentHash` is `sha256(LeafSimContent.from(leaf).toJson)`
 * (computed in the server module's `ContentHashIndex`).
 *
 * **Field order is a storage contract.** The zio-json derived encoder emits
 * keys in declaration order; reordering fields changes every leaf cache key
 * (mass cache miss, never incorrect results). Guarded by the byte-stability
 * snapshot test in `LeafSimContentSpec` — if that test breaks, you have
 * changed the cache-key preimage.
 *
 * Encoder only: the projection is hashed, never decoded — nothing stores or
 * reads it back.
 */
final case class LeafSimContent(
  seedVarId: SeedVarId.SeedVarId,
  probability: OccurrenceProbability,
  distributionType: DistributionType,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  terms: Option[PositiveInt]
)

object LeafSimContent {

  /** Project the simulation-relevant fields out of a leaf. */
  def from(leaf: RiskLeaf): LeafSimContent =
    LeafSimContent(
      seedVarId = leaf.seedVarId,
      probability = leaf.probability,
      distributionType = leaf.distributionType,
      percentiles = leaf.percentiles,
      quantiles = leaf.quantiles,
      minLoss = leaf.minLoss,
      maxLoss = leaf.maxLoss,
      terms = leaf.terms
    )

  /** Wire form: primitives only, declaration order = emission order (ADR-001). */
  private case class Raw(
    seedVarId: Long,
    probability: Double,
    distributionType: String,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[Long],
    maxLoss: Option[Long],
    terms: Option[Int]
  )
  private object Raw {
    given rawEncoder: JsonEncoder[Raw] = DeriveJsonEncoder.gen[Raw]
  }

  given encoder: JsonEncoder[LeafSimContent] = JsonEncoder[Raw].contramap { c =>
    Raw(
      seedVarId = c.seedVarId.value,
      probability = c.probability,
      distributionType = c.distributionType.toString,
      percentiles = c.percentiles,
      quantiles = c.quantiles,
      minLoss = c.minLoss.map(identity),
      maxLoss = c.maxLoss.map(identity),
      terms = c.terms.map(_.toInt)
    )
  }
}
