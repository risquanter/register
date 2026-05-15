# Distribution Preview Panel — Planning Context

**Date:** May 2026  
**Status:** IN-DEPTH DESIGN COMPLETE — awaiting explicit approval before any code is touched  
**Feature:** Replace `DistributionChartPlaceholder` with a live distribution visualisation
panel in the Design view, giving non-statistical users graphical feedback on the shape
of the distribution they are modelling as they type.

---

## Progress Tracker

Execute phases in strict order. Each phase unblocks the next.

| Phase | Goal | New files | Edits | Depends on | Status |
|---|---|---|---|---|---|
| **A** | Domain foundation — thread `terms` through existing stack | 0 | 15 | — | ☐ Not started |
| **B** | Preview endpoint — DTOs + service + controller | 5 | 2 | A | ☐ Not started |
| **C** | Frontend chart — state + spec builder + view + wiring | 3 | 3 | A, B | ☐ Not started |
| **D** | Decision science — coherence echo + ratio warning | 0 | 3 | C | ☐ Not started |

Update status as: `☐ Not started` → `⏳ In progress` → `✅ Complete`

### Phase A task list
- [ ] A1  `simulation/LognormalDistribution.scala` — add `density()` method
- [ ] A2  `simulation/MetalogDistribution.scala` — fix `terms` default
- [ ] A3  `domain/data/iron/ValidationMessages.scala` — two new constants
- [ ] A4  `domain/data/Distribution.scala` — add `terms: Option[PositiveInt]` + validation
- [ ] A5  `domain/data/RiskNode.scala` — add `terms` everywhere (`RiskLeaf`, `RiskLeafRaw`, `create`, `unsafeApply`, encoder, decoder)
- [ ] A6  `http/requests/RiskTreeDefinitionRequest.scala` — add `terms: Option[Int] = None`
- [ ] A7  `http/requests/RiskTreeUpdateRequest.scala` — add `terms: Option[Int] = None`
- [ ] A8  `http/requests/RiskTreeMaintenanceRequests.scala` — add `terms` + thread to `Distribution.create`
- [ ] A9  `http/requests/RiskTreeRequests.scala` — thread `l.terms` in both call sites
- [ ] A10 `services/RiskTreeServiceLive.scala` — thread `dist.terms` into `RiskLeaf.unsafeApply`
- [ ] A11 `services/helper/Simulator.scala` — resolve `terms` from `leaf.terms`, fix `ExpertDistributionParams`
- [ ] A12 `domain/data/Provenance.scala` — `ExpertDistributionParams.terms: Int` → `PositiveInt`
- [ ] A13 `app/state/TreeBuilderState.scala` — `LeafDistributionDraft.terms`; confirm/add `draftSignal`
- [ ] A14 `app/state/RiskLeafFormState.scala` — `termsVar`, `termsErrorRaw`, `termsError`, `Terms` field
- [ ] A15 `app/views/RiskLeafFormView.scala` — terms `textInput` in expert mode

### Phase B task list
- [ ] B1 `common/.../http/endpoints/DistributionPreviewEndpoints.scala` *(new)*
- [ ] B2 `common/.../http/requests/DistributionPreviewRequest.scala` *(new)*
- [ ] B3 `common/.../http/requests/DistributionPreviewResponse.scala` *(new)*
- [ ] B4 `server/.../services/DistributionPreviewService.scala` *(new)*
- [ ] B5 `server/.../http/controllers/DistributionPreviewController.scala` *(new)*
- [ ] B6 `server/.../http/HttpApi.scala` — register controller
- [ ] B7 `server/.../Application.scala` — wire service + controller layer

### Phase C task list
- [ ] C1 `app/.../state/DistributionChartState.scala` *(new, includes `DistributionViewMode` enum)*
- [ ] C2 `app/.../chart/DistributionSpecBuilder.scala` *(new)*
- [ ] C3 `app/.../views/DistributionChartView.scala` *(new)*
- [ ] C4 CSS — `.distribution-chart-view`, `.chart-mode-toggle`, `.toggle-btn`, `.distribution-chart-canvas`
- [ ] C5 `app/.../views/DesignView.scala` — add param, swap placeholder
- [ ] C6 Construction site — instantiate `DistributionChartState`, pass to `DesignView`

### Phase D task list
- [ ] D1 `app/.../views/DistributionChartView.scala` — coherence echo caption
- [ ] D2 `app/.../state/RiskLeafFormState.scala` — `impliedRatioWarning` signal
- [ ] D3 `app/.../views/RiskLeafFormView.scala` — ratio warning badge

---

## 1. User Problem

When a user configures a risk leaf, they enter abstract numbers (percentiles, loss
values, or a confidence interval) that internally define a probability distribution.
There is no visual feedback about the resulting shape. A non-statistician cannot tell
whether the inputs produce a sensible, well-behaved distribution or something
pathological (negative density lobes, implausible tails, extreme skew).

The goal is to replace the placeholder panel with a live chart that updates as the user
edits the form, so they can iterate until the shape looks right.

---

## 2. Two Distribution Modes

### 2.1 Expert Opinion (Metalog)

The user provides:
- **Percentiles** — probabilities at which they have a loss estimate, entered as 0–100
  values (e.g. `10, 50, 90`). Normalised to (0, 1) internally before submission.
  Default pre-filled value: `10, 50, 90`.
- **Quantiles** — the corresponding loss amounts in $M (e.g. `50, 200, 1000`).

Internally, the server fits a **Metalog distribution** (Keelin 2016) to these
percentile–quantile anchor pairs using monotonicity-constrained quadratic programming
(`QPFitter` from `simulation-util`).

A third parameter — **terms** — controls the number of basis functions in the Metalog
expansion. It is currently not exposed in the UI and its default is wrong.

### 2.2 Lognormal (BCG)

The user provides:
- **Minimum Loss** — lower bound of a 90% confidence interval (= 5th percentile, P05)
- **Maximum Loss** — upper bound of a 90% confidence interval (= 95th percentile, P95)

The server parameterises a lognormal distribution using:

```
μ = (log(max) + log(min)) / 2
σ = (log(max) - log(min)) / 3.29
```

where 3.29 = 2 × 1.645 (the z-scores for the 90% CI). Implemented in
`LognormalHelper.fromLognormal90CI` → `LognormalDistribution.fromConfidenceInterval`.

---

## 3. The `terms` Parameter (Metalog Only)

### What it controls

The Metalog quantile function is `Q(p) = Σ aⱼ Tⱼ(p)` where `Tⱼ` are basis functions
built from logit and centred-power terms. `terms` = n determines how many basis
functions participate.

### Relationship to the number of anchor points

| Anchor points (n) | terms = n | terms < n |
|---|---|---|
| Behaviour | Exact interpolating fit — curve passes through every point | Least-squares approximation — QP minimises error while enforcing monotonicity |
| Risk | With few expert-provided points, the tails can be implausible | Smoother shape, safer extrapolation |

`terms > n` is invalid (under-determined).

### Current default — wrong

`MetalogDistribution.fromPercentiles` currently defaults `terms = 9`, capped to
`percentiles.length` by `validateTerms`. With the standard 3-anchor input this
accidentally gives `terms = 3` (exact fit). But if the user adds more anchors, it
silently jumps to exact-fit behaviour with no cap at the practically validated maximum.

### Correct default — `min(n, 4)`

Best-practice default per the Keelin (2016) literature and practitioner consensus:

| n | `min(n, 4)` | Rationale |
|---|---|---|
| 2 | 2 | Minimum valid; logistic-family shape |
| 3 | 3 | Exact fit; the "standard" Metalog |
| 4 | 4 | Exact fit; adds asymmetry via φ term |
| 5+ | 4 | **Smoothed approximation by design.** Exact fitting with 5+ expert-provided points risks oscillation and compressed-PDF artefacts. |

The 4-term Metalog is the most studied and validated shape for expert opinion in the
literature.

### Valid user-adjustable range

`[2, n]` where n = current number of anchor points. The control must dynamically
constrain its own maximum as the user adds/removes points.

### Why expose it?

The PDF preview panel is the feedback mechanism. When a user increases `terms` and sees
the PDF develop extra humps or negative lobes, they immediately understand that their
inputs are insufficient to support that many terms. The chart makes the parameter
meaningful to a non-statistician without requiring them to understand the algebra.

---

## 4. Why Fitting Must Be Server-Side

Scala.js compiles Scala source to JavaScript but **cannot transpile Java bytecode**.
The fitting machinery has two JVM-only dependencies with no JS equivalents:

1. **`QPUnboundedConstrainedFitter`** uses ojalgo's QP solver (pure Java, JVM-only)
   for the monotonicity-constrained quadratic program.
2. **`LognormalDistribution`** wraps Apache Commons Math's `LogNormalDistribution`
   (pure Java, JVM-only).

There is no "compile Scala and cross-publish" escape: the transitive Java dependencies
make the fitting modules non-cross-compilable.

Once Metalog coefficients `a[]` are known, `Metalog.quantile(p)` and `Metalog.pdf(p)`
are pure arithmetic with no Java dependency and could in principle be ported. However,
computing the coefficients still requires the QP solver on the server. The clean
architecture is: **all distribution computation on the server**, client receives a
pre-sampled curve.

This also provides a correctness guarantee: the preview curve is produced by exactly
the same code path as the simulation, so what the user sees is what the system uses.

---

## 5. Chart Design Requirements

### 5.1 Two views, same server response

The server returns a fixed response shape (a vector of sampled distribution points)
that is sufficient for both chart types. The view switch is a pure client-side
transform — no additional network call when switching.

### 5.2 PDF view (primary / default)

- X axis: loss value (same units as user input, $M)
- Y axis: probability density
- Shape: right-skewed hill, characteristic of loss distributions
- Overlay for **expert mode**: vertical rug marks / rules at the user's input quantile
  x-positions, labelled with their percentile (e.g. "P10", "P50", "P90"). User can
  see where their anchors fall relative to the peak.
- Overlay for **lognormal mode**: vertical rules at P05 (minLoss) and P95 (maxLoss)
  with labels.
- Pathology signal: oscillating or negative-lobe PDF immediately visible to the user
  as a clear "something is wrong" indicator — no statistical knowledge required.

### 5.3 CDF view (secondary)

- X axis: loss value
- Y axis: P(Loss ≤ x), range [0, 1]
- Shape: S-curve
- Overlay for **expert mode**: the input (quantile, percentile/100) pairs as filled
  dots sitting exactly on the fitted curve. The metalog is constructed to pass through
  these points (when terms = n), so the dots-on-curve verification is the strongest
  possible sanity check the user can perform.
- Overlay for **lognormal mode**: vertical rules at P05/P95 with labels (matching
  existing LEC chart annotation pattern).

### 5.4 Toggle control

A `PDF | CDF` two-button toggle above the chart. Switching rebuilds the Vega-Lite
spec from the already-held server response — no new fetch. In the reactive model:
`Signal[(ServerResponse, ViewMode)] → spec → vegaEmbed`.

### 5.5 Trigger and debounce

The chart updates when the user edits the form. For the metalog, the server roundtrip
should be debounced (e.g. 400 ms) to avoid firing on every keystroke. The chart should
show a loading state during the roundtrip.

### 5.6 Chart schema consistency

Both distribution types and both view modes must use the same server response schema —
one spec builder or two spec-builder functions both consuming the same type, adapting
axis labels and overlay data based on distribution mode and view mode.

---

## 6. Current State of the Placeholder

[`app/views/DistributionChartPlaceholder.scala`](../../modules/app/src/main/scala/app/views/DistributionChartPlaceholder.scala):
- Static `div.distribution-chart-placeholder` with placeholder text and icon
- No reactive wiring
- No Vega integration
- CSS is already in place (`height: 100%`, flexbox column, dashed-border inner area)

[`app/views/DesignView.scala`](../../modules/app/src/main/scala/app/views/DesignView.scala):
- Placeholder occupies the **bottom 40%** of the right-hand `SplitPane.vertical`
- The top 60% is `TreeListView + TreePreview`
- The left 40% of the outer horizontal split is `TreeBuilderView` (the form)

---

## 7. Existing Patterns to Follow

### 7.1 Vega-Lite lifecycle — `LECChartView` / `LECSpecBuilder`

The existing LEC chart establishes the complete pattern:
- `Signal[LoadState[js.Dynamic]]` drives `child <--` in the view
- `vegaEmbed(el, spec, options)` called on `Loaded(spec)`, `finalize()` on any
  other state or unmount
- Spec built as `js.Dynamic` literals in a dedicated `SpecBuilder` object
- Dark theme axis/legend config centralised in the builder
- Annotation layers (rules + labels) composed as separate Vega-Lite layers

The distribution preview should follow exactly this pattern, with its own
`DistributionSpecBuilder` parallel to `LECSpecBuilder`.

### 7.2 State layering — `LECChartState`

`LECChartState` separates:
- Data cache (`Var[LoadState[...]]`)
- Derived spec signal (computed from cache + display parameters)
- Actions (fetch, reset)

The distribution chart state should follow the same separation.

### 7.3 Server endpoint pattern — `WorkspaceAnalysisController`

The existing analysis endpoints show:
- Tapir endpoint defined as a `trait` in `common` (shared with frontend for ZJS calls)
- Controller implementation in `server`, wired via `makeZIO`
- Controller registered in `HttpApi.makeControllers` and `Application.appLayer`

The distribution preview endpoint should follow this same three-layer pattern
(`common` endpoint trait → `server` controller → `Application` wiring).

### 7.4 Frontend–server bridge — ZJS

`LECChartState` uses `getWorkspaceLECCurvesMultiEndpoint(...)` via the ZJS bridge
(`ZJS.*`). The distribution preview state will use the same mechanism to call its
new endpoint.

---

## 8. What Needs to Be Touched

### 8.1 Common module (`modules/common`)

- **New endpoint trait** for the distribution preview: accepts distribution parameters
  (mode, percentiles/quantiles or minLoss/maxLoss, terms), returns a sampled curve.
- **New request/response types** for the distribution preview endpoint.
- **`LeafDistributionDraft`** (in `app/state/`) or an equivalent request DTO must gain
  the `terms` field.

### 8.2 Server module (`modules/server`)

- **New service method** (or standalone service) for computing a distribution preview
  curve from parameters — fitting and sampling at a fixed p-grid.
- **New controller** wired into `WorkspaceAnalysisController` or as a sibling,
  implementing the preview endpoint.
- **`MetalogDistribution.fromPercentiles`** default for `terms` should be corrected
  from 9 to `min(n, 4)`.
- **Application layer** wired to include any new service/controller.

### 8.3 App module (`modules/app`)

- **`RiskLeafFormState`**: add `termsVar: Var[Int]` and a `termsError` signal. The
  valid range `[2, len(percentiles)]` is a reactive constraint requiring
  `combineWith(percentilesVar)`.
- **`RiskLeafFormView`**: add a `terms` numeric input visible only in expert mode,
  showing range and default, wired to `termsVar`.
- **`LeafDistributionDraft`**: add `terms: Option[Int]` (None = lognormal mode,
  Some(n) = expert mode).
- **New `DistributionChartState`**: reactive state for the preview — debounced fetch,
  `LoadState` cache, view mode toggle (`PDF | CDF`), derived spec signal.
- **New `DistributionSpecBuilder`**: Vega-Lite spec construction for PDF and CDF views,
  parameterised by distribution mode and view mode. Dark theme consistent with
  `LECSpecBuilder`.
- **`DistributionChartPlaceholder`**: to be replaced by the real
  `DistributionChartView` once the feature is implemented.
- **`DesignView`**: wire `DistributionChartState` and pass to the new view.

### 8.4 Styles (`modules/app/styles`)

- CSS for the view-mode toggle (PDF/CDF buttons).
- The existing `.distribution-chart-placeholder` styles form the basis for
  `.distribution-chart-view`.

---

## 9. Open Questions for Design Phase

1. **Endpoint scope**: Should the distribution preview endpoint be workspace-scoped
   (requiring a workspace key) or public/stateless (just takes parameters, no auth)?
   It performs no data access — only computation. A stateless endpoint would be
   simpler and avoids requiring the user to have a workspace open before seeing
   a preview while building a tree.

2. **`terms` in the form vs preview-only**: Should `terms` be stored as part of the
   distribution definition (affecting simulation), or is it a preview-only control
   that doesn't persist? This drives whether `LeafDistributionDraft` grows a `terms`
   field that flows through to the backend simulation.

3. **Lower/upper bounds for expert mode**: The `QPFitter` supports bounded Metalog
   fits (`lower`, `upper`). These are currently not exposed. A lower bound of 0 is
   physically meaningful (losses cannot be negative). Should this be exposed in the
   expert form, and if so, does it also affect the preview?

4. **Preview p-grid resolution**: How many sample points should the server return?
   The existing `ExampleUtil.buildFitJson` uses 100 steps. The LEC curve also uses
   ~100 ticks. 100 points produces smooth curves for both PDF and CDF rendering.

5. **Error presentation**: If the QPFitter throws (ill-conditioned input, non-monotone
   constraint violation), the preview panel should show a meaningful error, not a
   generic "chart error". What error text is appropriate for a non-statistician?

---

## 10. ADRs to Review During Design Phase

- **ADR-001** — Iron types for all new validated inputs (terms, bounds)
- **ADR-010** — Error handling: fitting errors → typed error in ZIO error channel, not
  exceptions surfacing to the client
- **ADR-011** — Import conventions for new files
- **ADR-017** — API design: if new DTO types are added, validate field naming and
  schema shape against existing conventions
- **ADR-018** — Nominal wrappers: check whether new ID/type fields need wrapper case
  classes
- **ADR-019** — Frontend architecture: Signals down / callbacks up, FormState trait
  for `termsVar`, no state mutation in children

---

*Context capture complete. Solution design below.*

---

## 11. Resolved Decisions

### 11.1 Endpoint auth scope (GQ1)

**`POST /w/{key}/distribution/preview` — workspace-scoped, no `treeId`.**

A workspace contains N trees, but the preview endpoint receives all distribution
parameters in the request body — it performs no tree lookup. "Which tree is the
modelling done for" is answered entirely at the frontend signal level: `DistributionChartState`
receives its input signal from `RiskLeafFormState`, which is instantiated inside
`TreeBuilderView` for the specific tree being built. The server endpoint is a pure
function of its inputs; the workspace key is solely the auth gate (confirms a live
workspace session, no semantic relation to any specific tree).

**Controller pattern:** `workspaceStore.resolve(key)` only — no `treeId` path segment,
no `authzService.check()` call. Pattern mirrors `WorkspaceAnalysisController` minus the
tree-scoped auth.

### 11.2 `terms` parameter (GQ2)

`terms` is a full distribution parameter. It flows through the entire stack identically
to `percentiles` and `quantiles` (a field that was simply forgotten). No backward
compatibility requirement.

### 11.3 UX control for `terms` (GQ3)

**Option A:** single `textInput` for terms visible in expert mode, consistent with every
existing form field.

---

## 12. Implementation Phases

> Sections are organized in execution order (A → B → C → D). All signatures and
> patterns are cross-checked against the existing codebase. Corrections from earlier
> skeleton versions are marked **⚠ CORRECTED**.
>
> Consult the Progress Tracker (top of document) for task checklists and status.

---

### Phase A — Domain Foundation

*Goal: Lay the domain and simulation foundations required by all later phases. Changes
are confined to `common`, `server`, and `app` modules at the domain layer. This phase
must be complete before Phase B work begins.*

#### A.1 New ValidationMessages constants

**File:** `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`

```scala
// ══════════════════════════════════════════════════════════════════
// Distribution — Metalog terms
// ══════════════════════════════════════════════════════════════════
val termsOutOfRange: String =
  "Terms must be between 2 and the number of anchor points"

// ══════════════════════════════════════════════════════════════════
// Distribution — fit failure (preview endpoint + Simulator)
// ══════════════════════════════════════════════════════════════════
val distributionFitFailed: String =
  "The inputs could not be fitted to a valid distribution. " +
  "Try reducing Terms, check that quantile values are strictly increasing, " +
  "or remove conflicting estimates."
```

These follow the existing `val camelCaseIdentifier: String = "sentence."` naming
convention in `ValidationMessages`.

#### A.2 `terms` full stack changes

In order of dependency (bottom-up). **⚠ CORRECTED** from skeleton: added
`LognormalDistribution.scala` (item #1) and `Provenance.scala` (item #11); renumbered
accordingly.

| # | File | Change — exact |
|---|---|---|
| 1 | `simulation/LognormalDistribution.scala` | Add `def density(x: Double): Double = underlying.density(x)` — exposes `LogNormalDistribution.density` from Apache Commons Math; same pattern as existing `quantile`. Needed by `DistributionPreviewService` for lognormal PDF sampling |
| 2 | `simulation/MetalogDistribution.scala` | `terms: PositiveInt = 9.refineUnsafe` → `terms: PositiveInt = math.min(percentiles.length, 4).refineUnsafe` in `fromPercentiles`; apply the same default fix in `fromPercentilesUnsafe` |
| 3 | `domain/data/Distribution.scala` | Add `terms: Option[PositiveInt]` field; add `terms: Option[Int] = None` param to `create(...)`; expert-mode cross-field validation: `terms.exists(_ > percentiles.get.length)` → accumulate `ValidationError` with `ValidationMessages.termsOutOfRange` (new message constant, A.1) |
| 4 | `domain/data/RiskNode.scala` (`RiskLeaf`) | Add `terms: Option[PositiveInt]` to `RiskLeaf` case class; `terms: Option[Int]` to `RiskLeafRaw`; add to `create(...)` and `unsafeApply(...)` params; add to encoder `contramap` (`terms = leaf.terms.map(_.toInt)`); add to decoder `create(...)` call |
| 5 | `http/requests/RiskTreeDefinitionRequest.scala` | Add `terms: Option[Int] = None` to `RiskLeafDefinitionRequest` |
| 6 | `http/requests/RiskTreeUpdateRequest.scala` | Add `terms: Option[Int] = None` to `RiskLeafUpdateRequest` |
| 7 | `http/requests/RiskTreeMaintenanceRequests.scala` | Add `terms: Option[Int] = None` to `DistributionUpdateRequest`; pass `req.terms` to `Distribution.create(...)` in `validate(...)` |
| 8 | `http/requests/RiskTreeRequests.scala` | Thread `l.terms` into `Distribution.create(...)` in both `refineLeafDefs` and `refineExistingLeaves` |
| 9 | `services/RiskTreeServiceLive.scala` (`buildNodes`) | Add `terms = dist.terms.map(_.toInt)` to `RiskLeaf.unsafeApply(...)` call |
| 10 | `services/helper/Simulator.scala` (`createDistributionWithParams`) | Replace `val terms = ps.length.asInstanceOf[PositiveInt]` with `val terms = leaf.terms.map(_.toInt).getOrElse(math.min(ps.length, 4)).asInstanceOf[PositiveInt]`; pass this resolved `terms` to both `MetalogDistribution.fromPercentiles` and `ExpertDistributionParams` |
| 11 | `domain/data/Provenance.scala` | `ExpertDistributionParams.terms: Int` → `PositiveInt`; update kdoc comment ("3-16, default 9" → "2–n, default min(n, 4)"); `DeriveJsonCodec.gen` handles Iron opaque types automatically — no manual codec change needed |
| 12 | `app/state/TreeBuilderState.scala` | Add `terms: Option[Int] = None` to `LeafDistributionDraft`; thread into `validateDistribution` (`Distribution.create`) and `toLeafRequest` (`RiskLeafDefinitionRequest`) |
| 13 | `app/state/RiskLeafFormState.scala` | Add `Terms` to `enum RiskLeafField`; add `termsVar: Var[String] = Var("")`; add `termsErrorRaw` (reactive range validation — see below); add `termsError = withSubmitErrors(Terms, termsErrorRaw)`; wire into `toDistributionDraft`; add to `errorSignals`; add `termsVar.set("")` to `resetFields()` |
| 14 | `app/views/RiskLeafFormView.scala` | Add `textInput(labelText = "Terms", valueVar = formState.termsVar, errorSignal = formState.termsError, filter = lossFilter, placeholderText = "e.g. 3")` in `expertFields` block; add `termsVar.signal.changes` observer |

`PositiveInt` is already defined at `com.risquanter.register.domain.data.iron.PositiveInt` —
no new types needed.

**`termsErrorRaw` reactive logic (item #13):**

```scala
// In RiskLeafFormState
private val termsErrorRaw: Signal[Option[String]] =
  termsVar.signal.combineWith(percentilesVar.signal).map { case (tStr, pStr) =>
    if tStr.isBlank then None   // blank = "use server default", always valid
    else
      tStr.toIntOption match
        case None    => Some("Terms must be a whole number")
        case Some(t) =>
          val n = pStr.split(",").count(_.trim.nonEmpty)
          if t < 2      then Some(ValidationMessages.termsOutOfRange)
          else if t > n then Some(ValidationMessages.termsOutOfRange)
          else None
  }

val termsError: Signal[Option[String]] = withSubmitErrors(Terms, termsErrorRaw)
```

Note: `termsVar` uses `lossFilter` (digits only — `_.forall(_.isDigit)`), consistent
with existing `minLossVar` / `maxLossVar`. Default is blank (not pre-filled): the user
leaves it empty for the server to apply `min(n, 4)`. Placeholder text `"e.g. 3"`
communicates the typical value without constraining the field.

---

### Phase B — Preview Endpoint

*Goal: Add the `/w/{key}/distribution/preview` endpoint with request/response DTOs,
service, and controller wiring. Requires Phase A complete (`LognormalDistribution.density()`
at minimum).*

#### B.1 Endpoint trait

**Trait location:** `modules/common/src/main/scala/com/risquanter/register/http/endpoints/DistributionPreviewEndpoints.scala`

Pattern: mirrors `WorkspaceAnalysisEndpoints` exactly — extends `BaseEndpoint`, uses
`authedBaseEndpoint`, `path[WorkspaceKeySecret]`, `post`, `jsonBody`.

`authedBaseEndpoint` definition (from `BaseEndpoint.scala`):
```scala
val authedBaseEndpoint = baseEndpoint.in(header[Option[UserId]]("x-user-id"))
```
This produces an `Endpoint[Unit, Option[UserId], ...]`. All `in(...)` parameters are
accumulated into the input tuple `I = (Option[UserId], WorkspaceKeySecret,
DistributionPreviewRequest)`. The security input remains `Unit` — the endpoint is
fully compatible with the ZJS `extension [I, E, O](endpoint: Endpoint[Unit, I, E, O, Any])`
bridge.

```scala
package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.AppError
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse}

trait DistributionPreviewEndpoints extends BaseEndpoint:

  val distributionPreviewEndpoint =
    authedBaseEndpoint
      .tag("distribution")
      .name("distributionPreview")
      .description("Compute a sampled distribution preview curve from parameters (no tree required)")
      .in("w" / path[WorkspaceKeySecret]("key") / "distribution" / "preview")
      .post
      .in(jsonBody[DistributionPreviewRequest])
      .out(jsonBody[DistributionPreviewResponse])
```

Inferred Tapir type:
`Endpoint[Unit, (Option[UserId], WorkspaceKeySecret, DistributionPreviewRequest), AppError, DistributionPreviewResponse, Any]`

#### B.2 Request / response DTOs

**Note:** Unlike `DistributionUpdateRequest`, the preview request has **no `probability`
field**. The preview is about the loss distribution shape only — `probability` (the
annual likelihood of the risk occurring) is irrelevant to the distribution curve.

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionPreviewRequest.scala`

```scala
package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError

final case class DistributionPreviewRequest(
  distributionType: String,
  percentiles:      Option[Array[Double]],   // 0-100 values (normalised ÷100 server-side, matching RiskLeafFormState)
  quantiles:        Option[Array[Double]],
  terms:            Option[Int],             // None → server uses min(n, 4)
  minLoss:          Option[Long],
  maxLoss:          Option[Long]
)

object DistributionPreviewRequest:
  given codec: JsonCodec[DistributionPreviewRequest] = DeriveJsonCodec.gen[DistributionPreviewRequest]

  /** Validate cross-field rules (same logic as Distribution.create, minus probability). */
  def validate(req: DistributionPreviewRequest): Validation[ValidationError, DistributionPreviewRequest] = ...
```

Validation rules in `validate(...)` (mirrors `Distribution.create` cross-field checks):
1. `distributionType` must be `"expert"` or `"lognormal"` → same error as `ValidationMessages.distributionTypeInvalid`
2. Expert mode: `percentiles` required, `quantiles` required, `percentiles.length == quantiles.length`, and `terms.forall(_ <= percentiles.get.length)` (else `ValidationMessages.termsOutOfRange` — new message constant)
3. Lognormal mode: `minLoss` required, `maxLoss` required

**File:** `modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionPreviewResponse.scala`

```scala
package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}

final case class DistributionPreviewPoint(x: Double, pdf: Double, cdf: Double)

object DistributionPreviewPoint:
  given codec: JsonCodec[DistributionPreviewPoint] = DeriveJsonCodec.gen[DistributionPreviewPoint]

final case class DistributionPreviewResponse(
  distributionType: String,
  resolvedTerms:    Option[Int],   // terms value actually used; None for lognormal
  anchorCount:      Option[Int],   // number of input anchor points; None for lognormal
  points:           Array[DistributionPreviewPoint]
)

object DistributionPreviewResponse:
  given codec: JsonCodec[DistributionPreviewResponse] = DeriveJsonCodec.gen[DistributionPreviewResponse]
```

`resolvedTerms` and `anchorCount` together enable the coherence echo in Phase D:
- `resolvedTerms = Some(t)`, `t < anchorCount.get`: "Smoothed with {t} terms ({n} anchor points)"
- `resolvedTerms = Some(t)`, `t == anchorCount.get`: "Exact fit with {t} terms"
- `resolvedTerms = None` (lognormal): no terms caption

The single response shape is sufficient for both PDF (`x`, `pdf`) and CDF (`x`, `cdf`)
views — view mode switch is a pure client-side transform; no second fetch.

#### B.3 Server service

**⚠ CORRECTED:** `LognormalDistribution` (confirmed from file read) exposes **only
`quantile(p: Double): Double`** — it has no `density` or `pdf` method. The `underlying:
LogNormalDistribution` field is `private`. To sample the lognormal PDF, we must add
`def density(x: Double): Double = underlying.density(x)` to `LognormalDistribution`.
This is item #1 in the file change table below (§12.5).

**File:** `modules/server/src/main/scala/com/risquanter/register/simulation/DistributionPreviewService.scala`

```scala
package com.risquanter.register.services

import zio.*
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError}
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse, DistributionPreviewPoint}
import com.risquanter.register.simulation.{MetalogDistribution, LognormalHelper}
import io.github.iltotore.iron.*

trait DistributionPreviewService:
  def preview(req: DistributionPreviewRequest): IO[ValidationFailed, DistributionPreviewResponse]

object DistributionPreviewService:
  val layer: ZLayer[Any, Nothing, DistributionPreviewService] =
    ZLayer.succeed(DistributionPreviewServiceLive())
```

**Implementation logic — `DistributionPreviewServiceLive`:**

**Expert mode:**
1. Normalise percentiles: `ps = req.percentiles.get.map(_ / 100.0)`
2. Refine each to `Probability` via `ValidationUtil.refineProbability`
3. Resolve `terms = req.terms.getOrElse(math.min(ps.length, 4)).refineUnsafe[Greater[0]]`
4. Call `MetalogDistribution.fromPercentiles(ps.toArray, req.quantiles.get.toArray, terms, lower = Some(0.0), upper = None)`
5. On `Left(e)` → convert to `ValidationFailed`
6. On `Right(metalog)` → sample p-grid at 200 uniform steps over `(0.001, 0.999)`:
   ```scala
   val pGrid = (1 to 200).map(i => 0.001 + (i - 0.5) * (0.999 - 0.001) / 200)
   val points = pGrid.map { p =>
     val x   = metalog.quantile(p)
     val pdf = metalog.pdf(p)
     DistributionPreviewPoint(x = x, pdf = pdf, cdf = p)
   }
   ```
7. Return `DistributionPreviewResponse("expert", resolvedTerms = Some(terms.value), anchorCount = Some(ps.length), points.toArray)`

**Lognormal mode:**
1. `LognormalHelper.fromLognormal90CI(req.minLoss.get, req.maxLoss.get)` — returns `Either[ValidationError, LognormalDistribution]`
2. On `Left(e)` → `ValidationFailed`
3. On `Right(lognormal)` → same p-grid strategy:
   ```scala
   val pGrid = (1 to 200).map(i => 0.001 + (i - 0.5) * (0.999 - 0.001) / 200)
   val points = pGrid.map { p =>
     val x   = lognormal.quantile(p)       // existing method
     val pdf = lognormal.density(x)        // ⚠ new method to be added
     DistributionPreviewPoint(x = x, pdf = pdf, cdf = p)
   }
   ```
4. Return `DistributionPreviewResponse("lognormal", resolvedTerms = None, anchorCount = None, points.toArray)`

**QPFitter exception handling:**
`MetalogDistribution.fromPercentiles` returns `Either[ValidationError, MetalogDistribution]`
(already uses `Either`, no raw exception surface). The `Left` case is mapped to
`IO.fail(ValidationFailed(List(e)))` using `ValidationMessages.distributionFitFailed` (Phase A A.1).

**p-grid choice:** 200 steps. Metalog PDFs can be narrow and peaky; 100 steps
(the LEC grid size) visually undershoots narrow distributions. 200 steps has negligible
bandwidth impact (~9 KB JSON) and visibly improves PDF rendering.

#### B.4 Controller + application wiring

**File:** `modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala`

Pattern: identical to `WorkspaceAnalysisController` — `private class`, `makeZIO` in
companion object, `extends BaseController with DistributionPreviewEndpoints`:

```scala
class DistributionPreviewController private (
  previewService: DistributionPreviewService,
  workspaceStore: WorkspaceStore,
  userCtx:        UserContextExtractor
) extends BaseController with DistributionPreviewEndpoints:

  val distributionPreviewRoute: ServerEndpoint[Any, Task] =
    distributionPreviewEndpoint.serverLogic {
      case (maybeUserId, key, req) =>
        (for
          _      <- userCtx.extract(maybeUserId)   // validates user context; NoOp in capability mode
          _      <- workspaceStore.resolve(key)     // auth gate: confirms live workspace session
          result <- DistributionPreviewRequest.validate(req)
                      .toZIOWithFail(ValidationFailed.apply)
                      .flatMap(previewService.preview)
        yield result).either
    }

  override val routes: List[ServerEndpoint[Any, Task]] = List(distributionPreviewRoute)

object DistributionPreviewController:
  val makeZIO: ZIO[DistributionPreviewService & WorkspaceStore & UserContextExtractor, Nothing, DistributionPreviewController] =
    for
      previewService <- ZIO.service[DistributionPreviewService]
      workspaceStore <- ZIO.service[WorkspaceStore]
      userCtx        <- ZIO.service[UserContextExtractor]
    yield DistributionPreviewController(previewService, workspaceStore, userCtx)
```

Note: no `AuthorizationService` — the preview endpoint is workspace-scoped (auth gate
via `workspaceStore.resolve`) but not tree-scoped. No `authzService.check()` call.

**`HttpApi.scala` changes** — add `DistributionPreviewController` to the controller registry:
```scala
// In makeControllers and endpointsZIO: add DistributionPreviewController to the
// ZIO environment type and to the `for` comprehension:
distPreview <- ZIO.service[DistributionPreviewController]
// ...
yield List(system, lifecycle, trees, analysis, sse, cache, query, distPreview)
```

**`Application.scala` changes** — add service layer and controller to `appLayer`:
```scala
// In imports:
import com.risquanter.register.http.controllers.DistributionPreviewController
import com.risquanter.register.services.DistributionPreviewService

// In appLayer type parameter: add & DistributionPreviewController
// In ZLayer.make body, add:
DistributionPreviewService.layer,
ZLayer.fromZIO(DistributionPreviewController.makeZIO),
```

`DistributionPreviewService.layer` uses `ZLayer.succeed` (pure, no deps) — no
additional layer wiring needed.

---

### Phase C — Frontend Chart

*Goal: Replace `DistributionChartPlaceholder` with a live distribution preview panel.
Requires Phase B endpoint deployed and reachable. The `DistributionChartState` is
constructed above `DesignView` per ADR-019 Pattern 1.*

#### C.1 DistributionChartState

**⚠ CORRECTED:** `LECChartState` constructor uses `StrictSignal` (not `Signal`) and
includes `userIdAccessor: () => Option[UserId]`. The distribution chart state follows
the same pattern.

**File:** `modules/app/src/main/scala/app/state/DistributionChartState.scala`

```scala
package app.state

import com.raquo.laminar.api.L.*
import scala.scalajs.js
import app.core.ZJS.*
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, UserId}
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse}
import com.risquanter.register.http.endpoints.DistributionPreviewEndpoints

enum DistributionViewMode:
  case PDF, CDF

class DistributionChartState(
  draftSignal:      StrictSignal[Option[LeafDistributionDraft]],
  keySignal:        StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor:   () => Option[UserId] = () => None
) extends DistributionPreviewEndpoints:

  val viewModeVar: Var[DistributionViewMode] = Var(DistributionViewMode.PDF)

  private val previewVar: Var[LoadState[DistributionPreviewResponse]] = Var(LoadState.Idle)

  val specSignal: Signal[LoadState[js.Dynamic]] =
    previewVar.signal.combineWith(viewModeVar.signal).map { (loadState, viewMode) =>
      loadState.map(resp => DistributionSpecBuilder.build(resp, viewMode))
    }

  /** Trigger a preview fetch for the given draft + workspace key.
    *
    * Called on the debounced EventStream in DistributionChartView's onMountCallback.
    * Sets Loading → Loaded/Failed via loadInto (ZJS bridge).
    */
  def loadPreview(key: WorkspaceKeySecret, req: DistributionPreviewRequest): Unit =
    distributionPreviewEndpoint((userIdAccessor(), key, req)).loadInto(previewVar)

  def reset(): Unit = previewVar.set(LoadState.Idle)
```

**`DesignView`** derives `draftSignal` from `builderState.draftSignal` (or equivalent
`Signal` produced by `TreeBuilderState`). The `keySignal` is passed from
`WorkspaceState`. Both are `StrictSignal` (from `Var.signal`).

**Debounce wiring** in `DistributionChartView.onMountCallback`:
```scala
// In DistributionChartView:
ctx.owner.let { owner =>
  EventStream.fromSignal(
    draftSignal.combineWith(keySignal)
  )
    .debounce(400)
    .collect { case (Some(draft), Some(key)) =>
      draft.toPreviewRequest.toOption.map((key, _))
    }
    .collectSome
    .foreach { case (key, req) => chartState.loadPreview(key, req) }(owner)
}
```

`draft.toPreviewRequest` is a `Either[..., DistributionPreviewRequest]` method on
`LeafDistributionDraft` (or inline mapping in `DesignView`).

#### C.2 DistributionSpecBuilder

**File:** `modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala`

**⚠ NOTE:** `LECSpecBuilder.emptySpec` is `private def` — `DistributionSpecBuilder`
must define its own empty spec; it cannot share `LECSpecBuilder`'s.

Dark theme constants (verified from `LECSpecBuilder.scala`, used verbatim):

| Constant | Value | Usage |
|---|---|---|
| background | `"transparent"` | Top-level spec |
| legend labelColor / titleColor | `"#e6e8e8"` | `config.legend` |
| axis gridColor | `"#1c2225"` | `config.axis` |
| axis labelColor | `"#b0b8b8"` | `config.axis` |
| axis titleColor | `"#e6e8e8"` | `config.axis` |
| axis domainColor | `"#4a5a5e"` | `config.axis` |
| axis tickColor | `"#4a5a5e"` | `config.axis` |
| annotation rule color | `"#6a8a8e"` | `quantile rule strokeDash` |
| annotation label color | `"#a0b0b0"` | `text mark color`, fontSize 11, dx 4, dy -6 |

```scala
package app.chart

import scala.scalajs.js
import app.state.{DistributionViewMode, DistributionPreviewResponse, DistributionPreviewPoint}

object DistributionSpecBuilder:

  def build(
    response: DistributionPreviewResponse,
    viewMode: DistributionViewMode,
    width:    Int = 950,
    height:   Int = 300
  ): js.Dynamic =
    if response.points.isEmpty then emptySpec(width, height)
    else viewMode match
      case DistributionViewMode.PDF => buildPdfSpec(response, width, height)
      case DistributionViewMode.CDF => buildCdfSpec(response, width, height)
```

**PDF spec (`buildPdfSpec`):**
- Mark: `"area"` with `"color" -> "#4a8a8e"` and `"opacity" -> 0.7`
- X encoding: `"x"` field, `"quantitative"`, title `"Loss"`, B/M axis label expression
  (same `labelExpr` as `LECSpecBuilder`)
- Y encoding: `"y"` field (`pdf`), `"quantitative"`, title `"Density"`
- Anchor overlay (expert mode): vertical `rule` layer at each input quantile x-position,
  `text` labels (`"P10"`, `"P50"`, `"P90"`, etc.) — using the same
  `quantileAnnotation(value, label)` helper pattern as `LECSpecBuilder`
- Anchor overlay (lognormal mode): `rule` layers at `minLoss` and `maxLoss` with
  labels `"P05"`, `"P95"`
- No legend, `"autosize" -> "fit"`

**CDF spec (`buildCdfSpec`):**
- Mark: `"line"` with `"interpolate" -> "monotone"`, `"color" -> "#4a8a8e"`
- X encoding: same as PDF
- Y encoding: `"cdf"` field, range `[0, 1]`, axis format `".1~%"`, title `"Probability"`
- Anchor overlay (expert mode): `"point"` mark layer — filled dots at each
  `(quantile_x, percentile/100)` pair, `"color" -> "#e6a35a"` for visibility against
  the line; these sit exactly on the fitted CDF curve when `terms = n` (exactfit),
  providing the strongest possible sanity check
- Anchor overlay (lognormal): same `rule` + `text` annotation as PDF view
- No legend

**`emptySpec`:** same structure as `LECSpecBuilder.emptySpec` — `"text"` mark with
`"color" -> "#b0b8b8"`, message `"Enter distribution parameters to see a preview"`.

**Dark config block** (copy verbatim from `LECSpecBuilder` — identical for all specs):
```scala
"config" -> js.Dynamic.literal(
  "legend" -> js.Dynamic.literal(
    "disable"    -> false,
    "labelColor" -> "#e6e8e8",
    "titleColor" -> "#e6e8e8"
  ),
  "axis" -> js.Dynamic.literal(
    "grid"        -> true,
    "gridColor"   -> "#1c2225",
    "labelColor"  -> "#b0b8b8",
    "titleColor"  -> "#e6e8e8",
    "domainColor" -> "#4a5a5e",
    "tickColor"   -> "#4a5a5e"
  ),
  "title" -> js.Dynamic.literal("color" -> "#e6e8e8")
)
```

#### C.3 DistributionChartView

**File:** `modules/app/src/main/scala/app/views/DistributionChartView.scala`

Follows `LECChartView` exactly — verified from file read. The critical lifecycle
pattern (from `LECChartView`):

```scala
object DistributionChartView:

  def apply(chartState: DistributionChartState): HtmlElement =
    var currentResult: js.UndefOr[EmbedResult] = js.undefined

    def disposeChart(): Unit =
      currentResult.foreach { result =>
        result.finalize()
        currentResult = js.undefined
      }

    val renderError$: Var[Option[String]] = Var(None)

    def renderChart(spec: js.Dynamic): HtmlElement =
      div(
        cls := "distribution-chart-canvas",
        onMountCallback { ctx =>
          vegaEmbed(ctx.thisNode.ref, spec, vegaEmbedOptions).`then` { result =>
            currentResult = result
            renderError$.set(None)
          }.`catch` { err =>
            renderError$.set(Some(s"Chart render error: $err"))
          }
        }
      )

    // View-mode toggle (PDF | CDF)
    val toggleEl = div(
      cls := "chart-mode-toggle",
      button(
        cls <-- chartState.viewModeVar.signal.map(m =>
          if m == DistributionViewMode.PDF then "toggle-btn active" else "toggle-btn"),
        "PDF",
        onClick --> { _ => chartState.viewModeVar.set(DistributionViewMode.PDF) }
      ),
      button(
        cls <-- chartState.viewModeVar.signal.map(m =>
          if m == DistributionViewMode.CDF then "toggle-btn active" else "toggle-btn"),
        "CDF",
        onClick --> { _ => chartState.viewModeVar.set(DistributionViewMode.CDF) }
      )
    )

    div(
      cls := "distribution-chart-view",
      toggleEl,
      child <-- chartState.specSignal.combineWith(renderError$.signal).map { (state, renderErr) =>
        disposeChart()
        renderErr match
          case Some(msg) => renderChartError(msg)
          case None =>
            state match
              case LoadState.Idle       => renderIdle
              case LoadState.Loading    => renderLoading
              case LoadState.Failed(m)  => renderChartError(m)
              case LoadState.Loaded(sp) => renderChart(sp)
      },
      onUnmountCallback { _ => disposeChart() }
    )
```

`vegaEmbedOptions`: `js.Dynamic.literal("theme" -> "dark", "renderer" -> "svg",
"actions" -> false)` — matches `LECChartView` options.

Idle state render: the same placeholder content as `DistributionChartPlaceholder`
(icon + "Enter distribution parameters to see a preview") — no flash of empty content.

#### C.4 DesignView wiring

**⚠ CORRECTED:** `DesignView` is a pure structural component (ADR-019 Pattern 1) that
currently owns no state. Constructing `DistributionChartState` in `DesignView` would
violate this. The state must be constructed **above** `DesignView` and passed in as a
constructor parameter — just like `builderState`, `treeViewState`, and `wsState`.

The construction site is wherever `DesignView` is instantiated (likely `MainView` or
`AppState`). The exact location depends on where `WorkspaceKeySecret` and
`userIdAccessor` are available — follow the same call chain used to construct
`LECChartState`.

**`DesignView` signature change:**
```scala
// Before:
def apply(builderState: TreeBuilderState, treeViewState: TreeViewState, wsState: WorkspaceState): HtmlElement

// After:
def apply(
  builderState: TreeBuilderState,
  treeViewState: TreeViewState,
  wsState: WorkspaceState,
  distributionChartState: DistributionChartState
): HtmlElement
```

**Usage in `DesignView.apply`:**
```scala
// Replace:
bottom = DistributionChartPlaceholder(),
// With:
bottom = DistributionChartView(distributionChartState),
```

**Construction site** (wherever `DesignView(...)` is called):
```scala
// draftSignal derived from builderState's current form state signal
val distributionChartState = DistributionChartState(
  draftSignal    = builderState.draftSignal,   // StrictSignal[Option[LeafDistributionDraft]]
  keySignal      = wsState.keySignal,          // StrictSignal[Option[WorkspaceKeySecret]]
  userIdAccessor = () => wsState.currentUserId
)
DesignView(builderState, treeViewState, wsState, distributionChartState)
```

`builderState.draftSignal` is a `StrictSignal[Option[LeafDistributionDraft]]` derived
from the current form state (to be added to `TreeBuilderState` if not already present —
reflects the current in-flight draft, `None` if the form is empty or invalid).

#### C.5 CSS

The current `DistributionChartPlaceholder` uses `.distribution-chart-placeholder` as
the container class. The new view replaces this with `.distribution-chart-view` (keep
same layout rules — `height: 100%`, flexbox column).

Changes required:
- Add `.distribution-chart-view` with same layout as `.distribution-chart-placeholder`
  (or rename — check if `.distribution-chart-placeholder` is used elsewhere first)
- Add `.chart-mode-toggle` — horizontal flex row, `gap: 8px`, centered, margin-bottom
- Add `.toggle-btn` — outlined button; `.toggle-btn.active` — filled/accent style
  consistent with existing button components
- Add `.distribution-chart-canvas` — `flex: 1`, `min-height: 0` (prevents flexbox
  overflow in the split pane)
- No layout changes to the SplitPane structure

---

### Reference — New Files

| File | Module | Full path |
|---|---|---|
| `DistributionPreviewEndpoints.scala` | common | `modules/common/src/main/scala/com/risquanter/register/http/endpoints/` |
| `DistributionPreviewRequest.scala` | common | `modules/common/src/main/scala/com/risquanter/register/http/requests/` |
| `DistributionPreviewResponse.scala` | common | `modules/common/src/main/scala/com/risquanter/register/http/requests/` |
| `DistributionPreviewService.scala` | server | `modules/server/src/main/scala/com/risquanter/register/services/` |
| `DistributionPreviewController.scala` | server | `modules/server/src/main/scala/com/risquanter/register/http/controllers/` |
| `DistributionChartState.scala` | app | `modules/app/src/main/scala/app/state/` |
| `DistributionSpecBuilder.scala` | app | `modules/app/src/main/scala/app/chart/` |
| `DistributionChartView.scala` | app | `modules/app/src/main/scala/app/views/` |

---

### Phase D — Decision Science Features

*Goal: Add decision science quality signals on top of the working chart from Phase C.
All features are non-blocking improvements — Phase C is fully functional without them.*

#### D.1 Decision science features

- **Coherence echo:** `DistributionPreviewResponse.resolvedTerms` and `anchorCount` are
  the data sources (both fields added in Phase B B.2). `DistributionChartView` renders
  a `p` caption below the chart:
  - `resolvedTerms = Some(t)`, `t < anchorCount.get`: "Smoothed with {t} terms ({n} anchor points)"
  - `resolvedTerms = Some(t)`, `t == anchorCount.get`: "Exact fit with {t} terms"
  - `resolvedTerms = None`: no terms caption (lognormal mode)

- **Implied-ratio warning:** computed entirely in the frontend from `termsVar` /
  `quantilesVar` — no server call. In `RiskLeafFormState`:
  ```scala
  val impliedRatioWarning: Signal[Option[String]] =
    quantilesVar.signal.combineWith(percentilesVar.signal).map { (qStr, pStr) =>
      val qs = qStr.split(",").flatMap(_.trim.toDoubleOption)
      if qs.length >= 2 then
        val p10idx = pStr.split(",").indexWhere(_.trim.toDoubleOption.exists(_ <= 10))
        val p90idx = pStr.split(",").indexWhere(_.trim.toDoubleOption.exists(_ >= 90))
        if p10idx >= 0 && p90idx >= 0 && qs(p10idx) > 0 then
          val ratio = qs(p90idx) / qs(p10idx)
          if ratio > 100 then
            Some(f"P90/P10 ratio is $ratio%.0f — very high-severity losses modelled as >100× more likely than low-severity. Review whether this reflects expert judgment.")
          else None
        else None
      else None
    }
  ```
  Rendered as a warning badge below the quantiles field in `RiskLeafFormView`, visible
  only in expert mode.

- **Anchor-point placement:** covered by the PDF/CDF overlay layers in Phase C C.2.

- **Three-point elicitation framing:** the default `percentilesVar = Var("10, 50, 90")`
  is already the existing default (confirmed from `RiskLeafFormState.scala`). No change
  needed. It encodes the three-point estimation convention (minimum / most likely /
  maximum, or optimistic / base / pessimistic) without requiring statistical language.

