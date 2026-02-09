# ADR-017: Risk Tree API Design

**Status:** Accepted  
**Date:** 2026-01-27  
**Context:** Redesign of risk tree creation and update APIs with name-based topology, ULID identification, and separated DTOs.

---

# Part A: Architecture Decision Record

## 1. Context

- Risk trees grow large; **full resubmission** becomes impractical and error-prone
- Current DTOs mix client and server concerns (IDs present/absent ambiguity)
- **Create** semantics differ from **update** semantics (server-generated vs client-referenced IDs)
- Structural constraints (single root, no cycles, leaves can't be parents) need compile-time or validation-time enforcement
- Future requirements: undo/redo, collaborative editing, WebSocket sync

## 2. Decision

### 2.1 Separate Create and Update DTOs

**Create DTOs** have no `id` field (server generates ULIDs):
```scala
case class RiskLeafDefinitionRequest(name: String, parentName: Option[String], ...)
```

**Update DTOs** require `id` field (client references existing nodes):
```scala
case class RiskLeafUpdateRequest(id: String, name: String, parentName: Option[String], ...)
```

### 2.2 Separate Collections for Portfolios and Leaves (Option B)

Structure enforces "leaf cannot be parent" — leaves only reference portfolios by name:
```scala
case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest]
)
```

### 2.3 Name-Based Topology on Create, ULID on Update

- **Create:** `parentName: Option[String]` references by display name within request
- **Update:** `id: String` (ULID) for stable identity; `parentName` for topology changes

### 2.4 Batch Operations for Targeted Updates

Instead of full tree resubmission, use `TreeOp` algebra:
```scala
sealed trait TreeOp
case class UpdateDistribution(nodeId: String, distribution: DistributionParams) extends TreeOp
case class ReparentNode(nodeId: String, newParentName: String) extends TreeOp
// ... etc
```

Single-element batch = targeted update without full PUT.

### 2.5 DELETE Requires Explicit Action (Option C)

- Deleting last child of portfolio → **error** (not cascading ancestor deletion)
- Forces explicit restructure for edge cases

## 3. Code Smells

### ❌ Mixed ID Semantics

```scala
// BAD: ID optional, ambiguous intent
case class RiskNodeRequest(
  id: Option[String],      // Present on update? Always? Never?
  parentId: Option[String],
  childIds: Seq[String]    // Client maintaining topology
)

// GOOD: Separate DTOs with clear intent
case class RiskLeafDefinitionRequest(...)   // No id - create
case class RiskLeafUpdateRequest(id: String, ...) // id required - update
```

### ❌ ChildIds in Client Request

```scala
// BAD: Client maintains parent↔child consistency
case class RiskNodeRequest(childIds: Seq[String], parentId: String)

// GOOD: Parent-only topology, children derived
case class RiskPortfolioDefinitionRequest(name: String, parentName: Option[String])
```

### ❌ Full Tree Resubmission for Single Change

```scala
// BAD: Update one distribution → resubmit entire tree
PUT /trees/42 { ...entire tree with one field changed... }

// GOOD: Targeted operation
PATCH /trees/42/batch { operations: [{ op: "updateDistribution", nodeId: "01HX...", ... }] }
```

## 4. Implementation

| Location | Pattern |
|----------|---------|
| `RiskTreeDefinitionRequest` | Create DTO - no IDs |
| `RiskTreeUpdateRequest` | Update DTO - required IDs |
| `RiskTreeRoutes` | Wire endpoints per Part B spec |
| `RiskTreeServiceLive` | Validation + ULID generation |
| `TreeOp` (Phase 2) | Batch operation algebra |

---

# Part B: API Specification Appendix

## 5. Domain Model Recap

### Tree Structure
- A **RiskTree** is a hierarchical structure of nodes
- **Portfolio** nodes aggregate children (no distribution)
- **Leaf** nodes carry loss distributions (lognormal or expert opinion)
- Each node has a server-generated **ULID** as stable identity
- Node **names** are display labels, unique within a tree

### Invariants
1. Exactly one root (no parent)
2. Every non-root node has exactly one parent
3. Parents must be portfolios (leaves cannot have children)
4. No cycles
5. Names unique within tree
6. Leaves must have valid distribution parameters

---

## 6. DTO Definitions

### 6.1 Create DTOs (No IDs)

```scala
/** Request to create a new risk tree */
final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest]
)

/** Portfolio node definition (no id - server generates) */
final case class RiskPortfolioDefinitionRequest(
  name: String,
  parentName: Option[String]  // None = root; Some = must reference another portfolio
)

/** Leaf node definition (no id - server generates) */
final case class RiskLeafDefinitionRequest(
  name: String,
  parentName: Option[String],  // must reference a portfolio
  distributionType: String,    // "lognormal" | "expert"
  probability: Double,         // (0, 1) exclusive
  // Lognormal mode
  minLoss: Option[Long],
  maxLoss: Option[Long],
  // Expert mode
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)
```

### 6.2 Update DTOs (ID Required)

```scala
/** Request to update an existing risk tree structure */
final case class RiskTreeUpdateRequest(
  name: String,
  // Existing nodes (matched by ULID, fields updated)
  portfolios: Seq[RiskPortfolioUpdateRequest],
  leaves: Seq[RiskLeafUpdateRequest],
  // New nodes (no ULID, server generates)
  newPortfolios: Seq[RiskPortfolioDefinitionRequest],
  newLeaves: Seq[RiskLeafDefinitionRequest]
)

/** Update an existing portfolio node */
final case class RiskPortfolioUpdateRequest(
  id: String,                   // ULID - required, must exist in tree
  name: String,                 // new name (can rename)
  parentName: Option[String]    // new parent (can reparent)
)

/** Update an existing leaf node */
final case class RiskLeafUpdateRequest(
  id: String,                   // ULID - required, must exist in tree
  name: String,
  parentName: Option[String],
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)
```

### 6.3 Patch DTOs

```scala
/** Update distribution parameters only */
final case class DistributionUpdateRequest(
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)

/** Rename a node */
final case class NodeRenameRequest(
  name: String
)
```

---

## 7. API Endpoints

### 7.1 Create Tree

```
POST /risk-trees
Content-Type: application/json

{
  "name": "Operational Risk Portfolio",
  "portfolios": [
    { "name": "Root" },
    { "name": "IT Risk", "parentName": "Root" },
    { "name": "Financial Risk", "parentName": "Root" }
  ],
  "leaves": [
    { "name": "Cyber Attack", "parentName": "IT Risk", 
      "distributionType": "lognormal", "probability": 0.25,
      "minLoss": 1000, "maxLoss": 50000 },
    { "name": "Data Breach", "parentName": "IT Risk",
      "distributionType": "lognormal", "probability": 0.15,
      "minLoss": 500, "maxLoss": 25000 },
    { "name": "Fraud", "parentName": "Financial Risk",
      "distributionType": "lognormal", "probability": 0.10,
      "minLoss": 2000, "maxLoss": 100000 }
  ]
}

→ 201 Created
{
  "id": 42,
  "name": "Operational Risk Portfolio",
  "nodes": [
    { "id": "01HX7M...", "type": "portfolio", "name": "Root", "parentId": null },
    { "id": "01HX7N...", "type": "portfolio", "name": "IT Risk", "parentId": "01HX7M..." },
    { "id": "01HX7P...", "type": "portfolio", "name": "Financial Risk", "parentId": "01HX7M..." },
    { "id": "01HX7Q...", "type": "leaf", "name": "Cyber Attack", "parentId": "01HX7N...", ... },
    { "id": "01HX7R...", "type": "leaf", "name": "Data Breach", "parentId": "01HX7N...", ... },
    { "id": "01HX7S...", "type": "leaf", "name": "Fraud", "parentId": "01HX7P...", ... }
  ]
}
```

**Validation Rules:**
1. `name` must be valid SafeName (non-blank, ≤50 chars)
2. `portfolios` + `leaves` can be empty (placeholder tree)
3. If non-empty:
   - All names unique across portfolios + leaves
   - Exactly one portfolio has `parentName = None` (root)
   - Every `parentName` references a portfolio (not a leaf)
   - All `parentName` references resolve within the request
4. Leaf distribution validation per `distributionType`
5. Server generates ULIDs for all nodes

---

### 7.2 Full Structural Update (PUT)

```
PUT /risk-trees/{treeId}
Content-Type: application/json

{
  "name": "Operational Risk Portfolio v2",
  "portfolios": [
    { "id": "01HX7M...", "name": "Root (renamed)" },
    { "id": "01HX7N...", "name": "IT Risk", "parentName": "Root (renamed)" }
  ],
  "leaves": [
    { "id": "01HX7Q...", "name": "Cyber Attack", "parentName": "IT Risk",
      "distributionType": "lognormal", "probability": 0.30,
      "minLoss": 1200, "maxLoss": 60000 }
  ],
  "newPortfolios": [
    { "name": "Compliance Risk", "parentName": "Root (renamed)" }
  ],
  "newLeaves": [
    { "name": "Regulatory Fine", "parentName": "Compliance Risk",
      "distributionType": "lognormal", "probability": 0.05,
      "minLoss": 10000, "maxLoss": 500000 }
  ]
}

→ 200 OK
```

**Semantics:**
| Source | Behavior |
|--------|----------|
| `portfolios` (with id) | Match by ULID, update name/parent |
| `leaves` (with id) | Match by ULID, update all fields |
| `newPortfolios` (no id) | Create new portfolio, generate ULID |
| `newLeaves` (no id) | Create new leaf, generate ULID |
| Node in tree but not in request | **Deleted** (reject if this would leave parent portfolio empty; delete/restructure parent instead) |

**Validation Rules:**
1. All `id` values must exist in current tree
2. All `id` values must match node type (portfolio id in portfolios list)
3. Same topology rules as create (unique names, single root, parents are portfolios)
4. `parentName` resolves within the combined set (existing + new)
5. No cycles (cannot reparent node under its own descendant)
6. Omitted nodes must not leave an empty portfolio; remove or replace the parent in the same request

**Node Matching:**
- Existing nodes matched by ULID (supports rename)
- Parent topology specified by `parentName` (name after any renames)

---

### 7.3 Update Distribution (PATCH)

```
PATCH /risk-trees/{treeId}/nodes/{nodeUlid}/distribution
Content-Type: application/json

{
  "distributionType": "lognormal",
  "probability": 0.35,
  "minLoss": 1500,
  "maxLoss": 75000
}

→ 200 OK
```

**Validation:**
1. `{nodeUlid}` must exist in tree
2. Node must be a leaf (portfolios have no distribution)
3. Distribution parameters validated per type

**Cache Invalidation:**
- Invalidate this node + all ancestors

---

### 7.4 Rename Node (PATCH)

```
PATCH /risk-trees/{treeId}/nodes/{nodeUlid}
Content-Type: application/json

{
  "name": "Advanced Cyber Threat"
}

→ 200 OK
```

**Validation:**
1. `{nodeUlid}` must exist in tree
2. `name` must be unique within tree
3. `name` must be valid SafeName

**Note:** ULID preserved; only display name changes.

---

### 7.5 Delete Node

```
DELETE /risk-trees/{treeId}/nodes/{nodeUlid}

→ 200 OK (returns updated tree)
```

**Behavior:**

| Scenario | Result |
|----------|--------|
| Leaf with siblings | Delete leaf only ✓ |
| Leaf as only child | **400 Bad Request**: "Cannot delete last child of portfolio; delete parent or use full restructure" |
| Portfolio (any) | Delete portfolio + all descendants recursively ✓ |
| Root node | **400 Bad Request**: "Cannot delete root node; delete tree instead" |

**Rationale for only-child restriction:**
- Prevents accidental creation of empty portfolios
- Forces explicit intent for structural edge cases
- Predictable: delete affects target + descendants only, never ancestors

**Cache Invalidation:**
- Invalidate deleted node + all ancestors

---

### 7.6 Delete Tree

```
DELETE /risk-trees/{treeId}

→ 204 No Content
```

---

### 7.7 Read Operations (Unchanged)

```
GET /risk-trees
→ 200 OK, list of trees

GET /risk-trees/{treeId}
→ 200 OK, single tree with all nodes

GET /risk-trees/{treeId}/nodes/{nodeUlid}/lec
→ 200 OK, LEC curve for node

GET /risk-trees/{treeId}/nodes/{nodeUlid}/lec?includeProvenance=true
→ 200 OK, LEC curve with provenance

GET /risk-trees/{treeId}/nodes/{nodeUlid}/prob-of-exceedance?threshold=10000
→ 200 OK, exceedance probability
```

---

## 8. Error Responses

All error responses follow the existing `ErrorResponse` format with domain `"risk-trees"`.

### New Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `ID_NOT_ALLOWED_ON_CREATE` | 400 | Client supplied ULID in create request |
| `MISSING_REFERENCE` | 400 | `parentName` references non-existent node |
| `AMBIGUOUS_REFERENCE` | 400 | Duplicate names detected |
| `INVALID_NODE_TYPE` | 400 | Operation not valid for node type (e.g., distribution on portfolio) |
| `CANNOT_DELETE_ROOT` | 400 | Attempted to delete root via node endpoint |
| `CANNOT_DELETE_ONLY_CHILD` | 400 | Delete would leave empty portfolio |
| `NODE_NOT_FOUND` | 404 | ULID does not exist in tree |
| `INVALID_REPARENT` | 400 | Reparent would create cycle |
| `LEAF_AS_PARENT` | 400 | `parentName` references a leaf node |

---

## 9. Batch Operations (Phase 2)

### 9.1 Motivation

For complex restructuring scenarios, a batch PATCH endpoint allows multiple atomic operations in one request.

**Original goal:** Avoid full tree resubmission for large trees. A **single-operation batch** fulfills this:
```json
{ "operations": [{ "op": "updateDistribution", "nodeId": "01HX...", ... }] }
```

**Additional benefits:**
- Undo/redo via operation replay
- Audit trail of changes
- Collaborative editing support
- WebSocket-ready command format

### 9.2 Batch vs Optics Clarification

| Concern | Batch Operations | Optics |
|---------|-----------------|--------|
| **Level** | HTTP API (external) | Implementation (internal) |
| **Serializable** | Yes (JSON commands) | No (code-level composition) |
| **Use case** | Client sends intent | Server applies changes efficiently |
| **Undo/redo** | Yes (invert operations) | Not directly |

**Relationship:** Client sends `TreeOp` commands via batch API → Server interpreter may use **optics or zippers internally** to apply operations efficiently. They complement, not compete.

**When to use each:**
- **Batch API:** Always for external clients (HTTP, WebSocket)
- **Optics:** Internal helper for composable deep updates (implementation detail)
- **Zipper:** Internal helper for focused navigation and local edits (implementation detail)

### 9.3 Operation Algebra

Each operation has preconditions that must hold against the *current* tree state.

```scala
sealed trait TreeOp

case class AddLeaf(
  name: String,
  parentName: String,
  distribution: DistributionParams
) extends TreeOp
// Pre: parentName exists ∧ parent is portfolio ∧ name unique
// Post: tree has new leaf under parent

case class AddPortfolio(
  name: String,
  parentName: String
) extends TreeOp
// Pre: parentName exists ∧ parent is portfolio ∧ name unique
// Post: tree has new portfolio under parent

case class DeleteNode(nodeId: String) extends TreeOp
// Pre: node exists ∧ node ≠ root ∧ (node has siblings ∨ node is portfolio)
// Post: node and descendants removed

case class ReparentNode(
  nodeId: String,
  newParentName: String
) extends TreeOp
// Pre: node exists ∧ newParent exists ∧ newParent is portfolio 
//      ∧ newParent ∉ descendants(node)
// Post: node moved under newParent

case class UpdateDistribution(
  nodeId: String,
  distribution: DistributionParams
) extends TreeOp
// Pre: node exists ∧ node is leaf
// Post: distribution updated

case class RenameNode(
  nodeId: String,
  newName: String
) extends TreeOp
// Pre: node exists ∧ newName unique
// Post: name updated
```

### 9.4 Batch Endpoint

```
PATCH /risk-trees/{treeId}/batch
Content-Type: application/json

{
  "operations": [
    { "op": "addPortfolio", "name": "New Category", "parentName": "Root" },
    { "op": "reparent", "nodeId": "01HX7Q...", "newParentName": "New Category" },
    { "op": "updateDistribution", "nodeId": "01HX7R...", 
      "distribution": { "distributionType": "lognormal", ... } },
    { "op": "delete", "nodeId": "01HX7S..." }
  ]
}

→ 200 OK
{
  "tree": { /* updated tree */ },
  "version": 12,            // monotonically increasing tree version
  "etag": "\"abc123\"",   // optional HTTP ETag for caching/concurrency
  "appliedOps": [           // echo of accepted operations, in order
    { "op": "addPortfolio", "name": "New Category", "parentName": "Root" },
    { "op": "reparent", "nodeId": "01HX7Q...", "newParentName": "New Category" },
    { "op": "updateDistribution", "nodeId": "01HX7R...", "distribution": { ... } },
    { "op": "delete", "nodeId": "01HX7S..." }
  ],
  "inverseOps": [           // optional; present if client requests undo payload
    { "op": "delete", "nodeId": "01HY..." },
    { "op": "reparent", "nodeId": "01HX7Q...", "newParentName": "Root" },
    { "op": "updateDistribution", "nodeId": "01HX7R...", "distribution": { /* previous */ } },
    { "op": "addLeaf", "name": "01HX7S...", "parentName": "..." }
  ]
}

**Response schema (concise):**
- `tree`: updated tree snapshot (same shape as GET by id)
- `version`: server-assigned version (optimistic concurrency, optional ETag mirror)
- `etag`: HTTP-safe ETag string (optional)
- `appliedOps`: the validated operations in execution order
- `inverseOps` (optional): inverses for undo/redo, emitted when client asks via query `?includeInverse=true`
```

### 9.5 Execution Semantics

1. Parse all operations
2. Validate each operation's precondition against current tree state
3. Apply operations **sequentially** (order matters)
4. After each operation, update working tree state for next validation
5. Validate final tree invariants
6. Commit atomically or rollback entirely on any failure

### 9.6 Complexity Bounds

| Operation | Time Complexity | Space Complexity | Notes |
|-----------|----------------|------------------|-------|
| `AddLeaf` | O(1) amortized | O(1) | Parent lookup via Map |
| `AddPortfolio` | O(1) amortized | O(1) | Parent lookup via Map |
| `DeleteNode` | O(k) | O(k) | k = descendants |
| `ReparentNode` | O(d) | O(d) | d = depth (cycle check) |
| `UpdateDistribution` | O(1) | O(1) | Direct node access |
| `RenameNode` | O(n) | O(1) | Uniqueness check |
| Batch of m ops | O(m × avg) | O(n) | n = tree size |

**Zipper advantage:** With zipper-based implementation, `ReparentNode` becomes O(depth) for navigation + O(1) for the move, without copying unrelated subtrees.

### 9.7 Commutativity Analysis

Some operations can be safely reordered; others cannot.

| Op A | Op B | Commutative? | Notes |
|------|------|--------------|-------|
| `UpdateDistribution(x)` | `UpdateDistribution(y)` | ✓ if x≠y | Independent nodes |
| `RenameNode(x)` | `RenameNode(y)` | ✓ if x≠y | Independent renames |
| `AddLeaf` | `AddLeaf` | ✓ | Order doesn't matter |
| `AddPortfolio` | `AddLeaf` to it | ✗ | Portfolio must exist first |
| `ReparentNode(x)` | `DeleteNode(x)` | ✗ | Delete invalidates reparent |
| `DeleteNode(x)` | `DeleteNode(child of x)` | ✗ | Child already deleted |

**Implication:** Server must respect client-specified order. Future optimization: detect and batch commutative operations.

### 9.8 Invertibility for Undo/Redo

Each operation has a computable inverse:

| Operation | Inverse | Stored Context |
|-----------|---------|----------------|
| `AddLeaf(name, parent, dist)` | `DeleteNode(generatedId)` | Generated ULID |
| `AddPortfolio(name, parent)` | `DeleteNode(generatedId)` | Generated ULID |
| `DeleteNode(id)` | `AddSubtree(subtree)` | Full subtree snapshot |
| `ReparentNode(id, newParent)` | `ReparentNode(id, oldParent)` | Original parent name |
| `UpdateDistribution(id, new)` | `UpdateDistribution(id, old)` | Previous distribution |
| `RenameNode(id, new)` | `RenameNode(id, old)` | Previous name |

**Implementation pattern:**
```scala
case class AppliedOp(op: TreeOp, inverse: TreeOp, timestamp: Instant)

def applyWithUndo(tree: Tree, op: TreeOp): (Tree, AppliedOp) = {
  val inverse = computeInverse(tree, op)
  val newTree = apply(tree, op)
  (newTree, AppliedOp(op, inverse, Instant.now))
}
```

### 9.9 Zipper-Based Interpreter

For efficient batch execution, use a tree zipper (see TREE-OPS.md Pattern 1):

```scala
def interpretOps(tree: Tree, ops: Seq[TreeOp]): Either[BatchError, Tree] = {
  ops.foldLeft(Right(Zipper.fromTree(tree)): Either[BatchError, Zipper]) {
    case (Right(z), op) => interpretSingle(z, op)
    case (Left(e), _)   => Left(e)
  }.map(_.toTree)
}

def interpretSingle(z: Zipper, op: TreeOp): Either[BatchError, Zipper] = op match {
  case AddLeaf(name, parent, dist) =>
    z.navigateTo(parent)
      .flatMap(_.insertChild(Leaf(name, dist)))
  case ReparentNode(id, newParent) =>
    for {
      focused  <- z.navigateTo(id)
      detached <- focused.detach  // removes from current parent
      target   <- detached.navigateTo(newParent)
      attached <- target.insertChild(focused.focus)
    } yield attached
  // ... other cases
}
```

**Benefits:**
- O(depth) navigation, O(1) local edits
- Structural sharing — unmodified subtrees not copied
- Natural fit for sequential operation application

### 9.10 WebSocket/SSE Compatibility

The batch operation model transfers excellently to real-time protocols:

#### REST (Current)
```
Client → Server: POST /trees/42/batch { operations: [...] }
Server → Client: 200 OK { tree: {...}, appliedOps: [...] }
```

#### WebSocket (Future)
```
Client → Server:
{ "type": "batch", "treeId": "42", "operations": [...] }

Server → All Clients:
{ "type": "tree-updated", "treeId": "42", 
  "appliedOps": [...], "tree": {...} }
```

#### SSE (Server-Push Only)
```
Client → Server: POST /trees/42/batch (HTTP)
Server → All Clients (SSE): 
data: { "type": "tree-updated", "treeId": "42", ... }
```

**Advantages for WebSocket migration:**
- Same `TreeOp` schema works across all protocols
- `appliedOps` enables collaborative undo/redo
- Incremental sync: send only operations, not full trees
- Conflict detection: server rejects ops with stale preconditions

### 9.11 Category Theory Foundation

The operations form a **free monad** over `TreeOp`:

```scala
type TreeProgram[A] = Free[TreeOp, A]
```

**Properties:**
- Operations are composable
- Interpretation separates description from execution
- Natural transformation to `Task` for effectful execution
- Rollback via algebraic effect or transaction boundary

---

## 10. Implementation Plan

### Phase 1: Core CRUD (This Sprint)

1. **DTOs**
  - [x] `RiskTreeDefinitionRequest` with portfolios/leaves
  - [x] `RiskPortfolioDefinitionRequest`, `RiskLeafDefinitionRequest`
  - [x] `RiskTreeUpdateRequest` with existing + new node lists
  - [x] `RiskPortfolioUpdateRequest`, `RiskLeafUpdateRequest`
  - [x] `DistributionUpdateRequest`, `NodeRenameRequest`
  - [x] JSON codecs for all DTOs

2. **Validation**
  - [x] Create validation: names unique, single root, parent refs valid, parents are portfolios
  - [x] Update validation: ULIDs exist, no cycles, same structural rules
  - [x] Distribution validation per type

3. **Service Layer**
  - [x] `create(req: RiskTreeDefinitionRequest): Task[RiskTree]`
  - [x] `update(id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree]`
  - [x] `updateDistribution(treeId, nodeId, req): Task[RiskTree]`
  - [x] `renameNode(treeId, nodeId, req): Task[RiskTree]`
  - [x] `deleteNode(treeId, nodeId): Task[RiskTree]`

4. **Endpoints**
  - [x] Wire new DTOs to existing endpoints
  - [x] Add PATCH endpoints for distribution/rename
  - [x] Add DELETE endpoint for nodes

5. **Tests**
  - [x] DTO serialization/deserialization
  - [x] Validation rules (happy path + error cases)
  - [x] Service integration tests
  - [x] Delete cascade behavior

### Phase 2: Batch Operations (Future)

1. Define `TreeOp` ADT (pending)
2. Implement batch endpoint (pending)
3. Operation interpreter with precondition checks (pending)
4. Transactional execution (pending)
5. Zipper-based tree manipulation (optional optimization) (pending)

---

## 11. Migration Notes

### Breaking Changes

The new DTO structure differs from the current `RiskTreeDefinitionRequest`:

| Current | New |
|---------|-----|
| `nodes: Seq[RiskNode]` | `portfolios` + `leaves` (separated) |
| `rootId: String` | Implicit (portfolio with no parent) |
| Nodes have `childIds` | No `childIds` (derived from parent refs) |

### Migration Path

1. Deploy new endpoints alongside existing (done)
2. Update clients to use new format (in progress)
3. Deprecate old endpoints (planned)
4. Remove old DTOs after migration period (planned)

---

## 12. Example Scenarios

### A. Add a new leaf to existing tree

```
PUT /risk-trees/42
{
  "name": "Ops Risk",
  "portfolios": [
    { "id": "01HX...", "name": "Root" },
    { "id": "01HY...", "name": "IT Risk", "parentName": "Root" }
  ],
  "leaves": [
    { "id": "01HZ...", "name": "Cyber Attack", "parentName": "IT Risk", ... }
  ],
  "newPortfolios": [],
  "newLeaves": [
    { "name": "Ransomware", "parentName": "IT Risk", 
      "distributionType": "lognormal", "probability": 0.20,
      "minLoss": 5000, "maxLoss": 200000 }
  ]
}
```

### B. Insert portfolio between existing nodes

Current: Root → Leaf A, Leaf B  
Desired: Root → New Portfolio → Leaf A, Leaf B

```
PUT /risk-trees/42
{
  "name": "Tree",
  "portfolios": [
    { "id": "01HX...", "name": "Root" }
  ],
  "leaves": [
    { "id": "01HY...", "name": "Leaf A", "parentName": "Intermediate" },
    { "id": "01HZ...", "name": "Leaf B", "parentName": "Intermediate" }
  ],
  "newPortfolios": [
    { "name": "Intermediate", "parentName": "Root" }
  ],
  "newLeaves": []
}
```

### C. Rename node while restructuring

```
PUT /risk-trees/42
{
  "name": "Updated Tree",
  "portfolios": [
    { "id": "01HX...", "name": "Root Portfolio (New Name)" }
  ],
  "leaves": [
    { "id": "01HY...", "name": "Risk Alpha", "parentName": "Root Portfolio (New Name)", ... }
  ],
  "newPortfolios": [],
  "newLeaves": []
}
```

Note: `parentName` uses the **new** name (after rename) for topology resolution.

### D. Quick distribution update

```
PATCH /risk-trees/42/nodes/01HY7Q.../distribution
{
  "distributionType": "expert",
  "probability": 0.25,
  "percentiles": [0.1, 0.5, 0.9],
  "quantiles": [1000, 5000, 25000]
}

### E. Delete only child (expected failure)

```
DELETE /risk-trees/42/nodes/01HY7Q...

→ 400 Bad Request { "code": "CANNOT_DELETE_ONLY_CHILD" }
```

Use full PUT restructure instead: delete the parent or add a sibling in the same request to avoid empty portfolios.
```

---

## 13. Decision Log

| Decision | Rationale |
|----------|-----------|
| Separate portfolios/leaves in DTO | Eliminates "leaf cannot be parent" validation; structure enforces constraint |
| No `childIds` in request | Derived from parent refs; prevents client/server inconsistency |
| `parentName` for topology | Human-readable; works for create; updates use ULID for identity |
| Separate create/update DTOs | Clear intent; update requires ULID, create forbids it |
| DELETE only-child restriction | Prevents surprising ancestor deletion; forces explicit restructure |
| PUT for structural changes | Atomic; server sees complete picture; simpler than incremental ops |
| Batch ops as Phase 2 | Provides fine-grained control later; not needed for MVP || Batch subsumes targeted updates | Single-element batch = targeted update without full PUT |
| Optics as implementation detail | Batch is API-level; optics/zippers are internal optimization |
| Invertible operations | Each TreeOp has computable inverse for undo/redo |
| Sequential execution | Client-specified order respected; commutativity optimization deferred |
| WebSocket-ready schema | Same TreeOp format works for REST, WebSocket, and SSE |

---

## 14. References

- [TREE-OPS.md](TREE-OPS.md) — FP patterns for tree manipulation (zippers, optics, recursion schemes)
- [ADR-00X.md](ADR-00X.md) — ADR template and sizing guidelines