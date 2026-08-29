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
`docs/archive/milestone-2b-cache-and-decisions.md` (DD-15 through DD-19),
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
  sibling vql-engine change (`docs/archive/MITIGATION-PRE-PLANNING.md` §P-4). A
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
  predicates ranging over asset-graph relations instead of tree structure —
  asset attributes would be modelled as relational predicates (the same atom
  shape as today's `leaf(x)` / `child_of(x, …)`), since the targeting fragment
  admits predicate atoms but no attribute-access or function terms; keeping
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
`docs/archive/MITIGATION-PRE-PLANNING.md` ("Decisions (ruled)"). The sibling
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
| **M4** | API surface + frontend: tree-PUT mitigation buckets, LEC endpoint selection parameter + mitigation-provenance layer in responses, mitigation selection UI (see OD-3), two-tier badges, override edit-popup + stale badge | `common`, `server`, `app` | M1–M3 (badges/selection UI need only M1–M2; predicate-scope UI needs M3) |
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
effective parameters client-side:

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
  composition order. (Staleness tests are M2 —
  `MitigationStaleness` lives server-side, OD-6.)
- `RiskTree` codec: old-format JSON (no `mitigations` key) decodes to `Nil`;
  round-trip with mitigations; `fromNodes` rejects dangling target ids,
  duplicate mitigation ids/names, LeafStage targeting a portfolio.

### 7.2 M2 — Persistence and resolution (`server`)

> **⚠️ This section (2026-08-08) predates §8.6/§8.7 (M1R, 2026-08-13/14) and
> the M2 resolver-edge rulings in §8.8 (2026-08-15); its resolver signatures are
> reconciled in §8.14 (M2 slice 3, implementation-grade), which is the source of
> truth for the trait shape.** The algebra takes
> `resolvedScopes: Map[MitigationId, Set[NodeId]]` (§8.6), not `selection` alone;
> scope is produced by a new `MitigationScopeResolver` (§8.2, §8.8 M2-D1); the
> resolver's per-mitigation output is a `ScopeOutcome` coproduct (§8.8 M2-D2);
> and the resolver is `CachedResultResolver` (§8.8 M2-D4). Still current here:
> the storage shape (§7.2.1), the D3 caching rule (§7.2.2 step 2 — raw
> simulations cached, result-stage transforms applied at the edge), and the
> override staleness function (§7.2.2a, OD-6).

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

The resolver trait and its `ensureCached` / `ensureCachedAll` signatures are
specified in §8.14 (`CachedResultResolver`, implementation-grade) — the source
of truth for the trait shape. The wiring below is the behaviour those signatures
serve.

`CachedResultResolverLive` behaviour for a non-`None` selection:

1. `MitigationApplication.effectiveTree(tree, selection)` once per resolution;
   validation failure → `ValidationFailed` (typed channel, ADR-010).
2. `ContentHashIndex.build(effectiveTree)` — cache keys are the **effective**
   leaf content. DD-16's `LeafSimContent` and the cache value shape are
   untouched; a param-mitigated leaf is simply different content. D3 stands:
   the cache stores raw simulations; result-stage transforms are applied at
   the edge on every read and never cached.
3. In `distributionOf`, after a node's result exists (leaf hit/miss or portfolio
   aggregate), match `resultTransformFor(node.id, scoped)`: `None` returns the
   node's outcomes unchanged, `Some(t)` applies `t.run` to its `trialOutcomes`
   **before returning it to the parent** — the transform acts on the combine's
   operand or finished aggregate, never inside the combine (ADR-009 associativity
   honoured).
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
- **`MitigationTarget.Predicate`** (as-built, per §8): the targeting predicate
  is validated in the cross-compiled `common` boundary constructor
  `TargetingPredicate.create` — length-refine → `FOLParser.parse` (the FOL
  formula grammar has no vague-quantifier or answer-variable production, so
  `Q[...]`/answer variables fail at parse by construction) → then three
  accumulated checks on the parsed formula: targeting-fragment membership via
  `FragmentCheck.check(formula, Fragment.Targeting)` (no quantifier nodes, no
  function terms), exactly one free variable, and no `mitigate`/`mitigated`/
  `unmitigated` predicate. New parser boundary → row in ADR-029 §3's table (no
  interpolation; length-capped source; parse-don't-re-parse).
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

#### 7.3.1 M3 mandatory re-assessments (decision-guide-ready) — prerequisite: auxiliary sorts exist

These two questions are **deliberately deferred to M3, not dropped**, because
each depends on a prerequisite that does not exist yet (a second sort beyond
the node sort). They are M3 scope and MUST be ruled when M3 is built — they are
not open sub-questions of the current milestone. Both are written here in
decision-guide form so the ruling can be made directly with no re-request.
They originate as pre-planning P-1 divergences (check location; bounded
auxiliary quantifiers) between the scratch notes and the as-built M1R code.

**RA-1 — Where the targeting checks run (fragment membership, single free
variable, mitigation-state ban).**
- *Why it matters now-at-M3:* today the checks live in the cross-compiled
  `common` boundary constructor `TargetingPredicate.create`, so the browser
  and server share one validation and no invalid `TargetingPredicate` value
  can exist. Pre-planning P-1 instead placed them in the `server`-side
  `QueryBinder` binding phase. When auxiliary sorts arrive, a sort-dependent
  rule (RA-2) needs sort information that only typing/binding has — which
  reopens where each check belongs.
- *Option A — keep all checks in `common` (`TargetingPredicate.create`).* Pros:
  one boundary, correct-by-construction on both platforms, no invalid value
  ever exists; matches the current shipped design. Cons: any sort-dependent
  rule must be expressible without a bound sort environment, or must move
  server-side, splitting targeting validation across two layers. Plays out:
  the sort rule (RA-2) is either encoded structurally in the fragment spec or
  the whole targeting-validation stays in `common` only if the sort catalog is
  available there.
- *Option B — split: fragment/free-var/ban in `common`, sort-dependent rule in
  `QueryBinder`.* Pros: the sort rule sits where sorts are known (P-1's
  original placement); each check runs where its information lives. Cons: two
  validation loci for one concept; a `TargetingPredicate` can exist in `common`
  that a later server bind rejects, weakening the correct-by-construction
  guarantee.
- *Trade-off only the user weighs:* the single-boundary correct-by-construction
  guarantee (ADR-001) against putting the sort rule where sort information
  naturally lives.

**RA-2 — Whether the targeting fragment admits bounded auxiliary quantifiers.**
- *Why it matters now-at-M3:* today `Fragment.Targeting` rejects ALL quantifiers,
  ruled correct pre-M3 (§8.4-3) precisely because the node sort is the only
  sort, so there is nothing to quantify over and the P-1 sort rule and the
  no-quantifier rule coincide with zero expressiveness loss. At M3 auxiliary
  sorts (`Mitigation`, `RiskType`) arrive, and bounded auxiliary quantifiers
  (`∃a:Mitigation`, `∃r:RiskType`) become genuinely expressive — this is the
  point §8.4-3 records as the moment to revisit.
- *Option A — keep rejecting all quantifiers.* Pros: simplest fragment; no
  bind-time sort rule needed; smallest attack surface. Cons: targeting cannot
  express "nodes with some mitigation of kind K" or similar auxiliary-sort
  conditions; expressiveness ceiling.
- *Option B — admit bounded auxiliary quantifiers over non-node sorts only
  (P-1's rule), never over the node variable `x`.* Pros: recovers the P-1
  expressiveness; the bind-time sort rule (built at M3 regardless — syntax
  cannot know a variable's sort) enforces the "non-`x` sort only" boundary.
  Cons: needs the sort-aware rule and its placement settled (RA-1); larger
  fragment to validate and secure.
- *Trade-off only the user weighs:* targeting expressiveness over auxiliary
  sorts against fragment simplicity and validation/security surface.

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
  Pattern 6 state machine) + stale badge surfacing; ADR-019
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

Say Firewall has the targeting predicate `leaf(x) /\ descendant_of(x, "Servers")`
— every leaf under the `Servers` portfolio — instead of an explicit node list.
Structural targeting is what makes scope dynamic; prefix or substring matching on
names is **not** expressible in the targeting fragment (no function terms), so
"all `srv-*` leaves" is spelled as membership under a parent. (The KB domain
elements are node ids, so the concrete constant is the `Servers` portfolio's id,
filled by the node picker; the name is shown here only for readability.)

- Tree version 1 has leaves `srv-web` and `srv-db`. The predicate resolves to
  `{srv-web, srv-db}`. Firewall is enabled globally (full scope): both leaves
  are mitigated; the LEC's provenance layer records
  `resolvedScope = {srv-web, srv-db}`.
- You add a leaf `srv-mail` under `Servers` (tree version 2). Scope re-resolves per tree
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
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CacheTransparencySpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala`
- `build.sbt`

M1R adds (engine adoption + predicate targeting, §8.6):

- `modules/common/src/main/scala/com/risquanter/register/domain/data/TargetingPredicate.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/TargetingPredicateSpec.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
- `modules/common/src/main/scala/com/risquanter/register/common/FolSymbols.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/QueryRequest.scala`
- `modules/app/src/main/scala/app/state/AnalyzeQueryState.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/QueryResponseBuilder.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/QueryResponseBuilderSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`

§8.14 (`CachedResultResolver` rename + resolver-edge mitigation wiring) edits
these existing files (rename ripple + edge wiring); the three resolver files
themselves are the renamed bullets above (`CachedResultResolver.scala`,
`CachedResultResolverLive.scala`, `CachedResultResolverSpec.scala`), and
`QueryServiceLive.scala` / `CacheTransparencySpec.scala` are already listed:

- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/Item17RegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`

§8.11 adds (bind-error → UNKNOWN_REFERENCE classification + vql 0.16.0 re-pin):

- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala`
- `project/plugins.sbt` (Scala.js toolchain bump — see §8.11 D1 below; not hook-gated)

§8.12 (retire `=`; add `eq`/`named`/`has_id`) adds **no new files** — it edits
`RiskTreeKnowledgeBase.scala`, `FolSymbols.scala`, `RiskTreeKnowledgeBaseSpec.scala`,
and `build.sbt`, all already listed above.

§8.13 (slice 2: `MitigationScopeResolver` + `ScopeOutcome`) adds **four new
server-only files** (the resolver contract, its live impl, the per-workspace
registry mirroring `CacheScope`, and the spec — none pre-existed; the M1/M2
inventory above holds only the *renamed* resolver `RiskResultResolver.scala` and
`MitigationStaleness.scala`, not these):

- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ScopeResolverScope.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CacheScope.scala` (boyscout: its doc comments carried plan-provenance references cleaned in the same pass as the new files)

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
     (`scoped`/`effectiveTree`/`resultTransformFor`/`applicationRecords`).
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

**Delivered contract (vql-engine 0.13.1 — see §8.6 for the exact surface and
the single 0.10.2 → 0.13.1 pin bump; the 0.11.0 baseline below is retained as
the design-time assumption):**

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
  *    mitigation-state predicates (`mitigate`, `mitigated`, `unmitigated`)
  *    are rejected case-insensitively (self-reference/fixpoint exclusion, §6).
  *    At M3 the KB is the authority — it marks its own predicates
  *    non-targetable, superseding this hardcoded set.
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
`leaf(x) /\ descendant_of(x, "Servers")`; the resolver evaluates it against the
current tree and produces `{firewall → {srv-web, srv-db}}`; `effectiveTree` then
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
`TransformPipeline.steps` max 10 (revised from 100, user 2026-08-14: a
realistic pipeline stacks at most one of each of the five op types, so 10 is
a guard-rail ceiling with headroom, not a modeling maximum) — values ruled
2026-08-10, §8.4-2; count bounds enforced in validators per M1R-D1. `ScopeRestriction.NodesOnly` (selection,
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
   edit popup / stale badge) and the stamp's meaning
   (ContentHash of the target leaf's DD-16 projection at authoring time —
   renames excluded by construction) are DECIDED and not reopened here.
   The only new question predicate-first targeting introduces is how the
   override *points at* its one leaf. (A) Predicate-only: the predicate
   must resolve to exactly one node, checked server-side at tree write. A
   rename can silently re-point the predicate at a *different* leaf; the
   stamp then mismatches, but the signal reads as "content changed" when
   the truth is "target changed" — two distinct drifts, one indicator.
   Worked example: override authored on the leaf named `primary-db` via
   the name predicate `named(x, "primary-db")` (§8.12); `primary-db` is renamed
   `db-main`, so the predicate resolves to the empty set and the override
   silently applies to nothing; worse, if another leaf is later renamed
   `primary-db`, the predicate re-points at *that* leaf and the override
   applies to the wrong node with only a stamp-mismatch badge as the clue. (B)
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
   Default authoring path: the UI emits a stable-id predicate for
   the picked leaf — `has_id(x, "<nodeId>")` (§8.12; was `x = "<nodeId>"`
   before `=` was retired), a node-reference atom over the id-literal sort
   (founded in the targeting fragment) — which (ids being rename-stable)
   never diverges from the anchor unless the node is deleted; a hand-written
   name-based predicate (`named(x, "<name>")`) is allowed and its drift is
   flagged precisely as scope drift.

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
   max 10 — revised from 100, user 2026-08-14), enforced as Iron literals** — the codebase's uniform vehicle
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
   quantifier-free resolution is single-digit milliseconds **per predicate**
   (`satisfyingSet` scans the node domain once per predicate; total resolution
   is O(mitigations × nodes) cheap evaluations, memoized per tree version — the
   override subset barely contributes, since overrides resolve by their stored
   `overrideAnchor`, not a re-scan) — below the
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
Central-binary flow (DONE-PLAN-DEPENDENCY-REPUBLISH) is the only consumption
path. The fragment-membership API (§8.4-3, ruled engine-side) is a second
sibling deliverable: a small engine release after 0.11.0 (0.11.x), needed
by the register write-path validation at M2/M3 — not on the M1R critical
path, but its engine-plan slot should be scheduled alongside the 0.11.0
work. **DELIVERED — see §8.6:** the fragment API shipped in vql 0.12.0 as
`vql.fragment.FragmentCheck`; M1R uses it in `TargetingPredicate.create`.

### 8.6 M1R implementation-grade elevation (2026-08-13)

**Engine delivered — supersedes the "0.11.0 assumed" header above.**
vql-engine **0.13.1** is on Maven Central (first-party; cooldown-exempt,
ADR-020 §10). One release now carries the whole accumulated delta: formula
ranges + `satisfyingSet` (0.11.0), the fragment-membership API (0.12.0), and
the `fol.* → vql.*` package rename (0.13.0). Register adopts it in a **single
pin bump 0.10.2 → 0.13.1** (Decision 1 "absorb"; Decision 2 "target the
`vql.*` layout" — both ruled by the user 2026-08-13, so M1R is written against
the final package names, no intermediate `fol.*` pin). The engine surface M1R
consumes:

- Foundation (unchanged by the rename — top-level packages, cross-compiled to
  JS): `parser.FOLParser.parse(s: String): Either[parser.ParseError,
  Formula[FOL]]`; `logic.{Formula, FOL, Term}`;
  `logic.FOLUtil.fvFOL(fm: Formula[FOL]): List[String]`.
- Vague layer (now under `vql.*`): `vql.fragment.{Fragment, FragmentCheck,
  FragmentViolation}` with `FragmentCheck.check(formula: Formula[FOL],
  fragment: Fragment): Either[FragmentViolation, Unit]`;
  `vql.semantics.VagueSemantics.satisfyingSet` (M2 resolver, not M1R);
  `vql.error.QueryError`; `vql.typed.*`; `vql.parser.VagueQueryParser`;
  `vql.logic.ParsedQuery`.

**M1R lands as two green steps.** Each closes with the full suite green
(commonJVM + server + app + serverIt + BATS C); Step A is green before Step B
begins.

#### Step A — Engine adoption (pin bump + migration; no behaviour change)

- `build.sbt`: `vqlEngineVersion` `"0.10.2"` → `"0.13.1"`; project version
  PATCH bump; first-party cooldown-waiver comment at the pin site (ADR-020 §10,
  `vql-engine`, user-approved 2026-08-09). `APP_VERSION` mirrored to `.env` and
  `.env.irmin`.
- `import fol.* → import vql.*` across the 11-file foladapter surface (map
  below). Foundation imports (`parser`, `logic`) are untouched.
- `AppError.fromQueryError`: change `import fol.error.QueryError as QE` →
  `import vql.error.QueryError as QE`, and delete the four arms for variants
  removed in 0.11.0 — `RelationNotFoundError`, `SchemaError`, `DataStoreError`,
  `PositionOutOfBoundsError`. The remaining 18 arms are unchanged and the match
  stays exhaustive (verified against `vql.error.QueryError` at 0.13.1).
- `FolQueryFailureFromQueryErrorSpec`: drop `import fol.datastore.RelationName`
  (package deleted in 0.11.0), remove the cases constructing the four deleted
  variants, rewrite remaining imports to `vql.*`.

| File | Rewrite |
|---|---|
| `modules/app/src/main/scala/app/state/AnalyzeQueryState.scala` | fol.error→vql.error; fol.parser→vql.parser |
| `modules/common/src/main/scala/com/risquanter/register/http/requests/QueryRequest.scala` | fol.parser→vql.parser; fol.logic→vql.logic; fol.error→vql.error |
| `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala` | fol.error→vql.error (+ prune 4 arms) |
| `modules/server/src/main/scala/com/risquanter/register/foladapter/QueryResponseBuilder.scala` | fol.result→vql.result; fol.typed→vql.typed |
| `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala` | fol.typed→vql.typed |
| `modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala` | fol.logic→vql.logic |
| `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala` | fol.logic/semantics/sampling/typed→vql.* |
| `modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala` | prune + vql.*; drop fol.datastore |
| `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala` | fol.parser/semantics/sampling/typed→vql.* |
| `modules/server/src/test/scala/com/risquanter/register/foladapter/QueryResponseBuilderSpec.scala` | fol.result/typed/quantifier/sampling→vql.* |
| `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala` | fol.typed→vql.typed |

**Engine follow-on — 0.13.1 → 0.14.0 (published 2026-08-14, user-directed).**
0.14.0 prunes 10 more dead `QueryError` variants (`LexicalError`,
`UninterpretedSymbolError`, `ScopeEvaluationError`, `TypeMismatchError`,
`TimeoutError`, `QuantifierError`, `QueryStructureError`, `ResourceError`,
`ConnectionError`, `ConfigError`). Register adaptation: `build.sbt` pin
`"0.13.1"` → `"0.14.0"`; `AppError.fromQueryError` drops those 10 arms, leaving
8 surviving mappings (`ParseError`, `UnknownConstantOrLiteralError`,
`BindError`, `DomainNotFoundError`, `ModelValidationError`, `EvaluationError`,
`ValidationError`, `UnboundVariableError`) — match stays exhaustive;
`FolUnknownSymbol` retained (its `UninterpretedSymbolError` source is gone, but
it still round-trips through `ErrorResponse`); `FolQueryFailureFromQueryErrorSpec`
drops the 10 matching cases. Landed in the same 0.10.18 PATCH as Step B.

#### Step B — Predicate-targeting domain rework (§8.1 signatures made exact)

`iron/OpaqueTypes.scala` — new refined type:
```scala
type TargetingSource = String :| (MinLength[1] & MaxLength[256])
```

`domain/data/TargetingPredicate.scala` (NEW):
```scala
final case class TargetingPredicate private (source: TargetingSource)
object TargetingPredicate:
  /** Boundary validation (cross-compiled — runs in browser and server), two
    * phases (M1R-D2 RULED user 2026-08-14: gate short-circuits, formula checks
    * accumulate):
    *  - GATE (short-circuit, each step needs the prior's output):
    *    1. length-refine `source` into `TargetingSource` (also bounds the parser input);
    *    2. `parser.FOLParser.parse(source)` (Either-returning) — parse failure → ValidationError;
    *  - FORMULA CHECKS (accumulate via `Validation.validateWith`, independent walks over the parsed formula):
    *    3. `FragmentCheck.check(formula, Fragment.Targeting)` — no quantifiers, no function terms;
    *    4. `logic.FOLUtil.fvFOL(formula)` — exactly one free variable;
    *    5. no `mitigate` / `mitigated` atom predicate (self-reference exclusion, §6).
    * The parsed `Formula` is derived state; only `source` is stored/serialized. */
  def create(source: String): Validation[ValidationError, TargetingPredicate]
  given JsonCodec[TargetingPredicate]   // decode = create (boundary validation)
  given Schema[TargetingPredicate]
```

`domain/data/Mitigation.scala` — `MitigationTarget` rework and the override anchor:
```scala
sealed trait MitigationTarget
object MitigationTarget:
  final case class Predicate(predicate: TargetingPredicate) extends MitigationTarget
  // Nodes(Set[NodeId]) REMOVED (§8.4-1 = C)

final case class LeafStage(               // in object MitigationSpec
  transform: RiskLeafTransform,
  overrideBaseStamp: Option[ContentHash],
  overrideAnchor: Option[NodeId]          // required iff `transform` has an Override component
) extends MitigationSpec
```
`Mitigation.create` (signature unchanged) cross-field rules become:
LeafStage + Override component ⇒ `overrideBaseStamp` AND `overrideAnchor` both
defined; LeafStage without an Override ⇒ both empty. The former "target
non-empty" and "Override ⇒ single-node target" rules are deleted (targeting is
a predicate; the single leaf is `overrideAnchor`).

`domain/data/MitigationApplication.scala` — the algebra takes resolved scopes
as a lookup table instead of reading ids off the mitigation:
```scala
def scoped(tree: RiskTree, selection: MitigationSelection,
           resolvedScopes: Map[MitigationId, Set[NodeId]]): Map[NodeId, List[Mitigation]]
def effectiveTree(tree: RiskTree, selection: MitigationSelection,
                  resolvedScopes: Map[MitigationId, Set[NodeId]]): Validation[ValidationError, RiskTree]
def resultTransformFor(nodeId: NodeId, scoped: Map[NodeId, List[Mitigation]]): RiskResultTransform  // unchanged
def applicationRecords(tree: RiskTree, selection: MitigationSelection,
                       resolvedScopes: Map[MitigationId, Set[NodeId]]): List[MitigationApplicationRecord]
```
`scoped` reads each enabled mitigation's node set from
`resolvedScopes.getOrElse(m.id, Set.empty)` (was `m.target match { Nodes(ids) => ids }`);
the `ScopeRestriction.NodesOnly` intersect is unchanged.

`domain/data/RiskTree.scala` — `validateMitigations` drops the
target-resolves-in-index and LeafStage-targets-leaves checks (resolution is
server-side now; the §8.1 stage-domain definition supersedes the write-time
leaf rule); keeps unique ids/names; predicate parse-level validity is already
enforced by `TargetingPredicate.create` at decode. Collection-count bound: see
M1R-D1.

**8.6.1 ADR alignment (M1R delta).**
- ADR-020 — pin exact; first-party cooldown waiver comment at the pin site.
  Engine bump triggers the graalvm-builder rebuild + BATS revalidation
  (ADR-026); "done" includes green BATS C.
- ADR-029 — the targeting predicate is a new parser boundary; **its table row
  lands in this pass** (parsed once by `FOLParser`, never interpolated;
  fragment-membership + single-free-var + `mitigate`/`mitigated` exclusion
  enforced at the boundary).
- ADR-001/010 — `TargetingPredicate.create` validates at the boundary
  (decode = create), errors accumulated; server receives validated types;
  storage re-parse on read is the same boundary (§8.1).
- ADR-033 — parse + fragment check are `Either`-returning; no new catches.
- ADR-018 — `MitigationId` wrapper unchanged; `TargetingSource` is an Iron
  opaque refinement (ADR-001). No new deviations.

**8.6.2 Decisions (M1R) — all ruled.**
- **M1R-D1 — vehicle for the collection-count bounds** (`RiskTree.mitigations`
  ≤ 1000, `TransformPipeline.steps` ≤ 10). **RULED (2026-08-13): validator.**
  The mitigations bound is enforced in `RiskTree.validateMitigations` (already
  the materialization boundary, re-run on every read). The steps bound is
  enforced in `Mitigation.create` (user ruling 2026-08-14: keep the current
  design), the sole materialization boundary for a result-stage pipeline — a
  `ResultStage` exists only inside a `Mitigation`, so every persisted/wire
  pipeline passes through `create`. `create` enforces both ends of the count:
  besides the `MaxPipelineSteps` ceiling it rejects an empty pipeline
  (`MinPipelineSteps = 1`, user ruling 2026-08-29) — an empty ResultStage
  pipeline is a structurally-lossy no-op, so it is rejected at construction.
  It is NOT in a `TransformPipeline` smart
  constructor: `TransformPipeline` is a monoid whose `combine` (`l.steps ++
  r.steps`) must stay total, so a validating constructor there would need an
  unsafe internal path that partly negates the guarantee. The fields stay plain
  `Seq`/`List`. Reifying the bound as
  an Iron `MaxLength` field type is **deferred to §9 Lever 2**, where it is
  applied uniformly across all tree collections (never mitigations-only). The
  "unbypassable" property §8.4-2 cares about is delivered by §9 Lever 1
  (private aggregate constructor), not by the field type — reifying before the
  constructor is closed buys little, and a mitigations-only refinement would be
  a half-refined domain.
- **M1R-D2 — error-reporting shape of `TargetingPredicate.create`.** **RULED
  (user, 2026-08-14): gate short-circuits, formula checks accumulate.** Length
  and parse form a short-circuit gate (each needs the prior's output, and the
  length refine bounds the string the parser sees); once parsed, the three
  independent formula checks (fragment membership, single free variable, no
  mitigation-state predicate) accumulate via `Validation.validateWith`, so an
  authoring form surfaces every formula-level problem in one round. Conforms to
  ADR-010 (accumulate independent, sequence dependent). The earlier plan
  wording "errors accumulated" was imprecise about the gate; this ruling is the
  precise form.

No other open decisions in M1R; plan-wide D4/D5 remain open by design.

**8.6.3 Verification plan (M1R).** Full suite green (the commands under
"Verification plan" above) at the end of BOTH Step A and Step B. New/changed
tests:
- `TargetingPredicateSpec` (NEW): accept a well-formed predicate; reject blank
  and > 256 chars; reject a quantifier; reject a function term; reject > 1 free
  variable; reject `mitigate` / `mitigated`.
- `MitigationEntitySpec`: retargeted from `Nodes` to `Predicate` +
  `overrideAnchor` cross-field rules.
- `MitigationApplicationSpec`: `scoped` / `effectiveTree` driven by a
  `resolvedScopes` lookup table.
- `FolQueryFailureFromQueryErrorSpec`: covers only the surviving `QueryError`
  variants (8 after the 0.14.0 prune — see the engine follow-on note below);
  green under `vql.error`.
- BATS C after the engine bump.

**8.6.4 Review findings & dispositions (routine + scoped complex review, 2026-08-14).**
Both review tiers ran on the M1R diff. Dispositions:
- **Done in M1R:**
  - *Finding 6 (free-var message):* the "more than one free variable" rejection
    now hints that unquoted words are variables and literal values must be
    quoted. Test added.
  - *Finding 1 + Finding 4 (`unmitigated` gap, case-sensitivity) — user ruling
    2026-08-14:* `reservedPredicates` now includes `unmitigated`, matched
    case-insensitively, so `unmitigated(x)` / `Mitigated(x)` are rejected at
    authoring alongside `mitigate`/`mitigated`. This completes the correctness
    of M1R's own reserved-name check (it was arbitrary while `mitigated` was
    reserved but its complement was not). The general mechanism lives at M3:
    the KB marks its own predicates non-targetable and supersedes this
    hardcoded set. Tests added.
- **Resolved by deletion (user ruling 2026-08-14):** Findings 2 and 5 both
  concerned the worsening-override diagnostic; it is deleted in full (code,
  tests, plan/doc references). `overrideAnchor` is retained for M2 staleness.
- **Deferred to M2/M4 — REQUIRED, not optional (user ruling 2026-08-14):**
  - *Finding 3 → M2/M4:* `MitigationApplicationRecord`'s derived codec
    re-validates nothing, so a tampered record (e.g. an 11-step pipeline)
    decodes cleanly. Latent today — nothing decodes these records from an
    untrusted source; they are display/provenance only. **Required deliverable
    of whichever phase first gives the record an inbound decode path (client
    resubmit or persist-and-reload):** a validating decoder (`mapOrFail`
    re-running the two spec rules — step limit and Override stamp/anchor).
    Landing that decode path without this re-check is a defect, not a choice.
- **No change (ruled design) — user ruling 2026-08-14, D1 = Option A:** the
  targeting boundary stays structural; typeless atoms (`eq(x, x)` select-all,
  `x > 5`, `named(x, "Ransomware")`) are accepted at authoring, and sort errors are
  caught by the typed bind at M3 resolution — which reuses the existing
  `satisfyingSet` + KB path, no new checker (§8.4-3 enforcement-locus ruling;
  P-1 bind-time sort rule). Early authoring-time feedback, if wanted, is an M3
  server-side validate round-trip (the form asks the server), never a
  client-side duplicate of the KB. Confirmed by the scoped review: the fragment
  grammar already excludes every function/arithmetic term.

### 8.7 Carried-forward rulings and cleanups (2026-08-14)

Rulings made in session 2026-08-14 that bind later phases of this plan; each
is incorporated at the named elevation and must not be re-derived or reopened
there.

1. **KB identity carrier — binds the M2 elevation.** RULED (user,
   2026-08-14): the §6 stable-id reconciliation carries the node id through
   the engine as the **typed `NodeId`** — domain elements become
   `Value(Asset, nodeId)` and register provides `given Extract[NodeId]` —
   not as the raw ULID String. Not in tension with engine ADR-015's
   rejection of typed carriers: that rejection fixed the ENGINE's container
   (`Value.raw` stays `Any`); this ruling decides only which JVM object
   register stores in that field, via the consumer extension mechanism
   ADR-015 itself defines. Engine ADR-016 (Carrier witness) is unimplemented
   in vql 0.13.1 and is NOT a prerequisite. (Confirmed settled — no timing
   decision — as §8.8 M2-D3a.)
2. **`QueryResponseBuilder` payload projection — folds into the same M2
   rework.** The current projection matches `v.raw` against `String` and
   duplicates the `Asset` sort declaration held by `RiskTreeKnowledgeBase`.
   The id reconciliation supersedes both: project by sort filter +
   `extract[NodeId]`, with one shared sort declaration. No standalone fix
   before M2.
3. **`Mitigation` sort identity carrier — binds the M3 elevation.** The M3
   KB schema's `Mitigation` sort follows the item-1 pattern by convention:
   typed `MitigationId` payload + register-provided
   `given Extract[MitigationId]`. Convention application, not a new
   decision.
4. **Probability-type naming cleanup — boyscout scope of the first phase
   whose file inventory covers `iron/OpaqueTypes.scala`,
   `iron/ValidationUtil.scala`, `RiskNode.scala` (the §9 hardening phase is
   the natural host).** RULED (user, 2026-08-14): rename the open-interval
   metalog-only type `Probability` → **`MetalogPercentile`** (with
   `ValidationUtil.refineProbability`; it has no production caller — decide
   rename vs delete at that pass); fix the stale `RiskNode` scaladoc that
   documents the leaf field as `Probability (0<p<1)` when the field is
   `OccurrenceProbability` [0,1]; reword `OccurrenceProbability`'s
   "semantically distinct from Probability" comment to describe the interval
   difference. Explicitly ruled OUT (do not reopen): a general
   `EventProbability` rename, nominal occurrence-vs-exceedance separation,
   and typing `probOfExceedance` (stays `Double`) — occurrence and
   exceedance probability are the same semantic type; roles live in
   field/method names.

### 8.8 M2 resolver-edge rulings (2026-08-15)

Decisions from the M2 scope-resolution session. Labelled `M2-D*` to avoid
collision with §4's `D*` and §8.6's `M1R-D*`. They bind the pending M2
implementation-grade elevation; exact signatures are written there, not here.

- **M2-D1 — scope-resolution placement. RULED (user, 2026-08-15): Option 1 —
  the service resolves scope.** A new `MitigationScopeResolver` turns each
  mitigation's targeting predicate into a `Set[NodeId]` via the engine's
  `satisfyingSet` over a **results-free KB** (targeting references structure
  and identity only, never simulation output, so resolution runs before any
  simulation). Resolution is selection-independent, so it is memoized on tree
  version identity `(WorkspaceId, TreeId, BranchRef, CommitHash)` — head-only
  per §8.4-5. The resolved `Map[MitigationId, Set[NodeId]]` is consumed at the
  resolver edge (§8.2) and is exactly what `MitigationApplication.scoped` /
  `effectiveTree` already take as of §8.6.

- **M2-D2 — resolver output shape. RULED (user, 2026-08-15): a `ScopeOutcome`
  coproduct; `toEither` + wrapper projections only (no `map`/`flatMap`).**
  Per-mitigation success/failure isolation (security-review F3): one
  mitigation's unresolvable predicate does not fail the whole resolution. The
  service partitions the outcome into resolved scopes before calling the §8.6
  algebra, so `MitigationApplication`'s signatures do not change.

- **M2-D3a — Asset identity carrier. SETTLED (not a decision): id-based.** The
  KB carries the typed `NodeId` (`Value(Asset, nodeId)` + `given
  Extract[NodeId]`), per §6's stable-id requirement and §8.7 item 1 — both
  already ruled id-based, so there was never a name-vs-id choice. Predicates
  reference node ids by construction, so name-based scope drift is not a
  representable state. There is no timing choice: the plan ships as one
  delivery, so "carrier at M2 vs later" is not a ruling — the carrier is built
  wherever it fits best for testability, naturally alongside the resolver
  (its first consumer).

- **M2-D3b — duplicate-node-name merge guard. RULED (user, 2026-08-15): A + B**
  (built with §9 Lever 1 — see below). Byte-level Irmin merge is per storage path and cannot see
  a cross-path invariant like global node-name uniqueness, so two branches can
  each add a differently-pathed node with the same name and the merge succeeds
  silently. The guard lives register-side (Irmin cannot host it): **A** — a
  pre-merge scan over the already-fetched branch blobs rejects with a
  descriptive `MergeConflict` when the union would duplicate a name; **B** — a
  post-merge validate-and-revert that runs the merged tree through
  `RiskTree.fromNodes` and reverts on failure. B is the general net for any
  cross-path invariant `fromNodes` enforces, so its completeness tracks
  `fromNodes`' invariant set — which is why node-name uniqueness must be
  *added* to `fromNodes`, and why B rides **§9 Lever 1** (fromNodes as the sole
  construction gate). A is the fast, specific early reject.

- **M2-D4 — resolver rename. RULED (user, 2026-08-15): `RiskResultResolver` →
  `CachedResultResolver`**, a separate mechanical change. `Simulator` is the
  Monte-Carlo engine that produces `RiskResult`; this type is the
  content-addressed cache + tree recursion + portfolio aggregation layer over
  it, and the new name says so. §7.2.2's use of the old name is stale pending
  the rename.

- **M2-D5 — engine carrier mechanism for the node sort. RECORDED (2026-08-15):
  the concrete registration, so the M2 elevation builds it rather than
  re-deriving it.** The KB carries each node as `Value(nodeSort, nodeId)` — the
  engine's `raw: Any` field holds the register `NodeId` object (M2-D3a / §8.7
  item 1), never a bare string. Three register-supplied pieces make it work,
  all reusing existing engine hooks (no engine change):
  1. **Domain.** `model.domains(nodeSort) =
     tree.index.nodes.keys.map(id => Value(nodeSort, id)).toSet` — one
     `NodeId`-carrying element per node.
  2. **Constant/literal path (superseded by §8.12 — the single id-then-name
     "guessing" validator described below is retired; the `Node` sort now has a
     name-only validator, and node-by-id / node-by-name are the explicit
     `has_id` / `named` predicates over dedicated value sorts. The claims that
     still hold: node references bind through `LiteralRef` to
     `Value(nodeSort, nodeId)` — a `NodeId`, not a string — via literal
     validators, never as registered constants; an unresolvable token fails at
     BIND → 400, never a silent empty scope).** Register a
     node-sort literal validator
     `nodeSort -> (s => NodeId.fromString(s).toOption.orElse(nameToId.get(s)))`
     in the `TypeCatalog`
     (`literalValidators: Map[TypeId, String => Option[Any]]` — the same hook
     that turns `"1000000"` into a typed `Loss`). Both a quoted id
     (`x = "<ulid>"`) and a quoted node name (`descendant_of(x, "Servers")`)
     bind through `LiteralRef` to `Value(nodeSort, nodeId)` — a `NodeId`, not a
     string: the validator parses the token as a ULID first, then falls back to
     a name lookup (so a name that is itself ULID-shaped reads as an id — a
     non-issue for human names). The name lookup is deterministic because node
     names are unique (M2-D3b adds that invariant to `RiskTree.fromNodes`), so a
     name resolves to exactly one id. A token that is neither a valid id nor a
     known node name fails at BIND (`TypeCheckError.UnparseableConstant` → 400),
     not silently as an empty scope. Neither ids nor names are registered as
     `catalog.constants` (that path yields a string-carried `ConstRef`); the
     validator is the sole node-constant path.
  3. **Node-identity predicate (renamed `=` → `eq` by §8.12).** Register the
     node-identity relation — `SymbolName("eq") -> PredicateSig(List(nodeSort,
     nodeSort))` with dispatcher impl
     `for { a <- args(0).extract[NodeId]; b <- args(1).extract[NodeId] } yield a == b`,
     backed by a register-provided `given Extract[NodeId]` (ADR-015 §2 consumer
     extension). (§8.12 replaces the infix `=` symbol with the prefix `eq` and
     adds `named` / `has_id` for node-by-name / node-by-id references; see §8.12
     for the current catalog.) Ordering operators (`< <= > >=`) are NOT
     registered on the node sort — nodes have identity, not order; ordering stays
     on the scalar sorts (`gt_loss`/`gt_prob` already cover it). `eq` is the only new operator
     targeting needs.

  **Structural predicates re-key id-native (simplification).** The
  `leaf`/`child_of`/`descendant_of`/`leaf_descendant_of` dispatchers currently
  back their predicates with name-keyed sets built by translating the id-keyed
  `TreeIndex` down to names (`leafNames`, `childrenByName`, `descendantsByName`).
  With the id carrier they key on `NodeId` directly — which `TreeIndex` already
  holds natively (`leafIds`, `children`, `descendants`) — so this rework DELETES
  the name-translation layer rather than adding one. Node-constant arguments
  (`descendant_of(x, "…")`) arrive as `NodeId` via the point-2 validator, so the
  comparison is id-to-id.

  **Open-world consistency (no engine type knowledge added).** The engine never
  holds a register type: `Value.raw` is `Any`, the literal validator returns
  `Any`, and all interpretation (`=` implementation, `Extract[NodeId]`) is
  injected by register. Engine-internal domain-set dedup relies only on `NodeId`'s
  universal `equals`/`hashCode`, never on knowing the type. So "storing NodeId in
  the engine" is the intended use of ADR-015's carrier/extract mechanism, not a
  breach of the open-world design — it adds nothing to the engine's own type
  vocabulary.

  **Sort rename `Asset` → `Node` (user-approved 2026-08-15).** Internal-only label
  (no wire/DTO/user surface): the sort holds risk nodes — leaves AND portfolios —
  and §6 reserves "asset" for the future asset-graph concept, so the current label
  is a borrowed misnomer. Applied in the same KB rework; the plan's
  `Value(Asset, …)` wording (§8.7 item 1, M2-D3a) reads `Value(Node, …)` after it.

  **Name-constant reconciliation (RESOLVED — required for screening, not only
  targeting).** The id carrier is not targeting-only: §8.7 item 2 flips the
  screening output builder (`QueryResponseBuilder`) to `extract[NodeId]`, so the
  screening KB's domain is id-carried too, and screening's existing "quoted
  node-name literal" feature (`child_of(x, "IT Risk")`) would break unless names
  resolve to ids. The id-or-name validator in point 2 above closes this for both
  paths uniformly; M2-D3b's node-name uniqueness is therefore load-bearing for
  the screening query path, not only for merge safety. Screening users type names
  (there is no picker), so this reconciliation is mandatory, not optional.

**Merge-control finding (2026-08-15) — context for M2-D3b and §8.4-4.** Register
does not define Irmin's merge resolution: the patched backend
(`irmin-graphql-3.11.0-merge-conflict.patch`) only *surfaces* a conflict as a
typed error; the 3-way merge itself is per-path server-side OCaml. A conflict
Irmin reports (both branches touched one path) is refused fail-closed with the
target head untouched — safe even for an unanticipated conflict. A cross-path
invariant violation (duplicate names) is the opposite: Irmin reports success,
so register must detect it (M2-D3b A/B). Defining a custom Irmin merge function
is possible but still per-value, so it cannot enforce a whole-tree invariant;
it is not pursued. The richer interactive conflict-resolution UI (one-click
keep-main/keep-scenario, parameter-average) is a deferred convenience item of
the **milestone-2b** merge workstream (PLAN-UI-MILESTONE-2B §8 / its scratch
tracker), not of this plan, and does not bear on M2.

### 8.9 M2 KB id-carrier elevation (Asset → Node) — implementation-grade (2026-08-15)

> **Amended by §8.12 (2026-08-25):** this section's `=` predicate and the
> id-or-name "guessing" node-sort literal validator are superseded — `=` is
> retired for prefix `eq`, node-by-name and node-by-id become the explicit
> `named` / `has_id` predicates, and the `Node` validator is narrowed to
> name-only. The id-carrier / `Asset → Node` rename and the response-builder
> projection below are unchanged. Read §8.12 for the current catalog shape.

First buildable, testable slice of M2: turn the `RiskTreeKnowledgeBase` sort
that currently carries node **names as `String`** into one that carries the
typed **`NodeId`**, rename the sort `Asset` → `Node`, register `=` on it, and
flip the screening output projection to the id carrier. This makes the exact
code for §8.7 items 1–2 and §8.8 M2-D3a / M2-D5. No new decisions — every shape
here is already ruled; this section only writes it verbatim so it is G1-covered.
Remaining M2 (resolver `MitigationScopeResolver`, `ScopeOutcome`, storage,
staleness, `CachedResultResolver` rename, M2-D3b `fromNodes` guard) is elevated
in a later continuation; it is not in this slice's scope.

**Files (all already in the M2 File inventory — no inventory change):**
`RiskTreeKnowledgeBase.scala`, `QueryResponseBuilder.scala`, `QueryServiceLive.scala`
(main); `RiskTreeKnowledgeBaseSpec.scala`, `QueryResponseBuilderSpec.scala`,
`BinderIntegrationSpec.scala` (test). `QueryService.scala` is unchanged (its
`evaluate` signature does not move).

**Companion object (new) — carrier + shared sort declaration.** Home for the
one `given Extract[NodeId]` and the single `NodeSort` declaration both the KB
and `QueryResponseBuilder` reference (removes the duplicate sort literal §8.7
item 2 flagged). Imports add `vql.typed.Extract`.

```scala
object RiskTreeKnowledgeBase:

  /** Canonical sort id for tree nodes (leaves and portfolios). Shared with
    * `QueryResponseBuilder` so the id projection uses one declaration. */
  val NodeSort: TypeId = TypeId("Node")

  /** Consumer carrier for the node sort (ADR-015 §2): the engine holds the
    * register `NodeId` opaquely in `Value.raw`; this lifts it back out. */
  given Extract[NodeId] with
    def apply(v: Value): Either[String, NodeId] = v.raw match
      case id: NodeId => Right(id)
      case other      =>
        Left(s"Extract[NodeId]: expected NodeId carrier for sort '${v.sort.value}', got $other")
```

**Class members — replacements.** `import RiskTreeKnowledgeBase.given` at the
top of the class body. `assetSort` becomes `nodeSort` sourced from the companion:

```scala
  val nodeSort: TypeId        = RiskTreeKnowledgeBase.NodeSort
  val lossSort: TypeId        = TypeId("Loss")
  val probabilitySort: TypeId = TypeId("Probability")
  val boolSort: TypeId        = TypeId("Bool")
```

`nameToNodeId` (public, unfiltered, name→id) is **removed** — its only consumer
was the old `QueryResponseBuilder` reverse lookup. It is replaced by `nameToId`,
the reserved-filtered map the node-sort literal validator's name branch uses.
This preserves today's behaviour: reserved-symbol names stay unbindable as node
constants, and `nameCollisions` still reports them. Placed after
`reservedFolNames` / `nameCollisions` (which are unchanged), so its use of
`reservedFolNames` is initialised first.

```scala
  /** Node name → NodeId for the node-sort literal validator's name branch.
    * Excludes reserved-symbol names (see `nameCollisions`); last-write-wins on
    * duplicate names until `RiskTree.fromNodes` enforces uniqueness (M2-D3b). */
  val nameToId: Map[String, NodeId] =
    tree.index.nodes.iterator.collect {
      case (id, node) if !reservedFolNames.contains(node.name.value) => node.name.value -> id
    }.toMap
```

`nameToResult` (name-keyed result map) and `nodeNameConstants` (the
`Map[String, TypeId]` constants) are **removed**: results dispatch by `NodeId`
directly (the `results` param is already `Map[NodeId, LossDistribution]`), and
node constants now bind through the literal validator, not `catalog.constants`.

**Catalog.** `constants` empties; every `assetSort` becomes `nodeSort`; `=` is
added on the node sort; the node-sort literal validator is added (id first, then
name fallback — a name that is itself ULID-shaped reads as an id, a non-issue for
human names; a token that is neither fails at bind → 400, not a silent empty
scope).

```scala
  val catalog: TypeCatalog = TypeCatalog.unsafe(
    types = Set(
      TypeDecl.DomainType(nodeSort),
      TypeDecl.ValueType(lossSort),
      TypeDecl.ValueType(probabilitySort),
      TypeDecl.ValueType(boolSort)
    ),
    constants = Map.empty,
    functions = Map(
      SymbolName("p95") -> FunctionSig(List(nodeSort), lossSort),
      SymbolName("p99") -> FunctionSig(List(nodeSort), lossSort),
      SymbolName("lec") -> FunctionSig(List(nodeSort, lossSort), probabilitySort)
    ),
    predicates = Map(
      SymbolName("leaf")               -> PredicateSig(List(nodeSort)),
      SymbolName("portfolio")          -> PredicateSig(List(nodeSort)),
      SymbolName("child_of")           -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("descendant_of")      -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("leaf_descendant_of") -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("gt_loss")            -> PredicateSig(List(lossSort, lossSort)),
      SymbolName("gt_prob")            -> PredicateSig(List(probabilitySort, probabilitySort)),
      SymbolName("=")                  -> PredicateSig(List(nodeSort, nodeSort))
    ),
    literalValidators = Map(
      nodeSort        -> ((s: String) => NodeId.fromString(s).toOption.orElse(nameToId.get(s))),
      lossSort        -> ((s: String) => s.toLongOption.filter(_ >= 0L)),
      probabilitySort -> ((s: String) => s.toDoubleOption.filter(d => d >= 0.0 && d <= 1.0))
    )
  )
```

Covariance widens each `Option[NodeId]` / `Option[Long]` / `Option[Double]` to
the declared `String => Option[Any]`; if inference balks on the mixed map, the
node lambda is annotated `: Option[Any]` (fallback only).

**Dispatcher — id-native.** The name-keyed sets (`leafNames`, `portfolioNames`,
`childrenByName`, `descendantsByName`) are **deleted**; predicates key on `NodeId`
via `TreeIndex`'s native `leafIds` / `children` / `descendants`. `lookupResult`
keys on `NodeId`:

```scala
  private val leafIdSet: Set[NodeId] = index.leafIds
  private val portfolioIds: Set[NodeId] =
    index.nodes.collect { case (id, _: RiskPortfolio) => id }.toSet

  private def lookupResult(id: NodeId, ctx: String): Either[String, LossDistribution] =
    results.get(id).toRight(s"$ctx: no simulation result for node '${id.value}'")

  val dispatcher: MapDispatcher = MapDispatcher(
    functions = Map(
      SymbolName("p95") -> { args =>
        for id <- args(0).extract[NodeId]; result <- lookupResult(id, "p95")
        yield percentile(result, 0.95)
      },
      SymbolName("p99") -> { args =>
        for id <- args(0).extract[NodeId]; result <- lookupResult(id, "p99")
        yield percentile(result, 0.99)
      },
      SymbolName("lec") -> { args =>
        for
          id        <- args(0).extract[NodeId]
          threshold <- args(1).extract[Long]
          result    <- lookupResult(id, "lec")
        yield result.probOfExceedance(threshold)
      }
    ),
    predicates = Map(
      SymbolName("leaf")      -> { args => args(0).extract[NodeId].map(leafIdSet.contains) },
      SymbolName("portfolio") -> { args => args(0).extract[NodeId].map(portfolioIds.contains) },
      SymbolName("child_of") -> { args =>
        for child <- args(0).extract[NodeId]; parent <- args(1).extract[NodeId]
        yield index.children.getOrElse(parent, Nil).contains(child)
      },
      SymbolName("descendant_of") -> { args =>
        for desc <- args(0).extract[NodeId]; ancestor <- args(1).extract[NodeId]
        yield (index.descendants(ancestor) - ancestor).contains(desc)
      },
      SymbolName("leaf_descendant_of") -> { args =>
        for desc <- args(0).extract[NodeId]; ancestor <- args(1).extract[NodeId]
        yield
          val descs = index.descendants(ancestor) - ancestor
          descs.contains(desc) && leafIdSet.contains(desc)
      },
      SymbolName("gt_loss") -> { args =>
        for a <- args(0).extract[Long]; b <- args(1).extract[Long] yield a > b
      },
      SymbolName("gt_prob") -> { args =>
        for a <- args(0).extract[Double]; b <- args(1).extract[Double] yield a > b
      },
      SymbolName("=") -> { args =>
        for a <- args(0).extract[NodeId]; b <- args(1).extract[NodeId] yield a == b
      }
    )
  )
```

**Model.** Domain elements carry `NodeId`:

```scala
  private val nodeDomain: Set[Value] =
    tree.index.nodes.keys.map(id => Value(nodeSort, id)).toSet

  val model: RuntimeModel = RuntimeModel(
    domains = Map(nodeSort -> nodeDomain),
    dispatcher = dispatcher
  )
```

**`QueryResponseBuilder`.** The `nodeIdLookup` parameter is removed; node values
are projected by sort filter + `extract[NodeId]`. Imports drop the now-unused
local `TypeId`/`assetSort` and add `vql.typed.extract`.

```scala
object QueryResponseBuilder:
  import RiskTreeKnowledgeBase.given

  def from(output: EvaluationOutput[Value], queryEcho: String): QueryResponse =
    val matchingIds = output.satisfyingElements.toList
      .filter(_.sort == RiskTreeKnowledgeBase.NodeSort)
      .flatMap(_.extract[NodeId].toOption)
    QueryResponse(
      satisfied         = output.satisfied,
      proportion        = output.proportion,
      rangeSize         = output.rangeElements.size,
      sampleSize        = output.rangeElements.size,
      satisfyingCount   = output.satisfyingElements.size,
      satisfyingNodeIds = matchingIds,
      queryEcho         = queryEcho
    )
```

**`QueryServiceLive`.** One call-site change; the `kb.nameToNodeId` argument is
gone:

```scala
        response = QueryResponseBuilder.from(output, queryText)
```

The `nameCollisions` diagnostic block (lines ~68–74) is unchanged.

**Doc/comment sweep (in the same edit):** the KB class scaladoc sort table
(`Asset | String` → `Node | NodeId`, "node identity (leaves and portfolios)"),
the function/predicate signature tables (`Asset` → `Node`, add a `= | (Node,
Node) | NodeId equality` row), the `reservedFolNames` scaladoc (it now filters
`nameToId`, not `catalog.constants`), the `nameCollisions` scaladoc ("building
`nameToId`" not "building `catalog.constants`"), and the `QueryResponseBuilder`
scaladoc ("Node-sorted values projected to `NodeId` via `extract[NodeId]`").

**ADR alignment.** ADR-015 §2 — compliant (consumer `given Extract[NodeId]`, no
engine change). ADR-018 — compliant (carrier is the nominal `NodeId`). ADR-001 —
improved: `=` and structural predicates compare typed `NodeId`, the only raw
`String` is the literal-validator parse input (the sanctioned boundary). ADR-010
— unchanged (`Extract` returns `Either`). ADR-029 — improved: node constants are
whitelist-constrained to real ids / known names at bind. No deviations.

**Reserved-name sync (`=`).** `reservedFolNames` is defined as the union of the
catalog's function and predicate symbol names (`FolSymbols.reservedNames`, the
single source of truth also used by the DTO gate `requireNoReservedNames`); the
C4 test asserts that equality. Registering `=` adds it to `catalog.predicates`,
so `=` is added to `FolSymbols.reservedNames` too — keeping the two sets equal
and C4 passing untouched. Consequence: a node literally named `=` is excluded
from `nameToId` / rejected by the DTO gate, same as any other symbol name (no
real node carries that name). `FolSymbols.scala` is in the File inventory for
this slice.

**Decision-trigger check.** #4/#5 (the `QueryResponseBuilder.from` signature and
the KB behaviour rework) are covered verbatim by §8.7 items 1–2 and §8.8
M2-D3a/M2-D5 — plan execution, not an unplanned trigger. #8: the three specs are
rewritten to assert on `NodeId` instead of `String`; same behaviours, no
assertion weakened or removed. No open decisions.

**Determinism note.** The name→id branch is last-write-wins on duplicate names
until M2-D3b adds node-name uniqueness to `RiskTree.fromNodes` (later M2 slice) —
identical to today's `nameToNodeId`, so this slice introduces no regression.

**Verification plan.**

```bash
sbt server/compile
sbt server/test                 # RiskTreeKnowledgeBaseSpec, QueryResponseBuilderSpec,
                                # BinderIntegrationSpec green
sbt 'commonJVM/test; server/test'
sbt serverIt/test               # unaffected by this slice; must stay green
run_bats tests/bats/suite-c-in-memory.bats   # fast gate after code change
```

New/updated test cases: `=` node equality binds and evaluates
(`x = "<ulid>"` true for that node, false for others); a quoted node-name
literal resolves to its id (`child_of(x, "IT Risk")`); a reserved name stays
unbindable (`nameCollisions` non-empty, bind fails); structural predicates
(`leaf`/`child_of`/`descendant_of`/`leaf_descendant_of`) return the same sets as
before over the id carrier; `p95`/`p99`/`lec` dispatch by id; the response
builder projects node values to `NodeId` and drops non-node sorts.

### 8.10 M2 slice status & pickup map (2026-08-15)

M2 ships as one delivery; the slices below are internal testability sequencing,
not separate releases. This is the resume list for a cold session: what is
elevated to exact signatures, what is only ruled, and where each lives. Update
the status column as slices land.

| # | Slice | Status | Elevation / ruling anchor |
|---|-------|--------|---------------------------|
| 1 | **KB id-carrier (Asset → Node)** — sort rename, `NodeId` carrier, `given Extract[NodeId]`, node-reference predicates (`eq` / `named` / `has_id`, §8.12; `=` and the id-or-name guessing validator retired), name-only `Node` literal validator, id-native structural dispatchers, `QueryResponseBuilder`/`QueryServiceLive` flip | **Landed — §8.11 (0.10.19) + §8.12 (0.10.21)** | §8.9 + §8.12 (exact code); §8.7 items 1–2; §8.8 M2-D3a, M2-D5 |
| 2 | **`MitigationScopeResolver` + `ScopeOutcome`** — results-free KB; `satisfyingSet` turns each targeting predicate into `Set[NodeId]`; per-mitigation success/failure isolation; memoized on `(WorkspaceId, TreeId, BranchRef, CommitHash)`; output `Map[MitigationId, Set[NodeId]]` | **Landed — §8.13 (0.10.22)** | §8.8 M2-D1, M2-D2; §8.2 resolver edge; §8.13 |
| 3 | **`RiskResultResolver` → `CachedResultResolver` rename + resolver-edge wiring** — edge takes `resolvedScopes: Map[MitigationId, Set[NodeId]]` (not `MitigationSelection`); result-stage transforms applied at the edge, never cached (D3) | Ruled; **exact signatures pending** (§7.2.2 stale box reconciled here) | §8.8 M2-D4; §7.2.2; §8.6 algebra |
| 4 | **Storage — one Irmin path per mitigation** — `WorkspaceStoragePaths.treeMitigations`; `RiskTreeRepositoryIrmin` read/write; whole-subtree replacement (DD-7); byte-level conflict pre-check (ADR-032) | Ruled; **exact signatures pending** | §7.2.1 |
| 5 | **`MitigationStaleness.staleOverrides`** — diagnostic-only override-staleness set (frozen-opinion semantics; resolution ignores it); stamp writing on the tree-PUT path | Ruled; **exact signatures pending** | §7.2.2a (OD-6) |
| 6 | **M2-D3b duplicate-node-name merge guard (A + B)** — pre-merge scan (A) + post-merge `fromNodes` validate-and-revert (B); **adds node-name uniqueness to `RiskTree.fromNodes`**, which also makes slice 1's name→id branch deterministic (removes its last-write-wins caveat) | Ruled; **rides §9 Lever 1**, exact signatures pending | §8.8 M2-D3b; §9 Lever 1; §8.4-4 |

Cross-slice dependency to remember: slice 1 ships with a last-write-wins name→id
map (matching today's behaviour); slice 6 tightens it to deterministic by adding
the `fromNodes` uniqueness invariant. Slice 1 does not block on slice 6 — the
caveat is documented in §8.9's determinism note.

Files for slices 3–6 are already in the M2 File inventory (they edit or rename
existing files). Slice 2 is the exception: its resolver, per-workspace registry,
and spec are genuinely new files, added to the inventory by §8.13. Each pending
slice gets its own §8.x implementation-grade elevation (exact signatures, per the
Plan Quality Gate) presented before its first source edit, exactly as §8.9 was.

### 8.11 M2 bind-error → UNKNOWN_REFERENCE classification + vql 0.16.0 re-pin — implementation-grade (2026-08-19)

Elevates the last piece of the M2 KB id-carrier workstream: collapse the
two-tier bind-error handling into the single `fromQueryError` mapper now that
vql-engine 0.16.0 exposes each bind error's sort name. An unknown quoted node
name maps to HTTP 400 `UNKNOWN_REFERENCE` (was `BIND_FAILED`), which turns the
serverIt `QueryEndpointSpec` H3 test green. **Option A (ruled 2026-08-18):** the
widened `FolUnknownReference` carries the engine's rendered messages and
round-trips through the existing `ErrorDetail` **message** slot, mirroring
`FolBindFailure` — no wire-contract redesign (PLAN-ERROR-REFACTORING §5 A/B/C is
NOT adopted). The cleanup this creates is recorded in PLAN-ERROR-REFACTORING §11.

**Engine facts (vql 0.16.0, `vql/error/QueryError.scala`):**
`QueryError.BindError(details: List[BindErrorDetail])`, with
`messages: List[String] = details.map(_.rendered)`. `BindErrorDetail` is an enum
in `vql.error` (primitives only — the error layer must not depend on `vql.typed`):
`UnparseableConstant(name, sortName, sourceText, rendered)` and `Other(rendered)`.
An unresolved quoted node name binds to
`UnparseableConstant(name, sortName = "Node", …)`. `TypeCheckError.UnparseableConstant`
(the typed layer) is unchanged at 3 fields, so `BinderIntegrationSpec` B2 is untouched.

#### Exact signatures

```scala
// build.sbt
val vqlEngineVersion = "0.16.0"        // was "0.14.0" (line 39)
// ThisBuild / version := "0.10.19"    // PATCH on landing (bug fix + step)

// modules/common/.../domain/errors/AppError.scala  — object FolQueryFailure

/** Node-sort discriminator: the TypeId.value the engine crosses in
  * BindErrorDetail.UnparseableConstant.sortName when a quoted token failed the
  * node-sort literal validator. The catalog declares TypeId(NodeSortName)
  * indirectly via RiskTreeKnowledgeBase.NodeSort = TypeId("Node"); a drift-guard
  * assertion in RiskTreeKnowledgeBaseSpec binds the two, matching the
  * FolSymbols mirror-plus-drift convention. */
val NodeSortName: String = "Node"

/** Widened from a single name to the engine's rendered messages, one per
  * unresolved node reference. Mirrors FolBindFailure so decode round-trips
  * losslessly through the ErrorDetail message slot. */
final case class FolUnknownReference(messages: List[String])
  extends FolQueryFailure:
  override def getMessage: String =
    s"Unknown reference(s): ${messages.mkString("; ")}"

// fromQueryError — new import + two changed arms
import vql.error.BindErrorDetail

case e: QE.UnknownConstantOrLiteralError =>            // unreachable for register, kept correct
  FolUnknownReference(List(e.message))

case e: QE.BindError =>
  val allNodeUnresolved =
    e.details.nonEmpty && e.details.forall {
      case BindErrorDetail.UnparseableConstant(_, sortName, _, _) => sortName == NodeSortName
      case _                                                      => false
    }
  if allNodeUnresolved then FolUnknownReference(e.messages)
  else                      FolBindFailure(e.messages)   // e.errors accessor is gone at 0.16.0

// modules/common/.../domain/errors/ErrorResponse.scala

// encode dispatch (was: case FolUnknownReference(name) => makeFolUnknownReferenceResponse(name))
case FolUnknownReference(messages) => makeFolUnknownReferenceResponse(messages)

// decode UNKNOWN_REFERENCE arm (was: FolUnknownReference(firstField) — the "query" bug)
case ValidationErrorCode.UNKNOWN_REFERENCE =>
  FolUnknownReference(details.map(_.message))

// builder — now one detail per message, mirroring makeFolBindFailureResponse
def makeFolUnknownReferenceResponse(
  messages: List[String], domain: String = "query", requestId: Option[String] = None
): (StatusCode, ErrorResponse) =
  val details = messages.map(m => ErrorDetail(domain, "query", ValidationErrorCode.UNKNOWN_REFERENCE, m, requestId))
  val message = s"Unknown reference(s): ${messages.mkString("; ")}"
  (StatusCode.BadRequest, ErrorResponse(JsonHttpError(StatusCode.BadRequest.code, message, details)))
```

#### Test changes

```scala
// FolQueryFailureFromQueryErrorSpec (server test) — bindSuite rewritten for the
// 0.16.0 BindError(details) shape (the List[String] constructor is gone) and
// extended with classification cases:
import vql.error.BindErrorDetail
//  (a) all node-unresolved            → FolUnknownReference(messages)
//  (b) node-unresolved + Other(...)   → FolBindFailure (genuine type error dominates)
//  (c) homogeneous non-node (sort "Loss") UnparseableConstant → FolBindFailure
//  existing message-preservation tests re-expressed over details/messages

// ErrorResponseSpec (common test) — add the missing arm, mirroring FolBindFailure:
test("FolUnknownReference roundtrip preserves list losslessly") {
  val messages = List("Unknown reference: 'Foo'", "Unknown reference: 'Bar'")
  val original = FolQueryFailure.FolUnknownReference(messages)
  ErrorResponse.decode(ErrorResponse.encode(original)) match
    case f: FolQueryFailure.FolUnknownReference => assertTrue(f.messages == messages)
    case other => assertTrue(other.isInstanceOf[FolQueryFailure.FolUnknownReference])
}

// RiskTreeKnowledgeBaseSpec (server test) — drift guard for the discriminator:
test("NodeSort.value matches the classifier's NodeSortName") {
  assertTrue(RiskTreeKnowledgeBase.NodeSort.value == FolQueryFailure.NodeSortName)
}
```

#### File inventory (delta)

Already listed in `## File inventory`: `build.sbt`, `AppError.scala`,
`FolQueryFailureFromQueryErrorSpec.scala`, `RiskTreeKnowledgeBaseSpec.scala`.
Added by §8.11: `ErrorResponse.scala`, `ErrorResponseSpec.scala` (both under the
inventory heading above). `AnalyzeQueryState.scala` matches `FolUnknownReference`
by type only — **not** touched. `QueryEndpointSpec.scala` (serverIt H3) asserts
the wire code and goes green unmodified — **not** touched.

#### ADR alignment

- **ADR-028** (VQL query evaluation): classification lives in the single
  `fromQueryError` mapper; no `QueryBinder.bind` bypass. Compliant.
- **ADR-020 §10** (supply chain): 0.16.0 re-pin under the first-party cooldown
  waiver already naming `vql-engine` (user-approved 2026-08-09); exact pin. Compliant.
- **ADR-001 / ADR-010** (validate at the boundary, typed errors): error mapping
  stays at the HTTP edge; unchanged. Compliant.
- **Trigger #4** (case-class field change): `FolUnknownReference` `name: String`
  → `messages: List[String]` — specified verbatim here; approval of this section
  is its echo. **Trigger #8** (test assertions): the `bindSuite` rewrite is forced
  by the 0.16.0 `BindError` shape change and specified verbatim; the roundtrip and
  drift-guard tests are additive. Both covered by this plan — no separate halt.

#### Open decisions

One design note (not a user decision): `NodeSortName` lives in the
`FolQueryFailure` object (common), the catalog keeps `TypeId("Node")`, and a
drift-guard test binds them — matching the existing `FolSymbols` mirror-plus-drift
convention rather than coupling the catalog to the errors package.

Two blockers surfaced at implementation and were ruled 2026-08-21:

- **D1 — Scala.js toolchain mismatch.** vql-engine 0.16.0's Scala.js artifact is
  built with Scala.js 1.22 (`scalajs-library 1.22.0`, IR 1.22); register's linker
  was `sbt-scalajs 1.20.0` (IR up to 1.20), so the `app` module could not link.
  ✅ RULED **Option B**: bump register's `sbt-scalajs` to `1.22.0` in
  `project/plugins.sbt`. Supply chain: 1.22.0 is the latest (`org.scala-js`,
  established publisher), published 2026-06-20 — past the 14-day cooldown; exact
  pin. `sbt-scalajs-crossproject` stays at `1.3.2` unless the link fails.
- **D2 — Maven Central availability.** 0.16.0 was published to Maven Central
  2026-08-21 (both `vql-engine_3` and `vql-engine_sjs1_3`), so the GraalVM/frontend
  Docker builds and CI resolve it. The earlier note that 0.16.0 was already on
  Central was premature; corrected here.

#### Verification plan

```
sbt 'commonJVM/test; server/test'                 # classification + roundtrip + drift guard
sbt app/test                                       # frontend unaffected (type-only match)
sbt "serverIt/testOnly *QueryEndpointSpec"         # H3 → 400 UNKNOWN_REFERENCE
sbt "serverIt/test"                                # full IT tier
run_bats tests/bats/suite-c-in-memory.bats         # smoke
```
Then: version bump PATCH (`0.10.18` → `0.10.19`), mirror `APP_VERSION` into
`.env` and `.env.irmin`; doc-consistency sweep (the `FolUnknownReference` /
`FolBindFailure` doc-comments in `AppError.scala`; PLAN-ERROR-REFACTORING §11).

### 8.12 Retire node-`=`; add `eq` / `named` / `has_id` with specialized node-reference sorts — implementation-grade (2026-08-25)

**Summary.** Three changes to the `RiskTreeKnowledgeBase` catalog, all
register-only (zero vql-engine change): (1) retire the infix `=` predicate and
replace it with the prefix `eq: (Node, Node)` — same relation (node identity
between two variables), renamed to obey the "every predicate is a written-out
prefix symbol" discipline; (2) add two explicit node-reference predicates,
`named(x, "IT Risk")` and `has_id(x, "01BX…")`, each backed by its own value
sort with a dedicated literal validator, so a node can be pinned by name or by
id unambiguously in both the screening and targeting sublanguages; (3) narrow
the `Node`-sort literal validator to **name-only** — a quoted literal in a
structural-predicate node slot (`child_of(x, "IT Risk")`) resolves as a node
name; an id in such a slot no longer binds and must be written `has_id`.

Because `named`'s literal lives in its own `NodeNameLiteral` value sort, the
§8.11 bind-error classifier is extended (Option B, ruled 2026-08-25) so a
nonexistent node named through `named` still reports as HTTP 400
`UNKNOWN_REFERENCE`, not `BIND_FAILED` — the same category `child_of(x,
"Nonexistent")` already returns for the identical user mistake.

This supersedes §8.9's two catalog decisions (registering `=`, and the
id-or-name "guessing" node validator) and the §8.10 slice-1 row's `=` / literal
mention; both are amended here. The engine-side rationale (a literal carries no
sort of its own; its sort is decided by the argument slot at bind time; the
engine deliberately withholds a `String` `LiteralParser`, so a String-backed
sort is an explicit per-sort consumer choice — engine T-012) was settled in the
2026-08-23/25 design discussion. No new engine capability is required: `named` /
`has_id` are ordinary registered predicates whose second argument is a
consumer-declared value sort.

**Why three identical dispatcher bodies are correct.** `eq`, `named`, and
`has_id` all reduce at eval time to `NodeId` equality, because by the time an
argument reaches the dispatcher the bind-time literal validator has already
resolved the quoted string to a `NodeId` carrier. The three predicates differ
**only** at bind time, in which validator accepts the literal and how: `eq` and
`named` accept a known node name (`nameToId.get`), `has_id` accepts a
well-formed id (`NodeId.fromString`). An unresolvable literal fails the bind
(`UnparseableConstant` → HTTP 400), never a silent empty result. This is the
"specialized per-slot validator" design: the predicate's meaning lives in the
argument sort and its validator, not in a runtime branch.

**Consequence recorded (not a defect).** Under the name-only `Node` validator,
`named`'s value sort validator (`nameToId.get`) is identical to the `Node`
sort's own literal validator, so `named(x, "IT Risk")` and `eq(x, "IT Risk")`
are equivalent in the screening language. Both are kept deliberately: `named` /
`has_id` are the canonical, explicit node-pinning predicates the targeting
sublanguage and the UI node-picker emit; `eq` is node-to-node identity between
two variables (its sole non-redundant use — e.g. "two distinct leaves under a
portfolio": `leaf_descendant_of(a,p) /\ leaf_descendant_of(b,p) /\ not eq(a,b)`).

#### Exact signatures

**Companion object — two new value-sort declarations** (beside `NodeSort`):

```scala
object RiskTreeKnowledgeBase:

  val NodeSort: TypeId = TypeId("Node")

  /** Value sort for a node reference written as a quoted node NAME literal
    * (`named(x, "IT Risk")`). Carrier: NodeId — the name is resolved to the
    * node's id at bind time by the literal validator (`nameToId.get`). A
    * ValueType (ADR-014): it flows through an argument slot and is never
    * quantified over. */
  val NodeNameLiteralSort: TypeId = TypeId("NodeNameLiteral")

  /** Value sort for a node reference written as a quoted node ID literal
    * (`has_id(x, "01BX…")`). Carrier: NodeId — the id string is parsed by
    * `NodeId.fromString` at bind time. */
  val NodeIdLiteralSort: TypeId = TypeId("NodeIdLiteral")

  given Extract[NodeId] with            // unchanged — carrier is NodeId for all three sorts
    def apply(v: Value): Either[String, NodeId] = v.raw match
      case id: NodeId => Right(id)
      case other      =>
        Left(s"Extract[NodeId]: expected NodeId carrier for sort '${v.sort.value}', got $other")
```

**Class members — sort declarations** (`boolSort` removed — OD-A, ruled remove):

```scala
  val nodeSort: TypeId            = RiskTreeKnowledgeBase.NodeSort
  val lossSort: TypeId            = TypeId("Loss")
  val probabilitySort: TypeId     = TypeId("Probability")
  val nodeNameLiteralSort: TypeId = RiskTreeKnowledgeBase.NodeNameLiteralSort
  val nodeIdLiteralSort: TypeId   = RiskTreeKnowledgeBase.NodeIdLiteralSort
```

`nameToId` is unchanged (reserved-filtered name → `NodeId`).

**Catalog** — `=` gone; `eq` / `named` / `has_id` added; `Node` validator
name-only; two value sorts with their validators:

```scala
  val catalog: TypeCatalog = TypeCatalog.unsafe(
    types = Set(
      TypeDecl.DomainType(nodeSort),
      TypeDecl.ValueType(lossSort),
      TypeDecl.ValueType(probabilitySort),
      TypeDecl.ValueType(nodeNameLiteralSort),
      TypeDecl.ValueType(nodeIdLiteralSort)
    ),
    constants = Map.empty,
    functions = Map(
      SymbolName("p95") -> FunctionSig(List(nodeSort), lossSort),
      SymbolName("p99") -> FunctionSig(List(nodeSort), lossSort),
      SymbolName("lec") -> FunctionSig(List(nodeSort, lossSort), probabilitySort)
    ),
    predicates = Map(
      SymbolName("leaf")               -> PredicateSig(List(nodeSort)),
      SymbolName("portfolio")          -> PredicateSig(List(nodeSort)),
      SymbolName("child_of")           -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("descendant_of")      -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("leaf_descendant_of") -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("gt_loss")            -> PredicateSig(List(lossSort, lossSort)),
      SymbolName("gt_prob")            -> PredicateSig(List(probabilitySort, probabilitySort)),
      SymbolName("eq")                 -> PredicateSig(List(nodeSort, nodeSort)),
      SymbolName("named")              -> PredicateSig(List(nodeSort, nodeNameLiteralSort)),
      SymbolName("has_id")             -> PredicateSig(List(nodeSort, nodeIdLiteralSort))
    ),
    literalValidators = Map(
      nodeSort            -> ((s: String) => nameToId.get(s)),                 // name-only (Option B)
      nodeNameLiteralSort -> ((s: String) => nameToId.get(s)),                // named's 2nd arg
      nodeIdLiteralSort   -> ((s: String) => NodeId.fromString(s).toOption),   // has_id's 2nd arg
      lossSort          -> ((s: String) => s.toLongOption.filter(_ >= 0L)),
      probabilitySort   -> ((s: String) => s.toDoubleOption.filter(d => d >= 0.0 && d <= 1.0))
    )
  )
```

**Dispatcher** — the single `=` lambda is replaced by one shared node-identity
lambda mapped under all three symbols (no duplication):

```scala
    // shared: all three reduce to NodeId identity; differentiation is bind-time
    val nodeIdentity: List[Value] => Either[String, Boolean] = args =>
      for a <- args(0).extract[NodeId]; b <- args(1).extract[NodeId] yield a == b
    // …
    predicates = Map(
      // leaf, portfolio, child_of, descendant_of, leaf_descendant_of, gt_loss, gt_prob — unchanged
      SymbolName("eq")     -> nodeIdentity,
      SymbolName("named")  -> nodeIdentity,
      SymbolName("has_id") -> nodeIdentity
    )
```

**Bind-time behaviour table** (the observable contract):

| Query fragment | Binds to | Result |
|---|---|---|
| `eq(a, b)` (two vars) | node identity | true iff same node |
| `named(x, "IT Risk")` | `nameToId.get("IT Risk")` | pins that node |
| `named(x, "01BX…ulid")` | `nameToId.get(ulid)` = None | 400 `UnparseableConstant` (unless a node is literally so named) |
| `has_id(x, "01BX…ulid")` | `NodeId.fromString` | pins that node |
| `has_id(x, "IT Risk")` | `NodeId.fromString("IT Risk")` = None | 400 `UnparseableConstant` |
| `child_of(x, "IT Risk")` | `Node` validator = name | pins by name (unchanged from today) |
| `child_of(x, "01BX…ulid")` | `Node` validator (name-only) = None | 400 (was: bound by id under §8.9) — use `has_id` + `eq`/structural bind |

The 400 category differs by sort: a failed `Node` or `NodeNameLiteral` literal
is a nonexistent node → `UNKNOWN_REFERENCE`; a failed `NodeIdLiteral` literal is
malformed id syntax → `BIND_FAILED` (next subsection).

#### Error classification — extend the §8.11 `named`-unresolved mapping (Option B, ruled 2026-08-25)

Introducing `named` over its own `NodeNameLiteral` value sort changes what
`FolQueryFailure.fromQueryError` ([AppError.scala](../../modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala))
sees for a nonexistent node named through `named`. §8.11 classifies a bind
failure as HTTP 400 `UNKNOWN_REFERENCE` (rather than `BIND_FAILED`) only when
**every** failed literal carries sort `"Node"`. A failed `named(x,
"Nonexistent")` now carries sort `"NodeNameLiteral"`, so without this amendment
it would fall through to `BIND_FAILED` — a different, misleading category for
the same user mistake ("that node does not exist") that `child_of(x,
"Nonexistent")` already reports as `UNKNOWN_REFERENCE`.

Fix: the node-reference discriminator becomes a **set** of both name-resolving
sorts. `NodeIdLiteral` is deliberately excluded — a failed id literal is
malformed id syntax (a genuine parse/bind error, `"cannot parse '…' as
NodeIdLiteral"`), and a well-formed-but-absent id parses and simply evaluates
false, so `has_id` has no unresolved-reference failure mode.

```scala
// modules/common/.../domain/errors/AppError.scala — object FolQueryFailure
// (amends the §8.11 single-name discriminator)

val NodeSortName: String = "Node"

/** The name-literal value sort (`named(x, "…")`). A failed name literal here is
  * the same user error as a failed Node-slot name — a nonexistent node — so it
  * classifies as UNKNOWN_REFERENCE too. NodeIdLiteral is excluded: a failed id
  * literal is malformed syntax, and a well-formed-but-absent id evaluates false. */
val NodeNameLiteralSortName: String = "NodeNameLiteral"

/** Sorts whose failed literal means "no such node" → UNKNOWN_REFERENCE. */
val NodeReferenceSortNames: Set[String] = Set(NodeSortName, NodeNameLiteralSortName)

case e: QE.BindError =>
  val allNodeUnresolved =
    e.details.nonEmpty && e.details.forall {
      case BindErrorDetail.UnparseableConstant(_, sortName, _, _) =>
        NodeReferenceSortNames.contains(sortName)
      case _ => false
    }
  if allNodeUnresolved then FolUnknownReference(e.messages)
  else                      FolBindFailure(e.messages)
```

Drift guard (`RiskTreeKnowledgeBaseSpec`) — bind both discriminator strings to
their catalog sorts, extending §8.11's single-sort guard:

```scala
test("node-reference sort discriminators match the classifier's names") {
  assertTrue(
    RiskTreeKnowledgeBase.NodeSort.value            == FolQueryFailure.NodeSortName,
    RiskTreeKnowledgeBase.NodeNameLiteralSort.value == FolQueryFailure.NodeNameLiteralSortName
  )
}
```

Classifier test (`FolQueryFailureFromQueryErrorSpec`) — add one case to the
§8.11 `bindSuite`:

```scala
//  (d) homogeneous NodeNameLiteral UnparseableConstant → FolUnknownReference
//      (named(x, <nonexistent>) is a nonexistent-node error, like child_of)
```

#### `MitigationTarget` emission amendment (§8.4-1, authorized 2026-08-25)

§8.4-1 (RULED C) is unchanged in substance — single-variant `MitigationTarget`,
`overrideAnchor: NodeId`, server-side resolution-equals-`{overrideAnchor}`. Only
the concrete predicate the UI node-picker emits changes, from the retired `=`
form to `has_id`:

- Default authoring emission: `x = "<nodeId>"` → **`has_id(x, "<nodeId>")`**.
- The §8.4-1 item-1 worked example's hand-written name predicate
  `x = "primary-db"` → **`named(x, "primary-db")`** (illustrates name-based
  drift; unchanged meaning).

Both edits are made to §8.4-1 in this document as part of this section (the user
authorized the emission wording 2026-08-25). The injection-safety argument is
unchanged: `NodeId`'s refinement (`^[0-9A-HJKMNP-TV-Z]{26}$`) admits no quotes,
spaces, or operator characters, and the controlling check remains server-side
(`has_id`'s `NodeId.fromString` validator rejects any non-conforming token at
bind).

#### Test changes (`RiskTreeKnowledgeBaseSpec`)

```scala
// catalog structure suite
//   "catalog declares four sorts" → "five sorts"; add typeIds.size == 5
//   predicateSymbols.size == 8 → == 10

// "= (node identity)" suite → "eq (node identity)"; SymbolName("=") → SymbolName("eq") (both cases)

// C1 validator suite — Node validator is now name-only; add the two new validators:
val nodeV = kb.catalog.literalValidators(nodeSort)
val nameV = kb.catalog.literalValidators(nodeNameLiteralSort)
val idV   = kb.catalog.literalValidators(nodeIdLiteralSort)
assertTrue(
  nodeV("Cyber")        == Some(cyberId),   // name → id (unchanged)
  nodeV(cyberId.value)  == None,            // WAS Some(cyberId): id no longer binds in a Node slot
  nameV("Cyber")        == Some(cyberId),   // named: name → id
  nameV(cyberId.value)  == None,            // named rejects an id
  idV(cyberId.value)    == Some(cyberId),   // has_id: id → id
  idV("Cyber")          == None             // has_id rejects a name
)

// C4 drift-guard baseline gains "eq", "named", "has_id"; the `==` assertion holds
//   once FolSymbols.reservedNames is synced (below).

// New query-level cases (via QueryBinder + evaluate over the 4-node fixture):
//   named(x, "IT Risk")  binds and its satisfying set is that node
//   has_id(x, "<cyberId>") binds and pins Cyber
//   has_id(x, "IT Risk") → BindError/UnparseableConstant (sort "NodeIdLiteral")
//   named(x, "<cyberId>") → BindError/UnparseableConstant (sort "NodeNameLiteral")
```

`BinderIntegrationSpec` B1/B2/B4 use `leaf_descendant_of(x, "IT Risk")` /
`"Nonexistent"` — name literals in a structural node slot, resolved by the
retained name branch — so they stay green **unmodified** and are not touched.

#### `FolSymbols.reservedNames` sync

Drop `"="`; add `"eq"`, `"named"`, `"has_id"`. The set stays equal to the
catalog's function ∪ predicate symbol union, so the C4 drift-guard passes:

```scala
  val reservedNames: Set[String] = Set(
    // predicates
    "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
    "gt_loss", "gt_prob", "eq", "named", "has_id",
    // functions
    "p95", "p99", "lec"
  )
```

A node literally named `eq` / `named` / `has_id` is excluded from `nameToId`
and rejected by the DTO gate `requireNoReservedNames`, exactly as for any
symbol name — no real node carries these names.

#### File inventory (delta)

All files this section edits are **already in `## File inventory`** — no
inventory change:

- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala` (M1R list)
- `modules/common/src/main/scala/com/risquanter/register/common/FolSymbols.scala` (M1R list)
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala` (§8.11 list — Option B classifier extension)
- `modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala` (§8.11 list — classifier case)
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala` (M1R list)
- `build.sbt` (version PATCH)

Not touched: `QueryResponseBuilder.scala` (projects by `NodeSort` filter +
`extract[NodeId]`, unaffected), `QueryServiceLive.scala`, `BinderIntegrationSpec.scala`,
`ErrorResponse.scala` (the `UNKNOWN_REFERENCE` wire arm from §8.11 already
carries the widened message list — no shape change).

#### ADR alignment

- **ADR-014** (DomainType vs ValueType): `NodeNameLiteral` / `NodeIdLiteral` are
  `ValueType`s — scalar, flow through argument slots, never quantified over.
  Compliant.
- **ADR-015 §2** (consumer carrier): unchanged single `given Extract[NodeId]`;
  all three node-reference sorts carry `NodeId`. No engine change. Compliant.
- **ADR-018** (nominal id wrapper): the carrier is the nominal `NodeId`
  throughout; the only raw `String` is each validator's parse input (the
  sanctioned boundary). Compliant.
- **ADR-001 / ADR-010** (validate at boundary, typed errors): improved —
  node-by-name and node-by-id are now two disjoint, whitelist-constrained
  validators; an unresolvable literal is a typed bind error, not a silent
  empty set.
- **ADR-028 §4** (query validation before evaluation): the `named`-unresolved
  classification stays inside the single `fromQueryError` mapper (no
  `QueryBinder.bind` bypass); the discriminator widens from one sort name to a
  set. **Trigger #5** (behaviour change): the HTTP category for a failed
  `named` literal changes from `BIND_FAILED` to `UNKNOWN_REFERENCE` — specified
  verbatim in the classifier subsection above; approval of this section is its
  echo. **Trigger #8** (test assertions): the added classifier case and the
  extended drift guard are additive; the existing §8.11 cases are unchanged.
- **ADR-029** (input-injection defence): improved. Two ADR-029 edits land
  **with this slice's implementation** (doc sweep, below), because §8.12 is the
  first change to rewrite exactly the mechanism ADR-029 §3 describes:
  - §3 row "FOL `VagueQueryParser.parse`" currently reads "node names enter
    via `catalog.constants` lookup (`Map.get`)". This has been **stale since
    §8.9** (`constants = Map.empty`); correct it to: "node references resolve
    at bind time through per-sort literal validators — `nameToId.get` for a
    name (`Node` / `NodeNameLiteral` sorts), `NodeId.fromString` for an id
    (`NodeIdLiteral`) — whitelist-constrained, never interpolated."
  - §5 injection-inventory row for the KB dispatcher stays correct
    (`Map.get` / `Set.contains`; no interpolation).
- **ADR-028 / ADR-028-appendix** (query-pane predicate vocabulary): the
  relational-predicate tables list `leaf` / `child_of` / `descendant_of` /
  `leaf_descendant_of` but not the node-reference predicates (they also never
  listed `=`). Add `eq(x, y)`, `named(x, "name")`, `has_id(x, "id")` rows with
  the doc sweep when this slice lands (behaviour-descriptive tables kept
  current). No decision content in ADR-028 changes.
- **ADR-028 (main)**: the `code-quality-review` / query-pane autocomplete
  vocabulary, if it enumerates predicate names, gains `eq` / `named` /
  `has_id` and drops `=` in the same sweep.

No ADR decision is reversed; the edits are descriptive-currency only and land in
the same commit as the code.

#### Open decisions

Both resolved (user, 2026-08-25):

- **OD-A — remove the vestigial `Bool` sort. RULED: remove.** `boolSort =
  TypeId("Bool")` was declared as a `ValueType` but referenced by no signature
  and had no validator (predicates return `Boolean` natively through the
  `MapDispatcher`, `List[Value] => Either[String, Boolean]`); verified no
  consumer anywhere in `modules/` (only its own two lines). The exact signatures
  above omit it.
- **OD-B — TypeId names for the two value sorts. RULED: `NodeNameLiteral` /
  `NodeIdLiteral`.** Both are `…Literal`-suffixed value sorts, symmetric. The
  sort string is not part of query input — the sort is inferred from the
  argument slot, so a user writes `named(x, "…")` / `has_id(x, "…")`, never a
  sort name — but it does surface in a failed-literal bind message (`"cannot
  parse '…' as NodeIdLiteral"`) and is the string the classifier branches on
  (classifier subsection above), so both names are chosen to read correctly in
  that message and to avoid colliding with the Scala carrier type `NodeId`.

No other open decisions: the substance (`eq` rename, `named` / `has_id`,
Option B name-only `Node` validator, `has_id` emission, and the classifier
extension for `named`-unresolved) is ruled (user, 2026-08-23/25).

#### Verification plan

```bash
sbt server/compile
sbt server/test                 # RiskTreeKnowledgeBaseSpec (catalog, eq, C1/C4, new named/has_id cases)
sbt 'commonJVM/test; server/test'
sbt app/test                    # unaffected — must stay green
sbt serverIt/test               # unaffected — must stay green
run_bats tests/bats/suite-c-in-memory.bats   # fast gate after code change
```

Then: PATCH bump `0.10.20` → `0.10.21`, mirror `APP_VERSION` into `.env` and
`.env.irmin`; doc sweep — the KB class scaladoc (sort table: drop `Bool`, add
`NodeNameLiteral` / `NodeIdLiteral` carrying `NodeId`; predicate table: `=` row
→ `eq`, add `named` / `has_id` rows), the `AppError.scala` classifier
doc-comments (`NodeNameLiteralSortName` / `NodeReferenceSortNames`), the two
ADR-029 §3 edits above, and the ADR-028 predicate-vocabulary rows.

### 8.13 M2 slice 2 — `MitigationScopeResolver` + `ScopeOutcome` — implementation-grade (2026-08-28)

Second buildable slice of M2. Turns each mitigation's stored targeting predicate
into the set of node ids it scopes, against a specific tree version, with
per-mitigation failure isolation and per-tree-version memoization. Server-only
(the engine is a `server` dependency, ADR-028 §1); **no `common` DTO or wire
change** — the resolver's output projects to the `Map[MitigationId, Set[NodeId]]`
the already-landed `MitigationApplication.scoped` / `effectiveTree` take (§8.6).
Every decision is ruled: §8.8 M2-D1 (service resolves scope), M2-D2 (`ScopeOutcome`
coproduct, `toEither` + wrapper projections only); §8.1 (stage-domain restriction,
per-workspace memoization on the byte-level revision, F3 per-mitigation isolation);
and 2026-08-28 (`NonEmptyChunk` failure collection, granular `ScopeResolutionFailure`).
This section writes the exact signatures so they are G1-covered.

**Engine surface used (vql-engine 0.16.0, verified in the sources jar).** The
resolver calls the binder and the satisfying-set evaluator directly, not the
screening `VagueSemantics` path, so it receives the full typed error list:

```scala
QueryBinder.bindSatisfyingFormula(
  formula: Formula[FOL], variable: String, catalog: TypeCatalog
): Either[List[TypeCheckError], (BoundFormula, BoundVar)]

TypedSemantics.satisfyingSet(
  formula: BoundFormula, variable: BoundVar, model: RuntimeModel
): Either[QueryError, Set[Value]]
```

`List[TypeCheckError]` (11 typed variants, each carrying the real `TypeId` of the
offending sort) is the fidelity that makes the granular failure classification
below possible without routing through the lossy `BindErrorDetail` the HTTP
classifier flattens to.

#### Contract — `MitigationScopeResolver.scala`

```scala
package com.risquanter.register.services.cache

import zio.{UIO, NonEmptyChunk}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash, MitigationId}

/** Names the tree version whose scopes are resolved. The owning workspace is NOT
  * a field: one resolver instance exists per workspace (`ScopeResolverScope`, the
  * DD-17 `CacheScope` precedent), so the workspace IS the instance and the memo
  * key inside it is exactly (treeId, branch, revision). `revision` is the
  * byte-level Irmin commit hash, never the DD-16 domain hash — predicates
  * reference node names, which the domain hash omits, so a rename changes
  * resolution but not the domain hash (§8.1 cache-identity ruling). */
final case class ScopeResolutionContext(treeId: TreeId, branch: BranchRef, revision: CommitHash)

/** Why one mitigation's predicate did not resolve against this tree version.
  * Granularity preserved (ruled 2026-08-28): the user-actionable distinctions —
  * a renamed/deleted node, a malformed id, a type error — stay separate; the
  * structural and internal faults a create-validated predicate should never
  * reach are collapsed, because they carry no end-user action. */
enum ScopeResolutionFailure:
  case UnknownNode(reference: String)      // a `named`/`Node`-slot node name no longer resolves
  case MalformedNodeId(reference: String)  // a `has_id` literal is not a well-formed node id
  case TypeError(detail: String)           // a symbol used at an incompatible / conflicting sort
  case MalformedPredicate(detail: String)  // structural mismatch vs the current catalog vocabulary
  case InternalError(detail: String)       // re-parse or eval fault a bound predicate should never reach

/** Per-mitigation resolution outcome (M2-D2: a coproduct with `toEither` and
  * wrapper projections only — no `map`/`flatMap`; it is a result, not a pipeline).
  * `Failed` still contributes an empty applied scope (§8.1 F3: a stale predicate
  * is a no-op for that mitigation, never a whole-request failure), so
  * `scopeOrEmpty` is what the application algebra consumes and `failures` is the
  * per-mitigation drift signal. */
enum ScopeOutcome:
  case Resolved(scope: Set[NodeId])
  case Failed(errors: NonEmptyChunk[ScopeResolutionFailure])   // field `errors`; the `failures` projection below is the Option view

  def toEither: Either[NonEmptyChunk[ScopeResolutionFailure], Set[NodeId]] = this match
    case Resolved(scope) => Right(scope)
    case Failed(errs)    => Left(errs)

  def scopeOrEmpty: Set[NodeId] = this match
    case Resolved(scope) => scope
    case Failed(_)       => Set.empty

  def failures: Option[NonEmptyChunk[ScopeResolutionFailure]] = this match
    case Resolved(_)  => None
    case Failed(errs) => Some(errs)

/** The per-mitigation outcome map for one tree version. Total over
  * `tree.mitigations`: every mitigation has an entry (its `MitigationTarget` is
  * always a `Predicate`, §8.4-1 = C). */
final case class ResolvedScopes(outcomes: Map[MitigationId, ScopeOutcome]):
  /** Success projection consumed by `MitigationApplication.scoped` /
    * `effectiveTree` (§8.6): each mitigation's applied scope, empty for a failed
    * one. This is the `Map[MitigationId, Set[NodeId]]` the resolver edge (slice 3)
    * passes into the pure algebra. */
  def appliedScopes: Map[MitigationId, Set[NodeId]] =
    outcomes.view.mapValues(_.scopeOrEmpty).toMap
  def failures: Map[MitigationId, NonEmptyChunk[ScopeResolutionFailure]] =
    outcomes.collect { case (id, ScopeOutcome.Failed(errs)) => id -> errs }

trait MitigationScopeResolver:
  /** Resolve every mitigation's predicate to its applied node-id scope against
    * `tree`, memoized per tree version. `tree` must be the tree at
    * `context.revision`; the context names which version for cache identity.
    * No error channel (`UIO`): every per-mitigation failure is isolated into the
    * outcome map (F3), and the results-free KB build plus the in-memory memo
    * cannot fault — see the signature-review note on the error channel. */
  def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes]
```

#### Implementation — `MitigationScopeResolverLive.scala`

```scala
package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{RiskTree, Mitigation, MitigationTarget, MitigationSpec, TargetingPredicate}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.FolQueryFailure   // NodeReferenceSortNames (single source of truth, §8.12)
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase
import com.risquanter.register.foladapter.RiskTreeKnowledgeBase.given   // Extract[NodeId]
import vql.typed.{QueryBinder, TypedSemantics, TypeCheckError, Value}
import vql.error.QueryError
import parser.FOLParser
import logic.{Formula, FOL, FOLUtil}

/** Memoizes the resolved scopes of one tree version. Head-only (§8.4-5): one
  * entry per (treeId, branch) holds a revision and its scopes, and any resolve
  * at a different revision overwrites it, so revisions never accumulate. The memo
  * read and write are not atomic (last-writer-wins) — see "Memo write policy".
  * In-memory `Ref` → `UIO`. One instance per workspace (`ScopeResolverScope`). */
final case class MitigationScopeResolverLive(
  memo: Ref[Map[(TreeId, BranchRef), (CommitHash, ResolvedScopes)]]
) extends MitigationScopeResolver:

  override def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes] =
    val slot = (context.treeId, context.branch)
    memo.get.map(_.get(slot)).flatMap {
      case Some((rev, cached)) if rev == context.revision => ZIO.succeed(cached)
      case _ =>
        val resolved = computeAll(tree)
        memo.update(_ + (slot -> (context.revision, resolved))).as(resolved)
    }

  /** Results-free KB (§8.1): the targeting sublanguage admits no simulation
    * symbol, so an empty result map is correct and makes the resolved scopes a
    * pure function of the tree version. */
  private def computeAll(tree: RiskTree): ResolvedScopes =
    val kb = RiskTreeKnowledgeBase(tree, Map.empty)
    ResolvedScopes(tree.mitigations.map(m => m.id -> resolveOne(m, tree, kb)).toMap)

  private def resolveOne(m: Mitigation, tree: RiskTree, kb: RiskTreeKnowledgeBase): ScopeOutcome =
    val predicate = m.target match { case MitigationTarget.Predicate(p) => p }
    val domain: Set[NodeId] = m.spec match          // §8.1 stage-domain restriction
      case _: MitigationSpec.LeafStage   => tree.index.leafIds
      case _: MitigationSpec.ResultStage => tree.index.nodes.keySet
    satisfyingIds(predicate, kb) match
      case Right(ids)     => ScopeOutcome.Resolved(ids intersect domain)
      case Left(failures) => ScopeOutcome.Failed(failures)

  /** Re-parse (source is the only stored form) → bind against this tree version's
    * catalog → evaluate to the exact satisfying set → lift each `Value` to its
    * `NodeId`. Bind is the tree-version-relative check that fails when a quoted
    * node was renamed/deleted (F3); parse and extract cannot fail for a
    * create-validated predicate over the node sort, so their failure arms are
    * `InternalError`. */
  private def satisfyingIds(
    predicate: TargetingPredicate, kb: RiskTreeKnowledgeBase
  ): Either[NonEmptyChunk[ScopeResolutionFailure], Set[NodeId]] =
    FOLParser.parse(predicate.source) match
      case Left(pe) =>
        Left(NonEmptyChunk(ScopeResolutionFailure.InternalError(s"re-parse failed: ${pe.message}")))
      case Right(formula) =>
        FOLUtil.fvFOL(formula).distinct match
          case variable :: Nil =>
            QueryBinder.bindSatisfyingFormula(formula, variable, kb.catalog) match
              case Left(errs) =>
                Left(
                  NonEmptyChunk
                    .fromIterableOption(errs.map(fromTypeCheckError))
                    .getOrElse(NonEmptyChunk(ScopeResolutionFailure.InternalError("empty bind-error list")))
                )
              case Right((bound, boundVar)) =>
                TypedSemantics.satisfyingSet(bound, boundVar, kb.model) match
                  case Left(qe)      => Left(NonEmptyChunk(fromQueryError(qe)))
                  case Right(values) => Right(values.flatMap(_.extract[NodeId].toOption))
          case _ =>
            // create guarantees exactly one free variable — unreachable
            Left(NonEmptyChunk(ScopeResolutionFailure.InternalError(
              "targeting predicate free-variable invariant violated")))

  /** Engine bind error → register failure. The three user-actionable variants
    * stay distinct; every structural fault a create-validated predicate cannot
    * legitimately reach collapses to `MalformedPredicate`. */
  private def fromTypeCheckError(e: TypeCheckError): ScopeResolutionFailure = e match
    case TypeCheckError.UnparseableConstant(name, sort, _) =>
      sort.value match
        case s if s == RiskTreeKnowledgeBase.NodeIdLiteralSort.value  => ScopeResolutionFailure.MalformedNodeId(name)
        case s if FolQueryFailure.NodeReferenceSortNames.contains(s)  => ScopeResolutionFailure.UnknownNode(name)
        case s                                                        => ScopeResolutionFailure.MalformedPredicate(s"unparseable literal '$name' for sort '$s'")
    case TypeCheckError.TypeMismatch(expected, actual, ctx)           => ScopeResolutionFailure.TypeError(s"$ctx: expected ${expected.value}, got ${actual.value}")
    case TypeCheckError.ConflictingTypes(name, l, r)                  => ScopeResolutionFailure.TypeError(s"variable '$name' used at ${l.value} and ${r.value}")
    case TypeCheckError.UnknownPredicate(name)                        => ScopeResolutionFailure.MalformedPredicate(s"unknown predicate '$name'")
    case TypeCheckError.UnknownFunction(name)                         => ScopeResolutionFailure.MalformedPredicate(s"unknown function '$name'")
    case TypeCheckError.ArityMismatch(sym, exp, act)                  => ScopeResolutionFailure.MalformedPredicate(s"arity mismatch for '$sym': expected $exp, got $act")
    case TypeCheckError.UnknownConstantOrLiteral(name)                => ScopeResolutionFailure.MalformedPredicate(s"unknown constant or literal '$name'")
    case TypeCheckError.UnconstrainedVar(name)                        => ScopeResolutionFailure.MalformedPredicate(s"unconstrained variable '$name'")
    case TypeCheckError.UnexpectedFreeVar(name)                       => ScopeResolutionFailure.MalformedPredicate(s"unexpected free variable '$name'")
    case TypeCheckError.TypeNotQuantifiable(name)                     => ScopeResolutionFailure.MalformedPredicate(s"non-quantifiable target sort '$name'")
    case TypeCheckError.UnboundAnswerVar(name)                        => ScopeResolutionFailure.MalformedPredicate(s"unbound answer variable '$name'")

  /** Evaluation-phase `QueryError` is an internal wiring fault for a bound
    * targeting predicate (no domain gap, no unbound var possible once bound), so
    * it is `InternalError`, not a user-facing drift reason. */
  private def fromQueryError(e: QueryError): ScopeResolutionFailure =
    ScopeResolutionFailure.InternalError(e.formatted)
```

Full `TypeCheckError` → `ScopeResolutionFailure` mapping (the observable contract
for the granularity ruling):

| Engine `TypeCheckError` | → `ScopeResolutionFailure` | User meaning |
|---|---|---|
| `UnparseableConstant`, sort `Node` / `NodeNameLiteral` | `UnknownNode(name)` | the node this predicate names was renamed or deleted |
| `UnparseableConstant`, sort `NodeIdLiteral` | `MalformedNodeId(name)` | a `has_id("…")` literal is not a valid id |
| `UnparseableConstant`, other sort | `MalformedPredicate` | a non-node literal (e.g. loss/probability) is malformed |
| `TypeMismatch`, `ConflictingTypes` | `TypeError(detail)` | a symbol used at the wrong / two conflicting sorts |
| `UnknownPredicate`, `UnknownFunction`, `ArityMismatch`, `UnknownConstantOrLiteral`, `UnconstrainedVar`, `UnexpectedFreeVar`, `TypeNotQuantifiable`, `UnboundAnswerVar` | `MalformedPredicate(detail)` | structural — the predicate does not fit the current catalog (only reachable if the catalog vocabulary changed under a stored predicate) |
| any evaluation-phase `QueryError` | `InternalError(detail)` | a wiring fault; should not occur for a bound predicate |

#### Per-workspace registry — `ScopeResolverScope.scala` (mirrors `CacheScope`)

```scala
package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, CommitHash}

/** Per-workspace `MitigationScopeResolver` resolution (§8.1 cache-identity, the
  * DD-17 `CacheScope` precedent). One resolver instance per workspace makes
  * cross-workspace scope contamination structurally impossible; the memo key
  * inside each instance is (treeId, branch, revision). */
trait ScopeResolverScope:
  def resolverFor(workspaceId: WorkspaceId): UIO[MitigationScopeResolver]

object ScopeResolverScope:
  val layer: ZLayer[Any, Nothing, ScopeResolverScope] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[WorkspaceId, MitigationScopeResolver]).map(ScopeResolverScopeLive(_))
    )

  def resolverFor(workspaceId: WorkspaceId): URIO[ScopeResolverScope, MitigationScopeResolver] =
    ZIO.serviceWithZIO[ScopeResolverScope](_.resolverFor(workspaceId))

final case class ScopeResolverScopeLive(
  resolvers: Ref[Map[WorkspaceId, MitigationScopeResolver]]
) extends ScopeResolverScope:
  override def resolverFor(workspaceId: WorkspaceId): UIO[MitigationScopeResolver] =
    resolvers.get.map(_.get(workspaceId)).flatMap {
      case Some(r) => ZIO.succeed(r)
      case None =>
        for
          memo     <- Ref.make(Map.empty[(TreeId, BranchRef), (CommitHash, ResolvedScopes)])
          candidate = MitigationScopeResolverLive(memo)
          // modify picks the winner atomically if two fibers race on first access
          resolver <- resolvers.modify { m =>
                        m.get(workspaceId) match
                          case Some(existing) => (existing, m)
                          case None           => (candidate, m + (workspaceId -> candidate))
                      }
        yield resolver
    }
```

#### Signature-review points (call out; these set what §8.1 deferred to "exact shape at M2 elevation")

1. **`resolve` returns `UIO[ResolvedScopes]`, not `IO[AppError, ResolvedScopes]`
   (§8.1 sketch).** F3 isolation puts every per-mitigation failure into the
   outcome map, and the results-free KB build (total) plus the in-memory memo
   (`Ref`) cannot fault, so there is no whole-request error to raise. The §8.1
   text explicitly deferred the shape here; this is that decision, not a silent
   deviation.
2. **The memo caches `ResolvedScopes` only, not the KB.** §8.1 says the cache
   entry "holds the KB plus the resolved scope map." The stated purpose — not
   recomputing scopes per request — is met by caching the resolved scopes alone;
   the KB is transient (rebuilt once per tree-version miss). Retaining the KB
   itself is an M3 analytics concern (KB reuse across analytic queries), out of
   slice-2 scope. Flagged rather than swept because it narrows the §8.1 wording.
3. **Per-workspace partition key is `WorkspaceId`, not `SeedEntityId`.**
   `CacheScope` keys by `SeedEntityId` because simulation figures depend on the
   HDR entity axis; scope resolution has no seed relationship, so `WorkspaceId`
   is the honest authority identity. Structurally identical isolation.
4. **`ScopeResolutionContext` carries `(treeId, branch, revision)`, not the
   workspace.** The workspace is the instance (exactly as `ContentCache.get`'s
   key omits `seedEntityId`), matching §8.1's "keyed inside the instance by
   (TreeId, branch, revision)."

#### Memo write policy — last-writer-wins (accepted trade-off, complex review 2026-08-28)

`resolve` reads the memo slot and, on a miss or revision change, computes and
writes it as two separate `Ref` operations (`memo.get` then `memo.update`), not
one atomic step. This is deliberate.

- **Verified constructible.** Head revA is edited to head revB; request R1 read
  the tree at revA, R2 at revB; both enter `resolve` on the same (treeId, branch)
  slot concurrently. R2 misses, computes, writes (revB, scopesB) — slot at head.
  R1, whose `get` already returned a miss, then writes (revA, scopesA)
  unconditionally — the slot now holds the older revA. Two resolves at the same
  revision likewise both compute and both write the identical value.
- **Why it is not a correctness problem.** A hit requires `rev == context.revision`
  (exact equality on a content-addressed commit hash), so a displaced older entry
  is never returned for a newer request — it only makes the next head request miss
  and recompute, which rewrites the head. No wrong scope is ever served; the slot
  self-heals on the next head request. The sole cost is bounded redundant
  computation (a parse + bind over a ≤256-char predicate) under concurrency.
- **Why not the heavy fix.** `CommitHash` is a content hash with no ordering, so
  "keep only the newer revision" is not definable — last-writer-wins is the
  correct-by-necessity policy, and a single `memo.modify` would not help because
  `computeAll` runs before any atomic section. Removing the redundant compute
  needs per-slot single-flight (a `Promise` stored in the memo, plus
  revision-change replacement and interrupted-producer handling) —
  disproportionate to a cheap, self-healing miss. Revisit only if slice-3
  profiling shows the KB build is hot on the simulation path.

Decision: **accept last-writer-wins as-is; record the trade-off in the class
scaladoc.** (Option A of the complex-review decision, 2026-08-28.)

#### ADR alignment

- **ADR-028 §1** — compliant: engine stays a `server` dependency; the resolver
  and all its types are server-side; no engine change.
- **ADR-015 §2** — compliant: node ids recovered via the consumer `Extract[NodeId]`
  (reused from `RiskTreeKnowledgeBase.given`), no engine carrier change.
- **ADR-001 / decode == create** — compliant: the predicate re-parses and
  re-binds at resolution against the current tree version; a stored predicate
  that went stale fails to bind and becomes a per-mitigation `Failed`, never an
  exception or a silent wrong scope.
- **ADR-010** — compliant: errors are values (`Either` from the engine, the
  `ScopeOutcome` coproduct out of the resolver); no exceptions for domain
  conditions.
- **ADR-006 / M2-D2** — compliant: `ScopeOutcome` and `ScopeResolutionFailure`
  are `enum`s; `ScopeOutcome` exposes `toEither` + wrapper projections only, no
  `map`/`flatMap` (it is a result, not a transform pipeline).
- **ADR-018** — compliant: `NodeId`, `TreeId`, `WorkspaceId`, `MitigationId`,
  `BranchRef`, `CommitHash` are the existing nominal wrappers; no raw primitive
  carries a domain value across any signature.
- **Concurrency** — the per-workspace registry resolves a first-access race to
  one winner via atomic `Ref.modify` (the `CacheScope` pattern). The per-instance
  memo write is a non-atomic get-then-update with last-writer-wins semantics — a
  reviewed, accepted trade-off that never serves a wrong scope; see "Memo write
  policy" above.

No deviations beyond the four signature-review points above.

#### Open decisions

None. The one substantive shape (failure-arm collection and granularity) is
ruled (2026-08-28); the four points above are exact-signature settlements of what
§8.1 deferred, presented for the accepted signal, not open questions.

#### Verification plan

New spec `MitigationScopeResolverSpec` (server, `zio-test`), cases:

- **resolve — happy path:** a predicate `leaf(x) /\ descendant_of(x, "Servers")`
  resolves to exactly the matching leaf ids; `ScopeOutcome.Resolved`.
- **stage-domain restriction:** a `ResultStage` predicate matching a portfolio
  keeps it (domain = all nodes); the same predicate on a `LeafStage` mitigation
  drops the portfolio (domain = leaves) — applied scope is the intersection.
- **F3 isolation + granularity:** a tree with one stale predicate (quoted node
  renamed) and one valid predicate → the stale one is `Failed(UnknownNode(...))`
  with an empty applied scope, the valid one `Resolved(...)`; `resolve` succeeds.
- **malformed id vs unknown name:** `has_id("not-an-id")` → `MalformedNodeId`;
  `named("Gone")` / `child_of(x, "Gone")` → `UnknownNode`.
- **memoization:** two `resolve` calls at the same `(treeId, branch, revision)`
  build the KB once (assert via a resolve count / instrumented tree); a call at a
  new `revision` recomputes and the old entry is gone (head-only).
- **per-workspace isolation:** `ScopeResolverScope.resolverFor` returns the same
  instance for one `WorkspaceId` and distinct instances for different ones.
- **projection:** `ResolvedScopes.appliedScopes` equals the `Map[MitigationId,
  Set[NodeId]]` `MitigationApplication.scoped` consumes; `Failed` maps to `∅`.

```bash
sbt server/compile              # zero new warnings
sbt server/test                 # MitigationScopeResolverSpec + existing green
sbt 'commonJVM/test; server/test'
sbt app/test                    # unaffected — must stay green
sbt serverIt/test               # unaffected — must stay green
run_bats tests/bats/suite-c-in-memory.bats   # fast gate after code change
```

Then: PATCH bump `0.10.21` → `0.10.22`, mirror `APP_VERSION` into `.env` and
`.env.irmin`; doc sweep — the §8.10 slice-2 status row (done above) and any KB
scaladoc that now also describes the resolver consumer. No `common` / wire / ADR
doc changes (server-internal slice).

### 8.14 M2 slice 3 — `CachedResultResolver` rename + resolver-edge mitigation wiring — implementation-grade (2026-08-28)

Third buildable slice of M2. Consumes slice 2's `Map[MitigationId, Set[NodeId]]`
at the resolver edge: param-stage transforms change the cache-key content;
result-stage transforms are applied at the edge and never cached (D3). This
section reconciles the stale §7.2.2 signature box against the §8.6 algebra.

Two parts of very unequal weight:

- **Rename (mechanical).** `RiskResultResolver` → `CachedResultResolver` (M2-D4).
  A pure symbol rename the compiler proves; no signature ceremony — only the
  ripple-site list below, because several ripple files are not yet in the
  File inventory and the hook would deny them.
- **Edge wiring (the substance).** Two provenance-determined pieces (edge
  signature; leaf-path + cache-key wiring) plus one genuine open decision
  (portfolio-level result-stage transform).

#### Rename — ripple sites (mechanical)

`trait RiskResultResolver` + its accessor `object` + `RiskResultResolverLive`
become `CachedResultResolver` / `CachedResultResolverLive`. Every reference
updates; the compiler enforces completeness. Sites (verified by grep
2026-08-28):

- **main:** `RiskResultResolver.scala`, `RiskResultResolverLive.scala`,
  `Application.scala` (layer reference), `QueryServiceLive.scala` (env type),
  `RiskTreeServiceLive.scala` (env type + call sites), `RiskTreeService.scala`
  (doc comment). `RiskTreeKnowledgeBase.scala` already names
  `CachedResultResolver` in a scaladoc line — no edit needed.
- **test:** `RiskResultResolverSpec.scala`, `CacheTransparencySpec.scala`,
  `Item17RegressionSpec.scala`, `SeedStabilitySpec.scala`, `ProvenanceSpec.scala`,
  `RiskTreeServiceLiveSpec.scala`, `RiskTreeControllerSpec.scala`,
  `RouteSecurityRegressionSpec.scala`, `WorkspaceLifecycleControllerSpec.scala`
  (the last four update `RiskResultResolverLive.layer` in ZLayer wiring);
  serverIt `SeedReproducibilityItSpec.scala`, `HttpTestHarness.scala`,
  `support/StubHttpTestHarness.scala`.

Files are renamed to match the type (`CachedResultResolver.scala`,
`CachedResultResolverLive.scala`, `CachedResultResolverSpec.scala`) — see the
minor sub-decision below.

#### Edge signature (determined — provenance: §8.6 algebra + M2-D1)

The §7.2.2 box (`mitigations: MitigationSelection = None`) predates the §8.6
algebra split. `MitigationApplication.scoped` / `effectiveTree` take **both**
`selection: MitigationSelection` and `resolvedScopes: Map[MitigationId,
Set[NodeId]]`, so the §8.10 row's "resolvedScopes, not `MitigationSelection`" is
a compression: the edge threads both, with no-op defaults so every existing
caller compiles unchanged.

```scala
trait CachedResultResolver:
  def ensureCached(
    tree: RiskTree, nodeId: NodeId, seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[LossDistribution]

  def ensureCachedAll(
    tree: RiskTree, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[Map[NodeId, LossDistribution]]
```

Accessor `object` methods mirror the added parameters. `selection = None` makes
`scoped` return the empty map, so the whole mitigation path is identity — the
existing callers (`QueryServiceLive`, `RiskTreeServiceLive`) are
behaviour-unchanged.

#### Internal wiring — determined parts (`CachedResultResolverLive`)

1. **Effective tree drives the cache keys.** Once per resolution, before
   hashing, and the recursion runs over the effective tree so leaf content
   matches its key:

   ```scala
   effective <- ZIO.fromEither(
     MitigationApplication.effectiveTree(tree, selection, resolvedScopes).toEither
   ).mapError(errs => ValidationFailed(errs.toList))
   hashes = ContentHashIndex.build(effective)
   // distributionForId(effective, hashes, …); node ids are stable across
   // effectiveTree, so the requested nodeId still resolves.
   ```

   For `selection = None`, `effectiveTree` returns the input nodes revalidated
   through `RiskTree.fromNodes` — identical leaf content, identical hashes — so
   raw leaf simulations are shared with the un-mitigated path (§7.2.2 "cheap by
   design"). ADR-010: a validation failure becomes typed `ValidationFailed`, no
   exception crosses the edge.

2. **Scoped map computed once, threaded through `distributionOf`:**

   ```scala
   scoped = MitigationApplication.scoped(tree, selection, resolvedScopes)  // Map[NodeId, List[Mitigation]]
   ```

3. **Result-stage transform at each node's return — leaf arm (determined):**

   ```scala
   MitigationApplication.resultTransformFor(leaf.id, scoped) match {   // None when unscoped
     case None    => raw
     case Some(t) => RiskResult.fromTrialOutcomes(leaf.id, t.run(raw.trialOutcomes), raw.provenances)
   }
   ```

   `resultTransformFor` is `None` when nothing result-stage scopes the leaf, so
   the raw cached value passes through unchanged on the un-mitigated path. ADR-009:
   the transform acts on the finished leaf operand before it enters any parent
   combine.

#### Portfolio-level result-stage transform — RULED: F (2026-08-28)

The valuation model is **ADR-034 (Mitigation Valuation Model)**; this section is
its result-resolver-edge realization. A `ResultStage` mitigation may scope a
portfolio, and `domain(ResultStage) = all nodes` (2026-08-10) is **preserved** —
F keeps portfolios in scope, so slice-2's `MitigationScopeResolverLive.resolveOne`
(`ResultStage → tree.index.nodes.keySet`) and its spec are unchanged.

**The ruling.** A portfolio's result transform is a compositional decorated
fold: `mitigated(P) = f_P(⊕ mitigated(children))` (ADR-034 Decisions 1–3). It
folds into ancestors — a parent aggregates its children's *mitigated* values.
The raw commutative fold `raw(P) = ⊕ raw(children)` is untouched and stays the
cached, content-addressed value (ADR-034 Decision 4); `RiskResultGroup` keeps its
private constructor with **no** sanctioned exception. Withdrawn: Option A
(mutating the canonical aggregate — it would have needed that exception), Option E
(terminal projection that does not fold), and the old A/B/C framing built on the
retired "aggregate ≠ sum(children)" premise.

**Why the raw aggregate cannot carry it (provenance for the edge-fold).**
Result-stage transforms are never cached (D3), so a portfolio's cached aggregate
is `⊕ raw(children)`, *not* `⊕ mitigated(children)`. Applying `f_P` to the cached
raw aggregate would give `f_P(⊕ raw(children))`, which differs from F whenever a
child carries its own result transform. So the mitigated value is a **separate
fold computed at the edge** (ADR-034 Decision 1): at each node, combine the
children's mitigated values, then apply that node's `resultTransformFor`. The leaf
arm above is the base case of exactly this fold (`resultTransformFor(leaf.id,
scoped)` applied to the raw leaf outcomes); the portfolio arm applies
`resultTransformFor(portfolio.id, scoped)` to the combined mitigated children.
`resultTransformFor` is `None` off-scope, so an un-mitigated subtree's mitigated
fold equals its raw fold and the two coincide.

**Implementation-grade item finalized at the code echo.** Whether the edge runs
the mitigated fold as a second traversal or threads a `(raw, mitigated)` pair
through the existing one — and the exact `CachedResultResolverLive` return shape
that carries the mitigated aggregate alongside the cached raw value — is a
signature decision presented at the code step's Signature Echo. It touches
`CachedResultResolverLive`'s recursion, not the public trait parameter list fixed
above; it changes no `common` wire type and adds no `LossDistribution` API.

**Resolver file renames — RULED: rename to match the type.**
`CachedResultResolver.scala`, `CachedResultResolverLive.scala`,
`CachedResultResolverSpec.scala` (`git mv` + internal rename). The name is swept
consistent across every reference — scaladoc and the ADR/plan corpus
(ADR-002/003/005/009/014/015, ARCHITECTURE.md, IMPLEMENTATION-PLAN.md,
PLAN-PROVENANCE-ENDPOINT.md, PLAN-MONOID, SENSITIVITY-ANALYSIS-PLAN.md, TODO.md)
— in the same pass as the code rename, as that change's doc-consistency sweep,
not a separate decision.

#### ADR alignment

- **ADR-034** (mitigation valuation model): this slice is the result-resolver-edge
  realization of F — the separate mitigated fold, raw kept pristine. Compliant.
- **ADR-009** (associativity): each node's transform acts on its finished
  (combined) operand before it enters the parent combine. Compliant.
- **ADR-010**: `effectiveTree` failure → typed `ValidationFailed`. Compliant.
- **ADR-015** (cache-aside): unchanged; keys are effective-content, misses
  simulate. Compliant.
- **D3**: result-stage transforms applied post-cache at the edge, never stored.
  Compliant.
- **Correct-by-construction / §9 aggregate-privacy**: F needs no exception —
  `RiskResultGroup` stays private (ADR-034 Decision 4). No `common` domain-type
  API change.

#### Open decisions

None. Both ruled 2026-08-28: portfolio-level result-stage transform → **F**
(ADR-034); resolver file renames → **rename to match the type**. The only item
remaining is the `CachedResultResolverLive` edge-fold return shape, which is a
provenance-determined signature presented at the code step's Signature Echo, not
an open design choice.

#### Verification plan

- **Rename:** the whole suite compiles and is green — `sbt 'commonJVM/test;
  server/test'`, `sbt app/test`, `sbt serverIt/test`. Green *is* the proof for a
  rename.
- **New/added resolver cases (`CachedResultResolverSpec`):**
  - un-mitigated (`selection = None`) resolution returns byte-identical results
    to today — the regression guard for the no-op defaults.
  - a `LeafStage` mitigation scoping a leaf changes that leaf's cache key and
    figures; a content-identical unmitigated leaf still shares the cache.
  - a `ResultStage` mitigation scoping a leaf transforms that leaf's outcomes
    (identity elsewhere).
  - a `ResultStage` mitigation scoping a portfolio: the **raw** aggregate still
    equals `⊕ raw(children)` (cache invariant preserved), while the **mitigated**
    aggregate equals `f_P(⊕ mitigated(children))` and differs from the raw one at
    a binding cap.
  - compositional fold (ADR-034 Decision 3): a `ResultStage` on a child *and* on
    its ancestor compose by tree position — the ancestor's transform sees the
    child's already-mitigated total — and the result is independent of the order
    the two mitigations were authored.
- **Bump:** PATCH on landing; mirror `APP_VERSION` into `.env` and `.env.irmin`.

#### File inventory (delta)

Rename ripple — **add** (not currently listed; hook would otherwise deny):

- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/Item17RegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`

Renamed paths (`git mv` of the three old files; the new paths carry the internal
type rename and the edge-fold wiring, so they must be inventoried for the hook):

- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`

F carries no `common` domain-type change — no `LossDistribution.scala` /
`LossDistributionSpec.scala` entry (that was conditional on the withdrawn Option
A).

Already in the inventory and unchanged in listing: `MitigationApplication.scala`,
`QueryServiceLive.scala`, `CacheTransparencySpec.scala`,
`RiskTreeKnowledgeBase.scala`.

## 9. Domain-invariant hardening (immediate follow-up to M1R)

**Motivation (surfaced by M1R's bounds work, 2026-08-13).** Two gaps, both
pre-existing, neither introduced by M1R:
1. `RiskTree` and `TreeIndex` are the **only two aggregate types with public
   constructors** — `RiskLeaf`, `RiskPortfolio`, `Mitigation`,
   `ResultTransformSpec`, `RiskLeafTransform`, `LossDistribution` all use
   `private` + a smart constructor. So every invariant `RiskTree.fromNodes` /
   `TreeIndex.fromNodes` validates (unique ids, root-exists, and the new
   mitigation/step counts) is **bypassable** via `apply` / `.copy`.
2. **No HTTP request body-size limit is configured** (none found in
   `modules/server/src/main`), so an oversized payload is fully decoded and
   allocated *before* any bound check runs — the field-level bound (Iron or
   validator) rejects it only post-allocation.

Three levers, in descending value. This is a **new phase with its own
implementation-grade elevation, file inventory, and hook token** — it does NOT
ride M1R's approval. Sequenced immediately after M1R.

**Lever 1 — Close the aggregate constructors (highest value).** Make
`RiskTree` and `TreeIndex` `final case class … private`, matching the rest of
the domain. `fromNodes` / `fromNodesUnsafe` become the sole gates, so ALL their
invariants — including the M1R count bounds sitting in `validateMitigations` —
become unbypassable at every construction site (`apply`, `.copy`, internal
builders, merges). Route the direct `RiskTree(...)` / `TreeIndex(...)`
construction sites (the ~7 in tests) through the smart constructors /
`fromNodesUnsafe`. **Compile-verification gate:** confirm the tapir `Schema`
auto-derivation (`generic.auto.*`) + zio-json codec still compile with a private
primary constructor; if they do not, switch `RiskTree`/`TreeIndex` to an
explicit `Schema` (`Schema.any` or hand-derived) — the pattern `Mitigation`
already uses. Verify green before committing the lever (convention-vs-hygiene
rule).

**Lever 2 — Reify tree-level collection bounds as Iron `MaxLength` types
(defense-in-depth; uniform or not at all).** Introduce `MaxLength`-refined
collection types for EVERY tree-level collection in one pass — `RiskTree.nodes`
and the `TreeIndex` maps, `RiskTree.mitigations`, `TransformPipeline.steps`,
`RiskPortfolio.children`, `RiskLeaf.percentiles`/`quantiles` — never a subset
(a half-refined domain is worse than a uniformly validated one). With Lever 1
in, the marginal value is defense through `fromNodesUnsafe` and internal
builders. Empty defaults become named safe-empty constants. First collection
refinement in the codebase — verify Iron collection `MaxLength` + zio-json +
Scala.js compile. Lowest-value lever; adopt only if the type-advertised bound is
judged worth the friction over Levers 1+3.

**Lever 3 — Bound the pre-allocation DoS at the transport boundary (the real
attacker-facing control).** Configure an HTTP request body-size limit in the
zio-http / tapir server so an oversized payload is rejected before the decoder
allocates it. This is the ONLY lever that addresses the decode-time allocation
vector, and it is independent of the type/validator layer. Locate the server
options (appears unset — verify) and set a limit.

**Open sub-decisions (resolve at elevation):**
- Max values per collection: `nodes`, `children`, `percentiles`/`quantiles`
  array length, body-size limit (bytes). (`mitigations` = 1000, `steps` = 10
  already set by §8.4-2, `steps` revised 2026-08-14.)
- Whether Lever 2 is adopted at all, or Levers 1 + 3 suffice.
- Whether to codify "aggregate types have private constructors" as an ADR
  (correct-by-construction rule), and add the body-size row to ADR-029. ADR-034
  Decision 4 already fixes this for `RiskResultGroup` (stays private, no
  exception); a dedicated ADR would generalize that to `RiskTree` / `TreeIndex`.

**ADR bearing:** strengthens ADR-001 / ADR-010 correct-by-construction; ADR-029
(input/DoS defence) gains the body-size-limit control. A dedicated
aggregate-constructor-privacy ADR is a candidate — it would lift ADR-034
Decision 4's `RiskResultGroup` rule to the remaining public-constructor
aggregates (`RiskTree`, `TreeIndex`).
