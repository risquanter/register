package app.state

import com.risquanter.register.domain.data.iron.{BranchChoice, TreeId}

/** Decides whether loading a fetched tree into [[TreeBuilderState]] should
  * proceed silently, ask for confirmation, or proceed and clear the draft
  * preview.
  *
  * A pure function, so it is unit-testable without a Laminar/DOM harness.
  *
  * The branch is compared alongside the tree id: a scenario fork keeps the
  * same [[TreeId]] as its source branch but can carry different node content,
  * so the tree id alone cannot tell a post-submit reload apart from a branch
  * switch onto the same tree.
  */
enum TreeLoadDecision:
  /** Same tree, same branch as what's already loaded — reload without
    * confirmation and without clearing the draft (post-submit refresh).
    */
  case SameContext
  /** Different tree or different branch, and the builder has unsaved
    * content — ask before overwriting.
    */
  case NeedsConfirm
  /** Different tree or different branch, nothing unsaved — reload freely. */
  case ReloadClean

object TreeLoadPolicy:
  def decide(
    previousTreeId: Option[TreeId],
    previousBranch: BranchChoice,
    newTreeId:      TreeId,
    newBranch:      BranchChoice,
    isDirty:        Boolean
  ): TreeLoadDecision =
    if previousTreeId.contains(newTreeId) && previousBranch == newBranch then
      TreeLoadDecision.SameContext
    else if isDirty then
      TreeLoadDecision.NeedsConfirm
    else
      TreeLoadDecision.ReloadClean
