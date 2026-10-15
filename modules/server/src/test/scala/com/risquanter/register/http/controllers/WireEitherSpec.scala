package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind

import java.time.Instant
import java.time.Duration as JDuration

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{SafeId, TelemetryPlaceholders, WorkspaceId}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, WorkspaceExpired}
import com.risquanter.register.telemetry.TelemetryLive

/** The trace identifier a wire failure carries names the span the failing work
  * ran under.
  *
  * That is the property which makes the identifier worth showing a user: a
  * well-typed value that corresponded to no span would read as a trace and
  * resolve to nothing. It cannot be checked by a type, because the identifier is
  * read from a fibre reference at the moment the projection happens, so it is
  * checked here.
  *
  * The complementary case is also pinned. Work that belongs to no span reads
  * OpenTelemetry's invalid span context and so names the unlinked trace, which
  * is the correct answer rather than a defect. Inside a span the identifier is
  * never that placeholder, so a placeholder seen on a request path means the
  * request was served without the span the interceptor is there to open.
  */
object WireEitherSpec extends ZIOSpecDefault:

  /** The projection reads nothing from the failure's content, so any `AppError`
    * exercises it; this one is the cheapest to construct. */
  private val failure: AppError = RepositoryFailure("a failure for the boundary to project")

  private val failing: IO[AppError, Nothing] = ZIO.fail(failure)

  private val wsId: WorkspaceId =
    WorkspaceId(SafeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get)

  /** A caller-caused failure that carries structured fields, so one test covers
    * both the level and the annotations. The two windows are the configured
    * defaults and the instant only has to be a recognisable value.
    */
  private val expired: AppError =
    WorkspaceExpired(wsId, JDuration.ofHours(72), JDuration.ofHours(1), ExpiryCause.Inactivity, Instant.EPOCH)

  /** The projection's log line for a failure, or `None` if it wrote none. */
  private def lineFor(error: AppError)(using Tracing): UIO[Option[ZTestLogger.LogEntry]] =
    ZIO.fail(error).wireEither *>
      ZTestLogger.logOutput.map(_.find(_.message() == error.logMessage))

  /** Names the span these tests open. It is never exported — no collector runs
    * during a test — so it only has to be recognisable in a failure message. */
  private val SpanName = "wire-either-boundary"

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  override def spec = suite("WireEitherSpec")(

    test("the identifier a wire failure carries is the identifier of the span it ran under") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            context   <- tracing.getCurrentSpanContextUnsafe
            projected <- failing.wireEither
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
          failing.wireEither.map { projected =>
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

        failing.wireEither.map { projected =>
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
          lineFor(failure).map(line =>
            assertTrue(line.map(_.logLevel).contains(LogLevel.Warning))
          )
        }
      }
    },

    test("a caller-caused failure is logged below WARN, and visible at the default level") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          lineFor(expired).map(line =>
            assertTrue(
              line.map(_.logLevel).contains(LogLevel.Info),
              line.exists(_.logLevel < LogLevel.Warning)
            )
          )
        }
      }
    },

    test("the line carries the failure's own annotations and the trace the response names") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        given Tracing = tracing

        tracing.span(SpanName, SpanKind.SERVER) {
          for
            context <- tracing.getCurrentSpanContextUnsafe
            line    <- lineFor(expired)
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
    }

  ).provideLayerShared(telemetry)

end WireEitherSpec
