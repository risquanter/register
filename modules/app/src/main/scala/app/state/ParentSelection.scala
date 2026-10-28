package app.state

import zio.prelude.Validation

import com.risquanter.register.domain.data.iron.{ValidationMessages, ValidationUtil}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef

/** A form's in-progress parent-field selection.
  *
  * The same relationship as `ParentRef`, which is what a submitted node
  * carries, and deliberately a different type. Two things separate them. The
  * payload here is `String`, because a `Var` holds what the user is typing and
  * a refined type cannot represent partially-typed input. And this type has a
  * third case: `Unset` means nothing has been chosen yet, which a submitted
  * node cannot express, and which keeps a system-forced correction
  * distinguishable from a deliberate choice of the same value (ADR-019).
  *
  * `Unset` is never submitted — `toParentRef` refuses it the same way an empty
  * name is refused.
  */
enum ParentSelection:
  case Root
  case Under(parent: String)
  case Unset

object ParentSelection:
  /** A saved node's own parent is always a definite, already-made choice —
    * never `Unset`. Used when populating a form from a saved leaf or
    * portfolio, and as the baseline `isFormDirty` compares the live form
    * against.
    */
  def fromSaved(parent: ParentRef): ParentSelection =
    parent match
      case ParentRef.Root          => ParentSelection.Root
      case ParentRef.Under(name)   => ParentSelection.Under(name.value.toString)

  /** The parent to submit, refined, or the refusal to show under the control.
    *
    * This is the one place the parent field crosses from text to a refined
    * value, so both forms' `toDraft` call it and neither refines a parent of
    * its own. The selection arrives with its location, so a refusal names the
    * form that submitted it.
    *
    * {{{
    * toParentRef(Located(Under("IT"), at))  // Success(ParentRef.Under(SafeName("IT")))
    * toParentRef(Located(Unset, at))        // Failure at `at` — the parent is required
    * }}}
    */
  def toParentRef(selection: Located[ParentSelection]): Validation[ValidationError, ParentRef] =
    selection.value match
      case ParentSelection.Root =>
        Validation.succeed(ParentRef.Root)
      case ParentSelection.Under(name) =>
        toValidation(selection.as(name).refine(ValidationUtil.refineName)).map(ParentRef.Under(_))
      case ParentSelection.Unset =>
        Validation.fail(selection.reject(ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.parentRequired))
