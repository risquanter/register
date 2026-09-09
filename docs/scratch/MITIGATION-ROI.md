# Mitigation ROI — conception note

> **Status (scratch note).** Exploration only, no code, no plan commitment. Captures
> a from-first-principles conception of how the tool could express return on
> investment (ROI) for a mitigation, given the Loss Exceedance Curves (LECs) the
> engine already produces and a cost model of {yearly fee} or {upfront implementation
> fee + yearly maintenance fee}. When this graduates, it appends to
> `PLAN-RISKTRANSFORM.md` (one plan per workstream); it does not become its own plan.
>
> Ties to: `DEPENDENCE.md` (with-vs-without at the portfolio node; TVaR; tail
> allocation), `LEC-TAIL-TRIMMING.md` (0.5% render trim is visualization-only, full
> sample retained), `MITIGATION-PRE-PLANNING.md` (targeting predicate → node set).

---

## 1. The question

A mitigation costs money and reduces loss. We want to tell the user whether it is
worth it, and by how much, using what the engine already computes. Two cost shapes to
support: a flat yearly fee, or an upfront implementation fee plus a yearly maintenance
fee. The benefit side must come from the simulated loss distributions, not a
hand-entered "expected savings".

---

## 2. What register already computes (validated against the code)

Grounding first, because the whole conception rests on what is already there.

- **Trial-aligned loss data is retained.** `TrialOutcomes` = `nTrials` + a sparse
  `Map[TrialId, Loss]` (zero-loss trials omitted). Portfolio aggregation is a
  per-trial pointwise sum over the tree (`combine`, with `Math.addExact`). This is
  the standard catastrophe-modelling structure (per-year loss tables, summed within
  each simulated year) — see `DEPENDENCE.md` (a). It means we can align a base run and
  a mitigated run **trial by trial** and take differences; the information is present.
- **AAL is computed.** `LECGenerator.averageAnnualLoss` = the mean of the
  unconditional loss distribution (zero-loss trials included). This is the ALE
  (Annualized Loss Expectancy) that ROSI needs.
- **VaR quantiles are computed.** `calculateQuantiles` gives p90/p95/p99/p99.5 of the
  unconditional loss (`unconditionalQuantile`). `probabilityOfNoLoss` and the
  exceedance-probability endpoint exist.
- **The render curve is trimmed at 0.5% exceedance for display only** (1-in-200,
  Solvency II convention); the full trial sample is preserved (`LEC-TAIL-TRIMMING.md`).
- **Missing for ROI:** (i) a **cost field** on `Mitigation` (today `Mitigation` has
  id/name/target/spec/precedence and no cost); (ii) a **TVaR** computation (nothing
  computes mean-loss-beyond-a-quantile today).

### Glossary (each term defined where a decision below first needs it)

- **LEC — Loss Exceedance Curve.** For each loss level `x`, the probability that the
  year's loss is at least `x`: `P(Loss ≥ x)`. Reading it top-to-bottom answers "how
  likely is a loss this big or bigger."
- **AAL — Average Annual Loss** (same number as **ALE**, Annualized Loss Expectancy).
  The mean of the loss distribution over all trials, including the many trials with
  zero loss. It is the single "expected yearly cost of this risk" number.
- **Key identity.** `E[Loss] = ∫₀^∞ P(Loss > x) dx` = **the area under the LEC**. So
  the drop in AAL from a mitigation is exactly the **area between the base LEC and the
  mitigated LEC**. Benefit-as-a-number and benefit-as-a-curve are the same object.
- **ROSI — Return on Security Investment.** `((ALE_before − ALE_after) − Cost) / Cost`.
  The textbook one-year point estimate. We treat it as the *starting* point, not the
  deliverable — sections 4–7 say why.
- **VaR — Value at Risk** at level `q`: the loss quantile, e.g. the p99 loss is the
  level exceeded in 1% of years.
- **TVaR — Tail Value at Risk** (a.k.a. expected shortfall): the **mean** loss in the
  years beyond that quantile — the average of the worst `1−q` fraction of trials.
  Preferred over VaR in the literature because it is subadditive (aggregation-friendly)
  and uses the whole tail sample, not one fragile quantile point (`DEPENDENCE.md` (c)).

---

## 3. The three things a textbook ROSI point-estimate leaves out

ROSI as a single number `((ALE_before − ALE_after) − Cost)/Cost` is a fine anchor and
wrong to stop at. Three gaps, each of which the retained trial data lets us close.

1. **Time. One year is the wrong horizon for the {upfront + maintenance} cost shape.**
   A mitigation with a big install fee and small yearly benefit can look terrible in
   year 1 and excellent over five years. The right object is a **multi-year cash flow**
   — `NPV` (Net Present Value: the sum of yearly {benefit − cost}, optionally
   discounted; see §7) and `IRR` (Internal Rate of Return: the discount rate at which
   NPV hits zero — "what return does this spend earn"). ROSI is the one-year slice of
   this.

2. **Distribution. The benefit is not a number, it is a distribution.** The engine has
   a saving on every trial, not just an average. Reporting only the mean throws away
   the shape — and for rare-severe risks the shape is the whole story (§4, §6).

3. **Which benefit metric. "Reduced loss" is a choice of functional, not a given.**
   The benefit can be measured as the drop in the mean (ΔAAL), the drop in a tail
   quantile (ΔVaR), or the drop in the tail average (ΔTVaR). These rank mitigations
   **differently**, and for a solvency-style decision the tail metric is the right one
   (§5). This is the decision the tool must let the user make, not bury.

---

## 4. The saving distribution, and the honest limit of "probability it pays off"

This section is the corrected teaching lesson. It replaces an earlier draft that
over-claimed a "probability the mitigation pays off in a year" as a headline number.

**Trials and savings.** A **trial** is one simulated year. Run the same 10,000 trials
twice — once base, once mitigated — and each trial `i` has a base loss `xᵢ` and a
mitigated loss `x′ᵢ`. The **saving** is `sᵢ = xᵢ − x′ᵢ`: what the mitigation saved that
particular year. Quiet years save 0; years where the risk fired save something
positive. The full set of 10,000 savings is the **saving distribution**.

**The mean saving is the benefit input.** `E[s]` = (sum of all savings)/10,000 = the
drop in AAL (ΔAAL), and by the §2 identity it is the area between the two LECs. This is
the yearly benefit `NPV` consumes.

**Why "probability it pays off in a year" is a weak headline — the precise reason.**
Let `p` be the risk's firing probability (a value the user typed in) and `a` the
mitigation's annualized cost. Then:

```
P(pays off) = P(s ≥ a) = P(fires) · P(s ≥ a | fires) = p · c
```

where `c = P(saving ≥ cost, given the risk fired)`. `P(pays off)` is always `p · c`
with `c ∈ (0, 1]` — it is **bounded by and anchored to the input frequency `p`**. That
is why it can read as a tautology: the leading term is your own input.

**The nuance — where it does and does not detach from `p`.** The dividing line is
*not* "fixed loss vs. variable loss". It is **where the annual cost `a` sits relative to
the spread of per-occurrence savings**:

- If `a` is smaller than almost every firing's saving, `c ≈ 1`, so `P(pays off) ≈ p`.
  Reading it back tells you nothing you didn't type in. A **fixed** severity is just
  the degenerate case: the saving distribution is a single point, so `c` is exactly 1
  or 0.
- `c` is meaningfully below 1 **only when `a` cuts through the middle of the saving
  spread** — a real share of firings are too small for their saving to cover the year's
  cost. Only then does `P(pays off)` genuinely detach from `p`, and only then is it
  engine-decided information.

*Worked instance.* A risk fires 5% of years. When it fires, the loss is \$5M half the
time and \$80M half the time; the mitigation halves the loss (saves \$2.5M or \$40M).

- Annual cost `a` = \$1M (below both savings): `c = 1`, `P(pays off) = 0.05 × 1 = 5%` —
  identical to the input frequency. Tautological.
- Annual cost `a` = \$3M (between \$2.5M and \$40M): the \$5M firings save \$2.5M and
  do **not** cover the cost; only the \$80M firings do. `c = 0.5`,
  `P(pays off) = 0.05 × 0.5 = 2.5%` — now detached from `p`, now informative.

**Takeaway.** The number the simulation actually decides is the conditional
`c = P(saving ≥ cost | fired)` — "when this risk does hit, how often is the mitigation
actually earning its cost rather than firing on a too-small event." Report **that**, not
the frequency-blended `P(pays off)`, and only where the cost regime makes it non-trivial.
The load-bearing deliverables are the ones anchored to no single input frequency: the
**magnitude** of savings (`E[s]`, NPV; §6) and the **portfolio tail contribution** (§5).

---

## 5. The aggregate that actually matters: ΔTVaR at the portfolio node

This is where trial-aligned aggregation earns its keep, and where the number is a joint
property of many risks that no single input frequency reveals.

**Setup.** Portfolio of two independent leaves, 10,000 trials:

| Leaf | Fires | Loss when it fires | AAL |
|---|---|---|---|
| A "breach" | 5% (≈500 trials) | \$60M | \$3M |
| B "fraud"  | 40% (≈4,000 trials) | \$5M | \$2M |

Two candidate mitigations, **deliberately tuned to the same ΔAAL = \$2M/yr**:

- **M_A** on A: cut \$60M → \$20M. Saving per breach = \$40M. ΔAAL = 0.05 × 40 = \$2M.
- **M_B** on B: eliminate B. ΔAAL = 0.40 × 5 = \$2M.

By the mean-benefit (ΔAAL) metric these are **identical** — same \$2M/yr, same NPV
input. Now read the **tail**.

**The tail.** Rank all 10,000 trials by total portfolio loss; take the worst 1% (worst
100). Those worst trials are dominated by breach years (A contributes ≥ \$60M; B at most
\$5M). So `TVaR99` (mean loss of the worst 100) ≈ \$62M.

- **M_A** removes \$40M from **every** one of those worst trials → `TVaR99` ≈ \$22M.
  **ΔTVaR ≈ \$40M.**
- **M_B** removes \$5M only where B is present in the worst trials (~40% of them) →
  `TVaR99` ≈ \$60M. **ΔTVaR ≈ \$2M.**

**Same ΔAAL (\$2M), ΔTVaR different by 20×** (\$40M vs \$2M). The mitigation that
protects the portfolio's solvency is obviously M_A — and **nothing in the AAL or the
input frequencies says so.** Only aggregating the joint per-trial losses and reading the
tail does. Note the reframing this forces: `P(M_A pays off in a year)` is still ≈5% (its
frequency, the trivial number from §4) — but that is plainly the wrong question here; the
\$40M tail reduction is the number worth computing, and it is a pure product of
aggregation. This is the concrete, non-circular payoff of the architecture, and it is the
`co-TVaR` / tail-contribution idea parked in `DEPENDENCE.md` (c).

**Where to measure.** At the **nearest enclosing portfolio node**, with-vs-without the
mitigation, exactly as `DEPENDENCE.md` (a) prescribes — because the tail is a portfolio
property, and register's scenario branches already make with-vs-without a first-class
flow.

---

## 6. Benefit as a curve: the with-vs-without portfolio LEC gap

The mean is one number off a distribution the tool can show in full. Because savings
form a distribution, so does the benefit, and it has the same exceedance-curve shape the
product already draws for losses.

The honest, aggregate version is **not** a single leaf's two-point saving curve — it is
the **portfolio LEC with the mitigation overlaid on the portfolio LEC without it**. The
**gap between the two curves is the benefit at every probability level**:

- at the body of the curve, the vertical/area gap integrates to ΔAAL (§2 identity);
- at the tail (beyond p99), the gap is ΔTVaR — the solvency protection from §5;
- the return-period reading ("loss at 1-in-100 before vs after") falls straight out.

This is the deliverable to foreground: one overlay chart, base vs mitigated portfolio
curve, with the AAL gap and the tail gap called out. It reuses the existing chart and
the existing scenario-branch comparison; it needs no new curve type. (Return-period
tables and delta/"A minus B" curves are the `DEPENDENCE.md` (c) backlog candidates,
still pending the user's look — not filed.)

---

## 7. Cost model and discounting

**Cost shapes.** Flat yearly fee `f`; or upfront `C₀` + yearly maintenance `m`. Both
reduce to a per-year cost stream: `{C₀ at t=0, then m each year}` or `{f each year}`.
Net yearly cash flow = `benefit_year − cost_year`; sum over the horizon = the multi-year
result.

**What "discounting" means, since it was queried.** Discounting converts future money to
its present value with a rate `r`: `PV = FV / (1+r)^t`. The rate is **not primarily
inflation** — it is mostly the **cost of capital / opportunity cost** (the return the
money would earn elsewhere), typically a firm's WACC (Weighted Average Cost of Capital,
often ~5–12%), with inflation and risk folded in. Worked: a \$2M benefit five years out
at `r = 8%` is worth `2 / 1.08^5 ≈ \$1.36M` today — about a third less. Over a 3–5 year
mitigation horizon it is a real but rarely decision-flipping haircut on a strongly
positive NPV.

**Does the tool need it?** The tool's differentiator is the **distributional benefit**
(§4–§6), not the discounting arithmetic — any spreadsheet does the `1/(1+r)^t` part, and
the rate is a finance assumption the tool has no authority to set. Your instinct is
right: don't bake in an opinionated rate.

*Decision (D3 below): how to handle the discount rate.*
- **(a) No discounting.** Report the undiscounted multi-year net (plain sum of yearly
  {benefit − cost}). Simplest; slightly overstates long-horizon value.
- **(b) Pluggable rate, default 0 — my recommendation.** A single optional `r` the CFO
  sets; default 0 reproduces (a) exactly, any positive `r` gives the discounted NPV/IRR.
  Zero opinion baked in, full control handed to the person who owns the assumption.
- **(c) Opinionated default (e.g. 8%).** Presents a "proper" NPV out of the box but
  asserts a finance assumption the tool shouldn't own; users will argue with the default.

---

## 8. What the tool would need (conception, not a build list)

- A **cost field** on `Mitigation` (either `yearlyFee` or `{upfrontFee, yearlyMaintenance}`),
  versioned/diffed/merged with the tree like the rest of the mitigation entity.
- A **TVaR** computation in `LECGenerator` (mean loss beyond a chosen quantile) — the
  one genuinely missing simulation output; VaR quantiles and AAL are already there.
- A **benefit functional selector** (ΔAAL default, ΔVaR, ΔTVaR) — a read-side choice
  over already-simulated data, no re-simulation.
- Everything else (trial-aligned differencing, portfolio aggregation, the overlay chart,
  with-vs-without scenario branches) **already exists**.

---

## 9. Open decisions (numbered)

1. **Benefit metric default and options.** Default ΔAAL, offer ΔVaR and ΔTVaR as
   selectable bases? *Leaning:* yes — ΔAAL default (matches the AAL already shown),
   ΔTVaR available and preferred for solvency framing (subadditive, uses the whole tail;
   VaR is not subadditive and rests on a single fragile quantile). Needs TVaR (§8).
2. **Single-number vs distributional presentation.** Headline the multi-year net /
   NPV plus the with-vs-without portfolio overlay (§6), and **drop** `P(pays off in a
   year)` as a headline (§4). Optionally expose the conditional `c` only where the cost
   regime makes it non-trivial. *Leaning:* as stated.
3. **Discount rate handling.** §7 — pluggable rate, default 0 (my recommendation) vs no
   discounting vs opinionated default.
4. **Cost field shape on `Mitigation`.** One field supporting both shapes
   ({yearlyFee} and {upfront + maintenance}) vs. two explicit variants. *Leaning:* a
   small sealed `MitigationCost` ADT with `Flat(yearly)` and `UpfrontPlusMaintenance(
   upfront, yearly)` variants — explicit, exhaustive-matched, correct-by-construction.
5. **Where the benefit is measured.** Nearest enclosing portfolio node, with-vs-without
   (§5, `DEPENDENCE.md` (a)). *Leaning:* yes; this is the only dependence-aware choice
   and it reuses scenario branches. Single-leaf ROI is available but is the frequency-
   dominated, less informative view (§4).

---

## 10. Query evaluation regimes (context: why the ROI reads are cheap to serve)

> **Recorded understanding at the time of writing — not a description of shipped
> behaviour.** A later task revisiting this must re-verify against **both** codebases —
> register (the query consumer and the simulation cache) and the query engine library —
> to see what was actually built and what further insight was gained on the way. Treat
> the claims here as the state of understanding when the note was written, superseded by
> whatever the two codebases actually show.

Register runs a screening query language over the live risk tree. A query can call
functions that read a simulated result — for example `p95(node)`, `p99(node)`,
`lec(node)` return quantiles or the exceedance curve of a node's loss distribution.
Producing that loss distribution is the expensive step: it is the Monte Carlo
simulation, cached. Reading a quantile off an already-produced distribution is cheap.

**The constraint that frames the regimes.** The query evaluation walk is pure and
synchronous — it computes values, it does not run simulations. It cannot pause
mid-walk to launch a Monte Carlo run and wait for the answer. So every simulation a
query needs must already be in the cache before the walk reads it. The whole question
is: **can we always know, before the walk starts, exactly which simulations it will
need?**

Call the identity of one needed simulation the **result key**: the pair
`(node, mitigation selection)` that selects which distribution a function call reads —
the tree node it is asked about, and which mitigations are applied (none, or a specific
set). Two regimes follow.

- **Regime 1 — the set of result keys is fixed before evaluation.** Every key a query
  will read is determined by the query text plus the fixed tree and mitigation domains,
  with no dependence on any value the walk computes. Register can then list that exact
  set up front, run those simulations through its own effect system, populate the
  cache, and hand the pure walk a fully-populated model to read from. No feedback, no
  change to the query engine.

- **Regime 2 — some result key depends on a value the evaluation itself produces.** The
  walk computes an intermediate result, and that result decides which simulation to run
  next. The demand set cannot be enumerated in advance, because part of it is not known
  until earlier values exist. A pure pre-population pass cannot serve this on its own.

**Why register's queries — including every ROI read conceived above — are Regime 1.**
Three structural facts, each independently sufficient:

1. **Result-reading functions take their key from fixed domains, never from a computed
   value.** `p95`/`p99`/`lec` take a node drawn from the tree's pre-materialized node
   set, and a mitigation selection fixed by the query text or mode. Neither argument is
   ever a value the walk produced.

2. **Mitigation scope is resolved by a structural pre-pass that cannot read a result.**
   Which nodes a mitigation applies to is decided by a targeting sublanguage that
   forbids calling result-reading functions (and forbids quantifiers and function
   application generally). So a mitigation's scope — and therefore the mitigated result
   keys — can never depend on a p95 or an LEC. The selection side of every key is
   structural.

3. **Loss and probability are leaf value types with no path back into a key.** A query
   can read a loss or a probability out of a distribution, but the language has no
   symbol that turns a loss back into a node identity or a mitigation selection. A
   number read out cannot become the thing that picks the next simulation.

Together these guarantee the set of `(node, selection)` keys a query references is
closed under the query text and the fixed domains. Register enumerates it **exactly** —
not an over-approximation — pre-runs those simulations, and the pure walk only reads.

**The ROI functionals are all Regime 1 reads.** Everything §2–§8 proposes computes on
already-produced distributions at fixed keys: the base result is `(node, no
mitigation)`, the mitigated result is `(node, the given mitigation)`. ΔAAL, ΔVaR,
ΔTVaR, the saving distribution and its conditional `c = P(saving ≥ cost | fired)` (§4),
NPV/IRR (§7), and the with-vs-without portfolio LEC overlay (§6) are all read-side
arithmetic over those two fixed distributions. None of them computes a value that then
selects a different simulation. Even ranking a fixed list of candidate mitigations by
ROI stays Regime 1: enumerate every candidate's keys, simulate them, then sort — the
sort is pure post-processing, not a new simulation demand.

**Current stance (at time of writing).** We have settled, for the time being, on the
Regime 1 arrangement: register computes the demand set and pre-populates the cache on
its own side, and the query engine stays a pure synchronous evaluator. The only thing
that would move ROI — or any register query — into Regime 2 is a **solver**, an
evaluation whose next simulation is chosen from the results of earlier ones, and the
only solver candidate raised so far is a **ROI solver** (§11). No such solver is
planned or specified; until one is, Regime 1 covers all conceived work.

---

## 11. Follow-up: what a ROI solver would be, and why it is the Regime 2 trigger

> **Follow-up discussion, not a proposal.** Nothing here is planned or specified. It
> records the one shape of ROI work that would break the Regime 1 arrangement, so a
> future task recognizes it on sight. As in §10, this is the understanding at the time
> of writing; re-verify both codebases before relying on it.

Everything above **evaluates a given mitigation** (or compares with-vs-without at a
node). A **solver** does the opposite: it **searches for which mitigations to pick**.
That difference is exactly what separates the two regimes.

**The criterion for Regime 2, stated precisely.** A workload is Regime 2 when the
choice of the next simulation to run is computed from the results of simulations
already run — a feedback loop from produced values back into the demand set. Searching
a space by pruning it with results is the canonical case.

**The candidate problem.** "Given a budget, which combination of the available
mitigations most reduces the portfolio's tail loss?" — for example, of twelve available
mitigations, find the subset whose total cost is within \$500k that minimizes the
portfolio's 1-in-200 TVaR (§5).

**Where it splits from Regime 1.** The set of candidate mitigation combinations is the
powerset — `2ⁿ` subsets for `n` mitigations. Two ways to attack it:

- **Exhaustive scoring — still Regime 1.** Simulate every subset's residual portfolio
  distribution, compute each one's ΔTVaR and cost, keep the best affordable one. The
  demand set is the whole powerset: large, but fixed up front and enumerable, so the
  pre-population pass serves it. Regime 1, just expensive (`2ⁿ` simulations).

- **Result-guided search — Regime 2.** Prune using results instead of scoring
  everything. Greedy: simulate each single mitigation, read each one's marginal ΔTVaR
  per dollar, add the best, re-simulate the portfolio with it applied, then choose the
  next addition from *that* residual — repeat until the budget is spent. Or
  branch-and-bound: use a simulated bound to discard whole branches of the powerset
  unsimulated. In both, **the next result key — which combination to simulate next — is
  a function of tail values the search already produced.** The demand set is not known
  in advance; it unfolds as results arrive. This is the feedback loop a pure
  pre-population pass cannot serve on its own.

**Why a solver is attractive despite the cost.** Exhaustive scoring is `2ⁿ`
simulations; a result-guided search visits a small fraction of the powerset to reach a
near-optimal set, which is the only tractable route once `n` is more than a handful.
That tractability is the whole reason a solver would ever be built — and it is exactly
the pruning that makes it Regime 2.

**Recorded conclusion.** No ROI solver is planned or specified anywhere. ROI as
conceived in §1–§9 is evaluation of given mitigations, fully Regime 1. If a
budget-constrained mitigation-selection solver ever becomes real work, that is the
point at which the Regime 1 arrangement no longer suffices and the engine's evaluation
model has to be revisited — and a task reaching that point must re-verify both
codebases (see the note at §10) before assuming anything recorded here still holds.
