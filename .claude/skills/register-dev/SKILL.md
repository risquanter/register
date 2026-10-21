---
name: register-dev
description: "Dev workflow for the register project. Use for: compiling Scala/SBT modules, running unit/integration tests, Scala.js frontend builds, Vite dev server, Docker Compose stack management, container image builds, BATS smoke tests, health checks, leaked Docker state cleanup, sbt commands, fastLinkJS, fullLinkJS, docker compose up, docker compose down."
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

### A compiler `StackOverflowError` is incremental state, not a defect in the code

An incremental compile of a module that derives JSON codecs or Tapir schemas can
fail with a `java.lang.StackOverflowError` thrown by the compiler itself.

The symptom is what makes it costly, because it reads as a defect in the code
being compiled. A stack overflow inside derivation looks like a type that
recurses with no base case, so the response it invites is redesigning the type.
Nothing in the message distinguishes the two.

The cause is the incremental compilation state rather than the sources. That is
what the difference between a dirty and a clean run of the same module shows,
and it is the whole diagnosis — clean one module and compile it again:

```bash
sbt 'commonJVM/clean; commonJVM/compile'
sbt 'commonJS/clean; commonJS/compile'
sbt 'server/clean; server/compile'
sbt 'app/clean; app/compile'
```

A clean compile that succeeds on unchanged sources is the proof that the sources
were never the problem. Run it before changing anything, and do not count it
against the two-attempt limit in the red-build rule below: it is a diagnosis,
not a second attempt at the same fix.

The module is cleaned on its own rather than the whole build, because a
build-wide `clean` discards every other module's incremental state as well and
buys a full rebuild for nothing. Clean the module the error names, which is not
always the one being compiled: a `serverIt` or `app` compile fails this way on
`commonJVM` or `commonJS` state, because those are what it depends on.

It occurs while deriving schemas in `common`, on both platforms, and a clean
compile of the module the error names clears it.

### A warning category is enforced by the compiler or it is not a rule

Two documents required zero compiler warnings: a phase-completion list, and the
code-quality-review skill's compiler-hygiene section. Neither requirement was
enforced by anything. A clean build then reported 197 unused imports across the
four modules. A requirement that nothing can fail does not change what gets
written. So:

**A warning category becomes a rule by being escalated to a compile error in
`build.sbt`. Until it is escalated, the project has no rule about it, and no
document may state one.**

Escalation uses the pattern already in `build.sbt`, which matches on the
warning text:

```scala
"-Wconf:msg=match may not be exhaustive:error"   // ADR-035 Decision 1
"-Wconf:msg=unused import:error"                 // added per module as it reaches zero
```

Two consequences when adding a category:

- **Measure before escalating.** Turn the warning on build-wide first, count
  the sites per source root with a *clean* compile of every module, and only
  then escalate. An incremental compile reports nothing for a module it did not
  rebuild, which silently reads as zero.
- **Escalate per module as each reaches zero**, by adding the `-Wconf` option
  to that module's own `scalacOptions` rather than to `ThisBuild`. A cleaned
  module then cannot regress while the others are still being fixed.

Current state of the unused-import category: `-Wunused:imports` is on
build-wide as a warning; no module is escalated yet. Counts at the last clean
measurement were app/main 61, server/test 56, server/main 32, common/main 18,
common/test 15, server-it/test 14, app/test 1.

Every unused import in a file is removed whenever that file is edited for any
other reason. No separate request is needed and none is asked for. This covers a
file edited to fix a review finding, and any file the approved plan's inventory
already authorizes. The cleanup belongs to that edit rather than to a later
pass, so the counts above fall as ordinary work proceeds and a module reaches
zero without a dedicated sweep.

### Verifying a language or library fact — one route works, four do not

A question like "can a Scala 3 `enum` extend `Throwable`", "does zio-json derive
a codec for this shape", or "does this widen" is a compile-level fact. Verifying
it is correct and expected. Four plausible ways to verify it **cannot work in
this project**, each for a fixed structural reason, so none of them is worth a
second attempt.

**1. The REPL cannot be driven from a pipe, and it fails silently.**
`echo '…' | sbt commonJVM/console` consumes the input without evaluating it,
prints the jline warning *"The Parser of class dotty.tools.repl.JLineTerminal
does not support the CompletingParsedLine interface"*, and exits
`[success]`. The absence of output is the only signal that nothing ran, so a
piped REPL session reads as a pass. Never verify anything this way.

**2. A hand-assembled `scalac` classpath cannot run the compiler.**
`scala3-library_3-3.8.4.jar` in the coursier cache holds exactly one entry,
`META-INF/MANIFEST.MF`, and no classes — the Scala 3.8 library is a shim
artifact. So
`java -cp …/scala3-compiler_3-3.8.4.jar dotty.tools.dotc.Main` dies during
driver initialisation with `NoClassDefFoundError: scala.deriving.Mirror$Singleton`.
sbt gets the real library from its `scalaInstance`, which is not a path that can
be reconstructed with `find`.

**3. `sbt export <proj>/Compile/dependencyClasspath` is not that classpath
either.** It is the project's dependency classpath, and it begins with the same
empty shim jar. It is correct as the `-classpath` for user code and useless as
the `java -cp` for the compiler.

**4. A scratch file under `modules/` is not scratch, because it cannot be
removed.** A Bash heredoc or redirection into `modules/**` is refused by the
protocol hook — including into a test directory the hook would otherwise
auto-authorize — and the refusal names the Edit/Write tools as the route. But
`rm` on that path is a Bash write into the same gated region and is refused too.
So the agent can create such a file and cannot delete it, leaving the user to
clean up. Do not create one.

**What does work, in order of cost:**

- **Search the corpus for precedent first.** It is free. If no file here uses the
  construct, say that rather than assuming either answer — in this repository, as
  of 2026-10-11, no `Throwable` subtype carries a derived JSON codec and no `enum`
  extends a class, so neither question had an in-repo answer.
- **Which artifact you are looking at decides whether you may open it at all.**
  - **A first-party library — `metalog-distribution`, `vql-engine`, `hdr-rng` —
    is read from its sibling source checkout, never from a jar.** Not the
    compiled jar, not the `-sources.jar`, not to list it, not to extract it, not
    to decompile it. The sources are right there:
    `/home/danago/projects/metalog-distribution/src/`,
    `/home/danago/projects/vague-quantifier-logic/core/src/main/scala/vql/`,
    `/home/danago/projects/hdr-rng/core/`.
    **This is where the trap is set:** these three arrive as ordinary jars in the
    coursier cache and on every classpath this project exports, so a classpath
    dump or a cache search puts them in front of you looking like any other
    dependency. And the note in `CLAUDE.md` that they are "consumed as binary
    artifacts from Maven Central" and that "sibling checkouts are not needed to
    build" is about **building** — it is not licence to read their code out of a
    jar.
  - **A third-party artifact with no local checkout** (scala3-library, zio-json,
    tapir) may have its packaging inspected with `unzip -l` — that is how the
    empty-shim fact above was established. Reading its source from a jar is a
    last resort reserved for a library that has no checkout and cannot be
    cloned.
- **Compile the real change in the real module.** `sbt commonJVM/compile`,
  `sbt server/compile`, `sbt app/compile` are the authoritative answer and are
  work the change needs anyway, so "write it and compile" usually costs less than
  any probe.
- **When the fact must be known *before* a gated edit is authorized**, hand the
  user a pasteable probe — the Write, the compile command, and the `rm` — say in
  one line what a clean compile proves, and stop. That is the only route to a
  compile-level fact without an authorized edit.

Two failed verification attempts for environmental reasons is the limit, under
the red-build rule below — *"two attempts at the same fix is the limit"*. Then either answer analytically with the unverified
premise labelled as such, or hand over the one command that settles it. A third
mechanism is not tried.

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
sbt "server/testOnly *SimulatorSpec"
sbt "commonJVM/testOnly *RiskLeafSpec"
sbt "app/testOnly *TreeBuilderStateSpec"

# With filtered output (pass/fail summary)
sbt 'commonJVM/test; server/test' 2>&1 | \
  grep -E 'tests failed|FAILED|\[error\]|success'
```

### A red build or a failing test is yours to fix

The origin of a failure is irrelevant to the obligation to resolve it. This is
the rule most often argued around, so it is stated without an exit.

- **Never** dismiss, defer, downgrade or narrate around a failure on the grounds
  that it is pre-existing, unrelated, already broken, or not caused by the
  change in hand. Investigating blame is never a substitute for fixing.
- **Never** spend a tool call proving a failure is pre-existing. Spend it fixing.
- **Never** report work as done while any module that was touched is red. Green
  is the only done, and compiling is not running.
- The one exception is a fix that carries a genuine trade-off — weakening an
  assertion, changing an API shape, a workaround. That is a Decision Required
  (`CLAUDE.md`, G2). "It would take effort" and "it is unrelated" are not
  trade-offs.

**When a failure blocks progress**, in order: stop the current approach rather
than trying it again — two attempts at the same fix is the limit; state what was
attempted, what failed, and the exact error; say whether the failure is
revealing a missing type, a wrong abstraction, or a conflict with a decision
record; then present at least two concrete alternatives and wait. A third
attempt at the same approach is the behaviour this rule exists to stop.

### What "the build is finished" requires

Before a phase, step or plan is reported done:

- every module touched compiles, and its tests run green — no failure excused;
- the new behaviour has tests: the happy path, every validation constraint that
  can reject input, and every error branch;
- no test assertion was weakened, disabled, renamed or deleted to get there;
- the decision records that bear on the change were reviewed after the
  implementation, not only before it (`adr-constraints`);
- nothing in scope was deferred, parked or moved to a follow-up (`CLAUDE.md`, G8).

Zero compiler warnings is deliberately **not** on that list. The warning rule
above is the reason: a category this project cares about is escalated to a
compile error, and a category that is not escalated is not a rule. Requiring
"no warnings" in prose while 197 unused imports sat in the build is what made
the earlier version of this list untrue.

### Reporting test results

Run tests. Report **pass or fail only**. Never report the count. Never comment on the count. Never act on the count. The count is a distraction and irrelevant to the user. The user only needs to know if the tests passed or failed. Failed test need to be investigated even if they are from earlier. A single failure is a blocker regardless of the count. The user needs to know about it and fix it before proceeding.

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

---

## Integration Tests (serverIt)

Requires `local/irmin-prod:3.11-p1` Docker image (built once — see Image Builds).
`IrminCompose` starts/stops a scoped Irmin container automatically per run using
`docker-compose.server-it.yml` (dynamic host port — multiple specs run concurrently
without port conflicts).

**Before every run, clear leaked containers, networks and volumes first** — see
"Leaked Docker state cleanup" below (Mechanism 1). It is a mandatory pre-step,
not just crash recovery.

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

### Leaked Docker state cleanup — MANDATORY before every serverIt and BATS run

Two distinct leak mechanisms accumulate scoped Docker state across runs,
crashes, and other terminals. Both are environmental, not code failures: clean
and re-run, never re-diagnose them, and never count the run as a real failure on
their account. Clear the relevant one **before** starting a Docker tier — not
only after a Ctrl+C / crash.

**Mechanism 1 — `register_it_` per-run stacks (serverIt).** `IrminCompose`
creates a uniquely-named `register_it_<random>` stack per spec. Interrupted runs
leave these behind until Docker's address pool is exhausted (`all predefined
address pools have been fully subnetted`). That daemon error has this one cause.

The stack leaks three kinds of resource, and **volumes are the one that hides**:
nothing ever errors on a leaked volume, so they accumulate without limit while
the network leak is the only symptom anyone notices. Clear all three. Run before
every `serverIt` run:

```bash
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm; echo "--- remaining register_it_ containers/networks/volumes ---"; for k in "ps -a" "network ls" "volume ls"; do docker $k --filter name=register_it_ -q | wc -l; done
```

**Mechanism 2 — the fixed `register` compose stack (BATS suites A/C, dev
Compose).** BATS suites A and C and day-to-day `docker compose up` share the
default `register` project. An interrupted teardown leaves a container bound to
a since-removed network, and the next `up` fails with `network <id> not found`.
Run before every BATS suite A/C run (and any Docker Compose dev tier):

```bash
docker compose -p register down -v --remove-orphans 2>/dev/null || true
```

**What this reaches, stated precisely.** It is label-matched to the `register`
compose project, so it cannot touch another project's resources. It **can**
reach a container that was started by hand, if that container carries the
register compose labels: `--remove-orphans` reaps by label, not by how the
container was created, and a long-running stray from an old version looks
exactly like an orphan to it. List them before running it:

```bash
docker ps -a --filter label=com.docker.compose.project=register --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
```

`-v` additionally purges the `register_pg-data` volume, so each run starts from
a pristine store. Do not run it while doing persistence dev work whose data you
want to keep — it is a test-tier pre-step, not a general dev command.

### What has to be rebuilt after a cleanup

**Nothing, because a cleanup removes no images.** Neither mechanism passes
`--rmi`, so every image survives and `docker compose up` recreates containers,
networks and volumes from what is already there. A rebuild is triggered by a
**version bump**, not by a cleanup: `register-server` and `frontend` are tagged
`${APP_VERSION}` with `pull_policy: build`, so after a bump compose finds no
image at the new tag and builds one (server roughly 5–10 min, frontend 10–15).

What each tier needs, and whether it can build it on demand:

| Tier | Needs | On demand? |
|---|---|---|
| `commonJVM/test`, `app/test` | nothing | no Docker at all |
| `server/test` | `postgres:17-alpine` | yes — public image, pulled |
| `serverIt/test` | `local/irmin-prod:3.11-p1`, `authzed/spicedb:latest` | irmin is a **fixed** tag, unaffected by version bumps; build it once (see Image Builds). spicedb is pulled |
| BATS A/B/C, manual stack | `local/register-server:${APP_VERSION}`, `local/frontend:${APP_VERSION}`, `local/bats-runner:1.11` | server and frontend build on first `up` after a bump; the runner is built once |

So the unit and integration tiers never need a rebuild after a cleanup. Only the
BATS suites and the manual Docker stack do, and only when the version has moved
since those images were last built — check with
`docker images --filter reference='local/*'` against `APP_VERSION` in `.env`.

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

Output lands in `modules/app/target/scala-3.8.4/`.

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
`docs/dev/decision-records/ADR-020-supply-chain-security.md` §7–§9. Prerequisites and the
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

The two builder bases are independent of each other. `register-prod` requires
`graalvm-builder` and `irmin-prod` requires `irmin-builder`; `frontend-prod`
requires neither, because it builds its own first stage inline (ADR-026 §4).

```bash
# GraalVM builder base (~1-2 min; first-party libs resolve from Maven Central)
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 containers/builders/

# Irmin builder base (~15-40 min first run; rebuild only on Irmin/OCaml version change)
docker build -f containers/builders/Dockerfile.irmin-builder \
  -t local/irmin-builder:3.11-p1 containers/builders/

# Irmin production image (~10s; requires irmin-builder)
docker build -f containers/prod/Dockerfile.irmin-prod \
  -t local/irmin-prod:3.11-p1 containers/prod/

# Frontend SPA (~10-15 min first run)
source .env
docker build -f containers/prod/Dockerfile.frontend-prod \
  -t local/frontend:${APP_VERSION} .

# Register server native binary (~5-10 min; requires graalvm-builder)
docker build -f containers/prod/Dockerfile.register-prod \
  -t local/register-server:${APP_VERSION} .
```

**After server source changes**:
```bash
docker compose up -d register-server   # compose rebuilds via pull_policy: build
```

**After a first-party library release** (metalog-distribution, vql-engine, hdr-rng):
bump the pinned version in `build.sbt` (supply-chain skill applies); no builder
rebuild is needed — images resolve the new version from Maven Central.

---

## BATS Smoke Tests

Requires pre-built production images and the `local/bats-runner:1.11` image.

**Before every suite A/C run, clear the leaked `register` compose stack first**
— see "Leaked Docker state cleanup" above (Mechanism 2:
`docker compose -p register down -v --remove-orphans`, which purges
`register_pg-data` and reaps any container carrying the register compose
labels). It is a mandatory pre-step, not just crash recovery.

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
  `/workspace`) — build contexts and compose paths are resolved by the **host**
  daemon, so every path the runner passes must resolve to the same file on the
  host. Build contexts are all within the repo (`.` and `containers/*`), so
  mounting just the repo root at its identical path also works; the parent mount
  is the safe superset. A `/workspace` mount fails with errors like
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
# > commonJVM/clean   (on a compiler StackOverflowError — see Compilation)
```

---

## Reference

- Docker & Development guide: [docs/user/DOCKER-DEVELOPMENT.md](../../../docs/user/DOCKER-DEVELOPMENT.md)
- Testing guide: [docs/test/TESTING.md](../../../docs/test/TESTING.md)
