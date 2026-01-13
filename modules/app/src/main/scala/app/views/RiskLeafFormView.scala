package app.views

import com.raquo.laminar.api.L.{*, given}

/** Placeholder for the Risk Leaf creation form - will be implemented in Phase 3-5 */
object RiskLeafFormView:

  def apply(): HtmlElement =
    div(
      cls := "risk-leaf-form",
      h2("Create Risk Leaf"),
      p(
        cls := "form-description",
        "Configure a risk leaf node with either expert distribution or lognormal distribution."
      ),
      // Placeholder for form - will be replaced in Phase 3
      div(
        cls := "form-placeholder",
        p("Form will be implemented in Phase 3")
      )
    )
