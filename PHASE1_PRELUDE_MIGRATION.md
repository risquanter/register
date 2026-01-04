# Phase 1: ZIO Prelude Migration - Implementation Plan

**Status**: In Progress  
**Date Started**: January 4, 2026  
**Approval Required**: After each stage before proceeding

---

## Overview

Migrate domain and service classes to use ZIO Prelude type classes for functional design. Focus on explicit `Monoid`, `Equal`, `Ord`, and `Show` instances without changing collection implementations.

**Scope**:
- ✅ Add explicit type class instances
- ✅ Replace `Identity` with explicit `Monoid`
- ✅ Add `Ord[Loss]` to replace implicit `Ordering`
- ❌ NO collection migration (keep Vector, Map, TreeMap)
- ❌ NO Prelude Numeric (performance-critical)

---

## Stage 1: PreludeInstances Foundation

### Objectives
1. Create central `PreludeInstances.scala` file
2. Define lawful type class instances for core types
3. Add comprehensive tests

### Files to Create/Modify

#### 1.1: Create PreludeInstances.scala

**File**: `modules/common/src/main/scala/com/risquanter/register/domain/PreludeInstances.scala`

```scala
package com.risquanter.register.domain

import zio.prelude.*
import com.risquanter.register.domain.data.{Loss, TrialId}

/**
 * ZIO Prelude type class instances for domain types.
 * 
 * Provides lawful instances for:
 * - Monoid: Compositional aggregation (Loss, TrialId)
 * - Ord: Total ordering (Loss, TrialId)
 * - Equal: Value equality
 * - Show: Human-readable representation
 */
object PreludeInstances {
  
  // ══════════════════════════════════════════════════════════════════
  // Loss Type Class Instances
  // ══════════════════════════════════════════════════════════════════
  
  /** Monoid for Loss aggregation (additive) */
  given lossMonoid: Monoid[Loss] with {
    def identity: Loss = 0L
    def combine(a: Loss, b: Loss): Loss = a + b
  }
  
  /** Total ordering for Loss (natural Long ordering) */
  given lossOrd: Ord[Loss] = Ord.default[Long]
  
  /** Value equality for Loss */
  given lossEqual: Equal[Loss] = Equal.default[Long]
  
  /** Human-readable representation */
  given lossShow: Show[Loss] = Show.make(loss => s"$$${loss}")
  
  // ══════════════════════════════════════════════════════════════════
  // TrialId Type Class Instances
  // ══════════════════════════════════════════════════════════════════
  
  /** Total ordering for TrialId (natural Int ordering) */
  given trialIdOrd: Ord[TrialId] = Ord.default[Int]
  
  /** Value equality for TrialId */
  given trialIdEqual: Equal[TrialId] = Equal.default[Int]
  
  /** Human-readable representation */
  given trialIdShow: Show[TrialId] = Show.make(id => s"Trial#$id")
}
```

**Acceptance Criteria**:
- ✅ File compiles without errors
- ✅ All given instances are accessible via import

---

#### 1.2: Create PreludeInstancesSpec.scala

**File**: `modules/common/src/test/scala/com/risquanter/register/domain/PreludeInstancesSpec.scala`

```scala
package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import com.risquanter.register.domain.data.{Loss, TrialId}
import com.risquanter.register.domain.PreludeInstances.{given}

object PreludeInstancesSpec extends ZIOSpecDefault {
  
  def spec = suite("PreludeInstancesSpec")(
    
    suite("Loss Monoid")(
      test("identity is 0L") {
        assertTrue(Monoid[Loss].identity == 0L)
      },
      
      test("combine adds losses") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(Monoid[Loss].combine(a, b) == 3000L)
      },
      
      test("associativity: (a + b) + c == a + (b + c)") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        val c: Loss = 3000L
        
        val left = Monoid[Loss].combine(Monoid[Loss].combine(a, b), c)
        val right = Monoid[Loss].combine(a, Monoid[Loss].combine(b, c))
        
        assertTrue(left == right)
      },
      
      test("left identity: 0 + a == a") {
        val a: Loss = 5000L
        assertTrue(Monoid[Loss].combine(Monoid[Loss].identity, a) == a)
      },
      
      test("right identity: a + 0 == a") {
        val a: Loss = 5000L
        assertTrue(Monoid[Loss].combine(a, Monoid[Loss].identity) == a)
      },
      
      test("commutativity: a + b == b + a") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(
          Monoid[Loss].combine(a, b) == Monoid[Loss].combine(b, a)
        )
      }
    ),
    
    suite("Loss Ord")(
      test("less than") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        assertTrue(Ord[Loss].compare(a, b) == Ordering.LessThan)
      },
      
      test("greater than") {
        val a: Loss = 2000L
        val b: Loss = 1000L
        assertTrue(Ord[Loss].compare(a, b) == Ordering.GreaterThan)
      },
      
      test("equal") {
        val a: Loss = 1000L
        val b: Loss = 1000L
        assertTrue(Ord[Loss].compare(a, b) == Ordering.Equals)
      },
      
      test("transitivity: a < b && b < c => a < c") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        val c: Loss = 3000L
        
        val ab = Ord[Loss].compare(a, b) == Ordering.LessThan
        val bc = Ord[Loss].compare(b, c) == Ordering.LessThan
        val ac = Ord[Loss].compare(a, c) == Ordering.LessThan
        
        assertTrue(ab && bc && ac)
      }
    ),
    
    suite("Loss Equal")(
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
    
    suite("Loss Show")(
      test("formats with dollar sign") {
        val loss: Loss = 1000L
        assertTrue(Show[Loss].show(loss) == "$1000")
      }
    ),
    
    suite("TrialId Ord")(
      test("natural ordering") {
        val a: TrialId = 1
        val b: TrialId = 2
        assertTrue(Ord[TrialId].compare(a, b) == Ordering.LessThan)
      }
    ),
    
    suite("TrialId Show")(
      test("formats with Trial# prefix") {
        val trial: TrialId = 42
        assertTrue(Show[TrialId].show(trial) == "Trial#42")
      }
    )
  )
}
```

**Acceptance Criteria**:
- ✅ All 17 tests pass
- ✅ Monoid laws verified (associativity, identity)
- ✅ Ord laws verified (transitivity)
- ✅ Equal laws verified (reflexivity, symmetry, transitivity)

---

### Stage 1 Deliverables

1. ✅ `PreludeInstances.scala` created with 8 type class instances
2. ✅ `PreludeInstancesSpec.scala` with 17 tests passing
3. ✅ Run: `sbt "commonJVM/testOnly *PreludeInstancesSpec"`

**Approval Gate**: User reviews test results before proceeding to Stage 2

---

## Stage 2: Migrate SimulationResult to Explicit Monoid

### Objectives
1. Replace `Identity` with explicit `Monoid[RiskResult]`
2. Update all call sites to use `Monoid.combine`
3. Add comprehensive property tests

### Files to Modify

#### 2.1: Update SimulationResult.scala

**File**: `modules/common/src/main/scala/com/risquanter/register/domain/data/SimulationResult.scala`

**Changes**:
1. Replace `Identity[RiskResult]` instance with explicit `Monoid[RiskResult]`
2. Keep `Equal[RiskResult]` and `Show[RiskResult]` unchanged

**Before**:
```scala
given Identity[RiskResult] with {
  def identity: RiskResult = RiskResult("", Map.empty, 0)
  def combine(a: RiskResult, b: RiskResult): RiskResult = ...
}
```

**After**:
```scala
given Monoid[RiskResult] with {
  def identity: RiskResult = RiskResult("", Map.empty, 0)
  def combine(a: RiskResult, b: RiskResult): RiskResult = ...
}
```

**Acceptance Criteria**:
- ✅ File compiles
- ✅ Existing tests still pass

---

#### 2.2: Update Simulator.scala

**File**: `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala`

**Changes**:
Update `simulateTree` to use `Monoid[RiskResult].combine`:

**Before** (line ~158):
```scala
val combined = childRiskResults.reduce((a, b) => RiskResult.identity.combine(a, b))
```

**After**:
```scala
import com.risquanter.register.domain.data.SimulationResult.given

val combined = childRiskResults.reduce((a, b) => Monoid[RiskResult].combine(a, b))
```

**Acceptance Criteria**:
- ✅ File compiles
- ✅ `SimulatorSpec` tests pass

---

#### 2.3: Add SimulationResultMonoidSpec.scala

**File**: `modules/common/src/test/scala/com/risquanter/register/domain/data/SimulationResultMonoidSpec.scala`

```scala
package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*

object SimulationResultMonoidSpec extends ZIOSpecDefault {
  
  def spec = suite("SimulationResultMonoidSpec")(
    
    suite("Monoid Laws")(
      test("associativity: (a + b) + c == a + (b + c)") {
        val r1 = RiskResult("risk1", Map(1 -> 1000L, 2 -> 2000L), 10)
        val r2 = RiskResult("risk2", Map(2 -> 500L, 3 -> 1500L), 10)
        val r3 = RiskResult("risk3", Map(3 -> 3000L, 4 -> 4000L), 10)
        
        val left = Monoid[RiskResult].combine(Monoid[RiskResult].combine(r1, r2), r3)
        val right = Monoid[RiskResult].combine(r1, Monoid[RiskResult].combine(r2, r3))
        
        assertTrue(Equal[RiskResult].equal(left, right))
      },
      
      test("left identity: empty + a == a") {
        val r = RiskResult("risk", Map(1 -> 1000L), 10)
        val empty = Monoid[RiskResult].identity
        
        val combined = Monoid[RiskResult].combine(empty, r)
        
        assertTrue(combined.outcomes == r.outcomes)
      },
      
      test("right identity: a + empty == a") {
        val r = RiskResult("risk", Map(1 -> 1000L), 10)
        val empty = Monoid[RiskResult].identity
        
        val combined = Monoid[RiskResult].combine(r, empty)
        
        assertTrue(combined.outcomes == r.outcomes)
      }
    ),
    
    suite("Outer Join Semantics")(
      test("overlapping trials sum losses") {
        val r1 = RiskResult("r1", Map(1 -> 1000L, 2 -> 2000L), 10)
        val r2 = RiskResult("r2", Map(2 -> 500L, 3 -> 1500L), 10)
        
        val combined = Monoid[RiskResult].combine(r1, r2)
        
        assertTrue(
          combined.outcomes(1) == 1000L,  // Only in r1
          combined.outcomes(2) == 2500L,  // Sum: 2000 + 500
          combined.outcomes(3) == 1500L   // Only in r2
        )
      },
      
      test("disjoint trials preserved") {
        val r1 = RiskResult("r1", Map(1 -> 1000L), 10)
        val r2 = RiskResult("r2", Map(2 -> 2000L), 10)
        
        val combined = Monoid[RiskResult].combine(r1, r2)
        
        assertTrue(
          combined.outcomes.size == 2,
          combined.outcomes(1) == 1000L,
          combined.outcomes(2) == 2000L
        )
      }
    ),
    
    suite("Edge Cases")(
      test("combining two empty results") {
        val empty1 = RiskResult.empty("r1", 10)
        val empty2 = RiskResult.empty("r2", 10)
        
        val combined = Monoid[RiskResult].combine(empty1, empty2)
        
        assertTrue(combined.outcomes.isEmpty)
      },
      
      test("combining non-empty with empty") {
        val r = RiskResult("r", Map(1 -> 1000L), 10)
        val empty = RiskResult.empty("empty", 10)
        
        val combined = Monoid[RiskResult].combine(r, empty)
        
        assertTrue(combined.outcomes == r.outcomes)
      }
    )
  )
}
```

**Acceptance Criteria**:
- ✅ All 8 tests pass
- ✅ Monoid laws verified
- ✅ Outer join semantics correct

---

### Stage 2 Deliverables

1. ✅ `SimulationResult.scala` updated to use `Monoid`
2. ✅ `Simulator.scala` updated to use `Monoid[RiskResult].combine`
3. ✅ `SimulationResultMonoidSpec.scala` with 8 tests passing
4. ✅ Run: `sbt test` - all 111+ tests pass

**Approval Gate**: User reviews test results before proceeding to Stage 3

---

## Stage 3: Add Ord[Loss] and Update TreeMap Usage

### Objectives
1. Update `TreeMap` operations to use `Ord[Loss]` instead of implicit `Ordering`
2. Verify ordering-dependent operations still work correctly

### Files to Modify

#### 3.1: Update SimulationResult.scala - TreeMap Operations

**File**: `modules/common/src/main/scala/com/risquanter/register/domain/data/SimulationResult.scala`

**Changes**:
Import `Ord[Loss]` and use it for TreeMap operations:

**Before** (line ~62):
```scala
override lazy val maxLoss: Loss = 
  if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max
  
override lazy val minLoss: Loss = 
  if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min
```

**After**:
```scala
import com.risquanter.register.domain.PreludeInstances.{given}

override lazy val maxLoss: Loss = 
  if (outcomeCount.isEmpty) 0L 
  else outcomeCount.keys.maxBy(identity)(using Ord[Loss].toScala)
  
override lazy val minLoss: Loss = 
  if (outcomeCount.isEmpty) 0L 
  else outcomeCount.keys.minBy(identity)(using Ord[Loss].toScala)
```

**Note**: `Ord[Loss].toScala` converts Prelude `Ord` to standard library `Ordering`

**Acceptance Criteria**:
- ✅ File compiles
- ✅ `SimulationResultSpec` tests pass

---

#### 3.2: Add OrdUsageSpec.scala

**File**: `modules/common/src/test/scala/com/risquanter/register/domain/PreludeOrdUsageSpec.scala`

```scala
package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import com.risquanter.register.domain.data.{RiskResult, Loss}
import com.risquanter.register.domain.PreludeInstances.{given}

object PreludeOrdUsageSpec extends ZIOSpecDefault {
  
  def spec = suite("PreludeOrdUsageSpec")(
    
    suite("Ord[Loss] with TreeMap")(
      test("maxLoss uses Ord[Loss]") {
        val result = RiskResult("test", Map(1 -> 1000L, 2 -> 5000L, 3 -> 2000L), 10)
        
        assertTrue(result.maxLoss == 5000L)
      },
      
      test("minLoss uses Ord[Loss]") {
        val result = RiskResult("test", Map(1 -> 1000L, 2 -> 5000L, 3 -> 2000L), 10)
        
        assertTrue(result.minLoss == 1000L)
      },
      
      test("empty result has max/min of 0L") {
        val result = RiskResult.empty("test", 10)
        
        assertTrue(
          result.maxLoss == 0L,
          result.minLoss == 0L
        )
      },
      
      test("single outcome") {
        val result = RiskResult("test", Map(1 -> 1000L), 10)
        
        assertTrue(
          result.maxLoss == 1000L,
          result.minLoss == 1000L
        )
      }
    ),
    
    suite("Ord[Loss] comparison operations")(
      test("less than") {
        val a: Loss = 1000L
        val b: Loss = 2000L
        
        assertTrue(Ord[Loss].lessThan(a, b))
      },
      
      test("greater than") {
        val a: Loss = 2000L
        val b: Loss = 1000L
        
        assertTrue(Ord[Loss].greaterThan(a, b))
      },
      
      test("less than or equal") {
        val a: Loss = 1000L
        val b: Loss = 1000L
        
        assertTrue(Ord[Loss].lessThanOrEqual(a, b))
      }
    )
  )
}
```

**Acceptance Criteria**:
- ✅ All 8 tests pass
- ✅ TreeMap min/max operations work correctly
- ✅ Ord comparison methods verified

---

### Stage 3 Deliverables

1. ✅ `SimulationResult.scala` updated to use `Ord[Loss]`
2. ✅ `PreludeOrdUsageSpec.scala` with 8 tests passing
3. ✅ Run: `sbt test` - all 119+ tests pass

**Approval Gate**: User reviews test results before proceeding to Stage 4

---

## Stage 4: Property-Based Testing for Monoid Laws

### Objectives
1. Add ScalaCheck generators for RiskResult
2. Property-based tests for Monoid laws
3. Verify laws hold across random inputs

### Files to Create

#### 4.1: Create MonoidPropertySpec.scala

**File**: `modules/common/src/test/scala/com/risquanter/register/domain/data/MonoidPropertySpec.scala`

```scala
package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import org.scalacheck.{Gen, Arbitrary}

object MonoidPropertySpec extends ZIOSpecDefault {
  
  // ScalaCheck generators
  val genLoss: Gen[Loss] = Gen.chooseNum(0L, 1000000L)
  
  val genOutcomes: Gen[Map[TrialId, Loss]] = for {
    numTrials <- Gen.chooseNum(0, 20)
    trialIds <- Gen.listOfN(numTrials, Gen.chooseNum(0, 100))
    losses <- Gen.listOfN(numTrials, genLoss)
  } yield trialIds.zip(losses).toMap
  
  val genRiskResult: Gen[RiskResult] = for {
    name <- Gen.alphaNumStr
    outcomes <- genOutcomes
    nTrials <- Gen.chooseNum(1, 100)
  } yield RiskResult(name, outcomes, nTrials)
  
  def spec = suite("MonoidPropertySpec")(
    
    test("Monoid associativity property") {
      check(genRiskResult, genRiskResult, genRiskResult) { (r1, r2, r3) =>
        // Normalize nTrials for comparison
        val normalized = r1.copy(nTrials = 10)
        val r2n = r2.copy(nTrials = 10)
        val r3n = r3.copy(nTrials = 10)
        
        val left = Monoid[RiskResult].combine(
          Monoid[RiskResult].combine(normalized, r2n), 
          r3n
        )
        val right = Monoid[RiskResult].combine(
          normalized, 
          Monoid[RiskResult].combine(r2n, r3n)
        )
        
        // Compare outcomes (ignore name differences)
        assertTrue(left.outcomes == right.outcomes)
      }
    },
    
    test("Monoid left identity property") {
      check(genRiskResult) { r =>
        val empty = Monoid[RiskResult].identity
        val combined = Monoid[RiskResult].combine(empty, r)
        
        assertTrue(combined.outcomes == r.outcomes)
      }
    },
    
    test("Monoid right identity property") {
      check(genRiskResult) { r =>
        val empty = Monoid[RiskResult].identity
        val combined = Monoid[RiskResult].combine(r, empty)
        
        assertTrue(combined.outcomes == r.outcomes)
      }
    },
    
    test("combineAll matches repeated combine") {
      check(Gen.listOfN(5, genRiskResult)) { results =>
        if (results.isEmpty) {
          assertTrue(true)  // Skip empty lists
        } else {
          val normalized = results.map(_.copy(nTrials = 10))
          
          val viaReduce = normalized.reduce((a, b) => Monoid[RiskResult].combine(a, b))
          val viaCombineAll = Monoid[RiskResult].combineAll(normalized)
          
          assertTrue(viaReduce.outcomes == viaCombineAll.outcomes)
        }
      }
    }
  )
}
```

**Acceptance Criteria**:
- ✅ All 4 property tests pass
- ✅ Tests run 200 random examples each (default ScalaCheck)
- ✅ No counterexamples found

---

### Stage 4 Deliverables

1. ✅ `MonoidPropertySpec.scala` with 4 property tests passing
2. ✅ Run: `sbt "commonJVM/testOnly *MonoidPropertySpec"`
3. ✅ 800 property checks pass (4 tests × 200 examples)

**Approval Gate**: User reviews property test results

---

## Stage 5: Final Integration and Cleanup

### Objectives
1. Run full test suite
2. Update documentation
3. Remove any deprecated code

### Tasks

#### 5.1: Full Test Suite

Run all tests to verify no regressions:
```bash
sbt test
```

**Expected**: 120+ tests passing (111 current + 9 new)

---

#### 5.2: Update DEVELOPMENT_CONTEXT.md

Add section documenting Prelude usage:

```markdown
### ZIO Prelude Type Classes

**Type Class Instances**:
- `Monoid[Loss]` - Additive aggregation of losses
- `Monoid[RiskResult]` - Compositional risk aggregation with outer join
- `Ord[Loss]` - Total ordering for loss amounts
- `Equal[Loss]`, `Equal[RiskResult]` - Value equality
- `Show[Loss]`, `Show[TrialId]` - Human-readable formatting

**Location**: `modules/common/src/main/scala/com/risquanter/register/domain/PreludeInstances.scala`

**Usage**:
```scala
import com.risquanter.register.domain.PreludeInstances.{given}

// Monoid operations
val combined = Monoid[RiskResult].combine(result1, result2)

// Ord operations  
val ordered = losses.sorted(using Ord[Loss].toScala)

// Equal operations
if Equal[RiskResult].equal(r1, r2) then ...
```
```

---

#### 5.3: Commit Checklist

- ✅ All tests pass (120+ tests)
- ✅ No compilation warnings
- ✅ Documentation updated
- ✅ Code formatted

---

## Summary of Changes

### Files Created (5)
1. `modules/common/src/main/scala/com/risquanter/register/domain/PreludeInstances.scala`
2. `modules/common/src/test/scala/com/risquanter/register/domain/PreludeInstancesSpec.scala`
3. `modules/common/src/test/scala/com/risquanter/register/domain/data/SimulationResultMonoidSpec.scala`
4. `modules/common/src/test/scala/com/risquanter/register/domain/PreludeOrdUsageSpec.scala`
5. `modules/common/src/test/scala/com/risquanter/register/domain/data/MonoidPropertySpec.scala`

### Files Modified (3)
1. `modules/common/src/main/scala/com/risquanter/register/domain/data/SimulationResult.scala`
   - Replace `Identity` with `Monoid`
   - Update TreeMap min/max to use `Ord[Loss]`

2. `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala`
   - Update to use `Monoid[RiskResult].combine`

3. `DEVELOPMENT_CONTEXT.md`
   - Add Prelude usage documentation

### Test Count
- **Before**: 111 tests
- **After**: 120+ tests
- **New tests**: 33 tests across 5 spec files

---

## Success Criteria

- ✅ All 120+ tests pass
- ✅ No performance regression (< 1% overhead)
- ✅ All Monoid laws verified (associativity, identity)
- ✅ All Ord laws verified (transitivity, totality)
- ✅ All Equal laws verified (reflexivity, symmetry, transitivity)
- ✅ Property tests pass 800+ random examples
- ✅ Code compiles without warnings
- ✅ Documentation updated

---

**Next Phase**: Property-Based Testing (Equal, Ord laws) + Provenance Metadata
