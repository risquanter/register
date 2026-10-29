package app.views

import com.raquo.laminar.api.L.*
import app.state.FormState
import com.risquanter.register.domain.errors.{ErrorLocation, ValidationError}

/** Shared routing of submit-time validation errors into a form.
  *
  * Both forms show a refusal the same two ways: under every target its location
  * names, or on the form's submit line when it names none. This holds that
  * split; each form supplies the mapping from a location to its own targets.
  */
object FormSubmitUtil:

  /** Show each error under every input its location names, and the first
    * error that names no input on the form's own line.
    *
    * @param fieldMapping the form's own location-to-inputs mapping. The empty
    *                     set means this form has no input for that location.
    */
  def routeErrors[Field](
    form:         FormState[Field],
    errors:       List[ValidationError],
    submitError:  Var[Option[String]],
    fieldMapping: ErrorLocation => Set[Field]
  ): Unit =
    val unrouted = errors.filter { err =>
      val targets = fieldMapping(err.location)
      targets.foreach(form.setSubmitFieldError(_, err.message))
      targets.isEmpty
    }
    submitError.set(unrouted.headOption.map(_.message))
