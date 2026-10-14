package com.risquanter.register.telemetry

import zio.UIO
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.domain.data.iron.{TelemetryPlaceholders, TraceId}

/** The trace identifier of the active span.
  *
  * One definition, because three places need it: the wire boundary that stamps
  * a projected failure, the request interceptor that annotates the request's
  * log lines, and the catch-all that answers a failure reaching no endpoint.
  */
object ActiveTrace:

  /** Reads the identifier from whatever span is active on the current fibre.
    *
    * Reading it requires an effect because the OpenTelemetry context lives in a
    * fibre reference rather than a thread-local, which is what lets it survive
    * the async boundaries of a work-stealing executor.
    *
    * With no active span OpenTelemetry returns its invalid span context, whose
    * identifier is thirty-two zero characters — the value
    * `TelemetryPlaceholders.UnlinkedTrace` holds. So work outside any span names
    * an unlinked trace rather than a plausible identifier that resolves to
    * nothing, and no branch is written to produce that: it is what the
    * mechanism already yields.
    */
  def id(using tracing: Tracing): UIO[TraceId] =
    tracing.getCurrentSpanContextUnsafe.map { ctx =>
      TraceId.fromString(ctx.getTraceId).toOption.getOrElse(TelemetryPlaceholders.UnlinkedTrace)
    }
