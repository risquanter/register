package com.risquanter.register.services.helper

import com.risquanter.register.BuildInfo
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution, MetalogFitRefusal, Distribution, SeedDerivation}
import com.risquanter.register.domain.data.{
  TrialId, Loss, RiskLeaf, NodeProvenance, DistributionShape, DistributionParams
}
import com.risquanter.register.domain.errors.{AppError, FieldProblem}
import com.risquanter.register.domain.data.iron.PositiveInt
import io.github.iltotore.iron.refineUnsafe
import com.risquanter.register.simulation.LognormalHelper
import zio.{IO, UIO, ZIO}
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
    }.orDie.flatMap {
      case Vector() =>
        ZIO.succeed(Map.empty[TrialId, Loss])
      case successfulTrials if successfulTrials.size < 100 || parallelism <= 1 =>
        // Small workload: sequential is more efficient (avoid fiber overhead)
        ZIO.attempt {
          successfulTrials.map(trial => (trial, sampler.sampleLoss(trial.toLong))).toMap
        }.orDie
      case successfulTrials =>
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
  ): UIO[(RiskSampler, NodeProvenance)] = {
    for {
      // Create distribution based on mode
      distAndParams <- createDistributionWithParams(leaf)
      (distribution, distParams) = distAndParams

      // Single derivation site: even/odd streams from the leaf's assigned seedVarId
      streams = SeedDerivation.streams(seedEntityId, leaf.seedVarId, seed3, seed4)
      sampler = RiskSampler.fromDistribution(
        nodeId = leaf.id,
        streams = streams,
        occurrenceProb = leaf.probability, // Already Probability type from domain model
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
  
  /** A refused fit, split by where each part goes. `problem` is what the caller
    * is told, and `diagnostic` is the fitting library's own text, which names a
    * third-party class and carries whatever it threw, so it is logged and never
    * sent (ADR-035).
    */
  private[services] final case class FitRefusal(
    problem:    FieldProblem,
    diagnostic: String
  )

  /**
   * The shape's loss distribution and the parameters the fit ran with, or the
   * refusal of those parameters.
   *
   * A numerical fit failure is the only refusal. The shape carries its own
   * construction proof, so there is no absent field, no mode mismatch and no
   * out-of-range element to check — those states have no spelling in
   * `DistributionShape`. A tree write runs this on every leaf it adds or
   * changes, and simulation runs it again on a stored leaf.
   */
  private[services] def fit(shape: DistributionShape): Either[FitRefusal, (Distribution, DistributionParams)] = {
    // The parameters the fit runs with: the authored shape with every value the
    // system supplies filled in. One derivation, so the record cannot state a
    // term count or a confidence level the fit did not use.
    val params = DistributionShape.resolve(shape)

    // Both fitters take the widened figures the record carries. The metalog
    // fitter re-checks the percentile interval on arrival, which is the
    // third-party boundary refining its own input (ADR-039 §1) rather than the
    // domain re-validating itself.
    // A fit refusal is about the parameters of one mode, and the remedy differs
    // by mode: an infeasible metalog system is answered by moving an anchor or
    // lowering the term count, and a lognormal by moving a bound. So each arm
    // carries its own problem, and a caller is told which mode's parameters to
    // look at rather than only that fitting failed.
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
            lower = Some(0.0), // Loss cannot be negative
            upper = None
          )
          .left.map(metalogRefusal)

      case DistributionParams.Lognormal(minLoss, maxLoss, _) =>
        LognormalHelper
          .fromLognormal90CI(minLoss, maxLoss)
          .left.map(error => FitRefusal(
            FieldProblem.LognormalFitFailed,
            s"Failed to create lognormal: ${error.message}"
          ))
    }

    fitted.map(distribution => (distribution, params))
  }

  /** The caller's refusal for anchors no increasing metalog fits. Every other
    * case is a defect: a shape's anchors are sorted, positive, in (0, 1) and no
    * fewer than its terms, and the fit's only bound is 0, so the wrapper's checks
    * and the fitter's malformed-input refusal cannot be reached, and a solver
    * that stops early is the fitter's fault.
    */
  private def metalogRefusal(refusal: MetalogFitRefusal): FitRefusal = refusal match
    case MetalogFitRefusal.Infeasible(text) =>
      FitRefusal(FieldProblem.MetalogFitFailed, s"Failed to fit Metalog: $text")
    case unreachable @ (MetalogFitRefusal.InputsRefused(_) | MetalogFitRefusal.InputsMalformed(_) |
                        MetalogFitRefusal.SolverStopped(_)) =>
      throw new IllegalStateException(s"A metalog fit of a valid shape was refused: $unreachable")

  /** The stored leaf's distribution and its fit parameters. A tree write refuses
    * a leaf no curve fits, so a stored leaf that does not fit is a defect. */
  private def createDistributionWithParams(leaf: RiskLeaf): UIO[(Distribution, DistributionParams)] =
    ZIO.attempt {
      fit(leaf.shape) match
        case Right(fitted) => fitted
        case Left(refusal) =>
          throw new IllegalArgumentException(
            s"requirement failed: stored leaf ${leaf.id.value} has a shape no curve fits: ${refusal.diagnostic}")
    }.orDie
}

