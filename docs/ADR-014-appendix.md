# ADR-014 Appendix: LEC Caching Performance Analysis

**Parent ADR:** [ADR-014.md](./ADR-014.md)  
**Date:** 2026-01-18

---

## Purpose

This appendix preserves the performance analysis that informed the caching strategy decision in ADR-014.

---

## 1. Simulation Cost Analysis

### HDR PRNG Characteristics

The simulation uses Hash-Derived Random (HDR) PRNG which is:
- **Stateless**: `generate(trial, entityId, varId, seed3, seed4)` is a pure function
- **O(1) per sample**: Splitmix64 hash operations
- **Parallelizable**: No shared state between trials

### Cost by Trial Count

| nTrials | Simulation Time | Outcome Size | LEC Generation |
|---------|-----------------|--------------|----------------|
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

## 2. Memory Trade-offs

### RiskResult is Cached

| Nodes | Trials | Memory |
|-------|--------|--------|
| 10 | 10K | 800KB |
| 100 | 10K | 8MB |
| 1000 | 10K | 80MB |

Acceptable for in-memory caching with JVM heap sizes of 512MB+.

### Why Outcomes Over Rendered Curves

Caching rendered curves was considered but rejected:
- Curves depend on **display context** (which nodes are shown together)
- Shared tick domain computed at render time from all displayed nodes
- Caching curves would require interpolation for new tick domains → mathematical errors
- Outcomes enable **exact** `P(Loss >= x)` computation at any tick

---

## 3. High Load Scenarios

### Frequent Parameter Changes

Each parameter change invalidates affected cache entries:
- Change node probability → invalidate node + ancestors (O(depth))
- Sibling subtrees remain valid
- Re-simulation on next request (~5-50ms depending on tree size)

### Scenario Drafting (Branches)

Different seed3/seed4 values create independent cache entries:
```
key1 = (nodeId, seeds=(0,0))      // Main scenario
key2 = (nodeId, seeds=(42,0))     // Draft scenario
```

Each scenario variant is independently cached.

### Concurrent Users

With RiskResult caching:
- First user triggers simulation → cached
- Subsequent users get cached results
- Cache invalidation is surgical (O(depth) per change)
