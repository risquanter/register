package app.components

import com.raquo.laminar.api.L.{*, given}

/** Bordered, collapsible container for one slot's tree view: the slot's
  * (branch, tree) picker row lives in the header, the slot's own tree view is
  * the body. Each card is an independent Ctrl+click surface — selection
  * identity is the pair (branch, node).
  *
  * Owns only the local open/collapsed flag; everything else comes in as
  * arguments (ADR-019 Pattern 4).
  */
object BranchCard:

  /** @param header        The header row content (swatch + branch/tree selects +
    *                      action buttons) — built by the caller. The chevron is
    *                      prepended by this component and is the only element
    *                      that toggles collapse, so the header's selects and
    *                      buttons stay clickable.
    * @param body          The slot's tree view.
    * @param initiallyOpen Whether the card starts expanded (comparand cards
    *                      start collapsed until their tree loads).
    * @param expandOn      Each event expands the card — used to auto-expand a
    *                      comparand card when its tree finishes loading. Manual
    *                      chevron toggling stays available.
    */
  def apply(
    header:        HtmlElement,
    body:          HtmlElement,
    initiallyOpen: Boolean = true,
    expandOn:      EventStream[Unit] = EventStream.empty
  ): HtmlElement =
    val open: Var[Boolean] = Var(initiallyOpen)
    div(
      cls := "slot-card",
      cls("slot-card--collapsed") <-- open.signal.map(!_),
      expandOn --> { _ => open.set(true) },
      div(
        cls := "slot-card-header",
        button(
          cls := "slot-card-chevron-btn",
          tpe := "button",
          onClick.stopPropagation --> { _ => open.update(v => !v) },
          child <-- open.signal.map {
            case true  => Icons.chevronDown("slot-card-chevron")
            case false => Icons.chevronRight("slot-card-chevron")
          }
        ),
        header
      ),
      div(
        cls := "slot-card-body",
        display <-- open.signal.map(if _ then "flex" else "none"),
        body
      )
    )
