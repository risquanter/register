package com.risquanter.register.repositories

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*

import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, WorkspaceId, TreeId, NodeId, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.{IrminError, RepositoryFailure}
import com.risquanter.register.infra.irmin.{IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry}
import com.risquanter.register.repositories.model.TreeMetadata
import com.risquanter.register.testutil.TestHelpers.*

import java.time.Instant

/** Read-path consistency: a tree load resolves the branch head exactly once,
  * then reads every constituent path pinned to that immutable commit, so a
  * commit landing mid-load cannot mix two commits' states (torn read).
  * Deterministic, no Docker: a scripted `IrminClient` whose branch head
  * advances after the first resolution, plus per-commit stores, lets us assert
  * that every read targeted the one resolved commit.
  */
object RiskTreeReadConsistencySpec extends ZIOSpecDefault:

  private val wsId: WorkspaceId = WorkspaceId(safeId("consistency-ws"))
  private val treeIdF: TreeId = treeId("consistency-tree")
  private val treeIdG: TreeId = treeId("consistency-tree-2")
  private val rootId = nodeId("root")
  private val leaf1Id = nodeId("leaf1")
  private val leaf2Id = nodeId("leaf2")

  private val headC1: CommitHash = CommitHash.fromString("a" * 40).toOption.get
  private val headC2: CommitHash = CommitHash.fromString("b" * 40).toOption.get

  private def leaf(id: NodeId, probability: Double): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = id.value.toString,
      name = s"Leaf ${id.value}",
      distributionType = "lognormal",
      probability = probability,
      minLoss = Some(1000L),
      maxLoss = Some(50000L),
      parentId = Some(rootId),
      seedVarId = if id == leaf1Id then 1L else 2L
    )

  private def tree(id: TreeId, children: Seq[RiskNode]): RiskTree =
    unsafeGet(
      RiskTree.fromNodes(
        id = id,
        name = SafeName.SafeName("Consistency Tree".refineUnsafe),
        nodes = RiskPortfolio.unsafeFromStrings(
          id = rootId.value.toString,
          name = "Root",
          childIds = children.map(_.id.value.toString).toArray,
          parentId = None
        ) +: children,
        rootId = rootId
      ),
      "Test fixture has invalid RiskTree"
    )

  private def metaOf(t: RiskTree): TreeMetadata =
    TreeMetadata(
      id = t.id,
      name = t.name,
      rootId = t.rootId,
      seedVarHighWater = t.seedVarHighWater,
      schemaVersion = 2,
      createdAt = Instant.EPOCH,
      updatedAt = Instant.EPOCH
    )

  private def nodeJson(n: RiskNode): String = n match
    case l: RiskLeaf      => l.toJson
    case p: RiskPortfolio => p.toJson

  /** Serialize a tree into a commit's path→value store, exactly as
    * `RiskTreeRepositoryIrmin.writeTree` lays it out. */
  private def storeOf(ts: RiskTree*): Map[String, String] =
    ts.flatMap { t =>
      val nodesBase = WorkspaceStoragePaths.treeNodes(wsId, t.id)
      Map(WorkspaceStoragePaths.treeMeta(wsId, t.id) -> metaOf(t).toJson) ++
        t.nodes.map(n => s"$nodesBase/${n.id.value}" -> nodeJson(n))
    }.toMap

  /** Same layout minus the meta blob — the "meta absent but nodes present"
    * behaviour-preservation case. */
  private def storeWithoutMeta(t: RiskTree): Map[String, String] =
    val nodesBase = WorkspaceStoragePaths.treeNodes(wsId, t.id)
    t.nodes.map(n => s"$nodesBase/${n.id.value}" -> nodeJson(n)).toMap

  private def emptyInfo: IrminInfo = IrminInfo(date = "", author = "", message = "")

  /** Scripted client. `getBranch` returns `headRef`, counts its calls, and (if
    * `advanceTo` is set) advances the head AFTER answering — so a second head
    * resolution would observe a different commit. `getAtCommit`/`listAtCommit`
    * record which commit they were asked about and serve that commit's store. */
  private final class ScriptedIrminClient(
      headRef: Ref[Option[CommitHash]],
      branchCalls: Ref[Int],
      queriedCommits: Ref[Set[CommitHash]],
      advanceTo: Option[CommitHash],
      store: Map[CommitHash, Map[String, String]]
  ) extends IrminClient:

    override def getBranch(branch: BranchRef): IO[IrminError, Option[IrminBranch]] =
      for
        _    <- branchCalls.update(_ + 1)
        head <- headRef.get
        _    <- advanceTo.fold[UIO[Unit]](ZIO.unit)(next => headRef.set(Some(next)))
      yield head.map(h =>
        IrminBranch(branch.toBranchRef, Some(IrminCommit(h.value, h.value, Nil, emptyInfo)))
      )

    override def getAtCommit(commit: CommitHash, path: IrminPath): IO[IrminError, Option[String]] =
      queriedCommits.update(_ + commit) *>
        ZIO.succeed(store.getOrElse(commit, Map.empty).get(path.value))

    override def listAtCommit(commit: CommitHash, path: IrminPath): IO[IrminError, List[IrminPath]] =
      queriedCommits.update(_ + commit) *> ZIO.succeed {
        val base = if path.value.isEmpty then "" else s"${path.value}/"
        store
          .getOrElse(commit, Map.empty)
          .keySet
          .collect { case p if p.startsWith(base) && p != path.value => p.stripPrefix(base).takeWhile(_ != '/') }
          .filter(_.nonEmpty)
          .toList
          .distinct
          .map(IrminPath.unsafeFrom)
      }

    private def unused(op: String): Nothing = throw new NotImplementedError(s"$op unused by RiskTreeReadConsistencySpec")
    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("get"))
    override def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("set"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("setTree"))
    override def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("remove"))
    override def branches = ZIO.die(unused("branches"))
    override def mainBranch = ZIO.die(unused("mainBranch"))
    override def mergeBranch(from: BranchRef, into: BranchRef, message: String) = ZIO.die(unused("mergeBranch"))
    override def revert(commit: CommitHash, branch: BranchRef) = ZIO.die(unused("revert"))
    override def createBranchAt(branch: BranchRef, at: CommitHash) = ZIO.die(unused("createBranchAt"))
    override def deleteBranch(branch: BranchRef, currentHead: CommitHash) = ZIO.die(unused("deleteBranch"))
    override def getCommit(hash: CommitHash) = ZIO.die(unused("getCommit"))
    override def getHistory(path: IrminPath, n: com.risquanter.register.domain.data.iron.PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("getHistory"))
    override def lca(branch: BranchRef, commit: CommitHash) = ZIO.die(unused("lca"))
    override def healthCheck = ZIO.die(unused("healthCheck"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("list"))

  private def scripted(
      initialHead: Option[CommitHash],
      advanceTo: Option[CommitHash],
      store: Map[CommitHash, Map[String, String]]
  ): UIO[(RiskTreeRepositoryIrmin, Ref[Int], Ref[Set[CommitHash]])] =
    for
      headRef  <- Ref.make(initialHead)
      calls    <- Ref.make(0)
      queried  <- Ref.make(Set.empty[CommitHash])
    yield (new RiskTreeRepositoryIrmin(new ScriptedIrminClient(headRef, calls, queried, advanceTo, store)), calls, queried)

  def spec = suite("RiskTreeRepositoryIrmin read-path consistency")(

    test("getById resolves the head once and reads every path pinned to that one commit") {
      val treeC1 = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))                    // 2 nodes
      val treeC2 = tree(treeIdF, Seq(leaf(leaf1Id, 0.9), leaf(leaf2Id, 0.2))) // 3 nodes
      for
        setup             <- scripted(Some(headC1), advanceTo = Some(headC2),
                               store = Map(headC1 -> storeOf(treeC1), headC2 -> storeOf(treeC2)))
        (repo, calls, queried) = setup
        result            <- repo.getById(wsId, treeIdF, BranchRef.Main)
        headResolutions   <- calls.get
        commitsRead       <- queried.get
      yield assertTrue(
        result.isDefined,
        result.get.nodes.size == 2,            // treeC1 = root + 1 leaf, never treeC2's 3 nodes
        headResolutions == 1,                  // resolved once
        commitsRead == Set(headC1)             // every read pinned to the pre-advance commit
      )
    },

    test("getById on a nonexistent branch returns None (behaviour preservation)") {
      for
        setup             <- scripted(initialHead = None, advanceTo = None, store = Map.empty)
        (repo, _, _)       = setup
        result            <- repo.getById(wsId, treeIdF, BranchRef.Main)
      yield assertTrue(result.isEmpty)
    },

    test("getById fails with RepositoryFailure when meta is absent but nodes exist at the resolved commit") {
      val treeC1 = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      for
        setup             <- scripted(Some(headC1), advanceTo = None,
                               store = Map(headC1 -> storeWithoutMeta(treeC1)))
        (repo, _, _)       = setup
        exit              <- repo.getById(wsId, treeIdF, BranchRef.Main).exit
      yield assert(exit)(fails(isSubtype[RepositoryFailure](anything)))
    },

    test("getAllForWorkspace resolves ONE head for the whole listing and every read is pinned to it") {
      val treeF = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val treeG = tree(treeIdG, Seq(leaf(leaf1Id, 0.3)))
      for
        setup             <- scripted(Some(headC1), advanceTo = Some(headC2),
                               store = Map(headC1 -> storeOf(treeF, treeG), headC2 -> Map.empty))
        (repo, calls, queried) = setup
        result            <- repo.getAllForWorkspace(wsId, BranchRef.Main)
        headResolutions   <- calls.get
        commitsRead       <- queried.get
      yield assertTrue(
        result.size == 2,
        result.forall(_.isRight),
        headResolutions == 1,
        commitsRead == Set(headC1)
      )
    }
  )
