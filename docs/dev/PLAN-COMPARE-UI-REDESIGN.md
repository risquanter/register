# PLAN — Compare UI redesign: implicit comparison via Load Trees rows

Status: CLOSED — landed and committed (`2dba914`, `844994e` "visual rework").
Redesign + continuation "mirror-baseline-selection + styling polish" both
implemented, Opus code-reviewed, Opus security-reviewed (PASS), all green.
Code-review NOTEs ruled by the user 2026-07-26: NOTE 1 (mirror cap bypass) left
as-is by design; NOTE 2 done (`selectionLocked` is `StrictSignal`, local mirror
removed); NOTE 3 done (notice clears when Mirror turns off); NOTE 4 deferred to
the post-0.8.0 backlog (handleNodeClick if/else→match); NOTE 5 done (CSS class
renamed `.compare-select--disabled`); NOTE 6 done (`LECChartStateSpec` covers
`setUserSelection`). All changes are in `modules/app` (client only) plus docs +
version files. No wire/API change, no server change, no dependency change.
Version: the whole compare rework shipped at 0.9.0 (build.sbt + .env +
.env.irmin).

Continuation 2 (Load-Trees grouping + button/toggle styling) implemented
2026-07-26; `app/compile` + `app/test` green. Comparand rows + add button +
layout toggle now render inside the Load Trees box; add button uses the Run
style; baseline eye matches the refresh button; layout toggle is a sliding
two-position control.

Continuation 3 (slot cards + UI-consistency pass) implemented 2026-07-26;
`app/compile` + `app/test` green. Each slot is now one BranchCard (picker in
header, tree in body); BranchCard takes `header`/`body` (chevron-only toggle);
TreeListView gained `bare` and dropped `belowControls`; one "Load Trees" title,
neutral ✎ marker, footer with a compact add button + sliding toggle; unified
32px control height across the panel. Design's boxed TreeListView unchanged
(`bare = false`).

Continuation 4 (Design-view collapse + review fixes) implemented 2026-07-26;
`app/compile` + `app/test` green. Review items applied: #1 `TreeListView.title`
removed; #2 unified `.slot-card*` naming (branch-card→slot-card, compare-row-remove
→slot-card-remove, compare-slot-picker→slot-select-pair, slot-card-head→
slot-card-content, branch-card-swatch→color-swatch); #4a icon-button/refresh
unified (Design + Analyze), icon-size rules merged, form chrome removed (forms
are chrome-less inside FormCard); #5 undefined tokens inlined; stale section
comments deleted. Design forms are now collapsible `FormCard`s driven by
`FormMode` (inactive collapses to a header, active expands; clicking a collapsed
header activates it) — no FormMode/validation/create-update logic changed.
Deferred (source-only, no visual effect): physically relocating the split CSS
blocks into one contiguous section; extracting a shared button/select base
(genuine sizing differences — left to avoid unverifiable regressions). Stray
stale comment `branch-card` in PaletteData.scala:91 (not in inventory) noted for
a later sweep.

Continuation 6 (slot card height model) implemented 2026-07-26; CSS-only,
`app/compile` green. Slot cards no longer `flex:1` (which redistributed panel
height between expanded cards); each card is `flex:0 0 auto` (sizes to its own
content), the in-card tree grows to content up to `--slot-tree-max-h` (~10 rows)
then scrolls, with a drag-to-resize handle; the whole `.slot-card-stack` scrolls
if the cards together exceed the panel. Redundant `.slot-card--collapsed` and
`.form-card` flex overrides removed (base is now `0 0 auto`); Design renders
identically.

Continuation 5 (baseline slot parity + baseline-branch-select retirement)
implemented 2026-07-26; `app/compile` + `app/test` green. Baseline header is now
two rows (row 1 swatch + branch + tree + refresh; row 2 eye alone, right-aligned
under the refresh, rightmost buttons aligned across all slots); baseline starts
collapsed and expands on tree load (same as comparands); every panel select
(incl. baseline branch + panel tree) gets a focus border via panel-scoped
`:focus` rules; `.baseline-branch-select` fully retired — `BranchBar.picker`'s
default class is now `compare-branch-select`; `TreeListView.rowTrailing` removed.

## Goal (end state, user-visible)

The Analyze view drops the explicit "Compare" concept. The right-side panel
(today "Saved Trees") becomes the **Load Trees panel**: an ordered list of
**selector rows**, each pointing at a (branch, tree) pair. Row 0 is the
**baseline** — the tree the VQL query runs against and the reference the ✎
change-markers compare to. The user adds **comparand rows** with an add
button (up to the palette-family cap) and removes them with a per-row "−"
button. Each row has a **tree viewer** card (collapsed while empty, expands
when its tree loads) and an **eye button** that hides that row's curves from
the chart without touching its state. Whether a comparison exists is
implicit: two or more rows with loaded trees and charted nodes IS the
comparison. A binary **layout toggle** (Overlay ⇄ Side-by-side) in the panel
replaces today's three-state Off/Overlay/Side-by-side control; the "slot
bar" (the two compare pickers in the Query panel header) is deleted, leaving
the "Query" heading to label only the VQL input.

## Vocabulary (binding for this plan)

- **Slot** — selector row + tree viewer + charted selection. **Baseline** =
  row 0; **comparand** = every added row.
- **Pool slot** — one of the fixed `ComparedSlotCount` `CompareSlot` bundles
  constructed at startup; a pool slot is live only while its index is in the
  row list. Series labels and default palette families key on the POOL
  index, so a slot keeps its colour and chart identity regardless of row
  position.
- **Row order** — display order; also the "earlier-wins" order for the
  duplicate-pair dedup (baseline earliest).
- **Hidden** — the eye state: the slot's curves/panel are excluded from the
  chart; selector, viewer, selection, diff markers, and all fetch/reset
  machinery stay fully live. Hiding the baseline keeps the query running
  against it; unhiding re-charts whatever the state then is.

## Behaviour rules

1. **Baseline charting unchanged** (ruled 2026-07-26): loading, switching,
   or refreshing the baseline tree charts nothing by itself — exactly as
   today. An earlier draft's root-default rule is removed; whether the
   visual redesign alone makes entry charting feel natural is assessed
   after landing.
2. **Comparand entry seeding — unchanged** (current `computeSeed`, kept
   verbatim): baseline-charted counterparts; empty baseline → baseline root
   selected on the baseline + comparand root seeded; nonempty baseline with
   no counterpart → comparand seeds empty. (User ruling: D3/D4 keep current
   behaviour.)
3. **Query isolation.** Run applies only to the baseline's (branch, tree).
   A query run never resets or adjusts any comparand's charted selection.
   (Already true today — stated as a binding invariant; the redesign must
   not regress it.)
4. **Follow-active kept** (user ruling D2): a comparand's tree select
   retains the "same tree as active" value; the collision/inert/re-engage
   machinery (`SlotCoordinate`, `collidesWith`, `samePairAs`, C4
   earlier-wins dedup, R1 resets, pinned-tree recovery) is retained
   unchanged. Dedup order = row order. Dedup ignores hidden (hide is a
   display filter only: a hidden earlier slot still wins, so its duplicate
   stays inert).
5. **Add/remove.** Add appends a row bound to the lowest free pool slot;
   disabled at the cap. Remove tears the row down completely (target →
   NotChosen, hidden → false; the slot's tree view deselects via the
   existing target-cleared path). The baseline row has no "−" (not
   removable), only the eye.
6. **Viewers.** Every row always has its viewer card: all cards (baseline
   included, per continuation 5) start collapsed to their header and auto-expand
   when their tree loads. The baseline renders as a card in the stack at all
   times (today it is a plain panel until a comparison engages) — deliberate,
   for a uniform column.
7. **Chart.** Overlay: one chart; sides = baseline (unless hidden) + every
   engaged, non-hidden comparand. Side-by-side: one panel per such side on
   shared pinned axes; the grid wraps beyond 3 panels. No visible comparand
   side → the plain single-branch chart exactly as today (regression guard;
   baseline hidden with no comparands → empty/Idle chart).
8. **Cap** = `PaletteData.namedFamilies.size` (8) branches total: baseline
   (Aqua default) + 7 comparand pool slots, each with a distinct default
   family. (User ruling D7: cap = the number of colour palettes; no other
   config exists — the constant is derived from the palette list, so adding
   a 9th family raises the cap automatically.)

## Signatures (exact)

### modules/app/src/main/scala/app/state/CompareState.scala

```scala
/** How multiple loaded trees are displayed. `Overlay` draws every visible
  * side's curves on one chart, coloured by branch family; `SideBySide`
  * tiles one chart per side on shared pinned axes. With no visible
  * comparand side, both render the plain single-tree chart. */
enum CompareLayout:
  case Overlay, SideBySide
// CompareMode (Off, Overlay, SideBySide) is DELETED.

final class CompareSlotState:
  val target: Var[CompareTarget] = Var(CompareTarget.NotChosen)   // unchanged
  /** Eye state: excluded from the chart while true; all other machinery
    * (fetches, diffs, resets, viewer) stays live. */
  val hidden: Var[Boolean] = Var(false)
  val branchSignal: Signal[BranchChoice] = ...                    // unchanged

object CompareState:
  /** Branches on screen at most: one per palette family — the baseline
    * (Aqua default) plus a comparand pool slot per remaining family. */
  val MaxBranches: Int = PaletteData.namedFamilies.size
  val ComparedSlotCount: Int = MaxBranches - 1

  /** The pool slot a new row claims: lowest index not currently in use. */
  def nextFreeSlot(rows: Vector[Int], poolSize: Int): Option[Int] =
    (0 until poolSize).find(i => !rows.contains(i))

final class CompareState:
  val layout: Var[CompareLayout] = Var(CompareLayout.Overlay)
  /** Baseline eye state — mirrors CompareSlotState.hidden for row 0. */
  val baselineHidden: Var[Boolean] = Var(false)
  /** Fixed pool; a pool slot is live only while its index is in `rows`. */
  val slots: Vector[CompareSlotState] =
    Vector.fill(CompareState.ComparedSlotCount)(new CompareSlotState)
  /** Pool indices of the user-added comparand rows, in display order. */
  val rows: Var[Vector[Int]] = Var(Vector.empty)

  /** (poolIdx, target) pairs in row order — the engagement/dedup input. */
  val rowTargets: Signal[Vector[(Int, CompareTarget)]] =
    rows.signal
      .combineWith(Signal.combineSeq(slots.map(_.target.signal)))
      .map { (order, ts) => order.map(i => i -> ts(i)) }

  /** Per-pool-slot hidden flags (pool order). */
  val hiddenFlags: Signal[Vector[Boolean]] =
    Signal.combineSeq(slots.map(_.hidden.signal)).map(_.toVector)

  def addRow(): Unit =
    CompareState.nextFreeSlot(rows.now(), slots.size)
      .foreach(i => rows.update(_ :+ i))

  def removeRow(poolIdx: Int): Unit =
    rows.update(_.filterNot(_ == poolIdx))
    slots(poolIdx).target.set(CompareTarget.NotChosen)
    slots(poolIdx).hidden.set(false)
// DELETED members: mode, comparisonOn, comparisonOnNow, targets.
```

`SlotCoordinate`, `CompareTarget`, `toCoordinate`: unchanged.

### modules/app/src/main/scala/app/Main.scala

```scala
// Baseline default family stays Aqua; each pool slot gets a distinct
// remaining family (Purple/Orange kept first for continuity with 0.8.0):
val compareSlotDefaultPalettes = Vector(
  PaletteData.Purple, PaletteData.Orange, PaletteData.Green,
  PaletteData.Yellow, PaletteData.Red, PaletteData.Pink, PaletteData.Emerald
)
require(
  compareSlotDefaultPalettes.length == CompareState.ComparedSlotCount,
  "one default palette family per compare slot"
)
// CompareSlot construction loop unchanged, now over 7 pool slots.
```

### modules/app/src/main/scala/app/views/TreeListView.scala

```scala
def apply(
  state: TreeViewState,
  onNewTree: Option[() => Unit] = None,
  leadingControl: Option[HtmlElement] = None,
  onRefreshExtra: () => Unit = () => (),
  title: String = "Saved Trees",
  rowTrailing: Option[HtmlElement] = None
): HtmlElement
```

`title` renders in the existing header (`Analyze` passes "Load Trees";
Design keeps the default). `rowTrailing` renders at the end of
`tree-list-controls-row` (Analyze passes the baseline eye button).

### modules/app/src/main/scala/app/components/BranchCard.scala

```scala
def apply(
  swatch:        HtmlElement,
  branchName:    Signal[String],
  body:          HtmlElement,
  initiallyOpen: Boolean = true,
  expandOn:      EventStream[Unit] = EventStream.empty
): HtmlElement
```

The local `open` Var initialises to `initiallyOpen`; `expandOn` events set
it true (manual toggling unchanged). Comparand cards pass
`initiallyOpen = false` and an `expandOn` that fires only when the loaded
tree's identity changes — `selectedTree.signal.changes.collect { case
Loaded(t) => t.id }.distinct.mapToUnit` — so a same-tree reload (baseline
refresh, follow-active re-fetch of the same tree) does not reopen a card the
user manually collapsed; a genuinely new tree still auto-expands it.

### modules/app/src/main/scala/app/components/Icons.scala

```scala
def eye(cls: String = "eye-icon"): SvgElement
def eyeOff(cls: String = "eye-icon"): SvgElement
```

### modules/app/src/main/scala/app/views/AnalyzeView.scala

```scala
// seedCompareCard: signature unchanged; body changes only by dropping the
// comparisonOnNow read (see the comparisonOn-removal bullet below).

// renderBranchPicker loses disabledSignal (rows exist only when added, so
// the pickers are never disabled-gated):
private def renderBranchPicker(
  scenarioState: ScenarioState,
  slot: CompareSlot,
  otherSlots: Vector[CompareSlotState],
  activeTreeId: Signal[Option[TreeId]]
): HtmlElement

// NEW private defs:
/** One comparand selector row: branch+tree picker, eye toggle, remove. */
private def renderCompareRow(
  scenarioState: ScenarioState,
  slot: CompareSlot,
  poolIdx: Int,
  compareState: CompareState,
  otherSlots: Vector[CompareSlotState],
  activeTreeId: Signal[Option[TreeId]]
): HtmlElement

/** Eye toggle bound to a hidden flag (baseline or a slot's). */
private def renderEyeToggle(hidden: Var[Boolean]): HtmlElement

/** Binary Overlay ⇄ Side-by-side control. */
private def renderLayoutToggle(compareState: CompareState): HtmlElement

// engagedSlots, computeSeed, excludedBranchValues, excludedTreeOverrideValues:
// signatures and bodies UNCHANGED. Call sites now pass row-ordered targets
// (from compareState.rowTargets) and translate returned positions back to
// pool indices; series labels stay s"s${poolIdx + 1}".
```

Body-level changes (specified, exact code at implementation):

- **Seeding blocks unchanged:** both the baseline-Loaded comparand re-seed
  pass and the per-slot own-tree-Loaded seeding stay exactly as today.
- **comparisonOn removal:** the effective-tree subscription, diff gate, ✎
  gating, and `seedCompareCard`'s `comparisonOnNow` check drop the
  comparison-on input entirely (a pool slot outside `rows` holds
  `NotChosen`, which already no-ops every one of them). The
  toggle-off-preserves-state arm dies with `Off`.
- **Chart signals:** `combinedSpecSignal` keys on `layout` instead of
  `mode`; both it and `sideBySideSpecs` add `rowTargets` + `hiddenFlags` +
  `baselineHidden` inputs; visible sides per behaviour rule 7. The chart
  area and card-stack `(mode/on, engaged)` rebuild keys are replaced: the
  viewer stack becomes `children <-- rows.signal.split(identity)(...)` with
  a stable card per row (baseline card always first); the chart area keys on
  `(layout, visible-side pool indices)`, `.distinct`.
- **Panel composition:** `savedTreePanel` = `TreeListView(..., title =
  "Load Trees", rowTrailing = Some(renderEyeToggle(compareState.baselineHidden)))`
  + `children <-- rows.split` of `renderCompareRow` + an add-row button
  (disabled at cap) + `renderLayoutToggle` + the viewer stack.
- **Query header:** the two picker mounts and `renderModeControl` are
  deleted; `renderModeControl` itself is deleted.

### modules/app/styles/app.css

New/changed classes: `.compare-row` (selector row layout), `.compare-row-remove`,
`.eye-toggle` (+ hidden state), `.compare-add-btn`, `.compare-layout-toggle`
(binary), `.lec-panel-grid` gains wrapping for up to 8 panels
(`flex-wrap`/grid auto-fit). Three-state slider styles for the old mode
control are removed.

### docs/dev/TODO.md

New item: **Phase F — multi-tree queries**: plan how the VQL query pane
extends beyond the baseline (per-row query targeting or cross-tree
predicates). Recorded per user instruction 2026-07-26; no design yet.

## ADR alignment

- **ADR-019**: compliant. `rows`/`hidden`/`layout` are parent-owned Vars on
  `CompareState`; cards/rows receive signals and emit callbacks;
  `BranchCard` keeps its documented local open flag (existing sanctioned
  exception), extended with an init value + expand events, not external
  state. The Pattern 6 correction subscriptions are untouched. No `.now()`
  added to any rendering pipeline (`addRow`/`removeRow`/root-default read
  state inside event handlers, matching the existing precedents).
- **ADR-018/ADR-001**: no new IDs, no raw-primitive parameters
  (`poolIdx: Int` is a vector index, not a domain value — same as the
  existing `engagedSlots` index usage).
- **No Tapir/wire change** — Decision Triggers 1/3/7 not touched; the
  public-signature changes (TreeListView, BranchCard, CompareState) are
  covered by this plan (G3).
- **PLAN-C-REFACTOR / PLAN-C-TASKB-FIXES**: the retained machinery
  (coordinates, dedup, resets, recovery, seeding) is explicitly NOT
  redesigned; this plan supersedes their UI-surface descriptions (slot bar,
  three-state control, engagement-gated card stack).

## Open decisions

None — all ruled by the user 2026-07-26:

- **Cap semantics — RULED: 8 branches total** (baseline + 7 comparands),
  every branch owning a distinct default family; the constant derives from
  `PaletteData.namedFamilies.size`.
- **Hide scope — RULED: chart only** — the eye hides a row's chart
  contribution; the viewer card (with ✎ markers) stays visible, and the
  card's own collapse chevron remains available for shrinking it.
- **Baseline root-default — RULED OUT**: existing charting behaviour kept
  (behaviour rule 1); re-assess after landing whether the visual redesign
  alone makes entry charting feel natural.
- Also fixed by earlier rulings: new rows start unchosen ("— compare
  against —", today's picker default); the baseline renders as a card
  always (behaviour rule 6).

## File inventory

- modules/app/src/main/scala/app/state/CompareState.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/components/BranchCard.scala
- modules/app/src/main/scala/app/components/Icons.scala
- modules/app/styles/app.css
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/AnalyzeQueryState.scala
- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/test/scala/app/state/CompareStateSpec.scala
- modules/app/src/test/scala/app/state/LECChartStateSpec.scala
- modules/app/src/test/scala/app/views/AnalyzeViewSeedSpec.scala
- modules/app/src/test/scala/app/state/ScenarioDiffStateSpec.scala
- docs/dev/TODO.md
- build.sbt

Not touched (prose, not inventory): BranchBar.scala, FormInputs.scala,
CompareColorAssigner.scala, TreeViewState.scala, ScenarioDiffState.scala,
BranchPaletteState.scala, PaletteData.scala, DesignView.scala,
server/common modules.

## Verification plan

Tests:

- `CompareStateSpec`: `nextFreeSlot` — empty rows → 0; gap fill
  (rows=[0,2] → 1); full pool → None. `removeRow` resets the slot's target
  and hidden flag and drops only that row. `MaxBranches ==
  PaletteData.namedFamilies.size`.
- `AnalyzeViewSeedSpec`: existing `engagedSlots`/`computeSeed`/exclusion
  tests unchanged and must stay green (their pure signatures don't change).

Commands (pass/fail only):

```
sbt app/compile
sbt app/test
```

Manual (nginx + Irmin stack, `./examples/stage-compare-slots.sh` data):

- Fresh load: Load Trees panel with one row; picking Tree A fills the
  viewer, nothing charted (unchanged behaviour); viewer card expanded.
- Add row → collapsed empty card; point at (alpha, Tree A) → card expands,
  ✎ on Leaf One + Root; entry seeding as today (empty baseline → baseline
  root selected + comparand root seeded; charted baseline → counterparts);
  overlay/side-by-side per toggle; remove row → card and curves gone.
- Eye on comparand: curves/panel vanish, card + ✎ stay; eye off → curves
  return unchanged. Eye on baseline: baseline curves vanish; Run a query
  while hidden → no visible change; unhide → query hits now charted.
- Query isolation: with a comparand charted, Run → only the baseline's
  charted set changes.
- Add rows to the cap: add button disables at 7 comparands; each row's
  default swatch is a distinct family.
- Retained machinery spot-checks: duplicate-pair values greyed in pickers;
  same-branch cross-tree comparison reachable; deleting a scenario or a
  pinned tree clears the pointing row's selection (row stays); follow-active
  collision still disengages reversibly on tree browsing.
- Regression: with zero comparand rows the chart and query behave exactly
  as the single-tree Analyze view.

## Versioning

PATCH 0.8.0 → 0.8.1 on landing (user ruling 2026-07-26: the whole compare
rework — redesign + polish + mirror — is a patch of the 0.8.0 compare
feature, not a new minor). `build.sbt` + `APP_VERSION` in `.env` AND
`.env.irmin`, all set to 0.8.1 (also resolves the transient 0.9.0 divergence).

---

# Continuation: mirror-baseline-selection + styling polish (2026-07-26)

Same UI workstream, same plan. From live testing of the redesign. Rulings
2026-07-26: Mirror syncs the baseline's full charted set (query ∪ user —
decision 1B); selection-locked feedback = persistent header hint + transient
bubble (decision 2B); version stays the 0.8.1 patch above. No open decisions.

## Goal

Two additions to the Load-Trees rows: (1) styling fixes so the redesigned
components match the rest of the app; (2) a per-row **Mirror toggle** (beside
the eye) that makes a row's selection continuously track the baseline's, locks
manual selection on that row, and shows a hint + a notice on a blocked
gesture.

## Vocabulary additions

- **Mirror toggle** — per-row control beside the eye (`CompareSlotState.mirror`).
- **Counterparts** — baseline charted node ids that also exist in the row's
  tree; a cross-tree row shares none, so a mirrored cross-tree row charts
  nothing.
- **Selection-locked notice** — transient bubble on a blocked Ctrl-gesture,
  paired with a persistent header hint.

## Behaviour rules

1. **Mirror sync (one-way, continuous, full set — 1B):** while a row's Mirror
   is on, its user selection = `baselineVisible ∩ rowTreeNodeIds`, where
   `baselineVisible` is the baseline `chartState.visibleCurves` (query hits ∪
   manual). Re-syncs on baseline-visible change or row-tree change; immediate
   on toggle-on. A row's query set is always empty, so it charts exactly the
   counterparts; the existing per-row curve-fetch subscription follows.
2. **Cross-tree:** counterparts only; no root fallback while mirroring.
3. **Freeze on off:** the current selection stays; syncing stops; manual
   gestures resume.
4. **Manual lock while on:** Ctrl+Click / Ctrl+Shift+Click on the row are
   disabled and show the selection-locked notice; plain click (navigate)
   still works; the tree-viewer header shows a persistent hint.
5. **Supersedes entry seeding** for a row while on; Mirror off restores
   today's entry seeding.
6. **removeRow** resets `mirror` alongside `target`/`hidden`.
7. **Styling:** S1 baseline eye matches the comparand eye (size + inline with
   refresh); S2 comparand tree `<select>` reuses the baseline `.tree-select`
   styling + legible placeholder; S3 styling pass over eye/mirror toggles,
   layout toggle, add/remove, compare-row, hint, and bubble.

## Signatures (exact)

```scala
// CompareState.scala — CompareSlotState gains mirror; removeRow resets it.
val mirror: Var[Boolean] = Var(false)
def removeRow(poolIdx: Int): Unit =
  rows.update(_.filterNot(_ == poolIdx))
  slots(poolIdx).target.set(CompareTarget.NotChosen)
  slots(poolIdx).hidden.set(false)
  slots(poolIdx).mirror.set(false)

// LECChartState.scala — one-write selection replace (mirrors reset()'s
// direct-write precedent); no-op when unchanged.
def setUserSelection(ids: Set[NodeId]): Unit =
  if userSelectedNodeIds.now() != ids then userSelectedNodeIds.set(ids)

// TreeDetailView.scala — apply gains a lock signal; Ctrl-gestures gate on it.
// StrictSignal (not Signal): both call sites already pass strict signals
// (the comparand card's `slot.state.mirror.signal`, and the `Val(false)`
// default), so the handler samples `.now()` directly — no local mirror Var.
def apply(
  state: TreeViewState,
  queryMatchedNodes: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
  hoverBridge: ChartHoverBridge = new ChartHoverBridge(),
  changedNodeIds: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
  selectionLocked: StrictSignal[Boolean] = Val(false)
): HtmlElement
// body: local lockedNoticeVisible Var pulsed on a blocked gesture (auto-hide
// via EventStream.fromValue(false).delay + flatMapSwitch) → `.selection-locked-notice`;
// a persistent `.selection-lock-hint` shown <-- selectionLocked; handleNodeClick
// blocks the Ctrl-paths when selectionLocked.now() and pulses the notice instead
// of toggling. Turning Mirror off (selectionLocked → false) clears any pending
// notice at once. selectionLocked.now() is sampled only in the event handler.

// Icons.scala
def mirror(cls: String = "mirror-icon"): SvgElement

// AnalyzeView.scala
private def renderMirrorToggle(mirror: Var[Boolean]): HtmlElement
// renderCompareRow: picker, renderMirrorToggle(slot.state.mirror),
//   renderEyeToggle(slot.state.hidden), remove button.
// Per-slot mirror subscription:
compareSlots.map { slot =>
  slot.state.mirror.signal
    .combineWith(treeViewState.chartState.visibleCurves, slot.treeViewState.selectedTree.signal)
    .changes --> {
      case (true, baselineVisible, LoadState.Loaded(tree)) =>
        slot.treeViewState.chartState.setUserSelection(
          baselineVisible.intersect(tree.nodes.map(_.id).toSet)
        )
      case (true, _, _) => slot.treeViewState.chartState.setUserSelection(Set.empty)
      case (false, _, _) => ()
    }
}
// Comparand card body passes selectionLocked = slot.state.mirror.signal to its
// TreeDetailView. Baseline card is never locked and has no mirror toggle.
```

## Styling (app.css)

- **S1** `.eye-toggle` sizing/alignment identical in the baseline row
  (`rowTrailing`) and a compare row.
- **S2** comparand tree `<select>` uses the baseline `.tree-select` rules
  (background, padding, text color/alignment, disabled-placeholder legibility).
- **S3** new: `.mirror-toggle`(+`--on`)/`.mirror-icon`, `.selection-lock-hint`,
  `.selection-locked-notice` (app-token bubble), plus a spacing/typography
  pass on the redesign's controls.

## ADR alignment (continuation)

ADR-019 compliant: `mirror` is a parent-owned Var; the sync is a Pattern-6
subscription writing the row's own selection via a named method
(`setUserSelection`, precedent = `reset()`); `lockedNoticeVisible` is
card-local view state (sanctioned like `pickerOpenFor`); `selectionLocked.now()`
is sampled only in an event handler. No wire/endpoint/DTO/auth change.

## Verification (continuation)

- `CompareStateSpec`: `CompareSlotState.mirror` defaults false; `removeRow`
  resets it.
- `LECChartStateSpec`: `setUserSelection` replaces the whole set and skips the
  write (no emission) when the set is unchanged.
- Manual (staging script): S1 eye alignment; S2 comparand select matches
  baseline + legible placeholder; Mirror on same-tree row tracks the baseline
  live and locks manual selection (notice + hint); Mirror on cross-tree row
  charts nothing; Mirror off freezes and re-enables manual; mirror+eye
  interaction; regression with all mirrors off.
- `sbt app/compile` (zero new warnings), `sbt app/test`.

---

# Continuation 2: Load-Trees grouping + button/toggle styling (2026-07-26)

Same UI workstream, same plan. From live testing of the mirror slice. No open
decisions. Version stays the 0.8.1 patch.

## Goal

Appearance/layout fixes to the Load Trees panel:

1. **Add-tree button** ("+ Compare tree") drops its dashed border and adopts the
   Run button's style (`.query-run-btn`: solid border, `--surface-overlay`
   background, `--sp-2 --sp-4` padding).
2. **Comparand rows + add-tree button + layout toggle all move inside the "Load
   Trees" box** — they render within the bordered `.tree-list-view` container,
   under the baseline selector, in order: comparand rows → add button → layout
   toggle. Only the card stack stays a sibling below the box.
3. **Baseline (row 0) eye** matches the refresh button beside it: same box
   (`.refresh-btn` model — `--surface-overlay` bg, 1px border, `--sp-2 --sp-3`
   padding, radius) and same bottom alignment on the controls row.
4. **Layout toggle → sliding two-position toggle with both labels visible.** A
   pill-shaped track holds both labels ("Overlay", "Side by side"); a sliding
   thumb sits under the active one and animates between the two halves; the
   active label reads in the accent/foreground colour, the inactive muted.
   Replaces the current two-segment button control. Full width of the box,
   aligned with the add button / rows above it.

## Signatures (exact)

```scala
// TreeListView.scala — new optional content slot, rendered INSIDE the
// `.tree-list-view` box, directly after `.tree-list-controls-row`. Additive;
// Design's call site (which omits it) is unaffected.
def apply(
  state: TreeViewState,
  onNewTree: Option[() => Unit] = None,
  leadingControl: Option[HtmlElement] = None,
  onRefreshExtra: () => Unit = () => (),
  title: String = "Saved Trees",
  rowTrailing: Option[HtmlElement] = None,
  belowControls: Option[HtmlElement] = None
): HtmlElement
// body: render `belowControls.getOrElse(emptyNode)` as the last child of the
// `.tree-list-view` div, after the controls row.

// AnalyzeView.scala — the `.compare-row-list`, the add-row button, AND
// renderLayoutToggle(compareState) move from savedTreePanel's top level into
// TreeListView(..., belowControls = Some(div(cls := "load-trees-compares",
//   compareRowList, addButton, renderLayoutToggle(compareState)))).
// Only the branch-card-stack stays a sibling below TreeListView. No signature
// change to any AnalyzeView def.

// AnalyzeView.renderLayoutToggle — private; body becomes the sliding toggle:
//   div.compare-layout-toggle(
//     span.compare-layout-thumb( styleAttr <-- layout.signal.map(overlay ? 0% : 50%) ),
//     the two `.compare-layout-option` buttons (transparent, over the thumb,
//       `--active` toggled by layout.signal) )
// Same signature: renderLayoutToggle(compareState: CompareState): HtmlElement.
```

## Styling (app.css)

One control system for the whole Load Trees panel — `.saved-tree-panel` defines
`--lt-control-h: 32px` (fallback `32px`) and every control in the panel shares
it, so selects, refresh, baseline eye, and each comparand row's mirror/eye/remove
line up on one baseline:

- **Selects** — `.compare-branch-select`/`.compare-tree-select` and (scoped to
  `.saved-tree-panel`, so Design is untouched) `.tree-select`/`.baseline-branch-select`
  all set `height: var(--lt-control-h)`, `box-sizing: border-box`,
  `padding: 0 --sp-2`; existing flex/width from their base rules is kept.
- **Icon buttons** — one rule covers `.eye-toggle`, `.mirror-toggle`,
  `.compare-row-remove`, and `.saved-tree-panel .refresh-btn`: identical
  bordered squares `--lt-control-h × --lt-control-h`, `--surface-overlay` bg,
  1px `--border`, `--radius-md`, centred icon, hover `--surface-overlay-hover`.
  `.eye-toggle--hidden` (opacity) and `.mirror-toggle--on` (accent) layer on top.
  The baseline eye is the same square, dropped to the select line via
  `.tree-list-controls-row .eye-toggle { align-self: flex-end }`.
- `.compare-add-btn` — the `.query-run-btn` box (solid 1px `--border`,
  `--surface-overlay` bg, `--radius-md`, `--sp-2 --sp-4` padding,
  `--font-sans`/`--text-sm`/medium weight); keeps `align-self: flex-start` and
  the disabled state. Dashed border removed.
- `.load-trees-compares` — column of rows + add button + layout toggle,
  `gap: --sp-2`, `margin-top: --sp-3` inside the box.
- `.compare-layout-toggle` — sliding toggle sized to content (`width: fit-content`,
  `height: var(--lt-control-h)`): two fixed-width `.compare-layout-option`s
  (108px each, transparent, `z-index` above the thumb, muted text; `--active` →
  `--foreground`) and a `.compare-layout-thumb` (absolute, 50% width,
  `--surface-overlay`, `transform` transition translating 0 ↔ 100% — 50% lands
  exactly under each equal-width option).

## ADR alignment (continuation 2)

ADR-019 compliant: `belowControls` is passed-in content owned by the parent
(`AnalyzeView`); `TreeListView` stays a pure view (Pattern 1/4), adds no Var,
adds no `.now()`. The sliding toggle keeps reading `compareState.layout` via a
signal (no `.now()` in the render pipeline). Additive optional param — no
call-site break (Design omits it). No wire/endpoint/DTO/auth change.

## File inventory (continuation 2)

- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/styles/app.css

## Verification (continuation 2)

- `sbt app/compile` (zero new warnings), `sbt app/test` (regression only — no
  new spec; this is view markup + CSS).
- Manual (staging script): add button reads like Run (solid, no dashed border);
  comparand rows + add button + layout toggle sit inside the bordered Load Trees
  box under the baseline selector; the layout toggle is a sliding two-position
  control with both labels visible and the thumb animating between them; the
  baseline eye matches the refresh button's size and sits on the same line.

---

# Continuation 3: slot cards (picker + viewer unified) + UI-consistency pass (2026-07-26)

Same UI workstream, same plan. From a systematic UI audit of the Load Trees
area (user ruling: option **B** — each slot is one self-contained card — plus
the P2/P3 consistency cleanups). No open decisions. Version stays 0.8.1.

## Goal

Each slot becomes ONE card: its (branch, tree) picker in the card header, its
tree viewer in the body — so a slot's selector and its viewer are adjacent
(Gestalt proximity), and the baseline and comparand slots read as one uniform
column. The separate "Load Trees box of selectors" + "card stack of viewers"
split is dissolved. Plus consistency fixes: one title treatment, one spacing
rhythm, neutral ✎ marker colour, header controls that fit the 25% panel.

## Structure (end state)

```
.saved-tree-panel
  h3.load-trees-title "Load Trees"              (one panel title)
  .slot-card-stack
    BranchCard[baseline]   header: swatch + branch <select> + tree <select> + refresh + eye
                           body:   baseline TreeDetailView
    BranchCard[comparand]×rows  header: swatch + branch <select> + tree <select> + mirror + eye + remove
                           body:   slot TreeDetailView (✎ markers, mirror lock)
  .load-trees-footer  + Compare tree button  |  sliding layout toggle
```

The card header is two lines when the panel is narrow: line 1 = chevron +
swatch + the two selects (grow); line 2 = the action buttons, right-aligned —
so nothing overflows at the 25% split width. Chevron is the only collapse
affordance (header selects/buttons stay clickable).

## Signatures (exact)

```scala
// BranchCard.scala — header becomes arbitrary content (the picker row); the
// chevron is the sole collapse toggle so header controls remain interactive.
def apply(
  header:        HtmlElement,
  body:          HtmlElement,
  initiallyOpen: Boolean = true,
  expandOn:      EventStream[Unit] = EventStream.empty
): HtmlElement
// (was: swatch, branchName, body, initiallyOpen, expandOn)

// TreeListView.scala — `belowControls` removed (unused after B); new `bare`.
// bare = true renders only the controls-row content (leadingControl + tree
// selector + refresh + rowTrailing) with NO `.tree-list-view` box, NO
// header/title, and NO slot labels — for use inside the baseline slot-card
// header. bare = false is unchanged (Design's boxed list).
def apply(
  state: TreeViewState,
  onNewTree: Option[() => Unit] = None,
  leadingControl: Option[HtmlElement] = None,
  onRefreshExtra: () => Unit = () => (),
  title: String = "Saved Trees",
  rowTrailing: Option[HtmlElement] = None,
  bare: Boolean = false
): HtmlElement

// AnalyzeView.scala — new private header builders; `renderCompareRow` removed
// (superseded by renderComparandHead); `loadTreesCompares` removed.
private def renderBaselineHead(
  treeViewState: TreeViewState,
  scenarioState: ScenarioState,
  appConfigState: AppConfigState,
  compareState: CompareState,
  branchPaletteState: BranchPaletteState,
  activePalette: Signal[Vector[HexColor]],
  onRefreshExtra: () => Unit
): HtmlElement
// = div.slot-card-head( BranchPalettePicker(swatch),
//     TreeListView(treeViewState, leadingControl = Some(BranchBar.picker(...)),
//       onRefreshExtra = onRefreshExtra, rowTrailing = Some(renderEyeToggle(baselineHidden)),
//       bare = true) )

private def renderComparandHead(
  scenarioState: ScenarioState,
  slot: CompareSlot,
  poolIdx: Int,
  compareState: CompareState,
  otherSlots: Vector[CompareSlotState],
  activeTreeId: Signal[Option[TreeId]],
  branchPaletteState: BranchPaletteState
): HtmlElement
// = div.slot-card-head( BranchPalettePicker(swatch, slot.state.branchSignal, slot.palette),
//     renderBranchPicker(scenarioState, slot, otherSlots, activeTreeId),
//     renderMirrorToggle(slot.state.mirror), renderEyeToggle(slot.state.hidden),
//     remove button )

// savedTreePanel body:
//   h3.load-trees-title "Load Trees"
//   div.slot-card-stack(
//     BranchCard(header = renderBaselineHead(...), body = baseline TreeDetailView),
//     children <-- rows.split { poolIdx =>
//       BranchCard(header = renderComparandHead(...), body = slot TreeDetailView,
//         initiallyOpen = false, expandOn = <tree-loaded, distinct by id>) })
//   div.load-trees-footer( add-tree button, renderLayoutToggle(compareState) )
```

`renderLayoutToggle`, `renderEyeToggle`, `renderMirrorToggle`, `renderBranchPicker`
signatures unchanged.

## Styling (app.css) — P1 structural + P2/P3 consistency

- `.slot-card-stack` — replaces `.branch-card-stack` usage; `gap: --sp-3`, flex column.
- `.slot-card-head` — column, `gap: --sp-2`; row 1 (swatch + selects) flex with
  selects `flex:1`; row 2 (actions) flex, `justify-content: flex-end`. All
  controls keep `--lt-control-h`. Actions wrap to line 2 so a comparand header
  (2 selects + 3 buttons) never overflows the 25% panel.
- `.branch-card-header` — holds the chevron toggle button + `.slot-card-head`;
  chevron via `.branch-card-chevron-btn` (only this toggles collapse).
- `.load-trees-title` — the one panel title: `--text-base`, medium, mono, matching
  the left panel's "Query" heading weight; the per-card branch NAME span is gone
  (the branch select shows it), and the in-card tree header stays `--text-sm`
  (P2 title ramp: panel title > tree name, two levels not three).
- `.load-trees-footer` — add button + layout toggle, `gap: --sp-3`, `margin-top: --sp-3`.
  The "+ Compare tree" button is sized to the panel's other controls — height
  `--lt-control-h`, not visually dominant (user: it currently "stands out");
  the layout toggle stays content-width beside it. If the button still reads
  heavy next to the toggle, both drop to the same compact height so the footer
  reads as one control row, not a banner.
- Rhythm (P2): group gaps standardise on `--sp-3`, within-group on `--sp-2`.
- `.node-changed-marker` (P1 colour) — `--curve-palette-purple` → `--foreground-variant`
  (neutral): the ✎ no longer implies one specific comparand family.
- Remove now-dead rules: `.load-trees-compares`, `.compare-row`/`.compare-row-list`
  (comparand rows are gone — headers replace them), and the `.tree-list-controls-row .eye-toggle`
  box override folds into the shared icon-button rule.

## ADR alignment (continuation 3)

ADR-019 compliant: `BranchCard` still owns only its local `open` flag; all header
content is parent-owned and passed in (Pattern 4). `TreeListView` stays a pure
view; `bare` only changes chrome, adds no state. No `.now()` added to any render
pipeline. Refactor of view composition only — no wire/endpoint/DTO/auth change,
no domain type change. BranchCard/TreeListView public-signature changes are
covered here (G3).

## File inventory (continuation 3)

- modules/app/src/main/scala/app/components/BranchCard.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/styles/app.css

## Verification (continuation 3)

- `sbt app/compile` (zero new warnings), `sbt app/test` (regression only — view
  composition + CSS; no pure-logic change, so no new spec).
- Manual (staging script): each slot is one card — pick branch/tree in its
  header, see its tree in its body; baseline and comparand cards read as a
  uniform column; headers fit the 25% panel (actions wrap to a second line, no
  overflow); one panel title; ✎ marker is neutral, not purple; add button +
  layout toggle sit in the footer; Design's tree list (boxed TreeListView)
  unchanged.

---

# Continuation 4: Design-view focus (collapse the inactive form) + shared-CSS consolidation

Same workstream, same plan (folded in — no separate plan file). User ruling
2026-07-26: option **A** from the Design-view UI audit, plus the review's full
CSS consolidation (item 4a) now that Design-shared CSS is in scope.

## Goal

Design left panel: the Portfolio and Leaf forms become collapsible cards. Exactly
one is expanded — the one you're working on (`FormMode` active); the other
collapses to a header. Clicking a collapsed header activates that form via the
existing transition. NO domain-logic change: `FormMode` + every `disabled` flag
stay as-is; collapse is layered on top.

## Behaviour rules

1. Expanded ⇔ active: a form is expanded when its own mode view is active
   (`Blank`/`Drafting(self)`/`Locked`/`Editing`/`Templating` of its own type),
   collapsed when `Inactive`/`Drafting(other)`. Derived from the existing
   `leafMode`/`portfolioMode` signals.
2. When both forms are `Blank` (nothing selected) both stay expanded (ruling: A).
3. Clicking a collapsed header runs that form's existing `Inactive` activation
   (→ `Drafting(kind)`, via the existing `proceedOrConfirm` guard) — no new
   transition; the header reuses the Add-button handler.
4. The active card is not user-collapsible (chevron inert/hidden); it collapses
   automatically when the other activates.
5. Header shows the form title (existing `h2` text logic) + the target node name
   when `Locked`/`Editing`/`Templating`.
6. Fields stay disabled underneath (defence-in-depth unchanged).
7. Visual parity with the Analyze slot cards (reuse the card CSS system).

## Signatures (exact)

`RiskLeafFormView.apply` / `PortfolioFormView.apply` / `TreeBuilderView.apply`
signatures UNCHANGED — internal composition only. One new component:

```scala
// app/components/FormCard.scala
object FormCard:
  def apply(
    header:           HtmlElement,
    body:             HtmlElement,
    expanded:         Signal[Boolean],
    onHeaderActivate: () => Unit
  ): HtmlElement
// body always mounted (fields keep disabled state); `display` follows `expanded`.
// Header click routes to onHeaderActivate only while collapsed (expanded.now()
// == false, sampled in the handler); expanded header click is inert.

// In each FormView, derived from the existing mode signal:
val expanded: Signal[Boolean] = leafMode.map {   // (portfolioMode in the other)
  case FormMode.Inactive | FormMode.Drafting(_) => false
  case _                                         => true
}
// onHeaderActivate = the existing Inactive→Drafting activation extracted from
// onAddSubmitClick (the proceedOrConfirm { activeForm.set(Drafting(kind)) } arm),
// shared verbatim by the header and the Add button.
```

## Styling / CSS consolidation (app.css) — review item 4a (full)

- `FormCard` reuses the slot-card visual system (`.slot-card`, header bar,
  chevron) for cross-page parity — extract shared card rules if it removes
  duplication.
- **4a full box-model consolidation (Design-shared included):** one `.icon-btn`
  base for the icon squares (`.eye-toggle`/`.mirror-toggle`/`.slot-card-remove`/
  `.refresh-btn`/`.retry-btn`); one button base shared by `.compare-add-btn` and
  `.query-run-btn`; one select box-model for the panel selects and base
  `.tree-select`; group the three identical `16px` icon-size rules. Touches
  Design-shared `.refresh-btn`/`.retry-btn`/`.tree-select`/`.query-run-btn` —
  reviewed together with the Design collapse here.
- Review item 3: relocate the split Load-Trees/slot-card rules into one
  contiguous section; delete stale `/* Compare mode */` + old branch-card comments.
- Review item 5: inline the undefined `--foreground-subtle`/`--shadow-md` token
  references (use literals).

## ADR alignment (continuation 4)

ADR-019: `FormCard` owns no domain state; `expanded` is a passed-in signal from
parent-owned `activeForm`; `onHeaderActivate` is a callback up; `expanded.now()`
sampled only in an event handler. No `FormMode` case, `disabled`, validation, or
create/update logic changed — the explicit "logic unchanged" check. No wire/DTO/
domain change.

## File inventory (continuation 4)

- modules/app/src/main/scala/app/components/FormCard.scala
- modules/app/src/main/scala/app/views/RiskLeafFormView.scala
- modules/app/src/main/scala/app/views/PortfolioFormView.scala
- modules/app/src/main/scala/app/views/TreeBuilderView.scala
- modules/app/styles/app.css

## Verification (continuation 4)

- `sbt app/compile` (zero new warnings), `sbt app/test` — existing FormMode/form
  specs stay green (logic unchanged).
- Manual: selecting a leaf expands the Leaf card, collapses the Portfolio card to
  a header; clicking a collapsed header activates it (with the unsaved-draft
  confirm); saved node name shows in the header; disabled fields stay
  non-editable; create/update/validation unchanged; Design + Analyze cards look
  consistent.

---

# Continuation 5: baseline slot parity + baseline-branch-select retirement (2026-07-26)

Same workstream, same plan. From live review of the slot cards.

## Goal

Slot 0 (baseline) matches the comparand slots exactly:
1. **Two-row header.** Row 1: swatch + branch ▾ + tree ▾ + refresh (unchanged —
   refresh stays with the dropdowns). Row 2: the eye alone, right-aligned, so it
   stacks under the refresh. All slots' action rows are right-aligned to the same
   edge, so every slot's rightmost button lines up vertically.
2. **Focus border on every panel select**, including the baseline branch select
   and the baseline (panel) tree select — the panel-scoped box rule out-specifies
   a plain `:focus`, so the focus rules must be panel-scoped too.
3. **Baseline starts collapsed** (header only, not stretched), expands to host
   the tree on load — identical to a comparand card. Baseline is always present,
   never removable; comparands stack below as collapsed-header entries.

## Cleanup (user-authorised this round)

Retire the vestigial `.baseline-branch-select` class: it predates the unified
panel-select rule and now only diverged by lacking a focus border. `BranchBar.picker`
(its sole emitter, used only by the baseline) switches its default `domCls` to
`compare-branch-select`, so the baseline branch select shares the comparand
select's styling and focus. All `.baseline-branch-select` CSS is removed.

## Signatures (exact)

```scala
// BranchBar.scala — picker's default class is now the shared panel select class.
def picker(scenarioState: ScenarioState, scenariosEnabled: Signal[Boolean],
           domCls: String = "compare-branch-select"): HtmlElement

// TreeListView.scala — `rowTrailing` removed (the baseline eye moves to the
// slot-card actions row built in AnalyzeView; no other caller used it).
def apply(state: TreeViewState, onNewTree: Option[() => Unit] = None,
          leadingControl: Option[HtmlElement] = None, onRefreshExtra: () => Unit = () => (),
          bare: Boolean = false): HtmlElement

// AnalyzeView.renderBaselineHead — two rows: picker (swatch + bare TreeListView
// = branch + tree + refresh) then actions (eye alone, right-aligned). Baseline
// BranchCard now initiallyOpen = false with expandOn = tree-loaded (as comparands).
```

## Styling (app.css)

- `.slot-card-actions` already right-aligned (`justify-content: flex-end`); a
  single baseline eye lands at the same right edge as the comparand remove.
- Panel select focus: one grouped rule `.compare-branch-select:focus,
  .compare-tree-select:focus, .saved-tree-panel .tree-select:focus` (panel-scoped
  where the box rule is panel-scoped).
- Remove all `.baseline-branch-select` rules (grouped box entry, standalone rule,
  `.tree-list-bare >` flex entry → `.compare-branch-select`).

## ADR alignment

View composition + CSS only; no domain/wire/DTO change. ADR-019 unaffected
(BranchCard/TreeListView stay pure views).

## File inventory (continuation 5)

- modules/app/src/main/scala/app/components/BranchBar.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/styles/app.css

## Verification (continuation 5)

- `sbt app/compile` (zero new warnings), `sbt app/test`.
- Manual: baseline header is two rows (refresh row 1, eye row 2 right-aligned,
  rightmost buttons aligned across all slots); every panel select shows a focus
  border; baseline starts collapsed and expands on tree load; `.baseline-branch-select`
  gone with no visual change.
