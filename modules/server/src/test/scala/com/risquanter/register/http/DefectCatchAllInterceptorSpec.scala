package com.risquanter.register.http

import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.json.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing

import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.{Header, Method, QueryParams, StatusCode, Uri}
import sttp.monad.MonadError as SttpMonadError
import sttp.tapir.*
import sttp.tapir.AttributeKey
import sttp.tapir.model.{ConnectionInfo, ServerRequest}
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{CustomiseInterceptors, EndpointInterceptor, Interceptor, RequestHandler, RequestResult, Responder, ResponseSource}
import sttp.tapir.server.interceptor.cors.{CORSInterceptor, CORSConfig as TapirCORSConfig}
import sttp.tapir.server.model.{ServerResponse, ValuedEndpointOutput}
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.server.ziohttp.ZioHttpServerOptions
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.iron.TelemetryPlaceholders
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.TelemetryLive

/** A failure that reaches no endpoint's declared output is answered with one,
  * and the answer is one a browser can actually read.
  *
  * The first property stops an exception message being published: without this
  * interceptor the zio-http interpreter writes the squashed cause into a
  * plain-text body. The second is why this interceptor is appended rather than
  * prepended, and it is the property that placement exists to secure.
  *
  * Tapir applies the first interceptor in the stack outermost, and the CORS
  * interceptor adds its headers to the response returned from the interceptors
  * below it. An interceptor placed outside CORS therefore produces a response
  * CORS never decorates, and a browser rejects it on the cross-origin check
  * before any script can read the body. The trace the `500` exists to deliver
  * would then be unreachable by the only client that needs it, so "the answer
  * carries the CORS header" is a correctness property of this interceptor's
  * position and not a detail of the deployment.
  */
object DefectCatchAllInterceptorSpec extends ZIOSpecDefault with BaseEndpoint:

  private given SttpMonadError[Task] = new RIOMonadError[Any]

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  /** The origin the request claims and the CORS configuration allows. Any value
    * works; it only has to match on both sides for CORS to answer. */
  private val TestOrigin = "https://register.example"

  /** The path the stub request carries. One segment, so no capability key is
    * involved in the span name. */
  private val RequestPath = List("probe")

  // ── Driving the interceptor directly ────────────────────────────────────────

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

  /** Runs the real interceptor over a downstream handler producing the given
    * result, and returns both its own outcome and whatever output the responder
    * was asked for.
    *
    * The call is wrapped in a span because that is how the interceptor runs:
    * it is appended, so `RequestSpanInterceptor` is always outside it and a
    * span is always open. Driven with no span the interceptor would read
    * OpenTelemetry's invalid context and stamp the unlinked placeholder, which
    * would be correct behaviour and a misleading test.
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
        outcome <- tracing
                     .span("test request", SpanKind.SERVER) {
                       DefectCatchAllInterceptor
                         .interceptor(tracing)
                         .apply[Any, Unit](recordingResponder(recorded), handler)
                         .apply(stubRequest, Nil)
                     }
                     .exit
        sent    <- recorded.get
      yield (outcome, sent)
    }

  private def faultIn(sent: Option[ValuedEndpointOutput[?]]): Option[WireError.InternalFault] =
    sent.map(_.value).collect { case fault: WireError.InternalFault => fault }

  // ── Driving a real request through the whole chain ──────────────────────────

  /** An endpoint whose logic always dies, so every request to it reaches the
    * catch-all rather than any declared output. */
  private val dying =
    baseEndpoint.get
      .in("probe")
      .out(stringBody)
      .serverLogic[Task](_ => ZIO.die(new RuntimeException("a bug in a handler")))

  /** An endpoint whose logic fails with a `Throwable` rather than dying.
    *
    * Tapir's exception handler sits inside the catch-all and would answer this
    * itself, as plain text with no trace. The chain below replaces it with one
    * that re-raises, so this reaches the catch-all like a defect does.
    */
  private val failing =
    baseEndpoint.get
      .in("probe")
      .out(stringBody)
      .serverLogic[Task](_ => ZIO.fail(new RuntimeException("an escaped failure")))

  /** A capability key for the workspace-path request. Any value works; it only
    * has to be recognisable if it reaches a log line. */
  private val CapabilityKey = "LEAKCANARYcapabilitykeyvalue0123"

  /** An endpoint under the workspace path family that answers normally.
    *
    * Tapir's server log renders a request line for every request it handles,
    * and its default renders the whole path, so an ordinary request to a
    * workspace route is where the capability key would reach standard output.
    * It succeeds rather than dying because the line under test is the one the
    * log writes about a handled request.
    */
  private val reachableUnderWorkspacePath =
    baseEndpoint.get
      .in("w")
      .in(path[String]("key"))
      .in("probe")
      .out(stringBody)
      .serverLogic[Task](_ => ZIO.succeed(Right("reached the logic")))

  /** The options builder these tests add the catch-all to. */
  private type Customise = CustomiseInterceptors[Task, ZioHttpServerOptions[Any]]

  /** Where a test puts the catch-all in the chain. */
  private type Placement = (Customise, Interceptor[Task]) => Customise

  /** The production interceptor chain, with the catch-all's placement left to
    * the caller so a test can show what each placement produces.
    *
    * It reproduces the decode-failure handler, the exception handler, the server
    * log's redacted request line, the security headers prepended first and
    * therefore outermost, the span interceptor second, and CORS after both. Only
    * the CORS configuration differs from `Application.startServer`, which reads
    * its allowed origins from configuration.
    */
  private def chainWith(place: Placement, tracing: Tracing): Customise =
    place(
      ZioHttpServerOptions
        .customiseInterceptors[Any]
        .decodeFailureHandler(WireDecodeFailureHandler.handler(tracing))
        .exceptionHandler(DefectCatchAllInterceptor.exceptionHandler)
        .serverLog(
          ZioHttpServerOptions
            .defaultServerLog[Any]
            .showRequest(RequestSpanInterceptor.spanName)
        )
        .prependInterceptor(SecurityHeadersInterceptor.interceptor)
        .prependInterceptor(RequestSpanInterceptor.interceptor(tracing))
        .corsInterceptor(
          CORSInterceptor.customOrThrow[Task](
            TapirCORSConfig.default.allowMatchingOrigins(_ == TestOrigin)
          )
        ),
      DefectCatchAllInterceptor.interceptor(tracing)
    )

  private def getProbe(
    place:    Placement,
    endpoint: ServerEndpoint[Any, Task] = dying
  ): ZIO[Tracing, Throwable, Response[Either[String, String]]] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      val backend = TapirStubInterpreter(
        chainWith(place, tracing),
        SttpBackendStub[Task, Any](summon[SttpMonadError[Task]])
      ).whenServerEndpointRunLogic(endpoint).backend()

      basicRequest
        .get(uri"http://localhost/probe")
        .header("Origin", TestOrigin)
        .send(backend)
    }

  private val appended:  Placement = (c, i) => c.appendInterceptor(i)
  private val prepended: Placement = (c, i) => c.prependInterceptor(i)

  /** Drives one request at a workspace-scoped path through the same chain, so
    * the assertions can read what the server log wrote about it. */
  private def getWorkspaceProbe: ZIO[Tracing, Throwable, Response[Either[String, String]]] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      val backend = TapirStubInterpreter(
        chainWith(appended, tracing),
        SttpBackendStub[Task, Any](summon[SttpMonadError[Task]])
      ).whenServerEndpointRunLogic(reachableUnderWorkspacePath).backend()

      basicRequest
        .get(uri"http://localhost/w/$CapabilityKey/probe")
        .header("Origin", TestOrigin)
        .send(backend)
    }

  private def fault(response: Response[Either[String, String]]): Option[WireError.InternalFault] =
    response.body.left.toOption.flatMap(_.fromJson[WireError.InternalFault].toOption)

  private def allowOrigin(response: Response[Either[String, String]]): Option[String] =
    response.header("Access-Control-Allow-Origin")

  override def spec = suite("DefectCatchAllInterceptor")(

    suite("what it answers")(
      test("a defect is answered with the declared internal-fault output") {
        for (outcome, sent) <- runWith(ZIO.die(new RuntimeException("a bug in a handler")))
        yield assertTrue(
          outcome.isSuccess,
          sent.map(_.output).contains(BaseEndpoint.internalFaultOut),
          faultIn(sent).isDefined
        )
      },
      test("a typed failure that escaped the endpoint is answered the same way") {
        for (outcome, sent) <- runWith(ZIO.fail(new RuntimeException("an escaped failure")))
        yield assertTrue(outcome.isSuccess, faultIn(sent).isDefined)
      },
      test("the answer carries the trace of the span the request ran under") {
        for
          (_, sent) <- runWith(ZIO.die(new RuntimeException("a bug in a handler")))
          trace      = faultIn(sent).map(_.trace)
        yield assertTrue(trace.isDefined, !trace.contains(TelemetryPlaceholders.UnlinkedTrace))
      },
      test("a successful result passes through untouched and nothing is sent") {
        val response = ServerResponse(StatusCode.Ok, Nil, Some(()), None)
        for
          (outcome, sent) <- runWith(ZIO.succeed(RequestResult.Response(response, ResponseSource.EndpointHandler)))
        yield assertTrue(outcome.isSuccess, sent.isEmpty)
      },
      test("an interruption is re-failed rather than answered") {
        for (outcome, sent) <- runWith(ZIO.interrupt)
        yield assertTrue(!outcome.isSuccess, sent.isEmpty)
      }
    ),

    suite("where it sits in the chain")(
      test("appended, the answer is a 500 carrying only the trace") {
        for response <- getProbe(appended)
        yield assertTrue(
          response.code.code == 500,
          fault(response).isDefined,
          // The exception's own message must not reach the body. Without this
          // interceptor the interpreter writes `cause.squash.getMessage` there.
          !response.body.left.toOption.exists(_.contains("a bug in a handler")),
          // Through the real chain the span interceptor is outside this one, so
          // the trace is a real span's and not the unlinked placeholder.
          !fault(response).map(_.trace).contains(TelemetryPlaceholders.UnlinkedTrace)
        )
      },
      test("appended, the answer carries the CORS header a browser requires") {
        for response <- getProbe(appended)
        yield assertTrue(allowOrigin(response).contains(TestOrigin))
      },
      // Tapir's own exception handler would answer this one before the
      // catch-all saw it, as `500` with the plain-text body "Internal server
      // error" and no trace. The chain replaces it with a handler that
      // re-raises, so a typed failure and a defect answer alike.
      test("a typed failure reaches the catch-all rather than Tapir's own handler") {
        for response <- getProbe(appended, failing)
        yield assertTrue(
          response.code.code == 500,
          fault(response).isDefined,
          !fault(response).map(_.trace).contains(TelemetryPlaceholders.UnlinkedTrace),
          !response.body.left.toOption.exists(_.contains("Internal server error")),
          !response.body.left.toOption.exists(_.contains("an escaped failure"))
        )
      },
      /** The server log is a second sink for the request line, and its default
        * renders the whole path. Pointing it at the span name is what keeps the
        * capability key out of standard output, and nothing else asserts it.
        */
      test("the server log names the route family and never the capability key") {
        for
          _     <- getWorkspaceProbe
          lines <- ZTestLogger.logOutput
          text   = lines.map(_.message()).mkString("\n")
        yield assertTrue(
          text.contains("/w/{key}"),
          !text.contains(CapabilityKey)
        )
      },
      test("prepended, the same answer loses that header — which is why it is appended") {
        for response <- getProbe(prepended)
        yield assertTrue(
          // Still a 500 with the right body: the placement does not change what
          // is answered, only whether CORS decorates it on the way out.
          response.code.code == 500,
          fault(response).isDefined,
          allowOrigin(response).isEmpty
        )
      }
    )
  ).provideLayer(telemetry.orDie)
