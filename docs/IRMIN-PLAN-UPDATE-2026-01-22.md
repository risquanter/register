# Irmin Integration Plan Update (2026-01-22)

This document is self-contained for picking up Irmin wiring and test work. It supersedes status items in `docs/IRMIN-INTEGRATION-STATUS-2026-01-20.md` where noted.

## Goals
- Config-selectable repository: `irmin` or `in-memory`.
- Fail-fast Irmin wiring with startup health check (retry + timeout, configurable, with defaults).
- Add repository-level integration spec against real Irmin.
- Add HTTP-level integration specs against a real server wired to Irmin.

## Configuration (new/updated)
- `register.repository.type`: `"irmin" | "in-memory"` (default: `"in-memory"`). Any other value falls back to `in-memory` with a warning.
- `register.irmin.url`: required when `type = "irmin"`; validated as an Iron `IrminUrl` (absolute http/https URI with host, optional port).
- `register.irmin.healthCheck.timeoutMillis`: default `5000` (fail-fast total timeout for startup health check).
- `register.irmin.healthCheck.retries`: default `2` (number of additional attempts; combined with retry schedule below).
- Suggested retry schedule: exponential/backoff or spaced (e.g., 200ms, 400ms) capped by `timeoutMillis`.

## Wiring Changes (Application.scala)
- Layers:
  - `inMemoryRepo = RiskTreeRepositoryInMemory.layer`
  - `irminRepo = IrminConfig.layer >>> IrminClientLive.layer >>> RiskTreeRepositoryIrmin.layer`
- Selection helper `chooseRepo: ZLayer[Any, Throwable, RiskTreeRepository]`:
  1. Read `register.repository.type`.
  2. If `"irmin"` and `register.irmin.url` present:
     - Validate URL via Iron `IrminUrl` smart constructor (fail config load if invalid).
     - Run startup health check: `IrminClient.healthCheck.retry(retrySchedule).timeout(timeoutMillis)`.
     - On success: log selected repo + URL; provide `irminRepo`.
     - On failure/timeout: **fail the layer** (fail-fast). Log clear error.
  3. Otherwise: log warning (reason: missing/invalid type or URL) and provide `inMemoryRepo`.
- Startup logs must state which repo is used and whether a fallback occurred.

## Health Check Design (Fail-Fast)
- Implement inside a scoped layer after `IrminClientLive` creation.
- Use configurable retry + timeout; defaults above cap total wait at ~5s.
- Failure path: raise layer error → application startup fails. (Preferred policy per request.)
- Rationale: hard dependency in prod/docker; early signal over latent runtime failures.

## Iron Type for URL
- Add `IrminUrl` opaque type refined via Iron:
  - Validates absolute `http|https` URI, non-empty host, optional port.
  - Smart constructor `IrminUrl.fromString: Either[ValidationError, IrminUrl]`.
  - Used in `IrminConfig`; config load fails on invalid URL (mirrors existing NonNegativeInt patterns).

## Tests to Add
- **Repo integration spec** (server-it): `RiskTreeRepositoryIrminSpec`
  - Env/config: requires Irmin container (`docker compose --profile persistence up -d`); reuse `register.irmin.url` or `IRMIN_URL`.
  - Cases: create tree; read back; update with node deletions (prune); list trees; delete; meta roundtrip (`createdAt/updatedAt`, `schemaVersion`).
- **HTTP integration specs** (server-it): real server wired to Irmin
  - `RiskTreeApiIntegrationSpec`: POST/GET trees, GET node LEC, POST multi LEC, GET prob-of-exceedance.
  - `CacheApiIntegrationSpec`: cache stats/list, invalidate node, clear tree cache.
  - Harness: start server on random port with `repository.type=irmin` and `register.irmin.url` pointing to container; sttp client to call endpoints.

## Execution Steps
1. Implement `IrminUrl` Iron type and integrate into `IrminConfig` (defaults + validation).
2. Add config defaults for repo type (`in-memory`) and health check (timeout 5000ms, retries 2).
3. Implement `chooseRepo` wiring and replace direct `RiskTreeRepositoryInMemory.layer` in `Application.scala` with the selector.
4. Add startup health check in the Irmin path (fail-fast as described).
5. Add repo integration spec (Irmin container required).
6. Add HTTP integration specs (Irmin-backed server) and document how to run.
7. Update `docs/IRMIN-INTEGRATION-STATUS-2026-01-20.md` to reflect new wiring and tests once merged; ensure `modules/server-it/README.md` mentions new tests and config.

## References
- Status baseline: `docs/IRMIN-INTEGRATION-STATUS-2026-01-20.md` (update after completing steps).
- Existing Irmin client tests: `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala`.
- Compose Irmin service: `docker compose --profile persistence up -d` (see `modules/server-it/README.md`).
