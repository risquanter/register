package com.risquanter.register.http.sse

import zio.json.*
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, BranchChoice}

/**
  * Server-Sent Events for real-time updates to browser clients.
  *
  * Per ADR-004a-proposal: "SSE provides simple unidirectional streaming 
  * for server→client push"
  *
  * Event types:
  * - LECUpdated: LEC curve recomputed for a node
  * - NodeChanged: Tree structure modified (add/update/remove)
  * - CacheInvalidated: These nodes' figures changed — re-fetch (name kept for wire compatibility)
  * - ConnectionStatus: Client connection lifecycle events
  */
sealed trait SSEEvent {
  def eventType: String
}

/** Kind of structural change reported by a `NodeChanged` event. */
enum NodeChangeType:
  case Added, Updated, Removed

  /** Wire form for `NodeChanged.changeType` — colocated with the case list so a
    * new case and its wire string are added in the same place (mirrors
    * `NodeChangeStatus.toWire`).
    */
  def toWire: String = this match
    case NodeChangeType.Added   => "added"
    case NodeChangeType.Updated => "updated"
    case NodeChangeType.Removed => "removed"

object NodeChangeType:
  def fromWire(s: String): Either[String, NodeChangeType] = s match
    case "added"   => Right(NodeChangeType.Added)
    case "updated" => Right(NodeChangeType.Updated)
    case "removed" => Right(NodeChangeType.Removed)
    case other     => Left(s"unknown node change type: $other")

  given JsonEncoder[NodeChangeType] = JsonEncoder[String].contramap(_.toWire)
  given JsonDecoder[NodeChangeType] = JsonDecoder[String].mapOrFail(fromWire)

/** Connection lifecycle state reported by a `ConnectionStatus` event. */
enum ConnectionState:
  case Connected, Heartbeat, Disconnecting

  /** Wire form for `ConnectionStatus.status` — colocated with the case list so a
    * new case and its wire string are added in the same place (mirrors
    * `NodeChangeStatus.toWire`).
    */
  def toWire: String = this match
    case ConnectionState.Connected     => "connected"
    case ConnectionState.Heartbeat     => "heartbeat"
    case ConnectionState.Disconnecting => "disconnecting"

object ConnectionState:
  def fromWire(s: String): Either[String, ConnectionState] = s match
    case "connected"     => Right(ConnectionState.Connected)
    case "heartbeat"     => Right(ConnectionState.Heartbeat)
    case "disconnecting" => Right(ConnectionState.Disconnecting)
    case other           => Left(s"unknown connection state: $other")

  given JsonEncoder[ConnectionState] = JsonEncoder[String].contramap(_.toWire)
  given JsonDecoder[ConnectionState] = JsonDecoder[String].mapOrFail(fromWire)

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
    * @param changeType Kind of structural change to the node
    */
  final case class NodeChanged(
      nodeId: String,
      treeId: TreeId,
      changeType: NodeChangeType
  ) extends SSEEvent {
    override def eventType: String = "node_changed"
  }

  /**
    * These nodes' figures changed — clients should re-fetch them. (The event
    * name predates the content-addressed cache, which has no invalidation;
    * kept as the wire event type.)
    *
    * @param nodeIds Node IDs whose figures changed (nodes + ancestors)
    * @param treeId Tree containing the nodes
    * @param branch Client-facing branch the change landed on — `Main` or a
    *   scenario name (never the internal `BranchRef`, which embeds the
    *   WorkspaceId). Required (DD-22 / E7): the SPA filters events to the tab's
    *   branch, so there is no absent-means-main state. Serializes as the same
    *   `"main"`/slug string a raw `String` would.
    */
  final case class CacheInvalidated(
      nodeIds: List[NodeId],
      treeId: TreeId,
      branch: BranchChoice
  ) extends SSEEvent {
    override def eventType: String = "cache_invalidated"
  }

  /**
    * Connection lifecycle event.
    *
    * @param status Connection lifecycle state
    * @param message Optional message
    */
  final case class ConnectionStatus(
      status: ConnectionState,
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
