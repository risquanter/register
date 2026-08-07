package app.views

import com.raquo.laminar.api.L.{*, given}
import com.raquo.airstream.web.DomEventStream
import org.scalajs.dom

import scala.scalajs.js

import app.chart.ChartParams
import app.components.Icons
import app.facades.{vegaEmbed, EmbedResult}
import app.state.{LoadState, ChartHoverBridge, ChartParamStore}

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

  /** @param paramStore Holds the user's interpolation/annotation choices as
    *                    app-side state (the source of truth). This view applies
    *                    them to its Vega view whenever they change and on each
    *                    embed. Pass one shared store to every chart surface whose
    *                    settings should feel like one chart (Analyze's single
    *                    chart and both side-by-side panels), so the one
    *                    `LecChartControls` panel drives them all. The default
    *                    private store keeps this view at the defaults.
    */
  def apply(
      specSignal: Signal[LoadState[js.Dynamic]],
      hoverBridge: ChartHoverBridge,
      paramStore: ChartParamStore = new ChartParamStore
  ): HtmlElement =
    // Mutable ref for the current EmbedResult — needed for cleanup.
    // This is local to the component lifecycle, not shared state.
    var currentResult: js.UndefOr[EmbedResult] = js.undefined
    // Mutable ref for the last error — used to show render errors without
    // re-triggering the signal (which would dispose the container).
    val renderError$ : Var[Option[String]] = Var(None)

    // The root element, captured on mount, is the fullscreen target; the button
    // reflects the live fullscreen state so Esc/F11 keep it in sync.
    var rootRef: dom.Element = null
    val isFullscreen: Var[Boolean] = Var(false)

    // Latest control state, kept off the store's signal so a freshly embedded
    // view can be initialised to it (the subscription only re-fires on change).
    // Same component-local edge pattern as `currentResult`.
    var latestParams: ChartParams = ChartParams.default

    def disposeChart(): Unit =
      currentResult.foreach { result =>
        hoverBridge.detachFromView(result.view)
        result.finalize()
        currentResult = js.undefined
      }

    div(
      cls := "lec-chart-view",
      onMountCallback(ctx => rootRef = ctx.thisNode.ref),
      DomEventStream[dom.Event](dom.document, "fullscreenchange", useCapture = false)
        --> { _ => isFullscreen.set(dom.document.fullscreenElement != null) },
      div(
        cls := "lec-chart-header",
        h3("LEC Chart"),
        button(
          cls := "chart-fullscreen-btn",
          tpe := "button",
          title <-- isFullscreen.signal.map(fs => if fs then "Exit fullscreen" else "Fullscreen"),
          child <-- isFullscreen.signal.map(fs =>
            if fs then Icons.minimize("chart-icon") else Icons.maximize("chart-icon")
          ),
          onClick --> { _ =>
            if dom.document.fullscreenElement == null then rootRef.requestFullscreen()
            else dom.document.exitFullscreen()
            ()
          }
        )
      ),
      div(
        cls := "lec-chart-content",
        // Control panel → Vega: push the shared control state onto the current
        // view whenever it changes (and once on mount — a Signal emits its
        // current value on subscribe). A newly embedded view is initialised
        // from `latestParams` in `onResult`, since this only re-fires on change.
        paramStore.signal --> { params =>
          latestParams = params
          currentResult.foreach(result => params.applyTo(result.view))
        },
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
                      latestParams.applyTo(result.view)
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
