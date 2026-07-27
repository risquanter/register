package com.risquanter.register.services

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, RiskLeaf}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId, WorkspaceId, SeedVarId, PositiveInt, BranchRef, CommitHash, Revision}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId}
import io.github.iltotore.iron.*

/** E3/E4/E8 revert against a real Irmin: `RiskTreeRepository.revert` is a
  * FORWARD write — it restores the tree state at a target commit as one new
  * `:revert` commit, leaving every prior commit (including the superseded
  * state) reachable in history. Contrast the native head-set behaviour probed
  * by `IrminRevertSemanticsSpec`.
  */
object TreeRevertItSpec extends ZIOSpecDefault:

  private val wsId = WorkspaceId(safeId("revert-it-ws"))

  private def rootId  = nodeId("root")
  private def leaf1Id = nodeId("leaf-1")
  private def leaf2Id = nodeId("leaf-2")

  /** v1: root + two leaves (3 nodes). */
  private def treeV1(tid: TreeId): RiskTree =
    val root = RiskPortfolio.create(rootId.value, "Root", Array(leaf1Id, leaf2Id), None).toEither.toOption.get
    val l1 = RiskLeaf.create(id = leaf1Id.value, name = "Leaf 1", distributionType = "lognormal",
      probability = 0.1, minLoss = Some(1000L), maxLoss = Some(2000L), parentId = Some(rootId), seedVarId = 1L).toEither.toOption.get
    val l2 = RiskLeaf.create(id = leaf2Id.value, name = "Leaf 2", distributionType = "lognormal",
      probability = 0.2, minLoss = Some(1500L), maxLoss = Some(3000L), parentId = Some(rootId), seedVarId = 2L).toEither.toOption.get
    RiskTree(tid, SafeName.fromString("Revert Tree").toOption.get, Seq(root, l1, l2), rootId,
      TreeIndex.fromNodesUnsafe(Map(rootId -> root, leaf1Id -> l1, leaf2Id -> l2)), SeedVarId.fromLong(2L).toOption.get)

  /** v2: root + one leaf (leaf-2 pruned, 2 nodes). */
  private def treeV2(original: RiskTree): RiskTree =
    val root = RiskPortfolio.create(rootId.value, "Root", Array(leaf1Id), None).toEither.toOption.get
    val l1 = original.index.nodes(leaf1Id)
    RiskTree(original.id, original.name, Seq(root, l1), rootId,
      TreeIndex.fromNodesUnsafe(Map(rootId -> root, leaf1Id -> l1)), original.seedVarHighWater)

  private def treeRoot(id: TreeId): String = s"workspaces/${wsId.value}/risk-trees/${id.value}"
  private def positiveInt(n: Int): PositiveInt = n.refineUnsafe

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository & IrminClient] =
    ZLayer.make[RiskTreeRepository & IrminClient](
      IrminCompose.irminConfigLayer, IrminClientLive.layer, RiskTreeRepositoryIrmin.layer
    )

  override def spec =
    suite("TreeRevertItSpec")(
      test("revert restores an earlier state as a forward :revert commit, leaving history intact") {
        val tid = treeId("revert-tree")
        val v1  = treeV1(tid)
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          _       <- repo.create(wsId, v1, BranchRef.Main)
          histC   <- irmin.getHistory(IrminPath.unsafeFrom(s"${treeRoot(tid)}/meta"), positiveInt(20), BranchRef.Main)
          c1Hash  <- ZIO.fromEither(CommitHash.fromString(histC.head.hash)).mapError(e => new RuntimeException(e.mkString(", ")))
          _       <- repo.update(wsId, tid, _ => treeV2(v1), BranchRef.Main)
          histU   <- irmin.getHistory(IrminPath.unsafeFrom(s"${treeRoot(tid)}/meta"), positiveInt(20), BranchRef.Main)
          reverted <- repo.revert(wsId, tid, c1Hash, BranchRef.Main)
          loaded  <- repo.getById(wsId, tid, Revision.Head(BranchRef.Main))
          histR   <- irmin.getHistory(IrminPath.unsafeFrom(s"${treeRoot(tid)}/meta"), positiveInt(20), BranchRef.Main)
          headAfter <- irmin.mainBranch.map(_.flatMap(_.head))
        yield assertTrue(
          // restored to v1 — leaf-2 is back (3 nodes)
          loaded.exists(_.index.nodes.size == 3),
          reverted.index.nodes.size == 3,
          // forward write: history grew by exactly one over the post-update history
          histR.size == histU.size + 1,
          // the newest commit is the revert — last_modified gives no list-order
          // guarantee, so "newest" is read from the branch head, not list position
          headAfter.exists(_.info.message.endsWith(":revert")),
          // non-destructive: every pre-revert commit is still present in history
          histU.map(_.hash).toSet.subsetOf(histR.map(_.hash).toSet)
        )
      },

      test("reverting to a non-existent commit fails (NotFound), no write") {
        val tid = treeId("revert-absent")
        val v1  = treeV1(tid)
        val bogus = CommitHash.fromString("a" * 40).toOption.get
        for
          repo <- ZIO.service[RiskTreeRepository]
          _    <- repo.create(wsId, v1, BranchRef.Main)
          exit <- repo.revert(wsId, tid, bogus, BranchRef.Main).exit
        yield assertTrue(exit.isFailure)
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
