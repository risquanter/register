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
import com.risquanter.register.domain.data.iron.{ScenarioName, TelemetryPlaceholders, ValidationMessages}
import com.risquanter.register.domain.errors.{ErrorLocation, ExpectedInput, FieldProblem, RequestHeaderName, RequestQueryParam, ValidationErrorCode}
import com.risquanter.register.http.codecs.IronTapirCodecs
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

  /** A scenario name no refinement accepts, long enough to break the length
    * bound and recognisable if any message repeats it. */
  private val ScenarioNameTooLong: String = "LEAKCANARY" + "x" * 128

  /** The `ValidationError` a real Iron codec's refusal maps to.
    *
    * `ScenarioName`'s codec is the representative: it reads the raw string
    * first and refines second, which is the shape all twelve share.
    */
  private def refusalOf(raw: String): Option[com.risquanter.register.domain.errors.ValidationError] =
    import IronTapirCodecs.given
    summon[Codec[String, ScenarioName.ScenarioName, CodecFormat.TextPlain]].decode(raw) match
      case failure: DecodeResult.Failure => Some(WireDecodeFailureHandler.toValidationError(failure, branchInput))
      case _                             => None

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
      // The span interceptor is part of the arrangement under test, not scenery:
      // the handler stamps the trace of the active span, and without a span open
      // every rejection would carry the unlinked placeholder. Production prepends
      // it in `Application.startServer`, so the stub does too.
      val options = ZioHttpServerOptions
        .customiseInterceptors[Any]
        .decodeFailureHandler(WireDecodeFailureHandler.handler(tracing))
        .prependInterceptor(RequestSpanInterceptor.interceptor(tracing))

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
  // The handler runs before any handler does, so it never has a tree and
  // always answers the request-level case.
  private def rejection(response: Response[Either[String, String]]): Option[WireError.RequestRejected] =
    response.body.left.toOption.flatMap(_.fromJson[WireError.RequestRejected].toOption)

  override def spec = suite("WireDecodeFailureHandler")(

    suite("toValidationError")(
      test("an absent input is required") {
        val error = WireDecodeFailureHandler.toValidationError(DecodeResult.Missing, branchInput)
        assertTrue(
          error.location == ErrorLocation.Header(RequestHeaderName.Branch),
          error.problem == FieldProblem.Required
        )
      },
      test("a value the codec could not read is unreadable as the declared type") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Error("not-a-branch", new RuntimeException("boom")),
          branchInput
        )
        assertTrue(
          error.problem == FieldProblem.Unreadable(ExpectedInput.Declared),
          FieldProblem.code(error.problem) == ValidationErrorCode.TYPE_MISMATCH
        )
      },
      /** `Mismatch` is the second of the two failure kinds carrying a value the
        * client submitted. Neither value reaches the problem: a closed case
        * carries no text at all.
        */
      test("a value of the wrong shape is unreadable, and carries neither value") {
        val submitted = "LEAKCANARY-the-value-a-client-sent"
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Mismatch("main", submitted),
          branchInput
        )
        assertTrue(
          error.problem == FieldProblem.Unreadable(ExpectedInput.Declared),
          !ValidationMessages.render(error.problem).contains(submitted)
        )
      },
      test("several values where one was expected is unreadable as a single value") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Multiple(List("main", "other")),
          branchInput
        )
        assertTrue(
          error.problem == FieldProblem.Unreadable(ExpectedInput.SingleValue),
          FieldProblem.code(error.problem) == ValidationErrorCode.TYPE_MISMATCH
        )
      },
      test("a value a declared validator refused, with no problem of ours behind it, is a declared constraint not met") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.InvalidValue(Nil),
          branchInput
        )
        assertTrue(
          error.problem == FieldProblem.DeclaredConstraintNotMet,
          FieldProblem.code(error.problem) == ValidationErrorCode.INVALID_FORMAT
        )
      },
      test("no sentence repeats the value that was rejected") {
        val rejected = "a-value-a-client-supplied"
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Error(rejected, new RuntimeException(rejected)),
          branchInput
        )
        assertTrue(!ValidationMessages.render(error.problem).contains(rejected))
      }
    ),

    /** The mapping from a Tapir input to a `RequestHeaderName` case, and from
      * that case to the spelling a client sends.
      *
      * Neither half is reached by the enum's own exhaustiveness: a wrong
      * pairing of input to case compiles, and a renamed case would silently
      * move the spelling if the spelling were derived from the name rather
      * than declared (ADR-037).
      */
    suite("a declared header reports at its own case")(
      test("each declared header maps to the case that names it") {
        def caseFor(name: String): ErrorLocation =
          WireDecodeFailureHandler.toValidationError(DecodeResult.Missing, header[String](name)).location
        assertTrue(
          caseFor("x-user-id") == ErrorLocation.Header(RequestHeaderName.UserId),
          caseFor("X-Branch") == ErrorLocation.Header(RequestHeaderName.Branch),
          caseFor("If-Match") == ErrorLocation.Header(RequestHeaderName.IfMatch)
        )
      },
      test("each case carries the spelling its endpoint declares") {
        assertTrue(
          RequestHeaderName.UserId.wireName == "x-user-id",
          RequestHeaderName.Branch.wireName == "X-Branch",
          RequestHeaderName.IfMatch.wireName == "If-Match"
        )
      },
      test("a header this application does not declare reports at the whole request") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Missing,
          header[String]("X-Not-Declared")
        )
        assertTrue(error.location == ErrorLocation.Whole)
      },
      /** `Content-Type` is the realistic instance: Tapir pins it itself, so it
        * arrives as a `FixedHeader` rather than as a declared input of ours. */
      test("a header Tapir pins itself reports at the whole request") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.Missing,
          header(sttp.model.Header.contentType(sttp.model.MediaType.ApplicationJson))
        )
        assertTrue(error.location == ErrorLocation.Whole)
      }
    ),

    /** The same mapping for a query parameter, which the endpoint definitions
      * close in the same way: the name comes from the definition, so each
      * declared spelling reaches its own case and nothing else does.
      */
    suite("a declared query parameter reports at its own case")(
      test("each declared parameter maps to the case that names it") {
        def caseFor(name: String): ErrorLocation =
          WireDecodeFailureHandler.toValidationError(DecodeResult.Missing, query[String](name)).location
        assertTrue(
          caseFor("n")                 == ErrorLocation.QueryParameter(RequestQueryParam.Limit),
          caseFor("at")                == ErrorLocation.QueryParameter(RequestQueryParam.At),
          caseFor("a")                 == ErrorLocation.QueryParameter(RequestQueryParam.SideA),
          caseFor("aAt")               == ErrorLocation.QueryParameter(RequestQueryParam.SideAPin),
          caseFor("b")                 == ErrorLocation.QueryParameter(RequestQueryParam.SideB),
          caseFor("bAt")               == ErrorLocation.QueryParameter(RequestQueryParam.SideBPin),
          caseFor("threshold")         == ErrorLocation.QueryParameter(RequestQueryParam.Threshold),
          caseFor("seedEntityId")      == ErrorLocation.QueryParameter(RequestQueryParam.SeedEntityId),
          caseFor("includeProvenance") == ErrorLocation.QueryParameter(RequestQueryParam.IncludeProvenance),
          caseFor("omitAbsent")        == ErrorLocation.QueryParameter(RequestQueryParam.OmitAbsent)
        )
      },
      test("the match is exact, because a query parameter name is case-sensitive") {
        val error = WireDecodeFailureHandler.toValidationError(DecodeResult.Missing, query[String]("aat"))
        assertTrue(error.location == ErrorLocation.Whole)
      },
      test("a parameter this application does not declare reports at the whole request") {
        val error = WireDecodeFailureHandler.toValidationError(DecodeResult.Missing, query[String]("notDeclared"))
        assertTrue(error.location == ErrorLocation.Whole)
      },
      test("a declared validator the parameter fails is a declared constraint not met at the parameter") {
        val error = WireDecodeFailureHandler.toValidationError(
          DecodeResult.InvalidValue(List(ValidationError(Validator.min(1), 0, Nil, None))),
          query[Int]("n").validate(Validator.min(1))
        )
        assertTrue(
          error.location == ErrorLocation.QueryParameter(RequestQueryParam.Limit),
          error.problem == FieldProblem.DeclaredConstraintNotMet
        )
      }
    ),

    suite("a refusal that a real Iron codec produced")(

      /** The cases above hand `toValidationError` a `DecodeResult` built by
        * hand, which tests the mapping and not what the codecs actually emit.
        * These drive a real codec, so the problem is checked against the value a
        * request would really carry: it crosses Tapir's one text slot encoded and
        * is read back as the case the refinement produced, which is the law of
        * the `refused` / `problemIn` pair.
        */
      test("carries the refinement's own problem, read back from the slot it crossed in") {
        val expected = ScenarioName.fromString(ScenarioNameTooLong).left.getOrElse(Nil).map(_.problem)
        assertTrue(
          expected.nonEmpty,
          refusalOf(ScenarioNameTooLong).map(_.problem) == expected.headOption
        )
      },
      test("is classified by that problem, not as unreadable") {
        assertTrue(
          refusalOf(ScenarioNameTooLong).map(e => FieldProblem.code(e.problem)).contains(ValidationErrorCode.INVALID_LENGTH)
        )
      },
      test("repeats no part of the submitted value") {
        assertTrue(
          !refusalOf(ScenarioNameTooLong).exists(e => ValidationMessages.render(e.problem).contains(ScenarioNameTooLong))
        )
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
          body.exists(_.errors.exists(_.location == ErrorLocation.Header(RequestHeaderName.Branch))),
          body.exists(_.errors.exists(_.problem == FieldProblem.Required))
        )
      },
      test("the rejection carries the trace of a real span") {
        for
          response <- getProbe(branch = None)
          body      = rejection(response)
          trace     = body.map(_.trace)
        yield assertTrue(
          trace.isDefined,
          // The unlinked placeholder is thirty-two zeros, which is non-empty, so
          // asserting non-emptiness would pass with no span open at all.
          !trace.contains(TelemetryPlaceholders.UnlinkedTrace)
        )
      },
      test("a well-formed request still reaches the logic") {
        for
          response <- getProbe(branch = Some("main"))
        yield assertTrue(response.code.code == 200)
      },

      /** A rejection never passes through `wireEither`, so the line the
        * projection boundary writes for a controller failure does not exist on
        * this path. Without one, a trace a caller quotes from a `400` would
        * match nothing in the log.
        */
      test("a rejected request writes one INFO line carrying the trace the response names") {
        for
          response <- getProbe(branch = None)
          body      = rejection(response)
          lines    <- ZTestLogger.logOutput
          written   = lines.filter(_.message().contains("Request rejected before any handler ran"))
        yield assertTrue(
          written.size == 1,
          written.forall(_.logLevel == LogLevel.Info),
          written.exists(_.message().contains("X-Branch")),
          body.exists(b => written.exists(_.annotations.get("trace_id").contains(b.trace.value)))
        )
      },

      test("a well-formed request writes no rejection line") {
        for
          _     <- getProbe(branch = Some("main"))
          lines <- ZTestLogger.logOutput
        yield assertTrue(
          !lines.exists(_.message().contains("Request rejected before any handler ran"))
        )
      }
    )
  ).provideLayer(telemetry.orDie)
