# PLAN — `Monoid[RiskResult]` Aggregation & Mitigation Design Exploration

Status: **Draft / not approved for implementation**
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
`Monoid`**. This document captures that insight at implementation-pickup level, and separately
explores **mitigation** design (an acknowledged open topic with no current good design).

---

## PART A — `Monoid[RiskResult]` aggregation

### A.1 The core insight

Aggregating independent child risks into a portfolio is **associative** and has an **identity**
(a zero-loss / empty result). That is precisely the algebraic signature of a `Monoid`:

- `empty` = the zero-loss result (empty outcomes + empty provenance).
- `combine(a, b)` = index-aligned outcome combination + provenance merge.

Provenance composition is **itself** a monoid (empty provenance + associative merge), so the
overall `RiskResult` monoid is the product of two monoids (outcomes × provenance).

> The categorical framing in the source conversation (Profunctor / Kleisli / Comonad / CBRNG
> generator fusion) is **not** adopted. Only the monoid lens is.

### A.2 What problem this actually solves for us

1. **Documented semantic smell**: aggregated portfolios are currently represented as a Leaf-type
   `RiskResult` rather than a proper group/aggregate type
   (`docs/scratch/milestone-2b-cache-and-decisions.md`). A lawful monoid gives a single,
   principled `combineAll`-style aggregation instead of ad-hoc Leaf reuse.

2. **Safe parallel subtree reduction**. The resolver currently aggregates children sequentially
   (`ZIO.foreach` in `RiskResultResolverLive`). **Associativity is exactly the law that licenses
   reordering / parallel reduction.** Making the combine lawful means the parallelism we want
   becomes *correct by construction*, not asserted.

3. **Cleaner provenance accumulation** — provenance merge becomes the monoid of the provenance
   component, aligning with ADR-003 instead of being threaded manually.

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

| Question | Why it matters | Resolution needed before impl |
|---|---|---|
| Are child outcome vectors **index-aligned** (same iteration count, same RNG coordinate)? | Pointwise combine is only meaningful if index `i` of child A corresponds to index `i` of child B. If counts differ, associativity/identity break. | **Decision required** — confirm invariant or define alignment rule. |
| Is provenance merge **associative and commutative** as currently structured (ADR-003)? | Parallel reduction may reorder merges. | Verify against ADR-003 provenance model. |
| Does `empty` (zero outcomes) interact correctly with a fixed-N simulation? | An empty result has no samples; combining with an N-sample result must not produce a length mismatch. | **Decision required** — define empty semantics (zero-vector of length N vs. true neutral). |

> ⚠️ The third row is the subtle one: a *true* monoidal `empty` is length-agnostic, but our
> outcomes are fixed-length sample vectors. We likely need either (a) `empty` = zero-vector of
> the ambient N, or (b) a representation where length is carried/validated. This is a genuine
> design fork, not a detail.

### A.5 Implementation sketch (for pickup — NOT final)

Conceptual shape only. Real types must use existing domain types and smart constructors
(correct-by-construction; no `new`/raw primitives).

```
// CONCEPTUAL — not the final signature.
// RiskResult monoid = product of (outcomes monoid) × (provenance monoid)

empty:    RiskResult with zero/neutral outcomes + empty provenance
combine:  index-aligned outcome combine  +  provenance merge
```

- Live in the **domain/services layer**, behind the resolver. No leakage to HTTP DTOs.
- Resolver aggregation rewritten as a single lawful reduction over children.
- Parallelism (replacing sequential `ZIO.foreach`) added **only after** laws are proven by
  property tests — associativity first, identity second.

### A.6 Test strategy (mandatory before behaviour change)

- **Property tests** for monoid laws (identity, associativity) on `RiskResult` — likely with a
  generator that respects the index-alignment invariant.
- **Provenance-merge** property tests (associativity; commutativity if relied upon for parallel
  reduction).
- **Regression**: existing `RiskResultResolverSpec` / `RiskResultCacheSpec` must remain green and
  must demonstrate that aggregated results are *unchanged* vs. current Leaf-based aggregation
  (i.e., the refactor is behaviour-preserving for outputs, only changing internal structure).

### A.7 Decision gates before any implementation

1. Index-alignment invariant (A.4, row 1).
2. `empty` semantics under fixed-N (A.4, row 3).
3. Provenance-merge law confirmation against ADR-003 (A.4, row 2).
4. Whether to introduce a distinct aggregate type (resolving the Leaf-as-aggregate smell) or keep
   `RiskResult` and only formalize combine. **This touches `RiskResult` shape → trigger #4/#5.**

---

## PART B — Mitigation design exploration (OPEN TOPIC)

> No good design currently exists. This section proposes options with merits/cons. **Nothing here
> is approved.** Some options imply new API surface and are flagged accordingly.

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

### Option B3 — Result-stage mitigation (`RiskResult => RiskResult`)

A mitigation as an endomorphism on the whole `RiskResult` (transforms outcomes **and** appends
provenance in one place).

**Merits**
- **Single, honest home for provenance**: the mitigation *is* the provenance event. Best alignment
  with ADR-003.
- Composes as endomorphism; can be ordered.
- Operates at the same granularity as the cache (`RiskResult` per node), so caching story is
  explicit and controllable.
- **Composes naturally with Part A**: if `RiskResult` has a monoid for aggregation, mitigations are
  endomorphisms on the same object — a clean, unified algebra (monoid for combine, endomorphisms
  for mitigation).

**Cons**
- Most invasive to `RiskResult` semantics (now both a sampling output *and* a transformation
  target) — trigger #4/#5.
- Requires deciding caching of pre/post-mitigation results.

**API impact**: none if internal; new surface if exposed.

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
  Part A (monoid for aggregation + endomorphisms for mitigation = one coherent algebra on
  `RiskResult`) and gives provenance a single honest home (ADR-003).
- **B1** remains attractive as a *complementary* mechanism for mitigations that are genuinely
  parameter changes (lower frequency), since it requires essentially zero new machinery and is the
  best cache fit.
- **B4** should be modeled as an endomorphism **applied after** `combineAll`, explicitly **outside**
  the monoid, to avoid breaking associativity.
- **Composition algebra**: prefer **ordered endomorphism composition** over a mitigation-monoid,
  because real mitigations (deductible vs. cap) do **not** commute; claiming a monoid here would be
  unlawful and is exactly the kind of "mathematically pretty but wrong" trap the source
  conversation falls into elsewhere.

### B.7 Open decisions for mitigation (all blocking)

1. Which stage(s) to support — single (B3?) or a small combination (B1 + B3)?
2. Composition algebra: ordered endomorphisms vs. monoid (recommendation: ordered).
3. Caching of pre- vs. post-mitigation results.
4. **Whether mitigation becomes a client-facing concept** → if yes, this is a deliberate **API-shape
   decision** (trigger #1) requiring its own ADR, not a refactor.
5. Provenance representation of a mitigation event (ADR-003).

---

## PART C — Other improvement suggestions (beyond Monoid & Mitigation)

Surfaced from the review; each is **informational**, none approved.

### C.1 Resolver parallelism — but gated on Part A
The sequential `ZIO.foreach` child traversal in `RiskResultResolverLive` is the main performance
gap. **Do not parallelize it independently** — its correctness depends on the Part A associativity
law. Treat C.1 as the *payoff* of Part A, not a separate task. (Cross-ref:
`docs/scratch/milestone-2b-cache-and-decisions.md`.)

### C.2 Aggregate type vs. `RiskResult` reuse
Resolve the documented Leaf-as-aggregate smell *as part of* Part A decision A.7.4. Either introduce
a distinct aggregate representation or formally bless `RiskResult` as the aggregate via the monoid.
Pick one deliberately — drifting is the current problem.

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

## Pickup checklist (next session)

1. Resolve A.7 decision gates (esp. index-alignment + `empty` semantics + Leaf-vs-aggregate).
2. Load `adr-constraints` (new/changed types touch ADR-003/014/015).
3. Write monoid-law property tests **first**; prove laws before any resolver behaviour change.
4. Resolve B.7 decisions; if mitigation goes client-facing, open a separate ADR (API decision).
5. Only then implement, ending with the mandatory `code-quality-review`.