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
import com.risquanter.register.domain.data.iron.{TelemetryPlaceholders, TraceId}
import com.risquanter.register.telemetry.{ActiveTrace, TelemetryLive}

/** One SERVER span is open around everything downstream, and nothing else is
  * changed on the way through.
  *
  * The span is this interceptor's whole job. What makes it worth asserting is
  * that the span has to be *open while downstream runs* rather than merely
  * opened and closed: the boundary reads the trace from the active span deep
  * inside a request, and an interceptor that opened a span around nothing would
  * leave every failure carrying the unlinked placeholder. So the first test
  * reads the trace from inside the downstream handler, which is where the real
  * readers sit.
  *
  * Answering a failure that reached no declared output is
  * `DefectCatchAllInterceptor`'s job and is asserted in its own spec. This
  * interceptor passes every outcome through untouched, which the remaining
  * tests pin — a failure must reach the catch-all rather than being handled
  * here.
  */
object RequestSpanInterceptorSpec extends ZIOSpecDefault:

  private given SttpMonadError[Task] = new RIOMonadError[Any]

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  /** The path the stub request carries. One segment, so the span name is the
    * method and that segment and no capability key is involved. */
  private val RequestPath = List("risk-trees")

  /** Minimal `ServerRequest` stub exposing only what the interceptor reads. */
  private def stubRequest(path: List[String] = RequestPath, httpMethod: Method = Method.GET): ServerRequest =
    new ServerRequest:
      override def protocol: String                                      = "HTTP/1.1"
      override def connectionInfo: ConnectionInfo                        = ConnectionInfo(None, None, None)
      override def underlying: Any                                       = ()
      override def pathSegments: List[String]                            = path
      override def queryParameters: QueryParams                          = QueryParams()
      override def method: Method                                        = httpMethod
      override def uri: Uri                                              = Uri(path.mkString("/", "/", ""))
      override def headers: Seq[Header]                                  = Nil
      override def attribute[T](k: AttributeKey[T]): Option[T]           = None
      override def attribute[T](k: AttributeKey[T], v: T): ServerRequest = this
      override def withUnderlying(underlying: Any): ServerRequest        = this

  /** A responder that records anything it is asked to send. Nothing should ask
    * it: this interceptor answers nothing. */
  private def recordingResponder(
    recorded: Ref[Option[ValuedEndpointOutput[?]]]
  ): Responder[Task, Unit] =
    new Responder[Task, Unit]:
      override def apply[O](req: ServerRequest, out: ValuedEndpointOutput[O]): Task[ServerResponse[Unit]] =
        recorded.set(Some(out)).as(ServerResponse(StatusCode.Ok, Nil, Some(()), None))

  /** Runs the real interceptor over a downstream handler built from the given
    * effect, and returns its outcome plus anything the responder was asked for.
    */
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
                     .apply(stubRequest(), Nil)
                     .exit
        sent    <- recorded.get
      yield (outcome, sent)
    }

  private val okResult: RequestResult[Unit] =
    RequestResult.Response(ServerResponse(StatusCode.Ok, Nil, Some(()), None), ResponseSource.EndpointHandler)

  override def spec = suite("RequestSpanInterceptor")(

    test("the span is open while downstream runs, so the trace is readable there") {
      ZIO.serviceWithZIO[Tracing] { tracing =>
        for
          observed <- Ref.make(Option.empty[TraceId])
          handler: (EndpointInterceptor[Task] => RequestHandler[Task, Any, Unit]) =
            _ =>
              new RequestHandler[Task, Any, Unit]:
                override def apply(req: ServerRequest, eps: List[ServerEndpoint[Any, Task]])(implicit
                  m: SttpMonadError[Task]
                ): Task[RequestResult[Unit]] =
                  ActiveTrace.id(using tracing).flatMap(t => observed.set(Some(t))).as(okResult)
          recorded <- Ref.make(Option.empty[ValuedEndpointOutput[?]])
          _        <- RequestSpanInterceptor
                        .interceptor(tracing)
                        .apply[Any, Unit](recordingResponder(recorded), handler)
                        .apply(stubRequest(), Nil)
          trace    <- observed.get
        yield assertTrue(
          trace.isDefined,
          // The unlinked placeholder is thirty-two zeros, so a non-emptiness
          // check would pass with no span open at all.
          !trace.contains(TelemetryPlaceholders.UnlinkedTrace)
        )
      }
    },

    test("a successful result passes through untouched and nothing is sent") {
      for (outcome, sent) <- runWith(ZIO.succeed(okResult))
      yield assertTrue(outcome.isSuccess, sent.isEmpty)
    },

    test("a defect passes through rather than being answered here") {
      for (outcome, sent) <- runWith(ZIO.die(new RuntimeException("a bug in a handler")))
      yield assertTrue(!outcome.isSuccess, sent.isEmpty)
    },

    test("an interruption passes through") {
      for (outcome, sent) <- runWith(ZIO.interrupt)
      yield assertTrue(!outcome.isSuccess, sent.isEmpty)
    },

    suite("the span name")(
      test("a workspace-scoped path hides the capability key") {
        val name = RequestSpanInterceptor.spanName(stubRequest(List("w", "a-capability-key", "risk-trees")))
        assertTrue(
          name == "GET /w/{key}",
          !name.contains("a-capability-key")
        )
      },
      test("another path contributes its first segment only") {
        assertTrue(
          RequestSpanInterceptor.spanName(stubRequest(List("risk-trees", "42"))) == "GET /risk-trees"
        )
      },
      test("an empty path is the root") {
        assertTrue(RequestSpanInterceptor.spanName(stubRequest(Nil, Method.POST)) == "POST /")
      }
    )
  ).provideLayer(telemetry.orDie)
