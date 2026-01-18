package com.risquanter.register.services.helper

import com.risquanter.register.BuildInfo
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution, Distribution}
import com.risquanter.register.domain.data.{RiskResult, TrialId, Loss, RiskNode, RiskLeaf, RiskPortfolio, RiskTreeResult, NodeProvenance, TreeProvenance, ExpertDistributionParams, LognormalDistributionParams}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.domain.data.iron.{PositiveInt, Probability}
import io.github.iltotore.iron.refineUnsafe
import io.github.iltotore.iron.constraint.numeric.Greater
import zio.prelude.Identity
import com.risquanter.register.simulation.LognormalHelper
import com.risquanter.register.domain.data.iron.ValidationUtil
import zio.{ZIO, Task, Chunk}
import java.time.Instant

// Default parallelism for trial-level computation within a single risk
private val DefaultTrialParallelism: Int = Runtime.getRuntime.availableProcessors()

/**
 * Monte Carlo simulation engine with sparse storage optimization.
 * 
 * Design principles:
 * - Sparse storage: Only stores trials where risk occurred (loss > 0)
 * - Parallelization: Uses ZIO fibers for parallel computation at both:
 *   - Risk level: Multiple risks simulated concurrently
 *   - Trial level: Trials within a risk computed in parallel batches
 * - Determinism: Pure functions guarantee identical results for same seeds
 * - Memory efficiency: Avoids materializing zero-loss trials
 * - Recursive tree simulation: Bottom-up aggregation with lazy evaluation
 * - GraalVM Native Image compatible: No Scala parallel collections
 */
object Simulator {
  
  /** 
   * Run trials for a single risk using sparse storage with ZIO parallelization.
   * Only stores trials where risk occurred (non-zero loss).
   * 
   * Computation strategy:
   * 1. Filter: Identify successful trials (where risk occurred)
   * 2. Chunk: Split successful trials into batches for parallel processing
   * 3. Parallel map: Compute losses across batches using ZIO fibers
   * 4. Combine: Merge results into final sparse map
   * 
   * Why ZIO parallelization vs sequential:
   * - A single risk with 100K trials benefits from multi-core processing
   * - GraalVM native image has efficient thread handling (no JIT warm-up)
   * - ZIO fibers are lightweight and work well with native images
   * - Risk-level parallelism alone is insufficient for trees with few leaves
   * 
   * Thread safety: sampler functions are pure (HDR-based determinism)
   * 
   * @param sampler RiskSampler with occurrence + loss distribution
   * @param nTrials Total number of trials to simulate (must be positive)
   * @param parallelism Number of parallel fibers for trial computation
   * @return Task of sparse map: trial ID → loss (only non-zero outcomes)
   */
  def performTrials(
    sampler: RiskSampler,
    nTrials: PositiveInt,
    parallelism: Int = DefaultTrialParallelism
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
   * Synchronous version of performTrials for tests and simple use cases.
   * Uses sequential processing - suitable for small trial counts.
   */
  def performTrialsSync(
    sampler: RiskSampler,
    nTrials: PositiveInt
  ): Map[TrialId, Loss] = {
    val n: Int = nTrials
    val successfulTrials = (0 until n).view
      .filter(trial => sampler.sampleOccurrence(trial.toLong))
      .toVector
    
    successfulTrials.map { trial =>
      (trial, sampler.sampleLoss(trial.toLong))
    }.toMap
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
   * @param nTrials Number of trials per risk (must be positive)
   * @param parallelism Maximum concurrent fibers (must be positive, default: 8)
   * @return Task of RiskResult for each risk
   */
  def simulate(
    samplers: Vector[RiskSampler],
    nTrials: PositiveInt,
    parallelism: PositiveInt = 8.refineUnsafe
  ): Task[Vector[RiskResult]] = {
    val n: Int = nTrials
    val p: Int = parallelism
    val trialSets = samplers.map { sampler =>
      performTrials(sampler, nTrials, p).map { trials =>
        RiskResult(sampler.id, trials, n)
      }
    }
    
    ZIO.collectAllPar(trialSets).withParallelism(parallelism)
  }
  
  /**
   * Sequential simulation for small workloads or debugging.
   * Guaranteed deterministic execution order.
   * 
   * @param samplers Vector of risk samplers to simulate
   * @param nTrials Number of trials per risk (must be positive)
   * @return Task of RiskResult for each risk
   */
  def simulateSequential(
    samplers: Vector[RiskSampler],
    nTrials: PositiveInt
  ): Task[Vector[RiskResult]] = {
    val n: Int = nTrials
    ZIO.foreach(samplers) { sampler =>
      // Use sync version for sequential simulation (no parallelism overhead)
      ZIO.attempt {
        val trials = performTrialsSync(sampler, nTrials)
        RiskResult(sampler.id, trials, n)
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
   * @param nTrials Number of Monte Carlo trials (must be positive)
   * @param parallelism Max concurrent child simulations (must be positive)
   * @param includeProvenance Whether to capture provenance metadata
   * @param seed3 Global seed 3 for HDR random number generation
   * @param seed4 Global seed 4 for HDR random number generation
   * @return Tuple of (RiskTreeResult, Option[TreeProvenance])
   */
  def simulateTree(
    node: RiskNode,
    nTrials: PositiveInt,
    parallelism: PositiveInt = 8.refineUnsafe,
    includeProvenance: Boolean = false,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Task[(RiskTreeResult, Option[TreeProvenance])] = {
    val n: Int = nTrials
    val p: Int = parallelism
    simulateTreeInternal(node, nTrials, parallelism, includeProvenance, seed3, seed4, Map.empty).map {
      case (result, provenances) =>
        val treeProvenance = if (includeProvenance) {
          Some(TreeProvenance(
            treeId = 0L, // Will be set by service layer with actual tree ID
            globalSeeds = (seed3, seed4),
            nTrials = n,
            parallelism = p,
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
    nTrials: PositiveInt,
    parallelism: PositiveInt,
    includeProvenance: Boolean,
    seed3: Long,
    seed4: Long,
    provenances: Map[String, NodeProvenance]
  ): Task[(RiskTreeResult, Map[String, NodeProvenance])] = {
    val n: Int = nTrials
    node match {
      case leaf: RiskLeaf =>
        // Terminal node: create sampler and simulate
        val p: Int = parallelism
        for {
          samplerAndProv <- createSamplerFromLeaf(leaf, includeProvenance, seed3, seed4)
          (sampler, maybeProv) = samplerAndProv
          trials <- performTrials(sampler, nTrials, p)
          // RiskTreeResult uses String id for wire format (deprecated - see ADR-015)
          result = RiskTreeResult.Leaf(leaf.id.value.toString, RiskResult(leaf.id, trials, n))
          updatedProvenances = maybeProv match {
            case Some(prov) => provenances + (leaf.id.value.toString -> prov)
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
              code = ValidationErrorCode.EMPTY_COLLECTION,
              message = s"RiskPortfolio '${portfolio.id}' has no children"
            ))))
          )
          
          // Recursively simulate all children in parallel
          childResultsWithProv <- ZIO.collectAllPar(
            portfolio.children.map(child => simulateTreeInternal(child, nTrials, parallelism, includeProvenance, seed3, seed4, provenances))
          ).withParallelism(parallelism)
          
          // Separate results and provenances
          childResults = childResultsWithProv.map(_._1)
          mergedProvenances = childResultsWithProv.foldLeft(provenances)((acc, tuple) => acc ++ tuple._2)
          
          // Aggregate children using Identity.combine
          aggregated <- ZIO.attempt {
            val childRiskResults = childResults.map(_.result)
            val combined = childRiskResults.reduce((a, b) => Identity[RiskResult].combine(a, b))
            
            // Update name to portfolio ID (String for wire format - deprecated, see ADR-015)
            RiskTreeResult.Branch(
              id = portfolio.id.value.toString,
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
   * Note: leaf.probability is already refined to Probability type at the boundary,
   * so no additional validation is needed here.
   * 
   * @param leaf RiskLeaf definition
   * @param includeProvenance Whether to capture provenance metadata
   * @param seed3 Global seed 3 for HDR random number generation
   * @param seed4 Global seed 4 for HDR random number generation
   * @return Tuple of (RiskSampler, Option[NodeProvenance])
   */
  private[services] def createSamplerFromLeaf(
    leaf: RiskLeaf,
    includeProvenance: Boolean = false,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Task[(RiskSampler, Option[NodeProvenance])] = {
    for {
      // Create distribution based on mode
      distAndParams <- createDistributionWithParams(leaf)
      (distribution, distParams) = distAndParams
      
      // Build sampler (using entityId = hash of leaf.id for determinism)
      entityId = leaf.id.value.toString.hashCode.toLong
      sampler = RiskSampler.fromDistribution(
        entityId = entityId,
        riskId = leaf.id,
        occurrenceProb = leaf.probability, // Already Probability type from domain model
        lossDistribution = distribution,
        seed3 = seed3,
        seed4 = seed4
      )
      
      // Capture provenance if requested (riskId as String for wire format)
      provenance = if (includeProvenance) {
        Some(NodeProvenance(
          riskId = leaf.id.value.toString,
          entityId = entityId,
          occurrenceVarId = entityId.hashCode + 1000L,
          lossVarId = entityId.hashCode + 2000L,
          globalSeed3 = seed3,
          globalSeed4 = seed4,
          distributionType = leaf.distributionType,
          distributionParams = distParams,
          timestamp = Instant.now(),
          simulationUtilVersion = BuildInfo.simulationUtilVersion
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
              val terms = ps.length.asInstanceOf[PositiveInt]
              
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
                    terms = ps.length
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

