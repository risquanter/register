package app.components

import com.raquo.laminar.api.L.{*, given}

import app.state.{LoadState, MergeSubmitState, ScenarioMergeState}
import app.views.BlockedMessages
import com.risquanter.register.domain.data.iron.ScenarioName
import com.risquanter.register.http.responses.{MergePreviewResponse, MergePreviewStatus}

/** "Merge into main…" modal (Design view): shows the server's conflict
  * preview and offers Merge only when it is clean. Each conflict is listed
  * report-only, by the tree, node or mitigation it names and the fields both
  * branches changed — the resolution path is changing either branch so those
  * fields agree, then Re-check.
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
    val mergeBlockedBy: Signal[List[String]] =
      state.preview.signal.combineWith(state.submit.signal).map {
        case (LoadState.Loaded(preview), submit) =>
          (preview.status, submit) match
            case (MergePreviewStatus.Clean(), MergeSubmitState.Submitting) => List(BlockedMessages.MergeRunning)
            case (MergePreviewStatus.Clean(), _)                          => Nil
            case _                                                        => List(BlockedMessages.MergeConflicts)
        case (LoadState.Loading, _) => List(BlockedMessages.MergeChecking)
        case _                      => List(BlockedMessages.MergeNotChecked)
      }
    div(
      cls := "merge-modal-overlay",
      onClick --> { _ => state.close() },
      div(
        cls := "merge-modal",
        onClick.stopPropagation --> { _ => () },
        div(cls := "merge-modal-title", s"Merge '⎇ ${name.value}' into main"),
        div(cls := "merge-modal-body", child <-- state.preview.signal.map(renderPreview(state, name, _))),
        child.maybe <-- state.submit.signal.map {
          case MergeSubmitState.Failed(failure) =>
            Some(FailureNotice(failure, None))
          case _ => None
        },
        div(
          cls := "merge-modal-actions",
          FormInputs.blockable(
            button(
              tpe := "button",
              cls := "merge-modal-merge-btn",
              "Merge",
              onClick --> { _ => state.merge(onMerged) }
            ),
            mergeBlockedBy
          ),
          button(tpe := "button", "Cancel", onClick --> { _ => state.close() })
        )
      )
    )

  private def renderPreview(
    state: ScenarioMergeState,
    name: ScenarioName.ScenarioName,
    preview: LoadState[MergePreviewResponse]
  ): HtmlElement =
    preview match
      case LoadState.Loading =>
        div(cls := "merge-modal-status", MergeMessages.Checking)
      case LoadState.Failed(failure) =>
        div(
          FailureNotice(failure, None),
          recheckButton(state)
        )
      // No request is in flight, so the re-check is offered rather than a
      // progress sentence. `Idle` is where a settled request with no result
      // lands — an expired workspace or an absent scenario.
      case LoadState.Idle =>
        div(recheckButton(state))
      // The preview status is a closed set, so every outcome has its own arm
      // and there is no wildcard rendering an unrecognised status as an empty
      // conflict list.
      case LoadState.Loaded(result) =>
        result.status match
          case MergePreviewStatus.Clean() =>
            div(cls := "merge-modal-status", MergeMessages.Clean)

          case MergePreviewStatus.ScenarioMissing() =>
            FailureNotice.lines(List(MergeMessages.ScenarioMissing), None)

          case MergePreviewStatus.DuplicateNames(perTree) =>
            div(
              div(cls := "merge-modal-error", MergeMessages.DuplicateNamesHeading),
              div(cls := "merge-modal-conflicts", MergeMessages.duplicateNames(perTree)),
              div(cls := "merge-modal-hint", MergeMessages.DuplicateNamesHint),
              recheckButton(state)
            )

          case MergePreviewStatus.Conflicts(entries) =>
            div(
              div(cls := "merge-modal-error", MergeMessages.conflictsHeading(entries)),
              ul(
                cls := "merge-modal-conflicts",
                entries.map(entry =>
                  li(
                    div(MergeMessages.entryLabel(name, entry)),
                    MergeMessages.fieldLines(name, entry).map(line => div(cls := "merge-modal-conflict-detail", line)),
                    MergeMessages.presenceLine(name, entry).map(line => div(cls := "merge-modal-conflict-detail", line))
                  )
                )
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
