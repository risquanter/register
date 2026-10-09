# ADR-021: Capability URLs for Workspace Access

**Status:** Amended  
**Date:** 2026-02-12 (amended 2026-02-16)  
**Tags:** security, capability-url, access-control, ids  
**Related:** [ADR-012](./ADR-012.md) (Service Mesh Strategy), [ADR-018](./ADR-018-nominal-wrappers.md) (Nominal Wrappers), [ADR-022](./ADR-022-secret-handling.md) (Secret & Credential Handling)

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

A dedicated `WorkspaceKeySecret` type — a 128-bit `SecureRandom` value, base64url encoded to 22 characters — serves as the external-facing capability credential. Its type internals — `final class`, Iron-validated, redacted `toString`, the full R1–R8 requirements checklist — are defined in [ADR-022](./ADR-022-secret-handling.md).

`TreeId` remains internal (server-assigned ULID). `WorkspaceKeySecret` is the external-facing capability. This follows **least privilege**: leaking a `WorkspaceKeySecret` exposes one workspace's trees; leaking a `TreeId` could interact with internal APIs.

### 2. WorkspaceStore with TTL Eviction

A `WorkspaceStore` maps `WorkspaceKeySecret → Workspace` (containing tree list, creation time, TTL) with automatic expiry:

```scala
// Protected methods additionally require `using Checked[Permission]` (ADR-030);
// `resolve` is the Layer 0 capability gate and is the one call that precedes it.
trait WorkspaceStore:
  def create(seedEntityId: Option[SeedEntityId]): IO[AppError, WorkspaceKeySecret]
  def addTree(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Unit]
  def resolve(key: WorkspaceKeySecret): IO[AppError, WorkspaceRecord]
  def belongsTo(key: WorkspaceKeySecret, treeId: TreeId): IO[AppError, Boolean]
  def listTrees(key: WorkspaceKeySecret): IO[AppError, List[TreeId]]
  def checkTreeCapacity(key: WorkspaceKeySecret): IO[AppError, Unit]
  def delete(key: WorkspaceKeySecret): IO[AppError, Unit]
  def rotate(key: WorkspaceKeySecret): IO[AppError, WorkspaceKeySecret]
  def evictExpired: UIO[List[WorkspaceRecord]]
```

There is no by-identifier lookup. Every method takes the capability key, so no
entry point exists that names a workspace without proving the caller holds its
credential.

In-memory `Ref[Map[WorkspaceKeyHash, WorkspaceRecord]]` implementation with a background reaper fiber (configurable interval, default 5 minutes). The raw key is never stored: the map is keyed by its SHA-256 digest. Default absolute TTL 72 hours, idle timeout 1 hour; either expiring is enough.

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

- **Creation rate limit: an edge concern, and the application holds none.**
  `POST /workspaces` is anonymous in every authorization mode — Layer 0 has no
  JWT to require, so the mesh admits the request unauthenticated — so this
  endpoint does need a limit. That limit belongs at the ingress gateway, and the
  application deliberately implements none.
  The reason is that only the component holding the connection knows who is
  calling. Anything further in has to be told, through a forwarded-header
  convention that must be kept in agreement with the deployment's topology by
  hand; a wrong value there silently counts an identity the caller chooses, and
  the limit stops holding with no error and no log line. An application-level
  per-address limit was implemented and removed for exactly that reason.
  **This is a known, unclosed gap:** no rate limit exists at the gateway today,
  so workspace creation is currently unbounded. Building it is required work in
  the `register-infra` repository.
  A per-credential limit is a different question and would legitimately sit in
  the application, because the workspace key is a value the application holds
  and the edge cannot see (`docs/dev/TODO.md` item 42).
- **HTTPS-only:** Prevents URL sniffing on the wire
- **No Referer leakage:** `Referrer-Policy: no-referrer` header on workspace responses
- **Cache-Control:** `no-store` on workspace responses to prevent proxy caching of keys

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
// Workspace created with configurable TTL (default 72h) and idle timeout (1h)
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
| Workspace endpoints | `common/.../http/endpoints/WorkspaceLifecycleEndpoints.scala`, `WorkspaceTreeEndpoints.scala`, `WorkspaceAnalysisEndpoints.scala`, `WorkspaceQueryEndpoints.scala` — Tapir endpoint definitions under `/w/{key}`, split by concern |
| Workspace controllers | `server/.../http/controllers/WorkspaceLifecycleController.scala`, `WorkspaceTreeController.scala`, `WorkspaceAnalysisController.scala` — wire those endpoints to `WorkspaceStore` + `RiskTreeService` |
| `WorkspaceConfig` | `server/.../configs/WorkspaceConfig.scala` — TTL, rate limit, reaper interval |
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
- [ADR-022: Secret & Credential Handling](./ADR-022-secret-handling.md) — `WorkspaceKeySecret` as `final class` with R1–R8 requirements
