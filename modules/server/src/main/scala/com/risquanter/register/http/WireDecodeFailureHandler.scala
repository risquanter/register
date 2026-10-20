package com.risquanter.register.http

import zio.{Task, UIO, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.{DecodeResult, EndpointIO, EndpointInput, ValidationError as TapirValidationError}
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.model.ValuedEndpointOutput

import com.risquanter.register.domain.errors.{ErrorLocation, RequestHeaderName, ValidationError, ValidationErrorCode}
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
              logRejection(error, status) *>
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
    * (ADR-002). There is no `AppError` here to read an origin from, because the
    * request never reached a handler.
    *
    * The trace is not passed in: the span interceptor sits outside this handler
    * and applies it as a log annotation, so the line already carries the same
    * trace the response names.
    */
  private def logRejection(error: ValidationError, status: StatusCode): UIO[Unit] =
    ZIO.logInfo(
      s"Request rejected before any handler ran, answered $status: " +
        s"${ErrorLocation.describe(error.location)} ${error.message} (${error.code.code})"
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

  /** One `ValidationError` for the rejected input, naming it and why it was
    * refused.
    *
    * The code comes from the existing set rather than a new one: an input that
    * is absent is `REQUIRED_FIELD`, a value the codec could not read at all is
    * `TYPE_MISMATCH`, and a value it read and then refused is `INVALID_FORMAT` —
    * which is what every Iron refinement produces.
    *
    * Only a declared header has a location that names the input. Every other
    * input reports at the request as a whole, so for those the message opens
    * with the input's own description and the name travels in the sentence.
    *
    * Visible to the package, so the mapping is testable without driving a
    * request through the whole stack.
    */
  private[http] def toValidationError(
    failure: DecodeResult.Failure,
    input:   EndpointInput[?]
  ): ValidationError =
    val (code, reason) = failure match
      case DecodeResult.Missing =>
        (ValidationErrorCode.REQUIRED_FIELD, "is required")
      case DecodeResult.Multiple(vs) =>
        (ValidationErrorCode.TYPE_MISMATCH, s"expects a single value, and ${vs.size} were given")
      case DecodeResult.Mismatch(expected, _) =>
        (ValidationErrorCode.TYPE_MISMATCH, s"expects $expected")
      case DecodeResult.InvalidValue(errors) =>
        (ValidationErrorCode.INVALID_FORMAT, constraintBroken(errors))
      case DecodeResult.Error(_, _) =>
        (ValidationErrorCode.TYPE_MISMATCH, "could not be read")

    val location = locationOf(input)
    // The name is said once. A declared header is named by the location, so
    // repeating it in the sentence would say it twice; everything else reports
    // at the request as a whole, where the sentence is the only place left.
    val message = location match
      case ErrorLocation.Header(_) => reason
      case _                       => s"${inputName(input)} $reason"

    ValidationError(location, code, message)

  /** What the refused value broke, in the words of whatever refused it.
    *
    * An Iron refinement puts its own sentence in `customMessage`, which is the
    * branch every refusal from `IronTapirCodecs` takes. A validator declared on
    * a schema instead carries no message and renders through `show`, which
    * states the constraint — `length<=30`. Either way the rejected value is not
    * echoed: it is client-supplied text, and a message is a disclosure surface
    * whether or not the value came from the same client (ADR-035).
    */
  private def constraintBroken(errors: List[TapirValidationError[?]]): String =
    errors.flatMap(e => e.customMessage.orElse(e.validator.show)) match
      case Nil      => "is not valid"
      case messages => messages.mkString("; ")

  /** Where the rejected input is, as a location the browser can match on.
    *
    * A header this application declares is the one input with a case of its
    * own, and it is the case worth having: the branch header and the user
    * header are the two inputs a client most often gets wrong. Every other
    * input — a query parameter, a path segment, the body, and a header this
    * application does not declare — has no closed set to draw a name from, so
    * it reports at the request as a whole and `inputName` puts Tapir's
    * description in the message.
    */
  private def locationOf(input: EndpointInput[?]): ErrorLocation =
    headerNameOf(input).flatMap(RequestHeaderName.fromWireName) match
      case Some(header) => ErrorLocation.Header(header)
      case None         => ErrorLocation.Whole

  /** The name a client would recognise for the input that was rejected.
    *
    * A header carries its own name. Anything else is described by Tapir's own
    * rendering of the input, which names its kind and its name together —
    * "query parameter q", "body" — and is stable across input shapes this does
    * not enumerate.
    */
  private def inputName(input: EndpointInput[?]): String =
    headerNameOf(input).getOrElse(input.show)

  private def headerNameOf(input: EndpointInput[?]): Option[String] =
    input match
      case EndpointIO.Header(name, _, _)        => Some(name)
      case EndpointIO.FixedHeader(header, _, _) => Some(header.name)
      case _                                    => None
