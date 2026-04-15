package app.views

import com.raquo.laminar.api.L.{*, given}

import app.components.SplitPane
import app.state.{TreeViewState, AnalyzeQueryState, LoadState}
import com.risquanter.register.domain.data.{RiskNode, RiskTree}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.requests.LECChartRequest

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

  def apply(treeViewState: TreeViewState, queryState: AnalyzeQueryState): HtmlElement =

    /** Fire query against selected tree. No-op if no tree is selected. */
    def runQuery(): Unit = queryState.executeQuery()

    // ── Reactive chart request (§3.10) ──────────────────────────
    // Merges query-matched nodes (green) with user Ctrl+click selections
    // (aqua), tagging overlap as purple. Fires POST to /lec-chart on
    // any change to either set (debounced 100ms for rapid Ctrl+click).
    val chartRequest: Signal[Option[LECChartRequest]] =
      queryState.satisfyingNodeIds
        .combineWith(treeViewState.chartState.userSelectedNodeIds.signal)
        .map { (querySet, userSet) =>
          val allNodes = querySet ++ userSet
          if allNodes.isEmpty then None
          else Some(LECChartRequest.build(querySet, userSet))
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
      // Auto-LEC: fire chart request on any change to either node set
      chartRequest.changes
        .collect { case Some(req) => req }
        .debounce(100) --> { req =>
          treeViewState.chartState.loadLECChart(req)
        },
      // Reset chart to idle when both sets become empty
      chartRequest.changes
        .collect { case None => () } --> { _ =>
          treeViewState.chartState.lecChartSpec.set(LoadState.Idle)
        },
      // ── Query input panel ───────────────────────────────────────
      div(
        cls := "analyze-query-panel",
        h3("Query"),
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
        LECChartView(treeViewState.lecChartSpec)
      )
    )

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(treeViewState),
      TreeDetailView(treeViewState, queryState.satisfyingNodeIds)
    )

    div(
      cls := "analyze-view",
      SplitPane.horizontal(
        left = analyzeLeftPanel,
        right = savedTreePanel,
        leftPercent = 75
      )
    )
