package com.risquanter.register.http.responses

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** The curves lec-multi answers, per requested node present in the tree at the
  * revision read: the mitigation-free reading first, and a second reading when
  * the selection reaches the node. Every curve is sampled at the same loss
  * ticks, so the set draws on one axis.
  */
final case class LECCurvesResponse(curves: Map[NodeId, List[LECNodeSeries]])

object LECCurvesResponse:
  given codec: JsonCodec[LECCurvesResponse] = DeriveJsonCodec.gen[LECCurvesResponse]
  given schema: Schema[LECCurvesResponse] = Schema.derived[LECCurvesResponse]
