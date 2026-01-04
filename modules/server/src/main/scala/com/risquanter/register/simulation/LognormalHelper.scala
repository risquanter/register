package com.risquanter.register.simulation

import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*

/** Helper for creating LognormalDistribution from confidence interval bounds.
  * 
  * BCG Implementation Note:
  * Uses 90% confidence interval (minLoss = P05, maxLoss = P95) to parameterize lognormal:
  * - meanLog = (log(max) + log(min)) / 2
  * - stdLog = (log(max) - log(min)) / 3.29
  * 
  * where 3.29 = 2 * 1.645 (z-scores for 90% CI: P05 to P95)
  * 
  * Returns LognormalDistribution directly (BCG approach), not Metalog approximation.
  */
object LognormalHelper {
  
  /** Create LognormalDistribution from 90% confidence interval bounds.
    * 
    * Matches BCG's implementation using 90% CI (5th to 95th percentile).
    * 
    * Formula:
    * ```
    * meanLog = (log(max) + log(min)) / 2
    * stdLog = (log(max) - log(min)) / 3.29
    * ```
    * 
    * Derivation: For lognormal, quantile Q(p) = exp(μ + σ * Φ^-1(p))
    * where Φ^-1 is inverse standard normal CDF.
    * 
    * For 90% CI: Φ^-1(0.05) ≈ -1.645, Φ^-1(0.95) ≈ 1.645
    * Setting Q(0.05) = minLoss and Q(0.95) = maxLoss:
    *   μ + σ * (-1.645) = log(minLoss)
    *   μ + σ * (1.645) = log(maxLoss)
    * Solving: σ = (log(maxLoss) - log(minLoss)) / 3.29
    * 
    * @param minLoss 90% CI lower bound (5th percentile) in millions
    * @param maxLoss 90% CI upper bound (95th percentile) in millions
    * @return LognormalDistribution wrapping Apache Commons Math implementation
    */
  def fromLognormal90CI(minLoss: Long, maxLoss: Long): Either[ValidationError, LognormalDistribution] = {
    if (minLoss <= 0 || maxLoss <= minLoss) {
      return Left(ValidationError(List(s"Invalid bounds: minLoss=$minLoss must be > 0 and < maxLoss=$maxLoss")))
    }
    LognormalDistribution.fromConfidenceInterval(minLoss, maxLoss)
  }
}
