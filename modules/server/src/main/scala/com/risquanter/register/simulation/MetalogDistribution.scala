package com.risquanter.register.simulation

import zio.NonEmptyChunk

import com.risquanter.metalog.{Metalog, MetalogInfeasibleException, QPFitter}
import com.risquanter.register.domain.data.iron.{OpenProbability, PositiveInt}
import com.risquanter.register.domain.errors.Diagnostic
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

/**
 * Validation error for Lognormal distribution creation.
 */
case class ValidationError(errors: List[String]) {
  def message: String = errors.mkString("; ")
}

/** Why a metalog was not fitted: this wrapper's own refusal of the inputs, or
  * one case per exception the fitter throws, so a caller can tell each apart.
  * The text is the wrapper's or the fitter's own, for a log line.
  */
enum MetalogFitRefusal:
  /** This wrapper's own checks refused the inputs before any fit. */
  case InputsRefused(reasons: NonEmptyChunk[String])

  /** No strictly increasing metalog with the requested terms passes through
    * the points (the fitter's `MetalogInfeasibleException`). */
  case Infeasible(fitterText: String)

  /** The fitter called the inputs malformed (its `IllegalArgumentException`). */
  case InputsMalformed(fitterText: String)

  /** The solver stopped before an optimum (the fitter's
    * `IllegalStateException`). */
  case SolverStopped(fitterText: String)

/**
 * Scala wrapper around metalog-distribution's Metalog implementation.
 * 
 * The Metalog is a flexible quantile-parameterized distribution that can represent
 * a wide variety of shapes (bounded, semi-bounded, unbounded) using percentile-quantile
 * pairs fitted with a quantile function.
 * 
 * Used for metalog mode, where the author provides percentile-quantile estimates.
 * 
 * This wrapper adds:
 * - Iron refinement type validation for percentiles (must be in (0,1) - exclusive bounds)
 * - Input validation before calling Java QPFitter
 * - Functional error handling with Either
 * - Distribution trait implementation for uniform interface with Lognormal
 * 
 * @param fitter The underlying Java Metalog instance from metalog-distribution
 * @see com.risquanter.metalog.Metalog
 * @see com.risquanter.metalog.QPFitter
 */
case class MetalogDistribution private(fitter: Metalog) extends Distribution {
  
  /**
   * Compute the quantile (inverse CDF) for a given probability.
   * 
   * @param p Probability in [0, 1]
   * @return The value x such that P(X ≤ x) = p
   */
  def quantile(p: Double): Double = fitter.quantile(p)

  /**
   * Compute the probability density at a given probability level.
   *
   * @param p Probability in (0, 1) — same domain as [[quantile]]
   * @return Probability density dQ/dp evaluated at p
   */
  def pdf(p: Double): Double = fitter.pdf(p)
}

object MetalogDistribution {
  
  /**
   * Create a Metalog distribution from percentile-quantile pairs.
   * 
   * Uses Iron OpenProbability type to ensure percentiles are in (0,1) (exclusive bounds).
   * The underlying QPFitter library requires strict (0,1) bounds - 0.0 and 1.0 are not valid.
   * 
   * Validates inputs before calling metalog-distribution's QPFitter to provide
   * better error messages and defensive testing against library changes. Every
   * exception the fitter throws is returned as its own case rather than
   * thrown, so the caller decides which ones its inputs can reach.
   *
   * @param percentiles Probabilities in (0, 1) (exclusive), must be sorted ascending
   * @param quantiles Corresponding quantile values
   * @param terms Number of terms in the Metalog expansion
   * @param lower Optional lower bound for bounded distribution
   * @param upper Optional upper bound for bounded distribution
   * @return Either the refusal or the fitted Metalog distribution
   *
   * @example
   * {{{
   * val percentiles: Array[OpenProbability] = Array(0.05, 0.5, 0.95).map(_.refineUnsafe)
   * MetalogDistribution.fromPercentiles(percentiles, Array(1000.0, 5000.0, 25000.0), terms = 3, lower = None, upper = None)
   * // Right(MetalogDistribution(…))
   * MetalogDistribution.fromPercentiles(percentiles, Array(0.0, 50.0, 200.0), terms = 3, lower = Some(0.0), upper = None)
   * // Left(InputsMalformed("…")): a quantile on the lower bound
   * }}}
   */
  def fromPercentiles(
    percentiles: Array[OpenProbability],
    quantiles: Array[Double],
    terms: PositiveInt,
    lower: Option[Double],
    upper: Option[Double]
  ): Either[MetalogFitRefusal, MetalogDistribution] = {
    
    // Collect all validation errors
    val validations = List(
      validateSorted(percentiles),
      validateArrayLengths(percentiles, quantiles),
      validateTerms(terms, percentiles.length),
      validateBounds(lower, upper)
    ).collect { case Left(err) => err }
    
    NonEmptyChunk.fromIterableOption(validations) match {
      case Some(reasons) => Left(MetalogFitRefusal.InputsRefused(reasons))
      case None =>
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

          Right(MetalogDistribution(withBounds.fit()))
        } catch {
          // The three exceptions the fitter declares, each its own case, so a
          // caller can tell an author's infeasible anchors from this service's
          // fault. Anything else the fitter throws propagates (ADR-033).
          case e: MetalogInfeasibleException => Left(MetalogFitRefusal.Infeasible(Diagnostic.textOf(e)))
          case e: IllegalArgumentException   => Left(MetalogFitRefusal.InputsMalformed(Diagnostic.textOf(e)))
          case e: IllegalStateException      => Left(MetalogFitRefusal.SolverStopped(Diagnostic.textOf(e)))
        }
    }
  }
  
  /**
   * Convenience method for creating Metalog from raw doubles (auto-refines to OpenProbability).
   * Use this when percentiles are known to be valid at compile time.
   */
  def fromPercentilesUnsafe(
    percentiles: Array[Double],
    quantiles: Array[Double],
    terms: Int,
    lower: Option[Double],
    upper: Option[Double]
  ): Either[MetalogFitRefusal, MetalogDistribution] = {

    // Validate percentiles are in (0,1) before refining (exclusive bounds per QPFitter)
    if (percentiles.exists(p => p <= 0.0 || p >= 1.0)) {
      Left(MetalogFitRefusal.InputsRefused(NonEmptyChunk("All percentiles must be in (0.0, 1.0)")))
    } else if (terms <= 0) {
      Left(MetalogFitRefusal.InputsRefused(NonEmptyChunk("Terms must be positive")))
    } else {
      // Cast to refined array (unsafe but validated above)
      val refinedPercentiles: Array[OpenProbability] = percentiles.asInstanceOf[Array[OpenProbability]]
      val refinedTerms: PositiveInt = terms.refineUnsafe
      fromPercentiles(refinedPercentiles, quantiles, refinedTerms, lower, upper)
    }
  }
  
  // Validation helper methods
  
  private def validateSorted(ps: Array[OpenProbability]): Either[String, Unit] = {
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
    ps: Array[OpenProbability],
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
