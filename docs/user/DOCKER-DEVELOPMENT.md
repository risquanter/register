# Docker & Development Guide

## Prerequisites

- Docker 20.10+, Docker Compose 2.0+
- JDK 21, sbt (for local Scala / sbt development)
- Node.js 18+ and npm (for Vite dev server only)

---

## How images are built

All images are built locally from source. There are two distinct tiers:

**Builder bases** — `local/graalvm-builder:21` and `local/irmin-builder:3.11` — install
heavyweight toolchains (GraalVM/sbt, OCaml/opam) once and are reused across all application
builds. You build these manually; they rarely change. See
[ADR-026](../dev/ADR-026-container-image-strategy.md) for the image strategy and
[ADR-020](../dev/ADR-020-supply-chain-security.md) for supply chain security policy.

**Application images** — `local/register-server`, `local/frontend`, `local/irmin-prod` — are
built automatically by `docker compose up`. The compose file uses `pull_policy: build` for
these services, which means every `docker compose up` rebuilds them from the Dockerfile using
Docker layer cache. You do not need to pre-build them; they are always up to date with the
current source.

**Versioning:** The `.env` file at the project root sets `APP_VERSION`, which compose uses to
tag application images. After a version bump in `build.sbt`, sync `.env`:

```bash
sed -n 's/ThisBuild \/ version := "\(.*\)"/APP_VERSION=\1/p' build.sbt > .env
```

For local development, `.env` is optional — compose falls back to the `dev` tag when absent.
The file is needed when version tags must match a `build.sbt` bump, and in CI.
If you add or update `.env` after images were already built without it, run
`docker compose build` again before `docker compose up` — otherwise compose will look for the
versioned tag (e.g. `:0.1.1`) while only the `:dev` image exists locally.

---

## One-time setup: Builder base images

Build these once after cloning. Rebuild only when the toolchain version changes — see
[Image Build Reference](IMAGE-BUILD-REFERENCE.md) for the full command reference and rebuild
triggers.

```bash
# GraalVM builder base — GraalVM native-image + sbt (~10-20 min, once)
# Context is the parent directory — sibling repos vague-quantifier-logic/ and hdr-rng/
# must be present at ../
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 ..
```

For Irmin persistence, the Irmin builder base is also required. See
[Persistent Setup](PERSISTENT-SETUP.md).

---

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

### Use case C: Full stack — register + Irmin + Postgres persistence + nginx frontend

Register-server defaults to in-memory storage for both risk trees and workspace
metadata. The `.env.irmin` overrides switch it onto the persistent backends.
A Compose profile only decides which containers start — it cannot change the
server's environment — so `--profile persistence` alone leaves the server on its
in-memory defaults; the `--env-file` is what actually enables persistence.

```bash
# Create once from the tracked template (Irmin trees + Postgres workspaces +
# extended workspace expiry):
cp .env.irmin.example .env.irmin

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

## Frontend Modes: Vite vs nginx

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

---

## Integration Tests (serverIt)

`IrminCompose` auto-starts and stops a scoped Irmin container per test run — no manual
management needed. Each run uses a unique compose project (`register_it_<uuid>`) for isolation.

**Prerequisite:** `local/irmin-prod:3.11` must exist. Build it with the commands in
[Image Build Reference](IMAGE-BUILD-REFERENCE.md) if you have not already.

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
| `REGISTER_REPOSITORY_TYPE` | `in-memory` | Set to `irmin` to enable Irmin backend. Read by **both** `register-server` and `frontend` (see `/config.json` below) — one variable, one source of truth, so the two containers can never disagree on whether scenarios are available. |
| `IRMIN_URL` | `http://localhost:9080` | Irmin GraphQL URL (use `http://irmin:8080` inside compose) |
| `IRMIN_BRANCH` | `main` | Irmin default branch |
| `IRMIN_TIMEOUT` | `30s` | Irmin request timeout (HOCON duration, e.g. `30s`, `2m`) |
| `IRMIN_HEALTHCHECK_ATTEMPT_TIMEOUT` | `5s` | Startup readiness: per-attempt probe timeout (ADR-031) |
| `IRMIN_HEALTHCHECK_BUDGET` | `45s` | Startup readiness: total bounded wait before failing closed (ADR-031) |
| `BACKEND_URL` | `http://register-server:8090` | **Frontend (nginx) only** — register-server URL for proxy_pass |

### `/config.json` — frontend capability discovery

The `frontend` container's entrypoint script writes `/tmp/config.json` at startup
(served by nginx at `GET /config.json`, `Cache-Control: no-cache`) from the same
`REGISTER_REPOSITORY_TYPE` variable `register-server` reads:

```json
{"scenariosEnabled": true}
```

`true` when `REGISTER_REPOSITORY_TYPE=irmin`, `false` otherwise. This lets the SPA
know whether scenario branching is available without an API round trip, and without
the two containers being configured to disagree — set the variable once, both
containers read it. The backend's own `ScenarioServiceNotSupported` stub (501 on
`repository.type=in-memory`) is what actually enforces this; `/config.json` only
controls whether the SPA shows the UI for it (milestone-2b Phase B item 6). No SPA
code consumes it yet — there is no scenario UI to gate until item 9 (BranchBar).

Kubernetes: set `REGISTER_REPOSITORY_TYPE` on both the `register-server` and
`frontend` pod specs from the same source (e.g. the same ConfigMap key).

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
curl -s http://localhost:18080/config.json                     # {"scenariosEnabled": true|false}
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

## See also

- [Image Build Reference](IMAGE-BUILD-REFERENCE.md) — complete build command reference,
  rebuild triggers, and CI workflow
- [Persistent Setup](PERSISTENT-SETUP.md) — Irmin-backed persistence setup

