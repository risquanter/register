---
applyTo: "**/*.scala"
---

# Scala 3 / ZIO Code Style & Functional Composition

## Imports

- Top-level imports always (ADR-011).
- Local imports only on genuine name collision — never to shorten a qualified
  reference or reduce scroll distance.
- No unused imports. No wildcard imports that widen implicit scope unexpectedly.

## Functor / ADT discipline

If you are writing a match that threads non-success cases unchanged:

```scala
// ❌ SMELL — missing map
state match
  case Idle         => Idle
  case Loading      => Loading
  case Failed(m)    => Failed(m)
  case Loaded(data) => Loaded(transform(data))
```

The repetition is not style — it is a structural gap. The type is missing `map`.
Every caller that copies this pattern pays a maintenance tax: a new ADT variant
requires a change at every match site. Add `map` once; all callers collapse to a
single line.

```scala
// ✅ CORRECT — define map on the ADT
extension [A](s: LoadState[A])
  def map[B](f: A => B): LoadState[B] = s match
    case Loaded(a) => Loaded(f(a))
    case other     => other.asInstanceOf[LoadState[B]]
```

## Named domain operations

A multi-line lambda inside `.map`, `.combineWith`, or a signal combinator is a
domain operation with no name, no test, and no documentation. Extract it to a
named pure function on the companion object.

```scala
// ❌ SMELL — inline domain logic
items.map(item => item.copy(score = item.probability * item.weight * config.scale))

// ✅ CORRECT — named, testable, one-liner at call site
object RiskItem:
  def applyWeight(item: RiskItem, config: Config): RiskItem =
    item.copy(score = item.probability * item.weight * config.scale)

items.map(RiskItem.applyWeight(_, config))
```

## No speculative API surface

Every public method must have at least one call site at the time it is written.
Curried wrappers, convenience overloads, and "bridge" methods with no call site
are dead code. If a future step will need it, add it in that future step.

## ZIO effect composition

- `for`-comprehensions for sequential ZIO effects. No nested `.flatMap` chains.
- Independent effects → `ZIO.zip` / `ZIO.foreachPar` over chained `flatMap`.
- `for`-comprehensions only where steps are genuinely sequential (result of step N
  is needed by step N+1). Otherwise the sequencing is an accidental constraint.

## Compose, do not orchestrate

```scala
// ❌ ORCHESTRATION — manual unwrap/transform/re-wrap
val result = state match
  case Success(x) => Success(f(x))
  case other      => other

// ✅ COMPOSITION — call map
val result = state.map(f)
```
