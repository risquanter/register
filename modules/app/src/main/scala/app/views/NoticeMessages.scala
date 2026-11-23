package app.views

/** The sentences a view shows beside a control to say what it is showing.
  * `BlockedMessages` says why a control is disabled and `ErrorMessages` what
  * failed; a notice is neither. */
object NoticeMessages:

  /** Ticked nodes the tree at the pinned commit does not hold. */
  def droppedSelections(count: Int): String =
    s"$count selected node(s) not present at this point in time"

  /** Mitigations a column names that the tree at the pinned commit does not hold. */
  def absentMitigations(count: Int): String =
    s"$count selected mitigation(s) not present at this point in time"

  /** A card whose grid follows the baseline's. */
  val SelectionMirrored: String = "Ticks mirror the baseline — turn Mirror off to edit them."

  /** A cell the query's match keeps ticked. */
  val MatchedByQuery: String = "Matched by the query."
