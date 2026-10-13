package com.risquanter.register.http.errors

import java.time.Duration
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.TraceId
import com.risquanter.register.domain.errors.*
import com.risquanter.register.domain.errors.FolQueryFailure.*
// Two internal error names collide with the wire case that carries them. The
// rename is the one sanctioned use of a non-plain import (ADR-011 §2), and it
// keeps `fromAppError`'s arms reading left-to-right: internal name in, wire name
// out.
import com.risquanter.register.domain.errors.{
  WorkspaceExpired as AppWorkspaceExpired,
  AccessDenied as AppAccessDenied
}

/** The only error type that crosses to a client.
  *
  * Projected from `AppError` by `fromAppError`, which is the single place a
  * field stops crossing. There is no inverse: a client deserialises this type
  * and uses it, exactly as it does with a response type in `http/responses`
  * (ADR-035). A confidential identifier cannot appear in a response because no
  * case here declares one (ADR-036).
  *
  * Nine cases, one per HTTP status, so no two cases share a status. The browser
  * selects a case by status alone, so two cases at one status would make the
  * first one declared win silently; where several outcomes share a status they
  * share a case and an inner enumeration discriminates.
  *
  * Every case carries `trace`, the trace identifier of the request whose work
  * failed, so a failure the user sees names the trace it came from. It is last
  * in every parameter list, so the case's own content reads first. The accessor
  * is declared here and each case's own parameter implements it, which is what
  * lets a caller read it without matching on the case first.
  */
enum WireError:
  def trace: TraceId

  /** A request body the decoders rejected. Carries one entry per independent
    * failure, so a form shows every problem at once.
    */
  case ValidationRejected(errors: List[ValidationError], trace: TraceId)

  /** A well-formed request whose query the engine refused. */
  case QueryRejected(problem: QueryProblem, trace: TraceId)

  /** No such resource, or one the caller may not see. The two are one response
    * so that neither is an existence oracle (ADR-035 §3).
    */
  case NotFound(trace: TraceId)

  /** A workspace past one of its two deadlines.
    *
    * Three values cross, and each has one job:
    *   - `cause` — which deadline ended this workspace, the absolute one or the
    *     idle one. It is what decides the sentence the browser writes.
    *   - `lifetime` — the absolute window, counted from when the workspace was
    *     created. Use does not extend it.
    *   - `idleLimit` — the idle window, counted from the workspace's last use.
    *     Every use pushes that deadline forward.
    *
    * Both windows are sent on every expiry, not only the one that was hit, so
    * the response has one shape whatever the cause and the browser can state the
    * whole rule when that is the more useful message.
    *
    * The two windows are deployment configuration. Sending them tells a caller
    * what this deployment is configured to — a caller that already holds the
    * workspace's key.
    */
  case WorkspaceExpired(cause: ExpiryCause, lifetime: Duration, idleLimit: Duration, trace: TraceId)

  /** Refused. Every denial produces this one shape: an explicit deny and an
    * unreachable authorization service must be indistinguishable, or an outage
    * is observable from outside (ADR-035 §2).
    */
  case AccessDenied(trace: TraceId)

  /** The request conflicts with current state. */
  case Conflict(conflict: ConflictKind, trace: TraceId)

  /** The deployment does not provide this capability. */
  case NotSupported(capability: Capability, trace: TraceId)

  /** A dependency this request needed did not answer. Which dependency, and how
    * it failed, stay internal (ADR-035 §2).
    */
  case DependencyUnavailable(trace: TraceId)

  /** An unexpected server-side fault. The reason is in the server log, against
    * this trace.
    */
  case InternalFault(trace: TraceId)

object WireError:
  /** One codec per case, because each case is a separate response body declared
    * at its own status in `BaseEndpoint`'s `oneOf`. There is deliberately no
    * codec for the enum as a whole: the status names the case, so a body carries
    * the case's own fields and no discriminator field.
    */
  given validationRejectedCodec:    JsonCodec[ValidationRejected]    = DeriveJsonCodec.gen[ValidationRejected]
  given queryRejectedCodec:         JsonCodec[QueryRejected]         = DeriveJsonCodec.gen[QueryRejected]
  given notFoundCodec:              JsonCodec[NotFound]              = DeriveJsonCodec.gen[NotFound]
  given workspaceExpiredCodec:      JsonCodec[WorkspaceExpired]      = DeriveJsonCodec.gen[WorkspaceExpired]
  given accessDeniedCodec:          JsonCodec[AccessDenied]          = DeriveJsonCodec.gen[AccessDenied]
  given conflictCodec:              JsonCodec[Conflict]              = DeriveJsonCodec.gen[Conflict]
  given notSupportedCodec:          JsonCodec[NotSupported]          = DeriveJsonCodec.gen[NotSupported]
  given dependencyUnavailableCodec: JsonCodec[DependencyUnavailable] = DeriveJsonCodec.gen[DependencyUnavailable]
  given internalFaultCodec:         JsonCodec[InternalFault]         = DeriveJsonCodec.gen[InternalFault]

  /** Project an internal failure onto the wire.
    *
    * Every arm binds what it keeps and writes `_` only for what it deliberately
    * gives up, so the full set of drops is readable in one screen. The match is
    * on the 31 leaf types rather than on the four sub-traits, so
    * `-Wconf:msg=match may not be exhaustive:error` makes a new error type a
    * compile error here until an arm is written for it.
    */
  def fromAppError(e: AppError, trace: TraceId): WireError =
    // Bound once so each arm reads `t` rather than repeating the parameter. The
    // trace is not a field of the error: it is read from the active span at the
    // boundary that calls this, which is the only place an effect exists to
    // read it.
    val t = trace

    e match
      // Request rejected
      case ValidationFailed(errors)            => WireError.ValidationRejected(errors, t)

      // Query refused
      case FolParseFailure(message, position)  => WireError.QueryRejected(QueryProblem.Unparseable(message, position), t)
      case FolUnknownReference(messages)       => WireError.QueryRejected(QueryProblem.UnknownReferences(messages), t)
      case FolBindFailure(errors)              => WireError.QueryRejected(QueryProblem.TypeCheckFailed(errors), t)
      case FolDomainNotQuantifiable(message)   => WireError.QueryRejected(QueryProblem.NotQuantifiable(message), t)

      // Absent, or not the caller's. One shape for both, so neither is an
      // existence oracle. The workspace identifier may not cross (ADR-036), and
      // the tree identifier is dropped because it would distinguish the two.
      case WorkspaceNotFound()                 => WireError.NotFound(t)
      case TreeNotInWorkspace(_, _)            => WireError.NotFound(t)

      // Expired. The configured lifetime crosses; the workspace identifier may
      // not (ADR-036), and the moment this one expired is a fact about a single
      // workspace that the browser has no use for.
      case AppWorkspaceExpired(_, ttl, idle, cause, _) => WireError.WorkspaceExpired(cause, ttl, idle, t)

      // Refused. The internal reason is dropped from all three: an explicit deny
      // and an unreachable authorization service must look identical.
      case AppAccessDenied(_)                  => WireError.AccessDenied(t)
      case AuthForbidden(_, _, _, _)           => WireError.AccessDenied(t)
      case AuthServiceUnavailable(_, _)        => WireError.AccessDenied(t)

      // Conflicts
      case DataConflict(_)                     => WireError.Conflict(ConflictKind.Rejected(), t)
      case VersionConflict(_, _, _)            => WireError.Conflict(ConflictKind.StaleVersion(), t)
      case MergeConflict(scenario, refusal)    => WireError.Conflict(ConflictKind.MergeRefused(scenario, refusal), t)
      case MergeAlreadyRunning(scenario)       => WireError.Conflict(ConflictKind.MergeAlreadyRunning(scenario), t)
      case MergeTargetMoved(scenario)          => WireError.Conflict(ConflictKind.MergeTargetMoved(scenario), t)
      case ScenarioHeadStale(scenario, exp, _) => WireError.Conflict(ConflictKind.ScenarioHeadStale(scenario, exp), t)
      case SimulationNotCached(treeId)         => WireError.Conflict(ConflictKind.SimulationRequired(treeId), t)

      // Capability absent
      case ScenariosNotSupported(_)            => WireError.NotSupported(Capability.Scenarios, t)

      // Dependency did not answer
      case IrminUnavailable(_)                 => WireError.DependencyUnavailable(t)
      case NetworkTimeout(_, _)                => WireError.DependencyUnavailable(t)
      case IrminHttpError(_, _)                => WireError.DependencyUnavailable(t)
      case IrminGraphQLError(_, _)             => WireError.DependencyUnavailable(t)

      // Faults. The three compare-and-set outcomes are translated into domain
      // errors by the scenario service before they reach here; these arms are the
      // net if that translation is ever skipped, and a client can act on none of
      // them.
      case BranchAlreadyExists(_)              => WireError.InternalFault(t)
      case BranchHeadStale(_, _)               => WireError.InternalFault(t)
      case IrminMergeConflict(_)               => WireError.InternalFault(t)
      case RepositoryFailure(_)                => WireError.InternalFault(t)
      case TreeLoadFailure(_, _)               => WireError.InternalFault(t)
      case SimulationFailure(_, _)             => WireError.InternalFault(t)
      case FolModelValidationFailure(_)        => WireError.InternalFault(t)
      case FolEvaluationFailure(_, _)          => WireError.InternalFault(t)
