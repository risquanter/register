package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.{Identity, Equal, Ord, Debug}
import com.risquanter.register.domain.data.{Loss, TrialId}
import com.risquanter.register.domain.PreludeInstances.{given}

object PreludeInstancesSpec extends ZIOSpecDefault {
  
  def spec = suite("PreludeInstancesSpec")(
    
    suite("Loss Identity (Monoid)")(
      test("identity is 0L") {
        assertTrue(Identity[Loss].identity == 0L)
      },
      
      test("combine adds losses") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(Identity[Loss].combine(a, b) == 3000L)
      },
      
      test("associativity: (a + b) + c == a + (b + c)") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        val c: Loss = 3000L
        
        val left = Identity[Loss].combine(Identity[Loss].combine(a, b), c)
        val right = Identity[Loss].combine(a, Identity[Loss].combine(b, c))
        
        assertTrue(left == right)
      },
      
      test("left identity: 0 + a == a") {
        val a: Loss = 5000L
        assertTrue(Identity[Loss].combine(Identity[Loss].identity, a) == a)
      },
      
      test("right identity: a + 0 == a") {
        val a: Loss = 5000L
        assertTrue(Identity[Loss].combine(a, Identity[Loss].identity) == a)
      },
      
      test("commutativity: a + b == b + a") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(
          Identity[Loss].combine(a, b) == Identity[Loss].combine(b, a)
        )
      }
    ),
    
    suite("Loss Ord")(
      test("less than") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(Ord[Loss].compare(a, b) == zio.prelude.Ordering.LessThan)
      },
      
      test("greater than") {
        val a: Loss = 2000L
        val b: Loss = 1000L
        assertTrue(Ord[Loss].compare(a, b) == zio.prelude.Ordering.GreaterThan)
      },
      
      test("equal") {
        val a: Loss = 1000L
        val b: Loss = 1000L
        assertTrue(Ord[Loss].compare(a, b) == zio.prelude.Ordering.Equals)
      },
      
      test("transitivity: a < b && b < c => a < c") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        val c: Loss = 3000L
        
        val ab = Ord[Loss].compare(a, b) == zio.prelude.Ordering.LessThan
        val bc = Ord[Loss].compare(b, c) == zio.prelude.Ordering.LessThan
        val ac = Ord[Loss].compare(a, c) == zio.prelude.Ordering.LessThan
        
        assertTrue(ab && bc && ac)
      }
    ),
    
    suite("Loss Equal (via Ord)")(
      test("reflexivity: a == a") {
        val a: Loss = 1000L
        assertTrue(Equal[Loss].equal(a, a))
      },
      
      test("symmetry: a == b => b == a") {
        val a: Loss = 1000L
        val b: Loss = 1000L
        assertTrue(
          Equal[Loss].equal(a, b) == Equal[Loss].equal(b, a)
        )
      },
      
      test("transitivity: a == b && b == c => a == c") {
        val a: Loss = 1000L
        val b: Loss = 1000L
        val c: Loss = 1000L
        
        val ab = Equal[Loss].equal(a, b)
        val bc = Equal[Loss].equal(b, c)
        val ac = Equal[Loss].equal(a, c)
        
        assertTrue(ab && bc && ac)
      },
      
      test("not equal") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(!Equal[Loss].equal(a, b))
      }
    ),
    
    suite("Loss Debug")(
      test("formats with Loss wrapper") {
        val loss: Loss = 1000L
        assertTrue(Debug[Loss].debug(loss).render == "\"Loss($1000)\"")
      }
    ),
    
    suite("TrialId Ord")(
      test("natural ordering") {
        val a: TrialId = 1
        val b: TrialId = 2
        assertTrue(Ord[TrialId].compare(a, b) == zio.prelude.Ordering.LessThan)
      }
    ),
    
    suite("TrialId Debug")(
      test("formats with Trial# prefix") {
        val trial: TrialId = 42
        assertTrue(Debug[TrialId].debug(trial).render == "\"Trial#42\"")
      }
    )
  )
}
