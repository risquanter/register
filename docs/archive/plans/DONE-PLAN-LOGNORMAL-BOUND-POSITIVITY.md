# Plan — lognormal bound positivity (`PositiveLong`), outward rounding, and the sub-unit mitigation error

**Status:** §1–§12 implemented 2026-10-03 at version 0.10.43, all four test tiers
green. LBP-D-1 through LBP-D-9 ruled (LBP-D-4 is superseded by LBP-D-6). §11a's
no-elimination rule was absorbed into ADR-034 §6 and the `adr-constraints`
distillation. What the implementation found that the plan did not state, including
one item in §7 that was not reachable, is recorded in §13.

**§14 implemented 2026-10-03 at version 0.10.44**, all four tiers green. It closes
the four remaining ways to express a zero residual that §11a listed and left open,
ruling LBP-D-10 through LBP-D-12. The rule now has two enforcement points: a type
at the boundary where the parameter decides the outcome, and a check in
`LossDistribution.decorate` where the trial outcomes decide it.

**No tree that has already been saved can be affected by the three tightened
validation rules.** No trees have been saved: there is no Irmin backend holding
any. The point would otherwise matter, because all three rules run on the way out
as well as on the way in — `RiskLeaf`'s decoder is `mapOrFail` over
`RiskLeaf.create`, so reading a saved leaf re-runs the same validation that
accepted it, and `RiskTreeRepositoryIrmin.readNodesAt` fails the entire tree read
on the first node that will not decode. A leaf saved with a `minLoss` of zero (§3)
or an expert quantile of zero (§10), or a mitigation saved with the operation
`"narrow"` (§12), would therefore stop its whole tree from opening rather than
failing only at simulation. With nothing saved there is nothing to re-read, and a
backend populated from the committed fixtures is clean too: no file under
`examples/` carries a zero bound, a zero quantile, or that operation.

**Goal.** A lognormal leaf's CI bounds (`minLoss` = P05, `maxLoss` = P95) feed a
logarithm in the distribution fit, so both must be strictly positive. The codebase
stores them as `NonNegativeLong` (≥ 0), one notch too weak, and enforces `> 0` late
(at simulation) instead of at the type boundary. This plan:

1. adds a `PositiveLong` Iron type and retypes the bound declaration sites to it, so a
   zero bound is unrepresentable and rejected once, at the boundary (ADR-001);
2. changes the severity mitigation transform from round-to-nearest to outward
   rounding (`floor` the lower bound, `ceil` the upper), removing the tie-collapse
   failure and keeping the fitted interval conservative;
3. produces a specific, informative error (server-side) when a mitigation scales a
   bound below the smallest representable whole unit, instead of a late cryptic
   simulation error;
4. keeps the bound arithmetic as pure functions in `common` (no JVM libraries),
   used by the server check and available to a future client pre-check.

**Root cause (one fact).** `σ = (log(maxLoss) − log(minLoss)) / 3.29`; `log(0)` is
−∞. Every item below is a consequence of "the lower bound feeds a logarithm, so it
must be `> 0`", meeting "bounds are whole integers — the `Loss` unit is an
uninterpreted `Long`, so values below one unit are not representable". The `Loss`
unit itself (the `1L = $1M` scaladoc convention, which the example data does not
follow — it enters dollars verbatim) is a separate inconsistency tracked in
`docs/dev/TODO.md`, out of this plan's scope.

---

## 1. New type — `PositiveLong` (colocated, mirrors `PositiveInt`)

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`,
immediately after `type PositiveInt = Int :| Greater[0]` (line 73).

```scala
// Positive long values (must be > 0) — e.g. lognormal CI bounds, which feed a logarithm
type PositiveLong = Long :| Greater[0L]
```

**Smart-constructor helper**, in
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`,
after `refineNonNegativeLong` (line 149) — exact mirror of `refinePositiveInt`,
reusing the existing `valueMustBePositive` message:

```scala
// Refinement for positive long values (must be > 0)
def refinePositiveLong(value: Long, fieldPath: String = "value"): Either[List[ValidationError], PositiveLong] = {
  value
    .refineEither[Greater[0L]]
    .left
    .map(_ => List(ValidationError(
      field = fieldPath,
      code = ValidationErrorCode.INVALID_RANGE,
      message = ValidationMessages.valueMustBePositive
    )))
}
```

No `DeriveConfig[PositiveLong]` is added: no bound is config-loaded. If one ever is,
it follows the `SimulationConfig` `positiveIntConfig` shape verbatim.

---

## 2. Shared bound arithmetic (pure, `common`, no JVM libraries)

**Location:** the `RiskLeafTransform` companion object in
`modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala`
(colocated with the only transform that uses it; already cross-compiled to JS).
Only `Math.floor` and `Math.ceil` are used — both implemented by Scala.js.

All three functions are total. They have no failure channel because, with the
factor bounded above by 1 (§11), neither scaled bound can leave the `Long` range:
the floored lower bound is at most `min` and the ceiled upper bound is at most
`max + 1`, and `max` is already a `Long`.

```scala
/** Severity-scaled lognormal CI bounds: lower floored, upper ceiled. Outward
  * rounding only widens the fitted interval (σ never shrinks) and preserves the
  * strict `min < max` ordering whenever the continuous scaled bounds differ. */
def scaleSeverityBounds(min: Long, max: Long, factor: Double): (Long, Long) =
  (Math.floor(min * factor).toLong, Math.ceil(max * factor).toLong)

/** A scaled lower bound is representable when it stays at the positive floor
  * (≥ 1 whole unit). Below that it floors to 0 and cannot be fit. */
def lowerBoundRepresentable(scaledMin: Long): Boolean = scaledMin >= 1L

/** Smallest severity factor that keeps `min` representable (`min * f ≥ 1`). */
def minRepresentableFactor(min: Long): Double = 1.0 / min.toDouble
```

There is no `narrowBounds`. `DistributionTransform.Narrow` is deleted by this plan
(§12), so the only parameter-stage operation that touches the bounds is
`ScaleSeverity`.

---

## 3. Retype the bound declaration sites to `PositiveLong`

Each is a mechanical type-tightening from `NonNegativeLong` to `PositiveLong`,
with the refine call switched from `refineNonNegativeLong` to `refinePositiveLong`.
The wire format is unchanged everywhere (a `Long` still serializes as a number);
only the decode side tightens to reject `0`, which was never a valid bound.

| # | File | Change |
|---|---|---|
| 1 | `.../domain/data/RiskNode.scala` | `RiskLeaf.minLoss/maxLoss: Option[PositiveLong]`; private `apply` params; `validateLognormalMode` and `validateModeFields` return/accept `Option[PositiveLong]`; `requireMinBelowMax` params `PositiveLong`. `create`'s external signature stays `Option[Long]` (raw-input path) — only the internal refine + stored field type change. `isValid` unchanged (still `min < max`; positivity now holds by type). The class-body `require` block's lognormal clause gains the positivity conjunct (§3b). |
| 2 | `.../domain/data/Distribution.scala` | `Distribution.minLoss/maxLoss: Option[PositiveLong]`; `create` refines via `refinePositiveLong`. |
| 3 | `.../domain/data/RiskLeafTransform.scala` | `OverrideDistributionParams.minLoss/maxLoss: Option[PositiveLong]`; its `create`, codec `optRefineLong`, and `validateModeFields` call switch to `refinePositiveLong`. |
| 4 | `.../domain/data/Provenance.scala` | `LognormalDistributionParams.minLoss/maxLoss: PositiveLong` (non-optional); codec switches to `refinePositiveLong`. |
| 5 | `.../domain/data/LeafSimContent.scala` | `minLoss/maxLoss: Option[PositiveLong]`. The `Raw` encoder stays `Option[Long]`; **field order and emitted bytes are unchanged**, so the cache-key preimage and the `LeafSimContentSpec` byte-stability snapshot are unaffected (ADR-032). |
| 6 | `.../domain/data/iron/OpaqueTypes.scala` | the new type (§1). |
| 7 | `.../domain/data/iron/ValidationUtil.scala` | the new helper (§1). |

`DistributionShapeRequest` is **not** changed: it holds raw `Option[Long]` and
delegates all validation to `Distribution.create`, so the tightening reaches it for
free.

Consumer check, done by reading every reader rather than deferred to the compile.
Most readers widen the bound to `Long` and need no change (`l: Long`, and
`min > 0 && min < max` in `Simulator`), because an Iron refined type `A :| C` is
declared as a subtype of its base type `A`. The `Simulator` `min > 0` guard and
`LognormalDistribution.fromConfidenceInterval`'s `lowerBound <= 0` guard stay as
defense in depth.

**Three frontend readers do not widen, and are changed here.** Iron's subtyping
reaches the base type only: `Long :| Greater[0L]` and `Long :| GreaterEqual[0L]`
are each a subtype of `Long`, and neither is a subtype of the other. `Option` is
covariant in its element type, so an `Option[PositiveLong]` cannot be passed where
an `Option[NonNegativeLong]` is declared. The same constraint is already visible in
the codebase: `RiskNode.scala` and `LeafSimContent.scala` both write
`leaf.minLoss.map(identity)` solely to widen the option's element to `Long`.

| # | File | Change |
|---|---|---|
| 8 | `.../app/components/TreeNodeRow.scala` | `leafTooltip`'s `minLoss`/`maxLoss` parameters become `Option[Long]`. The function interpolates the value into a tooltip string and makes no use of the refinement, so the base type is the right parameter type and no future bound-type change reaches it. |
| 9 | `.../app/views/TreePreview.scala` | `TreeNode.Leaf`'s `minLoss`/`maxLoss` parameters become `Option[Long]`; its two construction sites pass `l.distribution.minLoss.map(identity)` / `.maxLoss.map(identity)`, and its `leafTooltip` call is unchanged. |
| 10 | `.../app/views/TreeDetailView.scala` | `nodeTooltip` passes `leaf.minLoss.map(identity)` / `leaf.maxLoss.map(identity)` into `leafTooltip`. |

### 3b. The class invariant assertion (LBP-D-7)

`RiskLeaf`'s class body already carries a `require` block, commented "Defense in
depth: invariant check as safety net" and "Should never trigger if custom decoder
works correctly". Its lognormal clause currently reads:

```scala
case "lognormal" => minLoss.isDefined && maxLoss.isDefined && minLoss.get < maxLoss.get
```

It becomes:

```scala
case "lognormal" =>
  minLoss.isDefined && maxLoss.isDefined && minLoss.get > 0L && minLoss.get < maxLoss.get
```

The added conjunct is redundant against the type in every ordinary path, and that
is the point. A `require` block states the class's invariant in one place, and a
block that enumerated the ordering rule while omitting the positivity rule would be
an incomplete statement of an invariant that now has two parts. It is also the one
check that survives Iron's `assume`, which produces a refined value with no runtime
test at all; `refineUnsafe`, which the tests use, already rejects a zero on its own.

This is the only place the positivity rule is asserted outside the type. It is
deliberately **not** added to `validateLognormalMode` or to `Distribution.create`'s
lognormal branch: the refinement runs first in both, so a later `> 0` test there
could never see a zero, and an unreachable branch in a `Validation` path reads as
live code and would mislead.

### 3a. Documentation the change makes stale

Every item here is in a file this plan already edits, so the sweep adds no file to
the inventory. Each is corrected in the same pass as the code it describes.

| File | Current text | Why it is wrong afterwards |
|---|---|---|
| `RiskNode.scala` | "minLoss/maxLoss: NonNegativeLong (>= 0)" | names the old type and the old constraint |
| `RiskNode.scala` | "@param minLoss Optional min loss (lognormal mode, will be refined to NonNegativeLong)" | names the old refinement |
| `RiskLeafFormState.scala` | "Lognormal mode: requires minLoss < maxLoss (both non-negative)" | the bounds are positive, not merely non-negative |
| `RiskLeafFormState.scala` | "Lognormal mode: minLoss validation using Iron NonNegativeLong" (and the same line for maxLoss) | names the old refinement |
| `RiskLeafTransform.scala` | "requires a positive minLoss — log space is undefined at 0" | still true, but the requirement now holds by type rather than by guard, which is what the sentence should say |
| `RiskLeafFormState.scala` | "Expert mode: quantiles validation (non-negative loss amounts)" | §10 makes the quantiles strictly positive, so "non-negative" names the rule the change replaces |
| `ValidationMessages.scala` | `quantilesMustBeNonNegative`, "Quantiles must be non-negative" | the same: §10 replaces this message, so both the value name and its text are stale |

One further correction, in a file already in scope, which this plan's own premise
exposes. `RiskNode.scala` describes the lognormal bounds as an "80% CI" in three
places — once in the mode summary at line 57, and once each in the `@param` lines
for `minLoss` and `maxLoss`, as "80% CI lower bound in millions" and the matching
upper-bound line. The fit implemented in
`LognormalHelper.scala` uses the 5th and 95th percentiles, which is a 90%
confidence interval, and this plan's goal statement says the same. The scaladoc is
wrong as it stands and is corrected to 90%. The "in millions" half of that sentence
is the separate `Loss`-unit inconsistency recorded in `docs/dev/TODO.md`; this plan
does not resolve it, so that clause is left as it is rather than changed to
something equally unverified.

---

## 4. Outward rounding + the sub-unit error (server, in `common`)

**File:** `RiskLeafTransform.scala`, `distributionFields` — the `ScaleSeverity`
lognormal branch. Replace the two `Math.round` sites with `scaleSeverityBounds` and
an explicit representability check that produces the informative error **before**
`RiskLeaf.create` (whose `PositiveLong` refinement is the backstop — defense in
depth). The `Narrow` branch's own two `Math.round` sites are not rewritten; that
whole branch is deleted (§12).

### 4a. What this supersedes, and why the two rounding rules differ

These two lines were last changed by commit `811f51c` (29 September 2026,
"simulation: round sampled loss and distribution params to nearest rather than
truncating toward zero"), which moved them from truncation toward zero to
round-to-nearest after the user reviewed the effect of truncation case by case.
**This plan supersedes `811f51c` on these two lines only**, and leaves the rest of
that commit — the sampled-loss rounding in `RiskSampler` — unchanged.

The reason the two halves of `811f51c` diverge here is that a sampled loss and a
confidence-interval endpoint are different kinds of quantity, and the rounding rule
that is right for one is wrong for the other.

A sampled loss is a point estimate drawn from a distribution. Rounding it to the
nearest whole unit is unbiased: across many trials the rounding error averages to
zero, so the simulated total is not systematically pushed either way. That is why
`RiskSampler` keeps round-to-nearest.

A confidence-interval endpoint is not a point estimate. It is one end of a range
whose width is the information being stated, and the fit converts that width into
the distribution's spread parameter σ (sigma, the standard deviation of the
underlying normal in log space). Rounding each endpoint to the nearest whole unit
can move both endpoints inward, which narrows the range, which shrinks σ, which
understates the modelled uncertainty. In the limit the two endpoints meet and the
range vanishes: with `minLoss = 100`, `maxLoss = 101` and factor 0.505,
`Math.round(50.5)` is 51 and `Math.round(51.005)` is also 51, so the mitigation is
rejected with "minLoss must be < maxLoss" — a message that misdescribes correctly
ordered input. Flooring the lower endpoint and taking the ceiling of the upper can
only widen the range, so σ can only grow, and the two endpoints can never meet.

### 4b. Two consequences of the rounding change

Both follow from the rounding change alone, not from the type change, and neither
is a defect.

**Mitigated figures move slightly.** Outward rounding widens the fitted interval,
so σ for a mitigated lognormal leaf is equal to or larger than it was, and the loss
exceedance curves derived from it shift accordingly. Any comparison a user has
saved will show slightly different mitigated figures after this lands. The
direction is conservative: the mitigated distribution is never reported as
narrower than the arithmetic supports.

**Every previously cached mitigated leaf result misses once.** A leaf's cache key
is `sha256` of the `LeafSimContent` projection, and parameter-stage transforms are
folded into the effective tree before that hash is computed. Changing the rounding
changes a mitigated leaf's bounds, which changes its projection, which changes its
key. The first read after this lands recomputes those leaves and repopulates the
cache. Unmitigated leaves are untouched, because their bounds do not pass through
these two lines — which is also why the `LeafSimContentSpec` byte-stability
snapshot is unaffected (§3, row 5).

`ScaleSeverity` lognormal branch (new):

```scala
case DistributionTransform.ScaleSeverity(f) =>
  leaf.distributionType.toString match {
    case "lognormal" =>
      (leaf.minLoss, leaf.maxLoss) match {
        case (Some(min), Some(max)) =>
          val (newMin, newMax) = RiskLeafTransform.scaleSeverityBounds(min, max, f)
          if (!RiskLeafTransform.lowerBoundRepresentable(newMin))
            Validation.fail(ValidationError(
              field   = s"$fieldPrefix.minLoss",
              code    = ValidationErrorCode.INVALID_LOGNORMAL_PARAMS,
              message = ValidationMessages.mitigationLowersMinLossBelowFloor(
                          min, RiskLeafTransform.minRepresentableFactor(min))
            ))
          else
            Validation.succeed(("lognormal", None, None, Some(newMin), Some(newMax), None))
        case _ =>
          Validation.succeed(("lognormal", None, None,
            leaf.minLoss.map(m => (m: Long)), leaf.maxLoss.map(m => (m: Long)), None))
      }
    case _ => /* expert branch unchanged: quantiles scaled, no rounding */
  }
```

The `(Some, Some)` match keeps a fallback branch for exhaustivity, because both
bounds are `Option`. That branch is unreachable for a lognormal leaf: the
class-body `require` (§3b) guarantees both bounds are present. It therefore raises
no error of its own and emits no message. It passes whichever bounds it was given
straight through, and `RiskLeaf.create` — which `applyTo` calls on the result —
rejects a lognormal leaf with a missing bound with the existing message for that
case. Adding a second message here would mean writing user-facing text for a state
the type system already prevents.

There is no `Narrow` branch. `DistributionTransform.Narrow` is deleted by this plan
(§12).

**Meaningful message**, in `ValidationMessages.scala` (the lognormal section):

```scala
def mitigationLowersMinLossBelowFloor(min: Long, minFactor: Double): String =
  f"This mitigation reduces the minimum loss below 1, the smallest representable whole unit. The leaf's minimum loss is $min, so the smallest severity factor that still applies here is $minFactor%.3e (1 / $min)."
```

The factor is formatted in scientific notation rather than as a fixed number of
decimal places. Realistic bounds are large, so the smallest workable factor is
small: a leaf whose minimum loss is 40 000 — the value the
`examples/stage-compare-slots.sh` fixture uses — has a smallest workable factor of
0.000025, which three decimal places would render as `0.000` and tell the user the
answer is zero. Scientific notation keeps four significant digits at every
magnitude. The exact reciprocal is given alongside it so the number can be checked
by hand.

---

## 5. Client side

### 5a. Leaf-form bound validation → `PositiveLong` (lands now)

**File:** `modules/app/src/main/scala/app/state/RiskLeafFormState.scala`. In
`minLossErrorRaw` and `maxLossErrorRaw`, switch `refineNonNegativeLong` →
`refinePositiveLong`, so the form rejects `0` with "must be positive" and matches
the server. The cross-field `min < max` signal is unchanged.

### 5b. Mitigation sub-unit handling — server-side only (no client pre-check in scope)

The sub-unit case is handled entirely server-side, by the §4 informative error.
There is **no client pre-check in this plan.** Two reasons, ruled: the case is rare
under realistic inputs (bounds are entered as large whole numbers, so a scaled
lower bound almost never falls below one unit), and there is no mitigation-authoring
view in `modules/app/src` to host a pre-check (only a test passes
`mitigations = Nil`). The §2 functions stay in `common` and are available to the
future client pre-check, which is routed to `PLAN-RISKTRANSFORM.md`'s post-M4
frontend scope (LBP-D-3); wiring one is out of scope here.

---

## 6. ADR alignment

| ADR | Bearing | Status |
|---|---|---|
| ADR-001 (validate once at boundary) | `PositiveLong` + `refinePositiveLong`; the shared-module sites refine at the codec/`create` boundary; services receive validated types | Compliant |
| ADR-010 / ADR-035 (typed, safe errors) | sub-unit case is a `ValidationError` with a code and a safe, human-readable message; no internals leaked | Compliant |
| ADR-032 (content equality) | `LeafSimContent` `Raw` encoder and field order unchanged; hash preimage stable; snapshot test unaffected | Compliant |
| ADR-034 (mitigation valuation) | transform stays above the cache boundary; the sub-unit error arises in the `mitigated` fold at the read edge, re-derived deterministically | Compliant |
| ADR-018 (nominal wrappers) | `PositiveLong` is a value refinement, not two IDs sharing an encoding → a plain Iron alias like `PositiveInt`, not a `case class` wrapper | Compliant |
| ADR-019 (frontend) | leaf-form change stays `Signal`-derived from `Var`s; no `.now()` in render | Compliant |
| ADR build rule (inexhaustive sealed match = compile error) | LBP-D-1 adds no `ValidationErrorCode` case, so no exhaustive match on that enumeration changes | Compliant |

---

## 7. Verification plan

**Phase 1 — server + common (unit).** Green under:

```
sbt "commonJVM/test; server/test"
```

New / extended tests:
- `ValidationUtilSpec` — `refinePositiveLong` accepts `1`, rejects `0` and
  negatives; `refineRetentionFactor` accepts the smallest positive double and
  `1.0`, rejects `0.0`, anything above `1.0`, and not-a-number.
- `RiskLeafSpec` / `RiskNodeSpec` — a `"lognormal"` leaf with `minLoss = 0` is
  rejected at `create` (was accepted before); `min < max` still enforced.
- `RiskLeafTransformSpec` — the previously-collapsing tie case (`100→101`,
  `×0.505`) now yields a valid `min < max`; a sub-unit factor yields the sub-unit
  `ValidationError` naming the smallest workable factor, **not** a simulation
  error; `scaleSeverityBounds` / `lowerBoundRepresentable` /
  `minRepresentableFactor` unit tests; a transform payload with `"op": "narrow"`
  fails to decode with the unknown-operation message (§12).
- `DistributionSpec` — a zero quantile is rejected, the smallest positive one
  accepted (§10).
- `MetalogDistributionSpec` — a zero quantile fed to the real fitter, recording
  what it actually does (§10).
- `RiskResultTransformSpec` — the overflow case at `Long.MaxValue` with factor
  `1.0`; `refineRetentionFactor` rejects `0.0` and `2.0` (§11).
- `ProvenanceSpec` — `LognormalDistributionParams` codec rejects `minLoss = 0`.
- `LeafSimContentSpec` — byte-stability snapshot unchanged (proves no hash drift).

**Phase 2 — client (unit + integration).** Green under:

```
sbt app/test
sbt "serverIt/test"    # leaked-state cleanup first (register-dev skill)
```

- `RiskLeafFormStateSpec` — `minLoss = "0"` in lognormal mode now shows the
  positivity error.
- Integration (`serverIt`) — a tree PUT with a `"lognormal"` leaf `minLoss = 0` is
  rejected end-to-end with the positivity `ValidationError`; a mitigation whose
  `ScaleSeverity` drives a bound sub-unit returns the §4 informative message.

Report pass/fail only. A red tier in any touched module blocks done (G5).

---

## 8. Open decisions

Decision labels carry this plan's own code, `LBP`, short for lognormal bound
positivity, so a label quoted elsewhere names the plan that ruled it.

### LBP-D-1 — error code for the sub-unit case — RULED: reuse `INVALID_LOGNORMAL_PARAMS`
The sub-unit error carries `ValidationErrorCode.INVALID_LOGNORMAL_PARAMS`; the
`f_min` message carries the specificity. No new enum case, so no inexhaustive-match
blast radius, and the 400 status is unchanged.

### LBP-D-2 — location of the shared bound functions — RULED: Option A (companion)
The four functions live on the `RiskLeafTransform` companion object, colocated with
their sole caller.

### LBP-D-3 — client mitigation pre-check UI — RULED: routed to PLAN-RISKTRANSFORM (post-M4)
No client pre-check in this plan. The task is recorded in `PLAN-RISKTRANSFORM.md`'s
post-M4 frontend scope (the M5 planning follow-up), to be built on the
mitigation-authoring UI that milestone introduces, using the §2 functions. Until
then the server-side `INVALID_LOGNORMAL_PARAMS` error is the only signal.

### LBP-D-4 — overflow guard on the bound functions — SUPERSEDED by LBP-D-6
The earlier ruling was that `boundToLong` guards and throws on a not-a-number or
`≥ Long.MaxValue` scaled bound, and that this is defense in depth which cannot
occur for a single confidence-interval bound.

**That premise does not hold, in three separate ways, so the ruling is withdrawn
pending a fresh decision.**

First, the overflow is reachable. `DistributionTransform.ScaleSeverity` carries a
`NonNegativeDouble` factor, which is constrained only to be at least `0.0` and at
most `1.7976931348623157e308`, the largest finite double. The decoder refines the
client's number against that type and applies no further bound. A minimum loss of
1 000 with a factor of `1e300` gives `1e303`, and the largest `Long` is about
`9.22e18`.

Second, the distinction the ruling drew is not the operative one. Overflow in
`RiskResultTransform.scaleLosses` is not caused by accumulation either; it is
caused by the factor. That function's own scaladoc already states it: "A factor
above 1 can take a loss past `Long.MaxValue`." The same factor type reaches a
confidence-interval bound by the same route.

Third, the two halves of the guard have opposite truth values. The not-a-number
branch is genuinely unreachable: the factor cannot be not-a-number, because that
value fails the `>= 0.0` constraint under IEEE 754 comparison, and the bound is an
integer. The overflow branch is reachable. A single comment cannot truthfully
describe both.

A fourth point is a defect in the plan text rather than in the ruling. §2 states
that the §4 branches wrap the bound computation so the throw cannot escape, but the
§4 code contains no such wrapping. Written as shown, the `ArithmeticException`
escapes `applyTo`, escapes `MitigationApplication.effectiveTree` — which returns a
`Validation` and installs no handler — and becomes a defect, surfacing as HTTP 500
rather than the intended 400.

### LBP-D-5 — plan name — RULED: `PLAN-LOGNORMAL-BOUND-POSITIVITY`
Confirmed; no rename.

### LBP-D-6 — scale-factor bound — RULED: `(0, 1]`
The three scale operations take a `RetentionFactor`, strictly above zero and at
most one, and the change lands in this plan rather than a separate one. A figure
that increases is stated absolutely through an `Override`, which is where worsening
already belongs and where it is visible. A factor of zero is excluded on
methodological grounds, not safety grounds: no transform may assert that a risk has
ceased to exist. Written out in §11, with the rule and its remaining reach in §11a.

### LBP-D-7 — where the positivity rule is asserted outside the type — RULED
The `RiskLeaf` class-body `require` block's lognormal clause gains the positivity
conjunct. Nothing is added to `validateLognormalMode` or to `Distribution.create`'s
lognormal branch, because the refinement runs first in both and an unreachable
branch in a `Validation` path reads as live code. Written out in §3b.

### LBP-D-9 — `DistributionTransform.Narrow` — RULED: deleted
`Narrow` raises the author's own declared 5th percentile — on the worked leaf, from
1 000 to 2 659 — while leaving the median bit-identical. Raising a low quantile is
not unique to it: the fit pins the 5th percentile at `minLoss` and the 95th at
`maxLoss`, so any refit that reduces the spread parameter pivots the distribution
and something below the pivot rises. What is specific to `Narrow` is *which* figure
moves. It moves one the author wrote down, and leaves untouched the median a
reviewer checks, while cutting the mean by up to half. Every real mechanism that compresses loss spread
is one-sided — a cap, a floor, a blast-radius limit — and the one mechanism that
matches `Narrow`'s shape reduces uncertainty about the loss rather than the loss.
Written out in §12, with the `Override` construction that reproduces any
contraction's spread reduction while leaving the floor in place. Whether that
construction should also become a parameterised transform, so that it can broadcast
across leaves with different bounds, is an open question carried in
`PLAN-RISKTRANSFORM.md`'s final step rather than decided here.

### LBP-D-8 — expert-mode zero quantile — RULED: fixed in this plan
A quantile value of zero reaches `ln(0)` in the metalog fit, unguarded on both
sides, and fails silently rather than loudly. It is the same root cause as the
lognormal lower bound in the other distribution mode, it is cheaper to fix than the
defect this plan was opened for, and it is more severe. Written out in §10.

---

## 9. File inventory

The files this plan edits, covering §1 through §11. The authorizing scope document
is `PLAN-LOGNORMAL-BOUND-POSITIVITY-INVENTORY.md`, which does not exist yet; the
user creates it by pasting this list into `.claude/protocol/pending` and running
`.claude/bin/approve-inventory`. Every path below exists except the one marked
`new:`, which states that the file does not exist yet so the claim can be checked.

**Common (main):**
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala

**Common (test):**
- modules/common/src/test/scala/com/risquanter/register/domain/data/iron/ValidationUtilSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafTransformSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/LeafSimContentSpec.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/DistributionSpec.scala

**Server (main + test):** `Provenance.scala` lives in `common` but its spec lives
in `server`, so that spec needs its own entry — the hook's same-module test
allowance does not reach across modules.
- modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala
- modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala
- modules/server/src/test/scala/com/risquanter/register/mitigation/RiskResultTransformSpec.scala
- modules/server/src/test/scala/com/risquanter/register/simulation/LossDistributionSpec.scala
- modules/server/src/test/scala/com/risquanter/register/simulation/MetalogDistributionSpec.scala

**Client (main + test):**
- modules/app/src/main/scala/app/state/RiskLeafFormState.scala
- modules/app/src/main/scala/app/components/TreeNodeRow.scala
- modules/app/src/main/scala/app/views/TreePreview.scala
- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/test/scala/app/state/RiskLeafFormStateSpec.scala

**Integration:**
- new: modules/server-it/src/test/scala/com/risquanter/register/http/LognormalBoundPositivityItSpec.scala

Further test files construct one of the three scale operations, and are absent from
this list for two different reasons, both deliberate.

Six pass a literal factor of `0.5`, `0.8` or `1.0`, which the compiler refines
against the new `RetentionFactor` constraint, so they compile with no edit at all:
`MitigationScopeResolverSpec`, `MitigationStalenessSpec`, `RiskTreeServiceLiveSpec`,
`MitigationEntitySpec`, `ResultTransformSpecSpec` and
`ResultTransformInterpreterSpec`.

Two do need an edit, and are reached without an inventory entry by the gate's
same-module test allowance: `CachedResultResolverSpec` and
`MitigationApplicationSpec` both pass a run-time-refined `NonNegativeDouble` into
`ScaleSeverity`, which no longer typechecks, so each switches to
`refineRetentionFactor` (§11). Both live under `modules/server/src/test/`, and this
list names `modules/server/src/main/.../RiskResultTransform.scala`, which authorizes
any test edit in that same module.

If implementation reaches a file this list does not name, that denial is the
deviation escalation: stop, present the path, and wait for the inventory to be
amended.

---

## 10. Expert-mode quantile positivity (LBP-D-8)

**The same root cause, in the other distribution mode, failing silently instead of
loudly.** An expert-mode leaf states its loss distribution as percentile-and-value
pairs, and the simulator fits a metalog distribution to them. A metalog is a
flexible shape fitted to quantile pairs; the implementation is the first-party
`metalog-distribution` library, whose source checkout is a sibling of this
repository.

The fit is performed in lower-bounded mode with the bound set to zero. `Simulator`
calls `MetalogDistribution.fromPercentiles(..., lower = Some(0.0))`, commented "Loss
cannot be negative". In lower-bounded mode the library applies Keelin's
lower-bounded transform `z = ln(x − L)`, which with `L = 0.0` is `z = ln(x)`.

A quantile value of exactly `0` therefore produces `ln(0)`, which is negative
infinity, and nothing stops it arriving there:

- `Distribution.create` rejects a quantile only when it is not-a-number, infinite,
  or **less than** `0.0`. Exactly `0.0` passes, under the message "Quantile loss
  amount must be non-negative".
- `RiskNode.validateExpertMode` checks array lengths and the minimum point count.
  It does not inspect the values.
- `MetalogDistribution.validateBounds` checks only that a lower bound is below an
  upper bound. Nothing checks that each quantile exceeds the lower bound.
- In the library, neither `QPFitter` nor `QPUnboundedConstrainedFitter` tests for a
  non-finite input. The only exception either throws is a length mismatch.

So a negative infinity enters the quadratic-program solve that fits the
coefficients. **The resulting symptom is not yet established** — it is either
not-a-number coefficients or a failure inside the linear algebra — and determining
which is a deliverable of this section, because it decides whether any existing
tree is already producing meaningless figures rather than an error.

**The change.** In `Distribution.create`, the quantile element test moves from
`qt < 0.0` to `qt <= 0.0`. In `RiskLeafFormState`, the expert quantile field's own
test moves from `values.exists(_ < 0)` to `values.exists(_ <= 0)`, so the form
rejects a zero before submit. Both report the same new message, which replaces
`quantilesMustBeNonNegative` in `ValidationMessages.scala`:

```scala
val quantilesMustBePositive: String =
  "Quantile loss amounts must be greater than zero — the distribution fit takes their logarithm"
```

The clause after the dash states the reason, which is the established pattern for
the explained messages in that file — `percentilesMustBeStrictlyIncreasing` and
`quantilesMustBeStrictlyIncreasing` are both written that way.

**The two sides currently disagree, and this consolidates them.** The shared value
`quantilesMustBeNonNegative` has exactly one reader, the client form at
`RiskLeafFormState.scala:158`. The server does not use it: `Distribution.create`
carries its own inline string, `"Quantile loss amount must be non-negative"`, so the
same rejection reads differently depending on which side produced it. The new value
replaces both, and `Distribution.create`'s inline literal becomes a reference to it.
The old value is then unreferenced and is deleted.

**Scope note.** This tightens the wire contract: a client sending a zero quantile
for an expert leaf starts receiving a 400 where it previously received a stored
tree that could not be simulated correctly. The example data does not send one.

**Verification.** `DistributionSpec` gains a case rejecting a zero quantile and a
case accepting the smallest positive one. One test feeds a zero quantile to the
real fitter through `MetalogDistribution.fromPercentiles` and records what it
actually does, which is what settles the open symptom question above.

---

## 11. Scale-factor bound (LBP-D-6)

**This supersedes LBP-D-4.** Rather than guarding an overflow, the factor that
causes it is bounded so the overflow cannot arise. The three scale operations
become consistent with each other, and `LikelihoodTransform.Scale`'s silent clamp
is replaced by rejection at the boundary.

**The new type**, in `OpaqueTypes.scala`:

```scala
// Fraction of a figure a mitigation keeps: strictly above 0 and at most 1. A
// scale operation reduces and can never eliminate — a residual risk of exactly
// zero is not a defensible estimate, because it removes control failure from the
// analysis. A figure that increases is stated absolutely through an Override,
// never as a factor. NaN fails the lower constraint by IEEE 754 comparison.
type RetentionFactor = Double :| (Greater[0.0] & LessEqual[1.0])
```

Both endpoints are settled: strictly above zero, at most one. The name follows the
wording already in `RiskResultTransform.scaleLosses`'s own example,
`scaleLosses(0.8) // 80% retention`.

### 11a. No transform may eliminate a risk

This is the methodological rule behind the exclusive lower endpoint, stated once
because it binds every future transform, not only these three.

**A mitigation may reduce a risk. No mitigation may assert that a risk has ceased
to exist.** The reason is not arithmetic. A residual risk of exactly zero asserts
that the control cannot fail, and controls fail. A model that admits a zero
residual has removed control failure from the analysis, which is the specific thing
a risk register exists to keep visible. A factor of zero is therefore not a strong
mitigation; it is a statement the methodology does not permit anyone to make.

A risk that genuinely no longer exists is not a mitigated risk. The system was
decommissioned, the data was deleted, the business line was exited. That is removal
of the node from the tree, which the model already supports and which leaves an
audit trail in the tree's history. It is not a transform applied to a node that
remains.

**The rule reaches further than this plan implements.** Bounding the three scale
factors away from zero closes the direct expression of a zero residual. The same
assertion is still reachable by parameterisation elsewhere, and each case is listed
here so the gap is on the record rather than discovered later:

| Operation | Annihilating parameterisation | Where it lives |
|---|---|---|
| `LikelihoodTransform.Override` | probability set to `0.0` | parameter stage |
| `RiskResultTransform.capLosses` | cap of `0`, which zeroes every loss | result stage |
| `RiskResultTransform.applyDeductible` | a deductible at or above every loss | result stage |
| `RiskResultTransform.filterBelowThreshold` | a threshold above every loss | result stage |

Closing those is **not** in this plan's scope, because each needs its own judgement
rather than one shared bound. A cap and a deductible describe a financial structure
whose annihilating case may be a legitimate statement about who bears the loss
rather than a claim that the loss stopped happening, and deciding that is a
modelling question, not a type question. The routing of that work is an open
decision in this plan's report, not a silent omission.

One thing that is **not** an instance of the rule, so it is not on the list above.
`applyDeductible` and `scaleLosses` end with `.filter(_._2 > 0)`, commented "Remove
zero losses (sparse storage)". That is a storage normalisation, not an
elimination: `TrialOutcomes.outcomeOf` reads an absent trial as `0L`, so a stored
zero and an absent entry are the same value, and the filter puts a zero into its
canonical form. No outcome is discarded.

**Smart-constructor helper**, in `ValidationUtil.scala`, mirroring
`refineShrinkFraction`:

```scala
// Refinement for a retention factor: a fraction of a figure that is kept
def refineRetentionFactor(value: Double, fieldPath: String = "factor"): Either[List[ValidationError], RetentionFactor] = {
  value
    .refineEither[Greater[0.0] & LessEqual[1.0]]
    .left
    .map(_ => List(ValidationError(
      field = fieldPath,
      code = ValidationErrorCode.INVALID_RANGE,
      message = ValidationMessages.retentionFactorOutOfRange
    )))
}
```

The error code is `INVALID_RANGE`, which is what `refineShrinkFraction` already
uses for the same kind of failure, so the HTTP status and the error shape are
unchanged.

**The message**, in `ValidationMessages.scala`, taking the slot
`shrinkFractionOutOfRange` occupies in the numeric section (§12 deletes that
value):

```scala
val retentionFactorOutOfRange: String =
  "Mitigation factor must be greater than 0 and at most 1 — a mitigation reduces a figure, so it can neither increase it nor remove it entirely"
```

Three things about the wording follow the file's existing conventions rather than
being chosen here.

The range is spelled out longhand instead of with the `(exclusive)` or
`(inclusive)` suffix that `probabilityOutOfRange` and
`occurrenceProbabilityOutOfRange` use. Those suffixes describe an interval whose
two endpoints are both open or both closed. `RetentionFactor` is open at zero and
closed at one, which neither suffix can state. `shrinkFractionOutOfRange` is the
one existing message with the same shape and solves it the same way: "must be at
least 0 and below 1".

The clause after the dash is there because the range alone does not explain the
rejection a user will actually hit. Someone entering `0` means "this control
removes the risk", and a message giving only the bounds reads as an off-by-one
quibble rather than a statement that the model does not accept that claim (§11a).
The file uses this dash-clause form for exactly this case — a rule that is clear
but whose reason is not — in `percentilesMustBeStrictlyIncreasing` and
`quantilesMustBeStrictlyIncreasing`, and nowhere else.

The subject is "Mitigation factor" rather than "Factor" because every other
message in the file names the domain quantity: "Occurrence probability",
"Minimum loss", "Narrowing fraction".

**The three adopting signatures:**

```scala
// RiskLeafTransform.scala
final case class Scale(factor: RetentionFactor) extends LikelihoodTransform
final case class ScaleSeverity(factor: RetentionFactor) extends DistributionTransform

// ResultTransformSpec.scala
final case class ScaleLosses(factor: RetentionFactor) extends ResultTransformSpec

// RiskResultTransform.scala
def scaleLosses(factor: RetentionFactor): RiskResultTransform
```

Each codec switches from `refineNonNegativeDouble` to `refineRetentionFactor`.

### 11b. Scaladoc on the two severity scalings

Both operations multiply the loss by a factor, and on the distribution they are the
same operation: scaling a lognormal's two bounds by `f` multiplies every quantile by
`f`, which is what scaling each sampled loss by `f` does. Nothing in the code says
which one an author should reach for, so each gets a scaladoc stating its role and
naming the other.

`ScaleSeverity`, replacing its bullet in the `DistributionTransform` scaladoc in
`RiskLeafTransform.scala`:

```scala
 * - `ScaleSeverity` — the loss an occurrence produces is smaller, because a
 *   control changed what it costs. Applies before simulation, so the leaf is
 *   re-simulated from the scaled parameters. Lognormal: scales both CI bounds,
 *   multiplying every quantile by the factor and leaving the spread parameter
 *   unchanged. Expert: scales the quantile values, with the same effect on the
 *   fitted distribution. Broadcasts across a heterogeneous target set, and applies
 *   alongside `ResultTransformSpec.ScaleLosses` on the same node — that one scales
 *   what the entity bears of a loss this one has already reduced.
```

`ScaleLosses`, as a new scaladoc on the case class in `ResultTransformSpec.scala`:

```scala
  /** The entity bears this fraction of each loss; the rest falls elsewhere, by
    * transfer or by an agreed share. The loss itself is unchanged — this scales
    * cached trial outcomes at the read edge rather than re-simulating, and it is
    * the only proportional scaling that can be ordered against a deductible or a
    * cap. Applies alongside `DistributionTransform.ScaleSeverity` on the same
    * node — that one states the loss got smaller, this one states who pays for
    * it, and both factors apply. */
  final case class ScaleLosses(factor: RetentionFactor) extends ResultTransformSpec
```

Each names the other, because the two factors compound when both are present and
an author reading one needs to know the other exists. Neither comment cites this
plan or any decision label.

**Consequences, each of which is a required deliverable of this section.**

The probability clamp is deleted. `applyTo` currently computes
`math.min(1.0, leaf.probability * f)`. With the factor at most 1 and the
probability at most 1, the product cannot exceed 1, so the clamp is dead and is
removed. A factor above 1 is now rejected at the boundary with a message rather
than silently absorbed, which is the user-visible improvement.

The lognormal bound overflow becomes unreachable, so §2's `boundToLong` is deleted
and `scaleSeverityBounds` returns `(Long, Long)` with no failure channel. With
`min <= max <= Long.MaxValue` and `factor <= 1`, neither the floored nor the ceiled
product can exceed `Long.MaxValue`. §4's branch therefore needs no exception
handling, which removes the defect LBP-D-4 left behind.

`scaleLosses`'s own overflow guard is **kept**, and its test input changes. The
throw-instead-of-saturate behaviour was ruled during the architecture-decision
review of 15 September 2026 and landed in commit `ad2576c`; it is not revisited
here. It remains reachable, because `Long.MaxValue.toDouble` rounds up to a value
at or above `Long.MaxValue.toDouble`, so a loss at the top of the `Long` range
trips the guard even at a factor of exactly 1. The two tests that currently trigger
it with a factor of `2.0` are rewritten to use a loss at `Long.MaxValue` with a
factor of `1.0`. The method's scaladoc sentence "A factor above 1 can take a loss
past `Long.MaxValue`" is replaced by the actual remaining cause.

**Tests that must change**, all of them because their input becomes
unconstructible rather than because their assertion was wrong:

| File | Current input | Becomes |
|---|---|---|
| `RiskResultTransformSpec.scala` | `scaleLosses(2.0)` overflow case | loss at `Long.MaxValue`, factor `1.0` |
| `RiskResultTransformSpec.scala` | `ScaleLosses(2.0)` in a result-stage pipeline | same substitution |
| `RiskResultTransformSpec.scala` | `scaleLosses(0.0)` zero-loss filter case | deleted, and replaced by a boundary-rejection case: `refineRetentionFactor(0.0)` fails. The `filter(_._2 > 0)` path it exercised stays covered by the existing `scaleLosses(0.001)` case, where small losses still round to zero. |
| `RiskLeafTransformSpec.scala` | `LikelihoodTransform.Scale(4.0)` clamp case | a boundary-rejection case: `refineRetentionFactor(4.0)` fails |
| `RiskLeafTransformSpec.scala` | the closure property test's generator, `Gen.double(0.1, 2.0).map(_.refineUnsafe)` typed `Gen[Any, NonNegativeDouble]`, feeding `Scale(lf)` and `ScaleSeverity(df)` | `Gen.double(0.1, 1.0)` typed `Gen[Any, RetentionFactor]`. Two separate breaks: the element type is no longer the one either constructor accepts, and the range's upper half would make `refineUnsafe` throw at run time. |
| `CachedResultResolverSpec.scala` | `ScaleSeverity(ValidationUtil.refineNonNegativeDouble(factor).toOption.get)` in `leafScaleSeverity` | `refineRetentionFactor` in place of `refineNonNegativeDouble` |
| `MitigationApplicationSpec.scala` | the same construction in `leafScale` | the same substitution |
| `LossDistributionSpec.scala` | `scaleRecord(…, 2.0)` at two sites, one of them the layer-overflow case over `Map(1 -> Long.MaxValue)` | the overflow case keeps `Long.MaxValue` with factor `1.0`; the other case takes a factor at or below 1 |

The two `refineNonNegativeDouble` rows above are in the table rather than in the
compiles-unchanged list below for a reason worth stating, because the distinction
decides which list every such site belongs in. A literal factor is refined by the
compiler against whatever constraint the parameter declares, so `Scale(0.5)`
retypes for free. A factor refined at run time does not: `refineNonNegativeDouble`
returns a `NonNegativeDouble`, and an Iron refined type `A :| C` is declared a
subtype of `A` alone, so `NonNegativeDouble` is not a subtype of `RetentionFactor`
and the call does not compile. Both of these files pass a run-time-refined value.

Every other test that constructs one of these three operations passes a literal
factor of `0.5`, `0.8` or `1.0` and compiles unchanged: `MitigationScopeResolverSpec`,
`MitigationStalenessSpec`, `RiskTreeServiceLiveSpec`, `MitigationEntitySpec`,
`ResultTransformSpecSpec` and `ResultTransformInterpreterSpec`. They need no edit,
so they are not in the inventory.

**Documentation this invalidates.** `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`
§8.2 justifies `LossDistribution`'s fallible construction with the sentence
"Construction has to be able to fail, for one real reason: `ScaleLosses` takes a
factor that may exceed 1, so applying a transform can overflow." That reason no
longer holds once the factor is bounded. Construction stays fallible for a
different reason — aggregating children can overflow, which `NodeLosses` catches
and converts — and the passage is rewritten to say so. This document was ruled
authoritative over the architecture decision records, so the edit is listed here
rather than made silently.

---

## 12. Delete `DistributionTransform.Narrow` (LBP-D-9)

**Why.** `Narrow` contracts a distribution's spread toward its centre. In the
lognormal case it pivots on the geometric mean, which is exactly `exp(μ)`, so it
leaves μ untouched and multiplies σ by `1 − fraction`. In the expert case it pulls
each quantile toward the interpolated median by the same factor. Both preserve the
median and compress the spread.

Compressing a spread raises the low quantiles. For bounds of 1 000 and 50 000,
`Narrow(0.5)` moves the lower bound from 1 000 to 2 659 and the upper from 50 000
to 18 803. Small losses become less possible.

That is unavoidable for any refit that reduces σ, and `Narrow` is not unique in it:
the fit pins the 5th percentile at `minLoss` and the 95th at `maxLoss`, so reducing
σ pivots the distribution and something below the pivot must rise. What is specific
to `Narrow` is **which** number rises. It raises the author's own declared 5th
percentile, by 166% in this example, and leaves the median bit-identical. An
`Override` that lowers only the ceiling leaves the declared 5th percentile exactly
where the author put it, raises only the region below it where losses are smallest,
and halves the median. One moves a figure the author stated; the other does not.

It also moves the headline figure without moving the figure a reviewer checks. The
mean of a lognormal is `exp(μ + σ²/2)`, so σ enters it through a squared term
inside an exponential. `Narrow(0.9)` on those same bounds cuts the mean from 14 339
to 7 121 — a 50% reduction, the same as halving every loss — while leaving the
median at 7 071 exactly.

The capability survives the deletion. σ is a function of the ratio of the two
bounds and of nothing else, so lowering the ceiling alone reaches every spread the
contraction could. For any contraction fraction, this `Override` produces the
identical σ with the floor left in place:

```
minLoss' = minLoss
maxLoss' = minLoss × (maxLoss / minLoss) ^ (1 − fraction)
```

At fraction `0.5` on the worked leaf that is `Override(1 000, 7 071)`: σ is
`0.5945` either way, but the median falls from 7 071 to 2 659 and the mean from
14 339 to 3 173, against `Narrow(0.5)`'s unchanged median and 8 438. The
replacement is a better mitigation by every figure except the one `Narrow` moved,
which is the floor.

What `Override` cannot do is broadcast: it carries absolute bounds, so one
mitigation cannot apply a relative ceiling reduction across leaves with different
bounds. Whether that warrants its own parameterised transform is an open question
carried in `PLAN-RISKTRANSFORM.md`'s final step, together with the formula and the
user documentation it belongs in. It is not designed in this plan.

**What is deleted.** Every item exists only to serve `Narrow`.

| File | Deleted |
|---|---|
| `RiskLeafTransform.scala` | the `Narrow` case class; its encoder arm `case Narrow(fr) => Raw("narrow", …)`; its decoder arm `case Raw("narrow", None, Some(fr), None)`; its branch in `distributionFields` including both representations; its bullet in the `DistributionTransform` scaladoc; the `ShrinkFraction` import |
| `iron/OpaqueTypes.scala` | `type ShrinkFraction` and its comment |
| `iron/ValidationUtil.scala` | `refineShrinkFraction` |
| `iron/ValidationMessages.scala` | `shrinkFractionOutOfRange` |
| `RiskLeafTransformSpec.scala` | the `distribution component — Narrow` suite (four cases) and the combined likelihood-and-narrow case; the `ShrinkFraction` import |

The decoder's fallback arm already rejects an unknown operation —
`case other => Left(s"invalid distribution transform: op '${other.op}' …")` — so a
payload carrying `"op": "narrow"` is rejected with that message once the arm is
gone. No new error path is needed.

**No saved mitigation can carry the deleted operation.** A mitigation whose stored
JSON held `"op": "narrow"` would stop decoding once the arm is gone, and
`RiskTreeRepositoryIrmin.readNodesAt` fails the whole tree read on the first node
that will not decode, so one such mitigation would prevent its tree from opening.
No mitigations have been saved — there is no Irmin backend holding any — and
nothing under `examples/` carries the operation, so a backend populated from the
committed fixtures holds none either. There is also no mitigation-authoring view in
the client through which one could have been written; the only match for
"mitigation" under `modules/app/src` is a test file. The deletion is therefore an
ordinary step with no data condition attached.

**Verification.** `sbt "commonJVM/test; server/test"` and `sbt app/test` green,
with `RiskLeafTransformSpec` carrying one added case: a transform payload with
`"op": "narrow"` fails to decode with the unknown-operation message.

---

## 13. What the implementation found that the plan did not state

**One item in §7 is not reachable.** The second integration case — a mitigation
whose `ScaleSeverity` drives a bound below one whole unit, checked end to end —
cannot be written, because no HTTP endpoint accepts a mitigation. There is no
mitigation route in any endpoint file, which matches §5b's own observation that no
mitigation-authoring view exists. The §4 error is covered instead by a unit case in
`RiskLeafTransformSpec` that asserts both the error code and the formatted smallest
workable factor. The integration spec covers the three cases that are reachable,
plus a positive-bound control case proving the rejection is specific to zero.

**Three files needed an edit the plan's tables did not name.**
`IronTapirCodecs.scala` needed `Schema[PositiveLong]`, because `RiskNode`'s Tapir
schema is derived and a derived schema needs an instance for every field type; this
was the only compile error the retype produced, and the file was added to the
inventory. `RiskResultTransformSpec`'s `genScaleFactor` generator needed the same
retype and range narrowing as the generator §11 does name. `interpolatedMedian` in
`RiskLeafTransform.scala` was deleted: it was private and the deleted `Narrow`
expert branch was its only caller.

**The metalog symptom question in §10 is settled.** A quantile of exactly zero
makes the fit fail, and the failure arrives on the error channel as a `Left`. The
fitter does not return non-finite coefficients a caller could mistake for a real
distribution. No tree was producing meaningless figures; an expert leaf with a zero
quantile would have failed at simulation.

**A separate defect was found and fixed in the same pass.** The zio-json codec and
the Tapir schema describe the same wire format independently, and they disagreed.
`RiskLeaf` and `RiskPortfolio` carry `@jsonField("id")` and `@jsonField("name")`,
which zio-json honours, so the JSON says `id` and `name`. Tapir's derivation does
not read that annotation and named the two properties `safeId` and `safeName`, so
the generated OpenAPI document described fields that are not on the wire. Both
classes now carry Tapir's `@encodedName` alongside, and `RiskLeafSpec` has a
regression case comparing the derived schema's field names against the wire names.

**`NonNegativeDouble` and `refineNonNegativeDouble` have no production callers**
now that the three scale operations take a `RetentionFactor`. Only their own tests
exercise them. They are general-purpose and the plan does not ask for their
removal, so they were left in place.

---

## 14. Continuation — closing the remaining ways to express a zero residual

**Status:** Presented for approval. Not implemented. The approval token already
names this plan, and the inventory already lists every file but one; the
amendment needed is at the end of this section.

**Why this is a continuation and not a new plan.** §11a states the rule that no
mitigation may assert a risk has ceased to exist, and §13 records that the rule is
now in ADR-034 §6 while the type reaches only the three scale factors. The four
remaining paths are the same rule applied to the same transform algebra, so they
belong to this plan's workstream rather than a separate epic.

### 14a. The two kinds of annihilation, which need different mechanisms

The four paths divide on one question: does the parameter annihilate on its own,
or only against particular trial outcomes?

**Parameter-annihilating.** `LikelihoodTransform.Override(0.0)` sets the
occurrence probability to zero, so the leaf never occurs and every loss is zero,
whatever the outcomes were. `CapLosses(0)` caps every loss at zero. Each is a
zero residual decided entirely by the number the author wrote, so each is
closable by a type at the boundary (ADR-001).

**Data-dependent.** `applyDeductible(d)` annihilates when `d` is at or above
every loss in the node's outcomes, and `filterBelowThreshold(t)` when `t` is
above every loss. A deductible of 1 000 000 is ordinary for a node whose losses
run in the millions and annihilating for one whose losses run in the thousands.
No type can close these, because the parameter alone does not decide the outcome.

`scaleLosses` belongs to this group too, which the first draft of this section
missed. Its factor is already bounded as tightly as the methodology permits, and
it still annihilates: a factor of `0.001` takes losses of 100 and 300 to `0.1` and
`0.3`, both round to zero, and the sparse-storage filter drops them. The loss of
precision is in the rounding rather than the parameter, so no tightening of
`RetentionFactor` would reach it.

This is why §11a said each case needs its own judgement. The judgement turns out
to be the same for all four — a zero residual is not permitted — but the
enforcement point differs, and that is the part a shared bound could not supply.

### LBP-D-10 — `LikelihoodTransform.Override` probability floor — RULED: a new `(0, 1]` type

`Override` takes a new `ResidualProbability` rather than the existing
`OccurrenceProbability`, which is the closed interval `[0, 1]`.

The two must stay distinct, because a probability of zero is legitimate in one
place and not the other. An author may declare a leaf whose occurrence
probability is zero: that is a statement about the world, and
`OccurrenceProbability`'s own comment records the closed interval as deliberate
for exactly that reason. A mitigation may not *set* it to zero: that is a claim
that a control cannot fail. The rule in ADR-034 §6 binds mitigations, not
authors, so the narrower type belongs on the transform and the leaf field is
left alone.

### LBP-D-11 — `CapLosses` cap floor — RULED: `PositiveLong`

A cap is the largest loss the entity bears. A cap of zero states that the entity
bears nothing, which is a claim that the transfer cannot fail — the insurer can
deny the claim, exclude the peril, or become insolvent. It is the same defect as
a retention factor of zero, reached through a financial structure instead of a
fraction, so it is refused the same way.

`InsurancePolicy` already excludes it without a type: its cross-field rule is
`cap > deductible` and the deductible is non-negative, so the cap is at least 1
by arithmetic. Its field is retyped anyway, because `ResultTransformInterpreter`
turns an `InsurancePolicy` into `applyDeductible(d).andThen(capLosses(c))` and
`capLosses` now requires a `PositiveLong`.

### LBP-D-12 — the data-dependent pair — RULED: one check in `LossDistribution.decorate`

`decorate` is already the conversion boundary for a layer's arithmetic: it runs
the composed transform inside a `try` and turns an `ArithmeticException` into a
`CONSTRAINT_VIOLATION`. It is also the only place that holds the node's source
outcomes and the layer's result side by side, which is exactly what a
data-dependent check needs. One check there covers both remaining paths, covers
`CapLosses(0)` a second time as defense in depth, and covers any future transform
whose annihilating case nobody anticipated.

The check compares before against after, so a node that never had a loss is not
caught by it. A portfolio with no occurrences in any trial has a zero residual
that no mitigation produced, and reporting that as an elimination would be wrong.

### 14b. The new type

In `OpaqueTypes.scala`, after `RetentionFactor`:

```scala
// Probability a mitigation may assert for a risk that remains in the tree:
// strictly above 0 and at most 1. A mitigation reduces the chance of an
// occurrence and can never remove the possibility, because a residual of
// exactly zero asserts the control cannot fail. Distinct from
// OccurrenceProbability, whose closed interval is correct for a leaf's own
// declared probability: an author may state that an event never occurs, a
// mitigation may not state that it has been prevented.
type ResidualProbability = Double :| (Greater[0.0] & LessEqual[1.0])
```

The constraint is identical to `RetentionFactor`'s, so the two are the same type
to the compiler and are mutually assignable. They are kept separate for the
reader, because one names a probability and the other a retained fraction. No
Iron mechanism distinguishes them without an opaque type, and ADR-018's nominal
wrapper rule covers identifiers sharing an encoding rather than value
refinements.

### 14c. The two refiners

In `ValidationUtil.scala`, mirroring `refineRetentionFactor`:

```scala
// Refinement for a probability a mitigation may assert: above 0, at most 1
def refineResidualProbability(value: Double, fieldPath: String = "probability"): Either[List[ValidationError], ResidualProbability] = {
  value
    .refineEither[Greater[0.0] & LessEqual[1.0]]
    .left
    .map(_ => List(ValidationError(
      field = fieldPath,
      code = ValidationErrorCode.INVALID_RANGE,
      message = ValidationMessages.residualProbabilityOutOfRange
    )))
}

// Refinement for a loss cap: the largest loss the entity bears, at least 1
def refineLossCap(value: Long, fieldPath: String = "cap"): Either[List[ValidationError], PositiveLong] = {
  value
    .refineEither[Greater[0L]]
    .left
    .map(_ => List(ValidationError(
      field = fieldPath,
      code = ValidationErrorCode.INVALID_RANGE,
      message = ValidationMessages.lossCapMustBePositive
    )))
}
```

`refineLossCap` duplicates `refinePositiveLong`'s refinement and differs only in
its message. It exists because `valueMustBePositive` — "Value must be greater
than zero" — states the bound without the reason, and §11's wording rule applies
here for the same cause: a user entering `0` means "we bear nothing", and a
message giving only the bound reads as a quibble rather than a statement that the
model does not accept the claim.

### 14d. The three messages

In `ValidationMessages.scala`, the first two beside `retentionFactorOutOfRange`
in the numeric section and the third in the lognormal section's neighbourhood of
mitigation messages:

```scala
val residualProbabilityOutOfRange: String =
  "Mitigated probability must be greater than 0 and at most 1 — a mitigation reduces the chance a risk occurs, so it cannot remove the possibility entirely"

val lossCapMustBePositive: String =
  "Loss cap must be greater than 0 — a cap of zero would leave the entity bearing nothing, which states that the transfer cannot fail"

val mitigationEliminatesRisk: String =
  "This mitigation removes every loss from the risk, leaving a residual of exactly zero — a mitigation reduces exposure and cannot assert that a risk has ceased to exist. Raise the cap, lower the deductible, or lower the threshold; a risk that genuinely no longer exists is removed from the tree instead."
```

All three follow the dash-clause form the file uses for a rule whose reason is
not self-evident, which §11 established for `retentionFactorOutOfRange`.

### 14e. The adopting signatures

```scala
// RiskLeafTransform.scala
final case class Override(probability: ResidualProbability) extends LikelihoodTransform

// ResultTransformSpec.scala
final case class CapLosses(cap: PositiveLong) extends ResultTransformSpec
final case class InsurancePolicy private (deductible: NonNegativeLong, cap: PositiveLong)

object InsurancePolicy {
  def create(
    deductible: NonNegativeLong,
    cap: PositiveLong
  ): Validation[ValidationError, InsurancePolicy]
}

// RiskResultTransform.scala
def capLosses(cap: PositiveLong): RiskResultTransform
```

Four codec sites switch refiner: `LikelihoodTransform`'s `"override"` arm to
`refineResidualProbability`, `capLossesCodec` to `refineLossCap`, and
`insurancePolicyCodec`'s cap half to `refineLossCap` while its deductible half
stays `refineNonNegativeLong`.

### 14f. The elimination check

In `LossDistribution.scala`, `decorate` gains the check and one private helper.
The `try` block now binds the result before testing it, so the existing overflow
conversion is unchanged:

```scala
  def decorate(
    nodeId: NodeId,
    source: TrialOutcomes,
    provenance: Option[NodeProvenance],
    applied: List[MitigationApplicationRecord],
    run: TrialOutcomes => TrialOutcomes
  ): Validation[ValidationError, LossDistribution] =
    if (applied.isEmpty)
      Validation.succeed(LossDistribution(nodeId, source, source, Nil, provenance))
    else
      try {
        val mitigated = run(source)
        if (eliminatesEveryLoss(source, mitigated)) Validation.fail(layerEliminatesRisk(nodeId))
        else Validation.succeed(LossDistribution(nodeId, mitigated, source, applied, provenance))
      }
      catch { case _: ArithmeticException => Validation.fail(layerOverflow(nodeId)) }

  /** A layer eliminates the risk when the node had at least one loss and the
    * layer leaves none. A node whose outcomes held no loss to begin with is not
    * caught: its zero residual is what the simulation produced, not what a
    * mitigation asserted. */
  private def eliminatesEveryLoss(source: TrialOutcomes, mitigated: TrialOutcomes): Boolean =
    source.outcomes.exists(_._2 > 0L) && !mitigated.outcomes.exists(_._2 > 0L)

  private def layerEliminatesRisk(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"mitigatedResult.${nodeId.value}",
      code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.mitigationEliminatesRisk
    )
```

The error code is `CONSTRAINT_VIOLATION`, matching `layerOverflow` in the same
function, so the HTTP status and error shape of a layer-level failure stay
uniform.

### 14g. What compiles unchanged, and what needs an edit

Every existing cap and probability literal is already positive, so the two type
bounds break no literal call site. There is no `CapLosses(0)`, no `capLosses(0)`
and no `Override(0.0)` anywhere in the repository; every `Override` literal is
`0.05` and every cap literal is `1000000L` or similar. `InsurancePolicy.create`'s
three test call sites pass literals and retype for free.

Six run-time-refined sites break, for the reason §11 records: a value refined at
run time carries its own type, and neither `OccurrenceProbability` nor
`NonNegativeLong` is a subtype of the narrower type, because the constraints
differ. A seventh site is a production signature the plan's first draft missed.

| File | Current input | Becomes |
|---|---|---|
| `RiskResultTransform.scala` | `insurancePolicy(deductible: NonNegativeLong, cap: NonNegativeLong)` — a second cross-field helper beside `InsurancePolicy.create`, reached because it calls `capLosses(cap)` | `cap: PositiveLong`; its `cap > deductible` rule already guaranteed at least 1 |
| `MitigationApplicationSpec.scala` | `Override(refineOccurrenceProbability(prob)…)` in `leafOverride`, and `CapLosses(refineNonNegativeLong(cap)…)` in its cap record | `refineResidualProbability` and `refineLossCap` |
| `RiskResultTransformSpec.scala` | `genLoss.map(capLosses)`, where `genLoss` is `Gen[Any, NonNegativeLong]` | a new `genCap: Gen[Any, PositiveLong]` over the same 100–50 000 range |
| `RiskResultTransformSpec.scala` | two `val cap: NonNegativeLong` bindings in the `insurancePolicy` property tests | `val cap: PositiveLong`, refined at run time from the generated `Long` |
| `LossDistributionSpec.scala` | `CapLosses(refineNonNegativeLong(cap)…)` in `capRecord` | `refineLossCap` |
| `NodeLossesSpec.scala` | the same construction in its own `capRecord` | `refineLossCap` |
| `CachedResultResolverSpec.scala` | the same construction in its cap record | `refineLossCap` |

`NodeLossesSpec.scala` and `CachedResultResolverSpec.scala` are reached without
an inventory entry by the gate's same-module test allowance: both live under
`modules/server/src/test/` and the inventory lists
`modules/server/src/main/.../RiskResultTransform.scala`.

**Tests to add:**

- `ValidationUtilSpec` — `refineResidualProbability` accepts the smallest
  positive double and `1.0`, rejects `0.0`, anything above `1.0`, and
  not-a-number; `refineLossCap` accepts `1` and rejects `0`.
- `RiskLeafTransformSpec` — a boundary-rejection case for `Override`:
  `refineResidualProbability(0.0)` fails.
- `ResultTransformSpecSpec` — a `"capLosses"` payload carrying `0` fails to
  decode; an `"insurancePolicy"` payload carrying a cap of `0` fails to decode.
- `LossDistributionSpec` — a deductible at or above every loss is rejected with
  `CONSTRAINT_VIOLATION`; a threshold above every loss is rejected the same way;
  a layer that leaves one loss standing succeeds; a node whose source held no
  loss is **not** rejected, which is the case the before-and-after comparison
  exists to protect.

### 14h. Documentation this continuation updates

`ADR-034 §6` currently ends with a paragraph naming the four paths the type does
not reach. That paragraph is replaced by a statement of how each is now closed —
two by a type at the boundary, two by the layer check — because the ADR is a
current-state document and the gap will no longer exist. The `adr-constraints`
distillation's ADR-034 bullet and its `ADR-034 × ADR-001` interaction row are
updated in the same pass, under Plan Quality Gate item 3; the interaction row's
trap changes from "the rule is enforced on only three factors" to "the rule has
two enforcement points, and a new transform has to be checked against the
data-dependent one rather than assumed covered by a type".

### 14i. Verification plan

```
sbt "commonJVM/test; server/test"
sbt app/test
sbt "serverIt/test"    # leaked-state cleanup first (register-dev skill)
```

All four tiers green. Report pass/fail only. A red tier in any touched module
blocks done (G5).

### 14j. Inventory amendment required

Every file above is already on this plan's inventory except one. `LossDistribution.scala`
holds `decorate` and is not listed, so the amendment is a single line:

```
docs/dev/plans/PLAN-LOGNORMAL-BOUND-POSITIVITY.md
modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala
```

`ResultTransformSpecSpec.scala` needs no entry: it lives under
`modules/common/src/test/` and the inventory lists several
`modules/common/src/main/` files, so the gate's same-module test allowance
reaches it. `MitigationApplicationSpec.scala` and `LossDistributionSpec.scala`
are already listed by name.
