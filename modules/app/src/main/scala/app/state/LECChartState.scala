package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.WorkspaceEndpoints

/** Chart selection and LEC spec state, separated from tree navigation.
  *
  * Receives read-only signals from `TreeViewState` for the currently selected
  * tree context. Owns the chart selection lifecycle: which nodes are selected
  * for charting, and the fetched Vega-Lite spec.
  *
  * This separation follows the SRP split identified in the Phase F code review:
  * navigation state (expand/collapse/select) vs chart state (chart selection/spec).
  * Phase H's `LECState` (stale tracking for SSE) will extend this class.
  *
  * Extends `WorkspaceEndpoints` to access workspace-scoped Tapir endpoint
  * definitions for ZJS bridge calls.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param selectedTreeId Signal for the currently selected tree ID.
  * @param selectedTree   Signal for the currently loaded tree structure.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class LECChartState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree: StrictSignal[LoadState[RiskTree]],
  userIdAccessor: () => Option[UserId] = () => None
) extends WorkspaceEndpoints:

  // ── Chart selection state ─────────────────────────────────────
  /** Node IDs currently selected for LEC chart overlay. */
  val chartNodeIds: Var[Set[NodeId]] = Var(Set.empty)
  /** Render-ready Vega-Lite JSON spec (fetched from backend). */
  val lecChartSpec: Var[LoadState[String]] = Var(LoadState.Idle)

  // ── Actions ───────────────────────────────────────────────────

  /** Reset chart state (called when tree selection changes). */
  def reset(): Unit =
    chartNodeIds.set(Set.empty)
    lecChartSpec.set(LoadState.Idle)

  /** Toggle a node's inclusion in the chart selection.
    *
    * For portfolio nodes: toggles the portfolio and all its direct children
    * as a group. For leaf nodes: toggles just the leaf.
    * After updating `chartNodeIds`, triggers a backend LEC chart fetch.
    */
  def toggleChartSelection(nodeId: NodeId): Unit =
    selectedTree.now() match
      case LoadState.Loaded(tree) =>
        // Resolve the group: nodeId + direct children if portfolio
        val node     = tree.nodes.find(_.id == nodeId)
        val childIds = node.collect { case p: RiskPortfolio => p.childIds.toSet }.getOrElse(Set.empty)
        val group    = childIds + nodeId

        val current = chartNodeIds.now()
        val updated = if current.contains(nodeId) then current -- group else current ++ group
        chartNodeIds.set(updated)

        if updated.nonEmpty then loadLECChart(updated)
        else lecChartSpec.set(LoadState.Idle)
      case _ => () // No tree loaded — nothing to do

  /** Fetch the LEC chart spec from the backend for the given node IDs.
    *
    * Public to allow cross-component triggers (e.g. query result
    * "View LEC" button in AnalyzeView — T3.5).
    */
  def loadLECChart(nodeIds: Set[NodeId]): Unit =
    (keySignal.now(), selectedTreeId.now()) match
      case (Some(key), Some(treeId)) =>
        getWorkspaceLECChartEndpoint((userIdAccessor(), key, treeId, nodeIds.toList)).loadInto(lecChartSpec)
      case _ => () // No workspace or tree selected — nothing to do
