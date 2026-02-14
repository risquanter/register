package app.views

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

import scala.scalajs.js

import app.facades.{vegaEmbed, EmbedResult}
import app.state.LoadState

/** Reactive LEC chart panel backed by Vega-Lite via VegaEmbed.
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  * Receives the chart spec lifecycle as a `Signal[LoadState[String]]`.
  *
  * Lifecycle:
  *   - On `Loaded(specJson)`: parse JSON, call `vegaEmbed`, store `EmbedResult`
  *   - On any transition away from `Loaded` or on unmount: call `finalize()`
  *     to release canvas/timer resources
  */
object LECChartView:

  def apply(specSignal: Signal[LoadState[String]]): HtmlElement =
    // Mutable ref for the current EmbedResult — needed for cleanup.
    // This is local to the component lifecycle, not shared state.
    var currentResult: js.UndefOr[EmbedResult] = js.undefined
    // Mutable ref for the last error — used to show render errors without
    // re-triggering the signal (which would dispose the container).
    val renderError$ : Var[Option[String]] = Var(None)

    def disposeChart(): Unit =
      currentResult.foreach { result =>
        result.finalize()
        currentResult = js.undefined
      }

    div(
      cls := "lec-chart-view",
      h3("LEC Chart"),
      div(
        cls := "lec-chart-content",
        child <-- specSignal.combineWith(renderError$.signal).map { (state, renderErr) =>
          disposeChart()
          renderErr match
            case Some(msg) => renderError(msg)
            case None =>
              state match
                case LoadState.Idle           => renderIdle
                case LoadState.Loading        => renderLoading
                case LoadState.Failed(msg)    => renderError(msg)
                case LoadState.Loaded(specJson) =>
                  renderChart(
                    specJson,
                    onResult = result => currentResult = result,
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
      specJson: String,
      onResult: EmbedResult => Unit,
      onError: String => Unit
  ): HtmlElement =
    val container = div(cls := "lec-chart-container")
    container.amend(
      onMountCallback { ctx =>
        val parsed = js.JSON.parse(specJson)
        val options = js.Dynamic.literal(
          "actions"    -> false,
          "renderer"   -> "canvas",
          "hover"      -> true
        )
        vegaEmbed(ctx.thisNode.ref, parsed, options)
          .`then`[Unit] { (result: EmbedResult) =>
            onResult(result)
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
