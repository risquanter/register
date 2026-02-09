# ADR-005-proposal: Cached Subtree Aggregates

**Status:** Proposed  
**Date:** 2026-01-16  
**Tags:** caching, performance, lec, aggregation, zio

> **Implementation status (2026-02-09):** `TreeIndex` and `RiskResultCache` are implemented.
> `TreeIndex` is built from nodes at tree construction time and provides O(1) node/parent lookup.
> `RiskResultCache` stores `RiskResult` per `NodeId` (not `LECCurveData`).
> Cache invalidation uses `TreeCacheManager` with O(depth) ancestor path via `TreeIndex`.
> The conceptual `LECCache` in this ADR was realized as `RiskResultCache` + `RiskResultResolver`.

---

## Context

- LEC simulation is **expensive** (Monte Carlo with thousands of trials)
- Tree updates should trigger **O(depth)** recomputation, not O(n)
- Aggregates are **composable** via `Identity[RiskResult].combine(a, b)` (see ADR-009)
- Cache lives in **ZIO backend**, not Irmin (computation stays in Scala)
- Irmin notifications trigger **cache invalidation**

---

## Decision

### 1. Parent-Pointer Index for O(1) Lookup

Maintain bidirectional navigation structure in ZIO:

```scala
// Conceptual: Tree navigation index
case class TreeIndex(
  nodes: Map[NodeId, RiskNode],           // O(1) lookup by ID
  parents: Map[NodeId, NodeId],           // child → parent
  children: Map[NodeId, List[NodeId]]     // parent → children
)

object TreeIndex:
  def ancestorPath(nodeId: NodeId): List[NodeId] =
    // Returns [nodeId, parentId, grandparentId, ..., rootId]
    // O(depth) traversal via parent pointers
```

### 2. Per-Node Result Cache

Cache simulation `RiskResult` for each node (not rendered curves — see ADR-014):

```scala
// Actual implementation: RiskResultCache + TreeCacheManager
trait RiskResultCache:
  def get(nodeId: NodeId): UIO[Option[RiskResult]]
  def put(nodeId: NodeId, result: RiskResult): UIO[Unit]

trait TreeCacheManager:
  def cacheFor(treeId: TreeId): UIO[RiskResultCache]
  def onTreeStructureChanged(treeId: TreeId): UIO[Unit]
  def deleteTree(treeId: TreeId): UIO[Unit]
```

### 3. Invalidation on Node Change

When Irmin notifies of a change, invalidate from node to root:

```scala
// Conceptual: Invalidation logic
def onNodeChanged(nodeId: NodeId): Task[Unit] =
  for
    ancestors <- cache.invalidate(nodeId)  // O(depth)
    _         <- ZIO.foreachDiscard(ancestors)(scheduleRecompute)
  yield ()

// Invalidation walks parent pointers
def invalidate(nodeId: NodeId): UIO[List[NodeId]] =
  for
    idx       <- index.get
    ancestors  = idx.ancestorPath(nodeId)
    _         <- ZIO.foreachDiscard(ancestors)(id => cache.update(_ - id))
  yield ancestors
```

### 4. Lazy Recomputation

Recompute results only when requested (cache-aside via `RiskResultResolver`):

```scala
// Actual implementation: RiskResultResolver
trait RiskResultResolver:
  def ensureCached(tree: RiskTree, nodeId: NodeId, includeProvenance: Boolean = false): Task[RiskResult]
  def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId], includeProvenance: Boolean = false): Task[Map[NodeId, RiskResult]]

// Cache-aside: check cache, simulate if miss, store result
def ensureCached(tree: RiskTree, nodeId: NodeId, ...): Task[RiskResult] =
  for
    cache  <- cacheManager.cacheFor(tree.id)
    cached <- cache.get(nodeId)
    result <- cached match
      case Some(r) => ZIO.succeed(r)
      case None    => simulate(tree, nodeId).tap(r => cache.put(nodeId, r))
  yield result
```

### 5. Structural Sharing via Content Addressing

Unchanged subtrees share cached values across branches:

```scala
// Conceptual: Content-addressed cache key
case class CacheKey(nodeId: NodeId, contentHash: Hash)

// Same parameters = same LEC, reusable across scenarios
def getCacheKey(node: RiskNode): CacheKey =
  CacheKey(node.id, Hash.of(node.parameters))
```

---

## Outstanding Investigation

### Cache Key Design & Eviction

**Issue:** If cache key includes `contentHash`, then after a node update, the old cache entry becomes orphaned (never evicted). Need explicit eviction strategy.

**Options to investigate:**
1. Use `nodeId` only, invalidate on change (simpler, no orphans)
2. Use `contentHash` only (content-addressed, auto-dedupe, but orphan risk)
3. Add LRU eviction for stale entries
4. Investigate how Irmin actually handles this with its content-addressing

**Action required:** Review Irmin's content-addressing behavior and decide eviction strategy before implementation.

---

## Code Smells

### ❌ Full Tree Recomputation

```scala
// BAD: Recompute entire tree on any change
def onNodeChanged(nodeId: NodeId): Task[Unit] =
  for
    tree <- irminClient.getFullTree
    lec  <- Simulator.simulateTree(tree)  // O(n)
  yield ()

// GOOD: Recompute only affected path
def onNodeChanged(nodeId: NodeId): Task[Unit] =
  cache.invalidate(nodeId).flatMap { ancestors =>
    ZIO.foreachDiscard(ancestors)(recomputeAndCache)  // O(depth)
  }
```

### ❌ No Parent Pointer Index

```scala
// BAD: Search tree to find ancestors
def findAncestors(nodeId: NodeId, tree: RiskTree): List[NodeId] =
  tree.nodes.flatMap(n => if containsDescendant(n, nodeId) then Some(n.id) else None)
  // O(n) search

// GOOD: O(1) lookup via parent map
def ancestorPath(nodeId: NodeId): List[NodeId] =
  index.parents.get(nodeId) match
    case None         => List(nodeId)
    case Some(parent) => nodeId :: ancestorPath(parent)
```

### ❌ Eager Full Recomputation

```scala
// BAD: Recompute all ancestors immediately
def onNodeChanged(nodeId: NodeId): Task[Unit] =
  for
    ancestors <- getAncestors(nodeId)
    _         <- ZIO.foreach(ancestors)(recompute)  // Blocks on all
  yield ()

// GOOD: Invalidate immediately, recompute lazily
def onNodeChanged(nodeId: NodeId): Task[Unit] =
  cache.invalidate(nodeId)  // Fast, O(depth) invalidation only
  // Recomputation happens on next read
```

---

## Implementation

| Component | Location | Purpose | Status |
|-----------|----------|---------|--------|
| `TreeIndex` | `domain/tree/TreeIndex.scala` | Parent-pointer navigation | ✅ Implemented |
| `RiskResultCache` | `services/cache/RiskResultCache.scala` | Per-node result storage | ✅ Implemented |
| `TreeCacheManager` | `services/cache/TreeCacheManager.scala` | Per-tree cache lifecycle + invalidation | ✅ Implemented |
| `RiskResultResolverLive` | `services/cache/RiskResultResolverLive.scala` | Lazy recomputation logic | ✅ Implemented |
| `InvalidationHandler` | `services/cache/InvalidationHandler.scala` | Handles Irmin change notifications | ✅ Implemented |

---

## Complexity Analysis

| Operation | Complexity | Notes |
|-----------|------------|-------|
| Node lookup | O(1) | Via `nodes` map |
| Find ancestors | O(depth) | Via `parents` map |
| Invalidate path | O(depth) | Walk to root |
| Recompute path | O(depth × sim) | `sim` = simulation cost per node |
| Full tree recompute | O(n × sim) | Avoided by this pattern |

---

## References

- Tree Zippers Conversation: Cached Subtree Aggregates pattern
- ADR-004a/b: Irmin notifications trigger invalidation
- ADR-003: Provenance for reproducible simulation
