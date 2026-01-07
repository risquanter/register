# WebSocket Migration Guide

## Current Implementation: BCG Synchronous Pattern

The current implementation follows BCG's proven synchronous request-reply pattern:

```
POST /simulations → validate → execute → generate LEC → persist → return results
```

**Characteristics:**
- **Synchronous**: Client waits for full simulation completion
- **Immediate Results**: LEC data embedded in response
- **Simple Architecture**: No job queue, no state management
- **Limited Scale**: Max 100K trials (~5-10 seconds execution time)

**Trade-offs:**
- ✅ Simple implementation and testing
- ✅ Predictable behavior (no polling, no race conditions)
- ✅ Good for typical use cases (10K-100K trials)
- ❌ HTTP timeout risk for long-running simulations
- ❌ Client blocked during execution
- ❌ Cannot scale beyond ~10 seconds

---

## Future Enhancement: WebSocket Streaming

When synchronous execution becomes insufficient (e.g., 1M+ trials, complex multi-risk scenarios), migrate to WebSocket streaming for real-time progress updates.

### Architecture Overview

```
┌─────────┐                    ┌──────────────┐
│ Client  │◄────WebSocket─────►│    Server    │
└─────────┘                    └──────────────┘
     │                              │
     │ 1. POST /simulations         │
     ├─────────────────────────────►│
     │                              │ 2. Validate & start job
     │◄─────202 Accepted────────────┤    Return jobId
     │    {jobId: "xyz"}            │
     │                              │
     │ 3. WS /simulations/xyz/stream│
     ├─────────────────────────────►│ 4. Execute with progress
     │                              │
     │◄────Progress Updates─────────┤ {"type":"progress","pct":25}
     │◄─────────────────────────────┤ {"type":"progress","pct":50}
     │◄─────────────────────────────┤ {"type":"progress","pct":75}
     │                              │
     │◄────Complete + LEC───────────┤ {"type":"complete","lec":{...}}
     │                              │
     └───Close Connection───────────┘
```

### Implementation Phases

#### Phase 1: Job Management Infrastructure

**Files to Create:**
- `SimulationJob.scala` - Domain model for async jobs
- `SimulationJobRepository.scala` - Persistence for job metadata
- `SimulationJobService.scala` - Job lifecycle management

```scala
// Domain Model
case class SimulationJob(
  id: String,              // UUID for job tracking
  simulationId: Long,      // Reference to persisted Simulation
  status: JobStatus,       // Queued, Running, Completed, Failed
  progress: Double,        // 0.0 to 1.0
  createdAt: Instant,
  startedAt: Option[Instant],
  completedAt: Option[Instant],
  error: Option[String]
)

sealed trait JobStatus
object JobStatus {
  case object Queued extends JobStatus
  case object Running extends JobStatus
  case object Completed extends JobStatus
  case object Failed extends JobStatus
}
```

**Key Operations:**
- `createJob(simulationId: Long): Task[SimulationJob]`
- `updateProgress(jobId: String, progress: Double): Task[Unit]`
- `completeJob(jobId: String, result: RiskResult): Task[Unit]`
- `failJob(jobId: String, error: String): Task[Unit]`

#### Phase 2: Background Execution Worker

**Files to Create:**
- `SimulationWorker.scala` - ZIO fiber pool for background execution
- `ProgressReporter.scala` - Progress tracking and broadcasting

```scala
trait SimulationWorker {
  /** Execute simulation asynchronously with progress updates
    * 
    * @param job Job metadata
    * @param config Risk configuration
    * @param nTrials Number of trials
    * @param progressCallback Called periodically with progress %
    * @return Fiber that completes with RiskResult
    */
  def executeAsync(
    job: SimulationJob,
    config: RiskConfig,
    nTrials: Int,
    progressCallback: Double => Task[Unit]
  ): Task[Fiber[Throwable, RiskResult]]
}
```

**Implementation Notes:**
- Use ZIO `Queue` for job queue
- Worker pool with configurable parallelism
- Progress updates every N trials (e.g., every 10K trials)
- Graceful shutdown on server restart

#### Phase 3: WebSocket Endpoint

**Files to Create:**
- `SimulationStreamEndpoint.scala` - Tapir WebSocket endpoint
- `SimulationStreamController.scala` - WebSocket message handling

```scala
// WebSocket Message Protocol
sealed trait StreamMessage
case class ProgressMessage(jobId: String, progress: Double, message: String) extends StreamMessage
case class CompleteMessage(jobId: String, quantiles: Map[String, Double], vegaLite: String) extends StreamMessage
case class ErrorMessage(jobId: String, error: String) extends StreamMessage

// Endpoint Definition
val streamEndpoint: PublicEndpoint[String, Unit, ZStream[Any, Throwable, StreamMessage], ZStream[Any, Throwable, ServerSentEvent]] =
  endpoint
    .get
    .in("simulations" / path[String]("jobId") / "stream")
    .out(webSocketBody[String, CodecFormat.TextPlain, StreamMessage, CodecFormat.Json](ZPipeline.identity))
```

**Flow:**
1. Client opens WebSocket connection: `/simulations/{jobId}/stream`
2. Server looks up job status
3. If running: Stream progress updates
4. If completed: Send final LEC data and close
5. If failed: Send error message and close

#### Phase 4: Update Simulation Service

**Modify:**
- `SimulationService.create()` to support adaptive routing:

```scala
trait SimulationService {
  /** Create simulation with adaptive execution:
    * - nTrials <= 100K: Synchronous (BCG pattern)
    * - nTrials > 100K: Async with job tracking
    */
  def create(req: RiskTreeDefinitionRequest): Task[Either[SimulationWithLEC, SimulationJob]]
}
```

**Alternative (simpler):**
Keep both endpoints separate:
- `POST /simulations` - Synchronous (existing)
- `POST /simulations/async` - Asynchronous (new)

This avoids breaking existing clients and keeps API predictable.

#### Phase 5: Client-Side Integration

**JavaScript/TypeScript Example:**

```typescript
// Async submission
const response = await fetch('/simulations/async', {
  method: 'POST',
  body: JSON.stringify({ name: "Large Simulation", nTrials: 1_000_000, ... })
});
const { jobId } = await response.json();

// WebSocket streaming
const ws = new WebSocket(`ws://localhost:8080/simulations/${jobId}/stream`);

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  switch(msg.type) {
    case 'progress':
      console.log(`Progress: ${msg.progress * 100}%`);
      updateProgressBar(msg.progress);
      break;
      
    case 'complete':
      console.log('Simulation complete!');
      renderLEC(msg.quantiles, msg.vegaLite);
      ws.close();
      break;
      
    case 'error':
      console.error(`Error: ${msg.error}`);
      ws.close();
      break;
  }
};

ws.onerror = (error) => {
  console.error('WebSocket error:', error);
};
```

### Migration Strategy

#### Option A: Big Bang (Not Recommended)
- Replace synchronous endpoint entirely
- Requires all clients to update simultaneously
- High risk, complex coordination

#### Option B: Gradual Migration (Recommended)
1. **Add async endpoint alongside existing sync endpoint**
   - `/simulations` - Synchronous (existing, < 100K trials)
   - `/simulations/async` - Asynchronous (new, any trial count)
   
2. **Dual-mode period**
   - Both endpoints coexist
   - Clients migrate at their own pace
   - Monitor usage to track migration progress
   
3. **Deprecation notice**
   - After 6 months, add deprecation header to sync endpoint
   - Document that sync will be removed in future version
   
4. **Final migration**
   - After 12+ months, consider removing sync endpoint
   - Or keep it permanently for backwards compatibility

#### Option C: Adaptive Routing (Transparent)
- Single endpoint auto-selects execution mode based on nTrials
- `nTrials <= 100K`: Synchronous response with LEC
- `nTrials > 100K`: Return 202 with jobId, require WebSocket
- Pro: Transparent to simple clients
- Con: Inconsistent API behavior (sometimes sync, sometimes async)

**Recommendation: Option B (Gradual Migration)**

### Performance Considerations

**When to Use WebSocket:**
- ✅ nTrials > 100K (> 10 seconds execution)
- ✅ User needs progress feedback
- ✅ Long-running multi-risk aggregations
- ✅ Batch processing scenarios

**When to Keep Synchronous:**
- ✅ nTrials <= 100K (< 10 seconds execution)
- ✅ Simple API clients (curl, scripts)
- ✅ Prototyping and testing
- ✅ Backwards compatibility requirements

### Testing Strategy

**Unit Tests:**
- Job state transitions (Queued → Running → Completed)
- Progress calculation accuracy
- Error handling and job failure

**Integration Tests:**
- Full async flow: POST → WebSocket → completion
- Multiple concurrent jobs
- Job cancellation
- Server restart recovery

**Load Tests:**
- 100 concurrent simulations
- 1M+ trials per simulation
- WebSocket connection stability
- Progress update frequency tuning

### Configuration

```hocon
# application.conf
simulation {
  execution {
    # Synchronous execution limit
    max-sync-trials = 100000
    
    # Worker pool configuration
    async {
      worker-count = 4              # Parallel execution fibers
      queue-size = 100               # Max queued jobs
      progress-interval = 10000      # Report progress every N trials
    }
    
    # WebSocket settings
    websocket {
      idle-timeout = 5 minutes       # Close idle connections
      max-frame-size = 1MB          # Max message size
    }
  }
}
```

### Rollback Plan

If WebSocket implementation causes issues:

1. **Disable async endpoint** via feature flag:
   ```scala
   val enableAsync = config.getBoolean("simulation.async.enabled")
   
   val routes = if (enableAsync) {
     List(syncEndpoint, asyncEndpoint, wsEndpoint)
   } else {
     List(syncEndpoint)
   }
   ```

2. **Redirect async requests** to sync endpoint with validation:
   ```scala
   if (!enableAsync && req.nTrials > maxSyncTrials) {
     ZIO.fail(ValidationFailed(List(
       "Async execution is temporarily disabled. " +
       "Please reduce nTrials to <= 100K or contact support."
     )))
   }
   ```

3. **Monitor and fix** issues while keeping sync endpoint operational

### Dependencies to Add

```scala
// build.sbt additions for WebSocket support

libraryDependencies ++= Seq(
  // Already have Tapir, just need WebSocket support
  "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % tapirVersion,
  
  // ZIO Streams for WebSocket (already have ZIO)
  // No additional deps needed
  
  // Optional: Job persistence
  "io.getquill" %% "quill-jdbc-zio" % "4.6.0",  // If using SQL for jobs
  
  // Optional: Distributed job queue (future)
  "dev.zio" %% "zio-redis" % "0.2.0"  // For Redis-backed queue
)
```

### Estimated Effort

| Phase | Effort | Description |
|-------|--------|-------------|
| Phase 1 | 2-3 days | Job management infrastructure |
| Phase 2 | 3-4 days | Background execution worker |
| Phase 3 | 2-3 days | WebSocket endpoint |
| Phase 4 | 1 day | Update service routing |
| Phase 5 | 1 day | Client examples and docs |
| Testing | 2-3 days | Integration and load testing |
| **Total** | **11-17 days** | Full WebSocket migration |

### Success Criteria

**Functional:**
- ✅ Can execute 1M+ trial simulations
- ✅ Real-time progress updates every 10K trials
- ✅ LEC data delivered via WebSocket
- ✅ Graceful handling of connection drops
- ✅ Job recovery after server restart

**Performance:**
- ✅ Support 100 concurrent jobs
- ✅ < 100ms latency for progress updates
- ✅ < 1% overhead vs synchronous execution

**Operational:**
- ✅ Feature flag for rollback
- ✅ Monitoring dashboards (job queue depth, completion rate)
- ✅ Clear documentation for clients
- ✅ Backwards compatibility maintained

---

## Conclusion

The current BCG synchronous pattern is **correct and sufficient** for the majority of use cases (10K-100K trials). WebSocket streaming should be considered **only when**:

1. Users consistently need > 100K trials
2. Long-running simulations cause HTTP timeouts
3. Real-time progress feedback is a requirement

For now, **focus on completing the synchronous implementation** and gather production metrics. If 90%+ of requests are < 100K trials with < 10s execution time, WebSocket may never be needed.

**Migration trigger:** If > 20% of requests hit the 100K limit or users explicitly request batch processing, revisit this guide and implement Phase 1-3.
