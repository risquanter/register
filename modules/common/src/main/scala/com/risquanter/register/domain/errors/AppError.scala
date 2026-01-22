package com.risquanter.register.domain.errors

import scala.concurrent.duration.Duration
import sttp.model.StatusCode

sealed trait AppError extends Throwable
sealed trait SimError extends AppError
sealed trait IrminError extends AppError

/** Validation error with structured information */
case class ValidationError(
  field: String,
  code: ValidationErrorCode,
  message: String
)

/** Validation failure with accumulated structured errors */
case class ValidationFailed(errors: List[ValidationError]) extends SimError {
  override def getMessage: String = errors.map(e => s"[${e.field}] ${e.message}").mkString("; ")
}

/** Repository operation failure */
case class RepositoryFailure(reason: String) extends SimError

/** Simulation execution failure - wraps underlying cause with context */
case class SimulationFailure(simulationId: String, cause: Throwable) extends SimError {
  override def getMessage: String = s"Simulation $simulationId failed: ${cause.getMessage}"
  override def getCause: Throwable = cause
}

/** Data conflict (e.g., duplicate key) */
case class DataConflict(reason: String) extends SimError

// ============================================================================
// Infrastructure Errors (ADR-008: Error Handling & Resilience)
// ============================================================================

/** External service (Irmin) is unavailable - transient, retriable */
case class IrminUnavailable(reason: String) extends IrminError {
  override def getMessage: String = s"Irmin service unavailable: $reason"
}

/** Irmin HTTP-layer error (non-503) */
case class IrminHttpError(status: StatusCode, body: String) extends IrminError {
  override def getMessage: String = s"HTTP ${status.code}: $body"
}

/** GraphQL error surface from Irmin */
case class IrminGraphQLError(messages: List[String], path: Option[List[String]]) extends IrminError {
  override def getMessage: String = messages.mkString("; ")
  def fieldPath: String = path.filter(_.nonEmpty).map(_.mkString(".")).getOrElse("graphql")
}

/** Network operation timed out - transient, retriable */
case class NetworkTimeout(operation: String, duration: Duration) extends IrminError {
  override def getMessage: String = s"Network timeout after ${duration.toMillis}ms during: $operation"
}

/** Optimistic locking conflict - client should refresh and retry */
case class VersionConflict(nodeId: String, expected: String, actual: String) extends SimError {
  override def getMessage: String = s"Version conflict on node $nodeId: expected $expected, found $actual"
}

/** Branch merge conflict - requires user intervention */
case class MergeConflict(branch: String, details: String) extends SimError {
  override def getMessage: String = s"Merge conflict on branch $branch: $details"
}
