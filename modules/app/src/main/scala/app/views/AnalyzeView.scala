package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.components.{SplitPane, FormInputs, BranchBar, BranchCard}
import app.chart.{LECSpecBuilder, CompareColorAssigner, PaletteData}
import app.state.{TreeViewState, AnalyzeQueryState, LoadState, ChartHoverBridge, ScenarioState, AppConfigState, CompareState, CompareTarget, ScenarioDiffState, toChoice}
import com.risquanter.register.domain.data.{LECNodeCurve, RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, ScenarioName}

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

    // Shared hover bridge — wired to both chart and tree views
    val hoverBridge = new ChartHoverBridge()

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
      compareState.enabled.signal.combineWith(diffState.changedNodeIds).map { (enabled, ids) =>
        if enabled then ids else Set.empty
      }

    /** The compare card's own selection — independent of the tab's own
      * (selection identity is the pair (branch, node)). User Ctrl+clicks
      * only; the query pane runs against the tab's active branch, so the
      * compare instance's query set stays empty. */
    val compareVisibleNodeIds: Signal[Set[NodeId]] =
      compareTreeViewState.chartState.visibleCurves

    /** Off, or no compare branch chosen → the tab's own single-branch spec,
      * untouched (regression guard — nothing about today's chart changes
      * unless Compare is fully on). Otherwise → Overlay: each branch card
      * contributes its own selection's curves, coloured by branch family
      * (`CompareColorAssigner`), labelled with the branch names the cards
      * show.
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
      // producers; enabled/compareBranch/activeBranch only change on genuine
      // user action.
      compareState.enabled.signal
        .combineWith(treeViewState.chartState.specSignal, treeViewState.curveCache.distinct, visibleNodeIds)
        .combineWith(compareTreeViewState.curveCache.distinct, compareVisibleNodeIds)
        .combineWith(compareState.compareBranch.signal, scenarioState.activeBranch.signal)
        .map { case (enabled, singleSpec, thisCurves, thisVisible, compareCurves, compareVisible, target, activeBranch) =>
          def loadedOrEmpty(s: LoadState[Map[NodeId, LECNodeCurve]]): Map[NodeId, LECNodeCurve] = s match
            case LoadState.Loaded(m) => m
            case _                   => Map.empty
          if !enabled then singleSpec
          else target.toChoice match
            case None => singleSpec
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
          treeViewState.chartState.curveCache.set(LoadState.Idle)
        },
      // Compare mode: reload the diff whenever the selected tree, the tab's
      // own active branch, the compare toggle, or the chosen compare branch
      // changes. Off, or no compare branch chosen yet → reset to Idle.
      // Debounced in step with the curve-fetch subscription below — both
      // read `compareState.compareBranch`, so an undebounced diff fetch
      // racing ahead of the (still-debounced) curve fetch would briefly
      // label stale curve data with the newly-chosen branch's name.
      treeViewState.selectedTreeId.signal
        .combineWith(compareState.enabled.signal, compareState.compareBranch.signal, scenarioState.activeBranch.signal)
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
      // selected tree while Compare is fully on; clear the card's state
      // entirely when Compare stops (toggle off, no target, or no tree).
      // The `chosen == synced` guard exists because the two Vars sync across
      // an Airstream transaction boundary: on the emission where a target was
      // just picked, `chosenBranch` (which the compare instance's fetches
      // read) still holds the previous value — a `selectTree` fired then
      // would fetch the tree on the wrong branch and race the corrective
      // refetch (`loadOptionInto` does not supersede in-flight requests).
      // A target change with the tree already selected needs nothing here:
      // the compare instance's own branch subscription refetches it.
      treeViewState.selectedTreeId.signal
        .combineWith(compareState.enabled.signal, compareState.compareBranch.signal, compareState.chosenBranch.signal)
        .changes --> {
          case (Some(treeId), true, CompareTarget.Target(chosen), synced) =>
            // chosen != synced: waiting for chosenBranch to catch up — the
            // follow-up emission (same tuple, synced) does the selectTree.
            if chosen == synced && !compareTreeViewState.selectedTreeId.now().contains(treeId) then
              compareTreeViewState.selectTree(treeId)
          case _ =>
            if compareTreeViewState.selectedTreeId.now().isDefined then
              compareTreeViewState.selectedTreeId.set(None)
              compareTreeViewState.selectedTree.set(LoadState.Idle)
              compareTreeViewState.chartState.reset()
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
          compareTreeViewState.chartState.curveCache.set(LoadState.Idle)
        },
      // ── Query input panel ───────────────────────────────────────
      div(
        cls := "analyze-query-panel",
        div(
          cls := "analyze-query-header",
          h3("Query"),
          label(
            cls := "form-label-inline compare-toggle",
            input(
              typ := "checkbox",
              controlled(
                checked <-- compareState.enabled.signal,
                onInput.mapToChecked --> compareState.enabled
              )
            ),
            span(" Compare")
          ),
          // Always mounted, not conditionally shown/hidden — toggling Compare
          // used to mount/unmount this <select> outright, which shifted the
          // surrounding panel's size every time and needed a moment to
          // rebuild its option list on remount. Disabled instead: same size,
          // same position, always ready, with a visual cue (dimmed + inert)
          // for "not applicable right now" instead of vanishing entirely.
          renderBranchPicker(scenarioState, compareState, disabledSignal = compareState.enabled.signal.map(!_))
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
      div(
        cls := "analyze-lec-panel",
        LECChartView(combinedSpecSignal, hoverBridge)
      )
    )

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(
        treeViewState,
        leadingControl = Some(BranchBar.picker(scenarioState, appConfigState.scenariosEnabled.signal))
      ),
      // Compare off (or no target chosen yet) → today's single tree view,
      // untouched. Fully on → one bordered, collapsible card per compared
      // branch, each an independent tree view and Ctrl+click surface. The
      // ✎ markers sit on the compare card — its
      // nodes are the ones diffed against the tab's active branch. Swatches
      // are the branch palette families the Overlay chart colours by
      // (Aqua = the tab's own branch, Purple = the compared one).
      child <-- compareState.enabled.signal.combineWith(compareState.compareBranch.signal).map {
        case (true, CompareTarget.Target(compareChoice)) =>
          div(
            cls := "branch-card-stack",
            BranchCard(
              swatchColor = PaletteData.Aqua(3),
              branchName  = scenarioState.activeBranch.signal.map(BranchBar.branchDisplayName),
              body        = TreeDetailView(treeViewState, queryState.satisfyingNodeIds, hoverBridge)
                              .amend(cls := "tree-detail-view--in-card")
            ),
            BranchCard(
              swatchColor = PaletteData.Purple(3),
              branchName  = Val(BranchBar.branchDisplayName(compareChoice)),
              body        = TreeDetailView(compareTreeViewState, hoverBridge = hoverBridge, changedNodeIds = gatedChangedNodeIds)
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
