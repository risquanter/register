package com.risquanter.register.domain.data

import zio.prelude.*
import com.risquanter.register.domain.data.iron.{NonNegativeDouble, NonNegativeLong, ValidationMessages}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * Pure transformation of TrialOutcomes for mitigation strategies.
 *
 * RiskTransform represents risk mitigation tactics that modify simulation outcomes:
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
 * Constructor parameters are Iron-refined (ADR-001): single-field rules live
 * in the parameter types, so those constructors are total; only the
 * cross-field rule of `insurancePolicy` (cap > deductible) returns
 * `Validation`.
 *
 * The Identity instance enables:
 * ```scala
 * val deductible = RiskTransform.applyDeductible(10000L)
 * val cap = RiskTransform.capLosses(1000000L)
 *
 * // Compose: apply deductible THEN cap
 * val combined = Identity[RiskTransform].combine(deductible, cap)
 * val mitigated = combined.run(originalOutcomes)
 * ```
 *
 * @param run Pure function transforming TrialOutcomes
 */
case class RiskTransform(run: TrialOutcomes => TrialOutcomes) {

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
  def andThen(that: RiskTransform): RiskTransform =
    RiskTransform(to => that.run(this.run(to)))

  /**
   * Compose with another transformation (that THEN this).
   *
   * @param that Transformation to apply before this one
   * @return Combined transformation
   */
  def compose(that: RiskTransform): RiskTransform =
    RiskTransform(to => this.run(that.run(to)))
}

object RiskTransform {

  /**
   * Identity transformation (no-op).
   * Returns input unchanged.
   */
  val identityTransform: RiskTransform = RiskTransform(to => to)

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
      RiskTransform(to => r.run(l.run(to)))  // l THEN r
  }

  given Debug[RiskTransform] = Debug.make(_ => "RiskTransform(...)")

  // ══════════════════════════════════════════════════════════════════
  // Common Mitigation Strategies
  // ══════════════════════════════════════════════════════════════════

  /**
   * Apply a deductible to each trial.
   * Reduces losses by deductible amount (minimum 0).
   *
   * @param deductible Amount to subtract from each loss (non-negative)
   * @return Transformation applying deductible
   *
   * @example
   * {{{
   * val transform = RiskTransform.applyDeductible(10000L)
   * // Loss of 50000 becomes 40000
   * // Loss of 5000 becomes 0
   * }}}
   */
  def applyDeductible(deductible: NonNegativeLong): RiskTransform = RiskTransform { to =>
    val mitigated = to.outcomes.map { case (trial, loss) =>
      trial -> Math.max(0L, loss - deductible)
    }.filter(_._2 > 0)  // Remove zero losses (sparse storage)

    to.copy(outcomes = mitigated)
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
   * val transform = RiskTransform.capLosses(1000000L)
   * // Loss of 5000000 becomes 1000000
   * // Loss of 500000 remains 500000
   * }}}
   */
  def capLosses(cap: NonNegativeLong): RiskTransform = RiskTransform { to =>
    val capped = to.outcomes.map { case (trial, loss) =>
      trial -> Math.min(loss, cap)
    }

    to.copy(outcomes = capped)
  }

  /**
   * Scale all losses by a factor.
   * Useful for currency conversion or proportional risk transfer.
   *
   * @param factor Scaling multiplier (non-negative)
   * @return Transformation scaling losses
   *
   * @example
   * {{{
   * val transform = RiskTransform.scaleLosses(0.8)  // 80% retention
   * // Loss of 100000 becomes 80000
   * }}}
   */
  def scaleLosses(factor: NonNegativeDouble): RiskTransform = RiskTransform { to =>
    val scaled = to.outcomes.map { case (trial, loss) =>
      trial -> (loss * factor).toLong
    }.filter(_._2 > 0)  // Remove zero losses

    to.copy(outcomes = scaled)
  }

  /**
   * Apply both deductible and cap (insurance policy).
   * Equivalent to: applyDeductible(ded) andThen capLosses(cap)
   *
   * Cross-field rule: the cap must exceed the deductible, otherwise the
   * policy would zero out every loss it covers.
   *
   * @param deductible Amount subtracted first (non-negative)
   * @param cap Maximum after deductible applied (non-negative)
   * @return Validation with the combined transformation, or the cross-field error
   */
  def insurancePolicy(
    deductible: NonNegativeLong,
    cap: NonNegativeLong
  ): Validation[ValidationError, RiskTransform] =
    if (cap > deductible)
      Validation.succeed(
        Identity[RiskTransform].combine(
          applyDeductible(deductible),
          capLosses(cap)
        )
      )
    else
      Validation.fail(ValidationError(
        field = "insurancePolicy.cap",
        code = ValidationErrorCode.INVALID_COMBINATION,
        message = ValidationMessages.capMustExceedDeductible
      ))

  /**
   * Filter out trials below a threshold.
   * Removes small losses from sparse storage.
   *
   * @param threshold Minimum loss to keep (non-negative)
   * @return Transformation filtering losses
   */
  def filterBelowThreshold(threshold: NonNegativeLong): RiskTransform = RiskTransform { to =>
    val filtered = to.outcomes.filter { case (_, loss) => loss >= threshold }
    to.copy(outcomes = filtered)
  }
}
