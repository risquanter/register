package com.risquanter.register.http.responses

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

import com.risquanter.register.domain.data.MitigationSelection
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One valuation's answer: its selection, and the curve of each requested node
  * present at the revision read.
  */
final case class CurveValuationResponse(selection: MitigationSelection, curves: Map[NodeId, LECNodeSeries])

object CurveValuationResponse:
  given codec: JsonCodec[CurveValuationResponse] = DeriveJsonCodec.gen[CurveValuationResponse]
  given schema: Schema[CurveValuationResponse]   = Schema.derived[CurveValuationResponse]

/** The valuations lec-multi answers, in request order. Every curve of every
  * valuation is sampled at the same loss ticks, so the set draws on one axis.
  */
final case class LECCurvesResponse(valuations: List[CurveValuationResponse])

object LECCurvesResponse:
  given codec: JsonCodec[LECCurvesResponse] = DeriveJsonCodec.gen[LECCurvesResponse]
  given schema: Schema[LECCurvesResponse] = Schema.derived[LECCurvesResponse]
