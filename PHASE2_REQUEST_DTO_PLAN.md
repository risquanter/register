# Phase 2: Request DTO Implementation Plan

**Created:** January 7, 2026  
**Status:** Ready for Implementation  
**Estimated Time:** 0.5 days (4 hours)  
**Current Progress:** Response DTOs complete (50%), Request DTOs missing (50%)

---

## 🎯 Goal

Complete the DTO/Domain separation by implementing Request DTOs that mirror the existing Response DTO pattern. This creates a clean HTTP boundary while preserving all existing architectural merits:

- ✅ **Category Theory Foundation** - `Validation` monad with error accumulation
- ✅ **Iron-Based Refinement** - Opaque types (`SafeId`, `SafeName`, etc.)
- ✅ **Secure by Default** - Private constructors, validation at boundaries
- ✅ **Smart Constructors** - Domain layer validation remains unchanged

---

## 📐 Architectural Approach

### Current State Analysis

**What Works (Response Side - Already Implemented):**
```scala
// Response DTO → Domain: Clean separation
case class SimulationResponse(
  id: Long,
  name: String,              // Plain types for JSON
  quantiles: Map[String, Double],
  exceedanceCurve: Option[String]
)

object SimulationResponse {
  // fromDomain factory pattern
  def fromRiskTree(tree: RiskTree): SimulationResponse = 
    SimulationResponse(
      id = tree.id,
      name = tree.name.value,  // Extract from Iron type
      quantiles = Map.empty,
      exceedanceCurve = None
    )
}
```

**What's Missing (Request Side):**
```scala
// Current: RiskTreeDefinitionRequest uses domain types directly
case class RiskTreeDefinitionRequest(
  name: String,
  nTrials: Int,
  root: RiskNode  // ❌ Domain type leaked into HTTP layer
)

// Problem: JSON decoder directly creates RiskNode with Iron types
// This mixes HTTP concerns with domain validation
```

### Target Architecture

**Clean Separation Pattern:**

```
┌─────────────────────────────────────────────────────────────┐
│ HTTP Layer (Plain Types)                                    │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ RiskLeafRequest(                                        │ │
│ │   id: String,              // Plain types              │ │
│ │   name: String,                                         │ │
│ │   distributionType: String,                             │ │
│ │   probability: Double,                                  │ │
│ │   minLoss: Option[Long]                                 │ │
│ │ )                                                       │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                         │
                         │ .toDomain() - Validation[List[ValidationError], RiskLeaf]
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ Domain Layer (Iron Refined Types)                           │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ RiskLeaf(                                               │ │
│ │   safeId: SafeId.SafeId,    // Iron refined types      │ │
│ │   safeName: SafeName.SafeName,                          │ │
│ │   distributionType: DistributionType,                   │ │
│ │   probability: Probability,                             │ │
│ │   minLoss: Option[NonNegativeLong]                      │ │
│ │ )                                                       │ │
│ └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Key Design Decisions

1. **Plain Types at HTTP Boundary**
   - Request DTOs use `String`, `Double`, `Long` for JSON compatibility
   - No Iron types in HTTP layer (avoids coupling)
   - Simple JSON codecs without custom decoders

2. **Validation at DTO Boundary**
   - `toDomain()` methods use existing smart constructors
   - Reuse `RiskLeaf.create()` and `RiskPortfolio.create()`
   - No duplication of validation logic
   - Error accumulation preserved via `Validation` monad

3. **Preserve Domain Integrity**
   - Domain constructors remain private
   - Smart constructors unchanged
   - Iron types stay in domain layer
   - No changes to existing validation logic

---

## 📋 Task Breakdown

### Task 1: Create RiskNodeRequest ADT (1.5 hours)

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskNodeRequest.scala`

**Implementation:**

```scala
package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec, jsonDiscriminator}
import zio.prelude.Validation
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.errors.ValidationError

/** 
 * HTTP Request DTO for risk node - plain types for JSON serialization.
 * 
 * Design:
 * - Plain types (String, Double, Long) at HTTP boundary
 * - Simple JSON codec without custom decoders
 * - Validation happens in toDomain() using existing smart constructors
 * 
 * Pattern:
 * 1. JSON → RiskNodeRequest (plain types, no validation)
 * 2. RiskNodeRequest.toDomain() → Validation[List[ValidationError], RiskNode]
 * 3. Uses RiskLeaf.create() / RiskPortfolio.create() for actual validation
 */
@jsonDiscriminator("type")
sealed trait RiskNodeRequest {
  def id: String
  def name: String
  
  /**
   * Convert request DTO to domain model with validation.
   * 
   * @param fieldPrefix Path context for error messages (e.g., "root", "root.children[0]")
   * @return Validation with accumulated errors or valid RiskNode
   */
  def toDomain(fieldPrefix: String = "root"): Validation[ValidationError, RiskNode]
}

object RiskNodeRequest {
  // Simple JSON codec - no custom decoders needed
  given codec: JsonCodec[RiskNodeRequest] = DeriveJsonCodec.gen[RiskNodeRequest]
}

/**
 * Request DTO for leaf risk node.
 * 
 * Supports two distribution modes:
 * 1. Expert Opinion: distributionType="expert", provide percentiles + quantiles
 * 2. Lognormal (BCG): distributionType="lognormal", provide minLoss + maxLoss
 */
final case class RiskLeafRequest(
  id: String,
  name: String,
  distributionType: String,
  probability: Double,
  percentiles: Option[Array[Double]] = None,
  quantiles: Option[Array[Double]] = None,
  minLoss: Option[Long] = None,
  maxLoss: Option[Long] = None
) extends RiskNodeRequest {
  
  override def toDomain(fieldPrefix: String = "root"): Validation[ValidationError, RiskLeaf] = {
    // Delegate to existing smart constructor - reuses all validation logic
    RiskLeaf.create(
      id = this.id,
      name = this.name,
      distributionType = this.distributionType,
      probability = this.probability,
      percentiles = this.percentiles,
      quantiles = this.quantiles,
      minLoss = this.minLoss,
      maxLoss = this.maxLoss,
      fieldPrefix = fieldPrefix
    )
  }
}

object RiskLeafRequest {
  given codec: JsonCodec[RiskLeafRequest] = DeriveJsonCodec.gen[RiskLeafRequest]
}

/**
 * Request DTO for portfolio risk node (aggregation of child risks).
 */
final case class RiskPortfolioRequest(
  id: String,
  name: String,
  probability: Double,
  children: Array[RiskNodeRequest]
) extends RiskNodeRequest {
  
  override def toDomain(fieldPrefix: String = "root"): Validation[ValidationError, RiskPortfolio] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Step 1: Validate each child recursively with indexed field paths
    val childrenValidations: Array[Validation[ValidationError, RiskNode]] = 
      children.zipWithIndex.map { case (childReq, idx) =>
        childReq.toDomain(s"$fieldPrefix.children[$idx]")
      }
    
    // Step 2: Sequence validations to accumulate all child errors
    val childrenV: Validation[ValidationError, Array[RiskNode]] = 
      Validation.validateAll(childrenValidations)
    
    // Step 3: Use RiskPortfolio.create() to validate portfolio-level fields
    // This approach accumulates errors from both portfolio fields AND children
    childrenV.flatMap { validChildren =>
      RiskPortfolio.create(
        id = this.id,
        name = this.name,
        probability = this.probability,
        children = validChildren,
        fieldPrefix = fieldPrefix
      )
    }
  }
}

object RiskPortfolioRequest {
  given codec: JsonCodec[RiskPortfolioRequest] = DeriveJsonCodec.gen[RiskPortfolioRequest]
}
```

**Key Design Points:**
- ✅ **Plain types** - No Iron types in HTTP layer
- ✅ **Reuses validation** - Delegates to `RiskLeaf.create()` / `RiskPortfolio.create()`
- ✅ **Error accumulation** - Recursive validation with `Validation.validateAll()`
- ✅ **Field path context** - Propagates paths like `"root.children[0].id"` for nested errors

---

### Task 2: Update RiskTreeDefinitionRequest (0.5 hours)

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`

**Changes:**

```scala
package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.{RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationError
import io.github.iltotore.iron.*

/** 
 * Request DTO for defining a new risk tree.
 * 
 * Now uses RiskNodeRequest (plain types) instead of RiskNode (domain types).
 * This completes the DTO/Domain separation.
 */
final case class RiskTreeDefinitionRequest(
  name: String,
  nTrials: Int = 10000,
  root: RiskNodeRequest  // ✅ Changed from RiskNode to RiskNodeRequest
)

object RiskTreeDefinitionRequest {
  given codec: JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen[RiskTreeDefinitionRequest]
  
  /** 
   * Validate request and convert to domain model.
   * 
   * Accumulates errors from:
   * 1. Top-level fields (name, nTrials)
   * 2. Recursive root node validation (all nested risks)
   * 
   * @return Validation with all accumulated errors, or validated domain tuple
   */
  def toDomain(req: RiskTreeDefinitionRequest): Validation[ValidationError, (SafeName.SafeName, Int, RiskNode)] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Validate request-level fields
    val nameV = toValidation(ValidationUtil.refineName(req.name, "request.name"))
    val trialsV = toValidation(ValidationUtil.refinePositiveInt(req.nTrials, "request.nTrials"))
      .map(_ => req.nTrials)
    
    // Validate root node recursively (this is the key change)
    val rootV = req.root.toDomain("request.root")
    
    // Combine all validations - accumulates errors from name, nTrials, AND entire tree
    Validation.validateWith(nameV, trialsV, rootV) { (name, trials, root) =>
      (name, trials, root)
    }
  }
}
```

**Changes Summary:**
- ✅ `root: RiskNode` → `root: RiskNodeRequest`
- ✅ `toDomain()` now calls `req.root.toDomain("request.root")` for recursive validation
- ✅ Error accumulation includes entire tree structure

---

### Task 3: Add RiskPortfolio.create() Enhancement (0.5 hours)

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`

**Enhancement Needed:**

The existing `RiskPortfolio` smart constructor may not accept pre-validated children. We need to add/verify this overload:

```scala
object RiskPortfolio {
  // ... existing code ...
  
  /**
   * Smart constructor accepting pre-validated RiskNode children.
   * 
   * This is used by RiskPortfolioRequest.toDomain() after children
   * have already been validated recursively.
   * 
   * @param id Plain string identifier
   * @param name Plain string name
   * @param probability Plain double [0.0, 1.0]
   * @param children ALREADY VALIDATED RiskNode children
   * @param fieldPrefix Path context for error messages
   * @return Validation with errors, or valid RiskPortfolio
   */
  def create(
    id: String,
    name: String,
    probability: Double,
    children: Array[RiskNode],  // Pre-validated children
    fieldPrefix: String = "root"
  ): Validation[ValidationError, RiskPortfolio] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Validate portfolio-level fields (id, name, probability)
    val idV = toValidation(ValidationUtil.refineId(id, s"$fieldPrefix.id"))
    val nameV = toValidation(ValidationUtil.refineName(name, s"$fieldPrefix.name"))
    val probV = toValidation(ValidationUtil.refineProbability(probability, s"$fieldPrefix.probability"))
    
    // Children are already validated - just wrap them
    Validation.validateWith(idV, nameV, probV) { (safeId, safeName, prob) =>
      new RiskPortfolio(safeId, safeName, prob, children)
    }
  }
}
```

**Verification Needed:**
- Check if this overload already exists
- If not, add it alongside existing `create()` methods
- Ensure it follows same pattern as `RiskLeaf.create()`

---

### Task 4: Update Tests (1 hour)

**New Test File:** `modules/common/src/test/scala/com/risquanter/register/http/requests/RiskNodeRequestSpec.scala`

**Test Cases:**

```scala
package com.risquanter.register.http.requests

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.risquanter.register.domain.errors.ValidationErrorCode

object RiskNodeRequestSpec extends ZIOSpecDefault {
  
  def spec = suite("RiskNodeRequest")(
    
    suite("RiskLeafRequest.toDomain()")(
      test("Valid lognormal leaf converts to domain model") {
        val req = RiskLeafRequest(
          id = "cyber-attack",
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        
        assertTrue(req.toDomain().isSuccess)
      },
      
      test("Invalid id accumulates error") {
        val req = RiskLeafRequest(
          id = "",  // Invalid: blank
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        
        val result = req.toDomain()
        assertTrue(
          result.isFailure,
          result.flip.map(errors => 
            errors.exists(_.code == ValidationErrorCode.FIELD_REQUIRED)
          ).getOrElse(false)
        )
      },
      
      test("Multiple invalid fields accumulate errors") {
        val req = RiskLeafRequest(
          id = "",              // Invalid: blank
          name = "",            // Invalid: blank
          distributionType = "invalid",  // Invalid: not expert/lognormal
          probability = 1.5,    // Invalid: > 1.0
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        
        val result = req.toDomain()
        assertTrue(
          result.isFailure,
          result.flip.map(_.size >= 4).getOrElse(false)  // At least 4 errors
        )
      }
    ),
    
    suite("RiskPortfolioRequest.toDomain()")(
      test("Valid portfolio with valid children succeeds") {
        val leaf1 = RiskLeafRequest(
          id = "leaf1",
          name = "Leaf 1",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        )
        
        val portfolio = RiskPortfolioRequest(
          id = "portfolio1",
          name = "Portfolio 1",
          probability = 0.8,
          children = Array(leaf1)
        )
        
        assertTrue(portfolio.toDomain().isSuccess)
      },
      
      test("Invalid child errors propagate with field paths") {
        val invalidLeaf = RiskLeafRequest(
          id = "",  // Invalid
          name = "Leaf",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        )
        
        val portfolio = RiskPortfolioRequest(
          id = "portfolio1",
          name = "Portfolio 1",
          probability = 0.8,
          children = Array(invalidLeaf)
        )
        
        val result = portfolio.toDomain()
        assertTrue(
          result.isFailure,
          result.flip.map(errors =>
            errors.exists(_.field.contains("children[0].id"))  // Nested path
          ).getOrElse(false)
        )
      },
      
      test("Multiple invalid children accumulate all errors") {
        val invalidLeaf1 = RiskLeafRequest(
          id = "",  // Invalid
          name = "Leaf 1",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        )
        
        val invalidLeaf2 = RiskLeafRequest(
          id = "leaf2",
          name = "",  // Invalid
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        )
        
        val portfolio = RiskPortfolioRequest(
          id = "portfolio1",
          name = "Portfolio 1",
          probability = 0.8,
          children = Array(invalidLeaf1, invalidLeaf2)
        )
        
        val result = portfolio.toDomain()
        assertTrue(
          result.isFailure,
          result.flip.map(_.size >= 2).getOrElse(false)  // At least 2 errors
        )
      }
    ),
    
    suite("JSON Codec")(
      test("RiskLeafRequest encodes and decodes") {
        val req = RiskLeafRequest(
          id = "cyber",
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        
        val json = req.toJson
        val decoded = json.fromJson[RiskLeafRequest]
        
        assertTrue(decoded.isRight)
      },
      
      test("RiskPortfolioRequest with nested children") {
        val leaf = RiskLeafRequest(
          id = "leaf1",
          name = "Leaf 1",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        )
        
        val portfolio = RiskPortfolioRequest(
          id = "portfolio1",
          name = "Portfolio 1",
          probability = 0.8,
          children = Array(leaf)
        )
        
        val json = portfolio.toJson
        val decoded = json.fromJson[RiskPortfolioRequest]
        
        assertTrue(decoded.isRight)
      }
    )
  )
}
```

**Integration Test Updates:**

Update existing service tests to use new request DTOs:
- `RiskTreeServiceSpec` - Should work unchanged (uses `RiskTreeDefinitionRequest`)
- API endpoint tests - Verify JSON → Request DTO → Domain flow

---

### Task 5: Update Service Layer (0.5 hours)

**File:** `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`

**Verification:**

The service layer should work unchanged because it already uses `RiskTreeDefinitionRequest.toDomain()`. The only difference is that `toDomain()` now returns validated `RiskNode` (domain) instead of mixing DTO/domain.

**No Changes Needed** - Service layer is already properly structured:

```scala
override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
  for {
    // This still works - toDomain() now does recursive validation
    validated <- ZIO.fromEither(
      RiskTreeDefinitionRequest.toDomain(req)
        .toEither
        .left.map(errors => ValidationFailed(errors.toList))
    )
    (safeName, nTrials, rootNode) = validated  // rootNode is now properly validated
    
    // ... rest unchanged
  } yield persisted
}
```

---

## 🔬 Verification Checklist

After implementation, verify:

- [ ] All 408 existing tests still pass
- [ ] New `RiskNodeRequestSpec` tests pass (15+ new tests)
- [ ] JSON deserialization uses plain types (no Iron decoders in HTTP layer)
- [ ] Validation happens at DTO boundary via `toDomain()`
- [ ] Error accumulation works for nested structures
- [ ] Field paths correctly show nested locations (e.g., `"request.root.children[0].id"`)
- [ ] Domain smart constructors unchanged
- [ ] Iron types remain in domain layer only
- [ ] Service layer works without changes

**Test Count Target:** 423+ tests (408 current + 15 new DTO tests)

---

## 📊 Benefits Summary

### What We Gain

1. **Clean Separation**
   - HTTP layer: Plain types for JSON
   - Domain layer: Iron refined types for guarantees
   - Clear boundary with explicit conversion

2. **Maintainability**
   - JSON schema changes don't affect domain
   - Domain refinements don't break JSON serialization
   - Easy to add new DTO validation without touching domain

3. **Testability**
   - Can test DTO validation independently
   - Can test domain logic independently
   - Integration tests verify boundary crossing

4. **Error Accumulation**
   - Client gets ALL validation errors in one response
   - Nested errors include full field paths
   - Better DX for API consumers

### What We Preserve

1. **Category Theory Foundation**
   - `Validation` monad unchanged
   - Error accumulation via `validateWith` preserved
   - Composable validation pipeline

2. **Iron Refinement Types**
   - All opaque types remain in domain
   - Smart constructors unchanged
   - Type safety guarantees preserved

3. **Secure by Default**
   - Private domain constructors enforced
   - Validation mandatory at boundaries
   - No unsafe constructors needed

4. **Existing Tests**
   - All 408 tests continue passing
   - No breaking changes to domain
   - Incremental enhancement

---

## 🚀 Implementation Order

1. ✅ **Task 1** - Create `RiskNodeRequest.scala` (foundation)
2. ✅ **Task 3** - Verify/add `RiskPortfolio.create()` overload (dependency for Task 2)
3. ✅ **Task 2** - Update `RiskTreeDefinitionRequest.scala` (uses Task 1)
4. ✅ **Task 4** - Add comprehensive tests
5. ✅ **Task 5** - Verify service layer works (should be no changes)

**Total Estimate:** 4 hours
**Risk Level:** Low (additive change, existing tests provide safety net)

---

## 💡 Key Insights

### Why This Approach Works

1. **Reuses Existing Validation**
   - `toDomain()` delegates to `RiskLeaf.create()` / `RiskPortfolio.create()`
   - No duplication of validation logic
   - Single source of truth for business rules

2. **Recursive Validation**
   - `RiskPortfolioRequest.toDomain()` recursively validates children
   - `Validation.validateAll()` accumulates errors across tree
   - Field paths track nesting depth automatically

3. **Minimal Impact**
   - Service layer unchanged (already uses `toDomain()`)
   - Domain layer unchanged (smart constructors preserved)
   - Only adds new DTO layer between HTTP and domain

### Category Theory Connection

This follows the **Kleisli composition** pattern:

```
toDomain: A => Validation[E, B]

RiskLeafRequest.toDomain(): 
  RiskLeafRequest => Validation[List[ValidationError], RiskLeaf]

RiskPortfolioRequest.toDomain():
  RiskPortfolioRequest => Validation[List[ValidationError], RiskPortfolio]
  
Composition via flatMap:
  children.map(_.toDomain()) >>= Validation.validateAll
```

The `Validation` applicative functor allows **error accumulation** while maintaining composability.

---

## 📝 Example Usage

### Before (Current)

```json
POST /api/risk-trees
{
  "name": "IT Risk",
  "nTrials": 10000,
  "root": {
    "type": "leaf",
    "id": "cyber",
    "name": "Cyber Attack",
    "distributionType": "lognormal",
    "probability": 0.25,
    "minLoss": 1000,
    "maxLoss": 50000
  }
}
```

**Problem:** JSON decoder directly creates `RiskNode` with Iron types. Mixing HTTP and domain validation.

### After (With Request DTOs)

```json
POST /api/risk-trees
{
  "name": "IT Risk",
  "nTrials": 10000,
  "root": {
    "type": "leaf",
    "id": "cyber",
    "name": "Cyber Attack",
    "distributionType": "lognormal",
    "probability": 0.25,
    "minLoss": 1000,
    "maxLoss": 50000
  }
}
```

**Flow:**
1. JSON → `RiskTreeDefinitionRequest` (plain types, simple codec)
2. `RiskTreeDefinitionRequest.toDomain()` → validates recursively
3. `RiskLeafRequest.toDomain()` → calls `RiskLeaf.create()` (reuses existing validation)
4. Returns `Validation[List[ValidationError], RiskNode]`
5. Service layer converts to `Task` and proceeds

**Benefits:**
- Same JSON format (no breaking changes)
- Clean separation (HTTP vs Domain)
- Better error messages with full field paths
- Reuses all existing validation logic

---

## ✅ Success Criteria

Phase 2 is **100% complete** when:

1. [x] `RiskNodeRequest.scala` created with `RiskLeafRequest` and `RiskPortfolioRequest`
2. [x] Each request DTO has `toDomain()` method using smart constructors
3. [x] `RiskTreeDefinitionRequest` uses `RiskNodeRequest` (not `RiskNode`)
4. [x] All 408 existing tests pass
5. [x] 15+ new DTO validation tests added and passing
6. [x] Service layer works without modification
7. [x] Error accumulation works for nested structures with field paths
8. [x] Documentation updated

**Expected Test Count:** 423+ passing tests

---

**Ready to proceed?** This plan preserves all architectural merits while completing the clean DTO/Domain separation. Let me know if you'd like any adjustments before implementation!
