package app.views

import com.raquo.laminar.api.L.{*, given}

/**
 * Placeholder for the LEC (Loss Exceedance Curve) chart panel.
 * Will be replaced with Vega-Lite visualization in Phase E.
 */
object LECChartPlaceholder:

  def apply(): HtmlElement =
    div(
      cls := "lec-chart-placeholder",
      h3("LEC Chart"),
      div(
        cls := "placeholder-content",
        span(cls := "placeholder-icon", "📊"),
        p("Loss Exceedance Curves will appear here"),
        p(cls := "placeholder-hint", "Select a node in the tree to view its risk distribution")
      )
    )
