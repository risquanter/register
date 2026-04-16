# Plan: Workspace-Scoped Persistence

**Status:** Draft — decisions D1–D5 decided, D6 pending  
**Scope:** Planning only — no code changes  
**Date:** 2026-04-15 (decisions updated 2026-04-17)

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
4. **Lazy cache population:** `Ref[Map]` starts empty on boot;
   populated on-demand from PG when users present capability URLs.

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

#### 1.3 `RiskTreeRepositoryInMemory` ← **Decision D3: Enforce** (decided)

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
  key_hash    TEXT NOT NULL UNIQUE,  -- SHA-256 hex digest of WorkspaceKeySecret
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

**Note on `key_hash`:** SHA-256 hex digest of the `WorkspaceKeySecret`.
One-way — the raw key cannot be recovered from the hash, satisfying
ADR-022's secret handling principles. SHA-256 (not bcrypt) is correct
here because the input is 128-bit `SecureRandom` with full entropy —
no need for bcrypt's slow-by-design password stretching. The hash is
deterministic and indexable, enabling O(1) lookups when users present
capability URLs. See Decision D5 (decided: SHA-256 hash + lazy cache).

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

The `Ref[Map]` remains as a **read-through cache** in front of PG
for O(1) hot-path lookups. PG is the durable source of truth. Cache
starts empty on boot; populated lazily on first access per workspace
(see Phase 3).

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

### Phase 3: Lazy Cache Population

**Goal:** Ensure `Ref[Map]` serves as a fast read-through cache in
front of PG, populated on-demand. No startup ceremony required.

#### 3.1 Startup Sequence (PostgreSQL-backed)

```
Server starts
  → Flyway migrations run (idempotent)
  → Ref[Map] initialized empty
  → Server begins accepting HTTP traffic
```

No workspace data is loaded at startup. The cache populates lazily:

```
User presents GET /w/{key}/risk-trees
  → Ref[Map] lookup: miss
  → SELECT * FROM workspaces w
      JOIN workspace_trees wt ON w.id = wt.workspace_id
      WHERE w.key_hash = encode(sha256($key), 'hex')
        AND w.status = 'active'
  → Check TTL/idle expiry
  → If valid: populate Ref[Map], serve request
  → If expired: UPDATE status = 'expired', return 410
  → If not found: return 404
```

#### 3.2 Why Not Proactive Loading?

The earlier draft considered loading all workspaces into `Ref[Map]`
at startup ("rehydration"). This is unnecessary because:

- **No use case requires it.** The server has no reason to know about
  a workspace before a user presents its capability URL. Access is
  always user-initiated.
- **Lazy population is simpler.** No startup delay, no config toggle,
  no "hold traffic until loaded" synchronization.
- **Cache semantics are clean.** `Ref[Map]` is a pure cache. PG is
  the source of truth. Cache miss = PG query. Eviction from cache
  is safe (key presented again → re-cached).

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

Workspaces already marked `expired` in PG are ignored on cache miss
lookups. Their Irmin tree data is cleaned up by the Reaper.

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

> **Decided: D3-B — Enforce isolation.**

Key `TrieMap` by `(WorkspaceId, TreeId)`. Catches scoping bugs — a
forgotten or wrong `wsId` returns `None` instead of silently leaking
another workspace's tree. Minimal added complexity (tuple key).

---

### Decision D4: `getAll` replacement strategy

> **Decided: D4-A — Replace only.**

Remove `getAll`, add `getAllForWorkspace(wsId)` only. No global scan on
the repository trait. `getAll` has no use case in workspace-scoped
operation. An admin endpoint can be added later outside the trait.

---

### Decision D5: Key persistence strategy

> **Decided: SHA-256 hash with lazy cache population.**

Store `SHA-256(WorkspaceKeySecret)` in PG as an **indexed** column.
`Ref[Map]` starts empty on boot. When a user presents a capability
URL, the server:

1. Check `Ref[Map]` — cache hit → serve immediately
2. Cache miss → `SELECT * FROM workspaces WHERE key_hash = sha256($key)`
3. PG hit → populate `Ref[Map]` entry, serve request
4. PG miss → 404 (unknown workspace)

Old URLs survive server restarts. No key regeneration. No startup
ceremony. No key management infrastructure.

#### Why SHA-256, Not bcrypt

The original draft assumed `bcrypt(key)` — designed for **low-entropy
human passwords** (intentionally slow to resist brute-force). But
`WorkspaceKeySecret` is 128-bit `SecureRandom` with full entropy.
SHA-256 provides 128-bit preimage resistance against this input —
identical security to bcrypt — with two critical advantages:

1. **Indexable.** SHA-256 output is deterministic and fixed-length →
   standard B-tree index → O(1) lookup per request.
2. **No O(n) scan.** bcrypt includes a per-row salt, requiring each
   stored hash to be checked individually. SHA-256 is a single indexed
   query.

```sql
-- O(1) lookup via B-tree index on key_hash
SELECT * FROM workspaces WHERE key_hash = encode(sha256($key), 'hex');
```

#### Why Not Envelope Encryption

Envelope encryption (`AES-GCM(key)` in PG, decrypt at startup,
populate `Ref[Map]`) was evaluated. It offers no practical benefit:
the server never needs to know keys before a user presents a URL.
"Instant access on restart" is meaningless — access is always
user-initiated. Envelope encryption adds key management complexity
(master key in env/KMS/file) for zero functional gain over hashing.

| | SHA-256 hash | Envelope encryption |
|---|---|---|
| Lookup on first request | O(1) indexed query | O(1) map lookup (pre-populated) |
| Key management burden | None | Must manage master encryption key |
| Complexity | Low | Medium |
| Old URLs survive restart | Yes | Yes |

#### ADR-022 Alignment

ADR-022 mandates that secrets not be stored in plaintext. SHA-256 is
a one-way function — the raw key cannot be recovered from the hash.
This satisfies ADR-022 while maintaining O(1) lookup and full URL
survivability across restarts.

> **ADR note:** This analysis is ready to be incorporated into an ADR
> (or appended to ADR-022) when implementation proceeds.

#### Crash Recovery (Simplified)

With PG as durable store and lazy cache population, crash recovery is
straightforward: **server restarts → cache is empty → users show up →
cache populates from PG.**

| Crash scenario | Outcome |
|----------------|---------|
| OOM / SIGKILL during normal operation | PG state intact. Users present URLs, cache repopulates. Workspaces past TTL/idle are expired on next access or Reaper cycle. |
| Crash during `create` | PG insert committed → workspace accessible. Not committed → user sees failed request, retries. No orphans. |
| Crash during `addTree` | PG insert committed → association intact. Not committed → tree may exist in Irmin as orphan, Reaper cleans up. |
| Crash during `evictExpired` | Partial commit. Already-expired workspaces stay expired. Others continue as active. Reaper resumes on next cycle. |
| PostgreSQL crash | PG WAL ensures durability. On PG recovery, everything accessible. |

**Downtime duration effect:** If the server is down longer than a
workspace's remaining TTL or idle timeout, that workspace is naturally
expired. The user creates a new one. This is correct behaviour — no
special handling needed.

---

### Decision D6: Soft vs hard delete for expired workspaces

| Option | Description |
|--------|-------------|
| **D6-A: Soft delete** | Set `status = 'expired'`. Workspace row preserved in PG. Tree data removed from Irmin current state (commit history retains it). |
| **D6-B: Hard delete** | `DELETE FROM workspaces WHERE ...`. PG row gone. Irmin current tree data removed (commit history retains it). |

#### EU Regulatory and Data Privacy Analysis

##### What data exists where

| Location | Content | Personal data? |
|----------|---------|----------------|
| **PG `workspaces` row** | `id` (ULID), `key_hash` (SHA-256), timestamps, status | **No** at Layer 0. **Yes** when `owner_id` added (Layer 1+) — becomes pseudonymous under GDPR Art. 4(1). |
| **PG `workspace_trees`** | `workspace_id`, `tree_id` (ULIDs), `added_at` | **No** — system-generated identifiers with no person link. |
| **Irmin current state** | Risk node names, L/E/C values, tree structure | **No** — domain model data (risk assessments), not data about a person. |
| **Irmin commit history** | Workspace ID, tree ID, operation type, timestamp in commit messages. Full prior tree state in parent commits. | **No** — same content as current state, plus system-generated metadata. No person identifiers. |

Under normal operating conditions, **Irmin data is not personal
data.** Risk trees describe risk assessments, not natural persons.
Irmin paths and commit messages contain only system-generated ULIDs
and operation labels. This holds at both Layer 0 and Layer 1+ —
`owner_id` lives exclusively in PG, never in Irmin.

**Edge case:** If a user types personally identifying information
into a risk node name (e.g., "Jane Smith's department risk"), that
content becomes personal data. This is a content-level concern, not
a structural one, and is addressed under "Irmin and the right to
erasure" below.

##### Irmin and the right to erasure — legal precedents

Irmin is content-addressed and append-only. `remove()` creates a new
commit without the data at the current path, but **all prior commits
still contain the data.** This is architecturally identical to Git.
A "delete" in Irmin is not an erasure — it is a state transition.

Relevant regulatory guidance:

1. **GDPR Art. 17(1) — Right to erasure.** The controller must erase
   personal data "without undue delay." The regulation does not define
   "erase" technically, but EDPB guidance clarifies intent.

2. **EDPB Guidelines 5/2019 on Art. 17, §2.3.** Erasure means data
   must be "put beyond any possibility of retrieval." This applies to
   **all copies** including backups, replicas, and archives. However,
   the EDPB acknowledges technical constraints on immutable media:
   when true erasure is impracticable, the controller must apply
   additional protections and delete when feasible.

3. **UK ICO "putting beyond use" doctrine.** When immediate erasure
   is technically impracticable (immutable storage, backup tapes),
   data may be "put beyond use" as an **interim measure** if:
   - The data is not used for any purpose
   - No access is given to any third party
   - Appropriate technical/organizational measures prevent access
   - Deletion occurs as soon as practicable
   This is a pragmatic concession, not a permanent exemption.

4. **CJEU C-131/12 (Google Spain).** Established that erasure must be
   "effective and complete." Applied to search engine delisting, not
   immutable stores, but the principle stands: data must become
   practically inaccessible to those who could use it.

5. **Anonymisation by severance (EDPB Opinion 05/2014 on
   Anonymisation Techniques).** If you destroy the key that links
   pseudonymous data to an identity, the remaining data is no longer
   personal data and is no longer subject to Art. 17. This is the
   pattern that applies to our architecture.

**How this applies to register:**

- **Layer 0 (now):** No personal data anywhere. Art. 17 does not
  apply. Irmin history retention is regulatory-neutral.
- **Layer 1+ with `owner_id` in PG:** The only person-linkable data
  is the `owner_id` column in PG. Deleting the PG row (or nulling
  `owner_id`) **severs the identity link.** After severance, Irmin
  data containing `workspace_id` (a ULID) cannot be linked to a
  natural person → it becomes anonymised → Art. 17 is satisfied.
- **If tree content itself contains personal data:** Irmin's immutable
  history retains it. The "put beyond use" interim measure applies:
  restrict API access to history, document a retention period, and
  investigate Irmin GC/compaction when feasible. Alternatively,
  terms of service can prohibit personal data in risk tree content
  (shifts controller obligation but does not eliminate it).

**Takeaway:** Under normal circumstances, Irmin history is not subject
to GDPR erasure obligations because it does not contain personal data.
The identity link (`owner_id`) lives exclusively in PG and can be
fully erased there. D6 is therefore primarily an **operational**
decision, not a regulatory one.

##### Soft delete vs hard delete — comparative analysis

| Dimension | D6-A: Soft delete | D6-B: Hard delete |
|-----------|-------------------|-------------------|
| **GDPR Art. 17 compliance** | Requires purge job for Art. 17 requests (Layer 1+). Forgetting to purge = non-compliance. | Compliant by default — row is gone. |
| **GDPR Art. 5(1)(e) storage limitation** | Requires documented retention period and scheduled purge (Layer 1+). | Satisfied automatically. |
| **Data minimisation** | More data retained = larger attack surface. Low-risk (SHA-256 hashes, ULIDs) but non-zero. | Minimal PG footprint. |
| **Operational audit / analytics** | Full manifest of all workspaces ever created. Easy `SELECT count(*), min(created_at), max(created_at)` for capacity planning, abuse detection, usage trends. | Lost unless separately logged. Requires a dedicated audit log table or external system. |
| **Workspace recovery / undo** | Expired workspace can be reactivated (`status = 'active'`, reset TTL) if the user still has the key. Useful during grace period. | Deletion is permanent. No undo. User must create a new workspace. |
| **Reaper simplicity** | Reaper sets `status = 'expired'` — single UPDATE. Separate purge job handles final deletion. Two-phase approach. | Reaper does `DELETE` — single operation. Simpler control flow. |
| **Schema evolution** | Soft-deleted rows accumulate. Table grows unboundedly without purge. Index bloat on `status` column if many expired rows. | Table size stays proportional to active workspaces only. |
| **Cascade to Irmin** | Irmin tree data can be removed lazily (Reaper cleans up after marking expired). Temporal decoupling between PG state change and Irmin cleanup. | Irmin tree data removed at same time as PG row. If Irmin removal fails, PG row is already gone — orphan risk. |
| **Debugging / incident response** | "Why did this workspace disappear?" → query PG for expired rows. Workspace ID, timestamps, tree associations all preserved. | "Why did this workspace disappear?" → nothing in PG. Must search Irmin commit history (harder, less structured). |
| **Layer 1 transition** | Existing expired rows from Layer 0 have no `owner_id` — remain anonymous and GDPR-neutral. New Layer 1 rows with `owner_id` require a retention policy. | Clean slate — no legacy rows. |
| **Implementation cost** | `status` column already in schema. Purge job adds ~50 lines. | Simpler — standard `DELETE`. No purge job needed. |

**Recommendation — conditional:**

- **Pre-Layer 1 (now):** D6-A is safe. No personal data exists in
  workspace rows. The audit manifest has operational value (debugging,
  analytics, undo) at zero regulatory cost.
- **Post-Layer 1:** D6-A requires a **retention policy** (e.g., purge
  after 90 days) and an Art. 17 erasure handler. If this operational
  commitment is unwanted, D6-B is the simpler compliant choice.

A pragmatic approach: **start with D6-A now** (Layer 0, no personal
data) and add a scheduled hard-purge job (`DELETE FROM workspaces
WHERE status = 'expired' AND last_access < now() - interval '90
days'`) when Layer 1 lands. This preserves the audit manifest for a
bounded retention window while satisfying storage limitation.

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
| `RiskTreeRepositoryInMemory.scala` | Accept `wsId`, enforce isolation (D3-B, decided — see §1.3) |
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

### Phase 3 (Lazy Cache)

| File | Change |
|------|--------|
| `WorkspaceStorePostgres.scala` | Add `resolveByKeyHash` query (cache-miss path) |
| `WorkspaceStoreLive.scala` | Cache-miss → PG fallback logic in `resolve()` |
| Integration tests | Cache miss → PG lookup E2E |

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
Domain       Scoped       PG store    Lazy        Reaper
model        paths                    cache       aware
(~2h)        (~4h)        (~6h)       (~2h)       (~2h)
```

Phase 5 (migration) executes once at the Phase 1→2 boundary.
Each phase is independently deployable and testable.

---

## 6 Invariants and Security Properties

| Property | Guarantee |
|----------|-----------|
| `WorkspaceKeySecret` never in Irmin or PG plaintext | Secret exists only in `Ref[Map]` (cache) and PG as SHA-256 hash (D5, decided). Never in Irmin paths, commits, or logs (ADR-022). Raw key cannot be recovered from hash. |
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
| D3 | In-memory repo workspace isolation? | **B: Enforce** | **Decided** |
| D4 | `getAll` replacement strategy? | **A: Replace only** | **Decided** |
| D5 | Key persistence strategy? | **SHA-256 hash + lazy cache** | **Decided** |
| D6 | Soft vs hard delete? | A: Soft / B: Hard | Pending — see GDPR analysis |

All five phases proceed (D1-A). D3-B affects Phase 1 in-memory impl.
D5 (SHA-256 + lazy cache) simplifies Phases 2–3. D6 affects Phase 4.
