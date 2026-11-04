package com.risquanter.register.http.sse

import zio.json.*
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, BranchChoice}

/** Kind of structural change reported by a `NodeChanged` event. */
enum NodeChangeType:
  case Added, Updated, Removed

object NodeChangeType:
  given codec: JsonCodec[NodeChangeType] = DeriveJsonCodec.gen[NodeChangeType]

/** Connection lifecycle state reported by a `ConnectionStatus` event. */
enum ConnectionState:
  case Connected, Heartbeat, Disconnecting

object ConnectionState:
  given codec: JsonCodec[ConnectionState] = DeriveJsonCodec.gen[ConnectionState]

/** One event pushed to a browser over a tree's event stream (ADR-004a §3).
  *
  * The tag field names the case with the spelling the SSE `event:` line also
  * carries, so a listener registered for a name receives exactly the payloads
  * whose tag carries it. The spellings are declared rather than derived,
  * because ADR-004a and the endpoint's description publish them (ADR-037 §2).
  */
@jsonDiscriminator(SSEEvent.TagField)
enum SSEEvent:
  /** Quantiles recomputed for a node. Defined by ADR-004a; nothing publishes
    * it yet. */
  @jsonHint(SSEEvent.Names.LecUpdated)
  case LECUpdated(nodeId: NodeId, treeId: TreeId, quantiles: Map[String, Double])

  /** The tree's structure changed at a node. Defined by ADR-004a; nothing
    * publishes it yet. */
  @jsonHint(SSEEvent.Names.NodeChanged)
  case NodeChanged(nodeId: NodeId, treeId: TreeId, changeType: NodeChangeType)

  /** These nodes' figures changed, so a browser showing them fetches them
    * again: the edited nodes and every ancestor whose aggregate changed with
    * them. `branch` is the client-facing branch the change landed on, never
    * the internal `BranchRef`, which embeds the `WorkspaceId` (ADR-036). */
  @jsonHint(SSEEvent.Names.FiguresChanged)
  case FiguresChanged(nodeIds: List[NodeId], treeId: TreeId, branch: BranchChoice)

  /** Connection lifecycle: sent on connect and as the periodic heartbeat. */
  @jsonHint(SSEEvent.Names.ConnectionStatus)
  case ConnectionStatus(status: ConnectionState, message: Option[String])

object SSEEvent:
  /** The key naming which event a payload is. */
  val TagField: String = "event"

  /** The published event names (ADR-004a §3), each the tag value and the
    * `event:` line of its case. */
  object Names:
    final val LecUpdated       = "lec_updated"
    final val NodeChanged      = "node_changed"
    final val FiguresChanged   = "figures_changed"
    final val ConnectionStatus = "connection_status"

  /** The SSE `event:` line for an event: the same spelling its tag carries. */
  def eventName(event: SSEEvent): String = event match
    case _: LECUpdated       => Names.LecUpdated
    case _: NodeChanged      => Names.NodeChanged
    case _: FiguresChanged   => Names.FiguresChanged
    case _: ConnectionStatus => Names.ConnectionStatus

  given codec: JsonCodec[SSEEvent] = DeriveJsonCodec.gen[SSEEvent]
