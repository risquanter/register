package app.state

import com.risquanter.register.domain.errors.{ErrorLocation, ValidationError, ValidationErrorCode}

/** A value together with the location a refusal about it names.
  *
  * A form field's value is transformed between being read and being refined —
  * text is parsed, then rescaled, before a refinement sees it — and the location
  * has to survive that without being named again. `map` and `as` carry it, so
  * the field is named once, where the value is read, and no later step can pair
  * one field's value with another field's location.
  *
  * {{{
  * val input = Located("90", ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.Probability))
  * input.map(_.toDouble / 100.0)  // Located(0.9, Field(DraftNode, Probability))
  * }}}
  */
case class Located[+A](value: A, location: ErrorLocation):

  /** The value transformed, at the same location. */
  def map[B](f: A => B): Located[B] = Located(f(value), location)

  /** `map`'s constant form, for a step whose replacement value is already in
    * hand — a parse result, which was computed to decide whether the step
    * failed at all. */
  def as[B](b: B): Located[B] = Located(b, location)

  /** A refusal about this value, at its own location. The location is not a
    * parameter, so a refusal about a located value cannot name a different
    * place than the value came from. */
  def reject(code: ValidationErrorCode, message: String): ValidationError =
    ValidationError(location, code, message)

  /** Hand the pair to a refinement that takes a value and a location
    * separately, which is every method in `ValidationUtil`. Both arguments come
    * from one value, so a caller cannot pass one field's location beside
    * another field's value.
    *
    * {{{
    * probabilityLocated.map(RiskLeafFormState.pctToDomain)
    *   .refine(ValidationUtil.refineOccurrenceProbability)
    * }}}
    */
  def refine[B](
    rule: (A, ErrorLocation) => Either[List[ValidationError], B]
  ): Either[List[ValidationError], B] = rule(value, location)
