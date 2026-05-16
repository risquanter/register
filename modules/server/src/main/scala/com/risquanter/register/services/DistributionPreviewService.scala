package com.risquanter.register.services

import zio.*

import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode, ValidationFailed}
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse, DistributionPreviewPoint}
import com.risquanter.register.simulation.{LognormalHelper, MetalogDistribution}

/** Computes a sampled distribution preview curve from raw distribution parameters.
  *
  * The service is stateless and has no ZIO environment dependencies — it is a pure
  * function of its request input. The workspace key auth gate lives in the controller.
  */
trait DistributionPreviewService:
  def preview(req: DistributionPreviewRequest): IO[ValidationFailed, DistributionPreviewResponse]

object DistributionPreviewService:
  val layer: ZLayer[Any, Nothing, DistributionPreviewService] =
    ZLayer.succeed(DistributionPreviewServiceLive())

private final class DistributionPreviewServiceLive() extends DistributionPreviewService:

  private val GridSize = 200

  /** Uniform probability grid over the open interval (0.001, 0.999).
    *
    * 200 steps give visually smooth PDF and CDF curves while keeping the payload
    * small (~9 KB JSON). The open-interval bounds avoid quantile singularities at 0
    * and 1 that arise with many distribution families.
    */
  private val pGrid: IndexedSeq[Double] =
    val lo  = 0.001
    val hi  = 0.999
    val step = (hi - lo) / GridSize
    (1 to GridSize).map(i => lo + (i - 0.5) * step)

  override def preview(req: DistributionPreviewRequest): IO[ValidationFailed, DistributionPreviewResponse] =
    req.distributionType match
      case "expert"    => previewExpert(req)
      case "lognormal" => previewLognormal(req)
      case other =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field   = "request.distributionType",
          code    = ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE,
          message = s"Unsupported distribution type: $other"
        ))))

  private def previewExpert(req: DistributionPreviewRequest): IO[ValidationFailed, DistributionPreviewResponse] =
    val rawPercentiles = req.percentiles.getOrElse(Array.empty[Double])
    val rawQuantiles   = req.quantiles.getOrElse(Array.empty[Double])

    // Normalise 0–100 percentiles to (0, 1); fromPercentilesUnsafe validates the range
    val normalisedPercentiles = rawPercentiles.map(_ / 100.0)
    val resolvedTerms         = req.terms.getOrElse(math.min(rawPercentiles.length, 4))

    for
      metalog <- ZIO.fromEither(
        MetalogDistribution.fromPercentilesUnsafe(
          percentiles = normalisedPercentiles,
          quantiles   = rawQuantiles,
          terms       = resolvedTerms,
          lower       = Some(0.0),
          upper       = None
        )
      ).mapError(_ => ValidationFailed(List(ValidationError(
        field   = "request",
        code    = ValidationErrorCode.DISTRIBUTION_FIT_FAILED,
        message = ValidationMessages.distributionFitFailed
      ))))
      points = sampleMetalog(metalog)
    yield DistributionPreviewResponse(
      distributionType = "expert",
      resolvedTerms    = Some(resolvedTerms),
      anchorCount      = Some(rawPercentiles.length),
      points           = points
    )

  private def previewLognormal(req: DistributionPreviewRequest): IO[ValidationFailed, DistributionPreviewResponse] =
    val minLoss = req.minLoss.getOrElse(0L)
    val maxLoss = req.maxLoss.getOrElse(0L)

    for
      lognormal <- ZIO.fromEither(LognormalHelper.fromLognormal90CI(minLoss, maxLoss))
        .mapError(ve => ValidationFailed(List(ValidationError(
          field   = "request",
          code    = ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
          message = ve.message
        ))))
      points = pGrid.map { p =>
        val x = lognormal.quantile(p)
        DistributionPreviewPoint(x = x, pdf = lognormal.density(x), cdf = p)
      }.toArray
    yield DistributionPreviewResponse(
      distributionType = "lognormal",
      resolvedTerms    = None,
      anchorCount      = None,
      points           = points
    )

  private def sampleMetalog(metalog: MetalogDistribution): Array[DistributionPreviewPoint] =
    pGrid.map { p =>
      val x = metalog.quantile(p)
      DistributionPreviewPoint(x = x, pdf = metalog.pdf(p), cdf = p)
    }.toArray
