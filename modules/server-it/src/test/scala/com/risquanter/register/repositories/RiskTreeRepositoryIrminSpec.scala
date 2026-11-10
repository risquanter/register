package com.risquanter.register.repositories

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.prelude.NonEmptyList
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NodeId, TreeId, WorkspaceId, SeedVarId, PositiveInt, BranchRef, Revision, Url}
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, ErrorSubject, Failure, FieldName, FieldProblem, OutageMode, PeerZone, TrustZone, ValueRange
}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.http.errors.NotFoundKind
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, IrminClientLive}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.domain.data.RiskPortfolio
import com.risquanter.register.domain.data.RiskLeaf
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId, lognormalShape}
import io.github.iltotore.iron.*

/**
  * Integration tests for RiskTreeRepositoryIrmin against a real Irmin container.
  *
  * PREREQUISITES:
  *   docker compose --profile persistence up -d
  *
  * Runs only when IRMIN_URL is set (e.g., http://localhost:9080).
  */
object RiskTreeRepositoryIrminSpec extends ZIOSpecDefault:

  private val wsId: WorkspaceId = WorkspaceId(safeId("test-irmin-ws"))

  private def sampleTree(tid: TreeId, treeName: String): RiskTree =
    val rootId  = nodeId("root")
    val leaf1Id = nodeId("leaf-1")
    val leaf2Id = nodeId("leaf-2")

    val portfolio = RiskPortfolio.create(subject = ErrorSubject.Request,
      id = rootId.value,
      name = "Root",
      childIds = Vector(leaf1Id, leaf2Id),
      parentId = None
    ).toEither.toOption.get

    val leaf1 = RiskLeaf.create(subject = ErrorSubject.Request,
      id = leaf1Id.value,
      name = "Leaf 1",
      probability = 0.1,
      shape = lognormalShape(1000L, 2000L),
      parentId = Some(rootId),
      seedVarId = 1L
    ).toEither.toOption.get

    val leaf2 = RiskLeaf.create(subject = ErrorSubject.Request,
      id = leaf2Id.value,
      name = "Leaf 2",
      probability = 0.2,
      shape = lognormalShape(1500L, 3000L),
      parentId = Some(rootId),
      seedVarId = 2L
    ).toEither.toOption.get

    RiskTree.fromNodesUnsafe(
      id = tid,
      name = SafeName.fromString(treeName).toOption.get,
      nodes = Seq(portfolio, leaf1, leaf2),
      rootId = rootId,
      seedVarHighWater = Some(SeedVarId.fromLong(2L).toOption.get),
      mitigations = Nil
    )

  private def updatedTree(original: RiskTree): RiskTree =
    val rootId  = original.rootId
    val leaf1Id = nodeId("leaf-1")
    val root    = original.index.nodes(rootId).asInstanceOf[RiskPortfolio]
    val leaf1   = original.index.nodes(leaf1Id)
    val newRoot = RiskPortfolio.create(subject = ErrorSubject.Request,
      id = root.id.value,
      name = root.name.value,
      childIds = Vector(leaf1Id),
      parentId = None
    ).toEither.toOption.get
    RiskTree.fromNodesUnsafe(
      id = original.id,
      name = original.name,
      nodes = Seq(newRoot, leaf1),
      rootId = rootId,
      seedVarHighWater = Some(original.seedVarHighWater),
      mitigations = original.mitigations
    )

  /** Identity-preserving edit: change leaf-1's `minLoss` while keeping every NodeId
    * (and therefore every Irmin node path) unchanged. Models the frontend
    * identity-preserving update path at the repository boundary. */
  private def editLeaf1MinLoss(original: RiskTree, newMin: Long): RiskTree =
    val rootId  = original.rootId
    val leaf1Id = nodeId("leaf-1")
    val leaf2Id = nodeId("leaf-2")
    val root    = original.index.nodes(rootId).asInstanceOf[RiskPortfolio]
    val leaf2   = original.index.nodes(leaf2Id)
    val newLeaf1 = RiskLeaf.create(subject = ErrorSubject.Request,
      id = leaf1Id.value,          // SAME id → same path nodes/{leaf-1}
      name = "Leaf 1",
      probability = 0.1,
      shape = lognormalShape(newMin, 2000L),
      parentId = Some(rootId),
      seedVarId = 1L               // identity-preserving: same leaf keeps its stream
    ).toEither.toOption.get
    RiskTree.fromNodesUnsafe(
      id = original.id,
      name = original.name,
      nodes = Seq(root, newLeaf1, leaf2),
      rootId = rootId,
      seedVarHighWater = Some(original.seedVarHighWater),
      mitigations = original.mitigations
    )

  private def positiveInt(n: Int): PositiveInt = n.refineUnsafe

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository & IrminClient] =
    ZLayer.make[RiskTreeRepository & IrminClient](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer
    )

  override def spec =
    suite("RiskTreeRepositoryIrminSpec")(
      test("a read whose store does not answer fails with that outage, not with a repository failure") {
        // Port 1 is reserved and nothing listens on it, so the connection is refused.
        val deadConfig = IrminConfig(
          url                          = Url.fromString("http://localhost:1").toOption.get,
          conflictScanFetchParallelism = positiveInt(1)
        )
        ZIO.scoped {
          (ZLayer.succeed(deadConfig) >>> IrminClientLive.layer >>> RiskTreeRepositoryIrmin.layer).build
            .flatMap(_.get[RiskTreeRepository].getById(wsId, treeId("unanswered"), Revision.Head(BranchRef.Main)).either)
        }.map {
          case Left(Failure.Unanswered(Core(PeerZone.Store, errors, _))) =>
            assertTrue(errors.head._2 == OutageMode.ConnectionRefused)
          case _ => assertTrue(false)
        }
      },

      test("create and get roundtrip with metadata") {
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tree    = sampleTree(treeId("tree-1"), "Tree One")
          _      <- repo.create(wsId, tree, BranchRef.Main)
          loaded <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main)).map(_.map(_._1))
        yield assertTrue(loaded.exists(_.index.nodes.size == tree.index.nodes.size))
      },

      test("update prunes removed nodes") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(treeId("tree-2"), "Tree Two")
          _    <- repo.create(wsId, tree, BranchRef.Main)
          _    <- repo.update(wsId, tree.id, _ => updatedTree(tree), BranchRef.Main)
          got  <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main)).map(_.map(_._1))
          leaf2Id = nodeId("leaf-2")
        yield assertTrue(got.exists(!_.index.nodes.contains(leaf2Id)))
      },

      test("list returns created trees") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(treeId("tree-3"), "Tree Three")
          _    <- repo.create(wsId, tree, BranchRef.Main)
          all  <- repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main))
        yield assertTrue(all.exists(_.id == tree.id))
      },

      test("delete removes tree") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(treeId("tree-4"), "Tree Four")
          _    <- repo.create(wsId, tree, BranchRef.Main)
          _    <- repo.delete(wsId, tree.id, BranchRef.Main)
          res  <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main)).map(_.map(_._1))
        yield assertTrue(res.isEmpty)
      },

      // Every repository write action is exactly ONE Irmin commit whose
      // message names the user action — the commit log IS the user history.
      test("create is one commit covering meta and all nodes, with the action message") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-dd7-create"), "DD7 Create")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree, BranchRef.Main)
          hMeta   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/meta"), positiveInt(10))
          hNode   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/nodes/${nodeId("leaf-1").value}"), positiveInt(10))
        yield assertTrue(
          hMeta.map(_.hash) == hNode.map(_.hash),   // same single commit wrote meta and node
          hMeta.size == 1,
          hMeta.headOption.exists(_.info.message == CommitMessage.TreeWritten(wsId, tree.id, TreeWriteKind.Create))
        )
      },

      test("update is one commit; node removal happens in that same commit") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-dd7-update"), "DD7 Update")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree, BranchRef.Main)
          _       <- repo.update(wsId, tree.id, _ => updatedTree(tree), BranchRef.Main)
          hMeta   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/meta"), positiveInt(10))
          leaf2   <- irmin.get(IrminPath.unsafeFrom(s"$base/nodes/${nodeId("leaf-2").value}"))
        yield assertTrue(
          hMeta.size == 2,                          // create + update, nothing else
          hMeta.map(_.info.message).toSet == Set(
            CommitMessage.TreeWritten(wsId, tree.id, TreeWriteKind.Create),
            CommitMessage.TreeWritten(wsId, tree.id, TreeWriteKind.Update)
          ),
          leaf2.isEmpty                             // pruned by subtree replace, no extra commit
        )
      },

      // getHistory returns a tree's commits oldest-first, which maps directly to
      // the slider's left-to-right fill (left = initial, right = latest). The
      // order is the transaction chain: each entry is the sole parent of the next.
      test("history is returned oldest-first in ancestry (transaction) order") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-hist-order"), "Hist Order")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree, BranchRef.Main)
          _       <- repo.update(wsId, tree.id, _ => updatedTree(tree), BranchRef.Main)
          hMeta   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/meta"), positiveInt(10))
        yield assertTrue(
          hMeta.size == 2,
          hMeta.zip(hMeta.drop(1)).forall((older, newer) => newer.parents == List(older.hash))
        )
      },

      // The same-second caveat matters for automated/scripted callers that fire
      // many writes to one tree with no clock delay. Force a rapid burst (commits
      // sharing a wall-clock second) and prove the returned history is still a
      // valid oldest-first ancestry chain — i.e. not scrambled by same-second ties.
      test("history stays ancestry-ordered under a rapid same-second write burst") {
        val tid  = treeId("tree-hist-burst")
        val full = sampleTree(tid, "Burst")
        val base = s"workspaces/${wsId.value}/risk-trees/${tid.value}"
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          _       <- repo.create(wsId, full, BranchRef.Main)
          _       <- ZIO.foreachDiscard(1 to 12)(i =>
                       repo.update(wsId, tid, _ => if i % 2 == 0 then full else updatedTree(full), BranchRef.Main))
          hMeta   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/meta"), positiveInt(50))
        yield assertTrue(
          hMeta.size == 13,
          hMeta.zip(hMeta.drop(1)).forall((older, newer) => newer.parents == List(older.hash))
        )
      },

      test("delete is one commit that leaves no residue under the tree path") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-dd7-delete"), "DD7 Delete")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree, BranchRef.Main)
          headC   <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          _       <- repo.delete(wsId, tree.id, BranchRef.Main)
          headD   <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          res     <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main)).map(_.map(_._1))
          residue <- irmin.list(IrminPath.unsafeFrom(base))
        yield assertTrue(
          res.isEmpty,
          residue.isEmpty,                          // no meta, no nodes dir, nothing
          headC != headD,
          headD.isDefined
        )
      },

      // Git-semantics guarantee that later enables path-scoped time travel:
      // an identity-preserving edit rewrites the node at the SAME path and records a
      // NEW commit on top of the old one — it does not delete the node and recreate it
      // under a fresh id (which would sever the path's commit lineage).
      test("identity-preserving update rewrites a node in place at the same path and advances commit history") {
        for
          repo       <- ZIO.service[RiskTreeRepository]
          irmin      <- ZIO.service[IrminClient]
          tree        = sampleTree(treeId("tree-git"), "Git Tree")
          leaf1Id     = nodeId("leaf-1")
          leafPath    = IrminPath.unsafeFrom(
                          s"workspaces/${wsId.value}/risk-trees/${tree.id.value}/nodes/${leaf1Id.value}"
                        )
          _          <- repo.create(wsId, tree, BranchRef.Main)
          before     <- irmin.get(leafPath)
          headBefore <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          _          <- repo.update(wsId, tree.id, t => editLeaf1MinLoss(t, 1234L), BranchRef.Main)
          after      <- irmin.get(leafPath)
          headAfter  <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          reloaded   <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main)).map(_.map(_._1))
        yield assertTrue(
          before.isDefined,                                 // node was stored at nodes/{id}
          after.isDefined,                                  // STILL at the same path after edit
          before != after,                                  // content changed (minLoss 1000 → 1234)
          headBefore.isDefined,
          headAfter.isDefined,
          headBefore != headAfter,                          // a new commit → the path has lineage
          reloaded.exists(_.index.nodes.contains(leaf1Id))  // NodeId preserved across the update
        )
      },

      // The three store-refusal tests write content this service would never
      // write, so they use a workspace of their own and no other test lists it.
      test("a stored leaf with two refused fields fails the read with both, each at that node") {
        val tid = treeId("tree-refused-leaf")
        for
          repo  <- ZIO.service[RiskTreeRepository]
          irmin <- ZIO.service[IrminClient]
          _     <- repo.create(refusalWs, sampleTree(tid, "Refused Leaf"), BranchRef.Main)
          _     <- corruptLeaf1(irmin, tid, refuseName = true)
          exit  <- repo.getById(refusalWs, tid, Revision.Head(BranchRef.Main)).exit
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).contains(Failure.Rejected(Core(
            TrustZone.Peer(PeerZone.Store),
            NonEmptyList(
              ErrorLocation.nodeField(nodeId("leaf-1"), FieldName.Name)        -> FieldProblem.Required,
              ErrorLocation.nodeField(nodeId("leaf-1"), FieldName.Probability) -> FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval)
            ),
            Nil
          )))
        )
      },

      test("a stored leaf whose only refusal is its probability is refused as a leaf") {
        val tid = treeId("tree-refused-probability")
        for
          repo  <- ZIO.service[RiskTreeRepository]
          irmin <- ZIO.service[IrminClient]
          _     <- repo.create(refusalWs, sampleTree(tid, "Refused Probability"), BranchRef.Main)
          _     <- corruptLeaf1(irmin, tid, refuseName = false)
          exit  <- repo.getById(refusalWs, tid, Revision.Head(BranchRef.Main)).exit
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).collect { case Failure.Rejected(core) => core.errors.toList }
            .contains(List(ErrorLocation.nodeField(nodeId("leaf-1"), FieldName.Probability) -> FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval)))
        )
      },

      test("a listing holding refused trees fails with every refusal, a tree's own fields at that tree") {
        val tid = treeId("tree-refused-meta")
        val metaPath = IrminPath.unsafeFrom(s"workspaces/${refusalWs.value}/risk-trees/${tid.value}/meta")
        for
          repo  <- ZIO.service[RiskTreeRepository]
          irmin <- ZIO.service[IrminClient]
          _     <- repo.create(refusalWs, sampleTree(tid, "Refused Meta"), BranchRef.Main)
          _     <- irmin.set(metaPath, """{"name":"Refused Meta","schemaVersion":2,"createdAt":0,"updatedAt":0}""",
                     CommitMessage.TreeWritten(refusalWs, tid, TreeWriteKind.Update), BranchRef.Main)
          exit  <- repo.getAllForWorkspace(refusalWs, Revision.Head(BranchRef.Main)).exit
        yield
          val refusal = exit.causeOption.flatMap(_.failureOption).collect { case Failure.Rejected(core) => core }
          val atTree  = ErrorSubject.Tree(tid)
          assertTrue(
            refusal.map(_.zone).contains(TrustZone.Peer(PeerZone.Store)),
            refusal.exists(_.errors.toList.map(_._1).toSet.subsetOf(Set(
              ErrorLocation.nodeField(nodeId("leaf-1"), FieldName.Name),
              ErrorLocation.nodeField(nodeId("leaf-1"), FieldName.Probability),
              ErrorLocation.Field(atTree, FieldName.Id),
              ErrorLocation.Field(atTree, FieldName.RootId),
              ErrorLocation.Field(atTree, FieldName.SeedVarHighWater)
            ))),
            refusal.exists(_.errors.toList.map(_._1).contains(ErrorLocation.Field(atTree, FieldName.RootId))),
            refusal.map(_.diagnostics.toSet).contains(Set(
              Diagnostic.TreeRef(treeId("tree-refused-leaf")),
              Diagnostic.TreeRef(treeId("tree-refused-probability")),
              Diagnostic.TreeRef(tid)
            ))
          )
      },

      test("a tree whose nodes are stored and whose metadata is not fails the read as MetadataAbsent at the tree") {
        val tid = treeId("tree-meta-absent")
        for
          repo  <- ZIO.service[RiskTreeRepository]
          irmin <- ZIO.service[IrminClient]
          _     <- repo.create(absentWs, sampleTree(tid, "Meta Absent"), BranchRef.Main)
          _     <- irmin.remove(storedPath(tid, "meta"), CommitMessage.TreeWritten(absentWs, tid, TreeWriteKind.Update), BranchRef.Main)
          exit  <- repo.getById(absentWs, tid, Revision.Head(BranchRef.Main)).exit
        yield assert(exit)(fails(equalTo(Failure.NotFound(Core(
          TrustZone.Peer(PeerZone.Store),
          NonEmptyList(ErrorLocation.Field(ErrorSubject.Tree(tid), FieldName.Id) -> NotFoundKind.MetadataAbsent()),
          Nil
        )))))
      },

      test("a tree whose metadata is stored and whose nodes are not fails the read as NodesAbsent at the tree") {
        val tid = treeId("tree-nodes-absent")
        for
          repo  <- ZIO.service[RiskTreeRepository]
          irmin <- ZIO.service[IrminClient]
          _     <- repo.create(absentWs, sampleTree(tid, "Nodes Absent"), BranchRef.Main)
          _     <- ZIO.foreachDiscard(List("root", "leaf-1", "leaf-2"))(node =>
                     irmin.remove(storedPath(tid, s"nodes/${nodeId(node).value}"),
                       CommitMessage.TreeWritten(absentWs, tid, TreeWriteKind.Update), BranchRef.Main))
          exit  <- repo.getById(absentWs, tid, Revision.Head(BranchRef.Main)).exit
        yield assert(exit)(fails(equalTo(Failure.NotFound(Core(
          TrustZone.Peer(PeerZone.Store),
          NonEmptyList(ErrorLocation.Field(ErrorSubject.Tree(tid), FieldName.Nodes) -> NotFoundKind.NodesAbsent()),
          Nil
        )))))
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  /** The workspace the store-refusal tests write into. */
  private val refusalWs: WorkspaceId = WorkspaceId(safeId("test-irmin-refusals"))

  /** The workspace the absent-content tests write into, apart from the
    * refusal tests so their listing sees only the trees they refused. */
  private val absentWs: WorkspaceId = WorkspaceId(safeId("test-irmin-absent"))

  /** A path under one tree of `absentWs`. */
  private def storedPath(tid: TreeId, within: String): IrminPath =
    IrminPath.unsafeFrom(s"workspaces/${absentWs.value}/risk-trees/${tid.value}/$within")

  /** Rewrite leaf-1 of `tid` in place with a probability above one, and with a
    * blank name when `refuseName` holds. */
  private def corruptLeaf1(irmin: IrminClient, tid: TreeId, refuseName: Boolean) =
    val leafPath = IrminPath.unsafeFrom(s"workspaces/${refusalWs.value}/risk-trees/${tid.value}/nodes/${nodeId("leaf-1").value}")
    for
      stored <- irmin.get(leafPath).someOrFailException
      bad     = stored.replace("\"probability\":0.1", "\"probability\":1.5")
      written = if refuseName then bad.replace("\"name\":\"Leaf 1\"", "\"name\":\"\"") else bad
      _      <- irmin.set(leafPath, written, CommitMessage.TreeWritten(refusalWs, tid, TreeWriteKind.Update), BranchRef.Main)
    yield ()