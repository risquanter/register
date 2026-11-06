package com.risquanter.register.http.responses

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

/** What a probe answers: the one word a liveness or readiness check reads. */
enum ProbeStatus:
  case Healthy, Ready

object ProbeStatus:
  given codec: JsonCodec[ProbeStatus] = DeriveJsonCodec.gen[ProbeStatus]
  given schema: Schema[ProbeStatus] = Schema.derivedEnumeration[ProbeStatus].defaultStringBased

/** Body of `/health` on the API port and of `/health` and `/ready` on the
  * probe port. `service` names this deployment for an operator reading the
  * body. It is free text with no narrower contract, and its one producer
  * holds it as a named constant.
  */
final case class ProbeResponse(status: ProbeStatus, service: String)

object ProbeResponse:
  given codec: JsonCodec[ProbeResponse] = DeriveJsonCodec.gen[ProbeResponse]
  given schema: Schema[ProbeResponse] = Schema.derived[ProbeResponse]
