package com.risquanter.register.http.errors

import java.time.Duration
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{TraceId, TreeId}
import com.risquanter.register.domain.errors.*
import com.risquanter.register.domain.errors.FolQueryFailure.*

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

  /** A request the decoders rejected before any tree was in hand. Carries one
    * entry per independent failure, so a form shows every problem at once.
    */
  case RequestRejected(errors: List[ValidationError], trace: TraceId)

  /** The content of a known tree broke its own rules. The tree is always
    * present, which is what separates this case from `RequestRejected`. What
    * broke is in `detail`: submitted fields, or a query the engine refused.
    */
  case ValidationRejected(tree: TreeId, detail: RejectionDetail, trace: TraceId)

  /** Nothing to answer with. `absence` says which kind: an unknown resource, a
    * resource the caller may not see, or a tree this workspace holds that the
    * revision read lacks. The first two share one kind so that neither is an
    * existence oracle (ADR-035 §3).
    */
  case NotFound(absence: NotFoundKind, trace: TraceId)

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

  /** Refused. Every denial produces this one shape, so a denial this service
    * decided and one the authorization service decided are indistinguishable,
    * and neither discloses whether the resource exists (ADR-035 §2). An
    * authorization service that did not answer is not a denial and does not
    * reach here; it is a dependency outage.
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
  given requestRejectedCodec:       JsonCodec[RequestRejected]       = DeriveJsonCodec.gen[RequestRejected]
  given validationRejectedCodec:    JsonCodec[ValidationRejected]    = DeriveJsonCodec.gen[ValidationRejected]
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
    * on the leaf types rather than on the sub-traits, so
    * `-Wconf:msg=match may not be exhaustive:error` makes a new error type a
    * compile error here until an arm is written for it.
    */
  def fromAppError(e: AppError, trace: TraceId, scope: TreeScope): WireError =
    // A short local name, so the arms below stay one line each and their
    // wire cases line up. The trace is not a field of the error: it is read
    // from the active span at the boundary that calls this, which is the only
    // place an effect exists to read it. `scope` arrives the same way and for
    // the same reason.
    val t = trace

    // A content rejection names its tree; a request-level one has none to name.
    def rejected(detail: RejectionDetail, fallback: List[ValidationError]): WireError =
      scope match
        case TreeScope.InTree(tree) => ValidationRejected(tree, detail, t)
        case TreeScope.NoTree       => RequestRejected(fallback, t)

    // A query runs only on a route carrying `path[TreeId]("treeId")`, so
    // `NoTree` cannot occur here. It is this service's defect rather than the
    // caller's, so it answers the opaque fault and the boundary log line
    // records it.
    def queryRejected(problem: QueryProblem): WireError =
      scope match
        case TreeScope.InTree(tree) => ValidationRejected(tree, RejectionDetail.Query(problem), t)
        case TreeScope.NoTree       => InternalFault(t)

    e match
      // Content of a known tree, or a request that named none
      case ValidationFailed(errors)            => rejected(RejectionDetail.Fields(errors), errors)

      // Query refused
      case FolParseFailure(message, position)  => queryRejected(QueryProblem.Unparseable(message, position))
      case FolUnknownReference(messages)       => queryRejected(QueryProblem.UnknownReferences(messages))
      case FolBindFailure(errors)              => queryRejected(QueryProblem.TypeCheckFailed(errors))
      case FolDomainNotQuantifiable(message)   => queryRejected(QueryProblem.NotQuantifiable(message))

      // Faults: a client can act on none of them.
      case SimulationFailure(_, _)             => WireError.InternalFault(t)
      case FolModelValidationFailure(_)        => WireError.InternalFault(t)
      case FolEvaluationFailure(_, _)          => WireError.InternalFault(t)
