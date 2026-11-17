package app.views

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import app.components.{AbsoluteStatementList, BranchBar, MergeModal, SplitPane}
import app.state.{AppConfigState, DistributionChartState, LoadState, OverrideEditEvent, OverrideEditState, ScenarioMergeState, ScenarioState, TreeBuilderState, TreeLoadDecision, TreeLoadPolicy, TreeViewState, WorkspaceState}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{BranchChoice, MitigationId, NodeId, SafeName}

/** Design view — tree creation and editing workflow.
  *
  * Structural wrapper that composes existing components into the
  * Design section layout:
  *
  *   SplitPane.horizontal(40% | 60%)
  *   ├── LEFT:  TreeBuilderView (forms)
  *   └── RIGHT: SplitPane.vertical(60% | 40%)
  *       ├── TOP:    TreeListView (dropdown selector) + TreePreview (live ASCII
  *       │           preview) + the loaded tree's absolute statements
  *       └── BOTTOM: Placeholder panel for future distribution modelling chart
  *
  * The override edit popup opens over the view on one absolute statement; its
  * state is `overrideEditState`, which the Analyze view's link also moves.
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
    mergeState: ScenarioMergeState,
    appConfigState: AppConfigState,
    overrideEditState: OverrideEditState
  ): HtmlElement =
    val scenarioMenuOpen: Var[Boolean] = Var(false)

    // Branch of the tree content currently reflected in `builderState` — distinct
    // from `scenarioState.activeBranch`, which can move ahead of it while a confirm
    // dialog is pending or a fetch is in flight. See TreeLoadPolicy.
    val loadedBranch: Var[BranchChoice] = Var(BranchChoice.Main)

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
      val held = builderState.absoluteStatementIds
      overrideEditState.dispatch(OverrideEditEvent.TreeLoaded(tree.id, held), held)

    /** The builder starts a new, empty tree: nothing is held, so the popup
      * closes and a pending request is dropped. */
    def clearForNewTree(): Unit =
      builderState.startNewTree()
      treeViewState.selectedTreeId.set(None)
      loadedBranch.set(scenarioState.activeBranch.now())
      overrideEditState.dispatch(OverrideEditEvent.TreeCleared, Set.empty)

    def onNewTree(): Unit =
      val proceed = !builderState.hasUnsavedWork || dom.window.confirm("Starting a new tree will clear your current draft. Continue?")
      if proceed then clearForNewTree()

    // The previously-selected tree doesn't exist on the branch just switched to
    // (e.g. it was created after the fork point, or on a sibling branch). There's
    // nothing valid left to show — offer the same start-fresh flow as "+ New Tree",
    // or revert the branch switch if the user declines to lose a real draft.
    def handleTreeUnavailableOnBranch(): Unit =
      val proceed = !builderState.hasUnsavedWork ||
        dom.window.confirm("This tree doesn't exist on the new branch. Starting a new tree will clear your current draft. Continue?")
      if proceed then clearForNewTree()
      else
        treeViewState.selectedTreeId.set(builderState.editingTreeId.now())
        revertBranch()

    /** The absolute statements of the tree the builder loaded, read when the
      * popup is asked to open. */
    def heldNow(): Set[MitigationId] = builderState.absoluteStatementIds

    // The stale badge comes from the structure read of the tree the builder
    // loaded, and an anchor is named as that tree names it. A tree the tree
    // view holds but the builder did not load, such as one whose load the user
    // declined, gives no badge and no name.
    val staleIds: Signal[Set[MitigationId]] =
      treeViewState.structure.combineWith(builderState.editingTreeId.signal).map {
        case (LoadState.Loaded(response), Some(loaded)) if response.tree.id == loaded => response.staleMitigationIds
        case _                                                                        => Set.empty
      }
    val nodeName: Signal[NodeId => Option[SafeName.SafeName]] =
      treeViewState.selectedTree.combineWith(builderState.editingTreeId.signal).map {
        case (LoadState.Loaded(tree), Some(loaded)) if tree.id == loaded => id => tree.index.nodes.get(id).map(_.name)
        case _                                                           => _ => None
      }

    val previewPanel = div(
      cls := "design-preview-panel",
      TreeListView(treeViewState, Some(onNewTree), leadingControl = None),
      TreePreview(builderState),
      AbsoluteStatementList(
        builderState.absoluteStatements,
        staleIds,
        nodeName,
        id => overrideEditState.dispatch(OverrideEditEvent.OpenOn(id), heldNow()),
        builderState.isEditDirtyVar.signal
      )
    )

    val scenarioLeftPanel = div(
      cls := "design-left-panel",
      // Click/Escape anywhere in this panel closes the scenario dropdown —
      // mirrors the `pickerOpenFor` click-outside pattern in TreeDetailView.
      onClick --> { _ => scenarioMenuOpen.set(false) },
      onKeyDown --> { ev => if ev.key == "Escape" then scenarioMenuOpen.set(false) },
      BranchBar.toolbar(scenarioState, appConfigState.scenariosEnabled.signal, scenarioMenuOpen, mergeState.open),
      TreeBuilderView(builderState, treeViewState, wsState, distributionChartState, scenarioState, appConfigState.debugUi.signal)
    )

    // On a successful merge: close the modal and put the view on main, where
    // the merged result now lives. The branch switch flows through the same
    // TreeLoadPolicy subscription below as a manual switch — including its
    // unsaved-draft confirm, which can only trigger for uncommitted form
    // edits (committed scenario edits were already saved to the branch).
    def onMerged(): Unit =
      mergeState.close()
      scenarioState.switchTo(BranchChoice.Main)

    div(
      cls := "design-view",
      MergeModal(mergeState, onMerged),
      // Applying records the figures in the builder, so the tree's save
      // re-affirms the statement; the popup then closes.
      OverridePopup(
        overrideEditState.edit.signal,
        builderState.absoluteStatements,
        nodeName,
        (id, figures) =>
          builderState.affirm(id, figures)
          overrideEditState.dispatch(OverrideEditEvent.Applied, heldNow()),
        () => overrideEditState.dispatch(OverrideEditEvent.Cancelled, heldNow())
      ),
      // ── Load subscription: propagate selected tree → builder state ──
      // Bound to element lifetime (ADR-019: side effects in callbacks, not in .map).

      // Note: refreshing the scenario list and the tree list/selected tree once a
      // workspace key appears is owned by ScenarioState/TreeViewState themselves
      // (each self-refreshes on `keySignal.changes` internally) — not wired here,
      // so every consumer of either state gets it, not just this view.

      // Note: re-fetching the tree list/selected tree on a branch switch is owned
      // by TreeViewState itself (constructed with scenarioState.activeBranch.signal
      // in Main.scala) — not wired here, so every TreeViewState consumer gets it,
      // not just this view. This subscription only decides what the *resulting*
      // fetch should do to the builder.
      treeViewState.selectedTree.changes --> {
        case LoadState.Loaded(tree) =>
          val previousId = builderState.editingTreeId.now()
          TreeLoadPolicy.decide(previousId, loadedBranch.now(), tree.id, scenarioState.activeBranch.now(), builderState.hasUnsavedWork) match
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
        case LoadState.TreeAbsent(_) =>
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
