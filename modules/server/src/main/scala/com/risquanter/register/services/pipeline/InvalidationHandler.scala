package com.risquanter.register.services.pipeline

import zio.*
import com.risquanter.register.services.cache.TreeCacheManager
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.data.iron.{TreeId, NodeId}

/**
  * Handles cache invalidation and SSE notification when nodes change.
  *
  * Per ADR-005-proposal: "When Irmin notifies of a change, invalidate from node to root"
  * Per ADR-004a-proposal: "SSE provides simple unidirectional streaming for server→client push"
  *
  * This service bridges the gap between data changes and client notifications:
  * 1. Looks up tree to get current TreeIndex
  * 2. Invalidates affected cache entries (node + ancestors) using TreeCacheManager
  * 3. Broadcasts CacheInvalidated event via SSE to all subscribers
  *
  * Currently triggered manually via API endpoint. Future: triggered by Irmin watch subscription.
  *
  * == Example ==
  * {{{
  * // User modifies "cyber-risk" node parameters
  * InvalidationHandler.handleNodeChange(treeId = 1, nodeId = "cyber-risk")
  * 
  * // Result:
  * // 1. Tree looked up to get current index
  * // 2. Cache entries cleared: cyber-risk, ops-risk, portfolio (ancestors)
  * // 3. SSE event sent: CacheInvalidated(List("cyber-risk", "ops-risk", "portfolio"), treeId = 1)
  * // 4. Connected browsers receive event (can refresh if needed)
  * }}}
  */
/**
  * Result of a cache invalidation operation.
  *
  * @param invalidatedNodes Node IDs whose cache entries were cleared (node + ancestors)
  * @param subscribersNotified Number of SSE subscribers who received the invalidation event
  */
final case class InvalidationResult(
    invalidatedNodes: List[NodeId],
    subscribersNotified: Int
)

trait InvalidationHandler {

  /**
    * Handle a node change by invalidating cache and notifying clients.
    *
    * @param treeId Tree ID for cache lookup and SSE routing
    * @param nodeId Changed node identifier
    * @return Invalidation result containing affected nodes and subscriber count
    */
  def handleNodeChange(treeId: TreeId, nodeId: NodeId): UIO[InvalidationResult]
}

object InvalidationHandler {

  /**
    * Create live InvalidationHandler layer.
    */
  val live: ZLayer[TreeCacheManager & RiskTreeService & SSEHub, Nothing, InvalidationHandler] =
    ZLayer.fromFunction(InvalidationHandlerLive(_, _, _))

  // Accessor methods for ZIO service pattern
  def handleNodeChange(treeId: TreeId, nodeId: NodeId): URIO[InvalidationHandler, InvalidationResult] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleNodeChange(treeId, nodeId))
}

/**
  * Live implementation of InvalidationHandler.
  */
final case class InvalidationHandlerLive(
    cacheManager: TreeCacheManager,
    treeService: RiskTreeService,
    hub: SSEHub
) extends InvalidationHandler {

  override def handleNodeChange(treeId: TreeId, nodeId: NodeId): UIO[InvalidationResult] =
    for {
      // Step 1: Look up tree to get current index
      treeOpt <- treeService.getById(treeId).catchAll(_ => ZIO.succeed(None))
      
      result <- treeOpt match {
        case None =>
          // Tree not found - log warning and return empty result
          ZIO.logWarning(s"InvalidationHandler: tree $treeId not found") *>
            ZIO.succeed(InvalidationResult(invalidatedNodes = Nil, subscribersNotified = 0))
          
        case Some(tree) =>
          for {
            // Step 2: Invalidate cache for node and ancestors using tree's index
            invalidated <- cacheManager.invalidate(tree, nodeId)
            
            // Step 3: Log the invalidation (ADR-002)
            _ <- ZIO.logInfo(s"Cache invalidated: treeId=$treeId, nodeId=${nodeId.value}, affected=${invalidated.map(_.value).mkString(", ")}")
            
            // Step 4: Broadcast SSE event to subscribers
            event = SSEEvent.CacheInvalidated(
              nodeIds = invalidated.map(_.value),
              treeId = treeId
            )
            subscriberCount <- hub.publish(treeId, event)
            
            // Step 5: Log notification (ADR-002)
            _ <- ZIO.logDebug(s"SSE notification sent: treeId=$treeId, subscribers=$subscriberCount")
          } yield InvalidationResult(invalidatedNodes = invalidated, subscribersNotified = subscriberCount)
      }
    } yield result
}
