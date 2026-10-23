# PLAN-LOSS-SHAPE-ALGEBRA — the loss distribution shape and the transforms over it, derived

**Plan code `LSA`.** Decisions in this document are labelled `LSA-D-1` upward.

**Status: derivation complete, all five decisions ruled, inventory and token in
place, awaiting the accepted signal on this document.** The replacement type is
derived and its signatures are written out below. `LSA-D-1` to `LSA-D-5` are
ruled (2026-10-22), with their consequences written into §4.5, §5.6, §6.6, §8,
§9.1, §12, §13 and §14. `PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md` exists beside this
document and carries all 25 paths as bullet lines, and
`.claude/protocol/approved` names this plan. What remains before any source edit
is the accepted signal on this document.

**Two things the plan-gate pass added, both from checking a claim rather than
from the derivation.** §4.5 inventories every rule across all five current rule
sites and records which variant survives, because collapsing five sets to one is
where a rule gets lost silently; it found that the term count's lower bound is
enforced in one site out of five and that the value it admits throws inside the
fitter. §9.1 records that a leaf may carry the other mode's fields today, which
is what `LSA-D-5` rules on.

**One presentation is owed to another document.** §5.6 deletes
`NodeProvenance.distributionType`, which makes a verbatim quotation in
[`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) stale. That plan is
approved and not yet implemented, so this plan does not edit it; the amendment is
presented to the user separately.

**Order.** `LSA-D-4` ruled that this plan runs before `PLAN-RISKTRANSFORM`'s M4
and as soon as `LSA-D-2` closes, subject to one working-tree condition recorded
with that ruling: `PLAN-ERROR-WIRE-BOUNDARY`'s Phase 3a steps 1–2 are committed
first, because two of the files they leave uncommitted are files this plan
rewrites.

**What this plan is for.** `EWB-D-40` in
[`PLAN-ERROR-WIRE-BOUNDARY.md`](PLAN-ERROR-WIRE-BOUNDARY.md) ruled that the
loss-distribution shape is declared in three places, that the shape change is
its own plan, and that the replacement type must be *derived* rather than
chosen. This is that derivation, extended — as the same ruling required — to
cover every type the distribution-override perspective introduced. The
foundation it builds on is
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md),
which derives the mitigated value's type from the monoid and catamorphism laws,
with
[`docs/scratch/MITIGATION-PORTFOLIO-CATEGORY-THEORY.md`](../../scratch/MITIGATION-PORTFOLIO-CATEGORY-THEORY.md)
holding the recovered argument behind the two-fold ruling.

**Two corrections to `EWB-D-40`'s own text, both of my own earlier findings.**
Neither changes that ruling's three parts.

1. It says the two shape rule-sets "agree on every refusal's location and differ
   in error code and message text". They also differ in **which rules exist**:
   `Distribution.create` checks that percentile values lie in the open interval
   from zero to one, that quantile values are positive, and that both sequences
   strictly increase (`Distribution.scala:65-80`), and
   `RiskNode.validateExpertMode` checks none of those (`RiskNode.scala:226-285`).
   §4.4 below gives the defect that follows.
2. It says the change "alters a persisted shape and the leaf's wire
   representation". It need not. §9 shows the domain type can become a coproduct
   with every serialized byte unchanged — no stored-tree migration, no cache-key
   change. It remains a guarded modification because it changes public
   signatures, so the echo and halt gates still bind.

---

## 1. Part 1 — the vocabulary, in dependency order

The explanation document this plan extends builds its vocabulary before its
argument. The same is done here, and the terms are different ones: that document
needed the algebra of *combining values*, and this one needs the algebra of
*representing inputs*. Nothing below is assumed known.

### 1.1 Product

A **product** of two types is a type holding one value of each. `(Int, String)`
is the product of `Int` and `String`. A `case class` with several fields is a
product of its fields' types. Its inhabitant count is the product of theirs: a
`case class` of two `Boolean`s has 2 × 2 = 4 inhabitants.

### 1.2 Coproduct

A **coproduct**, also called a sum, of two types is a type holding one value of
*either*, tagged with which. A Scala `sealed trait` with two `case class`es is a
coproduct of those two cases. Its inhabitant count is the **sum of what the cases
hold**, not the count of the cases:

```scala
sealed trait T
case class A(b: Boolean) extends T
case class B(b: Boolean) extends T
```

has 2 + 2 = 4 inhabitants — `A(true)`, `A(false)`, `B(true)`, `B(false)` — and
each one carries exactly one `Boolean`, never two and never none. The count is 4
rather than 2 because `A` wraps a `Boolean`, which has two inhabitants, and so
does `B`. Two cases holding nothing give the other count:

```scala
sealed trait T
case object A extends T
case object B extends T
```

Each case here holds `Unit`, which has one inhabitant, so the sum is 1 + 1 = 2.
That is also the inhabitant count of a two-case `enum` with no parameters.

The general rule, used throughout §3.3: a case wrapping a type `X` contributes
`|X|` inhabitants, so `|Unit| = 1`, `|Boolean| = 2`, and `|Option[X]| = 1 + |X|`
because `None` is one value and each `Some(x)` is one more.

Both products and coproducts are primitive in Scala 3. Neither has to be encoded
in terms of the other.

### 1.3 Representable against meaningful

A type's inhabitants are what it can **represent**. The subset of those that the
domain gives a meaning to are the **meaningful** ones. When the two sets differ,
every function reading the type has to decide what to do with the difference, and
the decision is taken independently at each reading site.

A validation rule is a predicate that carves the meaningful subset out of the
representable one. So the count of rules over a type is a measure of how far the
two sets have been allowed to diverge. A rule is better **unwritable** than
checked: a rule that cannot be expressed cannot be forgotten at a new call site.

### 1.4 Total and partial interpretation

A type's meaning is given by the function that interprets it. Call that function
`interpret`. It is **total** when it produces an answer for every inhabitant, and
**partial** when some inhabitants have no answer and it must refuse.

This is the test used throughout this plan: **a shape type is correct exactly
when its interpretation into a sampler is total, up to failures of the numerical
fit itself.** A partial `interpret` whose refusals are about *which fields are
populated* is the signature of a type that over-represents, and the refusals are
the carving predicates of §1.3 showing up at the far end of the system.

### 1.5 Endomorphism, identity, absorbing element

An **endomorphism** on a type is a function from that type to itself. The
endomorphisms on one type compose, composition is associative because function
composition always is, and the identity function is the neutral element — so they
form a **monoid**, which is the structure the explanation document's Part 1.3
defines.

An **absorbing element** of a monoid is an element `z` with `z ∘ x = z` for every
`x`: composing anything before it changes nothing, because it discards its input.
A constant function is the absorbing element of an endomorphism monoid. §6.3 shows
this is exactly what "absolute replacement" means, which is why the override
transform must carry a *whole* shape.

### 1.6 Kleisli composition

When each step can fail, a step is not `A => A` but `A => F[A]` for an effect `F`
— here `Validation[ValidationError, A]`. Two such steps compose through `flatMap`
rather than `andThen`, and that composition is called **Kleisli composition**:

```scala
def andThenK[A, B, C](f: A => F[B], g: B => F[C]): A => F[C] =
  a => f(a).flatMap(g)
```

`flatMap` is what makes this typecheck, and it is also what fixes the arrows'
shape: `f(a)` is an `F[B]`, and `flatMap` hands `g` the plain `B` inside it. So
no arrow in this structure ever takes a wrapped value as input, and the identity
must have the same shape the arrows have — `A => F[A]`, which is
`a => Validation.succeed(a)`, not a function from `F[A]`. Composition stays
associative and that arrow is its neutral element by the two monad identity laws,
so the arrows on one fixed type form a monoid. Its failure behaviour is fail-fast
rather than accumulating, and that is correct here rather than a compromise: if
step one refuses, step two has no input to work on. §6.4 does this derivation
against the fold that already runs.

---

## 2. Part 2 — what the code declares today

Six types in the shared module declare the same loss-distribution fields. Three
were named in `EWB-D-40`; the search for the construction surface found three
more.

| Type | Declared at | Shape fields | Constructor |
|---|---|---|---|
| `Distribution` | `Distribution.scala:12-19` | all six | **public** `apply`, plus `create` |
| `RiskLeaf` | `RiskNode.scala:88-94` | all six, inline among the leaf's own | `private`, plus `create` and `fromValidated` |
| `OverrideDistributionParams` | `RiskLeafTransform.scala:60-66` | all six | `private`, plus `create` |
| `LeafSimContent` | `LeafSimContent.scala:30-38` | all six | public, projection only |
| `DistributionShapeRequest` | `DistributionShapeRequest.scala:25-32` | all six, as primitives | public, wire type |
| `DistributionParams` | `Provenance.scala:35-49` | **two cases, no `Option`** | public |

The last row is the one that already has the derived shape. Its two cases,
`ExpertDistributionParams` and `LognormalDistributionParams`, each carry exactly
their own fields.

Five of the six are the same product: a mode discriminator and five optional
fields. `Distribution` is the clearest statement of it:

```scala
final case class Distribution(
  distributionType: DistributionType,
  minLoss: Option[PositiveLong],
  maxLoss: Option[PositiveLong],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  terms: Option[PositiveInt]
)
```

**The rules live in five places, not three.** `Distribution.create`
(`Distribution.scala:36-109`); `RiskNode.validateExpertMode`,
`validateLognormalMode` and `validateModeFields`
(`RiskNode.scala:226-412`); the leaf's own class invariant
(`RiskNode.scala:99-107`); `Simulator.createDistributionWithParams`
(`Simulator.scala:126-215`); and the leaf-stage interpreter's own re-derivation
in `RiskLeafTransform.distributionFields` (`RiskLeafTransform.scala:262-309`).

---

## 3. Part 3 — the obstruction

### 3.1 The interpretation is partial, and its refusals are about populated fields

`interpret` for this type is `Simulator.createDistributionWithParams`. Its
signature is at `Simulator.scala:126`:

```scala
private def createDistributionWithParams(leaf: RiskLeaf): IO[AppError, (Distribution, DistributionParams)]
```

Of its error arms, four refuse the *shape* rather than the fit:

- `Simulator.scala:173-178` — expert mode without both arrays, or of unequal
  length, or shorter than two.
- `Simulator.scala:200-205` — lognormal mode without both bounds, or with the
  minimum not below the maximum.
- `Simulator.scala:208-213` — a distribution type that is neither.
- `Simulator.scala:146-147` — a percentile outside the open unit interval.

Each one restates a rule `Distribution.create` already enforced. They are present
because the type reaching the simulator cannot prove any of it, and the leaf is
**persisted** by then: `RiskTreeRepositoryIrmin.scala:289` stores `leaf.toJson`
and `:293` reads it back. So these refusals name a stored node rather than a
submitted field, which is why the code at `Simulator.scala:132-133` builds its
locations from `ErrorSubject.ExistingNode(leaf.id)`.

### 3.2 Consumers invent values for fields the contract guarantees

A consumer that cannot see the guarantee supplies a default. The preview service
does it five times:

```scala
val percentiles   = dist.percentiles.getOrElse(Array.empty[Double])  // already 0-1
val quantiles     = dist.quantiles.getOrElse(Array.empty[Double])
val resolvedTerms = dist.terms.map(_.toInt).getOrElse(math.min(percentiles.length, 4))
```
— `DistributionPreviewService.scala:47-49`

```scala
val minLoss = dist.minLoss.getOrElse(0L)
val maxLoss = dist.maxLoss.getOrElse(0L)
```
— `DistributionPreviewService.scala:74-75`

The second pair is the sharper case. `Distribution.create` guarantees both bounds
are present in lognormal mode and that the minimum is below the maximum. The
service cannot read that guarantee, so it defaults both to zero and hands
`fromLognormal90CI(0, 0)` a pair that must fail — converting a type-level
guarantee into a runtime error path. This is the shape the global instructions
name under "No shortcut may justify another shortcut": the consumer's form is a
consequence of the producer's, never evidence for it.

The browser does the same, and adds the code-style defect that follows from it:

```scala
if d.distributionType == IronConstants.Expert then
  val pcts   = d.percentiles.getOrElse(Array.empty[Double])
  val quants = d.quantiles.getOrElse(Array.empty[Double])
```
— `DistributionSpecBuilder.scala:147-150`

Testing which case of a value you hold by comparing it against a named constant
is the exact defect `~/.claude/CLAUDE.md`'s code-style section names. It is
written that way because there is no case to match on.

Counted across production sources, the five shape fields are interrogated as
`Option`s 43 times in 11 files, the largest groups being
`RiskLeafTransform.scala` with 13, `TreeBuilderState.scala` with 6 and
`DistributionPreviewService.scala` with 5.

### 3.3 The count

Write `a` for the inhabitants of `Array[Double]`, `p` for `PositiveLong` and `t`
for `PositiveInt`. The declared product has

```
2 × (1+a)² × (1+p)² × (1+t)
```

inhabitants. The meaningful set is

```
Expert:    a × a × (1+t)   restricted to equal length, paired ordering, element ranges
Lognormal: p × p           restricted to the minimum below the maximum
```

Every factor of the form `(1+x)` rather than `x` is one optional field, and each
one is a field whose absence has to be ruled out by a predicate somewhere. There
are five such factors and there are, in `Distribution.create` and
`RiskNode`'s helpers together, nine distinct refusals that do nothing but rule
them out. The two sets are not close, and the gap is the rule count.

### 3.4 There is no obstruction to removing it

In the explanation document's Part 3 the obstruction was real: `I1` and `I2`
could not both hold, so two folds were needed. Here there is no such tension.
The meaningful set **is** a coproduct, Scala has coproducts, and §9 shows the
representations need not change. Nothing is being traded. That is why this part
is short, and it is worth saying plainly: the current type is an *encoding* of
the coproduct — the product of both cases' fields with every field made optional
— and the encoding buys nothing while costing the nine predicates and the 43
interrogations.

---

## 4. Part 4 — the derivation

### 4.1 Replace the encoding with the thing encoded

The meaningful set is a tagged choice of two field-sets. That is a coproduct, so
declare a coproduct. The mode discriminator stops being a field, because the
case *is* the mode: `DistributionType`, a string refined to match `expert` or
`lognormal` (`OpaqueTypes.scala:279`), survives only in the wire encoding, where
the string is a contract with the stored bytes rather than with the serialiser.
That is the carve-out `ADR-037` §2 states for a declared spelling.

Every one of the five optional fields then sits inside the one case that
populates it, with no `Option` — except `terms`, which keeps one, for a reason
§4.3 derives.

### 4.2 The pairing rule moves to the one place that zips

Expert mode's percentiles and quantiles are co-indexed: the *n*th percentile and
the *n*th quantile are one anchor point. Declared as two sequences, they can
differ in length, so a rule must forbid it — and that rule is written three
times, at `Distribution.scala:90-91`, `RiskNode.scala:235-240` and
`Simulator.scala:139`.

Declared as one sequence of pairs, the rule cannot be written, because there is
no second length. What then happens to the refusal is worth stating precisely,
because it does not disappear: a form holds two text fields and the wire carries
two arrays, so **something still zips two inputs of possibly different length.**
The rule moves from the domain type to the one boundary that performs the zip,
and there are exactly two such boundaries — the browser's form parse and the wire
decoder. Both are places where `ADR-001` and `ADR-039` already say validation
belongs.

So `FieldName.PercentileQuantilePairing`, added to `ErrorLocation` earlier in this
work by `EWB-D-39`, stays reachable. Its home changes from three domain smart
constructors to two boundary decoders.

### 4.3 Which `Option`s are real

An `Option` earns its place when absence has a meaning other than "wrong mode".
Applying that test to the five:

- `percentiles`, `quantiles`, `minLoss`, `maxLoss` — absence means only "the
  other mode". Each becomes a plain field of its case.
- `terms` — absence means **derive it**. The derivation is
  `math.min(anchorCount, 4)`, and it is written twice, at `Simulator.scala:150`
  and `DistributionPreviewService.scala:49`. So the `Option` is real, it belongs
  inside the `Expert` case, and the derived value becomes one method on that case
  so the two copies collapse to one. §5.2 names it `termsToFit`.

### 4.3a The defaulted parameters, which are a different thing and are forbidden

Two things could both be called a default here and only one of them is allowed,
so they are separated before the count.

A **derived value** interprets an absence: `termsToFit` reads an absent `terms`
and computes the count a fit will use. A **defaulted parameter** hides an
absence: `terms: Option[Int] = None` lets a caller omit the argument entirely.
`.claude/rules/scala-style.md` forbids the second outright — "`Option[T] = None`
as a default parameter is forbidden. The `Option` stays and the default goes" —
and the `boyscout` skill gives the reason: a default "makes dropped information
invisible — the field that is never populated, where nothing ever failed because
nothing ever had to name a value." Every signature in §5 and §6 therefore
declares its `Option`s with no default.

**Eight defaulted `Option` parameters sit in the two files this part replaces**,
and six of them are in one signature:

| Location | Parameter | What happens to it |
|---|---|---|
| `Distribution.scala:43` | `terms` | goes with the file |
| `RiskNode.scala:163` | `percentiles` | unwritable: the six shape parameters become one |
| `RiskNode.scala:164` | `quantiles` | unwritable, same reason |
| `RiskNode.scala:165` | `minLoss` | unwritable, same reason |
| `RiskNode.scala:166` | `maxLoss` | unwritable, same reason |
| `RiskNode.scala:169` | `terms` | unwritable, same reason |
| `RiskNode.scala:167` | `parentId`, in `RiskLeaf.create` | boyscout removal |
| `RiskNode.scala:548` and `:602` | `parentId`, in the two portfolio constructors | boyscout removal |

The six in `RiskLeaf.create` belong to the derivation rather than beside it,
because they are what makes the mode confusion of §3 easy to reach. This
compiles today:

```scala
RiskLeaf.create(id, name, "lognormal", 0.5, seedVarId = 1L)
```

Four shape arguments were never named, all four defaulted to `None`, and the
refusal arrives at runtime from `validateLognormalMode`
(`RiskNode.scala:306-308`). Under §5 the call cannot be written: the six shape
parameters become one `shape: DistributionShape` with no default, so there is
nothing to omit.

The last three rows are not made unwritable by the new type. They are
obligations the `boyscout` skill attaches to `RiskNode.scala` because the change
edits it, and they are performed in the same pass rather than raised.

### 4.4 The defect the derived type removes

The two rule-sets differ in which rules they contain, and one path has no element
checks at all. `RiskNode.validateExpertMode` (`RiskNode.scala:226-285`) checks
presence, non-emptiness, equal length and a point count between 2 and 20. It
does not check that percentiles lie in the open unit interval, and it does not
check that either sequence increases. `validateModeFields`
(`RiskNode.scala:390-412`) delegates to it, and
`OverrideDistributionParams.create` (`RiskLeafTransform.scala:90-101`) delegates
to `validateModeFields`. The override's own decoder refines the mode, the bounds
and the term count, and passes `raw.percentiles` and `raw.quantiles` through
unrefined (`RiskLeafTransform.scala:119-134`).

So an override mitigation can be authored with expert anchors that are out of
range or out of order, and it is accepted and persisted in the tree's mitigation
blob. What happens next splits:

- **Out of range is caught late.** `Simulator.scala:141-147` refines each
  percentile to `Probability` and fails with `ValidationFailed`. The refusal
  names a persisted node, and it fires on every resolution of any tree that
  scopes that mitigation. **CONFIRMED** from the code above.
- **Out of order may not be caught at all.** The anchors reach
  `MetalogDistribution.fromPercentiles`. In the metalog library's own source
  checkout, `QPUnboundedConstrainedFitter.java:45` states that "Q(p) is enforced
  to be strictly increasing by requiring its derivative" and `:61` states the
  quadratic program is solved "subject to D·a ≥ ε (monotonicity)" — the fit is
  constrained to be monotone, which is not the same as rejecting a non-monotone
  input. The only exceptions found in `Metalog.java` are on a null or mis-sized
  coefficient array and on a probability outside the open unit interval. No
  ordering precondition on the input sequence was found in that package.
  **PLAUSIBLE**, and the single check that settles it is one unit test feeding a
  decreasing anchor pair to `MetalogDistribution.fromPercentiles` and recording
  whether it returns a `Left`. Until that is run, nothing in this plan is ranked
  on the assumption that it does.

Under the derived type neither is reachable: the element refinements are the
anchor's field types, and the ordering is the anchor sequence's construction
rule, so there is one site holding each and no path that skips it.

### 4.5 Collapsing five rule sets takes the strictest variant of every rule

§2 established that the rules live in five places. Collapsing them to one is
where a rule can be lost silently, because a rule present in only one of the five
looks like an absent rule from any of the other four. **So every rule is
inventoried across all five sites and the surviving single implementation takes
the strictest variant that remains meaningful under the stronger typing.** Taking
the weaker variant anywhere would be a regression this plan's own premise forbids
— §1.3's measure is the rule count, not the rule strength, and dropping strength
to reduce count is not the trade this plan makes.

The five sites, named once:

- **A** — `Distribution.create` (`Distribution.scala:36-109`), which the wire
  reaches through `DistributionShapeRequest.validate`.
- **B** — `RiskLeaf.create`, through `validateExpertMode`
  (`RiskNode.scala:226-285`) and `validateLognormalMode` (`:288-309`).
- **C** — the leaf's class `require` block (`RiskNode.scala:99-107`).
- **D** — `Simulator.createDistributionWithParams` (`Simulator.scala:126-215`).
- **E** — `validateModeFields` (`RiskNode.scala:390-412`), which
  `OverrideDistributionParams.create` reaches.

| Rule | Held by | Strictest variant | Where it lands |
|---|---|---|---|
| Percentile strictly inside (0, 1), not NaN, not infinite | A (`:65-68`), D (`:141-147`) | theirs | `Probability`, the anchor's field type |
| Loss strictly positive, not NaN, not infinite | **A only** (`:69-72`) | A's | `PositiveDouble`, the anchor's field type |
| Percentiles strictly increasing | **A only** (`:77-78`) | A's | `QuantileAnchors.create` |
| Losses strictly increasing | **A only** (`:79-80`) | A's | `QuantileAnchors.create` |
| Percentile and loss counts equal | A, B, D | all agree | unwritable — one sequence of pairs (`LSA-D-3`) |
| At least 2 anchors | B (`MinPercentiles`, `:123`), D (`:139`) | theirs; A requires only non-empty | `MinAnchors = 2` |
| At most 20 anchors | **B only** (`MaxPercentiles`, `:124`) | B's | `MaxAnchors = 20` |
| Term count at least 2 | **A only** (`:84`) | A's | `MetalogTerms` |
| Term count at most 20 | none explicitly | the fitter's own | `MetalogTerms` |
| Term count at most the anchor count | A (`:82`), B and E (`requireTermsWithinPercentiles`, `:344-358`) | all agree | `DistributionShape.Expert.create` |
| Both lognormal bounds present | A (`:96-104`), B (`:306-308`), E | all agree | unwritable — the case holds one `LossRange` |
| Both bounds strictly positive | A, B (`PositiveLong`), D (`:181`) | all agree | `PositiveLong`, `LossRange`'s field type |
| Minimum below maximum | A (`:98`), B (`requireMinBelowMax`, `:328-342`), D, E | all agree | `LossRange.create` |
| Mode is expert or lognormal | A, B (`DistributionType`), D's third arm, E's `failOnUnknownDistributionType` | all agree | unwritable — the case is the mode |
| Off-mode fields absent | **none** (§9.1) | the derived type's | unwritable — the coproduct cannot hold them |
| A scaled lower bound stays representable | `RiskLeafTransform.lowerBoundRepresentable` (`:254`) | the check stays, with the type as backstop | the transform arrow, and `PositiveLong` beneath it |

**Two rows are findings rather than bookkeeping.**

**The term count's lower bound is enforced in one place out of five, and the
value it admits throws inside the fitter.** `Distribution.create` refuses a
stated count below two at `Distribution.scala:84`. `validateExpertMode` has no
lower bound, `requireTermsWithinPercentiles` checks only the upper relation
(`RiskNode.scala:344-358`), and the simulator applies `leaf.terms.getOrElse(...)`
without checking it (`Simulator.scala:150`). So a leaf built through
`RiskLeaf.create` with one term and five anchors is accepted, persisted, and
reaches `Metalog.validateInputs`, which throws:

```java
public static void validateInputs(int terms) {
    if (terms < 2 || terms > MAX_TERMS) {
        throw new IllegalArgumentException(
                "terms must be in [2," + MAX_TERMS + "], got " + terms);
```

— `Metalog.java:218-223` in the `metalog-distribution` sibling checkout, with
`MAX_TERMS = 20` at `:42`, called from `QPUnboundedConstrainedFitter.java:85`.
`MetalogDistribution.fromPercentiles` catches it and returns a `Left`
(`ADR-033`'s Implementation table records that boundary), so it surfaces as
`DISTRIBUTION_FIT_FAILED` on a stored node rather than as a crash. **That matters
for this plan's own test.** §1.4 says the type is correct when `interpret` is
total up to genuine fit failure, and a term count of one would otherwise satisfy
§13's two-arm criterion while being a shape refusal wearing a fit failure's name.
`MetalogTerms` closes it, and the `Option[PositiveInt]` this plan carried until
this audit would not have.

**A scaled lower bound is the one case where the explicit check is kept, and the
reason is the message rather than the predicate.** `lowerBoundRepresentable`
(`RiskLeafTransform.scala:254`) is `scaledMin >= 1L`, and `PositiveLong` is
`Long :| Greater[0L]`, which for an integer is the same predicate — so the type
alone would refuse the same inputs once the severity-scaling arrow rebuilds its
bounds through `LossRange.create`. What the type would not do is say what to do
about it. The guard's refusal interpolates `minRepresentableFactor(min)`
(`:288`), which names the smallest factor that keeps the bound representable, and
a refinement failure cannot carry that. `ADR-010` §2 is the ground for keeping
it: a validation error carries "structured information with typed codes", and a
factor the author can act on is that information.

So this row is not a duplicated rule to collapse. It is one check that produces a
diagnosis, with the field type as the backstop that makes it impossible for the
check to be the only guard — which is the arrangement `ADR-034` §6 already
describes for the layer check, where "no type can close any of the three" and the
check and the types work together. All three helpers stay, and their three unit
tests at `RiskLeafTransformSpec.scala:136-153` stay unmodified.

---

## 5. Part 5 — the derived type

Four declarations, in the order their dependencies require. All live in
`modules/common/src/main/scala/com/risquanter/register/domain/data/`, cross-compiled
to the browser, so the browser's form builds the same type the server validates.

### 5.0 Which of them has a private constructor, and why that is not a judgement call

`ADR-001` §5 is the rule, and it is stated without exception: "A domain aggregate
is declared `final case class X private (...)`, with all construction routed
through a smart constructor (`create` / `fromNodes`) returning
`Validation[ValidationError, X]`", generalising "the `PortfolioLosses` private
constructor (`ADR-034`) to every aggregate".

The mechanism matters for the derivation, because it decides what a public
constructor actually costs. Scala 3 makes the generated `.copy` private when the
primary constructor is private, so a public case class offers two construction
paths rather than one, and the record names both: "once the constructor is
private there is no `apply` or `.copy` path that skips validation — the smart
constructor is the sole gate for every construction site (builders, merges,
store-loads, tests)".

`ADR-039` §3 supplies the half that rules out arguing from field types alone:
"Refining each field proves each field. It does not prove the cross-field rules
the aggregate owns."

So the test is one question per type: **is there any state reachable through
`apply` or `.copy` that this type's own rule would refuse?** A type carrying a
relation between two of its fields answers yes, and gets a private constructor
and a `create`.

A type whose every constraint is carried by one field's own refinement answers
no. Adding a `create` to such a type would return `Validation` having checked
nothing while leaving `apply` and `.copy` open beside it, so it would be surface
with no gate to keep — which `.claude/rules/scala-style.md` forbids ("Every
public method must have at least one call site at the time it is written") and
`ADR-018` names as a smell ("❌ Duplicating Validation in Wrapper": "Wrapper
re-implements ULID validation … GOOD: Wrapper delegates to base type").

**The two are not symmetric, and the asymmetry is what §6.6 turns on.** Removing
a rule from a type that already has a private constructor does not make its
`create` removable, because the record's requirement is that "all construction
routed through a smart constructor" rather than that the constructor check
something. A `create` that holds no rule but is the sole reachable path to a
private constructor is the prescribed shape; a `create` that holds no rule beside
a public `apply` is the smell. `Mitigation` ends in the first of those two
positions, for the reason §6.6 gives.

Applied to the types below and in §6.6:

| Type | Own rule | Constructor |
|---|---|---|
| `QuantileAnchor` | none — `Probability` and `PositiveDouble` carry both fields, and the two are unrelated | public |
| `QuantileAnchors` | anchor count in range, strictly increasing | **private**, `create` |
| `LossRange` | minimum below maximum | **private**, `create` |
| `DistributionShape.Expert` | term count at most the anchor count | **private**, `create` |
| `DistributionShape.Lognormal` | none — `LossRange` carries the only relation | public |
| `LeafParams` | none — both fields carry their own | public |
| `RelativeLikelihood.Scale`, `RelativeSeverity.ScaleSeverity` | none — `RetentionFactor` carries it | public |
| `AbsoluteReplacement.Likelihood`, `Severity`, `Both` | none — `ResidualProbability` and `DistributionShape` carry their own | public |
| `MitigationSpec.LeafRelative` | none — `Keep` paired with `Keep` is the identity and is legitimate | public |
| `MitigationSpec.LeafAbsolute` | none — the anchor and stamp are plain fields, so their presence is not a rule | public |
| `MitigationSpec.ResultStage` | pipeline step count between 1 and 10 | **private**, `create` (§6.6) |

A public row is a statement that the type has no rule to protect today. Should one
ever be added, that row moves and the constructor goes private in the same edit,
which is the migration `ADR-001` §5 exists to make cheap while the call sites are
few.

Two new Iron refinements are needed, both co-located with their siblings in
`OpaqueTypes.scala` as the `code-quality-review` skill's placement table
requires. Its upper bound follows the precedent of `NonNegativeDouble` at
`OpaqueTypes.scala:110`, which exists because `Greater[0.0]` admits positive
infinity while a comparison against the largest finite double excludes it:

```scala
// A loss figure in millions: strictly positive and finite. The upper bound is
// Double.MaxValue spelled out, which is what excludes positive infinity —
// Greater[0.0] alone admits it.
type PositiveDouble = Double :| (Greater[0.0] & LessEqual[1.7976931348623157e308])
```

A second refinement is needed, for the reason §4.5 derives: the term count's
valid range is a precondition of the fitter that throws, and only one of the five
current rule sites enforces its lower bound. Both bounds come from the library's
own source rather than from this project:

```scala
// The metalog fit's term count. Both bounds are the fitter's own precondition:
// Metalog.validateInputs throws IllegalArgumentException outside [2, MAX_TERMS],
// and MAX_TERMS is 20. A fit with fewer than two terms has no curve to describe.
type MetalogTerms = Int :| (GreaterEqual[2] & LessEqual[20])
```

### 5.1 One anchor

```scala
/** One expert-opinion anchor: the loss at a stated percentile. The percentile is
  * strictly inside the open unit interval, which is the domain the metalog
  * quantile function is defined on. */
final case class QuantileAnchor(percentile: Probability, loss: PositiveDouble)
```

`Probability` is the existing `Double :| (Greater[0.0] & Less[1.0])` at
`OpaqueTypes.scala:271`, whose own comment records that it exists for metalog
percentiles. The loss stays a `Double` rather than becoming a `PositiveLong`:
quantiles are carried as doubles on the wire and in storage today, and widening
them to integers would change stored figures.

### 5.2 The anchor sequence

```scala
/** Between two and twenty anchors, strictly increasing in percentile and in
  * loss. Construction is the only way in, so the ordering holds for every value
  * that exists. */
final case class QuantileAnchors private (values: Vector[QuantileAnchor]):
  /** Anchors zipped back into the two parallel sequences the wire and the
    * fitter take. */
  def percentiles: Array[Double] = values.map(a => a.percentile: Double).toArray
  def losses: Array[Double]      = values.map(a => a.loss: Double).toArray

  /** The term count a fit uses when the author named none: one term per anchor.
    * That is the fit whose curve passes through every anchor the author stated,
    * which is what an author who states anchors and no count is asking for. A
    * lower count smooths the curve away from the anchors and is reachable only by
    * stating it.
    *
    * The earlier `math.min(values.length, 4)` is retired. The `4` had no source:
    * the fitter recommends no term count and defaults to none, and this
    * repository's only mention of the number described a default that did not
    * exist.
    *
    * The `refineUnsafe` is sound here and nowhere else in this plan: the only
    * constructor bounds the anchor count to [2, 20], so the result is inside
    * `MetalogTerms`' own [2, 20]. ADR-001 names `.refineUnsafe` in production as
    * a smell because it crashes on invalid input; it cannot be reached with
    * invalid input from here. */
  def termsToFit: MetalogTerms = values.length.refineUnsafe

object QuantileAnchors:
  /** The metalog fitter's valid term range is two to twenty, and a fit cannot
    * use more terms than it has anchors, so bounding the anchor count bounds the
    * term count transitively. Below two there is no curve to fit; above twenty
    * the fitter throws past the validation boundary. */
  private val MinAnchors = 2
  private val MaxAnchors = 20

  def create(
    anchors:  Vector[QuantileAnchor],
    location: ErrorLocation
  ): Validation[ValidationError, QuantileAnchors]
```

The two kinds of constraint sit where each belongs. Per-element range is the
field's type, carried by `Probability` and `PositiveDouble`. Count and ordering
are relations over the sequence, so they are held by `create`, and the `private`
constructor is what makes that the only entry — the same device the explanation
document's Part 2 describes for `PortfolioLosses`: "its constructor is private.
The only way to build one is `PortfolioLosses.create`".

### 5.3 The lognormal bounds

```scala
/** The lognormal mode's 90% confidence interval: a strictly ordered pair of
  * positive loss bounds. The ordering is the pair's own rule — neither bound is
  * wrong on its own, and editing either satisfies it. */
final case class LossRange private (min: PositiveLong, max: PositiveLong)

object LossRange:
  def create(
    min:      PositiveLong,
    max:      PositiveLong,
    location: ErrorLocation
  ): Validation[ValidationError, LossRange]
```

The type's name is already in the codebase: `FieldName.LossRange` was added to
`ErrorLocation.scala` earlier in this work by `EWB-D-39`, to be the location a
minimum-under-maximum refusal reports at. The error location named the pair as a
thing before the type did, and that is the signal the decision records expect —
a refusal that is about a pair rather than a field is a pair that wants to be a
type.

### 5.4 The shape

```scala
/** The loss distribution of one risk leaf, in the two modes an author can
  * declare. The case is the mode, so no value carries an expert field and a
  * lognormal field together, and no rule has to state which fields a mode
  * populates. */
sealed trait DistributionShape

object DistributionShape:

  /** Expert opinion: a metalog quantile function fitted to the author's
    * anchors. `terms` absent means fit with `anchors.termsToFit`. */
  final case class Expert private (
    anchors: QuantileAnchors,
    terms:   Option[MetalogTerms]
  ) extends DistributionShape

  object Expert:
    /** Refuses a stated term count above the anchor count: a metalog fit cannot
      * use more terms than it has points. An absent count is always valid,
      * because `anchors.termsToFit` derives one that cannot exceed the count.
      *
      * This is the whole of the rule, because `MetalogTerms` carries the range
      * [2, 20] and `QuantileAnchors` carries the count [2, 20]. What is left is
      * the one comparison no field type can hold. */
    def create(
      anchors:  QuantileAnchors,
      terms:    Option[MetalogTerms],
      location: ErrorLocation
    ): Validation[ValidationError, Expert]

  /** Lognormal: the BCG method fitted to a 90% confidence interval. */
  final case class Lognormal(bounds: LossRange) extends DistributionShape

  /** The confidence level the lognormal bounds are read at. Fixed by the BCG
    * method rather than configurable; it is recorded in provenance as 0.90 at
    * `Simulator.scala:190` today. */
  val LognormalConfidenceInterval: Double = 0.90
```

`Expert.terms` keeps its `Option` under §4.3's test, and
`requireTermsWithinPercentiles` (`RiskNode.scala:344-358`) survives as a rule on
the `Expert` case alone, where it is a relation between two of its own fields.
That relation is why `Expert` is the one case of the coproduct with a private
constructor, per §5.0: no field type can carry a comparison between the term
count and the anchor count, so `Expert.create` is the only gate that can hold it,
and a public `apply` or `.copy` would be a second path around it.

`Lognormal` has no such relation. `LossRange` already holds the only rule
relating two values in that case, and it holds it behind its own private
constructor, so `Lognormal.apply` can construct nothing its own rule would
refuse.

### 5.5 The name

`DistributionShape` rather than `Distribution`, for two reasons that need no
decision. The codebase already calls it the shape — `DistributionShapeRequest`,
the `distributionShape` wire field, `RiskLeafFormState.currentShapeValidation`,
and the `shape` parameter at `RiskTreeServiceLive.scala:264`. And `Distribution`
currently names three unrelated things: this type, the sampler trait at
`modules/server/.../simulation/Distribution.scala:12`, and `LossDistribution`,
the simulation output. Retiring one of the three collisions costs nothing.

### 5.6 The resolved shape, and the tag it is missing

`LSA-D-2` ruled that `DistributionParams` stays, because it is a different thing
from `DistributionShape`: the shape is what an author declared and the parameters
are what the simulator consumed. The two are related by one total function, which
is what makes the second not a copy of the first:

```scala
object DistributionShape:
  /** The parameters a fit will actually run with: the author's shape, with every
    * value the system supplies filled in. Total, because every absence has a
    * derivation — `termsToFit` for the term count, and the BCG confidence level
    * for a lognormal, which no author states. */
  def resolve(shape: DistributionShape): DistributionParams = shape match
    case Expert(anchors, terms) =>
      ExpertDistributionParams(anchors.percentiles, anchors.losses, terms.getOrElse(anchors.termsToFit))
    case Lognormal(bounds) =>
      LognormalDistributionParams(bounds.min, bounds.max, LognormalConfidenceInterval)
```

`resolve` is where the `Option` disappears, and it is the only place the derived
term count and the confidence level are applied — replacing the second copy of
`math.min(n, 4)` at `DistributionPreviewService.scala:49` and the bare `0.90` at
`Simulator.scala:190`.

**The tag.** `NodeProvenance` declares a `distributionType: String`
(`Provenance.scala:26`) beside `distributionParams`, and the string is derivable
from the parameters' case: an `ExpertDistributionParams` cannot be anything but
expert. That is this plan's own subject one more time — a discriminator field
beside a coproduct that already discriminates — so by §1.3 it should go.

It cannot simply go, because it is the payload's only tag.
`DistributionParams`'s codec is an **untagged** union: the encoder writes each
case's own object with no tag (`Provenance.scala:84-91`), and the decoder tries
one case and falls back to the other (`Provenance.scala:93-95`):

```scala
given decoder: JsonDecoder[DistributionParams] =
  ExpertDistributionParams.codec.decoder.widen[DistributionParams] <>
  LognormalDistributionParams.codec.decoder.widen[DistributionParams]
```

So discrimination is by field presence and by the order of those two lines. That
has a diagnostic failure mode: an expert record whose `terms` is out of range
fails the first decoder, falls through to the second, and surfaces the
*lognormal* decoder's complaint about a missing `minLoss` — about a record that
was never lognormal.

**So the fix is two moves, in this order.** Give `DistributionParams` a derived
discriminated codec, with `@jsonDiscriminator` naming the tag field and
`@jsonHint` carrying the external spelling of each case, which is what
`.claude/rules/scala-style.md` prescribes for a closed set: "Its JSON codec is
derived; `@jsonHint` carries the external spelling only where it differs from the
case name, and `@jsonDiscriminator` names the tag field." Then delete
`NodeProvenance.distributionType`, which is redundant once the tag sits inside
the union, and with it the widened assignment at `Simulator.scala:113` that puts
a `DistributionType` into a `String`.

The declaration, with the tag field named `distributionType` and the two case
spellings declared so the tagged payload carries the same key and the same two
values the deleted field carries today:

```scala
/** Distribution-specific parameters for loss modeling, as the simulator
  * consumed them. The tag field is the payload's only discriminator: the two
  * cases are distinguished by it rather than by which fields are present. */
@jsonDiscriminator("distributionType")
sealed trait DistributionParams

@jsonHint("expert")
final case class ExpertDistributionParams(
  percentiles: Array[Double],
  quantiles:   Array[Double],
  terms:       MetalogTerms
) extends DistributionParams

@jsonHint("lognormal")
final case class LognormalDistributionParams(
  minLoss:            PositiveLong,
  maxLoss:            PositiveLong,
  confidenceInterval: Double
) extends DistributionParams

object DistributionParams:
  given codec: JsonCodec[DistributionParams] = DeriveJsonCodec.gen[DistributionParams]
```

`terms` tightens from `PositiveInt` to `MetalogTerms`, which is the strictest
type that still describes the value: the record states what the simulator
consumed, and what it consumed always passed the fitter's own [2, 20]
precondition. `ADR-038` §3 — "No Raw Primitive Where a Refined Type Exists" —
reaches this, because `PositiveInt` admits 1 and the value never can be 1. No
signature in
[`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) is affected, for the
reason §5.6 already gives: each names `NodeProvenance` as a whole.

Each case keeps its own existing `Raw`-based codec, with the refinement helper
changed from `ValidationUtil.refinePositiveInt` to one for `MetalogTerms`
(`Provenance.scala:50-60` is the current site); the
derivation above adds the tag above them and replaces only the hand-written
encoder and the `<>` fallback decoder. **Whether `DeriveJsonCodec.gen` composes
the per-case codecs rather than requiring field-level instances for the Iron
types is a compile-level fact and is not asserted here.** The register-dev skill
names the one route to such a fact — compile the real change in the real module —
so `sbt commonJVM/compile` settles it, and the fallback if it does not compose is
one `Raw` product carrying the tag plus both cases' fields, decoded by matching
on the tag.

**Why now rather than later, stated as a fact about reachability.** Nothing
decodes a `NodeProvenance` today — the record is built in process, is never
persisted (§11, `LSA-D-2`), and reaches no response. So the format can be changed
at no cost right now. It acquires a decoder the moment
[`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) ships and a client
parses the body. After that, both moves are a breaking wire change.

**What this obliges toward that plan.** Its Phase 2 quotes `NodeProvenance`
verbatim as current state, including the field being deleted
(`PLAN-PROVENANCE-ENDPOINT.md:53-65`). That quotation goes stale. None of its
signatures do: the route, the Tapir endpoint, `getProvenance` and
`leafProvenances` name `NodeProvenance` as a whole and never its fields. It is an
approved plan that is not yet implemented, so this plan does not edit it — the
one-paragraph amendment is presented to the user and waits.

### 5.7 The signatures the change propagates to

Four existing signatures change shape because the six shape fields become one
value. They are written out here rather than described, because `G1` in
`CLAUDE.md` says an approved plan satisfies the echo only "for every signature it
contains verbatim".

**The leaf's two constructors.** Eleven parameters become six, and the shape
arrives already gated, so `create` refines only the four raw primitives it is
given:

```scala
object RiskLeaf:
  /** Accepts already-validated types. The shape carries its own proof, so there
    * is nothing left for this to drop. */
  def fromValidated(
    id:          SafeId.SafeId,
    name:        SafeName.SafeName,
    probability: OccurrenceProbability,
    shape:       DistributionShape,
    parentId:    Option[NodeId],
    seedVarId:   SeedVarId.SeedVarId
  ): RiskLeaf

  /** Refines the identifier, the name, the probability and the seed identity.
    * The shape is not re-validated: it cannot exist unless its own gate passed
    * (§5.0), which is the one-boundary rule of ADR-001 §2. */
  def create(
    id:          String,
    name:        String,
    probability: Double,
    shape:       DistributionShape,
    parentId:    Option[NodeId],
    seedVarId:   Long,
    subject:     ErrorSubject = ErrorSubject.Request
  ): Validation[ValidationError, RiskLeaf]
```

`parentId` loses its default under §4.3a. `subject` keeps its
`ErrorSubject.Request` default: the forbidden form is specifically
`Option[T] = None`, which `.claude/rules/scala-style.md` names and this is not,
so it is stated here rather than left to look like an oversight.

**The cache-key projection.** `LeafSimContent` keeps its eight flat fields
unchanged, because §9 holds every emitted byte fixed. What changes is that `from`
becomes the one site that flattens the coproduct back into them:

```scala
object LeafSimContent:
  /** Project the simulation-relevant fields out of a leaf. The flat shape is a
    * storage contract, so this fills the same eight fields in the same order
    * the encoder has always emitted. */
  def from(leaf: RiskLeaf): LeafSimContent = leaf.shape match
    case DistributionShape.Expert(anchors, terms) =>
      LeafSimContent(
        seedVarId        = leaf.seedVarId,
        probability      = leaf.probability,
        distributionType = IronConstants.Expert,
        percentiles      = Some(anchors.percentiles),
        quantiles        = Some(anchors.losses),
        minLoss          = None,
        maxLoss          = None,
        terms            = terms
      )
    case DistributionShape.Lognormal(bounds) =>
      LeafSimContent(
        seedVarId        = leaf.seedVarId,
        probability      = leaf.probability,
        distributionType = IronConstants.Lognormal,
        percentiles      = None,
        quantiles        = None,
        minLoss          = Some(bounds.min),
        maxLoss          = Some(bounds.max),
        terms            = None
      )
```

The two `None` groups are where `LSA-D-5` bites; §9 states why.

**The leaf's codec.** `RiskLeafRaw` (`RiskNode.scala:435-447`) keeps its eleven
flat primitive fields in their current declaration order, which is what makes §9
true. The decoder gains the case split `Distribution.create` performs today, and
routes through the shape's own gates:

```scala
given decoder: JsonDecoder[RiskLeaf] = RiskLeafRaw.rawCodec.decoder.mapOrFail: raw =>
  // raw.distributionType decides which gate runs; each returns Validation, and
  // the leaf's own create refines the remaining four primitives.
  shapeFromRaw(raw).flatMap(shape => RiskLeaf.create(...)).toEither.left.map(...)

/** The store-read and request-decode boundary for the shape: the one place two
  * parallel arrays are zipped into anchors, so the pairing rule lives here
  * (§4.2) rather than in three domain constructors. */
private def shapeFromRaw(raw: RiskLeafRaw): Validation[ValidationError, DistributionShape]
```

**The browser's shape parse.** `RiskLeafFormState.currentShapeValidation`
(`:387-411`) today runs four `mode match` expressions to decide which fields to
populate, sets the rest to `None`, and flattens the result into
`Distribution.create`'s optional parameters. It becomes one match producing one
case, and it is the second of §4.2's two zip boundaries:

```scala
/** The form's two text fields parsed, zipped and gated into one shape. The zip
  * is where a percentile count unequal to the loss count is refused, which is
  * the only place in the browser that rule can be stated. */
val currentShapeValidation: Signal[Validation[ValidationError, DistributionShape]]
```

### 5.8 What the fitter does not check, and the one check this plan must keep

Two preconditions of the metalog fit are not enforced by the library this plan
fits through. They are recorded here because the derived type changes which of
them this plan has to carry, and because a later reader looking at the adapter
will otherwise read a surviving check as redundant defence and remove it.

The library is `metalog-distribution`, consumed as a binary artifact. Both points
below are written up in `FINDING-basis-function-indexing.md` at the root of that
repository, which is where their fixes belong; neither can be fixed from here.

**The term count against the anchor count — this plan keeps the check.** A
metalog fit with more terms than data points is underdetermined: the
least-squares objective has a subspace of zero-residual solutions, so infinitely
many curves pass through the anchors exactly and disagree between them and in
the tails. The library accepts it. `Metalog.validateInputs(int terms)` checks
only that the count lies in [2, 20], and `QPUnboundedConstrainedFitter`'s
constructor calls that and never compares the count with `pData.length`.

So two checks on this side are the only enforcement of the relation anywhere, and
both stay:

- `DistributionShape.Expert.create` refuses a stated count above the anchor
  count. This is the type's own cross-field rule, and §5.4 states it.
- `MetalogDistribution.validateTerms` repeats it at the adapter. This one is not
  redundant: the adapter takes the anchors and the term count as two parameters,
  and no field type relates them, so a caller assembling a mismatched pair is
  refused here and nowhere else.

The second is the reason the adapter keeps a runtime check at all once it takes
`QuantileAnchors`. Sortedness, equal lengths and non-emptiness become
unrepresentable and their checks go; this relation does not, because
`MetalogTermCount` bounds the range [2, 20] and the anchor count bounds [2, 20]
independently.

**The duplicate basis column — a limitation to record, not a rule to add.**
`Metalog.basisFunctions` produces the same function at index 2 and index 3, and
`basisDerivatives` duplicates the same pair. Every fit with four or more terms
therefore carries one coefficient that holds no information, and the fit succeeds
silently.

The fitted curve stays determined, and figures stay reproducible. Both duplicate
columns multiply the same basis function, so the quantile function contains
`a2 * T2 + a3 * T3 = (a2 + a3) * T2`, and that sum is determined even though the
split between the two coefficients is not. The monotonicity constraint matrix
duplicates the same pair, so it does not distinguish the minimisers either. What
the defect costs is flexibility rather than determinism: a fit stated as `n` terms
delivers the curve of `n - 1` terms for every `n` of four or more.

So the record's claim to correct: `NodeProvenance` states the term count the
simulation consumed, and for a count of four or more that number overstates the
fit's flexibility by one. Nothing here can avoid it and no runtime rule would
help — refusing counts of four or more would refuse the feature.

One thing this side can do, and it is a test rather than a rule.
`Metalog.basisFunctions` and `Metalog.basisDerivatives` are both public, so a
test here can call them and pin the duplicate as it currently stands. That test
passes today and fails the moment the library's basis changes, which is the
notification this side actually needs: correcting the duplicate changes the
fitted figures of every expert leaf with four or more anchors, so the change must
arrive as a failing test rather than as moved numbers.

It is worth stating against the term-count ruling rather than leaving implicit.
Deriving the count as the anchor count puts a typical expert fit at five or more
terms, which includes the duplicate column. The capped derivation it replaced put
a typical fit at exactly four, which also includes it. So the ruling neither
causes nor avoids the problem, and the comparison is not an argument against it.

---

## 6. Part 6 — the override type, derived

The same ruling required this derivation to cover "every type the
distribution-override perspective introduced". It does, and the answer for the
first of them is that it is not a type.

### 6.1 `OverrideDistributionParams` is `DistributionShape`

An override replaces a leaf's loss distribution with an author-supplied one. Its
parameter is therefore a loss distribution. `OverrideDistributionParams`
(`RiskLeafTransform.scala:60-66`) declares the same six fields as `Distribution`
(`Distribution.scala:12-19`) and validates them with the same helper
(`RiskLeafTransform.scala:99-101` calls `RiskLeaf.validateModeFields`). It is the
shape type, declared a second time.

The reason it exists is visible in what it adds, and both additions are
properties of `Distribution`'s encoding rather than of the concept:

- A `private` constructor. `Distribution`'s `apply` is public, so it offers no
  construction gate — and `TreeBuilderState.scala:447` uses it, building a
  `Distribution` from a loaded leaf's six fields with no check.
- Hand-written `equals`, `hashCode` and `optArrayEq`
  (`RiskLeafTransform.scala:68-85`), because `Array` compares by reference under
  case-class equality and the mitigation work needs content equality for set
  membership.

Both vanish under §5. `QuantileAnchors` holds a `Vector`, which has structural
equality, so the 18 lines of hand-written equality go and `Equal.default` is
correct. The constructors of all three new types are `private`, so there is one
gate and no second type is needed to get one.

So `DistributionTransform.Override` carries the shape itself:

```scala
final case class Override(shape: DistributionShape) extends DistributionTransform
```

and `OverrideDistributionParams` is deleted — the case class, `create`, the three
equality members, the `Raw` type and the codec, about 85 lines in total.

### 6.2 What `DistributionTransform` is

`DistributionTransform` (`RiskLeafTransform.scala:160-165`) has three cases, and
each is a function from a shape to a shape:

- `Keep` — the identity function.
- `ScaleSeverity(factor)` — multiply the loss figures by `factor`.
- `Override(shape)` — the constant function at `shape`.

So the type is a set of endomorphisms on `DistributionShape` in the sense of
§1.5, named as data so it can cross the wire and be displayed. The interpreter
that turns the data back into the function is
`RiskLeafTransform.distributionFields` (`RiskLeafTransform.scala:262-309`).

### 6.3 The set is closed, and `Override` is its absorbing element

Composing two of them:

```
Keep ∘ x                              = x                           -- identity
ScaleSeverity(f) ∘ ScaleSeverity(g)   = ScaleSeverity(f · g)         -- closed
Override(s) ∘ x                       = Override(s)                  -- absorbing
x ∘ Override(s)                       = Override(x(s))               -- closed
```

The second line is closed because `RetentionFactor` is
`Double :| (Greater[0.0] & LessEqual[1.0])` (`OpaqueTypes.scala:117`), and a
product of two values in that half-open interval stays in it. The third line is
§1.5's absorbing element, and it is the formal content of "absolute replacement":
an override discards whatever came before it.

**That is why `Override` must carry a complete shape and never a partial one.** A
constant function has to name its whole value. An override carrying some fields
and inheriting the rest would not be a constant function, and its composition
with a preceding transform would depend on that transform — which is exactly
what the type's own documentation says it is not:
"Representation-agnostic by construction (it replaces, so no cross-representation
mapping is needed)" (`RiskLeafTransform.scala:54-58`). The derivation confirms
that comment and gives it a reason.

### 6.4 The composition already runs, in the Kleisli category

The fourth line can fail. `ScaleSeverity` applied to lognormal bounds floors the
scaled minimum, and a minimum that floors to zero cannot be fitted — the guard is
`RiskLeafTransform.lowerBoundRepresentable` at `RiskLeafTransform.scala:254`.
So composition is Kleisli composition in `Validation`, per §1.6.

And it is already written that way. `MitigationApplication.scala:156-157` folds
every leaf-stage transform scoping one leaf, in precedence order:

```scala
transforms.foldLeft(Validation.succeed(leaf): Validation[ValidationError, RiskLeaf]) {
  (accV, t) => accV.flatMap(l => RiskLeafTransform.applyTo(t, l))
}
```

`flatMap` is the Kleisli composition, and `applyTo(t, _)` has type
`RiskLeaf => Validation[ValidationError, RiskLeaf]`, which is the `A => F[A]`
arrow shape §1.6 describes. The monoid is in the fold; it is simply not named on
the type.

**Two things are called the identity here and they are not the same object.** The
identity *element* of the monoid is the arrow `pure`, of type `A => F[A]`, which
is `a => Validation.succeed(a)`. The fold's seed, `Validation.succeed(leaf)`, is
`pure` applied to `leaf`, so it is a value of type `F[A]`. Folding the arrows
with Kleisli composition and then applying the result to `leaf` computes the same
answer as folding values from that seed, which is what the code does, but only
the first of the two is the monoid's identity.

That `pure` is an identity follows from the two monad identity laws, and it holds
because composition is `flatMap`-based rather than `andThen`-based: Kleisli
composition hands the next arrow the plain value inside the `Validation`, so no
arrow in this monoid ever takes a wrapped value as input.

```
andThenK(pure, g) = a => Validation.succeed(a).flatMap(g) = a => g(a) = g
andThenK(f, pure) = a => f(a).flatMap(Validation.succeed) = a => f(a) = f
```

A monoid is a category with one object, so the precise statement is that the
arrows `A => F[A]` for a **fixed** `A` form a monoid. If the type varied from
step to step this would be a category and not a monoid, and nothing in the
argument would survive unchanged. The fixed `A` is `RiskLeaf` today and becomes
`LeafParams` under the derived arrow at the end of this section. The claim is
about `Validation`'s monadic structure only. Its
accumulating structure is a different operation and the two do not agree, which
is why `.claude/rules/scala-domain.md` separates them: "`.flatMap` stops on the
first error (Monad — dependent steps)" against "`validateWith` collects all
errors before returning (Applicative — independent fields)". A transform chain is
dependent steps, so `flatMap` is the right one and fail-fast is the right
behaviour.

**What that costs today.** The fold threads a whole `RiskLeaf` through every
step, and `RiskLeafTransform.applyTo` rebuilds it by calling `RiskLeaf.create`
with primitives (`RiskLeafTransform.scala:229-242`):

```scala
RiskLeaf.create(
  id = leaf.id.value,
  name = leaf.name.value,
  ...
```

So with *n* leaf-stage mitigations scoping one leaf, the identifier, the name and
the seed identifier are each widened to a primitive and re-refined *n* times,
although no transform can change any of them. `.claude/rules/scala-algebraic-design.md`
names this directly: "`.value` is a widening signal", and the only sanctioned
reasons are a third-party library, an output boundary and a repository query.
None applies here.

The derived arrow operates on what a transform can actually change:

```scala
/** What a leaf-stage transform may change: the occurrence probability and the
  * loss distribution. Identity, name, parent and seed identity are outside a
  * transform's reach, so they are outside its type. */
final case class LeafParams(
  probability: OccurrenceProbability,
  shape:       DistributionShape
)

object LeafStageSpec:
  /** A leaf-stage spec as a Kleisli arrow on the parameters it can change.
    * Identity is `p => Validation.succeed(p)`, which `LeafRelative(Keep, Keep)`
    * is. */
  def applyTo(spec: LeafStageSpec, params: LeafParams): Validation[ValidationError, LeafParams]
```

The leaf is then rebuilt once, after the fold, by `copy`. The *n* identifier and
name refinements become zero, and the monoid is visible in the signature.

The arrow is declared on `LeafStageSpec` rather than on a transform type, because
`LSA-D-1` ruled that the two param-stage specs *are* the two kinds of arrow —
`LeafRelative` composes and `LeafAbsolute` absorbs — so there is no separate
product type left to hang it on. §6.6 carries that ruling.

### 6.5 The precedent is in the codebase, on the other side

The result-stage transforms already do all of this explicitly.
`ResultTransformSpec.scala:168-178` declares the pipeline with an identity:

```scala
/** Associative with `empty` as identity; deliberately NOT commutative (order matters). */
def identity: TransformPipeline = empty
def combine(l: => TransformPipeline, r: => TransformPipeline): TransformPipeline =
  TransformPipeline(l.steps ++ r.steps)
```

and `ResultTransformInterpreter.scala:25-29` states and tests the homomorphism
law relating the data monoid to the function monoid:

```scala
/** Law (tested): toTransform(a <> b) behaves as toTransform(a) andThen toTransform(b). */
def toTransform(p: TransformPipeline): RiskResultTransform =
  p.steps.foldLeft(RiskResultTransform.identityTransform)((acc, s) => acc.andThen(toTransform(s)))
```

The parameter-stage side has the same algebra and names none of it. This is an
argument from the codebase's own standard rather than from theory: one side
declares its identity, folds with it, and tests the law; the other folds without
naming either. The derived `applyTo` above is what makes the two sides match, and
the law it then owes is in §13.

### 6.6 The authoring evidence is misplaced — ruled 2026-10-22, `LSA-D-1`

`MitigationSpec.LeafStage` (`Mitigation.scala:50-54`) is a product of three
fields:

```scala
final case class LeafStage(
  transform: RiskLeafTransform,
  overrideBaseStamp: Option[ContentHash],
  overrideAnchor: Option[NodeId]
) extends MitigationSpec
```

and `Mitigation.create` carries the rule relating them
(`Mitigation.scala:125-146`): both fields present when the transform contains an
override component, both absent when it does not, with two failure arms for the
other two combinations. The predicate it tests on is
`hasOverrideComponent` (`Mitigation.scala:205-210`).

This is §1.3 again, in the override's own neighbourhood. Four combinations are
representable and two are meaningful, and the rule exists only to rule out the
other two. The two fields are not properties of a leaf-stage spec; they are the
evidence an *override* carries, and the type says otherwise.

#### Why the dependency exists at all

The split is not by which leaves may be overridden — any leaf may be. It is by
**what kind of statement the mitigation makes**, and each component's three cases
already fall into two kinds.

A **relative** statement — `Keep`, `Scale(factor)`, `ScaleSeverity(factor)` — says
"whatever this leaf is, multiply it". It stays true when the leaf changes. A
control that cuts likelihood to 30% still means 30% after the leaf is
re-estimated, so there is nothing about the leaf's former value to record.

An **absolute** statement — either `Override` case — says "after this control,
this leaf *is* these figures". The figures were judged against one leaf as it
stood at authoring time. An expert looking at a lognormal 20M–120M leaf and
writing "with the policy we bear 1M–5M" has made a statement *about* 20M–120M; if
the leaf is later re-estimated to 200M–1.2B, the retention is no longer 1M–5M and
nothing in the mitigation can tell. That is what the evidence is for, and
`MitigationStaleness.isStale` (`MitigationStaleness.scala:25-32`) is its only
reader: it compares the stored stamp against the anchor leaf's current
`LeafSimContent` hash.

So the evidence is not optional decoration on some mitigations. It is what makes
an absolute assertion auditable, and a relative one has nothing to audit. The
dependency is real, and the type should carry it rather than a rule.

#### The ruled shape

Both components split by the same cut, which is what lets the spec split. These
go in `RiskLeafTransform.scala`, beside the types they replace.

```scala
/** A likelihood change stated relative to whatever the leaf currently says. */
sealed trait RelativeLikelihood
object RelativeLikelihood:
  case object Keep extends RelativeLikelihood
  final case class Scale(factor: RetentionFactor) extends RelativeLikelihood

/** A loss-shape change stated relative to whatever the leaf currently says. */
sealed trait RelativeSeverity
object RelativeSeverity:
  case object Keep extends RelativeSeverity
  final case class ScaleSeverity(factor: RetentionFactor) extends RelativeSeverity

/** What an absolute replacement asserts: a post-control probability, a
  * post-control loss shape, or both. Three cases, which is 2² − 1 — the
  * non-empty subsets of a two-element set. "At least one component is
  * replaced" is therefore not a rule but the absence of a fourth case: a
  * replacement that replaces nothing is `RelativeLikelihood.Keep` paired with
  * `RelativeSeverity.Keep` instead. */
sealed trait AbsoluteReplacement
object AbsoluteReplacement:
  final case class Likelihood(probability: ResidualProbability) extends AbsoluteReplacement
  final case class Severity(shape: DistributionShape) extends AbsoluteReplacement
  final case class Both(probability: ResidualProbability, shape: DistributionShape) extends AbsoluteReplacement
```

`MitigationSpec` gains an intermediate sealed layer, so the one site that
collects applicable leaf-stage specs stays a single pattern. These replace
`LeafStage` in `Mitigation.scala`:

```scala
sealed trait MitigationSpec

/** The two param-stage specs, grouped so a caller that applies them need not
  * name each case. */
sealed trait LeafStageSpec extends MitigationSpec

object MitigationSpec:

  /** A relative param-stage change. The statement stays true when the leaf
    * changes, so there is nothing to stamp and nothing to anchor. */
  final case class LeafRelative(
    likelihood: RelativeLikelihood,
    severity:   RelativeSeverity
  ) extends LeafStageSpec

  /** An absolute param-stage replacement. The figures were asserted against one
    * leaf as it stood, so the spec carries that leaf and its content hash at
    * authoring time. Both are plain fields: an absolute statement without them
    * is not representable. */
  final case class LeafAbsolute(
    replacement: AbsoluteReplacement,
    anchor:      NodeId,
    baseStamp:   ContentHash
  ) extends LeafStageSpec

  /** A result-stage pipeline. The step-count rule is this case's own relation
    * over its one field, so it is held here rather than in `Mitigation.create`. */
  final case class ResultStage private (pipeline: TransformPipeline) extends MitigationSpec

  object ResultStage:
    /** The pipeline's step count. A pipeline with no steps has no effect. Above
      * ten, a pipeline is restating operations, since a realistic one uses at
      * most one of each of the five operation types. Both bounds are carried
      * over unchanged from `Mitigation.scala:109` and `:112`, where they are
      * declared today. */
    private val MinPipelineSteps = 1
    private val MaxPipelineSteps = 10

    def create(
      pipeline: TransformPipeline,
      location: ErrorLocation
    ): Validation[ValidationError, ResultStage]
```

**Why `ResultStage` moves too, and it is the same rule as §5.0's.** The step
count is a relation over `ResultStage`'s own field, and it currently lives in
`Mitigation.create`'s `stepsV` block (`Mitigation.scala:150-164`) — one level
above the type that owns it. So a `ResultStage` can be constructed with an empty
pipeline, and only the enclosing `Mitigation`'s construction refuses it. Moving
it is the same correction §5.3 makes for `requireMinBelowMax`, which also sat in
`RiskNode` rather than on the pair it is about.

**What that leaves `Mitigation.create`, stated exactly, because it is the cost of
the move.** Its two rules are `overrideRulesV` (`:125-148`) and `stepsV`
(`:150-164`), and they are combined at `:166-168`:

```scala
Validation
  .validateWith(overrideRulesV, stepsV) { (_, _) => () }
  .map(_ => new Mitigation(id, name, target, spec, precedence))
```

`LSA-D-1` deletes the first because it has nothing left to forbid, and this move
relocates the second. So `Mitigation.create` is left with no cross-field rule of
its own, and becomes the gate alone:

```scala
def create(
  id:         MitigationId,
  name:       SafeName.SafeName,
  target:     MitigationTarget,
  spec:       MitigationSpec,
  precedence: MitigationPrecedence
): Validation[ValidationError, Mitigation] =
  Validation.succeed(new Mitigation(id, name, target, spec, precedence))
```

That is the shape `ADR-001` §5 prescribes rather than a defect, and the
distinction is the one §5.0's test turns on: a `create` that is the sole gate of
an already-private constructor is required by the record whether or not it holds
a rule today, because the record's requirement is that "all construction routed
through a smart constructor" — not that the constructor check something. What
§5.0 rules out is the opposite case: adding a `create` to a type that has neither
a private constructor nor a rule, where it would be surface with no gate to keep
and nothing to check. `Mitigation.requireDistinctIds` (`:173`) is unaffected: it
is a rule over a collection of mitigations rather than over one mitigation's
fields, and its caller is `RiskTree.scala:187`.

**One ingress gap this closes, found while verifying the above.**
`MitigationSpec`'s decoder builds its cases directly — `yield LeafStage(t, h, a)`
at `Mitigation.scala:87` — so a stored mitigation is decoded without
`Mitigation.create` running, and today's `overrideRulesV` therefore does not
guard the store-read path at all. That is the shape `ADR-039` §3 names: "A
composite goes through its smart constructor, not its field types alone." Under
this ruling the gap is not closed by adding a call but by the state becoming
unrepresentable: a decoder reading `stage: "leaf"` with no stamp can only produce
`LeafRelative`, and one reading a stamp and an anchor can only produce
`LeafAbsolute`.

#### What this makes unwritable

`Mitigation.create`'s `overrideRulesV` block (`Mitigation.scala:125-146`) and its
predicate `hasOverrideComponent` (`:205-210`) both go: there is no combination
left to forbid. `MitigationStaleness.staleOverrides` loses its two `Some`
patterns and matches `LeafAbsolute` directly. The collect at
`MitigationApplication.scala:154` matches `s: LeafStageSpec` instead of
destructuring three fields and discarding two.

Authoring an absolute override without its base stamp stops being a rejected
request and becomes a program that does not compile.

#### What it does to the algebra

The absorbing element of §6.3 becomes a case of the type rather than a case
nested inside a component. `LeafAbsolute` *is* the constant map, visibly, and
`LeafRelative` visibly is not. The monoid statement of §6.4 is unchanged — both
cases are still arrows `LeafParams => F[LeafParams]`, and composition is still
Kleisli — but the law `Override(s) ∘ x = Override(s)` is now provable by matching
on the spec rather than by matching on a component inside a product.

#### The one cost, and the route through it

Mitigations are persisted: `RiskTreeRepositoryIrmin.scala:123` writes
`mitigations/<id>` as `m.toJson`. So unlike the shape change of §9, this one
reaches a stored format.

The same device applies. `MitigationSpec.Raw` (`Mitigation.scala:61-67`) is
already a flat product carrying `stage`, `transform`, `overrideBaseStamp`,
`overrideAnchor` and `pipeline`. A decoder can read `stage: "leaf"` with the
stamp and anchor present as `LeafAbsolute` and absent as `LeafRelative`, and an
encoder can write the same two fields back, so stored mitigations decode and
re-encode unchanged. **This is the route, not a verified fact** — the decoder has
not been written. The check that settles it already exists:
`MitigationPersistenceItSpec`'s "an override mitigation round-trips with its
overrideBaseStamp intact". §13 lists it.

#### One observation this ruling does not settle

`overrideAnchor` names one leaf, while `MitigationTarget.Predicate` can resolve
to many. So an absolute override whose predicate matches five leaves applies the
same replacement to all five while only one is stamped, and
`MitigationStaleness` audits only that one. Whether that is intended is a design
question about targeting rather than about this type, and `LSA-D-1` does not
answer it. It is recorded here so it is not lost.

---

## 7. Part 7 — the construction surface

`EWB-D-40` put the emphasis here: "which factory and `create` methods exist, what
each one guarantees on return, and which of them a caller can reach". This is
that inventory, before and after.

### 7.1 Today

| Entry point | Declared at | Guarantees on return | Reachable from |
|---|---|---|---|
| `Distribution.apply` | generated, **public** | nothing — no rule runs | anywhere; used at `TreeBuilderState.scala:447` |
| `Distribution.create` | `Distribution.scala:36` | mode rules, element ranges, ordering, term limit | anywhere |
| `RiskLeaf.create` | `RiskNode.scala:158` | mode rules and lengths; **no element range, no ordering** | anywhere, and the JSON decoder |
| `RiskLeaf.fromValidated` | `RiskNode.scala:129` | nothing — takes the six fields positionally | anywhere; used at `RiskTreeServiceLive.scala:265` |
| `OverrideDistributionParams.create` | `RiskLeafTransform.scala:90` | mode rules and lengths; **no element range, no ordering** | anywhere |
| `RiskLeaf`'s class invariant | `RiskNode.scala:99-107` | mode rules, as a `require` that throws | every construction path |
| `validateModeFields` | `RiskNode.scala:390` | mode rules and lengths | `private[data]` |

Four observations follow from the table, and each is a defect the derived type
removes.

**There are three unguarded ways in.** `Distribution.apply`,
`RiskLeaf.fromValidated` and the six-field positional call it takes. `ADR-001`'s
rule is that smart constructors are the only valid construction path, and
`.claude/rules/scala-domain.md` states it as "Smart constructors are the **only**
valid construction path."

**The guarded ways in do not agree.** `Distribution.create` enforces strictly
more than `RiskLeaf.create`, and §4.4 gives the consequence.

**The invariant is enforced twice, once as a type-adjacent `require` that
throws.** The leaf's `require` block restates the mode rules its own `create` has
already checked, and its comment says so: "Defense in depth: invariant check as
safety net / Should never trigger if custom decoder works correctly".

**The shape is unfolded into a product at the one site that should preserve it.**
`RiskTreeServiceLive.scala:265-277` takes a validated `Distribution` and spreads
its six fields across `RiskLeaf.fromValidated`'s parameters. Everything
`Distribution.create` proved is discarded at that call, and the leaf's `require`
re-derives part of it at runtime.

### 7.2 After

| Entry point | Guarantees on return | Reachable from |
|---|---|---|
| `QuantileAnchors.create` | count in range, strict ordering; elements by their field types | anywhere |
| `LossRange.create` | minimum below maximum; positivity by field types | anywhere |
| `DistributionShape.Expert.create` | term count at most the anchor count; everything below it by the anchors' own gate | anywhere; `apply` and `.copy` are private |
| `DistributionShape.Lognormal.apply` | everything, because `LossRange` carries the case's only relation | anywhere |
| `MitigationSpec.ResultStage.create` | pipeline step count between 1 and 10 | anywhere; `apply` and `.copy` are private |
| `RiskLeaf.fromValidated` | takes one `shape: DistributionShape`; nothing left to drop | anywhere |

**The structural change is that every relational rule ends up behind the private
constructor of the type it is a relation over, and nothing else is reachable.**
Four types hold a rule — `QuantileAnchors`, `LossRange`,
`DistributionShape.Expert` and `MitigationSpec.ResultStage` — and each has a
private primary constructor, so Scala 3 makes its `.copy` private too and the
`create` is the sole path in (`ADR-001` §5). `Lognormal` is the one case of the
coproduct left public, and §5.0's test is what licenses it: `LossRange` already
holds the only relation in that case, behind its own gate.

That is what makes the three unguarded entry points in §7.1 stop being holes,
rather than their being closed off one at a time. `Distribution.apply` goes with
its type. `RiskLeaf.fromValidated`'s six positional shape fields become one
already-gated value. And the six-field positional call `RiskLeaf.create` accepts
today cannot be written, because there is one shape parameter and no default
(§4.3a).

The leaf's `require` block is then deleted, because it states what the field's
type states.

---

## 8. Part 8 — what can no longer be written

Each item below is code that is deleted and cannot be reintroduced, because the
rule it implements has no expressible violation.

In `RiskNode.scala`:

- `validateExpertMode`'s four refusal arms for absent or empty sequences
  (`:257-284`) and its length-mismatch arm (`:235-240`).
- `validateLognormalMode`'s three absent-bound arms (`:306-308`) and the three
  helpers they call, `lognormalBothBoundsMissing`, `lognormalMinMissing` and
  `lognormalMaxMissing` (`:360-382`).
- `failOnUnknownDistributionType` (`:415-425`) — there is no third case to be
  unknown.
- `validateModeFields` (`:390-412`) in full; its surviving content is the two
  cases' own constructors.
- the class `require` block (`:99-107`).
- the five defaulted `Option` parameters of `RiskLeaf.create` that carry shape
  fields (`:163-166` and `:169`), because the six shape parameters become one
  `shape: DistributionShape` with no default. §4.3a gives the call they make
  possible today and why it stops compiling.
- `requireMinBelowMax` (`:328-342`) moves to `LossRange.create`, and
  `requireTermsWithinPercentiles` (`:344-358`) moves to the `Expert` case.

Three further defaulted `Option` parameters in that file — `parentId` at `:167`,
`:548` and `:602` — are **not** made unwritable by the new type. They are
`boyscout` obligations on a file the change edits, performed in the same pass and
listed with it rather than raised (§4.3a).

In `Distribution.scala`: the whole file, replaced by §5's four declarations. Its
mode dispatch and its eight refusal arms (`:61-104`) go with it. Per §4.5 this is
the file holding the strictest variant of five rules, and each lands somewhere
narrower rather than being dropped: the percentile and loss element checks
(`:65-72`) become `Probability` and `PositiveDouble`, the two ordering checks
(`:77-80`) become `QuantileAnchors.create`'s rule, the term-count lower bound
(`:84`) becomes `MetalogTerms`, and the term-count relation (`:82`) becomes
`DistributionShape.Expert.create`. `requireStrictlyIncreasing` (`:27-34`) moves
with them; its `arr.length >= 2` guard becomes unnecessary, because `MinAnchors`
makes a shorter sequence unconstructible.

In `RiskLeafTransform.scala`: `OverrideDistributionParams` in full, with its
three hand-written equality members. `LikelihoodTransform` and
`DistributionTransform` are replaced by the relative and absolute types of §6.6,
so the `Override` case of each goes and with it the possibility of an absolute
statement that carries no evidence.

In `Mitigation.scala` (`LSA-D-1`): `hasOverrideComponent` (`:205-210`) and the
four-arm `overrideRulesV` block in `Mitigation.create` (`:125-148`). There is no
combination left to forbid, because `LeafAbsolute` cannot be built without its
anchor and stamp and `LeafRelative` has nowhere to put them. The `stepsV` block
(`:150-164`) is not deleted but relocated, to `ResultStage.create`, which is the
type the step count is a relation over (§6.6). With both blocks gone from
`Mitigation.create` it keeps its private constructor and becomes the gate alone;
§6.6 writes out what that leaves and why `ADR-001` §5 prescribes it.

In `MitigationStaleness.scala` (`LSA-D-1`): the two `Some(...)` patterns in
`staleOverrides` (`:20-23`), which become a match on `LeafAbsolute`. The function
keeps its body — the staleness comparison itself is unchanged.

In `MitigationApplication.scala` (`LSA-D-1`): the three-field destructuring at
`:154` that discards two of them, which becomes a match on `LeafStageSpec`.

In `Simulator.scala`: the three shape-refusal arms (`:173-178`, `:200-205`,
`:208-213`) and the percentile refinement loop (`:141-149`).
`createDistributionWithParams` becomes a two-arm match whose only error paths
are `MetalogDistribution.fromPercentiles` and `fromLognormal90CI` returning a
`Left` — a genuine failure of the numerical fit. **That totality is the test of
this whole derivation, per §1.4, and §13 states it as a verification item.**

In `DistributionPreviewService.scala`: the five defaulted reads (`:47-49`,
`:74-75`), and with the minimum and maximum no longer defaulting to zero, the
impossible-input path into `fromLognormal90CI` as well.

In `DistributionSpecBuilder.scala`: the constant comparison at `:147` becomes a
two-arm match, and the two defaulted reads at `:149-150` go.

In `RiskLeafFormState.scala`: `currentShapeValidation` (`:387-411`) performs the
case split itself — four `mode match` expressions deciding which fields to
populate and setting the rest to `None` — and then flattens the result into
`Distribution.create`'s optional parameters. It becomes one match producing one
case.

In `Provenance.scala` (`LSA-D-2`, §5.6): the `distributionType: String` field of
`NodeProvenance` (`:26`), the widened assignment that fills it
(`Simulator.scala:113`), and the hand-written untagged encoder and `<>` fallback
decoder for `DistributionParams` (`:83-96`), which a derived discriminated codec
replaces. The field goes only after the tag moves inside the union — §5.6 states
why that order is not optional.

In `ValidationErrorCode.scala`: whether `UNSUPPORTED_DISTRIBUTION_TYPE`,
`INVALID_EXPERT_PARAMS` and `INVALID_LOGNORMAL_PARAMS` retain any construction
site is to be re-counted during implementation; each is currently raised only by
arms this part deletes.

---

## 9. Part 9 — which serialized bytes change, and what each change costs

This part was written as "every serialized byte stays the same", on the premise
that all three `Raw` types keep their flat declarations. **The ruling of
2026-10-22 on `LeafSimContent` overrides that for the cache key**: the hash
preimage is rewritten to the coproduct form, and its hashing function is adapted
with it, so the preimage gains one level of nesting. The leaf's own stored form
and the wire request are unaffected, and the reasoning for those two stands as
written below.

Three serialized forms touch the shape, and each is produced by an intermediate
`Raw` type that the domain type maps to and from:

- **The persisted leaf.** `RiskTreeRepositoryIrmin.scala:289` stores
  `leaf.toJson` and `:293` reads it back with `json.fromJson[RiskLeaf]`. The
  codec is built from `RiskLeafRaw` (`RiskNode.scala:435-447`), a flat product of
  primitives with the six shape fields optional.
- **The wire request.** `DistributionShapeRequest` (`:25-32`) is a flat product
  of primitives, and `ADR-038` §1 already places a wire shape inside the domain
  type's companion with the decoder routing through the smart constructor.
- **The cache key.** `LeafSimContent` (`:30-38`) is the leaf cache-key preimage,
  and its own documentation states the contract: "**Field order is a storage
  contract.** The zio-json derived encoder emits keys in declaration order;
  reordering fields changes every leaf cache key (mass cache miss, never
  incorrect results)." It is guarded by a byte-stability snapshot test in
  `LeafSimContentSpec`.

The first two keep their current flat declarations, and the domain type becomes a
coproduct above them: the leaf's decoder gains the case split that
`Distribution.create` performs today, and its encoder writes the same keys with
the same values. So there is no stored-tree migration, no client-visible wire
change, and the Irmin blob hashes — the second of `ADR-032`'s two hash
relations — stay identical because the leaf's bytes do.

**The cache key is the one that moves.** `LeafSimContent` now holds the shape as
one `DistributionShape` field and renders it through its own private `Raw`, which
nests the shape's keys under `shape` rather than flattening them beside
`seedVarId` and `probability`. The keys inside, their order and their number
rendering are unchanged; the preimage gains one level of nesting and therefore a
different hash. Two consequences, and both are bounded:

- **The result cache is in-memory.** `ContentCache` holds
  `Ref[Map[ContentHash, LeafSimResult]]`, empty at process start, so a changed
  key costs one re-simulation per leaf on the first request after deployment and
  nothing further.
- **The one persisted copy of a leaf hash is `MitigationSpec.LeafAbsolute`'s
  `baseStamp`**, compared against a recomputed hash in
  `MitigationStaleness.isStale` (`MitigationStaleness.scala:27`). Every stamp
  written under the old preimage therefore reads as stale. That function has no
  production caller — its only callers are its own spec — and its own
  documentation says "Diagnostic only: resolution ignores staleness", so no
  simulation result and no resolution outcome changes.

### 9.1 The one leaf for which that is false, and it is reachable today

The qualifier in the previous sentence is load-bearing, and it was found by
checking the claim rather than asserting it. **A leaf may legally populate the
other mode's fields, and those values are persisted and hashed.**

`Distribution.create`'s lognormal arm examines only the two bounds, and the value
it then builds carries the expert fields through untouched
(`Distribution.scala:96-107`):

```scala
case "lognormal" =>
  (minV, maxV) match {
    case (Validation.Success(_, Some(minv)), Validation.Success(_, Some(maxv))) if minv < maxv => Validation.succeed(())
    ...
  }
...
Validation.validateWith(distTypeV, minV, maxV, termsV, crossV) { (dt, min, max, t, _) =>
  Distribution(dt, min, max, percentiles, quantiles, t)
}
```

`percentiles`, `quantiles` and `t` are whatever the caller passed. The same holds
on the leaf's own path: `validateModeFields`'s lognormal arm reads only
`(minLoss, maxLoss)` (`RiskNode.scala:403-409`), and `validateExpertMode` reads
only the two arrays (`:226-285`), so neither mode rejects the other's fields.

So this is accepted today, stored, and hashed into the cache key:

```
{ "distributionType": "lognormal", "minLoss": 20, "maxLoss": 120,
  "percentiles": [0.1, 0.9], "quantiles": [5.0, 50.0], "terms": 3, ... }
```

It is a third instance of this plan's own subject — the product
over-represents — and the only one that reaches persisted bytes.

**`LSA-D-5` rules that such a record is refused rather than normalised**, at both
decode boundaries. The ruling's ground is that no such record exists: the
environment is a development one, no simulation cache is live, and nothing stored
predates this change, so there is no migration question to answer and the only
question is which behaviour is right from here on.

That makes §9's claim exact rather than qualified. There is no stored leaf whose
bytes change, because every stored leaf populates one mode; and there is no
record that decodes to something re-encoding differently, because a record that
would is refused at the boundary instead. **What does change is the request
path:** a direct API client submitting off-mode fields is refused where it is
accepted today, which §11 records as the ruling's one forward behaviour change.

`LeafSimContentSpec`'s byte-stability snapshot pins leaves built through the
browser's form, which populates one mode's fields and sets the other's to `None`
(`RiskLeafFormState.scala:387-411`), so it is expected to pass unmodified, and
§13 keeps it as the gate on the whole claim.

The change is still a guarded modification, because it changes public signatures
and domain types. `G1`'s echo and `G6`'s halt bind on every signature in §5 and
§6 that an approved plan does not yet contain verbatim.

---

## 10. Part 10 — consequences for the error-wire work

**Where this runs.** After `PLAN-ERROR-WIRE-BOUNDARY.md`'s Phase 3a completes and
before its Phase 3b starts. Phase 3b replaces `ValidationError.message` with a
structured problem type across 151 construction sites, 29 of which are in
`Distribution.scala` and `RiskNode.scala`. §8 deletes a substantial share of
exactly those 29. Converting them and then deleting them is the waste to avoid,
and that ordering is derivable rather than a choice.

**One location case changes home, one does not.** `FieldName.LossRange` becomes
the error location of `LossRange.create`, the single site that now holds the rule
— and it becomes a type name as well (§5.3).
`FieldName.PercentileQuantilePairing` moves from three domain smart constructors
to the two boundary decoders that zip two inputs (§4.2). Both cases were added
earlier in this work by `EWB-D-39` and both survive.

**The set-valued display mapping is unaffected.** `EWB-D-39` ruled that a
cross-field rule stamps the aspect that failed and that the display layer
resolves an aspect to one or more controls, through
`inputsFor(location): Set[RiskLeafField]`. Both surviving pair locations still
map to two controls each, for the reason that ruling gives, and nothing here
changes that.

---

## 11. Decisions

Five were opened and all five are ruled, each recorded below with its date.
`LSA-D-5` was opened while verifying §9's byte-stability claim during the
plan-gate pass and ruled in the same session. The plan has no open decision.

One obligation toward another document remains, and it is not a decision of this
plan: §5.6's deletion of `NodeProvenance.distributionType` makes a verbatim
quotation in [`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) stale.
That plan is approved and not yet implemented, so the amendment is presented to
the user rather than made here.

### LSA-D-1 — where an override's authoring evidence lives — RULED 2026-10-22

**Ruled: Option A as presented.** `LeafStage` splits into `LeafRelative` and
`LeafAbsolute` under a new intermediate `LeafStageSpec`, each component type
splits into its relative and absolute halves, and the stamp and anchor become
plain fields of the absolute case. §6.6 carries the derivation of why the
dependency exists, the exact signatures, what becomes unwritable, what it does to
the algebra, and the one cost — the persisted mitigation format — with the route
through it and the existing integration test that settles whether that route
holds.

The two options not taken, recorded so the choice is legible later. **Moving the
evidence into each `Override` case** would let a mitigation overriding both
components carry two stamps and two anchors that disagree, trading a presence
rule for an agreement rule. **Keeping the rule** would have left the plan
carrying an instance of the defect it was written to remove, in the override's
own type; it was the smaller change and the only one touching no persisted
mitigation format, which is why it was recommended before the `Raw`-level route
in §6.6 was worked out.

### LSA-D-2 — whether provenance keeps its own parameter type — RULED 2026-10-22

**Ruled: keep both types, because they say different things.**
`DistributionShape` is what an author declared; `DistributionParams` is what the
simulator consumed. §5.6 carries the consequence — the total function `resolve`
that relates them, which is where the authored `Option` disappears and where the
derived term count and the confidence level are applied, replacing the second
copy of `math.min(n, 4)` and the bare `0.90`.

**What §2's six declarations become, counted exactly.** Four of them are domain
declarations and they end as two: `DistributionShape`, and `DistributionParams`
as its named projection through `resolve`. `Distribution` and
`OverrideDistributionParams` are deleted, and `RiskLeaf` stops declaring the
fields and holds one `shape` instead. The other two —
`DistributionShapeRequest` and `LeafSimContent` — keep their flat shapes by
design, because §9 holds every serialized byte fixed and both exist only to
produce bytes. So the count is four domain declarations down to two, with two
serialization projections unchanged and deliberately flat.

Two consequences of the ruling, both already written in. The approved
[`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) needs no change to
its payload's parameter shape. And the discriminator finding below is in this
plan's scope by the user's instruction of 2026-10-22, with its derivation and its
two ordered moves in §5.6.

The reasoning that produced the ruling is kept below, because it is what makes
the two types distinguishable to a later reader.

**Two premises corrected, because both of mine were wrong.**

`NodeProvenance` is **not persisted and not on the wire today**. The Irmin writer
stores tree metadata, nodes and mitigations and nothing else;
`LeafSimResult.scala:22` states "Never serialized — it lives in an in-memory
`Ref` (ADR-015), so it has no codec"; `LECNodeCurve` has six fields and none is
provenance; and `probOfExceedance` returns a `Double`. The
`includeProvenance` query parameter defaults to `false` and only sets a trace
attribute. So the cost I first stated — "the persisted provenance JSON changes" —
does not exist.

**But it is about to be a wire shape, by an approved plan.**
[`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) is marked "Approved
— awaiting implementation", and its Phase 2 puts the record on the wire
unchanged:

```scala
.out(jsonBody[Map[NodeId, NodeProvenance]])
```

Its endpoint description names the payload as "HDR stream identities and
**distribution parameters**", it quotes `NodeProvenance` verbatim including
`distributionParams: DistributionParams`, and `ADR-003` Decision 4 names a
dedicated audit endpoint as the only surface provenance gets. That plan is not
yet implemented, so a ruling here that changed `DistributionParams` would
overwrite an approved artifact — which the standing rule on stale documents
reserves to the user rather than to this plan.

**What the two fields express, which is what the decision turns on.** Provenance
records not what the author submitted but **what the simulator consumed**. An
author entering five expert anchors and no term count submits `terms = None`;
the derivation resolves it to the anchor count, a five-term metalog is fitted,
and the record says `terms = 5`. If the derivation rule later gained a ceiling of
four, re-running the authored shape would give a four-term fit and different
figures, while re-running the record gives the original ones. That is not
hypothetical: the rule used to carry exactly that ceiling, and retiring it is
why the record's independence matters. The same
holds one level more implicitly for `confidenceInterval`: nothing the author
typed says 90, because the 90 lives in the method chosen
(`LognormalHelper.fromLognormal90CI`), and recording `0.90` pins which reading
was used.

That is also why an `Option` on `DistributionParams.terms` would be a defect
rather than a convenience. A record saying "terms: absent" forces its reader to
re-run the current rule to reproduce, reintroducing the dependency the record
exists to remove. The existing non-`Option` field at `Provenance.scala:41` is
right.

**Option A — replace `DistributionParams` with `DistributionShape`.** The domain
declarations end as one instead of two, and nothing migrates, because nothing is
stored. The
cost is the audit payload: either the resolved term count and the confidence
level move onto `NodeProvenance` as two fields of its own, or an auditor can no
longer tell which fit ran. And the approved plan's quoted wire shape changes,
which needs the user's sign-off on overwriting it.
*Concretely:* a provenance read for a leaf whose author named no term count would
report `terms: null`, and reproducing the run would mean re-deriving the default
from whatever rule is current at the time of the audit.

**Option B — keep both, and write the relation down.** `DistributionShape` is the
authored shape; `DistributionParams` is the resolved one; they are related by a
total function `resolve: DistributionShape => DistributionParams` that applies
`termsToFit` and names the confidence level. The duplication stops being
accidental and becomes a named projection. The approved plan's wire shape is
untouched. The cost is that two declarations of similar fields remain and a
reader has to be told why.
*Concretely:* the audit endpoint keeps reporting the exact term count and
confidence level the simulation used, and
[`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) needs no amendment.

**Why Option B was taken.** An audit surface whose stated purpose is
reproducibility has to carry what was consumed, not what was authored, and those
differ in exactly the two fields Option A would have to relocate. The condition
it holds under is that the audit endpoint is still wanted as `ADR-003`
Decision 4 describes it; if that endpoint were ever dropped,
`DistributionParams` would have no reader at all and Option A would become
right.

**The discriminator, which the user routed into this plan on 2026-10-22.**
`NodeProvenance` declares `distributionType: String` beside
`distributionParams`, and the string is derivable from the parameters' case — the
same flattening defect this plan is about, on a field that becomes a wire field
when the provenance endpoint ships. It is also a widened Iron type:
`Simulator.scala:113` assigns a `DistributionType` into a `String`, which
`ADR-038`'s "no raw primitive where a refined type exists" covers.

Investigating it found the reason it cannot simply be deleted:
`DistributionParams`'s codec is an untagged union, so that field is the payload's
only tag. §5.6 carries the derivation, the diagnostic failure mode the untagged
fallback produces, and the two ordered moves — tag the union with a derived
discriminated codec first, then delete the redundant field. §5.6 also records
that nothing decodes a `NodeProvenance` today, which is what makes the change
free now and breaking after that endpoint ships.

### LSA-D-3 — one sequence of pairs, or two sequences inside the case — RULED 2026-10-22

**What has to be decided.** §4.2 derives that the percentiles and quantiles are
co-indexed and therefore one sequence of pairs. The alternative is to keep two
parallel sequences inside the `Expert` case and keep an equal-length rule on that
case alone.

**Option A — one sequence of anchors**, as §5.1 and §5.2 declare. The
length-mismatch rule cannot be written in the domain at all, and it is held once
at each of the two zip boundaries. The cost is a mapping at every site that
currently reads the two arrays: the metalog fitter takes two arrays
(`Simulator.scala:152-156`), the wire carries two arrays, and the browser's chart
builder zips them back together at `DistributionSpecBuilder.scala:151`. §5.2's
`percentiles` and `losses` accessors are that mapping.
*Concretely:* submitting three percentiles and two quantiles is refused once, by
the decoder, naming the pair; today it is refused by whichever of three
implementations the path happens to run.

**Option B — two sequences inside `Expert`**, with one equal-length rule on the
case. No mapping is needed anywhere, because every consumer already wants two
arrays. The cost is that the rule survives, as a relation between two fields of
one case, and the type still admits the violation.
*Concretely:* the same refusal exists, raised by `Expert.create` instead of by
the decoder, and a future consumer can still receive unequal sequences from a
value that passed construction — it just cannot happen, because `create` is the
only way in.

**Ruled: Option A.** One sequence of anchors, as §5.1 and §5.2 declare, with
those two accessors as the mapping surface for the fitter, the codecs and the
chart builder. The length-mismatch rule becomes unwritable in the domain and is
held once at each of the two zip boundaries. Option B — two parallel sequences
inside the `Expert` case with an equal-length rule on it — was the fallback and
would have lost only that rule's unwritability, at the price of leaving one more
rule that a future construction site can forget.

### LSA-D-4 — ordering against `PLAN-RISKTRANSFORM`'s M4 — RULED 2026-10-22

**Ruled: this plan first, and as soon as the remaining decision is closed.** The
ground is that it removes duplication every later piece of work would otherwise
have to reason around, so correctness elsewhere is cheaper afterwards.
`PLAN-RISKTRANSFORM`'s M4 then writes its override edit interface against
`DistributionShape` from the start rather than against
`OverrideDistributionParams`, which §6.1 deletes.

**The one sequencing condition, which is about the working tree rather than the
design.** `PLAN-ERROR-WIRE-BOUNDARY`'s Phase 3a has steps 1 and 2 landed but
uncommitted, and two of the files they touch —
`domain/data/Distribution.scala` and `domain/data/RiskNode.scala` — are files
this plan deletes and rewrites. The approval token names one plan at a time, so
pointing it here while those edits are uncommitted both withdraws authorization
from them and leaves two plans editing the same files with different intents.
Phase 3a steps 1–2 are committed first, at the green boundary they are already
at; its steps 3, 4 and 5 then wait, which is a pause between steps rather than
abandoned scope.

### LSA-D-5 — what the leaf decoder does with an off-mode field — RULED 2026-10-22

**Ruled: refuse it, at both boundaries.** A record whose populated fields do not
match its stated mode is not a leaf any version of this type can represent, and
the decoder says so rather than discarding the surplus values.

**Superseded 2026-10-23, on cost.** The refusal was ruled to be implemented only
if it was cheap, where cheap meant the library's own strictness annotation
working without turning other tests red. It does not. `@jsonNoExtraFields` on
each case of a `@jsonDiscriminator` coproduct refuses the discriminator key
itself: annotating both cases made the mixed body fail as intended
(`Left("(lognormal)(invalid extra field)")`) and made **every valid body fail
too**, because `distributionType` is an undeclared field from the case decoder's
point of view. The remaining route is a hand-written coproduct decoder comparing
the key set against the named case, which is more than the ruling was worth.

**So the behaviour stands as it is:** a body naming one mode and carrying the
other's fields decodes to the named mode and the surplus values are discarded.
That is recorded on `DistributionShapeRequest`'s own scaladoc as behaviour rather
than as a benefit, so the next reader is not told it is a guarantee. The ground
that made the refusal cheap to adopt — nothing is stored — is the same ground
that makes leaving it cost nothing.

**The ground, which is the user's and settles the premise the decision turned
on.** There are no older records: the environment is a development one, no
simulation cache is live, and nothing stored predates the change. So the question
of what to do with an existing off-mode record is empty, and what remains is only
that the behaviour be consistent from here on. With no legacy data the refusal
costs nothing, and it is the stricter of the two behaviours, which is what §4.5's
rule prescribes when a surviving validation has more than one possible strength.

**What it changes going forward, stated because it is a real behaviour change.**
The request path accepts off-mode fields today (§9.1), so a direct API client that
submits `distributionType: "lognormal"` together with `percentiles` and
`quantiles` currently gets a stored leaf and will instead get a refusal. The
browser is unaffected: `RiskLeafFormState.currentShapeValidation` populates one
mode and sets the other's fields to `None`
(`RiskLeafFormState.scala:387-411`), so the single-page application cannot author
such a request and never could.

**The shape of the refusal** is `ADR-039` §4's: "An ingress rejection names the
boundary, the location within the payload, and carries the accumulated
`ValidationError` list." It is raised at the two boundaries that decode a leaf —
the request codec and the repository's read — because both are the same
`shapeFromRaw` gate (§5.7), so there is one implementation and no path that skips
it.

**The behaviour that was rejected**, recorded so it is not re-proposed: dropping
the off-mode fields silently. It would make a decode-then-encode normalise a
record rather than reproduce it, which is a persisted-shape change performed
without anyone being told. That cost is zero today for the same reason the
refusal's cost is zero — there is nothing stored — so the choice falls to which
behaviour is right from here on, and silently discarding author-supplied values
is not.

**A third behaviour was never available**, and recording why keeps it off the
table: holding the off-mode fields somewhere on the new type would reintroduce
exactly the product this plan replaces, in the one type whose shape the whole
derivation is about.

---

## 12. File inventory

`docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md` exists beside this
document and carries every path below as a bullet line; the user wrote it through
`.claude/bin/approve-inventory`, and `.claude/protocol/approved` names this plan.
The paths are what the derivation reaches, measured rather than estimated, and
they include the five files `LSA-D-1` added.

The four decision records §14.2 amends are **not** on this list and do not belong
on it. The approval hook gates source edits under `modules/` and `build.sbt`, and
the global instruction on inventory blocks puts decision records out of scope:
they are "edited directly instead".

```
docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md
new: modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala
modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala
modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala
modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala
modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala
modules/app/src/main/scala/app/state/RiskLeafFormState.scala
modules/app/src/main/scala/app/state/TreeBuilderState.scala
modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala
modules/app/src/main/scala/app/state/DistributionChartState.scala
modules/app/src/main/scala/app/views/DistributionChartView.scala
modules/app/src/main/scala/app/views/TreeDetailView.scala
modules/app/src/main/scala/app/views/TreePreview.scala
modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionPreviewResponse.scala
modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala
```

What `LSA-D-1` added, and why each: `Mitigation.scala` holds the `MitigationSpec`
split; `MitigationStaleness.scala` holds the only reader of the base stamp;
`ResultTransformSpec.scala` carries a doc-comment reference to
`DistributionTransform.ScaleSeverity` at `:32` that the rename makes false, which
the comment-currency rule requires fixing in the same pass;
`MitigationApplication.scala` and `RiskLeafTransform.scala` were already listed.

What §16 added, and why each: `DistributionPreviewResponse.scala` holds the
second `Array` field and the raw `String` mode field §16.3 retypes;
`InvalidationHandler.scala` holds the comparison workaround §16.2 quotes, whose
comment names two fields that §5 deleted. The `server-it` specs were added for
the fixture migration §13 requires. `TreeIndex.scala` and
`ContentHashIndex.scala` stay absent deliberately: they read `childIds` through
`.contains`, `.toList` and `.map`, so the sequence change reaches them without an
edit and gives the sweep no claim on either file.

Test sources in a module whose `src/main` the inventory names are reached by the
standing authorization, so they are not listed. `LeafSimContentSpec`'s
byte-stability snapshot is expected to pass unchanged (§9), which is itself a
verification item.

---

## 13. Verification plan

**The derivation's own test.** After implementation,
`Simulator.createDistributionWithParams` has exactly two error arms, both of them
a numerical fit returning a `Left`. If any arm still refuses a shape, the type
did not capture the invariant and the derivation is incomplete. This is §1.4's
totality criterion and it is checkable by reading one function.

**The law the new arrow owes.** §6.4 makes the leaf-stage transforms a Kleisli
monoid, so it owes what the result-stage side already has at
`ResultTransformInterpreter.scala:25` — identity on both sides, and
associativity:

```scala
test("Keep is the identity of leaf-param transform composition") {
  assertTrue(applyTo(identity, params) == Validation.succeed(params))
}
test("leaf-param transform composition is associative") { /* (a ∘ b) ∘ c == a ∘ (b ∘ c) */ }
test("Override absorbs what precedes it") {
  assertTrue(applyTo(overrideShape, params).flatMap(p => applyTo(scale, p)) != applyTo(overrideShape, params))
}
```

`.claude/rules/scala-test.md` requires law tests for every new type-class-shaped
instance, and `ADR-009` holds the existing pattern.

**The defect from §4.4.** One test authoring an override with a decreasing anchor
pair, asserting it is refused at construction. It fails before the change and
passes after, and it is also the single check that settles §4.4's PLAUSIBLE
verdict about the fitter's behaviour.

**Cache-key stability.** `LeafSimContentSpec`'s byte-stability snapshot pins the
preimage of the leaf cache key. The 2026-10-22 ruling rewrites that preimage to
the coproduct form, so the snapshot's two expected strings are updated once, to
the nested form §9 states, and the test's purpose is unchanged: any *later* change
to the preimage fails it rather than silently rekeying every entry. The snapshot
is the record of a deliberate rekey, so an edit to it outside this plan is the
defect it exists to catch.

**Round-trip stability, in two parts because §9.1 splits it.** A decode-then-
encode of a stored leaf JSON and of a stored mitigation JSON must produce
byte-identical output **for a record populating only its own mode's fields**,
which is what makes §9's no-migration claim true rather than asserted. A record
carrying off-mode fields is the `LSA-D-5` case, and what the test asserts there
is decided by that ruling: under Option A it pins the normalised output, under
Option B it pins the rejection and names the field.

**The two new construction gates** (§5.0). One test per rule, each asserting the
refusal at construction rather than downstream:
`DistributionShape.Expert.create` refuses a stated term count above the anchor
count, and `MitigationSpec.ResultStage.create` refuses an empty pipeline and one
of eleven steps. The second is a relocation rather than a new rule, so the
existing assertions on `Mitigation.create`'s step-count refusal move with it and
are not weakened — the refusal, its code and its location stay the same, only the
constructor raising it changes.

**The strictness audit's two findings each get a test, and both fail before the
change** (§4.5). A term count of one with five anchors is refused by
`MetalogTerms` at the boundary; today it is accepted by `RiskLeaf.create` and
surfaces as `DISTRIBUTION_FIT_FAILED` from the fitter on a stored node, so the
test pins which layer refuses it as much as that it is refused. And a leaf
submitted as lognormal with expert arrays is refused by `shapeFromRaw`
(`LSA-D-5`); today it is accepted and persisted.

**Every rule in §4.5's table has a test asserting the strict variant, not the
weak one.** The table is the checklist: the element ranges, both ordering rules,
the anchor-count bounds at 2 and 20, all three term-count rules, and the two
lognormal-bound rules. Four of them — the loss positivity, both ordering rules,
and the term-count lower bound — exist in only one of the five current sites, so
a test that passes against the weak variant would hide the regression this audit
exists to prevent.

**The mitigation format specifically**, which is `LSA-D-1`'s one cost.
`MitigationPersistenceItSpec`'s "an override mitigation round-trips with its
overrideBaseStamp intact" must pass **unmodified**. If it needs editing, the
`Raw`-level route described at the end of §6.6 does not hold and the split is a
stored-format migration rather than a free change. That test is the gate on the
ruling, not a confirmation of it.

**The provenance record's new codec** (`LSA-D-2`, §5.6). A round-trip test
pinning the discriminated form: each case encodes with its tag, decodes back to
the same case, and a malformed expert record now reports the expert decoder's own
complaint rather than falling through to the lognormal one. There is no existing
format to preserve here, because nothing decodes a `NodeProvenance` today, so
this test pins the new format rather than guarding an old one.

**`resolve` is total** (§5.6). A property test: for every
`DistributionShape`, `resolve` returns a `DistributionParams` of the matching
case, with the term count equal to the author's when stated and to
`anchors.termsToFit` when not.

**The compiler enumerates the split's call sites.**
`-Wconf:msg=match may not be exhaustive:error` is on build-wide in `build.sbt`,
so replacing `LeafStage` with two cases under `LeafStageSpec` turns every
non-exhaustive match on `MitigationSpec` into a compile error. The build is
therefore the search: no grep is needed to find what the split reaches, and a
clean compile of all four modules is evidence that nothing was missed.

**Commands that must be green**, from the `register-dev` skill, with the leaked
Docker state cleared before the integration tier as that skill requires:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
sbt "serverIt/test"
```

---

## 14. ADR alignment

| Record | What this plan touches | Status |
|---|---|---|
| ADR-001 (validation strategy) | the smart constructors, the construction surface | **Compliant, and it closes three violations.** §5 of the record is the governing clause — "Aggregate constructors are private — the smart constructor is the only gate", generalised "to every aggregate" — and §5.0 of this plan applies its test type by type. §7.1 lists the three current violations: `Distribution.apply`, `RiskLeaf.fromValidated`'s six positional shape fields, and the `TreeBuilderState.scala:447` call that uses the first. §7.2 removes all three, and the gate ends up behind the private constructor of each of the four types that hold a relational rule — `QuantileAnchors`, `LossRange`, `DistributionShape.Expert` and `MitigationSpec.ResultStage` — which is also where `.copy` stops being a second path in. **One named smell is used once and declared here rather than slipped in:** the record lists "❌ Bypassing with `.refineUnsafe` in production", and `QuantileAnchors.termsToFit` (§5.2) uses it. It is sound at exactly that site and nowhere else, because the only constructor bounds the anchor count to [2, 20] so the result is inside `MetalogTerms`' own [2, 20]. It is also not new: `Simulator.scala:150` performs the same `refineUnsafe` today, sound for the same reason but with the reason unstated, and the second copy of the derivation at `DistributionPreviewService.scala:49` computes a raw `Int` and skips refinement altogether. So the change goes from one unproven use plus one unrefined value to a single use carrying its proof. No amendment needed; the record already states the rule this makes true. |
| ADR-037 (in-code constants) | the mode discriminator, the confidence level, the derived term count, the defaulted parameters | **Compliant.** The `expert`/`lognormal` strings stop being a domain field and survive only as the persisted spelling, which is the record's §2 carve-out for a string contracted with something other than the serialiser. The bare `0.90` at `Simulator.scala:190` becomes `LognormalConfidenceInterval` with its origin stated, and the twice-written `math.min(n, 4)` becomes the single `termsToFit` method — whose body is now the anchor count, because the `4` had no origin to state and the rule obliges one. All eight forbidden `Option[T] = None` parameters in the two replaced files go, five of them unwritable under the new type and three as `boyscout` removals — §4.3a has the table and distinguishes them from the derived value, which is a method and not a parameter default. |
| ADR-018 (nominal wrappers) | `QuantileAnchor`, `QuantileAnchors`, `LossRange` | **Compliant.** Each is a nominal `case class` over refined types, which is the record's prescribed pattern, and none is a credential — so `ADR-022`'s opposing `final class` rule does not apply, per the Layer 3 collision row in the `adr-constraints` skill. |
| ADR-038 (response wire shapes) | `DistributionShapeRequest`, the leaf codec, the mitigation codec | **Compliant.** §9 keeps the wire shape inside the domain type's companion with the decoder routing through the smart constructor, which is the record's §1 pattern, and the module is cross-compiled so it holds on both sides of the wire at once. `LSA-D-1` applies the same device to `MitigationSpec.Raw`, so the split reaches no stored or wire byte — gated by the integration test §13 names. The finding this row once carried open — `NodeProvenance.distributionType` holding a widened `DistributionType` as a raw `String` — is closed by §5.6 deleting that field, so the record's "no raw primitive where a refined type exists" rule has nothing left to catch here. §16.3 applies the same rule to `DistributionPreviewResponse.distributionType`, which cannot be deleted because a client reading the curve needs the mode, and which takes the refined type instead. |
| ADR-039 (trust-boundary ingress) | the two zip boundaries | **Compliant, and it strengthens the ingress.** Under `LSA-D-3`'s Option A the pairing and ordering rules are enforced at the decoder, which is the border the record names, rather than three times inward of it. |
| ADR-032 (content equality relations) | the leaf cache key, the Irmin blob hash | **Compliant, and the record is amended.** The Irmin blob relation is untouched, because the leaf's own stored bytes are unchanged (§9). The domain-content relation's projection moves: `LeafSimContent` holds one `DistributionShape` and nests its keys under `shape`, so the preimage and therefore the key changes once. The record was amended in the same pass to state the projection in its coproduct form and to add the rule that the projection keeps its own private encoder rather than delegating to the wire codec. |
| ADR-014 (simulation result caching) | the leaf cache key | **Compliant.** The key moves once, which the record permits — it forbids *invalidation*, not a deliberate change of the hashed projection. The cache is an in-memory `Ref`, so a moved key costs one re-simulation per leaf after deployment and no stale entry can be served, because a key that is absent is recomputed rather than guessed. |
| ADR-034 (mitigation valuation model) | the leaf-stage transform algebra, and the no-elimination rule's enforcement points | **Compliant on substance, and the record is amended.** This plan touches the parameter-stage side only. The two folds, the result-stage pipeline and the no-elimination rule itself are untouched: the rule is carried by `RetentionFactor` and `ResidualProbability`, and §6.6 keeps both on the cases that replace the ones naming them. §6.5 makes the parameter-stage side match the standard the result-stage side already meets. **Amendment owed:** §6 names the rule's three type-level enforcement points — "`LikelihoodTransform.Scale`, `DistributionTransform.ScaleSeverity` and `ResultTransformSpec.ScaleLosses` all take a `RetentionFactor`" — and `LSA-D-1` renames the first two; the same section's "stated absolutely through an `Override`" names a case that becomes `AbsoluteReplacement`. Two names and one reference, edited in the same pass. |
| ADR-003 (seeding and provenance) | `NodeProvenance`, and the endpoint that will serve it | **Compliant on substance, and the record is amended.** `LSA-D-2` kept the record's parameter shape, so Decision 4's audit payload still reports what the simulation consumed — the resolved term count and the confidence level — which is what reproducibility requires. §5.6 removes the redundant `distributionType` field and tags the union instead; that changes the payload's tagging but not what it reports, and it is free today because nothing decodes the record yet. **Amendment owed:** Decision 4 writes `NodeProvenance` out verbatim including `distributionType: String`, so that code block is edited in the same pass — see the amendment list below. **One presentation is owed and not made:** [`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) quotes `NodeProvenance` verbatim at `:53-65` including the deleted field. It is approved and unimplemented, so that amendment waits on the user; none of its signatures is affected, because each names the record as a whole. |
| ADR-017 (tree API design) | the leaf request DTO, and the resource-limit table | **Compliant on substance, and the record is amended.** The request's field shape is unchanged (§9); only what the decoder builds from it changes. **Amendment owed:** §6's resource-limit table names where the expert point bound is enforced — "| Expert percentile/quantile points | 2 … 20 | `RiskLeaf.create` (`validateExpertMode`) |" — and §8 deletes `validateExpertMode` while §5.2 moves the bound to `QuantileAnchors.create`. The value 2 … 20 does not change; the cell naming its site does. |
| ADR-029 (input injection defence) | the stored node's string-bearing fields, and the two zip boundaries | **Compliant, strengthened, and the record is amended.** §3's Irmin GraphQL row rests on the premise that "every string-bearing field is refined — `SafeId`, `SafeName`, `NodeId`, `DistributionType` — so neither `RiskLeaf` nor `RiskPortfolio` has a free-text field". After this change the mode string is produced by matching on the case rather than carried as a refined field, so it is a compile-time literal — a strictly narrower guarantee than a refinement, by a different mechanism. **Amendment owed:** that row's reasoning is edited to say so, because as written it cites a field the leaf no longer has. §4 is untouched: no new output path consumes a user-input type. |

### 14.1 The records that bear and need no edit

Layer 1 of the `adr-constraints` skill routes by activity rather than by the
task's wording, and this change is simultaneously a domain-type change, a
wire-adjacent change, a service-method change, a simulation and caching change, a
mitigation-transform change, a frontend change and an error-handling change.
Eleven further records bear on it for those reasons and are compliant, each
checked against the plan rather than assumed:

| Record | Verdict |
|---|---|
| ADR-011 (import conventions) | Unused imports go as a `boyscout` obligation on every file the change edits. |
| ADR-010 (error handling) | Every new gate returns `Validation[ValidationError, T]` and accumulates. Nothing moves to an exception or to a string match. Whether `UNSUPPORTED_DISTRIBUTION_TYPE`, `INVALID_EXPERT_PARAMS` and `INVALID_LOGNORMAL_PARAMS` retain a construction site is re-counted during implementation (§8). |
| ADR-009 (risk aggregation) | Untouched: the change is to a leaf's input shape, not to `TrialOutcomes` or `PortfolioLosses`. |
| ADR-015 (the cached-result resolver) | Untouched, and nothing reintroduces the invalidation its retired sections describe. |
| ADR-019 (frontend architecture) | The seven app files keep their state layering. Replacing the constant comparison at `DistributionSpecBuilder.scala:147` with a two-arm match is the record's Pattern 6 — "a small set of named phases … modeled as a sealed enum/ADT" — rather than a departure from it. |
| ADR-030 (authorization boundary) | No service method's `using Checked[Permission]` posture changes. |
| ADR-033 (exception boundaries) | §8 deletes `RiskLeaf`'s `require` at `RiskNode.scala:99-107`. The record's Implementation table names `RiskPortfolio`'s `require`, which is a separate block at `RiskNode.scala:518` and survives, so no row goes stale. |
| ADR-035 (error leakage) | No new wire error case, and no message gains an internal value. |
| ADR-036 (confidential identifiers) | `LeafAbsolute.anchor` is a `NodeId`, which is not one of the record's confidential identifiers; nothing here names a `WorkspaceId`. |
| ADR-002 (logging and telemetry) | No log line is added, removed or changed. |
| ADR-032 (content equality relations) | Already in the table above, and re-verified: §1 writes `LeafSimContent` out with its eight flat fields, and §5.7 keeps that declaration, so the record's own code block stays accurate. |

**ADR-004a and ADR-007 do not bear.** They govern the storage layout and
scenario branching, and §9 is the reason the repository needs no edit:
`RiskTreeRepositoryIrmin.scala` is deliberately absent from §12's inventory.

### 14.2 The four amendments this plan carries

Each is a factual correction to a passage naming something this plan deletes or
renames. None reverses anything a record decided, which is why they are
amendments rather than proposals to overturn. They land in the same pass as the
implementation, and under the `adr-constraints` skill's rule for amending the
corpus each is checked against all three of its layers: all four are confined to
the record's own text, because no file is renamed, no record is added or retired,
and no new collision is created.

1. **ADR-003 Decision 4** — the `NodeProvenance` code block loses its
   `distributionType: String` line, and the `DistributionParams` hierarchy gains
   the tag (§5.6).
2. **ADR-034 §6** — `LikelihoodTransform.Scale` becomes
   `RelativeLikelihood.Scale`, `DistributionTransform.ScaleSeverity` becomes
   `RelativeSeverity.ScaleSeverity`, and the sentence naming `Override` names
   `AbsoluteReplacement`. `ResultTransformSpec.ScaleLosses` is unchanged.
3. **ADR-017 §6** — the expert-point row's enforcement cell becomes
   `QuantileAnchors.create`. The bound stays 2 … 20.
4. **ADR-029 §3** — the Irmin GraphQL row's reasoning stops citing
   `DistributionType` as a leaf field and states that the mode spelling is a
   matched literal.

---

## 15. What this plan does not do

- It does not change the mitigated valuation, the two folds, or anything in
  `MITIGATION-VALUATION-EXPLAINED.md`'s Part 11 settled list. That document's
  subject is the type of the value a fold *returns*; this plan's subject is the
  type of the shape a leaf *consumes*. They are opposite ends of the same
  pipeline and share only the vocabulary.
- It does not route `RiskLeaf.create` through a single shared rule set as a
  separate step. Under §5 there is one rule set because there is one type, so the
  routing `EWB-D-40` part 2 declined to do has nothing left to route.
- It does not place the standing directive that
  `MITIGATION-PORTFOLIO-CATEGORY-THEORY.md`'s closing section records — the
  requirement that every design assessment name the algebraic structure in play
  and show the operation preserves its laws. That document carries the proposed
  wording, it belongs in `~/.claude/CLAUDE.md`, and placing it is the user's.
  This plan's §1, §6.3 and §13 are what that directive asks for, applied here.

This list is bounded by §16, which was added later and reaches two types outside
the shape algebra. What that sweep deliberately leaves alone is listed in §16.7
rather than repeated here.

## 16. The closing typing sweep

This part was added to the plan after Parts 1–15 were ruled, and it is scope
rather than a decision. It runs last, because nothing in Parts 1–15 depends on
it and every item here is independent of the derivation.

### 16.1 The defect the derivation exposed, and why it reaches further than the shape

A Scala `case class` derives its `equals` by comparing each field with `==`, and
`Array.==` is reference identity rather than element comparison. So a case class
with an `Array` field has an `equals` that reports two structurally identical
values as different, and a `hashCode` that puts them in different buckets. The
type compiles, every call site type-checks, and the defect surfaces only as a
comparison that silently answers `false`.

§5.2's `QuantileAnchors` removes this from the shape by holding
`Vector[QuantileAnchor]`, so `DistributionShape.Expert` compares correctly. Two
case classes outside the shape still hold an `Array` field, and both are in
files this plan already edits or already owns the endpoint of.

**`RiskPortfolio.childIds`** (`RiskNode.scala:248`) is `Array[NodeId]`, so no
two portfolios are ever equal unless they are the same object.

**`DistributionPreviewResponse.points`** (`DistributionPreviewResponse.scala:33`)
is `Array[DistributionPreviewPoint]`, the body of the endpoint §9 keeps
byte-identical.

**Where `Array` legitimately survives, stated as the boundary the whole sweep
applies.** An `Array` is correct where an interface outside this codebase demands
one, and nowhere else. There is one such interface:
`metalog-distribution`'s fitter entry point is the Java method
`QPFitter.with(double[] p, double[] x, int terms)`, so a primitive array is what
it has to be handed. The conversion therefore belongs at that call and not above
it, and every type between an author's input and that call holds an immutable
sequence. Two sites perform the conversion —
`Simulator.scala:148` and `DistributionPreviewService.scala:57`, both of which
call `MetalogDistribution.fromPercentilesUnsafe`.

That boundary is what decides the sweep's extent in both directions. It is why
the shape's own chain — `QuantileAnchors`, `DistributionShape.Expert`,
`ExpertShapeRequest`, `ExpertDistributionParams` and the three private raw types
beneath them — all become `Vector[Double]`, and it is why
`MetalogDistribution`'s own signatures are left alone (§16.7).

### 16.2 The workaround that already exists, which is what makes this a simplification

`InvalidationHandler.nodeContent` (`InvalidationHandler.scala:227-234`) exists
because of this defect, and says so in place:

```scala
/** Content comparison key for a node. Case-class `==` is unusable here:
  * `RiskLeaf.percentiles`/`quantiles` and `RiskPortfolio.childIds` are
  * arrays, which compare by reference — and every mutation rebuilds the
  * tree, so untouched nodes would always look "changed" (over-notifying
  * every portfolio on every PUT). The JSON codecs are deterministic and
  * byte-stable, so encoded equality is content equality.
  */
private def nodeContent(node: RiskNode): String = node.toJson
```

Two things follow. The comment is already half false: `RiskLeaf.percentiles` and
`RiskLeaf.quantiles` do not exist under §5, because the leaf holds a
`DistributionShape` and that type holds a `Vector`. And once `childIds` is a
`Vector` the stated reason is gone entirely, so case-class `==` becomes usable
for a `RiskNode` and the per-comparison JSON encode is no longer forced.

So the sweep is not only a hardening against a comparison nobody writes today.
It removes the cause of a workaround that is written today, on the invalidation
path that decides which nodes a write notifies.

### 16.3 The signatures

The shape's own chain, from the anchors down to the two boundary types. The
`fromPairedArrays` rename is forced: a name stating "arrays" would be false of
its own parameters.

```scala
// DistributionShape.scala — the anchors and their two derived sequences
final case class QuantileAnchors private (values: Vector[QuantileAnchor]):
  def percentiles: Vector[Double]
  def losses:      Vector[Double]

def fromPairedSequences(
  percentiles: Vector[Double],
  losses:      Vector[Double],
  subject:     ErrorSubject
): Validation[ValidationError, QuantileAnchors]

// DistributionShape.scala — the expert case's construction gate
def fromPrimitives(
  percentiles: Vector[Double],
  quantiles:   Vector[Double],
  terms:       Option[Int],
  subject:     ErrorSubject
): Validation[ValidationError, Expert]

// DistributionShape.scala — private, codec only
private case class ExpertRaw(
  percentiles: Vector[Double],
  quantiles:   Vector[Double],
  terms:       Option[Int]
) extends ShapeRaw

// LeafSimContent.scala — private, encoder only, the cache-key preimage
private case class ExpertRaw(
  percentiles: Vector[Double],
  quantiles:   Vector[Double],
  terms:       Option[Int]
) extends ShapeRaw

// Provenance.scala — the simulator's audit record, and its private raw
final case class ExpertDistributionParams(
  percentiles: Vector[Double],
  quantiles:   Vector[Double],
  terms:       MetalogTermCount
) extends DistributionParams

private case class Raw(percentiles: Vector[Double], quantiles: Vector[Double], terms: Int)

// DistributionShapeRequest.scala — the submitted form
final case class ExpertShapeRequest(
  percentiles: Vector[Double],
  quantiles:   Vector[Double],
  terms:       Option[Int]
) extends DistributionShapeRequest
```

`RiskPortfolio` and its four construction and codec entry points:

```scala
final case class RiskPortfolio private (
  @jsonField("id") @encodedName("id") safeId: SafeId.SafeId,
  @jsonField("name") @encodedName("name") safeName: SafeName.SafeName,
  parentId: Option[NodeId],
  childIds: Vector[NodeId]
) extends RiskNode

def create(
  id:       String,
  name:     String,
  childIds: Vector[NodeId],
  parentId: Option[NodeId],
  subject:  ErrorSubject = ErrorSubject.Request
): Validation[ValidationError, RiskPortfolio]

def createFromStrings(
  id:       String,
  name:     String,
  childIds: Vector[String],
  parentId: Option[NodeId],
  subject:  ErrorSubject = ErrorSubject.Request
): Validation[ValidationError, RiskPortfolio]

def fromValidated(
  id:       SafeId.SafeId,
  name:     SafeName.SafeName,
  childIds: Vector[NodeId],
  parentId: Option[NodeId]
): RiskPortfolio

private case class RiskPortfolioRaw(
  id:       String,
  name:     String,
  parentId: Option[String],
  childIds: Vector[String]
)
```

The preview response, which changes in two ways at once — the sequence and the
mode field:

```scala
final case class DistributionPreviewResponse(
  distributionType: DistributionType,
  resolvedTerms:    Option[Int],
  anchorCount:      Option[Int],
  points:           Vector[DistributionPreviewPoint]
)
```

`DistributionType` is `String :| Match["^(expert|lognormal)$"]`
(`OpaqueTypes.scala:300`), and the two values it admits are already named:
`IronConstants.Expert` and `IronConstants.Lognormal` (`OpaqueTypes.scala:178-179`).
The field is a raw `String` today and is filled from two bare literals —
`distributionType = "expert"` (`DistributionPreviewService.scala:72`) and
`distributionType = "lognormal"` (`:92`). That is the same defect §5.6 removes
from `NodeProvenance`, and §14's ADR-038 row names the pattern. Here
the field cannot be deleted, because a client reading the curve needs the mode,
so it takes the refined type instead.

### 16.4 What the type change deletes rather than edits

Three pieces of code exist only because `Array` permits `null` and permits
emptiness, and the `Vector` signature retires them:

- `require(childIds != null && childIds.nonEmpty, ...)` (`RiskNode.scala:251`).
  The `null` half is unreachable from Scala and the non-empty half is already
  enforced by `create`. The invariant check goes.
- `Option(childIds).getOrElse(Array.empty[NodeId])` (`RiskNode.scala:296`), which
  is the same `null` defence inside the smart constructor.
- `.toArray` at `RiskTreeServiceLive.scala:276`, `DistributionPreviewService.scala:89`
  and `:99`, each of which becomes `.toVector` or disappears.

Every reader of `childIds` is already sequence-generic and needs no edit:
`TreeIndex.scala:147` calls `.toList`, `:236` calls `.contains`,
`CachedResultResolverLive.scala:151` calls `.toList`,
`ContentHashIndex.scala:36` calls `.map`, and `TreeDetailView.scala:61` calls
`.length`. The browser's `DistributionSpecBuilder.makeDataValues` takes the
points sequence and its parameter type moves with the field (`:127`).

### 16.5 Two bare literals the same pass names

`RiskNode.scala:301` and `:308` build their user-facing messages inline —
`"childIds array must not be empty"` and the count message interpolating
`MaxChildren`. ADR-037 puts a user-facing sentence in a named template, and a
template taking a parameter becomes a function of it. Both move to
`ValidationMessages`, which this plan already edits. The word "array" leaves the
first message with the type.

### 16.6 Two items that are conditional, and stop rather than improvise

**`nodeContent` becoming `==`.** Once §16.3 lands, `nodeContent`
(`InvalidationHandler.scala:234`) can compare nodes directly instead of encoding
each to JSON. The premise that structural equality and encoded equality agree
here is **not verified**, and the single check that settles it is
`InvalidationHandlerSpec` passing **unmodified** — the same device §13 applies to
`MitigationPersistenceItSpec`. If it needs editing, the two relations disagree,
the replacement does not land, and `nodeContent` keeps its JSON route with a
corrected comment. The comment correction is unconditional either way, because
the current wording names two fields that no longer exist.

**`Schema.any`.** Four sites declare a Tapir schema as `Schema.any`, which
publishes the type as an unconstrained object: `Mitigation.scala:287` and `:303`,
`Provenance.scala:103`, and `DistributionPreviewResponse.scala:38`. Replacing one
with `Schema.derived` changes the generated interface description, which is
Decision Trigger 1, and whether each type derives at all is **not verified** —
each holds Iron opaque types and one holds a `java.time.Instant`. The rule for
this pass: attempt `Schema.derived` for each, and keep the result only where it
compiles and the published document gains the type's real shape without renaming
a field or changing whether one is required. Any site that fails either half
keeps `Schema.any` and is reported, not forced.

### 16.7 What this sweep does not reach

Named so that each is a record rather than a silent omission.

- **A non-empty sequence type for `childIds`.** `Vector` still permits empty, so
  `create`'s non-emptiness rule stays a runtime check rather than becoming the
  field's type. Carrying it in the type is the deeper correction and it changes
  the decoder's failure shape, so it is a separate change.
- **The repository-wide defaulted-`Option` set.** `Option[T] = None` as a default
  parameter is forbidden by ADR-037, and a sweep found it at roughly thirty sites
  across all four modules, most of them in files this plan never opens. The
  `boyscout` obligation drains these as files are edited for other reasons, which
  is the mechanism that reaches them without an unbounded pass here.
- **`MetalogDistribution`'s cast and its string errors.**
  `percentiles.asInstanceOf[Array[Probability]]`
  (`MetalogDistribution.scala:142`) is an unchecked cast, and that file reports
  failures as `String` rather than as `ValidationError`. It is the bridge to the
  `metalog-distribution` library, whose `QPFitter.with(double[], double[], int)`
  fixes `Array` at that boundary, so it is where `Array` legitimately survives.
  Correcting its error channel is a change to a third-party bridge and is not
  this plan's subject.

### 16.8 Verification

The commands are §13's, unchanged. What this part adds to them:

- `RiskPortfolioSpec` and `TreeIndexSpec` pass with no assertion edited. Both
  already assert through `.length` and indexing, so a correct change touches
  neither.
- `InvalidationHandlerSpec` passes **unmodified**, which is the gate on §16.6's
  first item rather than a confirmation of it.
- A new test asserting that two structurally identical portfolios are equal,
  which is the defect stated as an assertion and is impossible to write today.
- The preview endpoint's response bytes are unchanged: `Vector` and `Array` both
  encode to a JSON array, and `DistributionType` is a refined `String` that
  encodes as the same string. `DistributionPreviewControllerSpec` and
  `DistributionPreviewServiceSpec` pass with no assertion edited.
- `LeafSimContentSpec`'s byte-stability snapshot stays green, which it must,
  because this part touches no leaf projection field.
