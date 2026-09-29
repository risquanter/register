package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs.given_Schema_NodeId

/** A single point on a Loss Exceedance Curve: loss amount and P(Loss >= loss). */
final case class LECPoint(
  loss: Long,
  exceedanceProbability: Double
)

object LECPoint {
  given codec: JsonCodec[LECPoint] = DeriveJsonCodec.gen[LECPoint]
  given schema: Schema[LECPoint] = Schema.derived[LECPoint]
}

/** Curve data for a single node: identity, curve points, tail quantiles, and summary statistics.
  * Quantiles are computed from the full `outcomeCount` map, not interpolated from the 100-tick subset.
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
