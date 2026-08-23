 Marginals and dependence — how the literature and other tools handle it

> **Status (scratch note, kept deliberately).** The current tool does not model
> cross-risk dependence at all: leaves simulate independently, and aggregation is
> a per-trial sum over a shared tree. Given that, the sensible way to track a
> risk's influence today is the with-vs-without portfolio comparison in (a) — leave
> the risk out (or mitigate it) and read the portfolio node's curve across branches
> — not any explicit dependence construct.
>
> - **(a) is live and correct** for the case dependence matters most (shared-tree aggregation).
> - **(b) explicit dependence modelling** and **(c) alternative comparison metrics** are
>   marked **NEEDS FURTHER CONSIDERATION** — not scheduled, not designed.
> - **Revisit the applicability of dependence concepts, and the approach to them, once
>   the asset knowledge graph setup is in place.** The asset-graph domain (assets carry
>   risks; risks carry mitigations) is where dependence and cross-entity influence
>   become real modelling questions; until it exists, dependence is premature. This
>   ties to the asset-scope follow-ups parked in `MITIGATION-PRE-PLANNING`
>   (`has_unmitigated_risk`, D5) and TODO item 45 — the same future epic.
> - The return-period tables / delta ("A minus B") curves suggestion in (c) is
>   **PENDING a further look by the user** — deliberately NOT filed to the chart
>   backlog yet.

The problem has three established answers, and it's worth knowing our engine already embodies the first:

(a) Aggregate at the trial/event level, then compare aggregates. Catastrophe-modeling platforms (RMS, Verisk/AIR) keep per-event or per-year loss tables (ELTs/YLTs) and aggregate portfolios by summing losses within each simulated year — dependence enters through shared events, and any curve you draw from the aggregate already contains it. Our engine is architecturally this: trial-aligned sparse maps, portfolio aggregation by per-trial summation. The practical consequence: the dependence-aware way to compare two risks is not marginal-vs-marginal but with-vs-without at the portfolio level — compare the portfolio's curve including risk X against the portfolio without it (or with it mitigated). Our scenario branches make that comparison a first-class flow today: fork, modify/remove the risk, compare the portfolio node's curve across branches. So the tool already has the standard answer for the case where dependence matters most; the marginal overlay is the right tool for the simpler "which risk is bigger on its own" question. (Caveat for honesty: our leaves currently simulate independently, so cross-risk dependence beyond shared-tree aggregation isn't modelled yet — correlation/copula modelling would be future engine work.)

(b) Model dependence explicitly when combining marginals. **[NEEDS FURTHER CONSIDERATION — revisit once the asset knowledge graph exists.]** Insurance capital frameworks combine marginal distributions via correlation matrices (Solvency II standard formula) or copulas (internal models, Sklar's theorem). Relevant to us only if/when risks stop being independent — this is where a future mitigation/correlation feature would sit, not a charting question.

(c) Compare with different metrics than the whole curve. **[NEEDS FURTHER CONSIDERATION.]** Standard practice alongside curve overlays:

Return-period loss tables — the loss at 1-in-10 / 1-in-100 / 1-in-200 side by side per risk. This is just reading the curves at fixed probabilities; cheap to add as a table under the chart.
AAL (we already compute it) and prob-of-exceedance at a threshold (we already have the endpoint) — single-number comparisons.
TVaR / expected shortfall — the mean loss beyond a quantile; preferred in the literature over the quantile itself because it's subadditive (tail-focused, aggregation-friendly).
Difference ("delta") curves — plot A minus B across the loss axis; used for before/after mitigation views.
Stochastic dominance — the rigorous version of "this risk is smaller": if one exceedance curve lies entirely below the other, first-order dominance holds. Visually, this is exactly what the overlay shows; the literature just gives the crossing/non-crossing observation a name and a decision rule.
Tail-contribution allocation (co-TVaR / Euler allocation) — "how much of the group's tail does Op Risk contribute vs Brand Damage" — the fully dependence-aware comparison; meaningful for us once dependence modelling exists, since under independence contributions are close to what with/without comparison already shows.
None of this is Phase E scope. Return-period tables and delta curves are cheap chart-side candidates, but are **PENDING a further look by the user** — not filed to the chart backlog. The rest is background for when dependence modelling gets designed (i.e. after the asset knowledge graph exists).