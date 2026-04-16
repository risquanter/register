# Plan: Query Result Visualization — v3

**Status:** Draft — awaiting decisions (Option A vs Option D)  
**Scope:** Planning only — no code changes  
**Predecessor:** PLAN-QUERY-RESULT-VISUALIZATION_v2.md (implemented, committed `5776201`)  
**Date:** 2026-04-15

---

## 0 Motivation

Manual testing after v2 revealed two UX problems with the colour system:

1. **Tree highlights and chart curves use different colours for the same
   node.** A query-matched leaf gets a fixed green-300 (`#86efac`)
   border in the tree, but its LEC chart curve is assigned from a
   13-shade palette by p95 rank. With a single matched node at rank 0,
   the curve colour is green-975 (`#03170b`) — nearly invisible on the
   dark background and visually unrelated to the tree highlight.

2. **Tree highlights are static and palette-unaware.** The
   `node-query-matched` and `node-chart-selected` CSS classes apply
   fixed green-300 / aqua-300 borders unconditionally, regardless of
   whether a curve is actually drawn or which shade it received. Ctrl+
   clicking a node adds an aqua border, but the chart curve for that
   node may be a completely different aqua shade.

### Root Cause

The v1 plan designed tree highlighting and chart colouring as two
independent systems:

- **Tree:** Binary CSS classes → fixed `--query-match` / `--chart-selected`
  variables.
- **Chart:** 13-shade palettes → p95-ranked shade assignment, darkest
  first.

There is no feedback loop: the tree never learns which hex colour the
chart used, and the chart never considers the tree highlight colour.

### Goal

After this change:

- **Every node on the chart has the same highlight colour in the tree
  as its curve colour on the chart.**
- **Nodes not on the chart have no highlight** (or only faint palette
  background — see Option D).
- Both views compute colour deterministically.

---

## 0.1 ADR Compliance Review (Planning Phase)

**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011,
ADR-018, ADR-019

| ADR | Status | Notes |
|-----|--------|-------|
| ADR-001 (Iron types) | Compliant | `HexColor` remains Iron-refined. New shared palette code uses the same `HexColor` opaque type. |
| ADR-002 (Logging) | N/A | No new logging. |
| ADR-003 (Provenance) | N/A | No simulation changes. |
| ADR-009 (Identity aggregation) | N/A | No aggregation changes. |
| ADR-010 (Error handling) | Compliant | Cap overflow error continues to route via `GlobalError` → `ErrorBanner`. |
| ADR-011 (Import conventions) | Compliant | Top-level imports, no FQNs. |
| ADR-018 (Nominal wrappers) | Compliant | `NodeId` case class wrapper used for sorting. `.value` for ULID string extraction. |
| ADR-019 (Frontend architecture) | Compliant | Signals down, events up. New `nodeColorMap` signal flows from state → views. No shared mutable state. |

**Deviations detected:** None.

### Validation Checklist

- [x] Compliant with ADR-001 (Iron types)
- [x] Compliant with ADR-010 (Error handling)
- [x] Compliant with ADR-011 (Import conventions)
- [x] Compliant with ADR-018 (Nominal wrappers)
- [x] Compliant with ADR-019 (Frontend architecture)
- [ ] Code compiles
- [ ] Tests pass
- [ ] Integration verified
- [ ] User approves

### Decision Triggers Acknowledged

Per WORKING-INSTRUCTIONS.md § Decision Triggers:

- **Trigger #5 (Behavioral changes):** Colour assignment logic changes
  from p95-ranked to NodeId-sorted, shade order reverses.
- **Trigger #4 (Type changes):** `TreeDetailView` signature changes.
  Several constructor signatures change.
- **Trigger #1 (Schema/API changes):** Option D requires the `lec-chart`
  endpoint response to include `chartedNodeIds` alongside the spec.
- **Critical Stop Points:**
  - Removing `node-query-matched` / `node-chart-selected` CSS class
    application from `TreeDetailView`.
  - Changing `CurvePaletteRegistry` shade count from 13 to 8.
  - Changing shade assignment order in `ColouredCurve`.
  - Expanding `CurvePalette` enum (Option D).
  - Changing `lec-chart` endpoint return type (Option D).
  - Deleting `CurvePaletteRegistry.scala` and its test.

---

## 1 Shared Foundations (both options)

### 1.1 Palette Trimming

Current 13-shade palettes run from shade-975 (near-black) to shade-25
(near-white). On a dark background:

- **Shades 25, 50, 100** are too light — low contrast against white
  text / invisible borders.
- **Shades 975, 950** are too dark — indistinguishable from the dark
  background.

**Action:** Drop these 5 shades. Retain 8 shades per palette:

| New Index | CSS var    | Green hex | Aqua hex  | Purple hex |
|-----------|------------|-----------|-----------|------------|
| 0         | -200       | `#bbf7d0` | `#aee9f8` | `#e5ccff`  |
| 1         | -300       | `#86efac` | `#7bdaf3` | `#dab2ff`  |
| 2         | -400       | `#4ade80` | `#42c9ed` | `#c27aff`  |
| 3         | -500       | `#22c55e` | `#00b3e6` | `#ad46ff`  |
| 4         | -600       | `#16a34a` | `#0094bf` | `#9810fa`  |
| 5         | -700       | `#15803d` | `#007299` | `#7b0acd`  |
| 6         | -800       | `#145c2f` | `#005370` | `#5a1094`  |
| 7         | -900       | `#0f3e21` | `#003a52` | `#3e0f61`  |

Index 0 = lightest (most visible on dark background).
Index 7 = darkest (still distinguishable from `--surface: #060f11`).

The same trimming applies to all 8 CSS palettes (yellow, green, red,
purple, pink, orange, aqua, emerald). Neutral is excluded (UI chrome).

### 1.2 Shade Assignment Order (Dark Theme)

**Dark theme (current, only supported scheme):** First in sort order →
lightest shade (index 0). Each subsequent node gets the next darker
shade.

**Light theme (future — comment only):** Reverse: first in sort order →
darkest shade (index 7). The reversal point is a single conditional
commented with `// TODO: light theme`.

### 1.3 Deterministic Sort Key: NodeId

Both client and server sort nodes by **ULID lexicographic order** (the
`.value` string of `NodeId`). This is deterministic, stable, and
requires no simulation data. Shades are assigned in this order.

### 1.4 `PaletteAssigner` in `common`

`CurvePaletteRegistry` currently lives in `modules/server/`. It moves
to `modules/common/` as a new `PaletteAssigner` object, cross-compiled
for both JVM and Scala.js:

- 8-shade trimmed palettes per `CurvePalette` variant.
- `assign(nodeIds: Set[NodeId], palette: CurvePalette): Map[NodeId, HexColor]`
  — sorts by NodeId, zips with shades (wrapping mod 8), returns mapping.
- `maxCurvesPerPalette: Int = 8` constant.

---

# OPTION A — Flat Palette Assignment (simpler, immediate)

## A.1 Summary

Three palettes (Green, Aqua, Purple) assigned by set membership:
query-only → Green, user-only → Aqua, overlap → Purple. Each capped
at 8. Deterministic on both sides (no API change needed). Tree nodes
get highlight colour = chart curve colour.

## A.2 Cap Selection

**Cap selection for query matches >8: server picks top-8 by p95.**
**Shade assignment among the ≤8: NodeId sort (deterministic).**

This splits the concern:
- Server selects *which* nodes are charted (requires simulation data).
- Both sides assign *which shade* each gets (requires only NodeId set).

**API change required:** `lec-chart` endpoint response widens from
`String` to a DTO:

```scala
final case class LECChartResponse(
  spec: String,                    // Vega-Lite JSON
  chartedNodeIds: List[NodeId]     // nodes actually included after cap
)
```

Client intersects `chartedNodeIds` with its known palette sets, calls
`PaletteAssigner` with NodeId sort → exact same colours as server.

**User Ctrl+click (Aqua):** Capped at 8 client-side (existing guard).
No server involvement — immediate highlights.

**Overlap:** Computed *after* server cap. Client sends raw sets (all
query nodes tagged Green, all user nodes tagged Aqua). Server determines
final overlap after applying the p95 cap.

### A.2.1 LECChartRequest.build() Change

Currently computes overlap client-side. Under this option:
- Client sends all query matches as Green, all user selections as Aqua.
- Server determines overlap (Purple) after capping.
- OR: client still tags overlap, server re-evaluates after cap
  (functionally equivalent, simpler client).

### A.3 QueryResultCard — Matching Nodes List

- **≤ 8 matches:** Display all, as today.
- **> 8 matches:** Display the charted 8 (from `chartedNodeIds`), with cap text:
  `"Matching nodes (top {N} of {total} by p95, charted):"`

### A.4 Tree Highlighting

- Nodes in `nodeColorMap` → inline `borderLeftColor` + `backgroundColor`
  (using `color-mix` for surface tint).
- Nodes NOT in the map → no highlight.
- `queryMatchedNodes` / `node-query-matched` CSS classes removed.

### A.5 Option A — Open Decisions

**AD3: `HexColor` return type.**
- (a) `PaletteAssigner` returns `Map[NodeId, HexColor]` — type-safe
  (ADR-001). Client extracts `.value` at view boundary.
  **Recommended.**
- (b) Returns `Map[NodeId, String]` — simpler, loses refinement.

**AD4: Inline style application.**
- (a) Direct Laminar style properties: `borderLeftColor <-- signal`.
  **Recommended.**
- (b) CSS custom property override via inline `style` attribute.

**AD5: Surface tint derivation.**
- (a) CSS `color-mix` in inline style string. **Recommended.**
- (b) Client-side Scala colour math (hex blending).
- (c) Fixed translucent surface for all highlights.

---

# OPTION D — Subtree Palette Exploration (richer, more complex)

## D.1 Summary

A fundamentally different colour strategy designed for **drill-down
analysis**. Instead of flat Green/Aqua/Purple sets, each **maximal
matching subtree root** receives its own palette from the 8 available
CSS colour families. The user can then interactively add individual
curves from any part of the tree, drilling into subtrees progressively.

### Available palettes (8, excluding neutral)

| Slot | Palette  | Lightest (shade-200) | Darkest (shade-900) |
|------|----------|----------------------|---------------------|
| 0    | Yellow   | `#fef08a`            | `#542f0d`           |
| 1    | Green    | `#bbf7d0`            | `#0f3e21`           |
| 2    | Red      | `#fecaca`            | `#4f0c0c`           |
| 3    | Purple   | `#e5ccff`            | `#3e0f61`           |
| 4    | Pink     | `#fbcfe8`            | `#5b112e`           |
| 5    | Orange   | `#fde68a`            | `#56260b`           |
| 6    | Aqua     | `#aee9f8`            | `#003a52`           |
| 7    | Emerald  | `#a7f3d0`            | `#072e27`           |

## D.2 Behavioural Model

### State 1 — No query active

- User can Ctrl+left-click any node → adds that node's curve to the
  chart. The node gets a shade from a **user palette** (one of the 8,
  assigned deterministically).
- Ctrl+left-click again → removes the curve.

### State 2 — Query returns matching nodes

1. Server returns `satisfyingNodeIds` (leaf-level matches).
2. Client computes **maximal matching subtree roots**: walk up from each
   matching leaf; a portfolio is a maximal root if **all** its
   descendants that are leaves satisfy the query.
3. Up to 8 subtree roots are selected, **ranked by position in the
   tree** (highest / closest to root first — see D.3).
4. Each subtree root gets its own palette (slot 0–7). The subtree root
   itself is assigned the **lightest shade** from its palette.
5. Initial chart: LEC curves for the subtree roots (portfolio-level).
6. All nodes within a subtree root's descendant scope are "palette-
   tagged" with that root's palette.

### State 3 — User adds curves via interaction

**Expansion does NOT auto-add children.** The user explicitly selects
which curves to chart:

- **Ctrl+left-click on a node:** Add that single node's curve to the
  chart, using its assigned palette shade. If the node is within a
  query-matched subtree, it uses that subtree's palette. Otherwise, it
  is assigned from a user palette.
- **Ctrl+right-click on a portfolio node:** Add that portfolio's
  *immediate children* to the chart, each getting the next shade from
  the parent's palette.
- **Ctrl+left-click again** on an already-charted node → removes its
  curve.

Shades within a palette are assigned by **NodeId sort order**, wrapping
(mod 8) when a subtree has more than 8 expanded members.

### State 4 — Disabling query-matched curves

Two mechanisms needed:

- **(I) Per-curve disable:** Ctrl+left-click on a query-matched node
  that is currently charted → removes its curve (same toggle as user
  selections). The node remains palette-tagged (faint background) but
  loses the left border and chart curve.
- **(II) Bulk disable:** A toggle control (e.g. checkbox/button in the
  query panel: "Show query curves ☑") that hides/shows all query-
  matched curves at once. When toggled off, the subtree root curves
  are removed from the chart and left borders disappear; faint palette
  backgrounds may optionally remain (to show scope). Toggling back on
  re-adds the subtree root curves.

## D.3 Subtree Root Selection and Ranking

### Maximal subtree root algorithm

Given `satisfyingNodeIds: Set[NodeId]` (leaves) and `TreeIndex`:

1. For each matching leaf, walk up via `ancestorPath`.
2. A portfolio is a maximal root if `leafIds ∩ descendants(portfolio)`
   ⊆ `satisfyingNodeIds` — i.e. all leaves under it match.
3. Take the highest such portfolio (closest to tree root) for each
   matching subtree.
4. Deduplicate (a subtree root's ancestors are not additional roots).

### Ranking: position in tree (top-down, left-right)

**When >8 subtree roots exist:** Select the first 8 ranked by their
position in the tree structure. "Higher" means closer to the root.
Among siblings at the same depth, use left-to-right order (i.e. the
order children appear in `TreeIndex.children`).

Implementation: BFS from root, collecting subtree roots in visit order,
take first 8.

## D.4 Tree Highlighting

### Palette background (scope indicator)

Every node within a subtree root's scope gets a **faint tinted
background** in that subtree root's palette colour. This shows "this
node belongs to the Green palette subtree" even before any curves are
added.

### Left border (active curve indicator)

A node gets a **visible left border** in its palette shade if and only
if its curve is currently rendered on the chart. This is the exact hex
colour used for the Vega-Lite curve.

### Visual states per node

| State | Background | Left border |
|---|---|---|
| No palette (not in any subtree, not user-selected) | None | None |
| Palette-tagged, no curve | Faint tint (12% opacity) | None |
| Palette-tagged, curve active | Faint tint (12% opacity) | Solid shade colour |
| User-selected, curve active | Faint tint (12% opacity) | Solid shade colour |

**Open decision DD3 (Da vs Db):** See §D.7.

## D.5 API Changes Required

### `lec-chart` endpoint response

Must widen from `String` to a DTO to communicate which nodes the server
actually charted (after p95-based cap selection):

```scala
final case class LECChartResponse(
  spec: String,
  chartedNodeIds: List[NodeId]
)
```

### `CurvePalette` enum expansion

Currently 3 variants (Green, Aqua, Purple). Must expand to all 8 CSS
palette families:

```scala
enum CurvePalette:
  case Yellow, Green, Red, Purple, Pink, Orange, Aqua, Emerald
```

### `LECChartRequest` semantics

Client sends palette-tagged nodes. The overlap logic (Green/Aqua →
Purple) from Option A does not apply in Option D — each node has
exactly one palette based on its subtree root.

## D.6 Determinism Analysis

**Shade assignment within a palette:** Deterministic — NodeId sort,
same on both sides.

**Palette assignment to subtree roots:** Deterministic — the maximal
subtree root algorithm + BFS ranking uses only `TreeIndex` + 
`satisfyingNodeIds`, both available on client.

**Cap selection (which subtree roots when >8):** Deterministic — BFS
from root, take first 8. No server data needed.

**Therefore:** Client and server can independently compute the same
`NodeId → (palette, shade)` mapping. **No API change is strictly
required for determinism.** However, the `chartedNodeIds` response
field is still useful for the QueryResultCard display and for future
p95-based cap selection if needed.

## D.7 Option D — Open Decisions

**DD1: Ctrl+click interaction with palette-tagged subtree nodes.**
When a query-matched subtree root (e.g. B, palette Green) is on the
chart, and the user Ctrl+left-clicks B1 (a child of B):
- (a) B1 gets a shade from B's Green palette (stays in family).
  **Recommended.**
- (b) B1 gets a shade from a user palette (treated as independent
  user selection).

**DD2: Partial subtree matches.**
If only B1 and B2 match but not B3, then B is NOT a maximal root
(not all leaves under B satisfy). The matching nodes B1, B2 are treated
as individual matching leaves. They do NOT form a subtree.
- (a) B1 and B2 each get their own palette (as if they were independent
  roots). Inefficient use of palette slots.
- (b) B1 and B2 share a palette (grouped by common ancestor B, even
  though B is not a maximal root). More efficient, but adds a
  "nearest common ancestor" grouping step.
  **Recommended: (b)** — group by nearest common ancestor.

**DD3: Da vs Db — default background visibility.**
- **(Da) Background always visible** for palette-tagged nodes.
  Left border appears only when curve is active. Provides a visual
  map of palette ownership before expansion.
  **Recommended.**
- **(Db) Background and border both invisible by default.** Both
  toggled on together only when curve is active. Cleaner, but user
  cannot see palette scope until drilling down.

**DD4: Bulk query toggle — scope of "off".**
When the "Show query curves" toggle is off:
- (a) Remove subtree root curves + hide all palette backgrounds.
  Tree returns to un-highlighted state. Clean.
- (b) Remove subtree root curves + hide left borders, but keep
  faint palette backgrounds. User can still see the scope.
  **Recommended: (b)** if Da is chosen; (a) if Db is chosen.

**DD5: User palette for non-query Ctrl+clicks.**
When no query is active and the user Ctrl+clicks nodes:
- (a) All user selections share one palette (e.g. Aqua, as today).
  **Simplest, recommended for initial implementation.**
- (b) Each user-selected node gets its own palette. Expensive.
- (c) User selections inherit the palette of their nearest
  palette-tagged ancestor (if within a query subtree). Otherwise
  share one user palette.

---

# SHARED: Files, Phases, Tests (per chosen option)

## S.1 Files to Change — Common to Both Options

### Common Module (cross-compiled JVM + Scala.js)

| # | File | Change |
|---|---|---|
| C1 | `domain/data/PaletteAssigner.scala` | **NEW.** 8-shade trimmed palettes, NodeId-sorted assignment, shades wrap mod 8, `maxCurvesPerPalette` constant. |
| C2 | `domain/data/CurvePalette.scala` | Option A: no change. Option D: expand from 3 → 8 variants. |
| C3 | `http/requests/LECChartRequest.scala` | Update `build()` as per chosen option. |

### Server Module

| # | File | Change |
|---|---|---|
| S1 | `simulation/CurvePaletteRegistry.scala` | **DELETE.** Replaced by `PaletteAssigner`. |
| S2 | `simulation/ColouredCurve.scala` | Delegate to `PaletteAssigner`. |
| S3 | `simulation/LECChartSpecBuilder.scala` | No change (receives `ColouredCurve` as before). |
| S4 | `services/RiskTreeServiceLive.scala` | Option A: minor (returns `LECChartResponse`). Option D: same + subtree logic. |

### Server Tests

| # | File | Change |
|---|---|---|
| T1 | `simulation/CurvePaletteRegistrySpec.scala` | **DELETE.** |
| T2 | `simulation/ColouredCurveSpec.scala` | Rewrite for new `PaletteAssigner` delegation. |

### Common Tests

| # | File | Change |
|---|---|---|
| T3 | `domain/data/PaletteAssignerSpec.scala` | **NEW.** Shade count, sort order, cap/wrap, determinism. |
| T4 | `http/requests/BuildChartRequestSpec.scala` | Update for cap and new palette semantics. |

### App Module (Scala.js)

| # | File | Change |
|---|---|---|
| A1 | `state/LECChartState.scala` | Cap update, new signals, Ctrl+right-click bus (Option D). |
| A2 | `views/AnalyzeView.scala` | Derive `nodeColorMap`, pass to tree view. |
| A3 | `views/TreeDetailView.scala` | Inline styles from colour map, Ctrl+right-click handler (Option D). |
| A4 | `views/QueryResultCard.scala` | Cap-aware text. |
| A5 | `styles/app.css` | Remove dead CSS classes/variables. |

### Integration Tests

| # | File | Change |
|---|---|---|
| I1 | `LECChartEndpointSpec.scala` | Update for new shades, response type (if changed). |

## S.2 Option D — Additional Files

| # | File | Change |
|---|---|---|
| D1 | `domain/tree/SubtreeRootFinder.scala` | **NEW** (in `common`). Maximal subtree root algorithm + BFS ranking. |
| D2 | `domain/tree/SubtreeRootFinderSpec.scala` | **NEW.** Tests: full-match subtrees, partial matches, ranking, >8 cap. |
| D3 | `state/QueryChartState.scala` (or extend `LECChartState`) | **NEW.** Per-node enable/disable, bulk toggle, subtree palette assignments. |
| D4 | `views/AnalyzeView.scala` | Query toggle control (checkbox/button). |

## S.3 Implementation Phases

### Phases common to both options

| Phase | Description | ~Time |
|-------|-------------|-------|
| P0 | Create `PaletteAssigner` + tests in `common` | 45 min |
| P1 | Migrate server: refactor `ColouredCurve`, delete `CurvePaletteRegistry` | 30 min |
| P2 | Update `LECChartRequest.build()`, `LECChartState` cap, common tests | 20 min |

### Option A additional phases

| Phase | Description | ~Time |
|-------|-------------|-------|
| PA3 | `LECChartResponse` DTO, endpoint return type change, integration tests | 30 min |
| PA4 | `nodeColorMap` signal, `TreeDetailView` inline styles, CSS cleanup | 40 min |
| PA5 | QueryResultCard cap text, full test pass | 20 min |

### Option D additional phases

| Phase | Description | ~Time |
|-------|-------------|-------|
| PD3 | Expand `CurvePalette` enum to 8 variants, update codecs/tests | 20 min |
| PD4 | `SubtreeRootFinder` algorithm + tests in `common` | 45 min |
| PD5 | `LECChartResponse` DTO, endpoint change | 30 min |
| PD6 | Subtree palette state, `nodeColorMap` derivation, Ctrl+right-click | 60 min |
| PD7 | `TreeDetailView` palette backgrounds + active borders | 40 min |
| PD8 | Query toggle control, per-node disable, QueryResultCard | 30 min |
| PD9 | CSS cleanup, integration tests, full test pass | 30 min |

**Estimated total:** Option A ≈ 3 hrs, Option D ≈ 5.5 hrs.

## S.4 Test Strategy

### Unit Tests (common) — both options

- `PaletteAssignerSpec`: shade count (8), hex validity, sort order,
  cap enforcement, mod-8 wrap, determinism, empty-set edge case.
- `BuildChartRequestSpec`: cap enforcement per chosen option.

### Unit Tests (common) — Option D only

- `SubtreeRootFinderSpec`: full-match subtrees, partial matches,
  BFS ranking, >8 cap, single-leaf tree, flat tree.

### Unit Tests (server)

- `ColouredCurveSpec`: colours from `PaletteAssigner` (not p95 rank).

### Integration Tests

- `LECChartEndpointSpec`: updated shade values, response type.

### Manual Testing — both options

- 1 matched node → highlight = curve colour.
- 3 matched nodes → each highlight matches curve.
- Ctrl+left-click 2 nodes → highlights match curves.
- 9th node → cap error message.

### Manual Testing — Option D only

- Query matching 2 subtrees → each gets own palette.
- Ctrl+right-click portfolio → children added with parent's palette.
- Ctrl+left-click charted query node → curve removed, border gone,
  faint background stays (Da) or also goes (Db).
- Bulk toggle off → all query curves removed.
- Bulk toggle on → subtree root curves restored.

---

## S.5 Decision Summary

### Accepted Decisions

| ID | Decision | Status |
|----|----------|--------|
| D1 | Sort key: NodeId (ULID lexicographic) | **Accepted** |
| D2-cap | Cap selection split: server picks top-8 by p95, client assigns shades by NodeId | **Accepted** |
| C1-cap | 8 shades per palette (trim 3 lightest + 2 darkest) | **Accepted** |
| C2-subtree | >8 subtree roots: first 8 ranked by position in tree (closest to root) | **Accepted** |

### Open Decisions — Choose Option

| ID | Question |
|----|----------|
| **OPT** | **Option A vs Option D.** Option A is simpler (~3 hrs), delivers colour matching immediately. Option D is richer (~5.5 hrs), enables subtree drill-down and palette scoping. Both are internally consistent and feasible. |

### Open Decisions — Shared (both options)

| ID | Question | Recommendation |
|----|----------|----------------|
| SD3 | `PaletteAssigner` return type: `Map[NodeId, HexColor]` vs `Map[NodeId, String]` | (a) `HexColor` |
| SD4 | Inline style method: direct Laminar props vs CSS custom property override | (a) Direct props |
| SD5 | Surface tint: `color-mix` vs Scala colour math vs fixed translucent | (a) `color-mix` |

### Open Decisions — Option D only

| ID | Question | Recommendation |
|----|----------|----------------|
| DD1 | Ctrl+click within palette subtree: inherit palette (a) or independent user palette (b)? | (a) Inherit |
| DD2 | Partial matches: own palette per leaf (a) or group by nearest common ancestor (b)? | (b) Group |
| DD3 | Da (background always visible) vs Db (background+border toggle together)? | Da |
| DD4 | Bulk toggle off: hide all (a) or keep faint backgrounds (b)? | (b) if Da |
| DD5 | User palette for non-query Ctrl+clicks: shared single palette (a), individual (b), or inherit ancestor (c)? | (a) Shared |

---

## S.6 Out of Scope

- Light theme implementation (commented placeholder only).
- Custom tooltip component (v2 decision D2(a) stands — native `title`).
- Changes to `QueryResponse` wire format.
- Changes to the Vega-Lite spec structure beyond colour values.

---

## S.7 Risk Assessment

| Risk | Impact | Mitigation |
|---|---|---|
| `color-mix` browser support | Low | Baseline 2023. Fallback: client-side colour math. |
| NodeId sort diverges JVM vs JS | Low | ULID is ASCII-only; `String.compareTo` identical across platforms. Cross-platform determinism test. |
| Existing tests break on shade values | Medium | Dedicated phase updates integration tests; `CurvePaletteRegistrySpec` deletion handled explicitly. |
| Option D complexity — subtree root algorithm edge cases | Medium | `SubtreeRootFinder` thoroughly tested; partial-match grouping decision (DD2) clarifies the hard case. |
| 8 palette slots exhausted (Option D) | Low | Rotate with visual indicator (subtle border style change). Trees rarely have >8 independent matching subtrees. |
| `lec-chart` API change breaks existing clients | Low | Endpoint is internal, behind capability URLs, no external consumers. Client and server deployed together. |
