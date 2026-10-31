package app.views

import com.raquo.laminar.api.L.*
import app.state.GlobalError
import com.risquanter.register.domain.data.iron.TraceId

/** Dismissible banner for a condition of the whole session.
  *
  * Renders between the header and the main content, and is visible only while
  * the signal holds a condition. The result of a specific action is rendered
  * inline where the action was taken and never here (ADR-019 decision 7).
  */
object ErrorBanner:

  def apply(
    globalError: Signal[Option[GlobalError]],
    onDismiss: () => Unit
  ): HtmlElement =
    div(
      cls := "error-banner-container",
      child.maybe <-- globalError.map(_.map(renderBanner(_, onDismiss)))
    )

  /** How one condition is presented: its icon, its sentences, the trace to
    * offer for copying, and which styling it takes.
    *
    * The four values are derived from the same case, so they are produced
    * together in one match rather than by several matches on the same value.
    */
  private case class Presentation(
    icon: String,
    messages: List[String],
    trace: Option[TraceId],
    styling: String
  )

  private val ErrorStyling: String = "error-banner"
  private val InfoStyling: String  = "error-banner info-banner"

  private def present(error: GlobalError): Presentation = error match
    case GlobalError.ServerUnreachable =>
      Presentation("⚡", List(ErrorMessages.ConnectionLost), None, ErrorStyling)

    case GlobalError.DependencyDown(trace) =>
      Presentation("⚡", List(ErrorMessages.DependencyDown), trace, ErrorStyling)

    case GlobalError.WorkspaceExpired(message) =>
      Presentation("ℹ", List(message), None, InfoStyling)

  /** The trace is rendered as a muted line under the message and is selectable,
    * so someone reporting a fault can copy the identifier the server logged the
    * failure against.
    */
  private def renderBanner(error: GlobalError, onDismiss: () => Unit): HtmlElement =
    val presentation = present(error)
    div(
      cls := presentation.styling,
      span(cls := "error-banner-icon", presentation.icon),
      div(
        cls := "error-banner-body",
        presentation.messages.map(message => div(span(cls := "error-banner-message", message))),
        presentation.trace.map(t => div(cls := "error-banner-trace", t.value))
      ),
      button(
        cls := "error-banner-dismiss",
        "✕",
        onClick --> (_ => onDismiss())
      )
    )
