# Milestone 2b: Cache & Branching Design

> Empirical validation: `dev/test-irmin-hashes.sh` (9/9 tests passed).

---

## Problem Statement

The current cache maps `(TreeId, NodeId) → RiskResult`. Every tree lives on
`main`. Introducing branches means the same `(TreeId, NodeId)` can hold
different parameter values on different branches. The cache cannot distinguish
between them — **branch switching silently returns wrong results.**

The cache must support seven workflows:

| # | Workflow | Cache requirement |
|---|---------|-------------------|
| UC1 | Tree creation | Nothing — all nodes are new, simulate everything |
| UC2 | Loading a stored tree | Look up existing results by content identity |
| UC3 | Parameter edit on a branch | Detect changed nodes, reuse unchanged |
| UC4 | Time travel / revert | Reuse results from historical states if content matches |
| UC5 | Branch comparison (diff view) | O(1) per-node equality check across two branches |
| UC6 | Branch switch (cache warming) | Reuse results from the prior branch for identical nodes |
| UC7 | Structural edit (add/remove/move) | Invalidate parent aggregation while preserving sibling results |

---

## Cache Strategy

### Core idea (shared by all options below)

Replace `Map[(TreeId, NodeId), RiskResult]` with
`Map[ContentHash, RiskResult]`. Two nodes with identical content share one
cache entry regardless of branch or path. Cross-branch sharing is implicit.

A per-branch cache (`Map[(BranchRef, NodeId), RiskResult]`) cannot serve
cross-branch comparison or cache warming — it has no concept of content
equality across branches.

### The open question: who computes the leaf hash?

Portfolios need a JVM Merkle hash — that's settled (flat storage layout,
Irmin `Tree.hash` would require hierarchical paths → reparenting cost). The
question is: **where does the leaf cache key come from?**

**Option A — Hybrid: Irmin hash for leaves, JVM Merkle for portfolios**

| Node type | Cache key | Computed by |
|-----------|-----------|-------------|
| Leaf | Irmin `Contents.hash` | **Irmin** (`get_contents` GraphQL query returns it alongside the value) |
| Portfolio | `sha256(sort(children's cache keys))` | **Scala** (`ContentHashIndex.build`) |

Two different hash systems: Irmin SHA-1 for leaf keys, JVM SHA-256 for
portfolio keys. The portfolio computation takes Irmin's leaf hashes as
opaque string inputs.

**Option B — Full JVM: JVM hash for everything**

| Node type | Cache key | Computed by |
|-----------|-----------|-------------|
| Leaf | `sha256(leafJsonBytes)` | **Scala** (`ContentHashIndex.build`) |
| Portfolio | `sha256(sort(children's cache keys))` | **Scala** (`ContentHashIndex.build`) |

One hash system: JVM SHA-256 for everything. No dependency on Irmin's
internal hashing. `IrminClient.get` [Scala→Irmin] returns the JSON string;
we hash it ourselves.

### Consistency analysis: can the hybrid drift?

The concern: two different hash implementations (Irmin SHA-1 + JVM SHA-256)
participating in one cache key computation. Can they produce inconsistent
results?

**Trace through a concrete example** (using the reference tree):

```
Write:  IrminClient.set("nodes/C", '{"id":"C","prob":0.3,…}')  [Scala→Irmin]
        Irmin stores these exact bytes. Computes SHA-1 internally.

Read:   IrminClient.getContents("nodes/C")                      [Scala→Irmin]
        → ("{"id":"C","prob":0.3,…}", "abc111")
        Irmin returns the bytes it stored + the SHA-1 it computed on write.

Cache:  ContentHashIndex.build takes "abc111" as an opaque string.
        It never re-hashes the leaf bytes. It never calls SHA-1 itself.
        Portfolio key: sha256("abc111|abc222") — operates on STRING inputs.
```

**There is no bidirectional coupling.** The data flows in one direction:

```
  Irmin SHA-1(bytes) → leaf key string → JVM SHA-256(leaf key strings) → portfolio key
```

The JVM Merkle layer does not depend on the leaf hash being SHA-1. It
treats `"abc111"` as an opaque identifier. If Irmin used SHA-256, or
BLAKE2, or a random UUID — the portfolio hash would still be deterministic
as long as the same leaf content produces the same string.

**When can drift occur? Enumerated:**

| Scenario | Hybrid (Option A) | Full JVM (Option B) | Outcome |
|----------|-------------------|---------------------|---------|
| Same JSON bytes → same leaf key? | Yes: Irmin SHA-1 is deterministic | Yes: JVM SHA-256 is deterministic | Both safe |
| Irmin version upgrade changes hash algorithm | Leaf keys change → portfolio keys change → mass cache miss | Not affected (JVM computes its own) | **Option B is isolated.** But hybrid failure mode is cache miss, not wrong results. Cache is in-memory → restart clears it anyway. |
| Irmin re-encodes stored bytes (e.g., pretty-prints JSON) | Different bytes → different hash → cache miss | If `get` returns re-encoded bytes, JVM hashes those → same problem | **Both affected equally** — Irmin stores opaque blobs, validated empirically. |
| zio-json version changes serialization | Next write produces different bytes → new hash | Same | **Both affected equally** |
| JVM SHA-256 changes output | N/A for leaves | Leaf keys change | **Only Option B affected** (but `MessageDigest` is JDK-spec'd, never changes) |

**Key finding:** There is no scenario where the hybrid produces *wrong
results* (cache hit returning stale data). The worst case is a cache miss
(hash changes → lookup misses → re-simulate). And that worst case is
identical for both options — it's caused by serialization changes, not by
the hash algorithm mismatch.

### Cross-system hash matching concern

A related concern: could there be a flow where the same value is hashed
once by Irmin and once by the JVM, and the two hashes must agree for
correct cache lookup? If so, any subtle difference (serialization, byte
order, encoding) would cause silent mismatches.

**Analysis:** In both options, every value's cache key has exactly one
producer. The two hash systems operate on disjoint inputs:

| Data | Who hashes it | Who consumes the hash | Other system ever re-hashes it? |
|------|--------------|----------------------|-------------------------------|
| Leaf JSON bytes | Irmin (Option A) or JVM (Option B) — never both | JVM — as opaque string input to portfolio Merkle | No. JVM passes it through in Option A. Irmin never sees it in Option B. |
| Portfolio cache key string (e.g. `"abc111\|abc222"`) | JVM (`sha256`) | JVM — cache lookup | Irmin never sees this string. It is a JVM-only construct. |

There is no flow — including hypothetical pre-check scenarios — where both
systems hash the same bytes and the results must match. In Option A, the
only data that crosses the boundary is the JSON bytes themselves (not
hashes): `leaf.toJson` [Scala] → stored by Irmin → returned by Irmin on
read. Irmin returns the exact bytes stored (opaque blob storage, validated
empirically). This is a byte-fidelity property, not a hash-agreement
property, and it affects both options equally.

**Conclusion:** The cross-system hash matching concern does not apply to
this design. Each cache key has a single authoritative source; no
reconciliation between hash implementations is required.

### Performance comparison

Both options make the same number of Irmin GraphQL round-trips. The read
path is `list("nodes/")` → N × `get` (or `getContents`). Whether the
response includes the Irmin hash (Option A) or not (Option B) does not
change the round-trip count — it is the same HTTP request with marginally
more data in the response for Option A.

Option B adds one `sha256` call per leaf (~1μs each, ~100μs for 100 nodes).
Against network RTT of 1–10ms per GraphQL call, this is negligible. The
expensive operations — Monte Carlo simulation for leaves, map-merge
aggregation for portfolios — are identical in both options and dominate
overall latency by orders of magnitude.

One edge case: pre-checking whether an edit would change a cache key.
Option A calls Irmin's `contents_hash(value:)` [Irmin GraphQL] — one
network round-trip. Option B calls `sha256(newLeaf.toJson)` locally — zero
round-trips.

**No meaningful performance difference between the two options.**

### Implementation simplicity

**Design surface:**

| Concern | Option A (hybrid) | Option B (full JVM) |
|---------|-------------------|---------------------|
| New Irmin API method | `getContents` — new query, new response type `IrminContents(value, hash)` | None — uses existing `get` |
| Read-path type change | Every method returning node data must switch from `String` to `(String, ContentHash)` | No type changes on the read path |
| `ContentHashIndex.build` input | `Map[NodeId, ContentHash]` from Irmin hashes, threaded through repository | Node JSON values (already available from existing `get` calls) |

**Implementation effort:**

Option A requires: a new `getContents` GraphQL query in `IrminQueries`, a
new `getContents` method in `IrminClient`, a new `IrminContents` case
class, repository-layer changes to call `getContents` instead of `get`, and
mapping of Irmin hashes into `ContentHashIndex.build`.

Option B requires: one `sha256` utility function. This function is already
needed for portfolio Merkle hashes — leaf hashing reuses it.
`ContentHashIndex.build` receives the tree and node JSON values (already
available from existing `get` calls) and produces all cache keys in one
pure function. No new Irmin API surface.

**Testability and maintainability:**

- Option A: leaf cache key correctness depends on Irmin's behavior.
  Verifying hash stability requires integration tests against a running
  Irmin instance. Two code paths exist for cache key computation: Irmin
  passthrough (leaves) and JVM Merkle (portfolios).
- Option B: cache key computation is a self-contained pure Scala function.
  Unit-testable without Irmin. One code path for all nodes: `sha256`.
  Cache correctness has zero coupling to Irmin internals.

**Option B is simpler** across design, implementation, and maintenance.
Option A's "hash comes for free" advantage applies to CPU cost (~1μs per
leaf), not to code cost — the Irmin query, response type, and threading
still need to be written and maintained.

### Recommendation

**Option B (full JVM)** is the stronger choice. The consistency analysis
shows neither option has drift or wrong-result risk. The performance
analysis shows no meaningful difference. The implementation analysis shows
Option B requires less code, fewer new types, no new Irmin API surface, and
is fully unit-testable without Irmin.

Migration from A to B (or B to A) is mechanical — replace one line in
`ContentHashIndex.build`. The cache structure, pipeline, and worked examples
are identical for both options.

| Aspect | Option A (hybrid) | Option B (full JVM) |
|--------|-------------------|---------------------|
| Leaf hash source | Irmin `Contents.hash` | `sha256(json)` [Scala] |
| Portfolio hash source | `sha256(sort(children))` [Scala] | `sha256(sort(children))` [Scala] |
| Hash algorithms in play | SHA-1 (Irmin) + SHA-256 (JVM) | SHA-256 only |
| Drift risk | None — pipeline, not peers | None — single system |
| Wrong-result risk | None | None |
| Worst-case failure | Mass cache miss (correct behavior) | Mass cache miss (correct behavior) |
| New Irmin API surface | `getContents` query + `IrminContents` type | None |
| Read-path type changes | Yes (`String` → `(String, ContentHash)`) | None |
| New code paths | 2 (Irmin passthrough + JVM Merkle) | 1 (`sha256` for all nodes) |
| Unit-testable without Irmin | No (leaf keys depend on Irmin) | Yes |
| Pre-compute key without write | `contents_hash` query (1 round-trip) | Local `sha256` (0 round-trips) |
| Migration A↔B | Replace 1 line in `ContentHashIndex.build` | — |

All worked examples below use Option A notation (leaf keys from Irmin). If
Option B is chosen, replace every "Irmin hash passthrough" with
"`sha256(json)` [Scala]" — the cache hit/miss outcomes are identical.

---

## Reference Tree

All examples in this document use this 5-node risk tree.

### Logical structure

```
                   portfolio (P)
                   ├── ops-risk (O)       ← portfolio
                   │   ├── cyber (C)      ← leaf, Lognormal, prob=0.3
                   │   └── hardware (H)   ← leaf, Lognormal, prob=0.1
                   └── market (M)         ← leaf, Lognormal, prob=0.5
```

### Irmin storage (flat layout)

Each node is stored at `risk-trees/{treeId}/nodes/{nodeId}` as a JSON string.
Metadata lives at `risk-trees/{treeId}/meta`, separate from node data.

```
risk-trees/tree-1/
  meta  → {"name":"My Portfolio","createdAt":"…"}
  nodes/
    P   → {"id":"P","name":"portfolio","parentId":null,"childIds":["O","M"]}
    O   → {"id":"O","name":"ops-risk","parentId":"P","childIds":["C","H"]}
    C   → {"id":"C","name":"cyber","parentId":"O","distributionType":"Lognormal",
           "probability":0.3,"minLoss":1000,"maxLoss":50000}
    H   → {"id":"H","name":"hardware","parentId":"O","distributionType":"Lognormal",
           "probability":0.1,"minLoss":500,"maxLoss":20000}
    M   → {"id":"M","name":"market","parentId":"P","distributionType":"Lognormal",
           "probability":0.5,"minLoss":2000,"maxLoss":100000}
```

### Hash structure (after first load on `main`)

Three data structures participate in cache resolution. The diagram uses
**Option A (hybrid)** notation. For Option B (full JVM), replace layer 1
with `sha256(jsonBytes)` [Scala] per node, and replace "passthrough" in
layer 2 with `sha256(leaf.toJson)`.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 1. Leaf hash source                                                      │
│    Option A: Irmin Contents.hash  [Irmin — returned by getContents]     │
│    Option B: sha256(jsonBytes)    [Scala — computed at tree-load time]  │
│                                                                          │
│    Node │ Hash          │ Covers                                         │
│    ─────┼───────────────┼──────────────────────────────────────────────  │
│    C    │ "abc111"      │ C's JSON bytes (prob, dist, minLoss, maxLoss…) │
│    H    │ "abc222"      │ H's JSON bytes                                 │
│    M    │ "abc333"      │ M's JSON bytes                                 │
│    O    │ "def444"      │ O's JSON bytes ({id, childIds:[C,H]} only!)    │
│    P    │ "ghi555"      │ P's JSON bytes ({id, childIds:[O,M]} only!)    │
│                                                                          │
│    ⚠ Portfolio hashes (O, P) do NOT reflect children's parameters.      │
│    They can NOT be used as cache keys for simulation results.            │
└──────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ 2. ContentHashIndex           [Scala — built at tree-load time]         │
│    ContentHashIndex.build(tree, leafHashes) → Map[NodeId, ContentHash]  │
│                                                                          │
│    Node │ Cache key    │ Source                                           │
│    ─────┼──────────────┼───────────────────────────────────────────────  │
│    C    │ "abc111"     │ Leaf hash passthrough (from layer 1)            │
│    H    │ "abc222"     │ Leaf hash passthrough (from layer 1)            │
│    M    │ "abc333"     │ Leaf hash passthrough (from layer 1)            │
│    O    │ "merk-O"     │ sha256(sort(["abc111","abc222"]))  ← JVM Merkle│
│    P    │ "merk-P"     │ sha256(sort(["abc333","merk-O"]))  ← JVM Merkle│
│                                                                          │
│    Leaf keys = layer 1 hash. Portfolio keys = JVM Merkle hash.          │
│    Portfolio Merkle hashes propagate child changes upward.               │
└──────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────────┐
│ 3. ContentCache               [Scala — Ref[Map[ContentHash, RiskResult]]│
│                                                                          │
│    Cache key  │ Value                                                    │
│    ───────────┼────────────────────────────────────────────────────────  │
│    "abc111"   │ RiskResult(cyber,     outcomes={t3→5M, t17→12M, …})     │
│    "abc222"   │ RiskResult(hardware,  outcomes={t8→1M, …})              │
│    "abc333"   │ RiskResult(market,    outcomes={t2→3M, t11→8M, …})     │
│    "merk-O"   │ RiskResult(ops-risk,  outcomes={t3→5M, t8→1M, …})      │
│    "merk-P"   │ RiskResult(portfolio, outcomes={t2→3M, t3→5M, …})      │
│                                                                          │
│    Keyed by cache key from ContentHashIndex, NOT by (branch, nodeId).   │
│    Same key from any branch → same entry. No duplication.               │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## How It Works

### Cache resolution pipeline

For every node in a loaded tree (shown with Option A / Option B variants):

```
1. Read node from Irmin:
   Option A: IrminClient.getContents(path, branch)   [Scala → Irmin GraphQL]
             → returns (value: String, hash: ContentHash)
   Option B: IrminClient.get(path, branch)            [Scala → Irmin GraphQL]
             → returns value: String  (no hash)

2. ContentHashIndex.build(tree, leafHashes)            [Scala, pure computation]
   → leaf:      Option A: cacheKey = irminHash (passthrough)
                Option B: cacheKey = sha256(value.getBytes)
   → portfolio: cacheKey = sha256(sort(children's cacheKeys))   [both options]

3. ContentCache.get(cacheKey)                          [Scala, Ref lookup]
   → hit:  return cached RiskResult
   → miss: leaf      → Simulator.performTrials(leaf, config)  [Scala, Monte Carlo]
           portfolio → children.reduce(RiskResult.combine)     [Scala, map merge]
           then ContentCache.put(cacheKey, result)             [Scala, Ref update]
```

### Serialization determinism

Both options depend on the same JSON bytes producing the same hash. For
Option A, Irmin hashes the bytes we store. For Option B, the JVM hashes the
bytes Irmin returns. Either way: same logical values → identical string →
identical hash.

The current codecs guarantee this:

- `RiskLeaf` and `RiskPortfolio` serialize through `*Raw` intermediate case
  classes (`RiskLeafRaw`, `RiskPortfolioRaw`) with `DeriveJsonCodec.gen`
  [Scala, zio-json]. The derived encoder emits keys in **case class field
  declaration order** (compile-time macro). No HashMap iteration, no randomness.
- zio-json produces compact JSON (no whitespace).
- `Double.toString` is deterministic per the Java spec.
- `None` fields are omitted (not written as `null`). Stable.

**Risk vectors:**

| Risk | Impact | Mitigation |
|------|--------|------------|
| Reorder fields in `*Raw` case classes | All hashes change → mass cache miss (no incorrect results) | Treat `*Raw` field order as a storage contract. Add a comment. |
| zio-json version upgrade changes encoding | Different bytes → different hashes | Cache is in-memory → restart clears it. Add a serialization snapshot test. |
| Floating-point edge cases | Negligible — `Double.toString` is canonical | None needed. |

**Safeguard: serialization snapshot test.**

```scala
test("RiskLeaf JSON serialization is byte-stable") {
  val leaf = RiskLeaf.make(id = "test-1", name = "cyber",
    parentId = Some("ops"), distributionType = Lognormal,
    probability = 0.3, percentiles = None, quantiles = None,
    minLoss = Some(1000L), maxLoss = Some(50000L)).toOption.get
  val json = leaf.toJson              // [Scala, zio-json]
  // If this breaks, leaf cache keys (Option A or B) have changed.
  assertTrue(json ==
    """{"id":"test-1","name":"cyber","parentId":"ops","distributionType":"Lognormal","probability":0.3,"minLoss":1000,"maxLoss":50000}""")
}
```

### Portfolio aggregation cost

`RiskResult.combine` [Scala] = trial-aligned loss summation via sparse map
merge. This is O(|union-of-trial-IDs| × nChildren) — **no Monte Carlo
sampling**. Re-aggregating a portfolio on cache miss is cheap compared to
leaf simulation.

---

## JVM Merkle Hash Computation

### Algorithm

`ContentHashIndex.build` [Scala, pure] runs once at tree-load time, after
reading all nodes from Irmin. The `leafHashes` parameter comes from either
Irmin `getContents` (Option A) or `sha256(jsonBytes)` (Option B):

```scala
import java.security.MessageDigest

object ContentHashIndex:                                     // [Scala]

  def build(
    tree: RiskTree,
    leafHashes: Map[NodeId, ContentHash]    // Option A: from getContents
  ): Map[NodeId, ContentHash] =             // Option B: from sha256(json)

    val index = scala.collection.mutable.Map.empty[NodeId, ContentHash]

    def computeHash(nodeId: NodeId): ContentHash =
      index.getOrElseUpdate(nodeId,
        tree.index.nodes(nodeId) match
          case _: RiskLeaf =>
            leafHashes(nodeId)                               // passthrough

          case p: RiskPortfolio =>
            val childHashes = p.childIds
              .map(computeHash)                              // recurse
              .map(_.value)
              .sorted                                        // canonical order
              .mkString("|")
            ContentHash(sha256(childHashes))
      )

    tree.index.rootId.foreach(computeHash)
    index.toMap

  private def sha256(input: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(input.getBytes("UTF-8"))
      .map("%02x".format(_)).mkString
```

### Complexity

- Each node visited exactly once (memoized)
- Per leaf: O(1) map lookup
- Per portfolio: O(k) — sort + concat + SHA-256 (k = children count)
- Total: O(n), O(n log n) worst case from sorting
- 100 nodes ≈ 100μs — invisible against network latency

---

## Flat Layout Rationale

Irmin's `Tree.hash` [Irmin] can propagate child changes through a Merkle
tree natively — if the Irmin path structure mirrors the risk tree hierarchy.
This would eliminate the JVM Merkle computation entirely.

| Aspect | Flat + JVM Merkle (chosen) | Hierarchical + Irmin `Tree.hash` |
|--------|---------------------------|----------------------------------|
| Portfolio cache key | `ContentHashIndex.build` [Scala] at load time | `Tree.hash` [Irmin] — zero JVM |
| Storage migration | None | Must restructure all existing data |
| Reparent a node | Update 2 JSON fields (`parentId` + `childIds`) | Move entire Irmin subtree: O(descendants) writes |
| Add/remove node | Write to `nodes/{id}` | Write under parent's `children/` path |
| List all nodes | `IrminClient.list("nodes/")` [Scala→Irmin] | Recursive tree walk |

Flat keeps reparenting at O(1) (drag-and-drop in the UI). The JVM Merkle
computation is ~100μs for 100 nodes.

---

## Worked Examples

All examples use the [reference tree](#reference-tree) and build on each
other as a **sequential narrative**. Each step shows:
- what changes in Irmin
- what `ContentHashIndex.build` [Scala] produces
- what `ContentCache` [Scala] hits or misses

> **Hash option notation:** Examples use Option A (hybrid) — leaf hashes
> come from `IrminClient.getContents` [Scala→Irmin]. For Option B (full
> JVM), Step 1's `getContents` calls become `get` + `sha256(jsonBytes)`;
> leaf hash values like "abc111" would differ but all cache hit/miss logic
> is identical.

### Step 1: First load on `main` (UC1 / UC2)

Tree loaded for the first time. `ContentCache` is empty.

```
IrminClient.getContents [Scala→Irmin] per node on branch "main":
  ┌───────────────────────────────────────────────────────────┐
  │   C  → { value: "{prob:0.3,…}",    hash: "abc111" }      │
  │   H  → { value: "{prob:0.1,…}",    hash: "abc222" }      │
  │   M  → { value: "{prob:0.5,…}",    hash: "abc333" }      │
  │   O  → { value: "{childIds:[C,H]}", hash: "def444" }     │  ← not a cache key
  │   P  → { value: "{childIds:[O,M]}", hash: "ghi555" }     │  ← not a cache key
  └───────────────────────────────────────────────────────────┘

ContentHashIndex.build [Scala] — bottom-up Merkle:
  ┌───────────────────────────────────────────────────────────┐
  │ computeHash(C) → leaf  → "abc111"                         │
  │ computeHash(H) → leaf  → "abc222"                         │
  │ computeHash(M) → leaf  → "abc333"                         │
  │ computeHash(O) → sha256("abc111|abc222")      = "merk-O"  │
  │ computeHash(P) → sha256("abc333|merk-O")      = "merk-P"  │
  └───────────────────────────────────────────────────────────┘

ContentCache.get [Scala] per node:
  ┌───────────────────────────────────────────────────────────────────┐
  │ .get("abc111") → miss → Simulator.performTrials(C) [Scala] → put │
  │ .get("abc222") → miss → Simulator.performTrials(H) [Scala] → put │
  │ .get("abc333") → miss → Simulator.performTrials(M) [Scala] → put │
  │ .get("merk-O") → miss → RiskResult.combine(C,H)   [Scala] → put │  cheap
  │ .get("merk-P") → miss → RiskResult.combine(O,M)   [Scala] → put │  cheap
  └───────────────────────────────────────────────────────────────────┘
Result: 3 Monte Carlo simulations + 2 aggregations.
ContentCache now has 5 entries (matches hash structure diagram above).
```

### Step 2: Parameter edit — cyber prob 0.3→0.6 on `main` (UC3)

User edits cyber's probability. `IrminClient.set` [Scala→Irmin] writes new
JSON for C. Tree is reloaded.

```
IrminClient.getContents [Scala→Irmin] on branch "main":
  ┌───────────────────────────────────────────────────────────┐
  │   C  → hash: "xyz999"   ← CHANGED (prob now 0.6)         │
  │   H  → hash: "abc222"   ← same                           │
  │   M  → hash: "abc333"   ← same                           │
  │   O  → hash: "def444"   ← same (childIds unchanged)      │
  │   P  → hash: "ghi555"   ← same (childIds unchanged)      │
  └───────────────────────────────────────────────────────────┘

ContentHashIndex.build [Scala]:
  ┌───────────────────────────────────────────────────────────┐
  │ C → "xyz999"                                    ← CHANGED │
  │ H → "abc222"                                              │
  │ M → "abc333"                                              │
  │ O → sha256("abc222|xyz999")      = "merk-O2"   ← CHANGED │
  │ P → sha256("abc333|merk-O2")    = "merk-P2"   ← CHANGED │
  └───────────────────────────────────────────────────────────┘
  C's Irmin hash changed → O's Merkle hash changed → P's Merkle hash changed.

ContentCache.get [Scala]:
  ┌──────────────────────────────────────────────────────────┐
  │ .get("xyz999")  → miss → Simulator.performTrials(C)     │  1 sim
  │ .get("abc222")  → HIT                                   │
  │ .get("abc333")  → HIT                                   │
  │ .get("merk-O2") → miss → RiskResult.combine(C,H)        │  cheap
  │ .get("merk-P2") → miss → RiskResult.combine(O,M)        │  cheap
  └──────────────────────────────────────────────────────────┘
Result: 1 simulation + 2 aggregations. H and M reused.
Old entries ("abc111", "merk-O", "merk-P") are now orphaned in the cache.
```

### Step 3: Branch switch + comparison (UC5 / UC6)

User creates branch `scenario-high` (forked from current `main` where
cyber.prob=0.6). On `scenario-high`, edits market.prob from 0.5 to 0.8.

```
IrminClient.getContents [Scala→Irmin] on branch "scenario-high":
  ┌───────────────────────────────────────────────────────────┐
  │   C  → hash: "xyz999"   ← same as main (forked state)    │
  │   H  → hash: "abc222"   ← same                           │
  │   M  → hash: "mmm888"   ← CHANGED (prob now 0.8)         │
  │   O  → hash: "def444"   ← same                           │
  │   P  → hash: "ghi555"   ← same                           │
  └───────────────────────────────────────────────────────────┘

ContentHashIndex.build [Scala]:
  ┌────────────────────────────────────────────────────────────┐
  │ C → "xyz999"                                               │
  │ H → "abc222"                                               │
  │ M → "mmm888"                                     ← CHANGED │
  │ O → sha256("abc222|xyz999")      = "merk-O2"    ← same!   │
  │ P → sha256("merk-O2|mmm888")    = "merk-P3"    ← CHANGED │
  └────────────────────────────────────────────────────────────┘
  O has the SAME Merkle hash as on main — its entire subtree is identical.

ContentCache.get [Scala]:
  ┌──────────────────────────────────────────────────────────┐
  │ .get("xyz999")  → HIT  (computed on main, Step 2!)      │
  │ .get("abc222")  → HIT  (computed on main, Step 1!)      │
  │ .get("mmm888")  → miss → Simulator.performTrials(M)     │  1 sim
  │ .get("merk-O2") → HIT  (computed on main — same subtree)│
  │ .get("merk-P3") → miss → RiskResult.combine(O,M)        │  cheap
  └──────────────────────────────────────────────────────────┘
Branch switch: 1 simulation + 1 aggregation. C, H, O all reused from main.
```

**Switching back to `main`:**

```
IrminClient.getContents [Scala→Irmin] on branch "main":
  (same hashes as Step 2 — nothing changed on main)

ContentHashIndex.build [Scala]:
  C → "xyz999", H → "abc222", M → "abc333", O → "merk-O2", P → "merk-P2"

ContentCache.get [Scala]:
  ┌──────────────────────────────────────────────────────────┐
  │ All 5 keys → HIT  (all still in cache from Steps 1–2)   │
  └──────────────────────────────────────────────────────────┘
Switch back to main: 0 simulations. Everything cached.
```

**Branch comparison (UC5):** To diff `main` vs `scenario-high`, build both
`ContentHashIndex` maps [Scala] and compare cache keys per node:

```
  ┌────────────────────────────────────────────────────────┐
  │ Node │ main key   │ scen key   │ Status                │
  │ ─────┼────────────┼────────────┼────────────────────── │
  │ C    │ "xyz999"   │ "xyz999"   │ identical              │
  │ H    │ "abc222"   │ "abc222"   │ identical              │
  │ M    │ "abc333"   │ "mmm888"   │ DIFFERENT → show diff  │
  │ O    │ "merk-O2"  │ "merk-O2"  │ identical (subtree)    │
  │ P    │ "merk-P2"  │ "merk-P3"  │ DIFFERENT → show diff  │
  └────────────────────────────────────────────────────────┘
O(n) string comparisons. No JSON diffing needed.
P is flagged because a descendant (M) differs — Merkle propagation.
```

### Step 4: Structural edit — add fraud node on `scenario-high` (UC7)

User adds `fraud (F)` as a new leaf child of `ops-risk`.
Two Irmin writes: `IrminClient.set` [Scala→Irmin] for F (new node) and O
(updated `childIds`).

```
IrminClient.getContents [Scala→Irmin] on branch "scenario-high":
  ┌───────────────────────────────────────────────────────────┐
  │   C  → hash: "xyz999"   ← unchanged                      │
  │   H  → hash: "abc222"   ← unchanged                      │
  │   F  → hash: "fff777"   ← NEW node                       │
  │   O  → hash: "def888"   ← CHANGED (childIds now [C,H,F]) │
  │   M  → hash: "mmm888"   ← unchanged                      │
  │   P  → hash: "ghi555"   ← unchanged (childIds still [O,M])│
  └───────────────────────────────────────────────────────────┘

ContentHashIndex.build [Scala]:
  ┌────────────────────────────────────────────────────────────┐
  │ C → "xyz999"                                               │
  │ H → "abc222"                                               │
  │ F → "fff777"                                     ← NEW     │
  │ O → sha256("abc222|fff777|xyz999") = "merk-O3"  ← CHANGED │
  │ M → "mmm888"                                               │
  │ P → sha256("merk-O3|mmm888")      = "merk-P4"  ← CHANGED │
  └────────────────────────────────────────────────────────────┘

ContentCache.get [Scala]:
  ┌──────────────────────────────────────────────────────────┐
  │ .get("xyz999")  → HIT                                   │
  │ .get("abc222")  → HIT                                   │
  │ .get("fff777")  → miss → Simulator.performTrials(F)     │  1 sim
  │ .get("merk-O3") → miss → RiskResult.combine(C,H,F)      │  cheap
  │ .get("mmm888")  → HIT                                   │
  │ .get("merk-P4") → miss → RiskResult.combine(O,M)        │  cheap
  └──────────────────────────────────────────────────────────┘
Structural edit: 1 simulation + 2 aggregations.
C, H, M all reused despite tree structure change.
```

### Step 5: Time travel — revert to before fraud (UC4)

User wants to undo the fraud addition on `scenario-high`.

Irmin commit history on `scenario-high` at this point:

```
  c1  "branch from main"           C=0.6, H=0.1, M=0.5, tree=[P→[O→[C,H], M]]
  c2  "edit market to 0.8"         C=0.6, H=0.1, M=0.8, tree=[P→[O→[C,H], M]]
  c3  "add fraud to ops-risk"      C=0.6, H=0.1, M=0.8, F=new, tree=[P→[O→[C,H,F], M]]
  ▲ HEAD
```

User calls `IrminClient.revert(commitHash=c2, branch="scenario-high")`
[Scala→Irmin]. Irmin creates a new commit c4 whose tree content is
identical to c2's:

```
  c1  "branch from main"
  c2  "edit market to 0.8"
  c3  "add fraud to ops-risk"
  c4  "revert to c2"               C=0.6, H=0.1, M=0.8, tree=[P→[O→[C,H], M]]
  ▲ HEAD                            (fraud removed, tree structure restored)
```

After revert, the tree is reloaded:

```
IrminClient.getContents [Scala→Irmin] on "scenario-high" at c4:
  ┌───────────────────────────────────────────────────────────┐
  │   C  → hash: "xyz999"   ← same as c2                     │
  │   H  → hash: "abc222"   ← same as c2                     │
  │   M  → hash: "mmm888"   ← same as c2                     │
  │   O  → hash: "def444"   ← restored (childIds:[C,H] again)│
  │   P  → hash: "ghi555"   ← same                           │
  │   (F is gone — removed by revert)                         │
  └───────────────────────────────────────────────────────────┘

ContentHashIndex.build [Scala]:
  ┌───────────────────────────────────────────────────────────┐
  │ C → "xyz999"                                              │
  │ H → "abc222"                                              │
  │ M → "mmm888"                                              │
  │ O → sha256("abc222|xyz999")      = "merk-O2"             │
  │ P → sha256("merk-O2|mmm888")    = "merk-P3"             │
  └───────────────────────────────────────────────────────────┘
  These are EXACTLY the cache keys from Step 3 (before fraud was added).

ContentCache.get [Scala]:
  ┌──────────────────────────────────────────────────────────┐
  │ .get("xyz999")  → HIT  (from Step 2)                    │
  │ .get("abc222")  → HIT  (from Step 1)                    │
  │ .get("mmm888")  → HIT  (from Step 3)                    │
  │ .get("merk-O2") → HIT  (from Step 3)                    │
  │ .get("merk-P3") → HIT  (from Step 3)                    │
  └──────────────────────────────────────────────────────────┘
Time travel: 0 simulations, 0 aggregations.
Reverted content produces identical hashes → identical cache keys → all cached.
```

### Summary

| Step | Workflow | Sims | Aggs | Cache hits | Mechanism |
|------|----------|------|------|------------|-----------|
| 1 | First load (UC1/UC2) | 3 | 2 | 0 | All misses — cold cache |
| 2 | Param edit on `main` (UC3) | 1 | 2 | 2 | H, M unchanged → Irmin hash match |
| 3 | Branch switch (UC6) | 1 | 1 | 3 | C, H, O reused from `main` via content equality |
| 3b | Switch back to `main` | 0 | 0 | 5 | All cached from Steps 1–2 |
| 4 | Structural edit (UC7) | 1 | 2 | 3 | Siblings reused, Merkle propagates to ancestors |
| 5 | Time travel revert (UC4) | 0 | 0 | 5 | Reverted bytes = old hashes = cache hits |
| — | Branch diff (UC5) | 0 | 0 | — | O(n) string compare on cache keys |

---

## Eviction Strategy

Content-addressed caching creates orphan entries: when a node's params change,
the old hash's cache entry is never looked up again.

### Interface

```scala
trait EvictionStrategy:                                      // [Scala]
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
service-wide, set at startup. It is NOT stored in node JSON and therefore NOT
part of Irmin content hashes.

If `SimulationConfig` changes (e.g., server restart with different config),
all cached `RiskResult` entries become stale: same content hash, but results
computed with different trial counts or seeds.

Caches are in-memory `Ref` [Scala]. Server restart empties the cache →
no stale data.

If per-branch `SimulationConfig` is needed in the future, extend the cache
key to `(ContentHash, configHash)`.

---

## Decision Points

### Closed

| DD | Topic | Decision | Rationale |
|----|-------|----------|-----------|
| DD-1 | `IrminClient` branch parameterization | Optional `branch` param on existing methods | Branch-aware reads required for scenarios. Default `None` = backward compatible. |
| DD-2 | New `IrminClient` operations | Add 7 ops: `getContents`, `getBranch`, `mergeBranch`, `revert`, `getCommit`, `getHistory`, `lca` | `getContents` required if Option A (hybrid) chosen (DD-14), otherwise only needed for commit info. Others are mechanical GraphQL wrappers. |
| DD-3 | Cache strategy | Content-addressed: `Map[ContentHash, RiskResult]` | Content-identical nodes share one cache entry regardless of branch. |
| DD-4 | Repository branch threading | Optional `branch` param on trait methods | Comparison workflow needs explicit branch args (read both in one effect). |
| DD-10 | Error types | Flat hierarchy extending `AppError` | `BranchNotFound`, `MergeConflictError`, `CommitNotFound`, etc. Follows existing pattern. |
| DD-12 | Test backward compat | Default args are source- and binary-compatible | ~60 new tests estimated across new capabilities. |

### Influenced by cache strategy

| DD | Topic | Effect |
|----|-------|--------|
| DD-6 | ScenarioService API | Comparison uses hash-diff (UC5) — no value-level comparison. |
| DD-13 | Implementation order | Foundation includes `ContentCache` from day one. |

### Open (require UX/architecture decisions)

| DD | Topic | Core question |
|----|-------|---------------|
| DD-14 | Leaf hash source | Option A (hybrid): Irmin `Contents.hash`. Option B (full JVM): `sha256(jsonBytes)` [Scala]. See [Cache Strategy](#cache-strategy) analysis. Migration A→B is 1-line change. |
| DD-5 | Scenario domain model | Branch naming convention and scenario metadata storage location. |
| DD-7 | HistoryService API | History granularity (raw Irmin commits vs transaction-grouped). Revert UX semantics. |
| DD-8 | HTTP endpoint design | Branch state: client-side header (`X-Active-Branch`) vs server session. Two-tab problem. |
| DD-9 | Frontend UI placement | Branch bar location, comparison view placement in Analyze section. |
| DD-11 | Workspace ↔ scenario ownership | Convention-based prefix matching vs explicit ownership records. |

### Deferred

| DD | Topic | Rationale |
|----|-------|-----------|
| DD-9b | Per-branch SimulationConfig | Cache-clear-on-restart sufficient. Extend to `(ContentHash, configHash)` if needed later. |

---

## New Types

```scala
case class BranchRef(value: String)                          // Irmin branch name
case class ContentHash(value: String)                        // Content hash (Irmin SHA-1 or JVM SHA-256, see DD-14)
case class CommitHash(value: String)                         // Irmin commit hash
case class IrminContents(value: String, hash: ContentHash)   // Value + hash pair
case class ScenarioId(toSafeId: SafeId.SafeId)               // ULID, same pattern as TreeId
```

---

## New/Modified Components

| Component | Layer | Change |
|-----------|-------|--------|
| `IrminClient` | Scala→Irmin | Add optional `branch` param to `get`/`set`/`remove`/`list`. Add `getContents`, `getBranch`, `mergeBranch`, `revert`, `getCommit`, `getHistory`, `lca`. |
| `IrminQueries` | Scala | New GraphQL query strings for `get_contents`, `branch`, `merge_with_branch`, `revert`, `commit`, `last_modified`, `lcas`. |
| `RiskTreeRepositoryIrmin` | Scala→Irmin | Thread `branch` param. Option A: use `getContents` to return hash alongside value. Option B: use `get`, compute hash in `ContentHashIndex`. |
| `ContentCache` (new) | Scala | `Ref[Map[ContentHash, RiskResult]]` with `EvictionStrategy`. Replaces `RiskResultCache`. |
| `ContentHashIndex` (new) | Scala | At tree load: leaf hashes from passthrough (Option A: Irmin, Option B: `sha256(json)`), portfolio Merkle hashes computed bottom-up. Returns `Map[NodeId, ContentHash]`. |
| `CacheScope` (new) | Scala | Abstraction over cache resolution. `RiskResultResolver` calls `CacheScope` instead of `TreeCacheManager`. |
| `TreeCacheManager` | Scala | **Retired.** Replaced by `CacheScope` + `ContentCache`. |
| `InvalidationHandler` | Scala | Simplified — cache misses driven by hash changes, not explicit `ancestorPath` invalidation. Structural mutation logic still needed for SSE notifications. |

---

## Code Review Findings

Review of the simulation/aggregation pipeline against design assumptions.

### Confirmed assumptions

| # | Assumption | Status |
|---|-----------|--------|
| 1 | Leaf simulation is expensive (Monte Carlo), portfolio aggregation is cheap (map merge) | **Confirmed.** Leaf: nTrials occurrence samples + loss quantile. Portfolio: sparse map union + per-trial sum. |
| 2 | Portfolio result = reduce(children) via trial-aligned loss summation | **Confirmed.** `childResults.reduce(RiskResult.combine)` [Scala] → `LossDistribution.merge` = outer join + sum. |
| 3 | Cache invalidation walks ancestor path to root | **Confirmed.** `TreeCacheManager.invalidate` [Scala] → `tree.index.ancestorPath(nodeId)` → `cache.removeAll(path)`. |
| 4 | Portfolio result depends only on children's results, not its own JSON | **Confirmed.** `combine` uses only children's `outcomes` + `provenances`. Portfolio `id` is stamped on via `withNodeId`. |
| 5 | SimulationConfig is global, not per-node | **Confirmed.** Injected once at layer construction. All nodes share `defaultNTrials`, `seed3`, `seed4`. |
| 6 | Each leaf generates sparse Map[TrialId, Loss] | **Confirmed.** `performTrials` [Scala] filters for occurrence first, then samples loss only for hits. |

### Deviations and nuances

**1. No risk-level parallelism in the resolver.** `RiskResultResolverLive`
[Scala] simulates portfolio children **sequentially** via `ZIO.foreach`. The
`Simulator.simulate` method with `ZIO.collectAllPar` exists but is NOT called
by the resolver. Only trial-level parallelism (inside `performTrials`) is
used. Independent subtrees are not parallelised.

> Impact: Follow-up improvement opportunity. Content-addressed cache makes
> this more valuable — fewer nodes need simulation on branch switch, so
> parallelising remaining misses has higher relative payoff.

**2. `RiskResult` is always typed as `Leaf`.** `RiskResult.combine` [Scala]
returns a `RiskResult` with `distributionType = LossDistributionType.Leaf`
even for portfolio aggregates. A separate `RiskResultGroup` class with
`LossDistributionType.Composite` exists but is never produced by the cache
pipeline. Semantic mismatch, no correctness impact.

> Impact: None. `ContentCache` stores `RiskResult` regardless of declared
> type. See [RiskResult Type Hierarchy](#riskresult-type-hierarchy) for
> details.

**3. `nTrials` alignment enforced at combine time.** `RiskResult.combine`
[Scala] calls `require(a.nTrials == b.nTrials)`. All leaves use the same
global `SimulationConfig.defaultNTrials`, so this holds. If the config
changes between simulation runs without a full cache clear, stale entries
with old `nTrials` would cause a **runtime crash** during combine.

> Impact: Reinforces the need to clear `ContentCache` on `SimulationConfig`
> change — which is automatic on restart (in-memory cache).

**4. Provenance is always captured.** `simulateLeaf` [Scala] always records
`NodeProvenance`. The `includeProvenance` parameter only controls whether
provenances are **returned to the caller** at the service layer — they're
always in the cache. Portfolio provenances accumulate all descendant
provenances via `a.provenances ++ b.provenances`.

> Impact: `RiskResult` entries in `ContentCache` always carry full provenance
> chains. Increases entry size for deep portfolios. Not a concern for v1 but
> relevant for eviction sizing estimates.

**5. Entity seed derived from node ID hashCode.** `entitySeed =
leaf.id.value.hashCode.toLong`. The node's ULID determines its PRNG seed.
Same leaf ID + same SimulationConfig = same simulation output, regardless of
branch. Content-addressed caching captures this correctly — the JSON includes
`id`, so the hash changes if the ID changes.

> Impact: None. Seed determinism aligns with content-addressed caching.

**6. Name change triggers re-simulation.** Renaming a node changes its
`Contents.hash` [Irmin] (the JSON includes `name`) → cache miss →
re-simulation, even though `name` does not affect simulation results.
Avoiding this would require hashing only simulation-relevant fields on the
JVM — the exact canonicalisation complexity that Irmin delegation avoids.
Acceptable trade-off: renames are rare, re-simulation of one node is fast.

> Impact: Unnecessary work on rename. Accepted.

---

## Phase Outline

```
Phase A: Foundation
  - BranchRef, ContentHash, CommitHash types              [Scala]
  - IrminClient branch parameterization + getContents     [Scala→Irmin]
  - IrminClient branch operations (create/merge/revert)   [Scala→Irmin]
  - Repository branch threading                           [Scala]
  - ContentCache + NoOpEvictionStrategy                   [Scala]
  - ContentHashIndex (leaf: Irmin hash, portfolio: Merkle) [Scala]
  - CacheScope → RiskResultResolver wiring                [Scala]
  - Retire TreeCacheManager                               [Scala]

Phase B: Scenario CRUD + Minimal UI
  - ScenarioService (create/list/delete/switch)           [Scala]
  - BranchBar UI component                                [JS/Lit]
  - End-to-end: create scenario, switch, edit, switch back

Phase C: Comparison
  - ScenarioDiff service (hash-based diff, UC5)           [Scala]
  - Comparison view in Analyze section                     [JS/Lit]
  - Cross-branch cache reuse (UC6, implicit)

Phase D: Merge
  - ScenarioMerger (Irmin merge_with_branch)              [Scala→Irmin]
  - Merge preview + confirm flow
  - Conflict handling

Phase E: History / Time Travel
  - HistoryService (commit log, point-in-time, revert)    [Scala→Irmin]
  - CommitHistoryPanel UI                                  [JS/Lit]
```

### Follow-up improvements (post-launch)

**Risk-level simulation parallelism.** `RiskResultResolverLive` [Scala]
simulates portfolio children sequentially (`ZIO.foreach`). Only trial-level
parallelism exists (inside `Simulator.performTrials`). A `Simulator.simulate`
method with `ZIO.collectAllPar` exists but is not wired into the
cache-aware resolver.

With content-addressed caching, this optimisation has higher relative payoff:
cross-branch sharing produces fewer cache misses on branch switch, so
remaining misses should resolve as fast as possible. Independent sibling
subtrees can be simulated in parallel.

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
`simulateNode`, constrained by a concurrency semaphore.

---

## RiskResult Type Hierarchy

### Overview

The simulation result types form a small sealed hierarchy:

```
LECCurve (trait)                                             [Scala]
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
LossDistributionType (enum)                                  [Scala]
  Leaf       used by RiskResult
  Composite  used by RiskResultGroup

LECCurve (trait)
  Pure interface: nTrials, probOfExceedance(threshold), maxLoss, minLoss
  The "Loss Exceedance Curve" — answers "P(Loss ≥ X)?".

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
  → Simulator.performTrials(cyber, config)                   [Scala]
  → RiskResult(nodeId=cyber, outcomes={t3→5M, t17→12M, …},
               nTrials=10000, provenances=[NodeProvenance(cyber)])
     distributionType = Leaf    ✓ correct, it IS a leaf

Step 2: Simulate leaf "hardware"
  → RiskResult(nodeId=hw, outcomes={t8→1M, …},
               nTrials=10000, provenances=[NodeProvenance(hw)])
     distributionType = Leaf    ✓ correct

Step 3: Aggregate portfolio "ops-risk"
  → RiskResult.combine(cyberResult, hwResult)                [Scala]
  → RiskResult(
      nodeId    = cyber          ← takes first operand's ID
      outcomes  = {t3→5M, t8→1M, t17→12M}   ← outer join + sum
      nTrials   = 10000
      provenances = [cyber-prov, hw-prov]    ← concatenated
    ).withNodeId(ops-risk)                   ← stamped after combine
     distributionType = Leaf    ✗ WRONG — this represents a portfolio
```

`RiskResult.combine` [Scala] returns `RiskResult` (hardcoded `Leaf` type),
not `RiskResultGroup` (`Composite` type). The resolver uses `combine`, never
`RiskResultGroup.apply`.

**Why `RiskResultGroup` exists but isn't used:**

`RiskResultGroup` preserves `children: List[RiskResult]` for drill-down
(seeing individual component contributions). The resolver only needs aggregate
outcomes for the LEC curve. Building a `RiskResultGroup` would keep child
references alive, adding memory overhead for a UI feature (drill-down) that
is not yet implemented.

**Practical consequence:**

```
ContentCache:
  "abc111" → RiskResult(cyber,    ..., type=Leaf)   ← genuinely a leaf
  "abc222" → RiskResult(hw,       ..., type=Leaf)   ← genuinely a leaf
  "merk-O" → RiskResult(ops-risk, ..., type=Leaf)   ← portfolio aggregate
  "merk-P" → RiskResult(portfolio,..., type=Leaf)   ← portfolio aggregate
```

Every entry is `RiskResult` with `type=Leaf`. The outcomes and provenances are
correct. The `distributionType` field is misleading for portfolios but has no
impact on cache correctness. If drill-down is added later, the resolver can
switch to `RiskResultGroup` for portfolio nodes.
