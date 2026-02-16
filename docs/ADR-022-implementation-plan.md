# ADR-022 Implementation Plan: Secret Handling & Error Leakage Prevention

**Date:** 2026-02-16  
**Implements:** [ADR-022](./ADR-022-secret-handling.md)  
**Estimated effort:** ~40 lines across 6 files

---

## Background

ADR-022 proposes type-level secret handling and compile-time exhaustive error encoding. After review, the following scope was established:

- **Only one credential lives in application code today:** `WorkspaceKey` (Layer 0 capability token)
- **All Layer 1/2 secrets** (passwords, JWT signing keys, mTLS certs, OAuth2 client secrets) are handled by the service mesh (Keycloak/Istio/ztunnel) and never enter application code
- **`Secret[A]` as a `final class`** is the correct pattern — and `WorkspaceKey` is the one type that should use it. Overriding `toString` alone while having an ADR advocating `final class` would be contradictory
- **None of the three `getMessage` calls** that reference `key.value` need `.reveal` — the raw credential adds zero diagnostic value in any of them

---

## Design Decisions (from review)

### D1: Convert `WorkspaceKey` to `final class`, not just override `toString`

The ADR proposes `Secret[A]` as a generic `final class` wrapper. `WorkspaceKey` is the only credential in app code. Rather than creating a general `Secret[A]` that wraps `WorkspaceKey`, convert `WorkspaceKey` itself to a `final class` — it *is* the secret type.

**Rationale:** Creating `Secret[A]` and then not applying it to `WorkspaceKey` (the only candidate) would make the ADR a policy document with zero applications. The `final class` conversion is ~40 lines across 6 files — negligible effort.

### D2: No `.reveal` in error `getMessage` — credential not needed

All three workspace error types currently print `key.value` in `getMessage`:

```scala
case class WorkspaceNotFound(key: WorkspaceKey) — "Workspace not found: ${key.value}"
case class WorkspaceExpired(key: WorkspaceKey, ...) — "Workspace expired: ${key.value}"
case class TreeNotInWorkspace(key: WorkspaceKey, treeId: TreeId) — "Tree ... not in workspace ${key.value}"
```

Assessment for each:

| Error type | Who consumes `getMessage` | Does the raw key add diagnostic value? | Decision |
|---|---|---|---|
| `WorkspaceNotFound` | Server logs only — HTTP boundary returns opaque 404 (A13), matched via `case _: WorkspaceNotFound` (key never inspected) | **No** — the request URL `/w/{key}/...` already identifies the workspace in access logs | Drop key from message |
| `WorkspaceExpired` | Server logs only — same opaque 404 (A13) | **No** — `createdAt` and `ttl` are the useful diagnostics, not the credential | Use `createdAt`/`ttl` instead |
| `TreeNotInWorkspace` | Server logs only — same opaque 404 (A13) | **No** — `treeId` is the interesting part; workspace is already in the URL | Drop key from message |

After the `final class` conversion, these messages would use `$key` (which prints `WorkspaceKey(***)`) rather than `key.reveal`. No call site needs the raw value.

### D3: `Config.Secret` for future infrastructure secrets — boundary with `WorkspaceKey`

`zio.Config.Secret` is ZIO's built-in type for loading sensitive config values (database passwords, API keys) from environment variables or config files. Its `toString` returns `"Secret(<redacted>)"`.

Currently unused — no config-loaded secrets exist. Documented as the pattern to follow when infrastructure secrets are added (e.g., SpiceDB pre-shared key, direct database connection).

**Why not use `Config.Secret` for `WorkspaceKey`?** Three reasons:

1. **Accessor shape:** `Config.Secret.value` returns `Chunk[Char]`, not `String`. Every call site (JSON codecs, Tapir codec, URL embedding, Iron validation) works with `String`. Converting back and forth adds friction with zero security benefit.
2. **`unapply` exists:** `Config.Secret` has a compiler-generated `unapply` that extracts the raw value. For config-loaded secrets this is acceptable (see D3a below). For `WorkspaceKey` — which flows through error types, pattern matches, and logging — it's a leakage vector.
3. **Design intent:** `Config.Secret` is for values loaded from config and consumed immediately. `WorkspaceKey` is generated at runtime and flows through the entire request lifecycle (endpoints → controllers → stores → frontend state → error types).

**Why is `unapply` acceptable on `Config.Secret` but not on `WorkspaceKey`?** (D3a)

The threat model differs:

| | `Config.Secret` | `WorkspaceKey` |
|---|---|---|
| **Where it's pattern-matched** | Config loading code only — `case Secret(raw) =>` in a config parser | Error handlers, controller logic, store lookups, test assertions |
| **Logging exposure** | Config code rarely logs; values consumed immediately | Error `getMessage`, string interpolation in controllers, test output |
| **Lifecycle** | Created once at startup, consumed, wiped | Created per-request, stored in maps, held in frontend `Var`, embedded in error types, serialised to JSON |
| **Number of call sites** | 1-2 (config layer) | 14+ across 10 files |

A stray `case Secret(raw) =>` in a config parser is low-risk — it's a single controlled call site that developers review. A stray `case WorkspaceKey(raw) =>` in an error handler or controller could silently embed the credential in a log line or exception message. The `final class` (no `unapply`) makes that pattern a **compile error**.

### D4: Exhaustive error encoding — separate from `WorkspaceKey` conversion

The `ErrorResponse.encode` refactoring (split `Throwable` match into typed `AppError` → `SimError`/`IrminError` inner matches + `-Wconf` compiler flag) is independently valuable. It can be done in the same PR or separately.

---

## Task Breakdown

### Task 1: Convert `WorkspaceKey` from `case class` to `final class`

**File:** `modules/common/src/main/scala/.../domain/data/iron/OpaqueTypes.scala` (line 218)

**Current:**
```scala
case class WorkspaceKey(value: String)

object WorkspaceKey:
  def generate: UIO[WorkspaceKey] =
    ZIO.succeed {
      val bytes = new Array[Byte](16)
      java.security.SecureRandom().nextBytes(bytes)
      WorkspaceKey(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }

  def fromString(s: String): Either[List[ValidationError], WorkspaceKey] =
    ValidationUtil.refineWorkspaceKey(s)

  given JsonEncoder[WorkspaceKey] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[WorkspaceKey] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceKey.fromString(s).left.map(_.mkString(", ")))
```

**Target:**
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

  def generate: UIO[WorkspaceKey] =
    ZIO.succeed {
      val bytes = new Array[Byte](16)
      java.security.SecureRandom().nextBytes(bytes)
      WorkspaceKey(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }

  def fromString(s: String): Either[List[ValidationError], WorkspaceKey] =
    ValidationUtil.refineWorkspaceKey(s)

  given JsonEncoder[WorkspaceKey] = JsonEncoder[String].contramap(_.reveal)
  given JsonDecoder[WorkspaceKey] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceKey.fromString(s).left.map(_.mkString(", ")))
```

**What changes:**
- `case class` → `final class` (no `copy`, no `unapply`, no product serialisation)
- `value` → `private val raw` + explicit `reveal` accessor
- Explicit companion `apply` (preserves `WorkspaceKey(...)` construction syntax)
- `toString` returns `"WorkspaceKey(***)"` instead of printing the credential
- Manual `equals`/`hashCode` (case class auto-generated these; final class does not)
- JSON codecs: `.value` → `.reveal`

### Task 2: Update Tapir codec

**File:** `modules/common/src/main/scala/.../http/codecs/IronTapirCodecs.scala` (line 120)

```scala
// .value → .reveal in the encode direction
given Codec[String, WorkspaceKey, CodecFormat.TextPlain] =
  Codec.string.mapDecode[WorkspaceKey](raw =>
    WorkspaceKey.fromString(raw).fold(
      errors => DecodeResult.Error(raw, ...),
      key => DecodeResult.Value(key)
    )
  )(_.reveal)  // was: _.value
```

**1 line changed.**

### Task 3: Update error `getMessage` — remove raw credential

**File:** `modules/common/src/main/scala/.../domain/errors/AppError.scala` (lines 49-61)

```scala
case class WorkspaceNotFound(key: WorkspaceKey) extends SimError {
  override def getMessage: String = "Workspace not found"
  // was: s"Workspace not found: ${key.value}"
  // key not needed — request URL already identifies workspace in access logs
}

case class WorkspaceExpired(key: WorkspaceKey, createdAt: Instant, ttl: JDuration) extends SimError {
  override def getMessage: String = s"Workspace expired (created: $createdAt, ttl: $ttl)"
  // was: s"Workspace expired: ${key.value}"
  // createdAt/ttl are the useful diagnostics, not the credential
}

case class TreeNotInWorkspace(key: WorkspaceKey, treeId: TreeId) extends SimError {
  override def getMessage: String = s"Tree ${treeId.value} not found in workspace"
  // was: s"Tree ${treeId.value} is not in workspace ${key.value}"
  // treeId is the diagnostic; workspace is already in the URL
}
```

**3 lines changed.** Note: `key` is still stored in the case class for pattern matching (e.g., `case WorkspaceNotFound(key) =>`) — just not printed.

### Task 4: Update frontend `WorkspaceState`

**File:** `modules/app/src/main/scala/app/state/WorkspaceState.scala` (line 55)

The `pushKeyToURL` method and `extractKeyFromURL` use `.value` to embed/extract the key in the URL hash. These become `.reveal` — this is a legitimate "I need the raw value to put in the URL" use case.

```scala
private def pushKeyToURL(key: WorkspaceKey): Unit =
  // key.value → key.reveal
```

**1-2 lines changed.**

### Task 5: Update tests

**File:** `modules/common/src/test/scala/.../domain/data/iron/WorkspaceKeySpec.scala`

Every test assertion that uses `.value` becomes `.reveal`:
```scala
key.value  →  key.reveal   // ~10 occurrences
```

**~10 lines changed.** Purely mechanical rename.

### Task 6: Exhaustive error encoding (independent)

**File:** `modules/common/src/main/scala/.../domain/errors/ErrorResponse.scala` (line 82)

Split the single `encode` match into typed inner matches:

```scala
def encode(error: Throwable): (StatusCode, ErrorResponse) = error match
  case e: AppError => encodeAppError(e)
  case _           => makeGeneralResponse()

private def encodeAppError(error: AppError): (StatusCode, ErrorResponse) = error match
  case e: SimError   => encodeSimError(e)
  case e: IrminError => encodeIrminError(e)

private def encodeSimError(error: SimError): (StatusCode, ErrorResponse) = error match
  case ValidationFailed(errors)                    => makeValidationResponse(errors)
  case AccessDenied(reason)                        => makeAccessDeniedResponse(reason)
  case RateLimitExceeded(ip, limit, window)        => makeRateLimitExceededResponse(ip, limit, window)
  case _: WorkspaceNotFound                        => makeWorkspaceOpaqueNotFoundResponse()
  case _: WorkspaceExpired                         => makeWorkspaceOpaqueNotFoundResponse()
  case _: TreeNotInWorkspace                       => makeWorkspaceOpaqueNotFoundResponse()
  case RepositoryFailure(reason)                   => makeRepositoryFailureResponse(reason)
  case SimulationFailure(id, cause)                => makeSimulationFailureResponse(id)
  case DataConflict(reason)                        => makeDataConflictResponse(reason)
  case VersionConflict(nodeId, expected, actual)   => makeVersionConflictResponse(nodeId, expected, actual)
  case MergeConflict(branch, details)              => makeMergeConflictResponse(branch, details)

private def encodeIrminError(error: IrminError): (StatusCode, ErrorResponse) = error match
  case IrminUnavailable(reason)                    => ...
  case IrminHttpError(status, body)                => ...
  case IrminGraphQLError(messages, path)           => ...
  case NetworkTimeout(operation, duration)         => ...
```

**File:** `build.sbt`

Add compiler flag for exhaustive match enforcement:
```scala
scalacOptions += "-Wconf:msg=match may not be exhaustive:error"
```

**~30 lines refactored** in ErrorResponse. **1 line** in build.sbt.

---

## Impact Summary

| File | Change | Lines |
|------|--------|-------|
| `OpaqueTypes.scala` | `case class` → `final class` + `reveal` + `equals`/`hashCode` | ~15 |
| `IronTapirCodecs.scala` | `.value` → `.reveal` | 1 |
| `AppError.scala` | Remove raw key from `getMessage` | 3 |
| `WorkspaceState.scala` | `.value` → `.reveal` | 1-2 |
| `WorkspaceKeySpec.scala` | `.value` → `.reveal` in assertions | ~10 |
| `ErrorResponse.scala` | Split into typed inner matches | ~30 |
| `build.sbt` | `-Wconf` flag | 1 |
| **Total** | | **~60 lines** |

### Unaffected (no changes needed)

| File/Area | Why unaffected |
|-----------|---------------|
| `WorkspaceStore` / `WorkspaceStoreLive` — all method signatures | Take `key: WorkspaceKey` as opaque parameter; never access `.value` |
| `WorkspaceController` | Passes key through to services; never inspects `.value` |
| `SSEController` | Same — passes key to `resolveTree` |
| `WorkspaceEndpoints` | Tapir `path[WorkspaceKey]` uses the Tapir codec (Task 2), not `.value` |
| `WorkspaceBootstrapResponse` / `WorkspaceRotateResponse` | `DeriveJsonCodec.gen` uses the companion `JsonEncoder`/`JsonDecoder` givens, not case class product derivation |
| `LECChartState` / `TreeViewState` (frontend) | Hold `StrictSignal[Option[WorkspaceKey]]` — never access `.value` |

---

## Verification

After implementation:

1. `sbt commonJVM/test` — WorkspaceKeySpec passes with `.reveal`
2. `sbt server/test` — all 290 server tests pass (WorkspaceStoreSpec, ErrorResponseSpec, SecurityHeadersInterceptorSpec)
3. `sbt app/fastLinkJS` — frontend compiles
4. Manual: `println(WorkspaceKey.generate)` prints `WorkspaceKey(***)`
5. Manual: JSON response still contains raw key string (via `reveal` in codec)
6. Manual: error logs for workspace 404s no longer contain the raw credential

---

## Not In Scope

| Item | Reason |
|------|--------|
| Generic `Secret[A]` wrapper type | `WorkspaceKey` *is* the secret type — no need for an additional layer of indirection |
| `Config.Secret` usage | No config-loaded secrets exist today; pattern documented for future use |
| `ZIO.Scope`-based secret lifecycle | No raw-byte secrets to wipe; pattern documented for future use |
| Layer 1/2 secrets (JWT, mTLS, passwords) | Handled by service mesh; structurally unreachable from app code |
