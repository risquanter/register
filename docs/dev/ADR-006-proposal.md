# ADR-006-proposal: Real-Time Collaboration

**Status:** Proposed  
**Date:** 2026-01-16  
**Tags:** collaboration, events, concurrency, zio, irmin

> **Note:** Code examples in this ADR are conceptual patterns, not actual codebase types.

---

## Context

- Multiple users may edit the **same risk tree** simultaneously
- Changes from Irmin must **propagate to all connected clients**
- Conflicts need detection and resolution strategy
- ZIO backend acts as **event hub** between Irmin and browsers
- See ADR-004a (SSE) or ADR-004b (WebSocket) for transport details

---

## Decision

### 1. Event Hub Architecture

ZIO backend maintains subscriber registry:

```scala
// Conceptual: Event distribution hub
class EventHub(subscribers: Ref[Map[UserId, Queue[RiskEvent]]]):
  def subscribe(userId: UserId): UStream[RiskEvent]
  def broadcast(event: RiskEvent): UIO[Unit]
  def broadcastExcept(event: RiskEvent, exclude: UserId): UIO[Unit]
```

### 2. Event Types

```scala
// Conceptual: Collaboration events
enum RiskEvent:
  case NodeCreated(id: NodeId, parent: NodeId, by: UserId)
  case NodeUpdated(id: NodeId, changes: NodeDiff, by: UserId)
  case NodeDeleted(id: NodeId, by: UserId)
  case TreeBranched(branchId: BranchId, from: CommitHash, by: UserId)
  case TreeMerged(source: BranchId, target: BranchId, by: UserId)
  case LECRecomputed(nodeId: NodeId, summary: LECSummary)
  case UserJoined(userId: UserId, treeId: TreeId)
  case UserLeft(userId: UserId, treeId: TreeId)
```

### 3. Irmin Watch → Broadcast Pipeline

```scala
// Conceptual: Irmin subscription to client broadcast
def irminToBrowser: Task[Unit] =
  irminClient.watchChanges
    .mapZIO { commit =>
      for
        diff   <- computeDiff(commit.parent, commit.current)
        events <- diffToEvents(diff)
        _      <- ZIO.foreachDiscard(events)(eventHub.broadcast)
      yield ()
    }
    .runDrain
```

### 4. Conflict Detection

Detect concurrent edits via version vectors:

```scala
// Conceptual: Optimistic concurrency
case class EditRequest(
  nodeId: NodeId,
  changes: NodeDiff,
  baseVersion: CommitHash,  // "I'm editing based on this version"
  userId: UserId
)

def applyEdit(req: EditRequest): Task[EditResult] =
  for
    current <- irminClient.currentVersion(req.nodeId)
    result  <- if current == req.baseVersion
               then doApply(req).map(EditResult.Success(_))
               else ZIO.succeed(EditResult.Conflict(current, req.baseVersion))
  yield result

enum EditResult:
  case Success(newVersion: CommitHash)
  case Conflict(serverVersion: CommitHash, clientVersion: CommitHash)
```

### 5. Conflict Resolution Strategies

```scala
// Conceptual: Resolution options
enum ConflictResolution:
  case LastWriterWins      // Automatic, may lose data
  case Merge(strategy: MergeStrategy)  // Irmin 3-way merge
  case Reject              // Client must refresh and retry
  case Fork(branchName: String)  // Create scenario branch

// Default: Reject with client refresh
def handleConflict(conflict: EditResult.Conflict): Task[Unit] =
  eventHub.sendTo(conflict.userId, RiskEvent.ConflictDetected(
    message = "Tree was modified. Please refresh and retry.",
    serverVersion = conflict.serverVersion
  ))
```

---

## Outstanding Issues

### Backpressure

**Issue:** If events arrive faster than clients can consume, queue can grow unbounded.

**Options to investigate:**
1. Bounded queue with drop-oldest strategy
2. Bounded queue with backpressure signal to Irmin consumer
3. Coalescing rapid updates (debounce multiple edits to same node)
4. Per-client flow control

**Action required:** Define backpressure strategy before production deployment.

### Conflict Detection Timing

**Issue:** Current design detects conflict at write time. For better UX, could detect earlier:

**Option A (current):** Detect at write → User loses work if conflict  
**Option B (soft locks):** User signals "I'm editing node X" → Others see warning  
**Option C (OT/CRDT):** Operational transforms → Complex but seamless merging

**Trade-offs:**
- Option B requires additional protocol (editing presence)
- Option C significantly increases complexity

**Action required:** Decide if soft locks (Option B) are worth the added complexity. If yes, extend ADR-004b WebSocket presence to include "editing" state.

---

## Diagrams

### Event Flow

```
┌─────────┐    GraphQL     ┌───────────┐    SSE/WS    ┌─────────┐
│  Irmin  │ ──subscription─→│    ZIO    │ ──events───→ │ Browser │
│         │                 │  EventHub │              │   A     │
└─────────┘                 └───────────┘              └─────────┘
                                  │
                                  │ SSE/WS
                                  ▼
                            ┌─────────┐
                            │ Browser │
                            │   B     │
                            └─────────┘
```

### Conflict Scenario

```
Time    User A              Server              User B
 │
 ├──── Read v1 ◄────────── v1 ─────────────► Read v1 ────┤
 │                                                        │
 ├──── Edit locally                    Edit locally ──────┤
 │                                                        │
 ├──── Submit edit ──────► v2 ◄────────────────────────────
 │                         │
 │                         │ (User A wins)
 │                         │
 ├───────────────────────────────────── Submit edit ──────┤
 │                         │                              │
 │                    Conflict detected                   │
 │                         │                              │
 │                         ├──────────► Conflict event ──►│
 │                                      (must refresh)
```

---

## Code Smells

### ❌ No Conflict Detection

```scala
// BAD: Blindly overwrite
def updateNode(nodeId: NodeId, changes: NodeDiff): Task[Unit] =
  irminClient.update(nodeId, changes)  // May silently lose concurrent edits

// GOOD: Check version first
def updateNode(req: EditRequest): Task[EditResult] =
  for
    current <- irminClient.currentVersion(req.nodeId)
    result  <- if current == req.baseVersion
               then irminClient.update(req).map(EditResult.Success(_))
               else ZIO.succeed(EditResult.Conflict(current, req.baseVersion))
  yield result
```

### ❌ Broadcast to Self

```scala
// BAD: User receives their own edit as event
def onEdit(edit: EditRequest): Task[Unit] =
  for
    _ <- irminClient.apply(edit)
    _ <- eventHub.broadcast(edit.toEvent)  // User A gets confused
  yield ()

// GOOD: Exclude originator
def onEdit(edit: EditRequest): Task[Unit] =
  for
    _ <- irminClient.apply(edit)
    _ <- eventHub.broadcastExcept(edit.toEvent, exclude = edit.userId)
  yield ()
```

### ❌ Unbounded Event Queue

```scala
// BAD: Queue grows forever if client is slow
class EventHub(subscribers: Ref[Map[UserId, Queue[RiskEvent]]]):
  def broadcast(event: RiskEvent): UIO[Unit] =
    subscribers.get.flatMap { subs =>
      ZIO.foreachDiscard(subs.values)(_.offer(event))  // Unbounded
    }

// GOOD: Bounded with drop policy
class EventHub(subscribers: Ref[Map[UserId, BoundedQueue[RiskEvent]]]):
  def broadcast(event: RiskEvent): UIO[Unit] =
    subscribers.get.flatMap { subs =>
      ZIO.foreachDiscard(subs.values)(_.offerDropOldest(event))
    }
```

---

## Implementation

| Component | Location | Purpose |
|-----------|----------|---------|
| `EventHub` | `service/EventHub.scala` | Subscriber management |
| `RiskEvent` | `domain/RiskEvent.scala` | Event ADT |
| `ConflictDetector` | `service/ConflictDetector.scala` | Version checking |
| `IrminWatcher` | `infra/IrminWatcher.scala` | GraphQL subscription handler |

---

## References

- ADR-004a/b: Transport layer (SSE vs WebSocket)
- ADR-005: Cache invalidation triggers events
- Tree Zippers Conversation: Collaboration patterns discussed
