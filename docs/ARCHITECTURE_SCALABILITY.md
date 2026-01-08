# Architecture & Scalability Analysis

## Current Architecture Overview

### Parallelism Migration Status: ✅ COMPLETE

The codebase is **fully migrated to ZIO** for parallel execution. There are two parallelism mechanisms:

| Level | Mechanism | Purpose | Code Location |
|-------|-----------|---------|---------------|
| **Trial-level** | `scala.collection.parallel` | CPU-bound trial computation | `Simulator.performTrials` |
| **Node-level** | `ZIO.collectAllPar.withParallelism` | Fiber-based tree traversal | `Simulator.simulate`, `simulateTreeInternal` |

### Execution Flow

```
Request (computeLEC)
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ RiskTreeServiceLive.computeLEC()                                │
│   └─ tracing.span("computeLEC") { ... }                         │
│       └─ SimulationExecutionService.runTreeSimulation()         │
│           └─ Simulator.simulateTree()                           │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Simulator.simulateTreeInternal (recursive)                      │
│                                                                 │
│   RiskPortfolio (Branch):                                       │
│   └─ ZIO.collectAllPar(children.map(simulateTreeInternal))      │
│       .withParallelism(parallelism)                             │
│                                                                 │
│   RiskLeaf (Terminal):                                          │
│   └─ performTrials(sampler, nTrials)                            │
│       └─ successfulTrials.par.map(trial => sampleLoss)          │
│          (scala.collection.parallel)                            │
└─────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────┐
│ Aggregation (bottom-up)                                         │
│   └─ Identity[RiskResult].combine (outer join sparse maps)      │
└─────────────────────────────────────────────────────────────────┘
```

---

## Current Configuration

```hocon
register.simulation {
  defaultNTrials = 10000          # Trials per simulation
  maxTreeDepth = 5                # Max hierarchy depth
  defaultParallelism = 8          # ZIO fiber parallelism
}
```

---

## Scalability Scenarios

### Scenario 1: Increase Trials per Leaf (10K → 1M)

**Workload:** Single risk leaf with 1,000,000 trials

**Current Behavior:**
```
performTrials() execution:
├─ Filter successful trials: O(n) lazy view
├─ Materialize: O(k) where k = occurrences  
├─ Parallel map: O(k/p) on ForkJoinPool
└─ Convert to Map: O(k)
```

**Analysis:**

| Trials | Est. Occurrences (10% prob) | Memory (sparse) | Time (8 cores) |
|--------|----------------------------|-----------------|----------------|
| 10K | 1,000 | ~24 KB | ~10 ms |
| 100K | 10,000 | ~240 KB | ~100 ms |
| 1M | 100,000 | ~2.4 MB | ~1 sec |
| 10M | 1,000,000 | ~24 MB | ~10 sec |

**Bottlenecks:**
1. **Memory** - Sparse storage helps but 10M trials still allocates ~24MB per leaf
2. **ForkJoinPool** - Default pool shared across all parallel collections
3. **GC Pressure** - Large map allocations trigger GC pauses

**Mitigation Strategies:**
```scala
// 1. Chunked processing (streaming)
def performTrialsChunked(sampler: RiskSampler, nTrials: Int, chunkSize: Int = 100000): Task[Map[TrialId, Loss]] = {
  ZIO.foreachPar((0 until nTrials).grouped(chunkSize).toVector) { chunk =>
    ZIO.attempt {
      chunk.view
        .filter(trial => sampler.sampleOccurrence(trial.toLong))
        .map(trial => (trial, sampler.sampleLoss(trial.toLong)))
        .toMap
    }
  }.map(_.reduce(_ ++ _))
}

// 2. Streaming aggregation (no full materialization)
// Future: Use ZStream for trial processing
```

---

### Scenario 2: Large Risk Hierarchy

**Workload:** 1000 leaves, depth 5, 10K trials each

**Current Behavior:**
```
Tree traversal: ZIO.collectAllPar.withParallelism(8)
├─ Level 1: 1 portfolio (root)
├─ Level 2: ~10 portfolios  
├─ Level 3: ~100 portfolios
├─ Level 4: ~500 portfolios
└─ Level 5: ~1000 leaves
```

**Analysis:**

| Leaves | Trials/Leaf | Total Trials | Est. Time | Memory Peak |
|--------|-------------|--------------|-----------|-------------|
| 10 | 10K | 100K | ~100 ms | ~10 MB |
| 100 | 10K | 1M | ~1 sec | ~100 MB |
| 1000 | 10K | 10M | ~10 sec | ~1 GB |
| 10000 | 10K | 100M | ~100 sec | ~10 GB |

**Bottlenecks:**
1. **Memory** - All leaf results held in memory for aggregation
2. **Aggregation** - `reduce(combine)` creates intermediate maps
3. **GC** - Major GC at 1GB+ heap usage

**Mitigation Strategies:**
```scala
// 1. Streaming aggregation (avoid full materialization)
def aggregateStreaming(results: Vector[RiskResult]): RiskResult = {
  // Merge-sort style aggregation: O(n log n) vs O(n²)
  results.reduceLeft { (acc, next) =>
    Identity[RiskResult].combine(acc, next)
  }
}

// 2. Depth-limited aggregation
// Only aggregate to requested depth, prune deeper branches

// 3. Parallel aggregation tree
def parallelReduce[A](items: Vector[A])(combine: (A, A) => A): Task[A] = {
  items match {
    case Vector(single) => ZIO.succeed(single)
    case _ =>
      val (left, right) = items.splitAt(items.length / 2)
      ZIO.collectAllPar(Vector(
        parallelReduce(left)(combine),
        parallelReduce(right)(combine)
      )).map(pair => combine(pair(0), pair(1)))
  }
}
```

---

### Scenario 3: Concurrent Requests

**Workload:** 100 simultaneous simulation requests

**Current Behavior:**
- ZIO HTTP server handles requests on fiber pool
- Each request creates independent simulation fibers
- No request queuing or rate limiting
- Shared ForkJoinPool for parallel collections

**Analysis:**

| Concurrent Requests | Fibers Created | CPU Contention | Memory |
|---------------------|----------------|----------------|---------|
| 1 | ~8 | Low | ~50 MB |
| 10 | ~80 | Medium | ~500 MB |
| 100 | ~800 | High | ~5 GB |
| 1000 | ~8000 | Severe | ~50 GB |

**Bottlenecks:**
1. **CPU Contention** - Too many fibers competing for CPU
2. **Memory Explosion** - Each request holds full simulation state
3. **No Backpressure** - Requests accepted faster than processed

**Mitigation Strategies:**
```scala
// 1. Request Semaphore (limit concurrent simulations)
val simulationSemaphore = Semaphore.make(permits = 10)

def computeLEC(...): Task[RiskTreeWithLEC] = {
  simulationSemaphore.withPermit {
    // Actual computation
  }
}

// 2. Request Queue with Backpressure
val requestQueue = Queue.bounded[(SimulationRequest, Promise[RiskTreeWithLEC])](100)

// 3. Global parallelism budget
val globalParallelism = ZLayer.fromZIO(
  Ref.make(Runtime.getRuntime.availableProcessors() * 2)
)
```

---

### Scenario 4: Millions of Trials (Complete Aggregation)

**Workload:** Full aggregation over large hierarchy with millions of total trials

**Example:**
- 500 leaves × 100K trials = 50M trials
- Full depth aggregation needed

**Current Limitations:**
1. **Memory Bound** - All sparse maps in memory
2. **Single Node** - No distribution across machines
3. **No Checkpointing** - Failure = restart from beginning

**Scaling Strategy: Horizontal**

```
┌─────────────────────────────────────────────────────────────────┐
│                    Load Balancer                                │
│                         │                                       │
│    ┌───────────────────┼───────────────────┐                   │
│    ▼                   ▼                   ▼                   │
│ ┌──────────┐     ┌──────────┐       ┌──────────┐               │
│ │ Node 1   │     │ Node 2   │       │ Node N   │               │
│ │ (Subtree)│     │ (Subtree)│       │ (Subtree)│               │
│ └────┬─────┘     └────┬─────┘       └────┬─────┘               │
│      │                │                   │                     │
│      └────────────────┼───────────────────┘                     │
│                       ▼                                         │
│               ┌──────────────┐                                  │
│               │ Aggregator   │                                  │
│               │ (Final Merge)│                                  │
│               └──────────────┘                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation Options:**

| Approach | Complexity | When to Use |
|----------|------------|-------------|
| **Vertical (bigger machine)** | Low | < 100M trials |
| **Work Queue (Redis/Kafka)** | Medium | 100M - 1B trials |
| **Distributed (Spark/Flink)** | High | > 1B trials |

---

## Recommended Scaling Roadmap

### Phase 1: Optimize Single Node (Current Focus)

**Immediate improvements:**

1. **Chunked Trial Processing**
   ```scala
   // Process trials in 100K chunks to reduce GC pressure
   def performTrialsChunked(sampler: RiskSampler, nTrials: Int): Task[Map[TrialId, Loss]]
   ```

2. **Simulation Semaphore**
   ```scala
   // Limit concurrent simulations to prevent OOM
   val maxConcurrentSimulations = 10
   ```

3. **Memory-Aware Parallelism**
   ```scala
   // Adjust parallelism based on available memory
   def adaptiveParallelism(estimatedMemoryMB: Long): Int = {
     val availableMB = Runtime.getRuntime.maxMemory() / (1024 * 1024)
     Math.min(8, (availableMB / estimatedMemoryMB).toInt)
   }
   ```

### Phase 2: Vertical Scaling (Containerization)

**Target:** 100M trials on single container

1. **JVM Tuning**
   ```
   -XX:+UseG1GC
   -XX:MaxGCPauseMillis=200
   -XX:MaxRAMPercentage=75.0
   -XX:+UseStringDeduplication
   ```

2. **Container Resources**
   ```yaml
   resources:
     requests:
       memory: "4Gi"
       cpu: "4"
     limits:
       memory: "8Gi"
       cpu: "8"
   ```

3. **Health Checks**
   - Memory pressure endpoint
   - Simulation queue depth
   - Average response time

### Phase 3: Horizontal Scaling (Future)

**Target:** 1B+ trials

**Architecture:**
```
                    ┌─────────────┐
                    │   Gateway   │
                    │ (Rate Limit)│
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Worker 1 │ │ Worker 2 │ │ Worker N │
        │ (Leaves) │ │ (Leaves) │ │ (Leaves) │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
             └────────────┼────────────┘
                          ▼
                   ┌──────────────┐
                   │  Aggregator  │
                   │   Service    │
                   └──────────────┘
```

**Technologies to consider:**
- **Work Distribution:** Redis Streams or Kafka
- **State Management:** Redis for intermediate results
- **Orchestration:** Kubernetes Jobs for batch processing

---

## Current System Behavior Under Load

### Load Test Predictions

| Load Level | Requests/sec | Avg Latency | Memory | CPU | Status |
|------------|--------------|-------------|--------|-----|--------|
| Light | 1 | ~100ms | 200MB | 20% | ✅ Healthy |
| Moderate | 10 | ~500ms | 1GB | 60% | ✅ Healthy |
| Heavy | 50 | ~2s | 4GB | 90% | ⚠️ Degraded |
| Extreme | 100 | ~10s+ | 8GB+ | 100% | ❌ OOM Risk |

### Failure Modes

1. **OOM Kill** - Too many concurrent large simulations
2. **Timeout** - Request queue backup
3. **GC Thrashing** - Memory pressure causes stop-the-world pauses
4. **Thread Starvation** - ForkJoinPool exhausted

### Monitoring Metrics (via OpenTelemetry)

Already implemented:
- `risk_tree.operations` - Operation counts with success/failure
- `risk_tree.simulation.duration_ms` - Simulation latency histogram
- `risk_tree.simulation.trials` - Total trials executed

**Recommended additions:**
```scala
// Memory pressure
meter.gauge("jvm.memory.used_ratio", () => usedMemory / maxMemory)

// Concurrent simulations
meter.gauge("simulation.concurrent", () => activeSemaphorePermits)

// Queue depth (if added)
meter.gauge("simulation.queue_depth", () => requestQueue.size)
```

---

## Summary

### Current State
- ✅ **Fully ZIO** - Parallel execution properly migrated
- ✅ **Sparse Storage** - Memory-efficient trial storage
- ✅ **Observability** - OpenTelemetry tracing and metrics
- ⚠️ **No Backpressure** - Unbounded concurrent requests
- ⚠️ **Single Node** - No horizontal scaling

### Scaling Limits (Current Implementation)

| Metric | Safe Limit | Warning | Critical |
|--------|-----------|---------|----------|
| Trials per request | 100K | 500K | 1M+ |
| Concurrent requests | 10 | 50 | 100+ |
| Total trials/sec | 1M | 5M | 10M+ |
| Heap usage | 2GB | 4GB | 8GB+ |

### Next Steps

1. **Containerize** (Immediate) - Enable deployment and resource limits
2. **Add Semaphore** (Short-term) - Prevent OOM from concurrent requests
3. **Chunked Processing** (Medium-term) - Handle larger trial counts
4. **Horizontal Scaling** (Long-term) - Distributed simulation when needed

---

## Recommendations Before UI Development

1. **Add health endpoint** - `/health` with memory/queue status
2. **Configure max concurrent simulations** - Environment variable
3. **Set reasonable defaults** - 10K trials sufficient for most use cases
4. **Document API limits** - Max trials, max depth, expected latency
