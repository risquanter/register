# Irmin Integration Status Report

**Date:** 2026-01-20  
**Scope:** API Integration Tests with Irmin Persistence  
**Status:** 🟡 **IN PROGRESS** — Irmin client integration tests exist; repository + HTTP integration still pending (requires running Irmin container)

---

## Table of Contents

1. [Initial Plan Summary](#1-initial-plan-summary)
2. [Decision Points Encountered](#2-decision-points-encountered)
3. [Choices Made](#3-choices-made)
4. [Current Code State](#4-current-code-state)
5. [What's Left To Do](#5-whats-left-to-do)
6. [Next Steps](#6-next-steps)

---

## 1. Initial Plan Summary

### Objective

Create comprehensive end-to-end integration tests that:
- Test the full HTTP API layer (triggered via HTTP requests)
- Cover CRUD operations for risk trees
- Test LEC query endpoints
- Verify caching and cache invalidation behavior
- Use the manually-managed docker-compose Irmin container

### Proposed Test Structure

```
modules/server-it/src/test/scala/com/risquanter/register/
  http/
    RiskTreeApiIntegrationSpec.scala    # CRUD + LEC endpoints
    CacheApiIntegrationSpec.scala       # Cache management + invalidation
```

### Test Cases Planned

**RiskTreeApiIntegrationSpec:**
- `POST /risk-trees` - Create risk tree
- `GET /risk-trees` - Get all risk trees
- `GET /risk-trees/{id}` - Get by ID
- `GET /risk-trees/{treeId}/nodes/{nodeId}/lec` - Get LEC curve
- `POST /risk-trees/{treeId}/nodes/lec-multi` - Get multiple LEC curves
- `GET /risk-trees/{treeId}/nodes/{nodeId}/prob-of-exceedance` - Exceedance probability

**CacheApiIntegrationSpec:**
- `GET /risk-trees/{treeId}/cache/stats` - Cache stats
- `GET /risk-trees/{treeId}/cache/nodes` - Cached nodes
- `POST /risk-trees/{id}/invalidate/{nodeId}` - Cache invalidation
- `DELETE /risk-trees/{treeId}/cache` - Clear tree cache

---

## 2. Decision Points Encountered

### Decision 1: Irmin Persistence Scope

**Question:** Should integration tests verify Irmin persistence (requires modifying Application wiring to use IrminClient), or should they focus on HTTP→Service→Cache flow with in-memory persistence?

**User Answer:** ✅ Include wiring Irmin for persistence

### Decision 2: Test Server Approach

**Question:** Should tests use:
- Option A: ZIO Test's `serverEndpoint.testIn` for simulated HTTP (faster)
- Option B: Start actual HTTP server and use sttp client (more realistic)

**User Answer:** ✅ Start a real sttp server for the test

### Decision 3: Storage Approach for RiskTreeRepositoryIrmin

**Question:** Which storage model to implement?

- **Option A - Per-Node Storage** (matches ADR-004a):
  ```
  risk-trees/{treeId}/nodes/{nodeId}
  ```
  - Fine-grained Irmin watch notifications
  - Multiple Irmin operations per tree CRUD
  - More complex implementation

- **Option B - Per-Tree Storage** (simpler):
  ```
  risk-trees/{treeId}/definition  → entire RiskTree JSON
  risk-trees/{treeId}/meta        → tree metadata
  ```
  - Simpler CRUD
  - Coarser watch notifications

**User Answer:** ✅ **Option A with bidirectional references**

This means:
- Each node stored at path `risk-trees/{treeId}/nodes/{nodeId}`
- RiskNode has `parentId: Option[NodeId]` field
- RiskPortfolio has `childIds: Array[NodeId]` (references, not embedded objects)
- Tree reconstruction by querying all nodes under `risk-trees/{treeId}/nodes/*`

---

## 3. Choices Made

### ✅ Completed: Flat Node Model Refactoring

The "Full Refactoring Walkthrough: childIds + parentId" is **complete**:

| Component | Status |
|-----------|--------|
| `RiskNode.parentId: Option[NodeId]` | ✅ Implemented ([RiskNode.scala](../modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala) L38) |
| `RiskPortfolio.childIds: Array[NodeId]` | ✅ Implemented ([RiskNode.scala](../modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala) L404) |
| `TreeIndex.fromNodes(Map[NodeId, RiskNode])` | ✅ Implemented ([TreeIndex.scala](../modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala) L133) |
| Consistency validation (parent↔child bidirectional) | ✅ Implemented ([TreeIndex.scala](../modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala) L156-200) |
| `RiskResultResolverLive.simulateNode` uses `childIds` + index lookup | ✅ Implemented ([RiskResultResolverLive.scala](../modules/server/src/main/scala/com/risquanter/register/services/cache/RiskResultResolverLive.scala) L117-132) |

---

## 4. Current Code State

### Existing Infrastructure

| Component | File | Status |
|-----------|------|--------|
| `RiskTreeRepository` trait | [RiskTreeRepository.scala](../modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala) | ✅ Exists |
| `RiskTreeRepositoryInMemory` | [RiskTreeRepositoryInMemory.scala](../modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala) | ✅ Exists |
| `IrminClient` trait | [IrminClient.scala](../modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala) | ✅ Exists |
| `IrminClientLive` | [IrminClientLive.scala](../modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala) | ✅ Exists |
| `IrminPath` | `infra/irmin/model/IrminPath.scala` | ✅ Exists |
| `IrminClientIntegrationSpec` | [IrminClientIntegrationSpec.scala](../modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala) | ✅ Exists (8 tests, require Irmin container) |

### Missing Infrastructure

| Component | Status | Description |
|-----------|--------|-------------|
| `RiskTreeRepositoryIrmin` | 🔴 **NOT CREATED** | Irmin-backed implementation of `RiskTreeRepository` |
| `RiskTreeApiIntegrationSpec` | 🔴 **NOT CREATED** | HTTP CRUD + LEC endpoint tests |
| `CacheApiIntegrationSpec` | 🔴 **NOT CREATED** | Cache management tests |
| Test server infrastructure | 🔴 **NOT CREATED** | Real HTTP server for integration tests |
| Application wiring for Irmin | 🔴 **NOT DONE** | Currently uses `RiskTreeRepositoryInMemory.layer` |

### Application.scala Current Wiring

```scala
// Line 53 in Application.scala
RiskTreeRepositoryInMemory.layer,  // ← Currently in-memory only
```

---

## 5. What's Left To Do

### Phase 1: RiskTreeRepositoryIrmin (Blocked Here)

**File to create:** `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`

**Requirements:**
1. Implement `RiskTreeRepository` trait
2. Use `IrminClient` for storage operations
3. Follow per-node storage model:
   ```
  risk-trees/{treeId}/nodes/{nodeId} → JSON of RiskLeaf | RiskPortfolio
  risk-trees/{treeId}/meta           → { name, rootId }
   ```
4. Reconstruct `RiskTree` from flat nodes + `TreeIndex.fromNodes()`

**Key operations:**
- `create(RiskTree)`: Write each node to `risk-trees/{id}/nodes/{nodeId}`, write meta
- `getById(id)`: Read all nodes from `risk-trees/{id}/nodes/*`, reconstruct tree
- `update(id, op)`: Read tree, apply op, diff nodes, write changes
- `delete(id)`: Remove all nodes under `risk-trees/{id}/`
- `getAll`: List `risk-trees/*/meta`, reconstruct each tree

### Phase 2: Test Server Infrastructure

Create reusable test layer that:
- Starts real HTTP server on random port
- Uses `IrminClientLive` + `RiskTreeRepositoryIrmin`
- Provides sttp client for making requests

### Phase 3: RiskTreeApiIntegrationSpec

Full CRUD + LEC endpoint tests.

### Phase 4: CacheApiIntegrationSpec

Cache management + invalidation tests.

---

## 6. Next Steps

### Immediate Action Required

**Approve creation of `RiskTreeRepositoryIrmin.scala`?**

This file will:
1. Implement `RiskTreeRepository` using `IrminClient`
2. Follow per-node storage at `risk-trees/{treeId}/nodes/{nodeId}`
3. Store tree metadata at `risk-trees/{treeId}/meta`
4. Use `TreeIndex.fromNodes()` for reconstruction with validation

**Estimated effort:** 2-3 hours

### Sequence

1. ✅ Flat node model refactoring (DONE)
2. ⏳ Create `RiskTreeRepositoryIrmin` (NEXT)
3. ⏳ Create test server infrastructure
4. ⏳ Create `RiskTreeApiIntegrationSpec`
5. ⏳ Create `CacheApiIntegrationSpec`
6. ⏳ Wire Irmin into Application (optional, for non-test use)

---

## References

- [ADR-004a-proposal.md](ADR-004a-proposal.md) - Persistence architecture with SSE
- [IRMIN-INTEGRATION.md](IRMIN-INTEGRATION.md) - Irmin integration guide
- [IrminClientIntegrationSpec.scala](../modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala) - Working Irmin client tests

---

*Report generated: 2026-01-20*
