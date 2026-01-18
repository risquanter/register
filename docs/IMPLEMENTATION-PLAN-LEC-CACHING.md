# LEC Caching Implementation Plan

**ADR:** ADR-014  
**Status:** Implementation Ready  
**Created:** Based on ADR-014 decision (RiskResult Caching)

## Executive Summary

This plan implements the RiskResult caching strategy defined in ADR-014. The key insight is that **tick domains are display-context dependent**—the X-axis range depends on which nodes are shown together. Therefore, we cache simulation outcomes (RiskResult), not rendered curves, and compute shared ticks at render time using `LECGenerator.generateCurvePointsMulti`.

---

## Current State Analysis

| Component | Status | Notes |
|-----------|--------|-------|
| `LECCache` | ⚠️ Wrong cache type | Caches LECCurveResponse, needs to cache RiskResult |
| `CurveBundle*` | ❌ Wrong approach | All CurveBundle code to be deleted |
| `LECGenerator` | ✅ Has `generateCurvePoints` | Needs `generateCurvePointsMulti` |
| `TreeIndex` | ⚠️ Wired as empty | Provides O(depth) invalidation paths |
| `CacheController` | ✅ Working | Admin endpoints for cache management |
| `RiskTreeService` | ⚠️ No caching | Does not use cache |

---

## Implementation Phases

### Phase 1: Delete Wrong CurveBundle Code

**Goal:** Remove all code based on the wrong caching approach.

**Files to delete:**
1. `modules/common/src/main/scala/.../bundle/CurveBundle.scala`
2. `modules/common/src/test/scala/.../bundle/CurveBundleSpec.scala`
3. `modules/server/src/main/scala/.../services/CurveBundleExecutor.scala`
4. `modules/server/src/test/scala/.../services/CurveBundleExecutorSpec.scala`

**Verification:** `sbt compile test` passes after deletion.

---

### Phase 2: Refactor LECCache → RiskResultCache

**Goal:** Cache `RiskResult` (simulation outcomes) instead of UI DTOs.

**Files to modify:**
1. `modules/server/src/main/scala/.../services/cache/LECCache.scala` → `RiskResultCache.scala`
   - Change cached type: `Map[NodeId, RiskResult]`
   - Keep `TreeIndex` dependency for O(depth) invalidation
   - `ancestorPath` unchanged - invalidates from leaf to root

2. `modules/server/src/test/scala/.../services/cache/LECCacheSpec.scala` → `RiskResultCacheSpec.scala`
   - Update tests for RiskResult caching

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

### Phase 4: Wire RiskResultCache to RiskTreeService

**Goal:** `RiskTreeService` uses cache for simulation results.

**Files to modify:**
1. `Application.scala`: Wire `RiskResultCache` instead of `LECCache`
2. `RiskTreeServiceLive.scala`: Add cache dependency and cache-aside pattern

**Pattern:**
```scala
def getNodeLEC(nodeId: NodeId): Task[LECCurveResponse] =
  for
    cached <- cache.get(nodeId)
    result <- cached match
      case Some(r) => ZIO.succeed(r)
      case None    => simulate(nodeId).tap(r => cache.put(nodeId, r))
    curve  = LECGenerator.generateCurvePoints(result)
  yield LECCurveResponse.fromPoints(curve)
```

For multi-node display:
```scala
def getMultiNodeLEC(nodeIds: Set[NodeId]): Task[Map[NodeId, LECCurveResponse]] =
  for
    results <- ZIO.foreach(nodeIds.toList)(id => cache.get(id).map(id -> _))
    // fetch missing, populate cache, then:
    curves  = LECGenerator.generateCurvePointsMulti(allResults)
  yield curves.map((id, pts) => id -> LECCurveResponse.fromPoints(pts))
```

---

### Phase 5: TreeIndex Wiring (Future)

**Goal:** Real parent-pointer data instead of empty index.

**Deferred:** Current `TreeIndex.empty` works for development. Real wiring needed when Irmin integration is complete.

---

## Success Criteria

1. **All CurveBundle* files deleted**
2. **RiskResultCache caches simulation outcomes**
3. **generateCurvePointsMulti produces shared tick domain**
4. **All existing tests pass** after refactoring
5. **O(depth) invalidation** via TreeIndex.ancestorPath

---

## Estimated Effort

| Phase | Effort | Risk |
|-------|--------|------|
| 1: Delete CurveBundle | 30 min | None |
| 2: Refactor LECCache | 2 hours | Low |
| 3: generateCurvePointsMulti | 1 hour | Low |
| 4: Wire to RiskTreeService | 2-3 hours | Medium |
| 5: TreeIndex (deferred) | - | - |

**Total:** ~5-7 hours

---

## Next Action

**Proceed with Phase 1: Delete Wrong CurveBundle Code?**
