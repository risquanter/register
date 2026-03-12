# ADR-004a Appendix: Tree Terminology & Domain-to-Storage Mapping

**Parent ADR:** [ADR-004a-proposal](ADR-004a-proposal.md) — Persistence Architecture (SSE Variant)  
**Date:** 2026-03-12

---

## 1. Tree Terminology Glossary

Three distinct "tree" concepts coexist in this system. They share the word
but refer to different things.

| Concept | What it is | Where it appears |
|---------|-----------|------------------|
| **Risk Tree** (domain model) | The user's hierarchical risk model — portfolios containing leaves with loss distributions. This is the core domain object that users create, edit, and analyse. | `RiskTree`, `RiskLeaf`, `RiskPortfolio`, `TreeId`, `NodeId` |
| **Irmin Tree** (storage structure) | Irmin's filesystem-like namespace. Every path such as `risk-trees/tree1/nodes/cyber` is a node in Irmin's internal tree structure. This is what `Tree` means in the Irmin GraphQL schema (`Tree { hash, get(path), list }`). | `IrminPath`, `Tree` type in `irmin-schema.graphql` |
| **Merkle Tree** (computer science concept) | Any hash-linked tree where a parent's hash is derived from its children's hashes. Both Irmin's internal storage and the planned content-addressed cache use this pattern. A change to any leaf propagates hash changes up to the root. | `Tree.hash`, `Contents.hash` in the Irmin schema; planned `ContentHash` cache key |

**The critical distinction:** Irmin does not know or care that the data it
stores represents a risk tree. To Irmin, it is key–value pairs at paths. The
Irmin "Tree" is its internal storage structure — analogous to a filesystem
directory. The "RiskTree" is the domain model that happens to be serialised
into Irmin's tree.

---

## 2. Domain-to-Storage Mapping

The per-node storage convention (established in ADR-004a §1) maps each risk
node to a single Irmin path. This is implemented in `RiskTreeRepositoryIrmin`.

### Storage layout

```
risk-trees/
  {treeId}/
    meta                          ← JSON: name, rootId, schemaVersion, timestamps
    nodes/
      {nodeId-cyber}              ← JSON: leaf params (type, probability, min, max, …)
      {nodeId-hardware}           ← JSON: leaf params
      {nodeId-ops-risk}           ← JSON: portfolio (children list, correlation, …)
      {nodeId-portfolio-root}     ← JSON: portfolio (root node)
```

### Why this mapping matters

The 1:1 correspondence between domain nodes and Irmin paths has three
consequences:

1. **Granular versioning.** Changing one node's parameters (e.g. switching
   cyber-risk from expert-opinion to lognormal) produces a single Irmin
   commit touching a single path. The commit message carries a structured
   prefix (`risk-tree:{treeId}:update:{txn}:set-node:{nodeId}`) that
   identifies exactly what changed.

2. **O(depth) cache invalidation.** Because each node is a separate path,
   Irmin's change tracking is per-node. The ancestor-path invalidation
   strategy (ADR-005, ADR-014) walks from the changed node to the root,
   invalidating only the affected O(depth) cache entries — not the entire
   tree.

3. **Path-level merge resolution.** Irmin merges operate at the path level.
   When two branches modify different nodes (different paths), Irmin
   auto-resolves the merge with no conflicts. The per-node storage layout
   maximises the surface area for automatic merge resolution — a direct
   enabler for scenario branching (ADR-007).

### Concrete example

Given a risk tree:

```
portfolio (root)
   ├── ops-risk
   │   ├── cyber       { prob: 0.3, type: lognormal, min: 1M, max: 50M }
   │   └── hardware    { prob: 0.1, type: lognormal, min: 0.5M, max: 5M }
   └── market-risk     { prob: 0.4, type: expert, percentiles: [...] }
```

Irmin stores five values at five paths:

| Irmin path | Content |
|-----------|---------|
| `risk-trees/{treeId}/meta` | `{ "rootId": "{portfolio-id}", "name": "My Portfolio", … }` |
| `risk-trees/{treeId}/nodes/{portfolio-id}` | `{ "type": "portfolio", "children": ["{ops-id}", "{market-id}"] }` |
| `risk-trees/{treeId}/nodes/{ops-id}` | `{ "type": "portfolio", "children": ["{cyber-id}", "{hw-id}"] }` |
| `risk-trees/{treeId}/nodes/{cyber-id}` | `{ "type": "leaf", "prob": 0.3, "distribution": "lognormal", … }` |
| `risk-trees/{treeId}/nodes/{hw-id}` | `{ "type": "leaf", "prob": 0.1, "distribution": "lognormal", … }` |

Updating cyber-risk probability from 0.3 to 0.6 results in exactly one
`irmin.set` call to path `risk-trees/{treeId}/nodes/{cyber-id}`, producing
one commit on the active branch.
