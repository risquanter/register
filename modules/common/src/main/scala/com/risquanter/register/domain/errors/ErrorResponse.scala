package com.risquanter.register.domain.errors

import scala.concurrent.duration.Duration

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.model.StatusCode

/** Wrapper for error responses sent to clients */
final case class ErrorResponse(error: JsonHttpError)

object ErrorResponse {
  given codec: JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]

  /** Reconstruct a domain error from an HTTP error response.
    *
    * This is the inverse of `encode`: it maps `(StatusCode, ErrorResponse)` back
    * to the shared `AppError` hierarchy so the client can pattern-match on typed
    * domain errors rather than parsing status codes or strings.
    *
    * The reconstruction is not perfectly lossless (a 500 could be `RepositoryFailure`
    * or `SimulationFailure` — we disambiguate via `ErrorDetail.field`). But it
    * preserves type safety at the sealed-trait level, which is the right granularity
    * for client-side error handling.
    *
    * @see `encode` for the forward direction (domain error → HTTP)
    */
  def decode(tuple: (StatusCode, ErrorResponse)): Throwable =
    val (status, response) = tuple
    val details = response.error.errors
    val message = details.map(_.message).mkString("; ")
    val firstField = details.headOption.map(_.field).getOrElse("unknown")
    val firstCode = details.headOption.map(_.code).getOrElse(ValidationErrorCode.CONSTRAINT_VIOLATION)

    status.code match
      // 400 → ValidationFailed (only source for this status code)
      case 400 =>
        ValidationFailed(details.map(d => ValidationError(d.field, d.code, d.message)))

      // 409 → disambiguate by field
      case 409 => firstField match
        case "version" => VersionConflict("unknown", "unknown", message)  // nodeId lost through HTTP
        case "branch"  => MergeConflict("unknown", message)               // branch name lost through HTTP
        case _         => DataConflict(message)

      // 502 → IrminGraphQLError
      case 502 =>
        IrminGraphQLError(details.map(_.message), Some(List(firstField)))

      // 503 → IrminUnavailable
      case 503 =>
        IrminUnavailable(message)

      // 504 → NetworkTimeout
      case 504 =>
        NetworkTimeout(message, scala.concurrent.duration.Duration.Zero)

      // 500 → disambiguate by field
      case 500 => firstField match
        case "simulation" => SimulationFailure("unknown", new RuntimeException(message))
        case _            => RepositoryFailure(message)

      // Any other status code → generic
      case _ =>
        RepositoryFailure(s"HTTP ${status.code}: $message")

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

  // ============================================================================
  // Response Builders
  // ============================================================================

  /** Shared builder for single-detail error responses.
    * All `make*Response` methods delegate here, varying only the
    * status code, field, error code, message, and domain.
    */
  private def response(
    status: StatusCode,
    field: String,
    code: ValidationErrorCode,
    message: String,
    domain: String = "risk-trees",
    requestId: Option[String] = None
  ): (StatusCode, ErrorResponse) =
    val errors = List(ErrorDetail(domain, field, code, message, requestId))
    (status, ErrorResponse(JsonHttpError(status.code, message, errors)))

  def makeGeneralResponse(domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.InternalServerError, "unknown", ValidationErrorCode.CONSTRAINT_VIOLATION,
      "General server error, please check the logs...", domain, requestId)

  /** Validation responses carry multiple `ErrorDetail` entries (one per `ValidationError`),
    * so they cannot use the single-detail `response()` builder.
    */
  def makeValidationResponse(errors: List[ValidationError], domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    val message = "Domain validation error"
    val details = errors.map(ve => ErrorDetail(domain, ve.field, ve.code, ve.message, requestId))
    (StatusCode.BadRequest, ErrorResponse(JsonHttpError(StatusCode.BadRequest.code, message, details)))

  def makeDataConflictResponse(message: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Conflict, "unknown", ValidationErrorCode.DUPLICATE_VALUE, message, domain, requestId)

  def makeRepositoryFailureResponse(reason: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.InternalServerError, "unknown", ValidationErrorCode.CONSTRAINT_VIOLATION, reason, domain, requestId)

  def makeSimulationFailureResponse(simulationId: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.InternalServerError, "simulation", ValidationErrorCode.CONSTRAINT_VIOLATION,
      s"Simulation $simulationId failed", domain, requestId)

  // ── Infrastructure Error Responses (ADR-008) ───────────────────────────────

  def makeServiceUnavailableResponse(reason: String, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.ServiceUnavailable, "service", ValidationErrorCode.DEPENDENCY_FAILED,
      s"Service unavailable: $reason", domain, requestId)

  def makeNetworkTimeoutResponse(operation: String, duration: Duration, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.GatewayTimeout, "network", ValidationErrorCode.DEPENDENCY_FAILED,
      s"Network timeout after ${duration.toMillis}ms during: $operation", domain, requestId)

  def makeIrminHttpErrorResponse(status: StatusCode, body: String, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(status, "http", ValidationErrorCode.DEPENDENCY_FAILED,
      s"HTTP ${status.code}: $body", domain, requestId)

  def makeIrminGraphQlErrorResponse(messages: List[String], path: Option[List[String]], domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    val field = path.filter(_.nonEmpty).map(_.mkString(".")).getOrElse("graphql")
    response(StatusCode.BadGateway, field, ValidationErrorCode.DEPENDENCY_FAILED,
      messages.mkString("; "), domain, requestId)

  private def makeIrminErrorResponse(error: IrminError): (StatusCode, ErrorResponse) = error match
    case IrminUnavailable(reason)            => makeServiceUnavailableResponse(reason)
    case NetworkTimeout(operation, duration) => makeNetworkTimeoutResponse(operation, duration)
    case IrminHttpError(status, body)        => makeIrminHttpErrorResponse(status, body)
    case IrminGraphQLError(messages, path)   => makeIrminGraphQlErrorResponse(messages, path)

  def makeVersionConflictResponse(nodeId: String, expected: String, actual: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Conflict, "version", ValidationErrorCode.CONSTRAINT_VIOLATION,
      s"Version conflict on node $nodeId: expected $expected, found $actual", domain, requestId)

  def makeMergeConflictResponse(branch: String, details: String, domain: String = "scenarios", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Conflict, "branch", ValidationErrorCode.CONSTRAINT_VIOLATION,
      s"Merge conflict on branch $branch: $details", domain, requestId)
}
