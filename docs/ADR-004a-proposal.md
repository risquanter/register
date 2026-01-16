# ADR-004a-proposal: Persistence Architecture (SSE Variant)

**Status:** Proposed  
**Date:** 2026-01-16  
**Tags:** persistence, irmin, graphql, sse, architecture

> **Note:** Code examples in this ADR are conceptual patterns, not actual codebase types.
> Types like `TreeId`, `NodeId`, `LECCurveData`, `IrminClient` are not yet implemented.

---

## Context

- Versioned persistence is **non-negotiable** for scenario analysis and time travel
- Computation and persistence must be **separated** (ZIO/Scala vs Irmin/OCaml)
- Two communication channels required: Irmin↔ZIO (internal) and ZIO↔Browser (external)
- SSE provides **simple unidirectional streaming** for server→client push
- Full requirements documented in [REQUIREMENTS-PERSISTENCE.md](./REQUIREMENTS-PERSISTENCE.md)

---

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Data Flow Architecture                               │
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
│  │ • Branches  │       │ Caches:     │           │ Caches:       │         │
│  │             │       │ • LEC curves│           │ • Rendered    │         │
│  │ Notifies:   │       │   per node  │           │   chart specs │         │
│  │ • watch API │       │             │           │               │         │
│  └─────────────┘       └─────────────┘           └───────────────┘         │
│         │                     │                          │                  │
│         │  GraphQL (Channel A)│       SSE (Channel B)    │                  │
│         │◀───────────────────▶│─────────────────────────▶│                  │
│         │  Irmin ↔ ZIO        │     ZIO → Browser        │                  │
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

### 3. SSE for ZIO→Browser Push

Server-Sent Events stream LEC updates to connected browsers:

```scala
// Conceptual: Endpoint pattern
// GET /events/tree/{treeId}
case class TreeEvent(
  nodeId: NodeId,
  eventType: String,  // "lec_updated" | "node_changed" | "conflict"
  payload: Json
)
```

### 4. ZIO-Side Cache for Computed Aggregates

LEC curves cached in ZIO, invalidated on Irmin notifications:

```scala
// Conceptual: Cache pattern
class LECCache:
  private val cache: Ref[Map[NodeId, LECCurveData]]
  
  def invalidatePath(nodeId: NodeId): UIO[List[NodeId]] =
    // Walk parent pointers, invalidate ancestors
    // Returns list of invalidated nodes for recomputation
```

---

## Complete Data Flow

```
1. User edits risk parameter in Browser
   │
   ▼
2. Browser sends REST mutation to ZIO
   │
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
   ▼
7. ZIO invalidates cache for X and ancestors
   │
   ▼
8. ZIO recomputes LEC for affected path (O(depth))
   │
   ▼
9. ZIO pushes update via SSE to Browser
   │
   ▼
10. Browser updates Vega chart
```

---

## Code Smells

### ❌ Polling Instead of Subscriptions

```scala
// BAD: Polling Irmin for changes
def pollForChanges: Task[Unit] =
  ZIO.sleep(1.second) *> checkIrmin *> pollForChanges

// GOOD: GraphQL subscription
irminClient.subscribe(watchQuery).foreach(handleChange)
```

### ❌ Full Tree Transmission

```scala
// BAD: Send entire tree on any change
def getTree: Task[RiskTree] = irminClient.query(fullTreeQuery)

// GOOD: Stream individual node updates
def streamChanges: ZStream[Any, Throwable, NodeUpdate]
```

### ❌ Computation in Irmin Layer

```scala
// BAD: OCaml computes LEC
// Irmin stores: /nodes/<id>/lec_cache (computed in OCaml)

// GOOD: Scala computes, Irmin stores structure only
// Irmin stores: /nodes/<id>/v (parameters only)
// ZIO computes and caches LECCurveData
```

---

## Implementation

| Component | Technology | Purpose |
|-----------|------------|---------|
| `IrminClient` | sttp + GraphQL | Mutations and subscriptions to Irmin |
| `LECCache` | ZIO Ref + Map | In-memory LEC curve storage |
| `SSEController` | Tapir SSE | Push updates to browsers |
| `TreeWatcher` | ZStream | Process Irmin notifications |

---

## References

- [Irmin Documentation](https://irmin.org/)
- [irmin-graphql](https://github.com/mirage/irmin)
- [Tapir SSE Support](https://tapir.softwaremill.com/en/latest/endpoint/sse.html)
- [REQUIREMENTS-PERSISTENCE.md](./REQUIREMENTS-PERSISTENCE.md)
- ADR-003: Provenance (simulation reproducibility)
