# Code Quality Review - 2026-01-20

**Scope:** TreeIndex/RiskTree validation refactoring  
**Reviewer:** AI Assistant  
**Status:** Partial completion — Issue 1 open; Issues 2 & 5 resolved; Issue 3 pending service adoption (Issue 4 & 6 deferred)

---

## Summary

| Severity | Count | Category |
|----------|-------|----------|
| ⚠️ Medium | 3 | FP Style Violations |
| 💡 Low | 3 | API Consistency |

---

## Issues

### Issue 1: Imperative Error Collection Pattern

**File:** [TreeIndex.scala](../modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala)  
**Lines:** 156-200

**Violation:** ADR-010 recommends applicative composition for validation. Current implementation uses imperative list-building with `flatMap` + `match` + `if/else`.

**Current Code:**
```scala
// Lines 156-175
val childToParentErrors: List[ValidationError] = children.toList.flatMap { case (parentId, childIds) =>
  childIds.flatMap { childId =>
    nodes.get(childId) match {
      case Some(child) if !child.parentId.contains(parentId) =>
        Some(ValidationError(...))
      case None =>
        Some(ValidationError(...))
      case _ => None
    }
  }
}

// Lines 196-200
if (allErrors.isEmpty) {
  Validation.succeed(TreeIndex(nodes, parents, children))
} else {
  Validation.failNonEmptyChunk(NonEmptyChunk.fromIterable(allErrors.head, allErrors.tail))
}
```

**Suggested Treatment:**
```scala
def fromNodes(nodes: Map[NodeId, RiskNode]): Validation[ValidationError, TreeIndex] = {
  val parents = buildParents(nodes)
  val children = buildChildren(nodes)
  
  val childToParentV = children.toList.traverse { case (parentId, childIds) =>
    childIds.traverse(childId => validateChildToParent(nodes, parentId, childId))
  }
  
  val parentToChildV = parents.toList.traverse { case (nodeId, parentId) =>
    validateParentToChild(nodes, nodeId, parentId)
  }
  
  (childToParentV *> parentToChildV).as(TreeIndex(nodes, parents, children))
}

private def validateChildToParent(...): Validation[ValidationError, Unit] = ...
private def validateParentToChild(...): Validation[ValidationError, Unit] = ...
```

**Priority:** Medium  
**Effort:** 1 hour

---

### Issue 2: Inconsistent Validation Return Types

**Files:**  
- [RiskTreeDefinitionRequest.scala](../modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala) Lines 51, 85
- [TreeIndex.scala](../modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala) Line 133

**Violation:** ADR-010 specifies `Validation[ValidationError, A]` at domain layer. Mixed usage creates conversion overhead.

| Method | Returns | Should Be |
|--------|---------|-----------|
| `TreeIndex.fromNodes` (L133) | `Validation[ValidationError, TreeIndex]` | ✅ Correct |
| `RiskTreeDefinitionRequest.toDomain` (L51) | `Validation[ValidationError, ...]` | ✅ Correct |
| `RiskTreeDefinitionRequest.validate` (L85) | `Either[List[ValidationError], ...]` | ❌ Should be `Validation` |

**Current Code (L85-97):**
```scala
def validate(req: RiskTreeDefinitionRequest): Either[List[ValidationError], (SafeName.SafeName, Seq[RiskNode], NodeId)] = {
  val basicValidation = toDomain(req)
  basicValidation.toEither.left.map(_.toList).flatMap { ... }
}
```

**Suggested Treatment:**
```scala
def validate(req: RiskTreeDefinitionRequest): Validation[ValidationError, (SafeName.SafeName, Seq[RiskNode], NodeId)] = {
  toDomain(req).flatMap { case (name, nodes, rootId) =>
    val nodeIds = nodes.map(_.id).toSet
    Validation.fromPredicateWith(
      ValidationError("request.rootId", CONSTRAINT_VIOLATION, s"rootId '${rootId.value}' not found")
    )(nodeIds.contains(rootId))(identity).as((name, nodes, rootId))
  }
}
```

**Priority:** Low  
**Effort:** 30 minutes

**Status:** ✅ Implemented (2026-01-21). `RiskTreeDefinitionRequest.validate` now returns `Validation`; service/controller paths consume it.

---

### Issue 3: Verbose ZIO.fromEither Conversion

**File:** [RiskTreeServiceLive.scala](../modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala)  
**Lines:** 187-194, 217-219

**Violation:** Repeated `.toEither.left.map(errors => ValidationFailed(errors.toList))` pattern is verbose and error-prone.

**Current Code (L187-194):**
```scala
riskTree <- ZIO.fromEither(
  RiskTree.fromNodes(
    id = 0L.refineUnsafe,
    name = safeName,
    nodes = nodes,
    rootId = rootId
  ).toEither.left.map(errors => ValidationFailed(errors.toList))
)
```

**Suggested Treatment:**

Add extension in `package.scala` or `ValidationExtensions.scala`:
```scala
extension [E, A](v: Validation[E, A])
  def toZIOValidation: IO[ValidationFailed, A] =
    ZIO.fromEither(v.toEither.left.map(errors => ValidationFailed(errors.toList)))
```

Then usage becomes:
```scala
riskTree <- RiskTree.fromNodes(id, name, nodes, rootId).toZIOValidation
```

**Status:** 🚧 Partially applied. Extension `ValidationExtensions.toZIOValidation` exists, but `RiskTreeServiceLive` still uses `ZIO.fromEither(...ValidationFailed(errors.toList))` in create/update (L184-216). Adoption pending.

**Priority:** Low  
**Effort:** 20 minutes

---

### Issue 4: require() in Identity.combine (Partial Function)

**File:** [LossDistribution.scala](../modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala)  
**Line:** 131

**Violation:** ADR-010 states "Errors are values, not exceptions". The `combine` operation uses `require()` making it partial.

**Current Code (L130-132):**
```scala
def combine(a: => RiskResult, b: => RiskResult): RiskResult = {
  require(a.nTrials == b.nTrials, s"Cannot merge results with different trial counts: ${a.nTrials} vs ${b.nTrials}")
```

**Note:** This is an **intentional design decision** per ADR-009. The precondition is enforced at simulation time (all results have same nTrials from config). However, it violates totality.

**Suggested Treatment (if stricter FP desired):**

Option A: Phantom type for trial count
```scala
case class RiskResult[N <: Int](name: SafeId.SafeId, outcomes: Map[TrialId, Loss], provenances: List[NodeProvenance])
// combine only works on RiskResult[N] with same N
```

Option B: Accept current design as documented tradeoff (recommended)

**Priority:** Low (documented tradeoff)  
**Effort:** N/A or 4+ hours for phantom types

---

### Issue 5: Conditional Branching in fromNodes Validation

**File:** [RiskTree.scala](../modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala)  
**Lines:** 116-125

**Violation:** Uses `if/else` instead of declarative `Validation.fromPredicateWith`.

**Current Code (L116-125):**
```scala
TreeIndex.fromNodeSeq(nodes).flatMap { index =>
  if (!index.nodes.contains(rootId)) {
    Validation.fail(ValidationError(
      field = "rootId",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = s"rootId '${rootId.value}' not found in nodes"
    ))
  } else {
    Validation.succeed(RiskTree(id, name, nodes, rootId, index))
  }
}
```

**Suggested Treatment:**
```scala
for {
  index <- TreeIndex.fromNodeSeq(nodes)
  _     <- Validation.fromPredicateWith(
             ValidationError("rootId", CONSTRAINT_VIOLATION, s"rootId '${rootId.value}' not found in nodes")
           )(index.nodes.contains(rootId))(identity)
} yield RiskTree(id, name, nodes, rootId, index)
```

**Status:** ✅ Implemented (2026-01-21). `RiskTree.fromNodes` now uses `Validation.fromPredicateWith`; related predicate refactors applied to RiskNode validations and the nodes non-empty check in `RiskTreeDefinitionRequest`.

**Priority:** Low  
**Effort:** 10 minutes

---

### Issue 6: Unsafe Pattern in Test Fixtures

**Files:**  
- [RiskResultCacheSpec.scala](../modules/server/src/test/scala/com/risquanter/register/services/cache/RiskResultCacheSpec.scala) Lines 80-88
- [RiskResultResolverSpec.scala](../modules/server/src/test/scala/com/risquanter/register/services/cache/RiskResultResolverSpec.scala) Lines 53-69
- [RiskTreeControllerSpec.scala](../modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala) Line 63
- [RiskTreeServiceLiveSpec.scala](../modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala) Line 66

**Violation:** Tests use `.toEither.getOrElse(throw ...)` which hides validation errors.

**Current Code (examples):**
```scala
val treeIndex = TreeIndex.fromNodeSeq(allNodes).toEither.getOrElse(
  throw new AssertionError("Test fixture has invalid tree structure")
)
```

**Note:** This is acceptable per ADR-010 for test fixtures where validity is guaranteed.

**Suggested Treatment (optional improvement):**
```scala
val treeIndex = TreeIndex.fromNodeSeq(allNodes) match {
  case Validation.Success(_, idx) => idx
  case Validation.Failure(_, errors) => 
    fail(s"Test fixture invalid: ${errors.map(_.message).mkString("; ")}")
}
```

**Priority:** Low  
**Effort:** 15 minutes

---

## Category Theory Properties Status

| Property | File | Status |
|----------|------|--------|
| `Identity[RiskResult].identity` | LossDistribution.scala L128 | ✅ Intact |
| Associativity | IdentityPropertySpec.scala L115-116 | ✅ Tested |
| Left Identity | LossDistributionSpec.scala L138-140 | ⚠️ Conditional on nTrials |
| Right Identity | LossDistributionSpec.scala L148-150 | ⚠️ Conditional on nTrials |
| Commutativity | LossDistributionSpec.scala L171-172 | ✅ Tested |
| `Equal[RiskResult]` | LossDistribution.scala L143 | ✅ Defined |
| `Debug[RiskResult]` | LossDistribution.scala L147 | ✅ Defined |

---

## Action Items

- [ ] Issue 1: Refactor TreeIndex.fromNodes to use traverse/applicative (Medium)
- [ ] Issue 3: Adopt `toZIOValidation` in `RiskTreeServiceLive` (Low)
- [x] Issue 2: Unify RiskTreeDefinitionRequest.validate to return Validation (Low) — Implemented 2026-01-21
- [x] Issue 5: Use fromPredicateWith in RiskTree.fromNodes (Low) — Implemented 2026-01-21

**Deferred:** Issue 4 (intentional design per ADR-009), Issue 6 (acceptable for tests unless hardened)
