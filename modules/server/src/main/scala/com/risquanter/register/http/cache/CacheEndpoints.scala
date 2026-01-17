package com.risquanter.register.http.cache

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import zio.json.*

import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.domain.data.LECCurveData

/**
  * Cache management endpoint definitions.
  *
  * == Security Model (ADR-012 Compliant) ==
  *
  * These endpoints are '''admin-only''' and must be protected via service mesh.
  *
  * '''Istio AuthorizationPolicy example:'''
  * {{{
  * apiVersion: security.istio.io/v1
  * kind: AuthorizationPolicy
  * metadata:
  *   name: cache-admin-only
  * spec:
  *   selector:
  *     matchLabels:
  *       app: risk-register
  *   rules:
  *   - to:
  *     - operation:
  *         paths: ["/cache", "/cache/..."]
  *     when:
  *     - key: request.auth.claims[roles]
  *       values: ["admin", "cache-admin"]
  * }}}
  *
  * '''OPA ext_authz (recommended):'''
  * {{{
  * allow {
  *   startswith(input.path, "/cache")
  *   "admin" in input.claims.roles
  * }
  * }}}
  *
  * No application-level auth code is implemented—this follows ADR-012's
  * "mesh handles auth" principle. In development (no mesh), these endpoints
  * are unrestricted; production requires mesh policy enforcement.
  */
trait CacheEndpoints extends BaseEndpoint {

  /**
    * Get cache statistics.
    *
    * GET /cache/stats
    *
    * Returns current cache size and other metrics.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheStatsEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheStats")
      .summary("Get LEC cache statistics")
      .description("Returns cache size and metadata. Admin-only endpoint.")
      .in("cache" / "stats")
      .get
      .out(jsonBody[CacheStatsResponse])

  /**
    * List all cached node IDs.
    *
    * GET /cache/nodes
    *
    * Returns list of node IDs currently in cache.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheNodesEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheNodes")
      .summary("List cached node IDs")
      .description("Returns all node IDs currently in the LEC cache. Admin-only endpoint.")
      .in("cache" / "nodes")
      .get
      .out(jsonBody[CacheNodesResponse])

  /**
    * Get cached LEC data for a specific node.
    *
    * GET /cache/node/{nodeId}
    *
    * Returns full LECCurveData if cached, 404 otherwise.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheNodeEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheNode")
      .summary("Get cached LEC data for a node")
      .description("Returns cached LECCurveData for a specific node. 404 if not cached. Admin-only endpoint.")
      .in("cache" / "node" / path[SafeId.SafeId]("nodeId"))
      .get
      .out(jsonBody[Option[LECCurveData]])

  /**
    * Clear entire cache.
    *
    * DELETE /cache
    *
    * Removes all entries from the LEC cache.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheClearEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheClear")
      .summary("Clear entire LEC cache")
      .description("Removes all entries from the cache. Admin-only endpoint.")
      .in("cache")
      .delete
      .out(jsonBody[CacheClearResponse])

  /**
    * Invalidate cache for a specific node.
    *
    * DELETE /cache/node/{nodeId}
    *
    * Removes cache entry for the node and all ancestors (per ADR-005).
    * Admin-only: Protected by service mesh policy.
    */
  val cacheInvalidateNodeEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheInvalidateNode")
      .summary("Invalidate cache for a node and ancestors")
      .description("Removes cache entries for the node and all ancestors in the tree. Admin-only endpoint.")
      .in("cache" / "node" / path[SafeId.SafeId]("nodeId"))
      .delete
      .out(jsonBody[CacheInvalidateResponse])
}

/**
  * Cache statistics response.
  *
  * @param size Number of entries in cache
  * @param capacityNote Note about cache capacity (unbounded in current impl)
  */
final case class CacheStatsResponse(
  size: Int,
  capacityNote: String
)

object CacheStatsResponse {
  given codec: JsonCodec[CacheStatsResponse] = DeriveJsonCodec.gen[CacheStatsResponse]
}

/**
  * Cached node IDs response.
  *
  * @param nodeIds List of node IDs currently cached
  * @param count Total count
  */
final case class CacheNodesResponse(
  nodeIds: List[String],
  count: Int
)

object CacheNodesResponse {
  given codec: JsonCodec[CacheNodesResponse] = DeriveJsonCodec.gen[CacheNodesResponse]
}

/**
  * Cache clear response.
  *
  * @param cleared Number of entries cleared
  * @param message Confirmation message
  */
final case class CacheClearResponse(
  cleared: Int,
  message: String
)

object CacheClearResponse {
  given codec: JsonCodec[CacheClearResponse] = DeriveJsonCodec.gen[CacheClearResponse]
}

/**
  * Cache invalidation response.
  *
  * @param invalidatedNodeIds Node IDs that were invalidated (node + ancestors)
  * @param count Number of invalidated entries
  */
final case class CacheInvalidateResponse(
  invalidatedNodeIds: List[String],
  count: Int
)

object CacheInvalidateResponse {
  given codec: JsonCodec[CacheInvalidateResponse] = DeriveJsonCodec.gen[CacheInvalidateResponse]
}
