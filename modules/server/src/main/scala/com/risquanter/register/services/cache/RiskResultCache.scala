package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.RiskResult
import com.risquanter.register.domain.data.iron.NodeId

/**
  * RiskResult cache service (ADR-014).
  *
  * Pure storage for simulation outcomes (RiskResult) by node ID.
  * This is a simple cache with no tree-awareness — invalidation logic
  * is handled by TreeCacheManager which uses TreeIndex.
  *
  * LEC curves are computed at render time from cached outcomes using
  * LECGenerator.generateCurvePoints or generateCurvePointsMulti.
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
  * == Cache Invalidation ==
  *
  * Invalidation (walking ancestor paths) is handled by TreeCacheManager,
  * which calls removeAll() with the computed path. This cache is tree-agnostic.
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
    * Remove multiple entries from cache.
    *
    * Used by TreeCacheManager for ancestor-path invalidation.
    *
    * @param nodeIds Node identifiers to remove
    * @return Number of entries actually removed
    */
  def removeAll(nodeIds: List[NodeId]): UIO[Int]

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
    * Create a new RiskResultCache instance with empty cache.
    *
    * This is NOT a ZLayer - caches are created per-tree by TreeCacheManager.
    *
    * @return Effect producing a new RiskResultCache
    */
  def make: UIO[RiskResultCache] =
    Ref.make(Map.empty[NodeId, RiskResult]).map(RiskResultCacheLive(_))

  // Accessor methods removed - use TreeCacheManager.cacheFor(treeId) instead
}

/**
  * Live implementation of RiskResultCache.
  *
  * Pure storage with no tree-awareness.
  *
  * @param cacheRef Thread-safe cache storage
  */
final class RiskResultCacheLive(
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

  override def removeAll(nodeIds: List[NodeId]): UIO[Int] =
    cacheRef.modify { cache =>
      val toRemove = nodeIds.toSet
      val removed = cache.keys.count(toRemove.contains)
      (removed, cache -- toRemove)
    }

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
