package com.risquanter.register.auth

import zio.*
import zio.json.*
import zio.json.ast.Json
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
import com.risquanter.register.domain.errors.{AuthError, AuthForbidden, AuthServiceUnavailable}

// ─── SpiceDB REST API request/response types (file-private) ─────────────────
// Private to this file — not part of any public or package-level API surface.
// Named with SpiceDb prefix to avoid shadowing same-named types from other namespaces.

private case class SpiceDbObjectRef(objectType: String, objectId: String)
private given JsonEncoder[SpiceDbObjectRef] = DeriveJsonEncoder.gen

private case class SpiceDbSubjectRef(`object`: SpiceDbObjectRef)
private given JsonEncoder[SpiceDbSubjectRef] = DeriveJsonEncoder.gen

private case class SpiceDbCheckRequest(
  resource:    SpiceDbObjectRef,
  permission:  String,
  subject:     SpiceDbSubjectRef,
  consistency: Json
)
private given JsonEncoder[SpiceDbCheckRequest] = DeriveJsonEncoder.gen

private case class SpiceDbLookupRequest(
  resourceObjectType: String,
  permission:         String,
  subject:            SpiceDbSubjectRef,
  consistency:        Json
)
private given JsonEncoder[SpiceDbLookupRequest] = DeriveJsonEncoder.gen

private case class SpiceDbCheckResponse(permissionship: String)
private given JsonDecoder[SpiceDbCheckResponse] = DeriveJsonDecoder.gen

private case class SpiceDbLookupResultItem(resourceObjectId: String)
private given JsonDecoder[SpiceDbLookupResultItem] = DeriveJsonDecoder.gen

private case class SpiceDbLookupResponseLine(result: Option[SpiceDbLookupResultItem], error: Option[Json])
private given JsonDecoder[SpiceDbLookupResponseLine] = DeriveJsonDecoder.gen

// ─── Live SpiceDB HTTP adapter ───────────────────────────────────────────────

/** Live SpiceDB HTTP adapter for the authorization service.
  *
  * Implements AuthorizationService by calling SpiceDB's REST API (v1).
  * Fail-closed on all transport and SpiceDB-side errors: any failure mode
  * maps to AuthServiceUnavailable, which the HTTP error encoder maps to
  * 403 Forbidden — never 503 (which would reveal infrastructure state).
  *
  * Only `check()` and `listAccessible()` are implemented. This service
  * never writes authorization data — it is a pure Policy Enforcement Point.
  *
  * @see AUTHORIZATION-PLAN.md — Task L2.2
  * @see ADR-024: Externalized Authorization / PEP Pattern
  */
final class AuthorizationServiceSpiceDB private (
  config:       SpiceDbConfig,
  backend:      SttpBackend[Task, Any],
  tracing:      Tracing,
  checkCounter: Counter[Long],
  checkLatency: Histogram[Double]
) extends AuthorizationService:

  private val checkUri:  Uri = Uri.unsafeParse(config.url.value + "/v1/permissions/check")
  private val lookupUri: Uri = Uri.unsafeParse(config.url.value + "/v1/permissions/resources")

  private val timeout: FiniteDuration =
    FiniteDuration(config.timeoutSeconds.toLong, TimeUnit.SECONDS)

  private def consistencyJson: Json = config.consistency match
    case SpiceDbConsistency.MinimizeLatency => Json.Obj("minimizeLatency" -> Json.Obj())
    case SpiceDbConsistency.FullyConsistent => Json.Obj("fullyConsistent" -> Json.Bool(true))

  private def userSubject(user: UserId.Authenticated): SpiceDbSubjectRef =
    SpiceDbSubjectRef(`object` = SpiceDbObjectRef("user", user.value))

  // ─── check() ──────────────────────────────────────────────────────────────

  override def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]] =
    tracing.span("spicedb.check", SpanKind.CLIENT) {
      for
        // No userId in trace attributes — avoid PII in trace backends that
        // may not be access-controlled (AUTHORIZATION-PLAN.md §L2.2 Traces).
        _         <- tracing.setAttribute("permission",    permission.zedName)
        _         <- tracing.setAttribute("resource_type", resource.resourceType.zedType)
        _         <- tracing.setAttribute("resource_id",   resource.resourceId.value)
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
  ): IO[AuthError, Checked[P]] =
    val body = SpiceDbCheckRequest(
      resource    = SpiceDbObjectRef(resource.resourceType.zedType, resource.resourceId.value),
      permission  = permission.zedName,
      subject     = userSubject(user),
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
      .mapError(e =>
        AuthServiceUnavailable(s"SpiceDB check request failed: ${e.getMessage}", Some(e))
      )
      .flatMap { response =>
        if response.code.isSuccess then
          response.body.fromJson[SpiceDbCheckResponse] match
            case Right(SpiceDbCheckResponse("PERMISSIONSHIP_HAS_PERMISSION")) =>
              ZIO.succeed(Checked[P]())
            case Right(SpiceDbCheckResponse(_)) =>
              // PERMISSIONSHIP_NO_PERMISSION, PERMISSIONSHIP_UNSPECIFIED, and any
              // unknown value all map to explicit denial (fail-closed per §L2.2).
              ZIO.fail(AuthForbidden(
                userId       = user.value,
                permission   = permission.zedName,
                resourceType = resource.resourceType.zedType,
                resourceId   = resource.resourceId.value
              ))
            case Left(parseErr) =>
              ZIO.fail(AuthServiceUnavailable(
                s"SpiceDB check response parse error: $parseErr"
              ))
        else
          ZIO.fail(AuthServiceUnavailable(
            s"SpiceDB check returned HTTP ${response.code.code}"
          ))
      }

  // ─── listAccessible() ─────────────────────────────────────────────────────

  // Intentionally uninstrumented: AUTHORIZATION-PLAN §L2.2 Observability scopes
  // telemetry (span, authz.check.* metrics, audit log) to check() only — the
  // per-request allow/deny enforcement decision. listAccessible is a listing
  // convenience whose result is shown to the user; failures surface as
  // AuthServiceUnavailable and are visible in ordinary request logs/traces.
  override def listAccessible(
    user:         UserId.Authenticated,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]] =
    val body = SpiceDbLookupRequest(
      resourceObjectType = resourceType.zedType,
      permission         = permission.zedName,
      subject            = userSubject(user),
      consistency        = consistencyJson
    )
    basicRequest
      .post(lookupUri)
      .contentType("application/json")
      .auth.bearer(config.token.reveal)
      .body(body.toJson)
      .response(asStringAlways)
      .readTimeout(timeout)
      .send(backend)
      .mapError(e =>
        AuthServiceUnavailable(s"SpiceDB lookup request failed: ${e.getMessage}", Some(e))
      )
      .flatMap { response =>
        if response.code.isSuccess then
          ZIO.fromEither(parseLookupResponseLines(response.body))
            .mapError(msg => AuthServiceUnavailable(s"SpiceDB lookup response error: $msg"))
        else
          ZIO.fail(AuthServiceUnavailable(
            s"SpiceDB lookup returned HTTP ${response.code.code}"
          ))
      }

  // SpiceDB LookupResources API streams newline-delimited JSON objects.
  // Each non-empty line is a complete JSON object containing either a `result`
  // field or an `error` object (the gateway can report errors mid-stream on an
  // HTTP 200, e.g. deadline exceeded after partial results). Any error object,
  // unparseable line, or line with neither field fails the whole lookup —
  // returning partial results as success would mask incidents (fail-closed
  // per §L2.2). Result ids that fail SafeId refinement are skipped: they
  // belong to other tenants of the SpiceDB instance, not to this service.
  private def parseLookupResponseLines(body: String): Either[String, List[ResourceId]] =
    body.split('\n')
      .toList
      .filter(_.nonEmpty)
      .foldLeft[Either[String, List[ResourceId]]](Right(Nil)) { (acc, line) =>
        acc.flatMap { ids =>
          line.fromJson[SpiceDbLookupResponseLine] match
            case Right(SpiceDbLookupResponseLine(Some(SpiceDbLookupResultItem(id)), None)) =>
              Right(SafeId.fromString(id).toOption.fold(ids)(ids :+ _))
            case Right(SpiceDbLookupResponseLine(_, Some(err))) =>
              Left(s"error object in lookup stream: ${err.toJson}")
            case Right(SpiceDbLookupResponseLine(None, None)) =>
              Left("lookup stream line has neither result nor error")
            case Left(parseErr) =>
              Left(s"lookup stream parse error: $parseErr")
        }
      }

  // ─── Telemetry ────────────────────────────────────────────────────────────

  private def recordCheckMetrics[P <: Permission, A](
    outcome:    Either[AuthError, A],
    permission: P,
    resource:   ResourceRef,
    latencyMs:  Double
  ): UIO[Unit] =
    val resultLabel = outcome match
      case Right(_)                        => "allowed"
      case Left(_: AuthForbidden)          => "denied"
      case Left(_: AuthServiceUnavailable) => "error"
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
    outcome:    Either[AuthError, A],
    latencyMs:  Long
  ): UIO[Unit] =
    val resultStr = outcome match
      case Right(_)                         => "allowed"
      case Left(_: AuthForbidden)           => "denied"
      case Left(e: AuthServiceUnavailable)  => s"error reason=${e.reason}"
    // user.value — explicit PII opt-in; this is the designated audit log context.
    // user.toString would produce UserId(***) — PII is redacted for general logs.
    // @see AUTHORIZATION-PLAN.md §L2.2 Observability
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
    * @see AUTH-TESTING-PLAN.md §W3: T-U1 through T-U7
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
