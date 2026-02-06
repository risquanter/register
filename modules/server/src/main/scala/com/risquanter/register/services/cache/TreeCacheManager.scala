package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{RiskResult, RiskTree}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId}

/**
  * Manages per-tree RiskResultCache instances.
  *
  * Provides tree-scoped cache isolation:
  * - Each tree has its own cache (no key collisions across trees)
  * - Invalidation uses tree's current TreeIndex (always up-to-date)
  * - Lifecycle tied to tree existence
  *
  * == Cache Invalidation Semantics ==
  *
  * When a leaf node changes, its ancestors' cached results become stale
  * because aggregate distributions are computed bottom-up. The invalidate
  * method uses the tree's TreeIndex to walk up the ancestor path.
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
  * Example: Hardware parameters change:
  * {{{
  * for
  *   // Invalidate hardware → clears hardware, ops-risk, portfolio
  *   invalidated <- TreeCacheManager.invalidate(tree, hardwareId)
  *   // invalidated = List(portfolio, ops-risk, hardware)  (root to leaf)
  *   
  *   // market-risk cache is PRESERVED (not an ancestor of hardware)
  * yield invalidated
  * }}}
  *
  * This enables O(depth) invalidation instead of O(n) full cache clear.
  */
trait TreeCacheManager {

  /**
    * Get or create the cache for a specific tree.
    *
    * Creates an empty cache on first access for a tree.
    *
    * @param treeId Tree identifier
    * @return RiskResultCache for this tree
    */
  def cacheFor(treeId: TreeId): UIO[RiskResultCache]

  /**
    * Invalidate cache for a node and all its ancestors.
    *
    * Uses tree.index (the current TreeIndex) to walk up the ancestor path.
    * This ensures invalidation always uses the latest tree structure.
    *
    * @param tree Risk tree containing the node
    * @param nodeId Changed node identifier
    * @return List of invalidated node IDs (top-down: root to nodeId)
    */
  def invalidate(tree: RiskTree, nodeId: NodeId): UIO[List[NodeId]]

  /**
    * Clear all cache entries for a tree.
    *
    * Called when tree structure changes (node add/remove/move).
    * Conservative approach: clears entire cache rather than
    * attempting partial invalidation with potentially stale index.
    *
    * @param treeId Tree identifier
    * @return Number of entries cleared
    */
  def onTreeStructureChanged(treeId: TreeId): UIO[Int]

  /**
    * Delete cache for a tree.
    *
    * Called when tree is deleted. Removes cache from memory.
    *
    * @param treeId Tree identifier
    */
  def deleteTree(treeId: TreeId): UIO[Unit]

  /**
    * Get number of cached trees.
    *
    * For monitoring/debugging.
    */
  def treeCount: UIO[Int]

  /**
    * Clear all caches for all trees.
    *
    * @return Tuple of (number of trees cleared, total entries cleared)
    */
  def clearAll: UIO[(Int, Int)]
}

object TreeCacheManager {

  /**
    * Create live implementation layer.
    */
  val layer: ZLayer[Any, Nothing, TreeCacheManager] =
    ZLayer.fromZIO {
      Ref.make(Map.empty[TreeId, RiskResultCache])
        .map(TreeCacheManagerLive(_))
    }

  // Accessor methods for ZIO service pattern
  def cacheFor(treeId: TreeId): URIO[TreeCacheManager, RiskResultCache] =
    ZIO.serviceWithZIO[TreeCacheManager](_.cacheFor(treeId))

  def invalidate(tree: RiskTree, nodeId: NodeId): URIO[TreeCacheManager, List[NodeId]] =
    ZIO.serviceWithZIO[TreeCacheManager](_.invalidate(tree, nodeId))

  def onTreeStructureChanged(treeId: TreeId): URIO[TreeCacheManager, Int] =
    ZIO.serviceWithZIO[TreeCacheManager](_.onTreeStructureChanged(treeId))

  def deleteTree(treeId: TreeId): URIO[TreeCacheManager, Unit] =
    ZIO.serviceWithZIO[TreeCacheManager](_.deleteTree(treeId))

  def treeCount: URIO[TreeCacheManager, Int] =
    ZIO.serviceWithZIO[TreeCacheManager](_.treeCount)

  def clearAll: URIO[TreeCacheManager, (Int, Int)] =
    ZIO.serviceWithZIO[TreeCacheManager](_.clearAll)
}

/**
  * Live implementation of TreeCacheManager.
  *
  * @param caches Map from tree ID to its RiskResultCache
  */
final case class TreeCacheManagerLive(
    caches: Ref[Map[TreeId, RiskResultCache]]
) extends TreeCacheManager {

  override def cacheFor(treeId: TreeId): UIO[RiskResultCache] =
    caches.get.flatMap(_.get(treeId) match {
      case Some(cache) => ZIO.succeed(cache)
      case None =>
        for
          cache <- RiskResultCache.make
          _     <- caches.update(_ + (treeId -> cache))
          _     <- ZIO.logDebug(s"TreeCacheManager: created cache for tree $treeId")
        yield cache
    })

  override def invalidate(tree: RiskTree, nodeId: NodeId): UIO[List[NodeId]] =
    for
      cache <- cacheFor(tree.id)
      path   = tree.index.ancestorPath(nodeId)
      _     <- cache.removeAll(path)
      _     <- ZIO.logInfo(s"TreeCacheManager invalidated tree=${tree.id}: ${path.map(_.value).mkString(" → ")}")
    yield path

  override def onTreeStructureChanged(treeId: TreeId): UIO[Int] =
    for
      cache   <- cacheFor(treeId)
      cleared <- cache.clearAndGetSize
      _       <- ZIO.logInfo(s"TreeCacheManager: tree $treeId structure changed, cleared $cleared entries")
    yield cleared

  override def deleteTree(treeId: TreeId): UIO[Unit] =
    for
      _ <- caches.update(_ - treeId)
      _ <- ZIO.logDebug(s"TreeCacheManager: deleted cache for tree $treeId")
    yield ()

  override def treeCount: UIO[Int] =
    caches.get.map(_.size)

  override def clearAll: UIO[(Int, Int)] =
    for
      currentCaches <- caches.get
      treeCount      = currentCaches.size
      entriesCleared <- ZIO.foldLeft(currentCaches.values.toList)(0) { (total, cache) =>
                          cache.clearAndGetSize.map(_ + total)
                        }
      _             <- ZIO.logInfo(s"TreeCacheManager: cleared all caches ($treeCount trees, $entriesCleared entries)")
    yield (treeCount, entriesCleared)
}
