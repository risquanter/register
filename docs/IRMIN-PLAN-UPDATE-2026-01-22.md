# Irmin Integration Plan Update (2026-01-22)

This document is self-contained for picking up Irmin wiring and test work. It supersedes status items in `docs/IRMIN-INTEGRATION-STATUS-2026-01-20.md` where noted.

## Goals
- Config-selectable repository: `irmin` or `in-memory`. **(Implemented in Application.scala)**
- Fail-fast Irmin wiring with startup health check (retry + timeout, configurable). **(Implemented: SafeUrl-based IrminConfig + bounded health check)**
- Add repository-level integration spec against real Irmin. **(Pending)**
- Add HTTP-level integration specs against a real server wired to Irmin. **(Pending)**

## Configuration (as implemented)
- `register.repository.repositoryType`: `"irmin" | "in-memory"` (default: `"in-memory"`). Any other value falls back to in-memory with a warning.
- `register.irmin.url`: SafeUrl-validated (http/https, internal hosts allowed).
- `register.irmin.healthCheckTimeoutMillis`: default `5000`.
- `register.irmin.healthCheckRetries`: default `2` (simple recurs schedule).
- `register.irmin.timeoutSeconds`, `branch` still available.

## Wiring (current state in Application.scala)
- Layers already present:
  - `inMemoryRepo = RiskTreeRepositoryInMemory.layer`
  - `irminRepo = IrminConfig.layer >>> IrminClientLive.layer >>> irminHealthCheck >>> RiskTreeRepositoryIrmin.layer`
- Selection helper `chooseRepo` reads `register.repository.repositoryType`; if `"irmin"`, runs bounded health check (retries = `healthCheckRetries`, timeout = `healthCheckTimeoutMillis`) and selects Irmin. Otherwise logs a warning and selects in-memory.
- Health check logs success/failure; failure fails the layer (fail-fast).

## Health Check Design (implemented)
- Scoped ZLayer after `IrminClientLive` creation.
- Retries: `healthCheckRetries` recurs schedule; Timeout: `healthCheckTimeoutMillis`.
- Failure path fails the layer (startup fails); success logs selected repo/URL.

## URL Validation
- `IrminConfig.url` currently uses `SafeUrl` (relaxed internal http/https regex). No separate `IrminUrl` added yet.

## Tests to Add (still pending)
- **Repo integration spec** (server-it): `RiskTreeRepositoryIrminSpec`
  - Env/config: requires Irmin container (`docker compose --profile persistence up -d`); reuse `register.irmin.url` or `IRMIN_URL`.
  - Cases: create tree; read back; update with node deletions (prune); list trees; delete; meta roundtrip (`createdAt/updatedAt`, `schemaVersion`).
- **HTTP integration specs** (server-it): real server wired to Irmin
  - `RiskTreeApiIntegrationSpec`: POST/GET trees, GET node LEC, POST multi LEC, GET prob-of-exceedance.
  - `CacheApiIntegrationSpec`: cache stats/list, invalidate node, clear tree cache.
  - Harness: start server on random port with `repositoryType=irmin` and `register.irmin.url` pointing to container; sttp client to call endpoints.

## Execution Steps (updated)
1. Add repo integration spec (Irmin container required).
2. Add HTTP integration specs (Irmin-backed server) and document how to run.
3. Optionally tighten URL type (IrminUrl) if we want stricter-than-SafeUrl validation.
4. Update `docs/IRMIN-INTEGRATION-STATUS-2026-01-20.md` after tests land; ensure `modules/server-it/README.md` mentions new tests and config.

## Testcontainers-first plan (Option B — approved 2026-01-22)
- Goal: get a clean Irmin state per test run by using Testcontainers to spin up the existing `docker-compose.yml` (persistence profile) instead of relying on a manually started container with unknown state.
- Dependency (pending user approval already granted for Option B): add `testcontainers-scala-docker-compose` (and core) for Scala 3 in the `server-it` test scope; keep versions aligned with Scala 3.6.x and Testcontainers 1.19.x+.
- Container setup: use `DockerComposeContainer` pointing at the repo root `docker-compose.yml`, enable profile `persistence`, set a unique `COMPOSE_PROJECT_NAME` per suite/run to isolate volumes/networks, and expose the Irmin GraphQL endpoint (port 9080) as `IRMIN_URL` for tests.
- Test isolation: prefer unique Irmin branches or tree-id prefixes per suite; add teardown cleanup (delete branch or paths) to avoid state bleed if compose reuse ever occurs.
- Guardrails: detect missing Docker/Testcontainers support and mark suites as pending with a clear message rather than failing hard.
- Impact on phases:
  - Phase 1 (repo integration spec) will consume the Testcontainers-managed `IRMIN_URL` and can assume a clean store per run.
  - Phase 2 (live HTTP harness) will start the real server after the compose container is up; inject `repositoryType=irmin` and the provided URL.
  - Phase 3 (HTTP integration specs) will run on top of the harness; state isolation comes from fresh compose per run.
- Until Testcontainers wiring is implemented and green, Phase 1–3 code additions remain on hold.

## References
- Status baseline: `docs/IRMIN-INTEGRATION-STATUS-2026-01-20.md` (update after completing steps).
- Existing Irmin client tests: `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala`.
- Compose Irmin service: `docker compose --profile persistence up -d` (see `modules/server-it/README.md`).
