package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, DeriveJsonCodec}

/** Detailed error information for API responses
  * 
  * @param domain Business domain (e.g., "simulations", "risk-trees", "users")
  * @param field JSON path to the problematic field (e.g., "name", "root.children[0].minLoss")
  * @param code Machine-readable error code for categorization and client-side handling
  * @param message Detailed error message for debugging
  * @param requestId Optional correlation ID linking error to specific request
  */
final case class ErrorDetail(
  domain: String,
  field: String,
  code: ValidationErrorCode,
  message: String,
  requestId: Option[String] = None
)

object ErrorDetail {
  given codec: JsonCodec[ErrorDetail] = DeriveJsonCodec.gen[ErrorDetail]
}
