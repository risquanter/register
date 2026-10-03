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

### 3. The aggregate claim is never mutated; the layer sits outside it

`PortfolioLosses.create` enforces one claim: the aggregate is the combine of the
children it was given. It derives the total from those children and offers no
parameter through which a different one could arrive, so the claim cannot be
made falsely. `raw` always honours it; `mitigated` cannot, wherever a transform
binds — so the transform is applied **outside** the aggregate rather than
written into it.

Nothing is lost by this. The aggregate underneath a transformed node still holds
exactly its children and still equals their combine; the node's own figures are
the layer applied to that total. Where each reduction happened stays visible: a
node's mitigated value and the combine of its children's mitigated values differ
by exactly that node's transform layer. A client reads a child's mitigated value
by requesting that node.

Because the layer never touches the aggregate claim, applying a transform does
not change the shape of the answer. A mitigation that happens to change no figure
returns the same kind of value as one that changes every figure.

### 4. Every reading carries its layer; raw is the identity instance

The mitigated fold produces at every node it visits a value recording what the
transform layer was applied to and which mitigation applications produced it. The
empty record list is the identity, so a node with nothing in scope carries one
too.

```scala
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,                       // after this node's layer
  source: TrialOutcomes,                       // the figures that layer was applied to
  applied: List[MitigationApplicationRecord],  // empty = identity = a raw reading
  provenance: Option[NodeProvenance]           // a simulated leaf has one; a portfolio none
) extends LECCurve
```

Recording is unconditional because a no-op mitigation is authorable —
`ScaleLosses(1.0)`, `ApplyDeductible(0)`, `FilterBelowThreshold(0)` — so
"record when something changed" would make the answer depend on a parameter's
numeric value. `source` is figures, not a nested value: the value names no
internal type and holds no reference to another node, so its size is bounded by
its own node rather than by the subtree beneath it. Applying an empty layer must
return the **same** `TrialOutcomes` reference, not an equal rebuild, or every
untransformed node reallocates its outcome map on every read; the factory
short-circuits and passes one reference twice, which makes that a property of the
constructed value rather than a rule the transform has to honour.

The layer is applied strictly above the cache boundary: it works on values the
cache already returned, so no key, entry or hash input changes.

### 5. Reproducible from raw × active mitigations

Stored state is the raw tree (versioned) and the mitigation definitions. Mitigated
values are re-derived on demand; identical `(raw version, active mitigation set)`
yields identical values. There is no stored mitigated tree and no application log.

### 6. A mitigation may reduce a risk; none may eliminate it

A residual risk of exactly zero asserts that the control cannot fail, and controls
fail. A model that admits a zero residual has removed control failure from the
analysis, which is the thing a risk register exists to keep visible. The exclusion
is methodological, not arithmetic.

The three scale operations carry this in their type. `LikelihoodTransform.Scale`,
`DistributionTransform.ScaleSeverity` and `ResultTransformSpec.ScaleLosses` all
take a `RetentionFactor` — above 0 and at most 1 — so a factor of zero is rejected
at the boundary with a message, and a factor above 1 is rejected too, because a
figure that increases is stated absolutely through an `Override` rather than as a
factor.

A risk that genuinely no longer exists is not a mitigated risk: the system was
decommissioned, the data deleted, the business line exited. That is removal of the
node from the tree, which leaves an audit trail in the tree's history. It is not a
transform applied to a node that remains.

The rule has two enforcement points, because the paths to a zero residual divide
on whether the parameter annihilates on its own.

**A type at the boundary, where the parameter decides it.** `Override` takes a
`ResidualProbability` and `CapLosses` a `PositiveLong`, so a probability of zero
and a cap of zero are refused before any handler runs. `ResidualProbability` is
deliberately narrower than `OccurrenceProbability`: an author may declare a leaf
that never occurs, which is a statement about the world, while a mitigation may
not set the probability to zero, which claims a control cannot fail.

**A check at the layer boundary, where the outcomes decide it.** Three parameters
annihilate only against particular trial outcomes: a deductible at or above every
loss, a threshold above every loss, and a scale factor small enough that every
loss rounds to zero and is dropped by `scaleLosses`' sparse-storage filter. The
same deductible is ordinary for a node whose losses run in the millions and
annihilating for one whose losses run in the thousands, and a factor of `0.001`
is harmless against millions and fatal against hundreds. No type can close any of
the three, so `LossDistribution.decorate` compares the node's source outcomes
against the layer's result and refuses a layer that leaves no loss where there was
one. The comparison is what makes it safe: a node whose outcomes held no loss to
begin with has a zero residual the simulation produced, not one a mitigation
asserted, and is not refused.

The scale factor is the case that shows why the check cannot be replaced by
tightening the types. Its bound is already as tight as the methodology allows —
above 0 and at most 1 — and it still annihilates, because the loss of precision
happens in the rounding, not in the parameter.

Adding a transform therefore means deciding its annihilating case against the
layer check, rather than assuming a factor bound covers it.

One thing is not an instance of the rule: `applyDeductible` and `scaleLosses` end
with `.filter(_._2 > 0)`. `TrialOutcomes.outcomeOf` reads an absent trial as `0L`,
so a stored zero and an absent entry are the same value and the filter puts a zero
into its canonical form. No outcome is discarded.

---

## Code Smells

### ❌ Mutating the aggregate to carry a mitigation

```scala
// BAD: a builder letting the aggregate differ from its children
PortfolioLosses.withAggregate(nodeId, children, cappedOutcomes)   // aggregate ≠ combine(children)

// GOOD: the aggregate is a pure combine; the cap is the layer applied on top of it
PortfolioLosses.create(nodeId, children)                          // aggregate = combine(children)
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
// BAD: a no-op mitigation now changes the shape the caller receives
if (records.isEmpty) aggregate else flatten(aggregate, run(records, aggregate))

// GOOD: decorate every visited node; the empty record list is the identity
LossDistribution.decorate(id, source, provenance, records, run(records, _))
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
| Raw fold (cached, mitigation-free) | `PortfolioLosses.create` — combine of children | live |
| Non-mutation invariant | `PortfolioLosses` private constructor, aggregate derived not accepted | live |
| Leaf-stage mitigated tree | `MitigationApplication.effectiveTree` — drives cache keys | live |
| A node's result-stage layer | `MitigationApplication.recordsByNode` and `run`, at the resolver edge | live |
| Layer applied outside the aggregate claim | `LossDistribution.decorate`, called from both arms of `distributionOf` | live |
| Same-node ordering | `TransformPipeline` step order; `MitigationPrecedence` across mitigations | live |
| `applied` records on every reading | `LossDistribution.applied` | live |

---

## References

- ADR-009 — associativity: a transform acts on a finished operand, never inside the combine
- ADR-015 — cache-aside; keys are effective content, misses re-simulate
- `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md` — the derivation behind Decisions 1, 3 and 4, built from first principles
