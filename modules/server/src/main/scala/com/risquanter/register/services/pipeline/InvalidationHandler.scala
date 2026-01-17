package com.risquanter.register.services.pipeline

import zio.*
import com.risquanter.register.services.cache.LECCache
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.tree.NodeId
import com.risquanter.register.domain.data.iron.NonNegativeLong

/**
  * Handles cache invalidation and SSE notification when nodes change.
  *
  * Per ADR-005-proposal: "When Irmin notifies of a change, invalidate from node to root"
  * Per ADR-004a-proposal: "SSE provides simple unidirectional streaming for server→client push"
  *
  * This service bridges the gap between data changes and client notifications:
  * 1. Invalidates affected cache entries (node + ancestors)
  * 2. Broadcasts CacheInvalidated event via SSE to all subscribers
  *
  * Currently triggered manually via API endpoint. Future: triggered by Irmin watch subscription.
  *
  * == Example ==
  * {{{
  * // User modifies "cyber-risk" node parameters
  * InvalidationHandler.handleNodeChange(treeId = 1, nodeId = "cyber-risk")
  * 
  * // Result:
  * // 1. LECCache entries cleared: cyber-risk, ops-risk, portfolio (ancestors)
  * // 2. SSE event sent: CacheInvalidated(List("cyber-risk", "ops-risk", "portfolio"), treeId = 1)
  * // 3. Connected browsers receive event (can refresh if needed)
  * }}}
  */
trait InvalidationHandler {

  /**
    * Handle a node change by invalidating cache and notifying clients.
    *
    * @param treeId Tree ID for SSE routing
    * @param nodeId Changed node identifier
    * @return Number of SSE subscribers notified
    */
  def handleNodeChange(treeId: NonNegativeLong, nodeId: NodeId): UIO[Int]
}

object InvalidationHandler {

  /**
    * Create live InvalidationHandler layer.
    */
  val live: ZLayer[LECCache & SSEHub, Nothing, InvalidationHandler] =
    ZLayer.fromFunction(InvalidationHandlerLive(_, _))

  // Accessor methods for ZIO service pattern
  def handleNodeChange(treeId: NonNegativeLong, nodeId: NodeId): URIO[InvalidationHandler, Int] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleNodeChange(treeId, nodeId))
}

/**
  * Live implementation of InvalidationHandler.
  */
final case class InvalidationHandlerLive(
    lecCache: LECCache,
    sseHub: SSEHub
) extends InvalidationHandler {

  override def handleNodeChange(treeId: NonNegativeLong, nodeId: NodeId): UIO[Int] =
    for {
      // Step 1: Invalidate cache for node and ancestors
      invalidated <- lecCache.invalidate(nodeId)
      
      // Step 2: Log the invalidation (ADR-002)
      _ <- ZIO.logInfo(s"Cache invalidated: treeId=$treeId, nodeId=${nodeId.value}, affected=${invalidated.map(_.value).mkString(", ")}")
      
      // Step 3: Broadcast SSE event to subscribers
      event = SSEEvent.CacheInvalidated(
        nodeIds = invalidated.map(_.value),
        treeId = treeId: Long  // Extract Long from NonNegativeLong
      )
      subscriberCount <- sseHub.publish(treeId, event)
      
      // Step 4: Log notification (ADR-002)
      _ <- ZIO.logDebug(s"SSE notification sent: treeId=$treeId, subscribers=$subscriberCount")
    } yield subscriberCount
}
