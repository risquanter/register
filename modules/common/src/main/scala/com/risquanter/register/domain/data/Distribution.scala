package com.risquanter.register.domain.data

import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.{DistributionType, Probability, NonNegativeLong, ValidationUtil}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
  * Validated loss distribution parameters used by RiskLeaf. Encapsulates Iron types
  * and cross-field rules (expert vs lognormal).
  */
final case class Distribution(
  distributionType: DistributionType,
  probability: Probability,
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)

object Distribution {
  def create(
    distributionType: String,
    probability: Double,
    minLoss: Option[Long],
    maxLoss: Option[Long],
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    fieldPrefix: String = "request"
  ): Validation[ValidationError, Distribution] = {
    val distTypeV = toValidation(ValidationUtil.refineDistributionType(distributionType, s"$fieldPrefix.distributionType"))
    val probV = toValidation(ValidationUtil.refineProbability(probability, s"$fieldPrefix.probability"))
    val minV = minLoss match {
      case Some(v) => toValidation(ValidationUtil.refineNonNegativeLong(v, s"$fieldPrefix.minLoss")).map(Some(_))
      case None    => Validation.succeed(None)
    }
    val maxV = maxLoss match {
      case Some(v) => toValidation(ValidationUtil.refineNonNegativeLong(v, s"$fieldPrefix.maxLoss")).map(Some(_))
      case None    => Validation.succeed(None)
    }

    val crossV: Validation[ValidationError, Unit] = distTypeV.map(_.toString).flatMap {
      case "expert" =>
        (percentiles, quantiles) match {
          case (Some(pct), Some(q)) if pct.nonEmpty && q.nonEmpty && pct.length == q.length => Validation.succeed(())
          case (Some(_), Some(q)) if q.isEmpty => Validation.fail(ValidationError(s"$fieldPrefix.quantiles", ValidationErrorCode.REQUIRED_FIELD, "Expert mode requires quantiles"))
          case (Some(pct), Some(q)) if pct.length != q.length =>
            Validation.fail(ValidationError(s"$fieldPrefix.distributionType", ValidationErrorCode.INVALID_COMBINATION, s"percentiles and quantiles length mismatch (${pct.length} vs ${q.length})"))
          case (None, _) => Validation.fail(ValidationError(s"$fieldPrefix.percentiles", ValidationErrorCode.REQUIRED_FIELD, "Expert mode requires percentiles"))
          case (_, None) => Validation.fail(ValidationError(s"$fieldPrefix.quantiles", ValidationErrorCode.REQUIRED_FIELD, "Expert mode requires quantiles"))
          case _ => Validation.fail(ValidationError(s"$fieldPrefix.distributionType", ValidationErrorCode.INVALID_COMBINATION, "Expert mode requires percentiles and quantiles"))
        }
      case "lognormal" =>
        (minV, maxV) match {
          case (Validation.Success(_, Some(minv)), Validation.Success(_, Some(maxv))) if minv < maxv => Validation.succeed(())
          case (Validation.Success(_, Some(_)), Validation.Success(_, Some(_))) =>
            Validation.fail(ValidationError(s"$fieldPrefix.minLoss", ValidationErrorCode.INVALID_LOGNORMAL_PARAMS, "minLoss must be < maxLoss"))
          case _ => Validation.fail(ValidationError(s"$fieldPrefix.distributionType", ValidationErrorCode.REQUIRED_FIELD, "Lognormal mode requires minLoss and maxLoss"))
        }
      case other => Validation.fail(ValidationError(s"$fieldPrefix.distributionType", ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE, s"Unsupported distribution type: $other"))
    }

    Validation.validateWith(distTypeV, probV, minV, maxV, crossV) { (dt, prob, min, max, _) =>
      Distribution(dt, prob, min, max, percentiles, quantiles)
    }
  }
}
