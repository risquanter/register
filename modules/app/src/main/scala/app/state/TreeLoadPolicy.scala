package app.state

import com.risquanter.register.domain.data.iron.{TreeId, ScenarioName}

/** Decides whether loading a fetched tree into [[TreeBuilderState]] should
  * proceed silently, ask for confirmation, or proceed and clear the draft
  * preview.
  *
  * Pulled out of `DesignView` as a pure function so it is unit-testable
  * without a Laminar/DOM harness (see `docs/dev/TODO.md` item 25).
  *
  * Branch ID is included alongside tree ID (added 2026-07-21, milestone-2b
  * Phase B follow-up): a scenario fork keeps the same [[TreeId]] as its
  * source branch but can carry different node content, so tree ID alone is
  * not enough to tell "the same submit response reloading" apart from
  * "the user switched branches and the same tree now has different content".
  * Before this, switching branches never re-fetched anything at all, so this
  * distinction was never exercised — there was no code path that triggered
  * it, so it went untested.
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
    previousBranch: Option[ScenarioName.ScenarioName],
    newTreeId:      TreeId,
    newBranch:      Option[ScenarioName.ScenarioName],
    isDirty:        Boolean
  ): TreeLoadDecision =
    if previousTreeId.contains(newTreeId) && previousBranch == newBranch then
      TreeLoadDecision.SameContext
    else if isDirty then
      TreeLoadDecision.NeedsConfirm
    else
      TreeLoadDecision.ReloadClean
