package com.risquanter.register.foladapter

import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.{
  RiskResult, RiskLeaf, RiskPortfolio, RiskNode, RiskTree, LossDistribution,
  MitigationSelection, ScopeRestriction,
  Mitigation, MitigationTarget, MitigationSpec, MitigationPrecedence, TargetingPredicate,
  TransformPipeline, ResultTransformSpec
}
import com.risquanter.register.domain.data.iron.{NodeId, MitigationId, SafeName, SeedVarId}
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
  *   - Value function dispatch (p95, p99, lec) and their mitigation selection slot
  *   - Mitigation predicates (mitigate, mitigated, unmitigated) and references
  *     (named_mitigation, mitigation_id)
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

  private val rootPortfolio = unsafeGet(RiskPortfolio.create(
    id   = rootId.value,
    name = "Root",
    childIds = Array(itId),
    parentId = None
  ), "portfolio")

  private val itPortfolio = unsafeGet(RiskPortfolio.create(
    id   = itId.value,
    name = "IT Risk",
    childIds = Array(cyberId, hardwareId),
    parentId = Some(rootId)
  ), "portfolio")

  private val cyberLeaf = unsafeGet(RiskLeaf.create(
    id = cyberId.value, name = "Cyber",
    distributionType = "lognormal", probability = 0.25,
    minLoss = Some(1000L), maxLoss = Some(50000L),
    parentId = Some(itId),
    seedVarId = 1L
  ), "leaf")

  private val hardwareLeaf = unsafeGet(RiskLeaf.create(
    id = hardwareId.value, name = "Hardware",
    distributionType = "lognormal", probability = 0.10,
    minLoss = Some(500L), maxLoss = Some(10000L),
    parentId = Some(itId),
    seedVarId = 2L
  ), "leaf")

  private val allNodes: Map[NodeId, RiskNode] =
    Map(rootId -> rootPortfolio, itId -> itPortfolio, cyberId -> cyberLeaf, hardwareId -> hardwareLeaf)

  private val tree = RiskTree.fromNodesUnsafe(
    id     = treeId("test-tree"),
    name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Test Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    seedVarHighWater = Some(SeedVarId.fromLong(1000L).toOption.get),
    mitigations = Nil
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

  /** Widen a `RiskResult` result map to the `LossDistribution` value type the KB
    * constructor expects (`Map` is invariant, so the widening is explicit). */
  private def widen(res: Map[NodeId, RiskResult]): Map[NodeId, LossDistribution] =
    res.map { case (k, v) => k -> (v: LossDistribution) }

  /** Build a KB whose only precomputed valuation is the inherent (base) one and
    * whose resolved-scope set is empty — the shape every pre-M3 test needs. */
  private def kbInherent(t: RiskTree, res: Map[NodeId, RiskResult]): RiskTreeKnowledgeBase =
    RiskTreeKnowledgeBase(RiskTreeKnowledgeBase.schemaFor(t), Map(MitigationSelection.Inherent -> widen(res)), Map.empty)

  private val kb = kbInherent(tree, results)

  private val nodeSort                  = RiskTreeKnowledgeBase.NodeSort
  private val lossSort                  = TypeId("Loss")
  private val probSort                  = TypeId("Probability")
  private val nodeNameLiteralSort       = RiskTreeKnowledgeBase.NodeNameLiteralSort
  private val nodeIdLiteralSort         = RiskTreeKnowledgeBase.NodeIdLiteralSort
  private val mitigationSort            = RiskTreeKnowledgeBase.MitigationSort
  private val mitigationNameLiteralSort = RiskTreeKnowledgeBase.MitigationNameLiteralSort
  private val mitigationIdLiteralSort   = RiskTreeKnowledgeBase.MitigationIdLiteralSort

  /** The mitigation-slot values the value functions read: the two aggregate
    * constants (carrying their own name string) and a bound-mitigation value
    * (carrying a `MitigationId`). */
  private val inherent: Value = Value(mitigationSort, RiskTreeKnowledgeBase.InherentConst)
  private val residual: Value = Value(mitigationSort, RiskTreeKnowledgeBase.ResidualConst)
  private def mitVal(id: MitigationId): Value = Value(mitigationSort, id)

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

  // ── Fixtures: mitigations + resolved scopes (M3) ────────────────────
  //
  // The same 4-node tree, now carrying three mitigations. `resolvedScopes` maps
  // two of them to node scopes; the third (`mAbsent`) is omitted, modelling a
  // Failed mitigation excluded upstream from `ResolvedScopes.appliedScopes`
  // (M3-D2=A) — the KB must treat it as contributing nothing.

  private val mCyberId    = mitigationId("m-cyber")
  private val mHardwareId = mitigationId("m-hardware")
  private val mAbsentId   = mitigationId("m-absent")

  private def mkMit(idLabel: String, nm: String): Mitigation =
    Mitigation.create(
      mitigationId(idLabel),
      SafeName.fromString(nm).toOption.get,
      MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get),
      MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.CapLosses(1000000L)))),
      MitigationPrecedence.default
    ).toEither.toOption.get

  private val mCyber    = mkMit("m-cyber", "Cyber Mitigation")
  private val mHardware = mkMit("m-hardware", "Hardware Mitigation")
  private val mAbsent   = mkMit("m-absent", "Absent Mitigation")

  private val mitTree = RiskTree.fromNodesUnsafe(
    id     = treeId("mit-tree"),
    name   = SafeName.fromString("Mit Tree").toOption.get,
    nodes  = allNodes.values.toSeq,
    rootId = rootId,
    seedVarHighWater = Some(SeedVarId.fromLong(1000L).toOption.get),
    mitigations = List(mCyber, mHardware, mAbsent)
  )

  // Residual and single-mitigation valuations differ from the inherent one so
  // the selection argument is observable: cyber's inherent p95 is 50000, a flat
  // 5000 under residual, and a flat 10000 under the mCyber-only Selected.
  private val residualCyber = withCfg(5) {
    RiskResult(nodeId = cyberId, outcomes = Map(1 -> 5000L, 2 -> 5000L, 3 -> 5000L, 4 -> 5000L, 5 -> 5000L), provenances = Nil)
  }
  private val selectedCyber = withCfg(5) {
    RiskResult(nodeId = cyberId, outcomes = Map(1 -> 10000L, 2 -> 10000L, 3 -> 10000L, 4 -> 10000L, 5 -> 10000L), provenances = Nil)
  }
  private val mCyberSelection = MitigationSelection.Selected(Map(mCyberId -> ScopeRestriction.FullScope))

  private val resolvedScopes: Map[MitigationId, Set[NodeId]] =
    Map(mCyberId -> Set(cyberId), mHardwareId -> Set(hardwareId)) // mAbsent omitted

  private val mitKb = RiskTreeKnowledgeBase(
    RiskTreeKnowledgeBase.schemaFor(mitTree),
    Map(
      MitigationSelection.Inherent -> widen(results),
      MitigationSelection.Residual -> widen(results.updated(cyberId, residualCyber)),
      mCyberSelection              -> widen(results.updated(cyberId, selectedCyber))
    ),
    resolvedScopes
  )

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
      nodeReferenceQuerySuite,
      mitigationPredicateSuite,
      selectionFunctionSuite,
      mitigationReferenceSuite,
      mitigationConstantSuite
    )

  // ── Percentile suite ───────────────────────────────────────────────

  private val percentileSuite = suite("percentile (via p95/p99 functions)")(
    test("p95 returns correct loss for known distribution") {
      // Cyber outcomes sorted: [0, 5000, 10000, 20000, 50000]
      // outcomeCount: {0→1, 5000→1, 10000→1, 20000→1, 50000→1}
      // totalTrials = 5, target = 5 * 0.95 = 4.75
      // Walk: 0→cum 1, 5000→cum 2, 10000→cum 3, 20000→cum 4, 50000→cum 5
      // First where cum >= 4.75 is 50000 (cum=5)
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      assertTrue(result == Right(50000L))
    },
    test("p99 returns correct loss for known distribution") {
      // Same logic, target = 5 * 0.99 = 4.95 → 50000 (cum=5)
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
      assertTrue(result == Right(50000L))
    },
    test("p95 on wider distribution selects correct quantile") {
      // Hardware outcomes sorted: [500, 1000, 1000, 2000, 8000]
      // outcomeCount: {500→1, 1000→2, 2000→1, 8000→1}
      // totalTrials = 5, target = 5 * 0.95 = 4.75
      // Walk: 500→1, 1000→3, 2000→4, 8000→5
      // First where cum >= 4.75 is 8000 (cum=5)
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Hardware"), inherent))
      assertTrue(result == Right(8000L))
    },
    test("p50 behaviour via percentile — verified through lec instead") {
      // Use lec to verify probOfExceedance: P(Cyber >= 10000)
      // Cyber outcomeCount: {0→1, 5000→1, 10000→1, 20000→1, 50000→1}
      // rangeFrom(10000) = {10000→1, 20000→1, 50000→1}, sum = 3
      // P = 3/5 = 0.6
      val result = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(10000L), inherent))
      assertTrue(result == Right(0.6))
    },
    test("percentile with empty outcomes returns 0") {
      val emptyKb = kbInherent(tree, results.updated(cyberId, emptyResult))
      val result = emptyKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      assertTrue(result == Right(0L))
    },
    test("percentile monotonicity: p95 <= p99") {
      val p95 = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      val p99 = kb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
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
      val sparseKb = kbInherent(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      assertTrue(result == Right(50000L))
    },
    test("p95 on sparse hardware — walks past zero mass into tail") {
      // Hardware: outcomeCount {500→1, 1000→1, 2000→1}, implicitZeros=7
      // target = 10 * 0.95 = 9.5, cum: 7, 8, 9, 10
      // Walk: 500→8, 1000→9, 2000→10 → first >= 9.5 is 2000
      val sparseKb = kbInherent(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Hardware"), inherent))
      assertTrue(result == Right(2000L))
    },
    test("p99 with low occurrence returns last outcome") {
      // sparseCyberResult: nTrials=10, implicitZeros=7
      // target = 10 * 0.99 = 9.9
      // Walk: 5000→8, 10000→9, 50000→10 → first >= 9.9 is 50000
      val sparseKb = kbInherent(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
      assertTrue(result == Right(50000L))
    },
    test("p95 with very sparse results returns 0 — target deep in zero mass") {
      // nTrials=100, only 2 outcomes → implicitZeros = 98
      // target = 100 * 0.95 = 95.0
      // cumulative starts at 98 >= 95.0 → 0L
      val verySparse = withCfg(100) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 10000L, 2 -> 50000L), provenances = Nil)
      }
      val sparseKb = kbInherent(tree, results.updated(cyberId, verySparse))
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      assertTrue(result == Right(0L))
    },
    test("single outcome with many implicit zeros — p95 in zero mass, p99 in zero mass") {
      // nTrials=100, 1 outcome {1→42000} → outcomeCount={42000→1}, implicitZeros=99
      // p95 target=95.0, 99 >= 95 → 0L
      // p99 target=99.0, 99 >= 99 → 0L (boundary: 99.0 >= 99.0 is true)
      val single = withCfg(100) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 42000L), provenances = Nil)
      }
      val singleKb = kbInherent(tree, results.updated(cyberId, single))
      val p95 = singleKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      val p99 = singleKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
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
      val identKb = kbInherent(tree, results.updated(cyberId, identical))
      val p95 = identKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      val p99 = identKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
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
      val bKb = kbInherent(tree, results.updated(cyberId, boundary))
      val p95 = bKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      assertTrue(p95 == Right(0L))
    },
    test("just past boundary — implicitZeros just below target at p99") {
      // nTrials=20, 1 outcome → implicitZeros=19, p99 target=19.8
      // 19.0 < 19.8, walk: {30000→1} → cum=20. 20 >= 19.8 → 30000L
      // Correct: 1 of 20 trials exceeds the 99th percentile → last outcome
      val boundary = withCfg(20) {
        RiskResult(nodeId = cyberId, outcomes = Map(1 -> 30000L), provenances = Nil)
      }
      val bKb = kbInherent(tree, results.updated(cyberId, boundary))
      val p99 = bKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
      assertTrue(p99 == Right(30000L))
    },
    test("lec is unaffected by sparse results — still unconditional") {
      // sparseCyberResult: nTrials=10, outcomes = {5000, 10000, 50000}
      // P(Loss >= 5000) = count(outcomes >= 5000) / nTrials = 3/10 = 0.3
      val sparseKb = kbInherent(tree, sparseResults)
      val result = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(5000L), inherent))
      assertTrue(result == Right(0.3))
    },
    test("monotonicity holds with sparse outcomes: p95 <= p99") {
      val sparseKb = kbInherent(tree, sparseResults)
      val p95 = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      val p99 = sparseKb.dispatcher.evalFunction(vql.typed.SymbolName("p99"), List(nodeVal("Cyber"), inherent))
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
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(5000L), inherent))
      assertTrue(r == Right(0.8))
    },
    test("lec with raw Long threshold (as engine delivers literals)") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossStr("5000"), inherent))
      assertTrue(r == Right(0.8))
    },
    test("lec with threshold above max returns 0.0") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("lec"), List(nodeVal("Cyber"), lossVal(100000L), inherent))
      assertTrue(r == Right(0.0))
    },
    test("unknown asset returns Left") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Nonexistent"), inherent))
      assertTrue(r.isLeft)
    },
    test("unknown function returns Left") {
      val r = kb.dispatcher.evalFunction(vql.typed.SymbolName("median"), List(nodeVal("Cyber"), inherent))
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
    test("Mitigation domain enumerates the tree's mitigations") {
      val ids = mitKb.model.domains(mitigationSort)
        .flatMap(v => v.raw match { case id: MitigationId => Some(id); case _ => None })
      assertTrue(ids == Set(mCyberId, mHardwareId, mAbsentId))
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
    test("catalog declares eight sorts") {
      // Two DomainType (Node, Mitigation) + six ValueType (Loss, Probability,
      // NodeNameLiteral, NodeIdLiteral, MitigationNameLiteral, MitigationIdLiteral).
      assertTrue(
        kb.catalog.typeIds.size == 8,
        kb.dispatcher.functionSymbols.size == 3,
        kb.dispatcher.predicateSymbols.size == 15
      )
    },
    test("riskNameToId maps names to correct NodeIds") {
      assertTrue(
        kb.riskNameToId("Cyber") == cyberId,
        kb.riskNameToId("Root") == rootId,
        kb.riskNameToId.size == 4
      )
    }
  )

  // ── Constants suite ────────────────────────────────────────────────

  /** Build a RiskTree from a list of nodes via the domain smart constructor,
    * without the request/DTO layer — the construction path a direct repo write,
    * migration, or Irmin merge takes. The first node is the root. The domain
    * invariants in RiskTree.fromNodes (structure, distinct seedVarIds, unique
    * node names) still hold; only the request-layer checks are skipped.
    */
  private def bypassTree(nodes: Seq[RiskNode]): RiskTree =
    RiskTree.fromNodesUnsafe(
      id     = treeId("bypass-tree"),
      name   = com.risquanter.register.domain.data.iron.SafeName.fromString("Bypass Tree").toOption.get,
      nodes  = nodes,
      rootId = nodes.head.id,
      seedVarHighWater = Some(SeedVarId.fromLong(1000L).toOption.get),
      mitigations = Nil
    )

  private val constantsSuite = suite("node constants via literal validator")(
    test("C1: 4-node fixture — only the two mitigation aggregate constants; riskNameToId carries the names; per-sort validators resolve name vs id; no collisions") {
      val nodeV = kb.catalog.literalValidators(nodeSort)
      val nameV = kb.catalog.literalValidators(nodeNameLiteralSort)
      val idV   = kb.catalog.literalValidators(nodeIdLiteralSort)
      assertTrue(
        kb.catalog.constants.keySet == Set(RiskTreeKnowledgeBase.InherentConst, RiskTreeKnowledgeBase.ResidualConst),
        kb.riskNameToId.keySet == Set("Root", "IT Risk", "Cyber", "Hardware"),
        nodeV("Cyber")       == Some(cyberId),   // Node slot: quoted node name → its id
        nodeV(cyberId.value) == None,            // Node slot is name-only: an id no longer binds
        nodeV("Nonexistent") == None,            // unknown name → unbindable
        nameV("Cyber")       == Some(cyberId),   // named_risk: name → id
        nameV(cyberId.value) == None,            // named_risk rejects an id
        idV(cyberId.value)   == Some(cyberId),   // risk_id: id → id
        idV("Cyber")         == None,            // risk_id rejects a name
        kb.riskNameCollisions.isEmpty
      )
    },
    test("C3: reserved-name collision (\"leaf\") — skipped from constants; predicate retained") {
      val rootIdStr = idStr("root3")
      val cIdStr    = idStr("c")
      val rootP = unsafeGet(RiskPortfolio.create(
        id = rootIdStr, name = "Root3",
        childIds = Array(NodeId(safeId("c"))), parentId = None
      ), "portfolio")
      val c = unsafeGet(RiskLeaf.create(
        id = cIdStr, name = "leaf",
        distributionType = "lognormal", probability = 0.1,
        minLoss = Some(1L), maxLoss = Some(2L), parentId = Some(NodeId(safeId("root3"))),
        seedVarId = 5L
      ), "leaf")
      val t = bypassTree(Seq(rootP, c))
      val kb3 = RiskTreeKnowledgeBase(RiskTreeKnowledgeBase.schemaFor(t), Map.empty, Map.empty)
      assertTrue(
        !kb3.riskNameToId.contains("leaf"),
        kb3.catalog.predicates.contains(vql.typed.SymbolName("leaf")),
        kb3.riskNameCollisions.exists(s => s.startsWith("reserved:") && s.endsWith("leaf"))
      )
    },
    test("C4: reservedFolNames equals the union of catalog function, predicate & constant symbol names") {
      // Drift guard for the FolSymbols.reservedNames literal. That literal is a
      // manual mirror of the catalog's symbol names, held in `common` because the
      // DTO gate needs it there and cannot see the server-side catalog. This is
      // the one place both are visible, so it asserts they still agree — add a
      // catalog symbol without updating the literal and this goes red.
      val symbolStrings: Set[String] =
        kb.catalog.functions.keySet.map(_.value) ++
        kb.catalog.predicates.keySet.map(_.value) ++
        kb.catalog.constants.keySet
      val baseline = Set(
        "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
        "gt_loss", "gt_prob", "eq", "named_risk", "risk_id",
        "named_mitigation", "mitigation_id", "mitigate", "mitigated", "unmitigated",
        "p95", "p99", "lec",
        "inherent", "residual"
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

  // ── Node-reference predicate queries (named_risk / risk_id) ─────────

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

  private val nodeReferenceQuerySuite = suite("node-reference predicate queries (named_risk / risk_id)")(
    test("named_risk(x, \"IT Risk\") pins that node") {
      // named_risk's 2nd arg is the NodeNameLiteral sort; "IT Risk" resolves via
      // riskNameToId.get to itId, so the satisfying set is exactly {IT Risk}.
      val names = satisfyingNames("""Q[>=]^{1/1} x (named_risk(x, "IT Risk"), portfolio(x))""")
      assertTrue(names.contains(Set("IT Risk")))
    },
    test("risk_id(x, <cyberId>) pins Cyber") {
      // risk_id's 2nd arg is the NodeIdLiteral sort; the ulid parses via
      // NodeId.fromString to cyberId, so the satisfying set is exactly {Cyber}.
      val names = satisfyingNames(s"""Q[>=]^{1/1} x (risk_id(x, "${cyberId.value}"), leaf(x))""")
      assertTrue(names.contains(Set("Cyber")))
    },
    test("risk_id(x, \"IT Risk\") → UnparseableConstant on the NodeIdLiteral sort") {
      // A name in an id slot: NodeId.fromString("IT Risk") = None → malformed id.
      val parsed = VagueQueryParser.parse("""Q[>=]^{1/1} x (risk_id(x, "IT Risk"), leaf(x))""").toOption.get
      val bound  = QueryBinder.bind(parsed, kb.catalog)
      assertTrue(
        bound.isLeft,
        bound.left.toOption.exists(_.exists {
          case TypeCheckError.UnparseableConstant(name, sort, _) => name == "IT Risk" && sort == nodeIdLiteralSort
          case _                                                 => false
        })
      )
    },
    test("named_risk(x, <cyberId>) → UnparseableConstant on the NodeNameLiteral sort") {
      // An id in a name slot: riskNameToId.get(<ulid>) = None → no such node name.
      val parsed = VagueQueryParser.parse(s"""Q[>=]^{1/1} x (named_risk(x, "${cyberId.value}"), leaf(x))""").toOption.get
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

  // ── Mitigation predicate suite (M3: mitigate / mitigated / unmitigated) ──

  private val mitigationPredicateSuite = suite("mitigation predicates (M3)")(
    test("mitigate: node in the mitigation's resolved scope → true") {
      val r = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigate"), List(nodeVal("Cyber"), mitVal(mCyberId)))
      assertTrue(r == Right(true))
    },
    test("mitigate: node outside the mitigation's resolved scope → false") {
      val r = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigate"), List(nodeVal("Hardware"), mitVal(mCyberId)))
      assertTrue(r == Right(false))
    },
    test("mitigate: mitigation absent from resolvedScopes (Failed, M3-D2) → false") {
      val r = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigate"), List(nodeVal("Cyber"), mitVal(mAbsentId)))
      assertTrue(r == Right(false))
    },
    test("mitigated: union of resolved scopes; a Failed mitigation contributes nothing") {
      val cyber    = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigated"), List(nodeVal("Cyber")))
      val hardware = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigated"), List(nodeVal("Hardware")))
      val root     = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigated"), List(nodeVal("Root")))
      assertTrue(cyber == Right(true), hardware == Right(true), root == Right(false))
    },
    test("unmitigated is the exact complement of mitigated over the node domain (M3-D5)") {
      val nodes  = List("Root", "IT Risk", "Cyber", "Hardware")
      val agrees = nodes.forall { n =>
        val m = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigated"), List(nodeVal(n)))
        val u = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("unmitigated"), List(nodeVal(n)))
        (m, u) match
          case (Right(mb), Right(ub)) => mb != ub
          case _                      => false
      }
      assertTrue(agrees)
    }
  )

  // ── Value-function selection slot (M3: inherent / residual / bound m) ──

  private val selectionFunctionSuite = suite("value functions read the mitigation selection slot (M3)")(
    test("p95(x, inherent) reads the base results") {
      val r = mitKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), inherent))
      assertTrue(r == Right(50000L))
    },
    test("p95(x, residual) reads the precomputed residual results") {
      val r = mitKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), residual))
      assertTrue(r == Right(5000L))
    },
    test("p95(x, m) for a bound mitigation reads that single-mitigation Selected valuation") {
      val r = mitKb.dispatcher.evalFunction(vql.typed.SymbolName("p95"), List(nodeVal("Cyber"), mitVal(mCyberId)))
      assertTrue(r == Right(10000L))
    }
  )

  // ── Mitigation-reference predicates (M3: named_mitigation / mitigation_id) ──

  private val mitigationReferenceSuite = suite("mitigation-reference predicates (M3: named_mitigation / mitigation_id)")(
    test("per-sort literal validators resolve mitigation name vs id") {
      val nameV = mitKb.catalog.literalValidators(mitigationNameLiteralSort)
      val idV   = mitKb.catalog.literalValidators(mitigationIdLiteralSort)
      assertTrue(
        nameV("Cyber Mitigation") == Some(mCyberId),   // named_mitigation: name → id
        nameV(mCyberId.value)     == None,             // named_mitigation rejects an id
        idV(mCyberId.value)       == Some(mCyberId),   // mitigation_id: id → id
        idV("Cyber Mitigation")   == None              // mitigation_id rejects a name
      )
    },
    test("named_mitigation / mitigation_id reduce to MitigationId equality") {
      val same = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("named_mitigation"), List(mitVal(mCyberId), mitVal(mCyberId)))
      val diff = mitKb.dispatcher.evalPredicate(vql.typed.SymbolName("mitigation_id"), List(mitVal(mCyberId), mitVal(mHardwareId)))
      assertTrue(same == Right(true), diff == Right(false))
    }
  )

  // ── Mitigation aggregate constants (M3) ─────────────────────────────

  private val mitigationConstantSuite = suite("mitigation aggregate constants (M3)")(
    test("inherent / residual are declared on the Mitigation sort") {
      assertTrue(
        mitKb.catalog.constants == Map(
          RiskTreeKnowledgeBase.InherentConst -> mitigationSort,
          RiskTreeKnowledgeBase.ResidualConst -> mitigationSort
        )
      )
    },
    test("inherent used in a Node slot fails to bind with a TypeMismatch on the Mitigation sort") {
      // eq expects (Node, Node); `inherent` resolves to a Mitigation-sort constant.
      val parsed = VagueQueryParser.parse("""Q[>=]^{1/1} x (leaf(x), eq(x, "inherent"))""").toOption.get
      val bound  = QueryBinder.bind(parsed, mitKb.catalog)
      assertTrue(
        bound.isLeft,
        bound.left.toOption.exists(_.exists {
          case TypeCheckError.TypeMismatch(_, actual, _) => actual == mitigationSort
          case _                                         => false
        })
      )
    },
    test("a node or mitigation named inherent/residual is surfaced via the collision diagnostics") {
      val rootP = unsafeGet(RiskPortfolio.create(
        id = idStr("root-col"), name = "Root Col",
        childIds = Array(NodeId(safeId("inh"))), parentId = None
      ), "portfolio")
      val leafInherent = unsafeGet(RiskLeaf.create(
        id = idStr("inh"), name = "inherent",
        distributionType = "lognormal", probability = 0.1,
        minLoss = Some(1L), maxLoss = Some(2L), parentId = Some(NodeId(safeId("root-col"))),
        seedVarId = 7L
      ), "leaf")
      val mResidual = mkMit("m-res", "residual")
      val t = RiskTree.fromNodesUnsafe(
        id     = treeId("collide-tree"),
        name   = SafeName.fromString("Collide Tree").toOption.get,
        nodes  = Seq(rootP, leafInherent),
        rootId = rootP.id,
        seedVarHighWater = Some(SeedVarId.fromLong(1000L).toOption.get),
        mitigations = List(mResidual)
      )
      val colKb = RiskTreeKnowledgeBase(RiskTreeKnowledgeBase.schemaFor(t), Map.empty, Map.empty)
      assertTrue(
        !colKb.riskNameToId.contains("inherent"),
        colKb.riskNameCollisions.contains("reserved:inherent"),
        !colKb.mitigationNameToId.contains("residual"),
        colKb.mitigationNameCollisions.contains("reserved-mitigation:residual")
      )
    }
  )

end RiskTreeKnowledgeBaseSpec
