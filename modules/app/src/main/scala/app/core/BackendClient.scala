package app.core

import zio.IO
import zio.Task
import zio.ZIO
import zio.ZLayer

import com.risquanter.register.http.errors.WireError

import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.WebSockets
import sttp.client3.*
import sttp.client3.impl.zio.FetchZioBackend
import sttp.model.Uri
import sttp.tapir.{DecodeResult, Endpoint}
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
    * Turns an endpoint description into an effect that builds a request from
    * the payload, sends it to the backend via the Fetch API, and returns the
    * decoded response.
    *
    * The two ways this can fail are the two cases of [[RequestFailure]]: the
    * server answered with a typed error body, or the call never completed.
    *
    * @param endpoint the Tapir endpoint definition, from the shared common module
    * @param payload the input to the endpoint
    * @return the decoded output, or the failure that replaced it
    */
  def endpointRequestZIO[I, O](
      endpoint: Endpoint[Unit, I, WireError, O, Any]
  )(payload: I): IO[RequestFailure, O]

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
    *
    * The decoded body is a `DecodeResult` rather than the decoded value, so a
    * reply that does not decode is a value this client can classify. The
    * throwing variant of the interpreter makes it an exception instead, which
    * loses the status it arrived with, and the status is the only evidence of
    * whether the request took effect.
    */
  private def endpointRequest[I, E, O](
      endpoint: Endpoint[Unit, I, E, O, Any]
  ): I => Request[DecodeResult[Either[E, O]], Any] =
    val toRequest = interpreter.toRequest(endpoint, baseUrl)
    input => toRequest(input).header("Accept", "application/json")

  /** The three failure cases are separated here, and this is the only place
    * they can be told apart.
    *
    * A failure of `send` is the Fetch call itself not completing, so it becomes
    * `Transport` — the one case where nothing reached the server and nothing can
    * have changed. Once a response exists, its body decides the rest: a decoded
    * `Left` is the server's own typed answer and becomes `Wire`, and a body that
    * did not decode becomes `Undecodable` carrying the status it arrived with.
    *
    * Telling the last two from `Transport` is the point of the three cases.
    * `Transport` states that nothing was changed, and that is true only when the
    * request never arrived.
    */
  def endpointRequestZIO[I, O](
      endpoint: Endpoint[Unit, I, WireError, O, Any]
  )(payload: I): IO[RequestFailure, O] =
    backend
      .send(endpointRequest(endpoint)(payload))
      .mapError(RequestFailure.Transport(_))
      .flatMap { response =>
        response.body match
          case DecodeResult.Value(Right(output)) => ZIO.succeed(output)
          case DecodeResult.Value(Left(error))   => ZIO.fail(RequestFailure.Wire(error))
          case _: DecodeResult.Failure           => ZIO.fail(RequestFailure.Undecodable(response.code))
      }

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
