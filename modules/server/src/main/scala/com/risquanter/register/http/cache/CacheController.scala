package com.risquanter.register.http.cache

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.http.controllers.BaseController
import com.risquanter.register.services.cache.LECCache
import com.risquanter.register.domain.tree.NodeId

/**
  * Controller for cache management endpoints.
  *
  * Per ADR-004a-proposal: "Controllers wire endpoints to services"
  * This controller connects cache management endpoints to LECCache service.
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
  * - GET /cache/node/nodeId - Full LECCurveData for a node
  * - DELETE /cache - Clear entire cache
  * - DELETE /cache/node/nodeId - Invalidate node + ancestors
  */
class CacheController private (lecCache: LECCache)
    extends BaseController
    with CacheEndpoints {

  /**
    * Get cache statistics.
    */
  val getStats: ServerEndpoint[Any, Task] =
    cacheStatsEndpoint.serverLogicSuccess { _ =>
      for
        size <- lecCache.size
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
        ids <- lecCache.keys
      yield CacheNodesResponse(
        nodeIds = ids.map(_.value),
        count = ids.size
      )
    }

  /**
    * Get cached LEC data for a specific node.
    */
  val getNode: ServerEndpoint[Any, Task] =
    cacheNodeEndpoint.serverLogicSuccess { nodeId =>
      lecCache.get(nodeId)
    }

  /**
    * Clear entire cache.
    */
  val clearCache: ServerEndpoint[Any, Task] =
    cacheClearEndpoint.serverLogicSuccess { _ =>
      for
        size <- lecCache.size
        _    <- lecCache.clear
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
        invalidated <- lecCache.invalidate(nodeId)
      yield CacheInvalidateResponse(
        invalidatedNodeIds = invalidated.map(_.value),
        count = invalidated.size
      )
    }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(getStats, getNodes, getNode, clearCache, invalidateNode)
}

object CacheController {

  /**
    * Create CacheController with LECCache dependency.
    */
  val layer: ZLayer[LECCache, Nothing, CacheController] =
    ZLayer.fromZIO {
      for
        cache <- ZIO.service[LECCache]
      yield new CacheController(cache)
    }

  /**
    * Create CacheController directly from LECCache.
    */
  def make(cache: LECCache): CacheController = new CacheController(cache)
}
