# Risk Register Terminology

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
sealed abstract class LossDistribution extends LECCurve {
  def outcomes: Map[TrialId, Loss]
  def outcomeCount: TreeMap[Loss, Int]
  def nTrials: Int
}
```

**Subtypes:**
- `RiskResult` - Single risk distribution (leaf node)
- `RiskResultGroup` - Aggregated distribution (composite node)

### LEC Curve (Loss Exceedance Curve)
The **mathematical function**: `Loss → Probability`

Represents: **P(Loss ≥ threshold)** for any threshold value.

**Conceptual interface:**
```scala
trait LECCurve {
  def probOfExceedance(threshold: Loss): BigDecimal
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
  childIds: Option[List[String]] = None,  // Flat child ID references
  provenances: List[NodeProvenance] = Nil
)
```

**Note:** This is **not** the LEC function itself, but a discrete sampling of it for visualization and API transmission.

## Mathematical Structure

### Identity (Monoid) for Loss Distributions
Loss distributions can be **combined** using outer join semantics:

```scala
given identity: Identity[RiskResult] with {
  def identity: RiskResult = RiskResult("", Map.empty, 0)
  
  def combine(a: => RiskResult, b: => RiskResult): RiskResult = {
    // Union of trial IDs, sum losses per trial
    RiskResult(name, merge(a, b), nTrials)
  }
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
val risk1: RiskResult = simulate(riskSampler1, nTrials)
val risk2: RiskResult = simulate(riskSampler2, nTrials)

// Combine distributions (sum losses per trial)
val combined: RiskResult = risk1 combine risk2

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
val distribution: RiskResult = 
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
  VegaLiteBuilder.buildSpec(curveData)
```

## Summary

| Concept | Type | Mathematical View | Practical View |
|---------|------|-------------------|----------------|
| **Risk** | `RiskSampler` | Random variable | Sampling strategy |
| **Loss** | `Long` | Outcome value | Monetary impact |
| **Loss Distribution** | `LossDistribution` | Empirical distribution | Simulation data |
| **LEC Curve** | `LECCurve` trait | `Loss → ℝ` function | `probOfExceedance` method |
| **LEC Curve Response** | `LECCurveResponse` | Discrete sampling | API response format |

## Key Insights

1. **LEC is a function**, backed by a Loss Distribution
2. **Loss Distribution is the data**, LEC Curve Response is its serialization
3. **Combine distributions, not curves** - sum trial-wise losses, then compute LEC
4. **Identity structure** enables compositional risk aggregation
5. **LECCurve trait** provides the `Loss → Probability` abstraction
