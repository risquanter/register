package app.components

import com.raquo.laminar.api.L.{*, given}

/** Header component for the app */
object Header:

  def apply(): HtmlElement =
    headerTag(
      cls := "app-header",
      div(
        cls := "header-content",
        h1("Risk Tree Builder"),
        p(cls := "tagline", "Create and configure risk models")
      )
    )
