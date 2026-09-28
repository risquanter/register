# Four candidate type designs for the mitigated value
# !!FB-F WON!! this document is just retained to review the other designs for historical purposes and analysis.

This note records four possible ways to type the value that the mitigation
calculation produces for a node. It also records what has been established
about them so far, and what has been withdrawn after being found wrong.

Nothing here is decided. There is no recommendation in this note, because the
comparison has not been redone since two of the arguments behind the previous
one were withdrawn.

The companion document is `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`. That
document says how the calculation works and is the authority on that. This note
is only about the shape of the types the calculation produces.

---

## 1. Summary

A risk tree has two kinds of node. A leaf is a single risk event. A portfolio
holds other nodes and represents their combined loss.

A mitigation can reduce the loss at a node. When it applies to a portfolio, the
reduced figure for that portfolio is not the sum of its children's reduced
figures. Section 6 shows why, with numbers. Because of that, the value for a node
has to carry three separate figures rather than one, and every one of the four
designs below carries the same three.

What the four designs disagree about is narrow. There is a public type that
everything outside the simulation code receives. There may also be an internal
type that records whether a node was a leaf or a portfolio. The question is
whether those two types refer to each other, and in which direction.

- FB-a: the public type holds a reference to the internal one.
- FB-b: the internal type holds a reference to the public one.
- FB-c: there is no internal type.
- FB-d: both types exist and neither refers to the other.

One fact decides most of it. The internal type has exactly one piece of code
that would read it. That code is in an approved but not yet implemented plan,
`docs/dev/plans/PLAN-PROVENANCE-ENDPOINT.md`. Section 9.1 sets it out.

---

## 2. Terms used in this note

These are defined in the order they depend on each other, so that no term is
used before it is explained.

**Trial.** One run of the Monte Carlo simulation. A simulation runs many trials
and records the loss produced in each one.

**`TrialOutcomes`.** The type holding one node's simulation figures. It is a
trial count together with a map from trial number to loss. It is defined at
`modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala:57`.

**Combining two `TrialOutcomes` values.** Adding the losses trial by trial. If
one node lost 9 in trial 4 and another lost 14 in trial 4, the combination lost
23 in trial 4. This is `TrialOutcomes.combine` at
`modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala:91`.

**Mitigation.** A measure that reduces loss. Two kinds matter here. A
parameter-stage mitigation changes a leaf's input numbers before the simulation
runs. A result-stage mitigation changes the figures after the simulation has
produced them. Only result-stage mitigations create the problem this note is
about.

**Cap.** One kind of result-stage mitigation. A cap of 18 means any loss above
18 is recorded as 18.

**`MitigationApplicationRecord`.** A record saying which mitigation was applied
at a node. It carries the mitigation's identifier and its specification.

**`NodeProvenance`.** A record of how one leaf's random numbers were produced,
kept so that a simulation can be reproduced exactly later.

**Calculating the tree from the bottom up.** The calculation starts at the
leaves and works out a value for each one. It then works out each portfolio's
value from the values of its children. Every node is handled by the same rule.
The formal name for this is a catamorphism. Section 14 gives that background for
anyone who wants it; the plain description is enough for the rest of this note.

**The resolver.** The class `CachedResultResolverLive`, at
`modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`.
It performs the calculation described above, reads cached leaf simulations, and
hands the results to the service layer.

---

## 3. A warning about one name

The word `LossDistribution` means two different things across the two documents.
Confusing them produced a wrong conclusion once already.

In the code as it stands today, and therefore in
`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`, `LossDistribution` is the
sealed class with two subclasses. `RiskResult` is the leaf case and
`RiskResultGroup` is the portfolio case. All four designs below reassign that
name. The two subclasses move under a new parent called `NodeValuation`, or they
disappear entirely, and the name `LossDistribution` is reused for the new flat
public type.

So when `MITIGATION-VALUATION-EXPLAINED.md` §8.2 writes

> `source` is `(+) m(children)` — the value before this node's own transform

and gives `source` the type `LossDistribution`, that sentence means the whole
structure, children included. In the vocabulary of this note it means `source`
is a whole `NodeValuation`. It does not mean the new flat type.

---

## 4. The field names, which are settled

These names are decided and are not open for discussion. All four designs use
them.

`trials` holds the node's figures after its own mitigation step has been
applied. The spelling `trialOutcomes` is permanently out. Where the figures
inside it are needed, the expression is `trials.outcomes`.

`source` holds the figures that the mitigation step was applied to. The
spellings `sourceOutcomes` and `preLayerOutcomes` are out.

`applied` holds the mitigation step itself, as a
`List[MitigationApplicationRecord]`, ordered by precedence.

`nodeId` holds the identifier of the node.

---

## 5. What all four designs agree on

These points are common to all four and are not part of the question.

The public type is called `LossDistribution`. Every piece of code outside the
resolver receives it. It keeps the methods the current type has:
`probOfExceedance`, `maxLoss`, `minLoss`, the loss histogram, `nTrials`,
`outcomes`, and `nodeId`.

It carries `trials`, `source` and `applied`. Because it carries both `trials`
and `source`, the amount that this node's own mitigation removed is obtained by
subtracting one from the other. No second request to the server is needed. That
is the property `MITIGATION-VALUATION-EXPLAINED.md` §8.2 asks for.

There is one code path for building it, not two. The code does not ask whether a
mitigation applies at this node and then take a different branch for yes and no.
When no mitigation applies, `applied` is the empty list, and the same
`TrialOutcomes` object is returned unchanged rather than copied. That second
part is a requirement stated in `MITIGATION-VALUATION-EXPLAINED.md` §8.3. The
reason for having only one path is stated in §7.2 of the same document:

> An implementation that asks "is a transform scoped here?" and takes
> different code paths for yes and no is not using the identity. It is
> treating the absence of a transform as a different case rather than as the
> neutral case of one uniform rule.

The `flatten` method is removed. That was decided separately and has not been
carried out yet, so the method is still in the code.

None of these types is sent to the browser. A separate response type is defined
for that, and none of the types below has any JSON encoding.

---

## 6. Why a portfolio needs two figures rather than one

This section explains the problem the three fields exist to solve. It uses the
example from `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md` §4.2.

The tree, and the figures from one trial:

```
Group                    (portfolio)
|-- Servers              (portfolio)   -- insurance policy: cap the total at 18
|   |-- DiskFailure      (leaf)  raw  9  -- cap this leaf at 6
|   \-- PowerLoss        (leaf)  raw 14
\-- Fraud                (leaf)  raw  3
```

Without any mitigation, a portfolio's loss is the sum of its children's losses.
Servers loses 9 + 14 = 23. Group loses 23 + 3 = 26. This holds at every level of
the tree.

Now apply the two mitigations. DiskFailure is capped at 6, so its 9 becomes 6.
Servers is capped at 18.

The question is what Servers loses. Its children now lose 6 and 14, which sum to
20. Its own cap of 18 then applies to that 20, giving 18.

So Servers has two different correct figures. The sum of its children is 20. The
figure after its own cap is 18. Neither can be dropped. The sum of the children
is needed because that is what the cap is applied to. The figure after the cap is
needed because that is the answer.

The reason this happens is that a cap does not distribute across a sum. Capping
the sum of 8 and 8 at 10 gives 10. Capping each 8 at 10 first and then adding
gives 16. Those are different numbers, so no single figure can be both.

That is why the type has `source` for the sum of the children and `trials` for
the figure after this node's own mitigation. It is also why the children are
combined before the cap is applied rather than after. Section 4.3 of
`MITIGATION-VALUATION-EXPLAINED.md` gives a case where doing it the other way
produces 18 when the correct answer is 10.

The same document states the consequence as a constraint on the type, in §5.1:

> the mitigated value of a transformed node **cannot** be a `RiskResultGroup`
> without lying.

Stated plainly: a portfolio with a cap cannot be recorded as a value whose total
is required to equal the sum of its children, because after the cap it no longer
does.

---

## 7. The worked example, as all four designs produce it

Same tree, same single trial.

| node | source | applied | trials |
|---|---|---|---|
| DiskFailure | 9 | `[cap6]` | 6 |
| PowerLoss | 14 | `[]` | 14 |
| Servers | 20 (= 6 + 14) | `[cap18]` | 18 |
| Fraud | 3 | `[]` | 3 |
| Group | 21 (= 18 + 3) | `[]` | 21 |

Without mitigations the figures are 9, 14, 23, 3 and 26.

In every row the `source` figure is exactly the sum of that node's children's
`trials` figures. In two rows the `trials` figure differs from `source`, and
those are the two rows that have a mitigation in `applied`. All four designs
produce this table identically.

---

## 8. The four designs

### FB-a: the public type holds the internal one

```scala
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  source: TrialOutcomes,
  applied: List[MitigationApplicationRecord],
  private val origin: NodeValuation
) extends LECCurve

private[simulation] sealed trait NodeValuation

private[simulation] final case class RiskResult(
  provenances: List[NodeProvenance]
) extends NodeValuation

private[simulation] final case class RiskResultGroup(
  children: List[LossDistribution]
) extends NodeValuation
```

The two internal cases carry only what the public type does not: the provenance
records at a leaf, and the children at a portfolio.

This arrangement has one consequence worth stating. A class in the package
`simulation` cannot name a type marked `private[cache]` at all. So if
`LossDistribution` holds a `NodeValuation`, both types must live in the same
package, and `private[simulation]` is the most restricted visibility available.

### FB-b: the internal type holds the public one

```scala
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  source: TrialOutcomes,
  applied: List[MitigationApplicationRecord]
) extends LECCurve

private[cache] sealed trait NodeValuation:
  def value: LossDistribution
  final def nodeId: NodeId = value.nodeId

private[cache] final case class RiskResult(
  value: LossDistribution, provenances: List[NodeProvenance]
) extends NodeValuation

private[cache] final case class RiskResultGroup(
  value: LossDistribution, children: List[NodeValuation]
) extends NodeValuation
```

The resolver calculates in terms of `NodeValuation`, then reads the `value`
field out of each one before returning. No code in the package `simulation`
names `NodeValuation`, so it can sit next to the resolver with `private[cache]`
visibility, and no package has to be split.

This is the version currently written into `docs/dev/plans/PLAN-RISKTRANSFORM.md`.

### FB-c: no internal type

```scala
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  source: TrialOutcomes,
  applied: List[MitigationApplicationRecord]
) extends LECCurve
```

Only the public type exists. The provenance records are collected into a
separate `Map[NodeId, List[NodeProvenance]]`, which the resolver returns
alongside the map of values it already returns.

### FB-d: both types exist and neither refers to the other

```scala
// public; names no internal type
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  source: TrialOutcomes,
  applied: List[MitigationApplicationRecord]
) extends LECCurve

// internal; names no public type
private sealed trait NodeValuation
private final case class RiskResult(provenances: List[NodeProvenance]) extends NodeValuation
private final case class RiskResultGroup(children: List[NodeValuation]) extends NodeValuation
```

As it calculates, the resolver keeps a pair of a `LossDistribution` and a
`NodeValuation` for each node. That pair stays inside the resolver and is never
given a type name of its own. The two results it produces are connected only by
the node identifier.

This matches the stated requirement most directly. `RiskResult` and
`RiskResultGroup` share a parent, and `LossDistribution` is not part of that
family.

---

## 9. What has been established

### 9.1 The curve endpoints need no reference between the types; the provenance endpoint does

Taking each thing the code actually has to do:

When calculating a portfolio, the code needs its children's `trials` figures so
it can add them together. It does not need their provenance records, and it does
not need their children.

When answering a request for curves or exceedance probabilities, the code needs
each requested node's value. That is a `Map[NodeId, LossDistribution]`, which the
resolver already builds.

Neither of those requires a value to reach the structure that produced it, or
the reverse. For those two endpoints, the reference in FB-a and FB-b is a choice
rather than a requirement.

That is not the whole picture, and the exception changes the conclusion.
`docs/dev/plans/PLAN-PROVENANCE-ENDPOINT.md` is an approved plan that has not
been implemented yet. Its Phase 2 adds a function that starts from one node's
value and descends through its children, collecting the provenance records of
every leaf beneath it:

```scala
def attributedProvenances(dist: LossDistribution): Map[NodeId, NodeProvenance] =
  dist match
    case r: RiskResult      => r.provenances.map(r.nodeId -> _).toMap
    case g: RiskResultGroup => g.children.map(attributedProvenances).reduceOption(_ ++ _).getOrElse(Map.empty)
```

and calls it on the value the resolver returns:

```scala
(tree, _, _)   <- lookupNodeInTree(wsId, treeId, nodeId, rev)
dist           <- resolver.ensureCached(tree, nodeId, seedEntityId)
attributed      = LossDistribution.attributedProvenances(dist)
```

That function reads the internal structure starting from a value. It is the one
piece of code that needs what FB-a and FB-b provide and FB-c and FB-d do not.

The same plan gives that arrangement as its reason for satisfying ADR-009:

> ADR-009 | Attribution is read through the structure; no flat list on the
> supertype, nothing merged onto the aggregate | Compliant

One further point, about the code as it currently stands. Once `flatten` is
removed, and once a portfolio with a mitigation stops being replaced by a
childless value, no code in the repository reads `children` or `provenances`
from a value. The function `descendantProvenances` at
`modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala:202`
then has no caller left. That is true but incomplete, because the provenance
plan deletes that function and puts `attributedProvenances` in its place. The
same descent through the children still happens. It is renamed, and its result
is keyed by node.

### 9.2 What the internal type actually contains

The two cases hold two different kinds of thing.

`RiskResultGroup(children)` holds the shape of the tree, and the caller already
has that shape. `RiskTree` stores its nodes flat, with `childIds` and `parentId`
fields, and `TreeIndex` builds its map of children directly from those
`childIds`. `MitigationApplication.effectiveTree` rebuilds the tree through
`RiskTree.fromNodes` using the same nodes and the same root, so parameter-stage
mitigations change a leaf's input numbers and never change the shape. The
comment at
`modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala:108`
says the same thing for the unmitigated case: "the input tree revalidated through
`RiskTree.fromNodes` — identical content, identical hashes". So the shape held
inside the internal type is a copy of something the caller already has.

`RiskResult(provenances)` holds the leaf provenance records. Those are not
available anywhere else.

An earlier version of this note described that second half as "a map" and
stopped there. That was misleading, because it reads as though a map were an
alternative to the internal type. It is not. Under the provenance plan the map
is the result of descending through the internal type, and
`Map[NodeId, NodeProvenance]` is that endpoint's response shape rather than a
statement about where the records are held during the calculation. Under FB-c or
FB-d the resolver would have to produce that map directly, as a second result
alongside the curves. That is possible, because the calculation visits every leaf
in any case. It does change the method signature that the approved plan already
fixes, and it removes the reason that plan gives for satisfying ADR-009. It is a
cost to weigh, not a free observation.

### 9.3 One change in behaviour that all four share

Today a portfolio's trial count is taken from configuration. The code reads
`TrialOutcomes(cfg.defaultNTrials, merge(...))` at
`modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala:300`.
Building the portfolio's figures by combining the children instead takes the
trial count from the children. In practice the number is the same, because the
resolver builds everything under one `SimulationConfig`. It is a different source
for that number, so it needs a test.

---

## 10. Three arguments that were withdrawn

These three were offered as differences between FB-a and FB-b. All three are
wrong. They are recorded here so they are not raised again.

### 10.1 Withdrawn: "FB-a lets a caller read a node's children"

In FB-a the field is declared `private val origin`. A caller holding that value
cannot read it. The data is present in memory, but no method returns it. No
caller can descend through the children in either design.

The real question behind the argument is this. Servers shows 23 without
mitigations and 18 with them. Where did the difference of 5 come from?

Two of it came from Servers' own cap. That is `source` minus `trials`, which is
20 minus 18. It is one subtraction on the node itself, and it works in all four
designs.

Three of it came from DiskFailure's cap. That is 9 minus 6, computed on
DiskFailure's own value.

How a caller obtains the 3:

```
FB-b, FB-c, FB-d: the existing multi-node request already returns it
    ensureCachedAll(tree, {Servers, DiskFailure, PowerLoss}, ...)
      -> Map(Servers -> 18/20/[cap18], DiskFailure -> 6/9/[cap6], PowerLoss -> 14/14/[])
    DiskFailure.source - DiskFailure.trials = 3

FB-a: needs a new public method to be added
    def children: List[LossDistribution]
    servers.children.map(c => c.source - c.trials)   // List(3, 0)
```

The existing request takes a list of node identifiers and returns a value for
each one. The caller already knows the shape of the tree from reading the tree
itself, so it already knows which identifiers to ask for. Descending through a
value is therefore a second route to figures the first route already returns. It
does not separate the designs.

### 10.2 Withdrawn: "FB-b allows a portfolio whose recorded total contradicts its children, and FB-a does not"

In FB-b the portfolio case holds the total and the children together:

```scala
RiskResultGroup(value = LossDistribution(Servers, trials = 18, source = 999, ...), children = ...)
```

A `source` of 999 next to children summing to 20 is a value the compiler will
accept. Only the private constructor stops it being built.

FB-a has the same property, one line higher up:

```scala
LossDistribution(Servers, trials = 18, source = 999, applied = [cap18], RiskResultGroup(children))
//                                     ^^^^^^^^^^^                      ^^^^^^^^^^^^^^^^^^^^^^^
//                                     the total and the children, in the same constructor call
```

Both designs allow the contradictory value to be expressed, and both rely on the
private constructor to prevent it being built. There is no difference between
them here.

### 10.3 Withdrawn: "the reasoning document is equally far from FB-a and FB-b"

This rested on the two meanings of `LossDistribution` described in section 3.
Read correctly, that document's `source` is a whole `NodeValuation`, meaning the
child calculation itself rather than a number taken out of it. All four designs
reduce it to a `TrialOutcomes`.

The four are equally far from the document on this point, so the comparison
between FB-a and FB-b is unaffected. The distance itself is real, though, and
was described as absent. It should be recorded as a deliberate narrowing. What
it gives up is the route described in section 10.1, which the existing
multi-node request already covers.

---

## 11. What separates the four

With the three arguments in section 10 withdrawn, the differences are these.

**FB-a.** There is one public type, and nothing has to be read out of a wrapper
before it is returned. Against that, `LossDistribution` is a case class, so the
compiler generates `equals`, `hashCode`, `toString` and `productElement` over
every field, including `origin`. Comparing two root values with `==` therefore
compares every loss map in the whole tree, and `productElement(4)` hands the
internal type out typed as `Any`. Avoiding either means giving up the generated
case class methods. The size of the public type is also unbounded. A leaf's value
is four small fields; the root's value holds the entire tree beneath it, so
holding one value keeps all of it in memory. Finally, `NodeValuation` has to live
in the package `simulation`, because a class there cannot name a `private[cache]`
type.

**FB-b.** There is a name for the node's own figures on their own, which is what
every caller and the response type want. The public type is small, and comparing
two of them compares only that node. The cost is that the resolver reads the
`value` field out of each result before returning, and that there are two types
where one was pictured. The internal type stays `private[cache]`, and no package
has to be split.

**FB-c.** This is the smallest arrangement: one type, and no visibility question.
It drops the internal family that the stated requirement asked to keep. It also
contradicts `docs/dev/plans/PLAN-PROVENANCE-ENDPOINT.md` Phase 2 directly, since
that plan's `attributedProvenances` matches on `RiskResult` and
`RiskResultGroup`, and its ADR-009 entry gives reading through the structure as
the reason it is compliant. Choosing FB-c means reopening that plan.

**FB-d.** It matches the stated requirement literally. The two cases share a
parent, and `LossDistribution` is outside that family. Neither type constrains
the other's visibility or size. Against that, the two results are connected only
by the node identifier, which is a weaker connection than a direct reference and
has to be kept consistent by the calculation. And per section 9.2, most of what
the internal type holds is a copy of the tree.

---

## 12. The question that decides it

The internal family has exactly one piece of code that would read it:
`attributedProvenances` in `docs/dev/plans/PLAN-PROVENANCE-ENDPOINT.md` Phase 2.
That function reaches a node's leaf-descendant provenance records by descending
through the structure attached to the value the resolver returns. Nothing else in
the repository, and nothing in any other approved plan, reads it.

So the question is not whether the internal family is used at all. It is this: is
one approved but unimplemented endpoint reason enough to keep a reference from
the value to the structure, or should that endpoint instead be rewritten to
accept a map that the calculation can produce anyway?

FB-a and FB-b both keep that endpoint working as written, though FB-a needs a
public method added to get past `private val origin`. FB-b is the closer fit of
the two, because the function is already written over the internal type, so the
endpoint would take a `NodeValuation` rather than a `LossDistribution`.

FB-c and FB-d both require the resolver to produce `Map[NodeId, NodeProvenance]`
as a second result. That changes the service method signature the plan fixes, and
removes the reason it gives for satisfying ADR-009.

I am not recording a recommendation. Two of the three arguments behind the
previous one are withdrawn in section 10, the constraint in section 9.1 was
missed when that recommendation was made, and the comparison has not been redone
since.

---

## 13. Open items

1. Where the `LossDistribution.merge` function belongs, once `LossDistribution`
   is no longer the parent class.
2. Whether `LossDistribution` exposes the structure at all, meaning public
   methods returning the children or the provenance records. This applies only
   to FB-a and FB-b.
3. What an equality comparison on `LossDistribution` should compare. This
   matters most under FB-a, where the generated comparison descends through the
   whole tree.
4. Whether the `origin` field exists at all. It is present in FB-a and absent in
   the other three, and has not been decided either way.
5. How this interacts with `docs/dev/plans/PLAN-PROVENANCE-ENDPOINT.md`. That
   plan is approved and not implemented. Its file list already includes
   `modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`,
   and its Phase 2 code is written against the class hierarchy as it is today.
   All four designs break that code. FB-a and FB-b break it because the two cases
   move and change shape. FB-c and FB-d break it because there is nothing left to
   descend through. Whichever design is chosen, that plan needs amending. Under
   FB-c and FB-d the amendment changes its service method signature, rather than
   only its internals.
6. That plan's own open question is affected too. It asks whether the provenance
   endpoint should ever accept a mitigation selection, and it records that a
   portfolio with a mitigation currently loses its children, which breaks the
   assumption of one record per key. Under all four designs a portfolio keeps its
   children, so that obstacle disappears.

---

## 14. Optional background: the standard names for this structure

This section is reference material. Nothing above depends on it.

The calculation described in section 2 as "calculating the tree from the bottom
up" is called a catamorphism, or a fold. Its defining property is that one rule
is applied at every node, and a parent's result is computed only from its
children's results.

What the calculation produces is a value at every node, together with the shape
of the tree beneath it. That combination is written
`Cofree f a = (a, f (Cofree f a))` in the usual notation, where `a` is the value
at a node and `f` describes the shape. Here `a` is the flat `LossDistribution`,
and `f` has two cases: a leaf carrying a list of `NodeProvenance`, and a
portfolio carrying a list of children.

FB-a and FB-b are both that structure, and they contain exactly the same
information as each other. The only difference is which of the two halves gets
the public name. FB-c keeps the value half and drops the shape half. FB-d keeps
both halves but stops either one from referring to the other.

The reason given in section 6 for needing two figures has a standard name as
well. Adding losses trial by trial forms a commutative monoid, meaning the
operation is associative, is commutative, and has a zero element. Without
mitigations, a portfolio's value equals the combination of its children's values
at every level, which makes the calculation a monoid homomorphism. A cap is not a
homomorphism, which is what the two 8s in section 6 demonstrate. The three-field
design keeps the homomorphism property on `source`, which is where the
content-addressed cache relies on it, and allows `trials` to break it, which is
what a cap on a total requires.
