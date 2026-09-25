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
`docs/archive/plans/milestone-2b-cache-and-decisions.md` (DD-15 through DD-19),
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

### D4 — Provenance of a transform application (monoid B.7 decision 5) — ✅ DECIDED (2026-09-14)

A transform application is analytically meaningful and must be explainable
(ADR-003), so it needs a representation for "transform X with parameters Y was
applied". That representation is `MitigationApplicationRecord`, and §8.16 rules
where the records live: on the `applied` field of the `ValuationResult` the
mitigated fold returns, server-side. They are not carried on the wire; the
response tags each reading with `withMitigations`, and the client already holds
each mitigation's `spec` and `resolvedScope` from the tree read (§7.6.2
Decision 4).

The derivation behind this placement is in
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md);
consult it before re-opening the question.

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
  sibling vql-engine change (`docs/archive/plans/MITIGATION-PRE-PLANNING.md` §P-4). A
  mitigation's targeting predicate is a **restricted** sublanguage (closed in x, no answer variables, no quantifiers, no mitigation-state predicates; §8.4-3. Targeting ranges over the node sort only, and mitigation-state predicates are permanently barred (self-reference/fixpoint), so there is no other sort to quantify over — the no-quantifier rule costs no expressiveness).

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
`docs/archive/plans/MITIGATION-PRE-PLANNING.md` ("Decisions (ruled)"). The sibling
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
| **M4** | API surface + frontend: tree-PUT mitigation buckets, LEC endpoint selection parameter, per-node readings tagged with the mitigations that produced them, mitigation selection UI (see OD-3), two-tier badges, override edit-popup + stale badge | `common`, `server`, `app` | M1–M3 (badges/selection UI need only M1–M2; predicate-scope UI needs M3) |
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

/** One record per applied mitigation. It sits on the `applied` field of the
  * `ValuationResult` the mitigated fold returns, and never inside
  * `NodeProvenance`, which carries no identity. It does not cross the wire:
  * a response tags each reading with the mitigation ids that shaped it, and the
  * client already holds each mitigation's spec and resolved scope from the tree
  * read. (§8.16 rules the placement.) */
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
  * M4 in the request body (Decision 3). A NodesOnly restriction intersects with the
  * mitigation's resolved scope at application time: ids outside the current scope no-op. */
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

// The two nullary cases shipped as `Inherent` and `Residual`, matching ADR-034's
// valuation names; `None`/`All` above is the pre-rename sketch.

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

### 7.4 M4 — API surface + frontend (work items; elevate before build)

- **Tree PUT buckets**: `RiskTreeUpdateRequest` gains both mitigation buckets
  (ADR-017 pattern: identity-preserving `mitigations` + `newMitigations`).
  `RiskTreeDefinitionRequest` is the create DTO and by ADR-017 Decision 1
  carries no id-bearing bucket, so it gains `newMitigations` only. Tapir
  endpoint shape change (Decision Trigger #1 — covered by this plan once §7.6
  freezes the DTOs). This is D5's scope; which ADR the DTO/endpoint design
  lands in is open (§7.6.1 Decision 2 — ADR-034 does not cover it).
  With the buckets present the mitigation collection follows the node
  collection's omission-means-delete rule, so one request writes the whole
  aggregate. That makes the tree PUT a whole-tree PUT again: today the request
  enumerates the node set, the tree name and `seedVarHighWater` but has no
  mitigation field at all, which is why `RiskTreeServiceLive.update` carries
  `mitigations = oldTree.mitigations` over from its own read. Landing the
  buckets must delete that carry-over and its comment and resolve mitigations
  from the request instead; left in place it would make the new buckets inert.
- **LEC endpoints**: one `MitigationSelection` per request (Decision 11),
  carried in a **JSON request body on both endpoints** (Decision 3), which makes
  `prob-of-exceedance` a POST. `Inherent` is the wire spelling of "no
  mitigations" and is identity, so a caller that does not select anything reads
  exactly the figure it reads today. A response carries the inherent curves and
  the curves under that one selection, each tagged with the mitigation ids that
  produced it (`withMitigations`); the `MitigationApplicationRecord` layer stays
  server-side on the valuation and is not carried on the wire, because its `spec`
  and `resolvedScope` are already in the client's hands from the tree read
  (Decision 4, and §8.16 for where the records live). Comparing
  several selections means several requests, one per Compare slot. Each toggle
  issues its own request; there is no server-push refetch on this path — the
  notification channel carries node invalidation only, and no browser consumer
  for it exists (Decision 1).
- **Tree read**: the structure endpoint gains each mitigation's **resolved scope
  and `ScopeOutcome`** (Decision 12), so the selection interface can draw a
  mitigation's rows, and show which mitigations no longer match any node, before
  anything is ticked. The mitigation definitions themselves already cross the
  wire — `RiskTree.mitigations` is part of the tree's serialized form. The
  resolved scopes travel beside the tree in the same response, not inside
  `RiskTree`, which is the persisted content type.
- **Frontend**: per-mitigation selection UI per OD-3's refined model —
  mitigation child-styled rows under scoped nodes (per-(mitigation, node)
  enablement) + global tri-state control; a curve is identified by
  (node, variant), where a variant is one selection and `Inherent` is the empty
  selection — a node has one curve per variant, never a fixed pair, since the
  mitigations in a selection compose into a single valuation. **A selection is a
  set**: any subset of the tree's mitigations can be applied together, each with
  its own scope restriction, and the comparison axis is between selections —
  `{m1, m2, m3}` against `{m1, m2}` is two variants, one per Compare slot, and
  the difference between their curves is m3's contribution in the presence of
  the others. A single-mitigation selection is the one-element case, not a
  distinct concept. Because one request carries one selection (Decision 11),
  comparing several selections costs one slot each, and the slot pool is one per
  palette family — at most eight selections on screen together. The content
  cache absorbs most of the repetition: two selections agreeing on a leaf's
  param-stage transforms produce the same leaf content and hit the same entry,
  and a result-stage mitigation changes no leaf content at all, so N selections
  cost N round trips rather than N simulations; within-view
  variant curves beside the inherent one, with a client-side display mode giving
  a purely mitigated (residual-risk) view — granularity per OD-3c; colour stays
  node identity and a variant is distinguished by **point-marker shape**
  (Decision 7), with the curve cap counting nodes; **Compare slots
  gain mitigation selection as a slot dimension** (Decision 6) with a
  copy-for-compare gesture (variant comparison: inherent vs fw vs fw+IDS as
  slots, overlay/side-by-side, slot-keyed colours; comparand slots display their
  variant only, baseline shows the inherent curve);
  toggle↔curve colour consistency; two-tier badges (directly-scoped solid,
  affected-by-descendant faint + tooltip); override edit-popup flow (ADR-019
  Pattern 6 state machine) + stale badge surfacing; ADR-019
  ownership rules throughout.
- **Change notification**: `InvalidationHandler.computeAffectedNodes` diffs node
  membership and node content hashes only, so a mitigation edit changes every
  affected node's results while the diff reports nothing. M4 extends it to diff
  the mitigation collection and publish the affected nodes (Decision 1), which
  gives `InvalidationHandler` a scope-resolver dependency on the write path. The
  browser has no consumer for these messages, so M4's publish reaches zero
  subscribers; building the consumer is M5 scope (§7.7), not an M4 omission.
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
  are mitigated; the tree read reports Firewall's
  `resolvedScope = {srv-web, srv-db}`, and both leaves' readings name Firewall in
  `withMitigations`.
- You add a leaf `srv-mail` under `Servers` (tree version 2). Scope re-resolves per tree
  version, so `srv-mail` enters Firewall's scope automatically. Because the
  enablement is full-scope, `srv-mail` is mitigated with no further action,
  and the tree read at version 2 reports the three-node set. Reading version 1
  still reports two nodes — scope is a function of the tree version, so past
  versions are not retro-altered.
- Same story but Firewall was enabled per-node on `srv-web` only: after adding
  `srv-mail`, only `srv-web` stays mitigated. A restriction is an explicit
  list; new scope members are not silently pulled into it. `srv-mail` shows
  Firewall's row (in scope, badge visible) unticked until clicked.
- You delete `srv-web` while the per-node restriction names it: the stored id
  no longer intersects the resolved scope, so the selection applies nothing —
  a no-op, not an error. Selections are client-side view state, so nothing
  persistent goes stale.

### 7.5 M3 implementation-grade elevation — analytics VQL: monomorphic mitigation-selection argument + targeting predicates (rewritten 2026-09-10)

This rewrite supersedes the 2026-09-08 draft (which kept `p95`/`p99`/`lec`
mitigation-free and modelled selection as three predicates only). Two rulings
drive it:

- **M3 analytics-VQL decisions (2026-09-10):** D6=C (per-call selection
  argument), OD-1=A (ship both the selection argument *and* the screening
  predicates), OD-2=C (by-name and by-id are two separate identity predicates),
  OD-4=A (a what-if bound variable fans out to one precomputed residual per
  mitigation; unbounded fan-out accepted), OD-5=D (`getById` returns the
  resolved `CommitHash` in place), M3-D2=A (`mitigated(x)` = union of *Resolved*
  scopes only), M3-D5 (`unmitigated` ships as the complement of the same set).
- **Monomorphic per-sort surface syntax (2026-09-10):** register declares a
  *distinct identity symbol per entity sort* rather than asking the engine for
  sort polymorphism. Consequence: **the engine needs no change** — vql stays at
  0.17.0. Node identity predicates are renamed to the uniform scheme
  (`named_risk`/`risk_id`, Decision 1=A); mitigation identity uses the parallel
  pair (`named_mitigation`/`mitigation_id`); the scheme extends later to
  `named_asset`/`asset_id`. The two aggregate valuations are mitigation-sort
  *constants* `inherent`/`residual` (Decision 2 renamed the ADT cases to match:
  `MitigationSelection.Inherent`/`.Residual`; no `Raw`/`All`/`None` terminology
  anywhere — vql surface or Scala).

Everything upstream of M3 has landed and is exploited here rather than rebuilt:

- M1R (§8.6): vql adoption sweep, `TargetingPredicate` + parser boundary.
- M2 slice 2 (§8.13): `MitigationScopeResolver` + `ResolvedScopes` + per-`(TreeId,
  BranchRef, CommitHash)` memoization; `MitigationScopeResolverRegistry` per-workspace factory.
- M2 slice 3 (§8.14): `CachedResultResolver.ensureCached`/`ensureCachedAll`
  already take `selection: MitigationSelection` and `resolvedScopes`;
  `MitigationApplication.effectiveTree` already bakes a selection into a
  content-addressed effective tree, so precomputing one result map per selection
  reuses the existing cache with no new machinery.
- M2 slice 4 (§8.15): override staleness detection.

What genuinely remains for M3: (1) surface mitigations in the query language —
the selection argument on the value functions plus the identity and screening
predicates; (2) wire `QueryServiceLive` to bind once, discover the referenced
selections, precompute one result set per selection, and resolve scopes; (3) the
terminology/naming rulings; (4) the ADR-028 memoization rider.

#### 7.5.1 Scope

In scope:

1. `MitigationSelection` case rename `None`→`Inherent`, `All`→`Residual`
   (Decision 2) across `common` + every server call site + specs + the wire
   codec kind strings. `Selected(entries: Map[MitigationId, ScopeRestriction])`
   is unchanged; a specific/what-if mitigation is a single-entry `FullScope`
   selection.
2. `RiskTreeKnowledgeBase`: add the `Mitigation` domain sort and the two
   mitigation literal sorts; the `inherent`/`residual` constants; the identity
   predicates `named_mitigation`/`mitigation_id`; the screening predicates
   `mitigate`/`mitigated`/`unmitigated`; the mitigation-sort selection argument
   on `p95`/`p99`/`lec` (D6=C arity break). Results become keyed by
   `MitigationSelection`. Node identity predicates renamed `named`→`named_risk`,
   `has_id`→`risk_id` (Decision 1=A); `eq` unchanged.
3. `QueryServiceLive`: resolve scopes via the per-workspace `MitigationScopeResolverRegistry`;
   bind once against the catalog; walk the bound AST for referenced selections;
   precompute one result map per selection; build the KB from the
   selection-keyed results and resolved scopes.
4. `RiskTreeRepository.getById` returns `(RiskTree, CommitHash)` in place (OD-5=D).
5. `FolSymbols.reservedNames` gains the renamed/new symbols and the two
   constants; the mirror definition and its drift test extend to cover constant
   names (previously functions ∪ predicates only).
6. ADR-028 Decision 5 memoization rider.

Out of scope (M4 territory):

- DTO/endpoint changes for mitigation-aware queries; projecting `MitigationId`
  in `QueryResponseBuilder`; frontend consumption.
- The mitigation create/update API and its DTO-level reserved-name gate for
  mitigation names — M3 uses a KB-level alarm-on-bypass for mitigation names
  (`mitigationNameCollisions`), mirroring the existing node pattern.
- The enumerated-subset selection form `{a,b}` (engine list-valued argument —
  routed to `../vague-quantifier-logic/docs/PROMPT-SET-VALUED-ARGUMENT-TERM.md`).

#### 7.5.2 Surface syntax and selection semantics

The value functions gain a mitigation-selection *term* in their last argument
slot; the term's sort is `Mitigation`. Three kinds of term are accepted there,
and only three (the slot has no quoted-literal validator, so a bare `"…"` in it
never binds):

| Surface form | Term kind at bind | `MitigationSelection` |
|---|---|---|
| `p95(x, "inherent")` | `ConstRef("inherent", Mitigation)` | `Inherent` (raw, mitigation-free) |
| `p95(x, "residual")` | `ConstRef("residual", Mitigation)` | `Residual` (all applicable) |
| `∃m : mitigation . … ∧ p95(x, m) …` | `VarRef(m: Mitigation)` | `Selected(Map(mᵢ.id → FullScope))` per every mitigation `mᵢ` |

Specific selection is expressed by *constraining* the bound variable, exactly
mirroring node identity:

```
p95(x, "inherent") < 500000                                                    // raw
p95(x, "residual") < 500000                                                    // all applicable
∃m : mitigation . named_mitigation(m, "IT Risk mitigation") ∧ p95(x, m) < 500000   // specific, by name
∃m : mitigation . mitigation_id(m, "01H…")                 ∧ p95(x, m) < 500000   // specific, by id
∃m : mitigation .                                            p95(x, m) < 500000   // what-if over all
```

Why the two constants are the *name string* at runtime: the engine evaluates a
`ConstRef(name, sort)` to `Value(sort, name)` (the IR carries no payload —
[vql `TypedSemantics`]), while a bound `∃m : mitigation` carries a
`MitigationId` drawn from the runtime domain. The KB dispatcher discriminates on
that carrier: the string `"inherent"`/`"residual"` maps to the aggregate cases,
a `MitigationId` maps to a single-entry `Selected`. Constants are resolved by
name separately from the `∃`-domain enumeration, so `inherent`/`residual` are
**not** members of `∃m : mitigation` — a what-if ranges over real mitigations
only.

Why the specific-by-name and what-if forms are indistinguishable at precompute
time: `p95(x, m)` carries a `VarRef` regardless of any `named_mitigation`
constraint elsewhere in the formula. `referencedSelections` therefore fans a
bound mitigation variable out to a `Selected` per mitigation (OD-4=A). The
`named_mitigation`/`mitigation_id` constraint narrows *which* bindings satisfy
the formula at evaluation; it does not narrow what is precomputed. Unbounded
fan-out over `tree.mitigations` is the accepted cost of OD-4=A.

Each surface case maps to the existing `MitigationSelection` ADT (ADR-034
valuations): `inherent` → `Inherent` → (raw, ∅); `residual` → `Residual` →
(mitigated, all applicable); a bound/specific `m` → `Selected(Map(id →
FullScope))` → (mitigated, {m}).

#### 7.5.3 Exact signatures

**(a) `MitigationSelection` case rename** —
`modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationApplication.scala`.
`None`→`Inherent`, `All`→`Residual` (Decision 2); `Selected` unchanged. The
scaladoc, the `scoped` match arms, and the wire codec kind strings move with the
names:

```scala
object MitigationSelection {
  /** Mitigation-free valuation (ADR-034 raw): no mitigation applied. The default
    * on every existing read path (OD-5). Named for the domain term (inherent
    * risk = before controls) and to avoid shadowing `scala.None`. */
  case object Inherent extends MitigationSelection
  /** Residual valuation (ADR-034): every applicable mitigation applied
    * (residual risk = after controls). */
  case object Residual extends MitigationSelection
  final case class Selected(entries: Map[MitigationId, ScopeRestriction]) extends MitigationSelection

  given Equal[MitigationSelection] = Equal.default
  private case class Raw(kind: String, entries: Option[Map[MitigationId, ScopeRestriction]])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }
  given codec: JsonCodec[MitigationSelection] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case Inherent          => Raw("inherent", scala.None)
      case Residual          => Raw("residual", scala.None)
      case Selected(entries) => Raw("selected", Some(entries))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("inherent", scala.None)    => Right(Inherent)
      case Raw("residual", scala.None)    => Right(Residual)
      case Raw("selected", Some(entries)) => Right(Selected(entries))
      case other => Left(s"invalid mitigation selection kind '${other.kind}'")
    }
  )
}
```

The `scoped` match arm `case MitigationSelection.None => Nil` becomes
`case MitigationSelection.Inherent => Nil`; `case MitigationSelection.All =>`
becomes `case MitigationSelection.Residual =>`. The wire codec is not yet
consumed by any endpoint (M4), so changing the kind strings is not a live API
break; it keeps one vocabulary end-to-end.

Call-site migration (rename only): `CachedResultResolver.scala` and
`CachedResultResolverLive.scala` defaults `= MitigationSelection.None` →
`= MitigationSelection.Inherent`; specs `CachedResultResolverSpec`,
`MitigationStalenessSpec`, `MitigationApplicationSpec`.

**(b) `RiskTreeKnowledgeBase` companion — sorts, constants, `Extract`** —
`modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`:

```scala
object RiskTreeKnowledgeBase:
  val NodeSort: TypeId                  = TypeId("Node")
  val NodeNameLiteralSort: TypeId       = TypeId("NodeNameLiteral")
  val NodeIdLiteralSort: TypeId         = TypeId("NodeIdLiteral")
  /** Domain sort for tree-level mitigations. Carrier: `MitigationId`. A
    * `DomainType` so `∃m : mitigation …` is well-typed and the runtime domain
    * enumerates the tree's mitigations. */
  val MitigationSort: TypeId            = TypeId("Mitigation")
  /** Value sort for a mitigation reference written as a quoted NAME literal
    * (`named_mitigation(m, "IT Risk")`). Carrier: `MitigationId`, resolved at
    * bind time by the name→id validator. */
  val MitigationNameLiteralSort: TypeId = TypeId("MitigationNameLiteral")
  /** Value sort for a mitigation reference written as a quoted ID literal
    * (`mitigation_id(m, "01H…")`). Carrier: `MitigationId` via `fromString`. */
  val MitigationIdLiteralSort: TypeId   = TypeId("MitigationIdLiteral")

  /** The two aggregate-valuation constants. Mitigation-sort so they sit in the
    * value functions' selection slot; reserved names (see FolSymbols) because a
    * constant of this sort in any other slot binds as `TypeMismatch`. */
  val InherentConst: String = "inherent"
  val ResidualConst: String = "residual"

  given Extract[NodeId] with … // unchanged
  given Extract[MitigationId] with
    def apply(v: Value): Either[String, MitigationId] = v.raw match
      case id: MitigationId => Right(id)
      case other            =>
        Left(s"Extract[MitigationId]: expected MitigationId carrier for sort '${v.sort.value}', got $other")
```

**(c) `RiskTreeKnowledgeBase` constructor — results keyed by selection** — same file:

```scala
class RiskTreeKnowledgeBase(
  tree:               RiskTree,
  resultsBySelection: Map[MitigationSelection, Map[NodeId, LossDistribution]],
  resolvedScopes:     Map[MitigationId, Set[NodeId]]
):
  val mitigationSort: TypeId            = RiskTreeKnowledgeBase.MitigationSort
  val mitigationNameLiteralSort: TypeId = RiskTreeKnowledgeBase.MitigationNameLiteralSort
  val mitigationIdLiteralSort: TypeId   = RiskTreeKnowledgeBase.MitigationIdLiteralSort
```

`resolvedScopes` is the caller's `ResolvedScopes.appliedScopes` projection
(Failed outcomes already excluded — the projection every consumer uses, M3-D2=A).
`resultsBySelection` holds one result map per selection the query references
(precomputed by `QueryServiceLive`); an empty map is valid when the query uses
no value function.

**(d) Mitigation name→id + alarm-on-bypass** — same file, mirroring the node
`riskNameToId`/`riskNameCollisions`:

```scala
val mitigationNameToId: Map[String, MitigationId] =
  tree.mitigations.iterator.collect {
    case m if !reservedFolNames.contains(m.name.value) => m.name.value -> m.id
  }.toMap

/** Mitigation names skipped because they collide with a reserved catalog
  * symbol/constant. Empty in the supported flow; surfaced for the orchestrating
  * service to log, exactly like `riskNameCollisions` for node names. */
val mitigationNameCollisions: List[String] =
  tree.mitigations.map(_.name.value)
    .filter(reservedFolNames.contains).distinct.sorted
    .map(n => s"reserved-mitigation:$n")
```

**(e) Catalog additions / renames** — `types` gains
`DomainType(mitigationSort)`, `ValueType(mitigationNameLiteralSort)`,
`ValueType(mitigationIdLiteralSort)`; `constants` gains the two valuation
constants; the value functions gain the selection slot; the node identity
predicates are renamed and the mitigation identity/screening predicates added:

```scala
constants = Map(
  InherentConst -> mitigationSort,
  ResidualConst -> mitigationSort
),
functions = Map(
  SymbolName("p95") -> FunctionSig(List(nodeSort, mitigationSort), lossSort),
  SymbolName("p99") -> FunctionSig(List(nodeSort, mitigationSort), lossSort),
  SymbolName("lec") -> FunctionSig(List(nodeSort, lossSort, mitigationSort), probabilitySort)
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
  SymbolName("named_risk")         -> PredicateSig(List(nodeSort, nodeNameLiteralSort)),   // was "named"
  SymbolName("risk_id")            -> PredicateSig(List(nodeSort, nodeIdLiteralSort)),     // was "has_id"
  SymbolName("named_mitigation")   -> PredicateSig(List(mitigationSort, mitigationNameLiteralSort)),
  SymbolName("mitigation_id")      -> PredicateSig(List(mitigationSort, mitigationIdLiteralSort)),
  SymbolName("mitigate")           -> PredicateSig(List(nodeSort, mitigationSort)),
  SymbolName("mitigated")          -> PredicateSig(List(nodeSort)),
  SymbolName("unmitigated")        -> PredicateSig(List(nodeSort))
),
literalValidators = Map(
  // node validators unchanged (comment references named_risk/risk_id):
  nodeSort                  -> ((s: String) => riskNameToId.get(s)),
  nodeNameLiteralSort       -> ((s: String) => riskNameToId.get(s)),
  nodeIdLiteralSort         -> ((s: String) => NodeId.fromString(s).toOption),
  lossSort                  -> ((s: String) => s.toLongOption.filter(_ >= 0L)),
  probabilitySort           -> ((s: String) => s.toDoubleOption.filter(d => d >= 0.0 && d <= 1.0)),
  mitigationNameLiteralSort -> ((s: String) => mitigationNameToId.get(s)),                 // named_mitigation's 2nd arg
  mitigationIdLiteralSort   -> ((s: String) => MitigationId.fromString(s).toOption)         // mitigation_id's 2nd arg
)
```

No `literalValidator` is registered for `mitigationSort` itself: the value
functions' selection slot accepts only the two constants or a bound variable — a
bare quoted literal there deliberately fails to bind.

**(f) Dispatcher — selection extraction, value functions, identity, screening**:

```scala
// Maps a mitigation-sort argument Value to the selection it denotes. The
// inherent/residual constants arrive as their own name string (ConstRef →
// Value(sort, name)); a bound ∃m carries a MitigationId from the domain.
private def selectionOf(v: Value): Either[String, MitigationSelection] = v.raw match
  case RiskTreeKnowledgeBase.InherentConst => Right(MitigationSelection.Inherent)
  case RiskTreeKnowledgeBase.ResidualConst => Right(MitigationSelection.Residual)
  case id: MitigationId                    =>
    Right(MitigationSelection.Selected(Map(id -> ScopeRestriction.FullScope)))
  case other =>
    Left(s"mitigation selection: unrecognised carrier '$other' in sort '${v.sort.value}'")

private def resultsFor(sel: MitigationSelection): Either[String, Map[NodeId, LossDistribution]] =
  resultsBySelection.get(sel).toRight(
    s"no precomputed results for selection '$sel' (referencedSelections omitted it)"
  )

private def lookupResult(rs: Map[NodeId, LossDistribution], id: NodeId, ctx: String) =
  rs.get(id).toRight(s"$ctx: no simulation result for node '${id.value}'")

// functions:
SymbolName("p95") -> { args =>
  for
    id  <- args(0).extract[NodeId]
    sel <- selectionOf(args(1))
    rs  <- resultsFor(sel)
    r   <- lookupResult(rs, id, "p95")
  yield percentile(r, 0.95)
},
SymbolName("p99") -> { args => /* args(1) selection, percentile 0.99 */ },
SymbolName("lec") -> { args =>
  for
    id        <- args(0).extract[NodeId]
    threshold <- args(1).extract[Long]
    sel       <- selectionOf(args(2))
    rs        <- resultsFor(sel)
    r         <- lookupResult(rs, id, "lec")
  yield r.probOfExceedance(threshold)
}

// M3-D5 approach (a): unmitigated is the complement of the SAME set mitigated
// reads, so `unmitigated ≡ ¬mitigated` holds by construction.
private val mitigatedIds: Set[NodeId] =
  resolvedScopes.valuesIterator.foldLeft(Set.empty[NodeId])(_ union _)

private val mitigationIdentity: List[Value] => Either[String, Boolean] = args =>
  for
    a <- args(0).extract[MitigationId]
    b <- args(1).extract[MitigationId]
  yield a == b

// predicates:
SymbolName("eq")               -> nodeIdentity,
SymbolName("named_risk")       -> nodeIdentity,       // was "named"
SymbolName("risk_id")          -> nodeIdentity,       // was "has_id"
SymbolName("named_mitigation") -> mitigationIdentity,
SymbolName("mitigation_id")    -> mitigationIdentity,
SymbolName("mitigate")         -> { args =>
  for
    node <- args(0).extract[NodeId]
    mid  <- args(1).extract[MitigationId]
  yield resolvedScopes.getOrElse(mid, Set.empty).contains(node)
},
SymbolName("mitigated")   -> { args => args(0).extract[NodeId].map(mitigatedIds.contains) },
SymbolName("unmitigated") -> { args => args(0).extract[NodeId].map(id => !mitigatedIds.contains(id)) }
```

**(g) Domain population** — `RuntimeModel.domains` gains the mitigation domain:

```scala
private val mitigationDomain: Set[Value] =
  tree.mitigations.iterator.map(m => Value(mitigationSort, m.id)).toSet

val model: RuntimeModel = RuntimeModel(
  domains    = Map(nodeSort -> nodeDomain, mitigationSort -> mitigationDomain),
  dispatcher = dispatcher
)
```

**(h) Reserved symbols** —
`modules/common/src/main/scala/com/risquanter/register/common/FolSymbols.scala`.
The mirror now covers functions ∪ predicates ∪ constants:

```scala
val reservedNames: Set[String] = Set(
  // predicates
  "leaf", "portfolio", "child_of", "descendant_of", "leaf_descendant_of",
  "gt_loss", "gt_prob", "eq", "named_risk", "risk_id",
  "named_mitigation", "mitigation_id", "mitigate", "mitigated", "unmitigated",
  // functions
  "p95", "p99", "lec",
  // mitigation-sort aggregate-valuation constants
  "inherent", "residual"
)
```

The C4 drift test in `RiskTreeKnowledgeBaseSpec` currently asserts
`reservedFolNames == functions ∪ predicates`; it must widen to
`functions ∪ predicates ∪ constants.keySet`.

**(i) `getById` returns the resolved commit hash (OD-5=D)** —
`modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala:30`:

```scala
def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): Task[Option[(RiskTree, CommitHash)]]
```

Ripple (from the OD-5=D ruling): `RiskTreeRepositoryIrmin.scala:85` plumbs the
hash `loadTreeAt`/`TreeWithMeta` already resolved; `RiskTreeRepositoryInMemory.scala:77`
returns a deterministic synthetic hash (dev/test-only backend);
`RiskTreeServiceLive` threads the hash through its private read helpers
(`getTreeOrFail`, `lookupNodeInTree`, `lookupNodesInTree`). Per the later F8
ruling the widening reaches the public `RiskTreeService` trait as well, and the
hash is discarded at the wire boundary by the three controller call sites; the
Tapir endpoint shapes are unchanged. Decision Trigger #4 — pre-ruled OD-5=D.

**(j) `QueryServiceLive` wiring** —
`modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`.
Add `MitigationScopeResolverRegistry` (the per-workspace resolver factory, mirrors
`ContentCacheRegistry`; one resolver instance per workspace, so cross-workspace scope
contamination is structurally impossible). `evaluate` binds once against the
catalog to discover referenced selections, precomputes one result map per
selection, then builds the KB:

```scala
class QueryServiceLive private (
  repo:          RiskTreeRepository,
  resolver:      CachedResultResolver,
  scopeResolver: MitigationScopeResolverRegistry,        // NEW
  tracing:       Tracing
) extends QueryService:

  override def evaluate(wsId, treeId, parsed, seedEntityId, branch): Task[QueryResponse] =
    traced("evaluate") {
      for
        (tree, commitHash) <- repo.getById(wsId, treeId, Revision.Head(branch)).flatMap {
                                case Some(t) => ZIO.succeed(t)
                                case None    => ZIO.fail(treeNotFound(treeId))
                              }
        mitResolver <- scopeResolver.forWorkspace(wsId)                    // UIO
        resolved    <- mitResolver.resolve(ScopeResolutionContext(treeId, branch, commitHash), tree)
        allNodeIds   = tree.index.nodes.keySet

        // Bind once against the (results-independent) catalog to learn which
        // selections the query references. The catalog is identical to the
        // eval-time KB's catalog (both built from `tree`), so this bind is sound.
        // A bind failure here needs no handling: precompute nothing and let
        // `evaluateTyped` below re-bind and surface the classified error, exactly
        // as today (no new error path).
        kbShell      = RiskTreeKnowledgeBase(tree, Map.empty, resolved.appliedScopes)
        selections   = QueryBinder.bind(parsed, kbShell.catalog)
                          .map(b => MitigationSelectionScan.referenced(b, tree))
                          .getOrElse(Set.empty[MitigationSelection])

        // One precompute per selection over that selection's effective tree; the
        // content-addressed cache dedups identical effective trees, so no new
        // cache machinery. Inherent → base results; Residual/Selected → residual.
        resultsBySelection <- ZIO.foreach(selections.toList) { sel =>
                                resolver.ensureCachedAll(tree, allNodeIds, seedEntityId,
                                    selection = sel, resolvedScopes = resolved.appliedScopes)
                                  .mapError(_ => FolQueryFailure.SimulationNotCached(treeId): Throwable)
                                  .map(sel -> _)
                              }.map(_.toMap)

        kb           = RiskTreeKnowledgeBase(tree, resultsBySelection, resolved.appliedScopes)
        _           <- logNameCollisions(kb)          // node + mitigation collisions
        _           <- logResolutionFailures(resolved) // ResolvedScopes.failures, ADR-002
        folModel    <- ZIO.fromEither(FolModel(kb.catalog, kb.model)).mapError(FolQueryFailure.fromQueryError)
        output      <- ZIO.fromEither(VagueSemantics.evaluateTyped(parsed, folModel,
                          answerTuple = Map.empty, samplingParams = SamplingParams.exact,
                          hdrConfig = HDRConfig.default)).mapError(FolQueryFailure.fromQueryError)
        response     = QueryResponseBuilder.from(output, parsed.toString)
      yield response
    }

object QueryServiceLive:
  val layer: ZLayer[
    RiskTreeRepository & CachedResultResolver & MitigationScopeResolverRegistry & Tracing,
    Nothing, QueryService
  ] = ZLayer { … }
```

`evaluateTyped` re-binds `parsed` internally; the referenced-selections bind is a
second, pure, cheap bind against the same catalog (accepted — no lower-level
`BoundQuery` eval entry point is used).

**(k) `referencedSelections` walk** — a small register-side helper
(`MitigationSelectionScan` object in the same file). Walks every `BoundFormula`
in the bound query (range + scope + any satisfying formula); at each
`BoundTerm.FnApp("p95"|"p99"|"lec", args, _)` it inspects the mitigation-slot
term (last arg for `p95`/`p99`, arg index 2 for `lec`):

```scala
object MitigationSelectionScan:
  def referenced(bound: BoundQuery, tree: RiskTree): Set[MitigationSelection] =
    // fold over BoundFormula/BoundTerm; at a value-function FnApp:
    //   ConstRef(InherentConst, _) => + Inherent
    //   ConstRef(ResidualConst, _) => + Residual
    //   VarRef(BoundVar(_, MitigationSort)) =>
    //       ++ tree.mitigations.map(m => Selected(Map(m.id -> FullScope)))   // OD-4=A fan-out
    //   (LiteralRef here is impossible — no mitigationSort literal validator)
    …
```

**(l) `MitigationScopeResolverLive` internal KB construction** —
`modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`.
The targeting-only KB construction becomes
`RiskTreeKnowledgeBase(tree, Map.empty, Map.empty)` (results-free / scopes-free;
targeting evaluates predicates only). No signature change.

**(m) `Application.scala`** —
`modules/server/src/main/scala/com/risquanter/register/Application.scala` adds
`MitigationScopeResolverRegistry.layer` to the layer graph (built in M2, no live call site
until now) and updates the `QueryServiceLive.layer` requirement.

**(n) App default/placeholder query strings** — the arity break makes the
current `p95(x)` defaults invalid. `modules/app/.../AnalyzeQueryState.scala:37`
and `modules/app/.../views/AnalyzeView.scala:518` migrate `p95(x)` →
`p95(x, "inherent")`.

**(o) ADR-028 Decision 5 amendment** —
`docs/dev/decision-records/ADR-028-vague-quantifier-query-pane.md`: rider
recording that the analytics KB shell is built per query, but its `results` (via
`CachedResultResolver`, per selection) and `resolvedScopes` (via
`MitigationScopeResolver`) are memoized upstream per tree version — so the
per-query construction is cheap wiring, not the work.

#### 7.5.4 Decisions — all ruled (no open decisions)

Every M3 decision is ruled; this section records the outcome and its provenance.
The earlier M3-D1…M3-D6 draft labels are folded in where they map to a ruling.

- **D6 = C (per-call selection argument).** `p95`/`p99` become 2-ary, `lec`
  3-ary, with a mitigation-sort selection term. Hard arity break — every
  existing `p95(x)`/`p99(x)`/`lec(x,l)` migrates (§7.5.6).
- **Selection surface = monomorphic per-sort (2026-09-10).** Two aggregate
  constants `inherent`/`residual`; specific selection via `named_mitigation` /
  `mitigation_id` constraining a bound `∃m : mitigation`; what-if via an
  unconstrained bound variable (§7.5.2). Supersedes the earlier OD-2 "two
  literal sub-sorts on the selection argument" framing (now two identity
  predicates) and the earlier "raw"/"all" reserved-word framing (now `inherent`/
  `residual` mitigation-sort constants).
- **OD-1 = A.** Ship both layers: the selection argument on the value functions
  *and* the screening predicates `mitigate`/`mitigated`/`unmitigated`.
- **OD-4 = A.** A what-if bound variable fans out to one precomputed residual per
  mitigation; unbounded fan-out accepted (§7.5.2 rationale).
- **OD-5 = D.** `getById` returns `Option[(RiskTree, CommitHash)]` in place — one
  honest method that reports which concrete head it loaded, no second call, no
  race. The
  memo-keys-on-`CommitHash` half landed at M2 §8.13; OD-5=D only settles how the
  query path acquires the hash. (Replaces the earlier M3-D3, whose Option B
  `getByIdAt` companion is rejected as a permanent near-duplicate and whose
  Option A/C are unsound — the race and the content-token staleness.)
- **M3-D1 (constructor) = required parameters.** `resultsBySelection` and
  `resolvedScopes` are both required (no defaults) — a default would silently
  make `mitigate`/`mitigated` return false and `p95` fail for every input.
- **M3-D2 = A.** `mitigated(x)` = union of *Resolved* scopes only
  (`ResolvedScopes.appliedScopes`, Failed excluded). A node covered only by a
  Failed mitigation reads `unmitigated(x) = true`. The failure surface stays on
  the mitigation panel (ADR-028), not the analytics KB. Verified against code
  2026-09-10: general targeting stores source text and re-resolves per tree
  version (HYBRID dynamic scoping); a rename makes a `named_risk(x,"…")`
  predicate bind-fail → `ScopeOutcome.Failed`, a by-design drift signal
  (ADR-016). `risk_id`/`overrideAnchor` are rename-stable but deliberately not
  the default for name-based targeting.
- **M3-D4 (sort kind) = `DomainType`.** `Mitigation` is quantifiable so
  `∃m : mitigation …` is well-typed and the runtime domain enumerates the tree's
  mitigations. Matches how `Node` is declared.
- **M3-D5 = ship `unmitigated` first-class, approach (a).** `unmitigated(x)` is
  the complement of the *same* precomputed `mitigatedIds` set that `mitigated(x)`
  reads, so `unmitigated ≡ ¬mitigated` holds by construction. (The engine has no
  formula-level predicate aliasing, so the "not-mitigated" definition lives at
  the implementation level — one shared set, two dispatcher arms — never a query
  rewrite.)
- **Decision 1 = A (node predicate rename).** `named`→`named_risk`,
  `has_id`→`risk_id`; `eq` unchanged. Uniform `named_<sort>`/`<sort>_id` scheme,
  extensible to `named_asset`/`asset_id`. Small internal blast radius (3 source +
  3 test files + demo scripts + reserved-names mirror), no persisted queries yet.
- **Decision 2 (ADT case names).** `MitigationSelection.None`→`Inherent`,
  `All`→`Residual`. One vocabulary end-to-end: surface `inherent`/`residual`,
  ADT `Inherent`/`Residual`, ADR-034 raw/residual valuations. No `Raw`/`All`/
  `None` terminology in vql or Scala.

#### 7.5.5 ADR alignment (complete sweep — every register ADR checked explicitly)

**Namespace note.** Two ADR namespaces meet in the KB: register's own ADRs
(`docs/dev/decision-records/`) and vql-engine's ADRs (external artifact, cited
in the KB scaladoc). The sort-type *mechanism* (`DomainType`/`ValueType`,
`Extract[_]` consumer carrier) is vql-engine's — the KB comments cite vql
ADR-014 / vql ADR-015 §2 for it. Those are **not** register ADR-014 (RiskResult
Caching Strategy) or register ADR-015 (RiskResult Cache Integration); this
section labels vql-engine references as such to keep them distinct. (The shipped
KB comment `ValueType (ADR-014)` at `RiskTreeKnowledgeBase.scala:289` is a bare,
namespace-ambiguous citation — a pre-existing comment-only nit, not an M3
change; flagged, not fixed here.)

**Bearing on M3:**

- **ADR-001** (Validation / Iron / smart constructors): compliant. `RiskTree`
  supplies already-validated `MitigationId`; the KB never sees raw strings, and
  `resolvedScopes` is a projection of validated domain values.
- **ADR-010** (Error handling — typed channel): compliant. The resolver isolates
  every per-mitigation failure into `ResolvedScopes` and exposes no error channel
  (`UIO`); `QueryServiceLive` keeps the existing typed `FolQueryFailure` path. No
  new error condition is introduced.
- **ADR-014 / ADR-015 register** (RiskResult caching / cache integration): the KB
  reads simulation `results` through `CachedResultResolver.ensureCachedAll`
  exactly as today — content-addressed, no invalidation — but now once per
  referenced selection (Inherent → base tree; Residual/Selected → effective
  tree). Identical effective trees share cache entries by content address, so the
  per-selection fan-out adds no new cache machinery. Which valuation a query reads
  is fixed per call by its selection term (D6=C), not a global mode.
- **ADR-018** (Nominal wrappers): compliant. `MitigationId` is a nominal wrapper
  over `SafeId` (`OpaqueTypes.scala`); `Extract[MitigationId]` matches on that
  distinct runtime type, exactly mirroring `Extract[NodeId]` (vql ADR-015 §2
  consumer-carrier pattern).
- **ADR-020** (Supply chain): no dependency change. vql-engine already pinned at
  0.17.0; no new artifact, no cooldown/pin action.
- **ADR-028 + appendix** (Vague-quantifier query pane): (a) the sort-catalog
  design lives here, so declaring `Mitigation` a quantifiable domain sort plus the
  two mitigation literal sorts, renaming the node identity predicates
  (`named`→`named_risk`, `has_id`→`risk_id`), and adding the two valuation
  constants and the mitigation identity/screening predicates are register-side
  ADR-028 decisions applying vql-engine's sort mechanism — the appendix's
  predicate/sort/constant/function table is updated accordingly, and the value
  functions gain the selection argument. (b) Decision 5 amendment — "model
  *shell* built per-query; inputs (per-selection results + resolved scopes)
  memoized upstream per tree version", included in this slice.
- **ADR-029** (Input injection — parse, don't re-parse): compliant. The three
  predicates are registered catalog symbols, not string-interpolated; query
  source stays a single parse at the endpoint; no new parser boundary or
  interpolation surface.
- **ADR-030** (Authz at the orchestration boundary) + **ADR-024** (PEP pattern):
  compliant, and reinforced. No new endpoint; the query endpoint's existing
  capability gate is unchanged. `MitigationScopeResolverRegistry.forWorkspace(wsId)` takes a
  server-derived `WorkspaceId`, and the one-resolver-per-workspace design makes
  cross-workspace scope contamination structurally impossible — a tenancy
  isolation property, not just an absence of new surface.
- **ADR-031** (Startup readiness vs request-path resilience): compliant.
  `MitigationScopeResolverRegistry.layer` is a pure `Ref.make` with no external dependency, so
  it adds no startup-readiness dependency; `resolve` is `UIO`, so it adds no new
  request-path failure mode.
- **ADR-032** (Content equality — domain hash vs storage hash): **bears on
  OD-5=D.** A mitigation's resolved scope depends on node *names*
  (`named_risk(x, …)`), and the domain content hash omits names (reports
  `Identical` on a rename), while the Irmin storage hash changes on any byte edit
  including a rename. `ScopeResolutionContext.revision` is therefore the Irmin
  `CommitHash` (storage relation, ADR-032 §3; DD-16) — exactly the hash `getById`
  now returns in place (OD-5=D). A domain content token would serve a stale scope
  after a rename.
- **ADR-034** (Mitigation valuation model): **governs the selection argument.**
  ADR-034 defines two valuations — raw (mitigation-free, cached,
  content-addressed) and residual (derived at the read edge, never stored). The
  selection term names which one per call: `inherent` → raw, `residual`/specific
  → residual. Both already exist by design, so the argument invents no machinery.
  The ADT case names `Inherent`/`Residual` (Decision 2) are the register-code
  spelling of ADR-034's two valuations.
- **ADR-035** (Error leakage prevention): compliant. M3 adds **no** new
  `AppError`/`FolQueryFailure` subtype (it reuses `FolQueryFailure.fromQueryError`),
  so the compile-time exhaustive-`encode` guarantee is undisturbed. The new
  `Extract[MitigationId]` `Left` message interpolates the carrier and is a
  server-side carrier-mismatch signal that a well-formed query never reaches; it
  must not be echoed to the wire (it flows through `ErrorResponse.encode`
  sanitisation like every other internal message).
- **ADR-036** (Confidential internal identifiers): checked, no violation.
  `MitigationId` is a **client-facing** domain identifier — it appears in the
  `Mitigation` / `MitigationApplication` DTOs in `common` and is decoded from the
  wire via `MitigationId.fromString` — so it is in the same class as `NodeId` /
  `TreeId`, not a confined identifier like `WorkspaceId`. Surfacing it in the KB
  domain is therefore not a boundary crossing that ADR-036 forbids. M3 also does
  not project it into responses: `QueryResponseBuilder` filters
  `satisfyingElements` to `NodeSort` before extracting, so a `Mitigation`-sort
  value is structurally unprojectable. (Consistency note for the verification
  plan: `satisfyingCount`/`rangeSize` count the raw sets, so they stay equal to
  `satisfyingNodeIds.size` only while the query's free variable is `Node` — M3's
  single-free-node-variable shape holds this; an M4 query with a free `Mitigation`
  variable would diverge and is out of scope here.)
- **ADR-002** (Logging): minor observability item. The resolver exposes
  `ResolvedScopes.failures` (per-mitigation drift signals); `QueryServiceLive`
  should log these the way it already logs `kb.riskNameCollisions`. Not a blocker;
  fold into the wiring.
- **ADR-003** (Provenance/reproducibility): compliant. Resolved scopes and
  results are reproducible from stored inputs; the per-tree-version memo is a
  cache, not a provenance log.
- **ADR-009** (TrialOutcomes monoid aggregation): compliant. M3 changes no
  aggregation; it bears only transitively through ADR-034's raw fold.
- **ADR-033** (Exception boundaries): compliant. The KB dispatcher and the
  resolver are throw-free (`Either` / `UIO`); M3 adds no `catch` and no new
  Scala.js↔JS boundary.

**No bearing (checked, explicitly):** ADR-004a/004b (persistence SSE/WebSocket
proposals), ADR-005 (cached subtree aggregates proposal), ADR-006 (real-time
collaboration proposal), ADR-007 (scenario branching — M3 reads at an existing
branch head, no branching change), ADR-011 (import conventions — implementation style, not a design
bearing), ADR-012 (service mesh / JWT at waypoint — no app-code auth change),
ADR-016 (config management — no config change), ADR-017 (tree API / create-vs-update
DTOs — M4), ADR-019 (frontend — M4), ADR-021 (capability URLs — unchanged), ADR-022
(secret handling — `MitigationId` is not a credential; ADR-036 §3 confirms it is
lighter than a credential), ADR-023 (local dev TLS), ADR-025 (SPA routing), ADR-026
(container image strategy), ADR-027 (frontend nginx serving), ADR-INFRA-006 (DB
credentials), ADR-00X (meta template).

#### 7.5.6 Verification plan

Whole-suite-green is the bar (global CLAUDE.md rule). All four tiers must be green:

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
sbt serverIt/test
```

**Arity-break + rename migration (must land in the same change or nothing
compiles).** Every existing `p95(x)`/`p99(x)`/`lec(x,l)` occurrence gains the
selection argument (`inherent` unless the test's intent is residual); every
`named(`/`has_id(` query string is renamed to `named_risk(`/`risk_id(`.
Occurrences: `BinderIntegrationSpec`, `QueryEndpointSpec`, `DemoSimpleScriptSpec`,
`DemoEnterpriseScriptSpec`, `TargetingPredicateSpec`, and the two app
default/placeholder strings (`AnalyzeQueryState.scala:37`, `AnalyzeView.scala:518`).

New / extended coverage:

- `RiskTreeKnowledgeBaseSpec`:
  - `mitigate(x, m)` binds and evaluates for {node in scope, node out of scope,
    mitigation absent from `resolvedScopes`};
  - `mitigated(x)` = union of resolved-scope nodes; a Failed mitigation
    contributes nothing (M3-D2=A); `unmitigated(x)` is the exact complement of
    that same set over the node domain (M3-D5 approach (a));
  - `p95(x, "inherent")` reads base results; `p95(x, "residual")` and a bound
    `p95(x, m)` read the corresponding precomputed residual;
  - `named_mitigation(m, "…")` / `mitigation_id(m, "…")` bind and reduce to
    `MitigationId` equality; `named_risk`/`risk_id` behave as `named`/`has_id`
    did;
  - `inherent`/`residual` used in a node slot fail to bind (TypeMismatch); a
    node or mitigation named `inherent`/`residual` is surfaced via the collision
    diagnostics (`riskNameCollisions` / `mitigationNameCollisions`);
  - the C4 drift test widened to `functions ∪ predicates ∪ constants.keySet`.
- `MitigationSelectionScan` unit test: constant → Inherent/Residual; a bound
  mitigation variable fans out to one `Selected` per `tree.mitigations` element
  (OD-4=A); a query with no value function yields the empty set.
- `QueryServiceLiveSpec` / integration: a `mitigated(x)` query returns the node
  set `MitigationScopeResolver` reports; `p95(x, "residual") < t` reflects applied
  mitigations while `p95(x, "inherent") < t` does not, end-to-end; `getById`'s
  `(RiskTree, CommitHash)` tuple leaves public `RiskTreeService` behaviour
  unchanged.
- `MitigationApplicationSpec`, `CachedResultResolverSpec`, `MitigationStalenessSpec`:
  updated for the `Inherent`/`Residual` rename (behaviour unchanged).

M4 test surface (endpoints, DTOs, frontend) stays out of this slice.

#### 7.5.7 File inventory (append to shared `## File inventory` on approval)

Sources — `common`:
- `modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationApplication.scala` (ADT rename + `scoped` arms + codec kinds)
- `modules/common/src/main/scala/com/risquanter/register/common/FolSymbols.scala` (reserved-name mirror incl. constants)

Sources — `server`:
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala` (wiring + `MitigationSelectionScan`)
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala` (default rename)
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala` (default rename)
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala` (getById → tuple)
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala` (three `.map(_.map(_._1))` discards)

Sources — `app` (arity-break migration of default query strings):
- `modules/app/src/main/scala/app/state/AnalyzeQueryState.scala`
- `modules/app/src/main/scala/app/views/AnalyzeView.scala`

Tests:
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/QueryServiceLiveSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationApplicationSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/TargetingPredicateSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/QueryEndpointSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/DemoSimpleScriptSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/DemoEnterpriseScriptSpec.scala`

Docs:
- `docs/dev/decision-records/ADR-028-vague-quantifier-query-pane.md` (Decision 5 rider + predicate table rename/additions + example arity)
- `docs/dev/decision-records/ADR-028-appendix-technical-design.md` (schema tables: predicate rename/additions, mitigation constants, value-function selection arg; example arity)

The full repo-relative paths above must be added as bullets under the shared
top-level `## File inventory` heading before the hook will allow the edits; the
`QueryServiceLiveSpec.scala` path is created by this slice if absent.


Ammendment:
- `modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeReadConsistencySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemoryBranchSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala`

### 7.6 M4 implementation-grade elevation

Written for slices 1 to 5 (§7.6.4 names the slices). Those five carry exact
signatures, ADR alignment, a verification plan and a file inventory. Slice 6 —
the interface and the user documentation — is not elevated here. §7.6.12 lists
the seven open decisions: five gate the `ValuationResult` sub-slice ruled in
§8.16, which slice 1 consumes, and two gate slice 6. Slices 2 to 5 carry none. No M4 source edit is
authorized until the approval token names this plan and the edited file appears
in the shared `## File inventory`.

**Signatures below use the current names of the two per-workspace registries:
`ContentCacheRegistry` and `MitigationScopeResolverRegistry`, both handing out
their instance via `forWorkspace(...)`.**

**Reasoning source.** Where a question about the mitigated value's type, the two
folds, the identity case, or `flatten` is ambiguous in the sections below, the
derivation that settles it is
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md).
It builds the vocabulary, the raw fold, the obstruction, the Option F ruling and
each consequence in order, and it is the record to consult before re-deciding any
of them. Individual sections below point at the specific part that governs them.

#### 7.6.0 Design anchors

The mitigation design tenets, each verified against source. Every M4 decision is
checked against this list, and a decision that contradicts one is wrong or the
anchor is wrong — neither is settled silently.

| # | Anchor | Enforced at |
|---|---|---|
| A1 | Two valuations, never one merged value. Raw is the mitigation-free fold and is cached; mitigated is a second fold at the read edge and is never stored. Why one valuation cannot serve both is derived in the reasoning document, Part 3 | ADR-034 §1 |
| A2 | Stage determines **where** a mitigation may apply: `LeafStage` leaves only, `ResultStage` any node | the stage-domain intersection in `MitigationScopeResolverLive.resolveOne` |
| A3 | Stage determines **when** it applies and therefore whether it is cached. `LeafStage` is baked in **before** hashing, so a mitigated leaf hashes differently and becomes its own content-addressed entry. `ResultStage` is applied **after** the lookup returns and is never cached | `CachedResultResolverLive` steps 1–3 |
| A4 | Composition within a node is function composition in precedence order, not addition. Leaf: `foldLeft(applyTo)`. Result: `reduceOption(_.andThen(_))`. Order `(precedence.key, id.value)` | `MitigationApplication` |
| A5 | A node's mitigated value folds its children's **mitigated** values, never the raw aggregate with a transform laid over it. The worked example is in the reasoning document, Part 4 | ADR-034 §1, §3 |
| A6 | Portfolios are never cached; the aggregate is recomputed on every read | `CachedResultResolverLive` step 4 |
| A7 | **A selection is a set** — any subset of the tree's mitigations, each with its own scope restriction — and one selection yields exactly **one** mitigated valuation per node. Several mitigations scoping a node compose into one curve, not one curve each. A single-mitigation selection is the one-element case, not a distinct concept. The comparison axis is between selections | `MitigationSelection.Selected`; follows from A4 |
| A8 | Scope is resolved server-side, per tree version, from a predicate. A predicate that stops binding makes that one mitigation a no-op plus a drift signal; it never fails the request | `MitigationScopeResolverLive`; `ScopeOutcome` |
| A9 | A selection restricts and can never extend — `NodesOnly` intersects the resolved scope, so an out-of-scope id silently does nothing | `MitigationApplication.scoped` |
| A10 | The defaults are identity: `Inherent` with empty scopes makes the whole mitigation path a no-op, so an unchanged caller gets an unchanged **figure**. This anchor constrains figures, not representation: under §8.16 an unchanged caller receives a `ValuationResult` whose outcomes are identical to what it reads today | OD-5; the resolver's default arguments |
| A11 | Nothing mitigated is ever persisted. The effective tree is built per request and discarded | ADR-034 §5 and its persistence code smell |
| A12 | The cache stores **content**, never mitigations. A param-stage mitigation is cached because it produces new content; a result-stage mitigation is not cached because it produces none | `ContentHashIndex`, `LeafSimContent` |

**A12 stated as one sentence:** a param-stage mitigation changes what must be
simulated; a result-stage mitigation changes what is done with a simulation.

**Reasoning source for A1, A5, A6 and A10.** These four state the two-fold model
and its identity case. Their derivation — why a result transform is not a monoid
homomorphism, why no single valuation can satisfy both the cache invariant and an
aggregate cap, and what type the mitigated value therefore has — is in
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md).
§8.16 records the rulings that follow from it. An ambiguity in any section below
about the mitigated value's type, the identity case or `flatten` is resolved
there, not by re-deriving it.

**Why a leaf is stored and a portfolio is not.** The cache key is
`sha256(LeafSimContent.toJson)`, and `LeafSimContent` is `seedVarId,
probability, distributionType, percentiles, quantiles, minLoss, maxLoss, terms`
— no node id, no name, no parent. The value is `LeafSimResult(outcomes,
provenance)`, also carrying no identity. So a mitigated leaf is not marked as a
mitigation anywhere and produces no tree version; it is an ordinary leaf whose
parameters happen to have come from a transform, and a user who typed those same
numbers by hand would hit the same entry. `seedVarId` rides through the transform
unchanged, so the mitigated leaf draws the same stochastic stream as the raw one
and the two curves are a controlled comparison rather than two experiments.

A leaf is stored because a param-mitigated leaf **must be simulated** — a leaf
with one set of distribution parameters and a leaf with another are different
stochastic processes, with no derive-from-raw shortcut — and simulation is the
expensive operation whose result is a pure function of content. That argument is
complete without reference to mitigations.

A portfolio result is not stored for three separate reasons. Combining children
is a fold while simulating is the expensive step, so a portfolio cache avoids
nothing (portfolios still get content hashes — `sha256` of the sorted child
hashes — but those serve change detection). A cached **raw** aggregate cannot
produce the mitigated one, by A5. And a cached **mitigated** aggregate would need
the selection in its key, which is exactly what content addressing avoids and
what A11 forbids.

**Worked example.** Tree `P → {Q → {a, b}, c}`. One `LeafStage` mitigation
("Patch cadence") pulls leaf `a` from 9 to 5; one `ResultStage` mitigation
("Cyber insurance") caps `Q` at 15. Per-trial dollars in millions, `b = 8`,
`c = 1`:

| Read | Selection | Effective tree | Cache | Q | P |
|---|---|---|---|---|---|
| 1 | `Inherent` | input | `h_a`, `h_b`, `h_c` all miss → simulate → store | 9+8 = 17 | 18 |
| 2 | Patch cadence | `a → a'` | `h_a'` miss → store; `h_b`, `h_c` hit | 5+8 = 13 | 14 |
| 3 | Insurance | input | all three hit | cap15(17) = 15 | 16 |
| 4 | both | `a → a'` | all three hit | cap15(13) = 13 | 14 |

Four analyses, four cache entries, four leaf simulations; read 4 costs none. The
shortcut A5 forbids is visible at read 4: applying the cap to a cached
`Q_raw = 17` gives 15, while the correct value is 13, and the gap is exactly
Patch cadence's effect on `a`, which capping the raw total never sees.

#### 7.6.1 Pre-elevation review register

Input to the M4 elevation. Every entry was verified against source, not against
plan text. Severity and straightforward-fix flags are as assessed at review
time; the Status column is current.

| # | Finding | Sev | Status |
|---|---|---|---|
| F1 | Tree PUT silently deletes every mitigation: `RiskTreeServiceLive.create`/`update` called `RiskTree.fromNodes` without `mitigations`, and `writeTree`'s whole-subtree `set_tree` (DD-7) deletes every `mitigations/{id}` blob the call omits. Latent while no write path creates mitigations; live data loss at M4. `MitigationPersistenceItSpec` exercises the repository, not the service, so it could not catch this | high | **Fixed.** Defaults removed from the `RiskTree` constructor, `fromNodes` and `fromNodesUnsafe` so the compiler forces intent; `create` passes `Nil`, `update` carries `oldTree.mitigations`; service-level regression tests added to `RiskTreeServiceLiveSpec` |
| F2 | **Ruled (Decision 1 = B).** The refetch mechanism §7.4 cited does not exist on the client (the SPA has no SSE consumer at all; SSE lives only under `modules/server/`), and `InvalidationHandler.computeAffectedNodes` diffs node membership and node content hashes only, so it is blind to mitigation-only edits | med | Open → Decision 1 |
| F3 | D5 is open and its designated ADR does not cover the API: §7.4 points at ADR-034, which explicitly scopes transform definitions out. No ADR covers the client-facing mitigation API. D4 likewise open, with `applicationRecords` built but consumed nowhere | high | Ruled → Decision 2 |
| F4 | `overrideBaseStamp` is server-computed by ruling (§8.15) but client-supplied by the `Mitigation` codec, which decodes it straight off the wire | high | **Specified, not implemented.** Discharged structurally at §7.6.6: the request-side spec type carries no base-stamp field at all, so a client cannot send one; the stamp is computed from the anchored leaf |
| F5 | The selection payload has no size bound: neither `ScopeRestriction.NodesOnly.ids` nor `MitigationSelection.Selected.entries` is bounded. Derived bounds 10 000 (tree node ceiling) and 1 000 (`MaxMitigations`), both from ADR-017 §6 domain cardinalities; the 8 MiB `RequestStreaming.Disabled(cfg.maxRequestBytes)` cap covers the aggregate | med-high | **Specified, not implemented** (§7.6.5). The elevation adds a third bound F5 did not name: the requested node list, bounded by the same tree node ceiling |
| F6 | Where the selection rides is unresolved, and the plan's stated answer does not fit the endpoints | med | Ruled → Decision 3 |
| F7 | The response type cannot carry what ADR-034 and OD-3 require: `Map[NodeId, LECNodeCurve]` has no room for two valuations or for per-valuation provenance | med | Ruled → Decision 4; shape at §7.6.3 |
| F8 | The scope resolver is not reachable from the LEC path: `MitigationScopeResolverRegistry` is wired into `QueryServiceLive` only, and `RiskTreeServiceLive` discarded the `CommitHash` | med | **Fixed.** `RiskTreeService.getById` widened to `(RiskTree, CommitHash)`; the three controller callers discard the hash at the wire boundary. Supersedes the confinement formerly recorded in §7.5.3 (i) and §7.5.4, both now corrected |
| F9 | §8.7 Finding 3's required validating decoder has an unclear trigger: `MitigationApplicationRecord`'s derived codec re-validates nothing, so a tampered record decodes cleanly | med | **Closed, no action.** The record is outbound only; both inbound paths (targeting expression, persisted mitigation) already re-validate. See Decision 5 |
| F10 | Mitigation selection collides with Compare slot identity: OD-3 wants selection as a slot dimension, but `SlotCoordinate.samePairAs` does not carry it | med | Ruled → Decision 6 |
| F11 | **Ruled (Decision 7).** `LECChartState` caps user-selected nodes at 13 and `ColorAssigner` assigns one colour per node, which a second curve per node breaks. The cap counts nodes; a variant is drawn with point-marker shape, colour staying node identity | med | Ruled → Decision 7 |
| F12 | New nodes and new mitigations cannot reference each other in one PUT: `overrideAnchor` is a `NodeId` and `risk_id` literals need ids, but ADR-017 create buckets carry no ids — the server mints them | low-med | Ruled → Decision 8; the name-uniqueness debt it carried is discharged (`requireDistinctNodeNames`) |
| F13 | §7.4's create-DTO wording contradicted ADR-017 Decision 1 by giving `RiskTreeDefinitionRequest` an identity-preserving bucket | low | **Fixed** (§7.4 first bullet) |
| F14 | §7.4.1's user-documentation deliverable has zero coverage and is already due: `docs/user/VQL-QUERY-EXAMPLES.md`, `TERMINOLOGY.md` and `API-TUTORIAL.md` contain no occurrence of "mitigat"; the deliverable is scoped to "M3/M4" and M3 landed at 0.10.31/0.10.32 | low (blocking under G8) | Open — scheduled in slice 6 (§7.6.4); writable now that the wire shapes are ruled |
| F15 | Stale docs and comments to sweep | low | **Fixed.** ADR-034 status → "Accepted (implemented)"; `CachedResultResolver`/`CachedResultResolverLive` `None` → `Inherent` (3 sites); ADR-017 `obsoleteNodeIds` row corrected to the whole-subtree `set_tree` semantics. Not stale after all: `RiskTreeRequests.resolveUpdate`, which exists as a delegating entry point |
| F16 | The plan's ADR-alignment table predates ADR-034, ADR-035 and ADR-036 | low | **Fixed** (rows added) |
| F17 | A tree PUT can leave `overrideAnchor` pointing at a node the same request deleted. `validateMitigations` checks duplicate ids, duplicate names and the 1 000 cap only — it does not cross-check `MitigationSpec.LeafStage.overrideAnchor: Option[NodeId]` against the node collection, and its docstring places scope resolution outside the tree invariants. Predicate targets are not references — they are re-resolved per tree version, and a non-binding predicate is a designed per-mitigation no-op drift signal — but `overrideAnchor` is a stored reference to a ULID that, once its node is deleted, can never bind again. F12 is the forward case (a new node and a new mitigation referencing each other before ids are minted); this is the backward case | med | Ruled → Decision 9 |

Two further items came from the complex-tier review of the F1/F8 change:

| # | Finding | Sev | Status |
|---|---|---|---|
| R1 | Lost update in the tree-update path: `RiskTreeServiceLive.update` precomputes a whole replacement tree from its own earlier read, then passes `_ => riskTree` to `repo.update`, which discards the fresh read it just performed. No compare-and-swap anywhere in the write path, so two overlapping PUTs silently drop one client's edit. Pre-existing; predates mitigations and applies equally to node content, tree name and `seedVarHighWater` | should-fix | Ruled: fix properly via optimistic concurrency keyed on `CommitHash`. Scoped out to `PLAN-TREE-WRITE-CONCURRENCY.md` (NEEDS REVIEW, not implementation-grade), whose write-shape ruling is a version token first, then server-side diff-and-merge — options in `PLAN-TREE-WRITE-CONCURRENCY-APPENDIX-WRITE-SHAPE.md` |
| R2 | `CommitHash` described as "the storage-relation revision (ADR-032 §3)" in two trait scaladocs. ADR-032 §3 is about per-node byte hashing for merge-conflict prediction, not commit identity | note | **Fixed** in both `RiskTreeService` and `RiskTreeRepository` |

#### 7.6.2 Decision register — all twelve ruled

The M4 elevation's decision set, in decision-number order. Decisions 11 and 12
were added during the ruling session; both were absent from the review that
produced F1–F17. Decision 5 turned out not to be a decision and is recorded as
closed. **Decision 9 was re-ruled on 2026-09-14** and now reads the opposite way
from its first ruling; the entry states both the new ruling and why it changed.

1. **Does M4 make mitigation edits visible to the change-notification channel?**
   (F2) **RULED B.** Yes. `computeAffectedNodes` gains a mitigation-collection
   diff and publishes the affected nodes, which gives `InvalidationHandler` a
   scope-resolver dependency on the write path. The browser consumer is M5
   scope, so M4's publish reaches zero subscribers by design.

2. **Where does the client-facing mitigation API contract live?** (F3)
   **RULED B — split by surface.** The mitigation buckets amend ADR-017, whose
   "Four Buckets" decision would otherwise describe a tree PUT that no longer
   exists. The read side becomes a new **ADR-037**, which covers the analysis
   read contract as a whole — pin, branch header, selection, response — not only
   its mitigation-shaped part, and which follows the ADR-00X meta template.

3. **How does the selection cross the wire?** (F6) **RULED A — a JSON body on
   both analysis endpoints; `prob-of-exceedance` becomes a POST.** A selection
   is a set (A7), so the payload is a map of up to 1 000 mitigation entries,
   each of which may carry up to 10 000 node ids under
   `ScopeRestriction.NodesOnly` (F5's bounds); a URL cannot be designed against
   the typical case when the bound is that large. At those bounds a selection
   carries up to ten million node ids, which exceeds the request-line limit of
   every common server and proxy, so a query string is not a viable carrier for
   it at any size near the bound. One further argument holds independently:
   `lec-multi` is already a POST with a JSON body, so a body on both endpoints
   is one encoding rather than two.

   Three consequences, each verified against source:
   - **`MitigationSelection.Inherent` is the wire spelling of "no mitigations",
   and it is identity.** `MitigationApplication.scoped` returns `Nil` for it,
   so `effectiveTree` applies no transform and `resultTransformFor` yields
   `None`. The tree is rebuilt through `RiskTree.fromNodes` with unchanged
   node values, so `LeafSimContent` hashes identically and no cache entry
   fragments.
   - **Existing figures and assertions do not move.**
   `CachedResultResolver.ensureCached` and `ensureCachedAll` already declare
   `selection: MitigationSelection = MitigationSelection.Inherent` and
   `resolvedScopes` defaulted in the trait, and `RiskTreeServiceLive` calls
   them without either argument. The same defaulting carries to
   `RiskTreeService.probOfExceedance` and `getLECCurvesMulti`, so every
   service-level call site compiles and asserts unchanged.
   - **Only one existing test re-encodes a request.**
   `SeedReproducibilityItSpec` posts a bare `List[NodeId]` body to
   `lec-multi` and moves to the request object. `prob-of-exceedance` has no
   HTTP-level test and no browser consumer — `getWorkspaceProbOfExceedanceEndpoint`
   is referenced only by its own definition and its controller — so the method
   change costs no test at all. `LECChartState` is the one production caller
   to change.

   The request body is **required**, not optional: a Tapir `jsonBody` cannot be
   absent, and `Inherent` is the explicit spelling of the empty selection, so an
   optional body would add a second way to say the same thing.

   Doc sweep this ruling makes due when the change lands (not before — the
   endpoint is a GET today): the method column in
   `docs/dev/plans/IMPLEMENTATION-PLAN.md` and the endpoint row in
   `docs/dev/plans/AUTHORIZATION-PLAN.md`.

4. **What shape does the analysis response take?** (F7) **RULED 2026-09-13 —
   the shape is written out with examples at §7.6.3.** The response is keyed by
   node id; each node carries a list of series, and each series is a curve plus
   `withMitigations`, the mitigations that shaped it. The inherent series is the
   entry whose list is empty, so it is a case of the general structure rather
   than a separately named field — which is what finally retires the
   mitigated-twin framing that kept reappearing as a field name. A node the
   selection does not reach carries one series only.

   `withMitigations` reads as every mitigation that shaped this curve: those
   applied at the node plus every one applied below it. The union is required,
   not cosmetic: no mitigation scopes a portfolio directly when only its leaves
   are targeted, so an "applied here" reading would leave both of the root's
   entries tagged with an empty list and nothing would distinguish them.

   Three things the earlier framing expected in this response are **not** in it.
   The per-request facts have no home here because there are none: a ticked
   mitigation that contributed nothing is visible as an identifier appearing in
   no node's `withMitigations`, and why it contributed nothing reaches the
   interface earlier through the tree read, which carries each mitigation's
   resolved scope and `ScopeOutcome` under Decision 12. The full
   `MitigationApplicationRecord` list is redundant for the same reason — its
   `spec` and `resolvedScope` are both already in the client's hands from that
   tree read. And no staleness or revision-stamp field is carried, because
   immutable versioning excludes the condition one would report (§7.6.3).

5. **Does M4's record decode trigger §8.7 Finding 3's required validator?** (F9)
   **CLOSED — no action in M4, and none required.** Not a decision: the question
   dissolves once the paths are named.

   The obligation fires on an **inbound** decode path — a record arriving from
   somewhere the server does not control. Both such paths are already validated,
   and were before M4 began:
   - **The targeting expression**, on every path. `MitigationTarget`'s codec is
   `JsonCodec[TargetingPredicate].transform(...)` and `TargetingPredicate`'s own
   decode re-runs `create`.
   - **The persisted mitigation**, on every tree PUT. `Mitigation`'s codec is a
   `mapOrFail` calling `Mitigation.create`, so the 1-to-10 pipeline step limit
   and the Override stamp/anchor pairing both run on stored content.

   `MitigationApplicationRecord` only travels outward: the server builds it and
   sends it to the browser, which displays it. Re-checking a value the server
   itself produced, inside the client it was sent to, defends against nobody —
   there is no third party between them. The record's generated decoder stays as
   it is. The obligation stays correctly conditional on a client sending a record
   back, or on one being stored and read again; neither path exists, and if one
   is ever built the check lands with it.

6. **Does mitigation selection join `SlotCoordinate`?** (F10) **RULED A**, and
   under Decision 11 it is the only workable option rather than a preference:
   two slots differing only in selection would hold identical coordinates,
   `engagedSlots` would make the second inert, and no coordinate change could
   ever differentiate it.

7. **What does the curve cap count, and how is a variant drawn?** (F11)
   **RULED: the cap counts nodes; a variant is drawn with point-marker shape.**
   Colour stays node identity. Stroke dash was rejected — it already carries the
   tail-quantile rule annotations on this chart. A second colour palette was
   rejected on supply: `PaletteData` defines eight named families and
   `CompareState` spends all eight, one per slot, under a `require`.

8. **How does a client attach an override to a node created in the same
   request?** (F12) **RULED: a name-based anchor**, resolved server-side against
   the `SafeName`-keyed resolved-node map the request resolver already builds for
   parent resolution. The verification debt is discharged: `RiskTree.fromNodes`
   runs `requireDistinctNodeNames`, so a name identifies at most one node in a
   tree and the anchor is unambiguous.

9. **What happens to an `overrideAnchor` whose node the same PUT deletes?**
   (F17) **RULED — accept the request; staleness is the mechanism.** No
   cross-check is added to `validateMitigations`. A mitigation whose anchor names
   a node that no longer exists is a legal tree that reports itself stale:
   `MitigationStaleness.isStale` already ends with the arm that treats a missing
   or no-longer-leaf anchor as stale, and slice 3 delivers the result in
   `staleMitigationIds` on every structure read.

   This reverses an earlier ruling of "reject the request", and the reason is the
   experience each option can actually deliver. Rejection turns a deleted anchor
   into a failed PUT, so the information exists only as a transient string bound
   to one submission attempt: a reload loses it, and the node deletion the user
   asked for is not saved either. The failure also cannot be routed to the
   mitigation it concerns — `ValidationError` carries a field name, a code and a
   message, and no node or mitigation id as data — so no interface can offer
   "keep the node or retarget the override". Acceptance puts the same information
   in persisted, addressable, per-mitigation state that survives a reload and is
   acted on when the user chooses, which is the affordance that was wanted, and
   the staleness machinery for it is already designed and built.

   Two consequences follow. The `case _ => true` arm in `MitigationStaleness`
   stays reachable and is the specified behaviour, not a leftover. And a stored
   tree carrying a dangling anchor stays decodable, so `fromNodes` does not
   tighten on this point.

11. **Does one analysis request carry one selection or several named variants?**
   **RULED A — one selection per request.** Comparing several selections is
   therefore several requests, one per Compare slot, which is what makes
   Decision 6 load-bearing.

12. **Where does the client get mitigation definitions and resolved scopes?**
   **RULED A — the tree read carries them.** The resolved scope is a function
   of the tree version and nothing else, so shipping it with the tree makes it
   definitionally consistent with the nodes it describes; every other placement
   opens a window where scopes and nodes come from different revisions. It also
   puts the warnings about mitigations that no longer match any node on the
   editing screen, which is where a user can act on them.

   Scope of the change, corrected against source: **mitigation definitions
   already cross the wire.** `RiskTree` carries `mitigations: Seq[Mitigation]`
   and its codec emits the field (omitted when empty, for pre-mitigation
   payloads), so `getWorkspaceTreeStructureEndpoint` — the read
   `TreeViewState` uses — already returns them. What is missing is the
   **resolved scopes and each mitigation's `ScopeOutcome`**.

   Those must not be added to `RiskTree`. `RiskTree` is the persisted content
   type: it is what the PUT carries and what Irmin stores, so a server-computed,
   per-revision resolution result placed inside it would persist derived
   mitigation state, against A11, and would claim tree-version independence it
   does not have, against A8. The structure endpoint's response therefore
   returns a type holding the tree and the resolved scopes side by
   side; its exact shape is written in the §7.6 elevation.

**Carried question, now answered.** What `ScopeResolutionContext` means for a
`Revision.At` pinned read was carried without a decision number. It is resolved
by the capacity-2 memo (§8.4-5): a pinned read occupies the second slot and the
head entry survives, so alternating between head and a pin no longer recomputes
both.

#### 7.6.3 Analysis request and response shapes

The ruled request and response shapes of the two analysis endpoints, with worked
examples and the reason behind each rule. Every shape question is settled. What
is still missing is the elevation, not the design: this section carries no exact
Tapir signature, no file inventory and no verification plan, so on its own it
confers no plan coverage.

**Naming principle.** The JSON field names reuse the existing domain wording
wherever the concept is the same, so that a reader moving between the wire
format and `MitigationSelection` meets one vocabulary rather than two.
`fullScope` and `nodesOnly` are the names of the two `ScopeRestriction` cases and
are used verbatim on the wire.

**Shape.** The body carries the requested nodes and one selection (Decision 11).
The selection is two lists, one per gesture the interface offers: a mitigation
ticked on its own control applies across its whole resolved scope; a mitigation
ticked on one of its rows beneath a node applies at that node only. The
interface also offers an "All mitigations" control, which needs no third list —
it fills `fullScope` with every mitigation the tree holds, as ruled below.

```json
{
  "nodeIds": ["01HQ8ZK3", "01HQ8ZK7"],
  "selection": {
    "fullScope": ["m-backups"],
    "nodesOnly": [ { "node": "01HQ8ZK3", "mitigation": "m-insurance" } ]
  }
}
```

**Conversion at the controller.** The body decodes into the existing
`MitigationSelection`, so the service, the resolver, the cache path and the
query-language path keep the signatures they have.

| Body | `MitigationSelection` |
|---|---|
| `fullScope: ["m-backups"]` and `nodesOnly: [{01HQ8ZK3, m-insurance}]` | `Selected(Map(m-backups -> FullScope, m-insurance -> NodesOnly(Set(01HQ8ZK3))))` |
| both lists empty | `Inherent` |

**A mitigation named in both lists — ruled 2026-09-13.** The two lists are both
statements that a mitigation is on, so they combine by union and cannot
conflict. A mitigation ticked on its own control is on across its whole resolved
scope; naming it again beneath one of its nodes states something already true.
The conversion therefore yields `FullScope` for that mitigation and the decoder
accepts the input rather than rejecting it. This agrees with what
`MitigationApplication.scoped` already computes: `FullScope` yields the resolved
scope, `NodesOnly` yields the resolved scope intersected with the named nodes,
and the union of those two is the resolved scope.

**A state this shape cannot express, deliberately.** `NodesOnly(Set.empty)` —
a mitigation switched on and applied to no node — has no spelling here, because
a mitigation is named only by appearing in `fullScope` or by having at least one
entry in `nodesOnly`. The current `ScopeRestriction` codec accepts an empty
`ids` array and produces that state, which is indistinguishable in effect from
the mitigation being off.

**A named mitigation that is not in the tree — ruled 2026-09-13: reject.** A
selection names mitigations by `MitigationId`, never by name. An identifier the
tree does not hold at the served revision fails the request; there is no
time-travel exemption, so scrubbing the pin back past a mitigation's creation
while that mitigation is ticked breaks the chart, and that is intended. The two
cases are deliberately different: naming a mitigation that applies to nothing is
a valid request whose answer is "no effect", which the response shows by that
mitigation appearing in no node's series; naming a mitigation that does not
exist is a broken request.

The check cannot live in the JSON decoder. The decoder validates that a
`MitigationId` is well formed — the Iron refinement — but has no tree to look it
up in; the tree is loaded afterwards, at a revision chosen by the branch header
and the `at` pin. Membership is a lookup, not a validation, so the check belongs
immediately after the tree is loaded, where the scope resolver already runs.
`RiskTree.validateMitigations` draws the same line for the write path: unique
ids, unique names and the collection bound are tree invariants, while resolving
a predicate against a version is a server-side concern.

**Renaming a mitigation is not a failure and not a behaviour change**, because
identification is by identifier. The curve is unaffected and only the interface's
labels go stale. Renaming a *node* is the case that changes behaviour: targeting
predicates reference node names, so a node rename can change which nodes a
mitigation binds to while leaving the domain content hash identical.

**The "All mitigations" control enumerates — ruled 2026-09-13, no `residual`
spelling on the wire.** The interface offers a single control that turns every
mitigation on. It sends them in `fullScope` using the structure above; no extra
field and no extra case are needed. Enumeration is exact rather than a snapshot
that might have gone stale, because a tree version is immutable: the client
enumerated the mitigations of a version it read, and a request is answered at
the version it names, so the list it sends is the list that version holds. A
mitigation created after that read is absent from the list and is not applied,
which is exactly what the request asked for; a mitigation the tree does not hold
at the served revision is rejected by the rule above. `Residual` stays a
`MitigationSelection` case, because the query language's `residual` literal
produces it (`RiskTreeKnowledgeBase.scala:476`); the analysis endpoint's body
simply never constructs it.

**No staleness mechanism is needed, by construction.** Irmin stores immutable
versions and every save appends a new one, so a version a client has read stays
readable and unchanged forever. There is no mutable authoritative tree for a
client's view to diverge from, and therefore no condition where a selection
panel silently describes a tree that no longer exists. Which version an answer
describes is a choice the request makes: a request naming a revision is served
at that revision, and a request naming none is served at branch head, which is
equally what was asked for. Simultaneous editors change nothing here — each save
appends a commit and neither invalidates the other's read; simultaneous editing
resolves on the write path, as a merge. Proposals to stamp responses with the
served revision, to have the server compare a client-supplied basis revision
against branch head, or to return the current node and mitigation inventory on
every chart response were all raised and withdrawn on this ground.

**Response shape — ruled 2026-09-13.** The response is keyed by node id. Each
node carries a list of series, and each series is a curve together with the
mitigations that shaped it. The inherent series is the entry whose mitigation
list is empty — a case of the general structure rather than a separately named
field, which is what retires the "mitigated twin" framing for good.

```json
{
  "01HQ8ZK3": [
    { "curve": { "id": "01HQ8ZK3", "name": "Ransomware",
                 "curve": [ {"loss": 1000000, "exceedanceProbability": 0.41} ],
                 "quantiles": {"p95": 24000000.0},
                 "averageAnnualLoss": 3100000.0, "probabilityOfNoLoss": 0.55 },
      "withMitigations": [] },
    { "curve": { "id": "01HQ8ZK3", "name": "Ransomware",
                 "curve": [ {"loss": 1000000, "exceedanceProbability": 0.22} ],
                 "quantiles": {"p95": 11000000.0},
                 "averageAnnualLoss": 1400000.0, "probabilityOfNoLoss": 0.72 },
      "withMitigations": ["m-backups"] }
  ],
  "01HQ8ROOT": [
    { "curve": { "id": "01HQ8ROOT", "name": "Total", "curve": [ ... ] },
      "withMitigations": [] },
    { "curve": { "id": "01HQ8ROOT", "name": "Total", "curve": [ ... ] },
      "withMitigations": ["m-backups"] }
  ],
  "01HQ8ZK9": [
    { "curve": { "id": "01HQ8ZK9", "name": "Phishing", "curve": [ ... ] },
      "withMitigations": [] }
  ]
}
```

Reading that example: Ransomware is inside the applied scope of `m-backups`, so
it carries a second series. The root carries one too, because a portfolio's
mitigated value folds its children's mitigated values (A5). Phishing is reached
by nothing and carries one series only.

**The three rules the shape encodes.**

1. The inherent series is always present and is never replaced. A selection adds
   curves; it does not substitute them.
2. A node the selection reaches carries exactly one additional series, holding
   the combined effect of every mitigation applied at it — never one series per
   mitigation, because the mitigations scoping a node compose into a single
   valuation (A4, A7).
3. A node the selection does not reach — neither inside an applied mitigation's
   effective scope nor an ancestor of such a node — carries no additional
   series. It is omitted rather than returned as a duplicate of the inherent one.

**`withMitigations` is every mitigation that shaped this curve**: those applied
at the node itself, plus every mitigation applied anywhere below it. The union
is what makes a portfolio's two series distinguishable. No mitigation scopes the
root directly in the example above, so an "applied here" reading would give both
of the root's entries an empty list and nothing would tell them apart. A
result-stage mitigation scoping a portfolio directly is included as well, since
result-stage mitigations may attach to any node.

The list's order is significant at a leaf and not at a portfolio.
`MitigationApplication.scoped` sorts by `(precedence.key, id.value)`, and at a
leaf that is the order the transforms compose in. At a portfolio the list
gathers mitigations applied at different nodes, which never compose with each
other, so the order there is determinism only and carries no meaning.

**Nothing else belongs in this response.** There is no enclosing object and no
per-request content beside the map. A mitigation that was ticked and contributed
nothing is observable directly: the client knows what it sent, and an identifier
appearing in no node's `withMitigations` contributed to no curve here. Why it
contributed nothing — a predicate that no longer binds, or one that resolves to
nothing once intersected with the stage domain — reaches the interface earlier
and more usefully through the tree read, which carries each mitigation's
resolved scope and `ScopeOutcome` (Decision 12) so the panel can mark a
mitigation as matching nothing before anything is ticked. The full application
records add nothing either: their `spec` comes from `RiskTree.mitigations` and
their `resolvedScope` from that same tree read, both already in the client's
hands.

#### 7.6.4 Delivery slices

M4 lands in six slices. The order is forced by what each slice depends on: the
wire types come first because everything else consumes them, and the interface
comes last because it consumes all of them. Slicing is how the work is
sequenced, not permission to stop partway — the plan is done when every slice
has landed green (G8).

| Slice | What it delivers | Elevated |
|---|---|---|
| 1 | The analysis read path: request and response types, both endpoint signatures, the controller and service threading, the scope-resolver call, and the smallest browser change that keeps the app compiling | §7.6.5 |
| 2 | The tree write path: the two mitigation buckets on the tree PUT, the name-based override anchor, and the server-computed override stamp. No anchor cross-check — Decision 9 rules that a deleted anchor is a staleness signal, not a validation failure | §7.6.6 |
| 3 | The tree structure read: resolved scopes, resolution failures and stale-override ids beside the tree | §7.6.7 |
| 4 | Change notification: the mitigation-collection diff, and the scope memo's capacity-2 amendment | §7.6.8 |
| 5 | The two decision records: the ADR-017 amendment and the new ADR-037 | §7.6.9 |
| 6 | The interface and the user documentation | not elevated — see §7.6.12 |

Slices 2 to 5 are specified below and carry no open decisions. Slice 1 consumes
the `ValuationResult` ruling of §8.16, whose five gating decisions are open.
Slice 6 is not elevated in this pass: it is the one part of M4 whose shape the
twelve rulings do not determine. §7.6.12 lists all seven open decisions.

#### 7.6.5 Slice 1 — the analysis read path

**What this slice changes, in plain terms.** Today both analysis endpoints
answer one question each: the exceedance probability at a node, and the loss
exceedance curves of several nodes. Neither can be asked "and what would these
look like with these mitigations applied". This slice adds that: each request
carries one mitigation selection, and each answer carries the mitigation-free
reading plus, for every node the selection reaches, one more reading under it.

**Reasoning source, and what is not settled here.** This slice reads the resolver
and turns what it returns into curves. What the resolver returns is ruled in
§8.16: the mitigated fold yields a `ValuationResult` at every node it visits. The
derivation is in
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md).
The signatures written below predate that ruling and read the resolver's result
as a bare `LossDistribution`. They still compile against it, because
`ValuationResult` is a `LossDistribution`, but the exact resolver return type is
an open decision in §7.6.12 and this slice is not implemented until it is ruled.
Any ambiguity below about which valuation a value represents is resolved by §8.16
and that document, never by re-deriving it here.

**New file — `modules/common/src/main/scala/com/risquanter/register/http/requests/AnalysisRequests.scala`.**

```scala
package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec, JsonEncoder, JsonDecoder}
import zio.prelude.Validation
import sttp.tapir.Schema

import com.risquanter.register.domain.data.{MitigationSelection, ScopeRestriction}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One tick on a mitigation's row beneath a node: that mitigation applies at
  * that node and nowhere else.
  */
final case class NodeMitigationEntry(node: NodeId, mitigation: MitigationId)

object NodeMitigationEntry:
  given codec: JsonCodec[NodeMitigationEntry] = DeriveJsonCodec.gen[NodeMitigationEntry]
  given schema: Schema[NodeMitigationEntry]   = Schema.derived[NodeMitigationEntry]

/** A mitigation selection in the form the interface produces it. `fullScope`
  * holds the mitigations ticked on their own control, each applying across its
  * whole resolved scope; `nodesOnly` holds the per-node ticks. Both lists empty
  * is the mitigation-free reading. A mitigation named in both lists applies
  * across its whole resolved scope, because both lists are statements that it
  * is on and the wider one subsumes the narrower.
  */
final case class MitigationSelectionRequest private (
  fullScope: List[MitigationId],
  nodesOnly: List[NodeMitigationEntry]
):

  /** The domain selection this body denotes. */
  def toSelection: MitigationSelection = (fullScope, nodesOnly) match
    case (Nil, Nil) => MitigationSelection.Inherent
    case _ =>
      val full = fullScope.toSet
      val restricted: Map[MitigationId, ScopeRestriction] =
        nodesOnly
          .filterNot(e => full.contains(e.mitigation))
          .groupMap(_.mitigation)(_.node)
          .view.mapValues(ns => ScopeRestriction.NodesOnly(ns.toSet): ScopeRestriction)
          .toMap
      MitigationSelection.Selected(
        restricted ++ full.map(_ -> ScopeRestriction.FullScope).toMap
      )

  /** Every mitigation this body names, in either list. */
  def namedMitigations: Set[MitigationId] =
    fullScope.toSet ++ nodesOnly.map(_.mitigation).toSet

object MitigationSelectionRequest:

  /** The empty selection: no mitigation is on. */
  val inherent: MitigationSelectionRequest = new MitigationSelectionRequest(Nil, Nil)

  /** How many distinct mitigations one selection may name. Mirrors the bound on
    * `RiskTree.mitigations`: a selection cannot usefully name more mitigations
    * than a tree can hold. */
  private val MaxSelectedMitigations = 1000

  /** How many nodes one mitigation may be ticked at. Mirrors the tree's node
    * bound: a mitigation cannot be ticked at more nodes than a tree can hold. */
  private val MaxNodesPerMitigation = 10000

  /** Both bounds are checked together so an oversized request reports every
    * problem at once. Whether the named mitigations exist is deliberately not
    * checked here: that is a lookup against a loaded tree, and no tree is in
    * hand at decode time. */
  def create(
    fullScope: List[MitigationId],
    nodesOnly: List[NodeMitigationEntry],
    fieldPrefix: String = "selection"
  ): Validation[ValidationError, MitigationSelectionRequest] =
    val named = fullScope.toSet ++ nodesOnly.map(_.mitigation).toSet
    val oversizedTick = nodesOnly.groupBy(_.mitigation).collectFirst {
      case (id, ticks) if ticks.sizeIs > MaxNodesPerMitigation => (id, ticks.size)
    }

    val countV: Validation[ValidationError, Unit] =
      if named.sizeIs <= MaxSelectedMitigations then Validation.succeed(())
      else Validation.fail(ValidationError(
        field   = fieldPrefix,
        code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"selection names too many mitigations: ${named.size} exceeds the limit of $MaxSelectedMitigations"
      ))

    val perMitigationV: Validation[ValidationError, Unit] = oversizedTick match
      case None => Validation.succeed(())
      case Some((id, count)) => Validation.fail(ValidationError(
        field   = s"$fieldPrefix.nodesOnly",
        code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"mitigation ${id.value} is ticked at too many nodes: $count exceeds the limit of $MaxNodesPerMitigation"
      ))

    Validation.validateWith(countV, perMitigationV)((_, _) =>
      new MitigationSelectionRequest(fullScope, nodesOnly))

  private case class Raw(
    fullScope: Option[List[MitigationId]],
    nodesOnly: Option[List[NodeMitigationEntry]]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  /** Decode runs `create`, so no value of this type exists that breaks a bound.
    * Either list may be omitted and means the empty list. */
  given codec: JsonCodec[MitigationSelectionRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(s => Raw(Some(s.fullScope), Some(s.nodesOnly))),
    JsonDecoder[Raw].mapOrFail(raw =>
      create(raw.fullScope.getOrElse(Nil), raw.nodesOnly.getOrElse(Nil))
        .toEither.left.map(_.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")))
  )

  given schema: Schema[MitigationSelectionRequest] = Schema.any[MitigationSelectionRequest]

/** Body of the multi-node curve read: the nodes to draw, and the one selection
  * the answer is computed under.
  */
final case class LECCurvesMultiRequest private (
  nodeIds: List[NodeId],
  selection: MitigationSelectionRequest
)

object LECCurvesMultiRequest:

  /** How many nodes one request may ask for. Mirrors the tree's node bound: a
    * request cannot ask for more nodes than a tree can hold. */
  private val MaxRequestedNodes = 10000

  def create(
    nodeIds: List[NodeId],
    selection: MitigationSelectionRequest
  ): Validation[ValidationError, LECCurvesMultiRequest] =
    if nodeIds.sizeIs <= MaxRequestedNodes then
      Validation.succeed(new LECCurvesMultiRequest(nodeIds, selection))
    else
      Validation.fail(ValidationError(
        field   = "nodeIds",
        code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"too many nodes requested: ${nodeIds.size} exceeds the limit of $MaxRequestedNodes"
      ))

  private case class Raw(nodeIds: List[NodeId], selection: Option[MitigationSelectionRequest])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[LECCurvesMultiRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(r => Raw(r.nodeIds, Some(r.selection))),
    JsonDecoder[Raw].mapOrFail(raw =>
      create(raw.nodeIds, raw.selection.getOrElse(MitigationSelectionRequest.inherent))
        .toEither.left.map(_.toChunk.map(e => s"[${e.field}] ${e.message}").mkString("; ")))
  )

  given schema: Schema[LECCurvesMultiRequest] = Schema.any[LECCurvesMultiRequest]

/** Body of the exceedance-probability read. The node and the threshold stay
  * where they are — in the path and the query string — so the body carries the
  * selection alone.
  */
final case class ProbOfExceedanceRequest(selection: MitigationSelectionRequest)

object ProbOfExceedanceRequest:
  private case class Raw(selection: Option[MitigationSelectionRequest])
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[ProbOfExceedanceRequest] = JsonCodec(
    JsonEncoder[Raw].contramap(r => Raw(Some(r.selection))),
    JsonDecoder[Raw].map(raw =>
      ProbOfExceedanceRequest(raw.selection.getOrElse(MitigationSelectionRequest.inherent)))
  )

  given schema: Schema[ProbOfExceedanceRequest] = Schema.any[ProbOfExceedanceRequest]
```

Three points about that file. The node-count bound on `nodeIds` is new: F5 bounded the
selection payload and said nothing about the requested node list, and an
unbounded list on an endpoint that now also carries a selection is the same
defect in a second place. The empty-`nodeIds` case is deliberately **not**
moved into the decoder: `RiskTreeServiceLive` already fails it with
`EMPTY_COLLECTION` and a test asserts that, and moving the check would change
the error a caller sees for no gain. `Schema.any` is the settled treatment for
these payloads — §7.4.1 records that the OpenAPI document renders the
mitigation types as opaque objects and that the wire-format reference lives in
`docs/user/API-TUTORIAL.md`, because a second hand-written schema beside the
custom codecs would drift silently and no external OpenAPI consumer exists.

**New file — `modules/common/src/main/scala/com/risquanter/register/http/responses/AnalysisResponses.scala`.**

```scala
package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.MitigationId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One drawn curve together with the mitigations that shaped it.
  *
  * `withMitigations` names every mitigation whose resolved scope covers this
  * node or any node below it — decided by scope, so a selected mitigation that
  * covers nothing here is absent and a covering mitigation is named whether or
  * not it visibly bent this curve. The mitigation-free curve is the entry whose
  * list is empty, which is why there is no separately named field for it.
  */
final case class LECNodeSeries(curve: LECNodeCurve, withMitigations: List[MitigationId])

object LECNodeSeries:
  given codec: JsonCodec[LECNodeSeries] = DeriveJsonCodec.gen[LECNodeSeries]
  given schema: Schema[LECNodeSeries]   = Schema.derived[LECNodeSeries]

/** One exceedance probability together with the mitigations that shaped it.
  *
  * `withMitigations` is read exactly as `LECNodeSeries.withMitigations`: the
  * mitigations whose resolved scope covers this node or any node below it,
  * neither the set the caller selected nor the set that moved the number. It is
  * empty exactly when no selected mitigation reached this node, which is the
  * only way a caller can tell an unmitigated figure from a mitigated one that
  * happens to match it — a cap set above the queried threshold leaves the
  * probability unchanged while still being named here.
  */
final case class ExceedanceSeries(probability: Double, withMitigations: List[MitigationId])

object ExceedanceSeries:
  given codec: JsonCodec[ExceedanceSeries] = DeriveJsonCodec.gen[ExceedanceSeries]
  given schema: Schema[ExceedanceSeries]   = Schema.derived[ExceedanceSeries]
```

**`modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala`** gains the
Tapir schema `MitigationId` needs to appear inside a JSON body, mirroring the
one `NodeId` already has:

```scala
  /** Schema for MitigationId for JSON body parameters (ADR-001 §2). */
  given Schema[MitigationId] = Schema.string
```

**`modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala`** — both endpoints:

```scala
  val getWorkspaceProbOfExceedanceEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceProbOfExceedance")
      .description("Get probability of exceeding a loss threshold (workspace-scoped) under one mitigation selection; optional `at` commit pin")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "prob-of-exceedance")
      .post
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[ProbOfExceedanceRequest].description("The mitigation selection this reading is computed under"))
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .out(jsonBody[ExceedanceSeries])

  val getWorkspaceLECCurvesMultiEndpoint =
    authedBaseEndpoint
      .tag("workspaces")
      .name("getWorkspaceLECCurvesMulti")
      .description("Get LEC curves for multiple nodes (workspace-scoped) under one mitigation selection; optional `at` commit pin")
      .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / "lec-multi")
      .post
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[LECCurvesMultiRequest].description("Requested node IDs and the mitigation selection"))
      .in(branchHeader)
      .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
      .in(query[Boolean]("omitAbsent").default(false)
        .description("When true, requested node IDs absent from the tree at this revision are omitted from the result instead of failing the request (point-in-time reads)."))
      .out(jsonBody[Map[NodeId, List[LECNodeSeries]]])
```

The exceedance endpoint becomes a POST. That is Decision 3 and it is not
cosmetic: a selection can name a thousand mitigations, each ticked at up to ten
thousand nodes, so at the bound it carries ten million node ids and cannot ride
in a query string at any size approaching that.

**`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`** — the two
analysis methods:

```scala
  /** Exceedance probability at a threshold for one node, under one mitigation
    * selection.
    *
    * One reading, computed under the selection the caller passed.
    * `withMitigations` names the mitigations whose resolved scope reached this
    * node or any node below it, and is empty exactly when none did — which is
    * also the whole answer for the mitigation-free selection. A caller wanting
    * the mitigation-free figure alongside a mitigated one asks twice; the two
    * probabilities share no tick domain and need no shared request.
    *
    * `branch` and `at` are taken separately rather than as a `Revision`
    * because the scope resolver memoizes per (tree, branch) and `Revision.At`
    * carries a commit with no branch. The method composes the `Revision` the
    * repository reads at.
    */
  def probOfExceedance(
    wsId: WorkspaceId,
    treeId: TreeId,
    nodeId: NodeId,
    threshold: Long,
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean,
    branch: BranchRef,
    at: Option[CommitHash],
    selection: MitigationSelection = MitigationSelection.Inherent
  ): Task[ExceedanceSeries]

  /** LEC curves for several nodes on one shared tick domain, under one
    * mitigation selection.
    *
    * Each node carries the mitigation-free curve first, and a second curve when
    * the selection reaches it. Every curve in the answer — both valuations of
    * every node — is evaluated against one tick domain, so any two of them can
    * be read against each other.
    */
  def getLECCurvesMulti(
    wsId: WorkspaceId,
    treeId: TreeId,
    nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean,
    branch: BranchRef,
    at: Option[CommitHash],
    omitAbsent: Boolean = false,
    selection: MitigationSelection = MitigationSelection.Inherent
  ): Task[Map[NodeId, List[LECNodeSeries]]]
```

**`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`** — the class gains
one dependency and five private members.

```scala
class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  resolver: CachedResultResolver,
  scopeResolver: MitigationScopeResolverRegistry,
  invalidationHandler: InvalidationHandler,
  tracing: Tracing,
  operationsCounter: Counter[Long]
) extends RiskTreeService {
```

```scala
  /** The read coordinate for a (branch, pin) pair. */
  private def revisionOf(branch: BranchRef, at: Option[CommitHash]): Revision =
    at.fold[Revision](Revision.Head(branch))(Revision.At(_))

  /** Fail when the selection names a mitigation this tree version does not
    * hold. Naming a mitigation that applies to nothing is a valid request whose
    * answer is "no effect"; naming one that does not exist is a broken request.
    * The check cannot live in the JSON decoder, which has no tree to look an id
    * up in. */
  private def requireKnownMitigations(tree: RiskTree, selection: MitigationSelection): Task[Unit] =
    selection match
      case MitigationSelection.Selected(entries) =>
        val absent = entries.keySet -- tree.mitigations.map(_.id).toSet
        if absent.isEmpty then ZIO.unit
        else ZIO.fail(ValidationFailed(absent.toList.sortBy(_.value).map(id => ValidationError(
          field   = "selection",
          code    = ValidationErrorCode.NOT_FOUND,
          message = s"Mitigation ${id.value} not found in tree ${tree.id}"
        ))))
      case _ => ZIO.unit

  /** Per-mitigation resolved scopes for this tree version. A mitigation-free
    * reading applies nothing, so nothing needs resolving and the resolver is
    * not called at all. */
  private def resolvedScopesFor(
    wsId: WorkspaceId,
    treeId: TreeId,
    branch: BranchRef,
    commit: CommitHash,
    tree: RiskTree,
    selection: MitigationSelection
  ): Task[ResolvedScopes] = selection match
    case MitigationSelection.Inherent => ZIO.succeed(ResolvedScopes(Map.empty))
    case _ =>
      for
        mitResolver <- scopeResolver.forWorkspace(wsId)
        scopes      <- mitResolver.resolve(ScopeResolutionContext(treeId, branch, commit), tree)
        _           <- ResolvedScopes.logFailures(scopes)
      yield scopes

  /** The mitigations whose resolved scope covers this node or any node below
    * it, in the order `MitigationApplication.scoped` composes them. Computed per
    * requested node, so the cost follows the requested subtrees rather than the
    * whole tree.
    *
    * Membership is decided by scope alone, which makes this list two things it
    * is easy to mistake it for, and neither of them:
    *
    *   - It is not the set the caller selected. A selected mitigation whose
    *     scope covers nothing in this subtree is absent from it.
    *   - It is not the set that changed the figure. A mitigation whose scope
    *     covers the subtree is present even when the reading is identical to
    *     the mitigation-free one — a cap set above an exceedance threshold
    *     moves trials down to the cap, and trials already at or above that
    *     threshold stay above it. Deciding "did this change the figure" would
    *     require computing the mitigation-free reading as well, which the
    *     single-reading exceedance answer deliberately does not do.
    *
    * The list is also the reached test: it is empty exactly when neither the
    * node nor any descendant is inside an applied mitigation's scope. On the
    * curve response such a node carries no second reading. */
  private def withMitigationsFor(
    tree: RiskTree,
    scoped: Map[NodeId, List[Mitigation]],
    nodeId: NodeId
  ): List[MitigationId] =
    tree.index.descendants(nodeId).toList
      .flatMap(scoped.getOrElse(_, Nil))
      .distinct
      .sortBy(m => (m.precedence.key, m.id.value))
      .map(_.id)

  /** Key for the one shared tick-domain computation. Both valuations of a node
    * must be evaluated against the same ticks, or the two curves land on
    * different x-axes and cannot be read against each other; keying by
    * (node, mitigated) puts them through a single
    * `LECGenerator.generateCurvePointsMulti` call. */
  private case class SeriesKey(nodeId: NodeId, mitigated: Boolean)

  /** Assemble one node's curve from its evaluated points and its own result. */
  private def curveOf(
    nodeId: NodeId,
    name: String,
    points: Vector[(Long, Double)],
    result: Option[LossDistribution]
  ): LECNodeCurve =
    LECNodeCurve(
      nodeId,
      name,
      points.map { case (loss, prob) => LECPoint(loss, prob) },
      result.map(LECGenerator.calculateQuantiles).getOrElse(Map.empty),
      result.map(LECGenerator.averageAnnualLoss).getOrElse(0.0),
      result.map(LECGenerator.probabilityOfNoLoss).getOrElse(1.0)
    )
```

`getLECCurvesMulti`'s body, replacing the current one from the tree lookup
onward. Everything before the lookup — the empty-`nodeIds` failure, the tracing
attributes, the `omitAbsent` log line — stays as it is:

```scala
      (tree, commit, nodesMap) = treeWithNodes
      _        <- requireKnownMitigations(tree, selection)
      presentIds = nodesMap.keySet
      scopes   <- resolvedScopesFor(wsId, treeId, branch, commit, tree, selection)
      applied   = scopes.appliedScopes
      scoped    = MitigationApplication.scoped(tree, selection, applied)

      inherent  <- resolver.ensureCachedAll(tree, presentIds, seedEntityId, includeProvenance)
      mitigated <- selection match
                     case MitigationSelection.Inherent =>
                       ZIO.succeed(Map.empty[NodeId, LossDistribution])
                     case _ =>
                       resolver.ensureCachedAll(tree, presentIds, seedEntityId, includeProvenance, selection, applied)

      // One tick domain for every curve in the answer, both valuations included.
      curves = LECGenerator.generateCurvePointsMulti(
                 inherent.map((id, r) => SeriesKey(id, false) -> r) ++
                 mitigated.map((id, r) => SeriesKey(id, true) -> r)
               )

      result = presentIds.iterator.map { id =>
                 val name    = nodesMap.get(id).map(_.name.value.toString).getOrElse(id.value)
                 val applied = withMitigationsFor(tree, scoped, id)
                 val base    = LECNodeSeries(
                   curveOf(id, name, curves.getOrElse(SeriesKey(id, false), Vector.empty), inherent.get(id)),
                   Nil
                 )
                 val extra   = Option.when(applied.nonEmpty)(
                   LECNodeSeries(
                     curveOf(id, name, curves.getOrElse(SeriesKey(id, true), Vector.empty), mitigated.get(id)),
                     applied
                   )
                 )
                 id -> (base :: extra.toList)
               }.toMap
    } yield result
```

`probOfExceedance` shares the lookup, the known-mitigation check and the scope
resolution, and diverges after them: it reads one node, generates no curve, and
resolves once rather than twice, because it answers under the caller's selection
alone.

```scala
        (tree, commit, _) <- lookupNodeInTree(wsId, treeId, nodeId, revisionOf(branch, at))
        _        <- requireKnownMitigations(tree, selection)
        scopes   <- resolvedScopesFor(wsId, treeId, branch, commit, tree, selection)
        applied   = scopes.appliedScopes
        scoped    = MitigationApplication.scoped(tree, selection, applied)
        withMits  = withMitigationsFor(tree, scoped, nodeId)

        result   <- if withMits.isEmpty
                    then resolver.ensureCached(tree, nodeId, seedEntityId, includeProvenance)
                    else resolver.ensureCached(tree, nodeId, seedEntityId, includeProvenance, selection, applied)
      } yield ExceedanceSeries(result.probOfExceedance(threshold), withMits)
```

The empty-`withMits` branch drops the selection rather than passing one that
reaches nothing. Both branches return the same figure in that case — a selection
scoping no node in this subtree leaves the effective subtree and its content
hash unchanged — so the branch is there to keep the mitigation-free read on the
path it already has, not to correct a result.

**`modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`** gains one
shared logging helper, moved out of `QueryServiceLive` so both readers of a
resolution report drift the same way:

```scala
object ResolvedScopes:
  /** Per-mitigation drift signals (ADR-002): a predicate that no longer binds
    * makes that one mitigation a no-op, never a request failure, so this only
    * logs. */
  def logFailures(resolved: ResolvedScopes): UIO[Unit] =
    ZIO.when(resolved.failures.nonEmpty)(
      ZIO.logWarning(
        s"MitigationScopeResolver: ${resolved.failures.size} mitigation(s) did not " +
        s"resolve against this tree version: " +
        resolved.failures.map((id, errs) => s"${id.value} -> ${errs.mkString("[", ", ", "]")}").mkString("; ")
      )
    ).unit
```

`QueryServiceLive.logResolutionFailures` is deleted and its one call site now
reads `ResolvedScopes.logFailures(resolved)`.

**`modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala`** — both
handlers take the new tuple and forward the branch and the pin instead of
composing a `Revision`:

```scala
  val probOfExceedance: ServerEndpoint[Any, Task] = getWorkspaceProbOfExceedanceEndpoint.serverLogic {
    case (maybeUserId, key, treeId, nodeId, threshold, includeProvenance, body, activeBranch, at) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        result <- riskTreeService.probOfExceedance(
                    ws.id, treeId, nodeId, threshold, ws.seedEntityId, includeProvenance,
                    branch, at, body.selection.toSelection)
      yield result).either
  }

  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getWorkspaceLECCurvesMultiEndpoint.serverLogic {
    case (maybeUserId, key, treeId, includeProvenance, body, activeBranch, at, omitAbsent) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        result <- riskTreeService.getLECCurvesMulti(
                    ws.id, treeId, body.nodeIds.toSet, ws.seedEntityId, includeProvenance,
                    branch, at, omitAbsent, body.selection.toSelection)
      yield result).either
  }
```

**`modules/server/src/main/scala/com/risquanter/register/Application.scala`** — `RiskTreeServiceLive.layer` now also
requires `MitigationScopeResolverRegistry`, which the application already provides at line
290; the layer's type widens and no wiring line moves.

**The browser, minimally.** Changing a shared endpoint definition breaks the
Scala.js module, so this slice carries the smallest change that keeps it
compiling and its behaviour identical: `LECChartState.loadCurves` sends the new
body with the empty selection, and takes each node's mitigation-free curve out
of the answer. The variant-aware cache belongs to slice 6.

```scala
  def loadCurves(nodeIds: List[NodeId]): Unit =
    (keySignal.now(), selectedTreeId.now()) match
      case (Some(key), Some(treeId)) =>
        curvesTrigger.emit(Some(() =>
          getWorkspaceLECCurvesMultiEndpoint(
            (userIdAccessor(), key, treeId, false,
             LECCurvesMultiRequest.create(nodeIds, MitigationSelectionRequest.inherent)
               .getOrElse(throw new IllegalStateException("selection request rejected its own empty value")),
             branchAccessor(), atAccessor(), true)
          ).toOutcomeEventStream.map(_.map(LECChartState.inherentCurves))
        ))
      case _ => ()
```

```scala
object LECChartState:
  /** Each node's mitigation-free curve — the series whose mitigation list is
    * empty. Every node always has one, so a node missing from the result is a
    * node absent at this revision, which is what `deriveDropped` reports. */
  def inherentCurves(response: Map[NodeId, List[LECNodeSeries]]): Map[NodeId, LECNodeCurve] =
    response.flatMap { case (id, series) =>
      series.find(_.withMitigations.isEmpty).map(id -> _.curve)
    }
```

The `getOrElse` above throws on a value that cannot fail — the empty selection
breaks no bound — and that is the wrong shape for the finished code. Slice 6
replaces the whole call with one that carries a real selection and handles a
rejected one; until then the throw is unreachable and states the invariant.
`LECCurvesMultiRequest` exposes no total constructor deliberately, so this is
the one place the tension shows.

#### 7.6.6 Slice 2 — the tree write path carries mitigations

**What this slice changes, in plain terms.** A mitigation can be stored today
and read back, but no request can create one: the tree PUT has no mitigation
field, and `RiskTreeServiceLive.update` carries the previous version's
mitigations forward so that the omission does not delete them. This slice gives
the request both mitigation buckets, deletes the carry-over, and closes the two
reference problems the buckets create.

**`modules/common/src/main/scala/com/risquanter/register/http/requests/MitigationRequests.scala`** — new file:

```scala
package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec, JsonEncoder, JsonDecoder}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.{RiskLeafTransform, TransformPipeline}

/** A mitigation's effect as a request states it. It differs from the stored
  * `MitigationSpec` in exactly two ways, both deliberate. The override anchor
  * is a node NAME, because a request may attach an override to a node the same
  * request creates and ids are minted server-side. There is no override base
  * stamp field at all, because the stamp is the content hash of the anchored
  * leaf and is computed server-side; a client cannot state it and now cannot
  * send it.
  */
sealed trait MitigationSpecRequest

object MitigationSpecRequest:

  final case class LeafStage(
    transform: RiskLeafTransform,
    overrideAnchorName: Option[String]
  ) extends MitigationSpecRequest

  final case class ResultStage(pipeline: TransformPipeline) extends MitigationSpecRequest

  private case class Raw(
    stage: String,
    transform: Option[RiskLeafTransform],
    overrideAnchorName: Option[String],
    pipeline: Option[TransformPipeline]
  )
  private object Raw { given c: JsonCodec[Raw] = DeriveJsonCodec.gen }

  given codec: JsonCodec[MitigationSpecRequest] = JsonCodec(
    JsonEncoder[Raw].contramap {
      case LeafStage(t, anchor) => Raw("leaf", Some(t), anchor, None)
      case ResultStage(p)       => Raw("result", None, None, Some(p))
    },
    JsonDecoder[Raw].mapOrFail {
      case Raw("leaf", Some(t), anchor, None)    => Right(LeafStage(t, anchor))
      case Raw("result", None, None, Some(p))    => Right(ResultStage(p))
      case other => Left(s"invalid mitigation spec: stage '${other.stage}' with mismatched fields")
    }
  )

  given schema: Schema[MitigationSpecRequest] = Schema.any[MitigationSpecRequest]

/** Create bucket: a mitigation the request is adding. No id — the server mints
  * it (ADR-017 Decision 1). */
final case class MitigationDefinitionRequest(
  name: String,
  target: String,
  spec: MitigationSpecRequest,
  precedence: Int = 0
)

object MitigationDefinitionRequest:
  given codec: JsonCodec[MitigationDefinitionRequest] = DeriveJsonCodec.gen
  given schema: Schema[MitigationDefinitionRequest]   = Schema.any[MitigationDefinitionRequest]

/** Identity-preserving bucket: a mitigation the request is keeping, by id. */
final case class MitigationUpdateRequest(
  id: String,
  name: String,
  target: String,
  spec: MitigationSpecRequest,
  precedence: Int = 0
)

object MitigationUpdateRequest:
  given codec: JsonCodec[MitigationUpdateRequest] = DeriveJsonCodec.gen
  given schema: Schema[MitigationUpdateRequest]   = Schema.any[MitigationUpdateRequest]
```

`target` is the predicate source text, which is the only stored form of a
targeting predicate; refinement runs `TargetingPredicate.create` at the DTO
boundary, so a malformed predicate is a 400 before any handler runs.

**`RiskTreeUpdateRequest` and `RiskTreeDefinitionRequest`** gain their buckets:

```scala
final case class RiskTreeUpdateRequest(
  name: String,
  portfolios: Seq[RiskPortfolioUpdateRequest],
  leaves: Seq[RiskLeafUpdateRequest],
  newPortfolios: Seq[RiskPortfolioDefinitionRequest],
  newLeaves: Seq[RiskLeafDefinitionRequest],
  mitigations: Seq[MitigationUpdateRequest] = Nil,
  newMitigations: Seq[MitigationDefinitionRequest] = Nil
)

final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest],
  newMitigations: Seq[MitigationDefinitionRequest] = Nil
)
```

**Omission now means deletion, and that is the point.** Once the update request
enumerates the mitigation collection, a PUT that omits `mitigations` deletes
every mitigation the tree had, exactly as omitting a node deletes the node. The
carry-over in `RiskTreeServiceLive.update` — `mitigations = oldTree.mitigations`
and the comment explaining it — is deleted in the same change, because leaving
it would make the new buckets inert. The consequence is that any client issuing
a tree PUT must send the mitigations back; the interface slice does that, and
until it lands, a PUT from the current browser clears the mitigation
collection. That is a real window and it is acceptable only because nothing can
create a mitigation before this slice: the collection it would clear is always
empty.

**`modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`** — resolved
shapes and validators:

```scala
  /** A mitigation from a request, refined but not yet anchored. The override
    * anchor is still a node NAME here: nodes created in the same request have
    * no id until the service mints them, so the anchor resolves server-side
    * against the same name-keyed map parent names resolve against. */
  final case class ResolvedMitigation(
    id: MitigationId,
    name: SafeName.SafeName,
    target: MitigationTarget,
    stage: ResolvedMitigationStage,
    precedence: MitigationPrecedence
  )

  enum ResolvedMitigationStage:
    case LeafStage(transform: RiskLeafTransform, overrideAnchorName: Option[SafeName.SafeName])
    case ResultStage(pipeline: TransformPipeline)
```

`ResolvedCreate` and `ResolvedUpdate` each gain `mitigations: Seq[ResolvedMitigation]`.
Kept and added mitigations merge into one field because they end in one
collection and the id is already decided by the time they meet: a kept
mitigation carries the client's id, an added one carries an id minted by the
same `IdGenerator` that mints node ids.

```scala
  private[requests] def refineMitigationDefs(
    mitigations: Seq[MitigationDefinitionRequest],
    baseLabel: String,
    newId: IdGenerator
  ): Validation[ValidationError, Seq[ResolvedMitigation]]

  private[requests] def refineExistingMitigations(
    mitigations: Seq[MitigationUpdateRequest],
    baseLabel: String
  ): Validation[ValidationError, Seq[ResolvedMitigation]]

  /** Guard: one request may not name the same mitigation id or the same
    * mitigation name twice. `RiskTree.fromNodes` enforces the same rule on the
    * tree; this layer contributes the request-scoped field path so the client
    * is told which bucket entry is at fault. */
  private[requests] def requireDistinctMitigations(
    mitigations: Seq[ResolvedMitigation]
  ): Validation[ValidationError, Unit]
```

Each element's refinement accumulates its own errors through
`Validation.validateWith`: the name through `refineNameField`, the predicate
through `TargetingPredicate.create`, the id (kept bucket only) through
`ValidationUtil.refineId`, and the stage through a shape mapping that carries
the anchor name through `refineNameField`.

**`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`** — the anchoring
and stamping step, and the two call sites:

```scala
  /** Turn a request's mitigations into stored ones: resolve each override
    * anchor name against the request's own nodes, and stamp the override with
    * the anchor leaf's current content hash.
    *
    * An anchor naming something the request does not contain, or naming a
    * portfolio, fails the request — an override asserts a value for one
    * assessed leaf, and an anchor that names no leaf can never bind again.
    * `Mitigation.create` then enforces the pairing rule: a transform with an
    * Override component must have both an anchor and a stamp, and one without
    * must have neither. */
  private def buildMitigations(
    resolved: Seq[RiskTreeRequests.ResolvedMitigation],
    nodesByName: Map[SafeName.SafeName, RiskTreeRequests.ResolvedNode],
    nodes: Seq[RiskNode]
  ): Task[Seq[Mitigation]]
```

The anchor resolution is: name → `ResolvedNode` → `NodeId` → the built
`RiskLeaf` in `nodes` → `ContentHashIndex.hashOf(leaf)`. A name absent from
`nodesByName`, or present but resolving to a portfolio, is a
`MISSING_REFERENCE` / `INVALID_NODE_TYPE` validation error carrying the
mitigation's field path.

`create` passes `mitigations = buildMitigations(resolved.mitigations, resolved.nodes, nodes)`
instead of `Nil`, and `update` passes the same instead of `oldTree.mitigations`.
`allocateIds` in both methods widens to cover the added mitigations:

```scala
      ids <- allocateIds(req.newPortfolios.size + req.newLeaves.size + req.newMitigations.size)
```

**`modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`** — unchanged
by this slice. Decision 9 rules that a PUT deleting an anchored leaf is accepted,
so `validateMitigations` keeps the invariants it already enforces — unique
mitigation ids, unique mitigation names and the collection bound — and gains no
anchor cross-check. Its signature and its docstring stay as they are.

An override whose anchor names a deleted node is therefore a legal tree.
`MitigationStaleness.isStale` reports it stale, slice 3 carries the result to the
client in `staleMitigationIds`, and the user retargets or removes the mitigation
when they choose. Placing the check in `validateMitigations` would have put it in
the decode path as well, since `RiskTree`'s decoder routes through `fromNodes`;
not adding it keeps every stored tree readable.

#### 7.6.7 Slice 3 — the tree read carries resolved scopes

**What this slice changes, in plain terms.** The selection interface has to draw
a mitigation's row under every node it currently scopes, and has to say which
mitigations no longer match anything, before the user ticks anything. The
mitigation definitions already cross the wire inside the tree. What is missing
is the resolution of each predicate against this tree version, and the ids of
overrides whose stored stamp no longer matches the leaf they were authored
against.

**New file — `modules/common/src/main/scala/com/risquanter/register/http/responses/TreeStructureResponse.scala`.**

```scala
package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Why one mitigation's targeting predicate did not resolve, in wire form.
  * `code` is one of "unknown-node", "malformed-node-id", "type-error",
  * "malformed-predicate", "internal-error". `detail` names the reference or
  * symbol at fault for the four a user can act on; for "internal-error" it is a
  * fixed message, because that case carries server internals a client must not
  * receive (ADR-035).
  */
final case class ScopeFailureView(code: String, detail: String)

object ScopeFailureView:
  given codec: JsonCodec[ScopeFailureView] = DeriveJsonCodec.gen[ScopeFailureView]
  given schema: Schema[ScopeFailureView]   = Schema.derived[ScopeFailureView]

/** One mitigation's resolution against the tree version this read returned: the
  * nodes it applies to, and why it resolved to none when its predicate did not
  * bind. An empty `failures` with an empty `resolvedScope` is a predicate that
  * bound correctly and matched nothing — a different situation from a predicate
  * that failed, and the interface says so differently.
  */
final case class MitigationScopeView(
  mitigationId: MitigationId,
  resolvedScope: Set[NodeId],
  failures: List[ScopeFailureView]
)

object MitigationScopeView:
  given codec: JsonCodec[MitigationScopeView] = DeriveJsonCodec.gen[MitigationScopeView]
  given schema: Schema[MitigationScopeView]   = Schema.derived[MitigationScopeView]

/** The structure read: the tree, each mitigation's resolved scope for this
  * version, and the overrides whose stored base stamp no longer matches the
  * leaf they were authored against.
  *
  * The scopes travel beside the tree rather than inside it. `RiskTree` is the
  * persisted content type — what a PUT carries and what the store holds — so a
  * server-computed, per-version resolution placed inside it would persist
  * derived mitigation state and would claim a version-independence it does not
  * have.
  */
final case class TreeStructureResponse(
  tree: RiskTree,
  mitigationScopes: List[MitigationScopeView],
  staleMitigationIds: Set[MitigationId]
)

object TreeStructureResponse:
  given codec: JsonCodec[TreeStructureResponse] = DeriveJsonCodec.gen[TreeStructureResponse]
  given schema: Schema[TreeStructureResponse]   = Schema.derived[TreeStructureResponse]
```

**`MitigationScopeResolver.scala`** gains the wire mapping on the failure enum,
following the `NodeChangeStatus.toWire` precedent — the enum is server-side, the
view type is shared, and the server module already depends on the shared one:

```scala
enum ScopeResolutionFailure:
  ...
  def toWire: ScopeFailureView = this match
    case UnknownNode(reference)      => ScopeFailureView("unknown-node", reference)
    case MalformedNodeId(reference)  => ScopeFailureView("malformed-node-id", reference)
    case TypeError(detail)           => ScopeFailureView("type-error", detail)
    case MalformedPredicate(detail)  => ScopeFailureView("malformed-predicate", detail)
    case InternalError(_)            => ScopeFailureView("internal-error", "resolution failed")
```

**`RiskTreeService`** gains one method rather than widening `getById`:

```scala
  /** The tree at this read coordinate, together with each mitigation's resolved
    * scope for that version and the ids of overrides whose base stamp is stale.
    *
    * Separate from `getById` because resolving scopes builds a knowledge base
    * and evaluates every predicate. `getById` is on the update path's pre-read
    * and on the cascade paths, none of which consume a resolution, and none of
    * which should pay for one. The commit hash `getById` returns is not
    * analogous: it is already in hand from the same read and costs nothing.
    */
  def getStructure(wsId: WorkspaceId, id: TreeId, branch: BranchRef, at: Option[CommitHash])(
    using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]
  ): Task[Option[(RiskTree, ResolvedScopes, Set[MitigationId])]]
```

The implementation reads through `repo.getById`, calls the workspace's scope
resolver with `ScopeResolutionContext(id, branch, commit)`, and computes
`MitigationStaleness.staleOverrides(tree)`. It returns domain values; the
controller builds the wire type, exactly as the changed-nodes endpoint does:

```scala
  val getTreeStructure: ServerEndpoint[Any, Task] = getWorkspaceTreeStructureEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch, at) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        loaded <- riskTreeService.getStructure(ws.id, treeId, branch, at)
      yield loaded.map { case (tree, scopes, stale) =>
        TreeStructureResponse(
          tree = tree,
          mitigationScopes = scopes.outcomes.toList.sortBy(_._1.value).map { (id, outcome) =>
            MitigationScopeView(id, outcome.scopeOrEmpty, outcome.failures.toList.flatMap(_.toList).map(_.toWire))
          },
          staleMitigationIds = stale
        )
      }).either
  }
```

and the endpoint's output type becomes `jsonBody[Option[TreeStructureResponse]]`.

**The browser, minimally.** `TreeViewState.emitStructureFetch` now receives the
response object; the minimal adaptation maps it to `_.tree` so every existing
consumer of `selectedTree` is untouched. The scopes and the stale ids are
carried and unused until slice 6, which is where a consumer for them exists.

#### 7.6.8 Slice 4 — change notification sees mitigation edits

**What this slice changes, in plain terms.** When a tree is saved, the server
tells subscribers which nodes' figures changed. It computes that from the node
collection alone, so editing a mitigation — which changes every scoped node's
mitigated figures — reports nothing at all. This slice makes the diff see the
mitigation collection.

**`MitigationScopeResolver.scala`** exposes resolution as a pure function, which
is what the write path can actually call:

```scala
object MitigationScopeResolver:
  /** Resolve every mitigation's predicate against `tree` without consulting or
    * writing the memo. The write path has neither a workspace-scoped resolver
    * instance nor the commit hash a memo entry is keyed by, and resolution is
    * cheap to repeat: one knowledge-base build plus one quantifier-free
    * evaluation per mitigation. */
  def resolveUncached(tree: RiskTree): ResolvedScopes
```

`computeAll`, `resolveOne`, `satisfyingIds`, `fromTypeCheckError` and
`fromQueryError` move from `MitigationScopeResolverLive` into this object
unchanged — none of them reads instance state — and the live resolver's miss
branch calls `MitigationScopeResolver.resolveUncached(tree)`. This is how
Decision 1's "scope-resolver dependency on the write path" is discharged:
`InvalidationHandler` gains no constructor parameter and no layer requirement,
because `handleMutation` is given two trees and a branch and has neither a
workspace nor a commit to key a memo with.

**`InvalidationHandler.scala`** — the diff:

```scala
  /** Nodes whose mitigated figures changed because the mitigation collection
    * changed.
    *
    * A mitigation is compared on what it does — its target, its spec and its
    * precedence — so renaming one contributes nothing, which is correct: a name
    * changes no figure. A mitigation whose effect changed affects every node in
    * either version's scope. A mitigation whose effect is unchanged but whose
    * scope moved — a node was renamed into or out of what its predicate matches
    * — affects exactly the nodes the two scopes disagree on.
    *
    * Resolving both versions costs two knowledge-base builds per mutation,
    * which is the same order as the tree-diff this method already performs and
    * orders of magnitude below a simulation.
    */
  private def mitigationAffectedNodes(oldTree: RiskTree, newTree: RiskTree): Set[NodeId] = {
    val oldScopes = MitigationScopeResolver.resolveUncached(oldTree).appliedScopes
    val newScopes = MitigationScopeResolver.resolveUncached(newTree).appliedScopes
    val oldById   = oldTree.mitigations.map(m => m.id -> m).toMap
    val newById   = newTree.mitigations.map(m => m.id -> m).toMap

    (oldById.keySet ++ newById.keySet).flatMap { id =>
      val before = oldScopes.getOrElse(id, Set.empty)
      val after  = newScopes.getOrElse(id, Set.empty)
      val effectChanged = oldById.get(id).map(mitigationEffect) != newById.get(id).map(mitigationEffect)
      if effectChanged then before ++ after
      else (before ++ after) -- (before intersect after)
    }.filter(newTree.index.nodes.contains)
  }

  /** What a mitigation does, as a comparison key. Encoded rather than compared
    * by value for the same reason `nodeContent` is: a transform can carry
    * arrays, which compare by reference, and every mutation rebuilds the tree,
    * so value comparison would report every mitigation as changed on every
    * save. The name is deliberately not part of the key. */
  private def mitigationEffect(m: Mitigation): String =
    s"${m.target.toJson}|${m.spec.toJson}|${m.precedence.key}"
```

`computeAffectedNodes` returns `(affectedFromAdded ++ affectedFromRemoved ++ affectedFromChanged ++ mitigationAffectedNodes(oldTree, newTree), removed)`,
and every affected node still expands to its ancestor path in the existing
caller. The browser has no consumer for these messages, so this publish reaches
zero subscribers by design; building the consumer is M5 (§7.7), not an omission
here.

**The scope memo becomes capacity 2.** §8.4-5's amendment lands in this slice
because the read path it serves is slice 1's: a reader alternating between the
branch head and a pinned revision currently misses on every call and gains
nothing from the memo.

```scala
/** Memoizes resolved scopes per tree version. Each (tree, branch) slot holds at
  * most two revisions, most recently used first, so a pinned historic read
  * takes the second position and the head entry survives. The bound is a
  * property of the structure, not an eviction policy.
  *
  * The memo read and write are not atomic — last-writer-wins is a deliberate,
  * accepted trade-off, safe because the exact-revision hit guard never serves a
  * mismatched scope.
  */
final case class MitigationScopeResolverLive(
  memo: Ref[Map[(TreeId, BranchRef), Vector[(CommitHash, ResolvedScopes)]]]
) extends MitigationScopeResolver:

  override def resolve(context: ScopeResolutionContext, tree: RiskTree): UIO[ResolvedScopes] =
    val slot = (context.treeId, context.branch)
    memo.get.map(_.getOrElse(slot, Vector.empty)).flatMap { entries =>
      entries.find(_._1 == context.revision) match
        case Some(hit) =>
          memo.update(m => m.updated(slot, hit +: entries.filterNot(_._1 == context.revision))).as(hit._2)
        case None =>
          val resolved = MitigationScopeResolver.resolveUncached(tree)
          memo.update { m =>
            val current = m.getOrElse(slot, Vector.empty).filterNot(_._1 == context.revision)
            m.updated(slot, ((context.revision, resolved) +: current).take(MitigationScopeResolverLive.SlotCapacity))
          }.as(resolved)
    }

object MitigationScopeResolverLive:
  /** Revisions one (tree, branch) slot holds: the branch head and one pinned
    * revision. */
  val SlotCapacity: Int = 2
```

`MitigationScopeResolverRegistry.forWorkspace` constructs the widened `Ref` type:

```scala
          memo <- Ref.make(Map.empty[(TreeId, BranchRef), Vector[(CommitHash, ResolvedScopes)]])
```

#### 7.6.9 Slice 5 — the two decision records

Decision 2 ruled that the client-facing mitigation contract is split by surface.
Both documents land with the code they describe, not after it.

**`docs/dev/decision-records/ADR-017-tree-api-design.md` — amendment.** The
"Four Buckets" decision describes a tree PUT that this plan changes, so leaving
it as it stands would make it wrong. The amendment records that the update
request carries two further buckets, `mitigations` and `newMitigations`; that
the create request carries `newMitigations` only, because Decision 1 of that
same ADR keeps id-bearing buckets out of create requests; that omission of the
mitigation collection deletes it, exactly as for nodes; and that an override
anchor is stated as a node name and resolved server-side against the same
name-keyed map parent names resolve against. The amendment also records that a
tree PUT whose mitigation carries an override anchor naming an absent node is
accepted, and that the resulting mitigation reports itself stale (Decision 9).

**The same amendment corrects that ADR's HTTP surface table, which is wrong in
both directions.** It lists `POST /w/{key}/risk-trees/{treeId}/invalidate/{nodeId}`,
an endpoint that exists nowhere in `modules/`; and it omits every workspace-scoped
endpoint that is not tree CRUD — `changed-nodes`, `history`, `revert`,
`nodes/{nodeId}/prob-of-exceedance`, `nodes/lec-multi`, `query`, the five
`scenarios` routes, `rotate`, and workspace delete. Fixing the table as a whole is
part of this amendment, not a separate change: the mitigation buckets are being
added to a table that does not currently describe the surface it claims to.

**`docs/dev/decision-records/ADR-037-analysis-read-contract.md` — new, following the
ADR-00X template.** It covers the analysis read contract as a whole rather than
its mitigation-shaped part: the required branch header, the optional `at` pin
and what it means, why both analysis endpoints take a JSON body, the selection's
two-list form and its conversion, the rule that a named-but-absent mitigation
fails the request while a mitigation that applies to nothing does not, the
two response shapes and the one reading of `withMitigations` they share — the
curve endpoint answers a list of readings per node so that both valuations land
on one tick domain, the exceedance endpoint answers one reading because a single
probability has no axis to share — the payload bounds,
and the reason there is no staleness or revision field — stored versions are
immutable, so a request is answered at the version it names and there is nothing
for a client's view to diverge from.

**Scope of the ADR amendments — RULED 2026-09-15 (user).** Slice 5 amends the
four records marked "Amended, slice 5" in §7.6.10, and in ADR-003 and ADR-009 it
also corrects two statements that were already wrong before this plan touched
them, because they sit in the same paragraphs:

- ADR-003's Implementation table claims optional provenance capture is
  implemented via an `includeProvenance` flag. The flag sets a tracing attribute
  and nothing else, and no production caller passes `true`.
- ADR-003's Decision 4 and ADR-009 §5 both publish
  `group.children.collect { case r: RiskResult => r.nodeId -> r.provenances }`
  as the provenance derivation. That pattern exists nowhere in `src/main`.

These two are the follow-up step ADR housekeeping task T4 leaves open. T4 itself
is closed: ADR-003 was rewritten on 2026-09-25 to state the boundary-assigned
seed-identity decision and to fold its per-node-provenance section into
Decision 4, and both statements above were left untouched in that pass so that
slice 5 is their only edit. The section numbering the corrections apply to is
therefore the new one — Decision 4 and the Implementation table row "Optional
provenance capture".

Editing a paragraph while leaving an adjacent falsehood in it is the drift the
docs-as-current-state rule exists to stop, so both are fixed here. Everything
else the 2026-09-15 ADR review found is housekeeping and is tracked in
[`docs/dev/ADR-HOUSEKEEPING.md`](../ADR-HOUSEKEEPING.md), not in this plan.

#### 7.6.10 ADR alignment

| ADR | Bearing | Status |
|---|---|---|
| ADR-001 (validate once, at the boundary) | The request types carry smart constructors and their decoders run them, so a handler receives a selection that already satisfies both bounds. The one check deliberately outside the decoder — whether a named mitigation exists — is a lookup against a loaded tree, not a field format rule, and the plan says so where it is placed | Compliant |
| ADR-001 §2 (Iron types in JSON bodies need an explicit Tapir schema) | `Schema[MitigationId]` is added beside the existing `Schema[NodeId]` | Compliant |
| ADR-002 (drift signals, not failures) | A predicate that no longer binds makes one mitigation a no-op and is logged; it never fails a read | Compliant |
| ADR-003 (provenance and reproducibility) | Uniform wrapping puts a `ValuationResult` between a portfolio and its children, so the resolver's provenance walk descends through `source` to keep Decision 4's "union of all leaf provenances in its subtree, in child order". ADR-003's Implementation table separately claims optional provenance capture is implemented via `includeProvenance`, which sets a tracing attribute only | Amended, slice 5 |
| ADR-004a (storage mapping) | Unchanged: mitigations are already stored as `mitigations/{id}` blobs and this plan adds no storage shape | Compliant |
| ADR-009 (associativity of the aggregate) | Result-stage transforms still apply to a finished node value, never inside the summation — compliant and unchanged. But §2 enumerates exactly two subtypes and the Implementation table names them, and a third subtype makes both stale; §5's `children.collect { case r: RiskResult => … }` provenance pattern is superseded by the `source` descent | Amended, slice 5 |

| ADR-010 (typed errors, accumulated) | Every new validation returns `ValidationError` with a code, and independent checks accumulate through `Validation.validateWith` | Compliant |
| ADR-014 (render-time curve computation) | Both valuations of every requested node go through one `generateCurvePointsMulti` call, so the shared tick domain covers them together | Compliant |
| ADR-015 (query APIs compose on `ensureCached`) | The mitigated reading is a second `ensureCached`/`ensureCachedAll` call with a selection, not a new resolution path — compliant. The ADR writes the resolver trait out verbatim in a form two generations old, and §7.6.12 decision 2 would change it again if the return type is narrowed | Amended, slice 5 |

| ADR-017 (tree API design) | The tree PUT gains two buckets; the ADR is amended in slice 5 rather than contradicted | Amended, slice 5 |
| ADR-018 (nominal id wrappers) | `MitigationId` stays distinct from `NodeId` and `TreeId` throughout the new types | Compliant |
| ADR-019 (frontend ownership rules) | Slice 1's browser change touches one state class and adds one pure function beside it; no component gains state | Compliant |
| ADR-024 (application as a pure enforcement point) | Both analysis handlers keep their `AnalyzeRun` check and the structure handler keeps `ViewTree`; the method change on one endpoint moves no check | Compliant |
| ADR-032 (two equality relations) | The mitigation diff compares encoded content, not values, for the same array-equality reason the node diff already does | Compliant |
| ADR-033 (narrowest sound catch) | `ValuationResult.create` catches `ArithmeticException` from the scaled-loss guard and converts it to a `ValidationError`, the same named-type conversion `RiskResultGroup.create` already performs in this file. ADR-033's Implementation table lists `LossDistribution.scala` and gains the second site | Amended, slice 5 |
| ADR-034 (mitigation valuation model) | Two valuations, never one merged value; the mitigated aggregate folds mitigated children; nothing mitigated is persisted. ADR-034 was restructured on 2026-09-14: its Decision 3 states that a transformed node's mitigated value is flat by construction, and its Decision 4 carries the `ValuationResult` ruling that §8.16 records | Compliant; ADR-034 amended |
| ADR-035 (error leakage prevention) | The internal-error resolution failure reaches the wire as a fixed message with no detail | Compliant |
| ADR-036 (confidential internal identifiers) | `WorkspaceId` and `BranchRef` stay on internal service signatures and never cross the client boundary. `TreeId`, `NodeId` and `MitigationId` are not confined by this record: it names `WorkspaceId` as its subject and shows `nodeId` as a client-safe reporting field, and every lookup taking a client-supplied node id resolves it inside a tree the caller is already authorized for | Compliant |

#### 7.6.11 Verification plan

New tests, by the behaviour each one pins:

- `modules/common/src/test/scala/com/risquanter/register/http/requests/MitigationSelectionRequestSpec.scala` —
  both lists empty converts to the mitigation-free reading; the two-list form
  converts to the entry map; a mitigation in both lists converts to full scope;
  an omitted list decodes as empty; each bound rejects with its own field path
  and both bounds accumulate.
- `modules/common/src/test/scala/com/risquanter/register/http/requests/MitigationRequestsSpec.scala` —
  a malformed predicate is rejected at the DTO boundary; a request-side
  duplicate mitigation id and a duplicate name are each rejected; the request
  spec type has no field in which a base stamp could be sent.
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeSpec.scala` (or the
  existing mitigation entity spec) — a tree whose override anchor names a node
  that is not in the tree still builds and still decodes, because Decision 9
  makes that a staleness signal rather than a validation failure; duplicate
  mitigation ids, duplicate mitigation names and an over-large collection each
  still fail.
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala` —
  a mitigation whose override anchor names a deleted node reports stale, and a
  mitigation whose anchor names a node that is no longer a leaf reports stale.
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala` — the
  mitigation-free reading is always present and first; a node inside an applied
  scope carries exactly one further reading; a node outside every applied scope
  carries one reading only; a portfolio above a scoped leaf carries a second
  reading whose `withMitigations` names the descendant's mitigations; the
  exceedance endpoint answers one reading rather than a list, with an empty
  `withMitigations` for a node no applied scope reaches and the descendant's
  mitigations named for a portfolio above a scoped leaf; a cap set above the
  queried threshold leaves that endpoint's probability equal to the
  mitigation-free figure while `withMitigations` still names the mitigation,
  which is what makes the field load-bearing rather than decorative; a
  selection naming an absent mitigation fails with `NOT_FOUND`; a
  mitigation-free request performs one resolution pass and no scope resolution;
  a tree PUT carrying mitigation buckets stores them, and a PUT omitting the
  buckets clears them.
- `modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala`
  (new, or added to the existing pipeline spec) — adding a mitigation publishes
  its scope and the ancestors; removing one publishes the scope it had; renaming
  a mitigation publishes nothing; renaming a node into a predicate's match
  publishes the difference between the two scopes.
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala` —
  alternating between head and one pinned revision hits the memo on both after
  the first pass; a third revision evicts the least recently used of the two.
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisControllerSpec.scala`
  (new) — the exceedance endpoint answers a POST with a body; an oversized
  selection is a 400 before the handler runs; the structure endpoint returns the
  resolved scopes and the stale ids beside the tree.
- `modules/app/src/test/scala/app/state/LECChartStateSpec.scala` — `inherentCurves`
  takes the empty-mitigation series and drops nothing else.
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala` — updated
  to the request object, its assertions unchanged.
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala` —
  extended with a round trip through the service: a PUT creating a mitigation,
  a structure read returning its resolved scope, and a curve read under a
  selection naming it.
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala`
  (new test) — a tree carrying a result-stage mitigation is created over the real
  HTTP API and read back with its `TransformPipeline` intact, step order and all.
  No test sends a mitigation over HTTP today: the persistence spec reaches Irmin
  through the repository rather than the wire, and every mitigation it builds is a
  leaf-stage one, so the result-stage branch has never crossed a real boundary.
  The gap matters because that branch's decoder is hand-assembled — five per-case
  codecs chained behind an `op` discriminator, each re-validating its Iron fields —
  and an in-process round trip only ever feeds a decoder bytes its own encoder
  produced. Mitigations have no endpoints of their own; they ride inside the tree
  payload, so the tree create and read calls are where this is exercised.

Commands that must be green before any slice is reported done. The complete run
is all four:

```
sbt 'commonJVM/test; server/test'
sbt app/test
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f; docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm
sbt 'serverIt/test'
```

The leaked-network cleanup is a mandatory pre-step for the integration tier, not
crash recovery. A BATS fast gate (`run_bats tests/bats/suite-c-in-memory.bats`,
invoked as the register-dev skill defines it) runs after the slices that change
the server image's behaviour, which is all of slices 1 to 4.

Each slice lands with a PATCH version bump in `build.sbt`, mirrored into `.env`
and `.env.irmin`; closing M4 is the MINOR bump.

#### 7.6.12 Open decisions

Five decisions gate the `ValuationResult` sub-slice ruled in §8.16, which slice 1
consumes. Two further decisions gate slice 6. Slices 2 to 5 carry none. All seven
are listed here so the elevation states them rather than implying them.

Numbering is kept stable because other sections reference these by number.
Decision 5 was answered by checking the inventory rather than by a ruling, so it
keeps its slot and records the answer in place. Decisions 8, 9 and 10 are the
open ones.

**Gating the §8.16 sub-slice.** §8.16 rules the design; none of the following was
ruled by it, and each changes what the code looks like. The reasoning that
produced the design is in
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md)
and should be read before any of these is answered.

**Ruled 2026-09-15 (user):** decisions 1, 2, 3, 4, 6 and 7 below, each recorded
in place. Decision 5 was answered by checking the inventory rather than by a
ruling. Two new questions were raised while ruling 1 and 2 and are recorded as
decisions 8, 9 and 10 at the end of this section; the sub-slice is not elevated until they are
settled, because each changes where the type lives or what it is.

1. **Where `ValuationResult` is defined.** It extends `LossDistribution`, whose
   hierarchy is sealed in
   `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`.
   Scala 3 permits a subclass of a sealed class only in the **same source file** —
   not the same directory and not the same package — so keeping the hierarchy
   sealed means the type goes in that file. That places a read-edge concept in the
   shared domain module, which every other valuation type already sits in. The
   alternative — unsealing the hierarchy to put it in `server` — trades an
   enforced invariant for module placement, and the invariant is load-bearing:
   sealing is what makes the exhaustiveness check in the subsection "The sealed
   hierarchy gains a third case" a compile error rather than a silent omission,
   and ADR-035 §1 relies on the same mechanism for the error hierarchy.

   **RULED 2026-09-15 (user): keep the hierarchy sealed.** Unsealing is off the
   table. Which module the sealed file lives in is a separate question and is
   decision 8 below.
2. **The resolver trait's return type.** `ensureCached` and `ensureCachedAll`
   return `Task[LossDistribution]` and `Task[Map[NodeId, LossDistribution]]`.
   Under uniform wrapping every returned value is a `ValuationResult`, so the
   return type can be narrowed to say so, or left wide. Narrowing states the fact
   in the type and is the reason the decorator exists.

   **Corrected baseline (2026-09-15).** An earlier version of this decision said
   narrowing "moves every stub and test that wires the resolver layer". That is
   wrong. `CachedResultResolverLive` is the only implementation of the trait in
   the repository; no test implements it, and every test wires
   `CachedResultResolverLive.layer`. The three production consumers — two in
   `RiskTreeServiceLive`, one in `QueryServiceLive` — read the returned values
   through members inherited `final` from the base class, so narrowing is
   source-compatible for them. `RiskTreeKnowledgeBase` keeps its
   `Map[NodeId, LossDistribution]` parameter and still accepts a narrowed map,
   because `Map` is covariant in its value type. The edit is the trait's two
   signatures, the companion's two accessors and the two overrides in the live
   implementation.

   **RULED 2026-09-15 (user): narrow it.** Two questions raised while ruling this
   are recorded as decisions 9 and 10 below; both bear on what is being narrowed
   to, so the signature is not written until they are settled.
3. **Where the wrapping happens.** Either `CachedResultResolverLive`'s recursion
   builds the decorator directly at each node, or a separate function decorates
   what the existing recursion returns. This is the remaining part of the
   "second traversal or threaded pair" question §8.14 left to the code step.

   One consequence to weigh that §8.14 did not state: a fold that threads a pair —
   the raw value and the mitigated value together — would let a single resolver
   call return both readings, which would collapse §7.6.5's two `ensureCachedAll`
   calls into one. It is not free. Each call today builds its own effective tree
   via `MitigationApplication.effectiveTree` and its own
   `ContentHashIndex.build(effective)`, and the whole recursion looks nodes up
   through that effective tree. A pair-threaded fold would have to carry both
   trees and both hash indexes, because a parameter-stage transform gives a leaf a
   different content hash in the mitigated pass than in the raw one. Whether that
   is worth removing one traversal is the decision; the reasoning document's Part 9
   describes the read path as resolving twice, so two calls is the shape that
   document assumes and the one §7.6.5 currently writes.

   **RULED 2026-09-15 (user): second traversal, two calls.** The threaded pair is
   rejected on a ground the options list did not carry: a caller may ask for more
   than two valuations at once. The analytics query language already binds an
   existential over mitigations, so a request can reference several selections in
   one evaluation, and §7.6.2 precomputes one result map per referenced selection.
   A fold threading a fixed pair serves exactly two readings and would have to be
   generalised or abandoned the moment a third is asked for, whereas repeating a
   one-selection traversal per selection scales without redesign. The repeated
   work is the portfolio combines; the expensive half, leaf simulation, is shared
   through the content-addressed cache wherever the effective leaf content is
   unchanged.

   The post-hoc decoration option that appeared in earlier framings is withdrawn
   as not viable: `applied` needs the records scoped to that node and `source`
   needs the combine of the mitigated children, and both exist only during the
   fold, so a pass over its finished output would have to re-walk the tree and
   re-derive them.
4. **Whether `flatten`'s removal travels with this sub-slice or lands
   separately.** It touches the same file and the same sealed hierarchy, which
   argues for one change; it is also a deletion with no dependency on
   `ValuationResult`, which argues for landing it first and alone so the
   `ValuationResult` diff carries no unrelated deletion.

   **RULED 2026-09-15 (user): lands separately, first.** The deletion is its own
   landing — the abstract member on `LossDistribution`, the overrides on
   `RiskResult` and `RiskResultGroup`, and the two assertions in
   `LossDistributionSpec.scala` — taken green before any `ValuationResult` work
   starts. It has no dependency on the decorator and is banked even if decisions 8,
   9 or 10 hold the sub-slice up. `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`
   §12.4 records the shape to reach for should the method ever return; it stays
   unruled.
5. **The file inventory delta.** Checked against the inventory as it stands, the
   answer is that **no bullet has to be added** for the sub-slice as ruled:

   - `LossDistribution.scala`, `CachedResultResolver.scala`,
     `CachedResultResolverLive.scala`, `RiskResultTransform.scala`,
     `CascadeTestStubs.scala`, `ProvenanceSpec.scala`,
     `CachedResultResolverSpec.scala`, `CacheTransparencySpec.scala` and
     `RiskResultTransformSpec.scala` are already bullets.
   - `LossDistributionSpec.scala` is **not** a bullet and does not need to be. It
     sits under `modules/common/src/test/`, and the enforcement hook authorises a
     module's test tree whenever the plan lists any file under that module's
     `src/main` — which it does many times over.

   What remains genuinely conditional is decision 2: if the resolver trait's
   return type is narrowed, every stub implementing that trait moves, and each
   such file must be re-checked against the inventory before it is edited.

**Gating slice 6.**

6. **The exceedance endpoint's answer shape.** §7.6.3 wrote out the curve
   response and not this one. Three shapes were on the table: the curve
   endpoint's three rules applied unchanged, so that a node answers a list with
   the mitigation-free reading always present and first; a bare probability with
   selections refused outright; or one reading under the caller's selection,
   carrying `withMitigations`. It is recorded as a decision because the shape was
   derived here rather than ruled at §7.6.3, and because the endpoint has no
   in-repository client, which is what made every one of the three affordable.

   **Baseline the ruling rests on.** Who actually consumes this
   endpoint was checked, because it decides whether changing its shape costs
   anything. `GET /w/{key}/risk-trees/{treeId}/nodes/{nodeId}/prob-of-exceedance`
   is defined in `WorkspaceAnalysisEndpoints.scala` and wired in
   `WorkspaceAnalysisController.scala:38`. Nothing else calls it: the browser
   module contains no reference to it, no integration test exercises it, and no
   BATS suite touches it. It is a published endpoint with no in-repository client.

   The curve endpoint it would be made to match answers with `LECNodeCurve`
   (`LEC.scala:52`) — node id, name, the curve points on a shared tick domain,
   the tail quantiles, the average annual loss and the probability of no loss.
   §7.6.3 ruled that a node's answer becomes a **list** of such readings rather
   than one, for a single reason: a node can be read under more than one
   valuation in one request, and the list is how the mitigation-free reading and
   the mitigated readings are returned together so they share one tick domain
   and can be drawn on one chart. Each entry carries `withMitigations` naming the
   mitigations that shaped it, with the empty list marking the mitigation-free
   reading.

   The exceedance endpoint returns a single probability, which has no tick domain
   and needs no shared axis. So the list is not forced on it by the same argument
   that forced it on the curve endpoint.

   **RULED 2026-09-15 (user): one `ExceedanceSeries`, not a list of them.** The
   endpoint answers the selection the caller passed, and answers it once. A
   caller who wants the mitigation-free figure beside a mitigated one asks
   twice, which costs a second tree read and a second scope resolution and no
   second simulation, because leaf results are content-addressed and the second
   request reads them from cache.

   The reading keeps `withMitigations` rather than collapsing to a bare
   probability, and that is the part of the ruling that carries weight. A bare
   number cannot say whether any mitigation reached this node, and a second
   request does not recover it: a mitigation whose scope reaches the node can
   leave `probOfExceedance` at one threshold bit-identical to the inherent
   figure — a cap set above the threshold moves every trial above the cap down
   to it, and each of those trials was already at or above the threshold and
   still is. So equal probabilities do not mean nothing applied, and only
   `withMitigations` distinguishes the two cases.

   `withMitigations` is read here exactly as it is on the curve endpoint, by the
   same `withMitigationsFor` function: the mitigations whose **resolved scope**
   covers this node or any node below it, ordered by
   `(precedence.key, id.value)`. It is not the set the caller sent, and it is
   not the set that changed the number. A requested mitigation scoping nothing
   in this subtree is absent from it; a mitigation scoping the subtree is
   present even when the probability did not move. A test of "did it change this
   figure" would require computing the inherent reading too, which is the second
   call this shape deliberately does not make.

   The ruling also removes a resolver call: the list shape resolved twice inside
   one request to build both entries, and one reading resolves once.
7. **Whether M4 ships mitigation authoring.** §7.4's interface list covers
   selecting mitigations, drawing their effect, comparing selections, the
   badges, and the override edit popup. It does not say whether a user can
   create a mitigation and write its targeting predicate in the interface. Slice
   2 gives the API the ability; whether slice 6 builds the authoring screen, or
   M4 ships with mitigations authored through the API alone, decides a large
   part of slice 6's size.

   **RULED 2026-09-15 (user): M4 ships selection and visualisation only.**
   Mitigations are authored through the API for the duration of M4. Slice 6 builds
   what §7.4 lists and no authoring screen.

   The predicate editor gets **its own plan document**, not a continuation section
   here — this plan is already too large to absorb it. That plan follows M4 in the
   implementation sequence. M4's closing report ends with an explicit instruction
   to start it, so the gap M4 ships with is handed forward rather than discovered
   later: until the editor exists, a mitigation cannot be created without calling
   the API directly.

The two original slice-6 decisions kept their wording and were renumbered 6 and 7.

**Two obligations on M4's closing report, recorded here so neither is
rediscovered.** It ends with the instruction to start the predicate-editor plan,
per the ruling above. It also marks `docs/dev/TODO.md` item 40 closed: that item
asks for a maximum size on the multi-LEC endpoint's node-id list, and slice 1's
`LECCurvesMultiRequest` adds precisely that bound. If the bound ships ahead of
the rest of M4, item 40 is marked closed then rather than at the end.

**Raised while ruling decisions 1 and 2 (2026-09-15). These gate the sub-slice.**

8. **Which module the sealed hierarchy lives in.** Decision 1 settled that the
   hierarchy stays sealed, which pins every subtype to one source file. It did not
   settle which module that file sits in, and the review had wrongly treated
   `common` as forced. Moving the whole file to `server` keeps the sealing intact,
   because sealing constrains the file, not the module.

   Checked 2026-09-15: outside `server`, nothing uses the hierarchy in code.
   `LEC.scala` and `Provenance.scala` in `common/src/main` name `RiskResult` and
   `RiskResultGroup` only inside scaladoc. The browser's single occurrence, in
   `LECSpecBuilder.scala:404`, is also a comment. `server` has five files in
   `src/main` and ten in `src/test`. The live code users outside `server` are four
   files in `common/src/test`: `LossDistributionSpec.scala`,
   `RiskResultTestSupport.scala`, `PreludeOrdUsageSpec.scala` and
   `ConfigTestLoader.scala` — those would move with it. The dependency direction
   permits the move: `server` depends on `commonJVM`, so `SimulationConfig`,
   `NodeId`, `Loss` and `TrialId` all still resolve. A move also stops the
   hierarchy being cross-compiled into the Scala.js artifact, where nothing uses
   it.

   **RULED 2026-09-15 (user): move it to `server`.** The work is scoped in its
   own document,
   [`PLAN-LOSSDISTRIBUTION-TO-SERVER.md`](./PLAN-LOSSDISTRIBUTION-TO-SERVER.md),
   and lands **before** the `ValuationResult` sub-slice, so the third subtype is
   written once in its final home rather than written here and moved afterwards.

   Two facts that document establishes and this decision did not anticipate.
   The hierarchy is not the only server-only code in `common`:
   `RiskResultTransform.scala` and `MitigationApplication.scala` have the same
   property, and moving the group rather than the one file is what lets
   `LossDistribution.scala` move whole instead of being split around
   `TrialOutcomes`. And the argument that the move shrinks the browser bundle is
   false — the Scala.js linker already strips every one of these types, measured
   at zero occurrences in the linked output. The case is placement, not size.

   Consequences for this plan when that one lands: the inventory entries for
   `LossDistribution.scala` and `RiskResultTransformSpec.scala` both change path,
   and ADR-009 gains a second reason to be amended in slice 5 — it gives file
   paths for every item as well as enumerating the two subtypes.

9. **Whether `RiskResult` and `RiskResultGroup` stop being part of any consumer-
   facing surface, and what follows.** If decision 2's narrowing lands, the
   resolver hands back only `ValuationResult`, and the two older subtypes are
   reachable only through its `source` field. The question is whether they then
   become an implementation detail that should be closed off, and relatedly which
   code still needs to match across the whole hierarchy at all.

   Checked 2026-09-15: after `flatten` is deleted (decision 4) the only production
   match over the hierarchy is `descendantProvenances` at
   `CachedResultResolverLive.scala:201`. Note the naming trap — `RiskLeaf` and
   `RiskPortfolio` are **tree node** types and are unaffected by any of this;
   `RiskResult` and `RiskResultGroup` are the **computed value** types. Only the
   second pair is in question.

10. **Whether `ValuationResult` should be a subtype of `LossDistribution` at all,
    and whether its name carries its meaning.** A decorator that wraps a value of
    a type and is also a member of that type is a deliberate choice, not a
    necessity. The alternative is a separate type holding a `LossDistribution`,
    which would leave the sealed hierarchy at two members and remove every
    exhaustiveness consequence — at the cost that consumers must unwrap before
    reading a figure. The name is a second, independent question:
    `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md` §8.2 records only why it is
    *not* called `MitigatedResult`, and does not argue that "valuation" is the
    right word against the vocabulary the rest of the domain uses.

#### 7.6.13 File inventory — slices 1 to 5

These lines append to the shared `## File inventory` section on approval. Files
already listed there for M1, M1R, M2 or M3 are repeated only where this plan
edits them again; the hook matches on presence, so a repeat is harmless and an
omission is a denial.

```
- `modules/common/src/main/scala/com/risquanter/register/http/requests/AnalysisRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/MitigationRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeUpdateRequest.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/responses/AnalysisResponses.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/responses/TreeStructureResponse.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceTreeEndpoints.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/requests/MitigationSelectionRequestSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/requests/MitigationRequestsSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeBoundsSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationEntitySpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverRegistry.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala`
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/AggregateFreshnessAfterLeafMoveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/app/src/main/scala/app/state/LECChartState.scala`
- `modules/app/src/main/scala/app/state/TreeViewState.scala`
- `modules/app/src/test/scala/app/state/LECChartStateSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala`
```

Two documents change outside the gated tree and are listed for scope, not for
the hook: `docs/dev/decision-records/ADR-017-tree-api-design.md` (amended) and
`docs/dev/decision-records/ADR-037-analysis-read-contract.md` (new).

Why each non-obvious entry is here. `QueryServiceLive.scala` loses its private
resolution-failure logger to the shared one. `CascadeTestStubs.scala` is the
only other implementation of `RiskTreeService`, so every signature change
reaches it. `AggregateFreshnessAfterLeafMoveSpec`, `SeedStabilitySpec`,
`RiskTreeControllerSpec`, `RouteSecurityRegressionSpec`,
`WorkspaceLifecycleControllerSpec`, `HttpTestHarness` and `StubHttpTestHarness`
each build `RiskTreeServiceLive.layer`, which now also requires
`MitigationScopeResolverRegistry`. `TreeViewState.scala` and `LECChartState.scala` are the
two browser call sites of the endpoints whose signatures change, and without
them the Scala.js module does not compile.

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

**The browser change-notification consumer is part of that scope** (Decision 1).
The server publishes node invalidations and, after M4, mitigation-driven ones
too; nothing in the single-page app listens, so every publish reaches zero
subscribers. The consumer belongs here rather than in M4 because its core
operation is deciding which cached curves to discard for a given set of affected
nodes, and the shape of that client-side state is fixed by M4's response and
Compare-slot decisions. Building it before those land would target a layout about
to change. It is the same question as the three surfaces above, asked of a
fourth, and it takes the same input.

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
| 034 (mitigation valuation model) | The whole plan | Compliant: `raw` is the cached mitigation-free fold, `mitigated` is derived at the read edge and never stored; the Option F portfolio arm is implemented in `CachedResultResolverLive` |
| 035 (error leakage prevention) | M4 endpoints | Deferred to §7.6: mitigation scope-resolution failures are server-side drift signals (logged, per-mitigation no-op) and must not be echoed to the client; any new error variant needs its sanitisation clause on the sealed hierarchy |
| 036 (confidential internal identifiers) | M4 request/response shapes | Deferred to §7.6: `WorkspaceId` and `BranchRef` must not cross the client boundary in either direction. `CommitHash` is **not** in this category — it is already client input (`?at=`, `revertTree`) and already returned in `TreeHistoryEntry`, so returning it from a service read introduces no new exposure |
| INFRA-006 | — | No bearing (DB credentials) |

## File inventory

The file inventory lives in its own document, `PLAN-RISKTRANSFORM-INVENTORY.md`,
which the approval hook reads and only the user writes.

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
  *    fragment membership, §8.4-3)
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
of historic-revision entries: a capacity-2 slot per (tree, branch), ruled
(§8.4-5).

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
   targeting fragment spec.** Targeting ranges over the node sort only and
   mitigation-state predicates are permanently barred (§6), so there is no
   sort to quantify over — the exclusion costs no expressiveness. A future
   asset-graph sort that were both quantifiable and legal in targeting would
   reopen this — asset-graph-epic scope (TODO §45), not M3. No open
   sub-questions remain.
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
   resolve uncached. **Amended (user, 2026-09-13): capacity 2.** The slot
   holds at most two revisions and evicts the least recently used, so a
   pinned historic read takes the second position and the head entry
   survives. This keeps the bound structural — twice a constant rather
   than a constant — while removing the one cost head-only carried: a
   caller alternating between the head and a pinned revision missed on
   every call and gained nothing from the memo. The amendment preserves
   the reasoning below unchanged; only the claim that a single slot was
   the sole bounded option was too strong. Cost analysis behind the ruling: at realistic tree
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
   is a revision-checked, capacity-2 slot per (tree, branch), not an
   evicting map — no EvictionStrategy, no generic-cache extraction from
   ContentCache (design + `CacheStats` reuse only). The adaptive
   alternative — the composite key `(TreeId, BranchRef, CommitHash)` plus
   an eviction policy, which carries that extraction with it — remains
   recorded as TODO 49 with its trigger criteria, and the project's
   general preference for bounds by construction over eviction policies
   is TODO 52.

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
`FolQueryFailureFromQueryErrorSpec` drops the 10 matching cases. Landed in the
same 0.10.18 PATCH as Step B. `FolUnknownSymbol` was later retired entirely
(Option D, PLAN-ERROR-REFACTORING §13): with no producer it was dead
end-to-end, so the type and its `UNKNOWN_SYMBOL` wire code were removed.

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
  typed bind's predicate-argument sort check). Early authoring-time feedback, if wanted, is an M3
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
  version identity `(WorkspaceId, TreeId, BranchRef, CommitHash)` — held in a
  capacity-2 slot per (tree, branch), per §8.4-5. The resolved `Map[MitigationId, Set[NodeId]]` is consumed at the
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

#### Outcome (2026-08-31) — spun-off cleanup completed; vql pin advanced to 0.17.0

The cleanup §8.11 deferred to PLAN-ERROR-REFACTORING §11 has landed, and that
plan is closed (archived at `docs/archive/plans/DONE-PLAN-ERROR-REFACTORING.md`).
Verified against the code at register `0.10.28`:

- **Sibling `decode` arms brought onto the message slot (that plan §13,
  Option D).** `FolUnknownSymbol` is retired entirely — no producer, no type,
  no `UNKNOWN_SYMBOL` wire code remain (zero source references). It was dead
  end-to-end once vql-engine 0.14.0 pruned its producing variant.
  `FolDomainNotQuantifiable` is folded from `(typeName, availableTypes)` to a
  single `message: String` rendered at the `fromQueryError` boundary in
  `AppError.scala`. Every FOL `decode` arm now reads the per-detail message
  slot; the §5 `Diagnostic` wire redesign was **not** adopted.
- **`encode`/`classify` exhaustiveness compile-enforced (that plan §12).**
  `ErrorResponse.encode` dispatches through `encodeAppError` and
  `GlobalError.fromThrowable` through `fromAppError`, both matching on the
  sealed `AppError`, so a missing sub-trait is a compile error rather than a
  silent fall-through.
- **vql-engine pin `0.16.0` → `0.17.0` (that plan §14).** `build.sbt`
  `vqlEngineVersion` is `0.17.0`. 0.17.0 reshapes `BindErrorDetail` into an
  eleven-case `enum` with **no `Other`** — superseding the two-case
  `UnparseableConstant(name, sortName, sourceText, rendered)` + `Other(rendered)`
  shape recorded in the "Engine facts" note above. `UnparseableConstant` keeps
  its four fields and `BindError.messages` is still `details.map(_.rendered)`,
  so the reshape required **no change** to the §8.11 bind-error classifier: it
  still matches `UnparseableConstant` and folds every other detail through
  `case _ => false`. Only `FolQueryFailureFromQueryErrorSpec` fixture
  constructors that had built `BindErrorDetail.Other` moved to real variants
  (`ArityMismatch`, `UnknownPredicate`, `UnknownFunction`); no assertion
  changed. 0.17.0 is `publishLocal`-only, so the four sbt tiers resolve and
  pass against it, but the Docker/GraalVM image and BATS builds cannot resolve
  it until it reaches Maven Central — a vql-engine coordination dependency, not
  a register code gap.

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
  * a field: one resolver instance exists per workspace (`MitigationScopeResolverRegistry`, the
  * DD-17 `ContentCacheRegistry` precedent), so the workspace IS the instance and the memo
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

/** Memoizes the resolved scopes of one tree version. One entry per
  * (treeId, branch) holds at most two revisions and their scopes, evicting the
  * least recently used, so revisions never accumulate and a pinned historic read
  * cannot displace the head. The memo read and write are not atomic
  * (last-writer-wins) — see "Memo write policy". In-memory `Ref` → `UIO`. One
  * instance per workspace (`MitigationScopeResolverRegistry`).
  *
  * The bound is structural rather than a policy. An eviction strategy could have
  * served here, and one already exists for `ContentCache`; reusing it would mean
  * extracting a generic bounded cache, a refactoring whose cost outweighed the
  * return at two entries per slot. Open to reconsideration if a caller ever
  * alternates across more than two revisions. */
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

#### Per-workspace registry — `MitigationScopeResolverRegistry.scala` (mirrors `ContentCacheRegistry`)

```scala
package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, BranchRef, CommitHash}

/** Per-workspace `MitigationScopeResolver` resolution (§8.1 cache-identity, the
  * DD-17 `ContentCacheRegistry` precedent). One resolver instance per workspace makes
  * cross-workspace scope contamination structurally impossible; the memo key
  * inside each instance is (treeId, branch, revision). */
trait MitigationScopeResolverRegistry:
  def forWorkspace(workspaceId: WorkspaceId): UIO[MitigationScopeResolver]

object MitigationScopeResolverRegistry:
  val layer: ZLayer[Any, Nothing, MitigationScopeResolverRegistry] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[WorkspaceId, MitigationScopeResolver]).map(MitigationScopeResolverRegistryLive(_))
    )

  def forWorkspace(workspaceId: WorkspaceId): URIO[MitigationScopeResolverRegistry, MitigationScopeResolver] =
    ZIO.serviceWithZIO[MitigationScopeResolverRegistry](_.forWorkspace(workspaceId))

final case class MitigationScopeResolverRegistryLive(
  resolvers: Ref[Map[WorkspaceId, MitigationScopeResolver]]
) extends MitigationScopeResolverRegistry:
  override def forWorkspace(workspaceId: WorkspaceId): UIO[MitigationScopeResolver] =
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
   `ContentCacheRegistry` keys by `SeedEntityId` because simulation figures depend on the
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
  one winner via atomic `Ref.modify` (the `ContentCacheRegistry` pattern). The per-instance
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
  second `revision` recomputes and **both** entries are then live, so alternating
  between the two hits every time; a call at a third `revision` evicts the least
  recently used, leaving the slot at two.
- **per-workspace isolation:** `MitigationScopeResolverRegistry.forWorkspace` returns the same
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
  `AggregateFreshnessAfterLeafMoveSpec.scala`, `SeedStabilitySpec.scala`, `ProvenanceSpec.scala`,
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
cached, content-addressed value (ADR-034 Decision 3); `RiskResultGroup` keeps its
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

**The deferred return shape — RULED 2026-09-14, see §8.16.** This paragraph
originally left "the exact `CachedResultResolverLive` return shape that carries
the mitigated aggregate alongside the cached raw value" to the code step's
Signature Echo. That question is now ruled: the mitigated fold returns a
`ValuationResult` at every node it visits. §8.16 records the ruling and its
consequences, and
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md)
carries the derivation behind it. Read that document before re-opening any part
of this ruling. What remains a code-step choice is only whether the edge runs the
mitigated fold as a second traversal or threads a pair through the existing one —
an internal recursion question that changes no `common` wire type.

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
  `RiskResultGroup` stays private (ADR-034 Decision 3). No `common` domain-type
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
  - compositional fold (ADR-034 Decision 2): a `ResultStage` on a child *and* on
    its ancestor compose by tree position — the ancestor's transform sees the
    child's already-mitigated total — and the result is independent of the order
    the two mitigations were authored.
- **Bump:** PATCH on landing; mirror `APP_VERSION` into `.env` and `.env.irmin`.

#### File inventory (delta)

Rename ripple — **add** (not currently listed; hook would otherwise deny):

- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/AggregateFreshnessAfterLeafMoveSpec.scala`
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

### 8.15 M2 slice 4 — override staleness detection + mitigation persistence verification — implementation-grade (2026-08-29)

Fourth and closing slice of M2. Two parts of unequal weight, both closing
open M2 scope (§7.2.2a, §7.2.3):

- **Override staleness detection (the substance, new production code).** The
  server-side diagnostic predicate `MitigationStaleness.staleOverrides` — OD-6,
  already ruled (placement + signature) at `## File inventory` → Open decisions.
- **Mitigation persistence — production + tests (Option A, ruled 2026-08-29).**
  The original premise that mitigations already round-trip through Irmin was
  **false**. `RiskTreeRepositoryIrmin.writeTree` persisted only `meta` +
  `nodes/{id}`, and `rebuildTree` reconstructed the tree without mitigations, so
  every override and its base stamp was silently dropped on reload — the whole
  staleness feature was inert in production. The merge conflict scan
  (`ScenarioMergeService.pathsOn`) likewise never enumerated a `mitigations/`
  subtree, so the same mitigation edited on two branches would last-writer-win
  on merge instead of conflicting. This slice now implements the §7.2.1 storage
  mapping (mitigations as per-id blobs beside nodes), extends the byte-level
  merge scan over them, and proves both against real Irmin with serverIt tests.
  This realizes §7.2.1/§7.2.3 scope that was designed but never built; the
  "verification surfaced a real gap" halt (G2/G7) fired and was escalated as
  this decision, not silently grown into the slice.

Stamp *writing* stays out of this slice: `overrideBaseStamp = ContentHashIndex.
hashOf(targetLeaf)` is computed on the tree-PUT path, which M4 wires. M2 delivers
the read-side predicate and its tests only (§7.2.2a).

#### Part 1 — what staleness means, from first principles

A `LeafStage` mitigation with an Override component (`LikelihoodTransform.Override`
or `DistributionTransform.Override`) asserts a fixed probability/distribution
against a *specific base state* of one leaf. `Mitigation.create` already enforces
that such a mitigation carries both `overrideBaseStamp` (the `ContentHash` of that
leaf's `LeafSimContent` at authoring time — the DD-16 simulation-relevant
projection) and `overrideAnchor` (the `NodeId` of the leaf it asserts against).

An override is **stale** when the anchor leaf's *current* `LeafSimContent` hash no
longer equals the stored stamp — the base the frozen assertion was written against
has moved. Because the stamp is a hash of `LeafSimContent` (probability, loss
distribution parameters — **not** `name` or `parentId`), a rename or reparent
leaves the hash unchanged and therefore does **not** make an override stale
(DD-16 projection); only a change to a simulation-relevant field does. Staleness
is purely diagnostic: resolution ignores it (frozen expert opinion is the ruled
semantics, OD-6 reason 3), and the predicate participates in no monoid-action law
— it is a total function `RiskTree → Set[MitigationId]`.

#### Contract — `MitigationStaleness.scala` (determined; OD-6 ruled)

```scala
// server — services/cache/MitigationStaleness.scala
package com.risquanter.register.services.cache

import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, Mitigation, MitigationSpec}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, ContentHash}

/** Staleness layer 1: overrides whose stored base stamp no longer matches the
  * anchor leaf's current LeafSimContent hash. Fires on any edit path (form,
  * merge, API PUT, time-travel revert) that changes a simulation-relevant leaf
  * field; renames/reparents do not fire (DD-16 projection — the stamp hashes
  * LeafSimContent, which excludes name and parentId). Diagnostic only:
  * resolution ignores staleness (frozen expert opinion is the ruled semantics).
  * The sole consumers are HTTP handlers that put `staleMitigationIds` into
  * read/update response payloads (M4); the client renders, never computes. */
object MitigationStaleness:

  def staleOverrides(tree: RiskTree): Set[MitigationId] =
    tree.mitigations.iterator.collect {
      case m @ Mitigation(_, _, _, MitigationSpec.LeafStage(_, Some(stamp), Some(anchor)), _)
          if isStale(tree, anchor, stamp) =>
        m.id
    }.toSet

  private def isStale(tree: RiskTree, anchor: NodeId, stamp: ContentHash): Boolean =
    tree.index.nodes.get(anchor) match
      case Some(leaf: RiskLeaf) => ContentHashIndex.hashOf(leaf) != stamp
      case _                    => true   // anchor gone / no longer a leaf → stale (OD-8 Option A)
```

Why the pattern selects exactly the right mitigations, with no separate filter:
`Mitigation.create`'s cross-field rule makes `overrideBaseStamp` present **iff**
the leaf transform has an Override component, so `LeafStage(_, Some(stamp),
Some(anchor))` matches every Override leaf-stage mitigation and nothing else — a
non-Override `LeafStage` carries `None` and fails the match; a `ResultStage`
carries no stamp and fails the match. The predicate reuses `ContentHashIndex.
hashOf` (the same JVM SHA-256 over `LeafSimContent.from(leaf).toJson` that keys
the cache) as the single hash code path — OD-6 reason 1's single-producer
invariant is preserved: this adds a *reader* of that hash, not a second producer.

`staleOverrides` needs only the tree, not resolved scopes: the leaf an override
asserts against is the stored `overrideAnchor`, not the mitigation's resolved
targeting scope, so no `MitigationScopeResolver` output is required.

#### Part 2 — mitigation persistence (production) + verification

Mitigations are stored as one Irmin blob per mitigation under
`.../mitigations/{mitigationId}`, mirroring the per-node convention (§7.2.1).
DD-7 whole-subtree replacement keeps holding: an omitted mitigation is deleted
by the `set_tree` that omits it, exactly as for nodes. Storage is
byte-for-byte the existing `Mitigation` JSON codec (the same one
`MitigationEntitySpec` round-trips), so disjoint mitigation edits auto-merge and
same-mitigation edits fall under the existing byte-level pre-check (ADR-032) —
no new merge machinery.

**Storage path helper — `WorkspaceStoragePaths.scala`:**

```scala
def treeMitigations(wsId: WorkspaceId, treeId: TreeId): String =
  s"${treeRoot(wsId, treeId)}/mitigations"
```

**Write + read — `RiskTreeRepositoryIrmin.scala`.** `writeTree` gains a
`mitigations` operand and emits one entry per mitigation beside meta + nodes;
`create` / `update` / `revert` pass `riskTree.mitigations` /
`updatedTree.mitigations` / `existing.tree.mitigations`. The read path reads the
`mitigations/*` prefix at the pinned commit and passes the decoded list into
`RiskTree.fromNodes`, so every tree invariant (duplicate-id, name-uniqueness,
count bound) is re-checked on load. Exact signatures:

```scala
private def writeTree(base: IrminPath, meta: TreeMetadata, nodes: Seq[RiskNode],
                      mitigations: Seq[Mitigation], message: String, branch: BranchRef): Task[Unit] =
  val entries =
    IrminTreeEntry(IrminPath.unsafeFrom("meta"), meta.toJson) ::
      nodes.toList.map(node => IrminTreeEntry(IrminPath.unsafeFrom(s"nodes/${node.id.value}"), nodeJson(node))) :::
      mitigations.toList.map(m => IrminTreeEntry(IrminPath.unsafeFrom(s"mitigations/${m.id.value}"), m.toJson))
  handleIrmin(irmin.setTree(base, entries, message, branch)).unit

private def readMitigationsAt(prefix: IrminPath, at: CommitHash): Task[Seq[Mitigation]] =
  for
    childNames <- handleIrmin(irmin.listAtCommit(at, prefix))
    mits       <- ZIO.foreach(childNames) { child =>
                    val fullPath = IrminPath.unsafeFrom(s"${prefix.value}/${child.value}")
                    handleIrmin(irmin.getAtCommit(at, fullPath)).flatMap {
                      case Some(json) => decodeMitigation(child, json)
                      case None       => ZIO.fail(RepositoryFailure(s"Missing mitigation value at ${fullPath.value}"))
                    }
                  }
  yield mits

private def decodeMitigation(child: IrminPath, json: String): Task[Mitigation] =
  ZIO.fromEither(json.fromJson[Mitigation].left.map(err => RepositoryFailure(s"Decode mitigation ${child.value}: $err")))

private def rebuildTree(meta: TreeMetadata, nodes: Seq[RiskNode], mitigations: Seq[Mitigation]): Task[RiskTree] =
  // unchanged empty-nodes guard, then:
  //   RiskTree.fromNodes(meta.id, meta.name, nodes, meta.rootId, Some(meta.seedVarHighWater), mitigations.toList)
```

In `loadTreeAt`, alongside `nodePrefix`, read
`val mitPrefix = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeMitigations(wsId, id))`
and thread `readMitigationsAt(mitPrefix, at)` into `rebuildTree`. Mitigations
may legitimately be empty (a tree with no mitigations), so — unlike nodes —
there is no non-empty guard on them.

**Merge scan — `ScenarioMergeService.scala`.** `pathsOn` enumerates the
`mitigations/` subtree in the same pass as `nodes/`, so a mitigation that
changed on both branches enters the byte-level `findConflicts` comparison;
`MergeConflictPath.fromRelativePath` gains a case for the mitigation path shape:

```scala
// pathsOn: per tree, zipPar the nodes and mitigations listings
irmin.list(IrminPath.unsafeFrom(s"$base/nodes"), branch)
  .zipPar(irmin.list(IrminPath.unsafeFrom(s"$base/mitigations"), branch))
  .map { case (nodes, mits) =>
    s"risk-trees/${treeId.value}/meta" ::
      nodes.map(n => s"risk-trees/${treeId.value}/nodes/${n.value}") :::
      mits.map(m => s"risk-trees/${treeId.value}/mitigations/${m.value}")
  }

// MergeConflictPath.fromRelativePath: new case (OD-9 Option A — no wire field)
case "risk-trees" :: treeId :: "mitigations" :: _ :: Nil =>
  MergeConflictPath(rel, TreeId.fromString(treeId).toOption, None)
```

Enumerating `mitigations/` in the scan is unconditional — it is the correctness
fix (without it, same-mitigation merges silently last-writer-win). How a
mitigation conflict is *structured for the UI* is OD-9 below.

**Verification (serverIt).** Round-trip cases go in the new
`MitigationPersistenceItSpec`; merge cases extend `ScenarioMergeServiceItSpec`
(reusing its branch/scenario harness, mirroring its node-conflict tests):

- round-trip: a tree created with an override reads back with the override and
  its `overrideBaseStamp` intact (this is the assertion that fails against
  today's code).
- update: replacing the mitigation list persists; a tree written without a
  previously-stored mitigation reads back without it (omitted = deleted).
- merge, disjoint: two branches editing *different* mitigations preview `Clean`
  and merge folds both.
- merge, conflicting: two branches editing the *same* mitigation preview
  `Conflicts` naming that mitigation's path, and `merge` refuses with
  `MergeConflict` (byte-level pre-check, ADR-032), main untouched.

#### ADR alignment

- **OD-6** (staleness placement, ruled 2026-08-09): server-side
  `MitigationStaleness`, original signature unchanged. Compliant — this slice
  realizes exactly that ruling.
- **ADR-010** (errors are values): `staleOverrides` is total — a pure `RiskTree
  → Set[MitigationId]` with no failure channel and no exception (it never
  constructs a hash from unvalidated input; `hashOf` operates on an
  already-validated `RiskLeaf`). Compliant; no typed error added.
- **DD-14 / DD-16**: reuses `ContentHashIndex.hashOf` (JVM SHA-256 over the
  `LeafSimContent` projection); rename/reparent invariance is inherited, not
  re-implemented. Compliant.
- **ADR-030** (orchestration boundary): the only caller is a server HTTP handler
  (M4); the domain/`common` layer gains nothing. Compliant.
- **ADR-004a** (workspace storage layout): the mitigation path
  `.../mitigations/{mitigationId}` extends the existing per-node mapping through
  the single owner `WorkspaceStoragePaths`. Compliant — one new helper, no other
  construction site of the path.
- **DD-7** (one commit per user action): mitigations ride the same `set_tree`
  as meta + nodes, so create/update/revert stay exactly one commit; omitted
  mitigation = deleted by subtree replacement, identical to node semantics.
  Compliant.
- **ADR-032** (typed merge conflict): Part 2 brings mitigation paths into the
  byte-level pre-check (`pathsOn`) so same-mitigation edits conflict instead of
  last-writer-winning; the comparison and typed-conflict machinery are unchanged.
  Behaviour change to the scan's candidate set, ruled in (Option A) and tested.
- **Correct-by-construction**: the read path routes loaded mitigations through
  `RiskTree.fromNodes`, so tree-level mitigation invariants (duplicate-id,
  name-uniqueness, count bound) are re-validated on load; no raw primitive
  crosses a service/repository boundary (`staleOverrides` input is an
  already-validated `RiskTree`; output is domain `MitigationId`s).

#### Open decisions

**OD-8 — anchor no longer resolves to a leaf. ✅ RULED (2026-08-29, Option A).**
The anchor `NodeId` is stable
across rename and reparent (a node's id does not change), so in normal editing it
still names its original leaf. It stops resolving to a `RiskLeaf` only if that
leaf is **deleted** (the id assignment is server-side and effectively
non-reusable, so "the id now names a portfolio" is a defensive-only branch). The
question: does `staleOverrides` report a mitigation whose anchor leaf is gone?

- **Option A — report as stale (fold "orphaned" into "stale"):** `isStale`
  returns `true` for a missing/non-leaf anchor (the code above). One diagnostic
  set in the payload; an override whose base leaf was deleted is surfaced for the
  user's review alongside one whose base changed. Con: conflates "base changed"
  with "base gone" — but the payload is a review flag, and both mean "this
  override needs your attention."
  - *Concrete case:* you author an override on leaf "cyber", later delete
    "cyber"; the mitigation still lists in the tree but targets nothing. Its id
    appears in `staleMitigationIds`, so the UI flags it for review.
- **Option B — report only a genuine hash mismatch of an existing leaf:**
  `isStale` returns `false` (or skips) when the anchor is absent; staleness means
  strictly "the stamp no longer matches an existing leaf." A deleted anchor is a
  *different* diagnostic ("orphaned") that a later slice/handler adds separately.
  Con: an override pointing at a deleted leaf is silently absent from this
  function's output for M2.
  - *Concrete case:* same deletion of "cyber" → the mitigation's id does **not**
    appear in `staleMitigationIds`; the UI shows nothing until an orphaned-override
    diagnostic is added later.

**My recommendation: Option A.** The function answers "which overrides need the
user's attention"; an override whose base leaf is gone needs attention as much as
one whose base moved, and folding it in avoids shipping a second near-identical
id-set in the M2 payload. A dedicated "orphaned" classification can split out
later if M4/M5 shows the UI needs to distinguish the two states. The one-line
`case _ => true` above encodes A; choosing B changes it to match `Some(leaf:
RiskLeaf)` only. Part 1 is implemented on Option A and green.

**OD-9 — how a mitigation merge conflict is structured for the client. ✅ RULED
(2026-08-29, Option A).** The
merge-preview response carries, per conflicting path, a `MergeConflictEntry(path,
treeId, nodeId)` (wire form of the internal `MergeConflictPath`). A node conflict
fills `treeId` + `nodeId`; a `meta` conflict fills `treeId` only. A mitigation
conflict is a new path shape. Enumerating `mitigations/` in the scan is settled
(correctness, above); the open point is only the *structured coordinate*:

- **Option A — no wire change (recommended):** parse the mitigation path to
  `MergeConflictPath(rel, Some(treeId), None)`; the raw `path` string
  (`.../mitigations/{id}`) is what distinguishes it from a `meta` conflict. No
  change to `MergeConflictEntry`, `MergePreviewResponse`, or the controller
  mapping — no Decision Trigger #1 wire change. The M2 correctness fix lands
  without touching the response DTO.
  - *Concrete case:* the preview for a same-mitigation conflict returns
    `{"path":"risk-trees/t1/mitigations/m1","treeId":"t1","nodeId":null}`; the
    client reads the `mitigations` segment from `path` to label it.
- **Option B — typed field now (Decision Trigger #1):** add
  `mitigationId: Option[MitigationId]` to `MergeConflictPath` and
  `mitigationId: Option[String]` to the wire `MergeConflictEntry`, and populate
  it in `toPreviewResponse`. The client gets a typed coordinate without parsing
  the path. Cost: a response-DTO field added before any consumer renders it
  (the UI is M4), and it pulls `ScenarioMergeResponse.scala` +
  `ScenarioController.scala` into this slice's inventory.
  - *Concrete case:* the same conflict returns an extra
    `"mitigationId":"m1"` field the M2 client does not yet read.

**My recommendation: Option A.** No consumer exists until M4 renders the
conflict list, so adding a wire field now has no standalone benefit (phase
routing) and speculatively widens a response contract; the `path` string already
carries the mitigation id unambiguously. If M4's UI turns out to want a typed
coordinate, it adds the field in the same slice that renders it. Either way the
correctness fix (scan covers `mitigations/`) is unconditional and identical.

#### Verification plan

- **`MitigationStalenessSpec`** (`server/test`, pure — no Docker):
  - an Override whose anchor leaf's probability (or loss distribution) changed →
    reported stale.
  - the same Override after the leaf is renamed or reparented (no
    simulation-relevant field changed) → **not** reported (DD-16).
  - re-stamping to the current hash clears the report.
  - a non-Override `LeafStage` mitigation and a `ResultStage` mitigation → never
    reported.
  - anchor-leaf deletion → reported (OD-8 Option A; flips with the ruling).
  - resolution output (`effectiveTree` / `resultTransformFor`) is identical with
    and without stale overrides present — staleness never feeds resolution.
- **`MitigationPersistenceItSpec`** (`serverIt/test`, needs
  `local/irmin-prod:3.11-p1`; clear leaked `register_it_` networks first): the
  round-trip cases in Part 2 (create→read with override + stamp intact; update
  replaces the mitigation list; omitted mitigation deleted).
- **`ScenarioMergeServiceItSpec`** (`serverIt/test`, same prerequisites): the two
  merge cases in Part 2 (disjoint mitigation edits → `Clean` + merge folds both;
  same mitigation edited on both sides → `Conflicts` naming the mitigation path +
  `merge` refuses, main untouched), mirroring its existing node-conflict tests.
- **Whole suite green** closes the slice: `sbt 'commonJVM/test; server/test'`,
  `sbt app/test`, `sbt serverIt/test`; BATS suite-C fast gate after the code
  change.
- **Bump:** PATCH on landing (phase step, not plan close) — `0.10.25 → 0.10.26`;
  mirror `APP_VERSION` into `.env` and `.env.irmin`.

#### File inventory (delta)

Part 1's files and Part 2's `MitigationPersistenceItSpec` were already listed;
Option A (2026-08-29) adds the two production/test files the merge-scan change
needs. Files this slice writes:

Already in `## File inventory` (no add):
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala` (Part 1 production)
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala` (Part 1 spec)
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/WorkspaceStoragePaths.scala` (Part 2 — `treeMitigations`)
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala` (Part 2 — write/read mitigations)
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala` (Part 2 — round-trip)
- `build.sbt` (PATCH bump 0.10.25 → 0.10.26)

Added to `## File inventory` by this slice (Option A):
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala` (Part 2 — `pathsOn` + `fromRelativePath` mitigation shape)
- `modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala` (Part 2 — merge cases)

`RiskTreeRepositoryInMemory` stores whole `RiskTree` values and needs no change;
it is already in the inventory (§7.2.1) in case compilation surfaces one. OD-9
Option B would additionally add `ScenarioMergeResponse.scala` and
`ScenarioController.scala`; those are **not** in the inventory under the
recommended Option A and would be added only if B is ruled. The token stays
pointed at this plan; the user re-points it after approving this amendment so
the two new production/test paths are covered.

### 8.16 The mitigated value's type — `ValuationResult` (RULED 2026-09-14)

**Reasoning source.** Every claim in this section is derived in
[`docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`](../../scratch/MITIGATION-VALUATION-EXPLAINED.md),
which builds the vocabulary, the raw fold, the obstruction, the Option F ruling
and each consequence in dependency order. Where any section of this plan is
ambiguous about the mitigated value's type, the two folds, the identity case or
`flatten`, that document settles it. Do not re-decide any of it from this summary
alone.

#### What was missing

§8.14 ruled the fold — `mitigated(P) = f_P(⊕ mitigated(children))` — and left the
return shape to the code step. The code step chose a flat `RiskResult` at a
transformed portfolio, which is correct arithmetic, and the test suite pins it.
But the returned type said three true things and omitted three others: it did not
say which valuation the caller was holding, it did not say which mitigations had
been applied to produce it, and it did not carry the value the transform was
applied to. A caller holding the result could not tell a mitigated reading from a
raw one without remembering what it had passed in.

#### The ruling

**1. The mitigated fold returns a decorator named `ValuationResult`.** It records
the node, the value the transform layer was applied to, which mitigation
applications produced the layer, and the resulting outcomes:

```scala
final case class ValuationResult private (
  override val nodeId: NodeId,
  source: LossDistribution,
  applied: List[MitigationApplicationRecord],
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes)
```

`source` is the value this node's own transform layer was applied to. At a
portfolio that is the combine of the children's **mitigated** values, built as a
true `RiskResultGroup` whose aggregate really is the combine of exactly those
children, so the children remain reachable through it and ADR-009's drill-down
structure is preserved rather than discarded. At a leaf it is the cached raw
`RiskResult`.

`source` is **not** the raw value at that node. The two differ at every node that
has a transformed descendant: in the reasoning document's worked example the node
`Servers` has a raw figure of 23 and a `source` of 20, the 3 being a leaf cap
below it. The difference between this node's `trialOutcomes` and
`source.trialOutcomes` is therefore exactly this node's own layer and nothing
else, which is the property the decorator exists to provide.

**2. Wrapping is uniform: every node the mitigated fold visits is wrapped.** The
empty `applied` list is the identity, so a node with no mitigation in scope is
wrapped with an empty list rather than left bare.

Uniform means uniform in both directions, and the second one is easy to miss.
Wrapping does not depend on whether a transform binds at the node, **and it does
not depend on which `MitigationSelection` the caller passed.** There is one fold
at the read edge; a raw reading is that fold run with `Inherent`, which feeds the
identity transform everywhere. So every value the resolver returns is a
`ValuationResult`, for every selection, including the mitigation-free one. There
is no undecorated return path.

What stays undecorated is everything *inside*: `source` is a plain `RiskResult`
at a leaf and a plain `RiskResultGroup` at a portfolio, and the cache below it
stores identity-free leaf content that the decorator never reaches. This was ruled in preference to
wrapping only where a transform binds. The reason is that a no-op mitigation is
authorable today — `ScaleLosses(1.0)`, `ApplyDeductible(0)` and
`FilterBelowThreshold(0)` are all valid single-step pipelines — so a rule of
"wrap only when something changed" would make the return type depend on the
numeric value of a parameter rather than on the shape of the request.

**3. One method, not two.** `ensureCached` keeps its single form and its
`selection` parameter. A two-method form — a separate `resolveRaw` beside a
`resolveMitigated` — was proposed and withdrawn: the ADR-034 passage cited for it
is about what is *stored*, and Form 3 satisfies that passage identically.

A raw reading is the identity instance of the same fold: the same method called
with `MitigationSelection.Inherent`, which feeds the identity transform at every
node, so every `applied` list comes back empty and every `trialOutcomes` equals
its `source`. It is **not** read off a mitigated result's `source`, because
`source` carries the combine of the mitigated children and equals the raw
aggregate only where no descendant transforms.

**One method does not mean one call.** A read that must show both valuations
resolves twice — once with `Inherent` and once with the caller's selection — and
feeds both into one curve generation so the two series share a tick domain. That
is what §7.6.5's two `ensureCachedAll` calls are, and the reasoning document's
Part 9 states it in those terms. The second resolution re-reads the same leaf
cache entries wherever no parameter-stage transform changed that leaf's content
hash, so its cost is the portfolio combines plus only the leaves whose simulation
inputs the selection actually rewrote. Whether the two resolutions could instead
be one traversal carrying a pair is the open question in §7.6.12 decision 3.

**4. The empty case must be physically the identity.** Applying an empty pipeline
must return the *same* `TrialOutcomes` reference it was given, not a structurally
equal rebuild. A rebuild would duplicate the outcome map of every untransformed
node on every read, which on a large tree is the dominant allocation.

**5. `MitigationApplicationRecord` becomes the `applied` field.** The type exists,
is tested, and today has no production caller. This ruling gives it its only home.

The existing `MitigationApplication.applicationRecords` is **not** the producer of
that field and cannot be reused unchanged. It answers a whole-tree question: it
groups by mitigation across the entire resolution and emits one record per
mitigation carrying the union of every node that mitigation touched, and it does
not filter by mitigation stage. `applied` answers a per-node question: the records
for the **result-stage** mitigations scoping *this* node, in precedence order.
Parameter-stage transforms are folded into the effective tree before the content
hash is computed, so they are not part of the layer this node applied and must not
appear in its `applied` list. A new per-node function is therefore needed beside
the existing one; the precedence ordering — by `(precedence.key, mitigationId.value)`
— is the one part that carries over unchanged. `applicationRecords` keeps its
current shape and its current tests.

This ruling closes the open
question of where the provenance layer lives and removes the contradiction
between §7.4's prose and the ruled response shape in §7.6.3 — the records live on
the server-side valuation, and the wire response continues to carry only
`withMitigations: List[MitigationId]`.

**6. `LossDistribution.flatten` is removed.** The abstract member and both
overrides go, together with the two `LossDistributionSpec` sites that exercise
them. It has no production caller and cannot acquire one: the expand-and-collapse
tree in the interface walks the persisted structure through `childIds`, the chart
receives a map from node id to curve, and the browser never holds a
`LossDistribution`. Keeping it would tax every future subtype — including
`ValuationResult` — with an override that nothing calls. A replacement shape was
considered and deliberately **not** ruled; it is recorded in §12.4 of the
reasoning document along with the three changes that would bring the need back.

#### Decision Trigger #8 — the test assertion this changes

`CachedResultResolverSpec` asserts that an un-mitigated portfolio read is a
`RiskResultGroup` and that a mitigated one is a flat `RiskResult`. Under uniform
wrapping both readings are `ValuationResult`s, so the assertions are rewritten to
test the property they were protecting rather than the type name: that the raw
structure is reachable and unchanged, and that the mitigated outcomes differ from
their source exactly where a transform binds. Rewriting them was approved under
Decision Trigger #8. This is the one place where the ruling changes a shipped
assertion; no other test in the suite asserts on these type names.

#### Constructing a `ValuationResult`

The primary constructor is private, so the companion object is the only place a
value can be built. That companion lives in `LossDistribution.scala` in the
`common` module, while the fold that builds the values runs in
`CachedResultResolverLive` in the `server` module, so a factory on the companion
is not optional — without one the type cannot be constructed at all from the
module that needs it.

The construction itself is otherwise mechanical. It is one function applied at
every node, with the node's children already resolved:

```
recordsFor(node)        = the MitigationApplicationRecords for the result-stage
                          mitigations scoping this node, in precedence order
run(records, outcomes)  = the composed transform applied to outcomes;
                          run(Nil, outcomes) returns outcomes itself

decorate(id, source, records) =
    ValuationResult.create(id, source, records, run(records, source.trialOutcomes))

m(leaf)         = decorate(leaf.id, cachedSimulation(effectiveLeaf), recordsFor(leaf))
m(portfolio P)  = decorate(P.id, RiskResultGroup.create(P.id, P.children.map(m)*),
                           recordsFor(P))
```

Two constraints on that sketch, both already ruled above. `run(Nil, outcomes)`
must return the same `TrialOutcomes` reference it was given (ruling 4). And the
group at a portfolio is built from the **mitigated** children, so its aggregate
claim stays true and `RiskResultGroup`'s private constructor needs no exception.

**The factory returns `Validation`, and one real failure reaches it.** This
mirrors `RiskResultGroup.create`, which exists because `TrialOutcomes.combine`
sums with `Math.addExact` and throws `ArithmeticException` on overflow, converted
there into a `ValidationError` (ADR-033 §3).

The transform path has one arithmetic hazard of its own, and today it does not
fail — it reports a wrong number. `RiskResultTransform.scaleLosses` computes
`(loss * factor).toLong`. Narrowing a `Double` that exceeds the `Long` range does
not throw; it saturates at `Long.MaxValue`. A scaling factor large enough to push
a loss past that range therefore produces `9223372036854775807` and presents it as
a real figure. The other three transforms are safe: `applyDeductible` subtracts
two non-negative values and floors at zero, `capLosses` takes a minimum, and
`insurancePolicy` composes those two.

**RULED (user, 2026-09-15): detect it and route it through `Validation`.** This
lands inside this sub-slice rather than being scheduled, because the conversion
site it needs is the factory this sub-slice introduces.

- `RiskResultTransform.scaleLosses` guards the narrowing and throws
  `ArithmeticException` when the scaled value is not representable as a `Long`,
  matching what `TrialOutcomes.combine` already does for addition. The guard is
  written so that a non-finite product fails the same way rather than silently
  becoming zero.
- `ValuationResult.create` catches `ArithmeticException` and returns
  `Validation.fail(ValidationError(...))` with
  `ValidationErrorCode.CONSTRAINT_VIOLATION`, exactly as `RiskResultGroup.create`
  does for the aggregation overflow.
- `RiskResultTransform` keeps its total shape, so `Identity[RiskResultTransform]`,
  `TransformPipeline.toTransform` and `MitigationApplication.resultTransformFor`
  are untouched. The failure is converted once, at the single public boundary.
- **Ordering constraint.** The `scaleLosses` guard must not land before
  `ValuationResult.create` exists. Until the factory is there, no caller catches
  the exception and it would escape the resolver as a defect.
- **Verification, required deliverable.** A test pins the new behaviour directly:
  a scaling factor large enough to push a loss past the `Long` range produces a
  `ValidationError`, not `Long.MaxValue`. A second test pins that an in-range
  scaling is unchanged, so the guard cannot be satisfied by rejecting everything.
  Both go in
  `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskResultTransformSpec.scala`,
  which the inventory already lists.

#### The sealed hierarchy gains a third case

`LossDistribution` is sealed, which means the compiler knows its complete list of
subtypes and can check that a `match` over it covers every one. `build.sbt` sets
`-Wconf:msg=match may not be exhaustive:error`, so a `match` that misses a case is
a build failure rather than a warning. Adding `ValuationResult` therefore breaks
every exhaustive match over the hierarchy until each is extended.

There is exactly one such match in production code, the resolver's provenance
walk at `CachedResultResolverLive.scala:201`:

```scala
private def descendantProvenances(dist: LossDistribution): List[NodeProvenance] =
  dist match {
    case r: RiskResult      => r.provenances
    case g: RiskResultGroup => g.children.flatMap(descendantProvenances)
  }
```

The branch to add is:

```scala
    case v: ValuationResult => descendantProvenances(v.source)
```

**The elevation must state why that branch is correct, not merely that it
compiles.** The argument it has to make, and which the implementation must be
checked against, is this. ADR-003 Decision 4 requires that a portfolio's provenance be
"the union of all leaf provenances in its subtree, in child order". Under uniform
wrapping every value the mitigated fold returns is a `ValuationResult`, so a
portfolio's children are all wrappers and the existing `RiskResultGroup` branch
would recurse into values that match neither existing case. Descending through
`source` restores the walk exactly: a wrapper over a leaf has the cached
`RiskResult` as its `source`, which carries that leaf's records; a wrapper over a
portfolio has a `RiskResultGroup` as its `source`, whose children are the wrapped
children, so the recursion continues one level down per node and terminates at the
leaves. Child order is preserved because `RiskResultGroup` retains its children in
the order the fold produced them, which `ZIO.foreachPar` already fixes to
`childIds` order. Descending through `source` rather than skipping the wrapper is
what keeps the result a union over leaves; returning `Nil` for a wrapper would
silently empty the provenance of every mitigated portfolio, and that failure would
be invisible because no test asserts on a mitigated portfolio's provenance today.

The elevation must also record the two consequences that follow from this branch
existing:

- The collapsed-transformed-portfolio case disappears. Today a portfolio whose
  transform binds returns a flat `RiskResult` carrying
  `descendantProvenances(combined)` computed eagerly. Under uniform wrapping the
  wrapper keeps `source` instead, so the walk is performed on demand and the
  eager call at the portfolio arm goes away. The scaladoc above
  `descendantProvenances` describes that collapsed case and is rewritten in the
  same pass.
- `ProvenanceSpec.scala:336-337` uses
  `g.children.collect { case r: RiskResult => r.nodeId -> r.provenances }`. A
  `collect` is not exhaustiveness-checked, so it will compile and silently return
  an empty list once the children are wrappers. It must be updated with the
  production branch, not left to pass vacuously.

#### Cache interaction: none

`ValuationResult` is built strictly above the cache boundary. Only leaf content is
cached, keyed by a content hash of identity-free content; param-stage transforms
are folded in before the hash, and result-stage transforms are applied after the
cache read and never stored. The decorator is constructed from values the cache
has already returned, so no cache key, no cached value and no hash input changes.

#### Status: ruled, not yet elevated

This section records the rulings. It is **not** an implementation-grade
specification and confers no G3 coverage: the exact file placement, the resolver
trait's return type, and whether `flatten`'s removal travels with this sub-slice
are not settled here. They are §7.6.12 decisions 1, 2 and 4, and must be ruled and
written up before any source edit implements this.

The inventory delta is settled — §7.6.12 decision 5 records the check and its
answer — and so is the construction site, in "Constructing a `ValuationResult`"
above. What that subsection does **not** settle is decision 3, which is where in
the resolver the decoration happens, not what it produces.

## 9. Domain-invariant hardening (immediate follow-up to M1R)

**Status:** elevated to the implementation-grade plan
[PLAN-DOMAIN-INVARIANT-HARDENING.md](PLAN-DOMAIN-INVARIANT-HARDENING.md), which
supersedes this design-stage sketch — it carries the exact signatures, file
inventory, hook token, and resolved decisions. The text below is retained as the
originating design record.

**Motivation (surfaced by M1R's bounds work, 2026-08-13).** Two gaps, both
pre-existing, neither introduced by M1R:
1. `RiskTree` and `TreeIndex` are the **only two aggregate types with public
   constructors** — `RiskLeaf`, `RiskPortfolio`, `Mitigation`,
   `ResultTransformSpec`, `RiskLeafTransform`, `LossDistribution` all use
   `private` + a smart constructor. So every invariant `RiskTree.fromNodes` /
   `TreeIndex.fromNodes` validates (unique ids, root-exists, and the new
   mitigation/step counts) is **bypassable** via `apply` / `.copy`.
2. **The HTTP request body-size limit is implicit and wrong-sized.** zio-http's
   `Server.Config.default` sets `requestStreaming = Disabled(102400)` — a 100 KiB
   cap the server inherits unchanged, so it is undocumented, unconfigurable, and
   (if it binds through the tapir interpreter) rejects valid large trees. The
   original "none found" reading missed the zio-http default; the elevated plan
   replaces the implicit cap with an explicit, configurable 8 MiB limit so an
   oversized payload is rejected before the decoder allocates it.

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

## Task — user-documentation stub: targeting semantics under node renames

Create the user-facing documentation stub for mitigation targeting, and include
in it a **required, explicit note on how a mitigation's targeting behaves when a
node is renamed or deleted.** This is a mandatory user-facing note, not optional
background: targeting behaviour under renaming is non-obvious and differs by which
predicate the author used.

The note must state, in user terms:

- **Name-based targeting re-scopes dynamically.** A targeting predicate written
  with `named_risk(x, "…")` (or a bare quoted node name in a node slot) is resolved
  by *name against the current tree* every time the mitigation's scope is computed.
  A node newly matching that name is auto-included; a node whose name no longer
  matches — because it was renamed or deleted — silently drops out of scope. When
  a referenced name resolves to nothing, the mitigation's scope has **drifted**;
  this is surfaced as a per-mitigation drift signal (the mitigation applies to
  nothing until the predicate resolves again), not an error.
- **Id-based targeting is rename-stable.** A predicate written with
  `risk_id(x, "…")` references the node's stable id, which does not change on
  rename, so its scope is unaffected by renames.
- **Guidance for the author.** Use `named_risk(…)` when the scope should track a
  *name* (and pick up any future node given that name). If stability across renames
  is wanted — the scope should stay pinned to *this specific node* regardless of
  what it is later called — phrase the targeting explicitly with `risk_id(…)`.

**Authoring constraint:** every example in this note MUST be written and verified
against the code as it stands at the time the doc is written (re-run the behaviour;
do not copy examples forward from this plan). The mechanism recorded here reflects
`TargetingPredicate` (source-text storage + per-version re-resolution),
`MitigationScopeResolverLive.satisfyingIds` (bind against the current catalog),
and the `named_risk`/`eq` → `nameToId.get` vs `risk_id` → `NodeId.fromString`
literal split in `RiskTreeKnowledgeBase` — verified 2026-09-10, but re-verify at
doc time.

This composes with the existing user-doc TODO (§7.4.1); it does not replace it.
