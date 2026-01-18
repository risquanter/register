# ADR-015-proposal: RiskResult Cache Integration

**Status:** Proposal  
**Date:** 2026-01-18  
**Supersedes:** Parts of ADR-014 (refines cache wiring details)

---

## Context

We have implemented `RiskResultCache` (ADR-014) but need to define how it integrates with the simulation and query APIs. The existing `RiskTreeResult` structure packages simulation outputs into a tree, but this is redundant with the cache.

### Key Insight

The tree STRUCTURE comes from `RiskNode` (the definition stored in Irmin). The simulation RESULTS are `RiskResult` per node. `RiskTreeResult` couples these unnecessarily.

---

## Decision

### Remove RiskTreeResult, Use Cache Directly

**Current model:**
```
Simulation → RiskTreeResult (tree of results) → Extract per-node → Use
```

**Proposed model:**
```
Simulation → Cache (RiskResult per node) → Query cache directly
```

### Core Abstraction: RiskResultResolver

Separate service that provides the `ensureCached` primitive. Cache remains pure storage.

```scala
trait RiskResultResolver:
  /** Core primitive: ensure result is cached, simulate if needed */
  def ensureCached(nodeId: NodeId): Task[RiskResult]
  
  /** Ensure multiple nodes are cached */
  def ensureCachedAll(nodeIds: Set[NodeId]): Task[Map[NodeId, RiskResult]]
```

All query methods become simple `map` compositions over this primitive.

### Data Flow

```
Irmin (persistent)     ←→     RiskTreeRepository
        ↓
    RiskNode (tree definition)
        ↓
    RiskResultResolver.ensureCached(nodeId)
        ↓
    [cache hit?] → return cached RiskResult
    [cache miss?] → simulate subtree → cache all → return RiskResult
        ↓
    LECGenerator / probOfExceedance (pure transforms)
```

---

## API Design

### RiskResultResolver Service (New)

```scala
trait RiskResultResolver:
  /** Core primitive: ensure result is cached, simulate if needed */
  def ensureCached(nodeId: NodeId): Task[RiskResult]
  
  /** Ensure multiple nodes are cached */
  def ensureCachedAll(nodeIds: Set[NodeId]): Task[Map[NodeId, RiskResult]] =
    ZIO.foreach(nodeIds.toList)(id => ensureCached(id).map(id -> _))
      .map(_.toMap)

class RiskResultResolverLive(
  cache: RiskResultCache,
  nodeIndex: NodeIndex,       // lookup RiskNode by ID
  simulator: SimulationExecutionService
) extends RiskResultResolver:
  
  def ensureCached(nodeId: NodeId): Task[RiskResult] =
    cache.get(nodeId).flatMap {
      case Some(r) => ZIO.succeed(r)
      case None    => simulateSubtree(nodeId)
    }
```

### Query APIs (Compositions over ensureCached)

```scala
// LEC curve for single node
def getLECCurve(nodeId: NodeId): Task[Vector[(Long, Double)]] =
  resolver.ensureCached(nodeId).map(LECGenerator.generateCurvePoints(_))

// Exceedance probability for single node
def probOfExceedance(nodeId: NodeId, threshold: Loss): Task[BigDecimal] =
  resolver.ensureCached(nodeId).map(_.probOfExceedance(threshold))

// LEC curves for multiple nodes (shared tick domain)
def getLECCurvesMulti(nodeIds: Set[NodeId]): Task[Map[NodeId, Vector[(Long, Double)]]] =
  resolver.ensureCachedAll(nodeIds).map(LECGenerator.generateCurvePointsMulti)
```

### RiskResultCache (Pure Storage)

```scala
trait RiskResultCache:
  def get(nodeId: NodeId): UIO[Option[RiskResult]]
  def put(nodeId: NodeId, result: RiskResult): UIO[Unit]
  def invalidate(nodeId: NodeId): UIO[List[NodeId]]  // clears node + ancestors
  def clear: UIO[Unit]
  def size: UIO[Int]
```

Cache knows nothing about simulation. It's pure get/put/invalidate.

---

## Simulation Logic

### simulateSubtree

Cache-aware simulation that traverses only the necessary subtree:

```scala
def simulateSubtree(nodeId: NodeId): Task[RiskResult] =
  for
    node <- lookupNodeDefinition(nodeId)  // from RiskTree/RiskNode structure
    result <- node match
      case leaf: RiskLeaf =>
        // Simulate leaf directly
        simulateLeaf(leaf).tap(r => cache.put(nodeId, r))
        
      case portfolio: RiskPortfolio =>
        // Recurse to children, combine
        for
          childResults <- ZIO.foreach(portfolio.children)(child =>
            cache.get(child.id).flatMap {
              case Some(r) => ZIO.succeed(r)  // reuse cached
              case None    => simulateSubtree(child.id)  // recurse
            }
          )
          combined = childResults.combineAll  // using Identity[RiskResult]
          _ <- cache.put(nodeId, combined)
        yield combined
  yield result
```

### Simulation Direction

The traversal is top-down (from requested node to leaves), but computation is bottom-up (leaves combine into parents):

```
1. Start at requested node           ← top-down TRAVERSAL
2. Recurse to children  
3. Recurse to leaves
4. Simulate leaf → RiskResult → CACHE IT   ← bottom-up COMPUTATION
5. Combine children → parent → CACHE IT
6. Continue up to requested node
7. Return node's RiskResult
```

---

## Cache Invalidation

### RiskResultCache Interface

```scala
trait RiskResultCache:
  def get(nodeId: NodeId): UIO[Option[RiskResult]]
  def put(nodeId: NodeId, result: RiskResult): UIO[Unit]
  def invalidate(nodeId: NodeId): UIO[List[NodeId]]  // clears node + ancestors
  def clear: UIO[Unit]
  def size: UIO[Int]
```

### Invalidation Uses TreeIndex

```scala
def invalidate(nodeId: NodeId): UIO[List[NodeId]] =
  for
    path <- ZIO.succeed(treeIndex.ancestorPath(nodeId))  // [root, ..., parent, nodeId]
    _    <- cacheRef.update(cache => cache -- path)
  yield path
```

---

## Invalidation Walkthrough

### Tree Structure

```
        portfolio (root)
           /    \
      ops-risk   market-risk
        /   \
    cyber  hardware
```

### Initial State (all cached)

```
cache["cyber"]       = RiskResult(outcomes=...)
cache["hardware"]    = RiskResult(outcomes=...)
cache["ops-risk"]    = RiskResult(outcomes=...)  // combined cyber+hardware
cache["market-risk"] = RiskResult(outcomes=...)
cache["portfolio"]   = RiskResult(outcomes=...)  // combined all
```

### Scenario: User Modifies "hardware" Probability

**Step 1: Invalidation triggered**

```scala
cache.invalidate(hardwareId)

// TreeIndex.ancestorPath(hardware) returns:
// List(portfolio, ops-risk, hardware)

// Cache entries cleared:
cache.remove(hardware)
cache.remove(ops-risk)
cache.remove(portfolio)
```

**After invalidation:**

```
cache["cyber"]       = RiskResult(...)   ✓ PRESERVED (not ancestor)
cache["hardware"]    = <empty>           ✗ CLEARED
cache["ops-risk"]    = <empty>           ✗ CLEARED (ancestor)
cache["market-risk"] = RiskResult(...)   ✓ PRESERVED (sibling branch)
cache["portfolio"]   = <empty>           ✗ CLEARED (ancestor)
```

---

**Step 2: User requests LEC for "hardware"**

```scala
getLECCurve(hardwareId)

// cache.get(hardwareId) → None (cache miss)
// simulateSubtree(hardwareId) called
```

For hardware (a leaf):
1. Simulate leaf → `RiskResult`
2. `cache.put("hardware", result)`
3. Return result

**After this request:**

```
cache["cyber"]       = RiskResult(...)   unchanged
cache["hardware"]    = RiskResult(...)   ✓ REPOPULATED
cache["ops-risk"]    = <empty>           still empty
cache["market-risk"] = RiskResult(...)   unchanged
cache["portfolio"]   = <empty>           still empty
```

---

**Step 3: User requests LEC for "portfolio"**

```scala
getLECCurve(portfolioId)

// cache.get(portfolioId) → None (miss)
// simulateSubtree(portfolioId) called
```

Traversal:
1. Portfolio has children: `[ops-risk, market-risk]`
2. For `ops-risk`: cache miss → recurse
   - Children: `[cyber, hardware]`
   - `cyber`: cache **HIT** → reuse
   - `hardware`: cache **HIT** → reuse (from step 2)
   - Combine → `cache.put("ops-risk", combined)`
3. For `market-risk`: cache **HIT** → reuse
4. Combine `[ops-risk-result, market-risk-result]`
5. `cache.put("portfolio", combined)`

**What got recomputed:**
- `ops-risk` combination (not simulation, just `combineAll`)
- `portfolio` combination

**What was reused (no simulation):**
- `cyber` (cached)
- `hardware` (cached from step 2)
- `market-risk` (cached)

---

## Direction Summary

| Operation | Direction | Scope |
|-----------|-----------|-------|
| Invalidation | Bottom-up | Node → root (ancestors) |
| Simulation (on miss) | Top-down then bottom-up | Requested node → leaves → back up |
| Combine | Bottom-up | Leaves → requested node |

---

## Irmin Interaction

Irmin stores the **tree definition** (`RiskNode`). It does NOT store simulation results.

```
Irmin (persistent)
    ↓
RiskNode (tree definition)
    ↓
Simulation (in-memory)
    ↓
RiskResult per node (cached in-memory, NOT in Irmin)
```

`RiskTreeResult` never touches Irmin. Removing it has no effect on persistence.

---

## Migration Path

### Phase 1: Create RiskResultResolver Service

1. Create `RiskResultResolver` trait with `ensureCached` and `ensureCachedAll`
2. Create `RiskResultResolverLive` implementation
3. Wire to `RiskResultCache` and simulation services
4. Unit tests for resolver

### Phase 2: Create NodeIndex for Node Lookup

1. Create `NodeIndex` trait - lookup `RiskNode` by `NodeId`
2. Implement from tree structure (walk or index at load time)
3. Wire to `RiskResultResolver`

### Phase 3: Refactor RiskTreeServiceLive

1. Replace direct simulation calls with `resolver.ensureCached`
2. Replace `convertResultToLEC` with `LECGenerator.generateCurvePoints`
3. Get `childIds` from `RiskNode` (via NodeIndex), not `RiskTreeResult`
4. Update tests

### Phase 4: Delete RiskTreeResult

1. Remove `TreeResult.scala`
2. Update any remaining references
3. Verify all tests pass

### Phase 5: Integration Tests

1. Test cache hit/miss behavior via API
2. Test invalidation clears correct entries
3. Test recomputation reuses cached siblings

---

## Open Questions

1. **Simulation parameters in cache key?**  
   Current: key is `NodeId` only.  
   Should it be `(NodeId, nTrials, seed3, seed4)`?

2. **lookupNodeDefinition implementation?**  
   Need way to get `RiskNode` by ID from tree structure.  
   Options: walk from root, or index nodes at tree load time.

3. **Provenance on cache hit?**  
   Currently provenance is generated during simulation.  
   On cache hit, no simulation runs. Cache provenance too, or skip?

---

## Consequences

### Positive

- Cleaner separation: definition (RiskNode) vs results (RiskResult)
- Cache becomes single source of truth for simulation outputs
- Fine-grained invalidation and recomputation
- Simpler return types from simulation

### Negative

- Requires refactoring simulation layer
- Need to ensure cache is always populated before query
- `lookupNodeDefinition` requires node index or tree walking

---

## Related

- **ADR-014:** RiskResult Caching Strategy (parent ADR)
- **ADR-014-appendix-b:** Cache Invalidation Flow
- **IMPLEMENTATION-PLAN-LEC-CACHING.md:** Implementation phases
