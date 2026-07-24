package app.components

import com.raquo.laminar.api.L.{*, given}

/** Bordered, collapsible container for one compared branch's tree view:
  * swatch + branch name in the header, the branch's own tree view as the
  * body. Each card is an independent Ctrl+click surface — selection identity
  * in compare mode is the pair (branch, node).
  *
  * Owns only the local open/collapsed flag; everything else comes in as
  * arguments (ADR-019 Pattern 4).
  */
object BranchCard:

  /** @param swatch The header's swatch element — a `BranchPalettePicker` (a
    *               clickable swatch opening the branch-colour popover) or any
    *               plain coloured span carrying the `branch-card-swatch`
    *               class.
    */
  def apply(
    swatch:     HtmlElement,
    branchName: Signal[String],
    body:       HtmlElement
  ): HtmlElement =
    val open: Var[Boolean] = Var(true)
    div(
      cls := "branch-card",
      cls("branch-card--collapsed") <-- open.signal.map(!_),
      div(
        cls := "branch-card-header",
        onClick --> { _ => open.update(v => !v) },
        child <-- open.signal.map {
          case true  => Icons.chevronDown("branch-card-chevron")
          case false => Icons.chevronRight("branch-card-chevron")
        },
        swatch,
        span(cls := "branch-card-name", child.text <-- branchName)
      ),
      div(
        cls := "branch-card-body",
        display <-- open.signal.map(if _ then "flex" else "none"),
        body
      )
    )
