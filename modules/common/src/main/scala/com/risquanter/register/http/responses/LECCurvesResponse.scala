package com.risquanter.register.http.responses

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs

/** The curves lec-multi answers, one per requested node present in the tree
  * at the revision read. Every curve is sampled at the same loss ticks, so the
  * set draws on one axis.
  */
final case class LECCurvesResponse(curves: Map[NodeId, LECNodeCurve])

object LECCurvesResponse:
  given codec: JsonCodec[LECCurvesResponse] = DeriveJsonCodec.gen[LECCurvesResponse]
  given schema: Schema[LECCurvesResponse] =
    import IronTapirCodecs.given
    Schema.derived[LECCurvesResponse]
