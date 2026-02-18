package app.views

import com.raquo.laminar.api.L.{*, given}
import app.components.SplitPane
import app.state.TreeViewState

/** Analyze view — tree inspection and LEC chart analysis.
  *
  * Structural wrapper that composes existing components into the
  * Analyze section layout:
  *
  *   SplitPane.horizontal(70% | 30%)
  *   ├── LEFT:  LECChartView (full-width LEC chart)
  *   └── RIGHT: saved-tree-panel
  *       ├── TreeListView  (dropdown selector)
  *       └── TreeDetailView (expandable hierarchy)
  *
  * Pure structural component — owns no state (ADR-019 Pattern 1).
  *
  * Note: A "results panel" on the left is a future addition (see sketch
  * ⚠ SUGGESTION banners). For now the chart takes the full left area.
  */
object AnalyzeView:

  def apply(treeViewState: TreeViewState): HtmlElement =
    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(treeViewState),
      TreeDetailView(treeViewState)
    )

    div(
      cls := "analyze-view",
      SplitPane.horizontal(
        left = LECChartView(treeViewState.lecChartSpec),
        right = savedTreePanel,
        leftPercent = 70
      )
    )
