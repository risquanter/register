package com.risquanter.register.services

import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.{
  RiskResult, RiskLeaf, RiskPortfolio, RiskNode, RiskTree, LossDistribution,
  MitigationSelection, ScopeRestriction,
  Mitigation, MitigationTarget, MitigationSpec, MitigationPrecedence, TargetingPredicate,
  TransformPipeline, ResultTransformSpec
}
import com.risquanter.register.domain.data.iron.{NodeId, MitigationId, SafeName, SeedVarId}
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase
import com.risquanter.register.testutil.TestHelpers
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

import vql.parser.VagueQueryParser
import vql.semantics.VagueSemantics
import vql.typed.{FolModel, QueryBinder}

/** Tests for [[QueryServiceLive]] — specifically [[MitigationSelectionScan]],
  * the part that decides which mitigation valuations a bound query references so
  * the service precomputes exactly those result maps — plus the parse → bind →
  * evaluate integration through [[RiskTreeKnowledgeBase]] that the service's
  * `evaluate` drives. The full ZIO/HTTP round trip is covered in the serverIt
  * `QueryEndpointSpec`.
  */
object QueryServiceLiveSpec extends ZIOSpecDefault with TestHelpers:

  // ── Fixture tree: root → { a, b } (two independent leaves) ───────────

  private val rootId = nodeId("qsl-root")
  private val aId    = nodeId("qsl-a")
  private val bId    = nodeId("qsl-b")

  private val rootP = unsafeGet(RiskPortfolio.create(
    id = rootId.value, name = "Root", childIds = Array(aId, bId), parentId = None), "portfolio")
  private val aLeaf = unsafeGet(RiskLeaf.create(
    id = aId.value, name = "Alpha", distributionType = "lognormal", probability = 0.2,
    minLoss = Some(100L), maxLoss = Some(100000L), parentId = Some(rootId), seedVarId = 1L), "leaf")
  private val bLeaf = unsafeGet(RiskLeaf.create(
    id = bId.value, name = "Beta", distributionType = "lognormal", probability = 0.2,
    minLoss = Some(100L), maxLoss = Some(100000L), parentId = Some(rootId), seedVarId = 2L), "leaf")

  private val allNodes: Map[NodeId, RiskNode] = Map(rootId -> rootP, aId -> aLeaf, bId -> bLeaf)

  private val mAlphaId = mitigationId("m-alpha")
  private val mAlpha = Mitigation.create(
    mAlphaId,
    SafeName.fromString("Alpha Mitigation").toOption.get,
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get),
    MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.CapLosses(1000L)))),
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

  private def flat(id: NodeId, v: Long): RiskResult =
    withCfg(5)(RiskResult(nodeId = id, outcomes = Map(1 -> v, 2 -> v, 3 -> v, 4 -> v, 5 -> v), provenances = Nil))

  private def widen(res: Map[NodeId, RiskResult]): Map[NodeId, LossDistribution] =
    res.map { case (k, v) => k -> (v: LossDistribution) }

  private val inherentResults = Map(aId -> flat(aId, 100L), bId -> flat(bId, 100L))
  private val residualResults  = Map(aId -> flat(aId, 10L),  bId -> flat(bId, 100L))

  private val resolvedScopes: Map[MitigationId, Set[NodeId]] = Map(mAlphaId -> Set(aId))

  private val kb = RiskTreeKnowledgeBase(
    RiskTreeKnowledgeBase.schemaFor(tree),
    Map(
      MitigationSelection.Inherent -> widen(inherentResults),
      MitigationSelection.Residual -> widen(residualResults)
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
    evaluationSuite
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

end QueryServiceLiveSpec
