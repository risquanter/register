package com.risquanter.register.domain.errors

import java.time.Duration

import zio.prelude.NonEmptyList

import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{TraceId, ValidationMessages}
import com.risquanter.register.http.errors.{Capability, ConflictKind, NotFoundKind, RejectionDetail, TreeScope, WireError}

/** The fields every failure carries. `Z` is the zone type a variant admits and
  * `P` the problem type its list holds; a variant fixes both and adds nothing.
  *
  * `errors` is never empty: a failure with nothing refused is not a failure.
  * `diagnostics` is log-only and never reaches the wire.
  */
final case class Core[Z, P](
  zone:        Z,
  errors:      NonEmptyList[(ErrorLocation, P)],
  diagnostics: List[Diagnostic]
)

/** A deadline a workspace has passed, with the two limits that define it. */
final case class ExpiryDetail(cause: ExpiryCause, lifetime: Duration, idleLimit: Duration)

object ExpiryDetail:
  /** The server's own sentence for a log line. Never reaches a client. */
  def describe(detail: ExpiryDetail): String = detail.cause match
    case ExpiryCause.Lifetime   => s"the lifetime of ${detail.lifetime} has passed"
    case ExpiryCause.Inactivity => s"the idle limit of ${detail.idleLimit} has passed"

/** Every failure the server produces as a value. One variant per answerable
  * wire kind; an internal fault is not a variant but what a value outside the
  * `Client` zone answers as.
  *
  * `Expired` admits only the `Client` zone and `NotSupported` only the
  * `Environment` zone, through the singleton types of those two cases, so an
  * expiry cannot name a store and a missing capability cannot name a caller.
  */
enum Failure:
  case Rejected    (core: Core[TrustZone,                  FieldProblem])
  case Unanswered  (core: Core[PeerZone,                   OutageMode])
  case NotFound    (core: Core[TrustZone,                  NotFoundKind])
  case Conflict    (core: Core[TrustZone,                  ConflictKind])
  case AccessDenied(core: Core[TrustZone,                  DenialKind])
  case Expired     (core: Core[TrustZone.Client.type,      ExpiryDetail])
  case NotSupported(core: Core[TrustZone.Environment.type, Capability])

  /** Project this failure onto the wire: one-way and total over `(variant,
    * zone)`.
    *
    * A value from the `Client` zone answers its own wire case. So does an outage
    * from any peer, a missing capability, and a denial decided by the mesh or the
    * authorization service, because each is the answer the caller acts on. Every
    * other pair is content a peer returned or this service's own, and answers
    * the opaque fault. The zone and the diagnostics are dropped on every arm and
    * the trace is added.
    */
  def toWire(trace: TraceId, scope: TreeScope): WireError = Failure.project(this, trace, scope)

object Failure:
  extension (f: Failure)
    /** The zone, widened from each variant's `Z`. */
    def zone: TrustZone = view(f).zone

    /** `Caller` for `Client`, `Service` for every other zone. Decides the log
      * level: `Caller` is INFO, `Service` is WARN (ADR-002). */
    def origin: FailureOrigin = f.zone match
      case TrustZone.Client => FailureOrigin.Caller
      case TrustZone.Environment | TrustZone.MeshHeader | TrustZone.Peer(_) | TrustZone.Service =>
        FailureOrigin.Service

    /** The server's own text for the log line: the variant's name, then each
      * entry's location and problem. Never crosses to a client.
      *
      * {{{
      * rejected(TrustZone.Client, NonEmptyList(ValidationError(requestField(FieldName.Name), FieldProblem.Required)), Nil).logMessage
      * // Rejected: [request.Name] A value is required
      * }}}
      */
    def logMessage: String =
      view(f).entries.toList.mkString(s"${f.productPrefix}: ", "; ", "")

    /** The structured fields for the log line: every diagnostic rendered, plus
      * `trust_zone`. */
    def logAnnotations: Map[String, String] =
      val v = view(f)
      v.diagnostics.flatMap(Diagnostic.render).toMap + (ZoneKey -> zoneValue(v.zone))

  /** The one way a `Validation` becomes a failure. */
  def rejected(zone: TrustZone, errors: NonEmptyList[ValidationError], diagnostics: List[Diagnostic]): Failure =
    Rejected(Core(zone, errors.map(e => (e.location, e.problem)), diagnostics))

  private def project(f: Failure, t: TraceId, scope: TreeScope): WireError = f match
    case Rejected(c) => c.zone match
      case TrustZone.Client => rejectedOnWire(c.errors, t, scope)
      case TrustZone.Environment | TrustZone.MeshHeader | TrustZone.Peer(_) | TrustZone.Service =>
        WireError.InternalFault(t)

    case NotFound(c) => c.zone match
      case TrustZone.Client => WireError.NotFound(problemOf(c), t)
      case TrustZone.Environment | TrustZone.MeshHeader | TrustZone.Peer(_) | TrustZone.Service =>
        WireError.InternalFault(t)

    case Conflict(c) => c.zone match
      case TrustZone.Client => WireError.Conflict(problemOf(c), t)
      case TrustZone.Environment | TrustZone.MeshHeader | TrustZone.Peer(_) | TrustZone.Service =>
        WireError.InternalFault(t)

    // The mesh's missing identity header and the authorization service's
    // refusal are values the caller did not produce, and still the answer the
    // caller must act on.
    case AccessDenied(c) => c.zone match
      case TrustZone.Client | TrustZone.MeshHeader | TrustZone.Peer(PeerZone.Authorization) =>
        WireError.AccessDenied(t)
      case TrustZone.Environment | TrustZone.Peer(PeerZone.Store) | TrustZone.Peer(PeerZone.Database) |
           TrustZone.Service =>
        WireError.InternalFault(t)

    case Unanswered(_)   => WireError.DependencyUnavailable(t)

    case Expired(c) =>
      val detail = problemOf(c)
      WireError.WorkspaceExpired(detail.cause, detail.lifetime, detail.idleLimit, t)

    case NotSupported(c) => WireError.NotSupported(problemOf(c), t)

  /** A refusal of the caller's content names its tree; one with no tree in scope
    * names none. A refusal that is one refused query keeps the engine's
    * structure, and a query runs only on a route carrying a tree, so a query
    * refusal with no tree in scope is this service's defect and answers the
    * opaque fault. */
  private def rejectedOnWire(
    errors: NonEmptyList[(ErrorLocation, FieldProblem)],
    t:      TraceId,
    scope:  TreeScope
  ): WireError =
    val asList = errors.toList.map((location, problem) => ValidationError(location, problem))
    (errors.toList, scope) match
      case (List((_, FieldProblem.Query(problem))), TreeScope.InTree(tree)) =>
        WireError.ValidationRejected(tree, RejectionDetail.Query(problem), t)
      case (List((_, FieldProblem.Query(_))), TreeScope.NoTree) =>
        WireError.InternalFault(t)
      case (_, TreeScope.InTree(tree)) =>
        WireError.ValidationRejected(tree, RejectionDetail.Fields(asList), t)
      case (_, TreeScope.NoTree) =>
        WireError.RequestRejected(asList, t)

  /** The problem half of the first entry, which is what a wire case naming one
    * problem carries. */
  private def problemOf[Z, P](core: Core[Z, P]): P = core.errors.head._2

  /** The log key the zone is recorded under, a contract with the log pipeline. */
  private val ZoneKey = "trust_zone"

  /** What every derived value reads from a failure, taken once per variant. */
  private final case class View(zone: TrustZone, entries: NonEmptyList[String], diagnostics: List[Diagnostic])

  private def view(f: Failure): View = f match
    case Rejected(c)     => View(c.zone, entries(c, ValidationMessages.render), c.diagnostics)
    case Unanswered(c)   => View(TrustZone.Peer(c.zone), entries(c, OutageMode.describe), c.diagnostics)
    case NotFound(c)     => View(c.zone, entries(c, NotFoundKind.describe), c.diagnostics)
    case Conflict(c)     => View(c.zone, entries(c, ConflictKind.describe), c.diagnostics)
    case AccessDenied(c) => View(c.zone, entries(c, DenialKind.describe), c.diagnostics)
    case Expired(c)      => View(c.zone, entries(c, ExpiryDetail.describe), c.diagnostics)
    case NotSupported(c) => View(c.zone, entries(c, Capability.describe), c.diagnostics)

  private def entries[Z, P](core: Core[Z, P], describe: P => String): NonEmptyList[String] =
    core.errors.map((location, problem) => s"[${ErrorLocation.describe(location)}] ${describe(problem)}")

  /** The zone's log value. Declared rather than derived from the case name,
    * because the log pipeline matches it exactly (ADR-037). */
  private def zoneValue(zone: TrustZone): String = zone match
    case TrustZone.Client                       => "client"
    case TrustZone.Environment                  => "environment"
    case TrustZone.MeshHeader                   => "mesh_header"
    case TrustZone.Peer(PeerZone.Store)         => "peer.store"
    case TrustZone.Peer(PeerZone.Database)      => "peer.database"
    case TrustZone.Peer(PeerZone.Authorization) => "peer.authorization"
    case TrustZone.Service                      => "service"
