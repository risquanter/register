package com.risquanter.register.domain.data

import zio.prelude.*
import zio.json.{JsonCodec, JsonEncoder, JsonDecoder, DeriveJsonCodec}
import com.risquanter.register.domain.data.iron.{
  DistributionType, NonNegativeDouble, NonNegativeLong, OccurrenceProbability,
  PositiveInt, ShrinkFraction, ValidationUtil
}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
 * Param-stage transform on a leaf's occurrence probability. `Keep` is the
 * identity component of the `RiskLeafTransform` product.
 */
sealed trait LikelihoodTransform

object LikelihoodTransform {
  case object Keep extends LikelihoodTransform

  /** Relative: probability × factor, clamped into the closed [0, 1] domain on application. */
  final case class Scale(factor: NonNegativeDouble) extends LikelihoodTransform

  /** Absolute: the expert-supplied post-mitigation probability. */
  final case class Override(probability: OccurrenceProbability) extends LikelihoodTransform

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
        ValidationUtil.refineNonNegativeDouble(f, "factor").map(Scale(_))
          .left.map(_.map(_.message).mkString("; "))
      case Raw("override", None, Some(p)) =>
        ValidationUtil.refineOccurrenceProbability(p, "probability").map(Override(_))
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
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
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
    minLoss: Option[NonNegativeLong],
    maxLoss: Option[NonNegativeLong],
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
  ): Validation[ValidationError, Option[NonNegativeLong]] = v match {
    case Some(l) => ValidationUtil.toValidation(ValidationUtil.refineNonNegativeLong(l, field)).map(Some(_))
    case None    => Validation.succeed(None)
  }
}

/**
 * Param-stage transform on a leaf's loss distribution. One semantic operation
 * interpreted per representation (uniform-op semantics):
 *
 * - `ScaleSeverity` — lognormal: scales both CI bounds; expert: scales the
 *   quantile values. Broadcasts across a heterogeneous target set.
 * - `Narrow` — contracts the spread toward the median. Lognormal: symmetric
 *   shrink in log space around the geometric mean of the bounds (requires a
 *   positive minLoss — log space is undefined at 0). Expert: quantile values
 *   pulled affinely toward the interpolated median (monotonicity preserved).
 * - `Override` — absolute replacement (representation-agnostic).
 */
sealed trait DistributionTransform

object DistributionTransform {
  case object Keep extends DistributionTransform
  final case class ScaleSeverity(factor: NonNegativeDouble) extends DistributionTransform
  final case class Narrow(fraction: ShrinkFraction) extends DistributionTransform
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
      case Narrow(fr)       => Raw("narrow", None, Some(fr), None)
      case Override(p)      => Raw("override", None, None, Some(p))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("keep", None, None, None) => Right(Keep)
      case Raw("scaleSeverity", Some(f), None, None) =>
        ValidationUtil.refineNonNegativeDouble(f, "factor").map(ScaleSeverity(_))
          .left.map(_.map(_.message).mkString("; "))
      case Raw("narrow", None, Some(fr), None) =>
        ValidationUtil.refineShrinkFraction(fr, "fraction").map(Narrow(_))
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
      case LikelihoodTransform.Scale(f)    => math.min(1.0, leaf.probability * f)
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
          Validation.succeed((
            "lognormal", None, None,
            leaf.minLoss.map(min => (min * f).toLong),
            leaf.maxLoss.map(max => (max * f).toLong),
            None
          ))
        case _ =>
          Validation.succeed((
            "expert",
            leaf.percentiles,
            leaf.quantiles.map(_.map(_ * f)),
            None, None,
            leaf.terms.map(_.toInt)
          ))
      }

    case DistributionTransform.Narrow(fraction) =>
      leaf.distributionType.toString match {
        case "lognormal" =>
          (leaf.minLoss, leaf.maxLoss) match {
            case (Some(min), Some(max)) if (min: Long) > 0L =>
              val keep = 1.0 - fraction
              val g = math.sqrt((min: Long).toDouble * (max: Long).toDouble) // geometric mean = log-space midpoint
              val newMin = (g * math.pow((min: Long).toDouble / g, keep)).toLong
              val newMax = (g * math.pow((max: Long).toDouble / g, keep)).toLong
              Validation.succeed(("lognormal", None, None, Some(newMin), Some(newMax), None))
            case _ =>
              Validation.fail(ValidationError(
                field = s"$fieldPrefix.minLoss",
                code = ValidationErrorCode.INVALID_COMBINATION,
                message = "Narrow on a lognormal leaf requires a positive minLoss (log-space contraction is undefined at 0)"
              ))
          }
        case _ =>
          val keep = 1.0 - fraction
          val narrowed = for {
            ps <- leaf.percentiles
            qs <- leaf.quantiles
          } yield {
            val m = interpolatedMedian(ps, qs)
            qs.map(q => m + (q - m) * keep)
          }
          Validation.succeed(("expert", leaf.percentiles, narrowed, None, None, leaf.terms.map(_.toInt)))
      }
  }

  private def currentFields(leaf: RiskLeaf): DistFields =
    (leaf.distributionType.toString, leaf.percentiles, leaf.quantiles,
     leaf.minLoss.map(l => l: Long), leaf.maxLoss.map(l => l: Long), leaf.terms.map(_.toInt))

  /** Linear interpolation of the quantile at p = 0.5 from (percentile, quantile)
    * pairs; clamps to the outermost quantile when 0.5 lies outside the stated
    * percentile range. Arrays are the leaf's validated expert-mode pair
    * (equal length, ascending percentiles). */
  private def interpolatedMedian(percentiles: Array[Double], quantiles: Array[Double]): Double = {
    val p = 0.5
    if (p <= percentiles.head) quantiles.head
    else if (p >= percentiles.last) quantiles.last
    else {
      val i = percentiles.indexWhere(_ >= p)
      val (p0, p1) = (percentiles(i - 1), percentiles(i))
      val (q0, q1) = (quantiles(i - 1), quantiles(i))
      if (p1 == p0) q0 else q0 + (q1 - q0) * (p - p0) / (p1 - p0)
    }
  }
}
