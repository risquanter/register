package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.GlobalError

/** Dismissible global error banner for unhandled / cross-cutting errors.
  *
  * Renders at the top of the layout (between Header and main content).
  * Only visible when the `globalError` signal contains `Some(error)`.
  *
  * This is the "safety net" component from Phase I.a / Option A:
  * it handles errors that have no per-view handler (e.g. health-check
  * failure, future workspace auth errors, SSE disconnection).
  *
  * Per-view errors (LoadState.Failed, SubmitState.Failed) are NOT
  * routed here — they continue to render inline in their owning views.
  *
  * @see GlobalError for the error ADT
  * @see ADR-008 / ADR-010 for error handling strategy
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

  private def renderBanner(error: GlobalError, onDismiss: () => Unit): HtmlElement =
    val (icon, message) = error match
      case GlobalError.ValidationFailed(errors) =>
        ("⚠", errors.map(_.message).mkString("; "))

      case GlobalError.NetworkError(msg, retryable) =>
        val hint = if retryable then " — will retry" else ""
        ("⚡", s"$msg$hint")

      case GlobalError.Conflict(msg) =>
        ("🔄", msg)

      case GlobalError.ServerError(msg) =>
        ("🔥", msg)

      case GlobalError.DependencyError(msg) =>
        ("⚡", s"Service issue: $msg")

    div(
      cls := "error-banner",
      span(cls := "error-banner-icon", icon),
      span(cls := "error-banner-message", message),
      button(
        cls := "error-banner-dismiss",
        "✕",
        onClick --> (_ => onDismiss())
      )
    )
