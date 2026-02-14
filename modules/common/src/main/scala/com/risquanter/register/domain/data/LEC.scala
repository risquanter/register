package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.tapir.Schema

/** Single point on a Loss Exceedance Curve
  * 
  * Represents: At this loss level, what's the probability of exceeding it?
  * 
  * @param loss Loss amount in millions (e.g., 1000 = $1B)
  * @param exceedanceProbability P(Loss >= loss), range [0.0, 1.0]
  */
final case class LECPoint(
  loss: Long,
  exceedanceProbability: Double
)

object LECPoint {
  given codec: JsonCodec[LECPoint] = DeriveJsonCodec.gen[LECPoint]
  given schema: Schema[LECPoint] = Schema.derived[LECPoint]
}

/** LEC curve data for a single node (flat, non-recursive)
  * 
  * Serialized representation of computed LEC data for API responses.
  * Contains curve points sampled at specific loss values (ticks).
  * 
  * Design (post ADR-004a/005 redesign):
  * - Flat structure: no embedded children, just childIds for navigation
  * - Client fetches child curves separately via node-specific endpoints
  * - Enables per-node caching and SSE streaming
  * 
  * @param id Node identifier (matches RiskNode.id)
  * @param name Human-readable name
  * @param curve Loss exceedance curve points (loss → P(Loss >= loss))
  * @param quantiles Key percentiles (p50, p90, p95, p99) for quick reference
  * @param childIds IDs of child nodes (for navigation, not embedded data)
  * @param provenances Opt-in provenance metadata for reproducibility (via ?includeProvenance=true)
  */
final case class LECCurveResponse(
  id: String,
  name: String,
  curve: Vector[LECPoint],
  quantiles: Map[String, Double],
  childIds: Option[List[String]] = None,
  provenances: List[NodeProvenance] = Nil
)

object LECCurveResponse {
  given codec: JsonCodec[LECCurveResponse] = DeriveJsonCodec.gen[LECCurveResponse]
  given schema: Schema[LECCurveResponse] = Schema.derived[LECCurveResponse]
}

/** Lean LEC data for multi-curve overlay (lec-multi endpoint).
  * 
  * Unlike LECCurveResponse (single-node endpoint), this type carries only
  * the data needed for chart rendering: name, curve points, and quantiles.
  * Navigation metadata (childIds) and tracing metadata (provenances) are
  * excluded — the multi endpoint returns a Map keyed by node ID, making
  * the id field redundant as well.
  * 
  * Quantiles are computed server-side from the full RiskResult.outcomeCount
  * TreeMap (exact to simulation resolution — not interpolated from the
  * 100-tick curve subset).
  * 
  * @param name Human-readable node name (for chart legend)
  * @param curve Loss exceedance curve points (shared tick domain across all nodes)
  * @param quantiles Key percentiles (p50, p90, p95, p99) as loss values
  */
final case class LECNodeCurve(
  name: String,
  curve: Vector[LECPoint],
  quantiles: Map[String, Double]
)

object LECNodeCurve {
  given codec: JsonCodec[LECNodeCurve] = DeriveJsonCodec.gen[LECNodeCurve]
  given schema: Schema[LECNodeCurve] = Schema.derived[LECNodeCurve]
}
