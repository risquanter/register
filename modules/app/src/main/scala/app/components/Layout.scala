package app.components

import com.raquo.laminar.api.L.{*, given}
import app.state.GlobalError
import app.views.ErrorBanner

/** Main layout wrapper component.
  *
  * Renders: Header → ErrorBanner (if error) → main content → footer.
  * The error banner is a global safety net for unhandled errors (Phase I.a).
  */
object Layout:

  def apply(
    globalError: Signal[Option[GlobalError]],
    onDismissError: () => Unit,
    content: HtmlElement*
  ): HtmlElement =
    div(
      cls := "app-layout",
      Header(),
      ErrorBanner(globalError, onDismissError),
      mainTag(
        cls := "main-content",
        content
      ),
      footerTag(
        cls := "app-footer",
        p("© 2026 Risquanter")
      )
    )
