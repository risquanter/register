# Mathematical Test Implementation Plan

## Summary
Created comprehensive mathematical test suites for distribution correctness. Tests encountered compilation errors that need fixes before running.

## Files Created

### 1. MetalogDistributionPropertySpec.scala ⚠️ NEEDS FIXES
**Purpose**: Property-based testing for Metalog distribution  
**Test Categories**:
- CDF/Quantile mathematical properties (monotonicity, bounds)
- Bounded distribution constraints
- Statistical moments (mean, median, variance)
- Edge cases and robustness
- Sample method equivalence

**Issues to Fix**:
- `assertTrue` doesn't accept description strings - remove them or use `assertTrue(...) && assertTrue(..., message)`
- Range syntax: Use `(0.01 to 0.99).by(0.01)` instead of `to ... by` infix
- Property generators need work - Gen.function doesn't produce Array[Double]

### 2. LognormalPropertySpec.scala ⚠️ NEEDS FIXES  
**Purpose**: Mathematical correctness of lognormal 80% CI implementation  
**Test Categories**:
- 80% CI parameter transformation (BCG formula)
- Lognormal statistical properties (mean, median, skewness)
- Quantile function accuracy
- Edge cases (narrow/wide ranges, extreme values)
- Metalog approximation quality

**Issues to Fix**:
- Same `assertTrue` description string issues
- Same range syntax issues (`to ... by`)
- Need import for BigDecimal conversion: `import scala.math.BigDecimal.double2bigDecimal`

### 3. PerformanceBenchmarkSpec.scala ⚠️ NEEDS FIXES
**Purpose**: Measure Monte Carlo simulation performance  
**Test Categories**:
- Baseline performance (10k, 100k, 1M trials)
- Scalability (portfolio size impact)
- Parallelism effectiveness
- Memory efficiency
- Stress tests

**Issues to Fix**:
- Same `assertTrue` description issues
- `result.values` - Simulator.simulateTree returns `RiskTreeResult`, not Map
- Need to check actual return type from Simulator

---

## ✅ What Was Successfully Removed

1. **Obsolete TODO in RiskTreeServiceLiveSpec.scala** - Removed line 90 comment about incomplete convertResultToLEC

---

## 🔧 Recommended Next Steps

### Option A: Fix and Run Tests (2-3 hours)
1. Fix `assertTrue` calls - remove description strings, restructure assertions
2. Fix range syntax - add BigDecimal import or use explicit loops
3. Fix `RiskTreeResult` access - check Simulator API, may need `.results` or similar
4. Run tests: `sbt "project server" test`
5. Document performance results

### Option B: Simpler Math Validation (30 minutes)
Instead of elaborate property tests, add focused unit tests:

```scala
test("Lognormal P10/P90 match input bounds") {
  LognormalHelper.fromLognormal80CI(1000L, 50000L) match {
    case Right(metalog) =>
      val p10 = metalog.quantile(0.10)
      val p90 = metalog.quantile(0.90)
      assertTrue(
        math.abs(p10 - 1000) / 1000 < 0.05 &&
        math.abs(p90 - 50000) / 50000 < 0.05
      )
    case Left(_) => assertTrue(false)
  }
}

test("Metalog interpolation accuracy") {
  val percentiles = probArray(0.1, 0.5, 0.9)
  val quantiles = Array(1000.0, 5000.0, 20000.0)
  
  MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3) match {
    case Right(metalog) =>
      val errors = percentiles.zip(quantiles).map { case (p, expectedQ) =>
        val actualQ = metalog.quantile(p: Double)
        math.abs(actualQ - expectedQ) / expectedQ
      }
      assertTrue(errors.max < 0.01)  // < 1% error
    case Left(_) => assertTrue(false)
  }
}

test("Quantile function is monotonic") {
  val percentiles = probArray(0.1, 0.5, 0.9)
  val quantiles = Array(1000.0, 5000.0, 20000.0)
  
  MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3) match {
    case Right(metalog) =>
      val testPoints = List(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)
      val quantileValues = testPoints.map(metalog.quantile)
      val isMonotonic = quantileValues.sliding(2).forall { case Seq(a, b) => a <= b }
      assertTrue(isMonotonic)
    case Left(_) => assertTrue(false)
  }
}

test("Performance: 100k trials completes in < 10 seconds") {
  val risk = RiskLeaf(
    id = "test",
    name = "Test",
    distributionType = "lognormal",
    probability = 0.5,
    minLoss = Some(1000L),
    maxLoss = Some(50000L)
  )
  
  for {
    start <- Clock.currentTime(TimeUnit.MILLISECONDS)
    result <- Simulator.simulateTree(risk, nTrials = 100000, parallelism = 1)
    end <- Clock.currentTime(TimeUnit.MILLISECONDS)
    duration = end - start
  } yield assertTrue(duration < 10000)  // < 10 seconds
}
```

### Option C: Document Current Test Coverage (10 minutes)
Update PROJECT_STATUS.md to note:
- Existing 101 tests provide functional coverage
- Mathematical property tests drafted but need compilation fixes
- Performance benchmarks can be run manually via Simulator API

---

## Key Mathematical Properties to Test

### Metalog Distribution
1. **Monotonicity**: Q(p1) ≤ Q(p2) for p1 < p2
2. **Interpolation Accuracy**: Fitted quantiles match input percentiles within 1%
3. **Bounds Respect**: Semi-bounded (lower=0) never returns negative
4. **Sample Equivalence**: `sample(p) == quantile(p)`

### Lognormal 80% CI
1. **BCG Formula**: P10 ≈ minLoss, P90 ≈ maxLoss (within 5%)
2. **Right Skew**: Mean > Median, (Q3-Median) > (Median-Q1)
3. **Geometric Mean**: Median ≈ sqrt(minLoss × maxLoss) (within 5%)
4. **Non-Negative**: All quantiles ≥ 0 (semi-bounded)

### Performance
1. **10k trials**: < 2 seconds
2. **100k trials**: < 10 seconds
3. **Parallelism**: 4 cores achieve > 1.5x speedup
4. **Memory**: 1M trials completes without OOM

---

## ZIO Test Property-Based Testing Notes

ZIO Test includes property-based testing via `Gen`:
- ✅ Available: `Gen.double`, `Gen.listOfN`, `check(gen1, gen2)`
- ✅ Used in existing tests: HDRWrapperSpec uses `Gen.int`
- ⚠️ Complex: Generator composition can be tricky
- 💡 Alternative: ScalaCheck integration via iron-scalacheck (already in dependencies)

**Recommendation**: For mathematical properties, explicit example-based tests may be clearer than generators. Property testing shines for testing invariants across random inputs, but distributions have known mathematical properties we can test directly.

---

## Status
- ✅ Obsolete TODO removed from RiskTreeServiceLiveSpec
- ⚠️ 3 new test files created but need compilation fixes
- 📝 Current test count: 101 passing (unchanged until new tests compile)
- 🎯 Next: Choose Option A (fix tests), B (simpler tests), or C (document only)
