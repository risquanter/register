package com.risquanter.register.domain.errors

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.ValidationMessages

/** Who a failure is attributable to, which decides the level its log line is
  * written at (ADR-002).
  *
  * `Caller` is ordinary traffic: the caller sent something this service
  * correctly refused, so the line records what happened and nothing is wrong
  * here. `Service` is an operational signal: something this service depends on
  * failed, or it has a defect.
  *
  * The distinction is drawn on the internal failure rather than on the wire
  * case, because the wire is deliberately coarser: several failures share one
  * wire case, and they need not share an attribution. Reading the level from
  * the wire case would give one level to all of them. A failure's origin is
  * derived from its zone.
  */
enum FailureOrigin:
  case Caller
  case Service

/** One refusal: where it is and what it is.
  *
  * The one internal error type that is also a wire type: it crosses inside
  * `WireError.ValidationRejected`, because a form needs to know which field
  * failed and why, and that is the whole content of this type. Both halves are
  * closed sets, so no sentence crosses: the browser renders one from the
  * problem and its values, and a log line renders its own (ADR-035).
  */
case class ValidationError(
  location: ErrorLocation,
  problem: FieldProblem
)

object ValidationError:
  given codec: JsonCodec[ValidationError] = DeriveJsonCodec.gen[ValidationError]

  /** The refusal as diagnostic text, for a log line or a decoder's flattened
    * message: the location, then the rendered sentence.
    *
    * {{{
    * describe(ValidationError(requestField(FieldName.Name), FieldProblem.Required))
    * // [request.Name] A value is required
    * }}}
    */
  def describe(error: ValidationError): String =
    s"[${ErrorLocation.describe(error.location)}] ${ValidationMessages.render(error.problem)}"
