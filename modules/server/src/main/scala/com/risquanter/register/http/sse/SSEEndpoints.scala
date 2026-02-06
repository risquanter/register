package com.risquanter.register.http.sse

import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint
import sttp.capabilities.zio.ZioStreams
import sttp.model.MediaType
import zio.*
import zio.stream.*

import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.iron.TreeId

/**
  * SSE endpoint definitions for real-time updates.
  *
  * Per ADR-004a-proposal: Uses Tapir streamBody with ZioStreams for consistency 
  * with existing endpoint patterns and Swagger documentation.
  *
  * Endpoint: GET /events/tree/{treeId}
  * Returns: Server-Sent Events stream (text/event-stream)
  */
trait SSEEndpoints extends BaseEndpoint {

  /**
    * SSE stream endpoint for tree updates.
    *
    * Clients connect to receive real-time notifications about:
    * - LEC curve updates
    * - Node changes (add/update/remove)
    * - Cache invalidation events
    *
    * Response format: text/event-stream with newline-delimited JSON events
    */
  val treeEventsEndpoint: PublicEndpoint[TreeId, Unit, Stream[Throwable, Byte], ZioStreams] =
    endpoint
      .tag("events")
      .name("treeEvents")
      .summary("Subscribe to real-time tree updates")
      .description(
        """Server-Sent Events stream for receiving real-time updates about a risk tree.
          |
          |Event types:
          |- `lec_updated`: LEC curve recomputed for a node
          |- `node_changed`: Tree structure modified
          |- `cache_invalidated`: Cache entries cleared
          |- `connection_status`: Heartbeat and lifecycle events
          |
          |Each event is formatted as:
          |```
          |event: <event_type>
          |data: <json_payload>
          |
          |```
          |""".stripMargin
      )
      .in("events" / "tree" / path[TreeId]("treeId"))
      .get
      .out(
        streamBody(ZioStreams)(
          Schema.string,
          CodecFormat.TextEventStream()
        )
      )
}

object SSEEndpoints extends SSEEndpoints
