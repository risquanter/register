package com.risquanter.register.http.cache

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import zio.json.*

import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.iron.NonNegativeLong

/**
  * Cache management endpoint definitions.
  *
  * == Tree-Scoped Endpoints ==
  *
  * Cache operations are scoped to individual trees:
  * - GET  /risk-trees/{treeId}/cache/stats  - Stats for tree's cache
  * - GET  /risk-trees/{treeId}/cache/nodes  - Cached node IDs for tree
  * - DELETE /risk-trees/{treeId}/cache      - Clear tree's cache
  *
  * Global operation:
  * - DELETE /cache/clear-all                - Clear all caches
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
  *         paths: ["/risk-trees/{treeId}/cache/...", "/cache/..."]
  *     when:
  *     - key: request.auth.claims[roles]
  *       values: ["admin", "cache-admin"]
  * }}}
  *
  * No application-level auth code is implemented—this follows ADR-012's
  * "mesh handles auth" principle. In development (no mesh), these endpoints
  * are unrestricted; production requires mesh policy enforcement.
  */
trait CacheEndpoints extends BaseEndpoint {

  /**
    * Get cache statistics for a specific tree.
    *
    * GET /risk-trees/{treeId}/cache/stats
    *
    * Returns current cache size for the specified tree.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheStatsEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheStats")
      .summary("Get LEC cache statistics for a tree")
      .description("Returns cache size and metadata for the specified tree. Admin-only endpoint.")
      .in("risk-trees" / path[NonNegativeLong]("treeId") / "cache" / "stats")
      .get
      .out(jsonBody[CacheStatsResponse])

  /**
    * List all cached node IDs for a specific tree.
    *
    * GET /risk-trees/{treeId}/cache/nodes
    *
    * Returns list of node IDs currently in cache for the specified tree.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheNodesEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheNodes")
      .summary("List cached node IDs for a tree")
      .description("Returns all node IDs currently cached for the specified tree. Admin-only endpoint.")
      .in("risk-trees" / path[NonNegativeLong]("treeId") / "cache" / "nodes")
      .get
      .out(jsonBody[CacheNodesResponse])

  /**
    * Clear cache for a specific tree.
    *
    * DELETE /risk-trees/{treeId}/cache
    *
    * Removes all entries from the cache for the specified tree.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheClearEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheClear")
      .summary("Clear LEC cache for a tree")
      .description("Removes all cache entries for the specified tree. Admin-only endpoint.")
      .in("risk-trees" / path[NonNegativeLong]("treeId") / "cache")
      .delete
      .out(jsonBody[CacheClearResponse])

  /**
    * Clear all caches globally.
    *
    * DELETE /cache/clear-all
    *
    * Removes all entries from all tree caches.
    * Admin-only: Protected by service mesh policy.
    */
  val cacheClearAllEndpoint =
    baseEndpoint
      .tag("cache-admin")
      .name("cacheClearAll")
      .summary("Clear all LEC caches")
      .description("Removes all entries from all tree caches. Admin-only endpoint.")
      .in("cache" / "clear-all")
      .delete
      .out(jsonBody[CacheClearAllResponse])
}

/**
  * Cache statistics response.
  *
  * @param treeId Tree identifier
  * @param size Number of entries in cache
  * @param capacityNote Note about cache capacity (unbounded in current impl)
  */
final case class CacheStatsResponse(
  treeId: Long,
  size: Int,
  capacityNote: String
)

object CacheStatsResponse {
  given codec: JsonCodec[CacheStatsResponse] = DeriveJsonCodec.gen[CacheStatsResponse]
}

/**
  * Cached node IDs response.
  *
  * @param treeId Tree identifier
  * @param nodeIds List of node IDs currently cached
  * @param count Total count
  */
final case class CacheNodesResponse(
  treeId: Long,
  nodeIds: List[String],
  count: Int
)

object CacheNodesResponse {
  given codec: JsonCodec[CacheNodesResponse] = DeriveJsonCodec.gen[CacheNodesResponse]
}

/**
  * Cache clear response.
  *
  * @param treeId Tree identifier
  * @param cleared Number of entries cleared
  * @param message Confirmation message
  */
final case class CacheClearResponse(
  treeId: Long,
  cleared: Int,
  message: String
)

object CacheClearResponse {
  given codec: JsonCodec[CacheClearResponse] = DeriveJsonCodec.gen[CacheClearResponse]
}

/**
  * Global cache clear response.
  *
  * @param treesCleared Number of tree caches cleared
  * @param totalEntriesCleared Total number of entries cleared across all trees
  * @param message Confirmation message
  */
final case class CacheClearAllResponse(
  treesCleared: Int,
  totalEntriesCleared: Int,
  message: String
)

object CacheClearAllResponse {
  given codec: JsonCodec[CacheClearAllResponse] = DeriveJsonCodec.gen[CacheClearAllResponse]
}
