# LEC Caching Implementation Plan

**ADR:** ADR-014  
**Status:** Phases 1–4 Complete  
**Created:** Based on ADR-014 decision (RiskResult Caching)  
**Last Updated:** 2026-02-09

## Executive Summary

This plan implements the RiskResult caching strategy defined in ADR-014. The key insight is that **tick domains are display-context dependent**—the X-axis range depends on which nodes are shown together. Therefore, we cache simulation outcomes (RiskResult), not rendered curves, and compute shared ticks at render time using `LECGenerator.generateCurvePointsMulti`.

---

## Current State Analysis

| Component | Status | Notes |
|-----------|--------|-------|
| `RiskResultCache` | ✅ Implemented | Caches `RiskResult` per `NodeId` |
| `TreeCacheManager` | ✅ Implemented | Per-tree cache lifecycle, invalidation |
| `LECGenerator` | ✅ Has `generateCurvePoints` | Multi-node shared ticks via `generateCurvePointsMulti` |
| `TreeIndex` | ✅ Wired | O(1) node lookup via `nodes: Map[NodeId, RiskNode]` |
| `CacheController` | ✅ Working | Admin endpoints for cache management |
| `RiskResultResolver` | ✅ Implemented | Cache-aside pattern: `ensureCached(tree, nodeId)` |

---

## Implementation Phases

### Phase 1: Remove Incorrect Cache Approach ✅

**Status:** Complete. The incorrect CurveBundle-based caching approach was replaced with `RiskResultCache`.

---

### Phase 2: RiskResultCache ✅

**Status:** Complete. Caches `RiskResult` (simulation outcomes) per `NodeId`.

**Components:**
1. `RiskResultCache` — per-node result storage
2. `TreeCacheManager` — per-tree cache lifecycle + invalidation
3. `RiskResultCacheSpec` — test coverage

**Trait:**
```scala
trait RiskResultCache:
  def get(nodeId: NodeId): UIO[Option[RiskResult]]
  def put(nodeId: NodeId, result: RiskResult): UIO[Unit]
  def invalidate(nodeId: NodeId): UIO[Set[NodeId]]  // returns all invalidated
  def clear: UIO[Unit]
  def stats: UIO[CacheStats]
```

---

### Phase 3: Add generateCurvePointsMulti

**Goal:** Compute LEC curves with shared tick domain at render time.

**File to modify:**
`modules/common/src/main/scala/.../domain/data/LECGenerator.scala`

**Add:**
```scala
def generateCurvePointsMulti(
  results: Map[NodeId, RiskResult], 
  nEntries: Int = 100
): Map[NodeId, Vector[(Loss, Double)]] = {
  val combinedMin = results.values.map(_.minLoss).min
  val combinedMax = results.values.map(_.maxLoss).max
  val sharedTicks = getTicks(combinedMin, combinedMax, nEntries)
  results.map { case (nodeId, result) =>
    nodeId -> sharedTicks.map(loss => (loss, result.probOfExceedance(loss).toDouble))
  }
}
```

**Tests:** Add to `LECGeneratorSpec.scala`

---

### Phase 4: Wire RiskResultCache to RiskTreeService ✅

**Goal:** `RiskTreeService` uses cache for simulation results.

**Completed:**
1. `RiskResultResolverLive` implements cache-aside pattern via `ensureCached(tree, nodeId)`
2. `RiskTreeServiceLive` delegates simulation to `RiskResultResolver`
3. Multi-node LEC uses `getLECCurvesMulti` with shared tick domain

---

### Phase 5: TreeIndex Wiring ✅

**Goal:** Real parent-pointer data instead of empty index.

**Completed:** `TreeIndex` is now built at tree construction time with `nodes: Map[NodeId, RiskNode]` providing O(1) lookup. `RiskTree.index` is populated automatically.

---

## Success Criteria

1. **RiskResultCache caches simulation outcomes**
2. **generateCurvePointsMulti produces shared tick domain**
3. **All existing tests pass**
4. **O(depth) invalidation** via TreeIndex.ancestorPath

---

## Estimated Effort

| Phase | Status | Notes |
|-------|--------|-------|
| 1: Remove incorrect cache | ✅ Complete | |
| 2: RiskResultCache | ✅ Complete | Per-node result storage |
| 3: generateCurvePointsMulti | ✅ Complete | Shared tick domain |
| 4: Wire to RiskTreeService | ✅ Complete | Via RiskResultResolver |
| 5: TreeIndex wiring | ✅ Complete | Built at tree construction |

All phases complete.
