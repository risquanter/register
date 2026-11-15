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
  * applies the figures frozen into it. The structure read reports the result
  * as `staleMitigationIds`, and the client renders it and never computes it. */
object MitigationStaleness:

  def staleOverrides(tree: RiskTree): Set[MitigationId] =
    tree.mitigations.iterator.collect {
      case m @ Mitigation(_, _, _, MitigationSpec.LeafAbsolute(_, anchor, stamp), _)
          if isStale(tree, anchor, stamp) =>
        m.id
    }.toSet

  private def isStale(tree: RiskTree, anchor: NodeId, stamp: ContentHash): Boolean =
    tree.index.nodes.get(anchor) match
      case Some(leaf: RiskLeaf) => ContentHashIndex.hashOf(leaf) != stamp
      // The anchor is gone, or is no longer a leaf. The override then applies
      // to no node, which is a no-op like any mitigation with nothing to
      // apply to, so it is not reported.
      case _                    => false
