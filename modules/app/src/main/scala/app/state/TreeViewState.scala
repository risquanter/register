package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.responses.SimulationResponse

/** Reactive state for viewing server-persisted risk trees.
  *
  * Owns the data lifecycle for the tree-list and selected tree structure,
  * plus UI navigation state (expand/collapse/select).
  *
  * Chart selection and LEC spec concerns are delegated to `LECChartState`
  * (SRP split — see Phase F code review, Phase W.11).
  *
  * Views receive this as a constructor argument (ADR-019 Pattern 2).
  *
  * Extends `WorkspaceEndpoints` to access workspace-scoped Tapir endpoint
  * definitions for ZJS bridge calls.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param globalError    App-wide error Var (passed through to LECChartState for
  *                       the 13-cap validation error).
  * @param userIdAccessor  Returns the current user identity (None in capability-only mode).
  */
final class TreeViewState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  globalError: Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId] = () => None
) extends WorkspaceEndpoints:

  // ── Available trees (summary list) ────────────────────────────
  val availableTrees: Var[LoadState[List[SimulationResponse]]] = Var(LoadState.Idle)

  // ── Selected tree (full structure with nodes) ─────────────────
  val selectedTreeId: Var[Option[TreeId]] = Var(None)
  val selectedTree: Var[LoadState[RiskTree]] = Var(LoadState.Idle)

  // ── UI state (uses NodeId per ADR-001 §7) ─────────────────────
  val expandedNodes: Var[Set[NodeId]] = Var(Set.empty)
  val selectedNodeId: Var[Option[NodeId]] = Var(None)

  // ── Chart state (delegated) ───────────────────────────────────
  val chartState: LECChartState = LECChartState(keySignal, selectedTreeId.signal, selectedTree.signal, globalError, userIdAccessor)

  // ── Convenience accessors (preserve call-site compatibility) ──
  // Read-only signals — views should never .set() chart state directly.
  // Mutations go through userSelectionToggle bus (ADR-019: events up).
  /** Node IDs manually Ctrl+clicked for LEC chart overlay (read-only). */
  def userSelectedNodeIds: StrictSignal[Set[NodeId]] = chartState.userSelectedNodeIds.signal
  /** Structured curve data (read-only). Vega-Lite spec built client-side. */
  def curveCache: Signal[LoadState[Map[NodeId, LECNodeCurve]]] = chartState.curveCache.signal
  /** Node → hex colour map for chart curves and tree highlights (read-only). */
  def nodeColorMap: Signal[Map[NodeId, HexColor]] = chartState.nodeColorMap
  /** Vega-Lite spec lifecycle for the LEC chart (read-only). */
  def specSignal: Signal[LoadState[js.Dynamic]] = chartState.specSignal
  /** WriteBus for Ctrl+click toggle events (delegates to LECChartState). */
  def userSelectionToggle: WriteBus[NodeId] = chartState.userSelectionToggle

  // ── Actions ───────────────────────────────────────────────────

  /** Fetch all trees from the backend (summary only). */
  def loadTreeList(): Unit =
    keySignal.now() match
      case Some(key) => listWorkspaceTreesEndpoint((userIdAccessor(), key)).loadInto(availableTrees)
      case None      => () // No workspace yet — nothing to load

  /** Fetch the full tree structure for the given id. */
  def loadTreeStructure(id: TreeId): Unit =
    expandedNodes.set(Set.empty)
    selectedNodeId.set(None)
    chartState.reset()
    keySignal.now() match
      case Some(key) =>
        getWorkspaceTreeStructureEndpoint((userIdAccessor(), key, id)).loadOptionInto(selectedTree, "Tree not found")
      case None => ()

  /** Select a tree by id — sets `selectedTreeId` and triggers structure fetch. */
  def selectTree(id: TreeId): Unit =
    selectedTreeId.set(Some(id))
    loadTreeStructure(id)

  /** Re-fetch the currently selected tree structure (no-op if nothing selected). */
  def refreshSelectedTree(): Unit =
    selectedTreeId.now().foreach(loadTreeStructure)

  def toggleExpanded(nodeId: NodeId): Unit =
    expandedNodes.update { nodes =>
      if nodes.contains(nodeId) then nodes - nodeId else nodes + nodeId
    }

  def selectNode(nodeId: NodeId): Unit =
    selectedNodeId.set(Some(nodeId))

  /** Expand the tree to reveal all given nodes (Feature 3: auto-expand).
    *
    * For each node, adds its entire ancestor path to `expandedNodes`.
    * Additive — preserves existing expand/collapse state (§5.1 D3).
    */
  def expandToRevealNodes(nodeIds: Set[NodeId]): Unit =
    selectedTree.now() match
      case LoadState.Loaded(tree) =>
        val ancestors = nodeIds.flatMap(tree.index.ancestorPath)
        expandedNodes.update(_ ++ ancestors)
      case _ => ()
