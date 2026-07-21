package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import java.time.Duration
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.auth.{AuthorizationServiceNoOp, BootstrapProvisionerNoOp, Checked, Permission, TestChecked, UserContextExtractor}
import com.risquanter.register.configs.{TestConfigs, WorkspaceConfig}
import com.risquanter.register.domain.data.iron.{CommitHash, ScenarioName, TreeId}
import com.risquanter.register.services.{CascadeTestStubs, RiskTreeService, ScenarioService, ScenarioSummary}
import com.risquanter.register.services.workspace.{RateLimiterLive, WorkspaceStore, WorkspaceStoreLive}
import com.risquanter.register.util.IdGenerators

/** HTTP-layer tests for [[WorkspaceLifecycleController]]'s cascade-delete behaviour
  * on `deleteWorkspace` (`DELETE /w/{key}`) and `evictExpired`
  * (`DELETE /admin/workspaces/expired`) — both fixed 2026-07-21 to cascade
  * scenario branches; `evictExpired` gained tree cascade too, which it never had
  * (it previously only evicted the workspace *record*, leaving every tree and
  * scenario branch orphaned).
  *
  * `WorkspaceStoreSpec`/`WorkspaceStorePostgresSpec` cover the underlying
  * `WorkspaceStore.evictExpired`/`delete` primitives; this spec covers the
  * controller-level orchestration layered on top (the part that was missing).
  */
object WorkspaceLifecycleControllerCascadeSpec extends ZIOSpecDefault:

  private given MonadError[Task] = new RIOMonadError[Any]
  // store.addTree() below requires Checked[Permission] in scope.
  // TestChecked provides the proof for this direct stub invocation (never in src/main).
  private given Checked[Permission] = TestChecked.value

  /** Short TTL so `evictExpired`'s test can force real expiry via `TestClock`. */
  private val shortTtlConfig = WorkspaceConfig(
    ttl = Duration.ofMinutes(2),
    idleTimeout = Duration.ofMinutes(2),
    reaperInterval = Duration.ofMinutes(1)
  )

  private def buildBackend(
    store:           WorkspaceStore,
    riskTreeService: RiskTreeService,
    scenarioService: ScenarioService
  ): ZIO[Any, Nothing, SttpBackend[Task, Any]] =
    WorkspaceLifecycleController.makeZIO
      .provide(
        ZLayer.succeed(riskTreeService),
        ZLayer.succeed(store),
        TestConfigs.workspaceLayer,
        RateLimiterLive.layer,
        ZLayer.succeed(UserContextExtractor.noOp),
        AuthorizationServiceNoOp.layer,
        ZLayer.succeed(BootstrapProvisionerNoOp),
        ZLayer.succeed(scenarioService)
      )
      .map { ctrl =>
        TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
          .whenServerEndpointsRunLogic(ctrl.routes)
          .backend()
      }

  private def scenarioHead: CommitHash = CommitHash.fromString("a" * 40).toOption.get
  private def scenarioName(s: String): ScenarioName.ScenarioName = ScenarioName.fromString(s).toOption.get

  override def spec = suite("WorkspaceLifecycleController — cascade delete")(

    test("deleteWorkspace cascades both trees and scenario branches") {
      for
        store           <- WorkspaceStoreLive.make(TestConfigs.workspace)
        treeDeleted     <- Ref.make(List.empty[TreeId])
        scenarioDeleted <- Ref.make(List.empty[ScenarioName.ScenarioName])
        name             = scenarioName("stress-2026")
        treeSvc          = CascadeTestStubs.riskTreeService((_, id) =>
                              treeDeleted.update(_ :+ id) *>
                              ZIO.fail(new NoSuchElementException("gone")))
        scenarioSvc      = CascadeTestStubs.scenarioService(
                              onList = _ => ZIO.succeed(List(ScenarioSummary(name, scenarioHead))),
                              onDelete = (_, n, _) => scenarioDeleted.update(_ :+ n)
                            )
        key             <- store.create()
        treeId          <- IdGenerators.nextTreeId
        _               <- store.addTree(key, treeId)
        backend         <- buildBackend(store, treeSvc, scenarioSvc)
        resp            <- basicRequest.delete(uri"http://localhost/w/${key.reveal}").send(backend)
        treesGone       <- treeDeleted.get
        scenariosGone   <- scenarioDeleted.get
      yield assertTrue(
        resp.code.code == 204,
        treesGone.contains(treeId),
        scenariosGone.contains(name)
      )
    },

    test("evictExpired cascades both trees and scenario branches for every evicted workspace") {
      for
        store           <- WorkspaceStoreLive.make(shortTtlConfig)
        treeDeleted     <- Ref.make(List.empty[TreeId])
        scenarioDeleted <- Ref.make(List.empty[ScenarioName.ScenarioName])
        name             = scenarioName("stress-2026")
        treeSvc          = CascadeTestStubs.riskTreeService((_, id) =>
                              treeDeleted.update(_ :+ id) *>
                              ZIO.fail(new NoSuchElementException("gone")))
        scenarioSvc      = CascadeTestStubs.scenarioService(
                              onList = _ => ZIO.succeed(List(ScenarioSummary(name, scenarioHead))),
                              onDelete = (_, n, _) => scenarioDeleted.update(_ :+ n)
                            )
        key             <- store.create()
        treeId          <- IdGenerators.nextTreeId
        _               <- store.addTree(key, treeId)
        _               <- TestClock.adjust(4.minutes)
        backend         <- buildBackend(store, treeSvc, scenarioSvc)
        resp            <- basicRequest.delete(uri"http://localhost/admin/workspaces/expired").send(backend)
        treesGone       <- treeDeleted.get
        scenariosGone   <- scenarioDeleted.get
      yield assertTrue(
        resp.code.code == 200,
        treesGone.contains(treeId),
        scenariosGone.contains(name)
      )
    }
  )
