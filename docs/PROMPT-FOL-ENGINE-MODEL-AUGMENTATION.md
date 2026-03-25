# Prompt: Add Model Augmentation Extension Point to fol-engine

**Target project:** `~/projects/vague-quantifier-logic` (fol-engine)  
**Requesting project:** `~/projects/register` (risk register)  
**Date:** 2026-03-23

---

## Context

The `register` project (a risk register application) depends on
`fol-engine` to evaluate vague quantifier queries against risk trees.
Register builds a `KnowledgeBase` with structural facts (`leaf`,
`portfolio`, `child_of`, `descendant_of`, `leaf_descendant_of`) and
wraps it in a `KnowledgeSource` via
`KnowledgeSource.fromKnowledgeBase(kb)`.

## Problem

The current `VagueSemantics.evaluate()` and `VagueSemantics.holds()`
accept a `KnowledgeSource` and internally build `Model[Any]` via
`FOLBridge.scopeToPredicate()` → `KnowledgeSourceModel.toModel(source)`.
**There is no way for the caller to inject custom functions or predicates
into the model.** The resulting `Interpretation[Any]` has only
relation-backed predicates and identity functions for constants.

Register needs to evaluate queries like:

```
Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))
```

This requires the model's `Interpretation[Any]` to include:

- **Simulation-backed functions** that compute statistics from Monte
  Carlo simulation results
- **Comparison predicates** for numeric ordering
- **Numeric literal resolution** so `5000000` in the query is parsed as
  a numeric value

None of these can currently be injected through the public API.

## Goal

Add an extension point — ideally a `Model[Any] => Model[Any]`
augmentation function parameter — that allows callers to enrich the
model before scope evaluation. This should be available on both
`VagueSemantics.evaluate()` and `VagueSemantics.holds()`, and/or on
`FOLBridge.scopeToPredicate()`.

**Constraint:** All changes must be **additive** (new overloads or
default parameters). The existing API signatures must not break.
Existing tests must continue to pass unchanged.

---

## Complete List of Functions/Predicates Register Needs to Inject

### 1. Quantile functions (arity 1 — node → numeric value)

| FOL Symbol | Scala backing | Return type | Description |
|---|---|---|---|
| `p50(x)` | `LECGenerator.calculateQuantiles(result)("p50")` | `Double` | 50th percentile (median) loss |
| `p90(x)` | `LECGenerator.calculateQuantiles(result)("p90")` | `Double` | 90th percentile loss |
| `p95(x)` | `LECGenerator.calculateQuantiles(result)("p95")` | `Double` | 95th percentile loss |
| `p99(x)` | `LECGenerator.calculateQuantiles(result)("p99")` | `Double` | 99th percentile loss |

`calculateQuantiles` builds a CDF from
`RiskResult.outcomeCount: TreeMap[Loss, Int]` (histogram of Monte Carlo
trial outcomes, where `Loss = Long` representing currency units). It
walks the CDF to find the loss value where cumulative probability ≥
target percentile, returning `loss.toDouble`.

**Scala implementation (server module):**

```scala
// LECGenerator.scala — simplified
def calculateQuantiles(result: RiskResult): Map[String, Double] =
  val cdf = result.outcomeCount  // TreeMap[Long, Int] — sorted by loss
  val total = result.nTrials
  var cumulative = 0
  val percentiles = Map("p50" -> 0.5, "p90" -> 0.9, "p95" -> 0.95, "p99" -> 0.99)
  // Walk CDF, find loss where cumulative/total >= target
  // Returns Map("p50" -> 1200000.0, "p90" -> 4500000.0, ...)
```

### 2. Exceedance probability function (arity 2 — node × threshold → probability)

| FOL Symbol | Scala backing | Return type | Description |
|---|---|---|---|
| `lec(x, t)` | `result.probOfExceedance(threshold)` | `Double` | P(Loss ≥ threshold) from the empirical LEC curve |

`probOfExceedance` implementation:

```scala
// LossDistribution.scala (trait LECCurve)
def probOfExceedance(threshold: Loss): Double =
  outcomeCount.rangeFrom(threshold).values.sum.toDouble / nTrials.toDouble
```

Uses `TreeMap.rangeFrom(threshold)` — O(log n) for the lookup, then sums
frequencies of all outcomes ≥ threshold.

### 3. Comparison predicates (arity 2 — numeric × numeric → Boolean)

| FOL Symbol | Semantics | Implementation |
|---|---|---|
| `>(a, b)` | `a > b` | `a.doubleValue > b.doubleValue` |
| `<(a, b)` | `a < b` | `a.doubleValue < b.doubleValue` |
| `>=(a, b)` | `a ≥ b` | `a.doubleValue >= b.doubleValue` |
| `<=(a, b)` | `a ≤ b` | `a.doubleValue <= b.doubleValue` |

These must handle mixed `Long`/`Double` comparisons
(quantile functions return `Double`, `lec` returns `Double`,
literals are `Long`). Comparing via `.doubleValue` is sufficient for the
magnitudes involved (loss amounts in millions).

### 4. Numeric literal resolution

When the evaluator encounters `Term.Const("5000000")` or
`Term.Fn("5000000", Nil)`, it calls `getFunction("5000000")`. The
augmented model must override `getFunction` to parse all-digit strings
as `Long` values: `_ => name.toLong`. This follows the pattern already
present in `FOLSemantics.integerModel`.

### 5. Potential future functions (not required now, but the extension mechanism should accommodate)

| FOL Symbol | Arity | Description |
|---|---|---|
| `maxLoss(x)` | 1 | Maximum observed loss across all trials |
| `minLoss(x)` | 1 | Minimum observed loss across all trials |
| `nTrials(x)` | 1 | Number of simulation trials backing this node |
| `quantile(x, p)` | 2 | Arbitrary percentile lookup (e.g., `quantile(x, 0.995)`) |

---

## How Register Will Use the Extension Point

Register would call something like:

```scala
VagueSemantics.evaluate(
  query          = parsedQuery,
  source         = riskTreeKnowledgeBase.source,   // structural facts
  answerTuple    = Map.empty,
  samplingParams = SamplingParams.exact,
  hdrConfig      = HDRConfig.default,
  modelAugmenter = baseModel => riskTreeKnowledgeBase.augmentModel(baseModel)
  // ^^^ or whatever the API shape turns out to be
)
```

Where `augmentModel` adds entries to `funcInterp` and `predInterp` and
overrides `getFunction` for numeric literals.

---

## Current Internal Flow (for reference)

```
VagueSemantics.evaluate(query, source, answerTuple, params, config)
  → toResolved(query, source, answerTuple, params, config)
    → RangeExtractor.extractRange(source, query, answerTuple)   // range elements
    → FOLBridge.scopeToPredicate(query.scope, query.variable, source, answerTuple)
        → KnowledgeSourceModel.toModel(source)                  // ← builds Model[Any] HERE
        → creates closure: RelationValue => Boolean
    → VagueQuantifier.fromQuantifier(query.quantifier)
    → ResolvedQuery(quantifier, elements, predicate, params, config)
  → resolvedQuery.evaluateWithOutput()
    → returns EvaluationOutput(result, rangeElements, satisfyingElements)
```

The injection point should be between `KnowledgeSourceModel.toModel(source)`
and the closure creation in `FOLBridge.scopeToPredicate`. Alternatively,
`toResolved` could accept an augmenter and pass it through.

---

## Design Suggestions (non-prescriptive)

Some possible approaches (choose whatever fits the library's design best):

1. **`FOLBridge.scopeToPredicate` overload** accepting `Model[Any]`
   directly (instead of `KnowledgeSource`)
2. **`FOLBridge.scopeToPredicate` overload** accepting
   `Model[Any] => Model[Any]` augmenter
3. **`VagueSemantics.evaluate`/`holds` overload** with an additional
   `modelAugmenter: Model[Any] => Model[Any] = identity` parameter
4. **New parameter on `toResolved`** (private → internal, or made public
   if useful)

The key requirement is that register can pass structural facts via
`KnowledgeSource` (for range extraction) and *also* inject
simulation-backed functions into the model (for scope evaluation)
without bypassing the `VagueSemantics` facade.

---

## Acceptance Criteria

1. All existing tests pass unchanged (792 tests)
2. The existing `VagueSemantics.evaluate(query, source, ...)` signature
   remains valid (source compatibility)
3. A new code path allows callers to inject custom `funcInterp` and
   `predInterp` entries
4. A test demonstrates evaluating a query with a custom function (e.g.,
   a mock `score(x)` function returning an integer, then a
   `>(score(x), 50)` comparison)
5. `sbt publishLocal` produces an updated artifact
