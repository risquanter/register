# Plan: make telemetry reach a backend, selected by configuration

**Status:** Ruled, awaiting approval as the session's governing plan.
**Date:** 2026-09-13.
**ADR reference:** ADR-002 (logging strategy — the sibling signal, unchanged
here); ADR-016 (configuration management — how a new setting is declared and
overridden).

One configuration field, one layer selection in the application's wiring, and
the removal of a duplicated SDK. No new dependency: both exporters and both
layers already exist and are already compiled.

---

## Objective

The project records metrics and traces that nothing can read.

`MetricsLive` and `TracingLive` build an OpenTelemetry SDK and expose a `Meter`
and a `Tracing` service. Three subsystems use them: `CachedResultResolverLive`
records simulation duration and a trial counter, `RiskTreeServiceLive` records
an operations counter, and `AuthorizationServiceSpiceDB` records its own counter
and histogram. All of that instrumentation works.

`Application.scala` wires the **console** exporter variants, and carries a
comment saying what that means:

> Current setup: LoggingSpanExporter & LoggingMetricExporter configured.
> NOTE: Console exporters produce no visible output in application logs (likely
> log at DEBUG/FINE level filtered by default log config).
> TODO: Configure log level or switch to `TracingLive.otlp` & `MetricsLive.otlp`
> for actual telemetry export to otel-collector.

So every recorded measurement goes into an exporter whose output is filtered
away. The compose stack already runs an OpenTelemetry collector under the
`observability` profile, receiving OTLP on ports 4317 and 4318 and exposing
Prometheus metrics on 8889. The application never sends it anything;
`docker-compose.yml` even carries the endpoint commented out with the note
"Unused with console exporters".

This matters now because the concurrency work adds a saturation gauge whose
entire purpose is to be watched while tuning concurrency limits. A gauge that
cannot be read does not do that job.

**A second defect surfaces on the same lines.** `TelemetryLive` provides
combined layers, `TelemetryLive.console` and `TelemetryLive.otlp`, each building
**one** SDK serving both traces and metrics. Its scaladoc states the reason
plainly: *"This is preferred over using TracingLive.console + MetricsLive.console
separately, as it uses a single SDK instance."* `Application.scala` uses the two
separate layers, so the running process builds **two** OpenTelemetry SDKs, each
with its own resource attributes and its own export loop. The fix is the same
edit that fixes the exporter selection, so it is part of this plan rather than a
follow-up.

---

## Background — the pieces, in plain terms

**OpenTelemetry** is the vendor-neutral standard for emitting telemetry.
Application code talks to its API; a configurable *exporter* decides where the
data goes.

**An exporter** is the component that ships recorded data somewhere. Two are in
play. The **logging exporter** writes measurements through the Java logging
framework — intended for local debugging, and in this application filtered out
before it becomes visible. The **OTLP exporter** sends data over OpenTelemetry
Protocol to a collector, over gRPC on port 4317.

**A collector** receives telemetry from applications and forwards it to
backends. The compose stack runs one, configured to expose what it receives in
Prometheus format on port 8889, so `curl` is enough to read it.

**A meter** is the object instruments are created from — counters, histograms,
gauges. `MetricsLive` provides it as a ZIO layer, so any service can request one
by naming `Meter` in its layer requirements.

Putting those together: the instruments exist, the meter exists, the collector
exists, and the only missing link is which exporter the SDK is built with.

---

## Exact signatures

### `TelemetryConfig` gains one field

In
`modules/common/src/main/scala/com/risquanter/register/configs/TelemetryConfig.scala`:

```scala
/** Where telemetry is sent. `Console` writes through the logging framework and
  * is filtered out by the default log configuration; `Otlp` sends to the
  * collector at `otlpEndpoint`. */
enum TelemetryExporter:
  case Console, Otlp

object TelemetryExporter:
  def fromString(s: String): Either[String, TelemetryExporter]
```

```scala
final case class TelemetryConfig(
  serviceName:               String,
  instrumentationScope:      String,
  exporter:                  TelemetryExporter,
  otlpEndpoint:              Url.Url,
  devExportIntervalSeconds:  Int,
  prodExportIntervalSeconds: Int
)
```

`exporter` is placed before `otlpEndpoint` because it decides whether that
endpoint is used at all. A `DeriveConfig[TelemetryExporter]` given is added
alongside the existing `DeriveConfig[Url.Url]`, following the same
`Config.string.mapOrFail` shape already in the companion.

### `TelemetryLive` gains one selector

No existing layer changes. One addition, in
`modules/server/src/main/scala/com/risquanter/register/telemetry/TelemetryLive.scala`:

```scala
  /** The combined Tracing + Meter layer for the configured exporter. */
  val configured: ZLayer[TelemetryConfig, Throwable, Tracing & Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      configEnv.get.exporter match
        case TelemetryExporter.Console => console
        case TelemetryExporter.Otlp    => otlp
    }
```

### `Application.scala`

The two separate console layers are replaced by the one configured layer:

```scala
      // Telemetry — one SDK serving both traces and metrics, exporter from config
      TelemetryLive.configured,
```

replacing

```scala
      TracingLive.console,
      MetricsLive.console,
```

together with the five-line comment describing the console limitation, which
stops being true.

### `application.conf`

```hocon
  telemetry {
    serviceName = "risk-register"
    serviceName = ${?OTEL_SERVICE_NAME}
    instrumentationScope = "com.risquanter.register"
    exporter = "otlp"
    exporter = ${?REGISTER_TELEMETRY_EXPORTER}
    otlpEndpoint = "http://localhost:4317"
    otlpEndpoint = ${?OTEL_EXPORTER_OTLP_ENDPOINT}
    devExportIntervalSeconds = 5
    prodExportIntervalSeconds = 60
  }
```

**The default is `otlp`, and that choice needs its reasoning stated.** The OTLP
exporter does not fail when no collector is listening — it retries in the
background and drops data, so a developer running without the observability
profile sees nothing worse than today, which is also nothing. Defaulting to
`console` would instead preserve the current situation as the default and leave
the visible path opt-in, which is what this plan exists to end.

---

## What `TracingLive` and `MetricsLive` are for after this

Both keep their layers. They are used by tests that need one signal without the
other, and they remain the tracing-only and metrics-only entry points. Only the
application's own wiring stops using them, because the application needs both
and should build one SDK rather than two.

---

## File inventory

- `modules/common/src/main/scala/com/risquanter/register/configs/TelemetryConfig.scala` — the `TelemetryExporter` enum, the new field, the `DeriveConfig` given
- `modules/server/src/main/scala/com/risquanter/register/telemetry/TelemetryLive.scala` — the `configured` selector layer
- `modules/server/src/main/scala/com/risquanter/register/Application.scala` — the layer swap and the comment that stops being true
- `modules/server/src/main/resources/application.conf` — the `exporter` setting and its environment override
- `modules/common/src/test/scala/com/risquanter/register/configs/TelemetryConfigSpec.scala` — new — the exporter field parses from configuration and rejects an unknown value
- `docker-compose.yml` — uncomment `OTEL_EXPORTER_OTLP_ENDPOINT` and remove the "Unused with console exporters" note
- `docs/user/DOCKER-DEVELOPMENT.md` — how to read metrics: run the observability profile, curl port 8889
- `docs/dev/ARCHITECTURE.md` — the observability section currently states telemetry is "fully integrated via TelemetryLive (console + OTLP exporters)", which reads as though export works; it is corrected to say which exporter is default and how to change it

Any test that provides `TelemetryConfig` by constructing it directly gains the
new field. Adding a field to a case class is a compile error at every
construction site, so the compiler produces that list exhaustively; sites found
that way are added to this inventory before being edited.

---

## ADR alignment

- **ADR-016 (configuration management).** The new setting follows the
  established pattern exactly: a typed field on the config case class, a default
  in `application.conf`, an environment variable override, and a derived
  `Config` instance in the companion. Conforms.
- **ADR-002 (logging strategy).** Untouched. Logging is a separate signal with
  its own pipeline; this plan changes only where metrics and traces go.
- **ADR-031 (startup readiness).** The OTLP exporter is not a startup
  dependency: it does not block layer construction and does not fail when the
  collector is absent. No readiness gate is added, and none is needed. Conforms.
- **Decision Trigger review.** Trigger 4 (existing signatures) fires on
  `TelemetryConfig`, whose shape changes. The new shape is written out above,
  which is what the trigger requires. No endpoint, DTO or wire format changes.

---

## Verification plan

### Configuration parsing

A unit test in `commonJVM` asserts the exporter field parses from configuration
and that an unknown value is rejected rather than silently defaulted. A
mistyped exporter name should fail at startup, not quietly produce the wrong
one.

### Metrics actually arrive — the point of the plan

```bash
docker compose --profile persistence --profile frontend --profile observability \
  --env-file .env.irmin up -d
```

Exercise the application so the instrumented paths run — create a tree and
request an analysis, which drives the simulation duration histogram and the
trials counter. Then:

```bash
curl -s http://localhost:8889/metrics | grep risk_result
```

The acceptance condition is that `risk_result_simulation_duration_ms` and
`risk_result_simulation_trials` appear with non-zero values. Metric names are
dotted in code and appear with underscores in Prometheus format, which is the
standard translation and not a defect.

### One SDK, not two

Start the application and confirm from the startup logs that a single
OpenTelemetry SDK is constructed. This is the check for the duplicated-SDK half
of the change.

### Console still works

Set `REGISTER_TELEMETRY_EXPORTER=console`, start the application, and confirm it
starts and serves. This proves the selector works in both directions rather than
only on the default path.

### Full suite

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

Then, after the mandatory leaked-network cleanup:

```bash
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f; docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm; echo "--- remaining register_it_ networks ---"; docker network ls --filter name=register_it_ --format '{{.Name}}' | wc -l

sbt "serverIt/test"
```

All four tiers green is the acceptance condition. Report pass or fail only.

---

## Sequencing

**This plan lands before `PLAN-SIMULATION-CONCURRENCY-BOUNDS`.** The concurrency
plan's saturation gauge exists to be watched while its two limits are tuned, and
the tuning rule is written from what that gauge shows. Watching it requires this
change.

It touches `Application.scala`, which `PLAN-CACHE-REGISTRY-RENAME` also touches.
The two edit different lines — a layer swap in the telemetry block versus
renamed types in the cache block — so either order works; landing the rename
first keeps the diffs smaller.

---

## Version bump

PATCH. Shipped code changes, no external API change.
