package app.chart

import scala.scalajs.js

import app.core.JsBoundary

/** Line interpolation mode for the LEC chart, offered as a select control.
  * `signalValue` is the value written to the Vega `interpolate` signal (the
  * one place the raw string crosses into Vega). */
enum Interpolation(val signalValue: String, val label: String):
  case Monotone  extends Interpolation("monotone", "Monotone")
  case Basis     extends Interpolation("basis", "Basis")
  case Linear    extends Interpolation("linear", "Linear")
  case StepAfter extends Interpolation("step-after", "Step after")

object Interpolation:
  val default: Interpolation = Monotone

  /** Map a raw select value back to the enum; unknown values fall back to the
    * default (the select only ever emits known values, so this is defensive). */
  def fromSignal(s: String): Interpolation =
    values.find(_.signalValue == s).getOrElse(default)

/** A per-curve LEC annotation with its own show/hide toggle. `signalName` is
  * the Vega signal the spec's opacity `expr` reads; `defaultOn` is its initial
  * state. The set of cases is the single source of truth for the toggle signal
  * declarations in `LECSpecBuilder` and the checkboxes in `LecChartControls`. */
enum LecAnnotation(val signalName: String, val label: String, val defaultOn: Boolean):
  case P90    extends LecAnnotation("showP90", "P90", false)
  case P95    extends LecAnnotation("showP95", "P95", true)
  case P99    extends LecAnnotation("showP99", "P99", false)
  case P995   extends LecAnnotation("showP995", "P99.5", false)
  case AAL    extends LecAnnotation("showAAL", "AAL", true)
  case NoLoss extends LecAnnotation("showNoLossProbability", "No-loss probability", true)

object LecAnnotation:
  /** The annotations shown by default (the `defaultOn` cases). */
  val defaults: Set[LecAnnotation] = values.filter(_.defaultOn).toSet

/** The user's LEC chart-control state, and the sole bridge that pushes it onto
  * a live Vega view. Held app-side (`ChartParamStore`) as the source of truth;
  * `LECChartView` applies it to each embedded view. */
final case class ChartParams(interpolation: Interpolation, annotations: Set[LecAnnotation]):

  /** Flip one annotation's visibility. */
  def toggle(a: LecAnnotation): ChartParams =
    val next = if annotations.contains(a) then annotations - a else annotations + a
    copy(annotations = next)

  /** Push these values onto a live Vega view. This is the only place raw Vega
    * signal names/values are used. Per-signal guards (`JsBoundary.orElse`,
    * ADR-033 §4): an empty/base spec may not declare a signal, and Vega throws
    * on unknown names — one missing signal must not block the others. */
  def applyTo(view: js.Dynamic): Unit =
    JsBoundary.orElse(()) { view.signal("interpolate", interpolation.signalValue); () }
    LecAnnotation.values.foreach { a =>
      JsBoundary.orElse(()) { view.signal(a.signalName, annotations.contains(a)); () }
    }
    JsBoundary.orElse(()) { view.run(); () }

object ChartParams:
  val default: ChartParams = ChartParams(Interpolation.default, LecAnnotation.defaults)
