package com.risquanter.register.http.cache

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.http.controllers.BaseController
import com.risquanter.register.services.cache.RiskResultCache
import com.risquanter.register.domain.tree.NodeId

/**
  * Controller for cache management endpoints.
  *
  * Per ADR-004a-proposal: "Controllers wire endpoints to services"
  * This controller connects cache management endpoints to RiskResultCache service.
  *
  * == Security Model (ADR-012 Compliant) ==
  *
  * These endpoints are '''admin-only'''. Authorization is enforced at the
  * service mesh layer (Istio + OPA), not in application code.
  *
  * - Development: Endpoints unrestricted (no mesh)
  * - Production: Mesh enforces `admin` role requirement via:
  *   - Istio AuthorizationPolicy on `/cache/` paths
  *   - OPA ext_authz checking JWT claims
  *
  * See `CacheEndpoints.scala` for policy examples.
  *
  * == Cache Operations ==
  *
  * - GET /cache/stats - Cache size and metadata
  * - GET /cache/nodes - List of cached node IDs
  * - DELETE /cache - Clear entire cache
  * - DELETE /cache/node/nodeId - Invalidate node + ancestors
  */
class CacheController private (cache: RiskResultCache)
    extends BaseController
    with CacheEndpoints {

  /**
    * Get cache statistics.
    */
  val getStats: ServerEndpoint[Any, Task] =
    cacheStatsEndpoint.serverLogicSuccess { _ =>
      for
        size <- cache.size
      yield CacheStatsResponse(
        size = size,
        capacityNote = "Unbounded cache (entries persist until invalidation)"
      )
    }

  /**
    * List all cached node IDs.
    */
  val getNodes: ServerEndpoint[Any, Task] =
    cacheNodesEndpoint.serverLogicSuccess { _ =>
      for
        ids <- cache.keys
      yield CacheNodesResponse(
        nodeIds = ids.map(_.value),
        count = ids.size
      )
    }

  /**
    * Clear entire cache.
    *
    * Uses atomic clearAndGetSize to ensure the reported count exactly
    * matches the number of entries removed (no race window).
    */
  val clearCache: ServerEndpoint[Any, Task] =
    cacheClearEndpoint.serverLogicSuccess { _ =>
      for
        size <- cache.clearAndGetSize
      yield CacheClearResponse(
        cleared = size,
        message = s"Cleared $size cache entries"
      )
    }

  /**
    * Invalidate cache for a specific node and ancestors.
    */
  val invalidateNode: ServerEndpoint[Any, Task] =
    cacheInvalidateNodeEndpoint.serverLogicSuccess { nodeId =>
      for
        invalidated <- cache.invalidate(nodeId)
      yield CacheInvalidateResponse(
        invalidatedNodeIds = invalidated.map(_.value),
        count = invalidated.size
      )
    }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(getStats, getNodes, clearCache, invalidateNode)
}

object CacheController {

  /**
    * Create CacheController with RiskResultCache dependency.
    */
  val layer: ZLayer[RiskResultCache, Nothing, CacheController] =
    ZLayer.fromZIO {
      for
        cache <- ZIO.service[RiskResultCache]
      yield new CacheController(cache)
    }

  /**
    * Create CacheController directly from RiskResultCache.
    */
  def make(cache: RiskResultCache): CacheController = new CacheController(cache)
}
