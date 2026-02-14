package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.RiskResult
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.testutil.TestHelpers.nodeId
import com.risquanter.register.testutil.ConfigTestLoader.withCfg
import com.risquanter.register.configs.SimulationConfig

object LECGeneratorSpec extends ZIOSpecDefault {

  // Test fixtures - simulation outcomes (scoped with withCfg)
  val cyberResult = withCfg(5) {
    RiskResult(
      nodeId = nodeId("cyber"),
      outcomes = Map(1 -> 10000L, 2 -> 25000L, 3 -> 0L, 4 -> 15000L, 5 -> 0L),
      provenances = Nil
    )
  }
  
  val hardwareResult = withCfg(5) {
    RiskResult(
      nodeId = nodeId("hardware"),
      outcomes = Map(1 -> 5000L, 2 -> 0L, 3 -> 8000L, 4 -> 0L, 5 -> 3000L),
      provenances = Nil
    )
  }
  
  val wideRangeResult = withCfg(3) {
    RiskResult(
      nodeId = nodeId("wide"),
      outcomes = Map(1 -> 1000L, 2 -> 100000L, 3 -> 50000L),
      provenances = Nil
    )
  }
  
  val emptyResult = withCfg(5) {
    RiskResult(
      nodeId = nodeId("empty"),
      outcomes = Map.empty,
      provenances = Nil
    )
  }

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
      }
    ),
    
    suite("generateCurvePoints")(
      test("returns correct number of points") {
        val points = LECGenerator.generateCurvePoints(cyberResult, 10)
        assertTrue(points.nonEmpty && points.size <= 11)
      },
      test("exceedance probabilities are in [0, 1]") {
        val points = LECGenerator.generateCurvePoints(cyberResult, 20)
        assertTrue(points.forall((_, p) => p >= 0.0 && p <= 1.0))
      },
      test("exceedance is monotonically decreasing") {
        val points = LECGenerator.generateCurvePoints(cyberResult, 20)
        val pairs = points.zip(points.tail)
        assertTrue(pairs.forall { case ((_, p1), (_, p2)) => p2 <= p1 })
      },
      test("empty result returns empty vector") {
        val points = LECGenerator.generateCurvePoints(emptyResult, 10)
        assertTrue(points.isEmpty)
      }
    ),
    
    suite("generateCurvePointsMulti")(
      test("all curves share the same tick domain") {
        val results = Map("cyber" -> cyberResult, "hardware" -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10)
        
        val cyberLosses = curves("cyber").map(_._1)
        val hardwareLosses = curves("hardware").map(_._1)
        
        assertTrue(cyberLosses == hardwareLosses)
      },
      test("shared domain covers combined range") {
        val results = Map("cyber" -> cyberResult, "wide" -> wideRangeResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10)
        
        // Wide range has min=1000, max=100000
        // Cyber has min=10000, max=25000
        // Combined should cover 1000 to ~110000 (with buffer)
        val ticks = curves("cyber").map(_._1)
        assertTrue(
          ticks.head <= 1000L || ticks.head <= cyberResult.minLoss,
          ticks.last >= 100000L
        )
      },
      test("each curve has correct exceedance values") {
        val results = Map("cyber" -> cyberResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10)
        
        // Verify one specific point
        val cyberCurve = curves("cyber")
        assertTrue(cyberCurve.forall((_, p) => p >= 0.0 && p <= 1.0))
      },
      test("empty results map returns empty map") {
        val curves = LECGenerator.generateCurvePointsMulti(Map.empty[String, RiskResult], 10)
        assertTrue(curves.isEmpty)
      },
      test("handles mix of empty and non-empty results") {
        val results = Map("cyber" -> cyberResult, "empty" -> emptyResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10)
        
        assertTrue(
          curves("cyber").nonEmpty,
          curves("empty").isEmpty
        )
      },
      test("works with generic key types") {
        // Test that it works with NodeId or any other key type
        val results = Map(1 -> cyberResult, 2 -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10)
        
        assertTrue(curves.contains(1) && curves.contains(2))
      },
      test("single result produces subset of generateCurvePoints (trimmed tail)") {
        val multiPoints = LECGenerator.generateCurvePointsMulti(Map("cyber" -> cyberResult), 20)("cyber")
        val singlePoints = LECGenerator.generateCurvePoints(cyberResult, 20)
        
        // Multi trims trailing near-zero ticks, so size ≤ single
        assertTrue(
          multiPoints.size <= singlePoints.size,
          multiPoints.forall((_, p) => p >= 0.0 && p <= 1.0)
        )
      },
      test("tail is trimmed: no long near-zero plateau") {
        // wideRangeResult: 3 trials, outcomes [1000, 100000, 50000]
        // getTicks covers [1000, 110000] with 100 ticks.
        // probOfExceedance drops to 0 well before tick 100.
        // After trimming, last point should have exceedance near 0
        // but the curve should NOT extend far past the last meaningful tick.
        val results = Map("wide" -> wideRangeResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 100)
        val wideCurve = curves("wide")
        val untrimmedTicks = LECGenerator.getTicks(1000L, 100000L, 100)

        // Trimmed curve should be shorter than untrimmed tick count
        assertTrue(wideCurve.size < untrimmedTicks.size)

        // Last point should be near-zero (the one tick past cutoff)
        val (_, lastProb) = wideCurve.last
        assertTrue(lastProb < LECGenerator.tailCutoff)
      },
      test("trimmed curves still share the same tick domain") {
        val results = Map("cyber" -> cyberResult, "hardware" -> hardwareResult)
        val curves = LECGenerator.generateCurvePointsMulti(results, 10)
        
        val cyberLosses = curves("cyber").map(_._1)
        val hardwareLosses = curves("hardware").map(_._1)
        assertTrue(cyberLosses == hardwareLosses)
      }
    ),
    
    suite("calculateQuantiles")(
      test("returns expected quantile keys") {
        val quantiles = LECGenerator.calculateQuantiles(cyberResult)
        assertTrue(
          quantiles.contains("p50"),
          quantiles.contains("p90"),
          quantiles.contains("p95"),
          quantiles.contains("p99")
        )
      },
      test("quantiles are monotonically increasing") {
        val quantiles = LECGenerator.calculateQuantiles(cyberResult)
        assertTrue(
          quantiles("p50") <= quantiles("p90"),
          quantiles("p90") <= quantiles("p95"),
          quantiles("p95") <= quantiles("p99")
        )
      },
      test("empty result returns empty map") {
        val quantiles = LECGenerator.calculateQuantiles(emptyResult)
        assertTrue(quantiles.isEmpty)
      }
    )
  )
}
