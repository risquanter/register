# PLAN — `TrialOutcomes` Monoid, Aggregation & Mitigation Design

Status: **Draft / not approved for implementation — code audit completed 2026-06-18**
Scope: Internal implementation only (no API change intended). API implications flagged where relevant.
Related: ADR-003 (provenance), ADR-014/ADR-015 (RiskResult as cache/runtime state, cache-aside),
`docs/scratch/milestone-2b-cache-and-decisions.md` (Leaf-as-aggregate semantic smell).

---

## 0. Why this document exists

An external clean-slate design exploration (see `interview-prep/Agent-category-theory-risk.md`)
proposed a category-theory-inspired risk engine. Most of it does **not** fit our domain
(it optimizes for a single cold full-tree sweep with no per-node reuse, which is the opposite
of our incremental, cached, provenance-carrying engine).

Exactly **one** idea is portable and genuinely useful: modeling **child aggregation as a lawful
monoid**. This document captures that insight at implementation-pickup level, and separately
explores **mitigation** design (an acknowledged open topic with no current good design).

---

## Code audit — 2026-06-18

Audit of `modules/common/src/main/scala/.../domain/data/` and
`modules/server/src/main/scala/.../services/cache/` against this plan.

### Confirmed decisions (2026-06-18 design review)

- **`RiskResult → LossDistribution` widening is confirmed safe.** Every caller of `ensureCached` /
  `ensureCachedAll` uses only `LossDistribution` members (`probOfExceedance`, `outcomeCount`,
  `nTrials`, `minLoss`, `maxLoss`). `LECGenerator`, `RiskTreeKnowledgeBase`, and the service
  layer all touch only these. The type substitution is mechanical with zero logic change. The
  one exception — `provenances` access in `ProvenanceSpec` — requires a pattern match after
  widening; for `RiskResultGroup` provenance lives in `children.flatMap(_.provenances)`.

- **`LossDistributionType` enum can be deleted.** The enum (`Leaf`/`Composite`) is referenced only
  in `LossDistribution.scala` itself as hardcoded constructor arguments. No code outside that
  file reads `.distributionType`. The class hierarchy (`RiskResult`/`RiskResultGroup`) is the
  correct discriminator — pattern match on the subtype.

- **`Associative[RiskResult]`, `Commutative[RiskResult]`, `RiskResult.combine`,
  `RiskResultIdentityInstances`, and `RiskResult.withNodeId` are semantically wrong and should
  be deleted.** See A.1 update below.

### What already exists

| Item | Location | Notes |
|---|---|---|
| `Associative[RiskResult]` + `Commutative[RiskResult]` | `LossDistribution.scala` | **To be deleted** — semantically wrong (see A.1) |
| `Identity[RiskResult]` (context-dependent) | `RiskResultIdentityInstances.scala` | **To be deleted** — test-only, supports the wrong monoid |
| `LossDistribution.merge` | `LossDistribution.scala` | The genuine combine for `Map[TrialId, Loss]`; correct and kept |
| `RiskResultGroup` (Composite type) | `LossDistribution.scala` | Correct composite subtype; holds `children: List[RiskResult]` (to be widened to `List[LossDistribution]` — gap 2) and combined `outcomes` — the correct fix for the Leaf-as-aggregate smell |
| `RiskTransform` (B3 result-stage endomorphism) | `RiskTransform.scala` | `case class RiskTransform(run: RiskResult => RiskResult)`; lawful `Identity[RiskTransform]`; operations: `applyDeductible`, `capLosses`, `scaleLosses`, `insurancePolicy`, `filterBelowThreshold`; property-tested |

### What is NOT wired into production

- **`RiskResultGroup`** is used in tests only (`LossDistributionSpec`, `PreludeOrdUsageSpec`).
  `RiskResultResolverLive` still does `childResults.reduce(RiskResult.combine).withNodeId(portfolio.id)`,
  producing a `LossDistributionType.Leaf` result for portfolio nodes — the smell is still live.
- **`RiskTransform`** is used in tests only (`RiskTransformSpec`). No production call path
  invokes it. The transforms do **not** append to `RiskResult.provenances`.

### Open gaps after audit

1. **Resolver `reduce(RiskResult.combine)` is wrong** — must be replaced with `RiskResultGroup(portfolio.id, childResults*)`. Not just a wiring change; the false monoid instances must be deleted too.
2. **`RiskResultGroup.children: List[RiskResult]`** — must widen to `List[LossDistribution]` to support nested portfolios. `apply` must accept `LossDistribution*`.
3. **Cache and resolver return type** — `RiskResult` → `LossDistribution` throughout (mechanical substitution, zero logic change).
4. **`LossDistributionType` enum + `distributionType` field** — dead code; can be deleted.
5. **`RiskTransform` + widened cache type** — `RiskTransform` operates on `RiskResult` (not
   `LossDistribution`). After widening the cache to `LossDistribution`, any future call site that
   reads from cache and applies a transform must pattern-match: `case rr: RiskResult =>`.
   Portfolio results (`RiskResultGroup`) cannot be directly transformed by `RiskTransform`.
   This is acceptable now (transforms are unwired in production), but must be resolved before
   `RiskTransform` is wired into any production path.
6. **`RiskTransform` not on any call path** — no service, controller, or API accepts or applies one.
7. **`RiskTransform` correctness defects (found 2026-07-14) — MUST FIX before rollout.** Four
   defects on a type that is public API in a shared module. All are cheap **now**, while gap 6
   holds and nothing calls it; each becomes a breaking change the moment it has a caller. Detail
   and rationale in [B.8](#b8-risktransform-defects--must-fix-before-any-production-wiring).
   - `scaleLosses`, `insurancePolicy` (×2) throw `IllegalArgumentException` via `require` —
     violates correct-by-construction (ADR-001); raw primitives, no Iron refinement (Pass 0a).
   - `given Equal[RiskTransform] = Equal.default` is reference equality on a lambda — an
     unlawful instance sitting in `common`, waiting for a caller.

---

## Functional Equivalence Specification

This refactoring changes internal structure only. The following simulation output
properties **must be bit-for-bit identical** before and after any resolver or cache change.
These are the ground truth properties that regression tests must explicitly verify.

| Property | Where computed | Must preserve |
|---|---|---|
| `probOfExceedance(threshold)` on portfolio result | `LossDistribution` (shared impl) | Identical values — same `outcomes` map, same `nTrials` |
| Portfolio `outcomes` map | `LossDistribution.merge` | Each trial's value = pointwise sum of all child trial values (outer join, missing = 0) |
| `nTrials` on portfolio result | `RiskResultGroup.apply` | Must equal `cfg.defaultNTrials` (same as leaf results) |
| `provenances` reachable from portfolio | `children.flatMap(_.provenances)` | Same set as current `combinedResult.provenances`; same insertion order if children are in the same sequence |
| Leaf node outcomes | Unchanged — leaf simulation path not modified | Identical |

**The key invariant to verify before any resolver change:**

> For a portfolio with children C1 and C2, after the resolver runs:
> `portfolioResult.outcomes.get(trialId) == c1.outcomes.get(trialId).getOrElse(0) + c2.outcomes.get(trialId).getOrElse(0)`
> for every `trialId` that appears in either child.

This invariant is currently tested only vacuously (`rootResult.outcomes.size >= 0` is always
true). **Before making any resolver change, this test must be strengthened.** See A.6.

---

## PART A — Aggregation design

### A.1 The core insight (revised 2026-06-18)

> The original framing placed the monoid on `RiskResult`. This was wrong. The correct
> algebraic structure is described below.

`LossDistribution` is a **product type**: `NodeId × TrialOutcomes`, where `NodeId` is a label
(not algebraic) and `TrialOutcomes = (nTrials: Int, outcomes: Map[TrialId, Loss])` is the
genuine monoid.

```
LossDistribution = NodeId × TrialOutcomes
                   (label)   (the monoid)
```

**`TrialOutcomes` is the honest monoid:**
- `empty` = `(nTrials = N, outcomes = Map.empty)` — zero losses across N trials.
- `combine(a, b)` = outer-join sum of outcome maps, same-N enforced.
- Associative ✅ Commutative ✅ Identity ✅

**`NodeId` does not participate in the algebra.** This is why `combine(a: LossDistribution,
b: LossDistribution)` cannot be a semantically correct monoid on `LossDistribution`:
the `nodeId` of the combined result has no principled derivation from the inputs — it must
always come from external context (the tree structure, the resolver). Any instance that steals
`a.nodeId` is producing a semantic lie.

**`LossDistribution.merge`** is the implementation of `TrialOutcomes.combine`. It already
exists, is correct, and is the right primitive. The separate name (`merge` not `combine`)
accidentally signals the right thing: it operates on the mathematical content and returns
`Map[TrialId, Loss]` rather than a full `LossDistribution`, because constructing the named
result is the caller's responsibility (not the algebra's).

**Portfolio construction is a named constructor, not a monoid operation.** The form is:
```scala
RiskResultGroup(parentNodeId, children*)  // children: List[LossDistribution]
```
The resolver supplies `parentNodeId` from the tree. `RiskResultGroup.apply` calls `merge`
internally. No binary `combine` is involved.

**The canonical structure is:**
```scala
// Option 1 — explicit TrialOutcomes type (makes algebra visible, property-testable)
case class TrialOutcomes(nTrials: Int, outcomes: Map[TrialId, Loss])
given Associative[TrialOutcomes]  // outer-join sum
given Commutative[TrialOutcomes]  // order-independent
given identity(using cfg: SimulationConfig): Identity[TrialOutcomes]  // empty

// Option 2 — implicit (minimum fix: delete false monoid, use RiskResultGroup directly)
// No new type. Resolver calls RiskResultGroup(id, children*); merge is the internal detail.
```

Option 1 makes the algebra explicit and standalone-testable. Option 2 is the minimum viable
fix. Both are correct; Option 1 is better long-term. The choice between them is a decision
gate (new type on a shared domain module → trigger #4/#5).

> The categorical framing in the source conversation (Profunctor / Kleisli / Comonad / CBRNG
> generator fusion) is **not** adopted. Only the monoid lens is — and it now lives in the right place.

### A.2 What problem this actually solves for us

1. **Documented semantic smell**: aggregated portfolios are currently represented as a Leaf-type
   `RiskResult` rather than a proper group/aggregate type
   (`docs/scratch/milestone-2b-cache-and-decisions.md`). A lawful monoid gives a single,
   principled `combineAll`-style aggregation instead of ad-hoc Leaf reuse.

2. **Safe parallel subtree reduction**. The resolver currently aggregates children sequentially
   (`ZIO.foreach` in `RiskResultResolverLive`). **Associativity is exactly the law that licenses
   reordering / parallel reduction.** Making the combine lawful means the parallelism we want
   becomes *correct by construction*, not asserted.

3. **Cleaner provenance access** — under the corrected design, leaf provenances stay in
   `RiskResult.provenances`; portfolio provenances are accessed as `children.flatMap(_.provenances)`
   on `RiskResultGroup`. This is structurally honest: provenance belongs to the node that was
   simulated, not to the aggregate. No manual provenance threading at aggregation time.

### A.3 What this is NOT

- ❌ Not generator fusion / deferred `Int => Double` sampling. That would destroy per-node
  caching, incremental recompute, and provenance — our three crown jewels (ADR-014/015).
- ❌ Not a replacement for `Simulator`. Sampling stays where it is.
- ❌ Not an API change. `SimulationResponse` and Tapir endpoints remain untouched.

### A.4 Critical correctness constraints (must hold for laws to be real)

These are the laws that make the abstraction safe. **If any cannot be satisfied, the monoid is
not lawful and this plan must stop and escalate** (instruction trigger #6 / #9 at impl time):

- **Identity**: `combine(empty, x) == x` and `combine(x, empty) == x`.
- **Associativity**: `combine(combine(a, b), c) == combine(a, combine(b, c))`.

Open questions that determine whether the laws genuinely hold for *our* `RiskResult`:

| Question | Why it matters | Status |
|---|---|---|
| Are child outcome vectors **index-aligned** (same iteration count, same RNG coordinate)? | Pointwise combine is only meaningful if index `i` of child A corresponds to index `i` of child B. | ✅ **Resolved** — `LossDistribution.merge` uses outer-join (missing trial = 0 loss); `RiskResultGroup.apply` inherits this. Same-N invariant enforced by the alignment check in `TrialOutcomes.combine` (Option 1) or by `RiskResultGroup.apply` directly (Option 2). |
| Is provenance associative under the new design? | Parallel reduction may reorder child merges. | ✅ **Resolved** — provenance stays in individual `RiskResult.provenances` and is accessed at read time via `children.flatMap(_.provenances)`. No merge step; no ordering sensitivity. |
| Does `empty` interact correctly with a fixed-N simulation? | An empty result has no samples; combining with an N-sample result must not produce a length mismatch. | ✅ **Resolved** — `TrialOutcomes.empty` carries `nTrials = cfg.defaultNTrials` (Option 1); `RiskResultGroup.apply` handles empty child list explicitly (Option 2). Alignment is always enforced. |

### A.5 Implementation sketch (revised 2026-06-18)

**Delete (false monoid):**
- `RiskResult.combine`
- `given Associative[RiskResult]`, `given Commutative[RiskResult]`
- `RiskResultIdentityInstances` (both copies in `common` and `server`)
- `RiskResult.withNodeId`
- `LossDistributionType` enum + `distributionType` field on `LossDistribution`

**Add alignment guard to `RiskResultGroup.apply`:**
`RiskResult.combine` currently enforces `require(a.nTrials == b.nTrials, ...)`. Deleting
it removes the only alignment check. `RiskResultGroup.apply` must gain this guard
explicitly before the resolver is changed:
```scala
require(
  results.isEmpty || results.map(_.nTrials).distinct.size == 1,
  s"Cannot aggregate distributions with different trial counts: ${results.map(_.nTrials).mkString(", ")}"
)
```
This must be in place before any call site switches to `RiskResultGroup`.

**Widen (mechanical type substitution):**
- `RiskResultGroup.children: List[LossDistribution]` (from `List[RiskResult]`)
- `RiskResultGroup.apply(results: LossDistribution*)` (from `RiskResult*`)
- `RiskResultCache.get/put` signature: `LossDistribution` (from `RiskResult`)
- `RiskResultResolver.ensureCached/ensureCachedAll` return type: `LossDistribution`
- `LECGenerator` method signatures: `LossDistribution` (from `RiskResult`)
- `RiskTreeKnowledgeBase` constructor and field: `Map[NodeId, LossDistribution]`

**Fix resolver portfolio branch:**
```scala
// DELETE this:
childResults.reduce[RiskResult]((a, b) => RiskResult.combine(a, b))
  .withNodeId(portfolio.id)

// REPLACE with:
RiskResultGroup(portfolio.id, childResults*)
// where childResults: List[LossDistribution]
```

**Optional — introduce `TrialOutcomes`:**
If Option 1 (explicit type) is chosen, add `case class TrialOutcomes` with `Associative`,
`Commutative`, `Identity` instances and restructure `RiskResult`/`RiskResultGroup` to embed it.
This makes the monoid property-testable in isolation. Decision gate: new type in shared domain
module → trigger #4.

### A.6 Test strategy — sequencing is mandatory

> **The test sequence below is not optional.** Changing the order risks adapting tests
> to wrong behaviour to fix a refactoring-induced mistake. Each step is a gate.

**Step 1 — Strengthen existing resolver test BEFORE making any change (gate)**

`RiskResultResolverSpec` currently has:
```scala
// THIS IS VACUOUS — always true, verifies nothing:
rootResult.outcomes.size >= 0
```
Before touching the resolver, this must be replaced with an explicit numerical assertion:
```scala
// After resolving the root portfolio:
risk1Result <- resolver.ensureCached(testTree, risk1Id)
risk2Result <- resolver.ensureCached(testTree, risk2Id)
rootResult  <- resolver.ensureCached(testTree, rootId)
allTrialIds = risk1Result.outcomes.keySet ++ risk2Result.outcomes.keySet
then:
  allTrialIds.forall { t =>
    rootResult.outcomes.getOrElse(t, 0L) ==
      risk1Result.outcomes.getOrElse(t, 0L) +
      risk2Result.outcomes.getOrElse(t, 0L)
  }
```
This test must be green on the CURRENT (pre-change) resolver before any edit.
If it is not green on the current code, stop — the current implementation is broken.

**Step 2 — Add alignment guard to `RiskResultGroup.apply` (gate)**

Add the `require` described in A.5. Run `sbt test` — must be green.

**Step 3 — Write new property / unit tests for the replacement (gate)**

- **For Option 1 (`TrialOutcomes`)**: property tests for `Associative[TrialOutcomes]`
  (associativity, commutativity) and `Identity[TrialOutcomes]` (left/right identity).
- **For Option 2 (minimum fix)**: unit tests for `RiskResultGroup.apply` — verify that
  outcomes equal the pointwise sum of children's outcomes for every trial ID.

Run `sbt test` — must be green (new tests and all existing tests).

**Step 4 — Make the resolver change (gate)**

Replace `childResults.reduce(RiskResult.combine).withNodeId` with `RiskResultGroup(id, children*)`.
Widen resolver and cache return types to `LossDistribution`.

Run `sbt test` — all tests including the strengthened Step 1 assertion must be green.
If the strengthened assertion fails, the resolver change broke numerical equivalence.
Fix the resolver; do not weaken the assertion.

**Step 5 — Delete false monoid instances (gate)**

Delete `RiskResult.combine`, `Associative/Commutative[RiskResult]`, `RiskResultIdentityInstances`,
`RiskResult.withNodeId`, `LossDistributionType` enum.

Run `sbt test` — must still be green.

> `scala-test.instructions.md` rule: **never weaken an assertion to fix a failing test.
> If a test fails, the implementation is wrong. Fix the implementation.**

### A.7 Decision gates before any implementation

1. ~~Index-alignment invariant (A.4, row 1).~~ ✅ **Resolved** — the alignment check moves from
   `RiskResult.combine` (being deleted) to `RiskResultGroup.apply` (explicit `require` per A.5).
   The guard must be added to `apply` before the resolver is changed (Step 2 in A.6).
2. ~~`empty` semantics under fixed-N (A.4, row 3).~~ ✅ **Resolved** — `TrialOutcomes` (Option 1) or `RiskResultGroup.apply` empty-list path (Option 2).
3. ~~Provenance-merge law confirmation against ADR-003 (A.4, row 2).~~ ✅ **Resolved** — provenance stays in leaves; portfolio access is `children.flatMap(_.provenances)` at read time.
4. Whether to introduce a distinct aggregate type (resolving the Leaf-as-aggregate smell) or keep
   `RiskResult` and only formalize combine. **This touches `RiskResult` shape → trigger #4/#5.**
   > **Audit update**: `RiskResultGroup` already exists as the correct composite type in
   > `LossDistribution.scala`. The design decision is already made in code — the question is
   > whether to wire the resolver to use it. This is a **behaviour change** → trigger #5.

---

## PART B — Mitigation

> **Audit update (2026-06-18)**: B3 (result-stage endomorphism) is **fully implemented** as
> `RiskTransform` in `modules/common/.../domain/data/RiskTransform.scala`. It has `applyDeductible`,
> `capLosses`, `scaleLosses`, `insurancePolicy`, `filterBelowThreshold`, and a lawful
> `Identity[RiskTransform]` with property tests. **It is not wired into any production call path
> and does not record provenance.** B1 (parameter-stage) does not exist. B2 is subsumed by B3
> (transforms operate on `Map[TrialId, Loss]` inside `RiskResult`). The section below is
> preserved for historical context; options B1–B4 are no longer open questions — B3 is the answer.

> Original note: No good design currently existed. This section proposed options with merits/cons.
> Some options imply new API surface and are flagged accordingly.

### B.0 Overview of the design space

A mitigation reduces frequency (likelihood) and/or severity (loss magnitude). The fundamental
design question is **at which stage** of the pipeline a mitigation acts, and **what algebraic
structure** governs composing multiple mitigations on one risk.

Four candidate stages:

```
RiskParameters --(B1: parameter-stage)--> RiskParameters
                                              |
                                          (sampling)
                                              v
   sample stream --(B2: outcome-stage)--> sample stream
                                              v
   RiskResult --(B3: result-stage)--> RiskResult
                                              v
   (B4: aggregation-stage — mitigation applied at portfolio combine)
```

Two cross-cutting structural questions apply to **all** options:

- **Composition algebra**: how do two mitigations on the same risk combine? Candidates:
  - *Monoid* (order-independent, associative) — clean but only valid if mitigations truly commute.
  - *Ordered pipeline (endomorphism composition)* — order matters (e.g., deductible then cap ≠
    cap then deductible). More faithful, less algebraically convenient.
- **Provenance**: a mitigation is an analytically meaningful transformation and almost certainly
  must be **recorded in provenance** (ADR-003) so results remain explainable. This strongly
  influences which stage is acceptable.

---

### Option B1 — Parameter-stage mitigation (`RiskParameters => RiskParameters`)

From the conversation: frequency mitigation lowers `likelihood`; severity mitigation tightens
the lognormal CI bounds.

**Merits**
- Conceptually simplest; mitigations are pure endomorphisms on validated parameters.
- Mitigated risk is *itself a normal risk* → flows through existing `Simulator` and cache
  **unchanged**.
- Naturally cacheable: a mitigated node is just a node with different parameters → existing
  per-node caching and incremental recompute apply directly. **Strong fit with ADR-014/015.**

**Cons**
- Cannot express mitigations that are **not** representable as a parameter change (e.g., a hard
  per-event cap / deductible that reshapes the *distribution tail* in a way no `(likelihood, CI)`
  pair can encode). Tightening `ciUpper` is an **approximation**, not a true cap — ⚠️ this is a
  *“works but…”* caveat (trigger #6 at impl time).
- Composition: parameter edits may not commute (two severity mitigations both rewriting CI).

**API impact**: potentially **none** if a mitigated risk is modeled as parameter data already
flowing through existing endpoints. Becomes API-affecting only if mitigations are a *new
addressable concept* clients must send/receive.

---

### Option B2 — Outcome-stage mitigation (`Double => Double` on the sample stream)

From the conversation: a post-loss transform (deductible, insurance cap) mapped over each sample.

**Merits**
- Can express **true** caps/deductibles exactly (`loss => max(0, min(loss, cap) - deductible)`),
  which B1 cannot.
- Endomorphism on `Double` composes cleanly; ordered pipeline is natural and faithful.

**Cons**
- Acts **after** sampling, so it changes the relationship between a node's cached raw outcomes and
  its mitigated outcomes. Must decide: cache raw, mitigated, or both? ⚠️ trigger #5 (behaviour of
  cached results).
- Provenance must record the transform to stay explainable (ADR-003).
- Independence/monoid claims from the source conversation are about *aggregation*, not about
  per-sample mitigation; do not conflate.

**API impact**: none if internal; **new surface** if clients specify caps/deductibles.

---

### Option B3 — Result-stage mitigation (`RiskResult => RiskResult`) ✅ IMPLEMENTED

A mitigation as an endomorphism on the whole `RiskResult`. **This is implemented as `RiskTransform`.**

`RiskTransform` is `case class RiskTransform(run: RiskResult => RiskResult)` with:
- `Identity[RiskTransform]` (lawful; `combine` = ordered composition `l then r`)
- `applyDeductible(d: Loss)`, `capLosses(c: Loss)`, `scaleLosses(f: Double)`,
  `insurancePolicy(d: Loss, c: Loss)`, `filterBelowThreshold(t: Loss)`
- Property tests for all identity laws in `RiskTransformSpec`

**What it does**: maps `RiskResult.outcomes` via per-trial `Long => Long` transforms. Works on
the sparse `Map[TrialId, Loss]` inside `RiskResult` — which is B2 (outcome-level) expressed
through the B3 (result-level) interface. Both concerns are unified.

**Remaining gaps before B3 is complete**:
1. **Provenance not recorded**: no transform appends a mitigation event to `RiskResult.provenances`.
   To satisfy ADR-003, each named transform must record what it did and at what parameter.
2. **Not wired**: no service, controller, or API invokes `RiskTransform`. It is pure domain
   infrastructure waiting to be connected.
3. **Caching decision open**: cache pre-mitigation result only (raw), apply transforms at read
   time? Or cache mitigated variants separately? This is **trigger #5** (behaviour change).

**API impact**: none currently (internal only); new surface required if clients submit transforms.

---

### Option B4 — Aggregation-stage mitigation (portfolio-level)

Mitigation applied at the *combine* step (e.g., a portfolio-wide hedge / shared cap across
children).

**Merits**
- Expresses genuinely portfolio-level mitigations that cannot be attributed to a single child.

**Cons**
- Interacts with the Part A monoid in subtle ways: a mitigation at aggregation may **break
  associativity** of the combine (the whole point of Part A). ⚠️ This is a real conceptual hazard —
  a portfolio cap is *not* distributable over children, so it cannot live inside the monoid's
  `combine`; it must be a **separate endomorphism applied after** `combineAll`.
- Hardest to cache and to reason about incrementally.

**API impact**: likely new surface (portfolio-level mitigation is a distinct concept).

---

### B.5 Comparative summary

| Option | Expressiveness | Cache/incremental fit | Provenance fit | Composition | API risk |
|---|---|---|---|---|---|
| B1 Parameter | Low–Med (approx. caps) | **Excellent** | Indirect | Endo (may not commute) | Low |
| B2 Outcome | High (true caps) | Medium | Needs explicit handling | Endo (ordered) | Med (if exposed) |
| B3 Result | High | Good | **Excellent** | Endo (ordered) + pairs with Part A | Med (if exposed) |
| B4 Aggregation | Portfolio-only | Poor | Hard | **Must sit outside monoid** | High |

### B.6 Tentative recommendation (for discussion, not approved)

- **Most promising single direction: B3 (result-stage endomorphism)**, *because* it unifies with
  Part A (monoid for aggregation on `TrialOutcomes` + endomorphisms for mitigation on
  `LossDistribution` = one coherent algebra) and gives provenance a single honest home (ADR-003).
- **B1** remains attractive as a *complementary* mechanism for mitigations that are genuinely
  parameter changes (lower frequency), since it requires essentially zero new machinery and is the
  best cache fit.
- **B4** should be modeled as an endomorphism **applied after** `combineAll`, explicitly **outside**
  the monoid, to avoid breaking associativity.
- **Composition algebra**: prefer **ordered endomorphism composition** over a mitigation-monoid,
  because real mitigations (deductible vs. cap) do **not** commute; claiming a monoid here would be
  unlawful and is exactly the kind of "mathematically pretty but wrong" trap the source
  conversation falls into elsewhere.

### B.7 Open decisions for mitigation

1. ~~Which stage(s) to support — single (B3?) or a small combination (B1 + B3)?~~ ✅ **Resolved** — B3 (`RiskTransform`) is implemented. B1 has no implementation and no use case identified yet.
2. ~~Composition algebra: ordered endomorphisms vs. monoid (recommendation: ordered).~~ ✅ **Resolved** — `Identity[RiskTransform]` uses ordered composition (`l then r`); `andThen`/`compose` methods explicit.
3. **Caching of pre- vs. post-mitigation results.** Still open. Current cache stores raw results; `RiskTransform` is applied outside the cache. Decision needed before any production wiring. → **trigger #5**
4. **Whether mitigation becomes a client-facing concept** → if yes, this is a deliberate **API-shape decision** (trigger #1) requiring its own ADR, not a refactor.
5. **Provenance representation of a mitigation event** — `RiskTransform` currently does not append to `RiskResult.provenances`. What `NodeProvenance` or new type should record a transform application is unresolved.

---

### B.8 `RiskTransform` defects — MUST FIX before any production wiring

Code review 2026-07-14 against `RiskTransform.scala`. **Gap 6 is the opportunity, not an
excuse**: `RiskTransform` is public API in a shared module (`commonJVM`/`commonJS`) with zero
production callers. Every item below is a local edit today and a breaking signature change once
a call path exists. Fix them **before** wiring, not as part of it — otherwise B.7 decision 3
lands on top of a type that is already wrong.

**1–3. `require` throws where the codebase uses typed errors.**

| Site | Guard |
|---|---|
| `RiskTransform.scala:154` | `require(factor >= 0.0, "Scale factor must be non-negative")` |
| `RiskTransform.scala:172` | `require(deductible >= 0, "Deductible must be non-negative")` |
| `RiskTransform.scala:173` | `require(cap > deductible, "Cap must be greater than deductible")` |

`require` throws `IllegalArgumentException` — an untyped, unrecoverable failure in a ZIO
codebase whose stated rule is *validate once, at the boundary*, with smart constructors
returning `Validation[ValidationError, T]` (ADR-001). Compounding it, `factor: Double` and
`deductible`/`cap: Loss` arrive as **raw primitives with no Iron refinement** — a Pass 0a
finding on its own (`code-quality-review`). A non-negative scale factor and a
`cap > deductible` cross-field rule are exactly what the Iron + `Validation.validateWith`
pattern exists to express.

Note `RiskResult.combine:131` carries the same `require` smell, but it is already in the
deletion set (A.5) — **do not fix it, delete it**. These three are not; they survive the
refactor untouched unless fixed deliberately.

**4. `given Equal[RiskTransform] = Equal.default` is unlawful.**

`Equal.default` delegates to `==`. `RiskTransform` is a `case class` whose only field is a
`RiskResult => RiskResult`, and case-class `equals` compares that field **by reference**.
So `capLosses(1000) === capLosses(1000)` is `false` — two structurally identical transforms
compare unequal, and `Equal`'s reflexivity-beyond-identity expectation is broken for every
value the smart constructors produce.

Not currently live: nothing consumes the instance, and the Identity law tests sidestep it by
running both transforms and comparing `.outcomes` (`RiskTransformSpec:73`) rather than using
`Equal`. That is why it survived — **the law suite does not exercise the instance it would
most naturally use.** Either delete the instance, or reify transforms into a comparable spec
(see below) and derive `Equal` from that.

**Root cause shared by 4 and the caching question.** `RiskTransform` wraps an opaque function,
so it is **not data**: it cannot be compared, serialised, hashed, or logged. This is precisely
why B.7 decision 3 (pre- vs. post-mitigation caching) is hard — a content-addressed cache key
cannot cover a transform that has no representation. It works only if a transform's
*parameters* live in the node's stored JSON and the function is constructed *from* that JSON,
making the JSON the reified spec. If B.7 decision 3 lands on caching post-mitigation results,
a `sealed trait TransformSpec` (data) with an interpreter to `RiskTransform` (function) is the
likely shape — new type in a shared module → **trigger #4**, and it subsumes defect 4.
See `docs/scratch/milestone-2b-cache-and-decisions.md` §A4 review (DD-15) for the cache-side
analysis, including why portfolios cannot carry a `RiskTransform` at all under B3 (gap 5).

---

## PART C — Other improvement suggestions (beyond Monoid & Mitigation)

Surfaced from the review; each is **informational**, none approved.

### C.1 Resolver parallelism — but gated on Part A
The sequential `ZIO.foreach` child traversal in `RiskResultResolverLive` is the main performance
gap. **Do not parallelize it independently** — its correctness depends on the Part A associativity
law. Treat C.1 as the *payoff* of Part A, not a separate task. (Cross-ref:
`docs/scratch/milestone-2b-cache-and-decisions.md`.)

### C.2 Aggregate type vs. `RiskResult` reuse — Leaf-as-aggregate smell

> **Audit update**: `RiskResultGroup` exists in `LossDistribution.scala` as the correct
> composite subtype of `LossDistribution`. It carries `children: List[RiskResult]` alongside
> the combined `outcomes: Map[TrialId, Loss]`. The design decision (introduce a distinct
> aggregate type) is **already made in code**.

**The smell, precisely**: `RiskResultResolverLive.simulateNode` aggregates portfolio children via
`childResults.reduce[RiskResult]((a, b) => RiskResult.combine(a, b)).withNodeId(portfolio.id)`.
This produces a `RiskResult` whose type is `Leaf` (the subtype), despite carrying a portfolio's
`nodeId`. Children are discarded. Downstream code cannot distinguish a simulated leaf from an
aggregated portfolio.

**The fix**: replace the `reduce` + `withNodeId` with `RiskResultGroup(portfolio.id, childResults*)`,
which stores the children list and carries the correct `Composite` type tag. The resolver's
return type for portfolio paths must change from `Task[RiskResult]` to `Task[LossDistribution]`
(the common supertype) — or the cache must be widened. **This is a behaviour change → trigger #5.**
See A.7 gate 4.

### C.3 `includeProvenance` flag honesty
`RiskResultResolver` exposes `includeProvenance`, but the live implementation always captures
provenance in leaf simulation (see `docs/dev/PLAN-PROVENANCE-ENDPOINT.md`). Either honor the flag
or remove it. ⚠️ Changing/removing a public method parameter is **trigger #4**.

### C.4 Sampler / RNG — explicitly DO NOT adopt conversation's RNG
For the record (to prevent future temptation): the source conversation's RNG suggestions are
statistically unsound for tail risk and **must not** be ported:
- `Objects.hash(assetId, idx)` is not a uniform source (collisions, weak tails) — fatal for LEC.
- Reusing the Bernoulli draw as `u1` inside Box-Muller couples occurrence and severity.
- `flatMap` on `Int => A` reuses the same index, introducing correlation.
Our existing `Simulator` sampler is the correct home and is statistically sounder. This note exists
purely to close the door.

---

## Pickup checklist (updated after 2026-06-18 design review)

**Resolved — no action needed:**
- A.4 rows 1–3 (alignment, provenance, empty semantics) — resolved (see audit).
- `RiskResult → LossDistribution` widening — confirmed mechanical, zero logic change.
- `LossDistributionType` enum — confirmed dead code, safe to delete.
- B3 design choice — implemented as `RiskTransform`.
- B.7 decisions 1–2 (stage selection, composition algebra) — resolved in code.

**Remaining open items (each requires a Decision before work starts):**

1. **Option 1 vs Option 2 for A.1** — Introduce explicit `TrialOutcomes` type (Option 1) or
   proceed with minimum viable fix only (Option 2, delete false monoid + use `RiskResultGroup`)?
   Option 1 is richer; Option 2 is smaller scope. New type in shared module → **trigger #4**.

2. **Delete false monoid + fix resolver** — once Option 1/2 decided, execute in the order
   specified by A.6 (Steps 1–5). Key work items within that sequence:
   - Add `require` alignment guard to `RiskResultGroup.apply` (A.5, A.6 Step 2).
   - Delete `RiskResult.combine`, `Associative/Commutative[RiskResult]`, `RiskResultIdentityInstances`,
     `RiskResult.withNodeId`, `LossDistributionType` enum.
   - Widen `RiskResultGroup.children` to `List[LossDistribution]`.
   - Replace resolver `reduce(combine)` with `RiskResultGroup(portfolio.id, childResults*)`.
   - Widen cache and all downstream signatures to `LossDistribution`.
   - Behaviour change in resolver → **trigger #5**.

3. **Tests — follow A.6 sequencing (Steps 1–5 are mandatory gates)**. Step 1 must be
   completed on the current code before any implementation begins.

4. **B.7 decision 3**: Caching policy for pre- vs. post-mitigation results before wiring
   `RiskTransform` into any production call path. → **trigger #5**.
5. **B.7 decision 5**: Provenance representation for a `RiskTransform` application (ADR-003).
6. **B.7 decision 4**: If mitigation becomes client-facing, open a separate ADR (trigger #1).
7. Load `adr-constraints` before any implementation phase.
8. End with mandatory `code-quality-review`.

**MUST FIX before rollout — `RiskTransform` defects (gap 7, detail in [B.8](#b8-risktransform-defects--must-fix-before-any-production-wiring)):**

These are **not** gated behind the Option 1 / Option 2 decision and do not touch the monoid
work — they can land independently and should land first. `RiskTransform` is public API in a
shared module with zero callers (gap 6); that window is what makes them free.

- [ ] Replace the three `require` guards (`RiskTransform.scala:154, 172, 173`) with
      Iron-refined parameters + smart constructors returning `Validation` (ADR-001).
      Cross-field rule `cap > deductible` via `Validation.validateWith`, not `.flatMap`.
      Public signature change → **trigger #4** if the refined types are new.
- [ ] Delete or replace `given Equal[RiskTransform] = Equal.default` (`RiskTransform.scala:88`)
      — currently reference equality on a lambda.
- [ ] Add a law-suite case that exercises `Equal[RiskTransform]` directly if the instance is
      kept. The current suite compares `.outcomes` and would not have caught this.
- [ ] **Blocking gate:** none of the above may be deferred past `RiskTransform`'s first
      production call path. After that they are breaking changes.