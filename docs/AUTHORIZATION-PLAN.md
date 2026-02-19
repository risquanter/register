# Authorization Plan — Layers 1 & 2

**Date:** February 19, 2026
**Status:** Planning (not yet scheduled for implementation)
**Related:** [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) (Tier 1.5 = Layer 0)
**ADR References:** [ADR-012](./ADR-012.md) (Service Mesh), [ADR-021](./ADR-021-capability-urls.md) (Capability URLs), [ADR-023](./ADR-023-local-dev-tls-and-trust-material-policy.md) (Local Dev TLS & Trust Policy)

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
  │  Pattern: explicit ACL — user must be granted access by owner
  │  Per-tree roles: editor, viewer, admin
  │  Workspace sharing: invite user Y as viewer of workspace W
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
- Verify JWT validation path and claim forwarding (`x-jwt-claims`)
- Keep app-side JWT parsing only (no duplicate signature validation)

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
│  JWT in  │     │  JWT      │     │ x-jwt-claims  │     │ UserContext  │
│  cookie  │     │  issued   │     │ header added  │     │ extracted    │
└──────────┘     └───────────┘     └──────────────┘     └──────────────┘
```

### Task L1.1: UserContext Extraction

**Files:**
```
server/.../auth/UserContext.scala
server/.../auth/UserContextExtractor.scala
```

**UserContext:**
```scala
final case class UserContext(
  userId: UserId,          // from JWT `sub` claim
  email: Option[Email],    // from JWT `email` claim
  roles: Set[String]       // from JWT `realm_access.roles`
)
```

**Extraction pattern (from ADR-012 §5):**
- Mesh validates JWT signature, expiry, issuer
- App extracts claims from `x-jwt-claims` header (Istio-injected)
- No manual JWT validation in app code

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
    forwardOriginalToken: true
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
- Implement `AuthorizationServiceSpiceDB` with `check/grant/revoke/listAccessible`
- Implement `AuthzProvisioning` job in CI/CD (idempotent reconcile, drift detection, audit logs)
- Ensure app runtime is read-only toward provisioning concerns (check/grant/revoke by authorized API paths only; no startup seeding)
- Verify latency budget for `check` on representative workspace/tree paths
- Record operational runbook (backup, upgrade, health checks)

### Task L2.1: Authorization Schema

**SpiceDB schema (`.zed`):**
```
definition user {}

definition workspace {
    relation owner: user
    relation member: user
    relation viewer: user

    permission admin = owner
    permission edit = owner + member
    permission view = owner + member + viewer
}

definition risk_tree {
    relation workspace: workspace
    relation editor: user
    relation viewer: user

    permission edit = editor + workspace->edit
    permission view = viewer + editor + workspace->view
}
```

This gives:
- Workspace-level roles (owner, member, viewer)
- Per-tree role overrides (editor, viewer)
- Inheritance: workspace membership flows to tree access unless overridden

### Task L2.2: Authorization Service

**Files:**
```
server/.../auth/AuthorizationService.scala
server/.../auth/AuthorizationServiceSpiceDB.scala
```

**Trait:**
```scala
trait AuthorizationService:
  def check(user: UserId, permission: String, resource: ResourceRef): IO[AuthError, Boolean]
  def grant(user: UserId, relation: String, resource: ResourceRef): IO[AuthError, Unit]
  def revoke(user: UserId, relation: String, resource: ResourceRef): IO[AuthError, Unit]
  def listAccessible(user: UserId, resourceType: String, permission: String): IO[AuthError, List[String]]
```

**ResourceRef:**
```scala
case class ResourceRef(resourceType: String, resourceId: String)
// e.g., ResourceRef("workspace", "01HXY..."), ResourceRef("risk_tree", "01HAB...")
```

### Task L2.3: Sharing UI

- Invite user to workspace (by email)
- Set per-tree permissions (editor/viewer)
- Revoke access
- Show who has access to workspace/tree

### Task L2.4: OPA Integration (Alternative Path)

If SpiceDB is deemed too complex for initial deployment, OPA can serve as an intermediate step:

**OPA Rego policy:**
```rego
package register.authz

default allow = false

allow {
    input.method == "GET"
    input.path[0] == "w"
    workspace_key := input.path[1]
    data.workspaces[workspace_key].owner == input.user
}

allow {
    input.method == "GET"
    input.path[0] == "w"
    workspace_key := input.path[1]
    input.user in data.workspaces[workspace_key].members
}
```

**OPA data bundle** (synced from workspace store):
```json
{
  "workspaces": {
    "abc123...": {
      "owner": "user-uuid-1",
      "members": ["user-uuid-2", "user-uuid-3"]
    }
  }
}
```

**Trade-off:** OPA is already in the mesh stack (ADR-012) — no new service to operate. But OPA policies are less expressive than Zanzibar for relationship-based access (no transitive inheritance, manual data sync required).

### Task L2.5: Tests

- Permission check: owner can edit, viewer cannot
- Inheritance: workspace member can access tree in workspace
- Per-tree override: tree-level viewer cannot edit
- Revocation: removed member loses access immediately

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
        _    <- authorizationService.check(user.userId, "view", ws.asResource)  // must have explicit permission
      yield AuthContext.FineGrained(user, ws)
```

### Config-Driven Feature Gating

Beyond authorization, the deployment mode also gates features:

```hocon
register.features {
  scenario-branching = false   # Tier 3 feature — tier placement under review (see scenario analysis planning)
  collaboration = false        # Tier 3 feature — enterprise only
  websocket = false            # Tier 4 feature — enterprise only
  workspace-sharing = false    # Layer 2 feature
}
```

Free-tier shows a sneak-peak: tree building, LEC visualization, workspace grouping. Enterprise unlocks collaboration, scenarios, fine-grained sharing.

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
  - SpiceDB example: `workspace:ID#owner@user:ID`
3. Set `register.auth.mode = "fine-grained"`
4. Existing owner-based access continues via backend `owner` relation
5. New sharing features become available

---

## Open Questions

1. **OPA as intermediate:** Should OPA (already in mesh) be Layer 1.5 before Zanzibar-style backend? Simpler but less expressive.
2. **Anonymous workspace claiming:** When a free-tier user upgrades, how do they prove they "own" a capability-only workspace? Options: (a) login while URL has workspace key, (b) email verification, (c) just create new.
3. **Feature gating UI:** How does the frontend know which features are available? `/config` endpoint? HTML-embedded config?
4. **Terraform adoption trigger:** At what point do we need provider-level IaC (managed cluster/network/DNS/secrets), beyond Helm + manifests?
5. **SpiceDB integration details:** HTTP vs gRPC client path for initial implementation; tuple write-through strategy vs batched sync for ownership migration.
6. **Workspace override governance:** For direct user-owned workspaces, confirm final override policy (org-admin only vs configurable delegation).

---

## References

- [ADR-012: Service Mesh Strategy](./ADR-012.md) — Istio + Keycloak + OPA architecture
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — Layer 0 capability model
- [ADR-022: Secret Handling](./ADR-022-secret-handling.md) — WorkspaceKeySecret credential hardening
- [ADR-023: Local Development TLS and Trust Material Policy](./ADR-023-local-dev-tls-and-trust-material-policy.md) — TLS-by-default local posture, trust material handling
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
