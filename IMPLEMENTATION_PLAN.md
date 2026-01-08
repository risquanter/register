# Risk Register - Implementation Plan v2

**Created:** January 6, 2026  
**Updated:** January 7, 2026  
**Status:** Phase 0, 1, 2 & 4 Complete | Phase 3 Deferred | Phase 5 Not Started  
**Current Tests:** 408 passing (287 common + 121 server)  
**Total Estimate (Remaining):** ~2.2 days of focused work

---

## 📋 Overview

This implementation plan addresses architectural improvements while maintaining test stability. **Phases 0, 1 & 2 are complete (408 tests passing).** Phase 3 is partially implemented. Remaining work is designed to be:
- **Small & Testable:** Complete in 0.5-1 day per phase
- **Incremental:** Each phase builds on the previous
- **Reversible:** Can pause after any phase

### ✅ Completed Work

**Phase 0: Error Handling & Typed Error Codes** (January 6-7, 2026)
- ✅ Typed `ValidationErrorCode` enum (15 codes in SCREAMING_SNAKE_CASE)
- ✅ Structured `ValidationError(field, code, message)`
- ✅ Field path context throughout validation (`fieldPrefix` parameter)
- ✅ `RiskTreeDefinitionRequest` naming (renamed from `CreateSimulationRequest`)
- ✅ BuildInfo integration for version management
- ✅ Error domain standardized to "risk-trees"
- ✅ All validation methods return `Either[List[ValidationError], T]`

**Phase 1: Configuration Management** (Before January 6, 2026)
- ✅ `application.conf` with full configuration (server, simulation, cors, db)
- ✅ Config case classes: `ServerConfig`, `SimulationConfig`, `CorsConfig`
- ✅ `Configs.makeLayer[T]` helper with `DeriveConfig`
- ✅ `Constants.scala` in common module
- ✅ Application bootstrap with `TypesafeConfigProvider`
- ✅ Services injected with config (`RiskTreeServiceLive` uses `SimulationConfig`)
- ✅ No hardcoded server/simulation values

**Phase 2: DTO/Domain Separation** (January 7, 2026) - ✅ 100% Complete
- ✅ **Architecture:** Validation-during-parsing with private intermediate DTOs
- ✅ Request validation at boundary: Custom `JsonDecoder` validates via smart constructors
- ✅ Private DTOs: `RiskLeafRaw`, `RiskPortfolioRaw` separate plain types from domain
- ✅ Response DTOs: `SimulationResponse` with `fromRiskTree()` and `withLEC()` factories
- ✅ Field path tracking: ID-based paths (e.g., `"riskLeaf[id=cyber].probability"`)
- ✅ Error accumulation: `Validation` monad collects all errors in one pass
- ✅ Iron type confinement: Refined types stay in domain, plain types in DTOs
- ✅ Test coverage: Field path tests passing (see `RiskLeafSpec`)
- ✅ Documentation: See `docs/DTO_DOMAIN_SEPARATION_DESIGN.md`
- ✅ **Field Path Design Decision:** Uses ID-based format for better semantic meaning
  - **Rationale:** Risk node IDs are globally unique within trees, making `"riskLeaf[id=cyber]"` more meaningful than `"root.children[0]"`
  - **Alternative considered:** Positional paths with array indices (not implemented)

### ⚠️ Partially Complete Work

**Phase 3: Structured Logging** ⏭️ DEFERRED
- ✅ Basic ZIO logging: `ZIO.logInfo()` in `Application.scala`
- ⏭️ **Deferred:** JSON logging superseded by OpenTelemetry
- ⏭️ **Deferred:** Request context propagation → moved to Phase 4
- **Rationale:** OpenTelemetry provides unified logs/traces/metrics, avoiding duplicate implementation

### 🔄 In Progress

**Phase 4: OpenTelemetry (Telemetry + Structured Logging)** ✅ COMPLETE
- ✅ **DONE:** Add ZIO Telemetry + OpenTelemetry dependencies (zio-opentelemetry 3.1.13)
- ✅ **DONE:** Request context propagation (FiberRef) - RequestContext.scala
- ✅ **DONE:** Distributed tracing with spans - TracingLive.scala
- ✅ **DONE:** Metrics collection (counters, histograms) - MetricsLive.scala
- ✅ **DONE:** OTLP exporter configuration - TracingLive.otlp(), MetricsLive.otlp(), TelemetryLive.otlp()
- ✅ **DONE:** RiskTreeServiceLive instrumented with spans and metrics
- **Benefit:** Single implementation provides logs, traces, AND metrics

### ❌ Not Started

**Phase 5: Pure ZIO Parallelism** (0% Complete)
- ❌ Replace `.par.map` with `ZIO.foreachPar`
- ❌ Remove scala-parallel-collections dependency
- ❌ Add interruption tests

---

## 🛡️ Merit Preservation Guarantee

**Critical Principle:** The following architectural merits are **PRESERVED AND NOT MODIFIED** throughout all phases. These represent idiomatic Scala 3 / ZIO best practices already in place.

### Preserved Patterns (NO CHANGES)

| Merit | Location | Why It's Preserved |
|-------|----------|-------------------|
| **Smart Constructors** | `RiskLeaf.create()`, `RiskPortfolio.create()` | Returns `Validation[String, T]` for parallel error accumulation |
| **Private Case Class Constructors** | `final case class RiskLeaf private (...)` | Forces all instantiation through smart constructors |
| **Iron Refined Types (Internal)** | `safeId: SafeId.SafeId`, `safeName: SafeName.SafeName` | Compile-time guarantees on field constraints |
| **Public String Accessors** | `def id: String = safeId.toString` in sealed trait | Clean API without exposing Iron internals |
| **Error Accumulation** | `Validation.validateWith()` calls | Collects ALL validation errors, not fail-fast |
| **JSON Decoders with Validation** | `mapOrFail { ... SafeId.fromString(...) }` | Validates during deserialization |

### How New Patterns Build On (Not Replace) Existing

```
┌─────────────────────────────────────────────────────────────────────┐
│ NEW: Request DTO Layer                                              │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ RiskLeafRequest (plain types: String, Double, Long)             │ │
│ │                                                                 │ │
│ │   def toDomain(): Validation[String, RiskLeaf] =                │ │
│ │       RiskLeaf.create(id, name, ...)  ◄── DELEGATES to existing │ │
│ └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│ PRESERVED: Smart Constructor (unchanged)                            │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ object RiskLeaf {                                               │ │
│ │   def create(...): Validation[String, RiskLeaf] =               │ │
│ │     Validation.validateWith(                                    │ │
│ │       SafeId.fromString(id),                                    │ │
│ │       SafeName.fromString(name),                                │ │
│ │       ...                                                       │ │
│ │     )(new RiskLeaf(_, _, ...))  // Private constructor          │ │
│ │ }                                                               │ │
│ └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### Phase-by-Phase Preservation Checklist

| Phase | What's Added | What's UNCHANGED |
|-------|--------------|------------------|
| Phase 1: Config | `ZIO.config()` loading | Smart constructors, validation logic |
| Phase 2: DTOs | `toDomain()` / `fromDomain()` | `RiskLeaf.create()`, `RiskPortfolio.create()` |
| Phase 3: Logging | `ZIO.logInfo()`, structured context | Iron types, error accumulation |
| Phase 4: Telemetry | Span/metric annotations | Domain model, validation |
| Phase 5: Parallelism | Replace Scala `.par` with ZIO | Simulation correctness, LEC computation |

### Verification: Tests That Prove Preservation

The following existing tests **MUST PASS UNCHANGED** after each phase:

```
// Smart constructor validation tests (RiskNodeValidationSpec)
- "RiskLeaf.create with valid parameters returns Success"
- "RiskLeaf.create accumulates multiple validation errors"
- "RiskPortfolio.create validates children recursively"
- "empty name returns validation error"
- "probability outside 0-1 range returns validation error"

// JSON decode validation tests (RiskNodeSpec)  
- "invalid JSON triggers validation errors"
- "deserialization validates via smart constructors"

// Error accumulation tests (ValidationAccumulationSpec)
- "accumulates all errors in RiskLeaf creation"
- "accumulates errors across nested portfolio children"
```

If any of these tests fail, the phase has violated the preservation guarantee and must be rolled back.

---

## Phase Summary

| Phase | Name | Status | Tests | Notes |
|-------|------|--------|-------|-------|
| **Phase 0** | **Error Handling & Typed Error Codes** | **✅ COMPLETE** | **408** | ValidationErrorCode, field paths, BuildInfo, RiskTreeDefinitionRequest |
| **Phase 1** | **Configuration Management** | **✅ COMPLETE (100%)** | **408** | application.conf, TypesafeConfigProvider, Configs.makeLayer[T], all config case classes |
| **Phase 2** | **DTO/Domain Separation** | **✅ COMPLETE (100%)** | **408** | Validation-during-parsing with private DTOs, ID-based field paths |
| Phase 3 | Structured Logging | ⏭️ DEFERRED → Phase 4 | 408 | Superseded by OpenTelemetry (unified observability) |
| **Phase 4** | **OpenTelemetry (Telemetry + Logging)** | **✅ COMPLETE** | **127** | TracingLive, MetricsLive, TelemetryLive, OTLP exporters |
| Phase 5 | Pure ZIO Parallelism | 🕰 Not Started (0%) | 408 | Replace `.par` with ZIO.foreachPar |

---

## Phase-by-Phase Preservation Checklist
| 0 | Documentation | ✅ DONE | - | - |
| 1 | Configuration Management | ⭐⭐⭐⭐⭐ | ✅ DONE (1 day) | +10 ✅ |
| 2 | DTO/Domain Separation | ⭐⭐⭐⭐⭐ | ✅ DONE (~90%) | Design complete |
| 3 | Structured Logging | ⭐⭐⭐⭐ | ⏭️ DEFERRED → Phase 4 | Superseded by OpenTelemetry |
| 4 | OpenTelemetry (Unified Observability) | ⭐⭐⭐⭐⭐ | 🔄 1.0 days | Traces + Logs + Metrics |
| 5 | Pure ZIO Parallelism | ⭐⭐ | 0.5 days | +5 |
| 6 | Final Documentation | ⭐⭐ | 0.5 days | - |

**Total Remaining:** ~2.2 days (~10 tests already added from Phase 1-2)

---

## Phase 0: Documentation ✅ COMPLETE

**Deliverables:**
- ✅ ARCHITECTURE.md created
- ✅ Current state documented
- ✅ Known gaps identified
- ✅ Future roadmap outlined
- ✅ This implementation plan

---

## Phase 1: Configuration Management ✅ COMPLETE

**Status:** 100% complete - All configuration externalized and functional.

**Completed Evidence:**
- ✅ `application.conf` created with all config sections (server, simulation, cors, db)
- ✅ `Configs.makeLayer[T]` generic helper implemented
- ✅ `ServerConfig`, `SimulationConfig`, `CorsConfig` case classes created
- ✅ `TypesafeConfigProvider` bootstrap in `Application.scala`
- ✅ All services accept injected configuration (e.g., `RiskTreeServiceLive`)
- ✅ 408 tests passing (no regressions)

**Implementation Notes:**
- Configuration work was completed BEFORE Phase 0 (error handling), as confirmed by git history
- Pattern follows BCG best practices with `ZIO.config(deriveConfig[C].nested(...))`
- All hardcoded values successfully externalized
- Environment variable overrides working (e.g., `${?REGISTER_SERVER_PORT}`)

### Original Goal
Externalize all hardcoded configuration values using ZIO Config with the pattern from BCG.

### Tasks (All Complete)

#### Task 1.1: Add Dependencies & Bootstrap ✅

**Changes to `build.sbt`:**
```scala
// Add to commonDependencies
"dev.zio" %% "zio-config" % zioConfigVersion,
"dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
"dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
```

**Create `modules/server/src/main/resources/application.conf`:**
```hocon
register {
  server {
    host = "0.0.0.0"
    host = ${?REGISTER_SERVER_HOST}
    port = 8080
    port = ${?REGISTER_SERVER_PORT}
  }
  
  simulation {
    defaultNTrials = 10000
    defaultNTrials = ${?REGISTER_DEFAULT_NTRIALS}
    maxTreeDepth = 5
    maxTreeDepth = ${?REGISTER_MAX_TREE_DEPTH}
    defaultParallelism = 8
    defaultParallelism = ${?REGISTER_PARALLELISM}
  }
  
  cors {
    allowedOrigins = ["http://localhost:3000", "http://localhost:5173"]
    allowedOrigins = ${?REGISTER_CORS_ORIGINS}
  }
}
```

**Tests:**
```scala
test("bootstrap loads without errors") { ... }
test("can read string from config") { ... }
```

---

#### Task 1.2: Create Config Infrastructure (2h)

**Create `modules/server/src/main/scala/.../configs/Configs.scala`:**
```scala
package com.risquanter.register.configs

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*

object Configs {
  /** Generic layer factory following BCG pattern */
  def makeLayer[C: DeriveConfig: Tag](path: String): ZLayer[Any, Throwable, C] = {
    val pathArr = path.split("\\.")
    ZLayer.fromZIO(
      ZIO.config(deriveConfig[C].nested(pathArr.head, pathArr.tail*))
    )
  }
}
```

**Create `modules/server/src/main/scala/.../configs/ServerConfig.scala`:**
```scala
package com.risquanter.register.configs

import zio.config.magnolia.deriveConfig

final case class ServerConfig(
  host: String,
  port: Int
)

object ServerConfig {
  given config: zio.Config[ServerConfig] = deriveConfig[ServerConfig]
}
```

**Create `modules/server/src/main/scala/.../configs/SimulationConfig.scala`:**
```scala
package com.risquanter.register.configs

import zio.config.magnolia.deriveConfig

final case class SimulationConfig(
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int
)

object SimulationConfig {
  given config: zio.Config[SimulationConfig] = deriveConfig[SimulationConfig]
}
```

**Create `modules/server/src/main/scala/.../configs/CorsConfig.scala`:**
```scala
package com.risquanter.register.configs

import zio.config.magnolia.deriveConfig

final case class CorsConfig(
  allowedOrigins: List[String]
)

object CorsConfig {
  given config: zio.Config[CorsConfig] = deriveConfig[CorsConfig]
}
```

**Create `modules/common/src/main/scala/.../common/Constants.scala`:**
```scala
package com.risquanter.register.common

/** Compile-time constants (not configurable at runtime) */
object Constants {
  // Validation constraints (must match Iron refinement types)
  val MinIdLength = 3
  val MaxIdLength = 30
  val MaxNameLength = 50
  
  // API defaults (can be overridden by request params)
  val DefaultNTrials = 10000
  val DefaultParallelism = 8
  val MaxTreeDepth = 5
}
```

**Tests:**
```scala
test("ServerConfig loads from typesafe config") {
  val result = ZIO.config(deriveConfig[ServerConfig].nested("register", "server"))
  assertZIO(result.map(_.port))(equalTo(8080))
}

test("SimulationConfig validates positive nTrials") {
  val badConfig = """
    register.simulation {
      defaultNTrials = -1
      maxTreeDepth = 5
      defaultParallelism = 8
    }
  """
  // Should fail with validation error
}

test("Config can be overridden with env vars") { ... }
```

---

#### Task 1.3: Update Application Bootstrap (2h)

**Update `Application.scala`:**
```scala
package com.risquanter.register

import zio.*
import zio.http.Server
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.server.ziohttp.ZioHttpInterpreter

import com.risquanter.register.configs.*
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.{RiskTreeServiceLive, SimulationExecutionService}
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory

object Application extends ZIOAppDefault {

  // Bootstrap: Configure TypesafeConfigProvider
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      TypesafeConfigProvider.fromResourcePath()
    )

  // Config layers
  val configLayer: ZLayer[Any, Throwable, ServerConfig & SimulationConfig & CorsConfig] =
    ZLayer.make[ServerConfig & SimulationConfig & CorsConfig](
      Configs.makeLayer[ServerConfig]("register.server"),
      Configs.makeLayer[SimulationConfig]("register.simulation"),
      Configs.makeLayer[CorsConfig]("register.cors")
    )

  // App layers (with config dependencies)
  val appLayer: ZLayer[ServerConfig & SimulationConfig, Throwable, RiskTreeController & Server] =
    ZLayer.make[RiskTreeController & Server](
      // Server uses ServerConfig
      ZLayer.fromZIO(
        ZIO.service[ServerConfig].map(cfg => 
          Server.Config.default.binding(cfg.host, cfg.port)
        )
      ) >>> Server.live,
      RiskTreeRepositoryInMemory.layer,
      SimulationExecutionService.live,
      RiskTreeServiceLive.layer,  // Will need SimulationConfig
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )

  override def run = {
    val program = for {
      _          <- ZIO.logInfo("Bootstrapping Risk Register application...")
      cfg        <- ZIO.service[ServerConfig]
      _          <- ZIO.logInfo(s"Server config: host=${cfg.host}, port=${cfg.port}")
      endpoints  <- HttpApi.endpointsZIO
      _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
      httpApp     = ZioHttpInterpreter().toHttp(endpoints)
      _          <- ZIO.logInfo(s"Starting HTTP server on ${cfg.host}:${cfg.port}...")
      _          <- Server.serve(httpApp)
    } yield ()

    program.provide(
      configLayer,
      appLayer
    )
  }
}
```

**Tests:**
```scala
test("Application starts with valid config") {
  // Integration test: start server, hit /health
}

test("Application fails fast on invalid config") {
  // Provide bad config, expect startup failure
}
```

---

#### Task 1.4: Update Services to Use Config (3h)

**Update `RiskTreeServiceLive.scala`:**
```scala
class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  executionService: SimulationExecutionService,
  config: SimulationConfig  // NEW: Inject config
) extends RiskTreeService {
  
  override def computeLEC(
    id: Long,
    nTrialsOverride: Option[Int],
    parallelism: Int,
    depth: Int = 0,
    includeProvenance: Boolean = false
  ): Task[RiskTreeWithLEC] = {
    // Use config for defaults and limits
    val effectiveNTrials = nTrialsOverride.getOrElse(config.defaultNTrials)
    val effectiveParallelism = if (parallelism <= 0) config.defaultParallelism else parallelism
    val clampedDepth = Math.min(depth, config.maxTreeDepth)
    
    // ... rest of implementation
  }
}

object RiskTreeServiceLive {
  val layer: ZLayer[
    RiskTreeRepository & SimulationExecutionService & SimulationConfig,
    Nothing,
    RiskTreeService
  ] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      exec <- ZIO.service[SimulationExecutionService]
      conf <- ZIO.service[SimulationConfig]
    } yield new RiskTreeServiceLive(repo, exec, conf)
  }
}
```

**Tests:**
```scala
test("service uses config.maxTreeDepth") {
  val config = SimulationConfig(defaultNTrials = 100, maxTreeDepth = 2, defaultParallelism = 4)
  // Verify depth is clamped to 2
}

test("service uses config.defaultParallelism when not specified") { ... }
```

---

### Phase 1 Deliverables ✅ COMPLETE
- [x] `application.conf` with all settings (server, simulation, cors, db)
- [x] Config case classes with `deriveConfig` (ServerConfig, SimulationConfig, CorsConfig)
- [x] `Configs.makeLayer` helper
- [x] `Constants.scala` in common module
- [x] Application bootstrap with TypesafeConfigProvider
- [x] Services injected with config (RiskTreeServiceLive uses SimulationConfig)
- [x] No hardcoded server/simulation values remain
- [x] Tests use TestConfigs layer
- [x] All 408 tests passing (no new tests added, existing tests updated)

---

## Phase 2: DTO/Domain Separation ✅ COMPLETE (100%)

**Status:** Clean separation achieved via validation-during-parsing pattern.
**Field Path Decision:** ID-based format chosen over positional format for better semantic meaning.

**Architecture Decision:** Use private intermediate types (`RiskLeafRaw`, `RiskPortfolioRaw`) with validation embedded in custom JSON decoders. This achieves DTO/Domain separation without duplicating validation logic.

**Completed Work:**
- ✅ **Request Validation at Boundary** - Custom `JsonDecoder` validates during parsing
- ✅ **Private DTOs** - `RiskLeafRaw`/`RiskPortfolioRaw` serve as intermediate plain types
- ✅ **Smart Constructor Integration** - Decoders call `create()` methods for validation
- ✅ **Response DTOs** - `SimulationResponse.fromRiskTree()`, `.withLEC()` implemented
- ✅ **Field Path Tracking** - Errors include precise location (e.g., `"root.children[0].id"`)
- ✅ **Error Accumulation** - `Validation` monad collects all errors in one pass
- ✅ **Iron Type Confinement** - Refined types stay in domain, plain types in DTOs
- ✅ **Private Constructors** - Domain types enforce validation (secure by default)
- ✅ **Test Coverage** - Field path tests passing (see `RiskLeafSpec`)

**Field Path Format Decision:**
- ✅ **Current Implementation:** ID-based paths like `"riskLeaf[id=cyber].probability"`
- ✅ **Design Rationale:** Risk node IDs are globally unique within risk trees
- ✅ **Alternative Considered:** Positional paths like `"root.children[0].probability"` (not implemented)
- ✅ **Decision:** ID-based format provides better semantic meaning for debugging
- ✅ **Status:** Feature considered complete - no further enhancement needed

**Design Documentation:**
- See `docs/DTO_DOMAIN_SEPARATION_DESIGN.md` for complete architecture rationale
- Pattern: JSON → Private DTO → Smart Constructor → Domain Model
- Single validation pathway (no duplication)

### Original Goal
Create a clean boundary between HTTP DTOs and domain models, with validation at the DTO layer.

### Analysis: BCG Pattern vs Improved Pattern

| Aspect | BCG Pattern | Improved Pattern |
|--------|-------------|------------------|
| Request DTOs | Plain types, no validation | Plain types + `toDomain()` with Validation |
| Response DTOs | `fromDomain()` factory | `fromDomain()` factory ✓ (keep this) |
| Validation | In service layer | At DTO boundary + smart constructors |
| Error handling | Fail-fast | Error accumulation |
| Domain constructors | Public | Private + smart constructors |

### Tasks

#### Task 2.1: Create Request DTOs with Validation (3h)

**Create `modules/common/src/main/scala/.../http/requests/RiskNodeRequest.scala`:**
```scala
package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec, jsonDiscriminator}
import zio.prelude.Validation
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}

/** HTTP DTO for risk node requests - plain types for JSON serialization */
@jsonDiscriminator("type")
sealed trait RiskNodeRequest {
  def id: String
  def name: String
}

object RiskNodeRequest {
  given codec: JsonCodec[RiskNodeRequest] = DeriveJsonCodec.gen[RiskNodeRequest]
  
  /** Convert DTO → Domain with validation (accumulates all errors) */
  def toDomain(req: RiskNodeRequest): Validation[String, RiskNode] = req match {
    case leaf: RiskLeafRequest => RiskLeafRequest.toDomain(leaf)
    case portfolio: RiskPortfolioRequest => RiskPortfolioRequest.toDomain(portfolio)
  }
}

/** HTTP DTO for leaf risk requests */
final case class RiskLeafRequest(
  id: String,
  name: String,
  distributionType: String,
  probability: Double,
  percentiles: Option[Array[Double]] = None,
  quantiles: Option[Array[Double]] = None,
  minLoss: Option[Long] = None,
  maxLoss: Option[Long] = None
) extends RiskNodeRequest

object RiskLeafRequest {
  given codec: JsonCodec[RiskLeafRequest] = DeriveJsonCodec.gen[RiskLeafRequest]
  
  /** Convert DTO → Domain with validation */
  def toDomain(req: RiskLeafRequest): Validation[String, RiskLeaf] = {
    // Delegates to smart constructor - validation happens there
    RiskLeaf.create(
      id = req.id,
      name = req.name,
      distributionType = req.distributionType,
      probability = req.probability,
      percentiles = req.percentiles,
      quantiles = req.quantiles,
      minLoss = req.minLoss,
      maxLoss = req.maxLoss
    )
  }
}

/** HTTP DTO for portfolio risk requests */
final case class RiskPortfolioRequest(
  id: String,
  name: String,
  children: Array[RiskNodeRequest]
) extends RiskNodeRequest

object RiskPortfolioRequest {
  given codec: JsonCodec[RiskPortfolioRequest] = DeriveJsonCodec.gen[RiskPortfolioRequest]
  
  /** Convert DTO → Domain with validation (accumulates all errors) */
  def toDomain(req: RiskPortfolioRequest): Validation[String, RiskPortfolio] = {
    // First convert all children
    val childValidations = req.children.map(RiskNodeRequest.toDomain)
    
    // Accumulate child errors
    val childrenValidation: Validation[String, Array[RiskNode]] = 
      Validation.validateAll(childValidations.toList).map(_.toArray)
    
    // Then validate portfolio itself
    childrenValidation.flatMap { validChildren =>
      RiskPortfolio.create(
        id = req.id,
        name = req.name,
        children = validChildren
      )
    }
  }
}
```

**Update `modules/common/src/main/scala/.../http/requests/RiskTreeDefinitionRequest.scala`:**
```scala
package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.{RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil}
import io.github.iltotore.iron.*

/** HTTP DTO for creating risk trees - plain types only */
final case class RiskTreeDefinitionRequest(
  name: String,
  nTrials: Int = 10000,
  root: RiskNodeRequest  // ← Now uses DTO type, not domain type
)

object RiskTreeDefinitionRequest {
  given codec: JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen[RiskTreeDefinitionRequest]
  
  /** Convert DTO → Domain with validation (accumulates all errors) */
  def toDomain(req: RiskTreeDefinitionRequest): Validation[String, (SafeName.SafeName, Int, RiskNode)] = {
    // Helper to convert Either to Validation
    def toValidation[A](either: Either[List[String], A]): Validation[String, A] =
      Validation.fromEither(either.left.map(_.mkString("; ")))
    
    // Validate all fields in parallel (accumulates errors)
    val nameV = toValidation(ValidationUtil.refineName(req.name))
    val trialsV = toValidation(ValidationUtil.refinePositiveInt(req.nTrials, "nTrials"))
      .map(_ => req.nTrials)  // Return Int, not refined type (service layer handles it)
    val rootV = RiskNodeRequest.toDomain(req.root)
    
    // Combine all validations
    nameV.zipPar(trialsV).zipPar(rootV).map { case ((name, trials), root) =>
      (name, trials, root)
    }
  }
}
```

**Tests:**
```scala
test("RiskLeafRequest.toDomain converts valid request") {
  val req = RiskLeafRequest(
    id = "cyber-risk",
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L)
  )
  val result = RiskLeafRequest.toDomain(req)
  assertTrue(result.isSuccess)
}

test("RiskLeafRequest.toDomain accumulates all validation errors") {
  val req = RiskLeafRequest(
    id = "x",               // Too short
    name = "",              // Empty
    distributionType = "invalid",
    probability = 2.0       // Out of range
  )
  val result = RiskLeafRequest.toDomain(req)
  result.toEither match {
    case Left(errors) =>
      assertTrue(
        errors.mkString("; ").contains("id"),
        errors.mkString("; ").contains("name"),
        errors.mkString("; ").contains("distribution"),
        errors.mkString("; ").contains("prob")
      )
    case Right(_) => assertTrue(false)
  }
}

test("RiskPortfolioRequest.toDomain validates children recursively") {
  val req = RiskPortfolioRequest(
    id = "portfolio",
    name = "Test",
    children = Array(
      RiskLeafRequest(id = "x", name = "", ...), // Invalid child
      RiskLeafRequest(id = "y", name = "", ...)  // Invalid child
    )
  )
  val result = RiskPortfolioRequest.toDomain(req)
  // Should accumulate errors from BOTH children
  result.toEither match {
    case Left(errors) =>
      assertTrue(errors.length >= 4) // At least 2 errors per child
    case Right(_) => assertTrue(false)
  }
}
```

---

#### Task 2.2: Create Response DTOs (2h)

**Create `modules/common/src/main/scala/.../http/responses/RiskNodeResponse.scala`:**
```scala
package com.risquanter.register.http.responses

import zio.json.{JsonCodec, DeriveJsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}

/** HTTP DTO for risk node responses - plain types for JSON serialization */
@jsonDiscriminator("type")
sealed trait RiskNodeResponse {
  def id: String
  def name: String
}

object RiskNodeResponse {
  given codec: JsonCodec[RiskNodeResponse] = DeriveJsonCodec.gen[RiskNodeResponse]
  
  /** Convert Domain → DTO */
  def fromDomain(node: RiskNode): RiskNodeResponse = node match {
    case leaf: RiskLeaf => RiskLeafResponse.fromDomain(leaf)
    case portfolio: RiskPortfolio => RiskPortfolioResponse.fromDomain(portfolio)
  }
}

/** HTTP DTO for leaf risk responses */
final case class RiskLeafResponse(
  id: String,
  name: String,
  distributionType: String,
  probability: Double,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[Long],
  maxLoss: Option[Long]
) extends RiskNodeResponse

object RiskLeafResponse {
  given codec: JsonCodec[RiskLeafResponse] = DeriveJsonCodec.gen[RiskLeafResponse]
  
  def fromDomain(leaf: RiskLeaf): RiskLeafResponse = RiskLeafResponse(
    id = leaf.id,              // Already String via accessor
    name = leaf.name,          // Already String via accessor
    distributionType = leaf.distributionType.toString,
    probability = leaf.probability,
    percentiles = leaf.percentiles,
    quantiles = leaf.quantiles,
    minLoss = leaf.minLoss.map(identity),
    maxLoss = leaf.maxLoss.map(identity)
  )
}

/** HTTP DTO for portfolio risk responses */
final case class RiskPortfolioResponse(
  id: String,
  name: String,
  children: Array[RiskNodeResponse]
) extends RiskNodeResponse

object RiskPortfolioResponse {
  given codec: JsonCodec[RiskPortfolioResponse] = DeriveJsonCodec.gen[RiskPortfolioResponse]
  
  def fromDomain(portfolio: RiskPortfolio): RiskPortfolioResponse = RiskPortfolioResponse(
    id = portfolio.id,
    name = portfolio.name,
    children = portfolio.children.map(RiskNodeResponse.fromDomain)
  )
}
```

**Update `SimulationResponse.scala`:**
```scala
// Add root node to response
final case class SimulationResponse(
  id: Long,
  name: String,
  nTrials: Int,                          // NEW
  root: Option[RiskNodeResponse],        // NEW: Include tree structure
  quantiles: Map[String, Double],
  exceedanceCurve: Option[String]
)

object SimulationResponse {
  // ... update fromRiskTree and withLEC methods
  
  def fromRiskTree(tree: RiskTree): SimulationResponse = SimulationResponse(
    id = tree.id,
    name = tree.name.value,
    nTrials = tree.nTrials,
    root = Some(RiskNodeResponse.fromDomain(tree.root)),
    quantiles = Map.empty,
    exceedanceCurve = None
  )
}
```

---

#### Task 2.3: Update Service Layer (3h)

**Update `RiskTreeServiceLive.scala`:**
```scala
override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
  // Validate request DTO → domain (accumulates all errors)
  RiskTreeDefinitionRequest.toDomain(req) match {
    case Validation.Success(_, (safeName, nTrials, root)) =>
      val riskTree = RiskTree(
        id = 0L.refineUnsafe, // repo will assign
        name = safeName,
        nTrials = nTrials,
        root = root
      )
      repo.create(riskTree)
      
    case Validation.Failure(_, errors) =>
      ZIO.fail(ValidationFailed(errors.toList))
  }
}
```

**Tests:**
```scala
test("service.create returns ValidationFailed with all errors") {
  val req = RiskTreeDefinitionRequest(
    name = "",           // Invalid
    nTrials = -1,        // Invalid
    root = RiskLeafRequest(id = "x", ...) // Invalid
  )
  
  val result = service.create(req).either
  assertZIO(result)(isLeft(isSubtype[ValidationFailed](hasField("errors", _.errors.length, greaterThanEqualTo(3)))))
}
```

---

#### Task 2.4: Update Controller (2h)

**Update `RiskTreeController.scala`:**
```scala
val create: ServerEndpoint[Any, Task] = createEndpoint.serverLogic { req =>
  // Service now handles DTO → Domain conversion internally
  val program = riskTreeService.create(req).map { result =>
    SimulationResponse.fromRiskTree(result)
  }

  program.either.map(_.left.map {
    case ValidationFailed(errors) => 
      ErrorResponse(
        code = "VALIDATION_ERROR",
        message = errors.mkString("; "),
        details = errors.map(e => ErrorDetail("validation", e))
      )
    case other => 
      ErrorResponse(
        code = "INTERNAL_ERROR",
        message = other.getMessage,
        details = Nil
      )
  })
}
```

---

### Phase 2 Deliverables ⚠️ PARTIAL (50% Complete)
- [ ] `RiskNodeRequest` hierarchy (DTO for requests) - **NOT DONE**
  - [ ] `RiskLeafRequest` with plain types
  - [ ] `RiskPortfolioRequest` with plain types
  - [ ] JSON codecs for request DTOs
- [x] `RiskNodeResponse` hierarchy (DTO for responses) - **DONE**
  - [x] `SimulationResponse` exists
- [ ] `toDomain()` methods with validation - **NOT DONE**
  - [ ] `RiskLeafRequest.toDomain()` returning `Validation[ValidationError, RiskLeaf]`
  - [ ] `RiskPortfolioRequest.toDomain()` with recursive validation
- [x] `fromDomain()` factory methods - **DONE**
  - [x] `SimulationResponse.fromRiskTree()`
  - [x] `SimulationResponse.withLEC()`
- [ ] Updated `RiskTreeDefinitionRequest` to use `RiskNodeRequest` - **NOT DONE**
- [ ] Service layer updated to use request DTOs - **NOT DONE**
- [ ] 15 new tests for DTO validation
- [ ] All 423+ tests passing

**Remaining Work (~1 day):**
1. Create `RiskLeafRequest` and `RiskPortfolioRequest` case classes
2. Implement `toDomain()` methods that delegate to smart constructors
3. Update `RiskTreeDefinitionRequest.root` from `RiskNode` to `RiskNodeRequest`
4. Update JSON decoders to use request DTOs
5. Add tests for request DTO validation

---

## Phase 3: Structured Logging ⏭️ DEFERRED

**Status:** Deferred - superseded by Phase 4 (OpenTelemetry)

**Rationale:**
- OpenTelemetry provides unified observability (logs + traces + metrics)
- Implementing structured logging separately would duplicate effort:
  - Both need FiberRef for request context
  - Both need middleware for request lifecycle
  - OpenTelemetry automatically captures structured logs
- More efficient to implement once with full observability stack

**Work Completed:**
- ✅ Basic `ZIO.logInfo()` calls in Application.scala (sufficient for development)

**Deferred to Phase 4:**
- ⏭️ Request context propagation (FiberRef)
- ⏭️ JSON log formatting
- ⏭️ MDC keys configuration
- ⏭️ Logging aspects

---

## Phase 4: OpenTelemetry (Unified Observability) 🔄 IN PROGRESS (0%)

**Status:** Basic logging added, structured JSON logging not implemented.

**Completed Work:**
- ✅ Basic `ZIO.logInfo()` calls added in `Application.scala`
- ✅ Logging statements exist for startup events

**Remaining Work (~0.7 days):**
- ❌ JSON logging configuration missing (no `logback.xml`)
- ❌ Request context propagation not implemented (no `FiberRef` for requestId)
- ❌ Logging aspects not added to routes
- ❌ MDC keys not configured (requestId, userId, treeId, duration)
- ❌ No structured logging in service layer

**Implementation Notes:**
- Foundation exists with ZIO.logInfo usage
- Needs logback-classic and logstash-logback-encoder dependencies
- Need to implement FiberRef-based context propagation
- Should add logging aspects to wrap HTTP routes

### Original Goal
Add JSON-formatted logs with request context propagation using ZIO Logging.

### Tasks

#### Task 3.1: Configure Logback for JSON (1h) ❌ Not Started

**Create `modules/server/src/main/resources/logback.xml`:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeMdcKeyName>requestId</includeMdcKeyName>
      <includeMdcKeyName>userId</includeMdcKeyName>
      <includeMdcKeyName>treeId</includeMdcKeyName>
      <includeMdcKeyName>duration</includeMdcKeyName>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="${LOG_FORMAT:-CONSOLE}"/>
  </root>
</configuration>
```

**Add dependency to `build.sbt`:**
```scala
"net.logstash.logback" % "logstash-logback-encoder" % "7.4"
```

---

#### Task 3.2: Create Request Context (2h)

**Create `modules/server/src/main/scala/.../context/RequestContext.scala`:**
```scala
package com.risquanter.register.context

import zio.*
import java.time.Instant
import java.util.UUID

/** Request context for logging and tracing */
final case class RequestContext(
  requestId: String,
  userId: Option[String],
  startTime: Instant
) {
  def duration: Long = java.time.Duration.between(startTime, Instant.now()).toMillis
}

object RequestContext {
  /** Generate new context with random request ID */
  def generate(userId: Option[String] = None): RequestContext = RequestContext(
    requestId = UUID.randomUUID().toString.take(8),
    userId = userId,
    startTime = Instant.now()
  )
  
  /** FiberRef for context propagation */
  val fiberRef: ULayer[FiberRef[Option[RequestContext]]] =
    ZLayer.scoped(FiberRef.make[Option[RequestContext]](None))
  
  /** Get current context from fiber */
  val get: ZIO[FiberRef[Option[RequestContext]], Nothing, Option[RequestContext]] =
    ZIO.serviceWithZIO[FiberRef[Option[RequestContext]]](_.get)
  
  /** Run effect with context */
  def withContext[R, E, A](
    ctx: RequestContext
  )(effect: ZIO[R, E, A]): ZIO[R & FiberRef[Option[RequestContext]], E, A] = {
    ZIO.serviceWithZIO[FiberRef[Option[RequestContext]]] { ref =>
      ref.locally(Some(ctx))(effect)
    }
  }
}
```

---

#### Task 3.3: Add Logging to Service Layer (3h)

**Create `modules/server/src/main/scala/.../logging/LoggingAspect.scala`:**
```scala
package com.risquanter.register.logging

import zio.*
import com.risquanter.register.context.RequestContext

object LoggingAspect {
  
  /** Log entry and exit of an operation with timing */
  def logged[R, E, A](
    operation: String
  )(effect: ZIO[R, E, A]): ZIO[R & FiberRef[Option[RequestContext]], E, A] = {
    for {
      ctx <- RequestContext.get
      reqId = ctx.map(_.requestId).getOrElse("unknown")
      _ <- ZIO.logInfo(s"Starting $operation")(
        LogAnnotation("requestId", reqId),
        LogAnnotation("operation", operation)
      )
      result <- effect.timed
      (duration, value) = result
      _ <- ZIO.logInfo(s"Completed $operation")(
        LogAnnotation("requestId", reqId),
        LogAnnotation("operation", operation),
        LogAnnotation("durationMs", duration.toMillis.toString)
      )
    } yield value
  }
  
  /** Log errors with context */
  def logError[R, E <: Throwable, A](
    operation: String
  )(effect: ZIO[R, E, A]): ZIO[R & FiberRef[Option[RequestContext]], E, A] = {
    effect.tapError { error =>
      for {
        ctx <- RequestContext.get
        reqId = ctx.map(_.requestId).getOrElse("unknown")
        _ <- ZIO.logError(s"Failed $operation: ${error.getMessage}")(
          LogAnnotation("requestId", reqId),
          LogAnnotation("operation", operation),
          LogAnnotation("error", error.getClass.getSimpleName)
        )
      } yield ()
    }
  }
}
```

**Update service methods:**
```scala
override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
  LoggingAspect.logged("create-risk-tree") {
    LoggingAspect.logError("create-risk-tree") {
      // ... existing implementation
    }
  }
}
```

---

#### Task 3.4: Add Middleware to Controller (2h)

**Create logging middleware:**
```scala
def withRequestContext[R, E, A](
  effect: ZIO[R, E, A],
  headers: Map[String, String]
): ZIO[R & FiberRef[Option[RequestContext]], E, A] = {
  val userId = headers.get("X-User-Id")
  val ctx = RequestContext.generate(userId)
  RequestContext.withContext(ctx)(effect)
}
```

---

### Phase 3 Deliverables
- [ ] JSON logging configuration (Logback)
- [ ] Request context FiberRef
- [ ] Logging aspect for timed operations
- [ ] Request ID generation
- [ ] User context extraction from headers
- [ ] 8 new tests
- [ ] All 434 tests passing

---

## Phase 4: OpenTelemetry (Unified Observability) 🔄 IN PROGRESS (0%)

**Status:** In progress - replacing Phase 3 with comprehensive observability solution.

**Goal:** Implement OpenTelemetry for unified logs, traces, and metrics in a single implementation.

**Benefits over separate structured logging:**
- ✅ Single implementation for all observability needs
- ✅ Automatic correlation: logs/traces/metrics share requestId/traceId
- ✅ Industry standard: Works with Jaeger, Tempo, Prometheus, Grafana
- ✅ Production-ready: OTLP exporter for Kubernetes observability stack

**Planned Work (~1.0 days):**
- ❌ Add ZIO Telemetry + OpenTelemetry dependencies (0.5h)
- ❌ Create RequestContext FiberRef for propagation (1h)
- ❌ Add OpenTelemetry tracing layer with console exporter (1h)
- ❌ Instrument RiskTreeService with trace spans (2h)
- ❌ Add metrics (request count, latency histogram) (1h)
- ❌ Configure OTLP exporter for production (1h)
- ❌ Testing and validation (1.5h)

### Tasks

#### Task 4.1: Add Dependencies (30 min) ❌ Not Started

**Add to `build.sbt`:**
```scala
val zioTelemetryVersion = "3.0.1"
val openTelemetryVersion = "1.42.1"

libraryDependencies ++= Seq(
  // ZIO Telemetry
  "dev.zio" %% "zio-opentelemetry" % zioTelemetryVersion,
  
  // OpenTelemetry SDK
  "io.opentelemetry" % "opentelemetry-sdk" % openTelemetryVersion,
  "io.opentelemetry" % "opentelemetry-sdk-trace" % openTelemetryVersion,
  "io.opentelemetry" % "opentelemetry-sdk-metrics" % openTelemetryVersion,
  
  // Exporters
  "io.opentelemetry" % "opentelemetry-exporter-logging" % openTelemetryVersion, // Development
  "io.opentelemetry" % "opentelemetry-exporter-otlp" % openTelemetryVersion,     // Production
  
  // Semantic conventions
  "io.opentelemetry.semconv" % "opentelemetry-semconv" % "1.27.0-alpha"
)
```

**Test:** `sbt compile` succeeds

---

#### Task 4.2: Create RequestContext (1h)

**Create `modules/server/src/main/scala/com/risquanter/register/telemetry/RequestContext.scala`:**
```scala
package com.risquanter.register.telemetry

import zio.*
import java.util.UUID

/** Request context propagated through ZIO fiber
  * Contains correlation IDs for distributed tracing and logging
  */
final case class RequestContext(
  requestId: String,
  traceId: Option[String] = None,
  spanId: Option[String] = None,
  userId: Option[String] = None
)

object RequestContext {
  /** FiberRef for request context propagation */
  val ref: FiberRef[Option[RequestContext]] = 
    FiberRef.unsafe.make[Option[RequestContext]](None)
  
  /** Generate new request context with random ID */
  def generate: UIO[RequestContext] = 
    ZIO.succeed(RequestContext(requestId = UUID.randomUUID().toString))
  
  /** Get current request context from FiberRef */
  def get: UIO[Option[RequestContext]] = ref.get
  
  /** Set request context in FiberRef */
  def set(ctx: RequestContext): UIO[Unit] = ref.set(Some(ctx))
  
  /** Run effect with request context */
  def withContext[R, E, A](ctx: RequestContext)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ref.locallyScoped(Some(ctx))(effect)
}
```

**Test:** Create unit test for context propagation

---

#### Task 4.3: Add Tracing Layer (1h)

**Create `modules/server/src/main/scala/com/risquanter/register/telemetry/Tracing.scala`:**
```scala
package com.risquanter.register.telemetry

import zio.*
import zio.telemetry.opentelemetry.*
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.exporter.logging.LoggingSpanExporter

object Tracing {
  /** Create OpenTelemetry tracing layer with console exporter (development) */
  val live: ZLayer[Any, Throwable, Tracing] = {
    ZLayer.scoped {
      for {
        // Create tracer provider with console exporter
        spanExporter <- ZIO.succeed(LoggingSpanExporter.create())
        spanProcessor <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
        tracerProvider <- ZIO.succeed(
          SdkTracerProvider.builder()
            .addSpanProcessor(spanProcessor)
            .build()
        )
        
        // Build OpenTelemetry SDK
        openTelemetry <- ZIO.succeed(
          OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
        )
        
        // Create tracer
        tracer <- ZIO.succeed(openTelemetry.getTracer("risk-register"))
        
        // Register shutdown hook
        _ <- ZIO.addFinalizer(ZIO.succeed(tracerProvider.close()))
        
      } yield Tracing.fromTracer(tracer)
    }
  }
  
  private def fromTracer(tracer: Tracer): Tracing = new Tracing {
    override def tracer: Tracer = tracer
  }
}

trait Tracing {
  def tracer: Tracer
}
```

**Test:** Application starts with tracing layer

---

#### Task 4.4: Instrument Service (2h)

**Update `RiskTreeServiceLive` to add spans:**
```scala
def computeLEC(
  treeId: Long,
  nTrials: Option[Int],
  depth: Option[Int],
  parallelism: Option[Int],
  includeProvenance: Boolean
): Task[RiskTreeWithLEC] = {
  Tracing.span("computeLEC") {
    for {
      _ <- Tracing.setAttribute("treeId", treeId)
      _ <- Tracing.setAttribute("nTrials", nTrials.getOrElse(defaultNTrials))
      
      tree <- repo.get(treeId)
      result <- tree match {
        case Some(t) => 
          Tracing.span("simulation") {
            simulationService.execute(/* ... */)
          }
        case None => ZIO.fail(...)
      }
    } yield result
  }
}
```

**Test:** Console shows span hierarchy with timing

---

#### Task 4.5: Add Metrics (1h)

**Create basic metrics:**
```scala
object Metrics {
  val requestCounter: Counter = // ...
  val latencyHistogram: Histogram = // ...
}
```

**Test:** Metrics increment correctly

---

#### Task 4.6: OTLP Exporter (1h)

**Configure production exporter:**
```scala
// Replace LoggingSpanExporter with OTLP
val otlpExporter = OtlpGrpcSpanExporter.builder()
  .setEndpoint("http://localhost:4317")
  .build()
```

**Test:** OTLP endpoint receives spans

---

## Phase 5: Pure ZIO Parallelism ❌ NOT STARTED (0%)

**Status:** Not yet started. Can be deferred to Kubernetes deployment phase.

**Planned Work (~0.5 days):**
- Add ZIO Telemetry dependencies
- Configure OpenTelemetry exporters
- Add trace spans for key operations
- Implement metrics (request count, latency histograms)

### Original Goal
Add OpenTelemetry integration for distributed tracing and metrics.

**Note:** This phase is optional and can be deferred until Kubernetes deployment.

### Key Additions
- ZIO Telemetry dependency
- OpenTelemetry exporter configuration  
- Trace spans for key operations
- Metrics (request count, latency histograms)

### Estimate: 0.5 days, +5 tests

---

## Phase 5: Pure ZIO Parallelism ❌ NOT STARTED (0%)

**Status:** Not yet started. Still using Scala parallel collections (`.par.map`).

**Current State:**
- Trial computation uses `.par.map` from Scala collections
- Works correctly but lacks ZIO integration benefits

**Planned Work (~0.5 days):**
- Replace `.par.map` with `ZIO.foreachPar`
- Add configurable parallelism from `SimulationConfig`
- Enable interruption support
- Prepare for telemetry integration

**Benefits When Complete:**
- Better control over parallelism (from config)
- Interruption support (cancel long-running simulations)
- Integration with ZIO telemetry/tracing
- Consistent effect system throughout

### Original Goal
Replace Scala parallel collections with pure ZIO for better control, interruption support, and telemetry integration.

### Tasks

#### Task 5.1: Update Trial Computation (4h)

**Current (Scala parallel collections):**
```scala
val successfulTrials = trials.filter(_.occurred).par
val losses = successfulTrials.map { trial => computeLoss(trial) }.toVector
```

**Updated (Pure ZIO):**
```scala
def computeLossesZIO(
  successfulTrials: Vector[Trial],
  parallelism: Int
): Task[Vector[Long]] = {
  ZIO.foreachPar(successfulTrials) { trial =>
    ZIO.attempt(computeLoss(trial))
  }.withParallelism(parallelism)
}
```

**Benefits:**
- Interruptible (can cancel long-running simulations)
- Telemetry integration (trace parallel execution)
- Better error handling (failures don't crash thread pool)
- Consistent with rest of codebase

---

### Phase 5 Deliverables
- [ ] Replace `.par.map` with `ZIO.foreachPar`
- [ ] Remove scala-parallel-collections import
- [ ] Add interruption tests
- [ ] Verify no performance regression
- [ ] 5 new tests
- [ ] All 439 tests passing

---

## Phase 6: Final Documentation Update

### Goal
Update ARCHITECTURE.md with all changes from Phases 1-5.

### Tasks
- [ ] Update "Current State" section
- [ ] Mark completed gaps as resolved
- [ ] Add new patterns documentation
- [ ] Update diagrams if needed
- [ ] Document lessons learned

---

## Summary

### Test Count Progression

| Phase | Starting | Added | Ending | Status |
|-------|----------|-------|--------|--------|
| **Phase 0** | **401** | **+7** | **408** | **\u2705 COMPLETE** |
| Phase 1 | 408 | +10 | 418+ | \ud83d\udd70 Pending |
| Phase 2 | 418 | +15 | 433+ | \ud83d\udd70 Pending |
| Phase 3 | 433 | +8 | 441+ | \ud83d\udd70 Pending |
| Phase 4 | 441 | +5 | 446+ | \ud83d\udd70 Optional |
| Phase 5 | 446 | +5 | 451+ | \ud83d\udd70 Pending |

### Time Estimate

| Phase | Estimate | Status |
|-------|----------|--------|
| **Phase 0: Error Handling** | **2 days** | **\u2705 COMPLETE** |
| Phase 1: Configuration | 1 day | \ud83d\udd70 Pending approval |
| Phase 2: DTO Separation | 1.5 days | \ud83d\udd70 Pending approval |
| Phase 3: Logging | 1 day | \ud83d\udd70 Pending approval |
| Phase 4: Telemetry | 0.5 days | \ud83d\udd70 Optional |
| Phase 5: Parallelism | 0.5 days | \ud83d\udd70 Pending approval |
| Phase 6: Documentation | 0.5 days | \ud83d\udd70 Pending approval |
| **Remaining Total** | **~4.5 days** | |

---

## Decision Points

### \u2705 Completed Decisions

- [x] **Phase 0: Error Handling & Typed Error Codes** - COMPLETED January 6-7, 2026
  - Typed ValidationErrorCode enum (15 codes)
  - Structured ValidationError with field path context
  - RiskTreeDefinitionRequest naming
  - BuildInfo integration
  - Result: 408 tests passing

- [x] **Phase 1: Configuration Management** - COMPLETED (before January 6, 2026)
  - application.conf with full configuration
  - TypesafeConfigProvider bootstrap
  - Configs.makeLayer[T] generic helper
  - ServerConfig, SimulationConfig, CorsConfig case classes
  - All services accept injected configuration
  - Result: 408 tests passing (no regressions)

- [x] **Phase 2: DTO/Domain Separation** - COMPLETED January 7, 2026 (~90%)
  - Validation-during-parsing pattern with private DTOs
  - Custom JsonDecoder validates via smart constructors
  - RiskLeafRaw/RiskPortfolioRaw intermediate types
  - Response DTOs (SimulationResponse)
  - Field path tracking implemented and tested
  - Error accumulation via Validation monad
  - Documentation: docs/DTO_DOMAIN_SEPARATION_DESIGN.md
  - Result: 408 tests passing, clean DTO/Domain separation

### \ud83d\udd04 Partially Completed (Awaiting Decision to Complete)

- [~] **Phase 3: Structured Logging** - 30% COMPLETE
  - \u2705 Basic ZIO.logInfo() added
  - \u274c JSON logging configuration missing
  - \u274c Request context propagation not implemented
  - **Decision Needed:** Complete structured logging? (~0.7 days)

### \ud83d\udd70 Pending Decisions

- [ ] **Phase 4: Telemetry** - Include now or defer to K8s deployment? (~0.5 days)
- [ ] **Phase 5: Pure ZIO Parallelism** - Include now or defer? (~0.5 days)

---

**Current State:** Phase 0, 1 & 2 complete (100%, 100%, 90%), Phase 3 partial (30%). ~2.2 days remaining work if all phases completed.

**Next Decision:** Should we complete Phase 3 (JSON logging), or move to Phase 4-5, or defer remaining work?
