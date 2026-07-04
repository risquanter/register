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
