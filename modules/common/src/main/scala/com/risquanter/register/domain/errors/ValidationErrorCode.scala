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
  case EmptyCollection extends ValidationErrorCode("EMPTY_COLLECTION", "Collection must not be empty")
  case DistributionFitFailed extends ValidationErrorCode("DISTRIBUTION_FIT_FAILED", "Distribution fitting failed")
  case InvalidExpertOpinionParams extends ValidationErrorCode("INVALID_EXPERT_PARAMS", "Expert opinion parameters invalid")
  case InvalidLognormalParams extends ValidationErrorCode("INVALID_LOGNORMAL_PARAMS", "Lognormal parameters invalid")
  case UnsupportedDistributionType extends ValidationErrorCode("UNSUPPORTED_DISTRIBUTION_TYPE", "Distribution type not supported")

object ValidationErrorCode:
  given encoder: JsonEncoder[ValidationErrorCode] = 
    JsonEncoder[String].contramap(_.code)
  
  given decoder: JsonDecoder[ValidationErrorCode] = 
    JsonDecoder[String].mapOrFail { code =>
      ValidationErrorCode.values.find(_.code == code)
        .toRight(s"Unknown validation error code: $code")
    }
