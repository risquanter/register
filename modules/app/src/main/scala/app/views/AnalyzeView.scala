package app.views

import com.raquo.laminar.api.L.{*, given}

import scala.scalajs.js

import app.components.SplitPane
import app.chart.{LECSpecBuilder, CompareColorAssigner}
import app.state.{TreeViewState, AnalyzeQueryState, LoadState, ChartHoverBridge, ScenarioState, CompareState, CompareTarget, ScenarioDiffState, toBranchOption}
import com.risquanter.register.domain.data.{RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.{NodeId, ScenarioName}

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
  *       └── TreeDetailView (expandable hierarchy, query highlighting)
  *
  * Owns reactive subscriptions for:
  *   - Auto-expand: expands tree to reveal query-matched nodes (§5, D3/D4)
  *   - Auto-LEC: builds and fires chart requests when either the query set
  *     or manual user set changes (§3.10, §6)
  */
object AnalyzeView:

  def apply(
    treeViewState: TreeViewState,
    queryState: AnalyzeQueryState,
    scenarioState: ScenarioState,
    compareState: CompareState,
    diffState: ScenarioDiffState
  ): HtmlElement =

    /** Fire query against selected tree. No-op if no tree is selected. */
    def runQuery(): Unit = queryState.executeQuery()

    // Shared hover bridge — wired to both chart and tree views (§3B.1)
    val hoverBridge = new ChartHoverBridge()

    // ── Reactive chart node list (§3.10) ───────────────────────────
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

    // ── Compare mode (milestone-2b Phase C, Overlay-only, 2 branches) ──
    // Same node set as `chartNodeIds` above (query ∪ user selection) — reuse
    // `chartState`'s own derivation instead of recomputing it from scratch.
    val visibleNodeIds: Signal[Set[NodeId]] = treeViewState.chartState.visibleCurves

    /** ✎ markers gate to empty immediately when Compare is off, even if a
      * stale diff result from a previous session lingers in `diffState`. */
    val gatedChangedNodeIds: Signal[Set[NodeId]] =
      compareState.enabled.signal.combineWith(diffState.changedNodeIds).map { (enabled, ids) =>
        if enabled then ids else Set.empty
      }

    /** Off → the tab's own single-branch spec, untouched (regression guard —
      * nothing about today's chart changes unless Compare is on). Overlay →
      * paired curves from both branches via `CompareColorAssigner`, once both
      * sides have loaded. */
    val combinedSpecSignal: Signal[LoadState[js.Dynamic]] =
      compareState.enabled.signal
        .combineWith(treeViewState.chartState.specSignal, treeViewState.curveCache, diffState.compareCurves.signal)
        .combineWith(visibleNodeIds, compareState.compareBranch.signal)
        .map { case (enabled, singleSpec, thisCurves, compareCurves, visible, target) =>
          if !enabled then singleSpec
          else (thisCurves, compareCurves) match
            case (LoadState.Loaded(thisMap), LoadState.Loaded(compareMap)) =>
              val compareLabel = target match
                case CompareTarget.Main           => "main"
                case CompareTarget.Scenario(name)  => name.value.toString
                case CompareTarget.NotChosen       => "compare"
              val paired = CompareColorAssigner.pairForOverlay(thisMap, compareMap, visible, "this", compareLabel)
              LoadState.Loaded(LECSpecBuilder.buildFromSeries(paired))
            case (LoadState.Failed(msg), _)   => LoadState.Failed(msg)
            case (_, LoadState.Failed(msg))   => LoadState.Failed(msg)
            case (LoadState.Loading, _)       => LoadState.Loading
            case (_, LoadState.Loading)       => LoadState.Loading
            // Both sides idle — e.g. Compare just turned on and no branch
            // chosen yet, or nothing visible to fetch. Nothing is in flight,
            // so Idle (not Loading) is correct — LECChartView already knows
            // how to render it ("Select a node…").
            case _ => LoadState.Idle
        }

    // ── Node lookup for name resolution in QueryResultCard (A1) ──
    val nodeLookup: Signal[Map[NodeId, RiskNode]] =
      treeViewState.selectedTree.signal.map {
        case LoadState.Loaded(tree) => tree.nodes.map(n => n.id -> n).toMap
        case _                      => Map.empty
      }

    val analyzeLeftPanel = div(
      cls := "analyze-left-panel",
      // ── Reactive subscriptions (bound to element lifecycle) ────
      // Auto-expand: reveal query-matched nodes in tree (§5.1, §5.2)
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
          case (Some(treeId), true, target, activeBranch) =>
            dispatchOnBranch(target, cb => diffState.loadDiff(treeId, activeBranch, cb), () => diffState.reset())
          case _ =>
            diffState.reset()
        },
      // Compare mode: fetch the compare branch's own curves for whatever
      // node set is currently visible on the tab's own chart.
      visibleNodeIds
        .combineWith(compareState.enabled.signal, compareState.compareBranch.signal, treeViewState.selectedTreeId.signal)
        .changes.debounce(100) --> {
          case (visible, true, target, Some(treeId)) if visible.nonEmpty =>
            dispatchOnBranch(target, cb => diffState.loadCompareCurves(treeId, visible.toList, cb), () => diffState.clearCompareCurves())
          case _ =>
            diffState.clearCompareCurves()
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
          child.maybe <-- compareState.enabled.signal.map { enabled =>
            if enabled then Some(renderBranchPicker(scenarioState, compareState)) else None
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
        // Inline parse error (client-side validation, T3.1b)
        child.maybe <-- queryState.parseError.map(_.map { msg =>
          div(cls := "query-parse-error", span(cls := "form-error", msg))
        }),
        // Inline server domain error (400 query failures, Plan v2 §2.4)
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
      TreeListView(treeViewState),
      TreeDetailView(treeViewState, queryState.satisfyingNodeIds, hoverBridge, gatedChangedNodeIds)
    )

    div(
      cls := "analyze-view",
      SplitPane.horizontal(
        left = analyzeLeftPanel,
        right = savedTreePanel,
        leftPercent = 75
      )
    )

  /** Shared shape behind both Compare-mode fetch subscriptions: a chosen
    * compare branch dispatches the fetch, no branch chosen yet clears it. */
  private def dispatchOnBranch(
    target: CompareTarget,
    onChosen: Option[ScenarioName.ScenarioName] => Unit,
    onIdle: () => Unit
  ): Unit =
    target.toBranchOption match
      case Some(compareBranch) => onChosen(compareBranch)
      case None                 => onIdle()

  /** Branch picker for Compare mode — options are `scenarioState.scenarios`
    * plus `main`, excluding the tab's own active branch (comparing a branch
    * to itself is a no-op — `ScenarioDiffService.diff` would just report
    * every node `Identical`). `""` in the DOM `<select>` means "nothing
    * chosen yet"; `"__main__"` is the sentinel for main (a `ScenarioName`
    * can never collide with it — see `ScenarioName`'s charset).
    */
  private def renderBranchPicker(scenarioState: ScenarioState, compareState: CompareState): HtmlElement =
    val mainSentinel = "__main__"

    def parseSelection(raw: String): CompareTarget =
      if raw.isEmpty then CompareTarget.NotChosen
      else if raw == mainSentinel then CompareTarget.Main
      else ScenarioName.fromString(raw).toOption.map(CompareTarget.Scenario(_)).getOrElse(CompareTarget.NotChosen)

    select(
      cls := "compare-branch-select",
      onMountCallback(_ => scenarioState.refresh()),
      option(value := "", "— compare against —"),
      children <-- scenarioState.scenarios.signal.combineWith(scenarioState.activeBranch.signal).map {
        case (listState, active) =>
          val names = listState match
            case LoadState.Loaded(list) => list.map(_.name)
            case _                      => Nil
          val mainOpt = if active.isDefined then List(option(value := mainSentinel, "main")) else Nil
          mainOpt ++ names.filterNot(active.contains).map(n => option(value := n.value.toString, n.value.toString))
      },
      controlled(
        value <-- compareState.compareBranch.signal.map {
          case CompareTarget.NotChosen      => ""
          case CompareTarget.Main           => mainSentinel
          case CompareTarget.Scenario(name) => name.value.toString
        },
        onInput.mapToValue --> { raw => compareState.compareBranch.set(parseSelection(raw)) }
      )
    )
