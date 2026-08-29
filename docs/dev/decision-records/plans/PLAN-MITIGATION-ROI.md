# PLAN-MITIGATION-ROI — Return-on-Investment for mitigations

**Status: DESIGN STAGE. Decisions OPEN. No implementation authorized.**

This is a **new plan** (not a continuation of PLAN-RISKTRANSFORM). It defines
how the platform turns the loss distributions it already simulates into a
Return-on-Investment (ROI) read-out for a mitigation, so a user can answer
"is this control worth its cost, and by how much?" from the simulated data
rather than from hand-entered savings.

The conception and the first-principles teaching derivations live in
`docs/scratch/MITIGATION-ROI.md` (kept, not superseded). This plan references
those derivations rather than repeating them; it exists to ground them in the
current code and to hold the five open decisions until they are ruled.

Every signature in this document is **provisional** — a sketch to make the
decisions concrete, NOT an approved signature under G1/G3. Nothing here confers
plan coverage for a source edit until the decisions are ruled and this document
is elevated to implementation grade (exact signatures, file inventory,
verification plan) and approved.

---

## 1. Goal

Given a mitigation with a cost, and the tree it applies to, produce a decision
read-out that states:

- the money benefit the mitigation buys (a reduction in simulated loss),
- measured against its cost,
- at the tree level where the benefit is real (the enclosing portfolio node,
  where cross-risk aggregation has already happened),
- and, where the benefit is uneven across outcomes, some honest picture of that
  spread rather than a single number that hides it.

The benefit must be **derived from the simulated loss distributions**, never
entered by hand. The engine already produces those distributions; the missing
pieces are a cost input, one tail statistic (TVaR), and the read-side assembly
that differences base-vs-mitigated curves.

---

## 2. What the code computes today (grounding)

Confirmed by reading the current sources:

**`modules/common/.../domain/data/LossDistribution.scala`**
- `Loss` is `Long` = **whole millions of dollars** (`1L` = $1M).
- `TrialOutcomes(nTrials: PositiveInt, outcomes: Map[TrialId, Loss])` — a sparse
  per-trial loss vector; a commutative monoid whose `combine` is a per-trial
  pointwise sum via `Math.addExact` (overflow → `ArithmeticException` →
  `ValidationError` at `RiskResultGroup.create`, ADR-010/033).
- `RiskResult` / `RiskResultGroup` extend the sealed `LossDistribution`. The
  full per-trial sample is retained, so **any read-side statistic (mean, any
  quantile, any tail mean, base-minus-mitigated difference) is computable
  without re-simulation** — this is the ADR-014 "cache outcomes, not curves"
  guarantee.

**`modules/server/.../simulation/LECGenerator.scala`**
- `averageAnnualLoss(result): Double` — the mean of the unconditional loss
  distribution (includes zero-loss trials). This is AAL = ALE, and by the
  identity `E[Loss] = ∫₀^∞ P(Loss > x) dx` it is the area under the LEC.
- `calculateQuantiles` (p90/p95/p99/p99.5), `unconditionalQuantile(result, p)`,
  `probabilityOfNoLoss`, `findQuantileLoss`.
- `generateCurvePointsMulti[K]` — a shared-tick multi-curve overlay engine
  (already used for comparisons); the base-vs-mitigated overlay reuses it.
- `val tailCutoff: Double = 0.005` — display trims at 0.5% exceedance (1-in-200,
  Solvency II); the full sample is retained behind the trimmed curve.
- **No TVaR method exists.** This is the one genuinely missing engine output.

**`modules/common/.../domain/data/LEC.scala`**
- `LECPoint(loss: Long, exceedanceProbability: Double)`.
- `LECNodeCurve(id, name, curve, quantiles, averageAnnualLoss,
  probabilityOfNoLoss)` — the per-node DTO. A TVaR field would land here,
  alongside `averageAnnualLoss`. Built in `RiskTreeServiceLive.scala` /
  `LEC.scala`; consumed on the frontend by `LECSpecBuilder.scala`.

**`modules/common/.../domain/data/Mitigation.scala`**
- `Mitigation(id, name, target, spec, precedence)` — **no cost field.** Smart
  constructor `create(...): Validation[ValidationError, Mitigation]` with
  accumulated cross-field rules. A cost field would be added here and threaded
  through the private `Raw` codec.

**Net:** the data needed for benefit is present and retained; what is missing is
(1) a cost input on the mitigation, (2) a TVaR computation, (3) the read-side
ROI assembly and a base-vs-mitigated overlay.

---

## 3. ROI concepts (pointer to the scratch derivations)

Full teaching derivations are in `docs/scratch/MITIGATION-ROI.md`. In brief, so
this plan is readable on its own:

- **AAL / ALE (Average Annual Loss / Annual Loss Expectancy)** — the mean of the
  unconditional loss distribution. `ΔAAL` (base minus mitigated) is the expected
  yearly money saved, and equals the area between the two LECs.
- **ROSI (Return on Security Investment)** — `((ALE_before − ALE_after) − Cost) /
  Cost`, a one-year ratio. A first, coarse headline.
- **The p·c decomposition (scratch §4).** "The mitigation pays off in X% of
  years" is not free information when the loss's firing frequency `p` was an
  input: `P(pays off) = p · c`, where `c = P(saving ≥ annual cost | it fired)`.
  It is only informative where the annual cost cuts through the *spread* of
  per-occurrence savings; where cost sits below every possible saving, `c = 1`
  and the statement is just `p` restated. So expose the conditional `c` only
  where it is non-trivial, not a headline "pays off in X%".
- **TVaR (Tail Value at Risk / expected shortfall)** — the mean loss in the worst
  tail beyond a quantile (e.g. mean of the worst 1%). Subadditive and
  tail-focused; `ΔTVaR` is the **non-circular** deliverable: two mitigations with
  identical `ΔAAL` can have `ΔTVaR` differing by an order of magnitude (scratch
  §5 worked example). This is why TVaR is worth adding.
- **NPV / discounting (scratch §7).** For multi-year cost shapes (upfront +
  maintenance), a pluggable discount rate `r` lets a CFO trade future money
  against present money: `PV = FV / (1+r)^t`. Default `r = 0` (no discounting);
  the mechanism is present but neutral until someone sets a rate.
- **With-vs-without overlay (scratch §6).** The dependence-aware comparison, per
  `docs/scratch/DEPENDENCE.md` (a): compare the enclosing portfolio node's curve
  with the mitigation applied against the same node without it. Aggregation has
  already folded in shared-tree effects at that node.

---

## 4. Cross-cutting ADR concerns (surveyed for this work)

These are the constraints the ROI work will touch. They are flagged here so the
implementation-grade elevation respects them; several also feed the open
decisions in §5.

- **ADR-032 (two content-equality relations).** There are two hashes: the
  **domain content hash** (`LeafSimContent` projection — gates the simulation
  cache, survives renames) and the **storage hash** (full JSON blob — gates
  Irmin merge conflicts). **A cost field must ride the storage blob (so it
  versions, diffs, and merges with the tree) but must NOT enter the domain /
  simulation hash** — cost does not change any simulated loss, so a cost edit
  must not invalidate a single simulation cache entry. Same precedent as `name`
  and the OD-4 "mitigation-blind" simulation identity. This is a hard constraint
  on where the cost field is projected, and it interacts with Decision 4.
- **ADR-018 (nominal wrappers) + ADR-001 (domain types) + correct-by-construction.**
  A cost is money, and `Loss` is `Long`-millions — it cannot represent a
  $250k/yr fee. Cost therefore needs its own value type with its own precision,
  built by a smart constructor at the boundary, not reusing `Loss`. The unit and
  precision choice is a sub-decision inside Decision 4.
- **ADR-014 (cache outcomes, not curves).** TVaR and every ROI number are
  **read-side computations over the retained per-trial sample** — no
  re-simulation, no new cached artifact. TVaR lands as a field on the existing
  `LECNodeCurve` DTO, computed where `averageAnnualLoss` already is.
- **ADR-033 / ADR-010 (arithmetic and validation error boundaries).** Cost
  arithmetic, NPV discounting, and any ratio (division by cost) must route
  overflow / divide-by-zero / invalid-rate through the established
  `Validation` / typed-error path, not throw. `Math.addExact` precedent already
  exists for the per-trial sums.
- **Correct-by-construction (validate once at the boundary).** A `MitigationCost`
  smart constructor (`create(...): Validation[ValidationError, MitigationCost]`)
  validates the fee shape once; services receive an already-valid value. A
  "which benefit metric" selector, if user-facing, is a read-side enum decoded
  at the boundary.
- **Sealed-trait exhaustiveness (compile error).** If cost is modelled as a
  sealed ADT (Flat vs UpfrontPlusMaintenance), every match on it is checked at
  compile time — adding a variant later is a guided change, not a silent gap.

---

## 5. Sequencing prerequisite

The **single-mitigation, single-node ROI read-out** (cost field + TVaR + ΔAAL /
ΔTVaR + ROSI/NPV numbers) is largely standalone: it needs the cost field, the
TVaR computation, and read-side assembly.

The **with-vs-without portfolio overlay (scratch §6)** depends on the
mitigation-selection / compare view that is **PLAN-RISKTRANSFORM M3/M4** work
(VQL targeting analytics + the API surface and frontend compare view). The
overlay should be sequenced after that view exists, or share its plumbing. This
is a genuine prerequisite (category-1 style), not deferral of in-scope work —
the overlay is a distinct increment that rests on an unbuilt subsystem. It is
called out here so the decisions can be ruled with the sequencing visible; the
implementation-grade split (what ships in the first ROI increment vs what waits
on M4) is itself part of elevating this plan.

---

## 6. Open decisions — TO BE RULED (do not treat as settled)

Five decisions, in decision-guide format. Each states the goal, the real
options with plain pros/cons and a concrete example, and my labelled
recommendation. **None is ruled.** They are presented for the user in §7 output.

### Decision 1 — Default benefit metric, and which alternatives to offer

**Goal.** Pick the headline number that represents "the money this mitigation
buys," and which other metrics to expose alongside it. This is the number the
whole read-out is built around, so it needs to be both meaningful and honest.

**Options.**
- **(1a) `ΔAAL` as the default headline, offer `ΔVaR` and `ΔTVaR` alongside.**
  ΔAAL = drop in mean yearly loss. Cheap, already computable, intuitive
  ("saves $2M/yr on average"). But it hides tail shape: a mitigation that only
  ever shaves small frequent losses and one that removes a rare catastrophe can
  show the *same* ΔAAL. Offering ΔTVaR beside it restores the tail picture.
  *Example:* Leaf A (rare $60M breach, 5%) mitigated to $20M and Leaf B
  (frequent $5M fraud, 40%) eliminated both show `ΔAAL ≈ $2M`; but
  `ΔTVaR(99%) ≈ $40M` for A vs `≈ $2M` for B — only ΔTVaR separates them.
- **(1b) `ΔTVaR` as the default headline.** Leads with the tail, which is where
  risk-transfer decisions actually bite. But TVaR needs a chosen tail level
  (99%? 99.5%?), is less intuitive as a "how much does it save" number, and for
  a mitigation that targets frequent small losses it understates a real benefit
  that ΔAAL would show.
- **(1c) ΔAAL only (no TVaR this increment).** Smallest build — no TVaR method
  needed. But it ships the exact circular / tail-blind read-out the scratch
  analysis identified as the weakness; the one genuinely new piece of
  information (tail differentiation) is exactly what's dropped.

**My recommendation: (1a).** ΔAAL is the honest, intuitive headline and is free;
ΔTVaR is the one metric that adds non-circular information, so it is worth the
one new engine method. Offer VaR too since the quantile machinery already
exists. This makes TVaR a dependency of the first increment, which is a small,
self-contained read-side addition.

### Decision 2 — Single-number vs distributional presentation

**Goal.** Decide how much of the benefit *distribution* to show, versus a single
ROI number, so the read-out is neither misleadingly reductive nor unreadable.

**Options.**
- **(2a) Headline number + with-vs-without overlay, drop "P(pays off)", expose
  conditional `c` only where non-trivial.** One ROSI/NPV headline for scanning,
  the two-curve overlay for the shape, and the payoff-probability shown *only*
  when the annual cost cuts through the saving spread (so it carries real
  information, per §3). *Example:* cost $1M below every possible saving → no
  payoff-% shown (it would just restate the 5% firing rate); cost $3M cutting
  through a $5M/$80M saving spread → show "covers its cost in ~2.5% of years
  (half the times it fires)."
- **(2b) Single ROSI number only.** Simplest UI. But it is the reductive view
  the scratch analysis warns against — two very different risk profiles collapse
  to one ratio, and the tail is invisible.
- **(2c) Full distributional dashboard always.** Overlay + payoff-% + conditional
  `c` + return-period table always on. Most information, but most of it is noise
  when cost sits below the saving spread, and "pays off in 5% of years" reads as
  insight when it is just the input frequency restated.

**My recommendation: (2a).** It keeps a scannable headline, shows the shape via
the overlay, and — critically — suppresses the payoff-% exactly when it would be
a tautology, showing the conditional `c` only where it is informative. This is
the presentation the scratch §4/§6 reasoning points to.

### Decision 3 — Discount-rate handling

**Goal.** Decide whether and how to discount multi-year cash flows (relevant once
a cost has an upfront + maintenance shape spanning years).

**Options.**
- **(3a) Pluggable rate, default 0.** A single rate field a user (CFO) can set to
  play with present-value trade-offs; at 0 it is a plain undiscounted sum, so it
  is neutral by default and never silently bakes in an assumption. *Example:*
  upfront $3M + $0.5M/yr for 5y at r=0 → PV = $5.5M; at r=8% → PV ≈ $5.0M, and
  the NPV vs benefit updates live.
- **(3b) No discounting.** Simplest; sum nominal cash flows. But it cannot answer
  the CFO question the user explicitly raised, and adding it later is a schema
  change.
- **(3c) Opinionated default rate (e.g. 8% WACC).** Bakes in a "correct" cost of
  capital. But any hard-coded rate is wrong for most organisations and hides a
  judgment the user said they'd rather control; inflation-style discounting in
  particular the user said nobody cares about.

**My recommendation: (3a).** It is neutral at default (0 = no opinion), answers
the CFO "play with it" use the user named, and adding the field now avoids a
later schema change. The rate is a presentation/analysis input, not stored
simulation state.

### Decision 4 — Cost field shape on `Mitigation` (+ monetary unit sub-decision)

**Goal.** Decide the type of the new cost field, and the monetary unit/precision
it carries. This is the one decision that touches the domain model and ADR-032.

**Options (cost shape).**
- **(4a) Sealed `MitigationCost` ADT: `Flat(yearly)` | `UpfrontPlusMaintenance(
  upfront, yearly)`.** Models exactly the two cost shapes named; exhaustive
  matching is compiler-checked; NPV logic pattern-matches cleanly. Slightly more
  code than a flat record.
- **(4b) One flat record with optional upfront.** `MitigationCost(upfront:
  Option[Money], yearly: Money)`. Fewer types. But it makes "flat fee" an
  `upfront = None` special case rather than a named shape, and invites invalid
  states (e.g. upfront set with a zero yearly that means something ambiguous).
- **(4c) A single yearly number only.** Smallest. But it cannot represent the
  upfront + maintenance shape the user explicitly asked to support, so it fails
  a stated requirement.

**Sub-decision (monetary unit/precision), applies under 4a or 4b.**
- `Loss` is `Long`-millions and **cannot** hold a $250k/yr fee. Cost needs its
  own value type. Candidates: (i) a `Money` nominal wrapper over minor units
  (`Long` cents/dollars) with a smart constructor; (ii) `BigDecimal` with a
  fixed scale and currency; (iii) `Long` whole-dollars. This is an ADR-018 /
  ADR-001 value-type choice and must be ruled together with the shape.

**ADR-032 constraint (binds all options).** Wherever cost lands, it rides the
**storage** blob (versioned/diffed/merged) but is **excluded from the domain /
simulation content hash** — a cost edit must not invalidate any simulation
cache. The `LeafSimContent` projection and the mitigation-content hashing must
skip the cost field.

**My recommendation: (4a) with a `Money` nominal wrapper over `Long` minor
units (option i).** The ADT names the two real shapes and gives compiler-checked
exhaustiveness for the NPV code; `Long` minor units avoid floating-point money
error and fit the existing `Long`/`Math.addExact` arithmetic discipline;
`BigDecimal` is heavier than a single-currency internal tool needs. Multi-currency
is out of scope unless the user says otherwise.

### Decision 5 — Where benefit is measured

**Goal.** Decide the tree node at which base-vs-mitigated is differenced, since a
mitigation on a leaf changes curves at that leaf and at every ancestor.

**Options.**
- **(5a) The nearest enclosing portfolio node, with-vs-without.** Measure at the
  first aggregating ancestor, where shared-tree aggregation has already combined
  the mitigated risk with its siblings — the dependence-aware level per
  `DEPENDENCE.md` (a). *Example:* mitigating one leaf, read the parent
  portfolio's curve with vs without the mitigation; the ΔTVaR there reflects how
  much of the *portfolio's* tail the control removes, which is the decision-relevant
  quantity.
- **(5b) At the mitigated leaf itself.** Simplest and most local. But a leaf's
  own curve ignores how its loss stacks with siblings in the same trials; a
  mitigation that looks large at the leaf can be a small share of the portfolio
  tail (and vice versa), so the leaf number can mislead the buy/don't-buy call.
- **(5c) At the tree root always.** One consistent node. But for a deep tree the
  root can dilute a real local benefit to near-invisibility, and it ignores that
  the relevant budget-holder usually owns a portfolio, not the whole tree.

**My recommendation: (5a).** The enclosing portfolio node is where aggregation
has already happened and where the money decision is actually owned; it matches
the with-vs-without approach the dependence note already endorses. Allowing the
user to also read the leaf and the root is cheap (same computation at a chosen
node) and can be a follow-on, but the *default* benefit node is the enclosing
portfolio.

---

## 7. Next steps

1. **User rules the five decisions in §6.** Until then this stays design-stage.
2. On ruling, **elevate to implementation grade**: exact signatures (cost type,
   `MitigationCost` codec, TVaR method, `LECNodeCurve` field, ROI assembly),
   full file inventory, ADR-032 hash-exclusion point, verification plan, and the
   first-increment vs M4-overlay split (§5). Present that for approval (G3).
3. Only then does any source edit become authorized (G1/G4 + hook token).

The scratch conception doc `docs/scratch/MITIGATION-ROI.md` and
`docs/scratch/DEPENDENCE.md` stay as the derivation source.
