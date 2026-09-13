# Plan: release a workspace's in-memory caches when the workspace is torn down

**Status:** Ruled, awaiting approval as the session's governing plan.
**Date:** 2026-09-13.
**ADR reference:** ADR-021 (workspace lifetime — the absolute and idle timeouts
that make teardown routine); ADR-015 (content-addressed cache); ADR-030 and
ADR-024 (the per-workspace tenancy isolation the registries carry, which this
change preserves).

Scoped deliberately to one growth: memory retained for workspaces that no longer
exist. Eviction *within* a live workspace's cache is a different problem with a
different answer and is not touched here.

---

## Objective

Two registries hand out one per-workspace instance of a service, creating it on
first request. Neither ever removes one.

- `ContentCacheRegistry` holds `Ref[Map[SeedEntityId, ContentCache]]`, one
  simulation-result cache per workspace.
- `MitigationScopeResolverRegistry` holds
  `Ref[Map[WorkspaceId, MitigationScopeResolver]]`, one mitigation-scope
  resolver, and therefore one memo, per workspace.

A workspace is torn down on three paths, all of which already exist and all of
which route through one helper, `CascadeDelete.workspace`: an explicit delete
(`WorkspaceLifecycleController.deleteWorkspace`), an admin-triggered sweep
(`WorkspaceLifecycleController.evictExpired`), and the background reaper
(`WorkspaceReaper`, every five minutes by default). That helper deletes the
workspace's trees and its scenario branches. **It does not touch either
registry.**

So when a workspace expires — 72 hours absolute or 1 hour idle by default — its
simulation results and its resolved mitigation scopes stay in memory, reachable
by nothing, until the process restarts. The server container is limited to
256 MB.

The current code states the intent and concedes the gap in the same sentence, in
`CacheScope`'s scaladoc:

> Cache lifecycle = workspace lifecycle; a deleted workspace's cache lingers
> until restart (NoOp eviction).

The first clause is the design. The second is the behaviour. This plan makes the
first clause true and deletes the second.

### Why this needs no measurement to justify

The usual reason to defer a memory change is that you cannot tell whether the
retained data is worth keeping. That question does not arise here. Once the
workspace is gone from the store, every subsequent request for it fails at
`workspaceStore.resolve` before reaching any cache, so **no future request can
ever read these entries.** The memory is provably unreachable, not merely
unlikely to be used. Releasing it trades away nothing.

This is what separates it from the two eviction questions it sits beside, both
of which do trade away cache hits and therefore do need a policy and a bound:
within-cache eviction for `ContentCache` (`IMPLEMENTATION-PLAN.md`, "Eviction
Strategy") and the bounded revision cache for the mitigation scope memo
(TODO 49). Neither is in scope.

---

## Background — the pieces, for a reader new to this area

**A workspace** groups a set of risk trees under one capability key. It carries
two identifiers that matter here, both on `WorkspaceRecord`: `id`, a
`WorkspaceId`, and `seedEntityId`, the workspace's stochastic identity used as
the random generator's Entity axis. The two registries are keyed differently —
the cache registry by `seedEntityId`, the resolver registry by `id` — so
releasing needs both.

**A registry** is a keyed collection returning an existing instance or creating
one on first request. Instances are per workspace so that one workspace's
simulation results and resolved scopes are unreachable from another's; that
separation is an approved tenancy property and this plan does not alter it.

**The reaper** is a background fiber that wakes on an interval, asks the store
which workspaces have expired, and cascades their deletion. `evictExpired`
returns the full `WorkspaceRecord` of each evicted workspace, so both
identifiers are already in hand at every teardown site.

**`CascadeDelete.workspace`** exists precisely because this composition was once
inlined at all three sites. Its scaladoc calls itself "the single source of
truth for 'delete everything belonging to a workspace'". Releasing the caches
belongs there for the same reason the tree and scenario deletion does.

---

## Exact signatures

### New service — `WorkspaceCacheRelease`

New file
`modules/server/src/main/scala/com/risquanter/register/services/cache/WorkspaceCacheRelease.scala`.

```scala
package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, SeedEntityId}

/** Drops every in-memory registry entry belonging to one workspace. Called
  * where a workspace is torn down, so a workspace that no longer exists holds
  * no memory. Releasing is safe unconditionally: once the workspace is gone
  * from the store, no request can reach these entries. */
trait WorkspaceCacheRelease:
  def release(
    workspaceId:  WorkspaceId,
    seedEntityId: SeedEntityId.SeedEntityId
  ): UIO[Unit]

object WorkspaceCacheRelease:
  val layer: ZLayer[ContentCacheRegistry & MitigationScopeResolverRegistry, Nothing, WorkspaceCacheRelease]

final case class WorkspaceCacheReleaseLive(
  contentCaches:  ContentCacheRegistry,
  scopeResolvers: MitigationScopeResolverRegistry
) extends WorkspaceCacheRelease:
  override def release(
    workspaceId:  WorkspaceId,
    seedEntityId: SeedEntityId.SeedEntityId
  ): UIO[Unit]
```

**Why a service rather than passing both registries into `CascadeDelete`.**
`CascadeDelete.workspace` already takes four parameters plus a using clause;
adding two registries and a second identifier would take it to seven, and every
future registry would widen it again. One parameter naming one concept — release
this workspace's in-memory state — keeps the call sites readable, is stubbable
the way `CascadeTestStubs` already stubs the other two services, and confines a
third registry's arrival to one file. This is a judgement, not a ruled point,
and it is recorded here so it can be reversed.

### `ContentCacheRegistry` gains one method

```scala
  /** Drop this workspace's cache. A later request for the same workspace gets a
    * new, empty one. */
  def release(seedEntityId: SeedEntityId.SeedEntityId): UIO[Unit]
```

```scala
  override def release(seedEntityId: SeedEntityId.SeedEntityId): UIO[Unit] =
    caches.update(_ - seedEntityId)
```

A companion accessor is added alongside the existing `forWorkspace` one,
following the shape every other method on these objects uses.

### `MitigationScopeResolverRegistry` gains one method

```scala
  /** Drop this workspace's resolver and its memo. A later request for the same
    * workspace gets a new, empty one. */
  def release(workspaceId: WorkspaceId): UIO[Unit]
```

```scala
  override def release(workspaceId: WorkspaceId): UIO[Unit] =
    resolvers.update(_ - workspaceId)
```

### `CascadeDelete.workspace` gains two parameters

```scala
  def workspace(
    wsId:            WorkspaceId,
    seedEntityId:    SeedEntityId.SeedEntityId,
    treeIds:         Iterable[TreeId],
    riskTreeService: RiskTreeService,
    scenarioService: ScenarioService,
    cacheRelease:    WorkspaceCacheRelease
  )(using Checked[Permission]): UIO[Unit] =
    riskTreeService.cascadeDeleteTrees(wsId, treeIds)
      .zipPar(scenarioService.cascadeDeleteScenarios(wsId))
      .zipRight(cacheRelease.release(wsId, seedEntityId))
      .unit
```

**The release runs after the two cascades, not beside them, and the ordering is
deliberate.** Tree deletion reads trees, and a read is the kind of operation that
can populate a cache; releasing first would leave a fresh entry behind
immediately. Running it after also makes the release unconditional in practice:
both cascades are best-effort and return `UIO`, so neither can fail and skip it.

`treeIds` stays a separate parameter rather than being read from the record,
because `deleteWorkspace` passes the result of `workspaceStore.listTrees(key)`,
which is the authorization-checked list and is not necessarily
`WorkspaceRecord.trees`.

### The three call sites

Each already has the full `WorkspaceRecord` in scope, so no new lookup is needed.

`WorkspaceLifecycleController.deleteWorkspace` — `ws` comes from
`workspaceStore.resolve(key)`:

```scala
      _ <- CascadeDelete.workspace(ws.id, ws.seedEntityId, ids, riskTreeService, scenarioService, cacheRelease)
```

`WorkspaceLifecycleController.evictExpired` and `WorkspaceReaper`'s reap cycle —
`ws` comes from `evictExpired`, which returns `List[WorkspaceRecord]`:

```scala
      CascadeDelete.workspace(ws.id, ws.seedEntityId, ws.trees, riskTreeService, scenarioService, cacheRelease)
```

`WorkspaceLifecycleController` and `WorkspaceReaper` each take
`WorkspaceCacheRelease` as a constructor field, and their layers gain it as a
requirement. `Application.scala` adds `WorkspaceCacheRelease.layer` to the graph.

---

## The race this does not close, stated rather than hidden

A request that has already passed `workspaceStore.resolve` can call
`forWorkspace` after the release has run, recreating an entry for a workspace
that no longer exists.

**Why it is not worth locking against.** The recreated entry is a *new, empty*
cache — `ContentCache.make` builds a fresh one — so what survives the race is one
empty object, not the accumulated results this plan exists to free. Every later
request fails at `resolve` before reaching the registry, so nothing refills it.
Serialising teardown against in-flight requests would add a lock on the hot path
of every request to reclaim a few hundred bytes in a rare interleaving; that is
a worse trade than the residue.

The registry scaladoc states this, so the next reader does not mistake it for an
oversight.

---

## File inventory

| Path | Change |
|---|---|
| `modules/server/src/main/scala/com/risquanter/register/services/cache/WorkspaceCacheRelease.scala` | new — trait, layer, live implementation |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCacheRegistry.scala` | `release` on trait, companion and live implementation; scaladoc correction |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverRegistry.scala` | `release` on trait, companion and live implementation |
| `modules/server/src/main/scala/com/risquanter/register/services/CascadeDelete.scala` | two parameters, the release call, scaladoc |
| `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala` | constructor field, layer requirement, two call sites |
| `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceReaper.scala` | layer requirement, one call site |
| `modules/server/src/main/scala/com/risquanter/register/Application.scala` | `WorkspaceCacheRelease.layer` in the graph; two layer requirements updated |
| `modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala` | a `WorkspaceCacheRelease` stub recording its calls |
| `modules/server/src/test/scala/com/risquanter/register/services/cache/WorkspaceCacheReleaseSpec.scala` | new |
| `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala` | registry release tests |
| `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala` | registry release tests |
| `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerCascadeSpec.scala` | both teardown paths release |
| `modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceReaperSpec.scala` | the reaper releases on expiry |
| `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala` | provide the new layer |
| `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala` | provide the new layer |

Any other spec constructing `WorkspaceLifecycleController` or `WorkspaceReaper`
directly gains the new dependency. A new constructor parameter is a compile
error at every construction site, so the compiler enumerates them; sites found
that way are added to this inventory before being edited.

---

## Verification plan

The property to prove is not "a method was called" but "the memory is gone". The
tests are built around `ContentCache.stats`, which returns
`CacheStats(entries, hits, misses, evictedTotal)` and makes retained data
directly observable.

### Registry level

For each registry:

1. **Release drops the entry.** Ask for a workspace's instance, release, ask
   again, and assert the second instance is not the same object as the first.
2. **Release discards the data.** Ask for a workspace's cache, populate it, and
   assert `stats.entries` is greater than zero. Release. Ask again and assert
   `stats.entries` is zero. This is the test that actually proves the memory was
   freed rather than the key rebound.
3. **Release of an absent key is harmless.** Releasing a workspace that was
   never accessed succeeds and changes nothing — the reaper can evict a
   workspace that never ran a simulation.
4. **Release is confined to one workspace.** Populate two workspaces' caches,
   release one, and assert the other still reports its entries. This is the
   tenancy check: a release must not be able to reach across workspaces.

### Service level

`WorkspaceCacheReleaseSpec` asserts that one `release` call reaches **both**
registries, with the correct identifier passed to each — the cache registry
receives the `seedEntityId` and the resolver registry the `WorkspaceId`. Passing
the two identifiers to the wrong registries is the mistake this change makes
possible, and it would otherwise show up only as a silent failure to release.

### Teardown paths — all three

Using a recording `WorkspaceCacheRelease` stub added to `CascadeTestStubs`,
following the pattern the existing stubs already use:

- `WorkspaceLifecycleControllerCascadeSpec` gains a case for each of its two
  existing tests: `deleteWorkspace` releases, and `evictExpired` releases for
  every evicted workspace.
- `WorkspaceReaperSpec` gains a case beside its existing cascade tests: an
  expired workspace's caches are released when the reaper fires.

Each asserts the release was called with both of that workspace's identifiers,
so a site that passes only one is caught.

### End to end

One integration test is the honest check that the wiring is right, because the
unit tests all use stubs. Against the real stack: create a workspace, run an
analysis so its cache holds entries, delete the workspace, and assert that the
registry reports no entry for it.

### Ordering

A test asserting the release happens **after** the tree cascade, not before — a
recording stub that captures the order of calls. The ordering is a stated
correctness property above, so it is asserted rather than assumed.

### Full suite

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

Then, after the mandatory leaked-network cleanup:

```bash
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f; docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm; echo "--- remaining register_it_ networks ---"; docker network ls --filter name=register_it_ --format '{{.Name}}' | wc -l

sbt "serverIt/test"
```

All four tiers green is the acceptance condition. Report pass or fail only.

---

## Documentation and comment sweep

| Path | What changes |
|---|---|
| `ContentCacheRegistry.scala` | the scaladoc currently reads "Cache lifecycle = workspace lifecycle; a deleted workspace's cache lingers until restart (NoOp eviction)." The second clause stops being true and is deleted; the first becomes a plain statement of what the code now does, with the in-flight race noted |
| `MitigationScopeResolverRegistry.scala` | the same lifecycle statement added, since it now has the same behaviour |
| `CascadeDelete.scala` | the scaladoc lists what a workspace teardown removes; cache release joins trees and scenario branches |
| `docs/dev/plans/IMPLEMENTATION-PLAN.md` | the "Eviction Strategy" note ends "Monitor memory usage in production before implementing". It is rewritten to describe only within-cache eviction, which is what it is actually about, and to record that cross-workspace retention is handled here. Its unusable deferral criterion is replaced by the same kind of trigger criteria TODO 49 carries, and it gains the pointer to TODO 52 — which decides, once, which of the three shapes (a bound by construction, an eviction policy, or reclaiming the provably unreachable) belongs in which situation, and codifies the answer in an ADR |
| `docs/dev/TODO.md` | item 51 becomes a pointer to this plan rather than a description of unowned work |

---

## ADR alignment

- **ADR-030 and ADR-024 (tenancy isolation).** The per-workspace instance
  structure is unchanged; release removes one key from one map. The registry
  test asserting that releasing one workspace leaves another intact is the
  direct check on this. Conforms.
- **ADR-015 (content-addressed cache).** Cache semantics are untouched: the same
  keys, the same hits, the same eviction strategy within a live cache. Only the
  lifetime of a dead workspace's cache changes. Conforms.
- **ADR-021 (workspace lifetime).** This makes the in-memory state follow the
  lifetime that ADR already defines, rather than outliving it. Conforms, and
  closes a gap against it.
- **Decision Trigger review.** Trigger 4 (existing signatures) fires on
  `CascadeDelete.workspace` and on both registry traits; every new shape is
  written out above. Trigger 5 (existing behaviour) fires: a workspace's caches
  are now released at teardown, which is the deliberate change. No endpoint, DTO
  or wire format changes.

---

## Sequencing

**After `PLAN-CACHE-REGISTRY-RENAME`.** That plan renames both registries and
both their accessor methods. Adding a method to a type that is about to be
renamed means writing it twice and resolving a conflict for no reason. This plan
is written against the post-rename names throughout.

Independent of `PLAN-TELEMETRY-EXPORT`, `PLAN-SIMULATION-CONCURRENCY-BOUNDS` and
`PLAN-IRMIN-RECURSIVE-READ`. It shares `Application.scala` with the first two, on
different lines.

---

## Version bump

PATCH. Shipped code changes, no external API change.
