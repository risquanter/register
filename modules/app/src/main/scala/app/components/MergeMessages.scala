package app.components

import app.core.ViewFailure
import app.views.ErrorMessages
import com.risquanter.register.domain.data.iron.{SafeName, TreeId}
import com.risquanter.register.http.responses.MergeConflictEntry

/** Every sentence the merge modal shows.
  *
  * The server sends a case and the values that belong to it; the sentence is
  * written here rather than at the rendering site, so a sentence and the values
  * it interpolates cannot drift apart (ADR-037).
  */
object MergeMessages:

  val Checking: String =
    "Checking for conflicts…"

  val Clean: String =
    "No conflicts — merging applies this scenario's changes to main."

  val ScenarioMissing: String =
    "This scenario no longer exists."

  val DuplicateNamesHeading: String =
    "Merging would give two risks in one tree the same name:"

  val DuplicateNamesHint: String =
    "Rename one of them on either branch, then re-check."

  val ConflictsHint: String =
    "Merging is blocked until both branches agree on these paths — edit the node " +
    "on either branch to match the other, then re-check."

  /** The conflict check did not produce an answer. The reason is composed from
    * the failure itself, so this sentence names what the modal was doing and
    * borrows the wording for why it stopped.
    */
  def previewFailed(failure: ViewFailure): String =
    s"Conflict check failed: ${ErrorMessages.viewFailed(failure)}"

  def conflictsHeading(paths: List[MergeConflictEntry]): String =
    val noun = if paths.size == 1 then "path" else "paths"
    s"${paths.size} $noun changed on both branches since the fork:"

  /** The repeated names, grouped per tree and ordered, so two previews of the
    * same conflict read identically.
    */
  def duplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]]): String =
    perTree.toList
      .sortBy((treeId, _) => treeId.value)
      .map((_, names) => names.map(_.value).sorted.mkString(", "))
      .mkString("; ")
