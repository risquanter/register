package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, JsonEncoder, JsonDecoder}

/** Typed error codes for validation failures.
  * 
  * These codes are machine-readable and can be used by clients for:
  * - Error categorization and filtering
  * - Localization (map code → translated message)
  * - Analytics and monitoring
  * - Specific error handling logic
  */
enum ValidationErrorCode(val code: String, val description: String):
  case REQUIRED_FIELD extends ValidationErrorCode("REQUIRED_FIELD", "Required field is missing or empty")
  case INVALID_FORMAT extends ValidationErrorCode("INVALID_FORMAT", "Field format is invalid")
  case INVALID_RANGE extends ValidationErrorCode("INVALID_RANGE", "Value is outside valid range")
  case INVALID_LENGTH extends ValidationErrorCode("INVALID_LENGTH", "String length constraint violated")
  case INVALID_PATTERN extends ValidationErrorCode("INVALID_PATTERN", "Value doesn't match required pattern")
  case CONSTRAINT_VIOLATION extends ValidationErrorCode("CONSTRAINT_VIOLATION", "Business rule or constraint violated")
  case TYPE_MISMATCH extends ValidationErrorCode("TYPE_MISMATCH", "Expected different type")
  case DUPLICATE_VALUE extends ValidationErrorCode("DUPLICATE_VALUE", "Value must be unique")
  case DEPENDENCY_FAILED extends ValidationErrorCode("DEPENDENCY_FAILED", "Related field validation failed")
  case INVALID_COMBINATION extends ValidationErrorCode("INVALID_COMBINATION", "Field combination is invalid")

object ValidationErrorCode:
  given encoder: JsonEncoder[ValidationErrorCode] = 
    JsonEncoder[String].contramap(_.code)
  
  given decoder: JsonDecoder[ValidationErrorCode] = 
    JsonDecoder[String].mapOrFail { code =>
      ValidationErrorCode.values.find(_.code == code)
        .toRight(s"Unknown validation error code: $code")
    }
  
  /** Categorize free-form error message into typed code */
  def categorize(message: String): ValidationErrorCode =
    val lower = message.toLowerCase
    if lower.contains("required") || lower.contains("missing") then REQUIRED_FIELD
    else if lower.contains("range") || lower.contains("must be less than") || lower.contains("must be greater") then INVALID_RANGE
    else if lower.contains("length") || lower.contains("too long") || lower.contains("too short") then INVALID_LENGTH
    else if lower.contains("blank") || lower.contains("empty") then REQUIRED_FIELD
    else if lower.contains("format") || lower.contains("invalid") then INVALID_FORMAT
    else if lower.contains("pattern") || lower.contains("alphanumeric") then INVALID_PATTERN
    else if lower.contains("combination") || lower.contains("mode requires") then INVALID_COMBINATION
    else CONSTRAINT_VIOLATION
