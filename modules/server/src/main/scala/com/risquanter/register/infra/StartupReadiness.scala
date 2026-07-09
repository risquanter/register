package com.risquanter.register.infra

import zio.*
import java.util.concurrent.TimeUnit

/** Bounded, fail-closed readiness gate for startup dependencies.
  *
  * Startup dependency readiness is distinct from request-path resilience:
  * request-path retries/timeouts are the mesh's responsibility (ADR-012 §4),
  * while gating the process's own boot on a dependency becoming reachable is
  * application lifecycle wiring — the mesh cannot cover the window in which
  * its own policies are still being reconciled.
  *
  * Constraints (ADR-031): bounded total budget, fail-closed after the budget
  * (process exits, orchestrator restarts), boot-only, confined to lifecycle
  * wiring (ZLayer construction) — never used inside request handlers.
  *
  * @see ADR-031 — Startup Dependency Readiness vs Request-Path Resilience
  */
object StartupReadiness:

  /** Readiness polling policy — each requirement clause is one combinator:
    *
    *   - `Schedule.exponential(backoffBase).jittered` — growing, jittered gaps
    *     (avoids thundering herd when many pods boot simultaneously)
    *   - `|| Schedule.spaced(backoffCap)` — union takes the smaller delay:
    *     caps the gap so a late-arriving dependency is caught quickly
    *   - `&& Schedule.upTo(budget)` — intersection stops recurring once total
    *     elapsed time exceeds the budget. The bound is elapsed time, not an
    *     attempt count: jitter makes a count's total duration a random
    *     variable, while the requirement ("keep trying for ~45s") is
    *     time-shaped (ADR-031 §2)
    */
  def schedule(
    budget:      Duration,
    backoffBase: Duration = 500.millis,
    backoffCap:  Duration = 5.seconds
  ): Schedule[Any, Any, Any] =
    (Schedule.exponential(backoffBase).jittered || Schedule.spaced(backoffCap)) && Schedule.upTo(budget)

  /** Poll `probe` until it succeeds; fail closed with the probe's last typed
    * error once `budget` elapses. Each attempt is bounded by `attemptTimeout`
    * (a hung probe counts as not-ready). Every retry logs the real cause so
    * the wait is visible, never silent.
    */
  def awaitReady[R](
    name:           String,
    probe:          ZIO[R, Throwable, Any],
    attemptTimeout: Duration,
    budget:         Duration
  ): ZIO[R, Throwable, Unit] =
    for
      startMs <- Clock.currentTime(TimeUnit.MILLISECONDS)
      attempt  = probe.unit
                   .timeoutFail(new RuntimeException(
                     s"$name readiness probe timed out after ${attemptTimeout.toMillis} ms"
                   ))(attemptTimeout)
                   .tapError { e =>
                     Clock.currentTime(TimeUnit.MILLISECONDS).flatMap { nowMs =>
                       ZIO.logWarning(
                         s"$name not ready (elapsed ${nowMs - startMs} ms of ${budget.toMillis} ms budget): ${e.getMessage}"
                       )
                     }
                   }
      _       <- attempt
                   .retry(schedule(budget))
                   .tapError(e => ZIO.logError(
                     s"$name did not become ready within ${budget.toMillis} ms — failing closed: ${e.getMessage}"
                   ))
      _       <- ZIO.logInfo(s"$name ready")
    yield ()
