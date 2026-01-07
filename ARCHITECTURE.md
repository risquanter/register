# Risk Register - Architecture Documentation

**Last Updated:** January 7, 2026  
**Status:** Active Development  
**Version:** 0.1.0

---

## 📋 Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current Architecture State](#current-architecture-state)
3. [Architectural Strengths](#architectural-strengths)
4. [Known Architectural Gaps](#known-architectural-gaps)
5. [Technology Stack](#technology-stack)
6. [Domain Model](#domain-model)
7. [Layered Architecture](#layered-architecture)
8. [Data Flow](#data-flow)
9. [Validation Strategy](#validation-strategy)
10. [Parallel Execution Model](#parallel-execution-model)
11. [Testing Strategy](#testing-strategy)
12. [Future Roadmap](#future-roadmap)
13. [Deployment Architecture](#deployment-architecture)

---

## Executive Summary

**Risk Register** is a functional Scala application for hierarchical Monte Carlo risk simulation. It enables users to model complex risk portfolios as trees, execute simulations, and analyze Loss Exceedance Curves (LECs).

**Current State:**
- ✅ 408 tests passing (287 common + 121 server)
- ✅ Core simulation engine functional with parallel execution
- ✅ Type-safe domain model using Iron refinement types
- ✅ Clean separation of concerns (domain/service/HTTP layers)
- ✅ Typed error codes with field path context (ValidationErrorCode)
- ✅ BuildInfo integration for version management
- ⚠️ Configuration hardcoded (needs externalization)
- ⚠️ Minimal structured logging (needs enhancement)
- ⚠️ DTO/Domain separation incomplete (architectural debt)

---

## Current Architecture State

### ✅ **What Works Well**

#### 1. **Type Safety with Iron Refinement Types**
```scala
// Compile-time validation with zero runtime overhead
opaque type SafeId = String :| (Not[Blank] & MinLength[3] & MaxLength[30] & Match["^[a-zA-Z0-9_-]+$"])
type Probability = Double :| (Greater[0.0] & Less[1.0])
```

**Benefits:**
- Impossible to pass invalid data to domain models
- Errors caught at compile time
- Self-documenting code (types encode constraints)

#### 2. **Error Accumulation with ZIO Prelude**
```scala
// Parallel validation - collects ALL errors, not just first failure
val validated = idValidation
  .zipPar(nameValidation)
  .zipPar(probabilityValidation)
```

**Benefits:**
- Better UX (users see all errors at once)
- Functional approach (no exceptions)
- Composable validation logic

#### 3. **Recursive ADT for Risk Trees**
```scala
sealed trait RiskNode
case class RiskLeaf(...) extends RiskNode
case class RiskPortfolio(children: Array[RiskNode]) extends RiskNode
```

**Benefits:**
- Natural representation of hierarchical structures
- Type-safe pattern matching
- Compiler verifies exhaustive matching

#### 4. **Parallel Execution**
```scala
// ZIO structured concurrency for tree traversal
ZIO.collectAllPar(children.map(simulate)).withParallelism(8)

// Scala parallel collections for CPU-bound trials
successfulTrials.par.map(computeLoss).toVector
```

**Benefits:**
- Efficient utilization of multi-core CPUs
- Configurable parallelism
- Deterministic results (same seed = same output)

#### 5. **Clean Layer Dependency Injection**
```scala
val appLayer = ZLayer.make[RiskTreeController & Server](
  Server.default,
  RiskTreeRepositoryInMemory.layer,
  SimulationExecutionService.live,
  RiskTreeServiceLive.layer,
  ZLayer.fromZIO(RiskTreeController.makeZIO)
)
```

**Benefits:**
- Type-safe dependency resolution
- No manual wiring
- Testable (can swap implementations)

---

## Known Architectural Gaps

### 🔴 **Gap 1: DTO/Domain Model Separation**

**Current Problem:**
```scala
// ❌ Request DTO contains domain model directly
final case class RiskTreeDefinitionRequest(
  name: String,              // Plain String - not validated
  nTrials: Int,              // Plain Int - not validated
  root: RiskNode             // Domain model in DTO - tight coupling
)

// ❌ Domain model used in JSON serialization
final case class RiskTree(
  id: NonNegativeLong,       // Iron type
  name: SafeName.SafeName,   // Iron type
  nTrials: Int,
  root: RiskNode             // Domain model
)
```

**Issues:**
1. **Tight coupling:** HTTP layer depends on domain types
2. **Validation bypass risk:** JSON deserialization could bypass smart constructors
3. **API evolution:** Can't change domain model without breaking API
4. **Testing complexity:** Can't test HTTP serialization separately from domain logic

---

### **BCG Pattern Analysis**

The BCG (Business Case Generator) project uses a similar but simpler pattern:

**BCG Approach:**
```scala
// BCG Request: Plain types, no validation
final case class CreateLossModelRequest(
  val riskId: Long,
  val riskName: String,
  val probRiskOccurance: Double  // No validation here
)

// BCG Response: fromDomain() factory
object SimulationResponse {
  def fromSimulation(sim: Simulation): SimulationResponse = SimulationResponse(
    id = sim.id,
    name = sim.name.value,  // Extract from opaque type
    email = sim.email.value,
    ...
  )
}

// BCG Domain: Iron types, but public constructor
case class LossModel(
  val id: NonNegativeLong,
  val probRiskOccurance: Probability  // Iron refined type
)
```

**BCG Strengths:**
- ✅ Clean separation: DTOs use plain types, domain uses Iron
- ✅ `fromDomain()` factory methods for responses
- ✅ Explicit type boundaries (HTTP doesn't need to understand Iron)

**BCG Weaknesses:**
- ❌ No `toDomain()` methods - validation scattered in service layer
- ❌ No error accumulation - fails on first error
- ❌ Public domain constructors - can create invalid instances
- ❌ Easy to bypass validation accidentally

---

### **Improved Pattern (Risk Register v2)**

Combining BCG's separation with our ZIO Prelude validation:

```scala
// ✅ HTTP Layer: Plain DTOs + toDomain() with Validation
final case class RiskLeafRequest(
  id: String,
  name: String,
  distributionType: String,
  probability: Double,
  minLoss: Option[Long] = None,
  maxLoss: Option[Long] = None
)

object RiskLeafRequest {
  given codec: JsonCodec[RiskLeafRequest] = DeriveJsonCodec.gen
  
  // Converter: DTO → Domain (ACCUMULATES all errors)
  def toDomain(req: RiskLeafRequest): Validation[String, RiskLeaf] = {
    // Delegates to smart constructor - validation happens there
    RiskLeaf.create(
      id = req.id,
      name = req.name,
      distributionType = req.distributionType,
      probability = req.probability,
      minLoss = req.minLoss,
      maxLoss = req.maxLoss
    )
  }
}

// ✅ Domain Layer: Private constructor + smart constructor
final case class RiskLeaf private (
  safeId: SafeId.SafeId,
  safeName: SafeName.SafeName,
  ...
) extends RiskNode {
  // Public accessors return plain types
  override def id: String = safeId.value.toString
  override def name: String = safeName.value.toString
}

object RiskLeaf {
  // Smart constructor with error accumulation
  def create(...): Validation[String, RiskLeaf] = {
    idValidation.zipPar(nameValidation).zipPar(probValidation).map { ... }
  }
}

// ✅ Response: fromDomain() factory (same as BCG)
final case class RiskLeafResponse(
  id: String,
  name: String,
  distributionType: String,
  probability: Double,
  ...
)

object RiskLeafResponse {
  def fromDomain(leaf: RiskLeaf): RiskLeafResponse = RiskLeafResponse(
    id = leaf.id,        // Already String via accessor
    name = leaf.name,    // Already String via accessor
    ...
  )
}
```

**Key Improvements over BCG:**
| Aspect | BCG | Risk Register v2 |
|--------|-----|------------------|
| Request validation | Service layer (scattered) | `toDomain()` at boundary |
| Error handling | Fail-fast | Error accumulation |
| Domain constructors | Public | Private + smart constructor |
| Validation location | Multiple places | Single smart constructor |

**Why This Matters:**
- **API Stability:** Can change domain model without breaking API contracts
- **Validation Guarantee:** Impossible to create invalid domain models
- **Error UX:** Users see ALL validation errors at once
- **Testing:** Can test serialization, validation, and business logic independently
- **Single Source of Truth:** Validation logic in one place (smart constructor)

**Status:** **PLANNED** - Will be addressed in Phase 2 (see IMPLEMENTATION_PLAN.md)

---

### 🟡 **Gap 2: Configuration Externalization**

**Current Problem:**
```scala
// ❌ Hardcoded values scattered throughout codebase
nTrials: Int = 10000
val maxTreeDepth = 5
parallelism = java.lang.Runtime.getRuntime.availableProcessors()
```

**Issues:**
- Can't change settings without recompiling
- Different configs for dev/staging/prod not possible
- No validation of configuration values
- Can't override settings for testing

**Correct Pattern (from BCA project):**
```scala
// ✅ application.conf
register {
  server {
    host = "0.0.0.0"
    host = ${?SERVER_HOST}  // Override with env var
    port = 8080
    port = ${?SERVER_PORT}
  }
  
  simulation {
    defaultNTrials = 10000
    maxTreeDepth = 5
    defaultParallelism = 8
  }
  
  cors {
    allowedOrigins = ["http://localhost:3000"]
  }
}

// ✅ Type-safe config case classes
final case class ServerConfig(host: String, port: Int)
final case class SimulationConfig(
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int
)

// ✅ Automatic ZIO Config derivation
object Configs {
  def makeLayer[C: DeriveConfig: Tag](path: String): ZLayer[Any, Throwable, C] = {
    val pathArr = path.split("\\.")
    ZLayer.fromZIO(
      ZIO.config(deriveConfig[C].nested(pathArr.head, pathArr.tail*))
    )
  }
}

// ✅ Bootstrap with TypesafeConfigProvider
object Application extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      TypesafeConfigProvider.fromResourcePath()
    )
}
```

**Status:** **NEXT PRIORITY** - Phase 1 of implementation plan

---

### 🟡 **Gap 3: Structured Logging**

**Current Problem:**
```scala
// ❌ Minimal logging
ZIO.logInfo("Bootstrapping Risk Register application...")
ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
```

**Issues:**
- No request IDs (can't trace requests across logs)
- No user context (can't see who did what)
- No performance metrics (can't identify slow operations)
- Plain text logs (hard to parse/query)

**Correct Pattern:**
```scala
// ✅ Structured JSON logging with context
ZIO.logInfo("Risk tree created")(
  LogAnnotation.RequestId(requestId),
  LogAnnotation.UserId(userId),
  LogAnnotation.Duration(duration.toMillis),
  LogAnnotation.TreeId(treeId)
)

// Output:
// {
//   "timestamp": "2026-01-06T10:30:00Z",
//   "level": "INFO",
//   "message": "Risk tree created",
//   "requestId": "req-abc-123",
//   "userId": "user-456",
//   "duration": 234,
//   "treeId": 789
// }
```

**Status:** **PLANNED** - Phase 2 after Configuration

---

### 🟢 **Gap 4: User Context Extraction**

**Current Problem:**
- No user identity tracked
- No audit trail (who created/modified what)
- Not ready for Kubernetes authentication

**Intended Production Setup:**
```
┌─────────────────────────────────────────────────┐
│ Kubernetes Ingress (rate limiting, TLS)         │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Service Mesh (Istio/Linkerd)                    │
│  - JWT validation (Keycloak tokens)             │
│  - mTLS between services                         │
│  - Authorization policies (OPA)                  │
│  - Injects headers: X-User-Id, X-User-Roles     │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Risk Register Service (stateless)                │
│  - Trusts service mesh for authentication       │
│  - Reads user context from headers              │
│  - Propagates context through ZIO fiber refs    │
└─────────────────────────────────────────────────┘
```

**What Risk Register Needs:**
```scala
// ✅ Extract user from headers (service mesh provides these)
final case class UserContext(
  userId: String,
  roles: Set[String],
  tenantId: Option[String]
)

object HeaderExtractor {
  def extractUserContext(headers: Map[String, String]): UserContext = {
    UserContext(
      userId = headers.getOrElse("X-User-Id", "anonymous"),
      roles = headers.get("X-User-Roles").map(_.split(",").toSet).getOrElse(Set.empty),
      tenantId = headers.get("X-Tenant-Id")
    )
  }
}

// ✅ Propagate context through ZIO stack
val contextLayer: ZLayer[Any, Nothing, FiberRef[UserContext]] = ???

// ✅ Audit logging
ZIO.logInfo(s"User ${ctx.userId} created risk tree ${treeId}")
```

**Authentication Strategy:**
- ❌ **NOT building:** JWT parsing, OAuth2 flows, password handling
- ✅ **Delegate to:** Keycloak (IdP) + Service Mesh (enforcement) + OPA (policies)
- ✅ **Only building:** Header extraction + context propagation + audit logging

**Status:** **POSTPONED** - Not needed until Kubernetes deployment

---

### 🟢 **Gap 5: Rate Limiting**

**Current Problem:**
- No protection against resource exhaustion
- `/lec` endpoint is CPU-intensive (can run for seconds)
- Single user could DoS the service with many concurrent requests

**Multi-Layer Strategy:**

```
Layer 1: Infrastructure (Kubernetes Ingress)
  └─→ 100 req/sec per IP
  └─→ 1000 req/sec total
  └─→ (Handles network-level DoS)

Layer 2: Service Mesh (Istio/Linkerd)
  └─→ Connection limits
  └─→ Circuit breakers
  └─→ (Handles service-level overload)

Layer 3: Application (ZIO middleware) [FUTURE]
  └─→ 5 concurrent LEC computations per user
  └─→ 1 LEC per risk tree per minute (cache window)
  └─→ (Handles business logic limits)
```

**Application-Level Implementation (Future):**
```scala
trait RateLimiter {
  def checkLimit(key: String, limit: Int, window: Duration): Task[Boolean]
}

// Development: In-memory
class RateLimiterInMemory extends RateLimiter { ... }

// Production: Redis-backed
class RateLimiterRedis(redis: RedisClient) extends RateLimiter { ... }
```

**Testing Strategy:**
```scala
// Unit: Mock rate limiter
test("rejects when limit exceeded") {
  val limiter = MockRateLimiter.withLimit(5)
  // ...
}

// Integration: Testcontainers + real Redis
test("rate limiter persists across restarts") {
  withTestcontainers(redis, app) { ... }
}

// Load: K3s cluster + k6
test("handles 100 concurrent requests") {
  k6.run(scenario = "spike_test.js")
}
```

**Status:** **POSTPONED** - Start with infrastructure-level limits

---

## Technology Stack

### **Core Framework**
- **Scala:** 3.6.3
- **ZIO:** 2.1.24 (effect system, concurrency, dependency injection)
- **ZIO Prelude:** 1.0.0-RC44 (Validation, type classes)
- **ZIO Test:** 2.1.24 (property-based testing)

### **Type Safety**
- **Iron:** 3.2.1 (refinement types, opaque types)
- **Metalog Distribution:** Custom implementation

### **HTTP Layer**
- **Tapir:** 1.13.4 (endpoint definition)
- **ZIO HTTP:** (server implementation)
- **ZIO JSON:** 0.7.44 (serialization)

### **Configuration** (To Be Added)
- **ZIO Config:** 4.0.2
- **Typesafe Config:** 1.4.3

### **Logging** (To Be Enhanced)
- **ZIO Logging:** 2.2.4
- **Logback:** 1.5.23
- **ZIO Logging SLF4J Bridge:** 2.2.4

### **Testing**
- **ZIO Test:** Unit & property-based tests
- **ScalaCheck:** Property generators (via Iron)
- **Testcontainers:** (Planned - for integration tests)

### **Build Tools**
- **SBT:** 1.x
- **ScalaJS:** Cross-compilation for frontend

---

## Domain Model

### **Risk Tree Structure**

```
RiskNode (sealed trait)
├── RiskLeaf (terminal node - actual risk)
│   ├── id: SafeId
│   ├── name: SafeName
│   ├── distributionType: "expert" | "lognormal"
│   ├── probability: Probability (0.0 < p < 1.0)
│   ├── percentiles: Option[Array[Double]]  // Expert mode
│   ├── quantiles: Option[Array[Double]]    // Expert mode
│   ├── minLoss: Option[NonNegativeLong]    // Lognormal mode
│   └── maxLoss: Option[NonNegativeLong]    // Lognormal mode
│
└── RiskPortfolio (branch node - aggregation)
    ├── id: SafeId
    ├── name: SafeName
    └── children: Array[RiskNode]  // Recursive
```

### **Domain Entities**

```scala
// Configuration (persisted, no simulation results)
final case class RiskTree(
  id: NonNegativeLong,
  name: SafeName.SafeName,
  nTrials: Int,
  root: RiskNode
)

// Simulation Results (computed on-demand, not persisted)
final case class RiskTreeWithLEC(
  riskTree: RiskTree,
  quantiles: Map[String, Double],      // p50, p90, p95, p99
  vegaLiteSpec: Option[String],        // Visualization
  lecNode: Option[LECNode]             // Hierarchical results
)

// Metalog Distribution (compact representation)
final case class LossDistribution(
  coefficients: Array[Double],         // ~10 values instead of 10K samples
  lowerBound: Option[Double],
  upperBound: Option[Double]
)
```

**Key Design Decision: No Raw Trial Data Stored**
- ✅ Store: Metalog coefficients (~100 bytes)
- ❌ Don't store: Raw trial arrays (~80KB for 10K trials)
- **Benefit:** Compact storage, reproducible results, fast deserialization

---

## Layered Architecture

```
┌─────────────────────────────────────────────────┐
│ HTTP Layer (modules/server)                     │
│  - Controllers: Request/response handling       │
│  - Endpoints: Tapir route definitions           │
│  - Middleware: CORS, error handling             │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Service Layer (modules/server)                  │
│  - RiskTreeService: Business logic              │
│  - SimulationExecutionService: Orchestration    │
│  - Validation: Request validation               │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Domain Layer (modules/common)                   │
│  - RiskNode: ADT for risk trees                 │
│  - RiskTree: Aggregate root                     │
│  - Smart Constructors: Validation logic         │
│  - Iron Types: Refinement types                 │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Simulation Engine (modules/server)              │
│  - Simulator: Monte Carlo execution             │
│  - RiskSampler: Distribution sampling           │
│  - Metalog: Distribution fitting                │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Repository Layer (modules/server)               │
│  - RiskTreeRepository: Persistence interface    │
│  - InMemory: Development implementation         │
│  - (Future: PostgreSQL, DynamoDB)               │
└─────────────────────────────────────────────────┘
```

**Dependency Rules:**
- HTTP → Service → Domain ✅
- Service → Repository ✅
- Domain → Nothing (pure) ✅
- HTTP ❌→ Repository (bypass service)
- HTTP ❌→ Simulation (bypass service)

---

## Data Flow

### **1. Create Risk Tree (Config Only)**
```
POST /risk-trees

JSON Request
  ↓
RiskTreeDefinitionRequest (DTO)
  ↓ [Validation]
RiskTree (Domain)
  ↓ [Repository]
Persisted RiskTree
  ↓ [DTO Conversion]
SimulationResponse (without LEC)
```

### **2. Compute LEC (On-Demand)**
```
GET /risk-trees/:id/lec?nTrials=10000&depth=3

Load RiskTree from Repository
  ↓
Validate Parameters (nTrials, parallelism, depth)
  ↓
Simulator.simulateTree (recursive, parallel)
  │
  ├─→ RiskLeaf: Create Metalog → Sample trials → RiskResult
  │
  └─→ RiskPortfolio: Simulate children in parallel → Aggregate
  ↓
RiskTreeResult (hierarchical)
  ↓
Extract Quantiles (p50, p90, p95, p99)
  ↓
Generate Vega-Lite Spec
  ↓
RiskTreeWithLEC (Domain)
  ↓
SimulationResponse (with LEC)
```

**Key Insight:** Configuration (POST) and Computation (GET /lec) are separate.
- **POST:** Fast, synchronous, persists metadata
- **GET /lec:** Slow (seconds), asynchronous, computes results

---

## Validation Strategy

### **Layer 1: Iron Refinement Types (Compile-Time)**
```scala
type SafeId = String :| (Not[Blank] & MinLength[3] & MaxLength[30] & Match["^[a-zA-Z0-9_-]+$"])
```
- **When:** Compile time (if literal) or smart constructor call
- **What:** Primitive validation (length, format, range)
- **Error:** Compile error or `Validation[String, T]`

### **Layer 2: Smart Constructors (Business Rules)**
```scala
def create(
  id: String,
  distributionType: String,
  minLoss: Option[Long],
  maxLoss: Option[Long]
): Validation[String, RiskLeaf] = {
  // Cross-field validation
  if (distributionType == "lognormal" && minLoss.exists(_ >= maxLoss.getOrElse(0L)))
    Validation.fail("minLoss must be < maxLoss")
  else
    // ... refine and construct
}
```
- **When:** Domain object creation
- **What:** Business rules, cross-field validation
- **Error:** Accumulated via `Validation`

### **Layer 3: Service Layer (Orchestration)**
```scala
override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
  for {
    _ <- ZIO.fromEither(ValidationUtil.refinePositiveInt(req.nTrials, "nTrials"))
    safeName <- ZIO.fromEither(ValidationUtil.refineName(req.name))
    // req.root already validated by smart constructors during JSON deserialization
    persisted <- repo.create(riskTree)
  } yield persisted
}
```
- **When:** Request processing
- **What:** Coordinate validation, handle errors, orchestrate
- **Error:** `Task[A]` (ZIO effect type)

**Error Accumulation:**
```scala
// Parallel validation accumulates ALL errors
idValidation.zipPar(nameValidation).zipPar(probValidation)

// Result: Either success OR list of ALL validation failures
Left(Chunk("id too short", "name blank", "probability out of range"))
```

---

## Parallel Execution Model

### **Current Implementation: Hybrid Approach** ✅

#### **Level 1: ZIO Structured Concurrency (Tree Traversal)**
```scala
// Simulate children in parallel (IO-bound)
ZIO.collectAllPar(
  portfolio.children.map(child => simulateTreeInternal(child, ...))
).withParallelism(8)
```

**Benefits:**
- **Structured:** Parent waits for all children
- **Interruptible:** Can cancel mid-execution
- **Error handling:** Failures don't crash thread pool
- **Configurable:** `withParallelism(n)` controls concurrency

#### **Level 2: Scala Parallel Collections (Trial Computation)**
```scala
// Compute losses in parallel (CPU-bound)
val successfulTrials = trials.filter(_.occurred).par
val losses = successfulTrials.map { trial => computeLoss(trial) }.toVector
```

**Benefits:**
- **Efficient:** Leverages work-stealing thread pool
- **Simple:** Familiar collection API
- **Deterministic:** Same seed = same results

### **Proposed Migration: Pure ZIO** (Optional Future Enhancement)

```scala
// Replace .par.map with ZIO.foreachPar
ZIO.foreachPar(successfulTrials) { trial =>
  ZIO.attempt(computeLoss(trial))
}.withParallelism(parallelism)
```

**Additional Benefits:**
- **Interruptible:** Can cancel long simulations
- **Telemetry:** Trace parallel execution spans
- **Error recovery:** Better failure handling

**Status:** Optional - current implementation works well

---

## Testing Strategy

### **Current Test Coverage: 401 Tests**
- **Common Module:** 280 tests (domain, validation)
- **Server Module:** 121 tests (service, simulation, HTTP)

### **Test Pyramid**

```
           /\
          /  \  E2E Tests (Future: K8s + Testcontainers)
         /────\
        /      \  Integration Tests (Future: Redis, Postgres)
       /────────\
      /          \  Unit Tests (Current: 401 tests)
     /────────────\
    /              \  Property Tests (Current: Algebraic laws)
   /────────────────\
```

### **Test Categories**

#### **1. Property-Based Tests**
```scala
test("Identity law: x + 0 = x") {
  check(Gen.long) { loss =>
    val zero = Identity[Loss].identity
    Identity[Loss].combine(loss, zero) == loss
  }
}

test("Associativity: (a + b) + c = a + (b + c)") { ... }
test("Commutativity: a + b = b + a") { ... }
```

#### **2. Smart Constructor Tests**
```scala
test("rejects id too short (< 3 chars)") {
  val result = RiskLeaf.create(id = "ab", ...)
  assertTrue(result.isFailure)
}

test("accumulates all field validation errors") {
  val result = RiskLeaf.create(
    id = "x",              // Too short
    name = "",             // Empty
    probability = 2.0      // Out of range
  )
  result.toEither match {
    case Left(errors) =>
      assertTrue(
        errors.length >= 3,
        errors.mkString("; ").contains("id"),
        errors.mkString("; ").contains("name"),
        errors.mkString("; ").contains("prob")
      )
  }
}
```

#### **3. Simulation Tests**
```scala
test("determinism: same seed produces identical results") {
  val run1 = Simulator.simulate(samplers, nTrials = 500, parallelism = 2)
  val run2 = Simulator.simulate(samplers, nTrials = 500, parallelism = 2)
  assertTrue(run1.map(_.outcomes) == run2.map(_.outcomes))
}

test("parallel vs sequential produce identical results") {
  val seq = Simulator.simulateSequential(samplers, nTrials = 800)
  val par = Simulator.simulate(samplers, nTrials = 800, parallelism = 8)
  assertTrue(seq.map(_.outcomes).toSet == par.map(_.outcomes).toSet)
}
```

#### **4. Integration Tests** (Planned)
```scala
// Testcontainers for Redis rate limiting
test("rate limiter persists across restarts") {
  withTestcontainers(redis, app) { ... }
}

// Testcontainers for PostgreSQL repository
test("repository persists risk trees") {
  withTestcontainers(postgres) { ... }
}
```

#### **5. Load Tests** (Future)
```scala
// K3s cluster + k6 load testing
test("handles 100 concurrent requests") {
  k6.run(scenario = "spike_test.js")
}
```

---

## Future Roadmap

### **Intended Use Cases (Not Yet Implemented)**

#### **1. Iterative Tree Building**
```
User Flow:
1. Create initial tree (POST /risk-trees)
2. Compute LEC (GET /risk-trees/:id/lec)
3. Modify tree (PUT /risk-trees/:id)
4. Re-compute LEC
5. Compare results
```

**Technical Needs:**
- ✅ Already have: CRUD for risk trees
- 🔄 Future: Tree diff visualization
- 🔄 Future: Incremental recomputation (only changed branches)

#### **2. Visual Tree Navigation**
```
Frontend Features:
- Focus on a node (zoom in)
- Expand/collapse portfolio levels
- Drill down to leaf details
- Show LEC at each level
```

**Technical Needs:**
- ✅ Already have: Hierarchical LEC results (`LECNode` tree structure)
- 🔄 Future: Frontend implementation (React + D3.js)
- 🔄 Future: Memoization/caching of expanded nodes

#### **3. Scenario Analysis**
```
Workflow:
1. Baseline: Original risk tree
2. Scenario 1: Increase probability of cyber attack
3. Scenario 2: Add mitigation (reduces maxLoss)
4. Compare: Baseline vs Scenario 1 vs Scenario 2
```

**Technical Needs:**
- ✅ Already have: Can create multiple trees
- 🔄 Future: Scenario branching (fork from baseline)
- 🔄 Future: Visual comparison (side-by-side LECs)
- 🔄 Future: Delta calculation (quantile differences)

#### **4. Metalog-Based Reproducibility**
```
Persistence Strategy:
- Store: Metalog coefficients (~10 doubles)
- Don't store: Raw trial data (~10K doubles)
- Benefit: Compact, reproducible, fast
```

**Technical Needs:**
- ✅ Already have: Metalog distribution fitting
- ✅ Already have: Can reconstruct distribution from coefficients
- 🔄 Future: Store Metalog in database (not just in-memory)

### **Architectural Considerations for Future Features**

#### **No Streaming/Pagination Needed**
**Why?**
- ✅ Tree depth: Typically 3-5 levels (fits in memory)
- ✅ Tree size: Hundreds of nodes, not millions
- ✅ Metalog storage: ~100 bytes per node (compact)
- ✅ Frontend: Needs full tree for expand/collapse
- ✅ Scenarios: Need full results for comparison (can't compare partial data)

**What about large results?**
- ❌ Don't return raw trial data (10K doubles = 80KB) - use Metalog
- ❌ Don't paginate tree nodes - load full tree on demand
- ✅ Consider: WebSocket for real-time progress (optional)
- ✅ Consider: Caching computed LECs (memoization)

#### **Caching Strategy (Future)**
```scala
trait LECCache {
  def get(key: CacheKey): Task[Option[RiskTreeWithLEC]]
  def put(key: CacheKey, result: RiskTreeWithLEC): Task[Unit]
}

// Cache key: (treeId, nTrials, depth, treeHash)
// Invalidate: When tree structure changes
```

#### **Incremental Recomputation (Future)**
```
When user modifies a single node:
1. Identify affected subtree (node + ancestors)
2. Recompute only that branch (O(depth))
3. Aggregate up to root (O(log n))
4. Don't recompute sibling branches (cached)

Performance:
- Current: Full tree recomputation (O(n))
- Future: Incremental (O(log n))
- Benefit: 10x faster for small changes
```

---

## Deployment Architecture

### **Kubernetes Production Setup** (Intended)

```
┌─────────────────────────────────────────────────┐
│ Ingress Controller (nginx/traefik)              │
│  - TLS termination                               │
│  - Rate limiting (100 req/sec per IP)           │
│  - Path routing                                  │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Service Mesh (Istio/Linkerd)                    │
│  - mTLS between services                         │
│  - JWT validation (Keycloak)                    │
│  - Circuit breakers                              │
│  - Telemetry collection                         │
│  - Authorization (OPA policies)                  │
│  - Injects headers: X-User-Id, X-User-Roles     │
└─────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────┐
│ Risk Register Service (Deployment)               │
│  - Replicas: 3 (horizontal scaling)             │
│  - Resources: 1 CPU, 2Gi memory per pod         │
│  - Health checks: /health endpoint              │
│  - Stateless (can scale horizontally)           │
│  - Trusts service mesh for auth                 │
└─────────────────────────────────────────────────┘
      ↓                           ↓
┌──────────────────┐    ┌─────────────────────┐
│ PostgreSQL       │    │ Redis               │
│  - Risk trees    │    │  - Rate limiting    │
│  - Scenarios     │    │  - LEC cache        │
│  - Audit logs    │    │  - Session state    │
└──────────────────┘    └─────────────────────┘
```

### **Component Responsibilities**

| Component | Responsibility | Status |
|-----------|---------------|--------|
| **Ingress** | TLS, rate limiting, routing | Infrastructure |
| **Service Mesh** | Auth, authz, mTLS, telemetry | Infrastructure |
| **Keycloak** | Identity provider (OAuth2/OIDC) | Infrastructure |
| **OPA** | Policy decisions (RBAC) | Infrastructure |
| **Risk Register** | Business logic, simulation | **This Application** |
| **PostgreSQL** | Risk tree persistence | Future |
| **Redis** | Caching, rate limiting | Future |

### **What Risk Register Does NOT Do**
- ❌ JWT parsing/validation (service mesh does this)
- ❌ Password management (Keycloak does this)
- ❌ TLS termination (ingress does this)
- ❌ Policy enforcement (OPA does this)

### **What Risk Register DOES Do**
- ✅ Extract user context from headers (`X-User-Id`, `X-User-Roles`)
- ✅ Propagate context through ZIO fiber refs
- ✅ Audit logging (who did what, when)
- ✅ Business logic (risk simulation)
- ✅ Horizontal scaling (stateless design)

---

## Next Steps (Prioritized)

### **Phase 0: Documentation Update** 📝 (CURRENT)
- ✅ Create ARCHITECTURE.md (this document)
- ✅ Document current state
- ✅ Document known gaps
- ✅ Document future roadmap
- ⏭️ Create IMPLEMENTATION_PLAN.md

### **Phase 1: Configuration Management** ⭐⭐⭐⭐⭐
- Add ZIO Config dependencies
- Bootstrap TypesafeConfigProvider
- Create config case classes
- Externalize hardcoded values
- Add environment variable overrides
- **Estimate:** 1 day (~8 hours)

### **Phase 2: DTO/Domain Separation** ⭐⭐⭐⭐⭐
- Create separate DTO types for HTTP layer
- Add `toDomain()` / `fromDomain()` converters
- Move validation to smart constructors
- Update HTTP layer to use DTOs
- **Estimate:** 2 days (~16 hours)

### **Phase 3: Structured Logging** ⭐⭐⭐⭐
- Configure JSON logging (Logback)
- Add request ID generation
- Add context propagation (FiberRef)
- Add performance metrics
- **Estimate:** 1 day (~8 hours)

### **Phase 4: Telemetry Integration** ⭐⭐⭐ (Optional)
- Add ZIO Telemetry dependencies
- Configure OpenTelemetry exporter
- Add trace spans for key operations
- Add metrics (counters, histograms)
- **Estimate:** 1.5 days (~12 hours)

### **Phase 5: Pure ZIO Parallelism** ⭐⭐ (Optional)
- Replace Scala parallel collections
- Use ZIO.foreachPar for trial computation
- Add interruption support
- Verify performance (no regression)
- **Estimate:** 0.5 days (~4 hours)

### **Phase 6: Final Documentation Update** 📝
- Update ARCHITECTURE.md with changes
- Document new patterns
- Update diagrams
- **Estimate:** 0.5 days (~4 hours)

---

## Appendix

### **Key Architectural Decisions**

#### **ADR-001: Iron Refinement Types for Domain Models**
- **Decision:** Use Iron opaque types for all validated domain primitives
- **Rationale:** Compile-time safety, zero runtime overhead, self-documenting
- **Status:** ✅ Implemented
- **Alternatives Rejected:** Runtime validation only, custom wrapper types

#### **ADR-002: ZIO Prelude for Error Accumulation**
- **Decision:** Use `Validation[String, A]` for parallel error collection
- **Rationale:** Better UX (all errors at once), functional, composable
- **Status:** ✅ Implemented
- **Alternatives Rejected:** Fail-fast Either, exceptions

#### **ADR-003: Separate Config from Computation**
- **Decision:** POST creates config, GET /lec computes results
- **Rationale:** Config is fast/synchronous, computation is slow/expensive
- **Status:** ✅ Implemented
- **Alternatives Rejected:** POST runs simulation immediately

#### **ADR-004: Metalog Storage (No Raw Trials)**
- **Decision:** Store Metalog coefficients, not raw trial data
- **Rationale:** 1000x size reduction, reproducible, fast serialization
- **Status:** ✅ Implemented
- **Alternatives Rejected:** Store raw trials, store histograms

#### **ADR-005: Hybrid Parallelism (ZIO + Par Collections)**
- **Decision:** ZIO for IO-bound, Scala par collections for CPU-bound
- **Rationale:** Best of both worlds, proven to work well
- **Status:** ✅ Implemented
- **Future:** May migrate fully to ZIO for better control

#### **ADR-006: Delegate Auth to Service Mesh** (Future)
- **Decision:** Trust Keycloak + service mesh for authentication
- **Rationale:** Separation of concerns, standard K8s pattern
- **Status:** 🔄 Planned
- **Alternatives Rejected:** Implement OAuth2 in-app

---

**Document Status:** Living document, updated with each phase
**Last Review:** January 6, 2026
**Next Review:** After Phase 1 (Configuration) completion
