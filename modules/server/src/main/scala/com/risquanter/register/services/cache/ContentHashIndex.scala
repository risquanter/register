package com.risquanter.register.services.cache

import java.security.MessageDigest
import zio.json.EncoderOps
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, LeafSimContent}
import com.risquanter.register.domain.data.iron.{NodeId, ContentHash}

/**
  * Pure, bottom-up content-hash computation for a risk tree (DD-14 → Option
  * B: the JVM computes every hash itself — one code path, no Irmin coupling,
  * unit-testable without a running Irmin).
  *
  * - Leaf: `sha256(LeafSimContent.from(leaf).toJson)` — the simulation-
  *   relevant projection (DD-16), so renames/moves preserve the hash and
  *   content-identical leaves collide deliberately (shared cache entries).
  * - Portfolio: Merkle hash over the children's hashes, sorted for
  *   canonical order. Portfolio hashes never key cache entries (DD-15 → B);
  *   they exist for structural diffing (branch comparison, UC5).
  *
  * O(n) — each node visited once (memoized); invisible against simulation
  * cost.
  */
object ContentHashIndex {

  def build(tree: RiskTree): Map[NodeId, ContentHash] = {
    val index = scala.collection.mutable.Map.empty[NodeId, ContentHash]

    def computeHash(nodeId: NodeId): ContentHash =
      index.getOrElseUpdate(
        nodeId,
        tree.index.nodes(nodeId) match {
          case leaf: RiskLeaf =>
            hashOf(leaf)

          case p: RiskPortfolio =>
            val childHashes = p.childIds
              .map(computeHash)
              .map(_.value)
              .sorted
              .mkString("|")
            contentHash(childHashes)
        }
      )

    tree.index.rootId.foreach(computeHash)
    index.toMap
  }

  /** The cache key for a single leaf (DD-16 preimage). */
  def hashOf(leaf: RiskLeaf): ContentHash =
    contentHash(LeafSimContent.from(leaf).toJson)

  private def contentHash(input: String): ContentHash = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hex = digest
      .digest(input.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
    // A SHA-256 hex rendering always satisfies ^[a-f0-9]{64}$; fromString
    // keeps the refinement as the single validation site.
    ContentHash.fromString(hex).fold(
      errors => throw new IllegalStateException(s"SHA-256 hex failed ContentHash refinement: $errors"),
      identity
    )
  }
}
