package app.components

import com.raquo.laminar.api.L.*

import app.core.ViewFailure
import app.views.ErrorMessages
import com.risquanter.register.domain.data.iron.TraceId

/** The app's one box for a failure a view shows inline: a load that failed, a
  * submit that was refused, a request that never arrived.
  *
  * Every such failure renders through here, so they share one look. The
  * messages under a form's inputs are not failures of a request and keep
  * their own style.
  */
object FailureNotice:

  /** A failure's lines, one per refusal, the trace when the failure offers one
    * for reporting, and a retry action when the caller offers one. */
  def apply(failure: ViewFailure, retry: Option[() => Unit]): HtmlElement =
    box(ErrorMessages.viewFailedLines(failure), ErrorMessages.traceOf(failure), retry)

  /** Lines that are already sentences, in the same box. */
  def lines(messages: List[String], retry: Option[() => Unit]): HtmlElement =
    box(messages, None, retry)

  /** The trace is a muted line under the sentences, selectable so it can be
    * copied into a report. */
  private def box(messages: List[String], trace: Option[TraceId], retry: Option[() => Unit]): HtmlElement =
    div(
      cls := "failure-notice",
      messages.map(div(_)),
      trace.map(t => div(cls := "failure-notice-trace", t.value)),
      retry.map(action =>
        button(tpe := "button", cls := "failure-notice-retry", "Retry", onClick --> (_ => action()))
      )
    )
