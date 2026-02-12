package app.components

import com.raquo.laminar.api.L.{*, given}

/** Main layout wrapper component */
object Layout:

  def apply(content: HtmlElement*): HtmlElement =
    div(
      cls := "app-layout",
      Header(),
      mainTag(
        cls := "main-content",
        content
      ),
      footerTag(
        cls := "app-footer",
        p("© 2026 Risquanter")
      )
    )
