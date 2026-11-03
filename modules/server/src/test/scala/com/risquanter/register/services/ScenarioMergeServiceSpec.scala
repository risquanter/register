package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*

import java.time.Instant
import io.github.iltotore.iron.*
import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.{RiskLeaf, RiskNode, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, MergeStagingRef, NodeId, PositiveInt, Revision, SafeName, SeedVarId, StoreBranch, TreeId}
import com.risquanter.register.repositories.model.TreeMetadata
import com.risquanter.register.domain.errors.{AppError, BranchAlreadyExists, BranchHeadStale, FieldName, IrminError, IrminGraphQLError, IrminMergeConflict, MergeAlreadyRunning, MergeConflict, MergeTargetMoved, RepositoryFailure, TreeLoadFailure}
import com.risquanter.register.http.responses.{MergeConflictEntry, MergePreviewStatus}
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.WorkspaceStoragePaths.TreePath
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath, IrminSegment}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.testutil.TestHelpers.{safeId, unsafeGet, lognormalShape}

/** Unit tests for `ScenarioMergeServiceLive` with no Docker or live Irmin.
  *
  * Three things are pinned here. The error mapping around
  * `IrminClient.mergeCommit`: the patched Irmin backend refuses a conflicting
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

  private def leafNode(id: NodeId, nodeName: String, seedVarId: Long): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = id.value,
      name = nodeName,
      probability = 0.1,
      shape = lognormalShape(1000L, 50000L),
      parentId = Some(rootId),
      seedVarId = seedVarId
    ), "leaf")

  private def leafJson(id: NodeId, nodeName: String, seedVarId: Long): String =
    leafNode(id, nodeName, seedVarId).toJson

  private def nodeIdOf(n: Int): NodeId = NodeId.fromString(f"000000000000000000000000$n%02d").toOption.get

  /** Where node `n` of `treeId` is stored. */
  private def nodePath(treeId: TreeId, n: Int): TreePath = TreePath.Node(treeId, nodeIdOf(n))

  private def tree(id: TreeId, treeName: String, children: Seq[RiskNode]): RiskTree =
    unsafeGet(RiskTree.fromNodes(
      id = id,
      name = SafeName.SafeName(treeName.refineUnsafe),
      nodes = unsafeGet(RiskPortfolio.createFromStrings(
        id = rootId.value.toString,
        name = "Root",
        childIds = children.map(_.id.value.toString).toVector,
        parentId = None
      ), "portfolio") +: children,
      rootId = rootId,
      seedVarHighWater = None,
      mitigations = Nil
    ), "tree")

  private def treeIdOf(label: String): TreeId = TreeId.fromString(safeId(label).value).toOption.get

  /** A tree's stored `meta` value, saved at `savedAt`. The scan decodes the
    * `meta` value of every tree a conflict names. */
  private def metaJson(treeId: TreeId, savedAt: Instant): String =
    TreeMetadata(
      id = treeId,
      name = SafeName.SafeName("Merge Tree".refineUnsafe),
      rootId = rootId,
      seedVarHighWater = SeedVarId.fromLong(3L).toOption.get,
      schemaVersion = TreeMetadata.CurrentSchemaVersion,
      createdAt = Instant.parse("2026-11-01T09:00:00Z"),
      updatedAt = savedAt
    ).toJson

  // ── fakes ────────────────────────────────────────────────────────────────

  /** Fake Irmin for the merge path.
    *
    * `onMain`, `onScenario` and `atBase` hold the three sides of every
    * candidate path, keyed by where the content is stored in `wsId`. Each side
    * is answered for the commit that names it — main's head, the scenario's
    * head, or the base — and `listAtCommit` is derived from the same keys, so a
    * fixture names paths once and the listing follows. The branch-name reads
    * are not answered at all: the scan reads only at the commits it pinned.
    *
    * When `base` equals `mainHead` the scan short-circuits — main has not moved
    * since the fork — and no path is read at all, which is what the error
    * mapping cases want.
    */
  private final class FakeMergeIrmin(
    wsId: WorkspaceId,
    mainHead: CommitHash,
    scenarioHead: CommitHash,
    base: CommitHash,
    onMain: Map[TreePath, String],
    onScenario: Map[TreePath, String],
    atBase: Map[TreePath, String],
    mergeResult: IO[IrminError, IrminCommit],
    publishCalls: Ref[List[(CommitHash, CommitHash)]],
    publishResult: IO[IrminError, Unit],
    mainHeadPresent: Boolean = true,
    createResult: IO[IrminError, Unit] = ZIO.unit,
    branchOps: Option[Ref[List[String]]],
    stagingSets: Option[Ref[List[(IrminPath, String, CommitMessage)]]],
    mainMovesTo: List[CommitHash] = Nil
  ) extends IrminClient:
    private def branchOf(n: String, h: CommitHash): IrminBranch = IrminBranch(n, Some(commit(h)))

    /** How many times main's head has been read. The first read answers
      * `mainHead`, and each later read answers the next of `mainMovesTo`, so a
      * test can move main between two reads. */
    private val mainReads = new java.util.concurrent.atomic.AtomicInteger(0)

    /** The side a commit names: main's head, the scenario's head, or the base. */
    private def sideAt(c: CommitHash): Map[TreePath, String] = c match
      case `mainHead`     => onMain
      case `scenarioHead` => onScenario
      case _              => atBase

    /** The name Irmin lists for `full` under `prefix`: the ancestor of `full`
      * one level below `prefix`, named by its last segment. */
    private def childUnder(prefix: IrminPath, full: IrminPath): Option[IrminSegment] =
      Iterator.iterate(Option(full))(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten
        .find(_.parent.contains(prefix))
        .flatMap(_.name)

    private def record(op: String): UIO[Unit] =
      ZIO.foreachDiscard(branchOps)(_.update(_ :+ op))

    override def mainBranch: IO[IrminError, Option[IrminBranch]] =
      ZIO.succeed {
        val head = (mainHead :: mainMovesTo).applyOrElse(mainReads.getAndIncrement(), _ => mainMovesTo.lastOption.getOrElse(mainHead))
        Option.when(mainHeadPresent)(branchOf("main", head))
      }

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
      * rather than into main. Records the merged commit's hash and the target. */
    override def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage): IO[IrminError, IrminCommit] =
      record(s"merge:${from.value}->${into.name}") *> mergeResult

    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[IrminError, Unit] =
      publishCalls.update(_ :+ (expectedHead, to)) *> publishResult

    override def getAtCommit(c: CommitHash, path: IrminPath): IO[IrminError, Option[String]] =
      ZIO.succeed(sideAt(c).map((p, v) => WorkspaceStoragePaths.of(wsId, p) -> v).get(path))

    override def listAtCommit(c: CommitHash, prefix: IrminPath): IO[IrminError, List[IrminSegment]] =
      ZIO.succeed(
        sideAt(c).keySet.toList
          .flatMap(p => childUnder(prefix, WorkspaceStoragePaths.of(wsId, p)))
          .distinct
          .sortBy(_.value))

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def branches = ZIO.die(new NotImplementedError("unused by this spec"))
    override def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))

    /** Records the path, contents and message of each write to the staging
      * branch, and answers with a commit of its own so the merge's head is the
      * last write. */
    override def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef): IO[IrminError, IrminCommit] =
      ZIO.foreachDiscard(stagingSets)(_.update(_ :+ (path, value, message))).as(commit(hash('e')))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def revert(c: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getCommit(commitHash: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
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
    override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[AppError, List[Either[TreeLoadFailure, RiskTree]]] =
      revisions.update(_ :+ rev).as(loaded)

    override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision) = ZIO.die(new NotImplementedError("unused by this spec"))

  private val validTree =
    tree(treeIdOf("guard-tree"), "Guard Tree", Seq(leafNode(nodeIdOf(1), "Outage", 1L)))

  /** The workspace given to every fixture whose base equals main's head: the
    * scan short-circuits there and reads no path, so no path is ever built
    * from it. */
  private val noReadWs = ws("ws-no-path-read")

  /** The error-mapping fixture: base equals main's head, so the scan
    * short-circuits and no path is read.
    */
  private def service(mergeResult: IO[IrminError, IrminCommit]): UIO[ScenarioMergeService] =
    for
      resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
      revs   <- Ref.make(List.empty[Revision])
    yield ScenarioMergeServiceLive(
      FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
        mergeResult, resets, ZIO.unit, branchOps = None, stagingSets = None),
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
                failure.exists(m => !m.logMessage.contains(wsId.value))
              )
            }
        }
      ),

      suite("pre-merge node-name scan")(
        test("two branches adding a node with the same name under different ids refuse, and no merge runs") {
          // Both sides add a node absent at the base, so no path conflicts
          // byte-wise; the union gives one tree two nodes called "Outage".
          val wsId   = ws("ws-dup")
          val treeId = treeIdOf("dup-names")
          val shared = Map[TreePath, String](TreePath.Meta(treeId) -> """{"schemaVersion":2}""")
          val mainBlobs = shared + (nodePath(treeId, 1) -> leafJson(nodeIdOf(1), "Outage", 1L))
          val scenarioBlobs = shared + (nodePath(treeId, 2) -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, shared,
                          ZIO.die(new IllegalStateException("mergeCommit must not be called")),
                          resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(wsId, name("draft-v1")).exit
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.exists(_.logMessage.contains("duplicate node name(s)")),
              failure.exists(_.logMessage.contains("Outage")),
              failure.exists(_.logMessage.contains(treeId.value))
            )
        },
        test("two nodes with the same name in different trees merge cleanly — uniqueness is per tree") {
          val wsId  = ws("ws-dup-trees")
          val treeA = treeIdOf("dup-tree-a")
          val treeB = treeIdOf("dup-tree-b")
          val metas = Map[TreePath, String](
            TreePath.Meta(treeA) -> """{"schemaVersion":2}""",
            TreePath.Meta(treeB) -> """{"schemaVersion":2}"""
          )
          val mainBlobs = metas + (nodePath(treeA, 1) -> leafJson(nodeIdOf(1), "Outage", 1L))
          val scenarioBlobs = metas + (nodePath(treeB, 2) -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, metas,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            head   <- svc.merge(wsId, name("draft-v1"))
          yield assertTrue(head == hash('c'))
        },
        test("a node deleted on the winning side contributes no name, so the merge is clean") {
          // Main deletes node 1 (present at base, absent on main, unchanged on
          // the scenario side); the scenario adds node 2 with the old name.
          // A naive union of both branches would see "Outage" twice.
          val wsId = ws("ws-dup-del")
          val treeId = treeIdOf("dup-delete")
          val nodeOne = nodePath(treeId, 1)
          val nodeOneJson = leafJson(nodeIdOf(1), "Outage", 1L)
          val meta = Map[TreePath, String](TreePath.Meta(treeId) -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val atBase = meta + (nodeOne -> nodeOneJson)
          val mainBlobs = meta
          val scenarioBlobs = atBase + (nodePath(treeId, 2) -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, atBase,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            head   <- svc.merge(wsId, name("draft-v1"))
          yield assertTrue(head == hash('c'))
        },
        test("a byte-level conflict is reported instead of the duplicate name, because it is checked first") {
          // The same node path holds different bytes on each side and differs
          // from the base, which conflicts; both sides also name a node
          // "Outage" twice across the union.
          val wsId = ws("ws-dup-conflict")
          val treeId = treeIdOf("dup-and-conflict")
          val contested = nodePath(treeId, 1)
          val meta = Map[TreePath, String](TreePath.Meta(treeId) -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val atBase = meta + (contested -> leafJson(nodeIdOf(1), "Original", 1L))
          val mainBlobs = meta +
            (contested -> leafJson(nodeIdOf(1), "Outage", 1L)) +
            (nodePath(treeId, 2) -> leafJson(nodeIdOf(2), "Outage", 2L))
          val scenarioBlobs = meta +
            (contested -> leafJson(nodeIdOf(1), "Renamed", 3L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, atBase,
                          ZIO.die(new IllegalStateException("mergeCommit must not be called")),
                          resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(wsId, name("draft-v1")).exit
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.exists(_.logMessage.contains("conflicting item(s)")),
              failure.exists(m => !m.logMessage.contains("duplicate node name(s)"))
            )
        }
      ),

      suite("a node one branch deleted and the other changed")(
        test("previews one NodeConflict naming the fields the surviving branch changed since the fork") {
          val wsId     = ws("ws-deleted-changed")
          val treeId   = treeIdOf("deleted-and-changed")
          val node     = nodePath(treeId, 1)
          val meta     = Map[TreePath, String](TreePath.Meta(treeId) -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val atBase   = meta + (node -> leafJson(nodeIdOf(1), "Original", 1L))
          val onMain   = meta
          val onScen   = meta + (node -> leafJson(nodeIdOf(1), "Renamed", 1L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), onMain, onScen, atBase,
                          ZIO.die(new IllegalStateException("a preview must not merge")),
                          resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            result <- svc.preview(wsId, name("draft-v1"))
          yield result match
            case MergePreviewStatus.Conflicts(List(MergeConflictEntry.NodeConflict(tree, nodeId, versions, fields))) =>
              assertTrue(
                tree.id == treeId,
                nodeId == nodeIdOf(1),
                versions.base.isDefined,
                versions.onMain.isEmpty,
                versions.onScenario.map(_.name.value.toString).contains("Renamed"),
                fields == List(FieldName.Name)
              )
            case other =>
              assertTrue(false).label(s"expected one NodeConflict, got $other")
        }
      ),

      suite("a tree's meta value saved on both branches")(
        test("two meta values differing only in updatedAt preview Clean") {
          val wsId     = ws("ws-meta-preview")
          val treeId   = treeIdOf("meta-saved-twice")
          val metaPath = TreePath.Meta(treeId)
          val atBase   = Map[TreePath, String](metaPath -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val onMain   = Map[TreePath, String](metaPath -> metaJson(treeId, Instant.parse("2026-11-01T10:00:00Z")))
          val onScen   = Map[TreePath, String](metaPath -> metaJson(treeId, Instant.parse("2026-11-01T11:00:00Z")))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), onMain, onScen, atBase,
                          ZIO.die(new IllegalStateException("a preview must not merge")),
                          resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            result <- svc.preview(wsId, name("draft-v1"))
          yield assertTrue(result == MergePreviewStatus.Clean())
        },
        test("the merge writes the scenario's meta value before Irmin's merge and the merged value after it") {
          val wsId         = ws("ws-meta-merge")
          val scenario     = name("draft-v1")
          val treeId       = treeIdOf("meta-merge-writes")
          val metaPath     = TreePath.Meta(treeId)
          val scenarioMeta = metaJson(treeId, Instant.parse("2026-11-01T11:00:00Z"))
          val atBase       = Map[TreePath, String](metaPath -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val onMain       = Map[TreePath, String](metaPath -> metaJson(treeId, Instant.parse("2026-11-01T10:00:00Z")))
          val onScen       = Map[TreePath, String](metaPath -> scenarioMeta)
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            sets   <- Ref.make(List.empty[(IrminPath, String, CommitMessage)])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), onMain, onScen, atBase,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit,
                          branchOps = None, stagingSets = Some(sets)),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            _      <- TestClock.setTime(Instant.parse("2026-11-01T12:00:00Z"))
            head   <- svc.merge(wsId, scenario)
            writes <- sets.get
          yield
            val stored = WorkspaceStoragePaths.of(wsId, metaPath)
            val merged = writes.lift(1).flatMap(w => w._2.fromJson[TreeMetadata].toOption)
            assertTrue(
              writes.map(_._1) == List(stored, stored),
              writes.headOption.map(_._2).contains(scenarioMeta),
              writes.headOption.map(_._3).contains(CommitMessage.MergePrepared(wsId, scenario)),
              writes.lift(1).map(_._3).contains(CommitMessage.ScenarioMerged(wsId, scenario)),
              // The merged value carries the merge time as its updatedAt.
              merged.map(_.updatedAt).contains(Instant.parse("2026-11-01T12:00:00Z")),
              // The published head is the last staging write, not Irmin's own
              // merge commit.
              head == hash('e')
            )
        }
      ),

      suite("preview")(
        test("duplicated names and no byte-level conflict previews as DuplicateNames") {
          val wsId = ws("ws-preview-dup")
          val treeId = treeIdOf("preview-dup")
          val meta = Map[TreePath, String](TreePath.Meta(treeId) -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val mainBlobs = meta + (nodePath(treeId, 1) -> leafJson(nodeIdOf(1), "Outage", 1L))
          val scenarioBlobs = meta + (nodePath(treeId, 2) -> leafJson(nodeIdOf(2), "Outage", 2L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, meta,
                          ZIO.die(new IllegalStateException("mergeCommit must not be called")),
                          resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            result <- svc.preview(wsId, name("draft-v1"))
          yield result match
            case MergePreviewStatus.DuplicateNames(perTree) =>
              assertTrue(
                perTree.contains(treeId),
                perTree.get(treeId).exists(_.exists(_.value.toString == "Outage"))
              )
            case other => assertTrue(false).label(s"expected DuplicateNames, got $other")
        },
        test("a scan finding both reports Conflicts, matching merge's order") {
          val wsId = ws("ws-preview-both")
          val treeId = treeIdOf("preview-both")
          val contested = nodePath(treeId, 1)
          val meta = Map[TreePath, String](TreePath.Meta(treeId) -> metaJson(treeId, Instant.parse("2026-11-01T09:00:00Z")))
          val atBase = meta + (contested -> leafJson(nodeIdOf(1), "Original", 1L))
          val mainBlobs = meta +
            (contested -> leafJson(nodeIdOf(1), "Outage", 1L)) +
            (nodePath(treeId, 2) -> leafJson(nodeIdOf(2), "Outage", 2L))
          val scenarioBlobs = meta + (contested -> leafJson(nodeIdOf(1), "Renamed", 3L))
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(wsId, hash('a'), hash('b'), hash('d'), mainBlobs, scenarioBlobs, atBase,
                          ZIO.die(new IllegalStateException("mergeCommit must not be called")),
                          resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            result <- svc.preview(wsId, name("draft-v1"))
          yield assertTrue(result match { case MergePreviewStatus.Conflicts(_) => true; case _ => false })
        }
      ),

      suite("post-merge guard")(
        test("a merged state that loads cleanly is published to main under compare-and-set") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, branchOps = None, stagingSets = None),
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
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, branchOps = None, stagingSets = None),
                        FakeRepo(List(Left(TreeLoadFailure(treeIdOf("guard-tree"), "duplicate seedVarId 7"))), revs)
                      )
            exit   <- svc.merge(ws("ws-guard-undo"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeConflict => m }
            assertTrue(
              failure.exists(_.logMessage.contains("the merge was not applied")),
              // names the tree by its id, which is what a caller can act on
              failure.exists(_.logMessage.contains(treeIdOf("guard-tree").value)),
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
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, branchOps = None, stagingSets = None),
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
              failure.forall(f => !f.logMessage.contains("01ARZ3NDEKTSV4RRFFQ69G5FAV")),
              failure.forall(f => !f.logMessage.toLowerCase.contains("workspaces/"))
            )
        },
        test("a commit landing on main during the merge fails MergeTargetMoved and publishes nothing") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets,
                          ZIO.fail(BranchHeadStale(BranchRef.Main, hash('a'))), branchOps = None, stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-publish-stale"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeTargetMoved => m }
            assertTrue(
              failure.exists(_.scenario.value == "draft-v1"),
              // the publish was attempted against the head the scan compared
              // against, and refused
              calls == List((hash('a'), hash('c')))
            )
        },
        test("a save on main after the scan makes the publish check against the scanned head, so it fails MergeTargetMoved") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets,
                          ZIO.fail(BranchHeadStale(BranchRef.Main, hash('a'))), branchOps = None, stagingSets = None,
                          mainMovesTo = List(hash('d'))),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            exit   <- svc.merge(ws("ws-main-moved"), name("draft-v1")).exit
            calls  <- resets.get
          yield
            val failure = exit.causeOption.flatMap(_.failureOption).collect { case m: MergeTargetMoved => m }
            assertTrue(
              failure.exists(_.scenario.value == "draft-v1"),
              // main reads as 'd' after the scan, and the publish still expects
              // 'a', the head the scan compared against
              calls == List((hash('a'), hash('c')))
            )
        },
        test("a merge already running on the same scenario fails MergeAlreadyRunning") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit,
                          createResult = ZIO.fail(BranchAlreadyExists(BranchRef.Main)), branchOps = None, stagingSets = None),
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
                          FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                            ZIO.succeed(commit(hash('c'))), resets, ZIO.unit,
                            branchOps = Some(ops), stagingSets = None),
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
        test("merge merges the scenario commit the scan compared") {
          val scenarioHead = hash('b')
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            ops    <- Ref.make(List.empty[String])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(noReadWs, hash('a'), scenarioHead, hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit,
                          branchOps = Some(ops), stagingSets = None),
                        FakeRepo(List(Right(validTree)), revs)
                      )
            _      <- svc.merge(ws("ws-merges-scanned-commit"), name("draft-v1"))
            seen   <- ops.get
          yield
            val merged = seen.collect { case o if o.startsWith("merge:") => o.stripPrefix("merge:").takeWhile(_ != '-') }
            assertTrue(merged == List(scenarioHead.value))
        },
        test("no head on main fails RepositoryFailure — the store lost what it held") {
          for
            resets <- Ref.make(List.empty[(CommitHash, CommitHash)])
            revs   <- Ref.make(List.empty[Revision])
            svc     = ScenarioMergeServiceLive(
                        FakeMergeIrmin(noReadWs, hash('a'), hash('b'), hash('a'), Map.empty, Map.empty, Map.empty,
                          ZIO.succeed(commit(hash('c'))), resets, ZIO.unit, mainHeadPresent = false, branchOps = None, stagingSets = None),
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
