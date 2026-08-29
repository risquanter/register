package com.risquanter.register.services.cache

import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, Mitigation, MitigationSpec}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, ContentHash}

/** Staleness layer 1: overrides whose stored base stamp no longer matches the
  * anchor leaf's current LeafSimContent hash. Fires on any edit path (form,
  * merge, API PUT, time-travel revert) that changes a simulation-relevant leaf
  * field; renames/reparents do not fire (DD-16 projection — the stamp hashes
  * LeafSimContent, which excludes name and parentId). Diagnostic only:
  * resolution ignores staleness (frozen expert opinion is the ruled semantics).
  * The sole consumers are HTTP handlers that put `staleMitigationIds` into
  * read/update response payloads; the client renders, never computes. */
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
      case _                    => true   // anchor gone / no longer a leaf → stale (OD-8 Option A)
