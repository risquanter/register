package app.components

import com.raquo.laminar.api.L.{*, given}

import app.state.{LoadState, MergeSubmitState, ScenarioMergeState}
import com.risquanter.register.domain.data.iron.ScenarioName
import com.risquanter.register.http.responses.MergePreviewResponse

/** "Merge into main…" modal (Design view): shows the server's byte-level
  * conflict preview and offers Merge only when it is clean. Conflicting
  * paths are listed report-only — the resolution path is editing the nodes
  * so both branches agree, then Re-check.
  *
  * Owns no state (ADR-019 Pattern 1) — everything lives in
  * `ScenarioMergeState`; `onMerged` is the owning view's success reaction
  * (close + switch to main).
  */
object MergeModal:

  def apply(state: ScenarioMergeState, onMerged: () => Unit): HtmlElement =
    div(
      child.maybe <-- state.target.signal.map(_.map(name => renderModal(state, name, onMerged)))
    )

  private def renderModal(
    state: ScenarioMergeState,
    name: ScenarioName.ScenarioName,
    onMerged: () => Unit
  ): HtmlElement =
    val mergeEnabled: Signal[Boolean] =
      state.preview.signal.combineWith(state.submit.signal).map {
        case (LoadState.Loaded(preview), submit) =>
          preview.status == "clean" && submit != MergeSubmitState.Submitting
        case _ => false
      }
    div(
      cls := "merge-modal-overlay",
      onClick --> { _ => state.close() },
      div(
        cls := "merge-modal",
        onClick.stopPropagation --> { _ => () },
        div(cls := "merge-modal-title", s"Merge '⎇ ${name.value}' into main"),
        div(cls := "merge-modal-body", child <-- state.preview.signal.map(renderPreview(state, _))),
        child.maybe <-- state.submit.signal.map {
          case MergeSubmitState.Failed(msg) => Some(div(cls := "merge-modal-error", msg))
          case _                            => None
        },
        div(
          cls := "merge-modal-actions",
          button(
            tpe := "button",
            cls := "merge-modal-merge-btn",
            "Merge",
            disabled <-- mergeEnabled.map(!_),
            onClick --> { _ => state.merge(onMerged) }
          ),
          button(tpe := "button", "Cancel", onClick --> { _ => state.close() })
        )
      )
    )

  private def renderPreview(state: ScenarioMergeState, preview: LoadState[MergePreviewResponse]): HtmlElement =
    preview match
      case LoadState.Idle | LoadState.Loading =>
        div(cls := "merge-modal-status", "Checking for conflicts…")
      case LoadState.Failed(msg) =>
        div(
          div(cls := "merge-modal-error", s"Conflict check failed: $msg"),
          recheckButton(state)
        )
      case LoadState.Loaded(result) =>
        result.status match
          case "clean" =>
            div(cls := "merge-modal-status", "No conflicts — merging applies this scenario's changes to main.")
          case "missing-scenario" =>
            div(cls := "merge-modal-error", "This scenario no longer exists.")
          case _ =>
            div(
              div(
                cls := "merge-modal-error",
                s"${result.conflicts.size} path(s) changed on both branches since the fork:"
              ),
              ul(
                cls := "merge-modal-conflicts",
                result.conflicts.map(c => li(code(c.path)))
              ),
              div(
                cls := "merge-modal-hint",
                "Merging is blocked until both branches agree on these paths — edit the node on either branch to match the other, then re-check."
              ),
              recheckButton(state)
            )

  private def recheckButton(state: ScenarioMergeState): HtmlElement =
    button(
      tpe := "button",
      cls := "merge-modal-recheck-btn",
      "Re-check",
      onClick --> { _ => state.refreshPreview() }
    )
