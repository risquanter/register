package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.iron.TelemetryPlaceholders
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure}
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
    }

  ).provideLayerShared(telemetry)

end WireEitherSpec
