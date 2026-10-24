package com.risquanter.register.services.helper

import com.risquanter.register.BuildInfo
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution, Distribution, SeedDerivation}
import com.risquanter.register.domain.data.{
  TrialId, Loss, RiskLeaf, NodeProvenance, DistributionShape, DistributionParams
}
import com.risquanter.register.domain.errors.{AppError, ErrorLocation, ErrorSubject, FieldName, ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.iron.PositiveInt
import io.github.iltotore.iron.refineUnsafe
import com.risquanter.register.simulation.LognormalHelper
import zio.{IO, ZIO}
import java.time.Instant
import com.risquanter.register.domain.data.iron._

/** Trial-batch parallelism used when a caller does not supply one: one fiber
  * per available processor. */
private val DefaultTrialParallelism: PositiveInt =
  math.max(1, Runtime.getRuntime.availableProcessors()).refineUnsafe

/**
 * Monte Carlo simulation of a single risk leaf. Walking the tree and
 * aggregating portfolios belongs to the resolver, not here.
 *
 * Zero-loss trials are never materialized, and sampling is a pure function of
 * the HDR stream coordinates, so the same seeds give the same outcomes at any
 * parallelism.
 */
object Simulator {

  /**
   * Run `nTrials` trials and return a sparse map of the trials where the risk
   * occurred.
   *
   * The occurrence filter runs sequentially — one draw and one comparison per
   * trial. Loss sampling runs across `parallelism` fibers, an inverse-CDF
   * evaluation costing far more than an occurrence draw; below 100 successful
   * trials the fiber overhead outweighs the split and it runs sequentially.
   */
  def performTrials(
    sampler: RiskSampler,
    nTrials: PositiveInt,
    parallelism: PositiveInt = DefaultTrialParallelism
  ): IO[AppError, Map[TrialId, Loss]] = {
    // Sampling is pure arithmetic over an already-validated distribution, so an
    // exception here is a broken invariant rather than an outcome a caller can
    // act on. Each attempt therefore dies instead of entering the error
    // channel, which is reserved for failures that have a wire case.
    ZIO.attempt {
      // Filter phase: identify successful trials (pure, sequential is fine)
      val n: Int = nTrials
      (0 until n).filter(trial => sampler.sampleOccurrence(trial.toLong)).toVector
    }.orDie.flatMap { successfulTrials =>
      if (successfulTrials.isEmpty) {
        ZIO.succeed(Map.empty[TrialId, Loss])
      } else if (successfulTrials.size < 100 || parallelism <= 1) {
        // Small workload: sequential is more efficient (avoid fiber overhead)
        ZIO.attempt {
          successfulTrials.map(trial => (trial, sampler.sampleLoss(trial.toLong))).toMap
        }.orDie
      } else {
        // Large workload: parallel computation across batches
        val batchSize = math.max(1, successfulTrials.size / parallelism)
        val batches = successfulTrials.grouped(batchSize).toVector
        
        ZIO.foreachPar(batches) { batch =>
          ZIO.attempt {
            batch.map(trial => (trial, sampler.sampleLoss(trial.toLong)))
          }.orDie
        }.map(_.flatten.toMap)
         .withParallelism(parallelism)
      }
    }
  }

  /**
   * Build a sampler and its provenance record from a leaf definition.
   *
   * Both consume the same `HdrStreams` value, produced by the single
   * derivation site `SeedDerivation`, so a recorded provenance cannot diverge
   * from what was simulated.
   */
  private[services] def createSamplerFromLeaf(
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): IO[AppError, (RiskSampler, NodeProvenance)] = {
    for {
      // Create distribution based on mode
      distAndParams <- createDistributionWithParams(leaf)
      (distribution, distParams) = distAndParams

      // Single derivation site: even/odd streams from the leaf's assigned seedVarId
      streams = SeedDerivation.streams(seedEntityId, leaf.seedVarId, seed3, seed4)
      sampler = RiskSampler.fromDistribution(
        nodeId = leaf.id,
        streams = streams,
        occurrenceProb = leaf.probability, // Already OccurrenceProbability type from domain model
        lossDistribution = distribution
      )

      // Provenance records the very same stream tuple the sampler consumes.
      // Content-only: no node identity — attribution is by node, via the
      // LossDistribution that carries this record beside its own nodeId.
      provenance =
        NodeProvenance(
          entityId = streams.entityId,
          occurrenceVarId = streams.occurrenceVarId,
          lossVarId = streams.lossVarId,
          globalSeed3 = streams.seed3,
          globalSeed4 = streams.seed4,
          distributionParams = distParams,
          timestamp = Instant.now(),
          metalogDistributionVersion = BuildInfo.metalogDistributionVersion
        )

    } yield (sampler, provenance)
  }
  
  /** A refused fit, split by where each part goes. `clientMessage` is the
    * sentence the caller can act on, and `diagnostic` is the fitting library's
    * own text, which names a third-party class and carries whatever it threw,
    * so it is logged and never sent (ADR-035).
    */
  private final case class FitRefusal(
    code:          ValidationErrorCode,
    clientMessage: String,
    diagnostic:    String
  )

  /**
   * Build the leaf's loss distribution and the record of what the fit ran with.
   *
   * A numerical fit failure is the only refusal left. The shape carries its own
   * construction proof, so there is no absent field, no mode mismatch and no
   * out-of-range element to check — those states have no spelling in
   * `DistributionShape`.
   */
  private def createDistributionWithParams(leaf: RiskLeaf): IO[AppError, (Distribution, DistributionParams)] = {
    // The leaf is persisted, so a refusal names it by identifier and the message
    // carries only the reason.
    def at(field: FieldName): ErrorLocation =
      ErrorLocation.Field(ErrorSubject.ExistingNode(leaf.id), field)

    // The parameters the fit runs with: the authored shape with every value the
    // system supplies filled in. One derivation, so the record cannot state a
    // term count or a confidence level the fit did not use.
    val params = DistributionShape.resolve(leaf.shape)

    // Both fitters take the widened figures the record carries. The metalog
    // fitter re-checks the percentile interval on arrival, which is the
    // third-party boundary refining its own input (ADR-039 §1) rather than the
    // domain re-validating itself.
    // A fit refusal is about the parameters of one mode, and the remedy differs
    // by mode: an infeasible metalog system is answered by moving an anchor or
    // lowering the term count, and a lognormal by moving a bound. So each arm
    // carries its own code and its own sentence, and a caller is told which
    // mode's parameters to look at rather than only that fitting failed.
    val fitted: Either[FitRefusal, Distribution] = params match {
      case DistributionParams.Metalog(percentiles, quantiles, terms) =>
        MetalogDistribution
          .fromPercentilesUnsafe(
            // The fitter is a Java interface taking primitive arrays, so the
            // conversion from the domain's immutable sequences happens here and
            // nowhere above.
            percentiles = percentiles.toArray,
            quantiles = quantiles.toArray,
            terms = terms,
            lower = Some(0.0) // Loss cannot be negative
          )
          .left.map(error => FitRefusal(
            ValidationErrorCode.INVALID_METALOG_PARAMS,
            ValidationMessages.metalogFitFailed,
            s"Failed to fit Metalog: ${error.message}"
          ))

      case DistributionParams.Lognormal(minLoss, maxLoss, _) =>
        LognormalHelper
          .fromLognormal90CI(minLoss, maxLoss)
          .left.map(error => FitRefusal(
            ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
            ValidationMessages.lognormalFitFailed,
            s"Failed to create lognormal: ${error.message}"
          ))
    }

    ZIO
      .fromEither(fitted.map(distribution => (distribution, params)))
      // The diagnosis is written here because the failure cannot carry it: a
      // `ValidationFailed` derives its log text from the same messages it sends
      // to the client, so a reason put there would cross. At ordinary-traffic
      // level, because a refused fit is a caller's input being correctly
      // declined rather than an operational signal (ADR-002).
      .tapError(refusal =>
        ZIO.logInfo(s"Distribution fit refused for leaf ${leaf.id.value}: ${refusal.diagnostic}")
      )
      .mapError(refusal =>
        ValidationFailed(List(ValidationError(
          location = at(FieldName.DistributionType),
          code = refusal.code,
          message = refusal.clientMessage
        )))
      )
  }
}

