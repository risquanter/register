package app.views

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import app.components.SplitPane
import app.state.{DistributionChartState, LoadState, TreeBuilderState, TreeViewState, WorkspaceState}
import com.risquanter.register.domain.data.{RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.NodeId

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
    distributionChartState: DistributionChartState
  ): HtmlElement =
    val previewPanel = div(
      cls := "design-preview-panel",
      TreeListView(treeViewState),
      TreePreview(builderState)
    )

    div(
      cls := "design-view",
      // ── Load subscription: propagate selected tree → builder state ──
      // Bound to element lifetime (ADR-019: side effects in callbacks, not in .map).
      // Auto-disable preview when workspace key goes away (e.g. capability URL cleared)
      distributionChartState.keySignal.changes
        .filter(_.isEmpty) --> { _ => distributionChartState.previewEnabledVar.set(false) },

      treeViewState.selectedTree.signal.changes.collect {
        case LoadState.Loaded(tree) => tree
      } --> { tree =>
        val previousId = builderState.editingTreeId.now()
        if previousId.contains(tree.id) then
          // Same tree reloaded after successful submit — do not clear currentDraftVar so
          // that the preview checkbox works immediately after submission.
          builderState.loadFromTree(tree)
        else if builderState.isDirty then
          if dom.window.confirm("Loading a saved tree will clear your current draft. Continue?") then
            builderState.currentDraftVar.set(None) // switching tree — clear stale draft
            builderState.loadFromTree(tree)
          else
            // Revert dropdown to previously loaded tree (or None for new-tree mode).
            treeViewState.selectedTreeId.set(previousId)
        else
          builderState.currentDraftVar.set(None) // switching tree — clear stale draft
          builderState.loadFromTree(tree)
      },
      SplitPane.horizontal(
        left = TreeBuilderView(builderState, treeViewState, wsState, distributionChartState),
        right = SplitPane.vertical(
          top = previewPanel,
          bottom = DistributionChartView(distributionChartState),
          topPercent = 60
        ),
        leftPercent = 40
      )
    )
