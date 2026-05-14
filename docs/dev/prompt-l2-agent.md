# Agent Prompt — Layer 2 (SpiceDB Fine-Grained Authorization)

> **Context:** You are working on the `register` project — a ZIO 2 / Scala 3
> application with a Tapir HTTP API. The application uses an Istio ambient mesh
> with a waypoint proxy that handles JWT validation and header injection.
> The app contains zero JWT code.

## Your Mission

Complete the SpiceDB fine-grained authorization (Layer 2) implementation
following `docs/AUTHORIZATION-PLAN.md`. Waves 0 and 1 are already done.
Your job is to build the live SpiceDB adapter, wire config-driven mode
selection, add workspace-level `check()` calls to the remaining routes,
and implement bootstrap seeding.

## Key Documents — Read These First

1. **`docs/AUTHORIZATION-PLAN.md`** — the master plan (1450 lines). Contains:
   - Layered model (L0/L1/L2) — §top
   - Task L2.1: schema.zed (exact schema to use) — §Task L2.1
   - Task L2.2: AuthorizationService types + SpiceDB HTTP API mapping — §Task L2.2
   - Task L2.4: OPA + SpiceDB complementary layers — §Task L2.4
   - Route inventory and permission matrix (22 routes) — §Task L2.6
   - Rollout waves 0–6 — §Rollout Waves
   - ZLayer selection pattern — §ZLayer Selection
   - Regression test invariants — §Regression Test Invariants

2. **`docs/ADR-024-externalized-authorization-pep-pattern.md`** — architectural constraints:
   - App is a pure PEP: `check()` and `listAccessible()` only
   - No `grant()`/`revoke()`/tuple writes (exception: `seed()` in Wave 6)
   - Fail-closed: `check()` failure → ZIO effect failure → 403
   - SpiceDB receives `userId` only — never roles, never email

3. **`docs/ADR-012.md`** — mesh trust assumptions, claim header injection,
   UserContextExtractor design, OPA role gate rationale.

## Current Code State — What's Already Done

**Waves 0 and 1 are complete.** Do NOT recreate or re-wire any of this.

### Auth types and services (Wave 0 — done)

| File | What |
|------|------|
| `modules/common/.../domain/data/iron/OpaqueTypes.scala` | `WorkspaceId` type (nominal `case class` over `SafeId` ULID), `fromString`, JSON codecs — tested in `AuthTypesSpec.scala` |
| `modules/server/.../auth/AuthorizationService.scala` | Trait with `check()` + `listAccessible()`. `Permission` enum (7 values with `zedName`), `ResourceType` enum (4 values with `zedType`), `ResourceRef` case class, `.asResource` extension methods on both `WorkspaceId` and `TreeId` |
| `modules/server/.../auth/AuthorizationServiceNoOp.scala` | Always-allow stub with ZLayer — wired in capability-only / identity modes |
| `modules/server/.../auth/AuthorizationServiceStub.scala` | Set-backed stub for unit tests, with `denyAll` and `denyAllLayer` factories |
| `modules/server/.../auth/UserContext.scala` | `UserContext(userId, email, roles)` + `Role` enum (Analyst, Editor, TeamAdmin) with `fromClaim` |
| `modules/server/.../auth/UserContextExtractor.scala` | Trait with `noOp` + `requirePresent` implementations, anonymous sentinel UUID, `logStartupMode` |
| `modules/server/.../configs/AuthConfig.scala` | `AuthConfig(mode: String)` with `isCapabilityOnly`, `isIdentity`, `isFineGrained` helpers |

### Endpoint wiring (Wave 1 — done)

| File | What |
|------|------|
| `modules/common/.../http/endpoints/BaseEndpoint.scala` | `authedBaseEndpoint` exists — adds `header[Option[UserId]]("x-user-id")` to `baseEndpoint` |
| `modules/common/.../http/endpoints/WorkspaceEndpoints.scala` | All workspace-scoped endpoints already use `authedBaseEndpoint`. Input tuples include `Option[UserId]`. |
| `modules/server/.../http/controllers/WorkspaceController.scala` | Takes `authzService: AuthorizationService` + `userCtx: UserContextExtractor` as constructor params. All `serverLogic` already destructures `(maybeUserId, ...)` tuple and calls `userCtx.extract(maybeUserId)`. **Tree-scoped routes already call `authzService.check()`** (hitting NoOp). Workspace-lifecycle routes (list, create, rotate, delete) have `userCtx.extract()` but NOT `authzService.check()` yet — comments say "Wave 3: add workspace-level check". |
| `modules/server/.../http/sse/SSEController.scala` | Takes `authzService` + `userCtx`. `treeEventsEndpoint.serverLogic` already calls `userCtx.extract()` + `authzService.check(userId, Permission.ViewTree, ...)` |
| `modules/server/.../Application.scala` | Currently hardcodes `AuthorizationServiceNoOp.layer` + `ZLayer.succeed(UserContextExtractor.noOp)` + logs `"capability-only"`. **AuthConfig is NOT loaded from config** — mode selection is not yet config-driven. |

### Domain model — `WorkspaceId` NOT yet in `Workspace`

| File | Status |
|------|--------|
| `modules/common/.../domain/data/Workspace.scala` | `Workspace(key: WorkspaceKeySecret, trees: Set[TreeId], createdAt, lastAccessedAt, ttl, idleTimeout)` — **no `id: WorkspaceId` field** |
| `modules/server/.../services/workspace/WorkspaceStore.scala` | Trait keyed entirely by `WorkspaceKeySecret`. `create()` returns `UIO[WorkspaceKeySecret]`. No `WorkspaceId` anywhere. |
| `modules/server/.../services/workspace/WorkspaceStoreLive.scala` | In-memory `Ref[Map[WorkspaceKeySecret, Workspace]]`. `create()` generates a `WorkspaceKeySecret`, builds `Workspace`, stores it. No `WorkspaceId`. |

### What does NOT exist yet

- `id: WorkspaceId` field on `Workspace` case class
- `AuthorizationServiceSpiceDB.scala` (live HTTP adapter)
- `SpiceDbConfig.scala` (connectivity config)
- `infra/spicedb/schema.zed`
- SpiceDB dependencies in `build.sbt` (plan: use existing sttp HTTP client — no new library)
- Config-driven mode selection in `Application.scala` (currently hardcoded to NoOp)
- `authzService.check()` on workspace-lifecycle routes (list, create, rotate, delete)
- `seed()` method on `AuthorizationService` trait

## What You Need to Implement

### Step 1: Wire `WorkspaceId` into the Domain Model

The `WorkspaceId` type already exists in `OpaqueTypes.scala`. The `.asResource`
extension already exists in `AuthorizationService.scala`. Wire it in:

1. Add `id: WorkspaceId` field to `Workspace` case class — generated at creation
2. Update `WorkspaceStore.create()` to generate a `WorkspaceId` (via `SafeId.generate` → `WorkspaceId(...)`)
3. Update `WorkspaceStoreLive` accordingly
4. Update all tests that construct `Workspace` instances
5. Thread `ws.id` through controller routes that need workspace-level SpiceDB checks

### Step 2: Create `schema.zed`

Create `infra/spicedb/schema.zed` with the exact schema from AUTHORIZATION-PLAN.md §Task L2.1.
Five definitions: `user`, `organization`, `team`, `workspace`, `risk_tree`.
The schema is fully specified in the plan — copy it verbatim.

### Step 3: Config-driven mode selection (Wave 2)

`Application.scala` currently hardcodes NoOp layers. Make it config-driven:

1. Load `AuthConfig` from application config (e.g. `Configs.makeLayer[AuthConfig]("register.auth")`)
2. Select layers based on `AuthConfig.mode`:
   - `"capability-only"` → `AuthorizationServiceNoOp.layer` + `UserContextExtractor.noOp`
   - `"identity"` → `AuthorizationServiceNoOp.layer` + `UserContextExtractor.requirePresent`
   - `"fine-grained"` → `AuthorizationServiceSpiceDB.layer` + `UserContextExtractor.requirePresent`
3. `UserContextExtractor.requirePresent` already exists — in `identity` mode, missing `x-user-id` header fails closed

### Step 4: SpiceDB adapter (Wave 3)

1. Create `configs/SpiceDbConfig.scala` — URL, token (redacted `SpiceDbToken`), consistency, timeout. See §SpiceDbConfig in plan.
2. Create `auth/AuthorizationServiceSpiceDB.scala`:
   - HTTP POST to SpiceDB `/v1/permissions/check` using existing sttp backend
   - HTTP POST to `/v1/permissions/resources` for `listAccessible`
   - Response mapping per §SpiceDB HTTP API Mapping table:
     - `PERMISSIONSHIP_HAS_PERMISSION` → `ZIO.unit`
     - `PERMISSIONSHIP_NO_PERMISSION` / `UNSPECIFIED` → `ZIO.fail(AuthForbidden(...))`
     - Any HTTP error / timeout → `ZIO.fail(AuthServiceUnavailable(...))`
   - OTel counter `authz.check.total` + histogram `authz.check.latency_ms` (follow `RiskTreeServiceLive` pattern)
   - Structured log per `check()` — use `user.value` for explicit PII opt-in

### Step 5: Add workspace-level `check()` calls

`WorkspaceController` tree-scoped routes already call `authzService.check()`.
The workspace-lifecycle routes do NOT. Add `check()` to:

- `listWorkspaceTreesEndpoint` → `check(userId, Permission.ViewWorkspace, ws.id.asResource)`
- `createWorkspaceTreeEndpoint` → `check(userId, Permission.DesignWrite, ws.id.asResource)`
- `rotateWorkspaceKeySecretEndpoint` → `check(userId, Permission.AdminWorkspace, ws.id.asResource)`
- `deleteWorkspaceEndpoint` → `check(userId, Permission.AdminWorkspace, ws.id.asResource)`

Note: `Permission.AdminWorkspace` and `Permission.AdminSystem` may need to be added to the enum if not present. Check AUTHORIZATION-PLAN.md §Route Inventory for the full permission matrix.

The order invariant in `serverLogic` is: `ws.resolve(key)` → `resolveTree(key, treeId)` (if tree-scoped) → `userCtx.extract` → `authzService.check` → handler body. L0 always before L2.

### Step 6: Bootstrap seeding (Wave 6)

1. Add `seed(userId: UserId, workspaceId: WorkspaceId): IO[AuthError, Unit]` to `AuthorizationService` trait
2. `AuthorizationServiceSpiceDB`: writes one tuple `workspace:{id}#owner_user@user:{userId}`
3. `AuthorizationServiceNoOp`: `ZIO.unit`
4. `AuthorizationServiceStub`: record call for test verification
5. Call `seed()` in `bootstrapWorkspace` serverLogic after workspace creation
6. If `seed()` fails, workspace creation fails (ZIO for-comprehension ensures this)

## Architecture Constraints

- **Fail-closed everywhere.** `check()` error = 403, never 200. Callers use `flatMap`, never `fold`.
- **No JWT code in app.** The mesh handles JWT. App reads `x-user-id` header only.
- **Anonymous sentinel `00000000-...-000000000000` must never have SpiceDB grants.**
- **SpiceDB receives userId only.** Never roles (OPA's domain), never email (display only).
- **Order invariant:** `ws.resolve(key)` → `userCtx.extract` → `authz.check` → handler. L0 before L2.
- **HTTP first.** Use existing sttp backend. No gRPC, no new library dependency.
- **Cache endpoints (routes 19–22) stay mesh-only.** No `check()` calls — OPA admin role only.

## Testing Strategy

- Unit: `AuthorizationServiceStub` (Set-backed) — no live SpiceDB needed
- Integration: existing `HttpApiIntegrationSpec` with NoOp layers must pass at every step
- New regression invariants (add to `RouteSecurityRegressionSpec`):
  1. All workspace-scoped routes → 403 when stub grants empty (fine-grained mode)
  2. All workspace-scoped routes → 200 when stub grants match expected permission
  3. `authz.check()` called exactly once per protected request
  4. Cache endpoints NOT tested for SpiceDB permission (mesh-only)

## Commit Strategy

One commit per step. Each commit passes all existing tests before merge.

## Infrastructure Context (register-infra — separate repo)

SpiceDB infrastructure is managed in `register-infra`. You do NOT modify that repo.
The infra team handles: SpiceDB Helm deployment (infra namespace, 2 replicas, PDB),
SOPS secrets, NetworkPolicy, schema loading via `zed schema write`, test
relationship seeding, and register Helm values (`AUTH_MODE`, `SPICEDB_URL`,
`SPICEDB_TOKEN`).

Your job is application code only. SpiceDB endpoint URL and token arrive
via environment variables mapped from Kubernetes secrets.

## File Paths Reference

Base: `modules/server/src/main/scala/com/risquanter/register/`

| Directory | Contents |
|-----------|----------|
| `auth/` | AuthorizationService (trait+enums), UserContext, UserContextExtractor, NoOp, Stub — add SpiceDB adapter here |
| `configs/` | AuthConfig — add SpiceDbConfig here |
| `services/workspace/` | WorkspaceStore, WorkspaceStoreLive — wire WorkspaceId here |
| `http/endpoints/` | BaseEndpoint (has `authedBaseEndpoint`), WorkspaceEndpoints, SSEEndpoints |
| `http/controllers/` | WorkspaceController (already has authz plumbing, needs workspace-level checks) |
| `http/sse/` | SSEController (already has full authz plumbing) |
| `Application.scala` | Main wiring — needs config-driven mode selection |

Common module base: `modules/common/src/main/scala/com/risquanter/register/`

| Directory | Contents |
|-----------|----------|
| `domain/data/iron/` | OpaqueTypes — `WorkspaceId` already defined here |
| `domain/data/` | Workspace.scala — add `id: WorkspaceId` field here |
| `domain/errors/` | AppError hierarchy — `AuthError`, `AuthForbidden`, `AuthServiceUnavailable` already exist |

Test base: `modules/server/src/test/scala/com/risquanter/register/`
Integration test base: `modules/server-it/src/test/scala/com/risquanter/register/`
