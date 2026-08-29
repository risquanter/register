# Plan: Design View GUI Rework — Load, Edit, and Rendering Unification

**Status:** Awaiting approval  
**Date:** 2026-05-17  
**Tags:** frontend, laminar, scala-js, tree-builder, design-view

---

## Objective

This plan covers four sequential phases of the Design view GUI rework:

- **Phase A** — Fix two silent bugs (load + edit) so the dropdown is functional.
- **Phase B** — Replace `TreePreview`'s ASCII rendering with Laminar DOM rows,
  the prerequisite for code sharing.
- **Phase C** — Extract a shared `TreeNodeRow` component used by both `TreePreview`
  and `TreeDetailView`.
- **Phase D** — CSS beautification: replace ASCII connector characters with CSS
  pseudo-element lines and improve per-node styling.

Phases are sequential. Each requires explicit approval before implementation begins.
Phase A has no rendering dependency — it can be implemented and approved independently.
Phases B–D are purely rendering changes; none of them touch the state layer.

**Vega / ECharts tree visualisation is out of scope for all phases.**

---

## Background: Two Bugs in Phase A

1. **Loading is silently broken.** `TreeListView` fetches the selected tree into
   `treeViewState.selectedTree`, but nothing propagates this to `TreeBuilderState`.
   The builder remains empty; the tree loads invisibly.

2. **Editing a previously created tree is impossible.** `builderState.editingTreeId`
   is never set from a server-loaded tree, so submitting always creates a new tree.

The Analyze view is unaffected because it reads `treeViewState.selectedTree` directly.

---

## Frontend Typing — Known Limitation

The app module uses a two-layer typing model:

- **Form layer:** `Var[String]` for all inputs. No Iron types.
- **Draft layer** (`LeafDistributionDraft`, `PortfolioDraft`, `LeafDraft`): plain Scala
  primitives (`String`, `Double`, `Option[Long]`). No Iron types.
- **Validation boundary:** Iron smart constructors (`Distribution.create`,
  `ValidationUtil`) are called at commit time via the cross-compiled `common` module.
- **Server response layer:** `RiskTree`, `RiskLeaf` etc. are fully Iron-typed.

In `loadFromTree`, values are extracted from Iron-typed `RiskLeaf` fields back to
primitives (e.g. `leaf.distributionType.toString`) to populate `LeafDistributionDraft`.
This is safe — the values were already validated by the server. The reason the draft
layer uses `String` (rather than `DistributionType`) is simply consistency: the entire
draft layer uses plain Scala primitives throughout, regardless of whether the input
comes from free text or a dropdown. Changing the draft layer to use Iron types is out
of scope for this plan.

---

## ADR Compliance Review (Planning Phase)

**Reviewed ADRs:** All files present in `docs/dev/` at time of review.

| ADR | Relevant Constraint | Status |
|-----|---------------------|--------|
| ADR-001 | Validation at domain boundaries; Iron types at boundaries | ✅ No new domain boundary introduced. `loadFromTree` reads already-validated `RiskTree` fields; primitives populate the draft layer per established pattern. Acknowledged typing gap in draft layer is pre-existing and out of scope. |
| ADR-009 | Aggregation identity; compositional results | ✅ Not affected |
| ADR-010 | Error handling via typed sealed hierarchy | ✅ Not affected |
| ADR-011 | Import conventions — top-level imports | ✅ All new code will follow |
| ADR-017 | Separate Create/Update DTOs; `toUpdateRequest()` uses full-replace semantics | ✅ No API shape changes |
| ADR-018 | Nominal case-class wrappers (`NodeId`, `TreeId`) for ID resolution | ✅ Parent resolution uses `tree.index.nodes: Map[NodeId, RiskNode]` for name lookup — see Data Mapping section |
| ADR-019 | Signals down / callbacks up; state above pure views; no mutations in rendering | ✅ `loadFromTree` is a named state mutation called from a lifecycle subscription. Rendering phases (B–D) remain pure derived views. |
| ADR-025 | SPA routing | ✅ Not affected |
| ADR-028 | Query pane | ✅ Not affected |

**Deviations detected:** None.

---

## Data Mapping Reference (Phase A)

### Root portfolio model

`portfoliosVar` holds **all** portfolios, including the root. The root portfolio is
the `PortfolioDraft` with `parent = None` — the `None` parent is the root sentinel.
`treeNameVar` holds the **tree name**, which is a separate field on `RiskTree` from
the root portfolio's name. `rootLabel = "(root)"` is a dropdown placeholder in
`parentOptions` that appears only until a portfolio with `parent = None` exists;
once the root slot is taken, the actual portfolio name is shown instead.

The lone-leaf case (`portfoliosVar = Nil`, one `LeafDraft` with `parent = None`)
is handled naturally: a `RiskLeaf` with `parentId = None` maps to `parent = None`.

### Parent name resolution

Parent resolution is the same for every node type:

```scala
parentId.flatMap(id => tree.index.nodes.get(id).map(_.name))
```

- Root portfolio (`parentId = None`) → `None` → `PortfolioDraft(name, None)` ✓
- Child portfolio under root (`parentId = Some(rootId)`) → `Some(rootName)` ✓
- Leaf under root portfolio (`parentId = Some(rootId)`) → `Some(rootName)` ✓
- Lone leaf (`parentId = None`, no portfolios) → `None` ✓

No special case for `tree.rootId` is needed.

### `RiskTree` → `TreeBuilderState` field mapping

| Source | Target |
|--------|--------|
| `tree.name.value.toString` | `treeNameVar` |
| `tree.id` | `editingTreeId` |
| All `RiskPortfolio` nodes, with resolved parent | `portfoliosVar` (as `PortfolioDraft`) |
| `RiskLeaf` nodes, with resolved parent | `leavesVar` (as `LeafDraft` / `LeafDistributionDraft`) |

`LeafDistributionDraft` fields from `RiskLeaf`:
- `distributionType` ← `leaf.distributionType.toString`
- `probability` ← `leaf.probability.toDouble`
- `minLoss` ← `leaf.minLoss.map(_.toLong)`
- `maxLoss` ← `leaf.maxLoss.map(_.toLong)`
- `percentiles` ← `leaf.percentiles`
- `quantiles` ← `leaf.quantiles`
- `terms` ← `leaf.terms.map(_.toInt)`

---

## Phase A — Fix Load & Edit

### Step A1 — Add `loadFromTree` to `TreeBuilderState`

**File:** `modules/app/src/main/scala/app/state/TreeBuilderState.scala`

Add one public method: `def loadFromTree(tree: RiskTree): Unit`

Logic:
1. Partition `tree.nodes` into `RiskPortfolio` and `RiskLeaf` instances.
2. For each `RiskPortfolio`: resolve parent via
   `portfolio.parentId.flatMap(id => tree.index.nodes.get(id).map(_.name))`;
   build `PortfolioDraft(name, parent)`. The root portfolio has `parentId = None`
   and becomes `PortfolioDraft(name, None)` — included in `portfoliosVar` as normal.
3. For each `RiskLeaf`: same resolution; build `LeafDistributionDraft` from
   distribution fields; build `LeafDraft`.
4. Set all vars in order (Laminar batches synchronous updates):
   - `currentDraftVar.set(None)` — clears any in-flight leaf form preview
   - `treeNameVar.set(tree.name.value.toString)`
   - `portfoliosVar.set(portfolios)`
   - `leavesVar.set(leaves)`
   - `editingTreeId.set(Some(tree.id))`
   - `resetTouched()` — clears stale validation error display state

**Signature:**
```scala
def loadFromTree(tree: RiskTree): Unit
```

**Import required:** `com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio}`
(already in `common`, cross-compiled to Scala.js, available in `app` module).

**ADR-019:** Mutation method on state, called from a subscription callback — not
from inside a signal `.map`. Compliant.

---

### Step A2 — Unit tests for `loadFromTree`

**File:** `modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala`
(check whether this file exists before creating; use the same ZIO Test pattern
as `ChartHoverBridgeSpec` and `TreeIndexSpec`).

Test fixtures use `RiskLeaf.unsafeApply`, `RiskPortfolio.unsafeFromStrings`, and
`TreeIndex.fromNodeSeq` — the same pattern used in `TreeIndexSpec`.

Required test cases:

1. **Lone-leaf tree (lognormal)** — no portfolios in the tree; `portfoliosVar` is
   empty; `leavesVar` has one entry with `parent = None`; `treeNameVar` set;
   `editingTreeId` set.
2. **Lone-leaf tree with terms (expert)** — `terms` field threads correctly;
   `leavesVar` has one entry with `parent = None`.
3. **Root portfolio + leaf under root** — `portfoliosVar` has one entry with
   `parent = None` (the root); `leavesVar` has one entry with
   `parent = Some(rootPortfolioName)`.
4. **Root portfolio + child portfolio + leaf under child** — `portfoliosVar` has two
   entries: root (`parent = None`) and child (`parent = Some(rootName)`); `leavesVar`
   has one entry with `parent = Some(childName)`.
5. **Round-trip: after `loadFromTree`, `toUpdateRequest()` succeeds** — confirms the
   complete path from server model → draft vars → valid update request.

---

### Step A3 — `isDirty` and `DesignView` subscription (implemented together)

**Files:**
- `modules/app/src/main/scala/app/state/TreeBuilderState.scala` — add `isDirty`
- `modules/app/src/main/scala/app/views/DesignView.scala` — add subscription

`isDirty` has no call site without the `DesignView` subscription, so both are
written in the same implementation burst.

**`isDirty` definition:**
```scala
def isDirty: Boolean =
  treeNameVar.now().trim.nonEmpty || portfoliosVar.now().nonEmpty || leavesVar.now().nonEmpty
```

**Subscription logic** — `treeViewState.selectedTree.signal.changes` modifier on
the root `div`, bound to element lifetime:

On each `LoadState.Loaded(tree)` emission:
1. Capture `previousId = builderState.editingTreeId.now()` (before any mutation).
2. If `previousId.contains(tree.id)`: `builderState.loadFromTree(tree)` directly — same
   tree already loaded (covers the `onSuccess` path in `TreeBuilderView` and manual
   re-select). No confirmation needed.
3. Else if `builderState.isDirty`:
   - Call `dom.window.confirm("Loading a saved tree will clear your current draft. Continue?")`
   - If `false`: `treeViewState.selectedTreeId.set(previousId)` — reverts dropdown.
   - If `true`: `builderState.loadFromTree(tree)`.
4. Else: `builderState.loadFromTree(tree)` directly.

**Revert semantics:**
- If builder was in new-tree mode (`editingTreeId == None`), revert sets
  `selectedTreeId` to `None` → dropdown shows "— Select a tree —".
- If builder had a previously loaded tree A (`editingTreeId == Some(treeA.id)`),
  revert sets `selectedTreeId` back to `Some(treeA.id)` → dropdown shows tree A.

**ADR-019:** Subscription bound to `div` lifetime via modifier (same pattern as
`AnalyzeView` and `DistributionChartView`). Side effects occur in callback, not
in a `.map` feeding `child <--`.

**`dom.window` import:** `org.scalajs.dom` is already a dependency; `dom.window.confirm`
returns `Boolean` in the Scala.js DOM facade.

---

### Phase A — Integration verification (manual)

- [ ] Create a tree via the example scripts.
- [ ] Design view dropdown shows the tree.
- [ ] Select it with empty builder → tree loads; `TreePreview` shows it; button reads "Update Risk Tree".
- [ ] Edit a leaf and submit → existing tree updated, not duplicated.
- [ ] Make builder dirty (add a node), then select a different tree → `window.confirm` appears.
- [ ] Cancel → dropdown reverts to previously loaded tree (or blank).
- [ ] Confirm → new tree loads, builder clears and repopulates.

### Phase A — Files touched

| File | Change |
|------|--------|
| `modules/app/src/main/scala/app/state/TreeBuilderState.scala` | Add `loadFromTree`, `isDirty` |
| `modules/app/src/main/scala/app/views/DesignView.scala` | Add `selectedTree.signal.changes` subscription |
| `modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala` | New (or extended) — tests for `loadFromTree` |

---

## Phase B — `TreePreview` → Laminar DOM Rows

**Prerequisite:** Phase A approved and complete.

**Goal:** Replace `TreePreview`'s `white-space: pre` + ASCII rendering with individual
`<div>` rows per node, identical in structure to `TreeDetailView`'s rows. No visual
regression is required; the goal is structural parity to enable Phase C.

### Step B1 — Rewrite `TreePreview` row rendering

**File:** `modules/app/src/main/scala/app/views/TreePreview.scala`

Replace the current `renderTree` / string concatenation approach with a recursive
`renderNode` function that produces `List[HtmlElement]`, one per node. Each row is:

```
div(cls := "tree-node-row tree-node-row--depth-{n}",
  span(cls := "tree-node-indent"),  // width = depth × indent-unit
  iconElement,
  span(cls := "tree-node-label", labelText),
  removeButton
)
```

The ASCII connector characters (`├──`, `└──`, `│`) are replaced with CSS classes
applied to the indent span; the visual connectors are drawn by CSS (Phase D).
As a transitional measure, the indent span may carry a `data-depth` attribute so
Phase D can target it without touching the Scala code again.

**No change to `TreeBuilderState`**. The inputs and outputs of the function are
identical in contract; only the rendering strategy changes.

### Step B2 — CSS structural classes

**File:** `modules/app/styles/app.css`

Add structural CSS classes: `.tree-node-row`, `.tree-node-indent`, `.tree-node-label`.
Initially these can replicate the existing visual appearance (monospace font, same
spacing). Phase D upgrades the styling.

### Step B3 — Tests

`TreePreview` is a pure view reading from `TreeBuilderState` signals. The relevant
test is structural: given a `TreeBuilderState` with known nodes, confirm that
`portfoliosVar` and `leavesVar` produce a matching number of row elements. The
existing `ChartHoverBridgeSpec` pattern (Scala.js ZIO Test) applies.

---

## Phase C — Shared `TreeNodeRow` Component

**Prerequisite:** Phase B approved and complete.

**Goal:** Extract the per-row rendering logic shared between the new `TreePreview`
and the existing `TreeDetailView` into a single `TreeNodeRow` component.

### Step C1 — Define `TreeNodeRow` in `app/components/`

**File:** `modules/app/src/main/scala/app/components/TreeNodeRow.scala`

```scala
object TreeNodeRow:
  enum NodeKind:
    case Portfolio, Leaf

  def apply(
    label:     String,
    kind:      NodeKind,
    depth:     Int,
    onRemove:  Option[() => Unit] = None,
    isSelected: Signal[Boolean]   = Signal.fromValue(false)
  ): HtmlElement
```

`TreePreview` passes `onRemove = Some(...)`. `TreeDetailView` passes `onRemove = None`
(it has its own interaction model). Neither passes `isSelected` initially;
`TreeDetailView` can wire it in Phase C or a later phase.

### Step C2 — Update `TreePreview` to use `TreeNodeRow`

Replace the inline row `div` from Phase B with `TreeNodeRow(...)`.

### Step C3 — Update `TreeDetailView` to use `TreeNodeRow`

Replace its inline row construction with `TreeNodeRow(...)`.

### Step C4 — Tests

Verify `TreeNodeRow` renders the correct structure for each `NodeKind` and depth.

---

## Phase D — CSS Beautification

**Prerequisite:** Phase C approved and complete.

**Goal:** Replace ASCII connector characters with CSS pseudo-element lines and
improve per-node visual styling.

### Step D1 — CSS connector lines

**File:** `modules/app/styles/app.css`

Replace the ASCII `├──`/`└──` connectors with CSS:
- A vertical line on the `.tree-node-indent` element using `border-left` for each
  active indent level.
- A horizontal tick using `::before` on `.tree-node-row`.
- The `data-depth` attribute set in Phase B allows targeting without Scala changes.

### Step D2 — Per-node visual improvements

**File:** `modules/app/styles/app.css`

- Colored left border on `.tree-node-row` encoding distribution type
  (`lognormal` → one color, `expert` → another).
- Hover state (`background-color` on `.tree-node-row:hover`).
- Selection highlight (`background-color` on `.tree-node-row--selected`).

No Scala changes. All driven by existing CSS classes already on the elements.

### Step D3 — Tests

Visual regression is manual. Automated test: confirm that the `data-depth` attribute
is set correctly on rendered rows for each depth level (reuse Phase B/C test
infrastructure).

---

## Summary: Files by Phase

| Phase | Files |
|-------|-------|
| A | `TreeBuilderState.scala`, `DesignView.scala`, `TreeBuilderStateSpec.scala` |
| B | `TreePreview.scala`, `app.css`, `TreePreviewSpec.scala` |
| C | `TreeNodeRow.scala` (new), `TreePreview.scala`, `TreeDetailView.scala`, `TreeNodeRowSpec.scala` |
| D | `app.css` only |

No server-side files are touched in any phase. No new library dependencies.

---

## Approval Checkpoint

- [ ] ADR compliance verified at planning stage — **awaiting user approval**
- [ ] User approves this plan before any implementation begins
