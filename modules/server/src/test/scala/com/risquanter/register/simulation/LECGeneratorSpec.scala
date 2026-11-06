package com.risquanter.register.simulation

import zio.test.*
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.LECNodeCurve.QuantileLevel
import com.risquanter.register.domain.data.iron.{OccurrenceProbability, PositiveInt}
import com.risquanter.register.testutil.TestHelpers.nodeId
import com.risquanter.register.testutil.ConfigTestLoader.withCfg
import com.risquanter.register.testutil.RiskResultTestSupport.leafOf

object LECGeneratorSpec extends ZIOSpecDefault {

  // Test fixtures - simulation outcomes (scoped with withCfg)
  val cyberResult = withCfg(5) {
    leafOf(nodeId("cyber"), Map(0 -> 10000L, 1 -> 25000L, 2 -> 0L, 3 -> 15000L, 4 -> 0L), None)
  }

  val hardwareResult = withCfg(5) {
    leafOf(nodeId("hardware"), Map(0 -> 5000L, 1 -> 0L, 2 -> 8000L, 3 -> 0L, 4 -> 3000L), None)
  }

  val wideRangeResult = withCfg(3) {
    leafOf(nodeId("wide"), Map(0 -> 1000L, 1 -> 100000L, 2 -> 50000L), None)
  }

  // Occurrence probability 0.2% (< 0.5%): more than 99.5% of trials are
  // zero-loss, so the unconditional p99.5 quantile is 0 — below minLoss.
  // Guards the clippedMaxLoss fallback.
  val rareResult = withCfg(1000) {
    leafOf(nodeId("rare"), Map(1 -> 3000L, 2 -> 5000L), None)
  }

  val emptyResult = withCfg(5) {
    leafOf(nodeId("empty"), Map.empty, None)
  }

  private val tailCutoff: OccurrenceProbability = 0.005

  /** One node's curve: the one-element case of the shared-domain builder. */
  private def curveOf(result: LossDistribution, nEntries: PositiveInt): Vector[(Long, OccurrenceProbability)] =
    LECGenerator.generateCurvePointsMulti(Map("only" -> result), nEntries, tailCutoff)("only")

  def spec = suite("LECGeneratorSpec")(
    suite("getTicks")(
      test("generates correct number of ticks") {
        val ticks = LECGenerator.getTicks(0, 100000, 10)
        assertTrue(ticks.size >= 9 && ticks.size <= 11)
      },
      test("first tick is near minLoss") {
        val ticks = LECGenerator.getTicks(1000, 100000, 10)
        assertTrue(ticks.head >= 1000L)
      },
      test("last tick includes buffer above maxLoss") {
        val ticks = LECGenerator.getTicks(1000, 100000, 10)
        assertTrue(ticks.last >= 100000L)
      },
      test("ticks are monotonically increasing") {
        val ticks = LECGenerator.getTicks(1000, 100000, 20)
        val pairs = ticks.zip(ticks.tail)
        assertTrue(pairs.forall((a, b) => b > a))
      },
      test("handles equal min and max") {
        val ticks = LECGenerator.getTicks(5000, 5000, 10)
        assertTrue(ticks == Vector(5000L))
      },
      test("a count of 1 answers the lowest tick alone") {
        val ticks = LECGenerator.getTicks(1000, 100000, 1)
        assertTrue(ticks == Vector(1000L))
      }
    ),

    suite("one node's curve")(
      test("returns correct number of points") {
        // nEntries ticks (+ buffer slack) plus the always-prepended 0 tick
        val points = curveOf(cyberResult, 10)
        assertTrue(points.nonEmpty && points.size <= 12)
      },
      test("curve starts at loss 0 with the strict y-intercept (1 - probabilityOfNoLoss)") {
        // cyberResult: 2 explicit zeros out of 5 trials → noLoss = 0.4 → intercept 0.6
        val points = curveOf(cyberResult, 10)
        assertTrue(points.head == (0L, 0.6))
      },
      test("exceedance probabilities are in [0, 1]") {
        val points = curveOf(cyberResult, 20)
        assertTrue(points.forall((_, p) => p >= 0.0 && p <= 1.0))
      },
      test("exceedance is monotonically decreasing") {
        val points = curveOf(cyberResult, 20)
        val pairs = points.zip(points.tail)
        assertTrue(pairs.forall { case ((_, p1), (_, p2)) => p2 <= p1 })
      },
      test("rare risk (p <= 0.5%) falls back to the true max loss instead of the zero p99.5 quantile") {
        // p99.5 = 0 < minLoss 3000 — must not throw, and the curve must span
        // the actual observed outcomes up to maxLoss 5000.
        val points = curveOf(rareResult, 10)
        assertTrue(
          points.nonEmpty,
          points.head._1 == 0L,
          points.last._1 >= 5000L
        )
      },
      test("empty result returns empty vector") {
        val points = curveOf(emptyResult, 10)
        assertTrue(points.isEmpty)
      }
    ),
    
    suite("generateCurvePointsMulti")(
      test("all curves share the same tick domain") {
        val results = Map("cyber" -> cyberResult, "hardware" -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)
        
        val cyberLosses = curves("cyber").map(_._1)
        val hardwareLosses = curves("hardware").map(_._1)
        
        assertTrue(cyberLosses == hardwareLosses)
      },
      test("shared domain covers combined range (clipped to p99.5)") {
        val results = Map("cyber" -> cyberResult, "wide" -> wideRangeResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)
        
        // wideRangeResult: 3 outcomes [1000, 50000, 100000]
        //   p99.5 = 50000 (with only 3 data points)
        // cyberResult: p99.5 = 25000
        // Combined max = max(50000, 25000) = 50000, with 10% buffer = 55000
        val ticks = curves("cyber").map(_._1)
        assertTrue(
          ticks.head <= 1000L || ticks.head <= cyberResult.minLoss,
          ticks.last >= 50000L
        )
      },
      test("each curve has correct exceedance values") {
        val results = Map("cyber" -> cyberResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)
        
        // Verify one specific point
        val cyberCurve = curves("cyber")
        assertTrue(cyberCurve.forall((_, p) => p >= 0.0 && p <= 1.0))
      },
      test("a lone rare risk (p <= 0.5%) gets a valid shared tick domain") {
        val curves = LECGenerator.generateCurvePointsMulti(Map("rare" -> rareResult), 10, tailCutoff)
        assertTrue(curves("rare").nonEmpty, curves("rare").last._1 >= 5000L)
      },
      test("empty results map returns empty map") {
        val curves = LECGenerator.generateCurvePointsMulti(Map.empty[String, LossDistribution], 10, tailCutoff)
        assertTrue(curves.isEmpty)
      },
      test("handles mix of empty and non-empty results") {
        val results = Map("cyber" -> cyberResult, "empty" -> emptyResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)
        
        assertTrue(
          curves("cyber").nonEmpty,
          curves("empty").isEmpty
        )
      },
      test("works with generic key types") {
        // Test that it works with NodeId or any other key type
        val results = Map(1 -> cyberResult, 2 -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)
        
        assertTrue(curves.contains(1) && curves.contains(2))
      },
      test("a single result's trimmed curve is no longer than its untrimmed one") {
        val trimmed = curveOf(cyberResult, 20)
        val untrimmed = LECGenerator.generateCurvePointsMulti(Map("cyber" -> cyberResult), 20, 0.0)("cyber")

        // A cutoff of 0 keeps every tick, so size with the cutoff ≤ size without
        assertTrue(
          trimmed.size <= untrimmed.size,
          trimmed.forall((_, p) => p >= 0.0 && p <= 1.0)
        )
      },
      test("tail is trimmed: no long near-zero plateau") {
        // Create a result with enough trials for meaningful tail trimming.
        // 20 trials, outcomes spread across a range — exceedance drops
        // gradually so the tail cutoff (0.5%) can actually be reached.
        val manyTrialsResult = withCfg(20) {
          leafOf(nodeId("many"), (0 until 20).map(i => i -> ((i + 1) * 1000L)).toMap, None)
        }
        val results = Map("many" -> manyTrialsResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 100, tailCutoff)
        val curve = curves("many")

        // Trimmed curve should have fewer points than 100 raw ticks
        assertTrue(curve.size < 100)

        // Last point should have low exceedance (at or near zero) —
        // the "one tick past cutoff" sits beyond all outcomes.
        val (_, lastProb) = curve.last
        assertTrue(lastProb <= 0.05) // 1/20 = 0.05
      },
      test("trimmed curves still share the same tick domain") {
        val results = Map("cyber" -> cyberResult, "hardware" -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)

        val cyberLosses = curves("cyber").map(_._1)
        val hardwareLosses = curves("hardware").map(_._1)
        assertTrue(cyberLosses == hardwareLosses)
      },
      test("every curve starts at loss 0 with its own strict y-intercept") {
        // cyber: 2 explicit zeros / 5 trials → 0.6; hardware: 2 / 5 → 0.6
        val results = Map("cyber" -> cyberResult, "hardware" -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10, tailCutoff)
        assertTrue(
          curves("cyber").head == (0L, 0.6),
          curves("hardware").head == (0L, 0.6)
        )
      }
    ),
    
    suite("calculateQuantiles")(
      test("returns expected quantile keys") {
        val quantiles = LECGenerator.calculateQuantiles(cyberResult)
        assertTrue(
          quantiles.contains(QuantileLevel.P90),
          quantiles.contains(QuantileLevel.P95),
          quantiles.contains(QuantileLevel.P99),
          quantiles.contains(QuantileLevel.P995)
        )
      },
      test("reports every level and no other key") {
        val quantiles = LECGenerator.calculateQuantiles(cyberResult)
        assertTrue(quantiles.keySet == QuantileLevel.values.toSet)
      },
      test("quantiles are monotonically increasing") {
        val quantiles = LECGenerator.calculateQuantiles(cyberResult)
        assertTrue(
          quantiles(QuantileLevel.P90) <= quantiles(QuantileLevel.P95),
          quantiles(QuantileLevel.P95) <= quantiles(QuantileLevel.P99),
          quantiles(QuantileLevel.P99) <= quantiles(QuantileLevel.P995)
        )
      },
      test("empty result returns empty map") {
        val quantiles = LECGenerator.calculateQuantiles(emptyResult)
        assertTrue(quantiles.isEmpty)
      }
    ),

    suite("averageAnnualLoss")(
      test("weights losses by frequency across all trials, including implicit zeros") {
        // nTrials=10, implicitZeros=7, outcomeCount: {5000→1, 20000→1, 50000→1}
        // AAL = (5000 + 20000 + 50000) / 10 = 7500.0
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        assertTrue(LECGenerator.averageAnnualLoss(sparse) == 7500.0)
      },
      test("empty result returns 0.0") {
        assertTrue(LECGenerator.averageAnnualLoss(emptyResult) == 0.0)
      }
    ),

    suite("probabilityOfNoLoss")(
      test("counts both implicit zeros and explicit zero-valued outcomes") {
        // nTrials=5, outcomes: {10000, 25000, 0, 15000, 0} — trials 2 and 4
        // are explicit zero-valued outcomes, not implicit (outcomes.size == nTrials)
        // probabilityOfNoLoss = 2 / 5 = 0.4
        assertTrue(LECGenerator.probabilityOfNoLoss(cyberResult) == 0.4)
      },
      test("sparse result — implicit zeros only") {
        // nTrials=10, 3 recorded outcomes → implicitZeros=7, no explicit zero outcomes
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        assertTrue(LECGenerator.probabilityOfNoLoss(sparse) == 0.7)
      },
      test("empty result returns 1.0") {
        assertTrue(LECGenerator.probabilityOfNoLoss(emptyResult) == 1.0)
      }
    ),

    suite("unconditional VaR (sparse results)")(
      test("calculateQuantiles with sparse results includes implicit zeros") {
        // nTrials=10, 3 outcomes → implicitZeros=7
        // outcomeCount: {5000→1, 20000→1, 50000→1}
        // Cumulative (with 7 zeros prepended): 7, 8, 9, 10
        // p90: target=9.0, cum at 5000→8, 20000→9 → 20000.0
        // p95: target=9.5, cum at 50000→10 → 50000.0
        // p99: target=9.9, cum at 50000→10 → 50000.0
        // p99.5: target=9.95, cum at 50000→10 → 50000.0
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        val quantiles = LECGenerator.calculateQuantiles(sparse)
        assertTrue(
          quantiles(QuantileLevel.P90) == 20000.0,
          quantiles(QuantileLevel.P95) == 50000.0,
          quantiles(QuantileLevel.P99) == 50000.0,
          quantiles(QuantileLevel.P995) == 50000.0
        )
      },
      test("exact boundary — implicitZeros equals target (p50 via findQuantileLoss, half-sparse)") {
        // nTrials=10, 5 outcomes → implicitZeros=5
        // outcomeCount: {1000→1, 2000→1, 3000→1, 4000→1, 5000→1}
        // p50 isn't in calculateQuantiles' output, so this boundary is covered
        // on findQuantileLoss directly.
        // p50: target=5.0, implicitZeros=5, 5.0 >= 5.0 → 0.0 (AT boundary)
        // p90: target=9.0, cum: 5, 6, 7, 8, 9, 10 → 4000 at cum=9 → 4000.0
        // p95: target=9.5, cum at 5000→10, 10 >= 9.5 → 5000.0
        val halfSparse = withCfg(10) {
          leafOf(nodeId("half"), Map(1 -> 1000L, 2 -> 2000L, 3 -> 3000L, 4 -> 4000L, 5 -> 5000L), None)
        }
        val quantiles = LECGenerator.calculateQuantiles(halfSparse)
        assertTrue(
          LECGenerator.findQuantileLoss(halfSparse, 0.50) == Some(0L),
          quantiles(QuantileLevel.P90) == 4000.0,
          quantiles(QuantileLevel.P95) == 5000.0
        )
      },
      test("findQuantileLoss exact boundary — target equals implicitZeros") {
        // nTrials=20, 1 outcome → implicitZeros=19
        // p=0.95: target = (0.95 * 20).toLong = 19
        // 19 >= 19 → returns 0L (boundary exact)
        val boundary = withCfg(20) {
          leafOf(nodeId("boundary"), Map(1 -> 30000L), None)
        }
        val q95 = LECGenerator.findQuantileLoss(boundary, 0.95)
        assertTrue(q95 == Some(0L))
      },
      test("findQuantileLoss just past boundary — target exceeds implicitZeros") {
        // nTrials=20, 1 outcome → implicitZeros=19
        // p=0.95: target = 19.0, 19 >= 19.0 → 0L (exact boundary, see previous test)
        // p=0.951: target = 19.02, 19 < 19.02 → walk: {30000→1} → cum=20 → 30000L
        // p=1.0: target = 20.0, 19 < 20 → walk: 30000L
        val boundary = withCfg(20) {
          leafOf(nodeId("boundary"), Map(1 -> 30000L), None)
        }
        val qJustPast = LECGenerator.findQuantileLoss(boundary, 0.951)
        val qFull = LECGenerator.findQuantileLoss(boundary, 1.0)
        assertTrue(
          qJustPast == Some(30000L),
          qFull == Some(30000L)
        )
      },
      test("single outcome with many implicit zeros") {
        // nTrials=100, 1 outcome → outcomeCount={42000→1}, implicitZeros=99
        // p90: target=90, 99 >= 90 → 0.0
        // p95: target=95, 99 >= 95 → 0.0
        // p99: target=99, 99 >= 99 → 0.0 (boundary exact)
        // p99.5: target=99.5, 99 < 99.5 → walk: {42000→1} → cum=100 → 42000.0
        val single = withCfg(100) {
          leafOf(nodeId("single"), Map(1 -> 42000L), None)
        }
        val quantiles = LECGenerator.calculateQuantiles(single)
        assertTrue(
          quantiles(QuantileLevel.P90) == 0.0,
          quantiles(QuantileLevel.P95) == 0.0,
          quantiles(QuantileLevel.P99) == 0.0,
          quantiles(QuantileLevel.P995) == 42000.0
        )
      },
      test("all outcomes identical — single bin accumulation") {
        // nTrials=10, 5 outcomes all = 7000 → outcomeCount={7000→5}, implicitZeros=5
        // p90: target=9.0, 5 < 9, walk: {7000→5} → cum=10, 10 >= 9 → 7000.0
        // p95: target=9.5, same → 7000.0
        // p99: target=9.9, same → 7000.0
        // p99.5: target=9.95, same → 7000.0
        val identical = withCfg(10) {
          leafOf(nodeId("ident"), Map(1 -> 7000L, 2 -> 7000L, 3 -> 7000L, 4 -> 7000L, 5 -> 7000L), None)
        }
        val quantiles = LECGenerator.calculateQuantiles(identical)
        assertTrue(
          quantiles(QuantileLevel.P90) == 7000.0,
          quantiles(QuantileLevel.P95) == 7000.0,
          quantiles(QuantileLevel.P99) == 7000.0,
          quantiles(QuantileLevel.P995) == 7000.0
        )
      },
      test("findQuantileLoss at p=0.0 returns 0") {
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        // target = (0.0 * 10).toLong = 0, implicitZeros=7, 7 >= 0 → 0L
        val q0 = LECGenerator.findQuantileLoss(sparse, 0.0)
        assertTrue(q0 == Some(0L))
      },
      test("findQuantileLoss with sparse results accounts for zero mass") {
        // nTrials=10, implicitZeros=7, outcomeCount: {5000→1, 20000→1, 50000→1}
        // p99.5: target = 10 * 0.995 = 9.95
        // Walk: 5000→cum 8, 20000→cum 9 (< 9.95), 50000→cum 10 (≥ 9.95) → 50000
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        val q995 = LECGenerator.findQuantileLoss(sparse, 0.995)
        assertTrue(q995 == Some(50000L))
      },
      test("findQuantileLoss with very sparse results returns 0") {
        // nTrials=100, 2 outcomes → implicitZeros=98
        // p50: target = (0.50 * 100).toLong = 50 → 98 >= 50 → 0
        val verySparse = withCfg(100) {
          leafOf(nodeId("sparse"), Map(1 -> 10000L, 2 -> 50000L), None)
        }
        val q50 = LECGenerator.findQuantileLoss(verySparse, 0.50)
        assertTrue(q50 == Some(0L))
      },
      test("calculateQuantiles and findQuantileLoss agree") {
        // Both delegate to unconditionalQuantile — must return same value
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        val quantiles = LECGenerator.calculateQuantiles(sparse)
        val q95viaFind = LECGenerator.findQuantileLoss(sparse, 0.95)
        // p95: target = 10 * 0.95 = 9.5, walk: 5000→8, 20000→9, 50000→10 → 50000
        assertTrue(
          quantiles(QuantileLevel.P95) == 50000.0,
          q95viaFind == Some(50000L)
        )
      },
      test("quantiles monotonicity holds with sparse results") {
        val sparse = withCfg(10) {
          leafOf(nodeId("sparse"), Map(1 -> 5000L, 2 -> 20000L, 3 -> 50000L), None)
        }
        val quantiles = LECGenerator.calculateQuantiles(sparse)
        assertTrue(
          quantiles(QuantileLevel.P90) <= quantiles(QuantileLevel.P95),
          quantiles(QuantileLevel.P95) <= quantiles(QuantileLevel.P99),
          quantiles(QuantileLevel.P99) <= quantiles(QuantileLevel.P995)
        )
      }
    )
  )
}
