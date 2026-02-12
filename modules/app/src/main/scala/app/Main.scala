package app

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import app.components.{Layout, SplitPane}
import app.state.TreeBuilderState
import app.views.{TreeBuilderView, TreePreview, LECChartPlaceholder}

object Main:

  def main(args: Array[String]): Unit =
    val container = dom.document.querySelector("#app")
    val state = new TreeBuilderState

    val appElement = Layout(
      SplitPane.horizontal(
        left = TreeBuilderView(state),
        right = SplitPane.vertical(
          top = TreePreview(state),
          bottom = LECChartPlaceholder(),
          topPercent = 60
        ),
        leftPercent = 50
      )
    )

    render(container, appElement)
