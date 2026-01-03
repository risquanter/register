package com.risquanter.register.simulation

import com.risquanter.register.domain.data.RiskResult
import scala.collection.immutable.TreeMap

/** Utility for generating Loss Exceedance Curve (LEC) data from simulation results
  * 
  * Key concepts:
  * - Loss values are in millions: 1L = $1M
  * - Quantiles represent loss thresholds at specific percentiles
  * - Exceedance curve shows P(Loss >= x) for various loss levels
  */
object LECGenerator {
  
  /** Calculate key quantiles from simulation results
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
}
