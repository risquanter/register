package com.risquanter.register.services

import zio.*

import com.risquanter.register.domain.data.{
  DistributionShape, ExpertDistributionParams, LognormalDistributionParams
}
import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{ErrorLocation, ValidationError, ValidationErrorCode, ValidationFailed}
import com.risquanter.register.http.requests.{DistributionPreviewResponse, DistributionPreviewPoint}
import com.risquanter.register.simulation.{LognormalHelper, MetalogDistribution}

/** Computes a sampled distribution preview curve from a gated distribution shape.
  *
  * The service is stateless and has no ZIO environment dependencies — it is a pure
  * function of its request input. The workspace key auth gate lives in the controller.
  */
trait DistributionPreviewService:
  def preview(shape: DistributionShape): IO[ValidationFailed, DistributionPreviewResponse]

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

  /** The preview runs the same resolution the simulator does, so the curve a
    * client sees is the curve a run would fit. */
  override def preview(shape: DistributionShape): IO[ValidationFailed, DistributionPreviewResponse] =
    DistributionShape.resolve(shape) match
      case ExpertDistributionParams(percentiles, quantiles, terms) =>
        previewExpert(percentiles, quantiles, terms)
      case LognormalDistributionParams(minLoss, maxLoss, _) =>
        previewLognormal(minLoss, maxLoss)

  private def previewExpert(
    percentiles: Vector[Double],
    quantiles: Vector[Double],
    resolvedTerms: Int
  ): IO[ValidationFailed, DistributionPreviewResponse] =
    for
      metalog <- ZIO.fromEither(
        MetalogDistribution.fromPercentilesUnsafe(
          // The fitter is a Java interface taking primitive arrays, so the
          // conversion happens at this call and nowhere above it.
          percentiles = percentiles.toArray,
          quantiles   = quantiles.toArray,
          terms       = resolvedTerms,
          lower       = Some(0.0),
          upper       = None
        )
      ).mapError(_ => ValidationFailed(List(ValidationError(
        location = ErrorLocation.Whole,
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

  private def previewLognormal(
    minLoss: Long,
    maxLoss: Long
  ): IO[ValidationFailed, DistributionPreviewResponse] =
    for
      lognormal <- ZIO.fromEither(LognormalHelper.fromLognormal90CI(minLoss, maxLoss))
        .mapError(ve => ValidationFailed(List(ValidationError(
          location = ErrorLocation.Whole,
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
