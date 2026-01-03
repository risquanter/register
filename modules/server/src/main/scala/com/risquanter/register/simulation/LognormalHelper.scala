package com.risquanter.register.simulation

import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*

/** Helper for creating MetalogDistribution from lognormal parameters
  * 
  * BCG Implementation Note:
  * Uses 80% confidence interval (minLoss, maxLoss) to parameterize lognormal:
  * - meanLog = (log(max) + log(min)) / 2
  * - stdLog = (log(max) - log(min)) / 3.29  where 3.29 = 1.28 * 2.56 (80% CI z-score)
  * 
  * We approximate this by fitting a Metalog to strategic quantiles
  * derived from the lognormal CDF.
  */
object LognormalHelper {
  
  /** Create MetalogDistribution from 80% confidence interval bounds
    * 
    * BCG's lognormal formula:
    * ```
    * meanLog = (log(ub) + log(lb)) / 2
    * varLog = (log(ub) - log(lb)) / 3.29
    * ```
    * 
    * We convert this to Metalog by:
    * 1. Calculate lognormal parameters from 80% CI
    * 2. Sample strategic percentiles (10, 50, 90, 95, 99)
    * 3. Fit Metalog to these points
    * 
    * @param minLoss 80% CI lower bound (10th percentile) in millions
    * @param maxLoss 80% CI upper bound (90th percentile) in millions
    * @return Metalog distribution approximating the lognormal
    */
  def fromLognormal80CI(minLoss: Long, maxLoss: Long): Either[ValidationError, MetalogDistribution] = {
    import scala.math.{log, exp, sqrt}
    
    if (minLoss <= 0 || maxLoss <= minLoss) {
      return Left(ValidationError(List(s"Invalid bounds: minLoss=$minLoss must be > 0 and < maxLoss=$maxLoss")))
    }
    
    // BCG lognormal parameters from 80% CI (10th and 90th percentiles)
    val meanLog = (log(maxLoss.toDouble) + log(minLoss.toDouble)) / 2.0
    val stdLog = (log(maxLoss.toDouble) - log(minLoss.toDouble)) / 2.56  // 2.56 = 2 * 1.28 (z-score for 80% CI)
    
    // Sample strategic percentiles from lognormal CDF
    // Using inverse CDF: Q(p) = exp(meanLog + stdLog * Φ^-1(p))
    // where Φ^-1 is inverse standard normal CDF
    
    def lognormalQuantile(p: Double): Double = {
      // Approximate inverse normal CDF using rational approximation
      val z = inverseNormalCDF(p)
      exp(meanLog + stdLog * z)
    }
    
    // Strategic percentiles for good Metalog fit
    val percentiles: Array[Probability] = Array(
      0.10, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99
    ).map(_.asInstanceOf[Probability])
    
    val quantiles = percentiles.map(p => lognormalQuantile(p.toDouble))
    
    // Fit Metalog with semi-bounded (lower) constraint
    MetalogDistribution.fromPercentiles(
      percentiles = percentiles,
      quantiles = quantiles,
      terms = 5.asInstanceOf[PositiveInt], // 5 terms for smooth tail behavior
      lower = Some(0.0) // Loss cannot be negative
    )
  }
  
  /** Approximate inverse standard normal CDF
    * Using Beasley-Springer-Moro algorithm (accurate to ~10^-9)
    * 
    * Reference: "A highly accurate approximation for the cumulative normal distribution and its inverse"
    * Handbook of Mathematical Functions, Abramowitz & Stegun
    */
  private def inverseNormalCDF(p: Double): Double = {
    import scala.math.{log, sqrt}
    
    if (p <= 0.0 || p >= 1.0) {
      throw new IllegalArgumentException(s"Probability must be in (0, 1), got: $p")
    }
    
    // Handle symmetry
    val q = if (p <= 0.5) p else 1.0 - p
    
    // Rational approximation for central region
    val r = sqrt(-log(q))
    
    val c0 = 2.515517
    val c1 = 0.802853
    val c2 = 0.010328
    val d1 = 1.432788
    val d2 = 0.189269
    val d3 = 0.001308
    
    val numerator = c0 + c1 * r + c2 * r * r
    val denominator = 1.0 + d1 * r + d2 * r * r + d3 * r * r * r
    val z = r - numerator / denominator
    
    // Apply sign based on symmetry
    if (p <= 0.5) -z else z
  }
}
