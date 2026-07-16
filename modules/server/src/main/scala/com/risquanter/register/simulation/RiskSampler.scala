package com.risquanter.register.simulation

import com.risquanter.register.domain.data.iron.{OccurrenceProbability, NodeId}

/**
 * Samples risk events combining occurrence probability and loss distribution.
 * 
 * Each trial samples:
 * 1. Occurrence: Bernoulli(occurrenceProb) → did event happen?
 * 2. Loss: MetalogDistribution.sample() → if occurred, how much?
 * 
 * Uses separate HDR generators on disjoint even/odd var-ID streams to prevent
 * correlation between occurrence and loss sampling (see SeedDerivation).
 * 
 * @see HDRWrapper for deterministic PRNG
 * @see MetalogDistribution for loss distribution
 */
trait RiskSampler {
  /** Unique identifier for the risk node this sampler represents */
  def nodeId: NodeId
  
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
   * Create RiskSampler from a loss distribution and a pre-derived HDR stream tuple.
   *
   * The node ID is an identity label only — it never influences any random
   * stream (old decision 1 = A: no redundant seed parameters). All stochastic
   * identity arrives in `streams`, produced by [[SeedDerivation.streams]]:
   * occurrence and loss use disjoint even/odd var IDs, preventing correlation
   * between "did it occur" and "how much loss".
   *
   * @param nodeId Node this sampler represents (label, not a seed)
   * @param streams HDR generator inputs from the single derivation site
   * @param occurrenceProb Probability event occurs in a trial
   * @param lossDistribution Loss amount distribution (if occurred)
   * @return RiskSampler with curried generator pattern
   *
   * @example
   * {{{
   * val streams = SeedDerivation.streams(workspace.seedEntityId, leaf.seedVarId, 0L, 0L)
   * val sampler = RiskSampler.fromDistribution(leaf.id, streams, leaf.probability, metalog)
   * val losses  = (0L until 1000L).flatMap(sampler.sample)
   * }}}
   */
  def fromDistribution(
    nodeId: NodeId,
    streams: HdrStreams,
    occurrenceProb: OccurrenceProbability,
    lossDistribution: Distribution
  ): RiskSampler = {

    // Create curried generators — one per stream, identifiers already derived
    val occurrenceRng = HDRWrapper.createGenerator(streams.entityId, streams.occurrenceVarId, streams.seed3, streams.seed4)
    val lossRng = HDRWrapper.createGenerator(streams.entityId, streams.lossVarId, streams.seed3, streams.seed4)

    val id = nodeId
    new RiskSampler {
      val nodeId: NodeId = id

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
