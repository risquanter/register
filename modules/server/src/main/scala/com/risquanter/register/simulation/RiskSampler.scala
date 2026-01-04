package com.risquanter.register.simulation

import com.risquanter.register.domain.data.iron.Probability

/**
 * Samples risk events combining occurrence probability and loss distribution.
 * 
 * Each trial samples:
 * 1. Occurrence: Bernoulli(occurrenceProb) → did event happen?
 * 2. Loss: MetalogDistribution.sample() → if occurred, how much?
 * 
 * Uses separate HDR generators with offset seeds to prevent correlation
 * between occurrence and loss sampling.
 * 
 * @see HDRWrapper for deterministic PRNG
 * @see MetalogDistribution for loss distribution
 */
trait RiskSampler {
  /** Unique identifier for this risk */
  def id: String
  
  /** 
   * Sample occurrence for a trial.
   * 
   * @param trial Trial counter (0-based)
   * @return true if event occurred in this trial
   */
  def sampleOccurrence(trial: Long): Boolean
  
  /** 
   * Sample loss amount for a trial (assuming occurrence).
   * 
   * @param trial Trial counter (0-based)
   * @return Loss amount in base currency units
   */
  def sampleLoss(trial: Long): Long
  
  /**
   * Sample occurrence and loss together.
   * 
   * @param trial Trial counter (0-based)
   * @return Some(loss) if event occurred, None otherwise
   */
  def sample(trial: Long): Option[Long] = {
    if (sampleOccurrence(trial)) Some(sampleLoss(trial))
    else None
  }
}

object RiskSampler {
  
  /**
   * Create RiskSampler from Metalog loss distribution.
   * 
   * Uses HDR with offset seeds for occurrence vs loss:
   * - Occurrence: varId = hash(riskId) + 1000
   * - Loss: varId = hash(riskId) + 2000
   * 
   * This prevents correlation between "did it occur" and "how much loss".
   * 
   * @param entityId Entity identifier (e.g., company ID)
   * @param riskId Unique risk identifier
   * @param occurrenceProb Probability event occurs in a trial
   * @param lossDistribution Loss amount distribution (if occurred)
   * @param seed3 Global seed 3 for HDR (default 0)
   * @param seed4 Global seed 4 for HDR (default 0)
   * @return RiskSampler with curried generator pattern
   * 
   * @example
   * {{{
   * val metalog = MetalogDistribution.fromPercentiles(...)
   * val sampler = RiskSampler.fromMetalog(
   *   entityId = 123L,
   *   riskId = "CYBER-001",
   *   occurrenceProb = 0.15.refineUnsafe,
   *   lossDistribution = metalog.toOption.get
   * )
   * 
   * // Sample 1000 trials
   * val losses = (0L until 1000L).flatMap(sampler.sample)
   * }}}
   */
  def fromDistribution(
    entityId: Long,
    riskId: String,
    occurrenceProb: Probability,
    lossDistribution: Distribution,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): RiskSampler = {
    
    // Hash risk ID and offset for occurrence vs loss sampling
    val riskHash = riskId.hashCode.toLong
    val occurrenceVarId = riskHash + 1000L
    val lossVarId = riskHash + 2000L
    
    // Create curried generators
    val occurrenceRng = HDRWrapper.createGenerator(entityId, occurrenceVarId, seed3, seed4)
    val lossRng = HDRWrapper.createGenerator(entityId, lossVarId, seed3, seed4)
    
    new RiskSampler {
      val id: String = riskId
      
      def sampleOccurrence(trial: Long): Boolean = {
        val uniform = occurrenceRng(trial)
        uniform < (occurrenceProb: Double)
      }
      
      def sampleLoss(trial: Long): Long = {
        val uniform = lossRng(trial)
        val lossAmount = lossDistribution.sample(uniform)
        lossAmount.toLong
      }
    }
  }
}
