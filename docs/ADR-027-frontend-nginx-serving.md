# ADR-027: Frontend nginx Serving — Routing Rules and Image Build

**Status:** Accepted  
**Date:** 2026-03-11  
**Tags:** frontend, nginx, spa, routing, docker

---

> **Authoritative source:** [ADR-INFRA-007] in `risquanter/register-infra` is the
> complete architectural decision covering the full SPA serving strategy — nginx
> routing, Istio Gateway TLS termination, AuthorizationPolicy for capability URLs,
> and image ownership. This ADR extracts the sections implemented in *this*
> repository so developers working on the Dockerfile and nginx config have the
> rationale locally. When the two diverge, ADR-INFRA-007 governs.

---

## Context

- Path-based SPA routing (ADR-025) requires a file-serving component that can `try_files` against a local filesystem — Istio can route between backends but cannot serve files
- The `/w/{key}` path is dual-purpose: browser navigation (`Accept: text/html`) returns the SPA shell; Fetch/XHR (`Accept: application/json`) proxies to the register-server
- Accept-header discrimination must live in nginx, not in Istio HTTPRoute, because one branch needs the filesystem and the other needs the backend — no single Istio backend can serve both
- Static assets produced by Vite carry content-hashed filenames and should be served with immutable cache headers
- The nginx config must be baked into the image (no runtime ConfigMap dependency) to keep the frontend pod self-contained

## Decision

### 1. nginx Pod as File Server and Accept-Header Router

An nginx container serves the built SPA assets and applies Accept-header
discrimination on `/w/*`. All routing logic is in the baked-in `nginx.conf`.

```nginx
# Static assets — immutable cache, content-hashed filenames
location ~* \.(js|css|woff2|svg|png|ico)$ {
    add_header Cache-Control "public, immutable, max-age=31536000";
    add_header X-Content-Type-Options nosniff;
    try_files $uri =404;
}

# /w/* — dual-purpose capability URL path (ADR-021)
location /w/ {
    if ($http_accept ~* "application/json") {
        set $backend "http://register.register.svc.cluster.local:8090";
        proxy_pass $backend;
        break;
    }
    add_header X-Content-Type-Options nosniff;
    try_files $uri /index.html;
}

# API endpoints — unconditionally proxied
location /workspaces { set $backend "…:8090"; proxy_pass $backend; }
location /health     { set $backend "…:8090"; proxy_pass $backend; }
location /docs       { set $backend "…:8090"; proxy_pass $backend; }

# Default — SPA fallback
location / {
    add_header X-Content-Type-Options nosniff;
    try_files $uri /index.html;
}
```

`proxy_pass` uses a variable (`set $backend`) so nginx defers DNS resolution to
request time and starts successfully even when the backend is unreachable.

### 2. Multi-Stage Image Build

The frontend image is a two-stage Docker build at
`containers/prod/Dockerfile.frontend-prod`:

| Stage | Base | Purpose |
|-------|------|---------|
| builder | `node:22-alpine` + OpenJDK 21 + sbt | Scala.js compilation (`fullLinkJS`) + Vite bundling |
| runtime | `nginx:1.27.5-alpine-slim` | Serve `/srv/app/` on port 8080, uid 101 |

The builder stage downloads sbt with SHA-256 verification (ADR-020 §2). npm
packages are installed with `--ignore-scripts` and `.npmrc` enforces
`save-exact=true` (ADR-020 §1, §3).

### 3. Runtime Resolver Injection

The baked-in `nginx.conf.template` contains a `__RESOLVER__` placeholder. The
entrypoint script reads the first `nameserver` from `/etc/resolv.conf` at startup
and substitutes it via `sed`. This works in both Docker (host DNS or `127.0.0.11`)
and Kubernetes (CoreDNS ClusterIP from kubelet).

### 4. Security Properties

- `server_tokens off` — suppresses nginx version in `Server` header and error pages
- `X-Content-Type-Options: nosniff` — on nginx-generated responses only; proxied responses carry the backend's own headers
- Non-root: runs as uid 101 (nginx user)
- No shell-writable state apart from `/tmp` (supports `readOnlyRootFilesystem`)

## Topics in ADR-INFRA-007 Not Covered Here

The following are implemented in `risquanter/register-infra` and documented
exclusively in ADR-INFRA-007:

- **Istio Gateway for TLS termination** — `Gateway` + `HTTPRoute` resources; the
  nginx pod receives plaintext on port 8080 behind the mesh
- **AuthorizationPolicy exception for `/w/*`** — exempts capability URLs from
  JWT enforcement (ADR-021)
- **Helm chart, ArgoCD Application, NetworkPolicy** — deployment manifests for the
  frontend pod

## Code Smells

### ❌ Accept-Header Routing in Istio HTTPRoute

```yaml
# BAD: HTTPRoute can match headers but cannot serve files.
# No backend exists for the text/html branch — index.html is on disk.
rules:
  - matches:
      - headers:
          - name: Accept
            value: application/json
    backendRefs:
      - name: register
        port: 8090
```

```nginx
# GOOD: routing decision in nginx where the filesystem is available.
location /w/ {
    if ($http_accept ~* "application/json") { proxy_pass $backend; break; }
    try_files $uri /index.html;
}
```

### ❌ nginx Config as Runtime ConfigMap

```yaml
# BAD: nginx.conf as a Helm values string — no validation, no reuse.
nginxConf: |
  server { location / { ... } }
```

```dockerfile
# GOOD: nginx.conf baked into the image — validated at build time,
# identical in every environment, no runtime dependency.
RUN cat <<'EOF' > /etc/nginx/nginx.conf.template
...
EOF
```

### ❌ Hardcoded DNS Resolver

```nginx
# BAD: assumes CoreDNS IP — breaks in Docker Compose and other clusters.
resolver 10.96.0.10;
```

```nginx
# GOOD: injected at startup from /etc/resolv.conf.
resolver __RESOLVER__ valid=30s ipv6=off;
```

## Implementation

| Location | Artifact |
|----------|---------|
| `containers/prod/Dockerfile.frontend-prod` | Multi-stage build: Node+sbt → nginx runtime |
| `containers/prod/Dockerfile.frontend-prod` (inline) | `nginx.conf.template` with routing rules |
| `containers/prod/Dockerfile.frontend-prod` (inline) | `docker-entrypoint-frontend.sh` resolver injection |

## References

- **ADR-INFRA-007** (register-infra): SPA Serving Strategy — the authoritative, complete decision
- [ADR-020](ADR-020-supply-chain-security.md): Supply Chain Security — SHA-256 verification, npm hardening
- [ADR-021](ADR-021-capability-urls.md): Capability URLs — `/w/*` authorization model
- [ADR-025](ADR-025-spa-routing-strategy.md): SPA Routing Strategy — path-based `/w/` routing and Accept-header discrimination
- [ADR-026](ADR-026-container-image-strategy.md): Container Image Strategy — builder/dev/prod separation
