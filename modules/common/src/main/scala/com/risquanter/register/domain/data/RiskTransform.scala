package com.risquanter.register.domain.data

import zio.prelude.*

/**
 * Pure transformation of RiskResult for mitigation strategies.
 * 
 * RiskTransform represents risk mitigation tactics that modify simulation outcomes:
 * - Insurance policies (deductibles, coverage limits)
 * - Risk controls (loss caps, diversification)
 * - Portfolio strategies (hedging, correlation adjustments)
 * 
 * Key properties:
 * - Pure functions: No side effects, deterministic transformations
 * - Composable: Multiple strategies combine via function composition
 * - Identity instance: Lawful associative combination with identity element
 * 
 * The Identity instance enables:
 * ```scala
 * val deductible = RiskTransform.applyDeductible(10000L)
 * val cap = RiskTransform.capLosses(1000000L)
 * 
 * // Compose: apply deductible THEN cap
 * val combined = Identity[RiskTransform].combine(deductible, cap)
 * val mitigated = combined.run(originalResult)
 * ```
 * 
 * @param run Pure function transforming RiskResult
 */
case class RiskTransform(run: RiskResult => RiskResult) {
  
  /**
   * Apply this transformation to a RiskResult.
   * 
   * @param result The simulation result to transform
   * @return Transformed result with mitigation applied
   */
  def apply(result: RiskResult): RiskResult = run(result)
  
  /**
   * Compose with another transformation (this THEN that).
   * 
   * @param that Transformation to apply after this one
   * @return Combined transformation
   */
  def andThen(that: RiskTransform): RiskTransform = 
    RiskTransform(r => that.run(this.run(r)))
  
  /**
   * Compose with another transformation (that THEN this).
   * 
   * @param that Transformation to apply before this one
   * @return Combined transformation
   */
  def compose(that: RiskTransform): RiskTransform = 
    RiskTransform(r => this.run(that.run(r)))
}

object RiskTransform {
  
  /**
   * Identity transformation (no-op).
   * Returns input unchanged.
   */
  val identityTransform: RiskTransform = RiskTransform(r => r)
  
  // ══════════════════════════════════════════════════════════════════
  // ZIO Prelude Type Class Instances
  // ══════════════════════════════════════════════════════════════════
  
  /**
   * Identity instance for function composition.
   * 
   * Laws verified:
   * - Associativity: combine(a, combine(b, c)) == combine(combine(a, b), c)
   * - Left identity: combine(identity, a) == a
   * - Right identity: combine(a, identity) == a
   * 
   * Combine semantics: compose(a, b) means "apply a, then apply b"
   */
  given Identity[RiskTransform] with {
    def identity: RiskTransform = identityTransform
    
    def combine(l: => RiskTransform, r: => RiskTransform): RiskTransform =
      RiskTransform(result => r.run(l.run(result)))  // l THEN r
  }
  
  given Equal[RiskTransform] = Equal.default
  
  given Debug[RiskTransform] = Debug.make(_ => "RiskTransform(...)")
  
  // ══════════════════════════════════════════════════════════════════
  // Common Mitigation Strategies
  // ══════════════════════════════════════════════════════════════════
  
  /**
   * Apply a deductible to each trial.
   * Reduces losses by deductible amount (minimum 0).
   * 
   * @param deductible Amount to subtract from each loss
   * @return Transformation applying deductible
   * 
   * @example
   * {{{
   * val transform = RiskTransform.applyDeductible(10000L)
   * // Loss of 50000 becomes 40000
   * // Loss of 5000 becomes 0
   * }}}
   */
  def applyDeductible(deductible: Loss): RiskTransform = RiskTransform { result =>
    val mitigated = result.outcomes.map { case (trial, loss) =>
      trial -> Math.max(0L, loss - deductible)
    }.filter(_._2 > 0)  // Remove zero losses (sparse storage)
    
    result.copy(outcomes = mitigated)
  }
  
  /**
   * Cap losses at a maximum value.
   * Limits each trial's loss to the specified cap.
   * 
   * @param cap Maximum loss per trial
   * @return Transformation capping losses
   * 
   * @example
   * {{{
   * val transform = RiskTransform.capLosses(1000000L)
   * // Loss of 5000000 becomes 1000000
   * // Loss of 500000 remains 500000
   * }}}
   */
  def capLosses(cap: Loss): RiskTransform = RiskTransform { result =>
    val capped = result.outcomes.map { case (trial, loss) =>
      trial -> Math.min(loss, cap)
    }
    
    result.copy(outcomes = capped)
  }
  
  /**
   * Scale all losses by a factor.
   * Useful for currency conversion or proportional risk transfer.
   * 
   * @param factor Scaling multiplier
   * @return Transformation scaling losses
   * 
   * @example
   * {{{
   * val transform = RiskTransform.scaleLosses(0.8)  // 80% retention
   * // Loss of 100000 becomes 80000
   * }}}
   */
  def scaleLosses(factor: Double): RiskTransform = RiskTransform { result =>
    require(factor >= 0.0, "Scale factor must be non-negative")
    
    val scaled = result.outcomes.map { case (trial, loss) =>
      trial -> (loss * factor).toLong
    }.filter(_._2 > 0)  // Remove zero losses
    
    result.copy(outcomes = scaled)
  }
  
  /**
   * Apply both deductible and cap (insurance policy).
   * Equivalent to: applyDeductible(ded) andThen capLosses(cap)
   * 
   * @param deductible Amount subtracted first
   * @param cap Maximum after deductible applied
   * @return Combined transformation
   */
  def insurancePolicy(deductible: Loss, cap: Loss): RiskTransform = {
    require(deductible >= 0, "Deductible must be non-negative")
    require(cap > deductible, "Cap must be greater than deductible")
    
    Identity[RiskTransform].combine(
      applyDeductible(deductible),
      capLosses(cap)
    )
  }
  
  /**
   * Filter out trials below a threshold.
   * Removes small losses from sparse storage.
   * 
   * @param threshold Minimum loss to keep
   * @return Transformation filtering losses
   */
  def filterBelowThreshold(threshold: Loss): RiskTransform = RiskTransform { result =>
    val filtered = result.outcomes.filter { case (_, loss) => loss >= threshold }
    result.copy(outcomes = filtered)
  }
}
