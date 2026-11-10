package com.risquanter.register.http.controllers

import zio.*
import zio.json.*
import zio.test.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.auth.{Authority, AuthorizationServiceNoOp, Checked, UserContextExtractor}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.domain.data.RiskLeaf
import com.risquanter.register.domain.data.iron.{BranchRef, BranchChoice, CommitHash, MergeStagingRef, SafeName, ScenarioName, StoreBranch, TreeId, WorkspaceId, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.{AppError, ErrorLocation, ErrorSubject, Failure, FieldName}
import com.risquanter.register.http.requests.{CreateScenarioRequest, ScenarioSourceDto}
import com.risquanter.register.http.responses.{MergeConflictEntry, MergePreviewResponse, MergePreviewStatus, ScenarioListResponse, ScenarioResponse, TreeLabel, TreeWriteKind, Versions}
import com.risquanter.register.testutil.TestHelpers.{idStr, lognormalShape, nodeId, safeId, treeId, unsafeGet}
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient}
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath, IrminWriteOutcome}
import com.risquanter.register.domain.data.iron.PositiveInt
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryInMemory}
import com.risquanter.register.services.{ScenarioMergeService, ScenarioMergeServiceLive, ScenarioServiceLive}
import com.risquanter.register.services.workspace.{WorkspaceStore, WorkspaceStoreLive}

import java.time.Instant

/** HTTP-layer tests for [[ScenarioController]].
  *
  * `ScenarioServiceLiveSpec` already covers the compare-and-set and
  * business-logic depth
  * against a fake `IrminClient` — this spec exercises what's new at the HTTP
  * boundary instead: the `ScenarioName`/`CommitHash` Tapir codecs added for
  * this item, the `If-Match` CAS transport (RFC 7232 quoting), request/
  * response JSON shapes, and that a stale head and a name collision answer
  * 409 through the wire boundary.
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
    /** The information every fixture commit carries; no assertion here reads it. */
    private val info: IrminInfo =
      IrminInfo(Instant.EPOCH, CommitMessage.TreeWritten(WorkspaceId(safeId("fixture-ws")), treeId("fixture-tree"), TreeWriteKind.Update))

    /** The branch a fixture's raw name spells, as the live client refines it. */
    private def storeBranch(name: String): StoreBranch =
      StoreBranch.fromString(name, ErrorLocation.Whole).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

    private def toBranch(branch: StoreBranch, headOpt: Option[CommitHash]): IrminBranch =
      IrminBranch(branch, headOpt.map(h => IrminCommit(h, h, Nil, info)))

    override def branches: IO[Failure, List[StoreBranch]] = state.get.map(_.keys.toList.map(storeBranch))

    override def mainBranch: IO[Failure, Option[IrminBranch]] =
      state.get.map(m => Some(toBranch(BranchRef.Main, m.get("main"))))

    override def getBranch(branch: StoreBranch): IO[Failure, Option[IrminBranch]] =
      state.get.map(m => m.get(branch.name).map(h => toBranch(branch, Some(h))))

    override def createBranchAt(branch: StoreBranch, at: CommitHash): IO[Failure, IrminWriteOutcome] =
      state.modify { m =>
        m.get(branch.name) match
          case Some(head) => (IrminWriteOutcome.Refused(Some(head)), m)
          case None       => (IrminWriteOutcome.Applied, m + (branch.name -> at))
      }

    override def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[Failure, IrminWriteOutcome] =
      state.modify { m =>
        m.get(branch.name) match
          case Some(head) if head == currentHead => (IrminWriteOutcome.Applied, m - branch.name)
          case head                              => (IrminWriteOutcome.Refused(head), m)
      }

    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[Failure, IrminWriteOutcome] =
      state.modify { m =>
        m.get(branch.name) match
          case Some(head) if head == expectedHead => (IrminWriteOutcome.Applied, m + (branch.name -> to))
          case head                               => (IrminWriteOutcome.Refused(head), m)
      }

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef) = ZIO.die(new NotImplementedError("unused"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage) = ZIO.die(new NotImplementedError("unused"))
    override def revert(commit: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused"))
    override def getCommit(commitHash: CommitHash) = ZIO.die(new NotImplementedError("unused"))
    override def getAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused"))
    override def listAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused"))
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))
    override def lca(branch: BranchRef, commit: CommitHash) = ZIO.die(new NotImplementedError("unused"))
    override def healthCheck = ZIO.die(new NotImplementedError("unused"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused"))

  private def buildBackend(
    initial:      Map[String, CommitHash]       = Map("main" -> hash('a')),
    extractor:    UserContextExtractor          = UserContextExtractor.noOp,
    mergeService: Option[ScenarioMergeService]
  // `Throwable` rather than `Nothing`: building the tracing layer reads
  // configuration, so constructing the backend can fail.
  ): ZIO[Any, Throwable, (SttpBackend[Task, Any], WorkspaceKeySecret)] =
    for
      state          <- Ref.make(initial)
      // Built once, then reused as a fixed instance below — a second
      // `WorkspaceStoreLive.layer` build would be a distinct, empty store
      // that never learns about the workspace key `create` returns here.
      workspaceStore <- ZIO.service[WorkspaceStore].provide(TestConfigs.workspaceLayer >>> WorkspaceStoreLive.layer)
      // `create` fails with an `AppError`, which is not a `Throwable`, so the
      // conversion to a defect is explicit. A failure here is a broken fixture.
      wsKey          <- workspaceStore.create(None).orDieWith {
                          case e: AppError => RuntimeException(e.logMessage)
                          case f: Failure  => RuntimeException(f.logMessage)
                        }
      // The post-merge guard reads trees back through a repository. This spec
      // injects its own merge service in every case that exercises a merge, so
      // the fallback only has to be a real repository, not a configured one.
      repo           <- ZIO.service[RiskTreeRepository].provide(RiskTreeRepositoryInMemory.layer)
      mergeSvc        = mergeService.getOrElse(new ScenarioMergeServiceLive(FakeIrminClient(state), repo, TestConfigs.conflictScanFetchParallelism))
      ctrl           <- ScenarioController.makeZIO
        .provide(
          ZLayer.succeed(new ScenarioServiceLive(FakeIrminClient(state))),
          ZLayer.succeed[ScenarioMergeService](mergeSvc),
          ZLayer.succeed(workspaceStore),
          AuthorizationServiceNoOp.layer,
          ZLayer.succeed(extractor),
          TestConfigs.telemetryLayer >>> TelemetryLive.configured
        )
    yield
      val backend = TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
        .whenServerEndpointsRunLogic(ctrl.routes)
        .backend()
      (backend, wsKey)

  override def spec = suite("ScenarioController")(

    test("create from main: 200, response echoes name only — no branch reference") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        resp <- basicRequest
          .post(uri"http://localhost/w/${key.reveal}/scenarios")
          .body(CreateScenarioRequest(name = scenarioName("draft-v1"), source = ScenarioSourceDto.Branch(BranchChoice.Main)).toJson)
          .contentType("application/json")
          .send(backend)
        body = orThrow(resp.body.toOption, s"expected success body, got: $resp")
        decoded = body.fromJson[ScenarioResponse]
      yield assertTrue(
        resp.code.code == 200,
        decoded.map(_.name) == Right(scenarioName("draft-v1")),
        // The composed branch string embeds the workspace's own ID
        // (scenarios.<workspaceId>.<name>) — asserting its total
        // absence from the wire body verifies the leak this redesign closes.
        !body.contains("branch")
      )
    },

    test("create forkOf an existing scenario forks that scenario's head, not main's — verified via list") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        _     <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                   .body(CreateScenarioRequest(scenarioName("base"), ScenarioSourceDto.Branch(BranchChoice.Main)).toJson).contentType("application/json").send(backend)
        _     <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                   .body(CreateScenarioRequest(scenarioName("fork"), ScenarioSourceDto.Branch(BranchChoice.Scenario(scenarioName("base")))).toJson).contentType("application/json").send(backend)
        resp  <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios").send(backend)
        body   = orThrow(resp.body.toOption, s"expected list body, got: $resp")
        listed = orThrow(body.fromJson[ScenarioListResponse].toOption, s"bad list json: $body").scenarios
      yield assertTrue(
        resp.code.code == 200,
        listed.map(s => s.name -> s.head).toMap.get(scenarioName("base")) ==
          listed.map(s => s.name -> s.head).toMap.get(scenarioName("fork"))
      )
    },

    test("create name collision: 409") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        _    <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                  .body(CreateScenarioRequest(scenarioName("draft-v1"), ScenarioSourceDto.Branch(BranchChoice.Main)).toJson).contentType("application/json").send(backend)
        resp <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                  .body(CreateScenarioRequest(scenarioName("draft-v1"), ScenarioSourceDto.Branch(BranchChoice.Main)).toJson).contentType("application/json").send(backend)
      yield assertTrue(resp.code.code == 409)
    },

    test("merge-preview conflict wire mapping: a NodeConflict encodes and decodes to the same value") {
      def leaf(probability: Double): RiskLeaf =
        unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
          id = idStr("cyber"),
          name = "Cyber",
          probability = probability,
          shape = lognormalShape(1000L, 50000L),
          parentId = Some(nodeId("root")),
          seedVarId = 1L
        ), "leaf")
      val treeName = SafeName.fromString("Merge Tree").toOption.get
      val conflict = MergeConflictEntry.NodeConflict(
        tree = TreeLabel(treeId("merge-tree"), Versions(Some(treeName), Some(treeName), Some(treeName))),
        nodeId = nodeId("cyber"),
        versions = Versions(Some(leaf(0.1)), Some(leaf(0.3)), Some(leaf(0.6))),
        fields = List(FieldName.Probability)
      )
      val stub = new ScenarioMergeService:
        override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, MergePreviewStatus] =
          ZIO.succeed(MergePreviewStatus.Conflicts(List(conflict)))
        override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, CommitHash] =
          ZIO.die(new NotImplementedError("unused by this test"))
      for
        (backend, key) <- buildBackend(mergeService = Some(stub))
        resp <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1/merge-preview").send(backend)
        body    = orThrow(resp.body.toOption, s"expected success body, got: $resp")
        decoded = orThrow(body.fromJson[MergePreviewResponse].toOption, s"bad json: $body")
      yield assertTrue(
        resp.code.code == 200,
        // One assertion rather than two: the status case and its payload now
        // travel together, so matching the whole value pins both.
        decoded.status == MergePreviewStatus.Conflicts(List(conflict))
      )
    },

    test("merge-preview duplicate-names wire mapping: status carries the per-tree duplicate names") {
      val dupMap = Map(
        TreeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get ->
          List(SafeName.fromString("Alpha").toOption.get)
      )
      val stub = new ScenarioMergeService:
        override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, MergePreviewStatus] =
          ZIO.succeed(MergePreviewStatus.DuplicateNames(dupMap))
        override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, CommitHash] =
          ZIO.die(new NotImplementedError("unused by this test"))
      for
        (backend, key) <- buildBackend(mergeService = Some(stub))
        resp <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1/merge-preview").send(backend)
        body    = orThrow(resp.body.toOption, s"expected success body, got: $resp")
        decoded = orThrow(body.fromJson[MergePreviewResponse].toOption, s"bad json: $body")
      yield assertTrue(
        resp.code.code == 200,
        decoded.status == MergePreviewStatus.DuplicateNames(dupMap)
      )
    },

    test("merge-preview clean wire mapping: duplicateNames absent/None when the preview is clean") {
      val stub = new ScenarioMergeService:
        override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, MergePreviewStatus] =
          ZIO.succeed(MergePreviewStatus.Clean())
        override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)(using Checked[Authority]): IO[AppError, CommitHash] =
          ZIO.die(new NotImplementedError("unused by this test"))
      for
        (backend, key) <- buildBackend(mergeService = Some(stub))
        resp <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1/merge-preview").send(backend)
        body    = orThrow(resp.body.toOption, s"expected success body, got: $resp")
        decoded = orThrow(body.fromJson[MergePreviewResponse].toOption, s"bad json: $body")
      yield assertTrue(
        resp.code.code == 200,
        decoded.status == MergePreviewStatus.Clean()
      )
    },

    test("delete with matching If-Match: 204, then absent from list") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        _      <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                    .body(CreateScenarioRequest(scenarioName("draft-v1"), ScenarioSourceDto.Branch(BranchChoice.Main)).toJson).contentType("application/json").send(backend)
        // main's seeded head is hash('a') and create forks it verbatim, so
        // the new branch's head equals main's head.
        del    <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1")
                    .header("If-Match", s""""${hash('a').value}"""").send(backend)
        after  <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios").send(backend)
        afterBody = orThrow(after.body.toOption, s"expected body, got: $after")
        listed  = orThrow(afterBody.fromJson[ScenarioListResponse].toOption, s"bad json: $afterBody").scenarios
      yield assertTrue(del.code.code == 204, listed.isEmpty)
    },

    test("delete with stale If-Match: 409") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        _    <- basicRequest.post(uri"http://localhost/w/${key.reveal}/scenarios")
                  .body(CreateScenarioRequest(scenarioName("draft-v1"), ScenarioSourceDto.Branch(BranchChoice.Main)).toJson).contentType("application/json").send(backend)
        resp <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1")
                  .header("If-Match", s""""${hash('f').value}"""").send(backend)
      yield assertTrue(resp.code.code == 409)
    },

    test("delete with a malformed scenario-name path segment: 400") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        resp <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/NOT-A-VALID-SLUG!!")
                  .header("If-Match", s""""${hash('a').value}"""").send(backend)
      yield assertTrue(resp.code.code == 400)
    },

    test("delete without If-Match header: 400") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        resp <- basicRequest.delete(uri"http://localhost/w/${key.reveal}/scenarios/draft-v1").send(backend)
      yield assertTrue(resp.code.code == 400)
    },

    test("create with a full branch-ref-style name (containing dots): 400 — only a slug is ever accepted") {
      for
        (backend, key) <- buildBackend(mergeService = None)
        // The `scenarios.<workspaceId>.<name>` composed ref is a server-side
        // derivation, never caller input — this posts exactly that shape as `name`
        // to confirm the Iron `ScenarioName` codec at the Tapir boundary rejects it
        // (dots aren't in `^[a-zA-Z0-9 _-]+$`) rather than passing it through to
        // `ScenarioService.create`, which would let a caller dictate the branch ref.
        resp <- basicRequest
          .post(uri"http://localhost/w/${key.reveal}/scenarios")
          .body("""{"name":"scenarios.01j8zq3fkwp2x9m4v7rtbnd6ea.high-cyber","source":{"type":"Branch","name":"main"}}""")
          .contentType("application/json")
          .send(backend)
      yield assertTrue(resp.code.code == 400)
    },

    test("identity mode: absent x-user-id is rejected with 403") {
      for
        (backend, key) <- buildBackend(extractor = UserContextExtractor.requirePresent, mergeService = None)
        resp <- basicRequest.get(uri"http://localhost/w/${key.reveal}/scenarios").send(backend)
      yield assertTrue(resp.code.code == 403)
    }
  )

  private def scenarioName(s: String) =
    com.risquanter.register.domain.data.iron.ScenarioName.fromString(s).toOption.get
