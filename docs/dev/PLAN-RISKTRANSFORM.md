# PLAN — RiskTransform (Mitigation): Knowledge Consolidation & Open Decisions

Status: **Partially executed.** Created 2026-07-16. On 2026-07-17 the user decided
and the following was implemented: B.8 defect fixes, D2 Option 1 (delete `Equal`),
D6 Option 1 (retarget to `TrialOutcomes`); D3 was decided (Option 1, cache raw)
as policy — no code exists to wire it yet. D1 is decided (stratified
`TransformSpec` + `TransformPipeline` design locked; build deferred to the first
consumer; the trait was later renamed `ResultTransformSpec` — §7 OD-2 ruling
2026-08-08, `TransformPipeline` unchanged). D4 (unblocked 2026-07-18 by DD-19's closure; decide with D1's
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

> **Naming update (2026-08-08, §7 OD-2 ruling):** the trait sketched below ships
> as `ResultTransformSpec` (two-stage naming: it is specifically the
> result-stage spec, sibling to `RiskLeafTransform`). `TransformPipeline` keeps
> its name. The sketch is preserved verbatim as the decision record.

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
   `TransformPipeline`; sketch in D1 above; trait since renamed
   `ResultTransformSpec` — §7 OD-2). **D1 build** lands in §7 M1 (the first
   consumer has arrived).
4. Remaining, in order of external trigger:
   - **D1 build** — with the first consumer, starting from the locked sketch.
   - **D4** — DD-19 closed 2026-07-18, so no longer gated on it; decide with
     D1's build or the first mitigation wiring. If D1's
     pipeline is the record embedded in provenance, D4 reduces to a placement
     question.
   - **D5** — after D1's build, as its own ADR.
5. None of the remaining items blocks milestone-2b Phase A.

---

## 6. First-consumer concept (high level — not a build spec yet)

Recorded at concept level. Signatures, codecs, and file inventory are deferred
to a build plan; this section fixes the shape of the idea, not the details.

**Mitigation is a first-class, explicit domain concept — not a baked parameter
edit.** It must be visible, queryable, toggleable, comparable with/without, and
recorded as a provenance step. Dissolving a mitigation into edited node numbers
and keeping only a metadata trace is rejected.

- **Two stages, named.** `RiskResultTransform` (result-stage,
  `TrialOutcomes => TrialOutcomes` — the current `RiskTransform`, to be renamed)
  for effects with no parameter preimage (hard cap, deductible).
  `RiskLeafTransform` (parameter-stage, `RiskLeaf => RiskLeaf`) for
  likelihood/severity parameter changes — a **product** of a `LikelihoodTransform`
  on the probability and a `DistributionTransform` on the distribution, either
  component the identity. Product (disjoint fields, commuting), unlike the
  result-stage pipeline, which is an ordered, non-commutative composition.
- **Explicit representation, stored in versioned tree content** — either on the
  affected node or associated with the tree with a transparent node mapping.
- **Effective node derived at resolution:** parameter-stage before simulation,
  result-stage at the resolve edge (D3). Caching the effective
  (post-parameter-mitigation) leaf's simulation is an orthogonal efficiency
  choice, keyed on effective content; it does not touch the explicitness
  requirement.
- **Targeting is a transparent node predicate; mechanism VQL.** **Decided:
  tree-associated** — `RiskTree` gains a top-level `mitigations` collection in the
  versioned blob; on-node is only the degenerate single-node mapping. **Range
  expressiveness decided (B):** extend the *typed* range to full formulas
  (`∧`/`¬`/`∃`, closed-world negation) rather than adapter-derived predicates — a
  sibling vql-engine change (`docs/scratch/MITIGATION-PRE-PLANNING.md` §P-4). A
  mitigation's targeting predicate is a **restricted** sublanguage (closed in `x`,
  no answer variables, bounded auxiliary quantifiers, no mitigation-state
  predicates; §P-1 — pre-M3 the targeting fragment admits no quantifiers at
  all, §8.4-3; auxiliary-sort quantifiers arrive with M3's sorts).

**Open research feeding this concept:**

- **VQL soundness for targeting.** Exact-mode FOL predicate over the node domain
  gives crisp, deterministic, reproducible selection — sound for targeting.
  Keep two uncertainties separate: mitigation **coverage/rollout** across a
  population of nodes/instances is a sound vague-quantifier + sampler use;
  mitigation **efficiency/effect-size** on one node is not a quantifier concept
  and belongs in the Monte Carlo layer as a distribution-valued transform
  parameter sampled per trial. Conflating them is a category error.
- **Asset / knowledge-graph transferability.** The VQL knowledge base is a
  relational fact store already shaped around assets–risks–mitigations (engine
  example domain). Register currently feeds only the risk tree
  (`RiskTreeKnowledgeBase`: domain = tree nodes, structural predicates + sim
  functions). An asset / company-configuration graph is additive — a second KB
  source joined to risks by type/instance — and needs no engine change. The
  explicit-mitigation + VQL-targeting model transfers directly, with targeting
  predicates ranging over asset attributes instead of tree structure; keeping
  mitigation explicit is precisely what makes that future join possible. Node
  identity should move from name-based to stable-id-based before this.

**Scope.** Risk planning does not include asset scope. The mitigation design
targets the risk tree only. The requirements below keep an asset / knowledge-graph
extension open without building it now.

**Requirements carried into the build plan:**

- **Source-agnostic targeting.** The mitigation's targeting predicate must not be
  hard-wired to tree structure. It selects a set of targets through an interface
  that ranges over tree nodes today and can range over asset-graph elements later,
  with no change to the mitigation entity or its persistence — only the predicate's
  backing source changes.
- **Stable-id identity.** Mitigation targeting resolves to and stores stable node
  ids, never node names. The name-keyed VQL domain
  (`RiskTreeKnowledgeBase`, `Value(Asset, node.name.value)`) must be reconciled to
  id-based identity so targeting survives duplicate/renamed names and future
  multi-instance asset elements.

**Algebra (settled framing).** Three complementary structures, none in conflict:

1. **Commutative** aggregation monoid on `TrialOutcomes` — per-trial sum of
   children (ADR-009). Job: combine siblings into a portfolio.
2. **Non-commutative** mitigation-composition monoid on transforms — ordered
   composition (`Identity[RiskTransform]` / `Identity[TransformPipeline]`). Job:
   stack several controls on one node, in order.
3. **Monoid action** `Mits × Tree → Tree` — a scoped set of mitigations acting on
   a tree, folded at the right stage. Job: "apply these mitigations to this tree."
   Refinement: it is a **trace monoid** — scoped mitigations commute iff their
   scopes are disjoint, so order matters only where scopes overlap.

Consequences that follow from this framing:

- **Associativity invariant.** The aggregation combine is a pure per-trial sum
  with no mitigation logic. A mitigation transforms the values a combine
  **consumes** (a leaf operand) or **produces** (a node's finished aggregate),
  never the summation **step** — which keeps aggregation lawful (the old B4 point).
- **Scope ≠ affected set.** A mitigation directly transforms its **scoped** nodes;
  the **effect propagates** to all ancestors via aggregation (a portfolio benefits
  without being in scope). UI/provenance distinguish *directly-scoped* from
  *affected-by-descendant*.
- **Resolution is the action.** Because mitigations live in tree content, applying
  them is effectively `Tree → ResolvedTree`; resolving each predicate's scope
  against the tree is part of computing the action, so it recomputes per
  tree-version (memoized). Binding (type-check) is schema-stable and independent of
  ordinary tree edits.

---

## 7. Build plan (continuation, 2026-08-08)

Implements the §6 concept and the rulings recorded in
`docs/scratch/MITIGATION-PRE-PLANNING.md` ("Decisions (ruled)"). The sibling
vql-engine work is delegated under the contract
`../vague-quantifier-logic/PROMPT-VQL-RANGE-AND-TARGETING.md` (AC-1…AC-10);
this plan designs against those acceptance criteria and contains **no engine
changes**.

### 7.0 Phases and dependencies

| Phase | Scope | Module(s) | Depends on |
|---|---|---|---|
| **M1** | Domain model: renames, reified transform specs, `Mitigation` entity, tree-level collection + codecs, pure application algebra | `common` (+ tests) | nothing |
| **M2** | Persistence + resolution: Irmin storage paths, resolver-edge wiring (effective tree, result-stage application), mitigation-application record, override staleness detection | `server` (+ tests, server-it) | M1 |
| **M3** | VQL targeting & analytics: `Predicate` target variant, targeting-sublanguage validation, scope resolution via `satisfyingSet`, KB schema (`Mitigation` sort, `mitigate`, precomputed `mitigated`/`unmitigated`), KB memoization (P-2/P-3), engine version bump | `common`, `server`, `build.sbt` | M1, M2, engine AC-1…AC-10 delivered |
| **M4** | API surface + frontend: tree-PUT mitigation buckets, LEC endpoint selection parameter + mitigation-provenance layer in responses, mitigation selection UI (see OD-3), two-tier badges, override edit-popup + stale badge + nonsense check | `common`, `server`, `app` | M1–M3 (badges/selection UI need only M1–M2; predicate-scope UI needs M3) |
| **M5** | Mitigation-aware change visibility: problem space recorded in §7.7 — **no design yet, planned only after M1–M4 have landed** (user ruling on OD-4, 2026-08-08) | TBD | M1–M4 landed |

**Staging superseded for targeting (2026-08-10):** §8.2 is the
authoritative phase map — targeting (the `Predicate` variant, sublanguage
validation, scope resolution) moves from M3 into M1R, and KB + scope
memoization moves into M2. The M1 sections below describe the as-built
`Nodes`-based code, which stays until M1R lands (§8.2); §7.1.4's
target-invariant lines and §7.3's targeting bullets are superseded by §8.

**Detail level.** M1 and M2 are specified to implementation grade below (exact
signatures, file inventory). M3 and M4 are scoped as work items with their
interfaces named but not frozen; each is elevated to implementation grade in a
continuation section of THIS document (§7.5, §7.6) and presented for approval
before its first source edit (G3). Rationale: M3's exact signatures depend on
the delivered engine API (AC-5's final shape), and M4's on what M2/M3 actually
expose — freezing them now would speculate. This staging is itself an open
decision (OD-1) until approved.

**Versioning.** PATCH bump on landing each phase (mirror `APP_VERSION` to
`.env` and `.env.irmin`); MINOR when this plan closes. The M3 vql-engine bump
is a first-party sibling artifact (`com.risquanter %%% vql-engine`): exact pin
per ADR-020 §1; the 14-day cooldown (§10) targets external publishers and does
not apply, noted here as the pin-site rationale.

### 7.1 M1 — Domain model (`common`)

#### 7.1.1 Rename `RiskTransform` → `RiskResultTransform`

File `domain/data/RiskTransform.scala` is renamed to
`RiskResultTransform.scala`; the type, companion, and all members keep their
current shapes with only the type name changed (behaviour untouched):

```scala
case class RiskResultTransform(run: TrialOutcomes => TrialOutcomes) {
  def apply(outcomes: TrialOutcomes): TrialOutcomes
  def andThen(that: RiskResultTransform): RiskResultTransform
  def compose(that: RiskResultTransform): RiskResultTransform
}
object RiskResultTransform {
  val identityTransform: RiskResultTransform
  given Identity[RiskResultTransform]
  given Debug[RiskResultTransform]
  def applyDeductible(deductible: NonNegativeLong): RiskResultTransform
  def capLosses(cap: NonNegativeLong): RiskResultTransform
  def scaleLosses(factor: NonNegativeDouble): RiskResultTransform
  def insurancePolicy(deductible: NonNegativeLong, cap: NonNegativeLong): Validation[ValidationError, RiskResultTransform]
  def filterBelowThreshold(threshold: NonNegativeLong): RiskResultTransform
}
```

Test suite `RiskTransformSpec.scala` renamed to `RiskResultTransformSpec.scala`
(assertions unchanged). The stale comment in `iron/OpaqueTypes.scala` line ~80
referencing `RiskTransform.scaleLosses` is updated in the same pass.

#### 7.1.2 Result-stage spec reified (D1 sketch, renamed)

D1's locked sketch is built now (the first consumer has arrived), with the
trait renamed `TransformSpec` → `ResultTransformSpec` to match the two-stage
naming. **This is a deviation from D1's approved names — flagged as OD-2.**
New file `domain/data/ResultTransformSpec.scala`:

```scala
sealed trait ResultTransformSpec
object ResultTransformSpec {
  final case class ApplyDeductible(deductible: NonNegativeLong)     extends ResultTransformSpec
  final case class CapLosses(cap: NonNegativeLong)                  extends ResultTransformSpec
  final case class ScaleLosses(factor: NonNegativeDouble)           extends ResultTransformSpec
  final case class FilterBelowThreshold(threshold: NonNegativeLong) extends ResultTransformSpec
  final case class InsurancePolicy private (deductible: NonNegativeLong, cap: NonNegativeLong)
      extends ResultTransformSpec
  object InsurancePolicy {
    def create(deductible: NonNegativeLong, cap: NonNegativeLong)
        : Validation[ValidationError, InsurancePolicy]              // cross-field: cap > deductible
  }
  def toTransform(spec: ResultTransformSpec): RiskResultTransform   // single exhaustive match
  given Equal[ResultTransformSpec] = Equal.default
  given JsonCodec[ResultTransformSpec]   // discriminated; per-case Raw + mapOrFail (DistributionParams precedent)
}

final case class TransformPipeline(steps: List[ResultTransformSpec])
object TransformPipeline {
  val empty: TransformPipeline
  given Identity[TransformPipeline]      // list concatenation; associative, NOT commutative
  given Equal[TransformPipeline] = Equal.default
  given JsonCodec[TransformPipeline]
  def toTransform(p: TransformPipeline): RiskResultTransform
  // law (tested): toTransform(a <> b) ≙ toTransform(a) andThen toTransform(b)
}
```

#### 7.1.3 Param-stage: `RiskLeafTransform` product

New file `domain/data/RiskLeafTransform.scala`:

```scala
sealed trait LikelihoodTransform
object LikelihoodTransform {
  case object Keep extends LikelihoodTransform                              // identity component
  final case class Scale(factor: NonNegativeDouble) extends LikelihoodTransform
      // application clamps probability × factor into OccurrenceProbability's domain
  final case class Override(probability: OccurrenceProbability) extends LikelihoodTransform
  given Equal[LikelihoodTransform] = Equal.default
  given JsonCodec[LikelihoodTransform]
}

sealed trait DistributionTransform
object DistributionTransform {
  case object Keep extends DistributionTransform
  final case class ScaleSeverity(factor: NonNegativeDouble) extends DistributionTransform
      // lognormal: scales minLoss/maxLoss; expert: scales quantiles — one semantic op per representation
  final case class Narrow(fraction: ShrinkFraction) extends DistributionTransform
      // contract the spread toward the median by `fraction` (0 = no-op, →1 = collapse);
      // lognormal: shrink the CI symmetrically in log space; expert: pull quantiles toward the median quantile
  final case class Override(params: OverrideDistributionParams) extends DistributionTransform
  given Equal[DistributionTransform] = Equal.default
  given JsonCodec[DistributionTransform]
}

// Iron alias in iron/OpaqueTypes.scala + refine helper in iron/ValidationUtil.scala:
// type ShrinkFraction = Double :| (GreaterEqual[0.0] & Less[1.0])
// def refineShrinkFraction(value: Double, fieldPath: String): Either[List[ValidationError], ShrinkFraction]

/** Absolute replacement of a leaf's distribution — the expert-supplied post-mitigation shape.
  * Same mode invariant as RiskLeaf (expert ⇒ percentiles+quantiles; lognormal ⇒ minLoss<maxLoss),
  * validated once via a shared helper extracted from RiskLeaf.create (see 7.1.6). */
final case class OverrideDistributionParams private (
  distributionType: DistributionType,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  terms: Option[PositiveInt]
)
object OverrideDistributionParams {
  def create(
    distributionType: DistributionType,
    percentiles: Option[Array[Double]],
    quantiles: Option[Array[Double]],
    minLoss: Option[NonNegativeLong],
    maxLoss: Option[NonNegativeLong],
    terms: Option[PositiveInt]
  ): Validation[ValidationError, OverrideDistributionParams]
  given Equal[OverrideDistributionParams]        // structural; array fields compared by content
  given JsonCodec[OverrideDistributionParams]
}

/** Product of the two independent components; either may be Keep (identity). */
final case class RiskLeafTransform(
  likelihood: LikelihoodTransform,
  distribution: DistributionTransform
)
object RiskLeafTransform {
  val identity: RiskLeafTransform = RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Keep)
  /** Interpret onto a leaf; the output is a normal RiskLeaf revalidated through RiskLeaf.create. */
  def applyTo(t: RiskLeafTransform, leaf: RiskLeaf): Validation[ValidationError, RiskLeaf]
  given Equal[RiskLeafTransform] = Equal.default
  given JsonCodec[RiskLeafTransform]
}
```

#### 7.1.4 `Mitigation` entity + tree collection

`iron/OpaqueTypes.scala` gains the ADR-018 nominal wrapper (with Tapir/JSON
codecs following the `NodeId` pattern):

```scala
case class MitigationId(toSafeId: SafeId.SafeId)
object MitigationId {
  def fromString(s: String): Either[List[ValidationError], MitigationId]
  // JsonEncoder/JsonDecoder/Schema/Tapir Codec — NodeId pattern
}
```

New file `domain/data/Mitigation.scala`:

```scala
/** SUPERSEDED by §8 (2026-08-10 ruling): explicit-set general targeting is retired;
  * targeting is predicate-first against the delivered vql-engine 0.11.0 contract.
  * The signature below is the as-built M1 state until the §8 rework phase lands. */
sealed trait MitigationTarget
object MitigationTarget {
  final case class Nodes(ids: Set[NodeId]) extends MitigationTarget   // non-empty (checked in Mitigation.create)
  given Equal[MitigationTarget] = Equal.default
  given JsonCodec[MitigationTarget]
}

/** Global cross-mitigation order: ascending numeric key, MitigationId string as the stable
  * tiebreak. The key is the stored source of truth (merge-stable); UI ordering is a skin. */
final case class MitigationPrecedence(key: Int)
object MitigationPrecedence {
  val overrideBaseline: MitigationPrecedence = MitigationPrecedence(-1000)  // preset: applied first, relative ops blend on top
  val default: MitigationPrecedence          = MitigationPrecedence(0)
  val overrideFinal: MitigationPrecedence    = MitigationPrecedence(1000)   // preset: applied last, asserts the mitigated state
  given Equal[MitigationPrecedence] = Equal.default
  given JsonCodec[MitigationPrecedence]
}

sealed trait MitigationSpec
object MitigationSpec {
  /** Param-stage; leaves only. overrideBaseStamp = ContentHash of the target leaf's
    * LeafSimContent (DD-16 projection) at authoring time — REQUIRED iff either component
    * is an Override (staleness layer 1); renames/reparents do not change it by construction. */
  final case class LeafStage(transform: RiskLeafTransform, overrideBaseStamp: Option[ContentHash]) extends MitigationSpec
  /** Result-stage; any node. */
  final case class ResultStage(pipeline: TransformPipeline) extends MitigationSpec
  given Equal[MitigationSpec] = Equal.default
  given JsonCodec[MitigationSpec]
}

final case class Mitigation private (
  id: MitigationId,
  name: SafeName.SafeName,
  target: MitigationTarget,
  spec: MitigationSpec,
  precedence: MitigationPrecedence
)
object Mitigation {
  /** Cross-field rules (accumulated):
    *  - target Nodes set non-empty
    *  - spec LeafStage with an Override component ⇒ target is a single node AND overrideBaseStamp defined
    *  - spec LeafStage without Override ⇒ overrideBaseStamp empty
    */
  def create(
    id: MitigationId,
    name: SafeName.SafeName,
    target: MitigationTarget,
    spec: MitigationSpec,
    precedence: MitigationPrecedence
  ): Validation[ValidationError, Mitigation]
  given Equal[Mitigation] = Equal.default
  given JsonCodec[Mitigation]
  given Schema[Mitigation]
}

/** D-4 provenance layer: one record per applied mitigation, stored beside the simulation
  * provenance in responses — NEVER inside NodeProvenance (DD-19 stays identity-free). */
final case class MitigationApplicationRecord(
  mitigationId: MitigationId,
  spec: MitigationSpec,
  resolvedScope: Set[NodeId],
  precedence: MitigationPrecedence
)
object MitigationApplicationRecord {
  given JsonCodec[MitigationApplicationRecord]
  given Schema[MitigationApplicationRecord]
}
```

`domain/data/RiskTree.scala` changes:

```scala
final case class RiskTree(
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex,
  seedVarHighWater: SeedVarId.SeedVarId,
  mitigations: Seq[Mitigation] = Nil          // new field, default keeps all call sites source-compatible
)

// RiskTreeJson gains  mitigations: Option[Seq[Mitigation]]  (absent in pre-existing blobs → Nil)
// fromNodes / fromNodesUnsafe gain  mitigations: Seq[Mitigation] = Nil  and validate:
//  - mitigation ids unique;  names unique among mitigations (future VQL constants)
//  - every MitigationTarget.Nodes id resolves in the TreeIndex
//  - LeafStage targets are leaves;  (ResultStage targets: any node)
```

#### 7.1.5 Application algebra (pure, shared)

New file `domain/data/MitigationApplication.scala` — the monoid action
`Mits × Tree → Tree`, in `common` so the frontend can later preview
effective parameters and run the nonsense check client-side:

```scala
/** Which mitigations to apply, each optionally restricted to a subset of its scope
  * (per-(mitigation, node) enablement — OD-3 ruling 2026-08-09). Crosses the wire in
  * M4 as a query parameter. A NodesOnly restriction intersects with the mitigation's
  * resolved scope at application time: ids outside the current scope no-op. */
sealed trait MitigationSelection
object MitigationSelection {
  case object None extends MitigationSelection
  case object All extends MitigationSelection
  final case class Selected(entries: Map[MitigationId, ScopeRestriction]) extends MitigationSelection
  given JsonCodec[MitigationSelection]
}

sealed trait ScopeRestriction
object ScopeRestriction {
  case object FullScope extends ScopeRestriction                        // whole resolved scope (global toggle)
  final case class NodesOnly(ids: Set[NodeId]) extends ScopeRestriction // explicit per-node enablement
  given JsonCodec[ScopeRestriction]
}

object MitigationApplication {

  /** Per-node applicable mitigations, ascending precedence key, MitigationId tiebreak. */
  def scoped(tree: RiskTree, selection: MitigationSelection): Map[NodeId, List[Mitigation]]

  /** The action's param-stage half: every scoped leaf replaced by its transformed self
    * (Override wins per precedence; relative ops compose in order). Output is a normal
    * RiskTree revalidated through RiskTree.fromNodes — closure by construction. */
  def effectiveTree(tree: RiskTree, selection: MitigationSelection): Validation[ValidationError, RiskTree]

  /** The action's result-stage half for one node: the composed pipeline of every
    * ResultStage mitigation scoping this node, in precedence order (identity when none). */
  def resultTransformFor(nodeId: NodeId, scoped: Map[NodeId, List[Mitigation]]): RiskResultTransform

  /** Applications performed for a resolution — the D-4 records (resolvedScope = the
    * target sets as resolved against this tree version). */
  def applicationRecords(tree: RiskTree, selection: MitigationSelection): List[MitigationApplicationRecord]

  /** Staleness layer 4 (nonsense check): overrides that make the node's expected severity
    * or likelihood strictly worse than its current base. Diagnostic predicate (not part of
    * the action); platform-neutral inputs, so it stays here. */
  def worseningOverrides(tree: RiskTree): Set[MitigationId]
}
```

`staleOverrides` (staleness layer 1) is **deliberately NOT here** — it lives in
the server module (§7.2.2a). Decision record: OD-6.

Associativity invariant (tested): `effectiveTree` touches only leaves'
persisted params; `resultTransformFor` is applied by the resolver to a node's
finished `TrialOutcomes` (operand or finished aggregate) — never inside
`TrialOutcomes.combine`.

#### 7.1.6 Shared mode-fields invariant

The RiskLeaf mode rule (expert ⇒ percentiles+quantiles present; lognormal ⇒
minLoss < maxLoss) currently lives in `RiskLeaf` (require + create). It is
extracted into one helper used by both `RiskLeaf.create` and
`OverrideDistributionParams.create` (boyscout: single definition):

```scala
// in domain/data/RiskNode.scala (companion-level helper, exact home at implementation)
private[data] def validateModeFields(
  distributionType: DistributionType,
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]],
  minLoss: Option[NonNegativeLong],
  maxLoss: Option[NonNegativeLong],
  fieldPrefix: String
): Validation[ValidationError, Unit]
```

#### 7.1.7 M1 tests

- `RiskResultTransformSpec` (renamed; unchanged assertions).
- New `ResultTransformSpecSpec`: codec round-trip per case; `Equal` lawfulness;
  pipeline law `toTransform(a <> b) ≙ toTransform(a) andThen toTransform(b)`;
  `Identity[TransformPipeline]` laws.
- New `RiskLeafTransformSpec`: `applyTo` produces a valid leaf for every op on
  both representations; `Keep`/`Keep` is identity; Scale clamping; Narrow
  contracts spread; Override replaces wholesale; property — output leaf always
  passes `RiskLeaf.create`.
- New `MitigationEntitySpec`: `Mitigation.create` cross-field rules (all
  accumulation paths); codec round-trip; precedence ordering incl. tiebreak.
- New `MitigationApplicationSpec`: `scoped` ordering; `effectiveTree` closure +
  Override absorption + baseline/final preset semantics; `resultTransformFor`
  composition order; `worseningOverrides`. (Staleness tests are M2 —
  `MitigationStaleness` lives server-side, OD-6.)
- `RiskTree` codec: old-format JSON (no `mitigations` key) decodes to `Nil`;
  round-trip with mitigations; `fromNodes` rejects dangling target ids,
  duplicate mitigation ids/names, LeafStage targeting a portfolio.

### 7.2 M2 — Persistence and resolution (`server`)

#### 7.2.1 Storage (ADR-004a mapping extended)

One Irmin path per mitigation, mirroring the per-node convention — this is
what makes disjoint mitigation edits auto-merge and puts mitigation conflicts
under the existing byte-level pre-check (ADR-032) with no new machinery:

```
workspaces/<wsId>/risk-trees/<treeId>/mitigations/<mitigationId>  → Mitigation JSON
```

```scala
// infra/irmin/WorkspaceStoragePaths.scala
def treeMitigations(wsId: WorkspaceId, treeId: TreeId): String

// repositories/RiskTreeRepositoryIrmin.scala
//  - writeTree: one IrminTreeEntry per mitigation ("mitigations/{id}") beside meta + nodes
//    (DD-7 whole-subtree replacement keeps working: omitted mitigation = deleted)
//  - read path (getById / getAtCommit): read mitigations/* and pass into RiskTree.fromNodes
```

`RiskTreeRepositoryInMemory` stores whole `RiskTree` values and is expected to
need no change; it is in the inventory in case compilation surfaces one.

#### 7.2.2 Resolver-edge wiring

```scala
// services/cache/RiskResultResolver.scala
trait RiskResultResolver:
  def ensureCached(
    tree: RiskTree, nodeId: NodeId, seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    mitigations: MitigationSelection = MitigationSelection.None
  ): Task[LossDistribution]
  def ensureCachedAll(
    tree: RiskTree, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    mitigations: MitigationSelection = MitigationSelection.None
  ): Task[Map[NodeId, LossDistribution]]
```

`RiskResultResolverLive` behaviour for a non-`None` selection:

1. `MitigationApplication.effectiveTree(tree, selection)` once per resolution;
   validation failure → `ValidationFailed` (typed channel, ADR-010).
2. `ContentHashIndex.build(effectiveTree)` — cache keys are the **effective**
   leaf content. DD-16's `LeafSimContent` and the cache value shape are
   untouched; a param-mitigated leaf is simply different content. D3 stands:
   the cache stores raw simulations; result-stage transforms are applied at
   the edge on every read and never cached.
3. In `resolveNode`, after a node's result exists (leaf hit/miss or portfolio
   aggregate), apply `resultTransformFor(node.id, scoped).run` to its
   `trialOutcomes` **before returning it to the parent** — the transform acts
   on the combine's operand or finished aggregate, never inside the combine
   (ADR-009 associativity honoured).
4. Tracing: `mitigation.selection` and per-resolution applied-count attributes
   (ADR-002).

The with/without comparison is two resolver calls (`None` vs a selection) —
cheap by design: raw leaf simulations are shared through the content cache
whenever param-stage mitigation leaves a leaf untouched.

#### 7.2.2a Override staleness detection (server — OD-6)

New file `services/cache/MitigationStaleness.scala`:

```scala
/** Staleness layer 1: overrides whose stored base stamp no longer matches the
  * target leaf's current LeafSimContent hash. Fires on any edit path (form,
  * merge, API PUT, time-travel revert); renames/reparents do not fire (DD-16
  * projection). Diagnostic predicate — resolution ignores staleness (frozen
  * expert opinion is the ruled semantics); consumers are handlers that put
  * `staleMitigationIds` into read/update response payloads. */
object MitigationStaleness {
  def staleOverrides(tree: RiskTree): Set[MitigationId]  // compares via ContentHashIndex.hashOf
}
```

Stamp writing is likewise server-side: the tree-PUT path computes
`overrideBaseStamp = ContentHashIndex.hashOf(targetLeaf)` when an Override
arrives or is re-affirmed (M4 wires the endpoints; M2 delivers the function
and its tests).

#### 7.2.3 M2 tests

- `RiskResultResolverSpec` extensions: selection `None` bit-identical to
  today; param-stage: effective leaf simulated + cached under effective hash
  (raw entry untouched — both keys coexist); result-stage leaf transform
  applied before parent aggregation (portfolio aggregate reflects it);
  result-stage on portfolio applied after aggregation; `Only(ids)` subset;
  precedence order respected end-to-end; application records returned/logged.
- `CacheTransparencySpec` extension: with/without pairs share raw-leaf cache
  entries for out-of-scope leaves.
- New `MitigationStalenessSpec`: stale fires on sim-relevant base edits only
  (probability/distribution change → stale; rename/reparent → NOT stale,
  DD-16); re-stamp clears; non-Override mitigations never reported; resolution
  output identical with and without staleness present.
- `serverIt` (`RiskTreeRepositoryIrminSpec` + a new `MitigationPersistenceItSpec`
  if clearer): create/update/read round-trip with mitigations; omitted
  mitigation deleted; branch fork + disjoint mitigation edits merge cleanly;
  same-mitigation edits conflict (byte-level pre-check, ADR-032).

### 7.3 M3 — VQL targeting & analytics (work items; elevate before build)

Superseded in part by §8: the targeting items below (Predicate variant,
sublanguage validation, scope resolution) moved into M1R, and KB
memoization into M2; the remaining M3 scope is listed in §8.2.

- **Engine bump**: `vql-engine` to the AC-1…AC-10 release (exact pin in
  `build.sbt`; breaking `ParsedQuery.range` widening absorbed at the register
  HTTP boundary).
- **`MitigationTarget.Predicate`**: validated targeting predicate — parsed by
  the engine's FOL formula parser at the boundary (fails at parse time for
  `Q[...]`/answer variables by construction), then binding-phase checks
  (closed in the node variable, bounded auxiliary quantifiers, no
  mitigation-state predicates — pre-planning P-1). New parser boundary → row
  added to ADR-029 §3's table (no interpolation; length-capped source;
  parse-don't-re-parse).
- **Scope resolution** via the engine's `satisfyingSet` (AC-5), resolved per
  tree-version, memoized; resolved sets feed `MitigationApplicationRecord.resolvedScope`.
- **KB schema**: `Mitigation` sort; binary `mitigate(node, mitigation)`;
  precomputed unary `mitigated(x)` / `unmitigated(x)` via the existing
  precomputed-set dispatcher pattern (P-3).
- **KB memoization** (P-2): `RiskTreeKnowledgeBase` built per tree-version
  (keyed on workspace/tree/branch content identity), not per query; the
  mitigation precomputes ride the same memoized build. **ADR-028 Decision 5
  ("model built per-query") must be amended in the same change** (doc sweep).
- **Range use**: analytics over mitigated/unmitigated populations arrive free
  with AC-1/AC-2 once the KB predicates exist; register-side work is KB-only.

### 7.4 M4 — API surface + frontend (work items; elevate before build)

- **Tree PUT buckets**: `RiskTreeUpdateRequest`/`RiskTreeDefinitionRequest`
  gain mitigation buckets (ADR-017 pattern: identity-preserving `mitigations`
  + `newMitigations`); Tapir endpoint shape change (Decision Trigger #1 —
  covered by this plan once §7.6 freezes the DTOs). This is D5's scope: the
  DTO/endpoint design lands with its own ADR (ADR-034) per D5's ruling.
- **LEC endpoints**: `mitigations` selection parameter on the analysis
  endpoints; responses carry raw + selected-mitigated curves and the
  mitigation-provenance layer (`MitigationApplicationRecord`s) beside
  simulation provenance. Client toggling re-fetches per selection (SSE/HTTP
  notification-refetch model, ADR-004a) — see OD-3.
- **Frontend**: per-mitigation selection UI per OD-3's refined model —
  mitigation child-styled rows under scoped nodes (per-(mitigation, node)
  enablement) + global tri-state control; within-view mitigated-twin curves
  beside unmitigated, with a client-side display mode giving a purely
  mitigated (residual-risk) view — granularity per OD-3c; **Compare slots
  gain mitigation selection as a slot dimension** with a copy-for-compare
  gesture (variant comparison: raw vs fw vs fw+IDS as slots,
  overlay/side-by-side, slot-keyed colours; comparand slots display their
  variant only, baseline shows raw);
  toggle↔curve colour consistency; two-tier badges (directly-scoped solid,
  affected-by-descendant faint + tooltip); override edit-popup flow (ADR-019
  Pattern 6 state machine) + stale badge + nonsense check surfacing; ADR-019
  ownership rules throughout.
- **Semantic diff**: `ChangedNodesService` compares node domain hashes only —
  a mitigation edit changes results without changing any node hash. Ruled
  (OD-4): recorded as phase M5, §7.7 — planned after M1–M4 land.

### 7.4.1 User-facing documentation deliverable (lands with M3/M4)

The dynamic-scope behaviour needs user documentation; the worked example below
is the preserved seed text (user ruling 2026-08-09). Placement across the
existing docs when the feature ships:

- `README.md` → new "Mitigations" entry under **Features**: high-level
  description (explicit first-class mitigations, two stages, predicate
  targeting with auto-scope, with/without comparison) — a few sentences, no
  walkthrough.
- `docs/user/API-TUTORIAL.md` → new mitigation section carrying the **full
  worked example below** (it is the step-by-step walkthrough document).
- `docs/user/TERMINOLOGY.md` → entries: *mitigation*, *targeting predicate*,
  *scope* vs *resolved scope*, *param-stage* vs *result-stage*, *override*,
  *precedence*.
- `docs/user/VQL-QUERY-EXAMPLES.md` → targeting-predicate examples +
  mitigated/unmitigated population queries (cross-linked from the tutorial).
- `docs/user/API-TUTORIAL.md` (same section) → **wire-format reference
  examples** for the mitigation payloads (user ruling 2026-08-10, security
  review F5): the mitigation entity, the op-discriminated
  `ResultTransformSpec` shapes, `RiskLeafTransform`, target/selection JSON —
  worked request/response bodies. These examples are the documentation of the
  wire format; the OpenAPI document deliberately renders these types as
  opaque objects (`Schema.any` — a derived or hand-written schema would
  duplicate the custom codecs and drift silently, and no external OpenAPI
  consumer exists).

**Seed text — dynamic predicate scope, worked example:**

Say Firewall has the targeting predicate "all leaves whose name starts with
`srv-`" instead of an explicit node list.

- Tree version 1 has leaves `srv-web` and `srv-db`. The predicate resolves to
  `{srv-web, srv-db}`. Firewall is enabled globally (full scope): both leaves
  are mitigated; the LEC's provenance layer records
  `resolvedScope = {srv-web, srv-db}`.
- You add a leaf `srv-mail` (tree version 2). Scope re-resolves per tree
  version, so `srv-mail` enters Firewall's scope automatically. Because the
  enablement is full-scope, `srv-mail` is mitigated with no further action,
  and the next LEC's provenance records the three-node set. The version-1
  result's record still says two nodes — past results are not retro-altered.
- Same story but Firewall was enabled per-node on `srv-web` only: after adding
  `srv-mail`, only `srv-web` stays mitigated. A restriction is an explicit
  list; new scope members are not silently pulled into it. `srv-mail` shows
  Firewall's row (in scope, badge visible) unticked until clicked.
- You delete `srv-web` while the per-node restriction names it: the stored id
  no longer intersects the resolved scope, so the selection applies nothing —
  a no-op, not an error. Selections are client-side view state, so nothing
  persistent goes stale.

### 7.5 / 7.6 — reserved for the M3 / M4 implementation-grade continuations.

### 7.7 M5 — Mitigation-aware change visibility (problem space only)

Ruled on OD-4 (2026-08-08): this is the plan's **last deliverable**, to be
designed only after M1–M4 have landed. No design is locked here and no
preference is stated — this section records the problem space so the work is
explicitly part of the plan.

**Problem.** The system has two equality relations (ADR-032). Mitigations
deliberately live outside the domain relation: a mitigation edit changes every
affected node's simulation results while every node's domain content hash — and
therefore the semantic diff (`ChangedNodesService`), the compare view's
changed-nodes markers, and any domain-hash-driven "what changed" surface —
reports no change. Merge *safety* is unaffected (the byte-level pre-check
covers the `mitigations/{id}` paths), but a user comparing two branches that
differ only in mitigation content sees "no changes" while the curves differ.
Adjacent surfaces with the same blindness: branch-compare overlays, history
scrubbing annotations, and any future "changed since" indicator.

**Follow-up instruction (verbatim scope for the M5 planning session):** once
M1–M4 are landed, plan how mitigation-level changes become visible across the
diff/compare/history surfaces — as a §7.8 implementation-grade continuation of
this document, presented for approval before any source edit.

### ADR alignment

Reviewed the complete corpus in `docs/dev/` (all files in force per the
adr-constraints skill). Per-ADR outcome for this plan:

| ADR | Bearing | Alignment |
|---|---|---|
| 001 (Iron/smart constructors) | All new types | Compliant: every entity via `create`/refined params; no raw primitives in signatures |
| 002 (logging/telemetry) | Resolver wiring | Compliant: span attributes extended; no new log sinks |
| 003 (provenance) | D-4 layer | Compliant: `NodeProvenance` untouched; mitigation records are a separate layer beside it (DD-19 identity-free preserved) |
| 004a (+appendix) (persistence) | Storage mapping | Compliant: per-mitigation path mirrors per-node convention; single writer; DD-7 atomic whole-subtree write extended |
| 004b | — | No bearing (unadopted WebSocket variant) |
| 005 | Cache | Historical (superseded by ContentCache); no bearing beyond ADR-014/15 notes below |
| 006 | — | No bearing (collaboration unbuilt) |
| 007 (+appendix) (branching/merge) | Mitigation merges | Compliant: path-level merge gives disjoint-edit auto-merge; conflicts surface via existing `MergeConflict` |
| 008 | — | No bearing (conceptual error/resilience patterns; ADR-010/031 govern) |
| 009 (aggregation monoid) | Result-stage application | Compliant: transforms act on operands/finished aggregates, never in `combine`; law tests added |
| 010 (errors) | All validation | Compliant: `Validation` accumulation; typed channels; no exceptions |
| 011 (imports) | All code | Compliant by convention |
| 012 (mesh) | — | No app-level resilience/auth added |
| 014 (+appendices) (caching) | Cache keys | Compliant: outcomes cached, not curves; no transform params in keys (D3); effective-content keying reuses DD-16 unchanged |
| 015 (resolver) | Resolver edge | Compliant: `ensureCached` stays the single simulation entry point; mitigation is edge logic around it |
| 016 (config) | — | No new config in M1/M2 |
| 017 (+NOTES) (tree API) | M4 DTO buckets | Deferred to §7.6: whole-tree PUT + identity-preserving buckets pattern will be followed; flagged now |
| 018 (nominal wrappers) | `MitigationId` | Compliant: case-class wrapper over `SafeId`, `NodeId` pattern |
| 019 (frontend) | M4 | Deferred to §7.6: parent-owned state, Pattern 6 for the popup flow |
| 020 (supply chain) + skill | M3 engine bump | Compliant: exact pin; first-party sibling → cooldown n/a (documented at pin site) |
| 021 (capability URLs) | — | Endpoints stay under `/w/{key}`; no new auth surface |
| 022 (secrets) | — | No credentials involved; mitigation data is ordinary domain content |
| 023 | — | No bearing (TLS/local trust) |
| 024 (PEP) | — | No authorization writes; PEP untouched |
| 025/027 (SPA routing/nginx) | — | No new routes outside existing prefixes |
| 026 (images) | — | Engine bump triggers the documented graalvm-builder rebuild (register-dev skill); no Dockerfile changes |
| 028 (+appendix) (query pane) | M3 KB | **Amendment required in M3**: Decision 5 "model built per-query" superseded by tree-version memoization; KB schema additions follow the existing catalog/dispatcher patterns |
| 029 (injection) | M3 predicate | **Table row required in M3**: targeting predicate is a new parser boundary (parsed once at the boundary by the formula parser; never interpolated) |
| 030 (authz orchestration) | M4 endpoints | Deferred to §7.6: `Checked[Permission]` propagation on extended endpoints |
| 031 (startup readiness) | — | No bearing |
| 032 (equality relations) | Diff/merge | Compliant: mitigation blobs join the storage relation automatically; domain relation deliberately blind to mitigations (OD-4 covers the compare-view consequence) |
| 033 (exception boundaries) | New code | Compliant: throw-free; no new catches |
| INFRA-006 | — | No bearing (DB credentials) |

## File inventory

M1/M2 files (M3/M4 files are appended here when §7.5/§7.6 are approved):

- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskResultTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationApplication.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskResultTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/ResultTransformSpecSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationEntitySpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationApplicationSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeSeedVarIdSpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/WorkspaceStoragePaths.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/RiskResultResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/RiskResultResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/RiskResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CacheTransparencySpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala`
- `build.sbt`

### Open decisions

Status after the 2026-08-08 review session:

- **OD-1 — Plan staging.** ✅ RULED (Option A): M1+M2 implementation-grade now;
  M3/M4 elevated in §7.5/§7.6 before their builds.
- **OD-2 — D1 naming deviation.** ✅ RULED (Option A): `TransformSpec` →
  `ResultTransformSpec`; `TransformPipeline` unchanged. Consistency sweep done
  same day (this document's status header, D1 note, sequencing item;
  `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` B.8 intro and checklist).
- **OD-3 — Mitigation selection & comparison UX.** ✅ RULED (2026-08-09, user
  preference Option A + assistant selection confirmed against the
  copy-for-compare workflow): per-(mitigation, node) enablement via
  child-styled mitigation rows under scoped nodes + additive global tri-state
  control; M1 ships the generalized `MitigationSelection` /`ScopeRestriction`
  ADT (§7.1.5); display control = chart-level tri-mode Raw / Mitigated / Both
  per view and per slot (OD-3c ruled Option A — per-series eyes only if a
  concrete mixed-visibility need appears later). **Copy-for-compare (aligned
  semantics, generalized 2026-08-09):** available on the active (baseline)
  view AND on every comparand slot card — a button beside the slot's existing
  controls, styled like them. The gesture duplicates the source's complete
  state — branch, tree, commit pin, charted node selection, mitigation
  selection, display mode — into the next free comparand slot; source and
  copy then diverge independently (scrub the copy's pin, add a mitigation,
  …). Any slot can seed the next variant, so comparison chains build
  incrementally (baseline → fw → copy of fw + IDS). Use case: small
  comparisons — same risks, same mitigations, two versions back, plus one
  extra mitigation. Remaining build detail lands at §7.6 elevation. History
  of the decision below (kept as record):
  OPEN, refined 2026-08-08.
  Server side is settled for M2 (per-selection computation; every gesture
  already costs one `lec-multi` round-trip today). User's clarified proposal
  under review: a mitigation appears as a child-styled row **under each risk
  node its scope covers** (one mitigation, several appearances), and
  Ctrl+Click on such a row enables the mitigation **for that node only** —
  per-(mitigation, node) enablement, i.e. applying a mitigation restricted to
  a subset of its scope; ADDITIVELY, the originally designed global mechanism
  (enable/disable a mitigation across its whole scope, tri-state when
  partially enabled) is kept. Consequence if adopted: M1's
  `MitigationSelection` generalizes from a set of mitigation ids to a
  per-mitigation scope restriction (`Map[MitigationId, ScopeRestriction]`,
  `ScopeRestriction = FullScope | NodesOnly(Set[NodeId])`) — the ruling
  therefore fixes an M1 signature and must land before M1 freezes.
  Comparison model (refined with the user 2026-08-08): the Analyze view's
  **Compare slots** (`SlotCoordinate(branch, treeOverride, at)`; slots already
  support same-branch pairs for time-travel comparison) gain **mitigation
  selection as a slot dimension** — "copy-for-compare" duplicates the active
  view into the next free slot, where clicking mitigations sets that slot's
  selection; overlay/side-by-side then compares variants (raw vs fw vs fw+IDS
  = one slot each) with slot-keyed colours. This is phased **into M4** as a
  named work item (elevated at §7.6), not deferred. Display model (adopted
  2026-08-09): the response for a selection always carries BOTH the raw and
  mitigated series per charted node; which series are drawn is client-side
  display state — so a **purely mitigated view** (residual-risk picture
  without raw twins) is a display mode, not an API variant. Comparand slots
  with a selection display their variant curves only (baseline shows raw),
  keeping overlays free of duplicated raw curves. Open (OD-3c): the display
  control's granularity — chart-level tri-mode (Raw / Mitigated / Both) per
  view and slot, versus per-series visibility toggles, versus both. Scenario
  branches are NOT the comparison vehicle.
- **OD-4 — Semantic diff blindness.** ✅ RULED: backlog now, plus phase **M5**
  (§7.7) — problem space recorded, no design, planned only after M1–M4 land.
- **OD-5 — Selection default for existing read paths.** ✅ RULED (2026-08-08,
  Option A): `MitigationSelection.None` is the default on every existing read
  path — mitigation is strictly opt-in per request; no existing figure changes
  until a caller explicitly selects mitigations. §7.2.2's resolver defaults
  already encode this.
- **OD-6 — `staleOverrides` placement.** ✅ RULED (2026-08-09, Option B):
  relocated from `common`'s `MitigationApplication` (§7.1.5 as originally
  approved) to the server-side `MitigationStaleness` (§7.2.2a), original
  signature unchanged. Reasons, recorded for the decision trail:
  1. **Hash computation is JVM-only by prior decision.** `ContentHash` =
     `sha256(LeafSimContent.toJson)` via `java.security.MessageDigest` in
     `ContentHashIndex` — DD-14 (closed 2026-07-14 → "full JVM sha256"), whose
     load-bearing consistency argument is the **single-producer invariant**:
     no flow exists where two hash implementations must agree. Precision note:
     `MessageDigest` referenced from `common` (`CrossType.Pure`, one source
     tree) does compile under Scala.js and links as long as no JS code path
     reaches it — `WorkspaceKeyHash.fromSecret` already relies on exactly that
     unreachability pattern. A `common`-placed `staleOverrides` would have
     been the second such reachability-fragile site, breakable at link time by
     any future JS call; relocation removes the fragility instead of adding
     to it.
  2. **A second (JS/shared) hasher is ruled out**, not merely unchosen:
     cross-validation can pin the SHA-256 core but not the preimage bytes
     (platform-divergent `Double` rendering in the JSON preimage is silent,
     value-specific breakage), and a false "not stale" asserts frozen expert
     numbers against a changed base — reintroducing exactly the risk class
     DD-14 designed away.
  3. **`staleOverrides` is not algebra.** It participates in no law of the
     monoid action (resolution ignores staleness by design); it is a
     diagnostic predicate `Tree → Set[MitigationId]` comparing a stored
     fingerprint with a recomputed one. Moving it severs no algebraic
     structure; `MitigationApplication` keeps the complete action
     (`scoped`/`effectiveTree`/`resultTransformFor`/`applicationRecords`)
     plus the platform-neutral `worseningOverrides`.
  4. **Its only caller is server-side by architecture.** Staleness is
     computed in HTTP handlers (ADR-030 orchestration boundary) and shipped
     as `staleMitigationIds` in payloads; the client renders, never computes.
     The rejected Option A (inject `hashOf: RiskLeaf => ContentHash` into a
     `common` signature) compiled fine but bought a shared capability with no
     caller on the second platform.

### Verification plan

Every phase lands only with the full suite green (no tier deferred):

```bash
sbt compile                          # zero warnings
sbt commonJVM/test
sbt server/test
sbt app/test                         # Scala.js (shared module codecs compile + run on JS)
sbt "serverIt/test"                  # integration (local/irmin-prod:3.11-p1)
# BATS fast gate after code changes:
#   run_bats tests/bats/suite-c-in-memory.bats   (register-dev skill invocation)
```

Tests added per phase are listed in §7.1.7 / §7.2.3; M3/M4 test plans arrive
with their elevation sections. Each phase closes with the doc-consistency
sweep (comments/docs touched by the change updated in the same pass) and its
PATCH bump; plan close = MINOR bump.

---

## 8. Targeting re-plan (continuation, 2026-08-10): predicate-first

**Ruling context.** The user rejected explicit-set general targeting
(2026-08-10): the targeting predicate was always the designed mechanism, an
explicit id set contributes nothing a predicate cannot express, and it is
exactly the surface the security review flagged as unbounded client-supplied
input (finding F4). This section re-plans targeting under the assumption
that the vql-engine work lands **as described in the sibling plan**
`../vague-quantifier-logic/docs/PLAN-range-formula-and-satisfying-set.md`
(implements the AC-1…AC-10 contract of `PROMPT-VQL-RANGE-AND-TARGETING.md`;
one authorized deviation: AC-9 superseded — untyped backend retired). It
supersedes §7.1's `MitigationTarget.Nodes` design and absorbs most of the
former M3 targeting scope.

**Delivered contract assumed (vql-engine 0.11.0):**

- Typed path only; cross-compiled (JVM + Scala.js) — the parser and
  free-variable utilities are available in `common`/browser.
- Formula ranges: `ParsedQuery.range: Formula[FOL]` (breaking construction
  change), `BoundQuery.range: BoundFormula`, closed-world negation over the
  active domain, denominator = compound population.
- `satisfyingSet` entry point: exact, deterministic, type-checked,
  `Either[QueryError, Set[Value]]`, no sampling; validates
  free-variables-exactly-x and sort quantifiability (its input shape awaits
  the sibling plan's Ruling 1 — recommendation on record there: pre-parsed
  `Formula[FOL]` plus an `Either`-returning parse entry in the vague layer).

### 8.1 Design

**Targeting is a stored predicate.** The mitigation carries the predicate
source text; scope is a server-side resolution against the tree, never a
client-supplied node enumeration.

```scala
// common — new file domain/data/TargetingPredicate.scala
/** Restricted targeting sublanguage over one free node variable.
  * Boundary validation (cross-compiled, runs in browser and server):
  *  - parses via the engine's Either-returning parse entry
  *  - exactly one free variable (the target variable)
  *  - no answer variables; no quantifiers and no function terms (targeting
  *    fragment membership, §8.4-3 — auxiliary-sort quantifiers become
  *    admissible at M3 via the P-1 bind-time sort rule)
  *  - predicate whitelist: structural/attribute predicates only — the
  *    mitigation-state predicates (`mitigate`, `mitigated`) are rejected
  *    (self-reference/fixpoint exclusion, §6)
  * Wire format: the source string. The parsed formula is derived state,
  * never serialized. */
final case class TargetingPredicate private (source: TargetingSource)
object TargetingPredicate {
  def create(source: String): Validation[ValidationError, TargetingPredicate]
  given JsonCodec[TargetingPredicate]   // decode = create (boundary validation)
}
// iron/OpaqueTypes.scala: type TargetingSource = String :| (MinLength[1] & MaxLength[256])
// 256 (user ruling 2026-08-10): realistic predicates are tens of characters
// (a ~200-char string already holds a full multi-clause sentence); 256 is a
// convenient power-of-two ceiling and bounds parser work on stored text.

// common — Mitigation.scala rework
sealed trait MitigationTarget
object MitigationTarget {
  final case class Predicate(predicate: TargetingPredicate) extends MitigationTarget
  // Single-variant (RULED §8.4-1 = C, user 2026-08-10): the override anchor is
  // not a target variant — it is `overrideAnchor: NodeId` on
  // MitigationSpec.LeafStage, colocated with overrideBaseStamp (required iff
  // an Override component is present). Nodes(Set[NodeId]) is REMOVED.
}
```

**Resolution is a server component riding the memoized KB** (the ADR-028
memoization obligation moves here from the former M3):

```scala
// server — services/MitigationScopeResolver.scala
trait MitigationScopeResolver {
  /** Resolve every mitigation's predicate to a node-id set against the tree,
    * via the engine's satisfyingSet over the tree's typed model. Memoized per
    * tree version together with the KB itself; the context names WHICH tree
    * version (cache identity — see the memoization passage). */
  def resolve(context: ScopeResolutionContext, tree: RiskTree): IO[AppError, ResolvedScopes]
}
// ScopeResolutionContext: workspaceId + treeId + branch + Irmin revision —
// the full authority identity of the tree version (exact shape at M2
// elevation). ResolvedScopes: the per-mitigation outcome map — resolved
// Set[NodeId] or a per-mitigation resolution failure (F3 fix below); exact
// shape at M2 elevation.
```

**Storage is a trust boundary (predicate = stored source text).** The
predicate is necessarily persisted as its source string: the typed IL
(`BoundFormula`) has no serialization format, and binding is relative to a
specific tree version's type catalog — a stored bound form would go stale;
the source text re-parsed at the boundary is the only durable
representation. Every Irmin→register read therefore crosses the same
validation boundary as client input, which is already how tree reads work
today: `RiskTreeRepositoryIrmin.decodeNode`/`decodeMeta` decode through the
validating zio-json codecs (smart constructors, ADR-001) and reassemble via
`RiskTree.fromNodes` — tree-level invariants re-run on every read. The
mitigation collection (M2 storage) follows the identical pattern: decode =
`TargetingPredicate.create` = parse + sublanguage validation, so a
tampered or corrupted stored predicate fails the read with a typed
`RepositoryFailure`, never reaching the engine; parser cost on stored text
is bounded by the 256-char cap, and parse failures are `Either`-returned
(no exceptions) per the engine contract.

**The application algebra takes resolved scopes as input** — it no longer
reads ids off the mitigation (`common` stays engine-agnostic and the action
`Mits × Tree → Tree` is unchanged as algebra; only scope acquisition moves):

```scala
// common — MitigationApplication.scala rework (signature deltas only)
def scoped(tree: RiskTree, selection: MitigationSelection,
           resolvedScopes: Map[MitigationId, Set[NodeId]]): Map[NodeId, List[Mitigation]]
def effectiveTree(tree: RiskTree, selection: MitigationSelection,
                  resolvedScopes: Map[MitigationId, Set[NodeId]]): Validation[ValidationError, RiskTree]
def resultTransformFor(nodeId: NodeId, scoped: Map[NodeId, List[Mitigation]]): RiskResultTransform  // unchanged
```

In plain terms: the function that decides which mitigations apply to which
nodes used to read the answer directly off each mitigation record (its
stored id set). With predicates, that answer requires the engine and the
tree's typed model — which exist only on the server — while the application
algebra stays a pure function in `common`. So the server computes the
answer once (`MitigationScopeResolver`) and hands it to the same pure
functions as a lookup table. Example: Firewall stores the predicate
`name starts with "srv-"`; the resolver evaluates it against the current
tree and produces `{firewall → {srv-web, srv-db}}`; `effectiveTree` then
transforms exactly those two leaves. This is a new consequence of the
predicate-first ruling (2026-08-10), not a previously discussed design —
its layering follows the OD-6 precedent (pure algebra in `common`,
environment-dependent computation server-side).

**Memoization — what changed vs. the earlier model (nothing structural,
two things moved).** The earlier model (recorded pre-§8): the query
knowledge base is rebuilt from scratch on every analytics query today; the
planned fix was ONE cache keyed on the tree version (the Irmin revision) —
same tree version, same KB — with mitigation scope resolution and the
precomputed `mitigated(x)` riding that cached KB. That model is unchanged.
What moved: (1) **when it lands** — it was an M3 (analytics) work item;
it is now an M2 obligation, because with predicate targeting every
*simulation* request needs scope resolution, so KB construction sits on the
hot path much earlier than analytics; (2) **what is cached** — the cache
entry now holds the KB *plus* the resolved scope map
(`Map[MitigationId, Set[NodeId]]`), since the scopes are a pure function of
the same tree version and would otherwise be recomputed per request. The
invalidation rule is identical: a new tree version (any tree edit) drops
the entry; nothing else does.

**Cache identity (security-review F1 fix, user-approved 2026-08-10).** The
cache must know *whose* tree version it holds, not merely which content:
one resolver-cache instance **per workspace** (the `ContentCache` DD-17
precedent — cross-workspace contamination becomes structurally
impossible), keyed inside the instance by (`TreeId`, branch, Irmin
revision), all passed explicitly via `ScopeResolutionContext`. Two
non-options, ruled out with reasons: keying on `TreeId` alone serves one
branch another branch's scope map; keying on the DD-16 **domain** hash is
wrong because that projection deliberately excludes node *names* while
predicates reference names — a rename changes resolution but not the
domain hash (the two-hash-relations distinction: byte-level identity, not
domain identity, is the correct key material). The KB built for scope
resolution is **results-free** (no simulation results — the targeting
sublanguage admits no simulation-backed symbols, §8.4-3), which is what
makes the cached entry a true pure function of the tree version. Eviction
of historic-revision entries: head-only, ruled (§8.4-5).

**Stage-domain scope restriction (ruled 2026-08-10).** A mitigation's
applied scope is **defined** as the predicate's satisfying set intersected
with its stage's domain: `scope(m) = satisfying(m.predicate) ∩
domain(m.spec)`, where `domain(LeafStage)` = the tree's leaves and
`domain(ResultStage)` = all nodes. This is a definition applied at every
resolution, not a validation check — a portfolio matched by a LeafStage
predicate (authored so, or drifted into the satisfying set by a rename or
merge) is simply outside the mitigation's scope, with no error and no
drift signal; the M1 write-time rule "LeafStage targets are leaves"
(§7.1.4) is superseded by this definition, which unlike the write check
holds on every stored tree state, including merge results no PUT ever
validated. The type level already makes the wrong application
unrepresentable (`RiskLeafTransform.applyTo` accepts only `RiskLeaf`;
`effectiveTree` transforms leaf positions of the sealed `RiskNode` ADT),
so the definition and the types agree — the resolver computes what the
algebra could apply anyway. Consequences: an Override whose anchor node
is no longer a leaf applies nowhere (empty applied scope, surfaced
through the per-mitigation outcome, F3 pattern); `resolvedScope` in the
D-4 provenance record is the **applied** (post-restriction) scope.

**Validation split.** `common` (`RiskTree.validateMitigations`) keeps unique
ids/names and predicate parse-level validation; everything needing
resolution moves server-side to tree-write validation: an Override's
predicate must resolve to exactly `{overrideAnchor}` (§8.4-1, ruled C).
Unresolvable predicates (valid syntax, empty scope) are a no-op, not an
error — consistent with the dynamic-scope worked example (§7.4.1).
Provenance continues to record the resolved scope set per LEC (D-4 layer;
applied scope per the stage-domain definition above).

**Per-mitigation error isolation (security-review F3 fix, ruled
2026-08-10).** Resolution errors are isolated per mitigation and
accumulated, never short-circuited — ZIO's error-accumulation combinators
(`ZIO.partition`-style: resolve each predicate individually, collect all
failures alongside all successes), not `flatMap` sequencing. A predicate
that fails to *bind* against the tree version (its quoted node name was
renamed/deleted → `UnknownConstantOrLiteralError`) yields, for that
mitigation only, an empty scope plus a per-mitigation resolution-failure
signal in `ResolvedScopes` — a sibling of the §8.4-1 scope-drift signal;
the request as a whole never fails because one stored predicate went
stale. The tree-write anchor check blocks a PUT only on resolution errors
of the mitigation(s) being written, never on pre-existing ones. Read-time
semantics of a *divergent* (bound but anchor-mismatched) override:
apply-at-anchor, ruled (§8.4-4).

**Size bounds (F4 residue, absorbed here).** With enumeration gone, the
remaining wire bounds are small and land with the rework:
`TargetingSource` MaxLength 256; `RiskTree.mitigations` max 1000;
`TransformPipeline.steps` max 100 — all three as Iron literals (values +
vehicle ruled 2026-08-10, §8.4-2). `ScopeRestriction.NodesOnly` (selection,
request-scoped display state, M4) gets its bound in the M4 elevation.

### 8.2 Phase rework map

- **M1R (domain rework; replaces §7.1's targeting + absorbs former M3
  domain scope):** `TargetingPredicate`, `MitigationTarget` rework, algebra
  signature deltas above, bounds, test rework (MitigationEntitySpec /
  MitigationApplicationSpec re-targeted to predicates; parse-validation
  spec). Blocked on vql-engine **0.11.0 on Maven Central** and its Ruling 1
  outcome; elevation to implementation-grade happens then (OD-1 pattern).
  The as-built M1 `Nodes` code stays until M1R lands (pre-prod, nothing
  persisted, no migration).
- **M2 (persistence + resolver):** unchanged in storage shape
  (`mitigations/{id}` paths store the mitigation with its predicate
  source); `RiskResultResolver` consumes `MitigationScopeResolver` output;
  `MitigationStaleness.staleOverrides` unchanged (OD-6). KB + scope
  memoization per tree version lands here (was M3's perf item).
- **M3 (shrinks):** what remains after M1R absorbs targeting: the KB
  `Mitigation` sort + `mitigate`/`mitigated` analytics predicates with the
  precomputed `mitigated(x)` (§6), ADR-028 amendment + ADR-029 parser-
  boundary table row (the targeting predicate is a new parser boundary —
  the boundary lands with M1R, the ADR-029 row records it), vql 0.11.0
  adoption sweep (breaking `ParsedQuery` construction — register call sites
  in QueryServiceLive / app query state adapted at the pin bump).
- **M4/M5:** unchanged.

### 8.3 ADR alignment (delta)

ADR-028 (typed path only — strengthened by the engine's untyped
retirement); ADR-029 (new parser boundary: targeting predicate — table row
obligation); ADR-001 (predicate validated at the boundary via smart
constructor; server receives validated types); ADR-030 (resolution in
handlers/services, server-side). No new deviations.

### 8.4 Open decisions

All five items are **RULED** (2026-08-10) — no open decisions remain in
this section; the entries are kept as the decision record. Plan-wide, D4
and D5 (§4) remain open by design (decided at first mitigation wiring /
M4 elevation respectively).

1. **Override target anchoring.** The 4-layer staleness stack (stamp /
   edit popup / stale badge / nonsense check) and the stamp's meaning
   (ContentHash of the target leaf's DD-16 projection at authoring time —
   renames excluded by construction) are DECIDED and not reopened here.
   The only new question predicate-first targeting introduces is how the
   override *points at* its one leaf. (A) Predicate-only: the predicate
   must resolve to exactly one node, checked server-side at tree write. A
   rename can silently re-point the predicate at a *different* leaf; the
   stamp then mismatches, but the signal reads as "content changed" when
   the truth is "target changed" — two distinct drifts, one indicator.
   Worked example: override authored on `srv-db` via predicate
   `name starts with "srv-"` ∧ …; `srv-db` is renamed `db-main`; the
   predicate now resolves to `srv-web` alone, and the override applies to
   the wrong leaf with only a stamp-mismatch badge as the clue. (B)
   `SingleNode(NodeId)` variant reserved for Override: node ids are
   rename-stable, so the override follows its leaf through renames with no
   false staleness (stamp fires only on genuine content edits — exactly
   the designed semantics); create-time checkable in `common`; costs one
   special case in the target ADT. (C — user-proposed 2026-08-10, best of
   both) Targeting stays **single-variant** (`Predicate` only, uniform
   storage and UX); the override's anchor is a `NodeId` field colocated
   with the stamp in `MitigationSpec.LeafStage`
   (`overrideAnchor: NodeId`, required iff an Override component is
   present — same cross-field rule family as `overrideBaseStamp`).
   Server-side write validation: the predicate's resolution must equal
   `{overrideAnchor}`. Divergence is a **distinct scope-drift staleness
   signal**, separate from the stamp's content-drift signal — the
   conflation that motivated B disappears, without forking the target ADT.
   Default authoring path: the UI emits a stable-id equality predicate for
   the picked leaf, which (ids being rename-stable) never diverges from
   the anchor unless the node is deleted; a hand-written name-based
   predicate is allowed and its drift is flagged precisely as scope drift.

   **UI authoring mechanism (user-elaborated 2026-08-10):** the user never
   types or pastes a node id — a node picker fills a fixed client-side
   template (concrete syntax fixed at elevation against the vql 0.11.0
   grammar) with the selected node's id, and the filled result travels as
   an ordinary predicate string; there is no separate wire shape for
   picked-vs-typed targeting. Security treatment of the template: the
   server grants it no trust — the filled string is untrusted input like
   every predicate (from the server's perspective the UI is just another
   HTTP client) and goes through the same parse (256-char bound, typed
   parse, `mitigate`/`mitigated` whitelist exclusion) plus the
   resolution-equals-`{overrideAnchor}` check. Two layers apply to the
   interpolation itself: `NodeId`'s refinement
   (`^[0-9A-HJKMNP-TV-Z]{26}$`) admits no quotes, spaces, or operator
   characters, so a conforming id cannot alter the template's parse shape
   (defence-in-depth relying on the constraint, per ADR-029's
   string-built-query rule); the controlling check is server-side — a
   forged or tampered predicate either fails parse/validation or resolves
   to something other than `{overrideAnchor}` and the write is rejected.
   A conceptual security review of this surface (predicates from
   untrusted actors generally) was commissioned 2026-08-10.

   **RULED: C (user, 2026-08-10)** — uniform predicate targeting, stable
   stamp anchor, and the two drift kinds become two distinguishable
   signals instead of one ambiguous badge. The deep security review
   (2026-08-10) confirmed the write-time anchor check is sound: no
   storable predicate can make an override touch a node other than its
   anchor at the moment of writing.
2. **Bounds numbers** (§8.1). **RULED (user, 2026-08-10): values
   confirmed (`RiskTree.mitigations` max 1000, `TransformPipeline.steps`
   max 100), enforced as Iron literals** — the codebase's uniform vehicle
   for persisted-content and wire validity bounds (every `MaxLength`
   refinement in `iron/OpaqueTypes.scala`; the 256-char `TargetingSource`
   bound is already Iron by the same ruling). Runtime configuration (the
   `application.conf` nTrials pattern) was considered and rejected: that
   pattern serves per-request execution parameters that are never part of
   stored content, whereas these bounds govern persisted tree content
   validated at materialization — a lowerable configured bound would
   invalidate already-stored trees on re-read, and the grandfathering
   alternative (a server write-path-only check) splits validation across
   layers against ADR-001 and splits the bounding mechanism against the
   F5 single-mechanism requirement. Changing a bound is a one-line
   refinement edit shipped as a PATCH.
3. **Targeting sublanguage enforcement mechanics (security-review F4).**
   Settled part (no decision): simulation-backed symbols (`p95`, `p99`,
   `lec`) are excluded from targeting — admitting them would put
   whole-tree simulation on the tree-write path, make resolved scopes
   seed-dependent, and make targeting circular. Open part (since ruled
   below): the *mechanism*. User direction (2026-08-10): enforce by parsing targeting
   predicates against a restricted **FOL sub-grammar** (no vague
   quantifiers; term rule admits no function application, which excludes
   the simulation functions grammatically rather than by symbol
   whitelist), delegating as much as possible to the parser.

   **Enforcement locus RULED (user, 2026-08-10): engine-side
   fragment-membership API.** The engine gains a function (beside the
   `fol.typed` layer; name and fragment-spec shape at elevation) that
   walks a parsed `Formula` and reports whether it lies in a declared
   fragment — for targeting: no quantifier nodes, no function terms; for
   screening: quantifier depth ≤ k (one implementation, two fragment
   specs — satisfies the F5 single-mechanism requirement for the cost
   bound). Register calls parse + membership check as one boundary step
   and keeps treating `Formula` as opaque. Rationale: the engine's
   Harrison-port parser core is preserved verbatim under its ADR-007
   (quantifier arms and the term-precedence tower are hardcoded there),
   so a restricted parser entry point would fork or reshape that
   protected core for no gain — a membership test on the parse tree
   accepts exactly the same string set, and parse output is inert data,
   so rejection before typed bind preserves the reject-at-the-language
   security property in full. Ships as a small engine release after
   0.11.0 (0.11.x); sibling-repo work, see §8.5.

   **Quantifier exclusion RULED (user, 2026-08-10): excluded from the
   targeting fragment spec pre-M3.** Today the node sort is the only
   sort, so the exclusion equals the P-1 sort rule with no expressiveness
   loss. At M3, when auxiliary sorts arrive, the spec line is removed and
   the P-1 bind-time sort rule (built at M3 regardless — syntax cannot
   know a variable's sort) takes over; the membership machinery itself is
   unchanged. No open sub-questions remain on this item.
4. **Read-time semantics of a divergent override (security-review F2).**
   Merges (byte-level, domain-blind per ADR-032) and post-write renames
   can produce stored trees where an override's predicate no longer
   resolves to `{overrideAnchor}`; the write-time check never sees these
   states. Options considered: suspend (no-op + scope-drift signal),
   apply at the anchor (the `NodeId` field wins; predicate becomes
   authoring convenience + drift detector), fail the read (rejected:
   availability cost, and the only option incompatible with the F3
   per-mitigation outcome pattern). **RULED (user, 2026-08-10):
   apply-at-anchor** — the rename-stable anchor keeps the override on
   the leaf the expert assessed; the divergence is reported as the
   scope-drift signal in the per-mitigation outcome. The
   companion question (LeafStage scope member that is not a leaf) is
   dissolved, not ruled: the stage-domain scope definition (§8.1) makes
   non-leaf matches fall outside a LeafStage mitigation's scope by
   construction — no suspend semantics needed.
5. **Resolver-cache eviction for historic revisions (security-review
   F6).** Compare slots and history scrubbing resolve historic revisions;
   memoizing those without bound accretes a heavyweight KB entry
   (descendants index, ~n·depth set entries) per visited revision.
   **RULED (user, 2026-08-10): head-only** — one entry per live
   workspace/tree/branch, replaced on head advance; historic reads
   resolve uncached. Cost analysis behind the ruling: at realistic tree
   shapes (hundreds of nodes, depth < 10) a KB rebuild plus
   quantifier-free resolution is single-digit milliseconds — below the
   uncached portfolio re-aggregation a scrub step performs anyway, and
   orders of magnitude below fresh simulation (leaves × nTrials,
   default 10k) — while a retained entry is large; cheap-to-rebuild +
   expensive-to-retain is the head-only profile. Structurally the memo
   is a revision-checked slot per (tree, branch), not an evicting map —
   no EvictionStrategy, no generic-cache extraction from ContentCache
   (design + `CacheStats` reuse only). A bounded LRU over revisions
   remains the recorded M4 upgrade (carrying the generic-cache
   extraction with it) only if deep trees with thousands of nodes make
   per-step rebuild perceptible.

### 8.5 Sequencing note

The engine plan is the critical path: registers M1R elevation waits for the
sibling plan's rulings + phases 0–5 + a 0.11.0 Central release (supply-chain
skill applies to the bump; first-party waiver per ADR-020 §10). No register
mitigation code is written against an unreleased engine snapshot — the
Central-binary flow (PLAN-DEPENDENCY-REPUBLISH) is the only consumption
path. The fragment-membership API (§8.4-3, ruled engine-side) is a second
sibling deliverable: a small engine release after 0.11.0 (0.11.x), needed
by the register write-path validation at M2/M3 — not on the M1R critical
path, but its engine-plan slot should be scheduled alongside the 0.11.0
work.
