package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.facades.{vegaEmbed, EmbedResult}
import app.state.{DistributionChartState, DistributionViewMode, LoadState, DistributionDraft}
import com.risquanter.register.http.requests.DistributionPreviewRequest

/** Reactive distribution preview chart for the Design view.
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  * Replaces [[DistributionChartPlaceholder]] once the feature is active.
  *
  * Lifecycle:
  *   - On `Loaded(spec)`: call `vegaEmbed` with the dynamic spec, store `EmbedResult`
  *   - On any transition away from `Loaded` or on unmount: call `finalize()`
  *
  * Debounced fetch subscription:
  *   - On mount, subscribes to `chartState.draftSignal.changes` (debounced 400 ms)
  *     combined with the current `keySignal` value
  *   - Calls `chartState.loadPreview(key, req)` for each valid (draft, key) pair
  *   - Resets chart to Idle when draft becomes None (form unmounted or cleared)
  */
object DistributionChartView:

  def apply(chartState: DistributionChartState): HtmlElement =
    var currentResult: js.UndefOr[EmbedResult] = js.undefined
    val renderError$ : Var[Option[String]] = Var(None)

    def disposeChart(): Unit =
      currentResult.foreach { result =>
        result.finalize()
        currentResult = js.undefined
      }

    val toggleEl = div(
      cls := "chart-mode-toggle",
      button(
        cls <-- chartState.viewModeVar.signal.map(m =>
          if m == DistributionViewMode.PDF then "toggle-btn active" else "toggle-btn"),
        "PDF",
        onClick --> { _ => chartState.viewModeVar.set(DistributionViewMode.PDF) }
      ),
      button(
        cls <-- chartState.viewModeVar.signal.map(m =>
          if m == DistributionViewMode.CDF then "toggle-btn active" else "toggle-btn"),
        "CDF",
        onClick --> { _ => chartState.viewModeVar.set(DistributionViewMode.CDF) }
      )
    )

    div(
      cls := "distribution-chart-view",
      toggleEl,

      // Debounced fetch subscription — scoped to this element's mounted lifetime.
      onMountCallback { ctx =>
        // Fetch on draft changes (debounced), sampling the current key.
        chartState.draftSignal.changes
          .debounce(400)
          .withCurrentValueOf(chartState.keySignal)
          .foreach {
            case (Some(draft), Some(key)) =>
              chartState.loadPreview(key, toPreviewRequest(draft))
            case (None, _) =>
              chartState.reset()
            case _ => ()
          }(ctx.owner)
      },

      // Clear render error when a new spec arrives (new fetch attempt started).
      // Must be a separate subscription — mutating renderError$ inside the child <-- .map
      // would re-trigger the combined signal and dispose the chart element just built.
      chartState.specSignal.changes --> { _ => renderError$.set(None) },

      // Spec → DOM: dispose previous chart then render next state.
      child <-- chartState.specSignal.combineWith(renderError$.signal).map { (specState, renderErr) =>
        disposeChart()
        renderErr match
          case Some(msg) => renderError(msg)
          case None =>
            specState match
              case LoadState.Idle        => renderIdle
              case LoadState.Loading     => renderLoading
              case LoadState.Failed(msg) => renderError(msg)
              case LoadState.Loaded(sp)  =>
                renderChart(sp, result => currentResult = result, msg => renderError$.set(Some(msg)))
      },

      // Coherence echo caption: Exact/Smoothed fit summary from the server response.
      child.maybe <-- chartState.coherenceCaptionSignal.map(_.map { caption =>
        p(cls := "distribution-chart-caption", caption)
      }),

      onUnmountCallback(_ => disposeChart())
    )

  // ── State renderers ───────────────────────────────────────────

  private def renderIdle: HtmlElement =
    div(
      cls := "distribution-chart-message",
      span(cls := "distribution-chart-icon", "📊"),
      p("Enter distribution parameters to see a preview")
    )

  private def renderLoading: HtmlElement =
    div(
      cls := "distribution-chart-message",
      p(cls := "distribution-chart-loading", "Loading preview…")
    )

  private def renderError(message: String): HtmlElement =
    div(
      cls := "distribution-chart-message distribution-chart-error",
      p(s"Preview error: $message")
    )

  private def renderChart(
    spec:     js.Dynamic,
    onResult: EmbedResult => Unit,
    onError:  String => Unit
  ): HtmlElement =
    val container = div(cls := "distribution-chart-canvas")
    container.amend(
      onMountCallback { ctx =>
        val options = js.Dynamic.literal(
          "actions"  -> false,
          "renderer" -> "svg"
        )
        vegaEmbed(ctx.thisNode.ref, spec, options)
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

  // ── Draft → request conversion ────────────────────────────────

  /** Build a preview request from a valid draft.
    *
    * Percentiles in [[LeafDistributionDraft]] are stored as 0–1 (normalised from the
    * form's 0–100 display values). The preview endpoint expects 0–100, so we re-scale.
    */
  private def toPreviewRequest(draft: DistributionDraft): DistributionPreviewRequest =
    DistributionPreviewRequest(
      distributionType = draft.distributionType.toApiString,
      percentiles      = draft.percentiles.map(_.map(_ * 100.0)),
      quantiles        = draft.quantiles,
      terms            = draft.terms,
      minLoss          = draft.minLoss,
      maxLoss          = draft.maxLoss
    )
