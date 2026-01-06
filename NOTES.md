# Development Notes

## ID Generation Strategy - Pending Review

**Date**: 2026-01-06

### Current Implementation
The flat format (backward-compatible API) currently uses **generated IDs** from names:
- Pattern: `generateIdFromName(name: String, index: Int): String`
- Example: "Test Risk" → "test-risk-0"
- Location: `RiskTreeServiceLive.scala`

### Issue
**This approach needs review once persistence is implemented.**

#### Options:
1. **Continue with generated IDs**: Keep deterministic name-based generation
   - Pros: Deterministic, semantic, works without DB
   - Cons: Potential collisions if same name used multiple times across sessions

2. **Use DB sequence**: Generate IDs from database sequence
   - Pros: Guaranteed uniqueness across all data, standard enterprise pattern
   - Cons: Requires DB connection, less semantic, harder to debug

3. **Hybrid**: Use DB-generated IDs but store name-based slug for reference
   - Pros: Best of both worlds
   - Cons: More complex

### Recommendation
Wait until persistence layer is implemented, then:
- If using relational DB: Prefer DB sequences (auto-increment or sequence)
- If using document DB: Current approach may be sufficient with UUID fallback
- Consider making flat format truly deprecated once hierarchical format is stable

### Related Files
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`

---

## Critical Review: Iron Refinement Type Migration

**Date**: 2026-01-06

### Original Goal
Migrate domain model to use Iron refinement types for compile-time safety, following the pattern:
- **Option A (Selected)**: Public API returns `String`, internal storage uses Iron types (`SafeId`, `SafeName`)
- Service layer uses smart constructors (`create()`) instead of unsafe `unsafeApply()`
- Validation at service boundary, domain objects always valid by construction

### Work Completed

#### ✅ Achievements
1. **Domain Model Refactoring** (`RiskNode.scala`):
   - ✅ Implemented Option A architecture correctly
   - ✅ Added `@jsonField` annotations for JSON field mapping
   - ✅ Smart constructors `create()` with comprehensive validation
   - ✅ Public API returns clean `String` types
   - ✅ Internal storage uses Iron types (`safeId: SafeId.SafeId`, `safeName: SafeName.SafeName`)
   - ✅ Proper JSON codec generation with DeriveJsonCodec
   - ✅ Cross-field validation (e.g., minLoss < maxLoss, mode-specific parameters)

2. **Service Layer Migration** (`RiskTreeServiceLive.scala`):
   - ✅ Refactored `buildRiskNodeFromRequest()` to use smart constructors
   - ✅ Changed return type to `Task[RiskNode]` to handle validation errors
   - ✅ Proper error propagation: `Validation[String, T]` → `ZIO.fromEither` → `Task`
   - ✅ Implemented `generateIdFromName()` for flat format backward compatibility
   - ✅ All validation errors properly accumulated and reported

3. **Testing**:
   - ✅ All 399 tests passing (276 common + 123 server)
   - ✅ JSON serialization tests updated for new field names
   - ✅ Service validation tests cover error cases

### Critical Assessment

#### ✅ What Worked Well (Idiomatic & Optimal)

1. **Option A Architecture**: Excellent choice
   - Clean separation: public String API + internal Iron types
   - `@jsonField` annotations elegantly solve the naming problem
   - Follows Scala best practices: private constructors, smart constructors
   - No JSON codec for Iron types needed—works seamlessly

2. **Validation Pattern**: Textbook ZIO Prelude
   - `Validation[String, T]` accumulates all errors in parallel
   - Proper conversion to ZIO: `ZIO.fromEither(validation.toEitherWith(...))`
   - Business rule validation integrated cleanly (mode-specific parameters)
   - Error messages are descriptive and actionable

3. **Smart Constructors**: Idiomatic Scala 3
   - `create()` methods follow conventional naming
   - Private case class constructors enforce validation
   - `unsafeApply()` marked as temporary with TODO comments
   - Validation logic centralized in companion objects

4. **ID Generation Strategy**: Pragmatic solution
   - Deterministic: same input → same output (testability)
   - Semantic: preserves name meaning ("Test Risk" → "test-risk-0")
   - Unique: index suffix prevents collisions within request
   - Well-documented with clear use case explanation

#### ⚠️ Areas of Concern (Non-Optimal)

1. **`unsafeApply()` Still in Production Code**:
   - **Issue**: 37+ usages remain in test code
   - **Why**: Tests use `unsafeApply()` directly for setup
   - **Impact**: Bypasses validation, tests don't exercise smart constructor code path
   - **Recommendation**: 
     ```scala
     // Replace in tests:
     RiskLeaf.unsafeApply("id", "name", ...) 
     // With:
     RiskLeaf.create("id", "name", ...).toEither.getOrElse(fail("Invalid test data"))
     ```

2. **Redundant Validation in Service Layer**:
   - **Issue**: `validateRequest()` duplicates smart constructor validation
   - **Location**: Lines 138-167 in `RiskTreeServiceLive.scala`
   - **Why**: Historical—existed before smart constructors
   - **Impact**: Maintenance burden, validation logic in two places
   - **Evidence**: TODO comments acknowledge this: "TODO Step 3.1: Remove validateRequest entirely"
   - **Recommendation**: Delete `validateRequest()`, rely solely on smart constructors

3. **ID Generation Belongs in Domain, Not Service**:
   - **Issue**: `generateIdFromName()` is service concern, but logic feels domain-like
   - **Current**: `RiskTreeServiceLive.scala` (line 170-188)
   - **Concern**: When persistence is added, ID generation will need coordination
   - **Not necessarily wrong**: Service orchestration is appropriate here
   - **Recommendation**: Keep as-is until persistence layer clarifies requirements

4. **Flat Format Deprecation Incomplete**:
   - **Issue**: Flat format marked "deprecated" but fully supported
   - **Impact**: Users may continue using it, increasing maintenance burden
   - **Recommendation**: Either:
     - Add deprecation warning in API response
     - Remove flat format entirely (breaking change)
     - Accept it as a permanent convenience feature

#### ❌ Potential Issues

1. **Test Coverage Gap**:
   - **Issue**: Tests use `unsafeApply()`, don't validate smart constructor behavior
   - **Missing**: Tests that verify accumulated error messages
   - **Missing**: Tests that verify cross-field validation (minLoss < maxLoss)
   - **Recommendation**: Add test cases like:
     ```scala
     test("RiskLeaf.create accumulates multiple validation errors") {
       val result = RiskLeaf.create("", "", "invalid", -1.0, None, None, None, None)
       assertTrue(result.isFailure)
       // Verify all 4 errors are accumulated
     }
     ```

2. **Documentation Gap**:
   - **Issue**: No migration guide for users of the API
   - **Impact**: Users may be confused by field name changes (`_id` → `id`)
   - **Recommendation**: Add to API_EXAMPLES.md with before/after examples

### Conclusion: Did We Achieve the Goal?

**Yes, with caveats:**

✅ **Primary Goal Achieved**: Domain model uses Iron types correctly
✅ **Architecture**: Option A implemented idiomatically  
✅ **Service Layer**: Smart constructors integrated successfully  
✅ **Tests**: All passing, no regressions  

⚠️ **Incomplete**:
- `unsafeApply()` still exists (37+ usages in tests)
- Redundant validation in service layer
- Test coverage doesn't exercise smart constructors

🎯 **Optimal Solution?**
- **90% there**: Core architecture is excellent
- **Missing 10%**: Complete test migration, cleanup redundant validation

### Recommendations for Completion

**Priority 1 (Before Persistence)**:
1. Remove `validateRequest()` from `RiskTreeServiceLive.scala`
2. Update 5-10 key tests to use `create()` instead of `unsafeApply()`
3. Add validation error accumulation tests

**Priority 2 (Nice to Have)**:
1. Migrate all 37 test usages to smart constructors
2. Add deprecation warnings to flat format
3. Document ID generation strategy in API_EXAMPLES.md

**Priority 3 (When Adding Persistence)**:
1. Re-evaluate ID generation strategy (DB sequence vs. generated)
2. Consider removing flat format entirely
3. Add integration tests for persistence validation

---
