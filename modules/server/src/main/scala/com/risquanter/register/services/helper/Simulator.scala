package com.risquanter.register.services.helper

import com.risquanter.register.BuildInfo
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution, Distribution, SeedDerivation}
import com.risquanter.register.domain.data.{TrialId, Loss, RiskLeaf, NodeProvenance, ExpertDistributionParams, LognormalDistributionParams}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.iron.PositiveInt
import io.github.iltotore.iron.refineUnsafe
import io.github.iltotore.iron.constraint.numeric.{Greater, given}
import com.risquanter.register.simulation.LognormalHelper
import com.risquanter.register.domain.data.iron.ValidationUtil
import zio.{ZIO, Task}
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
  ): Task[Map[TrialId, Loss]] = {
    ZIO.attempt {
      // Filter phase: identify successful trials (pure, sequential is fine)
      val n: Int = nTrials
      (0 until n).filter(trial => sampler.sampleOccurrence(trial.toLong)).toVector
    }.flatMap { successfulTrials =>
      if (successfulTrials.isEmpty) {
        ZIO.succeed(Map.empty[TrialId, Loss])
      } else if (successfulTrials.size < 100 || parallelism <= 1) {
        // Small workload: sequential is more efficient (avoid fiber overhead)
        ZIO.attempt {
          successfulTrials.map(trial => (trial, sampler.sampleLoss(trial.toLong))).toMap
        }
      } else {
        // Large workload: parallel computation across batches
        val batchSize = math.max(1, successfulTrials.size / parallelism)
        val batches = successfulTrials.grouped(batchSize).toVector
        
        ZIO.foreachPar(batches) { batch =>
          ZIO.attempt {
            batch.map(trial => (trial, sampler.sampleLoss(trial.toLong)))
          }
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
  ): Task[(RiskSampler, NodeProvenance)] = {
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
          distributionType = leaf.distributionType,
          distributionParams = distParams,
          timestamp = Instant.now(),
          metalogDistributionVersion = BuildInfo.metalogDistributionVersion
        )

    } yield (sampler, provenance)
  }
  
  /**
   * Create MetalogDistribution from RiskLeaf parameters.
   * Returns both distribution and parameters for provenance.
   */
  private def createDistributionWithParams(leaf: RiskLeaf): Task[(Distribution, com.risquanter.register.domain.data.DistributionParams)] = {
    import io.github.iltotore.iron.*
    import com.risquanter.register.domain.data.iron.{Probability, PositiveInt}
    
    leaf.distributionType.toLowerCase match {
      // Expert opinion mode: fit from percentiles + quantiles
      case "expert" =>
        (leaf.percentiles, leaf.quantiles) match {
          case (Some(ps), Some(qs)) if ps.length == qs.length && ps.length >= 2 =>
            // Refine percentile values to Probability type (exclusive bounds (0,1) per QPFitter)
            val percentileResults = ps.map(p => ValidationUtil.refineProbability(p, "percentiles"))
            val percentileErrors = percentileResults.collect { case Left(errors) => errors }.flatten
            
            if (percentileErrors.nonEmpty) {
              ZIO.fail(ValidationFailed(percentileErrors.toList))
            } else {
              val percentiles = percentileResults.collect { case Right(p) => p }
              val terms: PositiveInt = leaf.terms.getOrElse(math.min(ps.length, 4).refineUnsafe)
              
              MetalogDistribution.fromPercentiles(
                percentiles = percentiles,
                quantiles = qs,
                terms = terms,
                lower = Some(0.0) // Loss cannot be negative
              ) match {
                case Right(metalog) =>
                  val params = ExpertDistributionParams(
                    percentiles = ps,
                    quantiles = qs,
                    terms = terms
                  )
                  ZIO.succeed((metalog, params))
                case Left(validationError) => ZIO.fail(ValidationFailed(List(ValidationError(
                  field = s"riskLeaf.${leaf.id}.metalogFit",
                  code = ValidationErrorCode.DISTRIBUTION_FIT_FAILED,
                  message = s"Failed to fit Metalog for '${leaf.id}': ${validationError.message}"
                ))))
              }
            }
          
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskLeaf.${leaf.id}.expertParams",
              code = ValidationErrorCode.INVALID_EXPERT_PARAMS,
              message = s"Expert mode requires percentiles and quantiles arrays with same length (≥2) for '${leaf.id}'"
            ))))
        }
      
      // Lognormal mode: use BCG 90% CI approach
      case "lognormal" =>
        (leaf.minLoss, leaf.maxLoss) match {
          case (Some(min), Some(max)) if min > 0 && min < max =>
            LognormalHelper.fromLognormal90CI(min, max) match {
              case Right(dist) =>
                val params = LognormalDistributionParams(
                  minLoss = min,
                  maxLoss = max,
                  confidenceInterval = 0.90
                )
                ZIO.succeed((dist, params))
              case Left(err) => ZIO.fail(ValidationFailed(List(ValidationError(
                field = s"riskLeaf.${leaf.id}.lognormalFit",
                code = ValidationErrorCode.DISTRIBUTION_FIT_FAILED,
                message = s"Failed to create lognormal for '${leaf.id}': ${err.message}"
              ))))
            }
          
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskLeaf.${leaf.id}.lognormalParams",
              code = ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
              message = s"Lognormal mode requires minLoss > 0 and minLoss < maxLoss for '${leaf.id}'"
            ))))
        }
      
      case other =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field = s"riskLeaf.${leaf.id}.distributionType",
          code = ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE,
          message = s"Unsupported distribution type: $other for '${leaf.id}'"
        ))))
    }
  }
}

