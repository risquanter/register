# ADR-014: LEC Curve Bundle Caching Strategy

**Status:** Proposed  
**Date:** 2026-01-18  
**Tags:** caching, LEC, curves, performance, ZIO Prelude

---

## Context

- LEC curves must be **tick-aligned** for multi-curve Vega-Lite rendering
- Parent node tick domains **contain** all descendant domains (monotonicity)
- HDR PRNG simulation is **cheap** (~1-5ms per node at 10K trials)
- Navigation actions (expand/collapse) are **frequent** in the GUI
- Curve identity must be **preserved** for theme color mapping

---

## Decision

### 1. CurveBundle as Monoidal Data Structure

Bundle aligned curves with preserved identity using ZIO Prelude Identity:

```scala
case class CurveBundle(
  ticks: Vector[Long],                    // Shared X-axis
  curves: Map[NodeId, Vector[Double]],    // ID → exceedance probabilities
  metadata: Map[NodeId, CurveMetadata]    // ID → name, color hints
)

object CurveBundle {
  given Identity[CurveBundle] with {
    def identity = CurveBundle(Vector.empty, Map.empty, Map.empty)
    def combine(a: => CurveBundle, b: => CurveBundle) = {
      val ticks = unifyTicks(a.ticks, b.ticks)  // Join-semilattice
      CurveBundle(ticks, realign(a, ticks) ++ realign(b, ticks), ...)
    }
  }
}
```

### 2. Cache Final Bundles Only (Option C)

Cache rendered `CurveBundle`, not raw simulation outcomes:

```scala
trait CurveBundleCache {
  def get(treeId: Long, nodeIds: Set[NodeId]): UIO[Option[CurveBundle]]
  def getSubset(treeId: Long, nodeIds: Set[NodeId]): UIO[Option[CurveBundle]]
  def set(treeId: Long, bundle: CurveBundle): UIO[Unit]
  def invalidateNode(treeId: Long, nodeId: NodeId): UIO[Int]
}
```

### 3. Incremental Bundle Extension

Expand bundles without full regeneration when tick domain doesn't change:

```scala
def addCurve(nodeId: NodeId, outcomes: Map[TrialId, Loss]): CurveBundle = {
  // O(nTicks) - compute exceedance at existing ticks only
  val points = ticks.map(t => outcomes.count(_._2 >= t).toDouble / outcomes.size)
  copy(curves = curves + (nodeId -> points), ...)
}
```

### 4. Tick Domain Monotonicity

Parent domains contain child domains—expanding within tree doesn't change ticks:

```scala
// ops-risk domain: [50, 5000]
// cyber domain:    [100, 3000] ⊆ [50, 5000]
// Expanding to show cyber: NO tick recalculation needed
bundle.addCurve(cyberId, cyberOutcomes, cyberMeta)  // O(nTicks)
```

### 5. Invalidation on Node Change

Irmin notifications trigger surgical cache invalidation:

```scala
// Node parameter changed → invalidate bundles containing that node
irminWatch.onNodeChange(nodeId) *> 
  curveBundleCache.invalidateNode(treeId, nodeId)
```

---

## Code Smells

### ❌ Caching Raw Outcomes

```scala
// BAD: Caching simulation results (80KB/node, wasteful)
cache: Map[NodeId, RiskResult]  // outcomes: Map[TrialId, Loss]

// GOOD: Cache only rendered bundles (1.6KB/curve)
cache: Map[(Long, Set[NodeId]), CurveBundle]
```

### ❌ Full Regeneration on Navigation

```scala
// BAD: Regenerate entire bundle when expanding
def expand(nodeId: NodeId) = computeFullBundle(currentNodes + nodeId)

// GOOD: Incremental extension
def expand(nodeId: NodeId) = 
  existingBundle.addCurve(nodeId, fetchOutcomes(nodeId), meta)
```

### ❌ Separate Tick Domains per Curve

```scala
// BAD: Each curve has its own ticks (misaligned for Vega-Lite)
curves: List[LECCurveResponse]  // Each with different tick vectors

// GOOD: Shared tick domain via monoidal combine
bundle: CurveBundle  // Single ticks vector, all curves aligned
```

### ❌ Losing Curve Identity

```scala
// BAD: Anonymous curve list
curves: Vector[(Vector[Long], Vector[Double])]

// GOOD: Identity-preserving map
curves: Map[NodeId, Vector[Double]]  // ID → points
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `domain/data/CurveBundle.scala` | Monoidal data structure |
| `services/cache/CurveBundleCache.scala` | Cache trait + live impl |
| `services/RiskTreeServiceLive.scala` | Integrate cache on LEC compute |
| `services/CacheInvalidator.scala` | Irmin watch → invalidation |
| `http/cache/CacheController.scala` | Admin endpoints (existing) |

---

## References

- [ADR-014-appendix.md](./ADR-014-appendix.md) — Full analysis of caching options
- [ADR-005-proposal.md](./ADR-005-proposal.md) — Cached Subtree Aggregates (superseded by this ADR)
- ZIO Prelude Identity: https://zio.dev/zio-prelude/functional-abstractions/identity
