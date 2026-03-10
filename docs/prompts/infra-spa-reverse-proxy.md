# Agent Prompt: SPA Reverse Proxy Configuration for register-infra

## Task

The Risk Register SPA uses **path-based routing** (`/w/{key}`) for workspace
capability URLs. This means browser navigation requests (bookmark, refresh,
shared link) hit `/w/{key}` — a path that is also used by the API. The reverse
proxy must distinguish these and route them correctly.

Your task: find the appropriate place in `~/projects/register-infra` (the
Hetzner Cloud deployment infrastructure project) to add the SPA fallback
configuration. This may be an nginx config, a Caddy config, a Traefik config,
an Ingress resource, or whatever reverse proxy the project uses. Adapt the
patterns below to match the project's existing conventions.

## Requirement

The reverse proxy must handle `/w/*` requests differently based on the `Accept`
header:

| Request Source | `Accept` Header | Expected Action |
|---|---|---|
| Browser address bar / refresh / bookmark | `text/html, application/xhtml+xml, …` | Serve `index.html` (SPA shell) |
| Fetch API / XHR from SPA JavaScript | `application/json` | Proxy to backend (port 8090) |

Additionally:
- `GET /` → serve `index.html`
- `GET /health`, `GET /docs` → proxy to backend
- `POST /workspaces` → proxy to backend
- Static assets (`*.js`, `*.css`, `*.woff2`) → serve from static files directory

## Suggested Nginx Pattern

This is a **suggestion** — adapt to whatever the project actually uses:

```nginx
# Static assets — highest priority
location ~* \.(js|css|woff2|svg|png|ico)$ {
    root /srv/app;
    expires 1y;
    add_header Cache-Control "public, immutable";
}

# API routes — proxy to backend
location /workspaces {
    proxy_pass http://backend:8090;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location /health {
    proxy_pass http://backend:8090;
}

location /docs {
    proxy_pass http://backend:8090;
}

# Workspace routes — dual purpose (SPA + API)
location /w/ {
    # API calls (Fetch with Accept: application/json) → backend
    if ($http_accept ~* "application/json") {
        proxy_pass http://backend:8090;
        break;
    }
    # Browser navigation (Accept: text/html) → SPA shell
    try_files $uri /index.html;
}

# SSE endpoint (if applicable)
location /sse/ {
    proxy_pass http://backend:8090;
    proxy_set_header Connection '';
    proxy_http_version 1.1;
    chunked_transfer_encoding off;
    proxy_buffering off;
    proxy_cache off;
}

# Default — SPA fallback
location / {
    root /srv/app;
    try_files $uri /index.html;
}
```

## Why This Is Needed

Without this configuration, the following break:
- **Browser refresh** on `/w/{key}` → 404 (no file at that path)
- **Pasting a shared link** `/w/{key}` → 404 
- **Bookmarks** to `/w/{key}` → 404

The SPA handles all workspace URL parsing client-side via
`WorkspaceState.extractKeyFromURL()`. The reverse proxy just needs to serve
`index.html` for any `/w/*` request that isn't a JSON API call.

## Security Headers (already emitted by the backend, but can be reinforced)

The backend's `SecurityHeadersInterceptor` already sets these on all responses:
- `Referrer-Policy: no-referrer` (prevents workspace key leaking via Referer)
- `Cache-Control: no-store` (on `/w/*` and `/workspaces` responses)
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- `X-Frame-Options: DENY`
- `Content-Security-Policy: default-src 'self'; ...`

If the proxy adds its own headers, ensure they don't conflict (e.g., don't set
`Cache-Control: public` on proxied API responses).

## Reference

- `~/projects/register/docs/ADR-025-spa-routing-strategy.md` — architectural decision
- `~/projects/register/docs/ADR-021-capability-urls.md` — capability URL design
- `~/projects/register/docs/IMPLEMENTATION-PLAN.md` Phase W.6 — frontend routing
