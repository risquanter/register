package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}

object WorkspaceReaperSpec extends ZIOSpecDefault:

  override def spec = suite("WorkspaceReaper")(
    test("evicts expired workspaces after reaper interval") {
      val config = TestConfigs.workspace.copy(
        ttl = Duration.ofMinutes(5),
        idleTimeout = Duration.ofMinutes(5),
        reaperInterval = Duration.ofMinutes(1)
      )
      for
        store   <- WorkspaceStoreLive.make(config)
        key     <- store.create()
        // Workspace exists before expiry
        ws      <- store.resolve(key)
        _       <- assertTrue(ws.key == key)
        // Advance past TTL so workspace is expired
        _       <- TestClock.adjust(6.minutes)
        // Run a single reap cycle
        evicted <- store.evictExpired
      yield assertTrue(evicted == 1)
    },

    test("skips non-expired workspaces") {
      val config = TestConfigs.workspace.copy(
        ttl = Duration.ofHours(24),
        idleTimeout = Duration.ofHours(1),
        reaperInterval = Duration.ofMinutes(1)
      )
      for
        store   <- WorkspaceStoreLive.make(config)
        _       <- store.create()
        // Advance less than idle timeout
        _       <- TestClock.adjust(30.minutes)
        evicted <- store.evictExpired
      yield assertTrue(evicted == 0)
    },

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
          // Advance past TTL (workspace expires)
          _     <- TestClock.adjust(3.minutes)
          // Advance past reaper interval (reaper fires)
          _     <- TestClock.adjust(1.minutes)
          // Give the fiber a tick to run
          _     <- ZIO.yieldNow
          // The store.evictExpired was called by the fiber — verify workspace is gone
          exit  <- store.resolve(key).exit
        yield assert(exit)(fails(anything))
      }
    }
  )
