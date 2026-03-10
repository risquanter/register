# ADR-025: SPA Routing Strategy — Path-Based `/w/` Over Hash Routing

**Status:** Proposed  
**Date:** 2026-03-10  
**Tags:** frontend, routing, deployment, security, capability-urls

---

## Context

- The SPA needs URL-based workspace state to support bookmarks, sharing, and browser refresh
- Capability URLs (ADR-021) embed a workspace secret in the URL path — the URL **is** the authorization
- Hash fragments (`/#/…`) are invisible to servers, reverse proxies, and service mesh sidecars
- Path segments (`/w/…`) are visible to infrastructure, enabling per-route policies and access logging
- API endpoints already use `/w/{key}/…` paths — a second URL scheme for browser navigation creates ambiguity
- Path-based routing requires infrastructure support (SPA fallback) that hash routing avoids

## Decision

### 1. Use `/w/{key}` Path Routing (Not `/#/{key}` Hash Routing)

The browser URL uses the same `/w/` path prefix as API endpoints:

```scala
// URL parsing — location.pathname, not location.hash
private val workspacePathPattern = "^/w/([A-Za-z0-9_-]{22})(?:/.*)?$".r

private def extractKeyFromURL(): Option[WorkspaceKeySecret] =
  dom.window.location.pathname match
    case workspacePathPattern(key) => WorkspaceKeySecret.fromString(key).toOption
    case _                         => None
```

**Rationale:** Single URL scheme. Mesh policies, proxy rules, and access logs all
see the workspace path. No mismatch between what the user sees and what the API uses.

### 2. Use `history.replaceState` for URL Management (Not `location.hash`)

```scala
// Push key into URL without reload
private def pushKeyToURL(key: WorkspaceKeySecret): Unit =
  dom.window.history.replaceState(null, "", s"/w/${key.reveal}")

// Clear on expiry
private def clearURL(): Unit =
  dom.window.history.replaceState(null, "", "/")
```

`replaceState` avoids polluting the session history stack. The user doesn't
accumulate back-button entries from workspace creation. Future deep-linking can
switch to `pushState` for tree-level navigation.

### 3. Production Requires SPA-Aware Reverse Proxy

Path-based routing means `GET /w/{key}` browser requests hit the server. Without
a handler, users get a 404 on refresh or direct navigation.

**Nginx pattern (production / standalone):**

```nginx
location /w/ {
    # API calls (Fetch with Accept: application/json) → backend
    if ($http_accept ~* "application/json") {
        proxy_pass http://backend:8090;
        break;
    }
    # Browser navigation (Accept: text/html) → SPA shell
    try_files $uri /index.html;
}

location / {
    try_files $uri /index.html;
}
```

**Istio + mesh deployment:** Istio VirtualService routes `/w/*` browser GETs to the
static asset server (or a sidecar serving `index.html`). API calls are routed by
the sttp `Accept: application/json` header.

**Vite dev server (local development):**

```js
// vite.config.js — add appType or middleware for SPA fallback
export default defineConfig({
  appType: 'spa', // enables history API fallback
  // ...
})
```

> **Open question:** Should the Vite config change be applied now, or deferred
> until deep-linking / browser-refresh scenarios are prioritized? Currently, users
> always land at `/` and navigate via `replaceState`, so SPA fallback is not
> triggered in the dev workflow.

### 4. Reverse Proxy Request Discrimination

The reverse proxy distinguishes browser navigation from API calls using the
`Accept` header:

| Request Source | `Accept` Header | Action |
|---|---|---|
| Address bar / refresh / bookmark | `text/html, application/xhtml+xml, …` | Serve `index.html` |
| Fetch API / sttp backend | `application/json` | Proxy to backend |

Both browser navigation and API calls can hit `/w/*`. The proxy resolves the
ambiguity by inspecting the `Accept` header — browsers request `text/html` for
page loads; the SPA's Fetch calls (via sttp `FetchZioBackend`) request
`application/json` for API data.

#### Scenario 1: No key — user visits `/`

```
Browser                    Nginx                    Backend
  │                          │                         │
  │─ GET /  ─────────────────▶│                         │
  │  Accept: text/html       │                         │
  │                          │─ try_files / ──▶ index.html
  │◀── 200 index.html ───────│                         │
  │                          │                         │
  │ SPA boots                │                         │
  │ extractKeyFromURL() → None                         │
  │ (no API calls — form ready)                        │
```

The SPA loads with no workspace key. The tree builder form is displayed. No API
calls are made until the user submits their first tree.

#### Scenario 2: Valid key — user visits `/w/{key}`

```
Browser                    Nginx                    Backend
  │                          │                         │
  │─ GET /w/{key} ───────────▶│                         │
  │  Accept: text/html       │                         │
  │                          │─ rewrite → index.html   │
  │◀── 200 index.html ───────│                         │
  │                          │                         │
  │ SPA boots                │                         │
  │ extractKeyFromURL() → Some(key)                    │
  │ preValidate() fires:     │                         │
  │                          │                         │
  │─ GET /w/{key}/risk-trees ▶│                         │
  │  Accept: application/json│─ proxy_pass ───────────▶│
  │                          │                         │─ resolve(key) ✅
  │                          │◀── 200 [tree, ...] ─────│
  │◀── 200 [tree, ...] ──────│                         │
  │                          │                         │
  │ onTreesLoaded(trees)     │                         │
  │ → populate tree list     │                         │
```

The reverse proxy serves `index.html` for the browser navigation request. The SPA
extracts the key from the URL path, then `preValidate()` makes an API call
(with `Accept: application/json`) which the proxy forwards to the backend. This
is the "free data fetch" — pre-validation doubles as the initial tree list load.

#### Scenario 3: Expired/invalid key — user visits `/w/{key}`

```
Browser                    Nginx                    Backend
  │                          │                         │
  │─ GET /w/{key} ───────────▶│                         │
  │  Accept: text/html       │                         │
  │                          │─ rewrite → index.html   │
  │◀── 200 index.html ───────│                         │
  │                          │                         │
  │ SPA boots                │                         │
  │ extractKeyFromURL() → Some(key)                    │
  │ preValidate() fires:     │                         │
  │                          │                         │
  │─ GET /w/{key}/risk-trees ▶│                         │
  │  Accept: application/json│─ proxy_pass ───────────▶│
  │                          │                         │─ resolve(key) ✗
  │                          │◀── 404 "Not found" ─────│  (A13 opaque)
  │◀── 404 "Not found" ──────│                         │
  │                          │                         │
  │ tapError fires:          │                         │
  │  workspaceKey.set(None)  │                         │
  │  clearURL() → "/"        │                         │
  │  onExpired()             │                         │
  │ → GlobalError.WorkspaceExpired                     │
  │ → blue info banner       │                         │
```

Same proxy flow as Scenario 2, but the backend returns A13 opaque 404 (identical
whether the key never existed or expired — no timing oracle). The SPA's
`preValidate.tapError` clears the stale key, resets URL to `/`, and shows the
blue info banner: "This workspace has expired or does not exist."

#### Scenario 4: Malformed key — user visits `/w/short`

```
Browser                    Nginx                    Backend
  │                          │                         │
  │─ GET /w/short ───────────▶│                         │
  │  Accept: text/html       │                         │
  │                          │─ rewrite → index.html   │
  │◀── 200 index.html ───────│                         │
  │                          │                         │
  │ SPA boots                │                         │
  │ extractKeyFromURL():     │                         │
  │  regex requires exactly  │                         │
  │  22 base64url chars      │                         │
  │  "short" → no match      │                         │
  │  → None                  │                         │
  │                          │                         │
  │ (no API calls — landing page)                      │
```

The proxy still serves `index.html` — it doesn't validate the key. The SPA's
Iron-validated regex (`[A-Za-z0-9_-]{22}`) rejects the malformed key client-side.
No API call is made. The user sees the landing page as if they visited `/`.

#### Summary table

| Scenario | Browser Request | Proxy Action | SPA Behaviour |
|---|---|---|---|
| No key (`/`) | `GET /` Accept: text/html | Serve `index.html` | Landing page, form ready |
| Valid key | `GET /w/{key}` Accept: text/html | Rewrite → `index.html` | Extract key, `preValidate()` → load trees |
| Expired key | `GET /w/{key}` Accept: text/html | Rewrite → `index.html` | Extract key, `preValidate()` → 404 → clear key, info banner |
| Malformed key | `GET /w/short` Accept: text/html | Rewrite → `index.html` | `extractKeyFromURL()` fails regex → landing page |
| API call | `GET /w/{key}/risk-trees` Accept: application/json | Proxy to backend:8090 | — |

## Trade-Offs

### Gained over hash routing

- **Infrastructure visibility:** Mesh policies, access logs, rate limiters, WAF rules all see `/w/` in the path
- **Single URL scheme:** Browser URL and API path share `/w/{key}` prefix — no translation layer
- **History API native:** `pushState` / `replaceState` — no `hashchange` event wiring
- **Future deep-linking ready:** `/w/{key}/tree/{treeId}` works naturally with path routing; hash routing would need nested fragment parsing

### Lost vs hash routing

- **SPA fallback required:** Must configure reverse proxy / Vite / static server. Hash routing needed zero server config.
- **Dev-prod parity gap:** Browser refresh on `/w/{key}` fails in unpatched Vite dev mode (users don't hit this path today because navigation is via `replaceState` from `/`)

### Accepted risk

The SPA fallback gap is a **deployment concern, not a correctness concern**. The
application functions correctly in all current workflows. The gap matters only
when a user directly navigates to `/w/{key}` (bookmark, shared link paste,
browser refresh). This ADR documents the requirement so deployment configurations
are correct from the start.

## Code Smells

### ❌ Parsing `location.hash` for Workspace Key

```scala
// BAD: Hash fragment — invisible to server, mesh, proxy
val key = dom.window.location.hash.stripPrefix("#/w/")
```

```scala
// GOOD: Path segment — visible to infrastructure
dom.window.location.pathname match
  case workspacePathPattern(key) => WorkspaceKeySecret.fromString(key).toOption
  case _                         => None
```

### ❌ Setting `location.hash` for Navigation

```scala
// BAD: Pollutes history, triggers hashchange, invisible to server
dom.window.location.hash = s"#/w/${key.reveal}"
```

```scala
// GOOD: No history entry, no page reload, server-visible path
dom.window.history.replaceState(null, "", s"/w/${key.reveal}")
```

### ❌ Serving SPA Without Fallback in Production

```nginx
# BAD: No fallback — browser refresh on /w/{key} returns 404
location / {
    proxy_pass http://backend:8090;
}
```

```nginx
# GOOD: SPA fallback for browser navigation, proxy for API
location /w/ {
    if ($http_accept ~* "application/json") {
        proxy_pass http://backend:8090;
        break;
    }
    try_files $uri /index.html;
}
```

## Implementation

| Location | Pattern |
|----------|---------|
| `WorkspaceState.extractKeyFromURL()` | Path parsing with `location.pathname` |
| `WorkspaceState.pushKeyToURL()` | `history.replaceState` (no hash) |
| `WorkspaceState.clearURL()` | Reset to `/` on expiry |
| `WorkspaceState.preValidate()` | Validates key via API on mount |
| Reverse proxy (nginx / Istio) | SPA fallback for `/w/*` browser GETs |
| `vite.config.js` | `appType: 'spa'` for dev fallback (TODO) |

## Open Questions

1. **Vite dev fallback timing:** Apply `appType: 'spa'` now or defer? Currently no dev workflow triggers the gap.
2. **Deep-linking scope:** Should `/w/{key}/tree/{treeId}` be supported? Requires `Router.scala` extraction from `WorkspaceState`.
3. **`popstate` listener:** Browser back/forward doesn't currently update `WorkspaceState`. Acceptable for single-workspace UX; needed for multi-workspace navigation.

## References

- [ADR-021: Capability URLs](ADR-021-capability-urls.md) — `/w/*` mesh exemption design
- [ADR-022: Secret Handling](ADR-022-secret-handling.md) — `WorkspaceKeySecret` Iron type
- IMPLEMENTATION-PLAN.md Phase W.6 — original `Router.scala` design (superseded)
