# PLAN — Milestone-2b UI (scenarios, comparison, merge, history)

> **Status: APPROVED 2026-07-19 (conceptual, per the fidelity boundary
> below) — no code yet.** This document closed **DD-9** (frontend UI
> placement; confirmed 2026-07-19, see milestone doc Closed table). It covers all milestone-2b UI surfaces across Phases B–E so
> placement is decided coherently once, phase-labelled for delivery.
> Companion: `docs/scratch/milestone-2b-cache-and-decisions.md` (decisions
> DD-5/7/8/9/11 and phase outline). Rendered mockups (open locally in a
> browser): v1 (neutral) and v2 (`--scenario` role token) are **superseded
> by the 2026-07-19 neutral-semantics decision (§0)** and kept as record;
> current candidate is `ui-milestone-2b-mockup-v5.html` — a live
> three-state compare control (Off / Overlay / Side by side) with
> user-assigned palettes (§1.1); `-v3.html`/`-v4.html` show the two
> layouts in isolation. **v5 approved 2026-07-19** (conceptual, per the
> fidelity boundary below). **DD-9 confirmed and closed 2026-07-19.**
>
> **Fidelity boundary (decided 2026-07-19):** the mockups are normative for
> **placement and interaction concepts only** — never for visual detail.
> Detail design (spacing, label positions, control styling) happens in
> Laminar against the real design system, where the existing good design
> wins by default. Conceptual approval of a mockup is NOT approval of its
> pixels; known mock defects are listed in §6 and are explicitly not
> design decisions. Mock iteration stops at this fidelity.

---

## 0. Scenario semantics — user-defined, not prescribed (decided 2026-07-19)

**A scenario has no pre-specified meaning.** Like a Jira ticket, the feature
carries no hard-coded interpretation: the mechanisms (branch, fork, compare,
merge, history) are provided, and what a scenario *is* — a transient what-if
line, a safe workspace forked from history, a standing alternative view, a
with/without-mitigation variant, an alternative-mitigation variant — is the
user's choice. All such use cases are supported implicitly.

UI consequence: **the UI must not communicate a preferred interpretation.**
No "default vs non-default state" signalling, no built-in role colour for
scenarios, no visual subordination of scenarios to `main`. `main` remains
*structurally* privileged (absent header = main, DD-8; merge target,
Phase D; namespace root, DD-5) but not *visually* privileged. This
supersedes the earlier `--scenario` role-token proposal (mockups v1/v2,
kept as record).

## 1. Design principles (agreed 2026-07-19)

1. **Design mutates, Analyze observes.** Scenario creation and editing live in
   the Design view; comparison and history browsing live in the Analyze view.
2. **The branch indicator is global; branch management is local to Design.**
   Both views must always show which branch the tab is on — otherwise Analyze
   silently shows scenario figures that look like main. The indicator (a
   "branch chip") sits in the shared topbar next to the workspace badge.
   Create / switch / duplicate / delete / merge affordances concentrate in
   Design. ("Rename" is not a server operation — DD-5 amendment,
   2026-07-20 — it is the user doing duplicate then delete as two
   separate actions; see §4.2.)
3. **Time travel splits by intent.** *Browsing* history and point-in-time
   comparison is read-only → Analyze. *Working from* an old state is a write
   intent → served by **fork-from-history** (see rule 4), keeping revert as
   the rare, explicitly destructive Design action.
4. **One sanctioned write affordance in Analyze: "Create scenario from
   here"** on a history commit. The protected invariant is *Analyze never
   mutates existing state* — forking mutates nothing observable; it creates a
   new line of work and hands you to Design on the new branch. This kills the
   only alternative (a detached read-only Design mode pinned at a commit,
   an entire mode existing to host one button).
5. **Per-tab branch state** (DD-8): the active branch is client state sent as
   `X-Active-Branch` per request; two tabs on different branches never
   interfere. No server-side "current branch" exists.

### 1.1 Colour language (bound to app.css tokens — no new hues)

The existing token roles (verified in `modules/app/styles/app.css`):
`--accent #b8bbbc` (neutral) is the only highlight/active colour; `--success`
green and `--error` red are status-only; `--info` aqua is used in exactly one
place (the workspace-expired banner, "aqua, not alarming"); the curve
palettes (aqua/yellow/…) are chart-line colours only. This plan adds no new
roles:

- All interactive/selected states (dropdown selection, active toggles,
  primary buttons, selected commit) → neutral `--accent`, as everywhere else.
- **Branch colours are user-assigned, not role-assigned (decided
  2026-07-19, follows §0).** Each branch — including `main` — can be given
  one of the nine existing curve palettes (`--curve-palette-yellow|green|
  red|purple|pink|orange|aqua|emerald|neutral`, app.css) by the user via a
  colour picker. Verified feasible: `ColorSwatchPicker.scala` already
  renders the 8×8 palette/shade grid for per-node curve overrides
  (ADR-019 Pattern 4); per-branch assignment is the same mechanism at
  branch granularity, and the palettes are already exposed as
  JS-consumable tokens. Storage of the assignment is an implementation
  decision for Phase B/C (client-side per workspace is sufficient; nothing
  durable references a scenario server-side, DD-5).
  **Built 2026-07-24** (`BranchPaletteState` + `BranchPalettePicker`):
  assignment is at family granularity (the 8 chart families — per-node
  shades stay hash-rotated within the family), stored in `localStorage`
  keyed by branch name (not workspace-scoped: the workspace's only
  client-side identifier is its secret key, which must not be written to
  storage). The picker opens from the branch-card header swatches in
  Analyze's compare mode; the topbar chip carries the assigned swatch. An
  unassigned branch keeps its surface default (Aqua active, Purple/Orange
  compare slots), so nothing changes until the user assigns.
- The **branch chip** is neutral chrome (same treatment as other topbar
  badges) carrying the branch's assigned palette swatch and name. The
  earlier `--scenario` role token is **withdrawn** — no new colour role is
  added to the design system.
- **Comparison and overlay curves render in each branch's assigned
  palette** — the assignment is exactly the branch's chart identity, which
  is what makes multi-branch overlays readable. Diff markers reuse
  `--success`/`--error` semantics only where they mean added/conflict.

---

## 2. Current shell (verified against source, 2026-07-19)

```
┌──────────┬──────────────────────────────────────────────────────────┐
│ Sidebar  │ Topbar:  [Section title]              (health●) [ws-badge]│
│  Design  ├──────────────────────────────────────────────────────────┤
│  Analyze │ ErrorBanner (when present)                               │
│          ├──────────────────────────────────────────────────────────┤
│          │ Routed content: DesignView | AnalyzeView                 │
└──────────┴──────────────────────────────────────────────────────────┘
```

- `AppShell.scala` — Sidebar | (topbar + ErrorBanner + routed content).
  Topbar right side: health dot + workspace badge. The branch chip slots here.
- `DesignView.scala` — SplitPane.horizontal(40|60):
  left `TreeBuilderView` (forms); right vertical split: `TreeListView` +
  `TreePreview` (top), `DistributionChartView` (bottom).
- `AnalyzeView.scala` — SplitPane.horizontal(75|25):
  left query panel + `QueryResultCard` + `LECChartView`; right
  `TreeListView` + `TreeDetailView`.
- `Sidebar.scala` — two nav items: Design, Analyze.

No third sidebar section is added by this plan: scenarios, comparison and
history are modes/panels of the two existing sections, per principle 1.

---

## 3. Surface inventory and placement

| Surface | Phase | Placement | Notes |
|---|---|---|---|
| Branch chip (indicator) | B | Topbar, left of workspace badge | Always visible, both views. Neutral chrome + the branch's user-assigned palette swatch (§1.1) — no role colour. |
| Scenario menu (switch, create, duplicate, delete) | B | Design — chip becomes a button there; also a "Scenarios" toolbar row atop TreeBuilder | In Analyze the chip is inert (indicator only). No bundled rename (DD-5 amendment, 2026-07-20) — duplicate then delete is two explicit actions. |
| Feature-disabled state | B | Topbar + Design toolbar | Both grayed and removed variants sketched (§5); decision deliberately after DD-9. |
| Comparison view | C | Analyze — three-state compare control (Off / Overlay / Side by side) in the query panel header | Branch multi-select; N-way limits per layout in §6; edit markers in tree panel (no diff list). |
| Merge preview + confirm | D | Design — action on the active scenario in the Scenario menu | Modal preview → confirm; conflicts surface in the modal. |
| History panel | E | Analyze — right panel gains a "History" tab next to the tree detail | Commit list; per-commit: view point-in-time, compare, **fork**. |
| Revert | E | Design — Scenario menu, destructive section | Explicit confirm naming the branch and target commit. |

---

## 4. Phase B — BranchBar + scenario CRUD

### 4.1 Topbar with branch chip (both views)

```
┌─────────────────────────────────────────────────────────────────────┐
│ Design                          (●) [⎇ stress-2026] [WS: A4T…KQ]    │
└─────────────────────────────────────────────────────────────────────┘
   on main the chip reads [⎇ main] in muted style — never absent
```

### 4.2 Design view with Scenario toolbar

```
┌──────────┬──────────────────────────────────────────────────────────┐
│ Sidebar  │ Design                    (●) [⎇ stress-2026] [WS badge] │
│          ├──────────────────────────────────────────────────────────┤
│ ▸ Design │ Scenarios: [⎇ stress-2026 ▾] [+ New scenario]            │
│  Analyze │ ┌───────────────────┬────────────────────────────────┐   │
│          │ │ TreeBuilderView   │ TreeListView + TreePreview     │   │
│          │ │ (forms, 40%)      │ (60% top)                      │   │
│          │ │                   ├────────────────────────────────┤   │
│          │ │                   │ DistributionChartView (bottom) │   │
│          │ └───────────────────┴────────────────────────────────┘   │
└──────────┴──────────────────────────────────────────────────────────┘
```

Scenario dropdown (opened):

```
 [⎇ stress-2026 ▾]
 ┌─────────────────────────────┐
 │ ⎇ main                      │   switch (per-tab)
 │ ⎇ stress-2026        ✓      │
 │ ⎇ new-vendor-risk           │
 ├─────────────────────────────┤
 │ + New scenario from main…   │   CAS create at main head (DD-5)
 │ ⧉ Duplicate current…        │   CAS create at this branch's head (DD-5)
 │ ⇪ Merge into main…          │   Phase D — hidden until then
 │ ↩ Revert this branch…       │   Phase E — hidden until then
 │ ✕ Delete current…           │   destructive confirm
 └─────────────────────────────┘
```

- Create prompts for a `ScenarioName` (slug-mappable charset, 400 at boundary
  otherwise — DD-5); collision rejected by the CAS itself, surfaced inline.
- Delete on `main` is disabled — main is not a scenario. "Duplicate current"
  on `main` is redundant with "New scenario from main" (same operation, same
  source head) and is hidden there rather than shown twice.
- Switching with a dirty draft reuses the existing dirty-draft confirm flow in
  `DesignView` (same guard as switching trees).
- **Backend note (2026-07-21):** `POST /w/{key}/risk-trees` already accepts
  `X-Active-Branch`, so a tree can be created directly on a scenario branch.
  `listWorkspaceTreesEndpoint` (and therefore `TreeListView`) still reads
  trees from `main` only — a tree created this way is invisible in the tree
  list until this BranchBar item wires branch-aware listing through.
  Workspace-level bookkeeping (reaper cascade-delete) already covers it
  regardless of branch; only the UI-facing listing is the current gap.

### 4.3 Analyze view in Phase B

Unchanged except the topbar chip. Reads simply go to the tab's active branch
via `X-Active-Branch`. No management affordances.

---

## 5. Phase B — feature-disabled state (kill-switch / in-memory backend)

DD-9 scope extension: both variants sketched so the grayed-vs-removed
decision (deliberately sequenced after DD-9) has a design to decide against.

**Variant G — grayed:**

```
Topbar:   (●) [⎇ scenarios unavailable]  [WS badge]      ← muted, tooltip:
Design:   Scenarios: [⎇ — ▾ disabled] [+ New scenario]     "Scenario features
          (controls visible, disabled, tooltip on hover)    require the Irmin
                                                            backend"
```

**Variant R — removed:**

```
Topbar:   (●) [WS badge]                    ← exactly today's topbar
Design:   no Scenarios toolbar row          ← exactly today's DesignView
```

**Decided 2026-07-19 (user):** **Variant R — removed — is the preference**:
scenario-specific UI elements are not shown at all when the backend is
in-memory / the kill-switch is off. Graying out (Variant G) is a **fallback
only**, admissible per element when not showing it would be a major
engineering effort or would produce bad design (e.g. a layout that
collapses without the element). No such element is currently identified;
implementation starts from full removal. Server side already logs
unavailability at startup (kill-switch item, minimum bar).

---

## 6. Phase C — comparison in Analyze

Compare is a mode of the Analyze view, toggled in the query-panel header.
It adds a branch **multi-select** (any subset of branches, `main`
included — no fixed baseline/comparand asymmetry, per §0). Trivial
switching between scenarios stays with per-tab branch state (one tab per
branch, DD-8); compare mode is for seeing branches together.

**Edits vs outcomes (decided 2026-07-19):** the hash diff (UC5) shows
*input edits* — what the user changed between two branches — not which
curves differ (in a with/without-mitigation comparison every curve
differs while the edit is one node). A separate diff list under the chart
conflated the two and read as clutter; it is **removed**. The edit
information surfaces only as ✎ markers on edited nodes in each compared
branch's own tree card, always diffed against the tab's **active**
branch (decided 2026-07-23, built in the branch-cards slice: N−1 cheap
hash-diff calls, no explicit pair selector — supersedes the earlier
"pair chosen explicitly" plan for 3+ branches).

**Current proposal (mockup v5): a three-state compare control —
Off / Overlay / Side by side** (v3/v4 show the two layouts in isolation
and are kept as record):

- **Off:** today's single chart on the tab's active branch.
- **Overlay:** one chart, all selected branches' curves, each branch in
  its user-assigned palette.
- **Side by side:** one self-contained chart per branch, tiled on
  **shared, pinned axes** (per-chart autoscaling would silently defeat
  the comparison).

**N-way (3+ branches) analysis — possible in both, reasonable within
different limits:**

- *Overlay:* possible for any N; colour becomes **branch** identity, so
  per-node distinction inside one branch must move to shades within that
  branch's palette (the palettes are 13-stop arrays — this is exactly what
  they support). Readability bounds it at roughly 3–4 branches × 1–3
  selected nodes; beyond that, curves are no longer attributable at a
  glance. Palette count (9) is the hard ceiling.
- *Side by side:* possible for any N; branch identity is the panel, so
  **node colours keep their normal single-branch meaning inside each
  panel** — the existing chart colour system survives unchanged, which is
  the tiles' structural advantage. Layout is a dynamic auto-fit grid:
  panels keep a minimum readable width and overflow to further rows
  automatically, so N is bounded by vertical scroll tolerance, not row
  width. The compare control renders as a three-position slider (same
  visual family as the two-state switch) with plain-text state labels
  beside the track.
- Cross-branch cache reuse (UC6) keeps every added branch cheap in both
  layouts.

Known v5 mock defects (recorded, not to be copied into Laminar): the
"Query" label sits detached from its field; the three state labels should
sit **below** the slider track, not beside it.

**Node selection in compare mode (requirement, 2026-07-19):** the user
must be able to select nodes from *each* compared branch exactly as on a
single branch (Ctrl+click in the tree). Proposed design:

- The right panel stacks **one collapsible tree section per selected
  branch** (accordion), each header carrying the branch's swatch + name.
  Trees collapse independently; branches may structurally diverge (added/
  removed nodes), which stacked trees represent honestly and a combined
  node×branch matrix would not.
- **Entry seeding (decided 2026-07-23, supersedes the earlier "default
  root selection" bullet; built):** when a branch enters the comparison
  (target chosen or changed, tree switched, refresh), its card is seeded
  with the counterparts of the **baseline** — the active card's charted
  set (query ∪ manual); an empty baseline falls back to the active tree's
  root, which becomes a real, persistent selection on the active card.
  Seeding is one-shot: after entry both cards are fully manual, later
  baseline changes do not propagate, and a plain Compare off/on toggle
  preserves the card's selection (deliberate removals included — nothing
  is forced back, and compare mode still never opens empty). Compare-card
  picks deliberately do not migrate when the target switches; a branch
  leaving the comparison drops all its curves.
- Selection identity is the pair **(branch, node)** — the same node ULID
  exists on several branches, so chart legends and tooltips always name
  both ("stress-2026 · cyber-outage"), and a node's curve renders in a
  shade of its branch's palette.
- Interaction is byte-identical to single-branch selection (Ctrl+click,
  same highlighting) — no new gesture is introduced.
- **Mirror selection (decided 2026-07-19): a dedicated gesture,
  Ctrl+Alt+click.** Ctrl+Alt+click on a node selects/deselects it in
  every compared branch where it exists; plain **Ctrl+click stays strictly
  tree-local** (identical to single-branch behaviour). Serves the dominant
  "same node across scenarios" case in one gesture without a matrix UI,
  and without overloading the existing selection semantics.
- Curve budget: default root-only keeps N curves for N branches; every
  Ctrl+Alt-mirrored node adds up to N more. The §6 overlay readability limits apply; the
  tiles layout tolerates larger selections since each panel only shows
  its own branch's curves.

**Addendum (2026-07-22, user input — decided 2026-07-23 as D2 = Option B+
and built):** the above right-panel design stacked all branches' trees as
collapsible sections within **one** panel (an accordion). The decided and
implemented design instead gives each compared branch its **own separate,
self-contained visual element** (`BranchCard`): a bordered container with
swatch + branch name in the header and a per-card collapsible body, each
acting as an independent tree view and independent Ctrl+click input
surface backed by its own `TreeViewState` instance. Selection identity is
the pair (branch, node); the ✎ markers sit on each compared branch's card,
diffed against the tab's active branch (see above). Built for 2 branches
in the branch-cards slice; N-way built 2026-07-24 as stable picker slots
(active branch + 2 compared slots, cap = one constant
`CompareState.MaxBranches`; per-slot `TreeViewState`/diff/palette —
Purple, Orange; the multi-select renders as one always-mounted `<select>`
per slot with mutual exclusion of the active branch and the other slot's
choice). Mirror-select remains a follow-on item.

```
┌──────────┬──────────────────────────────────────────────────────────┐
│ Sidebar  │ Analyze                    (●) [⎇ main] [WS badge]       │
│          ├──────────────────────────────────────────────────────────┤
│  Design  │ ┌ analyze-left (75%) ──────────────┬─ saved-tree (25%) ─┐│
│ ▸ Analyze│ │ Query  [Compare: ON]             │ [Tree ▾]           ││
│          │ │  [●main ✓][●stress-2026 ✓][●…]   │ TreeDetailView     ││
│          │ │ ┌ Query textarea ─────────┐ Run  │  nodes changed in  ││
│          │ │ └─────────────────────────┘      │  compared branch   ││
│          │ │ QueryResultCard                  │  marked with ⎇     ││
│          │ │ ┌ LEC chart ──────────────────┐  │                    ││
│          │ │ │  ── main        (green ●)   │  │                    ││
│          │ │ │  ── stress-2026 (purple ●)  │  │                    ││
│          │ │ └─────────────────────────────┘  │                    ││
│          │ └──────────────────────────────────┴────────────────────┘│
└──────────┴──────────────────────────────────────────────────────────┘
```

- Curves render in each branch's user-assigned palette (§1.1) — branch
  identity in the chart is the user's own colour choice, not a role.
- ✎ edit markers in TreeDetailView reveal edited nodes (reuses the query
  auto-expand plumbing); no diff list under the chart.
- Cross-branch cache reuse (UC6) makes the second curve cheap when subtrees
  are unchanged — no UI consequence, just expected snappiness.

---

## 7. Phase E — history in Analyze + the fork bridge

The right panel of Analyze gains tabs: **Tree | History**. History shows the
commit log of the active branch for the selected tree (commit messages carry
`workspace:{ws}:risk-tree:{id}:create|update|delete` — DD-7 — so the list is
per-tree filterable and human-readable).

```
┌ saved-tree-panel (25%) ────────────┐
│ [Tree] [History]                   │
│ ┌────────────────────────────────┐ │
│ │ ● 2026-07-19 14:02  update     │ │  ← head
│ │ ○ 2026-07-18 09:31  update     │ │
│ │ ○ 2026-07-15 16:20  create     │ │
│ └────────────────────────────────┘ │
│ selected commit ○ 2026-07-18 …     │
│  [👁 View at this point]           │  read-only point-in-time load
│  [⇄ Compare to current]            │  reuses §6 comparison, comparand
│  [⎇ Create scenario from here]     │  = commit instead of branch head
└────────────────────────────────────┘
```

**Fork bridge (principle 4):** "Create scenario from here" prompts for a
`ScenarioName`, creates the branch at that commit (same CAS create as DD-5,
pointed at a historical hash instead of main's head), switches the tab's
active branch to it, and navigates to Design. Editing an old state is thereby
always editing a scenario — no detached Design mode, no accidental rewriting
of main.

**"View at this point"** loads the tree read-only into the Analyze panels
(chart + detail); a banner above the tree detail marks the state:
`Viewing 2026-07-18 09:31 — [back to current] [⎇ fork]`. No edit affordance
appears anywhere in this state — it lives entirely inside Analyze.

**Revert** stays in Design (Scenario menu, destructive section): moves the
*current branch* back to a chosen commit, with a confirm naming branch and
target. Fork-from-history serves the safe majority of "work from old state";
revert remains for genuinely rewinding a branch.

---

## 8. Phase D — merge in Design

Merge is a mutation of main → it lives in Design, on the Scenario menu of the
active scenario ("Merge into main…").

```
┌ Merge stress-2026 → main ───────────────────────────┐
│ 3 nodes changed, 1 added, 0 removed                 │
│ ┌─────────────────────────────────────────────────┐ │
│ │ ~ cyber-outage      p: 0.02 → 0.05              │ │
│ │ ~ vendor-lock       loss σ: 1.2M → 2.0M         │ │
│ │ + new-vendor-risk   (added)                     │ │
│ └─────────────────────────────────────────────────┘ │
│ ⚠ 1 conflict: ops-risk edited on both branches      │
│   [keep main] [keep scenario]                       │
│                              [Cancel]  [Merge]      │
└─────────────────────────────────────────────────────┘
```

Preview reuses the Phase C diff machinery; conflicts (both branches touched
the same node since the LCA) must be resolved in the modal before Merge
enables. Details of conflict semantics are Phase D design work — this sketch
fixes only the placement (Design, modal, preview-then-confirm).

---

## 9. Cross-view flow summary

```
 Design ──(edit under scenario)──────────────► figures update on branch
   ▲                                                    │
   │ fork bridge:                                       ▼
   │ "Create scenario from here"              Analyze: chart/query on
   └───────────────◄──────────────┐           active branch (chip shows it)
                                  │                     │
                        Analyze ▸ History tab ◄─────────┘
                        (browse, view-at-point, compare)
```

- Scenario lifecycle: create/switch/duplicate/delete/merge/revert — Design.
- Observation: query, chart, compare, history browsing — Analyze.
- The single bridge Analyze→Design is the fork action (creates, never edits).

---

## 10. Open items this document exists to close or feed

| # | Item | State |
|---|---|---|
| 1 | DD-9: BranchBar placement (topbar chip + Design toolbar) | **CONFIRMED 2026-07-19 — DD-9 CLOSED** (milestone doc Closed table updated). |
| 2 | DD-9 scope ext.: disabled-state design input | **Decided 2026-07-19**: removed (R) by preference; grayed (G) only as per-element fallback for major-effort/bad-design cases (§5). |
| 3 | Comparison placement in Analyze | **APPROVED 2026-07-19 (v5, conceptual per fidelity boundary)**: three-state compare control (Off / Overlay / Side by side), branch multi-select, auto-fit tile grid, stacked per-branch selection trees, Ctrl+Alt+click mirror. |
| 4 | History placement + fork bridge | **Proposed here** (Analyze right-panel tab; fork = sole Analyze write affordance). |
| 5 | Merge placement | **Proposed here** (Design, modal preview). Conflict semantics = Phase D work, not this doc. |
| 6 | SSE branch-scoping (over-notification across branches) | **CLOSED 2026-07-19 → DD-22** (milestone doc, Closed table): branch tag in event payload (absent = main, DD-8 symmetry); hub and subscription unchanged; lands in Phase B. |

Nothing in this document changes code, API shapes, or tests.
