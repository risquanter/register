package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}

object WorkspaceReaperSpec extends ZIOSpecDefault:

  override def spec = suite("WorkspaceReaper")(
    // ========================================
    // isNoOp predicate (pure function tests)
    // ========================================

    test("isNoOp returns true when both ttl and idleTimeout are zero") {
      val config = WorkspaceConfig(
        ttl = Duration.ZERO,
        idleTimeout = Duration.ZERO,
        reaperInterval = Duration.ofMinutes(5)
      )
      assertTrue(WorkspaceReaper.isNoOp(config))
    },

    test("isNoOp returns true when both ttl and idleTimeout are negative") {
      val config = WorkspaceConfig(
        ttl = Duration.ofHours(-1),
        idleTimeout = Duration.ofHours(-1),
        reaperInterval = Duration.ofMinutes(5)
      )
      assertTrue(WorkspaceReaper.isNoOp(config))
    },

    test("isNoOp returns false when only ttl is zero but idleTimeout is positive") {
      val config = WorkspaceConfig(
        ttl = Duration.ZERO,
        idleTimeout = Duration.ofMinutes(30),
        reaperInterval = Duration.ofMinutes(5)
      )
      assertTrue(!WorkspaceReaper.isNoOp(config))
    },

    test("isNoOp returns false when only idleTimeout is zero but ttl is positive") {
      val config = WorkspaceConfig(
        ttl = Duration.ofHours(72),
        idleTimeout = Duration.ZERO,
        reaperInterval = Duration.ofMinutes(5)
      )
      assertTrue(!WorkspaceReaper.isNoOp(config))
    },

    test("isNoOp returns false for default config") {
      assertTrue(!WorkspaceReaper.isNoOp(TestConfigs.workspace))
    },

    // ========================================
    // Layer construction
    // ========================================

    test("layer constructs successfully with free-tier config") {
      val config = TestConfigs.workspace
      ZIO.scoped(
        WorkspaceReaper.layer.build
          .provide(
            ZLayer.succeed(config),
            WorkspaceStoreLive.layer,
            ZLayer.succeed(Scope.global)
          )
      ).as(assertTrue(true))
    },

    test("layer constructs successfully with enterprise (no-op) config") {
      val config = WorkspaceConfig(
        ttl = Duration.ZERO,
        idleTimeout = Duration.ZERO,
        reaperInterval = Duration.ofMinutes(5)
      )
      ZIO.scoped(
        WorkspaceReaper.layer.build
          .provide(
            ZLayer.succeed(config),
            WorkspaceStoreLive.layer,
            ZLayer.succeed(Scope.global)
          )
      ).as(assertTrue(true))
    },

    // ========================================
    // Fiber integration (TestClock-driven)
    // ========================================

    test("reaper fiber evicts workspace after interval elapses") {
      val config = WorkspaceConfig(
        ttl = Duration.ofMinutes(2),
        idleTimeout = Duration.ofMinutes(2),
        reaperInterval = Duration.ofMinutes(1)
      )
      ZIO.scoped {
        for
          store <- WorkspaceStoreLive.make(config)
          _     <- WorkspaceReaper.layer.build
                     .provide(
                       ZLayer.succeed(config),
                       ZLayer.succeed(store: WorkspaceStore),
                       ZLayer.succeed(Scope.global)
                     )
          key   <- store.create()
          // Advance past TTL + reaper interval.
          // TestClock.adjust wakes all sleeping fibers deterministically —
          // the reaper's ZIO.sleep completes and evictExpired runs within
          // the same adjust call, no yielding required.
          _     <- TestClock.adjust(4.minutes)
          exit  <- store.resolve(key).exit
        yield assert(exit)(fails(anything))
      }
    }
  )
