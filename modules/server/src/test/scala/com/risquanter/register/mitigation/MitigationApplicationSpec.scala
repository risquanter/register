package com.risquanter.register.mitigation

import zio.test.*
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.{
  AbsoluteReplacement, DistributionShape, Mitigation, MitigationPrecedence,
  MitigationSpec, MitigationTarget, RelativeLikelihood, RelativeSeverity,
  ResultTransformSpec, RiskLeaf, RiskPortfolio, RiskTree, TargetingPredicate
}
import com.risquanter.register.domain.data.iron.{ContentHash, MitigationId, NodeId, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName}
import com.risquanter.register.simulation.TrialOutcomes
import com.risquanter.register.testutil.TestHelpers.{idStr, lognormalShape, mitigationId, nodeId, resultStage, treeId, unsafeGet}

/**
 * MitigationApplication — the action's decomposition: per-node scoping with
 * precedence order and restrictions, the param-stage effective tree (closure,
 * the absorption an absolute replacement performs, and the two precedence
 * presets that decide whether it absorbs or is absorbed), the composed
 * result-stage transform, and application records.
 *
 * Targeting is a predicate resolved server-side, so each test supplies the
 * resolved node scope explicitly via `scopes(...)` — the lookup table the
 * resolver would produce — rather than reading node ids off the mitigation.
 */
object MitigationApplicationSpec extends ZIOSpecDefault {

  /** Transform parameters are built here only as fixtures, so one location
    * stands for all of them: each refinement succeeds, and nothing in this
    * suite reads where a refusal would have reported. */
  private val paramAt: ErrorLocation = ErrorLocation.requestField(FieldName.Mitigations)

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get
  private val stamp: ContentHash = ContentHash.fromString("b" * 64).toOption.get

  /** A well-formed placeholder predicate; the actual scope is provided per test
    * through `scopes`, standing in for the server-side resolver. */
  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  private def scopes(pairs: (Mitigation, Set[String])*): Map[MitigationId, Set[NodeId]] =
    pairs.iterator.map { case (m, ns) => m.id -> ns.map(nodeId) }.toMap

  private def leaf(label: String, seedVarId: Long, prob: Double = 0.4,
                   min: Long = 1000L, max: Long = 100000L): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = idStr(label), name = label, probability = prob,
      shape = lognormalShape(min, max),
      parentId = Some(nodeId("root-pf")), seedVarId = seedVarId
    ), "leaf")

  private val cyber = leaf("cyber", 1L)
  private val flood = leaf("flood", 2L)
  private val root = unsafeGet(RiskPortfolio.createFromStrings(
    id = idStr("root-pf"), name = "Root Portfolio",
    childIds = Vector(cyber.id.value, flood.id.value), parentId = None), "portfolio")

  private def mkTree(mitigations: Mitigation*): RiskTree =
    RiskTree.fromNodes(treeId("act-tree"), name("Action Tree"), Seq(root, cyber, flood), root.id,
      mitigations = mitigations.toList).toEither.toOption.get

  /** A relative statement scaling severity and leaving likelihood alone. */
  private def leafScaleSeverity(label: String, factor: Double,
                                precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), pred,
      MitigationSpec.LeafRelative(
        RelativeLikelihood.Keep,
        RelativeSeverity.ScaleSeverity(ValidationUtil.refineRetentionFactor(factor, paramAt).toOption.get)),
      precedence).toEither.toOption.get

  /** A relative statement scaling likelihood and leaving severity alone. */
  private def leafScaleLikelihood(label: String, factor: Double,
                                  precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), pred,
      MitigationSpec.LeafRelative(
        RelativeLikelihood.Scale(ValidationUtil.refineRetentionFactor(factor, paramAt).toOption.get),
        RelativeSeverity.Keep),
      precedence).toEither.toOption.get

  /** An absolute statement replacing the anchor leaf's likelihood. */
  private def leafAbsoluteLikelihood(label: String, prob: Double, anchor: String,
                                     precedence: MitigationPrecedence): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), pred,
      MitigationSpec.LeafAbsolute(
        AbsoluteReplacement.Likelihood(ValidationUtil.refineResidualProbability(prob, paramAt).toOption.get),
        nodeId(anchor),
        stamp),
      precedence).toEither.toOption.get

  private def resultCap(label: String, cap: Long,
                        precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), pred,
      resultStage(ResultTransformSpec.CapLosses(ValidationUtil.refineLossCap(cap, paramAt).toOption.get)),
      precedence).toEither.toOption.get

  private def resultDeductible(label: String, d: Long,
                               precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), name(label), pred,
      resultStage(ResultTransformSpec.ApplyDeductible(ValidationUtil.refineNonNegativeLong(d, paramAt).toOption.get)),
      precedence).toEither.toOption.get

  /** The leaf at `label`. Every id these tests pass names a leaf, so a portfolio
    * here is a broken fixture rather than a case to handle. */
  private def leafAt(t: RiskTree, label: String): RiskLeaf =
    t.index.nodes(nodeId(label)) match
      case l: RiskLeaf      => l
      case p: RiskPortfolio => throw new AssertionError(s"expected a leaf at $label, got $p")

  /** The leaf's lower lognormal bound. Every fixture here is lognormal. */
  private def minOf(t: RiskTree, label: String): Long =
    leafAt(t, label).shape match
      case DistributionShape.Lognormal(bounds) => bounds.min
      case e: DistributionShape.Expert         => throw new AssertionError(s"expected a lognormal shape at $label, got $e")

  private def probOf(t: RiskTree, label: String): Double =
    leafAt(t, label).probability

  def spec = suite("MitigationApplicationSpec")(

    suite("scoped")(
      test("selection Inherent applies nothing") {
        val m = leafScaleSeverity("m-scale", 0.5)
        val t = mkTree(m)
        assertTrue(MitigationApplication.scoped(t, MitigationSelection.Inherent, scopes(m -> Set("cyber"))).isEmpty)
      },
      test("Residual: per-node lists in ascending precedence order with id tiebreak") {
        val late  = resultCap("m-b-cap", 1000000L, MitigationPrecedence(10))
        val early = resultCap("m-a-cap", 500000L, MitigationPrecedence(-10))
        val tie   = resultCap("m-c-cap", 700000L, MitigationPrecedence(10))
        val t = mkTree(late, early, tie)
        val ms = MitigationApplication.scoped(t, MitigationSelection.Residual,
          scopes(late -> Set("cyber"), early -> Set("cyber"), tie -> Set("cyber")))(nodeId("cyber"))
        assertTrue(ms.map(_.id.value) == List(early, late, tie).map(_.id.value))
      },
      test("Selected with FullScope covers the whole resolved scope; absent mitigations are off") {
        val a = leafScaleSeverity("m-scale", 0.5)
        val b = resultCap("m-cap", 1000000L)
        val t = mkTree(a, b)
        val scoped = MitigationApplication.scoped(t,
          MitigationSelection.Selected(Map(a.id -> ScopeRestriction.FullScope)),
          scopes(a -> Set("cyber", "flood"), b -> Set("cyber")))
        assertTrue(
          scoped(nodeId("cyber")) == List(a),
          scoped(nodeId("flood")) == List(a),
          !scoped.contains(nodeId("root-pf"))
        )
      },
      test("NodesOnly restricts to the intersection with the resolved scope; foreign ids no-op") {
        val a = leafScaleSeverity("m-scale", 0.5)
        val t = mkTree(a)
        val scoped = MitigationApplication.scoped(t,
          MitigationSelection.Selected(Map(a.id ->
            ScopeRestriction.NodesOnly(Set(nodeId("cyber"), nodeId("ghost"))))),
          scopes(a -> Set("cyber", "flood")))
        assertTrue(scoped.keySet == Set(nodeId("cyber")))
      }
    ),

    suite("effectiveTree (param-stage half of the action)")(
      test("scoped leaf transformed, out-of-scope leaf and portfolio untouched; mitigations carried") {
        val a = leafScaleSeverity("m-scale", 0.5)
        val t = mkTree(a)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.Residual, scopes(a -> Set("cyber"))).toEither.toOption.get
        assertTrue(
          minOf(eff, "cyber") == 500L,
          minOf(eff, "flood") == 1000L,
          eff.index.nodes(nodeId("root-pf")).isInstanceOf[RiskPortfolio],
          eff.mitigations == t.mitigations,
          eff.seedVarHighWater == t.seedVarHighWater
        )
      },
      test("an absolute replacement at the baseline preset is blended: it runs first, the relative op on top") {
        val base  = leafAbsoluteLikelihood("m-abs-base", 0.1, "cyber", MitigationPrecedence.overrideBaseline)
        val scale = leafScaleLikelihood("m-prob-scale", 0.5)
        val t = mkTree(base, scale)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.Residual,
          scopes(base -> Set("cyber"), scale -> Set("cyber"))).toEither.toOption.get
        assertTrue(math.abs(probOf(eff, "cyber") - 0.05) < 1e-9) // replaced with 0.1 first, then × 0.5
      },
      test("an absolute replacement at the final preset absorbs: it runs last and discards the relative op") {
        val fin   = leafAbsoluteLikelihood("m-abs-final", 0.1, "cyber", MitigationPrecedence.overrideFinal)
        val scale = leafScaleLikelihood("m-prob-scale", 0.5)
        val t = mkTree(fin, scale)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.Residual,
          scopes(fin -> Set("cyber"), scale -> Set("cyber"))).toEither.toOption.get
        assertTrue(math.abs(probOf(eff, "cyber") - 0.1) < 1e-9) // × 0.5 first, then replaced with 0.1
      },
      test("selection Inherent is the identity on the tree") {
        val m = leafScaleSeverity("m-scale", 0.5)
        val t = mkTree(m)
        val eff = MitigationApplication.effectiveTree(t, MitigationSelection.Inherent, scopes(m -> Set("cyber"))).toEither.toOption.get
        assertTrue(minOf(eff, "cyber") == 1000L)
      }
    ),

    suite("recordsByNode + run (result-stage half of the action)")(
      test("a node's layer composes its pipelines in precedence order") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))
        val dFirst = resultDeductible("m-a-ded", 10000L, MitigationPrecedence(-1))
        val scale  = Mitigation.create(
          mitigationId("m-b-scale"), name("m-b-scale"), pred,
          resultStage(ResultTransformSpec.ScaleLosses(0.5)),
          MitigationPrecedence(1)).toEither.toOption.get
        val t = mkTree(dFirst, scale)
        val scoped = MitigationApplication.scoped(t, MitigationSelection.Residual,
          scopes(dFirst -> Set("cyber"), scale -> Set("cyber")))
        val records = MitigationApplication.recordsByNode(scoped)
        val layer   = records.getOrElse(nodeId("cyber"), Nil)
        assertTrue(
          layer.map(_.mitigationId) == List(dFirst.id, scale.id),
          MitigationApplication.run(layer, outcomes).outcomeOf(1) == 45000L // (100K − 10K) × 0.5, not 40K
        )
      },
      test("a parameter-stage mitigation produces no record") {
        // Parameter-stage transforms shape the figure the layer is applied to,
        // and are never part of the layer.
        val m = leafScaleSeverity("m-scale", 0.5)
        val t = mkTree(m)
        val scoped  = MitigationApplication.scoped(t, MitigationSelection.Residual, scopes(m -> Set("cyber")))
        val records = MitigationApplication.recordsByNode(scoped)
        assertTrue(
          records.getOrElse(nodeId("cyber"), Nil).isEmpty,
          records.getOrElse(nodeId("flood"), Nil).isEmpty
        )
      },
      test("each record carries the mitigation's whole node set, not only this node") {
        // The cap reaches both nodes, so the record on either one names both.
        val cap = resultCap("m-cap", 1000000L)
        val t   = mkTree(cap)
        val scoped  = MitigationApplication.scoped(t, MitigationSelection.Residual,
          scopes(cap -> Set("cyber", "flood")))
        val records = MitigationApplication.recordsByNode(scoped)
        assertTrue(
          records(nodeId("cyber")).head.resolvedScope == Set(nodeId("cyber"), nodeId("flood")),
          records(nodeId("flood")).head.resolvedScope == Set(nodeId("cyber"), nodeId("flood"))
        )
      },
      test("an empty layer returns the same reference it was given") {
        // The identity of the transform monoid.
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))
        assertTrue(MitigationApplication.run(Nil, outcomes) eq outcomes)
      }
    ),

    suite("applicationRecords — the provenance layer")(
      test("one record per applied mitigation with the actually-touched scope") {
        val a = leafScaleSeverity("m-scale", 0.5)
        val b = resultCap("m-cap", 1000000L, MitigationPrecedence(5))
        val t = mkTree(a, b)
        val records = MitigationApplication.applicationRecords(t,
          MitigationSelection.Selected(Map(
            a.id -> ScopeRestriction.NodesOnly(Set(nodeId("cyber"))),
            b.id -> ScopeRestriction.FullScope)),
          scopes(a -> Set("cyber", "flood"), b -> Set("cyber")))
        assertTrue(
          records.map(_.mitigationId) == List(a.id, b.id), // precedence order
          records.head.resolvedScope == Set(nodeId("cyber")),
          records(1).resolvedScope == Set(nodeId("cyber"))
        )
      }
    ),

    suite("selection codec")(
      test("selection kinds round-trip") {
        import zio.json.{EncoderOps, DecoderOps}
        val selections: List[MitigationSelection] = List(
          MitigationSelection.Inherent,
          MitigationSelection.Residual,
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
