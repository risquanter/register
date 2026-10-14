package com.risquanter.register.http

import zio.*
import zio.json.*
import zio.test.*
import zio.telemetry.opentelemetry.tracing.Tracing

import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError as SttpMonadError
import sttp.tapir.*
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.server.ziohttp.ZioHttpServerOptions
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.TelemetryLive

/** A request the decoders rejected is answered with the same typed error shape
  * as every other failure, and not with Tapir's plain-text default.
  *
  * Two levels are checked. The mapping from a decode failure to a
  * `ValidationError` is a pure function and is checked directly, one case per
  * rejection kind. The integration is checked by sending a real request through
  * the real interceptor chain, because the part that can silently regress is not
  * the mapping but whether the handler is consulted at all.
  */
object WireDecodeFailureHandlerSpec extends ZIOSpecDefault with BaseEndpoint:

  private given SttpMonadError[Task] = new RIOMonadError[Any]

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  /** The input the mapping tests report against. A header is the case whose name
    * must come through exactly, because a client can act on it. */
  private val branchInput: EndpointInput[String] = header[String]("X-Branch")

  // ── The endpoint the request-level test drives ───────────────────────────────

  /** A minimal endpoint carrying the same required branch header the workspace
    * routes use. Its logic is never reached in these tests: the point is what
    * happens before it runs. */
  private val probe =
    baseEndpoint.get
      .in("probe")
      .in(branchHeader)
      .out(stringBody)
      .serverLogic[Task](_ => ZIO.succeed(Right("reached the logic")))

  private def backend: ZIO[Tracing, Nothing, SttpBackend[Task, Any]] =
    ZIO.serviceWith[Tracing] { tracing =>
      val options = ZioHttpServerOptions
        .customiseInterceptors[Any]
        .decodeFailureHandler(WireDecodeFailureHandler.handler(tracing))

      TapirStubInterpreter(options, SttpBackendStub[Task, Any](summon[SttpMonadError[Task]]))
        .whenServerEndpointRunLogic(probe)
        .backend()
    }

  private def getProbe(branch: Option[String]): ZIO[Tracing, Throwable, Response[Either[String, String]]] =
    backend.flatMap { b =>
      val base = basicRequest.get(uri"http://localhost/probe")
      branch.fold(base)(v => base.header("X-Branch", v)).send(b)
    }

  /** The rejection body, decoded. A `Left` body is the error output, which on
    * this path must be a `ValidationRejected` rather than text. */
  private def rejection(response: Response[Either[String, String]]): Option[WireError.ValidationRejected] =
    response.body.left.toOption.flatMap(_.fromJson[WireError.ValidationRejected].toOption)

  override def spec = suite("WireDecodeFailureHandler")(

    suite("toValidationError")(
      test("an absent input is required") {
        val error = WireDecodeFailureHandler.toValidationError(DecodeResult.Missing, branchInput)
        assertTrue(
          error.field == "X-Branch",
          error.code == ValidationErrorCode.REQUIRED_FIELD
        )
      },
      test("a value the codec could not read is a type mismatch") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Error("not-a-branch", new RuntimeException("boom")),
          branchInput
        )
        assertTrue(error.code == ValidationErrorCode.TYPE_MISMATCH)
      },
      test("a value of the wrong shape is a type mismatch") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Mismatch("main", "whatever"),
          branchInput
        )
        assertTrue(error.code == ValidationErrorCode.TYPE_MISMATCH)
      },
      test("several values where one was expected is a type mismatch") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Multiple(List("main", "other")),
          branchInput
        )
        assertTrue(error.code == ValidationErrorCode.TYPE_MISMATCH)
      },
      test("a value read and then refused is an invalid format") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.InvalidValue(Nil),
          branchInput
        )
        assertTrue(error.code == ValidationErrorCode.INVALID_FORMAT)
      },
      test("no message repeats the value that was rejected") {
        val rejected = "a-value-a-client-supplied"
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Error(rejected, new RuntimeException(rejected)),
          branchInput
        )
        assertTrue(!error.message.contains(rejected))
      }
    ),

    suite("the request path")(
      test("a missing required header is answered with a typed rejection, not text") {
        for
          response <- getProbe(branch = None)
          body      = rejection(response)
        yield assertTrue(
          response.code.code == 400,
          body.isDefined,
          body.exists(_.errors.exists(_.field == "X-Branch")),
          body.exists(_.errors.exists(_.code == ValidationErrorCode.REQUIRED_FIELD))
        )
      },
      test("the rejection carries a trace") {
        for
          response <- getProbe(branch = None)
          body      = rejection(response)
        yield assertTrue(body.map(_.trace.value).exists(_.nonEmpty))
      },
      test("a well-formed request still reaches the logic") {
        for
          response <- getProbe(branch = Some("main"))
        yield assertTrue(response.code.code == 200)
      }
    )
  ).provideLayer(telemetry.orDie)
