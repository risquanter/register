package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.FormState
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.frontend.TreeBuilderLogic

/** Shared utilities for form submit error routing.
  *
  * Routes topology validation errors to per-field red borders via
  * `TreeBuilderLogic.formFieldFor`. Errors that don't map to a form field
  * (structural errors like "tree.portfolios" empty-collection) are returned
  * as unrouted and typically displayed in the submit-error banner.
  */
object FormSubmitUtil:

  /** Route topology errors: field-bound errors go to per-field submit errors,
    * unrouted (structural) errors go to the submit-error banner.
    */
  def routeTopologyErrors(
    form: FormState,
    errors: List[ValidationError],
    submitError: Var[Option[String]]
  ): Unit =
    val unrouted = errors.filterNot { err =>
      TreeBuilderLogic.formFieldFor(err.field) match
        case Some(fieldName) =>
          form.setSubmitFieldError(fieldName, err.message)
          true
        case None => false
    }
    submitError.set(unrouted.headOption.map(_.message))
