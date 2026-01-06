package com.risquanter.register.domain.errors

sealed trait SimulationError extends Throwable

/** Validation error with structured information */
case class ValidationError(
  field: String,
  code: ValidationErrorCode,
  message: String
)

/** Validation failure with accumulated structured errors */
case class ValidationFailed(errors: List[ValidationError]) extends SimulationError {
  override def getMessage: String = errors.map(e => s"[${e.field}] ${e.message}").mkString("; ")
}

/** Repository operation failure */
case class RepositoryFailure(reason: String) extends SimulationError
