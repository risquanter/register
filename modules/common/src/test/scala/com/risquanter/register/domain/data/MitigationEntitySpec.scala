package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.iron.{ContentHash, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.{idStr, mitigationId, nodeId, treeId}

/**
 * Mitigation entity: create cross-field rules, wire codec, precedence presets,
 * and the tree-level mitigation invariants (RiskTree.fromNodes + JSON codec,
 * including backward compatibility with mitigation-less payloads).
 */
object MitigationEntitySpec extends ZIOSpecDefault {

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private val stamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  private val resultSpec: MitigationSpec =
    MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.CapLosses(1000000L))))

  private val scaleLeafSpec: MitigationSpec =
    MitigationSpec.LeafStage(
      RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None)

  private def overrideLeafSpec(withStamp: Option[ContentHash]): MitigationSpec =
    MitigationSpec.LeafStage(
      RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Keep), withStamp)

  private def mk(
    label: String,
    target: Set[String],
    spec: MitigationSpec,
    precedence: MitigationPrecedence = MitigationPrecedence.default
  ) = Mitigation.create(
    mitigationId(label), name(label), MitigationTarget.Nodes(target.map(nodeId)), spec, precedence)

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
      test("result-stage with multi-node target is valid") {
        assertTrue(mk("m-result", Set("cyber", "flood"), resultSpec).toEither.isRight)
      },
      test("empty target set is rejected") {
        assertTrue(mk("m-empty", Set.empty, resultSpec).toEither.isLeft)
      },
      test("Override without base stamp is rejected") {
        val result = mk("m-ov", Set("cyber"), overrideLeafSpec(None))
        assertTrue(result.toEither.swap.toOption.get.exists(_.code == ValidationErrorCode.REQUIRED_FIELD))
      },
      test("non-Override with a stamp is rejected") {
        val badSpec = MitigationSpec.LeafStage(
          RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), Some(stamp))
        assertTrue(mk("m-stamped", Set("cyber"), badSpec).toEither.isLeft)
      },
      test("Override with a multi-node target is rejected") {
        assertTrue(mk("m-ov-multi", Set("cyber", "flood"), overrideLeafSpec(Some(stamp))).toEither.isLeft)
      },
      test("Override with stamp and single-node target is valid") {
        assertTrue(mk("m-ov-ok", Set("cyber"), overrideLeafSpec(Some(stamp))).toEither.isRight)
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
          mk("m-result", Set("cyber", "flood"), resultSpec).toEither.toOption.get,
          mk("m-ov-ok", Set("cyber"), overrideLeafSpec(Some(stamp)),
             MitigationPrecedence.overrideFinal).toEither.toOption.get
        )
        assertTrue(ms.forall(m => m.toJson.fromJson[Mitigation] == Right(m)))
      },
      test("decoder enforces create rules (Override without stamp rejected on the wire)") {
        val valid = mk("m-ov-ok", Set("cyber"), overrideLeafSpec(Some(stamp))).toEither.toOption.get
        val json = valid.toJson
        val tampered = json
          .replace(s""","overrideBaseStamp":"${"a" * 64}"""", "")
          .replace(s""""overrideBaseStamp":"${"a" * 64}",""", "")
        assertTrue(json != tampered, tampered.fromJson[Mitigation].isLeft)
      }
    ),

    suite("tree-level invariants (RiskTree.fromNodes)")(
      test("valid mitigations are accepted and carried") {
        val m = mk("m-result", Set("cyber"), resultSpec).toEither.toOption.get
        val t = tree(m).toEither.toOption.get
        assertTrue(t.mitigations == List(m))
      },
      test("duplicate mitigation ids are rejected") {
        val a = mk("m-dup", Set("cyber"), resultSpec).toEither.toOption.get
        val b = Mitigation.create(
          mitigationId("m-dup"), name("other-name"),
          MitigationTarget.Nodes(Set(nodeId("flood"))), resultSpec,
          MitigationPrecedence.default).toEither.toOption.get
        assertTrue(tree(a, b).toEither.isLeft)
      },
      test("duplicate mitigation names are rejected") {
        val a = mk("m-one", Set("cyber"), resultSpec).toEither.toOption.get
        val b = Mitigation.create(
          mitigationId("m-two"), a.name,
          MitigationTarget.Nodes(Set(nodeId("flood"))), resultSpec,
          MitigationPrecedence.default).toEither.toOption.get
        assertTrue(tree(a, b).toEither.isLeft)
      },
      test("dangling target node is rejected") {
        val m = mk("m-dangling", Set("ghost"), resultSpec).toEither.toOption.get
        assertTrue(tree(m).toEither.isLeft)
      },
      test("param-stage mitigation targeting a portfolio is rejected") {
        val m = mk("m-leafstage", Set("root-pf"), scaleLeafSpec).toEither.toOption.get
        assertTrue(tree(m).toEither.isLeft)
      },
      test("result-stage mitigation may target a portfolio") {
        val m = mk("m-portfolio-cap", Set("root-pf"), resultSpec).toEither.toOption.get
        assertTrue(tree(m).toEither.isRight)
      }
    ),

    suite("tree wire format")(
      test("tree with mitigations round-trips") {
        val m = mk("m-result", Set("cyber"), resultSpec).toEither.toOption.get
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
      test("decoder enforces tree-level invariants on the wire") {
        val m = mk("m-result", Set("cyber"), resultSpec).toEither.toOption.get
        val t = tree(m).toEither.toOption.get
        // retarget ONLY the mitigation (its target array) at an id absent from the tree
        val json = t.toJson
        val tampered = json.replace(
          s""""ids":["${idStr("cyber")}"]""",
          s""""ids":["${idStr("ghost")}"]"""
        )
        assertTrue(json != tampered, tampered.fromJson[RiskTree].isLeft)
      }
    )
  )
}
