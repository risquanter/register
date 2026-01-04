package com.risquanter.register.simulation

import org.apache.commons.math3.distribution.LogNormalDistribution

/** Scala wrapper around Apache Commons Math LogNormalDistribution.
  * 
  * Lognormal distribution is used for "lognormal" mode where user provides
  * confidence interval bounds (minLoss, maxLoss) representing specific percentiles.
  * 
  * The distribution is parameterized by:
  * - μ (mu): Scale parameter - mean of the underlying normal distribution (of log values)
  * - σ (sigma): Shape parameter - standard deviation of the underlying normal (of log values)
  * 
  * For a lognormal distribution with parameters (μ, σ), the quantile function is:
  *   Q(p) = exp(μ + σ * Φ⁻¹(p))
  * where Φ⁻¹ is the inverse standard normal CDF.
  * 
  * BCG Implementation Reference:
  * BCG uses the formula for 90% CI (5th to 95th percentile):
  * ```
  * meanLog = (log(ub) + log(lb)) / 2
  * varLog = (log(ub) - log(lb)) / 3.29
  * ```
  * Note: Despite the name "varLog", this is actually σ (standard deviation), not variance.
  * The 3.29 comes from 2 * 1.645 where 1.645 is the z-score for the 95th percentile.
  * 
  * @param meanLog μ parameter (scale - mean of log values)
  * @param stdLog σ parameter (shape - standard deviation of log values)
  * @see org.apache.commons.math3.distribution.LogNormalDistribution
  */
case class LognormalDistribution(meanLog: Double, stdLog: Double) extends Distribution {
  
  require(stdLog > 0, s"stdLog must be positive, got: $stdLog")
  
  private val underlying = new LogNormalDistribution(meanLog, stdLog)
  
  /** Compute the quantile (inverse CDF) for a given probability.
    * 
    * Uses Apache Commons Math's inverseCumulativeProbability method which
    * implements: Q(p) = exp(μ + σ * Φ⁻¹(p))
    * 
    * @param p Probability in [0, 1]
    * @return The value x such that P(X ≤ x) = p
    */
  def quantile(p: Double): Double = underlying.inverseCumulativeProbability(p)
}

object LognormalDistribution {
  
  /** Create LognormalDistribution from confidence interval bounds.
    * 
    * Matches BCG's implementation using 90% CI (5th to 95th percentile).
    * The formula assumes:
    * - lowerBound is the 5th percentile (P05)
    * - upperBound is the 95th percentile (P95)
    * 
    * Derivation:
    * For lognormal: Q(p) = exp(μ + σ * Φ⁻¹(p))
    * 
    * At P05: exp(μ + σ * (-1.645)) = lowerBound
    * At P95: exp(μ + σ * (+1.645)) = upperBound
    * 
    * Taking logs and solving:
    * μ = (log(upperBound) + log(lowerBound)) / 2
    * σ = (log(upperBound) - log(lowerBound)) / 3.29
    * 
    * where 3.29 ≈ 2 * 1.645 (z-scores for 90% CI)
    * 
    * @param lowerBound Lower bound of 90% CI (5th percentile)
    * @param upperBound Upper bound of 90% CI (95th percentile)
    * @return Right(LognormalDistribution) or Left(ValidationError)
    */
  def fromConfidenceInterval(lowerBound: Long, upperBound: Long): Either[ValidationError, LognormalDistribution] = {
    import scala.math.log
    
    if (lowerBound <= 0 || upperBound <= lowerBound) {
      return Left(ValidationError(List(
        s"Invalid bounds: lowerBound=$lowerBound must be > 0 and < upperBound=$upperBound"
      )))
    }
    
    val meanLog = (log(upperBound.toDouble) + log(lowerBound.toDouble)) / 2.0
    val stdLog = (log(upperBound.toDouble) - log(lowerBound.toDouble)) / 3.29
    
    Right(LognormalDistribution(meanLog, stdLog))
  }
}
