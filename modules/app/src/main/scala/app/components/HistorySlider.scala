package app.components

import com.raquo.laminar.api.L.{*, given}

import com.risquanter.register.domain.data.iron.CommitHash
import com.risquanter.register.http.responses.TreeHistoryEntry

/** A discrete history slider for one (tree, branch). Stops are index-spaced —
  * one notch per commit — laid out left-to-right in `commits`' own oldest-first
  * order (as `getHistory` returns it, no reordering): left edge = oldest
  * ("initial"), right edge = newest = branch head ("latest"). The rightmost
  * stop is the live head and pins `None`; every earlier stop pins
  * `Some(commitHash)`. Each stop shows its timestamp and short hash. The chosen
  * pin is emitted via `onPick`.
  */
object HistorySlider:

  def apply(
    commits: Signal[List[TreeHistoryEntry]],
    selected: Signal[Option[CommitHash]],
    onPick: Option[CommitHash] => Unit
  ): HtmlElement =
    div(
      cls := "history-slider",
      children <-- commits.map { list =>
        if list.isEmpty then Nil
        else list.zipWithIndex.map { (entry, i) => renderStop(entry, i == list.size - 1, selected, onPick) }
      }
    )

  /** The newest stop pins `None` (live head); every earlier stop pins its own
    * commit hash (a read-only rewound view). */
  private def renderStop(
    entry: TreeHistoryEntry,
    isNewest: Boolean,
    selected: Signal[Option[CommitHash]],
    onPick: Option[CommitHash] => Unit
  ): HtmlElement =
    val pin: Option[CommitHash] = if isNewest then None else Some(entry.commitHash)
    button(
      cls := "history-stop",
      cls("history-stop--latest") := isNewest,
      cls("history-stop--active") <-- selected.map(_ == pin),
      tpe := "button",
      title := stopLabel(entry, isNewest),
      onClick --> (_ => onPick(pin))
    )

  /** Tooltip text: timestamp + short hash, with the head marked "latest". */
  private def stopLabel(entry: TreeHistoryEntry, isNewest: Boolean): String =
    val shortHash = entry.commitHash.value.take(8)
    val suffix = if isNewest then " · latest" else ""
    s"${entry.at} · $shortHash$suffix"
