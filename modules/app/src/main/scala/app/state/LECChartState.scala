package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.chart.{ColorAssigner, LECSpecBuilder}
import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.http.endpoints.WorkspaceEndpoints

/** Chart selection and LEC curve data state, separated from tree navigation.
  *
  * Owns the user's manual node selection for charting (Ctrl+click picks),
  * the fetched curve data cache, and the bus-based toggle with a 13-cap guard.
  *
  * Fetches structured `Map[String, LECNodeCurve]` via `lec-multi`.
  * The Vega-Lite spec is built client-side from this data.
  *
  * The reactive `loadCurves` call that merges query-matched nodes with
  * user selections is driven from `AnalyzeView`, where both
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

  /** Node IDs satisfying the current query (set from AnalyzeView). */
  val satisfyingNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Structured curve data fetched via `lec-multi` endpoint.
    * Keyed by `nodeId.value` (String), matching the endpoint response shape.
    */
  val curveCache: Var[LoadState[Map[String, LECNodeCurve]]] = Var(LoadState.Idle)

  /** Manual colour overrides per node (hex strings, e.g. "#4ade80"). */
  val colorOverrides: Var[Map[NodeId, String]] = Var(Map.empty)

  // ── Derived signals ───────────────────────────────────────────

  /** Union of query-matched + user-selected node IDs — the set of nodes
    * whose curves should be rendered in the chart.
    */
  val visibleCurves: Signal[Set[NodeId]] =
    userSelectedNodeIds.signal
      .combineWith(satisfyingNodeIds.signal)
      .map { (user, query) => user ++ query }

  /** Node → hex colour mapping for chart curves and tree highlights.
    * Derived from current visible nodes classified into query/user/overlap
    * groups, each assigned a palette shade via `ColorAssigner`.
    */
  val nodeColorMap: Signal[Map[NodeId, String]] =
    satisfyingNodeIds.signal
      .combineWith(userSelectedNodeIds.signal, colorOverrides.signal)
      .map { (query, user, overrides) =>
        ColorAssigner.assign(query, user, overrides)
      }

  /** Complete Vega-Lite spec ready for `vegaEmbed`, derived reactively from
    * the curve cache, visible node set, and colour assignments.
    */
  val specSignal: Signal[LoadState[js.Dynamic]] =
    curveCache.signal
      .combineWith(visibleCurves, nodeColorMap)
      .map { (cacheState, visible, colorMap) =>
        cacheState match
          case LoadState.Idle       => LoadState.Idle
          case LoadState.Loading    => LoadState.Loading
          case LoadState.Failed(m)  => LoadState.Failed(m)
          case LoadState.Loaded(allCurves) =>
            // Build a NodeId-keyed map from the visible nodes that have curve data.
            val filteredByNodeId: Map[NodeId, LECNodeCurve] = visible.flatMap { nid =>
              allCurves.get(nid.value).map(nid -> _)
            }.toMap
            val relevantColors = colorMap.view.filterKeys(filteredByNodeId.contains).toMap
            LoadState.Loaded(LECSpecBuilder.build(filteredByNodeId, relevantColors))
      }

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
    satisfyingNodeIds.set(Set.empty)
    curveCache.set(LoadState.Idle)
    colorOverrides.set(Map.empty)

  /** Fetch LEC curves from the backend for the given node IDs.
    *
    * Calls the `lec-multi` endpoint, which returns structured
    * `Map[String, LECNodeCurve]` data. The Vega-Lite spec is built
    * client-side from this cache (not here).
    *
    * @param nodeIds List of node IDs to fetch curves for
    */
  def loadCurves(nodeIds: List[NodeId]): Unit =
    (keySignal.now(), selectedTreeId.now()) match
      case (Some(key), Some(treeId)) =>
        getWorkspaceLECCurvesMultiEndpoint(
          (userIdAccessor(), key, treeId, false, nodeIds)
        ).loadInto(curveCache)
      case _ => () // No workspace or tree selected — nothing to do

