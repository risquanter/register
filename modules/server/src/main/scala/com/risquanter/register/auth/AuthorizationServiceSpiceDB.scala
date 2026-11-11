package com.risquanter.register.auth

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.prelude.NonEmptyList
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Counter, Histogram}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.opentelemetry.api.trace.SpanKind
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import com.risquanter.register.configs.{SpiceDbConfig, SpiceDbConsistency}
import com.risquanter.register.domain.data.iron.{UserId, SafeId}
import com.risquanter.register.domain.errors.{
  Core, DenialKind, Diagnostic, ErrorLocation, ExpectedInput, Failure, FieldProblem, OutageMode, PeerZone,
  TrustZone, ValidationError
}

// ─── SpiceDB REST API request/response types (package-private) ───────────────
// Top-level `private` = visible within the `auth` package only — not part of any
// public API surface. SpiceDbObjectRef / SpiceDbSubjectRef and their encoders are
// shared with BootstrapProvisionerSpiceDB.scala (WriteRelationships request body).
// Named with SpiceDb prefix to avoid shadowing same-named types from other namespaces.

/** What a SpiceDB request names: one of this application's resources, or a
  * user. The encoder writes each as the Zed definition and the object id, in
  * the spellings the schema fixes. */
private enum SpiceDbObjectRef:
  case Resource(resourceType: ResourceType, id: SafeId.SafeId)
  case User(id: UserId.Authenticated)

private object SpiceDbObjectRef:
  /** The Zed `definition` every user belongs to. Spelling fixed by the Zed
    * schema in `infra/spicedb/schema.zed`. */
  val UserDefinition: String = "user"

  /** The two field names of a SpiceDB v1 object reference. */
  private val TypeField = "objectType"
  private val IdField   = "objectId"

  given JsonEncoder[SpiceDbObjectRef] = JsonEncoder[Json].contramap {
    case Resource(resourceType, id) => objectJson(resourceType.zedType, id.value)
    case User(id)                   => objectJson(UserDefinition, id.value)
  }

  private def objectJson(definition: String, id: String): Json =
    Json.Obj(TypeField -> Json.Str(definition), IdField -> Json.Str(id))

private case class SpiceDbSubjectRef(`object`: SpiceDbObjectRef)
private given JsonEncoder[SpiceDbSubjectRef] = DeriveJsonEncoder.gen

private case class SpiceDbCheckRequest(
  resource:    SpiceDbObjectRef,
  permission:  Permission,
  subject:     SpiceDbSubjectRef,
  consistency: Json
)
private given JsonEncoder[SpiceDbCheckRequest] = DeriveJsonEncoder.gen

/** SpiceDB's answer to a check. The spellings are the ones the SpiceDB v1 API
  * fixes for `CheckPermissionResponse.Permissionship`. */
private enum Permissionship(val wire: String):
  case Unspecified           extends Permissionship("PERMISSIONSHIP_UNSPECIFIED")
  case NoPermission          extends Permissionship("PERMISSIONSHIP_NO_PERMISSION")
  case HasPermission         extends Permissionship("PERMISSIONSHIP_HAS_PERMISSION")
  case ConditionalPermission extends Permissionship("PERMISSIONSHIP_CONDITIONAL_PERMISSION")

private object Permissionship:
  /** The answer this spelling names, or `None` for a spelling outside the set. */
  def fromWire(text: String): Option[Permissionship] = values.find(_.wire == text)

/** A check response as SpiceDB's answer carries it, before the permissionship
  * is refined. */
private case class SpiceDbCheckResponse(permissionship: String)
private given JsonDecoder[SpiceDbCheckResponse] = DeriveJsonDecoder.gen

/** SpiceDB did not answer, in the given way. Shared by every SpiceDB adapter
  * in this package: no authorization decision was obtained, so the request
  * fails closed as a dependency outage. */
private def authorizationOutage(mode: OutageMode, diagnostics: List[Diagnostic]): Failure =
  Failure.Unanswered(Core(PeerZone.Authorization, NonEmptyList((ErrorLocation.Whole, mode)), diagnostics))

/** SpiceDB answered with content this service refuses (ADR-039): a body that
  * is not the check response, or a permissionship outside the set the API
  * fixes. No authorization decision was obtained, so the request fails closed. */
private def authorizationRefusal(expected: ExpectedInput, diagnostics: List[Diagnostic]): Failure =
  Failure.rejected(
    TrustZone.Peer(PeerZone.Authorization),
    NonEmptyList(ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(expected))),
    diagnostics
  )

/** SpiceDB answered that `user` does not hold `permission` on `resource`. */
private def permissionNotHeld(user: UserId.Authenticated, permission: Permission, resource: ResourceRef): Failure =
  Failure.AccessDenied(Core(TrustZone.Peer(PeerZone.Authorization),
    NonEmptyList(ErrorLocation.Whole -> DenialKind.PermissionNotHeld),
    List(Diagnostic.Principal(user, permission, resource))))

/** A transport exception's own text, for the log, when it has one. */
private def peerMessage(e: Throwable): List[Diagnostic] =
  Option(e.getMessage).map(Diagnostic.PeerMessage(_)).toList

// ─── Live SpiceDB HTTP adapter ───────────────────────────────────────────────

/** Live SpiceDB HTTP adapter for the authorization service.
  *
  * Implements AuthorizationService by calling SpiceDB's REST API (v1).
  * Fail-closed on all transport and SpiceDB-side errors: a request SpiceDB did
  * not answer is an `Unanswered` at the authorization zone, which the wire
  * projection answers as a dependency outage, and an answer outside the
  * protocol is a `Rejected` there. No authorization decision was obtained in
  * either case, so none is assumed, and the caller is told a service did not
  * answer rather than that access was refused.
  *
  * Only `check()` is implemented. This service never writes authorization
  * data — it is a pure Policy Enforcement Point.
  *
  * @see ADR-024: Externalized Authorization / PEP Pattern
  */
final class AuthorizationServiceSpiceDB private (
  config:       SpiceDbConfig,
  backend:      SttpBackend[Task, Any],
  tracing:      Tracing,
  checkCounter: Counter[Long],
  checkLatency: Histogram[Double]
) extends AuthorizationService:

  private val checkUri: Uri = Uri.unsafeParse(config.url.value + "/v1/permissions/check")

  private val timeout: FiniteDuration =
    FiniteDuration(config.timeoutSeconds.toLong, TimeUnit.SECONDS)

  private def consistencyJson: Json = config.consistency match
    case SpiceDbConsistency.MinimizeLatency => Json.Obj("minimizeLatency" -> Json.Obj())
    case SpiceDbConsistency.FullyConsistent => Json.Obj("fullyConsistent" -> Json.Bool(true))

  // ─── check() ──────────────────────────────────────────────────────────────

  override def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[Failure, Checked[P]] =
    tracing.span("spicedb.check", SpanKind.CLIENT) {
      for
        // No userId in trace attributes — avoid PII in trace backends that
        // may not be access-controlled.
        _         <- tracing.setAttribute("permission",    permission.zedName)
        _         <- tracing.setAttribute("resource_type", resource.resourceType.zedType)
        // Only a tree's identifier is recorded. A workspace, organization or
        // team identifier is a confidential internal identifier and must not
        // reach an exported span attribute (ADR-036).
        _         <- resource.resourceType match
                       case ResourceType.RiskTree =>
                         tracing.setAttribute("resource_id", resource.resourceId.value)
                       case ResourceType.Workspace | ResourceType.Organization | ResourceType.Team =>
                         ZIO.unit
        startMs   <- Clock.currentTime(TimeUnit.MILLISECONDS)
        outcome   <- sendCheckRequest(user, permission, resource).either
        endMs     <- Clock.currentTime(TimeUnit.MILLISECONDS)
        latencyMs  = (endMs - startMs).toDouble
        _         <- recordCheckMetrics(outcome, permission, resource, latencyMs)
        _         <- logCheckResult(user, permission, resource, outcome, endMs - startMs)
        result    <- ZIO.fromEither(outcome)
      yield result
    }

  private def sendCheckRequest[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[Failure, Checked[P]] =
    val body = SpiceDbCheckRequest(
      resource    = SpiceDbObjectRef.Resource(resource.resourceType, resource.resourceId),
      permission  = permission,
      subject     = SpiceDbSubjectRef(`object` = SpiceDbObjectRef.User(user)),
      consistency = consistencyJson
    )
    basicRequest
      .post(checkUri)
      .contentType("application/json")
      .auth.bearer(config.token.reveal)
      .body(body.toJson)
      .response(asStringAlways)
      .readTimeout(timeout)
      .send(backend)
      .mapError(e => authorizationOutage(OutageMode.ConnectionRefused, peerMessage(e)))
      .flatMap { response =>
        if response.code.isSuccess then
          ZIO.fromEither(refineAnswer(response.body)).flatMap {
            case Permissionship.HasPermission =>
              ZIO.succeed(Checked[P]())
            case Permissionship.NoPermission | Permissionship.Unspecified | Permissionship.ConditionalPermission =>
              // Anything but a plain grant is an explicit denial (fail-closed):
              // this service evaluates no caveats, so a conditional answer
              // grants nothing.
              ZIO.fail(permissionNotHeld(user, permission, resource))
          }
        else
          ZIO.fail(authorizationOutage(OutageMode.Status(response.code), Nil))
      }

  /** Refines the check response where SpiceDB's answer arrives (ADR-039): a
    * body that is not the response is refused as JSON, and a permissionship
    * outside the API's set is refused as the answer the decoder declares, kept
    * for the log. */
  private def refineAnswer(body: String): Either[Failure, Permissionship] =
    body.fromJson[SpiceDbCheckResponse] match
      case Left(parseErr) =>
        Left(authorizationRefusal(ExpectedInput.Json, List(Diagnostic.PeerMessage(parseErr))))
      case Right(SpiceDbCheckResponse(text)) =>
        Permissionship.fromWire(text)
          .toRight(authorizationRefusal(ExpectedInput.Declared, List(Diagnostic.RawValue(text))))

  // ─── Telemetry ────────────────────────────────────────────────────────────

  private def recordCheckMetrics[P <: Permission, A](
    outcome:    Either[Failure, A],
    permission: P,
    resource:   ResourceRef,
    latencyMs:  Double
  ): UIO[Unit] =
    val resultLabel = outcome match
      case Right(_)                       => "allowed"
      case Left(Failure.AccessDenied(_))  => "denied"
      case Left(_)                        => "error"
    val attrs = Attributes(
      Attribute.string("result",        resultLabel),
      Attribute.string("permission",    permission.zedName),
      Attribute.string("resource_type", resource.resourceType.zedType)
    )
    checkCounter.add(1L, attrs) *> checkLatency.record(latencyMs, attrs)

  private def logCheckResult[P <: Permission, A](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef,
    outcome:    Either[Failure, A],
    latencyMs:  Long
  ): UIO[Unit] =
    val resultStr = outcome match
      case Right(_)                       => "allowed"
      case Left(Failure.AccessDenied(_))  => "denied"
      case Left(f)                        => s"error reason=${f.logMessage}"
    // user.value — explicit PII opt-in; this is the designated audit log context.
    // user.toString would produce UserId(***) — PII is redacted for general logs.
    ZIO.logInfo(
      s"authz.check user=${user.value} " +
      s"permission=${permission.zedName} " +
      s"resource=${resource.resourceType.zedType}:${resource.resourceId.value} " +
      s"result=$resultStr latency_ms=$latencyMs"
    )

// ─── Companion object ────────────────────────────────────────────────────────

object AuthorizationServiceSpiceDB:

  private object MetricNames:
    val checkCounter     = "authz.check.total"
    val checkCounterUnit = "1"
    val checkCounterDesc = "Authorization check results"
    val checkLatency     = "authz.check.latency_ms"
    val checkLatencyUnit = "ms"
    val checkLatencyDesc = "SpiceDB authorization check latency in milliseconds"

  /** Layer with injectable SttpBackend — use SttpBackendStub in unit tests.
    *
    * Provides deterministic control over SpiceDB HTTP responses without a live
    * SpiceDB instance.
    *
    */
  val layer: ZLayer[SpiceDbConfig & SttpBackend[Task, Any] & Tracing & Meter, Throwable, AuthorizationService] =
    ZLayer {
      for
        config   <- ZIO.service[SpiceDbConfig]
        backend  <- ZIO.service[SttpBackend[Task, Any]]
        tracing  <- ZIO.service[Tracing]
        meter    <- ZIO.service[Meter]
        counter  <- meter.counter(MetricNames.checkCounter, Some(MetricNames.checkCounterUnit), Some(MetricNames.checkCounterDesc))
        latency  <- meter.histogram(MetricNames.checkLatency, Some(MetricNames.checkLatencyUnit), Some(MetricNames.checkLatencyDesc))
      yield new AuthorizationServiceSpiceDB(config, backend, tracing, counter, latency)
    }

  /** Production layer — creates and manages its own HTTP client lifecycle.
    *
    * The HTTP backend is scoped to this layer's lifetime (application shutdown).
    * Use [[layer]] with a SttpBackendStub for unit tests.
    */
  val liveLayer: ZLayer[SpiceDbConfig & Tracing & Meter, Throwable, AuthorizationService] =
    ZLayer.scoped {
      for
        config   <- ZIO.service[SpiceDbConfig]
        tracing  <- ZIO.service[Tracing]
        meter    <- ZIO.service[Meter]
        backend  <- HttpClientZioBackend.scoped()
        counter  <- meter.counter(MetricNames.checkCounter, Some(MetricNames.checkCounterUnit), Some(MetricNames.checkCounterDesc))
        latency  <- meter.histogram(MetricNames.checkLatency, Some(MetricNames.checkLatencyUnit), Some(MetricNames.checkLatencyDesc))
      yield new AuthorizationServiceSpiceDB(config, backend, tracing, counter, latency)
    }
