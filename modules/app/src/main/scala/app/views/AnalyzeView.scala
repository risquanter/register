package app.views

import com.raquo.laminar.api.L.{*, given}

import app.components.SplitPane
import app.state.{TreeViewState, AnalyzeQueryState, LoadState}

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
  * Pure structural component — owns no state (ADR-019 Pattern 1).
  */
object AnalyzeView:

  def apply(treeViewState: TreeViewState, queryState: AnalyzeQueryState): HtmlElement =

    /** Fire query against selected tree. No-op if no tree is selected. */
    def runQuery(): Unit = queryState.executeQuery()

    /** Load LEC charts for the nodes that matched the last query. */
    def viewLECForMatches(): Unit =
      queryState.queryResult.now() match
        case LoadState.Loaded(resp) if resp.matchingNodeIds.nonEmpty =>
          // Set chart selection to matching nodes and trigger LEC fetch
          treeViewState.chartState.chartNodeIds.set(resp.matchingNodeIds.toSet)
          treeViewState.chartState.loadLECChart(resp.matchingNodeIds.toSet)
        case _ => ()

    val analyzeLeftPanel = div(
      cls := "analyze-left-panel",
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
          ),
          // "View LEC" cross-link (T3.5)
          child.maybe <-- queryState.queryResult.signal.map {
            case LoadState.Loaded(resp) if resp.matchingNodeIds.nonEmpty =>
              Some(button(
                cls := "btn btn-secondary query-lec-btn",
                s"View LEC for ${resp.matchingNodeIds.size} matching node${if resp.matchingNodeIds.size != 1 then "s" else ""}",
                onClick --> (_ => viewLECForMatches())
              ))
            case _ => None
          }
        )
      ),
      // ── Query result card ───────────────────────────────────────
      QueryResultCard(queryState.queryResult.signal),
      // ── LEC chart panel ─────────────────────────────────────────
      div(
        cls := "analyze-lec-panel",
        LECChartView(treeViewState.lecChartSpec)
      )
    )

    val savedTreePanel = div(
      cls := "saved-tree-panel",
      TreeListView(treeViewState),
      TreeDetailView(treeViewState, queryState.matchingNodeIds)
    )

    div(
      cls := "analyze-view",
      SplitPane.horizontal(
        left = analyzeLeftPanel,
        right = savedTreePanel,
        leftPercent = 75
      )
    )
