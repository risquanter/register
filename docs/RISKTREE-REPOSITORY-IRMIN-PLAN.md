# RiskTreeRepositoryIrmin Implementation Plan

**Date:** 2026-01-20  
**Status:** Implemented (per-node); integration tests + wiring pending  
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
  def update(id: NonNegativeLong, op: RiskTree => RiskTree): Task[RiskTree]
  def delete(id: NonNegativeLong): Task[RiskTree]
  def getById(id: NonNegativeLong): Task[Option[RiskTree]]
  def getAll: Task[List[RiskTree]]
}
```

Currently there are two implementations:
- `RiskTreeRepositoryInMemory` (stores in a `TrieMap`, used by Application wiring today)
- `RiskTreeRepositoryIrmin` (per-node Irmin storage; see `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`)

---

### Service Call Chain: Create Risk Tree

**User action:** POST a new risk tree via HTTP

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
│    - Calls repository.create(riskTree)  ◀─────────── HERE IS THE GAP        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. RiskTreeRepository.create (abstraction)                                  │
│                                                                             │
│    CURRENT: RiskTreeRepositoryInMemory                                      │
│    - Stores in TrieMap                                                      │
│    - Data lost on restart ❌                                                │
│    - No audit trail ❌                                                      │
│    - No versioning ❌                                                       │
│                                                                             │
│    NEEDED: RiskTreeRepositoryIrmin                                          │
│    - Stores in Irmin via GraphQL                                            │
│    - Data persisted ✅                                                      │
│    - Full commit history ✅                                                 │
│    - Watch notifications for cache invalidation ✅                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

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
│     path = "trees/1/nodes/cyber-root",                                      │
│     value = """{"id":"cyber-root","name":"Cyber Risk",                      │
│                 "parentId":null,"childIds":["phishing","malware"]}""",      │
│     message = "Create node cyber-root"                                      │
│   )                                                                         │
│                                                                             │
│   irminClient.set(                                                          │
│     path = "trees/1/nodes/phishing",                                        │
│     value = """{"id":"phishing","name":"Phishing Attack",                   │
│                 "parentId":"cyber-root","distributionType":"lognormal"...}""│
│     message = "Create node phishing"                                        │
│   )                                                                         │
│                                                                             │
│   ... repeat for each node ...                                              │
│                                                                             │
│ Then store metadata:                                                        │
│   irminClient.set(                                                          │
│     path = "trees/1/meta",                                                  │
│     value = """{"name":"Cyber Risk","rootId":"cyber-root"}""",              │
│     message = "Create tree 1 metadata"                                      │
│   )                                                                         │
└─────────────────────────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Irmin Storage (Content-Addressed)                                           │
│                                                                             │
│ trees/                                                                      │
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
2. Repository writes ONLY trees/1/nodes/phishing
3. Irmin watch fires: "path trees/1/nodes/phishing changed"
4. Cache invalidation: invalidate phishing + ancestors (cyber-root)
5. Only affected LECs recomputed

With Per-Tree Storage (Option B):
─────────────────────────────────
1. User updates phishing node  
2. Repository writes entire tree at trees/1/definition
3. Irmin watch fires: "path trees/1/definition changed"
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

---

### Summary Table

| Question | Answer |
|----------|--------|
| **What is it?** | An implementation of `RiskTreeRepository` that uses `IrminClient` to persist trees |
| **What is implemented?** | `RiskTreeRepositoryIrmin` exists (per-node storage) and maps `IO[IrminError, *]` to repository `Task`s |
| **What remains?** | Application wiring still points at `RiskTreeRepositoryInMemory`; repository and HTTP integration tests are missing |
| **What does it enable?** | Persistent storage, version history, and fine-grained change notifications |
| **What happens without wiring/tests?** | Runtime still uses in-memory storage; Irmin path remains unexercised in integration suites |

---

## 2. Goals

### 2.1 Repository Implementation

| ID | Goal | Description |
|----|------|-------------|
| G1 | Create `RiskTreeRepositoryIrmin` | Implement `RiskTreeRepository` trait using `IrminClient` |
| G2 | Per-node storage model | Store each node at `trees/{treeId}/nodes/{nodeId}` |
| G3 | Tree metadata storage | Store tree metadata at `trees/{treeId}/meta` |
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

Full end-to-end tests that verify the complete stack with real HTTP requests.

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
modules/server-it/src/test/scala/com/risquanter/register/
  http/
    RiskTreeApiIntegrationSpec.scala
    LECApiIntegrationSpec.scala
    CacheApiIntegrationSpec.scala
  support/
    IntegrationTestSupport.scala    # Shared test infrastructure
```

### 2.4 Docker-Compose Integration

| ID | Goal | Description |
|----|------|-------------|
| G17 | Use existing Irmin container | Tests use `docker compose --profile persistence` |
| G18 | Test isolation | Each test uses unique paths/IDs to avoid collisions |
| G19 | Documentation | Clear instructions for running integration tests |

---

## 3. Implementation Plan

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

**Current state:** Metadata is modeled inline as a private `Meta` case class inside the implemented repository ([RiskTreeRepositoryIrmin.scala](../modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala)); it captures `name` and `rootId` and is encoded/decoded with `zio-json`. No standalone `TreeMetadata` file exists yet—introduce one only if other components need to share the type.

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
| `create` | Write each node to `trees/{id}/nodes/{nodeId}`, write metadata to `trees/{id}/meta` |
| `getById` | Read metadata, list nodes under `trees/{id}/nodes/*`, deserialize, reconstruct with `TreeIndex.fromNodes` |
| `update` | Read tree, apply operation, diff nodes, write changed nodes only |
| `delete` | Remove all paths under `trees/{id}/` |
| `getAll` | List `trees/*/meta`, load each tree |

**Deliverables:**
- [ ] Implement `RiskTreeRepositoryIrmin` class
- [ ] Create `ZLayer` for dependency injection
- [ ] Handle ID generation (use metadata to track next ID)
- [ ] Proper error mapping (Irmin errors → domain errors)

---

### Step 4: Repository Integration Tests

**Goal:** Test the repository against real Irmin.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`

**Test Cases:**
```scala
suite("RiskTreeRepositoryIrminSpec")(
  test("create stores tree and returns with assigned ID"),
  test("getById returns None for non-existent tree"),
  test("getById reconstructs tree with valid TreeIndex"),
  test("update modifies specific nodes"),
  test("delete removes all tree data"),
  test("getAll returns all stored trees"),
  test("create with invalid nodes fails validation"),
  test("concurrent creates get unique IDs"),
  test("update preserves nodes not modified"),
  test("Irmin commits are created for each operation")
)
```

**Deliverables:**
- [ ] Create `RiskTreeRepositoryIrminSpec`
- [ ] All tests pass with Irmin container running
- [ ] Update `server-it/README.md` with new test instructions

---

### Step 5: Integration Test Infrastructure

**Goal:** Create shared test support for HTTP integration tests.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/support/IntegrationTestSupport.scala`

**Contents:**
```scala
object IntegrationTestSupport {
  /** Layer that starts real HTTP server on random port */
  def testServerLayer: ZLayer[Any, Throwable, TestServer]
  
  /** Layer with Irmin-backed repository */
  def irminRepositoryLayer: ZLayer[IrminClient, Throwable, RiskTreeRepository]
  
  /** Combined layer for full integration tests */
  def fullTestLayer: ZLayer[Any, Throwable, TestServer & IrminClient]
  
  /** sttp client for making HTTP requests */
  def httpClient: SttpBackend[Task, Any]
  
  /** Generate unique test identifiers */
  def uniqueId: UIO[String]
}
```

**Deliverables:**
- [ ] Create `IntegrationTestSupport` object
- [ ] Test server binds to random port
- [ ] Server uses `RiskTreeRepositoryIrmin`
- [ ] HTTP client configured for tests

---

### Step 6: RiskTreeApiIntegrationSpec

**Goal:** Test CRUD endpoints via real HTTP.

**File:** `modules/server-it/src/test/scala/com/risquanter/register/http/RiskTreeApiIntegrationSpec.scala`

**Test Cases:**
- POST /risk-trees returns 201 with created tree
- POST /risk-trees with invalid data returns 400
- GET /risk-trees returns all trees
- GET /risk-trees/{id} returns tree by ID
- GET /risk-trees/{id} returns 404 for non-existent
- PUT /risk-trees/{id} updates tree
- DELETE /risk-trees/{id} removes tree

**Deliverables:**
- [ ] Create `RiskTreeApiIntegrationSpec`
- [ ] All CRUD endpoints tested
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
- [ ] Cache behavior fully tested
- [ ] Invalidation propagation verified

---

### Step 9: Documentation and Wiring

**Goal:** Complete documentation and optional production wiring.

**Deliverables:**
- [ ] Update `IRMIN-INTEGRATION.md` with repository usage
- [ ] Update `server-it/README.md` with all test categories
- [ ] (Optional) Add config flag to switch `Application.scala` to Irmin repository
- [ ] Update `IRMIN-INTEGRATION-STATUS-2026-01-20.md` to reflect completion

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
Step 5: Integration test infrastructure (IntegrationTestSupport)
  │
  ▼
Step 6: RiskTreeApiIntegrationSpec (CRUD)
  │
  ▼
Step 7: LECApiIntegrationSpec (LEC endpoints)
  │
  ▼
Step 8: CacheApiIntegrationSpec (cache management)
  │
  ▼
Step 9: Documentation and optional production wiring
```

**Estimated Total Effort:** 12-16 hours

---

*Document created: 2026-01-20*
