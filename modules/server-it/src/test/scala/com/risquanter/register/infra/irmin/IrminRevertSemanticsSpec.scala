package com.risquanter.register.infra.irmin

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, RiskLeaf}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId, WorkspaceId, SeedVarId, PositiveInt, BranchRef, CommitHash, Revision}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId}
import io.github.iltotore.iron.*

/** E3 probe: the PATCHED Irmin `revert` mutation is a HEAD-SET — it repoints the
  * branch head to the target commit rather than appending a forward commit.
  * This is why production revert (`RiskTreeRepository.revert`, exercised by
  * `TreeRevertItSpec`) does NOT use it: a head-set drops the intervening head
  * from the branch's linear history. This spec pins the native semantics so a
  * future Irmin change that alters them is caught. */
object IrminRevertSemanticsSpec extends ZIOSpecDefault:

  private val wsId = WorkspaceId(safeId("native-revert-ws"))
  private def rootId  = nodeId("root")
  private def leaf1Id = nodeId("leaf-1")
  private def leaf2Id = nodeId("leaf-2")

  private def treeV1(tid: TreeId): RiskTree =
    val root = RiskPortfolio.create(rootId.value, "Root", Array(leaf1Id, leaf2Id), None).toEither.toOption.get
    val l1 = RiskLeaf.create(id = leaf1Id.value, name = "Leaf 1", distributionType = "lognormal",
      probability = 0.1, minLoss = Some(1000L), maxLoss = Some(2000L), parentId = Some(rootId), seedVarId = 1L).toEither.toOption.get
    val l2 = RiskLeaf.create(id = leaf2Id.value, name = "Leaf 2", distributionType = "lognormal",
      probability = 0.2, minLoss = Some(1500L), maxLoss = Some(3000L), parentId = Some(rootId), seedVarId = 2L).toEither.toOption.get
    RiskTree(tid, SafeName.fromString("Native Revert Tree").toOption.get, Seq(root, l1, l2), rootId,
      TreeIndex.fromNodesUnsafe(Map(rootId -> root, leaf1Id -> l1, leaf2Id -> l2)), SeedVarId.fromLong(2L).toOption.get)

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
    suite("IrminRevertSemanticsSpec")(
      test("native revert repoints the branch head to the target commit (head-set), prior head still reachable") {
        val tid = treeId("native-revert-tree")
        val v1  = treeV1(tid)
        for
          repo       <- ZIO.service[RiskTreeRepository]
          irmin      <- ZIO.service[IrminClient]
          _          <- repo.create(wsId, v1, BranchRef.Main)
          histC      <- irmin.getHistory(IrminPath.unsafeFrom(s"${treeRoot(tid)}/meta"), positiveInt(20), BranchRef.Main)
          c1Hash     <- ZIO.fromEither(CommitHash.fromString(histC.head.hash)).mapError(e => new RuntimeException(e.mkString(", ")))
          _          <- repo.update(wsId, tid, _ => treeV2(v1), BranchRef.Main)
          headBefore <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          // native head-set revert (NOT the production forward-write path)
          _          <- irmin.revert(c1Hash, BranchRef.Main)
          headAfter  <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          loaded     <- repo.getById(wsId, tid, Revision.Head(BranchRef.Main))
          // the pre-revert head remains reachable by hash — a head-set repoints, it does not destroy commits
          priorStill <- irmin.getCommit(CommitHash.fromString(headBefore.getOrElse("")).toOption.get)
        yield assertTrue(
          loaded.exists(_.index.nodes.size == 3),        // head now reads c1's restored content
          headAfter.contains(c1Hash.value),              // head-set: head IS the target commit
          !headAfter.exists(headBefore.contains),        // head moved off c2
          priorStill.isDefined                           // c2 still reachable by hash
        )
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
