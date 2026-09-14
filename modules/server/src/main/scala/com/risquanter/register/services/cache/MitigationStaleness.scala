package com.risquanter.register.services.cache

import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, Mitigation, MitigationSpec}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, ContentHash}

/** Overrides whose stored base stamp no longer matches the anchor leaf's
  * current `LeafSimContent` hash. Fires on any edit path — form, merge, API
  * PUT, time-travel revert — that changes a simulation-relevant leaf field. A
  * rename or a reparent does not fire it, because the stamp hashes
  * `LeafSimContent`, which excludes name and parentId.
  *
  * Diagnostic only: resolution ignores staleness, so a stale override still
  * applies its frozen expert opinion. Nothing in production reads this yet; it
  * is built for HTTP handlers to report as `staleMitigationIds` on read and
  * update payloads, with the client rendering and never computing. */
object MitigationStaleness:

  def staleOverrides(tree: RiskTree): Set[MitigationId] =
    tree.mitigations.iterator.collect {
      case m @ Mitigation(_, _, _, MitigationSpec.LeafStage(_, Some(stamp), Some(anchor)), _)
          if isStale(tree, anchor, stamp) =>
        m.id
    }.toSet

  private def isStale(tree: RiskTree, anchor: NodeId, stamp: ContentHash): Boolean =
    tree.index.nodes.get(anchor) match
      case Some(leaf: RiskLeaf) => ContentHashIndex.hashOf(leaf) != stamp
      // The anchor is gone, or is no longer a leaf. A stored anchor that names
      // nothing can never bind again, so the override is reported stale rather
      // than rejected — a tree carrying one is valid and stays decodable.
      case _                    => true
