package com.risquanter.register.services.cache

import com.risquanter.register.domain.data.{TrialOutcomes, NodeProvenance}

/**
  * The `ContentCache` value type (DD-18, closed 2026-07-16): identity-free
  * simulation result content for one leaf.
  *
  * A product of the monoid carrier (`TrialOutcomes` — trial count + sparse
  * trial→loss map) and the content-only provenance record (`NodeProvenance`,
  * DD-19: carries no node identity). No node ID anywhere in the value — the
  * resolver attaches the *requested* node's ID when building the response,
  * which is what lets content-identical leaves share one entry.
  *
  * Provenance sits beside `TrialOutcomes`, not inside it, because provenance
  * does not participate in combination (portfolio provenance is read from
  * children, never merged).
  *
  * Leaf-only by design (DD-15 → B): portfolio results are never cached.
  * Never serialized — lives in an in-memory `Ref` (ADR-015), so no codec.
  */
final case class LeafSimResult(
  outcomes: TrialOutcomes,
  provenance: NodeProvenance
) {
  /** Rough in-memory footprint, for `EvictionStrategy.onStore` accounting:
    * ~16 bytes per sparse map entry (boxed key + value) plus a fixed
    * overhead for the record itself.
    */
  def approxSizeBytes: Long = 256L + 16L * outcomes.outcomes.size
}
