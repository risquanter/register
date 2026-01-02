package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, DeriveJsonCodec}

/** Detailed error information for API responses */
final case class ErrorDetail(
  domain: String,
  reason: String,
  message: String
)

object ErrorDetail {
  given codec: JsonCodec[ErrorDetail] = DeriveJsonCodec.gen[ErrorDetail]
}
