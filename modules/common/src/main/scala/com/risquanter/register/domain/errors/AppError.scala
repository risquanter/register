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
  * the wire case would give one level to all of them.
  */
enum FailureOrigin:
  case Caller
  case Service

/** Every failure the application produces as a value.
  *
  * Plain data, and deliberately not a `Throwable`. An exception is how the
  * platform reports a defect; these are expected outcomes a caller is meant to
  * handle. Keeping the two apart is what lets a signature name what can go
  * wrong: `IO[AppError, A]` states the closed set, where `Task[A]` would admit
  * anything and a widening would be invisible.
  *
  * `logMessage` is the server's own text and belongs in the log. The name says
  * where it goes: it never crosses to a client, because the browser composes
  * what a user reads from the wire case and its typed values, so no internal
  * reason can leak through a free-text field (ADR-035).
  *
  * The trace a failure ran under is not held here. It is read from the active
  * span at the boundary that projects onto the wire, which is the only place an
  * effect exists to read it.
  */
sealed trait AppError:
  def logMessage: String

  /** Who this failure is attributable to. Abstract rather than defaulted: a new
    * case cannot compile until it says which it is, so no failure reaches the
    * log at a level nobody chose.
    */
  def origin: FailureOrigin

  /** Structured fields for this failure's log line, queryable rather than
    * embedded in `logMessage`. Empty unless a case has values worth filtering
    * on. A confidential internal identifier may appear here, because this
    * reaches a server log and never the wire (ADR-036).
    */
  def logAnnotations: Map[String, String] = Map.empty

sealed trait DomainError extends AppError

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

/** Validation failure with accumulated structured errors */
case class ValidationFailed(errors: List[ValidationError]) extends DomainError {
  override def logMessage: String = errors.map(ValidationError.describe).mkString("; ")
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Simulation execution failure - wraps underlying cause with context */
case class SimulationFailure(simulationId: String, cause: Throwable) extends DomainError {
  override def logMessage: String = s"Simulation $simulationId failed: ${cause.getMessage}"
  override def origin: FailureOrigin = FailureOrigin.Service
}
