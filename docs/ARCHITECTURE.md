# Risk Register - Architecture Documentation

**Last Updated:** January 8, 2026  
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
14. [Appendix A: HDR Histogram for Million-Scale Trials](#appendix-a-hdr-histogram-for-million-scale-trials)
15. [Appendix B: ZIO Metrics Bridge for Runtime Observability](#appendix-b-zio-metrics-bridge-for-runtime-observability)

---

## Executive Summary

**Risk Register** is a functional Scala application for hierarchical Monte Carlo risk simulation. It enables users to model complex risk portfolios as trees, execute simulations, and analyze Loss Exceedance Curves (LECs).

**Current State:**
- ✅ 121 tests passing (simulation engine and service layer)
- ✅ Core simulation engine functional with parallel execution
- ✅ Type-safe domain model using Iron refinement types
- ✅ Clean separation of concerns (domain/service/HTTP layers)
- ✅ Typed error codes with field path context (ValidationErrorCode)
- ✅ BuildInfo integration for version management
- ✅ Sparse storage architecture validated and optimized
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
  RiskResultResolverLive.layer,
  TreeCacheManager.layer,
  RiskTreeServiceLive.layer,
  ZLayer.fromZIO(RiskTreeController.makeZIO)
)
```

**Benefits:**
- Type-safe dependency resolution
- No manual wiring
- Testable (can swap implementations)

---

## Architectural Considerations

### **DTO/Domain Model Separation** (Implemented)

**Status:** ✅ Complete (see IMPLEMENTATION_PLAN.md Phase 2)

**Approach:** Validation-during-parsing with private intermediate DTOs

**Key Design Points:**
- Request DTOs use plain types (String, Double, Long)
- Custom JSON decoders validate via smart constructors during deserialization  
- Private domain constructors enforce validation
- Response DTOs use `fromDomain()` factories
- Error accumulation collects all validation errors in one pass
- Field paths use ID-based format (e.g., `"riskLeaf[id=cyber].probability"`)

**Benefits over alternatives:**
- API stability: Domain model changes don't break HTTP contracts
- Validation guarantee: Impossible to bypass smart constructors
- Better UX: Users see all errors at once, not fail-fast
- Testability: Can test HTTP, validation, and business logic independently

---

### **Structured Logging** (Partially Implemented)

**Status:** 🔄 Phase 3 - 30% complete (see IMPLEMENTATION_PLAN.md)

**Current:** Basic ZIO logging integrated, JSON output and request context not yet configured

**Intended:** JSON-formatted logs with request IDs, user context, and performance metrics for production observability.

---

### **User Context Extraction** (Future)

**Status:** Planned for Kubernetes deployment

**Strategy:** Trust service mesh for authentication, extract user context from injected headers (`X-User-Id`, `X-User-Roles`), propagate via ZIO FiberRef for audit logging.

---

### **Rate Limiting** (Future)

**Status:** Delegated to infrastructure (Kubernetes Ingress + Service Mesh)

**Strategy:** Multi-layer approach with infrastructure handling network/service-level limits, application handling business logic limits (e.g., concurrent LEC computations per user).

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
  id: TreeId,                          // ULID-based nominal wrapper
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],                // Flat collection (not nested root)
  rootId: NodeId,                      // Root node reference
  index: TreeIndex                     // O(1) node lookup by NodeId
)

// Node identity uses nominal wrapper (ADR-018)
case class NodeId(toSafeId: SafeId.SafeId)
case class TreeId(toSafeId: SafeId.SafeId)

// Simulation results are per-node, cached in-memory
final case class RiskResult(
  nodeId: NodeId,
  outcomes: Map[TrialId, Loss],
  provenances: List[NodeProvenance]
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

### **Sparse Storage: Current Implementation Benefits**

The current sparse storage approach using `Map[TrialId, Loss]` provides excellent scaling characteristics:

- ✅ **Handles 10k trials efficiently today** - Sparse maps with 100-5000 entries (typical for low-probability events)
- ✅ **Scales to 1M trials with more cores/memory** - Parallel collections leverage multi-core CPUs without architectural changes
- ✅ **Scales to 100M trials with distributed workers** - Referential transparency enables trivial distribution across compute nodes
- ✅ **Preserves exact aggregation semantics at any scale** - Trial-level merge maintains mathematical correctness for portfolio aggregation
- ✅ **Maintains provenance/reproducibility guarantees** - Complete trial-by-trial reconstruction from stored metadata

**Why sparse storage works well:**
- **Memory efficiency for low-probability events:** A 0.01 probability risk with 10,000 trials stores ~100 map entries (~1.6KB), not 10,000 entries
- **Parallel-friendly:** Pure functions enable lock-free parallelization at both trial and risk levels
- **Identity-based aggregation:** Associative merge operation scales to arbitrary tree depths without recomputation
- **Deterministic parallelism:** HDR-based PRNG ensures identical results regardless of parallelization strategy

See [Appendix A: HDR Histogram for Million-Scale Trials](#appendix-a-hdr-histogram-for-million-scale-trials) for detailed comparison with alternative approaches.

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
│  - RiskResultResolver: Simulation orchestration │
│  - TreeCacheManager: Result caching             │
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
GET /risk-trees/:treeId/nodes/:nodeId/lec

Load RiskTree from Repository
  ↓
RiskResultResolver.ensureCached(tree, nodeId)
  │
  ├─→ Cache hit: Return cached RiskResult
  │
  └─→ Cache miss:
      ├─→ RiskLeaf: Create Metalog → Sample trials → RiskResult
      └─→ RiskPortfolio: Simulate children in parallel → Aggregate
  ↓
RiskResult (per-node, cached in TreeCacheManager)
  ↓
LECGenerator.generateCurvePoints
  ↓
LECCurveResponse (JSON)
```

**Key Insight:** Configuration (POST) and Computation (GET /lec) are separate.
- **POST:** Fast, synchronous, persists tree definition
- **GET /lec:** Computes per-node, caches results, incremental recomputation

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
// Ensure child results are cached (parallel)
ZIO.foreachPar(
  childIds.map(childId => resolver.ensureCached(tree, childId))
)
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

**Status:** Optional - current implementation provides excellent performance characteristics

---

## Testing Strategy

### **Current Test Coverage: 121 Tests**
- **Server Module:** 121 tests (service, simulation, HTTP, provenance)
- **Focus:** Simulation determinism, parallel execution, tree aggregation

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
- ✅ Already have: Per-node LEC via `LECCurveResponse` (flat `childIds` structure)
- 🔄 Future: Frontend implementation (Scala.js + Laminar + Vega-Lite)
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

**Handling large result sets:**
- ❌ Raw trial data not returned (10K doubles = 80KB) - Metalog representation used instead
- ❌ Tree node pagination not required - full tree loaded on demand
- ✅ Future consideration: WebSocket for real-time progress updates
- ✅ Future consideration: Caching computed LECs via memoization

#### **Caching Strategy** ✅ (Implemented)
```scala
// TreeCacheManager: per-tree cache lifecycle
trait TreeCacheManager:
  def cacheFor(treeId: TreeId): UIO[RiskResultCache]
  def onTreeStructureChanged(treeId: TreeId): UIO[Unit]
  def deleteTree(treeId: TreeId): UIO[Unit]

// RiskResultCache: per-node result cache
trait RiskResultCache:
  def get(nodeId: NodeId): UIO[Option[RiskResult]]
  def put(nodeId: NodeId, result: RiskResult): UIO[Unit]

// Cache key: NodeId (ULID)
// Invalidate: onTreeStructureChanged clears all nodes for that tree
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

## Implementation Status

**For detailed phase tracking, task lists, and implementation plans, see `IMPLEMENTATION_PLAN.md`.**

Key completed work:
- ✅ Configuration management (ZIO Config with application.conf)
- ✅ DTO/Domain separation (validation-during-parsing)
- ✅ Error handling (typed error codes, field paths)
- 🔄 Structured logging (basic ZIO logging, JSON output pending)

---

## Appendix A: HDR Histogram for Million-Scale Trials

**Date:** January 8, 2026  
**Decision:** High Dynamic Range (HDR) Histogram approach evaluated and determined to be **Not Applicable**  
**Status:** ✅ Sparse storage validated as optimal for this use case

> **Note:** This appendix discusses **HDR Histogram** (a data structure for recording value distributions), not to be confused with **HDR PRNG** (the deterministic pseudo-random number generator used elsewhere in this codebase for reproducible sampling).

### What is HDR Histogram?

High Dynamic Range (HDR) Histogram is a space-efficient data structure for recording and analyzing value distributions. It uses logarithmic bucketing to compress millions of observations into a fixed-size histogram (~10-50KB) while maintaining configurable precision.

**Typical benefits:**
- Constant memory footprint regardless of sample count
- O(1) percentile queries
- Histogram merging for distributed aggregation
- Efficient serialization for persistence

### Why HDR Histogram Was Considered

For large-scale Monte Carlo simulations (1M+ trials), memory consumption of sparse storage could become a concern:
- Current sparse map: 1M trials × 50% probability × 12 bytes/entry = ~6MB per risk
- HDR histogram: Fixed ~10-50KB regardless of trial count

This appears to offer a 100x memory reduction for high-volume simulations.

### Why HDR Histogram Is Not Used

**Critical incompatibility with trial-level aggregation:**

The current architecture requires exact trial-by-trial summation for portfolio aggregation:

```scala
// Required aggregation semantics:
val allTrialIds = Set(0, 1, 2, ..., 9999)
allTrialIds.map { trialId =>
  trialId -> (
    riskA.outcomeOf(trialId) + 
    riskB.outcomeOf(trialId) + 
    riskC.outcomeOf(trialId)
  )
}
```

HDR histograms cannot provide `outcomeOf(trialId)` - they only know "bucket 5000-6000 had 47 occurrences". This breaks:

1. **Exact aggregation:** Cannot sum losses for specific trial IDs across risks
2. **Identity-based composition:** `Identity[RiskResult].combine` requires trial-level granularity
3. **Hierarchical merge:** Bottom-up tree aggregation depends on matching trial sequences
4. **Provenance/reproducibility:** Cannot reconstruct exact trial outcomes from bucketed histogram

### Technical Comparison

| Aspect | Sparse Storage (Current) | HDR Histogram (Rejected) |
|--------|-------------------------|-------------------------|
| **Memory (10k trials, p=0.01)** | ~1.6KB (100 entries) | ~10KB (fixed overhead) |
| **Memory (1M trials, p=0.5)** | ~6MB (500k entries) | ~50KB (compressed) |
| **Trial-level access** | ✅ O(1) lookup by trialId | ❌ Not supported |
| **Exact aggregation** | ✅ Trial-wise summation | ❌ Approximate merge |
| **Parallel scaling** | ✅ Lock-free pure functions | ⚠️ Synchronized updates |
| **Distributed compute** | ✅ Partition trials across workers | ❌ Loses trial alignment |
| **Provenance** | ✅ Full trial reconstruction | ❌ Lossy compression |
| **Percentile queries** | ✅ O(log n) via lazy TreeMap | ✅ O(1) from histogram |

### Alternative Scaling Path: Horizontal Distribution

Instead of algorithmic compression (HDR), the current architecture scales horizontally through referential transparency:

```scala
// Each worker computes disjoint trial range
workers.map { case (worker, trialRange) =>
  worker.execute {
    val sampler = RiskSampler.fromDistribution(...)
    trialRange.map(t => t -> sampler.sampleLoss(t)).toMap
  }
}.reduce { (mapA, mapB) =>
  // Merge disjoint maps - O(n) but no trial overlap
  mapA ++ mapB
}
```

**Benefits of horizontal scaling:**
- Pure functions enable trivial distribution (no coordination)
- Deterministic PRNG allows any trial partitioning strategy  
- Associative merge preserves exact aggregation semantics
- Works with current codebase (zero architectural changes)

### Conclusion

Sparse storage using `Map[TrialId, Loss]` is the optimal approach because:

1. **Sparse efficiency:** Already memory-efficient for typical low-probability events
2. **Exact semantics:** Preserves mathematical correctness of portfolio aggregation
3. **Horizontal scaling:** Achieves 100M+ trial scale through distribution, not approximation
4. **Provenance intact:** Maintains full reproducibility guarantees
5. **Architecture simplicity:** No need for storage abstraction layer or mode switching

HDR Histogram would require fundamental architecture changes (approximate merge, loss of provenance) for questionable memory savings at scales the application is unlikely to encounter.

**Decision:** Mark HDR Histogram as "Evaluated - Not Applicable" and continue with sparse storage approach.

---

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

## Appendix B: ZIO Metrics Bridge for Runtime Observability

**Status:** Available but not yet implemented  
**Priority:** Low (nice-to-have)

### Context

The current telemetry implementation uses OpenTelemetry directly for:
- **Tracing** - Distributed spans via `Tracing` service
- **Metrics** - Business metrics via `Meter` service (counters, histograms)

ZIO has built-in runtime metrics (fiber counts, execution times, etc.) that are separate from our OpenTelemetry instrumentation.

### Technical Option

zio-telemetry provides `OpenTelemetry.zioMetrics` layer that bridges ZIO's internal metrics to OpenTelemetry exporters. Combined with `DefaultJvmMetrics`, this provides comprehensive runtime observability with zero additional instrumentation code.

### Implementation

```scala
// In Application.scala appLayer, add:
import zio.metrics.jvm.DefaultJvmMetrics

val appLayer: ZLayer[Any, Throwable, ...] =
  ZLayer.make[...](
    // ... existing layers ...
    
    // Optional: Bridge ZIO runtime metrics to OpenTelemetry
    OpenTelemetry.zioMetrics,
    
    // Optional: JVM metrics (heap, GC, threads)
    DefaultJvmMetrics.liveV2.unit
  )
```

### Metrics Provided

| Category | Metrics |
|----------|---------|
| **JVM Heap** | `jvm.memory.used`, `jvm.memory.committed`, `jvm.memory.max` |
| **GC** | `jvm.gc.duration`, `jvm.gc.count` |
| **Threads** | `jvm.threads.count`, `jvm.threads.daemon` |
| **ZIO Runtime** | Fiber execution times, fiber counts (when enabled) |

### Trade-offs

| Pros | Cons |
|------|------|
| Zero instrumentation code | Additional telemetry volume |
| Standard JVM metrics | May clutter dashboards |
| Useful for capacity planning | Requires filtering in observability backend |
| Free with existing OpenTelemetry setup | Minor runtime overhead |

### Recommendation

**Defer until production deployment.** JVM metrics are most valuable when:
1. Running in containers with resource limits
2. Debugging memory leaks or GC pressure
3. Capacity planning for scaling decisions

For development, the current business metrics (operations counter, simulation duration, trials counter) provide sufficient observability.

---

**Document Status:** Architecture reference (stable)
**Last Updated:** January 8, 2026
**Implementation Tracking:** See IMPLEMENTATION_PLAN.md
