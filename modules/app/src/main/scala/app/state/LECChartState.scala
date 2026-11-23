package app.state

import com.raquo.laminar.api.L.*

import scala.scalajs.js
import zio.prelude.Validation

import app.chart.{ColorAssigner, GridColumn, LECSpecBuilder, UserColumn}
import app.core.ZJS.*
import app.core.{LocalProblem, ViewFailure}
import com.risquanter.register.domain.data.{RiskTree, LECNodeCurve, MitigationSelection}
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, MitigationId, NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.http.endpoints.WorkspaceAnalysisEndpoints
import com.risquanter.register.http.requests.{CurveValuationRequest, LECCurvesMultiRequest}
import com.risquanter.register.http.responses.{LECCurvesResponse, LECNodeSeries}

/** One card's chart state, separated from tree navigation.
  *
  * Owns the card's grid — its columns, the selection each states and the
  * nodes ticked in each — and the curves fetched for it. The view changes the
  * grid only through the edits below, and a tick past
  * `LECChartState.MaxTickedNodes` is refused.
  *
  * Fetches through `lec-multi`, one valuation per distinct selection among
  * the columns holding a tick. The Vega-Lite spec is built client-side from
  * the curves of the columns not hidden.
  *
  * The fetch is driven from `AnalyzeView`, which calls `reloadCurrentCurves`
  * when `requested` changes.
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
  * @param userPalette    Palette family for ticked nodes. A signal: the family
  *                       follows this slot's own palette
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
  userPalette: Signal[Vector[HexColor]],
  origin: Signal[Option[String]]
) extends WorkspaceAnalysisEndpoints:

  // ── Grid state ────────────────────────────────────────────────
  private val gridVar: Var[CurveGrid] = Var(CurveGrid.empty)

  /** The card's grid. Changed only through the edits below. */
  val grid: StrictSignal[CurveGrid] = gridVar.signal

  /** Node IDs satisfying the current query (set from AnalyzeView). */
  val satisfyingNodeIds: Var[Set[NodeId]] = Var(Set.empty)

  /** Each requested selection's curves, by node, from the `lec-multi` answer.
    * Keyed by the selection each valuation repeats and by `NodeId`, matching
    * the domain types end-to-end (ADR-001 §3).
    *
    * Written only by the trigger pipeline below — `reloadCurrentCurves`/
    * `clearCurves`/`reset` emit triggers instead of setting this Var, so a new
    * request or a reset supersedes whatever the previous request was still
    * doing and a stale response can never overwrite a newer one. Same
    * mechanism as `ChangedNodesState.diffResult` and `TreeListState`.
    */
  val curveCache: Var[LoadState[Map[MitigationSelection, Map[NodeId, LECNodeSeries]]]] = Var(LoadState.Idle)

  // The failure side is a `ViewFailure`, because a request this state refuses
  // to send settles the cache the same way a request the server refused does.
  private val curvesTrigger =
    new EventBus[Option[() => EventStream[Either[ViewFailure, Map[MitigationSelection, Map[NodeId, LECNodeSeries]]]]]]

  // Write-guarded: callers fire on signal ticks, not only on real
  // transitions, and Var.set emits even when the value is unchanged — an
  // unguarded write would rebuild the chart spec for a no-op.
  requestPipeline[
    Either[ViewFailure, Map[MitigationSelection, Map[NodeId, LECNodeSeries]]],
    LoadState[Map[MitigationSelection, Map[NodeId, LECNodeSeries]]]
  ](
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

  /** The nodes ticked in at least one column, distinct. */
  private val tickedNodes: Signal[Set[NodeId]] = grid.map(_.tickedNodes).distinct

  /** Union of query-matched and ticked node IDs — the set of nodes whose
    * curves should be rendered in the chart.
    */
  // .distinct at the producer (here and on nodeColorMap below), not at each
  // consumer: Var.set emits even when the value is unchanged, and every
  // consumer of these signals rebuilds something expensive (the Vega spec in
  // specSignal, AnalyzeView's combined overlay spec, per-row tree
  // styling). Deduplicating once here absorbs any no-op upstream write —
  // e.g. a query re-run writing an equal satisfyingNodeIds set — for all of
  // them.
  val visibleCurves: Signal[Set[NodeId]] =
    tickedNodes
      .combineWith(satisfyingNodeIds.signal)
      .map { (ticked, query) => ticked ++ query }
      .distinct

  /** Ticked nodes with no curve in the current cache — nodes that do not
    * exist at the pinned commit. Empty at head (every ticked node has a
    * curve). Drives the transient "not present at this point in time" notice;
    * the chart already omits them. */
  val droppedSelections: Signal[Set[NodeId]] =
    tickedNodes
      .combineWith(curveCache.signal)
      .map(LECChartState.deriveDropped)
      .distinct

  /** Node → hex colour mapping for chart curves and tree highlights.
    * Derived from current visible nodes classified into query/ticked/overlap
    * groups, each assigned a palette shade via `ColorAssigner`.
    */
  val nodeColorMap: Signal[Map[NodeId, HexColor]] =
    satisfyingNodeIds.signal
      .combineWith(tickedNodes, colorOverrides.signal, previewOverride.signal, userPalette)
      .map { (query, ticked, overrides, preview, palette) =>
        val base = ColorAssigner.assign(query, ticked, overrides, aquaPalette = palette)
        preview match
          case Some((nid, hex)) if base.contains(nid) => base.updated(nid, hex)
          case _                                      => base
      }
      .distinct

  /** What the next curve request states, emitted only when it changes. */
  val requested: Signal[Map[GridColumn, (MitigationSelection, Set[NodeId])]] =
    grid.combineWith(satisfyingNodeIds.signal).map((g, query) => g.requested(query)).distinct

  /** The mitigations of the tree last loaded. Kept while a reload is in
    * flight, so the curves drawn do not change until the reload settles. */
  private val lastHeld: Signal[Set[MitigationId]] =
    selectedTree.scanLeft(LECChartState.heldBy(_).getOrElse(Set.empty)) { (previous, tree) =>
      tree match
        case LoadState.Loading => previous
        case other             => LECChartState.heldBy(other).getOrElse(Set.empty)
    }

  /** The curves the card draws, per node, each with its column. */
  val drawnCurves: Signal[LoadState[Map[NodeId, List[(GridColumn, LECNodeCurve)]]]] =
    curveCache.signal.distinct
      .combineWith(requested, grid.map(_.hidden).distinct, lastHeld)
      .map { (cache, asked, hidden, held) => cache.map(LECChartState.drawn(asked, hidden, held, _)) }
      .distinct

  /** Complete Vega-Lite spec ready for `vegaEmbed`, derived reactively from
    * the drawn curves, visible node set, and colour assignments.
    *
    * Composes `ColorAssigner.pairWithColors` → `LECSpecBuilder.build`
    * inside `LoadState.map`, eliminating manual Idle/Loading/Failed threading.
    */
  val specSignal: Signal[LoadState[js.Dynamic]] =
    // All inputs are deduplicated (structural equality on
    // LoadState/Set/Map — at their definitions above): every recompute below
    // creates a NEW js.Dynamic, and LECChartView re-embeds the whole Vega
    // chart on every emission — resetting the chart's own toggle params.
    // Without the dedup, any no-op Var write upstream (e.g. TreeDetailView's
    // close-picker-on-any-click → clearPreview chain) tore the chart down on
    // every plain tree click.
    drawnCurves
      .combineWith(visibleCurves, nodeColorMap, origin)
      .map { (drawnState, visible, colorMap, o) =>
        // Short-circuit to Idle when no node is visible.
        // Without this guard, unticking the last node causes a transient
        // Loaded(emptySpec) emission (curveCache still holds stale data)
        // before the fetch subscription resets curveCache to Idle. That
        // transient causes LECChartView to mount a new Vega container which
        // immediately gets replaced — leaving a stale EmbedResult that breaks
        // the next tick.
        if visible.isEmpty then LoadState.Idle
        else drawnState.map { curves =>
          LECSpecBuilder.build(
            ColorAssigner.pairWithColors(curves, visible, colorMap),
            origin = o,
            pinned = None,
            responsive = true,
            zoomable = true
          )
        }
      }

  // ── Grid edits (ADR-019: the owner changes its state) ────────

  private val capReachedBus: EventBus[Unit] = new EventBus[Unit]

  /** Emits when a tick is refused because `MaxTickedNodes` nodes already hold
    * one. The view that took the click shows the notice beside the tree. */
  val selectionCapReached: EventStream[Unit] = capReachedBus.events

  /** Write the grid, unless it is the one held: `Var.set` emits even when the
    * value is unchanged. */
  private def write(next: CurveGrid): Unit =
    if gridVar.now() != next then gridVar.set(next)

  /** Tick or untick one cell. A tick the limit refuses leaves the grid as it
    * is and emits on `selectionCapReached`. */
  def setTick(column: GridColumn, node: NodeId, on: Boolean): Unit =
    if on then
      gridVar.now().tick(column, node, LECChartState.MaxTickedNodes) match
        case Some(ticked) => write(ticked)
        case None         => capReachedBus.emit(())
    else write(gridVar.now().untick(column, node))

  /** Add the next free user column. Does nothing when all are held. */
  def addColumn(): Unit =
    gridVar.now().addColumn.foreach(write)

  def removeColumn(column: UserColumn): Unit =
    write(gridVar.now().removeColumn(column))

  /** Apply `edit` to a user column's selection. An edit its constructor
    * refuses leaves the grid as it is. */
  def editSelection(
    column: UserColumn,
    edit:   MitigationSelection.Enumerated => Validation[ValidationError, MitigationSelection.Enumerated]
  ): Unit =
    gridVar.now().editSelection(column, edit).toOption.foreach(write)

  def toggleHidden(column: GridColumn): Unit =
    write(gridVar.now().toggleHidden(column))

  /** Replace the grid whole, for the mirror and a copy's seed. Skips an equal one. */
  def setGrid(grid: CurveGrid): Unit =
    write(grid)

  /** Empty the grid, for a tree switch. */
  def clearGrid(): Unit =
    write(CurveGrid.empty)

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

  /** Reset chart state for a reload of the tree: the ticks go and the columns
    * stay. Also supersedes an in-flight curve fetch, if one was still running. */
  def reset(): Unit =
    write(gridVar.now().withoutTicks)
    satisfyingNodeIds.set(Set.empty)
    clearCurves()
    colorOverrides.set(Map.empty)
    previewOverride.set(None)

  /** Reset the curve cache to Idle, superseding an in-flight fetch. */
  def clearCurves(): Unit =
    curvesTrigger.emit(None)

  /** What the grid and the query ask for now. */
  private[state] def currentRequest: Map[GridColumn, (MitigationSelection, Set[NodeId])] =
    gridVar.now().requested(satisfyingNodeIds.now())

  /** Fetch the curves the grid asks for, at the current pin. Nothing asked
    * for clears the cache. Nodes absent at the pin come back missing and
    * surface via `droppedSelections`.
    *
    * A user column's selection is sent without the mitigations the loaded
    * tree does not hold, because the server refuses a request naming one the
    * tree lacks. A request this state cannot build settles the cache as a
    * failed form, with the refusals it was built with. While the tree is not
    * loaded a selection naming a mitigation cannot be checked against it, and
    * nothing is fetched: the owner calls this again once the tree arrives.
    */
  def reloadCurrentCurves(): Unit =
    val asked = currentRequest
    if asked.isEmpty then clearCurves()
    else
      (keySignal.now(), selectedTreeId.now()) match
        case (Some(key), Some(treeId)) =>
          LECChartState.requestFor(asked, LECChartState.heldBy(selectedTree.now())) match
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
                ).map(LECChartState.curvesBySelection).toOutcomeEventStream
              ))
        case _ => () // No workspace or tree selected — nothing to do

object LECChartState:
  /** The most nodes a card may hold a tick for, chosen by this project. */
  val MaxTickedNodes: Int = 13

  /** The mitigations of a loaded tree, or none while it is not loaded. */
  private def heldBy(tree: LoadState[RiskTree]): Option[Set[MitigationId]] = tree match
    case LoadState.Loaded(loaded) => Some(loaded.mitigations.map(_.id).toSet)
    case _                        => None

  /** Whether a column of `requested` states a selection that names a
    * mitigation, so that the request needs the tree's mitigations. */
  private[state] def namesMitigation(requested: Map[GridColumn, (MitigationSelection, Set[NodeId])]): Boolean =
    requested.values.exists { (selection, _) =>
      selection match
        case MitigationSelection.Selected(_)                              => true
        case MitigationSelection.Inherent | MitigationSelection.Residual => false
    }

  /** `selection` without the mitigations not in `held`. `Inherent` and
    * `Residual` name none and pass unchanged. */
  def sentSelection(selection: MitigationSelection, held: Set[MitigationId]): Validation[ValidationError, MitigationSelection] =
    selection match
      case enumerated: MitigationSelection.Enumerated => SelectionEdit.present(enumerated, held).map(_._1)
      case MitigationSelection.Residual               => Validation.succeed(MitigationSelection.Residual)

  /** The curve request for `requested`: one valuation per distinct sent
    * selection, naming the union of its columns' nodes in identifier order.
    * `None` when a requested selection names a mitigation and `held` is not
    * known. `Left` when a constructor refuses it. `requested` is not empty.
    *
    * {{{
    * // Inherent holds n1; column A applies m and holds n1
    * requestFor(asked, held = Some(Set(m)))   // Some(Right(n1 under Inherent, n1 under m))
    * requestFor(asked, held = Some(Set()))    // Some(Right(n1 under Inherent)): A now applies nothing
    * requestFor(asked, held = None)           // None
    * }}}
    */
  def requestFor(
    requested: Map[GridColumn, (MitigationSelection, Set[NodeId])],
    held:      Option[Set[MitigationId]]
  ): Option[Either[LocalProblem, LECCurvesMultiRequest]] =
    val inColumnOrder = GridColumn.all.flatMap(requested.get)
    val sentV = (held, namesMitigation(requested)) match
      case (Some(ids), _) =>
        Some(Validation.validateAll(inColumnOrder.map((selection, nodes) => sentSelection(selection, ids).map(_ -> nodes))))
      case (None, false) => Some(Validation.succeed(inColumnOrder))
      case (None, true)  => None
    sentV.map { v =>
      v.flatMap(valuationsFor)
        .flatMap(LECCurvesMultiRequest.create)
        .toEither.left.map(errors => LocalProblem.FormInvalid(errors.toList))
    }

  /** One valuation per distinct selection of `sent`, in first-seen order,
    * naming the union of the nodes asked for under it. */
  private def valuationsFor(
    sent: List[(MitigationSelection, Set[NodeId])]
  ): Validation[ValidationError, List[CurveValuationRequest]] =
    val nodesUnder = sent.groupMapReduce(_._1)(_._2)(_ ++ _)
    Validation.validateAll(
      sent.map(_._1).distinct.map(selection =>
        CurveValuationRequest.create(selection, nodesUnder(selection).toList.sortBy(_.value)))
    )

  /** The answer's valuations, keyed by the selection each repeats. */
  def curvesBySelection(response: LECCurvesResponse): Map[MitigationSelection, Map[NodeId, LECNodeSeries]] =
    response.valuations.map(valuation => valuation.selection -> valuation.curves).toMap

  /** The curves to draw: for each column of `requested` not in `hidden`, the
    * curve of each of its nodes under its sent selection, where the answer
    * holds one. A node's curves are in column order.
    *
    * {{{
    * // Inherent and A both hold n1; A is hidden
    * drawn(asked, hidden = Set(A), held, curves)   // n1 -> [Inherent -> its inherent curve]
    * }}}
    */
  def drawn(
    requested: Map[GridColumn, (MitigationSelection, Set[NodeId])],
    hidden:    Set[GridColumn],
    held:      Set[MitigationId],
    curves:    Map[MitigationSelection, Map[NodeId, LECNodeSeries]]
  ): Map[NodeId, List[(GridColumn, LECNodeCurve)]] =
    val perColumn =
      for
        column             <- GridColumn.all.filterNot(hidden.contains)
        (selection, nodes) <- requested.get(column).toList
        sent               <- sentSelection(selection, held).toOption.toList
        byNode             <- curves.get(sent).toList
        node               <- nodes.toList
        series             <- byNode.get(node).toList
      yield node -> (column -> series.curve)
    perColumn.groupMap(_._1)(_._2)

  /** Pure derivation extracted from `droppedSelections` so it's testable
    * without a Laminar harness: ticked nodes that no valuation of the loaded
    * answer holds a curve for. Every node present at the revision has a curve
    * in each valuation naming it, so a node in none is absent there.
    * Non-loaded cache → empty (nothing charted yet, nothing dropped). */
  def deriveDropped(
    selected: Set[NodeId],
    cache:    LoadState[Map[MitigationSelection, Map[NodeId, LECNodeSeries]]]
  ): Set[NodeId] =
    cache match
      case LoadState.Loaded(curves) => selected -- curves.values.flatMap(_.keySet)
      case _                        => Set.empty
