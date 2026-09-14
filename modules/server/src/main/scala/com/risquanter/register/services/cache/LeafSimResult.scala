package com.risquanter.register.services.cache

import com.risquanter.register.domain.data.{TrialOutcomes, NodeProvenance}

/**
  * The `ContentCache` value type: identity-free simulation result content for
  * one leaf.
  *
  * A product of the monoid carrier (`TrialOutcomes` — trial count plus a
  * sparse trial-to-loss map) and the content-only provenance record
  * (`NodeProvenance`, which carries no node identity). No node ID appears
  * anywhere in the value, which is what lets content-identical leaves share
  * one entry; the resolver attaches the requested node's ID when building the
  * response.
  *
  * Provenance sits beside `TrialOutcomes`, not inside it, because provenance
  * does not participate in combination — portfolio provenance is read from
  * children, never merged.
  *
  * Leaf results only: portfolio results are never cached. Never serialized —
  * it lives in an in-memory `Ref` (ADR-015), so it has no codec.
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
