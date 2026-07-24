package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.chart.PaletteData
import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.http.endpoints.WorkspaceTreeEndpoints
import com.risquanter.register.http.responses.SimulationResponse

/** Reactive state for viewing server-persisted risk trees.
  *
  * Owns the data lifecycle for the tree-list and selected tree structure,
  * plus UI navigation state (expand/collapse/select).
  *
  * Chart selection and LEC spec concerns are delegated to `LECChartState`.
  *
  * Views receive this as a constructor argument (ADR-019 Pattern 2).
  *
  * Extends workspace lifecycle/tree endpoint traits to access workspace-scoped Tapir endpoint
  * definitions for ZJS bridge calls.
  *
  * @param keySignal          Read-only signal providing the active workspace key.
  * @param treeListState      The shared, branch-keyed tree-list owner (one per
  *                           app, `Main`). This class no longer owns the list —
  *                           it reads its own branch's slice and forwards
  *                           refresh requests, so a mutation seen through any
  *                           view refreshes the one list every view reads.
  * @param globalError        App-wide error Var (passed through to LECChartState for
  *                           the 13-cap validation error).
  * @param userIdAccessor     Returns the current user identity (None in capability-only mode).
  * @param activeBranchSignal This tab's active branch (BranchChoice) — BranchBar.
  *                           A signal, not just a pull accessor: this class owns the
  *                           "branch changed, re-fetch the list and whatever tree is
  *                           selected" reactivity internally (mirrors `LECChartState`'s
  *                           internal `unsafeWindowOwner` subscription) so every consumer
  *                           of this state gets current data on a branch switch without
  *                           having to remember to wire that reload themselves.
  * @param userPalette        Palette family for user-selected (Ctrl+click) nodes,
  *                           passed through to `LECChartState`. A signal so the
  *                           family can follow the branch's user-assigned
  *                           palette (`BranchPaletteState`) — the tree
  *                           highlights match the branch's curve family.
  */
final class TreeViewState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  treeListState: TreeListState,
  globalError: Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None,
  activeBranchSignal: StrictSignal[BranchChoice] = Val(BranchChoice.Main),
  userPalette: Signal[Vector[HexColor]] = Val(PaletteData.Aqua)
) extends WorkspaceTreeEndpoints:

  private def branchAccessor(): BranchChoice = activeBranchSignal.now()

  // Set by a caller (DesignView) immediately before it reverts the active branch
  // itself (a declined confirm dialog) — suppresses exactly that one echo so the
  // revert doesn't trigger a redundant reload that could clobber what the caller
  // just chose to keep. Cleared automatically after being consumed once.
  private val suppressNextReloadVar: Var[Boolean] = Var(false)
  def suppressNextReload(): Unit = suppressNextReloadVar.set(true)

  activeBranchSignal.changes.foreach { branch =>
    if suppressNextReloadVar.now() then
      suppressNextReloadVar.set(false)
    else
      // ensureLoaded, not refresh: switching back to an already-fetched
      // branch shows its cached list instantly; only a never-fetched branch
      // costs a request. Mutations keep the cache honest via
      // `TreeListState.refresh` (see loadTreeList / TreeBuilderView).
      treeListState.ensureLoaded(branch)
      refreshSelectedTree()
  }(using unsafeWindowOwner)

  // A workspace key appearing for the first time (bootstrap via Create, or a
  // returning session's URL key) means this instance's branch needs its first
  // real fetch — a caller's own onMountCallback-driven `ensureTreeListLoaded()`
  // (e.g. TreeListView) races the key not existing yet at that mount and
  // silently no-ops. TreeListState's own key subscription already re-fetches
  // main; this covers an instance sitting on a scenario branch at that moment.
  keySignal.changes.collect { case Some(_) => () }.foreach { _ =>
    treeListState.ensureLoaded(branchAccessor())
    refreshSelectedTree()
  }(using unsafeWindowOwner)

  // ── Available trees (summary list) ────────────────────────────
  // This branch's slice of the shared, branch-keyed list — read-only; writes
  // go through TreeListState (`loadTreeList`/`ensureTreeListLoaded` below).
  val availableTrees: Signal[LoadState[List[SimulationResponse]]] =
    treeListState.listFor(activeBranchSignal)

  // ── Selected tree (full structure with nodes) ─────────────────
  val selectedTreeId: Var[Option[TreeId]] = Var(None)
  val selectedTree: Var[LoadState[RiskTree]] = Var(LoadState.Idle)

  // ── UI state (uses NodeId per ADR-001 §7) ─────────────────────
  val expandedNodes: Var[Set[NodeId]] = Var(Set.empty)
  val selectedNodeId: Var[Option[NodeId]] = Var(None)

  // ── Chart state (delegated) ───────────────────────────────────
  val chartState: LECChartState = LECChartState(keySignal, selectedTreeId.signal, selectedTree.signal, globalError, userIdAccessor, branchAccessor, userPalette)

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

  /** Commit a manual colour override for a node's chart curve. */
  def setColorOverride(nodeId: NodeId, hex: HexColor): Unit = chartState.setColorOverride(nodeId, hex)
  /** Remove the manual colour override for a node (revert to auto). */
  def clearColorOverride(nodeId: NodeId): Unit = chartState.clearColorOverride(nodeId)
  /** Temporarily preview a colour for a node (live swatch hover). */
  def setPreview(nodeId: NodeId, hex: HexColor): Unit = chartState.setPreview(nodeId, hex)
  /** Clear the transient preview (swatch hover ended or picker closed). */
  def clearPreview(): Unit = chartState.clearPreview()

  // ── Actions ───────────────────────────────────────────────────

  /** Force-refresh this branch's tree list (mutation just landed, or the
    * user clicked refresh/retry). Supersedes an in-flight fetch for the
    * same branch; other branches are untouched. */
  def loadTreeList(): Unit =
    treeListState.refresh(branchAccessor())

  /** Fetch this branch's tree list only if it isn't already loaded or in
    * flight — the mount-time entry point (TreeListView). */
  def ensureTreeListLoaded(): Unit =
    treeListState.ensureLoaded(branchAccessor())

  /** Fetch the full tree structure for the given id. */
  def loadTreeStructure(id: TreeId): Unit =
    expandedNodes.set(Set.empty)
    selectedNodeId.set(None)
    chartState.reset()
    keySignal.now() match
      case Some(key) =>
        getWorkspaceTreeStructureEndpoint((userIdAccessor(), key, id, branchAccessor())).loadOptionInto(selectedTree, "Tree not found")
      case None => ()

  /** Select a tree by id — sets `selectedTreeId` and triggers structure fetch. */
  def selectTree(id: TreeId): Unit =
    selectedTreeId.set(Some(id))
    loadTreeStructure(id)

  /** Re-fetch the currently selected tree structure (no-op if nothing selected). */
  def refreshSelectedTree(): Unit =
    selectedTreeId.now().foreach(loadTreeStructure)

  /** Clear the tree selection entirely — id, loaded structure, and chart
    * state, which must be cleared together. No-op when nothing is selected. */
  def deselectTree(): Unit =
    if selectedTreeId.now().isDefined then
      selectedTreeId.set(None)
      selectedTree.set(LoadState.Idle)
      chartState.reset()

  def toggleExpanded(nodeId: NodeId): Unit =
    expandedNodes.update { nodes =>
      if nodes.contains(nodeId) then nodes - nodeId else nodes + nodeId
    }

  def selectNode(nodeId: NodeId): Unit =
    selectedNodeId.set(Some(nodeId))

  /** Expand the tree to reveal all given nodes.
    *
    * For each node, adds its entire ancestor path to `expandedNodes`.
    * Additive — preserves existing expand/collapse state.
    */
  def expandToRevealNodes(nodeIds: Set[NodeId]): Unit =
    selectedTree.now() match
      case LoadState.Loaded(tree) =>
        val ancestors = nodeIds.flatMap(tree.index.ancestorPath)
        expandedNodes.update(_ ++ ancestors)
      case _ => ()
