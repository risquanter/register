package com.risquanter.register.http.cache

import zio.*
import sttp.tapir.server.ServerEndpoint
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.http.controllers.BaseController
import com.risquanter.register.services.cache.TreeCacheManager
import com.risquanter.register.domain.data.iron.TreeId

/**
  * Controller for cache management endpoints.
  *
  * Per ADR-004a-proposal: "Controllers wire endpoints to services"
  * This controller connects cache management endpoints to TreeCacheManager service.
  *
  * == Tree-Scoped Design ==
  *
  * Cache operations are scoped to individual trees via path parameter:
  * - GET  /risk-trees/{treeId}/cache/stats
  * - GET  /risk-trees/{treeId}/cache/nodes
  * - DELETE /risk-trees/{treeId}/cache
  *
  * Global clear:
  * - DELETE /cache/clear-all
  *
  * == Security Model (ADR-012 Compliant) ==
  *
  * These endpoints are '''admin-only'''. Authorization is enforced at the
  * service mesh layer (Istio + OPA), not in application code.
  *
  * - Development: Endpoints unrestricted (no mesh)
  * - Production: Mesh enforces `admin` role requirement
  */
class CacheController private (cacheManager: TreeCacheManager)
    extends BaseController
    with CacheEndpoints {

  /**
    * Get cache statistics for a specific tree.
    */
  val getStats: ServerEndpoint[Any, Task] =
    cacheStatsEndpoint.serverLogicSuccess { treeId =>
      for
        cache <- cacheManager.cacheFor(treeId)
        size  <- cache.size
      yield CacheStatsResponse(
        treeId = treeId,
        size = size,
        capacityNote = "Unbounded cache (entries persist until invalidation)"
      )
    }

  /**
    * List all cached node IDs for a specific tree.
    */
  val getNodes: ServerEndpoint[Any, Task] =
    cacheNodesEndpoint.serverLogicSuccess { treeId =>
      for
        cache <- cacheManager.cacheFor(treeId)
        ids   <- cache.keys
      yield CacheNodesResponse(
        treeId = treeId,
        nodeIds = ids.map(_.value),
        count = ids.size
      )
    }

  /**
    * Clear cache for a specific tree.
    */
  val clearCache: ServerEndpoint[Any, Task] =
    cacheClearEndpoint.serverLogicSuccess { treeId =>
      for
        size <- cacheManager.onTreeStructureChanged(treeId)
      yield CacheClearResponse(
        treeId = treeId,
        cleared = size,
        message = s"Cleared $size cache entries for tree $treeId"
      )
    }

  /**
    * Clear all caches globally.
    */
  val clearAllCaches: ServerEndpoint[Any, Task] =
    cacheClearAllEndpoint.serverLogicSuccess { _ =>
      for
        (treesCleared, entriesCleared) <- cacheManager.clearAll
      yield CacheClearAllResponse(
        treesCleared = treesCleared,
        totalEntriesCleared = entriesCleared,
        message = s"Cleared $entriesCleared entries across $treesCleared trees"
      )
    }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(getStats, getNodes, clearCache, clearAllCaches)
}

object CacheController {

  /**
    * Create CacheController with TreeCacheManager dependency.
    */
  val layer: ZLayer[TreeCacheManager, Nothing, CacheController] =
    ZLayer.fromZIO {
      for
        cacheManager <- ZIO.service[TreeCacheManager]
      yield new CacheController(cacheManager)
    }

  /**
    * Create CacheController directly from TreeCacheManager.
    */
  def make(cacheManager: TreeCacheManager): CacheController = new CacheController(cacheManager)
}
