package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.{
  RiskTree, RiskLeaf, RiskPortfolio, Mitigation,
  MitigationTarget, MitigationSpec, MitigationPrecedence, TargetingPredicate,
  RiskLeafTransform, LikelihoodTransform, DistributionTransform
}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, WorkspaceId, SafeName, ContentHash, BranchRef, Revision}
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId, mitigationId}

/**
  * Integration tests for mitigation persistence through `RiskTreeRepositoryIrmin`
  * against a real Irmin container: a tree's mitigations are stored as per-id
  * blobs beside its nodes (§7.2.1) and round-trip through create/update/read with
  * their `overrideBaseStamp` intact. Omitting a previously-stored mitigation
  * deletes it (DD-7 subtree replace).
  *
  * Run: sbt "serverIt/testOnly *MitigationPersistenceItSpec"
  */
object MitigationPersistenceItSpec extends ZIOSpecDefault:

  private val wsId: WorkspaceId = WorkspaceId(safeId("mit-persist-ws"))

  private val rootId:  NodeId = nodeId("root")
  private val cyberId: NodeId = nodeId("cyber")

  private def sname(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  /** A fixed authoring-time base stamp; the round-trip cares that whatever stamp
    * was written survives, not its exact value. */
  private val stamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  private def overrideMit(label: String, anchor: NodeId): Mitigation =
    Mitigation.create(
      mitigationId(label), sname(label), pred,
      MitigationSpec.LeafStage(
        RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Keep),
        Some(stamp), Some(anchor)),
      MitigationPrecedence.overrideFinal
    ).toEither.toOption.get

  /** root-pf → {cyber}, carrying `mits`. */
  private def treeWith(tid: TreeId, mits: Mitigation*): RiskTree =
    val root = RiskPortfolio.create(
      id = rootId.value, name = "Root", childIds = Array(cyberId), parentId = None
    ).toEither.toOption.get
    val cyber = RiskLeaf.create(
      id = cyberId.value, name = "Cyber", distributionType = "lognormal",
      probability = 0.2, minLoss = Some(1000L), maxLoss = Some(100000L),
      parentId = Some(rootId), seedVarId = 1L
    ).toEither.toOption.get
    RiskTree.fromNodes(tid, sname("Mit Persist"), Seq(root, cyber), rootId, mitigations = mits.toList)
      .toEither.toOption.get

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository & IrminClient] =
    ZLayer.make[RiskTreeRepository & IrminClient](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer
    )

  override def spec =
    suite("MitigationPersistenceItSpec")(

      test("an override mitigation round-trips with its overrideBaseStamp intact") {
        val m = overrideMit("m-override", cyberId)
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tree    = treeWith(treeId("mit-tree-rt"), m)
          _      <- repo.create(wsId, tree, BranchRef.Main)
          loaded <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main))
        yield assertTrue(loaded.exists(_.mitigations == List(m)))
      },

      test("update replaces the mitigation list; a mitigation omitted from the update is deleted") {
        val m1 = overrideMit("m-first", cyberId)
        val m2 = overrideMit("m-second", cyberId)
        for
          repo    <- ZIO.service[RiskTreeRepository]
          tid      = treeId("mit-tree-update")
          _       <- repo.create(wsId, treeWith(tid, m1), BranchRef.Main)
          _       <- repo.update(wsId, tid, _ => treeWith(tid, m2), BranchRef.Main)
          replaced<- repo.getById(wsId, tid, Revision.Head(BranchRef.Main))
        yield assertTrue(replaced.exists(_.mitigations == List(m2)))
      },

      test("a tree written without mitigations reads back with none") {
        val m = overrideMit("m-drop", cyberId)
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tid     = treeId("mit-tree-drop")
          _      <- repo.create(wsId, treeWith(tid, m), BranchRef.Main)
          _      <- repo.update(wsId, tid, _ => treeWith(tid), BranchRef.Main)
          loaded <- repo.getById(wsId, tid, Revision.Head(BranchRef.Main))
        yield assertTrue(loaded.exists(_.mitigations.isEmpty))
      }

    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
