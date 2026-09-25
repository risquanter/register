# File inventory — PLAN-TELEMETRY-EXPORT.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


- `modules/server/src/main/scala/com/risquanter/register/configs/TelemetryConfig.scala` — the `TelemetryExporter` enum, the new field, the `DeriveConfig` given
- `modules/server/src/main/scala/com/risquanter/register/telemetry/TelemetryLive.scala` — the `configured` selector layer
- `modules/server/src/main/scala/com/risquanter/register/Application.scala` — the layer swap and the comment that stops being true
- `modules/server/src/main/resources/application.conf` — the `exporter` setting and its environment override
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala` — the new `exporter` field at its `TelemetryConfig` construction site
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala` — the new `exporter` field at its `TelemetryConfig` construction site
- `modules/server/src/test/scala/com/risquanter/register/configs/TelemetryConfigSpec.scala` — existing file, extended — the exporter field parses from configuration and rejects an unknown value
- `otel-collector-config.yaml` — migrate the deprecated `logging` exporter to `debug` in both pipelines; the collector removed `logging` at v0.111.0
- `docker-compose.yml` — uncomment `OTEL_EXPORTER_OTLP_ENDPOINT` and remove the "Unused with console exporters" note
- `docs/user/DOCKER-DEVELOPMENT.md` — how to read metrics: run the observability profile, curl port 8889
- `docs/dev/ARCHITECTURE.md` — the observability section currently states telemetry is "fully integrated via TelemetryLive (console + OTLP exporters)", which reads as though export works; it is corrected to say which exporter is default and how to change it

Any test that provides `TelemetryConfig` by constructing it directly gains the
new field. Adding a field to a case class is a compile error at every
construction site, so the compiler produces that list exhaustively; sites found
that way are added to this inventory before being edited.
