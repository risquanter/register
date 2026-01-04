package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec}

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
}

/** Hierarchical node in a Loss Exceedance Curve tree
  * 
  * Matches RiskNode structure but contains computed LEC data.
  * Supports client-side expand/collapse navigation via depth parameter.
  * 
  * @param id Node identifier (matches RiskNode.id)
  * @param name Human-readable name
  * @param curve Array of (loss, exceedanceProbability) points
  * @param quantiles Key percentiles (p50, p90, p95, p99) for quick reference
  * @param children Child nodes (only populated if depth > 0)
  */
final case class LECNode(
  id: String,
  name: String,
  curve: Vector[LECPoint],
  quantiles: Map[String, Double],
  children: Option[Vector[LECNode]] = None
)

object LECNode {
  given codec: JsonCodec[LECNode] = DeriveJsonCodec.gen[LECNode]
}

/** Complete LEC response with visualization spec
  * 
  * Server-side computed, client-side rendered.
  * Contains both curve data and Vega-Lite JSON for immediate visualization.
  * 
  * Navigation:
  * - depth=0: Just root node's aggregate curve
  * - depth=1: Root + immediate children
  * - depth=N: N levels deep (max 5)
  * 
  * @param node Root node with optional children based on depth
  * @param vegaLiteSpec Vega-Lite JSON embedding all visible curves
  * @param depth Number of levels included in response
  */
final case class LECResponse(
  node: LECNode,
  vegaLiteSpec: String,
  depth: Int
)

object LECResponse {
  given codec: JsonCodec[LECResponse] = DeriveJsonCodec.gen[LECResponse]
}
