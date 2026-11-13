package com.risquanter.register.http

import zio.{Task, UIO, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.{DecodeResult, EndpointIO, EndpointInput, ValidationError as TapirValidationError}
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.model.ValuedEndpointOutput

import com.risquanter.register.domain.errors.{ErrorLocation, ExpectedInput, FieldProblem, RequestHeaderName, RequestQueryParam, ValidationError}
import com.risquanter.register.http.codecs.IronTapirCodecs
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.ActiveTrace

/** Answers a request the decoders rejected with `WireError.RequestRejected`, so
  * every failure this service sends has a declared shape.
  *
  * Validation happens in the Tapir codecs and happens once (ADR-001), which
  * means a body, header or path segment an Iron smart constructor refuses is
  * rejected before any handler runs. Tapir's own handler answers that with a
  * plain-text body, which is the one error on this API a client could not decode
  * and the one that does not carry a trace.
  *
  * Whether to answer at all is left to Tapir's default handler, and so is the
  * status. A failure on a path segment deliberately produces no response,
  * because another endpoint may still match the request, and answering there
  * would turn a routing miss into a rejection.
  *
  * Only the body is this handler's own. The status the default handler computed
  * belongs to the input that was rejected, so it is carried rather than
  * replaced: nearly every rejection is `400`, and the rest are decided by what
  * the endpoint declares.
  */
object WireDecodeFailureHandler:

  /** The default handler, consulted for two things: whether this failure should
    * be answered at all, and the status and headers it should be answered with.
    * Only its plain-text body is unused.
    */
  private val tapirDefault = DefaultDecodeFailureHandler[Task]

  /** The handler to pass to `ZioHttpServerOptions.customiseInterceptors`.
    *
    * `Tracing` is a parameter rather than an environment requirement because a
    * Tapir handler's effect type is `Task`, whose environment is `Any`.
    */
  def handler(tracing: Tracing): DecodeFailureHandler[Task] =
    new DecodeFailureHandler[Task]:
      override def apply(ctx: DecodeFailureContext)(using
        monad: MonadError[Task]
      ): Task[Option[ValuedEndpointOutput[?]]] =
        tapirDefault.respond(ctx) match
          case None => ZIO.none
          case Some((status, headers)) =>
            val error = toValidationError(ctx.failure, ctx.failingInput)
            logIfChallengeAdvertised(status) *>
              logRejection(error, ctx.failingInput, status) *>
              ActiveTrace.id(using tracing).map { trace =>
                // This handler runs before any handler does, so it never has a
                // tree to name and always answers the request-level case.
                val rejected: WireError.RequestRejected =
                  WireError.RequestRejected(List(error), trace)
                Some(ValuedEndpointOutput(BaseEndpoint.rejectionOut, (status, headers, rejected)))
              }

  /** Records a request the decoders rejected, so a failure that becomes a
    * response is logged wherever it was produced.
    *
    * A rejected request is caller-caused by construction, which is `INFO`
    * (ADR-002). There is no `Failure` here to read an origin from, because the
    * request never reached a handler.
    *
    * The input is named by Tapir's own rendering of it, because the location
    * names only a declared header or query parameter and the log line should
    * say which body or path segment was refused as well.
    *
    * The trace is not passed in: the span interceptor sits outside this handler
    * and applies it as a log annotation, so the line already carries the same
    * trace the response names.
    */
  private def logRejection(error: ValidationError, input: EndpointInput[?], status: StatusCode): UIO[Unit] =
    ZIO.logInfo(
      s"Request rejected before any handler ran, answered $status: " +
        s"${input.show} ${ValidationError.describe(error)} (${FieldProblem.code(error.problem).code})"
    )

  /** Records a `401` as a configuration defect of this service.
    *
    * A `401` from the decoders means the endpoint declares an authentication
    * input, which only `Tapir.auth` creates. This service validates no
    * credential: a token is checked at the mesh and its claims arrive as
    * ordinary headers (ADR-012), so an endpoint that advertises a challenge is
    * asking a client for something nothing here will read. The status is still
    * answered, because suppressing it would leave the request unanswered, but
    * it is logged, because the condition is a mistake in this service rather
    * than in the request.
    */
  private def logIfChallengeAdvertised(status: StatusCode): UIO[Unit] =
    ZIO
      .logError(
        "An endpoint declares an authentication input, so a decode failure answered 401 with a challenge. " +
          "Credential validation belongs to the mesh and this service reads claims as headers."
      )
      .when(status == StatusCode.Unauthorized)
      .unit

  /** One `ValidationError` for the rejected input: where it is and what it is.
    *
    * An absent input is `Required`. One the codec could not read at all is
    * `Unreadable`: several values where one was expected, or text the declared
    * type does not read. A value the codec read and then refused carries the
    * refinement's own problem, which `IronTapirCodecs.refused` put in the
    * `customMessage` slot and `problemIn` reads back; a validator declared on
    * the endpoint itself carries no problem, and reports that a declared
    * constraint was not met.
    *
    * Visible to the package, so the mapping is testable without driving a
    * request through the whole stack.
    */
  private[http] def toValidationError(
    failure: DecodeResult.Failure,
    input:   EndpointInput[?]
  ): ValidationError =
    val problem = failure match
      case DecodeResult.Missing              => FieldProblem.Required
      case DecodeResult.Multiple(_)          => FieldProblem.Unreadable(ExpectedInput.SingleValue)
      case DecodeResult.Mismatch(_, _)       => FieldProblem.Unreadable(ExpectedInput.Declared)
      case DecodeResult.InvalidValue(errors) => constraintBroken(errors)
      case DecodeResult.Error(_, _)          => FieldProblem.Unreadable(ExpectedInput.Declared)
    ValidationError(locationOf(input), problem)

  /** What the refused value broke, as the problem whatever refused it reported.
    *
    * An Iron refinement puts its problem's encoded form in `customMessage`,
    * which is the branch every refusal from `IronTapirCodecs` takes. A
    * validator declared on a schema instead carries no message, and the
    * constraint it states is not one of this application's problems. The first
    * problem is kept, because one input is answered with one refusal. The
    * rejected value is not read: it is client-supplied text, and a refusal is a
    * disclosure surface whether or not the value came from the same client
    * (ADR-035).
    */
  private def constraintBroken(errors: List[TapirValidationError[?]]): FieldProblem =
    errors
      .flatMap(e => e.customMessage.flatMap(IronTapirCodecs.problemIn))
      .headOption
      .getOrElse(FieldProblem.DeclaredConstraintNotMet)

  /** Where the rejected input is, as a location the browser can match on.
    *
    * A header or a query parameter this application declares has a case of its
    * own, drawn from the closed set the endpoint definitions fix. Every other
    * input — a path segment, the body, and a header or parameter this
    * application does not declare — has no closed set to draw a name from, so
    * it reports at the request as a whole.
    */
  private def locationOf(input: EndpointInput[?]): ErrorLocation =
    input match
      case EndpointIO.Header(name, _, _)        => headerLocation(name)
      case EndpointIO.FixedHeader(header, _, _) => headerLocation(header.name)
      case EndpointInput.Query(name, _, _, _)   =>
        RequestQueryParam.fromWireName(name).fold(ErrorLocation.Whole)(ErrorLocation.QueryParameter(_))
      case _                                    => ErrorLocation.Whole

  private def headerLocation(name: String): ErrorLocation =
    RequestHeaderName.fromWireName(name).fold(ErrorLocation.Whole)(ErrorLocation.Header(_))
