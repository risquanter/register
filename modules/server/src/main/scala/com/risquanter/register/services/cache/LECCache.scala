package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.LECCurveData
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}

/**
  * LEC (Loss Exceedance Curve) cache service.
  *
  * Caches computed LEC data by node ID (SafeId.SafeId per ADR-001). When a node 
  * changes, uses TreeIndex to invalidate affected ancestors in O(depth) time.
  *
  * Thread-safe via ZIO Ref.
  *
  * == Cache Invalidation Semantics ==
  *
  * When a leaf node changes, its ancestors' cached LEC curves become stale
  * because aggregate LECs are computed bottom-up. This cache automatically
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
  *   _           <- LECCache.set(hardware, hardwareLEC)
  *   _           <- LECCache.set(cyber, cyberLEC)
  *   _           <- LECCache.set(opsRisk, opsRiskLEC)    // aggregated from children
  *   _           <- LECCache.set(portfolio, portfolioLEC) // aggregated from all
  *   
  *   // 2. User modifies hardware node (e.g., changes probability)
  *   //    Hardware's LEC is now stale, AND so are all ancestors
  *   
  *   // 3. Invalidate hardware → clears hardware, ops-risk, portfolio
  *   invalidated <- LECCache.invalidate(hardware)
  *   // invalidated = List(portfolio, ops-risk, hardware)  (root to leaf)
  *   
  *   // 4. market-risk cache is PRESERVED (not an ancestor of hardware)
  *   stillCached <- LECCache.contains(marketRisk)
  *   // stillCached = true
  * yield invalidated
  * }}}
  *
  * This enables O(depth) invalidation instead of clearing the entire cache,
  * preserving expensive LEC computations for unaffected subtrees.
  */
trait LECCache {

  /**
    * Get cached LEC data for a node.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @return Cached LEC data if present
    */
  def get(nodeId: NodeId): UIO[Option[LECCurveData]]

  /**
    * Store LEC data in cache.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @param lec LEC curve data
    */
  def set(nodeId: NodeId, lec: LECCurveData): UIO[Unit]

  /**
    * Remove LEC data from cache.
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
}

object LECCache {
  
  /**
    * Create live implementation with empty cache.
    *
    * @param treeIndex Tree index for ancestor lookup
    * @return ZLayer providing LECCache
    */
  def layer: ZLayer[TreeIndex, Nothing, LECCache] =
    ZLayer.fromZIO {
      for
        treeIndex <- ZIO.service[TreeIndex]
        cache     <- Ref.make(Map.empty[NodeId, LECCurveData])
      yield LECCacheLive(treeIndex, cache)
    }

  // Accessor methods for ZIO service pattern
  def get(nodeId: NodeId): URIO[LECCache, Option[LECCurveData]] =
    ZIO.serviceWithZIO[LECCache](_.get(nodeId))

  def set(nodeId: NodeId, lec: LECCurveData): URIO[LECCache, Unit] =
    ZIO.serviceWithZIO[LECCache](_.set(nodeId, lec))

  def remove(nodeId: NodeId): URIO[LECCache, Unit] =
    ZIO.serviceWithZIO[LECCache](_.remove(nodeId))

  def invalidate(nodeId: NodeId): URIO[LECCache, List[NodeId]] =
    ZIO.serviceWithZIO[LECCache](_.invalidate(nodeId))

  def clear: URIO[LECCache, Unit] =
    ZIO.serviceWithZIO[LECCache](_.clear)

  def size: URIO[LECCache, Int] =
    ZIO.serviceWithZIO[LECCache](_.size)

  def contains(nodeId: NodeId): URIO[LECCache, Boolean] =
    ZIO.serviceWithZIO[LECCache](_.contains(nodeId))
}

/**
  * Live implementation of LECCache.
  *
  * @param treeIndex Tree structure for ancestor lookup
  * @param cacheRef Thread-safe cache storage
  */
final class LECCacheLive(
    treeIndex: TreeIndex,
    cacheRef: Ref[Map[NodeId, LECCurveData]]
) extends LECCache {

  override def get(nodeId: NodeId): UIO[Option[LECCurveData]] =
    cacheRef.get.map(_.get(nodeId))

  override def set(nodeId: NodeId, lec: LECCurveData): UIO[Unit] =
    for
      _ <- cacheRef.update(_ + (nodeId -> lec))
      _ <- ZIO.logDebug(s"Cache SET: ${nodeId.value}")
    yield ()

  override def remove(nodeId: NodeId): UIO[Unit] =
    for
      _ <- cacheRef.update(_ - nodeId)
      _ <- ZIO.logDebug(s"Cache REMOVE: ${nodeId.value}")
    yield ()

  override def invalidate(nodeId: NodeId): UIO[List[NodeId]] =
    for
      path <- ZIO.succeed(treeIndex.ancestorPath(nodeId))
      _    <- cacheRef.update(cache => cache -- path)
      _    <- ZIO.logInfo(s"Cache invalidated: ${path.map(_.value).mkString(" → ")}")
    yield path

  override def clear: UIO[Unit] =
    for
      _ <- cacheRef.set(Map.empty)
      _ <- ZIO.logDebug("Cache cleared")
    yield ()

  override def size: UIO[Int] =
    cacheRef.get.map(_.size)

  override def contains(nodeId: NodeId): UIO[Boolean] =
    cacheRef.get.map(_.contains(nodeId))
}
