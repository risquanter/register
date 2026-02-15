package app.state

import com.risquanter.register.domain.errors.*

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
    // ── Shared domain errors (reconstructed by ErrorResponse.decode) ──
    case vf: com.risquanter.register.domain.errors.ValidationFailed =>
      ValidationFailed(vf.errors)

    case _: DataConflict    => Conflict(e.getMessage)
    case _: VersionConflict => Conflict(e.getMessage)
    case _: MergeConflict   => Conflict(e.getMessage)

    case _: IrminError      => DependencyError(e.getMessage)

    case _: SimError        => ServerError(e.getMessage)

    // ── Transport layer errors (browser Fetch API / JVM networking) ──
    case _: java.io.IOException => NetworkError(msg(e), retryable = true)

    // ── Catch-all with browser Fetch API detection ──
    case _ if isFetchNetworkError(e) =>
      NetworkError("Server unreachable — check your connection", retryable = true)

    case _ => NetworkError(msg(e), retryable = false)

  /** Detect browser Fetch API network failures.
    *
    * The Fetch API signals connection-refused / DNS / timeout as a
    * `TypeError` with "NetworkError" in the message. This is standard
    * browser behavior (Firefox, Chrome, Safari all use this pattern).
    */
  private def isFetchNetworkError(e: Throwable): Boolean =
    val name = e.getClass.getSimpleName
    val message = Option(e.getMessage).getOrElse("")
    name == "TypeError" && message.contains("NetworkError")

  private def msg(e: Throwable): String =
    Option(e.getMessage).getOrElse("Unknown error")
