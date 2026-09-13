# Plan: rename the two per-workspace registries, and state the memo's key shape in code

**Status:** Ruled, awaiting approval as the session's governing plan.
**Date:** 2026-09-13.
**ADR reference:** ADR-030 and ADR-024 (the tenancy-isolation property the
per-workspace structure carries); ADR-018 (nominal wrappers — unaffected, noted
only because the renamed types sit next to it in the cache package).

Behaviour-preserving throughout. No signature changes other than the names
themselves, no wire change, no new type, no new dependency. The existing test
suite is the verification.

---

## Objective

Two types in `com.risquanter.register.services.cache` hand out one per-workspace
instance of a service: `CacheScope` returns a `ContentCache` per workspace seed
identity, and `ScopeResolverScope` returns a `MitigationScopeResolver` per
workspace. Both are named with the suffix `Scope`, and that name is wrong in
three separate ways.

**First, `ScopeResolverScope` uses the word "scope" twice, meaning two unrelated
things.** In this codebase the domain term *scope* means the set of node
identifiers a mitigation applies to — the word used consistently by
`ResolvedScopes`, `ScopeOutcome`, `appliedScopes` and `scopeOrEmpty`. That is
the first "Scope" in the name. The second one means "a per-workspace container",
a meaning borrowed from `CacheScope`. So the name expands to "the per-workspace
container of resolvers of mitigation scopes", and nothing in it tells a reader
which of the two words carries which meaning.

**Second, `Scope` is a core ZIO type meaning a resource-lifetime region** — the
thing `ZLayer.scoped` and `forkScoped` work with, both of which are used in this
codebase. `ScopeResolverScope.scala` opens with `import zio.*`, so inside that
file the bare name `Scope` *is* ZIO's type. A reader with ZIO habits reads the
suffix as a statement about resource lifetimes. Neither type has anything to do
with resource lifetimes and neither is ever used as a ZIO `Scope`.

**Third, neither type is a scope in any sense; both are registries.** A registry
is a keyed collection that returns an existing instance or creates one on first
request. That is exactly what both do, and it is already the word the prose
uses: `PLAN-RISKTRANSFORM.md` heads the relevant section "Per-workspace registry".
Only the type names disagree with the documents describing them.

A second, smaller objective rides along, because it concerns a comment in a file
this rename already touches. `MitigationScopeResolverLive`'s scaladoc states the
memo's shape but not why the commit hash is stored in the value rather than in
the map key, nor why the slot is bounded by construction instead of by an
eviction policy. Those are the design's two least obvious properties and the ones
a reader is most likely to try to "fix". Both reasons are stated in place.

---

## Background — what the two types do

Read this section if the cache package is unfamiliar; skip it otherwise.

### The content cache

`ContentCache` stores simulation results keyed by content hash, so that editing
one leaf of a risk tree re-simulates only that leaf and its ancestors. Results
must not be shared between workspaces, so there is one `ContentCache` per
workspace seed identity rather than one global cache. `CacheScope` is the object
holding that mapping: ask it for a workspace's cache and it returns the existing
one or creates a fresh one.

`CacheScope` also holds each cache's `EvictionStrategy` instance, so its
per-workspace instances carry state beyond the map entry itself.

### The mitigation scope resolver

A mitigation — a control that reduces risk — does not store a list of the nodes
it applies to. It stores a *targeting predicate*, a logical formula describing
its targets, such as "every node named Legacy CRM". `MitigationScopeResolver`
evaluates that formula against one version of one tree and returns the set of
node identifiers satisfying it. Because the formula must be re-parsed, re-bound
against the tree's vocabulary, and re-evaluated on every request, the result is
memoized per tree version.

`ScopeResolverScope` holds one resolver, and therefore one memo, per workspace.

### Why each has per-workspace instances rather than one shared map

This is settled and this plan does not change it. `PLAN-RISKTRANSFORM.md`
records it as an approved property under the ADR-030 and ADR-024 alignment:

> `ScopeResolverScope.resolverFor(wsId)` takes a server-derived `WorkspaceId`,
> and the one-resolver-per-workspace design makes cross-workspace scope
> contamination structurally impossible — a tenancy isolation property, not just
> an absence of new surface.

Tree identifiers are ULIDs and therefore already unique across workspaces, so
the memo key could not collide even in a shared map. The instance separation is
defence in depth: a property guaranteed by structure survives a future change to
how identifiers are generated, and one guaranteed by identifier uniqueness does
not. **The structure is preserved exactly. Only names change.**

---

## Exact signatures

### `ContentCacheRegistry` (was `CacheScope`)

File moves to
`modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCacheRegistry.scala`.

```scala
trait ContentCacheRegistry:
  def forWorkspace(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache]

object ContentCacheRegistry:
  val layer: ZLayer[Any, Nothing, ContentCacheRegistry]

  def forWorkspace(seedEntityId: SeedEntityId.SeedEntityId): URIO[ContentCacheRegistry, ContentCache]

final case class ContentCacheRegistryLive(
  caches: Ref[Map[SeedEntityId.SeedEntityId, ContentCache]]
) extends ContentCacheRegistry:
  override def forWorkspace(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache]
```

### `MitigationScopeResolverRegistry` (was `ScopeResolverScope`)

File moves to
`modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverRegistry.scala`.

```scala
trait MitigationScopeResolverRegistry:
  def forWorkspace(workspaceId: WorkspaceId): UIO[MitigationScopeResolver]

object MitigationScopeResolverRegistry:
  val layer: ZLayer[Any, Nothing, MitigationScopeResolverRegistry]

  def forWorkspace(workspaceId: WorkspaceId): URIO[MitigationScopeResolverRegistry, MitigationScopeResolver]

final case class MitigationScopeResolverRegistryLive(
  resolvers: Ref[Map[WorkspaceId, MitigationScopeResolver]]
) extends MitigationScopeResolverRegistry:
  override def forWorkspace(workspaceId: WorkspaceId): UIO[MitigationScopeResolver]
```

### `QueryServiceLive` constructor and layer

```scala
class QueryServiceLive private (
  repo:           RiskTreeRepository,
  resolver:       CachedResultResolver,
  scopeResolvers: MitigationScopeResolverRegistry,
  tracing:        Tracing
) extends QueryService
```

```scala
val layer: ZLayer[RiskTreeRepository & CachedResultResolver & MitigationScopeResolverRegistry & Tracing, Nothing, QueryService]
```

Call site becomes `mitResolver <- scopeResolvers.forWorkspace(wsId)`.

### `CachedResultResolverLive` layer

```scala
val layer: ZLayer[ContentCacheRegistry & SimulationConfig & Tracing & Meter, Throwable, CachedResultResolver]
```

Constructor parameter `cacheScope: CacheScope` becomes
`caches: ContentCacheRegistry`; its two call sites become `caches.forWorkspace(seedEntityId)`.

### Method rename summary

| Was | Becomes |
|---|---|
| `CacheScope.cacheFor` | `ContentCacheRegistry.forWorkspace` |
| `ScopeResolverScope.resolverFor` | `MitigationScopeResolverRegistry.forWorkspace` |

Once the type name already says "cache" or "resolver", repeating it in the
method reads as redundancy: `registry.forWorkspace(id)` rather than
`registry.cacheFor(id)`.

---

## The memo scaladoc

`MitigationScopeResolverLive`'s class scaladoc is replaced in full. The current
text states the shape and then points at a plan document for the rationale; the
replacement states the rationale in place, which is what the comment rule
requires — a comment reads as the current understanding of the code, and a
pointer to a plan is not that.

The slot's capacity is two, per the 2026-09-13 amendment to the memo ruling
(`PLAN-RISKTRANSFORM.md` §8.4-5). If that amendment has not landed by the time
this rename runs, apply this block as written and the capacity change with it —
the two edits touch the same comment and the same field, and splitting them
would mean writing the comment twice.

Replacing the block in
`modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`:

```scala
/** Memoizes the resolved scopes of one tree version. One entry per
  * (treeId, branch) holds up to two revisions and the scopes resolved at each,
  * evicting the least recently used, so revisions never accumulate. In-memory
  * `Ref` → `UIO`. One instance per workspace
  * (`MitigationScopeResolverRegistry`).
  *
  * The revision is a stamp inside the stored value rather than part of the map
  * key, and that is what bounds the memo. Keying on the revision would make
  * every revision a separate entry with nothing to remove it; each entry holds
  * a full mitigation-to-node-id map, so an editing session would retain one per
  * edit and grow without a ceiling. As a stamp under a fixed capacity it bounds
  * the memo by the number of live tree-and-branch pairs instead.
  *
  * Two is the capacity because the alternation it exists to serve has two
  * participants: the branch head, and whichever historic revision is being read.
  * A reader scrubbing through history replaces the second entry at every step
  * while the head entry survives, so neither reader forces the other to
  * recompute. A third revision evicts the least recently used.
  *
  * The bound is structural rather than a policy. An eviction strategy could have
  * served here, and `ContentCache` already has one; reusing it would mean
  * extracting a generic bounded cache shared by both, a refactoring whose cost
  * outweighed the return at two entries per slot. Open to reconsideration if a
  * caller ever alternates across more than two revisions. An entry is in any
  * case cheap to rebuild and expensive to retain: rebuilding scans the node
  * domain once per predicate, which is small against the simulation the
  * surrounding request performs, while a retained entry carries a node-id set
  * per mitigation.
  *
  * The memo read and write are not atomic — last-writer-wins is a deliberate,
  * accepted trade-off, safe because the exact-revision hit guard never serves a
  * mismatched scope: a losing writer's entry is replaced, never read as if it
  * matched a revision it did not resolve.
  */
```

---

## File inventory

Every path is repo-relative and complete, as the approval hook requires.

### Source — renamed files

| Path | Change |
|---|---|
| `modules/server/src/main/scala/com/risquanter/register/services/cache/CacheScope.scala` | renamed to `ContentCacheRegistry.scala`; 7 occurrences |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/ScopeResolverScope.scala` | renamed to `MitigationScopeResolverRegistry.scala`; 8 occurrences |

### Source — call sites

| Path | Occurrences |
|---|---|
| `modules/server/src/main/scala/com/risquanter/register/Application.scala` | 3 + 2 |
| `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala` | 5 |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala` | 5 |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCache.scala` | 1 |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala` | 1 + 1 |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala` | 1, plus the scaladoc replacement above |

### Unit tests — `server`

| Path | Occurrences |
|---|---|
| `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala` | 1, plus 3 `resolverFor` call sites |
| `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala` | 12, plus 8 `cacheFor` call sites |
| `modules/server/src/test/scala/com/risquanter/register/services/cache/CacheTransparencySpec.scala` | 7, including an `override def cacheFor` in a stub |
| `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala` | 4 |
| `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala` | 1 |
| `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala` | 2 |
| `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala` | 2 |
| `modules/server/src/test/scala/com/risquanter/register/services/Item17RegressionSpec.scala` | 1 |
| `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala` | 1 |
| `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala` | 1 |

### Integration tests — `serverIt`

| Path | Occurrences |
|---|---|
| `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala` | 2 + 2 |
| `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala` | 2 + 2 |
| `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala` | 2 |

---

## Documentation and comment sweep

This is the part the rename is most likely to leave half-done, so every site is
named rather than described.

### Scaladoc on the two renamed types

Both currently explain themselves using the word this rename removes.
`ScopeResolverScope`'s scaladoc opens "Per-workspace `MitigationScopeResolver`
resolution, mirroring `CacheScope`", and `CacheScope`'s is shaped the same way.
Both are rewritten to say what a registry is and why instances are
per-workspace, without the word "scope" in its container sense. The
cross-reference between them is kept — they are deliberately parallel and a
reader meeting one should be pointed at the other.

### Comments at the call sites

| Path | What is stale after the rename |
|---|---|
| `modules/server/src/main/scala/com/risquanter/register/Application.scala` | the layer-graph comments naming both types; the `QueryServiceLive.layer` requirement comment listing `ScopeResolverScope`; the `CacheScope.layer` comment carrying the decision label `DD-17`, which the comment rule forbids and which is removed rather than renamed |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala` | the class scaladoc's statement that the layer uses `CacheScope` for per-workspace access |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala` | the full scaladoc replacement given above, which also removes the `PLAN-RISKTRANSFORM §8.13` pointer — a comment may cite an ADR but never a plan |
| `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala` | the `ScopeResolutionContext` scaladoc explaining that the workspace is the instance, which names `ScopeResolverScope` |

### Plan and architecture documents

| Path | Occurrences | Treatment |
|---|---|---|
| `docs/dev/plans/PLAN-RISKTRANSFORM.md` | 29 | every occurrence renamed, including the `## File inventory` entry and the heading "Per-workspace registry — ScopeResolverScope.scala (mirrors CacheScope)". The inventory change is delivered as a copy-pasteable block plus a verbatim anchor line, not applied silently |
| `docs/dev/ARCHITECTURE.md` | 4 | renamed in place |
| `docs/dev/plans/PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` | 1 | renamed in place |
| `docs/dev/plans/PLAN-SIMULATION-CONCURRENCY-BOUNDS.md` | 1 | renamed in place |
| `docs/dev/decision-records/ADR-015.md` | 1 | renamed in place. An accepted decision record is amended, never rewritten: the sentence names the type as a component of the design it records, so the name is corrected and nothing else about the record changes |

### Documents deliberately left alone

| Path | Why |
|---|---|
| `docs/archive/milestone-2b-cache-and-decisions.md` (6) | archived documents record what was decided at the time. Renaming inside them would make the record describe code that did not exist when it was written |
| `docs/archive/DONE-PLAN-SEED-IDENTITY.md` (1) | same |

---

## ADR alignment

- **ADR-030 and ADR-024 (authorization boundary, tenancy).** The
  one-instance-per-workspace structure is preserved exactly: same map, same
  key type, same atomic first-access race resolution. The tenancy-isolation
  property PLAN-RISKTRANSFORM records is unaffected. Conforms.
- **ADR-018 (nominal wrappers).** Untouched. No identifier type changes.
- **ADR-001 (validate once, at the boundary).** Untouched. No validation moves.
- **No Decision Trigger fires.** The nine triggers cover API shapes, existing
  signatures and existing behaviour. A rename changes a name, not a shape: no
  endpoint, no DTO, no wire format, no error path, and no behaviour changes.
  The signature changes are the names themselves, spelled out above.

---

## Verification plan

No behaviour changes, so no new test asserts anything new. The existing suite is
the gate, and its value here is precisely that it is unchanged: if a rename were
incomplete or wrong, compilation fails, and if it silently changed wiring, the
layer-construction tests fail.

Run the complete suite, every tier:

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

All four green is the acceptance condition. Report pass or fail only.

One additional check, because a rename is exactly the change that leaves a
stale string behind in a document nobody compiles:

```bash
grep -rn -E 'ScopeResolverScope|\bCacheScope\b|\bcacheFor\b|\bresolverFor\b' \
  --exclude-dir=target --exclude-dir=node_modules --exclude-dir=.git \
  --exclude-dir=archive .
```

Zero hits outside `docs/archive/` is the acceptance condition.

---

## Sequencing

**This plan lands before `PLAN-SIMULATION-CONCURRENCY-BOUNDS`.** Both modify
`CachedResultResolverLive.scala`. A rename landing after a substantive change to
the same file means resolving conflicts that need not exist, and a rename is the
cheaper of the two to land first because it cannot fail on design grounds.

It has no ordering relationship with `PLAN-IRMIN-RECURSIVE-READ`,
`PLAN-NGINX-WORKSPACE-ROUTING` or `PLAN-TELEMETRY-EXPORT`; none of them touch
these files.

---

## Version bump

PATCH. Shipped code changes, no external API change.
