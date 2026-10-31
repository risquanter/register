package app.views

import app.state.FormKind

/** Every sentence that says why a button is disabled.
  *
  * A sentence that names a form kind is a function of it, so the words and the
  * kind cannot drift apart (ADR-037).
  */
object BlockedMessages:

  private def noun(kind: FormKind): String = kind match
    case FormKind.Leaf      => "leaf"
    case FormKind.Portfolio => "portfolio"

  // ── The node forms ──

  def editInProgress(kind: FormKind): String =
    s"Save or clear the ${noun(kind)} you are editing first."

  def otherDraftInProgress(other: FormKind): String =
    s"Submit or clear the ${noun(other)} draft first."

  def nothingSelected(kind: FormKind): String =
    s"Select a ${noun(kind)} in the tree to edit it."

  def copyNotEditable(kind: FormKind): String =
    s"A copy is submitted as a new ${noun(kind)}; it cannot be edited in place."

  def savedNothingToClear(kind: FormKind): String =
    s"Nothing to clear: the ${noun(kind)} shown is saved."

  val NothingToClear: String = "Nothing to clear."

  // ── The tree builder ──

  val TreeSaving: String = "Saving the tree…"

  // ── Analyze ──

  val QueryEvaluating: String = "Evaluating the query…"

  val QueryBlank: String = "Type a query first."

  def compareFull(slots: Int): String =
    s"At most $slots trees can be compared."

  // ── The merge modal ──

  val MergeConflicts: String = "Resolve the conflicts shown above first."

  val MergeNotChecked: String = "Re-check the scenario for conflicts first."

  val MergeChecking: String = "Checking for conflicts…"

  val MergeRunning: String = "Merging…"
