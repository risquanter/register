# PLAN — Boundary-Assigned Seed Identity

**Status:** **LOCKED 2026-07-15 — approved in full, no open decisions.**
Even/odd stream split re-confirmed by the user on corrected grounds (the
offset-collision argument applies only to offsets on the varId axis; the
seed3-constants alternative is mathematically sound and was declined on
plumbing cost — §13). Remaining §12 items are Signature-Echo-time details,
not decisions. **Implementation not started — awaits an explicit go signal.**
**Supersedes:** PLAN-NAME-ONLY-SEEDING.md (deleted 2026-07-15 — its hash-based design died twice, see §2).
**Decision record:** [TODO item 12](./TODO.md).
**API impact:** YES — additive optional request fields + seed IDs in responses. Trigger #1 acknowledged by the user 2026-07-15.

This document is the survival record for the item-12 redesign: it embeds the HDR
paper findings, the verified arithmetic, the design-space analysis, and every
locked decision, so that no context from the 2026-07-14/15 sessions is needed to
pick it up.

---

## 1. The decision (final form)

Every risk leaf carries a **`seedVarId`** — a small integer **assigned by the
server at the creation boundary** (or optionally provided by the caller),
**stored on the node**, immutable once assigned. Every workspace carries a
**`seedEntityId`** — assigned from a global counter (or optionally provided).
HDR streams derive from these in exactly one place:

```
entityId        = workspace.seedEntityId
occurrenceVarId = 2 * leaf.seedVarId
lossVarId       = 2 * leaf.seedVarId + 1
seed3, seed4    = global reproducibility knobs (unchanged; = the paper's Time/Agent axes)
```

**No hashing anywhere.** Stochastic identity is *assigned data*, exactly like
the paper's "chart of accounts" model (§3). App identity (ULID) and stochastic
identity (seed IDs) are deliberately separate concepts with separate lifecycles
(ADR-018 in spirit):

| | ULID (`SafeId`) | `seedVarId` / `seedEntityId` |
|---|---|---|
| Purpose | reference: authz `ResourceRef`, HTTP paths, `parentId`/`childIds` wiring | which HDR random stream |
| Recreate tree | new ULIDs | same seeds (sorted assignment / provided) |
| Rename node | unchanged | unchanged → **figures and cache survive renames** |
| In content hash (DD-15) | **excluded** | **included** |

Portfolios do **not** carry a `seedVarId` — retracted by the user 2026-07-15:
no stochastic behaviour exists at portfolio level (results are per-trial sums of
children), so the field would be inert. YAGNI. If portfolio-level stochastic
features ever appear, this is the recorded extension point.

---

## 2. Decision history — why three predecessor designs died

Do not re-propose these; each was killed for a recorded reason.

| # | Scheme | Killed by |
|---|---|---|
| 0 (status quo) | `entitySeed = leaf.id.value.hashCode` (ULID) | ULIDs are time-ordered → same tree recreated → different figures (item 12's symptom). **Also 8× over HDR's magnitude budget today** (§3.3) — a live, independent defect. |
| 1 | Name-only, truncated **SHA-256 to 64 bits** (locked 2026-07-15 morning) | HDR IDs are 8 *decimal digits* by design. 64-bit values are ~3.5×10¹⁰ over the dividend budget and silently wrap `Long`. Would void the Dieharder validation and Excel reproducibility — HDR's reason for existing. **Unimplementable.** |
| 2 | Name hash truncated to 8 digits + collision rejection at boundary | Unrecoverable UX: the error would be "rename until it stops colliding" — a remediation indistinguishable from superstition. Not validation (the input isn't wrong; the arithmetic had an accident). Birthday risk in 10⁸ space: 0.02% @ 100 leaves, **2% @ 1,000**, 39% @ 5,000. User veto 2026-07-15. |
| 3 (final) | Boundary-assigned IDs, stored on node | — |

**What survives from the dead designs:** name uniqueness as an enforced
invariant (now serving *reference* semantics only — §5.4); common random
numbers as a first-order product property; the single-derivation-site principle;
the two-site bug analysis (§6).

### The trilemma that forced the final shape

Any ID scheme gets at most two of:

| Scheme | Figures depend only on tree content | Insert never disturbs existing streams | Collision-free |
|---|---|---|---|
| Name hash | ✅ | ✅ | ❌ |
| Rank of name in sorted order | ✅ | ❌ re-ranks all → full cache invalidation, CRN destroyed | ✅ |
| Incrementing counter | ❌ path-dependent | ✅ | ✅ |

**Escape: store the assigned ID on the leaf.** The ID becomes part of the
content, so "content-determined" holds trivially; path-dependence moves into
what the content *is*, and export/import with provided IDs reproduces any
edit-built tree exactly.

---

## 3. HDR paper findings (Hubbard, WSC 2019)

Source: "A Multi-Dimensional, Counter-Based Pseudo Random Number Generator as a
Standard for Monte Carlo Simulations", D.W. Hubbard, Proc. WSC 2019, pp.
3064–3072. https://www.informs-sim.org/wsc19papers/339.pdf
Implementation: `../hdr-rng/core/src/main/scala/com/risquanter/hdr/HDR.scala` —
verified faithful to the paper (constants match Table 2 exactly).

### 3.1 All five dimensions are structurally identical

Equation (3) form — every dimension enters **one linear sum inside one modulus**
per lane:

```
mod(Trial*2499997 + Var*1800451 + Ent*2000371 + Time*1796777 + Agent*2299603, 7450589)   // lane 1
mod(Trial*2246527 + Var*2399993 + Ent*2100869 + Time*1918303 + Agent*1624729, 7450987)   // lane 2
```

> "Other dimensions are added by multiplying each counter … times the following
> prime coefficients and adding them." … "For any dimension excluded, the
> counter is defaulted to zero."

**Consequence (the question that triggered the paper review):** the stream is
identified by the whole tuple. There is no privileged axis; holding `varId`
constant across entities, or `entityId` constant across vars, is equally sound.
**The code's `seed3`/`seed4` are the paper's Time/Agent axes** — coefficient
match verified — so both remain available as global knobs.

### 3.2 Axis semantics (Table 3) — our mapping

| Paper axis | Paper meaning (quotes) | Our mapping |
|---|---|---|
| Trial | "unique identifier for a given scenario" | trial counter (unchanged) |
| **Var** | "'Monthly Demand for Product X' … would each be given unique variable IDs … an internal library of assigned variable IDs similar to an **accountant's chart of accounts**" | **the risk leaf** — 2 vars per leaf (occurrence/loss) |
| **Ent** | "identifies an organization … The FDIC would supply the variable ID along with the Entity ID of the FDIC so that every bank using those variables produces the same sequence" … "A default Entity ID of 0 can be used by anyone as long as sharing variables would not be an issue." | **the workspace** (organizational boundary — user decision 2026-07-15). 0 reserved. |
| Time | "identifies a particular time unit … default 0" | `seed3` (global knob, unchanged) |
| Agent | "identity for agents in agent-based modeling … default 0" | `seed4` (global knob, unchanged) |

The paper's IDs are **assigned from a registry, never derived** — the final
design is the paper's own model. The FDIC pattern is the future shared-risk-
library story: a published (`seedEntityId`, `seedVarId`s) reproduces reference
figures in every importing workspace.

### 3.3 Hard magnitude constraints — verified arithmetic

> "They are all chosen to ensure that none produces a value beyond Excel's
> precision limit (10^15) and no dividend of a modulus function is more than
> 2^27 times the divisor."

Every ID is **8 decimal digits (< 10⁸)**. This is load-bearing, not convention.
Verified (2026-07-15, lane-1 dividend vs the ~1.0×10¹⁵ limit):

| Usage | Lane-1 dividend | Verdict |
|---|---|---|
| Paper design point (all IDs at max) | 6.7×10¹⁴ | ✅ |
| **Current code** (`String.hashCode`, ±2³¹) | **8.2×10¹⁵** | ❌ **8× over — live defect on `main`, independent of item 12** |
| 64-bit hash (dead design 1) | 3.5×10²⁵ | ❌ wraps `Long` silently |
| **This design** (ent < 10⁸, var < 10⁸, 10⁶ trials) | ~4.5×10¹⁴ | ✅ |

The Dieharder validation (114 tests; algorithm 703756 statistically
indistinguishable from Python's Mersenne Twister and AWS's hardware-entropy
source) was run **inside** the design domain. Outside it, no quality claim
holds. Fixing item 12 with in-range IDs also fixes the standing violation.

---

## 4. The domain types

```scala
// modules/common — cross-compiles (pure arithmetic, no MessageDigest, no JS concern)
// Nominal, Iron-refined, smart constructors, codec-validated at boundary (ADR-001/ADR-018)

SeedEntityId : opaque type over Long, range [1, 100_000_000)   // 0 reserved: paper's "anyone" default
SeedVarId    : opaque type over Long, range [1, 50_000_000)    // 2k+1 must stay < 10^8
```

The range refinement is **load-bearing**: a provided ID of 2⁴⁰ would silently
re-import the §3.3 budget violation through the front door. Iron rejects it at
the codec with a range message.

`RiskLeaf` gains `seedVarId: SeedVarId` (required in domain; optional in the
definition DTO — server assigns when absent). `RiskPortfolio` is **unchanged**.
Workspace gains `seedEntityId: SeedEntityId`.

---

## 5. Assignment, uniqueness, immutability (all user-approved 2026-07-15)

### 5.1 Assignment algorithm (per tree)

```
validate:  all seedVarIds in request (provided ∪ to-be-assigned) distinct within
           the tree — O(N) set check, request-local, no repository access
assign:    unprovided leaves in sorted-name order, consecutive from highWater+1
persist:   tree.seedVarHighWater = max(old highWater, max id now in tree)
```

- **High-water mark, not `max(current)+1`.** Scan-max would re-issue a deleted
  leaf's ID; in a branched world the new leaf then correlates perfectly with a
  *different* risk still alive in a sibling branch — spurious correlation
  injected into exactly the scenario-comparison workflow. Assigned IDs are
  never reused.
- **Provided IDs may deliberately reuse** a deleted ID — explicit intent; this
  is how a removed risk is resurrected with its stochastic history intact.
- **Provided-ID clash within the tree → 400**: *"seedVarId 5 is already used by
  'Cyber Attack' in this tree — choose another or omit it to auto-assign."*
  This rejection is legitimate validation (the input references an occupied
  slot) — contrast the dead hash design, where the rejected input wasn't wrong.
- High-water merges across Irmin branches as `max` — associative, commutative,
  idempotent (bounded semilattice) — sound under Git-style merge with no
  bespoke conflict handling (DD-10 relevance).
- Edge (theoretical): a provided ID near the range cap exhausts the assignment
  space above it. Accepted; the range refinement bounds the damage; document
  the "assignment space exhausted" error if it ever becomes reachable.

### 5.2 Uniqueness scopes

- **`seedVarId`: per tree.** NOT union-across-trees (user's initial instinct,
  reversed on this argument, approved 2026-07-15): **scenario branches must
  share seed IDs** — comparing scenario A vs B requires unedited leaves to
  produce identical draws so every LEC difference is attributable to the edit
  (common random numbers across scenarios, milestone 2b's core premise).
  Workspace-wide uniqueness would force branches to re-roll everything.
  Additionally: results never aggregate across trees (cross-tree sharing is
  unobservable or a *correct* cache dedupe), and a union check needs repository
  state, which cannot live at the codec boundary (ADR-001 layering).
- **`seedEntityId`: global per deployment.** Service-level check (cross-
  aggregate uniqueness, like unique usernames) + store sequence for assignment.
  Cross-deployment sameness via provision is the *feature* (staging/prod
  parity, shared libraries).

### 5.3 Immutability

`seedVarId` is **immutable once assigned**. Update requests never carry it for
existing nodes (only new leaves within an update may provide one). Changing a
stream requires explicit delete-and-recreate. Rationale: a mutable seed ID lets
an edit silently re-roll a stream — the exact class of surprise this redesign
exists to kill. (ADR-017 create/update DTO separation holds.)

### 5.4 What the name invariant means now

`requireUniqueNames` (`RiskTreeRequests.scala:129`, enforced on both write
paths: `validateTopologyCreate:198`, `validateTopologyUpdate:227`) remains —
but it now serves **reference semantics only** (V2 requests wire parents by
name; the request model is `Map[SafeName, ResolvedNode]`). The name has **no
influence on any figure**: result = f(seedVarId, params, children) under the
workspace's seedEntityId. Renames preserve figures *and* cache validity.

**Defense in depth:** `RiskTree`'s smart constructor should also validate
per-tree `seedVarId` distinctness, so programmatically-built trees can't
violate the invariant the boundary enforces (correct-by-construction layering).

### 5.5 Entity counter — determinism requirement

The `seedEntityId` counter must be **deterministic per fresh store** (fixed
base in in-memory stores), or the demo suites become order-flaky again through
workspace creation order — item 12's disease reimported one level up. For the
same reason the entity ID must **never** be ULID-derived. Explicit provision is
available to tests that need pinned values.

---

## 6. Bugs fixed as side effects (all live on `main` today)

1. **Magnitude violation** — §3.3. `String.hashCode` values (±2.1×10⁹) are 21×
   the 10⁸ ID budget; lane dividends 8× over the limit; HDR's statistical
   guarantees currently do not apply to this codebase.
2. **Provenance var-ID mismatch** — `Simulator.scala:209-210` records
   `entitySeed.hashCode + 1000/2000` while `RiskSampler.scala:93-95` uses
   `riskSeed.value.hashCode + 1000/2000`. `Long.hashCode` = `(int)(v^(v>>>32))`
   ≠ the value outside Int range → recorded var-IDs are wrong for ~half of all
   leaves → provenance-based replay (item 12's stated escape hatch) is broken.
   No test covers it; this plan adds one. Fixed *by construction*: sampler and
   `NodeProvenance` consume the same derived values from the single derivation
   site.
3. **Two-site seed duplication** — the ULID hash is independently derived at
   `Simulator.scala:194` and `RiskSampler.scala:93` with divergent syntax; the
   redundancy is what allowed the drift in bug 2. The `entitySeed`/`riskSeed`
   double-input of `RiskSampler.fromDistribution` (same information twice) is
   removed per old decision 1 = A (locked): no redundant seed parameters; the
   node ID remains only as an identity label. Exact signature at signature-echo
   time.

---

## 7. API changes (trigger #1 — acknowledged by user 2026-07-15)

Additive only. No existing field changes shape.

| Surface | Change |
|---|---|
| Leaf definition request (create; new-leaf in update) | optional `seedVarId` |
| Workspace creation request | optional `seedEntityId` |
| Tree/leaf responses + export | **include** `seedVarId` (required for export→import round-trip reproduction) |
| Workspace responses | include `seedEntityId` |
| Update request, existing nodes | `seedVarId` NOT accepted (§5.3) |

OpenAPI output changes accordingly. This supersedes the earlier session
constraint "no API implications" — explicitly re-decided by the user.

---

## 8. Reproducibility semantics (scope — user-approved narrowing)

| Scenario | Reproducible? |
|---|---|
| Re-simulate saved tree | ✅ (seeds are stored data) |
| Delete + recreate same spec, same workspace, no IDs provided | ✅ (sorted-name assignment is content-determined) |
| Same spec, two workspaces | ❌ **by design** (different `seedEntityId` — org isolation, the FDIC pattern) |
| Cross-workspace / cross-deployment reproduction | ✅ **via explicit provision** (export carries seed IDs) |
| Scenario branch vs parent, unedited nodes | ✅ (shared seed IDs — CRN across scenarios) |
| Edit one leaf's params | ✅ untouched leaves byte-identical; edited leaf keeps its stream (CRN across edits) |
| Test suites in any order | ✅ (per-store counters + optional provision) |

The original item-12 text treated cross-user difference as a defect; the
narrowing to within-workspace (plus provision for everything else) is a
deliberate user decision, 2026-07-15.

---

## 9. DD-15 / content-addressed cache interactions

- Node cache key: hash of node content **including `seedVarId` and params,
  excluding name and ULID**; children enter via child *keys* (content hashes).
- `seedEntityId` enters as **cache scope** (maps onto the existing `CacheScope`
  concept), not per-node content.
- Renames invalidate nothing. Cross-workspace dedupe is impossible by design
  (entity differs → results differ → keys differ: *correct*). Within-tree
  incremental invalidation (the platform premise) unaffected. Branch CRN
  preserved (§5.2).
- Milestone-2b doc updates recorded there: Finding 6's premise ("name does not
  affect simulation results") is **true again** under this design; the A2
  dedupe/correlation note is superseded for within-tree cases (collision now
  impossible) — deliberate sharing (branches, provided IDs) remains the same
  event seen from two sides.

---

## 10. Work breakdown (ordered; each step green before the next)

1. `SeedEntityId`, `SeedVarId` Iron types + smart constructors + codecs
   (`common`; pure arithmetic — cross-compiles trivially).
2. Boundary validation: per-tree distinctness in `RiskTreeRequests`
   (create + update paths, next to `requireUniqueNames`); range via Iron.
3. `RiskLeaf.seedVarId` field + smart-constructor/codec changes + `RiskTree`
   defense-in-depth check. **Trigger #4 — planned and user-approved here.**
   Test-constructor churn across specs is expected; `unsafeApply` parameter
   strategy decided at signature-echo time.
4. Assignment service logic in tree create/update path (`buildNodes` area):
   sorted-name assignment, high-water on tree, provided-ID handling.
   **Must sort names explicitly — never rely on Scala `Map` iteration order.**
5. Workspace `seedEntityId`: field, store sequence, global uniqueness check,
   deterministic test base (§5.5).
6. Single derivation site (even/odd) + `RiskSampler.fromDistribution` reshape
   (old decision 1 = A) + `Simulator.createSamplerFromLeaf` passes the same
   values to sampler and `NodeProvenance` (kills bug 2).
7. DTO/OpenAPI additions (§7).
8. Scaladoc: `Provenance.scala:23-25` (documents the old derivation);
   `README.md` "Counter-based PRNG" bullet.
9. Wipe stores; recreate demo data (migration resolved as moot — §12.1).
10. Demo re-baseline with margin assertions (§11).
11. Full regression + integration + BATS C and A.
12. Version bump to MAJOR (user-decided 2026-07-15; executed at completion via
    the register-dev bump procedure: `build.sbt` + `.env` sync).

---

## 11. Test plan — layered, property-first

Figure-level golden vectors are **discarded for this task**: with no data
surviving (§12.1) and a MAJOR bump announcing that all figures change, freezing
unaudited emergent numbers pins nothing worth pinning. Exact-value assertions
do not disappear — they move **down** to the derivation layer, where every
expected value is hand-verifiable, and are replaced at the figure level by
properties that hold for *any* correct implementation.

**Layer 0 — domain types (common).** Iron range boundaries for both types
(reject 0, accept 1, accept max−1, reject max: 5·10⁷ / 10⁸); codec round-trips;
error accumulation via `Validation.validateWith` (independent field errors all
surface).

**Layer 1 — derivation (exact, hand-verifiable).** `seedVarId = k` →
streams `(2k, 2k+1)` asserted as literal integers; disjointness property
∀ j ≠ k: `{2j, 2j+1} ∩ {2k, 2k+1} = ∅`; **the §3.3 magnitude budget as an
executable property** — at maximal legal inputs (entity < 10⁸, var < 10⁸,
trial 10⁸), each HDR lane dividend stays within both paper limits (10¹⁵ and
2²⁷·divisor), using the real Table 2 coefficients. This test *fails on the
old code* and permanently guards the budget.

**Layer 2 — assignment algorithm (property-based).** Fresh create assigns
1..N in sorted-name order, **invariant under input permutation** (kills any
latent `Map`-iteration-order dependence); mixed provided/assigned
deterministic; update appends from high-water; deleted IDs never auto-reused
(high-water survives deletion); provided may reuse deleted; provided clash →
accumulated `ValidationError` with the actionable message; high-water merge =
max, with zio-prelude law checks (associative, commutative, idempotent).

**Layer 3 — boundary/API.** 400 with accumulated errors on range and clash
violations; `seedVarId` on an existing node in an update request rejected
(immutability, §5.3); OpenAPI output includes the new optional fields;
responses/exports carry seed IDs (round-trip prerequisite).

**Layer 4 — reproducibility integration (`SeedStabilitySpec`).** Same logical
tree created twice (different ULIDs, same JVM) → byte-identical `RiskResult`
outcomes (**fails on `main` today** — the item-12 regression proof); edit one
leaf's `minLoss` → its own stream and all untouched leaves unchanged (CRN);
rename → figures unchanged (impossible under every dead design); same spec in
two workspaces → **different** figures (entity isolation is asserted, not
assumed); export → import with provided IDs and matching entity → identical
figures (§8 round-trip claim).

**Layer 5 — statistical sanity (deterministic, cannot flake).** With seeds
fixed, every statistic is a constant — these are regression tests, not
hypothesis tests. Pearson correlation between adjacent leaves' streams
(`seedVarId` k vs k+1) below a small bound over N trials; occurrence frequency
within a precomputed tolerance of the declared probability; a KS-statistic
uniformity smoke on one stream. Rationale: the Dieharder validation covered
the algorithm, but **dense consecutive IDs are a new usage pattern** for this
codebase (the old hash-spread IDs never exercised adjacency), and a linear-mix
generator earns an adjacency check.

**Updated specs:** `RiskSamplerSpec` (call sites; the `:287`
"different risk IDs → different sequences" test rewritten to vary `seedVarId`,
intent preserved); `SimulatorSpec`; `ProvenanceSpec:192` (encodes the old ULID
derivation); **new** ProvenanceSpec assertion: recorded var-IDs equal the
generator's actual var-IDs (bug 2 has no test today).

**Demo re-baseline (supersedes old decision 3 for this task — user feedback
2026-07-15, task-scoped).** Verdict assertions are re-recorded once, and each
threshold-straddling query (Q1 `AtLeast(1/4,0.1)`, Q7b `About(2/3,0.1)`, Q-D
`AtMost(1/3,0.1)` in `DemoEnterpriseScriptSpec`) gains an **explicit margin
assertion** — the proportion must sit a stated distance from its quantifier
boundary, so a knife-edge baseline fails loudly instead of being frozen. If a
recorded verdict lands knife-edge, that is raised at implementation time
(changing demo data/queries touches trigger #8), not silently absorbed.

**Order-independence:** `DemoSimpleScriptSpec` and `DemoEnterpriseScriptSpec`
in both orders → identical figures (the original symptom, directly).

**Full regression (register-dev skill commands, pass/fail only):**

```bash
sbt commonJVM/test ; sbt server/test ; sbt app/test ; sbt "serverIt/test"
```

**Integration (serverIt):** persist → restart container → re-simulate →
identical figures; the cross-workspace import round-trip above at system
level.

**BATS suites C and A** — C as the fast gate; A because the persisted node
JSON schema changes (server ↔ Irmin integration).

---

## 12. Open items (implementation-time; none block the direction)

1. ~~Migration mechanism~~ **RESOLVED 2026-07-15 (user): no persisted data
   needs to survive.** Migration = wipe stores and recreate demo data. The
   batch-vs-lazy question is moot.
2. ~~`seedEntityId` sequence storage~~ **RESOLVED 2026-07-15:** both store
   backends are deployment-live (config-selected), so the counter is a
   `WorkspaceStore` **contract responsibility**, implemented per backend —
   Postgres sequence in `WorkspaceStorePostgres`, fixed-base in-memory counter
   in `WorkspaceStoreLive` (which is exactly what §5.5 test determinism
   requires).
3. Exact DTO field names + `RiskSampler` signature — Signature Echo Protocol at
   implementation time; deviation = stop.
4. `unsafeApply` test-helper parameter strategy (step 3 churn).

## 13. Decision log

| Decision | Outcome | When |
|---|---|---|
| Fix direction (item 12) | Boundary-assigned seed IDs stored on node | 2026-07-15 (supersedes same-day name-hash lock) |
| App ID vs seed ID separation | Separate types (`SeedEntityId`, `SeedVarId` naming convention) | 2026-07-15 user |
| Workspace = entity axis | Yes (org boundary, FDIC pattern) | 2026-07-15 user |
| `seedVarId` uniqueness scope | **Per tree** (branching-CRN argument; user reversed own union-of-trees proposal) | 2026-07-15 user |
| `seedVarId` mutability | Immutable once assigned | 2026-07-15 user |
| Portfolio `seedVarId` | **No** — retracted, YAGNI | 2026-07-15 user |
| Optional caller provision + boundary uniqueness | Yes (supersedes the lock text's rejection of caller-supplied seeds — context changed: assignment is default, provision is opt-in) | 2026-07-15 user |
| Content addressing | seed IDs in; name and ULID out; entity as cache scope | 2026-07-15 user |
| API additions | Acknowledged (trigger #1) | 2026-07-15 user |
| Old decision 1 (sampler signature) | A — no redundant seed inputs | 2026-07-15 user (carries over) |
| Old decision 2 (var-ID scheme A/B/C) | **Superseded** — even/odd from `seedVarId` | 2026-07-15 |
| Old decision 3 (re-baseline) | Superseded **for this task** (user feedback 2026-07-15, task-scoped): margin-asserted re-baseline, no figure-level goldens — §11 | 2026-07-15 user |
| Stream split | even/odd (`2k`/`2k+1`). NOT `+1000/+2000` offsets **on the varId axis** (old-code shape): with dense IDs, leaf k's loss varId (`k+2000`) equals leaf `k+1000`'s occurrence varId — identical five-tuples, deterministic collision, user-triggerable via provided IDs. Constants 1000/2000 **on the seed3 axis** are mathematically sound (cross-kind collision needs `k−j ≈ −1.3×10¹³`, verified 2026-07-15, far outside the 10⁸ budget) — rejected only on plumbing grounds: burns one of two global knobs, and `NodeProvenance.globalSeed3` / `SimulationConfig.defaultSeed3` semantics would change (schema churn) | 2026-07-15 |
| Migration | Moot — no data survives; wipe and recreate demo data | 2026-07-15 user |
| Version bump | **MAJOR** | 2026-07-15 user |
| `seedEntityId` counter home | `WorkspaceStore` contract, per-backend impl | 2026-07-15 |
| **Plan locked** | Approved in full; even/odd re-confirmed after collision arithmetic was verified for both layouts ("we just needed to make sure it is not based on bullshit arguments") | 2026-07-15 user |

## 14. Version impact

**MAJOR — decided by the user 2026-07-15.** Every simulation figure changes
once; no persisted data survives (stores wiped, demo data recreated). Executed
at completion, step 12 of §10.

## 15. References

- [TODO item 12](./TODO.md) — decision record
- [DD-15 / milestone 2b](../scratch/milestone-2b-cache-and-decisions.md) — cache interactions (§9)
- [PLAN-MONOID-RISKRESULT-AND-MITIGATION.md](./PLAN-MONOID-RISKRESULT-AND-MITIGATION.md) — RiskTransform must-fix list; unaffected by this plan
- Hubbard WSC 2019 paper: https://www.informs-sim.org/wsc19papers/339.pdf (key content embedded in §3)
- HDR implementation: `../hdr-rng/core/src/main/scala/com/risquanter/hdr/HDR.scala`
