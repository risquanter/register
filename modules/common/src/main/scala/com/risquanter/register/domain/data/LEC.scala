package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonFieldDecoder, JsonFieldEncoder}
import sttp.tapir.Schema
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.iron.{NodeId, OccurrenceProbability, Probability, SafeName}
import com.risquanter.register.domain.data.iron.given
import com.risquanter.register.http.codecs.IronTapirCodecs

/** One point on a loss exceedance curve: a loss, and the share of trials whose
  * loss was at or above it. */
final case class LECPoint(
  loss: Long,
  exceedanceProbability: OccurrenceProbability
)

object LECPoint {
  given codec: JsonCodec[LECPoint] = DeriveJsonCodec.gen[LECPoint]
  given schema: Schema[LECPoint] =
    import IronTapirCodecs.given
    Schema.derived[LECPoint]
}

/** One node's curve: its identity, the sampled points, the tail quantiles and
  * two summary figures. The quantiles are read from every trial, not from the
  * sampled points.
  */
final case class LECNodeCurve(
  id: NodeId,
  name: SafeName.SafeName,
  curve: Vector[LECPoint],
  quantiles: Map[LECNodeCurve.QuantileLevel, Double],
  averageAnnualLoss: Double,
  probabilityOfNoLoss: OccurrenceProbability
)

object LECNodeCurve {
  /** The tail levels a curve reports a loss at.
    *
    * Tail-only by design: for a risk occurring less than half the time the
    * unconditional median is 0 and says nothing, and `probabilityOfNoLoss`
    * carries that information instead. The level is the share of trials whose
    * loss falls at or below the reported figure.
    */
  enum QuantileLevel(val probability: Probability):
    case P90  extends QuantileLevel(0.90)
    case P95  extends QuantileLevel(0.95)
    case P99  extends QuantileLevel(0.99)
    case P995 extends QuantileLevel(0.995)

  object QuantileLevel {
    /** A level is a map key on the wire, spelled as its case name. The decoder
      * refuses any other key, so a decoded map is as trustworthy as a
      * constructed one (ADR-038). */
    given fieldEncoder: JsonFieldEncoder[QuantileLevel] =
      JsonFieldEncoder[String].contramap(_.toString)
    given fieldDecoder: JsonFieldDecoder[QuantileLevel] =
      JsonFieldDecoder[String].mapOrFail(key =>
        QuantileLevel.values.find(_.toString == key).toRight(s"Unknown quantile level: $key"))

    /** Declared because Tapir derives a map schema only for string keys. */
    given mapSchema: Schema[Map[QuantileLevel, Double]] =
      Schema.schemaForMap[QuantileLevel, Double](_.toString)
  }

  given codec: JsonCodec[LECNodeCurve] = DeriveJsonCodec.gen[LECNodeCurve]
  given schema: Schema[LECNodeCurve] =
    import IronTapirCodecs.given
    Schema.derived[LECNodeCurve]
}
