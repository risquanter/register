# Integration Tests

This subproject contains integration tests for the register server that require external dependencies (like Irmin).

## Prerequisites

Integration tests require running services from docker-compose:

```bash
# Start Irmin (required for Irmin client tests)
docker compose --profile persistence up -d

# Stop services when done
docker compose --profile persistence down
```

## Running Tests

```bash
# Run all integration tests
sbt "serverIt/test"

# Run specific integration test
sbt "serverIt/testOnly *IrminClientIntegrationSpec"

# Run only unit tests (excludes integration tests)
sbt "server/test"

# Run all tests (unit + integration)
sbt "test"  # from root project
```

## Project Structure

```
modules/server-it/
  src/
    test/
      scala/
        com/risquanter/register/
          infra/
            irmin/
              IrminClientIntegrationSpec.scala
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
