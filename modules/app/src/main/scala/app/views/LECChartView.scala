package app.views

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import scala.scalajs.js

import app.chart.LECSpecBuilder
import app.facades.{vegaEmbed, EmbedResult}
import app.state.{LoadState, ChartHoverBridge}

/** Reactive LEC chart panel backed by Vega-Lite via VegaEmbed.
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  * Receives the chart spec lifecycle as a `Signal[LoadState[js.Dynamic]]`
  * and a `ChartHoverBridge` for bidirectional hover (§3B).
  *
  * Lifecycle:
  *   - On `Loaded(spec)`: call `vegaEmbed` with the dynamic spec, store `EmbedResult`,
  *     attach hover bridge signal listener
  *   - On any transition away from `Loaded` or on unmount: detach listener,
  *     call `finalize()` to release canvas/timer resources
  */
object LECChartView:

  def apply(specSignal: Signal[LoadState[js.Dynamic]], hoverBridge: ChartHoverBridge): HtmlElement =
    // Mutable ref for the current EmbedResult — needed for cleanup.
    // This is local to the component lifecycle, not shared state.
    var currentResult: js.UndefOr[EmbedResult] = js.undefined
    // Mutable ref for the last error — used to show render errors without
    // re-triggering the signal (which would dispose the container).
    val renderError$ : Var[Option[String]] = Var(None)

    // Toggle/interpolation param values carried across re-embeds: a spec
    // change tears the whole Vega view down, and the params (and the user's
    // checkbox choices with them) live inside that view — without this,
    // every genuine data change (new selection, compare on/off) silently
    // reset the toggles to the spec defaults. Captured off the dying view,
    // re-applied to the next one. Per-signal try-guards: an empty/base spec
    // may not declare every param, and Vega throws on unknown signal names.
    var savedParams: Map[String, js.Any] = Map.empty

    def captureParams(view: js.Dynamic): Map[String, js.Any] =
      LECSpecBuilder.preservedParams.flatMap { name =>
        try Some(name -> (view.signal(name): js.Any))
        catch case _: Throwable => None
      }.toMap

    def restoreParams(view: js.Dynamic): Unit =
      if savedParams.nonEmpty then
        savedParams.foreach { (name, value) =>
          try { view.signal(name, value); () }
          catch case _: Throwable => ()
        }
        try { view.run(); () }
        catch case _: Throwable => ()

    def disposeChart(): Unit =
      currentResult.foreach { result =>
        savedParams = captureParams(result.view)
        hoverBridge.detachFromView(result.view)
        result.finalize()
        currentResult = js.undefined
      }

    div(
      cls := "lec-chart-view",
      h3("LEC Chart"),
      div(
        cls := "lec-chart-content",
        // Laminar → Vega hover push (§3B.3)
        hoverBridge.hoveredCurveId.signal.changes --> { maybeId =>
          currentResult.foreach { result =>
            hoverBridge.pushToView(result.view, maybeId)
          }
        },
        // renderError$ is deduplicated: the clearing subscription below writes
        // None on every spec emission, and an Airstream Var.set emits even
        // when the value is unchanged — without .distinct every spec change
        // rendered twice, embedding two Vega views of which one leaked
        // un-finalized (its embed resolved after its container was replaced).
        child <-- specSignal.combineWith(renderError$.signal.distinct).map { (state, renderErr) =>
          disposeChart()
          renderErr match
            case Some(msg) => renderError(msg)
            case None =>
              state match
                case LoadState.Idle           => renderIdle
                case LoadState.Loading        => renderLoading
                case LoadState.Failed(msg)    => renderError(msg)
                case LoadState.Loaded(spec) =>
                  renderChart(
                    spec,
                    onResult = result => {
                      currentResult = result
                      restoreParams(result.view)
                      hoverBridge.attachToView(result.view)
                    },
                    onError = msg => renderError$.set(Some(msg))
                  )
        },
        // Clear render error when spec changes (new fetch attempt)
        specSignal.changes --> { _ => renderError$.set(None) },
        onUnmountCallback(_ => disposeChart())
      )
    )

  // ── State renderers ───────────────────────────────────────────

  private def renderIdle: HtmlElement =
    div(
      cls := "lec-chart-message",
      span(cls := "lec-chart-icon", "📊"),
      p("Select a node to view its Loss Exceedance Curve")
    )

  private def renderLoading: HtmlElement =
    div(
      cls := "lec-chart-message",
      p(cls := "lec-chart-loading", "Loading chart…")
    )

  private def renderError(message: String): HtmlElement =
    div(
      cls := "lec-chart-message lec-chart-error",
      p(s"Chart error: $message")
    )

  /** Mount a chart into a fresh container element via VegaEmbed. */
  private def renderChart(
      spec: js.Dynamic,
      onResult: EmbedResult => Unit,
      onError: String => Unit
  ): HtmlElement =
    val container = div(cls := "lec-chart-container")
    container.amend(
      onMountCallback { ctx =>
        val options = js.Dynamic.literal(
          "actions"    -> false,
          // Canvas text uses grayscale-only anti-aliasing (no subpixel
          // smoothing), which reads as blurrier/lower-contrast than the rest
          // of the app's native-rendered text — svg uses the browser's own
          // text engine instead, matching DistributionChartView (which
          // already renders via svg). Vega's hover/nearest-point interaction
          // works identically under both renderers.
          "renderer"   -> "svg",
          "hover"      -> true
        )
        vegaEmbed(ctx.thisNode.ref, spec, options)
          .`then`[Unit] { (result: EmbedResult) =>
            // The embed resolves asynchronously: if a newer spec emission has
            // already replaced this container, storing the result would leak
            // the previous one and attach hover to a detached view — release
            // this late arrival instead.
            if ctx.thisNode.ref.isConnected then onResult(result)
            else result.finalize()
            ()
          }
          .`catch`[Unit] { (err: Any) =>
            val dyn = err.asInstanceOf[js.Dynamic]
            val msg = dyn.selectDynamic("message")
            val errorStr = if js.isUndefined(msg) then s"$err" else msg.toString
            onError(s"Vega render failed: $errorStr")
            ()
          }
        ()
      }
    )
    container
