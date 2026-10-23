package com.risquanter.register.domain.data

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator, jsonHint}
import zio.prelude.Validation
import sttp.tapir.Schema
import com.risquanter.register.domain.data.iron.{
  OccurrenceProbability, ResidualProbability, RetentionFactor, ValidationMessages
}
import com.risquanter.register.domain.data.iron.given
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode
}

/**
 * A likelihood change stated relative to whatever the leaf currently says.
 *
 * `Keep` is the identity. A relative statement stays true when the leaf
 * changes, so it records nothing about the leaf's former value.
 */
@jsonDiscriminator("op")
sealed trait RelativeLikelihood

object RelativeLikelihood {
  @jsonHint("keep")
  case object Keep extends RelativeLikelihood

  /** Probability × factor. The factor is at most 1 and the probability at most
    * 1, so the product stays inside the closed [0, 1] domain. */
  @jsonHint("scale")
  final case class Scale(factor: RetentionFactor) extends RelativeLikelihood

  given codec: JsonCodec[RelativeLikelihood] = DeriveJsonCodec.gen[RelativeLikelihood]

  // Declared rather than left to a holder's derivation: auto-derivation driven
  // from a record holding this trait does not descend into it, so every holder
  // would publish an unconstrained object instead of the cases.
  given schema: Schema[RelativeLikelihood] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[RelativeLikelihood]
}

/**
 * A loss-shape change stated relative to whatever the leaf currently says.
 *
 * `ScaleSeverity` means the loss an occurrence produces is smaller, because a
 * control changed what it costs. It applies before simulation, so the leaf is
 * re-simulated from the scaled parameters. Lognormal: scales both confidence
 * interval bounds, multiplying every quantile by the factor and leaving the
 * spread parameter unchanged. Expert: scales the anchor losses, with the same
 * effect on the fitted distribution. It broadcasts across a heterogeneous
 * target set, and applies alongside `ResultTransformSpec.ScaleLosses` on the
 * same node — that one scales what the entity bears of a loss this one has
 * already reduced.
 */
@jsonDiscriminator("op")
sealed trait RelativeSeverity

object RelativeSeverity {
  @jsonHint("keep")
  case object Keep extends RelativeSeverity

  @jsonHint("scaleSeverity")
  final case class ScaleSeverity(factor: RetentionFactor) extends RelativeSeverity

  given codec: JsonCodec[RelativeSeverity] = DeriveJsonCodec.gen[RelativeSeverity]

  // Declared for the same reason as `RelativeLikelihood`'s.
  given schema: Schema[RelativeSeverity] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[RelativeSeverity]
}

/**
 * What an absolute replacement asserts: a post-control probability, a
 * post-control loss shape, or both.
 *
 * Three cases, which is 2² − 1 — the non-empty subsets of a two-element set.
 * "At least one component is replaced" is therefore not a rule but the absence
 * of a fourth case: a replacement that replaces nothing is
 * `RelativeLikelihood.Keep` paired with `RelativeSeverity.Keep` instead.
 *
 * A replacement is representation-agnostic by construction, because it names a
 * whole shape rather than adjusting the one already there.
 */
@jsonDiscriminator("replaces")
sealed trait AbsoluteReplacement

object AbsoluteReplacement {
  /** Absolute post-control probability. Strictly above zero — an author may
    * declare a leaf that never occurs, but a mitigation may not assert that a
    * risk has been prevented. */
  @jsonHint("likelihood")
  final case class Likelihood(probability: ResidualProbability) extends AbsoluteReplacement

  @jsonHint("severity")
  final case class Severity(shape: DistributionShape) extends AbsoluteReplacement

  @jsonHint("both")
  final case class Both(
    probability: ResidualProbability,
    shape: DistributionShape
  ) extends AbsoluteReplacement

  given codec: JsonCodec[AbsoluteReplacement] = DeriveJsonCodec.gen[AbsoluteReplacement]

  // Declared for the same reason as `RelativeLikelihood`'s. The two cases that
  // carry a whole shape reach `DistributionShape`'s own schema through it.
  given schema: Schema[AbsoluteReplacement] =
    import sttp.tapir.generic.auto.*
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[AbsoluteReplacement]
}

/**
 * What a leaf-stage transform may change: the occurrence probability and the
 * loss distribution. Identity, name, parent and seed identity are outside a
 * transform's reach, so they are outside its type.
 *
 * Making this the arrow's object is what removes the repeated widening a
 * whole-leaf arrow performs: with n leaf-stage mitigations scoping one leaf, the
 * identifier, the name and the seed identifier were each widened to a primitive
 * and re-refined n times although no transform can change any of them.
 */
final case class LeafParams(
  probability: OccurrenceProbability,
  shape: DistributionShape
)

/**
 * Severity scaling: its interpretation onto each mode of a shape, and the
 * representability arithmetic each mode needs.
 *
 * The arithmetic is kept as named functions rather than inlined because each
 * mode's floor guard produces a diagnosis the field type cannot: `PositiveLong`
 * and `PositiveDouble` refuse the same inputs, and only these checks can name
 * the smallest factor that still applies to this leaf.
 */
object RiskLeafTransform {

  /** Severity-scaled lognormal CI bounds: lower floored, upper ceiled. Outward
    * rounding only widens the fitted interval (σ never shrinks) and preserves the
    * strict `min < max` ordering whenever the continuous scaled bounds differ. */
  def scaleSeverityBounds(min: Long, max: Long, factor: Double): (Long, Long) =
    (Math.floor(min * factor).toLong, Math.ceil(max * factor).toLong)

  /** A scaled lower bound is representable when it stays at the positive floor
    * (≥ 1 whole unit). Below that it floors to 0 and cannot be fit. */
  def lowerBoundRepresentable(scaledMin: Long): Boolean = scaledMin >= 1L

  /** Smallest severity factor that keeps `min` representable (`min * f ≥ 1`). */
  def minRepresentableFactor(min: Long): Double = 1.0 / min.toDouble

  /** A scaled anchor loss is representable while it stays above zero. An anchor
    * loss is a `PositiveDouble`, so its floor is the smallest positive double
    * rather than a whole unit, and only underflow reaches it. */
  def anchorLossRepresentable(scaledLoss: Double): Boolean = scaledLoss > 0.0

  /** Smallest severity factor that keeps `min` above the positive-double floor
    * (`min * f > 0`). */
  def minAnchorFactor(min: Double): Double = Double.MinPositiveValue / min

  /** Severity scaling interpreted onto a shape, one reading per mode.
    *
    * Lognormal: both confidence-interval bounds scale, which multiplies every
    * quantile by the factor and leaves the spread parameter unchanged. Expert:
    * the anchor losses scale and the percentiles do not, which has the same
    * effect on the fitted curve.
    *
    * Each mode has one refusal, and it is the same condition read against that
    * mode's floor: the factor is small enough to take a severity figure below
    * what its type can hold. Both are reported here rather than left to the
    * field type, because the actionable number is the smallest factor that
    * still applies to this leaf and only these checks can compute it. The code
    * names the mode whose parameters the caller must look at.
    */
  def scaleSeverity(
    shape:   DistributionShape,
    factor:  RetentionFactor,
    subject: ErrorSubject
  ): Validation[ValidationError, DistributionShape] = shape match {
    case DistributionShape.Lognormal(bounds) =>
      scaleSeverityBounds(bounds.min, bounds.max, factor) match {
        case (scaledMin, scaledMax) if lowerBoundRepresentable(scaledMin) =>
          DistributionShape.Lognormal.fromPrimitives(scaledMin, scaledMax, subject)

        case _ =>
          Validation.fail(ValidationError(
            location = ErrorLocation.Field(subject, FieldName.MinLoss),
            code = ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
            message = ValidationMessages.mitigationLowersMinLossBelowFloor(
              bounds.min, minRepresentableFactor(bounds.min))
          ))
      }

    case DistributionShape.Expert(anchors, terms) =>
      anchors.losses.map(_ * factor) match {
        case scaled if scaled.forall(anchorLossRepresentable) =>
          DistributionShape.Expert.fromPrimitives(
            percentiles = anchors.percentiles,
            quantiles = scaled,
            terms = terms.map(t => t: Int),
            subject = subject
          )

        case _ =>
          val smallest = anchors.losses.min
          Validation.fail(ValidationError(
            location = ErrorLocation.Field(subject, FieldName.Quantiles),
            code = ValidationErrorCode.INVALID_EXPERT_PARAMS,
            message = ValidationMessages.mitigationLowersAnchorLossBelowFloor(
              smallest, minAnchorFactor(smallest))
          ))
      }
  }
}
