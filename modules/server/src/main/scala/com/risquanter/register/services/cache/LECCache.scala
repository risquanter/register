package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.bundle.CurveBundle
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}

/**
  * CurveBundle cache service (ADR-014).
  *
  * Caches tick-aligned LEC curve bundles by node ID (SafeId.SafeId per ADR-001).
  * When a node changes, uses TreeIndex to invalidate affected ancestors in O(depth) time.
  *
  * Thread-safe via ZIO Ref.
  *
  * == Cache Invalidation Semantics ==
  *
  * When a leaf node changes, its ancestors' cached bundles become stale
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
  *   _           <- CurveBundleCache.set(hardware, hardwareBundle)
  *   _           <- CurveBundleCache.set(cyber, cyberBundle)
  *   _           <- CurveBundleCache.set(opsRisk, opsRiskBundle)    // aggregated from children
  *   _           <- CurveBundleCache.set(portfolio, portfolioBundle) // aggregated from all
  *   
  *   // 2. User modifies hardware node (e.g., changes probability)
  *   //    Hardware's bundle is now stale, AND so are all ancestors
  *   
  *   // 3. Invalidate hardware → clears hardware, ops-risk, portfolio
  *   invalidated <- CurveBundleCache.invalidate(hardware)
  *   // invalidated = List(portfolio, ops-risk, hardware)  (root to leaf)
  *   
  *   // 4. market-risk cache is PRESERVED (not an ancestor of hardware)
  *   stillCached <- CurveBundleCache.contains(marketRisk)
  *   // stillCached = true
  * yield invalidated
  * }}}
  *
  * This enables O(depth) invalidation instead of clearing the entire cache,
  * preserving expensive LEC computations for unaffected subtrees.
  */
trait CurveBundleCache {

  /**
    * Get cached bundle for a node.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @return Cached bundle if present
    */
  def get(nodeId: NodeId): UIO[Option[CurveBundle]]

  /**
    * Store bundle in cache.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @param bundle CurveBundle to cache
    */
  def set(nodeId: NodeId, bundle: CurveBundle): UIO[Unit]

  /**
    * Remove bundle from cache.
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

object CurveBundleCache {
  
  /**
    * Create live implementation with empty cache.
    *
    * @param treeIndex Tree index for ancestor lookup
    * @return ZLayer providing CurveBundleCache
    */
  def layer: ZLayer[TreeIndex, Nothing, CurveBundleCache] =
    ZLayer.fromZIO {
      for
        treeIndex <- ZIO.service[TreeIndex]
        cache     <- Ref.make(Map.empty[NodeId, CurveBundle])
      yield CurveBundleCacheLive(treeIndex, cache)
    }

  // Accessor methods for ZIO service pattern
  def get(nodeId: NodeId): URIO[CurveBundleCache, Option[CurveBundle]] =
    ZIO.serviceWithZIO[CurveBundleCache](_.get(nodeId))

  def set(nodeId: NodeId, bundle: CurveBundle): URIO[CurveBundleCache, Unit] =
    ZIO.serviceWithZIO[CurveBundleCache](_.set(nodeId, bundle))

  def remove(nodeId: NodeId): URIO[CurveBundleCache, Unit] =
    ZIO.serviceWithZIO[CurveBundleCache](_.remove(nodeId))

  def invalidate(nodeId: NodeId): URIO[CurveBundleCache, List[NodeId]] =
    ZIO.serviceWithZIO[CurveBundleCache](_.invalidate(nodeId))

  def clear: URIO[CurveBundleCache, Unit] =
    ZIO.serviceWithZIO[CurveBundleCache](_.clear)

  def clearAndGetSize: URIO[CurveBundleCache, Int] =
    ZIO.serviceWithZIO[CurveBundleCache](_.clearAndGetSize)

  def size: URIO[CurveBundleCache, Int] =
    ZIO.serviceWithZIO[CurveBundleCache](_.size)

  def contains(nodeId: NodeId): URIO[CurveBundleCache, Boolean] =
    ZIO.serviceWithZIO[CurveBundleCache](_.contains(nodeId))

  def keys: URIO[CurveBundleCache, List[NodeId]] =
    ZIO.serviceWithZIO[CurveBundleCache](_.keys)
}

/**
  * Live implementation of CurveBundleCache.
  *
  * @param treeIndex Tree structure for ancestor lookup
  * @param cacheRef Thread-safe cache storage
  */
final class CurveBundleCacheLive(
    treeIndex: TreeIndex,
    cacheRef: Ref[Map[NodeId, CurveBundle]]
) extends CurveBundleCache {

  override def get(nodeId: NodeId): UIO[Option[CurveBundle]] =
    cacheRef.get.map(_.get(nodeId))

  override def set(nodeId: NodeId, bundle: CurveBundle): UIO[Unit] =
    for
      _ <- cacheRef.update(_ + (nodeId -> bundle))
      _ <- ZIO.logDebug(s"CurveBundleCache SET: ${nodeId.value} (${bundle.size} curves, ${bundle.domain.size} ticks)")
    yield ()

  override def remove(nodeId: NodeId): UIO[Unit] =
    for
      _ <- cacheRef.update(_ - nodeId)
      _ <- ZIO.logDebug(s"CurveBundleCache REMOVE: ${nodeId.value}")
    yield ()

  override def invalidate(nodeId: NodeId): UIO[List[NodeId]] =
    for
      path <- ZIO.succeed(treeIndex.ancestorPath(nodeId))
      _    <- cacheRef.update(cache => cache -- path)
      _    <- ZIO.logInfo(s"CurveBundleCache invalidated: ${path.map(_.value).mkString(" → ")}")
    yield path

  override def clear: UIO[Unit] =
    for
      _ <- cacheRef.set(Map.empty)
      _ <- ZIO.logDebug("CurveBundleCache cleared")
    yield ()

  override def clearAndGetSize: UIO[Int] =
    for
      // Atomic read-and-clear: size returned matches exactly what was cleared
      size <- cacheRef.modify(m => (m.size, Map.empty))
      _    <- ZIO.logDebug(s"CurveBundleCache cleared $size entries (atomic)")
    yield size

  override def size: UIO[Int] =
    cacheRef.get.map(_.size)

  override def contains(nodeId: NodeId): UIO[Boolean] =
    cacheRef.get.map(_.contains(nodeId))

  override def keys: UIO[List[NodeId]] =
    cacheRef.get.map(_.keys.toList)
}

// Type alias for backward compatibility during migration
@deprecated("Use CurveBundleCache instead", "Phase C")
type LECCache = CurveBundleCache

@deprecated("Use CurveBundleCache instead", "Phase C")
val LECCache = CurveBundleCache
