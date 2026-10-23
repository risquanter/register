package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.iron.{ContentHash, NodeId, SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, ValidationErrorCode}
import com.risquanter.register.testutil.TestHelpers.{idStr, lognormalShape, mitigationId, nodeId, treeId, unsafeGet}

/**
 * Mitigation entity: the result-stage pipeline's step-count rule, the wire
 * codec, precedence presets, and the tree-level mitigation invariants
 * (RiskTree.fromNodes + JSON codec, including backward compatibility with
 * mitigation-less payloads and the mitigation-count bound).
 *
 * An absolute replacement's anchor and base stamp are plain fields of
 * `MitigationSpec.LeafAbsolute`, and a relative spec has no field for either,
 * so the presence and absence rules those once needed have no expression here
 * to test — a spec missing its stamp is a program that does not compile.
 */
object MitigationEntitySpec extends ZIOSpecDefault {

  private def name(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private val stamp: ContentHash = ContentHash.fromString("a" * 64).toOption.get

  /** Where the pipeline's own gate reports, which is what its decoder passes. */
  private val specLocation: ErrorLocation = ErrorLocation.requestField(FieldName.Spec)

  /** A well-formed targeting predicate; resolution is a server concern, so any
    * member of the targeting fragment serves for entity-level tests. */
  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  private def resultStage(steps: List[ResultTransformSpec]) =
    MitigationSpec.ResultStage.create(TransformPipeline(steps), specLocation)

  private val resultSpec: MitigationSpec =
    unsafeGet(resultStage(List(ResultTransformSpec.CapLosses(1000000L))), "resultSpec")

  private val scaleLeafSpec: MitigationSpec =
    MitigationSpec.LeafRelative(RelativeLikelihood.Scale(0.5), RelativeSeverity.Keep)

  private def absoluteLeafSpec(stamp: ContentHash, anchor: NodeId): MitigationSpec =
    MitigationSpec.LeafAbsolute(AbsoluteReplacement.Likelihood(0.05), anchor, stamp)

  private def mk(
    label: String,
    spec: MitigationSpec,
    precedence: MitigationPrecedence = MitigationPrecedence.default
  ) = Mitigation.create(mitigationId(label), name(label), pred, spec, precedence)

  private def leaf(label: String, seedVarId: Long): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = idStr(label), name = label, probability = 0.4,
      shape = lognormalShape(1000L, 100000L),
      parentId = Some(nodeId("root-pf")), seedVarId = seedVarId
    ), "leaf")

  private def tree(mitigations: Mitigation*) = {
    val l1 = leaf("cyber", 1L)
    val l2 = leaf("flood", 2L)
    val root = unsafeGet(RiskPortfolio.createFromStrings(
      id = idStr("root-pf"), name = "Root Portfolio",
      childIds = Vector(l1.id.value, l2.id.value), parentId = None), "portfolio")
    RiskTree.fromNodes(treeId("mit-tree"), name("Mit Tree"), Seq(root, l1, l2), root.id,
      mitigations = mitigations.toList)
  }

  def spec = suite("MitigationEntitySpec")(

    suite("Mitigation.create accepts every spec its type admits")(
      test("result-stage mitigation is valid") {
        assertTrue(mk("m-result", resultSpec).toEither.isRight)
      },
      test("relative leaf-stage mitigation is valid") {
        assertTrue(mk("m-scale", scaleLeafSpec).toEither.isRight)
      },
      test("absolute leaf-stage mitigation is valid") {
        assertTrue(mk("m-abs-ok", absoluteLeafSpec(stamp, nodeId("cyber"))).toEither.isRight)
      }
    ),

    suite("ResultStage.create holds the step-count rule")(
      test("a pipeline of one step is valid") {
        assertTrue(resultStage(List(ResultTransformSpec.CapLosses(1000000L))).toEither.isRight)
      },
      test("a pipeline at the step limit is valid") {
        assertTrue(resultStage(List.fill(10)(ResultTransformSpec.CapLosses(1000000L))).toEither.isRight)
      },
      test("a pipeline over the step limit is rejected") {
        val result = resultStage(List.fill(11)(ResultTransformSpec.CapLosses(1000000L)))
        assertTrue(result.toEither.swap.toOption.get.exists(e =>
          e.code == ValidationErrorCode.CONSTRAINT_VIOLATION && e.location == specLocation))
      },
      test("an empty pipeline is rejected") {
        val result = resultStage(Nil)
        assertTrue(result.toEither.swap.toOption.get.exists(e =>
          e.code == ValidationErrorCode.CONSTRAINT_VIOLATION && e.location == specLocation))
      },
      test("the decoder routes through create, so an over-long pipeline is refused on the wire") {
        val valid  = mk("m-result", resultSpec).toEither.toOption.get
        val oneStep = """{"op":"capLosses","cap":1000000}"""
        val eleven  = List.fill(11)(oneStep).mkString("[", ",", "]")
        val tampered = valid.toJson.replace(s"[$oneStep]", eleven)
        assertTrue(valid.toJson != tampered, tampered.fromJson[Mitigation].isLeft)
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
      test("all three spec cases round-trip") {
        val ms = List(
          mk("m-result", resultSpec).toEither.toOption.get,
          mk("m-scale", scaleLeafSpec).toEither.toOption.get,
          mk("m-abs-ok", absoluteLeafSpec(stamp, nodeId("cyber")),
             MitigationPrecedence.overrideFinal).toEither.toOption.get
        )
        assertTrue(ms.forall(m => m.toJson.fromJson[Mitigation] == Right(m)))
      },
      test("an absolute spec missing its base stamp is refused on the wire") {
        val valid = mk("m-abs-ok", absoluteLeafSpec(stamp, nodeId("cyber"))).toEither.toOption.get
        val json = valid.toJson
        val tampered = json
          .replace(s""","baseStamp":"${"a" * 64}"""", "")
          .replace(s""""baseStamp":"${"a" * 64}",""", "")
        assertTrue(json != tampered, tampered.fromJson[Mitigation].isLeft)
      },
      test("the three spec cases carry distinct stage tags") {
        val tags = List(resultSpec, scaleLeafSpec, absoluteLeafSpec(stamp, nodeId("cyber")))
          .map(s => mk("m", s).toEither.toOption.get.toJson)
        assertTrue(
          tags(0).contains("\"stage\":\"result\""),
          tags(1).contains("\"stage\":\"leafRelative\""),
          tags(2).contains("\"stage\":\"leafAbsolute\"")
        )
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
