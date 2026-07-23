package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.components.{SplitPane, FormInputs, BranchBar, BranchCard}
import app.chart.{LECSpecBuilder, ColorAssigner, CompareColorAssigner, PaletteData, PinnedAxes}
import app.state.{TreeViewState, AnalyzeQueryState, LoadState, ChartHoverBridge, ChartParamStore, ScenarioState, AppConfigState, CompareMode, CompareState, CompareSlot, CompareSlotState, CompareTarget, toChoice}
import com.risquanter.register.domain.data.{LECNodeCurve, RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, ScenarioName}
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
  *   └── RIGHT: saved-tree-panel
  *       ├── TreeListView  (dropdown selector)
  *       └── TreeDetailView (expandable hierarchy, query highlighting) —
  *           or, with Compare fully on, one collapsible BranchCard per
  *           compared branch, each wrapping its own TreeDetailView
  *
  * Owns reactive subscriptions for:
  *   - Auto-expand: expands tree to reveal query-matched nodes
  *   - Auto-LEC: builds and fires chart requests when either the query set
  *     or manual user set changes
  */
object AnalyzeView:

  /** @param compareSlots One bundle per compared-branch picker slot (cap:
    *                     `CompareState.MaxBranches` − 1): each carries its
    *                     own `TreeViewState` — an independent tree view and
    *                     Ctrl+click surface on the slot's chosen branch —
    *                     its own hash-diff state, and its palette family.
    *                     Selection identity in compare mode is the pair
    *                     (branch, node).
    */
  def apply(
    treeViewState: TreeViewState,
    queryState: AnalyzeQueryState,
    scenarioState: ScenarioState,
    appConfigState: AppConfigState,
    compareState: CompareState,
    compareSlots: Vector[CompareSlot]
  ): HtmlElement =

    /** Fire query against selected tree. No-op if no tree is selected. */
    def runQuery(): Unit = queryState.executeQuery()

    /** Slots taking part in the comparison right now: a chosen target that
      * isn't the tab's own branch. The exclusion guards the one transaction
      * where the tab's branch was just switched onto a slot's target — the
      * invalidation subscription resets that slot in the NEXT transaction,
      * but render signals see the collision first. */
    def engagedSlots(targets: Vector[CompareTarget], activeBranch: BranchChoice): Vector[(Int, BranchChoice)] =
      targets.zipWithIndex.flatMap { (t, i) => t.toChoice.filter(_ != activeBranch).map(c => (i, c)) }

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
          val allNodes = querySet ++ userSet
          if allNodes.isEmpty then None
          else Some(allNodes.toList)
        }

    // ── Compare mode ───────────────────────────────────────────────
    // Same node set as `chartNodeIds` above (query ∪ user selection) — reuse
    // `chartState`'s own derivation instead of recomputing it from scratch.
    val visibleNodeIds: Signal[Set[NodeId]] = treeViewState.chartState.visibleCurves

    /** Per-slot ✎ markers, gated to empty immediately when Compare is off,
      * even if a stale diff result from a previous session lingers in the
      * slot's diff state. */
    val slotChangedNodeIds: Vector[Signal[Set[NodeId]]] =
      compareSlots.map { slot =>
        compareState.comparisonOn.combineWith(slot.diffState.changedNodeIds).map { (on, ids) =>
          if on then ids else Set.empty
        }
      }

    /** Per-slot Overlay inputs: the slot's curve cache (deduplicated for the
      * same reason as below), its card's own selection — independent of the
      * tab's own, user Ctrl+clicks only (the query pane runs against the
      * tab's active branch, so a slot's query set stays empty) — and its
      * chosen target. */
    val slotOverlayInputs: Signal[Vector[(LoadState[Map[NodeId, LECNodeCurve]], Set[NodeId], CompareTarget)]] =
      Signal.combineSeq(compareSlots.map { slot =>
        slot.treeViewState.curveCache.distinct
          .combineWith(slot.treeViewState.chartState.visibleCurves, slot.state.target.signal)
      }).map(_.toVector)

    /** Not in Overlay mode, or no slot engaged → the tab's own single-branch
      * spec, untouched (in Side-by-side the chart area renders the panel
      * grid instead of this signal). Overlay with engaged slots → every
      * card contributes its own selection's curves, coloured by branch
      * family (`CompareColorAssigner`), labelled with the branch names the
      * cards show.
      *
      * A side whose curves haven't landed yet simply contributes nothing on
      * this emission and fills in when its fetch settles — an already-drawn
      * partial chart is worth more than blanking to a loading state. Only a
      * selection with no curves at all shows Loading. */
    val combinedSpecSignal: Signal[LoadState[js.Dynamic]] =
      // curveCache (every instance) is deduplicated here for the same reason
      // specSignal dedupes it internally (LECChartState): each map run below
      // builds a NEW js.Dynamic in compare mode, and LECChartView re-embeds
      // per emission. The other inputs are already dedup-safe: specSignal
      // and the visible sets are distinct at their producers; mode/targets/
      // activeBranch only change on genuine user action.
      compareState.mode.signal
        .combineWith(treeViewState.chartState.specSignal, treeViewState.curveCache.distinct, visibleNodeIds)
        .combineWith(slotOverlayInputs, scenarioState.activeBranch.signal)
        .map { case (mode, singleSpec, thisCurves, thisVisible, slotInputs, activeBranch) =>
          if mode != CompareMode.Overlay then singleSpec
          else
            // Same collision guard as `engagedSlots`: a slot whose target the
            // tab's branch was just switched onto contributes nothing this
            // frame — pairing it would give two sides identical series ids,
            // which Vega merges into one garbled series.
            val engaged = slotInputs.zip(compareSlots).flatMap { case ((curves, visible, target), slot) =>
              target.toChoice.filter(_ != activeBranch).map(choice => (curves, visible, choice, slot.palette))
            }
            if engaged.isEmpty then singleSpec
            else
              (thisCurves +: engaged.map(_._1)).collectFirst { case LoadState.Failed(msg) => msg } match
                case Some(msg) => LoadState.Failed(msg)
                case None =>
                  val sides =
                    CompareColorAssigner.OverlaySide(
                      loadedOrEmpty(thisCurves), thisVisible, PaletteData.Aqua,
                      BranchBar.branchDisplayName(activeBranch)
                    ) +: engaged.map { (curves, visible, choice, palette) =>
                      CompareColorAssigner.OverlaySide(
                        loadedOrEmpty(curves), visible, palette,
                        BranchBar.branchDisplayName(choice)
                      )
                    }
                  val paired = CompareColorAssigner.pairForOverlay(sides)
                  if paired.nonEmpty then LoadState.Loaded(LECSpecBuilder.buildFromSeries(paired))
                  else if thisVisible.isEmpty && engaged.forall(_._2.isEmpty) then LoadState.Idle
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
      treeViewState.curveCache.distinct
        .combineWith(visibleNodeIds, treeViewState.nodeColorMap)
        .combineWith(slotPanelInputs, scenarioState.activeBranch.signal)
        .map { case (thisCurves, thisVisible, thisColors, slotInputs, activeBranch) =>
          val thisPairs = ColorAssigner.pairWithColors(loadedOrEmpty(thisCurves), thisVisible, thisColors)
          val slotPairs = slotInputs.map { (curves, visible, colors, target) =>
            if target.toChoice.exists(_ != activeBranch)
            then Some(ColorAssigner.pairWithColors(loadedOrEmpty(curves), visible, colors))
            else None
          }
          val pinned = PinnedAxes.fromCurves((thisPairs ++ slotPairs.flatten.flatten).map(_._1))
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
      // Compare mode, per slot: reload the diff whenever the selected tree,
      // the tab's own active branch, the compare toggle, or the slot's
      // target changes. Off, or slot empty → reset to Idle. Debounced in
      // step with the curve-fetch subscription below — both read the slot's
      // target, so an undebounced diff fetch racing ahead of the (still-
      // debounced) curve fetch would briefly label stale curve data with
      // the newly-chosen branch's name.
      compareSlots.map { slot =>
        treeViewState.selectedTreeId.signal
          .combineWith(compareState.comparisonOn, slot.state.target.signal, scenarioState.activeBranch.signal)
          .changes.debounce(100) --> {
            case (Some(treeId), true, CompareTarget.Target(target), activeBranch) =>
              slot.diffState.loadDiff(treeId, activeBranch, target)
            case _ =>
              slot.diffState.reset()
          }
      },
      // Compare mode, per slot: reset the slot's target when it stops being
      // a valid choice — the tab's own branch switched onto it (a branch
      // compared against itself), or the scenario it names was deleted from
      // the shared list (reachable via any view's per-row delete). Without
      // this the picker's option disappears (DOM shows the placeholder)
      // while the Var keeps the stale value, so fetches keep firing against
      // it. Mirrors the activeBranch fallback in ScenarioState: reacts only
      // to the external signals that invalidate the value, reads its own
      // value via now() (ADR-019 Pattern 6). Deletion is only trusted from
      // a Loaded list — Idle/Loading/Failed are not confirmation the branch
      // is gone.
      compareSlots.map { slot =>
        scenarioState.activeBranch.signal
          .combineWith(scenarioState.scenarios)
          .changes --> { (active, list) =>
            slot.state.target.now() match
              case CompareTarget.Target(choice) =>
                val nowActive = choice == active
                val deleted = choice match
                  case BranchChoice.Scenario(name) =>
                    list match
                      case LoadState.Loaded(l) => !l.exists(_.name == name)
                      case _                   => false
                  case BranchChoice.Main => false
                if nowActive || deleted then slot.state.target.set(CompareTarget.NotChosen)
              case CompareTarget.NotChosen => ()
          }
      },
      // Per slot: keep the slot's tree selection in step with the tab's own
      // selected tree while Compare is fully on; drop its state when the
      // compared branch leaves the comparison (target cleared, or no tree).
      // A plain toggle-off PRESERVES the card's selection so toggling back
      // on with the same target and tree restores exactly what the user had,
      // deliberate removals included — nothing is re-seeded or forced back.
      // The `chosen == synced` guard exists because the two Vars sync across
      // an Airstream transaction boundary: on the emission where a target was
      // just picked, `chosenBranch` (which the slot's fetches read) still
      // holds the previous value — a `selectTree` fired then would fetch the
      // tree on the wrong branch and race the corrective refetch
      // (`loadOptionInto` does not supersede in-flight requests). A target
      // change with the tree already selected needs nothing here: the slot
      // instance's own branch subscription refetches it.
      compareSlots.map { slot =>
        treeViewState.selectedTreeId.signal
          .combineWith(compareState.comparisonOn, slot.state.target.signal, slot.state.chosenBranch.signal)
          .changes --> {
            case (Some(treeId), true, CompareTarget.Target(chosen), synced) =>
              // chosen != synced: waiting for chosenBranch to catch up — the
              // follow-up emission (same tuple, synced) does the selectTree.
              if chosen == synced && !slot.treeViewState.selectedTreeId.now().contains(treeId) then
                slot.treeViewState.selectTree(treeId)
            case (Some(_), false, CompareTarget.Target(_), _) =>
              () // toggled off, target still chosen: preserve the card's state
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
      // ── Query input panel ───────────────────────────────────────
      div(
        cls := "analyze-query-panel",
        div(
          cls := "analyze-query-header",
          h3("Query"),
          renderModeControl(compareState),
          // Always mounted, not conditionally shown/hidden — toggling Compare
          // used to mount/unmount these <select>s outright, which shifted the
          // surrounding panel's size every time and needed a moment to
          // rebuild the option lists on remount. Disabled instead: same size,
          // same position, always ready, with a visual cue (dimmed + inert)
          // for "not applicable right now" instead of vanishing entirely.
          compareSlots.map { slot =>
            renderBranchPicker(
              scenarioState,
              slot.state,
              otherSlots = compareSlots.map(_.state).filterNot(_ eq slot.state),
              disabledSignal = compareState.comparisonOn.map(!_)
            )
          }
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
      // Side-by-side with at least one engaged slot → one chart per branch
      // on shared pinned axes; every other state (Off, Overlay, side-by-side
      // without a target yet) → the single chart driven by
      // `combinedSpecSignal`.
      div(
        cls := "analyze-lec-panel",
        child <-- compareState.mode.signal.combineWith(compareState.targets, scenarioState.activeBranch.signal).map {
          case (CompareMode.SideBySide, targets, activeBranch) if engagedSlots(targets, activeBranch).nonEmpty =>
            div(
              cls := "lec-panel-grid",
              chartPanel(
                scenarioState.activeBranch.signal.map(BranchBar.branchDisplayName),
                PaletteData.familySwatch(PaletteData.Aqua),
                sideBySideSpecs.map(_._1),
                hoverBridge,
                chartParams
              ),
              engagedSlots(targets, activeBranch).map { (i, choice) =>
                chartPanel(
                  Val(BranchBar.branchDisplayName(choice)),
                  PaletteData.familySwatch(compareSlots(i).palette),
                  sideBySideSpecs.map(_._2(i)),
                  slotHoverBridges(i),
                  chartParams
                )
              }
            )
          case _ =>
            LECChartView(combinedSpecSignal, hoverBridge, chartParams)
        }
      )
    )

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(
        treeViewState,
        leadingControl = Some(BranchBar.picker(scenarioState, appConfigState.scenariosEnabled.signal)),
        // No-op for slots not holding a tree (a slot's selectedTreeId is
        // cleared whenever Compare is off), so it's safe to pass ungated.
        onRefreshExtra = () => compareSlots.foreach(_.treeViewState.refreshSelectedTree())
      ),
      // Compare off (or no slot engaged yet) → today's single tree view,
      // untouched. Fully on → one bordered, collapsible card per compared
      // branch, each an independent tree view and Ctrl+click input surface.
      // The ✎ markers sit on each compared branch's card — its nodes are the
      // ones diffed against the tab's active branch. Swatches are the branch
      // palette families the Overlay chart colours by (Aqua = the tab's own
      // branch; each slot has its own family).
      child <-- compareState.comparisonOn.combineWith(compareState.targets, scenarioState.activeBranch.signal).map {
        (on, targets, activeBranch) =>
          val engaged = engagedSlots(targets, activeBranch)
          if on && engaged.nonEmpty then
            div(
              cls := "branch-card-stack",
              BranchCard(
                swatchColor = PaletteData.familySwatch(PaletteData.Aqua),
                branchName  = scenarioState.activeBranch.signal.map(BranchBar.branchDisplayName),
                body        = TreeDetailView(treeViewState, queryState.satisfyingNodeIds, hoverBridge)
                                .amend(cls := "tree-detail-view--in-card")
              ),
              engaged.map { (i, choice) =>
                BranchCard(
                  swatchColor = PaletteData.familySwatch(compareSlots(i).palette),
                  branchName  = Val(BranchBar.branchDisplayName(choice)),
                  body        = TreeDetailView(compareSlots(i).treeViewState, hoverBridge = slotHoverBridges(i), changedNodeIds = slotChangedNodeIds(i))
                                  .amend(cls := "tree-detail-view--in-card")
                )
              }
            )
          else TreeDetailView(treeViewState, queryState.satisfyingNodeIds, hoverBridge)
      }
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
    swatchColor: HexColor,
    spec: Signal[LoadState[js.Dynamic]],
    bridge: ChartHoverBridge,
    paramStore: ChartParamStore
  ): HtmlElement =
    div(
      cls := "lec-panel",
      div(
        cls := "lec-panel-header",
        span(cls := "branch-card-swatch", styleAttr := s"background-color: ${swatchColor.value};"),
        span(cls := "lec-panel-name", child.text <-- branchName)
      ),
      LECChartView(spec, bridge, paramStore)
    )

  /** Three-position slider selecting the comparison display mode, with
    * plain-text state labels below the track; the labels are clickable and
    * set the mode directly. */
  private def renderModeControl(compareState: CompareState): HtmlElement =
    def modeIndex(m: CompareMode): Int = m match
      case CompareMode.Off        => 0
      case CompareMode.Overlay    => 1
      case CompareMode.SideBySide => 2
    def modeAt(i: Int): CompareMode = i match
      case 1 => CompareMode.Overlay
      case 2 => CompareMode.SideBySide
      case _ => CompareMode.Off
    div(
      cls := "compare-mode-control",
      input(
        typ := "range",
        cls := "compare-mode-slider",
        minAttr := "0",
        maxAttr := "2",
        stepAttr := "1",
        controlled(
          value <-- compareState.mode.signal.map(m => modeIndex(m).toString),
          onInput.mapToValue --> { raw => compareState.mode.set(modeAt(raw.toIntOption.getOrElse(0))) }
        )
      ),
      div(
        cls := "compare-mode-labels",
        List("Off" -> CompareMode.Off, "Overlay" -> CompareMode.Overlay, "Side by side" -> CompareMode.SideBySide).map { (text, m) =>
          span(
            cls := "compare-mode-label",
            cls("compare-mode-label--active") <-- compareState.mode.signal.map(_ == m),
            text,
            onClick --> { _ => compareState.mode.set(m) }
          )
        }
      )
    )

  /** Pure seeding rule for a branch entering the comparison.
    *
    * Baseline nonempty → its counterparts on the compared branch (same node
    * id present in that branch's tree), in deterministic id order, capped.
    * Baseline empty → the active tree's root: it is returned as the node to
    * select on the ACTIVE card (a real, persistent selection), and seeds the
    * compare side only where its counterpart exists.
    *
    * @return (node to select on the active card, nodes to seed into the
    *         compare card)
    */
  def computeSeed(
    baseline: Set[NodeId],
    activeRoot: Option[NodeId],
    compareTreeNodeIds: Set[NodeId],
    cap: Int = 13
  ): (Option[NodeId], List[NodeId]) =
    if baseline.nonEmpty then
      (None, baseline.toList.filter(compareTreeNodeIds.contains).sortBy(_.value).take(cap))
    else
      (activeRoot, activeRoot.filter(compareTreeNodeIds.contains).toList)

  /** One entry-time seeding pass for a slot's card. No-op unless Compare is
    * fully on for that slot and the card's selection is empty — an entry
    * event always resets it first, and a preserved or deliberately emptied
    * selection is respected. Emits through the selection buses so the
    * normal toggle and cap handling applies; the baseline read includes the
    * active card's current selection, so a root selected by an earlier pass
    * (the other slot's, or the same tree settling twice) is a nonempty
    * baseline on the next one, never a second (deselecting) toggle emission.
    */
  private def seedCompareCard(
    treeViewState: TreeViewState,
    slot: CompareSlot,
    compareState: CompareState,
    compareTree: RiskTree
  ): Unit =
    val fullyOn = compareState.comparisonOnNow && slot.state.target.now() != CompareTarget.NotChosen
    if fullyOn && slot.treeViewState.chartState.userSelectedNodeIds.now().isEmpty then
      val active = treeViewState.chartState
      val baseline = active.satisfyingNodeIds.now() ++ active.userSelectedNodeIds.now()
      val activeRoot = treeViewState.selectedTree.now() match
        case LoadState.Loaded(activeTree) => Some(activeTree.rootId)
        case _                            => None
      val (rootToSelect, seeds) = computeSeed(baseline, activeRoot, compareTree.nodes.map(_.id).toSet)
      rootToSelect.foreach(active.userSelectionToggle.onNext)
      seeds.foreach(slot.treeViewState.chartState.userSelectionToggle.onNext)

  /** Branch picker for one Compare slot — options are `scenarioState
    * .scenarios` plus `main`, excluding the tab's own active branch
    * (comparing a branch to itself is a no-op — `ScenarioDiffService.diff`
    * would just report every node `Identical`) and the other slots' current
    * choices (one branch can occupy only one slot). `""` in the DOM
    * `<select>` means "nothing chosen yet" — a third state `BranchBar`'s
    * own picker doesn't need to represent, so the option list and sentinel
    * come from `BranchBar` (shared with `BranchBar.picker`, Analyze's
    * baseline-branch selector) but the `CompareTarget` parsing stays local
    * to Compare.
    *
    * Always mounted regardless of `disabledSignal` — see the call site's
    * comment. `disabled` alone gives the browser's own dimmed/inert styling;
    * `compare-branch-select--disabled` layers a slightly stronger visual cue
    * (app.css) so "not applicable right now" reads clearly at a glance.
    */
  private def renderBranchPicker(
    scenarioState: ScenarioState,
    slot: CompareSlotState,
    otherSlots: Vector[CompareSlotState],
    disabledSignal: Signal[Boolean]
  ): HtmlElement =
    def parseSelection(raw: String): CompareTarget =
      if raw.isEmpty then CompareTarget.NotChosen
      else if raw == BranchBar.mainSentinel then CompareTarget.Target(BranchChoice.Main)
      else ScenarioName.fromString(raw).toOption.map(n => CompareTarget.Target(BranchChoice.Scenario(n))).getOrElse(CompareTarget.NotChosen)

    val optionEntries: Signal[List[(String, String)]] =
      BranchBar.branchOptionEntries(
        scenarioState.scenarios,
        excludeValues = scenarioState.activeBranch.signal
          .combineWith(Signal.combineSeq(otherSlots.map(_.target.signal)))
          .map { (active, otherTargets) =>
            otherTargets.flatMap(_.toChoice).map(BranchBar.branchOptionValue).toSet
              + BranchBar.branchOptionValue(active)
          }
      )

    select(
      cls := "compare-branch-select",
      cls("compare-branch-select--disabled") <-- disabledSignal,
      disabled <-- disabledSignal,
      onMountCallback(_ => scenarioState.refresh()),
      option(value := "", "— compare against —"),
      FormInputs.splitOptions(optionEntries),
      controlled(
        value <-- slot.target.signal.map {
          case CompareTarget.NotChosen      => ""
          case CompareTarget.Target(choice) => BranchBar.branchOptionValue(choice)
        },
        onInput.mapToValue --> { raw => slot.target.set(parseSelection(raw)) }
      )
    )
