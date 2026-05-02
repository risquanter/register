package com.risquanter.register.foladapter

import zio.test.*

import com.risquanter.register.domain.data.{RiskResult, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.NodeId
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
    minLoss = Some(1000L), maxLoss = Some(50000L), parentId = Some(itId)
  )
  private val hardwareLeaf = RiskLeaf.unsafeApply(
    id = hardwareId.value, name = "Hardware",
    distributionType = "lognormal", probability = 0.10,
    minLoss = Some(500L), maxLoss = Some(10000L), parentId = Some(itId)
  )

  private val allNodes: Map[NodeId, RiskNode] =
    Map(rootId -> rootPortfolio, itId -> itPortfolio, cyberId -> cyberLeaf, hardwareId -> hardwareLeaf)

  private val tree = RiskTree(
    id     = treeId("test-tree"),
    name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Test Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    index  = TreeIndex.fromNodesUnsafe(allNodes)
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
      index  = TreeIndex.fromNodesUnsafe(map)
    )

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("BinderIntegrationSpec — parse + bind against RiskTreeKnowledgeBase")(

      test("B1: quoted-literal scope query parses, binds, and evaluates with range = {Cyber, Hardware}") {
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
        val rangeNames = result.toOption.map { out =>
          out.satisfyingElements.flatMap(v => v.raw match { case s: String => Some(s); case _ => None })
        }
        assertTrue(
          result.isRight,
          rangeNames.contains(Set("Cyber", "Hardware"))
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

      test("B3: injection-shaped node name — binder echoes only the query token, never the embedded payload") {
        // Tree fixture: a single node whose name carries a grammar-meaningful payload.
        // SafeName allows arbitrary non-blank ≤50-char strings, so this construction is
        // a legal in-memory state reachable through any non-DTO write path.
        val rootIdStr = idStr("root-inj")
        val payloadIdStr = idStr("payload")
        val payloadName = """foo")"""
        val rootP = RiskPortfolio.unsafeApply(
          id = rootIdStr, name = "RootInj",
          childIds = Array(NodeId(safeId("payload"))), parentId = None
        )
        val payload = RiskLeaf.unsafeApply(
          id = payloadIdStr, name = payloadName,
          distributionType = "lognormal", probability = 0.1,
          minLoss = Some(1L), maxLoss = Some(2L),
          parentId = Some(NodeId(safeId("root-inj")))
        )
        val injTree = bypassTree(Seq(rootP, payload))
        val injKb   = RiskTreeKnowledgeBase(injTree, Map.empty)

        // KB constructed without exception; the payload-shaped name is registered as a constant.
        val kbBuilt = injKb.catalog.constants.contains(payloadName)

        // Query references the bare token "foo" (lexer's "-terminator stops at the first ")".
        val text   = """Q[>=]^{1/2} x (leaf_descendant_of(x, "foo"), leaf(x))"""
        val parsed = VagueQueryParser.parse(text).toOption.get
        val bound  = QueryBinder.bind(parsed, injKb.catalog)

        // Binder rejects with the offending token "foo" — never re-feeds the embedded
        // payload `foo")` into any error path. Locks PLAN §10's Map.get verdict in code:
        // if anyone swaps Map.get for string interpolation, this assertion catches it.
        val errors = bound.left.toOption.getOrElse(Nil)
        val unknownTokens = errors.collect {
          case TypeCheckError.UnknownConstantOrLiteral(name) => name
        }
        assertTrue(
          kbBuilt,
          bound.isLeft,
          unknownTokens.contains("foo"),
          !unknownTokens.exists(_.contains(payloadName))
        )
      }
    )

end BinderIntegrationSpec
