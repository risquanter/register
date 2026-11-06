package com.risquanter.register.simulation

import io.github.iltotore.iron.refineUnsafe
import com.risquanter.register.domain.data.Loss
import com.risquanter.register.domain.data.LECNodeCurve.QuantileLevel
import com.risquanter.register.domain.data.iron.{OccurrenceProbability, OccurrenceProbabilityConstraint, PositiveInt}

/** Generates Loss Exceedance Curve data from simulation outcomes.
  *
  * A curve gives P(Loss >= x) at evenly spaced ticks. Loss values are in
  * millions, so 1L = $1M.
  */
object LECGenerator {

  /** The unconditional VaR at percentile `p`: the loss below which a fraction
    * `p` of all trials fall, counting the zero-loss trials where the risk did
    * not occur. Returns 0L when no trial recorded an outcome.
    */
  def unconditionalQuantile(result: LossDistribution, p: OccurrenceProbability): Loss =
    val outcomes = result.outcomeCount
    outcomes.lastOption match
      case None => 0L
      case Some((largestLoss, _)) =>
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
            .getOrElse(largestLoss)

  /** The unconditional VaR at every `QuantileLevel`, which is where the tail-only
    * choice of levels is stated. Empty when no trial recorded an outcome.
    */
  def calculateQuantiles(result: LossDistribution): Map[QuantileLevel, Double] =
    QuantileLevel.values.toList.flatMap(level =>
      findQuantileLoss(result, level.probability).map(loss => level -> loss.toDouble)).toMap

  /** Average Annual Loss: the mean loss across all trials, zero-loss trials
    * included. A percentile answers how bad it can get; this answers what to
    * budget for on average.
    */
  def averageAnnualLoss(result: LossDistribution): Double =
    val totalLoss = result.outcomeCount.iterator.map { case (loss, count) => loss.toDouble * count }.sum
    totalLoss / result.nTrials.toDouble

  /** Probability that this risk causes no loss in a given trial, counting both
    * trials absent from the sparse map and explicit zero-valued outcomes — the
    * two sources `unconditionalQuantile` also treats as at or below zero.
    */
  def probabilityOfNoLoss(result: LossDistribution): OccurrenceProbability =
    val implicitZeros = result.nTrials.toLong - result.outcomeCount.values.sum.toLong
    val explicitZeros = result.outcomeCount.getOrElse(0L, 0).toLong
    result.trials.shareOf(implicitZeros + explicitZeros)

  /** The unconditional VaR at `percentile`, or None when there are no outcomes.
    * Clips tick ranges to a meaningful percentile rather than `maxLoss`, which
    * is one outlier and stretches the x-axis past the informative range.
    */
  def findQuantileLoss(result: LossDistribution, percentile: OccurrenceProbability): Option[Loss] =
    Option.when(result.outcomeCount.nonEmpty) {
      unconditionalQuantile(result, percentile)
    }
  
  /** Evenly-spaced loss ticks over [minLoss, maxLoss * 1.1], taking the
    * minimum from the data rather than assuming zero. A count of 1 has no
    * spacing to compute and answers the lowest tick alone.
    */
  def getTicks(minLoss: Long, maxLoss: Long, nEntries: PositiveInt): Vector[Long] = {
    require(minLoss >= 0, "minLoss must be >= 0")
    require(maxLoss >= minLoss, "maxLoss must be >= minLoss")

    if (minLoss == maxLoss) Vector(minLoss)
    else {
      // Add 10% buffer to max for better visualization
      val maxTick = if (maxLoss < Long.MaxValue / 11) (maxLoss * 11) / 10 else maxLoss
      val minTick = minLoss.max(1L)  // Use actual min, but avoid 0 for log-scale compatibility

      if (nEntries == 1) Vector(minTick)
      else (minTick to maxTick by math.max(1L, (maxTick - minTick) / (nEntries - 1))).toVector
    }
  }

  /** The thresholds a curve is sampled at. Tick 0 is always included so every
    * curve starts on the y-axis at its occurrence-probability plateau (see
    * `exceedanceAt` for the y-intercept convention).
    */
  private def curveTicks(minLoss: Long, maxLoss: Long, nEntries: PositiveInt): Vector[Long] =
    (0L +: getTicks(minLoss, maxLoss, nEntries)).distinct

  /** One node's curve, evaluated at the given thresholds. */
  private def curveAt(result: LossDistribution, ticks: Vector[Long]): Vector[(Long, OccurrenceProbability)] =
    ticks.map(loss => (loss, exceedanceAt(result, loss)))

  /** Upper end of the tick range: the p99.5 quantile, so outliers do not
    * stretch the x-axis. Falls back to the observed maximum when that quantile
    * sits below `minLoss`, which happens for a risk occurring at or below 0.5%
    * of trials and would otherwise violate `getTicks`' precondition.
    */
  private def clippedMaxLoss(result: LossDistribution): Long = {
    val q = findQuantileLoss(result, QuantileLevel.P995.probability).getOrElse(result.maxLoss)
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
  
  /** Curves as (loss, exceedance probability) pairs for one or more nodes over
    * one shared tick domain, so they can be overlaid and compared on a common
    * x-axis.
    *
    * Every probability is computed exactly at each tick rather than
    * interpolated, which the cached distributions make cheap (ADR-014).
    *
    * @param tailCutoff Ticks where every curve drops below this are dropped from
    *   the rendered data, leaving analytical queries untouched. A cutoff of 0
    *   trims nothing.
    */
  def generateCurvePointsMulti[K](
    results: Map[K, LossDistribution],
    nEntries: PositiveInt,
    tailCutoff: OccurrenceProbability
  ): Map[K, Vector[(Long, OccurrenceProbability)]] =
    results.values.filter(_.outcomeCount.nonEmpty).toList match
      // Covers an empty `results` too: mapping it answers an empty map.
      case Nil => results.map((k, _) => k -> Vector.empty)
      case withOutcomes =>
        // Compute combined loss range, clipping max to p99.5 to avoid
        // extreme outliers stretching the x-axis beyond the informative range.
        // clippedMaxLoss guarantees each per-result value is >= that result's
        // own minLoss, so combinedMax >= combinedMin always holds.
        val combinedMin = withOutcomes.map(_.minLoss).min
        val combinedMax = withOutcomes.map(clippedMaxLoss).max

        val sharedTicks = curveTicks(combinedMin, combinedMax, nEntries)

        // Evaluate all curves at every tick
        val evaluated: Map[K, Vector[(Long, OccurrenceProbability)]] = results.map {
          case (nodeId, result) if result.outcomeCount.isEmpty => nodeId -> Vector.empty
          case (nodeId, result)                                => nodeId -> curveAt(result, sharedTicks)
        }

        trimTail(evaluated, sharedTicks, tailCutoff)

  /** Drop trailing ticks where every curve is below `tailCutoff`, keeping one
    * beyond the last meaningful point for visual continuity.
    */
  private def trimTail[K](
    evaluated: Map[K, Vector[(Long, OccurrenceProbability)]],
    sharedTicks: Vector[Long],
    tailCutoff: OccurrenceProbability
  ): Map[K, Vector[(Long, OccurrenceProbability)]] =
    evaluated.values.filter(_.nonEmpty).toList match
      case Nil => evaluated
      case curves =>
        val lastMeaningfulIdx = sharedTicks.indices.reverse.find { i =>
          curves.exists(curve => curve(i)._2 >= tailCutoff)
        }.getOrElse(sharedTicks.size - 1)

        val trimIdx = math.min(lastMeaningfulIdx + 1, sharedTicks.size - 1)

        evaluated.map { case (k, pts) =>
          k -> pts.take(trimIdx + 1)
        }
}
