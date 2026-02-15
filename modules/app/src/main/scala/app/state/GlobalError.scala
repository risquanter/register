package app.state

import com.risquanter.register.domain.errors.*
import app.core.safeMessage

/** Global error ADT for errors that are not handled by per-view
  * error channels (LoadState.Failed, SubmitState.Failed, submitError).
  *
  * These are "nobody caught this" errors — network failures during fire-and-forget
  * calls, unexpected server errors, or future cross-cutting concerns
  * (workspace auth failures, SSE disconnection).
  *
  * Per-view errors should NOT be duplicated here — the ErrorBanner supplements,
  * it does not replace, the existing inline error display (ADR-008 / Option A).
  *
  * Named `GlobalError` (not `AppError`) to avoid collision with the server-side
  * `com.risquanter.register.domain.errors.AppError` sealed trait.
  *
  * All variants are pure values with no embedded side effects (ADR-010: errors
  * are values). UI actions (e.g. a refresh button on Conflict) are determined
  * at the rendering site, not stored in the error.
  *
  * @see ADR-008 (error handling & resilience)
  * @see ADR-010 (accepted error handling strategy)
  */
enum GlobalError:
  /** Server-side validation rejection (HTTP 400).
    * Carries the structured `ValidationError` list from the shared domain.
    */
  case ValidationFailed(errors: List[ValidationError])

  /** Network-level failure (connection refused, DNS, timeout) or
    * unclassified non-HTTP error.
    * `retryable` indicates whether a retry is meaningful.
    */
  case NetworkError(message: String, retryable: Boolean)

  /** Data or version conflict (HTTP 409).
    * The banner rendering site decides which refresh action to offer
    * based on context (e.g. reload tree list).
    */
  case Conflict(message: String)

  /** Server error (HTTP 5xx) — repository failure, simulation failure, etc. */
  case ServerError(message: String)

  /** Infrastructure dependency failure (Irmin unavailable, GraphQL error, timeout).
    * Transient — retry may succeed.
    */
  case DependencyError(message: String)

  /** Workspace pre-validation detected an expired or missing workspace.
    * Informational — not an error. Rendered with blue info styling in ErrorBanner,
    * not the red error treatment. The stale key has already been cleared;
    * the user can create a new tree to start a fresh workspace.
    */
  case WorkspaceExpired(message: String)

object GlobalError:

  /** Classify a Throwable into a GlobalError.
    *
    * Pattern-matches on the shared `AppError` sealed hierarchy from the
    * `common` module. Since `ErrorResponse.decode` reconstructs domain
    * error types (not raw `RuntimeException`), this classifier is
    * exhaustive over the known error space.
    *
    * For non-HTTP exceptions (e.g. transport-layer failures in the browser),
    * falls through to JVM exception type matching.
    */
  def fromThrowable(e: Throwable): GlobalError = e match
    // ── Transport layer errors — check FIRST, before domain types ──
    // Fetch API failures must be caught before domain pattern-matching
    // because sttp passes raw JS exceptions through (not SttpClientException).
    case _ if isFetchNetworkError(e) =>
      NetworkError(msg(e), retryable = true)

    case _: java.io.IOException => NetworkError(msg(e), retryable = true)

    // ── Shared domain errors (reconstructed by ErrorResponse.decode) ──
    case vf: com.risquanter.register.domain.errors.ValidationFailed =>
      ValidationFailed(vf.errors)

    case _: DataConflict    => Conflict(msg(e))
    case _: VersionConflict => Conflict(msg(e))
    case _: MergeConflict   => Conflict(msg(e))

    case _: IrminError      => DependencyError(msg(e))

    case _: SimError        => ServerError(msg(e))

    // ── Catch-all ──
    case _ => NetworkError(msg(e), retryable = false)

  /** Detect browser Fetch API network failures.
    *
    * The Fetch API signals connection-refused / DNS / timeout as a
    * `TypeError` with varying messages across browsers:
    *   - Firefox: "NetworkError when attempting to fetch resource."
    *   - Chrome:  "Failed to fetch"
    *   - Safari:  "Load failed"
    *
    * sttp's FetchZioBackend does NOT wrap these in `SttpClientException`
    * on Scala.js — the raw `JavaScriptException` passes through unchanged.
    */
  private def isFetchNetworkError(e: Throwable): Boolean =
    val message = Option(e.getMessage).getOrElse("")
    message.contains("NetworkError when attempting to fetch") ||
    message.contains("Failed to fetch") ||
    message.contains("Load failed")

  /** Extract a user-friendly message from a Throwable.
    *
    * Strips browser-internal prefixes ("TypeError: ", "Error: ") that
    * leak JS implementation details into the UI.
    */
  private def msg(e: Throwable): String =
    e.safeMessage
      .replaceFirst("^TypeError:\\s*", "")
      .replaceFirst("^Error:\\s*", "")
