package app.state

/** A form's in-progress parent-field selection.
  *
  * Distinct from the domain-level shape (`Option[String]`, where `None`
  * means root) because a form needs a third state the domain doesn't:
  * "nothing chosen yet, and nothing to silently default to." Collapsing that
  * into `Option[String]` — as the domain correctly does, and as this form
  * layer used to as well — is what let a system-forced correction and a
  * genuine, deliberate user choice become indistinguishable by value alone:
  * both ended up as the same `Option[String]`, with no way to tell "the
  * system put this here" apart from "the user chose the same thing on
  * purpose" (see docs/dev/ADR-019 Pattern 6).
  *
  * `Unset` is never a valid value to submit — `PortfolioFormState.toDraft`
  * and `RiskLeafFormState.parentDraft` both fail validation on it, the same
  * way an empty name does. It exists purely as the explicit "not decided
  * yet" state a freshly-cleared or freshly-templated form starts in.
  */
enum ParentSelection:
  case Root
  case Portfolio(name: String)
  case Unset

object ParentSelection:
  /** A saved node's own parent is always a definite, already-made choice —
    * never `Unset`. Used when populating a form from a saved leaf/portfolio,
    * and as the baseline `isFormDirty` compares the live form against.
    */
  def fromSaved(parent: Option[String]): ParentSelection =
    parent match
      case None       => ParentSelection.Root
      case Some(name) => ParentSelection.Portfolio(name)

  /** `None` only for `Unset` — callers route that to a "Parent is required"
    * validation error instead of silently defaulting to root or to some
    * arbitrary existing portfolio.
    */
  def toSaved(selection: ParentSelection): Option[Option[String]] =
    selection match
      case ParentSelection.Root         => Some(None)
      case ParentSelection.Portfolio(n) => Some(Some(n))
      case ParentSelection.Unset        => None
