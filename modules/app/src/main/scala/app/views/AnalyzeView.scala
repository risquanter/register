package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.components.{SplitPane, FormInputs, BranchBar, BranchCard}
import app.chart.{LECSpecBuilder, ColorAssigner, CompareColorAssigner, PaletteData, PinnedAxes}
import app.state.{TreeViewState, AnalyzeQueryState, LoadState, ChartHoverBridge, ChartParamStore, ScenarioState, AppConfigState, CompareMode, CompareState, CompareTarget, ScenarioDiffState, toChoice}
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

  /** @param compareTreeViewState The compare card's own tree/selection/chart
    *                             state — a second `TreeViewState` whose branch
    *                             signal is `compareState.chosenBranch`, giving
    *                             the compared branch an independent tree view
    *                             and Ctrl+click surface. Selection identity in
    *                             compare mode is the pair (branch, node).
    */
  def apply(
    treeViewState: TreeViewState,
    queryState: AnalyzeQueryState,
    scenarioState: ScenarioState,
    appConfigState: AppConfigState,
    compareState: CompareState,
    diffState: ScenarioDiffState,
    compareTreeViewState: TreeViewState
  ): HtmlElement =

    /** Fire query against selected tree. No-op if no tree is selected. */
    def runQuery(): Unit = queryState.executeQuery()

    // Hover bridges — one per chart surface. The active branch's chart and
    // tree card share `hoverBridge`; the compare card and its side-by-side
    // panel share `compareHoverBridge`, so hovering a row highlights only
    // its own card, and in side-by-side each panel's chart↔tree hover works
    // with plain node-id curve ids.
    val hoverBridge = new ChartHoverBridge()
    val compareHoverBridge = new ChartHoverBridge()

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

    /** ✎ markers gate to empty immediately when Compare is off, even if a
      * stale diff result from a previous session lingers in `diffState`. */
    val gatedChangedNodeIds: Signal[Set[NodeId]] =
      compareState.comparisonOn.combineWith(diffState.changedNodeIds).map { (on, ids) =>
        if on then ids else Set.empty
      }

    /** The compare card's own selection — independent of the tab's own
      * (selection identity is the pair (branch, node)). User Ctrl+clicks
      * only; the query pane runs against the tab's active branch, so the
      * compare instance's query set stays empty. */
    val compareVisibleNodeIds: Signal[Set[NodeId]] =
      compareTreeViewState.chartState.visibleCurves

    /** Not in Overlay mode, or no compare branch chosen → the tab's own
      * single-branch spec, untouched (in Side-by-side the chart area renders
      * the panel grid instead of this signal). Overlay with a target → each
      * branch card contributes its own selection's curves, coloured by
      * branch family (`CompareColorAssigner`), labelled with the branch
      * names the cards show.
      *
      * A side whose curves haven't landed yet simply contributes nothing on
      * this emission and fills in when its fetch settles — an already-drawn
      * partial chart is worth more than blanking to a loading state. Only a
      * selection with no curves at all shows Loading. */
    val combinedSpecSignal: Signal[LoadState[js.Dynamic]] =
      // curveCache (both instances) is deduplicated here for the same reason
      // specSignal dedupes it internally (LECChartState): each map run below
      // builds a NEW js.Dynamic in compare mode, and LECChartView re-embeds
      // per emission. The other inputs are already dedup-safe: specSignal,
      // visibleNodeIds and compareVisibleNodeIds are distinct at their
      // producers; mode/compareBranch/activeBranch only change on genuine
      // user action.
      compareState.mode.signal
        .combineWith(treeViewState.chartState.specSignal, treeViewState.curveCache.distinct, visibleNodeIds)
        .combineWith(compareTreeViewState.curveCache.distinct, compareVisibleNodeIds)
        .combineWith(compareState.compareBranch.signal, scenarioState.activeBranch.signal)
        .map { case (mode, singleSpec, thisCurves, thisVisible, compareCurves, compareVisible, target, activeBranch) =>
          if mode != CompareMode.Overlay then singleSpec
          else target.toChoice match
            case None => singleSpec
            // Guards the one transaction where the tab's branch was just
            // switched onto the compare target: the invalidation subscription
            // resets the target in the NEXT transaction, but this signal sees
            // (target == activeBranch) first — pairing then would give both
            // sides identical series ids, which Vega merges into one garbled
            // series for a frame.
            case Some(compareChoice) if compareChoice == activeBranch => singleSpec
            case Some(compareChoice) =>
              (thisCurves, compareCurves) match
                case (LoadState.Failed(msg), _) => LoadState.Failed(msg)
                case (_, LoadState.Failed(msg)) => LoadState.Failed(msg)
                case _ =>
                  val paired = CompareColorAssigner.pairForOverlay(
                    loadedOrEmpty(thisCurves), thisVisible,
                    loadedOrEmpty(compareCurves), compareVisible,
                    BranchBar.branchDisplayName(activeBranch),
                    BranchBar.branchDisplayName(compareChoice)
                  )
                  if paired.nonEmpty then LoadState.Loaded(LECSpecBuilder.buildFromSeries(paired))
                  else if thisVisible.isEmpty && compareVisible.isEmpty then LoadState.Idle
                  else LoadState.Loading
        }

    /** Side-by-side panel specs — each branch's own curves in its own
      * normal single-branch node colours, both panels pinned to the shared
      * extents of all visible curves (per-panel autoscaling would silently
      * defeat the comparison). Emitted as a pair so both panels always
      * share one `PinnedAxes` computation. */
    val sideBySideSpecs: Signal[(LoadState[js.Dynamic], LoadState[js.Dynamic])] =
      treeViewState.curveCache.distinct
        .combineWith(visibleNodeIds, treeViewState.nodeColorMap)
        .combineWith(compareTreeViewState.curveCache.distinct, compareVisibleNodeIds, compareTreeViewState.nodeColorMap)
        .map { case (thisCurves, thisVisible, thisColors, compareCurves, compareVisible, compareColors) =>
          val thisPairs    = ColorAssigner.pairWithColors(loadedOrEmpty(thisCurves), thisVisible, thisColors)
          val comparePairs = ColorAssigner.pairWithColors(loadedOrEmpty(compareCurves), compareVisible, compareColors)
          val pinned = PinnedAxes.fromCurves((thisPairs ++ comparePairs).map(_._1))
          (panelSpec(thisCurves, thisVisible, thisPairs, pinned),
           panelSpec(compareCurves, compareVisible, comparePairs, pinned))
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
      // Compare mode: reload the diff whenever the selected tree, the tab's
      // own active branch, the compare toggle, or the chosen compare branch
      // changes. Off, or no compare branch chosen yet → reset to Idle.
      // Debounced in step with the curve-fetch subscription below — both
      // read `compareState.compareBranch`, so an undebounced diff fetch
      // racing ahead of the (still-debounced) curve fetch would briefly
      // label stale curve data with the newly-chosen branch's name.
      treeViewState.selectedTreeId.signal
        .combineWith(compareState.comparisonOn, compareState.compareBranch.signal, scenarioState.activeBranch.signal)
        .changes.debounce(100) --> {
          case (Some(treeId), true, CompareTarget.Target(compareBranch), activeBranch) =>
            diffState.loadDiff(treeId, activeBranch, compareBranch)
          case _ =>
            diffState.reset()
        },
      // Compare mode: reset the compare target when it stops being a valid
      // choice — the tab's own branch switched onto it (a branch compared
      // against itself), or the scenario it names was deleted from the shared
      // list (reachable via any view's per-row delete). Without this the
      // picker's option disappears (DOM shows the placeholder) while the Var
      // keeps the stale value, so fetches keep firing against it. Mirrors the
      // activeBranch fallback in ScenarioState: reacts only to the external
      // signals that invalidate the value, reads its own value via now()
      // (ADR-019 Pattern 6). Deletion is only trusted from a Loaded list —
      // Idle/Loading/Failed are not confirmation the branch is gone.
      scenarioState.activeBranch.signal
        .combineWith(scenarioState.scenarios)
        .changes --> { (active, list) =>
          compareState.compareBranch.now() match
            case CompareTarget.Target(choice) =>
              val nowActive = choice == active
              val deleted = choice match
                case BranchChoice.Scenario(name) =>
                  list match
                    case LoadState.Loaded(l) => !l.exists(_.name == name)
                    case _                   => false
                case BranchChoice.Main => false
              if nowActive || deleted then compareState.compareBranch.set(CompareTarget.NotChosen)
            case CompareTarget.NotChosen => ()
        },
      // Compare card: keep its tree selection in step with the tab's own
      // selected tree while Compare is fully on; drop its state when the
      // compared branch leaves the comparison (target cleared, or no tree).
      // A plain toggle-off PRESERVES the card's selection so toggling back
      // on with the same target and tree restores exactly what the user had,
      // deliberate removals included — nothing is re-seeded or forced back.
      // The `chosen == synced` guard exists because the two Vars sync across
      // an Airstream transaction boundary: on the emission where a target was
      // just picked, `chosenBranch` (which the compare instance's fetches
      // read) still holds the previous value — a `selectTree` fired then
      // would fetch the tree on the wrong branch and race the corrective
      // refetch (`loadOptionInto` does not supersede in-flight requests).
      // A target change with the tree already selected needs nothing here:
      // the compare instance's own branch subscription refetches it.
      treeViewState.selectedTreeId.signal
        .combineWith(compareState.comparisonOn, compareState.compareBranch.signal, compareState.chosenBranch.signal)
        .changes --> {
          case (Some(treeId), true, CompareTarget.Target(chosen), synced) =>
            // chosen != synced: waiting for chosenBranch to catch up — the
            // follow-up emission (same tuple, synced) does the selectTree.
            if chosen == synced && !compareTreeViewState.selectedTreeId.now().contains(treeId) then
              compareTreeViewState.selectTree(treeId)
          case (Some(_), false, CompareTarget.Target(_), _) =>
            () // toggled off, target still chosen: preserve the card's state
          case _ =>
            compareTreeViewState.deselectTree()
        },
      // Compare card seeding: a branch ENTERS the comparison exactly when the
      // card's tree finishes (re)loading with an empty selection — choosing
      // or changing the target, switching the selected tree, and the refresh
      // button all reset the card's selection before their fetch, while a
      // toggle off/on with unchanged target+tree reloads nothing, so a
      // preserved selection is never re-seeded. The seed is the baseline's
      // counterparts on the compared branch (same node id present in its
      // tree); an empty baseline falls back to the active tree's root, which
      // becomes a real, persistent selection on the active card. The active
      // tree's own Loaded transition triggers the same check for the case
      // where its (re)load settles after the compare card's.
      compareTreeViewState.selectedTree.signal.changes
        .collect { case LoadState.Loaded(compareTree) => compareTree } --> { compareTree =>
          seedCompareCard(treeViewState, compareTreeViewState, compareState, compareTree)
        },
      treeViewState.selectedTree.signal.changes
        .collect { case LoadState.Loaded(_) => () } --> { _ =>
          compareTreeViewState.selectedTree.now() match
            case LoadState.Loaded(compareTree) =>
              seedCompareCard(treeViewState, compareTreeViewState, compareState, compareTree)
            case _ => ()
        },
      // Compare card: fetch its branch's curves for its own selection —
      // the mirror of the tab's own Auto-LEC subscription above, driven by
      // the card's independent Ctrl+click surface.
      compareVisibleNodeIds.changes
        .collect { case visible if visible.nonEmpty => visible.toList }
        .debounce(100) --> { nodeIds =>
          compareTreeViewState.chartState.loadCurves(nodeIds)
        },
      compareVisibleNodeIds.changes
        .collect { case visible if visible.isEmpty => () } --> { _ =>
          compareTreeViewState.chartState.clearCurves()
        },
      // ── Query input panel ───────────────────────────────────────
      div(
        cls := "analyze-query-panel",
        div(
          cls := "analyze-query-header",
          h3("Query"),
          renderModeControl(compareState),
          // Always mounted, not conditionally shown/hidden — toggling Compare
          // used to mount/unmount this <select> outright, which shifted the
          // surrounding panel's size every time and needed a moment to
          // rebuild its option list on remount. Disabled instead: same size,
          // same position, always ready, with a visual cue (dimmed + inert)
          // for "not applicable right now" instead of vanishing entirely.
          renderBranchPicker(scenarioState, compareState, disabledSignal = compareState.comparisonOn.map(!_))
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
      // Side-by-side with a chosen target → one chart per branch on shared
      // pinned axes; every other state (Off, Overlay, side-by-side without
      // a target yet) → the single chart driven by `combinedSpecSignal`.
      div(
        cls := "analyze-lec-panel",
        child <-- compareState.mode.signal.combineWith(compareState.compareBranch.signal).map {
          case (CompareMode.SideBySide, CompareTarget.Target(compareChoice)) =>
            div(
              cls := "lec-panel-grid",
              chartPanel(
                scenarioState.activeBranch.signal.map(BranchBar.branchDisplayName),
                PaletteData.familySwatch(PaletteData.Aqua),
                sideBySideSpecs.map(_._1),
                hoverBridge,
                chartParams
              ),
              chartPanel(
                Val(BranchBar.branchDisplayName(compareChoice)),
                PaletteData.familySwatch(PaletteData.Purple),
                sideBySideSpecs.map(_._2),
                compareHoverBridge,
                chartParams
              )
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
        // No-op unless the compare card holds a tree (its selectedTreeId is
        // cleared whenever Compare is off), so it's safe to pass ungated.
        onRefreshExtra = () => compareTreeViewState.refreshSelectedTree()
      ),
      // Compare off (or no target chosen yet) → today's single tree view,
      // untouched. Fully on → one bordered, collapsible card per compared
      // branch, each an independent tree view and Ctrl+click surface. The
      // ✎ markers sit on the compare card — its
      // nodes are the ones diffed against the tab's active branch. Swatches
      // are the branch palette families the Overlay chart colours by
      // (Aqua = the tab's own branch, Purple = the compared one).
      child <-- compareState.comparisonOn.combineWith(compareState.compareBranch.signal).map {
        case (true, CompareTarget.Target(compareChoice)) =>
          div(
            cls := "branch-card-stack",
            BranchCard(
              swatchColor = PaletteData.familySwatch(PaletteData.Aqua),
              branchName  = scenarioState.activeBranch.signal.map(BranchBar.branchDisplayName),
              body        = TreeDetailView(treeViewState, queryState.satisfyingNodeIds, hoverBridge)
                              .amend(cls := "tree-detail-view--in-card")
            ),
            BranchCard(
              swatchColor = PaletteData.familySwatch(PaletteData.Purple),
              branchName  = Val(BranchBar.branchDisplayName(compareChoice)),
              body        = TreeDetailView(compareTreeViewState, hoverBridge = compareHoverBridge, changedNodeIds = gatedChangedNodeIds)
                              .amend(cls := "tree-detail-view--in-card")
            )
          )
        case _ =>
          TreeDetailView(treeViewState, queryState.satisfyingNodeIds, hoverBridge)
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
    * axes both panels agree on. */
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

  /** One entry-time seeding pass for the compare card. No-op unless Compare
    * is fully on and the card's selection is empty — an entry event always
    * resets it first, and a preserved or deliberately emptied selection is
    * respected. Emits through the selection buses so the normal toggle and
    * cap handling applies; the baseline read includes the active card's
    * current selection, so a root selected by an earlier pass is a nonempty
    * baseline on the next one, never a second (deselecting) toggle emission.
    */
  private def seedCompareCard(
    treeViewState: TreeViewState,
    compareTreeViewState: TreeViewState,
    compareState: CompareState,
    compareTree: RiskTree
  ): Unit =
    val fullyOn = compareState.comparisonOnNow && compareState.compareBranch.now() != CompareTarget.NotChosen
    if fullyOn && compareTreeViewState.chartState.userSelectedNodeIds.now().isEmpty then
      val active = treeViewState.chartState
      val baseline = active.satisfyingNodeIds.now() ++ active.userSelectedNodeIds.now()
      val activeRoot = treeViewState.selectedTree.now() match
        case LoadState.Loaded(activeTree) => Some(activeTree.rootId)
        case _                            => None
      val (rootToSelect, seeds) = computeSeed(baseline, activeRoot, compareTree.nodes.map(_.id).toSet)
      rootToSelect.foreach(active.userSelectionToggle.onNext)
      seeds.foreach(compareTreeViewState.chartState.userSelectionToggle.onNext)

  /** Branch picker for Compare mode — options are `scenarioState.scenarios`
    * plus `main`, excluding the tab's own active branch (comparing a branch
    * to itself is a no-op — `ScenarioDiffService.diff` would just report
    * every node `Identical`). `""` in the DOM `<select>` means "nothing
    * chosen yet" — a third state `BranchBar`'s own picker doesn't need to
    * represent, so the option list and sentinel come from `BranchBar`
    * (shared with `BranchBar.picker`, Analyze's baseline-branch selector)
    * but the `CompareTarget` parsing stays local to Compare.
    *
    * Always mounted regardless of `disabledSignal` — see the call site's
    * comment. `disabled` alone gives the browser's own dimmed/inert styling;
    * `compare-branch-select--disabled` layers a slightly stronger visual cue
    * (app.css) so "not applicable right now" reads clearly at a glance.
    */
  private def renderBranchPicker(
    scenarioState: ScenarioState,
    compareState: CompareState,
    disabledSignal: Signal[Boolean]
  ): HtmlElement =
    def parseSelection(raw: String): CompareTarget =
      if raw.isEmpty then CompareTarget.NotChosen
      else if raw == BranchBar.mainSentinel then CompareTarget.Target(BranchChoice.Main)
      else ScenarioName.fromString(raw).toOption.map(n => CompareTarget.Target(BranchChoice.Scenario(n))).getOrElse(CompareTarget.NotChosen)

    val optionEntries: Signal[List[(String, String)]] =
      BranchBar.branchOptionEntries(
        scenarioState.scenarios,
        excludeValue = scenarioState.activeBranch.signal.map(a => Some(BranchBar.branchOptionValue(a)))
      )

    select(
      cls := "compare-branch-select",
      cls("compare-branch-select--disabled") <-- disabledSignal,
      disabled <-- disabledSignal,
      onMountCallback(_ => scenarioState.refresh()),
      option(value := "", "— compare against —"),
      FormInputs.splitOptions(optionEntries),
      controlled(
        value <-- compareState.compareBranch.signal.map {
          case CompareTarget.NotChosen      => ""
          case CompareTarget.Target(choice) => BranchBar.branchOptionValue(choice)
        },
        onInput.mapToValue --> { raw => compareState.compareBranch.set(parseSelection(raw)) }
      )
    )
