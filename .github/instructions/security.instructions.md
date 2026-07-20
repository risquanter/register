---
applyTo: "**/*.scala"
---

# Credential Handling & Authorization Boundaries (ADR-022, ADR-024)

## Credential types — apply this checklist before writing the first line

`case class` is the Scala developer's default and is wrong for credentials.
The compiler generates `copy(raw = exposed)`, `unapply(secret)`, and a `toString`
that prints the raw value — each is a distinct leakage path.

Before writing any new credential type, satisfy all of R1–R8:

| # | Requirement | Why |
|---|---|---|
| R1 | `final class` (not `case class`) | Prevents `copy`, `unapply`, product serialisation |
| R2 | `private val` for the raw field | Field inaccessible without explicit opt-in |
| R3 | Redacted `toString` returning `"TypeName(***)"`  | Protects against `println`, string interpolation, stack traces |
| R4 | Explicit `.reveal()` method | Call sites are visible in code review (`grep reveal`) |
| R5 | Iron-validated internal type | Carries validation proof; construction requires a refined value |
| R6 | Manual `equals`/`hashCode` | Case-class auto-generation is lost; must be explicitly re-provided |
| R7 | Companion `fromString` returning `Either[List[ValidationError], T]` | Canonical validated entry point |
| R8 | No generic JSON codec inherited from a base | Serialisation must be opt-in per credential type |

Reference implementation: `WorkspaceKeySecret` in the codebase satisfies all eight.

## Authorization boundary (ADR-024)

The application is a **pure PEP (Policy Enforcement Point)**. It reads authorization
state. It never writes authorization data.

```scala
// ✅ The complete interface — check only, returns Checked[P] proof token
trait AuthorizationService:
  def check[P <: Permission](user: UserId.Authenticated, permission: P, resource: ResourceRef): IO[AuthError, Checked[P]]
  // Checked[P] is bound via `given` in controller for-comprehensions.
  // Protected service methods require `using Checked[Permission]` — missing proof is a compile error.
  def listAccessible(user: UserId.Authenticated, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]]

// ❌ NEVER — app never writes tuples to SpiceDB
def grant(user: UserId, permission: Permission, resource: ResourceRef): IO[AuthError, Unit]
def revoke(user: UserId, permission: Permission, resource: ResourceRef): IO[AuthError, Unit]
```

SpiceDB tuple writes are exclusively ops tooling (CI/CD, `zed` CLI). Adding
`grant()` or `revoke()` to the `AuthorizationService` interface is a Decision
Trigger — stop and ask.

## JWT validation

JWT validation belongs to the service mesh (Istio waypoint), never to application
code. Application code receives decoded claims via the `x-jwt-claims` header.

## Capability URL generation

`SecureRandom` for all capability URL generation.
`scala.util.Random` is not cryptographically secure — never use it for tokens or
capability identifiers (ADR-021).

## Error messages

Error messages must never contain secrets, PII, or internal paths.
Typed error channels (`ZIO` error channel, sealed `AppError` hierarchy) over
`getMessage` string matching (ADR-010).

## Internal identifiers — never accepted as input, never returned as output (hard rule)

`WorkspaceId` (and any future internal identifier that participates in a
storage path, branch name, or other server-side scoping decision) must never
cross the client boundary in **either** direction:

- **Output:** must never appear in a JSON response body, header, error
  message, or any other client-visible surface. No `case class` response
  field, no log line returned to a client, no query-string echo.
- **Input:** no Tapir endpoint may accept a bare `WorkspaceId` as a path
  segment, query parameter, header, or JSON body field — not even on an
  endpoint that also separately checks a capability. The correct pattern is
  what every existing endpoint already does: accept `WorkspaceKeySecret`,
  derive `WorkspaceId` server-side via `WorkspaceStore.resolve`. Accepting the
  ID directly is an enumeration oracle **even if it is never echoed back** —
  any endpoint that behaves observably differently for a valid vs. invalid ID
  (a different status code, error shape, or timing) lets an attacker script
  through the ID space with no need to ever see the value returned. This is
  also the stronger, recommended fix, not merely a stricter one: OWASP's IDOR
  Prevention guidance is to never accept a caller-supplied object identifier
  when the caller's own identity already determines the correct scope — not
  "accept it and check ownership every request," which depends on that check
  being present and correct on every call site forever. Since no current or
  planned endpoint has a legitimate reason to accept a bare `WorkspaceId`,
  this costs nothing today and forecloses the failure mode permanently.

This is a hard rule, not a per-endpoint judgement call, because:

- Authorization in this system is entirely capability-based — possessing a
  valid `WorkspaceKeySecret` grants access, never knowledge of a
  `WorkspaceId`. That invariant is what keeps any endpoint accepting a
  cross-tenant-shaped reference (e.g. the `X-Active-Branch` header,
  2026-07-20 review) narrow rather than exploitable: an attacker needs
  another workspace's ID and today has no way to obtain one, because nothing
  returns it. Exposing it in even one legitimate-looking place (an audit log,
  a "list my workspaces" feature, telemetry) hands a future feature — one
  that does not exist yet and whose author cannot anticipate this one — the
  missing ingredient to target another tenant. Unexposed data cannot be
  repurposed by a feature that has not been designed yet; exposed data can.
- `WorkspaceId` is a ULID, not a `SecureRandom` capability token — it encodes
  a timestamp and is not designed to resist guessing. Its safety today comes
  entirely from never being returned, not from its own entropy. Exposing it
  converts a value that is safe-because-absent into one whose safety would
  depend on brute-force resistance it was never built to provide.
- It blurs the deliberate boundary between `WorkspaceKeySecret` (the one
  bearer capability, ADR-022 R1–R8) and `WorkspaceId` (an internal lookup
  key). The more places `WorkspaceId` legitimately appears in responses, the
  more it looks like a normal, safe-to-use value — inviting the exact mistake
  `WorkspaceStore.resolveById` stands as a warning against (resolving a
  workspace from a bare ID, no capability check).

If a client-facing feature genuinely needs a stable per-workspace reference
(e.g. a "my workspaces" list), it must expose something that carries no
server-side scoping power on its own — `WorkspaceKeySecret` itself (already
how `WorkspaceBootstrapResponse`/`WorkspaceRotateResponse` work), never the
raw `WorkspaceId`. Any new response field, log line, or header that would
return a `WorkspaceId` to a client is a Decision Trigger — stop and ask.
