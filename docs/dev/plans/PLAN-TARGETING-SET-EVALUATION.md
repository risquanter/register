# PLAN-TARGETING-SET-EVALUATION — resolve a targeting predicate as set algebra over the tree index

**Status:** draft, blocked on an engine release. vql-engine's T-016 in its
`docs/TODOS.md` (repository `vague-quantifier-logic`) adds the question this
plan answers. The engine's plan for it is `docs/PLAN-predicate-satisfying-set.md`
in that repository, plan code PSS, a draft. The plan becomes implementation-grade when that release exists
and its signature can be quoted. Open decisions are listed in §6.

**Inventory:** `PLAN-TARGETING-SET-EVALUATION-INVENTORY.md`, which does not
exist yet. The user creates it when this plan is approved.

## 1. Goal

A mitigation's scope is the set of nodes its targeting predicate holds for.
Resolving it should cost about the size of the answer, not the size of the
tree times the size of the predicate.

## 2. Current state and its cost

`MitigationScopeResolver.resolveUncached`
(`modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`)
resolves each mitigation through the engine's `TypedSemantics.satisfyingSet`.
The engine visits every node and evaluates the whole predicate there, asking
`RiskTreeKnowledgeBase`'s dispatcher once per atom per node.

Measured on 2026-11-16 on the server's native image at 0.22.0, with the
shipped caps of 2 CPUs and 256 MB:

| Nodes | Mitigations | Atoms each | First structure read |
|---|---|---|---|
| 10 000 | 1 | 257 | 0.60 s |
| 10 000 | 10 | 257 | 5.4 s |
| 10 000 | 100 | 257 | 52.4 s, with a 2 GB cap |

That is about 210 ns per atom per node. At the bounds that hold after
PLAN-RISKTRANSFORM §11, 1 000 mitigations of about 186 atoms over 10 000 nodes,
one resolution extrapolates to about 6.5 minutes. `POST /workspaces` needs no
key, so anyone who reaches the server can submit such a tree.

## 3. Design

Every atom the targeting fragment admits has an answer the tree index already
holds as a whole set:

| Atom | Its satisfying set | From |
|---|---|---|
| `leaf(x)` | the leaf identifiers | `TreeIndex.leafIds` |
| `portfolio(x)` | the portfolio identifiers | the knowledge base's `portfolioIds` |
| `eq(x, node_id("N"))` | `{N}`, or empty when the tree has no `N` | the node map |
| `child_of(x, node_id("P"))` | `P`'s children | `TreeIndex.children` |
| `descendant_of(x, node_id("P"))` | `P`'s subtree without `P` | `TreeIndex.descendants` |
| `leaf_descendant_of(x, node_id("P"))` | that subtree's leaves | `descendants` ∩ `leafIds` |

The engine owns how `/\`, `\/` and `~` combine those sets, by T-016.
Register's knowledge base answers the per-atom question for the six rows
above, and declines every other atom. An atom whose arguments repeat the
variable, such as `descendant_of(x, x)`, has no fixed argument and is
declined, so the engine evaluates it per node as today.

Example: a target picking 42 nodes by identifier is 42 lookups and 41 unions.
Today it is 42 times 10 000 dispatcher calls.

`Formula` stays opaque to register, by PLAN-RISKTRANSFORM §8.4-3.

## 4. Signatures

The register side overrides the engine's new dispatcher method in
`RiskTreeKnowledgeBase`. The method's name and parameter types are T-016's,
and are not written here until that release exists. The answer for each row
of §3 is a pure function of the knowledge base's fields:

```scala
/** The nodes satisfying a structural predicate with every argument but the
  * node variable fixed, or None for an atom the tree index cannot answer
  * whole. */
private def structuralSet(predicate: QueryPredicate, fixed: List[NodeId]): Option[Set[NodeId]]
```

Family: the dispatcher entries in `RiskTreeKnowledgeBase`, of which
`SymbolName(QueryPredicate.Leaf.symbol) -> { args => args(0).extract[NodeId].map(leafIdSet.contains) }`
is one. No existing function answers a whole satisfying set.

## 5. Tests and verification

- **The law, one test per predicate of §3:** for every fixed argument in the
  fixture tree, `structuralSet` equals the nodes for which the per-node
  dispatcher answers true.
- **`MitigationScopeResolverSpec`:** every existing case gives the same scope.
- **A performance check in `tests/perf/`:** 1 000 mitigations of 186 atoms over
  10 000 nodes resolve on the native image under the shipped caps. The time
  figure is decided in TSE-D-2.

```bash
sbt 'commonJVM/test; server/test'   # green
sbt serverIt/test                   # green
```

## 6. Open decisions

- **TSE-D-1 — the two smaller gains measured on 2026-11-16, in this plan or
  not.**
  - (a) Evaluate each distinct atom once per tree, and reuse its answer across
    mitigations.
  - (b) On a tree update, take the old version's scopes from the memo in
    `InvalidationHandler.mitigationAffectedNodes`
    (`modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala`),
    which today resolves both versions from scratch.
- **TSE-D-2 — the time a worst-case resolution must stay under** for the
  performance check in §5.

## 7. ADR alignment

- **ADR-028:** compliant. Evaluation stays in the engine, and the knowledge
  base answers from the tree.
- **ADR-009:** not touched. Scopes enter no aggregate.
- **ADR-014:** not touched. Scopes are not simulation results.

## 8. Terminology impact

None. No glossary term names how a scope is computed.
