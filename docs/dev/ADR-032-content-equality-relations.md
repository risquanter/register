# ADR-032: Content Equality Strategy — Domain Hash vs Storage Hash

**Status:** Accepted
**Date:** 2026-07-24
**Tags:** hashing, caching, diff, merge, irmin

---

## Context

- A content-addressed store (Irmin) hashes the **full persisted artifact** — every byte of a node's JSON blob, including presentation and topology fields (`name`, `parentId`).
- Simulation-cache reuse requires an equality that **survives renames and moves**: two leaves with identical simulation inputs must produce the same hash so cache entries are shared and edits to cosmetic fields do not trigger re-simulation.
- Storage-level merge resolves per path on **byte equality**: two branches merge cleanly at a path only when the stored bytes are reconcilable via the three-way merge base.
- One hash relation cannot serve both questions: any projection that excludes a persisted field makes "semantically equal but byte-different" states reachable, and any relation over full bytes invalidates on semantically irrelevant edits.

---

## Decision

### 1. Two named equality relations, selected by the question asked

| Relation | Hashes what | Answers |
|---|---|---|
| **Domain content hash** (`ContentHashIndex`) | `LeafSimContent` projection of a leaf; Merkle over sorted child hashes for a portfolio | "Does this change simulation results?" |
| **Storage hash** (Irmin blob hash) | Full persisted node JSON (`RiskTreeRepositoryIrmin.nodeJson`) | "Did the stored artifact change? Can a merge conflict here?" |

The projections differ deliberately:

```scala
// Domain relation input — simulation-relevant fields only:
final case class LeafSimContent(
  seedVarId: SeedVarId.SeedVarId,
  probability: OccurrenceProbability,
  distributionType: DistributionType,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  terms: Option[PositiveInt]
)

// Storage relation input — the full leaf, additionally carrying
// safeName and parentId:
private def nodeJson(node: RiskNode): String = node match
  case leaf: RiskLeaf           => leaf.toJson
  case portfolio: RiskPortfolio => portfolio.toJson
```

Irmin is itself a Merkle store — the distinction is **which JSON gets hashed**, not "Merkle vs not".

### 2. Semantic diff uses the domain relation

`ScenarioDiffService` compares domain content hashes. A renamed or moved node reports `Identical`: the diff tells a user whether the *risk content* differs between branches, and a rename is not a risk change.

```scala
// Rename on one branch, no other edit:
diffService.diff(wsId, treeId, main, scenario)
// → NodeDiff(nodeId, Identical)   — correct for this relation
```

### 3. Merge-conflict prediction uses the storage relation

A pre-check that predicts Irmin merge outcomes must compare stored bytes (Irmin's own hashes, or a hash of the persisted node JSON) between each branch and the merge base — never domain hashes. The divergence is reachable through ordinary edits:

```
main:      c2 ── c5 (edit cyber-risk probability) ──►
               \
scenario:       └── c3 (rename cyber-risk) ─────────►
```

- Domain relation: changed on one side only → no conflict predicted.
- Storage relation: both branches changed the bytes of `nodes/{id}` since the base → Irmin merge **conflicts**.

### 4. Do not unify the relations

- Widening the domain hash to cover `name`/`parentId` would invalidate simulation caches on every rename or move.
- Replacing storage-level merge with a merge over the domain projection would require reimplementing Irmin's three-way merge in the application.

Both hashes stay; each caller states which question it is asking.

---

## Code Smells

### ❌ Predicting merge outcomes from the semantic diff

```scala
// BAD: domain-hash diff gates the merge — misses byte-level conflicts
diffService.diff(wsId, treeId, main, scenario).map {
  case Diff(entries) if entries.forall(_.status == Identical) => enableMerge()
}

// GOOD: byte-level comparison against the merge base gates the merge
storageConflicts(wsId, treeId, main, scenario, mergeBase).map {
  case Nil => enableMerge()
  case cs  => showConflicts(cs)
}
```

### ❌ Driving user-facing "changed" markers from storage hashes

```scala
// BAD: rename shows as a change — noise in a risk-content diff
irminHashesA(path) != irminHashesB(path)

// GOOD: domain hashes — only simulation-relevant differences surface
hashesA(nodeId) != hashesB(nodeId)  // ContentHashIndex output
```

### ❌ Growing `LeafSimContent` beyond simulation inputs

```scala
// BAD: cache entries die on rename; renames re-simulate
final case class LeafSimContent(name: SafeName, /* ... */)

// GOOD: projection stays exactly the simulation inputs
final case class LeafSimContent(seedVarId: SeedVarId.SeedVarId, /* ... */)
```

---

## Implementation

| Location | Relation | Role |
|----------|----------|------|
| `ContentHashIndex` | Domain | Builds per-node domain hashes (leaf projection + portfolio Merkle) |
| `LeafSimContent` | Domain | The hashed projection; also the cache-key preimage |
| `ScenarioDiffService` | Domain | Branch-to-branch semantic diff |
| `RiskTreeRepositoryIrmin.nodeJson` | Storage | Defines the persisted blob Irmin hashes |
| `IrminClient.mergeBranch` / `lca` | Storage | Byte-level three-way merge and its base |

---

## References

- [ADR-014: RiskResult Caching Strategy](ADR-014.md) — why cache keys use the domain relation
- [ADR-007 Appendix §4](ADR-007-appendix.md#4-merge-and-the-per-node-advantage) — per-path merge mechanics
- [ADR-004a Appendix §2](ADR-004a-appendix.md#2-domain-to-storage-mapping) — node-per-path storage layout
