# PLAN-LOSS-SHAPE-ALGEBRA — the loss distribution shape and the transforms over it, derived

**Plan code `LSA`.** Decisions in this document are labelled `LSA-D-1` upward.

**Status: derivation complete, all four decisions ruled, awaiting the plan-gate
pass.** The replacement type is derived, its signatures are written out below,
and `LSA-D-1` to `LSA-D-4` are all ruled (2026-10-22) with their consequences
written into §5.6, §6.6, §8, §12, §13 and §14. What remains before any source
edit: this document is presented for an accepted signal, the user creates the
inventory document §12 names, and the user refreshes the approval token to point
at this plan. Until then it confers no implementation coverage.

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
— `DistributionPreviewService.scala:46-48`

```scala
val minLoss = dist.minLoss.getOrElse(0L)
val maxLoss = dist.maxLoss.getOrElse(0L)
```
— `DistributionPreviewService.scala:72-73`

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
  and `DistributionPreviewService.scala:48`. So the `Option` is real, it belongs
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

---

## 5. Part 5 — the derived type

Three declarations, in the order their dependencies require. All live in
`modules/common/src/main/scala/com/risquanter/register/domain/data/`, cross-compiled
to the browser, so the browser's form builds the same type the server validates.

One new Iron refinement is needed, co-located with its siblings in
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

  /** The term count a fit will use when the author named none: the anchor count,
    * capped at four. More terms than anchors cannot be fitted, and four is where
    * added terms stop improving a typical expert curve. */
  def termsToFit: PositiveInt = math.min(values.length, 4).refineUnsafe

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
  final case class Expert(
    anchors: QuantileAnchors,
    terms:   Option[PositiveInt]
  ) extends DistributionShape

  /** Lognormal: the BCG method fitted to a 90% confidence interval. */
  final case class Lognormal(bounds: LossRange) extends DistributionShape

  /** The confidence level the lognormal bounds are read at. Fixed by the BCG
    * method rather than configurable; it is recorded in provenance as 0.90 at
    * `Simulator.scala:190` today. */
  val LognormalConfidenceInterval: Double = 0.90
```

`Expert.terms` keeps its `Option` under §4.3's test, and `requireTermsWithinPercentiles`
(`RiskNode.scala:344-358`) survives as a rule on the `Expert` case alone, where
it is a relation between two of its own fields.

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
`math.min(n, 4)` at `DistributionPreviewService.scala:48` and the bare `0.90` at
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

  final case class ResultStage(pipeline: TransformPipeline) extends MitigationSpec
```

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
| `DistributionShape.Expert.apply` | everything, because both fields already carry it | anywhere, harmlessly |
| `DistributionShape.Lognormal.apply` | everything, for the same reason | anywhere, harmlessly |
| `RiskLeaf.fromValidated` | takes one `shape: DistributionShape`; nothing left to drop | anywhere |

The structural change is that the case constructors become safe to leave public.
A `DistributionShape.Expert` can only be built from a `QuantileAnchors`, which
can only be built by its own `create`. The gate moves down one level, to the two
types that hold genuine relational rules, and above that level there is nothing
left for a gate to check. That is what makes the three unguarded entry points in
§7.1 stop being holes rather than being closed off one by one.

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

In `Distribution.scala`: the whole file, replaced by §5's three declarations. Its
mode dispatch and its eight refusal arms (`:61-104`) go with it; the element and
ordering checks it alone performs move into `QuantileAnchor`'s field types and
`QuantileAnchors.create`.

In `RiskLeafTransform.scala`: `OverrideDistributionParams` in full, with its
three hand-written equality members. `LikelihoodTransform` and
`DistributionTransform` are replaced by the relative and absolute types of §6.6,
so the `Override` case of each goes and with it the possibility of an absolute
statement that carries no evidence.

In `Mitigation.scala` (`LSA-D-1`): `hasOverrideComponent` (`:205-210`) and the
four-arm `overrideRulesV` block in `Mitigation.create` (`:125-146`). There is no
combination left to forbid, because `LeafAbsolute` cannot be built without its
anchor and stamp and `LeafRelative` has nowhere to put them.

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

In `DistributionPreviewService.scala`: the five defaulted reads (`:46-48`,
`:72-73`), and with the minimum and maximum no longer defaulting to zero, the
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

## 9. Part 9 — every serialized byte stays the same

This is the finding that decides the plan's cost, and it corrects `EWB-D-40`'s
own statement that the change "alters a persisted shape and the leaf's wire
representation".

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

In all three the serialized bytes are produced by the `Raw` type, not by the
domain type. **So all three keep their current flat declarations, and the domain
type becomes a coproduct above them.** `LeafSimContent.from` matches on the shape
and fills the same flat fields in the same order; the encoder is untouched; every
leaf cache key is unchanged and the snapshot test passes as it stands. The leaf's
decoder gains the case split that `Distribution.create` performs today, and the
encoder writes the same keys with the same values.

What follows: no stored-tree migration, no mitigation-blob migration, no cache
invalidation, no client-visible wire change, and the Irmin blob hashes — the
second of `ADR-032`'s two hash relations — stay identical because the bytes do.

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

Four were opened and all four are ruled, each recorded below with its date. The
plan has no open decision.

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
`Simulator.scala:150` resolves it to `math.min(5, 4) = 4` and a four-term metalog
is fitted; the record says `terms = 4`. If the derivation rule later became
`math.min(n, 6)`, re-running the authored shape would give a six-term fit and
different figures, while re-running the record gives the original ones. The same
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

---

## 12. File inventory

The user creates `docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA-INVENTORY.md`. The paths
below are what the derivation reaches, measured rather than estimated, and they
include the five files `LSA-D-1` added. They are ready to paste into
`.claude/protocol/pending` once `LSA-D-2` is ruled; a ruling of Option A there
adds nothing to the list, because `Provenance.scala` is already on it.

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
```

What `LSA-D-1` added, and why each: `Mitigation.scala` holds the `MitigationSpec`
split; `MitigationStaleness.scala` holds the only reader of the base stamp;
`ResultTransformSpec.scala` carries a doc-comment reference to
`DistributionTransform.ScaleSeverity` at `:32` that the rename makes false, which
the comment-currency rule requires fixing in the same pass;
`MitigationApplication.scala` and `RiskLeafTransform.scala` were already listed.

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

**Cache-key stability.** `LeafSimContentSpec`'s byte-stability snapshot must pass
**unmodified**. If it needs editing, §9's claim is wrong and the change is a mass
cache miss rather than a free one.

**Round-trip stability.** A decode-then-encode of a stored leaf JSON and of a
stored mitigation JSON must produce byte-identical output, which is what makes
§9's no-migration claim true rather than asserted.

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
| ADR-001 (validation strategy) | the smart constructors, the construction surface | **Compliant, and it closes three violations.** The record's rule is that smart constructors are the only construction path; §7.1 lists `Distribution.apply`, `RiskLeaf.fromValidated`'s six positional shape fields, and the `TreeBuilderState.scala:447` call that uses the first. §7.2 removes the need for all three by moving the gate onto the two types that hold relational rules. No amendment needed — the record already states the rule this makes true. |
| ADR-037 (in-code constants) | the mode discriminator, the confidence level, the derived term count, the defaulted parameters | **Compliant.** The `expert`/`lognormal` strings stop being a domain field and survive only as the persisted spelling, which is the record's §2 carve-out for a string contracted with something other than the serialiser. The bare `0.90` at `Simulator.scala:190` becomes `LognormalConfidenceInterval` with its origin stated, and the twice-written `math.min(n, 4)` becomes the single `termsToFit` method. All eight forbidden `Option[T] = None` parameters in the two replaced files go, five of them unwritable under the new type and three as `boyscout` removals — §4.3a has the table and distinguishes them from the derived value, which is a method and not a parameter default. |
| ADR-018 (nominal wrappers) | `QuantileAnchor`, `QuantileAnchors`, `LossRange` | **Compliant.** Each is a nominal `case class` over refined types, which is the record's prescribed pattern, and none is a credential — so `ADR-022`'s opposing `final class` rule does not apply, per the Layer 3 collision row in the `adr-constraints` skill. |
| ADR-038 (response wire shapes) | `DistributionShapeRequest`, the leaf codec, the mitigation codec | **Compliant.** §9 keeps the wire shape inside the domain type's companion with the decoder routing through the smart constructor, which is the record's §1 pattern, and the module is cross-compiled so it holds on both sides of the wire at once. `LSA-D-1` applies the same device to `MitigationSpec.Raw`, so the split reaches no stored or wire byte — gated by the integration test §13 names. **One finding against the record is open and routed by `LSA-D-2`:** `NodeProvenance.distributionType` is a `String` holding a widened `DistributionType` (`Simulator.scala:113`), and that record becomes a wire type when `PLAN-PROVENANCE-ENDPOINT.md` ships. "No raw primitive where a refined type exists" covers it, and the field is additionally derivable from `distributionParams`'s case. |
| ADR-039 (trust-boundary ingress) | the two zip boundaries | **Compliant, and it strengthens the ingress.** Under `LSA-D-3`'s Option A the pairing and ordering rules are enforced at the decoder, which is the border the record names, rather than three times inward of it. |
| ADR-032 (content equality relations) | the leaf cache key, the Irmin blob hash | **Compliant and unchanged.** §9 leaves `LeafSimContent`'s encoder and every stored byte identical, so both of the record's two hash relations are untouched. |
| ADR-014 (simulation result caching) | the leaf cache key | **Compliant and unchanged**, for the same reason. No key moves, so no entry is invalidated. |
| ADR-034 (mitigation valuation model) | the leaf-stage transform algebra | **Compliant.** This plan touches the parameter-stage side only. The two folds, the result-stage pipeline and the no-elimination rule are untouched, and §6.5 makes the parameter-stage side match the standard the result-stage side already meets. |
| ADR-003 (seeding and provenance) | `NodeProvenance`, and the endpoint that will serve it | **Compliant. `LSA-D-2` kept the record's parameter shape**, so Decision 4's audit payload still reports what the simulation consumed — the resolved term count and the confidence level — which is what reproducibility requires. §5.6 removes the record's redundant `distributionType` field and tags the union instead; that changes the payload's tagging but not what it reports, and it is free today because nothing decodes the record yet. **One presentation is owed and not made:** [`PLAN-PROVENANCE-ENDPOINT.md`](PLAN-PROVENANCE-ENDPOINT.md) quotes `NodeProvenance` verbatim at `:53-65` including the deleted field. It is approved and unimplemented, so the amendment waits on the user; none of its signatures is affected, because each names the record as a whole. |
| ADR-017 (tree API design) | the leaf request DTO | **Compliant and unchanged.** The request's field shape is unchanged (§9); only what the decoder builds from it changes. |

No record requires amendment under the recommended options. `LSA-D-1`'s Option A
and `LSA-D-2`'s Option A would each change a persisted shape, and either would
oblige an `ADR-038` re-reading before implementation.

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
