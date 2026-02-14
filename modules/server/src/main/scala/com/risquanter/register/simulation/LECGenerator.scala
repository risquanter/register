package com.risquanter.register.simulation

import com.risquanter.register.domain.data.RiskResult
import scala.collection.immutable.TreeMap

/** Utility for generating Loss Exceedance Curve (LEC) data from simulation outcomes.
  * 
  * Key concepts:
  * - Loss values are in millions: 1L = $1M
  * - Quantiles represent loss thresholds at specific percentiles
  * - Exceedance curve shows P(Loss >= x) for various loss levels
  * 
  * BCG Implementation Notes:
  * - Uses evenly-spaced ticks over [minLoss, maxLoss] range
  * - Default nEntries = 100 provides smooth curves
  * 
  * TODO: Future optimization - implement adaptive sampling (Option C):
  * - Log-scale for fat-tailed distributions
  * - Percentile-based for critical thresholds
  * - Hybrid approach with guaranteed key quantiles
  */
object LECGenerator {
  
  /** Calculate key quantiles from simulation outcomes
    * 
    * @param result Risk simulation result with outcome counts
    * @return Map of quantile names to loss values (in millions)
    *         Keys: "p50" (median), "p90", "p95", "p99"
    */
  def calculateQuantiles(result: RiskResult): Map[String, Double] = {
    val outcomes = result.outcomeCount
    if (outcomes.isEmpty) return Map.empty
    
    val totalTrials = outcomes.values.sum
    if (totalTrials == 0) return Map.empty
    
    // Build cumulative distribution
    var cumulativeCount = 0L
    val cumulativeProbs = outcomes.map { case (loss, count) =>
      cumulativeCount += count
      loss -> (cumulativeCount.toDouble / totalTrials)
    }
    
    // Find quantiles
    def findQuantile(p: Double): Double = {
      cumulativeProbs.find(_._2 >= p) match {
        case Some((loss, _)) => loss.toDouble
        case None => cumulativeProbs.lastOption.map(_._1.toDouble).getOrElse(0.0)
      }
    }
    
    Map(
      "p50" -> findQuantile(0.50),
      "p90" -> findQuantile(0.90),
      "p95" -> findQuantile(0.95),
      "p99" -> findQuantile(0.99)
    )
  }
  
  /** Generate Vega-Lite JSON specification for exceedance curve visualization
    * 
    * Creates a step chart showing P(Loss >= x) vs Loss
    * 
    * @param result Risk simulation result
    * @param maxPoints Maximum number of data points (default 100 for performance)
    * @return Vega-Lite JSON as string, or None if no data
    */
  def generateVegaLiteSpec(result: RiskResult, maxPoints: Int = 100): Option[String] = {
    val outcomes = result.outcomeCount
    if (outcomes.isEmpty) return None
    
    val totalTrials = outcomes.values.sum
    if (totalTrials == 0) return None
    
    // Sample points for large datasets
    val sampledOutcomes = if (outcomes.size > maxPoints) {
      val step = outcomes.size / maxPoints
      outcomes.toSeq.zipWithIndex
        .filter { case (_, idx) => idx % step == 0 }
        .map(_._1)
        .to(TreeMap)
    } else {
      outcomes
    }
    
    // Generate exceedance curve data points
    val dataPoints = sampledOutcomes.map { case (loss, _) =>
      val exceedProb = result.probOfExceedance(loss)
      s"""{"loss": $loss, "exceedance": ${exceedProb.toDouble}}"""
    }.mkString(",\n      ")
    
    val spec = s"""{
      "$$schema": "https://vega.github.io/schema/vega-lite/v5.json",
      "description": "Loss Exceedance Curve showing P(Loss >= x)",
      "title": "Loss Exceedance Curve",
      "width": 600,
      "height": 400,
      "data": {
        "values": [
      $dataPoints
        ]
      },
      "mark": "line",
      "encoding": {
        "x": {
          "field": "loss",
          "type": "quantitative",
          "title": "Loss (Millions)",
          "scale": {"zero": false}
        },
        "y": {
          "field": "exceedance",
          "type": "quantitative",
          "title": "Probability of Exceedance",
          "scale": {"domain": [0, 1]}
        }
      }
    }"""
    
    Some(spec)
  }
  
  /** Generate both quantiles and Vega-Lite spec in one pass
    * More efficient than calling both methods separately
    */
  def generateLEC(result: RiskResult, maxVegaPoints: Int = 100): (Map[String, Double], Option[String]) = {
    (calculateQuantiles(result), generateVegaLiteSpec(result, maxVegaPoints))
  }
  
  /** Generate evenly-spaced loss ticks for LEC curve sampling
    * 
    * BCG approach: Linear spacing over [minLoss, maxLoss * 1.1]
    * Uses actual minimum from data (not hardcoded 0)
    * 
    * @param minLoss Minimum loss observed in simulation results
    * @param maxLoss Maximum loss observed in simulation results
    * @param nEntries Number of sample points (default 100)
    * @return Vector of loss values to sample
    */
  def getTicks(minLoss: Long, maxLoss: Long, nEntries: Int = 100): Vector[Long] = {
    require(nEntries > 1, "nEntries must be > 1")
    require(minLoss >= 0, "minLoss must be >= 0")
    require(maxLoss >= minLoss, "maxLoss must be >= minLoss")
    
    if (minLoss == maxLoss) return Vector(minLoss)
    
    // Add 10% buffer to max for better visualization
    val maxTick = if (maxLoss < Long.MaxValue / 11) (maxLoss * 11) / 10 else maxLoss
    val minTick = minLoss.max(1L)  // Use actual min, but avoid 0 for log-scale compatibility
    
    val step = (maxTick - minTick) / (nEntries - 1)
    if (step == 0) return Vector(minTick)
    
    val range = minTick to maxTick by step
    range.toVector
  }
  
  /** Generate LEC curve data points (loss → exceedance probability)
    * 
    * @param result Risk simulation result
    * @param nEntries Number of sample points
    * @return Vector of (loss, exceedanceProbability) tuples
    */
  def generateCurvePoints(result: RiskResult, nEntries: Int = 100): Vector[(Long, Double)] = {
    if (result.outcomeCount.isEmpty) return Vector.empty
    
    val minLoss = result.minLoss
    val maxLoss = result.maxLoss
    val ticks = getTicks(minLoss, maxLoss, nEntries)
    
    ticks.map { loss =>
      val exceedanceProb = result.probOfExceedance(loss).toDouble
      (loss, exceedanceProb)
    }
  }
  
  /** Generate LEC curves for multiple nodes with a shared tick domain.
    * 
    * When displaying multiple LEC curves together, they must share the same
    * X-axis (loss ticks) for proper comparison. This method:
    * 1. Computes the combined loss range across all results
    * 2. Generates a shared tick domain covering that range
    * 3. Computes exact exceedance probabilities for each result at each tick
    * 
    * This is the core of ADR-014's render-time computation strategy:
    * - No interpolation (mathematically exact probOfExceedance)
    * - Display-context dependent tick domain
    * - Cached RiskResult enables this without re-simulation
    * 
    * @param results Map of node ID to RiskResult (simulation outcomes)
    * @param nEntries Number of sample points for the shared tick domain
    * @return Map of node ID to curve points (loss, exceedanceProbability)
    */
  /** Visual-only exceedance threshold for chart tail trimming.
    *
    * Ticks where every curve drops below this value are removed from the
    * rendered chart data. The underlying RiskResult and all analytical
    * queries (probOfExceedance, quantiles, aggregation) remain unaffected.
    *
    * 0.5% corresponds to the Solvency II 1-in-200 year return period —
    * the most conservative regulatory floor in common use.
    *
    * @see docs/LEC-TAIL-TRIMMING.md for full rationale and references.
    */
  val tailCutoff: Double = 0.005

  def generateCurvePointsMulti[K](
    results: Map[K, RiskResult], 
    nEntries: Int = 100
  ): Map[K, Vector[(Long, Double)]] = {
    if (results.isEmpty) return Map.empty
    
    // Filter out empty results
    val nonEmpty = results.filter(_._2.outcomeCount.nonEmpty)
    if (nonEmpty.isEmpty) return results.map((k, _) => k -> Vector.empty)
    
    // Compute combined loss range
    val combinedMin = nonEmpty.values.map(_.minLoss).min
    val combinedMax = nonEmpty.values.map(_.maxLoss).max
    
    // Generate shared tick domain
    val sharedTicks = getTicks(combinedMin, combinedMax, nEntries)
    
    // Evaluate all curves at every tick
    val evaluated: Map[K, Vector[(Long, Double)]] = results.map { case (nodeId, result) =>
      if (result.outcomeCount.isEmpty) {
        nodeId -> Vector.empty
      } else {
        nodeId -> sharedTicks.map { loss =>
          (loss, result.probOfExceedance(loss).toDouble)
        }
      }
    }

    // Trim the uninformative tail: keep ticks where at least one curve
    // still has meaningful exceedance, plus one tick beyond (smooth end).
    val nonEmptyCurves = evaluated.values.filter(_.nonEmpty).toVector
    if (nonEmptyCurves.isEmpty) return evaluated

    val lastMeaningfulIdx = sharedTicks.indices.reverse.find { i =>
      nonEmptyCurves.exists(curve => curve(i)._2 >= tailCutoff)
    }.getOrElse(sharedTicks.size - 1)

    // Keep one tick past the cutoff so the curve visually reaches near-zero
    val trimIdx = math.min(lastMeaningfulIdx + 1, sharedTicks.size - 1)

    evaluated.map { case (k, pts) =>
      k -> (if (pts.isEmpty) pts else pts.take(trimIdx + 1))
    }
  }
}
