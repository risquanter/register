package app.views

/** The sentences a view shows beside a control to say what it is showing.
  * `BlockedMessages` says why a control is disabled and `ErrorMessages` what
  * failed; a notice is neither. */
object NoticeMessages:

  /** Charted nodes the tree at the pinned commit does not hold. */
  def droppedSelections(count: Int): String =
    s"$count selected node(s) not present at this point in time"

  /** Selected mitigations the tree at the pinned commit does not hold. */
  def absentMitigations(count: Int): String =
    s"$count selected mitigation(s) not present at this point in time"

  /** A row whose selection follows the baseline's. */
  val SelectionMirrored: String = "Selection mirrors the baseline — turn Mirror off to select manually."
