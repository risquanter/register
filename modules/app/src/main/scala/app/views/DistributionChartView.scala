package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.facades.{vegaEmbed, EmbedResult}
import app.state.{DistributionChartState, DistributionViewMode, LoadState}
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.http.requests.DistributionShapeRequest

/** Reason the chart area shows idle text rather than a chart or loading state.
  *
  * Used by [[DistributionChartView.renderIdle]] to show context-appropriate copy.
  */
enum PreviewIdleReason:
  /** No workspace key — distribution previews are not available. */
  case NoWorkspace
  /** Workspace exists but the user has not enabled the live preview toggle. */
  case PreviewDisabled
  /** Preview is enabled but form fields are incomplete or invalid. */
  case ParametersIncomplete

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
        // Stream 1: Fetch on draft changes (debounced), sampling key and preview-enabled flag.
        // Handles the common case: user editing form fields while preview is already enabled.
        // Resetting always fires (draft → None means form cleared/unmounted).
        chartState.draftSignal.changes
          .debounce(400)
          .withCurrentValueOf(chartState.keySignal, chartState.previewEnabledVar.signal)
          .foreach {
            case (Some(draft), Some(key), true) =>
              chartState.loadPreview(key, toPreviewRequest(draft))
            case (None, _, _) =>
              chartState.reset()
            case _ => ()
          }(ctx.owner)

        // Stream 2: React immediately when the preview toggle itself is flipped.
        // Handles the case where the draft is already valid and stable but preview
        // was off — draftSignal.changes would never fire in that state.
        // No debounce: the user clicked intentionally.
        chartState.previewEnabledVar.signal.changes
          .withCurrentValueOf(chartState.draftSignal, chartState.keySignal)
          .foreach {
            case (true, Some(draft), Some(key)) =>
              chartState.loadPreview(key, toPreviewRequest(draft))
            case (false, _, _) =>
              chartState.reset()
            case _ => ()
          }(ctx.owner)
      },

      // Clear render error when a new spec arrives (new fetch attempt started).
      // Must be a separate subscription — mutating renderError$ inside the child <-- .map
      // would re-trigger the combined signal and dispose the chart element just built.
      chartState.specSignal.changes --> { _ => renderError$.set(None) },

      // Spec → DOM: dispose previous chart then render next state.
      child <-- chartState.specSignal
        .combineWith(renderError$.signal, chartState.keySignal.map(_.isDefined), chartState.previewEnabledVar.signal)
        .map { (specState, renderErr, hasWorkspace, previewEnabled) =>
          disposeChart()
          resolveChartContent(specState, renderErr, hasWorkspace, previewEnabled,
            onResult = result => currentResult = result,
            onError  = msg => renderError$.set(Some(msg))
          )
        },

      // Coherence echo caption: Exact/Smoothed fit summary from the server response.
      child.maybe <-- chartState.coherenceCaptionSignal.map(_.map { caption =>
        p(cls := "distribution-chart-caption", caption)
      }),

      onUnmountCallback(_ => disposeChart())
    )

  // ── State renderers ───────────────────────────────────────────

  /** Resolve the current chart panel content from the combined state signals.
    *
    * Extracted from the `child <-- ...map` combinator to stay within the 3-line
    * lambda limit and make the routing logic independently readable.
    */
  private def resolveChartContent(
    specState:      LoadState[js.Dynamic],
    renderErr:      Option[String],
    hasWorkspace:   Boolean,
    previewEnabled: Boolean,
    onResult:       EmbedResult => Unit,
    onError:        String => Unit
  ): HtmlElement =
    renderErr match
      case Some(msg) => renderError(msg)
      case None =>
        specState match
          case LoadState.Loading     => renderLoading
          case LoadState.Failed(msg) => renderError(msg)
          case LoadState.Loaded(sp)  => renderChart(sp, onResult, onError)
          case LoadState.Idle =>
            val reason =
              if      !hasWorkspace   then PreviewIdleReason.NoWorkspace
              else if !previewEnabled then PreviewIdleReason.PreviewDisabled
              else                         PreviewIdleReason.ParametersIncomplete
            renderIdle(reason)

  private def renderIdle(reason: PreviewIdleReason): HtmlElement =
    val text = reason match
      case PreviewIdleReason.NoWorkspace          => "Create a risk tree to enable distribution previews"
      case PreviewIdleReason.PreviewDisabled       => "Enable preview to see a distribution chart"
      case PreviewIdleReason.ParametersIncomplete  => "Enter distribution parameters to see a preview"
    div(
      cls := "distribution-chart-message",
      span(cls := "distribution-chart-icon", "📊"),
      p(text)
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

  /** Build a preview request from a valid distribution shape.
    *
    * Percentiles in [[Distribution]] are stored as 0–1 (domain scale) and the
    * preview endpoint now expects 0–1, so no rescaling is needed.
    */
  private def toPreviewRequest(draft: Distribution): DistributionShapeRequest =
    DistributionShapeRequest(
      distributionType = draft.distributionType.toString,
      percentiles      = draft.percentiles,   // 0-1, no conversion
      quantiles        = draft.quantiles,
      terms            = draft.terms.map(_.toInt),
      minLoss          = draft.minLoss.map(identity),
      maxLoss          = draft.maxLoss.map(identity)
    )
