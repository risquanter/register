package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.RiskTree
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

  // ── Actions ───────────────────────────────────────────────────

  /** Fetch all trees from the backend (summary only). */
  def loadTreeList(): Unit =
    getAllEndpoint(()).loadInto(availableTrees)

  /** Fetch the full tree structure for the given id. */
  def loadTreeStructure(id: TreeId): Unit =
    expandedNodes.set(Set.empty)
    selectedNodeId.set(None)
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
