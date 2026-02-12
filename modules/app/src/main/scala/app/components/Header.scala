package app.components

import com.raquo.laminar.api.L.{*, given}
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.RiskTreeEndpoints

/** Header component with backend health indicator.
  *
  * Fires `GET /health` on mount and displays a connection status dot.
  * Uses the ZJS `endpoint(payload).emitTo(bus)` pattern.
  */
object Header extends RiskTreeEndpoints:

  /** None = checking, Some(true) = connected, Some(false) = failed */
  private val healthStatus: Var[Option[Boolean]] = Var(None)

  def apply(): HtmlElement =
    headerTag(
      cls := "app-header",
      onMountCallback { _ =>
        healthEndpoint(())
          .tap(_ => zio.ZIO.succeed(healthStatus.set(Some(true))))
          .tapError(_ => zio.ZIO.succeed(healthStatus.set(Some(false))))
          .runJs
      },
      div(
        cls := "header-content",
        h1("Risquanter Register"),
        p(cls := "tagline", "Create and configure risk models"),
        div(
          cls := "health-indicator",
          span(cls <-- healthStatus.signal.map {
            case None        => "health-dot health-checking"
            case Some(true)  => "health-dot health-ok"
            case Some(false) => "health-dot health-error"
          }),
          span(cls := "health-label", child.text <-- healthStatus.signal.map {
            case None        => "Checking…"
            case Some(true)  => "Connected"
            case Some(false) => "Disconnected"
          })
        )
      )
    )
