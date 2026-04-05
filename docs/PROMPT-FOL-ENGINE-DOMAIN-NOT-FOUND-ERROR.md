# Prompt: Add DomainNotFoundError to fol-engine QueryError hierarchy

**Target project:** `~/projects/vague-quantifier-logic` (fol-engine)  
**Requesting project:** `~/projects/register` (risk register)  
**Date:** 2026-03-27

---

## Context

The `register` project uses fol-engine's `fol.typed` API
(`TypedSemantics.evaluate`) to evaluate many-sorted FOL queries against
risk trees. Register defines four types in its `TypeCatalog`:

| Type      | TypeId          | JVM carrier | Role          |
|-----------|-----------------|-------------|---------------|
| `Asset`   | `TypeId("Asset")`       | `String`    | Entity type — finite, enumerable (tree node names) |
| `Loss`    | `TypeId("Loss")`        | `Long`      | Output type — function return values |
| `Probability` | `TypeId("Probability")` | `Double` | Output type — function return values |
| `Bool`    | `TypeId("Bool")`        | `Boolean`   | Output type — predicate return values |

Only `Asset` has a domain in `RuntimeModel.domains`. The other types are
output types: they appear as function return values (e.g.
`p95: Asset → Loss`) and are never enumerated. A query that quantifies
over a non-domain type (e.g. `∀x: Loss. …`) is always a user error — it
requests iteration over an infinite/undefined set.

## Problem

`TypedSemantics` currently reports a missing domain as a generic
`EvaluationError`:

```scala
// TypedSemantics.scala — three sites
model.domains.get(query.variable.sort)
  .toRight(QueryError.EvaluationError(
    message = s"No runtime domain found for type '${query.variable.sort.value}'",
    phase = "typed_evaluate"
  ))
```

This appears at three locations:
1. **Line 22–26** — top-level `evaluate()`, resolving the root
   quantified variable's domain
2. **Lines 143–147** — `evalFormula`, `Forall` case, resolving a nested
   universal quantifier's domain
3. **Lines 157–161** — `evalFormula`, `Exists` case, resolving a nested
   existential quantifier's domain

The problem: `EvaluationError` is a catch-all. The caller (register)
cannot distinguish "missing domain because the type is inherently
non-enumerable" from "missing domain due to a wiring bug" without
inspecting the error message string. This is fragile and defeats the
purpose of a typed error hierarchy.

## Goal

Add a dedicated `DomainNotFoundError` to the `QueryError` sealed trait,
following the structural pattern of `RelationNotFoundError`. Replace the
three `EvaluationError` sites above with `DomainNotFoundError`.

---

## Proposed Change

### 1. New error type in `QueryError`

Add to `fol/error/QueryError.scala`, following the pattern of
`RelationNotFoundError` (lines 142–153):

```scala
/** Raised when a query quantifies over a type that has no registered domain. */
case class DomainNotFoundError(
  typeName: TypeId,
  availableTypes: Set[TypeId]
) extends QueryError:
  def message = s"No domain found for type '${typeName.value}'"
  override val context = Map(
    "type" -> typeName.value,
    "available" -> availableTypes.map(_.value).mkString(", ")
  )
  override def formatted: String =
    s"No domain found for type '${typeName.value}'. " +
    s"Available types with domains: ${availableTypes.map(_.value).mkString(", ")}"
```

**Import:** `fol.typed.TypeId` will be needed in `QueryError.scala`.
Since `QueryError` is in `fol.error` and `TypeId` is in `fol.typed`,
this introduces a dependency from `fol.error` → `fol.typed`. If this
cross-package dependency is undesirable, `typeName` and `availableTypes`
can use `String` / `Set[String]` instead — the structural intent is the
same.

### 2. Update three sites in `TypedSemantics.scala`

Replace each `EvaluationError("No runtime domain found …")` with
`DomainNotFoundError`, passing `model.domains.keySet` as available types.

**Site 1 (line 22–26) — `evaluate()`:**
```scala
// Before:
model.domains.get(query.variable.sort)
  .toRight(QueryError.EvaluationError(
    message = s"No runtime domain found for type '${query.variable.sort.value}'",
    phase = "typed_evaluate"
  ))

// After:
model.domains.get(query.variable.sort)
  .toRight(QueryError.DomainNotFoundError(
    typeName = query.variable.sort,
    availableTypes = model.domains.keySet
  ))
```

**Site 2 (lines 143–147) — `evalFormula`, `Forall`:**
```scala
// Before:
case None => Left(QueryError.EvaluationError(
  message = s"No runtime domain found for quantified type '${v.sort.value}'",
  phase = "typed_formula"
))

// After:
case None => Left(QueryError.DomainNotFoundError(
  typeName = v.sort,
  availableTypes = model.domains.keySet
))
```

**Site 3 (lines 157–161) — `evalFormula`, `Exists`:**
Same transformation as Site 2, for the `Exists` case.

### 3. Tests

Add tests in the appropriate spec file:

```scala
"return DomainNotFoundError for unknown type" in {
  val query = // a BoundQuery whose variable has sort TypeId("Unknown")
  val model = RuntimeModel(
    domains = Map(TypeId("Asset") -> Set(Value(TypeId("Asset"), "a"))),
    dispatcher = // minimal dispatcher
  )
  val result = TypedSemantics.evaluate(query, model)
  result shouldBe Left(QueryError.DomainNotFoundError(
    typeName = TypeId("Unknown"),
    availableTypes = Set(TypeId("Asset"))
  ))
}

"return DomainNotFoundError in nested Forall" in {
  // A query: ∀x: Asset. ∀y: Unknown. P(x, y)
  // The outer quantifier resolves, the inner fails with DomainNotFoundError
}
```

---

## How Register Will Use This

Once `DomainNotFoundError` exists, register's `fromQueryError` mapping
(in `AppError.scala`) will add a match arm:

```scala
case e: QueryError.DomainNotFoundError =>
  FolDomainNotQuantifiable(e.typeName.value, e.availableTypes.map(_.value))
```

This maps to HTTP 400 with a user-friendly message explaining that only
entity types (like `Asset`) can be quantified over, and listing which
types are available.

---

## Structural Parallel

`DomainNotFoundError` follows the exact pattern of `RelationNotFoundError`:

| Aspect | `RelationNotFoundError` | `DomainNotFoundError` |
|--------|-------------------------|----------------------|
| "Not found" target | `relationName: RelationName` | `typeName: TypeId` |
| Available alternatives | `availableRelations: Set[RelationName]` | `availableTypes: Set[TypeId]` |
| Trigger | Predicate/function name not in catalog | Type not in `model.domains` |
| Caller action | Fix the symbol name | Fix the quantified variable type |

---

## Acceptance Criteria

1. All existing tests pass unchanged (854 tests)
2. `DomainNotFoundError` is a case class extending `QueryError`
3. The three `EvaluationError("No runtime domain …")` sites in
   `TypedSemantics.scala` are replaced with `DomainNotFoundError`
4. New tests verify the `DomainNotFoundError` is returned for
   unknown/non-domain types
5. `sbt publishLocal` produces an updated 0.3.0-SNAPSHOT artifact
