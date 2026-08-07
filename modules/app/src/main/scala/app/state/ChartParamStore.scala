package app.state

import com.raquo.laminar.api.L.{*, given}

import app.chart.{ChartParams, Interpolation, LecAnnotation}

/** Owns the user-facing LEC chart controls (interpolation + annotation
  * toggles) as app-side state — the source of truth, applied to each Vega view
  * rather than read back from it.
  *
  * One instance per chart surface group: every `LECChartView` in the Analyze
  * chart area (the single/Overlay chart and both side-by-side panels) shares
  * one store and one `LecChartControls` panel, so a toggle drives every chart
  * at once and the choices survive display-mode switches (which replace the
  * chart component instances outright). A view constructed without an explicit
  * store gets a private one at defaults.
  */
final class ChartParamStore:

  private val state: Var[ChartParams] = Var(ChartParams.default)

  /** Current control state — the control panel binds to this, and each
    * `LECChartView` applies it to its Vega view on change and on embed. */
  val signal: Signal[ChartParams] = state.signal

  def setInterpolation(i: Interpolation): Unit =
    state.update(_.copy(interpolation = i))

  /** Flip one annotation's visibility. */
  def toggleAnnotation(a: LecAnnotation): Unit =
    state.update(_.toggle(a))
