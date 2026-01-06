# Development Notes

## Outstanding Items

### ID Generation Strategy - Pending Persistence

**Status**: Open (deferred until persistence layer is implemented)

#### Current Implementation
IDs are provided explicitly in the hierarchical API format. The system validates that:
- IDs are 3-30 characters
- IDs contain only alphanumeric, underscore, or hyphen characters

#### Future Considerations
When persistence is added:
1. **Relational DB**: Consider DB-generated sequences for guaranteed uniqueness
2. **Document DB**: Current explicit IDs may be sufficient with UUID fallback
3. **Hybrid**: DB-generated IDs with user-friendly slug stored separately

#### Related Files
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/SafeId.scala`

---

## Completed Work (Historical Reference)

### ✅ Iron Refinement Type Migration (2026-01-06)

**Status**: COMPLETE

#### Implementation Summary
- **Option A Architecture**: Public API returns `String`, internal storage uses Iron types
- **Smart Constructors**: `RiskLeaf.create()`, `RiskPortfolio.create()` return `Validation[String, T]`
- **Private Constructors**: `final case class RiskLeaf private (...)`
- **Error Accumulation**: `Validation.validateWith()` collects all errors in parallel

#### What Was Achieved
1. Domain model uses Iron types internally (`SafeId.SafeId`, `SafeName.SafeName`, etc.)
2. Smart constructors enforce all validation at construction time
3. Public API uses clean `String` types via accessor methods
4. JSON codecs work seamlessly with `@jsonField` annotations
5. 401 tests passing (280 common + 121 server)

### ✅ Priority 1 Cleanup (2026-01-06)

**Status**: COMPLETE

1. ✅ Removed redundant `validateRequest()` from `RiskTreeServiceLive.scala`
2. ✅ Updated test cases to use smart constructors instead of `unsafeApply()`
3. ✅ Removed flat format support entirely (was deprecated)
4. ✅ Added validation error accumulation tests

---

## Architecture Decisions

### Why Option A (Public String, Internal Iron)?

**Rationale:**
1. **Clean API**: Users see `String` fields, not `SafeId.SafeId` opaque types
2. **Type Safety**: Internal code gets compile-time guarantees
3. **JSON Simplicity**: No custom codecs needed for Iron types
4. **Validation Boundary**: Validation happens once at construction, domain objects are always valid

**Pattern:**
```scala
// Domain model
final case class RiskLeaf private (
  @jsonField("id") safeId: SafeId.SafeId,    // Internal: Iron type
  @jsonField("name") safeName: SafeName.SafeName
) extends RiskNode {
  def id: String = safeId.toString            // Public: String
  def name: String = safeName.toString
}

// Smart constructor
object RiskLeaf {
  def create(...): Validation[String, RiskLeaf] = 
    Validation.validateWith(...)(new RiskLeaf(_, _))
}
```

### Why ZIO Prelude Validation?

**Rationale:**
1. **Error Accumulation**: Collects ALL validation errors, not just the first
2. **Parallel Composition**: `Validation.validateWith()` validates fields in parallel
3. **Type Safety**: Compiler ensures all validation paths are handled
4. **ZIO Integration**: Easy conversion to `Task` via `toEitherWith()`

**Pattern:**
```scala
def create(...): Validation[String, RiskLeaf] = {
  Validation.validateWith(
    SafeId.fromString(id).toValidation,
    SafeName.fromString(name).toValidation,
    Probability.fromDouble(probability).toValidation
  )(new RiskLeaf(_, _, _))
}
```
