package com.risquanter.register.domain.errors

import scala.concurrent.duration.Duration

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.model.StatusCode

import com.risquanter.register.domain.errors.FolQueryFailure.*

/** Wrapper for error responses sent to clients */
final case class ErrorResponse(error: JsonHttpError)

object ErrorResponse {
  given codec: JsonCodec[ErrorResponse] = DeriveJsonCodec.gen[ErrorResponse]

  /** Domain tag carried by A13 opaque workspace 404s.
    * Used in both `encode` (via `makeWorkspaceOpaqueNotFoundResponse`) and
    * `decode` to disambiguate workspace 404s from generic not-found errors.
    */
  val WorkspaceDomain: String = "workspaces"

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
    val firstCode = details.headOption.map(_.code).getOrElse(ValidationErrorCode.INTERNAL_ERROR)

    status.code match
      // 400 → disambiguate by code: FOL parse/symbol/bind errors vs general validation
      case 400 => firstCode match
        case ValidationErrorCode.PARSE_ERROR =>
          FolParseFailure(message, None)
        case ValidationErrorCode.UNKNOWN_SYMBOL =>
          FolUnknownSymbol(firstField, Nil)
        case ValidationErrorCode.BIND_FAILED =>
          FolBindFailure(details.map(_.message))
        case ValidationErrorCode.DOMAIN_NOT_QUANTIFIABLE =>
          FolDomainNotQuantifiable(firstField, Set.empty)
        case _ =>
          ValidationFailed(details.map(d => ValidationError(d.field, d.code, d.message)))

      // 403 → AccessDenied
      case 403 =>
        AccessDenied(message)

      // 429 → RateLimitExceeded
      case 429 =>
        RateLimitExceeded("unknown", 0)

      // 409 → disambiguate by field/code
      case 409 => firstCode match
        case ValidationErrorCode.SIMULATION_REQUIRED =>
          // SimulationNotCached requires TreeId (valid ULID) which is lost through HTTP;
          // reconstruct as DataConflict — the frontend already knows the treeId from context.
          DataConflict(message)
        case _ => firstField match
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

      // 404 → disambiguate by domain
      // A13: workspace opaque 404s carry domain="workspaces".
      // Reconstruct as WorkspaceNotFound so the frontend can route
      // to the informational blue banner, not the red error banner.
      // WorkspaceNotFound requires a WorkspaceKeySecret which is lost
      // through the opaque wire format, so we use a sentinel
      // RepositoryFailure with a well-known prefix that the frontend
      // classifier recognizes.
      case 404 =>
        val firstDomain = details.headOption.map(_.domain).getOrElse("")
        if firstDomain == WorkspaceDomain then
          RepositoryFailure(s"${RepositoryFailure.WorkspaceSentinelPrefix}not-found")
        else
          RepositoryFailure(s"Not found: $message")

      // 500 → disambiguate by field/code
      case 500 => firstCode match
        case ValidationErrorCode.EVALUATION_FAILED =>
          FolEvaluationFailure(message, "unknown")
        case ValidationErrorCode.MODEL_VALIDATION_FAILED =>
          FolModelValidationFailure(details.map(_.message))
        case _ => firstField match
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
    * Dispatches to exhaustive sub-matchers for SimError and IrminError so the
    * compiler enforces coverage when new subtypes are added (ADR-022 Decision 3).
    */
  def encode(error: Throwable): (StatusCode, ErrorResponse) = error match {
    case e: SimError          => encodeSimError(e)
    case e: IrminError        => encodeIrminError(e)
    case e: FolQueryFailure   => encodeFolQueryFailure(e)
    // Genuine unknown — already logged at service layer (ADR-002 Decision 5)
    case _ => makeGeneralResponse()
  }

  /** Exhaustive match on SimError — compiler-enforced coverage.
    * Adding a new SimError subtype without a case here is a compile error
    * (requires -Wconf:msg=match may not be exhaustive:error in build.sbt).
    */
  private def encodeSimError(error: SimError): (StatusCode, ErrorResponse) = error match {
    case ValidationFailed(errors)                  => makeValidationResponse(errors)
    case AccessDenied(reason)                      => makeAccessDeniedResponse(reason)
    case RateLimitExceeded(ip, limit, window)      => makeRateLimitExceededResponse(ip, limit, window)
    // Workspace errors — intentionally collapsed to opaque 404 (A13)
    case _: WorkspaceNotFound                      => makeWorkspaceOpaqueNotFoundResponse()
    case _: WorkspaceExpired                       => makeWorkspaceOpaqueNotFoundResponse()
    case _: TreeNotInWorkspace                     => makeWorkspaceOpaqueNotFoundResponse()
    case RepositoryFailure(reason)                 => makeRepositoryFailureResponse(reason)
    case SimulationFailure(id, cause)              => makeSimulationFailureResponse(id)
    case DataConflict(reason)                      => makeDataConflictResponse(reason)
    case VersionConflict(nodeId, expected, actual) => makeVersionConflictResponse(nodeId, expected, actual)
    case MergeConflict(branch, details)            => makeMergeConflictResponse(branch, details)
  }

  /** Exhaustive match on IrminError — compiler-enforced coverage (ADR-008). */
  private def encodeIrminError(error: IrminError): (StatusCode, ErrorResponse) = error match {
    case IrminUnavailable(reason)            => makeServiceUnavailableResponse(reason)
    case NetworkTimeout(operation, duration) => makeNetworkTimeoutResponse(operation, duration)
    case IrminHttpError(status, body)        => makeIrminHttpErrorResponse(status, body)
    case IrminGraphQLError(messages, path)   => makeIrminGraphQlErrorResponse(messages, path)
  }

  /** Exhaustive match on FolQueryFailure — compiler-enforced coverage (ADR-028). */
  private def encodeFolQueryFailure(error: FolQueryFailure): (StatusCode, ErrorResponse) = error match {
    case FolParseFailure(message, position)          => makeFolParseFailureResponse(message, position)
    case FolUnknownSymbol(symbol, available)          => makeFolUnknownSymbolResponse(symbol, available)
    case FolBindFailure(errors)                       => makeFolBindFailureResponse(errors)
    case FolDomainNotQuantifiable(typeName, available) => makeFolDomainNotQuantifiableResponse(typeName, available)
    case FolModelValidationFailure(errors)            => makeFolModelValidationFailureResponse(errors)
    case FolEvaluationFailure(message, phase)         => makeFolEvaluationFailureResponse(message, phase)
    case SimulationNotCached(treeId)                  => makeSimulationNotCachedResponse(treeId)
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
    response(StatusCode.InternalServerError, "unknown", ValidationErrorCode.INTERNAL_ERROR,
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

  def makeAccessDeniedResponse(reason: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Forbidden, "authorization", ValidationErrorCode.ACCESS_DENIED,
      "Forbidden", domain, requestId)

  def makeRateLimitExceededResponse(ip: String, limit: Int, window: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.TooManyRequests, "rate-limit", ValidationErrorCode.RATE_LIMIT_EXCEEDED,
      "Too many requests", domain, requestId)

  /** A13: constant opaque 404 — intentionally resource-neutral to avoid leaking
    * whether the workspace key, tree association, or TTL caused the failure.
    */
  def makeWorkspaceOpaqueNotFoundResponse(domain: String = WorkspaceDomain, requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.NotFound, "resource", ValidationErrorCode.NOT_FOUND,
      "Not found", domain, requestId)

  def makeRepositoryFailureResponse(reason: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    // A25: do not leak repository internals in client responses
    response(StatusCode.InternalServerError, "unknown", ValidationErrorCode.INTERNAL_ERROR,
      "Internal server error", domain, requestId)

  def makeSimulationFailureResponse(simulationId: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.InternalServerError, "simulation", ValidationErrorCode.INTERNAL_ERROR,
      s"Simulation $simulationId failed", domain, requestId)

  // ── Infrastructure Error Responses (ADR-008) ───────────────────────────────

  def makeServiceUnavailableResponse(reason: String, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.ServiceUnavailable, "service", ValidationErrorCode.DEPENDENCY_FAILED,
      s"Service unavailable: $reason", domain, requestId)

  def makeNetworkTimeoutResponse(operation: String, duration: Duration, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.GatewayTimeout, "network", ValidationErrorCode.DEPENDENCY_FAILED,
      s"Network timeout after ${duration.toMillis}ms during: $operation", domain, requestId)

  def makeIrminHttpErrorResponse(status: StatusCode, body: String, domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    // A24: never forward upstream body to clients
    response(status, "http", ValidationErrorCode.DEPENDENCY_FAILED,
      s"Upstream service error (HTTP ${status.code})", domain, requestId)

  def makeIrminGraphQlErrorResponse(messages: List[String], path: Option[List[String]], domain: String = "irmin", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    val field = path.filter(_.nonEmpty).map(_.mkString(".")).getOrElse("graphql")
    response(StatusCode.BadGateway, field, ValidationErrorCode.DEPENDENCY_FAILED,
      messages.mkString("; "), domain, requestId)

  def makeVersionConflictResponse(nodeId: String, expected: String, actual: String, domain: String = "risk-trees", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Conflict, "version", ValidationErrorCode.VERSION_CONFLICT,
      s"Version conflict on node $nodeId: expected $expected, found $actual", domain, requestId)

  def makeMergeConflictResponse(branch: String, details: String, domain: String = "scenarios", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Conflict, "branch", ValidationErrorCode.MERGE_CONFLICT,
      s"Merge conflict on branch $branch: $details", domain, requestId)

  // ── FOL Query Error Responses (ADR-028) ─────────────────────────────────

  def makeFolParseFailureResponse(message: String, position: Option[Int], domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    val detail = position.fold(message)(p => s"$message (at position $p)")
    response(StatusCode.BadRequest, "query", ValidationErrorCode.PARSE_ERROR, detail, domain, requestId)

  def makeFolUnknownSymbolResponse(symbol: String, available: List[String], domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.BadRequest, "query", ValidationErrorCode.UNKNOWN_SYMBOL,
      s"Unknown symbol '$symbol'. Available: ${available.mkString(", ")}", domain, requestId)

  def makeFolBindFailureResponse(errors: List[String], domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    val details = errors.map(e => ErrorDetail(domain, "query", ValidationErrorCode.BIND_FAILED, e, requestId))
    val message = s"Query type-checking failed: ${errors.mkString("; ")}"
    (StatusCode.BadRequest, ErrorResponse(JsonHttpError(StatusCode.BadRequest.code, message, details)))

  def makeFolDomainNotQuantifiableResponse(typeName: String, availableTypes: Set[String], domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.BadRequest, "query", ValidationErrorCode.DOMAIN_NOT_QUANTIFIABLE,
      s"Type '$typeName' cannot be quantified over. Available domain types: ${availableTypes.mkString(", ")}", domain, requestId)

  def makeFolModelValidationFailureResponse(errors: List[String], domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    val details = errors.map(e => ErrorDetail(domain, "query", ValidationErrorCode.MODEL_VALIDATION_FAILED, e, requestId))
    val message = s"Runtime model validation failed: ${errors.mkString("; ")}"
    (StatusCode.InternalServerError, ErrorResponse(JsonHttpError(StatusCode.InternalServerError.code, message, details)))

  def makeFolEvaluationFailureResponse(message: String, phase: String, domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.InternalServerError, "query", ValidationErrorCode.EVALUATION_FAILED,
      s"Evaluation failed in $phase: $message", domain, requestId)

  def makeSimulationNotCachedResponse(treeId: com.risquanter.register.domain.data.iron.TreeId, domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
    response(StatusCode.Conflict, "simulation", ValidationErrorCode.SIMULATION_REQUIRED,
      s"Simulation not cached for tree ${treeId.value}", domain, requestId)
}
