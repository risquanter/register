# ADR-014 Appendix: LEC Caching Strategy Analysis

**Parent ADR:** [ADR-014-proposal.md](./ADR-014-proposal.md)  
**Date:** 2026-01-18

---

## Background

This appendix preserves the detailed analysis that led to the caching strategy decision in ADR-014.

---

## 1. Simulation Cost Analysis

### HDR PRNG Characteristics

The simulation uses Hash-Derived Random (HDR) PRNG which is:
- **Stateless**: `generate(trial, entityId, varId, seed3, seed4)` is a pure function
- **O(1) per sample**: Splitmix64 hash operations
- **Parallelizable**: No shared state between trials

### Cost by Trial Count

| nTrials | Simulation Time | Raw Outcome Size | LEC Generation |
|---------|-----------------|------------------|----------------|
| 10,000 | ~1-5ms | 80KB/node | ~1ms |
| 100,000 | ~10-50ms | 800KB/node | ~10ms |
| 1,000,000 | ~100-500ms | 8MB/node | ~100ms |

### Key Insight: HDR is Cheap

For 10K trials:
```
10K trials × 10 nodes × 2 samples/trial = 200K hash operations
At ~1ns/hash = 0.2ms simulation time
Add O(nTrials) curve generation = ~5ms total
```

---

## 2. Caching Options Evaluated

### Option A: Cache Raw Outcomes (RiskResult)

```scala
cache: Map[NodeId, RiskResult]  // Map[TrialId, Loss] per node
```

| Aspect | Analysis |
|--------|----------|
| **Memory** | ~80KB per node (10K trials × 8 bytes) × N nodes |
| **What it saves** | Simulation computation (HDR sampling + distribution quantile) |
| **What it costs** | Must recompute LEC curve on every request |
| **Tick alignment** | ✅ Perfect - regenerate at any tick domain |
| **Invalidation** | On node parameter change |

**Verdict**: Only valuable if simulation is expensive. With HDR, regeneration is cheap.

### Option B: Two-Stage Caching (Raw + CurveBundle)

```scala
outcomeCache: Map[NodeId, RiskResult]     // Level 1
curveCache: Map[Set[NodeId], CurveBundle] // Level 2
```

| Aspect | Analysis |
|--------|----------|
| **Memory** | High - both raw data AND rendered curves |
| **What it saves** | Both simulation AND curve generation |
| **Complexity** | High - two invalidation paths, cache coherence issues |
| **Tick alignment** | ✅ CurveBundle always aligned |

**Verdict**: Over-engineered. Adds complexity without proportional benefit.

### Option C: Cache Only Final CurveBundle ✅ SELECTED

```scala
cache: Map[(TreeId, Set[NodeId]), CurveBundle]
```

| Aspect | Analysis |
|--------|----------|
| **Memory** | ~1.6KB per curve × N curves |
| **What it saves** | LEC point generation |
| **What it costs** | Re-simulation on miss (~5-50ms) |
| **Tick alignment** | ✅ By construction (monoidal combine) |
| **Invalidation** | Node change → invalidate containing bundles |

**Verdict**: Elegant balance of simplicity and performance.

### Option D: No Caching

| Aspect | Analysis |
|--------|----------|
| **Memory** | Zero |
| **Latency** | Every request = full simulation |
| **Simplicity** | Maximum |

**Verdict**: Viable for development. Production needs Option C for concurrent users.

---

## 3. Category Theory Foundation

### Join-Semilattice on Tick Intervals

Tick domain unification forms a join-semilattice:

```scala
combineTickDomain: (Interval, Interval) → Interval
combineTickDomain((min₁, max₁), (min₂, max₂)) = (min(min₁, min₂), max(max₁, max₂))
```

Properties:
- Associative: `(a ⊔ b) ⊔ c = a ⊔ (b ⊔ c)`
- Commutative: `a ⊔ b = b ⊔ a`
- Idempotent: `a ⊔ a = a`

### Functor: Outcomes → Aligned Curves

The transformation from raw outcomes to aligned LEC curves is a functor:

```
F: List[RiskResult] → CurveBundle
F(outcomes) = {
  ticks = unifyTickDomains(outcomes)
  curves = outcomes.map(o => computeExceedanceAt(o, ticks))
}
```

### Identity Instance for CurveBundle

```scala
given Identity[CurveBundle] with {
  def identity = CurveBundle(Vector.empty, Map.empty, Map.empty)
  
  def combine(a: => CurveBundle, b: => CurveBundle) = {
    val ticks = unifyTicks(a.ticks, b.ticks)
    CurveBundle(
      ticks = ticks,
      curves = realign(a, ticks) ++ realign(b, ticks),
      metadata = a.metadata ++ b.metadata
    )
  }
}
```

---

## 4. Tick Domain Monotonicity Property

### The Insight

For any tree:
```
        portfolio
          /    \
     ops-risk   market-risk
       /   \
   cyber  hardware
```

The tick domain of a parent is always **at least as wide** as any child:
- `cyber`: `[100, 5000]`
- `hardware`: `[50, 3000]`
- `ops-risk`: `[50, 5000]` = union of children
- `portfolio`: `[50, 10000]` = union of all

### Navigation Implications

| Action | Tick Domain Change | Required Work |
|--------|-------------------|---------------|
| Expand child (within tree) | NoChange | O(nTicks) - add curve only |
| Collapse child | NoChange | O(1) - remove from map |
| Zoom in (narrow range) | Narrowing | Regenerate at finer ticks |
| Zoom out (widen range) | Widening | Regenerate at wider ticks |

### Key Optimization

Most navigation (expand/collapse) **does not change tick domain**, enabling:

```scala
// Expanding to show cyber - NO regeneration of existing curves
existingBundle.addCurve(cyberId, cyberOutcomes, cyberMeta)  // O(nTicks)
```

---

## 5. High Load Scenarios

### Frequent Parameter Changes

Each parameter change invalidates affected bundles:
- Change cyber probability → invalidate bundles containing cyber
- Sibling bundles (not containing cyber) remain valid
- Re-simulation on next request (~5ms)

### Scenario Drafting (Branches)

Different seed3/seed4 values create independent cache entries:
```scala
key1 = (treeId=1, nodes={root,cyber}, seeds=(0,0))
key2 = (treeId=1, nodes={root,cyber}, seeds=(42,0))  // Different scenario
```

Each scenario variant is independently cached.

### Active Navigation

With incremental extension, navigation is cheap:
- Expand: `addCurve()` = O(nTicks)
- Collapse: `removeCurve()` = O(1)
- Full regeneration only on tick domain change

---

## 6. Current Infrastructure Status

### What Exists (Pre-ADR-014)

| Component | Status | Notes |
|-----------|--------|-------|
| `LECCache` | ⚠️ Exists, not wired | Caches `LECCurveResponse` (wrong type) |
| `TreeIndex` | ⚠️ Exists, empty | For O(depth) invalidation |
| `CacheInvalidator` | ❌ Not implemented | Irmin watch → cache |
| `RiskTreeResult` | ❌ Not cached | Raw outcomes |

### Post-ADR-014 Design

| Component | New Status | Action |
|-----------|------------|--------|
| `CurveBundleCache` | 🆕 New | Replaces LECCache purpose |
| `LECCache` | 🔄 Repurpose or remove | May keep for single-node responses |
| `TreeIndex` | ✅ Wire to cache | For invalidation path lookup |
| `CacheInvalidator` | 🆕 New | Irmin watch → `CurveBundleCache.invalidateNode` |

---

## 7. Decision Summary

**Selected: Option C (Cache Final CurveBundle)**

Reasons:
1. **Cheap simulation**: HDR PRNG makes re-simulation fast (~5ms)
2. **Memory efficient**: ~1.6KB per curve vs 80KB raw outcomes
3. **Elegant math**: Monoidal combine with join-semilattice ticks
4. **Navigation optimized**: Incremental extension for most operations
5. **Simple invalidation**: Node change → drop containing bundles

Trade-offs accepted:
- Cache miss = re-simulation (acceptable at ~5-50ms)
- Tick domain widening = full regeneration (rare operation)
