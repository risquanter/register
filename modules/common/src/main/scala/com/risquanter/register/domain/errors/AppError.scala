package com.risquanter.register.domain.errors

import scala.concurrent.duration.Duration
import sttp.model.StatusCode
import java.time.{Duration as JDuration, Instant}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId}

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

/** Authorization failure for disabled/forbidden operations */
case class AccessDenied(reason: String) extends SimError

/** Rate limiting failure for abuse-prevention controls */
case class RateLimitExceeded(ip: String, limit: Int, window: String = "1h") extends SimError {
  override def getMessage: String = s"Rate limit exceeded for $ip: max $limit per $window"
}

// ============================================================================
// Workspace Errors (A13: all map to opaque 404 at HTTP layer)
// ============================================================================

/** Workspace not found — maps to opaque 404 (A13).
  * ADR-022: getMessage omits raw key (zero diagnostic value — request URL identifies workspace).
  */
case class WorkspaceNotFound(key: WorkspaceKeySecret) extends SimError {
  override def getMessage: String = "Workspace not found"
}

/** Workspace expired — maps to same opaque 404 as not-found (A13).
  * ADR-022: getMessage uses createdAt/ttl as diagnostics, not raw key.
  */
case class WorkspaceExpired(key: WorkspaceKeySecret, createdAt: Instant, ttl: JDuration) extends SimError {
  override def getMessage: String = s"Workspace expired (created: $createdAt, ttl: $ttl)"
}

/** Tree not associated with workspace — maps to opaque 404 (A13).
  * ADR-022: getMessage omits raw key; treeId is the useful diagnostic.
  */
case class TreeNotInWorkspace(key: WorkspaceKeySecret, treeId: TreeId) extends SimError {
  override def getMessage: String = s"Tree ${treeId.value} not found in workspace"
}

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

// ============================================================================
// Authorization Errors (ADR-024: Externalized Authorization / PEP Pattern)
// ============================================================================

sealed trait AuthError extends AppError

/** SpiceDB returned PERMISSIONSHIP_NO_PERMISSION — explicit deny.
  * HTTP layer maps this to 403 Forbidden.
  * userId is the JWT sub claim value (UUID string), safe for structured audit logs.
  *
  * @see ADR-024: Fail-Closed by Default
  */
case class AuthForbidden(
  userId:       String,
  permission:   String,
  resourceType: String,
  resourceId:   String
) extends AuthError:
  override def getMessage: String =
    s"Access denied: user=$userId permission=$permission resource=$resourceType:$resourceId"

/** SpiceDB unreachable, timed out, or returned an unexpected response — fail-closed.
  * HTTP layer maps this to 403 Forbidden (not 503 — avoids revealing infrastructure state).
  *
  * @see ADR-024: Fail-Closed by Default
  */
case class AuthServiceUnavailable(reason: String, cause: Option[Throwable] = None) extends AuthError:
  override def getMessage: String = s"Authorization service unavailable: $reason"
  override def getCause: Throwable = cause.orNull
