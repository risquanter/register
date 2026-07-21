package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}
import com.risquanter.register.services.{CascadeTestStubs, RiskTreeService, ScenarioService, ScenarioServiceNotSupported, ScenarioSummary}
import com.risquanter.register.domain.data.iron.{TreeId, ScenarioName, CommitHash}
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.auth.{BootstrapProvisionerNoOp, Checked, Permission, TestChecked}

object WorkspaceReaperSpec extends ZIOSpecDefault:
  // Service-level test: store.addTree() below requires Checked[Permission] in scope.
  // TestChecked provides the proof for this direct stub invocation (never in src/main).
  private given Checked[Permission] = TestChecked.value

  // ── Test helpers ────────────────────────────────────────────────────
  // Tree/Scenario service test doubles are shared with WorkspaceLifecycleControllerCascadeSpec
  // via CascadeTestStubs — single source of truth for the stub shape.

  /** No-op stub: `delete` always fails (simulates already-deleted tree). */
  private val noOpTreeServiceLayer: ULayer[RiskTreeService] =
    ZLayer.succeed(CascadeTestStubs.noOpRiskTreeService)

  /** No-op stub: matches the in-memory-backend deployment shape (`list`/`delete`
    * both fail with `ScenariosNotSupported`) — the reaper swallows this the same
    * as any other best-effort cascade failure. Used by every test that isn't
    * specifically exercising scenario cascade-delete.
    */
  private val noOpScenarioServiceLayer: ULayer[ScenarioService] = ScenarioServiceNotSupported.layer

  /** Shared config for all fiber/cascade tests: short TTL, short reaper interval. */
  private val reaperTestConfig = WorkspaceConfig(
    ttl = Duration.ofMinutes(2),
    idleTimeout = Duration.ofMinutes(2),
    reaperInterval = Duration.ofMinutes(1)
  )

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
            noOpTreeServiceLayer,
            noOpScenarioServiceLayer,
            ZLayer.succeed(BootstrapProvisionerNoOp),
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
            noOpTreeServiceLayer,
            noOpScenarioServiceLayer,
            ZLayer.succeed(BootstrapProvisionerNoOp),
                        ZLayer.succeed(Scope.global)
          )
      ).as(assertTrue(true))
    },

    // ========================================
    // Fiber integration (TestClock-driven)
    // ========================================

    test("reaper fiber evicts workspace after interval elapses") {
      ZIO.scoped {
        for
          store <- WorkspaceStoreLive.make(reaperTestConfig)
          _     <- WorkspaceReaper.layer.build
                     .provide(
                       ZLayer.succeed(reaperTestConfig),
                       ZLayer.succeed(store: WorkspaceStore),
                       noOpTreeServiceLayer,
                       noOpScenarioServiceLayer,
                       ZLayer.succeed(BootstrapProvisionerNoOp),
                        ZLayer.succeed(Scope.global)
                     )
          key   <- store.create()
          // yield so the reaper fiber can run until it blocks on ZIO.sleep
          _     <- ZIO.yieldNow
          _     <- TestClock.adjust(4.minutes)
          exit  <- store.resolve(key).exit
        yield assert(exit)(fails(anything))
      }
    },

    // ========================================
    // Cascade deletion (orphan bug fix)
    // ========================================

    test("reaper cascade-deletes trees when workspace expires") {
      ZIO.scoped {
        for
          store            <- WorkspaceStoreLive.make(reaperTestConfig)
          deleted          <- Promise.make[Nothing, Unit]
          deletedRef       <- Ref.make(List.empty[TreeId])
          svc               = CascadeTestStubs.riskTreeService((_, id) =>
                               deletedRef.update(_ :+ id) *>
                               deleted.succeed(()).unit *>
                               ZIO.fail(new NoSuchElementException(s"Tree $id not found")))
          key              <- store.create()
          treeId           <- IdGenerators.nextTreeId
          _                <- store.addTree(key, treeId)
          _                <- WorkspaceReaper.layer.build
                               .provide(
                                 ZLayer.succeed(reaperTestConfig),
                                 ZLayer.succeed(store: WorkspaceStore),
                                 ZLayer.succeed(svc: RiskTreeService),
                                 noOpScenarioServiceLayer,
                                 ZLayer.succeed(BootstrapProvisionerNoOp),
                        ZLayer.succeed(Scope.global)
                               )
          // yield so the reaper fiber reaches ZIO.sleep, then advance
          _                <- ZIO.yieldNow
          _                <- TestClock.adjust(4.minutes)
          _                <- deleted.await
          result           <- deletedRef.get
        yield assertTrue(result.contains(treeId))
      }
    } @@ TestAspect.withLiveRandom,

    test("reaper cascade-deletes trees across multiple expired workspaces") {
      ZIO.scoped {
        for
          store            <- WorkspaceStoreLive.make(reaperTestConfig)
          // Latch fires after the 3rd cascade delete so deletedRef.get is
          // always called after the reaper fiber has finished its cycle.
          allDeleted       <- Promise.make[Nothing, Unit]
          deletedRef       <- Ref.make(List.empty[TreeId])
          svc               = CascadeTestStubs.riskTreeService((_, id) =>
                               deletedRef.updateAndGet(_ :+ id).flatMap { updated =>
                                 allDeleted.succeed(()).when(updated.size >= 3).unit
                               } *> ZIO.fail(new NoSuchElementException(s"Tree $id not found")))
          // Workspace 1 with 2 trees
          key1             <- store.create()
          ids1             <- IdGenerators.batch(2).map(_.map(TreeId(_)))
          _                <- ZIO.foreachDiscard(ids1)(store.addTree(key1, _))
          // Workspace 2 with 1 tree
          key2             <- store.create()
          id2              <- IdGenerators.nextTreeId
          _                <- store.addTree(key2, id2)
          _                <- WorkspaceReaper.layer.build
                               .provide(
                                 ZLayer.succeed(reaperTestConfig),
                                 ZLayer.succeed(store: WorkspaceStore),
                                 ZLayer.succeed(svc: RiskTreeService),
                                 noOpScenarioServiceLayer,
                                 ZLayer.succeed(BootstrapProvisionerNoOp),
                        ZLayer.succeed(Scope.global)
                               )
          _                <- ZIO.yieldNow
          _                <- TestClock.adjust(4.minutes)
          _                <- allDeleted.await
          deleted          <- deletedRef.get
        yield assertTrue(
          deleted.toSet == (ids1 :+ id2).toSet,
          deleted.size == 3
        )
      }
    } @@ TestAspect.withLiveRandom,

    test("reaper cascade tolerates already-deleted trees without crashing") {
      // The noOp stub's `delete` always fails — simulates trees that were
      // manually deleted before the workspace expired. The reaper uses `.ignore`,
      // so it must not crash. The workspace should still be evicted.
      ZIO.scoped {
        for
          store  <- WorkspaceStoreLive.make(reaperTestConfig)
          key    <- store.create()
          treeId <- IdGenerators.nextTreeId
          _      <- store.addTree(key, treeId)
          _      <- WorkspaceReaper.layer.build
                      .provide(
                        ZLayer.succeed(reaperTestConfig),
                        ZLayer.succeed(store: WorkspaceStore),
                        noOpTreeServiceLayer,  // delete always fails
                        noOpScenarioServiceLayer,
                        ZLayer.succeed(BootstrapProvisionerNoOp),
                        ZLayer.succeed(Scope.global)
                      )
          _      <- ZIO.yieldNow
          _      <- TestClock.adjust(4.minutes)
          exit   <- store.resolve(key).exit
        yield assert(exit)(fails(anything))
      }
    },

    test("reaper cascade-deletes scenario branches when workspace expires") {
      ZIO.scoped {
        for
          store        <- WorkspaceStoreLive.make(reaperTestConfig)
          deleted      <- Promise.make[Nothing, Unit]
          deletedRef   <- Ref.make(List.empty[ScenarioName.ScenarioName])
          head          = CommitHash.fromString("a" * 40).toOption.get
          scenarioName  = ScenarioName.fromString("stress-2026").toOption.get
          scenarioSvc   = CascadeTestStubs.scenarioService(
                            onList = _ => ZIO.succeed(List(ScenarioSummary(scenarioName, head))),
                            onDelete = (_, name) =>
                              deletedRef.update(_ :+ name) *> deleted.succeed(()).unit
                          )
          key          <- store.create()
          _            <- WorkspaceReaper.layer.build
                            .provide(
                              ZLayer.succeed(reaperTestConfig),
                              ZLayer.succeed(store: WorkspaceStore),
                              noOpTreeServiceLayer,
                              ZLayer.succeed(scenarioSvc: ScenarioService),
                              ZLayer.succeed(BootstrapProvisionerNoOp),
                              ZLayer.succeed(Scope.global)
                            )
          _            <- ZIO.yieldNow
          _            <- TestClock.adjust(4.minutes)
          _            <- deleted.await
          result       <- deletedRef.get
        yield assertTrue(result.contains(scenarioName))
      }
    } @@ TestAspect.withLiveRandom
  )
