package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.{TreeViewState, LoadState}
import app.components.Icons
import com.risquanter.register.domain.data.iron.TreeId
import com.risquanter.register.http.responses.SimulationResponse

/** Dropdown selector for server-persisted risk trees.
  *
  * Ensures the tree list is loaded on mount via
  * `TreeViewState.ensureTreeListLoaded()` (skips when the shared
  * `TreeListState` already has this branch's list or a fetch in flight).
  * When the user selects a tree, triggers structure fetch via `selectTree()`.
  *
  * Pure view function — owns no state (ADR-019 Pattern 1 + 4).
  * Receives `TreeViewState` as constructor arg (Pattern 2).
  */
object TreeListView:

  /** @param onNewTree      Design-view-only "start a fresh, blank tree" action —
    *                       absent in Analyze view, which has no builder to reset.
    * @param leadingControl Analyze-only baseline-branch picker, rendered beside
    *                       the tree `<select>` in the same row, each taking half
    *                       the width — visually paired, since choosing a branch
    *                       and choosing a tree are the same kind of "what am I
    *                       looking at" decision. Absent in Design, which already
    *                       has its own branch control (`BranchBar.toolbar`)
    *                       above this component instead.
    * @param onRefreshExtra Called on the refresh button alongside the panel's
    *                       own list + selected-tree refresh — Analyze passes the
    *                       compare card's refresh so a failed fetch there is
    *                       recoverable from the same control.
    */
  def apply(
    state: TreeViewState,
    onNewTree: Option[() => Unit] = None,
    leadingControl: Option[HtmlElement] = None,
    onRefreshExtra: () => Unit = () => ()
  ): HtmlElement =
    // Remembers the last successfully loaded list so a refetch (branch switch,
    // refresh click) doesn't blank the selector while in flight — every
    // subsequent load re-enters `Loading` the same way the first one did, and
    // without this, the whole selector + refresh button would tear down and
    // rebuild on every one of those, not just the first. Same principle as
    // AnalyzeView's combinedSpecSignal: don't discard a perfectly good display
    // just because a new fetch started; only replace it once the new data
    // actually lands. `None` only means "never loaded successfully yet" — the
    // one case that should still show "Loading trees…", not a stale selector.
    val lastKnownTrees: Var[Option[List[SimulationResponse]]] = Var(None)

    div(
      cls := "tree-list-view",
      div(
        cls := "tree-list-header",
        h3("Saved Trees"),
        onNewTree.map(action =>
          button(
            cls := "new-tree-btn",
            tpe := "button",
            "+ New Tree",
            onClick --> { _ => action() }
          )
        ).getOrElse(emptyNode)
      ),
      // ensureTreeListLoaded, not loadTreeList: both views' instances mount at
      // startup (only hidden via CSS), and the shared TreeListState collapses
      // their concurrent asks for the same branch into one request.
      onMountCallback(_ => state.ensureTreeListLoaded()),
      state.availableTrees.changes.collect { case LoadState.Loaded(trees) => trees } --> { trees =>
        lastKnownTrees.set(Some(trees))
      },
      div(
        cls := "tree-list-controls-row",
        // Labeled explicitly — otherwise two adjacent selects with no caption
        // read as unclear duplicates rather than "which scenario, which tree".
        leadingControl.map(c => div(
          cls := "tree-list-branch-slot",
          span(cls := "tree-list-slot-label", "Scenario"),
          c
        )).getOrElse(emptyNode),
        div(
          cls := "tree-list-tree-slot",
          span(cls := "tree-list-slot-label", "Tree"),
          child <-- state.availableTrees.combineWith(lastKnownTrees.signal).map {
            case (LoadState.Loaded(trees), _) =>
              if trees.isEmpty then renderPlaceholder("No saved trees yet.")
              else renderSelector(trees, state, onRefreshExtra)
            case (LoadState.Loading, Some(prev)) if prev.nonEmpty =>
              renderSelector(prev, state, onRefreshExtra)
            case (LoadState.Loading, _) => renderPlaceholder("Loading trees…")
            case (LoadState.Failed(msg), _) => renderError(msg, state)
            case (LoadState.Idle, _) => renderPlaceholder("Waiting to load…")
          }
        )
      )
    )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-list-placeholder", p(message))

  private def renderError(message: String, state: TreeViewState): HtmlElement =
    div(
      cls := "tree-list-error",
      p(cls := "error-message", s"Failed to load: $message"),
      button(
        cls := "retry-btn",
        "Retry",
        onClick --> (_ => state.loadTreeList())
      )
    )

  private def renderSelector(trees: List[SimulationResponse], state: TreeViewState, onRefreshExtra: () => Unit): HtmlElement =
    val selectedValue: Signal[String] = state.selectedTreeId.signal.map {
      case Some(id) => id.value
      case None     => ""
    }

    div(
      cls := "tree-list-selector",
      select(
        cls := "tree-select",
        value <-- selectedValue,
        onChange.mapToValue --> { idStr =>
          if idStr.nonEmpty then
            TreeId.fromString(idStr).foreach(state.selectTree)
        },
        option(value := "", disabled := true, selected := true, "— Select a tree —"),
        trees.map { tree =>
          option(value := tree.id.value, tree.name)
        }
      ),
      button(
        cls := "refresh-btn",
        Icons.refresh("refresh-icon"),
        title := "Refresh tree list and selected tree",
        onClick --> { _ =>
          state.loadTreeList()
          state.refreshSelectedTree()
          onRefreshExtra()
        }
      )
    )
