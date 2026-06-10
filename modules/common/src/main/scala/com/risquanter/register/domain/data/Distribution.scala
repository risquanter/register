package com.risquanter.register.domain.data

import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.{DistributionType, NonNegativeLong, PositiveInt, ValidationUtil}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/**
  * Validated loss distribution parameters used by RiskLeaf. Encapsulates Iron types
  * and cross-field rules (expert vs lognormal).
  */
final case class Distribution(
  distributionType: DistributionType,
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  terms: Option[PositiveInt]
)

object Distribution {
  /** Returns Failure when `arr` has two or more elements that are not strictly
    * increasing. Matches the pattern of `RiskNode.validateExpertMode` and
    * `TreeBuilderLogic.requireCond`: structural condition computed internally,
    * result expressed as `Validation[ValidationError, Unit]`.
    */
  private def requireStrictlyIncreasing(
    arr:     Array[Double],
    field:   String,
    message: String
  ): Validation[ValidationError, Unit] =
    if arr.length >= 2 && !ValidationUtil.isStrictlyIncreasing(arr.toSeq) then
      Validation.fail(ValidationError(field, ValidationErrorCode.INVALID_COMBINATION, message))
    else Validation.succeed(())

  def create(
    distributionType: String,
    minLoss: Option[Long],
    maxLoss: Option[Long],
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    fieldPrefix: String = "request",
    terms: Option[Int] = None
  ): Validation[ValidationError, Distribution] = {
    val distTypeV = toValidation(ValidationUtil.refineDistributionType(distributionType, s"$fieldPrefix.distributionType"))
    val minV = minLoss match {
      case Some(v) => toValidation(ValidationUtil.refineNonNegativeLong(v, s"$fieldPrefix.minLoss")).map(Some(_))
      case None    => Validation.succeed(None)
    }
    val maxV = maxLoss match {
      case Some(v) => toValidation(ValidationUtil.refineNonNegativeLong(v, s"$fieldPrefix.maxLoss")).map(Some(_))
      case None    => Validation.succeed(None)
    }
    val termsV: Validation[ValidationError, Option[PositiveInt]] = terms match {
      case Some(v) => toValidation(ValidationUtil.refinePositiveInt(v, s"$fieldPrefix.terms")).map(Some(_))
      case None    => Validation.succeed(None)
    }

    val crossV: Validation[ValidationError, Unit] = distTypeV.map(_.toString).flatMap {
      case "expert" =>
        (percentiles, quantiles) match {
          case (Some(pct), Some(q)) if pct.nonEmpty && q.nonEmpty && pct.length == q.length =>
            val pctErrors = pct.toList.zipWithIndex.collect {
              case (p, i) if p.isNaN || p.isInfinite || p <= 0.0 || p >= 1.0 =>
                ValidationError(s"$fieldPrefix.percentiles[$i]", ValidationErrorCode.INVALID_RANGE, "Percentile must be strictly in (0, 1)")
            }
            val qtErrors = q.toList.zipWithIndex.collect {
              case (qt, i) if qt.isNaN || qt.isInfinite || qt < 0.0 =>
                ValidationError(s"$fieldPrefix.quantiles[$i]", ValidationErrorCode.INVALID_RANGE, "Quantile loss amount must be non-negative")
            }
            val allElementErrors = pctErrors ++ qtErrors
            val elementV: Validation[ValidationError, Unit] = toValidation(
              if allElementErrors.isEmpty then Right(()) else Left(allElementErrors)
            )
            val monotonicPctCheck = requireStrictlyIncreasing(
              pct, s"$fieldPrefix.percentiles", ValidationMessages.percentilesMustBeStrictlyIncreasing)
            val monotonicQtCheck = requireStrictlyIncreasing(
              q, s"$fieldPrefix.quantiles", ValidationMessages.quantilesMustBeStrictlyIncreasing)
            val termsCheck: Validation[ValidationError, Unit] = termsV match {
              case Validation.Success(_, Some(t)) if t.toInt > pct.length =>
                Validation.fail(ValidationError(s"$fieldPrefix.terms", ValidationErrorCode.INVALID_COMBINATION, ValidationMessages.termsOutOfRange))
              case Validation.Success(_, Some(t)) if t.toInt < 2 =>
                Validation.fail(ValidationError(s"$fieldPrefix.terms", ValidationErrorCode.INVALID_COMBINATION, ValidationMessages.termsOutOfRange))
              case _ => Validation.succeed(())
            }
            Validation.validateWith(elementV, monotonicPctCheck, monotonicQtCheck, termsCheck)((_, _, _, _) => ())
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

    Validation.validateWith(distTypeV, minV, maxV, termsV, crossV) { (dt, min, max, t, _) =>
      Distribution(dt, min, max, percentiles, quantiles, t)
    }
  }
}
