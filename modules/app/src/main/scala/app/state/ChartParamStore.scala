package app.state

import scala.scalajs.js

import app.chart.LECSpecBuilder

/** Holds the user-facing Vega input params (annotation toggles,
  * interpolation) across chart teardowns. A spec change tears the whole Vega
  * view down, and the params — and the user's checkbox choices with them —
  * live inside that view; values are captured off the dying view and
  * re-applied to the next one.
  *
  * One instance per chart surface group: every `LECChartView` in the Analyze
  * chart area (the single/Overlay chart and both side-by-side panels) shares
  * one store, so the toggles also survive display-mode switches, which
  * replace the chart component instances outright. A view constructed
  * without an explicit store gets a private one and keeps the old
  * per-instance behavior.
  *
  * Per-signal try-guards: an empty/base spec may not declare every param,
  * and Vega throws on unknown signal names.
  */
final class ChartParamStore:

  private var saved: Map[String, js.Any] = Map.empty

  /** Read the current param values off a live Vega view. */
  def capture(view: js.Dynamic): Unit =
    saved = LECSpecBuilder.preservedParams.flatMap { name =>
      try Some(name -> (view.signal(name): js.Any))
      catch case _: Throwable => None
    }.toMap

  /** Apply the captured values to a freshly embedded Vega view. No-op when
    * nothing has been captured yet. */
  def restore(view: js.Dynamic): Unit =
    if saved.nonEmpty then
      saved.foreach { (name, value) =>
        try { view.signal(name, value); () }
        catch case _: Throwable => ()
      }
      try { view.run(); () }
      catch case _: Throwable => ()
