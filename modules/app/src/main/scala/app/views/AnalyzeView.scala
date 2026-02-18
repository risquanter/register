package app.views

import com.raquo.laminar.api.L.{*, given}
import app.components.SplitPane
import app.components.FormInputs
import app.state.{TreeViewState, AnalyzeQueryState}

/** Analyze view — tree inspection and LEC chart analysis.
  *
  * Structural wrapper that composes existing components into the
  * Analyze section layout:
  *
  *   SplitPane.horizontal(75% | 25%)
  *   ├── LEFT:  analysis-panel
  *   │   ├── Query input + textual response area
  *   │   └── LECChartView in a fixed-height panel
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

  def apply(treeViewState: TreeViewState, queryState: AnalyzeQueryState): HtmlElement =
    val noError: Signal[Option[String]] = Signal.fromValue(None)

    val analyzeLeftPanel = div(
      cls := "analyze-left-panel",
      div(
        cls := "analyze-query-panel",
        h3("Query Language"),
        FormInputs.textInput(
          labelText = "Query Input",
          valueVar = queryState.queryInput,
          errorSignal = noError,
          placeholderText = "Enter query-language expression"
        ),
        div(
          cls := "form-field",
          label(cls := "form-label", "Response"),
          div(
            cls := "response-area-output",
            child <-- queryState.textualResponse.signal.map { text =>
              if text.trim.nonEmpty then pre(cls := "response-area-content", text)
              else span(cls := "response-area-placeholder", "Textual response appears here")
            }
          )
        )
      ),
      div(
        cls := "analyze-lec-panel",
        LECChartView(treeViewState.lecChartSpec)
      )
    )

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(treeViewState),
      TreeDetailView(treeViewState)
    )

    div(
      cls := "analyze-view",
      SplitPane.horizontal(
        left = analyzeLeftPanel,
        right = savedTreePanel,
        leftPercent = 75
      )
    )
