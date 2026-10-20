package com.risquanter.register.http.controllers

import zio.*
import zio.json.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind

import java.time.Instant
import java.time.Duration as JDuration

import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError as SttpMonadError
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.server.ziohttp.ZioHttpServerOptions
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{SafeId, TelemetryPlaceholders, TraceId, WorkspaceId}
import com.risquanter.register.domain.errors.{AppError, AuthServiceUnavailable, RepositoryFailure, WorkspaceExpired}
import com.risquanter.register.http.RequestSpanInterceptor
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.{TreeScope, WireError}
import com.risquanter.register.telemetry.TelemetryLive

/** Two properties of the boundary where a domain failure becomes a response: the
  * trace identifier the response carries, and the one log line the failure is
  * recorded with.
  *
  * **The trace.** The identifier a wire failure carries names the span the
  * failing work ran under. That is the property which makes the identifier worth
  * showing a user: a well-typed value that corresponded to no span would read as
  * a trace and resolve to nothing. It cannot be checked by a type, because the
  * identifier is read from a fibre reference at the moment the projection
  * happens, so it is checked here.
  *
  * The complementary case is also pinned. Work that belongs to no span reads
  * OpenTelemetry's invalid span context and so names the unlinked trace, which
  * is the correct answer rather than a defect. Inside a span the identifier is
  * never that placeholder, so a placeholder seen on a request path means the
  * request was served without the span the interceptor is there to open.
  *
  * **The log line.** Its level comes from the failure's own origin, and the line
  * carries the failure's structured fields plus the same trace the response
  * names, so a failure a caller reports can be found in the log. The last test
  * drives a real request through the span interceptor and an endpoint, because
  * every other test here calls the projection directly and so cannot show that a
  * served request reaches it.
  */
object WireEitherSpec extends ZIOSpecDefault with BaseEndpoint:

  private given SttpMonadError[Task] = new RIOMonadError[Any]

  /** The projection reads nothing from the failure's content, so any `AppError`
    * exercises it; this one is the cheapest to construct. */
  private val failure: AppError = RepositoryFailure("a failure for the boundary to project")

  private val failing: IO[AppError, Nothing] = ZIO.fail(failure)

  /** A service-caused failure used by the level test alone. Its message is its
    * own, so the line this test reads can only be the line this test wrote. */
  private val serviceFailure: AppError = RepositoryFailure("a service failure for the level test")

  /** An unreachable authorization service.
    *
    * Pinned separately because its level is the one a contributor is most
    * likely to get wrong: the response says only that a dependency did not
    * answer, naming none, so nothing in what the caller sees indicates that
    * this failure is the service's own. The level is read from the internal
    * failure, which does.
    */
  private val authOutage: AppError =
    AuthServiceUnavailable("the authorization service did not answer", None)

  private val wsId: WorkspaceId =
    WorkspaceId(SafeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get)

  /** A caller-caused failure that carries structured fields, so one test covers
    * both the level and the annotations. The two windows are the configured
    * defaults and the instant only has to be a recognisable value.
    */
  private val expired: AppError =
    WorkspaceExpired(wsId, JDuration.ofHours(72), JDuration.ofHours(1), ExpiryCause.Inactivity, Instant.EPOCH)

  /** The wire failure the boundary projects `error` onto. This is also where a
    * test reads the trace the response would carry. */
  private def wireFailure(error: AppError)(using Tracing): UIO[Option[WireError]] =
    ZIO.fail(error).wireEither(TreeScope.NoTree).map(_.left.toOption)

  /** The boundary's log line for `error` under `trace`, or `None` if it wrote
    * none.
    *
    * The lookup is scoped by the trace as well as by the message, because the
    * identifier belongs to the single span the caller opened. A line another
    * test wrote therefore cannot answer for this one, whichever way the test
    * framework scopes the logger it collects from.
    */
  private def lineFor(error: AppError, trace: Option[TraceId]): UIO[Option[ZTestLogger.LogEntry]] =
    ZTestLogger.logOutput.map(_.find { entry =>
      entry.message() == error.logMessage &&
      trace.exists(t => entry.annotations.get("trace_id").contains(t.value))
    })

  /** Names the span these tests open. It is never exported — no collector runs
    * during a test — so it only has to be recognisable in a failure message. */
  private val SpanName = "wire-either-boundary"

  /** The path the served endpoint answers on. One segment, so the span the
    * interceptor names involves no capability key. */
  private val RequestPath = "probe"

  /** An endpoint whose logic always fails with `error` and ends in the
    * projection, which is how every controller method ends. */
  private def failingEndpoint(error: AppError)(using Tracing): ServerEndpoint[Any, Task] =
    baseEndpoint.get
      .in(RequestPath)
      .out(stringBody)
      .serverLogic[Task](_ => ZIO.fail(error).wireEither(TreeScope.NoTree))

  /** Serves one request to that endpoint through the span interceptor, so the
    * projection runs where it runs in production: inside the request's span. */
  private def requestFailing(error: AppError)(using tracing: Tracing): Task[Response[Either[String, String]]] =
    val backend = TapirStubInterpreter(
      ZioHttpServerOptions
        .customiseInterceptors[Any]
        .prependInterceptor(RequestSpanInterceptor.interceptor(tracing)),
      SttpBackendStub[Task, Any](summon[SttpMonadError[Task]])
    ).whenServerEndpointRunLogic(failingEndpoint(error)).backend()

    basicRequest.get(uri"http://localhost/probe").send(backend)

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  override def spec = suite("WireEitherSpec")(

    test("the identifier a wire failure carries is the identifier of the span it ran under") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            context   <- tracing.getCurrentSpanContextUnsafe
            projected <- failing.wireEither(TreeScope.NoTree)
          yield assertTrue(
            projected.left.toOption.map(_.trace.value).contains(context.getTraceId)
          )
        }
      }
    },

    test("a failure inside a span never names the unlinked trace") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          failing.wireEither(TreeScope.NoTree).map { projected =>
            assertTrue(
              projected.left.toOption.exists(_.trace != TelemetryPlaceholders.UnlinkedTrace)
            )
          }
        }
      }
    },

    test("a failure outside any span names the unlinked trace") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        failing.wireEither(TreeScope.NoTree).map { projected =>
          assertTrue(
            projected.left.toOption.map(_.trace).contains(TelemetryPlaceholders.UnlinkedTrace)
          )
        }
      }
    },

    test("a service-caused failure is logged at WARN") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            projected <- wireFailure(serviceFailure)
            line      <- lineFor(serviceFailure, projected.map(_.trace))
          yield assertTrue(line.map(_.logLevel).contains(LogLevel.Warning))
        }
      }
    },

    test("an unreachable authorization service is logged as the service failure it is") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            projected <- wireFailure(authOutage)
            line      <- lineFor(authOutage, projected.map(_.trace))
          yield assertTrue(
            // The outage is this service's own, so the line is an operational
            // signal even though the caller is told only that a dependency did
            // not answer.
            line.map(_.logLevel).contains(LogLevel.Warning)
          )
        }
      }
    },

    test("a caller-caused failure is logged below WARN, and visible at the default level") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            projected <- wireFailure(expired)
            line      <- lineFor(expired, projected.map(_.trace))
          yield assertTrue(
            line.map(_.logLevel).contains(LogLevel.Info),
            line.exists(_.logLevel < LogLevel.Warning)
          )
        }
      }
    },

    test("the line carries the failure's own annotations and the trace the response names") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            context   <- tracing.getCurrentSpanContextUnsafe
            projected <- wireFailure(expired)
            line      <- lineFor(expired, projected.map(_.trace))
          yield assertTrue(
            // A confidential internal identifier may be named in a server log.
            line.flatMap(_.annotations.get("workspace_id")).contains(wsId.value),
            line.flatMap(_.annotations.get("expiry_cause")).contains(ExpiryCause.Inactivity.toString),
            line.exists(_.annotations.contains("expired_at")),
            // The same identifier the caller received, so the two can be joined.
            line.flatMap(_.annotations.get("trace_id")).contains(context.getTraceId)
          )
        }
      }
    },

    test("a served request that fails is logged with the trace its response carries") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        for
          response <- requestFailing(expired)
          body      = response.body.left.toOption.flatMap(_.fromJson[WireError.WorkspaceExpired].toOption)
          line     <- lineFor(expired, body.map(_.trace))
        yield assertTrue(
          // The endpoint's own declared output for this failure, not the
          // catch-all's, which answers 500 for anything reaching no output.
          response.code.code == 410,
          body.isDefined,
          // The interceptor holds a span open around the handler, so the trace
          // the caller is given is a real span's.
          !body.map(_.trace).contains(TelemetryPlaceholders.UnlinkedTrace),
          // Found by the identifier the response itself names, which is the
          // join a caller reporting a failure has to be able to make.
          line.map(_.logLevel).contains(LogLevel.Info)
        )
      }
    }

  ).provideLayerShared(telemetry)

end WireEitherSpec
