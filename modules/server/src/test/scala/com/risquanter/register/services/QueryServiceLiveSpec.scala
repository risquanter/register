package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.auth.{Authority, Checked, TestChecked}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.{
  RiskLeaf, RiskPortfolio, RiskNode, RiskTree,
  Mitigation, MitigationTarget, MitigationPrecedence, TargetingPredicate,
  ResultTransformSpec
}
import com.risquanter.register.mitigation.{MitigationSelection, ScopeRestriction}
import com.risquanter.register.simulation.LossDistribution
import com.risquanter.register.testutil.RiskResultTestSupport.leafOf
import com.risquanter.register.domain.data.iron.{
  BranchRef, CommitHash, NodeId, MitigationId, Revision, SafeName, SeedEntityId, SeedVarId, TreeId, WorkspaceId
}

import com.risquanter.register.domain.errors.{
  AppError, ErrorLocation, ErrorSubject, Failure, FieldProblem, SimulationFailure, TrustZone, ValidationError
}
import com.risquanter.register.http.errors.QueryProblem
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.services.cache.{CachedResultResolver, MitigationScopeResolverRegistry}
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.testutil.TestHelpers
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

import vql.parser.VagueQueryParser
import vql.semantics.VagueSemantics
import vql.typed.{FolModel, QueryBinder}

/** Tests for [[QueryServiceLive]] — specifically [[MitigationSelectionScan]],
  * the part that decides which mitigation valuations a bound query references so
  * the service precomputes exactly those result maps — plus the parse → bind →
  * evaluate integration through [[RiskTreeKnowledgeBase]] that the service's
  * `evaluate` drives, and `evaluate`'s handling of a failure computing a
  * referenced selection. The full ZIO/HTTP round trip is covered in the serverIt
  * `QueryEndpointSpec`.
  */
object QueryServiceLiveSpec extends ZIOSpecDefault with TestHelpers:

  // ── Fixture tree: root → { a, b } (two independent leaves) ───────────

  private val rootId = nodeId("qsl-root")
  private val aId    = nodeId("qsl-a")
  private val bId    = nodeId("qsl-b")

  private val rootP = unsafeGet(RiskPortfolio.create(subject = ErrorSubject.Request,
    id = rootId.value, name = "Root", childIds = Vector(aId, bId), parentId = None), "portfolio")
  private val aLeaf = unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
    id = aId.value, name = "Alpha", probability = 0.2,
    shape = lognormalShape(100L, 100000L), parentId = Some(rootId), seedVarId = 1L), "leaf")
  private val bLeaf = unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
    id = bId.value, name = "Beta", probability = 0.2,
    shape = lognormalShape(100L, 100000L), parentId = Some(rootId), seedVarId = 2L), "leaf")

  private val allNodes: Map[NodeId, RiskNode] = Map(rootId -> rootP, aId -> aLeaf, bId -> bLeaf)

  private val mAlphaId = mitigationId("m-alpha")
  private val mAlpha = Mitigation.create(
    mAlphaId,
    SafeName.fromString("Alpha Mitigation").toOption.get,
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get),
    resultStage(ResultTransformSpec.CapLosses(1000L)),
    MitigationPrecedence.default
  ).toEither.toOption.get

  private val tree = RiskTree.fromNodesUnsafe(
    id     = treeId("qsl-tree"),
    name   = SafeName.fromString("QSL Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    seedVarHighWater = Some(SeedVarId.fromLong(1000L).toOption.get),
    mitigations = List(mAlpha)
  )

  // ── Per-selection results ────────────────────────────────────────────
  //
  // Inherent: both leaves have a flat 100 loss → p95 = 100 for both.
  // Residual: Alpha (the mitigated leaf) is knocked down to 10; Beta unchanged.
  // So with threshold 50: inherent → {a, b} exceed; residual → only {b}.

  private def flat(id: NodeId, v: Long): LossDistribution =
    withCfg(5)(leafOf(id, Map(0 -> v, 1 -> v, 2 -> v, 3 -> v, 4 -> v), None))

  private val inherentResults = Map(aId -> flat(aId, 100L), bId -> flat(bId, 100L))
  private val residualResults  = Map(aId -> flat(aId, 10L),  bId -> flat(bId, 100L))

  private val resolvedScopes: Map[MitigationId, Set[NodeId]] = Map(mAlphaId -> Set(aId))

  private val kb = RiskTreeKnowledgeBase(
    RiskTreeKnowledgeBase.schemaFor(tree),
    Map(
      MitigationSelection.Inherent -> inherentResults,
      MitigationSelection.Residual -> residualResults
    ),
    resolvedScopes
  )

  // ── Helpers ────────────────────────────────────────────────────────

  /** Bind a query against `kb`'s catalog and scan for referenced selections. */
  private def referenced(text: String): Set[MitigationSelection] =
    val parsed = VagueQueryParser.parse(text).toOption.get
    val bound  = QueryBinder.bind(parsed, kb.catalog).toOption.get
    MitigationSelectionScan.referenced(bound, tree)

  /** Parse → build FolModel → evaluate; return the satisfying node ids. */
  private def satisfyingNodeIds(text: String): Set[NodeId] =
    val parsed = VagueQueryParser.parse(text).toOption.get
    val out = (for
      fm <- FolModel(kb.catalog, kb.model)
      o  <- VagueSemantics.evaluateTyped(parsed, fm)
    yield o).toOption.get
    out.satisfyingElements.flatMap(v => v.raw match { case id: NodeId => Some(id); case _ => None })

  // ── Spec ───────────────────────────────────────────────────────────

  def spec = suite("QueryServiceLiveSpec")(
    scanSuite,
    evaluationSuite,
    serviceSuite
  )

  private val everyMitigation: Set[MitigationSelection] =
    Set(MitigationSelection.Selected(Map(mAlphaId -> ScopeRestriction.FullScope)))

  private val scanSuite = suite("MitigationSelectionScan.referenced")(
    test("the inherent constant references the Inherent valuation") {
      assertTrue(referenced("""Q[>=]^{1/1} x (leaf(x), gt_loss(p95(x, "inherent"), 50))""") == Set(MitigationSelection.Inherent))
    },
    test("the residual constant references the Residual valuation") {
      assertTrue(referenced("""Q[>=]^{1/1} x (leaf(x), gt_loss(p95(x, "residual"), 50))""") == Set(MitigationSelection.Residual))
    },
    test("a bound mitigation variable fans out to one Selected per tree mitigation (OD-4=A)") {
      // p95(x, m) with m bound by `exists m` reads the single-mitigation Selected
      // valuation of every mitigation in the tree.
      assertTrue(
        referenced("""Q[>=]^{1/1} x (leaf(x), exists m . (mitigate(x, m) /\ gt_loss(p95(x, m), 50)))""") == everyMitigation
      )
    },
    test("a query with no value function references no selection") {
      assertTrue(referenced("""Q[>=]^{1/1} x (leaf(x), mitigated(x))""") == Set.empty[MitigationSelection])
    },
    test("distinct constants in one query are both referenced") {
      assertTrue(
        referenced("""Q[>=]^{1/1} x (leaf(x), gt_loss(p95(x, "inherent"), 50) /\ gt_loss(p95(x, "residual"), 50))""")
          == Set(MitigationSelection.Inherent, MitigationSelection.Residual)
      )
    }
  )

  private val evaluationSuite = suite("parse → bind → evaluate through the KB")(
    test("mitigated(x) returns exactly the resolved-scope node set") {
      assertTrue(satisfyingNodeIds("""Q[>=]^{1/1} x (leaf(x), mitigated(x))""") == Set(aId))
    },
    test("unmitigated(x) returns the complement over the leaves") {
      assertTrue(satisfyingNodeIds("""Q[>=]^{1/1} x (leaf(x), unmitigated(x))""") == Set(bId))
    },
    test("p95(x, inherent) < t sees both leaves above threshold; residual reflects the applied mitigation") {
      // Inherent: both a,b have p95 = 100 > 50 → {a, b}.
      // Residual: a is knocked to p95 = 10 (not > 50), b stays 100 → {b} only.
      val inherentHits = satisfyingNodeIds("""Q[>=]^{1/1} x (leaf(x), gt_loss(p95(x, "inherent"), 50))""")
      val residualHits = satisfyingNodeIds("""Q[>=]^{1/1} x (leaf(x), gt_loss(p95(x, "residual"), 50))""")
      assertTrue(
        inherentHits == Set(aId, bId),
        residualHits == Set(bId)
      )
    }
  )

  // ── evaluate, against a resolver that fails ────────────────────────

  private val entity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get
  private val readCommit: CommitHash           = CommitHash.fromString("a" * 40).toOption.get

  /** What the resolver fails with: a fault simulating a leaf. */
  private val resolverFailure: AppError = SimulationFailure("qsl-tree", new RuntimeException("sampler failed"))

  /** A repository holding `tree` alone; `evaluate` only reads it. */
  private val oneTreeRepo: RiskTreeRepository = new RiskTreeRepository:
    private def unused = ZIO.die(new NotImplementedError("unused by evaluate"))
    def create(wsId: WorkspaceId, t: RiskTree, branch: BranchRef): IO[Failure,RiskTree] = unused
    def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[Failure,RiskTree] = unused
    def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[Failure,RiskTree] = unused
    def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): IO[Failure,RiskTree] = unused
    def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[Failure,Option[(RiskTree, CommitHash)]] =
      ZIO.succeed(Some((tree, readCommit)))
    def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[Failure,List[RiskTree]] = unused

  /** A resolver whose every computation fails with `resolverFailure`. */
  private val failingResolver: CachedResultResolver = new CachedResultResolver:
    def ensureCached(
      tree: RiskTree, nodeId: NodeId, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean,
      selection: MitigationSelection, resolvedScopes: Map[MitigationId, Set[NodeId]]
    ): IO[AppError, LossDistribution] = ZIO.fail(resolverFailure)
    def ensureCachedAll(
      tree: RiskTree, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean,
      selection: MitigationSelection, resolvedScopes: Map[MitigationId, Set[NodeId]]
    ): IO[AppError, Map[NodeId, LossDistribution]] = ZIO.fail(resolverFailure)

  private val serviceSuite = suite("QueryServiceLive.evaluate")(
    test("a failure computing a referenced selection reaches the caller as that failure") {
      given Checked[Authority] = TestChecked.value
      val parsed = VagueQueryParser.parse("""Q[>=]^{1/1} x (leaf(x), gt_loss(p95(x, "inherent"), 50))""").toOption.get
      (for
        svc  <- ZIO.service[QueryService]
        exit <- svc.evaluate(WorkspaceId(safeId("qsl-ws")), tree.id, parsed, entity, BranchRef.Main).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption) == Some(resolverFailure)))
        .provide(
          ZLayer.succeed(oneTreeRepo),
          ZLayer.succeed(failingResolver),
          MitigationScopeResolverRegistry.layer,
          TestConfigs.telemetryLayer >>> TelemetryLive.configured,
          QueryServiceLive.layer
        )
    },
    test("a query naming a node the tree does not hold is Rejected at Client with UnknownReferences") {
      // The bind fails before any selection is computed, so the failing
      // resolver is never reached.
      given Checked[Authority] = TestChecked.value
      val parsed = VagueQueryParser.parse("""Q[>=]^{1/1} x (named_risk(x, "NoSuchNode"), leaf(x))""").toOption.get
      (for
        svc  <- ZIO.service[QueryService]
        exit <- svc.evaluate(WorkspaceId(safeId("qsl-ws")), tree.id, parsed, entity, BranchRef.Main).exit
      yield assertTrue(exit.causeOption.flatMap(_.failureOption) == Some(Failure.rejected(
        TrustZone.Client,
        NonEmptyChunk(ValidationError(
          ErrorLocation.Whole,
          FieldProblem.Query(QueryProblem.UnknownReferences(List("cannot parse 'NoSuchNode' as NodeNameLiteral")))
        )),
        Nil
      ))))
        .provide(
          ZLayer.succeed(oneTreeRepo),
          ZLayer.succeed(failingResolver),
          MitigationScopeResolverRegistry.layer,
          TestConfigs.telemetryLayer >>> TelemetryLive.configured,
          QueryServiceLive.layer
        )
    }
  )

end QueryServiceLiveSpec
