# PLAN — `descendant_of` / `leaf_descendant_of` predicate efficiency (TODO 44)

## Goal

The two FOL predicates `descendant_of` and `leaf_descendant_of` answer a
boolean "is A below B?" question but currently do it by building B's entire
descendant set and testing membership — cost O(size of B's subtree) per call,
and O(n²) under quantification (`forall x. descendant_of(x, root)`). Replace the
set-build with the existing upward parent-chain walk (`TreeIndex.isAncestor`,
O(depth)). Behaviour is unchanged; only complexity improves.

## Signatures (exact bodies)

Only two dispatcher predicate bodies change in
`RiskTreeKnowledgeBase.dispatcher`. No method, type, or catalog signature moves.

`descendant_of`:

```scala
SymbolName("descendant_of") -> { args =>
  for
    desc     <- args(0).extract[NodeId]
    ancestor <- args(1).extract[NodeId]
  yield desc != ancestor && index.isAncestor(ancestor, desc)
},
```

`leaf_descendant_of`:

```scala
SymbolName("leaf_descendant_of") -> { args =>
  for
    desc     <- args(0).extract[NodeId]
    ancestor <- args(1).extract[NodeId]
  yield desc != ancestor && index.isAncestor(ancestor, desc) && leafIdSet.contains(desc)
},
```

`index.isAncestor(ancestorId, descendantId)` is
`ancestorPath(descendantId).contains(ancestorId)`. `ancestorPath` walks the
single-parent chain up from the descendant and **includes the descendant
itself**, so `isAncestor` is reflexive; the `desc != ancestor` guard reproduces
the current strict/irreflexive semantics of `descendant_of`.

Behaviour-preserving proof (all four edge classes match the old
`(index.descendants(ancestor) - ancestor).contains(desc)`):

- `desc` a strict descendant of `ancestor` → old true; new: `isAncestor` true and
  `desc != ancestor` → true.
- `desc == ancestor` → old subtracts `ancestor` then tests → false; new: guard → false.
- `ancestor` not in tree → old: `descendants` returns empty → false; new:
  `ancestorPath(desc)` cannot contain a non-existent id → false.
- `desc` not in tree → old: not in set → false; new: `ancestorPath(desc)` is empty
  → false.

## File inventory

- modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala
- build.sbt

`build.sbt` is listed only for the PATCH version bump. `.env` and `.env.irmin`
are mirrored from it (not hook-gated, handled in the same landing).
`TreeIndex.descendants` is **not** touched — it stays the right tool wherever the
whole subtree set is genuinely needed (change fan-out). No test file changes: the
existing `RiskTreeKnowledgeBaseSpec` truth-table and irreflexivity suites plus
`BinderIntegrationSpec` are the oracle that the rewrite preserves behaviour.

## ADR alignment

No ADR is engaged. No new type, endpoint, DTO, catalog symbol, or wire shape;
Iron `NodeId` typing at the boundary is unchanged. Pure internal
behaviour-preserving substitution.

## Open decisions

1. **Redundant `constants = Map.empty` in `catalog`.** `TypeCatalog.unsafe`
   defaults `constants` to `Map.empty`, so the explicit argument is not required.
   Options: (a) drop the argument (relies on the documented default); (b) keep it
   as an explicit "this catalog declares no constants" marker. This is the same
   file and pure hygiene; fold whichever you pick into this landing, or leave it.
   My recommendation: **(a) drop it** — the default is documented in
   `TypeCatalog.apply`, and the line otherwise reads as if constants exist.

## Verification plan

```bash
sbt 'commonJVM/test; server/test'
sbt 'serverIt/test'
```

Both green (the FOL predicate suites are in `server`); `serverIt` run because the
KB is on the query path. PATCH bump `0.10.19 → 0.10.20`, mirror `APP_VERSION`
into `.env` and `.env.irmin`. Landing report + one-line commit message at the end.
