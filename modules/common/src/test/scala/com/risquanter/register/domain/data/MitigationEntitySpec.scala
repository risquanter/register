package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.iron.{ContentHash, NodeId, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.{idStr, mitigationId, nodeId, treeId}

/**
 * Mitigation entity: create cross-field rules (override stamp + anchor), wire
 * codec, precedence presets, and the tree-level mitigation invariants
 * (RiskTree.fromNodes + JSON codec, including backward compatibility with
 * mitigation-less payloads and the mitigation-count bound).
 */
object MitigationEntitySpec extends ZIOSpecDefault {

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private val stamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  /** A well-formed targeting predicate; resolution is a server concern, so any
    * member of the targeting fragment serves for entity-level tests. */
  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  private val resultSpec: MitigationSpec =
    MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.CapLosses(1000000L))))

  private val scaleLeafSpec: MitigationSpec =
    MitigationSpec.LeafStage(
      RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None, None)

  private def overrideLeafSpec(stamp: Option[ContentHash], anchor: Option[NodeId]): MitigationSpec =
    MitigationSpec.LeafStage(
      RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Keep), stamp, anchor)

  private def mk(
    label: String,
    spec: MitigationSpec,
    precedence: MitigationPrecedence = MitigationPrecedence.default
  ) = Mitigation.create(mitigationId(label), name(label), pred, spec, precedence)

  private def leaf(label: String, seedVarId: Long): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = idStr(label), name = label, distributionType = "lognormal",
      probability = 0.4, minLoss = Some(1000L), maxLoss = Some(100000L),
      parentId = Some(nodeId("root-pf")), seedVarId = seedVarId
    )

  private def tree(mitigations: Mitigation*) = {
    val l1 = leaf("cyber", 1L)
    val l2 = leaf("flood", 2L)
    val root = RiskPortfolio.unsafeFromStrings(
      id = idStr("root-pf"), name = "Root Portfolio",
      childIds = Array(l1.id.value, l2.id.value))
    RiskTree.fromNodes(treeId("mit-tree"), name("Mit Tree"), Seq(root, l1, l2), root.id,
      mitigations = mitigations.toList)
  }

  def spec = suite("MitigationEntitySpec")(

    suite("Mitigation.create cross-field rules")(
      test("result-stage mitigation is valid") {
        assertTrue(mk("m-result", resultSpec).toEither.isRight)
      },
      test("non-Override leaf-stage mitigation is valid") {
        assertTrue(mk("m-scale", scaleLeafSpec).toEither.isRight)
      },
      test("Override with stamp and anchor is valid") {
        assertTrue(mk("m-ov-ok", overrideLeafSpec(Some(stamp), Some(nodeId("cyber")))).toEither.isRight)
      },
      test("Override without base stamp is rejected") {
        val result = mk("m-ov-nostamp", overrideLeafSpec(None, Some(nodeId("cyber"))))
        assertTrue(result.toEither.swap.toOption.get.exists(_.code == ValidationErrorCode.REQUIRED_FIELD))
      },
      test("Override without an anchor is rejected") {
        val result = mk("m-ov-noanchor", overrideLeafSpec(Some(stamp), None))
        assertTrue(result.toEither.swap.toOption.get.exists(_.code == ValidationErrorCode.REQUIRED_FIELD))
      },
      test("non-Override carrying a stamp is rejected") {
        val badSpec = MitigationSpec.LeafStage(
          RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), Some(stamp), None)
        assertTrue(mk("m-stamped", badSpec).toEither.swap.toOption.get.exists(_.code == ValidationErrorCode.INVALID_COMBINATION))
      },
      test("non-Override carrying an anchor is rejected") {
        val badSpec = MitigationSpec.LeafStage(
          RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None, Some(nodeId("cyber")))
        assertTrue(mk("m-anchored", badSpec).toEither.swap.toOption.get.exists(_.code == ValidationErrorCode.INVALID_COMBINATION))
      },
      test("result-stage pipeline at the step limit is valid") {
        val steps = List.fill(10)(ResultTransformSpec.CapLosses(1000000L))
        val okSpec = MitigationSpec.ResultStage(TransformPipeline(steps))
        assertTrue(mk("m-atlimit", okSpec).toEither.isRight)
      },
      test("result-stage pipeline over the step limit is rejected") {
        val steps = List.fill(11)(ResultTransformSpec.CapLosses(1000000L))
        val bigSpec = MitigationSpec.ResultStage(TransformPipeline(steps))
        assertTrue(mk("m-big", bigSpec).toEither.swap.toOption.get.exists(_.code == ValidationErrorCode.CONSTRAINT_VIOLATION))
      }
    ),

    suite("precedence presets")(
      test("baseline < default < final") {
        assertTrue(
          MitigationPrecedence.overrideBaseline.key < MitigationPrecedence.default.key,
          MitigationPrecedence.default.key < MitigationPrecedence.overrideFinal.key
        )
      }
    ),

    suite("codec")(
      test("result-stage and override mitigations round-trip") {
        val ms = List(
          mk("m-result", resultSpec).toEither.toOption.get,
          mk("m-ov-ok", overrideLeafSpec(Some(stamp), Some(nodeId("cyber"))),
             MitigationPrecedence.overrideFinal).toEither.toOption.get
        )
        assertTrue(ms.forall(m => m.toJson.fromJson[Mitigation] == Right(m)))
      },
      test("decoder enforces create rules (Override without stamp rejected on the wire)") {
        val valid = mk("m-ov-ok", overrideLeafSpec(Some(stamp), Some(nodeId("cyber")))).toEither.toOption.get
        val json = valid.toJson
        val tampered = json
          .replace(s""","overrideBaseStamp":"${"a" * 64}"""", "")
          .replace(s""""overrideBaseStamp":"${"a" * 64}",""", "")
        assertTrue(json != tampered, tampered.fromJson[Mitigation].isLeft)
      }
    ),

    suite("tree-level invariants (RiskTree.fromNodes)")(
      test("valid mitigations are accepted and carried") {
        val m = mk("m-result", resultSpec).toEither.toOption.get
        val t = tree(m).toEither.toOption.get
        assertTrue(t.mitigations == List(m))
      },
      test("duplicate mitigation ids are rejected") {
        val a = mk("m-dup", resultSpec).toEither.toOption.get
        val b = Mitigation.create(
          mitigationId("m-dup"), name("other-name"), pred, resultSpec,
          MitigationPrecedence.default).toEither.toOption.get
        assertTrue(tree(a, b).toEither.isLeft)
      },
      test("duplicate mitigation names are rejected") {
        val a = mk("m-one", resultSpec).toEither.toOption.get
        val b = Mitigation.create(
          mitigationId("m-two"), a.name, pred, resultSpec,
          MitigationPrecedence.default).toEither.toOption.get
        assertTrue(tree(a, b).toEither.isLeft)
      },
      test("more than the maximum number of mitigations is rejected") {
        val many = (1 to 1001).map(i => mk(s"m-$i", resultSpec).toEither.toOption.get).toList
        assertTrue(tree(many*).toEither.isLeft)
      }
    ),

    suite("tree wire format")(
      test("tree with mitigations round-trips") {
        val m = mk("m-result", resultSpec).toEither.toOption.get
        val t = tree(m).toEither.toOption.get
        val decoded = t.toJson.fromJson[RiskTree]
        assertTrue(decoded.toOption.get.mitigations == List(m))
      },
      test("payload without a mitigations key decodes to an empty collection (backward compat)") {
        val t = tree().toEither.toOption.get
        val json = t.toJson
        assertTrue(
          !json.contains("mitigations"),
          json.fromJson[RiskTree].toOption.get.mitigations.isEmpty
        )
      },
      test("decoder re-validates the targeting predicate on the wire") {
        val m = mk("m-result", resultSpec).toEither.toOption.get
        val t = tree(m).toEither.toOption.get
        // replace the stored predicate source with one carrying a quantifier
        val json = t.toJson
        val tampered = json.replace("leaf(x)", "forall x . leaf(x)")
        assertTrue(json != tampered, tampered.fromJson[RiskTree].isLeft)
      }
    )
  )
}
