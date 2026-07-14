# Milestone 2b: Cache & Branching Design

> Empirical validation: `dev/test-irmin-hashes.sh` (9/9 tests passed).
>
> **Status (audited 2026-07-12): designed and validated, NOT implemented.**
> None of the Phase A–E deliverables exist in code (`ContentHash`,
> `ContentCache`, `ContentHashIndex`, `CacheScope`, eviction strategies,
> branch-parameterized `IrminClient` operations, `Scenario*`/`History*`
> services). `TreeCacheManager` is still the live NodeId-keyed cache. The
> only Phase A item present is `BranchRef`, used solely as an Irmin config
> value; `IrminQueries.getValueFromBranch` exists but has no caller.
>
> **This design is the required substrate for scenario branching.** The
> Phase B–E features (scenarios, comparison, merge, time travel) cannot be
> built on the current NodeId-keyed cache — see Problem Statement: branch
> switching would silently return wrong results. See the
> [Review Addendum (2026-07-12)](#review-addendum-2026-07-12) for audit
> insights, a recommended Phase A lean-down, implementation aid, and launch
> prerequisites. Related: `docs/dev/TODO.md` item 17 (the cache-staleness
> bug class this design eliminates) and item 12 (seed derivation — coupled
> to the hashed bytes, see addendum).

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

> **DD-14 is closed (2026-07-14) → Option B, full JVM `sha256`.** This whole
> section retains the Option A vs Option B comparison as the *rationale* for
> that decision — the drift analysis, performance comparison, and
> implementation-simplicity sections below are a record of why, not a live
> choice. The conclusion is in
> [Decision](#decision-option-b-full-jvm--closed-2026-07-14); everything
> downstream of this section (pipeline, worked examples, types, phases)
> is written in Option B terms only.

### Core idea

Replace `Map[(TreeId, NodeId), RiskResult]` with
`Map[ContentHash, RiskResult]`. Two nodes with identical content share one
cache entry regardless of branch or path. Cross-branch sharing is implicit.

A per-branch cache (`Map[(BranchRef, NodeId), RiskResult]`) cannot serve
cross-branch comparison or cache warming — it has no concept of content
equality across branches.

### Leaf hash source (DD-14 — closed: Option B)

> **Closed 2026-07-14 → Option B (full JVM `sha256`).** The analysis below is
> retained as the rationale, not as an open exploration. Option A is recorded
> for the record; it is not a live alternative.

Portfolios need a JVM Merkle hash — that's settled (flat storage layout,
Irmin `Tree.hash` would require hierarchical paths → reparenting cost). The
question was: **where does the leaf cache key come from?**

**Option A — Hybrid: Irmin hash for leaves, JVM Merkle for portfolios** *(rejected)*

| Node type | Cache key | Computed by |
|-----------|-----------|-------------|
| Leaf | Irmin `Contents.hash` | **Irmin** (`get_contents` GraphQL query returns it alongside the value) |
| Portfolio | `sha256(sort(children's cache keys))` | **Scala** (`ContentHashIndex.build`) |

Two different hash systems: Irmin SHA-1 for leaf keys, JVM SHA-256 for
portfolio keys. The portfolio computation takes Irmin's leaf hashes as
opaque string inputs.

**Option B — Full JVM: JVM hash for everything** *(chosen)*

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

### Decision: Option B (full JVM) — closed 2026-07-14

**Option B (full JVM `sha256`) is chosen.** The consistency analysis shows
neither option has drift or wrong-result risk. The performance analysis shows
no meaningful difference. The implementation analysis shows Option B requires
less code, fewer new types, no new Irmin API surface, and is fully
unit-testable without Irmin.

**Decisive argument 1 — the in-memory backend (2026-07-12 review).** The
application also ships an in-memory repository backend
(`RiskTreeRepositoryInMemory` — the default in `docker-compose.yml` and the
backend most unit tests run against). Option A cannot produce leaf hashes
there at all: there is no Irmin to ask. Option B works identically on both
backends, so the content-addressed cache and its staleness guarantees cover
the default dev/demo configuration too. (This also shapes the in-memory
question in [A8](#a8-what-fully-implemented-means-for-feature-liveness) item
3: under Option B the *cache* is backend-agnostic, so only branch semantics —
not cache correctness — remain in question there.)

**Decisive argument 2 — the cache key must be free to diverge from stored
bytes (2026-07-14 review).** Irmin's hash is a function of *bytes stored*. A
cache key must be a function of *content that determines the simulation
result*. These coincide today, but this design already documents two ways
they must diverge — and Option A forecloses both, because you cannot subtract
a field from someone else's hash:

- **[A2](#a2-dedupe-claim-precision--coupling-to-todo-item-12) / TODO item
  12:** if seed derivation moves off ULIDs to content, `id` should be dropped
  from the hashed bytes so true cross-node dedupe becomes sound. Under Option
  A, `id` is in the stored JSON and therefore permanently in the key.
- **[A4](#a4-recommended-phase-a-lean-down-cache-leaf-results-only) trap 1:**
  if portfolios gain aggregation-relevant attributes (mitigation transforms —
  `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md`), the portfolio key must become
  `sha256(portfolioOwnContent | sort(childHashes))`. The key is a design
  artifact, not a storage fact.

`dev/test-irmin-hashes.sh` demonstrates the limitation directly: the `ts1`/`ts2`
case (identical `prob`, different `updatedAt`) asserts the **hashes differ** —
i.e. under Option A any non-simulation metadata in node JSON silently destroys
cache hits. The script's `sep/n1/params` vs `sep/n1/meta` case is the Option A
workaround: **restructure Irmin storage** into params/meta paths to keep
metadata out of the key. Option B needs no storage change — hash the fields
that determine the result.

**Decisive argument 3 — `ContentHash` type integrity.** Under Option A one
`ContentHash` carries both 40-char SHA-1 (leaves) and 64-char SHA-256
(portfolios), so the Iron refinement cannot pin a length — it degrades to
`Match["^([a-f0-9]{40}|[a-f0-9]{64})$"]` or a bare hex constraint. A length
implied by a protocol but not expressed in the type is a **Pass 0a MUST-FIX**
(`code-quality-review`). Option B pins `Match["^[a-f0-9]{64}$"]` exactly. See
[New Types](#new-types).

**Supporting — no SHA-1 on user-influenced bytes.** Leaf JSON derives from
user input (name, probability, distribution params). A SHA-1 collision would
mean two distinct leaves sharing one cache entry — a wrong-result class.
Realistic exploitability is very low (Iron whitelists heavily constrain the
byte space per ADR-029; chosen-prefix collisions are expensive; the payoff is
corrupting your own simulation). It is not a credible attack — but it is a
finding any security review raises, and Option B costs nothing to avoid it.

**What Option B actually requires of Irmin — read determinism, not byte
fidelity.** The requirement is *not* "the bytes Irmin returns equal the bytes
we stored." It is only that the round-trip is a **deterministic function**:
same stored content → same returned bytes, every time. If Irmin re-encoded
values consistently, `sha256(returned)` would still be a sound key. This holds
structurally — Irmin stores opaque blobs and does not know the string is JSON
— and it is not a differentiator: Irmin's own `Contents.hash` is computed over
the value, so a value-transforming Irmin would break Option A identically.

**Drift is self-cleaning; storage is never polluted.** Cache keys never reach
Irmin (ADR-015: persisting results to Irmin is a code smell; `ContentCache` is
a JVM-side `Ref`). A hash change therefore cannot corrupt stored data — the
worst case is orphaned in-memory entries. And every drift trigger (Irmin
upgrade, zio-json bump, JVM change) *requires a process restart*, which clears
the in-memory cache before any new hash is computed. The mass-cache-miss row in
the drift table costs one cold cache after a deploy. Routine orphans — every
parameter edit strands the old hash's entry — are the `EvictionStrategy`'s
problem and are identical under both options.

**Implementation rule (binding).** Hash **the bytes Irmin returns**, never a
re-serialisation of the decoded object. Re-serialising would make cache keys
hostage to zio-json's output stability for no benefit.

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

All worked examples below use **Option B**: every leaf key is
`sha256(jsonBytes)` [Scala], computed at tree-load time from the value
`IrminClient.get` returns. Short placeholders like `"abc111"` and `"merk-O"`
stand in for 64-hex SHA-256 digests throughout — they are illustrative labels,
not literal values.

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

Three data structures participate in cache resolution (Option B — DD-14).

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 1. Leaf hash source                                                      │
│    sha256(jsonBytes)  [Scala — computed at tree-load time from get()]   │
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

For every node in a loaded tree:

```
1. Read node from Irmin:
   IrminClient.get(path, branch)                       [Scala → Irmin GraphQL]
   → returns value: String

2. ContentHashIndex.build(tree, nodeJson)              [Scala, pure computation]
   → leaf:      cacheKey = sha256(value.getBytes)   ← the bytes get() returned
   → portfolio: cacheKey = sha256(sort(children's cacheKeys))

3. ContentCache.get(cacheKey)                          [Scala, Ref lookup]
   → hit:  return cached RiskResult
   → miss: leaf      → Simulator.performTrials(leaf, config)  [Scala, Monte Carlo]
           portfolio → children.reduce(RiskResult.combine)     [Scala, map merge]
           then ContentCache.put(cacheKey, result)             [Scala, Ref update]
```

### Serialization determinism

The cache key is `sha256` over the bytes `IrminClient.get` returns, so key
stability reduces to two properties:

1. **Write determinism (ours):** same logical values → identical JSON string.
   Guaranteed by the codecs below — this is the property that needs guarding.
2. **Read determinism (Irmin's):** same stored content → same returned bytes,
   every time. Note this is *weaker* than byte fidelity: we do not require
   `get` to return exactly what `set` stored, only that the round-trip be a
   deterministic function. Irmin stores opaque blobs and cannot re-encode a
   string it does not know is JSON; and its own `Contents.hash` is computed
   over the value, so a value-transforming Irmin would break its own hashing
   first. Empirically validated by `dev/test-irmin-hashes.sh` (9/9).

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
  // If this breaks, every leaf cache key has changed → mass cache miss.
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
reading all nodes from Irmin. Per DD-14 (Option B) it takes the **raw JSON
bytes `IrminClient.get` returned** per node and hashes them itself — one code
path, no Irmin coupling, unit-testable without a running Irmin:

```scala
import java.security.MessageDigest

object ContentHashIndex:                                     // [Scala]

  def build(
    tree: RiskTree,
    nodeJson: Map[NodeId, String]     // DD-14: the exact bytes get() returned —
  ): Map[NodeId, ContentHash] =       // never a re-serialisation of the decoded node

    val index = scala.collection.mutable.Map.empty[NodeId, ContentHash]

    def computeHash(nodeId: NodeId): ContentHash =
      index.getOrElseUpdate(nodeId,
        tree.index.nodes(nodeId) match
          case _: RiskLeaf =>
            ContentHash(sha256(nodeJson(nodeId)))            // leaf: hash the bytes

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

> **Hash notation (Option B — DD-14):** leaf hashes are
> `sha256(jsonBytes)` [Scala], computed at tree-load time from what
> `IrminClient.get` [Scala→Irmin] returns. Labels like `"abc111"` and
> `"merk-O"` are illustrative stand-ins for 64-hex SHA-256 digests.

### Step 1: First load on `main` (UC1 / UC2)

Tree loaded for the first time. `ContentCache` is empty.

```
IrminClient.get [Scala→Irmin] per node on branch "main":
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
IrminClient.get [Scala→Irmin] on branch "main":
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
IrminClient.get [Scala→Irmin] on branch "scenario-high":
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
IrminClient.get [Scala→Irmin] on branch "main":
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
IrminClient.get [Scala→Irmin] on branch "scenario-high":
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
IrminClient.get [Scala→Irmin] on "scenario-high" at c4:
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
| DD-2 | New `IrminClient` operations | Add 6 ops: `getBranch`, `mergeBranch`, `revert`, `getCommit`, `getHistory`, `lca` | Mechanical GraphQL wrappers. **`getContents` dropped**: DD-14 closed on Option B, so leaf hashes come from `sha256(json)` [Scala] and the existing `get` suffices. Add `getContents` only if a concrete commit-info caller appears — an op with no call site is a code-quality MUST-FIX (§4, unused API is a liability). |
| DD-3 | Cache strategy | Content-addressed: `Map[ContentHash, RiskResult]` | Content-identical nodes share one cache entry regardless of branch. |
| DD-4 | Repository branch threading | Optional `branch` param on trait methods | Comparison workflow needs explicit branch args (read both in one effect). |
| DD-10 | Error types | Flat hierarchy extending `AppError` | `BranchNotFound`, `MergeConflictError`, `CommitNotFound`, etc. Follows existing pattern. **Naming not settled** — `MergeConflict` already exists in the hierarchy; see [A7](#a7-implementation-aid-corrections-against-the-current-codebase). |
| DD-12 | Test backward compat | Default args are source- and binary-compatible | ~60 new tests estimated across new capabilities. |
| DD-14 | Leaf hash source | **Option B — full JVM `sha256(jsonBytes)`** (closed 2026-07-14) | One hash system (SHA-256, uniform 64-hex → tight Iron refinement); no `getContents` in Phase A; no SHA-1; works on the in-memory backend, which Option A cannot. See [Leaf hash source](#leaf-hash-source-dd-14--closed-option-b). |

### Influenced by cache strategy

| DD | Topic | Effect |
|----|-------|--------|
| DD-6 | ScenarioService API | Comparison uses hash-diff (UC5) — no value-level comparison. |
| DD-13 | Implementation order | Foundation includes `ContentCache` from day one. |

### Open (require UX/architecture decisions)

| DD | Topic | Core question |
|----|-------|---------------|
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

Per ADR-001 + ADR-018 these are nominal wrappers over Iron-refined types with
`fromString` delegating to the base smart constructor — **not** raw `String`
wrappers. A `case class X(value: String)` for any of these is a Pass 0a
MUST-FIX (`code-quality-review`). Co-locate with the existing wrappers in
`OpaqueTypes.scala` (§11 Co-location).

```scala
// ALREADY EXISTS — OpaqueTypes.scala:235. Do not redefine; it is Iron-refined
// today and used as an IrminConfig value (no other consumer yet).
case class BranchRef(toBranchRef: BranchRefStr)              // Irmin branch name

// NEW. DD-14 → Option B ⇒ SHA-256 only ⇒ uniform 64 hex chars, so the
// refinement pins the length exactly. (Under the rejected Option A this type
// would have had to straddle 40-char SHA-1 and 64-char SHA-256 — the Pass 0a
// violation that helped close DD-14.)
type ContentHashConstraint = Match["^[a-f0-9]{64}$"]
type ContentHashStr        = String :| ContentHashConstraint
case class ContentHash(toContentHash: ContentHashStr)        // SHA-256 hex digest

// NEW. Irmin commit hash — refine to Irmin's actual commit-hash charset/length
// before implementing; do not ship as a bare String.
case class CommitHash(toCommitHash: CommitHashStr)

// NEW. ULID, same pattern as TreeId (ADR-018).
case class ScenarioId(toSafeId: SafeId.SafeId)
```

**`IrminContents(value, hash)` is dropped.** It existed solely as the
`getContents` return type under Option A; with DD-14 closed on Option B the
read path returns `String` from the existing `get` and nothing needs the pair.
Reintroduce only alongside a `getContents` caller, if one ever appears (DD-2).

---

## New/Modified Components

| Component | Layer | Change |
|-----------|-------|--------|
| `IrminClient` | Scala→Irmin | Add optional `branch` param to `get`/`set`/`remove`/`list`. Add `getBranch`, `mergeBranch`, `revert`, `getCommit`, `getHistory`, `lca`. (`getContents` dropped — DD-14 → Option B.) |
| `IrminQueries` | Scala | New GraphQL query strings for `branch`, `merge_with_branch`, `revert`, `commit`, `last_modified`, `lcas`. (`get_contents` dropped — DD-14 → Option B.) Note `getValueFromBranch` already exists here with no caller — the branch-parameterised `get` should subsume it rather than sit alongside it. |
| `RiskTreeRepositoryIrmin` | Scala→Irmin | Thread `branch` param. Use the existing `get`; pass the returned JSON to `ContentHashIndex.build`, which hashes it (DD-14 → Option B). No read-path type change. |
| `ContentCache` (new) | Scala | `Ref[Map[ContentHash, RiskResult]]` with `EvictionStrategy`. Replaces `RiskResultCache`. |
| `ContentHashIndex` (new) | Scala | At tree load: leaf hashes = `sha256(json bytes returned by get)`, portfolio Merkle hashes computed bottom-up (DD-14 → Option B). Pure function, unit-testable without Irmin. Returns `Map[NodeId, ContentHash]`. |
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

**6. Name change triggers re-simulation.** Renaming a node changes its JSON
bytes (the JSON includes `name`) → new `sha256` → cache miss → re-simulation,
even though `name` does not affect simulation results.

> Impact: accepted. Renames are rare and re-simulating one node is fast
> (~1–5ms at 10K trials).
>
> **Revised under DD-14 → Option B (2026-07-14).** This finding previously
> argued that narrowing the hash preimage was infeasible because it was "the
> canonicalisation complexity that Irmin delegation avoids." That reasoning
> died with Option A: we now hash bytes we choose, so hashing only
> simulation-relevant fields is *available* — it is simply not worth it yet.
> Do **not** narrow the preimage in isolation: `id` must stay in the hashed
> bytes while seeds derive from ULIDs
> ([A1](#a1-first-principle--state-it-explicitly),
> [A2](#a2-dedupe-claim-precision--coupling-to-todo-item-12)), so preimage
> narrowing is coupled to TODO item 12 and must be decided with it.

> Impact: Unnecessary work on rename. Accepted.

---

## Phase Outline

```
Phase A: Foundation
  - ContentHash, CommitHash types (BranchRef exists)      [Scala]
  - IrminClient branch parameterization                   [Scala→Irmin]
  - IrminClient branch operations (create/merge/revert)   [Scala→Irmin]
  - Repository branch threading                           [Scala]
  - ContentCache + NoOpEvictionStrategy                   [Scala]
  - ContentHashIndex (leaf: Irmin hash, portfolio: Merkle) [Scala]
  - CacheScope → RiskResultResolver wiring                [Scala]
  - Retire TreeCacheManager                               [Scala]

Phase B: Scenario CRUD + Minimal UI
  - ScenarioService (create/list/delete/switch)           [Scala]
  - BranchBar UI component                                [Scala.js/Laminar]
  - End-to-end: create scenario, switch, edit, switch back

Phase C: Comparison
  - ScenarioDiff service (hash-based diff, UC5)           [Scala]
  - Comparison view in Analyze section                     [Scala.js/Laminar]
  - Cross-branch cache reuse (UC6, implicit)

Phase D: Merge
  - ScenarioMerger (Irmin merge_with_branch)              [Scala→Irmin]
  - Merge preview + confirm flow
  - Conflict handling

Phase E: History / Time Travel
  - HistoryService (commit log, point-in-time, revert)    [Scala→Irmin]
  - CommitHistoryPanel UI                                  [Scala.js/Laminar]
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

---

## Review Addendum (2026-07-12)

Full audit of this design against the codebase, combined with the live
root-cause investigation of the cache-staleness bug (`docs/dev/TODO.md`
item 17). Records insights, corrections, a recommended lean-down, and what
"scenario branching is live" actually requires.

### A1. First principle — state it explicitly

**The cache key must cover every input that determines the cached result.**
Everything else in this design is a consequence. Current coverage:

| Result input | Covered by the key? | Note |
|---|---|---|
| Leaf params (probability, distribution) | Yes — in the leaf JSON bytes | The point of the design |
| Leaf `id` (ULID) | Yes — in the leaf JSON bytes | **Load-bearing**: `entitySeed` is derived from the ULID (`Simulator.createSamplerFromLeaf`, TODO item 12). Do NOT drop `id` from the hashed bytes while seeds derive from ULIDs. |
| `SimulationConfig` (nTrials, seeds) | No | Tolerable **only** while the cache is in-memory (restart clears). If the cache is ever persisted or config becomes per-branch, extend the key (DD-9b). |
| Portfolio's own content | No — key is `sha256(sort(childHashes))` | Safe only while confirmed assumption 4 holds. See trap A4. |

### A2. Dedupe claim precision + coupling to TODO item 12

"Content-identical nodes share one cache entry" is imprecise: the leaf JSON
includes `id`, so two *different* leaves with identical parameters hash
differently and never share. All seven UCs still work — every UC concerns
the *same node* across branches/history, where ids match.

This imprecision is currently **correct behavior**: with ULID-derived seeds,
same-param/different-id leaves legitimately produce different Monte Carlo
results, so they must not share an entry. If TODO item 12 moves seed
derivation to content (name/param hash), `id` can be dropped from the hashed
bytes and true cross-node dedupe becomes sound. **Decide items 12 and this
design together** — changing one silently changes the correctness terms of
the other.

### A3. The strongest motivation, previously unstated: scenario comparability

Copy-based scenarios ("scenario = clone of the tree") are structurally broken
today: cloned nodes get new ULIDs → new seeds → different simulation results
for identical parameters → the scenario-vs-main diff shows phantom
differences the user did not cause. Branch-based scenarios keep node ids
stable across branches → same seeds → **differences reflect only the user's
edits**. This is the headline argument for Irmin branches over tree copies,
and it holds independent of any caching benefit.

### A4. Recommended Phase A lean-down: cache leaf results only

Portfolio *result* caching is the least valuable, most trap-laden element:

- Confirmed assumption 1 already says aggregation is cheap (sparse map
  merge, no sampling); re-aggregating a 100-node tree costs milliseconds.
- **Trap 1:** the portfolio key omits the portfolio's own content. If
  portfolios ever gain aggregation-relevant attributes — mitigation
  transforms (`RiskTransform`, `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md`)
  are heading exactly there — a stale-by-construction key reintroduces the
  silent wrong-result class this design exists to eliminate. The key would
  need to become `sha256(portfolioOwnContent | sort(childHashes))`.
- **Trap 2:** `sorted` child hashes hard-code commutative aggregation.
  Any future weighted/ordered combination breaks the key silently.

**Lean variant:** `ContentCache` stores **leaf** results only; portfolios
always re-aggregate on read. `ContentHashIndex` stays exactly as designed —
it is a pure function computed per request and still serves the UC5 diff
(which needs the hash index, not the result cache). Effect on the worked
examples: Step 5 (time travel) changes from "0 sims, 0 aggs" to "0 sims,
2 aggs" — same user-visible latency class. Portfolio caching can be added
later behind `CacheScope` if profiling ever demands it. Bonus: only genuine
leaves get cached, which dissolves the "always Leaf" `distributionType`
mismatch for cache entries (see previous section).

### A5. Scope honesty on UC4/UC6

The cache is an in-memory `Ref`. "Time travel: 0 simulations" and free
branch-switch warming hold **within one server session**; a restart clears
everything, and multiple replicas would each hold cold private caches.
These are UX-latency features, not capacity features. (This is also what
makes excluding `SimulationConfig` from the key tolerable — A1.)

### A6. Relationship to the invalidation bug (TODO item 17)

Confirmed root cause (live repro 2026-07-12): a node that is reparented
**and** param-changed in one PUT is never self-invalidated
(`computeAffectedNodes` treats the two as exclusive branches), and no
ancestor/root invalidation can recover, because the resolver recomposes
portfolios from cached child entries. Content addressing removes the entire
hand-written diff class — a changed leaf *is* a different key — and
`InvalidationHandler` survives only for SSE notifications, as the component
table already says. **The tactical item-17 fix must land regardless**: it
protects the current PUT path now and does not conflict with this design.

### A7. Implementation aid (corrections against the current codebase)

- **UI stack:** the SPA is Scala.js + Laminar (ADR-019), not JS/Lit — the
  phase outline has been corrected. BranchBar/comparison/history panels are
  Laminar components in `modules/app`.
- **Endpoints:** define scenario/history endpoints once in `modules/common`
  as Tapir endpoints (JVM server routes + SPA sttp clients derive from the
  same definition, per the existing pattern).
- **Error types:** `BranchNotFound`, `MergeConflictError`, `CommitNotFound`
  (DD-10) join the sealed `AppError` hierarchy; inexhaustive matches are
  compile **errors**, so every existing match site must be updated — budget
  for that sweep.
- **Error-type collision — settle before Phase A, not during it.** DD-10 names
  `MergeConflictError`, but `MergeConflict(branch: String, details: String)`
  **already exists** as a `SimError` subtype in `AppError.scala`, with a 409
  `ErrorResponse` mapping (`makeMergeConflictResponse`, domain `"scenarios"`)
  and round-trip codec tests. Adding `MergeConflictError` alongside it would
  give two types for one condition. Two ways out:
  - **(a) Reuse `MergeConflict`,** drop `MergeConflictError` from DD-10. No new
    type, no match-site churn for this case, and the 409 mapping already works.
    Cost: the branch field stays a raw `String` rather than `BranchRef`,
    against ADR-018 — and the decoder already notes the branch name is lost
    through HTTP (`case "branch" => MergeConflict("unknown", message)`).
  - **(b) Introduce `MergeConflictError`** carrying `BranchRef` and retire
    `MergeConflict`. ADR-018-clean and fixes the lossy decode. Cost: touches
    every existing match site (inexhaustive matches are compile errors), plus
    the `ErrorResponse` codec and its tests.

  Either way this renames or replaces an existing public type and reshapes an
  error response — Decision Triggers 4 and 8. It is a user decision, and taking
  it before Phase A avoids discovering it mid-sweep.
- **Auth:** this design predates ADR-024/ADR-030. Scenario and history
  endpoints need the same `Checked`-permission wiring as existing tree
  endpoints, and DD-11 (workspace ↔ scenario ownership) is now partly an
  authorization-model question, not just a naming convention.
- **New types** follow ADR-001/ADR-018: Iron refinements + nominal wrappers
  (`ContentHash`, `CommitHash`, `ScenarioId` as listed under New Types).
- **Workspace reaper:** cascade deletion currently covers trees; scenario
  branches of a reaped workspace are new state that must be cleaned too.

### A8. What "fully implemented" means for feature liveness

Phase B alone ships a usable end-to-end slice (create scenario, switch,
edit, switch back); C–E complete the feature (comparison, merge, history).
But "live" has preconditions **outside** the phase outline:

1. **Open decisions:** DD-5, DD-7, DD-8, DD-9, DD-11 must be closed. (DD-14
   was closed 2026-07-14 → Option B; see
   [Decision](#decision-option-b-full-jvm--closed-2026-07-14).) DD-8 (branch
   state: header vs session, the two-tab problem) is the hardest UX call.
2. **Deployment backend:** branching requires the Irmin repository, and the
   default compose stack still runs in-memory. TODO item 10 (`--profile
   persistence` was a no-op for the server) was **resolved 2026-07-12** by
   completing the `--env-file .env.irmin` path — no longer a blocker; the
   sentence above predated the fix by hours. The residual is TODO item 19:
   that fix was verified **statically only** (`docker compose --env-file
   .env.irmin.example config` resolves the backends), with no live
   restart-persistence test yet. Scenario branching should not be the first
   feature to exercise the persistent tier end-to-end.
3. **In-memory story:** decide explicitly what the in-memory backend does —
   feature-flagged off (scenario endpoints return 404/NOT_SUPPORTED) or
   branch semantics emulated in memory. The plan currently has no answer;
   silent partial behavior is not an option.
4. **Frontend phases included:** "fully implemented" per the outline already
   includes the Laminar UI (BranchBar, comparison view, history panel), so
   no separate frontend project remains.

With 1–3 resolved: yes — scenario branching is live for Irmin-backed
deployments at Phase B (minimal) / Phase E (complete).
