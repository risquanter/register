# Risk Register Terminology

This document explains the domain concepts and how they work: what a risk,
a loss, a loss distribution, an LEC and a mitigation are, how risks compose,
and the workflow from defining risks to a curve. It is the place to start on
anything to do with simulation or mitigation.

It also defines the two kinds of sensitive value the system handles — a secret
and a confidential identifier — because they are protected in opposite
directions and the rules about them only make sense once the terms are clear.

How the system is built is in [docs/dev/ARCHITECTURE.md](../dev/ARCHITECTURE.md).

## Core Concepts

### Risk
An **uncertain event** with:
- **Likelihood**: Occurrence probability (Bernoulli distribution)
- **Impact**: Loss distribution when event occurs (Metalog distribution)

```scala
trait RiskSampler {
  def sampleOccurrence(trial: Long): Boolean  // Did it happen?
  def sampleLoss(trial: Long): Long           // If yes, how much?
}
```

### Loss
The **monetary consequence** when a risk materializes. A single Long value per trial representing the financial impact (typically in millions: 1L = $1M).

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
set of nodes), how it reduces (for example, cap the loss, scale it, apply a
deductible), and where in the order it applies relative to other mitigations.

Because the reduction is held separately from the node, the same tree can be
valued with mitigations applied or without them. That is what the next two terms
name.

### Inherent Risk
The loss **before any mitigation is applied** — the raw simulated figure for a
node. This is what every read path returns unless a mitigation valuation is
asked for explicitly.

In the query language it is the literal `"inherent"`, as in
`p95(x, "inherent")`.

### Residual Risk
The loss **after every mitigation whose target covers that node has been
applied**. Mitigations apply in their recorded order, so residual risk is the
result of running the whole applicable sequence, not of applying them
independently.

In the query language it is the literal `"residual"`, as in
`p95(x, "residual")`.

A tree with no mitigations has identical inherent and residual figures.

## Mathematical Structure

### Identity (Monoid) for Trial Outcomes
Trial-aligned outcomes can be **combined** using outer join semantics. The monoid
lives on `TrialOutcomes` (trial count + trial→loss map); node identity is a label
supplied by tree context and does not participate in combination:

```scala
case class TrialOutcomes(nTrials: Int, outcomes: Map[TrialId, Loss])

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

## Composition

### How to Combine Risks

**You combine Loss Distributions, not LEC functions directly:**

```scala
val risk1: LossDistribution = simulate(riskSampler1, nTrials)
val risk2: LossDistribution = simulate(riskSampler2, nTrials)

// Combine distributions (sum losses per trial)
val combined: LossDistribution = risk1 combine risk2

// Compute LEC from combined distribution
val lecFunction: Loss => BigDecimal = 
  threshold => combined.probOfExceedance(threshold)

// Generate curve data for API
val curveData: LECCurveResponse = 
  generateCurveData(combined, nPoints = 100)
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

### The Two Side by Side

| | a server log | the wire |
|---|---|---|
| **secret** — workspace key | never: the log's audience is wider than the service | yes, in the two responses that mint it; never in an error |
| **confidential identifier** — workspace id | permitted: holding it grants nothing | never, in either direction |

The one place both are forbidden is a message a client can read. A secret there
reaches a log and a client at once; a confidential identifier there hands over
the reference the rules above exist to withhold.

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
| **Loss Distribution** | `LossDistribution` | Empirical distribution | Simulation data |
| **LEC Curve** | `LECCurve` trait | `Loss → ℝ` function | `probOfExceedance` method |
| **LEC Curve Response** | `LECCurveResponse` | Discrete sampling | API response format |
| **Mitigation** | `Mitigation` | Transform on a distribution | Recorded risk reduction |
| **Inherent Risk** | `MitigationSelection.Inherent` | Untransformed value | Loss before controls |
| **Residual Risk** | `MitigationSelection.Residual` | Value after every applicable transform | Loss after controls |

## Key Insights

1. **LEC is a function**, backed by a Loss Distribution
2. **Loss Distribution is the data**, LEC Curve Response is its serialization
3. **Combine distributions, not curves** - sum trial-wise losses, then compute LEC
4. **Identity structure** enables compositional risk aggregation
5. **LECCurve trait** provides the `Loss → Probability` abstraction
