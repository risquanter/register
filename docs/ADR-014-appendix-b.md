# ADR-014 Appendix B: Cache Invalidation & Request Flow

**Parent:** [ADR-014: RiskResult Caching Strategy](./ADR-014.md)

---

## 1. Invalidation Flow with Irmin Integration

When a node is modified (e.g., probability changed), the cache invalidation propagates up the ancestor chain:

```
User edits B's probability in UI
        │
        ▼
    Irmin persists change
        │
        ▼
    Irmin watch fires (IrminWatcher service)
        │
        ▼
    InvalidationHandler.handleNodeChange(treeId, "B")
        │
        ▼
    RiskResultCache.invalidate("B")
        │
        └─── ancestorPath("B") = [A, B]
        │
        ▼
    Cache removes entries for A and B (C preserved)
        │
        ▼
    SSEHub.publish(CacheInvalidated([A, B], treeId))
        │
        ▼
    Browser receives event → knows A and B need refresh
```

### Invalidation Examples

Given tree structure: `A → B → C` (A is root, C is leaf)

| Node Changed | `ancestorPath()` Returns | Cache Entries Removed |
|--------------|--------------------------|----------------------|
| C modified | `[A, B, C]` | A, B, C all invalidated |
| B modified | `[A, B]` | A, B invalidated (C preserved) |
| A modified | `[A]` | Only A invalidated (B, C preserved) |

---

## 2. Request Flow After Invalidation

When a client requests an LEC for a node that was invalidated:

```
GET /trees/1/nodes/A/lec
        │
        ▼
    cache.get("A") → MISS (was invalidated)
        │
        ▼
    simulateSubtree("A")  ← Full simulation of A → B → C
        │
        ▼
    cache.set("A", riskResultA)
        │
        ▼
    LECGenerator.generateCurvePointsMulti(results, nTicks)
        │
        ▼
    Return LECResponse with (loss, prob) points
```

### Key Semantic (ADR-014 Design)

- **Cache stores:** `RiskResult` (outcomes: `Map[TrialId, Loss]`)
- **On cache miss:** Subtree is re-simulated
- **On cache hit:** Outcomes available for exact `P(Loss >= x)` at any tick
- **Trade-off:** Memory cost (~80KB/node at 10K trials) for exact curve computation

---

## 3. Scenario Support

The same invalidation semantics apply to scenarios (branches):

```
Scenario branch created from main
        │
        ▼
    User modifies "B" in scenario
        │
        ▼
    Irmin persists to scenario branch
        │
        ▼
    InvalidationHandler.handleNodeChange(scenarioTreeId, "B")
        │
        ▼
    RiskResultCache.invalidate("B")  ← scenario's cache
        │
        ▼
    Main branch cache UNAFFECTED (different treeId)
```

Each scenario has its own cache namespace (keyed by `treeId`), so changes in one scenario don't invalidate another's cache.

---

*Appendix updated: 2026-01-18*
