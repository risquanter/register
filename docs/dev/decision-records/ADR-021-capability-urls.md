# ADR-021: Capability URLs for Workspace Access

**Status:** Amended  
**Date:** 2026-02-12 (amended 2026-02-16)  
**Tags:** security, capability-url, access-control, ids  
**Related:** [ADR-012](./ADR-012.md) (Service Mesh Strategy), [ADR-018](./ADR-018-nominal-wrappers.md) (Nominal Wrappers), [ADR-022](./ADR-022-secret-handling.md) (Secret Handling)

---

## Context

- Production auth is **externalized** to service mesh (Keycloak + OPA + Istio, ADR-012)
- A **workspace model** is needed that allows tree creation and interaction without user management, passwords, or OAuth infrastructure (Layer 0)
- ULIDs already carry **80 bits of randomness** per millisecond — sufficient entropy for unguessable tokens
- The pattern of "knowledge of URL = authorization" is a well-established **capability URL** model (W3C TAG, Google Docs share links)
- Workspaces must be **time-bounded** and **abuse-resistant** without adding operational complexity

---

## Decision

### 1. WorkspaceKeySecret as Capability Credential

A dedicated `WorkspaceKeySecret` type — a 128-bit `SecureRandom` value, base64url encoded to 22 characters — serves as the external-facing capability credential:

```scala
// Iron-refined type alias — validation proof carried through to the class
type WorkspaceKeyStr = String :| Match["^[A-Za-z0-9_-]{22}$"]

final class WorkspaceKeySecret private (private val raw: WorkspaceKeyStr):
  def reveal: String = raw
  override def toString: String = "WorkspaceKeySecret(***)"
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case wk: WorkspaceKeySecret => raw == wk.raw
    case _                      => false

object WorkspaceKeySecret:
  def apply(value: WorkspaceKeyStr): WorkspaceKeySecret = new WorkspaceKeySecret(value)

  // Thread-safe shared instance — avoids repeated /dev/urandom seeding per call
  private val rng: java.security.SecureRandom = new java.security.SecureRandom()

  def generate: UIO[WorkspaceKeySecret] =
    ZIO.succeed {
      val bytes = new Array[Byte](16)  // 128 bits
      rng.nextBytes(bytes)
      val encoded = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
      new WorkspaceKeySecret(encoded.refineUnsafe[Match["^[A-Za-z0-9_-]{22}$"]])
    }
```

`TreeId` remains internal (server-assigned ULID). `WorkspaceKeySecret` is the external-facing capability. This follows **least privilege**: leaking a `WorkspaceKeySecret` exposes one workspace's trees; leaking a `TreeId` could interact with internal APIs.

**Secret handling:** `WorkspaceKeySecret` is a `final class` with Iron-validated internals and redacted `toString` (ADR-022). See [ADR-022](./ADR-022-secret-handling.md) for the full requirements checklist (R1–R8).

### 2. WorkspaceStore with TTL Eviction

A `WorkspaceStore` maps `WorkspaceKeySecret → Workspace` (containing tree list, creation time, TTL) with automatic expiry:

```scala
trait WorkspaceStore:
  def create(): UIO[WorkspaceKeySecret]
  def addTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit]
  def resolve(key: WorkspaceKeySecret): IO[AppError, Workspace]
  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean]
  def listTrees(key: WorkspaceKeySecret): IO[AppError, List[TreeId]]
  def delete(key: WorkspaceKeySecret): IO[AppError, Unit]
  def rotate(key: WorkspaceKeySecret): IO[AppError, WorkspaceKeySecret]
  def evictExpired: UIO[Int]
```

In-memory `Ref[Map[WorkspaceKeySecret, Workspace]]` implementation with a background reaper fiber (configurable interval, default 5 minutes). Default TTL: 24 hours.

### 3. Workspace Endpoint Surface

All workspace routes are scoped under `/w/{key}`:

```
POST   /w                                    → create workspace + tree, return WorkspaceKeySecret
GET    /w/{key}/risk-trees                    → list trees in workspace
POST   /w/{key}/risk-trees                    → create tree in workspace
GET    /w/{key}/risk-trees/{treeId}            → get tree (validates ownership)
PUT    /w/{key}/risk-trees/{treeId}            → update tree
DELETE /w/{key}/risk-trees/{treeId}            → delete tree
GET    /w/{key}/events/tree/{treeId}           → SSE stream (A15: workspace-scoped)
POST   /w/{key}/rotate                         → rotate workspace key
DELETE /w/{key}                                → delete workspace + cascade trees
```

### 4. Rate Limiting & Abuse Prevention

- **Creation rate limit:** Max N workspaces per IP per hour (configurable, default 10)
- **HTTPS-only:** Prevents URL sniffing on the wire
- **No Referer leakage:** `Referrer-Policy: no-referrer` header on workspace responses
- **Cache-Control:** `no-store` on workspace responses to prevent proxy caching of keys
- In production: Istio rate limiting at ingress; for standalone: simple in-app `Ref`-based counter

### 5. PRNG — Cryptographic Randomness Required

`WorkspaceKeySecret` uses `java.security.SecureRandom` (not `java.util.Random`). A shared instance is used across calls for efficiency (thread-safe per JDK docs). This is critical — capability tokens **must** be cryptographically random. `TreeId` ULIDs can continue using standard `Random` since they are not exposed as credentials.

---

## Code Smells

### ❌ Exposing TreeId as Capability

```scala
// BAD: TreeId is the access credential — leaks internal identifier
val url = s"/trees/$treeId"
// TreeId uses java.util.Random (not cryptographic)
// TreeId prefix is time-based (predictable)
```

```scala
// GOOD: Separate WorkspaceKeySecret with SecureRandom
workspaceStore.create().map { key =>
  s"/w/${key.reveal}/risk-trees"
}
```

### ❌ Workspace Routes on Authenticated API Surface

```scala
// BAD: Workspace shares routes with production — mesh may reject unauthenticated
val tree = getRiskTreeEndpoint  // requires JWT in production

// GOOD: Separate /w/{key} prefix — mesh policy skips JWT for /w/*
val tree = workspaceGetTreeEndpoint  // under /w/{key}/risk-trees/{treeId}
```

### ❌ No TTL on Workspaces

```scala
// BAD: Workspaces live forever — resource leak, data accumulation
workspaceStore.create()  // no expiry

// GOOD: Mandatory TTL with background eviction
// Workspace created with configurable TTL (default 24h)
// Background reaper fiber runs evictExpired every 5 minutes
```

### ❌ List-All on Workspace Surface

```scala
// BAD: Enumeration endpoint reveals all active workspaces
GET /w  → List[WorkspaceKeySecret]

// GOOD: No enumeration — capability URLs only
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `WorkspaceKeySecret` | `common/.../domain/data/iron/OpaqueTypes.scala` — `final class` with Iron-validated `WorkspaceKeyStr` internal, `SecureRandom` factory, redacted `toString` (ADR-022) |
| `WorkspaceStore` | `server/.../services/workspace/WorkspaceStore.scala` — trait + `Ref[Map]` + TTL + reaper fiber |
| `WorkspaceEndpoints` | `common/.../http/endpoints/WorkspaceEndpoints.scala` — Tapir endpoint definitions under `/w/{key}` |
| `WorkspaceController` | `server/.../http/controllers/WorkspaceController.scala` — wires endpoints to `WorkspaceStore` + `RiskTreeService` |
| `WorkspaceConfig` | `server/.../config/WorkspaceConfig.scala` — TTL, rate limit, reaper interval |
| Istio policy | `AuthorizationPolicy` — skip JWT validation for `/w/*` paths |

---

## Mesh Integration (ADR-012)

In the service mesh setup, workspace routes require an Istio `AuthorizationPolicy` exception:

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: workspace-public-access
  namespace: register
spec:
  action: ALLOW
  rules:
  - to:
    - operation:
        paths: ["/w/*"]
  # No JWT requirement — capability URL is the credential
```

All other routes remain protected by the Keycloak JWT + OPA pipeline defined in ADR-012.

---

## References

- W3C TAG: [Good Practices for Capability URLs](https://www.w3.org/TR/capability-urls/)
- [ADR-012: Service Mesh Strategy](./ADR-012.md)
- [ADR-018: Nominal Wrappers](./ADR-018-nominal-wrappers.md)
- [ADR-022: Secret Handling](./ADR-022-secret-handling.md) — `WorkspaceKeySecret` as `final class` with R1–R8 requirements
