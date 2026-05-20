---
applyTo: "**/*.scala"
---

# Domain Modelling & Correct-by-Construction

## Before you write any new type or field

1. **Name the Iron constraint** for each field (layer 1 per-field refinement).
2. **Name the cross-field business rules**, if any (layer 2).
3. **Name the owning layer** (codec, service, repository).
4. Only then design the smart constructor shape.

If you cannot answer all three, stop and ask before writing any code.

## Layer contract

The codec has one job: validate raw input → produce Iron types.
The service has one job: trust that types are valid.

If a service method accepts `String`, any caller that constructs `String` values
directly bypasses the validation layer silently — the compiler cannot warn you.
The correctness guarantee holds only if every layer honours its boundary.

```
HTTP / JSON codec   →  Iron types         (validation happens here, once)
Service methods     →  accept Iron types  (trust, never re-validate)
Repository methods  →  accept Iron types  (trust, never re-validate)
```

## Smart constructors

Smart constructors are the **only** valid construction path. `RiskLeaf(rawStr,
rawDouble)` compiles and silently skips all validation. When you find yourself
passing primitive arguments to a constructor, extract a smart constructor — that
is the boundary where validation belongs.

```scala
// ✅ CORRECT — validation happens at the smart constructor
object RiskLeaf:
  def create(id: String, name: String, prob: Double): Validation[ValidationError, RiskLeaf] =
    Validation.validateWith(
      toValidation(ValidationUtil.refineId(id, "id")),
      toValidation(ValidationUtil.refineName(name, "name")),
      toValidation(ValidationUtil.refineProbability(prob, "probability"))
    )(RiskLeaf.apply)

// ❌ NEVER — bypasses validation, silent bug
val leaf = RiskLeaf(rawId, rawName, rawProb)
```

## Validation accumulation

`.flatMap` and `Validation.validateWith` produce identical types and both compile,
but encode different error semantics:

- `.flatMap` stops on the first error (Monad — dependent steps)
- `validateWith` collects all errors before returning (Applicative — independent fields)

A user submitting a form with three invalid fields should see all three errors, not
just the first. Reach for `validateWith` for any multi-field validation. Reserve
`.flatMap` for dependent steps where the second field is only meaningful if the first
succeeds.

## Nominal wrappers (ADR-018)

When two domain concepts share the same Iron constraint but must be compile-time
distinct, use a `case class` wrapper:

```scala
// ✅ CORRECT — compiler-distinct types
case class TreeId(toSafeId: SafeId.SafeId)
case class NodeId(toSafeId: SafeId.SafeId)

// ❌ NEVER — type aliases are transparent; compiler treats them as identical
type TreeId = SafeId.SafeId
type NodeId = SafeId.SafeId
```

Decision rule:
- Two concepts, same Iron constraint, must not be interchangeable → `case class` wrapper
- One concept, purely for performance, one semantic meaning → opaque type

## Domain primitive recognition (→ see scala-algebraic-design for Layer A₀)

When introducing any new field or parameter, ask before writing the type:
- What are the valid values? (subset of `String`/`Int`/`Double` → Iron refinement)
- Could this field be confused with another field of the same raw type? (→ ADR-018 wrapper)
- Does the value originate from user input? (→ always refine)
