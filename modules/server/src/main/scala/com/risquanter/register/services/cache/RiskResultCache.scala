package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.RiskResult
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}

/**
  * RiskResult cache service (ADR-014).
  *
  * Caches simulation outcomes (RiskResult) by node ID.
  * LEC curves are computed at render time from cached outcomes using
  * LECGenerator.generateCurvePoints or generateCurvePointsMulti.
  *
  * When a node changes, uses TreeIndex to invalidate affected ancestors in O(depth) time.
  *
  * Thread-safe via ZIO Ref.
  *
  * == Why Cache RiskResult, Not Rendered Curves ==
  *
  * LEC curves depend on display context - the X-axis (loss ticks) range depends
  * on which nodes are displayed together. Caching rendered curves would require
  * interpolation when the tick domain changes, producing mathematical errors.
  *
  * By caching RiskResult (simulation outcomes), we can compute exact exceedance
  * probabilities at any tick value using RiskResult.probOfExceedance(loss).
  *
  * == Cache Invalidation Semantics ==
  *
  * When a leaf node changes, its ancestors' cached results become stale
  * because aggregate distributions are computed bottom-up. This cache automatically
  * invalidates the entire ancestor path when `invalidate` is called.
  *
  * Example tree:
  * {{{
  *        portfolio (root)
  *           /    \
  *      ops-risk   market-risk
  *        /   \
  *    cyber  hardware   <-- leaf nodes
  * }}}
  *
  * Example: Hardware parameters change, requiring parent recalculation:
  * {{{
  * for
  *   // 1. Initial state: cache populated after simulation
  *   _           <- RiskResultCache.put(hardware, hardwareResult)
  *   _           <- RiskResultCache.put(cyber, cyberResult)
  *   _           <- RiskResultCache.put(opsRisk, opsRiskResult)    // aggregated from children
  *   _           <- RiskResultCache.put(portfolio, portfolioResult) // aggregated from all
  *   
  *   // 2. User modifies hardware node (e.g., changes probability)
  *   //    Hardware's result is now stale, AND so are all ancestors
  *   
  *   // 3. Invalidate hardware → clears hardware, ops-risk, portfolio
  *   invalidated <- RiskResultCache.invalidate(hardware)
  *   // invalidated = List(portfolio, ops-risk, hardware)  (root to leaf)
  *   
  *   // 4. market-risk cache is PRESERVED (not an ancestor of hardware)
  *   stillCached <- RiskResultCache.contains(marketRisk)
  *   // stillCached = true
  * yield invalidated
  * }}}
  *
  * This enables O(depth) invalidation instead of clearing the entire cache,
  * preserving expensive simulation results for unaffected subtrees.
  */
trait RiskResultCache {

  /**
    * Get cached result for a node.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @return Cached RiskResult if present
    */
  def get(nodeId: NodeId): UIO[Option[RiskResult]]

  /**
    * Store result in cache.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @param result RiskResult to cache
    */
  def put(nodeId: NodeId, result: RiskResult): UIO[Unit]

  /**
    * Remove result from cache.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    */
  def remove(nodeId: NodeId): UIO[Unit]

  /**
    * Invalidate cache for a node and all its ancestors.
    *
    * Uses TreeIndex to walk up the tree and clear cache entries
    * for the entire affected path.
    *
    * @param nodeId Changed node identifier (SafeId.SafeId)
    * @return List of invalidated node IDs (top-down: root to nodeId)
    */
  def invalidate(nodeId: NodeId): UIO[List[NodeId]]

  /**
    * Clear all cache entries.
    */
  def clear: UIO[Unit]

  /**
    * Atomically clear cache and return the number of entries cleared.
    *
    * Uses Ref.modify for correct semantics: the returned count is exactly
    * the number of entries that were removed, with no race window between
    * reading the size and clearing the map.
    *
    * @return Number of entries that were cleared
    */
  def clearAndGetSize: UIO[Int]

  /**
    * Get current cache size.
    *
    * @return Number of cached entries
    */
  def size: UIO[Int]

  /**
    * Check if cache contains entry for node.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @return true if cached
    */
  def contains(nodeId: NodeId): UIO[Boolean]

  /**
    * Get all cached node IDs.
    *
    * @return List of node IDs in cache
    */
  def keys: UIO[List[NodeId]]
}

object RiskResultCache {
  
  /**
    * Create live implementation with empty cache.
    *
    * @param treeIndex Tree index for ancestor lookup
    * @return ZLayer providing RiskResultCache
    */
  def layer: ZLayer[TreeIndex, Nothing, RiskResultCache] =
    ZLayer.fromZIO {
      for
        treeIndex <- ZIO.service[TreeIndex]
        cache     <- Ref.make(Map.empty[NodeId, RiskResult])
      yield RiskResultCacheLive(treeIndex, cache)
    }

  // Accessor methods for ZIO service pattern
  def get(nodeId: NodeId): URIO[RiskResultCache, Option[RiskResult]] =
    ZIO.serviceWithZIO[RiskResultCache](_.get(nodeId))

  def put(nodeId: NodeId, result: RiskResult): URIO[RiskResultCache, Unit] =
    ZIO.serviceWithZIO[RiskResultCache](_.put(nodeId, result))

  def remove(nodeId: NodeId): URIO[RiskResultCache, Unit] =
    ZIO.serviceWithZIO[RiskResultCache](_.remove(nodeId))

  def invalidate(nodeId: NodeId): URIO[RiskResultCache, List[NodeId]] =
    ZIO.serviceWithZIO[RiskResultCache](_.invalidate(nodeId))

  def clear: URIO[RiskResultCache, Unit] =
    ZIO.serviceWithZIO[RiskResultCache](_.clear)

  def clearAndGetSize: URIO[RiskResultCache, Int] =
    ZIO.serviceWithZIO[RiskResultCache](_.clearAndGetSize)

  def size: URIO[RiskResultCache, Int] =
    ZIO.serviceWithZIO[RiskResultCache](_.size)

  def contains(nodeId: NodeId): URIO[RiskResultCache, Boolean] =
    ZIO.serviceWithZIO[RiskResultCache](_.contains(nodeId))

  def keys: URIO[RiskResultCache, List[NodeId]] =
    ZIO.serviceWithZIO[RiskResultCache](_.keys)
}

/**
  * Live implementation of RiskResultCache.
  *
  * @param treeIndex Tree structure for ancestor lookup
  * @param cacheRef Thread-safe cache storage
  */
final class RiskResultCacheLive(
    treeIndex: TreeIndex,
    cacheRef: Ref[Map[NodeId, RiskResult]]
) extends RiskResultCache {

  override def get(nodeId: NodeId): UIO[Option[RiskResult]] =
    cacheRef.get.map(_.get(nodeId))

  override def put(nodeId: NodeId, result: RiskResult): UIO[Unit] =
    for
      _ <- cacheRef.update(_ + (nodeId -> result))
      _ <- ZIO.logDebug(s"RiskResultCache PUT: ${nodeId.value} (${result.outcomes.size} outcomes, max=${result.maxLoss})")
    yield ()

  override def remove(nodeId: NodeId): UIO[Unit] =
    for
      _ <- cacheRef.update(_ - nodeId)
      _ <- ZIO.logDebug(s"RiskResultCache REMOVE: ${nodeId.value}")
    yield ()

  override def invalidate(nodeId: NodeId): UIO[List[NodeId]] =
    for
      path <- ZIO.succeed(treeIndex.ancestorPath(nodeId))
      _    <- cacheRef.update(cache => cache -- path)
      _    <- ZIO.logInfo(s"RiskResultCache invalidated: ${path.map(_.value).mkString(" → ")}")
    yield path

  override def clear: UIO[Unit] =
    for
      _ <- cacheRef.set(Map.empty)
      _ <- ZIO.logDebug("RiskResultCache cleared")
    yield ()

  override def clearAndGetSize: UIO[Int] =
    for
      // Atomic read-and-clear: size returned matches exactly what was cleared
      size <- cacheRef.modify(m => (m.size, Map.empty))
      _    <- ZIO.logDebug(s"RiskResultCache cleared $size entries (atomic)")
    yield size

  override def size: UIO[Int] =
    cacheRef.get.map(_.size)

  override def contains(nodeId: NodeId): UIO[Boolean] =
    cacheRef.get.map(_.contains(nodeId))

  override def keys: UIO[List[NodeId]] =
    cacheRef.get.map(_.keys.toList)
}
