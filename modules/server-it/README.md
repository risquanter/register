# Integration Tests

This subproject contains integration tests for the register server that require external dependencies (like Irmin).

## Prerequisites

Integration tests auto-start Irmin via the `IrminCompose` helper (scoped Docker Compose) — just run the tests and the Irmin container is started/stopped for you. Requirements: Docker + docker-compose on PATH, and the `irmin` image from the repo's `docker-compose.yml` (build it first with `docker compose build irmin` if missing).

If you prefer to run Irmin yourself (optional):

```bash
# Start Irmin only (manual)
docker compose --profile persistence up -d irmin

# Stop when done
docker compose --profile persistence down
```

## Running Tests

```bash
# Run all integration tests (Irmin auto-starts via IrminCompose)
sbt "serverIt/test"

# Run specific integration test (Irmin auto-starts)
sbt "serverIt/testOnly *RiskTreeRepositoryIrminSpec"
sbt "serverIt/testOnly *HttpApiIntegrationSpec"

# Run only unit tests (excludes integration tests)
sbt "server/test"

# Run all tests (unit + integration)
sbt "test"  # from root project
```

- Repository-level specs (e.g., RiskTreeRepositoryIrminSpec) use `IrminCompose.irminConfigLayer` to start a compose-scoped Irmin and stop it when tests finish.
- HTTP-level specs (HttpApiIntegrationSpec) use `HttpTestHarness.irminServer` to start a real HTTP server on a random port wired to the Irmin-backed repository; the STTP client is provided by `SttpClientFixture.layer`.
- Ad-hoc harness recipe for new suites:
  - Server layer: `HttpTestHarness.irminServer(IrminCompose.irminConfigLayer)`
  - Client layer: `SttpClientFixture.layer` (provides STTP backend + baseUrl)
  - Combine with your test layer via `provideLayerShared`

## Project Structure

```
modules/server-it/
  src/test/scala/com/risquanter/register/
    infra/irmin/IrminClientIntegrationSpec.scala
    repositories/RiskTreeRepositoryIrminSpec.scala
    http/HttpApiIntegrationSpec.scala
    http/support/HttpTestHarness.scala
    http/support/SttpClientFixture.scala
    testcontainers/IrminCompose.scala
```

## Adding New Integration Tests

1. Create test files in `src/test/scala` (standard location)
2. Tests automatically inherit dependencies from the `server` project
3. Add any integration-test-specific dependencies to the `serverIt` project in `build.sbt`

## Why a Separate Subproject?

- **Separation**: Integration tests are physically separated from unit tests
- **Selective Execution**: Can run integration tests independently
- **CI/CD**: Easy to exclude integration tests in CI pipelines where external services aren't available
- **Clean Dependencies**: Can have different dependencies than unit tests if needed
- **Modern sbt**: Follows sbt 1.9+ recommendations (no deprecated `IntegrationTest` configuration)
