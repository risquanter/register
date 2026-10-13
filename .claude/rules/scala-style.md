---
paths:
  - "**/*.scala"
---

# Scala 3 / ZIO Code Style & Functional Composition

## Imports

- Top-level imports always (ADR-011).
- Local imports only on genuine name collision — never to shorten a qualified
  reference or reduce scroll distance.
- No unused imports. No wildcard imports that widen implicit scope unexpectedly.

## Named constants and closed sets (ADR-037)

- A value that is one of a fixed set is a case of an `enum`, declared in the
  companion of the type that owns it. Its JSON codec is derived; `@jsonHint`
  carries the external spelling only where it differs from the case name, and
  `@jsonDiscriminator` names the tag field.
- A hand-written string mapping survives only where the string is a contract with
  something other than the JSON encoder — a query-language keyword, an external
  protocol, a value another system matches exactly. Where it exists only to be
  the serialised spelling of a case, the derived codec replaces it.
- A single constant is a named value in the companion of the type that owns it,
  carrying a note that says where the value came from. A user-facing sentence is
  a named template, and a template taking a parameter is a function of it, so the
  sentence cannot drift from the values it interpolates.
- `Option[T] = None` as a default parameter is forbidden. The `Option` stays and
  the default goes, so a caller with nothing to pass writes `None` at the call
  site, where it is visible. A derived decoder still reads an absent JSON key as
  `None`, so this does not make a serialised field required.

Cleaning up existing violations is the `boyscout` skill's obligation on a file
already being edited for another reason, and is never itself a reason to open a
file.

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
