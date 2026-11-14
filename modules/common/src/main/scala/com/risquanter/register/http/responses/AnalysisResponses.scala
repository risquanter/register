package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.{MitigationId, Probability}
import com.risquanter.register.domain.data.iron.given
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One drawn curve together with the mitigations that shaped it.
  *
  * `withMitigations` names every mitigation whose resolved scope covers this
  * node or any node below it — decided by scope, so a selected mitigation that
  * covers nothing here is absent and a covering mitigation is named whether or
  * not it visibly bent this curve. The mitigation-free curve is the entry whose
  * list is empty, which is why there is no separately named field for it.
  */
final case class LECNodeSeries(curve: LECNodeCurve, withMitigations: List[MitigationId])

object LECNodeSeries:
  given codec: JsonCodec[LECNodeSeries] = DeriveJsonCodec.gen[LECNodeSeries]
  given schema: Schema[LECNodeSeries]   = Schema.derived[LECNodeSeries]

/** One exceedance probability together with the mitigations that shaped it.
  *
  * `withMitigations` is read exactly as `LECNodeSeries.withMitigations`: the
  * mitigations whose resolved scope covers this node or any node below it,
  * neither the set the caller selected nor the set that moved the number. It is
  * empty exactly when no selected mitigation reached this node, which is the
  * only way a caller can tell an unmitigated figure from a mitigated one that
  * happens to match it — a cap set above the queried threshold leaves the
  * probability unchanged while still being named here.
  */
final case class ExceedanceSeries(probability: Probability, withMitigations: List[MitigationId])

object ExceedanceSeries:
  given codec: JsonCodec[ExceedanceSeries] = DeriveJsonCodec.gen[ExceedanceSeries]
  given schema: Schema[ExceedanceSeries]   = Schema.derived[ExceedanceSeries]
