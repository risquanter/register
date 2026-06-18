# Risquanter Register — Development Setup

This guide covers the local development workflow for contributors modifying Register source code. The primary loop pairs the backend running in Docker with Scala.js watch compilation and Vite HMR for the frontend.

## Prerequisites

| Requirement | Detail |
|---|---|
| Repository cloned | All three sibling repos (`register/`, `vague-quantifier-logic/`, `hdr-rng/`) must be cloned side-by-side. See [Getting Started](../README.md#getting-started-in-memory-storage) for clone commands. |
| JDK 21 | Required by `sbt`. |
| sbt | Scala Build Tool — handles backend compilation, Scala.js, and native-image builds. |
| Node.js 18+ and npm | Required for the Scala.js / Vite frontend. |
| Docker 20.10+ and Docker Compose 2.0+ | Backend and persistence services run in containers during development. |

For containerized production-equivalent deployment (nginx, full stack, Kubernetes) see [DOCKER-DEVELOPMENT.md](DOCKER-DEVELOPMENT.md).

---

## Local environment templates

Use one of the checked-in env templates, copy it to a local file, and adjust values for your machine before starting services:

```bash
# In-memory backend (default local workflow)
cp .env.inmemory.example .env.inmemory

# Irmin-backed backend (persistent local workflow)
cp .env.irmin.example .env.irmin
```

Template files:
- `.env.inmemory.example` → sets `REGISTER_REPOSITORY_TYPE=in-memory`
- `.env.irmin.example` → sets `REGISTER_REPOSITORY_TYPE=irmin` and `IRMIN_URL=http://irmin:8080`

Run Compose with the selected env file:

```bash
# In-memory
docker compose --env-file .env.inmemory up -d register-server

# Irmin + PostgreSQL persistence services
docker compose --env-file .env.irmin --profile persistence up -d register-server irmin postgres
```

You can additionally set local values such as `REGISTER_CORS_ORIGINS` in these files when running the frontend from a non-default host/origin.

---

## Backend (JVM, watch mode)

The fastest local development loop runs the backend container from a pre-built production image while recompiling the frontend with Vite HMR:

```bash
# Terminal 1 — start the backend (in-memory storage, no Irmin needed)
docker compose up -d register-server

# Terminal 2 — Scala.js watch compiler
sbt '~app/fastLinkJS'

# Terminal 3 — Vite dev server with HMR
cd modules/app && npm run dev
```

Access at **`http://localhost:5173`**.

Note that in Vite mode the browser makes API calls directly to `http://localhost:8090` (two origins), so CORS is enforced. The default `REGISTER_CORS_ORIGINS` already includes `localhost:5173`. If your backend runs on a remote machine, export the Vite origin explicitly before starting the server:

```bash
REGISTER_CORS_ORIGINS=http://<your-machine>:5173 docker compose up -d register-server
```

---

## With Irmin persistence

To use the persistent Irmin backend during development, first build the Irmin images (see [PERSISTENT-SETUP.md](PERSISTENT-SETUP.md)), then:

```bash
docker compose --profile persistence up -d register-server irmin postgres
```

To enable the PostgreSQL-backed workspace store as well, export:

```bash
export REGISTER_WORKSPACE_STORE_BACKEND=postgres
```

---

## Running tests

```bash
# All tests — unit + integration (requires Docker, local/irmin-prod:3.11 image)
sbt 'commonJVM/test; server/test; app/test; serverIt/test'

# Unit tests only (no Docker required)
sbt 'commonJVM/test; server/test; app/test'

# Integration tests only
sbt serverIt/test
```

Integration tests (`serverIt`) spin up isolated Irmin containers using
`docker-compose.server-it.yml` with dynamic host ports — multiple specs run
concurrently without port conflicts with the dev stack.

---

## Full prod-equivalent stack locally (nginx)

```bash
docker compose \
  --profile persistence \
  --profile frontend \
  up -d

# Nginx serves the SPA and proxies API — no CORS, no Vite
open http://localhost:18080
```
