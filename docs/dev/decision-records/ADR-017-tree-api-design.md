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
  leaves: Seq[RiskLeafDefinitionRequest],
  newMitigations: Seq[MitigationDefinitionRequest]
)
```

### 3. Whole-Tree Submission Is the Correctness-by-Construction Mechanism

Update sends the **complete** node set in one request. Whole-graph invariants are
validated together, then the tree is rebuilt and re-validated; an accepted update is
always a fully valid tree. This is the design intent, not a limitation.

A request whose resulting tree equals the stored tree in content writes nothing and succeeds.

**Relation to ADR-001.** The request types carry raw fields, such as
`name: String`, which reads as a breach of ADR-001's rule that decoders call the
smart constructor. It is the exception ADR-001 §2 states. A tree create or
update is a command refined as a whole. Some of its rules read the stored tree,
and every refusal must be reported together at its entry, so `resolve` checks
every rule at the service's entry step. A collection the request gains, such as
its mitigations, is refined by the same `resolve`.

```scala
def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree]
// validateTopologyUpdate: unique names ∧ single root ∧ parents resolve ∧ no cycles ∧ no empty portfolio
```

### 4. Buckets: Identity-Preserving vs. New

`RiskTreeUpdateRequest` partitions nodes, and mitigations, by whether they already have server identity.

```scala
final case class RiskTreeUpdateRequest(
  name: String,
  portfolios: Seq[RiskPortfolioUpdateRequest],     // existing: matched by ULID → same NodeId, same store path
  leaves: Seq[RiskLeafUpdateRequest],              // existing: matched by ULID → fields updated in place
  newPortfolios: Seq[RiskPortfolioDefinitionRequest], // added: server mints ULID
  newLeaves: Seq[RiskLeafDefinitionRequest],          // added: server mints ULID
  mitigations: Seq[MitigationUpdateRequest],          // existing: matched by ULID → same MitigationId
  newMitigations: Seq[MitigationDefinitionRequest]    // added: server mints ULID
)
```

| Bucket | id | Effect on node or mitigation | Effect on store |
|--------|----|----|----|
| `portfolios` / `leaves` | required | update fields, keep ULID | rewrite **same** path → history continues |
| `newPortfolios` / `newLeaves` | none | create, mint ULID | new path → new lineage |
| `mitigations` | required | update fields, keep ULID | stored with the tree |
| `newMitigations` | none | create, mint ULID | stored with the tree |
| in tree, omitted from request | — | **deleted** (a node is refused if deleting it would empty a portfolio) | path removed → lineage ends |

Every bucket is a required key, so a request cannot omit one by accident and
delete what it held. A create request carries `newMitigations` only, because
§1 keeps id-bearing buckets out of create requests.

An absolute statement's anchor is the identifier of a leaf the stored tree
holds, so a new one can be written only by an update, and the server computes
its base stamp from that leaf. A request may delete the anchor leaf. The
mitigation is then accepted, applies to no node, and is not reported as stale.

### 5. Identity Preservation Enables History and Time Travel

Because an existing node keeps its ULID, it keeps its node path in the content-addressed
store, so each edit is a **new commit on the same path** rather than a delete-and-recreate.
Per-node history is therefore queryable, and cache invalidation can target the single
changed node instead of the whole tree.

### 6. Resource Limits on Tree Size

A tree is bounded so a single request cannot allocate unbounded memory or drive
downstream fitters past their valid range. The limits are enforced at the
earliest point that owns the value — the transport layer for the request body,
the smart constructors for the collections:

| Limit | Value | Where enforced |
|---|---|---|
| HTTP request body | 8 MiB (env `REGISTER_MAX_REQUEST_BYTES`) | zio-http `RequestStreaming.Disabled(maxRequestBytes)` — rejected with 413 before any handler runs |
| Nodes per tree | ≤ 10 000 | `RiskTree.fromNodes` (`validateNodeCount`) |
| Children per portfolio | ≤ 1 000 | `RiskPortfolio.create` (accumulated with the non-empty rule) |
| Expert percentile/quantile points | 2 … 20 | `QuantileAnchors.create` — matches the metalog fitter's valid term range |
| Mitigations per tree | ≤ 1 000 | `RiskTree.fromNodes` (`validateMitigations`) |

The 8 MiB body cap is sized above the largest valid tree payload (a maximal
10 000-node expert-leaf tree serialises to ≈ 4.94 MiB). The body-size control is
a transport-layer DoS defence and is cross-referenced from ADR-029.

### 7. One Path Form per Resource

Every route in the API has one path, with no trailing slash. A request carrying a
trailing slash matches no route, and it receives the answer any such request
receives (ADR-035 §4). `TrailingSlashInterceptor` makes this refusal once for all
routes, so a new endpoint inherits it. Zalando's RESTful API guidelines, rule 136,
state the same form: "MUST use normalized paths without empty path segments and
trailing slashes".

A create's relative `Location` depends on this. RFC 3986 §5.2.3 appends a relative
reference to everything up to the last `/` of the request's target:

| Request target | `Location` | Resolves to |
|---|---|---|
| `/w/{key}/risk-trees` | `risk-trees/{treeId}` | `/w/{key}/risk-trees/{treeId}`, the tree's route |
| `/w/{key}/risk-trees/` | `risk-trees/{treeId}` | `/w/{key}/risk-trees/risk-trees/{treeId}`, which is no route |

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
| `RiskTreeRepositoryIrmin.update` | One atomic `set_tree` replaces the whole subtree, so an omitted node is deleted by the write that omits it |
| `TreeBuilderState` (app) | `LeafDraft`/`PortfolioDraft` carry `id: Option[NodeId]`; partition on it |

### HTTP surface (workspace-capability scoped)

Every route below starts with the workspace key `/w/{key}` (ADR-021). Every
`risk-trees` route except `changed-nodes` also takes the required `X-Branch`
header. `changed-nodes` names its two branches in the query instead.

| Method & path | Request | Purpose |
|---------------|---------|---------|
| `GET /w/{key}/risk-trees` | — | List trees |
| `POST /w/{key}/risk-trees` | `RiskTreeDefinitionRequest` | Create |
| `GET /w/{key}/risk-trees/{treeId}` | — | Summary |
| `GET /w/{key}/risk-trees/{treeId}/structure` | optional `at` commit pin | Full structure, with each mitigation's resolved scope and the stale override ids |
| `PUT /w/{key}/risk-trees/{treeId}` | `RiskTreeUpdateRequest` | Full-replacement update |
| `DELETE /w/{key}/risk-trees/{treeId}` | — | Delete tree |
| `GET /w/{key}/risk-trees/{treeId}/changed-nodes` | query `a`, `aAt`, `b`, `bAt` | Nodes whose content hash differs between two revisions |
| `GET /w/{key}/risk-trees/{treeId}/history` | query `n` | The tree's commits on a branch, oldest first |
| `POST /w/{key}/risk-trees/{treeId}/revert` | `RevertTreeRequest` | Revert the tree to a chosen commit as one forward commit |
| `POST /w/{key}/risk-trees/{treeId}/nodes/{nodeId}/prob-of-exceedance` | `ProbOfExceedanceRequest`, query `threshold`, optional `at` | Exceedance probability of one node under a mitigation selection (ADR-040) |
| `POST /w/{key}/risk-trees/{treeId}/nodes/lec-multi` | `LECCurvesMultiRequest`, optional `at` | Loss exceedance curves of several nodes under a mitigation selection (ADR-040) |
| `POST /w/{key}/risk-trees/{treeId}/query` | `QueryRequest` | Evaluate a vague-quantifier query (ADR-028) |
| `POST /w/{key}/scenarios` | `CreateScenarioRequest` | Create a scenario |
| `GET /w/{key}/scenarios` | — | List scenarios |
| `GET /w/{key}/scenarios/{name}/merge-preview` | — | Preview merging a scenario into main; changes nothing |
| `POST /w/{key}/scenarios/{name}/merge` | — | Merge a scenario into main |
| `DELETE /w/{key}/scenarios/{name}` | `If-Match` header with the observed head | Delete a scenario |
| `POST /w/{key}/rotate` | — | Rotate the workspace key |
| `DELETE /w/{key}` | — | Delete the workspace and all its trees |

Creating a workspace (`POST /workspaces`) is not workspace-scoped, because no key
exists before it.

No cache-invalidation endpoint exists. The simulation cache is content-addressed
and has no invalidation operation, so there is nothing for such a route to call
(ADR-014 §2).

---

## References

- ADR-004a — Per-node content-addressed tree storage (history substrate)
- ADR-018 — Nominal `NodeId` / `TreeId` wrappers over `SafeId`
- ADR-021 — Capability-URL workspace scoping (`/w/{key}/...`)
- ADR-001 — Smart constructors and boundary validation; §2 places a whole-command refinement at the service's entry step
