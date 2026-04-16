# Plan: Workspace-Scoped Persistence

**Status:** Draft — decisions D1–D4 decided, D5–D6 pending  
**Scope:** Planning only — no code changes  
**Date:** 2026-04-15 (decisions updated 2026-04-16)

---

## 0 Problem Statement

Three problems in the current architecture:

1. **Orphaned trees on restart.** `WorkspaceStoreLive` uses
   `Ref[Map[WorkspaceKeySecret, Workspace]]` — lost on server restart.
   Trees in Irmin become unreachable orphans with no ownership metadata.
   The Reaper has nothing to evict, so orphans accumulate.

2. **No workspace provenance in storage.** Irmin paths are flat
   (`risk-trees/{treeId}/...`). An admin cannot determine which
   workspace owned which trees, even with direct Irmin GraphQL access.

3. **Capability token conflated with identity.** `Workspace` uses
   `WorkspaceKeySecret` as its sole identifier — a mutable, secret
   credential unsuitable for persistent paths, audit logs, or SpiceDB.
   `WorkspaceId` exists in `OpaqueTypes.scala` but is not wired in.

### Design Goals

1. **Stable identity:** Add `WorkspaceId` (ULID, immutable, non-secret)
   to the `Workspace` model as a first-class grouping key.
2. **Storage provenance:** Organize Irmin tree paths under workspace
   namespaces for admin audit and structural clarity.
3. **Durable workspace state:** Persist workspace associations so they
   survive server restarts (PostgreSQL — see D1, decided).
4. **Rehydration:** Rebuild in-memory workspace state from the durable
   store on startup.

---

## 1 Conceptual Model

### Two Types, Two Roles

```
WorkspaceKeySecret                  WorkspaceId
─────────────────────               ──────────────────
128-bit SecureRandom                ULID (Crockford base32)
base64url, 22 chars                 26 chars
Mutable (rotatable)                 Immutable (generated once)
Secret (toString redacted)          Non-secret (safe for paths/logs)
External access credential          Internal grouping key
Lives in Ref[Map] (ephemeral)       Persists in durable store
```

### Two Storage Layers, Separated

The architecture has two distinct layers:

```
┌─────────────────────────────────────┐
│  WorkspaceStore                     │  Association / token index
│  Ref[Map] now → PostgreSQL later    │  Maps key → {id, trees, TTL}
│  NEVER stores domain content        │
└──────────────┬──────────────────────┘
               │ TreeId references only
┌──────────────▼──────────────────────┐
│  RiskTreeRepository                 │  Domain content store
│  In-memory or Irmin (config-driven) │  Trees, nodes, metadata
│  Path: workspaces/{wsId}/risk-trees │  ← NEW: workspace in path
│  NEVER stores workspace tokens      │
└─────────────────────────────────────┘
```

The `WorkspaceId` appears in **both** layers:
- In `WorkspaceStore` as a stable identity (persisted alongside the key)
- In Irmin paths as a structural namespace for domain content

This is NOT the same as "storing workspace metadata in Irmin." The
workspace association data (key mapping, TTL, status) lives in
`WorkspaceStore`. Irmin paths merely use `WorkspaceId` as an
organizational prefix.

### Irmin Path Layout Change

**Current:**
```
risk-trees/
  {treeId}/
    meta
    nodes/
      {nodeId}
```

**Target:**
```
workspaces/
  {workspaceId}/
    risk-trees/
      {treeId}/
        meta
        nodes/
          {nodeId}
```

---

## 2 Phases

### Phase 0: Domain Model — Add `WorkspaceId` to `Workspace`

**Goal:** Wire the existing `WorkspaceId` type into the domain model.
This is purely additive — `WorkspaceId` already exists in
`OpaqueTypes.scala` but is not wired into the `Workspace` model.

| File | Change |
|------|--------|
| `Workspace.scala` | Add `id: WorkspaceId` as first field |
| `WorkspaceStoreLive.scala` | Generate `WorkspaceId` via `SafeId` ULID factory in `create()` |
| `WorkspaceStore.scala` | Add `resolveById(id: WorkspaceId): IO[AppError, Workspace]` |
| All `Workspace` constructors | Include `id` parameter |
| Test fixtures | Update `Workspace` construction |

**Invariants:**
- `id` is generated once at creation, never changes, survives `rotate()`
- `key` remains the primary lookup key (O(1) via `Ref[Map]`)
- A secondary reverse index `Map[WorkspaceId, WorkspaceKeySecret]`
  supports `resolveById`; both maps updated atomically via `Ref.modify`

**Test impact:** Mechanical — all `Workspace(...)` constructors in
tests gain an `id` parameter. Test fixtures generate deterministic
`WorkspaceId` values.

---

### Phase 1: Workspace-Scoped Tree Storage Paths

**Goal:** Move tree data under workspace namespaces in Irmin so every
tree has structural provenance.

#### 1.1 `RiskTreeRepository` Trait Change — **D2: Explicit Parameter** (decided)

The trait currently has no workspace awareness. `WorkspaceId` is
added as an explicit parameter to every method — compile-time enforced,
no hidden state:

```scala
trait RiskTreeRepository:
  def create(wsId: WorkspaceId, riskTree: RiskTree): Task[RiskTree]
  def update(wsId: WorkspaceId, id: TreeId, op: ...): Task[RiskTree]
  def delete(wsId: WorkspaceId, id: TreeId): Task[RiskTree]
  def getById(wsId: WorkspaceId, id: TreeId): Task[Option[RiskTree]]
  def getAllForWorkspace(wsId: WorkspaceId): Task[List[...]]
```

This is a mechanical change across ~30+ files/tests. The one-time cost
makes workspace scoping visible at every call site.

#### 1.2 `RiskTreeRepositoryIrmin` Path Changes

All path computations change from:
```scala
val basePath = s"risk-trees/${id.value}"
```
to:
```scala
val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
```

Commit messages gain workspace context:
```
workspace:{wsId}:risk-tree:{treeId}:create:{txn}:set-node:{nodeId}
workspace:{wsId}:risk-tree:{treeId}:delete:meta
```

#### 1.3 `RiskTreeRepositoryInMemory` ← **Decision D3** (pending)

This decision determines how the **test-time** in-memory repository
behaves when it receives a `WorkspaceId` parameter.

In production, the Irmin-backed repository uses `WorkspaceId` to
construct the storage path (`workspaces/{wsId}/risk-trees/...`). The
in-memory implementation has no path structure — it stores trees in a
flat `TrieMap`. The question is whether that `TrieMap` should
**enforce** workspace boundaries or **ignore** them:

| Behaviour | TrieMap key | What tests catch | What tests miss |
|-----------|-------------|------------------|-----------------|
| **D3-A: Ignore** | `TreeId` only | CRUD logic, validation, idempotency | A service bug that passes the wrong `wsId` — the tree is still found because the key ignores `wsId`. Cross-workspace data leak is invisible. |
| **D3-B: Enforce** | `(WorkspaceId, TreeId)` | All of the above **plus** wrong-workspace bugs. If a service forgets to thread `wsId` or passes a stale one, `getById` returns `None`. | Nothing — strictly more coverage. |

**Practical example of what D3-B catches:**

```scala
// Bug: controller resolves workspace A, but service accidentally
// passes workspace B's ID to the repository.
riskTreeService.getById(wrongWsId, treeId)
// D3-A: returns Some(tree) — bug is silent
// D3-B: returns None — test fails, bug caught
```

The cost of D3-B is that every test constructing an in-memory repo
must provide a valid `WorkspaceId` alongside each `TreeId`. This is
mechanical — test fixtures already generate `WorkspaceId` values
after Phase 0.

See **Decision D3** below.

#### 1.4 Service and Controller Threading

`WorkspaceController` already resolves the workspace. After Phase 0
adds `ws.id`, the controller passes `ws.id` to `RiskTreeService`
methods, which pass it to `RiskTreeRepository`.

```
HTTP request → WorkspaceController
  → workspaceStore.resolve(key) → Workspace{id, key, trees, ...}
  → riskTreeService.create(ws.id, tree)
    → riskTreeRepository.create(ws.id, tree)
      → Irmin: set("workspaces/{ws.id}/risk-trees/{tree.id}/...")
```

#### 1.5 `getAll` Replacement — **D4: Replace only** (decided)

Current `getAll` lists everything under `risk-trees/`. With scoped
paths, it is replaced by `getAllForWorkspace(wsId)`. No global scan
method exists on the repository trait. An admin endpoint scanning
across all workspaces can be added later outside the repository trait
if needed.

---

### Phase 2: Durable Workspace State — **D1: PostgreSQL now** (decided)

**Goal:** Persist workspace associations so they survive server restart.

The durable backend is **PostgreSQL**, added to `docker-compose.yml`
under the `persistence` profile alongside Irmin. Tech stack: Quill
4.8.6 + Flyway 11.5.0 + PG JDBC 42.7.7. Workspace data is an
association/token index (key → set of TreeIds, TTL, status) —
relational, mutable, TTL-evicted. This maps naturally to relational
tables, not to a content-addressed store like Irmin.

#### 2.1 Schema

```sql
CREATE TABLE workspaces (
  id          TEXT PRIMARY KEY,      -- WorkspaceId (ULID)
  key_hash    TEXT NOT NULL UNIQUE,  -- bcrypt/argon2 hash of WorkspaceKeySecret
  created_at  TIMESTAMPTZ NOT NULL,
  last_access TIMESTAMPTZ NOT NULL,
  ttl         INTERVAL NOT NULL,
  idle_timeout INTERVAL NOT NULL,
  status      TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE workspace_trees (
  workspace_id TEXT NOT NULL REFERENCES workspaces(id),
  tree_id      TEXT NOT NULL,
  added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (workspace_id, tree_id)
);

-- TTL-based pruning:
-- DELETE FROM workspaces
-- WHERE created_at + ttl < now()
--    OR last_access + idle_timeout < now();
```

**Note on `key_hash`:** The raw `WorkspaceKeySecret` is NEVER stored
in plaintext in PostgreSQL. On rehydration, the server generates new
capability tokens. The hash exists only for an optional
"reconnect with existing key" flow (see Decision D5).

Storing the raw key as plaintext would contradict ADR-022's secret
handling principles. A hash-based approach is more secure but prevents
reconnection. See Decision D5.

#### 2.2 Tech Stack

##### Existing Infrastructure — Validated Unused

The register codebase already contains two PostgreSQL-related
artefacts that were added in anticipation of this work but are
confirmed **completely unwired**:

1. **`register.db` section in `application.conf`** (lines 77–86).
   Contains `dataSourceClassName = org.postgresql.ds.PGSimpleDataSource`
   and a `dataSource { user, password, databaseName, portNumber,
   serverName }` block — the exact HOCON structure that Quill's
   `Quill.DataSource.fromPrefix("register.db")` reads.
   Verified: zero Scala files reference `register.db`;  no
   `Configs.makeLayer[...]("register.db")` call exists anywhere.

2. **`val quillVersion = "4.8.6"` in `build.sbt`** (line 29).
   Declared but never used in any `libraryDependencies` line.

Both are adopted as-is. No new config prefix is needed — there are
no collisions.

##### Dependencies (added to `serverDependencies` in `build.sbt`)

| Library | Artifact | Version | Published | Notes |
|---------|----------|---------|-----------|-------|
| Quill JDBC ZIO | `io.getquill::quill-jdbc-zio` | 4.8.6 | 2024-10-30 | Uses existing `quillVersion` val. Wire into `serverDependencies`. |
| PostgreSQL JDBC | `org.postgresql:postgresql` | 42.7.7 | 2025-06-11 | No known CVEs at time of writing. |
| Flyway Core | `org.flywaydb:flyway-core` | 11.5.0 | 2025-03-26 | Since Flyway 10+, PG support is in a separate module. 11.5.0 chosen for conservatism. |
| Flyway PG | `org.flywaydb:flyway-database-postgresql` | 11.5.0 | 2025-03-26 | Required since Flyway 10+. Must match `flyway-core` version. |
| Testcontainers PG | `io.github.scottweaver::zio-2-0-testcontainers-postgresql` | 0.10.0 | 2023-02-15 | `% Test` only. Latest available release. For `WorkspaceStorePostgres` unit tests; integration tests use Docker Compose CLI. |

**Version policy:** Every library version above was published >1 week
before the date of this plan. Before implementation, verify no CVEs
have been published against these versions (NVD, GitHub Advisories,
Snyk DB).

**Compatibility:** Scala 3.7.4, ZIO 2.1.24. Quill 4.8.6 targets
ZIO 2.x / Scala 3. Flyway 11.x requires Java 17+ (already satisfied).
PostgreSQL JDBC 42.7.x supports Java 8+.

##### Quill Layer Pattern

Centralized in a `Repository` object:

```scala
object Repository {
  val quillLayer      = Quill.Postgres.fromNamingStrategy(SnakeCase)
  val dataSourceLayer = Quill.DataSource.fromPrefix("register.db")
  val dataLayer       = dataSourceLayer >>> quillLayer
}
```

This reads the existing `register.db` HOCON section directly.

##### Configuration — Register's Native Pattern

Register uses `Configs.makeLayer[C: DeriveConfig: Tag](path)` for
typed config everywhere (8 config paths today). The `register.db`
section does **not** follow this pattern — it is consumed directly
by `Quill.DataSource.fromPrefix`, which reads HOCON natively. This
is correct: Quill's `fromPrefix` expects raw HOCON, not a ZIO
`DeriveConfig` derivation.

Flyway config, however, needs its own typed case class following
the register pattern:

```scala
final case class FlywayConfig(
  url: String,
  user: String,
  password: String
)

object FlywayConfig {
  val layer: ZLayer[Any, Throwable, FlywayConfig] =
    Configs.makeLayer[FlywayConfig]("register.flyway")
}
```

With a corresponding HOCON section:

```hocon
register {
  flyway {
    url = "jdbc:postgresql://localhost:5432/register"
    url = ${?REGISTER_FLYWAY_URL}
    user = register
    user = ${?REGISTER_DB_USER}
    password = register
    password = ${?REGISTER_DB_PASSWORD}
  }
}
```

The `register.db` section gets env var overrides added to match the
established register pattern (every field has a `${?...}` override):

```hocon
register {
  db {
    dataSourceClassName = org.postgresql.ds.PGSimpleDataSource
    dataSource {
      user = register
      user = ${?REGISTER_DB_USER}
      password = register
      password = ${?REGISTER_DB_PASSWORD}
      databaseName = register
      databaseName = ${?REGISTER_DB_NAME}
      portNumber = 5432
      portNumber = ${?REGISTER_DB_PORT}
      serverName = localhost
      serverName = ${?REGISTER_DB_HOST}
    }
  }
}
```

`register.db` and `register.flyway` share env vars for credentials
(`REGISTER_DB_USER`, `REGISTER_DB_PASSWORD`) but have separate URL
construction: Quill uses individual HOCON fields (`serverName`,
`portNumber`, `databaseName`); Flyway uses a JDBC URL string.

##### Flyway Migration Pattern

```scala
trait FlywayService {
  def runMigrations: Task[Unit]
  def runRepairs: Task[Unit]
}

class FlywayServiceLive(config: FlywayConfig) extends FlywayService {
  def runMigrations: Task[Unit] = ZIO.attempt {
    Flyway.configure()
      .dataSource(config.url, config.user, config.password)
      .locations("classpath:db/migration")
      .load()
      .migrate()
  }.unit
  // ...
}

object FlywayServiceLive {
  val layer: ZLayer[FlywayConfig, Nothing, FlywayService] =
    ZLayer.fromFunction(FlywayServiceLive(_))
}
```

Migration SQL files live in `resources/db/migration/V1__workspace_schema.sql`.
Migrations run at startup before the server accepts traffic.

##### Repository Implementation Pattern

```scala
class WorkspaceStorePostgres(quill: Quill.Postgres[SnakeCase])
    extends WorkspaceStore {
  import quill.*

  inline given schema: SchemaMeta[WorkspaceRow] =
    schemaMeta[WorkspaceRow]("workspaces")

  // CRUD via inline query[WorkspaceRow]...
}

object WorkspaceStorePostgres {
  val layer: ZLayer[Quill.Postgres[SnakeCase], Nothing, WorkspaceStore] =
    ZLayer.fromFunction(WorkspaceStorePostgres(_))
}
```

Iron type ↔ SQL type mappings (`WorkspaceId ↔ String`, etc.) go in a
`QuillMappings` object using Quill's `MappedEncoding[A, B]`.

The `Ref[Map]` may remain as a **write-through cache** in front of
PG for O(1) hot-path lookups, with PG as the durable backing store.
Or the Ref can be eliminated entirely in favour of PG queries.

##### Test Pattern

A `RepositorySpec` trait provides a Testcontainers-backed `DataSource`
layer:

```scala
trait RepositorySpec {
  val initScript: String
  val dataSourceLayer: ZLayer[Scope, Throwable, DataSource] = ZLayer {
    for {
      container <- ZIO.acquireRelease(
                     ZIO.attempt(PostgreSQLContainer("postgres")
                       .withInitScript(initScript).tap(_.start())))
                   (c => ZIO.attempt(c.stop()).ignoreLogged)
      ds        <- ZIO.attempt { /* PGSimpleDataSource from container */ }
    } yield ds
  }
}
```

Unit-level repository tests use this trait. Integration tests continue
using Docker Compose CLI (established pattern in `server-it`).

#### 2.3 Application.scala Wiring

Follows the `chooseRepo` pattern:

```scala
private val chooseWorkspaceStore: ZLayer[...] =
  ZLayer.fromZIO {
    ZIO.service[WorkspaceStoreConfig].flatMap { cfg =>
      cfg.backend match
        case "postgres" => // wire WorkspaceStorePostgres
        case _          => // wire WorkspaceStoreLive (Ref-based)
    }
  }
```

#### 2.4 Docker Compose

PostgreSQL must be added to `docker-compose.yml` for local dev:

```yaml
services:
  postgres:
    image: postgres:17-alpine
    profiles: ["persistence"]
    environment:
      POSTGRES_DB: register
      POSTGRES_USER: register_app
      POSTGRES_PASSWORD: dev-only-password
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
```

Sharing the `persistence` profile with Irmin keeps the dev experience
consistent: `docker compose --profile persistence up -d` starts both.

---

### Phase 3: Startup Rehydration

**Goal:** On server boot, rebuild the in-memory workspace state from
the durable store.

#### 3.1 Flow (PostgreSQL-backed)

```
Server starts
  → WorkspaceStorePostgres.loadAll()
  → For each workspace with status = active:
      1. Check if TTL/idle expired
      2. If still valid:
         - Generate fresh WorkspaceKeySecret (see Decision D5)
         - Build Workspace(id, key, trees, ...)
         - Populate write-through cache (if used)
      3. If expired:
         - UPDATE workspaces SET status = 'expired'
         - Cascade-delete trees from Irmin (configurable)
  → Log rehydrated count + expired count
```

#### 3.2 Fresh Tokens on Rehydration

Rehydrated workspaces get **new** `WorkspaceKeySecret` values.
Old URLs are invalidated. This is acceptable because:
- Capability URLs are ephemeral by design (ADR-021)
- Users already experience URL invalidation on server restart today
- Future authenticated users (Layer 1+) can retrieve workspaces
  by `UserId` and get fresh capability URLs

#### 3.3 Configuration

```hocon
register.workspace {
  rehydrateOnStartup = false  # default: off (in-memory mode)
  rehydrateOnStartup = ${?REGISTER_WORKSPACE_REHYDRATE}
}
```

When `true` (recommended for persistence profile), scans the durable
store on boot.

---

### Phase 4: Reaper Persistence-Awareness

**Goal:** Make `WorkspaceReaper` aware of the durable store.

#### Current Behavior
```
Every 5min:
  store.evictExpired → Map[Key, Workspace]
  For each evicted workspace:
    riskTreeService.cascadeDeleteTrees(ws.trees)
```

#### Enhanced Behavior
When backed by PostgreSQL, `evictExpired` also:
- Marks workspace as `expired` in PG
- Cascade-deletes tree data from Irmin using workspace-scoped paths:
  `workspaces/{ws.id}/risk-trees/...`

On startup (Phase 3), workspaces already marked `expired` are not
rehydrated — they are skipped or their trees are cleaned up.

#### Soft Delete vs Hard Delete ← **Decision D6**

---

### Phase 5: Migration — Flat to Scoped Paths

**Goal:** Handle the transition from flat `risk-trees/` paths to
workspace-scoped `workspaces/{wsId}/risk-trees/` paths.

**Recommendation:** Lazy adoption. This is pre-production.

1. Existing `risk-trees/` data = legacy orphans (no provenance)
2. New workspaces write to `workspaces/{wsId}/risk-trees/`
3. Old prefix never written to again
4. Optional: admin script to delete orphans under `risk-trees/`

---

## 3 Decisions Requiring Input

### Decision D1: When does PostgreSQL land?

> **Decided: D1-A — PostgreSQL now.**

Add PostgreSQL to `docker-compose.yml` under the `persistence` profile.
Implement `WorkspaceStorePostgres` as part of this plan. Tech stack:
Quill 4.8.6 + Flyway 11.5.0 + PG JDBC 42.7.7. All five phases proceed.

---

### Decision D2: How does `WorkspaceId` reach the repository?

> **Decided: D2-A — Explicit parameter.**

Add `wsId: WorkspaceId` to all `RiskTreeRepository` methods. Mechanical
change across ~30 files. Compile-time safe. Explicit is better than
implicit — workspace scoping is visible at every call site.

---

### Decision D3: In-memory repository workspace isolation?

| Option | Description |
|--------|-------------|
| **D3-A: Ignore `wsId`** | Accept parameter, key `TrieMap` by `TreeId` only. Simple. Tests don't catch cross-workspace leaks. |
| **D3-B: Enforce isolation** | Key by `(WorkspaceId, TreeId)`. Tests catch scoping bugs. Slightly more complex test setup. |

**My assessment:** D3-B. The added complexity is minimal (tuple key)
and catches real bugs — e.g., a forgotten `wsId` parameter silently
returning another workspace's tree.

---

### Decision D4: `getAll` replacement strategy

> **Decided: D4-A — Replace only.**

Remove `getAll`, add `getAllForWorkspace(wsId)` only. No global scan on
the repository trait. `getAll` has no use case in workspace-scoped
operation. An admin endpoint can be added later outside the trait.

---

### Decision D5: Key persistence strategy on rehydration

| Option | Description |
|--------|-------------|
| **D5-A: Fresh tokens only** | Rehydrated workspaces always get new `WorkspaceKeySecret`. Old URLs die on restart. Simplest. No key storage needed. |
| **D5-B: Hash-based reconnect** | Store `bcrypt(key)` in PG. On startup, rehydrate with fresh tokens. Users who present the old key can be re-linked (verify hash → issue session). Preserves old URLs across restarts. |
| **D5-C: Encrypted key storage** | Store `AES-GCM(key)` in PG. Exact key recovery on rehydration. Old URLs survive restart. Requires key management (which key encrypts what?). |

**My assessment:** D5-A. Capability URLs are explicitly ephemeral
(ADR-021). "Create a new workspace" on restart is the accepted UX.
Future Layer 1 authenticated users bypass capability URLs entirely.
D5-B/C add significant complexity for a transient benefit.

#### Rehydration: What It Means and When It Triggers

"Rehydration" is the process of reconstructing the in-memory
`Ref[Map[WorkspaceKeySecret, Workspace]]` from the durable PostgreSQL
store when the server starts. Without rehydration, a server restart
means every workspace's tree associations are lost — trees exist in
Irmin but no workspace claims them.

**Happy path — planned restart (deploy, config change, scaling):**

```
1. Server process starts
2. Flyway migrations run (idempotent — no-op if schema current)
3. WorkspaceStorePostgres.loadAll() queries all rows with
   status = 'active'
4. For each workspace:
   a. Check: created_at + ttl > now()  AND
              last_access + idle_timeout > now()
   b. If valid:
      - Generate fresh WorkspaceKeySecret (old URLs die)
      - Build Workspace(id, freshKey, trees, ...)
      - Insert into Ref[Map] (write-through cache)
      - Log: "Rehydrated workspace {wsId} with {n} trees"
   c. If expired:
      - UPDATE workspaces SET status = 'expired'
      - Schedule cascade delete of tree data from Irmin
      - Log: "Expired workspace {wsId} — {n} trees scheduled
        for cleanup"
5. Server begins accepting HTTP traffic
6. Log summary: "Rehydrated {alive} workspaces, expired {dead}"
```

The key property: **no HTTP traffic is served until rehydration
completes.** This prevents a race where a request arrives for a
workspace that hasn't been loaded yet.

**Unhappy path — crash restart (OOM, SIGKILL, hardware fault):**

The flow is identical to the happy path. PostgreSQL is the durable
ground truth — the server process is stateless between restarts.
Specific scenarios:

| Crash scenario | PostgreSQL state | Rehydration outcome |
|----------------|-----------------|--------------------|
| OOM kill during normal operation | All committed writes intact. `last_access` reflects last successful `touch()`. | Normal rehydration. Workspaces whose `last_access + idle_timeout` fell behind during downtime are expired. |
| Crash during `evictExpired` | Partially committed. Some workspaces marked `expired`, others not yet. Irmin tree data may or may not be deleted. | Workspaces already marked `expired` are skipped. Still-active workspaces are rehydrated. Orphaned Irmin data (trees marked for delete but not yet removed) is cleaned up by the Reaper's next cycle. |
| Crash during `create` (new workspace) | If PG insert committed: workspace exists in PG with tree list. If not committed: workspace never existed. | Committed workspaces are rehydrated. Uncommitted ones are lost — the user sees a failed request and retries. No orphans. |
| Crash during `addTree` | If PG `workspace_trees` insert committed: association exists. If not: workspace exists but tree not associated. | Committed associations are rehydrated. Uncommitted tree data may exist in Irmin as an orphan — the Reaper cleans it up (tree has no workspace owner). |
| PostgreSQL itself crashes | PG WAL ensures durability up to last committed transaction. | On PG recovery + server restart, rehydration proceeds normally from PG's recovered state. |
| Both PG and server crash simultaneously | PG recovers from WAL. Server restarts and rehydrates from recovered PG state. | Same as above — PG's ACID guarantees handle this. |

**Downtime duration effect:** If the server is down for longer than a
workspace's remaining TTL or idle timeout, that workspace is naturally
expired on rehydration. This is correct behaviour — the workspace
*should* be expired. The user creates a new one.

**Fresh tokens and URL invalidation:** Every rehydrated workspace gets
a new `WorkspaceKeySecret`. Bookmarked capability URLs from before the
restart are dead. This is by design (ADR-021: capability URLs are
ephemeral). Future Layer 1 authenticated users retrieve workspaces by
`UserId`, not by capability URL, so this limitation is transitional.

---

### Decision D6: Soft vs hard delete for expired workspaces

| Option | Description |
|--------|-------------|
| **D6-A: Soft delete** | Set `status = 'expired'`. Workspace row preserved. Tree data deleted from Irmin. Full audit trail in PG. |
| **D6-B: Hard delete** | `DELETE FROM workspaces WHERE ...`. Row gone. Irmin tree data deleted. Irmin commit history retains tree provenance. |

#### EU Regulatory and Data Privacy Analysis

The workspace row contains: `id` (ULID), `key_hash` (bcrypt digest),
`created_at`, `last_access`, `ttl`, `idle_timeout`, `status`. The
associated `workspace_trees` rows contain `workspace_id` and `tree_id`
(both ULIDs) plus `added_at`.

**Is any of this personal data under GDPR?**

In the current Layer 0 architecture (no authentication), workspaces
are anonymous. There is no `user_id`, email, IP address, or any other
identifier linking a workspace to a natural person. A ULID is a random
identifier with an embedded timestamp — it does not identify a person.
The `key_hash` is a one-way digest of a random token, also not
personal data.

However, once Layer 1 authentication lands (AUTHORIZATION-PLAN
L1.2), workspaces gain an `owner_id` linking them to a user account.
At that point the workspace row becomes **pseudonymous personal data**
under GDPR Art. 4(1) — the `owner_id` is an identifier that, combined
with the user table, relates to an identified natural person.

**Implications for each option:**

| Consideration | D6-A: Soft delete | D6-B: Hard delete |
|---------------|-------------------|-------------------|
| **GDPR Art. 5(1)(e) — storage limitation** | Retaining expired workspace rows indefinitely may conflict with the principle that personal data should be kept "no longer than necessary." Requires a documented retention period and scheduled purge job. | Compliant by default — data is gone when no longer needed. |
| **GDPR Art. 17 — right to erasure** | A user requesting deletion requires a purge of their soft-deleted rows. Must be implemented as an admin/automated operation. Forgetting to purge = non-compliance. | Already satisfied — row is deleted. |
| **GDPR Art. 30 — records of processing** | The soft-deleted manifest can serve as a processing record. However, GDPR does not require retaining *per-workspace* records — a processing register at the controller level suffices. | Irmin commit history still provides structural provenance (workspace ID in commit messages). Not as convenient as a PG table scan, but sufficient for audit. |
| **ePrivacy / data minimisation** | More data retained = larger attack surface. Soft-deleted rows with `key_hash` are low-risk (bcrypt is one-way, random input) but non-zero. | Minimal footprint. |
| **Operational audit** | Full manifest of all workspaces ever created. Easy `SELECT count(*)` reporting, capacity planning, abuse detection. | Lost unless separately logged. Irmin commit messages retain workspace IDs but are harder to query. |
| **Layer 1 transition** | When `owner_id` is added, existing soft-deleted rows (from Layer 0) have no owner — they remain anonymous and GDPR-neutral. New rows with `owner_id` require a retention policy. | No legacy rows to worry about. |

**Recommendation — conditional:**

- **Pre-Layer 1 (now):** D6-A is safe. No personal data exists in
  workspace rows. The audit manifest has operational value at zero
  regulatory cost.
- **Post-Layer 1:** D6-A requires a **retention policy** (e.g., purge
  soft-deleted rows after 90 days) and an erasure handler for Art. 17
  requests. If this operational commitment is unwanted, D6-B is the
  simpler compliant choice.

A pragmatic middle ground: **start with D6-A now** (Layer 0, no
personal data), and add a scheduled hard-purge job (`DELETE FROM
workspaces WHERE status = 'expired' AND last_access < now() -
interval '90 days'`) when Layer 1 lands. This preserves the audit
manifest for a bounded retention window while satisfying storage
limitation.

---

## 4 Files Inventory

### Phase 0 (Domain Model)

| File | Change |
|------|--------|
| `Workspace.scala` | Add `id: WorkspaceId` field |
| `WorkspaceStoreLive.scala` | Generate `WorkspaceId` in `create()`, secondary index |
| `WorkspaceStore.scala` | Add `resolveById` method |
| Test fixtures (~15 files) | Add `id` to `Workspace` constructors |

### Phase 1 (Scoped Paths)

| File | Change |
|------|--------|
| `RiskTreeRepository.scala` | Add `wsId` param to all methods (D2-A) |
| `RiskTreeRepositoryIrmin.scala` | Scoped paths, commit messages |
| `RiskTreeRepositoryInMemory.scala` | Accept `wsId`, enforce isolation (D3 pending — see §1.3) |
| `RiskTreeService.scala` + `RiskTreeServiceLive.scala` | Thread `wsId` |
| `WorkspaceController.scala` | Pass `ws.id` to service |
| `QueryController.scala` | Pass `wsId` to query service |
| All controller/service specs (~20 files) | Thread `wsId` |
| Integration tests (server-it) | Scoped path assertions |

### Phase 2 (Durable State)

| File | Change |
|------|--------|
| `build.sbt` | Wire existing `quillVersion` val into `serverDependencies`; add `postgresql`, `flyway-core`, `flyway-database-postgresql`, `zio-2-0-testcontainers-postgresql` |
| `Repository.scala` | **New** — centralized `quillLayer` + `dataSourceLayer` + `dataLayer` |
| `QuillMappings.scala` | **New** — Iron type ↔ SQL type `MappedEncoding` pairs |
| `WorkspaceStorePostgres.scala` | **New** — PG-backed `WorkspaceStore` impl |
| `FlywayService.scala` | **New** — trait + `FlywayServiceLive` |
| `FlywayConfig.scala` | **New** — `(url, user, password)` via `Configs.makeLayer[FlywayConfig]("register.flyway")` |
| `WorkspaceStoreConfig.scala` | **New** — backend selection config |
| `V1__workspace_schema.sql` | **New** — Flyway migration (DDL from §2.1) |
| `Application.scala` | `chooseWorkspaceStore` pattern, Flyway migration on boot, add `FlywayConfig.layer` |
| `docker-compose.yml` | Add PostgreSQL service under `persistence` profile |
| `application.conf` | Add `${?...}` env var overrides to existing `register.db` section; add new `register.flyway` section |
| `RepositorySpec.scala` | **New** — Testcontainers `DataSource` trait for tests |
| `WorkspaceStorePostgresSpec.scala` | **New** — repository unit tests |

### Phase 3 (Rehydration)

| File | Change |
|------|--------|
| `WorkspaceConfig.scala` | Add `rehydrateOnStartup` |
| `Application.scala` | Rehydration startup logic |
| Integration tests | Rehydration E2E |

### Phase 4 (Reaper)

| File | Change |
|------|--------|
| `WorkspaceReaper.scala` | Persistence-aware cascade |
| `WorkspaceReaperSpec.scala` | Enhanced tests |

### Phase 5 (Migration)

No code changes — operational: clear `risk-trees/` prefix from Irmin.

---

## 5 Implementation Order

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4
Domain       Scoped       PG store    Rehydrate   Reaper
model        paths                    on boot     aware
(~2h)        (~4h)        (~6h)       (~3h)       (~2h)
```

Phase 5 (migration) executes once at the Phase 1→2 boundary.
Each phase is independently deployable and testable.

---

## 6 Invariants and Security Properties

| Property | Guarantee |
|----------|-----------|
| `WorkspaceKeySecret` never in Irmin or PG plaintext | Secret exists only in `Ref[Map]` (or PG as hash, if D5-B). Never in Irmin paths, commits, or logs (ADR-022). |
| `WorkspaceId` is immutable | Generated once at creation. Survives `rotate()`. Survives server restart (if persisted). |
| Key rotation preserves identity | `rotate()` generates new key, copies same `WorkspaceId`. Irmin paths unchanged. Old key instantly dead. |
| Layered separation | `WorkspaceStore` = association/token index. `RiskTreeRepository` = domain content store. Connected only by `TreeId` references. Never cross-contaminated. |
| Irmin remains domain-only | No workspace lifecycle metadata in Irmin. Tree data uses `WorkspaceId` in paths for provenance only. |

---

## 7 Irmin Audit Value Chain

With workspace-scoped paths, every Irmin commit carries provenance:

```
workspace:01WSID..:risk-tree:01TREE..:create:a1b2c3d4:meta
workspace:01WSID..:risk-tree:01TREE..:update:e5f6g7h8:set-node:01ND..
workspace:01WSID..:risk-tree:01TREE..:delete:remove-node:01ND..
```

Admin with Irmin GraphQL access can:
- List all workspaces: query `workspaces/` prefix
- Browse tree data: navigate `workspaces/{id}/risk-trees/...`
- View full history: Irmin commit log — content-addressed, immutable
- Time-travel: query at any historical commit hash

None of this requires a `WorkspaceKeySecret`.

---

## 8 Relationship to Other Plans

| Plan / ADR | Relationship |
|------------|-------------|
| AUTHORIZATION-PLAN Phase K.3 | PostgreSQL on K8s. PG lands in dev-compose first (this plan); K8s deployment follows K.3 timeline. |
| AUTHORIZATION-PLAN Task L1.2 | Workspace ownership (`owner_id`). Requires `WorkspaceId` in the model (Phase 0 of this plan). |
| AUTHORIZATION-PLAN L552–569 | `WorkspaceId` for SpiceDB. Phase 0 is a prerequisite. |
| ADR-INFRA-006 | SOPS-encrypted PG credentials for K8s. Dev-compose uses plaintext env vars (standard for local dev). |
| IRMIN-INTEGRATION.md Phase 7 | Branches for what-if analysis. Orthogonal to this plan. Workspace-scoped paths don't affect branching. |
| PLAN-QUERY-RESULT-VISUALIZATION | Error reporting work stream. Independent. |

---

## 9 Summary of Required Decisions

| ID | Question | Options | Status |
|----|----------|---------|--------|
| D1 | When does PostgreSQL land? | **A: Now** | **Decided** |
| D2 | How does `WorkspaceId` reach the repo? | **A: Explicit param** | **Decided** |
| D3 | In-memory repo workspace isolation? | A: Ignore / B: Enforce | Pending — lean B |
| D4 | `getAll` replacement strategy? | **A: Replace only** | **Decided** |
| D5 | Key persistence on rehydration? | A: Fresh / B: Hash / C: Encrypted | Pending — lean A |
| D6 | Soft vs hard delete? | A: Soft / B: Hard | Pending — see GDPR analysis |

All five phases proceed (D1-A). D3 affects Phase 1 in-memory impl.
D5–D6 affect Phases 2–4.
