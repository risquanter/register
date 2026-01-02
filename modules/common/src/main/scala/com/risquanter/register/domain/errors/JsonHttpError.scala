package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, DeriveJsonCodec}

/** HTTP error response structure */
final case class JsonHttpError(
  code: Int,
  message: String,
  errors: List[ErrorDetail]
)

object JsonHttpError {
  given codec: JsonCodec[JsonHttpError] = DeriveJsonCodec.gen[JsonHttpError]
}
