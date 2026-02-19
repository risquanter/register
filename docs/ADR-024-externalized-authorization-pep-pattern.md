# ADR-024: Externalized Authorization — Application as Pure Policy Enforcement Point

**Status:** Accepted  
**Date:** 2026-02-19  
**Tags:** authorization, security, architecture, spicedb, pep

---

## Context

- Authorization decisions ("can user X do Y on resource Z?") are externalized to SpiceDB (ADR-012)
- Writing authorization data (granting/revoking relationships) is a privileged administrative operation, not a product feature of this application
- Mixing Policy Enforcement (checking access) with Policy Administration (writing access rules) in the same service increases the attack surface and creates unauditable side-channels
- The application has no self-service access management UI; access is administered by ops tooling (CI/CD job, `zed` CLI, Keycloak admin)
- This PEP/PDP/PAP separation is the canonical pattern established in Google Zanzibar (2019), XACML 3.0, and all major zero-trust reference architectures

---

## Decision

### 1. App is PEP Only — No Tuple Writes

The application calls SpiceDB to **read** authorization state. It never writes authorization data.

```scala
// The complete AuthorizationService interface — no grant(), no revoke()
trait AuthorizationService:
  def check(user: UserId, permission: Permission, resource: ResourceRef): IO[AuthError, Unit]
  // Fails with AuthError.Forbidden if SpiceDB returns false — fail-closed by design.
  // No grant() / revoke(): app is a pure PEP; tuple writes are ops-path only.

  def listAccessible(user: UserId, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]]
  // For listing resources a user can access (e.g. "show my workspaces").
```

### 2. PAP is Ops Tooling, Not the App

SpiceDB tuples are written exclusively by external tooling:

| Path | Mechanism | When |
|------|-----------|------|
| Org/team provisioning | `AuthzProvisioning` CI/CD job | Config change merged to main |
| Individual access changes | `zed` CLI via ops service account | On-demand admin operation with audit log |
| Emergency bulk revocation | Audited `zed` CLI runbook | Account termination / security incident |

### 3. Keycloak Handles Account-Wide Revocation

Disabling a user in Keycloak stops token issuance immediately. Combined with short access token TTLs (≤ 5 min), this is the primary revocation mechanism — no app-level revocation endpoint is needed.

```
Keycloak admin: disable user bob
  → No new access tokens issued
  → Existing tokens expire within TTL window
  → SpiceDB tuples remain but are unreachable (no valid JWT to present)
  → For immediate effect: zed CLI bulk-delete per ops runbook (M3 break-glass)
```

### 4. Fail-Closed by Default

`check()` fails the ZIO effect with `AuthError.Forbidden` (not returns `false`) so callers cannot accidentally grant access by ignoring the return value. SpiceDB connectivity failure also fails with `AuthError.ServiceUnavailable` — deny, not allow.

```scala
// Fail-closed: callers simply flatMap — denied and service-unavailable both fail the effect
authorizationService.check(user.userId, Permission.DesignWrite, resource)
  .flatMap(_ => handleRequest(...))   // only reached if check() succeeds (= allowed)
```

### 5. No App Endpoints for Grant/Revoke

No HTTP route in the application exposes tuple write operations. Any future self-service access management capability is a separate administrative service (a dedicated PAP), distinct from this application's codebase and deployment.

---

## Code Smells

### ❌ Grant/Revoke on AuthorizationService

```scala
// BAD: App writes authorization data — PAP concern smuggled into PEP
trait AuthorizationService:
  def grant(user: UserId, relation: String, resource: ResourceRef): IO[AuthError, Unit]
  def revoke(user: UserId, relation: String, resource: ResourceRef): IO[AuthError, Unit]
```

```scala
// GOOD: App reads authorization state only
trait AuthorizationService:
  def check(user: UserId, permission: Permission, resource: ResourceRef): IO[AuthError, Unit]
  def listAccessible(user: UserId, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]]
```

### ❌ Startup Seeding

```scala
// BAD: App writes authorization data at startup — PAP concern in app runtime
object Main extends ZIOAppDefault:
  def run = for
    _ <- authService.grant(adminId, "owner", rootWorkspace)  // App acting as PAP
    _ <- server.start
  yield ()
```

```scala
// GOOD: App starts clean — authorization graph is pre-provisioned by CI job
object Main extends ZIOAppDefault:
  def run = server.start
```

### ❌ Fail-Open Check

```scala
// BAD: SpiceDB connectivity failure defaults to allow
authService.check(user, permission, resource)
  .fold(_ => (), identity)  // error → silently allow
```

```scala
// GOOD: Any failure is a deny — check() itself fails the effect
authService.check(user, permission, resource)
  .flatMap(_ => proceed())  // unreachable on deny or error
```

---

## Implementation

| Location | Role |
|----------|------|
| `AuthorizationService` trait | PEP interface — `check` + `listAccessible` only |
| `AuthorizationServiceSpiceDB` | SpiceDB HTTP adapter — reads only, no write calls |
| `AuthzProvisioning` CI job | PAP — writes org/team/workspace tuples |
| `zed` CLI (ops) | PAP — individual access changes, break-glass |
| Keycloak admin | Account-wide revocation — disables token issuance |

---

## References

- [ADR-012: Service Mesh Strategy](./ADR-012.md) — mesh validates JWT; app extracts claims only
- [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) — Layer 2 design, SpiceDB provisioning, ops paths
- Google Zanzibar: Google's Consistent, Global Authorization System (2019) — reference for PDP/PAP separation
- XACML 3.0 (OASIS) — formalizes PEP/PDP/PAP/PIP roles
