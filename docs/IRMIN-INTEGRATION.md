# Irmin Integration Guide

This document provides an overview of Irmin and how we integrate it with our Scala/ZIO application.

---

## Table of Contents

1. [What is Irmin?](#what-is-irmin)
2. [Core Features](#core-features)
3. [Why Irmin for Risquanter Register](#why-irmin-for-risquanter-register)
4. [Current & Planned Usage](#current--planned-usage)
5. [Scala Integration Architecture](#scala-integration-architecture)
6. [Key Scala Classes](#key-scala-classes)
7. [GraphQL API Reference](#graphql-api-reference)

---

## What is Irmin?

**Irmin** is an OCaml library for building distributed, content-addressed data stores. Think of it as "Git for your application data" — every change creates an immutable commit, branches can diverge and merge, and the full history is preserved.

### Key Concepts

| Concept | Description | Analogy |
|---------|-------------|---------|
| **Store** | The database instance | Git repository |
| **Branch** | Named pointer to a commit | Git branch |
| **Commit** | Immutable snapshot of all data at a point in time | Git commit |
| **Tree** | Hierarchical structure of paths and values | File system |
| **Path** | Location in the tree (e.g., `risks/cyber/severity`) | File path |
| **Contents** | Value stored at a path | File contents |
| **Hash** | Content-addressed identifier | Git SHA |

### Content-Addressed Storage

Irmin uses **content-addressed storage** — identical data always produces the same hash. This has implications:

```
Write "hello" to path /a → hash abc123
Write "hello" to path /b → same value, references abc123
Write "hello" to path /a again → no new commit (value unchanged)
```

This is important for testing: writing the same value twice doesn't create a new commit.

---

## Core Features

### 1. Immutable History

Every mutation creates a new commit. Previous states are never lost:

```
Commit 1: { risks/cyber: 0.3 }
Commit 2: { risks/cyber: 0.5 }  ← current
          ↑
     Can still access Commit 1
```

### 2. Branching & Merging

Multiple branches can exist simultaneously, enabling:
- **Scenario analysis**: "What if cyber risk increases by 50%?"
- **Draft editing**: Edit without affecting main view
- **Collaboration**: Merge changes from multiple users

```
main:     A → B → C
                   \
scenario:           D → E  (what-if analysis)
```

### 3. GraphQL API

Irmin exposes its operations via GraphQL, enabling language-agnostic access:

```graphql
# Query: read a value
{ main { tree { get(path: "risks/cyber") } } }

# Mutation: write a value
mutation { set(path: "risks/cyber", value: "0.5", info: {message: "Update"}) { hash } }

# Subscription: watch for changes
subscription { watch(path: "risks") { commit { hash } } }
```

### 4. Watch Notifications

Irmin can notify clients when data changes, enabling reactive updates:

```
Client subscribes → Irmin watches tree
Another client writes → Irmin emits event
Subscriber receives → Can invalidate cache, update UI
```

---

## Why Irmin for Risquanter Register

Our risk register application has specific requirements that Irmin addresses:

| Requirement | Irmin Solution |
|-------------|----------------|
| **Audit trail** | Immutable commit history with author/message |
| **Scenario branching** | Native branch/merge support |
| **Collaborative editing** | Optimistic locking via version hashes |
| **Real-time updates** | GraphQL subscriptions for change notifications |
| **Reproducibility** | Commits are content-addressed and immutable |
| **Hierarchical data** | Tree structure mirrors risk hierarchy |

---

## Current & Planned Usage

### Phase 2 (Current): Basic CRUD via GraphQL

What's implemented now:
- Read values (`get`)
- Write values (`set`)
- Remove values (`remove`)
- List branches
- Health check

### Phase 3: Tree Index & Cache

*Coming next* — Internal cache structure that uses Irmin as source of truth:
- Parent-pointer index for O(depth) invalidation
- LEC (Loss Exceedance Curve) cache keyed by path

**Path Convention (Planned):**
Irmin paths will encode `treeId` to enable tree-scoped operations:
```
/risk-trees/{treeId}/nodes/{nodeId}

Example:
/risk-trees/1/nodes/cyber-risk      ← treeId=1, nodeId="cyber-risk"
/risk-trees/1/nodes/ops-risk        ← treeId=1, nodeId="ops-risk"  
/risk-trees/2/nodes/cyber-risk      ← treeId=2 (different tree!)
```

This ensures:
- Irmin watch notifications include treeId (parseable from path)
- Cache invalidation targets the correct tree's cache
- Node IDs (SafeId) only need to be unique within a tree, not globally

**Note:** Irmin branches are reserved for scenario/what-if analysis (Phase 7), not for tree isolation.

### Phase 4: SSE Infrastructure

Server-Sent Events for pushing updates to browsers:
- Irmin change → Cache invalidation → SSE broadcast
- Browser receives real-time LEC updates

### Phase 5: Cache Invalidation Pipeline

Connect Irmin watch notifications to our cache:
- Subscribe to Irmin `watch` (GraphQL subscription)
- Invalidate affected cache entries
- Recompute and broadcast updated LECs

### Phase 6: Collaboration

Multi-user conflict detection:
- Track `baseVersion` (commit hash) on edits
- Detect concurrent modifications
- Merge or conflict resolution

### Phase 7: Scenario Branching

Leverage Irmin branches for what-if analysis:
- Create branch from current state
- Modify parameters
- Compare results
- Optionally merge back

---

## Scala Integration Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     ZIO Application                         │
├─────────────────────────────────────────────────────────────┤
│  IrminClient (trait)                                        │
│    ├── get(path): Task[Option[String]]                      │
│    ├── set(path, value, message): Task[IrminCommit]         │
│    ├── remove(path, message): Task[IrminCommit]             │
│    └── branches: Task[List[String]]                         │
├─────────────────────────────────────────────────────────────┤
│  IrminClientLive (implementation)                           │
│    └── sttp HTTP client → GraphQL POST requests             │
├─────────────────────────────────────────────────────────────┤
│                    HTTP / GraphQL                           │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  Irmin GraphQL Server                       │
│                  (Docker container, port 9080)              │
├─────────────────────────────────────────────────────────────┤
│  irmin-graphql + irmin-pack                                 │
│  Storage: content-addressed pack files                      │
└─────────────────────────────────────────────────────────────┘
```

### Error Mapping

Network/Irmin errors are mapped to our `SimulationError` hierarchy:

| Error Condition | Scala Type | HTTP Status |
|-----------------|------------|-------------|
| Connection refused | `IrminUnavailable` | 503 |
| Request timeout | `NetworkTimeout` | 504 |
| Version conflict | `VersionConflict` | 409 |
| Merge conflict | `MergeConflict` | 409 |

---

## Key Scala Classes

### `IrminConfig`

**Location:** `configs/IrminConfig.scala`

Configuration for connecting to Irmin. Loaded from `application.conf`.

```scala
final case class IrminConfig(
    endpoint: String,        // e.g., "http://localhost:9080"
    branch: String = "main", // Default branch
    timeoutSeconds: Int = 30
)
```

**Irmin context:** The `endpoint` points to the `irmin-graphql` server. The `branch` specifies which named branch to use for operations (Irmin supports multiple branches like Git).

**Usage:**
```scala
// Layer from application.conf
val layer = IrminConfig.layer

// Or provide directly in tests
val testConfig = IrminConfig("http://localhost:9080")
```

---

### `IrminPath`

**Location:** `infra/irmin/model/IrminPath.scala`

Type-safe, Iron-refined path for navigating Irmin's tree structure.

```scala
opaque type IrminPath = String :| Match["^$|^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$"]
```

**Irmin context:** Irmin stores data in a hierarchical tree, similar to a file system. Paths like `risks/cyber/severity` navigate this tree. The root is represented by an empty string `""`.

**Validation rules:**
- Empty string = root
- Segments separated by `/`
- No leading or trailing slashes
- Alphanumeric, hyphen, underscore only

**Usage:**
```scala
// Safe construction (returns Either)
val path = IrminPath.from("risks/cyber")  // Right(IrminPath)
val bad  = IrminPath.from("/invalid")     // Left("Invalid Irmin path...")

// Unsafe (throws on invalid input) — for tests/literals only
val path = IrminPath.unsafeFrom("risks/cyber")

// Root path
val root = IrminPath.root

// Path operations
path.value                    // "risks/cyber"
path.parent                   // Some(IrminPath("risks"))
path.name                     // Some("cyber")
path.segments                 // List("risks", "cyber")
path / "severity"             // Right(IrminPath("risks/cyber/severity"))
```

---

### `IrminCommit`

**Location:** `infra/irmin/model/IrminCommit.scala`

Represents an Irmin commit — a point-in-time snapshot with metadata.

```scala
final case class IrminCommit(
    hash: String,           // Content-addressed hash (like Git SHA)
    key: String,            // Internal key (may differ in packed format)
    parents: List[String],  // Parent commit hashes
    info: IrminInfo         // Author, message, timestamp
)

final case class IrminInfo(
    date: String,    // Epoch timestamp
    author: String,  // e.g., "zio-client"
    message: String  // Commit message
)
```

**Irmin context:** Every mutation (`set`, `remove`) creates a new commit. Commits form a DAG (directed acyclic graph) where each commit points to its parents. The `hash` is the content-addressed identifier — identical trees produce identical hashes.

**Usage:**
```scala
for
  commit <- IrminClient.set(path, value, "Updated risk value")
  _      <- ZIO.logInfo(s"Committed: ${commit.hash}")
  _      <- ZIO.logInfo(s"Author: ${commit.info.author}")
yield ()
```

---

### `IrminClient` (Trait)

**Location:** `infra/irmin/IrminClient.scala`

ZIO service interface for Irmin operations. All methods return `Task` (ZIO with Throwable error channel).

```scala
trait IrminClient:
  def get(path: IrminPath): Task[Option[String]]
  def set(path: IrminPath, value: String, message: String): Task[IrminCommit]
  def remove(path: IrminPath, message: String): Task[IrminCommit]
  def branches: Task[List[String]]
  def mainBranch: Task[Option[IrminBranch]]
  def healthCheck: Task[Boolean]
```

**Irmin context:**
- `get`: Queries `main { tree { get(path) } }` — reads from the current head of main branch
- `set`: Executes `mutation { set(...) }` — creates new commit with the value
- `remove`: Executes `mutation { remove(...) }` — creates commit that deletes the path
- `branches`: Lists all branch names in the store
- `mainBranch`: Gets the `main` branch with its head commit

**Usage (ZIO service pattern):**
```scala
for
  maybeValue <- IrminClient.get(path)
  _          <- maybeValue match
                  case Some(v) => ZIO.logInfo(s"Found: $v")
                  case None    => ZIO.logInfo("Not found")
yield ()
```

---

### `IrminClientLive`

**Location:** `infra/irmin/IrminClientLive.scala`

Implementation of `IrminClient` using sttp HTTP client with ZIO backend.

```scala
val layer: ZLayer[IrminConfig, Throwable, IrminClient]
val layerWithConfig: ZLayer[Any, Throwable, IrminClient]
```

**Key implementation details:**

1. **GraphQL over HTTP**: Sends POST requests to `/graphql` endpoint
2. **Error mapping**: Network errors → `IrminUnavailable` / `NetworkTimeout`
3. **No retry logic**: Delegated to service mesh (per ADR-012)
4. **Scoped backend**: HTTP client lifecycle managed by ZIO

**Usage:**
```scala
// With config from application.conf
val program = myLogic.provide(IrminClientLive.layerWithConfig)

// With explicit config
val program = myLogic.provide(
  ZLayer.succeed(testConfig) >>> IrminClientLive.layer
)
```

---

### `IrminQueries`

**Location:** `infra/irmin/IrminQueries.scala`

Raw GraphQL query and mutation strings. Used internally by `IrminClientLive`.

```scala
object IrminQueries:
  def getValue(path: IrminPath): String
  def setValue(path: IrminPath, value: String, message: String, author: String): String
  def removeValue(path: IrminPath, message: String, author: String): String
  val listBranches: String
  val getMainBranch: String
```

**Irmin context:** These generate GraphQL documents that match Irmin's schema. For example:

```graphql
# getValue generates:
{ main { tree { get(path: "risks/cyber") } } }

# setValue generates:
mutation {
  set(path: "risks/cyber", value: "0.5", info: {message: "Update", author: "zio-client"}) {
    hash
    key
    info { date author message }
  }
}
```

---

## GraphQL API Reference

The full schema is at `dev/irmin-schema.graphql`. Key operations:

### Queries

```graphql
# Get value at path
{ main { tree { get(path: "risks/cyber") } } }

# List all branches
{ branches { name } }

# Get branch with head commit
{ main { name head { hash info { date author message } } } }
```

### Mutations

```graphql
# Set value
mutation {
  set(path: "risks/cyber", value: "0.5", info: {message: "Update"}) {
    hash
  }
}

# Remove value
mutation {
  remove(path: "risks/cyber", info: {message: "Cleanup"}) {
    hash
  }
}
```

### Subscriptions (Future - Phase 5)

```graphql
# Watch for changes
subscription {
  watch(path: "risks") {
    commit { hash info { date message } }
  }
}
```

---

## Testing Notes

### Content-Addressing Implications

When writing tests, be aware that Irmin's content-addressed storage means:

1. **Same value = same commit**: Writing identical values to the same path may return the existing commit rather than creating a new one
2. **Use unique paths**: Tests should use timestamped paths to avoid collisions:
   ```scala
   for
     ts   <- ZIO.clockWith(_.instant).map(_.toEpochMilli)
     path  = IrminPath.unsafeFrom(s"test/mytest/$ts")
   yield path
   ```

3. **Container state persists**: The Irmin container retains data between test runs. For full isolation, restart the container or use unique paths.

### Running Integration Tests

```bash
# Start Irmin container
docker compose --profile persistence up -d

# Run tests
sbt "server/testOnly *IrminClientIntegrationSpec"

# Check container health
docker compose --profile persistence ps
```

---

*Document created: 2026-01-17*
*Last updated: 2026-01-17*
