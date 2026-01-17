package com.risquanter.register.services.sse

import zio.*
import zio.stream.*
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.data.iron.NonNegativeLong

/**
  * SSE Hub for managing real-time event streams to browser clients.
  *
  * Per ADR-004a-proposal: "SSE provides simple unidirectional streaming 
  * for server→client push"
  *
  * Uses ZIO Hub for fan-out broadcasting: one event published reaches
  * all subscribers for that tree.
  *
  * == Thread Safety ==
  * All operations are thread-safe via ZIO concurrent primitives.
  *
  * == Lifecycle ==
  * - Clients subscribe via `subscribe(treeId)` → returns ZStream
  * - Server broadcasts via `publish(treeId, event)`
  * - Stream terminates when client disconnects (ZStream finalizer)
  *
  * == Example ==
  * {{{
  * // Server-side: broadcast LEC update
  * SSEHub.publish(treeId, SSEEvent.LECUpdated(nodeId, treeId, quantiles))
  *
  * // Client subscription (via SSE endpoint)
  * SSEHub.subscribe(treeId).map(event => ServerSentEvent(event.toJson))
  * }}}
  */
trait SSEHub {

  /**
    * Subscribe to events for a specific tree.
    *
    * @param treeId Tree to subscribe to
    * @return Stream of SSE events (terminates on client disconnect)
    */
  def subscribe(treeId: NonNegativeLong): UIO[ZStream[Any, Nothing, SSEEvent]]

  /**
    * Publish an event to all subscribers of a tree.
    *
    * @param treeId Tree to broadcast to
    * @param event Event to publish
    * @return Number of subscribers that received the event
    */
  def publish(treeId: NonNegativeLong, event: SSEEvent): UIO[Int]

  /**
    * Get current subscriber count for a tree.
    *
    * @param treeId Tree to check
    * @return Number of active subscribers
    */
  def subscriberCount(treeId: NonNegativeLong): UIO[Int]

  /**
    * Broadcast event to ALL trees (e.g., system-wide notifications).
    *
    * @param event Event to broadcast
    * @return Total number of subscribers reached
    */
  def broadcastAll(event: SSEEvent): UIO[Int]
}

object SSEHub {

  /**
    * Create live SSE Hub with configurable capacity.
    *
    * @param capacity Buffer capacity per tree hub (default 16)
    */
  def layer(capacity: Int = 16): ZLayer[Any, Nothing, SSEHub] =
    ZLayer.fromZIO {
      for
        hubs        <- Ref.make(Map.empty[NonNegativeLong, Hub[SSEEvent]])
        subscribers <- Ref.make(Map.empty[NonNegativeLong, Int])
      yield SSEHubLive(hubs, subscribers, capacity)
    }

  // Default layer with standard capacity
  val live: ZLayer[Any, Nothing, SSEHub] = layer()

  // Accessor methods for ZIO service pattern
  def subscribe(treeId: NonNegativeLong): URIO[SSEHub, ZStream[Any, Nothing, SSEEvent]] =
    ZIO.serviceWithZIO[SSEHub](_.subscribe(treeId))

  def publish(treeId: NonNegativeLong, event: SSEEvent): URIO[SSEHub, Int] =
    ZIO.serviceWithZIO[SSEHub](_.publish(treeId, event))

  def subscriberCount(treeId: NonNegativeLong): URIO[SSEHub, Int] =
    ZIO.serviceWithZIO[SSEHub](_.subscriberCount(treeId))

  def broadcastAll(event: SSEEvent): URIO[SSEHub, Int] =
    ZIO.serviceWithZIO[SSEHub](_.broadcastAll(event))
}

/**
  * Live implementation of SSEHub using ZIO Hub for fan-out.
  *
  * @param hubsRef Map of tree ID → Hub (created lazily on first subscribe)
  * @param subscribersRef Map of tree ID → subscriber count
  * @param capacity Buffer capacity for each hub
  */
final class SSEHubLive(
    hubsRef: Ref[Map[NonNegativeLong, Hub[SSEEvent]]],
    subscribersRef: Ref[Map[NonNegativeLong, Int]],
    capacity: Int
) extends SSEHub {

  override def subscribe(treeId: NonNegativeLong): UIO[ZStream[Any, Nothing, SSEEvent]] =
    for
      hub <- getOrCreateHub(treeId)
      _   <- subscribersRef.update(subs => subs + (treeId -> (subs.getOrElse(treeId, 0) + 1)))
      _   <- ZIO.logInfo(s"SSE stream created for tree $treeId")
      stream = ZStream.fromHub(hub).ensuring(
                 subscribersRef.update(subs => 
                   subs.get(treeId).map(c => subs + (treeId -> (c - 1).max(0))).getOrElse(subs)
                 ) *> ZIO.logInfo(s"SSE stream ended for tree $treeId")
               )
    yield stream

  override def publish(treeId: NonNegativeLong, event: SSEEvent): UIO[Int] =
    for
      hubs  <- hubsRef.get
      subs  <- subscribersRef.get
      count  = subs.getOrElse(treeId, 0)
      _     <- hubs.get(treeId) match {
                 case Some(hub) if count > 0 =>
                   hub.publish(event) *> 
                   ZIO.logDebug(s"SSE published ${event.eventType} to tree $treeId ($count subscribers)")
                 case _ =>
                   ZIO.logDebug(s"SSE publish to tree $treeId: no subscribers")
               }
    yield count

  override def subscriberCount(treeId: NonNegativeLong): UIO[Int] =
    subscribersRef.get.map(_.getOrElse(treeId, 0))

  override def broadcastAll(event: SSEEvent): UIO[Int] =
    for
      hubs  <- hubsRef.get
      subs  <- subscribersRef.get
      total  = subs.values.sum
      _     <- ZIO.foreachParDiscard(hubs.toList) { case (treeId, hub) =>
                 if subs.getOrElse(treeId, 0) > 0 then hub.publish(event)
                 else ZIO.unit
               }
      _     <- ZIO.logInfo(s"SSE broadcast ${event.eventType} to all trees ($total total subscribers)")
    yield total

  /**
    * Get existing hub or create new one for tree.
    */
  private def getOrCreateHub(treeId: NonNegativeLong): UIO[Hub[SSEEvent]] =
    hubsRef.get.flatMap { hubs =>
      hubs.get(treeId) match {
        case Some(hub) => ZIO.succeed(hub)
        case None =>
          for
            hub <- Hub.sliding[SSEEvent](capacity)
            _   <- hubsRef.update(_ + (treeId -> hub))
            _   <- ZIO.logDebug(s"SSE hub created for tree $treeId")
          yield hub
      }
    }
}
