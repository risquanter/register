package com.risquanter.register.domain.data

import zio.test.*
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.iron.{ContentHash, MitigationId, SafeName}
import com.risquanter.register.testutil.TestHelpers.{idStr, mitigationId, nodeId, treeId}

/**
 * MitigationApplication — the action's decomposition: per-node scoping with
 * precedence order and restrictions, the param-stage effective tree (closure,
 * Override absorption, baseline/final presets), the composed result-stage
 * transform, application records, and the worsening-override diagnostic.
 */
object MitigationApplicationSpec extends ZIOSpecDefault {

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get
  private val stamp: ContentHash = ContentHash.fromString("b" * 64).toOption.get

  private def leaf(label: String, seedVarId: Long, prob: Double = 0.4,
                   min: Long = 1000L, max: Long = 100000L): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = idStr(label), name = label, distributionType = "lognormal",
      probability = prob, minLoss = Some(min), maxLoss = Some(max),
      parentId = Some(nodeId("root-pf")), seedVarId = seedVarId
    )

  private val cyber = leaf("cyber", 1L)
  private val flood = leaf("flood", 2L)
  private val root = RiskPortfolio.unsafeFromStrings(
    id = idStr("root-pf"), name = "Root Portfolio",
    childIds = Array(cyber.id.value, flood.id.value))

  private def mkTree(mitigations: Mitigation*): RiskTree =
    RiskTree.fromNodes(treeId("act-tree"), name("Action Tree"), Seq(root, cyber, flood), root.id,
      mitigations = mitigations.toList).toEither.toOption.get

  private def leafScale(label: String, factor: Double, targets: Set[String],
                        precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), MitigationTarget.Nodes(targets.map(nodeId)),
      MitigationSpec.LeafStage(
        RiskLeafTransform(LikelihoodTransform.Keep,
          DistributionTransform.ScaleSeverity(com.risquanter.register.domain.data.iron.ValidationUtil.refineNonNegativeDouble(factor).toOption.get)),
        None),
      precedence).toEither.toOption.get

  private def leafOverride(label: String, prob: Double, target: String,
                           precedence: MitigationPrecedence): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), MitigationTarget.Nodes(Set(nodeId(target))),
      MitigationSpec.LeafStage(
        RiskLeafTransform(
          LikelihoodTransform.Override(com.risquanter.register.domain.data.iron.ValidationUtil.refineOccurrenceProbability(prob).toOption.get),
          DistributionTransform.Keep),
        Some(stamp)),
      precedence).toEither.toOption.get

  private def resultCap(label: String, cap: Long, targets: Set[String],
                        precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), MitigationTarget.Nodes(targets.map(nodeId)),
      MitigationSpec.ResultStage(TransformPipeline(List(
        ResultTransformSpec.CapLosses(com.risquanter.register.domain.data.iron.ValidationUtil.refineNonNegativeLong(cap).toOption.get)))),
      precedence).toEither.toOption.get

  private def resultDeductible(label: String, d: Long, targets: Set[String],
                               precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), MitigationTarget.Nodes(targets.map(nodeId)),
      MitigationSpec.ResultStage(TransformPipeline(List(
        ResultTransformSpec.ApplyDeductible(com.risquanter.register.domain.data.iron.ValidationUtil.refineNonNegativeLong(d).toOption.get)))),
      precedence).toEither.toOption.get

  private def minOf(t: RiskTree, label: String): Long =
    t.index.nodes(nodeId(label)).asInstanceOf[RiskLeaf].minLoss.map(l => l: Long).get
  private def probOf(t: RiskTree, label: String): Double =
    t.index.nodes(nodeId(label)).asInstanceOf[RiskLeaf].probability

  def spec = suite("MitigationApplicationSpec")(

    suite("scoped")(
      test("selection None applies nothing") {
        val t = mkTree(leafScale("m-scale", 0.5, Set("cyber")))
        assertTrue(MitigationApplication.scoped(t, MitigationSelection.None).isEmpty)
      },
      test("All: per-node lists in ascending precedence order with id tiebreak") {
        val late  = resultCap("m-b-cap", 1000000L, Set("cyber"), MitigationPrecedence(10))
        val early = resultCap("m-a-cap", 500000L, Set("cyber"), MitigationPrecedence(-10))
        val tie   = resultCap("m-c-cap", 700000L, Set("cyber"), MitigationPrecedence(10))
        val t = mkTree(late, early, tie)
        val ms = MitigationApplication.scoped(t, MitigationSelection.All)(nodeId("cyber"))
        assertTrue(ms.map(_.id.value) == List(early, late, tie).map(_.id.value))
      },
      test("Selected with FullScope covers the whole target; absent mitigations are off") {
        val a = leafScale("m-scale", 0.5, Set("cyber", "flood"))
        val b = resultCap("m-cap", 1000000L, Set("cyber"))
        val t = mkTree(a, b)
        val scoped = MitigationApplication.scoped(t,
          MitigationSelection.Selected(Map(a.id -> ScopeRestriction.FullScope)))
        assertTrue(
          scoped(nodeId("cyber")) == List(a),
          scoped(nodeId("flood")) == List(a),
          !scoped.contains(nodeId("root-pf"))
        )
      },
      test("NodesOnly restricts to the intersection with the target; foreign ids no-op") {
        val a = leafScale("m-scale", 0.5, Set("cyber", "flood"))
        val t = mkTree(a)
        val scoped = MitigationApplication.scoped(t,
          MitigationSelection.Selected(Map(a.id ->
            ScopeRestriction.NodesOnly(Set(nodeId("cyber"), nodeId("ghost"))))))
        assertTrue(scoped.keySet == Set(nodeId("cyber")))
      }
    ),

    suite("effectiveTree (param-stage half of the action)")(
      test("scoped leaf transformed, out-of-scope leaf and portfolio untouched; mitigations carried") {
        val a = leafScale("m-scale", 0.5, Set("cyber"))
        val t = mkTree(a)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.All).toEither.toOption.get
        assertTrue(
          minOf(eff, "cyber") == 500L,
          minOf(eff, "flood") == 1000L,
          eff.index.nodes(nodeId("root-pf")).isInstanceOf[RiskPortfolio],
          eff.mitigations == t.mitigations,
          eff.seedVarHighWater == t.seedVarHighWater
        )
      },
      test("baseline Override blends: replacement first, relative op on top") {
        val base = leafOverride("m-ov-base", 0.1, "cyber", MitigationPrecedence.overrideBaseline)
        val scale = Mitigation.create(
          mitigationId("m-prob-scale"), name("m-prob-scale"), MitigationTarget.Nodes(Set(nodeId("cyber"))),
          MitigationSpec.LeafStage(
            RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None),
          MitigationPrecedence.default).toEither.toOption.get
        val t = mkTree(base, scale)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.All).toEither.toOption.get
        assertTrue(math.abs(probOf(eff, "cyber") - 0.05) < 1e-9) // 0.1 overridden first, then × 0.5
      },
      test("final Override asserts: applied last, discards the prior relative op") {
        val fin = leafOverride("m-ov-final", 0.1, "cyber", MitigationPrecedence.overrideFinal)
        val scale = Mitigation.create(
          mitigationId("m-prob-scale"), name("m-prob-scale"), MitigationTarget.Nodes(Set(nodeId("cyber"))),
          MitigationSpec.LeafStage(
            RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None),
          MitigationPrecedence.default).toEither.toOption.get
        val t = mkTree(fin, scale)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.All).toEither.toOption.get
        assertTrue(math.abs(probOf(eff, "cyber") - 0.1) < 1e-9) // × 0.5 first, then asserted to 0.1
      },
      test("selection None is the identity on the tree") {
        val t = mkTree(leafScale("m-scale", 0.5, Set("cyber")))
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.None).toEither.toOption.get
        assertTrue(minOf(eff, "cyber") == 1000L)
      }
    ),

    suite("resultTransformFor (result-stage half of the action)")(
      test("composes pipelines in precedence order") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))
        val dFirst = resultDeductible("m-a-ded", 10000L, Set("cyber"), MitigationPrecedence(-1))
        val scale  = Mitigation.create(
          mitigationId("m-b-scale"), name("m-b-scale"), MitigationTarget.Nodes(Set(nodeId("cyber"))),
          MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.ScaleLosses(0.5)))),
          MitigationPrecedence(1)).toEither.toOption.get
        val t = mkTree(dFirst, scale)
        val scoped = MitigationApplication.scoped(t, MitigationSelection.All)
        val composed = MitigationApplication.resultTransformFor(nodeId("cyber"), scoped)
        assertTrue(composed.run(outcomes).outcomeOf(1) == 45000L) // (100K − 10K) × 0.5, not 40K
      },
      test("identity when nothing result-stage scopes the node") {
        val t = mkTree(leafScale("m-scale", 0.5, Set("cyber")))
        val scoped = MitigationApplication.scoped(t, MitigationSelection.All)
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))
        assertTrue(
          MitigationApplication.resultTransformFor(nodeId("cyber"), scoped).run(outcomes) == outcomes,
          MitigationApplication.resultTransformFor(nodeId("flood"), scoped).run(outcomes) == outcomes
        )
      }
    ),

    suite("applicationRecords (D-4 provenance layer)")(
      test("one record per applied mitigation with the actually-touched scope") {
        val a = leafScale("m-scale", 0.5, Set("cyber", "flood"))
        val b = resultCap("m-cap", 1000000L, Set("cyber"), MitigationPrecedence(5))
        val t = mkTree(a, b)
        val records = MitigationApplication.applicationRecords(t,
          MitigationSelection.Selected(Map(
            a.id -> ScopeRestriction.NodesOnly(Set(nodeId("cyber"))),
            b.id -> ScopeRestriction.FullScope)))
        assertTrue(
          records.map(_.mitigationId) == List(a.id, b.id), // precedence order
          records.head.resolvedScope == Set(nodeId("cyber")),
          records(1).resolvedScope == Set(nodeId("cyber"))
        )
      }
    ),

    suite("worseningOverrides (nonsense check)")(
      test("an override raising the probability is flagged; lowering is not") {
        val worse  = leafOverride("m-worse", 0.9, "cyber", MitigationPrecedence.overrideFinal)
        val better = leafOverride("m-better", 0.1, "flood", MitigationPrecedence.overrideFinal)
        val t = mkTree(worse, better)
        assertTrue(MitigationApplication.worseningOverrides(t) == Set(worse.id))
      },
      test("a lognormal override raising both bounds is flagged") {
        val params = OverrideDistributionParams.create(
          com.risquanter.register.domain.data.iron.ValidationUtil.refineDistributionType("lognormal").toOption.get,
          percentiles = None, quantiles = None,
          minLoss = Some(2000L), maxLoss = Some(200000L), terms = None
        ).toEither.toOption.get
        val m = Mitigation.create(
          mitigationId("m-dist-worse"), name("m-dist-worse"), MitigationTarget.Nodes(Set(nodeId("cyber"))),
          MitigationSpec.LeafStage(
            RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Override(params)), Some(stamp)),
          MitigationPrecedence.overrideFinal).toEither.toOption.get
        val t = mkTree(m)
        assertTrue(MitigationApplication.worseningOverrides(t) == Set(m.id))
      },
      test("non-override mitigations are never flagged") {
        val t = mkTree(leafScale("m-scale", 2.0, Set("cyber")))
        assertTrue(MitigationApplication.worseningOverrides(t).isEmpty)
      }
    ),

    suite("selection codec")(
      test("selection kinds round-trip") {
        import zio.json.{EncoderOps, DecoderOps}
        val selections: List[MitigationSelection] = List(
          MitigationSelection.None,
          MitigationSelection.All,
          MitigationSelection.Selected(Map(
            MitigationId(nodeId("m-scale").toSafeId) -> ScopeRestriction.FullScope,
            MitigationId(nodeId("m-cap").toSafeId)   -> ScopeRestriction.NodesOnly(Set(nodeId("cyber")))
          ))
        )
        assertTrue(selections.forall(s => s.toJson.fromJson[MitigationSelection] == Right(s)))
      }
    )
  )
}
