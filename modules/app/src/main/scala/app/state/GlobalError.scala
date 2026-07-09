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
    *
    * Request-path retries are owned by Istio (ADR-012 §4 + ADR-031).
    * The SPA fails fast; the mesh retries server→dependency traffic.
    */
  case NetworkError(message: String)

  /** Data or version conflict (HTTP 409).
    * The banner rendering site decides which refresh action to offer
    * based on context (e.g. reload tree list).
    */
  case Conflict(message: String)

  /** Server error (HTTP 5xx) — repository failure, simulation failure, etc. */
  case ServerError(message: String)

  /** Infrastructure dependency failure (Irmin unavailable, GraphQL error, timeout).
    * Transient at the infrastructure level — the mesh (ADR-012 §4 + ADR-031)
    * has already exhausted its retries on the register→Irmin path before
    * this variant reaches the browser.
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
    // ── Shared domain errors (reconstructed by ErrorResponse.decode) ──
    case vf: com.risquanter.register.domain.errors.ValidationFailed =>
      ValidationFailed(vf.errors)

    case _: DataConflict    => Conflict(msg(e))
    case _: VersionConflict => Conflict(msg(e))
    case _: MergeConflict   => Conflict(msg(e))

    // Workspace A13 opaque 404 — decoded as RepositoryFailure("workspace:not-found").
    // Route to informational blue banner, not red error.
    case rf: RepositoryFailure if RepositoryFailure.isWorkspaceSentinel(rf) =>
      WorkspaceExpired(
        "Your previous workspace has expired and its data is no longer available. " +
        "Creating a new tree will start a fresh workspace.")

    case _: IrminError      => DependencyError(msg(e))

    case _: SimError        => ServerError(msg(e))

    case _: FolQueryFailure => ServerError(msg(e))

    // ── Catch-all: browser Fetch failures (TypeError), IOExceptions, and anything else ──
    // Request-path retries are owned by Istio (ADR-012 §4 + ADR-031); the SPA fails fast.
    case _ => NetworkError(msg(e))

  /** Extract a user-friendly message from a Throwable.
    *
    * Strips browser-internal prefixes ("TypeError: ", "Error: ") that
    * leak JS implementation details into the UI.
    */
  private def msg(e: Throwable): String =
    e.safeMessage
      .replaceFirst("^TypeError:\\s*", "")
      .replaceFirst("^Error:\\s*", "")
