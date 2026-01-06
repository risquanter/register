package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, DeriveJsonCodec}

/** Detailed error information for API responses
  * 
  * @param domain Business domain (e.g., "simulations", "risk-trees")
  * @param field JSON path to the problematic field (e.g., "name", "root.children[0].minLoss")
  * @param code Machine-readable error code for categorization
  * @param reason Human-readable error category
  * @param message Detailed error message for debugging
  * @param requestId Optional correlation ID linking error to specific request
  */
final case class ErrorDetail(
  domain: String,
  field: String,
  code: ValidationErrorCode,
  reason: String,
  message: String,
  requestId: Option[String] = None
)

object ErrorDetail {
  given codec: JsonCodec[ErrorDetail] = DeriveJsonCodec.gen[ErrorDetail]
  
  /** Create error detail from legacy format (backward compatibility) */
  def fromLegacy(domain: String, reason: String, message: String): ErrorDetail =
    ErrorDetail(
      domain = domain,
      field = extractFieldFromMessage(message),
      code = ValidationErrorCode.categorize(message),
      reason = reason,
      message = message,
      requestId = None
    )
  
  /** Extract field name from error message if present */
  def extractFieldFromMessage(message: String): String =
    // Try to extract field name from patterns like "[fieldName]" or "fieldName:" or "fieldName failed"
    val bracketPattern = "\\[([^\\]]+)\\]".r
    val colonPattern = "^([a-zA-Z_][a-zA-Z0-9_.]*)\\s*:".r
    val failedPattern = "([a-zA-Z_][a-zA-Z0-9_.]*)\\s+failed".r
    
    bracketPattern.findFirstMatchIn(message).map(_.group(1))
      .orElse(colonPattern.findFirstMatchIn(message).map(_.group(1)))
      .orElse(failedPattern.findFirstMatchIn(message).map(_.group(1)))
      .getOrElse("unknown")
}
