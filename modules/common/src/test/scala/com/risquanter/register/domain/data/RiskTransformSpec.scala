package com.risquanter.register.domain.data

import zio.test.*
import zio.prelude.Identity
import io.github.iltotore.iron.{autoRefine, refineUnsafe}
import com.risquanter.register.domain.data.iron.{NonNegativeDouble, NonNegativeLong, PositiveInt, ValidationUtil}

/**
 * Property-based tests for RiskTransform Identity laws and mitigation strategies.
 *
 * Verifies:
 * - Identity laws: associativity, left/right identity
 * - nTrials preservation: no transform changes the trial count
 * - Mitigation strategies: deductible, cap, scaling, insurance policy
 * - Composition correctness: order matters for non-commutative operations
 */
object RiskTransformSpec extends ZIOSpecDefault {


  // ══════════════════════════════════════════════════════════════════
  // Generators
  // ══════════════════════════════════════════════════════════════════

  private val genNTrials: Gen[Any, PositiveInt] =
    Gen.int(100, 1000).map(_.refineUnsafe)

  /** Generate TrialOutcomes for testing transformations */
  val genTrialOutcomes: Gen[Any, TrialOutcomes] = for {
    nTrials <- genNTrials
    numTrials <- Gen.int(5, 20)  // Smaller for readable test output
    trialIds <- Gen.listOfN(numTrials)(Gen.int(0, nTrials - 1))
    losses <- Gen.listOfN(numTrials)(Gen.long(1000L, 100000L))
  } yield TrialOutcomes(nTrials, trialIds.zip(losses).toMap)

  /** Generate positive Loss values (refined to NonNegativeLong parameters) */
  val genLoss: Gen[Any, NonNegativeLong] =
    Gen.long(100L, 50000L).map(_.refineUnsafe)

  /** Generate scale factors */
  val genScaleFactor: Gen[Any, NonNegativeDouble] =
    Gen.double(0.1, 2.0).map(_.refineUnsafe)

  /** Generate simple RiskTransform (deductible, cap, scale, or filter) */
  val genSimpleTransform: Gen[Any, RiskTransform] = Gen.oneOf(
    genLoss.map(RiskTransform.applyDeductible),
    genLoss.map(RiskTransform.capLosses),
    genScaleFactor.map(RiskTransform.scaleLosses),
    genLoss.map(RiskTransform.filterBelowThreshold)
  )

  // ══════════════════════════════════════════════════════════════════
  // Identity Law Tests
  // ══════════════════════════════════════════════════════════════════

  def spec = suite("RiskTransformSpec")(

    suite("Identity Laws - Property Tests")(
      test("associativity: combine(a, combine(b, c)) == combine(combine(a, b), c)") {
        check(genSimpleTransform, genSimpleTransform, genSimpleTransform, genTrialOutcomes) {
          (t1, t2, t3, outcomes) =>
            val left = Identity[RiskTransform].combine(
              t1,
              Identity[RiskTransform].combine(t2, t3)
            )
            val right = Identity[RiskTransform].combine(
              Identity[RiskTransform].combine(t1, t2),
              t3
            )

            // Apply both to same input and compare results
            val leftResult = left.run(outcomes)
            val rightResult = right.run(outcomes)

            assertTrue(leftResult.outcomes == rightResult.outcomes)
        }
      },

      test("left identity: combine(identity, a) == a") {
        check(genSimpleTransform, genTrialOutcomes) { (transform, outcomes) =>
          val combined = Identity[RiskTransform].combine(
            Identity[RiskTransform].identity,
            transform
          )

          val directResult = transform.run(outcomes)
          val combinedResult = combined.run(outcomes)

          assertTrue(directResult.outcomes == combinedResult.outcomes)
        }
      },

      test("right identity: combine(a, identity) == a") {
        check(genSimpleTransform, genTrialOutcomes) { (transform, outcomes) =>
          val combined = Identity[RiskTransform].combine(
            transform,
            Identity[RiskTransform].identity
          )

          val directResult = transform.run(outcomes)
          val combinedResult = combined.run(outcomes)

          assertTrue(directResult.outcomes == combinedResult.outcomes)
        }
      },

      test("identity transformation leaves outcomes unchanged") {
        check(genTrialOutcomes) { outcomes =>
          val transformed = RiskTransform.identityTransform.run(outcomes)

          assertTrue(transformed == outcomes)
        }
      },

      test("every transform preserves nTrials") {
        check(genSimpleTransform, genTrialOutcomes) { (transform, outcomes) =>
          assertTrue(transform.run(outcomes).nTrials == outcomes.nTrials)
        }
      }
    ),

    // ══════════════════════════════════════════════════════════════════
    // Mitigation Strategy Tests
    // ══════════════════════════════════════════════════════════════════

    suite("Deductible Transformation")(
      test("applyDeductible reduces losses by deductible amount") {
        val outcomes = TrialOutcomes(100, Map(1 -> 50000L, 2 -> 10000L, 3 -> 5000L))
        val transform = RiskTransform.applyDeductible(10000L)
        val mitigated = transform.run(outcomes)

        assertTrue(
          mitigated.outcomeOf(1) == 40000L,  // 50000 - 10000
          mitigated.outcomeOf(2) == 0L,      // 10000 - 10000
          mitigated.outcomeOf(3) == 0L       // 5000 - 10000 -> 0
        )
      },

      test("deductible removes trials below threshold (sparse)") {
        val outcomes = TrialOutcomes(100, Map(1 -> 50000L, 2 -> 8000L))
        val transform = RiskTransform.applyDeductible(10000L)
        val mitigated = transform.run(outcomes)

        assertTrue(
          mitigated.outcomes.size == 1,  // Only trial 1 remains
          mitigated.outcomes.contains(1),
          !mitigated.outcomes.contains(2)
        )
      },

      test("zero deductible is identity") {
        check(genTrialOutcomes) { outcomes =>
          val transform = RiskTransform.applyDeductible(0L)
          val mitigated = transform.run(outcomes)

          assertTrue(mitigated.outcomes == outcomes.outcomes)
        }
      }
    ),

    suite("Cap Transformation")(
      test("capLosses limits each trial to maximum") {
        val outcomes = TrialOutcomes(100, Map(1 -> 5000000L, 2 -> 500000L, 3 -> 100000L))
        val transform = RiskTransform.capLosses(1000000L)
        val capped = transform.run(outcomes)

        assertTrue(
          capped.outcomeOf(1) == 1000000L,  // 5M capped to 1M
          capped.outcomeOf(2) == 500000L,   // 500K unchanged
          capped.outcomeOf(3) == 100000L    // 100K unchanged
        )
      },

      test("cap preserves all trials (even if modified)") {
        val outcomes = TrialOutcomes(100, Map(1 -> 2000000L, 2 -> 500000L))
        val transform = RiskTransform.capLosses(1000000L)
        val capped = transform.run(outcomes)

        assertTrue(capped.outcomes.size == 2)
      },

      test("very high cap is identity") {
        check(genTrialOutcomes) { outcomes =>
          val maxLoss = if (outcomes.outcomes.isEmpty) 0L
                       else outcomes.outcomes.values.max
          val transform = RiskTransform.capLosses((maxLoss * 10).refineUnsafe)
          val capped = transform.run(outcomes)

          assertTrue(capped.outcomes == outcomes.outcomes)
        }
      }
    ),

    suite("Scale Transformation")(
      test("scaleLosses multiplies each loss by factor") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L, 2 -> 50000L))
        val transform = RiskTransform.scaleLosses(0.8)
        val scaled = transform.run(outcomes)

        assertTrue(
          scaled.outcomeOf(1) == 80000L,   // 100000 * 0.8
          scaled.outcomeOf(2) == 40000L    // 50000 * 0.8
        )
      },

      test("scale by 1.0 is identity") {
        check(genTrialOutcomes) { outcomes =>
          val transform = RiskTransform.scaleLosses(1.0)
          val scaled = transform.run(outcomes)

          assertTrue(scaled.outcomes == outcomes.outcomes)
        }
      },

      test("scale by 0.0 removes all losses") {
        check(genTrialOutcomes) { outcomes =>
          val transform = RiskTransform.scaleLosses(0.0)
          val scaled = transform.run(outcomes)

          assertTrue(scaled.outcomes.isEmpty)
        }
      },

      test("scale removes trials that round to zero") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100L, 2 -> 50000L))
        val transform = RiskTransform.scaleLosses(0.001)  // 0.1%
        val scaled = transform.run(outcomes)

        // 100 * 0.001 = 0.1 rounds to 0, removed
        // 50000 * 0.001 = 50
        assertTrue(
          !scaled.outcomes.contains(1),
          scaled.outcomeOf(2) == 50L
        )
      }
    ),

    suite("Insurance Policy (Combined)")(
      test("insurancePolicy applies deductible then cap") {
        val outcomes = TrialOutcomes(100, Map(1 -> 2000000L, 2 -> 50000L, 3 -> 5000L))
        val insured = RiskTransform.insurancePolicy(
          deductible = 10000L,
          cap = 1000000L
        ).map(_.run(outcomes).outcomes).toEither

        assertTrue(
          insured == Right(Map(
            1 -> 1000000L,  // (2M - 10K) capped to 1M
            2 -> 40000L     // 50K - 10K = 40K
                            // trial 3: 5K - 10K = 0, removed (sparse)
          ))
        )
      },

      test("insurancePolicy fails when cap <= deductible (property test)") {
        check(genLoss, genLoss) { (loss1, loss2) =>
          val deductible: NonNegativeLong = if (loss1 >= loss2) loss1 else loss2
          val cap: NonNegativeLong = if (loss1 >= loss2) loss2 else loss1

          // cap <= deductible must yield a validation failure
          assertTrue(RiskTransform.insurancePolicy(deductible, cap).toEither.isLeft)
        }
      },

      test("insurancePolicy succeeds when cap > deductible") {
        check(genLoss, Gen.long(1L, 10000L)) { (deductible, gap) =>
          val cap: NonNegativeLong = (deductible + gap).refineUnsafe

          assertTrue(RiskTransform.insurancePolicy(deductible, cap).toEither.isRight)
        }
      }
    ),

    // Negative parameters are unrepresentable in the constructors
    // (NonNegativeLong/NonNegativeDouble); the boundary refinement that
    // rejects them is tested here.
    suite("Parameter Refinement Boundary")(
      test("refineNonNegativeLong rejects negative values") {
        assertTrue(
          ValidationUtil.refineNonNegativeLong(-1000L).isLeft,
          ValidationUtil.refineNonNegativeLong(0L).isRight
        )
      },

      test("refineNonNegativeDouble rejects negative values") {
        assertTrue(
          ValidationUtil.refineNonNegativeDouble(-0.5).isLeft,
          ValidationUtil.refineNonNegativeDouble(0.0).isRight,
          ValidationUtil.refineNonNegativeDouble(2.5).isRight
        )
      }
    ),

    suite("Composition Tests")(
      test("andThen applies transformations in sequence") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))

        val deductible = RiskTransform.applyDeductible(10000L)
        val scale = RiskTransform.scaleLosses(0.5)

        // Apply deductible (100K -> 90K), then scale (90K -> 45K)
        val combined = deductible.andThen(scale)
        val transformed = combined.run(outcomes)

        assertTrue(transformed.outcomeOf(1) == 45000L)
      },

      test("compose applies transformations in reverse") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))

        val deductible = RiskTransform.applyDeductible(10000L)
        val scale = RiskTransform.scaleLosses(0.5)

        // Compose: scale first (100K -> 50K), then deductible (50K -> 40K)
        val combined = deductible.compose(scale)
        val transformed = combined.run(outcomes)

        assertTrue(transformed.outcomeOf(1) == 40000L)
      },

      test("order matters for non-commutative operations") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L))

        val deductible = RiskTransform.applyDeductible(10000L)
        val scale = RiskTransform.scaleLosses(0.5)

        // deductible THEN scale
        val order1 = Identity[RiskTransform].combine(deductible, scale)
        val result1 = order1.run(outcomes).outcomeOf(1)

        // scale THEN deductible
        val order2 = Identity[RiskTransform].combine(scale, deductible)
        val result2 = order2.run(outcomes).outcomeOf(1)

        assertTrue(
          result1 == 45000L,  // (100K - 10K) * 0.5
          result2 == 40000L,  // (100K * 0.5) - 10K
          result1 != result2  // Order matters!
        )
      }
    ),

    suite("Edge Cases")(
      test("transform on empty outcomes is no-op") {
        val empty = TrialOutcomes(100, Map.empty)
        val transform = RiskTransform.applyDeductible(10000L)
        val transformed = transform.run(empty)

        assertTrue(transformed.outcomes.isEmpty)
      },

      test("filterBelowThreshold removes small losses") {
        val outcomes = TrialOutcomes(100, Map(1 -> 100000L, 2 -> 500L, 3 -> 50000L, 4 -> 200L))
        val transform = RiskTransform.filterBelowThreshold(1000L)
        val filtered = transform.run(outcomes)

        assertTrue(
          filtered.outcomes.size == 2,
          filtered.outcomes.contains(1),
          filtered.outcomes.contains(3),
          !filtered.outcomes.contains(2),
          !filtered.outcomes.contains(4)
        )
      }
    )
  )
}
