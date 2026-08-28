# ADR-034: Mitigation Valuation Model

**Status:** Accepted (awaiting implementation)  
**Date:** 2026-08-28  
**Tags:** mitigation, aggregation, fold, monoid, caching

**Scope:** how a node's value is computed once mitigations are present — the two
valuations (raw and mitigated), the order transforms compose in, and the
invariant that keeps aggregation and content-addressed caching sound. The
transform **definitions** (leaf-stage vs result-stage specs, targeting,
precedence) belong to the mitigation spec plans; this ADR governs how those
definitions combine into a value.

---

## Context

- A parent's value is the aggregate of its children; this aggregation is a commutative fold, and content-addressed caching keys on it — any operation that makes an aggregate differ from the combination of its children breaks both.
- A result transform (a cap, a deductible) is non-linear: applying it to a total is not the same as applying it to each part (`f(a ⊕ b) ≠ f(a) ⊕ f(b)`), so it cannot live inside the aggregation step.
- A mitigation legitimately changes what a node is worth; that change must be representable and inspectable without corrupting the un-mitigated aggregate that caching and drill-down depend on.
- Two independent orderings exist — a transform on a child must act before its parent aggregates, and two transforms at one node do not commute — while the sequence in which mitigations were authored is not an ordering at all.
- A derived value must be reproducible from stored inputs alone, with no log of the steps that produced it.

---

## Decision

### 1. Two valuations, computed separately

`raw` is the mitigation-free commutative fold of children; it is cached and never
altered by a mitigation. `mitigated` is a second fold computed at the read edge,
carried alongside `raw`, and never stored. `transforms(node)` is identity for an
un-mitigated node, so the two coincide wherever nothing applies.

```
raw(node)       = combine(raw(child)       for child in children)   // cached, mitigation-free
mitigated(node) = transforms(node)( combine(mitigated(child) …) )   // derived at the edge
```

#### Worked example

Read every figure as dollars from one trial (the real fold runs per trial across
the whole distribution; the shape is identical). The tree carries two
result-stage caps:

```
P  (portfolio, root)
├── Q  (portfolio)     cap Q at $15
│   ├── a  (leaf)      cap a at $6,  raw $9
│   └── b  (leaf)      raw $8
└── c  (leaf)          raw $1
```

Raw fold (mitigation-free — the cached, content-addressed value):
`a_raw=9, b_raw=8, c_raw=1`; `Q_raw = 9 + 8 = 17`; `P_raw = 17 + 1 = 18`.

Mitigated fold (leaves-upward, at the edge):

| node | rule | value |
|------|------|-------|
| a | `cap6(a_raw) = cap6(9)` | 6 — leaf arm |
| b | identity | 8 |
| c | identity | 1 |
| Q | `cap15(a_mit + b_mit) = cap15(14)` | 14 — portfolio arm |
| P | `Q_mit + c_mit = 14 + 1` | 13 |

The leaf arm is the base case of one uniform rule — *apply this node's transform
to the combine of its children's mitigated values*. At `Q` the children are `a`
and `b` (`cap15(a_mit + b_mit)`); at leaf `a` there are no children, so the
combine degenerates to `a`'s own raw simulation and the rule is just `cap6(9)`.

The mitigated value cannot decorate the cached aggregate. Applying Q's cap to the
cached raw `Q_raw = 17` gives `cap15(17) = 15`, but the correct `Q_mit` is `14`.
The `$1` gap is `a`'s own cap: capping the raw total at 15 never sees that `a` was
already pulled from `$9` to `$6`. Only folding the *mitigated* children
(`6 + 8 = 14`, then `cap15`, which does not bind) attributes the reduction to the
layer it happened at — which is why the mitigated value is a separate fold
(Decision 1), never the raw aggregate transformed (Decision 4).

### 2. A node's value is its transforms applied to its children's combined value

Each node's value is that node's own mitigations, applied in their set order, to
the combined value of its children. A node with no mitigations is just the
combined children — the ordinary case, and the only case before any mitigation
exists.

### 3. Transforms compose by position, never by authoring order

`mitigated` is folded leaves-upward, so a child's transform always acts before
its parent aggregates. Within one mitigation, `TransformPipeline` steps run in
list order; across two mitigations on the same node, `MitigationPrecedence`
orders them — both because the transforms do not commute. The order mitigations
were authored in does not enter the computation.

```
// D = A ⊕ B ⊕ C, raw 18 = 9 + 8 + 1. cap A at 6, cap D at 15.
mitigated(A) = cap6(9) = 6      // child transform first
mitigated(D) = cap15(6 + 8 + 1) = cap15(15) = 15   // parent transform sees the mitigated total
// authoring "cap D then cap A" yields the identical result
```

### 4. The raw aggregate is never mutated to carry a mitigation

At a binding cap, `mitigated(node) ≠ combine(mitigated(children))` — the cap
removed something at this level — while `raw(node)` still equals
`combine(raw(children))`. The cap lives in the mitigated fold as this node's
transform layer. Drilling **decomposes** `mitigated(node)` into that layer and
the children's mitigated aggregate, so where each reduction happened stays
visible.

### 5. Reproducible from raw × active mitigations

Stored state is the raw tree (versioned) and the mitigation definitions (each
pinned to a node, each with its precedence). Mitigated values are re-derived on
demand; identical `(raw version, active mitigation set)` yields identical
mitigated values. There is no stored mitigated tree and no application log —
history is raw versions combined with active mitigations.

---

## Code Smells

### ❌ Mutating the aggregate to carry a mitigation

```scala
// BAD: a builder that lets a group's aggregate differ from its children
RiskResultGroup.withAggregate(nodeId, children, cappedOutcomes)   // aggregate ≠ combine(children)

// GOOD: the raw group is a pure combine; the cap lives in the mitigated fold
RiskResultGroup.create(nodeId, children*)                         // aggregate = combine(children)
```

### ❌ Applying a portfolio transform per child

```scala
// BAD: transform each child, then combine — wrong figures (transform is non-linear)
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

### ❌ Persisting the mitigated tree or an application log

```scala
// BAD: store derived mitigated results, or a replay sequence, as source of truth
store.put(treeId, mitigatedTree)

// GOOD: store raw tree + mitigation definitions; re-derive mitigated on read
store.put(treeId, rawTree)   // definitions travel with the tree; mitigated is a function of both
```

---

## Implementation

| Concern | Location |
|---------|----------|
| Raw fold (cached, mitigation-free) | `RiskResultGroup.create` — combine of children |
| Leaf-stage mitigated tree | `MitigationApplication.effectiveTree` (drives cache keys) |
| Result-stage transform on a leaf | `MitigationApplication.resultTransformFor`, applied at the result resolver edge |
| Result-stage fold onto a portfolio aggregate | PLAN-RISKTRANSFORM §8.14 (Option F wiring) |
| Same-node ordering | `TransformPipeline` step order; `MitigationPrecedence` across mitigations |
| Non-mutation invariant | `RiskResultGroup` private constructor (aggregate = combine(children), no exception) |

---

## References

- ADR-009 — associativity: a transform acts on a finished operand, never inside the combine
- ADR-003 — provenance of a computed value
- ADR-015 — cache-aside; keys are effective content, misses re-simulate
- PLAN-MONOID-RISKRESULT-AND-MITIGATION.md §B — the monoid / staged-mitigation analysis (B3 result-stage, B4 aggregation-stage)
- PLAN-RISKTRANSFORM.md §8.14 — the result-resolver-edge wiring that realizes this model
