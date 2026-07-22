package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.{TreeViewState, LoadState}
import app.components.Icons
import com.risquanter.register.domain.data.iron.TreeId
import com.risquanter.register.http.responses.SimulationResponse

/** Dropdown selector for server-persisted risk trees.
  *
  * Loads the tree list on mount via `TreeViewState.loadTreeList()`.
  * When the user selects a tree, triggers structure fetch via `selectTree()`.
  *
  * Pure view function — owns no state (ADR-019 Pattern 1 + 4).
  * Receives `TreeViewState` as constructor arg (Pattern 2).
  */
object TreeListView:

  /** @param onNewTree      Design-view-only "start a fresh, blank tree" action —
    *                       absent in Analyze view, which has no builder to reset.
    * @param leadingControl Analyze-only baseline-branch picker (milestone-2b
    *                       Phase C follow-up, item 5), rendered beside the tree
    *                       `<select>` in the same row, each taking half the
    *                       width — visually paired, since choosing a branch and
    *                       choosing a tree are the same kind of "what am I
    *                       looking at" decision. Absent in Design, which already
    *                       has its own branch control (`BranchBar.toolbar`)
    *                       above this component instead.
    */
  def apply(
    state: TreeViewState,
    onNewTree: Option[() => Unit] = None,
    leadingControl: Option[HtmlElement] = None
  ): HtmlElement =
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
      onMountCallback(_ => state.loadTreeList()),
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
          child <-- state.availableTrees.signal.map {
            case LoadState.Idle    => renderPlaceholder("Waiting to load…")
            case LoadState.Loading => renderPlaceholder("Loading trees…")
            case LoadState.Failed(msg) => renderError(msg, state)
            case LoadState.Loaded(trees) =>
              if trees.isEmpty then renderPlaceholder("No saved trees yet.")
              else renderSelector(trees, state)
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

  private def renderSelector(trees: List[SimulationResponse], state: TreeViewState): HtmlElement =
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
        }
      )
    )
