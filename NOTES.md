# Development Notes

## Outstanding Items

### ID Generation Strategy ✅

**Status**: Complete — ULIDs used for both tree and node IDs.

#### Current Implementation
- **Tree IDs**: `TreeId(toSafeId: SafeId.SafeId)` — server-generated ULID via `IdGenerators.nextTreeId`.
- **Node IDs**: `NodeId(toSafeId: SafeId.SafeId)` — server-generated ULID via `IdGenerators.nextNodeId`.
- IDs are 26-character Crockford base32 ULIDs, canonical uppercase.
- Client-supplied tree IDs are structurally impossible (`RiskTreeDefinitionRequest` has no `id` field).
- `ensureUniqueTree` enforces tree name uniqueness.

#### Related Files
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/SafeId.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/NodeId.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/TreeId.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/IdGenerators.scala`

---

### Startup Irmin health check should retry, not fail-fast ✅

**Status**: Complete — bounded, fail-closed readiness gate implemented (2026-07-09).

**Problem**: `Application.irminHealthCheck` failed the whole startup on the first
`false` health check and the process self-terminated → Kubernetes `CrashLoopBackOff`.
On the k8s bootstrap this fired whenever register started before its network path to
irmin was ready — i.e. before the mesh (Istio ambient) NetworkPolicy/HBONE rules for
`register → irmin:8080` had been applied by ArgoCD.

**Resolution**: New `StartupReadiness.awaitReady` gate — jittered exponential backoff
capped at 5s, bounded by a total elapsed-time budget (`IrminConfig.healthCheckBudget`,
default 45s), fail-closed after the budget. `IrminClient.healthCheck` now returns a
typed error (not `Boolean`), so the final failure carries the real cause instead of a
generic "returned false". Governed by new
[ADR-031](docs/dev/ADR-031-startup-readiness-vs-request-path-resilience.md), which
draws the boundary between this (app-owned startup lifecycle gating) and request-path
resilience (mesh-owned, ADR-012 §4). Verified: bounded fail-closed exit when irmin is
absent for the whole budget, and clean recovery (server boots) when irmin becomes
reachable mid-window.

**Related**: `modules/server/.../infra/StartupReadiness.scala`,
`modules/server/.../Application.scala` (`irminHealthCheck`),
`modules/server/.../configs/IrminConfig.scala`,
`docs/dev/ADR-031-startup-readiness-vs-request-path-resilience.md`.

---

## Completed Work (Historical Reference)

### ✅ Error Handling & Typed Error Codes (2026-01-06/07)

**Status**: COMPLETE

#### Implementation Summary
- **Typed ValidationErrorCode**: Enum with 15 codes in SCREAMING_SNAKE_CASE
- **Structured ValidationError**: `case class ValidationError(field: String, code: ValidationErrorCode, message: String)`
- **Field Path Context**: All smart constructors accept `fieldPrefix` parameter
- **Error Accumulation**: All validation methods return `Either[List[ValidationError], T]`
- **BuildInfo Integration**: Version management via sbt-buildinfo
- **Naming Improvements**: `CreateSimulationRequest` → `RiskTreeDefinitionRequest`
- **Domain Standardization**: Error domain changed to "risk-trees"

#### What Was Achieved
1. All validation methods preserve structured error information through the call chain
2. Field paths built with dot notation: `root.id`, `children[0].probability`
3. ValidationUtil methods integrated with typed codes: `REQUIRED_FIELD`, `INVALID_RANGE`, `INVALID_PATTERN`
4. ErrorResponse helpers use typed codes for API responses
5. BuildInfo eliminates hardcoded version strings
6. 508 tests passing (289 common + 219 server)

### ✅ Iron Refinement Type Migration (2026-01-06)

**Status**: COMPLETE

#### Implementation Summary
- **Option A Architecture**: Public API returns `String`, internal storage uses Iron types
- **Smart Constructors**: `RiskLeaf.create()`, `RiskPortfolio.create()` return `Validation[ValidationError, T]`
- **Private Constructors**: `final case class RiskLeaf private (...)`
- **Error Accumulation**: `Validation.validateWith()` collects all errors in parallel

#### What Was Achieved
1. Domain model uses Iron types internally (`SafeId.SafeId`, `SafeName.SafeName`, etc.)
2. Smart constructors enforce all validation at construction time
3. Public API uses clean `String` types via accessor methods
4. JSON codecs work seamlessly with `@jsonField` annotations

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
  nodeId: NodeId,                              // Internal: nominal wrapper around SafeId
  @jsonField("name") safeName: SafeName.SafeName
) extends RiskNode {
  def id: NodeId = nodeId                      // Public: NodeId (ADR-018)
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
