# Plan: Design View UX Improvements

**Date:** June 2026
**Status:** Planning
**Scope:** Originally frontend only (modules/app). Option A (remove `probability`
from `Distribution`) extends the scope to `common` and `server` modules.

---

## Overview

Five UX issues and one feature addition identified in the Design view. Each section
states the current behaviour, the root cause, the required change, and the exact
files to touch.

---

## Issue 1 — Placeholder text when no workspace exists

### Current behaviour
`DistributionChartView.renderIdle` shows `"Enter distribution parameters to see a
preview"` unconditionally — including when there is no workspace yet, where entering
parameters alone would never produce a preview.

### Required change
The idle text must be a function of workspace state:

| `keySignal` | Text shown |
|-------------|-----------|
| `None` (no workspace) | **"Create a risk tree to enable distribution previews"** |
| `Some(_)` (workspace active), form incomplete | `"Enter distribution parameters to see a preview"` |

### Files to change
| File | Change |
|------|--------|
| `app/views/DistributionChartView.scala` | `renderIdle` becomes `renderIdle(hasWorkspace: Boolean)`. Caller passes `chartState.keySignal.map(_.isDefined)`. |
| `app/state/DistributionChartState.scala` | No change — `keySignal` is already exposed. |

### Note on Issue +1 dependency
Once the preview toggle (Issue +1) is implemented, a third state is needed:
workspace exists but preview is disabled → `"Enable preview to see a distribution
chart"`. The `renderIdle` function will then take `PreviewIdleReason` (an enum
replacing the boolean) as its argument. Design Issue 1 first without the enum;
refactor to enum when Issue +1 is implemented.

---

## Issue 2 — Clear form does not fully reset; placeholder values shown verbatim

### 2a — Hardcoded default percentile value

**Root cause:** `RiskLeafFormState.percentilesVar` is initialised to `"10, 50, 90"`.
This is a concrete value, not a placeholder. When the user clears the form,
`resetFields()` restores it to `"10, 50, 90"` — it was never cleared. The field
therefore appears pre-filled, is submitted if the user does not change it, and
misleads the preview into treating blank quantiles as an error rather than an
incomplete state.

**Required change:** Initialise `percentilesVar` to `""`. The suggested value
`"10, 50, 90"` belongs in the `placeholderText` attribute of the text input only.
`resetFields()` resets `percentilesVar` to `""`.

| File | Change |
|------|--------|
| `app/state/RiskLeafFormState.scala` | `val percentilesVar: Var[String] = Var("")` |
| `app/state/RiskLeafFormState.scala` | `resetFields()`: `percentilesVar.set("")` |
| `app/views/RiskLeafFormView.scala` | `expertFields`: `placeholderText = "e.g., 10, 50, 90"` already set — no change needed there. |

### 2b — Placeholder UX (standard HTML pattern)

`textInput` already accepts a `placeholderText` parameter which maps to the HTML
`placeholder` attribute. The pattern is already in use for all other fields. No
structural change is needed beyond 2a above.

---

## Issue 3 — Preview error messages from server shown in chart area

### Current behaviour
When the form is partially filled, the client fires a preview request. The server
returns a validation error (e.g. `[request.quantiles] Quantiles are required for
expert mode`). `DistributionChartView.renderError(message)` displays this verbatim
as `"Preview error: [request.quantiles] Quantiles are required for expert mode"`.

### Root cause
The draft signal emits whenever any field changes, including incomplete states.
Server-side validation errors are surfaced as error text in the chart area.

### Required change — gate the draft signal on full form validity

The draft signal in `RiskLeafFormState` should only be non-`None` when the form is
fully valid (no field errors, no cross-field errors). This is the correct fix: the
server should never be called with an incomplete form.

`RiskLeafFormState` already has `hasErrors: Signal[Boolean]`. The `draftSignal`
subscription in `RiskLeafFormView` should be combined with `hasErrors` and only push
a draft when `hasErrors` is false.

**Effect:** The preview call is never made for an incomplete form. The chart area
stays in the idle state (showing the idle text) until all fields are valid. Server
validation errors from the preview endpoint therefore never reach the UI.

### On loading state flicker (follow-up question)
The `LoadState.Loading` intermediate state adds value for remote deployments where
round-trips may take 200–500 ms. On a local cluster the state is traversed
imperceptibly. The correct resolution is:

- Keep `LoadState.Loading` — it is correct for non-local environments.
- The flicker was caused by calling on every keystroke with an incomplete form.
  Once Issue 3's gate is in place, calls only fire when the form is complete and
  stable (after the 400 ms debounce). The `Loading` flash becomes a rare event and
  the flicker disappears in practice.
- No additional change to loading state handling is needed.

### Files to change
| File | Change |
|------|--------|
| `app/views/RiskLeafFormView.scala` | Draft push subscription: gate on `state.hasErrors.map(!_)` before pushing to `builderState.currentDraftVar` |

Concretely, change:
```scala
state.draftSignal --> builderState.currentDraftVar.writer
```
to a subscription that only pushes when the form has no errors:
```scala
state.draftSignal
  .withCurrentValueOf(state.hasErrors)
  .map { case (draft, errors) => if errors then None else draft }
  --> builderState.currentDraftVar.writer
```

---

## Issue 4 — In-place leaf editing

### Current behaviour
No edit path. The user must delete a leaf and manually re-enter all values.

### Required change

#### State additions — `TreeBuilderState`
Add `selectedLeafName: Var[Option[String]]` (keyed by draft name, which is unique
within the builder state). This is a builder-level selection, not a node ID, because
draft nodes have no server ID until the tree is saved.

```scala
val selectedLeafName: Var[Option[String]] = Var(None)
val isLeafSelected: Signal[Boolean] = selectedLeafName.signal.map(_.isDefined)
```

Add a method to populate a `RiskLeafFormState` from a `LeafDraft`:
```scala
def populateLeafForm(state: RiskLeafFormState, leaf: LeafDraft): Unit
```
This writes all field vars from the leaf's distribution draft.

Add a method to update an existing leaf in-place:
```scala
def updateLeaf(originalName: String, ...) : Validation[ValidationError, Unit]
```

#### TreePreview — wire `onNodeClick` for leaf rows
`TreePreview` renders leaf rows via `TreeNodeRow` with `onNodeClick = None`.
Wire `onNodeClick` to toggle `builderState.selectedLeafName`:
- Click unselected leaf → `selectedLeafName.set(Some(name))`; populate form
- Click selected leaf → `selectedLeafName.set(None)`; clear form

`TreeNodeRow.isSelected` is already wired-ready — pass
`builderState.selectedLeafName.signal.map(_ contains name)`.

#### RiskLeafFormView — mode-aware title, button, submit handler
`RiskLeafFormView` derives its UI mode from `builderState.selectedLeafName.signal`:

| `selectedLeafName` | Form title | Button text | Submit action |
|--------------------|-----------|-------------|---------------|
| `None` | `"Add Risk Leaf"` | `"Add Leaf"` | `builderState.addLeaf(...)` |
| `Some(name)` | `"Edit Risk Leaf"` | `"Update Leaf"` | `builderState.updateLeaf(name, ...)` |

**Clear Form** button: clears `builderState.selectedLeafName` as well as calling
`state.resetFields()`.

#### Shared infrastructure with Analyze view
`TreeNodeRow` (already shared) handles the selection visual (`node-selected` CSS
class) through its `isSelected` signal parameter — no duplication. The click
toggle pattern mirrors the existing `treeViewState.selectNode` pattern in
`TreeDetailView` / `TreeViewState`. The builder selection is simpler (name-keyed
vs. ID-keyed) because draft nodes have no server IDs.

### Files to change
| File | Change |
|------|--------|
| `app/state/TreeBuilderState.scala` | Add `selectedLeafName`, `isLeafSelected`, `populateLeafForm`, `updateLeaf` |
| `app/views/TreePreview.scala` | Wire `onNodeClick` and `isSelected` for leaf rows |
| `app/views/RiskLeafFormView.scala` | Mode-derived title, button text, submit dispatch; Clear Form clears selection |

---

## Issue 5 — In-place portfolio editing

### Identical pattern to Issue 4, applied to portfolios

#### State additions — `TreeBuilderState`
```scala
val selectedPortfolioName: Var[Option[String]] = Var(None)
val isPortfolioSelected: Signal[Boolean] = selectedPortfolioName.signal.map(_.isDefined)
def populatePortfolioForm(state: PortfolioFormState, portfolio: PortfolioDraft): Unit
def updatePortfolio(originalName: String, ...): Validation[ValidationError, Unit]
```

#### TreePreview — wire `onNodeClick` for portfolio rows
Same toggle logic as Issue 4, keyed by portfolio name. Clicking a portfolio row
sets `selectedPortfolioName` and populates `PortfolioFormView` fields.

#### PortfolioFormView — mode-aware title, button, submit handler

| `selectedPortfolioName` | Form title | Button text | Submit action |
|------------------------|-----------|-------------|---------------|
| `None` | `"Add Portfolio"` | `"Add Portfolio"` | `addPortfolio(...)` |
| `Some(name)` | `"Edit Portfolio"` | `"Update Portfolio"` | `updatePortfolio(name, ...)` |

**Mutual exclusivity:** selecting a leaf clears `selectedPortfolioName` and vice
versa. At most one node type is selected at a time.

### Files to change
| File | Change |
|------|--------|
| `app/state/TreeBuilderState.scala` | Add `selectedPortfolioName`, `isPortfolioSelected`, `populatePortfolioForm`, `updatePortfolio` |
| `app/views/TreePreview.scala` | Wire `onNodeClick` and `isSelected` for portfolio rows |
| `app/views/PortfolioFormView.scala` | Mode-aware title, button, submit dispatch |

---

## Issue +1 — "Show preview" toggle

### Rationale
Currently the preview fires automatically whenever a valid draft and workspace key
are present. Users may not always want a live preview (distraction, latency on
remote deployments). The toggle gives explicit control and provides a natural hook
for the workspace-gate message.

### Design

A `previewEnabledVar: Var[Boolean]` lives in `DistributionChartState`. Default:
`false`. The `Var` is forced to `false` when `keySignal` becomes `None`.

The toggle renders as a checkbox + label to the left of "Clear Form" in
`RiskLeafFormView`:

```
[ ] Show preview    [Clear Form]    [Add Leaf / Update Leaf]
```

The toggle is disabled (greyed, unchecked) when `keySignal.map(_.isEmpty)`.

#### Chart area state machine (full)

| Workspace | Toggle | Form valid | Chart area shows |
|-----------|--------|-----------|-----------------|
| No | — | — | "Create a risk tree to enable distribution previews" |
| Yes | Off | — | "Enable preview to see a distribution chart" |
| Yes | On | No | "Enter distribution parameters to see a preview" |
| Yes | On | Yes, loading | Loading state (rare, sub-second) |
| Yes | On | Yes, loaded | Chart |

The `renderIdle` function refactors to take a `PreviewIdleReason` enum:
```scala
enum PreviewIdleReason:
  case NoWorkspace
  case PreviewDisabled
  case ParametersIncomplete
```

#### Debounced fetch subscription update
`DistributionChartView`'s `draftSignal.changes` subscription gates on both
`keySignal.isDefined` and `previewEnabledVar.signal` before calling `loadPreview`.

### Files to change
| File | Change |
|------|--------|
| `app/state/DistributionChartState.scala` | Add `previewEnabledVar`; auto-reset to false when `keySignal` goes `None` |
| `app/views/DistributionChartView.scala` | Gate fetch on `previewEnabled`; `renderIdle(reason: PreviewIdleReason)` |
| `app/views/RiskLeafFormView.scala` | Render toggle checkbox wired to `chartState.previewEnabledVar`; disabled when no workspace |

Note: `previewEnabledVar` must be passed into `RiskLeafFormView` from its call site
in `DesignView` where `DistributionChartState` is constructed (ADR-019 Pattern 1
state-above-view).

---

## Distribution type hierarchy — structural fix (approved, not yet implemented)

### Problem

`DistributionDraft` / `LeafDistributionDraft` are used in two structurally different
roles that require different types:

| Role | Where | Correct type |
|------|-------|-------------|
| Form accumulator — user is still typing, values may be invalid | `RiskLeafFormState.toShapeDraft`, `draftSignal`, `currentDraftVar` | `DistributionDraft` (raw primitives by necessity) |
| Committed node — has already passed `Distribution.create()` | `LeafDraft.distribution`, `leavesVar`, `TreePreview` | `Distribution` (fully Iron-typed, from `common`) |

`addLeaf` calls `validateDistribution` (= `Distribution.create()`), obtains a
validated `Distribution`, and then **discards it** — storing the unvalidated
`LeafDistributionDraft` instead. `toLeafRequest` then re-validates the same data a
second time. This is the structural flaw.

### Correct model

```
RiskLeafFormState: Var[String] fields
  → toShapeDraft / draftSignal  → DistributionDraft   (form accumulator, transient)
  → addLeaf (validation gate)   → Distribution        (Iron-typed, stored)
  → leavesVar                   → LeafDraft.distribution: Distribution
  → toLeafRequest               reads Distribution directly, no re-validation
```

### Required changes

1. `LeafDraft.distribution: LeafDistributionDraft` → `LeafDraft.distribution: Distribution`
2. `LeafDistributionDraft` deleted — it served no purpose once `LeafDraft` carries `Distribution`
3. `addLeaf` — `dist: LeafDistributionDraft` stays as the input parameter (form submits this);
   `LeafDraft` is constructed with the `Distribution` result of `validateDistribution`, not `dist`
4. `toLeafRequest` — reads from `LeafDraft.distribution: Distribution` directly; no
   `validateDistribution` call; extracts `.value` only at the HTTP request boundary
5. `loadFromTree` — constructs `Distribution` directly from `RiskLeaf` fields (both carry
   the same Iron types: `DistributionType`, `Probability`, `Option[NonNegativeLong]`,
   `Option[PositiveInt]`). `DistributionDraft` no longer appears in `loadFromTree`.
6. `TreePreview` — `TreeNode.Leaf` reads from `l.distribution: Distribution`;
   `.assume` calls removed; all fields are already correctly typed.
7. `TreeNodeRow.leafTooltip` — `distType` parameter changes from `DistributionMode`
   to `DistributionType` (the domain type). `TreeDetailView` passes `leaf.distributionType`
   directly. `TreePreview` passes `l.distribution.distributionType` directly. The
   `DistributionMode.fromString` conversion added to `TreeDetailView` is removed —
   it was introduced only because `DistributionMode` was chosen as the intermediate
   type, which was wrong for this helper.

### What stays `DistributionDraft`

`currentDraftVar: Var[Option[DistributionDraft]]` and the entire preview pipeline
(`draftSignal` → `DistributionChartState` → `DistributionSpecBuilder`) remain
unchanged. The chart preview is explicitly a pre-commit use of `DistributionDraft`.
This is its correct and only role.

### `LeafDraft` / `PortfolioDraft` — not similarly affected

`LeafDraft` is not overused in the same way. It is only ever:
- created by `addLeaf` (post-validation) or `loadFromTree` (from already-validated `RiskLeaf`)
- read by `removeNode` (name/parent only), `toLeafRequest` (distribution fields), `TreePreview`

After `distribution` changes to `Distribution`, `LeafDraft` is correctly typed
throughout. `PortfolioDraft` is clean — carries only `SafeName.SafeName` fields.

---

## Dependency ordering

```
Issue 2a (percentile placeholder) ──┐
Issue 3  (gate draft on valid form) ─┴──► Issue +1 (preview toggle)
Issue 1  (idle text)                 ────► Issue +1 (idle text enum refactor)

Issue 4  (leaf edit) ─────────────────── independent
Issue 5  (portfolio edit) ─────────────── independent; implement after Issue 4
                                          to reuse the selection toggle pattern
```

Issues 2a, 2b, and 3 are quick wins (state initialisations + one signal combinator)
and should be done first — they remove noise that obscures the behaviour of
everything that follows. Issues 4 and 5 are the largest changes and share the
`TreeBuilderState` selection infrastructure.

---

## Option A — Remove `probability` from `Distribution` (approved, not yet implemented)

### Decision

`Distribution.probability: Probability` is removed. `Distribution` becomes a pure
shape type — it describes how losses are distributed, not how frequently the event
occurs. Probability is a leaf-level property orthogonal to the loss shape.

**Rationale (confirmed by codebase audit):**
- `Simulator.createSamplerFromLeaf` already calls
  `RiskSampler.fromDistribution(occurrenceProb = leaf.probability, lossDistribution = distribution)`.
  The simulation layer never reads `distribution.probability`. The field is wrong
  by construction — it exists only because `Distribution.create()` happened to
  validate it.
- `DistributionPreviewRequest` has no probability field, confirming probability is
  not a property of the shape for preview purposes.
- Removing `probability` from `Distribution` allows `DistributionDraft` to be
  eliminated entirely: `Distribution.create()` without probability can be called
  directly from `RiskLeafFormState`, making `draftSignal` emit `Option[Distribution]`.
  The form accumulator layer collapses into `Distribution` itself.

### What is eliminated

| Type | Status | Reason |
|------|--------|--------|
| `DistributionDraft` | **Deleted** | Was a raw-primitive accumulator so `Distribution.create()` could be called later. With probability removed, `Distribution.create()` can be called immediately from `RiskLeafFormState`. |
| `LeafDistributionDraft` | **Deleted** | Was a wrapper around `DistributionDraft` + `probability: Double`. `LeafDraft` will hold `distribution: Distribution` and `probability: Probability` as separate fields. |
| `toDistributionDraft` method | **Deleted** | In `RiskLeafFormState`. The method constructed a `LeafDistributionDraft`; no longer needed. |
| `toShapeDraft` method | **Deleted** | In `RiskLeafFormState`. Was a sub-step used by `toDistributionDraft`. Replaced by inline `Distribution.create()` call. |
| `DistributionMode.fromString` conversion | **Deleted** | Was added to `TreeDetailView` as a bridge from `DistributionType` (domain) to `DistributionMode` (form enum). `DistributionMode` no longer appears in call sites receiving `DistributionType`. |
| `DistributionMode` enum | **Scope reduced** | `DistributionMode` was also used in `DistributionDraft.distributionType`. With `DistributionDraft` deleted, `DistributionMode` becomes unused and should be deleted too. |

### Named wrapper for HTTP resolution layer

`Distribution.create()` no longer validates probability. HTTP resolution code
(`RiskTreeRequests`, `RiskTreeMaintenanceRequests`) validates probability separately.
To keep maps well-typed, introduce a small wrapper in the `common` module (alongside
`Distribution.scala`):

```scala
final case class ResolvedLeafDistribution(
  probability: Probability,
  shape:       Distribution
)
```

This replaces `Distribution` wherever a post-resolution map previously bundled the
two together. All three resolution maps in `RiskTreeRequests` change type:

```
Map[SafeName.SafeName, Distribution]
  → Map[SafeName.SafeName, ResolvedLeafDistribution]
```

### `DistributionType` constants (new — `common` module)

`DistributionSpecBuilder` (app) currently checks distribution type with
`d.distributionType == DistributionMode.Expert`. With `DistributionMode` deleted and
`distributionType` now typed as `DistributionType` (`String :| Match["^(expert|lognormal)$"]`),
the check must compare against a typed constant.

Add a companion for `DistributionType` in `Distribution.scala`:

```scala
object DistributionType:
  val Expert:    DistributionType = "expert".assume
  val Lognormal: DistributionType = "lognormal".assume
```

`DistributionSpecBuilder` then uses `d.distributionType == DistributionType.Expert`.

---

### Change map by module

#### `common` module

| File | Change |
|------|--------|
| `domain/data/Distribution.scala` | Remove `probability: Probability` from case class; remove `probability: Double` from `create()` signature; remove `probV` validation; remove from `Validation.validateWith` call. Add `DistributionType` companion object with `Expert` and `Lognormal` constants. |
| `domain/data/Distribution.scala` | Add `final case class ResolvedLeafDistribution(probability: Probability, shape: Distribution)` in the same file (or adjacent companion file). |
| `http/requests/RiskTreeRequests.scala` | `refineLeafDefs` return type: `Seq[(SafeName.SafeName, Option[SafeName.SafeName], Distribution)]` → `Seq[(SafeName.SafeName, Option[SafeName.SafeName], ResolvedLeafDistribution)]`. Same change to `refineExistingLeaves`. Remove `l.probability` from `Distribution.create()` calls in both methods. Construct `ResolvedLeafDistribution(Probability.refined(l.probability), shape)` at each call site. |
| `http/requests/RiskTreeRequests.scala` | `ResolvedCreate.leafDistributions: Map[SafeName.SafeName, Distribution]` → `Map[SafeName.SafeName, ResolvedLeafDistribution]`. Same for `ResolvedUpdate.existingLeafDistributions` and `addedLeafDistributions`. |
| `http/requests/RiskTreeMaintenanceRequests.scala` | `DistributionUpdateRequest.validate()` currently returns `Distribution`. After: returns `ResolvedLeafDistribution`. Remove `probability` from `Distribution.create()` call; refine probability separately; construct `ResolvedLeafDistribution`. |
| `http/requests/RiskTreeRequests.scala` | `validateDistributionUpdate` (line 54) is a one-liner that delegates directly to `DistributionUpdateRequest.validate()` and declares its return type as `Distribution`. When `DistributionUpdateRequest.validate()` changes to return `ResolvedLeafDistribution`, this method's return type annotation and any call sites must be updated to match. |

#### `server` module

| File | Change |
|------|--------|
| `services/RiskTreeServiceLive.scala` | `buildNodes` parameter `leafDistributions: Map[SafeName.SafeName, Distribution]` → `Map[SafeName.SafeName, ResolvedLeafDistribution]`. Inside `buildNodes`, the map lookup produces `dist: ResolvedLeafDistribution` instead of `dist: Distribution`; the source expression `dist.probability` is syntactically unchanged — it now resolves to `ResolvedLeafDistribution.probability` rather than the removed `Distribution.probability`. The two `buildNodes` call sites in `create` and `update` pass the updated map type. |

No changes needed to `Simulator.scala` — it reads `leaf.probability` from `RiskLeaf`
which is populated from `ResolvedLeafDistribution.probability` by `buildNodes`.

#### `app` module

| File | Change |
|------|--------|
| `state/TreeBuilderState.scala` | Delete `DistributionDraft`, `LeafDistributionDraft`. `LeafDraft.distribution: LeafDistributionDraft` → `LeafDraft.distribution: Distribution`. Add `LeafDraft.probability: Probability`. `addLeaf` signature: parameter changes from `dist: LeafDistributionDraft` to `shape: Distribution, probability: Probability`. `addLeaf` stores `LeafDraft(name, parent, shape, probability)` directly (no re-validation). `toLeafRequest` reads `draft.distribution.*` for shape fields and `draft.probability.value` for the wire `Double`. `validateDistribution` signature removes `probability: Double` parameter. `loadFromTree` constructs `Distribution` from `RiskLeaf` shape fields; reads `riskLeaf.probability` for `LeafDraft.probability`. `currentDraftVar: Var[Option[DistributionDraft]]` → `Var[Option[Distribution]]`. |
| `state/RiskLeafFormState.scala` | Delete `toDistributionDraft`, `toShapeDraft`. `draftSignal: Signal[Option[Distribution]]` — derived by calling `Distribution.create()` inline, gated by whether the `Validation` result is a success. Combine all shape-field reactive inputs (`distributionTypeVar`, `percentilesVar`, `quantilesVar`, `minLossVar`, `maxLossVar`, `termsVar`, `expertParamsVar`, `lognormalParamsVar`) into a `draftSignal` combinator that calls `Distribution.create()` and maps to `Some(dist)` on success, `None` on failure. Add import for `Distribution`. |
| `state/DistributionChartState.scala` | `draftSignal: StrictSignal[Option[DistributionDraft]]` → `StrictSignal[Option[Distribution]]`. |
| `views/DistributionChartView.scala` | `toPreviewRequest(draft: Distribution)` — reads `draft.distributionType`, `draft.minLoss`, `draft.maxLoss`, `draft.percentiles`, `draft.quantiles`, `draft.terms` directly. Delete `DistributionDraft` import. |
| `views/DistributionSpecBuilder.scala` | `draft: Option[DistributionDraft]` → `draft: Option[Distribution]`. `d.distributionType == DistributionMode.Expert` → `d.distributionType == DistributionType.Expert`. Delete `DistributionMode` import. |
| `views/TreePreview.scala` | `TreeNode.Leaf` construction from `leavesVar`: reads `l.distribution: Distribution` for shape fields; `l.probability: Probability` for probability; all `.assume` calls removed (values are already Iron-typed). `distType = l.distribution.distributionType` (type `DistributionType`). |
| `views/RiskLeafFormView.scala` | Call site of `builderState.addLeaf` — no longer constructs `LeafDistributionDraft`; passes `shape: Distribution, probability: Probability` where `shape` comes from `leafFormState.draftSignal` and `probability` is refined from `probabilityVar`. |
| `components/TreeNodeRow.scala` | `leafTooltip` `distType: DistributionMode` → `distType: DistributionType`. Delete `DistributionMode` import from `TreeNodeRow`. |
| `views/TreeDetailView.scala` | Already passes `leaf.distributionType: DistributionType` — `DistributionMode.fromString` conversion removed. `DistributionMode` import removed. |

---

### Implementation sequence

Steps 1 and 2 are in `common` and compile independently. Steps 3 and 4 depend on
Step 1. Step 5 (app module) depends on Steps 1 and 3.

**Step 1 — `Distribution.scala` (common)**
Remove `probability` field and parameter. Add `DistributionType` companion. Add
`ResolvedLeafDistribution`. This step will break everything that depends on
`Distribution.probability` — expected.

**Step 2 — `RiskTreeRequests.scala` (common)**
Update `refineLeafDefs` and `refineExistingLeaves` to return tuples carrying
`ResolvedLeafDistribution`. Update all three resolved maps. Fix `Distribution.create()`
call sites.

**Step 3 — `RiskTreeMaintenanceRequests.scala` (common)**
Update `DistributionUpdateRequest.validate()` to return `ResolvedLeafDistribution`.

**Step 4 — `RiskTreeServiceLive.scala` (server)**
Update `buildNodes` parameter type. Update probability read site.

**Step 5 — App module (all files in parallel where possible)**
- `TreeBuilderState.scala` — structural changes to draft types
- `RiskLeafFormState.scala` — new `draftSignal` derivation
- `DistributionChartState.scala` — type annotation only
- `DistributionChartView.scala` — `toPreviewRequest` parameter type
- `DistributionSpecBuilder.scala` — `DistributionType.Expert` constant
- `TreePreview.scala` — construction from typed fields, remove `.assume`
- `TreeNodeRow.scala` — `distType` parameter type
- `TreeDetailView.scala` — remove `DistributionMode` bridge
- `RiskLeafFormView.scala` — `addLeaf` call site

---

## What is shared with the Analyze view

| Component | Currently shared | Notes |
|-----------|-----------------|-------|
| `TreeNodeRow` | ✅ Yes | Already handles `isSelected` signal + `onNodeClick`. Two tooltip utility functions to be added to its companion (see Tooltip Abstraction task below). |
| Selection toggle pattern | Partial | `TreeViewState.selectNode` (Analyze) is the reference pattern. Design view implements an analogous `selectedLeafName` / `selectedPortfolioName` — name-keyed instead of ID-keyed because draft nodes have no server IDs yet. |
| Tooltip format | ✅ Aligned | `TreePreview.TreeNode.Leaf` now carries all optional distribution fields. Tooltip building logic to be consolidated via `TreeNodeRow` companion utilities (see Tooltip Abstraction task). |
| Node icons | ✅ Yes | Both views call `Icons.portfolio` / `Icons.leaf` identically. |

No new shared components are needed. The alignment between Design and Analyze is
achieved through `TreeNodeRow` parameters, not through additional abstraction.

---

## Tooltip Abstraction — Iron-typed tooltip utilities in `TreeNodeRow`

**Status:** Implemented (Option B). `.assume` at construction site in `TreePreview` will be removed by Option A — see implementation sequence in that section.

### Background

After field-expanding `TreePreview.TreeNode.Leaf` to carry `percentiles`, `quantiles`,
`minLoss`, `maxLoss`, the `fold("")` optional-field building logic is duplicated
between `TreePreview.TreeNode.tooltip` and `TreeDetailView.nodeTooltip`. The
correct home for the shared logic is the `TreeNodeRow` companion, which is already
the shared abstraction for both views.

Additionally, a compiler-hygiene finding: `case Leaf(n, dt, p, _, _, _, _)` in
`TreeNode.label` uses positional destructuring with four wildcards on a 7-arity
case. If the constructor gains a field the match silently ignores it. Fix: use
`case l: Leaf` + named field access.

### Decision: Option B — Iron-typed signatures

Helper functions accept the refined domain types directly. `TreeNode.Leaf` is
updated to carry Iron types instead of raw strings. The `.toString` / `.value`
widening that would otherwise happen at every call site is eliminated.

### Type reference

All types live in
`com.risquanter.register.domain.data.iron` (module `common`, available to `app`
via `common.js` cross-compile).

| Field | Type | Note |
|-------|------|------|
| `name` (leaf or portfolio) | `SafeName.SafeName` | Opaque over `SafeShortStr`. `.value` gives the underlying `SafeShortStr`; `.toString` interpolation works via the `extension`. |
| `distType` | `DistributionType` | `String :| Match["^(expert|lognormal)$"]` — transparent Iron alias, interpolates directly. |
| `id` (persisted node only) | `Option[NodeId]` | `NodeId` is `case class NodeId(toSafeId: SafeId.SafeId)`. `.value` on the inner `SafeId` gives the ULID string. Use `id.map(_.toSafeId.value)` for the string representation in the tooltip. |
| `probability` | `Probability` | `Double :| (Greater[0.0] & Less[1.0])` — transparent, interpolates directly. |
| `percentiles` / `quantiles` | `Option[Array[Double]]` | No Iron refinement exists for these; type unchanged. |
| `minLoss` / `maxLoss` | `Option[NonNegativeLong]` | `Long :| GreaterEqual[0L]` — transparent, interpolates directly. Currently `TreeNode.Leaf` is constructed from `LeafDraft` which carries `Option[Long]` via the draft pipeline — construction uses `.map(_.assume)`. After Option A, `LeafDraft.distribution: Distribution` carries `Option[NonNegativeLong]` directly and `.assume` is removed. |
| `childCount` | `Option[Int]` | `portfolio.childIds.length` — plain `Int`, no Iron refinement for list length. |

### New signatures in `TreeNodeRow` companion

```scala
import com.risquanter.register.domain.data.iron.{
  SafeName, DistributionType, NodeId, NonNegativeLong, Probability
}

def leafTooltip(
  name:        SafeName.SafeName,
  distType:    DistributionType,
  probability: Probability,
  id:          Option[NodeId]            = None,
  percentiles: Option[Array[Double]]     = None,
  quantiles:   Option[Array[Double]]     = None,
  minLoss:     Option[NonNegativeLong]   = None,
  maxLoss:     Option[NonNegativeLong]   = None
): String

def portfolioTooltip(
  name:       SafeName.SafeName,
  id:         Option[NodeId] = None,
  childCount: Option[Int]    = None
): String
```

`id` rendering: `id.fold("")(n => s"\nID:           ${n.toSafeId.value}")`.

### Cascade: `TreeNode.Leaf` in `TreePreview`

`TreeNode` is a `private enum` inside `TreePreview`. `Leaf` currently stores:
```scala
case Leaf(n: String, distType: String, probability: Double,
          percentiles: Option[Array[Double]], quantiles: Option[Array[Double]],
          minLoss: Option[Long], maxLoss: Option[Long])
```

With Option B it becomes:
```scala
case Leaf(
  n:           SafeName.SafeName,
  distType:    DistributionType,
  probability: Probability,
  percentiles: Option[Array[Double]],
  quantiles:   Option[Array[Double]],
  minLoss:     Option[NonNegativeLong],
  maxLoss:     Option[NonNegativeLong]
)
```

`TreePreview.scala` will need the same Iron imports added to its import block.

**Construction note (current — pre-Option-A)** — `LeafDraft` (builder state) currently
carries `Option[Long]` for `minLoss`/`maxLoss` (via `LeafDistributionDraft`), while
`TreeNode.Leaf` carries `Option[NonNegativeLong]`. The construction site in `renderTree`
uses `.map(_.assume)` — Iron's trusted-cast for values known valid by construction
(the draft has already been validated by the smart constructor path). A comment at the
call site references the upstream validation.

After Option A, `LeafDraft.distribution: Distribution` carries `Option[NonNegativeLong]`
directly. The `.assume` call is removed. See Option A implementation sequence, Step 5.

`label` method: change positional destructuring to named field access:
```scala
def label: String = this match
  case Portfolio(n)  => n.value
  case l: Leaf       => s"${l.n.value} (${l.distType}, p=${f"${l.probability}%.2f"})"
```

`tooltip` method: delegates to `TreeNodeRow.leafTooltip` / `portfolioTooltip`.

### Call-site changes in `TreeDetailView.nodeTooltip`

`TreeDetailView` already imports `TreeNodeRow`. After the change:

```scala
private def nodeTooltip(node: RiskNode): String = node match
  case leaf: RiskLeaf =>
    TreeNodeRow.leafTooltip(
      name        = leaf.name,
      distType    = leaf.distributionType,
      probability = leaf.probability,
      id          = Some(leaf.id),
      percentiles = leaf.percentiles,
      quantiles   = leaf.quantiles,
      minLoss     = leaf.minLoss,
      maxLoss     = leaf.maxLoss
    )
  case portfolio: RiskPortfolio =>
    TreeNodeRow.portfolioTooltip(
      name       = portfolio.name,
      id         = Some(portfolio.id),
      childCount = Some(portfolio.childIds.length)
    )
```

Note: `leaf.id` is `NodeId`; `portfolio.id` is `NodeId`. Both match the parameter
type directly — no `.value` unwrapping needed.

### Files changed (all done)

| File | Change |
|------|--------|
| `app/src/main/scala/app/components/TreeNodeRow.scala` | ✅ Added `leafTooltip` and `portfolioTooltip` to companion; added Iron imports. |
| `app/src/main/scala/app/views/TreePreview.scala` | ✅ Updated `TreeNode.Leaf` fields to Iron types; fixed `label` to named-field access; delegated `tooltip` to `TreeNodeRow`; added Iron imports; used `.assume` at construction site. |
| `app/src/main/scala/app/views/TreeDetailView.scala` | ✅ Replaced inline `nodeTooltip` body with delegation to `TreeNodeRow` helpers. |

**Remaining after Option A:** `distType: DistributionMode` in `TreeNodeRow.leafTooltip`
will change to `distType: DistributionType`; `TreeDetailView` removes `DistributionMode`
import and conversion; `TreePreview` removes `.assume` call. These are part of the
Option A implementation sequence (Step 5).
