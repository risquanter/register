# ADR-022: Secret Handling & Error Leakage Prevention

**Status:** Accepted  
**Date:** 2026-02-16 (implemented 2026-02-17)  
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

**This ADR does NOT cover those secrets** — they are structurally unreachable by design (ADR-012, [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md)).

This ADR covers:
1. **`WorkspaceKey`** — the Layer 0 capability credential that lives in application code today
2. **Infrastructure config secrets** — future database passwords, SpiceDB pre-shared keys, loaded via `Config.Secret`
3. **Error leakage prevention** — ensuring `ErrorResponse.encode` remains structurally sound as the `AppError` hierarchy evolves

---

## Decision

### 1. Credential Type Requirements — Checklist

Any type that wraps a credential flowing through application code (request lifecycle, error types, frontend state) **must** satisfy all of the following:

| # | Requirement | Rationale | How to verify |
|---|-------------|-----------|---------------|
| R1 | **`final class`** (not `case class`) | Prevents compiler-generated `copy`, `unapply`, and product serialisation | `case class` → compile error on `copy`/`unapply` |
| R2 | **`private val`** for the raw value | Field inaccessible without explicit opt-in method | Direct field access → compile error |
| R3 | **Redacted `toString`** | `println(key)`, `s"got $key"`, exception messages all safe | `println` prints `TypeName(***)`, never the credential |
| R4 | **Explicit `reveal` method** | Call sites must opt in to raw value extraction — visible in code review | `grep reveal` finds all extraction points |
| R5 | **Iron-validated internally** | Correct-by-construction: the raw value carries its validation proof through the type (ADR-001) | Constructor takes Iron-refined type, not plain `String` |
| R6 | **Manual `equals`/`hashCode`** | Case-class auto-generation lost; must be explicitly provided | Unit test: equal values → same hash; distinct values → different |
| R7 | **Companion `fromString` with Iron validation** | Canonical validated entry point; returns `Either[List[ValidationError], T]` | Invalid input → `Left`; valid input → `Right` |
| R8 | **No default JSON codec on generic wrapper** | Serialisation must be opt-in per credential type, not inherited | Codec defined on the specific type's companion, not on a generic base |

**Relationship to ADR-018 (Nominal Wrappers):** ADR-018 uses `case class` wrappers to add *identity distinction* over Iron types — `TreeId` vs `NodeId` are both ULIDs but compile-time distinct. Credential types deliberately break from the case-class wrapper pattern because the goals are opposed: ADR-018 wants transparent serialisation and pattern matching; credential types exist specifically to *prevent* those operations.

**Relationship to ADR-001 (Iron Types):** Iron constrains value *shape* (format, range); credential types constrain value *visibility*. They are orthogonal and compose: `WorkspaceKeySecret` is both format-validated (Iron `WorkspaceKeyStr` stored internally) and leak-proof (redacted `toString`, no `unapply`).

#### Reference Implementation: `WorkspaceKeySecret`

`WorkspaceKeySecret` (formerly `WorkspaceKey`) is the sole credential in Layer 0 application code. It satisfies all eight requirements:

```scala
// R5: Iron-refined type alias — validation proof carried through to the class
type WorkspaceKeyStr = String :| Match["^[A-Za-z0-9_-]{22}$"]

// R1: final class, not case class
final class WorkspaceKeySecret private (private val raw: WorkspaceKeyStr): // R2: private val
  def reveal: String = raw                                                // R4: explicit opt-in
  override def toString: String = "WorkspaceKeySecret(***)"               // R3: redacted
  override def hashCode: Int = raw.hashCode                               // R6: manual
  override def equals(that: Any): Boolean = that match                    // R6: manual
    case wk: WorkspaceKeySecret => raw == wk.raw
    case _                      => false

object WorkspaceKeySecret:
  // R5: constructor takes WorkspaceKeyStr (Iron proof required)
  def apply(value: WorkspaceKeyStr): WorkspaceKeySecret = new WorkspaceKeySecret(value)

  // Thread-safe shared instance — avoids repeated /dev/urandom seeding per call
  private val rng: java.security.SecureRandom = new java.security.SecureRandom()

  def generate: UIO[WorkspaceKeySecret] = ZIO.succeed {
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    val encoded = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    // refineUnsafe safe: SecureRandom(16 bytes) → base64url always produces 22 chars from [A-Za-z0-9_-]
    new WorkspaceKeySecret(encoded.refineUnsafe[Match["^[A-Za-z0-9_-]{22}$"]])
  }

  // R7: canonical validated entry point
  def fromString(s: String): Either[List[ValidationError], WorkspaceKeySecret] =
    ValidationUtil.refineWorkspaceKey(s)

  // R8: codecs defined here, not on a generic base — opt-in per type
  given JsonEncoder[WorkspaceKeySecret] = JsonEncoder[String].contramap(_.reveal)
  given JsonDecoder[WorkspaceKeySecret] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceKeySecret.fromString(s).left.map(_.mkString(", ")))
```

The `Secret` prefix in the name is deliberate: it signals that this type has special handling properties (no `unapply`, redacted `toString`). Follow the type definition to this ADR for the full rationale.

### 2. `WorkspaceKeySecret` — The Sole Application-Level Credential

`WorkspaceKeySecret` is the only credential that flows through application code today. All Layer 1/2 secrets (passwords, JWT signing keys, mTLS certs) are handled by the service mesh (ADR-012) and never enter the application.

The name was chosen to be self-documenting: any developer encountering `WorkspaceKeySecret` can follow the type definition to find the `// ADR-022` comment and this document, understanding immediately why the type is a `final class` rather than a `case class` like `TreeId` or `NodeId`.

For config-loaded infrastructure secrets (future: database passwords, SpiceDB pre-shared keys), use ZIO's built-in `Config.Secret`:

```scala
val dbPassword: Config[Config.Secret] = Config.secret("DB_PASSWORD")
```

**Effort:** ~40 lines across 6 files, plus mechanical rename `WorkspaceKey` → `WorkspaceKeySecret` across ~17 files (completed 2026-02-17).

### 2a. Config.Secret vs WorkspaceKeySecret — Boundary Decision

Two tools exist for secret handling. They serve **different threat models**:

| Property | `WorkspaceKeySecret` (`final class`) | `zio.Config.Secret` |
|----------|-------------------------------|---------------------|
| **Lifecycle** | Generated at runtime, flows through request lifecycle (endpoints, controllers, stores, frontend state, error types) | Loaded once at startup from env/config, consumed immediately |
| **Accessor** | `.reveal: String` | `.value: Chunk[Char]`, `.stringValue: String` |
| **Pattern matching** | No `unapply` — cannot be extracted accidentally in `match`/`for` | Has `unapply` — but acceptable because config values aren't pattern-matched in error handlers or logging |
| **Validation** | Iron-validated via `fromString` (R5, R7) | No validation — raw config value |
| **Serialisation** | Explicit JSON codecs via `reveal`, Tapir codec (R4, R8) | No JSON codecs needed — never serialised to clients |
| **Where it appears** | URL paths, JSON responses, error types, frontend Var/Signal | Config loading only — `Config[Config.Secret]` |

**Why `unapply` is acceptable on `Config.Secret` but not on `WorkspaceKeySecret`:**

| | `Config.Secret` | `WorkspaceKeySecret` |
|---|---|---|
| **Pattern-matched where?** | Config loading code only — 1–2 controlled call sites | Error handlers, controllers, stores, tests — 14+ sites across 10 files |
| **Logging risk** | Config code rarely logs; value consumed immediately | `getMessage`, string interpolation, test output — many vectors |
| **Lifecycle** | Created once at startup, consumed, wiped | Created per-request, stored in maps, held in frontend `Var`, embedded in errors, serialised to JSON |

A stray `case Secret(raw) =>` in a config parser is low-risk — one reviewed call site. A stray `case WorkspaceKeySecret(raw) =>` in an error handler is a **compile error** — the `final class` has no `unapply` (R1).

**Rule:** Use the credential type requirements checklist (Decision 1) for credentials that flow through application code. Use `Config.Secret` for infrastructure secrets loaded from environment/config that never leave the config layer.

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
│   ├── WorkspaceNotFound(key: WorkspaceKeySecret)
│   ├── WorkspaceExpired(key: WorkspaceKeySecret, createdAt: Instant, ttl: Duration)
│   ├── TreeNotInWorkspace(key: WorkspaceKeySecret, treeId: TreeId)
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
- Workspace errors (A13) — opaque `"Not found"` regardless of variant (resource-neutral)
- `WorkspaceNotFound(key)` and `WorkspaceExpired(key, ...)` include the key in `getMessage` for server-side diagnostics — this is safe because `getMessage` is logged (ADR-002), not serialised to clients (A13 collapses all workspace errors to opaque 404). After Decision 2, these messages will print `WorkspaceKeySecret(***)` instead of the raw token (R3)

---

## Code Smells

### ❌ Capability Token Leaking via toString

```scala
// BAD: case class auto-toString prints the full credential
val key = WorkspaceKey("abcdefghijklmnopqrstuv")
log.info(s"Resolving $key") // prints "WorkspaceKey(abcdefghijklmnopqrstuv)"

// GOOD: WorkspaceKeySecret — final class with redacted toString (R1, R3)
log.info(s"Resolving $key") // prints "WorkspaceKeySecret(***)"
// Access requires explicit opt-in: key.reveal (R4)
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
  response(500, "unknown", INTERNAL_ERROR, reason)  // leaks SQL errors

// GOOD: opaque message, reason logged server-side only (ADR-002 Decision 5)
case RepositoryFailure(reason) =>
  response(500, "unknown", INTERNAL_ERROR, "Internal server error")
```

---

## Implementation

| Location | Pattern | Effort |
|----------|---------|--------|
| `WorkspaceKeySecret` (`OpaqueTypes.scala`) | `final class` with Iron-validated `WorkspaceKeyStr` internal, `reveal`, `equals`/`hashCode`, redacted `toString` (R1–R8) | Low (~40 lines) |
| Rename `WorkspaceKey` → `WorkspaceKeySecret` | Mechanical rename across imports, type annotations, endpoints, controllers, stores, tests | Medium (~15 files, mechanical) |
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

If a future deployment requires app-code access to infrastructure secrets (e.g., direct database connection without mesh, SpiceDB pre-shared key), use `Config.Secret` (Decision 2) and the scoped lifecycle (Decision 3). If the secret flows through the request lifecycle (not just config loading), apply the credential type checklist (Decision 1).

---

## References

- [ADR-001: Validation Strategy / Iron Types](./ADR-001.md) — boundary parsing, Iron constrains shape; this ADR constrains visibility
- [ADR-002: Logging / Typed Errors (Decision 5)](./ADR-002.md) — error hierarchy propagates type-safely; this ADR strengthens to compiler-enforced
- [ADR-008: Error Handling & Resilience](./ADR-008-proposal.md) — established sealed `AppError` hierarchy that enables exhaustive matching
- [ADR-012: Service Mesh](./ADR-012.md) — externalises JWT/mTLS/password handling to infrastructure
- [ADR-018: Nominal Wrappers](./ADR-018-nominal-wrappers.md) — case-class wrappers for identity distinction; credential types break from this pattern deliberately (R1)
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — `WorkspaceKeySecret` (originally `ShareToken` → `WorkspaceKey`) is the credential this ADR hardens
- [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) — three-layer model; Layers 1-2 delegate secrets to mesh
- [CWE-209: Information Exposure Through Error Messages](https://cwe.mitre.org/data/definitions/209.html)
- [OWASP Error Handling Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html)
- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [ZIO `Config.Secret`](https://github.com/zio/zio/blob/series/2.x/core/shared/src/main/scala/zio/Config.scala)
- [`geirolz/secret` — Secret lifecycle library](https://github.com/geirolz/secret)
