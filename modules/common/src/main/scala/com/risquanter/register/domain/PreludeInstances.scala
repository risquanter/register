package com.risquanter.register.domain

import zio.prelude.{Associative, Identity, Equal, Ord, Debug}
import com.risquanter.register.domain.data.{Loss, TrialId}

/**
 * ZIO Prelude type class instances for domain types.
 * 
 * Provides lawful instances for:
 * - Identity (combines Associative + identity element, equivalent to Monoid)
 * - Ord: Total ordering (Loss, TrialId)
 * - Equal: Value equality
 * - Debug: Human-readable representation
 */
object PreludeInstances {
  
  // ══════════════════════════════════════════════════════════════════
  // Loss Type Class Instances
  // ══════════════════════════════════════════════════════════════════
  
  /** Identity for Loss aggregation (additive monoid) */
  given lossIdentity: Identity[Loss] with {
    def identity: Loss = 0L
    def combine(l: => Loss, r: => Loss): Loss = l + r
  }
  
  /** Total ordering for Loss (natural Long ordering)
    * Note: Ord extends Equal, so this also provides equality */
  given lossOrd: Ord[Loss] = Ord.default[Long]
  
  /** Human-readable representation */
  given lossDebug: Debug[Loss] = Debug.make(loss => s"Loss($$${loss})")
  
  // ══════════════════════════════════════════════════════════════════
  // TrialId Type Class Instances
  // ══════════════════════════════════════════════════════════════════
  
  /** Total ordering for TrialId (natural Int ordering)
    * Note: Ord extends Equal, so this also provides equality */
  given trialIdOrd: Ord[TrialId] = Ord.default[Int]
  
  /** Human-readable representation */
  given trialIdDebug: Debug[TrialId] = Debug.make(id => s"Trial#$id")
}
