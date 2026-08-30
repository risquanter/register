# ADR-024: Externalized Authorization — Application as Pure Policy Enforcement Point

**Status:** Accepted  
**Date:** 2026-02-19  
**Tags:** authorization, security, architecture, spicedb, pep

---

## Context

- Authorization decisions ("can user X do Y on resource Z?") are externalized to SpiceDB (ADR-012)
- Writing authorization data (granting/revoking relationships) is a privileged administrative operation, not a product feature of this application
- Mixing Policy Enforcement (checking access) with Policy Administration (writing access rules) in one service increases attack surface and creates unauditable side-channels
- Access is administered by ops tooling (CI/CD job, `zed` CLI, Keycloak admin); the application has no self-service access-management UI
- PEP/PDP/PAP separation is the canonical pattern established in Google Zanzibar (2019), XACML 3.0, and zero-trust reference architectures

---

## Decision

### 1. App is a Pure PEP — Read-Only, Fail-Closed

The application calls SpiceDB to **read** authorization state. It never writes
authorization data, and it exposes no HTTP route that writes it. Any future
self-service access management is a separate administrative service (a dedicated
PAP), distinct from this application's codebase and deployment.

```scala
// The complete AuthorizationService interface — no grant(), no revoke()
trait AuthorizationService:
  def check[P <: Permission](user: UserId.Authenticated, permission: P, resource: ResourceRef): IO[AuthError, Checked[P]]
  // Returns a Checked[P] proof token on success; callers bind it via `given` in for-comprehensions.
  // Fails with AuthError.Forbidden if SpiceDB returns false, and with
  // AuthError.ServiceUnavailable on connectivity failure — deny, never allow.

  def listAccessible(user: UserId.Authenticated, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]]
```

`check()` fails the ZIO effect (rather than returning `false`) so a caller
cannot grant access by ignoring the result — the only way past it is a success:

```scala
authorizationService.check(user.userId, Permission.DesignWrite, resource)
  .flatMap(_ => handleRequest(...))   // only reached when check() succeeds (= allowed)
```

### 2. PAP is Ops Tooling, Not the App

SpiceDB tuples are written exclusively by external tooling. Account-wide
revocation is a Keycloak operation, not an app endpoint:

| Path | Mechanism | When |
|------|-----------|------|
| Org/team provisioning | `AuthzProvisioning` CI/CD job (in-cluster runner) | Config change merged to main |
| Individual access changes | `zed` CLI via ops service account | On-demand admin operation with audit log |
| Emergency bulk revocation | Audited `zed` CLI runbook | Account termination / security incident |
| Account-wide revocation | Keycloak: disable user | Stops token issuance; existing tokens expire within the ≤ 5 min TTL |

Disabling a user in Keycloak is the primary revocation mechanism: no new tokens
issue, existing tokens expire within their short TTL, and the SpiceDB tuples
remain but are unreachable without a valid JWT. For immediate effect, the `zed`
CLI break-glass runbook deletes tuples directly.

### 3. SpiceDB Receives `userId` Only

SpiceDB evaluates the relationship graph and needs no JWT role claims. The only
identifier `check()` passes is the `userId` (JWT `sub` claim):

```scala
authorizationService.check(
  user       = userContext.userId,          // ← only this from UserContext
  permission = Permission.DesignWrite,
  resource   = ResourceRef("risk_tree", treeId)
)
// userContext.roles is NOT passed — a role claim without a matching SpiceDB
// relation on the specific resource is denied. The relationship graph wins.
```

### 4. Resource Lifecycle Writes — Not PAP

Recording creator ownership at resource creation is system-initiated, not
user-initiated — categorically distinct from PAP. Gate: does a _user request_
drive the SpiceDB write? Yes (user delegates access) → PAP, ops tooling only.
No (system records the creator at creation time) → lifecycle write, app is correct.

```scala
// BootstrapProvisioner — separate trait, injected only into the bootstrap handler
for
  ws <- workspaceStore.create(req)
  _  <- bootstrapProvisioner.recordOwnership(userId, ws.id)
  //    writes: workspace:{id}#owner_user@user:{sub}
  //    AuthorizationService is NOT used here — the PEP stays read-only
yield ws
```

Service-account write scope is enforced at provisioning: `owner_user` and
`owner_team` on `workspace` only; `editor`, `analyst`, `viewer`, and all other
relations are prohibited.

---

## Code Smells

### ❌ Grant/Revoke on AuthorizationService

```scala
// BAD: App writes authorization data — PAP concern smuggled into PEP
trait AuthorizationService:
  def grant(user: UserId, relation: String, resource: ResourceRef): IO[AuthError, Unit]
  def revoke(user: UserId, relation: String, resource: ResourceRef): IO[AuthError, Unit]

// GOOD: App reads authorization state only
trait AuthorizationService:
  def check[P <: Permission](user: UserId.Authenticated, permission: P, resource: ResourceRef): IO[AuthError, Checked[P]]
  def listAccessible(user: UserId.Authenticated, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]]
```

### ❌ Startup Seeding

```scala
// BAD: App writes authorization data at startup — PAP concern in app runtime
object Main extends ZIOAppDefault:
  def run = for
    _ <- authService.grant(adminId, "owner", rootWorkspace)  // App acting as PAP
    _ <- server.start
  yield ()

// GOOD: App starts clean — the authorization graph is pre-provisioned by the CI job
object Main extends ZIOAppDefault:
  def run = server.start
```

### ❌ Fail-Open Check

```scala
// BAD: SpiceDB connectivity failure defaults to allow
authService.check(user, permission, resource)
  .fold(_ => (), identity)  // error → silently allow

// GOOD: Any failure is a deny — check() itself fails the effect
authService.check(user, permission, resource)
  .flatMap(_ => proceed())  // unreachable on deny or error
```

### ❌ Lifecycle Write on AuthorizationService

```scala
// BAD: Resource lifecycle write added to PEP — blurs the PAP boundary
trait AuthorizationService:
  def check(...)
  def recordOwnership(userId: UserId, wsId: WorkspaceId): IO[AuthError, Unit]  // ← PAP leak

// GOOD: Separate trait scoped to the one handler that needs it
trait BootstrapProvisioner:
  def recordOwnership(userId: UserId.Authenticated, wsId: WorkspaceId): IO[AuthError, Unit]
// AuthorizationService unchanged — zero write methods
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
- [AUTHORIZATION-PLAN.md](./plans/AUTHORIZATION-PLAN.md) — Layer 2 design, SpiceDB provisioning, ops paths
- Google Zanzibar: Google's Consistent, Global Authorization System (2019) — reference for PDP/PAP separation
- XACML 3.0 (OASIS) — formalizes PEP/PDP/PAP/PIP roles
