package com.risquanter.register.http.sse

import zio.*
import zio.stream.*
import sttp.tapir.server.ServerEndpoint
import sttp.capabilities.zio.ZioStreams
import zio.json.*

import com.risquanter.register.http.controllers.BaseController
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.domain.data.iron.TreeId

/**
  * Controller for SSE endpoints.
  *
  * Per ADR-004a-proposal: "Controllers wire endpoints to services"
  * This controller connects the Tapir streaming endpoint to the SSEHub service.
  *
  * Pattern: SSE Request → Tapir Endpoint → Controller → SSEHub → ZStream → Client
  *
  * Note: Uses ServerEndpoint[Any, Task] for BaseController compatibility.
  * ZioHttpInterpreter handles ZioStreams capabilities at runtime.
  *
  * SSE Format:
  * ```
  * event: lec_updated
  * data: {"nodeId":"cyber","treeId":1,"quantiles":{...}}
  *
  * ```
  */
class SSEController private (sseHub: SSEHub)
    extends BaseController
    with SSEEndpoints {

  private val HeartbeatInterval = 30.seconds

  /**
    * Tree events SSE stream.
    *
    * Subscribes to SSEHub for the requested tree and streams events
    * to the client. Includes periodic heartbeat to keep connection alive.
    */
  val treeEvents: ServerEndpoint[ZioStreams, Task] =
    treeEventsEndpoint.serverLogicSuccess { treeId =>
      for
        eventStream <- sseHub.subscribe(treeId)
        sseStream    = eventStream.map(formatAsSSE)
        withHeartbeat = sseStream.merge(heartbeatStream)
        withConnect   = ZStream.succeed(connectedEvent(treeId)) ++ withHeartbeat
        byteStream: Stream[Throwable, Byte] = withConnect
          .flatMap(s => ZStream.fromIterable(s.getBytes("UTF-8")))
          .mapError(_ => new RuntimeException("SSE stream error"))
      yield byteStream
    }

  /**
    * Format domain SSEEvent as SSE wire format.
    *
    * SSE format:
    * ```
    * event: <type>
    * data: <json>
    *
    * ```
    */
  private def formatAsSSE(event: SSEEvent): String =
    s"event: ${event.eventType}\ndata: ${event.toJson}\n\n"

  /**
    * Initial connection event.
    */
  private def connectedEvent(treeId: TreeId): String =
    formatAsSSE(SSEEvent.ConnectionStatus("connected", Some(s"Subscribed to tree ${treeId.value}")))

  /**
    * Periodic heartbeat to keep SSE connection alive.
    * Some proxies/load balancers close idle connections.
    */
  private val heartbeatStream: ZStream[Any, Nothing, String] =
    ZStream
      .tick(HeartbeatInterval)
      .map(_ => formatAsSSE(SSEEvent.ConnectionStatus("heartbeat", None)))

  // Cast to Any capability - ZioHttpInterpreter handles ZioStreams at runtime
  override val routes: List[ServerEndpoint[Any, Task]] =
    List(treeEvents.asInstanceOf[ServerEndpoint[Any, Task]])
}

object SSEController {

  /**
    * Create SSEController with SSEHub dependency.
    */
  val layer: ZLayer[SSEHub, Nothing, SSEController] =
    ZLayer.fromZIO {
      for
        hub <- ZIO.service[SSEHub]
      yield new SSEController(hub)
    }

  /**
    * Create SSEController directly from SSEHub.
    */
  def make(hub: SSEHub): SSEController = new SSEController(hub)
}
