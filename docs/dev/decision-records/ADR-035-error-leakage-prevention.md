# ADR-035: Error Leakage Prevention

**Status:** Accepted  
**Date:** 2026-08-30  
**Tags:** security, error-handling, type-safety, CWE-209

---

## Context

- An error response is untrusted output: internal reasons, stack traces, and backend messages must never reach the client
- Message text, string interpolation, and JSON serialisation are the leakage vectors on the outbound path
- Convention-enforced scrubbing at the HTTP boundary is not sound: a new error variant added without a matching sanitisation clause bypasses the defence, and no compiler or linter catches the omission
- A structural guarantee — exhaustive matching on a sealed hierarchy, checked at compile time — turns "remembered to sanitise" into "cannot compile without sanitising"
- A failure that never reaches a handler bypasses any guarantee the handler's own type provides, so the boundary has to cover rejected input and bugs as well as returned errors
- OWASP Top Ten 2025 A10 (Mishandling of Exceptional Conditions) and CWE-209 (Information Exposure Through Error Messages) apply directly

---

## Decision

### 1. One Closed Wire Type, Projected Exhaustively

`WireError` is a sealed enum of nine cases, and it is the only error type that
crosses. Internal failures reach it through one total function:

```scala
enum WireError:
  case ValidationRejected(errors: List[ValidationError], trace: TraceId)
  case QueryRejected(problem: QueryProblem, trace: TraceId)
  case NotFound(trace: TraceId)
  case WorkspaceExpired(cause: ExpiryCause, lifetime: Duration, idleLimit: Duration, trace: TraceId)
  case AccessDenied(trace: TraceId)
  case Conflict(conflict: ConflictKind, trace: TraceId)
  case NotSupported(capability: Capability, trace: TraceId)
  case DependencyUnavailable(trace: TraceId)
  case InternalFault(trace: TraceId)

object WireError:
  def fromAppError(e: AppError, trace: TraceId): WireError = e match
    case ValidationFailed(errors) => WireError.ValidationRejected(errors, trace)
    // … one arm per AppError case
```

The projection is one-way: no case carries an internal type, and nothing decodes
a `WireError` back into an `AppError`. `-Wconf:msg=match may not be exhaustive:error`
in `scalacOptions` makes a new `AppError` case without an arm a **compile error**.

### 2. Every Declared Status Is a Declared Case

The shared error output lists one variant per wire case, each at its own status,
so no status is shared and no case is undeclared:

```scala
.errorOut(
  oneOf[WireError](
    oneOfVariant(BaseEndpoint.validationRejectedOut),         // 400
    oneOfVariant(StatusCode.UnprocessableEntity, jsonBody[WireError.QueryRejected]),
    oneOfVariant(StatusCode.NotFound,            jsonBody[WireError.NotFound]),
    // … seven in total
  )
)
```

Controllers reach it through one extension method, which is also the only place
the trace is read, because reading the active span requires an effect:

```scala
def wireEither(using Tracing): ZIO[R, Nothing, Either[WireError, A]] =
  effect
    .map(Right(_))
    .catchAll(error =>
      ActiveTrace.id.flatMap(trace =>
        logProjectedFailure(error, trace).as(Left(WireError.fromAppError(error, trace)))
      )
    )
```

It is also the one place a failure that becomes a response is logged, at the
level the failure's own `origin` decides (ADR-002). The line carries the same
trace the response carries, so a failure a caller reports can be found in the
log — which is what makes dropping the internal reason from the response safe
rather than merely quiet.

### 3. Message Discipline and the Opaque Status

`AppError` is plain data, not a `Throwable`, so there is no inherited
`getMessage` behind any text. Each error's `logMessage` is logged server-side
(ADR-002) and never serialised.

- `RepositoryFailure(reason)` — `reason` is an internal diagnostic; the wire case is `InternalFault`, which carries a trace and nothing else
- A credential in any message prints its redacted `toString` (`WorkspaceKeySecret(***)`, ADR-022 R3), never the raw value
- A client-visible message names only client-safe values

**A secret and a confidential identifier are bounded in opposite directions** —
see ADR-036 §3 for the matching half — so one rule for both is wrong about one of
them. Both terms are defined in
[docs/user/TERMINOLOGY.md](../../user/TERMINOLOGY.md); the constraint is:

| | a server log, including `logMessage` | the wire |
|---|---|---|
| secret (`WorkspaceKeySecret`) | never — a log's audience is wider than the service (ADR-022 R3) | yes, in the responses that mint it; never in an error |
| confidential (`WorkspaceId`) | permitted — holding it grants nothing (ADR-036 §3) | never, in either direction (ADR-036 §1) |

The client-visible message is the one place both are forbidden.

**One opaque status where entitlement is in question.** An error whose variants
would reveal whether a resource exists collapses to one status, so neither the
status nor the message distinguishes "not yours" from "does not exist". A caller
who does not hold a resource's capability must not learn from the response
whether it is there. `AccessDenied` and `AuthForbidden` both project onto the
same wire case for the same reason: a denial this service decided and one the
authorization service decided are indistinguishable from outside.

An unreachable authorization service is **not** a denial. No decision was
obtained, so none is assumed, and the request fails closed — but it answers as a
dependency outage, indistinguishable from any other, so the answer does not
disclose which dependency failed. Reporting it as a denial told a caller their
access had been refused during an incident they should have waited out, which is
why the two are separated.

The rule binds exactly where the caller's entitlement is in question. It does not
bind where the caller demonstrably holds the capability and the resource's own
lifetime has ended: an expired workspace answers `410 Gone` to the holder of its
key, which discloses nothing that holder did not already know.

### 4. The Two Paths That Reach No Handler

A request the codecs reject and a bug in a handler both bypass `wireEither`.
Each has an interceptor that answers with a declared output:

```scala
.decodeFailureHandler(WireDecodeFailureHandler.handler(tracing))   // → ValidationRejected
.exceptionHandler(DefectCatchAllInterceptor.exceptionHandler)      // re-raises, so the catch-all answers
.appendInterceptor(DefectCatchAllInterceptor.interceptor(tracing)) // → InternalFault, inside CORS
```

Two answers remain Tapir's and carry a constant body: `404` for a request
matching no route, and `405` for a method mismatch. Neither reports a failure of
this service.

---

## Code Smells

### ❌ Internal Reason Forwarded to the Client

```scala
// BAD: the backend's own text becomes the body
case RepositoryFailure(reason) => WireError.ValidationRejected(
  List(ValidationError("db", INTERNAL_ERROR, reason)), trace)

// GOOD: opaque case carrying only the trace; reason logged server-side
case RepositoryFailure(_) => WireError.InternalFault(trace)
```

### ❌ Confidential Identifier in a Client-Visible Message

```scala
// BAD: the wire-facing failure names an internal scoping identifier (ADR-036)
ZIO.fail(ValidationFailed(List(ValidationError(
  "scenario", NOT_FOUND, s"not found in workspace ${wsId.value}"))))

// GOOD: message names only client-safe values
ZIO.fail(ValidationFailed(List(ValidationError(
  "scenario", NOT_FOUND, s"Scenario '${name.value}' not found"))))
```

### ❌ A Secret in a Log Line

```scala
// BAD: every reader of the log holds the capability
ZIO.logInfo(s"resolving workspace for key ${key.reveal}")

// GOOD: the confidential identifier is the one that may be logged
logSecurity("workspace.created", "workspace_id" -> id.value)("Workspace created")
```

### ❌ A Distinct Status for "Not Yours"

```scala
// BAD: 403 versus 404 tells the caller the resource exists
case TreeNotInWorkspace(_, _) => WireError.AccessDenied(trace)

// GOOD: one opaque status for both, so neither answer distinguishes them
case TreeNotInWorkspace(_, _) => WireError.NotFound(trace)
```

### ❌ An Error Shape Outside the Declared Output

```scala
// BAD: a plain-text body no client decodes and no trace to quote
Response.internalServerError(cause.squash.getMessage)

// GOOD: the declared output the endpoints themselves use
ValuedEndpointOutput(BaseEndpoint.internalFaultOut, WireError.InternalFault(trace))
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `WireError.fromAppError` | One-way projection, exhaustive over `AppError` |
| `BaseEndpoint` `oneOf` | One declared variant per wire case, each at its own status |
| `WireEither.wireEither` | The single projection point, and the only reader of the trace for a controller failure |
| `WireDecodeFailureHandler` | A rejected input answers `ValidationRejected` with the status the input decides |
| `DefectCatchAllInterceptor` | A defect or escaped failure answers `InternalFault`, from inside the CORS interceptor |
| `build.sbt` | `-Wconf:msg=match may not be exhaustive:error` promotes an inexhaustive match to a compile error |

---

## References

- [OWASP Top Ten 2025 A10 — Mishandling of Exceptional Conditions](https://owasp.org/Top10/)
- [CWE-209 — Generation of Error Message Containing Sensitive Information](https://cwe.mitre.org/data/definitions/209.html)
