---
name: register-dev
description: "Dev workflow for the register project. Use for: compiling Scala/SBT modules, running unit/integration tests, Scala.js frontend builds, Vite dev server, Docker Compose stack management, container image builds, BATS smoke tests, health checks, leaked-network cleanup, sbt commands, fastLinkJS, fullLinkJS, docker compose up, docker compose down."
user-invocable: false
---

# Register Dev Workflow

## Module Map

| SBT project | Language | Path |
|-------------|----------|------|
| `commonJVM` | Scala 3 JVM | `modules/common/` |
| `commonJS`  | Scala.js | `modules/common/` |
| `server`    | Scala 3 JVM | `modules/server/` |
| `serverIt`  | Scala 3 JVM | `modules/server-it/` |
| `app`       | Scala.js (Laminar) | `modules/app/` |

`server` and `serverIt` depend on `commonJVM`. `app` depends on `commonJS`.

---

## Compilation

```bash
# All modules
sbt compile

# Single module
sbt commonJVM/compile
sbt server/compile
sbt app/compile

# Incremental watch (re-compiles on save)
sbt ~server/compile
sbt ~app/compile
```

---

## Unit Tests

Run from the project root. No Docker required for these.

```bash
# All unit tests (common + server; excludes serverIt)
sbt 'commonJVM/test; server/test'

# Per-module
sbt commonJVM/test
sbt server/test

# Scala.js app tests
sbt app/test

# Single suite
sbt "server/testOnly *SimulationSemaphoreSpec"
sbt "commonJVM/testOnly *RiskLeafSpec"
sbt "app/testOnly *TreeBuilderStateSpec"

# With filtered output (pass/fail summary)
sbt 'commonJVM/test; server/test' 2>&1 | \
  grep -E 'tests passed|tests failed|FAILED|\[error\]|success|Executed in'
```

Expected counts (as of 2026-03-09): `commonJVM` 289, `server` 219.

---

## Integration Tests (serverIt)

Requires `local/irmin-prod:3.11` Docker image (built once — see Image Builds).
`IrminCompose` starts/stops a scoped Irmin container automatically per run.

```bash
# All integration tests
sbt "serverIt/test"

# Single suite
sbt "serverIt/testOnly *RiskTreeRepositoryIrminSpec"
sbt "serverIt/testOnly *HttpApiIntegrationSpec"

# Filtered output
sbt 'serverIt/test' 2>&1 | \
  grep -E 'tests passed|tests failed|FAILED|\[error\]|success|Executed in' | head -40
```

### Leaked network cleanup (after Ctrl+C / crash)

```bash
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f
docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm
```

---

## Scala.js Builds

```bash
# Fast link — development (faster, no optimisation)
sbt app/fastLinkJS

# Full link — production (optimised, larger)
sbt app/fullLinkJS

# Watch mode for frontend development
sbt ~app/fastLinkJS
```

Output lands in `modules/app/target/scala-3.7.4/`.

---

## Frontend Dev Server (Vite + HMR)

Requires a running backend. Use alongside `sbt ~app/fastLinkJS` for HMR.

```bash
# Terminal 1 — watch compiler
sbt ~app/fastLinkJS

# Terminal 2 — Vite dev server
cd modules/app && npm run dev
```

Access: **http://localhost:5173**

Build production frontend bundle (used by Docker):
```bash
cd modules/app && npm run build
```

---

## Docker Compose — Dev Use Cases

Always stop with `docker compose down`, not `docker stop` or Ctrl+C.
If a previous run was hard-killed: `docker rm -f <container-name>`

### Use case A — Backend in Docker + Vite dev server (day-to-day frontend dev)

```bash
# Terminal 1
docker compose up -d register-server

# Terminal 2
sbt ~app/fastLinkJS

# Terminal 3
cd modules/app && npm run dev
```

Access: **http://localhost:5173** | Teardown: `docker compose down`

### Use case B — Backend + nginx frontend, no Vite (test nginx routing)

```bash
docker compose --profile frontend up -d
```

Access: **http://localhost:18080** | Teardown: `docker compose --profile frontend down`

### Use case C — Full stack with Irmin persistence + nginx

```bash
# Create once
cat > .env.irmin <<'EOF'
REGISTER_REPOSITORY_TYPE=irmin
IRMIN_URL=http://irmin:8080
EOF

docker compose --profile persistence --profile frontend --env-file .env.irmin up -d
```

Access: **http://localhost:18080** | Irmin GraphiQL: **http://localhost:9080/graphql**

```bash
# Teardown (keep data)
docker compose --profile persistence --profile frontend down

# Teardown (destroy Irmin data volume)
docker compose --profile persistence --profile frontend down -v
```

### Use case C+ — Full stack + OpenTelemetry observability

```bash
docker compose \
  --profile persistence --profile frontend --profile observability \
  --env-file .env.irmin up -d

curl http://localhost:8889/metrics    # Prometheus metrics

# Teardown
docker compose --profile persistence --profile frontend --profile observability down
```

---

## Container Image Builds

Build order: steps 1, 3, 4 are independent; step 2 requires step 1; step 5 requires step 3.

```bash
# 1. Irmin builder base (~15-40 min first run; rebuild only on Irmin/OCaml version change)
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 containers/builders/

# 2. Irmin production image (~10s; requires step 1)
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11 containers/prod/

# 3. GraalVM builder base (~1-2 min; context is parent dir — vql-engine must be in scope)
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 ..

# 4. Frontend SPA (~10-15 min first run; context is parent dir)
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:<version> ..

# 5. Register server native binary (~5-10 min; requires step 3)
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:<version> .
```

**After server source changes** (vql-engine unchanged):
```bash
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:<version> . \
  && docker compose up -d register-server
```

**After vql-engine changes** (rebuild graalvm-builder first):
```bash
docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 .. \
  && docker build -f containers/prod/Dockerfile.register-prod \
     -t local/register-server:<version> . \
  && docker compose up -d register-server
```

---

## BATS Smoke Tests

Requires pre-built production images and the `local/bats-runner:1.11` image.

```bash
# Build BATS runner (once)
docker build -f containers/dev/Dockerfile.bats-runner \
  -t local/bats-runner:1.11 containers/dev/

# Suite C — in-memory, quickest; run after any code change
docker run --rm --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":/workspace -w /workspace \
  local/bats-runner:1.11 tests/bats/suite-c-in-memory.bats

# Suite A — E2E with Irmin persistence
docker run --rm --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":/workspace -w /workspace \
  local/bats-runner:1.11 tests/bats/suite-a-full-prod.bats

# Suite B — standalone Irmin image
docker run --rm --network host \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":/workspace -w /workspace \
  local/bats-runner:1.11 tests/bats/suite-b-irmin-prod.bats
```

| Scenario | Suite |
|----------|-------|
| After code change, fast gate | C |
| Server ↔ Irmin integration | A |
| Irmin image / Dockerfile change | B then A |
| Full release validation | A + B + C |

---

## Health Checks

```bash
curl http://localhost:8090/health         # register-server API
curl http://localhost:8091/health         # register-server health probe
curl http://localhost:8091/ready          # register-server readiness
curl -s -X POST http://localhost:9080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __typename }"}' | jq .  # Irmin GraphQL
curl -s http://localhost:18080/ | grep -q '<html' && echo OK  # nginx frontend
```

---

## Logs

```bash
docker compose logs -f                   # all services
docker compose logs -f register-server
docker compose logs -f irmin
```

---

## SBT Interactive Shell (fastest for repeated tasks)

```bash
sbt
# then inside the shell:
# > ~app/fastLinkJS
# > server/test
# > "serverIt/testOnly *IrminSpec"
# > reload   (after build.sbt changes)
# > clean    (when incremental compilation misbehaves)
```

---

## Reference

- Docker & Development guide: [docs/user/DOCKER-DEVELOPMENT.md](../../../docs/user/DOCKER-DEVELOPMENT.md)
- Testing guide: [docs/test/TESTING.md](../../../docs/test/TESTING.md)
