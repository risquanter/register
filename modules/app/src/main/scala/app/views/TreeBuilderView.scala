package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.TreeBuilderState

/**
 * Orchestrates portfolio + leaf forms and preview using TreeBuilderState.
 */
object TreeBuilderView:
  def apply(): HtmlElement =
    val state = new TreeBuilderState
    div(
      cls := "tree-builder",
      h1("Risk Tree Builder"),
      div(
        cls := "tree-name-field",
        label(cls := "form-label", "Tree Name"),
        input(
          typ := "text",
          cls := "form-input",
          placeholder := "e.g., Enterprise Risk Tree",
          controlled(
            value <-- state.treeNameVar.signal,
            onInput.mapToValue --> state.treeNameVar
          )
        )
      ),
      div(
        cls := "forms-grid",
        PortfolioFormView(state),
        RiskLeafFormView(state)
      ),
      TreePreview(state)
    )
