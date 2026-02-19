# Authorization Plan — Layers 1 & 2

**Date:** February 19, 2026
**Status:** Planning (not yet scheduled for implementation)
**Related:** [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) (Tier 1.5 = Layer 0)
**ADR References:** [ADR-012](./ADR-012.md) (Service Mesh), [ADR-021](./ADR-021-capability-urls.md) (Capability URLs), [ADR-023](./ADR-023-local-dev-tls-and-trust-material-policy.md) (Local Dev TLS & Trust Policy), [ADR-024](./ADR-024-externalized-authorization-pep-pattern.md) (Externalized Authorization / PEP Pattern)

---

## Overview

This document defines the authorization roadmap beyond the workspace capability model (Layer 0). The application uses a **layered authorization approach** implemented from a **single codebase**, with deployment mode selected via configuration.

### Layered Model

```
Layer 0 — Workspace Capability (Tier 1.5, IMPLEMENTATION-PLAN.md)
  │  URL: /#/{workspaceKey}/... (SAME URL scheme in ALL layers)
  │  Key role: SOLE CREDENTIAL (true capability)
  │  Knowledge of workspace key = access to all trees in workspace
  │  Free-tier: TTL-limited, reaper, rate limiting
  │  Enterprise: same URLs, same keys — but Layer 1+ adds gates
  │
Layer 1 — Identity + Ownership (this document)
  │  Key role: INVITATION TOKEN (necessary but insufficient)
  │  Key + valid JWT from right realm = access
  │  Pattern: "anyone with the link who is signed in" (like Google Docs)
  │  Sharing the URL IS sharing access — but only to authenticated users
  │  Leaked URL useless without valid session in the right Keycloak realm
  │
Layer 2 — Fine-Grained Authorization (this document)
  │  Key role: ROUTING TOKEN only (no authorization power)
  │  Key + JWT + explicit SpiceDB relationship = access
  │  Pattern: explicit ACL — relationships administered externally via ops path
  │  Per-resource roles: editor, analyst, viewer, admin
  │  Inheritance: org → team → workspace → tree
  │
  ▼
  Full multi-tenant SaaS capability
```

### Single Codebase, Config-Driven

All layers coexist in one codebase. The deployment mode determines which layers are active:

```hocon
register.auth {
  mode = "capability-only"    # Layer 0: free-tier public service
  # mode = "identity"         # Layer 0 + 1: enterprise with Keycloak
  # mode = "fine-grained"     # Layers 0 + 1 + 2: full multi-tenant
}
```

Each layer is **additive** — enabling a higher layer doesn't disable the lower ones; it adds extra authorization gates. The URL scheme (`/#/{workspaceKey}/...`) is **identical** across free-tier and enterprise, but the workspace key's **semantic role shifts** as layers are added:

| Layer | Key role | Access pattern |
|-------|---------|----------------|
| 0 | **Sole credential** (true capability) | Key = access |
| 0+1 | **Invitation token** (necessary but insufficient) | Key + JWT = access (invitation-link pattern) |
| 0+1+2 | **Routing token** (no auth power) | Key + JWT + SpiceDB relationship = access (ACL pattern) |

In "identity" mode (Layer 0+1), the workspace key acts as an invitation link — any authenticated user with the URL can access the workspace. This is analogous to Google Docs "anyone with the link who is signed in." In "fine-grained" mode (Layer 0+1+2), the key becomes purely a routing token — access is determined by explicit SpiceDB relationships, and the key alone has no authorization power.

### URL Scheme — Same Across All Layers

The URL scheme is **identical** in free-tier and enterprise deployments: `/#/{workspaceKey}/...`. Enterprise layers do **not** remove the workspace key from the URL — they make it **insufficient on its own** by requiring additional authorization.

| Layer | URL | Key role | Authorization Required | Leaked URL alone sufficient? |
|-------|-----|---------|----------------------|-----------------------------|
| 0 | `/#/{workspaceKey}/...` | Sole credential | Workspace key only | Yes (mitigated by TTL + security headers) |
| 0+1 | `/#/{workspaceKey}/...` | Invitation token | Key **+ valid JWT** from right realm | **No** — valid session required |
| 0+1+2 | `/#/{workspaceKey}/...` | Routing token | Key + JWT + **explicit SpiceDB relationship** | **No** — explicit membership required |

This design addresses the "leaked URL" concern progressively:
- **Layer 0+1:** The key acts as an invitation link. A leaked URL grants access only to users with a valid session in the right Keycloak realm — analogous to Google Docs "anyone with the link who is signed in."
- **Layer 0+1+2:** The key is purely a routing token. Access is determined by explicit SpiceDB relationships. A leaked URL grants nothing — the user must be explicitly added as a workspace member by the owner.

---

## Infrastructure Foundation (Phase K, k3s-first)

This section defines the dedicated infrastructure bootstrap needed before Layer 1 implementation.

### Deployment Baseline

- **Cluster:** k3s (single-node dev first, multi-node optional later)
- **Packaging:** Helm charts for stateful/system components
- **TLS:** cert-manager for local dev certificates (self-signed CA)
- **Persistence:** PostgreSQL as the primary state store (workspace + Keycloak)
- **Mesh/Auth edge:** Istio ambient mode + Keycloak
- **CI/CD (slim):** GitHub Actions + GHCR + Helm deploy jobs (no separate CD platform initially)
- **IaC boundary:** Start with Helm + Kubernetes manifests; add Terraform later only when provisioning managed cloud infra resources

### Phase Plan (Aligned)

#### Phase K.1 — Local K8s Bootstrap (k3s)

- Install k3s with reproducible bootstrap script
- Define base namespaces (`register`, `infra`, `observability`)
- Install baseline controllers/operators needed by later phases
- Add storage class and persistent volume policy for dev

#### Phase K.2 — Container Registry + Image Pipeline

- Choose registry strategy:
  - local dev: k3s embedded registry mirror or local OCI registry container
  - shared/dev-prod-like: GHCR (or other OCI registry)
- Define image naming/tagging policy (`:git-sha`, `:semver`, `:latest` for dev only)
- Configure pull secrets for private images if needed
- Wire build-and-push in CI

#### Phase K.3 — PostgreSQL on K8s (Helm chart)

- Deploy PostgreSQL via Helm (single instance is acceptable initially)
- Create separate DBs/schemas for app data and Keycloak
- Enable persistent storage, backup hooks, and health checks
- Add migration job path for schema changes

#### Phase K.4 — Keycloak on K8s (Helm chart + realm config)

- Deploy Keycloak with PostgreSQL backing store
- Provision realm (`register`), clients (`register-api`, `register-web`), roles, and mappers
- Export/import realm config as code (versioned)
- Validate OIDC login flow over HTTPS

#### Phase K.5 — Istio Ambient Mode Install

- Install Istio in ambient mode for cluster
- Configure waypoint(s), `RequestAuthentication`, and `AuthorizationPolicy`
- Verify JWT validation and claim header injection (`outputClaimToHeaders` in `RequestAuthentication`)
- Confirm app reads only mesh-injected `x-user-id` header — zero JWT code in application
- Verify waypoint strips external `x-user-*` headers before claim injection (see [ADR-012: Claim Header Injection](./ADR-012.md#6-claim-header-injection))

#### Phase K.6 — CI/CD Pipeline (GitHub Actions, minimal)

- Implement CI workflow on pull requests: format/lint/test + SCA/dependency scan
- Implement image build workflow on main: build OCI image and push to GHCR (`:git-sha`, optional `:latest` for dev)
- Implement deployment workflow with Helm:
  - target `local-dev` (k3s) via manual dispatch or protected branch
  - keep manifests/charts provider-neutral for future managed Kubernetes
- Implement **authorization graph provisioning job** (CI/CD one-shot):
  - fetch org/team mapping config from configurable Git source
  - resolve Keycloak groups → internal team grants
  - upsert SpiceDB relationships idempotently
  - fail pipeline on drift/validation errors (no runtime auto-bootstrap in app)
- Add minimal release controls: GitHub Environments, required approvals for non-dev deploys
- Add rollback command path (`helm rollback`) and one smoke-check step post-deploy

#### Phase K.7 — Managed Kubernetes Readiness (future)

- Add Terraform only for provider resources (cluster, network, DNS, load balancer, secret store)
- Reuse existing Helm charts and image pipeline unchanged
- Add environment matrix in GitHub Actions (`local-dev`, `staging`, `prod`)

### Explicit Infrastructure Tasks and Priorities

| Item | Why | Priority | Planned Phase |
|------|-----|----------|---------------|
| Container registry | K8s must pull images from a reachable OCI registry. | **High** — blocks deployment | K.2 |
| TLS certificates | Keycloak OIDC and secure cookies require HTTPS. | **High** — blocks Layer 1 auth flow | K.1 + K.4 |
| PostgreSQL instance | Required for Keycloak persistence and durable workspace storage. | **High** — blocks Layer 1 | K.3 |
| Config endpoint | Frontend must read `auth.mode` and feature flags. | **Medium** — blocks L1.3 | L1.3 |
| Anonymous → authenticated migration | Needed to claim existing capability-only workspaces after upgrade. | **Medium** — design decision | L1.2 + Migration |
| SpiceDB foundation setup | Layer 2 implementation requires schema, deployment, and integration baseline. | **Medium** — blocks L2.1+ | L2.0 |
| Auth graph provisioning pipeline | SpiceDB tuples must be managed outside app runtime with auditable CI/CD flow. | **Medium** — blocks secure L2 operations | K.6 + L2.0 |
| Observability for auth | Need traces/metrics for JWT/authz failures and debugging. | **Medium** — testing aid | K.5 + L1.6 |
| Security scanning in CI | Supports supply-chain controls and dependency hygiene from day one. | **Medium** — should be baseline | K.6 |
| Load testing auth middleware | Validate mesh JWT overhead is acceptable before scale-out. | **Low** — post-L1 hardening | Post L1 |
| Backup/restore | Needed for PostgreSQL + Keycloak disaster recovery readiness. | **Low** — production concern | K.3 + K.7 |

---

## Layer 1: Identity + Ownership

### Prerequisites

- Tier 1.5 complete (workspace model exists)
- Keycloak instance available (dev: Docker container; prod: managed service)
- Istio ambient mode deployed (or dev mode mock)

### Architecture

```
┌──────────┐     ┌───────────┐     ┌──────────────┐     ┌──────────────┐
│  Browser │────▶│  Keycloak │────▶│ Istio Waypoint│────▶│ ZIO Backend  │
│          │     │  (login)  │     │ (JWT verify)  │     │              │
│  JWT in  │     │  JWT      │     │ x-user-id     │     │ UserContext  │
│  cookie  │     │  issued   │     │ injected      │     │ extracted    │
└──────────┘     └───────────┘     └──────────────┘     └──────────────┘
```

### Task L1.1: UserContext Extraction

**Files:**
```
server/.../auth/UserContext.scala
server/.../auth/UserContextExtractor.scala
```

**Role enum:**
```scala
// Values mirror OPA Rego recognized role names exactly.
// Unknown claim strings are dropped — never fail on unrecognised claims.
enum Role:
  case Analyst    // may run analysis, create scenario branches; cannot mutate canonical design
  case Editor     // ⊇ Analyst: may also mutate canonical design
  case TeamAdmin  // may manage team structure

object Role:
  def fromClaim(s: String): Option[Role] = s match
    case "analyst"    => Some(Analyst)
    case "editor"     => Some(Editor)
    case "team_admin" => Some(TeamAdmin)
    case _            => None
```

**UserContext:**
```scala
final case class UserContext(
  userId: UserId,       // from `x-user-id` header (mesh-injected JWT `sub`) — the ONLY field passed to SpiceDB check()
  email: Option[Email], // from `x-user-email` header (mesh-injected JWT `email`) — display only, never used for authorization
  roles: Set[Role]      // from `x-user-roles` header (mesh-injected JWT `realm_access.roles`) — OPA coarse gate only (see [ADR-012: OPA for Authorization](./ADR-012.md#3-opa-for-authorization), [ADR-024: SpiceDB Receives userId Only](./ADR-024-externalized-authorization-pep-pattern.md#4-spicedb-receives-userid-only))
                        // SpiceDB does NOT receive roles; the relationship graph is authoritative
)
```

**Extraction pattern (from [ADR-012: Minimal Service Code for Auth](./ADR-012.md#5-minimal-service-code-for-auth)):**
- Mesh validates JWT signature, expiry, issuer (via `RequestAuthentication` + JWKS endpoint)
- Istio injects validated claims as plain HTTP headers via `outputClaimToHeaders` — app never sees the raw JWT
- App reads `x-user-id` header (mesh-injected UUID string from JWT `sub`) → UUID format validation → `UserId`
- App contains zero JWT code — no base64 decode, no claim extraction, no JWT library dependency
- Security: waypoint strips external `x-user-*` headers before injection (see [ADR-012: Claim Header Injection](./ADR-012.md#6-claim-header-injection)); header presence is an infrastructure assertion, not a client claim

**Dev mode (no mesh):**
- Config: `register.auth.dev-mode.enabled = true`
- Mock `UserContext` with configurable default user
- All requests treated as the dev user

**Note:** Cheleb reference architecture is relevant for PostgreSQL + ZIO layer patterns (see Tier 1.5, Phase W.2), but does **not** contain Keycloak/OAuth2 patterns. For Keycloak integration, consult Keycloak documentation and Istio RequestAuthentication examples directly.

### Task L1.2: Workspace Ownership

**Files:**
```
server/.../service/workspace/WorkspaceOwnership.scala
```

**Schema addition:**
```sql
ALTER TABLE workspaces ADD COLUMN owner_id TEXT;
CREATE INDEX idx_workspaces_owner ON workspaces(owner_id);
```

**Behaviour:**
- On workspace creation: `owner_id = userContext.userId`
- On workspace access: verify `owner_id == userContext.userId` (or workspace is shared — Layer 2)
- Workspace key still works for anonymous/shared access (backward compatible)

**Access resolution (depends on `register.auth.mode`):**

*capability-only mode (free-tier) — key = sole credential:*
1. Workspace key in URL → resolve workspace → grant access
2. No workspace key → 401 Unauthorized

*identity mode (enterprise) — key = invitation token:*
1. Workspace key in URL + valid JWT from right realm → grant access (invitation-link: the URL *is* the invitation)
2. Workspace key in URL + no JWT → 401 Unauthorized (key alone insufficient)
3. No workspace key → 401 Unauthorized
4. `owner_id` recorded on first authenticated access (workspace claiming) but not used as an access gate — that's Layer 2's job

*fine-grained mode (enterprise+) — key = routing token only:*
1. Workspace key in URL + valid JWT + explicit SpiceDB relationship → grant access
2. Workspace key in URL + valid JWT + no SpiceDB relationship → 403 Forbidden
3. Workspace key in URL + no JWT → 401 Unauthorized
4. No workspace key → 401 Unauthorized

### Task L1.3: Frontend Login Flow

**Files:**
```
app/.../core/AuthClient.scala
app/.../views/LoginView.scala
app/.../state/AuthState.scala
```

**Flow:**
1. App checks `register.auth.mode` config (served via `/config` endpoint or embedded in HTML)
2. If `mode == "identity"` or `"fine-grained"`:
   - Show login button
   - Redirect to Keycloak login page (OIDC authorization code flow)
   - On callback: Keycloak sets HTTP-only cookie with JWT
   - App reads user info from `/auth/me` endpoint
3. If `mode == "capability-only"`:
   - No login UI (current free-tier behaviour)

**Security:** The SPA stores JWT in memory only (not `localStorage`) — prevents XSS-based token exfiltration.

**URL scheme:**
- URL remains `/#/{workspaceKey}/...` (same as free-tier)
- No URL change — enterprise uses identical workspace key URLs
- Login button and user menu added to UI; workspace URLs unchanged

**Note:** Cheleb reference architecture does **not** contain OAuth2/OIDC patterns. For OIDC authorization code flow, consult Keycloak JS adapter documentation directly.

### Task L1.4: My Workspaces API

**Endpoints (identity-authenticated):**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/my/workspaces` | List workspaces owned by authenticated user |
| POST | `/my/workspaces` | Create workspace (owned by user, no TTL) |
| DELETE | `/my/workspaces/{id}` | Delete workspace |

These endpoints require JWT — scoped to the authenticated user. Enterprise workspaces have no TTL (owned, not ephemeral).

### Task L1.5: Istio Configuration

#### Bootstrap (Pre-Deploy)

Before deploying Layer 1, the following must be configured:

1. **Keycloak:**
   - Create realm: `register`
   - Create client: `register-api` (confidential, service account enabled)
   - Create client: `register-web` (public, PKCE flow)
   - Define roles: admin, analyst, viewer
   - Configure mappers: include roles in JWT claims
   - Export public key for JWT validation

2. **OPA Policy Bundle:**
   - `allow_read.rego` → viewer, analyst, admin
   - `allow_write.rego` → analyst, admin
   - `allow_admin.rego` → admin only
   - Deploy to OPA bundle server (or ConfigMap)

#### RequestAuthentication (Keycloak JWKS)

**RequestAuthentication (Keycloak JWKS):**
```yaml
apiVersion: security.istio.io/v1
kind: RequestAuthentication
metadata:
  name: keycloak-jwt
  namespace: register
spec:
  jwtRules:
  - issuer: "https://keycloak.example.com/realms/register"
    jwksUri: "https://keycloak.example.com/realms/register/protocol/openid-connect/certs"
    audiences:
    - "register-api"
    outputClaimToHeaders:
    - header: x-user-id
      claim: sub
    - header: x-user-email
      claim: email
    - header: x-user-roles
      claim: realm_access.roles
    # forwardOriginalToken is NOT set — the app never receives the raw JWT
```

**AuthorizationPolicy (protect non-public routes):**
```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: require-jwt
  namespace: register
spec:
  action: ALLOW
  rules:
  - from:
    - source:
        requestPrincipals: ["*"]  # any valid JWT
  - to:
    - operation:
        paths: ["/w/*", "/workspaces", "/health"]  # public routes
```

### Task L1.6: Tests

- `UserContextExtractor` parses claims correctly
- Dev mode provides mock user
- Workspace ownership enforced (owner only)
- Login flow round-trip (integration test)

### Estimated Effort: ~2–3 weeks

---

## Layer 2: Fine-Grained Authorization

### Prerequisites

- Layer 1 complete (user identity exists)
- SpiceDB selected and baseline instance available

### Phase L2.0 — SpiceDB Foundation (Decision Closed)

SpiceDB is the selected Layer 2 backend. This phase establishes the implementation baseline before L2.1.

### Decision Outcome

- **Selected backend:** SpiceDB
- **Rationale:** Best fit for inherited workspace/tree permissions, explicit relationship modeling, and expected collaboration growth (Tier 3/4)
- **Integration mode:** Start with HTTP client path for simplicity; move to gRPC later only if needed
- **Provisioning mode:** **B) CI/CD one-shot task** (application runtime does not bootstrap/seed authorization data)
- **Identity mapping now:** Keycloak groups map to team grants automatically
- **Indirection requirement:** Keep mapping/provisioning behind an adapter boundary so source can switch later to config-driven or hybrid without service API changes

### L2.0 Exit Criteria

- Deploy SpiceDB on k3s (Helm) with persistent storage
- Apply initial `.zed` schema (`workspace`, `risk_tree`, inherited permissions)
- Implement `AuthorizationServiceSpiceDB` with `check` and `listAccessible` only — app is a pure PEP, never writes tuples (see ADR-024)
- Implement `AuthzProvisioning` job in CI/CD (idempotent reconcile, drift detection, audit logs)
- Ensure app runtime never writes authorization data — no `grant`/`revoke`/tuple writes in application code (see ADR-024)
- Verify latency budget for `check` on representative workspace/tree paths
- Record operational runbook (backup, upgrade, health checks)

### Task L2.1: Authorization Schema

#### Schema Location

The schema lives in the application repository at `infra/spicedb/schema.zed`, versioned alongside the application code that references its permission names. This ensures:
- Schema changes and Scala code changes (permission name references) are atomic — same PR, same CI run, same git SHA deployed
- A schema rename (e.g. `design_write` → `write`) that breaks `check("design_write", ...)` call sites fails CI immediately, not silently at runtime
- Commit signing (ADR-020) covers schema changes automatically
- No additional credentials or fetch infrastructure for schema apply

The CI provisioning job accepts a configurable schema source for enterprise deployers with a dedicated policy team:

```hocon
register.authz.provisioning {
  schema-source = "classpath:/spicedb/schema.zed"  # default: in-repo
  # schema-source = "https://policy.internal/schemas/register/v2.zed"  # enterprise override
}
```

#### Schema Decisions

- **No `org_admin` in Zed (M3):** Org-wide emergency operations use Keycloak account disable + audited `zed` CLI bulk-delete per ops runbook. No escape-hatch relation in the schema.
- **Explicit grants only:** No org-default inheritance. A user must be explicitly related to a team or workspace to receive any permission.
- **Action-oriented permissions:** `design_write`, `analyze_run`, `view_*` — named for what they allow, not for a role title.
- **`editor` ⊇ `analyst`:** An editor can do everything an analyst can. An analyst can run analysis and create scenario branches but cannot mutate the canonical design.
- **Dual ownership model:** Workspaces may be owned by a team (`owner_team`) or directly by a user (`owner_user`) for team-less workspaces. Explicit, not inherited from org.

#### SpiceDB Schema (`infra/spicedb/schema.zed`)

```zed
definition user {}

definition organization {
  relation org_member: user
  // No org_admin in Zed — M3: org-wide emergency ops use Keycloak disable
  // + audited zed CLI bulk-delete per ops runbook. No schema escape hatch.

  permission view_org = org_member
}

definition team {
  relation organization: organization
  relation team_admin:   user
  relation editor:       user  // editor ⊇ analyst: may mutate canonical design
  relation analyst:      user  // may create scenario branches; cannot mutate canonical design
  relation viewer:       user

  permission manage_team  = team_admin
  permission design_write = editor + team_admin
  permission analyze_run  = analyst + editor + team_admin
  permission view_team    = viewer + analyst + editor + team_admin
}

definition workspace {
  relation organization: organization
  relation owner_team:   team
  relation owner_user:   user  // direct ownership for team-less workspaces
  relation editor:       user
  relation analyst:      user
  relation viewer:       user

  permission design_write   = editor + owner_user + owner_team->design_write
  permission analyze_run    = analyst + editor + owner_user + owner_team->analyze_run
  permission view_workspace = viewer + analyst + editor + owner_user + owner_team->view_team
}

definition risk_tree {
  relation workspace: workspace
  relation editor:    user
  relation analyst:   user
  relation viewer:    user

  permission design_write = editor + workspace->design_write
  permission analyze_run  = analyst + editor + workspace->analyze_run
  permission view_tree    = viewer + analyst + editor + workspace->view_workspace
}
```

This gives:
- **Org layer:** membership only; no admin escape hatch in the schema
- **Team layer:** four roles with explicit permission hierarchy; team owns workspaces via `owner_team`
- **Workspace layer:** team permissions flow in via `owner_team->*`; direct user ownership via `owner_user`; per-workspace role overrides for individual users
- **Risk tree layer:** workspace permissions flow in via `workspace->*`; per-tree role overrides for individual users

### Task L2.2: Authorization Service

#### Files

```
server/.../auth/AuthorizationService.scala          — trait, enums (Permission, ResourceType, ResourceRef, AuthError)
server/.../auth/AuthorizationServiceSpiceDB.scala   — live HTTP adapter (SpiceDB)
server/.../auth/AuthorizationServiceNoOp.scala      — always-allow stub (capability-only / identity modes)
server/.../auth/AuthorizationServiceStub.scala      — configurable stub for unit tests
server/.../configs/SpiceDbConfig.scala              — connectivity + consistency config
common/.../domain/data/iron/OpaqueTypes.scala       — add UserId, WorkspaceId
common/.../domain/errors/AppError.scala             — add AuthError sealed trait + variants
common/.../http/codecs/AuthTapirCodecs.scala        — Tapir codec for UserId (UUID header codec for `x-user-id` claim header; separate from IronTapirCodecs)
```

#### Type Inventory

**`UserId`** — redacted `final class` wrapping the `x-user-id` claim header value (mesh-injected JWT `sub`), validated as RFC 4122 UUID.

**Format rationale — UUID, not ULID:** The format is an **external constraint**. Keycloak issues `sub` as UUID v4; we consume it verbatim. SpiceDB imposes no format requirement on `objectId`. If the IdP were swapped, the regex constraint would change here. A looser `NonBlank` constraint would be more IdP-agnostic but less correct for our Keycloak setup.

**Not a secret, but PII:** `userId` is a pseudonymous stable identifier, not a credential. However it links to a natural person via Keycloak (GDPR personal data). Accidental inclusion in a log interpolation (`s"failed for $user"`) must produce `UserId(***)` — not the raw UUID. This enforcement requires `final class` + `toString` override, identical to `WorkspaceKeySecret`. Unlike that type, the extraction method is `.value` (standard Iron convention) rather than `.reveal` — the name signals this is not a secret, but explicit extraction is still required.

```scala
// In OpaqueTypes.scala — follows WorkspaceKeySecret pattern, not the opaque-type pattern.
// Opaque types cannot override toString; final class can.
type UuidStr = String :| Match["^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"]

final class UserId private (private val raw: UuidStr):
  def value: String = raw           // explicit extraction — use in SpiceDB calls and audit logs only
  override def toString: String = "UserId(***)"    // prevents accidental PII in log interpolations
  override def hashCode: Int = raw.hashCode
  override def equals(that: Any): Boolean = that match
    case u: UserId => raw == u.raw
    case _         => false

object UserId:
  def apply(s: UuidStr): UserId = new UserId(s)
  def fromString(s: String): Either[List[ValidationError], UserId] =
    ValidationUtil.refineUserId(s)  // delegates to Iron refineEither[UuidStr]

  given JsonEncoder[UserId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[UserId] = JsonDecoder[String].mapOrFail(s =>
    UserId.fromString(s).left.map(_.mkString(", ")))
```

**`WorkspaceId`** — stable non-secret ULID identifier for SpiceDB resource references. **Does not yet exist in the codebase.** The current `Workspace` domain model uses only `WorkspaceKeySecret` as its identity, which was intentional at Layer 0.

`WorkspaceKeySecret` cannot serve as the SpiceDB `objectId` for two reasons:
- It is a **secret credential** — SpiceDB logs relationship tuples for auditing; logging the capability key would be a security incident
- It **changes on `rotate()`** — `WorkspaceStore.rotate()` exists; using the key as a stable resource ID would invalidate all SpiceDB tuples on rotation

This type mirrors `TreeId` exactly: nominal `case class` wrapper over `SafeId` (ULID), compiler-distinct, non-secret, stable for the workspace's lifetime.

```scala
// Nominal case class wrapper over SafeId (ULID) — compiler-distinct from TreeId.
// Mirrors TreeId pattern exactly.
case class WorkspaceId(toSafeId: SafeId.SafeId):
  def value: String = toSafeId.value.toString

object WorkspaceId:
  def fromString(s: String): Either[List[ValidationError], WorkspaceId] =
    SafeId.fromString(s).map(WorkspaceId(_))
  given JsonEncoder[WorkspaceId] = JsonEncoder[String].contramap(_.value)
  given JsonDecoder[WorkspaceId] = JsonDecoder[String].mapOrFail(s =>
    WorkspaceId.fromString(s).left.map(_.mkString(", ")))
```

**Implementation note:** `workspaceId: WorkspaceId` should be added to `Workspace` at **Layer 1** implementation time (generated alongside the capability key at creation), not Layer 2. This avoids a data migration — workspaces created before Layer 2 will already have a stable non-secret ID when SpiceDB integration is added.

**`ResourceType`** — sealed enum. Values match Zed schema `definition` names exactly. Used to construct the `objectType` field in SpiceDB requests.

```scala
enum ResourceType(val zedType: String):
  case Organization extends ResourceType("organization")
  case Team         extends ResourceType("team")
  case Workspace    extends ResourceType("workspace")
  case RiskTree     extends ResourceType("risk_tree")
```

**`Permission`** — sealed enum. Values match Zed schema `permission` names exactly. The `zedName` field is the only source of truth for the wire value — a schema rename that doesn't update this enum fails CI immediately at the `check("old_name", ...)` call sites.

```scala
enum Permission(val zedName: String):
  case DesignWrite   extends Permission("design_write")
  case AnalyzeRun    extends Permission("analyze_run")
  case ViewWorkspace extends Permission("view_workspace")
  case ViewTree      extends Permission("view_tree")
  case ViewOrg       extends Permission("view_org")
  case ViewTeam      extends Permission("view_team")
  case ManageTeam    extends Permission("manage_team")
```

**`ResourceRef`** — typed resource reference for `check()` calls. `resourceId` reuses `SafeId` (ULID) since all our resources (Workspace, RiskTree) are identified by ULIDs.

```scala
case class ResourceRef(resourceType: ResourceType, resourceId: SafeId.SafeId)

object ResourceRef:
  extension (id: TreeId)
    def asResource: ResourceRef = ResourceRef(ResourceType.RiskTree, id.toSafeId)

  extension (id: WorkspaceId)
    def asResource: ResourceRef = ResourceRef(ResourceType.Workspace, id.toSafeId)
```

**`ResourceId`** — return element type of `listAccessible`. Alias for `SafeId`; caller promotes to `TreeId` / `WorkspaceId` as needed.

```scala
type ResourceId = SafeId.SafeId
```

**`AuthError`** — sealed authorization error hierarchy, extending the existing `AppError` sealed trait (ADR-010). Lives in `common/.../domain/errors/AppError.scala`.

```scala
sealed trait AuthError extends AppError

// SpiceDB returned PERMISSIONSHIP_NO_PERMISSION — explicit deny.
case class AuthForbidden(
  userId:       String,  // sub claim — not email, not PII-sensitive in audit logs
  permission:   String,
  resourceType: String,
  resourceId:   String
) extends AuthError:
  override def getMessage: String =
    s"Access denied: user=$userId permission=$permission resource=$resourceType:$resourceId"

// SpiceDB unreachable, timeout, or unexpected response — fail-closed.
// HTTP response is 403 (not 503) — returning 503 would reveal infrastructure state.
case class AuthServiceUnavailable(reason: String, cause: Option[Throwable] = None) extends AuthError:
  override def getMessage: String = s"Authorization service unavailable: $reason"
  override def getCause: Throwable = cause.orNull
```

#### Trait

```scala
trait AuthorizationService:

  def check(
    user:       UserId,
    permission: Permission,
    resource:   ResourceRef
  ): IO[AuthError, Unit]
  // Fails the ZIO effect — callers use flatMap, never fold/map (fail-closed, [ADR-024: Fail-Closed by Default](./ADR-024-externalized-authorization-pep-pattern.md#5-fail-closed-by-default)).
  // PERMISSIONSHIP_NO_PERMISSION  → AuthForbidden
  // Connectivity failure          → AuthServiceUnavailable (mapped to 403 at HTTP layer)
  // No grant() / revoke() — pure PEP; tuple writes are ops-path only ([ADR-024: App is PEP Only](./ADR-024-externalized-authorization-pep-pattern.md#1-app-is-pep-only)).

  def listAccessible(
    user:         UserId,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]]
  // Calls SpiceDB LookupResources API.
  // Used for "show my workspaces" — returns IDs of all resources where the user
  // has the given permission; no local DB join required.
```

#### SpiceDB HTTP API Mapping

**Check — POST `/v1/permissions/check`:**

```json
// Request (field names are camelCase in SpiceDB v1 REST API)
{
  "resource":    { "objectType": "risk_tree", "objectId": "01HXY..." },
  "permission":  "design_write",
  "subject":     { "object": { "objectType": "user", "objectId": "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf" } },
  "consistency": { "minimizeLatency": {} }
}

// Response — allowed
{ "permissionship": "PERMISSIONSHIP_HAS_PERMISSION" }

// Response — denied
{ "permissionship": "PERMISSIONSHIP_NO_PERMISSION" }
```

Scala mapping table:

| SpiceDB response | ZIO effect result | Notes |
|-----------------|-------------------|-------|
| `PERMISSIONSHIP_HAS_PERMISSION` | `ZIO.unit` | Check passes |
| `PERMISSIONSHIP_NO_PERMISSION` | `ZIO.fail(AuthForbidden(...))` | Hard deny |
| `PERMISSIONSHIP_UNSPECIFIED` | `ZIO.fail(AuthForbidden(...))` | Treat as deny — safe default |
| HTTP 4xx | `ZIO.fail(AuthServiceUnavailable(...))` | Config/auth error |
| HTTP 5xx / timeout | `ZIO.fail(AuthServiceUnavailable(...))` | Fail-closed |
| Network error | `ZIO.fail(AuthServiceUnavailable(...))` | Fail-closed |

**List Accessible — POST `/v1/permissions/resources`:**

```json
{
  "resourceObjectType": "workspace",
  "permission":         "view_workspace",
  "subject":            { "object": { "objectType": "user", "objectId": "..." } },
  "consistency":        { "minimizeLatency": {} }
}
// Response: streamed list — collect into List[ResourceId]
```

#### ZedToken Consistency Strategy

The app is a pure PEP — it never writes tuples. The CI provisioning job is external. There is no "write then immediately check" pattern in app code, so `at_least_as_fresh` semantics are not needed.

| Mode | Config value | Behaviour | Latency |
|------|-------------|-----------|---------|
| Default | `minimize_latency` | SpiceDB uses cache (NewEnemy) | ~1ms overhead |
| High-compliance | `fully_consistent` | Always reads PostgreSQL snapshot | ~5ms overhead |

```hocon
register.authz.spicedb {
  consistency = "minimize_latency"  # default; set "fully_consistent" for high-compliance environments
}
```

Start with `minimize_latency`. Add `fully_consistent` as a config option for environments with strict audit requirements.

#### Failure Modes

| Failure | HTTP response | Rationale |
|---------|--------------|-----------|
| SpiceDB: `NO_PERMISSION` / `UNSPECIFIED` | 403 Forbidden | Explicit deny |
| SpiceDB: HTTP 4xx (config/auth) | 403 Forbidden | Fail-closed; don't reveal infra |
| SpiceDB: HTTP 5xx / timeout | 403 Forbidden | Fail-closed; 503 would reveal SpiceDB is down |
| SpiceDB: network error | 403 Forbidden | Fail-closed |
| Caller uses `.fold(_ => (), ...)` on `check()` | Compile succeeds; auth bypassed | Code smell — [ADR-024: Fail-Closed by Default](./ADR-024-externalized-authorization-pep-pattern.md#5-fail-closed-by-default); catch in PR review |

#### Observability

Every `check()` emits a structured log event at INFO level. `UserId.toString` is redacted — audit log calls must use `user.value` explicitly (same pattern as `WorkspaceKeySecret.reveal`). `email` is never logged (display only, per [ADR-024: SpiceDB Receives userId Only](./ADR-024-externalized-authorization-pep-pattern.md#4-spicedb-receives-userid-only)).

```scala
// In AuthorizationServiceSpiceDB — correct pattern:
ZIO.logInfo(s"authz.check user=${user.value} permission=${permission.zedName} resource=${resource.resourceType.zedType}:${resource.resourceId.value} result=$result latency_ms=$latencyMs")
// user.value = explicit opt-in to emit PII in audit log
// user alone (toString) would produce: user=UserId(***)
```

Formatted output:
```
authz.check user=8f14e45f-... permission=design_write resource=risk_tree:01HXY result=allowed latency_ms=7
authz.check user=8f14e45f-... permission=design_write resource=risk_tree:01HXY result=denied latency_ms=4
authz.check user=8f14e45f-... permission=design_write resource=risk_tree:01HXY result=error reason=spicedb_unavailable latency_ms=5002
```

**Metrics** (via existing OTel `Meter` pattern from `RiskTreeServiceLive`):
- Counter: `authz.check.total` — labels: `result={allowed,denied,error}`, `permission`, `resource_type`
- Histogram: `authz.check.latency_ms` — SpiceDB HTTP round-trip latency

**Traces:** One child span per `check()` call, named `spicedb.check`. Attributes: `permission`, `resource_type`, `resource_id`. **No `userId` in trace attributes** — avoid PII in trace backends that may not be access-controlled.

#### `SpiceDbConfig`

```scala
final case class SpiceDbConfig(
  url:            SafeUrl,                  // SpiceDB HTTP endpoint (e.g., "https://spicedb.infra:50051")
  token:          SpiceDbToken,             // API bearer token — redacted in toString (WorkspaceKeySecret pattern)
  consistency:    SpiceDbConsistency = SpiceDbConsistency.MinimizeLatency,
  timeoutSeconds: Int = 10
)

enum SpiceDbConsistency:
  case MinimizeLatency  // default — SpiceDB uses cached NewEnemy resolver
  case FullyConsistent  // always reads PostgreSQL snapshot

// SpiceDbToken: redacted credential (follows WorkspaceKeySecret pattern — no unapply, toString redacted)
final class SpiceDbToken private (private val raw: String):
  def reveal: String = raw
  override def toString: String = "SpiceDbToken(***)"
```

#### `AuthorizationServiceSpiceDB` — Layer Signature

```scala
final class AuthorizationServiceSpiceDB private (
  config:       SpiceDbConfig,
  backend:      SttpBackend[Task, Any],
  tracing:      Tracing,
  checkCounter: Counter[Long],
  checkLatency: Histogram[Double]
) extends AuthorizationService:
  // Instruments created once at layer construction — same pattern as RiskTreeServiceLive.
  ...

object AuthorizationServiceSpiceDB:
  val layer: ZLayer[SpiceDbConfig & SttpBackend[Task, Any] & Tracing & Meter, Throwable, AuthorizationService] =
    ZLayer {
      for
        config   <- ZIO.service[SpiceDbConfig]
        backend  <- ZIO.service[SttpBackend[Task, Any]]
        tracing  <- ZIO.service[Tracing]
        meter    <- ZIO.service[Meter]
        counter  <- meter.counter("authz.check.total", Some("1"), Some("Authorization check results"))
        latency  <- meter.histogram("authz.check.latency_ms", Some("ms"), Some("SpiceDB check latency"))
      yield new AuthorizationServiceSpiceDB(config, backend, tracing, counter, latency)
    }
```

#### Dev/Test Stubs

```scala
// Wired when register.auth.mode = "capability-only" or "identity" — no SpiceDB needed.
// All checks pass; listAccessible returns Nil (no fine-grained filtering in those modes).
object AuthorizationServiceNoOp extends AuthorizationService:
  def check(user: UserId, permission: Permission, resource: ResourceRef): IO[AuthError, Unit] =
    ZIO.unit
  def listAccessible(user: UserId, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]] =
    ZIO.succeed(Nil)

// For unit tests — allow/deny by explicit (user, permission, resource) set.
// No live SpiceDB required; injected via ZLayer in test scope.
class AuthorizationServiceStub(
  allowed: Set[(UserId, Permission, ResourceRef)]
) extends AuthorizationService:
  def check(user: UserId, permission: Permission, resource: ResourceRef): IO[AuthError, Unit] =
    if allowed.contains((user, permission, resource)) then ZIO.unit
    else ZIO.fail(AuthForbidden(user.value, permission.zedName, resource.resourceType.zedType, resource.resourceId.value))
  def listAccessible(user: UserId, resourceType: ResourceType, permission: Permission): IO[AuthError, List[ResourceId]] =
    ZIO.succeed(allowed.collect {
      case (u, p, ResourceRef(rt, id)) if u == user && p == permission && rt == resourceType => id
    }.toList)
```

#### Adapter Boundary — gRPC Migration Path

The trait hides transport entirely. When gRPC is needed:

1. Add `scalapb` + SpiceDB gRPC stubs as dependencies
2. Create `AuthorizationServiceSpiceDbGrpc extends AuthorizationService`
3. Wire via config: `register.authz.spicedb.transport = http | grpc`
4. Zero changes to any `check()` call site — the trait is the stable interface

HTTP first; gRPC only if latency profiling shows the HTTP overhead exceeds budget under load.

### Task L2.3: Access Administration (Ops Path — Not App Logic)

Access grant and revoke operations are **not implemented in the application**. The app is a pure PEP (see ADR-024): it calls `check()` and `listAccessible()` only; it never writes authorization data.

Access administration paths:
- **Org/team-level provisioning:** CI/CD job (K.6) — idempotent reconcile from config, Keycloak groups → SpiceDB team relations
- **Individual workspace access changes:** `zed` CLI or SpiceDB HTTP API, gated behind ops service account with audit logging to SIEM
- **Emergency bulk revocation (M3 break-glass):** disable account in Keycloak (stops token issuance) + audited tuple bulk-delete via `zed` CLI per ops runbook

Any future self-service access management UI would be a dedicated administrative service (a separate PAP), distinct from this application's codebase and deployment, and is explicitly out of scope for this project.

### Task L2.4: OPA and SpiceDB — Complementary Layers

OPA and SpiceDB are **not alternatives**. They are complementary and both remain active in the stack (ADR-012). They answer fundamentally different questions at different layers.

#### Responsibility Split

| Concern | OPA (mesh layer — Istio ext_authz) | SpiceDB (app layer — `AuthorizationService`) |
|---------|-------------------------------------|----------------------------------------------|
| Input | JWT claims + HTTP method/path | User ID + permission name + resource ID |
| State | None — purely claim-based | Relationship graph (PostgreSQL-backed) |
| Speed | Sub-millisecond (sidecar/in-process) | Network hop + DB query (~5–20ms) |
| Question | "Does this role claim permit attempting this operation type?" | "Can this user do X on this specific resource instance?" |
| Policy author | Security team (Rego) | Engineering (Zed schema + CI provisioning) |

#### Request Flow

```
Request → Istio Waypoint
               ↓
           OPA (ext_authz — claim-based only)
           ✓ JWT has required role claim (analyst | editor | team_admin)?
           ✓ Method/path combination is valid for this role?
           ✓ No active emergency security override?
           → deny → 403 immediately (SpiceDB never queried)
               ↓ allow
           ZIO Application
           ✓ Extract UserContext from `x-user-id` header (mesh-injected JWT `sub` — no JWT parsing in app)
           ✓ authorizationService.check(user, "design_write", resource)
           → SpiceDB evaluates relationship graph
           → deny → 403 Forbidden
               ↓ allow
           Handle request
```

Both layers use AND logic: OPA allow **and** SpiceDB allow = proceed. Either deny = 403.

#### OPA's Role: Coarse Gate + Security Team Override Layer

OPA policies are **purely claim-based** — they read only the JWT and HTTP context. They never read relationship data (no bundle sync from the workspace store or SpiceDB). The moment relationship data is needed, that is SpiceDB's responsibility.

OPA is also the **defense-in-depth layer** for the security team: policy changes can be pushed without an application deployment. Appropriate OPA-only operations:
- Emergency write block: `deny all POST/PUT/DELETE for a specific sub claim during incident`
- Compliance mandate: `require X-Audit-Reason header on all DELETE operations`
- Coarse role gate: `deny if JWT has no analyst or editor role claim at all` (fast path before SpiceDB)
- Temporal restriction: `deny design_write outside approved hours`

**Anti-pattern to avoid:** Do not sync relationship data (workspace members, tree assignments) into OPA data bundles. This creates staleness, sync complexity, and defeats SpiceDB's purpose. A revoked tuple would remain "allowed" by OPA until the next bundle push.

#### OPA Policies (`infra/opa/policies/`)

```rego
package register.authz

import future.keywords.if

# Coarse role gate — purely from JWT claims, no data bundle
default allow = false

# Allow if user has at least one recognized role
allow if {
    some role in input.jwt.realm_access.roles
    role in {"analyst", "editor", "team_admin"}
}

# Deny writes for users with viewer-only claim
deny if {
    input.request.method in {"POST", "PUT", "PATCH", "DELETE"}
    every role in input.jwt.realm_access.roles {
        role == "viewer"
    }
}
```

SpiceDB then handles the instance-level question: does this editor's JWT correspond to a user who actually has `design_write` on this specific `risk_tree`?

### Task L2.5: Tests

- Permission check: owner can edit, viewer cannot
- Inheritance: workspace member can access tree in workspace
- Per-tree override: tree-level viewer cannot edit
- Ops-path revocation: after tuple deletion via `zed` CLI or CI job, `check()` returns forbidden immediately — no app-level revocation endpoint involved

---

### Task L2.6: Route Protection Rollout

Incremental plan for wiring `AuthorizationService.check()` into every HTTP route that requires it. Each wave is independently deployable and leaves existing tests passing. Activation is controlled entirely by the `register.auth.mode` config value; no wave requires a code-flag branch.

#### Route Inventory and Permission Matrix

Complete route census as of the current codebase. Every route is assigned a `Permission` enum value (or explicitly declared as exempt) before any code changes begin.

| # | Method | Path | Controller | Permission | Resource type | Auth mode | Notes |
|---|--------|------|-----------|-----------|--------------|-----------|-------|
| 1 | GET | `/health` | RiskTree | **None** | — | All | Always public — no auth ever |
| 2 | GET | `/risk-trees` | RiskTree | **None** | — | Config-gate | Default disabled (A17); admin debug only; no SpiceDB check |
| 3 | POST | `/workspaces/bootstrap` | Workspace | **None (pre-resource)** | — | L0+ | Creates workspace; no prior resource to check; see Wave 5 for post-create grant |
| 4 | GET | `/w/{key}/risk-trees` | Workspace | `ViewWorkspace` | Workspace | L0+ | Lists trees — coarse read of workspace membership |
| 5 | POST | `/w/{key}/risk-trees` | Workspace | `DesignWrite` | Workspace | L0+ | Creates tree inside workspace |
| 6 | POST | `/w/{key}/rotate` | Workspace | `AdminWorkspace` | Workspace | L0+ | Key rotation — sensitive, admin-level |
| 7 | DELETE | `/w/{key}` | Workspace | `AdminWorkspace` | Workspace | L0+ | Hard-delete workspace + all trees |
| 8 | DELETE | `/admin/workspaces/expired` | Workspace | `AdminSystem` | System | L0+ | Server-wide eviction — system admin only |
| 9 | GET | `/w/{key}/risk-trees/{treeId}` | Workspace | `ViewTree` | RiskTree | L0+ | Tree summary |
| 10 | GET | `/w/{key}/risk-trees/{treeId}/structure` | Workspace | `ViewTree` | RiskTree | L0+ | Full tree structure |
| 11 | PUT | `/w/{key}/risk-trees/{treeId}` | Workspace | `DesignWrite` | RiskTree | L0+ | Full tree replacement |
| 12 | DELETE | `/w/{key}/risk-trees/{treeId}` | Workspace | `DesignWrite` | RiskTree | L0+ | Tree deletion |
| 13 | POST | `/w/{key}/risk-trees/{treeId}/invalidate/{nodeId}` | Workspace | `AdminTree` | RiskTree | L0+ | Cache invalidation — operational action on tree |
| 14 | GET | `/w/{key}/risk-trees/{treeId}/nodes/{nodeId}/lec` | Workspace | `ViewTree` | RiskTree | L0+ | LEC curve for one node |
| 15 | GET | `/w/{key}/risk-trees/{treeId}/nodes/{nodeId}/prob-of-exceedance` | Workspace | `ViewTree` | RiskTree | L0+ | Probability of exceedance query |
| 16 | POST | `/w/{key}/risk-trees/{treeId}/nodes/lec-multi` | Workspace | `ViewTree` | RiskTree | L0+ | Multi-node LEC batch |
| 17 | POST | `/w/{key}/risk-trees/{treeId}/lec-chart` | Workspace | `ViewTree` | RiskTree | L0+ | Vega-Lite chart spec |
| 18 | GET | `/w/{key}/events/tree/{treeId}` | SSE | `ViewTree` | RiskTree | L0+ | SSE stream — same gate as the REST read |
| 19 | GET | `/risk-trees/{treeId}/cache/stats` | Cache | **Mesh-only** | — | Mesh | Istio/OPA `admin` role; no app-level check |
| 20 | GET | `/risk-trees/{treeId}/cache/nodes` | Cache | **Mesh-only** | — | Mesh | Istio/OPA `admin` role; no app-level check |
| 21 | DELETE | `/risk-trees/{treeId}/cache` | Cache | **Mesh-only** | — | Mesh | Istio/OPA `admin` role; no app-level check |
| 22 | DELETE | `/cache/clear-all` | Cache | **Mesh-only** | — | Mesh | Istio/OPA `admin` role; no app-level check |

**Cache endpoints (19–22) remain mesh-only.** They are not under `/w/{key}/...` — no workspace key in the path — so `AuthorizationService.check()` has no workspace-scoped context. Istio `AuthorizationPolicy` with `roles[admin]` OPA claim is the correct enforcement point (already documented in `CacheEndpoints.scala`). This is consistent with ADR-024: the app does not implement admin-role logic.

---

#### Prerequisite: `WorkspaceId` must exist before Wave 0

`ws.asResource` requires a stable, non-secret identifier for the workspace (see Task L2.2 design questions). `WorkspaceId` does not yet exist in the codebase. Before any wave can write or check SpiceDB workspace relations, the following changes must land as a separate atomic commit:

1. Add `WorkspaceId` to `OpaqueTypes.scala` — mirrors `TreeId` exactly:
   ```scala
   // OpaqueTypes.scala — in com.risquanter.register.domain.data.iron
   final case class WorkspaceId(value: SafeId.SafeId)
   object WorkspaceId:
     def generate(): WorkspaceId = WorkspaceId(SafeId.generate())
   ```
2. Add `id: WorkspaceId` field to `Workspace` case class (generated on bootstrap, persisted alongside `key`).
3. Add `.asResource` extension method on `WorkspaceId` (mirrors `TreeId.asResource`).
4. Update `WorkspaceStore` to store and retrieve `WorkspaceId`.
5. Update `IrminClient` persistence for `WorkspaceId`.

This is a **Layer 1 task**, not Layer 2 — blocking the entire rollout. No fine-grained check on a workspace resource is possible without it.

---

#### Tapir Integration Pattern

Authorization is embedded directly in `serverLogic`, not in a global interceptor. This is explicit, testable, and does not require Tapir interceptor machinery.

**Step 1 — New endpoint base for authenticated routes:**

```scala
// In BaseEndpoint (or a new AuthedEndpoint mixin)
// Option[UserId] — mesh-injected claim header:
//   - None:               x-user-id header absent → allowed (capability-only passes through NoOp)
//   - Some(userId):       header present, UUID format validated at Tapir codec boundary → typed identity
//   - DecodeResult.Error: header present but not a valid UUID → 400 before serverLogic, never reaches controller
// The mesh guarantees this header is either absent (unauthenticated) or contains the Keycloak sub claim.
// Mode enforcement (is a user identity *required*?) happens in UserContextExtractor.extract, not here.
val authedBaseEndpoint =
  baseEndpoint
    .in(header[Option[UserId]]("x-user-id"))
```

All workspace-scoped endpoints switch from `baseEndpoint` to `authedBaseEndpoint`. The path and query parameters are unchanged. The auth header becomes the first input tuple element.

**Step 2 — Updated endpoint signature (example):**

```scala
// Before:
val listWorkspaceTreesEndpoint =
  baseEndpoint
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees")
    .get
    .out(jsonBody[List[SimulationResponse]])
// Input tuple: WorkspaceKeySecret

// After:
val listWorkspaceTreesEndpoint =
  authedBaseEndpoint
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees")
    .get
    .out(jsonBody[List[SimulationResponse]])
// Input tuple: (Option[UserId], WorkspaceKeySecret)
```

**Step 3 — Controller serverLogic pattern:**

```scala
// check() is the FIRST effectful call after extracting the userId.
// resolveTree() (Layer 0 capability check) runs before check() for tree-scoped routes
// to avoid leaking tree existence to unauthenticated callers.
val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic {
  case (maybeUserId, key) =>    // maybeUserId: Option[UserId] — mesh-injected, UUID-validated at Tapir boundary
    (for
      ws     <- workspaceStore.resolve(key)                                     // Layer 0: capability
      userId <- userCtx.extract(maybeUserId)                                    // Layer 1: identity (NoOp or require-present)
      _      <- authz.check(userId, Permission.ViewWorkspace, ws.id.asResource) // Layer 2: SpiceDB (NoOp or live)
      trees  <- workspaceStore.listTrees(key).map(_.map(SimulationResponse.fromRiskTree))
    yield trees).either
}

// Tree-scoped route: resolveTree ensures tree belongs to this workspace,
// then check() verifies fine-grained permission on the tree itself.
val getTreeById: ServerEndpoint[Any, Task] = getWorkspaceTreeByIdEndpoint.serverLogic {
  case (maybeUserId, key, treeId) =>    // maybeUserId: Option[UserId] — mesh-injected, UUID-validated at Tapir boundary
    (for
      ws     <- workspaceStore.resolve(key)
      _      <- resolveTree(key, treeId)                                    // Layer 0: workspace capability + tree ownership
      userId <- userCtx.extract(maybeUserId)                                // Layer 1: identity (NoOp or require-present)
      _      <- authz.check(userId, Permission.ViewTree, treeId.asResource) // Layer 2: SpiceDB on tree
      result <- riskTreeService.getById(treeId).map(_.map(SimulationResponse.fromRiskTree))
    yield result).either
}
```

**Order invariant:** `ws.resolve(key)` → `resolveTree(key, treeId)` → `userCtx.extract` → `authz.check` → handler body. The Layer 0 check always precedes Layer 2 — this ensures that workspace-key errors return the same opaque 404 regardless of auth mode (no information about valid workspace IDs is revealed to unauthenticated callers).

---

#### `UserContextExtractor` Design

```scala
trait UserContextExtractor:
  /** Extract UserId from the mesh-injected `x-user-id` claim header.
    *
    * The parameter type is Option[UserId] — Tapir codec has already validated the UUID format:
    * - None:         `x-user-id` header absent → NoOp passes through; `requirePresent` fails closed
    * - Some(userId): header present, UUID-validated at Tapir boundary — value is trusted
    *
    * The mesh guarantees that if `x-user-id` is present, it is the `sub` claim from a Keycloak-validated
    * JWT. The app contains zero JWT code — no base64 decode, no claim extraction, no JWT library.
    * See [ADR-012: Claim Header Injection](./ADR-012.md#6-claim-header-injection) for the required external header stripping configuration.
    *
    * capability-only: returns UserId.anonymous — header not required, check() is NoOp
    * identity:        header required; fail with AuthForbidden if absent
    * fine-grained:    same as identity; check() then uses real SpiceDB
    */
  def extract(maybeUserId: Option[UserId]): IO[AppError, UserId]

object UserContextExtractor:
  val anonymous: UserId = UserId.fromString("00000000-0000-0000-0000-000000000000")
    // sentinel value used only in NoOp/capability-only mode; never reaches SpiceDB

  // capability-only — x-user-id header value is ignored entirely
  val noOp: UserContextExtractor = _ => ZIO.succeed(anonymous)

  // identity / fine-grained — header must be present.
  // If absent: request did not pass through an authenticated waypoint, or user is unauthenticated.
  // Both cases fail closed. No JWT parsing needed; the mesh has already done all validation.
  val requirePresent: UserContextExtractor = {
    case None         => ZIO.fail(AuthForbidden("Missing x-user-id header — unauthenticated request or mesh bypass"))
    case Some(userId) => ZIO.succeed(userId)
  }
```

`UserContextExtractor` is provided as a ZLayer. The layer selection follows the same `register.auth.mode` switch as `AuthorizationService`.

---

#### Rollout Waves

Each wave is a single PR. The PR passes all existing tests before merge. No wave requires a feature flag branch — mode is config-only at runtime.

---

**Wave 0 — Infrastructure (no user-visible change)**

_Deliverables:_
- `WorkspaceId` added to domain model (prerequisite — see above)
- `UserId` final class (`OpaqueTypes.scala` or new `AuthTypes.scala`)
- `Permission` enum with `zedName: String`
- `ResourceType` enum with `zedType: String`
- `ResourceRef` case class; `.asResource` extension methods on `WorkspaceId` and `TreeId`
- `AuthError` sealed trait added to `AppError` hierarchy
- `AuthorizationService` trait (see Task L2.2)
- `AuthorizationServiceNoOp` (always allow — all modes initially)
- `AuthorizationServiceStub` (Set-backed — for tests)
- `UserContextExtractor` trait + `noOp` implementation
- `AuthConfig` case class: `mode: String` (validated: `capability-only | identity | fine-grained`)
- ZLayer wiring: `AuthorizationServiceNoOp` bound by default in all modes

_Regression gate:_ All existing `RouteSecurityRegressionSpec` and unit tests pass unchanged. No endpoint definitions modified yet.

---

**Wave 1 — Auth header declared on workspace-scoped endpoints**

_Deliverables:_
- `authedBaseEndpoint` added to `BaseEndpoint` (or new `AuthedEndpoint` mixin)
- All workspace-scoped endpoints in `WorkspaceEndpoints.scala` and `SSEEndpoints.scala` switch to `authedBaseEndpoint`
- All workspace-scoped `serverLogic` calls updated to destructure the new `(Option[UserId], ...)` tuple
- `UserContextExtractor.noOp` wired — `extract()` always returns anonymous userId, `x-user-id` header ignored
- `authz.check()` called in every workspace-scoped `serverLogic` — still hits `AuthorizationServiceNoOp`

_Behavior change:_ None. NoOp always allows. Anonymous userId never reaches SpiceDB.

_Regression gate:_
- `RouteSecurityRegressionSpec` still passes (path templates unchanged, only input tuple extended)
- New test: for every workspace-scoped route, `serverLogic` compiles and all tests pass with `NoOp` layers

_Diff scope:_ `WorkspaceEndpoints.scala`, `SSEEndpoints.scala`, `WorkspaceController.scala`, `SSEController.scala` — mechanical, no logic changes.

---

**Wave 2 — Identity mode: claim header required**

_Deliverables:_
- `UserContextExtractor.requirePresent` implemented (header presence check — no JWT parsing)
- `AuthConfig.mode = "identity"` activates `requirePresent` in ZLayer selection:
  ```scala
  val userCtxLayer: ULayer[UserContextExtractor] =
    ZLayer.fromZIO(ZIO.config(AuthConfig.descriptor).map {
      case AuthConfig("capability-only", _) => UserContextExtractor.noOp
      case _                                => UserContextExtractor.requirePresent
    })
  ```
- No `authz.check()` behaviour changes — still NoOp

_Behavior change:_ In `identity` mode only, missing `x-user-id` header → `AuthForbidden` → 403. In `capability-only` mode, unchanged.

_New tests:_
  - Tapir codec (unit): valid UUID string in `x-user-id` header → `Some(UserId)`; malformed UUID → 400 (`DecodeResult.Error`) before `serverLogic` reached
  - `UserContextExtractor.requirePresent`: `Some(validUserId)` → `Right(userId)`; `None` → `AuthForbidden`
  - Missing `x-user-id` header → `AuthForbidden` (from `requirePresent` — not from Tapir: absent optional header is `None`, not an error)
  - Valid `x-user-id` header → userId extracted with zero JWT code involved

---

**Wave 3 — Fine-grained mode: read route protection**

_Deliverables:_
- `AuthorizationServiceSpiceDB` implemented (see Task L2.2)
- `AuthConfig.mode = "fine-grained"` activates `AuthorizationServiceSpiceDB` in ZLayer selection
- `check()` calls in read routes now hit SpiceDB when in `fine-grained` mode:
  - Route 4: `check(userId, Permission.ViewWorkspace, ws.id.asResource)`
  - Routes 9, 10, 14, 15, 16, 17, 18: `check(userId, Permission.ViewTree, treeId.asResource)`

_Behavior change:_ In `fine-grained` mode, users without a SpiceDB `view_workspace` or `view_tree` grant receive 403. In `capability-only` and `identity` modes, still NoOp.

_New tests (using `AuthorizationServiceStub`):_
  - `listWorkspaceTrees`: stub with `ViewWorkspace` grant → 200; stub without → 403
  - `getTreeById`: stub with `ViewTree` grant → 200; stub without → 403
  - Inheritance: workspace-level `ViewTree` grant covers tree-level read (SpiceDB schema inheritance)
  - All read routes: property test — every route with `ViewTree` in permission matrix returns 403 when stub grants are empty

---

**Wave 4 — Fine-grained mode: write route protection**

_Deliverables:_
- `check()` calls added to write routes:
  - Route 5: `check(userId, Permission.DesignWrite, ws.id.asResource)`
  - Routes 11, 12: `check(userId, Permission.DesignWrite, treeId.asResource)`
  - Route 13: `check(userId, Permission.AdminTree, treeId.asResource)`

_Behavior change:_ In `fine-grained` mode, users without `design_write` grant cannot create/update/delete trees.

_New tests:_
  - `createWorkspaceTree`: stub with `DesignWrite` on workspace → 200; without → 403
  - `updateTree`, `deleteTree`: stub with `DesignWrite` on tree → 200; without → 403
  - `invalidateCache`: stub with `AdminTree` → 200; without → 403

---

**Wave 5 — Fine-grained mode: admin route protection**

_Deliverables:_
- `check()` calls added to admin routes:
  - Route 6: `check(userId, Permission.AdminWorkspace, ws.id.asResource)`
  - Route 7: `check(userId, Permission.AdminWorkspace, ws.id.asResource)`
  - Route 8: `check(userId, Permission.AdminSystem, ResourceRef.system)`
- `ResourceRef.system` sentinel added for system-level checks (no specific resource ID):
  ```scala
  object ResourceRef:
    val system: ResourceRef = ResourceRef(ResourceType.Organization, SafeId("00000000000000000000000000"))
    // SpiceDB will evaluate against the org-level admin_system permission in the schema
  ```

_New tests:_
  - `rotateWorkspace`: `AdminWorkspace` grant → 200; without → 403
  - `deleteWorkspace`: `AdminWorkspace` grant → 200; without → 403
  - `evictExpired`: `AdminSystem` grant → 200; without → 403

---

**Wave 6 — Bootstrap seeding: post-create ownership write (fine-grained mode)**

`POST /workspaces/bootstrap` has no resource to check before the workspace is created (no prior `WorkspaceId` to look up). This is the only route that writes to SpiceDB instead of reading. It is also the only justified exception to ADR-024's "no grant/revoke in app" principle — this is initial provisioning, not an admin delegation action.

_Flow in `fine-grained` mode:_

```scala
val bootstrapWorkspace: ServerEndpoint[Any, Task] = bootstrapWorkspaceEndpoint.serverLogic {
  case (maybeForwardedFor, maybeUserId, req) =>          // maybeUserId: Option[UserId] — mesh-injected
    (for
      userId  <- userCtx.extract(maybeUserId)            // Layer 1: identity required in fine-grained
      tree    <- riskTreeService.create(req)
      ws      <- workspaceStore.bootstrap(tree.id, ...)
      _       <- authz.seed(userId, ws.id)             // writes SpiceDB tuple: workspace:ID#owner_user@user:UID
    yield WorkspaceBootstrapResponse(ws.key, tree)).either
}
```

`AuthorizationService.seed(userId, workspaceId)` is a new method, **separate from `check()`**, which writes a single SpiceDB relationship tuple:
```
workspace:{workspaceId}#owner_user@user:{userId}
```

In `capability-only` and `identity` modes, `seed()` is a no-op (the NoOp implementation does nothing).

_Decision record (inline, no new ADR):_ This is the minimal exception to ADR-024. The app writes exactly one tuple, at exactly one point in time, scoped to workspace creation. No tuple writes occur anywhere else in the app. If the `seed()` call fails, workspace creation is rolled back (the ZIO for-comprehension ensures atomicity at the service level). The alternative — an async provisioning sidecar — is a valid future migration target but adds infrastructure complexity disproportionate to a single tuple write.

_New tests:_
  - `bootstrapWorkspace` in fine-grained mode: stub records one `seed()` call with correct `(userId, workspaceId)`
  - `seed()` failure → workspace creation returns error (no orphaned workspace)
  - `bootstrapWorkspace` in capability-only mode: stub records zero `seed()` calls

---

#### ZLayer Selection — Mode-Driven Service Wiring

All mode selection is centralised in one ZLayer definition. Individual controllers receive `AuthorizationService` and `UserContextExtractor` via normal ZIO dependency injection — they do not inspect the config themselves.

```scala
// In AppLayer (modules/app/.../AppLayer.scala or similar):

val authModeLayer: TaskLayer[AuthorizationService & UserContextExtractor] =
  ZLayer.fromZIO(
    ZIO.config(AuthConfig.descriptor).flatMap {
      case AuthConfig("fine-grained", spicedb) =>
        ZIO.succeed(
          AuthorizationServiceSpiceDB.layer(spicedb) ++
          UserContextExtractor.jwtLayer
        )
      case AuthConfig("identity", _) =>
        ZIO.succeed(
          ZLayer.succeed(AuthorizationServiceNoOp) ++
          UserContextExtractor.jwtLayer
        )
      case _ => // capability-only
        ZIO.succeed(
          ZLayer.succeed(AuthorizationServiceNoOp) ++
          ZLayer.succeed(UserContextExtractor.noOp)
        )
    }
  ).flatten
```

**No controller contains a `config.auth.mode match` expression.** Mode switching is purely an infrastructure concern — this enforces ADR-024's "app as pure PEP" posture.

---

#### Regression Test Invariants

Extend `RouteSecurityRegressionSpec` with the following invariants (verified with `AuthorizationServiceStub`):

```scala
// Invariant 1: No workspace-scoped route returns 200 when stub grants are empty (fine-grained mode)
// (Health and /risk-trees are exempt — they are not workspace-scoped)
test("all workspace-scoped routes are 403 when stub has no grants") { ... }

// Invariant 2: Every workspace-scoped route returns 200 when stub grants match expected permission
// Verifies that the correct Permission enum value is used at each call site
test("stub with correct grant passes all workspace-scoped routes") { ... }

// Invariant 3: No workspace-scoped route short-circuits past check() — AuthorizationServiceStub
// records every check() call; test verifies call count == 1 per request
test("authz.check() is called exactly once per protected request") { ... }

// Invariant 4: Cache endpoints are NOT tested for SpiceDB permission — they are mesh-only
// Document this explicitly so future contributors don't add spurious check() calls
```

---

#### Summary Table: What Changes Per Wave

| Wave | Changed files | Behavior change? | Test additions |
|------|--------------|-----------------|----------------|
| 0 | New files: AuthTypes, AuthorizationService, UserContextExtractor | None | Unit tests for new types |
| 1 | WorkspaceEndpoints, SSEEndpoints, WorkspaceController, SSEController | None (NoOp) | Compilation + regression |
| 2 | AppLayer (ZLayer wiring) + UserContextExtractor.requirePresent | Identity mode: x-user-id header required | Header presence tests |
| 3 | AppLayer (SpiceDB wiring) | Fine-grained: read routes enforced | Stub-based 200/403 read tests |
| 4 | WorkspaceController (write routes) | Fine-grained: write routes enforced | Stub-based 200/403 write tests |
| 5 | WorkspaceController (admin routes) | Fine-grained: admin routes enforced | Stub-based 200/403 admin tests |
| 6 | WorkspaceController (bootstrap) + AuthorizationService (seed) | Fine-grained: bootstrap writes ownership tuple | seed() call count tests |

### Estimated Effort: ~2–4 weeks

---

## Interaction Between Layers

### How Layers Compose at Runtime

```scala
// Pseudocode — actual middleware/interceptor pattern
def authorize(request: Request): IO[AuthError, AuthContext] =
  // Layer 0 ALWAYS runs — workspace key in URL is required in ALL modes
  val layer0: IO[AuthError, Workspace] =
    extractWorkspaceKey(request.path)
      .flatMap(workspaceStore.resolve)

  config.auth.mode match
    case "capability-only" =>
      // Layer 0 only: key is sole credential (true capability)
      layer0.map(ws => AuthContext.Capability(ws))

    case "identity" =>
      // Layer 0+1: key is invitation token — key + JWT from right realm = access
      // Pattern: "anyone with the link who is signed in"
      // No ownership check here — having key + valid session is sufficient
      for
        ws   <- layer0                                    // must have valid workspace key
        user <- extractUserContext(request.headers)       // must also have valid JWT
        _    <- workspaceOwnership.recordIfUnclaimed(user, ws)  // claim on first access (side-effect, not a gate)
      yield AuthContext.Identity(user, ws)

    case "fine-grained" =>
      // Layer 0+1+2: key is routing token — access determined by explicit SpiceDB relationship
      // Pattern: explicit ACL — user must be granted access by owner
      for
        ws   <- layer0                                    // must have valid workspace key
        user <- extractUserContext(request.headers)       // must have valid JWT
        _    <- authorizationService.check(user.userId, Permission.ViewWorkspace, ws.asResource)  // Permission is a sealed enum — see Task L2.2
      yield AuthContext.FineGrained(user, ws)
```

### Config-Driven Feature Gating

Beyond authorization, the deployment mode also gates features:

```hocon
register.features {
  scenario-branching = false   # Tier 3 feature — tier placement under review (see scenario analysis planning)
  collaboration = false        # Tier 3 feature — enterprise only
  websocket = false            # Tier 4 feature — enterprise only
}
```

Free-tier shows a sneak-peak: tree building, LEC visualization, workspace grouping. Enterprise unlocks collaboration, scenarios, and fine-grained access control (governed externally via ops path).

---

## Migration Path

### From Layer 0 → Layer 0+1

When upgrading a free-tier deployment to enterprise:

1. Deploy Keycloak + configure realm
2. Set `register.auth.mode = "identity"`
3. All existing workspace key URLs remain valid — same URL scheme
4. Workspace key alone is now **insufficient** — users must also authenticate via Keycloak
5. On first authenticated access to a workspace URL, the workspace is "claimed" — `owner_id` set, TTL removed
6. Leaked workspace URLs from the free-tier era become inaccessible without a valid session

### From Layer 1 → Layer 2

1. Complete Phase L2.0 SpiceDB foundation setup
2. Deploy selected authorization backend with required persistence
3. Migrate ownership data: `workspace.owner_id → authorization relation`
  - SpiceDB example: `workspace:ID#owner_user@user:ID` (matches `owner_user` relation in schema)
3. Set `register.auth.mode = "fine-grained"`
4. Existing owner-based access continues via backend `owner` relation
5. Fine-grained access control becomes active — access relationships are now governed by SpiceDB, managed via ops path (CI job + `zed` CLI)

---

## Open Questions

~~1. **OPA as intermediate:** Should OPA (already in mesh) be Layer 1.5 before Zanzibar-style backend?~~ **Closed:** OPA is a permanent AND-logic co-gate at the mesh layer — not a sequential stepping stone. Both OPA and SpiceDB are always active in fine-grained mode; neither replaces the other. See Task L2.4 and [ADR-012: OPA for Authorization](./ADR-012.md#3-opa-for-authorization).
2. **Anonymous workspace claiming:** When a free-tier user upgrades, how do they prove they "own" a capability-only workspace? Options: (a) login while URL has workspace key, (b) email verification, (c) just create new.
3. **Feature gating UI:** How does the frontend know which features are available? `/config` endpoint? HTML-embedded config?
4. **Terraform adoption trigger:** At what point do we need provider-level IaC (managed cluster/network/DNS/secrets), beyond Helm + manifests?
~~5. **SpiceDB integration details:**~~ **Closed:** HTTP client path for initial implementation (sttp, same as IrminClient). gRPC migration = swap `AuthorizationServiceSpiceDB` implementation only — trait is the stable interface (see Task L2.2 Adapter Boundary). Ownership migration: one-time CI job writes existing `owner_id` values as SpiceDB `owner_user` relation tuples during L0→L2 upgrade — app never writes tuples in steady state. `WorkspaceId` generated from Layer 1 onward to avoid this migration needing a data backfill.
6. ~~**Workspace override governance (closed):**~~ Resolved: user-owned workspaces grant override only to the `owner_user` SpiceDB relation and the M3 break-glass ops path. No in-product override delegation. Policy recorded in ops runbook, not app code.

---

## References

- [ADR-012: Service Mesh Strategy](./ADR-012.md) — Istio + Keycloak + OPA architecture
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — Layer 0 capability model
- [ADR-022: Secret Handling](./ADR-022-secret-handling.md) — WorkspaceKeySecret credential hardening
- [ADR-023: Local Development TLS and Trust Material Policy](./ADR-023-local-dev-tls-and-trust-material-policy.md) — TLS-by-default local posture, trust material handling
- [ADR-024: Externalized Authorization — Application as Pure PEP](./ADR-024-externalized-authorization-pep-pattern.md) — no grant/revoke in app; PAP is ops tooling
- [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) — Tier 1.5 (Layer 0 implementation)
- Cheleb reference architecture — ZIO + PostgreSQL layer patterns (review before Tier 1.5 Phase W.2, **not** relevant for Keycloak/OAuth2)
- SpiceDB: https://authzed.com/spicedb
- OpenFGA: https://openfga.dev/
- OPA + Istio: https://www.openpolicyagent.org/docs/latest/envoy-introduction/
- W3C TAG Capability URLs: https://www.w3.org/TR/capability-urls/

---

*Document created: February 13, 2026; updated February 19, 2026*
*Covers: Authorization Layers 1 (Identity + Ownership) and 2 (Fine-Grained RBAC)*
*Layer 0 (Workspace Capability) is in IMPLEMENTATION-PLAN.md, Tier 1.5*
