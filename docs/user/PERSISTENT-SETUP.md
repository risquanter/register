# Risquanter Register — Persistent Setup (Irmin + PostgreSQL)

This guide covers enabling the full persistence layer in Register: **Irmin** for
risk-tree data (every change stored as an immutable commit in a Git-like
content-addressed store, making all historical states queryable and auditable)
and **PostgreSQL** for workspace metadata (so workspaces survive a server
restart instead of living in an in-memory map). Both are enabled together by the
`.env.irmin` file below — enabling only Irmin leaves workspaces in-memory, so
their trees would persist but the workspace pointing at them would not.

## Prerequisites

| Requirement | Detail |
|---|---|
| In-memory setup complete | Follow [Getting Started (in-memory storage)](../README.md#getting-started-in-memory-storage) first. This step builds the GraalVM builder base and the Register server image — the Irmin stack reuses both. |
| Docker 20.10+ and Docker Compose 2.0+ | Required for all container builds and Compose operations. |
| Build time | The Irmin builder downloads and compiles the OCaml toolchain from source. **Allow 15–40 minutes on the first run.** The resulting image is cached locally and survives `docker builder prune`. |
| Sibling repos | `vague-quantifier-logic/` and `hdr-rng/` must be present as siblings of `register/` at `../` — the same requirement as the in-memory setup. |

For the full environment variable reference, observability integration, and Kubernetes deployment options see [DOCKER-DEVELOPMENT.md](DOCKER-DEVELOPMENT.md).

---

## 1. Build the Irmin base images

The Irmin builder compiles the OCaml toolchain from source — allow 15–40 minutes on first run. The resulting images are cached locally and survive `docker builder prune`.

```bash
# Irmin builder base — OCaml toolchain + opam packages (~15-40 min, once)
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 \
  containers/builders/

# Irmin server (~10s, requires builder base above)
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11 \
  containers/prod/
```

## 2. Configure the persistence environment

```bash
cp .env.irmin.example .env.irmin
# Review .env.irmin — it sets both backends (Irmin + Postgres) and extends
# the workspace expiry so nothing is reaped during normal use.
```

`.env.irmin` sets four variables: `REGISTER_REPOSITORY_TYPE=irmin` and
`IRMIN_URL` (risk-tree store), `REGISTER_WORKSPACE_STORE_BACKEND=postgres`
(workspace store), and extended `REGISTER_WORKSPACE_TTL` /
`REGISTER_WORKSPACE_IDLE_TIMEOUT`. These override the in-memory defaults baked
into `docker-compose.yml` via `${VAR}` interpolation — `--env-file` reaches the
container only for variables the compose `environment:` block references, which
all four now are.

## 3. Start the full stack

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  --env-file .env.irmin \
  up -d
```

`--profile persistence` starts **both** the `irmin` and `postgres` containers.
This starts:
- **Irmin** (port 9080) — content-addressed risk-tree store
- **PostgreSQL** (port 5432) — durable workspace metadata (Flyway migrates the schema on server startup)
- **register-server** (port 8090 API, 8091 health probes) — the simulation backend
- **nginx** (port 18080) — serves the compiled SPA and proxies API calls

> **Why `--env-file` and not `--profile persistence` alone:** a Compose profile
> only decides which containers *start*; it cannot change another service's
> environment. Without the env-file, the `postgres` and `irmin` containers would
> run but `register-server` would still read its in-memory defaults and use
> neither. The `.env.irmin` file supplies the overrides that switch the server
> onto the persistent backends.

Access the application at **`http://localhost:18080`**. See [Using Register](../README.md#using-register) in the README for a walkthrough of the Design and Analyze views.

## Configuration

All configuration is driven by environment variables. The key variables with their defaults:

| Variable | Default | Description |
|---|---|---|
| `REGISTER_REPOSITORY_TYPE` | `in-memory` | Set to `irmin` for persistent risk-tree storage (set by `.env.irmin`) |
| `IRMIN_URL` | `http://localhost:9080` | Irmin GraphQL endpoint. Use `http://irmin:8080` inside Docker Compose (set by `.env.irmin`). |
| `REGISTER_WORKSPACE_STORE_BACKEND` | `in-memory` | Set to `postgres` for durable workspace metadata (set by `.env.irmin`) |
| `REGISTER_AUTH_MODE` | `capability-only` | Authorization layer: `capability-only`, `identity`, or `fine-grained` |
| `REGISTER_DEFAULT_NTRIALS` | `10000` | Monte Carlo trial count |
| `REGISTER_WORKSPACE_TTL` | `72h` | Workspace absolute expiry (`.env.irmin` sets `120h`; `0` disables) |
| `REGISTER_WORKSPACE_IDLE_TIMEOUT` | `1h` | Workspace idle expiry (`.env.irmin` sets `120h`; `0` disables) |
| `REGISTER_CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed origins |

For a full listing of all supported variables see [DOCKER-DEVELOPMENT.md](DOCKER-DEVELOPMENT.md).
