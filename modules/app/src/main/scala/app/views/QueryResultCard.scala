package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.LoadState
import com.risquanter.register.domain.data.RiskNode
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.QueryResponse

/** Result card for vague quantifier query evaluation (ADR-028).
  *
  * Shows satisfied badge, proportion bar, count, matching nodes, query echo.
  * States: Idle, Loading, Loaded (Failed renders as emptyNode safety-net).
  *
  * Accepts a node-lookup signal for resolving node names in the
  * matching-nodes list (Plan v2 §1.3.3, Work Stream A).
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  */
object QueryResultCard:

  def apply(
    resultSignal: Signal[LoadState[QueryResponse]],
    nodeLookup: Signal[Map[NodeId, RiskNode]] = Signal.fromValue(Map.empty)
  ): HtmlElement =
    div(
      cls := "query-result-card",
      child <-- resultSignal.combineWith(nodeLookup).map {
        case (LoadState.Idle, _)        => renderIdle
        case (LoadState.Loading, _)     => renderLoading
        case (LoadState.Failed(_), _)   => emptyNode // safety-net: domain errors route to AnalyzeView inline slot
        case (LoadState.Loaded(r), lookup) => renderResult(r, lookup)
      }
    )

  // ── State renderers ───────────────────────────────────────────

  private def renderIdle: HtmlElement =
    div(
      cls := "query-result-idle",
      p("Enter a query and press Run to evaluate.")
    )

  private def renderLoading: HtmlElement =
    div(
      cls := "query-result-loading",
      p("Evaluating query…")
    )

  private def renderResult(r: QueryResponse, nodeLookup: Map[NodeId, RiskNode]): HtmlElement =
    val satisfiedCls = if r.satisfied then "badge-satisfied" else "badge-not-satisfied"
    val satisfiedText = if r.satisfied then "SATISFIED" else "NOT SATISFIED"
    val pct = (r.proportion * 100).round.toInt

    div(
      cls := "query-result-loaded",
      // Verdict badge
      div(
        cls := "query-result-verdict",
        span(cls := s"query-badge $satisfiedCls", satisfiedText),
        span(cls := "query-echo", r.queryEcho)
      ),
      // Proportion bar
      div(
        cls := "query-result-proportion",
        div(
          cls := "proportion-bar-track",
          div(
            cls := "proportion-bar-fill",
            width := s"$pct%"
          )
        ),
        span(cls := "proportion-label", s"$pct% (${r.satisfyingCount} of ${r.rangeSize})")
      ),
      // Matching nodes — resolved to "Name (id: ULID)" when possible
      if r.satisfyingNodeIds.nonEmpty then
        div(
          cls := "query-result-matches",
          span(cls := "matches-label", "Matching nodes:"),
          ul(
            cls := "matches-list",
            r.satisfyingNodeIds.map { nid =>
              val label = nodeLookup.get(nid) match
                case Some(node) => s"${node.name} (id: ${nid.value})"
                case None       => nid.value
              li(label)
            }
          )
        )
      else
        div(cls := "query-result-matches", span(cls := "matches-label", "No matching nodes."))
    )
