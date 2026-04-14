package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.requests.LECChartRequest

/** Chart selection and LEC spec state, separated from tree navigation.
  *
  * Owns the user's manual node selection for charting (Ctrl+click picks),
  * the fetched Vega-Lite spec, and the bus-based toggle with a 13-cap guard.
  *
  * The reactive `chartRequest` signal that merges query-matched nodes with
  * user selections is constructed in `AnalyzeView`, where both
  * `AnalyzeQueryState.satisfyingNodeIds` and this state's
  * `userSelectedNodeIds` are in scope.
  *
  * Extends `WorkspaceEndpoints` to access workspace-scoped Tapir endpoint
  * definitions for ZJS bridge calls.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param selectedTreeId Signal for the currently selected tree ID.
  * @param selectedTree   Signal for the currently loaded tree structure.
  * @param globalError    App-wide error Var for the 13-cap validation error.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class LECChartState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree: StrictSignal[LoadState[RiskTree]],
  globalError: Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId] = () => None
) extends WorkspaceEndpoints:

  // ── User selection state ──────────────────────────────────────
  /** Node IDs manually Ctrl+clicked by the user for LEC chart overlay. */
  val userSelectedNodeIds: Var[Set[NodeId]] = Var(Set.empty)
  /** Render-ready Vega-Lite JSON spec (fetched from backend). */
  val lecChartSpec: Var[LoadState[String]] = Var(LoadState.Idle)

  // ── Bus-based toggle (ADR-019 P2: events up) ─────────────────

  /** WriteBus exposed to views for Ctrl+click toggle events.
    *
    * Views emit node IDs into this bus; the internal observer handles
    * set toggle + 13-cap guard. This keeps mutation logic encapsulated
    * inside the state owner (ADR-019 P2: events up, not imperative calls).
    */
  private val userSelectionBus: EventBus[NodeId] = new EventBus[NodeId]
  val userSelectionToggle: WriteBus[NodeId] = userSelectionBus.writer

  // Internal observer — handles the toggle + cap logic.
  // Uses unsafeWindowOwner: LECChartState lives for the app lifetime.
  userSelectionBus.events.foreach { nodeId =>
    val current = userSelectedNodeIds.now()
    if current.contains(nodeId) then
      userSelectedNodeIds.update(_ - nodeId)
    else if current.size >= 13 then
      globalError.set(Some(GlobalError.ValidationFailed(List(
        ValidationError("chartSelection", ValidationErrorCode.CONSTRAINT_VIOLATION,
          "Maximum 13 user-selected curves. Remove a selection before adding more.")
      ))))
    else
      userSelectedNodeIds.update(_ + nodeId)
  }(using unsafeWindowOwner)

  // ── Actions ───────────────────────────────────────────────────

  /** Reset chart state (called when tree selection changes). */
  def reset(): Unit =
    userSelectedNodeIds.set(Set.empty)
    lecChartSpec.set(LoadState.Idle)

  /** Fetch the LEC chart spec from the backend for the given request.
    *
    * Called reactively from the `chartRequest` signal subscription
    * in `AnalyzeView`.
    */
  def loadLECChart(request: LECChartRequest): Unit =
    (keySignal.now(), selectedTreeId.now()) match
      case (Some(key), Some(treeId)) =>
        getWorkspaceLECChartEndpoint((userIdAccessor(), key, treeId, request)).loadInto(lecChartSpec)
      case _ => () // No workspace or tree selected — nothing to do
