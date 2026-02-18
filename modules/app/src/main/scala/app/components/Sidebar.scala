package app.components

import com.raquo.laminar.api.L.{*, given}
import app.state.{NavigationState, Section}

/** Left vertical sidebar — brand, section navigation, version footer.
  *
  * Pure view component (ADR-019 Pattern 1): receives `NavigationState`
  * as a parameter, emits navigation intents via callbacks on the state.
  *
  * Layout and styling are driven entirely by CSS classes defined in
  * `app.css` — this component only emits semantic class names.
  */
object Sidebar:

  def apply(navState: NavigationState): HtmlElement =
    asideTag(
      cls := "sidebar",
      // ── Brand ──
      div(
        cls := "sidebar-logo",
        Icons.signet(),
        span(cls := "brand-name", "Risquanter")
      ),
      // ── Section label ──
      div(cls := "sidebar-section-label", "Tool"),
      // ── Nav items ──
      navTag(
        cls := "sidebar-nav",
        navItem(Section.Design, Icons.design(), "Design", navState),
        navItem(Section.Analyze, Icons.analyze(), "Analyze", navState)
      ),
      // ── Footer ──
      div(cls := "sidebar-footer", "v0.3")
    )

  /** A single navigation item with reactive active state. */
  private def navItem(
    section: Section,
    icon: SvgElement,
    label: String,
    navState: NavigationState
  ): HtmlElement =
    div(
      cls <-- navState.isActive(section).map: active =>
        if active then "sidebar-nav-item active" else "sidebar-nav-item",
      icon,
      label,
      onClick --> (_ => navState.navigate(section))
    )
