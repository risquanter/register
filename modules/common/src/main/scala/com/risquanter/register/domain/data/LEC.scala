package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs.given_Schema_NodeId

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

/** Core LEC curve data — identity + drawing data for a single node.
  *
  * This is the universal curve type used for:
  *   - Multi-curve overlay (lec-multi endpoint: `Map[NodeId, LECNodeCurve]`)
  *   - Client-side chart spec generation (`LECSpecBuilder.build`)
  *   - Frontend chart cache (`LECChartState.curveCache`)
  *
  * Carries exactly what's needed to draw and identify a curve: id, name,
  * curve points, and quantiles. Tracing metadata (provenances) stays on the
  * server-side result types (`RiskResult.provenances`); no endpoint response
  * currently includes it.
  *
  * Quantiles are computed server-side from the full RiskResult.outcomeCount
  * TreeMap (exact to simulation resolution — not interpolated from the
  * 100-tick curve subset).
  *
  * @param id Node identifier (preserved for identity after map destructuring)
  * @param name Human-readable node name (for chart legend)
  * @param curve Loss exceedance curve points (shared tick domain across all nodes)
  * @param quantiles Tail percentiles (p90, p95, p99, p99.5) as loss values —
  *   see `LECGenerator.calculateQuantiles` for why p05/p50 are deliberately
  *   excluded
  * @param averageAnnualLoss Mean loss across all trials, including implicit
  *   zero-loss ones — see `LECGenerator.averageAnnualLoss`
  * @param probabilityOfNoLoss Fraction of trials with zero loss — see
  *   `LECGenerator.probabilityOfNoLoss`
  */
final case class LECNodeCurve(
  id: NodeId,
  name: String,
  curve: Vector[LECPoint],
  quantiles: Map[String, Double],
  averageAnnualLoss: Double,
  probabilityOfNoLoss: Double
)

object LECNodeCurve {
  given codec: JsonCodec[LECNodeCurve] = DeriveJsonCodec.gen[LECNodeCurve]
  given schema: Schema[LECNodeCurve] = Schema.derived[LECNodeCurve]
}
