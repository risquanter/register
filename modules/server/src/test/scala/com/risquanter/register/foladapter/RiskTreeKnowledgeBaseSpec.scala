package com.risquanter.register.foladapter

import zio.test.*

import com.risquanter.register.domain.data.{RiskResult, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.SeedVarId
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.errors.FolQueryFailure
import com.risquanter.register.testutil.TestHelpers
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

import vql.parser.VagueQueryParser
import vql.semantics.VagueSemantics
import vql.typed.{Value, TypeId, FolModel, QueryBinder, TypeCheckError}

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
    parentId = Some(itId),
    seedVarId = 1L
  )

  private val hardwareLeaf = RiskLeaf.unsafeApply(
    id = hardwareId.value, name = "Hardware",
    distributionType = "lognormal", probability = 0.10,
    minLoss = Some(500L), maxLoss = Some(10000L),
    parentId = Some(itId),
    seedVarId = 2L
  )

  private val allNodes: Map[NodeId, RiskNode] =
    Map(rootId -> rootPortfolio, itId -> itPortfolio, cyberId -> cyberLeaf, hardwareId -> hardwareLeaf)

  private val index = TreeIndex.fromNodesUnsafe(allNodes)

  private val tree = RiskTree(
    id     = treeId("test-tree"),
    name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Test Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    index  = index,
      seedVarHighWater = SeedVarId.fromLong(1000L).toOption.get
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

  // Sparse result for unconditional VaR tests: nTrials=10, only 3 outcomes fire
  // outcomeCount: {5000→1, 10000→1, 50000→1}, implicitZeros = 10 - 3 = 7
  private val sparseCyberResult = withCfg(10) {
    RiskResult(nodeId = cyberId, outcomes = Map(1 -> 5000L, 2 -> 10000L, 3 -> 50000L), provenances = Nil)
  }
  private val sparseHardwareResult = withCfg(10) {
    RiskResult(nodeId = hardwareId, outcomes = Map(1 -> 500L, 2 -> 1000L, 3 -> 2000L), provenances = Nil)
  }
  private val sparseRootResult = withCfg(10) {
    RiskResult(nodeId = rootId, outcomes = Map(1 -> 5500L, 2 -> 11000L, 3 -> 52000L), provenances = Nil)
  }
  private val sparseItResult = withCfg(10) {
    RiskResult(nodeId = itId, outcomes = Map(1 -> 5500L, 2 -> 11000L, 3 -> 52000L), provenances = Nil)
  }
  private val sparseResults: Map[NodeId, RiskResult] =
    Map(rootId -> sparseRootResult, itId -> sparseItResult, cyberId -> sparseCyberResult, hardwareId -> sparseHardwareResult)

  // ── Knowledge base under test ──────────────────────────────────────

  private val kb = RiskTreeKnowledgeBase(tree, results)

  private val nodeSort            = RiskTreeKnowledgeBase.NodeSort
  private val lossSort            = TypeId("Loss")
  private val probSort            = TypeId("Probability")
  private val nodeNameLiteralSort = RiskTreeKnowledgeBase.NodeNameLiteralSort
  private val nodeIdLiteralSort   = RiskTreeKnowledgeBase.NodeIdLiteralSort

  /** A node-sorted `Value` carrying the fixture node's `NodeId`, looked up by
    * name. An unknown name yields a `NodeId` that is not in the tree. */
  private def nodeVal(name: String): Value =
    Value(nodeSort, name match
      case "Root"     => rootId
      case "IT Risk"  => itId
      case "Cyber"    => cyberId
      case "Hardware" => hardwareId
      case other      => nodeId(other)  // e.g. "Nonexistent" — a NodeId absent from the tree
    )
  private def lossVal(v: Long): Value        = Value(lossSort, v)
  private def probVal(v: Double): Value      = Value(probSort, v)
  private def lossStr(s: String): Value      = Value(lossSort, s.toLongOption.get)
  private def probStr(s: String): Value      = Value(probSort, s.toDoubleOption.get)

  // ── Spec ───────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("RiskTreeKnowledgeBase")(
      percentileSuite,
      unconditionalVarSuite,
      predicateSuite,
      functionSuite,
      domainSuite,
      catalogSuite,
      constantsSuite,
      nodeReferenceQuerySuite
    )

  // ── Percentile suite ───────────────────────────────────────────────

  private val percentileSuite = suite("percentile (via p95/p99 functions)")(
    test("p95 returns correct loss for known distribution") {
      // Cyber outcomes sorted: [0, 5000, 10000, 20000, 50000]
      // outcomeCount: {0→1, 5000→1, 10000→1, 20000→1, 50000→1}
      // totalTrials = 5, target = 5 * 0.95 = 4.75
      // Walk: 0→cum 1, 5000→cum 2, 10000→cum 3, 20000→cum 4, 50000→cum 5
      // First where cum >= 4.75 is 50000 (cum=5)
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      assertTrue(result == Right(50000L))
    },
    test("p99 returns correct loss for known distribution") {
      // Same logic, target = 5 * 0.99 = 4.95 → 50000 (cum=5)
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      assertTrue(result == Right(50000L))
    },
    test("p95 on wider distribution selects correct quantile") {
      // Hardware outcomes sorted: [500, 1000, 1000, 2000, 8000]
      // outcomeCount: {500→1, 1000→2, 2000→1, 8000→1}
      // totalTrials = 5, target = 5 * 0.95 = 4.75
      // Walk: 500→1, 1000→3, 2000→4, 8000→5
      // First where cum >= 4.75 is 8000 (cum=5)
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Hardware")))
      assertTrue(result == Right(8000L))
    },
    test("p50 behaviour via percentile — verified through lec instead") {
      // Use lec to verify probOfExceedance: P(Cyber >= 10000)
      // Cyber outcomeCount: {0→1, 5000→1, 10000→1, 20000→1, 50000→1}
      // rangeFrom(10000) = {10000→1, 20000→1, 50000→1}, sum = 3
      // P = 3/5 = 0.6
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(10000L)))
      assertTrue(result == Right(0.6))
    },
    test("percentile with empty outcomes returns 0") {
      val emptyKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, emptyResult))
      val result = emptyKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      assertTrue(result == Right(0L))
    },
    test("percentile monotonicity: p95 <= p99") {
      val p95 = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      val p99 = kb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      for
        v95 <- p95
        v99 <- p99
      yield (v95, v99) match
        case (l95: Long, l99: Long) =>
          assertTrue(l95 <= l99)
        case other =>
          throw MatchError(other)
    }
  )

  // ── Unconditional VaR suite (sparse results) ──────────────────────

  private val unconditionalVarSuite = suite("unconditional VaR (sparse results)")(
    test("p95 with sparse outcomes — target in non-zero range") {
      // sparseCyberResult: nTrials=10, outcomes = {5000→1, 10000→1, 50000→1}
      // outcomeCount: {5000→1, 10000→1, 50000→1}, implicitZeros = 10 - 3 = 7
      // target = 10 * 0.95 = 9.5
      // cumulative starts at 7 (zeros), then 8, 9, 10
      // Walk: 5000→cum 8, 10000→cum 9, 50000→cum 10
      // First where cum >= 9.5 is 50000 (cum=10)
      val sparseKb = RiskTreeKnowledgeBase(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      assertTrue(result == Right(50000L))
    },
    test("p95 on sparse hardware — walks past zero mass into tail") {
      // Hardware: outcomeCount {500→1, 1000→1, 2000→1}, implicitZeros=7
      // target = 10 * 0.95 = 9.5, cum: 7, 8, 9, 10
      // Walk: 500→8, 1000→9, 2000→10 → first >= 9.5 is 2000
      val sparseKb = RiskTreeKnowledgeBase(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Hardware")))
      assertTrue(result == Right(2000L))
    },
    test("p99 with low occurrence returns last outcome") {
      // sparseCyberResult: nTrials=10, implicitZeros=7
      // target = 10 * 0.99 = 9.9
      // Walk: 5000→8, 10000→9, 50000→10 → first >= 9.9 is 50000
      val sparseKb = RiskTreeKnowledgeBase(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      assertTrue(result == Right(50000L))
    },
    test("p95 with very sparse results returns 0 — target deep in zero mass") {
      // nTrials=100, only 2 outcomes → implicitZeros = 98
      // target = 100 * 0.95 = 95.0
      // cumulative starts at 98 >= 95.0 → 0L
      val verySparse = withCfg(100) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 10000L, 2 -> 50000L), provenances = Nil)
      }
      val sparseKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, verySparse))
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      assertTrue(result == Right(0L))
    },
    test("single outcome with many implicit zeros — p95 in zero mass, p99 in zero mass") {
      // nTrials=100, 1 outcome {1→42000} → outcomeCount={42000→1}, implicitZeros=99
      // p95 target=95.0, 99 >= 95 → 0L
      // p99 target=99.0, 99 >= 99 → 0L (boundary: 99.0 >= 99.0 is true)
      val single = withCfg(100) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 42000L), provenances = Nil)
      }
      val singleKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, single))
      val p95 = singleKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      val p99 = singleKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      assertTrue(
        p95 == Right(0L),
        p99 == Right(0L)
      )
    },
    test("all outcomes identical — single bin in outcomeCount") {
      // nTrials=10, 5 outcomes all = 7000 → outcomeCount={7000→5}, implicitZeros=5
      // p95 target=9.5, cum starts at 5, walk: {7000→5} → cum=10. 10 >= 9.5 → 7000L
      // p99 target=9.9, same → 7000L
      val identical = withCfg(10) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 7000L, 2 -> 7000L, 3 -> 7000L, 4 -> 7000L, 5 -> 7000L), provenances = Nil)
      }
      val identKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, identical))
      val p95 = identKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      val p99 = identKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      assertTrue(
        p95 == Right(7000L),
        p99 == Right(7000L)
      )
    },
    test("exact boundary — implicitZeros == target at p95") {
      // nTrials=20, 1 outcome → implicitZeros=19, p95 target=19.0
      // 19.0 >= 19.0 → true → returns 0L (percentile is AT the boundary of zero mass)
      // This is correct: 95% of trials have $0 loss = VaR₉₅ is $0
      val boundary = withCfg(20) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 30000L), provenances = Nil)
      }
      val bKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, boundary))
      val p95 = bKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      assertTrue(p95 == Right(0L))
    },
    test("just past boundary — implicitZeros just below target at p99") {
      // nTrials=20, 1 outcome → implicitZeros=19, p99 target=19.8
      // 19.0 < 19.8, walk: {30000→1} → cum=20. 20 >= 19.8 → 30000L
      // Correct: 1 of 20 trials exceeds the 99th percentile → last outcome
      val boundary = withCfg(20) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 30000L), provenances = Nil)
      }
      val bKb = RiskTreeKnowledgeBase(tree, results.updated(cyberId, boundary))
      val p99 = bKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      assertTrue(p99 == Right(30000L))
    },
    test("lec is unaffected by sparse results — still unconditional") {
      // sparseCyberResult: nTrials=10, outcomes = {5000, 10000, 50000}
      // P(Loss >= 5000) = count(outcomes >= 5000) / nTrials = 3/10 = 0.3
      val sparseKb = RiskTreeKnowledgeBase(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(5000L)))
      assertTrue(result == Right(0.3))
    },
    test("monotonicity holds with sparse outcomes: p95 <= p99") {
      val sparseKb = RiskTreeKnowledgeBase(tree, sparseResults)
      val p95 = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber")))
      val p99 = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber")))
      for
        v95 <- p95
        v99 <- p99
      yield (v95, v99) match
        case (l95: Long, l99: Long) =>
          assertTrue(l95 <= l99)
        case other =>
          throw MatchError(other)
    }
  )

  // ── Predicate suite ────────────────────────────────────────────────

  private val predicateSuite = suite("predicates")(
    suite("leaf / portfolio")(
      test("leaf returns true for leaf nodes") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("leaf"), List(nodeVal("Cyber")))
        assertTrue(r == Right(true))
      },
      test("leaf returns false for portfolio nodes") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("leaf"), List(nodeVal("Root")))
        assertTrue(r == Right(false))
      },
      test("portfolio returns true for portfolio nodes") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("portfolio"), List(nodeVal("Root")))
        assertTrue(r == Right(true))
      },
      test("portfolio returns false for leaf nodes") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("portfolio"), List(nodeVal("Cyber")))
        assertTrue(r == Right(false))
      }
    ),
    suite("child_of")(
      test("direct child returns true") {
        // IT Risk is a child of Root
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("child_of"), List(nodeVal("IT Risk"), nodeVal("Root")))
        assertTrue(r == Right(true))
      },
      test("grandchild returns false") {
        // Cyber is NOT a direct child of Root (it's a child of IT Risk)
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("child_of"), List(nodeVal("Cyber"), nodeVal("Root")))
        assertTrue(r == Right(false))
      },
      test("self is not own child") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("child_of"), List(nodeVal("Root"), nodeVal("Root")))
        assertTrue(r == Right(false))
      }
    ),
    suite("descendant_of — standard irreflexive semantics")(
      test("grandchild is a descendant") {
        // Cyber is a descendant of Root (via IT Risk)
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("descendant_of"), List(nodeVal("Cyber"), nodeVal("Root")))
        assertTrue(r == Right(true))
      },
      test("direct child is a descendant") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("descendant_of"), List(nodeVal("IT Risk"), nodeVal("Root")))
        assertTrue(r == Right(true))
      },
      test("node is NOT its own descendant (irreflexive)") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("descendant_of"), List(nodeVal("Root"), nodeVal("Root")))
        assertTrue(r == Right(false))
      },
      test("leaf is NOT its own descendant") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("descendant_of"), List(nodeVal("Cyber"), nodeVal("Cyber")))
        assertTrue(r == Right(false))
      },
      test("parent is not a descendant of child") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("descendant_of"), List(nodeVal("Root"), nodeVal("Cyber")))
        assertTrue(r == Right(false))
      }
    ),
    suite("leaf_descendant_of")(
      test("leaf under ancestor returns true") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("leaf_descendant_of"), List(nodeVal("Cyber"), nodeVal("Root")))
        assertTrue(r == Right(true))
      },
      test("portfolio under ancestor returns false") {
        // IT Risk is a descendant of Root but NOT a leaf
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("leaf_descendant_of"), List(nodeVal("IT Risk"), nodeVal("Root")))
        assertTrue(r == Right(false))
      },
      test("leaf is NOT its own leaf_descendant (irreflexive)") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("leaf_descendant_of"), List(nodeVal("Cyber"), nodeVal("Cyber")))
        assertTrue(r == Right(false))
      }
    ),
    suite("gt_loss / gt_prob")(
      test("gt_loss with a > b returns true") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("gt_loss"), List(lossVal(5000L), lossVal(1000L)))
        assertTrue(r == Right(true))
      },
      test("gt_loss with a == b returns false") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("gt_loss"), List(lossVal(1000L), lossVal(1000L)))
        assertTrue(r == Right(false))
      },
      test("gt_loss with string literals (as engine delivers them)") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("gt_loss"), List(lossStr("5000"), lossStr("1000")))
        assertTrue(r == Right(true))
      },
      test("gt_prob with a > b returns true") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("gt_prob"), List(probVal(0.8), probVal(0.5)))
        assertTrue(r == Right(true))
      },
      test("gt_prob with a <= b returns false") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("gt_prob"), List(probVal(0.5), probVal(0.5)))
        assertTrue(r == Right(false))
      }
    ),
    suite("eq (node identity)")(
      test("same node id returns true") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("eq"), List(nodeVal("Cyber"), nodeVal("Cyber")))
        assertTrue(r == Right(true))
      },
      test("different node ids return false") {
        val r = kb.dispatcher.evalPredicate(vql.typed.SymbolName("eq"), List(nodeVal("Cyber"), nodeVal("Hardware")))
        assertTrue(r == Right(false))
      }
    )
  )

  // ── Function dispatch suite ────────────────────────────────────────

  private val functionSuite = suite("functions")(
    test("lec computes correct probability of exceedance") {
      // Cyber: P(Loss >= 5000) = 4/5 = 0.8 (values: 5000, 10000, 20000, 50000)
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(5000L)))
      assertTrue(r == Right(0.8))
    },
    test("lec with raw Long threshold (as engine delivers literals)") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossStr("5000")))
      assertTrue(r == Right(0.8))
    },
    test("lec with threshold above max returns 0.0") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(100000L)))
      assertTrue(r == Right(0.0))
    },
    test("unknown asset returns Left") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Nonexistent")))
      assertTrue(r.isLeft)
    },
    test("unknown function returns Left") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("median"), List(nodeVal("Cyber")))
      assertTrue(r.isLeft)
    }
  )

  // ── Domain element suite ───────────────────────────────────────────

  private val domainSuite = suite("domain elements")(
    test("Node domain contains all node ids") {
      val domain = kb.model.domains(nodeSort)
      val ids    = domain.flatMap(v => v.raw match { case id: NodeId => Some(id); case _ => None })
      assertTrue(
        ids == Set(rootId, itId, cyberId, hardwareId)
      )
    },
    test("Node domain has correct size") {
      assertTrue(kb.model.domains(nodeSort).size == 4)
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
    test("catalog declares five sorts") {
      // TypeCatalog.unsafe checks are pass-through — verify sort and dispatcher coverage
      assertTrue(
        kb.catalog.typeIds.size == 5,
        kb.dispatcher.functionSymbols.size == 3,
        kb.dispatcher.predicateSymbols.size == 10
      )
    },
    test("nameToId maps names to correct NodeIds") {
      assertTrue(
        kb.nameToId("Cyber") == cyberId,
        kb.nameToId("Root") == rootId,
        kb.nameToId.size == 4
      )
    }
  )

  // ── Constants suite (PLAN-QUERY-NODE-NAME-LITERALS §5.4) ───────────

  /** Build a RiskTree directly from a list of nodes, bypassing DTO validation.
    * Simulates an unsupported path (direct repo write / migration / Irmin merge)
    * where a malformed tree could reach the KB. The first node is treated as
    * the root.
    */
  private def bypassTree(nodes: Seq[RiskNode]): RiskTree =
    val map: Map[NodeId, RiskNode] = nodes.iterator.map(n => n.id -> n).toMap
    val root: NodeId = nodes.head.id
    val idx = TreeIndex.fromNodesUnsafe(map)
    RiskTree(
      id     = treeId("bypass-tree"),
      name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Bypass Tree").toOption.get,
      nodes  = nodes,
      rootId = root,
      index  = idx,
      seedVarHighWater = SeedVarId.fromLong(1000L).toOption.get
    )

  private val constantsSuite = suite("node constants via literal validator (PLAN §5.4)")(
    test("C1: 4-node fixture — no catalog constants; nameToId carries the names; per-sort validators resolve name vs id; no collisions") {
      val nodeV = kb.catalog.literalValidators(nodeSort)
      val nameV = kb.catalog.literalValidators(nodeNameLiteralSort)
      val idV   = kb.catalog.literalValidators(nodeIdLiteralSort)
      assertTrue(
        kb.catalog.constants.isEmpty,
        kb.nameToId.keySet == Set("Root", "IT Risk", "Cyber", "Hardware"),
        nodeV("Cyber")       == Some(cyberId),   // Node slot: quoted node name → its id
        nodeV(cyberId.value) == None,            // Node slot is name-only: an id no longer binds
        nodeV("Nonexistent") == None,            // unknown name → unbindable
        nameV("Cyber")       == Some(cyberId),   // named: name → id
        nameV(cyberId.value) == None,            // named rejects an id
        idV(cyberId.value)   == Some(cyberId),   // has_id: id → id
        idV("Cyber")         == None,            // has_id rejects a name
        kb.nameCollisions.isEmpty
      )
    },
    test("C2: duplicate node names — last-write-wins; collision surfaced") {
      // Two distinct nodes both named "Cyber" — bypasses DTO requireUniqueNames.
      val rootIdStr = idStr("root2")
      val aIdStr    = idStr("a")
      val bIdStr    = idStr("b")
      val rootP = RiskPortfolio.unsafeApply(
        id = rootIdStr, name = "Root2",
        childIds = Array(NodeId(safeId("a")), NodeId(safeId("b"))), parentId = None
      )
      val a = RiskLeaf.unsafeApply(
        id = aIdStr, name = "Cyber",
        distributionType = "lognormal", probability = 0.1,
        minLoss = Some(1L), maxLoss = Some(2L), parentId = Some(NodeId(safeId("root2"))),
        seedVarId = 3L
      )
      val b = RiskLeaf.unsafeApply(
        id = bIdStr, name = "Cyber",
        distributionType = "lognormal", probability = 0.2,
        minLoss = Some(3L), maxLoss = Some(4L), parentId = Some(NodeId(safeId("root2"))),
        seedVarId = 4L
      )
      val t = bypassTree(Seq(rootP, a, b))
      val kb2 = RiskTreeKnowledgeBase(t, Map.empty)
      assertTrue(
        kb2.nameToId.keySet == Set("Root2", "Cyber"),
        kb2.nameCollisions.exists(_.contains("Cyber")),
        kb2.nameCollisions.count(_.startsWith("duplicate:")) == 1
      )
    },
    test("C3: reserved-name collision (\"leaf\") — skipped from constants; predicate retained") {
      val rootIdStr = idStr("root3")
      val cIdStr    = idStr("c")
      val rootP = RiskPortfolio.unsafeApply(
        id = rootIdStr, name = "Root3",
        childIds = Array(NodeId(safeId("c"))), parentId = None
      )
      val c = RiskLeaf.unsafeApply(
        id = cIdStr, name = "leaf",
        distributionType = "lognormal", probability = 0.1,
        minLoss = Some(1L), maxLoss = Some(2L), parentId = Some(NodeId(safeId("root3"))),
        seedVarId = 5L
      )
      val t = bypassTree(Seq(rootP, c))
      val kb3 = RiskTreeKnowledgeBase(t, Map.empty)
      assertTrue(
        !kb3.nameToId.contains("leaf"),
        kb3.catalog.predicates.contains(vql.typed.SymbolName("leaf")),
        kb3.nameCollisions.exists(s => s.startsWith("reserved:") && s.endsWith("leaf"))
      )
    },
    test("C4: reservedFolNames equals the union of catalog function & predicate symbol names") {
      // Drift guard for the FolSymbols.reservedNames literal. That literal is a
      // manual mirror of the catalog's symbol names, held in `common` because the
      // DTO gate needs it there and cannot see the server-side catalog. This is
      // the one place both are visible, so it asserts they still agree — add a
      // catalog symbol without updating the literal and this goes red.
      val symbolStrings: Set[String] =
        kb.catalog.functions.keySet.map(_.value) ++ kb.catalog.predicates.keySet.map(_.value)
      val baseline = Set(
        "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
        "gt_loss", "gt_prob", "eq", "named", "has_id", "p95", "p99", "lec"
      )
      assertTrue(
        kb.reservedFolNames == symbolStrings,
        baseline.subsetOf(kb.reservedFolNames)
      )
    },
    test("node-reference sort discriminators match the classifier's names") {
      // Drift guard for the bind-error classifier's sort-name literals in
      // `common` (FolQueryFailure.NodeSortName / NodeNameLiteralSortName), which
      // it compares against UnparseableConstant.sortName to classify a
      // nonexistent-node reference as UNKNOWN_REFERENCE. The catalog is
      // server-side and off `common`'s dependency graph, so those literals are
      // manual mirrors of the catalog sorts, held here where both are visible —
      // change one without the other and this goes red.
      assertTrue(
        RiskTreeKnowledgeBase.NodeSort.value            == FolQueryFailure.NodeSortName,
        RiskTreeKnowledgeBase.NodeNameLiteralSort.value == FolQueryFailure.NodeNameLiteralSortName
      )
    }
  )

  // ── Node-reference predicate queries (§8.12: named / has_id) ────────

  private val idToName: Map[NodeId, String] =
    allNodes.map { case (id, n) => id -> n.name.value }

  /** Parse + bind + evaluate a full query against this KB; returns the set of
    * satisfying node names, or None if parse/eval failed. */
  private def satisfyingNames(text: String): Option[Set[String]] =
    val parsed = VagueQueryParser.parse(text).toOption.get
    val result = for
      folModel <- FolModel(kb.catalog, kb.model)
      output   <- VagueSemantics.evaluateTyped(parsed, folModel)
    yield output
    result.toOption.map { out =>
      out.satisfyingElements.flatMap(v => v.raw match { case id: NodeId => idToName.get(id); case _ => None })
    }

  private val nodeReferenceQuerySuite = suite("node-reference predicate queries (§8.12: named / has_id)")(
    test("named(x, \"IT Risk\") pins that node") {
      // named's 2nd arg is the NodeNameLiteral sort; "IT Risk" resolves via
      // nameToId.get to itId, so the satisfying set is exactly {IT Risk}.
      val names = satisfyingNames("""Q[>=]^{1/1} x (named(x, "IT Risk"), portfolio(x))""")
      assertTrue(names.contains(Set("IT Risk")))
    },
    test("has_id(x, <cyberId>) pins Cyber") {
      // has_id's 2nd arg is the NodeIdLiteral sort; the ulid parses via
      // NodeId.fromString to cyberId, so the satisfying set is exactly {Cyber}.
      val names = satisfyingNames(s"""Q[>=]^{1/1} x (has_id(x, "${cyberId.value}"), leaf(x))""")
      assertTrue(names.contains(Set("Cyber")))
    },
    test("has_id(x, \"IT Risk\") → UnparseableConstant on the NodeIdLiteral sort") {
      // A name in an id slot: NodeId.fromString("IT Risk") = None → malformed id.
      val parsed = VagueQueryParser.parse("""Q[>=]^{1/1} x (has_id(x, "IT Risk"), leaf(x))""").toOption.get
      val bound  = QueryBinder.bind(parsed, kb.catalog)
      assertTrue(
        bound.isLeft,
        bound.left.toOption.exists(_.exists {
          case TypeCheckError.UnparseableConstant(name, sort, _) => name == "IT Risk" && sort == nodeIdLiteralSort
          case _                                                 => false
        })
      )
    },
    test("named(x, <cyberId>) → UnparseableConstant on the NodeNameLiteral sort") {
      // An id in a name slot: nameToId.get(<ulid>) = None → no such node name.
      val parsed = VagueQueryParser.parse(s"""Q[>=]^{1/1} x (named(x, "${cyberId.value}"), leaf(x))""").toOption.get
      val bound  = QueryBinder.bind(parsed, kb.catalog)
      assertTrue(
        bound.isLeft,
        bound.left.toOption.exists(_.exists {
          case TypeCheckError.UnparseableConstant(name, sort, _) => name == cyberId.value && sort == nodeNameLiteralSort
          case _                                                 => false
        })
      )
    }
  )

end RiskTreeKnowledgeBaseSpec
