package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{LossDistribution, RiskTree}
import com.risquanter.register.domain.data.iron.{NodeId, SeedEntityId}

/**
  * Service for resolving RiskResult with cache-aside pattern (ADR-015).
  *
  * Core primitive: `ensureCached(tree, nodeId)` checks cache first, simulates on miss.
  * All query APIs compose on top of this primitive.
  *
  * Methods now take `RiskTree` parameter to access tree-scoped TreeIndex.
  *
  * == Separation of Concerns ==
  * - ContentCache: Pure content-addressed storage (get/put; no invalidation —
  *   an edited leaf hashes to a new key and misses naturally)
  * - RiskResultResolver: Orchestration (content hashing + cache + simulation)
  *
  * == Usage Pattern ==
  * {{{
  * // Query APIs become simple compositions:
  * def getLECCurve(tree: RiskTree, nodeId: NodeId) =
  *   resolver.ensureCached(tree, nodeId).map(LECGenerator.generateCurvePoints(_))
  *
  * def probOfExceedance(tree: RiskTree, nodeId: NodeId, threshold: Loss) =
  *   resolver.ensureCached(tree, nodeId).map(_.probOfExceedance(threshold))
  * }}}
  */
trait RiskResultResolver {

  /**
    * Ensure result is cached for a node.
    *
    * Cache-aside pattern (content-addressed since milestone 2b Phase A):
    * 1. Compute the node's content hash from the tree (ContentHashIndex)
    * 2. Leaf hit: return cached content with this node's ID attached
    * 3. Leaf miss: simulate, cache under the content hash, return
    * 4. Portfolio: aggregate child results on every read (never cached)
    *
    * @param tree Risk tree containing the node (provides TreeIndex)
    * @param nodeId Node identifier
    * @param seedEntityId Owning workspace's stochastic identity (HDR Entity axis) —
    *                     threaded explicitly from the controller's resolved workspace
    * @param includeProvenance Whether to capture provenance metadata (default: false)
    * @return LossDistribution (from cache or freshly simulated)
    */
  def ensureCached(tree: RiskTree, nodeId: NodeId, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean = false): Task[LossDistribution]

  /**
    * Batch version of ensureCached for multiple nodes.
    *
    * Optimized for multi-node display (e.g., split pane LEC comparison).
    * Nodes with cached results are returned immediately; only missing
    * nodes trigger simulation.
    *
    * @param tree Risk tree containing the nodes
    * @param nodeIds Set of node identifiers
    * @param seedEntityId Owning workspace's stochastic identity (HDR Entity axis)
    * @param includeProvenance Whether to capture provenance metadata (default: false)
    * @return Map from nodeId to LossDistribution
    */
  def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean = false): Task[Map[NodeId, LossDistribution]]
}

object RiskResultResolver {

  // Accessor methods for ZIO service pattern
  def ensureCached(tree: RiskTree, nodeId: NodeId, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean = false): ZIO[RiskResultResolver, Throwable, LossDistribution] =
    ZIO.serviceWithZIO[RiskResultResolver](_.ensureCached(tree, nodeId, seedEntityId, includeProvenance))

  def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean = false): ZIO[RiskResultResolver, Throwable, Map[NodeId, LossDistribution]] =
    ZIO.serviceWithZIO[RiskResultResolver](_.ensureCachedAll(tree, nodeIds, seedEntityId, includeProvenance))
}
