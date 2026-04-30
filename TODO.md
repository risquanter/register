# TODO

Open issues, observations, and investigation notes.
Items are descriptive — they document symptoms and current understanding,
not prescribed solutions.

---

## 1. "Will retry" banner text has no backing implementation

**Observed:** When a network error occurs (e.g. `TypeError: NetworkError when
attempting to fetch resource.`), the global error banner appends " — will retry"
to the message. No retry ever happens — the message is misleading.

**Current understanding:** `GlobalError.NetworkError` carries a `retryable: Boolean`
field. `GlobalError.fromThrowable` classifies Fetch API failures and
`java.io.IOException` as `retryable = true`. `ErrorBanner` renders the
" — will retry" hint when that flag is true. However, no code in `modules/app/`
acts on the flag — there is no `Schedule`, no exponential backoff, no automatic
re-dispatch. A search of the entire app source tree for `retry`, `Schedule.recurs`,
`Schedule.exponential`, and `retryN` found nothing relevant. The `TreeListView`
has a manual "Retry" button for its own `LoadState.Failed`, but that is a
separate, unrelated mechanism.

The `retryable` flag is currently a classification-only hint with no consumer.

---

## 2. Dual error display with inconsistent formatting on the same exception

**Observed:** When a query (or any `loadInto`-based API call) fails, the same
exception surfaces in two places simultaneously:

1. **Global error banner** (top of page) — via `ErrorObserver.onError` in
   `forkProvided` → `GlobalError.fromThrowable` → `ErrorBanner`. The message
   has browser prefixes stripped (`"TypeError: "`, `"Error: "`) and, for
   retryable errors, `" — will retry"` appended.

2. **Per-view inline error** (e.g. `QueryResultCard`, `LECChartView`) — via
   `LoadState.Failed(e.safeMessage)` set in `loadInto`'s `tapError`. The
   message is the raw `safeMessage` with no stripping or hints.

This produces two visually different error messages for the same failure.

**Current understanding:** The duplication is structural — `loadInto` sets the
per-view `LoadState.Failed` in its `tapError`, and then `forkProvided` fires
the global `ErrorObserver` as a separate concern. Both channels process the
same `Throwable` independently with different formatting pipelines
(`safeMessage` vs `GlobalError.fromThrowable` → `msg()` + hint).

This may be a hint at latent code / functionality duplication between the
per-view error path and the global error path. The two formatting pipelines
(`safeMessage` in `ZJS.loadInto` vs `GlobalError.msg()` in `fromThrowable`)
diverge silently — the global path strips prefixes and adds context; the
per-view path does not. Whether these should converge, or whether one channel
should suppress when the other is active, is an open design question.

---

## 3. PostgreSQL workspace TTL/idle interval handling uses a DB-specific text round-trip

**Observed:** The PostgreSQL workspace store currently writes `ttl` and
`idle_timeout` via a Quill `infix` insert that casts lifted string parameters
to `::interval`, and reads them back by parsing PostgreSQL's textual interval
representation.

**Current understanding:** This is implemented in
`WorkspaceStorePostgres.create()` and `WorkspaceStorePostgres.DurationString`
under `modules/server/src/main/scala/com/risquanter/register/services/workspace/`.
The approach is secure because values are still parameterized with `lift(...)`,
but it is PostgreSQL-specific and less idiomatic than a first-class Quill/JDBC
mapping. This was introduced because Quill did not provide a working
encoder/decoder for `PGInterval` in the current setup.

Follow-up review should determine whether to replace this with a more idiomatic
representation, for example:
- a custom Quill/JDBC encoder-decoder for a dedicated interval type,
- or a schema change to store durations as numeric milliseconds/seconds,
- or another typed persistence representation that avoids textual interval parsing.

---

## 4. Backend/mode selector config is still stringly typed

**Observed:** Selector-style config values such as repository backend and
workspace-store backend still deserialize to raw `String` values rather than a
typed enum / ADT.

**Current understanding:** Immediate inspection found at least these cases:
- `RepositoryConfig.repositoryType: String` in
   `modules/server/src/main/scala/com/risquanter/register/configs/RepositoryConfig.scala`
- `WorkspaceStoreConfig.backend: String` in
   `modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceStoreConfig.scala`
- `AuthConfig.mode: String` in
   `modules/server/src/main/scala/com/risquanter/register/configs/AuthConfig.scala`

These values are normalized with `.trim.toLowerCase` and matched manually in
application wiring. That means invalid values are only caught indirectly at use
sites, and supported values are not encoded in the type system.

Follow-up review should determine whether these should deserialize directly to
a typed representation (e.g. Scala 3 `enum` / sealed trait with explicit
zio-config decoding), at least for closed sets such as:
- `in-memory` / `irmin`
- `in-memory` / `postgres`
- `capability-only` / `identity` / `fine-grained`

Free-form config such as URLs, branch names, hostnames, and service names may
remain strings where the domain is intentionally open; the concern here is with
closed-choice selector values.

---

## 5b. SNAPSHOT dependency resolution strategy in Docker builds

**Decision:** Inline `COPY + sbt publishLocal` per Dockerfile (Option 1).

Each Dockerfile that needs `hdr-rng` or `fol-engine` and cannot inherit a
pre-warmed base image copies the sibling source trees into a temporary directory,
runs `sbt publishLocal`, then deletes the source. The compiled `.jar` artifacts
remain in the image's Ivy cache (`~/.ivy2/local/`) for the duration of the build.

Current state:
- `containers/builders/Dockerfile.graalvm-builder` — publishLocal inline; context `..`
- `containers/prod/Dockerfile.frontend-prod` — publishLocal inline; context `..`
- `containers/prod/Dockerfile.register-prod` — no publishLocal; inherits the JVM
  artifacts from `FROM local/graalvm-builder:21`

**Rebuild rule:** if `hdr-rng` or `vague-quantifier-logic` source changes, rebuild
`local/graalvm-builder:21` and `local/frontend:dev` before rebuilding dependent images.

**Note on Docker context and sibling paths:**  
Docker's `COPY` instruction cannot reference paths outside the build context —
there is no `COPY ../sibling/` syntax. The `..` passed as the context *argument*
to `docker build` is the only valid mechanism when sibling source trees are
needed. Context `.` with sibling paths is not possible in a Dockerfile.

**Migration work required when `hdr-rng` and `fol-engine` are published:**

The following changes must be made atomically once both artifacts are available
from a Maven registry (GitHub Packages or Maven Central):

1. **`containers/builders/Dockerfile.graalvm-builder`**
   - Delete the two `COPY` + `RUN sbt publishLocal` blocks for `hdr-rng` and
     `vague-quantifier-logic`.
   - Update the build comment at the top: remove "Build context must be the
     parent of the register project" and change the example command to use `.`.

2. **`containers/prod/Dockerfile.frontend-prod`**
   - Delete the two `COPY` + `RUN sbt publishLocal` blocks for `hdr-rng` and
     `vague-quantifier-logic`.
   - Change all `COPY register/...` paths back to unprefixed `COPY ...` paths
     (i.e. revert to context `.`).
   - Update the build comment at the top: remove the `..` context note.

3. **`containers/builders/Dockerfile.graalvm-builder.dockerignore`**
   - Delete the file entirely (the new `.` context uses `register/.dockerignore`
     which already has the correct allow-list).

4. **`containers/prod/Dockerfile.frontend-prod.dockerignore`**
   - Delete the file entirely (same reason as above).

5. **`docs/DOCKER-DEVELOPMENT.md`** — update every occurrence of:
   - The graalvm-builder build command: `.. ` → `.`
   - The frontend-prod build command: `..` → `.`
   - The build context explanatory notes in the graalvm-builder and frontend
     sections.
   - The Quick Reference table entries for both images.

6. **`build.sbt`**
   - Add a `resolvers +=` entry if publishing to GitHub Packages (not needed
     for Maven Central which is already on the default resolver chain).
   - For GitHub Packages: pass registry credentials via
     `--secret id=MAVEN_TOKEN,...` in the `docker build` command and write
     `~/.sbt/1.0/credentials` in the Dockerfile builder stage.
   - The `libraryDependencies` coordinates (`com.risquanter %% "hdr-rng"`,
     `com.risquanter %%% "fol-engine"`) remain unchanged.

7. **`docker-compose.yml`**
   - Change `context: ..` back to `context: .` for the `frontend` service
     (widened to `..` solely because the Dockerfile needed sibling source trees;
     once those `COPY` blocks are removed, the context can be narrowed back).
   - Change `dockerfile: register/containers/prod/Dockerfile.frontend-prod` back
     to `dockerfile: containers/prod/Dockerfile.frontend-prod` (the `register/`
     prefix was added because the context root is `..`).
   - `register-server` and `irmin` both already use `context: .` and are unaffected.

---

## 6. Inconsistent Docker image naming convention

**Observed:** Locally-built images use inconsistent tag conventions:

| Image | Tag | Issue |
|---|---|---|
| `local/frontend:dev` | `dev` | Misleading — built from `Dockerfile.frontend-prod`; tag does not reflect content |
| `register-server:prod` | `prod` | No `local/` prefix; tag describes build type, not version |
| `local/graalvm-builder:21` | `21` | JDK version — semantically clear |
| `local/irmin-prod:3.11` | `3.11` | Irmin version — semantically clear |
| `local/irmin-builder:3.11` | `3.11` | Irmin version — semantically clear |

The `local/frontend:dev` and `register-server:prod` names pre-date a coherent
naming policy. The inconsistency creates confusion when reading `docker ps` or
build scripts.

**Decision context:**

Three options were evaluated:

| Option | Example | Assessment |
|---|---|---|
| `local/` + `:latest` | `local/register-server:latest` | Honest for a mutable local-only image ("always the last thing built"); zero version management overhead; matches `pull_policy: never` semantics in compose |
| `local/` + app version | `local/register-server:0.1.0-SNAPSHOT` | Aligns with sbt versioning; enables multi-version coexistence and rollback; requires keeping tag in sync with `build.sbt` |
| Keep `:dev` / `:prod` tags | — | No migration cost; but `:dev` is actively misleading (prod Dockerfile, prod content) and `register-server` lacks the `local/` prefix |

Version tagging (option 2) is the standard practice for images intended for
registry promotion, and will be the eventual target as the project matures
toward a release process. However, it requires a mechanism to keep the image
tag in sync with `build.sbt` (e.g. a shell script or sbt task that reads the
version and passes it as a `--build-arg`), which adds complexity not justified
at the current stage.

**Decided migration path (on hold):**

1. **Phase 1 — immediate (on hold):** Rename to `local/` + `:latest` for all
   locally-built app images. Specifically:
   - `local/frontend:dev` → `local/frontend:latest`
   - `register-server:prod` → `local/register-server:latest`
   - Update `docker-compose.yml`, all docs, and test references atomically.

2. **Phase 2 — subsequent:** Migrate to `local/` + app version tag
   (e.g. `local/register-server:0.1.0-SNAPSHOT`), driven by the sbt project
   version in `build.sbt`. Implement a helper (script or sbt task) to stamp
   the tag automatically so it stays in sync without manual edits.

The Irmin and GraalVM builder images are **not affected** — their version tags
pin to external software versions (`3.11`, `21`) and are already correct.
