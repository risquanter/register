package com.risquanter.register.services.helper

import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution, Distribution}
import com.risquanter.register.domain.data.{RiskResult, TrialId, Loss, RiskNode, RiskLeaf, RiskPortfolio, RiskTreeResult, NodeProvenance, TreeProvenance, ExpertDistributionParams, LognormalDistributionParams}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import zio.prelude.Identity
import com.risquanter.register.simulation.LognormalHelper
import com.risquanter.register.domain.data.iron.ValidationUtil
import scala.collection.parallel.CollectionConverters.*
import zio.{ZIO, Task}
import java.time.Instant

/**
 * Monte Carlo simulation engine with sparse storage optimization.
 * 
 * Design principles:
 * - Sparse storage: Only stores trials where risk occurred (loss > 0)
 * - Parallelization: Uses parallel collections for CPU-bound trial computation
 * - Determinism: Pure functions guarantee identical results for same seeds
 * - Memory efficiency: Avoids materializing zero-loss trials
 * - Recursive tree simulation: Bottom-up aggregation with lazy evaluation
 */
object Simulator {
  
  /** 
   * Run trials for a single risk using sparse storage.
   * Only stores trials where risk occurred (non-zero loss).
   * 
   * Parallelization strategy:
   * 1. Filter: Lazy view identifies successful trials (no allocation)
   * 2. Materialize: toVector creates collection of trial IDs only
   * 3. Parallel map: Compute losses for successful trials in parallel
   * 4. Convert back: seq.toMap for final result
   * 
   * Thread safety: sampler functions are pure (HDR-based determinism)
   * 
   * @param sampler RiskSampler with occurrence + loss distribution
   * @param nTrials Total number of trials to simulate
   * @return Sparse map of trial ID → loss (only non-zero outcomes)
   */
  def performTrials(
    sampler: RiskSampler,
    nTrials: Int
  ): Map[TrialId, Loss] = {
    // Lazy view: filter successful trials without intermediate allocation
    val successfulTrials = (0 until nTrials).view
      .filter(trial => sampler.sampleOccurrence(trial.toLong))
      .toVector
    
    // Parallel computation: map trial IDs to loss amounts
    // Thread-safe: sampleLoss is pure function (no shared mutable state)
    successfulTrials.par.map { trial =>
      (trial, sampler.sampleLoss(trial.toLong))
    }.seq.toMap
  }
  
  /** 
   * Simulate multiple risks in parallel using ZIO fibers.
   * Each risk is computed independently without shared state.
   * 
   * Parallelization correctness:
   * - Fiber isolation: Each sampler operates on independent data
   * - No race conditions: No shared mutable state between fibers
   * - Determinism: Same seeds produce identical results regardless of parallelism
   * 
   * @param samplers Vector of risk samplers to simulate
   * @param nTrials Number of trials per risk
   * @param parallelism Maximum concurrent fibers (default: 8)
   * @return Task of RiskResult for each risk
   */
  def simulate(
    samplers: Vector[RiskSampler],
    nTrials: Int,
    parallelism: Int = 8
  ): Task[Vector[RiskResult]] = {
    val trialSets = samplers.map { sampler =>
      ZIO.attempt {
        val trials = performTrials(sampler, nTrials)
        RiskResult(sampler.id, trials, nTrials)
      }
    }
    
    ZIO.collectAllPar(trialSets).withParallelism(parallelism)
  }
  
  /**
   * Sequential simulation for small workloads or debugging.
   * Guaranteed deterministic execution order.
   * 
   * @param samplers Vector of risk samplers to simulate
   * @param nTrials Number of trials per risk
   * @return Task of RiskResult for each risk
   */
  def simulateSequential(
    samplers: Vector[RiskSampler],
    nTrials: Int
  ): Task[Vector[RiskResult]] = {
    ZIO.foreach(samplers) { sampler =>
      ZIO.attempt {
        val trials = performTrials(sampler, nTrials)
        RiskResult(sampler.id, trials, nTrials)
      }
    }
  }
  
  // ══════════════════════════════════════════════════════════════════
  // Recursive Tree Simulation
  // ══════════════════════════════════════════════════════════════════
  
  /**
   * Recursively simulate risk tree with bottom-up aggregation.
   * 
   * Algorithm:
   * 1. Leaf: Create sampler, run trials → RiskTreeResult.Leaf
   * 2. RiskPortfolio: Recurse on children, aggregate results → RiskTreeResult.Branch
   * 
   * Aggregation uses Identity[RiskResult].combine (outer join semantics).
   * All nodes in tree share same trial sequence for consistency.
   * 
   * @param node Root node of tree (or subtree)
   * @param nTrials Number of Monte Carlo trials
   * @param parallelism Max concurrent child simulations
   * @param includeProvenance Whether to capture provenance metadata
   * @return Tuple of (RiskTreeResult, Option[TreeProvenance])
   */
  def simulateTree(
    node: RiskNode,
    nTrials: Int,
    parallelism: Int = 8,
    includeProvenance: Boolean = false
  ): Task[(RiskTreeResult, Option[TreeProvenance])] = {
    simulateTreeInternal(node, nTrials, parallelism, includeProvenance, Map.empty).map {
      case (result, provenances) =>
        val treeProvenance = if (includeProvenance) {
          Some(TreeProvenance(
            treeId = 0L, // Will be set by service layer with actual tree ID
            globalSeeds = (0L, 0L), // Currently hardcoded, future: user-configurable
            nTrials = nTrials,
            parallelism = parallelism,
            nodeProvenances = provenances
          ))
        } else None
        (result, treeProvenance)
    }
  }
  
  /**
   * Internal recursive tree simulation that accumulates provenances.
   */
  private def simulateTreeInternal(
    node: RiskNode,
    nTrials: Int,
    parallelism: Int,
    includeProvenance: Boolean,
    provenances: Map[String, NodeProvenance]
  ): Task[(RiskTreeResult, Map[String, NodeProvenance])] = {
    node match {
      case leaf: RiskLeaf =>
        // Terminal node: create sampler and simulate
        for {
          samplerAndProv <- createSamplerFromLeaf(leaf, includeProvenance)
          (sampler, maybeProv) = samplerAndProv
          result <- ZIO.attempt {
            val trials = performTrials(sampler, nTrials)
            RiskTreeResult.Leaf(leaf.id, RiskResult(leaf.id, trials, nTrials))
          }
          updatedProvenances = maybeProv match {
            case Some(prov) => provenances + (leaf.id -> prov)
            case None => provenances
          }
        } yield (result, updatedProvenances)
      
      case portfolio: RiskPortfolio =>
        // Branch node: recurse on children, then aggregate
        for {
          // Validate portfolio has children
          _ <- ZIO.when(portfolio.children.isEmpty)(
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskPortfolio.${portfolio.id}.children",
              code = ValidationErrorCode.EmptyCollection,
              message = s"RiskPortfolio '${portfolio.id}' has no children"
            ))))
          )
          
          // Recursively simulate all children in parallel
          childResultsWithProv <- ZIO.collectAllPar(
            portfolio.children.map(child => simulateTreeInternal(child, nTrials, parallelism, includeProvenance, provenances))
          ).withParallelism(parallelism)
          
          // Separate results and provenances
          childResults = childResultsWithProv.map(_._1)
          mergedProvenances = childResultsWithProv.foldLeft(provenances)((acc, tuple) => acc ++ tuple._2)
          
          // Aggregate children using Identity.combine
          aggregated <- ZIO.attempt {
            val childRiskResults = childResults.map(_.result)
            val combined = childRiskResults.reduce((a, b) => Identity[RiskResult].combine(a, b))
            
            // Update name to portfolio ID
            RiskTreeResult.Branch(
              id = portfolio.id,
              result = combined.copy(name = portfolio.id),
              children = childResults.toVector
            )
          }
        } yield (aggregated, mergedProvenances)
    }
  }
  
  /**
   * Create RiskSampler from RiskLeaf definition.
   * Validates parameters and builds Metalog distribution.
   * Optionally captures provenance metadata.
   * 
   * @return Tuple of (RiskSampler, Option[NodeProvenance])
   */
  private def createSamplerFromLeaf(
    leaf: RiskLeaf,
    includeProvenance: Boolean = false
  ): Task[(RiskSampler, Option[NodeProvenance])] = {
    for {
      // Validate probability
      probability <- ZIO.fromEither(
        ValidationUtil.refineProbability(leaf.probability)
      ).mapError(errors => ValidationFailed(errors))
      
      // Create distribution based on mode
      distAndParams <- createDistributionWithParams(leaf)
      (distribution, distParams) = distAndParams
      
      // Build sampler (using entityId = hash of leaf.id for determinism)
      entityId = leaf.id.hashCode.toLong
      sampler = RiskSampler.fromDistribution(
        entityId = entityId,
        riskId = leaf.id,
        occurrenceProb = probability,
        lossDistribution = distribution,
        seed3 = 0L,
        seed4 = 0L
      )
      
      // Capture provenance if requested
      provenance = if (includeProvenance) {
        Some(NodeProvenance(
          riskId = leaf.id,
          entityId = entityId,
          occurrenceVarId = entityId.hashCode + 1000L,
          lossVarId = entityId.hashCode + 2000L,
          globalSeed3 = 0L,
          globalSeed4 = 0L,
          distributionType = leaf.distributionType,
          distributionParams = distParams,
          timestamp = Instant.now(),
          simulationUtilVersion = "0.8.0" // TODO: Get from build info
        ))
      } else None
    } yield (sampler, provenance)
  }
  
  /**
   * Create MetalogDistribution from RiskLeaf parameters.
   * Returns both distribution and parameters for provenance.
   */
  private def createDistributionWithParams(leaf: RiskLeaf): Task[(Distribution, com.risquanter.register.domain.data.DistributionParams)] = {
    import io.github.iltotore.iron.*
    import com.risquanter.register.domain.data.iron.Probability
    import com.risquanter.register.simulation.PositiveInt
    
    leaf.distributionType.toLowerCase match {
      // Expert opinion mode: fit from percentiles + quantiles
      case "expert" =>
        (leaf.percentiles, leaf.quantiles) match {
          case (Some(ps), Some(qs)) if ps.length == qs.length && ps.length >= 2 =>
            // Refine percentile values to Probability type
            val percentileResults = ps.map(p => ValidationUtil.refineProbability(p))
            val percentileErrors = percentileResults.collect { case Left(errors) => errors }.flatten
            
            if (percentileErrors.nonEmpty) {
              ZIO.fail(ValidationFailed(percentileErrors.toList))
            } else {
              val percentiles = percentileResults.collect { case Right(p) => p }
              val terms = ps.length.asInstanceOf[PositiveInt]
              
              MetalogDistribution.fromPercentiles(
                percentiles = percentiles,
                quantiles = qs,
                terms = terms,
                lower = Some(0.0) // Loss cannot be negative
              ) match {
                case Right(m) =>
                  val params = ExpertDistributionParams(
                    percentiles = ps,
                    quantiles = qs,
                    terms = ps.length
                  )
                  ZIO.succeed((m, params))
                case Left(e) => ZIO.fail(ValidationFailed(List(ValidationError(
                  field = s"riskLeaf.${leaf.id}.metalogFit",
                  code = ValidationErrorCode.DistributionFitFailed,
                  message = s"Failed to fit Metalog for '${leaf.id}': ${e.message}"
                ))))
              }
            }
          
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskLeaf.${leaf.id}.expertParams",
              code = ValidationErrorCode.InvalidExpertOpinionParams,
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
                code = ValidationErrorCode.DistributionFitFailed,
                message = s"Failed to create lognormal for '${leaf.id}': ${err.message}"
              ))))
            }
          
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskLeaf.${leaf.id}.lognormalParams",
              code = ValidationErrorCode.InvalidLognormalParams,
              message = s"Lognormal mode requires minLoss > 0 and minLoss < maxLoss for '${leaf.id}'"
            ))))
        }
      
      case other =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field = s"riskLeaf.${leaf.id}.distributionType",
          code = ValidationErrorCode.UnsupportedDistributionType,
          message = s"Unsupported distribution type: $other for '${leaf.id}'"
        ))))
    }
  }
}

