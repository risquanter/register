package app.views

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import app.components.{BranchBar, SplitPane}
import app.state.{AppConfigState, DistributionChartState, LoadState, ScenarioState, TreeBuilderState, TreeLoadDecision, TreeLoadPolicy, TreeViewState, WorkspaceState}
import com.risquanter.register.domain.data.{RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.{NodeId, ScenarioName}

/** Design view — tree creation and editing workflow.
  *
  * Structural wrapper that composes existing components into the
  * Design section layout:
  *
  *   SplitPane.horizontal(40% | 60%)
  *   ├── LEFT:  TreeBuilderView (forms)
  *   └── RIGHT: SplitPane.vertical(60% | 40%)
  *       ├── TOP:    TreeListView (dropdown selector) + TreePreview (live ASCII preview)
  *       └── BOTTOM: Placeholder panel for future distribution modelling chart
  *
  * Pure structural component — owns no state (ADR-019 Pattern 1).
  */
object DesignView:

  def apply(
    builderState: TreeBuilderState,
    treeViewState: TreeViewState,
    wsState: WorkspaceState,
    distributionChartState: DistributionChartState,
    scenarioState: ScenarioState,
    appConfigState: AppConfigState
  ): HtmlElement =
    val scenarioMenuOpen: Var[Boolean] = Var(false)

    // Branch of the tree content currently reflected in `builderState` — distinct
    // from `scenarioState.activeBranch`, which can move ahead of it while a confirm
    // dialog is pending or a fetch is in flight. See TreeLoadPolicy.
    val loadedBranch: Var[Option[ScenarioName.ScenarioName]] = Var(None)

    // Reverting `scenarioState.activeBranch` (declining a confirm dialog) would
    // otherwise re-fire TreeViewState's own branch-change reload and trigger a
    // second, redundant fetch — which, worse, would then be judged `SameContext`
    // (branch now matches `loadedBranch` again) and silently overwrite whatever
    // the user just declined to discard. suppressNextReload() suppresses exactly
    // that one echo.
    def revertBranch(): Unit =
      treeViewState.suppressNextReload()
      scenarioState.switchTo(loadedBranch.now())

    def commitLoad(tree: RiskTree): Unit =
      builderState.loadFromTree(tree)
      loadedBranch.set(scenarioState.activeBranch.now())

    def onNewTree(): Unit =
      val proceed = !builderState.isDirty || dom.window.confirm("Starting a new tree will clear your current draft. Continue?")
      if proceed then
        builderState.startNewTree()
        treeViewState.selectedTreeId.set(None)
        loadedBranch.set(scenarioState.activeBranch.now())

    // The previously-selected tree doesn't exist on the branch just switched to
    // (e.g. it was created after the fork point, or on a sibling branch). There's
    // nothing valid left to show — offer the same start-fresh flow as "+ New Tree",
    // or revert the branch switch if the user declines to lose a real draft.
    def handleTreeUnavailableOnBranch(): Unit =
      val proceed = !builderState.isDirty ||
        dom.window.confirm("This tree doesn't exist on the new branch. Starting a new tree will clear your current draft. Continue?")
      if proceed then
        builderState.startNewTree()
        treeViewState.selectedTreeId.set(None)
        loadedBranch.set(scenarioState.activeBranch.now())
      else
        treeViewState.selectedTreeId.set(builderState.editingTreeId.now())
        revertBranch()

    val previewPanel = div(
      cls := "design-preview-panel",
      TreeListView(treeViewState, Some(onNewTree)),
      TreePreview(builderState)
    )

    val scenarioLeftPanel = div(
      cls := "design-left-panel",
      // Click/Escape anywhere in this panel closes the scenario dropdown —
      // mirrors the `pickerOpenFor` click-outside pattern in TreeDetailView.
      onClick --> { _ => scenarioMenuOpen.set(false) },
      onKeyDown --> { ev => if ev.key == "Escape" then scenarioMenuOpen.set(false) },
      BranchBar.toolbar(scenarioState, appConfigState.scenariosEnabled.signal, scenarioMenuOpen),
      TreeBuilderView(builderState, treeViewState, wsState, distributionChartState, scenarioState)
    )

    div(
      cls := "design-view",
      // ── Load subscription: propagate selected tree → builder state ──
      // Bound to element lifetime (ADR-019: side effects in callbacks, not in .map).

      // Fetch the scenario list once a workspace exists (mirrors TreeViewState's
      // own load-on-key-available pattern; a fresh session has no key yet).
      wsState.keySignal.changes.collect { case Some(_) => () } --> { _ => scenarioState.refresh() },

      // Note: re-fetching the tree list/selected tree on a branch switch is owned
      // by TreeViewState itself (constructed with scenarioState.activeBranch.signal
      // in Main.scala) — not wired here, so every TreeViewState consumer gets it,
      // not just this view. This subscription only decides what the *resulting*
      // fetch should do to the builder.
      treeViewState.selectedTree.signal.changes --> {
        case LoadState.Loaded(tree) =>
          val previousId = builderState.editingTreeId.now()
          TreeLoadPolicy.decide(previousId, loadedBranch.now(), tree.id, scenarioState.activeBranch.now(), builderState.isDirty) match
            case TreeLoadDecision.SameContext =>
              // Same tree, same branch, reloaded after successful submit — do not
              // clear currentDraftVar so that the preview checkbox works immediately
              // after submission.
              commitLoad(tree)
            case TreeLoadDecision.ReloadClean =>
              builderState.currentDraftVar.set(None) // switching tree/branch — clear stale draft
              commitLoad(tree)
            case TreeLoadDecision.NeedsConfirm =>
              if dom.window.confirm("Loading this tree will clear your current draft. Continue?") then
                builderState.currentDraftVar.set(None)
                commitLoad(tree)
              else
                // Revert both the tree-dropdown pointer and the active branch to
                // whatever is still safely reflected in the builder.
                treeViewState.selectedTreeId.set(previousId)
                revertBranch()
        case LoadState.Failed("Tree not found") =>
          handleTreeUnavailableOnBranch()
        case _ => ()
      },
      SplitPane.horizontal(
        left = scenarioLeftPanel,
        right = SplitPane.vertical(
          top = previewPanel,
          bottom = DistributionChartView(distributionChartState),
          topPercent = 60
        ),
        leftPercent = 40
      )
    )
