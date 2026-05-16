package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import sttp.tapir.Schema

import com.risquanter.register.domain.data.iron.{ValidationUtil, ValidationMessages}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/** Request body for the distribution preview endpoint.
  *
  * Carries the raw (unrefined) distribution parameters needed to fit and sample a
  * distribution curve. Both modes share this type; the `distributionType` field
  * acts as the discriminator.
  *
  * Expert mode fields: `percentiles`, `quantiles`, `terms`.
  * Lognormal mode fields: `minLoss`, `maxLoss`.
  *
  * Percentile values are expected in 0–100 form (matching the frontend form);
  * the service normalises them to (0, 1) before passing to [[MetalogDistribution]].
  */
final case class DistributionPreviewRequest(
  distributionType: String,
  percentiles:      Option[Array[Double]],
  quantiles:        Option[Array[Double]],
  terms:            Option[Int],
  minLoss:          Option[Long],
  maxLoss:          Option[Long]
)

object DistributionPreviewRequest:
  given schema: Schema[DistributionPreviewRequest] = Schema.any[DistributionPreviewRequest]
  given codec: JsonCodec[DistributionPreviewRequest] = DeriveJsonCodec.gen[DistributionPreviewRequest]

  /** Validate cross-field rules and return a cleaned request or accumulated errors.
    *
    * Rules mirror [[Distribution.create]] but omit the `probability` field, which
    * is irrelevant to distribution-shape preview.
    *
    * - `distributionType` must be `"expert"` or `"lognormal"`
    * - Expert mode: `percentiles` required, `quantiles` required,
    *   lengths must match, `terms` (if present) must be ≤ anchor count
    * - Lognormal mode: `minLoss` required, `maxLoss` required
    */
  def validate(req: DistributionPreviewRequest): Validation[ValidationError, DistributionPreviewRequest] =
    val distTypeV: Validation[ValidationError, String] = req.distributionType match
      case "expert" | "lognormal" => Validation.succeed(req.distributionType)
      case other                  =>
        Validation.fail(ValidationError(
          "request.distributionType",
          ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE,
          s"Unsupported distribution type: $other"
        ))
    val termsV: Validation[ValidationError, Option[Int]] = req.terms match
      case Some(t) =>
        toValidation(ValidationUtil.refinePositiveInt(t, "request.terms")).map(pi => Some(pi.toInt))
      case None =>
        Validation.succeed(None)

    val crossV: Validation[ValidationError, Unit] = distTypeV.map(_.toString).flatMap {
      case "expert" =>
        (req.percentiles, req.quantiles) match
          case (Some(pct), Some(q)) if pct.nonEmpty && q.nonEmpty && pct.length == q.length =>
            val isMonotone = q.length < 2 || q.sliding(2).forall { case Array(a, b) => a < b; case _ => true }
            if !isMonotone then
              Validation.fail(ValidationError(
                "request.quantiles",
                ValidationErrorCode.INVALID_COMBINATION,
                ValidationMessages.quantilesMustBeStrictlyIncreasing
              ))
            else req.terms match
              case Some(t) if t > pct.length =>
                Validation.fail(ValidationError(
                  "request.terms",
                  ValidationErrorCode.INVALID_COMBINATION,
                  ValidationMessages.termsOutOfRange
                ))
              case _ =>
                Validation.succeed(())
          case (Some(pct), Some(q)) if pct.length != q.length =>
            Validation.fail(ValidationError(
              "request.distributionType",
              ValidationErrorCode.INVALID_COMBINATION,
              ValidationMessages.expertLengthMismatch(pct.length, q.length)
            ))
          case (None, _) =>
            Validation.fail(ValidationError(
              "request.percentiles",
              ValidationErrorCode.REQUIRED_FIELD,
              ValidationMessages.percentilesRequired
            ))
          case (_, None) =>
            Validation.fail(ValidationError(
              "request.quantiles",
              ValidationErrorCode.REQUIRED_FIELD,
              ValidationMessages.quantilesRequired
            ))
          case _ =>
            Validation.fail(ValidationError(
              "request.distributionType",
              ValidationErrorCode.INVALID_COMBINATION,
              "Expert mode requires non-empty percentiles and quantiles"
            ))
      case "lognormal" =>
        (req.minLoss, req.maxLoss) match
          case (Some(min), Some(max)) if min < max =>
            Validation.succeed(())
          case (Some(_), Some(_)) =>
            Validation.fail(ValidationError(
              "request.minLoss",
              ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
              ValidationMessages.minMustBeLessThanMax
            ))
          case (None, _) =>
            Validation.fail(ValidationError(
              "request.minLoss",
              ValidationErrorCode.REQUIRED_FIELD,
              ValidationMessages.minLossRequired
            ))
          case (_, None) =>
            Validation.fail(ValidationError(
              "request.maxLoss",
              ValidationErrorCode.REQUIRED_FIELD,
              ValidationMessages.maxLossRequired
            ))
      case other =>
        Validation.fail(ValidationError(
          "request.distributionType",
          ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE,
          s"Unsupported distribution type: $other"
        ))
    }

    Validation.validateWith(distTypeV, termsV, crossV) { (_, _, _) => req }
