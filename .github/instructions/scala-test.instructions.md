---
applyTo: "**/test/**/*.scala"
---

# Test Quality & Assertion Integrity

## Before you write any test

Read one existing test in the same module for fixture idioms and assertion style.
Deviations from the established pattern require explicit approval.

## Tests are specifications

A test suite is not a safety net — it is the specification of the behaviour.
Happy path alone is insufficient. Every validation path, error branch, and
cross-field rule needs a test case.

Required coverage for any new feature:
- ✅ Happy path
- ✅ Every validation constraint (each Iron refinement that can reject input)
- ✅ Every error branch in the service / handler
- ✅ Every cross-field business rule (Layer 2 in ADR-001 smart constructors)
- ✅ Edge cases (empty collections, maximum values, boundary inputs)

## Never weaken an assertion

Never change a test assertion to make a failing test pass. This includes:
- Replacing `Assertion.equalTo(expected)` with `assertTrue(true)`
- Adding `@ignore` or moving to a deferred suite
- Replacing a value assertion with a structural one to hide a wrong value
- Deleting the test

This is Decision Trigger #8 applied at the file level. If a test reveals a design
tension, naming ambiguity, or implementation gap → stop and ask. The test is not
wrong; the implementation or design is wrong.

## ZIO Test patterns

```scala
// ✅ CORRECT assertion patterns
assertTrue(result == expected)
assert(result)(Assertion.equalTo(expected))
assert(errors)(Assertion.contains(ValidationError("field", INVALID_FORMAT, "...")))

// ❌ NEVER — raw throw/assert in ZIO effects
ZIO.attempt(assert(x == y))  // throws, not a ZIO failure
throw new AssertionError(...)
```

## Algebraic law tests

When providing a new type class instance (Monoid, Functor, Ordering, etc.),
write law tests for identity and associativity. See ADR-009 for the existing
pattern in `RiskResultSpec`.

```scala
// Example law test structure
test("RiskResult.combine is associative") {
  val a, b, c = ... // generate or construct test instances
  assertTrue(combine(combine(a, b), c) == combine(a, combine(b, c)))
},
test("RiskResult identity left") {
  assertTrue(combine(empty, a) == a)
}
```

## Tests are part of the definition of done

A task is not complete without tests. This applies even when the task description
does not mention tests. Compile and test before presenting for review.
