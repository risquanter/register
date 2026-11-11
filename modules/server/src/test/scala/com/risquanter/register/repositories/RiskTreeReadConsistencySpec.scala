package com.risquanter.register.repositories

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import zio.prelude.NonEmptyList
import io.github.iltotore.iron.*

import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.iron.{
  SafeName, WorkspaceId, TreeId, NodeId, BranchChoice, BranchRef, CommitHash, MergeStagingRef, PositiveInt, Revision, ScenarioName,
  StoreBranch, TelemetryPlaceholders
}
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, ErrorSubject, Failure, FieldName, FieldProblem, PeerZone, RequestHeaderName, RequestQueryParam,
  TrustZone, ValueRange
}
import com.risquanter.register.http.errors.{NotFoundKind, TreeScope, WireError}
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.WorkspaceStoragePaths.TreePath
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminSegment, IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry}
import com.risquanter.register.repositories.model.TreeMetadata
import com.risquanter.register.testutil.TestHelpers.*

import java.time.Instant

/** Read-path consistency: a tree load resolves the branch head exactly once,
  * then reads every constituent path pinned to that immutable commit, so a
  * commit landing mid-load cannot mix two commits' states (torn read).
  * Deterministic, no Docker: a scripted `IrminClient` whose branch head
  * advances after the first resolution, plus per-commit stores, lets us assert
  * that every read targeted the one resolved commit.
  *
  * The same scripted client pins what a read answers when the revision lacks a
  * tree the workspace holds: the caller's `NotFound`, naming the branch as the
  * caller knows it and never the workspace identifier.
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
    unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
      id = id.value.toString,
      name = s"Leaf ${id.value}",
      probability = probability,
      shape = lognormalShape(1000L, 50000L),
      parentId = Some(rootId),
      seedVarId = if id == leaf1Id then 1L else 2L
    ), "leaf")

  private def tree(id: TreeId, children: Seq[RiskNode]): RiskTree =
    unsafeGet(
      RiskTree.fromNodes(
        id = id,
        name = SafeName.SafeName("Consistency Tree".refineUnsafe),
        nodes = unsafeGet(RiskPortfolio.createFromStrings(subject = ErrorSubject.Request,
          id = rootId.value.toString,
          name = "Root",
          childIds = children.map(_.id.value.toString).toVector,
          parentId = None
        ), "portfolio") +: children,
        rootId = rootId,
        seedVarHighWater = None,
        mitigations = Nil
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
  private def storeOf(ts: RiskTree*): Map[IrminPath, String] =
    ts.flatMap { t =>
      Map(WorkspaceStoragePaths.treeMeta(wsId, t.id) -> metaOf(t).toJson) ++
        t.nodes.map(n => WorkspaceStoragePaths.of(wsId, TreePath.Node(t.id, n.id)) -> nodeJson(n))
    }.toMap

  /** Same layout minus the meta blob — the "meta absent but nodes present"
    * behaviour-preservation case. */
  private def storeWithoutMeta(t: RiskTree): Map[IrminPath, String] =
    t.nodes.map(n => WorkspaceStoragePaths.of(wsId, TreePath.Node(t.id, n.id)) -> nodeJson(n)).toMap

  /** The name Irmin lists for `full` under `prefix`: the ancestor of `full`
    * one level below `prefix`, named by its last segment. */
  private def childUnder(prefix: IrminPath, full: IrminPath): Option[IrminSegment] =
    Iterator.iterate(Option(full))(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten
      .find(_.parent.contains(prefix))
      .flatMap(_.name)

  /** The information every scripted commit carries; no assertion here reads it. */
  private def emptyInfo: IrminInfo =
    IrminInfo(Instant.EPOCH, CommitMessage.TreeWritten(wsId, treeIdF, TreeWriteKind.Update))

  /** Scripted client. `getBranch` returns `headRef`, counts its calls, and (if
    * `advanceTo` is set) advances the head AFTER answering — so a second head
    * resolution would observe a different commit. `getAtCommit`/`listAtCommit`
    * record which commit they were asked about and serve that commit's store. */
  private final class ScriptedIrminClient(
      headRef: Ref[Option[CommitHash]],
      branchCalls: Ref[Int],
      queriedCommits: Ref[Set[CommitHash]],
      advanceTo: Option[CommitHash],
      store: Map[CommitHash, Map[IrminPath, String]]
  ) extends IrminClient:

    override def getBranch(branch: StoreBranch): IO[Failure,Option[IrminBranch]] =
      for
        _    <- branchCalls.update(_ + 1)
        head <- headRef.get
        _    <- advanceTo.fold[UIO[Unit]](ZIO.unit)(next => headRef.set(Some(next)))
      yield head.map(h =>
        IrminBranch(branch, Some(IrminCommit(h, h, Nil, emptyInfo)))
      )

    override def getAtCommit(commit: CommitHash, path: IrminPath): IO[Failure,Option[String]] =
      queriedCommits.update(_ + commit) *>
        ZIO.succeed(store.getOrElse(commit, Map.empty).get(path))

    override def listAtCommit(commit: CommitHash, path: IrminPath): IO[Failure,List[IrminSegment]] =
      queriedCommits.update(_ + commit) *> ZIO.succeed(
        store.getOrElse(commit, Map.empty).keySet.toList
          .flatMap(childUnder(path, _))
          .distinct
      )

    private def unused(op: String): Nothing = throw new NotImplementedError(s"$op unused by RiskTreeReadConsistencySpec")
    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("get"))
    override def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("set"))
    override def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef) = ZIO.die(unused("set"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("setTree"))
    override def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("remove"))
    override def branches = ZIO.die(unused("branches"))
    override def mainBranch = ZIO.die(unused("mainBranch"))
    override def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage) = ZIO.die(unused("mergeCommit"))
    override def revert(commit: CommitHash, branch: BranchRef) = ZIO.die(unused("revert"))
    override def createBranchAt(branch: StoreBranch, at: CommitHash) = ZIO.die(unused("createBranchAt"))
    override def deleteBranch(branch: StoreBranch, currentHead: CommitHash) = ZIO.die(unused("deleteBranch"))
    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash) = ZIO.die(unused("moveBranchTo"))
    override def getCommit(hash: CommitHash) = ZIO.die(unused("getCommit"))
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("getHistory"))
    override def lca(branch: BranchRef, commit: CommitHash) = ZIO.die(unused("lca"))
    override def healthCheck = ZIO.die(unused("healthCheck"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(unused("list"))

  private def scripted(
      initialHead: Option[CommitHash],
      advanceTo: Option[CommitHash],
      store: Map[CommitHash, Map[IrminPath, String]]
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
        result            <- repo.getById(wsId, treeIdF, Revision.Head(BranchRef.Main))
        headResolutions   <- calls.get
        commitsRead       <- queried.get
      yield assertTrue(
        result.isDefined,
        result.get._1.nodes.size == 2,         // treeC1 = root + 1 leaf, never treeC2's 3 nodes
        // The reported commit is the one the read resolved to, not the head at
        // return time: the scripted client advances the head to headC2 after
        // the first resolution, so reporting the current head would give headC2.
        result.get._2 == headC1,
        headResolutions == 1,                  // resolved once
        commitsRead == Set(headC1)             // every read pinned to the pre-advance commit
      )
    },

    test("getById on a nonexistent branch returns None (behaviour preservation)") {
      for
        setup             <- scripted(initialHead = None, advanceTo = None, store = Map.empty)
        (repo, _, _)       = setup
        result            <- repo.getById(wsId, treeIdF, Revision.Head(BranchRef.Main)).map(_.map(_._1))
      yield assertTrue(result.isEmpty)
    },

    test("getById fails as MetadataAbsent at the tree when meta is absent but nodes exist at the resolved commit") {
      val treeC1 = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      for
        setup             <- scripted(Some(headC1), advanceTo = None,
                               store = Map(headC1 -> storeWithoutMeta(treeC1)))
        (repo, _, _)       = setup
        exit              <- repo.getById(wsId, treeIdF, Revision.Head(BranchRef.Main)).exit
      yield assert(exit)(fails(equalTo(Failure.NotFound(Core(
        TrustZone.Peer(PeerZone.Store),
        NonEmptyList(ErrorLocation.Field(ErrorSubject.Tree(treeIdF), FieldName.Id) -> NotFoundKind.MetadataAbsent()),
        Nil
      )))))
    },

    test("getById fails as NodesAbsent at the tree when meta is stored and no node is") {
      val treeC1 = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val metaOnly = Map(WorkspaceStoragePaths.treeMeta(wsId, treeIdF) -> metaOf(treeC1).toJson)
      for
        setup             <- scripted(Some(headC1), advanceTo = None, store = Map(headC1 -> metaOnly))
        (repo, _, _)       = setup
        exit              <- repo.getById(wsId, treeIdF, Revision.Head(BranchRef.Main)).exit
      yield assert(exit)(fails(equalTo(Failure.NotFound(Core(
        TrustZone.Peer(PeerZone.Store),
        NonEmptyList(ErrorLocation.Field(ErrorSubject.Tree(treeIdF), FieldName.Nodes) -> NotFoundKind.NodesAbsent()),
        Nil
      )))))
    },

    test("getAllForWorkspace resolves ONE head for the whole listing and every read is pinned to it") {
      val treeF = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val treeG = tree(treeIdG, Seq(leaf(leaf1Id, 0.3)))
      for
        setup             <- scripted(Some(headC1), advanceTo = Some(headC2),
                               store = Map(headC1 -> storeOf(treeF, treeG), headC2 -> Map.empty))
        (repo, calls, queried) = setup
        result            <- repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main))
        headResolutions   <- calls.get
        commitsRead       <- queried.get
      yield assertTrue(
        result.map(_.id).toSet == Set(treeIdF, treeIdG),
        headResolutions == 1,
        commitsRead == Set(headC1)
      )
    },

    // ADR-036 confines WorkspaceId to the server: it must never reach a
    // client-visible surface. The storage path of a listed value the store does
    // not hold embeds it, so the path travels only as a diagnostic, which
    // reaches the log and never the wire. A node listed by listAtCommit but
    // absent from getAtCommit at the SAME pinned commit should not happen under
    // normal operation (Irmin's content at a fixed commit is immutable), but
    // nothing in the type system rules it out.
    test("a listed node whose value is missing fails the listing as StoredValueAbsent at that node, its path a diagnostic") {
      val treeF = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val leafPath = WorkspaceStoragePaths.of(wsId, TreePath.Node(treeIdF, leaf1Id))
      // listAtCommit lists leaf1Id as a child because this phantom key lies
      // below the leaf's own path, but no key equals that path itself, so
      // getAtCommit for it returns None.
      val tornStore = Map(WorkspaceStoragePaths.treeMeta(wsId, treeIdF) -> metaOf(treeF).toJson) +
        ((leafPath / IrminSegment("phantom")) -> "unreachable")
      for
        setup       <- scripted(Some(headC1), advanceTo = None, store = Map(headC1 -> tornStore))
        (repo, _, _) = setup
        exit        <- repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).contains(Failure.NotFound(Core(
          TrustZone.Peer(PeerZone.Store),
          NonEmptyList(ErrorLocation.Field(ErrorSubject.ExistingNode(leaf1Id), FieldName.Id) -> NotFoundKind.StoredValueAbsent()),
          List(Diagnostic.StoragePath(leafPath))
        ))),
        leafPath.value.contains(wsId.value)
      )
    },

    test("a listed tree with neither metadata nor nodes fails the listing as NodesAbsent at the tree") {
      val mitigationOnly = Map(
        WorkspaceStoragePaths.of(wsId, TreePath.Mitigation(treeIdF, mitigationId("m1"))) -> "unread"
      )
      for
        setup       <- scripted(Some(headC1), advanceTo = None, store = Map(headC1 -> mitigationOnly))
        (repo, _, _) = setup
        exit        <- repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).contains(Failure.NotFound(Core(
          TrustZone.Peer(PeerZone.Store),
          NonEmptyList(ErrorLocation.Field(ErrorSubject.Tree(treeIdF), FieldName.Nodes) -> NotFoundKind.NodesAbsent()),
          Nil
        )))
      )
    },

    test("a stored leaf with two refused fields fails the read with both, each at that node") {
      val treeF = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val store = storeOf(treeF) + (WorkspaceStoragePaths.of(wsId, TreePath.Node(treeIdF, leaf1Id)) -> refusedLeafJson(leaf1Id))
      for
        setup       <- scripted(Some(headC1), advanceTo = None, store = Map(headC1 -> store))
        (repo, _, _) = setup
        exit        <- repo.getById(wsId, treeIdF, Revision.Head(BranchRef.Main)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).contains(Failure.Rejected(Core(
          TrustZone.Peer(PeerZone.Store),
          NonEmptyList(
            ErrorLocation.nodeField(leaf1Id, FieldName.Name)        -> FieldProblem.Required,
            ErrorLocation.nodeField(leaf1Id, FieldName.Probability) -> FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval)
          ),
          Nil
        )))
      )
    },

    test("a stored leaf whose only refusal is its probability is refused as a leaf, not as an unreadable portfolio") {
      val treeF = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val badProbability = nodeJson(leaf(leaf1Id, 0.1)).replace("\"probability\":0.1", "\"probability\":1.5")
      val store = storeOf(treeF) + (WorkspaceStoragePaths.of(wsId, TreePath.Node(treeIdF, leaf1Id)) -> badProbability)
      for
        setup       <- scripted(Some(headC1), advanceTo = None, store = Map(headC1 -> store))
        (repo, _, _) = setup
        exit        <- repo.getById(wsId, treeIdF, Revision.Head(BranchRef.Main)).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).collect { case Failure.Rejected(core) => core.errors.toList }
          .contains(List(ErrorLocation.nodeField(leaf1Id, FieldName.Probability) -> FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval)))
      )
    },

    test("a listing holding two refused trees fails with every refusal, naming both trees") {
      val treeF = tree(treeIdF, Seq(leaf(leaf1Id, 0.1)))
      val treeG = tree(treeIdG, Seq(leaf(leaf1Id, 0.3)))
      val store = storeOf(treeF, treeG) +
        (WorkspaceStoragePaths.of(wsId, TreePath.Node(treeIdF, leaf1Id)) -> refusedLeafJson(leaf1Id)) +
        (WorkspaceStoragePaths.treeMeta(wsId, treeIdG) -> """{"name":"G","schemaVersion":2,"createdAt":0,"updatedAt":0}""")
      for
        setup       <- scripted(Some(headC1), advanceTo = None, store = Map(headC1 -> store))
        (repo, _, _) = setup
        exit        <- repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main)).exit
      yield
        val refusal = exit.causeOption.flatMap(_.failureOption).collect { case Failure.Rejected(core) => core }
        val atTreeG = ErrorSubject.Tree(treeIdG)
        assertTrue(
          refusal.map(_.zone).contains(TrustZone.Peer(PeerZone.Store)),
          refusal.map(_.errors.toList.map(_._1).toSet).contains(Set(
            ErrorLocation.nodeField(leaf1Id, FieldName.Name),
            ErrorLocation.nodeField(leaf1Id, FieldName.Probability),
            ErrorLocation.Field(atTreeG, FieldName.Id),
            ErrorLocation.Field(atTreeG, FieldName.RootId),
            ErrorLocation.Field(atTreeG, FieldName.SeedVarHighWater)
          )),
          refusal.map(_.diagnostics.toSet).contains(Set(Diagnostic.TreeRef(treeIdF), Diagnostic.TreeRef(treeIdG)))
        )
    },

    suite("a tree the workspace holds and the revision read lacks")(
      test("on main is the caller's NotFound at the branch header, naming main") {
        val rev = Revision.Head(BranchRef.Main)
        for exit <- RiskTreeRepository.treeAbsent(wsId, treeIdF, rev).exit
        yield assert(exit)(fails(equalTo(treeAbsentAt(
          ErrorLocation.Header(RequestHeaderName.Branch), NotFoundKind.TreeNotOnBranch(BranchChoice.Main), rev))))
      },

      test("on a scenario names the scenario on the wire and neither identifier") {
        // The internal branch reference embeds the workspace identifier; the
        // wire carries the client-facing branch name instead (ADR-036).
        val rev = Revision.Head(scenarioBranch)
        for failure <- RiskTreeRepository.treeAbsent(wsId, treeIdF, rev).flip
        yield
          val json = failure.toWire(TelemetryPlaceholders.UnlinkedTrace, TreeScope.InTree(treeIdF)) match
            case nf: WireError.NotFound => Some(nf.toJson)
            case _                      => None
          assertTrue(
            failure == treeAbsentAt(
              ErrorLocation.Header(RequestHeaderName.Branch), NotFoundKind.TreeNotOnBranch(BranchChoice.Scenario(scenario)), rev),
            json.exists(_.contains(scenario.value)),
            json.exists(j => !j.contains(wsId.value) && !j.contains(treeIdF.value))
          )
      },

      test("at a commit is the caller's NotFound at the pinned read, naming no commit on the wire") {
        val rev = Revision.At(headC1)
        for failure <- RiskTreeRepository.treeAbsent(wsId, treeIdF, rev).flip
        yield
          val json = failure.toWire(TelemetryPlaceholders.UnlinkedTrace, TreeScope.InTree(treeIdF)) match
            case nf: WireError.NotFound => Some(nf.toJson)
            case _                      => None
          assertTrue(
            failure == treeAbsentAt(ErrorLocation.QueryParameter(RequestQueryParam.At), NotFoundKind.TreeNotAtCommit(), rev),
            json.exists(j => !j.contains(headC1.value) && !j.contains(wsId.value))
          )
      },

      test("a branch that is not one of the workspace's dies") {
        val otherWs = WorkspaceId(safeId("consistency-other-ws"))
        val foreign = BranchRef.scenario(otherWs, scenario).toOption.get
        for exit <- RiskTreeRepository.treeAbsent(wsId, treeIdF, Revision.Head(foreign)).exit
        yield assertTrue(exit.isFailure, exit.causeOption.exists(_.isDie))
      },

      test("deleting a tree a scenario lacks fails with that NotFound before anything is written") {
        for
          setup        <- scripted(initialHead = None, advanceTo = None, store = Map.empty)
          (repo, _, _)  = setup
          exit         <- repo.delete(wsId, treeIdF, scenarioBranch).exit
        yield assert(exit)(fails(equalTo(treeAbsentAt(
          ErrorLocation.Header(RequestHeaderName.Branch),
          NotFoundKind.TreeNotOnBranch(BranchChoice.Scenario(scenario)),
          Revision.Head(scenarioBranch)
        ))))
      }
    )
  )

  private val scenario: ScenarioName.ScenarioName = ScenarioName.fromString("stress-2026").toOption.get
  private val scenarioBranch: BranchRef           = BranchRef.scenario(wsId, scenario).toOption.get

  /** The caller's NotFound for `treeIdF` absent at `rev`, at `location`. */
  private def treeAbsentAt(location: ErrorLocation, kind: NotFoundKind, rev: Revision): Failure =
    Failure.NotFound(Core(
      TrustZone.Client,
      NonEmptyList(location -> kind),
      List(Diagnostic.WorkspaceRef(wsId), Diagnostic.TreeRef(treeIdF), Diagnostic.RevisionRead(rev))
    ))

  /** A stored leaf whose name is blank and whose probability is above one. */
  private def refusedLeafJson(id: NodeId): String =
    nodeJson(leaf(id, 0.1))
      .replace("\"probability\":0.1", "\"probability\":1.5")
      .replace(s"\"name\":\"Leaf ${id.value}\"", "\"name\":\"\"")
