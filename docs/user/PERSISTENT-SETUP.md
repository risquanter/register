# Risquanter Register — Persistent Setup (Irmin)

This guide covers enabling the Irmin-backed persistence layer in Register. With Irmin active, every change to a risk tree is stored as an immutable commit in a Git-like content-addressed store, making all historical states queryable and auditable.

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

## 2. Configure Irmin environment

```bash
cp .env.irmin.example .env.irmin
# Review .env.irmin — REGISTER_REPOSITORY_TYPE is set to irmin
```

## 3. Start the full stack

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  --env-file .env.irmin \
  up -d
```

This starts:
- **Irmin** (port 9080) — content-addressed persistence layer
- **register-server** (port 8090 API, 8091 health probes) — the simulation backend
- **nginx** (port 18080) — serves the compiled SPA and proxies API calls

Access the application at **`http://localhost:18080`**. See [Using Register](../README.md#using-register) in the README for a walkthrough of the Design and Analyze views.

## Configuration

All configuration is driven by environment variables. The key variables with their defaults:

| Variable | Default | Description |
|---|---|---|
| `REGISTER_REPOSITORY_TYPE` | `in-memory` | Set to `irmin` for persistent storage |
| `IRMIN_URL` | `http://localhost:9080` | Irmin GraphQL endpoint. Use `http://irmin:8080` inside Docker Compose (set by `.env.irmin.example`). |
| `REGISTER_AUTH_MODE` | `capability-only` | Authorization layer: `capability-only`, `identity`, or `fine-grained` |
| `REGISTER_DEFAULT_NTRIALS` | `10000` | Monte Carlo trial count |
| `REGISTER_WORKSPACE_TTL` | `72h` | Workspace absolute expiry |
| `REGISTER_WORKSPACE_IDLE_TIMEOUT` | `1h` | Workspace idle expiry |
| `REGISTER_CORS_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed origins |

For a full listing of all supported variables see [DOCKER-DEVELOPMENT.md](DOCKER-DEVELOPMENT.md).
