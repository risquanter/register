package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration

import com.risquanter.register.configs.{WorkspaceConfig, TestConfigs}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.domain.data.iron.TreeId
import com.risquanter.register.domain.data.{RiskTree, LECCurveResponse, LECPoint, LECNodeCurve}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.util.IdGenerators

object WorkspaceReaperSpec extends ZIOSpecDefault:

  // ── Test helpers ────────────────────────────────────────────────────

  /** Build a RiskTreeService stub where only `delete` is customizable.
    * All other methods die immediately to catch unintended calls.
    */
  private def makeStub(onDelete: TreeId => Task[RiskTree]): RiskTreeService = new RiskTreeService:
    def create(req: RiskTreeDefinitionRequest): Task[RiskTree]                                          = ZIO.die(new UnsupportedOperationException)
    def update(id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree]                                  = ZIO.die(new UnsupportedOperationException)
    def delete(id: TreeId): Task[RiskTree]                                                              = onDelete(id)
    def getAll: Task[List[RiskTree]]                                                                    = ZIO.die(new UnsupportedOperationException)
    def getById(id: TreeId): Task[Option[RiskTree]]                                                     = ZIO.die(new UnsupportedOperationException)
    def getLECCurve(treeId: TreeId, nodeId: NodeId, includeProvenance: Boolean): Task[LECCurveResponse]  = ZIO.die(new UnsupportedOperationException)
    def probOfExceedance(treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean): Task[BigDecimal] = ZIO.die(new UnsupportedOperationException)
    def getLECCurvesMulti(treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean): Task[Map[NodeId, LECNodeCurve]] = ZIO.die(new UnsupportedOperationException)
    def getLECChart(treeId: TreeId, nodeIds: Set[NodeId]): Task[String]                                 = ZIO.die(new UnsupportedOperationException)

  /** No-op stub: `delete` always fails (simulates already-deleted tree). */
  private val noOpTreeServiceLayer: ULayer[RiskTreeService] =
    ZLayer.succeed(makeStub(_ => ZIO.fail(new NoSuchElementException("Tree not found"))))

  /** Tracking stub: records deleted tree IDs in a Ref for assertions.
    * `delete` appends the ID then fails (tree doesn't really exist) — but the
    * reaper uses `.ignore`, so the failure is swallowed. The ref captures the call.
    */
  private def trackingTreeService: UIO[(Ref[List[TreeId]], RiskTreeService)] =
    Ref.make(List.empty[TreeId]).map { ref =>
      val svc = makeStub(id => ref.update(_ :+ id) *> ZIO.fail(new NoSuchElementException(s"Tree $id not found")))
      (ref, svc)
    }

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
    },

    // ========================================
    // Cascade deletion (orphan bug fix)
    // ========================================

    test("reaper cascade-deletes trees when workspace expires") {
      ZIO.scoped {
        for
          store          <- WorkspaceStoreLive.make(reaperTestConfig)
          (deletedRef, svc) <- trackingTreeService
          key            <- store.create()
          treeId         <- IdGenerators.nextTreeId
          _              <- store.addTree(key, treeId)
          _              <- WorkspaceReaper.layer.build
                              .provide(
                                ZLayer.succeed(reaperTestConfig),
                                ZLayer.succeed(store: WorkspaceStore),
                                ZLayer.succeed(svc: RiskTreeService),
                                ZLayer.succeed(Scope.global)
                              )
          // Advance past TTL + reaper interval
          _              <- TestClock.adjust(4.minutes)
          deleted        <- deletedRef.get
        yield assertTrue(deleted.contains(treeId))
      }
    },

    test("reaper cascade-deletes trees across multiple expired workspaces") {
      ZIO.scoped {
        for
          store          <- WorkspaceStoreLive.make(reaperTestConfig)
          (deletedRef, svc) <- trackingTreeService
          // Workspace 1 with 2 trees
          key1           <- store.create()
          ids1           <- IdGenerators.batch(2).map(_.map(TreeId(_)))
          _              <- ZIO.foreachDiscard(ids1)(store.addTree(key1, _))
          // Workspace 2 with 1 tree
          key2           <- store.create()
          id2            <- IdGenerators.nextTreeId
          _              <- store.addTree(key2, id2)
          _              <- WorkspaceReaper.layer.build
                              .provide(
                                ZLayer.succeed(reaperTestConfig),
                                ZLayer.succeed(store: WorkspaceStore),
                                ZLayer.succeed(svc: RiskTreeService),
                                ZLayer.succeed(Scope.global)
                              )
          _              <- TestClock.adjust(4.minutes)
          deleted        <- deletedRef.get
        yield assertTrue(
          deleted.toSet == (ids1 :+ id2).toSet,
          deleted.size == 3
        )
      }
    },

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
                        ZLayer.succeed(Scope.global)
                      )
          _      <- TestClock.adjust(4.minutes)
          exit   <- store.resolve(key).exit
        yield assert(exit)(fails(anything))
      }
    }
  )
