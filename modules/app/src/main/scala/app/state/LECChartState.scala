package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.chart.{ColorAssigner, LECSpecBuilder}
import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.http.endpoints.WorkspaceAnalysisEndpoints

/** Chart selection and LEC curve data state, separated from tree navigation.
  *
  * Owns the user's manual node selection for charting (Ctrl+click picks),
  * the fetched curve data cache, and the bus-based toggle with a 13-cap guard.
  *
  * Fetches structured `Map[NodeId, LECNodeCurve]` via `lec-multi`.
  * The Vega-Lite spec is built client-side from this data.
  *
  * The reactive `loadCurves` call that merges query-matched nodes with
  * user selections is driven from `AnalyzeView`, where both
  * `AnalyzeQueryState.satisfyingNodeIds` and this state's
  * `userSelectedNodeIds` are in scope.
  *
  * Extends `WorkspaceAnalysisEndpoints` to access workspace-scoped Tapir endpoint
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
) extends WorkspaceAnalysisEndpoints:

  // ── User selection state ──────────────────────────────────────
  /** Node IDs manually Ctrl+clicked by the user for LEC chart overlay. */
  val userSelectedNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Node IDs satisfying the current query (set from AnalyzeView). */
  val satisfyingNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Structured curve data fetched via `lec-multi` endpoint.
    * Keyed by `NodeId`, matching the domain type end-to-end (ADR-001 §4).
    */
  val curveCache: Var[LoadState[Map[NodeId, LECNodeCurve]]] = Var(LoadState.Idle)

  /** Manual colour overrides per node (mutated via `setColorOverride`/`clearColorOverride`). */
  private val colorOverrides: Var[Map[NodeId, HexColor]] = Var(Map.empty)

  /** Transient preview override for live swatch hover (P5 §5.6).
    * When `Some`, this temporarily replaces the colour for one node
    * in `nodeColorMap` without committing to `colorOverrides`.
    * Mutated via `setPreview`/`clearPreview`.
    */
  private val previewOverride: Var[Option[(NodeId, HexColor)]] = Var(None)

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
  val nodeColorMap: Signal[Map[NodeId, HexColor]] =
    satisfyingNodeIds.signal
      .combineWith(userSelectedNodeIds.signal, colorOverrides.signal, previewOverride.signal)
      .map { (query, user, overrides, preview) =>
        val base = ColorAssigner.assign(query, user, overrides)
        preview match
          case Some((nid, hex)) if base.contains(nid) => base.updated(nid, hex)
          case _                                      => base
      }

  /** Complete Vega-Lite spec ready for `vegaEmbed`, derived reactively from
    * the curve cache, visible node set, and colour assignments.
    *
    * Composes `ColorAssigner.pairWithColors` → `LECSpecBuilder.build`
    * inside `LoadState.map`, eliminating manual Idle/Loading/Failed threading.
    */
  val specSignal: Signal[LoadState[js.Dynamic]] =
    curveCache.signal
      .combineWith(visibleCurves, nodeColorMap)
      .map { (cacheState, visible, colorMap) =>
        cacheState.map { allCurves =>
          LECSpecBuilder.build(
            ColorAssigner.pairWithColors(allCurves, visible, colorMap)
          )
        }
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

  /** Commit a manual colour override for a single node. */
  def setColorOverride(nodeId: NodeId, hex: HexColor): Unit =
    colorOverrides.update(_ + (nodeId -> hex))
    previewOverride.set(None)

  /** Remove the manual colour override for a node (revert to auto). */
  def clearColorOverride(nodeId: NodeId): Unit =
    colorOverrides.update(_ - nodeId)
    previewOverride.set(None)

  /** Temporarily preview a colour for a node (live swatch hover). */
  def setPreview(nodeId: NodeId, hex: HexColor): Unit =
    previewOverride.set(Some((nodeId, hex)))

  /** Clear the transient preview (swatch hover ended or picker closed). */
  def clearPreview(): Unit =
    previewOverride.set(None)

  /** Reset chart state (called when tree selection changes). */
  def reset(): Unit =
    userSelectedNodeIds.set(Set.empty)
    satisfyingNodeIds.set(Set.empty)
    curveCache.set(LoadState.Idle)
    colorOverrides.set(Map.empty)
    previewOverride.set(None)

  /** Fetch LEC curves from the backend for the given node IDs.
    *
    * Calls the `lec-multi` endpoint, which returns structured
    * `Map[NodeId, LECNodeCurve]` data. The Vega-Lite spec is built
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

