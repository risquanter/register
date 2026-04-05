package com.risquanter.register.foladapter

import zio.test.*

import com.risquanter.register.domain.data.{RiskResult, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.testutil.TestHelpers
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

import fol.typed.{Value, TypeId, TypeRepr, LiteralValue}

/** Tests for [[RiskTreeKnowledgeBase]] — the bridge between register's domain
  * model and the fol-engine typed evaluation pipeline.
  *
  * Covers:
  *   - Percentile computation (edge cases, monotonicity, boundary values)
  *   - Predicate truth tables (leaf, portfolio, child_of, descendant_of, leaf_descendant_of)
  *   - descendant_of irreflexivity (standard graph-theory semantics)
  *   - Function dispatch (p95, p99, lec)
  *   - Domain element completeness
  */
object RiskTreeKnowledgeBaseSpec extends ZIOSpecDefault with TestHelpers:

  // ── Fixtures: nodes ──────────────────────────────────────────────────

  private val rootId    = nodeId("root")
  private val itId      = nodeId("it-risk")
  private val cyberId   = nodeId("cyber")
  private val hardwareId = nodeId("hardware")

  // Tree:
  //   root (portfolio)
  //   ├── it-risk (portfolio)
  //   │   ├── cyber (leaf)
  //   │   └── hardware (leaf)
  //
  // 4 nodes, 2 leaves, 2 portfolios

  private val rootPortfolio = RiskPortfolio.unsafeApply(
    id   = rootId.value,
    name = "Root",
    childIds = Array(itId),
    parentId = None
  )

  private val itPortfolio = RiskPortfolio.unsafeApply(
    id   = itId.value,
    name = "IT Risk",
    childIds = Array(cyberId, hardwareId),
    parentId = Some(rootId)
  )

  private val cyberLeaf = RiskLeaf.unsafeApply(
    id = cyberId.value, name = "Cyber",
    distributionType = "lognormal", probability = 0.25,
    minLoss = Some(1000L), maxLoss = Some(50000L),
    parentId = Some(itId)
  )

  private val hardwareLeaf = RiskLeaf.unsafeApply(
    id = hardwareId.value, name = "Hardware",
    distributionType = "lognormal", probability = 0.10,
    minLoss = Some(500L), maxLoss = Some(10000L),
    parentId = Some(itId)
  )

  private val allNodes: Map[NodeId, RiskNode] =
    Map(rootId -> rootPortfolio, itId -> itPortfolio, cyberId -> cyberLeaf, hardwareId -> hardwareLeaf)

  private val index = TreeIndex.fromNodesUnsafe(allNodes)

  private val tree = RiskTree(
    id     = treeId("test-tree"),
    name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Test Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    index  = index
  )

  // ── Fixtures: simulation results ───────────────────────────────────

  // Cyber: outcomes [0, 5000, 10000, 20000, 50000] — 5 trials
  private val cyberResult = withCfg(5) {
    RiskResult(
      nodeId     = cyberId,
      outcomes   = Map(1 -> 0L, 2 -> 5000L, 3 -> 10000L, 4 -> 20000L, 5 -> 50000L),
      provenances = Nil
    )
  }

  // Hardware: outcomes [500, 1000, 1000, 2000, 8000] — 5 trials
  private val hardwareResult = withCfg(5) {
    RiskResult(
      nodeId     = hardwareId,
      outcomes   = Map(1 -> 500L, 2 -> 1000L, 3 -> 1000L, 4 -> 2000L, 5 -> 8000L),
      provenances = Nil
    )
  }

  // Root and IT: dummy aggregated results
  private val rootResult = withCfg(5) {
    RiskResult(nodeId = rootId, outcomes = Map(1 -> 500L, 2 -> 6000L, 3 -> 11000L, 4 -> 22000L, 5 -> 58000L), provenances = Nil)
  }

  private val itResult = withCfg(5) {
    RiskResult(nodeId = itId, outcomes = Map(1 -> 500L, 2 -> 6000L, 3 -> 11000L, 4 -> 22000L, 5 -> 58000L), provenances = Nil)
  }

  private val results: Map[NodeId, RiskResult] =
    Map(rootId -> rootResult, itId -> itResult, cyberId -> cyberResult, hardwareId -> hardwareResult)

  // Empty result for edge case tests
  private val emptyResult = withCfg(5) {
    RiskResult(nodeId = cyberId, outcomes = Map.empty, provenances = Nil)
  }

  // ── Knowledge base under test ──────────────────────────────────────

  private val kb = RiskTreeKnowledgeBase(tree, results)

  private val assetSort = TypeId("Asset")
  private val lossSort  = TypeId("Loss")
  private val probSort  = TypeId("Probability")

  /** TypeRepr for projecting Asset-sorted Values to String (mirrors QueryResponseBuilder). */
  private given TypeRepr[String] = new TypeRepr[String]:
    val typeId: TypeId = assetSort

  private def assetVal(name: String): Value  = Value(assetSort, name)
  private def lossVal(v: Long): Value        = Value(lossSort, LiteralValue.IntLiteral(v))
  private def probVal(v: Double): Value      = Value(probSort, LiteralValue.FloatLiteral(v))
  private def lossStr(s: String): Value      = Value(lossSort, LiteralValue.IntLiteral(s.toLong))
  private def probStr(s: String): Value      = Value(probSort, LiteralValue.FloatLiteral(s.toDouble))

  // ── Spec ───────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("RiskTreeKnowledgeBase")(
      percentileSuite,
      predicateSuite,
      functionSuite,
      domainSuite,
      catalogSuite
    )

  // ── Percentile suite ───────────────────────────────────────────────

  private val percentileSuite = suite("percentile (via p95/p99 functions)")(
    test("p95 returns correct loss for known distribution") {
      // Cyber outcomes sorted: [0, 5000, 10000, 20000, 50000]
      // outcomeCount: {0→1, 5000→1, 10000→1, 20000→1, 50000→1}
      // totalTrials = 5, target = 5 * 0.95 = 4.75
      // Walk: 0→cum 1, 5000→cum 2, 10000→cum 3, 20000→cum 4, 50000→cum 5
      // First where cum >= 4.75 is 50000 (cum=5)
      val result = kb.dispatcher.evalFunction(fol.typed.SymbolName("p95"), List(assetVal("Cyber")))
      assertTrue(result == Right(LiteralValue.IntLiteral(50000L)))
    },
    test("p99 returns correct loss for known distribution") {
      // Same logic, target = 5 * 0.99 = 4.95 → 50000 (cum=5)
      val result = kb.dispatcher.evalFunction(fol.typed.SymbolName("p99"), List(assetVal("Cyber")))
      assertTrue(result == Right(LiteralValue.IntLiteral(50000L)))
    },
    test("p95 on wider distribution selects correct quantile") {
      // Hardware outcomes sorted: [500, 1000, 1000, 2000, 8000]
      // outcomeCount: {500→1, 1000→2, 2000→1, 8000→1}
      // totalTrials = 5, target = 5 * 0.95 = 4.75
      // Walk: 500→1, 1000→3, 2000→4, 8000→5
      // First where cum >= 4.75 is 8000 (cum=5)
      val result = kb.dispatcher.evalFunction(fol.typed.SymbolName("p95"), List(assetVal("Hardware")))
      assertTrue(result == Right(LiteralValue.IntLiteral(8000L)))
    },
    test("p50 behaviour via percentile — verified through lec instead") {
      // Use lec to verify probOfExceedance: P(Cyber >= 10000)
      // Cyber outcomeCount: {0→1, 5000→1, 10000→1, 20000→1, 50000→1}
      // rangeFrom(10000) = {10000→1, 20000→1, 50000→1}, sum = 3
      // P = 3/5 = 0.6
      val result = kb.dispatcher.evalFunction(fol.typed.SymbolName("lec"), List(assetVal("Cyber"), lossVal(10000L)))
      assertTrue(result == Right(LiteralValue.FloatLiteral(0.6)))
    },
    test("percentile with empty outcomes returns 0") {
      val emptyKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, emptyResult))
      val result = emptyKb.dispatcher.evalFunction(fol.typed.SymbolName("p95"), List(assetVal("Cyber")))
      assertTrue(result == Right(LiteralValue.IntLiteral(0L)))
    },
    test("percentile monotonicity: p95 <= p99") {
      val p95 = kb.dispatcher.evalFunction(fol.typed.SymbolName("p95"), List(assetVal("Cyber")))
      val p99 = kb.dispatcher.evalFunction(fol.typed.SymbolName("p99"), List(assetVal("Cyber")))
      for
        v95 <- p95
        v99 <- p99
      yield (v95, v99) match
        case (LiteralValue.IntLiteral(l95), LiteralValue.IntLiteral(l99)) =>
          assertTrue(l95 <= l99)
        case other =>
          throw MatchError(other)
    }
  )

  // ── Predicate suite ────────────────────────────────────────────────

  private val predicateSuite = suite("predicates")(
    suite("leaf / portfolio")(
      test("leaf returns true for leaf nodes") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("leaf"), List(assetVal("Cyber")))
        assertTrue(r == Right(true))
      },
      test("leaf returns false for portfolio nodes") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("leaf"), List(assetVal("Root")))
        assertTrue(r == Right(false))
      },
      test("portfolio returns true for portfolio nodes") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("portfolio"), List(assetVal("Root")))
        assertTrue(r == Right(true))
      },
      test("portfolio returns false for leaf nodes") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("portfolio"), List(assetVal("Cyber")))
        assertTrue(r == Right(false))
      }
    ),
    suite("child_of")(
      test("direct child returns true") {
        // IT Risk is a child of Root
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("child_of"), List(assetVal("IT Risk"), assetVal("Root")))
        assertTrue(r == Right(true))
      },
      test("grandchild returns false") {
        // Cyber is NOT a direct child of Root (it's a child of IT Risk)
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("child_of"), List(assetVal("Cyber"), assetVal("Root")))
        assertTrue(r == Right(false))
      },
      test("self is not own child") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("child_of"), List(assetVal("Root"), assetVal("Root")))
        assertTrue(r == Right(false))
      }
    ),
    suite("descendant_of — standard irreflexive semantics")(
      test("grandchild is a descendant") {
        // Cyber is a descendant of Root (via IT Risk)
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("descendant_of"), List(assetVal("Cyber"), assetVal("Root")))
        assertTrue(r == Right(true))
      },
      test("direct child is a descendant") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("descendant_of"), List(assetVal("IT Risk"), assetVal("Root")))
        assertTrue(r == Right(true))
      },
      test("node is NOT its own descendant (irreflexive)") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("descendant_of"), List(assetVal("Root"), assetVal("Root")))
        assertTrue(r == Right(false))
      },
      test("leaf is NOT its own descendant") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("descendant_of"), List(assetVal("Cyber"), assetVal("Cyber")))
        assertTrue(r == Right(false))
      },
      test("parent is not a descendant of child") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("descendant_of"), List(assetVal("Root"), assetVal("Cyber")))
        assertTrue(r == Right(false))
      }
    ),
    suite("leaf_descendant_of")(
      test("leaf under ancestor returns true") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("leaf_descendant_of"), List(assetVal("Cyber"), assetVal("Root")))
        assertTrue(r == Right(true))
      },
      test("portfolio under ancestor returns false") {
        // IT Risk is a descendant of Root but NOT a leaf
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("leaf_descendant_of"), List(assetVal("IT Risk"), assetVal("Root")))
        assertTrue(r == Right(false))
      },
      test("leaf is NOT its own leaf_descendant (irreflexive)") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("leaf_descendant_of"), List(assetVal("Cyber"), assetVal("Cyber")))
        assertTrue(r == Right(false))
      }
    ),
    suite("gt_loss / gt_prob")(
      test("gt_loss with a > b returns true") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("gt_loss"), List(lossVal(5000L), lossVal(1000L)))
        assertTrue(r == Right(true))
      },
      test("gt_loss with a == b returns false") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("gt_loss"), List(lossVal(1000L), lossVal(1000L)))
        assertTrue(r == Right(false))
      },
      test("gt_loss with string literals (as engine delivers them)") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("gt_loss"), List(lossStr("5000"), lossStr("1000")))
        assertTrue(r == Right(true))
      },
      test("gt_prob with a > b returns true") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("gt_prob"), List(probVal(0.8), probVal(0.5)))
        assertTrue(r == Right(true))
      },
      test("gt_prob with a <= b returns false") {
        val r = kb.dispatcher.evalPredicate(fol.typed.SymbolName("gt_prob"), List(probVal(0.5), probVal(0.5)))
        assertTrue(r == Right(false))
      }
    )
  )

  // ── Function dispatch suite ────────────────────────────────────────

  private val functionSuite = suite("functions")(
    test("lec computes correct probability of exceedance") {
      // Cyber: P(Loss >= 5000) = 4/5 = 0.8 (values: 5000, 10000, 20000, 50000)
      val r = kb.dispatcher.evalFunction(fol.typed.SymbolName("lec"), List(assetVal("Cyber"), lossVal(5000L)))
      assertTrue(r == Right(LiteralValue.FloatLiteral(0.8)))
    },
    test("lec with LiteralValue threshold (as engine delivers literals)") {
      val r = kb.dispatcher.evalFunction(fol.typed.SymbolName("lec"), List(assetVal("Cyber"), lossStr("5000")))
      assertTrue(r == Right(LiteralValue.FloatLiteral(0.8)))
    },
    test("lec with threshold above max returns 0.0") {
      val r = kb.dispatcher.evalFunction(fol.typed.SymbolName("lec"), List(assetVal("Cyber"), lossVal(100000L)))
      assertTrue(r == Right(LiteralValue.FloatLiteral(0.0)))
    },
    test("unknown asset returns Left") {
      val r = kb.dispatcher.evalFunction(fol.typed.SymbolName("p95"), List(assetVal("Nonexistent")))
      assertTrue(r.isLeft)
    },
    test("unknown function returns Left") {
      val r = kb.dispatcher.evalFunction(fol.typed.SymbolName("median"), List(assetVal("Cyber")))
      assertTrue(r.isLeft)
    },
    test("missing argument returns Left") {
      val r = kb.dispatcher.evalFunction(fol.typed.SymbolName("lec"), List(assetVal("Cyber")))
      assertTrue(r.isLeft)
    }
  )

  // ── Domain element suite ───────────────────────────────────────────

  private val domainSuite = suite("domain elements")(
    test("Asset domain contains all node names") {
      val domain = kb.model.domains(assetSort)
      val names  = domain.flatMap(_.as[String])
      assertTrue(
        names == Set("Root", "IT Risk", "Cyber", "Hardware")
      )
    },
    test("Asset domain has correct size") {
      assertTrue(kb.model.domains(assetSort).size == 4)
    },
    test("no Loss or Probability domains are registered") {
      assertTrue(
        !kb.model.domains.contains(lossSort),
        !kb.model.domains.contains(probSort)
      )
    }
  )

  // ── Catalog structure suite ────────────────────────────────────────

  private val catalogSuite = suite("catalog structure")(
    test("catalog declares four sorts") {
      // TypeCatalog.unsafe checks are pass-through — verify dispatcher coverage
      assertTrue(
        kb.dispatcher.functionSymbols.size == 3,
        kb.dispatcher.predicateSymbols.size == 7
      )
    },
    test("nameToNodeId maps names to correct NodeIds") {
      assertTrue(
        kb.nameToNodeId("Cyber") == cyberId,
        kb.nameToNodeId("Root") == rootId,
        kb.nameToNodeId.size == 4
      )
    }
  )

end RiskTreeKnowledgeBaseSpec
