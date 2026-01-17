# ADR-013-proposal: Testing Strategy

**Status:** Proposed  
**Date:** 2026-01-17  
**Tags:** testing, zio-test, integration, quality

---

## Context

- Tests exist but are primarily **unit tests** that verify components in isolation
- **Integration gaps** have been discovered: components build and pass unit tests but are not wired into the application
- No formalized distinction between test categories (unit, integration, wiring, e2e)
- Need to catch issues like "SSEController exists but is not connected to HttpApi"
- External testing tools (Playwright, Cypress) are out of scope — Scala/ZIO only
- Manual test cases should be documented in `docs/test/TESTING.md`

---

## Decision

### 1. Test Categories

Define four categories of tests with clear boundaries:

| Category | Scope | Runs In | Location |
|----------|-------|---------|----------|
| **Unit** | Single function/class | Memory only | `*Spec.scala` |
| **Integration** | Multiple components | May need services | `*IntegrationSpec.scala` |
| **Wiring** | Controller → HttpApi → Server | Needs running server | `*WiringSpec.scala` |
| **Manual** | Browser, visual, exploratory | Human execution | `docs/test/TESTING.md` |

### 2. Test Naming Conventions

```
{Component}Spec.scala           — Unit tests
{Component}IntegrationSpec.scala — Integration tests  
{Component}WiringSpec.scala      — Wiring/smoke tests
```

**Examples:**
- `SimulatorSpec.scala` — Unit: tests Simulator.performTrials in isolation
- `IrminClientIntegrationSpec.scala` — Integration: tests IrminClient against real Irmin
- `SSEControllerWiringSpec.scala` — Wiring: verifies SSE endpoint is accessible via HTTP

### 3. Wiring Tests (NEW)

Every controller MUST have a corresponding wiring test that verifies:

1. **Controller is registered** in HttpApi
2. **Endpoint is accessible** via HTTP request
3. **Required layers** are provided in Application

```scala
// Example: SSEControllerWiringSpec.scala
object SSEControllerWiringSpec extends ZIOSpecDefault {
  
  def spec = suite("SSEController Wiring")(
    test("SSE endpoint is accessible via HTTP") {
      for {
        // Start minimal server with all layers
        _ <- TestServer.start
        // Hit the actual endpoint
        response <- Client.request(Request.get(URL.decode("/events/tree/1").toOption.get))
        // Verify it responds (not 404)
      } yield assertTrue(response.status != Status.NotFound)
    }.provide(
      // Full application layer stack
      Application.appLayer,
      Client.default
    )
  )
}
```

**Wiring tests catch:**
- Controller not added to HttpApi.makeControllers
- Missing layers in Application.appLayer
- Endpoint path typos
- Dependency injection failures

### 4. Test Requirements by Phase

| Phase Type | Required Tests |
|------------|----------------|
| New Service | Unit tests for business logic |
| New Controller | Unit tests + Wiring test |
| New Endpoint | Wiring test (endpoint reachable) |
| Integration Layer | Integration test (e.g., IrminClient → Irmin) |

### 5. ZIO Test Patterns

#### 5.1 Unit Tests (In-Memory)

```scala
object LECCacheSpec extends ZIOSpecDefault {
  def spec = suite("LECCache")(
    test("invalidate clears ancestors") {
      for {
        cache <- ZIO.service[LECCache]
        _     <- cache.set(nodeId, lecData)
        inv   <- cache.invalidate(nodeId)
      } yield assertTrue(inv.contains(nodeId))
    }
  ).provide(
    LECCache.layer,
    TreeIndex.layer  // In-memory test layer
  )
}
```

#### 5.2 Integration Tests (External Services)

```scala
object IrminClientIntegrationSpec extends ZIOSpecDefault {
  def spec = suite("IrminClient Integration")(
    test("can write and read value") {
      for {
        client <- ZIO.service[IrminClient]
        _      <- client.set(path, "test-value", info)
        result <- client.get(path)
      } yield assertTrue(result.contains("test-value"))
    }
  ).provide(
    IrminClientLive.layer,
    IrminConfig.layer
  ) @@ TestAspect.ifEnvSet("IRMIN_URL")  // Skip if Irmin not running
}
```

#### 5.3 Wiring Tests (Full Stack)

```scala
object RiskTreeControllerWiringSpec extends ZIOSpecDefault {
  
  // Test server helper
  val serverLayer: ZLayer[Any, Throwable, Unit] = 
    Application.appLayer >>> ZLayer.fromZIO(
      for {
        endpoints <- HttpApi.endpointsZIO
        _ <- Server.serve(ZioHttpInterpreter().toHttp(endpoints)).fork
        _ <- ZIO.sleep(100.millis)  // Wait for server start
      } yield ()
    )
  
  def spec = suite("RiskTreeController Wiring")(
    test("health endpoint is accessible") {
      for {
        response <- Client.request(
          Request.get(URL.decode("http://localhost:8080/health").toOption.get)
        )
        body <- response.body.asString
      } yield assertTrue(
        response.status == Status.Ok,
        body.contains("healthy")
      )
    },
    
    test("SSE endpoint is accessible") {
      for {
        response <- Client.request(
          Request.get(URL.decode("http://localhost:8080/events/tree/1").toOption.get)
        )
      } yield assertTrue(response.status != Status.NotFound)
    }
  ).provide(
    serverLayer,
    Client.default,
    Scope.default
  ) @@ TestAspect.sequential
}
```

### 6. Test Execution Strategy

```bash
# Unit tests only (fast, CI default)
sbt "testOnly * -- -l integration -l wiring"

# Integration tests (requires external services)
sbt "testOnly *IntegrationSpec"

# Wiring tests (starts server)
sbt "testOnly *WiringSpec"

# All tests
sbt test
```

### 7. Test Tagging

Use ZIO Test aspects to tag tests:

```scala
test("requires Irmin") {
  // ...
} @@ TestAspect.tag("integration") @@ TestAspect.ifEnvSet("IRMIN_URL")

test("wiring check") {
  // ...
} @@ TestAspect.tag("wiring")
```

### 8. Manual Test Cases

Tests that cannot be automated in Scala/ZIO are documented in `docs/test/TESTING.md`:

- Browser SSE connection (EventSource behavior)
- Visual verification of Swagger UI
- Performance under load (manual benchmarking)
- Cross-browser compatibility

Format in TESTING.md:
```markdown
### Manual Test: SSE Browser Connection

**Purpose:** Verify SSE works in actual browser

**Steps:**
1. Start server: `docker compose up -d`
2. Open browser to `http://localhost:8080/events/tree/1`
3. Open DevTools → Network tab
4. Verify EventSource connection established
5. Trigger event via `POST /risk-trees/1/invalidate/node-id`
6. Verify event appears in browser

**Expected:** Event received within 1 second

**Status:** [ ] Pass / [ ] Fail
**Date:** ____
**Tester:** ____
```

### 9. Coverage Requirements

| Category | Minimum Coverage |
|----------|-----------------|
| Domain models | 90% (core business logic) |
| Services | 80% (service layer) |
| Controllers | 60% + wiring test |
| Infrastructure | Integration test exists |

### 10. CI Integration

```yaml
# .github/workflows/test.yml
test:
  steps:
    - name: Unit Tests
      run: sbt "testOnly * -- -l integration -l wiring"
    
    - name: Integration Tests
      run: |
        docker compose --profile persistence up -d
        sbt "testOnly *IntegrationSpec"
      
    - name: Wiring Tests
      run: sbt "testOnly *WiringSpec"
```

---

## Consequences

### Positive

- **Catches wiring issues early** — SSEController-not-connected bug would have been caught
- **Clear test organization** — developers know where to add tests
- **Fast CI** — unit tests run quickly, integration tests run separately
- **Explicit manual tests** — no false confidence from incomplete automation

### Negative

- **More test files** — each controller needs unit + wiring spec
- **Longer full test run** — wiring tests start server
- **Maintenance overhead** — wiring tests may break on layer changes

### Neutral

- Existing tests remain valid, just need categorization
- Manual tests are explicitly documented rather than forgotten

---

## Implementation

1. Create `*WiringSpec.scala` for existing controllers
2. Add test tagging to existing specs
3. Update TESTING.md with manual test section
4. Add CI workflow for test categories

---

## References

- ZIO Test documentation: https://zio.dev/reference/test/
- ZIO Test aspects: https://zio.dev/reference/test/aspects/
- Existing test guide: `docs/test/TESTING.md`

---

*Document created: 2026-01-17*
