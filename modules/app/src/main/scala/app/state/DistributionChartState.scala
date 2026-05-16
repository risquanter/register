package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.chart.DistributionSpecBuilder
import app.core.ZJS.*
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.DistributionPreviewEndpoints
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse}

/** PDF/CDF view toggle for the distribution preview chart. */
enum DistributionViewMode:
  case PDF, CDF

/** Reactive state for the distribution preview chart panel in Design view.
  *
  * Owns the async data cache, view mode toggle, and derived Vega-Lite spec signal.
  * Extends [[DistributionPreviewEndpoints]] to access the Tapir endpoint definition
  * for ZJS bridge calls (same pattern as [[LECChartState]]).
  *
  * Constructed above `DesignView` per ADR-019 Pattern 1 (state above pure view).
  * The `draftSignal` is fed from [[TreeBuilderState.draftSignal]], which is written
  * by [[app.views.RiskLeafFormView]]'s debounce-free subscription.
  *
  * @param draftSignal    Current in-flight distribution draft; None when form is
  *                       empty, invalid, or unmounted.
  * @param keySignal      Active workspace key; None when no workspace is open.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class DistributionChartState(
  val draftSignal:    StrictSignal[Option[LeafDistributionDraft]],
  val keySignal:      StrictSignal[Option[WorkspaceKeySecret]],
  val userIdAccessor: () => Option[UserId] = () => None
) extends DistributionPreviewEndpoints:

  /** Current PDF/CDF view mode — toggled by the user via the chart toggle buttons. */
  val viewModeVar: Var[DistributionViewMode] = Var(DistributionViewMode.PDF)

  /** Loaded preview data from the last successful server call. */
  private val previewVar: Var[LoadState[DistributionPreviewResponse]] = Var(LoadState.Idle)

  /** Complete Vega-Lite spec ready for `vegaEmbed`, derived reactively from the
    * preview cache, current view mode, and current draft (for anchor overlays).
    */
  val specSignal: Signal[LoadState[js.Dynamic]] =
    previewVar.signal
      .combineWith(viewModeVar.signal, draftSignal)
      .map { (loadState, viewMode, draft) =>
        loadState.map(resp => DistributionSpecBuilder.build(resp, viewMode, draft))
      }

  /** Trigger a preview fetch for the given workspace key and request.
    *
    * Sets `previewVar` through the `Loading → Loaded/Failed` lifecycle via
    * `loadInto`. Called from [[app.views.DistributionChartView]] on the
    * debounced `draftSignal.changes` stream.
    */
  def loadPreview(key: WorkspaceKeySecret, req: DistributionPreviewRequest): Unit =
    distributionPreviewEndpoint((userIdAccessor(), key, req)).loadInto(previewVar)

  /** Reset the preview to Idle (e.g. when the form is unmounted). */
  def reset(): Unit = previewVar.set(LoadState.Idle)

  /** Coherence echo caption for the chart panel.
    *
    * - `Some("Exact fit with N terms")` when `resolvedTerms == anchorCount`
    * - `Some("Smoothed with N terms (M anchor points)")` when `resolvedTerms < anchorCount`
    * - `None` for lognormal mode or when no preview is loaded.
    */
  val coherenceCaptionSignal: Signal[Option[String]] =
    previewVar.signal.map {
      case LoadState.Loaded(resp) =>
        (resp.resolvedTerms, resp.anchorCount) match
          case (Some(t), Some(n)) =>
            if t == n then Some(s"Exact fit with $t terms")
            else Some(s"Smoothed with $t terms ($n anchor points)")
          case _ => None
      case _ => None
    }
