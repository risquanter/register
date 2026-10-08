package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*

import io.github.iltotore.iron.*
import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.{RiskLeaf, RiskNode, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, NodeId, PositiveInt, Revision, SafeName, StoreBranch, TreeId}
import com.risquanter.register.domain.errors.{BranchAlreadyExists, BranchHeadStale, IrminError, IrminGraphQLError, IrminMergeConflict, MergeAlreadyRunning, MergeConflict, MergeTargetMoved, RepositoryFailure, TreeLoadFailure}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.testutil.TestHelpers.{safeId, unsafeGet}

/** Unit tests for `ScenarioMergeServiceLive` with no Docker or live Irmin.
  *
  * Three things are pinned here. The error mapping around
  * `IrminClient.mergeBranch`: the patched Irmin backend refuses a conflicting
  * merge as `IrminMergeConflict` (pinned live by `IrminMergeSemanticsSpec`) and
  * the service translates that, while unrelated Irmin errors pass through. The
  * pre-merge name scan, which predicts the merged node set from the same three
  * values the conflict check reads. And the post-merge guard, which reads every
  * tree back and moves main's pointer off an invalid merge.
  */
object ScenarioMergeServiceSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private def ws(label: String): WorkspaceId = WorkspaceId(safeId(label))
  private def name(s: String): ScenarioName.ScenarioName = ScenarioName.fromString(s).toOption.get
  private def hash(fill: Char): CommitHash = CommitHash.fromString(fill.toString * 40).toOption.get

  private def commit(h: CommitHash): IrminCommit =
    IrminCommit(h.value, "", Nil, IrminInfo("2026-01-01T00:00:00Z", "test", "test"))

  // ── stored blobs ─────────────────────────────────────────────────────────
  // A node is stored as its concrete type's own JSON (RiskTreeRepositoryIrmin
  // .nodeJson), so the fixtures encode real nodes rather than hand-written
  // JSON — the scan has to read exactly what the writer produces.

  // Distinct from every nodeIdOf(n) below, so a fixture leaf is never also the root.
  private val rootId = NodeId.fromString("00000000000000000000000099").toOption.get

  private def leafNode(id: String, nodeName: String, seedVarId: Long): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = id,
      name = nodeName,
      distributionType = "lognormal",
      probability = 0.1,
      minLoss = Some(1000L),
      maxLoss = Some(50000L),
      parentId = Some(rootId),
      seedVarId = seedVarId
    ), "leaf")

  private def leafJson(id: String, nodeName: String, seedVarId: Long): String =
    leafNode(id, nodeName, seedVarId).toJson

  private def nodeIdOf(n: Int): String = f"000000000000000000000000$n%02d"

  private def tree(id: TreeId, treeName: String, children: Seq[RiskNode]): RiskTree =
    unsafeGet(RiskTree.fromNodes(
      id = id,
      name = SafeName.SafeName(treeName.refineUnsafe),
      nodes = unsafeGet(RiskPortfolio.createFromStrings(
        id = rootId.value.toString,
        name = "Root",
        childIds = children.map(_.id.value.toString).toArray,
        parentId = None
      ), "portfolio") +: children,
      rootId = rootId,
      mitigations = Nil
    ), "tree")

  private def treeIdOf(label: String): TreeId = TreeId.fromString(safeId(label).value).toOption.get

  // ── fakes ────────────────────────────────────────────────────────────────

  /** Fake Irmin for the merge path.
    *
    * `blobs` holds the three sides of every candidate path, keyed by the
    * workspace-relative path the service computes. `list` is derived from those
    * keys, so a fixture names paths once and the directory listing follows.
    *
    * When `base` equals `mainHead` the scan short-circuits — main has not moved
    * since the fork — and no path is read at all, which is what the error
    * mapping cases want.
    */
  private final class FakeMergeIrmin(
    mainHead: CommitHash,
    scenarioHead: CommitHash,
    base: CommitHash,
    onMain: Map[String, String],
    onScenario: Map[String, String],
    atBase: Map[String, String],
    mergeResult: IO[IrminError, IrminCommit],
    publishCalls: Ref[List[(CommitHash, CommitHash)]],
    publishResult: IO[IrminError, Unit],
    mainHeadPresent: Boolean = true,
    createResult: IO[IrminError, Unit] = ZIO.unit,
    branchOps: Option[Ref[List[String]]] = None
  ) extends IrminClient:
    private def branchOf(n: String, h: CommitHash): IrminBranch = IrminBranch(n, Some(commit(h)))
    private def rel(path: IrminPath): String =
      val v = path.value
      val i = v.indexOf("risk-trees")
      if i < 0 then v else v.substring(i)

    private def record(op: String): UIO[Unit] =
      ZIO.foreachDiscard(branchOps)(_.update(_ :+ op))

    override def mainBranch: IO[IrminError, Option[IrminBranch]] =
      ZIO.succeed(Option.when(mainHeadPresent)(branchOf("main", mainHead)))

    /** Answers for the scenario branch and for the staging branch alike: the
      * service reads the first for its head and the second only to find the
      * head its cleanup deletes with. */
    override def getBranch(branch: StoreBranch): IO[IrminError, Option[IrminBranch]] =
      ZIO.succeed(Some(branchOf(branch.name, scenarioHead)))

    override def lca(branch: BranchRef, c: CommitHash): IO[IrminError, List[IrminCommit]] =
      ZIO.succeed(List(commit(base)))

    override def createBranchAt(branch: StoreBranch, at: CommitHash): IO[IrminError, Unit] =
      record(s"create:${branch.name}") *> createResult

    override def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[IrminError, Unit] =
      record(s"delete:${branch.name}")

    /** The merge target is the staging branch, so this models merging into it
      * rather than into main. */
    override def mergeBranch(from: BranchRef, into: StoreBranch, message: String): IO[IrminError, IrminCommit] =
      record(s"merge:${from.toBranchRef}->${into.name}") *> mergeResult

    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[IrminError, Unit] =
      publishCalls.update(_ :+ (expectedHead, to)) *> publishResult

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main): IO[IrminError, Option[String]] =
      ZIO.succeed((if branch == BranchRef.Main then onMain else onScenario).get(rel(path)))
    override def getAtCommit(c: CommitHash, path: IrminPath): IO[IrminError, Option[String]] =
      ZIO.succeed(atBase.get(rel(path)))

    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main): IO[IrminError, List[IrminPath]] =
      val keys = (if branch == BranchRef.Main then onMain else onScenario).keySet
      val p    = rel(prefix)
      val names = p.split('/').toList match
        case "risk-trees" :: Nil =>
          keys.flatMap(_.split('/').lift(1))
        case "risk-trees" :: treeId :: "nodes" :: Nil =>
          keys.collect { case k if k.startsWith(s"risk-trees/$treeId/nodes/") => k.split('/').last }
        case _ => Set.empty[String]
      ZIO.succeed(names.toList.sorted.map(IrminPath.unsafeFrom))

    override def branches = ZIO.die(new NotImplementedError("unused by this spec"))
    override def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def revert(c: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getCommit(commitHash: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def listAtCommit(c: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def healthCheck = ZIO.die(new NotImplementedError("unused by this spec"))

  /** Repository stub for the post-merge guard. Only `getAllForWorkspace` is
    * reached; it returns whatever the fixture chose, and records the revision
    * it was asked for so a test can pin that the guard reads at the merge
    * commit rather than at a branch head.
    */
  private final class FakeRepo(
    loaded: List[Either[TreeLoadFailure, RiskTree]],
    revisions: Ref[List[Revision]]
  ) extends RiskTreeRepository:
    override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): Task[List[Either[TreeLoadFailure, RiskTree]]] =
      revisions.update(_ :+ rev).as(loaded)

    override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision) = ZIO.die(new NotImplementedError("unused by this spec"))

  private val validTree =
    tree(treeIdOf("guard-tree"), "Guard Tree", Seq(leafNode(nodeIdOf(1), "Outage", 1L)))

  /** The error-mapping fixture: base equals main's head, so the scan
    * short-circuits and no path is read.
    */
  private def service(mergeResult: IO[IrminError, IrminCommit]): UIO[ScenarioMergeService] =
    for
      resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
      revs   <- Ref.make(List.empty[Revision])
    yield ScenarioMergeServiceLive(
      FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
        mergeResult, resets, ZIO.unit),
      FakeRepo(List(Right(validTree)), revs)
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ScenarioMergeServiceSpec")(
      suite("merge error mapping")(
        test("a refused merge (IrminMergeConflict) maps to the domain MergeConflict") {
          service(ZIO.fail(IrminMergeConflict("Recursive merging of common ancestors: default")))
            .flatMap(_.merge(ws("ws-conflict"), name("draft-v1")).exit)
            .map(exit => assert(exit)(fails(isSubtype[MergeConflict](anything))))
        },
        test("an unrelated Irmin failure passes through untranslated") {
          service(ZIO.fail(IrminGraphQLError(List("boom"), None)))
            .flatMap(_.merge(ws("ws-other-error"), name("draft-v1")).exit)
            .map(exit => assert(exit)(fails(isSubtype[IrminGraphQLError](anything))))
        },
        test("a successful merge returns the merge commit's head") {
          service(ZIO.succeed(commit(hash('c'))))
            .flatMap(_.merge(ws("ws-clean"), name("draft-v1")))
            .map(newHead => assertTrue(newHead == hash('c')))
        },
        test("the domain MergeConflict names the scenario, never the branch reference") {
          val wsId = ws("ws-names-scenario")
          service(ZIO.fail(IrminMergeConflict("conflict")))
            .flatMap(_.merge(wsId, name("draft-v1")).exit)
            .map { exit =>
              val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
              assertTrue(
                failure.exists(_.scenario.value == "draft-v1"),
                failure.exists(m => !m.getMessage.contains(wsId.value))
              )
            }
        }
      ),

      suite("pre-merge node-name scan")(
        test("two branches adding a node with the same name under different ids refuse, and no merge runs") {
          // Both sides add a node absent at the base, so no path conflicts
          // byte-wise; the union gives one tree two nodes called "Outage".
          val treeId = treeIdOf("dup-names")
          val shared = Map(s"risk-trees/${treeId.value}/meta" -> """{"schemaVersion":2}""")
          val mainBlobs = shared +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(1)}" -> leafJson(nodeIdOf(1), "Outage", 1L))
          val scenarioBlobs = shared +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(2)}" -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, shared,
                          ZIO.die(new IllegalStateException("mergeBranch must not be called")),
                          resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-dup"), name("draft-v1")).exit
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.exists(_.getMessage.contains("duplicate node name(s)")),
              failure.exists(_.getMessage.contains("Outage")),
              failure.exists(_.getMessage.contains(treeId.value))
            )
        },
        test("two nodes with the same name in different trees merge cleanly — uniqueness is per tree") {
          val treeA = treeIdOf("dup-tree-a")
          val treeB = treeIdOf("dup-tree-b")
          val metas = Map(
            s"risk-trees/${treeA.value}/meta" -> """{"schemaVersion":2}""",
            s"risk-trees/${treeB.value}/meta" -> """{"schemaVersion":2}"""
          )
          val mainBlobs = metas +
            (s"risk-trees/${treeA.value}/nodes/${nodeIdOf(1)}" -> leafJson(nodeIdOf(1), "Outage", 1L))
          val scenarioBlobs = metas +
            (s"risk-trees/${treeB.value}/nodes/${nodeIdOf(2)}" -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, metas,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            head   <- svc.merge(ws("ws-dup-trees"), name("draft-v1"))
          yield assertTrue(head == hash('c'))
        },
        test("a node deleted on the winning side contributes no name, so the merge is clean") {
          // Main deletes node 1 (present at base, absent on main, unchanged on
          // the scenario side); the scenario adds node 2 with the old name.
          // A naive union of both branches would see "Outage" twice.
          val treeId = treeIdOf("dup-delete")
          val nodeOne = s"risk-trees/${treeId.value}/nodes/${nodeIdOf(1)}"
          val nodeOneJson = leafJson(nodeIdOf(1), "Outage", 1L)
          val meta = Map(s"risk-trees/${treeId.value}/meta" -> """{"schemaVersion":2}""")
          val atBase = meta + (nodeOne -> nodeOneJson)
          val mainBlobs = meta
          val scenarioBlobs = atBase +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(2)}" -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, atBase,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            head   <- svc.merge(ws("ws-dup-del"), name("draft-v1"))
          yield assertTrue(head == hash('c'))
        },
        test("a byte-level conflict is reported instead of the duplicate name, because it is checked first") {
          // The same node path holds different bytes on each side and differs
          // from the base, which conflicts; both sides also name a node
          // "Outage" twice across the union.
          val treeId = treeIdOf("dup-and-conflict")
          val contested = s"risk-trees/${treeId.value}/nodes/${nodeIdOf(1)}"
          val meta = Map(s"risk-trees/${treeId.value}/meta" -> """{"schemaVersion":2}""")
          val atBase = meta + (contested -> leafJson(nodeIdOf(1), "Original", 1L))
          val mainBlobs = meta +
            (contested -> leafJson(nodeIdOf(1), "Outage", 1L)) +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(2)}" -> leafJson(nodeIdOf(2), "Outage", 2L))
          val scenarioBlobs = meta +
            (contested -> leafJson(nodeIdOf(1), "Renamed", 3L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, atBase,
                          ZIO.die(new IllegalStateException("mergeBranch must not be called")),
                          resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-dup-conflict"), name("draft-v1")).exit
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.exists(_.getMessage.contains("conflicting path(s)")),
              failure.exists(m => !m.getMessage.contains("duplicate node name(s)"))
            )
        }
      ),

      suite("preview")(
        test("duplicated names and no byte-level conflict previews as DuplicateNames") {
          val treeId = treeIdOf("preview-dup")
          val meta = Map(s"risk-trees/${treeId.value}/meta" -> """{"schemaVersion":2}""")
          val mainBlobs = meta +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(1)}" -> leafJson(nodeIdOf(1), "Outage", 1L))
          val scenarioBlobs = meta +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(2)}" -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, meta,
                          ZIO.die(new IllegalStateException("mergeBranch must not be called")),
                          resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            result <- svc.preview(ws("ws-preview-dup"), name("draft-v1"))
          yield result match
            case MergePreviewResult.DuplicateNames(details) =>
              assertTrue(details.contains("duplicate node name(s)"), details.contains("Outage"))
            case other => assertTrue(false).label(s"expected DuplicateNames, got $other")
        },
        test("a scan finding both reports Conflicts, matching merge's order") {
          val treeId = treeIdOf("preview-both")
          val contested = s"risk-trees/${treeId.value}/nodes/${nodeIdOf(1)}"
          val meta = Map(s"risk-trees/${treeId.value}/meta" -> """{"schemaVersion":2}""")
          val atBase = meta + (contested -> leafJson(nodeIdOf(1), "Original", 1L))
          val mainBlobs = meta +
            (contested -> leafJson(nodeIdOf(1), "Outage", 1L)) +
            (s"risk-trees/${treeId.value}/nodes/${nodeIdOf(2)}" -> leafJson(nodeIdOf(2), "Outage", 2L))
          val scenarioBlobs = meta + (contested -> leafJson(nodeIdOf(1), "Renamed", 3L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, atBase,
                          ZIO.die(new IllegalStateException("mergeBranch must not be called")),
                          resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            result <- svc.preview(ws("ws-preview-both"), name("draft-v1"))
          yield assertTrue(result.isInstanceOf[MergePreviewResult.Conflicts])
        }
      ),

      suite("post-merge guard")(
        test("a merged state that loads cleanly is published to main under compare-and-set") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            head   <- svc.merge(ws("ws-guard-clean"), name("draft-v1"))
            calls  <- resets.get
            read   <- revs.get
          yield assertTrue(
            head == hash('c'),
            // One publish, guarded on the head read before the merge began, so
            // a commit landing on main in between makes it fail rather than be
            // absorbed and later discarded.
            calls == List((hash('a'), hash('c'))),
            // The guard reads at the staged commit, never at a branch head: a
            // head read would race a concurrent write onto main.
            read == List(Revision.At(hash('c')))
          )
        },
        test("one unloadable tree leaves main untouched and fails MergeConflict") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit),
                        FakeRepo(List(Left(TreeLoadFailure(treeIdOf("guard-tree"), "duplicate seedVarId 7"))), revs)
                      )
            exit   <- svc.merge(ws("ws-guard-undo"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.exists(_.getMessage.contains("the merge was not applied")),
              // names the tree by its id, which is what a caller can act on
              failure.exists(_.getMessage.contains(treeIdOf("guard-tree").value)),
              // nothing was published: main is never pointed at the staged
              // commit, so there is no undo to perform
              calls.isEmpty
            )
        },
        test("the guard's refusal names the trees, never the repository's own text") {
          // TreeLoadFailure.reason can name an absolute storage path, which
          // embeds the WorkspaceId (ADR-036). It goes to the log, and the
          // refusal names the tree instead.
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit),
                        FakeRepo(List(Left(TreeLoadFailure(
                          treeIdOf("guard-tree"),
                          "Missing node value at workspaces/01ARZ3NDEKTSV4RRFFQ69G5FAV/risk-trees/t/nodes/n"
                        ))), revs)
                      )
            exit   <- svc.merge(ws("ws-guard-leak"), name("draft-v1")).exit
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.isDefined,
              failure.forall(f => !f.getMessage.contains("01ARZ3NDEKTSV4RRFFQ69G5FAV")),
              failure.forall(f => !f.getMessage.toLowerCase.contains("workspaces/"))
            )
        },
        test("a commit landing on main during the merge fails MergeTargetMoved and publishes nothing") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets,
                          ZIO.fail(BranchHeadStale(BranchRef.Main, hash('a')))),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-publish-stale"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeTargetMoved => m }
            assertTrue(
              failure.exists(_.scenario.value == "draft-v1"),
              // the publish was attempted against the head read before the
              // merge, and refused
              calls == List((hash('a'), hash('c')))
            )
        },
        test("a merge already running on the same scenario fails MergeAlreadyRunning") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit,
                          createResult = ZIO.fail(BranchAlreadyExists(BranchRef.Main))),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-merge-busy"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeAlreadyRunning => m }
            assertTrue(
              failure.exists(_.scenario.value == "draft-v1"),
              calls.isEmpty
            )
        },
        test("the staging branch is created before the merge and removed after it, on both outcomes") {
          def run(repoResult: List[Either[TreeLoadFailure, RiskTree]]) =
            for
              resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
              revs   <- Ref.make(List.empty[Revision])
              ops    <- Ref.make(List.empty[String])
              svc     = ScenarioMergeServiceLive(
                          FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                            ZIO.succeed(commit(hash('c'))), resets, ZIO.unit,
                            branchOps = Some(ops)),
                          FakeRepo(repoResult, revs)
                        )
              _      <- svc.merge(ws("ws-staging-life"), name("draft-v1")).exit
              seen   <- ops.get
            yield seen

          for
            onSuccess <- run(List(Right(validTree)))
            onRefusal <- run(List(Left(TreeLoadFailure(treeIdOf("guard-tree"), "duplicate seedVarId 7"))))
          yield
            val staging = onSuccess.collectFirst { case o if o.startsWith("create:") => o.stripPrefix("create:") }
            assertTrue(
              staging.exists(_.startsWith("merge-staging.")),
              // created, merged into, then deleted — in that order
              onSuccess.head.startsWith("create:"),
              onSuccess.exists(o => o.startsWith("merge:") && o.endsWith(staging.getOrElse("?"))),
              onSuccess.last == s"delete:${staging.getOrElse("?")}",
              // and deleted just the same when the guard refuses the merge
              onRefusal.last.startsWith("delete:merge-staging.")
            )
        },
        test("no head on main fails RepositoryFailure — the store lost what it held") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, mainHeadPresent = false),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-guard-nohead"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption)
            assertTrue(
              // a typed failure on the error channel, not a defect: the caller
              // sees the same opaque 500 as any other storage failure
              failure.exists {
                case RepositoryFailure(reason) => reason == "main has no head"
                case _                         => false
              },
              calls.isEmpty
            )
        }
      )
    )
