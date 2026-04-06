package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.LoadState
import com.risquanter.register.http.responses.QueryResponse

/** Result card for vague quantifier query evaluation (ADR-028).
  *
  * Composable function: `Signal[LoadState[QueryResponse]] => HtmlElement`.
  * Shows satisfied badge, proportion bar, count, matching IDs, query echo.
  * States: Idle, Loading, Failed, Loaded.
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  */
object QueryResultCard:

  def apply(resultSignal: Signal[LoadState[QueryResponse]]): HtmlElement =
    div(
      cls := "query-result-card",
      child <-- resultSignal.map {
        case LoadState.Idle        => renderIdle
        case LoadState.Loading     => renderLoading
        case LoadState.Failed(msg) => renderError(msg)
        case LoadState.Loaded(r)   => renderResult(r)
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

  private def renderError(message: String): HtmlElement =
    div(
      cls := "query-result-error",
      p(cls := "error-message", message)
    )

  private def renderResult(r: QueryResponse): HtmlElement =
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
      // Matching node IDs
      if r.matchingNodeIds.nonEmpty then
        div(
          cls := "query-result-matches",
          span(cls := "matches-label", "Matching nodes:"),
          ul(
            cls := "matches-list",
            r.matchingNodeIds.map(nid => li(nid.toString))
          )
        )
      else
        div(cls := "query-result-matches", span(cls := "matches-label", "No matching nodes."))
    )
