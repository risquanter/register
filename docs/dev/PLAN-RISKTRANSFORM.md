# PLAN — RiskTransform (Mitigation): Knowledge Consolidation & Open Decisions

Status: **Draft — knowledge consolidation, nothing approved.** Created 2026-07-16.
Source material: `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` Part B (B.0–B.8, which
remains the historical record and scoring of the design space),
`docs/scratch/milestone-2b-cache-and-decisions.md` (DD-15 through DD-19),
ADR-001 (correct-by-construction), ADR-003 (provenance).
Purpose: a single pickup point for mitigation work. Every decision below follows
the decision-guide format: goal and context, options, recommendation (labelled).

---

## 1. Current state — verified facts (2026-07-16)

- `RiskTransform` is a `case class` wrapping a single function
  `run: RiskResult => RiskResult`, in
  `modules/common/.../domain/data/RiskTransform.scala` — a shared module, so it
  is public API for both server and frontend builds.
- Operations: `applyDeductible`, `capLosses`, `scaleLosses`, `insurancePolicy`,
  `filterBelowThreshold`. All work per-trial on the sparse
  `Map[TrialId, Loss]` inside `RiskResult`.
- `Identity[RiskTransform]` is lawful (ordered composition, `l` then `r`);
  property tests live in `RiskTransformSpec`.
- **Zero production callers.** No service, controller, or endpoint references
  the type (grep-verified 2026-07-16). Consequence: every fix below is a local,
  non-breaking edit today; each becomes a breaking change the moment a call
  path exists.
- No transform records provenance (ADR-003 gap).
- A transform cannot act on a portfolio result: `RiskResultGroup` is a sibling
  of `RiskResult` under `LossDistribution`, not a subtype.
- The pipeline stage is decided: B3, result-stage endomorphism (monoid plan
  B.5/B.6). Portfolio-stage mitigation (B4) was scored and not chosen; if it
  ever returns, it must be an operation applied after aggregation, outside the
  combine (it would otherwise break the associativity law).

## 2. Defects (monoid plan B.8) — fix before any production wiring

Not prerequisites of monoid Part A or milestone-2b Phase A; they gate only the
first production wiring of `RiskTransform`.

1–3. Three `require` guards throw `IllegalArgumentException` on raw primitives
(`RiskTransform.scala:154` scale factor ≥ 0; `:172` deductible ≥ 0; `:173`
cap > deductible). ADR-001 requires Iron-refined parameters and smart
constructors returning `Validation`; the cross-field rule belongs in
`Validation.validateWith`.

4. `given Equal[RiskTransform] = Equal.default` compares the wrapped lambda by
reference: `capLosses(1000) === capLosses(1000)` is `false`. Unlawful, unused,
and unexercised — the law suite compares `.outcomes`, not the instance.

**Shared root cause:** a transform is an opaque function, not data. It cannot
be compared, hashed, serialized, or logged. Every decision below runs into
this fact.

## 3. Constraints inherited from the locked cache decisions

- **DD-16/DD-18:** cache keys hash only simulation-relevant projections; cache
  values are identity-free content. A function can never enter a key or a
  value — only reified parameter data stored in the node's JSON can.
- **DD-15 → Option B:** portfolio results are not cached. The transform-versus-
  portfolio-cache interaction is therefore moot for now; the leaf path is the
  only cached path.
- **DD-19 (open):** provenance record shape. Decision D4 below is blocked on it.

## 4. Decisions

### D1 — Reify transforms as data (`TransformSpec`)?

**Decision goal and context.** Equality, caching, API exposure, and provenance
all fail on the same fact: a transform is an opaque function. The decision is
whether to introduce a data description of a transform — a sealed trait with
one case per operation, carrying Iron-refined parameters — plus an interpreter
that builds the executable `RiskTransform` from it. New type in a shared
module → decision trigger #4.

**Options.**

1. **Reify.** `sealed trait TransformSpec` (data, validated by smart
   constructors) + interpreter to `RiskTransform` (function). The spec is
   comparable, hashable, serializable; the function becomes internal machinery.
   Cost: a new shared type with codec and tests.
2. **Stay function-only.** Fix the B.8 defects in place (Iron parameters on the
   smart constructors, delete the `Equal` instance) and keep no data form.
   Cost: transforms remain unstorable and uncomparable; any later caching, API,
   or provenance work reopens this decision under breaking-change pressure.

**Recommendation (mine):** Option 1, but sequenced with the first real use
case rather than built speculatively now. Do the B.8 Iron fixes on the
constructors either way — they are independent of reification.

### D2 — The unlawful `Equal[RiskTransform]` instance

**Decision goal and context.** The instance is wrong today and consumed by
nothing; it waits in a shared module for a caller to trip over it.

**Options.**

1. **Delete it now.** Smallest correct state; nothing breaks (verified: no
   consumer). If D1 Option 1 later reifies transforms, reintroduce `Equal`
   derived from the spec, with a law-suite case that exercises it directly.
2. **Keep it until D1 resolves,** then derive from the spec. Cost: a known-
   unlawful instance stays public in `common` in the meantime.

**Recommendation (mine):** Option 1 — delete now, together with the B.8
`require` fixes; both are free while there are no callers.

### D3 — Caching policy for mitigated results (monoid B.7 decision 3)

**Decision goal and context.** When a transform is wired into the read path,
does the cache store the raw simulation result (transform applied on every
read) or the post-transform result (transform identity in the key)? Must be
decided before wiring; it also decides whether transform parameters enter the
`LeafSimContent` projection (DD-16).

**Options.**

1. **Cache raw; apply the transform at the resolver edge on every read.** The
   key excludes transform parameters (they do not affect the cached raw
   content). One cache entry serves any number of transform variants —
   comparing mitigation scenarios over the same risks costs zero extra
   simulations. Per-read cost: one linear pass over the outcomes map.
2. **Cache post-transform results.** The key must include the transform's
   reified spec (requires D1 Option 1): one entry per (content, spec) pair.
   Saves the per-read pass; multiplies entries per mitigation variant.

**Recommendation (mine):** Option 1. It matches the locked identity-at-the-
edge design (DD-16/DD-18: the cache stores what was simulated; everything
request-specific is attached at the edge), and the avoided work is a linear
map pass, not a simulation. Revisit only if measurement shows transform
application dominating read latency.

### D4 — Provenance of a transform application (monoid B.7 decision 5)

Blocked on DD-19 (provenance record shape). A transform application is
analytically meaningful and must be explainable (ADR-003), so whatever record
DD-19 produces needs a representation for "transform X with parameters Y was
applied". **Decide together with DD-19 — last, per the agreed sequencing.**

### D5 — Client-facing mitigation API (monoid B.7 decision 4)

If mitigation becomes a concept clients send and receive, that is an API-shape
decision (trigger #1) requiring its own ADR. It presupposes D1 Option 1 (only
data can cross the API boundary). Not before D1 is decided.

## 5. Sequencing

1. None of the above blocks monoid Part A or milestone-2b Phase A.
2. When mitigation work is picked up: B.8 `require` fixes + D2 (free now) →
   D1 with the first concrete use case → D3 before any wiring → D4 after
   DD-19 → D5 as its own ADR.
