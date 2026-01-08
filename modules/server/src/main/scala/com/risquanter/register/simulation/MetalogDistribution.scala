package com.risquanter.register.simulation

import com.risquanter.simulation.util.distribution.metalog.{Metalog, QPFitter}
import com.risquanter.register.domain.data.iron.{Probability, PositiveInt}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

/**
 * Validation error for Metalog distribution creation.
 */
case class ValidationError(errors: List[String]) {
  def message: String = errors.mkString("; ")
}

/**
 * Scala wrapper around simulation-util's Metalog distribution.
 * 
 * The Metalog is a flexible quantile-parameterized distribution that can represent
 * a wide variety of shapes (bounded, semi-bounded, unbounded) using percentile-quantile
 * pairs fitted with a quantile function.
 * 
 * Used for "expert" mode where user provides percentile-quantile estimates.
 * 
 * This wrapper adds:
 * - Iron refinement type validation for percentiles (must be in (0,1) - exclusive bounds)
 * - Input validation before calling Java QPFitter
 * - Functional error handling with Either
 * - Distribution trait implementation for uniform interface with Lognormal
 * 
 * @param fitter The underlying Java Metalog instance from simulation-util
 * @see com.risquanter.simulation.util.distribution.metalog.Metalog
 * @see com.risquanter.simulation.util.distribution.metalog.QPFitter
 */
case class MetalogDistribution private(fitter: Metalog) extends Distribution {
  
  /**
   * Compute the quantile (inverse CDF) for a given probability.
   * 
   * @param p Probability in [0, 1]
   * @return The value x such that P(X ≤ x) = p
   */
  def quantile(p: Double): Double = fitter.quantile(p)
}

object MetalogDistribution {
  
  /**
   * Create a Metalog distribution from percentile-quantile pairs.
   * 
   * Uses Iron Probability type to ensure percentiles are in (0,1) (exclusive bounds).
   * The underlying QPFitter library requires strict (0,1) bounds - 0.0 and 1.0 are not valid.
   * 
   * Validates inputs before calling simulation-util's QPFitter to provide
   * better error messages and defensive testing against library changes.
   * 
   * @param percentiles Probabilities in (0, 1) (exclusive), must be sorted ascending
   * @param quantiles Corresponding quantile values
   * @param terms Number of terms in the Metalog expansion (default 9, max = percentiles.length)
   * @param lower Optional lower bound for bounded distribution
   * @param upper Optional upper bound for bounded distribution
   * @return Either validation error or fitted Metalog distribution
   * 
   * @example
   * {{{
   * val percentiles: Array[Probability] = Array(0.05, 0.5, 0.95).map(_.refineUnsafe)
   * val quantiles = Array(1000.0, 5000.0, 25000.0)
   * 
   * MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3) match {
   *   case Right(metalog) => println(s"P95 loss: ${metalog.quantile(0.95)}")
   *   case Left(err) => println(s"Validation failed: ${err.message}")
   * }
   * }}}
   */
  def fromPercentiles(
    percentiles: Array[Probability],
    quantiles: Array[Double],
    terms: PositiveInt = 9.refineUnsafe,
    lower: Option[Double] = None,
    upper: Option[Double] = None
  ): Either[ValidationError, MetalogDistribution] = {
    
    // Collect all validation errors
    val validations = List(
      validateSorted(percentiles),
      validateArrayLengths(percentiles, quantiles),
      validateTerms(terms, percentiles.length),
      validateBounds(lower, upper)
    ).collect { case Left(err) => err }
    
    if (validations.nonEmpty) {
      Left(ValidationError(validations))
    } else {
      // All validations passed, attempt to fit
      try {
        // Iron refined types need explicit mapping for Arrays due to invariance
        val pVals: Array[Double] = percentiles.map(p => p: Double)
        val builder = QPFitter.`with`(pVals, quantiles, terms)
        
        val withBounds = (lower, upper) match {
          case (Some(l), Some(u)) => builder.lower(l).upper(u)
          case (Some(l), None)    => builder.lower(l)
          case (None, Some(u))    => builder.upper(u)
          case (None, None)       => builder
        }
        
        val metalog = withBounds.fit()
        Right(MetalogDistribution(metalog))
      } catch {
        case e: Exception => 
          Left(ValidationError(List(s"QPFitter failed: ${e.getMessage}")))
      }
    }
  }
  
  /**
   * Convenience method for creating Metalog from raw doubles (auto-refines to Probability).
   * Use this when percentiles are known to be valid at compile time.
   */
  def fromPercentilesUnsafe(
    percentiles: Array[Double],
    quantiles: Array[Double],
    terms: Int = 9,
    lower: Option[Double] = None,
    upper: Option[Double] = None
  ): Either[ValidationError, MetalogDistribution] = {
    
    // Validate percentiles are in (0,1) before refining (exclusive bounds per QPFitter)
    if (percentiles.exists(p => p <= 0.0 || p >= 1.0)) {
      Left(ValidationError(List("All percentiles must be in (0.0, 1.0)")))
    } else if (terms <= 0) {
      Left(ValidationError(List("Terms must be positive")))
    } else {
      // Cast to refined array (unsafe but validated above)
      val refinedPercentiles: Array[Probability] = percentiles.asInstanceOf[Array[Probability]]
      val refinedTerms: PositiveInt = terms.asInstanceOf[PositiveInt]
      fromPercentiles(refinedPercentiles, quantiles, refinedTerms, lower, upper)
    }
  }
  
  // Validation helper methods
  
  private def validateSorted(ps: Array[Probability]): Either[String, Unit] = {
    // Convert refined array to base type for comparison
    val values: Array[Double] = ps.map(p => p: Double)
    if (ps.isEmpty) {
      Left("Percentiles array cannot be empty")
    } else if (!values.sorted.sameElements(values)) {
      Left("Percentiles must be sorted in ascending order")
    } else {
      Right(())
    }
  }
  
  private def validateArrayLengths(
    ps: Array[Probability], 
    qs: Array[Double]
  ): Either[String, Unit] = {
    if (qs.isEmpty) {
      Left("Quantiles array cannot be empty")
    } else if (ps.length != qs.length) {
      Left(s"Percentiles (${ps.length}) and quantiles (${qs.length}) must have same length")
    } else {
      Right(())
    }
  }
  
  private def validateTerms(terms: Int, dataPoints: Int): Either[String, Unit] = {
    if (terms > dataPoints) {
      Left(s"Terms ($terms) cannot exceed number of data points ($dataPoints)")
    } else {
      Right(())
    }
  }
  
  private def validateBounds(
    lower: Option[Double], 
    upper: Option[Double]
  ): Either[String, Unit] = {
    (lower, upper) match {
      case (Some(l), Some(u)) if l >= u => 
        Left(s"Lower bound ($l) must be < upper bound ($u)")
      case _ => 
        Right(())
    }
  }
}
