package com.risquanter.register.domain.errors

import scala.concurrent.duration.Duration

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
    * Uses typed pattern matching on AppError hierarchy - no string matching.
    */
  def encode(error: Throwable): (StatusCode, ErrorResponse) = error match {
    // Domain errors - typed hierarchy
    case ValidationFailed(errors)     => makeValidationResponse(errors)
    case RepositoryFailure(reason)    => makeRepositoryFailureResponse(reason)
    case SimulationFailure(id, cause) => makeSimulationFailureResponse(id)
    case DataConflict(reason)         => makeDataConflictResponse(reason)
    // Infrastructure errors (ADR-008)
    case e: IrminError                         => makeIrminErrorResponse(e)
    case VersionConflict(nodeId, expected, actual) => makeVersionConflictResponse(nodeId, expected, actual)
    case MergeConflict(branch, details)        => makeMergeConflictResponse(branch, details)
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

  // ============================================================================
  // Infrastructure Error Responses (ADR-008)
  // ============================================================================

  def makeServiceUnavailableResponse(reason: String, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = s"Service unavailable: $reason"
    val statusCode = StatusCode.ServiceUnavailable  // 503
    val errors = List(ErrorDetail(
      domain = domain,
      field = "service",
      code = ValidationErrorCode.DEPENDENCY_FAILED,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeNetworkTimeoutResponse(operation: String, duration: Duration, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = s"Network timeout after ${duration.toMillis}ms during: $operation"
    val statusCode = StatusCode.GatewayTimeout  // 504
    val errors = List(ErrorDetail(
      domain = domain,
      field = "network",
      code = ValidationErrorCode.DEPENDENCY_FAILED,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeIrminHttpErrorResponse(status: StatusCode, body: String, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = s"HTTP ${status.code}: $body"
    val errors = List(ErrorDetail(
      domain = domain,
      field = "http",
      code = ValidationErrorCode.DEPENDENCY_FAILED,
      message = message,
      requestId = requestId
    ))
    (status, ErrorResponse(JsonHttpError(status.code, message, errors)))
  }

  def makeIrminGraphQlErrorResponse(messages: List[String], path: Option[List[String]], domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = messages.mkString("; ")
    val field = path.filter(_.nonEmpty).map(_.mkString(".")).getOrElse("graphql")
    val statusCode = StatusCode.BadGateway // 502
    val errors = List(ErrorDetail(
      domain = domain,
      field = field,
      code = ValidationErrorCode.DEPENDENCY_FAILED,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  private def makeIrminErrorResponse(error: IrminError): (StatusCode, ErrorResponse) = error match
    case IrminUnavailable(reason)            => makeServiceUnavailableResponse(reason)
    case NetworkTimeout(operation, duration) => makeNetworkTimeoutResponse(operation, duration)
    case IrminHttpError(status, body)        => makeIrminHttpErrorResponse(status, body)
    case IrminGraphQLError(messages, path)   => makeIrminGraphQlErrorResponse(messages, path)

  def makeVersionConflictResponse(nodeId: String, expected: String, actual: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = s"Version conflict on node $nodeId: expected $expected, found $actual"
    val statusCode = StatusCode.Conflict  // 409
    val errors = List(ErrorDetail(
      domain = domain,
      field = "version",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }

  def makeMergeConflictResponse(branch: String, details: String, domain: String = "scenarios", requestId: Option[String] = None): (StatusCode, ErrorResponse) = {
    val message = s"Merge conflict on branch $branch: $details"
    val statusCode = StatusCode.Conflict  // 409
    val errors = List(ErrorDetail(
      domain = domain,
      field = "branch",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = message,
      requestId = requestId
    ))
    (statusCode, ErrorResponse(JsonHttpError(statusCode.code, message, errors)))
  }
}
