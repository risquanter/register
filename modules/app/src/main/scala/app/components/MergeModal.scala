package app.components

import com.raquo.laminar.api.L.{*, given}

import app.state.{LoadState, MergeSubmitState, ScenarioMergeState}
import app.views.ErrorMessages
import com.risquanter.register.domain.data.iron.ScenarioName
import com.risquanter.register.http.responses.{MergePreviewResponse, MergePreviewStatus}

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
          (preview.status, submit) match
            case (MergePreviewStatus.Clean(), MergeSubmitState.Submitting) => false
            case (MergePreviewStatus.Clean(), _)                          => true
            case _                                                        => false
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
          case MergeSubmitState.Failed(failure) =>
            Some(div(cls := "merge-modal-error", ErrorMessages.viewFailed(failure)))
          case _ => None
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
        div(cls := "merge-modal-status", MergeMessages.Checking)
      case LoadState.Failed(failure) =>
        div(
          div(cls := "merge-modal-error", MergeMessages.previewFailed(failure)),
          recheckButton(state)
        )
      // The preview endpoint either answers with a response or fails, and
      // `loadInto` has no arm that produces `Absent`, so this state cannot be
      // reached from here. It offers the re-check rather than a status line,
      // because a state that is unreachable today must not strand the modal if
      // that ever changes.
      case LoadState.Absent =>
        div(recheckButton(state))
      // The preview status is a closed set, so every outcome has its own arm
      // and there is no wildcard rendering an unrecognised status as an empty
      // conflict list.
      case LoadState.Loaded(result) =>
        result.status match
          case MergePreviewStatus.Clean() =>
            div(cls := "merge-modal-status", MergeMessages.Clean)

          case MergePreviewStatus.ScenarioMissing() =>
            div(cls := "merge-modal-error", MergeMessages.ScenarioMissing)

          case MergePreviewStatus.DuplicateNames(perTree) =>
            div(
              div(cls := "merge-modal-error", MergeMessages.DuplicateNamesHeading),
              div(cls := "merge-modal-conflicts", MergeMessages.duplicateNames(perTree)),
              div(cls := "merge-modal-hint", MergeMessages.DuplicateNamesHint),
              recheckButton(state)
            )

          case MergePreviewStatus.Conflicts(paths) =>
            div(
              div(cls := "merge-modal-error", MergeMessages.conflictsHeading(paths)),
              ul(
                cls := "merge-modal-conflicts",
                paths.map(entry => li(code(entry.path)))
              ),
              div(cls := "merge-modal-hint", MergeMessages.ConflictsHint),
              recheckButton(state)
            )

  private def recheckButton(state: ScenarioMergeState): HtmlElement =
    button(
      tpe := "button",
      cls := "merge-modal-recheck-btn",
      "Re-check",
      onClick --> { _ => state.refreshPreview() }
    )
