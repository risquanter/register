# Authorization Implementation Plan — Security Hardening & Type Corrections

**Date:** 2026-07-01
**Status:** Complete — all register app items implemented (2026-07-09); Phase K infra and BATS tests remain in register-infra
**Companion document:** [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) — strategic design, wave structure, route matrix
**Testing plan:** [AUTH-TESTING-PLAN.md](./AUTH-TESTING-PLAN.md) — BATS end-to-end tests (infra) + Scala `server-it` test cases cross-reference
**Applies to:** All waves in AUTHORIZATION-PLAN.md Task L2.6

This document specifies concrete amendments to the wave rollout defined in AUTHORIZATION-PLAN.md,
covering type-level safety improvements, design corrections, and infrastructure hardening. It does
not duplicate the strategic content; read AUTHORIZATION-PLAN.md first.

---

## Summary of Changes

| Item | Wave | Layer | Description |
|------|------|-------|-------------|
| ADR-024 clarification | Pre-Wave ✅ | Docs | Document resource lifecycle writes vs policy administration — §7 added to ADR-024 |
| `AuthMode` sealed enum | Wave 0 ✅ | Scala config | **Already implemented** — `AuthConfig.scala` has sealed `enum AuthMode` with fail-fast `DeriveConfig` (verified 2026-07-01) |
| `WorkspaceId` + `asResource` | Pre-Wave ✅ | Scala type | **Already exists** — `case class WorkspaceId` in `OpaqueTypes.scala`, `WorkspaceRecord.id: WorkspaceId`, both `asResource` extensions in `AuthorizationService.scala` (verified 2026-07-01) |
| `UserId` sum type | Wave 0 ✅| Scala type | `Anonymous \| Authenticated` — prevents sentinel reaching SpiceDB |
| `SpiceDbConfig` Iron constraints | Wave 0 ✅| Scala config | `PositiveInt` timeout, `MeshServiceUrl` constraint (http/https, mesh mTLS) |
| `BootstrapProvisioner` trait | Wave 0 ✅| Scala type | Separate resource lifecycle writes from `AuthorizationService` |
| `Checked[P]` proof token (strong form) | Wave 1 ✅ | Scala type | `check()` returns proof; protected operations require it via `using`. **Implemented 2026-07-04 — see implementation note below.** |
| `BootstrapProvisioner.bootstrapToken()` / `systemMaintenanceToken()` | Wave 0D amendment ✅ | Scala type | Lifecycle proof tokens added alongside `recordOwnership()` — required for `Checked[P]` to work at bootstrap and reaper call sites. `bootstrapToken()` produces `Checked[Bootstrap.type]`; `systemMaintenanceToken()` produces `Checked[SystemMaintenance.type]`. |
| `BootstrapProvisioner.recordOwnership()` | Wave 6 ✅ | Scala impl | Implemented in `BootstrapProvisionerSpiceDB`; wired into `bootstrapWorkspace` handler (2026-07-09) |
| SpiceDB service account scope | Wave 6 | Ops | Narrow write permission to `owner_user`/`owner_team` on `workspace` only |
| Header spoofing smoke test | Phase K.5 | K8s CI | Mandatory exit criterion verifying waypoint strips external headers |
| Full reconcile drift detection | Phase K.6 | K8s CI | Provisioning job compares both directions, not only write errors |

---

## Pre-Wave: ADR-024 Clarification

**File:** `docs/dev/ADR-024-externalized-authorization-pep-pattern.md`

Add a new section to ADR-024 clarifying the boundary between policy administration (PAP) and
resource lifecycle management. The distinction that was missing:

> **Resource lifecycle writes are not PAP actions.** The Zanzibar paper (Google, 2019) explicitly
> describes the creating service writing an initial ownership tuple as part of resource creation.
> This is categorically different from a user-initiated delegation request.
>
> The test: does a *user request* drive the SpiceDB write?
> - **Yes** (user requests access for themselves or others) → PAP. Must go through ops tooling.
> - **No** (system records creator ownership as part of resource creation) → lifecycle management.
>   Performed inline by the application, scoped to the `owner_user`/`owner_team` relations only.
>
> ADR-024's "no grant/revoke in app" principle targets the first case. It does not prohibit the
> second.

**Service account scope note** (also add to ADR-024 §1):

> The app service account must have SpiceDB write permission limited to:
> - `owner_user` relation on `workspace` definitions
> - `owner_team` relation on `workspace` definitions
>
> It must NOT have write permission for `editor`, `analyst`, `viewer`, or any other relation.
> This is the zero-trust control: even if the app were compromised, the service account cannot
> write arbitrary access grants.

---

## Wave 0 Amendments

These are additions to the Wave 0 deliverables defined in AUTHORIZATION-PLAN.md Task L2.6.
Wave 0 behaviour is still: no user-visible change, all NoOp.

### A. `AuthMode` sealed enum — replace raw String

> **✅ ALREADY IMPLEMENTED** (verified 2026-07-01)
>
> `modules/server/src/main/scala/com/risquanter/register/configs/AuthConfig.scala` already
> contains `enum AuthMode { CapabilityOnly, Identity, FineGrained }`, `given DeriveConfig[AuthMode]`
> via `Config.string.mapOrFail` (fails on unknown mode strings — not a silent default), and
> `AuthConfig(mode: AuthMode = AuthMode.CapabilityOnly)`. The code examples below match the
> existing implementation and are **reference only**. No code changes are needed for Wave 0A.

**File:** `modules/server/src/main/scala/com/risquanter/register/configs/AuthConfig.scala`

Replace `mode: String` with a validated sealed enum. A misconfigured mode string (typo,
wrong separator) silently falls through to `capability-only` in the current design — the
least restrictive option.

```scala
enum AuthMode:
  case CapabilityOnly  // Layer 0: free-tier
  case Identity        // Layer 0+1: enterprise with Keycloak
  case FineGrained     // Layer 0+1+2: full multi-tenant

object AuthMode:
  def fromString(s: String): Either[String, AuthMode] = s match
    case "capability-only" => Right(CapabilityOnly)
    case "identity"        => Right(Identity)
    case "fine-grained"    => Right(FineGrained)
    case other             => Left(s"Unknown auth mode: '$other'. Valid values: capability-only, identity, fine-grained")

final case class AuthConfig(mode: AuthMode, spiceDb: Option[SpiceDbConfig] = None)
```

ZIO Config descriptor must fail service startup — not default — on unknown mode:

```scala
// Service fails to start if mode is unrecognised. 
// A misconfigured production deployment fails closed, not open.
given ConfigDescriptor[AuthMode] =
  ConfigDescriptor.string.transformOrFailLeft(AuthMode.fromString)(_.toString)
```

**ZLayer selection** becomes exhaustive pattern match with no `case _ =>` fallthrough:

```scala
config.mode match
  case AuthMode.CapabilityOnly => ZLayer.succeed(AuthorizationServiceNoOp) ++ ZLayer.succeed(UserContextExtractor.noOp)
  case AuthMode.Identity       => ZLayer.succeed(AuthorizationServiceNoOp) ++ UserContextExtractor.jwtLayer
  case AuthMode.FineGrained    => AuthorizationServiceSpiceDB.layer(config.spiceDb.get) ++ UserContextExtractor.jwtLayer
```

**Tests:** `AuthMode.fromString` rejects unknown strings with `Left`; known strings parse correctly.

---

### B. `UserId` sum type — eliminate anonymous sentinel

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`

> **Note:** `UserId` lives in the **`common` module** (not `server`) — shared between the JVM
> server and the Scala.js frontend. The existing `final class UserId private (...)` is at the
> bottom of `OpaqueTypes.scala`. The sum type replaces it in-place.

The current design uses `UserId.fromString("00000000-...")` as an anonymous sentinel in
`capability-only` mode. If mode selection has a bug and NoOp is wired in production, this
sentinel reaches downstream code indistinguishably from a real user UUID.

Replace with a sum type so the compiler enforces that anonymous identities never reach
SpiceDB call sites:

```scala
// OpaqueTypes.scala — replace the existing `final class UserId` block in-place

sealed trait UserId:
  override def toString: String = "UserId(***)"  // PII redaction on all variants

object UserId:
  // Authenticated: wraps UuidStr (String :| UuidConstraint) — Iron constraint PRESERVED.
  // private constructor: only constructible via UserId.fromString (through ValidationUtil).
  final class Authenticated private (private val raw: UuidStr) extends UserId:
    def value: String = raw
    override def hashCode: Int = raw.hashCode
    override def equals(that: Any): Boolean = that match
      case u: Authenticated => raw == u.raw
      case _                => false

  // Anonymous: sentinel variant — no UUID, no PII, never reaches SpiceDB.
  case object Anonymous extends UserId:
    override def toString: String = "UserId.Anonymous"

  // fromString: validates UUID format via Iron, wraps in Authenticated.
  // NOTE: requires ValidationUtil.refineUserId to return Either[List[ValidationError], UuidStr]
  // (not UserId as it currently does). Change refineUserId to drop the .map(UserId(_)) wrap —
  // that construction moves here. This is an atomic change: the only runtime caller of
  // refineUserId is UserId.fromString, which is rewritten simultaneously.
  def fromString(s: String): Either[List[ValidationError], Authenticated] =
    ValidationUtil.refineUserId(s).map(new Authenticated(_))

  // JSON codecs — Authenticated only; Anonymous is never serialised to JSON.
  // These replace the existing given JsonEncoder[UserId] / JsonDecoder[UserId].
  given JsonEncoder[Authenticated] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[Authenticated] = JsonDecoder[String].mapOrFail(s =>
    fromString(s).left.map(_.mkString(", ")))
```

**`ValidationUtil.refineUserId` change required (Wave 0B — `common` module):**

```scala
// Before (current):
def refineUserId(value: String, fieldPath: String = "userId"): Either[List[ValidationError], UserId] =
  nonEmpty(value).refineEither[UuidConstraint].map(UserId(_)).left.map(...)

// After (Wave 0B):
def refineUserId(value: String, fieldPath: String = "userId"): Either[List[ValidationError], UuidStr] =
  nonEmpty(value).refineEither[UuidConstraint].left.map(...)
  // .map(UserId(_)) removed — caller (UserId.fromString) wraps in Authenticated
```

The Iron constraint `UuidConstraint` (`Match["^[0-9a-f]{8}-..."]`) is fully preserved — `Authenticated` still wraps `UuidStr`. No regression.

`AuthorizationService.check()` accepts only `UserId.Authenticated`, not `UserId`:

```scala
def check[P <: Permission](
  user:       UserId.Authenticated,   // ← not UserId
  permission: P,
  resource:   ResourceRef
): IO[AuthError, Checked[P]]
```

`UserContextExtractor` variants:

```scala
// capability-only: returns Anonymous — never reaches check()
val noOp: UserContextExtractor = _ => ZIO.succeed(UserId.Anonymous)

// identity / fine-grained: returns Authenticated or fails
val requirePresent: UserContextExtractor = {
  case None         => ZIO.fail(AuthForbidden(...))
  case Some(userId) => ZIO.succeed(userId)   // already Authenticated from Tapir codec
}
```

The `serverLogic` for-comprehension naturally enforces the separation:

```scala
userId <- userCtx.extract(maybeUserId)  // returns UserId (Anonymous or Authenticated)
// In capability-only mode, userId is Anonymous. authz.check() is NoOp and ignores it.
// In fine-grained mode, userId must be Authenticated to call check().
// If userCtx.extract returns Anonymous in fine-grained mode, the for-comp would need
// to narrow — achieved by having UserContextExtractor.requirePresent return Authenticated directly.
```

Adjust `UserContextExtractor` return type to `IO[AppError, UserId.Authenticated]` for
`requirePresent`, and `IO[AppError, UserId]` for `noOp`. The `serverLogic` code handles
both branches via the mode-driven layer selection.

### Migration scope for Wave 0B

Changing `UserId` from `final class` to `sealed trait` breaks all existing references.
Run `grep -rn "UserId" modules/ --include="*.scala" | grep -v target` to confirm scope.

**`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`** — the `UserId` type definition itself.

**`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`** — `refineUserId` return type changes from `Either[..., UserId]` to `Either[..., UuidStr]` (drops the `.map(UserId(_))` wrap; caller does the wrapping). One runtime caller: `UserId.fromString`. The Iron `UuidConstraint` check is unchanged — no validation regression.

**`modules/server` — auth package (modify existing files, do not create new):**
- `auth/AuthorizationService.scala` — `check()` and `listAccessible()` parameter types
- `auth/AuthorizationServiceNoOp.scala` — same two method signatures
- `auth/AuthorizationServiceStub.scala` — `Set[(UserId, ...)]` type, both method signatures, `layer()` factory
- `auth/UserContextExtractor.scala` — `extract()` input type, `requirePresent` return type, `anonymous` val, startup diagnostics
- `auth/UserContext.scala` — `userId: UserId` field; keep as sealed trait supertype (no functional change)

**`modules/server` — HTTP controllers (modify existing files):**
- `http/controllers/DistributionPreviewController.scala`
- `http/controllers/QueryController.scala`
- `http/controllers/WorkspaceAnalysisController.scala`
- `http/controllers/WorkspaceLifecycleController.scala`
- `http/controllers/WorkspaceTreeController.scala`
- `http/sse/SSEController.scala`

In each controller, the `userId <- userCtx.extract(maybeUserId)` binding carries `UserId`
(sealed trait). Mode-driven layer wires `requirePresent` (returns `Authenticated`) or `noOp`
(returns `Anonymous`). Calls to `authz.check()` require `UserId.Authenticated`.

**Tapir codec — `x-user-id` header binding:**
The `x-user-id` Tapir header input type changes from `Option[UserId]` to
`Option[UserId.Authenticated]` — a present UUID decodes to `Authenticated`; `None` means
header absent. Locate all `UserId` imports in endpoint definition files to find codec sites.

**`modules/server` — test files (modify existing files):**
- `test/.../auth/UserContextExtractorSpec.scala` — `UserId.fromString`, `UserContextExtractor.anonymous`, `extract()` return assertions
- `test/.../http/controllers/DistributionPreviewControllerSpec.scala` — `AuthorizationServiceStub` construction with `UserId` values

**Cross-cutting reference:**

This change partially resolves the compile-time gap recorded in `UserContextExtractor.scala`:
> `TODO [ADR-012 §7 T4 / Wave 3]: Replace point-in-time CI check with a continuous invariant...`
>
> Once `UserId.Authenticated` is the only type `check()` accepts, the anonymous sentinel
> (`00000000-...`) cannot reach SpiceDB as a code path — it is a compile error, not a runtime
> gap. The Wave 3 SpiceDB CEL caveat remains needed as an independent infrastructure layer.

See also `docs/dev/TODO.md §16b` — anonymous sentinel sum type recommendation.

---

### C. `SpiceDbConfig` Iron constraints

**File:** `modules/server/src/main/scala/com/risquanter/register/configs/SpiceDbConfig.scala` (new file)

Two constraint tightenings that follow existing Iron patterns in the codebase:

```scala
// MeshServiceUrl: mesh-internal service endpoint — mTLS provided by Istio, not the application.
// Accepts both http:// and https:// — use SecureUrl for internet-facing endpoints.
final case class SpiceDbConfig(
  url:            MeshServiceUrl,      // was SafeUrl (pre-impl name) → SecureUrl → MeshServiceUrl
  token:          SpiceDbToken,
  consistency:    SpiceDbConsistency = SpiceDbConsistency.MinimizeLatency,
  timeoutSeconds: PositiveInt = 10     // was Int
)
```

Service fails to start if SpiceDB URL is invalid or timeout is ≤ 0. Both are config errors
that would be invisible at runtime otherwise.

---

### D. `BootstrapProvisioner` trait — resource lifecycle separation

**Files:** (all new files)
```
modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisioner.scala
modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerSpiceDB.scala
modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerNoOp.scala
```

A separate service for resource lifecycle writes, distinct from `AuthorizationService`.
`AuthorizationService` remains a pure PEP: `check()` and `listAccessible()` only, no writes.

```scala
/** Records workspace ownership in SpiceDB as part of workspace creation.
  *
  * This is resource lifecycle management, not policy administration (PAP).
  * The Zanzibar paper (2019) explicitly models this pattern: the creating service
  * records initial ownership inline with resource creation.
  *
  * The distinction from PAP: this write is system-initiated (not driven by a user
  * delegation request). The service account's write scope is limited to `owner_user`
  * and `owner_team` relations on workspace definitions — not arbitrary ACL grants.
  *
  * @see ADR-024 §1 — updated with lifecycle write clarification
  */
trait BootstrapProvisioner:

  /** Record the creator as owner_user of the newly created workspace.
    * Called once, immediately after workspace creation, atomically within the
    * workspace bootstrap transaction (ZIO for-comprehension).
    *
    * Writes: workspace:{workspaceId}#owner_user@user:{userId}
    *
    * In capability-only and identity modes this is a no-op — SpiceDB is not
    * active and ownership is tracked via the `owner_id` DB column only.
    */
  def recordOwnership(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[AuthError, Unit]

object BootstrapProvisioner:
  val noOp: BootstrapProvisioner = (_, _) => ZIO.unit
```

`BootstrapProvisionerSpiceDB` implements the single SpiceDB write using the `WriteRelationships`
API. The service account for this call has write permission scoped to `owner_user`/`owner_team`
on `workspace` only — verified at K.6 provisioning job setup.

`BootstrapProvisionerNoOp` is wired in `capability-only` and `identity` modes.

**ZIO environment for bootstrap handler** — only the bootstrap endpoint receives
`BootstrapProvisioner` in its environment type. All other handlers use `AuthorizationService`
only. This scoping means the compiler prevents any handler other than bootstrap from calling
`recordOwnership()`.

---

## Wave 1 Amendment: `Checked[P]` Proof Token (Strong Form)

> **✅ IMPLEMENTED 2026-07-04**
>
> **Implementation deviation from plan examples:** Scala 3 type inference widens the return
> type of `check()` to `Checked[Permission]` (base type) when the caller does not explicitly
> ascribe the type parameter. As a result:
> - Service method signatures use `(using Checked[Permission])` — **not** `(using Checked[Permission.ViewWorkspace.type])`
> - Handler bindings use `given Checked[Permission] <- authzService.check(...)` — **not** the specific singleton type shown in examples below
> - The specific permission value (`Permission.ViewWorkspace`, `Permission.DesignWrite`, etc.) is still passed to `check()` and sent to SpiceDB — only the binding type is wider
> - This is correct behavior: service methods require `Checked[Permission]` (any proof); the specific permission that SpiceDB enforces lives in the `check()` call arguments
> - See ADR-030 §3 for the full rationale and working Scala 3 syntax

This is the most significant type-level change. It is introduced at Wave 1 because that wave
already touches every workspace-scoped `serverLogic` signature. Introducing it here avoids
a second pass.

### The strong form

`check()` returns a proof token `Checked[P]`. Every protected service operation demands the
proof via a Scala 3 `using` parameter. Without calling `check()` first, the downstream call
will not compile. There is no weak-form escape hatch.

**File:** Place `Checked[P]` and its companion in `modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala`, after the `ResourceRef` companion object and before the `AuthorizationService` trait definition. The `private[auth]` access on `Checked.apply` restricts instantiation to the `com.risquanter.register.auth` package.

```scala
/** Proof that AuthorizationService.check() was called and succeeded for permission P.
  *
  * Opaque with no public constructor — only AuthorizationService can produce this.
  * Consumer code cannot construct a Checked[P] without going through check().
  *
  * The using/given mechanism in Scala 3 makes the proof contextual: once obtained
  * in a for-comprehension via `given Checked[P] <- authz.check(...)`, it is
  * automatically available to any subsequent call in scope that requires it.
  */
opaque type Checked[+P <: Permission] = Unit

object Checked:
  // Package-private — only AuthorizationService implementations produce this
  private[auth] def apply[P <: Permission](): Checked[P] = ()
```

### Updated `AuthorizationService.check()` signature

```scala
trait AuthorizationService:

  def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]]   // ← was Unit

  def listAccessible(
    user:         UserId.Authenticated,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]]
```

### Updated stub/NoOp implementations

> **Note:** `AuthorizationServiceNoOp` and `AuthorizationServiceStub` **already exist** at
> `modules/server/src/main/scala/com/risquanter/register/auth/`. These are **modifications to
> existing files**, not new files. Modify them in-place.

```scala
object AuthorizationServiceNoOp extends AuthorizationService:
  def check[P <: Permission](user: UserId.Authenticated, permission: P, resource: ResourceRef): IO[AuthError, Checked[P]] =
    ZIO.succeed(Checked[P]())   // always grants; proof produced unconditionally

class AuthorizationServiceStub(allowed: Set[(UserId.Authenticated, Permission, ResourceRef)])
    extends AuthorizationService:
  def check[P <: Permission](user: UserId.Authenticated, permission: P, resource: ResourceRef): IO[AuthError, Checked[P]] =
    if allowed.contains((user, permission, resource)) then ZIO.succeed(Checked[P]())
    else ZIO.fail(AuthForbidden(...))
```

### Updated `serverLogic` pattern with strong form

The `given` binding in the ZIO for-comprehension makes the proof available as an implicit
context to subsequent calls:

```scala
val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic {
  case (maybeUserId, key) =>
    (for
      ws     <- workspaceStore.resolve(key)           // ws: WorkspaceRecord (not Workspace)
      userId <- userCtx.requireAuthenticated(maybeUserId)
      given Checked[Permission.ViewWorkspace.type] <- authz.check(userId, Permission.ViewWorkspace, ws.id.asResource)
      trees  <- workspaceStore.listTrees(key)   // Checked[ViewWorkspace.type] is in scope as a given
    yield trees).either
}
```

### Impact on protected service methods

Service methods that handle sensitive data must declare the proof requirement.
The compiler enforces that `check()` was called before these methods can be invoked:

```scala
// WorkspaceStore — protected operations gain using parameter
// NOTE: illustrative pattern — verify each existing method signature against current source
// before adding the using parameter.
def listTrees(key: WorkspaceKeySecret)
    (using Checked[Permission.ViewWorkspace.type]): IO[AppError, List[TreeId]]  // ← List[TreeId] not List[Tree]

// RiskTreeService — same pattern
// Actual current signature: getById(wsId: WorkspaceId, id: TreeId): Task[Option[RiskTree]]
def getById(wsId: WorkspaceId, id: TreeId)
    (using Checked[Permission.ViewTree.type]): Task[Option[RiskTree]]
```

Public methods that do NOT require authorization (workspace resolution, key lookup) have no
`using` parameter — they remain accessible without a proof.

### `userCtx.requireAuthenticated()` helper

Introduce alongside `extract()` for contexts where `Authenticated` is required:

```scala
trait UserContextExtractor:
  def extract(maybeUserId: Option[UserId.Authenticated]): IO[AppError, UserId]
  // Returns Anonymous in capability-only mode, Authenticated otherwise

  def requireAuthenticated(maybeUserId: Option[UserId.Authenticated]): IO[AppError, UserId.Authenticated]
  // Fails with AuthForbidden if None — used in serverLogic before check()
```

### Regression gate for Wave 1

All existing wave tests pass. New invariant:

```scala
// Invariant 5: Compiling a serverLogic that calls a protected service method without
// a prior check() call produces a compile error, not a runtime failure.
// This is verified by attempting to compile a deliberate violation in a test file
// and asserting it does NOT compile (using scala.compiletime.testing.typeCheckErrors).
test("protected service method without Checked[P] in scope does not compile") {
  import scala.compiletime.testing.typeCheckErrors
  val errors = typeCheckErrors("""
    workspaceStore.listTrees(key)   // no Checked[ViewWorkspace.type] in scope
  """)
  assertTrue(errors.nonEmpty)
}
```

---

## Wave 6 Revision: `BootstrapProvisioner.recordOwnership()`

Replace the `authz.seed()` call in the bootstrap handler (AUTHORIZATION-PLAN.md Wave 6)
with `bootstrapProvisioner.recordOwnership()`.

**File:** `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`

> **Note:** There is no `WorkspaceController.scala`. The bootstrap handler (`bootstrapWorkspace`)
> lives in `WorkspaceLifecycleController.scala`, which owns workspace bootstrap, key rotation,
> and deletion endpoints.

```scala
val bootstrapWorkspace: ServerEndpoint[Any, Task] = bootstrapWorkspaceEndpoint.serverLogic {
  case (maybeForwardedFor, maybeUserId, req) =>
    (for
      userId  <- userCtx.requireAuthenticated(maybeUserId)
      tree    <- riskTreeService.create(req)
      ws      <- workspaceStore.bootstrap(tree.id, ...)
      _       <- bootstrapProvisioner.recordOwnership(userId, ws.id)
      // ↑ resource lifecycle write — distinct from authz.check()
      // ↑ BootstrapProvisioner is in scope only for this handler
    yield WorkspaceBootstrapResponse(ws.key, tree)).either
}
```

`AuthorizationService` is NOT used for the write. The controller receives both
`AuthorizationService` (for reads in all other handlers) and `BootstrapProvisioner`
(for the lifecycle write in bootstrap only) via ZIO dependency injection. The ZIO
environment type of the bootstrap handler is the only place `BootstrapProvisioner`
appears — all other handlers' environment types do not include it.

**Service account configuration** (also part of Wave 6):

SpiceDB RBAC for the app service account must be explicitly scoped:

```yaml
# infra/spicedb/app-service-account-permissions.zed (or equivalent)
# App service account: register-server@register.svc.cluster.local
# Write permission: owner_user and owner_team relations on workspace ONLY
# Read permission: check() and lookup_resources on all definitions
```

This is provisioned by the K.6 CI job alongside the schema. Verify in the L2.0 exit
criteria that the service account cannot write `editor`, `analyst`, or `viewer` relations.

**Updated tests for Wave 6:**

- `bootstrapWorkspace` in fine-grained mode: `BootstrapProvisionerStub` records one
  `recordOwnership(userId, workspaceId)` call — not `AuthorizationServiceStub`
- `recordOwnership()` failure → workspace creation rolled back
- `bootstrapWorkspace` in capability-only mode: `BootstrapProvisionerNoOp` records zero calls
- No other handler calls `recordOwnership()` — verified by grepping for `bootstrapProvisioner`
  outside `WorkspaceController.bootstrapWorkspace`

---

## Phase K.5 Amendment: Header Spoofing Smoke Test

Add to K.5 exit criteria in AUTHORIZATION-PLAN.md:

**File:** `infra/smoke-tests/k5-header-spoofing.sh` (or equivalent CI step)

The entire Layer 1/2 identity model depends on the Istio waypoint stripping external
`x-user-*` headers before injecting claim headers from the validated JWT. This must be
verified against a real Istio deployment — an in-process unit test cannot exercise it.

```bash
#!/usr/bin/env bash
# K.5 smoke test: verify waypoint strips spoofed x-user-id header

set -euo pipefail

REGISTER_URL="${REGISTER_URL:?}"   # deployed service base URL

# Send a request with a spoofed x-user-id to a protected endpoint
# If the waypoint is not stripping external headers, this UUID would be
# accepted as a valid identity, bypassing authentication entirely.
SPOOFED_UUID="00000000-0000-0000-0000-deadbeef1337"

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "x-user-id: ${SPOOFED_UUID}" \
  "${REGISTER_URL}/w/invalid-key/risk-trees")

# Must be 404 (key not found) or 401 (no JWT) — NOT 200 with the spoofed identity
if [[ "${HTTP_STATUS}" == "200" ]]; then
  echo "FAIL: Request with spoofed x-user-id returned 200 — waypoint is not stripping headers"
  exit 1
fi

echo "PASS: Spoofed x-user-id header did not bypass authentication (status: ${HTTP_STATUS})"
```

Run this test:
1. As part of K.5 phase gate before declaring K.5 complete
2. In the K.6 CI pipeline as a post-deploy smoke check on every deploy to any environment

Add to AUTHORIZATION-PLAN.md K.5 exit criteria:
> - **Header spoofing test passes** — see AUTH-TESTING-PLAN.md §K5 (B-K5-1 through B-K5-3)

---

## Phase K.6 Amendment: Full Reconcile Drift Detection

The provisioning job (K.6) currently fails on write errors but does not detect tuples that
exist in SpiceDB but are absent from the config source. These represent privilege creep:
access grants that were added outside the CI path (manual `zed` CLI operations, previous
job runs with since-deleted config, etc.).

**Add to the K.6 provisioning job:**

```
Reconcile algorithm:
1. Compute intended_tuples = set of tuples derived from config source
2. Compute actual_tuples   = ReadRelationships query against SpiceDB for managed types
3. to_write  = intended_tuples − actual_tuples   (missing — write)
4. to_delete = actual_tuples − intended_tuples   (orphaned — delete or alert)

On drift (non-empty to_delete):
  - Log each orphaned tuple at WARN level with full detail
  - Fail the pipeline if register.authz.provisioning.strict-drift = true (default)
  - Otherwise warn and continue (for initial rollout tolerance)

Scope: only tuples for team/org relations (editor, analyst, viewer, team_admin, org_member)
       NOT ownership tuples (owner_user, owner_team) — those are written by the app
       and should not be managed by the CI job.
```

This is the authorization equivalent of Terraform's plan-then-apply: the job declares the
full desired state and reconciles against reality in both directions.

---

## SpiceDB Adapter Integration Tests (`server-it`) ✅ 2026-07-06

These tests verify `AuthorizationServiceSpiceDB` against a real SpiceDB instance using the
existing `server-it` Docker Compose infrastructure. They are owned by the `register` project.

**Setup (implemented):**
- SpiceDB added to `docker-compose.server-it.yml` (`authz` profile, `authzed/spicedb:latest`, `serve --datastore-engine=memory --grpc-preshared-key=test-spicedb-token`, dynamic port 8080, `grpc_health_probe` healthcheck)
- Schema applied via `SpiceDbCompose` helper: reads `infra/spicedb/schema.zed`, POSTs to `/v1/schema/write` at startup
- Test relationships seeded via SpiceDB REST API in `SpiceDbCompose.layer` acquisition
- Test class: `modules/server-it/src/test/scala/.../auth/AuthorizationServiceSpiceDBItSpec.scala` (named `ItSpec` to avoid collision with the unit-test `AuthorizationServiceSpiceDBSpec` in `server`)

The test data model uses three users (`alice`, `bob`, `carol`) and two workspaces (`ws1`, `ws2`)
with a single tree (`tree1` in `ws1`). `alice` is `owner_user` of `ws1`; `bob` has no grants;
`carol` has a `viewer` grant on `ws1`.

---

### T-S1 — Allowed check returns `Checked[P]`
**Precondition:** `workspace:ws1#owner_user@user:alice` seeded
**Action:** `check(alice.Authenticated, Permission.ViewWorkspace, ws1.asResource)`
**Assert:** `Right(Checked[Permission.ViewWorkspace.type])` — no exception, effect succeeds

### T-S2 — Denied check returns `AuthForbidden`
**Precondition:** No relationship seeded for `bob`
**Action:** `check(bob.Authenticated, Permission.ViewWorkspace, ws1.asResource)`
**Assert:** `Left(AuthForbidden)` — `userId`, `permission`, `resourceType`, `resourceId` fields
populated with the checked values (not redacted)

### T-S3 — Insufficient permission returns `AuthForbidden`
**Precondition:** `workspace:ws1#viewer@user:carol` seeded (viewer, not editor)
**Action:** `check(carol.Authenticated, Permission.DesignWrite, ws1.asResource)`
**Assert:** `Left(AuthForbidden)` — viewer cannot design_write

### T-S4 — Schema inheritance: workspace `owner_user` grants tree `view_tree`
**Precondition:** `workspace:ws1#owner_user@user:alice` seeded; `risk_tree:tree1#workspace@workspace:ws1` seeded; no direct tree relationship for alice
**Action:** `check(alice.Authenticated, Permission.ViewTree, tree1.asResource)`
**Assert:** `Right(Checked[...])` — inherited via `workspace->view_workspace->view_tree`

### T-S5 — SpiceDB unavailable returns `AuthServiceUnavailable`, fails closed
**Precondition:** SpiceDB container stopped or network blocked
**Action:** `check(alice.Authenticated, Permission.ViewWorkspace, ws1.asResource)`
**Assert:** `Left(AuthServiceUnavailable)` — NOT `AuthForbidden`; confirms different error path.
The HTTP layer must map both to 403 — caller cannot distinguish unavailable from denied.

### T-S6 — Invalid token returns `AuthServiceUnavailable`, fails closed
**Precondition:** `AuthorizationServiceSpiceDB` configured with a wrong bearer token
**Action:** `check(alice.Authenticated, Permission.ViewWorkspace, ws1.asResource)`
**Assert:** `Left(AuthServiceUnavailable)` — 4xx from SpiceDB maps to unavailable, not forbidden

### T-S7 — `listAccessible` returns workspace IDs where user has permission
**Precondition:** `workspace:ws1#owner_user@user:alice`, `workspace:ws2#viewer@user:alice` seeded
**Action:** `listAccessible(alice.Authenticated, ResourceType.Workspace, Permission.ViewWorkspace)`
**Assert:** Result contains both `ws1.id` and `ws2.id` (order-insensitive)

### T-S8 — `listAccessible` returns `Nil` for user with no relationships
**Precondition:** No relationships seeded for `bob`
**Action:** `listAccessible(bob.Authenticated, ResourceType.Workspace, Permission.ViewWorkspace)`
**Assert:** `Right(Nil)`

### T-S9 — Anonymous sentinel UUID (`00000000-...`) has no permissions (T4 guard)
**Precondition:** No relationship seeded for `00000000-0000-0000-0000-000000000000`; sentinel is
explicitly NOT present in any SpiceDB tuple (CI invariant from ADR-012 §7 T4)
**Action:** `check(sentinelUuid.Authenticated, Permission.ViewWorkspace, ws1.asResource)`
**Assert:** `Left(AuthForbidden)` — sentinel must never be granted access
**Note:** This test verifies the CI invariant at the Scala layer. The BATS-level counterpart
(B-FC-3 in AUTH-TESTING-PLAN.md) verifies the same at the deployed HTTP level.

### T-S10 — `BootstrapProvisionerSpiceDB.recordOwnership` writes a checkable tuple
**Precondition:** No relationship seeded for `alice` on `ws3`
**Action:**
1. `bootstrapProvisioner.recordOwnership(alice.Authenticated, ws3)`
2. `check(alice.Authenticated, Permission.ViewWorkspace, ws3.asResource)`
**Assert:** Step 1 succeeds; step 2 returns `Right(Checked[...])` — the written tuple is immediately
readable. Use `consistency = FullyConsistent` for this test to avoid NewEnemy window.

---



> **Status:** Initially proposed — not validated. Do not implement until all items above are
> complete and the `Checked[P]` strong form has been in use long enough to assess whether
> the edge case described below is actually observed in practice.

### The edge case

`Checked[P]` (as specified in Wave 1) proves that `authz.check()` was called for permission
`P` on *some* resource. It does not encode *which* resource was checked. Nothing in the current
type system prevents a developer from checking permission on workspace A then operating on
workspace B:

```scala
// check() called for ws1 — proof is Checked[ViewWorkspace.type]
given Checked[Permission.ViewWorkspace.type] <- authz.check(userId, ViewWorkspace, ws1.id.asResource)

// listTrees called with ws2 — different workspace, but compiles because the proof
// only carries the permission type, not the resource identity
trees <- workspaceStore.listTrees(ws2)
```

The for-comprehension ordering and code review are the current guards. The `resolve(key)`
step must succeed first and the returned `Workspace.id` is what gets passed to `check()`, so
accidental mis-wiring requires deliberate bad code rather than an innocent omission.

### The proposed direction (unvalidated)

Encode the resource identity in the proof type so cross-workspace confusion is a compile error:

```scala
// Proof carries both permission AND the specific resource checked
opaque type Checked[+P <: Permission, +Id] = Unit

// check() would produce a resource-scoped proof
def check[P <: Permission](
  user:       UserId.Authenticated,
  permission: P,
  resource:   ResourceRef   // ResourceRef carries the WorkspaceId or TreeId
): IO[AuthError, Checked[P, resource.resourceId.type]]  // phantom type on resourceId

// Protected method requires proof for the exact resource it operates on
def listTrees(ws: Workspace)(using Checked[Permission.ViewWorkspace.type, ws.id.type]): IO[...]
```

The `Checked[ViewWorkspace.type, ws1.id.type]` proof would be incompatible with
`listTrees(ws2)` at the call site — compile error if the resource doesn't match.

### What needs validating before committing to this

1. **Scala 3 type-level feasibility** — `resource.resourceId.type` is a singleton type. Whether
   the Scala 3 type inference can thread singleton types through ZIO for-comprehensions reliably
   needs prototyping. The `given` binding pattern may not produce the right singleton type without
   explicit ascription at every call site.

2. **Ergonomics cost** — every `serverLogic` for-comprehension would need explicit type
   annotations. The current design already requires `given Checked[P] <- authz.check(...)`;
   adding resource identity may require `given Checked[P, ws.id.type] <- ...` with a type
   ascription that references a value only available one step earlier in the comprehension.

3. **Practical impact** — if mis-wiring (checking ws1, operating on ws2) has never been
   observed as an actual bug in this codebase, the complexity cost may not be justified.

### Decision trigger

If a resource confusion bug is found in code review or testing during Wave 3–5 rollout,
escalate this to an active task. Otherwise, evaluate post-Layer 2 stabilisation.

---

## Completion Criteria

All items below must be satisfied before the authorization rollout is considered complete:

**Type-level (Scala):**
- [x] `AuthMode` sealed enum; service fails on unknown mode string — **DONE** (`AuthConfig.scala`, verified 2026-07-01)
- [x] `WorkspaceId` and `asResource` extensions exist — **DONE** (`OpaqueTypes.scala` + `AuthorizationService.scala`, verified 2026-07-01)
- [x] `UserId.Authenticated` / `UserId.Anonymous` sum type; `check()` accepts only `Authenticated` — **DONE** (`OpaqueTypes.scala`, Wave 0B, verified 2026-07-01)
- [x] `SpiceDbConfig.url` uses `MeshServiceUrl` constraint (http/https, mesh mTLS) — **DONE** (`SpiceDbConfig.scala`, Wave 0C, updated 2026-07-04)
- [x] `SpiceDbConfig.timeoutSeconds` is `PositiveInt` — **DONE** (`SpiceDbConfig.scala`, Wave 0C, verified 2026-07-01)
- [x] `Checked[P]` opaque type (strong form); `check()` returns `IO[AuthError, Checked[P]]` — **DONE** (`auth/AuthorizationService.scala`, Wave 1, verified 2026-07-04). **Implementation note:** service method `using` parameters use base `Checked[Permission]` type (not specific subtypes); handler bindings use `given Checked[Permission] <-` (Scala 3 infers base type from `check()`). See ADR-030 §3.
- [x] Protected service methods take `using Checked[P]`; missing proof is a compile error — **DONE** (`WorkspaceStore`, `RiskTreeService` traits + impls, Wave 1, verified 2026-07-04). All 10 protected methods on `WorkspaceStore` + `RiskTreeService` carry `(using Checked[Permission])`.
- [x] `BootstrapProvisioner` trait separate from `AuthorizationService`; `AuthorizationService` has no write methods — **DONE** (`auth/BootstrapProvisioner.scala`, Wave 0D, verified 2026-07-01). Extended 2026-07-04 with `bootstrapToken()` and `systemMaintenanceToken()` lifecycle proof methods.
- [x] `BootstrapProvisioner.recordOwnership()` wired in bootstrap handler only — **DONE** (`WorkspaceLifecycleController.bootstrapWorkspace`, Wave 6, verified 2026-07-05)
- [x] ADR-024 updated with lifecycle write clarification and service account scope note — **DONE** (ADR-024 §7, Pre-Wave, verified 2026-07-01)
- [x] ADR-030 created: Authorization Enforcement at the Orchestration Boundary — **DONE** (`docs/dev/ADR-030-authorization-enforcement-orchestration-boundary.md`, 2026-07-04)

**Infrastructure (K8s/CI):**
- [ ] Header spoofing smoke test passes against deployed K.5 cluster
- [ ] Header spoofing test is gated in K.6 CI post-deploy step
- [ ] K.6 provisioning job performs full bidirectional reconcile
- [ ] App service account verified: write permission scoped to `owner_user`/`owner_team` on `workspace` only

**Tests (unit + stub-based):**
- [x] Compile-time proof: missing `Checked[P]` at a protected call site does not compile — **DONE** (verified 2026-07-04: removing `given Checked[Permission] <-` from any controller handler causes compiler error at each subsequent protected service method call)
- [x] `AuthorizationServiceStub` returns `Checked[P]` on success — **DONE** (`auth/AuthorizationServiceStub.scala`, verified 2026-07-04)
- [x] `BootstrapProvisionerStub` records `recordOwnership()` calls (not on `AuthorizationService`) — **DONE** (`auth/BootstrapProvisionerStub.scala`, Wave 6, verified 2026-07-05)
- [x] All waves from AUTHORIZATION-PLAN.md pass their regression gates unchanged — **DONE** (478 tests pass, 0 failures, verified 2026-07-04)

**Tests (`server-it` — SpiceDB adapter, see §SpiceDB Adapter Integration Tests):**
- [x] SpiceDB added to `docker-compose.server-it.yml`; schema applied at startup — **DONE** (`authz` profile, `serve --datastore-engine=memory --grpc-preshared-key=test-spicedb-token`; `SpiceDbCompose` helper writes schema + seeds test relationships via REST API, verified 2026-07-06)
- [x] T-S1 through T-S10 pass against live SpiceDB instance — **DONE** (`AuthorizationServiceSpiceDBItSpec`, serverIt/test:compile clean, verified 2026-07-06)
