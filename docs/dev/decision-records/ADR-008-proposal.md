# ADR-008-proposal: Error Handling & Resilience

**Status:** Proposed  
**Date:** 2026-01-16  
**Tags:** errors, resilience, zio, retry, circuit-breaker

> **Note:** Code examples in this ADR are conceptual patterns, not actual codebase types.

---

## Context

- Multi-service architecture: Browser ↔ ZIO ↔ Irmin (OCaml)
- Network failures, timeouts, and service unavailability are expected
- Users need **clear feedback** when things go wrong
- System should **recover gracefully** without manual intervention
- See ADR-004a/b for transport layer details

---

## Decision

### 1. Error Domain Model

Distinguish between recoverable and terminal errors:

```scala
// Conceptual: Error hierarchy
enum RiskAppError:
  // Transient (recoverable via retry)
  case IrminUnavailable(cause: Throwable)
  case NetworkTimeout(operation: String, duration: Duration)
  case RateLimited(retryAfter: Duration)
  
  // Conflict (recoverable via user action)
  case VersionConflict(expected: CommitHash, actual: CommitHash)
  case MergeConflict(conflicts: List[ConflictInfo])
  
  // Validation (recoverable via user correction)
  case ValidationFailed(errors: NonEmptyList[ValidationError])
  case InvalidScenarioName(name: String, reason: String)
  
  // Terminal (requires investigation)
  case DataCorruption(message: String)
  case InternalError(message: String, cause: Throwable)
```

### 2. Retry Strategy for Transient Failures

```scala
// Conceptual: Retry with exponential backoff
object RetryPolicy:
  val irminRetry: Schedule[Any, RiskAppError, RiskAppError] =
    Schedule.exponential(100.millis, 2.0) &&
    Schedule.recurs(5) &&
    Schedule.recurWhile[RiskAppError] {
      case _: RiskAppError.IrminUnavailable => true
      case _: RiskAppError.NetworkTimeout   => true
      case _: RiskAppError.RateLimited      => true
      case _                                => false
    }

def callIrmin[A](op: Task[A]): IO[RiskAppError, A] =
  op
    .mapError(e => RiskAppError.IrminUnavailable(e))
    .retry(RetryPolicy.irminRetry)
```

### 3. Circuit Breaker for Irmin

Prevent cascade failures when Irmin is down:

```scala
// Conceptual: Circuit breaker configuration
val irminCircuitBreaker: CircuitBreaker[RiskAppError] =
  CircuitBreaker.make(
    maxFailures = 5,
    resetTimeout = 30.seconds,
    isRecoverable = {
      case _: RiskAppError.IrminUnavailable => true
      case _: RiskAppError.NetworkTimeout   => true
      case _                                => false
    }
  )

def withCircuitBreaker[A](op: IO[RiskAppError, A]): IO[RiskAppError, A] =
  irminCircuitBreaker.withCircuitBreaker(op)
```

### 4. Graceful Degradation

When Irmin is unavailable, provide degraded functionality:

```scala
// Conceptual: Fallback to cache
def getNode(nodeId: NodeId): IO[RiskAppError, RiskNode] =
  irminClient.getNode(nodeId)
    .catchSome {
      case _: RiskAppError.IrminUnavailable =>
        localCache.get(nodeId).someOrFail(RiskAppError.NodeNotCached(nodeId))
    }

// Notify user of degraded mode
def checkHealth: UIO[ServiceStatus] =
  irminClient.ping.fold(
    _ => ServiceStatus.Degraded("Irmin unavailable, showing cached data"),
    _ => ServiceStatus.Healthy
  )
```

### 5. SSE/WebSocket Reconnection

Handle connection drops gracefully:

```scala
// Conceptual: Auto-reconnect for SSE
def maintainSSEConnection(userId: UserId): Task[Unit] =
  connectSSE(userId)
    .retry(
      Schedule.exponential(1.second, 2.0) &&
      Schedule.recurs(10)
    )
    .tapError { e =>
      notifyUser(userId, "Connection lost. Retrying...")
    }
    .onInterrupt {
      notifyUser(userId, "Connection restored.")
    }
```

### 6. User-Facing Error Messages

Map internal errors to user-friendly messages:

```scala
// Conceptual: Error message translation
def toUserMessage(error: RiskAppError): UserMessage =
  error match
    case RiskAppError.IrminUnavailable(_) =>
      UserMessage.Warning(
        "Service temporarily unavailable. Your changes are queued.",
        retryable = true
      )
    case RiskAppError.VersionConflict(_, _) =>
      UserMessage.Action(
        "Someone else modified this node. Please refresh and retry.",
        action = "Refresh"
      )
    case RiskAppError.ValidationFailed(errors) =>
      UserMessage.Error(
        s"Please fix: ${errors.toList.map(_.message).mkString(", ")}",
        retryable = false
      )
    case RiskAppError.DataCorruption(msg) =>
      UserMessage.Critical(
        "Data integrity issue detected. Please contact support.",
        referenceId = generateSupportId()
      )
```

---

## Error Handling by Layer

### Browser → ZIO

| Error Type | HTTP Status | Response Body | Client Action |
|------------|-------------|---------------|---------------|
| ValidationFailed | 400 | `{errors: [...]}` | Show inline errors |
| VersionConflict | 409 | `{expected, actual}` | Prompt refresh |
| RateLimited | 429 | `{retryAfter: 5}` | Auto-retry after delay |
| IrminUnavailable | 503 | `{degraded: true}` | Show cached, poll status |
| InternalError | 500 | `{referenceId}` | Show error, offer retry |

### ZIO → Irmin (GraphQL)

| Error Type | GraphQL Error | ZIO Handling |
|------------|---------------|--------------|
| Network timeout | Connection refused | Retry with backoff |
| Rate limit | HTTP 429 | Extract retry-after, delay |
| Invalid query | GraphQL errors | Log, report as InternalError |
| Unavailable | Connection reset | Circuit breaker, fallback to cache |

---

## Outstanding Issues

### Queued Writes During Outage

**Issue:** If Irmin is unavailable, should we queue writes locally?

**Options:**
1. **Reject writes** - Simple, no data loss risk, but poor UX
2. **Queue in ZIO** - Better UX, but risk of data loss if ZIO crashes
3. **Queue in browser** - Survives ZIO restart, but complex sync

**Trade-offs:**
- Option 1: Safest, but frustrating for users
- Option 2: Good balance, needs durable queue (Redis?)
- Option 3: Most resilient, but complex conflict resolution

**Action required:** Decide write queue strategy.

### Error Aggregation & Alerting

**Issue:** Need observability for error patterns.

**Considerations:**
- Connect to ADR-002 (logging with OpenTelemetry)
- Alert thresholds for circuit breaker trips
- Dashboard for error rates by type

**Action required:** Define alerting rules and thresholds.

---

## Diagrams

### Error Recovery Flow

```
┌──────────────────────────────────────────────────────────┐
│                    Error Recovery Flow                    │
├──────────────────────────────────────────────────────────┤
│                                                          │
│   Operation                                              │
│       │                                                  │
│       ▼                                                  │
│   ┌───────┐      ┌─────────────┐      ┌──────────────┐  │
│   │ Error │─────►│ Recoverable?│──No──►│ Report Error │  │
│   └───────┘      └─────────────┘      └──────────────┘  │
│                        │                                 │
│                       Yes                                │
│                        │                                 │
│                        ▼                                 │
│                  ┌───────────┐                          │
│                  │   Retry   │                          │
│                  │ (backoff) │                          │
│                  └───────────┘                          │
│                        │                                 │
│              ┌─────────┴─────────┐                      │
│              ▼                   ▼                      │
│         ┌─────────┐        ┌───────────┐               │
│         │ Success │        │ Max Retry │               │
│         └─────────┘        │  Reached  │               │
│                            └───────────┘               │
│                                  │                      │
│                                  ▼                      │
│                           ┌────────────┐               │
│                           │  Circuit   │               │
│                           │  Breaker   │               │
│                           │    Open    │               │
│                           └────────────┘               │
│                                  │                      │
│                                  ▼                      │
│                           ┌────────────┐               │
│                           │  Degraded  │               │
│                           │    Mode    │               │
│                           └────────────┘               │
│                                                         │
└──────────────────────────────────────────────────────────┘
```

### Circuit Breaker States

```
        ┌──────────────────────────────────────┐
        │                                      │
        ▼                                      │
   ┌─────────┐    5 failures    ┌─────────┐   │
   │ CLOSED  │─────────────────►│  OPEN   │   │
   │         │                  │         │   │
   └─────────┘                  └─────────┘   │
        ▲                            │        │
        │                      30 sec timeout │
        │                            │        │
        │                            ▼        │
        │                      ┌──────────┐   │
        │         success      │HALF-OPEN │   │
        └──────────────────────│          │───┘
                    failure    └──────────┘
```

---

## Code Smells

### ❌ Swallowing Errors

```scala
// BAD: Error disappears
def getNode(nodeId: NodeId): Task[Option[RiskNode]] =
  irminClient.getNode(nodeId)
    .option  // Converts error to None - loses context!

// GOOD: Explicit error handling
def getNode(nodeId: NodeId): IO[RiskAppError, RiskNode] =
  irminClient.getNode(nodeId)
    .mapError(RiskAppError.IrminUnavailable(_))
```

### ❌ Retry Everything

```scala
// BAD: Retry validation errors (will never succeed)
def updateNode(req: UpdateRequest): Task[Unit] =
  validate(req)
    .flatMap(doUpdate)
    .retry(Schedule.recurs(3))  // Retrying validation failure!

// GOOD: Only retry transient errors
def updateNode(req: UpdateRequest): IO[RiskAppError, Unit] =
  validate(req)  // No retry
    .flatMap(doUpdate(_).retry(RetryPolicy.irminRetry))  // Retry only Irmin calls
```

### ❌ No Circuit Breaker

```scala
// BAD: Keep hammering failed service
def callIrmin[A](op: Task[A]): Task[A] =
  op.retry(Schedule.forever)  // Never gives up, wastes resources

// GOOD: Circuit breaker prevents cascade
def callIrmin[A](op: Task[A]): IO[RiskAppError, A] =
  irminCircuitBreaker
    .withCircuitBreaker(op)
    .mapError(RiskAppError.IrminUnavailable(_))
```

### ❌ Generic Error Messages

```scala
// BAD: Unhelpful message
def handleError(e: Throwable): UserMessage =
  UserMessage("An error occurred")  // What error? What to do?

// GOOD: Actionable message
def handleError(e: RiskAppError): UserMessage =
  toUserMessage(e)  // Specific guidance for each error type
```

---

## Implementation

| Component | Location | Purpose |
|-----------|----------|---------|
| `RiskAppError` | `domain/RiskAppError.scala` | Error ADT |
| `RetryPolicy` | `service/RetryPolicy.scala` | Retry schedules |
| `CircuitBreaker` | `service/CircuitBreaker.scala` | Circuit breaker config |
| `ErrorMapper` | `api/ErrorMapper.scala` | Error → HTTP response |
| `UserMessage` | `domain/UserMessage.scala` | User-facing messages |

---

## References

- ADR-002: Logging with OpenTelemetry (error correlation)
- ADR-004a/b: Transport layer error handling
- ADR-006: Conflict detection and resolution
- ZIO documentation: https://zio.dev/reference/error-management/
