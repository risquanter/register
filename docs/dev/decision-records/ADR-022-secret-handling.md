# ADR-022: Secret & Credential Handling

**Status:** Accepted  
**Date:** 2026-02-16  
**Tags:** security, secrets, credentials, type-safety

---

## Context

- Secrets (API keys, tokens, infrastructure credentials) must never appear in logs, serialised responses, or error messages
- The JVM `String` type is **hostile to secrets**: immutable, interned, persists in heap until GC
- Leakage vectors: `toString`, JSON serialisation, string interpolation, `getMessage`, stack traces, heap dumps
- A credential type that traverses application code must make leakage **structurally impossible** — auto-generated `toString`, `copy`, and `unapply` on a `case class` silently defeat this requirement regardless of review discipline
- Value *shape* (Iron refinement) and value *visibility* (credential confinement) are orthogonal and compose on one type

### Scope — What This ADR Covers (and What It Doesn't)

The three-layer authorization model ([AUTHORIZATION-PLAN.md](./plans/AUTHORIZATION-PLAN.md)) delegates most credential handling to the infrastructure stack:

| Secret type | Where it lives | Handler |
|---|---|---|
| User passwords | Keycloak only | Never touches app code |
| JWT signing keys | Keycloak JWKS endpoint | Istio fetches via `jwksUri`; app never sees private key |
| JWT tokens | Browser memory → `Authorization` header | Istio validates; app sees only decoded claims via `x-jwt-claims` |
| mTLS certificates | ztunnel auto-rotated | Zero-config, never in app code |

**This ADR does NOT cover those secrets** — they are structurally unreachable by design (ADR-012, [AUTHORIZATION-PLAN.md](./plans/AUTHORIZATION-PLAN.md)).

This ADR covers:
1. **`WorkspaceKeySecret`** — the Layer 0 capability credential that lives in application code
2. **Infrastructure config secrets** — database passwords, SpiceDB pre-shared keys, loaded via `Config.Secret`

Preventing internal detail from leaking through error responses is [ADR-035](./ADR-035-error-leakage-prevention.md); confining non-secret internal identifiers is [ADR-036](./ADR-036-confidential-internal-identifiers.md).

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

**Relationship to ADR-001 (Iron Types):** Iron constrains value *shape* (format, range); credential types constrain value *visibility*. They compose: `WorkspaceKeySecret` is both format-validated (Iron `WorkspaceKeyStr` stored internally) and leak-proof (redacted `toString`, no `unapply`).

#### Reference Implementation: `WorkspaceKeySecret`

`WorkspaceKeySecret` is the sole credential in Layer 0 application code. It satisfies all eight requirements:

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

The `Secret` suffix in the name is deliberate: it signals the special handling properties (no `unapply`, redacted `toString`). Follow the type definition to this ADR for the full rationale.

### 2. `WorkspaceKeySecret` and `Config.Secret` — The Two Tools and Their Boundary

`WorkspaceKeySecret` is the only credential that flows through application code. All Layer 1/2 secrets (passwords, JWT signing keys, mTLS certs) are handled by the service mesh (ADR-012) and never enter the application.

For config-loaded infrastructure secrets (database passwords, SpiceDB pre-shared keys), use ZIO's built-in `Config.Secret`:

```scala
val dbPassword: Config[Config.Secret] = Config.secret("DB_PASSWORD")
```

The two tools serve different threat models:

| Property | `WorkspaceKeySecret` (`final class`) | `zio.Config.Secret` |
|----------|-------------------------------|---------------------|
| **Lifecycle** | Generated at runtime, flows through request lifecycle (endpoints, controllers, stores, frontend state, error types) | Loaded once at startup from env/config, consumed immediately |
| **Accessor** | `.reveal: String` | `.value: Chunk[Char]`, `.stringValue: String` |
| **Pattern matching** | No `unapply` — cannot be extracted accidentally in `match`/`for` | Has `unapply` — acceptable because config values aren't pattern-matched in error handlers or logging |
| **Validation** | Iron-validated via `fromString` (R5, R7) | No validation — raw config value |
| **Serialisation** | Explicit JSON codecs via `reveal`, Tapir codec (R4, R8) | No JSON codecs — never serialised to clients |
| **Where it appears** | URL paths, JSON responses, error types, frontend `Var`/`Signal` | Config loading only — `Config[Config.Secret]` |

A stray `case Secret(raw) =>` in a config parser is low-risk — one reviewed call site, consumed immediately. A stray `case WorkspaceKeySecret(raw) =>` in an error handler is a **compile error** — the `final class` has no `unapply` (R1).

**Rule:** use the credential checklist (Decision 1) for credentials that flow through application code; use `Config.Secret` for infrastructure secrets loaded from environment/config that never leave the config layer.

### 3. Scoped Lifecycle — Acquire, Use, Wipe

Secrets that arrive as raw bytes (e.g., a database password loaded from config) use `ZIO.Scope` for deterministic cleanup:

```scala
def withSecret[R, E, A](raw: String)(use: Array[Char] => ZIO[R, E, A]): ZIO[R & Scope, E, A] =
  ZIO.acquireRelease(ZIO.succeed(raw.toCharArray)) { chars =>
    ZIO.succeed(java.util.Arrays.fill(chars, '\u0000'))
  }.flatMap(use)
```

Limitations (documented, not solvable on the JVM):
- GC may copy the `char[]` before erasure (heap compaction)
- JIT may optimise away the zeroing (`reachabilityFence` mitigates)
- Heap dumps capture all live objects regardless

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

### ❌ Case-Class Credential Type

```scala
// BAD: case class generates copy/unapply/toString — three leakage paths
case class ApiToken(value: String)

// GOOD: final class satisfying R1–R8; WorkspaceKeySecret is the reference
final class ApiToken private (private val raw: ApiTokenStr):
  def reveal: String = raw
  override def toString: String = "ApiToken(***)"
```

---

## Implementation

| Location | Pattern | Effort |
|----------|---------|--------|
| `WorkspaceKeySecret` (`OpaqueTypes.scala`) | `final class` with Iron-validated `WorkspaceKeyStr` internal, `reveal`, `equals`/`hashCode`, redacted `toString` (R1–R8) | Low (~40 lines) |
| `Config.Secret` | Infrastructure secrets (DB, SpiceDB) — boundary in Decision 2 | Trivial (when added) |
| Scoped lifecycle | `ZIO.acquireRelease` char-array wipe for config-loaded byte secrets | Low |

---

## Non-Scope — Secrets Handled Externally

The following secrets are handled by the service mesh (ADR-012) and **never enter application code**:

| Secret | Handler | Why this ADR doesn't apply |
|--------|---------|---------------------------|
| User passwords | Keycloak | App never sees passwords; Keycloak handles authentication |
| JWT signing keys | Keycloak JWKS | Istio fetches public keys for validation; private keys stay in Keycloak |
| JWT tokens | Istio `RequestAuthentication` | Mesh validates signature/expiry; app sees only decoded claims in `x-jwt-claims` header |
| mTLS certificates | ztunnel | Auto-rotated, zero-config; app is unaware of cert lifecycle |
| OAuth2 client secrets | Keycloak service accounts | Service-to-service auth via mTLS, not client secrets in app code |

If a future deployment requires app-code access to infrastructure secrets (direct database connection without mesh, SpiceDB pre-shared key), use `Config.Secret` (Decision 2) and the scoped lifecycle (Decision 3). If the secret flows through the request lifecycle (not just config loading), apply the credential checklist (Decision 1).

---

## References

- [ADR-001: Validation Strategy / Iron Types](./ADR-001.md) — Iron constrains shape; this ADR constrains visibility
- [ADR-012: Service Mesh](./ADR-012.md) — externalises JWT/mTLS/password handling to infrastructure
- [ADR-018: Nominal Wrappers](./ADR-018-nominal-wrappers.md) — case-class wrappers for identity distinction; credential types break from this pattern deliberately (R1)
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — `WorkspaceKeySecret` is the capability this ADR hardens
- [ADR-035: Error Leakage Prevention](./ADR-035-error-leakage-prevention.md) — keeps secrets out of error responses
- [ADR-036: Confidential Internal Identifiers](./ADR-036-confidential-internal-identifiers.md) — the lighter confinement for non-secret scoping identifiers
- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [ZIO `Config.Secret`](https://github.com/zio/zio/blob/series/2.x/core/shared/src/main/scala/zio/Config.scala)
