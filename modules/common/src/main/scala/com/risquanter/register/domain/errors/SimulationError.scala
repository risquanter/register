package com.risquanter.register.domain.errors

import scala.concurrent.duration.Duration

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

/** Simulation execution failure - wraps underlying cause with context */
case class SimulationFailure(simulationId: String, cause: Throwable) extends SimulationError {
  override def getMessage: String = s"Simulation $simulationId failed: ${cause.getMessage}"
  override def getCause: Throwable = cause
}

/** Data conflict (e.g., duplicate key) */
case class DataConflict(reason: String) extends SimulationError

// ============================================================================
// Infrastructure Errors (ADR-008: Error Handling & Resilience)
// ============================================================================

/** External service (Irmin) is unavailable - transient, retriable */
case class IrminUnavailable(reason: String) extends SimulationError {
  override def getMessage: String = s"Irmin service unavailable: $reason"
}

/** Network operation timed out - transient, retriable */
case class NetworkTimeout(operation: String, duration: Duration) extends SimulationError {
  override def getMessage: String = s"Network timeout after ${duration.toMillis}ms during: $operation"
}

/** Optimistic locking conflict - client should refresh and retry */
case class VersionConflict(nodeId: String, expected: String, actual: String) extends SimulationError {
  override def getMessage: String = s"Version conflict on node $nodeId: expected $expected, found $actual"
}

/** Branch merge conflict - requires user intervention */
case class MergeConflict(branch: String, details: String) extends SimulationError {
  override def getMessage: String = s"Merge conflict on branch $branch: $details"
}
