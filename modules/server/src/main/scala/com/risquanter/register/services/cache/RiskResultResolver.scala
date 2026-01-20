package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{RiskResult, RiskTree}
import com.risquanter.register.domain.tree.NodeId

/**
  * Service for resolving RiskResult with cache-aside pattern (ADR-015).
  *
  * Core primitive: `ensureCached(tree, nodeId)` checks cache first, simulates on miss.
  * All query APIs compose on top of this primitive.
  *
  * Methods now take `RiskTree` parameter to access tree-scoped TreeIndex.
  *
  * == Separation of Concerns ==
  * - RiskResultCache: Pure storage (get/put/invalidate)
  * - RiskResultResolver: Orchestration (cache + simulation)
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
    * Cache-aside pattern:
    * 1. Check cache for nodeId
    * 2. If hit: return cached result
    * 3. If miss: simulate node using tree's index, cache result, return it
    *
    * @param tree Risk tree containing the node (provides TreeIndex)
    * @param nodeId Node identifier
    * @return RiskResult (from cache or freshly simulated)
    */
  def ensureCached(tree: RiskTree, nodeId: NodeId): Task[RiskResult]

  /**
    * Batch version of ensureCached for multiple nodes.
    *
    * Optimized for multi-node display (e.g., split pane LEC comparison).
    * Nodes with cached results are returned immediately; only missing
    * nodes trigger simulation.
    *
    * @param tree Risk tree containing the nodes
    * @param nodeIds Set of node identifiers
    * @return Map from nodeId to RiskResult
    */
  def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId]): Task[Map[NodeId, RiskResult]]
}

object RiskResultResolver {

  // Accessor methods for ZIO service pattern
  def ensureCached(tree: RiskTree, nodeId: NodeId): ZIO[RiskResultResolver, Throwable, RiskResult] =
    ZIO.serviceWithZIO[RiskResultResolver](_.ensureCached(tree, nodeId))

  def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId]): ZIO[RiskResultResolver, Throwable, Map[NodeId, RiskResult]] =
    ZIO.serviceWithZIO[RiskResultResolver](_.ensureCachedAll(tree, nodeIds))
}
