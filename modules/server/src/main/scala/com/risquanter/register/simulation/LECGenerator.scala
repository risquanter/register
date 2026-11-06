package com.risquanter.register.simulation

import io.github.iltotore.iron.{autoRefine, refineUnsafe}
import com.risquanter.register.domain.data.LECNodeCurve.QuantileLevel
import com.risquanter.register.domain.data.iron.{OccurrenceProbability, OccurrenceProbabilityConstraint}

/** Generates Loss Exceedance Curve data from simulation outcomes.
  *
  * A curve gives P(Loss >= x) at evenly spaced ticks over [minLoss, maxLoss];
  * 100 ticks is the default. Loss values are in millions, so 1L = $1M.
  */
object LECGenerator {

  /** The probability of an event that happens in every trial: what a figure
    * read from no trials at all reports for "no loss". */
  private val Certain: OccurrenceProbability = 1.0

  /** The unconditional VaR at percentile `p`: the loss below which a fraction
    * `p` of all trials fall, counting the zero-loss trials where the risk did
    * not occur. Returns 0L when there is no simulation data.
    */
  def unconditionalQuantile(result: LossDistribution, p: Double): Long =
    val outcomes = result.outcomeCount
    if outcomes.isEmpty || result.nTrials == 0 then 0L
    else
      val implicitZeros = result.nTrials.toLong - outcomes.values.sum.toLong
      val target = result.nTrials.toDouble * p
      if implicitZeros >= target then 0L
      else
        outcomes.iterator
          .scanLeft((0L, implicitZeros)) { case ((_, cum), (loss, count)) =>
            (loss, cum + count)
          }
          .drop(1)
          .find(_._2 >= target)
          .map(_._1)
          .getOrElse(outcomes.lastKey)

  /** The unconditional VaR at every `QuantileLevel`, which is where the tail-only
    * choice of levels is stated. Empty when there is no simulation data.
    */
  def calculateQuantiles(result: LossDistribution): Map[QuantileLevel, Double] =
    if result.outcomeCount.isEmpty || result.nTrials == 0 then Map.empty
    else QuantileLevel.values.map(level =>
      level -> unconditionalQuantile(result, level.probability).toDouble).toMap

  /** Average Annual Loss: the mean loss across all trials, zero-loss trials
    * included. A percentile answers how bad it can get; this answers what to
    * budget for on average. Returns 0.0 when there is no simulation data.
    */
  def averageAnnualLoss(result: LossDistribution): Double =
    if result.nTrials == 0 then 0.0
    else
      val totalLoss = result.outcomeCount.iterator.map { case (loss, count) => loss.toDouble * count }.sum
      totalLoss / result.nTrials.toDouble

  /** Probability that this risk causes no loss in a given trial, counting both
    * trials absent from the sparse map and explicit zero-valued outcomes — the
    * two sources `unconditionalQuantile` also treats as at or below zero.
    * Returns 1.0 when there is no simulation data.
    */
  def probabilityOfNoLoss(result: LossDistribution): OccurrenceProbability =
    if result.nTrials == 0 then Certain
    else
      val implicitZeros = result.nTrials.toLong - result.outcomeCount.values.sum.toLong
      val explicitZeros = result.outcomeCount.getOrElse(0L, 0).toLong
      // A count of trials over the positive total of trials lies in [0, 1], so
      // the refinement cannot fail.
      ((implicitZeros + explicitZeros).toDouble / result.nTrials.toDouble)
        .refineUnsafe[OccurrenceProbabilityConstraint]

  /** The unconditional VaR at `percentile`, or None when there are no outcomes.
    * Clips tick ranges to a meaningful percentile rather than `maxLoss`, which
    * is one outlier and stretches the x-axis past the informative range.
    */
  def findQuantileLoss(result: LossDistribution, percentile: Double): Option[Long] =
    Option.when(result.nTrials > 0 && result.outcomeCount.nonEmpty) {
      unconditionalQuantile(result, percentile)
    }
  
  /** Evenly-spaced loss ticks over [minLoss, maxLoss * 1.1], taking the
    * minimum from the data rather than assuming zero.
    */
  def getTicks(minLoss: Long, maxLoss: Long, nEntries: Int = 100): Vector[Long] = {
    require(nEntries > 1, "nEntries must be > 1")
    require(minLoss >= 0, "minLoss must be >= 0")
    require(maxLoss >= minLoss, "maxLoss must be >= minLoss")
    
    if (minLoss == maxLoss) return Vector(minLoss)
    
    // Add 10% buffer to max for better visualization
    val maxTick = if (maxLoss < Long.MaxValue / 11) (maxLoss * 11) / 10 else maxLoss
    val minTick = minLoss.max(1L)  // Use actual min, but avoid 0 for log-scale compatibility
    
    val step = math.max(1L, (maxTick - minTick) / (nEntries - 1))
    
    val range = minTick to maxTick by step
    range.toVector
  }
  
  /** Curve points as (loss, exceedance probability) pairs. */
  def generateCurvePoints(result: LossDistribution, nEntries: Int = 100): Vector[(Long, OccurrenceProbability)] = {
    if (result.outcomeCount.isEmpty) Vector.empty
    else {
      val minLoss = result.minLoss
      val maxLoss = clippedMaxLoss(result)
      val ticks = (0L +: getTicks(minLoss, maxLoss, nEntries)).distinct

      ticks.map { loss =>
        (loss, exceedanceAt(result, loss))
      }
    }
  }

  /** Upper end of the tick range: the p99.5 quantile, so outliers do not
    * stretch the x-axis. Falls back to the observed maximum when that quantile
    * sits below `minLoss`, which happens for a risk occurring at or below 0.5%
    * of trials and would otherwise violate `getTicks`' precondition.
    */
  private def clippedMaxLoss(result: LossDistribution): Long = {
    val q = findQuantileLoss(result, 0.995).getOrElse(result.maxLoss)
    if (q < result.minLoss) result.maxLoss else q
  }

  /** Exceedance probability at a tick. At loss = 0 the "at least x" reading is
    * trivially 1.0, so the curve starts at the strict "more than x" value
    * instead, meeting the y-axis at the occurrence-probability plateau. Above
    * 0 the two readings coincide on integer losses.
    */
  private def exceedanceAt(result: LossDistribution, loss: Long): OccurrenceProbability =
    // The complement of a probability in [0, 1] is in [0, 1], so the
    // refinement cannot fail.
    if (loss == 0L) (1.0 - probabilityOfNoLoss(result)).refineUnsafe[OccurrenceProbabilityConstraint]
    else result.probOfExceedance(loss)
  
  /** Visual-only threshold for tail trimming: ticks where every curve drops
    * below it are dropped from the rendered data, leaving analytical queries
    * untouched. 0.5% is the Solvency II 1-in-200 year return period.
    */
  val tailCutoff: Double = 0.005

  /** Curves for several nodes over one shared tick domain, so they can be
    * overlaid and compared on a common x-axis.
    *
    * Every probability is computed exactly at each tick rather than
    * interpolated, which the cached distributions make cheap (ADR-014).
    */
  def generateCurvePointsMulti[K](
    results: Map[K, LossDistribution], 
    nEntries: Int = 100
  ): Map[K, Vector[(Long, OccurrenceProbability)]] = {
    if (results.isEmpty) return Map.empty
    
    val nonEmpty = results.filter(_._2.outcomeCount.nonEmpty)
    if (nonEmpty.isEmpty) return results.map((k, _) => k -> Vector.empty)
    
    // Compute combined loss range, clipping max to p99.5 to avoid
    // extreme outliers stretching the x-axis beyond the informative range.
    // clippedMaxLoss guarantees each per-result value is >= that result's
    // own minLoss, so combinedMax >= combinedMin always holds.
    val combinedMin = nonEmpty.values.map(_.minLoss).min
    val combinedMax = nonEmpty.values.map(clippedMaxLoss).max
    
    // Generate shared tick domain. Tick 0 is always included so every
    // curve starts on the y-axis at its occurrence-probability plateau
    // (see exceedanceAt for the y-intercept convention).
    val sharedTicks = (0L +: getTicks(combinedMin, combinedMax, nEntries)).distinct

    // Evaluate all curves at every tick
    val evaluated: Map[K, Vector[(Long, OccurrenceProbability)]] = results.map { case (nodeId, result) =>
      if (result.outcomeCount.isEmpty) nodeId -> Vector.empty
      else nodeId -> sharedTicks.map(loss => (loss, exceedanceAt(result, loss)))
    }

    trimTail(evaluated, sharedTicks)
  }

  /** Drop trailing ticks where every curve is below `tailCutoff`, keeping one
    * beyond the last meaningful point for visual continuity.
    */
  private def trimTail[K](
    evaluated: Map[K, Vector[(Long, OccurrenceProbability)]],
    sharedTicks: Vector[Long]
  ): Map[K, Vector[(Long, OccurrenceProbability)]] = {
    val nonEmptyCurves = evaluated.values.filter(_.nonEmpty).toVector
    if (nonEmptyCurves.isEmpty) return evaluated

    val lastMeaningfulIdx = sharedTicks.indices.reverse.find { i =>
      nonEmptyCurves.exists(curve => curve(i)._2 >= tailCutoff)
    }.getOrElse(sharedTicks.size - 1)

    val trimIdx = math.min(lastMeaningfulIdx + 1, sharedTicks.size - 1)

    evaluated.map { case (k, pts) =>
      k -> (if (pts.isEmpty) pts else pts.take(trimIdx + 1))
    }
  }
}
