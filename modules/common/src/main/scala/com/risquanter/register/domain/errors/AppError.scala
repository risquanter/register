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
case class RepositoryFailure(reason: String) extends SimError:
  override def getMessage: String = reason

object RepositoryFailure:
  /** Sentinel prefix for workspace-level errors reconstructed by `ErrorResponse.decode`.
    *
    * A13 opaque 404s are decoded as `RepositoryFailure("workspace:…")` because
    * the real `WorkspaceNotFound` requires a `WorkspaceKeySecret` that is lost
    * through the opaque wire format.  Both `GlobalError.fromThrowable` and
    * `ZJS.loadInto` use this predicate to route workspace errors to the blue
    * info banner rather than inline error displays.
    */
  val WorkspaceSentinelPrefix: String = "workspace:"

  /** Check whether a `Throwable` is a workspace-level sentinel error. */
  def isWorkspaceSentinel(e: Throwable): Boolean = e match
    case rf: RepositoryFailure => rf.reason.startsWith(WorkspaceSentinelPrefix)
    case _                     => false

/** Simulation execution failure - wraps underlying cause with context */
case class SimulationFailure(simulationId: String, cause: Throwable) extends SimError {
  override def getMessage: String = s"Simulation $simulationId failed: ${cause.getMessage}"
  override def getCause: Throwable = cause
}

/** Data conflict (e.g., duplicate key) */
case class DataConflict(reason: String) extends SimError:
  override def getMessage: String = reason

/** Authorization failure for disabled/forbidden operations */
case class AccessDenied(reason: String) extends SimError:
  override def getMessage: String = reason

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

// ============================================================================
// FOL Query Errors (ADR-028: Vague Quantifier Query Evaluation)
// ============================================================================

/** Errors originating from fol-engine interaction or query preconditions.
  * The `Fol` prefix identifies types mapped from `fol.error.QueryError`
  * (the library's error algebra); the `Failure` suffix follows the
  * `RepositoryFailure` / `SimulationFailure` naming convention (D13).
  *
  * @see ADR-028 §4 — Query Validation Before Evaluation
  */
sealed trait FolQueryFailure extends AppError

object FolQueryFailure:
  /** Maps from `fol.error.QueryError.ParseError` / `LexicalError`.
    *
    * Syntactic parse failure — the query string is not well-formed FOL syntax.
    * The parser rejected the input before any semantic analysis.
    *
    * Example: `"Q[>=]^{2/3 x (leaf(x))"` — missing closing brace after
    * the quantifier ratio produces `ParseError("Expected '}' ...", pos=14)`.
    *
    * HTTP 400 — `PARSE_ERROR`.
    */
  final case class FolParseFailure(message: String, position: Option[Int])
    extends FolQueryFailure:
    override def getMessage: String =
      position.fold(message)(p => s"$message (at position $p)")

  /** Maps from `fol.error.QueryError.RelationNotFoundError` /
    * `UninterpretedSymbolError` / `SchemaError`.
    *
    * The query is syntactically valid but references a predicate or function
    * name that does not exist in the `TypeCatalog`.
    *
    * Example: `"Q[>=]^{2/3} x (leaf(x), gt_loss(p96(x), 5000))"` —
    * `p96` is not a declared function; available functions are `p95`, `p99`, `lec`.
    *
    * HTTP 400 — `UNKNOWN_SYMBOL`.
    */
  final case class FolUnknownSymbol(symbol: String, available: List[String])
    extends FolQueryFailure:
    override def getMessage: String =
      s"Unknown symbol '$symbol'. Available: ${available.mkString(", ")}"

  /** Maps from `fol.error.QueryError.BindError`.
    *
    * The query is syntactically valid and all symbols are known, but the
    * typed bind phase (type-checking variable sorts against the catalog)
    * detected one or more type errors. This covers arity mismatches,
    * sort conflicts, and quantification over non-domain types.
    *
    * Example: `"Q[>=]^{1/2} x:Loss (gt_loss(x, 5000))"` — the type
    * `Loss` is a `ValueType` (computed output) and cannot be quantified
    * over; only `DomainType` sorts like `Asset` have finite domains.
    * The bind phase rejects this with `TypeNotQuantifiable("Loss")`.
    *
    * HTTP 400 — `BIND_FAILED`.
    */
  final case class FolBindFailure(errors: List[String])
    extends FolQueryFailure:
    override def getMessage: String =
      s"Query type-checking failed: ${errors.mkString("; ")}"

  /** Maps from `fol.error.QueryError.ModelValidationError`.
    *
    * The `RuntimeModel` does not satisfy the `TypeCatalog` contract:
    * a declared function or predicate has no dispatcher implementation,
    * or a `DomainType` has no registered element set. This is an
    * infrastructure / wiring error in `RiskTreeKnowledgeBase`, not a
    * user query error.
    *
    * Example: Register declares `lec: (Asset, Loss) → Probability` in
    * the catalog but the `RuntimeDispatcher` omits `lec` from its
    * `functionSymbols` set. `FolModel(catalog, model)` validation
    * catches this before any query is evaluated.
    *
    * HTTP 500 — `MODEL_VALIDATION_FAILED`.
    */
  final case class FolModelValidationFailure(errors: List[String])
    extends FolQueryFailure:
    override def getMessage: String =
      s"Runtime model validation failed: ${errors.mkString("; ")}"

  /** Maps from `fol.error.QueryError.DomainNotFoundError` (D14).
    *
    * The query attempts to quantify over a type that has no registered
    * domain in the `RuntimeModel`. In a correctly wired system this is
    * unreachable (the bind phase rejects non-domain quantification via
    * `TypeNotQuantifiable`). Mapped as a defensive fallback.
    *
    * Example: If a catalog declared `Loss` as `DomainType` (incorrect)
    * but the `RuntimeModel` had no domain set for `Loss`, evaluation
    * would reach this error. In practice, `FolModel` validation catches
    * such gaps first.
    *
    * HTTP 400 — `DOMAIN_NOT_QUANTIFIABLE`.
    */
  final case class FolDomainNotQuantifiable(typeName: String, availableTypes: Set[String])
    extends FolQueryFailure:
    override def getMessage: String =
      s"Queries can only range over tree nodes (type 'Asset'). " +
      s"The type '$typeName' cannot be enumerated. " +
      s"Available quantifiable types: ${availableTypes.mkString(", ")}"

  /** Maps from `fol.error.QueryError.EvaluationError` /
    * `ScopeEvaluationError` / `TypeMismatchError` / `TimeoutError` / etc.
    *
    * Catch-all for unexpected evaluation-phase failures that occur after
    * binding succeeds. These indicate internal errors (dispatcher bugs,
    * resource exhaustion) rather than user query mistakes.
    *
    * Example: The dispatcher's `evalFunction("p95", args)` throws because
    * simulation data is corrupted, surfacing as
    * `EvaluationError("Division by zero", phase="function:p95")`.
    *
    * HTTP 500 — `EVALUATION_FAILED`.
    */
  final case class FolEvaluationFailure(message: String, phase: String)
    extends FolQueryFailure:
    override def getMessage: String = s"Evaluation failed in $phase: $message"

  /** Register precondition — no `Fol` prefix because no fol-engine involvement.
    *
    * Simulation results have not yet been computed for the requested tree.
    * The user must run a simulation before querying.
    *
    * Example: POST `/w/{key}/risk-trees/{treeId}/query` before any
    * simulation has been triggered for `treeId`.
    *
    * HTTP 409 — `SIMULATION_REQUIRED`.
    */
  final case class SimulationNotCached(treeId: TreeId)
    extends FolQueryFailure:
    override def getMessage: String =
      s"Simulation not cached for tree ${treeId.value}"

  /** Centralised mapping from library `fol.error.QueryError` to register's
    * `FolQueryFailure` hierarchy. Used by both the controller (parse errors)
    * and the service layer (evaluation errors).
    *
    * @see T2.3b mapping table in IMPLEMENTATION-PLAN-QUERY-PANE.md
    */
  def fromQueryError(err: fol.error.QueryError): FolQueryFailure =
    import fol.error.QueryError as QE
    err match
      // ── Parse-phase errors → 400 ────────────────────────────────
      case e: QE.ParseError               => FolParseFailure(e.message, e.position)
      case e: QE.LexicalError             => FolParseFailure(e.message, Some(e.position))
      // ── Symbol-resolution errors → 400 ──────────────────────────
      case e: QE.RelationNotFoundError    => FolUnknownSymbol(e.relationName.value, e.availableRelations.map(_.value).toList)
      case e: QE.UninterpretedSymbolError => FolUnknownSymbol(e.symbolName, e.availableSymbols.toList)
      case e: QE.SchemaError              => FolUnknownSymbol(e.relationName.value, Nil)
      // ── Bind-phase type errors → 400 ────────────────────────────
      case e: QE.BindError                => FolBindFailure(e.errors)
      // ── Domain-not-found → 400 (D14, defensive fallback) ───────
      case e: QE.DomainNotFoundError      => FolDomainNotQuantifiable(e.typeName, e.availableTypes)
      // ── Model-validation → 500 (wiring error) ──────────────────
      case e: QE.ModelValidationError     => FolModelValidationFailure(e.errors)
      // ── Evaluation-phase errors → 500 ───────────────────────────
      case e: QE.EvaluationError          => FolEvaluationFailure(e.message, e.phase)
      case e: QE.ScopeEvaluationError     => FolEvaluationFailure(e.message, "scope")
      case e: QE.TypeMismatchError        => FolEvaluationFailure(e.message, "type_check")
      case e: QE.TimeoutError             => FolEvaluationFailure(e.message, e.operation)
      case e: QE.QuantifierError          => FolEvaluationFailure(e.message, "quantifier")
      case e: QE.ValidationError          => FolEvaluationFailure(e.message, "validation")
      // ── Remaining subtypes → 500 (each explicit for exhaustiveness) ─
      case e: QE.QueryStructureError      => FolEvaluationFailure(e.message, "query_structure")
      case e: QE.DataStoreError           => FolEvaluationFailure(e.message, "data_store")
      case e: QE.PositionOutOfBoundsError => FolEvaluationFailure(e.message, "position_bounds")
      case e: QE.UnboundVariableError     => FolEvaluationFailure(e.message, "unbound_variable")
      case e: QE.ResourceError            => FolEvaluationFailure(e.message, "resource")
      case e: QE.ConnectionError          => FolEvaluationFailure(e.message, "connection")
      case e: QE.ConfigError              => FolEvaluationFailure(e.message, "config")
