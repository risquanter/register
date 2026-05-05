# Docker & Development Guide

## Prerequisites

- Docker 20.10+, Docker Compose 2.0+
- JDK 21, sbt (for local Scala / sbt development)
- Node.js 18+ and npm (for Vite dev server only)

---

## Build from Scratch

All runtime images are built locally. Builder base images avoid re-installing heavyweight
toolchains (OCaml/opam, GraalVM, sbt) on every source change — they are built once and
reused for all subsequent application image builds. See [ADR-026](ADR-026-container-image-strategy.md)
for the image strategy and [ADR-020](ADR-020-supply-chain-security.md) for supply chain
security policy.

Build in this order — steps 1, 3, and 4 are independent; step 2 requires step 1; step 5
requires step 3.

```bash
# 1. Irmin builder base (OCaml + opam packages) — ~15-40 min first run
#    Rebuild only when Irmin or OCaml versions change.
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 containers/builders/

# 2. Irmin production image (slim Alpine runtime) — ~10s
#    Requires step 1.
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11 containers/prod/

# 3. GraalVM builder base (GraalVM native-image + sbt) — ~1-2 min
#    Context is the parent directory — vql-engine source must be in scope.
#    Rebuild when GraalVM/sbt version changes or vql-engine SNAPSHOT bumps.
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 ..

# 4. Frontend SPA (node:22-alpine builder + nginx:1.27.5-alpine-slim runtime) — ~10-15 min first run
#    Context is the parent directory — vague-quantifier-logic/ and hdr-rng/ must be at ../.
#    Rebuild when frontend or common source changes.
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:<version> ..

# 5. Register server (GraalVM native binary on distroless) — ~5-10 min
#    Requires step 3. Rebuild when server or common source changes.
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:<version> .

# Verify
docker images | grep -E 'irmin|register|graalvm|frontend'
```

## Docker Compose — Use Cases

`docker compose down` removes containers. Always use it to stop — never `docker stop` alone.
If a previous run was hard-killed, force-remove the stale container first:

```bash
docker rm -f irmin-graphql   # or register-server, frontend as needed
```

### Use case A: Backend only + Vite dev server

Backend runs in Docker (native binary, in-memory store); frontend served by Vite with HMR.
Best for day-to-day frontend development — Scala.js recompiles on every save.

```bash
# Terminal 1 — backend
docker compose up -d register-server

# Terminal 2 — Scala.js watch compiler
sbt ~app/fastLinkJS

# Terminal 3 — Vite dev server
cd modules/app && npm run dev
```

Access: **http://localhost:5173**

```bash
# Teardown
docker compose down
```

### Use case B: Backend + nginx frontend (in-memory)

Both backend and frontend run as containers. No Vite. Use for testing nginx routing,
security headers, and production-equivalent serving.

```bash
docker compose --profile frontend up -d
```

Access: **http://localhost:18080**

```bash
# Teardown
docker compose --profile frontend down
```

### Use case C: Full stack — register + Irmin persistence + nginx frontend

Register-server defaults to in-memory storage. The `REGISTER_REPOSITORY_TYPE` and
`IRMIN_URL` overrides are required to use Irmin.

```bash
# Create once:
cat > .env.irmin <<'EOF'
REGISTER_REPOSITORY_TYPE=irmin
IRMIN_URL=http://irmin:8080
EOF

docker compose \
  --profile persistence \
  --profile frontend \
  --env-file .env.irmin \
  up -d
```

Access: **http://localhost:18080** | Irmin GraphiQL: **http://localhost:9080/graphql**

```bash
# Teardown — keep Irmin data
docker compose --profile persistence --profile frontend down

# Teardown — destroy Irmin data volume too
docker compose --profile persistence --profile frontend down -v
```

### Use case C+: Full stack + observability

Adds the OpenTelemetry Collector to use case C. The collector receives OTLP traces from
register-server and exposes Prometheus metrics on port 8889.

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  --profile observability \
  --env-file .env.irmin \
  up -d
```

```bash
curl http://localhost:8889/metrics    # Prometheus metrics
```

```bash
# Teardown
docker compose \
  --profile persistence \
  --profile frontend \
  --profile observability \
  down
```

---

## Integration Tests (serverIt)

`IrminCompose` auto-starts and stops a scoped Irmin container per test run — no manual
management needed. Each run uses a unique compose project (`register_it_<uuid>`) for isolation.

**Prerequisite:** `local/irmin-prod:3.11` must exist (step 2 in Build from Scratch above).

```bash
# Run all integration tests
sbt "serverIt/test"

# Run a specific suite
sbt "serverIt/testOnly *RiskTreeRepositoryIrminSpec"
sbt "serverIt/testOnly *HttpApiIntegrationSpec"
```

### Network address space exhaustion

Each test run creates a Docker network (`register_it_<uuid>_default`). `IrminCompose` cleans
up on normal completion, but interrupted runs (Ctrl+C, OOM, crash) leak networks. After
enough leaks, Docker exhausts its `/24` subnet pool and new `up` calls fail.

```bash
# Remove leaked integration test containers
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f

# Remove leaked integration test networks
docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm
```

---

## Rebuild Images

| Image | When to rebuild | Command | Details |
|-------|-----------------|---------|---------|
| `local/graalvm-builder:21` | vql-engine changes, GraalVM/sbt version bump | `docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 ..` | [Builder base](#one-time-setup-builder-base-image) |
| `local/register-server:<version>` | Server or common source changes | `docker build -f containers/prod/Dockerfile.register-prod -t local/register-server:<version> .` | [Register server](#standalone-docker) |
| `local/frontend:<version>` | Frontend or common source changes | `docker build -f containers/prod/Dockerfile.frontend-prod -t local/frontend:<version> ..` | [Frontend SPA](#standalone-docker-1) |
| `local/irmin-prod:3.11` | Irmin version changes | `docker build -f containers/prod/Dockerfile.irmin-prod -t local/irmin-prod:3.11 containers/prod/` | [Irmin server](#irmin-graphql-server-persistence-layer) |
| `local/irmin-builder:3.11` | OCaml/Irmin version changes | `docker build -f containers/builders/Dockerfile.irmin-builder -t local/irmin-builder:3.11 containers/builders/` | [Irmin builder](#one-time-setup-irmin-builder-base-image) |

**After server source changes** (vql-engine unchanged):
```bash
docker build -f containers/prod/Dockerfile.register-prod -t local/register-server:<version> . \
  && docker compose up -d register-server
```

**After vql-engine changes** (rebuild graalvm-builder first):
```bash
docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 .. \
  && docker build -f containers/prod/Dockerfile.register-prod -t local/register-server:<version> . \
  && docker compose up -d register-server
```

---

## Configuration — Environment Variables

Configure via `docker-compose.yml`, `.env` file, or inline overrides.

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

---

## Troubleshooting

### Stale container (container name already in use)

`docker compose up` uses fixed container names (`irmin-graphql`, `register-server`, `frontend`).
If a previous run was killed without `docker compose down`, the named container still exists
and blocks a fresh `up`. Always stop with `docker compose down`, not `docker stop` or Ctrl+C.

Manual fix after a hard kill:

```bash
docker rm -f irmin-graphql    # adjust name as needed
docker compose --profile persistence --profile frontend --env-file .env.irmin up -d
```

### Health checks

```bash
curl http://localhost:8090/health          # register-server API
curl http://localhost:8091/health          # register-server health probe
curl http://localhost:8091/ready           # register-server readiness probe
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __typename }"}'          # Irmin GraphQL
curl -s http://localhost:18080/ | grep -q '<html' && echo OK   # nginx frontend
```

### Irmin data volume

```bash
# List
docker volume ls | grep irmin

# Backup
docker run --rm -v register_irmin-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/irmin-backup.tar.gz -C /data .

# Restore
docker run --rm -v register_irmin-data:/data -v $(pwd):/backup \
  alpine tar xzf /backup/irmin-backup.tar.gz -C /data

# Destroy (IRREVERSIBLE)
docker volume rm register_irmin-data
```

### Logs

```bash
docker compose logs -f                     # all services
docker compose logs -f register-server     # server only
docker compose logs -f irmin               # Irmin only
```

---

## Appendix: Vite vs nginx and CORS

| | Vite dev mode | nginx prod mode |
|---|---|---|
| Frontend port | 5173 | 18080 |
| API calls | Browser → 8090 directly (cross-origin) | Browser → 18080 → nginx → 8090 (same-origin) |
| CORS | Required — browser enforces it | Not involved — nginx proxies internally |
| Frontend updates | HMR on every Scala.js save | Requires image rebuild + `up -d` |
| Use for | Day-to-day development | Testing nginx routing, security headers, prod parity |

**Why CORS doesn't apply in nginx mode:** the browser only ever talks to port 18080. nginx
proxies API calls on the Docker network internally. No cross-origin request reaches the browser,
so CORS is not triggered and `REGISTER_CORS_ORIGINS` is irrelevant for that mode.

**Remote backend (Vite only):** if the Docker cluster runs on a different machine (e.g. a VM
at `192.168.1.50`) while your browser loads Vite from `192.168.1.100:5173`, the browser sees
a different origin and the backend will reject the request. Add the Vite origin explicitly:

```bash
REGISTER_CORS_ORIGINS=http://192.168.1.100:5173 docker compose up -d register-server
```

This does not apply to nginx mode.

