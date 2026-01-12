package com.risquanter.register.domain.errors

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.model.StatusCode

/** Wrapper for error responses sent to clients */
final case class ErrorResponse(error: JsonHttpError)

object ErrorResponse {
  given codec: JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]

  /** Decode error response tuple back to Throwable */
  def decode(tuple: (StatusCode, ErrorResponse)): Throwable =
    new RuntimeException(tuple._2.error.errors.mkString("; "))

  /** Encode Throwable to error response tuple for HTTP.
    * 
    * Note: This is a pure function called by Tapir outside the ZIO runtime.
    * All error logging should happen at the service layer using tapErrorCause
    * before errors reach this boundary. See ADR-002 Decision 5.
    * 
    * Uses typed pattern matching on SimulationError hierarchy - no string matching.
    */
  def encode(error: Throwable): (StatusCode, ErrorResponse) = error match {
    // Domain errors - typed hierarchy
    case ValidationFailed(errors)     => makeValidationResponse(errors)
    case RepositoryFailure(reason)    => makeRepositoryFailureResponse(reason)
    case SimulationFailure(id, cause) => makeSimulationFailureResponse(id)
    case DataConflict(reason)         => makeDataConflictResponse(reason)
    // Unexpected errors - already logged at service layer (ADR-002 Decision 5)
    case _ => makeGeneralResponse()
  }

  def makeGeneralResponse(domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = "General server error, please check the logs..."
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail(
      domain = domain,
      field = "unknown",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeValidationResponse(errors: List[ValidationError], domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = "Domain validation error"
    val statusCode = StatusCode.BadRequest
    val errorDetails = errors.map { ve =>
      ErrorDetail(
        domain = domain,
        field = ve.field,
        code = ve.code,
        message = ve.message,
        requestId = requestId
      )
    }
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errorDetails)))
  }

  def makeDataConflictResponse(message: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val statusCode = StatusCode.Conflict
    val errors = List(ErrorDetail(
      domain = domain,
      field = "unknown",
      code = ValidationErrorCode.DUPLICATE_VALUE,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeRepositoryFailureResponse(reason: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = "Repository operation failed"
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail(
      domain = domain,
      field = "unknown",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = reason,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeSimulationFailureResponse(simulationId: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = s"Simulation $simulationId failed"
    val statusCode = StatusCode.InternalServerError
    val errors = List(ErrorDetail(
      domain = domain,
      field = "simulation",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }
}
