# ADR-033: Exception Boundaries — Narrowest Sound Catch

**Status:** Accepted (awaiting implementation)  
**Date:** 2026-08-07  
**Tags:** errors, exceptions, boundaries, scala-js, interop

**Scope:** the places where an exception **physically exists** — a required API
throws, or an invariant is asserted — and the catch that guards each one. The
typed values a catch converts into, and everything downstream of them, are
**ADR-010**'s.

---

## Context

- Typed error channels make domain and service code throw-free — an exception exists only where a required API signals failure by throwing (JVM built-ins, third-party libraries, JS interop)
- A catch narrower than what a call can raise lets failures escape; one broader than necessary hides failures the handler cannot interpret
- Scala.js checked operations (`asInstanceOf`, `undefined`→primitive casts) raise `UndefinedBehaviorError`, which neither named exception types nor `scala.util.control.NonFatal` cover
- Catch width (what the call can throw) and catch product (typed error vs total fallback) are independent concerns

---

## Decision

### 1. Principle: catch the narrowest type guaranteed to cover every failure the call can raise

No narrower — failures must not escape a boundary guard. No wider — a handler must not absorb failures it cannot interpret. `NonFatal` never qualifies: on Scala.js it silently misses `UndefinedBehaviorError`, and on the JVM a named type is available.

### 2. Default: no catch at all

Throw-free code is the normal state: errors are values in typed channels — ADR-010's world. This ADR applies from the first throwing API onward:

```scala
def create(...): Validation[ValidationError, RiskLeaf] = ...   // accumulating
def update(...): Task[RiskTree] = ...                          // sealed SimulationError
```

A `catch` in code that calls no throwing API is dead ritual — remove it.

`ZIO.attempt` and the `catchAll`/`catchSome` combinators belong to this typed-channel world — ZIO's own conversion into and handling of the error channel, under ZIO's fatality policy — not manual catches governed by this ADR.

### 3. JVM throwing API → catch the named exception, produce a typed error

The failure has a nameable type; catching it is the conversion layer at the innermost owned boundary. A caller acts on the failure, so it becomes a typed error value — shaped per ADR-010 (`Validation` / the sealed hierarchy):

```scala
// Math.addExact is the only checked Long addition the JVM offers
try Validation.succeed(RiskResultGroup(nodeId, results*))
catch case _: ArithmeticException =>
  Validation.fail(ValidationError(s"riskPortfolio.$nodeId",
    ValidationErrorCode.CONSTRAINT_VIOLATION, ValidationMessages.aggregatedLossOverflow))
```

`Throwable` here is wider than sound coverage requires — forbidden.

### 4. Scala.js ↔ JS interop → catch `Throwable`, via the shared helper

Casts on JS values can raise `UndefinedBehaviorError`; no narrower catch is sound. Route every such guard through the one sanctioned catch-all site:

```scala
object JsBoundary:
  /** The only place `catch Throwable` is allowed. Converts ANY throwable —
    * including UndefinedBehaviorError, which NonFatal misses — into the
    * total fallback. Use only at a Scala.js ↔ JS interop edge. */
  inline def orElse[A](inline fallback: A)(inline body: A): A =
    try body catch case _: Throwable => fallback

def parseHoverSignal(value: js.Dynamic): Option[NodeId] =
  JsBoundary.orElse(Option.empty[NodeId]) { /* casts on the JS value */ }
```

The product follows the caller: no recovery path → total fallback (`Option`/`Unit`); a caller that genuinely recovers still catches `Throwable` here, then classifies into a typed error. On hot paths prefer shape pre-checks (`js.isUndefined`, length guards) so malformed input reaches the fallback without a throw.

### 5. `require()` as unreachable-invariant safety net

Thrown assertions guard programmer errors, never user input — user input goes through smart constructors (ADR-010):

```scala
final case class RiskPortfolio private (...) extends RiskNode:
  require(children.nonEmpty, "RiskPortfolio invariant violated")  // smart ctor enforces

case class LognormalDistribution(meanLog: Double, stdLog: Double):
  require(stdLog > 0, s"stdLog must be positive, got: $stdLog")   // external-lib precondition
```

Acceptable when the failing state is unreachable through validated entry points: private constructor + smart constructor, external-library preconditions, internal preconditions on already-validated parameters.

---

## Code Smells

### ❌ `NonFatal` at a boundary

```scala
// BAD: unsound on Scala.js — UndefinedBehaviorError escapes the guard
catch case NonFatal(_) => None

// GOOD: JS edge — the catch-all IS the narrowest sound catch
catch case _: Throwable => None
```

### ❌ Broad catch on the JVM

```scala
// BAD: absorbs OOM, interrupts, bugs — cannot interpret what it caught
try parse(raw) catch case _: Throwable => Left(ParseError.Malformed)

// GOOD: name the one failure the call can raise
try parse(raw) catch case _: NumberFormatException => Left(ParseError.Malformed)
```

### ❌ Catch in throw-free code

```scala
// BAD: nothing here throws — the catch guards nothing
try Validation.succeed(combine(a, b))
catch case _: Exception => Validation.fail(...)

// GOOD: typed channel, no catch
Validation.succeed(combine(a, b))
```

### ❌ Dead error channel on a total fallback

```scala
// BAD: every caller discards the Left — absence is the only correct outcome
def hover(v: js.Dynamic): Either[BridgeError, NodeId]

// GOOD: the total return encodes "couldn't parse" directly
def hover(v: js.Dynamic): Option[NodeId]
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `LossDistribution.scala` (`RiskResultGroup.create`) | Named-type conversion over a throwing JVM API |
| `MetalogDistribution.scala` (`fromPercentiles`) | Undocumented foreign JVM API — `Exception` is the narrowest guaranteed cover (JVM `Error`s still escape) → typed error |
| `app/state/ChartHoverBridge.scala` (`parseHoverSignal`) | JS-boundary catch-all → total fallback (`Option`) |
| `app/chart/LecChartParams.scala` (`ChartParams.applyTo`) | JS-boundary catch-all → total fallback (`Unit`) |
| `domain/data/RiskNode.scala` (`RiskPortfolio`) | `require()` invariant safety net |

---

## References

- ADR-010: Error handling strategy (typed channels, accumulation, HTTP mapping)
- ADR-019: Frontend component architecture (the Scala.js surface this governs)
