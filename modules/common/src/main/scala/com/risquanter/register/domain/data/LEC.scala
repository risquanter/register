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
  * @param childIds '''DEPRECATED''' — Navigation child IDs. The frontend reads childIds from the
  *   tree structure it already holds in memory (TreeViewState), making this field
  *   redundant. Retained until end of Phase F for backward compatibility.
  *   If no consumer reads this field by the Phase F review checkpoint, delete it.
  *   If a legitimate use case arises during E.5–F, raise it as a re-evaluation
  *   point before depending on it.
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

/** Core LEC curve data — identity + drawing data for a single node.
  *
  * This is the universal curve type used for:
  *   - Multi-curve overlay (lec-multi endpoint: `Map[String, LECNodeCurve]`)
  *   - Chart spec generation (`LECChartSpecBuilder.generateMultiCurveSpec`)
  *   - Frontend chart cache (`LECState`)
  *
  * Carries exactly what's needed to draw and identify a curve: id, name,
  * curve points, and quantiles. Navigation metadata (childIds) and tracing
  * metadata (provenances) live on `LECCurveResponse` — the single-node
  * endpoint envelope.
  *
  * Quantiles are computed server-side from the full RiskResult.outcomeCount
  * TreeMap (exact to simulation resolution — not interpolated from the
  * 100-tick curve subset).
  *
  * @param id Node identifier (preserved for identity after map destructuring)
  * @param name Human-readable node name (for chart legend)
  * @param curve Loss exceedance curve points (shared tick domain across all nodes)
  * @param quantiles Key percentiles (p50, p90, p95, p99) as loss values
  */
final case class LECNodeCurve(
  id: String,
  name: String,
  curve: Vector[LECPoint],
  quantiles: Map[String, Double]
)

object LECNodeCurve {
  given codec: JsonCodec[LECNodeCurve] = DeriveJsonCodec.gen[LECNodeCurve]
  given schema: Schema[LECNodeCurve] = Schema.derived[LECNodeCurve]
}
