# Pre-Containerization Hardening Plan

Production-readiness improvements before containerization. All implementations follow functional programming best practices with ZIO and maintain category-theory rooted design.

---

## Overview

| Item | Status | Priority | Complexity |
|------|--------|----------|------------|
| 1. Health Endpoint Enhancement | ✅ Exists | P1 | Low |
| 2. Semaphore-Based Concurrency Control | ⏳ Needed | P1 | Medium |
| 3. API Limits Configuration | ⏳ Needed | P2 | Low |
| 4. Document API Constraints | ⏳ Needed | P2 | Low |
| 5. Chunked Trial Processing | ⏳ Needed | P2 | Medium |
| 6. Memory-Aware Parallelism | ⏳ Needed | P3 | High |

---

## 1. Health Endpoint Enhancement

### Current State
```scala
// RiskTreeController.scala
val health: ServerEndpoint[Any, Task] = healthEndpoint.serverLogicSuccess { _ =>
  ZIO.succeed(Map("status" -> "healthy", "service" -> "risk-register"))
}
```

### Assessment
- **Basic health check exists** at `/health`
- Returns static JSON: `{"status": "healthy", "service": "risk-register"}`
- **Sufficient for Phase 1 containerization** (Docker health checks)

### Optional Enhancement (Phase 2+)
When adding a database or external dependencies, enhance to:
```scala
// Future: HealthService with dependency checks
trait HealthService {
  def check: UIO[HealthStatus]
}

case class HealthStatus(
  status: String,          // "healthy" | "degraded" | "unhealthy"
  version: String,         // BuildInfo.version
  uptime: Duration,        // Since startup
  checks: Map[String, ComponentHealth]  // Future: db, cache, etc.
)
```

### Action
- **No immediate changes required**
- Mark as complete for pre-containerization

---

## 2. Semaphore-Based Concurrency Control

### Problem
Currently, unlimited concurrent simulations can overwhelm server resources:
- Each simulation spawns `parallelism` fibers (default: 8)
- Each fiber uses parallel collections for trial computation
- 10 concurrent requests × 8 fibers × CPU-bound work = resource exhaustion

### Functional Design

#### 2.1 Configuration Extension
```scala
// configs/SimulationConfig.scala
final case class SimulationConfig(
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int,
  maxConcurrentSimulations: Int  // NEW: Global concurrency limit
)
```

#### 2.2 Semaphore as ZLayer Resource

**Key Principle**: Semaphore is a shared resource → model as ZLayer for proper lifecycle management.

```scala
// services/SimulationSemaphore.scala
package com.risquanter.register.services

import zio.*

/**
 * Simulation concurrency control using ZIO Semaphore.
 * 
 * Provides backpressure for compute-intensive Monte Carlo simulations.
 * Wraps ZIO Semaphore in a newtype for:
 *   - Type safety (distinguish from other semaphores)
 *   - Encapsulated access pattern (withPermit)
 * 
 * Thread-safety: ZIO Semaphore is fully concurrent and non-blocking.
 * Fairness: FIFO ordering for permit acquisition.
 */
trait SimulationSemaphore {
  /**
   * Execute effect while holding a simulation permit.
   * Semantics: acquire → run → release (guaranteed via bracket)
   * 
   * @tparam R Environment
   * @tparam E Error type
   * @tparam A Result type
   * @param effect Effect to run with permit
   * @return Effect that acquires permit, runs, then releases
   */
  def withPermit[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A]
  
  /** Current number of available permits (for monitoring) */
  def available: UIO[Long]
}

object SimulationSemaphore {
  
  /**
   * Live implementation backed by ZIO Semaphore.
   * 
   * Construction is effectful (creates Semaphore), so exposed as ZLayer.
   */
  private class Live(semaphore: Semaphore) extends SimulationSemaphore {
    def withPermit[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A] =
      semaphore.withPermit(effect)
    
    def available: UIO[Long] = semaphore.available
  }
  
  /**
   * ZLayer that creates SimulationSemaphore from SimulationConfig.
   * Semaphore is created once at layer construction.
   */
  val layer: ZLayer[SimulationConfig, Nothing, SimulationSemaphore] =
    ZLayer {
      for {
        config <- ZIO.service[SimulationConfig]
        semaphore <- Semaphore.make(config.maxConcurrentSimulations.toLong)
      } yield new Live(semaphore): SimulationSemaphore
    }
  
  /**
   * Test layer with configurable permits.
   * Useful for testing backpressure behavior.
   */
  def test(permits: Long): ZLayer[Any, Nothing, SimulationSemaphore] =
    ZLayer.fromZIO(Semaphore.make(permits).map(new Live(_)))
}
```

#### 2.3 Integration into RiskTreeServiceLive

```scala
// Modify RiskTreeServiceLive constructor signature
class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  executionService: SimulationExecutionService,
  config: SimulationConfig,
  tracing: Tracing,
  simulationSemaphore: SimulationSemaphore,  // NEW
  operationsCounter: Counter[Long],
  simulationDuration: Histogram[Double],
  trialsCounter: Counter[Long]
) extends RiskTreeService {
  
  // In computeLEC method, wrap simulation with permit
  override def computeLEC(
    id: Long,
    nTrialsOverride: Option[Int],
    parallelism: Int,
    depth: Int,
    includeProvenance: Boolean
  ): Task[RiskTreeWithLEC] = {
    val operation = tracing.span("computeLEC") {
      for {
        riskTree <- repo.getById(id).someOrFail(...)
        // Acquire permit for simulation phase only
        result <- simulationSemaphore.withPermit {
          executionService.execute(riskTree.root, nTrials, parallelism, depth, includeProvenance)
        }
        _ <- recordSimulationMetrics(...)
      } yield result
    }
    
    operation.onExit(...)  // Error handling as before
  }
}
```

#### 2.4 Layer Composition Update

```scala
// Update layer definition in RiskTreeServiceLive companion
object RiskTreeServiceLive {
  val layer: ZLayer[
    RiskTreeRepository & SimulationExecutionService & SimulationConfig 
      & Tracing & SimulationSemaphore & Meter,  // Added SimulationSemaphore
    Throwable,
    RiskTreeService
  ] = ZLayer {
    for {
      // ... existing service retrieval
      semaphore <- ZIO.service[SimulationSemaphore]
      // ... metrics creation
    } yield new RiskTreeServiceLive(
      repo, executionService, config, tracing, 
      semaphore,  // Pass semaphore
      operationsCounter, simulationDuration, trialsCounter
    )
  }
}
```

### Why ZIO Semaphore?

| Property | Benefit |
|----------|---------|
| **Non-blocking** | Suspends fibers, doesn't block threads |
| **FIFO fairness** | Requests served in order (no starvation) |
| **Composable** | `withPermit` is a combinator → works with any effect |
| **Bracket semantics** | Permit release guaranteed even on failure |
| **Backpressure** | Natural flow control without explicit queuing |

### Recommended Default
```scala
maxConcurrentSimulations = 4  // On 8-core container
```
Rationale: Each simulation uses `parallelism=8` internally, so 4 × 8 = 32 concurrent fiber computations is reasonable.

---

## 3. API Limits Configuration

### Current Defaults (SimulationConfig)
```scala
defaultNTrials: Int       = 10000
maxTreeDepth: Int         = 5
defaultParallelism: Int   = 8
```

### Proposed Additions

```scala
// configs/SimulationConfig.scala
final case class SimulationConfig(
  // Existing
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int,
  
  // NEW: Hard limits (enforced server-side)
  maxNTrials: Int,                 // Max trials per simulation (e.g., 1_000_000)
  maxConcurrentSimulations: Int,   // Global concurrency limit
  maxParallelism: Int,             // Max fibers per simulation
  
  // NEW: Soft limits (warnings in logs)
  warnTrialsThreshold: Int,        // Log warning above this (e.g., 100_000)
  warnTreeDepthThreshold: Int      // Log warning above this (e.g., 3)
)
```

### Validation in Service Layer

```scala
// In RiskTreeServiceLive.computeLEC
private def validateRequestLimits(
  nTrials: Int,
  parallelism: Int,
  depth: Int
): IO[ValidationFailed, Unit] = {
  val errors = List(
    Option.when(nTrials > config.maxNTrials)(
      ValidationError("nTrials", ValidationErrorCode.OUT_OF_RANGE, 
        s"nTrials ($nTrials) exceeds maximum (${config.maxNTrials})")
    ),
    Option.when(parallelism > config.maxParallelism)(
      ValidationError("parallelism", ValidationErrorCode.OUT_OF_RANGE,
        s"parallelism ($parallelism) exceeds maximum (${config.maxParallelism})")
    ),
    Option.when(depth > config.maxTreeDepth)(
      ValidationError("depth", ValidationErrorCode.OUT_OF_RANGE,
        s"depth ($depth) exceeds maximum (${config.maxTreeDepth})")
    )
  ).flatten
  
  ZIO.when(errors.nonEmpty)(ZIO.fail(ValidationFailed(errors))).unit
}

private def warnIfHighUsage(nTrials: Int, depth: Int): UIO[Unit] =
  ZIO.when(nTrials > config.warnTrialsThreshold)(
    ZIO.logWarning(s"High trial count requested: $nTrials (threshold: ${config.warnTrialsThreshold})")
  ) *>
  ZIO.when(depth > config.warnTreeDepthThreshold)(
    ZIO.logWarning(s"Deep tree computation requested: depth=$depth")
  )
```

---

## 4. Document API Constraints

### Add Scaladoc to Endpoint Definitions

```scala
// RiskTreeEndpoints.scala
/** Compute Loss Exceedance Curve for risk tree.
  * 
  * === Query Parameters ===
  * - `nTrials`: Override default trial count (default: 10,000, max: 1,000,000)
  * - `parallelism`: Parallel fibers for simulation (default: 1, max: 16)
  * - `depth`: Tree depth to include (0=root only, max: 5)
  * - `includeProvenance`: Include reproducibility metadata (default: false)
  * 
  * === Performance Characteristics ===
  * - Typical latency: 100ms (10K trials) to 10s (1M trials)
  * - Memory: ~1MB per 100K trials
  * - Concurrency: Limited by server semaphore (max 4 concurrent)
  * 
  * === Error Responses ===
  * - 400 Bad Request: Parameter validation failed
  * - 404 Not Found: Risk tree ID not found
  * - 503 Service Unavailable: Server overloaded (retry with backoff)
  */
val computeLECEndpoint = ...
```

### Create API Documentation File

```markdown
# docs/API_LIMITS.md

## Risk-Register API Limits

### Computation Limits

| Parameter | Default | Maximum | Notes |
|-----------|---------|---------|-------|
| nTrials | 10,000 | 1,000,000 | Monte Carlo trial count |
| parallelism | 8 | 16 | Concurrent fibers per simulation |
| depth | 0 | 5 | Tree traversal depth |

### Concurrency Limits

| Limit | Value | Notes |
|-------|-------|-------|
| Max concurrent simulations | 4 | Global server limit |
| Request timeout | 60s | Single simulation max duration |

### Memory Estimates

| nTrials | Approx. Memory | Latency |
|---------|----------------|---------|
| 10,000 | ~100KB | <100ms |
| 100,000 | ~1MB | ~1s |
| 1,000,000 | ~10MB | ~10s |
```

---

## 5. Chunked Trial Processing

### Problem
Large trial counts create memory pressure:
- 1M trials × sparse storage still allocates significant Maps
- All trials computed before any aggregation
- GC pressure from large intermediate collections

### Functional Design: Streaming Aggregation

#### 5.1 Chunk-Based Trial Execution

```scala
// services/helper/Simulator.scala

/**
 * Chunked trial processing with streaming aggregation.
 * 
 * Design:
 * - Split trials into chunks of chunkSize
 * - Process each chunk, aggregate via Identity.combine
 * - Constant memory regardless of total trial count
 * 
 * Category theory: This is a fold over chunks using RiskResult monoid.
 */
def performTrialsChunked(
  sampler: RiskSampler,
  nTrials: Int,
  chunkSize: Int = 10_000
): Task[RiskResult] = {
  val chunks = (0 until nTrials).grouped(chunkSize).toVector
  
  // Process chunks sequentially, accumulate via monoid
  ZIO.foldLeft(chunks)(RiskResult.empty(sampler.id, nTrials)) { (acc, chunk) =>
    ZIO.attempt {
      val chunkTrials = chunk.view
        .filter(trial => sampler.sampleOccurrence(trial.toLong))
        .map(trial => (trial, sampler.sampleLoss(trial.toLong)))
        .toMap
      
      // Combine sparse maps (union semantics)
      acc.combineChunk(chunkTrials)
    }
  }
}
```

#### 5.2 RiskResult Extension

```scala
// domain/data/RiskResult.scala

object RiskResult {
  /** Empty RiskResult for fold initialization */
  def empty(name: String, nTrials: Int): RiskResult = 
    RiskResult(name, Map.empty, nTrials)
}

// Add to RiskResult case class
case class RiskResult(...) {
  /** Combine chunk of trials into existing result.
    * More efficient than full combine - avoids re-allocating base map.
    */
  def combineChunk(chunkTrials: Map[TrialId, Loss]): RiskResult =
    copy(outcomes = outcomes ++ chunkTrials)
}
```

#### 5.3 Configuration

```scala
// SimulationConfig additions
chunkSize: Int = 10_000  // Trials per chunk (balance: memory vs. overhead)
```

### Benefits

| Aspect | Before (Batch) | After (Chunked) |
|--------|----------------|-----------------|
| Memory | O(nTrials) | O(chunkSize) working set |
| GC pressure | Large spike at end | Steady, small collections |
| Progress | All-or-nothing | Incremental (future: progress API) |

---

## 6. Memory-Aware Parallelism

### Problem
Static parallelism doesn't adapt to:
- Container memory limits
- Current heap utilization
- Workload characteristics

### Functional Design: Adaptive Parallelism

#### 6.1 Memory Monitoring Service

```scala
// services/MemoryMonitor.scala
package com.risquanter.register.services

import zio.*

/**
 * Memory monitoring for adaptive resource management.
 * 
 * Provides heap utilization metrics without side effects.
 * Readings are point-in-time snapshots (no caching needed).
 */
trait MemoryMonitor {
  /** Current heap utilization as fraction [0.0, 1.0] */
  def heapUtilization: UIO[Double]
  
  /** Available heap in bytes */
  def availableHeap: UIO[Long]
}

object MemoryMonitor {
  
  private class Live extends MemoryMonitor {
    private val runtime = Runtime.getRuntime
    
    def heapUtilization: UIO[Double] = ZIO.succeed {
      val max = runtime.maxMemory()
      val used = runtime.totalMemory() - runtime.freeMemory()
      used.toDouble / max.toDouble
    }
    
    def availableHeap: UIO[Long] = ZIO.succeed {
      val max = runtime.maxMemory()
      val used = runtime.totalMemory() - runtime.freeMemory()
      max - used
    }
  }
  
  val layer: ULayer[MemoryMonitor] = ZLayer.succeed(new Live)
}
```

#### 6.2 Adaptive Parallelism Calculator

```scala
// services/helper/AdaptiveParallelism.scala
package com.risquanter.register.services.helper

import zio.*
import com.risquanter.register.services.MemoryMonitor
import com.risquanter.register.configs.SimulationConfig

/**
 * Computes adaptive parallelism based on memory and workload.
 * 
 * Strategy:
 * - Base: Use requested parallelism from config/request
 * - Memory pressure: Reduce parallelism as heap fills
 * - Workload: Reduce for very large trial counts
 * 
 * Pure function: No side effects, just computes optimal value.
 */
object AdaptiveParallelism {
  
  /**
   * Calculate effective parallelism for simulation.
   * 
   * @param requested User/config requested parallelism
   * @param nTrials Number of trials (affects memory)
   * @param config System limits
   * @return Adjusted parallelism (≥ 1)
   */
  def calculate(
    requested: Int,
    nTrials: Int,
    memoryMonitor: MemoryMonitor,
    config: SimulationConfig
  ): UIO[Int] = {
    for {
      utilization <- memoryMonitor.heapUtilization
      effective = computeEffective(requested, nTrials, utilization, config)
    } yield effective
  }
  
  private def computeEffective(
    requested: Int,
    nTrials: Int,
    heapUtilization: Double,
    config: SimulationConfig
  ): Int = {
    val base = math.min(requested, config.maxParallelism)
    
    // Memory pressure factor: reduce parallelism above 70% heap
    val memoryFactor = heapUtilization match {
      case u if u < 0.7 => 1.0      // Normal: full parallelism
      case u if u < 0.85 => 0.5     // Warning: half parallelism
      case _ => 0.25                 // Critical: quarter parallelism
    }
    
    // Workload factor: reduce for large trial counts
    val workloadFactor = nTrials match {
      case n if n < 100_000 => 1.0
      case n if n < 500_000 => 0.75
      case _ => 0.5
    }
    
    // Combine factors, ensure minimum of 1
    math.max(1, (base * memoryFactor * workloadFactor).toInt)
  }
}
```

#### 6.3 Integration into SimulationExecutionService

```scala
// SimulationExecutionService modifications
class SimulationExecutionServiceLive private (
  config: SimulationConfig,
  memoryMonitor: MemoryMonitor  // NEW dependency
) extends SimulationExecutionService {
  
  override def execute(...): Task[RiskTreeWithLEC] = {
    for {
      // Calculate adaptive parallelism
      effectiveParallelism <- AdaptiveParallelism.calculate(
        requested = parallelism,
        nTrials = nTrials,
        memoryMonitor = memoryMonitor,
        config = config
      )
      
      _ <- ZIO.logInfo(s"Simulation: nTrials=$nTrials, parallelism=$effectiveParallelism (requested: $parallelism)")
      
      // Use effective parallelism
      result <- Simulator.simulateTree(node, nTrials, effectiveParallelism, includeProvenance)
      // ...
    } yield result
  }
}
```

---

## Implementation Order

### Phase A: Foundation (Required for Container Safety)
1. **Add maxConcurrentSimulations to SimulationConfig** ✅
2. **Create SimulationSemaphore service**
3. **Integrate semaphore into RiskTreeServiceLive**
4. **Add API limit validation**

### Phase B: Observability (Production Readiness)
5. **Document API constraints (Scaladoc + docs/)**
6. **Add warning logs for high-usage requests**

### Phase C: Optimization (Performance)
7. **Implement chunked trial processing**
8. **Add MemoryMonitor service**
9. **Implement adaptive parallelism**

---

## Testing Strategy

### Semaphore Tests
```scala
test("concurrent simulations limited by semaphore") {
  for {
    counter <- Ref.make(0)
    maxConcurrent <- Ref.make(0)
    
    // Track max concurrent executions
    trackingEffect = counter.updateAndGet(_ + 1).flatMap { current =>
      maxConcurrent.update(max => math.max(max, current))
    } *> ZIO.sleep(100.millis) *> counter.update(_ - 1)
    
    // Run 10 simulations with semaphore limit of 2
    _ <- ZIO.collectAllPar(List.fill(10)(simulationWithTracking))
    max <- maxConcurrent.get
  } yield assertTrue(max <= 2)
}
```

### Chunked Processing Tests
```scala
test("chunked processing produces same results as batch") {
  for {
    sampler <- createTestSampler
    batchResult <- performTrials(sampler, 100_000)
    chunkedResult <- performTrialsChunked(sampler, 100_000, chunkSize = 10_000)
  } yield assertTrue(batchResult.outcomes == chunkedResult.outcomes)
}
```

### Memory Monitor Tests
```scala
test("adaptive parallelism reduces under memory pressure") {
  for {
    // Mock high utilization
    mockMonitor = new MemoryMonitor {
      def heapUtilization = ZIO.succeed(0.9)
      def availableHeap = ZIO.succeed(100_000_000L)
    }
    result <- AdaptiveParallelism.calculate(8, 500_000, mockMonitor, config)
  } yield assertTrue(result < 8)
}
```

---

## Summary

| Component | Type | Category Theory Concept |
|-----------|------|------------------------|
| SimulationSemaphore | Service (ZLayer) | Resource algebra (acquire/release) |
| Chunked trials | Fold | Monoid fold over chunks |
| Adaptive parallelism | Pure function | Reader pattern (reads environment) |
| API validation | Validated | Applicative error accumulation |

All implementations maintain:
- ✅ Referential transparency
- ✅ Effect tracking (ZIO)
- ✅ Resource safety (ZLayer lifecycle)
- ✅ Composability (ZIO combinators)
- ✅ Testability (trait-based services)
