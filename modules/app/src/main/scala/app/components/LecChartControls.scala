package app.components

import com.raquo.laminar.api.L.{*, given}

import app.chart.{ChartParams, Interpolation, LecAnnotation}
import app.state.ChartParamStore

/** The LEC chart's control panel — a native, app-styled interpolation select
  * plus one checkbox per annotation, rendered as a left column beside the
  * chart.
  *
  * Pure derived view (ADR-019 Pattern 4): owns no state. It reads the current
  * `ChartParams` from a `Signal` and reports changes through the store's toggle
  * callbacks; the store is the single source of truth. Replaces Vega's own
  * `bind` inputs, which rendered inside the element Vega measures for width and
  * carried Vega's default styling.
  *
  * Rendered once per chart surface group and wired to the shared
  * `ChartParamStore`, so it drives the single chart and every side-by-side
  * panel at once.
  */
object LecChartControls:

  def apply(params: Signal[ChartParams], store: ChartParamStore): HtmlElement =
    div(
      cls := "lec-chart-controls",
      div(
        cls := "lec-controls-field",
        label(cls := "form-label", "Interpolation"),
        select(
          cls := "form-input lec-controls-select",
          controlled(
            value <-- params.map(_.interpolation.signalValue),
            onInput.mapToValue --> { raw => store.setInterpolation(Interpolation.fromSignal(raw)) }
          ),
          Interpolation.values.toSeq.map(i => option(value := i.signalValue, i.label))
        )
      ),
      div(
        cls := "lec-controls-toggles",
        LecAnnotation.values.toSeq.map { a =>
          label(
            cls := "form-label-inline lec-controls-toggle",
            input(
              typ := "checkbox",
              controlled(
                checked <-- params.map(_.annotations.contains(a)),
                onInput.mapToChecked --> { _ => store.toggleAnnotation(a) }
              )
            ),
            span(s" ${a.label}")
          )
        }
      )
    )
