# BATS Smoke / Integration Test Plan

**Status:** Approved in principle — not yet implemented  
**Date:** 2026-03-11

---

## Overview

Three test suites validating the Docker images as black boxes.
All suites use BATS (bats-core + bats-support + bats-assert).

---

## Suite A — Full Production Stack

**Compose:** `local/frontend:dev` + `register-server:prod` + `local/irmin-prod:3.11`  
**Profile:** `--profile persistence`  
**Purpose:** End-to-end validation of the complete production topology.

### Tests

1. **Irmin health** — POST `{"query":"{ __typename }"}` to irmin:8080/graphql → 200 + `data`
2. **Register health** — GET /health on register-server:8090 → 200
3. **POST /workspaces** — create workspace → 201 + JSON with key
4. **GET /w/{key}/risk-trees** — list trees for workspace → 200 + JSON array
5. **Irmin persistence** — create tree via API, query Irmin GraphQL directly → data present
6. **nginx serves index.html** — GET / on frontend:8080 → 200 + `<html`
7. **nginx Accept-header discrimination** — GET /w/{key} with `Accept: application/json` → proxy → real JSON from backend; GET /w/{key} with `Accept: text/html` → 200 + HTML
8. **nginx static asset cache** — GET /assets/main-*.js → `Cache-Control: public, immutable`
9. **nginx SPA fallback** — GET /nonexistent/path → 200 + HTML (not 404)
10. **nginx /docs proxy** — GET /docs on frontend → proxied to backend
11. **server_tokens off** — response `Server:` header has no version number
12. **X-Content-Type-Options** — nosniff on nginx-generated responses

### Setup / Teardown

- `setup_file`: `docker compose --profile persistence up -d --wait`
- `teardown_file`: `docker compose --profile persistence down -v`

---

## Suite B — Dev Irmin (Self-Contained)

**Compose:** `local/irmin-dev:3.11` only  
**Purpose:** Validate the self-contained dev Irmin image (full opam toolchain).

### Tests

1. **Container starts** — healthcheck passes within 30s
2. **GraphQL introspection** — POST `{ __schema { types { name } } }` → 200
3. **Set/get key** — mutation + query round-trip via GraphQL
4. **Non-root** — `docker exec … id -u` → 65532
5. **Read-only root** — `docker exec … touch /test` → permission denied

### Setup / Teardown

- `setup_file`: `docker run -d --name irmin-dev-test local/irmin-dev:3.11`
- `teardown_file`: `docker rm -f irmin-dev-test`

---

## Suite C — In-Memory Mode (No Irmin Dependency)

**Compose:** `register-server:prod` + `local/frontend:dev` (no persistence profile)  
**Purpose:** Validate the full nginx routing behaviour (ADR-INFRA-007 §1) and
register-server in-memory mode without Irmin complexity. This is the primary
ADR-INFRA-007 / ADR-027 validation suite.

### Tests

1. **Register health** — GET /health → 200
2. **POST /workspaces** — create workspace → 201 + JSON key
3. **GET /w/{key}/risk-trees** — Accept: application/json → 200 + JSON from backend (real data, not 502)
4. **Accept-header discrimination** — GET /w/{key} with Accept: text/html → 200 + HTML (SPA shell); GET /w/{key} with Accept: application/json → 200 + JSON (proxied)
5. **nginx SPA fallback** — GET /nonexistent → 200 + HTML
6. **Static asset cache** — GET /assets/main-*.js → `Cache-Control: public, immutable, max-age=31536000`
7. **X-Content-Type-Options: nosniff** — present on SPA fallback, present on static assets
8. **server_tokens off** — `Server:` header has no nginx version
9. **POST /workspaces + create tree + GET /w/{key}/risk-trees** — full CRUD round-trip in-memory
10. **/docs proxy** — GET /docs via frontend → proxied to backend
11. **/workspaces proxy** — GET /workspaces via frontend → proxied to backend (if list-all enabled)

### Setup / Teardown

- `setup_file`: `docker compose up -d --wait` (no `--profile persistence`)
- `teardown_file`: `docker compose down`

---

## File Layout (Proposed)

```
tests/
  bats/
    helpers/
      setup.bash          # shared load/helper functions
    suite-a-full-prod.bats
    suite-b-dev-irmin.bats
    suite-c-in-memory.bats
    docker-compose.suite-c.yml   # override: frontend + register-server only
```

---

## Prerequisites

- `bats-core`, `bats-support`, `bats-assert` installed (or git-submoduled)
- All images pre-built (`local/frontend:dev`, `register-server:prod`, `local/irmin-prod:3.11`, `local/irmin-dev:3.11`)
- `curl` and `jq` available on the host

---

## Open Items

- [ ] Decide: git submodule bats libs vs system install vs nix
- [ ] Suite A/C compose override files (frontend service not yet in docker-compose.yml)
- [ ] Parameterise backend hostname in nginx config (currently hardcoded k8s FQDN — see findings below)

### Blocker: nginx Backend Hostname

The nginx.conf.template currently hardcodes `register.register.svc.cluster.local:8090`
(Kubernetes service FQDN). This must be parameterised via an environment variable
(e.g., `BACKEND_URL`) substituted at container startup, so the same image works in
both Docker Compose (`http://register-server:8090`) and Kubernetes
(`http://register.register.svc.cluster.local:8090`).

See the separate findings report for the full analysis.
