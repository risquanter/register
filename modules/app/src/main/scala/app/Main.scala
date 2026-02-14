package app

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.components.{Layout, SplitPane}
import app.state.{TreeBuilderState, TreeViewState}
import app.views.{TreeBuilderView, TreePreview, TreeListView, TreeDetailView, LECChartView}

object Main:

  def main(args: Array[String]): Unit =
    val container = dom.document.querySelector("#app")
    val builderState = new TreeBuilderState
    val treeViewState = new TreeViewState

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(treeViewState),
      TreeDetailView(treeViewState)
    )

    val appElement = Layout(
      SplitPane.horizontal(
        left = TreeBuilderView(builderState, treeViewState),
        right = SplitPane.vertical(
          top = SplitPane.horizontal(
            left = TreePreview(builderState),
            right = savedTreePanel,
            leftPercent = 50
          ),
          // LEC chart panel — wired to real chart selection state
          bottom = LECChartView(treeViewState.lecChartSpec.signal),
          topPercent = 60
        ),
        leftPercent = 40
      )
    )

    render(container, appElement)
