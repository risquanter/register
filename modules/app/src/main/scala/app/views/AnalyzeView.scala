package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.components.{SplitPane, FormInputs, BranchBar, BranchCard, SlotPalettePicker, Icons, HistorySlider}
import app.chart.{LECSpecBuilder, ColorAssigner, CompareColorAssigner, PaletteData, PinnedAxes}
import app.state.{TreeViewState, TreeHistoryState, AnalyzeQueryState, LoadState, ChartHoverBridge, ChartParamStore, ScenarioState, AppConfigState, CompareLayout, CompareState, CompareSlot, CompareSlotState, CompareTarget, SlotCoordinate, toCoordinate}
import com.risquanter.register.domain.data.{LECNodeCurve, RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, NodeId, TreeId, ScenarioName}
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Analyze view — tree inspection, query pane, and LEC chart (ADR-028).
  *
  * Structural wrapper that composes components into the Analyze layout:
  *
  *   SplitPane.horizontal(75% | 25%)
  *   ├── LEFT:  analysis-panel
  *   │   ├── Query textarea (monospace) + Run button + parse error
  *   │   ├── QueryResultCard (satisfied badge, proportion, matches)
  *   │   └── LECChartView in an adaptive panel (page-level scroll)
  *   └── RIGHT: saved-tree-panel (Load Trees)
  *       ├── "Load Trees" title
  *       ├── slot-card stack — one BranchCard per slot, each with its
  *       │   (branch, tree) picker in the header and its own TreeDetailView in
  *       │   the body: baseline first (swatch + selects + refresh + eye), then
  *       │   one comparand card per row (swatch + selects + mirror + eye +
  *       │   remove; ✎ markers + mirror lock in the body)
  *       └── footer: add-tree button + sliding Overlay ⇄ Side-by-side toggle
  *
  * Owns reactive subscriptions for:
  *   - Auto-expand: expands tree to reveal query-matched nodes
  *   - Auto-LEC: builds and fires chart requests when either the query set
  *     or manual user set changes
  */
object AnalyzeView:

  /** @param compareSlots One bundle per comparand pool slot (cap:
    *                     `CompareState.ComparedSlotCount`): each carries its
    *                     own `TreeViewState` — an independent tree view and
    *                     Ctrl+click surface on the slot's chosen branch —
    *                     its own hash-diff state, and its palette family. A
    *                     pool slot is live only while its index is in
    *                     `compareState.rows`. Selection identity across
    *                     branches is the pair (branch, node).
    */
  def apply(
    treeViewState: TreeViewState,
    queryState: AnalyzeQueryState,
    scenarioState: ScenarioState,
    appConfigState: AppConfigState,
    compareState: CompareState,
    compareSlots: Vector[CompareSlot],
    baselineHistoryState: TreeHistoryState
  ): HtmlElement =

    /** The baseline slot's colour family (default Aqua). Slot-keyed, not
      * branch: colours the baseline's overlay curves, its side-by-side panel
      * and card swatch, matching the single chart's own `nodeColorMap` (whose
      * `TreeViewState` is built on this same `baselinePalette` in `Main`). */
    val activePalette: Signal[Vector[HexColor]] =
      compareState.baselinePalette.signal

    /** Fire query against selected tree. No-op if no tree is selected. */
    def runQuery(): Unit = queryState.executeQuery()

    // Hover bridges — one per chart surface. The active branch's chart and
    // tree card share `hoverBridge`; each slot's card and its side-by-side
    // panel share that slot's own bridge, so hovering a row highlights only
    // its own card, and in side-by-side each panel's chart↔tree hover works
    // with plain node-id curve ids.
    val hoverBridge = new ChartHoverBridge()
    val slotHoverBridges = compareSlots.map(_ => new ChartHoverBridge())

    // One param store for every chart surface in this view: the annotation
    // toggles and interpolation choice survive not only re-embeds but also
    // display-mode switches, which replace the chart component instances.
    val chartParams = new ChartParamStore

    // ── Reactive chart node list ───────────────────────────────────
    // Merges query-matched nodes with user Ctrl+click selections.
    // Fires POST to /lec-multi on any change to either set (debounced 100ms).
    // Also keeps chartState.satisfyingNodeIds in sync for visibleCurves.
    val chartNodeIds: Signal[Option[List[NodeId]]] =
      queryState.satisfyingNodeIds
        .combineWith(treeViewState.chartState.userSelectedNodeIds.signal)
        .map { (querySet, userSet) =>
          (querySet ++ userSet).toList match
            case Nil   => None
            case nodes => Some(nodes)
        }

    // ── Compare slots ──────────────────────────────────────────────
    // Same node set as `chartNodeIds` above (query ∪ user selection) — reuse
    // `chartState`'s own derivation instead of recomputing it from scratch.
    val visibleNodeIds: Signal[Set[NodeId]] = treeViewState.chartState.visibleCurves

    /** Per-slot ✎ markers. A slot outside `rows`, or one with no chosen
      * target, holds a reset (empty) diff state, so no extra gating is
      * needed here. */
    val slotChangedNodeIds: Vector[Signal[Set[NodeId]]] =
      compareSlots.map(_.diffState.changedNodeIds)

    /** Comparand slots taking part right now, as (poolIdx, branch) in row
      * order — `engagedSlots` run over the row-ordered targets with its
      * returned row positions translated back to pool indices. */
    def engagedPoolSlots(
      rowTs: Vector[(Int, CompareTarget)],
      activeBranch: BranchChoice,
      activeTid: Option[TreeId],
      activeAt: Option[CommitHash]
    ): Vector[(Int, BranchChoice)] =
      engagedSlots(rowTs.map(_._2), activeBranch, activeTid, activeAt)
        .map { (rowPos, branch) => (rowTs(rowPos)._1, branch) }

    /** Per-slot Overlay inputs: the slot's curve cache (deduplicated for the
      * same reason as below), its card's own selection — independent of the
      * tab's own, user Ctrl+clicks only (the query pane runs against the
      * tab's active branch, so a slot's query set stays empty) — its chosen
      * target, and its palette family (the branch's assignment, or the
      * slot's default). */
    val slotOverlayInputs: Signal[Vector[(LoadState[Map[NodeId, LECNodeCurve]], Set[NodeId], CompareTarget, Vector[HexColor])]] =
      Signal.combineSeq(compareSlots.map { slot =>
        slot.treeViewState.curveCache.distinct
          .combineWith(slot.treeViewState.chartState.visibleCurves, slot.state.target.signal, slot.state.palette.signal)
      }).map(_.toVector)

    /** The single chart surface (used for both layouts whenever the panel
      * grid is not shown). No visible comparand side → the baseline's own
      * single-branch spec, untouched (baseline hidden with no comparands →
      * Idle). Overlay with visible comparands → the baseline (unless hidden)
      * plus every visible comparand contributes its own selection's curves,
      * coloured by branch family (`CompareColorAssigner`), labelled with a
      * stable per-pool-slot label (`active`/`s1`/`s2`…), so a slot keeps its
      * chart identity regardless of row position.
      *
      * A side whose curves haven't landed yet simply contributes nothing on
      * this emission and fills in when its fetch settles — an already-drawn
      * partial chart is worth more than blanking to a loading state. Only a
      * selection with no curves at all shows Loading. */
    val combinedSpecSignal: Signal[LoadState[js.Dynamic]] =
      // curveCache (every instance) is deduplicated here for the same reason
      // specSignal dedupes it internally (LECChartState): each map run below
      // builds a NEW js.Dynamic in overlay, and LECChartView re-embeds per
      // emission. The other inputs are already dedup-safe: specSignal and the
      // visible sets are distinct at their producers; layout/rows/hidden/
      // targets/activeBranch only change on genuine user action.
      compareState.layout.signal
        .combineWith(
          treeViewState.chartState.specSignal
            .combineWith(treeViewState.curveCache.distinct, visibleNodeIds, activePalette),
          compareState.rowTargets
            .combineWith(compareState.hiddenFlags, compareState.baselineHidden.signal)
            .combineWith(slotOverlayInputs, scenarioState.activeBranch.signal, treeViewState.selectedTreeId.signal, compareState.baselineAt.signal)
        )
        .map {
          case (layout, (singleSpec, thisCurves, thisVisible, thisPalette),
                (rowTs, hidden, baselineHidden, slotInputs, activeBranch, activeTid, baselineAt)) =>
            // Visible comparand sides: engaged (tab-collision + duplicate-pair
            // dedup) minus the eye-hidden ones. Hide is a display filter only —
            // a hidden earlier slot still wins the dedup, so its duplicate
            // stays inert regardless.
            val visibleComparands =
              engagedPoolSlots(rowTs, activeBranch, activeTid, baselineAt).collect { case (pi, _) if !hidden(pi) => pi }
            (layout, visibleComparands.isEmpty) match
              case (_, true) =>
                if baselineHidden then LoadState.Idle else singleSpec
              case (CompareLayout.SideBySide, false) =>
                // The panel grid renders these; this signal isn't mounted then.
                if baselineHidden then LoadState.Idle else singleSpec
              case (CompareLayout.Overlay, false) =>
                val baselineSide =
                  if baselineHidden then None
                  else Some(CompareColorAssigner.OverlaySide(loadedOrEmpty(thisCurves), thisVisible, thisPalette, "active"))
                val comparandSides = visibleComparands.map { pi =>
                  val (curves, visible, _, palette) = slotInputs(pi)
                  (pi, curves, visible, palette)
                }
                val curvesToCheck =
                  (if baselineHidden then Vector.empty else Vector(thisCurves)) ++ comparandSides.map(_._2)
                curvesToCheck.collectFirst { case LoadState.Failed(msg) => msg } match
                  case Some(msg) => LoadState.Failed(msg)
                  case None =>
                    val sides = baselineSide.toVector ++ comparandSides.map { (pi, curves, visible, palette) =>
                      CompareColorAssigner.OverlaySide(loadedOrEmpty(curves), visible, palette, s"s${pi + 1}")
                    }
                    val paired = CompareColorAssigner.pairForOverlay(sides)
                    if paired.nonEmpty then LoadState.Loaded(LECSpecBuilder.buildFromSeries(paired, responsive = true, zoomable = true))
                    else if (baselineHidden || thisVisible.isEmpty) && comparandSides.forall(_._3.isEmpty) then LoadState.Idle
                    else LoadState.Loading
        }

    /** Per-slot Side-by-side inputs — as `slotOverlayInputs` plus the slot's
      * own node colour map, since each panel keeps its normal single-branch
      * node colours. */
    val slotPanelInputs: Signal[Vector[(LoadState[Map[NodeId, LECNodeCurve]], Set[NodeId], Map[NodeId, HexColor], CompareTarget)]] =
      Signal.combineSeq(compareSlots.map { slot =>
        slot.treeViewState.curveCache.distinct
          .combineWith(slot.treeViewState.chartState.visibleCurves, slot.treeViewState.nodeColorMap, slot.state.target.signal)
      }).map(_.toVector)

    /** Side-by-side panel specs — the active branch's panel plus one per
      * slot (Idle for a slot that isn't engaged), each branch's own curves
      * in its own normal single-branch node colours, every panel pinned to
      * the shared extents of all engaged panels' visible curves (per-panel
      * autoscaling would silently defeat the comparison). Emitted together
      * so every panel always shares one `PinnedAxes` computation. */
    val sideBySideSpecs: Signal[(LoadState[js.Dynamic], Vector[LoadState[js.Dynamic]])] =
      compareState.baselineHidden.signal
        .combineWith(
          treeViewState.curveCache.distinct
            .combineWith(visibleNodeIds, treeViewState.nodeColorMap),
          compareState.rowTargets
            .combineWith(compareState.hiddenFlags, slotPanelInputs, scenarioState.activeBranch.signal, treeViewState.selectedTreeId.signal, compareState.baselineAt.signal)
        )
        .map {
          case (baselineHidden, (thisCurves, thisVisible, thisColors),
                (rowTs, hidden, slotInputs, activeBranch, activeTid, baselineAt)) =>
            // Panels shown = engaged (tab collision + duplicate-pair dedup)
            // minus the eye-hidden ones — so the panel grid always agrees with
            // the overlay and the card stack on which sides show. Hidden panels
            // also drop out of the shared pinned-axis extents.
            val visiblePool =
              engagedPoolSlots(rowTs, activeBranch, activeTid, baselineAt).collect { case (pi, _) if !hidden(pi) => pi }.toSet
            val thisPairs = ColorAssigner.pairWithColors(loadedOrEmpty(thisCurves), thisVisible, thisColors)
            val slotPairs = slotInputs.zipWithIndex.map { case ((curves, visible, colors, _), pi) =>
              if visiblePool.contains(pi)
              then Some(ColorAssigner.pairWithColors(loadedOrEmpty(curves), visible, colors))
              else None
            }
            val baselinePairsForPin = if baselineHidden then Vector.empty else thisPairs
            val pinned = PinnedAxes.fromCurves((baselinePairsForPin ++ slotPairs.flatten.flatten).map(_._1))
            val slotSpecs = slotInputs.zip(slotPairs).map {
              case ((curves, visible, _, _), Some(pairs)) => panelSpec(curves, visible, pairs, pinned)
              case (_, None)                              => LoadState.Idle
            }
            (panelSpec(thisCurves, thisVisible, thisPairs, pinned), slotSpecs)
        }

    // ── Node lookup for name resolution in QueryResultCard ───────
    val nodeLookup: Signal[Map[NodeId, RiskNode]] =
      treeViewState.selectedTree.signal.map {
        case LoadState.Loaded(tree) => tree.nodes.map(n => n.id -> n).toMap
        case _                      => Map.empty
      }

    val analyzeLeftPanel = div(
      cls := "analyze-left-panel",
      // ── Reactive subscriptions (bound to element lifecycle) ────
      // Auto-expand: reveal query-matched nodes in tree
      queryState.queryResult.signal.changes --> {
        case LoadState.Loaded(resp) if resp.satisfied && resp.satisfyingNodeIds.nonEmpty =>
          treeViewState.expandToRevealNodes(resp.satisfyingNodeIds.toSet)
        case _ => ()
      },
      // Sync satisfyingNodeIds into chartState for visibleCurves derivation
      queryState.satisfyingNodeIds.changes --> { ids =>
        treeViewState.chartState.satisfyingNodeIds.set(ids)
      },
      // Clear the previous tree's query result on tree switch — mirrors
      // chartState.reset() (called from TreeViewState.loadTreeStructure)
      // so a stale query's matched nodes can't leak into the newly
      // selected tree's chart / Compare curve fetch.
      treeViewState.selectedTreeId.signal.changes --> { _ => queryState.resetResult() },
      // The previously selected tree doesn't exist on the newly chosen branch —
      // nothing valid left to point at. Clears the tree picker back to "nothing
      // selected" so it matches TreeDetailView's own placeholder for this event,
      // giving a genuine "back to the initial state" result rather than a picker
      // still showing a tree that no longer resolves to anything. Unlike
      // DesignView's handling of the same event, this never needs to confirm
      // first — Analyze has no in-progress draft that this could discard.
      treeViewState.selectedTree.signal.changes
        .collect { case LoadState.Failed("Tree not found") => () } --> { _ =>
          treeViewState.selectedTreeId.set(None)
        },
      // Auto-LEC: fire curve fetch on any change to either node set
      chartNodeIds.changes
        .collect { case Some(ids) => ids }
        .debounce(100) --> { nodeIds =>
          treeViewState.chartState.loadCurves(nodeIds)
        },
      // Reset curve cache to idle when both sets become empty
      chartNodeIds.changes
        .collect { case None => () } --> { _ =>
          treeViewState.chartState.clearCurves()
        },
      // Per slot: reload the diff whenever the selected tree, the tab's own
      // active branch, or the slot's target changes. Slot empty → reset to
      // Idle. Debounced in
      // step with the curve-fetch subscription below — both read the slot's
      // target, so an undebounced diff fetch racing ahead of the (still-
      // debounced) curve fetch would briefly label stale curve data with
      // the newly-chosen branch's name.
      compareSlots.map { slot =>
        treeViewState.selectedTreeId.signal
          .combineWith(slot.state.target.signal, scenarioState.activeBranch.signal, compareState.baselineAt.signal)
          .changes.debounce(100) --> {
            case (Some(activeTid), CompareTarget.Target(coord), activeBranch, baselineAt)
                if coord.treeOverride.forall(_ == activeTid) &&
                   (coord.branch != activeBranch || coord.at != baselineAt) =>
              // Same-tree only (shared node lineage), and the two sides differ in
              // branch or in point-in-time — cross-branch diff, or past-vs-current
              // on one branch when a history stop is rewound. A cross-tree slot
              // (override names a different tree) has no lineage; two sides at the
              // same (branch, pin) have nothing to diff. Both get no diff.
              slot.diffState.loadDiff(activeTid, activeBranch, baselineAt, coord.branch, coord.at)
            case _ =>
              slot.diffState.reset()
          }
      },
      // Per slot: load the slider's stops for the slot's (effective tree,
      // branch); reset when the slot holds no tree.
      compareSlots.map { slot =>
        treeViewState.selectedTreeId.signal
          .combineWith(slot.state.target.signal)
          .changes.debounce(100) --> {
            case (Some(activeTid), CompareTarget.Target(coord)) =>
              slot.historyState.loadHistory(coord.treeOverride.getOrElse(activeTid), coord.branch)
            case _ =>
              slot.historyState.reset()
          }
      },
      // Baseline slider stops: the active tree's history on the active branch.
      treeViewState.selectedTreeId.signal
        .combineWith(scenarioState.activeBranch.signal)
        .changes.debounce(100) --> {
          case (Some(tid), branch) => baselineHistoryState.loadHistory(tid, branch)
          case _                   => baselineHistoryState.reset()
        },
      // Per slot: reset the slot's target when it stops being a
      // valid choice. Two independent triggers, deliberately kept separate so
      // a scenario-list refresh can never be mistaken for a branch change:
      //   - the tab's own (branch, effective tree) pair moving onto the slot
      //     (a side compared against itself) — driven by active-branch changes
      //     only;
      //   - the scenario the slot names being deleted from the shared list
      //     (reachable via any view's per-row delete) — driven by list changes
      //     only, and trusted only from a Loaded list.
      // Without this the picker's option disappears (DOM shows the placeholder)
      // while the Var keeps the stale value, so fetches keep firing against it.
      // Tree browsing on the active tab is NOT a reset trigger: a collision
      // caused by switching the active tree onto the slot leaves the slot
      // chosen but disengaged (the effective-tree subscription handles that)
      // and re-engages when the tree moves away — reversible, never destroyed.
      // Each reads its own value and the tab's selected tree via now()
      // (ADR-019 Pattern 6).
      compareSlots.map { slot =>
        scenarioState.activeBranch.signal.changes --> { active =>
          slot.state.target.now() match
            // Reads the baseline pin via now() but is NOT triggered by it — a
            // rewind must never clear a comparand's chosen target.
            case CompareTarget.Target(coord)
                if coord.collidesWith(active, treeViewState.selectedTreeId.now(), compareState.baselineAt.now()) =>
              slot.state.target.set(CompareTarget.NotChosen)
            case _ => ()
        }
      },
      compareSlots.map { slot =>
        scenarioState.scenarios.changes --> { list =>
          slot.state.target.now() match
            case CompareTarget.Target(coord) =>
              val deleted = coord.branch match
                case BranchChoice.Scenario(name) =>
                  list match
                    case LoadState.Loaded(l) => !l.exists(_.name == name)
                    case _                   => false
                case BranchChoice.Main => false
              if deleted then slot.state.target.set(CompareTarget.NotChosen)
            case CompareTarget.NotChosen => ()
        }
      },
      // Per slot: a slot PINNED to a tree that no longer exists on its branch
      // (its branch was re-pointed while the override was kept, or the tree was
      // deleted) would otherwise dead-end with no recovery. Clear the whole
      // slot when its pinned tree is absent from the slot branch's loaded tree
      // list — the same authoritative-list evidence the deletion reset above
      // uses. A follow-active slot (no pinned override) is never touched: a
      // tree the active tab browses to that is missing on the slot's branch
      // disengages reversibly rather than invalidating the choice.
      compareSlots.map { slot =>
        slot.treeViewState.availableTrees.changes --> { list =>
          slot.state.target.now() match
            case CompareTarget.Target(coord) =>
              val pinnedGone = coord.treeOverride match
                case Some(tid) =>
                  list match
                    case LoadState.Loaded(trees) => !trees.exists(_.id == tid)
                    case _                       => false
                case None => false
              if pinnedGone then slot.state.target.set(CompareTarget.NotChosen)
            case CompareTarget.NotChosen => ()
        }
      },
      // Per slot: point the slot's tree view at its EFFECTIVE tree — the
      // slot's tree override if it pins one (cross-tree compare), otherwise
      // the tab's own selected tree (the default: the slot follows the active
      // tree). Drop its state when the row is torn down (target cleared),
      // there is no effective tree, or the coordinate collides with the tab's
      // own (branch, tree) pair — a colliding slot is inert and must not load
      // a duplicate of the active card, seed, or mutate the active card's
      // selection through the empty-baseline root fallback in seedCompareCard.
      // No "wait for the branch to catch up" guard is needed: the slot's
      // branch is derived from the same `target`, so it never lags the tree
      // across a transaction (the slot's own branch subscription refetches on
      // a branch change independently).
      compareSlots.map { slot =>
        // baselineAt joins the combine so a rewind re-evaluates the collision:
        // a slot that was a same-head duplicate re-engages (and loads its tree)
        // once the baseline moves off head, and vice versa.
        treeViewState.selectedTreeId.signal
          .combineWith(slot.state.target.signal, scenarioState.activeBranch.signal, compareState.baselineAt.signal)
          .changes --> {
            case (activeTid, CompareTarget.Target(coord), activeBranch, baselineAt) =>
              coord.effectiveTree(activeTid) match
                case Some(tid) if !coord.collidesWith(activeBranch, activeTid, baselineAt) =>
                  if !slot.treeViewState.selectedTreeId.now().contains(tid) then
                    slot.treeViewState.selectTree(tid)
                case _ =>
                  slot.treeViewState.deselectTree()
            case _ =>
              slot.treeViewState.deselectTree()
          }
      },
      // Card seeding, per slot: a branch ENTERS the comparison exactly when
      // its card's tree finishes (re)loading with an empty selection —
      // choosing or changing the slot's target, switching the selected tree,
      // and the refresh button all reset the card's selection before their
      // fetch, while a toggle off/on with unchanged target+tree reloads
      // nothing, so a preserved selection is never re-seeded. The seed is
      // the baseline's counterparts on the compared branch (same node id
      // present in its tree); an empty baseline falls back to the active
      // tree's root, which becomes a real, persistent selection on the
      // active card. The active tree's own Loaded transition triggers the
      // same check for the case where its (re)load settles after a card's.
      compareSlots.map { slot =>
        slot.treeViewState.selectedTree.signal.changes
          .collect { case LoadState.Loaded(compareTree) => compareTree } --> { compareTree =>
            seedCompareCard(treeViewState, slot, compareState, compareTree)
          }
      },
      treeViewState.selectedTree.signal.changes
        .collect { case LoadState.Loaded(_) => () } --> { _ =>
          compareSlots.foreach { slot =>
            slot.treeViewState.selectedTree.now() match
              case LoadState.Loaded(compareTree) =>
                seedCompareCard(treeViewState, slot, compareState, compareTree)
              case _ => ()
          }
        },
      // Per slot: fetch its branch's curves for its own selection — the
      // mirror of the tab's own Auto-LEC subscription above, driven by the
      // card's independent Ctrl+click surface.
      compareSlots.map { slot =>
        slot.treeViewState.chartState.visibleCurves.changes
          .collect { case visible if visible.nonEmpty => visible.toList }
          .debounce(100) --> { nodeIds =>
            slot.treeViewState.chartState.loadCurves(nodeIds)
          }
      },
      compareSlots.map { slot =>
        slot.treeViewState.chartState.visibleCurves.changes
          .collect { case visible if visible.isEmpty => () } --> { _ =>
            slot.treeViewState.chartState.clearCurves()
          }
      },
      // Per slot: while its Mirror is on, keep the slot's selection equal to
      // the baseline's charted set restricted to the counterparts present in
      // the slot's own tree (a cross-tree slot shares none, so it charts
      // nothing). Re-syncs on baseline-visible change or slot-tree change and
      // fires immediately on toggle-on; a slot's query set is always empty, so
      // this set is exactly what the slot charts. Mirror off leaves the current
      // selection frozen and re-enables manual gestures.
      compareSlots.map { slot =>
        slot.state.mirror.signal
          .combineWith(treeViewState.chartState.visibleCurves, slot.treeViewState.selectedTree.signal)
          .changes --> {
            case (true, baselineVisible, LoadState.Loaded(tree)) =>
              slot.treeViewState.chartState.setUserSelection(
                baselineVisible.intersect(tree.nodes.map(_.id).toSet)
              )
            case (true, _, _) => slot.treeViewState.chartState.setUserSelection(Set.empty)
            case (false, _, _) => ()
          }
      },
      // ── Query input panel ───────────────────────────────────────
      div(
        cls := "analyze-query-panel",
        div(
          cls := "analyze-query-header",
          h3("Query")
        ),
        div(
          cls := "form-field",
          label(cls := "form-label", "Query Expression"),
          textArea(
            cls := "form-input form-textarea query-textarea",
            placeholder := "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x), 5000000))",
            rows := 3,
            controlled(
              value <-- queryState.queryInput.signal,
              onInput.mapToValue --> queryState.queryInput
            ),
            onBlur.mapToUnit --> (_ => queryState.validateNow()),
            onKeyDown --> { ev =>
              if (ev.ctrlKey || ev.metaKey) && ev.key == "Enter" then
                ev.preventDefault()
                runQuery()
            }
          ),
          // Debounced keystroke validation — active only when instant validate is on
          queryState.queryInput.signal.changes
            .debounce(300)
            .filter(_ => queryState.instantValidate.now()) --> { _ => queryState.validateNow() }
        ),
        // Instant validation checkbox
        div(
          cls := "form-field query-instant-validate",
          label(
            cls := "form-label-inline",
            input(
              typ := "checkbox",
              controlled(
                checked <-- queryState.instantValidate.signal,
                onInput.mapToChecked --> queryState.instantValidate
              )
            ),
            span(" Instant validation")
          )
        ),
        // Inline parse error (client-side validation)
        child.maybe <-- queryState.parseError.map(_.map { msg =>
          div(cls := "query-parse-error", span(cls := "form-error", msg))
        }),
        // Inline server domain error (400 query failures)
        child.maybe <-- queryState.queryServerError.signal.map(_.map { msg =>
          div(cls := "query-server-error", span(cls := "form-error", msg))
        }),
        // Run button row
        div(
          cls := "query-actions",
          button(
            cls := "btn btn-primary query-run-btn",
            disabled <-- queryState.isExecuting.combineWith(queryState.queryInput.signal).map {
              case (executing, text) => executing || text.trim.isEmpty
            },
            child <-- queryState.isExecuting.map {
              case true  => span("Evaluating…")
              case false => span("Run")
            },
            onClick --> (_ => runQuery())
          )
        )
      ),
      // ── Query result card ───────────────────────────────────────
      QueryResultCard(queryState.queryResult.signal, nodeLookup),
      // ── LEC chart panel ─────────────────────────────────────────
      // Side-by-side with at least one visible comparand side → one chart per
      // side on shared pinned axes; every other state (Overlay, or
      // side-by-side with no visible comparand) → the single chart driven by
      // `combinedSpecSignal`. Rendered off the derived (layout, visible-side
      // pool indices) key, deduplicated — an active-branch switch that leaves
      // the visible set unchanged must not tear down and re-embed every panel
      // (the panels' own spec signals already react to the branch change). The
      // baseline panel is shown or hidden reactively inside the grid so its
      // eye toggle doesn't rebuild the whole grid.
      div(
        cls := "analyze-lec-panel",
        child <-- compareState.layout.signal
          .combineWith(compareState.rowTargets, compareState.hiddenFlags, scenarioState.activeBranch.signal, treeViewState.selectedTreeId.signal, compareState.baselineAt.signal)
          .map { (layout, rowTs, hidden, activeBranch, activeTid, baselineAt) =>
            val visibleComparands =
              engagedPoolSlots(rowTs, activeBranch, activeTid, baselineAt).collect { case (pi, _) if !hidden(pi) => pi }
            (layout, visibleComparands)
          }
          .distinct
          .map {
            case (CompareLayout.SideBySide, visible) if visible.nonEmpty =>
              div(
                cls := "lec-panel-grid",
                child <-- compareState.baselineHidden.signal.map {
                  case true  => emptyNode
                  case false =>
                    chartPanel(
                      scenarioState.activeBranch.signal.map(BranchBar.branchDisplayName),
                      activePalette.map(PaletteData.familySwatch),
                      sideBySideSpecs.map(_._1),
                      hoverBridge,
                      chartParams
                    )
                },
                visible.map { pi =>
                  chartPanel(
                    compareSlots(pi).state.branchSignal.map(BranchBar.branchDisplayName),
                    compareSlots(pi).state.palette.signal.map(PaletteData.familySwatch),
                    sideBySideSpecs.map(_._2(pi)),
                    slotHoverBridges(pi),
                    chartParams
                  )
                }
              )
            case _ =>
              div(
                cls := "lec-chart-surface",
                // H3: selections absent at a rewound commit are dropped from the
                // chart; name them so a vanished curve is explained, not silent.
                child.maybe <-- treeViewState.chartState.droppedSelections.map { dropped =>
                  if dropped.isEmpty then None
                  else Some(div(cls := "dropped-selections-notice",
                    s"${dropped.size} selected node(s) not present at this point in time"))
                },
                LECChartView(combinedSpecSignal, hoverBridge, chartParams)
              )
          }
      )
    )

    // Each slot is one self-contained card: its (branch, tree) picker in the
    // header, its own tree view in the body — baseline first, one comparand card
    // per row (keyed by pool index so a card keeps its element and picker DOM as
    // rows change). Comparand cards carry the ✎ markers and the mirror lock, and
    // start collapsed, expanding when their tree loads. The add-tree button and
    // layout toggle sit in the footer below the stack.
    val savedTreePanel = div(
      cls := "saved-tree-panel",
      h3(cls := "load-trees-title", "Load Trees"),
      div(
        cls := "slot-card-stack",
        BranchCard(
          header = renderBaselineHead(
            treeViewState, scenarioState, appConfigState, compareState,
            baselineHistoryState,
            // No-op for slots not holding a tree (a slot's selectedTreeId is
            // cleared whenever its row is torn down), so it's safe to pass ungated.
            onRefreshExtra = () => compareSlots.foreach(_.treeViewState.refreshSelectedTree())
          ),
          body = TreeDetailView(
                   treeViewState, queryState.satisfyingNodeIds, hoverBridge,
                   // Rewinding the baseline does NOT lock selection — the pin is
                   // a read dimension; Ctrl+click still charts through history.
                   pinnedAt = compareState.baselineAt.signal
                     .combineWith(baselineHistoryState.commits)
                     .map { (at, commits) => at.flatMap(h => commits.find(_.commitHash == h)) }
                 ).amend(cls := "tree-detail-view--in-card"),
          // Same as a comparand card: starts collapsed (header only), expands to
          // host the tree when one loads.
          initiallyOpen = false,
          expandOn = treeViewState.selectedTree.signal.changes
                       .collect { case LoadState.Loaded(t) => t.id }.distinct.mapToUnit
        ),
        children <-- compareState.rows.signal.split(identity) { (poolIdx, _, _) =>
          val slot = compareSlots(poolIdx)
          BranchCard(
            header = renderComparandHead(
              scenarioState, slot, poolIdx, compareState,
              otherSlots = compareSlots.map(_.state).filterNot(_ eq slot.state),
              activeTreeId = treeViewState.selectedTreeId.signal
            ),
            body = TreeDetailView(
                     slot.treeViewState,
                     hoverBridge = slotHoverBridges(poolIdx),
                     changedNodeIds = slotChangedNodeIds(poolIdx),
                     // Lock only while Mirror is on. A rewound slot (at set) stays
                     // interactive — rewind is a read dimension, not a lock.
                     selectionLocked = slot.state.mirror.signal,
                     pinnedAt = slot.state.atSignal
                       .combineWith(slot.historyState.commits)
                       .map { (at, commits) => at.flatMap(h => commits.find(_.commitHash == h)) }
                   ).amend(cls := "tree-detail-view--in-card"),
            initiallyOpen = false,
            expandOn = slot.treeViewState.selectedTree.signal.changes
                         .collect { case LoadState.Loaded(t) => t.id }.distinct.mapToUnit
          )
        }
      ),
      div(
        cls := "load-trees-footer",
        button(
          cls := "compare-add-btn",
          tpe := "button",
          disabled <-- compareState.rows.signal.map(_.size >= CompareState.ComparedSlotCount),
          "+ Compare tree",
          onClick --> (_ => compareState.addRow())
        ),
        renderLayoutToggle(compareState)
      )
    )

    div(
      cls := "analyze-view",
      SplitPane.horizontal(
        left = analyzeLeftPanel,
        right = savedTreePanel,
        leftPercent = 75
      )
    )

  private def loadedOrEmpty(s: LoadState[Map[NodeId, LECNodeCurve]]): Map[NodeId, LECNodeCurve] = s match
    case LoadState.Loaded(m) => m
    case _                   => Map.empty

  /** One side-by-side panel's spec lifecycle — mirrors `LECChartState
    * .specSignal`'s shape (empty selection → Idle; otherwise the cache's
    * own lifecycle carried over the built spec), plus the shared pinned
    * axes every panel agrees on. */
  private def panelSpec(
    cacheState: LoadState[Map[NodeId, LECNodeCurve]],
    visible: Set[NodeId],
    pairs: Vector[(LECNodeCurve, HexColor)],
    pinned: Option[PinnedAxes]
  ): LoadState[js.Dynamic] =
    if visible.isEmpty then LoadState.Idle
    else cacheState.map(_ => LECSpecBuilder.build(pairs, width = 460, height = 340, pinned = pinned))

  /** One tile of the side-by-side grid: swatch + branch name header over
    * that branch's own chart. */
  private def chartPanel(
    branchName: Signal[String],
    swatchColor: Signal[HexColor],
    spec: Signal[LoadState[js.Dynamic]],
    bridge: ChartHoverBridge,
    paramStore: ChartParamStore
  ): HtmlElement =
    div(
      cls := "lec-panel",
      div(
        cls := "lec-panel-header",
        span(cls := "color-swatch", styleAttr <-- swatchColor.map(c => s"background-color: ${c.value};")),
        span(cls := "lec-panel-name", child.text <-- branchName)
      ),
      LECChartView(spec, bridge, paramStore)
    )

  /** Header for the baseline slot card: swatch + branch/tree picker (the bare
    * `TreeListView`, which keeps the tree-list loading logic) + refresh + the
    * baseline eye — one picker row (the baseline carries a single action). */
  private def renderBaselineHead(
    treeViewState: TreeViewState,
    scenarioState: ScenarioState,
    appConfigState: AppConfigState,
    compareState: CompareState,
    baselineHistoryState: TreeHistoryState,
    onRefreshExtra: () => Unit
  ): HtmlElement =
    div(
      cls := "slot-card-content",
      div(
        cls := "slot-card-picker",
        SlotPalettePicker(
          compareState.baselinePalette.signal,
          compareState.baselinePalette.set,
          () => compareState.baselinePalette.set(PaletteData.Aqua)
        ),
        TreeListView(
          treeViewState,
          leadingControl = Some(BranchBar.picker(scenarioState, appConfigState.scenariosEnabled.signal)),
          onRefreshExtra = onRefreshExtra,
          bare = true
        )
      ),
      // History slider under the picker: rewind the baseline to an earlier stop.
      div(
        cls := "slot-card-slider",
        HistorySlider(baselineHistoryState.commits, compareState.baselineAt.signal, compareState.baselineAt.set(_))
      ),
      // Actions row — the eye alone, right-aligned, stacking under the refresh
      // and lining up with the comparand rows' rightmost button.
      div(
        cls := "slot-card-actions",
        renderEyeToggle(compareState.baselineHidden)
      )
    )

  /** Header for a comparand slot card: picker row (swatch + branch/tree picker)
    * over an action row (mirror, eye, remove) so the three buttons never crowd
    * the selects in the narrow panel. Mirror syncs this slot's selection to the
    * baseline's; eye hides only its chart contribution; remove tears it down. */
  private def renderComparandHead(
    scenarioState: ScenarioState,
    slot: CompareSlot,
    poolIdx: Int,
    compareState: CompareState,
    otherSlots: Vector[CompareSlotState],
    activeTreeId: Signal[Option[TreeId]]
  ): HtmlElement =
    div(
      cls := "slot-card-content",
      div(
        cls := "slot-card-picker",
        SlotPalettePicker(
          slot.state.palette.signal,
          slot.state.palette.set,
          () => slot.state.palette.set(slot.state.defaultPalette)
        ),
        renderBranchPicker(scenarioState, slot, otherSlots, activeTreeId)
      ),
      // History slider under the picker: rewind this comparand to an earlier
      // stop (past-vs-current against the baseline at head).
      div(
        cls := "slot-card-slider",
        HistorySlider(slot.historyState.commits, slot.state.atSignal, slot.state.setAt(_))
      ),
      div(
        cls := "slot-card-actions",
        renderMirrorToggle(slot.state.mirror),
        renderEyeToggle(slot.state.hidden),
        button(
          cls := "slot-card-remove",
          tpe := "button",
          title := "Remove this comparison",
          "−",
          onClick --> (_ => compareState.removeRow(poolIdx))
        )
      )
    )

  /** Mirror toggle: while on, this row's selection tracks the baseline's charted
    * set (its counterparts in this row's tree) and manual selection is locked. */
  private def renderMirrorToggle(mirror: Var[Boolean]): HtmlElement =
    button(
      cls := "mirror-toggle",
      cls("mirror-toggle--on") <-- mirror.signal,
      tpe := "button",
      title <-- mirror.signal.map {
        case true  => "Stop mirroring the baseline selection"
        case false => "Mirror the baseline selection"
      },
      Icons.mirror("mirror-icon"),
      onClick --> (_ => mirror.update(!_))
    )

  /** Eye toggle bound to a hidden flag (the baseline's or a slot's): shows the
    * open eye while charted, the struck-through eye while hidden. */
  private def renderEyeToggle(hidden: Var[Boolean]): HtmlElement =
    button(
      cls := "eye-toggle",
      cls("eye-toggle--hidden") <-- hidden.signal,
      tpe := "button",
      title <-- hidden.signal.map {
        case true  => "Show in chart"
        case false => "Hide from chart"
      },
      child <-- hidden.signal.map {
        case true  => Icons.eyeOff("eye-icon")
        case false => Icons.eye("eye-icon")
      },
      onClick --> (_ => hidden.update(!_))
    )

  /** Binary Overlay ⇄ Side-by-side control — a sliding two-position toggle: both
    * labels sit in one pill track, and the thumb slides under the active one. */
  private def renderLayoutToggle(compareState: CompareState): HtmlElement =
    div(
      cls := "compare-layout-toggle",
      span(
        cls := "compare-layout-thumb",
        styleAttr <-- compareState.layout.signal.map {
          case CompareLayout.Overlay   => "transform: translateX(0%);"
          case CompareLayout.SideBySide => "transform: translateX(100%);"
        }
      ),
      List("Overlay" -> CompareLayout.Overlay, "Side by side" -> CompareLayout.SideBySide).map { (text, l) =>
        button(
          cls := "compare-layout-option",
          cls("compare-layout-option--active") <-- compareState.layout.signal.map(_ == l),
          tpe := "button",
          text,
          onClick --> (_ => compareState.layout.set(l))
        )
      }
    )

  /** Slots taking part in the comparison right now, as (slot index, branch)
    * in slot order. A slot is engaged when it holds a chosen target whose
    * (branch, effective tree, pin) triple differs from the tab's own — the tab
    * at its current pin `activeAt` (`baselineAt`) — AND from every earlier
    * engaged slot's triple. A duplicate is inert (earlier slot wins), exactly
    * like a tab collision: the slot's target is untouched, so it re-engages as
    * soon as a tree, branch, or pin change differentiates it. */
  private[views] def engagedSlots(
    targets: Vector[CompareTarget],
    activeBranch: BranchChoice,
    activeTreeId: Option[TreeId],
    activeAt: Option[CommitHash]
  ): Vector[(Int, BranchChoice)] =
    targets.zipWithIndex
      .foldLeft((Vector.empty[SlotCoordinate], Vector.empty[(Int, BranchChoice)])) {
        case ((engaged, acc), (t, i)) =>
          t.toCoordinate match
            case Some(c)
                if !c.collidesWith(activeBranch, activeTreeId, activeAt) &&
                   !engaged.exists(e => c.samePairAs(e, activeTreeId)) =>
              (engaged :+ c, acc :+ (i, c.branch))
            case _ => (engaged, acc)
      }
      ._2

  /** Branch-select values to hide: a branch is excluded when picking it (with
    * this slot's existing tree override) would duplicate another slot's current
    * (branch, effective tree) pair. */
  private[views] def excludedBranchValues(
    ownOverride: Option[TreeId],
    otherCoords: Vector[SlotCoordinate],
    activeTid: Option[TreeId]
  ): Set[String] =
    otherCoords
      .filter(c => SlotCoordinate(c.branch, ownOverride).samePairAs(c, activeTid))
      .map(c => BranchBar.branchOptionValue(c.branch))
      .toSet

  /** Tree-override values to hide (including "" = follow-active): a value is
    * excluded when choosing it would make this slot's (branch, effective tree)
    * pair equal another slot's current pair. The same-branch restriction falls
    * out of `samePairAs` (a different-branch other can never match). An
    * unparseable non-empty value yields no candidate and so excludes nothing —
    * it is never treated as follow-active. */
  private[views] def excludedTreeOverrideValues(
    ownBranch: BranchChoice,
    overrideValues: List[String],   // "" plus each available tree id string
    otherCoords: Vector[SlotCoordinate],
    activeTid: Option[TreeId]
  ): Set[String] =
    overrideValues.filter { v =>
      val candidate = v match
        case "" => Some(SlotCoordinate(ownBranch, None))
        case id => TreeId.fromString(id).toOption.map(t => SlotCoordinate(ownBranch, Some(t)))
      candidate.exists(cand => otherCoords.exists(c => cand.samePairAs(c, activeTid)))
    }.toSet

  /** Pure seeding rule for a branch entering the comparison.
    *
    * Baseline nonempty → its counterparts on the compared branch (same node
    * id present in that branch's tree), in deterministic id order, capped.
    * Baseline empty → the active tree's root: it is returned as the node to
    * select on the ACTIVE card (a real, persistent selection), and seeds the
    * compare side with the active root where its counterpart exists, otherwise
    * with the compare tree's own root — so a cross-tree slot, whose node ids
    * are disjoint from the active tree's, still gets a root-vs-root two-sided
    * comparison instead of a blank compare side.
    *
    * @return (node to select on the active card, nodes to seed into the
    *         compare card)
    */
  private[views] def computeSeed(
    baseline: Set[NodeId],
    activeRoot: Option[NodeId],
    compareTreeNodeIds: Set[NodeId],
    compareRoot: Option[NodeId],
    cap: Int = 13
  ): (Option[NodeId], List[NodeId]) =
    if baseline.nonEmpty then
      (None, baseline.toList.filter(compareTreeNodeIds.contains).sortBy(_.value).take(cap))
    else
      (activeRoot, activeRoot.filter(compareTreeNodeIds.contains).orElse(compareRoot).toList)

  /** One entry-time seeding pass for a slot's card. No-op unless the slot
    * holds a chosen target and the card's selection is empty — an entry event
    * always resets it first, and a preserved or deliberately emptied selection
    * is respected. A mirroring row is skipped: its selection is driven by the
    * baseline sync, which supersedes entry seeding (and must not select the
    * active root on the baseline through the empty-baseline fallback). Emits
    * through the selection buses so the normal toggle and cap handling applies;
    * the baseline read includes the active card's current selection, so a root
    * selected by an earlier pass (another slot's, or the same tree settling
    * twice) is a nonempty baseline on the next one, never a second
    * (deselecting) toggle emission.
    */
  private def seedCompareCard(
    treeViewState: TreeViewState,
    slot: CompareSlot,
    compareState: CompareState,
    compareTree: RiskTree
  ): Unit =
    val chosen = slot.state.target.now() != CompareTarget.NotChosen
    if chosen && !slot.state.mirror.now() && slot.treeViewState.chartState.userSelectedNodeIds.now().isEmpty then
      val active = treeViewState.chartState
      val baseline = active.satisfyingNodeIds.now() ++ active.userSelectedNodeIds.now()
      val activeRoot = treeViewState.selectedTree.now() match
        case LoadState.Loaded(activeTree) => Some(activeTree.rootId)
        case _                            => None
      val (rootToSelect, seeds) =
        computeSeed(baseline, activeRoot, compareTree.nodes.map(_.id).toSet, Some(compareTree.rootId))
      rootToSelect.foreach(active.userSelectionToggle.onNext)
      seeds.foreach(slot.treeViewState.chartState.userSelectionToggle.onNext)

  /** Branch picker for one Compare slot — options are `scenarioState
    * .scenarios` plus `main`. The tab's own active branch is offered too: a
    * slot on the active branch is a valid comparison once it pins a different
    * tree, so that collision is resolved through the tree select rather than by
    * hiding the branch. A branch that would duplicate another slot's current
    * (branch, effective tree) pair is shown disabled (greyed), not removed — so
    * the same branch can occupy two slots on different trees, the picker never
    * lets two slots show the identical comparison, and it always keeps
    * displaying this slot's chosen branch. `""` in the DOM
    * `<select>` means "nothing chosen yet" — a third state `BranchBar`'s
    * own picker doesn't need to represent, so the option list and sentinel
    * come from `BranchBar` (shared with `BranchBar.picker`, Analyze's
    * baseline-branch selector) but the `CompareTarget` parsing stays local
    * to Compare.
    */
  private def renderBranchPicker(
    scenarioState: ScenarioState,
    slot: CompareSlot,
    otherSlots: Vector[CompareSlotState],
    activeTreeId: Signal[Option[TreeId]]
  ): HtmlElement =
    // Branch select sets the coordinate's branch, preserving any tree
    // override already chosen; clearing it clears the whole slot.
    def parseBranch(raw: String): CompareTarget =
      val existingOverride = slot.state.target.now().toCoordinate.flatMap(_.treeOverride)
      raw match
        case ""                     => CompareTarget.NotChosen
        case BranchBar.mainSentinel => CompareTarget.Target(SlotCoordinate(BranchChoice.Main, existingOverride))
        case name =>
          ScenarioName.fromString(name).toOption
            .map(n => CompareTarget.Target(SlotCoordinate(BranchChoice.Scenario(n), existingOverride)))
            .getOrElse(CompareTarget.NotChosen)

    // Tree select sets the coordinate's tree override: "" = follow the tab's
    // active tree (default); a tree id = pin this slot to that tree
    // (cross-tree compare). Only meaningful once a branch is chosen.
    def applyTreeOverride(raw: String): Unit =
      slot.state.target.now() match
        case CompareTarget.Target(coord) =>
          val over = if raw.isEmpty then None else TreeId.fromString(raw).toOption
          slot.state.target.set(CompareTarget.Target(coord.copy(treeOverride = over)))
        case CompareTarget.NotChosen => ()

    // The full branch list is always offered; a branch that would duplicate
    // another slot's current (branch, effective tree) pair is shown disabled
    // (greyed), not removed, so the picker keeps displaying this slot's chosen
    // branch. The tab's active branch is offered too — its collision with the
    // active pair is resolvable in place via the tree select.
    val optionEntries: Signal[List[(String, String)]] =
      BranchBar.branchOptionEntries(scenarioState.lastLoadedScenarios)

    val disabledBranchValues: Signal[Set[String]] = activeTreeId
      .combineWith(slot.state.target.signal, Signal.combineSeq(otherSlots.map(_.target.signal)))
      .map { (activeTid, own, otherTargets) =>
        excludedBranchValues(
          own.toCoordinate.flatMap(_.treeOverride),
          otherTargets.flatMap(_.toCoordinate).toVector,
          activeTid
        )
      }

    // Tree options come from the slot's own branch's tree list (the slot's
    // TreeViewState tracks the slot's chosen branch).
    val treeOptions: Signal[List[(String, String)]] =
      slot.treeViewState.availableTrees.map {
        case LoadState.Loaded(trees) => trees.map(t => t.id.value.toString -> t.name)
        case _                       => Nil
      }

    // A tree can only be chosen once a branch is.
    val treeDisabled: Signal[Boolean] =
      slot.state.target.signal.map(_ == CompareTarget.NotChosen)

    // Tree-override values — "same tree as active" (`""`) included — that would
    // make this slot's (branch, effective tree) pair equal another slot's
    // current pair. A tree is taken only by another slot on the SAME branch as
    // this one; with no branch chosen nothing is excluded (the whole select is
    // disabled then anyway). Wired as a per-option `disabled` on top of
    // `treeDisabled`, so the tree select enforces the same no-duplicate-pair
    // rule the branch select already does.
    val excludedTreeOverrides: Signal[Set[String]] =
      slot.state.target.signal
        .combineWith(activeTreeId, treeOptions, Signal.combineSeq(otherSlots.map(_.target.signal)))
        .map { (own, activeTid, trees, otherTargets) =>
          own.toCoordinate.map(_.branch) match
            case None => Set.empty
            case Some(ownBranch) =>
              excludedTreeOverrideValues(
                ownBranch,
                "" :: trees.map(_._1),
                otherTargets.flatMap(_.toCoordinate).toVector,
                activeTid
              )
        }

    div(
      cls := "slot-select-pair",
      select(
        cls := "compare-branch-select",
        onMountCallback(_ => scenarioState.refresh()),
        option(value := "", "— compare against —"),
        FormInputs.splitOptions(optionEntries, disabledBranchValues),
        controlled(
          value <-- slot.state.target.signal.map {
            case CompareTarget.NotChosen     => ""
            case CompareTarget.Target(coord) => BranchBar.branchOptionValue(coord.branch)
          },
          onInput.mapToValue --> { raw => slot.state.target.set(parseBranch(raw)) }
        )
      ),
      select(
        cls := "compare-tree-select",
        cls("compare-select--disabled") <-- treeDisabled,
        disabled <-- treeDisabled,
        children <-- treeOptions.combineWith(excludedTreeOverrides).map { (trees, excluded) =>
          (("" -> "same tree as active") :: trees).map { (v, treeLabel) =>
            option(value := v, disabled := excluded.contains(v), treeLabel)
          }
        },
        controlled(
          value <-- slot.state.target.signal.map {
            case CompareTarget.Target(coord) => coord.treeOverride.map(_.value.toString).getOrElse("")
            case CompareTarget.NotChosen     => ""
          },
          onInput.mapToValue --> { raw => applyTreeOverride(raw) }
        )
      )
    )
