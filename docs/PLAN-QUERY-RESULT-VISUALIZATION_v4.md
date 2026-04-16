# Plan: Query Result Visualization — v4

**Status:** Ready for implementation  
**Scope:** Client-side rendering, colour sync, bidirectional hover  
**Predecessor:** PLAN-QUERY-RESULT-VISUALIZATION_v3.md (Options A/D, not implemented)  
**Date:** 2026-04-16  
**Updated:** 2026-04-16

---

## 0 Motivation

v3 explored two colouring strategies (Option A: flat palettes, Option D:
subtree palettes) while retaining the server as the Vega-Lite spec
builder. Both struggled with the fundamental constraint that **the
server decides colours at spec-generation time, making client-side
colour customisation impossible without a round-trip.**

Investigation revealed:
1. The `lec-multi` endpoint already returns structured curve data
   (`Map[String, LECNodeCurve]`) with full x/y points + quantiles.
2. The spec builder is ~130 lines of JSON assembly with no server-
   exclusive dependencies.
3. `EmbedResult.view` (Vega View API) is already exposed as
   `js.Dynamic` but completely unused after mount.
4. Re-embedding a patched spec takes 30–80ms (imperceptible).
5. `view.signal("name", value).run()` triggers a <1ms differential
   pulse — sufficient for bidirectional hover highlighting.

**This plan moves Vega-Lite spec generation to the client**, enabling
instant colour changes, bidirectional hover-highlighting between chart
curves and tree nodes, a dual automatic/manual colouring system, and
the removal of ~1250 lines of server-side dead code.

### Goals

1. **Client builds the Vega-Lite spec** from cached `LECNodeCurve` data.
2. **Automatic default:** Hash-based rotational shade assignment within
   palette families (Green = query, Aqua = user, Purple = overlap).
3. **Manual override:** User picks colours from a swatch palette picker.
   Overrides apply on top of automatic defaults.
4. **Colour-sync:** Tree node left-border = exact chart curve hex.
   Single source of truth, always in sync — not just on hover.
5. **Bidirectional hover-highlight:** Hovering a chart curve highlights
   the tree node (border thickens). Hovering a tree node highlights
   the chart curve (opacity dimming). Sub-millisecond via Vega signals.
6. **Server dead code purged** — `lec-chart` endpoint, spec builder,
   ColouredCurve, CurvePaletteRegistry all deleted.

### Locked-in Design Decisions

| ID | Question | Decision |
|----|----------|----------|
| CQ1 | Chart hover effect | Opacity dimming — hovered curve 1.0 + strokeWidth 3, others 0.2 |
| CQ2 | Tree hover effect | Thicken left border 3px → 5px, same curve hex colour |
| CQ3 | Selection visual | Border presence = selection indicator; palette hue encodes provenance |
| CQ4 | Hover scope | Only plotted nodes participate; uncharted nodes do nothing |
| CQ5 | Shade count | Trim 13 → 8 (drop 25/50/100 lightest, 950/975 darkest); no reservation needed |

---

## 0.1 ADR Compliance Review

| ADR | Status | Notes |
|-----|--------|-------|
| ADR-001 (Iron types) | Compliant | `HexColor` retained in `common`. Client extracts `.value` at view edge. |
| ADR-010 (Error handling) | Compliant | `lec-multi` errors route via `GlobalError` → `ErrorBanner`. |
| ADR-011 (Import conventions) | Compliant | Top-level imports. |
| ADR-018 (Nominal wrappers) | Compliant | `NodeId` for sorting + map keys. |
| ADR-019 (Frontend architecture) | Compliant | Signals down, events up. `nodeColorMap` signal flows state → views. `ColorSwatchPicker` emits events up via bus. |

### Decision Triggers Acknowledged

Per WORKING-INSTRUCTIONS.md:

- **#1 (API changes):** `lec-chart` endpoint removed; client switches
  to `lec-multi`.
- **#4 (Type changes):** `LECChartState` API changes (accepts
  `List[NodeId]`, returns `Map[String, LECNodeCurve]` not spec string).
- **#5 (Behavioural changes):** Colour assignment moves client-side.
  Spec builder moves client-side.
- **Critical Stop Points:**
  - Deleting `lec-chart` endpoint and all downstream code.
  - Removing `LECChartRequest`, `ColouredCurve`, `CurvePaletteRegistry`,
    `LECChartSpecBuilder` and their tests.
  - Changing `LECChartState` from spec-fetching to data-fetching.
  - Introducing client-side spec builder.
  - Adding `ColorSwatchPicker` component.

---

## 1 Architecture: Client-Side Rendering

### 1.1 Current Flow (to be replaced)

```
Client                             Server
──────                             ──────
LECChartRequest(nodeId→palette) ──POST──▶ getLECChart
                                         ├ getLECCurvesMulti  → simulate
                                         ├ ColouredCurve.assign → hex colours
                                         └ LECChartSpecBuilder → Vega JSON
lecChartSpec: String ◀──────────────── Vega-Lite spec string
  │
  └─▶ vegaEmbed(spec)  →  chart rendered
```

Every colour change, curve add/remove → **full server round-trip**.

### 1.2 New Flow

```
Client                             Server
──────                             ──────
List[NodeId]  ─────────POST──▶ getLECCurvesMulti
                               ├ simulate (or cache hit)
                               └ LECNodeCurve per node
Map[String, LECNodeCurve] ◀──── structured curve data
  │
  ▼ (cached in LECChartState)
  │
  ├─▶ colorAssigner(nodeIds, overrides) → Map[NodeId, HexColor]
  │     ├── automatic: NodeId sort → shade index
  │     └── manual overrides: Map[NodeId, HexColor] applied on top
  │
  ├─▶ LECSpecBuilder.build(curves, colorMap)  → Vega-Lite JSON (js.Dynamic)
  │
  ├─▶ vegaEmbed(spec) → chart rendered
  │
  └─▶ nodeColorMap signal → TreeDetailView inline highlights
```

**Colour changes, curve toggles → local rebuild, zero network.**
**Data fetches only when the set of node IDs changes.**

### 1.3 Data Caching Strategy

`LECChartState` maintains:

```
curveCache: Var[Map[NodeId, LECNodeCurve]]     // fetched data, persists across interactions
visibleCurves: Var[Set[NodeId]]                // currently charted (subset of cache keys)
colorOverrides: Var[Map[NodeId, HexColor]]     // manual picks (persistent across sessions? TBD)
```

**Fetch policy:** When user adds a node to chart:
1. If already in `curveCache` → skip fetch (instant).
2. If not → fetch incremental batch via `lec-multi`.
3. When tree is edited → `curveCache` clears entirely (same as current
   `chartState.reset()` — server cache also invalidated).

**Invalidation:** Same mechanism as today. `TreeBuilderView.onSuccess` →
`selectTree` → `reset()` clears `curveCache` + `visibleCurves` +
`colorOverrides`. SSE-driven invalidation (Phase H) is orthogonal and
unchanged.

---

## 2 Colour Assignment: Automatic Default + Manual Override

### 2.1 Architecture: `ColorAssigner`

A single pure function with a simple contract:

```scala
object ColorAssigner:
  def assign(
    queryNodes: Set[NodeId],          // nodes from query result
    userNodes: Set[NodeId],           // nodes from Ctrl+click
    overrides: Map[NodeId, HexColor], // manual colour picks
    greenPalette: Vector[HexColor],   // 8 shades for query nodes
    aquaPalette: Vector[HexColor],    // 8 shades for user nodes
    purplePalette: Vector[HexColor]   // 8 shades for overlap nodes
  ): Map[NodeId, HexColor] =
    val overlap   = queryNodes intersect userNodes
    val queryOnly = queryNodes -- overlap
    val userOnly  = userNodes  -- overlap

    def hashShade(id: NodeId, palette: Vector[HexColor]): HexColor =
      palette((id.value.hashCode.abs) % palette.size)

    val automatic: Map[NodeId, HexColor] =
      queryOnly.map(id => id -> hashShade(id, greenPalette)).toMap ++
      userOnly.map(id  => id -> hashShade(id, aquaPalette)).toMap ++
      overlap.map(id   => id -> hashShade(id, purplePalette)).toMap

    automatic ++ overrides           // overrides win
```

No state, no server dependency. Lives in the `app` module.

**Key properties:**
- Hash-based: `NodeId.value.hashCode.abs % 8` gives a deterministic
  shade index. Same node → same shade every session. Adding/removing
  peers does NOT shift any other node's colour.
- Palette family encodes provenance: Green = query, Aqua = user,
  Purple = overlap. This is the visual cue for "how did this node
  reach the chart?"
- `overrides` apply on top of automatic. A node with an override
  gets that exact colour regardless of its provenance group.
- `String.hashCode` is deterministic in Scala.js (ASCII-only ULIDs).

**Hash collision note:** With 8 shades, two nodes in the same palette
have ~50% collision chance at 4 nodes (birthday problem). Acceptable
because curves also differ by shape. Manual overrides resolve ambiguity
if needed.

### 2.2 Automatic Default

When a node is added to the chart (query match or Ctrl+click):
1. `ColorAssigner.assign` is called.
2. Node's provenance determines palette family (Green/Aqua/Purple).
3. `nodeId.value.hashCode.abs % 8` picks a shade within the family.
4. Result: deterministic, stable colour that doesn't shift when other
   nodes join or leave the chart.

### 2.3 Manual Override

When a user wants a specific colour for a node:

1. User triggers the colour picker (see §2.5 for trigger).
2. `ColorSwatchPicker` opens, showing 8 palettes × 8 shades = 64
   swatches.
3. User clicks a swatch → `hex` emitted via event bus.
4. `colorOverrides.update(_ + (nodeId -> hex))`.
5. `nodeColorMap` signal recomputes → chart spec rebuilt (local,
   30–80ms) → tree highlights update.

**Removing an override:** User can "reset to automatic" (e.g. right-
click → context menu, or a ✕ button on the picker). This removes the
entry from `colorOverrides`, and the node reverts to its automatic
colour.

### 2.4 The Override Map

```scala
colorOverrides: Var[Map[NodeId, HexColor]] = Var(Map.empty)
```

**Properties:**
- Empty by default (all nodes use automatic colours).
- Entries are added one at a time via picker interactions.
- `reset()` clears all overrides (tree edit or tree selection change).
- Overrides for nodes not in `visibleCurves` are harmless (ignored
  by `ColorAssigner`). No need to garbage-collect stale entries.
- Future: could persist to `localStorage` keyed by `(workspaceKey,
  treeId)` for cross-session persistence. Out of scope for v4.

### 2.5 Colour Picker: Swatch Grid

**Approach:** Custom component in pure Laminar/HTML/CSS. No npm
dependency.

The picker renders our 8 non-neutral palettes × 8 trimmed shades
as a clickable grid:

```
         200   300   400   500   600   700   800   900
Yellow   ████  ████  ████  ████  ████  ████  ████  ████
Green    ████  ████  ████  ████  ████  ████  ████  ████
Red      ████  ████  ████  ████  ████  ████  ████  ████
Purple   ████  ████  ████  ████  ████  ████  ████  ████
Pink     ████  ████  ████  ████  ████  ████  ████  ████
Orange   ████  ████  ████  ████  ████  ████  ████  ████
Aqua     ████  ████  ████  ████  ████  ████  ████  ████
Emerald  ████  ████  ████  ████  ████  ████  ████  ████
```

64 swatches, 24×24px each, 3px gap. Total picker size: ~210×210px.

**Interaction flow:**
1. Picker opens as a popover/dropdown anchored to the node's tree
   row (or a toolbar button).
2. **Live preview:** Hovering a swatch **temporarily applies** that
   colour to the curve + tree highlight. The chart re-embeds on hover
   (30–80ms — fast enough for exploratory previewing). The override
   is not yet committed.
3. **Commit:** Clicking a swatch finalises the selection. The override
   is written to `colorOverrides`.
4. **Cancel:** Clicking outside the picker (or pressing Escape) closes
   it without committing. The temporary colour reverts.
5. **Reset to automatic:** A small "↺ Auto" button at the bottom of
   the picker removes the override for this node.

**Previewing a colour scheme (multiple nodes):** The user opens the
picker for node A, picks a colour (committed), then opens the picker
for node B, and so on. Each committed pick is visible in the chart as
they go. There is no "bulk scheme preview" — colours are applied one
node at a time, each immediately visible in context.

**Can we preview a single colour?** Yes — via hover-preview described
above. The user hovers over swatch candidates and sees each applied
to the curve in real time before clicking.

**Can we preview a colour scheme?** Not in a single interaction —
schemes are built up incrementally. However, the automatic assignment
*is* effectively a scheme preview: the user sees all curves with their
automatic colours before choosing to override any.

### 2.6 Picker Trigger Mechanism

**Open decision PD1 — see §6.**

Options:
- (a) Ctrl+right-click on a tree node → opens picker.
- (b) A small colour‐swatch icon (🎨) appears on hover of a charted
  node's tree row → click opens picker.
- (c) Click on the colour legend in the chart itself → opens picker
  for that curve.

### 2.7 Palette Trimming

Same as v3: 13 shades → 8 shades per palette. Drop 25, 50, 100
(too light) and 950, 975 (too dark). Retain:

| Index | CSS stop | Description |
|-------|----------|-------------|
| 0 | -200 | Lightest (most visible on dark bg) |
| 1 | -300 | |
| 2 | -400 | |
| 3 | -500 | Mid |
| 4 | -600 | |
| 5 | -700 | |
| 6 | -800 | |
| 7 | -900 | Darkest (still distinguishable from surface) |

---

## 3 Client-Side Spec Builder

### 3.1 Port from Server

The server's `LECChartSpecBuilder.generateMultiCurveSpec` is ~130 lines
of `zio.json.ast.Json` assembly. The client port uses `scala.scalajs.js`
dynamic object construction instead:

```scala
object LECSpecBuilder:
  def build(
    curves: Vector[(LECNodeCurve, HexColor)],
    width: Int = 950,
    height: Int = 400
  ): js.Dynamic = { /* ~150 lines of js.Dynamic.literal assembly */ }
```

**Key differences from server version:**
- Uses `js.Dynamic.literal` / `js.Array` instead of `zio.json.ast`.
- Returns `js.Dynamic` (passed directly to `vegaEmbed`) not `String`.
- No stringification step — Vega-Embed accepts a JS object directly.
- Colours are parameters, not computed internally.
- Includes a `hover` selection param + invisible point layer for
  bidirectional hover support (see §3.4).

### 3.2 Spec Features Preserved

All features from the current server-built spec:
- Inline data (`data.values` with `curveId`, `risk`, `loss`, `exceedance`)
- Colour scale (`encoding.color.scale.domain` / `range`)
- Legend with `labelExpr` (curveId → human name)
- Quantile annotations (P50/P95 dashed vertical rules for first curve)
- Axes formatting (Loss: B/M, Probability: percentage)
- Dark theme config (transparent bg, dark grid/axis colours)
- Interpolation toggle (monotone/basis/linear/step-after)
- Canvas renderer, hover enabled

### 3.3 Re-embed vs Signal for Changes

| Change type | Mechanism | Latency |
|-------------|-----------|---------|
| Colour change (manual override) | Full re-embed | 30–80ms |
| Curve add/remove | Full re-embed | 30–80ms |
| Hover highlight | `view.signal().run()` differential pulse | <1ms |

Colour changes and curve changes require re-embed because Data and
encoding scale change. Hover highlighting uses the Vega signal system
(§3B) — no re-embed, just a downstream pulse.

### 3.4 Hover Selection Param

The spec includes a `hover` param for bidirectional hover:

```json
{
  "name": "hover",
  "select": {
    "type": "point",
    "on": "pointerover",
    "clear": "pointerout",
    "nearest": true,
    "fields": ["curveId"]
  }
}
```

**Critical:** `nearest: true` does NOT work on `line` marks. The spec
uses a two-layer approach:

1. **Line layer** — the visible curves. Reads `hover` param for
   conditional opacity/strokeWidth but does NOT define the selection.
2. **Invisible point layer** — `mark: {type: "point", opacity: 0}`.
   Defines the `hover` param with `nearest: true`. Voronoi tessellation
   provides reliable snap-to-nearest-curve behaviour (standard Vega-Lite
   pattern for interactive line charts).

**Conditional encoding on the line layer:**
```json
"opacity": {
  "condition": [
    {"param": "hover", "empty": false, "value": 1.0},
    {"test": "length(data('hover_store')) == 0", "value": 1.0}
  ],
  "value": 0.2
},
"strokeWidth": {
  "condition": [
    {"param": "hover", "empty": false, "value": 3.0},
    {"test": "length(data('hover_store')) == 0", "value": 1.5}
  ],
  "value": 1.5
}
```

Behaviour:
- Nothing hovered → all curves full opacity, normal width.
- Something hovered → that curve = opacity 1.0 + strokeWidth 3.0,
  all others = opacity 0.2 + strokeWidth 1.5.

---

## 3B Bidirectional Hover-Highlight

### 3B.1 Architecture

A single `Var[Option[NodeId]]` acts as the shared hover channel. Both
`LECChartView` and `TreeDetailView` read and write it.

```
┌─────────────────────────────────────────────┐
│  Shared State: hoveredCurveId: Var[Option]  │
│                                             │
│  TreeDetailView                             │
│    onMouseEnter → set(Some(nodeId))         │
│    onMouseLeave → set(None)                 │
│    reads → thicken border 3→5px             │
│                                             │
│  LECChartView                               │
│    addSignalListener("hover") → set(id)     │
│    reads → view.signal("hover", store).run()│
└─────────────────────────────────────────────┘
```

### 3B.2 Chart → Tree (Vega → Laminar)

After `vegaEmbed` resolves:

```scala
val handler: js.Function2[String, js.Dynamic, Unit] = { (_, value) =>
  val arr = value.asInstanceOf[js.Array[js.Dynamic]]
  val hoveredId: Option[String] =
    if (arr.length > 0)
      val values = arr(0).values.asInstanceOf[js.Array[String]]
      if (values.length > 0) Some(values(0)) else None
    else None
  if (hoveredId != externallySet)  // guard against feedback loop
    hoverState.set(hoveredId.flatMap(NodeId.fromString(_).toOption))
}
view.addSignalListener("hover", handler)
```

The listener fires **only when the curveId changes** (Vega signal
semantics), NOT on every mouse move. Cleanup: `view.removeSignalListener`
before `result.finalize()`.

### 3B.3 Tree → Chart (Laminar → Vega)

When `hoveredCurveId` changes (from tree mouse events):

```scala
hoveredCurveId.signal.changes.foreach { maybeId =>
  currentResult.foreach { result =>
    val store = maybeId match
      case Some(id) =>
        js.Array(js.Dynamic.literal(
          "fields" -> js.Array(js.Dynamic.literal("field" -> "curveId", "type" -> "E")),
          "values" -> js.Array(id.value)
        ))
      case None => js.Array()
    externallySet = maybeId.map(_.value)  // guard flag
    result.view.signal("hover", store).run()
  }
}
```

`view.signal().run()` triggers a **differential pulse**: only operators
downstream of the `hover` signal re-evaluate (conditional opacity on
marks). The scenegraph does an incremental re-render — no spec reparse,
no dataflow rebuild. Latency: **<1ms** vs 50–150ms for re-embed.

### 3B.4 Tree-Side Visual Effect

When `hoveredCurveId` matches a tree node's ID:
- Left border thickens from **3px → 5px** (same hex colour).
- Implemented via inline style: `borderLeftWidth` bound to
  `hoveredCurveId.signal.map(hov => if hov == Some(nodeId) then "5px" else "3px")`.
- `padding-left` adjusts to compensate: `calc(var(--sp-1) - borderWidth)`.

Only nodes that have a curve plotted participate (CQ4). Hovering an
uncharted tree node has no effect on the chart.

### 3B.5 Feedback Loop Prevention

Both directions write to `hoveredCurveId`. Without a guard:
1. User hovers tree node → sets hoveredCurveId
2. → Laminar pushes to Vega via `view.signal().run()`
3. → Vega signal listener fires → writes back to hoveredCurveId
4. → Loop

Solution: a `var externallySet: Option[String]` guard flag. The signal
listener compares the incoming value against `externallySet` and
ignores it if they match. The tree→chart direction sets `externallySet`
before calling `view.signal().run()`. The chart→tree direction clears
`externallySet` before writing.

### 3B.6 Performance Characteristics

| Operation | Latency | Notes |
|-----------|---------|-------|
| `view.signal().run()` | <1ms | Differential pulse, only affected marks repaint |
| `view.addSignalListener` callback | Fires on change only | Not every mouse move — projected on `curveId` |
| Tree CSS border-width transition | 0.1s CSS transition | Already has `transition: background-color 0.1s` |
| Canvas hit-testing (10 curves × 100 pts) | Negligible | O(marks) per mouse event |

---

## 4 Tree Highlighting

### 4.1 Single Source of Truth: Always-On Colour Sync

`nodeColorMap: Signal[Map[NodeId, HexColor]]` — derived from
`ColorAssigner.assign(queryNodes, userNodes, overrides, palettes)`.

Every tree node that has a curve on the chart gets a **permanent**
3px left border in its exact curve hex colour. This border is always
visible — not just on hover. It IS the selection indicator (CQ3=A).

**Implementation:** `TreeDetailView` receives `nodeColorMap` and applies
inline styles per node:

```scala
// For each rendered node:
val borderStyle: Signal[String] = nodeColorMap.map { colorMap =>
  colorMap.get(nodeId) match
    case Some(hex) => s"border-left: 3px solid ${hex.value}; padding-left: calc(var(--sp-1) - 3px);"
    case None      => ""
}
```

The old CSS classes `node-query-matched` and `node-chart-selected` are
deleted. All node highlighting is now inline-style-driven from the
colour map, ensuring tree = chart colour at all times.

### 4.2 Hover Effect on Tree Nodes

When `hoveredCurveId` matches a node (from either direction):
- Border thickens from 3px → 5px. Colour unchanged.
- `padding-left` adjusts to `calc(var(--sp-1) - 5px)` to prevent layout shift.

```scala
val isHovered: Signal[Boolean] = hoveredCurveId.signal.map(_.contains(nodeId))
// Inline style combines colour + hover width
val borderWidth: Signal[String] = isHovered.map(h => if h then "5px" else "3px")
```

### 4.3 Provenance is Implicit in Colour

Palette family encodes provenance without extra UI:
- **Green shades** → node came from query
- **Aqua shades** → node came from Ctrl+click
- **Purple shades** → node is in both sets

No extra icon or marker needed (CQ3=A).

### 4.4 Query-Matched But Not Charted

When a query matches nodes but they aren't yet on the chart (no LEC
curves fetched), they appear in `satisfyingNodeIds` but not in
`visibleCurves`.

**Behaviour:** Query-matched-but-not-charted nodes get a **faint
neutral indicator** (subtle dotted border in `--neutral-700`) to
show "this node matches the query" without implying a chart colour.

When the user adds such a node to the chart (Ctrl+click), it enters
`visibleCurves`, gets an automatic colour, and the highlight updates
to match the curve.

---

## 5 Server-Side Changes: Dead Code Purge

### 5.1 Files to DELETE (1046 LOC server, 138 LOC common)

| # | Module | File | LOC |
|---|--------|------|-----|
| D1 | server | `simulation/LECChartSpecBuilder.scala` | 227 |
| D2 | server | `simulation/ColouredCurve.scala` | 55 |
| D3 | server | `simulation/CurvePaletteRegistry.scala` | 40 |
| D4 | server/test | `simulation/LECChartSpecBuilderSpec.scala` | 366 |
| D5 | server/test | `simulation/AssignPaletteColoursSpec.scala` | 147 |
| D6 | server/test | `simulation/CurvePaletteRegistrySpec.scala` | 53 |
| D7 | server-it/test | `http/LECChartEndpointSpec.scala` | 158 |
| D8 | common | `http/requests/LECChartRequest.scala` | 59 |
| D9 | common/test | `http/requests/BuildChartRequestSpec.scala` | 79 |
| | | **Total** | **1184** |

### 5.2 Files to TRIM (~64 LOC)

| # | File | Remove |
|---|------|--------|
| T1 | `services/RiskTreeService.scala` | `getLECChart` trait method (~15 LOC) |
| T2 | `services/RiskTreeServiceLive.scala` | `getLECChart` impl (~20 LOC) |
| T3 | `http/controllers/WorkspaceController.scala` | `getLECChart` handler + route (~14 LOC) |
| T4 | `http/endpoints/WorkspaceEndpoints.scala` | `getWorkspaceLECChartEndpoint` def (~14 LOC) |
| T5 | `WorkspaceReaperSpec.scala` | `getLECChart` stub (1 LOC) |

### 5.3 Conditional DELETE

| File | LOC | Decision |
|------|-----|----------|
| `CurvePalette.scala` | 27 | **Delete** — client-side `ColorAssigner` uses hex vectors from CSS, not an enum. If needed later for a typed palette API, re-introduce. |

### 5.4 What STAYS (untouched)

| File | Why |
|------|-----|
| `getLECCurvesMulti` in `RiskTreeServiceLive` | Serves the `lec-multi` endpoint — the client's new data source. |
| `LECGenerator` + all simulation code | Produces `LECNodeCurve` data. Core pipeline unchanged. |
| `TreeCacheManager` + `InvalidationHandler` | Cache management. Unchanged. |
| `SSEHub` + SSE infrastructure | Future Phase H. Unchanged. |
| `getWorkspaceLECCurvesMultiEndpoint` | The endpoint the client now uses. |

---

## 6 Decided & Remaining Open Items

### 6.1 Decided (locked in)

| ID | Decision | Choice |
|----|----------|--------|
| PD3 | Automatic palette strategy | Multi-palette by source: Green=query, Aqua=user, Purple=overlap. Hash-based shade index within each. |
| PD4 | Override persistence | Session only. `colorOverrides` clears on page reload. |
| PD5 | Query-matched-but-not-charted indicator | Neutral dotted border (`--neutral-700`). |
| PD6 | Colour collision handling | Accept mod-8 wrapping. Manual overrides resolve ambiguity. |

### 6.2 Remaining Open

### PD1: Picker trigger mechanism

How does the user open the colour picker for a specific node?

- **(a) Ctrl+right-click** on a charted node's tree row. Natural
  extension of existing Ctrl+left-click (toggle chart). Risk: context
  menu conflicts on some platforms.
- **(b) Colour-swatch icon** appears on hover of a charted node's tree
  row. Click opens picker. Discoverable, no modifier key. Adds visual
  clutter.
- **(c) Legend click** — click the colour dot / label in the chart
  legend. Requires Vega signal listener integration.
  **Recommendation: (b)** — icon on hover is discoverable and avoids
  modifier/context-menu issues.

### PD2: Hover-preview performance

Re-embedding on every swatch hover (~30–80ms per hover event) should
be smooth for <10 curves. With many curves, Vega compilation may cause
jank.

- **(a) Always re-embed on hover.** Simplest. Acceptable for typical
  curve counts (1–8).
- **(b) Debounce hover** (50ms). Prevents rapid re-embeds during fast
  mouse movement.
- **(c) Patch-only on hover, full rebuild on commit.** Lower latency
  for preview, full rebuild only when finalised.
  **Recommendation: (b)** — debounced hover balances smoothness and
  simplicity.

---

## 7 Files to Create / Modify

### 7.1 Common Module

| # | File | Change |
|---|------|--------|
| C1 | `LECChartRequest.scala` | **DELETE** |
| C2 | `BuildChartRequestSpec.scala` | **DELETE** |
| C3 | `CurvePalette.scala` | **DELETE** |
| C4 | `WorkspaceEndpoints.scala` | **TRIM** — remove `getWorkspaceLECChartEndpoint` |

### 7.2 Server Module

| # | File | Change |
|---|------|--------|
| S1 | `LECChartSpecBuilder.scala` | **DELETE** |
| S2 | `ColouredCurve.scala` | **DELETE** |
| S3 | `CurvePaletteRegistry.scala` | **DELETE** |
| S4 | `LECChartSpecBuilderSpec.scala` | **DELETE** |
| S5 | `AssignPaletteColoursSpec.scala` | **DELETE** |
| S6 | `CurvePaletteRegistrySpec.scala` | **DELETE** |
| S7 | `LECChartEndpointSpec.scala` | **DELETE** |
| S8 | `RiskTreeService.scala` | **TRIM** — remove `getLECChart` |
| S9 | `RiskTreeServiceLive.scala` | **TRIM** — remove `getLECChart` |
| S10 | `WorkspaceController.scala` | **TRIM** — remove handler + route |
| S11 | `WorkspaceReaperSpec.scala` | **TRIM** — remove stub |

### 7.3 App Module — New Files

| # | File | Description |
|---|------|-------------|
| A1 | `chart/LECSpecBuilder.scala` | **NEW.** Client-side Vega-Lite spec construction (~150 LOC). Builds `js.Dynamic` spec with hover selection param, invisible point layer, opacity-dimmed line encoding, and quantile annotations. |
| A2 | `chart/ColorAssigner.scala` | **NEW.** `assign(queryNodes, userNodes, overrides, palettes)` → `Map[NodeId, HexColor]` (~40 LOC). Multi-palette (Green/Aqua/Purple) with hash-based shade index. |
| A3 | `chart/PaletteData.scala` | **NEW.** 3 palette families (Green, Aqua, Purple) × 8 trimmed shades as `Vector[Vector[HexColor]]` (~50 LOC). |
| A4 | `components/ColorSwatchPicker.scala` | **NEW.** Swatch grid popover component (~80 LOC Scala + ~25 LOC CSS). 8 palettes × 8 shades + "↺ Auto" reset. |
| A5 | `state/ChartHoverBridge.scala` | **NEW.** Bidirectional hover bridge (~60 LOC). Shared `hoveredCurveId: Var[Option[String]]`. Methods: `attachToView(view: js.Dynamic)` (installs `addSignalListener("hover", …)`), `pushToView(view: js.Dynamic, nodeId: Option[String])` (calls `view.signal("hover_store", …).run()`). Contains guard flag to prevent feedback loops. |

### 7.4 App Module — Modified Files

| # | File | Change |
|---|------|--------|
| A6 | `state/LECChartState.scala` | **REWRITE.** `lec-multi` fetcher instead of `lec-chart`. New state: `curveCache`, `visibleCurves`, `colorOverrides`. Exposes `nodeColorMap` signal. |
| A7 | `views/LECChartView.scala` | **MODIFY.** Accept `Signal[LoadState[js.Dynamic]]` (spec object). After `vegaEmbed` resolves, call `ChartHoverBridge.attachToView(result.view)`. Subscribe to `hoveredCurveId` to push Laminar→Vega via `ChartHoverBridge.pushToView`. Detach listener before `finalize()`. |
| A8 | `views/AnalyzeView.scala` | **MODIFY.** Build `List[NodeId]` for `lec-multi` instead of `LECChartRequest`. Derive `nodeColorMap`. Wire `ChartHoverBridge.hoveredCurveId` to both chart and tree. |
| A9 | `views/TreeDetailView.scala` | **MODIFY.** Replace CSS class highlighting with inline styles from `nodeColorMap`. Add `onMouseEnter` → set `hoveredCurveId`, `onMouseLeave` → clear. Read `hoveredCurveId` to thicken border (3→5px) on hovered node. Add picker trigger icon (on hover). |
| A10 | `views/QueryResultCard.scala` | **MODIFY.** Minor — no palette references. |
| A11 | `styles/app.css` | **TRIM** — remove `.node-query-matched`, `.node-chart-selected`, `--query-match`, `--chart-selected`. Add `.color-swatch-*` styles, `.node-hovered` border-width transition. |

### 7.5 App Tests — New Files

| # | File | Description |
|---|------|-------------|
| T1 | `chart/ColorAssignerSpec.scala` | Determinism, override wins, mod-8 wrap, empty set, multi-palette assignment. |
| T2 | `chart/LECSpecBuilderSpec.scala` | Spec structure, colour range, hover param present, invisible point layer present, quantile annotations. |
| T3 | `state/ChartHoverBridgeSpec.scala` | Guard flag prevents feedback loop, `None` clears selection store, signal listener wiring. |

---

## 8 Implementation Phases

Six phases, each ending with a green compile / test gate. Complete each
phase fully before starting the next.

---

### Phase P0: Dead Code Purge (~45 min)

**Goal:** Remove all server-side spec-building, palette, and
`LECChartRequest` code. Reduce surface area before building the
replacement.

| Step | File(s) | Action | Verify |
|------|---------|--------|--------|
| 0.1 | D1–D7 (§5.1, server) | Delete 7 server files: `LECChartSpecBuilder`, `ColouredCurve`, `CurvePaletteRegistry`, and their 4 test specs. | `sbt server/compile` — some unused import warnings OK |
| 0.2 | D8–D9 (§5.1, common) | Delete `LECChartRequest.scala`, `BuildChartRequestSpec.scala`. | `sbt common/compile` |
| 0.3 | D10 (§5.1, common) | Delete `CurvePalette.scala`. | `sbt common/compile` |
| 0.4 | T1–T5 (§5.2) | Trim: `RiskTreeService` (remove `getLECChart`), `RiskTreeServiceLive`, `WorkspaceController` (remove route + handler), `WorkspaceEndpoints` (remove endpoint def), `WorkspaceReaperSpec` (remove stub). | `sbt server/compile server/test` green |
| 0.5 | — | Run full `sbt common/test server/test server-it/test`. | All pass. App module has ~5–10 compile errors referencing deleted types — expected, fixed in P1. |

**Commit gate:** All server + common tests green. App module broken (OK).

---

### Phase P1: Client Data Fetching (~30 min)

**Goal:** Rewrite `LECChartState` to fetch structured curve data via
`lec-multi` instead of an opaque Vega JSON string via `lec-chart`.
Wire `AnalyzeView` to supply `List[NodeId]`.

| Step | File(s) | Action | Verify |
|------|---------|--------|--------|
| 1.1 | `state/LECChartState.scala` | Rewrite: remove `loadLECChart`, `lecChartSpec: Var[LoadState[String]]`. Add `curveCache: Var[Map[NodeId, LECNodeCurve]]`, `visibleCurves: Signal[Set[NodeId]]` (derived from `userSelectedNodeIds ++ satisfyingNodeIds`), `colorOverrides: Var[Map[NodeId, HexColor]]`. Add `loadCurves(nodeIds: List[NodeId])` calling `lec-multi`. Keep 13-node cap guard. | Compiles (chart won't render) |
| 1.2 | `views/AnalyzeView.scala` | Update `chartRequest` signal → build `List[NodeId]` from `(satisfyingNodeIds ++ userSelectedNodeIds).toList`. Call `LECChartState.loadCurves(…)` instead of `loadLECChart`. Remove `LECChartRequest` import. | Compiles |
| 1.3 | `state/LECChartState.scala` | Add `nodeColorMap: Signal[Map[NodeId, HexColor]]` — placeholder returning empty map (stubs `ColorAssigner`, which doesn't exist yet). | Compiles |
| 1.4 | — | `sbt app/compile` green | All modules compile |

**Commit gate:** Full compile green. Chart pane shows "Loading…" (no
spec yet). Tree highlights lost (OK — restored in P3).

---

### Phase P2: Spec Builder + Colour Assigner (~75 min)

**Goal:** Build Vega-Lite spec entirely on the client. Render LEC
curves with automatic colours. Include hover selection param and
invisible point layer.

| Step | File(s) | Action | Verify |
|------|---------|--------|--------|
| 2.1 | `chart/PaletteData.scala` | Create. Define 3 palette families: `Green`, `Aqua`, `Purple`. Each is `Vector[HexColor]` with 8 trimmed shades (drop 25/50/100/950/975). | Compiles |
| 2.2 | `chart/ColorAssigner.scala` | Create. `assign(queryNodes, userNodes, overrides, palettes) → Map[NodeId, HexColor]`. Logic: classify → overlap=Purple, query-only=Green, user-only=Aqua. Hash-based shade: `id.value.hashCode.abs % 8`. Overrides win. | Compiles |
| 2.3 | `chart/ColorAssignerSpec.scala` | Create tests: determinism (same input → same output), override wins, mod-8 wrap (9+ nodes), empty set, overlap detection, single-node. | Tests pass |
| 2.4 | `chart/LECSpecBuilder.scala` | Create. `build(curves: Map[NodeId, LECNodeCurve], colorMap: Map[NodeId, HexColor], interpolation: String): js.Dynamic`. Build layered spec: base line layer with `curveId` field + `scale: {domain, range}` colour encoding, hover selection param `{name: "hover", select: {type: "point", on: "pointerover", nearest: true, fields: ["curveId"]}}`, invisible point layer for voronoi detection, opacity condition (`hover.curveId ? 1.0 : 0.3`), quantile rule annotations. | Compiles |
| 2.5 | `chart/LECSpecBuilderSpec.scala` | Create tests: spec has `layer` array, colour domain/range correct, hover param present, point layer has `opacity: 0`, quantile rules present, empty curves → valid spec, single curve. | Tests pass |
| 2.6 | `state/LECChartState.scala` | Wire real `ColorAssigner.assign` into `nodeColorMap` signal. Add `specSignal: Signal[LoadState[js.Dynamic]]` derived from `curveCache + visibleCurves + nodeColorMap + interpolation`. | Compiles |
| 2.7 | `views/LECChartView.scala` | Change to accept `Signal[LoadState[js.Dynamic]]`. In `vegaEmbed` call: pass `spec` as `js.Dynamic` instead of JSON string. Remove JSON parse. | Compiles |
| 2.8 | `views/AnalyzeView.scala` | Pass `specSignal` to `LECChartView`. | Compiles |
| 2.9 | — | Manual test: run `sbt app/fastLinkJS`, open app, Ctrl+click a node. | Chart renders with coloured LEC curves. Hover dims non-hovered curves in chart. |

**Commit gate:** Chart renders with automatic multi-palette colours.
In-chart hover dimming works (Vega-native). Tree not yet synced.

---

### Phase P3: Tree Colour Sync (~40 min)

**Goal:** Tree node highlights use the same `nodeColorMap` colours as
chart curves. Query-matched-but-not-charted nodes get neutral dotted
border.

| Step | File(s) | Action | Verify |
|------|---------|--------|--------|
| 3.1 | `views/AnalyzeView.scala` | Pass `nodeColorMap: Signal[Map[NodeId, HexColor]]` down to `TreeDetailView`. | Compiles |
| 3.2 | `views/TreeDetailView.scala` | In `renderNode`: replace `lineCls` CSS-class approach with inline `border-left` style derived from `nodeColorMap`. For charted nodes: `3px solid <hex>`. For query-matched-but-not-charted: `3px dotted var(--neutral-700)`. For neither: no border. Remove `isChartSelected`, `isQueryMatched` CSS class bindings. | Compiles |
| 3.3 | `styles/app.css` | Remove `.node-query-matched`, `.node-chart-selected`, `--query-match`, `--chart-selected` custom properties. | Compiles, no style regressions |
| 3.4 | — | Manual test: Ctrl+click nodes → tree borders match chart curve colours. Query match without charting → dotted neutral border. | Visual confirmation |

**Commit gate:** Tree and chart colours unified. No CSS-class-based
colour highlighting remains.

---

### Phase P4: Bidirectional Hover (~60 min)

**Goal:** Hovering a chart curve highlights the tree node (thickened
border); hovering a tree node highlights the chart curve (opacity
dimming). Feedback-loop-safe.

| Step | File(s) | Action | Verify |
|------|---------|--------|--------|
| 4.1 | `state/ChartHoverBridge.scala` | Create. Shared `hoveredCurveId: Var[Option[String]]`. `attachToView(view)`: calls `view.addSignalListener("hover", callback)`. Callback reads `hover_store` selection, extracts `curveId`, writes to `hoveredCurveId` (with guard flag check). `pushToView(view, nodeId)`: builds selection store `js.Array(…)` or empty array, calls `view.signal("hover_store", store).run()` (with guard flag). | Compiles |
| 4.2 | `state/ChartHoverBridgeSpec.scala` | Test guard flag logic: setting `externallySet` prevents echo. Clearing hover produces empty store. | Tests pass |
| 4.3 | `views/LECChartView.scala` | After `vegaEmbed` resolves: call `ChartHoverBridge.attachToView(result.view)`. Subscribe to `hoveredCurveId` changes → call `ChartHoverBridge.pushToView(…)` for Laminar→Vega direction. In `finalize()`: remove signal listener. | Compiles |
| 4.4 | `views/TreeDetailView.scala` | Add `onMouseEnter` on charted node rows → set `ChartHoverBridge.hoveredCurveId` to `Some(nodeId.value)`. Add `onMouseLeave` → set to `None`. Read `hoveredCurveId` signal: when matches this node's id, thicken border from 3px → 5px. | Compiles |
| 4.5 | `views/AnalyzeView.scala` | Wire `ChartHoverBridge` instance: pass to both `LECChartView` and `TreeDetailView`. | Compiles |
| 4.6 | `styles/app.css` | Add `transition: border-width 80ms ease` to tree node line style for smooth thickening. | Smooth animation |
| 4.7 | — | Manual test: hover chart curve → tree node border thickens. Hover tree node → chart curve stays opaque while others dim. Move away → all revert. | Visual + no console errors |

**Commit gate:** Bidirectional hover works. No feedback loops. Console
clean.

---

### Phase P5: Colour Picker + Polish (~75 min)

**Goal:** Users can manually override any charted node's colour via a
swatch picker. Final cleanup and full test pass.

| Step | File(s) | Action | Verify |
|------|---------|--------|--------|
| 5.1 | `components/ColorSwatchPicker.scala` | Create. Renders an 8×8 grid (8 palettes × 8 shades). Props: `currentColor: HexColor`, `onSelect: HexColor => Unit`, `onReset: () => Unit`. Shows "↺ Auto" button. Popover positioning relative to trigger element. | Compiles |
| 5.2 | `styles/app.css` | Add `.color-swatch-picker`, `.swatch-cell`, `.swatch-cell--active`, `.swatch-reset-btn` styles. | Visual |
| 5.3 | `views/TreeDetailView.scala` | Add colour-swatch icon that appears on hover of a charted node row (per PD1 recommendation (b)). Click opens `ColorSwatchPicker`. | Compiles |
| 5.4 | `state/LECChartState.scala` | Add `setColorOverride(nodeId, hex)` and `clearColorOverride(nodeId)` methods that update `colorOverrides` Var. | Compiles |
| 5.5 | Wire picker → state | Picker `onSelect` → `setColorOverride`. Picker `onReset` → `clearColorOverride`. | Override reflected in chart + tree immediately |
| 5.6 | Hover preview | On swatch hover (debounced 50ms, per PD2 (b)): temporarily apply colour to see preview. On mouse leave without click: revert. | Smooth preview |
| 5.7 | — | Run `sbt test` (all modules). | All tests green |
| 5.8 | — | Full manual testing matrix (§9.3). | All scenarios pass |
| 5.9 | Cleanup | Remove dead imports. Update Scaladoc references to deleted types. Verify no `TODO` items left from this plan. | Clean |

**Commit gate:** All features complete. All tests green. Manual matrix
passed.

---

### Summary

| Phase | Description | Est. Time | Key Deliverable |
|-------|-------------|-----------|-----------------|
| P0 | Dead code purge | 45 min | ~1200 LOC deleted, server clean |
| P1 | Client data fetching | 30 min | `lec-multi` wired, structured data in client state |
| P2 | Spec builder + colours | 75 min | Chart renders client-side with hover dimming |
| P3 | Tree colour sync | 40 min | Tree borders match chart, unified colour map |
| P4 | Bidirectional hover | 60 min | Hover chart ↔ tree, feedback-loop-safe |
| P5 | Colour picker + polish | 75 min | Manual overrides, full test pass |
| **Total** | | **~5.5 hrs** | |

---

## 9 Test Strategy

### 9.1 Unit Tests

- **`ColorAssignerSpec`**: determinism (same input → same output),
  override wins over automatic, mod-8 wrap (9+ nodes share shades),
  empty set, single node, overlap → Purple, query-only → Green,
  user-only → Aqua.
- **`LECSpecBuilderSpec`**: spec has `layer` array, colour
  domain/range match input `colorMap`, hover param `"hover"` present
  with `type: "point"`, invisible point layer has `opacity: 0`,
  quantile rule annotations present, empty curves → valid empty spec,
  single curve → no dimming ambiguity.
- **`ChartHoverBridgeSpec`**: guard flag prevents feedback loop
  (setting `externallySet` suppresses echo write), clearing hover
  produces empty selection store, `attachToView` registers signal
  listener.

### 9.2 Integration Tests

- `sbt server/test` passes after dead code purge (P0).
- `sbt server-it/test` passes (deleted `LECChartEndpointSpec` no longer
  runs).
- `lec-multi` endpoint tests unaffected.

### 9.3 Manual Testing Matrix

| # | Scenario | Expected |
|---|----------|----------|
| M1 | Ctrl+click 1 node | Curve appears, tree border matches curve colour |
| M2 | Ctrl+click 3 nodes | 3 curves with distinct colours, each tree border matches |
| M3 | Query matching 4 nodes | 4 green-shade curves auto-added, tree borders match |
| M4 | Query + manual = overlap | Overlapping nodes get Purple shades |
| M5 | Hover chart curve | Hovered curve stays opaque, others dim to 0.3. Corresponding tree node border thickens 3→5px. |
| M6 | Hover tree node | Chart: that node's curve stays opaque, others dim. Tree: border thickens. |
| M7 | Move mouse away from both | All curves full opacity. All borders revert to 3px. |
| M8 | Rapid hover between curves | No feedback loop, no console errors, smooth transitions |
| M9 | Open picker for node, hover swatches | Curve + tree border colour live-preview (debounced) |
| M10 | Pick a colour, confirm | Override persists, chart + tree reflect new colour |
| M11 | Pick colour then Escape | Reverts to previous colour |
| M12 | Click "↺ Auto" in picker | Override removed, automatic colour restored |
| M13 | Edit tree | Cache clears, chart resets, overrides cleared |
| M14 | Query-matched node not yet charted | Neutral dotted border (`--neutral-700`), no chart curve |
| M15 | Ctrl+click that node → charted | Dotted border → solid coloured border + new curve |
| M16 | 9+ curves (mod-8 wrap) | Two curves share same shade; no crash, manual override resolves |
| M17 | Re-embed after hover | Interpolation dropdown change → re-embed → hover still works |

---

## 10 Out of Scope

- Light theme support (placeholder `// TODO` in `PaletteData`).
- Arbitrary colour input via native `<input type="color">` — future
  "Custom…" row at bottom of picker.
- Override persistence across page reload (PD4: session-only for v4).
- SSE-driven cache invalidation (Phase H — orthogonal, unchanged).
- Subtree-based multi-palette automatic assignment (v3 Option D — can
  layer on top of `ColorAssigner` later).
- Bulk colour scheme / theme application.
- Chart legend interaction (click legend to select/deselect curves).
- Keyboard-driven hover (Tab through curves / tree nodes).

---

## 11 Risk Assessment

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Re-embed on picker hover causes jank (>10 curves) | Medium | Low | PD2: debounce 50ms. Typical use ≤8 curves. |
| `js.Dynamic` spec builder harder to test than typed | Low | Medium | Tests convert `js.Dynamic` → JSON string and assert structure. |
| `lec-multi` response shape differs from expectations | Low | Low | Same `LECNodeCurve` type, already tested. |
| Interpolation state lost on re-embed | Low | Medium | Read `view.signal("interpolate")` before finalize, inject into new spec. |
| Feedback loop between chart hover and tree hover | High | Low | Guard flag in `ChartHoverBridge`. Tested in `ChartHoverBridgeSpec`. |
| `addSignalListener` / `signal().run()` API changes in Vega 6+ | Medium | Low | Pin vega-embed version. Facade wraps calls centrally in `ChartHoverBridge`. |
| `String.hashCode` differs JVM vs JS for non-ASCII | Low | None | ULIDs are ASCII-only. `hashCode` deterministic for ASCII in Scala.js. |
| Removing `lec-chart` endpoint breaks external consumers | None | None | Internal endpoint, capability URLs, no external consumers. |
| Invisible point layer interferes with line tooltip | Low | Medium | Point layer has `tooltip: null`, line layer keeps tooltip. |
