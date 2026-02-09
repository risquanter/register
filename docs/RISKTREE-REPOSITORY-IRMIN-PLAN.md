# RiskTreeRepositoryIrmin Implementation Plan

**Date:** 2026-01-20  
**Status:** Implemented (per-node); node IDs now ULID; repository integration tests passing; HTTP coverage still partial; config wiring selectable  
**Related:** [CODE-QUALITY-REVIEW-2026-01-20.md](CODE-QUALITY-REVIEW-2026-01-20.md), [IRMIN-INTEGRATION.md](IRMIN-INTEGRATION.md), [ADR-004a-proposal.md](ADR-004a-proposal.md)

---

## Table of Contents

1. [Overview: Role of RiskTreeRepositoryIrmin](#1-overview-role-of-risktreerepositoryirmin)
2. [Goals](#2-goals)
3. [Implementation Plan](#3-implementation-plan)

---

## 1. Overview: Role of RiskTreeRepositoryIrmin

### The Repository Pattern

`RiskTreeRepository` is a **persistence abstraction** - it defines CRUD operations for `RiskTree` without specifying *where* data is stored:

```scala
trait RiskTreeRepository {
  def create(riskTree: RiskTree): Task[RiskTree]
  def update(id: TreeId, op: RiskTree => RiskTree): Task[RiskTree]
  def delete(id: TreeId): Task[RiskTree]
  def getById(id: TreeId): Task[Option[RiskTree]]
  def getAll: Task[List[Either[RepositoryFailure, RiskTree]]]
}
```

Currently there are two implementations:
- `RiskTreeRepositoryInMemory` (stores in a `TrieMap`, used by Application wiring today)
- `RiskTreeRepositoryIrmin` (per-node Irmin storage; see `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`)

---

### Service Call Chain: Create Risk Tree

**User action:** POST a new risk tree via HTTP (node IDs are ULIDs; tree IDs remain numeric for now)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. HTTP Request                                                             │
│    POST /risk-trees                                                         │
│    Body: { "name": "Cyber Risk", "nodes": [...], "rootId": "cyber-root" }   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. RiskTreeController.create                                                │
│    - Parses RiskTreeDefinitionRequest                                       │
│    - Calls riskTreeService.create(request)                                  │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. RiskTreeServiceLive.create                                               │
│    - Validates request (Iron types, business rules)                         │
│    - Builds RiskTree domain object                                          │
│    - Calls repository.create(riskTree) (config selects repo implementation) │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. RiskTreeRepository.create (abstraction)                                  │
│                                                                             │
│    DEFAULT: RiskTreeRepositoryInMemory (unless `repository.type=irmin`)     │
│    - Stores in TrieMap                                                      │
│    - Data lost on restart ❌                                                │
│    - No audit trail ❌                                                      │
│    - No versioning ❌                                                       │
│                                                                             │
│    AVAILABLE: RiskTreeRepositoryIrmin                                       │
│    - Stores in Irmin via GraphQL                                            │
│    - Data persisted ✅                                                      │
│    - Full commit history ✅                                                 │
│    - Watch notifications for cache invalidation ✅                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---


Step 0: Resolve CODE-QUALITY-REVIEW-2026-01-20.md issues
  │
### What RiskTreeRepositoryIrmin Does

For the `create` operation:

```
RiskTreeRepositoryIrmin.create(riskTree) 
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Per-Node Storage (Option A - chosen approach)                               │
│                                                                             │
│ For each node in riskTree.nodes:                                            │
│   irminClient.set(                                                          │
│     path = "risk-trees/1/nodes/01ARZ3NDEKTSV4RRFFQ69G5FAV",                                │
│     value = """{"id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","name":"Cyber Risk",                      │
│                 "parentId":null,"childIds":["01ARZ3NDEKTSV4RRFFQ69G5FAW","01ARZ3NDEKTSV4RRFFQ69G5FAX"]}""",      │
│     message = "Create node 01ARZ3NDEKTSV4RRFFQ69G5FAV"                                      │
│   )                                                                         │
│                                                                             │
│   irminClient.set(                                                          │
│     path = "risk-trees/1/nodes/01ARZ3NDEKTSV4RRFFQ69G5FAW",                                  │
│     value = """{"id":"01ARZ3NDEKTSV4RRFFQ69G5FAW","name":"Phishing Attack",                   │
│                 "parentId":"01ARZ3NDEKTSV4RRFFQ69G5FAV","distributionType":"lognormal"...}"""│
│     message = "Create node 01ARZ3NDEKTSV4RRFFQ69G5FAW"                                        │
│   )                                                                         │
│                                                                             │
│   ... repeat for each node ...                                              │
│                                                                             │
│ Then store metadata:                                                        │
│   irminClient.set(                                                          │
│     path = "risk-trees/1/meta",                                            │
│     value = """{"name":"Cyber Risk","rootId":"01ARZ3NDEKTSV4RRFFQ69G5FAV"}""",              │
│     message = "Create tree 1 metadata"                                      │
│   )                                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Irmin Storage (Content-Addressed)                                           │
│                                                                             │
│ risk-trees/                                                                 │
│   1/                                                                        │
│     meta           → {"name":"Cyber Risk","rootId":"cyber-root"}            │
│     nodes/                                                                  │
│       cyber-root   → RiskPortfolio JSON                                     │
│       phishing     → RiskLeaf JSON                                          │
│       malware      → RiskLeaf JSON                                          │
│                                                                             │
│ Each write creates an immutable commit with hash, author, timestamp         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Why Per-Node Storage Matters

When a user later **updates a single node** (e.g., changes phishing probability):

```
With Per-Node Storage (Option A):
─────────────────────────────────
1. User updates phishing node
2. Repository writes ONLY risk-trees/1/nodes/phishing
3. Irmin watch fires: "path risk-trees/1/nodes/phishing changed"
4. Cache invalidation: invalidate phishing + ancestors (cyber-root)
5. Only affected LECs recomputed

With Per-Tree Storage (Option B):
─────────────────────────────────
1. User updates phishing node  
2. Repository writes entire tree at risk-trees/1/definition
3. Irmin watch fires: "path risk-trees/1/definition changed"
4. Cache invalidation: ??? which node changed? Must diff entire tree
5. Coarse invalidation = more recomputation
```

---

### Architecture: Current vs Desired

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Current Application Wiring                        │
└──────────────────────────────────────────────────────────────────────────┘

  HTTP Layer          Service Layer           Repository Layer
  ──────────          ─────────────           ────────────────
      │                    │                        │
      ▼                    ▼                        ▼
┌──────────┐       ┌────────────────┐       ┌─────────────────────────┐
│Controller│──────▶│RiskTreeService │──────▶│RiskTreeRepositoryInMemory│ ← Data here
└──────────┘       └────────────────┘       └─────────────────────────┘   (volatile!)
                                                     │
                                                     ▼
                                               [TrieMap]
                                               (lost on restart)



┌──────────────────────────────────────────────────────────────────────────┐
│                        Desired Application Wiring                        │
└──────────────────────────────────────────────────────────────────────────┘

  HTTP Layer          Service Layer           Repository Layer
  ──────────          ─────────────           ────────────────
      │                    │                        │
      ▼                    ▼                        ▼
┌──────────┐       ┌────────────────┐       ┌────────────────────────┐
│Controller│──────▶│RiskTreeService │──────▶│RiskTreeRepositoryIrmin │ ← NEW
└──────────┘       └────────────────┘       └────────────────────────┘
                                                     │
                                                     ▼ IrminClient.set/get
                                            ┌─────────────────┐
                                            │  Irmin Server   │
                                            │  (Docker 9080)  │
                                            └─────────────────┘
                                                     │
                                                     ▼
                                            [Content-Addressed Store]
                                            (persistent, versioned,
                                             audit trail, watch events)
```

                                        **Config note:** Runtime wiring is selected via `register.repository.repositoryType`; default is `in-memory`, and `irmin` enables the health-checked Irmin path used by the integration harness.

---

### Summary Table

| Question | Answer |
|----------|--------|
| **What is it?** | An implementation of `RiskTreeRepository` that uses `IrminClient` to persist trees |
| **What is implemented?** | `RiskTreeRepositoryIrmin` (per-node, ULID node IDs) with metadata schema, `IrminClient.list`/health check support, selectable repo wiring, and container-backed repo integration spec |
| **What remains?** | Default config still uses in-memory; HTTP integration spec only covers health + create/list/get; LEC/probability and cache HTTP specs pending; optional Testcontainers isolation and SSE coverage not yet done; tree IDs still numeric |
| **What does it enable?** | Persistent storage, version history, and fine-grained change notifications |
| **What happens without wiring/tests?** | Runtime stays in-memory unless `repository.type=irmin`; HTTP path lacks coverage for update/delete/LEC/cache |

---

## 2. Goals

### 2.1 Repository Implementation

| ID | Goal | Description |
|----|------|-------------|
| G1 | Create `RiskTreeRepositoryIrmin` | Implement `RiskTreeRepository` trait using `IrminClient` |
| G2 | Per-node storage model | Store each node at `risk-trees/{treeId}/nodes/{nodeId}` |
| G3 | Tree metadata storage | Store tree metadata at `risk-trees/{treeId}/meta` |
| G4 | Validation on load | Use `TreeIndex.fromNodes()` with `Validation` when reconstructing trees |
| G5 | Proper error mapping | Map Irmin errors to domain error types |

### 2.2 Repository-Focused Test Cases

Repository tests require a running Irmin container. Following the established pattern, these tests belong in the **`server-it`** module (integration tests).

**Module Pattern (from `build.sbt`):**
```
server/        → Unit tests (no external dependencies)
server-it/     → Integration tests (require Docker containers)
```

| ID | Goal | Description |
|----|------|-------------|
| G6 | Create `RiskTreeRepositoryIrminSpec` | Comprehensive repository tests in `server-it` module |
| G7 | CRUD roundtrip tests | Create → Read → Update → Delete cycle verification |
| G8 | Multi-node tree tests | Test trees with multiple nodes, verify reconstruction |
| G9 | Validation error tests | Test that invalid trees are rejected on load |
| G10 | Concurrent access tests | Verify behavior under concurrent operations |
| G11 | Irmin-specific tests | Content-addressing behavior, commit history verification |

**Test File Location:**
```
modules/server-it/src/test/scala/com/risquanter/register/
  repositories/
    RiskTreeRepositoryIrminSpec.scala
```

**Running Repository Tests:**
```bash
# Requires Irmin container running
docker compose --profile persistence up -d
sbt "serverIt/testOnly *RiskTreeRepositoryIrminSpec"
```

### 2.3 HTTP API Integration Tests

Full end-to-end tests that verify the complete stack with real HTTP requests. **Status:** Partial via `HttpApiIntegrationSpec` (health + create/list/get); LEC and cache suites not started.

| ID | Goal | Description |
|----|------|-------------|
| G12 | Test server infrastructure | Reusable layer that starts real HTTP server on random port |
| G13 | HTTP client setup | sttp client configured for integration tests |
| G14 | Create `RiskTreeApiIntegrationSpec` | CRUD endpoint tests via HTTP |
| G15 | Create `LECApiIntegrationSpec` | LEC query endpoint tests |
| G16 | Create `CacheApiIntegrationSpec` | Cache management and invalidation tests |

**Test Cases for RiskTreeApiIntegrationSpec:**
- `POST /risk-trees` - Create risk tree
- `GET /risk-trees` - Get all risk trees  
- `GET /risk-trees/{id}` - Get by ID
- `PUT /risk-trees/{id}` - Update risk tree
- `DELETE /risk-trees/{id}` - Delete risk tree
- Error responses (404, 400 validation errors)

**Test Cases for LECApiIntegrationSpec:**
- `GET /risk-trees/{treeId}/nodes/{nodeId}/lec` - Get LEC curve
- `POST /risk-trees/{treeId}/nodes/lec-multi` - Get multiple LEC curves
- `GET /risk-trees/{treeId}/nodes/{nodeId}/prob-of-exceedance` - Exceedance probability
- LEC response structure validation
- Provenance inclusion/exclusion

**Test Cases for CacheApiIntegrationSpec:**
- `GET /risk-trees/{treeId}/cache/stats` - Cache stats after LEC query
- `GET /risk-trees/{treeId}/cache/nodes` - Cached node IDs
- `POST /risk-trees/{id}/invalidate/{nodeId}` - Cache invalidation
- `DELETE /risk-trees/{treeId}/cache` - Clear tree cache
- Cache population verification (LEC query populates cache)
- Cache invalidation propagation (ancestor invalidation)

**Test File Locations:**
```
modules/server-it/src/test/scala/com/risquanter/register/http/
  HttpApiIntegrationSpec.scala      # health + create/list/get (Irmin-backed harness)
  LECApiIntegrationSpec.scala       # TODO
  CacheApiIntegrationSpec.scala     # TODO
modules/server-it/src/test/scala/com/risquanter/register/http/support/
  HttpTestHarness.scala             # Stub backend wiring
```

### 2.4 Docker-Compose Integration

| ID | Goal | Description |
|----|------|-------------|
| G17 | Use existing Irmin container | Tests use `docker compose --profile persistence` |
| G18 | Test isolation | Each test uses unique paths/IDs to avoid collisions |
| G19 | Documentation | Clear instructions for running integration tests |

---

## 3. Implementation Plan

### Outstanding Tasks (consolidated)
- Expand HTTP integration to cover LEC/probability endpoints (build out `RiskTreeApiIntegrationSpec` or extend `HttpApiIntegrationSpec`).
- Add cache-focused HTTP integration (`CacheApiIntegrationSpec`): stats/list/invalidate/clear scenarios.
- Plan ULID adoption for tree/node IDs (see `docs/IRMIN-ULID-ID-PLAN.md`).
- (Optional) Add Testcontainers-based Irmin isolation instead of shared docker-compose volumes.
- (Optional) Add SSE integration coverage after cache/LEC specs.

### Step 0: Resolve Technical Debt

**Prerequisite:** Before implementing new functionality, resolve the issues documented in [CODE-QUALITY-REVIEW-2026-01-20.md](CODE-QUALITY-REVIEW-2026-01-20.md).

| Issue | Priority | Effort |
|-------|----------|--------|
| Imperative error collection in `TreeIndex.fromNodes` | Medium | 1 hour |
| Inconsistent validation return types (Either vs Validation) | Low | 30 min |
| Verbose `ZIO.fromEither` conversion pattern | Low | 20 min |
| `if/else` instead of `fromPredicateWith` in `RiskTree.fromNodes` | Low | 10 min |

**Rationale:** These issues affect the code paths that `RiskTreeRepositoryIrmin` will use for tree reconstruction. Fixing them first ensures clean integration.

---

### Step 1: IrminClient Enhancements

**Goal:** Add missing operations needed by the repository.

**File:** `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala`

**New Operations:**
```scala
trait IrminClient:
  // ... existing methods ...
  
  /** List all paths under a prefix (for loading all nodes of a tree) */
  def list(prefix: IrminPath): IO[IrminError, List[IrminPath]]
```

**Deliverables:**
- [x] Add `list` method to `IrminClient` trait (`IO[IrminError, List[IrminPath]]`)
- [x] Implement in `IrminClientLive` (uses GraphQL `listTree` query)
- [x] Add GraphQL query in `IrminQueries`
- [x] Add integration coverage in `IrminClientIntegrationSpec` for list (container-backed)

---

### Step 2: Tree Metadata Model

**Goal:** Represent metadata stored at `risk-trees/{treeId}/meta`.

**Current state:** Metadata is modeled as `TreeMetadata` ([modules/server/src/main/scala/com/risquanter/register/repositories/model/TreeMetadata.scala](../modules/server/src/main/scala/com/risquanter/register/repositories/model/TreeMetadata.scala)) with schema version + timestamps; legacy inline `Meta` decoder remains in `RiskTreeRepositoryIrmin` for backward compatibility.

**Deliverables:**
- [x] Create `TreeMetadata` case class with JSON codec
- [ ] Add `IrminPath` utilities for tree paths

---

### Step 3: RiskTreeRepositoryIrmin Implementation

**Goal:** Implement the Irmin-backed repository.

**File:** `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`

**Operations:**

| Method | Implementation |
|--------|----------------|
| `create` | Write each node to `risk-trees/{id}/nodes/{nodeId}`, write metadata to `risk-trees/{id}/meta` |
| `getById` | Read metadata, list nodes under `risk-trees/{id}/nodes/*`, deserialize, reconstruct with `TreeIndex.fromNodes` |
| `update` | Read tree, apply operation, diff nodes, write changed nodes only |
| `delete` | Remove all paths under `risk-trees/{id}/` |
| `getAll` | List `risk-trees/*/meta`, load each tree |

**Deliverables:**
- [x] Implement `RiskTreeRepositoryIrmin` class (per-node paths under `risk-trees/*`)
- [x] Create `ZLayer` for dependency injection
- [ ] Handle ID generation (use metadata to track next ID) — currently expects provided IDs
- [x] Proper error mapping (Irmin errors → domain errors)

---

### Step 4: Repository Integration Tests

**Goal:** Test the repository against real Irmin.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`

**Test Cases:**
```scala
suite("RiskTreeRepositoryIrminSpec")(
  test("create and get roundtrip with metadata"),
  test("update prunes removed nodes"),
  test("list returns created trees"),
  test("delete removes tree")
)
```

Pending coverage: validation failures, concurrent create, and commit history assertions.

**Deliverables:**
- [x] Create `RiskTreeRepositoryIrminSpec` (container-backed) in `server-it`
- [x] Tests pass with Irmin container running (see `docker compose --profile persistence` prereq)
- [ ] Update `server-it/README.md` with new test instructions

---

### Step 5: Integration Test Infrastructure

**Goal:** Create shared test support for HTTP integration tests.

**Status:** Implemented as `HttpTestHarness` (random-port ZIO HTTP server) and `http/support/HttpTestHarness` (Tapir stub backend). Server wiring supports Irmin or in-memory repositories; Irmin path includes health check retries and readiness probe.

**Deliverables:**
- [x] Test server binds to random port (`HttpTestHarness.irminServer`/`inMemoryServer`)
- [x] Server uses `RiskTreeRepositoryIrmin` when repository type is Irmin
- [x] HTTP client fixture available via `SttpClientFixture`
- [ ] Consolidate into shared `IntegrationTestSupport` if desired (current naming differs)

---

### Step 6: RiskTreeApiIntegrationSpec

**Goal:** Test CRUD endpoints via real HTTP.

**Status:** Partial via `HttpApiIntegrationSpec` (health + create/list/get); expand to full CRUD and rename/replace with `RiskTreeApiIntegrationSpec`.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala`

**Test Cases:**
- POST /risk-trees returns 201 with created tree
- POST /risk-trees with invalid data returns 400
- GET /risk-trees returns all trees
- GET /risk-trees/{id} returns tree by ID
- GET /risk-trees/{id} returns 404 for non-existent
- PUT /risk-trees/{id} updates tree
- DELETE /risk-trees/{id} removes tree

  **Deliverables:**
  - [x] Base HTTP integration spec (`HttpApiIntegrationSpec`) with health + create/list/get
  - [ ] Expand to full CRUD coverage (update/DELETE + error cases) plus LEC/probability endpoints
  - [ ] Error responses verified

---

### Step 7: LECApiIntegrationSpec

**Goal:** Test LEC query endpoints.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/http/LECApiIntegrationSpec.scala`

**Test Cases:**
- GET /risk-trees/{treeId}/nodes/{nodeId}/lec returns LEC curve
- GET with includeProvenance=true includes provenance
- POST /risk-trees/{treeId}/nodes/lec-multi returns multiple LECs
- GET prob-of-exceedance returns correct probability
- LEC for non-existent node returns 404

**Deliverables:**
- [ ] Create `LECApiIntegrationSpec`
- [ ] LEC response structure validated
- [ ] Provenance correctness verified

---

### Step 8: CacheApiIntegrationSpec

**Goal:** Test cache management and invalidation.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/http/CacheApiIntegrationSpec.scala`

**Test Cases:**
- Cache is empty before LEC query
- LEC query populates cache
- Cache stats reflect cached nodes
- Invalidate node clears that node
- Invalidate propagates to ancestors
- Clear cache removes all entries
- Cache survives across requests (within session)

**Deliverables:**
- [ ] Create `CacheApiIntegrationSpec`
- [ ] Cache behavior fully tested (stats/list/invalidate/clear)
- [ ] Invalidation propagation verified

---

### Step 9: Documentation and Wiring

**Goal:** Complete documentation and optional production wiring.

**Deliverables:**
- [x] Update `IRMIN-INTEGRATION.md` with repository usage
- [x] Update `server-it/README.md` with all test categories
- [x] (Optional) Add config flag to switch `Application.scala` to Irmin repository (via `register.repository.repositoryType`)
- [x] Update `IRMIN-INTEGRATION-STATUS-2026-01-20.md` to reflect completion

---

## Summary: Implementation Sequence

```
Step 0: Resolve CODE-QUALITY-REVIEW-2026-01-20.md issues
  │
  ▼
Step 1: IrminClient enhancements (list operation) — DONE
  │
  ▼
Step 2: TreeMetadata model (inlined Meta; external model optional)
  │
  ▼
Step 3: RiskTreeRepositoryIrmin implementation — DONE
  │
  ▼
Step 4: Repository integration tests (RiskTreeRepositoryIrminSpec)
  │
  ▼
Step 5: Integration test infrastructure (HttpTestHarness) — DONE
  │
  ▼
Step 6: HTTP integration spec (expand to full CRUD)
  │
  ▼
Step 7: LECApiIntegrationSpec (LEC endpoints)
  │
  ▼
Step 8: CacheApiIntegrationSpec (cache management)
  │
  ▼
Step 9: Documentation and optional production wiring (server-it README pending)
```

**Estimated Total Effort:** 12-16 hours

---

*Document created: 2026-01-20*
