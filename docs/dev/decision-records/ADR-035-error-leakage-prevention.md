# ADR-035: Error Leakage Prevention

**Status:** Accepted  
**Date:** 2026-08-30  
**Tags:** security, error-handling, type-safety, CWE-209

---

## Context

- An error response is untrusted output: internal reasons, stack traces, and backend messages must never reach the client
- `getMessage`, string interpolation, and JSON serialisation are the leakage vectors on the outbound path
- Convention-enforced scrubbing at the HTTP boundary is not sound: a new error variant added without a matching sanitisation clause bypasses the defence, and no compiler or linter catches the omission
- A structural guarantee — exhaustive matching on a sealed hierarchy, checked at compile time — turns "remembered to sanitise" into "cannot compile without sanitising"
- OWASP Top Ten 2025 A10 (Mishandling of Exceptional Conditions) and CWE-209 (Information Exposure Through Error Messages) apply directly

---

## Decision

### 1. Exhaustive Error Sanitisation — Compile-Time Guarantee

The error hierarchy (ADR-010) is a sealed `AppError` trait with sealed sub-traits:

```
sealed trait AppError extends Throwable
├── sealed trait SimError extends AppError    // domain/service failures
└── sealed trait IrminError extends AppError  // storage-backend failures
```

`ErrorResponse.encode` dispatches to a typed inner match so the compiler enforces
that every variant is handled:

```scala
def encode(error: Throwable): (StatusCode, ErrorResponse) = error match
  case e: AppError => encodeAppError(e)
  case _           => makeGeneralResponse()

// Exhaustive — a new AppError subtype without a branch is a compile error
private def encodeAppError(error: AppError): (StatusCode, ErrorResponse) = error match
  case e: SimError   => encodeSimError(e)
  case e: IrminError => encodeIrminError(e)
```

`-Wconf:msg=match may not be exhaustive:error` in `scalacOptions` promotes an
inexhaustive match to a **compile error**, so adding a variant without an
`encode` branch fails the build rather than leaking at runtime.

### 2. Typed Error Channel — Structural Boundary

Tapir's base endpoint (ADR-001) wires error mapping into every endpoint, so
`ErrorResponse` is the only type that can reach the wire:

```scala
val baseEndpoint = endpoint
  .errorOut(statusCode and jsonBody[ErrorResponse])
  .mapErrorOut[Throwable](ErrorResponse.decode)(ErrorResponse.encode)
```

The ZIO error channel in each `serverLogic` block passes through
`ErrorResponse.encode`. Internal error types (`AppError`, `Throwable`) are
structurally inaccessible to the serialiser; an error type that bypasses
`encode` is a type mismatch at compile time.

### 3. `getMessage` Discipline — No Sensitive Detail in Messages

An exception `getMessage` is logged server-side (ADR-002), never serialised to
the client. It must carry no secret, no internal reason forwarded to the wire,
and no confidential internal identifier (ADR-036):

- `RepositoryFailure(reason)` — `reason` is an internal diagnostic; `encode`
  maps it to `"Internal server error"`, never the raw string
- Resource-neutral errors collapse to an opaque status (a missing resource is a
  plain 404 regardless of variant), so the message cannot distinguish "not
  yours" from "does not exist"
- A credential in a message prints its redacted `toString` (`WorkspaceKeySecret(***)`,
  ADR-022 R3), never the raw value

---

## Code Smells

### ❌ Non-Exhaustive Error Encoding

```scala
// BAD: matching on Throwable — no exhaustiveness check
def encode(error: Throwable) = error match
  case ValidationFailed(errors) => ...
  case _                        => makeGeneralResponse()
// A new AppError subtype falls through to `_`; the compiler says nothing.

// GOOD: inner match on the sealed hierarchy — completeness is compiler-enforced
def encode(error: Throwable) = error match
  case e: AppError => encodeAppError(e)  // exhaustive match inside
  case _           => makeGeneralResponse()
```

### ❌ Leaking Internal Reason in a 500

```scala
// BAD: forwarding the reason string to the client
case RepositoryFailure(reason) =>
  response(500, "unknown", INTERNAL_ERROR, reason)  // leaks SQL / backend text

// GOOD: opaque message; reason logged server-side only (ADR-002)
case RepositoryFailure(reason) =>
  response(500, "unknown", INTERNAL_ERROR, "Internal server error")
```

### ❌ Confidential Identifier in an Error Message

```scala
// BAD: the wire-facing failure names an internal scoping identifier (ADR-036)
ZIO.fail(ValidationFailed(List(ValidationError(
  "scenario", NOT_FOUND, s"not found in workspace ${wsId.value}"))))

// GOOD: message names only client-safe values
ZIO.fail(ValidationFailed(List(ValidationError(
  "scenario", NOT_FOUND, s"Scenario '${name.value}' not found"))))
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `ErrorResponse.encode` | Split into `encode` + `encodeAppError` + `encodeSimError` + `encodeIrminError` — exhaustive inner match |
| `build.sbt` | `-Wconf:msg=match may not be exhaustive:error` promotes inexhaustive matches to compile errors |
| `baseEndpoint` (ADR-001) | `mapErrorOut(ErrorResponse.decode)(ErrorResponse.encode)` — `ErrorResponse` is the only wire type |

---

## References

- [ADR-010: Error Handling Strategy](./ADR-010.md) — owns the `AppError` hierarchy; this ADR strengthens sanitisation from convention to compiler-enforced
- [ADR-029: Input Injection Defence](./ADR-029-input-injection-defence.md) — inbound counterpart; this ADR is the outbound counterpart
- [ADR-036: Confidential Internal Identifiers](./ADR-036-confidential-internal-identifiers.md) — what `getMessage` and response bodies must not carry
- [CWE-209: Information Exposure Through Error Messages](https://cwe.mitre.org/data/definitions/209.html)
- [OWASP Error Handling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html)
