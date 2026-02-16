# ADR-021: Capability URLs for Unauthenticated Demo Access

**Status:** Amended  
**Date:** 2026-02-12 (amended 2026-02-16)  
**Tags:** security, capability-url, demo, access-control, ids  
**Related:** [ADR-012](./ADR-012.md) (Service Mesh Strategy), [ADR-018](./ADR-018-nominal-wrappers.md) (Nominal Wrappers)

> **Amendment (2026-02-16):** This ADR's concepts were implemented under different names. `ShareToken` → `WorkspaceKey`, `DemoStore` → `WorkspaceStore`, `/demo/*` routes → `/w/{key}/*` routes. The cryptographic properties (128-bit SecureRandom, base64url, 22-char) are preserved unchanged. See [IMPLEMENTATION-PLAN.md Phase W](./IMPLEMENTATION-PLAN.md) for the implemented design. The original text below is retained for historical context.

---

## Context

- Production auth is **externalized** to service mesh (Keycloak + OPA + Istio, ADR-012)
- A **public demo** is needed that allows tree creation and interaction without user management, passwords, or OAuth infrastructure
- ULIDs already carry **80 bits of randomness** per millisecond — sufficient entropy for unguessable tokens
- The pattern of "knowledge of URL = authorization" is a well-established **capability URL** model (W3C TAG, Google Docs share links)
- The demo must be **time-bounded** and **abuse-resistant** without adding operational complexity

---

## Decision

### 1. ShareToken as Capability Credential

Introduce a dedicated `ShareToken` wrapper — a 128-bit `SecureRandom` value — rather than exposing `TreeId` directly as the access credential:

```scala
case class ShareToken(value: String)  // 22-char base64url, 128-bit SecureRandom

object ShareToken:
  def generate: UIO[ShareToken] =
    ZIO.succeed {
      val bytes = new Array[Byte](16)  // 128 bits
      java.security.SecureRandom().nextBytes(bytes)
      ShareToken(java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes))
    }
```

`TreeId` remains internal (server-assigned ULID). `ShareToken` is the external-facing capability. This follows **least privilege**: leaking a `ShareToken` exposes one tree; leaking a `TreeId` could interact with internal APIs.

### 2. Demo Store with TTL Eviction

A `DemoStore` maps `ShareToken → (TreeId, Instant)` with automatic expiry:

```scala
trait DemoStore:
  def create(treeId: TreeId, ttl: Duration): UIO[ShareToken]
  def resolve(token: ShareToken): UIO[Option[TreeId]]
  def evictExpired: UIO[Int]
```

In-memory `TrieMap` implementation with a background reaper fiber (configurable interval, default 5 minutes). Default TTL: 24 hours.

### 3. Demo Endpoint Surface

Demo routes are **separate** from the authenticated API and scoped under `/demo`:

```
POST   /demo/trees                    → create tree, return ShareToken
GET    /demo/t/{shareToken}           → resolve token, return tree
POST   /demo/t/{shareToken}/nodes/lec-multi  → LEC curves
DELETE /demo/t/{shareToken}           → delete tree (optional)
```

The `GET /risk-trees` (list-all) endpoint is **not exposed** on the demo surface — no enumeration possible.

### 4. Rate Limiting & Abuse Prevention

- **Creation rate limit:** Max N trees per IP per hour (configurable, default 10)
- **HTTPS-only:** Prevents URL sniffing on the wire
- **No Referer leakage:** `Referrer-Policy: no-referrer` header on demo responses
- **Cache-Control:** `no-store` on demo responses to prevent proxy caching of tokens
- In production: Istio rate limiting at ingress; for standalone demo: simple in-app `Ref`-based counter

### 5. PRNG Upgrade for Capability Tokens

`ShareToken` uses `java.security.SecureRandom` (not `java.util.Random`). This is critical — capability tokens **must** be cryptographically random. `TreeId` ULIDs can continue using standard `Random` since they are not exposed as credentials.

---

## Code Smells

### ❌ Exposing TreeId as Capability

```scala
// BAD: TreeId is the access credential — leaks internal identifier
val demoUrl = s"/demo/trees/$treeId"
// TreeId uses java.util.Random (not cryptographic)
// TreeId prefix is time-based (predictable)
```

```scala
// GOOD: Separate ShareToken with SecureRandom
val token = ShareToken.generate
demoStore.create(treeId, ttl = 24.hours).map { token =>
  s"/demo/t/${token.value}"
}
```

### ❌ Demo Routes on Authenticated API Surface

```scala
// BAD: Demo shares routes with production — mesh may reject unauthenticated
val demoTree = getRiskTreeEndpoint  // requires JWT in production

// GOOD: Separate /demo prefix — mesh policy skips JWT for /demo/*
val demoTree = demoGetTreeEndpoint  // under /demo/t/{token}
```

### ❌ No TTL on Demo Trees

```scala
// BAD: Demo trees live forever — resource leak, data accumulation
demoStore.create(treeId)  // no expiry

// GOOD: Mandatory TTL with background eviction
demoStore.create(treeId, ttl = 24.hours)
// Background fiber runs evictExpired every 5 minutes
```

### ❌ List-All on Demo Surface

```scala
// BAD: Enumeration endpoint on demo — reveals all active trees
GET /demo/trees  → List[ShareToken]

// GOOD: No enumeration — capability URLs only
// Only POST (create) and GET-by-token (resolve) exposed
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `ShareToken` | `common/.../domain/data/ShareToken.scala` — nominal wrapper with `SecureRandom` factory |
| `DemoStore` | `server/.../service/demo/DemoStore.scala` — `TrieMap` + TTL + reaper fiber |
| `DemoEndpoints` | `common/.../endpoints/DemoEndpoints.scala` — Tapir endpoint definitions under `/demo` |
| `DemoController` | `server/.../controller/DemoController.scala` — wires endpoints to `DemoStore` + `RiskTreeService` |
| `DemoConfig` | `server/.../config/DemoConfig.scala` — TTL, rate limit, reaper interval |
| Istio policy | `AuthorizationPolicy` — skip JWT validation for `/demo/*` paths |

---

## Mesh Integration (ADR-012)

In the service mesh setup, demo routes require an Istio `AuthorizationPolicy` exception:

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: demo-public-access
  namespace: register
spec:
  action: ALLOW
  rules:
  - to:
    - operation:
        paths: ["/demo/*"]
  # No JWT requirement — capability URL is the credential
```

All other routes remain protected by the Keycloak JWT + OPA pipeline defined in ADR-012.

---

## References

- W3C TAG: [Good Practices for Capability URLs](https://www.w3.org/TR/capability-urls/)
- [ADR-012: Service Mesh Strategy](./ADR-012.md)
- [ADR-018: Nominal Wrappers](./ADR-018-nominal-wrappers.md)
