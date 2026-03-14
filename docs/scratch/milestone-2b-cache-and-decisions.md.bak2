# Milestone 2b: Cache & Branching Design (Consolidated)

> Scratch document. Reflects validated findings as of 2026-03-13.
> Prior revision preserved at `milestone-2b-cache-and-decisions.md.bak`.
> Empirical test script: `dev/test-irmin-hashes.sh` (9/9 passed).

---

## Problem Statement

The current cache maps `(TreeId, NodeId) → RiskResult`. Every tree lives on
`main`. Introducing branches means the same `(TreeId, NodeId)` can hold
different parameter values on different branches. The cache cannot distinguish
between them — **branch switching silently returns wrong results.**

The fix must support seven workflows:

| # | Workflow | What the cache must do |
|---|---------|----------------------|
| UC1 | **Tree creation** | Nothing — all nodes are new, simulate everything |
| UC2 | **Loading a stored tree** | Look up existing results by content identity |
| UC3 | **Parameter edit on a branch** | Detect changed nodes, reuse unchanged |
| UC4 | **Time travel / revert** | Reuse results from historical states if content matches |
| UC5 | **Branch comparison (diff view)** | O(1) per-node equality check across two branches |
| UC6 | **Branch switch (cache warming)** | Reuse results from the prior branch for identical nodes |
| UC7 | **Structural edit (add/remove/move)** | Invalidate parent aggregation while preserving sibling results |

---

## Decision: Content-Addressed Cache via Irmin Hash Delegation

### Why

Irmin already computes SHA-1 content hashes for every stored value
(`Contents.hash`). These hashes are:

- **Path-independent** — same value at different paths = same hash
- **Branch-independent** — same value on different branches = same hash
- **Offline-computable** — `contents_hash(value:)` returns hash without writing
- **Byte-stable** — Irmin stores values as opaque strings; no JSON reinterpretation

All properties validated empirically (9/9 tests passed).

Using `Contents.hash` as the cache key gives us `Map[ContentHash, RiskResult]`.
Two nodes with identical parameters share one cache entry, regardless of which
branch or path they're on. Cross-branch sharing is implicit — no explicit
cache copying or synchronization.

### Why not per-branch caches (State A)?

Per-branch caching (`Map[(TreeId, BranchRef, NodeId), RiskResult]`) was the
simpler intermediate option. We are skipping it because:

1. Every concern that made content-addressed caching seem hard has been
   resolved (metadata separation, canonicalization, hash computation)
2. State A would be throwaway scaffolding — designed explicitly to be replaced
   by State B
3. State A cannot serve UC5 (comparison) or UC6 (cache warming) efficiently —
   the two highest-value workflows for branching

---

## How It Works

### Leaf nodes: hash fully delegated to Irmin

```
Irmin  get_contents(path, branch) → { value: String, hash: ContentHash }
                                                       │
                                                       ▼
                                             ContentCache.get(hash)
                                               hit? → return RiskResult
                                               miss? → Monte Carlo simulate → store
```

Leaf `Contents.hash` is determined entirely by the stored JSON — which
contains only simulation-relevant fields (probability, distributionType,
percentiles, quantiles, minLoss, maxLoss) plus structural fields (id, name,
parentId). No timestamps, no metadata. The current storage layout already
separates `/meta` from `/nodes/{id}`.

**Serialization determinism.** `Contents.hash` hashes raw bytes, so the JSON
serialization must be byte-stable: same logical values → identical string,
every time. The current codecs provide this:

- `RiskLeaf` and `RiskPortfolio` each use a `*Raw` intermediate case class
  (`RiskLeafRaw`, `RiskPortfolioRaw`) with `DeriveJsonCodec.gen`. The derived
  encoder emits keys in **case class field declaration order** (macro-generated
  at compile time). There is no HashMap iteration, no randomness.
- zio-json produces compact JSON (no whitespace). Same values in → identical
  byte string out.
- `Double.toString` (used for `probability`) is deterministic per the Java
  spec: each `double` value has exactly one canonical string representation.
- `Option[T]` fields: `None` is omitted by zio-json's derived encoder
  (not written as `null`). This is stable across serialization calls.

**Risk vectors and mitigations:**

| Risk | Impact | Mitigation |
|------|--------|------------|
| Reorder fields in `RiskLeafRaw` / `RiskPortfolioRaw` | Key order changes → all hashes change → mass cache miss (no incorrect results) | Treat `*Raw` field order as part of the storage contract. Add a comment. |
| zio-json version upgrade changes encoding | Possible different bytes → different hashes | Cache is in-memory only → restart clears it. Existing Irmin data keeps old hashes; only re-written nodes get new hashes. Add a serialization snapshot test. |
| Floating-point edge cases | Negligible — Java `Double.toString` is canonical | No action needed. |

**Recommended safeguard:** Add a single test that serializes a known
`RiskLeaf` and asserts the exact JSON string. This catches serialization
drift from zio-json upgrades or field reordering at CI time. Example:

```scala
test("RiskLeaf JSON serialization is byte-stable") {
  val leaf = RiskLeaf.make(id = "test-1", name = "cyber",
    parentId = Some("ops"), distributionType = Lognormal,
    probability = 0.3, percentiles = None, quantiles = None,
    minLoss = Some(1000L), maxLoss = Some(50000L)).toOption.get
  val json = leaf.toJson
  // If this assertion breaks, Contents.hash for all leaves has changed.
  // This is not a bug — but it means the content-addressed cache will
  // miss on every entry after the next write cycle.
  assertTrue(json ==
    """{"id":"test-1","name":"cyber","parentId":"ops","distributionType":"Lognormal","probability":0.3,"minLoss":1000,"maxLoss":50000}""")
}
```

### Portfolio nodes: the Merkle propagation problem

A portfolio's `Contents.hash` captures only its OWN JSON (`{id, name,
parentId, childIds}`). It does NOT reflect children's parameter values.
If `cyber.probability` changes from 0.3 to 0.6, the parent portfolio
`ops-risk` keeps the same `Contents.hash` — but its aggregated simulation
result IS stale (it was summed from the old cyber outcomes).

**Using `Contents.hash` alone for portfolios would produce silent wrong
results.**

Irmin provides a second hash type: `Tree.hash` — the hash of an Irmin
**subtree** at a given path. Empirically validated properties:

- **Propagates child changes:** changing a descendant value changes the
  `Tree.hash` at every ancestor path (Irmin's native Merkle tree)
- **Branch-independent:** same subtree content on two branches produces
  the same `Tree.hash`
- **Sibling-stable:** changing one child does NOT affect sibling subtree
  hashes

This means **if the Irmin path structure mirrors the risk tree hierarchy**,
`Tree.hash` at a portfolio's path naturally works as a Merkle-aware cache
key — with zero JVM-side hash computation.

### Two approaches to portfolio hashing

**Option 1: Hierarchical storage layout (full Irmin delegation)**

Restructure Irmin paths to mirror the risk tree:

```
Current flat layout:              Hierarchical layout:
risk-trees/{tree}/                risk-trees/{tree}/
  meta                              meta
  nodes/                            tree/{rootId}/
    {nodeId-a}                        params          ← portfolio JSON
    {nodeId-b}                        children/
    {nodeId-c}                          {childId-1}/
                                          params      ← leaf JSON
                                        {childId-2}/
                                          params
```

With this layout, `Tree.hash` at `tree/{portfolioId}/` covers the portfolio's
params AND all descendant params. A child leaf change propagates up through
Irmin's native Merkle tree — no JVM hash computation, no `ContentHashIndex`,
no `ancestorPath` walk.

**Trade-offs:**

| Aspect | Flat (current) | Hierarchical |
|--------|---------------|-------------|
| Portfolio cache key | JVM Merkle bottom-up | `Tree.hash` from Irmin (zero JVM) |
| Storage migration | None | **Must migrate existing data** |
| Node CRUD | O(1) path construction | Path depends on position in tree |
| Move/reparent node | Update `parentId` + `childIds` JSON | **Move entire Irmin subtree** |
| Add node | Write to `nodes/{id}` | Write under parent's `children/` |
| List all nodes | `irmin.list(nodes/)` | Recursive tree walk |
| Irmin path length | 3 segments | O(depth) segments |
| Get single node | 1 query | 1 query (path is longer but still O(1)) |

The key cost is **reparenting**: moving a node in the risk tree requires
moving an Irmin subtree (delete at old path + write at new path, including
all descendants). In flat layout this is just updating two JSON values.

**Option 2: Flat layout + JVM Merkle (current plan)**

Keep the flat `nodes/{nodeId}` layout. Compute portfolio cache keys on the
JVM at tree-load time:

```
leafCacheKey      = Contents.hash from Irmin (delegated)
portfolioCacheKey = sha256(sort(children's cacheKeys))
```

O(n) total, microseconds of work, requires no extra Irmin queries (children's
hashes arrive with `getContents` calls already needed for tree loading).

**Recommendation: Option 2 (flat + JVM Merkle).**

The hierarchical layout is elegant for hash delegation but makes reparenting
operations (drag-and-drop in the UI) expensive — O(subtree size) Irmin writes
vs O(1) JSON updates. The JVM Merkle computation is trivially cheap and
avoids a storage migration. Full Irmin delegation of portfolio hashing is
possible but not worth the structural trade-off.

### Portfolio aggregation cost

Portfolio `RiskResult` computation is `childResults.reduce(RiskResult.combine)`
where `combine` = trial-aligned loss summation via sparse map merge. This is
O(|union-of-trial-IDs| × nChildren) — **no Monte Carlo sampling**. The
expensive step is leaf simulation; portfolio aggregation is cheap by
comparison. Re-aggregating a portfolio on cache miss is low-cost.

---

## Use Case Analysis

### Reminder: two kinds of hash

Every use case below references cache keys. These work differently for
leaves and portfolios:

| Node type | Cache key source | What it covers |
|-----------|-----------------|----------------|
| **Leaf** | Irmin `Contents.hash` (returned by `getContents`) | The leaf's own JSON bytes (simulation-relevant fields). Irmin computes this — zero JVM work. |
| **Portfolio** | JVM Merkle hash (computed by `ContentHashIndex.build`) | `sha256(sort(children's cache keys))`. Reflects all descendant parameters transitively. Irmin's `Contents.hash` for a portfolio only covers its own JSON (`{id, name, childIds}`) and does **not** reflect children — it is NOT used as a cache key. |

See [JVM Merkle Hash Computation](#jvm-merkle-hash-computation--design) for
algorithm details and worked examples (Flows 1–3).

### UC1: Tree creation

All nodes are new, nothing is cached. `ContentHashIndex.build` runs (see
Flow 1) but every lookup misses. Irmin stores `Contents.hash` per node as a
side effect of `set()` — available for future reads at no cost.

### UC2: Loading a stored tree

`getContents(path, branch)` returns `{ value, Contents.hash }` per node.
Then `ContentHashIndex.build` produces the actual cache keys:

- **Leaves:** cache key = Irmin `Contents.hash` directly.
- **Portfolios:** cache key = JVM Merkle hash (SHA-256 of sorted children's
  cache keys). The `Contents.hash` Irmin returns for a portfolio is
  **discarded** — it only covers `{id, name, childIds}`, not descendant params.

Per-node lookup: `ContentCache.get(cacheKey)` → hit → return `RiskResult`,
miss → simulate (leaf) or aggregate (portfolio), store under that key.

**Requires:** `IrminClient.getContents` method returning `(value, hash)`.

### UC3: Parameter edit on a branch

User edits `cyber.probability` on `scenario-high`. After Irmin `set()`:

1. **Changed leaf:** `getContents` returns a new `Contents.hash` → new cache
   key → miss → Monte Carlo simulate.
2. **Unchanged sibling leaves:** same `Contents.hash` → same cache key → hit.
3. **Ancestor portfolios:** `ContentHashIndex.build` computes new Merkle
   hashes (the changed leaf's hash propagates up through the `sha256(sort(…))`
   computation) → miss → re-aggregate (cheap — just map merge, no Monte Carlo).
4. **Unrelated subtrees:** unchanged Merkle hashes → hit.

For a 100-node tree with 1 changed leaf: **1 Monte Carlo simulation +
O(depth) aggregations instead of 100 simulations.**

### UC4: Time travel / revert

Irmin's commit DAG preserves every historical state. Reading a historical
commit returns node values whose `Contents.hash` matches what was valid at
that commit. `ContentHashIndex.build` produces the same Merkle hashes that
were valid then. If those entries are still in `ContentCache` → hit.

`revert(commit, branch)` creates a forward commit restoring old byte content.
The restored values produce the SAME `Contents.hash` → same leaf cache keys
→ same JVM Merkle hashes for portfolios → cache hits across the board.

### UC5: Branch comparison (diff view)

Load both branches. For each `NodeId` present in both:

- **Leaves:** compare `Contents.hash` from Irmin. Same → identical params → skip.
  Different → include in diff.
- **Portfolios:** compare JVM Merkle hashes. Same → entire subtree identical
  (because Merkle propagates). Different → at least one descendant differs.

This is O(n) string comparisons. No byte-level JSON diffing needed.

For simulation results in the diff view: both cache keys are looked up in
`ContentCache`. Unchanged nodes share the same entry (same hash =
same `RiskResult`). Only truly changed leaves require new simulation.

### UC6: Cache warm on branch switch

User switches from `main` (100 nodes simulated) to `scenario-high` (95
identical leaf params, 5 changed). On loading `scenario-high`:

1. `getContents` returns `Contents.hash` per node on the new branch.
2. `ContentHashIndex.build` produces cache keys.
3. **Unchanged leaves** (95): same `Contents.hash` → same cache key → hit
   from existing `ContentCache` entries (computed while on `main`).
4. **Changed leaves** (5): new `Contents.hash` → new cache key → miss →
   Monte Carlo simulate.
5. **Portfolios above changed leaves:** new JVM Merkle hash → miss →
   re-aggregate (cheap).
6. **Portfolios in unrelated subtrees:** same Merkle hash → hit.

**Result: 5 simulations instead of 100.** Cross-branch sharing is implicit —
no "copy cache entries" step, because the cache is keyed by content hash, not
by `(branch, nodeId)`.

### UC7: Structural edit (add/remove/move node)

Adding child `fraud` to `ops-risk`:

1. **New node `fraud`:** new `Contents.hash` from Irmin → miss → simulate.
2. **`ops-risk` (parent portfolio):** its Irmin `Contents.hash` changes
   (because `childIds` array changed), AND its JVM Merkle hash changes
   (because the child set changed) → miss → re-aggregate.
3. **Existing siblings** (`cyber`, `hardware`): `Contents.hash` unchanged →
   same cache key → hit.
4. **Ancestors up to root:** JVM Merkle hashes change (the changed child
   Merkle hash propagates up) → miss → re-aggregate (cheap).
5. **Unrelated subtrees** (`market`): unchanged → hit.

**Result: 1 simulation + O(depth) aggregations.**

Note: for portfolios, the Irmin `Contents.hash` change (step 2) and the JVM
Merkle hash change happen to coincide here, but they measure different things.
The Merkle hash is what matters for cache correctness — it's the actual cache
key. The `Contents.hash` change for the portfolio is irrelevant to caching
(we don't use it as a portfolio cache key).

---

## Eviction Strategy

Content-addressed caching creates orphan entries: when a node's params change,
the old hash's cache entry is never looked up again.

### Interface

```scala
trait EvictionStrategy:
  /** Called after a cache write. Returns hashes to evict (may be empty). */
  def onStore(hash: ContentHash, sizeBytes: Long): UIO[Set[ContentHash]]

  /** Called on cache hit. Allows recency tracking. */
  def onAccess(hash: ContentHash): UIO[Unit]

  /** Periodic or on-demand sweep. Returns all hashes to evict now. */
  def sweep: UIO[Set[ContentHash]]

  /** Observability. */
  def stats: UIO[EvictionStats]
```

### Implementations

| Strategy | When to use |
|----------|-------------|
| `NoOpEvictionStrategy` | v1, small trees (<50 nodes, <5 branches). Memory is cheap, entries are ~80KB. |
| `LruEvictionStrategy(maxEntries)` | Production default. Cap at 10K entries (~800MB worst case). |
| `RefCountEvictionStrategy` | Future. Tracks active branch references per hash. |

Start with `NoOpEvictionStrategy`. Graduate to LRU when memory pressure is
observable.

---

## SimulationConfig and Cache Validity

`SimulationConfig` (`defaultNTrials`, `defaultSeed3`, `defaultSeed4`) is
service-wide, set at startup. It is NOT part of Irmin content hashes — it's
not stored in node JSON.

If `SimulationConfig` changes (server restart with different config), all
cached results are stale: same content hash, but results computed with
different trial counts.

**Current behavior:** Caches are in-memory `Ref` → restart = empty → correct.
Content-addressed cache follows the same pattern for v1.

**Future extension:** If per-branch `SimulationConfig` is needed, extend
cache key to `(ContentHash, configHash)`. Not in scope for initial
implementation.

---

## Decision Points — Status

### Closed (dictated by validated Irmin delegation)

| DD | Topic | Decision | Rationale |
|----|-------|----------|-----------|
| DD-1 | IrminClient branch parameterization | Optional `branch` param on existing methods | `getContents` needs branch for hash retrieval. Default `None` = backward compatible. |
| DD-2 | New IrminClient operations | Add all 7 ops: `getContents`, `getBranch`, `mergeBranch`, `revert`, `getCommit`, `getHistory`, `lca` | `getContents` is a hard requirement. Others are mechanical GraphQL wrappers. |
| DD-3 | Cache strategy | **Content-addressed (State B)** directly. Skip per-branch caching. | All blockers resolved. Per-branch is throwaway. |
| DD-4 | Repository branch threading | Optional `branch` param on trait methods | Comparison workflow needs explicit branch args (read both in one effect). |
| DD-10 | Error types | Flat hierarchy extending `AppError` | `BranchNotFound`, `MergeConflictError`, `CommitNotFound`, etc. Follows existing pattern. |
| DD-12 | Test backward compat | Default args are source- and binary-compatible | ~60 new tests estimated across new capabilities. |

### Influenced by choice

| DD | Topic | How State B affects it |
|----|-------|-----------------------|
| DD-6 | ScenarioService API | Comparison uses hash-diff (UC5) — no value-level comparison. Server-side diff via hash equality. |
| DD-13 | Implementation order | No "State A" phase. Foundation includes `ContentCache` from day one. |

### Independent (still open — require UX/architecture decisions)

| DD | Topic | Core question |
|----|-------|---------------|
| DD-5 | Scenario domain model | Branch naming convention and scenario metadata storage location. |
| DD-7 | HistoryService API | History granularity (raw Irmin commits vs transaction-grouped). Revert UX semantics. |
| DD-8 | HTTP endpoint design | Branch state: client-side header (`X-Active-Branch`) vs server session. Two-tab problem. |
| DD-9 | Frontend UI placement | Branch bar location, comparison view placement in Analyze section. |
| DD-11 | Workspace ↔ scenario ownership | Convention-based prefix matching vs explicit ownership records. |

### Deferred / Follow-up improvements

| DD | Topic | Rationale |
|----|-------|-----------|
| DD-9b | Per-branch SimulationConfig | Cache-clear-on-restart sufficient. Extend to `(ContentHash, configHash)` if needed later. |

---

## New Types

```scala
case class BranchRef(value: String)               // Irmin branch name
case class ContentHash(value: String)              // Irmin SHA-1 content hash
case class CommitHash(value: String)               // Irmin commit hash
case class IrminContents(value: String, hash: ContentHash)  // Value + hash pair
case class ScenarioId(toSafeId: SafeId.SafeId)    // ULID, same pattern as TreeId
```

---

## New/Modified Components

| Component | Change |
|-----------|--------|
| `IrminClient` | Add optional `branch` param to `get`/`set`/`remove`/`list`. Add 7 new operations from DD-2. |
| `IrminQueries` | New GraphQL query strings for `get_contents`, `branch`, `merge_with_branch`, `revert`, `commit`, `last_modified`, `lcas`. |
| `RiskTreeRepositoryIrmin` | Thread `branch` param. Use `getContents` to return hash alongside value on read. |
| `ContentCache` (new) | `Ref[Map[ContentHash, RiskResult]]` with `EvictionStrategy`. Replaces role of `RiskResultCache`. |
| `ContentHashIndex` (new) | At tree load: stores leaf hashes from Irmin, computes portfolio Merkle hashes bottom-up. Maps `(TreeId, BranchRef, NodeId) → ContentHash`. |
| `CacheScope` (new) | Abstraction over cache resolution. `RiskResultResolver` calls `CacheScope` instead of `TreeCacheManager`. |
| `TreeCacheManager` | **Retired.** Replaced by `CacheScope` + `ContentCache`. |
| `InvalidationHandler` | Simplified — content-addressed cache doesn't need explicit `ancestorPath` invalidation. Cache misses are driven by hash changes. Structural mutation logic (orphan cleanup) still needed for SSE notifications. |

---

## Code Review Findings

Detailed review of the simulation/aggregation pipeline against our working
assumptions (2026-03-13).

### Confirmed assumptions

| # | Assumption | Status |
|---|-----------|--------|
| 1 | Leaf simulation is expensive (Monte Carlo), portfolio aggregation is cheap (map merge) | **Confirmed.** Leaf: nTrials occurrence samples + loss quantile. Portfolio: sparse map union + per-trial sum. |
| 2 | Portfolio result = reduce(children) via trial-aligned loss summation | **Confirmed.** `childResults.reduce(RiskResult.combine)` → `LossDistribution.merge` = outer join + sum. |
| 3 | Cache invalidation walks ancestor path to root | **Confirmed.** `TreeCacheManager.invalidate` → `tree.index.ancestorPath(nodeId)` → `cache.removeAll(path)`. |
| 4 | Portfolio result depends only on children's results, not its own JSON | **Confirmed.** `combine` uses only children's `outcomes` + `provenances`. Portfolio `id` is stamped on via `withNodeId`. |
| 5 | SimulationConfig is global, not per-node | **Confirmed.** Injected once at layer construction. All nodes share `defaultNTrials`, `seed3`, `seed4`. |
| 6 | Each leaf generates sparse Map[TrialId, Loss] | **Confirmed.** `performTrials` filters for occurrence first, then samples loss only for hits. |

### Deviations and nuances

**1. No risk-level parallelism in the resolver.** `RiskResultResolverLive`
simulates portfolio children **sequentially** via `ZIO.foreach`. The
`Simulator.simulate` method with `ZIO.collectAllPar` exists but is NOT called
by the resolver. Only trial-level parallelism (inside `performTrials`) is
used. This means the current pipeline does not exploit multi-core for
independent subtrees.

> Impact on design: Not a blocker, but an optimization opportunity for Phase A.
> Content-addressed cache makes this even more valuable — with cross-branch
> sharing, fewer nodes need simulation, so parallelizing the remaining misses
> has higher relative payoff.

**2. `RiskResult` is always typed as `Leaf`.** The `combine` method returns
a `RiskResult` with `distributionType = LossDistributionType.Leaf` even for
portfolio aggregates. A separate `RiskResultGroup` class with
`LossDistributionType.Composite` exists but is never produced by the cache
pipeline. This is a semantic mismatch but doesn't affect correctness.

> Impact on design: None. `ContentCache` stores `RiskResult` regardless of
> declared type.

**3. `nTrials` alignment enforced at combine time.** `RiskResult.combine`
calls `require(a.nTrials == b.nTrials)`. Since all leaves use the same
global `SimulationConfig.defaultNTrials`, this holds. But if the config changes
between simulation runs without a full cache clear, stale entries with old
`nTrials` would cause a **runtime crash** during combine.

> Impact on design: Reinforces the need to clear `ContentCache` on
> `SimulationConfig` change (already documented above). For State B, this
> becomes: clear cache on server restart or config change.

**4. Provenance is always captured.** `simulateLeaf` always records
`NodeProvenance` (distribution type, entity seed, occurrence count). The
`includeProvenance` parameter only controls whether provenances are
**returned to the caller** at the service layer — they're always in the cache.
Portfolio provenances accumulate all descendant provenances via
`a.provenances ++ b.provenances`.

> Impact on design: `RiskResult` entries in `ContentCache` always carry full
> provenance chains. This increases entry size for portfolios with many leaves.
> Not a concern for v1 but relevant for eviction sizing estimates.

**5. Entity seed derived from node ID hashCode.** `entitySeed =
leaf.id.value.hashCode.toLong`. The node's ULID determines its PRNG seed.
This means: same leaf ID + same SimulationConfig = same simulation output,
regardless of branch. Content-addressed caching captures this correctly —
the JSON includes `id`, so the hash changes if the ID changes.

> Impact on design: None. Seed determinism aligns with content-addressed
> caching.

**6. Name change triggers invalidation.** `InvalidationHandler.handleMutation`
compares nodes via structural equality (`RiskNode` equals). A name-only change
(no parameter change) triggers cache invalidation and re-simulation, even
though the name does not affect simulation results.

> Impact on design: With content-addressed caching, renaming a node changes
> its `Contents.hash` (the JSON includes `name`) → cache miss → re-simulation.
> This is **unnecessary work** but matches current behavior. Fixing it would
> require hashing only simulation-relevant fields (not `name`), which means
> JVM-side canonicalization — the exact complexity we're avoiding by using
> Irmin hashes. Acceptable trade-off: renames are rare, re-simulation of one
> node is fast.

---

## Phase Outline

```
Phase A: Foundation
  - BranchRef, ContentHash, CommitHash types
  - IrminClient branch parameterization + getContents + branch operations
  - Repository branch threading
  - ContentCache + NoOpEvictionStrategy
  - ContentHashIndex (leaf: Irmin hash, portfolio: JVM Merkle)
  - CacheScope → RiskResultResolver wiring
  - Retire TreeCacheManager

Phase B: Scenario CRUD + Minimal UI
  - ScenarioService (create/list/delete/switch)
  - BranchBar UI component
  - End-to-end: create scenario, switch, edit, switch back

Phase C: Comparison
  - ScenarioDiff service (hash-based diff, UC5)
  - Comparison view in Analyze section
  - Cross-branch cache warming (UC6, implicit from content-addressing)

Phase D: Merge
  - ScenarioMerger (Irmin merge_with_branch)
  - Merge preview + confirm flow
  - Conflict handling

Phase E: History / Time Travel
  - HistoryService (commit log, point-in-time, revert)
  - CommitHistoryPanel UI
```

### Follow-up improvements (post-launch)

**Risk-level simulation parallelism.** Currently `RiskResultResolverLive`
simulates portfolio children sequentially (`ZIO.foreach`). Only trial-level
parallelism exists (inside `Simulator.performTrials`). A `Simulator.simulate`
method with `ZIO.collectAllPar` already exists but is not wired into the
cache-aware resolver.

With content-addressed caching, this optimization has higher relative payoff:
cross-branch sharing means fewer cache misses on branch switch, so when misses
DO occur, they should resolve as fast as possible. Independent sibling
subtrees (e.g., `ops-risk` and `market-risk`) can be simulated in parallel.

```
Before (sequential):    cyber → hardware → ops-risk → market → portfolio
                        ████████████████████████████████████████████████ 200ms

After (parallel):       cyber    ──────┐
                        hardware ──────┼→ ops-risk ────┐
                        market   ──────────────────────┼→ portfolio
                        ████████████████████████████████  120ms
```

Implementation: replace `ZIO.foreach(childIds)(simulateNode)` with
`ZIO.foreachPar(childIds)(simulateNode)` in the portfolio branch of
`simulateNode`, constrained by `SimulationConfig.maxConcurrentSimulations`.
Guarded by `SimulationSemaphore` (already exists). Low risk because
cache writes are to a `Ref` (atomic) and child results are independent.

---

## JVM Merkle Hash Computation — Design

### Algorithm

At tree load time, after `getContents` returns `(value, hash)` for every
node, compute portfolio cache keys bottom-up:

```scala
import java.security.MessageDigest

/** Computes content-addressed cache keys for all nodes in a tree.
  *
  * Leaves:      use Irmin Contents.hash directly (zero JVM work)
  * Portfolios:  SHA-256 of sorted children's cache keys (Merkle hash)
  */
object ContentHashIndex:

  /** Build the full hash index from Irmin-provided leaf hashes. */
  def build(
    tree: RiskTree,
    irminHashes: Map[NodeId, ContentHash]   // from getContents calls
  ): Map[NodeId, ContentHash] =

    val index = scala.collection.mutable.Map.empty[NodeId, ContentHash]

    def computeHash(nodeId: NodeId): ContentHash =
      index.getOrElseUpdate(nodeId,
        tree.index.nodes(nodeId) match
          case _: RiskLeaf =>
            // Leaf: Irmin already gave us the hash
            irminHashes(nodeId)

          case p: RiskPortfolio =>
            // Portfolio: Merkle hash from children's cache keys
            val childHashes = p.childIds
              .map(computeHash)          // recurse (children first)
              .map(_.value)
              .sorted                    // canonical order
              .mkString("|")
            ContentHash(sha256(childHashes))
      )

    // Start from root, recursion visits every node exactly once
    tree.index.rootId.foreach(computeHash)
    index.toMap

  private def sha256(input: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(input.getBytes("UTF-8"))
      .map("%02x".format(_)).mkString
```

### Complexity

- Each node visited exactly once (memoized in `index`)
- Per leaf: O(1) map lookup
- Per portfolio: O(k) where k = number of children (sort + concat + SHA-256)
- Total: O(n) across all nodes, O(n log n) worst case from sorting
- SHA-256 of short strings: ~1μs per call
- For 100 nodes: ~100μs total — invisible against network latency

### Flows that trigger the JVM Merkle computation

Consider this 5-node tree loaded from Irmin on `main`:

```
                   portfolio (P)
                   ├── ops-risk (O)   ← portfolio
                   │   ├── cyber (C)  ← leaf
                   │   └── hw (H)     ← leaf
                   └── market (M)     ← leaf
```

#### Flow 1: Initial tree load (UC2)

```
Step 1: getContents for each node from Irmin
  ┌───────────────────────────────────────────────────────────┐
  │ Irmin returns:                                            │
  │   C  → { value: "{prob:0.3,...}",  hash: "abc111" }       │
  │   H  → { value: "{prob:0.1,...}",  hash: "abc222" }       │
  │   M  → { value: "{prob:0.5,...}",  hash: "abc333" }       │
  │   O  → { value: "{childIds:[C,H]}", hash: "def444" }      │  ← NOT usable
  │   P  → { value: "{childIds:[O,M]}", hash: "ghi555" }      │  ← NOT usable
  └───────────────────────────────────────────────────────────┘
         Contents.hash for O and P only covers their own JSON,
         not children's params. We need Merkle hashes.

Step 2: ContentHashIndex.build (JVM, bottom-up)
  ┌───────────────────────────────────────────────────────────┐
  │ computeHash(P)                                            │
  │   → computeHash(O)                                        │
  │       → computeHash(C) → leaf → irminHashes(C) = "abc111" │
  │       → computeHash(H) → leaf → irminHashes(H) = "abc222" │
  │       → portfolio: sha256(sort(["abc111","abc222"]))       │
  │       → O.cacheKey = sha256("abc111|abc222") = "merk-O"    │
  │   → computeHash(M) → leaf → irminHashes(M) = "abc333"     │
  │   → portfolio: sha256(sort(["merk-O","abc333"]))           │
  │   → P.cacheKey = sha256("abc333|merk-O") = "merk-P"       │
  └───────────────────────────────────────────────────────────┘

Step 3: Cache lookup per node
  ┌──────────────────────────────────────────────────┐
  │ ContentCache.get("abc111") → miss → simulate C   │
  │ ContentCache.get("abc222") → miss → simulate H   │
  │ ContentCache.get("abc333") → miss → simulate M   │
  │ ContentCache.get("merk-O") → miss → aggregate O  │  ← cheap
  │ ContentCache.get("merk-P") → miss → aggregate P  │  ← cheap
  └──────────────────────────────────────────────────┘
  First load: 3 Monte Carlo simulations + 2 aggregations.
```

#### Flow 2: Branch switch — 1 leaf changed (UC6)

User switches to `scenario-high`. Cyber's probability changed to 0.9.

```
Step 1: getContents from Irmin (branch: scenario-high)
  ┌───────────────────────────────────────────────────────────┐
  │   C  → hash: "xyz999"   ← DIFFERENT (prob changed)       │
  │   H  → hash: "abc222"   ← same                           │
  │   M  → hash: "abc333"   ← same                           │
  │   O  → hash: "def444"   ← same (childIds didn't change)  │
  │   P  → hash: "ghi555"   ← same (childIds didn't change)  │
  └───────────────────────────────────────────────────────────┘

Step 2: ContentHashIndex.build (JVM)
  ┌───────────────────────────────────────────────────────────┐
  │ computeHash(C) → leaf → "xyz999"                          │
  │ computeHash(H) → leaf → "abc222"  (same as main)          │
  │ computeHash(O) → sha256("abc222|xyz999") = "merk-O2"      │  ← DIFFERENT
  │ computeHash(M) → leaf → "abc333"  (same as main)          │
  │ computeHash(P) → sha256("abc333|merk-O2") = "merk-P2"     │  ← DIFFERENT
  └───────────────────────────────────────────────────────────┘
         Notice: H and M get the SAME hash as on main.
         O and P get NEW hashes because a descendant changed.

Step 3: Cache lookup
  ┌──────────────────────────────────────────────────┐
  │ ContentCache.get("xyz999")  → miss → simulate C  │  ← 1 sim
  │ ContentCache.get("abc222")  → HIT  (from main!)  │
  │ ContentCache.get("abc333")  → HIT  (from main!)  │
  │ ContentCache.get("merk-O2") → miss → aggregate O │  ← cheap
  │ ContentCache.get("merk-P2") → miss → aggregate P │  ← cheap
  └──────────────────────────────────────────────────┘
  Branch switch: 1 simulation + 2 aggregations. H and M reused from main.
```

#### Flow 3: Structural edit — node added (UC7)

User adds `fraud (F)` as a new child of `ops-risk`, on current branch.

```
After write to Irmin:
  ┌───────────────────────────────────────────────────────────┐
  │   C  → hash: "xyz999"   ← unchanged                      │
  │   H  → hash: "abc222"   ← unchanged                      │
  │   F  → hash: "fff777"   ← NEW node                       │
  │   O  → hash: "def888"   ← CHANGED (childIds now [C,H,F]) │
  │   M  → hash: "abc333"   ← unchanged                      │
  │   P  → hash: "ghi555"   ← unchanged (its childIds: [O,M]) │
  └───────────────────────────────────────────────────────────┘

ContentHashIndex.build:
  ┌───────────────────────────────────────────────────────────┐
  │ computeHash(C) → "xyz999"                                 │
  │ computeHash(H) → "abc222"                                 │
  │ computeHash(F) → "fff777"   ← new leaf                    │
  │ computeHash(O) → sha256("abc222|fff777|xyz999") = "merk-O3"│ ← DIFFERENT
  │ computeHash(M) → "abc333"                                 │
  │ computeHash(P) → sha256("abc333|merk-O3") = "merk-P3"     │ ← DIFFERENT
  └───────────────────────────────────────────────────────────┘

Cache lookup:
  ┌──────────────────────────────────────────────────┐
  │ C  → "xyz999"  → HIT                            │
  │ H  → "abc222"  → HIT                            │
  │ F  → "fff777"  → miss → simulate F              │  ← 1 sim
  │ O  → "merk-O3" → miss → aggregate (C+H+F)       │  ← cheap
  │ M  → "abc333"  → HIT                            │
  │ P  → "merk-P3" → miss → aggregate (O+M)         │  ← cheap
  └──────────────────────────────────────────────────┘
  Structural edit: 1 simulation + 2 aggregations.
  C, H, M all reused despite structural change.
```

---

## RiskResult Type Hierarchy

### Overview

The simulation result types form a small sealed hierarchy:

```
LECCurve (trait)
  │  nTrials, probOfExceedance, maxLoss, minLoss
  │
  └─ LossDistribution (sealed abstract class)
       │  nodeId, outcomes: Map[TrialId, Loss], distributionType, outcomeCount
       │
       ├─ RiskResult (case class)
       │    distributionType = LossDistributionType.Leaf     ← ALWAYS
       │    provenances: List[NodeProvenance]
       │    flatten → Vector(this)
       │
       └─ RiskResultGroup (case class)
            distributionType = LossDistributionType.Composite
            children: List[RiskResult]
            flatten → this +: children.sorted
```

### Supporting types

```
LossDistributionType (enum)
  Leaf       used by RiskResult
  Composite  used by RiskResultGroup

LECCurve (trait)
  Pure interface: nTrials, probOfExceedance(threshold), maxLoss, minLoss
  The "Loss Exceedance Curve" — core concept.
  Anything that can answer "P(Loss ≥ X)?" implements this.

LossDistribution (sealed abstract)
  Extends LECCurve, adds:
  - outcomes: Map[TrialId, Loss]    sparse trial→loss map
  - outcomeCount: TreeMap[Loss, Int]  loss→frequency histogram (lazy)
  - outcomeOf(trial): Loss            single trial lookup (0 if absent)
  - flatten: Vector[LossDistribution] for drill-down

RiskResult
  A single loss distribution — the result of simulating one entity.
  Has provenances (audit trail: which seed, which distribution, occurrence count).
  Typeclass instances: Associative, Commutative, Equal, Debug.
  combine(a, b): outer-join outcomes, sum per trial, concat provenances.

RiskResultGroup
  An aggregated distribution that preserves children for drill-down.
  Has children: List[RiskResult] — the individual component results.
  flatten gives [aggregate, child1, child2, ...] for chart rendering.

NodeProvenance
  Audit record: distributionType, entitySeed, occurrenceCount, etc.
  Accumulated through portfolio aggregation via provenances ++ provenances.
```

### The "always Leaf" mismatch

The resolver pipeline builds results bottom-up:

```
Step 1: Simulate leaf "cyber"
  → RiskResult(nodeId=cyber, outcomes={t3→5M, t17→12M, ...},
               nTrials=10000, provenances=[NodeProvenance(cyber)])
     distributionType = Leaf    ✓ correct, it IS a leaf

Step 2: Simulate leaf "hardware"
  → RiskResult(nodeId=hw, outcomes={t8→1M, ...},
               nTrials=10000, provenances=[NodeProvenance(hw)])
     distributionType = Leaf    ✓ correct

Step 3: Aggregate portfolio "ops-risk"
  → RiskResult.combine(cyberResult, hwResult)
  → RiskResult(
      nodeId    = cyber          ← takes first operand's ID
      outcomes  = {t3→5M, t8→1M, t17→12M}   ← outer join + sum
      nTrials   = 10000
      provenances = [cyber-prov, hw-prov]    ← concatenated
    ).withNodeId(ops-risk)                   ← stamped after combine
     distributionType = Leaf    ✗ WRONG — this represents a portfolio
```

The combine method returns `RiskResult` (hardcoded `Leaf` type), not
`RiskResultGroup` (`Composite` type). The resolver uses `combine`, never
`RiskResultGroup.apply`.

**Why `RiskResultGroup` exists but isn't used:**

`RiskResultGroup` preserves `children: List[RiskResult]` — you can drill
down to see individual components. The resolver pipeline doesn't need
this; it only needs the aggregate outcomes for the LEC curve. Building
a `RiskResultGroup` would require keeping child references alive, which
adds memory overhead for a feature (drill-down) not yet exposed in the UI.

**Practical consequence:**

```
ContentCache:
  "abc111" → RiskResult(cyber,    ..., type=Leaf)       ← genuinely a leaf
  "abc222" → RiskResult(hw,       ..., type=Leaf)       ← genuinely a leaf
  "merk-O" → RiskResult(ops-risk, ..., type=Leaf)       ← actually a portfolio aggregate
  "merk-P" → RiskResult(portfolio,..., type=Leaf)       ← actually a portfolio aggregate
```

Every entry looks like a `Leaf`. No information is lost — the outcomes and
provenances are correct. The `distributionType` field is simply misleading.
The `ContentCache` stores `RiskResult` uniformly; it doesn't care about the
type discriminator.

**Should we fix this?** Not now. It's cosmetic. If drill-down features are
added later (showing individual child contributions within a portfolio
curve), the resolver could switch to `RiskResultGroup` for portfolio nodes.
This is a separate concern from caching.
