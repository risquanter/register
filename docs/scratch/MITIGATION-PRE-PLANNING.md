# Mitigation — pre-planning notes (scratch)

Scratch pre-planning for the mitigation feature. **Not a plan.** Plan scope is still
open; these are essential parts to fold into the plan when it is written. The plan
home is `docs/dev/PLAN-RISKTRANSFORM.md` (§6 concept); design history is
`docs/dev/PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` Part B. Live open-question set is
tracked in `PLAN-RISKTRANSFORM.md` and the working session.

## Decisions (ruled)

- **Scope-resolution timing = HYBRID** (dynamic semantics; resolve the predicate per tree-version,
  memoized). **Provenance implication (explicit, part of the ruling):** the *resolved scope set*
  must be captured in provenance at simulation time — each LEC records which mitigations, resolved
  to which node ids, produced it — so a result stays reproducible and explainable even though scope
  is dynamic (a later tree edit that changes membership does not retro-alter a past result's record).
- **Cross-mitigation order = GLOBAL precedence key.** Each mitigation carries a precedence;
  application order = precedence, deterministic and merge-stable. Overlap detection (trace-monoid)
  surfaces the pairs that actually interact.
- **Multi-mitigation LEC view = per-mitigation TOGGLE.** Presented as a persistent **side panel**
  (chosen over a dropdown for traceability — try this first): a searchable checkbox list (substring
  filter on name) with a tri-state "select all", always-visible active selection, and toggle↔curve
  colour consistency. API sends raw + per-mitigation curves; toggles are client-side filters. (UX
  detail is a build-time refinement.)
- **Scope vs effect = TWO-TIER badges.** Directly-scoped (solid) vs affected-by-descendant (faint,
  tooltip). Provenance stores the scoped set; affected is derived (ancestors of scoped nodes).
- **Range expressiveness = (B)**, tree-associated representation, algebra framing — see
  `PLAN-RISKTRANSFORM.md` §6. The vql-engine work is delegated: agent prompt with the interface
  contract (AC-1…AC-10) in `../vague-quantifier-logic/PROMPT-VQL-RANGE-AND-TARGETING.md` (moved to
  the sibling repo); register's build plan designs against those acceptance criteria (notably the
  `satisfyingSet` API for targeting).
- **Build plan location = append as §7 of `PLAN-RISKTRANSFORM.md`** (one plan per workstream), folding
  in this scratch doc's content. §7 is drafted (2026-08-08) and awaiting review.
- **`DistributionTransform` semantics = uniform semantic op interpreted per representation**, and
  prefer **result-stage** for any severity mitigation expressible on outcomes (representation-free).
  Normalize-then-transform rejected (breaks the "output is a normal leaf of the same kind" promise).
  Metalog is strictly more expressive than the 2-parameter lognormal, so **metalog-only shape ops
  exist (e.g. reshape one quantile / the extreme tail alone) with no lognormal analog**; there are
  essentially no lognormal-only ops. Such metalog-only edits, when needed on a lognormal leaf, fall
  back to a **result-stage** transform (e.g. a tail cap), which works regardless of representation.
  Param-stage offers three op kinds: **Scale** (relative ×factor) and **Narrow** (relative, toward
  the median) — both broadcast across a heterogeneous target set — plus **Override** (absolute: the
  expert supplies the post-mitigation distribution and/or probability directly, capturing expert
  opinion of the mitigated state). Override is a *constant* transform: representation-agnostic (it
  replaces, so no metalog/lognormal mapping); **naturally single-node** (one absolute target cannot
  fit a heterogeneous set — a set-scoped override = per-node params = many single overrides);
  **absorbing under composition** (discards prior distribution edits on that node; precedence makes
  the winner deterministic); and it does **not** track later base re-estimates (frozen expert
  opinion — the intended semantics). Stays explicit/first-class: target params live in the mitigation
  entity with the base leaf preserved — not a silent node edit (distinct from a scenario-branch edit,
  which is a one-off divergent tree).
- **Override placement = baseline/final presets over the precedence key.** An `Override` is offered
  at either extreme of the global precedence order: **baseline** (min precedence, applied first —
  replaces the base, relative transforms compose on top; blends) or **final** (max precedence,
  applied last — asserts the mitigated state regardless of other edits; deliberately absorbing).
  Implemented as two type-precedence slots, no new transform kind. Override-vs-override still does
  not blend (higher precedence wins entirely) — correct, not a gap: two absolute expert statements
  cannot be meaningfully averaged.
- **Override staleness = four composed layers.** Overrides are **leaf-only** (param-stage). (1)
  **Base-version stamp** — the override stores the base params (hash/version) it was authored
  against; resolution compares against the current base and flags staleness on ANY edit path (form,
  merge, API PUT, time-travel restore). (2) **Edit popup** — the interactive front-end: when a form
  edit changes **simulation-relevant** params (DD-16 projection only; renames/reparents don't fire)
  of a leaf an override targets, prompt AFTER the edit saves (never gating the save): review /
  re-enter (prefilled with current override params + base old→new diff) / delete mitigation /
  **keep, review later**. (3) **Stale badge** — what "keep" and every non-interactive path degrade
  to; visible until re-affirmed (re-affirm re-stamps). (4) **Nonsense check** — always-on guard
  flagging an override that makes the node worse than its current base.
- **Provenance of a transform application (D4) = a separate mitigation-provenance layer per LEC**:
  `{ mitigationId, spec/params, resolvedScope: Set[NodeId], stage, precedence }`, stored beside the
  simulation provenance — NOT inside the content-addressed `NodeProvenance` (which stays identity-free
  per DD-19). Honors D-1's resolved-scope capture; result is self-describing.
- **Precedence assignment = type-based default order + explicit numeric key override/tiebreak.** The
  numeric key (not list position) is the stored source of truth (merge-stable); drag-reorder is a UI
  skin over it.

---

## P-1 — Restricted "targeting" predicate sublanguage

A mitigation's scope is a **targeting predicate** — a restricted VQL formula that selects
the set of nodes the mitigation applies to. Grammar (enforced, not just conventional):

- **Closed in `x`** — `x` (the node) is the only output variable. No answer/free grouping
  variables (grouping would yield a *family* of sets; a mitigation needs one set).
- **Constants allowed** (target a type or a named thing).
- **Bounded auxiliary quantifiers only** — `∃a:Mitigation`, `∃r:RiskType`; never over `x`'s
  own sort.
- **No mitigation-state predicates** (`mitigate`/`mitigated`) — self-reference/fixpoint: a
  mitigation must not target by mitigation state it itself changes.

**Expression approach — parse the narrow language, then a binding-phase check (standard
syntax/semantics split, NOT parse-a-superset-then-filter):**

A targeting predicate is a `Formula[FOL]` — no `Q[op]^{k/n}`, no answer variables — evaluated
over the node domain (reuse the typed `evalFormula` / satisfying-set path) as pure set
selection `{x | φ(x)}`.

1. **Syntax (parse time).** Parse with the engine's FOL **formula** parser (`FOLParser`), NOT
   `VagueQueryParser`. The formula grammar has no vague-quantifier or answer-variable
   production, so `Q[...]` and `(y)` fail at parse time by construction. Do not parse the full
   vague-query language and strip the quantifier.
2. **Semantics (binding phase — necessarily post-parse; no parser can do these).** Three
   checks, all non-context-free, added to the existing parse→bind pipeline where `QueryBinder`
   already lives:
   - **closed in `x`** — a free-variable property of the whole tree (`FOLUtil.fvFOL`); freeness
     depends on enclosing quantifiers, knowable only after parsing.
   - **bounded auxiliary quantifiers only** (∃ over a non-`x` sort) — depends on sorts, known
     only after typing (`QueryBinder`).
   - **mitigation-state predicate ban** — schema-aware symbol check (parser sees only
     identifiers).
   Reuse existing free-var analysis + sort unification; small.

Analytics VQL (user screening queries) stays fully expressive (answer variables/grouping,
mitigation predicates in range and scope). The restriction applies only to targeting.

## P-2 — Memoize `RiskTreeKnowledgeBase` by tree-version

`RiskTreeKnowledgeBase(tree, results)` is rebuilt **inline per query** in `QueryServiceLive`
with no memoization; it is a pure function of `(tree, results)` and O(N)+ to build
(`descendantsByName` is heavier on deep trees). Memoize it keyed on the tree version (Irmin
revision / content hash), rebuilding only on tree update. The mitigation scope-resolution
and the precomputed mitigation predicates (P-3) should ride the **same** memoized KB, so
adding mitigation is the moment to introduce KB memoization rather than pay three precomputes
per query.

## P-3 — Precompute nested existentials into unary predicates

Frequently-queried nested existentials in scope blow up: evaluating them scans auxiliary
domains per element. Precompute them once (at KB build) into a unary set-membership predicate.

**Illustrative domain:** a company asset/risk graph — ~8,000 servers (assets); each
`has_risk` to several of ~200 risk types; ~50 mitigation controls with
`has_mitigation(risk, control)`.

**Query (formal):**
```
Q[<=]^{1/3} x ( critical_asset(x),  ∃r. ( has_risk(x, r) ∧ ¬∃m. has_mitigation(r, m) ) )
```
**English:** "Of the critical assets, do at most a third have at least one risk with no
mitigation?"

**Cost:** per asset, `∃r` scans ~200 risks and inside it `¬∃m` scans ~50 controls →
O(assets × risks × controls) ≈ 8000 × 200 × 50 ≈ 80M predicate evaluations. Precomputing a
unary `has_unmitigated_risk(x)` at KB build collapses the scope to O(1) per asset →
O(assets) ≈ 8,000.

**Register-domain analogue:** `Q[>=]^{3/4} x ( leaf(x), ¬∃a. mitigate(x, a) )` — "are ≥3/4 of
leaves unmitigated?" — where a precomputed `unmitigated(x)` replaces the per-leaf mitigation
scan.

**Implement:** the KB dispatcher exposes such unary predicates backed by precomputed sets
(same pattern as `leafNames`/`portfolioNames`); ride P-2's memoized KB.

## P-6 — Binding vs resolution on tree change; scope vs propagated effect

**Binding vs resolution (two phases, different lifetimes).**
- **Binding** (type-check a targeting predicate) depends only on the KB **catalog** (sorts,
  predicate signatures), which is stable across ordinary tree edits → bind **once**; re-bind
  only on a **schema** change (new predicate/sort — code-level). Caveat: a predicate
  referencing a node by **name literal** depends on catalog constants, which change with the
  tree — a reason to prefer type/attribute/stable-id targeting over name literals.
- **Resolution** (evaluate the predicate → node **set**) depends on the tree's nodes and
  **recomputes per tree-version** — the memoized scope resolution (P-2). This IS the
  `Mit × Tree → Tree` action being a function of its `Tree` argument: re-resolving scopes is
  part of computing the action, so a tree change recomputes it (memoized per tree-version).
  Because mitigations live in tree content, the action is effectively `Tree → ResolvedTree`.

**Scope ≠ affected set (propagated effect).** A mitigation directly transforms its **scoped**
nodes; the **effect propagates** to all ancestors via aggregation — a portfolio's aggregate
reflects a descendant's mitigation even though no mitigation targets the portfolio (partial
mitigation; residual may still be high). UI/provenance must distinguish **directly in scope**
(badge) from **affected by a descendant's mitigation** (the node's with/without curve shows it).

**Associativity invariant (why aggregation stays lawful).** The combine **function** is a pure
per-trial sum with no mitigation logic. A mitigation transforms the values a combine
**consumes** (a leaf operand) or **produces** (a node's finished aggregate), never the
summation **step**. Applying a mitigation to an intermediate accumulator mid-fold (e.g. capping
the running total after each child) is non-linear and order-dependent — it breaks associativity
and is forbidden.

## Follow-ups (future / asset-scope — out of current scope)

Asset / knowledge-graph scope is not built now (see `PLAN-RISKTRANSFORM.md` §6 Scope). Notes
parked here for when it is drafted:

- **`has_unmitigated_risk(x)`** — a precomputed unary predicate for the asset-graph domain (assets
  carry risks, risks carry mitigations):
  `has_unmitigated_risk(x) ≝ ∃r. ( has_risk(x, r) ∧ ¬∃m. has_mitigation(r, m) )` — "asset `x` has at
  least one risk with no mitigation." Precompute it to collapse the nested `∃`/`¬∃` scan (§P-3).
  Current risk-tree analogue (leaves *are* risks): `unmitigated(x) ≝ leaf(x) ∧ ¬∃a. mitigate(x, a)`.
- **D5 — client-facing mitigation API** (`PLAN-RISKTRANSFORM.md` D5): only if mitigation crosses the
  wire; its own ADR.

## P-4 — Range expressiveness (B): typed-path change only

**Decided: (B).** Register rides only the **typed** backend. Extending the range from a single positive atom to
a full formula (`∧`/`¬`/`∃`) is a **typed-path** change:
- `TypedSemantics.collectRangeElements` already enumerates the domain per candidate; swap
  `evalAtom(query.range, …)` → `evalFormula(query.range, …)` (the formula evaluator already
  exists and backs scope).
- `BoundQuery.range: BoundAtom` → `BoundFormula`; `ParsedQuery.range: FOL` → `Formula[FOL]`;
  parser production for a range formula.
- `QueryBinder`: reuse scope formula binding; add **sort-unification** for the quantified
  variable when it appears across several range atoms.
- Negation is closed-world by construction (evaluation enumerates the finite active domain).

The untyped `RangeExtractor.buildPattern` atom-only limit is **irrelevant to register** (register
does not use the untyped backend). This is a sibling-repo (vql-engine) change.

## P-5 — Untyped backend is candidate dead code (sibling)

The vql-engine's untyped evaluation backend (`VagueSemantics.holds`/`evaluate`,
`RangeExtractor`/`buildPattern`, `KnowledgeSource`/`DomainExtraction`) has **no production
consumer** — register uses only `evaluateTyped`; the untyped path is live only in the engine's
own demos and tests. Recorded in the sibling repo as `docs/TODOS.md` **T-006** (evaluate
retiring it).
