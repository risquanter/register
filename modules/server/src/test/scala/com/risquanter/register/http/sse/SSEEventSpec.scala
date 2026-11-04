package com.risquanter.register.http.sse

import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.test.*

import com.risquanter.register.domain.data.iron.BranchChoice
import com.risquanter.register.testutil.TestHelpers.*

/** The event codec, and the law `EWB-D-62` rests on: the `event:` line and the
  * payload's tag carry one name. */
object SSEEventSpec extends ZIOSpecDefault:

  private val every: List[SSEEvent] = List(
    SSEEvent.LECUpdated(nodeId("cyber"), treeId("tree-1"), Map("p50" -> 1000.0)),
    SSEEvent.NodeChanged(nodeId("cyber"), treeId("tree-1"), NodeChangeType.Updated),
    SSEEvent.FiguresChanged(List(nodeId("cyber"), nodeId("root")), treeId("tree-1"), BranchChoice.Main),
    SSEEvent.ConnectionStatus(ConnectionState.Heartbeat, None)
  )

  /** The tag value the codec wrote, read back out of the encoded payload. */
  private def tagOf(event: SSEEvent): Option[String] =
    event.toJson.fromJson[Json].toOption.flatMap(_.get(JsonCursor.field(SSEEvent.TagField)).toOption).collect {
      case Json.Str(value) => value
    }

  def spec = suite("SSEEvent")(
    test("every case round-trips through the derived codec") {
      assertTrue(every.forall(e => e.toJson.fromJson[SSEEvent] == Right(e)))
    },
    test("the event: line and the payload's tag carry one name") {
      assertTrue(every.forall(e => tagOf(e) == Some(SSEEvent.eventName(e))))
    },
    test("the payload is a JSON object, not a quoted string") {
      assertTrue(every.forall(_.toJson.startsWith("{")))
    },
    test("the published names are the ones ADR-004a lists") {
      assertTrue(
        every.map(SSEEvent.eventName).toSet ==
          Set("lec_updated", "node_changed", "figures_changed", "connection_status")
      )
    }
  )
