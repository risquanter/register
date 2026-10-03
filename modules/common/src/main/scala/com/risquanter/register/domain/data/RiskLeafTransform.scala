package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import com.risquanter.register.domain.data.iron.{
  DistributionType, PositiveInt, PositiveLong, ResidualProbability,
  RetentionFactor, ValidationMessages, ValidationUtil
}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * Param-stage transform on a leaf's occurrence probability. `Keep` is the
 * identity component of the `RiskLeafTransform` product.
 */
sealed trait LikelihoodTransform

object LikelihoodTransform {
  case object Keep extends LikelihoodTransform

  /** Relative: probability × factor. The factor is at most 1 and the probability
    * at most 1, so the product stays inside the closed [0, 1] domain. */
  final case class Scale(factor: RetentionFactor) extends LikelihoodTransform

  /** Absolute: the expert-supplied post-mitigation probability. Strictly above
    * zero — an author may declare a leaf that never occurs, but a mitigation may
    * not assert that a risk has been prevented. */
  final case class Override(probability: ResidualProbability) extends LikelihoodTransform

  given Equal[LikelihoodTransform] = Equal.default

  private case class Raw(op: String, factor: Option[Double], probability: Option[Double])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[LikelihoodTransform] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case Keep          => Raw("keep", None, None)
      case Scale(f)      => Raw("scale", Some(f), None)
      case Override(p)   => Raw("override", None, Some(p))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("keep", None, None) => Right(Keep)
      case Raw("scale", Some(f), None) =>
        ValidationUtil.refineRetentionFactor(f, "factor").map(Scale(_))
          .left.map(_.map(_.message).mkString("; "))
      case Raw("override", None, Some(p)) =>
        ValidationUtil.refineResidualProbability(p, "probability").map(Override(_))
          .left.map(_.map(_.message).mkString("; "))
      case other => Left(s"invalid likelihood transform: op '${other.op}' with mismatched fields")
    }
  )
}

/**
 * Absolute replacement of a leaf's loss distribution — the expert-supplied
 * post-mitigation shape. Representation-agnostic by construction (it replaces,
 * so no cross-representation mapping is needed). Same mode invariant as
 * `RiskLeaf` (expert ⇒ percentiles+quantiles; lognormal ⇒ minLoss < maxLoss),
 * enforced through the shared `RiskLeaf.validateModeFields` helper.
 */
final case class OverrideDistributionParams private (
  distributionType: DistributionType,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[PositiveLong],
  maxLoss: Option[PositiveLong],
  terms: Option[PositiveInt]
) {
  // Array fields compare by reference under case-class equality; content
  // comparison is required for Equal.default / Set membership downstream.
  override def equals(that: Any): Boolean = that match {
    case o: OverrideDistributionParams =>
      distributionType == o.distributionType &&
        optArrayEq(percentiles, o.percentiles) &&
        optArrayEq(quantiles, o.quantiles) &&
        minLoss == o.minLoss && maxLoss == o.maxLoss && terms == o.terms
    case _ => false
  }
  override def hashCode: Int =
    (distributionType.toString, percentiles.map(_.toSeq), quantiles.map(_.toSeq), minLoss, maxLoss, terms).hashCode

  private def optArrayEq(a: Option[Array[Double]], b: Option[Array[Double]]): Boolean = (a, b) match {
    case (Some(x), Some(y)) => x.sameElements(y)
    case (None, None)       => true
    case _                  => false
  }
}

object OverrideDistributionParams {

  def create(
    distributionType: DistributionType,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[PositiveLong],
    maxLoss: Option[PositiveLong],
    terms: Option[PositiveInt],
    fieldPrefix: String = "overrideParams"
  ): Validation[ValidationError, OverrideDistributionParams] =
    RiskLeaf
      .validateModeFields(distributionType, percentiles, quantiles, minLoss, maxLoss, terms, fieldPrefix)
      .as(new OverrideDistributionParams(distributionType, percentiles, quantiles, minLoss, maxLoss, terms))

  given Equal[OverrideDistributionParams] = Equal.default

  private case class Raw(
    distributionType: String,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[Long],
    maxLoss: Option[Long],
    terms: Option[Int]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[OverrideDistributionParams] = JsonCodec(
    JsonEncoder[Raw].contramap(p =>
      Raw(p.distributionType.toString, p.percentiles, p.quantiles,
          p.minLoss.map(identity), p.maxLoss.map(identity), p.terms.map(_.toInt))),
    JsonDecoder[Raw].mapOrFail { raw =>
      val distTypeV = ValidationUtil.toValidation(
        ValidationUtil.refineDistributionType(raw.distributionType, "distributionType"))
      val minV = optRefineLong(raw.minLoss, "minLoss")
      val maxV = optRefineLong(raw.maxLoss, "maxLoss")
      val termsV = raw.terms match {
        case Some(t) => ValidationUtil.toValidation(ValidationUtil.refinePositiveInt(t, "terms")).map(Some(_))
        case None    => Validation.succeed(None)
      }
      Validation
        .validateWith(distTypeV, minV, maxV, termsV) { (dt, min, max, terms) => (dt, min, max, terms) }
        .flatMap { case (dt, min, max, terms) =>
          create(dt, raw.percentiles, raw.quantiles, min, max, terms)
        }
        .toEither.left.map(_.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; "))
    }
  )

  private def optRefineLong(
    v: Option[Long],
    field: String
  ): Validation[ValidationError, Option[PositiveLong]] = v match {
    case Some(l) => ValidationUtil.toValidation(ValidationUtil.refinePositiveLong(l, field)).map(Some(_))
    case None    => Validation.succeed(None)
  }
}

/**
 * Param-stage transform on a leaf's loss distribution. One semantic operation
 * interpreted per representation (uniform-op semantics):
 *
 * - `ScaleSeverity` — the loss an occurrence produces is smaller, because a
 *   control changed what it costs. Applies before simulation, so the leaf is
 *   re-simulated from the scaled parameters. Lognormal: scales both CI bounds,
 *   multiplying every quantile by the factor and leaving the spread parameter
 *   unchanged. Expert: scales the quantile values, with the same effect on the
 *   fitted distribution. Broadcasts across a heterogeneous target set, and applies
 *   alongside `ResultTransformSpec.ScaleLosses` on the same node — that one scales
 *   what the entity bears of a loss this one has already reduced.
 * - `Override` — absolute replacement (representation-agnostic).
 */
sealed trait DistributionTransform

object DistributionTransform {
  case object Keep extends DistributionTransform
  final case class ScaleSeverity(factor: RetentionFactor) extends DistributionTransform
  final case class Override(params: OverrideDistributionParams) extends DistributionTransform

  given Equal[DistributionTransform] = Equal.default

  private case class Raw(
    op: String,
    factor: Option[Double],
    fraction: Option[Double],
    params: Option[OverrideDistributionParams]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[DistributionTransform] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case Keep             => Raw("keep", None, None, None)
      case ScaleSeverity(f) => Raw("scaleSeverity", Some(f), None, None)
      case Override(p)      => Raw("override", None, None, Some(p))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("keep", None, None, None) => Right(Keep)
      case Raw("scaleSeverity", Some(f), None, None) =>
        ValidationUtil.refineRetentionFactor(f, "factor").map(ScaleSeverity(_))
          .left.map(_.map(_.message).mkString("; "))
      case Raw("override", None, None, Some(p)) => Right(Override(p))
      case other => Left(s"invalid distribution transform: op '${other.op}' with mismatched fields")
    }
  )
}

/**
 * Product of the two independent param-stage components — disjoint fields, so
 * they commute; either component may be `Keep` (identity). Application
 * interprets both onto a leaf; the output is a normal `RiskLeaf` revalidated
 * through `RiskLeaf.create` (closure: the tree persists params, so a
 * param-transformed leaf is an ordinary leaf).
 */
final case class RiskLeafTransform(
  likelihood: LikelihoodTransform,
  distribution: DistributionTransform
)

object RiskLeafTransform {

  val identity: RiskLeafTransform =
    RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Keep)

  given Equal[RiskLeafTransform] = Equal.default

  given codec: JsonCodec[RiskLeafTransform] = DeriveJsonCodec.gen[RiskLeafTransform]

  /** Interpret onto a leaf. Identity/parent/name/seed identity are untouched;
    * only the simulation-relevant params change, and the result is revalidated
    * through the smart constructor (errors accumulate). */
  def applyTo(t: RiskLeafTransform, leaf: RiskLeaf): Validation[ValidationError, RiskLeaf] = {
    val fieldPrefix = s"mitigation.leafTransform[${leaf.id.value}]"

    val newProbability: Double = t.likelihood match {
      case LikelihoodTransform.Keep        => leaf.probability
      case LikelihoodTransform.Scale(f)    => leaf.probability * f
      case LikelihoodTransform.Override(p) => p
    }

    distributionFields(t.distribution, leaf, fieldPrefix).flatMap {
      case (distType, percentiles, quantiles, minLoss, maxLoss, terms) =>
        RiskLeaf.create(
          id = leaf.id.value,
          name = leaf.name.value,
          distributionType = distType,
          probability = newProbability,
          percentiles = percentiles,
          quantiles = quantiles,
          minLoss = minLoss,
          maxLoss = maxLoss,
          parentId = leaf.parentId,
          fieldPrefix = fieldPrefix,
          terms = terms,
          seedVarId = leaf.seedVarId.value
        )
    }
  }

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

  private type DistFields =
    (String, Option[Array[Double]], Option[Array[Double]], Option[Long], Option[Long], Option[Int])

  private def distributionFields(
    dt: DistributionTransform,
    leaf: RiskLeaf,
    fieldPrefix: String
  ): Validation[ValidationError, DistFields] = dt match {

    case DistributionTransform.Keep =>
      Validation.succeed(currentFields(leaf))

    case DistributionTransform.Override(p) =>
      Validation.succeed((
        p.distributionType.toString, p.percentiles, p.quantiles,
        p.minLoss.map(l => l: Long), p.maxLoss.map(l => l: Long), p.terms.map(_.toInt)
      ))

    case DistributionTransform.ScaleSeverity(f) =>
      leaf.distributionType.toString match {
        case "lognormal" =>
          (leaf.minLoss, leaf.maxLoss) match {
            case (Some(min), Some(max)) =>
              val (newMin, newMax) = scaleSeverityBounds(min, max, f)
              if (!lowerBoundRepresentable(newMin))
                Validation.fail(ValidationError(
                  field   = s"$fieldPrefix.minLoss",
                  code    = ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
                  message = ValidationMessages.mitigationLowersMinLossBelowFloor(
                              min, minRepresentableFactor(min))
                ))
              else
                Validation.succeed(("lognormal", None, None, Some(newMin), Some(newMax), None))
            // Unreachable for a lognormal leaf: RiskLeaf's class invariant
            // guarantees both bounds are present. Passes them through so
            // RiskLeaf.create reports the missing-bound case with its own message.
            case _ =>
              Validation.succeed(("lognormal", None, None,
                leaf.minLoss.map(m => m: Long), leaf.maxLoss.map(m => m: Long), None))
          }
        case _ =>
          Validation.succeed((
            "expert",
            leaf.percentiles,
            leaf.quantiles.map(_.map(_ * f)),
            None, None,
            leaf.terms.map(_.toInt)
          ))
      }

  }

  private def currentFields(leaf: RiskLeaf): DistFields =
    (leaf.distributionType.toString, leaf.percentiles, leaf.quantiles,
     leaf.minLoss.map(l => l: Long), leaf.maxLoss.map(l => l: Long), leaf.terms.map(_.toInt))

}
