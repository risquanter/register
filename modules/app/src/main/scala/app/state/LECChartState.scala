package app.state

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.chart.{ColorAssigner, LECSpecBuilder, PaletteData}
import app.core.ZJS.*
import com.risquanter.register.domain.data.{RiskTree, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, TreeId, UserId, WorkspaceKeySecret}
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
  * @param branchAccessor Returns this tab's active branch (BranchChoice) — BranchBar.
  * @param userPalette    Palette family for user-selected (Ctrl+click) nodes.
  *                       Default Aqua matches the chart's single-branch colour
  *                       system; the Compare card's instance passes Purple so
  *                       its tree highlights match its branch's curve family
  *                       in the Overlay chart.
  */
final class LECChartState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree: StrictSignal[LoadState[RiskTree]],
  globalError: Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None,
  branchAccessor: () => BranchChoice = () => BranchChoice.Main,
  userPalette: Vector[HexColor] = PaletteData.Aqua
) extends WorkspaceAnalysisEndpoints:

  // ── User selection state ──────────────────────────────────────
  /** Node IDs manually Ctrl+clicked by the user for LEC chart overlay. */
  val userSelectedNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Node IDs satisfying the current query (set from AnalyzeView). */
  val satisfyingNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Structured curve data fetched via `lec-multi` endpoint.
    * Keyed by `NodeId`, matching the domain type end-to-end (ADR-001 §4).
    *
    * Written only by the trigger pipeline below — `loadCurves`/`clearCurves`/
    * `reset` emit triggers instead of setting this Var, so a new request or a
    * reset supersedes whatever the previous request was still doing and a
    * stale response can never overwrite a newer one. Same mechanism as
    * `ScenarioDiffState.diffResult` and `TreeListState`.
    */
  val curveCache: Var[LoadState[Map[NodeId, LECNodeCurve]]] = Var(LoadState.Idle)

  private val curvesTrigger = new EventBus[Option[() => EventStream[Either[Throwable, Map[NodeId, LECNodeCurve]]]]]

  // Write-guarded: callers fire on signal ticks, not only on real
  // transitions, and Var.set emits even when the value is unchanged — an
  // unguarded write would rebuild the chart spec for a no-op.
  loadStatePipeline(curvesTrigger.events).foreach { v =>
    if curveCache.now() != v then curveCache.set(v)
  }(using unsafeWindowOwner)

  /** Manual colour overrides per node (mutated via `setColorOverride`/`clearColorOverride`). */
  private val colorOverrides: Var[Map[NodeId, HexColor]] = Var(Map.empty)

  /** Transient preview override for live swatch hover.
    * When `Some`, this temporarily replaces the colour for one node
    * in `nodeColorMap` without committing to `colorOverrides`.
    * Mutated via `setPreview`/`clearPreview`.
    */
  private val previewOverride: Var[Option[(NodeId, HexColor)]] = Var(None)

  // ── Derived signals ───────────────────────────────────────────

  /** Union of query-matched + user-selected node IDs — the set of nodes
    * whose curves should be rendered in the chart.
    */
  // .distinct at the producer (here and on nodeColorMap below), not at each
  // consumer: Var.set emits even when the value is unchanged, and every
  // consumer of these signals rebuilds something expensive (the Vega spec in
  // specSignal, AnalyzeView's compare-mode combined spec, per-row tree
  // styling). Deduplicating once here absorbs any no-op upstream write —
  // e.g. a query re-run writing an equal satisfyingNodeIds set — for all of
  // them.
  val visibleCurves: Signal[Set[NodeId]] =
    userSelectedNodeIds.signal
      .combineWith(satisfyingNodeIds.signal)
      .map { (user, query) => user ++ query }
      .distinct

  /** Node → hex colour mapping for chart curves and tree highlights.
    * Derived from current visible nodes classified into query/user/overlap
    * groups, each assigned a palette shade via `ColorAssigner`.
    */
  val nodeColorMap: Signal[Map[NodeId, HexColor]] =
    satisfyingNodeIds.signal
      .combineWith(userSelectedNodeIds.signal, colorOverrides.signal, previewOverride.signal)
      .map { (query, user, overrides, preview) =>
        val base = ColorAssigner.assign(query, user, overrides, aquaPalette = userPalette)
        preview match
          case Some((nid, hex)) if base.contains(nid) => base.updated(nid, hex)
          case _                                      => base
      }
      .distinct

  /** Complete Vega-Lite spec ready for `vegaEmbed`, derived reactively from
    * the curve cache, visible node set, and colour assignments.
    *
    * Composes `ColorAssigner.pairWithColors` → `LECSpecBuilder.build`
    * inside `LoadState.map`, eliminating manual Idle/Loading/Failed threading.
    */
  val specSignal: Signal[LoadState[js.Dynamic]] =
    // All three inputs are deduplicated (structural equality on
    // LoadState/Set/Map — visibleCurves/nodeColorMap at their definitions
    // above): every recompute below creates a NEW js.Dynamic, and
    // LECChartView re-embeds the whole Vega chart on every emission —
    // resetting the chart's own toggle params. Without the dedup, any no-op
    // Var write upstream (e.g. TreeDetailView's close-picker-on-any-click
    // → clearPreview chain) tore the chart down on every plain tree click.
    curveCache.signal.distinct
      .combineWith(visibleCurves, nodeColorMap)
      .map { (cacheState, visible, colorMap) =>
        // Short-circuit to Idle when no curves are selected.
        // Without this guard, deselecting the last node causes a transient
        // Loaded(emptySpec) emission (curveCache still holds stale data)
        // before AnalyzeView resets curveCache to Idle.  That transient causes
        // LECChartView to mount a new Vega container which immediately gets
        // replaced — leaving a stale EmbedResult that breaks the next select.
        if visible.isEmpty then LoadState.Idle
        else cacheState.map { allCurves =>
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

  /** Clear the transient preview (swatch hover ended or picker closed).
    * Skips the write when there is nothing to clear — callers fire this on
    * broad events (any click in the tree), and Var.set emits even when the
    * value is unchanged. */
  def clearPreview(): Unit =
    if previewOverride.now().isDefined then previewOverride.set(None)

  /** Reset chart state (called when tree selection changes). Also supersedes
    * an in-flight curve fetch, if one was still running. */
  def reset(): Unit =
    userSelectedNodeIds.set(Set.empty)
    satisfyingNodeIds.set(Set.empty)
    clearCurves()
    colorOverrides.set(Map.empty)
    previewOverride.set(None)

  /** Reset the curve cache to Idle, superseding an in-flight fetch. */
  def clearCurves(): Unit =
    curvesTrigger.emit(None)

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
        curvesTrigger.emit(Some(() =>
          getWorkspaceLECCurvesMultiEndpoint(
            (userIdAccessor(), key, treeId, false, nodeIds, branchAccessor())
          ).toOutcomeEventStream
        ))
      case _ => () // No workspace or tree selected — nothing to do

