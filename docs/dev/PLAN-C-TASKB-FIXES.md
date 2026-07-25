# PLAN — Task B review fixes (Compare feature, app only)

Status: Sub-decisions ruled 2026-07-25 (Open decision 1 → Option C, Open
decision 2 → variant ii). Trio + trio-review fixes landed. Follow-up 2
(exclusion Option C + correctness fix (a) + if/else 1-4) specified 2026-07-25,
awaiting approval before implementation.
Follow-up to the Task B
review of the uncommitted compare-slot coordinate work (PLAN-C-REFACTOR.md,
Task B). The three top-level choices are already ruled by the user
(review Finding 2 → Option C, Finding 3 → Option A, Finding 4 → Option B);
this plan specifies their implementation. Both sub-decisions are now ruled
by the user (Open decision 1 → Option C, Open decision 2 → variant ii); the
signatures below already reflect those rulings, so there are no open
decisions remaining.

All changes are in `modules/app`. No wire/API change, no server change, no
dependency change.

---

## Fix 1 — superseding fetch pipeline for `selectedTree` (Finding 2, Option C)

### Problem

`TreeViewState.selectedTree` is loaded via `ZJS.loadOptionInto`
(modules/app/src/main/scala/app/core/ZJS.scala, lines 162–174), which forks
a request and writes whatever response arrives into the target `Var`. Two
overlapping loads on the same `TreeViewState` instance race: the last
response to arrive wins, even if it belongs to the older request. Every
other fetch in the app that can be superseded (`curveCache` in
`LECChartState`, `diffResult` in `ScenarioDiffState`, the per-branch tree
lists in `TreeListState`) already routes through `ZJS.requestPipeline` /
`loadStatePipeline`, where a newer trigger makes `flatMapSwitch` drop the
stale request's stream. `selectedTree` is the one fetched value still
outside that discipline.

Overlapping loads are reachable today (branch switch + tree switch in quick
succession; slot effective-tree changes) and become more frequent with
Fix 2, which makes a slot's effective tree change on both tree-override and
active-tree events. The `.distinct` on `CompareSlotState.branchSignal`
(review Finding 1, already applied) removes one trigger of the race; this
fix removes the race itself.

Relationship to PLAN-C-REFACTOR's "post-landing cleanup item": that item
targets synchronous `.now()` reads in the reactive layer and is not
executed here. Fix 1 is a separate hardening of the same file's fetch
plumbing; it neither adds nor removes any `.now()` read population that the
cleanup item inventories.

### Callers of the affected load path (traced, complete)

The pipeline boundary is per `TreeViewState` instance (the tab's own
instance, plus one per compare slot — constructed in `Main`). Every path
that loads or clears `selectedTree`:

- `loadTreeStructure(id)` — internal; called by `selectTree` and
  `refreshSelectedTree`.
- `selectTree(id)` — TreeListView.scala:127 (dropdown), TreeBuilderView.scala:57
  (after create), AnalyzeView.scala:336 (slot effective-tree subscription).
- `refreshSelectedTree()` — TreeListView.scala:140 (refresh button),
  AnalyzeView.scala:520 (`onRefreshExtra`), and TreeViewState's own two
  internal subscriptions (`activeBranchSignal.changes`, `keySignal.changes`).
- `deselectTree()` — AnalyzeView.scala:338, 342 (slot subscription); the
  only direct `selectedTree.set` writer today.

Readers (`selectedTree.signal` / `.now()`) are unchanged and unaffected:
DesignView.scala:124, TreeDetailView.scala:110, AnalyzeView.scala:223, 253,
357, 362, 365, 684, and `LECChartState`'s constructor parameter
(`selectedTree.signal` remains a `StrictSignal` because the `Var` stays).

### Design

`selectedTree` stays a `Var` owned by `TreeViewState` (its `.signal` is
passed as `StrictSignal` to `LECChartState`). Writes move into a
trigger-bus pipeline, exactly the `ScenarioDiffState.diffResult` shape,
including the idempotency guard. Because the endpoint returns
`Option[RiskTree]` (`None` = tree not found → `Failed(noneMessage)`), ZJS
gains an `Option`-unwrapping sibling of `loadStatePipeline`, mirroring the
existing `loadInto` / `loadOptionInto` pairing.

`loadOptionInto` then has zero callers (verified: `TreeViewState` line 150
is its only use, repo-wide) and is deleted as dead code. `loadInto` keeps
its two callers (`DistributionChartState`, `ScenarioMergeState`) and is out
of scope.

### Signatures

```scala
// modules/app/src/main/scala/app/core/ZJS.scala — NEW (placed next to
// loadStatePipeline); the extension method loadOptionInto is DELETED.
/** `loadStatePipeline` for requests returning `Option[A]`: `Some` loads,
  * `None` fails with `noneMessage`, error routing as `loadStatePipeline`.
  */
def loadOptionStatePipeline[A](
  triggers: EventStream[Option[() => EventStream[Either[Throwable, Option[A]]]]],
  noneMessage: String
): EventStream[LoadState[A]] =
  requestPipeline[Either[Throwable, Option[A]], LoadState[A]](
    triggers,
    idle    = LoadState.Idle,
    loading = LoadState.Loading,
    settle  = {
      case Right(Some(a))                                      => LoadState.Loaded(a)
      case Right(None)                                         => LoadState.Failed(noneMessage)
      case Left(e) if RepositoryFailure.isWorkspaceSentinel(e) => LoadState.Idle
      case Left(e)                                             => LoadState.Failed(e.safeMessage)
    }
  )
```

```scala
// modules/app/src/main/scala/app/state/TreeViewState.scala
// selectedTreeId, selectedTree declarations unchanged. NEW private members:
private val treeTrigger =
  new EventBus[Option[() => EventStream[Either[Throwable, Option[RiskTree]]]]]

// Wired once at construction (same shape and same-value guard as
// ScenarioDiffState / LECChartState):
loadOptionStatePipeline(treeTrigger.events, "Tree not found").foreach { v =>
  if selectedTree.now() != v then selectedTree.set(v)
}(using unsafeWindowOwner)

// CHANGED bodies (public signatures unchanged):
def loadTreeStructure(id: TreeId): Unit =
  expandedNodes.set(Set.empty)
  selectedNodeId.set(None)
  chartState.reset()
  keySignal.now() match
    case Some(key) =>
      treeTrigger.emit(Some(() =>
        getWorkspaceTreeStructureEndpoint((userIdAccessor(), key, id, branchAccessor())).toOutcomeEventStream
      ))
    case None => ()

def deselectTree(): Unit =
  if selectedTreeId.now().isDefined then
    selectedTreeId.set(None)
    treeTrigger.emit(None)   // pipeline resolves to Idle and supersedes any in-flight load
    chartState.reset()
```

### Behaviour notes

- `Loading` (and `Idle` on deselect) now lands one Airstream transaction
  after the call instead of synchronously — identical to how `curveCache`
  and `diffResult` already behave. All traced readers react to
  `.changes`/render states and are insensitive to this shift; the
  `Failed("Tree not found")` collector in AnalyzeView.scala:253 and
  DesignView's `selectedTree.changes` handler observe the same value
  sequence as today.
- The known shared gap of `requestPipeline` (superseding stops observing,
  it does not cancel the underlying fiber) is inherited deliberately;
  already tracked in TODO.md.

---

## Fix 2 — coordinate-granularity engagement, exclusion, and invalidation (Finding 3, Option A)

### Problem

Every exclusion/engagement/invalidation currently keys on branch alone.
Consequences: a slot pointing at the tab's own branch is unconditionally
excluded/reset, so "my branch, tree X vs tree Y" is unreachable, and a
cross-tree slot on the branch the tab just switched to is wrongly reset.
The comparison identity after Task B is the pair (branch, effective tree);
the branch-only checks are a leftover of the branch-only coordinate.

Effective tree of a slot = `coord.treeOverride.orElse(activeTreeId)` where
`activeTreeId` is the tab's `treeViewState.selectedTreeId`. A slot is a
distinct comparison side exactly when its (branch, effective tree) differs
from the active pair (activeBranch, activeTreeId).

### Design

One pure predicate on `SlotCoordinate` carries the rule; every consumer
(three engagement filters, invalidation, picker exclusion, slot
tree-pointing, diff gate) delegates to it. The tab's
`treeViewState.selectedTreeId.signal` is threaded as an additional input
into each combined signal that previously compared branches only.

A coordinate that currently collides with the active pair is representable
and inert: it engages nothing, fetches nothing, renders nothing. This is
both the transient guard (active branch switched onto a slot, reset
pending) and — new — the entry state for same-branch cross-tree comparison
(branch chosen, tree override not yet picked; see Open decision 1).

### Signatures

```scala
// modules/app/src/main/scala/app/state/CompareState.scala — SlotCoordinate
// gains two methods (fields unchanged):
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId]):
  /** The tree this slot compares on when the tab's selected tree is
    * `activeTreeId`: the pinned override, else the active tree. */
  def effectiveTree(activeTreeId: Option[TreeId]): Option[TreeId] =
    treeOverride.orElse(activeTreeId)

  /** True when this coordinate resolves to the same (branch, tree) pair
    * the tab itself shows — such a slot is not a distinct comparison side:
    * it engages nothing until the pair differs. */
  def collidesWith(activeBranch: BranchChoice, activeTreeId: Option[TreeId]): Boolean =
    branch == activeBranch && effectiveTree(activeTreeId) == activeTreeId
```

```scala
// modules/app/src/main/scala/app/state/CompareState.scala — the toBranch
// extension is DELETED (all four main-code uses are replaced below; its
// documented purpose — branch-keyed exclusion/engagement — no longer
// exists). toCoordinate stays.
```

AnalyzeView changes, one per affected site (current line numbers in
parentheses):

```scala
// (71–72) engagement helper — activeTreeId parameter added, predicate
// delegated; returns the same shape:
def engagedSlots(
  targets: Vector[CompareTarget],
  activeBranch: BranchChoice,
  activeTreeId: Option[TreeId]
): Vector[(Int, BranchChoice)] =
  targets.zipWithIndex.flatMap { (t, i) =>
    t.toCoordinate.filterNot(_.collidesWith(activeBranch, activeTreeId)).map(c => (i, c.branch))
  }

// (483–485) chart-area render key — selectedTreeId threaded in:
child <-- compareState.mode.signal
  .combineWith(compareState.targets, scenarioState.activeBranch.signal, treeViewState.selectedTreeId.signal)
  .map { (mode, targets, activeBranch, activeTid) => (mode, engagedSlots(targets, activeBranch, activeTid)) }
  .distinct

// (534–536) card-stack render key — same threading:
child <-- compareState.comparisonOn
  .combineWith(compareState.targets, scenarioState.activeBranch.signal, treeViewState.selectedTreeId.signal)
  .map { (on, targets, activeBranch, activeTid) => (on, engagedSlots(targets, activeBranch, activeTid)) }
  .distinct

// (145–161) combinedSpecSignal — selectedTreeId appended to the second
// combineWith; tuple widens by one; the engaged filter uses the predicate:
val combinedSpecSignal: Signal[LoadState[js.Dynamic]] =
  compareState.mode.signal
    .combineWith(treeViewState.chartState.specSignal, treeViewState.curveCache.distinct, visibleNodeIds)
    .combineWith(slotOverlayInputs, scenarioState.activeBranch.signal, activePalette, treeViewState.selectedTreeId.signal)
    .map { case (mode, singleSpec, thisCurves, thisVisible, slotInputs, activeBranch, thisPalette, activeTid) =>
      // ... unchanged structure; engaged filter becomes:
      val engaged = slotInputs.zipWithIndex.flatMap { case ((curves, visible, target, palette), i) =>
        target.toCoordinate.filterNot(_.collidesWith(activeBranch, activeTid))
          .map(_ => (curves, visible, s"s${i + 1}", palette))
      }
      // ... (side construction per Fix 3 below)
    }

// (202–219) sideBySideSpecs — selectedTreeId appended; per-slot filter
// (line 209) uses the predicate:
val sideBySideSpecs: Signal[(LoadState[js.Dynamic], Vector[LoadState[js.Dynamic]])] =
  treeViewState.curveCache.distinct
    .combineWith(visibleNodeIds, treeViewState.nodeColorMap)
    .combineWith(slotPanelInputs, scenarioState.activeBranch.signal, treeViewState.selectedTreeId.signal)
    .map { case (thisCurves, thisVisible, thisColors, slotInputs, activeBranch, activeTid) =>
      // ... slotPairs condition becomes:
      //   if target.toCoordinate.exists(c => !c.collidesWith(activeBranch, activeTid))
      // ... rest unchanged
    }

// (275–287) per-slot diff subscription — the same-tree gate is kept and a
// different-branch gate added: a colliding slot (same branch, same
// effective tree) is now representable and must not diff a branch against
// itself. Cross-tree slots keep getting no diff (no shared lineage):
compareSlots.map { slot =>
  treeViewState.selectedTreeId.signal
    .combineWith(compareState.comparisonOn, slot.state.target.signal, scenarioState.activeBranch.signal)
    .changes.debounce(100) --> {
      case (Some(activeTid), true, CompareTarget.Target(coord), activeBranch)
          if coord.treeOverride.forall(_ == activeTid) && coord.branch != activeBranch =>
        slot.diffState.loadDiff(activeTid, activeBranch, coord.branch)
      case _ =>
        slot.diffState.reset()
    }
}

// (328–343) per-slot effective-tree subscription — activeBranch added as
// an input; a colliding coordinate is treated like "no effective tree"
// (deselect), so an inert slot never loads a duplicate of the active card,
// never seeds, and never mutates the active card's selection through the
// empty-baseline root fallback in seedCompareCard:
compareSlots.map { slot =>
  treeViewState.selectedTreeId.signal
    .combineWith(compareState.comparisonOn, slot.state.target.signal, scenarioState.activeBranch.signal)
    .changes --> {
      case (activeTid, true, CompareTarget.Target(coord), activeBranch) =>
        coord.effectiveTree(activeTid) match
          case Some(tid) if !coord.collidesWith(activeBranch, activeTid) =>
            if !slot.treeViewState.selectedTreeId.now().contains(tid) then
              slot.treeViewState.selectTree(tid)
          case _ =>
            slot.treeViewState.deselectTree()
      case (_, false, CompareTarget.Target(_), _) =>
        () // toggled off, target still chosen: preserve the card's state
      case _ =>
        slot.treeViewState.deselectTree()
    }
}

// (300–315) invalidation subscription — reset condition tightened from
// coord.branch == active to full-coordinate collision; deletion handling
// unchanged. Exact inputs are Open decision 2; recommended variant (ii):
compareSlots.map { slot =>
  scenarioState.activeBranch.signal
    .combineWith(scenarioState.scenarios)
    .changes --> { (active, list) =>
      slot.state.target.now() match
        case CompareTarget.Target(coord) =>
          val nowActive = coord.collidesWith(active, treeViewState.selectedTreeId.now())
          val deleted = coord.branch match
            case BranchChoice.Scenario(name) =>
              list match
                case LoadState.Loaded(l) => !l.exists(_.name == name)
                case _                   => false
            case BranchChoice.Main => false
          if nowActive || deleted then slot.state.target.set(CompareTarget.NotChosen)
        case CompareTarget.NotChosen => ()
    }
}

// (707–744) renderBranchPicker — activeTreeId parameter added; the
// exclusion computation is Open decision 1; recommended Option C:
private def renderBranchPicker(
  scenarioState: ScenarioState,
  slot: CompareSlot,
  otherSlots: Vector[CompareSlotState],
  activeTreeId: Signal[Option[TreeId]],
  disabledSignal: Signal[Boolean]
): HtmlElement
// call site (400–407) additionally passes:
//   activeTreeId = treeViewState.selectedTreeId.signal

// exclusion body under Option C (exclude a branch only when picking it,
// with this slot's existing override, would duplicate ANOTHER slot's
// (branch, effective tree) pair; the active branch is always offered —
// its collision is resolvable through the tree select):
val optionEntries: Signal[List[(String, String)]] =
  BranchBar.branchOptionEntries(
    scenarioState.lastLoadedScenarios,
    excludeValues = activeTreeId
      .combineWith(slot.state.target.signal, Signal.combineSeq(otherSlots.map(_.target.signal)))
      .map { (activeTid, own, otherTargets) =>
        val wouldBeEffective = own.toCoordinate.flatMap(_.treeOverride).orElse(activeTid)
        otherTargets.flatMap(_.toCoordinate)
          .collect { case c if c.effectiveTree(activeTid) == wouldBeEffective => BranchBar.branchOptionValue(c.branch) }
          .toSet
      }
  )
```

`BranchBar.scala`, `Main.scala`, `CompareColorAssigner.scala`,
`CompareSlotState`, and `seedCompareCard` are not touched by Fix 2 (prose
note, not inventory): the picker keeps `branchOptionEntries`'s existing
signature; slot construction and branch derivation are unchanged; seeding
is protected by the deselect-on-collision rule above.

### Behaviour consequences (accepted)

- Same-branch cross-tree comparison becomes reachable, including "my
  current branch, tree X vs tree Y".
- Two engaged sides can now show the same branch name (different trees) on
  cards/panels, and — after Fix 3 — the same colour family. Both accepted
  by user ruling; label disambiguation is out of scope.
- A slot pinned to a different tree survives the tab's branch switching
  onto its branch (previously reset). This also independently narrows the
  Finding 1 race surface.

---

## Fix 3 — remove `deCollide` (Finding 4, Option B)

### Design

`BranchPaletteState.deCollide` (BranchPaletteState.scala lines 85–95, the
display-only overlay palette de-collision) is removed, together with its
single call site in AnalyzeView's `combinedSpecSignal` (line 172) and the
comment block introducing it (lines 166–171). The overlay reverts to
feeding each side its resolved family directly — the wiring that existed
before `deCollide` was added. User ruling: two branches assigned the same
family draw the same colour on the overlay and on their swatches;
consistent between surfaces, ambiguity accepted. No duplicate-assignment
prevention is added.

With Fix 2, two same-branch sides (cross-tree) resolve to the same family
by construction and will draw in one colour; explicitly accepted.

### Exact replacement in `combinedSpecSignal`

```scala
// BEFORE (inside the None branch of the Failed-check match):
val palettes = BranchPaletteState.deCollide(thisPalette +: engaged.map(_._4))
val sides =
  CompareColorAssigner.OverlaySide(
    loadedOrEmpty(thisCurves), thisVisible, palettes.head, "active"
  ) +: engaged.zip(palettes.tail).map { case ((curves, visible, slotLabel, _), palette) =>
    CompareColorAssigner.OverlaySide(loadedOrEmpty(curves), visible, palette, slotLabel)
  }

// AFTER:
val sides =
  CompareColorAssigner.OverlaySide(
    loadedOrEmpty(thisCurves), thisVisible, thisPalette, "active"
  ) +: engaged.map { case (curves, visible, slotLabel, palette) =>
    CompareColorAssigner.OverlaySide(loadedOrEmpty(curves), visible, palette, slotLabel)
  }
```

Verified: `deCollide` has exactly one call site (AnalyzeView:172) and no
other reference in main code; `CompareColorAssigner` and the swatch/panel
wiring read each side's own resolved palette already, so nothing else
changes. Remaining `PaletteData.allFamilies` usage is unaffected.

### Test removals

BranchPaletteStateSpec.scala: delete the three `deCollide` tests (lines
65–89: "leaves already-distinct families untouched", "shifts a later
duplicate…", "is display-only…"). Every other test stays. This deletes
assertions only about the deleted function (Decision Trigger 8 — covered
by approval of this plan).

Doc alignment note: PLAN-C-REFACTOR.md Task B's design bullet "Palette …
display-level de-collision rule" is superseded by this user ruling; that
plan document is not edited (it records the state at its presentation
date), this plan is the governing record for the change.

---

## Implementation order

1. **Fix 1** (ZJS.scala, TreeViewState.scala) — disjoint files, no overlap
   with the AnalyzeView work; lands the superseding plumbing that Fix 2's
   new effective-tree flows exercise.
2. **Fix 3** (BranchPaletteState.scala, AnalyzeView.scala
   `combinedSpecSignal` side construction, BranchPaletteStateSpec.scala) —
   small removal that simplifies the exact block Fix 2 then restructures.
3. **Fix 2** (CompareState.scala, AnalyzeView.scala broadly,
   CompareStateSpec.scala) — the largest change, applied last so the
   shared `combinedSpecSignal` block is edited in its final (post-Fix 3)
   form once.

Shared edit region: AnalyzeView's `combinedSpecSignal` `else` branch is
touched by Fix 3 (side construction) and Fix 2 (input tuple + engaged
filter). The 3-before-2 order means each line of that block is rewritten
once. Each step compiles and passes `sbt app/test` on its own, so the
steps are individually revertable.

## File inventory

- modules/app/src/main/scala/app/core/ZJS.scala
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/app/src/main/scala/app/state/CompareState.scala
- modules/app/src/main/scala/app/state/BranchPaletteState.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/components/FormInputs.scala
- modules/app/src/main/scala/app/components/BranchBar.scala
- modules/app/src/test/scala/app/state/CompareStateSpec.scala
- modules/app/src/test/scala/app/state/BranchPaletteStateSpec.scala
- modules/app/src/test/scala/app/views/AnalyzeViewSeedSpec.scala

Not touched (prose, not inventory): Main.scala,
CompareColorAssigner.scala, LECChartState.scala, ScenarioDiffState.scala,
TreeListState.scala, TreeListView.scala, TreeBuilderView.scala,
TreeDetailView.scala, DesignView.scala, CompareColorAssignerSpec.scala,
AnalyzeViewSeedSpec.scala, build.sbt (version handling below).

## ADR alignment

- **ADR-019 (frontend architecture)** — bears on all three fixes.
  - Fix 1: compliant. `selectedTree` stays a parent-owned `Var`; writes
    move behind an event pipeline identical to the three sanctioned
    precedents (`LECChartState`, `ScenarioDiffState`, `TreeListState`);
    consumers keep receiving signals. No `.now()` in any rendering
    pipeline is added; the pipeline's same-value guard `.now()` matches
    the existing precedents.
  - Fix 2: compliant. Engagement is derived per Pattern 4 (signals
    combined, no stored redundant state); the invalidation subscription
    stays a Pattern 6 correction (reacts to external signals, reads its
    own value via `.now()`, never reacts to the Var it corrects). The
    recommended variant (ii) adds one cross-signal `.now()` read inside an
    event handler — flagged in Open decision 2, including its tension with
    the queued synchronous-reads cleanup (PLAN-C-REFACTOR post-landing
    item).
  - Fix 3: compliant — pure removal; per-side palettes remain derived
    signals.
- **ADR-018 (nominal ID wrappers)** — Fix 2's new methods take
  `BranchChoice` / `Option[TreeId]`, never raw strings; no new ID types
  introduced. Compliant (also satisfies the ADR-001 no-raw-primitives
  amendment).
- **ADR-032 (content-equality relations)** — bears on Fix 2's diff gate:
  the semantic diff (`ScenarioDiffService`, domain hashes) is defined
  across branches over shared node lineage. The gate `treeOverride.forall
  (_ == activeTid) && branch != activeBranch` requests a diff exactly for
  cross-branch same-tree slots; cross-tree slots (no lineage) and
  same-branch slots (no second branch) get none. Compliant.
- **PLAN-C-REFACTOR Task B** — Fix 3 supersedes its palette de-collision
  design bullet (user ruling); flagged above, not a silent deviation.

## Open decisions

### Open decision 1 (RULED → Option C, user 2026-07-25) — how the slot branch picker treats colliding choices

Goal and context: Fix 2 makes "slot on the tab's own branch, different
tree" a valid comparison, but a branch is picked before a tree override
is, so the moment of picking the active branch always produces a
coordinate that still collides with the active pair (override not yet
chosen → effective tree = active tree). The old rule "exclude the active
branch and every other slot's branch from the options" makes the new
feature unreachable from the picker. Something must give: either the
exclusion list, or the entry flow.

- **Option A — strict pair exclusion.** Exclude a branch when picking it
  (with the slot's existing override) would duplicate any on-screen pair,
  the active pair included. From the user's perspective: the active branch
  never appears in "compare against" unless the slot already has a tree
  override pinned — reaching "my branch, tree X vs Y" requires picking a
  different branch first, pinning the tree, then switching the branch
  select back. Pro: the picker never offers a choice that does nothing.
  Con: the ruled-in headline flow exists but is hidden behind an
  unguessable three-step detour; in practice the feature stays
  undiscoverable.
- **Option B — no branch exclusion.** Every branch is always offered; a
  colliding or duplicate choice is simply inert (no card, no chart
  contribution, no fetch) until the tree select differentiates it. From
  the user's perspective: pick "my branch" → nothing appears yet → pick
  "tree Y" → the comparison appears. Pro: simplest code (exclusion logic
  deleted; the engagement predicate is the single authority). Con: the
  picker will also offer a branch that duplicates the other slot exactly,
  and a duplicate pick silently shows nothing — no feedback about why.
- **Option C — exclude other slots' exact pairs, always allow the active
  branch.** Exclusion keeps one job: a branch is hidden only when choosing
  it would duplicate another slot's current (branch, effective tree) pair.
  The active branch is always offered because its collision is resolvable
  in place via the tree select. User's perspective: same entry flow as
  Option B for the new feature (pick own branch, then a tree); picking the
  other slot's exact coordinate is still prevented up front. Con: between
  the branch pick and the tree pick the slot shows a chosen branch with no
  card — same momentary silent state as B, limited to the one flow that
  needs it.

**Recommendation (mine): Option C.** It opens the ruled-in flow with the
fewest surprises and keeps the only exclusion that is unambiguous. The
signatures above show Option C; Option B would instead delete the
`excludeValues` computation (and the then-unused `otherSlots` and
`activeTreeId` parameters); Option A would add the active pair to the
blocking set in the same computation.

### Open decision 2 (RULED → variant ii, user 2026-07-25) — which events the invalidation subscription reacts to

Goal and context: the reset condition is ruled (full-coordinate collision,
or scenario deleted). Unruled: whether the subscription also fires on
active-TREE switches, or keeps firing only on active-branch/scenario-list
changes. The difference is visible when the tab's selected tree is
switched onto a slot's pinned tree while both sides are on the same
branch, and while a slot sits in the entry state of decision 1 (branch
chosen, tree not yet).

- **Variant (i) — add `treeViewState.selectedTreeId.signal` to the
  subscription's inputs.** Any collision, however it arose, promptly
  resets the slot to `NotChosen`. User's perspective: switching the active
  tree onto the slot's pinned tree clears the slot's picker back to the
  placeholder; switching the tree back does not restore it — the choice is
  gone and must be re-picked. Also: while setting up "my branch, tree Y",
  an intervening active-tree switch wipes the half-finished pick. Pro:
  no lingering chosen-but-inert slots after any event; purely
  signal-driven (no cross-signal `.now()`). Con: destroys a state the user
  could have resolved reversibly; hostile to the decision-1 entry flow.
- **Variant (ii) — keep the inputs (activeBranch, scenarios); evaluate the
  collision against `treeViewState.selectedTreeId.now()` in the handler.**
  Branch switches and deletions reset as today (now coordinate-accurate);
  tree switches never reset — a tree-switch collision leaves the slot
  chosen but disengaged, and moving the active tree away re-engages it
  with its card state re-seeded. User's perspective: cross-tree slots
  survive tree browsing on the active tab; nothing is destroyed that the
  user can undo by browsing back. Pro: non-destructive, consistent with
  collision-as-inert-state (decision 1 B/C). Con: one more synchronous
  cross-signal read inside a subscription handler — the population the
  queued PLAN-C-REFACTOR cleanup targets (that cleanup may later convert
  it to in-transaction sampling; the reset semantics chosen here are
  unaffected by that conversion).

**Recommendation (mine): Variant (ii)** — reset stays reserved for events
that genuinely invalidate the choice (the branch moved onto it, the branch
was deleted); collisions caused by tree browsing are reversible and are
handled by disengagement, not destruction. The signature block above shows
variant (ii); variant (i) replaces the `combineWith` inputs and takes
`activeTid` from the tuple.

## Verification plan

Tests added:

- `CompareStateSpec` (modules/app/src/test/scala/app/state/CompareStateSpec.scala):
  - `effectiveTree`: `SlotCoordinate(b, None).effectiveTree(Some(t)) == Some(t)`;
    `SlotCoordinate(b, Some(t2)).effectiveTree(Some(t)) == Some(t2)`;
    `SlotCoordinate(b, None).effectiveTree(None).isEmpty`.
  - `collidesWith`: true for (activeBranch, override None) and for
    (activeBranch, override == activeTid); false for (activeBranch,
    override != activeTid), for (otherBranch, any override), and for
    (activeBranch, override Some(t)) when activeTid is None.
  - The existing `toBranch` assertions (lines 22, 24) are removed with the
    deleted extension; the test keeps its `toCoordinate` assertions
    (Decision Trigger 8 — covered by approval of this plan).

Tests removed:

- `BranchPaletteStateSpec`: the three `deCollide` tests (Fix 3 above). All
  other suites (`CompareColorAssignerSpec`, `AnalyzeViewSeedSpec`,
  `ScenarioDiffStateSpec`, …) are untouched and must stay green.

No new async-pipeline test for Fix 1: the existing pipelines
(`ScenarioDiffState`, `LECChartState`, `TreeListState`) are likewise
covered by their pure derivations plus manual verification; the pipeline
mechanism itself (`requestPipeline`) is unchanged and `loadOptionStatePipeline`
is a settle-table over it.

Commands that must be green (pass/fail reporting only):

```
sbt app/compile
sbt app/test
```

Manual compare-flow verification (Vite dev stack per register-dev skill):

- **Fix 1:** with browser devtools network throttling on, (a) switch the
  tree dropdown twice in quick succession — the detail view and chart must
  settle on the second tree; (b) in Compare, change a slot's tree override
  twice quickly — the slot's card must settle on the second override. In
  both cases the first (stale) response must never appear after the
  second selection.
- **Fix 2:** (a) Compare on → pick the tab's own branch in a slot (per
  decision 1 outcome) → pick a different tree → card, overlay curves, and
  side-by-side panel appear; no ✎ markers (cross-tree, and same branch);
  (b) cross-branch same-tree slot still shows ✎ markers as before;
  (c) pin a slot to branch B tree Y, switch the tab's branch to B with a
  different active tree — the slot survives and keeps comparing;
  (d) arrange full collision (same branch, same effective tree) — the slot
  resets or disengages per decision 2 outcome, and the overlay/panels/
  cards render the single-branch view;
  (e) regression: all §6 same-tree flows of PLAN-C-REFACTOR Task B
  (overlay, side-by-side, cards, markers, colour picker) unchanged for
  cross-branch slots.
- **Fix 3:** assign the same palette family to the active branch and a
  compared branch — overlay draws both sides in that family (ambiguity
  accepted) and each card swatch shows the same family as its curves
  (surface consistency); assign distinct families — distinct colours as
  today.

## Versioning

These fixes amend the uncommitted Task B working tree. Landing together
with Task B they are covered by Task B's MINOR bump (PLAN-C-REFACTOR
Versioning section). If landed as a separate commit after Task B, one
MINOR bump (Fix 2 is a new user-visible capability), mirrored to `.env`
and `.env.irmin`.

---

# Follow-up: trio complex-review fixes (2026-07-25)

The Fable complex review of the implemented trio found one MUST-FIX, two
SHOULD-FIX (both ruled), and stale-comment cleanup. Rulings: R1 apply;
R2 Option A + a focused Fable clarity review after implementation;
R3 the generalized seeding rule below (user's proposal); R4 comment
cleanup (recommended). All production changes are in `AnalyzeView.scala`;
tests in `AnalyzeViewSeedSpec.scala`; comment cleanup also touches
`CompareColorAssignerSpec.scala`. No wire/API change. No open decisions.

## Fix R1 (review Finding 1, MUST-FIX) — collision reset only on a branch change

The invalidation subscription currently re-evaluates the collision predicate
on every `activeBranch.combineWith(scenarios)` emission, so a scenario-list
refresh (Loading→Loaded) turns a tree-browse collision into a destructive
reset, violating the ruled variant-ii semantics. Split into two
subscriptions: branch changes drive the collision reset; scenario-list
changes drive only the deletion reset.

```scala
// AnalyzeView.scala (replaces the single invalidation subscription):
// Collision reset — only when the active BRANCH changes; a tree-browse
// collision must disengage (handled by the effective-tree subscription),
// never reset.
compareSlots.map { slot =>
  scenarioState.activeBranch.signal.changes --> { active =>
    slot.state.target.now() match
      case CompareTarget.Target(coord)
          if coord.collidesWith(active, treeViewState.selectedTreeId.now()) =>
        slot.state.target.set(CompareTarget.NotChosen)
      case _ => ()
  }
}
// Deletion reset — only when the scenario the slot names disappears.
compareSlots.map { slot =>
  scenarioState.scenarios.changes --> { list =>
    slot.state.target.now() match
      case CompareTarget.Target(coord) =>
        val deleted = coord.branch match
          case BranchChoice.Scenario(name) =>
            list match
              case LoadState.Loaded(l) => !l.exists(_.name == name)
              case _                   => false
          case BranchChoice.Main => false
        if deleted then slot.state.target.set(CompareTarget.NotChosen)
      case CompareTarget.NotChosen => ()
  }
}
```

## Fix R2 (review Finding 2, Option A) — mirror pair-exclusion into the tree select

Rule: the tree `<select>` disables a tree-override value — including "same
tree as active" (`""`) — when choosing it would make this slot's
`(branch, effective tree)` equal another slot's current pair. The branch
select already enforces this; the tree select is the second entry point and
must enforce the same rule. Candidate helper (the focused Fable review after
implementation assesses whether the branch-select and tree-select exclusions
can share one abstraction / read more cleanly — the shape below is a starting
point, not fixed):

```scala
// AnalyzeView.scala, in renderBranchPicker — tree-override values that would
// duplicate another slot's (branch, effective tree) pair for this slot's
// branch:
def excludedTreeOverrides(
  ownBranch: BranchChoice,
  activeTid: Option[TreeId],
  treeIds: List[TreeId],
  otherCoords: Vector[SlotCoordinate]
): Set[String] =
  val taken =
    otherCoords.collect { case c if c.branch == ownBranch => c.effectiveTree(activeTid) }.toSet
  ("" :: treeIds.map(_.value.toString)).filter { v =>
    val eff = if v.isEmpty then activeTid else TreeId.fromString(v).toOption
    taken.contains(eff)
  }.toSet
// the tree <select> adds `disabled`/`--disabled` for options in this set,
// combined with the existing treeDisabled (picker off / no branch chosen).
```

## Fix R3 (review Finding 3, Option A — generalized per user rule) — comparand-root seed fallback

Current `computeSeed` seeds the compare side only where the active baseline's
node ids exist in the compare tree; across two trees the ids are disjoint, so
a cross-tree slot always seeds empty while the active card's root is toggled
on regardless (one-sided). Generalize the baseline-empty branch to fall back
to the compare tree's own root when the active root has no counterpart. No
cross-tree flag: the rule keys on counterpart presence, which already
distinguishes the two cases. The baseline-non-empty branch is unchanged
(selected-but-no-match already leaves the compare side empty and does not
touch the active card).

```scala
// AnalyzeView.scala — computeSeed gains compareRoot; only the else branch changes:
def computeSeed(
  baseline: Set[NodeId],
  activeRoot: Option[NodeId],
  compareTreeNodeIds: Set[NodeId],
  compareRoot: Option[NodeId],
  cap: Int = 13
): (Option[NodeId], List[NodeId]) =
  if baseline.nonEmpty then
    (None, baseline.toList.filter(compareTreeNodeIds.contains).sortBy(_.value).take(cap))
  else
    (activeRoot, activeRoot.filter(compareTreeNodeIds.contains).orElse(compareRoot).toList)

// seedCompareCard call site passes the compare tree's root:
val (rootToSelect, seeds) =
  computeSeed(baseline, activeRoot, compareTree.nodes.map(_.id).toSet, Some(compareTree.rootId))
```

`AnalyzeViewSeedSpec.scala`: update existing `computeSeed` calls for the new
parameter; add cross-tree cases — baseline empty + active root absent from
the compare tree → seeds `compareRoot`; baseline non-empty + no intersection
→ seeds empty and returns `None` for the active card.

## Fix R4 (review Finding 4) — stale comments — APPLIED

Behaviour-only comment corrections, no code change. Applied directly in the
main session (with R1) 2026-07-25:
- `AnalyzeView.scala` `renderBranchPicker` scaladoc: reworded — the active
  branch is always offered; a branch is hidden only when it would duplicate
  another slot's pair; one branch can occupy two slots on different trees.
- `AnalyzeView.scala` `combinedSpecSignal` scaladoc: sides are labelled by
  stable slot label (`active`/`s1`/`s2`), not branch name.

The `CompareColorAssignerSpec.scala` `deCollide` comment is dropped from scope
per user ruling (not worth touching a test file for one comment).

## Fix R1 status — APPLIED

The two-subscription invalidation split (above) was applied directly in the
main session 2026-07-25; `sbt app/compile` clean. Remaining follow-up work for
the Opus pass: Fix R3 then Fix R2.

## Verification (follow-up)

- `sbt app/compile` (zero new warnings), `sbt app/test` green.
- Manual: R1 — with a slot disengaged by a tree-browse collision, create/delete
  a scenario in Design; the slot must stay chosen and re-engage when the active
  tree moves away. R2 — a tree value that would duplicate another slot's pair is
  disabled. R3 — same-branch cross-tree slot shows a root-vs-root two-sided
  comparison immediately, not a blank compare side.
- Focused Fable review afterward on Fix R2's exclusion logic for clarity /
  possible unifying abstraction (per user ruling).

---

# Follow-up 2: exclusion-clarity Option C + correctness fix (a) + if/else rewrites (2026-07-25)

The focused Fable clarity review (of R2) plus the follow-up correctness pass
produced three ruled items. Rulings (user 2026-07-25):

- **Exclusion clarity → Option C** — one named pair-equality relation on
  `SlotCoordinate`, both selects re-expressed through it, and both exclusion
  computations lifted into pure functions with unit tests. Visibility:
  `private[views]` on the new pure functions, and the **same modifier applied
  to the existing `computeSeed`** (currently fully public by omission).
- **Correctness gap → fix (a)** — a slot whose `(branch, effective tree)`
  pair duplicates an earlier engaged slot's pair is inert (earlier slot wins),
  reusing the same `samePairAs` relation. This is disengagement, not reset:
  the slot's `target` is untouched, so browsing the active tree away
  re-engages it (consistent with the ruled variant-ii semantics — tree
  browsing never destroys slot state).
- **if/else → `match` rewrites 1–4** — all four approved (§ "if/else
  rewrites" below).

All changes are in `CompareState.scala`, `AnalyzeView.scala`,
`CompareStateSpec.scala`, `AnalyzeViewSeedSpec.scala` — all already in the
File inventory. No wire/API change, no new dependency, no open decisions.

## Fix C1 — one pair-equality relation on `SlotCoordinate`

`samePairAs` becomes the single definition of "same comparison"; `collidesWith`
is redefined through it; a named `SlotCoordinate.activeTab` factory makes the
tab-as-coordinate explicit (removes the inline `SlotCoordinate(activeBranch,
None)` construction). Fields unchanged.

```scala
// modules/app/src/main/scala/app/state/CompareState.scala
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId]):
  /** The tree this slot compares on when the tab's selected tree is
    * `activeTree`: the pinned override, else the active tree. */
  def effectiveTree(activeTree: Option[TreeId]): Option[TreeId] =
    treeOverride.orElse(activeTree)

  /** True when this coordinate and `other` resolve to the same
    * (branch, effective tree) pair under the tab's current active tree — the
    * one relation every exclusion, engagement, and collision check delegates
    * to. */
  def samePairAs(other: SlotCoordinate, activeTree: Option[TreeId]): Boolean =
    branch == other.branch && effectiveTree(activeTree) == other.effectiveTree(activeTree)

  /** True when this coordinate resolves to the same pair the tab itself shows.
    * The tab is the coordinate (activeBranch, follow-active). */
  def collidesWith(activeBranch: BranchChoice, activeTree: Option[TreeId]): Boolean =
    samePairAs(SlotCoordinate.activeTab(activeBranch), activeTree)

object SlotCoordinate:
  /** The active tab as a coordinate: its branch, following the active tree
    * (no pinned override), so `effectiveTree(activeTreeId) == activeTreeId`. */
  def activeTab(branch: BranchChoice): SlotCoordinate = SlotCoordinate(branch, None)
```

`samePairAs` is not `infix`-annotated: it takes a second argument, so it
cannot be used infix. It is a plain two-argument method.

**Parameter rename (user ruling 2026-07-25):** the tree-context parameter is
renamed `activeTreeId → activeTree` on `effectiveTree`, `samePairAs`, and
`collidesWith` — its type is `Option[TreeId]`, and the old name read as a
single id. The C1 signatures above already use `activeTree`. Call sites pass
this argument positionally (their local names — `activeTid`, `activeTreeId` —
are unaffected), so only the three method parameter names change.

## Fix C2 — lift both picker exclusions into pure `private[views]` functions

The branch-select and tree-select exclusions (currently two inline signal
`.map` bodies in `renderBranchPicker`) both become calls to pure functions in
the `AnalyzeView` object, each expressed through `samePairAs`. The signals
shrink to input assembly + one `.map` calling the function. `private[views]`
so the same-package `AnalyzeViewSeedSpec` (package `app.views`) can unit-test
them without a DOM/HTTP harness — following the `computeSeed` precedent.

```scala
// modules/app/src/main/scala/app/views/AnalyzeView.scala — object AnalyzeView
/** Branch-select values to hide: a branch is excluded when picking it (with
  * this slot's existing tree override) would duplicate another slot's current
  * (branch, effective tree) pair. */
private[views] def excludedBranchValues(
  ownOverride: Option[TreeId],
  otherCoords: Vector[SlotCoordinate],
  activeTid: Option[TreeId]
): Set[String] =
  otherCoords
    .filter(c => SlotCoordinate(c.branch, ownOverride).samePairAs(c, activeTid))
    .map(c => BranchBar.branchOptionValue(c.branch))
    .toSet

/** Tree-override values to hide (including "" = follow-active): a value is
  * excluded when choosing it would make this slot's (branch, effective tree)
  * pair equal another slot's current pair. The same-branch restriction falls
  * out of `samePairAs` (a different-branch other can never match). */
private[views] def excludedTreeOverrideValues(
  ownBranch: BranchChoice,
  overrideValues: List[String],   // "" plus each available tree id string
  otherCoords: Vector[SlotCoordinate],
  activeTid: Option[TreeId]
): Set[String] =
  overrideValues.filter { v =>
    val over      = if v.isEmpty then None else TreeId.fromString(v).toOption
    val candidate = SlotCoordinate(ownBranch, over)
    otherCoords.exists(c => candidate.samePairAs(c, activeTid))
  }.toSet
```

`renderBranchPicker` bodies become (behaviour identical to the current inline
forms — verified against the R2 code):

```scala
// branch-select exclusion (replaces the inline .map at lines ~772-779):
excludeValues = activeTreeId
  .combineWith(slot.state.target.signal, Signal.combineSeq(otherSlots.map(_.target.signal)))
  .map { (activeTid, own, otherTargets) =>
    excludedBranchValues(
      own.toCoordinate.flatMap(_.treeOverride),
      otherTargets.flatMap(_.toCoordinate).toVector,
      activeTid
    )
  }

// tree-select exclusion (replaces the inline body at lines ~801-817):
val excludedTreeOverrides: Signal[Set[String]] =
  slot.state.target.signal
    .combineWith(activeTreeId, treeOptions, Signal.combineSeq(otherSlots.map(_.target.signal)))
    .map { (own, activeTid, trees, otherTargets) =>
      own.toCoordinate.map(_.branch) match
        case None => Set.empty
        case Some(ownBranch) =>
          excludedTreeOverrideValues(
            ownBranch,
            "" :: trees.map(_._1),
            otherTargets.flatMap(_.toCoordinate).toVector,
            activeTid
          )
    }
```

## Fix C3 — `computeSeed` visibility

`computeSeed` is tightened from fully public (no modifier today) to
`private[views]` — the other known case of a test-only-public member the user
asked to fix alongside the new functions. Its signature and body are
otherwise unchanged; `AnalyzeViewSeedSpec` (package `app.views`) keeps its
access.

```scala
// was: def computeSeed(...)
private[views] def computeSeed(
  baseline: Set[NodeId],
  activeRoot: Option[NodeId],
  compareTreeNodeIds: Set[NodeId],
  compareRoot: Option[NodeId],
  cap: Int = 13
): (Option[NodeId], List[NodeId]) = // body unchanged
```

## Fix C4 — correctness gap: earlier-slot-wins engagement dedup (fix (a))

`engagedSlots` becomes the single authority on which slots are engaged: it
drops a slot that collides with the tab (unchanged) AND a slot whose pair
duplicates an earlier already-engaged slot's pair (new), using `samePairAs`.
The two spec builders (`combinedSpecSignal`, `sideBySideSpecs`) stop deriving
engagement independently and consume `engagedSlots`' index set instead — so
all three surfaces (overlay series, side-by-side panels, cards) agree, and the
dedup lives in one place.

```scala
// AnalyzeView.scala — engagedSlots (replaces the flatMap at lines ~70-77):
def engagedSlots(
  targets: Vector[CompareTarget],
  activeBranch: BranchChoice,
  activeTreeId: Option[TreeId]
): Vector[(Int, BranchChoice)] =
  targets.zipWithIndex
    .foldLeft((Vector.empty[SlotCoordinate], Vector.empty[(Int, BranchChoice)])) {
      case ((engaged, acc), (t, i)) =>
        t.toCoordinate match
          case Some(c)
              if !c.collidesWith(activeBranch, activeTreeId) &&
                 !engaged.exists(e => c.samePairAs(e, activeTreeId)) =>
            (engaged :+ c, acc :+ (i, c.branch))
          case _ => (engaged, acc)
    }
    ._2
```

```scala
// combinedSpecSignal (Overlay) — the engaged filter (lines ~163-165) becomes
// index membership against engagedSlots, so the dedup is not re-derived:
val engagedIdx = engagedSlots(slotInputs.map(_._3), activeBranch, activeTid).map(_._1).toSet
val engaged = slotInputs.zipWithIndex.collect {
  case ((curves, visible, _, palette), i) if engagedIdx.contains(i) =>
    (curves, visible, s"s${i + 1}", palette)
}

// sideBySideSpecs — the per-slot slotPairs guard (line ~208) becomes the same
// index-membership test:
val engagedIdx = engagedSlots(slotInputs.map(_._3), activeBranch, activeTid).map(_._1).toSet
// ... slotPairs: `if engagedIdx.contains(i)` in place of the collidesWith exists-check
```

(`slotInputs.map(_._3)` is the per-slot `CompareTarget` already carried in the
overlay/panel input tuples; the exact tuple index is confirmed against the
current source at implementation time. If the target is not positioned to
recover `targets` cheaply, `engagedSlots` is called with the same
`compareState.targets` value already threaded into these signals.)

## if/else rewrites (1–4, all approved)

```scala
// 1. parseBranch (renderBranchPicker, lines ~745-751)
raw match
  case ""                     => CompareTarget.NotChosen
  case BranchBar.mainSentinel => CompareTarget.Target(SlotCoordinate(BranchChoice.Main, existingOverride))
  case name =>
    ScenarioName.fromString(name).toOption
      .map(n => CompareTarget.Target(SlotCoordinate(BranchChoice.Scenario(n), existingOverride)))
      .getOrElse(CompareTarget.NotChosen)

// 2. combinedSpecSignal mode gate (line ~154) — explicit alternatives, not
//    `case _`, so a future 4th CompareMode is a compile error here:
mode match
  case CompareMode.Off | CompareMode.SideBySide => singleSpec
  case CompareMode.Overlay                      => /* overlay body, unchanged */

// 3. slotPairs guard in sideBySideSpecs (lines ~207-210) — note this line is
//    superseded by Fix C4's index-membership form; if C4's form is applied the
//    if/else disappears entirely (no separate rewrite needed). If C4 keeps a
//    per-slot predicate, use:
target.toCoordinate match
  case Some(c) if !c.collidesWith(activeBranch, activeTid) =>
    Some(ColorAssigner.pairWithColors(loadedOrEmpty(curves), visible, colors))
  case _ => None

// 4. chartNodeIds (lines ~100-102)
(querySet ++ userSet).toList match
  case Nil   => None
  case nodes => Some(nodes)
```

Left as-is (clean two-way guards): the `paired.nonEmpty`/`Idle`/`Loading`
chain (a `match` would need a synthetic boolean tuple — reads worse),
`computeSeed`'s baseline guard, `applyTreeOverride`'s `if raw.isEmpty`.

## Verification (follow-up 2)

Tests added:

- `CompareStateSpec` — `samePairAs`: true for equal branch + equal effective
  tree (both `None` override under same active tree; one pinned == the other's
  effective); false for different branch, and for same branch + different
  effective tree. `collidesWith` re-expressed through `activeTab` keeps its
  existing assertions green. `SlotCoordinate.activeTab(b).treeOverride.isEmpty`.
- `AnalyzeViewSeedSpec` — `excludedBranchValues`: empty across branches; the
  other slot's branch excluded when its effective tree equals this slot's
  would-be effective tree; not excluded when trees differ.
  `excludedTreeOverrideValues`: `""` excluded when another same-branch slot
  follows the active tree; a pinned tree value excluded when another
  same-branch slot pins the same tree; nothing excluded across branches.
  `engagedSlots`: a second slot whose pair duplicates the first's is dropped
  (earlier wins); a same-branch slot on a different tree is kept; a
  tab-colliding slot is dropped.

Commands that must be green (pass/fail only): `sbt app/compile` (zero new
warnings), `sbt app/test`.

Manual: with slot 1 = (branch B, pinned tree T) and slot 2 = (branch B,
follow-active), browse the active tab to tree T — exactly one B series /
panel / card shows (not two); browse away — slot 2 re-engages (its picker was
never reset).

---

# Follow-up 3: review dispositions — Finding 1 (Option A), F4, F5, F3 (2026-07-26)

The Fable correctness review of Follow-up 2 returned no MUST-FIX. Rulings
(user 2026-07-26): Finding 1 → **Option A** (kill the bug class); Finding 2
(re-fetch of a disengaged duplicate) → **accept as-is, no change**; F4, F5,
F3 → **fix now**. Implemented by Opus in the main session.

Two files enter the File inventory for this follow-up: `FormInputs.scala`
and `BranchBar.scala` (added to the inventory below).

## Fix F1A — branch select uses per-option `disabled`, not option removal (Finding 1, Option A)

### Problem

The compare branch `<select>` renders its options through
`FormInputs.splitOptions(branchOptionEntries(..., excludeValues))`, where
`branchOptionEntries` *removes* excluded values from the list. `splitOptions`
keys options by value via `.split`, so a removed value's `<option>` node is
torn down — and when that value is the one the controlled `value` points at,
the native `<select>` falls back to the placeholder while the slot stays
engaged. During a duplicate-pair collision each slot's own branch value is in
its exclusion set, so both pickers blank out. The tree `<select>` already
avoids this by rendering all options and marking excluded ones `disabled`.

### Design

Lift the fix into the shared helper so every dynamic-option `<select>` gets it
by construction: `splitOptions` gains an optional `disabledValues` signal and
renders `disabled` per option (keying preserved — options are never removed).
The branch select then offers the full list and disables the colliding values
instead of removing them. `branchOptionEntries`' now-dead `excludeValues`
parameter is removed (its `@param` scaladoc is the F3 stale comment — removed
with it).

### Signatures

```scala
// modules/app/src/main/scala/app/components/FormInputs.scala — splitOptions
// gains a defaulted disabledValues signal; the two existing callers
// (BranchBar.picker, parentSelect) are unaffected by the default:
def splitOptions(
  options: Signal[List[(String, String)]],
  disabledValues: Signal[Set[String]] = Val(Set.empty)
): Modifier[HtmlElement] =
  children <-- options.split(_._1) { (key, initial, _) =>
    option(value := key, disabled <-- disabledValues.map(_.contains(key)), initial._2)
  }
```

```scala
// modules/app/src/main/scala/app/components/BranchBar.scala — the excludeValues
// parameter (and its @param scaladoc — the F3 stale comment) is REMOVED; the
// body no longer filters. Both remaining call sites already omit the argument.
def branchOptionEntries(
  scenarios: Signal[List[ScenarioSummaryResponse]]
): Signal[List[(String, String)]] =
  scenarios.map { list =>
    (mainSentinel -> "main") :: list.map(s => s.name.value.toString -> s.name.value.toString)
  }
```

```scala
// modules/app/src/main/scala/app/views/AnalyzeView.scala — renderBranchPicker:
// the exclusion feeds a disabled-set signal, not an option-removal set; the
// option list is the full always-offered list.
val optionEntries: Signal[List[(String, String)]] =
  BranchBar.branchOptionEntries(scenarioState.lastLoadedScenarios)

val disabledBranchValues: Signal[Set[String]] = activeTreeId
  .combineWith(slot.state.target.signal, Signal.combineSeq(otherSlots.map(_.target.signal)))
  .map { (activeTid, own, otherTargets) =>
    excludedBranchValues(
      own.toCoordinate.flatMap(_.treeOverride),
      otherTargets.flatMap(_.toCoordinate).toVector,
      activeTid
    )
  }

// in the branch <select>: the placeholder option stays static; the reactive
// list becomes:
option(value := "", "— compare against —"),
FormInputs.splitOptions(optionEntries, disabledBranchValues),
```

The `renderBranchPicker` scaladoc is updated: a colliding branch is shown
**disabled (greyed), not hidden** — the picker always displays the slot's
chosen branch, and a value that would duplicate another slot is present but
not selectable.

`excludedBranchValues` itself is unchanged (signature and body) — only its
rendering consumer changes. No new pure-function test; the change is DOM
behaviour, verified manually like the tree select's `disabled` is today.

## Fix F4 — `excludedTreeOverrideValues` parse-guard (review Finding 4)

An unparseable non-empty override value currently maps to `None`, i.e. is
evaluated as "follow-active", so it would be excluded exactly when
follow-active is taken. Treat parse failure as not-excludable (no candidate →
excludes nothing). Signature unchanged; body only.

```scala
// modules/app/src/main/scala/app/views/AnalyzeView.scala
private[views] def excludedTreeOverrideValues(
  ownBranch: BranchChoice,
  overrideValues: List[String],
  otherCoords: Vector[SlotCoordinate],
  activeTid: Option[TreeId]
): Set[String] =
  overrideValues.filter { v =>
    val candidate = v match
      case "" => Some(SlotCoordinate(ownBranch, None))
      case id => TreeId.fromString(id).toOption.map(t => SlotCoordinate(ownBranch, Some(t)))
    candidate.exists(cand => otherCoords.exists(c => cand.samePairAs(c, activeTid)))
  }.toSet
```

## Fix F5 — test suite label (review Finding 5)

`AnalyzeViewSeedSpec` covers `computeSeed`, `engagedSlots`, and both exclusion
functions; its suite label still reads `"AnalyzeView.computeSeed"`. Rename the
label to `"AnalyzeView pure helpers"`. Label text only — no assertion changed.

## Verification (follow-up 3)

Tests:

- `AnalyzeViewSeedSpec`: add one `excludedTreeOverrideValues` case — an
  unparseable override value is not excluded even when a same-branch slot
  follows the active tree. Existing cases unchanged; suite label renamed.

Commands (pass/fail only): `sbt app/compile` (zero new warnings),
`sbt app/test`.

Manual: reproduce Finding 1's collision (two slots resolving to the same
pair) — both branch pickers keep showing their chosen branch, with the
colliding value greyed/disabled in the dropdown, never falling back to the
placeholder; browsing the collision away leaves both pickers on their
selections.

---

# Follow-up 4: deferred Finding 5 — pinned-tree recovery (2026-07-26)

The Task B complex review's Finding 5 was deferred until the 2C/3A/4B fixes
landed (3A widens how often a pinned tree can go invalid). Those, plus
Follow-up 2 and 3, have landed, so the precondition is met. User ruling
(2026-07-26): the **convergent form** (reuse R1's authoritative-list reset
mechanism, do NOT key on the fetch error) with disposition **(a) — clear the
whole slot**.

Only `AnalyzeView.scala` is touched (already in the File inventory). No
wire/API change, no new dependency, no open decisions.

## Problem

A slot pinned to a tree (`treeOverride = Some(t)`) can end up pointing at a
tree that does not exist on its branch — the slot's branch was re-pointed
while the picker kept the override, or the tree was deleted. The slot's fetch
then dead-ends in `Failed("Tree not found")` with no recovery, unlike the
active view's handler.

## Design — one principle, reused mechanism

Resets fire on authoritative state changes (list membership), never on fetch
outcomes; browse-induced mismatches disengage reversibly (variant-ii). This
adds a third subscription in the exact family as R1's two invalidation
subscriptions (collision reset, deletion reset), placed immediately after the
deletion reset. It watches the slot's own branch tree list
(`slot.treeViewState.availableTrees`), acts only on `Loaded`, and clears the
slot only when it holds a **pinned** override absent from that list.

Keying on the fetch `Failed` is explicitly rejected: `Failed("Tree not
found")` also arises for a follow-active slot when the active tab browses to a
tree missing on the slot's branch — a reversible browse that variant-ii
protects. Restricting the trigger to a pinned override absent from the slot
branch's loaded list excludes that case by construction (a follow-active slot
has no override, and its `availableTrees` is its own branch's list, unaffected
by active-tab browsing).

## Signature

```scala
// modules/app/src/main/scala/app/views/AnalyzeView.scala — new subscription,
// placed directly after the scenario-deletion reset (after the block ending
// at the current line 324), in the same compareSlots.map family:
compareSlots.map { slot =>
  slot.treeViewState.availableTrees.changes --> { list =>
    slot.state.target.now() match
      case CompareTarget.Target(coord) =>
        val pinnedGone = coord.treeOverride match
          case Some(tid) =>
            list match
              case LoadState.Loaded(trees) => !trees.exists(_.id == tid)
              case _                       => false
          case None => false
        if pinnedGone then slot.state.target.set(CompareTarget.NotChosen)
      case CompareTarget.NotChosen => ()
  }
},
```

`availableTrees` is `Signal[LoadState[List[SimulationResponse]]]`; each element
has `.id: TreeId`, so `!trees.exists(_.id == tid)` is the pinned-absent test.
The mechanism, the `Loaded`-only guard, and the `now()` self-read match the
deletion reset exactly (ADR-019 Pattern 6).

## Consequences (accepted, disposition (a))

- Re-pointing a slot's branch while a pinned override is kept, onto a branch
  without that tree, clears the whole slot (branch and override) back to
  "— compare against —". The just-picked branch is cleared with it; a rare,
  immediately visible, one-click-recoverable reset. This is the ruled
  Option B/(a): one uniform rule for both permanent causes (branch re-point,
  tree deletion), no silent coordinate self-mutation.
- A follow-active slot is never cleared by this rule; a missing active-tab
  tree disengages it reversibly (unchanged from today).
- Recovery on a true tree deletion lands when the slot's `availableTrees`
  next reports `Loaded` without the tree — the same list-refresh dependency
  the scenario-deletion reset already has; not a new limitation.

## ADR alignment

- **ADR-019** — compliant. New reset is a Pattern 6 correction (reacts to an
  external signal, reads its own value via `now()`, never reacts to the Var it
  corrects), identical in shape to the two existing invalidation
  subscriptions. No `.now()` in a rendering pipeline.

## Verification

No unit test added: the change is a reactive subscription (no pure function to
isolate), consistent with R1's two resets, which are likewise verified
manually rather than as units. Extracting a predicate purely to test it would
diverge from the deletion-reset twin it mirrors.

Commands (pass/fail only): `sbt app/compile` (zero new warnings),
`sbt app/test`.

Manual: (a) pin a slot to tree T on branch B, then delete T on B (Design) —
the slot clears to the placeholder once B's tree list refreshes; (b) pin a
slot to T on B, re-point the slot's branch to a branch without T — the slot
clears; (c) a follow-active slot on branch B, active tab browses to a tree
missing on B — the slot disengages (no card/curves) but is NOT cleared, and
re-engages when the active tree moves back.
