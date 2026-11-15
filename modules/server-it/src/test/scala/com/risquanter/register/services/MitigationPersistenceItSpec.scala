package com.risquanter.register.services

import zio.*
import zio.test.*

import com.risquanter.register.auth.{Authority, Checked, TestChecked}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.{
  RiskTree, RiskLeaf, RiskPortfolio, Mitigation,
  MitigationTarget, MitigationSpec, MitigationPrecedence, MitigationSelection, TargetingPredicate,
  AbsoluteReplacement, ScopeOutcome
}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, WorkspaceId, SafeName, ContentHash, BranchRef, Revision, SeedEntityId}
import com.risquanter.register.domain.errors.ErrorSubject
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.services.cache.{CachedResultResolverLive, ContentCacheRegistry, MitigationScopeResolverRegistry}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId, mitigationId, lognormalShape, residualProbability}

/**
  * Integration tests for mitigation persistence through `RiskTreeRepositoryIrmin`
  * against a real Irmin container: a tree's mitigations are stored as per-id
  * blobs beside its nodes and round-trip through create/update/read with their
  * authoring-time anchor and base stamp intact. Omitting a previously-stored
  * mitigation deletes it, because a write replaces the whole subtree. A stored
  * mitigation also reads back through `RiskTreeService`: the structure read
  * answers its resolved scope and its staleness, and a curve read under a
  * selection naming it names it on the reading it reached.
  *
  * Run: sbt "serverIt/testOnly *MitigationPersistenceItSpec"
  */
object MitigationPersistenceItSpec extends ZIOSpecDefault:

  private given Checked[Authority] = TestChecked.value

  private val wsId: WorkspaceId = WorkspaceId(safeId("mit-persist-ws"))
  private val seedEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

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
      MitigationSpec.LeafAbsolute(
        AbsoluteReplacement.Likelihood(residualProbability(0.05)), anchor, stamp),
      MitigationPrecedence.overrideFinal
    ).toEither.toOption.get

  /** root-pf → {cyber}, carrying `mits`. */
  private def treeWith(tid: TreeId, mits: Mitigation*): RiskTree =
    val root = RiskPortfolio.create(
      id = rootId.value, name = "Root", childIds = Vector(cyberId), parentId = None,
      subject = ErrorSubject.Request
    ).toEither.toOption.get
    val cyber = RiskLeaf.create(
      subject = ErrorSubject.Request,
      id = cyberId.value, name = "Cyber", probability = 0.2,
      shape = lognormalShape(1000L, 100000L),
      parentId = Some(rootId), seedVarId = 1L
    ).toEither.toOption.get
    RiskTree.fromNodes(tid, sname("Mit Persist"), Seq(root, cyber), rootId, seedVarHighWater = None, mitigations = mits.toList)
      .toEither.toOption.get

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository & IrminClient & RiskTreeService] =
    ZLayer.make[RiskTreeRepository & IrminClient & RiskTreeService](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer,
      RiskTreeServiceLive.layer,
      TestConfigs.simulationLayer,
      CachedResultResolverLive.layer,
      ContentCacheRegistry.layer,
      MitigationScopeResolverRegistry.layer,
      InvalidationHandler.live,
      SSEHub.live,
      TestConfigs.telemetryLayer >>> TelemetryLive.configured
    )

  override def spec =
    suite("MitigationPersistenceItSpec")(

      test("an override mitigation round-trips with its overrideBaseStamp intact") {
        val m = overrideMit("m-override", cyberId)
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tree    = treeWith(treeId("mit-tree-rt"), m)
          _      <- repo.create(wsId, tree, BranchRef.Main)
          loaded <- repo.getById(wsId, tree.id, Revision.Head(BranchRef.Main)).map(_.map(_._1))
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
          replaced<- repo.getById(wsId, tid, Revision.Head(BranchRef.Main)).map(_.map(_._1))
        yield assertTrue(replaced.exists(_.mitigations == List(m2)))
      },

      test("a tree written without mitigations reads back with none") {
        val m = overrideMit("m-drop", cyberId)
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tid     = treeId("mit-tree-drop")
          _      <- repo.create(wsId, treeWith(tid, m), BranchRef.Main)
          _      <- repo.update(wsId, tid, _ => treeWith(tid), BranchRef.Main)
          loaded <- repo.getById(wsId, tid, Revision.Head(BranchRef.Main)).map(_.map(_._1))
        yield assertTrue(loaded.exists(_.mitigations.isEmpty))
      },

      test("multiple mitigations all round-trip intact, independent of stored order") {
        // Resolution is precedence-driven (MitigationApplication.inPrecedenceOrder
        // re-sorts by (precedence.key, id)), so the stored list order carries no
        // semantics: the fidelity that matters is that every mitigation survives
        // with its fields intact. Compared in id-sorted canonical form so the
        // assertion does not depend on Irmin's child-listing order.
        val mA = overrideMit("m-alpha", cyberId)
        val mZ = overrideMit("m-zebra", cyberId)
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tid     = treeId("mit-tree-multi")
          _      <- repo.create(wsId, treeWith(tid, mA, mZ), BranchRef.Main)
          loaded <- repo.getById(wsId, tid, Revision.Head(BranchRef.Main)).map(_.map(_._1))
        yield assertTrue(
          loaded.exists(_.mitigations.sortBy(_.id.value) == List(mA, mZ).sortBy(_.id.value))
        )
      },

      test("a stored mitigation reads back through the service with its resolved scope, and a curve read under it names it") {
        // The fixed base stamp matches no leaf, so the override is also stale.
        val m = overrideMit("m-service", cyberId)
        for
          repo      <- ZIO.service[RiskTreeRepository]
          tid        = treeId("mit-tree-service")
          _         <- repo.create(wsId, treeWith(tid, m), BranchRef.Main)
          structure <- ZIO.serviceWithZIO[RiskTreeService](_.getStructure(wsId, tid, BranchRef.Main, None))
          (tree, scopes, stale) = structure
          curves    <- ZIO.serviceWithZIO[RiskTreeService](_.getLECCurvesMulti(wsId, tid, Set(rootId), seedEntity,
                         false, BranchRef.Main, None, false, MitigationSelection.single(m.id)))
        yield assertTrue(
          tree.mitigations == List(m),
          scopes.outcomes == Map(m.id -> ScopeOutcome.Resolved(Set(cyberId))),
          stale == Set(m.id),
          curves(rootId).map(_.withMitigations) == List(Nil, List(m.id))
        )
      }

    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
