package app.components

import com.raquo.laminar.api.L.{*, given}
import app.state.{NavigationState, Section, GlobalError}
import app.views.ErrorBanner

/** Top-level application shell: Sidebar | (TopBar + ErrorBanner + content).
  *
  * Pure structural layout — arranges Sidebar, TopBar, ErrorBanner, and
  * routed view containers. Owns no state and fires no effects.
  *
  * Follows ADR-019 Pattern 1 (composable function) and Pattern 2
  * (all state received as signals — never created internally).
  */
object AppShell:

  def apply(
    navState: NavigationState,
    globalError: Signal[Option[GlobalError]],
    onDismissError: () => Unit,
    healthStatus: Signal[Option[Boolean]],
    workspaceBadge: Signal[String],
    designView: HtmlElement,
    analyzeView: HtmlElement
  ): HtmlElement =
    div(
      cls := "app-shell",
      // ── Left sidebar ──
      Sidebar(navState),
      // ── Main area (topbar + error banner + routed content) ──
      div(
        cls := "app-main",
        // Slim contextual top bar
        headerTag(
          cls := "topbar",
          div(
            cls := "topbar-left",
            span(
              cls := "section-title",
              child.text <-- navState.activeSection.signal.map(_.label)
            )
          ),
          div(
            cls := "topbar-right",
            healthDot(healthStatus),
            span(
              cls := "topbar-badge",
              child.text <-- workspaceBadge
            )
          )
        ),
        // Global error banner
        ErrorBanner(globalError, onDismissError),
        // Routed content area
        mainTag(
          cls := "main-content",
          routableView(Section.Design, navState, designView),
          routableView(Section.Analyze, navState, analyzeView)
        ),
        // Footer
        footerTag(
          cls := "app-footer",
          p("© 2026 Risquanter")
        )
      )
    )

  /** View container with reactive show/hide driven by navigation state. */
  private def routableView(
    section: Section,
    navState: NavigationState,
    view: HtmlElement
  ): HtmlElement =
    div(
      cls := "view-container",
      display <-- navState.isActive(section).map(if _ then "block" else "none"),
      view
    )

  /** Health indicator dot — tri-state: checking → ok | error. */
  private def healthDot(status: Signal[Option[Boolean]]): HtmlElement =
    span(cls <-- status.map:
      case None        => "health-dot health-checking"
      case Some(true)  => "health-dot health-ok"
      case Some(false) => "health-dot health-error"
    )
