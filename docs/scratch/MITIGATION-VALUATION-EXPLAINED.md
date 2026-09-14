# How a mitigated value is computed, and why its type is what it is

**Status: design record, not yet an ADR.** Whether this becomes its own decision
record or an appendix to ADR-034 is undecided. Its job right now is to hold the
reasoning accurately while it is still fresh.

This is a teaching write-up. It builds the vocabulary first, then the fold, then
the obstruction, then the ruling that resolves it, then the consequences for the
result type. It assumes no prior knowledge of the algebra and defines every term
at the point it is first needed.

---

## Part 1 — The vocabulary, in dependency order

### 1.1 What a node's value is

Every risk node's value is a `TrialOutcomes`: the result of running the Monte
Carlo simulation a fixed number of times and recording the loss produced in each
run. One run is a **trial**.

Throughout this document a node's value is shown as a single number, meaning the
loss in one trial. That is a slice through the real structure. Everything here
applies identically to every other trial, because every operation described works
trial by trial.

### 1.2 Combine

**Combine**, written `(+)`, is per-trial addition: for each trial, add the two
losses recorded for that trial.

If child A loses 9 in trial 1 and child B loses 14 in trial 1, then `A (+) B`
loses 23 in trial 1.

### 1.3 Monoid

A **monoid** is a set of things plus a way of combining two of them, subject to
two rules:

- **Associativity** — grouping does not matter: `(a (+) b) (+) c` equals
  `a (+) (b (+) c)`.
- **Identity** — some element changes nothing: `a (+) identity = a`. Here it is
  the all-zeros value, a simulation in which nothing ever goes wrong.

`TrialOutcomes` under `(+)` is a monoid. It is also **commutative**: order does
not matter either, because addition does not care about order.

Why this matters in practice: associativity is the property that licenses
computing a portfolio's children in parallel and in any order. Without it the
resolver could not use parallel evaluation over children without changing the
answer.

### 1.4 Fold, also called a catamorphism

A **fold** collapses a tree into a single value by giving one rule that says how
a node's value is built from its children's values. The same rule is applied at
every node, from the leaves upward. The formal name is a **catamorphism**; fold
and catamorphism mean the same thing here.

A fold is defined by two clauses: what to do at a leaf, and what to do at a node
with children.

### 1.5 Homomorphism

A function `f` is a **homomorphism** with respect to `(+)` when it does not care
whether you apply it before or after combining:

```
f(a (+) b)  =  f(a) (+) f(b)
```

A function with this property can be pushed inside a combine freely. A function
without it cannot. This single property is the hinge of everything that follows.

---

## Part 2 — The raw valuation

The **raw valuation**, written `r`, is the fold the system has always computed:

```
r(leaf)      = sim(leaf)                  -- run the simulation
r(portfolio) = (+) over r(children)       -- combine the children's raw values
```

Read the second line aloud: a portfolio's raw value is the combine of its
children's raw values, and nothing else.

This gives a property that holds at every node in the tree:

> **I1 — every node's value equals the combine of its children's values.**

`I1` is not decoration. The content-addressed cache depends on it. A leaf is
cached under a hash of its own simulation parameters; a portfolio is never cached
at all, because it can always be rebuilt by combining its children. That is only
sound while `I1` holds. If a portfolio's stored value stopped equalling the
combine of its children, rebuilding it from the children would produce a
different number from reading it, and the cache would be serving a value the tree
cannot reproduce.

The type system enforces `I1`. `RiskResultGroup` is the type of an aggregated
value, and its constructor is private. The only way to build one is
`RiskResultGroup.create(nodeId, children*)`, which computes the aggregate *from*
the children. It is not possible to hand it an aggregate of your choosing.

So a `RiskResultGroup` is not merely "a value that happens to have children
attached". It is a value carrying a claim, and the claim is enforced:

> **my aggregate is the combine of my children.**

Everything in Part 5 turns on that sentence.

---

## Part 3 — The obstruction

### 3.1 What a result-stage mitigation is

A **result-stage mitigation** is a function applied to a finished value. An
insurance cap is the clearest case: "whatever the loss came out to, do not pay
more than 18". Written `cap18`. A deductible is another: "subtract the first 2,
floor at zero".

There is a second kind, a **parameter-stage** mitigation, which changes a leaf's
simulation inputs before the simulation runs. It is a different mechanism, it is
baked into the tree before hashing, and it is not what this document is about.
Everything below concerns result-stage transforms.

### 3.2 A cap is not a homomorphism

Take a cap at 10 and two children that each lose 8 in the same trial.

Apply the cap after combining:

```
cap10(8 (+) 8) = cap10(16) = 10
```

Apply the cap to each child, then combine:

```
cap10(8) (+) cap10(8) = 8 (+) 8 = 16
```

10 is not 16. So `f(a (+) b) != f(a) (+) f(b)`. A cap is not a homomorphism with
respect to combine.

This is not a shortcoming of the implementation. It is what a cap *means*. A cap
on a total is a statement about the total. It is not the same statement as a cap
on each part, and it is not supposed to be.

### 3.3 The impossibility that follows

Now write down the second thing we want:

> **I2 — an aggregate cap exists: a portfolio can carry a transform that limits
> the portfolio's own total.**

We want `I1` because the cache needs it. We want `I2` because that is what an
insurance policy on a division actually is.

Suppose one value had to satisfy both. Node `P` has children summing to 16 and
carries `cap10`. By `I2`, `P`'s value is 10. By `I1`, `P`'s value equals the
combine of its children, which is 16. So `P`'s value is both 10 and 16, which is
a contradiction.

Therefore **no single valuation can satisfy both `I1` and `I2`.** This is
arithmetic, not a design preference. Any proposal claiming to satisfy both is
wrong before it is read.

### 3.4 The move that dissolves it

If one value cannot carry both properties, stop trying to make it. Compute
**two** values. Everything that follows is mechanical once that step is taken.

---

## Part 4 — The ruling: two folds

### 4.1 The two folds

**Raw valuation `r`** — unchanged from Part 2:

```
r(leaf)      = sim(leaf)
r(portfolio) = (+) over r(children)
```

`r` satisfies `I1` at every node, always. It is the cached value. No mitigation
ever touches it.

**Mitigated valuation `m`** — a second fold over the same tree. Its rule adds one
step: combine the children, then apply this node's own transform.

```
m(leaf)      = f_leaf( sim(leaf) )
m(portfolio) = f_P( (+) over m(children) )
```

`f_X` is the composed result-stage transform scoped to node `X`, and is the
identity function when nothing is scoped there.

Two things to notice:

- `m` is a **lawful fold**. One rule, applied uniformly at every node, leaves
  upward. The leaf clause is the same rule with an empty child list: there are no
  children to combine, so the combine degenerates to the node's own simulation
  and the rule reduces to `f_leaf(sim(leaf))`.
- The combine inside `m` is still the same commutative, associative `(+)`. The
  transform sits **outside** the combine, applied to its finished result. Nothing
  was done to the combine itself, so parallel evaluation of children is still
  licensed.

`m` does **not** satisfy `I1`, by design. Wherever the transform binds,
`m(P) != (+) m(children)`. That inequality is the cap doing its job. It is the
honest report that something was removed at this level.

### 4.2 A worked example

One trial. Figures are the loss in that trial.

```
Group                    (portfolio)
|-- Servers              (portfolio)   -- insurance policy: cap the total at 18
|   |-- DiskFailure      (leaf)  raw  9  -- cap this leaf at 6
|   \-- PowerLoss        (leaf)  raw 14
\-- Fraud                (leaf)  raw  3
```

**The raw valuation.** No mitigation enters:

| node | rule | value |
|---|---|---|
| DiskFailure | simulate | 9 |
| PowerLoss | simulate | 14 |
| Servers | `9 (+) 14` | **23** |
| Fraud | simulate | 3 |
| Group | `23 (+) 3` | **26** |

Check `I1`: Servers is 23 and its children sum to 23; Group is 26 and its
children sum to 26. The cache is sound.

**The mitigated valuation.** Same tree, same simulations, second fold:

| node | rule | value |
|---|---|---|
| DiskFailure | `cap6(9)` | 6 |
| PowerLoss | no transform, identity | 14 |
| Servers | `cap18(6 (+) 14)` = `cap18(20)` | **18** |
| Fraud | identity | 3 |
| Group | no transform: `18 (+) 3` | **21** |

Read the Servers row carefully. The children's **mitigated** values were combined
first, giving 20. The cap at 18 was then applied to 20, giving 18. The 2 removed
at that step is Servers' own policy doing its work, and it is called the
**layer** at Servers.

Group carries no transform of its own, so its mitigated value is just the combine
of its children's mitigated values: 21, against a raw 26. The benefit of both
policies has flowed to the top of the tree. Someone reading Group's figures sees
the effect of a policy written two levels below.

### 4.3 Why the children must be combined before the cap

It is tempting to apply a portfolio's cap to the portfolio's *raw* aggregate.
That gives a different and wrong answer. Take a different trial of the same tree:

```
DiskFailure raw 20  (capped at 6)
PowerLoss   raw  4
Servers     raw 24  (capped at 18)
```

Fold the mitigated children, which is the rule:

```
m(Servers) = cap18( cap6(20) (+) 4 ) = cap18( 6 (+) 4 ) = cap18(10) = 10
```

Apply the cap to the raw aggregate instead:

```
cap18( r(Servers) ) = cap18(24) = 18
```

10 against 18. The correct answer is 10. Capping the raw total at 18 never
notices that DiskFailure had already been pulled from 20 down to 6, so it reports
a loss of 18 where the policies together leave only 10. Only folding the
mitigated children attributes each reduction to the level where it actually
happened.

This is why `m` must be a separate fold and not a decoration applied to `r`.

### 4.4 The options that were rejected

**Leaf-only.** Portfolios never carry a transform; `m(P) = (+) m(children)`
always. Sound and simplest. Its cost: an aggregate stop-loss cannot be expressed
at all. "Cap Servers' total at 18" has no representation, and approximating it by
capping each leaf gives a different number, as Part 3.2 shows.

**Terminal projection.** The transform applies to the node's raw aggregate and is
shown at that node only: `m(P) = f_P(r(P))`. It does not fold into ancestors.
Also sound, and the raw fold stays pristine. Its cost is visible in the example:
Servers would read 18, but Group would read `23 (+) 3 = 26`, exactly as if no
policy existed. The money the policy saves disappears one level up, which is
wrong for an insurance payout that genuinely reduces the loss borne by whoever
owns the parent.

**Mutating the canonical aggregate.** Store `f_P(sum)` as Servers' aggregate
inside its `RiskResultGroup`. This breaks `I1` directly, therefore breaks the
cache, and requires a hole in `RiskResultGroup`'s private constructor. It is the
contradiction of Part 3.3 written as code.

**The compositional decorated fold — ruled.** `m(P) = f_P((+) m(children))`. It
is the only option that keeps the raw fold pristine, expresses a genuine
aggregate cap, and lets the benefit propagate upward.

---

## Part 5 — What type the mitigated value must have

### 5.1 It cannot be a RiskResultGroup

Recall from Part 2 what a `RiskResultGroup` is: a value carrying an enforced
claim, *my aggregate is the combine of my children*.

Ask whether the mitigated value of a transformed portfolio can truthfully make
that claim. In the Part 4.2 example, `m(Servers) = 18` while the combine of its
children's mitigated values is 20. The claim is false.

Part 3.2 guarantees this happens whenever the transform binds, for any
non-homomorphic transform, which is every interesting one. So the mitigated value
of a transformed node **cannot** be a `RiskResultGroup` without lying. Making it
one would either require weakening the constructor, which is the rejected
mutation option, or would produce a value that asserts something false.

### 5.2 What that does and does not settle

It settles that the value is not a group. It does **not** by itself settle what
the value *is*. Saying "it is a plain outcomes value" names one thing that is not
a group; it does not show that it is the only thing that is not a group.

That distinction is what Part 6 is about.

---

## Part 6 — What the type failed to say

The rule the fold implements is `m(P) = f_P((+) m(children))`. A value that
carries only outcomes and a node id expresses none of it. Three facts are needed
to read such a value, and a bare result carries no trace of any of them:

**Whether a transform was applied here.** In an implementation that returns the
group when nothing is scoped and a bare result when something is, this fact is
encoded only as a difference in *shape*. That encoding is not reliable, because a
leaf is also shapeless in the same way. Given a bare result you cannot tell
whether it is an unmitigated leaf, a mitigated leaf, or a transformed portfolio.

**What the transform was.** The layer — the difference between `m(P)` and
`(+) m(children)` — is the economically meaningful quantity, the amount the
policy removed at this level. Without it recorded, it is recoverable only by
requesting the children separately and subtracting.

**That the value came from the mitigated fold at all.** Nothing distinguishes it
from a raw value.

The numbers are correct throughout. This is entirely about what the value
communicates about itself.

---

## Part 7 — The identity, and the anomaly that exposed the gap

### 7.1 The transform algebra has an identity

The result-stage transforms at one node compose in precedence order. Composition
of transforms is a monoid and its identity element is the no-op transform. In a
value, the natural representation of "what was applied here" is a list of
records, and the identity of that list is the **empty list**:

```
run(Nil, outcomes) = outcomes
```

### 7.2 An implementation that branches instead of using the identity

An implementation that asks "is a transform scoped here?" and takes different
code paths for yes and no is not using the identity. It is treating the absence
of a transform as a different case rather than as the neutral case of one uniform
rule.

A catamorphism is one rule applied uniformly at every node. A fold that routes
the no-transform case into a different constructor is uniform in its *numbers* —
applying the identity changes nothing — but not in its *structure*.

### 7.3 The anomaly, demonstrated

This is not theoretical. `ScaleLosses` takes a non-negative factor, so a factor
of 1.0 is a valid, constructible mitigation. `ApplyDeductible` takes a
non-negative amount, so a deductible of 0 is valid. `FilterBelowThreshold(0)`
filters nothing. Each is a legal single-step result-stage pipeline, and
`Mitigation.create` accepts it, because the only pipeline rules are a step count
between 1 and 10 and, for the insurance-policy pair alone, that the cap exceeds
the deductible.

So: scope a portfolio with a mitigation whose transform is `ScaleLosses(1.0)`.
Every figure is identical to the figure without it. Under a branching
implementation the returned value changes from a group to a bare result and its
children disappear.

**A transform that does nothing changes the type of the answer.** That is the
concrete form of the gap in Part 6: the rule is not expressed in the type, so the
type ends up expressing something else — a trace of how the computation branched.

---

## Part 8 — ValuationResult

### 8.1 The decision

The mitigated fold returns, at **every** node it visits, a value that carries
what was applied there, with the empty list as the identity. Nothing is special
about the no-transform case: it is the identity instance of one uniform rule.

### 8.2 The type

```scala
final case class ValuationResult private (
  override val nodeId: NodeId,
  source: LossDistribution,
  applied: List[MitigationApplicationRecord],
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes)
```

The name is deliberately not "MitigatedResult". Under this design the common case
is a value with nothing applied, and a type named for mitigation would misdescribe
it. The type is the result of the valuation fold together with the record of what
was applied at that node, where "nothing" is a legal and frequent answer.

The three fields are the rule written as data:

- `source` is `(+) m(children)` — the value before this node's own transform;
- `applied` is `f_P`, as records naming the mitigations and carrying their specs;
- `trialOutcomes` is the result of applying `applied` to `source`.

The layer at a node is `trialOutcomes` against `source.trialOutcomes` — a local
subtraction, with no second request.

A new subtype costs very little, because `LossDistribution`'s useful members are
`final` on the base class and all derive from `trialOutcomes`. `probOfExceedance`,
`maxLoss`, `minLoss`, `nTrials`, `outcomes` and `outcomeCount` are inherited
unchanged, so every consumer of figures is unaffected.

It needs a smart constructor returning `Validation`, for one real reason:
`ScaleLosses` takes a factor that may exceed 1, so applying a transform can
overflow. `RiskResultGroup.create` already converts that arithmetic overflow into
a `ValidationError`, and this mirrors it.

### 8.3 The construction algorithm

```
recordsFor(node)  =  the MitigationApplicationRecords for the result-stage
                     mitigations scoping this node, in precedence order

run(records, outcomes) = the composed transform applied to outcomes
                         run(Nil, outcomes) = outcomes          -- the identity

decorate(id, source, records) =
    ValuationResult(id, source, records, run(records, source.trialOutcomes))

m(leaf) =
    let raw = cachedSimulation(effectiveLeaf)        -- RiskResult, from the cache
    in  decorate(leaf.id, raw, recordsFor(leaf))

m(portfolio P) =
    let kids     = P.children.map(m)
        combined = RiskResultGroup.create(P.id, kids*)   -- a TRUE group
    in  decorate(P.id, combined, recordsFor(P))
```

Notice what the portfolio case does. `RiskResultGroup.create(P.id, kids*)` builds
a group whose children are the **mitigated** children and whose aggregate is the
combine of exactly those children. That group's claim is true. No lying group, no
constructor change. The transform is then applied on top, by the wrapper, outside
the group.

**A required implementation constraint.** `run(Nil, outcomes)` must return the
*same* `TrialOutcomes` reference, not a structurally equal rebuild. If it
rebuilds, every untransformed node allocates a second copy of its outcome map,
and on a large tree that doubles the memory of a resolution for no benefit. The
obvious implementation — compose the pipeline, then run it — gets this wrong, so
it has to be written down.

### 8.4 The same example, with types

One trial, the tree from Part 4.2, resolved under a selection that turns both
mitigations on:

| node | what is built | outcomes |
|---|---|---|
| DiskFailure | cache read gives `RiskResult` 9; records `[cap6]` | `ValuationResult(source = RiskResult 9, applied = [cap6], outcomes = 6)` |
| PowerLoss | cache read gives `RiskResult` 14; no records | `ValuationResult(source = RiskResult 14, applied = [], outcomes = 14)` |
| Servers | `RiskResultGroup.create(Servers, [6, 14])` gives a true group of 20; records `[cap18]` | `ValuationResult(source = group 20, applied = [cap18], outcomes = 18)` |
| Fraud | cache read gives 3; no records | `ValuationResult(source = RiskResult 3, applied = [], outcomes = 3)` |
| Group | `RiskResultGroup.create(Group, [18, 3])` gives a true group of 21; no records | `ValuationResult(source = group 21, applied = [], outcomes = 21)` |

Read the Servers row against the raw figure of 23. Its source, the combine of its
mitigated children, is 20. Its own outcomes are 18. The 3 between 23 and 20 is
DiskFailure's leaf cap, visible inside `source`'s children. The 2 between 20 and
18 is Servers' own policy, and it is a local subtraction on one value.

Every group in that table is honest: 20 really is 6 + 14, and 21 really is
18 + 3. `RiskResultGroup`'s constructor is untouched.

### 8.5 The raw valuation is the identity instance

There is no separate raw method. A raw reading is the same fold with nothing
scoped anywhere — the identity fed in at every node. Every `applied` list is
empty, every `trialOutcomes` equals its `source`, and the figures are exactly
today's figures.

This is why the design needs one function rather than two. A separate raw method
would take the no-mitigation case out of the identity and handle it with separate
machinery, which is the same non-uniformity Part 7 removed, relocated from the
node level to the method level.

It also means a mitigated reading in which nothing bound is indistinguishable
from a raw reading. That is correct rather than a gap: if nothing was applied
anywhere, the mitigated valuation *is* the raw valuation. Two equal things should
be indistinguishable, and a design that distinguished them would be adding noise
rather than information.

---

## Part 9 — Interaction with the cache: none

Three facts fix this completely.

**Only leaves are cached.** The entry is identity-free content keyed by a hash of
the leaf's simulation-relevant parameters. Portfolios are never cached; a
portfolio is rebuilt by combining its children on every read.

**Parameter-stage transforms go in before the hash.** The effective tree is built
by rewriting the scoped leaves, and the content hash is computed from the
rewritten tree. So a parameter-stage mitigation produces a different key and
misses naturally.

**Result-stage transforms go in after the cache read**, and are never stored.

`ValuationResult` is built from result-stage records only, so it is constructed
strictly **above** the cache boundary. It never enters the cache, never appears in
a cache key, and changes nothing about the key projection.

The read path resolves twice — once with nothing selected and once with the
caller's selection — and feeds both into one curve generation so the two series
share an x-axis. Both resolutions read the same leaf cache entries wherever the
leaf content is identical, which is precisely where no parameter-stage transform
touched that leaf. The wrapper does not disturb that, because it is applied after
the cache read.

---

## Part 10 — Consequences

### 10.1 The provenance record gets a home

`MitigationApplicationRecord` — which mitigation, its spec, the node set it
touched, its precedence — was implemented and tested but attached to nothing, and
its own documentation claimed it travelled in responses, which it did not. It is
the `applied` field. The mitigated fold produces at each node a value together
with an account of how that value was produced; the account is no longer
discarded.

### 10.2 A test assertion is strengthened

The shipped guard for the unmitigated read asserted that the returned value was
still a group, inferring from an uncollapsed type that no transform had run.
Under this design the intent is asserted directly:

```scala
none.applied.isEmpty,                        // nothing was applied - stated, not inferred
none.source.isInstanceOf[RiskResultGroup],   // the aggregate underneath is a true group
none.outcomes == raw.outcomes
```

The old assertion infers the property; the new one states it.

### 10.3 The ripple is small

`LossDistribution` is a sealed hierarchy, so in principle every match over it must
handle a new case. In practice there is exactly one such match in production
code, in the resolver's provenance walk, plus a handful of test sites. The browser
module does not reference `LossDistribution` at all, so there is no Scala.js
ripple. The wire shape is unchanged, because the response carries a generated
curve derived from `trialOutcomes`.

---

## Part 11 — What is settled, and what is not

**Settled.**

- The mitigated valuation is a separate fold, `m(P) = f_P((+) m(children))`, with
  the raw fold left pristine and cached.
- The mitigated value of a transformed node is not a `RiskResultGroup`.
- The fold returns a value at every node carrying what was applied there, with
  the empty list as the identity.
- The type is `ValuationResult`.
- One method, not two; a raw reading is the identity instance.
- `run(Nil, outcomes)` returns the same reference.
- The unmitigated-read assertion is rewritten in the strengthened form above.

- `LossDistribution.flatten` is removed from the hierarchy (Part 12).

**Open.**

- Nothing. Every question this design raised has been ruled.

**Not yet done.** This is new design that sits in no existing plan slice. It needs
an implementation-grade section — exact signatures, file inventory, ADR alignment,
verification plan — before any source edit.

---

## Part 12 — The `flatten` question

Adding a third subtype to `LossDistribution` forced a decision about an existing
abstract member, so the reasoning is recorded here with the rest.

### 12.1 What `flatten` was

`LossDistribution` declared one abstract method:

```scala
def flatten: Vector[LossDistribution]
```

A leaf returned `Vector(this)`. A portfolio returned `this` followed by its
immediate children, sorted by node id — one level deep, not recursive. Its
recorded purpose was chart rendering: ask a node for its flattened form and
receive the aggregate first, then the components that make it up, so both can be
drawn together.

### 12.2 Why it had no caller, and could not acquire one

The expand-and-collapse tree in the interface is real and is used. It is not
built on `flatten`.

- The tree view recurses over the **persisted structure** — a portfolio's
  `childIds`, resolved through a lookup — and shows or hides each subtree
  according to whether that node id is in the view state's expanded set. It walks
  `RiskTree`, never a computed value.
- The chart's curves arrive as a map from node id to curve, produced by the
  multi-node read: the client names the nodes it wants and receives one curve per
  name. Drawing an aggregate beside its components is done by asking for the
  parent and the children together.
- The browser never holds a `LossDistribution` at all. The wire type is a
  generated curve, so there is no point on the client where `flatten` could be
  called even if something wanted to.

On the server the read path is handed the set of node ids the caller asked for
and produces one value per id. It never needs to ask a value what lies beneath
it, because the request already said which nodes to compute. So `flatten`'s
question is answered more directly by the node-id list in the request.

The only references anywhere were two assertions in `LossDistributionSpec`,
testing the method and nothing else.

### 12.3 Ruled: remove it

`flatten` is deleted from the hierarchy — the abstract member on
`LossDistribution`, the override on `RiskResult`, the override on
`RiskResultGroup` — and the two spec assertions go with it.

The reason is that it is a mechanism the shipped architecture routes around
rather than a feature waiting to arrive. Adding a subtype is the moment its cost
becomes visible, because the new subtype must define what the method means for a
value that sits on top of another value rather than beside its children. That
cost recurs for every subtype added later, with no consumer on either side of the
wire to repay it.

### 12.4 Considered, NOT ruled: the shape it would take if it returns

**This subsection records a design that was considered and deliberately left
unruled.** It is not a decision. It exists so that if the question reopens,
the reasoning does not have to be rebuilt.

Had `flatten` been kept, the shape to use is **one entry per node, carrying
post-transform values**:

```
flatten(v) = v +: childrenOf(v.source)
```

where `childrenOf` yields a group's children and yields nothing for a value that
wraps a leaf result. For a capped portfolio this returns the portfolio's final
value followed by each child's final value — one entry per node, that node's own
valuation, the aggregate first. It preserves the contract the method already had.

Two properties made it the right shape rather than the alternatives:

- **Node ids stay unique in the result.** Any consumer that keys the vector by
  node id — which is how every existing per-node structure in the system is
  keyed — works without special handling.
- **Nothing is lost by omitting the pre-transform value.** `source` is a public
  field, so the value before this node's own transform is one field access away
  from the entry that matters.

The alternative that returns the whole chain, `v +: v.source.flatten`, was
rejected: it makes a transformed node appear twice under the same node id, which
turns a keyed lookup into a collision.

### 12.5 Would we ever need a flatten?

Not under the current architecture. Three changes would bring the need back, and
each is a change to something other than this design:

1. **A response that carries a nested value instead of a per-node map.** Today a
   read returns one curve per requested node id. If a response ever carried a
   subtree in one payload, a traversal over that nested value becomes necessary,
   and something shaped like `flatten` returns with it.
2. **A server-side consumer that needs a whole subtree from one resolved value**
   without being told the node ids. This does not arise today because the tree
   structure is directly available alongside the values, so anything needing a
   subtree walks the tree rather than the value.
3. **Drill-down implemented by decomposing a single returned value locally**
   rather than by requesting the children. This is a user-interface design
   choice, not a constraint: the present choice is to request the children, and
   it is the one the per-node wire shape supports.

If any of those lands, §12.4 is the shape to reach for.
