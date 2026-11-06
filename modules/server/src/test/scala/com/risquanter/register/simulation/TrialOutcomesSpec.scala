package com.risquanter.register.simulation

import zio.test.*
import zio.prelude.Identity
import io.github.iltotore.iron.{autoRefine, refineUnsafe}
import com.risquanter.register.domain.data.{Loss, TrialId}
import com.risquanter.register.domain.data.iron.PositiveInt
import com.risquanter.register.domain.errors.{ErrorLocation, FieldProblem, ValidationError}
import com.risquanter.register.testutil.ConfigTestLoader.withCfg
import com.risquanter.register.testutil.RiskResultTestSupport.outcomesOf

/**
 * The gate on trial ids, the loss operations that keep it, and the law tests
 * for the TrialOutcomes monoid.
 *
 * Each law is exercised through the type class instance itself
 * (TrialOutcomes.commutative / Identity[TrialOutcomes]),
 * not through the underlying combine function — an unlawful instance must
 * fail here even if the function it delegates to is correct.
 */
object TrialOutcomesSpec extends ZIOSpecDefault {

  /** Random Loss values (0 to 10M) */
  private val genLoss: Gen[Any, Loss] = Gen.long(0L, 10000000L)

  /** Trial counts in a range that keeps outcome maps sparse */
  private val genNTrials: Gen[Any, PositiveInt] =
    Gen.int(100, 1000).map(_.refineUnsafe)

  /** Sparse outcome maps with trial IDs in valid range [0, nTrials) */
  private def genOutcomeMap(nTrials: Int): Gen[Any, Map[TrialId, Loss]] = for {
    numTrials <- Gen.int(0, Math.min(50, nTrials))
    trialIds  <- Gen.listOfN(numTrials)(Gen.int(0, nTrials - 1))
    losses    <- Gen.listOfN(numTrials)(genLoss)
  } yield trialIds.zip(losses).toMap

  /** Three trial-aligned TrialOutcomes values sharing one nTrials */
  private val genAligned3: Gen[Any, (TrialOutcomes, TrialOutcomes, TrialOutcomes)] =
    genNTrials.flatMap { nTrials =>
      for {
        o1 <- genOutcomeMap(nTrials)
        o2 <- genOutcomeMap(nTrials)
        o3 <- genOutcomeMap(nTrials)
      } yield (
        outcomesOf(nTrials,o1),
        outcomesOf(nTrials,o2),
        outcomesOf(nTrials,o3)
      )
    }

  private val genAligned2: Gen[Any, (TrialOutcomes, TrialOutcomes)] =
    genAligned3.map { case (a, b, _) => (a, b) }

  private val trialIdRefusal = ValidationError(ErrorLocation.Whole, FieldProblem.TrialIdOutOfRange)

  def spec = suite("TrialOutcomesSpec")(

    suite("create - the gate on trial ids")(
      test("accepts the lowest and the highest trial id") {
        val built = TrialOutcomes.create(10, Map(0 -> 5L, 9 -> 7L)).toEither
        assertTrue(built.map(_.outcomes) == Right(Map(0 -> 5L, 9 -> 7L)))
      },
      test("accepts an empty map") {
        assertTrue(TrialOutcomes.create(10, Map.empty).toEither.map(_.outcomes) == Right(Map.empty[TrialId, Loss]))
      },
      test("refuses a trial id equal to the trial count") {
        val refused = TrialOutcomes.create(10, Map(10 -> 5L)).toEither.left.map(_.toList)
        assertTrue(refused == Left(List(trialIdRefusal)))
      },
      test("refuses a negative trial id") {
        val refused = TrialOutcomes.create(10, Map(-1 -> 5L)).toEither.left.map(_.toList)
        assertTrue(refused == Left(List(trialIdRefusal)))
      }
    ),

    suite("loss operations")(
      test("mapLosses rewrites each loss under its own trial id and keeps the trial count") {
        val mapped = outcomesOf(100, Map(1 -> 10L, 2 -> 20L)).mapLosses(_ * 2)
        assertTrue(mapped.nTrials == 100, mapped.outcomes == Map(1 -> 20L, 2 -> 40L))
      },
      test("filterLosses keeps the satisfying trials and keeps the trial count") {
        val filtered = outcomesOf(100, Map(1 -> 10L, 2 -> 20L)).filterLosses(_ > 15L)
        assertTrue(filtered.nTrials == 100, filtered.outcomes == Map(2 -> 20L))
      }
    ),

    suite("Associative law (via the Commutative instance, which extends Associative)")(
      test("associativity: (a ⊕ b) ⊕ c == a ⊕ (b ⊕ c)") {
        check(genAligned3) { case (a, b, c) =>
          val assoc: zio.prelude.Associative[TrialOutcomes] = TrialOutcomes.commutative
          val left  = assoc.combine(assoc.combine(a, b), c)
          val right = assoc.combine(a, assoc.combine(b, c))
          assertTrue(left == right)
        }
      }
    ),

    suite("Commutative instance")(
      test("commutativity: a ⊕ b == b ⊕ a") {
        check(genAligned2) { case (a, b) =>
          val comm = TrialOutcomes.commutative
          assertTrue(comm.combine(a, b) == comm.combine(b, a))
        }
      }
    ),

    suite("Identity instance")(
      test("left identity: ∅ ⊕ a == a") {
        check(genNTrials.flatMap(n => genOutcomeMap(n).map(n -> _))) { case (nTrials, o) =>
          withCfg(nTrials) {
            val a  = outcomesOf(nTrials,o)
            val id = Identity[TrialOutcomes]
            assertTrue(id.combine(id.identity, a) == a)
          }
        }
      },
      test("right identity: a ⊕ ∅ == a") {
        check(genNTrials.flatMap(n => genOutcomeMap(n).map(n -> _))) { case (nTrials, o) =>
          withCfg(nTrials) {
            val a  = outcomesOf(nTrials,o)
            val id = Identity[TrialOutcomes]
            assertTrue(id.combine(a, id.identity) == a)
          }
        }
      },
      test("empty carries the configured trial count and no outcomes") {
        withCfg(777) {
          val e = TrialOutcomes.empty
          assertTrue(e.nTrials == 777, e.outcomes.isEmpty)
        }
      }
    ),

    suite("combine semantics")(
      test("outer join: trial IDs are the union of both operands") {
        check(genAligned2) { case (a, b) =>
          val combined = TrialOutcomes.combine(a, b)
          assertTrue(combined.trialIds == (a.trialIds ++ b.trialIds))
        }
      },
      test("pointwise sum: each trial's loss is the sum of both operands (missing = 0)") {
        check(genAligned2) { case (a, b) =>
          val combined = TrialOutcomes.combine(a, b)
          val allSum = (a.trialIds ++ b.trialIds).forall { t =>
            combined.outcomeOf(t) == a.outcomeOf(t) + b.outcomeOf(t)
          }
          assertTrue(allSum, combined.nTrials == a.nTrials)
        }
      },
      test("rejects operands with different trial counts") {
        val a = outcomesOf(100,Map(1 -> 1000L))
        val b = outcomesOf(200,Map(1 -> 2000L))
        assertTrue(
          try { TrialOutcomes.combine(a, b); false }
          catch { case _: IllegalArgumentException => true }
        )
      },
      test("zero losses are preserved (not filtered out)") {
        val withZero = outcomesOf(100,Map(1 -> 0L, 2 -> 1000L))
        val empty    = outcomesOf(100,Map.empty)
        val combined = TrialOutcomes.combine(withZero, empty)
        assertTrue(combined.trialIds.contains(1), combined.outcomeOf(1) == 0L)
      },
      test("combining empty operands stays empty") {
        val e = outcomesOf(100,Map.empty)
        assertTrue(TrialOutcomes.combine(TrialOutcomes.combine(e, e), e).outcomes.isEmpty)
      },
      test("overflowing pointwise sum throws instead of wrapping to a negative loss") {
        val a = outcomesOf(100,Map(1 -> Long.MaxValue))
        val b = outcomesOf(100,Map(1 -> 1L))
        assertTrue(
          try { TrialOutcomes.combine(a, b); false }
          catch { case _: ArithmeticException => true }
        )
      },
      test("large loss values don't overflow with reasonable aggregation") {
        val largeLoss = Long.MaxValue / 100
        val a = outcomesOf(100,Map(1 -> largeLoss))
        val combined = TrialOutcomes.combine(a, a)
        assertTrue(
          combined.outcomeOf(1) == largeLoss * 2,
          combined.outcomeOf(1) > 0
        )
      }
    )
  )
}
