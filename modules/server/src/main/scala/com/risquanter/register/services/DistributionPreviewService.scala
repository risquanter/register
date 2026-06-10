package com.risquanter.register.services

import zio.*

import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode, ValidationFailed}
import com.risquanter.register.http.requests.{DistributionPreviewResponse, DistributionPreviewPoint}
import com.risquanter.register.simulation.{LognormalHelper, MetalogDistribution}

/** Computes a sampled distribution preview curve from raw distribution parameters.
  *
  * The service is stateless and has no ZIO environment dependencies — it is a pure
  * function of its request input. The workspace key auth gate lives in the controller.
  */
trait DistributionPreviewService:
  def preview(dist: Distribution): IO[ValidationFailed, DistributionPreviewResponse]

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

  override def preview(dist: Distribution): IO[ValidationFailed, DistributionPreviewResponse] =
    // distributionType is DistributionType = String :| Match["^(expert|lognormal)$"],
    // so only these two values are reachable here.
    (dist.distributionType: String) match
      case "expert"    => previewExpert(dist)
      case "lognormal" => previewLognormal(dist)

  private def previewExpert(dist: Distribution): IO[ValidationFailed, DistributionPreviewResponse] =
    val percentiles   = dist.percentiles.getOrElse(Array.empty[Double])  // already 0-1
    val quantiles     = dist.quantiles.getOrElse(Array.empty[Double])
    val resolvedTerms = dist.terms.map(_.toInt).getOrElse(math.min(percentiles.length, 4))

    for
      metalog <- ZIO.fromEither(
        MetalogDistribution.fromPercentilesUnsafe(
          percentiles = percentiles,
          quantiles   = quantiles,
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
      anchorCount      = Some(percentiles.length),
      points           = points
    )

  private def previewLognormal(dist: Distribution): IO[ValidationFailed, DistributionPreviewResponse] =
    val minLoss = dist.minLoss.getOrElse(0L)
    val maxLoss = dist.maxLoss.getOrElse(0L)

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
