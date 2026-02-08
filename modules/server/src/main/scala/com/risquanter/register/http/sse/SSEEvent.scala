package com.risquanter.register.http.sse

import zio.json.*
import com.risquanter.register.domain.data.iron.{TreeId, NodeId}

/**
  * Server-Sent Events for real-time updates to browser clients.
  *
  * Per ADR-004a-proposal: "SSE provides simple unidirectional streaming 
  * for server→client push"
  *
  * Event types:
  * - LECUpdated: LEC curve recomputed for a node
  * - NodeChanged: Tree structure modified (add/update/remove)
  * - CacheInvalidated: Cache entries cleared (debugging/monitoring)
  * - ConnectionStatus: Client connection lifecycle events
  */
sealed trait SSEEvent {
  def eventType: String
}

object SSEEvent {

  /**
    * LEC curve has been recomputed for a node.
    *
    * @param nodeId Affected node (SafeId.SafeId)
    * @param treeId Tree containing the node
    * @param quantiles Summary quantiles (p50, p95, p99, etc.)
    */
  final case class LECUpdated(
      nodeId: String,
      treeId: TreeId,
      quantiles: Map[String, Double]
  ) extends SSEEvent {
    override def eventType: String = "lec_updated"
  }

  /**
    * Tree structure has changed.
    *
    * @param nodeId Affected node
    * @param treeId Tree containing the node
    * @param changeType Type of change: "added", "updated", "removed"
    */
  final case class NodeChanged(
      nodeId: String,
      treeId: TreeId,
      changeType: String
  ) extends SSEEvent {
    override def eventType: String = "node_changed"
  }

  /**
    * Cache entries have been invalidated.
    *
    * @param nodeIds List of invalidated node IDs (root to leaf)
    * @param treeId Tree containing the nodes
    */
  final case class CacheInvalidated(
      nodeIds: List[String],
      treeId: TreeId
  ) extends SSEEvent {
    override def eventType: String = "cache_invalidated"
  }

  /**
    * Connection lifecycle event.
    *
    * @param status "connected", "heartbeat", "disconnecting"
    * @param message Optional message
    */
  final case class ConnectionStatus(
      status: String,
      message: Option[String] = None
  ) extends SSEEvent {
    override def eventType: String = "connection_status"
  }

  // JSON codecs for SSE payload serialization
  given JsonEncoder[LECUpdated] = DeriveJsonEncoder.gen[LECUpdated]
  given JsonDecoder[LECUpdated] = DeriveJsonDecoder.gen[LECUpdated]

  given JsonEncoder[NodeChanged] = DeriveJsonEncoder.gen[NodeChanged]
  given JsonDecoder[NodeChanged] = DeriveJsonDecoder.gen[NodeChanged]

  given JsonEncoder[CacheInvalidated] = DeriveJsonEncoder.gen[CacheInvalidated]
  given JsonDecoder[CacheInvalidated] = DeriveJsonDecoder.gen[CacheInvalidated]

  given JsonEncoder[ConnectionStatus] = DeriveJsonEncoder.gen[ConnectionStatus]
  given JsonDecoder[ConnectionStatus] = DeriveJsonDecoder.gen[ConnectionStatus]

  // Unified encoder for SSEEvent (used for Tapir SSE body)
  given JsonEncoder[SSEEvent] = JsonEncoder[String].contramap { event =>
    event match {
      case e: LECUpdated        => e.toJson
      case e: NodeChanged       => e.toJson
      case e: CacheInvalidated  => e.toJson
      case e: ConnectionStatus  => e.toJson
    }
  }
}
