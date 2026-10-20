package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.FormState
import com.risquanter.register.domain.errors.{ErrorLocation, ValidationError}

/** Shared routing of submit-time validation errors into a form.
  *
  * Both forms show a refusal the same two ways: under the input it belongs to,
  * or in the submit banner when it belongs to none. This holds that split; each
  * form supplies the mapping from a location to its own inputs.
  */
object FormSubmitUtil:

  /** Show each error under the input its location names, and the first error
    * that names no input in the banner.
    *
    * @param fieldMapping the form's own location-to-input mapping. `None` means
    *                     this form has no input for that location, which sends
    *                     the error to the banner.
    */
  def routeErrors[F](
    form: FormState[F],
    errors: List[ValidationError],
    submitError: Var[Option[String]],
    fieldMapping: ErrorLocation => Option[F]
  ): Unit =
    val unrouted = errors.filterNot { err =>
      fieldMapping(err.location) match
        case Some(field) =>
          form.setSubmitFieldError(field, err.message)
          true
        case None => false
    }
    submitError.set(unrouted.headOption.map(_.message))
