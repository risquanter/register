package com.risquanter.register.http.controllers

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.auth.{AuthorizationServiceNoOp, UserContextExtractor}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.IrminError
import com.risquanter.register.http.requests.CreateScenarioRequest
import com.risquanter.register.http.responses.{ScenarioResponse, ScenarioSummaryResponse}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath}
import com.risquanter.register.domain.data.iron.PositiveInt
import com.risquanter.register.services.{ScenarioServiceLive, ScenarioMergeServiceLive}
import com.risquanter.register.services.workspace.{WorkspaceStore, WorkspaceStoreLive}

/** HTTP-layer tests for [[ScenarioController]] (milestone-2b Phase B, item 4).
  *
  * `ScenarioServiceLiveSpec` already covers the CAS/business-logic depth
  * against a fake `IrminClient` — this spec exercises what's new at the HTTP
  * boundary instead: the `ScenarioName`/`CommitHash` Tapir codecs added for
  * this item, the `If-Match` CAS transport (RFC 7232 quoting), request/
  * response JSON shapes, and that `ScenarioHeadStale`/`DataConflict` surface
  * with the right status codes through `ErrorResponse`.
  */
object ScenarioControllerSpec extends ZIOSpecDefault:

  private given MonadError[Task] = new RIOMonadError[Any]

  private def hash(fill: Char): CommitHash = CommitHash.fromString(fill.toString * 40).toOption.get

  private def orThrow[A](opt: Option[A], msg: => String): A =
    opt.getOrElse(throw new NoSuchElementException(msg))

  /** Same minimal fake as `ScenarioServiceLiveSpec` — only the methods
    * `ScenarioServiceLive` actually calls are implemented.
    */
  private final class FakeIrminClient(state: Ref[Map[String, CommitHash]]) extends IrminClient:
    private def toBranch(name: String, headOpt: Option[CommitHash]): IrminBranch =
      IrminBranch(name, headOpt.map(h => IrminCommit(h.value, "", Nil, IrminInfo("2026-01-01T00:00:00Z", "test", "test"))))

    override def branches: IO[IrminError, List[String]] = state.get.map(_.keys.toList)

    override def mainBranch: IO[IrminError, Option[IrminBranch]] =
      state.get.map(m => Some(toBranch("main", m.get("main"))))

    override def getBranch(branch: BranchRef): IO[IrminError, Option[IrminBranch]] =
      state.get.map(m => m.get(branch.toBranchRef).map(h => toBranch(branch.toBranchRef, Some(h))))

    override def createBranchAt(branch: BranchRef, at: CommitHash): IO[IrminError, Unit] =
      state.modify { m =>
        if m.contains(branch.toBranchRef) then (false, m)
        else (true, m + (branch.toBranchRef -> at))
      }.flatMap(applied => if applied then ZIO.unit else ZIO.fail(com.risquanter.register.domain.errors.BranchAlreadyExists(branch)))

    override def deleteBranch(branch: BranchRef, currentHead: CommitHash): IO[IrminError, Unit] =
      state.modify { m =>
        if m.get(branch.toBranchRef).contains(currentHead) then (true, m - branch.toBranchRef)
        else (false, m)
      }.flatMap(applied => if applied then ZIO.unit else ZIO.fail(com.risquanter.register.domain.errors.BranchHeadStale(branch, currentHead)))

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def mergeBranch(from: BranchRef, into: BranchRef, message: String) = ZIO.die(new NotImplementedError("unused"))
    override def revert(commit: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused"))
    override def getCommit(commitHash: CommitHash) = ZIO.die(new NotImplementedError("unused"))
    override def getAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused"))
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def lca(branch: BranchRef, commit: CommitHash) = ZIO.die(new NotImplementedError("unused"))
    override def healthCheck = ZIO.die(new NotImplementedError("unused"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))

  private def buildBackend(
    initial:   Map[String, CommitHash] = Map("main" -> hash('a')),
    extractor: UserContextExtractor    = UserContextExtractor.noOp
  ): ZIO[Any, Nothing, (SttpBackend[Task, Any], WorkspaceKeySecret)] =
    for
      state          <- Ref.make(initial)
      // Built once, then reused as a fixed instance below — a second
      // `WorkspaceStoreLive.layer` build would be a distinct, empty store
      // that never learns about the workspace key `create` returns here.
      workspaceStore <- ZIO.service[WorkspaceStore].provide(TestConfigs.workspaceLayer >>> WorkspaceStoreLive.layer)
      wsKey          <- workspaceStore.create(None).orDie
      ctrl           <- ScenarioController.makeZIO
        .provide(
          ZLayer.succeed(new ScenarioServiceLive(FakeIrminClient(state))),
          ZLayer.succeed(new ScenarioMergeServiceLive(FakeIrminClient(state))),
          ZLayer.succeed(workspaceStore),
          AuthorizationServiceNoOp.layer,
          ZLayer.succeed(extractor)
        )
    yield
      val backend = TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
        .whenServerEndpointsRunLogic(ctrl.routes)
        .backend()
      (backend, wsKey)

  override def spec = suite("ScenarioController")(

    test("create from main: 200, response echoes name only — no branch reference (2026-07-21 redesign)") {
      for
        (backend, key) <- buildBackend()
        resp <- basicRequest
          .post(uri"http://localhost/w/${key.reveal}/scenarios")
          .body(CreateScenarioRequest(name = scenarioName("draft-v1"), forkOf = None).toJson)
          .contentType("application/json")
          .send(backend)
        body = orThrow(resp.body.toOption, s"expected success body, got: $resp")
        decoded = body.fromJson[ScenarioResponse]
      yield assertTrue(
        resp.code.code == 200,
        decoded.map(_.name) == Right(scenarioName("draft-v1")),
        // The composed branch string embeds the workspace's own ID
        // (scenarios.<workspaceId>.<name>, DD-5) — asserting its total
        // absence from the wire body verifies the leak this redesign closes.
        !body.contains("branch")
      )
    },

    test("create forkOf an existing scenario forks that scenario's head, not main's — verified via list") {
      for
        (backend, key) <- buildBackend()
        _     <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                   .body(CreateScenarioRequest(scenarioName("base"), None).toJson).contentType("application/json").send(backend)
        _     <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                   .body(CreateScenarioRequest(scenarioName("fork"), Some(scenarioName("base"))).toJson).contentType("application/json").send(backend)
        resp  <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios").send(backend)
        body   = orThrow(resp.body.toOption, s"expected list body, got: $resp")
        listed = orThrow(body.fromJson[List[ScenarioSummaryResponse]].toOption, s"bad list json: $body")
      yield assertTrue(
        resp.code.code == 200,
        listed.map(s => s.name -> s.head).toMap.get(scenarioName("base")) ==
          listed.map(s => s.name -> s.head).toMap.get(scenarioName("fork"))
      )
    },

    test("create name collision: 409") {
      for
        (backend, key) <- buildBackend()
        _    <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                  .body(CreateScenarioRequest(scenarioName("draft-v1"), None).toJson).contentType("application/json").send(backend)
        resp <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                  .body(CreateScenarioRequest(scenarioName("draft-v1"), None).toJson).contentType("application/json").send(backend)
      yield assertTrue(resp.code.code == 409)
    },

    test("delete with matching If-Match: 204, then absent from list") {
      for
        (backend, key) <- buildBackend()
        _      <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                    .body(CreateScenarioRequest(scenarioName("draft-v1"), None).toJson).contentType("application/json").send(backend)
        // main's seeded head is hash('a') and create forks it verbatim (A9), so
        // the new branch's head equals main's head.
        del    <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1")
                    .header("If-Match", s""""${hash('a').value}"""").send(backend)
        after  <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios").send(backend)
        afterBody = orThrow(after.body.toOption, s"expected body, got: $after")
        listed  = orThrow(afterBody.fromJson[List[ScenarioSummaryResponse]].toOption, s"bad json: $afterBody")
      yield assertTrue(del.code.code == 204, listed.isEmpty)
    },

    test("delete with stale If-Match: 409") {
      for
        (backend, key) <- buildBackend()
        _    <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                  .body(CreateScenarioRequest(scenarioName("draft-v1"), None).toJson).contentType("application/json").send(backend)
        resp <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1")
                  .header("If-Match", s""""${hash('f').value}"""").send(backend)
      yield assertTrue(resp.code.code == 409)
    },

    test("delete with a malformed scenario-name path segment: 400") {
      for
        (backend, key) <- buildBackend()
        resp <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/NOT-A-VALID-SLUG!!")
                  .header("If-Match", s""""${hash('a').value}"""").send(backend)
      yield assertTrue(resp.code.code == 400)
    },

    test("delete without If-Match header: 400") {
      for
        (backend, key) <- buildBackend()
        resp <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1").send(backend)
      yield assertTrue(resp.code.code == 400)
    },

    test("create with a full branch-ref-style name (containing dots): 400 — DD-11 invariant, only a slug is ever accepted") {
      for
        (backend, key) <- buildBackend()
        // The `scenarios.<workspaceId>.<name>` composed ref (DD-5) is a server-side
        // derivation, never caller input — this posts exactly that shape as `name`
        // to confirm the Iron `ScenarioName` codec at the Tapir boundary rejects it
        // (dots aren't in `^[a-zA-Z0-9 _-]+$`) rather than passing it through to
        // `ScenarioService.create`, which would let a caller dictate the branch ref.
        resp <- basicRequest
          .post(uri"http://localhost/w/${key.reveal}/scenarios")
          .body("""{"name":"scenarios.01j8zq3fkwp2x9m4v7rtbnd6ea.high-cyber","forkOf":null}""")
          .contentType("application/json")
          .send(backend)
      yield assertTrue(resp.code.code == 400)
    },

    test("identity mode: absent x-user-id is rejected with 403") {
      for
        (backend, key) <- buildBackend(extractor = UserContextExtractor.requirePresent)
        resp <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios").send(backend)
      yield assertTrue(resp.code.code == 403)
    }
  )

  private def scenarioName(s: String) =
    com.risquanter.register.domain.data.iron.ScenarioName.fromString(s).toOption.get
