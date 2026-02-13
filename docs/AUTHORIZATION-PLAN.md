# Authorization Plan — Layers 1 & 2

**Date:** February 13, 2026
**Status:** Planning (not yet scheduled for implementation)
**Related:** [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) (Tier 1.5 = Layer 0)
**ADR References:** [ADR-012](./ADR-012.md) (Service Mesh), [ADR-021](./ADR-021-capability-urls.md) (Capability URLs)

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

**URL scheme:**
- URL remains `/#/{workspaceKey}/...` (same as free-tier)
- No URL change — enterprise uses identical workspace key URLs
- Login button and user menu added to UI; workspace URLs unchanged

**Note:** Cheleb reference architecture does **not** contain OAuth2/OIDC patterns. For OIDC authorization code flow, consult Keycloak JS adapter documentation and [OAUTH2-FLOW-ARCHITECTURE.md](./OAUTH2-FLOW-ARCHITECTURE.md).

### Task L1.4: My Workspaces API

**Endpoints (identity-authenticated):**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/my/workspaces` | List workspaces owned by authenticated user |
| POST | `/my/workspaces` | Create workspace (owned by user, no TTL) |
| DELETE | `/my/workspaces/{id}` | Delete workspace |

These endpoints require JWT — scoped to the authenticated user. Enterprise workspaces have no TTL (owned, not ephemeral).

### Task L1.5: Istio Configuration

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
- SpiceDB or OpenFGA instance available

### Technology Decision: SpiceDB vs OpenFGA

| Criterion | SpiceDB | OpenFGA |
|-----------|---------|---------|
| **Model** | Google Zanzibar | Google Zanzibar (simplified) |
| **Maintainer** | AuthZed | Okta/Auth0 |
| **Protocol** | gRPC + HTTP | HTTP + gRPC |
| **CNCF** | Not CNCF | CNCF Sandbox |
| **Backing store** | PostgreSQL, CockroachDB, Spanner | PostgreSQL, MySQL |
| **Kubernetes** | Official Helm chart | Official Helm chart |
| **ZIO integration** | gRPC client via scalapb | HTTP client via sttp |
| **Schema language** | `.zed` (rich, typed) | JSON model (simpler) |
| **Playground** | play.authzed.com | playground.fga.dev |
| **Ecosystem fit** | Standalone, works with any IdP | Tighter Auth0 integration |

**Recommendation:** SpiceDB — richer schema language, better standalone story (not tied to Auth0 ecosystem), PostgreSQL backing (same as workspace store). But evaluate both playgrounds before committing.

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
  scenario-branching = false   # Tier 3 feature — enterprise only
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

1. Deploy SpiceDB with PostgreSQL
2. Migrate ownership data: `workspace.owner_id → SpiceDB relation`
3. Set `register.auth.mode = "fine-grained"`
4. Existing owner-based access continues via SpiceDB `owner` relation
5. New sharing features become available

---

## Open Questions

1. **SpiceDB vs OpenFGA:** Evaluate both playgrounds before committing. Decision point: before Layer 2 implementation starts.
2. **OPA as intermediate:** Should OPA (already in mesh) be Layer 1.5 before SpiceDB? Simpler but less expressive.
3. **Anonymous workspace claiming:** When a free-tier user upgrades, how do they prove they "own" a capability-only workspace? Options: (a) login while URL has workspace key, (b) email verification, (c) just create new.
4. **Feature gating UI:** How does the frontend know which features are available? `/config` endpoint? HTML-embedded config?

---

## References

- [ADR-012: Service Mesh Strategy](./ADR-012.md) — Istio + Keycloak + OPA architecture
- [ADR-021: Capability URLs](./ADR-021-capability-urls.md) — Original capability model (being amended)
- [OAUTH2-FLOW-ARCHITECTURE.md](./OAUTH2-FLOW-ARCHITECTURE.md) — OAuth2/OIDC flow diagrams
- [IMPLEMENTATION-PLAN.md](./IMPLEMENTATION-PLAN.md) — Tier 1.5 (Layer 0 implementation)
- Cheleb reference architecture — ZIO + PostgreSQL layer patterns (review before Tier 1.5 Phase W.2, **not** relevant for Keycloak/OAuth2)
- SpiceDB: https://authzed.com/spicedb
- OpenFGA: https://openfga.dev/
- OPA + Istio: https://www.openpolicyagent.org/docs/latest/envoy-introduction/
- W3C TAG Capability URLs: https://www.w3.org/TR/capability-urls/

---

*Document created: February 13, 2026*
*Covers: Authorization Layers 1 (Identity + Ownership) and 2 (Fine-Grained RBAC)*
*Layer 0 (Workspace Capability) is in IMPLEMENTATION-PLAN.md, Tier 1.5*
