package app.state

import com.raquo.laminar.api.L.*

import scala.scalajs.js
import zio.prelude.Validation

import app.chart.{ColorAssigner, CurveVariant, LECSpecBuilder}
import app.core.ZJS.*
import app.core.{LocalProblem, ViewFailure}
import com.risquanter.register.domain.data.{RiskTree, LECNodeCurve, MitigationSelection}
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, MitigationId, NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.http.endpoints.WorkspaceAnalysisEndpoints
import com.risquanter.register.http.requests.{CurveValuationRequest, LECCurvesMultiRequest}
import com.risquanter.register.http.responses.{LECCurvesResponse, LECNodeSeries}

/** Chart selection and LEC curve data state, separated from tree navigation.
  *
  * Owns the user's manual node selection for charting (Ctrl+click picks),
  * the fetched curve data cache, and the bus-based toggle with a guard at
  * `LECChartState.MaxUserSelections`.
  *
  * Fetches each node's readings via `lec-multi`, under the mitigation
  * selection `selectionAccessor` returns. The Vega-Lite spec is built
  * client-side from the readings `display` keeps.
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
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  * @param branchAccessor Returns this tab's active branch (BranchChoice) — BranchBar.
  * @param atAccessor     Returns the commit the reads are pinned to; `None` is the head.
  * @param selectionAccessor Returns the mitigations the curve read applies.
  * @param display        Which of each node's readings the chart draws.
  * @param userPalette    Palette family for user-selected (Ctrl+click) nodes.
  *                       A signal: the family follows this slot's own palette
  *                       (`CompareSlotState.palette` / `CompareState
  *                       .baselinePalette`), so the tree highlights and curve
  *                       colours match the slot's family in the Overlay chart.
  * @param origin         Second legend line of every series ("branch · tree").
  */
final class LECChartState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree: StrictSignal[LoadState[RiskTree]],
  userIdAccessor: () => Option[UserId.Authenticated],
  branchAccessor: () => BranchChoice,
  atAccessor: () => Option[CommitHash],
  selectionAccessor: () => MitigationSelection.Enumerated,
  display: Signal[CurveDisplay],
  userPalette: Signal[Vector[HexColor]],
  origin: Signal[Option[String]]
) extends WorkspaceAnalysisEndpoints:

  // ── User selection state ──────────────────────────────────────
  /** Node IDs manually Ctrl+clicked by the user for LEC chart overlay. */
  val userSelectedNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Node IDs satisfying the current query (set from AnalyzeView). */
  val satisfyingNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Each node's curves from the `lec-multi` answer, in the form
    * `LECChartState.seriesByNode` gives: the inherent curve, and a second one
    * where the selection reaches the node. Keyed by `NodeId`, matching the
    * domain type end-to-end (ADR-001 §3).
    *
    * Written only by the trigger pipeline below — `loadCurves`/`clearCurves`/
    * `reset` emit triggers instead of setting this Var, so a new request or a
    * reset supersedes whatever the previous request was still doing and a
    * stale response can never overwrite a newer one. Same mechanism as
    * `ChangedNodesState.diffResult` and `TreeListState`.
    */
  val curveCache: Var[LoadState[Map[NodeId, List[LECNodeSeries]]]] = Var(LoadState.Idle)

  // The failure side is a `ViewFailure`, because a request this state refuses
  // to send settles the cache the same way a request the server refused does.
  private val curvesTrigger =
    new EventBus[Option[() => EventStream[Either[ViewFailure, Map[NodeId, List[LECNodeSeries]]]]]]

  // Write-guarded: callers fire on signal ticks, not only on real
  // transitions, and Var.set emits even when the value is unchanged — an
  // unguarded write would rebuild the chart spec for a no-op.
  requestPipeline[Either[ViewFailure, Map[NodeId, List[LECNodeSeries]]], LoadState[Map[NodeId, List[LECNodeSeries]]]](
    curvesTrigger.events,
    idle    = LoadState.Idle,
    loading = LoadState.Loading,
    settle  = {
      case Right(curves) => LoadState.Loaded(curves)
      case Left(failure) => LoadState.Failed(failure)
    }
  ).foreach { v =>
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

  /** The user's explicit per-node colour choices — committed overrides plus
    * any live picker preview. Branch-family independent; the Overlay compare
    * pairing gives these precedence over the side's family shade, exactly as
    * `nodeColorMap` does for the single-tree chart and tree highlights. */
  val explicitColors: Signal[Map[NodeId, HexColor]] =
    colorOverrides.signal.combineWith(previewOverride.signal).map {
      case (overrides, Some((nid, hex))) => overrides.updated(nid, hex)
      case (overrides, None)             => overrides
    }.distinct

  /** Union of query-matched + user-selected node IDs — the set of nodes
    * whose curves should be rendered in the chart.
    */
  // .distinct at the producer (here and on nodeColorMap below), not at each
  // consumer: Var.set emits even when the value is unchanged, and every
  // consumer of these signals rebuilds something expensive (the Vega spec in
  // specSignal, AnalyzeView's combined overlay spec, per-row tree
  // styling). Deduplicating once here absorbs any no-op upstream write —
  // e.g. a query re-run writing an equal satisfyingNodeIds set — for all of
  // them.
  val visibleCurves: Signal[Set[NodeId]] =
    userSelectedNodeIds.signal
      .combineWith(satisfyingNodeIds.signal)
      .map { (user, query) => user ++ query }
      .distinct

  /** User-selected nodes with no curve in the current cache — selections that
    * do not exist at the pinned commit. Empty at head (every selection has
    * a curve). Drives the transient "not present at this point in time" notice;
    * the chart already omits them. */
  val droppedSelections: Signal[Set[NodeId]] =
    userSelectedNodeIds.signal
      .combineWith(curveCache.signal)
      .map(LECChartState.deriveDropped)
      .distinct

  /** Node → hex colour mapping for chart curves and tree highlights.
    * Derived from current visible nodes classified into query/user/overlap
    * groups, each assigned a palette shade via `ColorAssigner`.
    */
  val nodeColorMap: Signal[Map[NodeId, HexColor]] =
    satisfyingNodeIds.signal
      .combineWith(userSelectedNodeIds.signal, colorOverrides.signal, previewOverride.signal, userPalette)
      .map { (query, user, overrides, preview, palette) =>
        val base = ColorAssigner.assign(query, user, overrides, aquaPalette = palette)
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
    // All inputs are deduplicated (structural equality on
    // LoadState/Set/Map — visibleCurves/nodeColorMap at their definitions
    // above): every recompute below creates a NEW js.Dynamic, and
    // LECChartView re-embeds the whole Vega chart on every emission —
    // resetting the chart's own toggle params. Without the dedup, any no-op
    // Var write upstream (e.g. TreeDetailView's close-picker-on-any-click
    // → clearPreview chain) tore the chart down on every plain tree click.
    curveCache.signal.distinct
      .combineWith(visibleCurves, nodeColorMap, origin, display)
      .map { (cacheState, visible, colorMap, o, shown) =>
        // Short-circuit to Idle when no curves are selected.
        // Without this guard, deselecting the last node causes a transient
        // Loaded(emptySpec) emission (curveCache still holds stale data)
        // before AnalyzeView resets curveCache to Idle.  That transient causes
        // LECChartView to mount a new Vega container which immediately gets
        // replaced — leaving a stale EmbedResult that breaks the next select.
        if visible.isEmpty then LoadState.Idle
        else cacheState.map { allCurves =>
          LECSpecBuilder.build(
            ColorAssigner.pairWithColors(LECChartState.readings(allCurves, shown), visible, colorMap),
            origin = o,
            pinned = None,
            responsive = true,
            zoomable = true
          )
        }
      }

  // ── Bus-based toggle (ADR-019 P2: events up) ─────────────────

  /** WriteBus exposed to views for Ctrl+click toggle events.
    *
    * Views emit node IDs into this bus; the internal observer handles
    * set toggle + selection-cap guard. This keeps mutation logic encapsulated
    * inside the state owner (ADR-019 P2: events up, not imperative calls).
    */
  private val userSelectionBus: EventBus[NodeId] = new EventBus[NodeId]
  val userSelectionToggle: WriteBus[NodeId] = userSelectionBus.writer

  private val capReachedBus: EventBus[Unit] = new EventBus[Unit]

  /** Emits when a selection is refused because `MaxUserSelections` are already
    * selected. The view that took the click shows the notice beside the tree. */
  val selectionCapReached: EventStream[Unit] = capReachedBus.events

  // Internal observer — handles the toggle + cap logic. A refused selection
  // leaves the set unchanged and emits on `selectionCapReached`.
  // Uses unsafeWindowOwner: LECChartState lives for the app lifetime.
  userSelectionBus.events.foreach { nodeId =>
    val current = userSelectedNodeIds.now()
    if current.contains(nodeId) then
      userSelectedNodeIds.update(_ - nodeId)
    else if current.size >= LECChartState.MaxUserSelections then
      capReachedBus.emit(())
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
    previewOverride.now() match
      case Some(_) => previewOverride.set(None)
      case None    => ()

  /** Replace the user selection wholesale (mirror-baseline sync). Writes
    * directly, like `reset()`, so the whole set changes in one transaction
    * rather than one toggle per node; skips the write when nothing changes. */
  def setUserSelection(ids: Set[NodeId]): Unit =
    if userSelectedNodeIds.now() != ids then userSelectedNodeIds.set(ids)

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

  /** Re-fetch the current visible selection's curves at the current pin — used
    * when only the point-in-time (`at`) changes: the selection is unchanged but
    * its curves must be re-read at the new commit, so the same curves scrub
    * through history. Empty selection → clear. Nodes absent at the pin come
    * back missing from the cache and surface via `droppedSelections`. */
  def reloadCurrentCurves(): Unit =
    (userSelectedNodeIds.now() ++ satisfyingNodeIds.now()).toList match
      case Nil => clearCurves()
      case ids => loadCurves(ids)

  /** Fetch the given nodes' readings from the backend, under the current
    * selection.
    *
    * The request names only the selected mitigations the loaded tree holds,
    * because the server refuses a request naming one the tree lacks. A
    * request this state cannot build settles the cache as a failed form, with
    * the refusals it was built with. While the tree is not loaded a non-empty
    * selection cannot be checked against it, and nothing is fetched: the
    * owner calls `reloadCurrentCurves` once the tree arrives.
    *
    * @param nodeIds List of node IDs to fetch curves for
    */
  def loadCurves(nodeIds: List[NodeId]): Unit =
    (keySignal.now(), selectedTreeId.now()) match
      case (Some(key), Some(treeId)) =>
        LECChartState.requestFor(nodeIds, selectionAccessor(), heldMitigations) match
          case None => ()
          case Some(Left(problem)) =>
            curvesTrigger.emit(Some(() => EventStream.fromValue(Left(problem), emitOnce = true)))
          case Some(Right(request)) =>
            curvesTrigger.emit(Some(() =>
              getWorkspaceLECCurvesMultiEndpoint(
                // omitAbsent = true: a rewound read must drop nodes that don't exist
                // at the pinned commit (surfaced via droppedSelections) rather
                // than fail the whole chart.
                (userIdAccessor(), key, treeId, false, request, branchAccessor(), atAccessor(), true)
              ).map(LECChartState.seriesByNode).toOutcomeEventStream
            ))
      case _ => () // No workspace or tree selected — nothing to do

  /** The mitigations of the loaded tree, or none while it is not loaded. */
  private def heldMitigations: Option[Set[MitigationId]] =
    selectedTree.now() match
      case LoadState.Loaded(tree) => Some(tree.mitigations.map(_.id).toSet)
      case _                      => None

object LECChartState:
  /** The most curves a user may select by Ctrl+click, chosen by this project. */
  val MaxUserSelections: Int = 13

  /** The curve request for `nodeIds` under `selection`, restricted to the
    * mitigations in `held`. It states the inherent valuation, and a second
    * valuation under the selection for the same nodes when the selection is
    * not inherent. `None` when the tree's mitigations are not known and the
    * selection names some, so the request cannot be built yet. `Left` when a
    * constructor refuses it.
    *
    * {{{
    * requestFor(List(n1), Inherent, held = None)           // Some(Right(n1 under Inherent))
    * requestFor(List(n1), selecting(m), held = Some(Set(m))) // Some(Right(n1 under Inherent, n1 under m))
    * requestFor(List(n1), selecting(m), held = None)       // None
    * requestFor(10001 nodes, Inherent, held = None)        // Some(Left(FormInvalid(too many nodes)))
    * }}}
    */
  def requestFor(
    nodeIds: List[NodeId],
    selection: MitigationSelection.Enumerated,
    held: Option[Set[MitigationId]]
  ): Option[Either[LocalProblem, LECCurvesMultiRequest]] =
    val presentV = (selection, held) match
      case (MitigationSelection.Inherent, _) => Some(Validation.succeed(MitigationSelection.Inherent))
      case (_, Some(ids))                    => Some(SelectionEdit.present(selection, ids).map(_._1))
      case (_, None)                         => None
    presentV.map { v =>
      v.flatMap(valuationsFor(nodeIds, _))
        .flatMap(LECCurvesMultiRequest.create)
        .toEither.left.map(errors => LocalProblem.FormInvalid(errors.toList))
    }

  /** The valuations of `nodeIds` the card asks for under `selection`: the
    * inherent one, then the one under `selection` when it applies a
    * mitigation. The node bound is the same for both, so the second is built
    * only once the first is. */
  private def valuationsFor(
    nodeIds: List[NodeId],
    selection: MitigationSelection.Enumerated
  ): Validation[ValidationError, List[CurveValuationRequest]] =
    val inherentV = CurveValuationRequest.create(MitigationSelection.Inherent, nodeIds)
    selection match
      case MitigationSelection.Inherent => inherentV.map(List(_))
      case selected @ MitigationSelection.Selected(_) =>
        inherentV.flatMap(inherent => CurveValuationRequest.create(selected, nodeIds).map(List(inherent, _)))

  /** Each node's curves from the answer's valuations, in the form the card
    * draws: the inherent valuation's curve first, then the other valuation's
    * curve where its `withMitigations` names a mitigation.
    *
    * {{{
    * // the answer holds Inherent and one selection; the selection reaches n1 and not n2
    * seriesByNode(answer) // n1 -> [inherent, selected], n2 -> [inherent]
    * }}}
    */
  def seriesByNode(response: LECCurvesResponse): Map[NodeId, List[LECNodeSeries]] =
    val (inherent, others) = response.valuations.partition(_.selection match
      case MitigationSelection.Inherent                                 => true
      case MitigationSelection.Residual | MitigationSelection.Selected(_) => false)
    val first  = inherent.flatMap(_.curves.toList)
    val second = others.flatMap(_.curves.toList).filter((_, series) => series.withMitigations.nonEmpty)
    (first ++ second).groupMap((id, _) => id)((_, series) => series)

  /** The readings a view draws, per node, under `display`. `Inherent` keeps
    * the reading that names no mitigation. `Mitigated` keeps the other reading
    * where the node has one, and the mitigation-free one where it does not,
    * because that is the node's figure under a selection that does not reach
    * it. `Both` keeps every reading. The variant is read off the reading, so a
    * curve's marker says which valuation it is under every display mode.
    *
    * {{{
    * // n1 has both readings, n2 only the mitigation-free one
    * readings(curves, Inherent)    // n1 -> [Inherent], n2 -> [Inherent]
    * readings(curves, Mitigated)   // n1 -> [Mitigated], n2 -> [Inherent]
    * readings(curves, Both)        // n1 -> [Inherent, Mitigated], n2 -> [Inherent]
    * }}}
    */
  def readings(
    curves: Map[NodeId, List[LECNodeSeries]],
    display: CurveDisplay
  ): Map[NodeId, List[(CurveVariant, LECNodeCurve)]] =
    curves.view.mapValues { series =>
      val (inherent, mitigated) = series.partition(_.withMitigations.isEmpty)
      val tagged =
        inherent.map(s => CurveVariant.Inherent -> s.curve) ++ mitigated.map(s => CurveVariant.Mitigated -> s.curve)
      display match
        case CurveDisplay.Both      => tagged
        case CurveDisplay.Inherent  => tagged.filter(_._1 == CurveVariant.Inherent)
        case CurveDisplay.Mitigated =>
          tagged.filter(_._1 == CurveVariant.Mitigated) match
            case Nil  => tagged
            case kept => kept
    }.toMap

  /** Pure derivation extracted from `droppedSelections` so it's testable
    * without a Laminar harness: selected nodes not present in the loaded curve
    * map. Every node present at the revision has its inherent curve, so a node
    * missing from the map is absent there. Non-loaded cache → empty
    * (nothing charted yet, nothing dropped). */
  def deriveDropped(selected: Set[NodeId], cache: LoadState[Map[NodeId, List[LECNodeSeries]]]): Set[NodeId] =
    cache match
      case LoadState.Loaded(m) => selected -- m.keySet
      case _                   => Set.empty

