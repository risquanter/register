package app.views

import com.raquo.laminar.api.L.{*, given}
import app.components.SplitPane
import app.state.{TreeBuilderState, TreeViewState, WorkspaceState, LoadState}

/** Design view — tree creation and editing workflow.
  *
  * Structural wrapper that composes existing components into the
  * Design section layout:
  *
  *   SplitPane.horizontal(40% | 60%)
  *   ├── LEFT:  TreeBuilderView (forms)
  *   └── RIGHT: SplitPane.vertical(60% | 40%)
  *       ├── TOP:    TreePreview (live ASCII preview)
  *       └── BOTTOM: LECChartView (chart for the currently-edited tree)
  *
  * Pure structural component — owns no state (ADR-019 Pattern 1).
  */
object DesignView:

  def apply(
    builderState: TreeBuilderState,
    treeViewState: TreeViewState,
    wsState: WorkspaceState
  ): HtmlElement =
    div(
      cls := "design-view",
      SplitPane.horizontal(
        left = TreeBuilderView(builderState, treeViewState, wsState),
        right = SplitPane.vertical(
          top = TreePreview(builderState),
          bottom = LECChartView(treeViewState.lecChartSpec),
          topPercent = 60
        ),
        leftPercent = 40
      )
    )
