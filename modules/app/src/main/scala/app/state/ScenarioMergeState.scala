package app.state

import zio.ZIO
import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import app.core.safeMessage
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceKeySecret, ScenarioName}
import com.risquanter.register.http.endpoints.ScenarioEndpoints
import com.risquanter.register.http.responses.MergePreviewResponse

/** Merge-request lifecycle for the merge modal. A separate enum from
  * `ScenarioSubmitState` (same rationale as that type's own doc): the
  * success payload differs and neither type is worth generalizing over.
  */
enum MergeSubmitState:
  case Idle
  case Submitting
  case Merged
  case Failed(message: String)

/** State for the "Merge into main…" modal (Design view).
  *
  * `target` doubles as the modal's open flag: `Some(name)` = open for that
  * scenario, `None` = closed. Opening fetches the server-side merge preview
  * (the byte-level conflict check — the ✎ diff markers deliberately cannot
  * answer this, see `ScenarioDiffService`/ADR-032); the modal's Merge button
  * is enabled only on a `"clean"` preview. The server re-checks on merge
  * regardless, so a stale-preview race degrades to a 409, never a bad merge.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class ScenarioMergeState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends ScenarioEndpoints:

  /** The scenario the modal is open for; `None` = modal closed. */
  val target: Var[Option[ScenarioName.ScenarioName]] = Var(None)

  val preview: Var[LoadState[MergePreviewResponse]] = Var(LoadState.Idle)

  val submit: Var[MergeSubmitState] = Var(MergeSubmitState.Idle)

  def open(name: ScenarioName.ScenarioName): Unit =
    target.set(Some(name))
    submit.set(MergeSubmitState.Idle)
    refreshPreview()

  def close(): Unit =
    target.set(None)
    preview.set(LoadState.Idle)
    submit.set(MergeSubmitState.Idle)

  /** (Re-)fetch the conflict preview for the open target. */
  def refreshPreview(): Unit =
    (target.now(), keySignal.now()) match
      case (Some(name), Some(key)) =>
        previewScenarioMergeEndpoint((userIdAccessor(), key, name)).loadInto(preview)
      case _ => ()

  /** Merge the open target into main. `onMerged` runs on success — the
    * owning view closes the modal and switches its context to main there.
    */
  def merge(onMerged: () => Unit): Unit =
    (target.now(), keySignal.now()) match
      case (Some(name), Some(key)) =>
        submit.set(MergeSubmitState.Submitting)
        mergeScenarioEndpoint((userIdAccessor(), key, name))
          .tap(_ => ZIO.succeed {
            submit.set(MergeSubmitState.Merged)
            onMerged()
          })
          .tapError(e => ZIO.succeed(submit.set(MergeSubmitState.Failed(e.safeMessage))))
          .runJs
      case _ => ()
