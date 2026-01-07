# Field Path Enhancement - Architecture Decision

**Date:** 2026-01-07  
**Status:** Investigation Required  
**Context:** Phase 2 Enhancement - Improving nested field path tracking in validation errors

---

## Problem Statement

### Current Behavior
Validation errors show generic paths that don't reflect nested structure:

```json
{
  "errors": [
    "Error: [riskLeaf[id=cyber].probability] Value must be less than 1.0",
    "Error: [riskPortfolio[id=root].children] Validation failed"
  ]
}
```

### Desired Behavior
Validation errors should show full hierarchical paths with indices:

```json
{
  "errors": [
    "Error: [root.children[0].probability] Value must be less than 1.0",
    "Error: [root.children[1].children[0].id] Must be at least 3 characters"
  ]
}
```

---

## Root Cause Analysis

### Current Architecture

```scala
// RiskPortfolio decoder - HARDCODED fieldPrefix
given decoder: JsonDecoder[RiskPortfolio] = RiskPortfolioRaw.rawCodec.decoder.mapOrFail { raw =>
  create(
    raw.id, 
    raw.name, 
    raw.children, 
    fieldPrefix = s"riskPortfolio[id=${raw.id}]"  // ← PROBLEM: No parent context!
  ).toEither.left.map(errors => ...)
}

// Smart constructor already supports fieldPrefix parameter
def create(
  id: String,
  name: String,
  children: Array[RiskNode],
  fieldPrefix: String = "root"  // ← Parameter exists but not threaded through recursion
): Validation[ValidationError, RiskPortfolio] = {
  val idValidation = toValidation(ValidationUtil.refineId(id, s"$fieldPrefix.id"))
  val nameValidation = toValidation(ValidationUtil.refineName(name, s"$fieldPrefix.name"))
  // ...
}
```

**The Issue:** During recursive JSON parsing, each decoder constructs its own `fieldPrefix` without knowing:
- Its position in the parent's children array (index)
- The full path from root to current node

---

## Rejected Approach: Post-Decode Re-Validation

### What Was Proposed
```scala
// After JSON decoding succeeds, walk the tree again and re-validate
def validateRiskNodeWithPath(
  node: RiskNode,
  path: String
): Validation[ValidationError, RiskNode] = {
  node match {
    case portfolio: RiskPortfolio =>
      portfolio.children.zipWithIndex.foldLeft(...) { case (accV, (child, idx)) =>
        val childPath = s"$path.children[$idx]"
        validateRiskNodeWithPath(child, childPath)  // Recursive re-validation
      }
  }
}
```

### Why Rejected
1. **Over-complicated:** Validates the tree twice (decode + re-validate)
2. **Inefficient:** Redundant work - smart constructors already validated during decode
3. **Misses the point:** The real issue is decoder not having parent context, not validation logic

---

## Preferred Approach: Thread Path Through Decoder

### Concept
Pass parent path as parameter through the decoder recursion, just like functional composition:

```
root 
  → root.children[0] 
    → root.children[0].children[0]
    → root.children[0].children[1]
  → root.children[1]
    → root.children[1].children[0]
```

Each decoder receives its path: `(parentPath) + currentField + [index]`

### Proposed Implementation Options

#### Option 1: Parameterized Decoder
```scala
object RiskPortfolio {
  // Decoder factory that accepts parent path
  def decoderWithPath(parentPath: String): JsonDecoder[RiskPortfolio] = 
    RiskPortfolioRaw.rawCodec.decoder.mapOrFail { raw =>
      val myPath = s"$parentPath"  // Current node path
      
      // Decode children with indexed paths
      val childrenWithPaths = raw.children.zipWithIndex.map { case (child, idx) =>
        val childPath = s"$myPath.children[$idx]"
        // Recursively decode child with its path
        decodeChildWithPath(child, childPath)
      }
      
      create(raw.id, raw.name, childrenWithPaths, fieldPrefix = myPath)
    }
}
```

**Challenges:**
- `given` decoders in Scala 3 cannot have parameters
- Need to manually control child decoding instead of relying on derived codec
- May need custom JSON traversal logic

#### Option 2: Decoder Context (ZIO JSON Feature Investigation)
```scala
// Does ZIO JSON expose parsing context?
given decoder: JsonDecoder[RiskPortfolio] = JsonDecoder.custom { cursor =>
  val currentPath = cursor.path  // ← Does this exist?
  // Decode using path from cursor
}
```

**Investigation Needed:**
- Does `zio-json` expose current JSON path during parsing?
- Can we access cursor/parser position?
- Are there hooks for tracking position during recursive descent?

#### Option 3: Custom JSON Traversal
```scala
// Manual JSON parsing with explicit path tracking
def parseRiskNodeWithPath(
  json: Json,
  path: String
): Either[String, RiskNode] = {
  json.asObject match {
    case Some(obj) if obj.contains("children") =>
      // It's a portfolio
      val children = obj("children").asArray.zipWithIndex.map { case (child, idx) =>
        parseRiskNodeWithPath(child, s"$path.children[$idx]")
      }
      RiskPortfolio.create(..., fieldPrefix = path)
      
    case Some(obj) =>
      // It's a leaf
      RiskLeaf.create(..., fieldPrefix = path)
  }
}
```

**Challenges:**
- Bypasses ZIO JSON's derivation - manual parsing
- More code to maintain
- Loses type safety from derived codecs

---

## Decision Required

### Investigation Tasks

1. **Research ZIO JSON API:**
   - Does `JsonDecoder` support parameterization?
   - Can we access parsing context/cursor during decoding?
   - Are there examples of position-aware decoders?

2. **Prototype Simplest Solution:**
   - If ZIO JSON supports context: Use it
   - If not: Evaluate manual traversal vs parameterized decoder

3. **Validate Approach:**
   - Must be referentially transparent (pure functional)
   - Should not require double validation
   - Must work with recursive structures

### Success Criteria

✅ Validation errors show full path: `root.children[0].children[1].probability`  
✅ No re-validation (decode once, validate once)  
✅ Referentially transparent (no ThreadLocal, no mutable state)  
✅ Works with arbitrary nesting depth  
✅ Minimal changes to existing smart constructors  

### Next Steps

1. **Investigate ZIO JSON capabilities** - Check documentation and source code
2. **Create proof-of-concept** - Test simplest approach with small example
3. **Update implementation plan** - Document chosen approach
4. **Implement solution** - Apply to RiskLeaf and RiskPortfolio decoders

---

## References

- **Current Implementation:** `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- **Smart Constructors:** `RiskLeaf.create()`, `RiskPortfolio.create()` - Already support `fieldPrefix` parameter
- **Tests:** `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafSpec.scala` - Field path tests passing
- **ZIO JSON:** https://zio.dev/zio-json/

---

## Notes

- **Functional Requirement:** Solution MUST be referentially transparent
- **Performance:** Negligible impact expected (validation is fast)
- **User Experience:** Better error messages significantly improve API usability
- **Phase Status:** This is the final 10% of Phase 2 (DTO/Domain Separation)
