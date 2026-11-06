package app.state

import app.core.RequestFailure
import app.views.ErrorMessages
import com.risquanter.register.domain.data.iron.TraceId
import com.risquanter.register.http.errors.WireError

/** A condition of the whole session, which the global banner shows.
  *
  * The banner shows only these: the server cannot be reached, a service the
  * server depends on is down, or the workspace has expired. The result of a
  * specific action is shown inline where the action was taken, and a failure
  * caused by one of these conditions is shown in both places, because the two
  * say different things: that this action failed, and that the next one will
  * fail too (ADR-019 decision 7).
  *
  * Named `GlobalError` rather than `AppError` so it does not collide with the
  * shared `com.risquanter.register.domain.errors.AppError`.
  *
  * Every case is a pure value and none carries an action. What a case offers the
  * user — a dismiss, the information styling — is decided where it is rendered
  * (ADR-010).
  */
enum GlobalError:
  /** The request never reached the server. Request-path retries belong to the
    * mesh, so the browser reports this rather than retrying (ADR-012 §4,
    * ADR-031).
    */
  case ServerUnreachable

  /** A service the server depends on did not answer. Carries the trace,
    * because the user can act on nothing else.
    */
  case DependencyDown(trace: Option[TraceId])

  /** The workspace is past one of its two deadlines. Informational rather than
    * an error, and rendered with the information styling: the stale key has
    * already been cleared, and creating a tree starts a fresh workspace.
    */
  case WorkspaceExpired(message: String)

object GlobalError:

  /** The session condition a failure reveals, if it reveals one.
    *
    * A transport failure means the server cannot be reached. An unreadable
    * answer means the server was reached, so it reveals no condition and is
    * shown only where the action was taken.
    *
    * {{{
    * from(RequestFailure.Transport(cause))            // Some(ServerUnreachable)
    * from(RequestFailure.Wire(WireError.Conflict(…))) // None
    * }}}
    */
  def from(failure: RequestFailure): Option[GlobalError] = failure match
    case RequestFailure.Wire(error)    => fromWireError(error)
    case RequestFailure.Transport(_)   => Some(ServerUnreachable)
    case RequestFailure.Undecodable(_) => None

  /** The session condition a server answer reveals, if it reveals one.
    *
    * The match is on the nine wire cases, so a new case is a compile error here
    * until it is placed as a condition or as a per-view result.
    */
  def fromWireError(e: WireError): Option[GlobalError] = e match
    case WireError.DependencyUnavailable(t)           => Some(DependencyDown(Some(t)))
    case WireError.WorkspaceExpired(c, life, idle, _) => Some(WorkspaceExpired(ErrorMessages.workspaceExpired(c, life, idle)))
    case WireError.RequestRejected(_, _)              => None
    case WireError.ValidationRejected(_, _, _)        => None
    case WireError.NotFound(_, _)                     => None
    case WireError.AccessDenied(_)                    => None
    case WireError.Conflict(_, _)                     => None
    case WireError.NotSupported(_, _)                 => None
    case WireError.InternalFault(_)                   => None
