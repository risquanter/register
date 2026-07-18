# PLAN — RiskTransform (Mitigation): Knowledge Consolidation & Open Decisions

Status: **Partially executed.** Created 2026-07-16. On 2026-07-17 the user decided
and the following was implemented: B.8 defect fixes, D2 Option 1 (delete `Equal`),
D6 Option 1 (retarget to `TrialOutcomes`); D3 was decided (Option 1, cache raw)
as policy — no code exists to wire it yet. D1 is decided (stratified
`TransformSpec` + `TransformPipeline` design locked; build deferred to the first
consumer). D4 (unblocked 2026-07-18 by DD-19's closure; decide with D1's
build or the first mitigation wiring) and D5 (after D1's build) remain open.
Source material: `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` Part B (B.0–B.8, which
remains the historical record and scoring of the design space),
`docs/scratch/milestone-2b-cache-and-decisions.md` (DD-15 through DD-19),
ADR-001 (correct-by-construction), ADR-003 (provenance).
Purpose: a single pickup point for mitigation work. Every decision below follows
the decision-guide format: goal and context, options, recommendation (labelled).

---

## 1. Current state — verified facts (updated 2026-07-17)

- `RiskTransform` is a `case class` wrapping a single function
  `run: TrialOutcomes => TrialOutcomes` (retargeted 2026-07-17, decision D6), in
  `modules/common/.../domain/data/RiskTransform.scala` — a shared module, so it
  is public API for both server and frontend builds.
- Operations: `applyDeductible`, `capLosses`, `scaleLosses`, `insurancePolicy`,
  `filterBelowThreshold`. All work per-trial on the sparse
  `Map[TrialId, Loss]` inside `TrialOutcomes`.
- Constructor parameters are Iron-refined (`NonNegativeLong` /
  `NonNegativeDouble`); the four single-parameter constructors are total.
  `insurancePolicy` returns `Validation` for the cross-field rule
  `cap > deductible` (B.8 fixes, implemented 2026-07-17).
- `Identity[RiskTransform]` is lawful (ordered composition, `l` then `r`);
  property tests live in `RiskTransformSpec`, including a property that every
  transform preserves `nTrials` (required by the `TrialOutcomes.combine`
  alignment invariant).
- **Zero production callers.** No service, controller, or endpoint references
  the type (grep-verified 2026-07-16 and 2026-07-17). Consequence: the fixes
  above were local, non-breaking edits; anything further becomes a breaking
  change the moment a call path exists.
- No transform records provenance (ADR-003 gap) — see D4.
- A transform applies to any node's result, leaf or portfolio, by acting on
  its `trialOutcomes` field (since D6; before that it accepted only
  `RiskResult`, so portfolio results were out of reach).
- The pipeline stage is decided: B3, result-stage endomorphism (monoid plan
  B.5/B.6). Portfolio-stage mitigation (B4) was scored and not chosen; if it
  ever returns, it must be an operation applied after aggregation, outside the
  combine (it would otherwise break the associativity law).

## 2. Defects (monoid plan B.8) — ✅ FIXED 2026-07-17

Fixed before any production wiring existed, as required. What was done:

1–3. The three `require` guards (scale factor ≥ 0, deductible ≥ 0,
cap > deductible) are gone. Single-field rules moved into Iron-refined
parameter types (`NonNegativeLong`, new alias `NonNegativeDouble` with
`ValidationUtil.refineNonNegativeDouble`), making `applyDeductible`,
`capLosses`, `scaleLosses`, and `filterBelowThreshold` total. The cross-field
rule lives in `insurancePolicy`, which returns
`Validation[ValidationError, RiskTransform]`. `capLosses` and
`filterBelowThreshold` gained the non-negativity constraint they previously
lacked (user-approved narrowing).

4. `given Equal[RiskTransform] = Equal.default` deleted (decision D2 below).

Also removed: `RiskResult.withOutcomes`, whose only callers were the transform
constructors (user-approved; zero call sites after D6).

**Shared root cause (still true for the remaining decisions):** a transform is
an opaque function, not data. It cannot be compared, hashed, serialized, or
logged. D1, D4, and D5 all run into this fact.

## 3. Constraints inherited from the locked cache decisions

- **DD-16/DD-18:** cache keys hash only simulation-relevant projections; cache
  values are identity-free content. A function can never enter a key or a
  value — only reified parameter data stored in the node's JSON can.
- **DD-15 → Option B:** portfolio results are not cached. The transform-versus-
  portfolio-cache interaction is therefore moot for now; the leaf path is the
  only cached path.
- **DD-19 (closed 2026-07-18 → (c)+(d) + A′):** `riskId` deleted; `NodeProvenance`
  becomes the content-only record; provenance lives on `RiskResult` only,
  attribution is structural. D4 below is thereby unblocked (itself still open).

## 4. Decisions

### D1 — Reify transforms as data (`TransformSpec`)? — ✅ DECIDED (2026-07-17): stratified design locked, build deferred

**Decision (user, 2026-07-17):** Reify, with the **stratified** design — atomic
operations plus a flat pipeline, no recursion:

```scala
sealed trait TransformSpec  // pure data: comparable, hashable, serializable
object TransformSpec {
  final case class ApplyDeductible(deductible: NonNegativeLong)       extends TransformSpec
  final case class CapLosses(cap: NonNegativeLong)                    extends TransformSpec
  final case class ScaleLosses(factor: NonNegativeDouble)             extends TransformSpec
  final case class FilterBelowThreshold(threshold: NonNegativeLong)   extends TransformSpec
  final case class InsurancePolicy private (deductible: NonNegativeLong, cap: NonNegativeLong)
      extends TransformSpec
  object InsurancePolicy {   // cross-field rule cap > deductible (ADR-001)
    def create(deductible: NonNegativeLong, cap: NonNegativeLong)
        : Validation[ValidationError, InsurancePolicy]
  }

  def toTransform(spec: TransformSpec): RiskTransform   // single exhaustive match
  given Equal[TransformSpec] = Equal.default            // lawful: structural equality on scalar data
  given JsonCodec[TransformSpec]                        // discriminated; per-case Raw + mapOrFail
                                                        // (DistributionParams precedent, Provenance.scala)
}

final case class TransformPipeline(steps: List[TransformSpec])
object TransformPipeline {
  val empty: TransformPipeline = TransformPipeline(Nil)
  given Identity[TransformPipeline]   // list concatenation; empty = identity;
                                      // associative, deliberately NOT commutative (order matters)
  def toTransform(p: TransformPipeline): RiskTransform =
    p.steps.foldLeft(RiskTransform.identityTransform)((acc, s) => acc.andThen(TransformSpec.toTransform(s)))
  // law to test: toTransform(a <> b) behaves as toTransform(a) andThen toTransform(b)
}
```

Design properties the decision rests on: the pipeline is an ordered list —
position is application order; interpretation folds front-to-back with
`andThen`; combining pipelines is list concatenation (appends, never reorders);
equality means same operations in the same order. Flattening is safe because
composition is associative; order is never touched. The rejected recursive
alternative (`Sequence` as a case of the trait) gave the same ordered sequence
multiple representations, required special recursive serialization
(trigger #7), and departed from the repo's flatten-recursion storage strategy
(`RiskPortfolio.childIds`, `RiskTreeJson`). Full ADR-by-ADR constraint sweep:
conversation record 2026-07-17; every accepted ADR is satisfied without
exception or workaround.

**Build is deferred to the first consumer** (D5 endpoint, D4 provenance
wiring, or other concrete mitigation use case). Reason: `RiskTransform` has
zero production callers, so building now would add public shared-module API
with zero call sites — a MUST-FIX dead-code state under the
code-quality-review checklist (§4). Implementation starts from the sketch
above.

Original decision write-up kept below for the record.

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

### D2 — The unlawful `Equal[RiskTransform]` instance — ✅ DECIDED & DONE (Option 1, 2026-07-17)

**Decision goal and context.** The instance was wrong and consumed by
nothing; it waited in a shared module for a caller to trip over it. A lawful
`Equal` cannot be written for a bare function (function equality is
undecidable; reference comparison and sample-based comparison are both
incorrect), so the only real fix is deriving `Equal` from reified data —
which is D1.

**Decision (user, 2026-07-17): Option 1 — deleted.** If D1 Option 1 later
reifies transforms, reintroduce `Equal` derived from the spec, with a
law-suite case that exercises it directly. Option 2 (keep until D1 resolves)
was rejected: it left a known-unlawful instance public in `common` for no
benefit.

### D3 — Caching policy for mitigated results (monoid B.7 decision 3) — ✅ DECIDED (Option 1, 2026-07-17)

**Decision (user, 2026-07-17): Option 1 — cache raw simulation results; apply
the transform at the resolver edge on every read.** Consequences: transform
parameters never enter the `LeafSimContent` cache-key projection (DD-16), and
milestone-2b Phase A can design the cache key without any transform fields.
No code exists to wire yet; this is policy, recorded for Phase A and for the
first mitigation wiring. Original decision text kept below for the record.

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

**Unblocked 2026-07-18:** DD-19 closed → (c)+(d) + A′ (identity-free
content record; provenance leaf-only; structural attribution). D4 itself
remains open — decide with D1's build or the first mitigation wiring.
Original note: Blocked on DD-19 (provenance record shape). A transform application is
analytically meaningful and must be explainable (ADR-003), so whatever record
DD-19 produces needs a representation for "transform X with parameters Y was
applied". **Decide together with DD-19 — last, per the agreed sequencing.**

### D5 — Client-facing mitigation API (monoid B.7 decision 4)

If mitigation becomes a concept clients send and receive, that is an API-shape
decision (trigger #1) requiring its own ADR. It presupposes D1 Option 1 (only
data can cross the API boundary). Not before D1 is decided.

### D6 — Transform input type (added 2026-07-17) — ✅ DECIDED & DONE (Option 1)

**Decision goal and context.** Monoid Part A introduced `TrialOutcomes` (trial
count + sparse trial→loss map) as a standalone type, which did not exist when
`RiskTransform` was written against `RiskResult`. Every operation only reads
and writes the loss map; none touches node identity or provenance. The B.8
constructor rewrite forced the question: fix the input type in the same pass,
or rewrite the constructors twice.

**Decision (user, 2026-07-17): Option 1 — `run: TrialOutcomes => TrialOutcomes`.**
A transform now applies to any node's result (leaf or portfolio) via its
`trialOutcomes` field and cannot see identity or provenance. The alternative
(keep `RiskResult => RiskResult`) preserved the portfolio limitation and
guaranteed a second, breaking rewrite once callers exist. Accepted cost:
`nTrials` is visible to a transform; a `RiskTransformSpec` property asserts
every constructor-built transform preserves it. This does not reopen the
pipeline-stage decision (B3 stands).

## 5. Sequencing (updated 2026-07-17)

1. ~~B.8 `require` fixes + D2~~ ✅ done 2026-07-17, together with D6.
2. ~~D3 before any wiring~~ ✅ decided 2026-07-17 (Option 1, cache raw).
3. ~~D1 design~~ ✅ decided 2026-07-17 (stratified `TransformSpec` +
   `TransformPipeline`; sketch in D1 above). **D1 build** waits for the first
   concrete mitigation use case (dead-code rule; see D1).
4. Remaining, in order of external trigger:
   - **D1 build** — with the first consumer, starting from the locked sketch.
   - **D4** — DD-19 closed 2026-07-18, so no longer gated on it; decide with
     D1's build or the first mitigation wiring. If D1's
     pipeline is the record embedded in provenance, D4 reduces to a placement
     question.
   - **D5** — after D1's build, as its own ADR.
5. None of the remaining items blocks milestone-2b Phase A.
