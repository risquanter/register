package com.risquanter.register.infra

import zio.*
import zio.test.*

/** Deterministic TestClock tests for the startup readiness gate (ADR-031).
  *
  * No real sleeping: the fiber running the gate is forked and the TestClock is
  * stepped past the backoff sleeps. Jitter draws from TestRandom (seeded), and
  * every backoff delay is capped at 5s, so stepping in 1s increments covers all
  * scheduled sleeps deterministically.
  */
object StartupReadinessSpec extends ZIOSpecDefault:

  private val attemptTimeout = 5.seconds
  private val budget         = 45.seconds

  /** Probe that fails with a typed error until `failures` attempts have happened. */
  private def flakyProbe(failures: Int, calls: Ref[Int]): ZIO[Any, Throwable, Unit] =
    calls.updateAndGet(_ + 1).flatMap { n =>
      if n <= failures then ZIO.fail(new RuntimeException(s"not ready (attempt $n)"))
      else ZIO.unit
    }

  /** Step the TestClock until the fiber completes (or the step budget is exhausted). */
  private def stepUntilDone[E, A](fiber: Fiber.Runtime[E, A], maxSteps: Int = 240): UIO[Exit[E, A]] =
    def loop(remaining: Int): UIO[Exit[E, A]] =
      fiber.poll.flatMap {
        case Some(exit)                 => ZIO.succeed(exit)
        case None if remaining <= 0     => fiber.interrupt
        case None                       => TestClock.adjust(1.second) *> loop(remaining - 1)
      }
    loop(maxSteps)

  def spec = suite("StartupReadiness")(

    test("succeeds immediately when the dependency is ready — single attempt, no retries") {
      for
        calls <- Ref.make(0)
        _     <- StartupReadiness.awaitReady("dep", flakyProbe(0, calls), attemptTimeout, budget)
        n     <- calls.get
      yield assertTrue(n == 1)
    },

    test("retries a failing probe and succeeds once the dependency becomes ready within budget") {
      for
        calls <- Ref.make(0)
        fiber <- StartupReadiness.awaitReady("dep", flakyProbe(3, calls), attemptTimeout, budget).fork
        exit  <- stepUntilDone(fiber)
        n     <- calls.get
      yield assertTrue(exit.isSuccess, n == 4)
    },

    test("never-ready dependency fails closed after the budget — not before, not never") {
      for
        calls    <- Ref.make(0)
        fiber    <- StartupReadiness.awaitReady("dep", flakyProbe(Int.MaxValue, calls), attemptTimeout, budget).fork
        // Well inside the budget the gate must still be waiting, not failed
        _        <- TestClock.adjust(10.seconds)
        early    <- fiber.poll
        exit     <- stepUntilDone(fiber)
        attempts <- calls.get
      yield assertTrue(
        early.isEmpty,          // still retrying at 10s of a 45s budget
        exit.isFailure,         // fail-closed after the budget
        attempts > 1            // it actually retried, not a single-shot failure
      )
    },

    test("final failure carries the probe's typed error, not a manufactured one") {
      for
        calls <- Ref.make(0)
        fiber <- StartupReadiness.awaitReady(
                   "dep",
                   calls.update(_ + 1) *> ZIO.fail(new IllegalStateException("connection refused")),
                   attemptTimeout,
                   budget
                 ).fork
        exit  <- stepUntilDone(fiber)
      yield exit match
        case Exit.Failure(cause) =>
          assertTrue(cause.failures.exists {
            case e: IllegalStateException => e.getMessage == "connection refused"
            case _                        => false
          })
        case Exit.Success(_) => assertTrue(false)
    },

    test("hung probe counts as not-ready: attempt is timed out and retried") {
      for
        calls <- Ref.make(0)
        // First attempt hangs forever; subsequent attempts succeed
        probe  = calls.updateAndGet(_ + 1).flatMap { n =>
                   if n == 1 then ZIO.never.unit else ZIO.unit
                 }
        fiber <- StartupReadiness.awaitReady("dep", probe, attemptTimeout, budget).fork
        exit  <- stepUntilDone(fiber)
        n     <- calls.get
      yield assertTrue(exit.isSuccess, n == 2)
    }
  )
