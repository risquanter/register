# ADR-034: Mitigation Valuation Model

**Status:** Accepted  
**Date:** 2026-09-14  
**Tags:** mitigation, aggregation, fold, monoid, caching

---

## Context

- This model governs how transform **definitions** combine into a value; the definitions themselves — stage, targeting, precedence — are specified elsewhere.
- A parent's value is the combine of its children's values. Content-addressed caching keys on that identity, so any operation making an aggregate differ from the combine of its children breaks caching and aggregation together.
- A result transform (a cap, a deductible) is non-linear: `f(a ⊕ b) ≠ f(a) ⊕ f(b)`. It is not a homomorphism, so it cannot live inside the combine step. It follows that "every node is the combine of its children" and "a genuine aggregate cap exists" are contradictory requirements, and one value per node cannot satisfy both.
- Two orderings are real — a child's transform acts before its parent aggregates, and two transforms at one node do not commute — while the order mitigations were authored in is not an ordering at all.
- A derived value must be reproducible from stored inputs alone, with no log of the steps that produced it.

---

## Decision

### 1. Two valuations, computed separately

`raw` is the mitigation-free commutative fold; it is cached and never altered by a
mitigation. `mitigated` is a second fold computed at the read edge and never
stored. Each node applies its own transforms to the combine of its children's
**mitigated** values, so a node with no transform yields that combine unchanged.

```
raw(node)       = combine(raw(child)       for child in children)   // cached
mitigated(node) = transforms(node)( combine(mitigated(child) …) )   // at the edge
```

Worked example. One trial, in dollars; the real fold runs per trial.

```
P (root)                      raw: a=9, b=8, c=1
├── Q          cap Q at $15         Q_raw = 9+8 = 17,  P_raw = 17+1 = 18
│   ├── a      cap a at $6     mitigated: a = cap6(9) = 6, b = 8, c = 1
│   └── b                                 Q = cap15(6+8) = cap15(14) = 14
└── c                                     P = 14+1 = 13
```

`cap15` applied to the cached `Q_raw = 17` gives 15, not 14. The $1 gap is `a`'s
own cap: capping the raw total never sees that `a` was already pulled from 9 to 6.
Only folding the mitigated children attributes each reduction to the layer it
happened at.

### 2. Transforms compose by position, never by authoring order

`mitigated` folds leaves-upward, so a child's transform always acts before its
parent aggregates. Within one mitigation, `TransformPipeline` steps run in list
order; across mitigations on one node, `MitigationPrecedence` orders them.

```scala
// D = A ⊕ B ⊕ C, raw 18 = 9 + 8 + 1. cap A at 6, cap D at 15.
mitigated(A) = cap6(9) = 6                          // child transform first
mitigated(D) = cap15(6 + 8 + 1) = cap15(15) = 15    // parent sees the mitigated total
// authoring "cap D then cap A" yields the identical result
```

### 3. The raw aggregate is never mutated, and a transformed node's value is flat

`RiskResultGroup`'s private constructor enforces one claim: its aggregate is the
combine of its children. `raw` always honours that claim; `mitigated` cannot,
wherever a transform binds. So a transformed node's mitigated value is a plain
transformed-outcomes value and is deliberately **not** a `RiskResultGroup`.

Nothing is lost by this. The value never carried the children-claim, and a type
asserting it would assert something false. Where each reduction happened stays
visible: a node's mitigated value and the combine of its children's mitigated
values differ by exactly that node's transform layer. A client reads a child's
mitigated value by requesting that node.

### 4. Every mitigated reading is a `ValuationResult`; raw is its identity instance

The mitigated fold wraps every node it visits, recording what the transform layer
was applied to and which mitigation applications produced it. The empty record
list is the identity, so a node with nothing in scope is wrapped too.

```scala
final case class ValuationResult private (
  override val nodeId: NodeId,
  source: LossDistribution,                     // the value the layer was applied to
  applied: List[MitigationApplicationRecord],   // empty = identity = a raw reading
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes)
```

Wrapping is unconditional because a no-op mitigation is authorable —
`ScaleLosses(1.0)`, `ApplyDeductible(0)`, `FilterBelowThreshold(0)` — so
"wrap when something changed" would make the return type depend on a parameter's
numeric value. `source` keeps the children reachable at a transformed node.
Applying an empty pipeline must return the **same** `TrialOutcomes` reference,
not an equal rebuild, or every untransformed node reallocates its outcome map on
every read.

The decorator is built strictly above the cache boundary: it is constructed from
values the cache already returned, so no key, entry or hash input changes.

### 5. Reproducible from raw × active mitigations

Stored state is the raw tree (versioned) and the mitigation definitions. Mitigated
values are re-derived on demand; identical `(raw version, active mitigation set)`
yields identical values. There is no stored mitigated tree and no application log.

---

## Code Smells

### ❌ Mutating the aggregate to carry a mitigation

```scala
// BAD: a builder letting a group's aggregate differ from its children
RiskResultGroup.withAggregate(nodeId, children, cappedOutcomes)   // aggregate ≠ combine(children)

// GOOD: the raw group is a pure combine; the cap lives in the mitigated fold
RiskResultGroup.create(nodeId, children*)                         // aggregate = combine(children)
```

### ❌ Applying a portfolio transform per child

```scala
// BAD: transform each child, then combine — wrong figures, the transform is non-linear
combine(children.map(c => cap(c)))

// GOOD: combine the mitigated children, then apply the node's transform to the total
cap(combine(children.map(mitigated)))
```

### ❌ Composing transforms in authoring order

```scala
// BAD: fold mitigations in the order they were added to the tree
mitigations.foldLeft(base)((acc, m) => m.run(acc))

// GOOD: fold leaves-upward; order same-node transforms by precedence
transformsByPrecedence(node).run( combine(children.map(mitigated)) )
```

### ❌ Deciding the return shape from whether a transform changed anything

```scala
// BAD: a no-op mitigation now changes the type the caller receives
if (outcomes == transformed) raw else ValuationResult(id, raw, records, transformed)

// GOOD: wrap every visited node; the empty record list is the identity
ValuationResult(id, raw, records, run(records, raw.trialOutcomes))
```

### ❌ Persisting the mitigated tree or an application log

```scala
// BAD: store derived mitigated results, or a replay sequence, as source of truth
store.put(treeId, mitigatedTree)

// GOOD: store raw tree + mitigation definitions; re-derive mitigated on read
store.put(treeId, rawTree)
```

---

## Implementation

| Concern | Location | State |
|---------|----------|-------|
| Raw fold (cached, mitigation-free) | `RiskResultGroup.create` — combine of children | live |
| Non-mutation invariant | `RiskResultGroup` private constructor, no exception | live |
| Leaf-stage mitigated tree | `MitigationApplication.effectiveTree` — drives cache keys | live |
| Result-stage transform on a leaf | `MitigationApplication.resultTransformFor`, at the resolver edge | live |
| Result-stage fold onto a portfolio aggregate | `CachedResultResolverLive` — portfolio arm of `distributionOf` | live |
| Same-node ordering | `TransformPipeline` step order; `MitigationPrecedence` across mitigations | live |
| `ValuationResult` and its `applied` records | `LossDistribution` hierarchy; built at the resolver edge | ruled, not yet built |

---

## References

- ADR-009 — associativity: a transform acts on a finished operand, never inside the combine
- ADR-015 — cache-aside; keys are effective content, misses re-simulate
- `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md` — the derivation behind Decisions 1, 3 and 4, built from first principles
