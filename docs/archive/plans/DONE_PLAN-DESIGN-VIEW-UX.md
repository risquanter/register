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

Prerequisite (preserve node identity) ──► Option A (distribution refactor)
                                      └──► Issues 4 & 5 (edit loaded trees)
```

The **prerequisite** (preserve node identity on update) lands before Option A and
before Issues 4 & 5, because all three operate on trees loaded from the server and
are only correct once a node's identity survives an edit (see the prerequisite
section below).

Issues 2a, 2b, and 3 are quick wins (state initialisations + one signal combinator)
and should be done first — they remove noise that obscures the behaviour of
everything that follows. Issues 4 and 5 are the largest changes and share the
`TreeBuilderState` selection infrastructure.

---

## Prerequisite — Preserve node identity on update (approved, not yet implemented)

**Lands before Option A and before Issues 4 & 5.**

### Why this is a prerequisite, not a feature

Issues 4 and 5 (in-place leaf / portfolio editing) and Option A's `loadFromTree`
rewrite all operate on a tree **loaded from the server**. Editing a loaded tree is
only correct if a node's identity survives the edit. Today it does not — so this
must land first.

### The defect (verified by codebase audit)

`RiskTreeUpdateRequest` has two buckets:

- `portfolios` / `leaves` — carry `id: String`; `RiskTreeUpdateRequest.resolve` keeps
  these ids verbatim → `NodeId(id)` → the **same** Irmin path `nodes/{id}`.
  (Identity-preserving.)
- `newPortfolios` / `newLeaves` — no id; each node gets a freshly minted `SafeId`.

The frontend never uses the identity-preserving bucket:

- `loadFromTree` discards every `NodeId` (`LeafDraft` / `PortfolioDraft` hold only
  name + parent-name).
- `toUpdateRequest` routes **everything** into `newPortfolios` / `newLeaves` (its own
  comment: *"full replacement with all nodes as 'new'"*).

**Consequence:** editing one distribution param reassigns **every** `NodeId` in the
tree. In `RiskTreeRepositoryIrmin.update`, `obsoleteNodeIds = before.diff(after)` is
then the *entire* old node set → every `nodes/{old}` removed, every `nodes/{new}`
written. This:

- **Destroys per-node git history** — `git log nodes/{id}` cannot follow a leaf across
  edits (defeats ADR-004a per-node storage and ADR-021 node capability URLs).
- **Over-invalidates the cache** — `InvalidationHandler.computeAffectedNodes` sees
  "all removed + all added" instead of its "common node, data changed → invalidate
  just this node" branch, flushing the whole tree's LEC cache on every edit.

### What is already correct (do not touch)

- **Whole-tree submission + whole-graph validation is the correctness-by-construction
  mechanism and stays.** `validateTopologyUpdate` enforces graph-global invariants
  (unique names, single root, parent references resolve, no cycles, non-empty
  portfolios) that cannot be checked from one node in isolation; `RiskTree.fromNodes`
  re-validates the rebuilt index. An accepted update is always a fully-valid tree. A
  per-node PATCH could not escape this — it would still have to load, patch, and re-run
  the same whole-graph validation.
- **Deletion-by-omission is the intended delete verb.** The persisted set is exactly
  `existing ++ added`; a node absent from the submitted tree is removed by
  `obsoleteNodeIds`. Correct and unchanged.
- **The server already supports identity-preserving update.** No server, endpoint, or
  Irmin change is required. The fix is entirely frontend.

### The fix — frontend only

Make the loaded `NodeId` survive the round-trip, so edit / add / remove map to clean
git-like per-path semantics:

| User action | Bucket | Irmin effect | History |
|-------------|--------|--------------|---------|
| Edit a loaded node (param / name / reparent) | `existing` (same id) | re-write **same** `nodes/{id}` | chain continues |
| Add a node this session | `new*` (no id) | new path | new chain |
| Remove a node | omitted | path removed | chain ends |
| Untouched loaded node | `existing` (same id) | identical content → blob dedup | no-op |

Whole-tree submission and full validation are untouched — the complete node set is
still sent and validated as a graph. **Only the id-bucketing changes.**

### Change map (`app` module only)

| File | Change |
|------|--------|
| `state/TreeBuilderState.scala` | Add `id: Option[NodeId]` to `LeafDraft` and `PortfolioDraft` (`None` = added this session, `Some` = loaded from server). `loadFromTree` populates `id = Some(node.id)` for every node. `toUpdateRequest` stops delegating wholesale to `new*`: it **partitions** drafts on `id` — `Some` → `RiskLeafUpdateRequest` / `RiskPortfolioUpdateRequest` (carrying the node's id string as their `id: String`) into the `leaves` / `portfolios` buckets; `None` → the existing `RiskLeafDefinitionRequest` / `RiskPortfolioDefinitionRequest` into `newLeaves` / `newPortfolios`. `toRequest` (create flow) is unchanged — every draft has `id = None`. Add imports for `NodeId`, `RiskLeafUpdateRequest`, `RiskPortfolioUpdateRequest`. |

### Verification step (before relying on the round-trip)

Confirm a `NodeId` rendered to its underlying string and re-refined server-side via
`ValidationUtil.refineId` yields the **same** `SafeId` (both wrap the same ULID-style
string). If `refineId` is stricter than `NodeId`'s own constructor, surface it as a
Blocked State decision rather than working around it.

### Interaction with Option A

Both rewrite `loadFromTree` and the `LeafDraft` / `PortfolioDraft` shapes. Because this
prerequisite lands **first**:

- The drafts already carry `id: Option[NodeId]` when Option A retypes
  `LeafDraft.distribution` to `Distribution` and adds `probability: Probability`.
- Option A's `loadFromTree` rewrite **preserves** the `NodeId` set here — it no longer
  "drops the NodeId"; it projects shape fields directly **and** keeps identity.

### Non-goal (dropped)

Per-node update **endpoints** (a net-new `updateNode` repo method + Tapir endpoint +
controller + ADR) are **not** pursued. The unwired `DistributionUpdateRequest` /
`NodeRenameRequest` request types remain dead scaffolding; Option A only keeps them
type-consistent. This identity-preservation fix delivers working, per-node-correct
editing through the existing whole-tree path, making per-node endpoints an optional
future nicety rather than a correctness need.

---

## Option A — Remove `probability` from `Distribution` (approved, not yet implemented)

### Decision

**The goal is to dissolve a conflation, not to remove a field.** `Distribution`
currently means two orthogonal things at once:

- **shape** — *how* a loss is distributed (expert percentiles/quantiles, or
  lognormal min/max bounds)
- **frequency** — *how often* the event occurs (`probability`)

These are independent axes. A loss shape is fully meaningful with no probability
attached — that is exactly what the distribution preview chart renders. Bundling
`probability` into `Distribution` forces every consumer of "the shape" to also
carry a frequency it does not need, and it is the root cause of the entire
draft-shadow apparatus (see below).

`Distribution.probability: Probability` is therefore removed. `Distribution` becomes
a pure **shape** type. Frequency becomes a separate leaf-level field
(`probability: Probability`) carried alongside it.

**`DistributionDraft` / `LeafDistributionDraft` are scar tissue of the conflation,
and their deletion is a direct entailment of this separation — not a separate
cleanup task.** The reasoning is a single causal chain:

1. The preview pipeline has a *shape* but no *frequency*.
2. `Distribution.create()` refused to produce a `Distribution` without a
   `probability`.
3. So a probability-free shadow of `Distribution` (`DistributionDraft`) had to be
   invented to carry shape alone, and a wrapper (`LeafDistributionDraft`) to staple
   probability back on.
4. That shadow then leaked into `leavesVar` storage, where committed nodes were held
   as *unvalidated* drafts and re-validated on every `toLeafRequest` — a
   validated → unvalidated → validated round-trip (most visible in `loadFromTree`,
   which downgrades an already-Iron-typed `RiskLeaf` into raw primitives).

Once probability leaves `Distribution`, step 2's obstacle is gone: `Distribution`
*is* the probability-free shape type, the preview pipeline can hold a real
`Distribution`, and the reason `DistributionDraft` ever existed no longer exists.
It vanishes as a consequence of the separation, taking `LeafDistributionDraft` with
it.

**Rationale (confirmed by codebase audit):**
- `Simulator.createSamplerFromLeaf` already calls
  `RiskSampler.fromDistribution(occurrenceProb = leaf.probability, lossDistribution = distribution)`.
  The simulation layer never reads `distribution.probability` — shape and frequency
  are already separate at the point of use. The field on `Distribution` exists only
  because `Distribution.create()` happened to validate it.
- `DistributionPreviewRequest` has no probability field, confirming probability is
  not a property of the shape for preview purposes.
- With probability removed, `Distribution.create()` can be called directly from
  `RiskLeafFormState`, making `draftSignal` emit `Option[Distribution]`. The form
  accumulator layer collapses into `Distribution` itself.

### What stays, what goes — the `*Draft` family

The `Draft` suffix conflates two unrelated ideas. Separating them is the whole point:

| Type | Decision | Why |
|------|----------|-----|
| `PortfolioDraft` | **Keep** (the prerequisite adds `id: Option[NodeId]`) | Already fully Iron-typed (`SafeName` fields). It is the proof that a builder "Draft" *should* be fully validated. Represents a node addressed by name in the builder; the prerequisite's `id` distinguishes a loaded node (`Some`) from one added this session (`None`). |
| `LeafDraft` | **Keep, fully typed** (the prerequisite adds `id: Option[NodeId]`) | Genuine concept (a builder node, parent-by-name, identity carried as `Option[NodeId]`). Its `distribution` field changes from the raw `LeafDistributionDraft` to the validated `Distribution`; a separate `probability: Probability` field is added. After this it carries no raw primitives. |
| `LeafDistributionDraft` | **Deleted** | Encoded no genuine difference from "a `Distribution` plus a `Probability`". Existed only to staple probability onto the shadow shape. With `LeafDraft` holding `distribution: Distribution` and `probability: Probability` as two clean fields, there is nothing left to staple. |
| `DistributionDraft` | **Deleted** | The probability-free shadow of `Distribution`. Its entire reason for existence was step 2 above. Gone once `Distribution` itself is probability-free. |

### Other eliminations

| Type | Status | Reason |
|------|--------|--------|
| `toDistributionDraft` method | **Deleted** | In `RiskLeafFormState`. Constructed a `LeafDistributionDraft`; no longer needed. |
| `toShapeDraft` method | **Deleted** | In `RiskLeafFormState`. Was a sub-step of `toDistributionDraft`. Replaced by an inline `Distribution.create()` call in `draftSignal`. |
| `DistributionMode.fromString` bridge in `TreeDetailView` | **Deleted** | Was added only to convert `DistributionType` (domain) → `DistributionMode` (form enum) for the tooltip helper. The display helpers move to `DistributionType` directly, so the bridge disappears. |

### `DistributionMode` — kept (correction to earlier draft of this plan)

`DistributionMode` is **not** deleted. It is the form's Expert/Lognormal mode-toggle
enum and is load-bearing in the form layer, independent of `DistributionDraft`:

- `RiskLeafFormState.distributionModeVar: Var[DistributionMode]` — the toggle state,
  read by ~10 reactive validation signals (`percentilesErrorRaw`, `quantilesErrorRaw`,
  cross-field checks, `minLoss`/`maxLoss` errors) and `resetFields`.
- `RiskLeafFormView` — the radio group (`selectedVar = state.distributionModeVar`)
  and the field-switching `child <-- distributionModeVar.signal.map { … }`.

Only the three **display-layer** usages of `DistributionMode` migrate to the domain
`DistributionType`: `TreeNodeRow.leafTooltip`, `TreePreview.TreeNode.Leaf`, and
`DistributionSpecBuilder`'s mode check. `DistributionMode` remains the form toggle
and is the boundary type between the radio UI and the domain `DistributionType`.

### HTTP resolution layer — carry probability as a bare tuple (no new type)

`Distribution.create()` no longer validates probability, so the HTTP resolution code
(`RiskTreeRequests`, `RiskTreeMaintenanceRequests`) must carry the refined
`Probability` *next to* the `Distribution` shape from the point of resolution to
`buildNodes`.

**No new named type is introduced.** This layer already passes resolved leaf data as
tuples and name-keyed maps; the only change is widening them to carry `Probability`
alongside `Distribution`. The two correct types — `Probability` and `Distribution` —
travel together as a bare pair:

```
Map[SafeName.SafeName, Distribution]
  → Map[SafeName.SafeName, (Probability, Distribution)]
```

`buildNodes` destructures `val (prob, shape) = leafDistributions(node.name)` and
passes both to `RiskLeaf.fromValidated`. (An earlier draft of this plan proposed a
`ResolvedLeafDistribution` wrapper — rejected: it is exactly the kind of
bundling-only type this refactor exists to remove.)

### `DistributionType` constants (new — `common` module)

`DistributionSpecBuilder` (app) currently checks distribution type with
`d.distributionType == DistributionMode.Expert`. After the migration its `draft`
field is a `Distribution`, so `d.distributionType` is a `DistributionType`
(`String :| Match["^(expert|lognormal)$"]`). The check must compare against a typed
constant rather than a `DistributionMode` case.

The codebase already has the established home for Iron-typed literal constants:
`object IronConstants` in `domain/data/iron/OpaqueTypes.scala` (documented purpose:
*"use these instead of `1.refineUnsafe[…]` scattered throughout the codebase …
compile-time safe since Iron validates literal values at compile time"*). Add the
two distribution-type constants there, as plain literal assignments — Iron validates
the literal against the `Match` constraint at compile time, so **no `.assume` is
needed**:

```scala
// in IronConstants, under a "DistributionType constants" header
val Expert:    DistributionType = "expert"
val Lognormal: DistributionType = "lognormal"
```

`DistributionSpecBuilder` then uses `d.distributionType == IronConstants.Expert`.
This supersedes any earlier suggestion of a companion object in `Distribution.scala`
— `IronConstants` is the convention-aligned location and `DistributionType` is
defined in that same file.

---

### Change map by module

#### `common` module

| File | Change |
|------|--------|
| `domain/data/iron/OpaqueTypes.scala` | Add `Expert` / `Lognormal` `DistributionType` constants to `object IronConstants` (plain literal assignments; no `.assume`). |
| `domain/data/Distribution.scala` | Remove `probability: Probability` from case class; remove `probability: Double` from `create()` signature; remove `probV` validation; remove from `Validation.validateWith` call. No new type added — probability travels as a bare `(Probability, Distribution)` pair in the resolution layer. |
| `http/requests/RiskTreeRequests.scala` | `refineLeafDefs` return type: `Seq[(SafeName.SafeName, Option[SafeName.SafeName], Distribution)]` → `Seq[(SafeName.SafeName, Option[SafeName.SafeName], Probability, Distribution)]`. Same change to `refineExistingLeaves` (its tuple already carries a `SafeId.SafeId` first). Remove `l.probability` from `Distribution.create()` calls in both methods; refine it separately with `ValidationUtil.refineProbability` and add it to the tuple via the same `Validation.validateWith`. |
| `http/requests/RiskTreeRequests.scala` | `ResolvedCreate.leafDistributions: Map[SafeName.SafeName, Distribution]` → `Map[SafeName.SafeName, (Probability, Distribution)]`. Same for `ResolvedUpdate.existingLeafDistributions` and `addedLeafDistributions`. Update the `.collect`/`.map` that build these maps in `RiskTreeDefinitionRequest.resolve` and `RiskTreeUpdateRequest.resolve` to key to the `(prob, shape)` pair. |
| `http/requests/RiskTreeMaintenanceRequests.scala` | `DistributionUpdateRequest.validate()` currently returns `Distribution`. After: returns `(Probability, Distribution)`. Remove `probability` from `Distribution.create()` call; refine it separately; return the pair. |
| `http/requests/RiskTreeRequests.scala` | `validateDistributionUpdate` (line 54) is a one-liner that delegates directly to `DistributionUpdateRequest.validate()` and declares its return type as `Distribution`. Change its return type to `(Probability, Distribution)` to match; update any call sites. |

#### `server` module

| File | Change |
|------|--------|
| `services/RiskTreeServiceLive.scala` | `buildNodes` parameter `leafDistributions: Map[SafeName.SafeName, Distribution]` → `Map[SafeName.SafeName, (Probability, Distribution)]`. Inside `buildNodes`, the lookup `val dist = leafDistributions(node.name)` becomes `val (prob, shape) = leafDistributions(node.name)`; pass `prob` and `shape`'s fields to `RiskLeaf.fromValidated` (currently `dist.probability` → `prob`, `dist.distributionType` → `shape.distributionType`, etc.). The two `buildNodes` call sites in `create` and `update` pass the updated map type. |

No changes needed to `Simulator.scala` — it reads `leaf.probability` from `RiskLeaf`
which `buildNodes` populates from the tuple's `prob` element.

#### `app` module

| File | Change |
|------|--------|
| `state/TreeBuilderState.scala` | Delete `DistributionDraft`, `LeafDistributionDraft`. `LeafDraft.distribution: LeafDistributionDraft` → `LeafDraft.distribution: Distribution`. Add `LeafDraft.probability: Probability`. `addLeaf` signature: parameter changes from `dist: LeafDistributionDraft` to `shape: Distribution, probability: Probability`. `addLeaf` stores `LeafDraft(name, parent, shape, probability)` directly (no re-validation). `toLeafRequest` reads `draft.distribution.*` for shape fields and `draft.probability` for probability — no `validateDistribution` re-call; `.value` only at the wire boundary. Delete the `validateDistribution` helper. `loadFromTree` becomes a structure-preserving projection: build `Distribution` directly from the `RiskLeaf`'s already-Iron-typed shape fields (`distributionType`, `minLoss`, `maxLoss`, `percentiles`, `quantiles`, `terms`) and copy `riskLeaf.probability` into `LeafDraft.probability` — **preserves the `NodeId`** (written into `LeafDraft.id` by the prerequisite), no downgrade to raw primitives, no `DistributionMode.fromString` round-trip. `currentDraftVar: Var[Option[DistributionDraft]]` → `Var[Option[Distribution]]`. |
| `state/RiskLeafFormState.scala` | Delete `toDistributionDraft`, `toShapeDraft`. `draftSignal: Signal[Option[Distribution]]` — derived by calling `Distribution.create()` inline, gated by whether the `Validation` result is a success. Combine the shape-field reactive inputs (`distributionModeVar`, `percentilesVar`, `quantilesVar`, `minLossVar`, `maxLossVar`, `termsVar`) into a `draftSignal` combinator that calls `Distribution.create()` and maps to `Some(dist)` on success, `None` on failure. `distributionModeVar` (a `DistributionMode`) supplies the mode via `.toApiString` at the `create()` call. Add import for `Distribution`. |
| `state/DistributionChartState.scala` | `draftSignal: StrictSignal[Option[DistributionDraft]]` → `StrictSignal[Option[Distribution]]`. |
| `views/DistributionChartView.scala` | `toPreviewRequest(draft: Distribution)` — reads `draft.distributionType`, `draft.minLoss`, `draft.maxLoss`, `draft.percentiles`, `draft.quantiles`, `draft.terms` directly. `distributionType` is now a `DistributionType` (use `.toString` for the wire string, not `.toApiString`). Delete `DistributionDraft` import. |
| `chart/DistributionSpecBuilder.scala` | `draft: Option[DistributionDraft]` → `draft: Option[Distribution]`. `d.distributionType == DistributionMode.Expert` → `d.distributionType == IronConstants.Expert`. Replace the `DistributionMode` import with `IronConstants`. |
| `views/TreePreview.scala` | `TreeNode.Leaf` construction from `leavesVar`: reads `l.distribution: Distribution` for shape fields; `l.probability: Probability` for probability; all `.assume` calls removed (values are already Iron-typed). `distType` field type changes from `DistributionMode` to `DistributionType`, fed by `l.distribution.distributionType`. Replace the `DistributionMode` import with `DistributionType`. |
| `views/RiskLeafFormView.scala` | Call site of `builderState.addLeaf` — no longer constructs `LeafDistributionDraft`; passes `shape: Distribution, probability: Probability` where `shape` comes from `leafFormState.draftSignal` and `probability` is refined from `probabilityVar`. |
| `components/TreeNodeRow.scala` | `leafTooltip` `distType: DistributionMode` → `distType: DistributionType`. Replace the `DistributionMode` import with `DistributionType`. |
| `views/TreeDetailView.scala` | Pass `leaf.distributionType: DistributionType` directly to `TreeNodeRow.leafTooltip` — the `DistributionMode.fromString(...).getOrElse(...)` bridge and the `DistributionMode` import are removed. |

---

### Implementation sequence

Steps 1 and 2 are in `common` and compile independently. Steps 3 and 4 depend on
Step 1. Step 5 (app module) depends on Steps 1 and 3.

**Step 1 — `OpaqueTypes.scala` + `Distribution.scala` (common)**
Add `Expert` / `Lognormal` `DistributionType` constants to `IronConstants`. Remove
`probability` field and parameter from `Distribution`. This step will break
everything that depends on `Distribution.probability` — expected.

**Step 2 — `RiskTreeRequests.scala` (common)**
Update `refineLeafDefs` and `refineExistingLeaves` to carry a refined `Probability`
alongside the `Distribution` in their result tuples. Widen all three resolved maps to
`Map[SafeName.SafeName, (Probability, Distribution)]`. Fix `Distribution.create()`
call sites (drop `l.probability`; refine it separately).

**Step 3 — `RiskTreeMaintenanceRequests.scala` (common)**
Update `DistributionUpdateRequest.validate()` to return `(Probability, Distribution)`;
update `validateDistributionUpdate`'s return type to match.

**Step 4 — `RiskTreeServiceLive.scala` (server)**
Update `buildNodes` parameter type. Update probability read site.

**Step 5 — App module (all files in parallel where possible)**
- `TreeBuilderState.scala` — delete `DistributionDraft`/`LeafDistributionDraft`; `LeafDraft` carries `Distribution` + `Probability`; `loadFromTree` becomes a structure-preserving projection (preserves the `NodeId` per the prerequisite)
- `RiskLeafFormState.scala` — new `draftSignal` derivation emitting `Option[Distribution]`; `DistributionMode` toggle retained
- `DistributionChartState.scala` — type annotation only
- `DistributionChartView.scala` — `toPreviewRequest` parameter type
- `DistributionSpecBuilder.scala` — `IronConstants.Expert` mode check
- `TreePreview.scala` — construction from typed fields, remove `.assume`, `distType: DistributionType`
- `TreeNodeRow.scala` — `distType` parameter `DistributionMode` → `DistributionType`
- `TreeDetailView.scala` — remove `DistributionMode.fromString` bridge
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
