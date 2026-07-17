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
> bug class this design eliminates — still open) and item 12 (seed identity —
> **CLOSED 2026-07-16**, implemented per `docs/dev/PLAN-SEED-IDENTITY.md`).
>
> **Consistency sweep 2026-07-16 (item 12 closed):** seeds no longer derive
> from ULIDs — `seedVarId` sits inside the stored leaf JSON, but
> workspace-level `seedEntityId` determines the figures but lives in **no
> node's bytes**. Both consequences are now decided: **DD-16 (closed)** — leaf cache
> keys hash a simulation-relevant projection (`seedVarId` + params; `name`
> and ULID excluded), superseding DD-14's hash-the-returned-bytes rule;
> **DD-17 (closed)** — one `ContentCache` per workspace isolates
> `seedEntityId`. A3 is rewritten (branching is justified by merge, history,
> no duplication and zero-care correctness — no longer by comparability,
> which the seed design now guarantees); finding 5 is superseded; DD-15
> narrowed to B vs C at that point (re-scored again by the second sweep
> below).
>
> **Second consistency sweep 2026-07-16 (DD-18/DD-19):** the cache value
> type is decided — **DD-18 (closed)**: a named case class holding
> `TrialOutcomes` plus a content-only provenance record, no node ID inside;
> this also decides the monoid plan's A.1 **Option 1** (explicit
> `TrialOutcomes` type). The provenance record's shape is **DD-19 (open —
> decide last, after every other open decision in this doc and the monoid
> plan)**. Identity-free values re-open the DD-15 option set: equal
> portfolio keys now imply equal figures, so an Option-A key collision is
> correct dedupe — the previous sweep's "Option A definitively dead" verdict
> is withdrawn; see the
> [A4 re-examination](#a4-review-2026-07-14--dd-15-closed-2026-07-16-option-b).
> DD-15 was re-scored to A vs B vs C′ and **closed later the same day →
> Option B** (leaf results only; portfolios always re-aggregate on read).
> The A/C′ alternative is parked as a post-landing follow-up in this doc and
> the monoid plan.

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
`Map[ContentHash, <identity-free value — DD-18>]`. Two nodes with identical
content share one cache entry regardless of branch or path. Cross-branch
sharing is implicit.

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
  12 — closed 2026-07-16; this divergence is now actual, not hypothetical:**
  seed identity is a boundary-assigned `seedVarId` stored on the leaf, and
  ULIDs influence no figure. `id` (and `name`) are therefore *droppable* from
  the hashed bytes — a projection Option A could never express because you
  cannot subtract a field from Irmin's hash. DD-16 (closed 2026-07-16) does
  narrow the preimage exactly this way.
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

**Implementation rule — superseded 2026-07-16 by DD-16.** As originally
written this rule said: hash **the bytes Irmin returns**, never a
re-serialisation — re-serialising would make keys hostage to zio-json's
output stability for no benefit. Item 12's closure inverted the trade:
`name` and `id` sit in those bytes but influence no figure, so hashing raw
bytes over-covers (renames spuriously re-simulate, dedupe never fires).
**DD-16 (closed 2026-07-16): leaf key = `sha256` of a dedicated
simulation-relevant projection** — `seedVarId` + probability + distribution
params, encoded by its own snapshot-tested codec (see New Types). The
stability cost the old rule avoided is real but bounded: the cache is
in-memory, so encoder drift after an upgrade costs one cold cache after a
deploy — this design's accepted failure mode everywhere else. Irmin
byte-fidelity is no longer load-bearing for cache keys at all. DD-14's core
choice (the JVM computes all hashes) is unchanged — in fact only JVM-side
hashing makes a projection possible.

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

> **DD-15 closed 2026-07-16 → Option B: portfolio results are not cached.**
> Layer 2 (`ContentHashIndex`) stays exactly as drawn — portfolio Merkle keys
> are still computed and serve the UC5 diff. Layer 3 holds **leaf entries
> only**; portfolios re-aggregate from child results on every read (linear
> sparse-map merge, A4). The A/C′ alternative (portfolio entries under
> child-key hashing — valid under DD-18 identity-free values) is parked as a
> post-landing follow-up; see the
> [A4 re-examination](#a4-review-2026-07-14--dd-15-closed-2026-07-16-option-b).
> Leaf keys (layer 1) are unaffected.

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
│ 3. ContentCache      [Scala — Ref[Map[ContentHash, <DD-18 value type>]] │
│                                                                          │
│    Cache key  │ Value (identity-free per DD-16/DD-18 — no node ID)       │
│    ───────────┼────────────────────────────────────────────────────────  │
│    "abc111"   │ TrialOutcomes(outcomes={t3→5M, t17→12M, …}) + prov      │
│    "abc222"   │ TrialOutcomes(outcomes={t8→1M, …}) + prov               │
│    "abc333"   │ TrialOutcomes(outcomes={t2→3M, t11→8M, …}) + prov      │
│                                                                          │
│    Leaf entries only — portfolio results are NOT cached (DD-15 → B);    │
│    portfolios re-aggregate from child results on every read.            │
│                                                                          │
│    Keyed by cache key from ContentHashIndex, NOT by (branch, nodeId).   │
│    Same key from any branch → same entry. No duplication. The resolver  │
│    attaches the requested node's ID when it builds the response.        │
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

2. ContentHashIndex.build(tree)                        [Scala, pure computation]
   → leaf:      cacheKey = sha256(projection bytes)  ← seedVarId + params only,
                canonical snapshot-tested encoding (DD-16; name/ULID excluded)
   → portfolio: cacheKey = sha256(sort(children's cacheKeys))  ← UC5 diff only;
                results not cached (DD-15 → B)

3. Leaf: ContentCache.get(cacheKey)                    [Scala, Ref lookup]
   → hit:  return cached content (identity-free, DD-16/DD-18); the resolver
           attaches the requested node's ID when building the response
   → miss: Simulator.performTrials(leaf, config)        [Scala, Monte Carlo]
           then ContentCache.put(cacheKey, <content>)   [Scala, Ref update]

   Portfolio: never cached (DD-15 → B) — aggregate child results on every
   read (today RiskResult.combine; after the monoid plan:
   RiskResultGroup(parentId, children*) — same figures).
```

### Serialization determinism

> **Superseded 2026-07-16 (DD-16):** the leaf key is now `sha256` over the
> leaf's *simulation-relevant projection* (`seedVarId` + probability +
> distribution params), encoded by its own snapshot-tested codec — not over
> the returned bytes. Property 1 below (write determinism) transfers to the
> projection codec verbatim and remains the property to guard; property 2
> (Irmin read determinism) is **no longer load-bearing for cache keys**. The
> snapshot-test safeguard below now targets the projection type.

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
reading all nodes from Irmin. Per DD-14 (Option B) the JVM computes every
hash itself — one code path, no Irmin coupling, unit-testable without a
running Irmin.

> **DD-16 (closed 2026-07-16) changes the leaf input:** the sketch below
> predates DD-16 and hashes raw `nodeJson` bytes. As closed, the leaf branch
> hashes the canonical projection instead —
> `ContentHash(sha256(LeafSimContent.from(leaf).toJson))` — and `build` takes
> decoded leaves, not raw bytes. The memoised bottom-up structure is
> unchanged.

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

> **Hash notation (Option B — DD-14; preimage per DD-16):** leaf hashes are
> `sha256` over the leaf's simulation-relevant projection (`LeafSimContent`:
> seedVarId + params — name and ULID excluded), computed at tree-load time.
> Labels like `"abc111"` and `"merk-O"` are illustrative stand-ins for 64-hex
> SHA-256 digests. The examples' hit/miss behaviour is unchanged by DD-16,
> with one addition: a pure rename would now be a full cache HIT (it changes
> no projection). **DD-15 closed 2026-07-16 → Option B:** the `.get("merk-…")`
> and portfolio `put` lines in the boxes below are Option-A history —
> portfolio results are not cached. Read them as: every full-tree read
> performs its 2 portfolio aggregations (linear sparse-map merge); the leaf
> hit/miss lines are unchanged. Deltas vs the Summary table: Step 3's
> `merk-O2` HIT becomes a re-aggregation; Step 5 becomes 0 sims + 2 aggs.

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
all cached entries become stale: same content hash, but results
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
| DD-3 | Cache strategy | Content-addressed: `Map[ContentHash, <value>]` | Content-identical nodes share one cache entry regardless of branch. Value type as originally written was `RiskResult`; refined by DD-16/DD-18 to the identity-free value. |
| DD-4 | Repository branch threading | Optional `branch` param on trait methods | Comparison workflow needs explicit branch args (read both in one effect). |
| DD-10 | Error types | Flat hierarchy extending `AppError` | `BranchNotFound`, `MergeConflictError`, `CommitNotFound`, etc. Follows existing pattern. **Naming not settled** — `MergeConflict` already exists in the hierarchy; see [A7](#a7-implementation-aid-corrections-against-the-current-codebase). |
| DD-12 | Test backward compat | Default args are source- and binary-compatible | ~60 new tests estimated across new capabilities. |
| DD-14 | Leaf hash source | **Option B — full JVM `sha256(jsonBytes)`** (closed 2026-07-14) | One hash system (SHA-256, uniform 64-hex → tight Iron refinement); no `getContents` in Phase A; no SHA-1; works on the in-memory backend, which Option A cannot. See [Leaf hash source](#leaf-hash-source-dd-14--closed-option-b). *Which* bytes get hashed was refined by DD-16. |
| DD-15 | Portfolio result caching scope | **Option B — cache leaf results only** (closed 2026-07-16) | Portfolio results are not cached; portfolios re-aggregate from child results on every read (linear sparse-map merge; A4: milliseconds at n=100, unmeasured). Smallest Phase A, smaller memory, no portfolio-key surface, decoupled from the resolver refactor. Reduces caching below current `TreeCacheManager` behaviour — the trigger #5 tradeoff is accepted by this decision. Alternatives A/C′ (portfolio entries under child-key hashing; a portfolio projection prepended if simulation-relevant portfolio fields ever appear) remain correct under DD-18 and are **parked as a post-landing follow-up in this doc and the monoid plan** — re-examine after both plans land, against measured behaviour. See the [A4 re-examination](#a4-review-2026-07-14--dd-15-closed-2026-07-16-option-b). |
| DD-16 | Leaf hash preimage | **Simulation-relevant projection, not raw stored bytes** (closed 2026-07-16) | The key hashes exactly what determines the figures: `seedVarId` + probability + distribution params, via a dedicated spec type with a byte-stability snapshot test. `name` and ULID are excluded — renames preserve the cache and cross-node hits become possible. **Corollary: cached values are identity-free** — the cache stores content only (trial map + stream provenance), never a node ID; the resolver attaches the *requested* node's ID when building the response. (`RiskResult` as it exists bundles `nodeId` with the outcomes and cannot be the cache value type unchanged; the replacement value type was fixed by DD-18 on 2026-07-16.) Supersedes DD-14's hash-the-returned-bytes rule; opened and closed 2026-07-16 after TODO item 12 removed the ULID→seed derivation. |
| DD-17 | Cache scope vs `seedEntityId` | **One `ContentCache` per workspace** (closed 2026-07-16) | `seedEntityId` determines figures but lives in no node's bytes. Per-workspace cache instances make cross-workspace contamination structurally impossible; a global map keyed by (entity, hash) buys nothing — different entity ⇒ different figures ⇒ cross-workspace hits are impossible by design — while mixing tenants in one structure and complicating workspace reaping. Cache lifecycle = workspace lifecycle. |
| DD-18 | `ContentCache` value type | **Named case class: `TrialOutcomes` + content-only provenance record** (closed 2026-07-16) | The cached value is a product of the monoid carrier (`TrialOutcomes` = nTrials + sparse `Map[TrialId, Loss]`; `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` A.1 — **Option 1 thereby decided**) and a provenance record containing no `riskId`. No node identity anywhere in the value — the DD-16 corollary made concrete. A named case class, not a tuple (nominal-type rule, ADR-018); provenance sits beside `TrialOutcomes`, not inside it, because provenance does not participate in combination (portfolio provenance is read from children, never merged). Class name chosen at implementation time. Provenance record shape: DD-19 (open). |

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
| DD-19 | Provenance content/identity representation | `NodeProvenance` mixes computation content (`entityId`, `occurrenceVarId`/`lossVarId`, global seeds, distribution type/params, timestamp, version) with identity (`riskId: NodeId`). The cached record must be the content part only (DD-16/DD-18). How is identity attached? **(a)** nested split — `NodeProvenance(riskId, <content record>)`; works before the monoid refactor; user prefers (a) over (b) (keep `NodeProvenance` unchanged, build it at the edge). **(c)+(d)** — drop `riskId` from the record entirely; attribution recovered from structure (`RiskResultGroup` keeps children) and, when ever exposed, a `Map[NodeId, <content record>]` assembled at the resolver edge — **candidate, not finalized**; requires the monoid plan's Part A first. Facts (verified 2026-07-16): `riskId` is written once (`Simulator.scala:209`) and read by no production code; no endpoint response carries provenance today (the `LECCurveResponse` type once named in an LEC.scala comment never existed — comment fixed). **Decide last — after every other open decision in this doc and the monoid plan is locked.** |

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
// ALREADY EXISTS — OpaqueTypes.scala:327 (drifted from :235 when the item-12
// seed types landed above it). Do not redefine; it is Iron-refined
// today and used as an IrminConfig value (no other consumer yet).
case class BranchRef(toBranchRef: BranchRefStr)              // Irmin branch name

// NEW. DD-14 → Option B ⇒ SHA-256 only ⇒ uniform 64 hex chars, so the
// refinement pins the length exactly. (Under the rejected Option A this type
// would have had to straddle 40-char SHA-1 and 64-char SHA-256 — the Pass 0a
// violation that helped close DD-14.)
type ContentHashConstraint = Match["^[a-f0-9]{64}$"]
type ContentHashStr        = String :| ContentHashConstraint
case class ContentHash(toContentHash: ContentHashStr)        // SHA-256 hex digest

// NEW (DD-16, closed 2026-07-16). The leaf hash preimage: exactly the fields
// that determine the figures — nothing else. name and ULID deliberately
// absent. Field order is a storage contract: byte-stability snapshot test
// REQUIRED on its codec (see Serialization determinism).
final case class LeafSimContent(
  seedVarId: SeedVarId.SeedVarId,
  probability: OccurrenceProbability,
  distributionType: DistributionType,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  terms: Option[PositiveInt]
)  // leaf cache key = ContentHash(sha256(leafSimContent.toJson))

// NEW (DD-18, closed 2026-07-16). The ContentCache value: identity-free
// result content. A named case class, NOT a tuple; final name chosen at
// implementation time. TrialOutcomes is the monoid plan's A.1 Option 1 type
// (nTrials + sparse Map[TrialId, Loss] — the lawful monoid). The provenance
// record is the content part of NodeProvenance — its shape is DD-19 (open);
// it must contain no riskId. No codec needed: cache values are never
// serialized (in-memory Ref, ADR-015).
final case class <CacheValue>(          // placeholder name — DD-18
  outcomes: TrialOutcomes,
  provenance: <content-only provenance record>   // DD-19: no riskId
)

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
| `ContentCache` (new) | Scala | `Ref[Map[ContentHash, <DD-18 value>]]` with `EvictionStrategy`. Replaces `RiskResultCache`. Value type decided (DD-18): a named case class of `TrialOutcomes` + a content-only provenance record — not `RiskResult`, which bundles `nodeId` (and provenance `riskId`) with the outcomes. Identity is attached by the resolver at the edge; provenance record shape is DD-19 (open). |
| `ContentHashIndex` (new) | Scala | At tree load: leaf hashes = `sha256(json bytes returned by get)`, portfolio Merkle hashes computed bottom-up (DD-14 → Option B). Pure function, unit-testable without Irmin. Returns `Map[NodeId, ContentHash]`. |
| `CacheScope` (new) | Scala | Abstraction over cache resolution — **one instance per workspace (DD-17), isolating `seedEntityId`**. `RiskResultResolver` calls `CacheScope` instead of `TreeCacheManager`. Cache values are identity-free (DD-16); the resolver attaches the requested node's ID when building the response. |
| `TreeCacheManager` | Scala | **Retired — deleted, not rewritten** (with `RiskResultCache`). Replaced by `CacheScope` + `ContentCache`. Consumers: `RiskResultResolverLive` rewires to `CacheScope`; `InvalidationHandler` keeps only its SSE half; `CacheController` — see its row. What the old design has that the new one drops: (1) portfolio result caching — decided away, DD-15 → B, post-landing follow-up; (2) explicit O(depth) ancestor-path invalidation with immediate memory reclamation — unnecessary under content addressing (a changed leaf *is* a different key; stale entries become orphans for the `EvictionStrategy`), and it is the mechanism behind the TODO item 17 bug class; (3) per-tree cache deletion on tree delete — lifecycle moves to workspace level (DD-17); a deleted tree's entries linger as orphans until eviction. |
| `CacheController` / `CacheEndpoints` | Scala | **Gap found 2026-07-16 — not previously in this table.** Four admin endpoints (`cacheStats`, `cacheNodes`, `cacheClear`, `cacheClearAll`) are built on `TreeCacheManager`'s `(TreeId, NodeId)` view. Under `ContentCache`, "which nodes of tree X are cached" is answerable only by joining the current `ContentHashIndex` with the cache keys — the cache alone stores identity-free values under content hashes and does not know node IDs. The endpoints must be redesigned (stats/clear port naturally to workspace scope; node listing needs the index) or retired. Changes API shape → **decision trigger #1; settle before Phase A**, alongside the A7 error-type collision. |
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

> Impact: None. Under DD-18 the cache stores identity-free values, so no
> declared subtype enters the cache at all. See
> [RiskResult Type Hierarchy](#riskresult-type-hierarchy) for details.

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

> Impact: cached values always carry full provenance content (per DD-18/DD-19
> the content part only — no `riskId`). Entries stay single-leaf sized —
> portfolio results are not cached (DD-15 → B). Not a concern for v1 but
> relevant for eviction sizing estimates.

**5. Entity seed derived from node ID hashCode.** `entitySeed =
leaf.id.value.hashCode.toLong`. The node's ULID determines its PRNG seed.
Same leaf ID + same SimulationConfig = same simulation output, regardless of
branch. Content-addressed caching captures this correctly — the JSON includes
`id`, so the hash changes if the ID changes.

> Impact: None. Seed determinism aligns with content-addressed caching.
>
> **Superseded 2026-07-16 — item 12 closed; the hashCode derivation is
> gone.** Streams now come from
> `SeedDerivation.streams(workspace.seedEntityId, leaf.seedVarId, seed3,
> seed4)`; the ULID influences nothing. The per-leaf input is still captured
> by a bytes-based hash — `seedVarId` is a field of the stored leaf JSON
> (`RiskLeafRaw`). **But one input that determines the figures now lives
> outside every node's bytes:** `seedEntityId` is workspace-level, so a content hash alone no
> longer determines the result across workspaces. See the A1 table (new gap
> row) and the cache-scope requirement in the A4 update.

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
>
> **Conditional on item 12's fix direction (2026-07-14).** This finding's
> premise — "`name` does not affect simulation results" — is true *today*
> only because seeds derive from `leaf.id`. Item 12's candidate 1 (name hash)
> would make `name` a **simulation input**, and this finding inverts: a rename
> would no longer be "same answer recomputed" but a **different answer**. A
> typo fix would move P99. The "impact: accepted" verdict below depends on the
> answer being unchanged and does not survive that fix direction. Re-evaluate
> this finding when item 12 is decided.
>
> **Resolved 2026-07-15 — premise resurrected.** Item 12's final decision
> (boundary-assigned seed IDs stored on the node — see
> `docs/dev/PLAN-SEED-IDENTITY.md`) rejected the name-hash direction. The
> name influences **no** figure: result = f(seedVarId, params, children)
> under the workspace's seedEntityId. This finding's premise is true again,
> and stronger: with name and ULID both excluded from the content hash, a
> rename preserves the cache too — the "unnecessary work on rename" impact
> below disappears entirely.

> Impact: **dissolved 2026-07-16.** Item 12 guarantees a rename changes no
> figure (the "different answer" inversion above is dead), and DD-16 (closed
> same day — projection preimage excludes `name`/`id`) means a rename no
> longer touches the cache either. No re-simulation, no invalidation.

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

**Re-examine portfolio result caching (DD-15 alternatives A/C′).** DD-15
closed 2026-07-16 → Option B (leaf results only). After both this design's
Phase A and `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` Part A have landed,
re-examine whether caching portfolio results improves anything measurable —
the goal is to judge the alternative against the landed system, not the
designed one. Inputs available then: measured re-aggregation cost on real
trees, `RiskResultGroup` in the resolver, the DD-18 value type in
`ContentCache`. Analysis to start from: the
[A4 re-examination](#a4-review-2026-07-14--dd-15-closed-2026-07-16-option-b).
Key variants to examine then (user, 2026-07-16): the recursive
sorted-child-key hash (Option A as designed) and the coarser **flattened
sorted-leaf-key list** of the whole subtree. The flat variant merges entries
across different internal tree shapes over the same leaf multiset — still
sound for identity-free values, because the merge is associative and
commutative (any bracketing of the same leaf multiset yields the same
figures and the same provenance content). Both variants stop being sound the
day portfolios gain simulation-relevant fields of their own (then C′ is the
required shape).

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
  Audit record: distributionType, entityId / occurrenceVarId / lossVarId
  (the derived HDR streams — since item 12, the same HdrStreams value the
  sampler consumed), etc.
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

**Superseded 2026-07-14 — the enum is dead code, already slated for deletion.**
This section overstates the problem, and DD-15 must not be argued from it.
[PLAN-MONOID-RISKRESULT-AND-MITIGATION.md](../dev/PLAN-MONOID-RISKRESULT-AND-MITIGATION.md)
recorded the assessment on 2026-06-18 under "Confirmed decisions":

> **`LossDistributionType` enum can be deleted.** … No code outside that file
> reads `.distributionType`. The class hierarchy (`RiskResult`/`RiskResultGroup`)
> is the correct discriminator — pattern match on the subtype.

Verified against the codebase 2026-07-14: the enum has exactly **four
references and zero reads** — the declaration, the abstract member, and the two
subclass constructor args, all inside `LossDistribution.scala`. Nothing reads
the field, so it misleads no caller. (Beware the name collision when
grepping: `RiskLeaf.distributionType` is an unrelated `String` field holding
`"lognormal"`/`"expert"`.) Its pickup checklist files it under "Resolved — no
action needed: confirmed dead code, safe to delete." It has **not** been
deleted — deletion was gated behind the plan's Option 1 vs Option 2 decision,
taken 2026-07-16 (Option 1); it proceeds with that plan's A.6 gated sequence.
Cache entries shown as `RiskResult(...)` in this section also predate
DD-16/DD-18 — cached values are identity-free.

Consequence for this design: **this is not a cache concern at all.** It is
neither an argument for nor against caching portfolio results, and the "bonus"
[A4](#a4-recommended-phase-a-lean-down-cache-leaf-results-only) claims from
dissolving it is void.

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
| Leaf `seedVarId` (item 12, closed 2026-07-16) | Yes — a field of the stored leaf JSON (`RiskLeafRaw`) | The per-leaf stochastic input that replaced the ULID-derived seed. Covered automatically by any bytes-based hash. |
| Leaf `id` (ULID) and `name` | **No — excluded by DD-16 (closed 2026-07-16)** | Not result inputs since item 12. Exclusion is deliberate: renames no longer invalidate, and identical-content nodes share entries — safe because cached values are identity-free (no node ID inside the cache; the resolver attaches the requested node's ID at the edge). |
| Workspace `seedEntityId` (item 12) | No — in no node's bytes | **Covered by DD-17 (closed 2026-07-16): one `ContentCache` per workspace.** Identical leaves in different workspaces produce *different* figures; per-workspace instances make cross-contamination structurally impossible. (A global cross-workspace cache would be a wrong-result bug.) |
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

**Added 2026-07-14 — dedupe and spurious correlation are the same event.**
This section reads as if content-based seeds are pure upside for the cache.
They are not, and the cost lands in the *domain*, not here. Content-derived
seeds mean two leaves with the same seed inputs produce byte-identical trial
streams. Aggregating them yields exactly 2× one risk rather than the
convolution of two independents — **tail risk overstated, silently**. A cache
hit across two distinct nodes and a pair of perfectly correlated risks are
the *same event seen from two sides*: dedupe firing is the symptom that the
model has made two risks identical. The cache stays correct; the model may
not. ULIDs cannot produce this — uniqueness is structural. Any content-based
seed can, unless uniqueness is enforced as a domain invariant on whatever
field the seed derives from. Item 12's candidate 2 (parameter hash) is the
worst case (every identical-spec leaf correlates); candidate 1 (name) is
safer only because names *tend* to differ, which is not a guarantee.
See TODO item 12 for the seed-path analysis.

**Superseded 2026-07-15 — item 12 decided against derived seeds entirely
(implemented and closed 2026-07-16).**
Seeds are now boundary-assigned IDs stored on the node
(`docs/dev/PLAN-SEED-IDENTITY.md`): per-tree uniqueness is *enforced at the
boundary*, so within-tree accidental correlation is unrepresentable. The
warning above survives only in its deliberate forms, which are features:
scenario branches share seed IDs (common random numbers across scenarios),
and caller-provided IDs may intentionally resurrect a deleted stream. Dedupe
firing across such nodes is correct, expected behaviour — same event seen
from two sides, now always by intent, never by accident.

### A3. Branching chosen over copying — position after item 12 (rewritten 2026-07-16)

**The decision stands: scenarios are Irmin branches, not tree copies.** What
item 12 changed is the *justification*, not the choice. Comparability is
guaranteed by the **seed design**, not by branching. Two
trees with the same leaf parameters and the same seed identities produce
byte-identical figures (`docs/dev/PLAN-SEED-IDENTITY.md`; proven by the
`SeedStabilitySpec` recreate test and the `SeedReproducibilityItSpec`
export→import round trip). A scenario-vs-main diff therefore shows only the
user's edits — whether the scenario is a branch or a copy.

The remaining difference is operational, not correctness-in-principle:

- **A branch carries seed identity automatically.** Same nodes, same stored
  `seedVarId`s, same workspace `seedEntityId`. Nothing to get wrong.
- **A copy must carry it deliberately.** Re-entering names and params matches
  the source only when the source has no deletion history; a tree with freed
  IDs (`seedVarHighWater` above the current max) diverges under fresh
  auto-assignment. A faithful copy must supply the source's `seedVarId`s
  explicitly (the API accepts them) and pin the same `seedEntityId`.

**The case for branches over copies:** no data duplication, merge (Phase D),
history/time travel (Phase E), and zero-care correctness — nothing to copy
correctly.

*(Retired 2026-07-16: this section originally argued copies were structurally
broken — cloned nodes got new ULIDs, hence new seeds, hence phantom diffs.
Item 12 removed the ULID→seed derivation, so that argument is dead; do not
re-raise it.)*

### A4. Recommended Phase A lean-down: cache leaf results only

> **Adopted 2026-07-16 — DD-15 closed → Option B.** The A/C′ alternative is
> parked as a post-landing follow-up (see
> [Follow-up improvements](#follow-up-improvements-post-launch)).

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

---

#### A4 review (2026-07-14) — DD-15, closed 2026-07-16 (Option B)

A4's proposal was reviewed against the code. **Three of the four objections
raised in review collapsed; one survived.** The net effect is that A4's own
central claim held, its Trap 1 did not, and a fourth option emerged that A4
did not consider. Recorded here because the collapses are as load-bearing as
the survivals — do not re-raise them without re-reading this.

**A4's "aggregation is cheap" is correct. A reviewer claim of quadratic cost
was wrong.** `reduce(RiskResult.combine)` over *k* children performs *k−1*
pairwise merges, each rebuilding the trial-ID union map. With *U* = union
size that is ~`2U(k−1)` lookups plus *k−1* throwaway maps, against `U·k` for
the variadic `LossDistribution.merge(d*)`. **Both are O(U·k)** — the pairwise
form costs a ~2× constant and allocation churn, not a complexity class. Full
re-aggregation of an *n*-node tree ≈ `U·(n−1)`: sub-ms on the 5-node
reference tree (A4 is simply right), order tens of ms at n=100, U=10K —
linear, predictable, and **still unmeasured**. Note the plan's switch to
`RiskResultGroup(id, children*)` picks up the variadic merge incidentally;
[PLAN-MONOID-RISKRESULT-AND-MITIGATION.md](../dev/PLAN-MONOID-RISKRESULT-AND-MITIGATION.md)
never cites performance as a reason and must not be quoted as if it does.

**Trap 1 is much weaker than A4 states — largely withdrawn.** `RiskTransform`
is typed `RiskResult => RiskResult`, and `RiskResultGroup` is a *sibling* of
`RiskResult` under `LossDistribution`, not a subtype. The monoid plan's gap 5
states it outright: *"Portfolio results (`RiskResultGroup`) cannot be directly
transformed by `RiskTransform`."* **A portfolio structurally cannot carry a
transform under B3**, the chosen mitigation stage. Portfolio-level mitigation
is B4, scored *"Poor cache fit, must sit outside the monoid, High API risk"*
and unchosen. A4's "mitigation transforms are heading exactly there" does not
hold on the documented roadmap.

Two further facts constrain any future attempt to hash a transform into a key:
`RiskTransform` wraps an opaque `RiskResult => RiskResult` **function, not
data** — it cannot be hashed without first reifying it into a spec type (new
shared-module type → trigger #4). It works only because a transform's
*parameters* would live in the node's stored JSON and the function is built
*from* that JSON — so hashing the node's own record covers it. Leaf keys
already do; portfolio keys do not. That asymmetry, not mitigation, is the
durable point.

**Trap 2 is speculative.** `LossDistribution.merge` is an outer join plus sum
— genuinely commutative. `sort` only misleads under a weighted/ordered
aggregation that nothing plans.

**The surviving objection: the portfolio key is identity-free.** `merk-O =
sha256(sort(childKeys))` contains nothing about O — not its `id`, not its
`name`. It is safe **today only by accident**: leaf JSON includes `id`, node
ids are ULIDs, so identity leaks upward transitively and no two portfolios
share a child-key set. If TODO item 12 is fixed and `id` is dropped from the
hashed bytes ([A2](#a2-dedupe-claim-precision--coupling-to-todo-item-12)),
that protection vanishes:

```
tree-1:  O1 {name:"ops-risk"}    children: cyber(p=0.3), hw(p=0.1)
tree-2:  O2 {name:"operations"}  children: cyber(p=0.3), hw(p=0.1)

leaf keys identical (id gone, params equal):  abc111, abc222
merk-O1 = sha256(sort([abc111,abc222])) == merk-O2      ← collide
```

The cached entry carries `nodeId = O1`; reading O2 returns it. The name
difference is **invisible to the key**, because the key never looks at O's own
record. The resolver only stamps the correct id on the *miss* path, and this
survives the monoid refactor unchanged (`RiskResultGroup` takes `nodeId` at
construction).

**The `distributionType` bonus A4 claims is void.** The enum is confirmed dead
code with deletion already sanctioned — see the ["always Leaf"
section](#the-always-leaf-mismatch). Nothing reads it, so it misleads nobody.
It is not a reason to prefer any option here.

##### Option set

| Option | Portfolio key | Verdict |
|---|---|---|
| **A** — design as written | `sha256(sort(childKeys))` | **Dominated by C.** C closes the identity gap for one extra hash input. No scenario favours A. |
| **B** — A4 lean-down | *(portfolios not cached)* | Live. Dissolves the identity gap by not having portfolio entries; shrinks the cross-tenant shared-key surface to full-content hashes only; decouples Phase A from the resolver refactor. Pays the (modest, linear, unmeasured) re-aggregation cost on every read. Reduces caching below current `TreeCacheManager` behaviour → **trigger #5**. |
| **C** — content-complete key | `sha256(ownJsonHash ++ childKeys in childIds order)` | Live. `ownJsonHash` is **already computed at layer 1 and discarded** (the `def444`/`ghi555` entries in [Hash structure](#hash-structure-after-first-load-on-main)) — so this is one extra input to a hash already being taken, with no canonicalisation and no re-serialisation (DD-14's binding rule holds). Covers own attributes, `childIds` order, `id` and `name`. **Preserves every number in the worked-examples table**: a branch where O's own record is unchanged still matches. |
| **D** — B now, C later behind `CacheScope` | *(staged)* | Live but weakened: C's cost is low enough that staging buys little. |

**Where the reviewer landed: C, weakly.** Its justification is now narrow —
it rests on the identity gap plus near-zero cost, **not** on the mitigation
story it was originally pitched on. "Include your own content in your own key"
is correct regardless of how the monoid plan's B.7 decision 3 or item 12 land,
so it is the conservative choice rather than a bet on the future. B remains
fully coherent and is the smaller Phase A.

**Decided 2026-07-16 → Option B**, after the re-scoring recorded below; the
A/C′ alternative is a post-landing follow-up.

**Update 2026-07-15 — item 12's final decision constrains the key
composition, whichever of B/C wins.** Seed identity is now boundary-assigned
and stored on the node (`docs/dev/PLAN-SEED-IDENTITY.md`): `seedVarId` is
part of the leaf's JSON, `seedEntityId` is workspace-level. Consequences for
this decision:

- The layer-1 "own JSON hash" now **contains the seed identity** — a node's
  content hash fully determines its simulation result again (the identity
  gap this review flagged is closed *from the domain side*). The hash must
  **exclude** name and ULID (both are reference identity; neither affects
  results — renames invalidate nothing).
- `seedEntityId` enters as **cache scope** (the existing `CacheScope`
  concept), not per-node content. Cross-workspace dedupe is impossible by
  design (different entity → genuinely different figures); within-workspace
  cross-tree hits (scenario branches, provided IDs) are correct dedupe.
- B vs C itself remains open, but C's "include your own content in your own
  key" now has the domain guarantee it previously lacked.

**Confirmed in code 2026-07-16 — item 12 is implemented and closed.**
`seedVarId` is a field of `RiskLeafRaw`, i.e. inside the stored leaf JSON.
`seedVarHighWater` lives in tree **meta**, not in any node's bytes — no key
impact. The first bullet's "must exclude name and ULID" became **DD-16,
closed the same day**: the leaf preimage is the simulation-relevant
projection.

**Update 2026-07-16 — DD-16/DD-17 closed; consequences for B vs C.** With
ULIDs out of the leaf preimage, the "surviving objection" above is no longer
hypothetical: identity-free portfolio keys (Option A) can genuinely collide
within a workspace — read at the time as fatal (**withdrawn same day by the
DD-18 re-examination below**: with identity-free values such a collision
returns correct figures). The DD-16
identity-free-value rule applies to portfolio entries too, under C. What does
*not* enter this decision: seeds. Portfolios have no `seedVarId` and need
none — a portfolio's figures are fully determined by its children's results,
and the child keys carry the seeds (and, via DD-17's per-workspace cache, the
`seedEntityId`) transitively. **B vs C remained the live choice until the
DD-18 re-examination below (same day).**

##### Re-examination after DD-18 (2026-07-16 sweep)

DD-18 fixes cached values as identity-free: `TrialOutcomes` plus a
content-only provenance record — no `nodeId`, no `riskId`. That removes the
premise of this review's surviving objection, which was formulated while
values carried `nodeId`:

- **The wrong-ID defect in the O1/O2 example is gone.** The cached entry
  carries no node ID; a read for O2 returns content and the resolver attaches
  O2's ID. Equal Option-A keys now imply equal figures: same child keys ⇒
  same child results (per-workspace scope, DD-17) ⇒ same commutative sum
  (confirmed assumptions 2 and 4). Provenance content is equal too — equal
  leaf keys ⇒ equal `seedVarId`s ⇒ equal streams. An Option-A key collision
  is therefore correct dedupe, exactly like leaf-level sharing under DD-16.
- **Option A is resurrected.** The previous sweep's "definitively dead"
  verdict is withdrawn — it was correct only while cached values carried
  identity.
- **Option C's `ownJsonHash` now over-covers.** A portfolio's own record
  (`id`, `name`, `childIds`) influences no figure, so hashing it into the key
  contradicts the DD-16 projection principle and buys spurious misses (a
  portfolio rename would invalidate). The consistent form is **C′**: prepend
  the portfolio's *simulation-relevant projection* to the child keys. Today
  that projection has no fields, so **C′ degenerates to A**. If portfolios
  ever gain simulation-relevant attributes (reified transform specs — trap 1),
  C′ is the shape the key must take; A is C′ with an empty projection.
- **The live choice is therefore B (do not cache portfolio results) vs A/C′
  (cache portfolios under the projection principle).** Trap 2 (ordered
  aggregation) remains speculative and unplanned.
- **Closed 2026-07-16 → Option B** (user decision). A/C′ is parked as a
  post-landing follow-up in this doc and the monoid plan — re-examine once
  both plans have landed and the system's measured behaviour is known.

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
   sentence above predated the fix by hours. The residual is TODO item 19
   (still open): that fix was verified **statically only** (`docker compose
   --env-file .env.irmin.example config` resolves the backends), with no live
   container-restart test yet. (Since 2026-07-16, `SeedReproducibilityItSpec`
   does prove Irmin-backed reload through a completely fresh in-process
   stack — repo/resolver-level evidence, but no container has been restarted
   under test.) Scenario branching should not be the first
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
