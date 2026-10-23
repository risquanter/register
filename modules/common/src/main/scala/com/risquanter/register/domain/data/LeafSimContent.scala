package com.risquanter.register.domain.data

import zio.json.{JsonEncoder, DeriveJsonEncoder, jsonDiscriminator, jsonHint}
import com.risquanter.register.domain.data.iron.{OccurrenceProbability, SeedVarId}

/**
 * The simulation-relevant projection of a `RiskLeaf` — the leaf cache-key
 * preimage.
 *
 * Contains exactly the fields that determine a leaf's simulated figures:
 * the stochastic identity (`seedVarId` — the HDR Var axis the streams derive
 * from) plus occurrence probability and the loss distribution shape.
 * `name`, ULID, and `parentId` are deliberately absent: renames and moves
 * preserve the cache, and content-identical leaves in different trees or
 * workspaces produce the same projection (cross-node cache hits).
 *
 * The leaf's `ContentHash` is `sha256(LeafSimContent.from(leaf).toJson)`
 * (computed in the server module's `ContentHashIndex`).
 *
 * **The emitted bytes are a storage contract.** The zio-json derived encoder
 * emits keys in declaration order; reordering fields or renaming a key changes
 * every leaf cache key (mass cache miss, never incorrect results) and makes
 * every stored override base stamp report stale. Guarded by the byte-stability
 * snapshot test in `LeafSimContentSpec` — if that test breaks, you have changed
 * the cache-key preimage.
 *
 * The projection renders its own bytes through the private `Raw` below rather
 * than through the leaf's wire codec. That is what keeps the cache key
 * independent of the wire: a change to the shape's serialised form fails the
 * snapshot test here instead of silently rekeying every entry.
 *
 * Encoder only: the projection is hashed, never decoded — nothing stores or
 * reads it back.
 */
final case class LeafSimContent(
  seedVarId: SeedVarId.SeedVarId,
  probability: OccurrenceProbability,
  shape: DistributionShape
)

object LeafSimContent {

  /** Project the simulation-relevant fields out of a leaf. The shape carries its
    * own mode, so there is nothing to flatten and no field to leave absent. */
  def from(leaf: RiskLeaf): LeafSimContent =
    LeafSimContent(leaf.seedVarId, leaf.probability, leaf.shape)

  /** Wire form: primitives only, declaration order = emission order (ADR-001). */
  @jsonDiscriminator("distributionType")
  private sealed trait ShapeRaw

  @jsonHint("expert")
  private case class ExpertRaw(
    percentiles: Vector[Double],
    quantiles: Vector[Double],
    terms: Option[Int]
  ) extends ShapeRaw

  @jsonHint("lognormal")
  private case class LognormalRaw(minLoss: Long, maxLoss: Long) extends ShapeRaw

  private object ShapeRaw {
    given rawEncoder: JsonEncoder[ShapeRaw] = DeriveJsonEncoder.gen[ShapeRaw]

    /** The one site that widens a shape to primitives for hashing. */
    def of(shape: DistributionShape): ShapeRaw = shape match
      case DistributionShape.Expert(anchors, terms) =>
        ExpertRaw(anchors.percentiles, anchors.losses, terms.map(t => t: Int))
      case DistributionShape.Lognormal(bounds) =>
        LognormalRaw(bounds.min, bounds.max)
  }

  private case class Raw(
    seedVarId: Long,
    probability: Double,
    shape: ShapeRaw
  )
  private object Raw {
    given rawEncoder: JsonEncoder[Raw] = DeriveJsonEncoder.gen[Raw]
  }

  given encoder: JsonEncoder[LeafSimContent] = JsonEncoder[Raw].contramap { c =>
    Raw(
      seedVarId = c.seedVarId.value,
      probability = c.probability,
      shape = ShapeRaw.of(c.shape)
    )
  }
}
