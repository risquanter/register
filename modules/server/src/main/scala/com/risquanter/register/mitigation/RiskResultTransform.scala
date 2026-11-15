package com.risquanter.register.mitigation

import zio.prelude.*
import com.risquanter.register.domain.data.iron.{NonNegativeLong, PositiveLong, RetentionFactor}
import com.risquanter.register.simulation.TrialOutcomes

/**
 * Pure transformation of TrialOutcomes for mitigation strategies.
 *
 * RiskResultTransform represents risk mitigation tactics that modify simulation outcomes:
 * - Insurance policies (deductibles, coverage limits)
 * - Risk controls (loss caps, diversification)
 * - Portfolio strategies (hedging, correlation adjustments)
 *
 * A transform operates on `TrialOutcomes` — the trial count plus the sparse
 * trial→loss map — so it applies to the algebraic content of any node's
 * result, leaf or portfolio, and never sees node identity or provenance.
 * Transforms must preserve `nTrials`: changing the trial count would break
 * the same-nTrials alignment invariant of `TrialOutcomes.combine`.
 *
 * Key properties:
 * - Pure functions: No side effects, deterministic transformations
 * - Composable: Multiple strategies combine via function composition
 * - Identity instance: Lawful associative combination with identity element
 *
 * Constructor parameters are Iron-refined (ADR-001): every rule lives in the
 * parameter types, so every constructor is total.
 *
 * The Identity instance enables:
 * ```scala
 * val deductible = RiskResultTransform.applyDeductible(10000L)
 * val cap = RiskResultTransform.capLosses(1000000L)
 *
 * // Compose: apply deductible THEN cap
 * val combined = Identity[RiskResultTransform].combine(deductible, cap)
 * val mitigated = combined.run(originalOutcomes)
 * ```
 *
 * @param run Pure function transforming TrialOutcomes
 */
case class RiskResultTransform(run: TrialOutcomes => TrialOutcomes) {

  /**
   * Apply this transformation to simulation outcomes.
   *
   * @param outcomes The trial outcomes to transform
   * @return Transformed outcomes with mitigation applied
   */
  def apply(outcomes: TrialOutcomes): TrialOutcomes = run(outcomes)

  /**
   * Compose with another transformation (this THEN that).
   *
   * @param that Transformation to apply after this one
   * @return Combined transformation
   */
  def andThen(that: RiskResultTransform): RiskResultTransform =
    RiskResultTransform(to => that.run(this.run(to)))

  /**
   * Compose with another transformation (that THEN this).
   *
   * @param that Transformation to apply before this one
   * @return Combined transformation
   */
  def compose(that: RiskResultTransform): RiskResultTransform =
    RiskResultTransform(to => this.run(that.run(to)))
}

object RiskResultTransform {

  /**
   * Identity transformation (no-op).
   * Returns input unchanged.
   */
  val identityTransform: RiskResultTransform = RiskResultTransform(to => to)

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
  given Identity[RiskResultTransform] with {
    def identity: RiskResultTransform = identityTransform

    def combine(l: => RiskResultTransform, r: => RiskResultTransform): RiskResultTransform =
      RiskResultTransform(to => r.run(l.run(to)))  // l THEN r
  }

  given Debug[RiskResultTransform] = Debug.make(_ => "RiskResultTransform(...)")

  // ══════════════════════════════════════════════════════════════════
  // Common Mitigation Strategies
  // ══════════════════════════════════════════════════════════════════

  /**
   * Apply a deductible to each trial: the entity bears the first `deductible`
   * of each loss and nothing beyond it. The same function as `capLosses` at the
   * same figure, read from the other end.
   *
   * @param deductible The part of each loss the entity bears (at least 1)
   * @return Transformation applying deductible
   *
   * @example
   * {{{
   * val transform = RiskResultTransform.applyDeductible(10000L)
   * // Loss of 50000 becomes 10000
   * // Loss of 5000 remains 5000
   * }}}
   */
  def applyDeductible(deductible: PositiveLong): RiskResultTransform = RiskResultTransform { to =>
    to.mapLosses(loss => Math.min(loss, deductible))
  }

  /**
   * Cap losses at a maximum value.
   * Limits each trial's loss to the specified cap.
   *
   * @param cap Maximum loss per trial (non-negative)
   * @return Transformation capping losses
   *
   * @example
   * {{{
   * val transform = RiskResultTransform.capLosses(1000000L)
   * // Loss of 5000000 becomes 1000000
   * // Loss of 500000 remains 500000
   * }}}
   */
  def capLosses(cap: PositiveLong): RiskResultTransform = RiskResultTransform { to =>
    to.mapLosses(loss => Math.min(loss, cap))
  }

  /**
   * Scale all losses by a factor.
   * Useful for currency conversion or proportional risk transfer.
   *
   * Scaled losses round to the nearest whole unit, so the error cancels across
   * trials instead of accumulating in one direction.
   *
   * A loss at the top of the `Long` range overflows even at a factor of exactly
   * 1, because `Long.MaxValue.toDouble` rounds up and the product then compares
   * at or above that bound. The overflow bound is conservative for the same
   * reason: doubles lose integer precision near 2^63, so it rejects a narrow
   * band that would have scaled correctly.
   *
   * @param factor Fraction of each loss the entity bears (above 0, at most 1)
   * @return Transformation scaling losses
   *
   * @example
   * {{{
   * val transform = RiskResultTransform.scaleLosses(0.8)  // 80% retention
   * // Loss of 100000 becomes 80000
   * }}}
   */
  def scaleLosses(factor: RetentionFactor): RiskResultTransform = RiskResultTransform { to =>
    val fraction = factor.toFraction
    to.mapLosses { loss =>
      val product = loss * fraction
      // Narrowing would saturate silently at Long.MaxValue; throw instead, and
      // let LossDistribution.decorate convert it (ADR-033 §3).
      if (product.isNaN || product >= Long.MaxValue.toDouble)
        throw new ArithmeticException(s"scaled loss overflow: $loss * $fraction")
      Math.round(product)
    }.filterLosses(_ > 0L)  // Remove zero losses
  }

  /**
   * Apply an insurance policy to each trial: the entity bears the first
   * `deductible`, the policy covers the next `limit`, and the loss above both
   * is the entity's again. A deductible of zero is first-unit coverage.
   *
   * @param deductible The first part of each loss the entity bears (non-negative)
   * @param limit The most the policy pays on one loss (at least 1)
   * @return Transformation applying the policy
   *
   * @example
   * {{{
   * val transform = RiskResultTransform.insurancePolicy(5L, 50L)
   * // Loss of 3 remains 3: inside the deductible
   * // Loss of 30 becomes 5: the deductible, the policy pays 25
   * // Loss of 100 becomes 50: the deductible plus the 45 above the limit
   * }}}
   */
  def insurancePolicy(deductible: NonNegativeLong, limit: PositiveLong): RiskResultTransform =
    RiskResultTransform { to =>
      to.mapLosses(loss => Math.min(loss, deductible) + Math.max(0L, loss - deductible - limit))
        .filterLosses(_ > 0L)  // Remove zero losses (sparse storage)
    }

  /**
   * Filter out trials below a threshold.
   * Removes small losses from sparse storage.
   *
   * @param threshold Minimum loss to keep (non-negative)
   * @return Transformation filtering losses
   */
  def filterBelowThreshold(threshold: NonNegativeLong): RiskResultTransform = RiskResultTransform { to =>
    to.filterLosses(_ >= threshold)
  }
}
