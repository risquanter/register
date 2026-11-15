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
import com.risquanter.register.http.requests.{
  AbsoluteReplacementRequest, BaseStampChoice, DistributionShapeRequest, MitigationDefinitionRequest,
  MitigationSpecRequest, MitigationUpdateRequest, RiskLeafDefinitionRequest, RiskLeafUpdateRequest,
  RiskPortfolioDefinitionRequest, RiskPortfolioUpdateRequest, RiskTreeDefinitionRequest, RiskTreeUpdateRequest
}
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
  * mitigation deletes it, because a write replaces the whole subtree. A
  * mitigation written through `RiskTreeService`'s tree PUT also reads back
  * through the service: the structure read
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
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)", ErrorSubject.Request).toEither.toOption.get)

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

  private val CyberProbability: Double       = 0.2
  private val ReEstimatedProbability: Double = 0.4

  private val cyberShape: DistributionShapeRequest = DistributionShapeRequest.Lognormal(minLoss = 1000L, maxLoss = 100000L)

  /** Root → {Cyber}, submitted through the service with no mitigations. */
  private val serviceTreeRequest: RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = "Mit Persist Service",
      portfolios = Seq(RiskPortfolioDefinitionRequest(name = "Root", parentName = None)),
      leaves = Seq(RiskLeafDefinitionRequest(
        name = "Cyber", parentName = Some("Root"), probability = CyberProbability,
        distributionShape = cyberShape, seedVarId = None)),
      newMitigations = Nil
    )

  private def serviceLeaf(tree: RiskTree): RiskLeaf =
    tree.nodes.collectFirst { case l: RiskLeaf => l }.get

  /** `tree` resubmitted with its leaf `cyber` at `probability`, keeping `mitigations`. */
  private def keepTree(tree: RiskTree, cyber: RiskLeaf, probability: Double,
                       mitigations: Seq[MitigationUpdateRequest]): RiskTreeUpdateRequest =
    RiskTreeUpdateRequest(
      name = tree.name.value,
      portfolios = Seq(RiskPortfolioUpdateRequest(id = tree.rootId.value, name = "Root", parentName = None)),
      leaves = Seq(RiskLeafUpdateRequest(
        id = cyber.id.value, name = "Cyber", parentName = Some("Root"), probability = probability,
        distributionShape = cyberShape, seedVarId = None)),
      newPortfolios = Nil,
      newLeaves = Nil,
      mitigations = mitigations,
      newMitigations = Nil
    )

  /** A new absolute statement that the leaf `anchor` occurs with probability 0.05. */
  private def pinRequest(anchor: NodeId): MitigationDefinitionRequest =
    MitigationDefinitionRequest(
      name = "m-service", target = "leaf(x)",
      spec = MitigationSpecRequest.LeafAbsolute(
        AbsoluteReplacementRequest.Likelihood(0.05), anchor.value, BaseStampChoice.KeepIfUnchanged),
      precedence = MitigationPrecedence.overrideFinal
    )

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

      test("a mitigation written through the tree PUT reads back through the service with its resolved scope, and a curve read under it names it") {
        // The PUT stamps the statement from the anchor leaf. Re-estimating that
        // leaf in a second PUT that keeps the statement unchanged makes it stale.
        for
          service   <- ZIO.service[RiskTreeService]
          created   <- service.create(wsId, serviceTreeRequest, BranchRef.Main)
          cyber      = serviceLeaf(created)
          pinned    <- service.update(wsId, created.id, keepTree(created, cyber, CyberProbability, Nil)
                         .copy(newMitigations = List(pinRequest(cyber.id))), BranchRef.Main)
          m          = pinned.mitigations.head
          _         <- service.update(wsId, created.id,
                         keepTree(pinned, cyber, ReEstimatedProbability, pinned.mitigations.map(MitigationUpdateRequest.fromMitigation)),
                         BranchRef.Main)
          structure <- service.getStructure(wsId, created.id, BranchRef.Main, None)
          (tree, scopes, stale) = structure
          curves    <- service.getLECCurvesMulti(wsId, created.id, Set(created.rootId), seedEntity,
                         false, BranchRef.Main, None, false, MitigationSelection.single(m.id))
        yield assertTrue(
          tree.mitigations.map(_.id) == List(m.id),
          scopes.outcomes == Map(m.id -> ScopeOutcome.Resolved(Set(cyber.id))),
          stale == Set(m.id),
          curves(created.rootId).map(_.withMitigations) == List(Nil, List(m.id))
        )
      }

    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
