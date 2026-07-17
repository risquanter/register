package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Identity
import com.risquanter.register.domain.data.Loss
import com.risquanter.register.domain.PreludeInstances.given

/**
 * Property-based tests for Identity (Monoid) laws using ZIO Test generators.
 *
 * Verifies algebraic properties across random inputs:
 * - Associativity: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)
 * - Left Identity: ∅ ⊕ a = a
 * - Right Identity: a ⊕ ∅ = a
 * - Commutativity (bonus for Loss): a ⊕ b = b ⊕ a
 *
 * Tests use ZIO Test generators to generate hundreds of random examples,
 * providing much stronger confidence than manual test cases.
 *
 * The simulation-outcome monoid laws live in `TrialOutcomesSpec` —
 * `TrialOutcomes` is the lawful monoid for trial-aligned aggregation.
 */
object IdentityPropertySpec extends ZIOSpecDefault {

  /** Generate random Loss values (0 to 10M) */
  val genLoss: Gen[Any, Loss] = Gen.long(0L, 10000000L)

  def spec = suite("IdentityPropertySpec")(

    suite("Loss Identity - Property Tests")(
      test("associativity property: (a + b) + c = a + (b + c)") {
        check(genLoss, genLoss, genLoss) { (a, b, c) =>
          val left  = Identity[Loss].combine(Identity[Loss].combine(a, b), c)
          val right = Identity[Loss].combine(a, Identity[Loss].combine(b, c))

          assertTrue(left == right)
        }
      },

      test("left identity property: 0 + a = a") {
        check(genLoss) { a =>
          val identity = Identity[Loss].identity
          val combined = Identity[Loss].combine(identity, a)

          assertTrue(combined == a)
        }
      },

      test("right identity property: a + 0 = a") {
        check(genLoss) { a =>
          val identity = Identity[Loss].identity
          val combined = Identity[Loss].combine(a, identity)

          assertTrue(combined == a)
        }
      },

      test("commutativity property: a + b = b + a") {
        check(genLoss, genLoss) { (a, b) =>
          val ab = Identity[Loss].combine(a, b)
          val ba = Identity[Loss].combine(b, a)

          assertTrue(ab == ba)
        }
      },

      test("combining with self doubles the value") {
        check(genLoss) { a =>
          val doubled = Identity[Loss].combine(a, a)

          assertTrue(doubled == a + a)
        }
      }
    )
  )
}
