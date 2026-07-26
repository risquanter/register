package app.components

import com.raquo.laminar.api.L.{*, given}

/** Collapsible card for a Design-view form: a header row with a chevron and the
  * form title, and a body that shows/hides by `expanded`. The body is always
  * mounted (so the form's subscriptions and disabled state stay live) — only its
  * `display` toggles.
  *
  * Which form is expanded is driven by the caller's `FormMode` (via `expanded`),
  * not by a local toggle: exactly one form is active/expanded at a time. Clicking
  * a COLLAPSED header activates that form (`onHeaderActivate`); clicking the
  * expanded (active) header is inert — you don't collapse the form you're in.
  *
  * Shares the slot-card visual system with the Analyze view (ADR-019 Pattern 4:
  * owns no domain state; `expanded` in, activation callback out).
  */
object FormCard:

  def apply(
    header:           HtmlElement,
    body:             HtmlElement,
    expanded:         Signal[Boolean],
    onHeaderActivate: () => Unit
  ): HtmlElement =
    // Strict mirror of `expanded` so the click handler can sample it (a plain
    // Signal has no `.now()`).
    val expandedNow: Var[Boolean] = Var(false)
    div(
      cls := "slot-card form-card",
      cls("slot-card--collapsed") <-- expanded.map(!_),
      expanded --> expandedNow.writer,
      div(
        cls := "slot-card-header",
        cls("slot-card-header--activatable") <-- expanded.map(!_),
        onClick --> { _ => if !expandedNow.now() then onHeaderActivate() },
        child <-- expanded.map {
          case true  => Icons.chevronDown("slot-card-chevron")
          case false => Icons.chevronRight("slot-card-chevron")
        },
        header
      ),
      div(
        cls := "slot-card-body",
        display <-- expanded.map(if _ then "flex" else "none"),
        body
      )
    )
