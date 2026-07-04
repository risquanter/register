package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}
import com.risquanter.register.domain.data.{RiskTree, LECPoint, LECNodeCurve}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.auth.{BootstrapProvisionerNoOp, Checked, Permission, TestChecked}

object WorkspaceReaperSpec extends ZIOSpecDefault:
  // Service-level test: WorkspaceReaper calls protected service methods as a background orchestrator.
  // TestChecked provides the Checked[Permission] proof for direct stub invocations.
  private given Checked[Permission] = TestChecked.value

  // ── Test helpers ────────────────────────────────────────────────────

  /** Build a RiskTreeService stub where only `delete` is customizable.
    * All other methods die immediately to catch unintended calls.
    */
  private def makeStub(onDelete: (WorkspaceId, TreeId) => Task[RiskTree]): RiskTreeService = new RiskTreeService:
    def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest)(using Checked[Permission]): Task[RiskTree]                                          = ZIO.die(new UnsupportedOperationException)
    def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest)(using Checked[Permission]): Task[RiskTree]                                  = ZIO.die(new UnsupportedOperationException)
    def delete(wsId: WorkspaceId, id: TreeId)(using Checked[Permission]): Task[RiskTree]                                                              = onDelete(wsId, id)
    def getById(wsId: WorkspaceId, id: TreeId)(using Checked[Permission]): Task[Option[RiskTree]]                                                     = ZIO.die(new UnsupportedOperationException)
    def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean): Task[Double] = ZIO.die(new UnsupportedOperationException)
    def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean): Task[Map[NodeId, LECNodeCurve]] = ZIO.die(new UnsupportedOperationException)

  /** No-op stub: `delete` always fails (simulates already-deleted tree). */
  private val noOpTreeServiceLayer: ULayer[RiskTreeService] =
    ZLayer.succeed(makeStub((_, _) => ZIO.fail(new NoSuchElementException("Tree not found"))))

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
          svc               = makeStub((_, id) =>
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
          svc               = makeStub((_, id) =>
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
                        ZLayer.succeed(BootstrapProvisionerNoOp),
                        ZLayer.succeed(Scope.global)
                      )
          _      <- ZIO.yieldNow
          _      <- TestClock.adjust(4.minutes)
          exit   <- store.resolve(key).exit
        yield assert(exit)(fails(anything))
      }
    }
  )
