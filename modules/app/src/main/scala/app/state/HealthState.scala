package app.state

import com.raquo.laminar.api.L.{*, given}
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.RiskTreeEndpoints
import zio.ZIO

/** Backend health status state.
  *
  * Owns the tri-state health signal used by `AppShell`:
  *   - `None`        = checking / unknown
  *   - `Some(true)`  = healthy
  *   - `Some(false)` = unreachable / failed
  *
  * Kept separate from `Main` so app wiring stays orchestration-only.
  */
final class HealthState extends RiskTreeEndpoints:

  val status: Var[Option[Boolean]] = Var(None)

  /** Runs a one-shot backend health probe and updates `status`. */
  def refresh(): Unit =
    healthEndpoint(())
      .tap(_ => ZIO.succeed(status.set(Some(true))))
      .tapError(_ => ZIO.succeed(status.set(Some(false))))
      .runJsQuiet
