package com.risquanter.register.domain.bundle

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Identity
import com.risquanter.register.testutil.TestHelpers

/**
  * Unit tests for CurveBundle and TickDomain.
  *
  * Verifies:
  * - TickDomain join-semilattice properties (union, containment)
  * - CurveBundle monoidal laws (associativity, identity)
  * - Tick alignment and interpolation
  * - Conversion to LECCurveResponse
  */
object CurveBundleSpec extends ZIOSpecDefault with TestHelpers {

  // ══════════════════════════════════════════════════════════════════
  // Test Data
  // ══════════════════════════════════════════════════════════════════

  // Node IDs (Iron-refined SafeId.SafeId)
  val cyberId: NodeId = safeId("cyber")
  val hardwareId: NodeId = safeId("hardware")
  val opsRiskId: NodeId = safeId("ops-risk")

  // Sample tick domains
  val domain3: TickDomain = TickDomain.fromProbabilities(Seq(0.90, 0.50, 0.10))
  val domain5: TickDomain = TickDomain.fromProbabilities(Seq(0.99, 0.90, 0.50, 0.10, 0.01))
  val domainExtra: TickDomain = TickDomain.fromProbabilities(Seq(0.95, 0.75, 0.25, 0.05))

  // Sample curves (loss values at each tick, descending order)
  // domain3 ticks: [0.90, 0.50, 0.10]
  val cyberLosses3: Vector[Long] = Vector(100000L, 500000L, 2000000L)
  val hardwareLosses3: Vector[Long] = Vector(50000L, 250000L, 1000000L)

  // ══════════════════════════════════════════════════════════════════
  // TickDomain Tests
  // ══════════════════════════════════════════════════════════════════

  def spec = suite("CurveBundleSpec")(

    suite("TickDomain")(

      test("fromProbabilities sorts descending and deduplicates") {
        val domain = TickDomain.fromProbabilities(Seq(0.10, 0.50, 0.90, 0.50))
        assertTrue(
          domain.ticks == Vector(0.90, 0.50, 0.10),
          domain.size == 3
        )
      },

      test("empty domain is identity for expansion") {
        val expanded = TickDomain.empty.expandTo(domain3)
        assertTrue(expanded == domain3)
      },

      test("expansion to self is idempotent") {
        val expanded = domain3.expandTo(domain3)
        assertTrue(expanded == domain3)
      },

      test("expansion computes union") {
        // domain3: [0.90, 0.50, 0.10]
        // domainExtra: [0.95, 0.75, 0.25, 0.05]
        // union: [0.95, 0.90, 0.75, 0.50, 0.25, 0.10, 0.05]
        val expanded = domain3.expandTo(domainExtra)
        assertTrue(
          expanded.ticks.contains(0.90),
          expanded.ticks.contains(0.50),
          expanded.ticks.contains(0.10),
          expanded.ticks.contains(0.95),
          expanded.ticks.contains(0.75),
          expanded.ticks.contains(0.25),
          expanded.ticks.contains(0.05),
          expanded.size == 7
        )
      },

      test("contains verifies subset relationship") {
        // domain5 contains domain3 ticks
        assertTrue(
          domain5.contains(domain3),
          !domain3.contains(domain5)  // domain3 is smaller
        )
      },

      test("TickDomain Identity law: left identity") {
        val result = Identity[TickDomain].combine(TickDomain.empty, domain3)
        assertTrue(result == domain3)
      },

      test("TickDomain Identity law: right identity") {
        val result = Identity[TickDomain].combine(domain3, TickDomain.empty)
        assertTrue(result == domain3)
      },

      test("TickDomain Identity law: associativity") {
        val a = domain3
        val b = domainExtra
        val c = domain5

        val left = Identity[TickDomain].combine(
          Identity[TickDomain].combine(a, b),
          c
        )
        val right = Identity[TickDomain].combine(
          a,
          Identity[TickDomain].combine(b, c)
        )

        assertTrue(left == right)
      }
    ),

    suite("CurveBundle")(

      test("empty bundle is identity for combination") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val combined = Identity[CurveBundle].combine(CurveBundle.empty, bundle)
        assertTrue(
          combined.domain == bundle.domain,
          combined.curves == bundle.curves
        )
      },

      test("single creates bundle with one curve") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        assertTrue(
          bundle.size == 1,
          bundle.contains(cyberId),
          bundle.get(cyberId) == Some(cyberLosses3)
        )
      },

      test("withCurve adds curve to bundle") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
          .withCurveUnsafe(hardwareId, hardwareLosses3)
        assertTrue(
          bundle.size == 2,
          bundle.contains(cyberId),
          bundle.contains(hardwareId)
        )
      },

      test("without removes curve from bundle") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
          .withCurveUnsafe(hardwareId, hardwareLosses3)
          .without(cyberId)
        assertTrue(
          bundle.size == 1,
          !bundle.contains(cyberId),
          bundle.contains(hardwareId)
        )
      },

      test("withCurve validates curve size matches domain") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val wrongSizeLosses = Vector(100L, 200L)  // 2 elements, domain has 3

        val result = bundle.withCurve(hardwareId, wrongSizeLosses)

        assertTrue(result.isFailure)
      },

      test("withCurve succeeds with correct curve size") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)

        val result = bundle.withCurve(hardwareId, hardwareLosses3)

        assertTrue(
          result.isSuccess,
          result.fold(_ => false, b => b.size == 2)
        )
      },

      test("combine merges bundles with same domain") {
        val bundleA = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val bundleB = CurveBundle.single(hardwareId, domain3, hardwareLosses3)

        val combined = Identity[CurveBundle].combine(bundleA, bundleB)

        assertTrue(
          combined.size == 2,
          combined.domain == domain3,
          combined.contains(cyberId),
          combined.contains(hardwareId)
        )
      },

      test("combine with different domains expands to union") {
        // Create bundles with different domains
        val domain2: TickDomain = TickDomain.fromProbabilities(Seq(0.90, 0.10))
        val losses2: Vector[Long] = Vector(100000L, 2000000L)

        val bundleA = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val bundleB = CurveBundle.single(hardwareId, domain2, losses2)

        val combined = Identity[CurveBundle].combine(bundleA, bundleB)

        // Union domain should contain all ticks from both
        assertTrue(
          combined.domain.contains(domain3),
          combined.domain.contains(domain2),
          combined.size == 2
        )
      },

      test("expandTo interpolates missing ticks") {
        // Start with domain3: [0.90, 0.50, 0.10]
        // Expand to include 0.70 (between 0.90 and 0.50)
        val targetDomain = TickDomain.fromProbabilities(Seq(0.90, 0.70, 0.50, 0.10))
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)

        val expanded = bundle.expandTo(targetDomain)
        val expandedLosses = expanded.get(cyberId).get

        // 0.70 should be interpolated between 0.90 (100000) and 0.50 (500000)
        // Linear interpolation: 100000 + (500000 - 100000) * (0.70 - 0.90) / (0.50 - 0.90)
        // = 100000 + 400000 * (-0.20) / (-0.40) = 100000 + 200000 = 300000
        assertTrue(
          expanded.domain == targetDomain,
          expandedLosses.size == 4,
          expandedLosses(0) == 100000L,  // 0.90 - unchanged
          expandedLosses(1) == 300000L,  // 0.70 - interpolated
          expandedLosses(2) == 500000L,  // 0.50 - unchanged
          expandedLosses(3) == 2000000L  // 0.10 - unchanged
        )
      },

      test("CurveBundle Identity law: left identity") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val result = Identity[CurveBundle].combine(CurveBundle.empty, bundle)

        assertTrue(
          result.domain == bundle.domain,
          result.curves == bundle.curves
        )
      },

      test("CurveBundle Identity law: right identity") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val result = Identity[CurveBundle].combine(bundle, CurveBundle.empty)

        assertTrue(
          result.domain == bundle.domain,
          result.curves == bundle.curves
        )
      },

      test("CurveBundle Identity law: associativity") {
        val a = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val b = CurveBundle.single(hardwareId, domain3, hardwareLosses3)
        val c = CurveBundle.single(opsRiskId, domain3, Vector(150000L, 750000L, 3000000L))

        val left = Identity[CurveBundle].combine(
          Identity[CurveBundle].combine(a, b),
          c
        )
        val right = Identity[CurveBundle].combine(
          a,
          Identity[CurveBundle].combine(b, c)
        )

        // Domains should match
        assertTrue(left.domain == right.domain)
        // All curves should be present
        assertTrue(
          left.curves.keySet == right.curves.keySet,
          left.curves.keySet == Set(cyberId, hardwareId, opsRiskId)
        )
      }
    ),

    suite("CurveBundle → LECCurveResponse")(

      test("toLECCurveResponse creates valid response") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val response = bundle.toLECCurveResponse(cyberId, "Cyber Risk", Some(List("child1", "child2")))

        assertTrue(
          response.isDefined,
          response.get.id == "cyber",
          response.get.name == "Cyber Risk",
          response.get.curve.size == 3,
          response.get.childIds == Some(List("child1", "child2"))
        )
      },

      test("toLECCurveResponse returns None for missing node") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val response = bundle.toLECCurveResponse(hardwareId, "Hardware Risk", None)

        assertTrue(response.isEmpty)
      },

      test("curve points have correct structure") {
        val bundle = CurveBundle.single(cyberId, domain3, cyberLosses3)
        val response = bundle.toLECCurveResponse(cyberId, "Cyber Risk", None).get

        // domain3 ticks: [0.90, 0.50, 0.10]
        // cyberLosses3: [100000, 500000, 2000000]
        val curvePoints = response.curve
        assertTrue(
          curvePoints(0).loss == 100000L,
          curvePoints(0).exceedanceProbability == 0.90,
          curvePoints(1).loss == 500000L,
          curvePoints(1).exceedanceProbability == 0.50,
          curvePoints(2).loss == 2000000L,
          curvePoints(2).exceedanceProbability == 0.10
        )
      }
    ),

    suite("Tick Domain Monotonicity Property")(

      test("parent domain contains all child domains after combination") {
        // Simulate: children have different domains, parent gets union
        val childADomain = TickDomain.fromProbabilities(Seq(0.99, 0.90, 0.50, 0.10))
        val childBDomain = TickDomain.fromProbabilities(Seq(0.95, 0.90, 0.50, 0.10, 0.05))

        val childA = CurveBundle.single(cyberId, childADomain, Vector(50000L, 100000L, 500000L, 2000000L))
        val childB = CurveBundle.single(hardwareId, childBDomain, Vector(25000L, 50000L, 250000L, 1000000L, 4000000L))

        // Parent combines children
        val parent = Identity[CurveBundle].combine(childA, childB)

        // Parent domain must contain all child ticks
        assertTrue(
          parent.domain.contains(childADomain),
          parent.domain.contains(childBDomain),
          parent.size == 2  // Both children present
        )
      }
    ),

    suite("LEC Boundary Semantics")(
      
      test("expansion beyond upper tick uses loss at max tick (floor)") {
        // Source domain: [0.90, 0.50, 0.10] - max tick is 0.90
        // Target domain includes 0.99 (> 0.90)
        // LEC semantics: at 99% exceedance (very common), use loss at 90%
        val sourceDomain = TickDomain.fromProbabilities(Seq(0.90, 0.50, 0.10))
        val sourceLosses = Vector(100000L, 500000L, 2000000L)  // losses at 90%, 50%, 10%
        
        val targetDomain = TickDomain.fromProbabilities(Seq(0.99, 0.90, 0.50, 0.10))
        
        val bundle = CurveBundle.single(cyberId, sourceDomain, sourceLosses)
        val expanded = bundle.expandTo(targetDomain)
        val expandedLosses = expanded.get(cyberId).get
        
        // At 0.99 (higher prob than we measured), use floor = loss at 0.90
        assertTrue(
          expandedLosses(0) == 100000L,  // 0.99 → floor to 0.90 loss
          expandedLosses(1) == 100000L,  // 0.90 → exact
          expandedLosses(2) == 500000L,  // 0.50 → exact
          expandedLosses(3) == 2000000L  // 0.10 → exact
        )
      },

      test("expansion beyond lower tick uses loss at min tick (ceiling)") {
        // Source domain: [0.90, 0.50, 0.10] - min tick is 0.10
        // Target domain includes 0.01 (< 0.10)
        // LEC semantics: at 1% exceedance (rare tail), use loss at 10%
        val sourceDomain = TickDomain.fromProbabilities(Seq(0.90, 0.50, 0.10))
        val sourceLosses = Vector(100000L, 500000L, 2000000L)
        
        val targetDomain = TickDomain.fromProbabilities(Seq(0.90, 0.50, 0.10, 0.01))
        
        val bundle = CurveBundle.single(cyberId, sourceDomain, sourceLosses)
        val expanded = bundle.expandTo(targetDomain)
        val expandedLosses = expanded.get(cyberId).get
        
        // At 0.01 (lower prob than we measured), use ceiling = loss at 0.10
        assertTrue(
          expandedLosses(0) == 100000L,  // 0.90 → exact
          expandedLosses(1) == 500000L,  // 0.50 → exact
          expandedLosses(2) == 2000000L, // 0.10 → exact
          expandedLosses(3) == 2000000L  // 0.01 → ceiling to 0.10 loss
        )
      },

      test("interpolation within range is linear") {
        // Source: [0.90, 0.10] with losses [100000, 1000000]
        // Target includes 0.50 (midpoint)
        // Linear interpolation: (100000 + 1000000) / 2 = 550000
        val sourceDomain = TickDomain.fromProbabilities(Seq(0.90, 0.10))
        val sourceLosses = Vector(100000L, 1000000L)
        
        val targetDomain = TickDomain.fromProbabilities(Seq(0.90, 0.50, 0.10))
        
        val bundle = CurveBundle.single(cyberId, sourceDomain, sourceLosses)
        val expanded = bundle.expandTo(targetDomain)
        val expandedLosses = expanded.get(cyberId).get
        
        // 0.50 is exactly between 0.10 and 0.90
        // ratio = (0.50 - 0.10) / (0.90 - 0.10) = 0.5
        // interpolated = 1000000 + (100000 - 1000000) * 0.5 = 550000
        assertTrue(
          expandedLosses(0) == 100000L,   // 0.90 → exact
          expandedLosses(1) == 550000L,   // 0.50 → interpolated
          expandedLosses(2) == 1000000L   // 0.10 → exact
        )
      },

      test("combining domains with different ranges preserves all data") {
        // Child A: narrow range [0.90, 0.10]
        // Child B: wide range [0.99, 0.90, 0.10, 0.01]
        // Union should have all ticks, A's values extended at boundaries
        val narrowDomain = TickDomain.fromProbabilities(Seq(0.90, 0.10))
        val narrowLosses = Vector(100000L, 1000000L)
        
        val wideDomain = TickDomain.fromProbabilities(Seq(0.99, 0.90, 0.10, 0.01))
        val wideLosses = Vector(50000L, 100000L, 500000L, 2000000L)
        
        val narrow = CurveBundle.single(cyberId, narrowDomain, narrowLosses)
        val wide = CurveBundle.single(hardwareId, wideDomain, wideLosses)
        
        val combined = Identity[CurveBundle].combine(narrow, wide)
        
        // Combined domain is union
        assertTrue(
          combined.domain.size == 4,  // [0.99, 0.90, 0.10, 0.01]
          combined.size == 2          // both curves present
        )
        
        // Check narrow curve was extended correctly
        val cyberLosses = combined.get(cyberId).get
        assertTrue(
          cyberLosses(0) == 100000L,   // 0.99 → floor to 0.90 value
          cyberLosses(1) == 100000L,   // 0.90 → exact
          cyberLosses(2) == 1000000L,  // 0.10 → exact
          cyberLosses(3) == 1000000L   // 0.01 → ceiling to 0.10 value
        )
        
        // Wide curve should be unchanged
        val hwLosses = combined.get(hardwareId).get
        assertTrue(
          hwLosses(0) == 50000L,
          hwLosses(1) == 100000L,
          hwLosses(2) == 500000L,
          hwLosses(3) == 2000000L
        )
      }
    )
  )
}
