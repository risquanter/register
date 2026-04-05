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
  case EMPTY_COLLECTION extends ValidationErrorCode("EMPTY_COLLECTION", "Collection must not be empty")
  case DISTRIBUTION_FIT_FAILED extends ValidationErrorCode("DISTRIBUTION_FIT_FAILED", "Distribution fitting failed")
  case INVALID_EXPERT_PARAMS extends ValidationErrorCode("INVALID_EXPERT_PARAMS", "Expert opinion parameters invalid")
  case INVALID_LOGNORMAL_PARAMS extends ValidationErrorCode("INVALID_LOGNORMAL_PARAMS", "Lognormal parameters invalid")
  case UNSUPPORTED_DISTRIBUTION_TYPE extends ValidationErrorCode("UNSUPPORTED_DISTRIBUTION_TYPE", "Distribution type not supported")
  case ID_NOT_ALLOWED_ON_CREATE extends ValidationErrorCode("ID_NOT_ALLOWED_ON_CREATE", "IDs must not be supplied when creating a resource")
  case MISSING_REFERENCE extends ValidationErrorCode("MISSING_REFERENCE", "Referenced entity not found in request scope")
  case AMBIGUOUS_REFERENCE extends ValidationErrorCode("AMBIGUOUS_REFERENCE", "Reference matched multiple entities; must be unique")
  case INVALID_NODE_TYPE extends ValidationErrorCode("INVALID_NODE_TYPE", "Referenced node is of the wrong type for this operation")

  // ── Resource & access codes ───────────────────────────────────────────────
  case NOT_FOUND extends ValidationErrorCode("NOT_FOUND", "Requested resource does not exist")
  case ACCESS_DENIED extends ValidationErrorCode("ACCESS_DENIED", "Insufficient permissions for this operation")
  case RATE_LIMIT_EXCEEDED extends ValidationErrorCode("RATE_LIMIT_EXCEEDED", "Too many requests in the current window")

  // ── Concurrency codes ─────────────────────────────────────────────────────
  case VERSION_CONFLICT extends ValidationErrorCode("VERSION_CONFLICT", "Optimistic concurrency conflict on resource version")
  case MERGE_CONFLICT extends ValidationErrorCode("MERGE_CONFLICT", "Branch merge conflict")

  // ── Infrastructure codes ──────────────────────────────────────────────────
  case INTERNAL_ERROR extends ValidationErrorCode("INTERNAL_ERROR", "Unexpected server-side error")

  // ── FOL query codes (ADR-028) ─────────────────────────────────────────────
  case PARSE_ERROR extends ValidationErrorCode("PARSE_ERROR", "Query syntax is malformed")
  case UNKNOWN_SYMBOL extends ValidationErrorCode("UNKNOWN_SYMBOL", "Query references an unknown predicate or function")
  case BIND_FAILED extends ValidationErrorCode("BIND_FAILED", "Query type-checking failed at bind phase")
  case DOMAIN_NOT_QUANTIFIABLE extends ValidationErrorCode("DOMAIN_NOT_QUANTIFIABLE", "Query quantifies over a non-domain type")
  case MODEL_VALIDATION_FAILED extends ValidationErrorCode("MODEL_VALIDATION_FAILED", "Runtime model does not satisfy catalog contract")
  case EVALUATION_FAILED extends ValidationErrorCode("EVALUATION_FAILED", "Query evaluation failed unexpectedly")
  case SIMULATION_REQUIRED extends ValidationErrorCode("SIMULATION_REQUIRED", "Simulation must be run before querying")

object ValidationErrorCode:
  given encoder: JsonEncoder[ValidationErrorCode] = 
    JsonEncoder[String].contramap(_.code)
  
  given decoder: JsonDecoder[ValidationErrorCode] = 
    JsonDecoder[String].mapOrFail { code =>
      ValidationErrorCode.values.find(_.code == code)
        .toRight(s"Unknown validation error code: $code")
    }
