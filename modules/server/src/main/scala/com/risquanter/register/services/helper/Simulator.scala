package com.risquanter.register.services.helper

import com.risquanter.register.simulation.RiskSampler
import com.risquanter.register.domain.data.{RiskResult, TrialId, Loss}
import scala.collection.parallel.CollectionConverters.*
import zio.{ZIO, Task}

/**
 * Monte Carlo simulation engine with sparse storage optimization.
 * 
 * Design principles:
 * - Sparse storage: Only stores trials where risk occurred (loss > 0)
 * - Parallelization: Uses parallel collections for CPU-bound trial computation
 * - Determinism: Pure functions guarantee identical results for same seeds
 * - Memory efficiency: Avoids materializing zero-loss trials
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
}
