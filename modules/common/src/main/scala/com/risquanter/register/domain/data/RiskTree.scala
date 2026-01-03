package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{SafeName, NonNegativeLong}
import zio.json.{JsonCodec, DeriveJsonCodec, JsonEncoder, JsonDecoder}
import io.github.iltotore.iron.*

/** Domain model for a risk tree configuration.
  * 
  * Stores the risk tree structure (RiskNode) but NOT computation results.
  * LEC data is computed on-demand via GET /risk-trees/:id/lec
  * 
  * @param id Unique risk tree identifier (assigned by repository)
  * @param name Human-readable risk tree name
  * @param nTrials Number of Monte Carlo trials for execution (default: 10,000)
  * @param root Root node of the risk tree (can be RiskPortfolio or single RiskLeaf)
  */
final case class RiskTree(
  id: NonNegativeLong,
  name: SafeName.SafeName,
  nTrials: Int,
  root: RiskNode
)

object RiskTree {
  // JSON codecs for Iron refined types
  given nonNegativeLongEncoder: JsonEncoder[NonNegativeLong] = 
    JsonEncoder[Long].contramap(identity)
  
  given nonNegativeLongDecoder: JsonDecoder[NonNegativeLong] = 
    JsonDecoder[Long].mapOrFail(l => l.refineEither[constraint.numeric.GreaterEqual[0L]].left.map(_.toString))
  
  given safeNameEncoder: JsonEncoder[SafeName.SafeName] = 
    JsonEncoder[String].contramap(_.value)
  
  given safeNameDecoder: JsonDecoder[SafeName.SafeName] = 
    JsonDecoder[String].mapOrFail(s => SafeName.fromString(s).left.map(_.mkString(", ")))
  
  // JSON codec for RiskTree (now with Iron codecs in scope)
  given codec: JsonCodec[RiskTree] = DeriveJsonCodec.gen[RiskTree]
}
