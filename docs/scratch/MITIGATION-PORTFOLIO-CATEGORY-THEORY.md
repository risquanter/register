# Mitigation composition and portfolios — the category-theory derivation

**Recovered 2026-09-14 from session history** (session
`4b8c339d-b23b-4fec-b98b-c34e0a831354`, exchanges dated late August 2026). This
derivation is the reasoning behind the Option F ruling recorded in
PLAN-RISKTRANSFORM 8.14 and behind ADR-034. Only its conclusions were ever
written down; the derivation itself existed nowhere in the repository until
this file. Nothing here is new design — it is the existing argument, restored.

## How the question arose

An earlier presentation offered an option (later "Option A") in which a
portfolio's result-stage transform was written into the canonical aggregate.
The user rejected it: "a disgusting aberration against all category theory
rules we have", and asked for a re-evaluation on category-theory soundness
grounds, explicitly including the possibility of not supporting portfolio
mitigation at all.

The user's own statement of the requirement, which the final option had to
satisfy:

> If I apply a mitigation to a child node, then of course the aggregate is
> simply not the same. It is partially mitigated irrespective of how this is
> displayed; one thing is sure, it should not be implied it is the same
> aggregate just recomputed. Also I find it normal to have a capping mitigation
> applied to a portfolio — it is the correct way to represent the working of
> various insurance policies. But this is simply not the right mechanics. It
> must stay informative that the mitigation was applied to the same aggregate.

## The two structures

**Monoid.** A set with an associative combine and an identity. `TrialOutcomes`
under per-trial sum is a *commutative* monoid: order and grouping do not
matter, identity is all-zeros.

**Catamorphism.** A fold: collapse a tree to a value by giving one rule for how
to combine a node's children's results. The **raw valuation** `r` is the fold
whose rule is plain combine:

```
r(leaf) = sim(leaf)
r(P)    = (+) r(children)
```

Every node equals the sum of its children. This fold invariant is what the
content-addressed cache relies on.

## The impossibility that forces two valuations

A result-stage transform `f` (a cap, a deductible) is **not a monoid
homomorphism**: `f(a (+) b) != f(a) (+) f(b)`.

Worked case — cap at 10, two trials of 8 each:

```
f(8 + 8) = f(16) = 10
f(8) + f(8) = 10 + 10 = 16
```

Because `f` does not distribute over the combine, you cannot simultaneously
hold both:

- **I1** — every node equals the sum of its children (the cache's invariant);
- **I2** — a genuine aggregate cap exists.

That is a mathematical fact, not a design flaw. It is the whole reason
PLAN-MONOID B4 concluded a portfolio cap "must be a separate endomorphism
applied after `combineAll`", explicitly outside the monoid.

The resolution is **two valuations, not one corrupted one**:

- **Raw valuation `r`** — the pristine commutative-monoid fold.
  `RiskResultGroup`'s private constructor keeps it honest by construction:
  `r(P) = (+) children`, always. Untouched; the cache stays valid.
- **Mitigated valuation `m`** — a *separate* fold over the same tree, each
  node's rule being "combine the children, then apply this node's transform":

```
m(leaf) = f_leaf(sim(leaf))
m(P)    = f_P((+) m(children))
```

This is a lawful catamorphism: the inner combine is still
commutative-associative, and `f_P` is a fixed pipeline ordered by precedence.
At a capped node `m(P) != (+) m(children)` — and that is correct and honest. A
capped aggregate is *supposed* to differ from the naive sum, and it never
claims otherwise, because it is carried as the mitigated series beside the raw
one.

## The sentence that settles the value's type

Quoted from the recovered analysis:

> The key point that dissolves the 8.14 "collision": the mitigated value of a
> capped portfolio is **not** a `RiskResultGroup` claiming to be a sum. It is a
> plain transformed-outcomes value. So there is no lying group, no
> private-constructor breach, and no need for the 9 ADR exception Option A
> required. The type system stays exactly as strict as it is now.

So the flat `RiskResult` produced by `CachedResultResolverLive`'s portfolio arm
is the derived and ruled outcome, not an implementation shortcut.

## The options as they were presented

- **Option D — leaf-only.** Portfolios never carry `f`; `m(P) = (+) m(children)`
  always. Sound and simplest, but an aggregate stop-loss cannot be expressed at
  all, because capping each leaf is not capping the sum.
- **Option E — terminal projection.** `f_P` applies to P's raw aggregate and is
  shown at P only: `m(P) = f_P(r(P))`. It does not fold into ancestors. Sound,
  but the money saved by the cap vanishes one level up, which is economically
  wrong for a real policy.
- **Option F — compositional decorated fold. RULED.**
  `m(P) = f_P((+) m(children))`. Caps propagate upward and stack with descendant
  mitigations; the raw valuation stays pristine and separate; no domain-type
  change is needed.
- **Option A — canonical aggregate mutation. WITHDRAWN.** It made the raw
  `RiskResultGroup` carry `f_P(sum) != sum(children)`, breaking the fold
  invariant and the cache, and needing a hole in the private-constructor rule.

Option F's worked example, as presented: cap "Servers" at $10M against children
summing to $14M. Look at Servers and see $10M with raw $14M beside it. Look at
Servers' parent and its mitigated total reflects the capped $10M, so the
policy's benefit flows up. Drill into Servers' children under the mitigated
view and they sum to $14M, not $10M — and the view labels this as the cap doing
its job rather than pretending nothing happened.

That last clause is the intended drill-down behaviour: the children are read
separately and the discrepancy is shown, not hidden.

## Two things this recovery surfaces

1. **ADR-034 is missing the type conclusion.** It carries the two valuations,
   the non-homomorphism, and the worked example, but not the sentence that the
   mitigated value of a transformed portfolio is deliberately a flat value and
   deliberately not a group. Its Decision 4 wording ("drilling decomposes ...
   stays visible") reads as a promise about one response value, when the
   derivation means reading the children separately.
2. **A standing directive was proposed and never placed.** The user said "from
   now on every assessment needs strict review from category theory
   perspective". Proposed wording for `~/.claude/CLAUDE.md`, offered at the time
   and still not present there:
   > Every design assessment, review, or recommendation must include an explicit
   > category-theory soundness check — name the algebraic structure in play
   > (monoid, fold/catamorphism, homomorphism) and show the operation preserves
   > its laws, before recommending it.
   It is a cross-project guardrail, so it belongs in the global file and must be
   placed by the user.
