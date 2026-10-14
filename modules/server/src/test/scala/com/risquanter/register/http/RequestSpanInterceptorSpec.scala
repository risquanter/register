package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing

import sttp.model.{Header, Method, QueryParams, StatusCode, Uri}
import sttp.monad.MonadError as SttpMonadError
import sttp.tapir.AttributeKey
import sttp.tapir.model.{ConnectionInfo, ServerRequest}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestResult, Responder, ResponseSource}
import sttp.tapir.server.model.{ServerResponse, ValuedEndpointOutput}
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.iron.TelemetryPlaceholders
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.TelemetryLive

/** A failure that reaches no endpoint's declared output is still answered with
  * one, and an interruption is not answered at all.
  *
  * The first is what stops an exception message being published: without it the
  * zio-http interpreter writes the squashed cause into a plain-text body. The
  * value asserted is the output handed to the responder, because that is the
  * single thing deciding both the status and the body, and it is the same value
  * the endpoints declare for a projected failure.
  *
  * The second is the condition the first must not swallow. A cancelled request
  * has no caller left to answer, so the cause is re-failed rather than turned
  * into a fault nobody reads.
  */
object RequestSpanInterceptorSpec extends ZIOSpecDefault:

  private given SttpMonadError[Task] = new RIOMonadError[Any]

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  /** The path the stub request carries. One segment, so the span name is the
    * method and that segment and no capability key is involved. */
  private val RequestPath = List("risk-trees")

  /** Minimal `ServerRequest` stub exposing only what the interceptor reads. */
  private def stubRequest: ServerRequest =
    new ServerRequest:
      override def protocol: String                                      = "HTTP/1.1"
      override def connectionInfo: ConnectionInfo                        = ConnectionInfo(None, None, None)
      override def underlying: Any                                       = ()
      override def pathSegments: List[String]                            = RequestPath
      override def queryParameters: QueryParams                          = QueryParams()
      override def method: Method                                        = Method.GET
      override def uri: Uri                                              = Uri(RequestPath.mkString("/", "/", ""))
      override def headers: Seq[Header]                                  = Nil
      override def attribute[T](k: AttributeKey[T]): Option[T]           = None
      override def attribute[T](k: AttributeKey[T], v: T): ServerRequest = this
      override def withUnderlying(underlying: Any): ServerRequest        = this

  /** A responder that records the output it was asked to send instead of
    * encoding it. The recorded value is what the assertions read: encoding it
    * would test zio-json rather than this interceptor. */
  private def recordingResponder(
    recorded: Ref[Option[ValuedEndpointOutput[?]]]
  ): Responder[Task, Unit] =
    new Responder[Task, Unit]:
      override def apply[O](req: ServerRequest, out: ValuedEndpointOutput[O]): Task[ServerResponse[Unit]] =
        recorded.set(Some(out)).as(ServerResponse(StatusCode.Ok, Nil, Some(()), None))

  /** Runs the real interceptor over a downstream handler that produces the given
    * result, and returns both the interceptor's own result and whatever output
    * the responder was asked for. */
  private def runWith(
    downstream: Task[RequestResult[Unit]]
  ): ZIO[Tracing, Nothing, (Exit[Throwable, RequestResult[Unit]], Option[ValuedEndpointOutput[?]])] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      for
        recorded <- Ref.make(Option.empty[ValuedEndpointOutput[?]])
        handler: (EndpointInterceptor[Task] => RequestHandler[Task, Any, Unit]) =
          _ =>
            new RequestHandler[Task, Any, Unit]:
              override def apply(req: ServerRequest, eps: List[ServerEndpoint[Any, Task]])(implicit
                m: SttpMonadError[Task]
              ): Task[RequestResult[Unit]] = downstream
        outcome <- RequestSpanInterceptor
                     .interceptor(tracing)
                     .apply[Any, Unit](recordingResponder(recorded), handler)
                     .apply(stubRequest, Nil)
                     .exit
        sent    <- recorded.get
      yield (outcome, sent)
    }

  /** The value inside a recorded output, when it is the internal-fault body. */
  private def faultIn(sent: Option[ValuedEndpointOutput[?]]): Option[WireError.InternalFault] =
    sent.map(_.value).collect { case fault: WireError.InternalFault => fault }

  override def spec = suite("RequestSpanInterceptor")(

    test("a defect is answered with the declared internal-fault output") {
      for
        (outcome, sent) <- runWith(ZIO.die(new RuntimeException("a bug in a handler")))
      yield assertTrue(
        outcome.isSuccess,
        sent.map(_.output).contains(endpoints.BaseEndpoint.internalFaultOut),
        faultIn(sent).isDefined
      )
    },

    test("a typed failure that escaped the endpoint is answered the same way") {
      for
        (outcome, sent) <- runWith(ZIO.fail(new RuntimeException("an escaped failure")))
      yield assertTrue(
        outcome.isSuccess,
        faultIn(sent).isDefined
      )
    },

    test("the answer carries the trace of the span the request ran under") {
      for
        (_, sent) <- runWith(ZIO.die(new RuntimeException("a bug in a handler")))
        trace      = faultIn(sent).map(_.trace)
      yield assertTrue(
        trace.isDefined,
        !trace.contains(TelemetryPlaceholders.UnlinkedTrace)
      )
    },

    test("a successful result passes through untouched and nothing is sent") {
      val response = ServerResponse(StatusCode.Ok, Nil, Some(()), None)
      for
        (outcome, sent) <- runWith(
                             ZIO.succeed(RequestResult.Response(response, ResponseSource.EndpointHandler))
                           )
      yield assertTrue(
        outcome.isSuccess,
        sent.isEmpty
      )
    },

    test("an interruption is re-failed rather than answered") {
      for
        (outcome, sent) <- runWith(ZIO.interrupt)
      yield assertTrue(
        !outcome.isSuccess,
        sent.isEmpty
      )
    }
  ).provideLayer(telemetry.orDie)
