package com.risquanter.register.foladapter

import zio.test.*

import com.risquanter.register.domain.data.{RiskResult, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.data.iron.SeedVarId
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.testutil.TestHelpers
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

import fol.parser.VagueQueryParser
import fol.semantics.VagueSemantics
import fol.sampling.{SamplingParams, HDRConfig}
import fol.typed.{FolModel, QueryBinder, TypeCheckError, TypeId, Value}

/** Integration tests for the parse → bind path against a register-side
  * [[RiskTreeKnowledgeBase]] populated with quoted node-name literals.
  *
  * Covers PLAN-QUERY-NODE-NAME-LITERALS §5.5 cases B1–B3:
  *   - B1: end-to-end parse + bind + evaluate; range projects to leaf names
  *   - B2: unknown literal rejected by binder with `UnknownConstantOrLiteral`
  *   - B3: injection-shaped node name does not feed the binder error path
  */
object BinderIntegrationSpec extends ZIOSpecDefault with TestHelpers:

  // ── 4-node fixture (mirrors RiskTreeKnowledgeBaseSpec) ────────────

  private val rootId     = nodeId("root")
  private val itId       = nodeId("it-risk")
  private val cyberId    = nodeId("cyber")
  private val hardwareId = nodeId("hardware")

  private val rootPortfolio = RiskPortfolio.unsafeApply(
    id   = rootId.value, name = "Root",
    childIds = Array(itId), parentId = None
  )
  private val itPortfolio = RiskPortfolio.unsafeApply(
    id   = itId.value, name = "IT Risk",
    childIds = Array(cyberId, hardwareId), parentId = Some(rootId)
  )
  private val cyberLeaf = RiskLeaf.unsafeApply(
    id = cyberId.value, name = "Cyber",
    distributionType = "lognormal", probability = 0.25,
    minLoss = Some(1000L), maxLoss = Some(50000L), parentId = Some(itId),
    seedVarId = 1L
  )
  private val hardwareLeaf = RiskLeaf.unsafeApply(
    id = hardwareId.value, name = "Hardware",
    distributionType = "lognormal", probability = 0.10,
    minLoss = Some(500L), maxLoss = Some(10000L), parentId = Some(itId),
    seedVarId = 2L
  )

  private val allNodes: Map[NodeId, RiskNode] =
    Map(rootId -> rootPortfolio, itId -> itPortfolio, cyberId -> cyberLeaf, hardwareId -> hardwareLeaf)

  private val tree = RiskTree(
    id     = treeId("test-tree"),
    name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Test Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    index  = TreeIndex.fromNodesUnsafe(allNodes),
      seedVarHighWater = SeedVarId.fromLong(1000L).toOption.get
  )

  // Five-trial outcomes; large enough that gt_loss(p95(x), 1000) is true for both leaves.
  private val cyberResult = withCfg(5) {
    RiskResult(
      nodeId = cyberId,
      outcomes = Map(1 -> 0L, 2 -> 5000L, 3 -> 10000L, 4 -> 20000L, 5 -> 50000L),
      provenances = Nil
    )
  }
  private val hardwareResult = withCfg(5) {
    RiskResult(
      nodeId = hardwareId,
      outcomes = Map(1 -> 500L, 2 -> 1000L, 3 -> 1000L, 4 -> 2000L, 5 -> 8000L),
      provenances = Nil
    )
  }
  private val rootResult = withCfg(5) {
    RiskResult(nodeId = rootId, outcomes = Map(1 -> 500L, 2 -> 6000L, 3 -> 11000L, 4 -> 22000L, 5 -> 58000L), provenances = Nil)
  }
  private val itResult = withCfg(5) {
    RiskResult(nodeId = itId, outcomes = Map(1 -> 500L, 2 -> 6000L, 3 -> 11000L, 4 -> 22000L, 5 -> 58000L), provenances = Nil)
  }
  private val results: Map[NodeId, RiskResult] =
    Map(rootId -> rootResult, itId -> itResult, cyberId -> cyberResult, hardwareId -> hardwareResult)

  private val kb = RiskTreeKnowledgeBase(tree, results)

  private val assetSort = TypeId("Asset")

  // ── Direct bypass-tree builder (mirrors RiskTreeKnowledgeBaseSpec.bypassTree) ─

  private def bypassTree(nodes: Seq[RiskNode]): RiskTree =
    val map: Map[NodeId, RiskNode] = nodes.iterator.map(n => n.id -> n).toMap
    val root: NodeId = nodes.head.id
    RiskTree(
      id     = treeId("bypass-tree"),
      name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Bypass Tree").toOption.get,
      nodes  = nodes,
      rootId = root,
      index  = TreeIndex.fromNodesUnsafe(map),
      seedVarHighWater = SeedVarId.fromLong(1000L).toOption.get
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("BinderIntegrationSpec — parse + bind against RiskTreeKnowledgeBase")(

      test("B1: quoted-literal scope query parses, binds, and evaluates with satisfying = {Cyber, Hardware}") {
        // PLAN-QUERY-NODE-NAME-LITERALS §5.5. Node names registered as catalog.constants
        // (Phase 4), so "IT Risk" binds to ConstRef("IT Risk", assetSort) which evaluates
        // to Value(assetSort, "IT Risk"). Both leaf descendants of IT Risk satisfy
        // gt_loss(p95(x), 1000) with the 5-trial fixture.
        val text   = """Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 1000))"""
        val parsed = VagueQueryParser.parse(text).toOption.get
        val result = for
          folModel <- FolModel(kb.catalog, kb.model)
          output   <- VagueSemantics.evaluateTyped(parsed, folModel)
        yield output
        val satisfyingNames = result.toOption.map { out =>
          out.satisfyingElements.flatMap(v => v.raw match { case s: String => Some(s); case _ => None })
        }
        assertTrue(
          result.isRight,
          satisfyingNames.contains(Set("Cyber", "Hardware"))
        )
      },

      test("B2: unknown quoted node name → Left(UnknownConstantOrLiteral)") {
        val text = """Q[>=]^{1/2} x (leaf_descendant_of(x, "Nonexistent"), gt_loss(p95(x), 1000))"""
        val parsed = VagueQueryParser.parse(text).toOption.get
        val bound  = QueryBinder.bind(parsed, kb.catalog)
        assertTrue(
          bound.isLeft,
          bound.left.toOption.exists(_.contains(TypeCheckError.UnknownConstantOrLiteral("Nonexistent")))
        )
      },

      test("B3: injection-shaped node name is rejected at SafeName construction with a clear error") {
        // SafeName now enforces ^[A-Za-z0-9 /\\-]+$ — the characters `"` and `)`
        // used in the canonical injection payload `foo")` are forbidden.
        // This test documents that:
        //   1. The injection-shaped name is rejected (not silently accepted)
        //   2. The rejection produces a structured ValidationError with a user-readable message
        //   3. The error code is INVALID_PATTERN so clients can distinguish it from
        //      REQUIRED_FIELD or INVALID_LENGTH
        //
        // The threat model tested by the original B3 (a node with `foo")` bypassing
        // DTO validators via a direct write path) is now CLOSED at the type level:
        // SafeName.fromString enforces the whitelist on every construction path.
        val payloadName = """foo")"""
        val result = SafeName.fromString(payloadName)

        import com.risquanter.register.domain.errors.ValidationErrorCode
        import com.risquanter.register.domain.data.iron.ValidationMessages

        assertTrue(
          result.isLeft,
          result.left.exists(_.exists(_.code == ValidationErrorCode.INVALID_PATTERN)),
          result.left.exists(_.exists(_.message == ValidationMessages.nameInvalidChars))
        )
      },

      test("B4: injection-shaped query string is rejected at parse or bind level — not evaluated") {
        // Documents the structural guarantee: if a node name were ever interpolated
        // into a query string (it is not — names are looked up via Map.get only),
        // the resulting malformed query would be caught before any dispatcher lambda
        // is invoked.
        //
        // Attack shape: "IT Risk" is a valid constant; the closing " terminates the
        // literal, and the characters that follow (`, gt_loss(p95(x), 0)`) would be
        // injection candidates. The trailing `""` (empty string literal) in argument
        // position produces either a parse error or an arity mismatch at bind time.
        //
        // If this test fails (assertTrue on a false value), the parser returned a
        // successful evaluation result for injection-shaped input. Stop immediately
        // and consult the user — do not attempt to work around the failure.
        val text = """Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 0"))"""
        val rejected = VagueQueryParser.parse(text) match
          case Left(_)       => true
          case Right(parsed) => QueryBinder.bind(parsed, kb.catalog).isLeft
        assertTrue(rejected)
      }
    )

end BinderIntegrationSpec
