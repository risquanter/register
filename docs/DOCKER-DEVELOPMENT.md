# Docker & Development Guide

## Overview

This guide covers containerization, deployment, and development environment setup for the Risk Register project.

## Prerequisites

- Docker 20.10+ 
- Docker Compose 2.0+
- JDK 21 (for Scala / sbt development)
- sbt (Scala Build Tool)
- Node.js 18+ and npm (for frontend Vite dev server)
- At least 4GB RAM available for Docker

---

## Quick Start

### Cluster Commands

#### Teardown — stop everything managed by this Compose file

```bash
# Stop and remove all containers, networks, and the Irmin data volume.
# Covers all profiles at once — safe to run regardless of what is currently up.
docker compose \
  --profile persistence \
  --profile frontend \
  --profile observability \
  down -v
```

Omit `-v` to keep Irmin data across restarts.

---

#### Nginx cluster (full prod-equivalent stack)

Serves the compiled SPA via nginx on port 18080, proxying API calls internally.
No Vite, no sbt watch needed.

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  up -d

# Verify
docker compose ps
curl http://localhost:18080/          # SPA via nginx
curl http://localhost:8090/health     # API direct
curl http://localhost:9080/graphql \  # Irmin GraphiQL (GET in browser)
  -X POST -H 'Content-Type: application/json' \
  -d '{"query":"{ __typename }"}'
```

Access: **`http://localhost:18080`**

---

#### Vite dev cluster (fast iteration on frontend/backend)

Backend runs in Docker (native binary); frontend is served by Vite with HMR.
Scala.js recompiles on every save.

```bash
# Terminal 1 — backend (in-memory, default)
docker compose up -d register-server

# Terminal 2 — Scala.js watch compiler
sbt ~app/fastLinkJS

# Terminal 3 — Vite dev server
cd modules/app && npm run dev
```

Access: **`http://localhost:5173`**

To use Irmin persistence instead of in-memory:
```bash
docker compose --profile persistence up -d register-server irmin
```

> **How Vite mode differs from nginx mode:**
> Vite is a direct-origin frontend — the browser makes API calls directly from
> `http://localhost:5173` to the backend at `http://localhost:8090`. There is no
> server-side proxy: two distinct origins are involved, so the browser enforces
> CORS. This is why `REGISTER_CORS_ORIGINS` must list `localhost:5173`.
>
> In nginx mode the browser only ever talks to port 18080; nginx proxies API
> calls internally on the Docker network. No cross-origin request reaches the
> browser, so CORS is not triggered and `REGISTER_CORS_ORIGINS` is irrelevant
> for that mode.
>
> **Remote-backend consequence:** if the Docker cluster runs on a different
> machine (e.g. a dev VM at `192.168.1.50`) while your browser loads Vite from
> your laptop at `192.168.1.100:5173`, the browser sees a different origin and
> the backend will reject the request. Add the Vite origin explicitly:
> ```bash
> REGISTER_CORS_ORIGINS=http://192.168.1.100:5173 docker compose up -d register-server
> ```
> This does not apply to nginx mode — the proxy collapses everything to a single
> origin regardless of which machine the cluster runs on.

---

#### One-Time Setup: Irmin Builder Base Image

`local/irmin-builder:3.11` is not pushed to any registry — it must be built
locally before building the Irmin production image for the first time:

```bash
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 containers/builders/
```

> **Note:** This build installs the OCaml toolchain and compiles several opam
> packages from source. Expect **15–40 minutes** on a first run. The resulting
> image is a named tagged image that survives `docker builder prune`.

Rebuild only when Irmin or OCaml versions change.

For local development/debugging, a separate dev image with full toolchain is
available:

```bash
docker build -f containers/dev/Dockerfile.irmin-dev -t local/irmin-dev:3.11 .
```

### Full Clean Build (all images from scratch)

When building from a clean state (no pre-existing images), execute in this
order. Steps 1, 3, and 4 are independent (can run in parallel); step 2
depends on step 1; step 5 depends on step 3.

```bash
# 1. Irmin builder base (opam + irmin packages) — ~15-40 min first run
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 containers/builders/

# 2. Irmin production image (slim Alpine runtime) — ~10s (copies from builder)
#    Requires step 1.
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11 containers/prod/

# 3. GraalVM builder base (native-image + sbt) — ~1-2 min
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 containers/builders/

# 4. Frontend SPA (node:22-alpine builder + nginx:1.27.5-alpine-slim runtime) — ~10-15 min first run
#    Self-contained: downloads and verifies sbt internally. Independent of steps 1-3.
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:dev .

# 5. Register server production (native binary on distroless) — ~5-10 min
#    Requires step 3.
docker build -f containers/prod/Dockerfile.register-prod \
  -t register-server:prod .

# Verify all images
docker images | grep -E 'irmin|register|graalvm|frontend'
```

### Start / Run Modes

```bash
# In-memory backend (default)
docker compose up -d register-server

# Irmin backend (app + Irmin) — option A: .env.irmin
docker compose --profile persistence --env-file .env.irmin up -d register-server irmin

# Irmin backend (app + Irmin) — option B: inline overrides
docker compose --profile persistence \
  up -d \
  -e REGISTER_REPOSITORY_TYPE=irmin \
  -e IRMIN_URL=http://irmin:8080 \
  register-server irmin

# Irmin only (useful for Irmin client tests/tools)
docker compose --profile persistence up -d irmin

# View logs
docker compose logs -f

# Stop all services
docker compose down
```

### Check Status

```bash
# All services
docker compose ps

# Specific service
docker compose ps register-server
docker compose ps irmin
```

---

## Services

### Risk Register Server (Native Image)

**Image:** GraalVM native binary on distroless  
**Ports:**
- 8090 — Main API (mTLS STRICT in mesh)
- 8091 — Health probes (mTLS PERMISSIVE in mesh)

**Endpoints:**
- Health (API): `http://localhost:8090/health`
- Health (probe): `http://localhost:8091/health`
- Readiness (probe): `http://localhost:8091/ready`
- Swagger: `http://localhost:8090/docs`

**Performance:**
- Startup: ~5-10ms
- Memory: ~50-80 MB
- Image size: ~118 MB

#### Build Architecture

The native image build uses a **two-layer strategy** to minimise rebuild times:

1. **Pre-built builder base image** (`local/graalvm-builder:21`) — contains
   GraalVM, native-image, OS packages, and sbt. Built once from
   `containers/builders/Dockerfile.graalvm-builder`. Rebuild only when GraalVM
   or sbt versions change.
2. **Dependency layer** — resolves sbt dependencies from `build.sbt` and
   `project/`. Cached as long as build definitions don't change.
3. **Source layer** — compiles application code and produces the native binary.
   This is the only layer that rebuilds on a source-only edit.

The production image (stage 2) remains `gcr.io/distroless/static-debian12:nonroot`.


#### One-Time Setup: Builder Base Image

Before the first native-image build (or after bumping GraalVM/sbt versions),
build the toolchain image:

```bash
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 containers/builders/
```

This takes ~1–2 minutes and only needs to be repeated when:
- GraalVM version changes (update the `FROM` in `containers/builders/Dockerfile.graalvm-builder`)
- sbt version changes (update `SBT_VERSION` arg to match `project/build.properties`)

#### Build Options

```bash
# Build native image (production)
docker compose build register-server

# Build without cache (forces dependency re-resolution + recompile)
docker compose build --no-cache register-server

# Build and start
docker compose up --build register-server -d
```

#### Standalone Docker

```bash
# Build prod image
docker build -f containers/prod/Dockerfile.register-prod -t register-server:prod .

# Run
docker run -p 8090:8090 --name register-server register-server:prod
```

---

### Irmin GraphQL Server (Persistence Layer)

Irmin is a versioned key-value store with Git-like semantics. It provides content-addressable storage with full version history.

**Port:** 9080 (host) → 8080 (container)  
**Endpoint:** `http://localhost:9080/graphql`  
**GraphiQL UI:** `http://localhost:9080/graphql` (GET in browser)  
**Schema:** `dev/irmin-schema.graphql`

**Performance (prod image):**
- Startup: ~500ms
- Memory: ~100-150 MB
- Image size: ~87 MB (prod, slim Alpine) / ~650-700 MB (dev with OCaml toolchain)

#### Quick Start

```bash
# Start Irmin
docker compose --profile persistence up irmin -d

# Check status
docker compose ps irmin

# View logs
docker compose logs -f irmin

# Stop
docker compose stop irmin
```

#### Why Irmin?

- **Versioned data**: Every change tracked like Git
- **Branching/merging**: Offline-first with conflict resolution
- **GraphQL API**: Type-safe, introspectable queries
- **Content-addressable**: Immutable, cryptographically verified
- **Time-travel**: Query any historical state

**For detailed testing, see:** [Testing Guide - Irmin Section](test/TESTING.md#irmin-graphql-server-tests)

---

### Frontend SPA (nginx)

The SPA is a Scala.js + Vite application compiled at build time and served by
nginx. It acts as both a static file server and an Accept-header router:
browser navigation renders the SPA shell; API requests (Accept: application/json)
are proxied to the register-server.

**Port:** 18080 (host) → 8080 (container)  
**Endpoint:** `http://localhost:18080/`  
**Implements:** [ADR-027](ADR-027-frontend-nginx-serving.md) (local); ADR-INFRA-007 (authoritative, in register-infra)

**Performance:**
- Startup: ~100ms (nginx)
- Memory: ~5-10 MB
- Image size: ~25 MB

#### Build Architecture

The frontend build uses a two-stage strategy:

1. **Builder stage** (`node:22-alpine`) — OpenJDK 21 + sbt (SHA256-verified
   download) + `npm install --ignore-scripts` + `vite build`. The Vite plugin
   (`@scala-js/vite-plugin-scalajs`) invokes sbt internally to compile
   Scala.js. Output: `modules/app/dist/`.
2. **Runtime stage** (`nginx:1.27.5-alpine-slim`) — static files served from
   `/srv/app/`; nginx.conf baked in (no ConfigMap dependency). DNS resolver
   injected at container startup from `/etc/resolv.conf`, allowing the image
   to work in both Docker and Kubernetes without rebuilding.

Layer caching mirrors the other images: build definition → sbt deps →
npm deps → source compile. Only the source layer rebuilds on a code change.

#### Standalone Docker

```bash
# Build
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:dev .

# Run
docker run --rm -p 18080:8080 local/frontend:dev

# Smoke test
curl -s http://localhost:18080/ | grep -q '<html' && echo OK

# Verify cache headers on a hashed asset
JS=$(docker run --rm local/frontend:dev sh -c \
  'find /srv/app/assets -name "*.js" | head -1 | sed s|/srv/app||')
curl -sI "http://localhost:18080${JS}" | grep -E 'Cache-Control|X-Content-Type-Options'
# Expected:
#   Cache-Control: public, immutable, max-age=31536000
#   X-Content-Type-Options: nosniff
```

**Security properties:**
- Non-root execution (uid 101 / nginx user)
- Port 8080 — no `CAP_NET_BIND_SERVICE` required
- `server_tokens off` — nginx version not disclosed in headers or error pages
- Static assets: `Cache-Control: public, immutable, max-age=31536000` +
  `X-Content-Type-Options: nosniff`
- Build-time: sbt download SHA256-verified; npm `ignore-scripts=true`;
  exact dependency pins (ADR-020)

---

## Configuration

### Environment Variables

Configure via `docker-compose.yml`, `.env`, or CLI overrides:

| Variable | Default | Description |
|----------|---------|-------------|
| `REGISTER_SERVER_HOST` | `0.0.0.0` | Server bind address |
| `REGISTER_SERVER_PORT` | `8090` | Main API server port |
| `REGISTER_HEALTH_PORT` | `8091` | Health probe port (kubelet liveness/readiness) |
| `REGISTER_DEFAULT_NTRIALS` | `10000` | Default simulation trials |
| `REGISTER_MAX_TREE_DEPTH` | `5` | Maximum risk tree depth |
| `REGISTER_TRIAL_PARALLELISM` | `8` | Trial-level parallelism (fibers per simulation) |
| `REGISTER_MAX_CONCURRENT_SIMULATIONS` | `4` | Max concurrent simulations |
| `REGISTER_MAX_NTRIALS` | `1000000` | Maximum trials per simulation |
| `REGISTER_MAX_PARALLELISM` | `16` | Maximum parallelism |
| `REGISTER_SEED3` | `0` | HDR histogram seed 3 (0 = random, ADR-003) |
| `REGISTER_SEED4` | `0` | HDR histogram seed 4 (0 = random, ADR-003) |
| `REGISTER_CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Allowed CORS origins (comma-separated). `localhost:18080` (nginx) is intentionally absent — in that mode the browser only talks to nginx; API calls are proxied server-side so the browser never issues a cross-origin request to port 8090. |
| `REGISTER_API_LIST_ALL_TREES_ENABLED` | `false` | Enable `GET /risk-trees` list-all endpoint (A17 gate) |
| `REGISTER_WORKSPACE_TTL` | `72h` | Workspace time-to-live |
| `REGISTER_WORKSPACE_IDLE_TIMEOUT` | `1h` | Workspace idle expiry |
| `REGISTER_WORKSPACE_REAPER_INTERVAL` | `5m` | How often the reaper runs |
| `REGISTER_WORKSPACE_MAX_CREATES_PER_IP` | `5` | Max workspace creates per IP per hour |
| `REGISTER_WORKSPACE_MAX_TREES` | `10` | Max risk trees per workspace |
| `OTEL_SERVICE_NAME` | `risk-register` | OpenTelemetry service name |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP endpoint |
| `REGISTER_REPOSITORY_TYPE` | `in-memory` | Set to `irmin` to enable Irmin backend |
| `IRMIN_URL` | `http://localhost:9080` | Irmin GraphQL URL (use `http://irmin:8080` inside compose) |
| `IRMIN_BRANCH` | `main` | Irmin default branch |
| `IRMIN_TIMEOUT_SECONDS` | `30` | Irmin request timeout |
| `IRMIN_HEALTHCHECK_TIMEOUT_MILLIS` | `5000` | Irmin startup health-check timeout |
| `IRMIN_HEALTHCHECK_RETRIES` | `0` | Irmin health-check retries (0 = fail-fast) |
| `BACKEND_URL` | `http://register-server:8090` | **Frontend (nginx) only** — register-server URL for proxy_pass |

### Custom Configuration

**Option A: .env for Irmin mode**

```bash
cat > .env.irmin <<'EOF'
REGISTER_REPOSITORY_TYPE=irmin
IRMIN_URL=http://irmin:8080
EOF

docker compose --profile persistence --env-file .env.irmin up -d register-server irmin
```

**Option B: .env for in-memory mode**

```bash
cat > .env.inmemory <<'EOF'
REGISTER_REPOSITORY_TYPE=in-memory
EOF

docker compose --env-file .env.inmemory up -d register-server
```

**Option C: Inline overrides**

```bash
docker compose --profile persistence \
  up -d \
  -e REGISTER_REPOSITORY_TYPE=irmin \
  -e IRMIN_URL=http://irmin:8080 \
  register-server irmin
```

---

## Data Persistence

### Irmin Volume

Irmin data persists in Docker volume: `register_irmin-data`

```bash
# List volumes
docker volume ls | grep irmin

# Inspect volume
docker volume inspect register_irmin-data

# Backup volume
docker run --rm -v register_irmin-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/irmin-backup.tar.gz -C /data .

# Restore volume
docker run --rm -v register_irmin-data:/data -v $(pwd):/backup \
  alpine tar xzf /backup/irmin-backup.tar.gz -C /data

# Remove volume (DESTROYS DATA)
docker volume rm register_irmin-data
```

---

## Development Workflow

### Backend Only (Docker)

For backend-only work or API testing:

```bash
# Start server (in-memory backend, default)
docker compose up -d

# Start with Irmin persistence
docker compose --profile persistence --env-file .env.irmin up -d register-server irmin

# View logs
docker compose logs -f

# Restart after config changes
docker compose restart register-server

# Stop
docker compose down
```

### Full-Stack Development (Backend + Frontend)

See the [Vite dev cluster](#vite-dev-cluster-fast-iteration-on-frontendbacked) and
[Nginx cluster](#nginx-cluster-full-prod-equivalent-stack) recipes in Quick Start
for the exact commands. The key difference between the two modes:

| | Vite dev mode | Nginx prod mode |
|---|---|---|
| Frontend port | 5173 | 18080 |
| API calls | Browser → 8090 directly (cross-origin) | Browser → 18080 → nginx → 8090 (same-origin) |
| CORS | Required — browser enforces it | Not involved — no cross-origin request |
| Frontend updates | HMR on every Scala.js save | Requires image rebuild + `up -d` |
| Use for | Day-to-day GUI development | Testing nginx routing, security headers, prod parity |

Vite dev mode requires **three terminals**:

**Terminal 1 — Backend (Docker):**
```bash
docker compose up -d register-server
```

**Terminal 2 — Scala.js continuous compilation:**
```bash
sbt ~app/fastLinkJS
```
Watches `modules/app/` and `modules/common/` and recompiles to JavaScript on every
save. The `~` prefix triggers re-runs automatically.

**Terminal 3 — Vite dev server:**
```bash
cd modules/app && npm run dev
```
Serves the frontend at `http://localhost:5173` with hot module replacement. Vite
picks up Scala.js output automatically via the `@scala-js/vite-plugin-scalajs` plugin.

**Verify the full stack:**
1. Open `http://localhost:5173` — the frontend should load
2. The header should show "Connected" (health check to backend)
3. Open `http://localhost:8090/docs` — Swagger UI for API exploration

### Backend Development (sbt, no Docker)

For faster iteration on server code without rebuilding the native image:

```bash
# Run server directly on JVM (faster startup cycle than native image rebuild)
sbt server/run

# Run with continuous restart on changes
sbt ~server/run
```

Note: `sbt server/run` runs the JVM version. The Docker image uses a GraalVM native binary which must be explicitly rebuilt (see below).

### Running Tests

```bash
# All tests (common + server)
sbt test

# Common module tests only (fastest)
sbt commonJVM/test

# Server module tests only
sbt server/test

# Frontend compilation check (no unit tests yet)
sbt app/fastLinkJS

# With coverage
sbt coverage test coverageReport
```

### Rebuilding the Native Image

The Docker image contains a GraalVM native binary. Source changes are **not** reflected until you rebuild:

```bash
# Full rebuild (clean image, pick up all source changes)
docker compose down && docker compose build --no-cache register-server && docker compose up -d

# Incremental rebuild (uses Docker layer cache — faster but may miss some changes)
docker compose up --build -d
```

The native image build takes several minutes (GraalVM compilation inside Docker). For faster iteration, use `sbt server/run` during development and only rebuild the native image for integration testing or deployment.

---

## Docker Images

### Multi-Stage Build (Register Server)

1. **Builder base** (`local/graalvm-builder:21`) — pre-built toolchain: GraalVM,
   `native-image`, sbt. Built once from `containers/builders/Dockerfile.graalvm-builder`;
   rebuilt only when GraalVM or sbt versions change.
2. **Build layer** — sbt resolves dependencies + compiles Scala source +
   GraalVM `native-image` produces a fully static binary.
3. **Runtime stage** (`gcr.io/distroless/static-debian12:nonroot`) — static binary only;
   no shell, no package manager, no libc.

**Properties:**
- Static binary (no dynamic library dependencies)
- Non-root user (UID 65532 / `nonroot`) — numeric UID for Kubernetes `runAsNonRoot`
- No shell (`/bin/sh` absent — distroless)
- Minimal attack surface (~118 MB total)

### Multi-Stage Build (Irmin Server)

1. **Builder base** (`local/irmin-builder:3.11`) — pre-compiled opam packages
   (`irmin-cli.3.11.0`, `irmin-graphql.3.11.0`, `irmin-pack.3.11.0`). Built once;
   rebuilt only when Irmin or OCaml versions change.
2. **Runtime stage** (`alpine:3.21`) — slim Alpine with only `libgmp` + `libffi`
   dynamically linked by the irmin binary.

**Image sizes:**
- Prod (`local/irmin-prod:3.11`): ~87 MB
- Dev (`local/irmin-dev:3.11`): ~650-700 MB (full opam toolchain + irmin-git)

### Multi-Stage Build (Frontend SPA)

1. **Builder stage** (`node:22-alpine`) — OpenJDK 21 + sbt (SHA256-verified) +
   npm (exact-pinned, scripts blocked) + Vite. Outputs `modules/app/dist/`.
2. **Runtime stage** (`nginx:1.27.5-alpine-slim`) — static files at `/srv/app/`;
   nginx.conf baked in with routing rules per ADR-INFRA-007.

**Image size:** ~25 MB

See [ADR-020](ADR-020-supply-chain-security.md) for supply chain security policy,
[ADR-027](ADR-027-frontend-nginx-serving.md) for the local routing rationale, and
[ADR-INFRA-007] in register-infra for the complete architectural decision.

---

## Observability (Optional)

### OpenTelemetry Collector

```bash
# Start with observability stack
docker compose --profile observability up -d

# Access Prometheus metrics
curl http://localhost:8889/metrics

# View OTLP traces
# Configure your observability backend endpoint
```

### Configure External OTLP

```yaml
environment:
  OTEL_EXPORTER_OTLP_ENDPOINT: "https://your-platform:4317"
```

---

## Production Deployment

### Resource Limits

```yaml
services:
  register-server:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 256M
        reservations:
          cpus: '0.5'
          memory: 64M
```

### Logging

```yaml
services:
  register-server:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

### Security Scanning

```bash
# Scan for vulnerabilities
docker scan register-server:prod

# Or use Trivy
trivy image register-server:prod
```

---

## Troubleshooting

### Container Won't Start

```bash
# Check logs
docker compose logs register-server
docker compose logs irmin

# Check if port is in use
lsof -i :8090
lsof -i :9080

# Rebuild without cache
docker compose build --no-cache

# Check container status
docker compose ps
```

### Memory Issues

Increase JVM heap for JVM-based services:

```yaml
environment:
  JAVA_OPTS: "-Xms1g -Xmx4g -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

For native image, memory is managed automatically.

### Irmin GraphQL Not Responding

```bash
# Check container health
docker compose ps irmin

# Test endpoint
curl -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __typename }"}'

# Check inside container
docker exec irmin-graphql sh -c 'wget -O - http://127.0.0.1:8080/graphql'

# View health logs
docker inspect irmin-graphql --format='{{range .State.Health.Log}}{{.Output}}{{end}}'
```

### Build Failures

```bash
# Clean build artifacts
sbt clean

# Remove Docker build cache
docker builder prune -a

# Rebuild from scratch
docker compose build --no-cache
```

### Network Issues

```bash
# Inspect network
docker network inspect register-network

# Check container connectivity
docker exec register-server ping irmin-graphql

# Recreate network
docker compose down
docker compose up -d
```

---

## Health Checks

### Register Server

```bash
# HTTP health endpoint
curl http://localhost:8090/health

# Docker health status
docker inspect --format='{{.State.Health.Status}}' register-server
```

**Expected Response:**
```json
{
  "status": "healthy",
  "service": "risk-register"
}
```

### Irmin Server

```bash
# GraphQL health check
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __typename }"}'

# Docker health status
docker inspect --format='{{.State.Health.Status}}' irmin-graphql
```

---

## Performance Benchmarks

### Register Server (Native Image)

| Metric | Native Image | JVM | Improvement |
|--------|--------------|-----|-------------|
| Startup Time | 5-10ms | 3-5s | 500x faster |
| Memory (idle) | 50-80 MB | 200-300 MB | 75% reduction |
| Image Size | 118 MB | 200 MB | 41% smaller |
| Cold Response | 10-20ms | 50-100ms | 3-5x faster |

### Irmin Server

| Metric | Value |
|--------|-------|
| Startup Time | ~500ms |
| Memory (idle) | 100-150 MB |
| Write Latency | < 200ms |
| Read Latency | < 100ms |
| Image Size | ~650 MB (dev) / ~87 MB (prod) |

---

## Security Notes

### Distroless Benefits (Register Server)

- ✅ No shell (`/bin/sh` not present)
- ✅ No package manager
- ✅ Minimal attack surface (~2 MB base)
- ✅ Non-root user (`nonroot:nonroot`)
- ✅ Static binary (no dynamic libraries)

### Verify Security

```bash
# Try to exec into distroless container (will fail - no shell)
docker exec -it register-server /bin/sh
# Expected: exec failed: no such file or directory

# Check running user
docker exec register-server id 2>/dev/null || echo "Cannot exec (distroless)"

# Scan for CVEs
trivy image register-server:prod
```

---

## Related Documentation

- [Testing Guide](test/TESTING.md) - Comprehensive test procedures
- [ADR-012: Service Mesh Strategy](ADR-012.md)
- [ADR-020: Supply Chain Security](ADR-020-supply-chain-security.md)
- [Implementation Plan](IMPLEMENTATION-PLAN.md)
- [Authorization Plan](AUTHORIZATION-PLAN.md)

---

## Quick Reference

```bash
# Start everything
docker compose --profile persistence up -d

# View logs
docker compose logs -f

# Check health
curl http://localhost:8090/health
curl -X POST http://localhost:9080/graphql -H "Content-Type: application/json" -d '{"query": "{ __typename }"}'

# Run tests
./docs/test/run-tests.sh

# Stop everything
docker compose down

# Remove all data
docker compose down -v
```
