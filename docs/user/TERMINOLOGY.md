# Risk Register Terminology

This document explains the domain concepts and how they work: what a risk,
a loss, a loss shape, a loss distribution, an LEC and a mitigation are, how
risks compose, and the workflow from defining risks to a curve. It is the place
to start on anything to do with simulation or mitigation.

A mitigation reduces risk in one of two places — before the simulation runs, or
after it — and the two behave differently enough that the terms for them are
separate. What makes them behave differently is set out under Mathematical
Structure, which defines the four properties the design turns on: the monoid,
the homomorphism, the fold, and the difference between a product and a
coproduct.

It also defines the two kinds of sensitive value the system handles — a secret
and a confidential identifier — because they are protected in opposite
directions and the rules about them only make sense once the terms are clear.

How the system is built is in [docs/dev/ARCHITECTURE.md](../dev/ARCHITECTURE.md).

## Core Concepts

### Risk
An **uncertain event** with two parts, and only these two:
- **Likelihood**: the occurrence probability — how often the event happens, a
  number in `[0, 1]`, drawn per trial as a Bernoulli outcome
- **Impact**: the **loss shape** — given that the event happens, the
  distribution the loss is drawn from

A risk leaf carries four further fields: its identifier, its display name, its
parent link, and the random-number stream it draws from. None of them describes
the risk. That distinction matters once mitigation is reached, because a
mitigation can change the two parts above and nothing else.

The two parts are sampled by the two methods a risk node's sampler exposes:

```scala
def sampleOccurrence(trial: Long): Boolean  // Did it happen?
def sampleLoss(trial: Long): Long           // If yes, how much?
```

### Loss
The **monetary consequence** when a risk materializes. A single Long value per trial representing the financial impact (typically in millions: 1L = $1M).

### Loss Shape

The **distribution the loss is drawn from**, given that the event happens. It is
what the author states about severity, and it is an input to the simulation. It
is not the simulation's output — that is the loss distribution below.

An author states a shape in one of two **modes**, and every shape is exactly one
of them:

```scala
sealed trait DistributionShape

final case class Expert(anchors: QuantileAnchors, terms: Option[MetalogTermCount])
  extends DistributionShape

final case class Lognormal(bounds: LossRange) extends DistributionShape
```

**Lognormal** is the shorter statement. The author gives two loss bounds, which
are read as the 5th and 95th percentiles — a 90% confidence interval — and a
lognormal curve is fitted through them. The 90% level is fixed by the fitting
method rather than chosen per leaf. `LossRange` is the pair of bounds, and its
one rule is that the lower bound is strictly below the upper.

**Expert** is the longer statement. The author gives points on the curve
directly: a 10% chance the loss is under 1 million, a 50% chance under 5 million,
a 90% chance under 25 million. A metalog quantile function is then fitted through
those points. Each point is a **quantile anchor** — a percentile paired with the
loss at that percentile. A shape carries between two and twenty anchors,
strictly increasing in both percentile and loss. Those bounds come from the
metalog fitter's own valid term range, because a fit cannot use more terms than
it has points. `terms` is how many metalog terms to fit; left absent, one term
per anchor is used, which is the fit passing through every anchor the author
stated.

The mode is the case, rather than a label read alongside optional fields. That is
what keeps a shape from carrying an expert field and a lognormal field at once,
and it is why no rule has to state which fields each mode populates. The general
form of that argument is under Product and Coproduct below.

### Loss Distribution
The **complete empirical distribution** from Monte Carlo simulation, backing the LEC function.

**Storage:**
- `outcomes: Map[TrialId, Loss]` - Raw trial data (sparse storage)
- `outcomeCount: TreeMap[Loss, Int]` - Frequency histogram

**Implementation:**
```scala
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,                       // after this node's mitigation layer
  source: TrialOutcomes,                       // the figures that layer was applied to
  applied: List[MitigationApplicationRecord],  // empty when nothing is applied here
  provenance: Option[NodeProvenance]
) extends LECCurve
```

There are no subtypes. One concrete type serves a simulated leaf and an
aggregated portfolio alike — the difference between them is internal to the
resolver and is not something a consumer can observe or match on.

The claim that a portfolio's total equals the combine of its children is not
held on this type. It is held one level below, on an internal aggregate type
whose constructor computes the total *from* the children, so the claim cannot be
made falsely. The reason the public value must not carry that claim is under The
Two Valuations below.

`trials` against `source` is the **layer** at this node, which has its own entry.
`applied` is empty where nothing is scoped, and the two sets of figures are then
the same object rather than two equal copies.

### LEC Curve (Loss Exceedance Curve)
The **mathematical function**: `Loss → Probability`

Represents: **P(Loss ≥ threshold)** for any threshold value.

**Conceptual interface:**
```scala
trait LECCurve {
  def probOfExceedance(threshold: Loss): Double
  def maxLoss: Loss
  def minLoss: Loss
  def nTrials: Int
}
```

**Practical implementation:** `LossDistribution` implements `LECCurve` via its simulation data.

### LEC Curve Response
The **serialized representation** of an LEC curve for API responses.

**Structure:**
```scala
final case class LECCurveResponse(
  id: String,
  name: String,
  curve: Vector[LECPoint],           // Discrete sampling of LEC function
  quantiles: Map[String, Double],    // Key percentiles (p50, p90, p95, p99)
  provenances: List[NodeProvenance] = Nil
)
```

**Note:** This is **not** the LEC function itself, but a discrete sampling of it for visualization and API transmission.

### Mitigation
A **risk reduction measure**, recorded once on the tree and applied to the nodes
it targets. It is tree-level content, never baked into a node's own parameters:
the node keeps describing the risk as it stands, and the mitigation describes
what reduces it.

A mitigation says three things: what it targets (a predicate that resolves to a
set of nodes), how it reduces, and where in the order it applies relative to
other mitigations, which is its precedence.

"How it reduces" is one of **two stages**, and they are different mechanisms
rather than two flavours of one. A **parameter-stage** mitigation changes a
leaf's simulation inputs before the simulation runs. A **result-stage**
mitigation changes a finished value after the simulation has run. They apply to
different kinds of node, they interact with the cache differently, and they
compose differently. Each has its own term below.

Because the reduction is held separately from the node, the same tree can be
valued with mitigations applied or without them. Those two valuations are
inherent risk and residual risk, both defined below.

### Parameter-Stage Mitigation

A mitigation that changes a leaf's **simulation inputs**, before the simulation
runs. It changes the two parts of the risk — the occurrence probability, the loss
shape, or both — and the leaf is then simulated from the changed values.

"Two-factor authentication cuts how often this account is compromised to a third
of what it was" is one. The probability is multiplied by one third, and every
figure downstream follows from re-simulating the leaf.

Two properties follow from its running before the simulation.

It applies to **leaves only**. A portfolio has no probability and no loss shape
of its own, so there is nothing at a portfolio for this stage to change.

It changes the **cache key**. A leaf is cached under a hash of its
simulation-relevant parameters, so a leaf with a parameter-stage mitigation
applied hashes differently and misses the cache naturally. No invalidation step
is needed, and none exists.

### The Loss Borne

Every result-stage operation answers one question, and reading any of them
against the wrong question is the mistake they are written to prevent:

> After this arrangement has paid, how much of the loss is still ours?

A node's figure is always the loss borne — a financial obligation in that
trial's year. It is never what a counterparty pays. The two are easy to confuse
because they are computed from the same two numbers and they cross at one point,
and because in the ordinary case they are very different: on a loss of 30 million
against a policy carrying a 5 million deductible and a 50 million limit, the
insurer pays 25 million and you bear 5 million.

The rest of the result-stage vocabulary below is read against this one sentence.

### Result-Stage Mitigation

A mitigation that changes a **finished value**, after the simulation has run. It
leaves the risk's parameters untouched and transforms the losses the simulation
produced into the losses still borne.

It applies to **any node**, a portfolio included, and that is the point. Capping
a division's total is a statement about the total, and capping each of its leaves
instead gives a different number. Why the two are different is under
Homomorphism below.

There are four operations, and each one states what is left on your books:

| operation | in plain words |
|---|---|
| **cap** | we never bear more than this figure; everything above it is covered, with no ceiling on the cover |
| **scale** | we bear this fraction of every loss; the rest falls elsewhere, by transfer or by an agreed share |
| **threshold filter** | losses below this figure are not borne by us at all; losses at or above it are borne in full |
| **insurance policy** | we bear the first slice, a counterparty covers the next band, and anything past the end of that band is ours again |

The first three have one parameter each and the fourth has two. Only the fourth
can describe cover that runs out, which is why it exists beside the cap rather
than as a special case of it — see the next entry.

A mitigation holds an ordered **pipeline** of these, between one and ten steps.
A pipeline with no steps would have no effect, and the ceiling of ten is chosen
by this project to stop one pipeline restating the same operation repeatedly.
Order within a pipeline matters, because capping and then scaling is not the
same as scaling and then capping.

It never enters the cache. The cache holds the raw simulated figures, and a
result-stage transform is applied to them after they are read back.

### Deductible, Limit, and the Band a Policy Covers

An insurance policy is described by two numbers, and together they divide any
loss into three bands. The vocabulary is worth a worked example because the
first band is the one most often read backwards.

- The **deductible** is the first slice of the loss, and it is **yours**. The
  policy starts paying only above it.
- The **limit** is the most the policy will pay in total. Past it the policy has
  run out.

So for a loss of `L`, with deductible `d` and limit `c`:

```
you bear      =  min(L, d)  +  max(0, L − d − c)
the policy pays  =  min( max(0, L − d), c )
```

Read the first line left to right: the first `d` is yours, the policy covers the
next `c`, and above `d + c` it is yours again.

A policy with a 5 million deductible and a 50 million limit:

| the loss | you bear | why |
|---|---|---|
| 3M | 3M | entirely inside the deductible, so the policy pays nothing |
| 30M | 5M | the deductible, and the policy covers the other 25M |
| 100M | 50M | the 5M deductible, plus the 45M above the policy's limit |

**A deductible of zero is first-unit coverage.** The policy pays from the very
first unit of loss up to its limit, and you bear only what is above. That is how
an arrangement with no deductible is written — a support contract or a warranty
that absorbs small incidents outright is a policy with a zero deductible and a
limit set to the amount it absorbs.

**A cap is not a policy with a huge limit.** A cap says the cover above it never
runs out, so your loss stops at the cap whatever happens. A policy's cover does
run out, and the loss comes back to you above `d + c`. The two differ exactly in
whether there is a third band.

### Relative and Absolute Statements

A parameter-stage mitigation states its effect in one of two ways, and which one
it is decides what the mitigation has to record.

A **relative** statement says "whatever this leaf is, change it by this much" —
cut the probability to 30% of what it was, scale the loss to 60% of what it was.
It stays true when the leaf changes. A control that cuts likelihood to 30% still
means 30% after the leaf is re-estimated, so there is nothing about the leaf's
former value to record.

An **absolute** statement says "after this control, this leaf *is* these
figures" — the probability is 2%, the loss we bear is 1M–5M. Those figures were
judged against one particular leaf as it stood when they were written.

That difference creates a failure mode the relative kind does not have.

The **cause** is that a leaf can be re-estimated after an absolute statement was
written against it. An expert looking at a leaf of 20M–120M and writing "with the
policy we bear 1M–5M" has made a statement about 20M–120M. Re-estimate the leaf
to 200M–1.2B and the retention is no longer 1M–5M.

The **symptom** is a mitigation whose figures describe a leaf that no longer
exists. It still applies, and it reports a number nobody would now assert.

The **fix** is that an absolute statement carries two pieces of evidence as
required fields: which leaf it was asserted against, and a hash of that leaf's
simulation-relevant fields at the time of writing. Comparing the stored hash
against the leaf's current one says whether the statement has gone stale. A
rename or a reparent does not trigger it, because the hash covers the
simulation-relevant fields only. A relative statement carries neither field,
because it has nothing to audit.

The two kinds are the two cases of the mitigation spec rather than optional
fields on one case, so an absolute statement missing its evidence is not
representable rather than representable-and-refused. There is one consequence for
what a single mitigation can say, and it is under How Parameter-Stage Mitigations
Compose below.

### Layer

The **layer** at a node is the difference between what that node's own
result-stage transform produced and what it was applied to. It is the amount the
control removed at that level, and it is the figure that says what a policy is
worth.

In the worked example under The Two Valuations below, the mitigated values of
Servers' children sum to 20 while Servers' own value is 18, so the layer at
Servers is 2. That subtraction is what the two sets of figures on a loss
distribution exist for: `source` is what the transform was applied to, `trials`
is what came out, and the layer is one against the other with no second request.

A node with nothing scoped to it has an empty layer, and its two sets of figures
are identical.

### Precedence

An integer on each mitigation fixing the order mitigations apply in when more
than one covers the same node. Lower keys apply first, and the mitigation
identifier breaks a tie, so the order is total and stays the same across a merge.

Three values are named: `-1000` applies first, `0` is the default, and `1000`
applies last. All three are chosen by this project rather than derived from
anything.

Precedence only changes an answer where two mitigations interact. Two whose
target sets do not overlap commute, and so do two that cover the same node but
change different parts of it.

### Inherent Risk
The loss **before any mitigation is applied** — the raw simulated figure for a
node. This is what every read path returns unless a mitigation valuation is
asked for explicitly.

It is not computed by separate machinery. It is the same fold as residual risk,
run with nothing selected, so every node's transform is the identity and every
layer is empty.

In the query language it is the literal `"inherent"`, as in
`p95(x, "inherent")`.

### Residual Risk
The loss **after every mitigation whose target covers that node has been
applied**. Mitigations apply in their recorded order, so residual risk is the
result of running the whole applicable sequence, not of applying them
independently.

It is computed by a second fold over the same tree rather than by adjusting the
inherent figure, and the two stages enter that fold at different points: a
parameter-stage mitigation rewrites a leaf before it is simulated, and a
result-stage mitigation transforms a finished value after that node's children
have been combined. Why it has to be a second fold rather than an adjustment is
under The Two Valuations below.

In the query language it is the literal `"residual"`, as in
`p95(x, "residual")`.

A tree with no mitigations has identical inherent and residual figures. So does
a tree whose only mitigations have no effect: a scale factor of exactly 1 is a
valid mitigation and changes no figure, and the residual valuation is then the
inherent one rather than something that resembles it.

## Mathematical Structure

### Identity (Monoid) for Trial Outcomes
Trial-aligned outcomes can be **combined** using outer join semantics. The monoid
lives on `TrialOutcomes` (trial count + trial→loss map); node identity is a label
supplied by tree context and does not participate in combination:

```scala
case class TrialOutcomes(nTrials: PositiveInt, outcomes: Map[TrialId, Loss])

object TrialOutcomes {
  def empty(using cfg: SimulationConfig): TrialOutcomes  // zero losses, configured nTrials
  def combine(a: TrialOutcomes, b: TrialOutcomes): TrialOutcomes  // union of trial IDs, sum losses per trial
}
```

**Properties:**
- **Associative**: `(a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)`
- **Identity**: `a ⊕ ∅ = a`
- **Commutative** (bonus): `a ⊕ b = b ⊕ a`

**Interpretation:** This models the aggregation of independent risks by summing their losses in each trial.

### Homomorphism

A function `f` is a **homomorphism** with respect to combine when applying it
before or after combining gives the same answer:

```
f(a ⊕ b) = f(a) ⊕ f(b)
```

A function with this property can be moved inside a combine freely. A function
without it cannot, and that single difference decides how mitigation is
computed.

A cap does not have it. Take a cap at 10, and two children that each lose 8 in
the same trial:

```
cap10(8 ⊕ 8)        = cap10(16) = 10
cap10(8) ⊕ cap10(8) = 8 ⊕ 8     = 16
```

Ten is not sixteen. This is what a cap means rather than a limitation of the
implementation: a cap on a total is a statement about the total, and it is not
the same statement as a cap on each part.

Mitigation is designed for functions without the property, because a cap and a
deductible both lack it and both are ordinary things for an author to write.

### Fold

A **fold** collapses a tree to one value by giving a single rule for how a
node's value is built from its children's values. The same rule runs at every
node, from the leaves upward. Its formal name is a catamorphism.

The fold the system has always computed is the **raw valuation**:

```
raw(leaf)      = simulate(leaf)
raw(portfolio) = combine over raw(children)
```

That gives one property holding at every node in the tree: a node's value equals
the combine of its children's values.

Two things depend on it. The content-addressed cache stores leaves only, keyed by
a hash of each leaf's simulation parameters, and never stores a portfolio at all,
because a portfolio can always be rebuilt by combining its children — which is
sound only while the property holds. And the type system enforces it, because the
internal aggregate type's constructor computes the total from the children and
offers no parameter through which a different total could arrive.

### The Two Valuations

An aggregate cap is an ordinary requirement: an insurance policy on a division
caps that division's total. Putting it together with the fold property above
produces a contradiction, and the contradiction is arithmetic rather than a
design preference.

Take a portfolio whose children sum to 16, carrying a cap at 10. The cap says its
value is 10. The fold property says its value equals the combine of its children,
which is 16. One value cannot be both.

So two values are computed rather than one. The raw valuation above is untouched,
and it is what the cache holds. A second fold — the **mitigated valuation** —
runs over the same tree with one extra step in its rule: combine the children,
then apply this node's own transform.

```
mitigated(leaf)      = f_leaf( simulate(leaf) )
mitigated(portfolio) = f_P( combine over mitigated(children) )
```

`f_X` is the composed result-stage transform scoped to node `X`, and is the
identity function wherever nothing is scoped.

A worked example, figures being the loss in one trial, with an insurance policy
capping Servers at 18 and a separate cap of 6 on DiskFailure:

| node | raw | mitigated |
|---|---|---|
| DiskFailure | 9 | `cap6(9)` = 6 |
| PowerLoss | 14 | no transform: 14 |
| Servers | `9 ⊕ 14` = 23 | `cap18(6 ⊕ 14)` = `cap18(20)` = **18** |
| Fraud | 3 | no transform: 3 |
| Group | `23 ⊕ 3` = 26 | no transform: `18 ⊕ 3` = **21** |

Two things to read off it. The mitigated total at the root is 21 against a raw 26,
so the benefit of a policy written two levels below reaches the top of the tree.
And at Servers the mitigated value is 18 while its children's mitigated values
sum to 20: the mitigated valuation deliberately does not have the fold property,
and that gap of 2 is the layer at Servers.

The children must be combined **before** the cap is applied, not after the raw
aggregate has been capped. Capping the raw aggregate gives a different and wrong
number, because it cannot see a reduction already applied below it. Take a trial
where DiskFailure is 20 and capped to 6, and PowerLoss is 4. Folding the
mitigated children gives `cap18(6 ⊕ 4)` = 10. Capping the raw total gives
`cap18(24)` = 18. Ten is the right answer, and only the fold reaches it.

The reasoning is in
[ADR-034](../dev/decision-records/ADR-034-mitigation-valuation-model.md), and the
cache's side of it in [ADR-014](../dev/decision-records/ADR-014.md).

### Product and Coproduct

A **product** type holds several things at once, which in Scala is a
`case class`. A **coproduct** type is one of several alternatives, which is a
`sealed trait` with cases.

The counting rule follows from those definitions. A product's size is the product
of its fields' sizes, so two fields of three values each give nine. A coproduct's
size is the sum of its cases' sizes, so cases of three and four values give
seven.

Why the distinction matters here: writing alternatives as a product with optional
fields loses information. "A circle or a rectangle", written as a product:

```scala
final case class BadShape(
  kind:   String,
  radius: Option[Double],
  w:      Option[Double],
  h:      Option[Double]
)
```

Three optional fields admit eight presence combinations, of which two are
meaningful. The other six have to be excluded by a hand-written rule in the
constructor. That rule can be forgotten, it can drift from the comment describing
it, and it has to produce an error message about a state the type should never
have admitted.

Both the loss shape and the mitigation spec are coproducts for this reason, and
both were products with optional fields before. In each case the rule that used
to exclude the meaningless combinations no longer exists, because there is no
longer a combination to exclude.

### How Parameter-Stage Mitigations Compose

A result-stage transform runs on finished figures and always succeeds, so
composing two of them is ordinary function composition, with the identity
transform as its neutral element and the empty list as its representation.

A parameter-stage transform can fail. Scaling a lognormal's lower bound by a
small factor can push it below the smallest representable loss, and that has to
be reported rather than rounded away. So a parameter-stage transform is a
function returning a validation:

```scala
LeafParams => Validation[ValidationError, LeafParams]
```

`LeafParams` is the pair a transform may change — the occurrence probability and
the loss shape — and nothing else. A function of this shape, from a plain value
to a wrapped one, is a **Kleisli arrow**. Two of them compose by running the
first and feeding its result into the second if it succeeded, which is `flatMap`.
The identity arrow returns its input unchanged, and that is what a relative
statement of "keep the probability, keep the severity" is.

Every parameter-stage mitigation scoped to a leaf is folded through that
composition in precedence order, and the leaf is rebuilt once from the result. A
relative statement about a part it does not name passes that part through
unchanged. An absolute replacement of one part replaces that part and passes the
other through unchanged.

Two consequences follow, and both are relied on.

**A control that is relative in one part and absolute in the other is two
mitigations, and their composition is exact.** No single spec says "cut the
frequency to 30% of what it was, and after the policy the loss we bear is
1M–5M", because the first half is relative and the second is absolute, and a spec
is one or the other. Writing it as two mitigations on the same leaf produces
exactly that result:

```
start                       probability 0.40, shape 20M–120M
relative: scale 0.3         probability 0.12, shape 20M–120M   severity untouched
absolute: severity 1M–5M    probability 0.12, shape   1M–5M    probability untouched
```

What that costs is bookkeeping: one authored control becomes two mitigation
entities, so it has two identifiers, two names and two targeting predicates. What
it does not cost is accuracy. The composed figures are the paired statement's
figures rather than an approximation of them.

**Two mitigations changing different parts of a leaf commute.** In the example
above the relative half reads and writes only the probability, and the absolute
half reads and writes only the shape, so running them in the other order gives
the same answer and the precedence key does not matter. Precedence matters only
where two mitigations change the *same* part: scaling a probability by 0.5 and
then replacing it with 0.02 gives 0.02, while replacing and then scaling gives
0.01.

## Composition

### How to Combine Risks

**You combine loss distributions, not LEC functions directly.** Two levels are
involved, and they are different operations.

The **figures** combine through the monoid above, which is defined on
`TrialOutcomes` and sums losses trial by trial:

```scala
val combined: TrialOutcomes = TrialOutcomes.combine(a, b)
```

A **node** is aggregated through a factory that derives the total from exactly
the children it is handed:

```scala
PortfolioLosses.create(nodeId, children): Validation[ValidationError, PortfolioLosses]
```

No method accepts a total, which is what makes the aggregate claim true by
construction rather than by discipline. Construction returns a validation
because it can fail for one real reason: summing children's losses can pass
`Long.MaxValue`, and that overflow becomes a validation error rather than a
wrapped negative number. A differing trial count between children is a
programming error instead, and it propagates rather than being converted.

Once the figures are combined, the LEC is read off them:

```scala
val lecFunction: Loss => Double =
  threshold => combined.probOfExceedance(threshold)
```

### Why Not Sum LEC Functions?

The LEC of a portfolio is **not** the sum of individual LECs, because:
- LECs represent probabilities, not losses
- Portfolio LEC must account for loss aggregation per trial
- Correlation structure matters (independent vs dependent risks)

**Correct approach:** Sum the underlying loss distributions, then compute the portfolio's LEC.

## Workflow

### 1. Define Risks
```scala
val cyberRisk = RiskSampler.fromDistribution(
  entityId = 1L,
  riskId = "CYBER-001",
  occurrenceProb = 0.3.refineUnsafe,
  lossDistribution = metalog
)
```

### 2. Simulate (Generate Loss Distribution)
```scala
val distribution: LossDistribution =
  simulate(cyberRisk, nTrials = 10000)
```

### 3. Query LEC Function
```scala
val var95: BigDecimal = 
  distribution.probOfExceedance(5000L)  // P(Loss ≥ $5B)
```

### 4. Generate Curve Data for API
```scala
val curveData: LECCurveResponse = 
  LECGenerator.generateCurveData(distribution, nPoints = 100)
```

### 5. Visualize
```scala
val vegaSpec: String = 
  LECChartSpecBuilder.generateMultiCurveSpec(curveData)
```

## Sensitive Values

Two kinds of sensitive value travel through the system, and they are protected in
**opposite** directions. Treating them as one class is wrong about one of them,
so the vocabulary matters before any rule about them makes sense.

A third kind of value is defined here as well, although it is not sensitive and
is not protected. It belongs in this section because nothing in the value itself
distinguishes it from the second kind: the two are the same 26-character string,
produced by the same generator. What separates them is what the server does when
a caller presents one, and that is set out after all three are defined.

### Trust Boundary

A trust boundary is the line a value may not cross without being checked. A
system has several, and they are not nested neatly — the boundary around "what
the service itself can see" is not the same as the boundary around "what appears
in a log file", because a log is read by people and tools the service never talks
to: operators, a log shipper, an aggregation dashboard. That difference is what
makes the two kinds of value below behave differently.

### Secret

A **secret** is a value where holding it *is* permission. The workspace key
(`WorkspaceKeySecret`) is the one in this system: a 22-character base64url token
generated by `SecureRandom`, where the length and alphabet come from the token
generator's own encoding. Anyone who has it can read and write that workspace
until it expires — after 72 hours, or after 1 hour of no use, both defaults set
in `WorkspaceConfig`.

Its trust boundary is the narrowest in the system, and two consequences follow.

**It may never be written to a log.** The cause is that a log's audience is wider
than the service. The symptom, if it happens, is a key sitting in plain text in a
file or a dashboard that several people and tools can read. The fix is that the
type prints `WorkspaceKeySecret(***)` rather than its contents, and reading the
real value requires calling `.reveal()` explicitly, which makes every such call
findable.

**It does cross the wire, because that is its purpose.** A bearer credential
exists to be sent. The server returns it exactly twice — when a workspace is
created, and when its key is rotated — and those are the only two responses that
carry it. What it may never appear in is an *error*, which would put it in a log
and in front of a client at once, neither of which needs it.

### Confidential Identifier

A **confidential identifier** is a value that names something internally but
grants nothing. `WorkspaceId` is the one here: the key under which a workspace is
stored and the string embedded in its storage branch names.

Holding it gives no access at all, because permission in this system comes from
the key alone. So the risk it carries is not that someone learns a password — it
is that someone learns a *reference they could aim at*. That risk has a name:

- An **enumeration oracle** is any response that lets a caller discover which
  identifiers exist, by answering differently for one that does and one that does
  not. Given one real identifier and a differing answer, a caller can script
  through the space and map what is there.
- **IDOR (Insecure Direct Object Reference)** is the matching attack: supplying
  someone else's identifier and being served their data, because the server used
  the submitted reference instead of deriving the right one from who the caller
  is.

Both need the caller to see or guess an identifier. So the protection is the
mirror image of a secret's:

**It may be logged.** A reader of a server log gains nothing by seeing it, so
storage paths, commit messages and log lines may all name it.

**It may never cross the client boundary, in either direction.** Not in a
response field, a header or an error message, and not accepted as a path
segment, query parameter or body field either — the server derives it from the
key instead. Accepting one is an enumeration oracle even when it is never echoed
back, because an endpoint that behaves differently for a valid and an invalid
identifier has answered the question by behaving differently.

### Resource Name

A **resource name** names one thing inside a scope that has already been chosen.
`TreeId` is one. So are `NodeId` and `MitigationId`.

Reading a tree shows the two stages that make this different from the value
above. A request arrives at `GET /w/{key}/risk-trees/{treeId}`. The server
resolves `{key}` to a workspace first, which fixes the scope. Only then does it
ask whether that workspace contains `{treeId}`.

Suppose the caller submits a tree identifier belonging to someone else's
workspace. It is not in the resolved workspace's set of trees, so the lookup
finds nothing — the same nothing returned for a tree identifier that was never
issued to anyone. No branch in the code distinguishes those two cases, so there
is no status code, response shape or timing difference to tell them apart.

A resource name is returned to clients and accepted from them. That is not an
exception to the rule stated for confidential identifiers. It falls outside that
rule, for the reason given next.

### Selector and Proof

Three jobs are in play, and each of the three values does a different subset of
them.

**Selecting** is answering which tenant's data a request touches. **Proving** is
establishing that the caller is allowed to touch it.

The workspace key does both. The server looks the key up and gets a workspace,
which is the selecting. The fact that the caller held the key at all is the
entitlement, which is the proving.

The workspace identifier is what that lookup returns. It selects, because every
storage path and branch name is built from it. It proves nothing, because
permission in this system comes from the key alone.

A tree identifier does neither. It names something within a scope that has
already been selected and already been proved.

So the precise statement is this: the key is selector plus proof, the workspace
identifier is selector without proof, and a tree identifier is neither. That is
the asymmetry. It is not about which values are secret.

Nothing in the values themselves would tell you any of this. `WorkspaceId` and
`TreeId` are both ULIDs, a 26-character format fixed by the ULID specification,
and both are generated from ZIO's `Random` rather than from `SecureRandom` —
which ADR-021 §5 records as a deliberate choice for identifiers. The safety of a
workspace identifier was therefore never its entropy. Tree and node identifiers
come from the same generator and are returned to clients, so values of that
shape and that quality are already on the wire.

### The Test That Decides Confinement

One question decides whether an identifier has to be withheld:

> Does presenting the identifier make the server widen what it looks at?

Run it on a tree identifier. A caller presents one from another workspace. The
server checks it against the tree set of the workspace the key already resolved
to, does not find it, and answers as it would for an identifier that does not
exist. The server looked at exactly the same data either way, so nothing
widened.

Run it on a workspace identifier. If an endpoint accepted one, the server would
widen as far as the submitted value said, because that value *is* the scope. The
client would then be choosing the tenant, which is the job the credential exists
to do.

### The Three Side by Side

| | a server log | the wire |
|---|---|---|
| **secret** — workspace key | never: the log's audience is wider than the service | yes, in the two responses that mint it; never in an error |
| **confidential identifier** — workspace id | permitted: holding it grants nothing | never, in either direction |
| **resource name** — tree, node, mitigation id | permitted | yes, in both directions |

The one place both sensitive kinds are forbidden is a message a client can read.
A secret there reaches a log and a client at once; a confidential identifier
there hands over the reference the rules above exist to withhold.

### Why the Rule Holds When Nothing Is Exploitable Today

Holding a workspace identifier gains an attacker nothing at present.
`WorkspaceStore`, the component that owns workspace records, has no
by-identifier method at all: every entry point takes the key. The one method
that resolved a workspace from an identifier alone, with no capability check,
had no caller in the running system and was deleted.

So the rule is not stopping an attack that works today. It is stopping a feature
that has not been written yet, and a worked example shows how.

Someone is asked to build a page listing the workspaces a person owns. If the
identifier were already in responses, it would be the value nearest to hand, and
the obvious route is an endpoint shaped `GET /workspaces/{workspaceId}/trees`.
The scope of that request now comes from the path rather than from the key. The
capability check is then absent by construction rather than by oversight, which
is the harder kind of absence to notice in review.

Because nothing returns the identifier today, that author has to add the
exposure first, deliberately, in a change a reviewer can see. The control is on
the design rather than on an attacker, and ADR-036 §4 states it that way.

A resource name cannot become the same trap. No lookup of a tree skips the
workspace, so even an endpoint written as `GET /trees/{treeId}` would still have
to resolve a workspace from somewhere, and the only thing that resolves one is
the key.

The same reasoning is what makes a tree identifier free to return in an error.
A request to `POST /w/{key}/risk-trees/01HQ…/query` whose query text is refused
is answered with a body naming tree `01HQ…`. The caller supplied that value in
the path of the request being answered, so the response adds nothing to what the
caller already held.

The binding rules are in
[ADR-035](../dev/decision-records/ADR-035-error-leakage-prevention.md) for what a
message may carry,
[ADR-036](../dev/decision-records/ADR-036-confidential-internal-identifiers.md)
for the identifier, and
[ADR-022](../dev/decision-records/ADR-022-secret-handling.md) for how a secret
type is built.

## Summary

| Concept | Type | Mathematical View | Practical View |
|---------|------|-------------------|----------------|
| **Risk** | `RiskSampler` | Random variable | Sampling strategy |
| **Loss** | `Long` | Outcome value | Monetary impact |
| **Loss Shape** | `DistributionShape` | Coproduct of two fitted families | What the author states about severity |
| **Loss Distribution** | `LossDistribution` | Empirical distribution | Simulation data |
| **LEC Curve** | `LECCurve` trait | `Loss → ℝ` function | `probOfExceedance` method |
| **LEC Curve Response** | `LECCurveResponse` | Discrete sampling | API response format |
| **Mitigation** | `Mitigation` | Targeted reduction applied at one of two stages | Recorded risk reduction |
| **Parameter-Stage Mitigation** | `LeafStageSpec` | Kleisli arrow on a leaf's two parameters | Control changing a risk's inputs |
| **Result-Stage Mitigation** | `MitigationSpec.ResultStage` | Endomorphism on trial outcomes, applied outside the combine | Policy changing a finished loss |
| **Loss borne** | what every `ResultTransformSpec` case returns | The quantity the whole result stage is defined over | What is still ours after a counterparty has paid |
| **Deductible and limit** | `ResultTransformSpec.InsurancePolicy` | Two bounds splitting a loss into three bands | The first slice ours, the next band covered, the rest ours |
| **Layer** | `trials` against `source` | Difference across one node's own transform | What the control removed here |
| **Precedence** | `MitigationPrecedence` | Total order on mitigations | Which control applies first |
| **Inherent Risk** | `MitigationSelection.Inherent` | The mitigated fold's identity instance | Loss before controls |
| **Residual Risk** | `MitigationSelection.Residual` | A second fold, decorated at each node | Loss after controls |

## Key Insights

1. **LEC is a function**, backed by a Loss Distribution
2. **Loss Distribution is the data**, LEC Curve Response is its serialization
3. **Combine distributions, not curves** - sum trial-wise losses, then compute LEC
4. **Identity structure** enables compositional risk aggregation
5. **LECCurve trait** provides the `Loss → Probability` abstraction
6. **Mitigation has two stages** — parameter-stage changes a leaf's inputs before
   it is simulated, result-stage changes figures after the simulation has run
7. **A cap is not a homomorphism**, which is why residual risk is a second fold
   rather than an adjustment applied to the raw one
8. **Alternatives are coproducts** — the loss shape and the mitigation spec are
   cases, so an impossible combination is unrepresentable rather than
   representable and forbidden by a rule
9. **A control mixing the two kinds of parameter-stage statement is two
   mitigations**, and because they change different parts of the leaf they
   compose exactly and commute
10. **A node's figure is the loss borne**, never what a counterparty pays — the
    two are different numbers in the ordinary case and coincide only where they
    cross
11. **A cap and a policy differ in whether the cover runs out** — a cap has no
    third band, a policy returns the loss above its limit to you
