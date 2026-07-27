package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import app.core.*
import com.risquanter.register.domain.data.iron.{BranchChoice, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.WorkspaceTreeEndpoints
import com.risquanter.register.http.responses.{TreeHistoryResponse, TreeHistoryEntry}

/** Reactive state for one (tree, branch)'s commit history — the stop list a
  * `HistorySlider` renders. Entries are oldest-first, as `getHistory` returns
  * them (commit-DAG ancestry order), so they map directly to the slider's
  * left-to-right fill with no reordering. The compared branch's own curves and
  * changed-node markers live in its `LECChartState`/`ChangedNodesState`; this
  * holds only the history axis.
  */
final class TreeHistoryState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends WorkspaceTreeEndpoints:

  // Not written directly by `loadHistory`/`reset`: driven from the EventBus-fed
  // `flatMapSwitch` pipeline below, so a stale response can never land after a
  // newer request has started. Same mechanism as `ChangedNodesState`.
  val history: Var[LoadState[TreeHistoryResponse]] = Var(LoadState.Idle)

  private val historyTrigger =
    new EventBus[Option[() => EventStream[Either[Throwable, TreeHistoryResponse]]]]

  // Idempotency guard: callers fire on every tick of a combined signal, and
  // `Var.set` doesn't dedupe by value — an unguarded write would rebuild the
  // slider on a "change" that never happened.
  ZJS.loadStatePipeline(historyTrigger.events).foreach { v =>
    if history.now() != v then history.set(v)
  }(using unsafeWindowOwner)

  /** Fetch `treeId`'s commit history on `branch` (oldest-first, most recent `n`). */
  def loadHistory(treeId: TreeId, branch: BranchChoice, n: Int = 50): Unit =
    keySignal.now() match
      case Some(key) =>
        historyTrigger.emit(Some(() =>
          getTreeHistoryEndpoint((userIdAccessor(), key, treeId, branch, n)).toOutcomeEventStream
        ))
      case None => reset()

  /** Also supersedes an in-flight fetch, if one was still running. */
  def reset(): Unit =
    historyTrigger.emit(None)

  /** Oldest-first commit stops for the slider; empty for any non-loaded state. */
  val commits: Signal[List[TreeHistoryEntry]] = history.signal.map(TreeHistoryState.deriveCommits)

object TreeHistoryState:
  /** Pure derivation extracted from `commits` so it's testable without a
    * Laminar `Var`/`Signal` harness. */
  def deriveCommits(result: LoadState[TreeHistoryResponse]): List[TreeHistoryEntry] = result match
    case LoadState.Loaded(resp) => resp.entries
    case _                      => Nil
