# ADR-017: Risk Tree API Design

**Status:** Accepted
**Date:** 2026-01-27
**Tags:** api-design, dto, ulid, risk-trees, persistence-history

---

## Context

- A risk tree is a whole-graph artifact: validity (single root, no cycles, unique
  names, every parent resolves, no empty portfolio) is a property of the **entire**
  node set, not of any node in isolation.
- A single DTO shape serving both create and update forces nullable identity fields
  whose intent is ambiguous at the call site — absent on create (server-assigned),
  required on update (client-referenced).
- Node identity must be **stable across edits**: a content-addressed, per-node store
  (ADR-004a) keys history by node path, so a node that keeps its identifier keeps its
  commit lineage. Re-minting identifiers severs that lineage and forecloses time travel.
- "Leaf cannot be a parent" is best enforced by request **shape**, not runtime checks.
- Cache invalidation cost is proportional to how precisely an edit identifies the nodes
  that actually changed.

---

## Decision

### 1. Separate Create and Update DTOs

Create DTOs carry **no** `id` (server mints ULIDs). Update DTOs **require** `id` so the
server can target an existing node by stable identity.

```scala
final case class RiskLeafDefinitionRequest(name: String, parentName: Option[String], ...)        // create: no id
final case class RiskLeafUpdateRequest(id: String, name: String, parentName: Option[String], ...) // update: id required
```

### 2. Separate Collections for Portfolios and Leaves

Structure enforces the type constraint; leaves reference portfolios by name only.

```scala
final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest]
)
```

### 3. Whole-Tree Submission Is the Correctness-by-Construction Mechanism

Update sends the **complete** node set in one request. Whole-graph invariants are
validated together, then the tree is rebuilt and re-validated; an accepted update is
always a fully valid tree. This is the design intent, not a limitation.

```scala
def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree]
// validateTopologyUpdate: unique names ∧ single root ∧ parents resolve ∧ no cycles ∧ no empty portfolio
```

### 4. Four Buckets: Identity-Preserving vs. New

`RiskTreeUpdateRequest` partitions nodes by whether they already have server identity.

```scala
final case class RiskTreeUpdateRequest(
  name: String,
  portfolios: Seq[RiskPortfolioUpdateRequest],     // existing: matched by ULID → same NodeId, same store path
  leaves: Seq[RiskLeafUpdateRequest],              // existing: matched by ULID → fields updated in place
  newPortfolios: Seq[RiskPortfolioDefinitionRequest], // added: server mints ULID
  newLeaves: Seq[RiskLeafDefinitionRequest]           // added: server mints ULID
)
```

| Bucket | id | Effect on node | Effect on store |
|--------|----|----|----|
| `portfolios` / `leaves` | required | update fields, keep ULID | rewrite **same** path → history continues |
| `newPortfolios` / `newLeaves` | none | create, mint ULID | new path → new lineage |
| in tree, omitted from request | — | **deleted** (reject if it would empty a portfolio) | path removed → lineage ends |

### 5. Identity Preservation Enables History and Time Travel

Because an existing node keeps its ULID, it keeps its node path in the content-addressed
store, so each edit is a **new commit on the same path** rather than a delete-and-recreate.
Per-node history is therefore queryable, and cache invalidation can target the single
changed node instead of the whole tree.

---

## Code Smells

### ❌ Mixed ID Semantics

```scala
// BAD: id optional — present on update? always? never?
final case class RiskNodeRequest(id: Option[String], parentId: Option[String], childIds: Seq[String])

// GOOD: separate DTOs make intent unambiguous
final case class RiskLeafDefinitionRequest(name: String, ...)            // no id → create
final case class RiskLeafUpdateRequest(id: String, name: String, ...)    // id → update existing
```

### ❌ Client Maintains Topology via childIds

```scala
// BAD: client keeps parent↔child consistency
final case class RiskNodeRequest(childIds: Seq[String], parentId: String)

// GOOD: parent-only; children derived server-side
final case class RiskPortfolioDefinitionRequest(name: String, parentName: Option[String])
```

### ❌ Discarding Node Identity on the Edit Round-Trip

```scala
// BAD: drop NodeId on load, resubmit every node as "new"
//   → server mints fresh ULIDs → every node path is removed and rewritten
//   → per-node commit lineage is severed; cache flushes the whole tree
loadFromTree(t)                       // NodeId thrown away
toUpdateRequest()                     // all nodes → newLeaves / newPortfolios

// GOOD: retain NodeId, route loaded nodes to the identity-preserving buckets
loadFromTree(t)                       // LeafDraft.id = Some(node.id)
toUpdateRequest()                     // id.isDefined → leaves/portfolios; else → new*
```

### ❌ Treating Per-Node PATCH as the Real Write Path

```scala
// BAD: assume a single-node PATCH avoids whole-graph validation
PATCH /risk-trees/{id}/nodes/{ulid}/distribution   // still must load the tree,
                                                   // apply, and re-validate the whole graph

// GOOD: whole-tree PUT — the validation it performs cannot be skipped anyway
PUT /w/{key}/risk-trees/{treeId}   { ...complete node set... }
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `RiskTreeDefinitionRequest` | Create DTO — no ids, name-based topology |
| `RiskTreeUpdateRequest` | Update DTO — existing (ULID) + new buckets |
| `RiskTreeRequests.resolveUpdate` | Existing ids preserved verbatim; new nodes get `newId()` |
| `RiskTreeServiceLive.buildNodes` | Builds `RiskLeaf`/`RiskPortfolio` keyed by resolved `NodeId` |
| `RiskTreeRepositoryIrmin.update` | Same path rewritten per node; `obsoleteNodeIds` deletes only omitted nodes |
| `TreeBuilderState` (app) | `LeafDraft`/`PortfolioDraft` carry `id: Option[NodeId]`; partition on it |

### HTTP surface (workspace-capability scoped)

| Method & path | Request | Purpose |
|---------------|---------|---------|
| `GET /w/{key}/risk-trees` | — | List trees |
| `POST /w/{key}/risk-trees` | `RiskTreeDefinitionRequest` | Create |
| `GET /w/{key}/risk-trees/{treeId}` | — | Summary |
| `GET /w/{key}/risk-trees/{treeId}/structure` | — | Full structure |
| `PUT /w/{key}/risk-trees/{treeId}` | `RiskTreeUpdateRequest` | Full-replacement update |
| `DELETE /w/{key}/risk-trees/{treeId}` | — | Delete tree |
| `POST /w/{key}/risk-trees/{treeId}/invalidate/{nodeId}` | — | Cache invalidation (not a write) |

---

## References

- ADR-004a — Per-node content-addressed tree storage (history substrate)
- ADR-018 — Nominal `NodeId` / `TreeId` wrappers over `SafeId`
- ADR-021 — Capability-URL workspace scoping (`/w/{key}/...`)
- ADR-001 — Smart constructors and boundary validation
