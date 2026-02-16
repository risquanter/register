# ADR-022: Secret Handling & Error Leakage Prevention

**Status:** Proposed  
**Date:** 2026-02-16  
**Tags:** security, secrets, error-handling, type-safety, CWE-209  
**Related:** [ADR-001](./ADR-001.md) (Validation / Iron), [ADR-002](./ADR-002.md) (Logging / Typed Errors), [ADR-008](./ADR-008-proposal.md) (Error Resilience), [ADR-012](./ADR-012.md) (Service Mesh), [ADR-018](./ADR-018-nominal-wrappers.md) (Nominal Wrappers), [ADR-021](./ADR-021-capability-urls.md) (Capability URLs)

---

## Context

- Secrets (API keys, tokens, infrastructure credentials) must never appear in logs, serialised responses, or error messages
- The JVM `String` type is **hostile to secrets**: immutable, interned, persists in heap until GC
- Leakage vectors: `toString`, JSON serialisation, string interpolation, `getMessage`, stack traces, heap dumps
- `WorkspaceKey` is the only credential that lives in application code today — it is a `case class` whose auto-generated `toString` prints the full 128-bit capability token
- Current defence (A25/A26): `ErrorResponse.encode` sanitises at the HTTP boundary — effective but **convention-enforced**, not **type-enforced**
- OWASP Top Ten 2025 A10 (Mishandling of Exceptional Conditions) and CWE-209 (Information Exposure Through Error Messages) apply directly

### Scope — What This ADR Covers (and What It Doesn't)

The three-layer authorization model ([AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md)) explicitly delegates credential handling to the infrastructure stack:

| Secret type | Where it lives | Handler |
|---|---|---|
| User passwords | Keycloak only | Never touches app code |
| JWT signing keys | Keycloak JWKS endpoint | Istio fetches via `jwksUri`; app never sees private key |
| JWT tokens | Browser memory → `Authorization` header | Istio validates; app sees only decoded claims via `x-jwt-claims` |
| mTLS certificates | ztunnel auto-rotated | Zero-config, never in app code |

**This ADR does NOT cover those secrets** — they are structurally unreachable by design (ADR-012, OAUTH2-FLOW-ARCHITECTURE.md).

This ADR covers:
1. **`WorkspaceKey`** — the Layer 0 capability credential that lives in application code today
2. **Infrastructure config secrets** — future database passwords, SpiceDB pre-shared keys, loaded via `Config.Secret`
3. **Error leakage prevention** — ensuring `ErrorResponse.encode` remains structurally sound as the `AppError` hierarchy evolves

---

## Decision

### 1. `Secret[A]` Wrapper — toString-safe, serialisation-hostile

Secrets are wrapped in a `final class` (not `case class`) that overrides `toString` and provides no JSON codec:

```scala
final class Secret[A] private (private val raw: A):
  override def toString: String = "Secret(***)"
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case s: Secret[?] => raw == s.raw
    case _            => false

  /** Explicit opt-in to access the value. */
  def reveal: A = raw

object Secret:
  def apply[A](value: A): Secret[A] = new Secret(value)
  // Deliberately: NO given JsonEncoder, Show, or similar
```

Key properties:
- `final class` — no `copy`, no `unapply`, no product serialisation
- `private val` — inaccessible without `reveal`
- No `JsonEncoder[Secret[A]]` given — compile error if serialisation attempted
- `toString` → `"Secret(***)"` — safe for logging, interpolation, exception messages

**Relationship to ADR-018 (Nominal Wrappers):** ADR-018 uses `case class` wrappers to add *identity distinction* over Iron types — `TreeId` vs `NodeId` are both ULIDs but compile-time distinct. `Secret[A]` deliberately breaks from the case-class wrapper pattern because the goals are opposed: ADR-018 wants transparent serialisation and pattern matching; `Secret` exists specifically to *prevent* those operations. `final class` removes `copy`, `unapply`, and product serialisation that `case class` provides.

**Relationship to ADR-001 (Iron Types):** Iron constrains value *shape* (format, range); `Secret` constrains value *visibility*. They are orthogonal and compose: a `Secret[WorkspaceKey]` is both format-validated (Iron, via `WorkspaceKey.fromString`) and leak-proof (`toString` returns `"Secret(***)"`, no JSON codec).

For config-loaded secrets, use ZIO's built-in `Config.Secret` (already a dependency):

```scala
val dbPassword: Config[Config.Secret] = Config.secret("DB_PASSWORD")
```

### 2. WorkspaceKey — Convert to `final class`

`WorkspaceKey` is the sole credential in Layer 0 (capability-only mode). Rather than creating a generic `Secret[A]` wrapper and then not using it on the only candidate, convert `WorkspaceKey` itself to a `final class` — it *is* the secret type:

```scala
final class WorkspaceKey private (private val raw: String):
  override def toString: String = "WorkspaceKey(***)"
  def reveal: String = raw
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case wk: WorkspaceKey => raw == wk.raw
    case _                => false

object WorkspaceKey:
  def apply(value: String): WorkspaceKey = new WorkspaceKey(value)
  given JsonEncoder[WorkspaceKey] = JsonEncoder[String].contramap(_.reveal)
  given JsonDecoder[WorkspaceKey] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceKey.fromString(s).left.map(_.mkString(", ")))
```

This gives all the `Secret[A]` properties — no `copy`, no `unapply`, `private val`, redacted `toString` — without an additional layer of indirection. The JSON codecs use `reveal` explicitly (opt-in access), Tapir codec uses `reveal`, and `println(key)` prints `WorkspaceKey(***)`.

**Effort:** ~40 lines across 6 files (see [implementation plan](./ADR-022-implementation-plan.md)).

### 2a. Config.Secret vs WorkspaceKey — Boundary Decision

Two tools exist for secret handling. They serve **different threat models**:

| Property | `WorkspaceKey` (`final class`) | `zio.Config.Secret` |
|----------|-------------------------------|---------------------|
| **Lifecycle** | Generated at runtime, flows through request lifecycle (endpoints, controllers, stores, frontend state, error types) | Loaded once at startup from env/config, consumed immediately |
| **Accessor** | `.reveal: String` | `.value: Chunk[Char]`, `.stringValue: String` |
| **Pattern matching** | No `unapply` — cannot be extracted accidentally in `match`/`for` | Has `unapply` — but acceptable because config values aren't pattern-matched in error handlers or logging |
| **Validation** | Iron-validated via `fromString` | No validation — raw config value |
| **Serialisation** | Explicit JSON codecs via `reveal`, Tapir codec | No JSON codecs needed — never serialised to clients |
| **Where it appears** | URL paths, JSON responses, error types, frontend Var/Signal | Config loading only — `Config[Config.Secret]` |

**Why `unapply` is acceptable on `Config.Secret` but not on `WorkspaceKey`:**

| | `Config.Secret` | `WorkspaceKey` |
|---|---|---|
| **Pattern-matched where?** | Config loading code only — 1–2 controlled call sites | Error handlers, controllers, stores, tests — 14+ sites across 10 files |
| **Logging risk** | Config code rarely logs; value consumed immediately | `getMessage`, string interpolation, test output — many vectors |
| **Lifecycle** | Created once at startup, consumed, wiped | Created per-request, stored in maps, held in frontend `Var`, embedded in errors, serialised to JSON |

A stray `case Secret(raw) =>` in a config parser is low-risk — one reviewed call site. A stray `case WorkspaceKey(raw) =>` in an error handler could silently embed the credential in a log line. The `final class` (no `unapply`) makes that pattern a **compile error**.

**Rule:** Use `WorkspaceKey` (or similar `final class` pattern) for credentials that flow through application code. Use `Config.Secret` for infrastructure secrets loaded from environment/config that never leave the config layer. See [D3 in the implementation plan](./ADR-022-implementation-plan.md) for details.

### 3. Scoped Lifecycle — Acquire, Use, Wipe

Secrets that arrive as raw bytes (e.g., a future database password loaded from config) use `ZIO.Scope` for deterministic cleanup:

```scala
def withSecret[R, E, A](raw: String)(use: Array[Char] => ZIO[R, E, A]): ZIO[R & Scope, E, A] =
  ZIO.acquireRelease(ZIO.succeed(raw.toCharArray)) { chars =>
    ZIO.succeed(java.util.Arrays.fill(chars, '\u0000'))
  }.flatMap(use)
```

Limitations (documented, not solvable on the JVM):
- GC may copy the `char[]` before erasure (heap compaction)
- JIT may optimise away the zeroing (`reachabilityFence` mitigates)
- Heap dumps will capture all live objects regardless

### 4. Exhaustive Error Sanitisation — Compile-Time Guarantee

The error hierarchy (ADR-002 Decision 5, implemented as `sealed trait AppError`) uses two sub-traits:

```
sealed trait AppError extends Throwable
├── sealed trait SimError extends AppError
│   ├── ValidationFailed(errors: List[ValidationError])
│   ├── RepositoryFailure(reason: String)
│   ├── SimulationFailure(simulationId: String, cause: Throwable)
│   ├── DataConflict(reason: String)
│   ├── AccessDenied(reason: String)
│   ├── RateLimitExceeded(ip: String, limit: Int, window: String)
│   ├── WorkspaceNotFound(key: WorkspaceKey)
│   ├── WorkspaceExpired(key: WorkspaceKey, createdAt: Instant, ttl: Duration)
│   ├── TreeNotInWorkspace(key: WorkspaceKey, treeId: TreeId)
│   ├── VersionConflict(nodeId: String, expected: String, actual: String)
│   └── MergeConflict(branch: String, details: String)
└── sealed trait IrminError extends AppError
    ├── IrminUnavailable(reason: String)
    ├── IrminHttpError(status: StatusCode, body: String)
    ├── IrminGraphQLError(messages: List[String], path: Option[List[String]])
    └── NetworkTimeout(operation: String, duration: Duration)
```

Currently, `ErrorResponse.encode` matches on `Throwable` — the compiler cannot enforce exhaustive handling. Split into a typed inner match:

```scala
def encode(error: Throwable): (StatusCode, ErrorResponse) = error match
  case e: AppError => encodeAppError(e)
  case _           => makeGeneralResponse()

// Exhaustive — compiler warns if a new variant is added without handling
private def encodeAppError(error: AppError): (StatusCode, ErrorResponse) = error match
  case e: SimError   => encodeSimError(e)
  case e: IrminError => encodeIrminError(e)
```

Enable `-Wconf:msg=match may not be exhaustive:error` in `scalacOptions` so adding a new `AppError` subtype without a corresponding `encode` branch is a **compile error**, not a runtime leak.

**Relationship to ADR-002 (Decision 5):** ADR-002 established that errors should propagate type-safely and be pattern-matched without string inspection. This decision strengthens that guarantee from convention-enforced to compiler-enforced. The sealed hierarchy that makes this possible was architecturally established by ADR-008.

### 5. Typed Error Channel — Structural Boundary

Tapir's `BaseEndpoint` (ADR-001 Decision 3) wires error mapping into all endpoints:

```scala
val baseEndpoint = endpoint
  .errorOut(statusCode and jsonBody[ErrorResponse])
  .mapErrorOut[Throwable](ErrorResponse.decode)(ErrorResponse.encode)
```

The ZIO error channel in each `serverLogic` block calls `.either`, which passes through `ErrorResponse.encode`. This means:
- The only type that can reach the HTTP boundary is `ErrorResponse`
- Internal error types (`AppError`, `Throwable`) are structurally inaccessible to the Tapir serialiser
- Adding new error types that bypass `encode` causes a **type mismatch** at compile time

### 6. `getMessage` Discipline — No Secrets in Exception Messages

Exception `getMessage` must never include credentials or tokens:
- `RepositoryFailure(reason)` — `reason` is an internal diagnostic string, sanitised to `"Internal server error"` by `encode`
- Workspace errors (A13) — opaque `"Workspace not found"` regardless of variant
- `WorkspaceNotFound(key)` and `WorkspaceExpired(key, ...)` include the key in `getMessage` for server-side diagnostics — this is safe because `getMessage` is logged (ADR-002), not serialised to clients (A13 collapses all workspace errors to opaque 404). After Decision 2, these messages will print `WorkspaceKey(***)` instead of the raw token

---

## Code Smells

### ❌ Capability Token Leaking via toString

```scala
// BAD: case class auto-toString prints the full credential
val key = WorkspaceKey("abcdefghijklmnopqrstuv")
log.info(s"Resolving $key") // prints "Resolving WorkspaceKey(abcdefghijklmnopqrstuv)"

// GOOD: final class with redacted toString (Decision 2)
// WorkspaceKey is a final class — toString returns "WorkspaceKey(***)"
log.info(s"Resolving $key") // prints "Resolving WorkspaceKey(***)"
// Access requires explicit opt-in: key.reveal
```

### ❌ Raw String for Infrastructure Secrets

```scala
// BAD: config secret is a String — toString, logging, serialisation all leak it
case class DbConfig(host: String, password: String)

// GOOD: use Config.Secret for credentials loaded from config
val dbPassword: Config[Config.Secret] = Config.secret("DB_PASSWORD")
// Config.Secret.toString → "Secret(<redacted>)"
```

### ❌ Non-Exhaustive Error Encoding

```scala
// BAD: matching on Throwable — no exhaustiveness check
def encode(error: Throwable) = error match
  case ValidationFailed(errors) => ...
  case _                        => makeGeneralResponse()
// Adding a new AppError subtype? Compiler says nothing. It falls through to `_`.

// GOOD: inner match on sealed hierarchy — compiler enforces completeness
def encode(error: Throwable) = error match
  case e: AppError => encodeAppError(e)  // exhaustive match inside
  case _           => makeGeneralResponse()
```

### ❌ Leaking Internal Reason in 500

```scala
// BAD: forwarding the reason string to the client
case RepositoryFailure(reason) =>
  response(500, "unknown", CONSTRAINT_VIOLATION, reason)  // leaks SQL errors

// GOOD: opaque message, reason logged server-side only (ADR-002 Decision 5)
case RepositoryFailure(reason) =>
  response(500, "unknown", CONSTRAINT_VIOLATION, "Internal server error")
```

---

## Implementation

| Location | Pattern | Effort |
|----------|---------|--------|
| `WorkspaceKey` (`OpaqueTypes.scala`) | Convert to `final class` with `reveal`, `equals`/`hashCode`, redacted `toString` | Low (~40 lines across 6 files) |
| `ErrorResponse.encode` | Split into `encode` + `encodeAppError` + `encodeSimError` + `encodeIrminError` | Low (~30 lines refactored) |
| `build.sbt` | Add `-Wconf:msg=match may not be exhaustive:error` | Trivial (1 line) |
| `Config.Secret` | Use for infrastructure secrets (DB, SpiceDB) — boundary documented in Decision 2a | Trivial (when added) |

---

## Non-Scope — Secrets Handled Externally

The following secrets are handled by the service mesh (ADR-012) and **never enter application code**:

| Secret | Handler | Why ADR-022 doesn't apply |
|--------|---------|---------------------------|
| User passwords | Keycloak | App never sees passwords; Keycloak handles authentication |
| JWT signing keys | Keycloak JWKS | Istio fetches public keys for validation; private keys stay in Keycloak |
| JWT tokens | Istio `RequestAuthentication` | Mesh validates signature/expiry; app sees only decoded claims in `x-jwt-claims` header |
| mTLS certificates | ztunnel | Auto-rotated, zero-config; app is unaware of cert lifecycle |
| OAuth2 client secrets | Keycloak service accounts | Service-to-service auth via mTLS, not client secrets in app code |

If a future deployment requires app-code access to infrastructure secrets (e.g., direct database connection without mesh, SpiceDB pre-shared key), use `Config.Secret` (Decision 1) and the scoped lifecycle (Decision 3).

---

## References

- [ADR-001: Validation Strategy / Iron Types](./ADR-001.md) — boundary parsing, Iron constrains shape; this ADR constrains visibility
- [ADR-002: Logging / Typed Errors (Decision 5)](./ADR-002.md) — error hierarchy propagates type-safely; this ADR strengthens to compiler-enforced
- [ADR-008: Error Handling & Resilience](./ADR-008-proposal.md) — established sealed `AppError` hierarchy that enables exhaustive matching
- [ADR-012: Service Mesh](./ADR-012.md) — externalises JWT/mTLS/password handling to infrastructure
- [ADR-018: Nominal Wrappers](./ADR-018-nominal-wrappers.md) — case-class wrappers for identity distinction; `Secret` breaks from this pattern deliberately
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — `WorkspaceKey` (originally proposed as `ShareToken`) is the credential this ADR hardens
- [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) — three-layer model; Layers 1-2 delegate secrets to mesh
- [CWE-209: Information Exposure Through Error Messages](https://cwe.mitre.org/data/definitions/209.html)
- [OWASP Error Handling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html)
- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [ZIO `Config.Secret`](https://github.com/zio/zio/blob/series/2.x/core/shared/src/main/scala/zio/Config.scala)
- [`geirolz/secret` — Secret lifecycle library](https://github.com/geirolz/secret)
