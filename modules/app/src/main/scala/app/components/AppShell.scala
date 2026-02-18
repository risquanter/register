package app.components

import com.raquo.laminar.api.L.{*, given}
import app.state.{NavigationState, Section, GlobalError}
import app.views.ErrorBanner
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.RiskTreeEndpoints

/** Top-level application shell: Sidebar | (TopBar + ErrorBanner + content).
  *
  * Replaces the previous `Layout` (Header → content → Footer) with
  * a sidebar-driven layout that switches between Design and Analyze views.
  *
  * Structural responsibilities only — all visual styling is in `app.css`.
  * Follows ADR-019 Pattern 1 (composable function) and Pattern 2
  * (state passed as parameter, not inherited).
  */
object AppShell extends RiskTreeEndpoints:

  def apply(
    navState: NavigationState,
    globalError: Signal[Option[GlobalError]],
    onDismissError: () => Unit,
    workspaceBadge: Signal[String],
    designView: HtmlElement,
    analyzeView: HtmlElement
  ): HtmlElement =

    // Health indicator — fires GET /health on mount (migrated from Header)
    val healthStatus: Var[Option[Boolean]] = Var(None)

    div(
      cls := "app-shell",
      onMountCallback { _ =>
        healthEndpoint(())
          .tap(_ => zio.ZIO.succeed(healthStatus.set(Some(true))))
          .tapError(_ => zio.ZIO.succeed(healthStatus.set(Some(false))))
          .runJs
      },
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
              child.text <-- navState.activeSection.signal.map:
                case Section.Design  => "Design"
                case Section.Analyze => "Analyze"
            )
          ),
          div(
            cls := "topbar-right",
            span(cls <-- healthStatus.signal.map:
              case None        => "health-dot health-checking"
              case Some(true)  => "health-dot health-ok"
              case Some(false) => "health-dot health-error"
            ),
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
          div(
            cls := "view-container",
            display <-- navState.isActive(Section.Design).map(if _ then "flex" else "none"),
            designView
          ),
          div(
            cls := "view-container",
            display <-- navState.isActive(Section.Analyze).map(if _ then "flex" else "none"),
            analyzeView
          )
        ),
        // Footer
        footerTag(
          cls := "app-footer",
          p("© 2026 Risquanter")
        )
      )
    )
