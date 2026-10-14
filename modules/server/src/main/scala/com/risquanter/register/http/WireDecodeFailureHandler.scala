package com.risquanter.register.http

import zio.{Task, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import sttp.monad.MonadError
import sttp.tapir.{DecodeResult, EndpointIO, EndpointInput}
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.model.ValuedEndpointOutput

import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.ActiveTrace

/** Answers a request the decoders rejected with `WireError.ValidationRejected`,
  * so every failure this service sends has a declared shape.
  *
  * Validation happens in the Tapir codecs and happens once (ADR-001), which
  * means a body, header or path segment an Iron smart constructor refuses is
  * rejected before any handler runs. Tapir's own handler answers that with a
  * plain-text body, which is the one error on this API a client could not decode
  * and the one that does not carry a trace.
  *
  * Whether to answer at all is left to Tapir's default handler. A failure on a
  * path segment deliberately produces no response, because another endpoint may
  * still match the request, and answering there would turn a routing miss into a
  * rejection.
  */
object WireDecodeFailureHandler:

  /** The default handler, consulted only for its decision about whether this
    * failure should be answered. Its own output is never used.
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
          case Some(_) =>
            ActiveTrace.id(using tracing).map { trace =>
              val rejected: WireError.ValidationRejected = WireError.ValidationRejected(
                List(toValidationError(ctx.failure, ctx.failingInput)),
                trace
              )
              Some(ValuedEndpointOutput(BaseEndpoint.validationRejectedOut, rejected))
            }

  /** One `ValidationError` for the rejected input, naming it and why it was
    * refused.
    *
    * The code comes from the existing set rather than a new one: an input that
    * is absent is `REQUIRED_FIELD`, a value the codec could not read at all is
    * `TYPE_MISMATCH`, and a value it read and then refused is `INVALID_FORMAT` —
    * which is what every Iron refinement produces.
    *
    * Not private, so the mapping is testable without driving a request through
    * the whole stack.
    */
  private[http] def toValidationError(
    failure: DecodeResult.Failure,
    input:   EndpointInput[?]
  ): ValidationError =
    val (code, message) = failure match
      case DecodeResult.Missing =>
        (ValidationErrorCode.REQUIRED_FIELD, "is required")
      case DecodeResult.Multiple(vs) =>
        (ValidationErrorCode.TYPE_MISMATCH, s"expects a single value, and ${vs.size} were given")
      case DecodeResult.Mismatch(expected, _) =>
        (ValidationErrorCode.TYPE_MISMATCH, s"expects $expected")
      case DecodeResult.InvalidValue(errors) =>
        (ValidationErrorCode.INVALID_FORMAT, invalidValueMessage(errors))
      case DecodeResult.Error(_, _) =>
        (ValidationErrorCode.TYPE_MISMATCH, "could not be read")

    ValidationError(fieldName(input), code, message)

  /** The constraints a validator reported, rendered without the rejected value.
    *
    * The value itself is deliberately not echoed. It is client-supplied text,
    * and a message is a leakage surface whether or not the value came from the
    * same client (ADR-035).
    */
  private def invalidValueMessage(errors: List[sttp.tapir.ValidationError[?]]): String =
    errors match
      case Nil => "is not valid"
      case es  => es.map(e => e.validator.toString).mkString("fails ", ", ", "")

  /** The name a client would recognise for the input that was rejected.
    *
    * A header carries its own name, which is the case worth naming exactly: the
    * branch header and the user header are the two inputs a client most often
    * gets wrong. Anything else is described by Tapir's own rendering of the
    * input, which names its kind and its name together — "query parameter q",
    * "body" — and is stable across input shapes this does not enumerate.
    */
  private def fieldName(input: EndpointInput[?]): String =
    input match
      case EndpointIO.Header(name, _, _)        => name
      case EndpointIO.FixedHeader(header, _, _) => header.name
      case other                                => other.show
