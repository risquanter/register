package app.core

import zio.Task
import zio.ZLayer

import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.WebSockets
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import sttp.tapir.Endpoint
import sttp.tapir.client.sttp.SttpClientInterpreter

type ZioStreamsWithWebSockets = ZioStreams & WebSockets

/** A client to the backend, interpreting shared Tapir endpoints as ZIO effects.
  *
  * Pattern adapted from cheleb/rockthejvm — BackendClient is a ZIO service
  * whose live implementation uses sttp's FetchZioBackend (Fetch API in the browser)
  * and Tapir's SttpClientInterpreter to turn endpoint descriptions into HTTP
  * requests automatically.
  */
trait BackendClient:

  /** Call an endpoint with a payload.
    *
    * Turns an endpoint description into a Task that:
    *   - builds a request from the payload
    *   - sends it to the backend via the Fetch API
    *   - returns the decoded response
    *
    * @param endpoint the Tapir endpoint definition (from the shared common module)
    * @param payload the input to the endpoint
    * @return the decoded output wrapped in a Task
    */
  def endpointRequestZIO[I, E <: Throwable, O](
      endpoint: Endpoint[Unit, I, E, O, Any]
  )(payload: I): Task[O]

/** Live implementation backed by FetchZioBackend.
  *
  * @param backend     sttp backend using the browser Fetch API
  * @param interpreter Tapir→sttp request builder
  * @param baseUrl     backend base URL from [[Constants.backendBaseURL]]
  */
private class BackendClientLive(
    backend: SttpBackend[Task, ZioStreamsWithWebSockets],
    interpreter: SttpClientInterpreter,
    baseUrl: Option[Uri]
) extends BackendClient:

  /** Turn an endpoint into a function from Input => Request.
    *
    * Every request carries `Accept: application/json` so that nginx's
    * Accept-header router (`location /w/`) proxies it to the backend
    * instead of serving the SPA shell.  Tapir's sttp client interpreter
    * does not reliably set this header on bodiless GET requests, and the
    * browser Fetch API defaults to `Accept: *⁄*` which does not match
    * the nginx rule.  See ADR-027 / ADR-INFRA-007 for routing design.
    */
  private def endpointRequest[I, E, O](
      endpoint: Endpoint[Unit, I, E, O, Any]
  ): I => Request[Either[E, O], Any] =
    val toRequest = interpreter.toRequestThrowDecodeFailures(endpoint, baseUrl)
    input => toRequest(input).header("Accept", "application/json")

  def endpointRequestZIO[I, E <: Throwable, O](
      endpoint: Endpoint[Unit, I, E, O, Any]
  )(payload: I): Task[O] =
    backend
      .send(endpointRequest(endpoint)(payload))
      .map(_.body)
      .absolve

object BackendClientLive:

  /** Pre-wired layer with FetchZioBackend, default interpreter,
    * and base URL from [[Constants.backendBaseURL]].
    *
    * This is the layer that ZJS extension methods provide to the ZIO runtime.
    * Lazy to defer Fetch backend and URI construction until first use.
    */
  lazy val configuredLayer: ZLayer[Any, Nothing, BackendClient] =
    ZLayer.succeed {
      BackendClientLive(
        backend     = FetchZioBackend(),
        interpreter = SttpClientInterpreter(),
        baseUrl     = Some(uri"${Constants.backendBaseURL}")
      )
    }
