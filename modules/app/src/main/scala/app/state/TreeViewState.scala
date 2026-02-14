package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId}
import com.risquanter.register.http.endpoints.RiskTreeEndpoints
import com.risquanter.register.http.responses.SimulationResponse

/** Reactive state for viewing server-persisted risk trees.
  *
  * Owns the data lifecycle for the tree-list and selected tree structure.
  * Views receive this as a constructor argument (ADR-019 Pattern 2).
  *
  * Extends `RiskTreeEndpoints` to access shared Tapir endpoint definitions
  * for ZJS bridge calls (established codebase pattern).
  */
final class TreeViewState extends RiskTreeEndpoints:

  // ── Available trees (summary list) ────────────────────────────
  val availableTrees: Var[LoadState[List[SimulationResponse]]] = Var(LoadState.Idle)

  // ── Selected tree (full structure with nodes) ─────────────────
  val selectedTreeId: Var[Option[TreeId]] = Var(None)
  val selectedTree: Var[LoadState[RiskTree]] = Var(LoadState.Idle)

  // ── UI state (uses NodeId per ADR-001 §7) ─────────────────────
  val expandedNodes: Var[Set[NodeId]] = Var(Set.empty)
  val selectedNodeId: Var[Option[NodeId]] = Var(None)

  // ── Chart selection state ─────────────────────────────────────
  /** Node IDs currently selected for LEC chart overlay. */
  val chartNodeIds: Var[Set[NodeId]] = Var(Set.empty)
  /** Render-ready Vega-Lite JSON spec (fetched from backend). */
  val lecChartSpec: Var[LoadState[String]] = Var(LoadState.Idle)

  // ── Actions ───────────────────────────────────────────────────

  /** Fetch all trees from the backend (summary only). */
  def loadTreeList(): Unit =
    getAllEndpoint(()).loadInto(availableTrees)

  /** Fetch the full tree structure for the given id. */
  def loadTreeStructure(id: TreeId): Unit =
    expandedNodes.set(Set.empty)
    selectedNodeId.set(None)
    chartNodeIds.set(Set.empty)
    lecChartSpec.set(LoadState.Idle)
    getTreeStructureEndpoint(id).loadOptionInto(selectedTree, "Tree not found")

  /** Select a tree by id — sets `selectedTreeId` and triggers structure fetch. */
  def selectTree(id: TreeId): Unit =
    selectedTreeId.set(Some(id))
    loadTreeStructure(id)

  def toggleExpanded(nodeId: NodeId): Unit =
    expandedNodes.update { nodes =>
      if nodes.contains(nodeId) then nodes - nodeId else nodes + nodeId
    }

  def selectNode(nodeId: NodeId): Unit =
    selectedNodeId.set(Some(nodeId))

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

  /** Fetch the LEC chart spec from the backend for the given node IDs. */
  private def loadLECChart(nodeIds: Set[NodeId]): Unit =
    selectedTreeId.now() match
      case Some(treeId) =>
        getLECChartEndpoint((treeId, nodeIds.toList)).loadInto(lecChartSpec)
      case None => () // No tree selected — nothing to do
