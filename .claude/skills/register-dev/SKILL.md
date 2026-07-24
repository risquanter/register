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
  grep -E 'tests failed|FAILED|\[error\]|success'
```

### Reporting test results

Run tests. Report **pass or fail only**. Never report the count. Never comment on the count. Never act on the count. The count is a distraction and irrelevant to the user. The user only needs to know if the tests passed or failed. Failed test need to be investigated even if they are from earlier. A single failure is a blocker regardless of the count. The user needs to know about it and fix it before proceeding.

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

---

## Integration Tests (serverIt)

Requires `local/irmin-prod:3.11` Docker image (built once — see Image Builds).
`IrminCompose` starts/stops a scoped Irmin container automatically per run using
`docker-compose.server-it.yml` (dynamic host port — multiple specs run concurrently
without port conflicts).

```bash
# All integration tests (runs all specs concurrently — safe)
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

### npm dependencies — hard rules (ADR-020)

**Never run `npm install`/`npm update` without explicit prior authorization
from the user** — this applies even to repair a broken `node_modules`
(missing binary, corrupted install). Ask first; do not install to unblock
yourself.

**npm silently auto-installs required peerDependencies (v7+ default
behaviour) — this bit the project once.** `geist` declares `next` as a
required peer; a plain `npm install` pulled in `next@16.2.2` and its `sharp`
dependency — ~46 packages, two of them with real high-severity CVEs — none
of it declared in `package.json`, none of it used by the app. Fixed via
`legacy-peer-deps=true` in `.npmrc` (ADR-020 §7). If a future `npm install`
in this project ever reports a large, unexpected package count change, this
is the first thing to suspect — check for a new/changed dependency with a
peerDependency the tree doesn't already satisfy.

**When install/update work is explicitly authorized, follow ADR-020 §8,
not a plain `npm install`:**
```bash
cd modules/app
npm install --package-lock-only   # resolve only — no node_modules writes, no scripts
npm audit                         # check the resolved lockfile before installing anything
# only if clean (or after fixing via version bump / package.json "overrides"):
npm install                       # or `npm ci` in CI/Docker — syncs node_modules
npm audit                         # confirm again post-install
npm audit signatures              # registry signature + Sigstore provenance check
```

**Tool version is pinned and enforced, not just the packages.**
`package.json`'s `"engines"` field (`npm >=10.9.8`, `node >=20.5.0`) +
`engine-strict=true` in `.npmrc` make `npm install`/`npm ci` refuse to run
on an older npm/Node — verified 2026-07-21 by deliberately setting an
unsatisfiable `engines.npm` and confirming `EBADENGINE` fires. `npm audit
signatures` requires npm ≥9.5.0 for Sigstore provenance; this machine's
system npm was upgraded (`sudo npm install -g npm@10.9.8`) specifically to
clear that floor — confirmed working: 98/98 packages verified registry
signatures, 10 packages verified Sigstore attestations.

**Global npm config mirrors the same hardening** (`~/.npmrc`, not just this
project's) — `ignore-scripts`, `save-exact`, `legacy-peer-deps` apply by
default to every npm project on this machine, not only `register`.
`engine-strict` is project-scoped only (it depends on each project's own
`engines` field, which most other projects won't declare).

Full incident writeup, the `npm ci` vs `npm install` distinction for
`Dockerfile.frontend-prod`, and Sigstore/`npm audit signatures` status:
`docs/dev/ADR-020-supply-chain-security.md` §7–§9. Prerequisites and the
same command sequence for new contributors:
`docs/user/DEVELOPMENT-SETUP.md`.

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

## Versioning

`build.sbt` (`ThisBuild / version`) is the single source of truth. **Both** `.env` and
`.env.irmin` must be kept in sync — Docker Compose reads `APP_VERSION` to tag images,
and `--env-file .env.irmin` replaces `.env`, so each file needs its own copy.

### When to bump

| Change type | Bump |
|-------------|------|
| Task/step completed in an approved plan; bug fix; security fix — when shipped code changed | PATCH `x.y.Z` |
| Plan closed (feature level); external API change (while < 1.0.0) | MINOR `x.Y.0` |
| External API change (once ≥ 1.0.0); user-declared case or pre-declared milestone | MAJOR `X.0.0` |
| Docs-only, test-only, skill/tooling-only changes (no shipped code) | **No bump** |

**Ownership:** PATCH and MINOR bumps are autonomous — apply them as part of landing the
qualifying work. MAJOR bumps are **user-owned**: never bump autonomously; the user
performs the bump or pre-declares it (e.g., a note at the end of a plan document
naming the milestone). Dependency changes and supply-chain rules: supply-chain skill.

### Bump procedure

```bash
# 1. Edit build.sbt — change ThisBuild / version := "x.y.z"
#    (build.sbt is hook-gated — requires the user-refreshed approval token)

# 2. Sync .env (single-line file, safe to regenerate)
sed -n 's/ThisBuild \/ version[[:space:]]*:= "\(.*\)"/APP_VERSION=\1/p' build.sbt > .env

# 3. Sync .env.irmin (multi-line file — replace only the APP_VERSION line)
sed -i "s/^APP_VERSION=.*/$(cat .env)/" .env.irmin

# 4. Verify
grep APP_VERSION .env .env.irmin   # both must print APP_VERSION=x.y.z
```

After this, `docker compose up` tags all application images with the new version.
Full map of what a bump or dependency change triggers: `docs/dev/VERSION-UPGRADE-PROTOCOL.md`.

---

## Container Image Builds

For day-to-day development, `docker compose up` builds application images automatically
(`pull_policy: build`). The explicit commands below are needed for one-time builder base
setup and CI. See `docs/user/IMAGE-BUILD-REFERENCE.md` for the full reference.

Builder bases are independent of each other; app images require the corresponding builder.

```bash
# GraalVM builder base (~1-2 min; context is parent dir — vql-engine must be in scope)
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 ..

# Irmin builder base (~15-40 min first run; rebuild only on Irmin/OCaml version change)
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11 containers/builders/

# Irmin production image (~10s; requires irmin-builder)
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11 containers/prod/

# Frontend SPA (~10-15 min first run; context is parent dir)
source .env
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:${APP_VERSION} ..

# Register server native binary (~5-10 min; requires graalvm-builder)
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:${APP_VERSION} .
```

**After server source changes** (vql-engine unchanged):
```bash
docker compose up -d register-server   # compose rebuilds via pull_policy: build
```

**After vql-engine changes** (rebuild graalvm-builder first):
```bash
docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 .. \
  && docker compose up -d register-server
```

---

## BATS Smoke Tests

Requires pre-built production images and the `local/bats-runner:1.11` image.

```bash
# Build BATS runner (once)
docker build -f containers/dev/Dockerfile.bats-runner \
  -t local/bats-runner:1.11 containers/dev/

# All suites share one invocation — only the .bats file changes.
run_bats() {
  docker run --rm --userns=host --network host \
    --group-add "$(stat -c '%g' /var/run/docker.sock)" \
    -e HOME=/tmp \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$(dirname "$(pwd)")":"$(dirname "$(pwd)")" \
    -w "$(pwd)" \
    local/bats-runner:1.11 "$1"
}

run_bats tests/bats/suite-c-in-memory.bats   # Suite C — in-memory, quickest; run after any code change
run_bats tests/bats/suite-a-full-prod.bats   # Suite A — E2E with Irmin persistence
run_bats tests/bats/suite-b-irmin-prod.bats  # Suite B — standalone Irmin image
```

Why each flag (the runner drives the **host's** Docker daemon from inside a
container, and this machine's daemon runs with user-namespace remapping):

- `--userns=host` — the remapped daemon refuses `--network host` otherwise;
  no-op on daemons without remapping, so the command is portable.
- `--group-add $(stat -c '%g' /var/run/docker.sock)` — puts the container
  user in the socket's group; without it every Docker call is permission-denied.
- `-e HOME=/tmp` — the Docker CLI writes `$HOME/.docker`; the image's baked-in
  home is not writable under the remap.
- Mount the repo's **parent** directory at its **identical host path** (not
  `/workspace`) — compose build contexts (`..` for frontend/builder images)
  are resolved by the host daemon, so every path the runner passes must mean
  the same thing on the host. A `/workspace` mount fails with errors like
  `lstat /register: no such file or directory`.

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
