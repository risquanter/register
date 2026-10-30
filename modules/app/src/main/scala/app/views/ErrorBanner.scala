package app.views

import com.raquo.laminar.api.L.*
import app.state.GlobalError
import com.risquanter.register.domain.data.iron.TraceId

/** Dismissible banner for failures no per-view channel caught.
  *
  * Renders between the header and the main content, and is visible only while
  * the signal holds a failure. A per-view error continues to render inline in
  * its own view and is not routed here, because the banner supplements that
  * display rather than replacing it (ADR-019 Pattern 7).
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

  /** How one failure is presented: its icon, its sentences, the trace to offer
    * for copying, and which styling it takes. A refusal of several fields has
    * one sentence per refusal, so no refusal is joined into another's.
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
    case GlobalError.ValidationFailed(errors) =>
      Presentation("⚠", errors.map(_.message), None, ErrorStyling)

    case GlobalError.NetworkError(message) =>
      Presentation("⚡", List(message), None, ErrorStyling)

    case GlobalError.InputRejected(message) =>
      Presentation("⚠", List(message), None, ErrorStyling)

    case GlobalError.Conflict(message) =>
      Presentation("🔄", List(message), None, ErrorStyling)

    case GlobalError.NotFound =>
      Presentation("⚠", List(ErrorMessages.NotFound), None, ErrorStyling)

    case GlobalError.WorkspaceExpired(message) =>
      Presentation("ℹ", List(message), None, InfoStyling)

    case GlobalError.ServerError(message, trace) =>
      Presentation("🔥", List(message), trace, ErrorStyling)

    case GlobalError.DependencyError(message, trace) =>
      Presentation("⚡", List(message), trace, ErrorStyling)

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
