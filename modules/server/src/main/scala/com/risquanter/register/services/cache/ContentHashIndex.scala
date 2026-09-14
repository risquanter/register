package com.risquanter.register.services.cache

import java.security.MessageDigest
import zio.json.EncoderOps
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, LeafSimContent}
import com.risquanter.register.domain.data.iron.{NodeId, ContentHash}

/**
  * Pure, bottom-up content-hash computation for a risk tree. The JVM computes
  * every hash itself, so there is one code path, no coupling to Irmin, and the
  * calculation is unit-testable without a running Irmin.
  *
  * - Leaf: `sha256(LeafSimContent.from(leaf).toJson)` — the simulation-relevant
  *   projection only, so renames and moves preserve the hash, and
  *   content-identical leaves collide deliberately and share one cache entry.
  * - Portfolio: Merkle hash over the children's hashes, sorted for canonical
  *   order. Portfolio hashes never key cache entries; they exist for
  *   structural diffing when two branches are compared.
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

  /** The cache key for a single leaf. */
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
