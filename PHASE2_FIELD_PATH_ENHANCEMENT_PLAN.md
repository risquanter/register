# Phase 2 Field Path Enhancement Plan

**Created:** January 7, 2026  
**Estimated Time:** 2-3 hours  
**Goal:** Enhance nested error paths from `riskLeaf[id=cyber].field` to `root.children[0].field`

---

## 🎯 Objective

Improve field path tracking in validation errors for nested portfolio structures to show full hierarchical paths.

**Current State:**
```
Error: [riskLeaf[id=cyber].probability] Value must be less than 1.0
```

**Target State:**
```
Error: [root.children[0].probability] Value must be less than 1.0
Error: [root.children[1].children[0].id] Must be at least 3 characters
```

---

## 🧮 Functional Programming Approach

**Critical Decision:** This implementation will be **fully referentially transparent** with no side effects.

### ❌ What We WON'T Do (Impure Approach)

```scala
// IMPURE - Uses ThreadLocal (side effects, not referentially transparent)
object DecodingContext {
  private val contextThreadLocal = ThreadLocal.withInitial[String](() => "root")
  
  def withContext[A](path: String)(block: => A): A = {
    val previous = contextThreadLocal.get()  // ← Mutable state!
    try {
      contextThreadLocal.set(path)  // ← Side effect!
      block
    } finally {
      contextThreadLocal.set(previous)  // ← Cleanup side effect!
    }
  }
}
```

**Problems:**
- Not referentially transparent (mutable state)
- Side effects (ThreadLocal modification)
- Hard to reason about (implicit context)
- Breaks FP principles

### ✅ What We WILL Do (Pure Approach)

```scala
// PURE - Post-decode validation with explicit recursion
def validateRiskNodeWithPath(
  node: RiskNode,
  path: String
): Validation[ValidationError, RiskNode] = {
  node match {
    case leaf: RiskLeaf =>
      // Re-validate with explicit path parameter
      RiskLeaf.create(..., fieldPrefix = path)
      
    case portfolio: RiskPortfolio =>
      // Pure recursive fold - no mutation
      portfolio.children.zipWithIndex.foldLeft(...) { case (accV, (child, idx)) =>
        val childPath = s"$path.children[$idx]"  // ← Pure string construction
        val childV = validateRiskNodeWithPath(child, childPath)  // ← Pure recursion
        Validation.validateWith(accV, childV)((acc, c) => acc :+ c)  // ← Pure composition
      }
  }
}
```

**Benefits:**
- ✅ Referentially transparent (pure function)
- ✅ No side effects (explicit parameters only)
- ✅ Easy to test (same input → same output)
- ✅ Composable (functional recursion)
- ✅ Thread-safe (no shared mutable state)

**Trade-off:**
- Validates twice: JSON decode + path-aware re-validation
- Performance impact: Negligible (validation is fast, ~microseconds)
- Benefit: Perfect error messages + pure FP code

---

## 📋 Implementation Tasks

### Functional Approach: Post-Decode Re-Validation (Referentially Transparent)

**Key Insight:** The existing decoders work fine. We just need to **re-validate** the decoded tree in `toDomain()` with proper path tracking. This is:
- ✅ Pure (no side effects)
- ✅ Referentially transparent
- ✅ Simple (no decoder changes)
- ✅ Composable (recursive pure functions)

### Task 1: Add Recursive Path Validation to RiskTreeDefinitionRequest (1.5 hours)

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`

**Implementation:** (See detailed code in Task 2 section below)

The approach:
1. JSON decoder creates domain objects (as it does now)
2. `toDomain()` re-validates the tree with proper paths
3. Recursive `validateRiskNodeWithPath()` function walks tree
4. Each child gets indexed path: `"request.root.children[0]"`, `"request.root.children[1]"`, etc.
5. Pure functional fold accumulates errors

**No mutation, no ThreadLocal, just pure recursive validation.**

**Problem:**
Current decoders use hardcoded field prefixes that don't track parent context:

```scala
// Current - loses parent context
given decoder: JsonDecoder[RiskLeaf] = RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
  create(
    raw.id, raw.name, ...,
    fieldPrefix = s"riskLeaf[id=${raw.id}]"  // ← Hardcoded, no parent
  )
}
```

**Solution:**
Create parameterized decoders that accept and propagate parent path context.

#### Step 1.1: Update RiskLeaf Decoder

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`

**Changes:**

```scala
object RiskLeaf {
  // ... existing code ...
  
  /** Context-aware decoder that tracks parent path in error messages.
    * 
    * @param parentPath The path to this node from the root (e.g., "root", "root.children[0]")
    * @return JsonDecoder that produces errors with full hierarchical paths
    */
  def decoderWithContext(parentPath: String): JsonDecoder[RiskLeaf] = 
    RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
      create(
        raw.id, raw.name, raw.distributionType, raw.probability,
        raw.percentiles, raw.quantiles, raw.minLoss, raw.maxLoss,
        fieldPrefix = parentPath  // ← Use provided parent path
      ).toEither.left.map(errors => 
        errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")
      )
    }
  
  /** Default decoder for backward compatibility - uses id-based path */
  given decoder: JsonDecoder[RiskLeaf] = 
    RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
      decoderWithContext(s"riskLeaf[id=${raw.id}]").decode(???) // Need to rethink this
    }
}
```

**Issue with Above Approach:** We can't easily call `decoderWithContext` from the given decoder because we've already consumed the JSON in the first decoder step.

**Better Approach - Use Decoder Wrapper:**

```scala
object RiskLeaf {
  // ... existing create() and other methods ...
  
  /** Internal decoder factory that creates context-aware decoder.
    * 
    * This is used by RiskNode codec to provide parent path context.
    */
  private[data] def makeDecoder(parentPath: String): JsonDecoder[RiskLeaf] = 
    RiskLeafRaw.rawCodec.decoder.mapOrFail { raw =>
      create(
        raw.id, raw.name, raw.distributionType, raw.probability,
        raw.percentiles, raw.quantiles, raw.minLoss, raw.maxLoss,
        fieldPrefix = parentPath
      ).toEither.left.map(errors => 
        errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")
      )
    }
  
  /** Default decoder - uses makeDecoder with id-based path for backward compatibility */
  given decoder: JsonDecoder[RiskLeaf] = 
    JsonDecoder[Json].mapOrFail { json =>
      // First extract id to build path, then decode with context
      json.as[RiskLeafRaw].flatMap { raw =>
        makeDecoder(s"riskLeaf[id=${raw.id}]").decodeJson(json)
      }
    }
}
```

**Even Better - Keep It Simple:**

After analysis, the issue is that `RiskNode.codec` needs to be context-aware, not individual leaf/portfolio decoders.

#### Step 1.2: Create Context-Aware RiskNode Codec

**Key Insight:** The `RiskNode` codec is what's used recursively. If we make it context-aware, it will propagate paths automatically.

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`

```scala
object RiskNode {
  /** Context-aware codec factory for recursive decoding with parent paths.
    * 
    * This is used internally by RiskPortfolio to decode children with
    * indexed paths like "root.children[0]", "root.children[1]", etc.
    * 
    * @param parentPath Path to parent node (e.g., "root", "root.children[0]")
    * @return JsonCodec that uses the parent path for error messages
    */
  private[data] def codecWithContext(parentPath: String): JsonCodec[RiskNode] = {
    val decoder: JsonDecoder[RiskNode] = JsonDecoder[Json].mapOrFail { json =>
      // Check discriminator to determine type
      json.hcursor.get[String]("type").flatMap {
        case "leaf" =>
          RiskLeaf.makeDecoder(parentPath).decodeJson(json)
        case "portfolio" =>
          RiskPortfolio.makeDecoder(parentPath).decodeJson(json)
        case other =>
          Left(s"Unknown type: $other")
      }
    }
    
    // Encoder remains the same (no path needed for output)
    val encoder: JsonEncoder[RiskNode] = new JsonEncoder[RiskNode] {
      override def encodeJson(node: RiskNode, indent: Option[Int]): Json = node match {
        case leaf: RiskLeaf => RiskLeaf.encoder.encodeJson(leaf, indent)
        case portfolio: RiskPortfolio => RiskPortfolio.encoder.encodeJson(portfolio, indent)
      }
    }
    
    JsonCodec(encoder, decoder)
  }
  
  // Default codec uses DeriveJsonCodec (backward compatibility)
  given codec: JsonCodec[RiskNode] = DeriveJsonCodec.gen[RiskNode]
  
  // Tapir schema unchanged
  given schema: Schema[RiskNode] = Schema.any[RiskNode]
}
```

#### Step 1.3: Update RiskPortfolio to Use Context-Aware Children Decoding

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`

```scala
object RiskPortfolio {
  // ... existing create() method unchanged ...
  
  /** Internal decoder factory with parent path context */
  private[data] def makeDecoder(parentPath: String): JsonDecoder[RiskPortfolio] = {
    // Custom raw type with context-aware children decoding
    case class RiskPortfolioRawWithContext(
      id: String,
      name: String,
      childrenJson: Json  // Store raw JSON to decode with context
    )
    
    JsonDecoder[Json].mapOrFail { json =>
      // Parse id, name, and get children JSON
      for {
        id <- json.hcursor.get[String]("id")
        name <- json.hcursor.get[String]("name")
        childrenJson <- json.hcursor.get[Json]("children")
        
        // Decode children array with indexed paths
        childrenArray <- childrenJson.as[List[Json]] match {
          case Right(jsonList) =>
            jsonList.zipWithIndex.traverse { case (childJson, idx) =>
              val childPath = s"$parentPath.children[$idx]"
              RiskNode.codecWithContext(childPath).decoder.decodeJson(childJson)
            }.map(_.toArray)
          case Left(err) => Left(err)
        }
        
        // Validate portfolio-level fields
        validated <- create(id, name, childrenArray, fieldPrefix = parentPath)
          .toEither
          .left.map(errors => 
            errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")
          )
      } yield validated
    }
  }
  
  /** Default decoder for backward compatibility */
  given decoder: JsonDecoder[RiskPortfolio] = 
    RiskPortfolioRaw.rawCodec.decoder.mapOrFail { raw =>
      create(raw.id, raw.name, raw.children, fieldPrefix = s"riskPortfolio[id=${raw.id}]")
        .toEither.left.map(errors => 
          errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")
        )
    }
  
  // Encoder unchanged
  given encoder: JsonEncoder[RiskPortfolio] = // ... existing code ...
  
  given codec: JsonCodec[RiskPortfolio] = JsonCodec(encoder, decoder)
}
```

**Problem with Above:** Too complex, requires manual JSON cursor manipulation.

---

### **Revised Simpler Approach**

After analysis, the cleanest approach is to:
1. Add a `parentPath` parameter to the `Raw → Domain` conversion
2. Use thread-local or context passing in `RiskTreeDefinitionRequest.toDomain()`
3. Let the existing decoders work as-is, but enhance the entry point

#### **Better Task 1: Enhance RiskTreeDefinitionRequest Entry Point**

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`

**Current:**
```scala
object RiskTreeDefinitionRequest {
  def toDomain(req: RiskTreeDefinitionRequest): Validation[ValidationError, ...] = {
    val nameV = toValidation(ValidationUtil.refineName(req.name, "request.name"))
    val trialsV = toValidation(ValidationUtil.refinePositiveInt(req.nTrials, "request.nTrials"))
    
    // root is already validated during JSON parsing
    Validation.validateWith(nameV, trialsV, Validation.succeed(req.root)) { ... }
  }
}
```

**Issue:** By the time we reach `toDomain()`, JSON parsing has already happened with the default decoder.

**Solution:** The field path enhancement needs to happen **during JSON decoding**, not after. This requires modifying the decoders themselves.

---

### **Final Simplified Approach**

The cleanest solution is to add an optional context parameter to smart constructors and thread it through the decoder chain:

#### Task 1: Add Path Parameter to Smart Constructors (Already Done!)

**Key Insight:** The smart constructors (`RiskLeaf.create()`, `RiskPortfolio.create()`) already accept a `fieldPrefix` parameter. We just need to pass the correct path during decoding.

**Current Signature:**
```scala
def create(
  id: String,
  name: String,
  ...,
  fieldPrefix: String = "root"  // ← Already supports custom paths!
): Validation[ValidationError, RiskLeaf]
```

**No new infrastructure needed** - the validation layer already supports this!

#### Task 2: Create Referentially Transparent Context-Aware Decoders (2 hours)

**Functional Approach:** Instead of ThreadLocal, explicitly pass path as a parameter through the decoder chain.

**Step 2.1: Add Helper Methods for Context-Aware Decoding**

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`

```scala
object RiskNode {
  /** Functional helper to decode RiskNode with explicit parent path.
    * 
    * This is a pure function - same input always produces same output.
    * No side effects, no ThreadLocal, fully referentially transparent.
    * 
    * @param json The JSON to decode
    * @param parentPath The path to this node from root (e.g., "request.root", "request.root.children[0]")
    * @return Either[String, RiskNode] with errors containing full path
    */
  def decodeWithPath(json: String, parentPath: String): Either[String, RiskNode] = {
    // Parse JSON to determine type
    import zio.json.*
    
    json.fromJson[Json].flatMap { jsonValue =>
      // Extract type discriminator
      jsonValue.hcursor.get[String]("type").flatMap {
        case "leaf" => 
          RiskLeaf.decodeWithPath(json, parentPath)
        case "portfolio" => 
          RiskPortfolio.decodeWithPath(json, parentPath)
        case other => 
          Left(s"Unknown type: $other")
      }
    }
  }
  
  // Default codec unchanged (for backward compatibility)
  given codec: JsonCodec[RiskNode] = DeriveJsonCodec.gen[RiskNode]
  
  given schema: Schema[RiskNode] = Schema.any[RiskNode]
}
```

**Step 2.2: Add RiskLeaf.decodeWithPath (Pure Function)**

```scala
object RiskLeaf {
  // ... existing create() method unchanged ...
  
  /** Functional decoder with explicit path - no side effects.
    * 
    * Pure function: same JSON + path → same result every time.
    * 
    * @param json JSON string to decode
    * @param parentPath Path context (e.g., "request.root.children[0]")
    * @return Either[String, RiskLeaf] with errors including full path
    */
  def decodeWithPath(json: String, parentPath: String): Either[String, RiskLeaf] = {
    // First parse to raw DTO (plain types)
    json.fromJson[RiskLeafRaw].flatMap { raw =>
      // Then validate using smart constructor with explicit path
      create(
        raw.id, raw.name, raw.distributionType, raw.probability,
        raw.percentiles, raw.quantiles, raw.minLoss, raw.maxLoss,
        fieldPrefix = parentPath  // ← Explicit parameter, not ThreadLocal!
      ).toEither.left.map(errors => 
        errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")
      )
    }
  }
  
  // ... existing decoder/encoder/codec unchanged for backward compatibility ...
}
```

**Step 2.3: Add RiskPortfolio.decodeWithPath (Pure Recursive Function)**

```scala
object RiskPortfolio {
  // ... existing create() method unchanged ...
  
  /** Functional decoder with explicit path - recursively decodes children.
    * 
    * Pure function with explicit recursion (no mutation, no ThreadLocal).
    * Each child gets indexed path: "parent.children[0]", "parent.children[1]", etc.
    * 
    * @param json JSON string to decode
    * @param parentPath Path context (e.g., "request.root")
    * @return Either[String, RiskPortfolio] with all nested errors
    */
  def decodeWithPath(json: String, parentPath: String): Either[String, RiskPortfolio] = {
    import zio.json.*
    
    // Parse to get raw fields
    json.fromJson[Json].flatMap { jsonValue =>
      val cursor = jsonValue.hcursor
      
      for {
        // Extract fields
        id <- cursor.get[String]("id")
        name <- cursor.get[String]("name")
        childrenJsonArray <- cursor.get[List[Json]]("children")
        
        // Decode each child with indexed path (pure recursive traversal)
        decodedChildren <- childrenJsonArray.zipWithIndex.traverse { case (childJson, idx) =>
          val childPath = s"$parentPath.children[$idx]"
          val childJsonString = childJson.toString  // Convert back to string for decoding
          RiskNode.decodeWithPath(childJsonString, childPath)
        }
        
        // Validate portfolio-level fields
        validated <- create(id, name, decodedChildren.toArray, fieldPrefix = parentPath)
          .toEither
          .left.map(errors => 
            errors.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")
          )
      } yield validated
    }
  }
  
  // ... existing decoder/encoder/codec unchanged for backward compatibility ...
}
```

**Step 2.4: Update RiskTreeDefinitionRequest to Use Path-Aware Decoding**

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`

```scala
object RiskTreeDefinitionRequest {
  /** Custom codec that uses path-aware decoding for better error messages.
    * 
    * Purely functional - no side effects, no ThreadLocal.
    */
  given codec: JsonCodec[RiskTreeDefinitionRequest] = {
    val decoder: JsonDecoder[RiskTreeDefinitionRequest] = new JsonDecoder[RiskTreeDefinitionRequest] {
      override def unsafeDecode(trace: List[JsonError], in: RetractReader): RiskTreeDefinitionRequest = {
        // Read entire JSON first
        val jsonString = in.toString  // Simplified - actual implementation needs proper reading
        
        // Parse fields manually to use path-aware decoding for root
        import zio.json.*
        
        val result = for {
          json <- jsonString.fromJson[Json]
          cursor = json.hcursor
          
          name <- cursor.get[String]("name")
          nTrials <- cursor.get[Int]("nTrials")
          rootJson <- cursor.get[Json]("root")
          
          // Decode root with explicit path
          root <- RiskNode.decodeWithPath(rootJson.toString, "request.root")
        } yield RiskTreeDefinitionRequest(name, nTrials, root)
        
        result match {
          case Right(req) => req
          case Left(err) => throw new RuntimeException(err)  // ZIO JSON decoder API limitation
        }
      }
    }
    
    // Encoder unchanged
    val encoder = DeriveJsonEncoder.gen[RiskTreeDefinitionRequest]
    
    JsonCodec(encoder, decoder)
  }
  
  // ... existing toDomain() method unchanged ...
}
```

---

## ⚠️ Problem with Above Approach

The ZIO JSON decoder API (`unsafeDecode`) doesn't give us the raw JSON string easily, making the above approach awkward.

---

## ✅ Better Functional Solution: Post-Decode Validation

**Insight:** We don't need to change decoders at all! We can:
1. Decode with existing decoders (works fine)
2. Then **re-validate** the decoded tree with proper paths using `toDomain()`

This is **pure** and **simpler**:

#### **Final Approach: Enhance toDomain() for Recursive Path Validation**

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`

```scala
object RiskTreeDefinitionRequest {
  given codec: JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen[RiskTreeDefinitionRequest]
  
  /** 
   * Validate request with proper field paths for nested structures.
   * 
   * Pure function - no side effects, fully referentially transparent.
   * Recursively validates entire tree with indexed child paths.
   * 
   * @return Validation with all errors including precise nested paths
   */
  def toDomain(req: RiskTreeDefinitionRequest): Validation[ValidationError, (SafeName.SafeName, Int, RiskNode)] = {
    import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
    
    // Validate request-level fields
    val nameV = toValidation(ValidationUtil.refineName(req.name, "request.name"))
    val trialsV = toValidation(ValidationUtil.refinePositiveInt(req.nTrials, "request.nTrials"))
    
    // Re-validate root tree with proper paths (pure recursive function)
    val rootV = validateRiskNodeWithPath(req.root, "request.root")
    
    // Combine all validations
    Validation.validateWith(nameV, trialsV, rootV) { (name, trials, root) =>
      (name, trials, root)
    }
  }
  
  /** Pure recursive validation of RiskNode tree with path tracking.
    * 
    * This is a pure function - same node + path → same result.
    * No mutation, no side effects, referentially transparent.
    * 
    * @param node Already-decoded RiskNode (from JSON decoder)
    * @param path Current path in tree (e.g., "request.root.children[0]")
    * @return Validation with errors including full paths
    */
  private def validateRiskNodeWithPath(
    node: RiskNode,
    path: String
  ): Validation[ValidationError, RiskNode] = {
    node match {
      case leaf: RiskLeaf =>
        // Re-validate leaf with proper path
        RiskLeaf.create(
          leaf.id,
          leaf.name,
          leaf.distributionType.toString,
          leaf.probability,
          leaf.percentiles,
          leaf.quantiles,
          leaf.minLoss.map(identity),
          leaf.maxLoss.map(identity),
          fieldPrefix = path
        )
        
      case portfolio: RiskPortfolio =>
        // Recursively validate each child with indexed path
        val childrenV: Validation[ValidationError, Array[RiskNode]] = 
          portfolio.children.zipWithIndex.foldLeft(
            Validation.succeed(Array.empty[RiskNode])
          ) { case (accV, (child, idx)) =>
            val childPath = s"$path.children[$idx]"
            val childV = validateRiskNodeWithPath(child, childPath)
            
            // Accumulate validated children (pure functional fold)
            Validation.validateWith(accV, childV) { (acc, validChild) =>
              acc :+ validChild
            }
          }
        
        // Then validate portfolio-level fields
        childrenV.flatMap { validChildren =>
          RiskPortfolio.create(
            portfolio.id,
            portfolio.name,
            validChildren,
            fieldPrefix = path
          )
        }
    }
  }
}
```

**Why This is Better:**
1. ✅ **Fully referentially transparent** - pure functions only
2. ✅ **No ThreadLocal** - explicit parameter passing
3. ✅ **Simple** - reuses existing decoders and smart constructors
4. ✅ **Composable** - recursive validation is just function calls
5. ✅ **Already validated once** - JSON decoder ensures valid structure, we just add better paths

**Trade-off:**
- Validates twice (JSON decode + re-validate with paths)
- But validation is fast and this gives us perfect error messages

This is the **proper FP solution**!

**Step 2.1: Update RiskLeaf Decoder**

❌ **REMOVED** - Not needed with post-decode validation approach.

**Step 2.2: Update RiskPortfolio Decoder**

❌ **REMOVED** - Not needed with post-decode validation approach.

**Step 2.3: Update RiskTreeDefinitionRequest to Initialize Context**

❌ **REMOVED** - Not needed with post-decode validation approach.
```

---

## 🧪 Testing Strategy (0.5 hours)

### Test 1: Single Leaf Error Path

**Test Method:**
```scala
test("Single leaf with invalid id shows proper path via toDomain") {
  val req = RiskTreeDefinitionRequest(
    name = "Test",
    nTrials = 10000,
    root = RiskLeaf.unsafeApply(  // Use unsafe to create invalid leaf
      id = "x",  // Invalid
      name = "Test",
      distributionType = "lognormal",
      probability = 0.3,
      minLoss = Some(1000L),
      maxLoss = Some(5000L)
    )
  )
  
  val result = RiskTreeDefinitionRequest.toDomain(req)
  
  assertTrue(
    result.isFailure,
    result.toEither.left.map(_.exists(_.field == "request.root.id")).getOrElse(false)
  )
}
```

**Expected Error:**
```
[request.root.id] Must be at least 3 characters
```

### Test 2: Nested Portfolio Error Paths

**Test Method:**
```scala
test("Nested portfolio shows indexed child paths") {
  val invalidLeaf = RiskLeaf.unsafeApply(
    id = "y",  // Invalid
    name = "Test",
    distributionType = "invalid",  // Invalid
    probability = 1.5,  // Invalid
    minLoss = Some(1000L),
    maxLoss = Some(5000L)
  )
  
  val childPortfolio = RiskPortfolio.unsafeApply(
    id = "child-port",
    name = "Child",
    children = Array(invalidLeaf)
  )
  
  val rootPortfolio = RiskPortfolio.unsafeApply(
    id = "root-port",
    name = "Root",
    children = Array(
      RiskLeaf.unsafeApply(...),  // Valid leaf
      childPortfolio
    )
  )
  
  val req = RiskTreeDefinitionRequest("Test", 10000, rootPortfolio)
  val result = RiskTreeDefinitionRequest.toDomain(req)
  
  assertTrue(
    result.isFailure,
    result.toEither.left.map { errors =>
      errors.exists(_.field.contains("request.root.children[1].children[0].id")) &&
      errors.exists(_.field.contains("request.root.children[1].children[0].distributionType"))
    }.getOrElse(false)
  )
}
```

**Expected Errors:**
```
[request.root.children[1].children[0].id] Must be at least 3 characters
[request.root.children[1].children[0].distributionType] Must be 'expert' or 'lognormal'
[request.root.children[1].children[0].probability] Value must be less than 1.0
```

### Test 3: Verify Referential Transparency

**Test Method:**
```scala
test("toDomain is referentially transparent - same input always gives same output") {
  val req = RiskTreeDefinitionRequest(
    name = "Test",
    nTrials = 10000,
    root = RiskLeaf.unsafeApply(
      id = "x",
      name = "Test",
      distributionType = "lognormal",
      probability = 0.3,
      minLoss = Some(1000L),
      maxLoss = Some(5000L)
    )
  )
  
  // Call toDomain multiple times
  val result1 = RiskTreeDefinitionRequest.toDomain(req)
  val result2 = RiskTreeDefinitionRequest.toDomain(req)
  val result3 = RiskTreeDefinitionRequest.toDomain(req)
  
  // All results should be identical (referential transparency)
  assertTrue(
    result1 == result2,
    result2 == result3,
    result1.isFailure
  )
}
```

### Test File

**File:** `modules/common/src/test/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequestSpec.scala`

Add new test suite: "Field Path Enhancement" with above tests.

---

## 📚 Documentation Updates (0.5 hours)

### Task 3: Update Architecture Documentation

**File:** `docs/DTO_DOMAIN_SEPARATION_DESIGN.md`

**Section to Add: "Field Path Enhancement (January 2026)"**

```markdown
### Field Path Enhancement (Implemented January 7, 2026)

**Problem Solved:**
Original error paths used id-based format: `riskLeaf[id=cyber].probability`
Enhanced to show full tree structure: `request.root.children[0].probability`

**Implementation:**
- Added `DecodingContext` with thread-local tracking
- Modified decoders to read current context path
- Portfolio decoder sets indexed context for each child
- RiskTreeDefinitionRequest initializes context as "request.root"

**Benefits:**
- Precise error locations in deeply nested structures
- Easier to debug validation failures
- Better UX for API consumers

**Example Error Message:**
```json
{
  "error": "Validation failed",
  "errors": [
    {
      "field": "request.root.children[1].children[0].probability",
      "code": "FIELD_OUT_OF_RANGE",
      "message": "Value 1.5 must be less than 1.0"
    }
  ]
}
```

**Trade-offs:**
- Thread-local adds minimal overhead (acceptable for validation)
- More complex decoder logic (but cleaner error messages)
```

### Task 4: Update API Documentation

**File:** `docs/API_VALIDATION_ERRORS.md` (NEW)

Create comprehensive guide for API consumers explaining error message format.

```markdown
# API Validation Error Reference

## Error Response Format

All validation errors follow this structure:

```json
{
  "error": "Validation failed",
  "errors": [
    {
      "field": "request.root.children[0].probability",
      "code": "FIELD_OUT_OF_RANGE",
      "message": "Value must be less than 1.0"
    }
  ]
}
```

## Field Path Format

### Nested Structure Paths

Field paths show the exact location in your JSON structure:

**Example 1: Direct field error**
```
"field": "request.root.id"
```
Meaning: The `id` field in your root risk node is invalid.

**Example 2: Nested portfolio error**
```
"field": "request.root.children[0].probability"
```
Meaning: The first child of your root portfolio has an invalid `probability` field.

**Example 3: Deeply nested error**
```
"field": "request.root.children[1].children[0].minLoss"
```
Meaning: The second child of root is a portfolio, and its first child has an invalid `minLoss` field.

### Path Components

- `request.root` - The top-level risk tree
- `.children[N]` - The Nth child in a portfolio (0-indexed)
- `.fieldName` - A specific field (id, name, probability, etc.)

## Common Validation Errors

[... detailed error code reference ...]
```

### Task 5: Update IMPLEMENTATION_PLAN.md

Mark Phase 2 as 100% complete with field path enhancement details.

---

## 📦 Summary

**Total Time:** 2-2.5 hours

**Functional Programming Approach:**
✅ **Referentially transparent** - Pure functions only, no side effects
✅ **No ThreadLocal** - Explicit parameter passing through recursion
✅ **Composable** - Recursive validation using pure folds
✅ **Simple** - Post-decode validation, no decoder changes needed
✅ **Testable** - Same input always produces same output

**Tasks:**
1. ✅ Enhance `RiskTreeDefinitionRequest.toDomain()` with recursive path validation (1.5 hours)
2. ✅ Add comprehensive tests including referential transparency test (0.5 hours)
3. ✅ Update documentation (0.5 hours)

**Key Insight:**
Instead of mutating decoders with ThreadLocal (impure), we re-validate the already-decoded tree with proper paths in `toDomain()`. This is:
- Pure (referentially transparent)
- Simple (reuses existing smart constructors)
- Efficient enough (validation is fast)

**Outcome:**
- Phase 2 moves from 90% → 100% complete
- Error messages show full hierarchical paths
- **Fully functional** - no side effects, no mutation, referentially transparent
- Better debugging experience for API consumers
- Documentation complete with examples

**Risk:** Low - Enhancement is additive, purely functional, existing tests verify no regressions.
