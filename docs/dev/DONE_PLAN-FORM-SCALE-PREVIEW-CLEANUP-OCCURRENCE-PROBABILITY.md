# Plan: Form Scale Conventions, Preview Cleanup, and OccurrenceProbability

**Status:** ✅ COMPLETE — All phases implemented and tested  
**Date:** 2026-06-10  
**Completed:** 2026-06-18  
**Tags:** frontend, laminar, preview-endpoint, domain-types, iron, scala-js

---

## Overview

Four sequential phases, each independently releasable:

| Phase | Scope | Trigger |
|-------|-------|---------|
| 1 — Conversion Methods + Filter Split | Frontend (`app`) only | None |
| 2 — `Distribution.create` strictly-increasing ordering checks | `common` — correctness fix | #5 (behaviour) |
| 3 — `DistributionShapeRequest` + Tree Wire Restructuring + Preview Cleanup | `common`, `server`, `app` | #1 (wire shapes), #4 (service sig), #5 |
| 4 — `OccurrenceProbability` | `common`, `server`, `app` | #4 (field type), #5 (boundary) |

Phases 2 and 3 are coupled: Phase 2 is the prerequisite for Phase 3 (the preview
`validate` call will delegate to `Distribution.create` and must inherit the
strictly-increasing check before Phase 3 can be completed).

Phases 1 and 4 are independent of each other and of Phases 2–3.

---

## Background: Current Scale Conventions

| Field | Entered by user | Domain / stored | Wire (tree API) | Wire (preview API, pre-Phase-3) |
|-------|-----------------|-----------------|-----------------|----------------------------------|
| Probability | 0–100 (`%`) | 0–1 | 0–1 | n/a |
| Percentiles | 0–100 (integers) | 0–1 | 0–1 | **0–100** ← bug |
| Quantiles | raw $M | raw $M | raw $M | raw $M |

After Phase 3, all wire scales are 0–1 for percentiles, matching the domain.

---

## Phase 1 — Conversion Methods + Filter Split

**Scope:** `app` module only. No ADR triggers.

### Motivation

The same `÷100` arithmetic appears as inline lambdas in multiple places:
`probabilityErrorRaw`, `refinedProbability`, `currentShapeValidation` (percentile
array), and `populateLeafForm`. The same `×100` rescaling appears as inline arithmetic
in `populateLeafForm`. These should all route through named, tested methods.

There is **no `domainToWirePct` method**. After Phase 3 the preview wire accepts
percentiles at 0–1 (matching the domain), so the `* 100.0` in `toPreviewRequest` is
deleted outright. No wire-direction conversion method is needed or created.

### Step 1.1 — `object RiskLeafFormState` companion

**File:** `modules/app/src/main/scala/app/state/RiskLeafFormState.scala`

Add companion object:

```scala
object RiskLeafFormState:
  /** Convert a 0–100 percent-scale value (as entered in the form) to the
    * 0–1 domain scale used in [[Distribution]] and [[OccurrenceProbability]].
    *
    * Used for both scalar probability (`pctToDomain(pct)`) and
    * per-element percentile arrays (`arr.map(pctToDomain)`).
    */
  def pctToDomain(pct: Double): Double = pct / 100.0

  /** Convert a 0–1 domain value to its 0–100 display string, rounded to
    * `decimals` decimal places using half-up rounding.
    *
    * - `decimals = 0` → percentiles (integers: "10", "50", "90")
    * - `decimals = 2` → probability ("20.50")
    *
    * Uses [[BigDecimal]] to eliminate floating-point noise
    * (e.g., `0.1 * 100 = 10.000000000000001`).
    */
  def domainToDisplayPct(p: Double, decimals: Int): String =
    BigDecimal(p * 100.0)
      .setScale(decimals, scala.math.BigDecimal.RoundingMode.HALF_UP)
      .underlying.stripTrailingZeros.toPlainString
```

Note: `.setScale(2)` followed by `.stripTrailingZeros` preserves meaningful trailing
zeros only when the scale is 0 (percentiles: `"10"` not `"10.00"`). For scale 2
(probability) it produces `"20.50"` → `"20.5"` stripping the insignificant zero.
The test assertion for probability must therefore use `startsWith` or `==` against
the stripped form. Decide at implementation time whether `"20.5"` or `"20.50"` is
the preferred display — the `decimals` parameter controls the maximum, not the minimum.
If fixed trailing zeros are required (e.g. always show 2 dp), remove `.stripTrailingZeros`.

### Step 1.2 — Filter split

**File:** `modules/app/src/main/scala/app/state/RiskLeafFormState.scala`

`arrayFilter` collapses two semantically different inputs — integer percentiles and
decimal quantiles — into one filter, hiding the distinction. Replace it with three
purpose-specific filters.

**Rationale for 0 dp on percentiles:** Decision-analysis standard (Cooke, SHELF,
Keelin 2016) — expert elicitation anchors are always integers (P10, P50, P90).
Fractional percentiles imply false precision. The filter enforces this by rejecting
decimal points entirely.

**Rationale for 2 dp on probability and quantiles:** `40.12` is meaningful for
occurrence probability; `40.123` implies precision beyond what is useful. Loss amounts
in $M may have cents-level precision (2 dp).

```scala
/** Filter for percentile field: digits, commas, and spaces only.
  * Percentiles are entered as integers (0 dp); decimal points are rejected
  * per decision-analysis convention (Cooke, SHELF, Keelin 2016). */
val percentilesFilter: String => Boolean =
  _.forall(c => c.isDigit || c == ',' || c == ' ')

/** Filter for quantiles field: digits, commas, spaces, and decimal points.
  * Prevents consecutive dots (guards against "1..2" typos). Max 2 dp is
  * enforced at validation time, not at the filter level, because the filter
  * operates on the whole comma-separated string. */
val quantilesFilter: String => Boolean = { s =>
  val validChars = s.forall(c => c.isDigit || c == ',' || c == ' ' || c == '.')
  val noConsecutiveDots = !s.contains("..")
  validChars && noConsecutiveDots
}

/** Filter for probability field: digits and single decimal point, max 2 dp.
  * Enforced at the character level so the user cannot type a third decimal place. */
val probabilityFilter: String => Boolean = { s =>
  val hasSingleDot = s.count(_ == '.') <= 1
  val allValidChars = s.forall(c => c.isDigit || c == '.')
  val maxTwoDp = s.indexOf('.') match
    case -1  => true
    case idx => s.length - idx - 1 <= 2
  hasSingleDot && allValidChars && maxTwoDp
}
```

Remove `arrayFilter` entirely.

### Step 1.3 — Update `currentShapeValidation` to use named conversions

In `RiskLeafFormState`, the percentile values are parsed from 0–100 form input
and must be divided by 100 before being passed to `Distribution.create`.
Replace the inline `_ / 100.0` lambda with `pctToDomain`:

```scala
// Before:
val normalisedPercentiles = parsed.map(_ / 100.0)
// After:
val normalisedPercentiles = parsed.map(pctToDomain)
```

Update `probabilityErrorRaw` and `refinedProbability` to use `pctToDomain` for the
scalar division:

```scala
ValidationUtil.refineProbability(pctToDomain(pct))
```

**Note:** Phase 4 supersedes the `refineProbability` call here — it will be replaced
with `refineOccurrenceProbability(pctToDomain(pct))`. The Phase 1 implementation is
correct in isolation; Phase 4 is a follow-on change to the same line.

### Step 1.4 — Update `TreeBuilderState.populateLeafForm`

**File:** `modules/app/src/main/scala/app/state/TreeBuilderState.scala`

Replace inline arithmetic with named methods:

```scala
// Probability (2 dp display)
state.probabilityVar.set(RiskLeafFormState.domainToDisplayPct(leaf.probability, 2))

// Percentiles (0 dp — integers)
state.percentilesVar.set(
  leaf.percentiles
    .map(_.map(RiskLeafFormState.domainToDisplayPct(_, 0)).mkString(", "))
    .getOrElse("")
)
```

### Step 1.5 — Update view call sites for filter rename

**Files:** `RiskLeafFormView.scala` (and any other view using `arrayFilter`)

Replace `state.arrayFilter` with `state.percentilesFilter` for the percentiles input
element and `state.quantilesFilter` for the quantiles input element.

### Step 1.6 — Tests

Update `TreeBuilderStateSpec` assertions:
- Probability: `state.probabilityVar.now() == "20.00"` (BigDecimal-rounded, 2 dp)
- Percentiles: `state.percentilesVar.now() == "10, 50, 90"` (integers, 0 dp)

Add unit tests for `RiskLeafFormState` companion methods:
- `pctToDomain(50.0) == 0.5`
- `domainToDisplayPct(0.1, 0) == "10"` (no noise)
- `domainToDisplayPct(0.205, 2) == "20.50"`

### Phase 1 — Files touched

| File | Change |
|------|--------|
| `app/state/RiskLeafFormState.scala` | Add companion; split filters; use `pctToDomain` in signals |
| `app/state/TreeBuilderState.scala` | Use named methods in `populateLeafForm` |
| `app/views/RiskLeafFormView.scala` | Update filter references |
| `app/state/TreeBuilderStateSpec.scala` | Precision assertions |

---

## Phase 2 — `Distribution.create` Strictly-Increasing Ordering Checks

**Scope:** `common` module.  
**Trigger:** #5 — behaviour change to existing tree creation/update endpoints.

### Motivation

`DistributionPreviewRequest.validate` checks that quantiles are strictly increasing
but `Distribution.create` has neither a quantile nor a percentile ordering check.
Non-monotone inputs are currently rejected only at the Metalog fitter level
(`fromPercentilesUnsafe`) — never reported back as a typed `ValidationError` with
a field path. Percentiles `[0.9, 0.1, 0.5]` are equally invalid and equally
silent. This phase closes both gaps at the domain level.

### Step 2.1 — Add private helper `requireStrictlyIncreasing` to `Distribution` companion

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala`

This follows the established pattern in the same module: `RiskNode` uses
`private def validateExpertMode(…, fieldPrefix: String)` and
`private def validateLognormalMode(…, fieldPrefix: String)`; `TreeBuilderLogic`
uses `private def requireCond(cond, field, code, message)`. The helper computes
the structural condition internally and returns `Validation[ValidationError, Unit]`:

```scala
private def requireStrictlyIncreasing(
  arr:     Array[Double],
  field:   String,
  message: String
): Validation[ValidationError, Unit] =
  if arr.length >= 2 && !arr.sliding(2).forall { case Array(a, b) => a < b; case _ => true } then
    Validation.fail(ValidationError(field, ValidationErrorCode.INVALID_COMBINATION, message))
  else Validation.succeed(())
```

### Step 2.2 — Call it from `crossV` for both arrays

Inside the `case "expert"` branch of `crossV`, after the per-element `elementV`
construction and before `termsCheck`, add:

```scala
val monotonicPctCheck = requireStrictlyIncreasing(
  pct, s"$fieldPrefix.percentiles", ValidationMessages.percentilesMustBeStrictlyIncreasing)

val monotonicQtCheck = requireStrictlyIncreasing(
  q, s"$fieldPrefix.quantiles", ValidationMessages.quantilesMustBeStrictlyIncreasing)

Validation.validateWith(elementV, monotonicPctCheck, monotonicQtCheck, termsCheck)((_, _, _, _) => ())
```

### Step 2.3 — Add message to `ValidationMessages`

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`

```scala
val percentilesMustBeStrictlyIncreasing: String =
  "Percentiles must be strictly increasing"
```

(`quantilesMustBeStrictlyIncreasing` already exists.)

### Step 2.4 — Tests

**File:** `modules/common/src/test/scala/com/risquanter/register/domain/data/DistributionSpec.scala`
(create if absent; follow `RiskTreeRequestsSpec` pattern)

- Non-monotone quantiles `Array(5000.0, 1000.0, 25000.0)` → failure `INVALID_COMBINATION` on `"request.quantiles"`
- Non-monotone percentiles `Array(0.9, 0.1, 0.5)` → failure `INVALID_COMBINATION` on `"request.percentiles"`
- Both failing simultaneously → both errors accumulated (not short-circuited)
- Valid monotone inputs → success

### Phase 2 — Files touched

| File | Change |
|------|--------|
| `common/domain/data/Distribution.scala` | Add `requireStrictlyIncreasing` helper; two monotone checks in `crossV` |
| `common/iron/ValidationMessages.scala` | Add `percentilesMustBeStrictlyIncreasing` |
| `common/test/…/DistributionSpec.scala` | New or extended test file |

---

## Phase 3 — `DistributionShapeRequest` + Tree Wire Restructuring + Preview Cleanup

**Prerequisite:** Phase 2 complete.  
**Triggers:** #1 (wire shapes change for both tree and preview endpoints), #4 (service method signature), #5 (service behaviour).

### What this phase does

Three coupled changes that must land together:

1. **Rename** `DistributionPreviewRequest` → `DistributionShapeRequest`. One type, three uses.
2. **Restructure** `RiskLeafDefinitionRequest` and `RiskLeafUpdateRequest` to nest
   `distributionShape: DistributionShapeRequest` instead of carrying distribution
   fields inline alongside `probability`.
3. **Fix preview wire scale** — percentiles 0–1, delegate validation to `Distribution.create`.

They are coupled because the rename must happen before the tree DTOs can reference
the type, and both touch the frontend in the same commit.

### Motivation

The probability / distribution-shape separation already exists internally:
`refineLeafDefs` produces `(OccurrenceProbability, Distribution)` and `buildNodes`
receives `Map[SafeName, (OccurrenceProbability, Distribution)]`. The wire DTOs
currently express both concerns as flat siblings, making the boundary invisible at
the API level and allowing the shadow stack to regrow as fields drift independently.
`DistributionShapeRequest` is already the correct shape (it is `DistributionPreviewRequest`
without renaming); nesting it makes the separation explicit in the contract.

### Current vs Target Data Flow

**Preview endpoint — current:**
```
Frontend: draft.percentiles.map(_ * 100.0)  [0-1 → 0-100]
  → Wire: DistributionPreviewRequest { percentiles 0-100 }
  → Controller: DistributionPreviewRequest.validate(req) → Validation[.., DistributionPreviewRequest]
  → Service: preview(req: DistributionPreviewRequest)
    → rawPercentiles.map(_ / 100.0)  [0-100 → 0-1]
    → MetalogDistribution.fromPercentilesUnsafe(...)
```

**Preview endpoint — after Phase 3:**
```
Frontend: draft.percentiles  [0-1, no conversion]
  → Wire: DistributionShapeRequest { percentiles 0-1 }
  → Controller: DistributionShapeRequest.validate(req) → Validation[.., Distribution]
  → Service: preview(dist: Distribution)
    → dist.percentiles  [0-1, already validated]
    → MetalogDistribution.fromPercentilesUnsafe(...)
```

**Tree endpoint — current (leaf portion):**
```
Frontend: RiskLeafDefinitionRequest { probability, distributionType, percentiles, ... }  (flat)
  → Wire: flat fields
  → refineLeafDefs: l.distributionType, l.percentiles, ...
```

**Tree endpoint — after Phase 3 (leaf portion):**
```
Frontend: RiskLeafDefinitionRequest { probability, distributionShape: DistributionShapeRequest }
  → Wire: nested distributionShape object
  → refineLeafDefs: l.distributionShape.distributionType, l.distributionShape.percentiles, ...
```

### Step 3.1 — Rename file and type

**Rename file:** `DistributionPreviewRequest.scala` → `DistributionShapeRequest.scala`  
**Rename type:** `DistributionPreviewRequest` → `DistributionShapeRequest` throughout.

Fields unchanged: `{distributionType, percentiles, quantiles, terms, minLoss, maxLoss}`.

Update class Scaladoc: percentiles are `0–1` (domain scale); the form's 0–100 display
values are divided by 100 before serialising.

### Step 3.2 — Fix `Schema.any` workaround

**File:** `DistributionShapeRequest.scala`

The current `Schema.any[DistributionPreviewRequest]` is a workaround that produces
an untyped `{}` in the OpenAPI output, losing contract documentation for the preview
endpoint. The correct fix is to provide a `Schema[Array[Double]]` instance (absent
from Tapir's stdlib) and derive the schema properly:

```scala
object DistributionShapeRequest:
  // Array[Double] has no implicit Tapir Schema — provide one so Schema.derived works.
  private given Schema[Array[Double]] = Schema.array[Double](Schema.schemaForDouble)
  given schema: Schema[DistributionShapeRequest] = Schema.derived
  given codec:  JsonCodec[DistributionShapeRequest] = DeriveJsonCodec.gen
```

This is purely additive (no behaviour change; only OpenAPI output improves). No trigger.

**Tests for this step:**

*Regression — existing behaviour unchanged:*
- A valid `DistributionShapeRequest` JSON string round-trips through `codec` without
  error (encode → decode → same values).
- `DistributionShapeRequest.validate` still returns `Success` for a valid expert request
  and `Failure` for an invalid one (identical assertions to existing
  `DistributionShapeRequestSpec` happy/sad paths).

*New behaviour — schema is no longer `any`:*
- `schema.schemaType` is not `SProduct` with zero fields (i.e., not the `{}` that
  `Schema.any` produces). Assert that `schema.schemaType` is an `SProduct` and that
  `schema.name` is defined.
- `schema` contains a field named `"distributionType"` of type `SString`.
- `schema` contains a field named `"percentiles"` whose type is an array schema, not
  `SProduct({})`.

Pattern: follow the `given` derivation test pattern already used in
`IronTapirCodecsSpec` or equivalent schema-level tests in the `server` module.

### Step 3.2 — Restructure tree leaf DTOs

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`

```scala
final case class RiskLeafDefinitionRequest(
  name:              String,
  parentName:        Option[String],
  probability:       Double,
  distributionShape: DistributionShapeRequest
)
```

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeUpdateRequest.scala`

```scala
final case class RiskLeafUpdateRequest(
  id:                String,
  name:              String,
  parentName:        Option[String],
  probability:       Double,
  distributionShape: DistributionShapeRequest
)
```

`RiskLeafUpdateRequest` retains `id` — it is intentionally distinct from
`RiskLeafDefinitionRequest` per ADR-017 (existing nodes carry a server-assigned id;
new nodes do not).

### Step 3.3 — Update `refineLeafDefs` and `refineExistingLeaves`

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`

Field access changes; validation logic is identical:

```scala
Distribution.create(
  l.distributionShape.distributionType,
  l.distributionShape.minLoss,
  l.distributionShape.maxLoss,
  l.distributionShape.percentiles,
  l.distributionShape.quantiles,
  base,
  l.distributionShape.terms
)
```

Same change in `refineExistingLeaves` for `RiskLeafUpdateRequest`.

### Step 3.4 — Change `DistributionShapeRequest.validate` return type

**File:** `DistributionShapeRequest.scala`

Replace the entire body with delegation to `Distribution.create`:

```scala
def validate(req: DistributionShapeRequest): Validation[ValidationError, Distribution] =
  Distribution.create(
    distributionType = req.distributionType,
    minLoss          = req.minLoss,
    maxLoss          = req.maxLoss,
    percentiles      = req.percentiles,
    quantiles        = req.quantiles,
    terms            = req.terms
  )
```

All cross-field rules (both monotone ordering checks, element range, length match,
terms limit) are owned by `Distribution.create` after Phase 2. The entire duplicate
cross-field body of the old `validate` is deleted.

### Step 3.5 — Update `DistributionPreviewService` to accept `Distribution`

**File:** `modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala`

```scala
trait DistributionPreviewService:
  def preview(dist: Distribution): IO[ValidationFailed, DistributionPreviewResponse]
```

In `previewExpert`, replace raw request field access and `/ 100.0` normalisation:

```scala
val percentiles   = dist.percentiles.getOrElse(Array.empty[Double])  // already 0-1
val quantiles     = dist.quantiles.getOrElse(Array.empty[Double])
val resolvedTerms = dist.terms.map(_.toInt).getOrElse(math.min(percentiles.length, 4))
```

In `previewLognormal`, replace `req.minLoss` / `req.maxLoss` with
`dist.minLoss.map(_.toLong)` / `dist.maxLoss.map(_.toLong)`.

### Step 3.6 — Update controller (type flows through, no logic change)

**File:** `DistributionPreviewController.scala`

The `for`-comprehension `validate(...).toZIOValidation.flatMap(previewService.preview)`
continues to compile — `validate` returns `Validation[.., Distribution]` which flows
into `preview(dist: Distribution)`. No logic change.

### Step 3.7 — Update frontend

**`DistributionChartView.toPreviewRequest`** — return type changes to `DistributionShapeRequest`;
`* 100.0` deleted (wire scale now matches domain):

```scala
private def toPreviewRequest(draft: Distribution): DistributionShapeRequest =
  DistributionShapeRequest(
    distributionType = draft.distributionType.toString,
    percentiles      = draft.percentiles,   // 0-1, no conversion
    quantiles        = draft.quantiles,
    terms            = draft.terms.map(_.toInt),
    minLoss          = draft.minLoss.map(identity),
    maxLoss          = draft.maxLoss.map(identity)
  )
```

There is **no `domainToWirePct`** — the `* 100.0` is deleted, not replaced.
After Phase 3 the wire scale equals the domain scale; no conversion layer exists
or is needed in this direction.

**`TreeBuilderState.toLeafRequest`** — build the nested object:

```scala
private def toLeafRequest(draft: LeafDraft): RiskLeafDefinitionRequest =
  RiskLeafDefinitionRequest(
    name              = draft.name.value,
    parentName        = draft.parent.map(_.value),
    probability       = draft.probability,
    distributionShape = DistributionShapeRequest(
      distributionType = draft.distribution.distributionType.toString,
      percentiles      = draft.distribution.percentiles,
      quantiles        = draft.distribution.quantiles,
      terms            = draft.distribution.terms.map(_.toInt),
      minLoss          = draft.distribution.minLoss.map(identity),
      maxLoss          = draft.distribution.maxLoss.map(identity)
    )
  )
```

**`TreeBuilderState.toLeafUpdateRequest`** — identical, with `id` field added.

### Step 3.8 — Tests

**`DistributionShapeRequestSpec`** (renamed from `DistributionPreviewRequestSpec`):
- Update all `expertReq` fixtures: `Array(5.0, 50.0, 95.0)` → `Array(0.05, 0.50, 0.95)`
- Add: non-monotone quantiles → `INVALID_COMBINATION` on `request.quantiles`
- Add: non-monotone percentiles → `INVALID_COMBINATION` on `request.percentiles`
- Add: both failing simultaneously → both errors accumulated
- Add: `validate` returns `Distribution` — assert `isSuccess` and `distributionType` field

**`RiskTreeRequestsSpec`** — update `validLeafDef` and `validLeafUpdate` helpers:

```scala
private def validLeafDef(name: String, parent: Option[String]) =
  RiskLeafDefinitionRequest(
    name              = name,
    parentName        = parent,
    probability       = 0.8,
    distributionShape = DistributionShapeRequest(
      distributionType = "lognormal",
      percentiles = None, quantiles = None, terms = None,
      minLoss = Some(1000L), maxLoss = Some(5000L)
    )
  )
```

**Integration smoke tests:**
- `POST /api/distribution/preview` `{"percentiles": [0.05, 0.5, 0.95], ...}` → 200
- Same with `[5, 50, 95]` (old scale) → 422 `INVALID_RANGE` on `request.percentiles[0]`
- `POST /api/trees` with nested `distributionShape` → 201
- Same with flat distribution fields at leaf root level → 400 (JSON codec rejects unknown fields)

### Phase 3 — Files touched

| File | Change |
|------|--------|
| `common/http/requests/DistributionPreviewRequest.scala` → `DistributionShapeRequest.scala` | Rename type; fix `Schema.any` → `Schema.derived`; doc update; `validate` returns `Distribution`; percentiles 0–1 |
| `common/http/requests/RiskTreeDefinitionRequest.scala` | `RiskLeafDefinitionRequest` nests `distributionShape: DistributionShapeRequest` |
| `common/http/requests/RiskTreeUpdateRequest.scala` | `RiskLeafUpdateRequest` nests `distributionShape: DistributionShapeRequest` |
| `common/http/requests/RiskTreeRequests.scala` | `refineLeafDefs`, `refineExistingLeaves` read nested fields |
| `server/services/DistributionPreviewService.scala` | Method sig `Distribution`; remove `/ 100.0` |
| `server/controllers/DistributionPreviewController.scala` | No logic change; type flows through |
| `app/views/DistributionChartView.scala` | `toPreviewRequest` returns `DistributionShapeRequest`; delete `* 100.0` |
| `app/state/TreeBuilderState.scala` | `toLeafRequest`, `toLeafUpdateRequest` build nested `distributionShape` |
| `server/test/…/DistributionPreviewRequestSpec.scala` → `DistributionShapeRequestSpec.scala` | Rename; update fixtures; new tests |
| `common/test/…/RiskTreeRequestsSpec.scala` | Update `validLeafDef`, `validLeafUpdate` helpers |

---

## Phase 4 — `OccurrenceProbability`

**Scope:** `common`, `server`, `app`.  
**Triggers:** #4 (field type changes), #5 (behaviour: `0` and `1` become valid).

### Motivation

`RiskLeaf.probability` represents **occurrence probability** — the likelihood that
a risk event occurs at all. This is semantically distinct from a parameter in a
probability distribution: it should allow `0` (never occurs) and `1` (always occurs).
The current `Probability = Double :| (Greater[0.0] & Less[1.0])` (exclusive open
interval) rejects both endpoints without any validation message distinguishing this
from a distribution percentile constraint.

`OccurrenceProbability = Double :| (GreaterEqual[0.0] & LessEqual[1.0])` closes this
gap and makes the intent unambiguous at every call site.

### Step 4.1 — Add type to `OpaqueTypes.scala`

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`

```scala
// Occurrence probability for a risk leaf: can be 0 (never) or 1 (always)
type OccurrenceProbability = Double :| (GreaterEqual[0.0] & LessEqual[1.0])
```

The existing `Probability = Double :| (Greater[0.0] & Less[1.0])` is **not removed** —
it may still be used internally (e.g., as a distribution percentile value). Audit call
sites before removing it.

### Step 4.2 — Add `refineOccurrenceProbability` to `ValidationUtil`

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`

```scala
def refineOccurrenceProbability(v: Double, field: String): Either[List[ValidationError], OccurrenceProbability] =
  v.refineEither[GreaterEqual[0.0] & LessEqual[1.0]]
    .left.map(_ => List(ValidationError(field, ValidationErrorCode.INVALID_RANGE,
      ValidationMessages.occurrenceProbabilityOutOfRange)))
```

### Step 4.3 — Add message to `ValidationMessages`

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`

```scala
val occurrenceProbabilityOutOfRange: String =
  "Occurrence probability must be between 0 and 1 (inclusive)"
```

### Step 4.4 — Propagate through domain and server stack

**`RiskLeaf.probability` field type:**
- Change from `Probability` to `OccurrenceProbability`
- Update `RiskLeaf.fromValidated` probability parameter type
- Update `RiskNode.create` probability parameter type
- **Update `RiskLeafRaw` JSON decoder path:** `RiskLeafRaw` is the internal raw case
  class used by `RiskLeaf`'s `JsonDecoder`. Its decoder calls `refineProbability`
  to construct the validated `RiskLeaf`. This call must be changed to
  `refineOccurrenceProbability` — it is the same code path as `fromValidated` but
  reached via JSON decode, not the service layer. Omitting this means persisted
  leaves with `probability = 0` or `= 1` will fail to deserialise after Phase 4.
- Update `refineLeafDefs` in `RiskTreeRequests` where `ValidationUtil.refineProbability`
  is called for incoming wire requests: replace with `refineOccurrenceProbability`

**`RiskSampler.fromDistribution` (simulation):**
- Change `occurrenceProb: Probability` parameter to `occurrenceProb: OccurrenceProbability`

**`Simulator.createSamplerFromLeaf`:**
- Update the call site that extracts `leaf.probability` and passes it to `RiskSampler.fromDistribution`

**`RiskTreeServiceLive.buildNodes`:**
- The map signature `Map[SafeName, (Probability, Distribution)]` changes to
  `Map[SafeName, (OccurrenceProbability, Distribution)]`
- Update `leafOccurrenceAndShape` construction where `ValidationUtil.refineProbability` is called:
  replace with `refineOccurrenceProbability`

### Step 4.5 — Propagate through app module

**`LeafDraft.probability`:**
- Currently typed as `Probability` (Iron-refined, not `String`). Change to `OccurrenceProbability`.
- `addLeaf` and `updateLeaf` in `TreeBuilderState` receive `probability: Probability` — change
  parameter type to `OccurrenceProbability` at both call sites.

**`RiskLeafFormState`:**
- `refinedProbability`: replace `refineProbability` call with `refineOccurrenceProbability`
  (this supersedes the Phase 1 `refineProbability(pctToDomain(pct))` change — same line,
  different refine function)
- `probabilityErrorRaw`: update error message to `ValidationMessages.occurrenceProbabilityOutOfRange`
  expressed on the display scale: `"Occurrence probability must be between 0 and 100 (inclusive)"`

### Step 4.6 — Tests

Update boundary tests:
- `OccurrenceProbability(0.0)` → valid
- `OccurrenceProbability(1.0)` → valid
- `OccurrenceProbability(-0.001)` → invalid
- `OccurrenceProbability(1.001)` → invalid

Update `RiskLeafSpec`, `RiskTreeDefinitionRequestsSpec`, `SimulationResponseSpec`
fixtures that use `probability = 0.8` etc. — these stay valid; no changes needed to
values, only to type annotations if any are explicit.

Update `TreeBuilderStateSpec` if it asserts on boundary values.

### Phase 4 — Files touched

| File | Change |
|------|--------|
| `common/iron/OpaqueTypes.scala` | Add `OccurrenceProbability` type |
| `common/iron/ValidationUtil.scala` | Add `refineOccurrenceProbability` |
| `common/iron/ValidationMessages.scala` | Add `occurrenceProbabilityOutOfRange` |
| `common/domain/data/RiskLeaf.scala` | Field type change; `RiskLeafRaw` decoder path: `refineProbability` → `refineOccurrenceProbability` |
| `common/domain/data/RiskNode.scala` | `create` param type |
| `server/services/RiskTreeServiceLive.scala` | Map type; refine call |
| `server/simulation/RiskSampler.scala` | `occurrenceProb` param type |
| `server/simulation/Simulator.scala` | Call site update |
| `app/state/RiskLeafFormState.scala` | `refinedProbability`; `probabilityErrorRaw` message |
| `common/test/…/OpaqueTypesSpec.scala` | New boundary tests |

---

## Consistency Check

After all four phases, the following invariants must hold throughout the codebase.
These are the verification targets for code review, not just for tests.

### Scale invariants

| Location | Probability | Percentiles | Quantiles |
|----------|-------------|-------------|-----------|
| Form input (`Var[String]`) | 0–100, 2 dp max | 0–100, integers | raw $M, 2 dp max |
| Form filter | 2 dp enforced | no decimal point allowed | 2 dp |
| Domain (`Distribution`, `OccurrenceProbability`) | 0–1 | 0–1 | raw $M |
| Wire — tree API | 0–1 | 0–1 | raw $M |
| Wire — preview API | n/a | 0–1 | raw $M |
| Conversion methods | `pctToDomain` / `domainToDisplayPct` only | same | none needed |

No inline `÷100` or `×100` lambdas remain in production code. `domainToWirePct` does
not exist (the preview wire now matches domain scale; no conversion layer is needed).

### Type invariants

| Concept | Type | Interval |
|---------|------|----------|
| Leaf occurrence probability | `OccurrenceProbability` | `[0, 1]` closed |
| Distribution percentile element | validated inline in `Distribution.create` as `(0, 1)` open | `(0, 1)` open |
| `Probability` (legacy) | retained only if still referenced for distribution-internal use | `(0, 1)` open |

### Validation ownership

| Check | Owned by |
|-------|----------|
| Percentile element range `(0, 1)` | `Distribution.create` `crossV` |
| Percentiles strictly increasing | `Distribution.create` via `requireStrictlyIncreasing` (after Phase 2) |
| Quantiles strictly increasing | `Distribution.create` via `requireStrictlyIncreasing` (after Phase 2) |
| Terms ≤ anchor count | `Distribution.create` `crossV` |
| Preview cross-field rules | `DistributionShapeRequest.validate` → delegates to `Distribution.create` (after Phase 3) |
| Tree leaf cross-field rules | `refineLeafDefs` → `Distribution.create` (unchanged path, same delegate) |
| Occurrence probability `[0, 1]` | `ValidationUtil.refineOccurrenceProbability` |

No cross-field rule exists in two places simultaneously after all phases are complete.

---

## Success Criteria

### How the final solution looks and behaves

**Form (leaf creation / editing):**
- Probability field: user types `"20"` or `"20.5"` — filter rejects a third decimal place.
  On submit the value `20.5` is converted to domain `0.205` via `pctToDomain`. On
  populate (editing an existing leaf with `probability = 0.205`) the field shows `"20.50"`.
- Probability field accepts `"0"` (0%) and `"100"` (100%) after Phase 4 (`OccurrenceProbability`).
- Percentile field: user types `"10, 50, 90"` — filter rejects decimal points.
  On submit `[10.0, 50.0, 90.0]` → `[0.1, 0.5, 0.9]` via `arr.map(pctToDomain)`.
  On populate: `[0.1, 0.5, 0.9]` → `"10, 50, 90"` via `arr.map(domainToDisplayPct(_, 0)).mkString(", ")`.
- Non-monotone quantiles (e.g., `"5000, 1000, 25000"`) produce an inline field error
  below the quantiles input on submit (not a banner), routed via `setSubmitFieldError`.
- Distribution preview chart updates correctly as the user types percentiles/quantiles
  (debounced 400 ms), sending them at 0–1 scale.

**Preview endpoint (after Phase 3):**
- `POST /api/distribution/preview` with body `{"distributionType": "expert", "percentiles": [0.05, 0.5, 0.95], "quantiles": [1000, 5000, 25000]}` → 200 chart data.
- Same request with `percentiles: [5, 50, 95]` (old 0–100 scale) → 422 INVALID_RANGE on `request.percentiles[0]`.
- Non-monotone quantiles → 422 INVALID_COMBINATION on `request.quantiles`.
- Non-monotone percentiles → 422 INVALID_COMBINATION on `request.percentiles`.

**Tree endpoint leaf wire (after Phase 3):**
- `POST /api/trees` with `{"probability": 0.2, "distributionShape": {"distributionType": "expert", ...}}` → 201.
- Same with flat fields at root level → 400 / unrecognised field (JSON codec rejects).

**Type system (after Phase 4):**
- `RiskLeaf.probability` is `OccurrenceProbability`, visually distinct from any distribution-internal `Probability` usage.
- Tree creation with `probability: 0` or `probability: 1` succeeds end-to-end.
- Compiler rejects passing a raw `Double` where `OccurrenceProbability` is expected.

**Test suite:**
- All modules green: `common` unit, `server` unit, `server-it` integration, `app` Scala.js.
- No skipped or weakened assertions relative to the pre-work state.

---

## ADR Compliance

| ADR | Constraint | Phases affected | Status |
|-----|-----------|-----------------|--------|
| ADR-001 | Validate at boundary; services receive Iron types | 3, 4 | Service receives `Distribution` (P3) and `OccurrenceProbability` (P4) — compliant |
| ADR-001 | No raw primitives in service signatures | 3, 4 | `preview(req: DistributionShapeRequest)` → `preview(dist: Distribution)` (P3) — compliant |
| ADR-009 | Single validation rule per invariant | 2, 3 | Monotone check moved to `Distribution.create`; preview delegates — compliant |
| ADR-010 | Typed sealed error hierarchy | 2, 3, 4 | All errors via `ValidationError` — compliant |
| ADR-011 | Top-level imports | All | Follow throughout |
| ADR-019 | Signals down / callbacks up | 1 | Companion methods are pure functions, not reactive — compliant |

---

## Approval Checkpoint

- [x] User approves Phase 1 (Conversion Methods + Filter Split) — ✅ COMPLETE
- [x] User approves Phase 2 (strictly-increasing check in `Distribution.create`) — Trigger #5 — ✅ COMPLETE
- [x] User approves Phase 3 (Preview Shadow Stack) — Triggers #1, #4, #5 — ✅ COMPLETE
- [x] User approves Phase 4 (OccurrenceProbability) — Triggers #4, #5 — ✅ COMPLETE
