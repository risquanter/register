package app.state

import app.core.RequestFailure
import app.views.ErrorMessages
import com.risquanter.register.domain.data.iron.TraceId
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.http.errors.WireError

/** A failure the global banner shows, classified into the treatment it gets.
  *
  * These are the failures no per-view channel caught — a fire-and-forget call
  * that failed, an unexpected server fault, a workspace that has expired.
  * Per-view errors are not duplicated here, because the banner supplements the
  * inline display rather than replacing it (ADR-019 Pattern 7).
  *
  * Named `GlobalError` rather than `AppError` so it does not collide with the
  * shared `com.risquanter.register.domain.errors.AppError`.
  *
  * Every case is a pure value and none carries an action. What a case offers the
  * user — a reload button, a dismiss — is decided where it is rendered, because
  * only the rendering site knows what is on screen beside it (ADR-010).
  */
enum GlobalError:
  /** The request body was rejected. Carries one entry per independent failure
    * so the banner can show every problem at once.
    */
  case ValidationFailed(errors: List[ValidationError])

  /** The request never reached the server. Request-path retries belong to the
    * mesh, so this fails fast rather than retrying (ADR-012 §4, ADR-031).
    */
  case NetworkError(message: String)

  /** The request reached the server and its content was refused. A refused
    * query is the case this exists for: the problem is in what the user typed,
    * so it gets the user-input treatment rather than the server-fault one.
    */
  case RequestRejected(message: String)

  /** The request conflicts with current state. The rendering site decides which
    * refresh action to offer, because that depends on what the view holds.
    */
  case Conflict(message: String)

  /** The resource is absent, or it is not the caller's. The two are one case
    * because the server answers them identically (ADR-035 §3).
    */
  case NotFound

  /** The workspace is past one of its two deadlines. Informational rather than
    * an error, and rendered with the information styling: the stale key has
    * already been cleared, and creating a tree starts a fresh workspace.
    */
  case WorkspaceExpired(message: String)

  /** A server-side fault. Carries the trace identifier, because the user has
    * nothing else to act on and a reference is the only useful thing on screen.
    */
  case ServerError(message: String, trace: Option[TraceId])

  /** A dependency the request needed did not answer. Carries the trace for the
    * same reason as `ServerError`.
    */
  case DependencyError(message: String, trace: Option[TraceId])

object GlobalError:

  /** Classify any failed request.
    *
    * The two arms are the two things that can go wrong, and they are disjoint:
    * the server answered, or the call never happened.
    */
  def from(failure: RequestFailure): GlobalError = failure match
    case RequestFailure.Wire(error)      => fromWireError(error)
    case RequestFailure.Transport(cause) => fromThrowable(cause)

  /** Classify what the server answered with.
    *
    * The match is on the nine wire cases, so a new case is a compile error here
    * until it has a treatment. Only `ServerError` and `DependencyError` are
    * given the trace, because they are the two the banner displays it on; the
    * others carry an actionable message of their own, beside which a trace
    * identifier is noise.
    */
  def fromWireError(e: WireError): GlobalError = e match
    case WireError.ValidationRejected(errors, _)       => ValidationFailed(errors)
    case WireError.QueryRejected(problem, _)           => RequestRejected(ErrorMessages.query(problem))
    case WireError.NotFound(_)                         => NotFound
    case WireError.WorkspaceExpired(c, life, idle, _)  => WorkspaceExpired(ErrorMessages.workspaceExpired(c, life, idle))
    case WireError.AccessDenied(_)                     => ServerError(ErrorMessages.Forbidden, None)
    case WireError.Conflict(conflict, _)               => Conflict(ErrorMessages.conflict(conflict))
    case WireError.NotSupported(capability, _)         => ServerError(ErrorMessages.capabilityUnavailable(capability), None)
    case WireError.DependencyUnavailable(t)            => DependencyError(ErrorMessages.DependencyDown, Some(t))
    case WireError.InternalFault(t)                    => ServerError(ErrorMessages.InternalFault, Some(t))

  /** Classify a transport failure.
    *
    * The cause is deliberately not read. A browser `fetch` rejection carries
    * developer wording such as "Failed to fetch", which is diagnostic rather
    * than something to show a user, so the banner states what happened and the
    * cause goes to the browser console instead.
    */
  def fromThrowable(cause: Throwable): GlobalError =
    NetworkError(ErrorMessages.TransportFailed)
