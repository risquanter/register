package com.risquanter.register.services

import zio.*
import io.github.iltotore.iron.{autoRefine, refineUnsafe}

import com.risquanter.register.domain.data.{DistributionShape, DistributionParams}
import com.risquanter.register.domain.data.iron.{MetalogTermCount, PositiveLong, Probability, ProbabilityConstraint}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldProblem, ValidationError, ValidationFailed}
import com.risquanter.register.http.responses.{DistributionPreviewPoint, DistributionPreviewResponse, PreviewFit}
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

  /** 200 steps give visually smooth PDF and CDF curves while keeping the payload
    * small (~9 KB JSON). */
  private val GridSize = 200

  /** The grid's two bounds, chosen here. They keep the grid off 0 and 1, where
    * many distribution families' quantile functions are singular. */
  private val GridLower: Probability = 0.001
  private val GridUpper: Probability = 0.999

  /** Uniform probability grid over the open interval (`GridLower`, `GridUpper`).
    *
    * The `refineUnsafe` is sound here: every value lies strictly between the two
    * bounds, which are themselves inside `Probability`'s open interval, so it
    * cannot be reached with an invalid value. The one other production site with
    * the same justification is `QuantileAnchors.termsToFit`.
    */
  private val pGrid: Vector[Probability] =
    val step = (GridUpper - GridLower) / GridSize
    (1 to GridSize).map(i => (GridLower + (i - 0.5) * step).refineUnsafe[ProbabilityConstraint]).toVector

  /** The preview runs the same resolution the simulator does, so the curve a
    * client sees is the curve a run would fit. */
  override def preview(shape: DistributionShape): IO[ValidationFailed, DistributionPreviewResponse] =
    DistributionShape.resolve(shape) match
      case DistributionParams.Metalog(percentiles, quantiles, terms) =>
        previewMetalog(percentiles, quantiles, terms)
      case DistributionParams.Lognormal(minLoss, maxLoss, _) =>
        previewLognormal(minLoss, maxLoss)

  private def previewMetalog(
    percentiles: Vector[Double],
    quantiles: Vector[Double],
    resolvedTerms: MetalogTermCount
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
      ).mapError(_ => ValidationFailed(List(ValidationError(ErrorLocation.Whole, FieldProblem.MetalogFitFailed))))
    yield DistributionPreviewResponse(PreviewFit.Metalog(resolvedTerms), sampleMetalog(metalog))

  private def previewLognormal(
    minLoss: PositiveLong,
    maxLoss: PositiveLong
  ): IO[ValidationFailed, DistributionPreviewResponse] =
    for
      lognormal <- ZIO.fromEither(LognormalHelper.fromLognormal90CI(minLoss, maxLoss))
        .mapError(_ => ValidationFailed(List(ValidationError(ErrorLocation.Whole, FieldProblem.LognormalFitFailed))))
      points = pGrid.map { p =>
        val x = lognormal.quantile(p)
        DistributionPreviewPoint(x = x, pdf = lognormal.density(x), cdf = p)
      }
    yield DistributionPreviewResponse(PreviewFit.Lognormal(), points)

  private def sampleMetalog(metalog: MetalogDistribution): Vector[DistributionPreviewPoint] =
    pGrid.map { p =>
      val x = metalog.quantile(p)
      DistributionPreviewPoint(x = x, pdf = metalog.pdf(p), cdf = p)
    }
