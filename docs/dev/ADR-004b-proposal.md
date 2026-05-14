# ADR-004b-proposal: Persistence Architecture (WebSocket Enhancement)

**Status:** Proposed  
**Date:** 2026-01-16  
**Tags:** persistence, irmin, graphql, websocket, architecture

> **Note:** Code examples in this ADR are conceptual patterns showing the intended data flow.
> See actual implementations: `IrminClient`, `RiskResultCache`, `SSEHub`.

---

## Context

- Versioned persistence is **non-negotiable** for scenario analysis and time travel
- Computation and persistence must be **separated** (ZIO/Scala vs Irmin/OCaml)
- Two communication channels required: Irmin↔ZIO (internal) and ZIO↔Browser (external)
- WebSocket provides **bidirectional streaming** for enhanced collaboration
- Full requirements documented in [REQUIREMENTS-PERSISTENCE.md](./REQUIREMENTS-PERSISTENCE.md)

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   Data Flow Architecture (WebSocket)                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐      ┌─────────────────┐      ┌─────────────────────────┐ │
│  │   Irmin     │      │   ZIO Backend   │      │   Browser (Scala.js)   │ │
│  │   (OCaml)   │      │   (Scala)       │      │   Laminar              │ │
│  └─────────────┘      └─────────────────┘      └─────────────────────────┘ │
│         │                     │                          │                  │
│  ┌──────┴──────┐       ┌──────┴──────┐           ┌───────┴───────┐         │
│  │ Stores:     │       │ Computes:   │           │ Displays:     │         │
│  │ • Tree      │       │ • LEC sims  │           │ • Tree view   │         │
│  │   structure │       │ • Aggregates│           │ • Vega charts │         │
│  │ • Versions  │       │             │           │               │         │
│  │ • Branches  │       │ Caches:     │           │ Sends:        │         │
│  │             │       │ • LEC curves│           │ • Edits       │         │
│  │ Notifies:   │       │   per node  │           │ • Cursors     │         │
│  │ • watch API │       │             │           │ • Presence    │         │
│  └─────────────┘       └─────────────┘           └───────────────┘         │
│         │                     │                          │                  │
│         │  GraphQL (Channel A)│   WebSocket (Channel B)  │                  │
│         │◀───────────────────▶│◀────────────────────────▶│                  │
│         │  Irmin ↔ ZIO        │     ZIO ↔ Browser        │                  │
│                               │     (bidirectional)      │                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Decision

### 1. Irmin as Versioned Persistence Layer

Irmin (OCaml) stores tree structure with Git-like semantics:

```
/nodes/<id>/v          → RiskLeaf | RiskPortfolio parameters
/nodes/<id>/children   → List[NodeId] for portfolios
/branches/<name>       → Commit hash (scenarios)
```

### 2. GraphQL for Irmin↔ZIO Communication

ZIO connects to Irmin via GraphQL subscriptions for real-time watch:

```scala
// Conceptual: ZIO subscribes to tree changes
val subscription = """
  subscription WatchTree($path: [String!]!) {
    treeChanged(path: $path) {
      kind      // Added | Updated | Removed
      path      // ["nodes", "42"]
      nodeId    // affected node
    }
  }
"""
```

### 3. WebSocket for Bidirectional ZIO↔Browser Communication

WebSocket enables client→server streaming for enhanced collaboration:

```scala
// Conceptual: WebSocket message types

// Client → Server messages
sealed trait ClientMessage
case class EditNode(nodeId: NodeId, patch: JsonPatch) extends ClientMessage
case class CursorMove(nodeId: NodeId) extends ClientMessage
case class PresenceUpdate(userId: UserId, status: Status) extends ClientMessage

// Server → Client messages
sealed trait ServerMessage
case class LECUpdated(nodeId: NodeId, lec: LECCurveResponse) extends ServerMessage
case class NodeChanged(nodeId: NodeId, node: RiskNode) extends ServerMessage
case class ConflictDetected(nodeId: NodeId, users: List[UserId]) extends ServerMessage
case class UserCursor(userId: UserId, nodeId: NodeId) extends ServerMessage
```

### 4. ZIO-Side Cache for Computed Aggregates

LEC curves cached in ZIO, invalidated on Irmin notifications:

```scala
// Conceptual: Cache pattern
class RiskResultCache:
  private val cache: Ref[Map[NodeId, RiskResult]]
  
  def invalidatePath(nodeId: NodeId): UIO[List[NodeId]] =
    // Walk parent pointers, invalidate ancestors
```

### 5. Presence and Cursor Tracking

WebSocket enables real-time collaboration awareness:

```scala
// Conceptual: Presence tracking
class PresenceHub:
  private val connections: Ref[Map[UserId, WebSocketConnection]]
  private val cursors: Ref[Map[UserId, NodeId]]
  
  def broadcast(msg: ServerMessage, exclude: Option[UserId]): UIO[Unit]
  def notifyConflict(nodeId: NodeId, editors: List[UserId]): UIO[Unit]
```

---

## Complete Data Flow (WebSocket)

```
1. User edits risk parameter in Browser
   │
   ▼
2. Browser sends EditNode via WebSocket to ZIO
   │
   ├──────────────────────────────────────────┐
   │                                          ▼
   │                              3a. ZIO broadcasts CursorMove
   │                                  to other connected clients
   ▼
3. ZIO sends GraphQL mutation to Irmin
   │
   ▼
4. Irmin commits new tree version
   │
   ▼
5. Irmin watch fires (GraphQL subscription)
   │
   ▼
6. ZIO receives notification: "node X changed"
   │
   ├──────────────────────────────────────────┐
   │                                          ▼
   │                              6a. ZIO checks for conflicts
   │                                  (multiple editors on same node)
   ▼
7. ZIO invalidates cache for X and ancestors
   │
   ▼
8. ZIO recomputes LEC for affected path (O(depth))
   │
   ▼
9. ZIO broadcasts LECUpdated via WebSocket to all clients
   │
   ▼
10. All browsers update Vega charts simultaneously
```

---

## Code Smells

### ❌ REST for Real-Time Edits

```scala
// BAD: REST endpoint for each edit
def updateNode(nodeId: NodeId, patch: JsonPatch): Task[Unit]
// Requires client to poll for other users' changes

// GOOD: WebSocket for edits
wsConnection.send(EditNode(nodeId, patch))
// Server broadcasts to all clients automatically
```

### ❌ No Presence Awareness

```scala
// BAD: Users don't know who else is editing
def getNode(nodeId: NodeId): Task[RiskNode]

// GOOD: Presence and cursor tracking
case class NodeView(
  node: RiskNode,
  activeEditors: List[UserId],
  cursors: Map[UserId, CursorPosition]
)
```

### ❌ Conflict After Commit

```scala
// BAD: Detect conflict only after Irmin merge fails
irminClient.mutate(updateNode).catchSome {
  case ConflictException => handleConflict
}

// GOOD: Detect concurrent editing before commit
presenceHub.getEditorsFor(nodeId).flatMap {
  case editors if editors.size > 1 =>
    broadcast(ConflictDetected(nodeId, editors))
  case _ => proceed
}
```

---

## WebSocket vs SSE Comparison

| Aspect | SSE (ADR-004a) | WebSocket (ADR-004b) |
|--------|----------------|---------------------|
| **Direction** | Server → Client only | Bidirectional |
| **Client edits** | Separate REST calls | Same connection |
| **Presence** | Not supported | Real-time cursors |
| **Conflict detection** | Post-commit only | Pre-commit awareness |
| **Complexity** | Lower | Higher |
| **Use when** | Read-heavy, few editors | Collaborative editing |

---

## Implementation

| Component | Technology | Purpose |
|-----------|------------|---------|
| `IrminClient` | sttp + GraphQL | Mutations and subscriptions to Irmin |
| `RiskResultCache` | ZIO Ref + Map | In-memory simulation result storage |
| `WebSocketHub` | ZIO HTTP WebSocket | Bidirectional client communication |
| `PresenceHub` | ZIO Ref + Hub | Track connected users and cursors |
| `ConflictDetector` | ZIO logic | Pre-commit conflict awareness |

---

## References

- [Irmin Documentation](https://irmin.org/)
- [irmin-graphql](https://github.com/mirage/irmin)
- [ZIO HTTP WebSocket](https://zio.dev/zio-http/websocket/)
- [REQUIREMENTS-PERSISTENCE.md](./REQUIREMENTS-PERSISTENCE.md)
- ADR-004a-proposal: SSE variant (simpler, unidirectional)
- ADR-003: Provenance (simulation reproducibility)
