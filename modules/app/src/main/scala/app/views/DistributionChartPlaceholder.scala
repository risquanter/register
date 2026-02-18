package app.views

import com.raquo.laminar.api.L.{*, given}

/** Placeholder for future distribution-modelling chart in Design view.
  *
  * Intentionally decoupled from Analyze LEC chart lifecycle.
  */
object DistributionChartPlaceholder:

  def apply(): HtmlElement =
    div(
      cls := "distribution-chart-placeholder",
      h3("Distribution Modelling") ,
      div(
        cls := "placeholder-content",
        span(cls := "placeholder-icon", "📈"),
        p("Distribution chart will appear here"),
        p(
          cls := "placeholder-hint",
          "This panel is reserved for a future node-distribution modelling visualization"
        )
      )
    )
