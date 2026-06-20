# Plan: ECharts Migration — LEC Chart, Distribution Chart, Threshold Pie, Scenario Comparison

**Status:** Awaiting approval  
**Date:** 2026-05-17  
**Tags:** frontend, laminar, scala-js, echarts, vega-lite, lec-chart, analyze-view

---

## Objective

Migrate both client-side chart surfaces from Vega-Lite to Apache ECharts, then add two
new analytical features enabled by the migration:

1. **Threshold-linked pie** — as the user moves a crosshair over the LEC chart, a
   companion pie chart shows each selected node's contribution to P(loss > X) at the
   current threshold X. X is a continuous parameter controlled by the axis pointer.

2. **Scenario comparison** — overlay LEC curves from multiple trees in one chart, so
   different modelling configurations can be compared side-by-side.

ECharts is chosen over Vega or D3 because:
- `on('updateAxisPointer', fn)` is a first-class event that delivers the axis X value
  continuously — this is the foundation for the threshold pie interaction without any
  server round-trips.
- Multi-series charts are native; scenario comparison falls out of the same series array.
- `instance.setOption(opts, { notMerge: true })` updates the chart in-place — no
  re-embed Promise overhead, enabling smooth continuous updates for the threshold pie.
- The option format is a `js.Dynamic` object — the same construction pattern already
  used in `LECSpecBuilder` and `DistributionSpecBuilder`.
- `ColorAssigner` and `PaletteData` are Vega-independent and reused unchanged.

---

## Affected File Map (current state)

| File | Role | Fate |
|------|------|------|
| `app/facades/VegaEmbed.scala` | `@JSImport` facade for `vega-embed` | Deleted in Phase E4 |
| `app/chart/LECSpecBuilder.scala` | Builds Vega-Lite spec for LEC multi-curve chart | Deleted in Phase E2 |
| `app/chart/DistributionSpecBuilder.scala` | Builds Vega-Lite spec for PDF/CDF preview | Deleted in Phase E3 |
| `app/chart/ColorAssigner.scala` | `NodeId → HexColor` mapping | **Unchanged** |
| `app/chart/PaletteData.scala` | Palette definitions | **Unchanged** |
| `app/state/ChartHoverBridge.scala` | Bidirectional Vega↔Laminar hover state | Preserved until Phase E3.5; deleted in E3.5-4 |
| `app/state/LECChartState.scala` | LEC curve data cache, `specSignal` derivation | Modified in Phase E2; extended in Phase E5 |
| `app/state/DistributionChartState.scala` | Distribution preview cache, `specSignal` | Modified in Phase E3 |
| `app/views/LECChartView.scala` | Renders LEC chart via `vegaEmbed` | Renamed to `VegaLECChartView` (E2-4a); replaced with new ECharts implementation (E2-4b) |
| `app/views/VegaLECChartView.scala` | Preserved Vega LEC chart (renamed from `LECChartView`) | Created E2-4a; used in comparison (E3.5-1); deleted E3.5-4 |
| `app/views/DistributionChartView.scala` | Renders distribution preview via `vegaEmbed` | Renamed to `VegaDistributionChartView` (E3-3a); replaced with new ECharts implementation (E3-3b) |
| `app/views/VegaDistributionChartView.scala` | Preserved Vega distribution chart (renamed from `DistributionChartView`) | Created E3-3a; used in comparison (E3.5-1); deleted E3.5-4 |
| `app/views/AnalyzeView.scala` | Creates `ChartHoverBridge`; wires hover | Modified in Phase E2; comparison layout added+removed in E3.5; extended in Phase E5 |
| `app/views/TreeDetailView.scala` | Reads `hoveredCurveId` from bridge | Modified in Phase E2 (bridge type); gains `onFocusPortfolio` callback in Phase E5 |
| `modules/app/package.json` | Has `vega`, `vega-embed`, `vega-lite` | Modified in E1, cleaned in E4 |

---

## ADR Compliance Review (Planning Phase)

| ADR | Relevant Constraint | Status |
|-----|---------------------|--------|
| ADR-001 | Iron types at boundaries; validation at domain edges | ✅ `LECNodeCurve` and `DistributionPreviewResponse` arrive from the server already validated. Option builders are pure transforms of validated data. No new domain boundary. |
| ADR-011 | Top-level import conventions | ✅ All new code will follow. |
| ADR-018 | Nominal wrappers (`NodeId`, `TreeId`) | ✅ `NodeId` used throughout chart state and hover bridge; no change to wrapping convention. |
| ADR-019 | Signals down / callbacks up; state above pure views; no mutations in rendering | ✅ ECharts event callbacks write to Laminar `Var`s (same pattern as current bridge). Option builders are pure functions. State objects created above views. `focusPortfolioId` is written via `onFocusPortfolio: Option[NodeId] => Unit` callback on `TreeDetailView.apply` — `TreeDetailView` never accesses `focusPortfolioId` directly. |
| ADR-025 | SPA routing | ✅ Not affected. |
| ADR-028 | Query pane | ✅ Not affected; `AnalyzeQueryState` unchanged. |

**Deviations detected:** None.

---

## Data Flow Reference

### LEC chart hover (current, Vega)

```
TreeDetailView.onMouseEnter
  → ChartHoverBridge.hoveredCurveId.set(Some(nodeId))
  → LECChartView subscription
  → hoverBridge.pushToView(view, maybeId)       ← Vega: view.signal("hover_store", store)
  ↕
Vega signal listener ("hover")
  → ChartHoverBridge.parseHoverSignal(value)
  → ChartHoverBridge.hoveredCurveId.set(...)
  → TreeDetailView CSS highlight via signal
```

### LEC chart hover (target, ECharts)

```
TreeDetailView.onMouseEnter
  → EChartsHoverBridge.hoveredCurveId.set(Some(nodeId))
  → LECChartView subscription
  → bridge.highlightNode(instance, maybeId)     ← ECharts: instance.dispatchAction(...)
  ↕
ECharts mouseover event
  → EChartsHoverBridge.parseEChartsEvent(params)
  → EChartsHoverBridge.hoveredCurveId.set(...)
  → TreeDetailView CSS highlight via signal (unchanged)
```

### Threshold pie data flow (new, Phase E5)

```
ECharts updateAxisPointer event
  → LECChartState.axisPointerXVar.set(Some(x: Double))
  → LECChartState.pieDataSignal (derived):
      axisPointerXVar × curveCache × focusPortfolioId × expandedNodes × selectedTree
      → ThresholdPieCalculator.compute(x, focusId, expandedIds, tree, curves)
      → Vector[(name, exceedanceProbability)]   ← pure interpolation, no server call
  → LECChartView subscription (pieDataSignal.changes)
      → instance.setOption({series: [{type:'pie', data:[...]}]}, notMerge=false)
        ← partial update, same instance as line chart (E5-D2: in-chart overlay)
```

---

## Phase E1 — ECharts Dependency + Scala.js Facade

**Prerequisite:** None.

**Goal:** Bring ECharts into the project and define the typed Scala.js facade that all
later phases depend on. No Vega code is removed yet — both libraries coexist briefly.

---

### Decision Point E1-D1 — Bundle strategy: RESOLVED (Option A for now)

**Decision:** Option A — full bundle (`import * as echarts from 'echarts'`). `package.json`
adds `"echarts": "^5.5.0"`. Single `@JSImport("echarts", ...)` facade. Matches the
current `vega-embed` full-bundle approach — simple facade, easy to add chart types later.

Option B (modular tree-shaking, ~200–300 KB gzip) is tracked in TODO.md for a follow-up
evaluation once the full migration is stable and bundle size can be profiled against real
usage.

---

### Step E1-1 — Add ECharts NPM dependency

**File:** `modules/app/package.json`

Add `"echarts": "^5.5.0"` to `dependencies`. Do not remove Vega packages yet.

### Step E1-2 — Define Scala.js facade

**File:** `modules/app/src/main/scala/app/facades/ECharts.scala`

Typed facade covering the lifecycle methods used by chart views:

```scala
// facade for echarts.init(el, theme?, opts?)
@js.native @JSImport("echarts", "init")
def echartsInit(el: dom.HTMLElement, theme: js.UndefOr[String] = js.undefined): EChartsInstance = js.native

@js.native
trait EChartsInstance extends js.Object:
  def setOption(option: js.Any, notMerge: js.UndefOr[Boolean] = js.undefined): Unit = js.native
  def on(event: String, handler: js.Function1[js.Dynamic, Unit]): Unit = js.native
  def off(event: String, handler: js.UndefOr[js.Function1[js.Dynamic, Unit]] = js.undefined): Unit = js.native
  def dispatchAction(payload: js.Any): Unit = js.native
  def dispose(): Unit = js.native
  def resize(): Unit = js.native
```

The (Bundle strategy B variant of this step would include `echarts.use(...)` registration.)

### Step E1-3 — Tests

No unit tests for the facade itself — it is a thin `@js.native` wrapper around an NPM
package. Integration-level verification happens in Phase E2's manual checklist.

### Phase E1 — Files touched

| File | Change |
|------|--------|
| `modules/app/package.json` | Add `echarts` |
| `modules/app/src/main/scala/app/facades/ECharts.scala` | New facade |

---

## Phase E2 — LEC Chart Migration

**Prerequisite:** Phase E1 approved and complete.

**Goal:** Replace `LECSpecBuilder` and `LECChartView` with ECharts equivalents; replace
`ChartHoverBridge` with `EChartsHoverBridge`. Before overwriting, `LECChartView` is renamed
to `VegaLECChartView` (zero logic changes) to preserve the Vega implementation for the
Phase E3.5 visual comparison gate. `ChartHoverBridge` is not deleted in this phase —
it is kept alive for `VegaLECChartView` until E3.5-4. `ColorAssigner`, `PaletteData`,
`LECChartState.curveCache`, and `LECChartState.specSignal` type (`Signal[LoadState[js.Dynamic]]`)
remain unchanged — the option builder is a drop-in replacement for the spec builder at the call site.

---

### Decision Point E2-D1 — Interpolation toggle fate: RESOLVED (Option B — dropped)

**Decision:** Hardcode `smooth: true, smoothMonotone: 'x'` (best-quality monotone cubic
spline). No toggle. `EChartsLECOptionBuilder.build` has no interpolation parameter.
No `interpolationModeVar` in `LECChartState`. The Vega-Lite `<select>` that existed
inside the chart canvas is removed with no frontend replacement.

---

### Functional Equivalence Specification

For each migrated component, the new implementation must preserve the following
behavioural contracts. Tests in E2-3 and E3-2 are the automated verification of
these contracts.

| Current | New | Must-preserve semantics |
|---|---|---|
| `LECSpecBuilder.build(curves, ...)` | `EChartsLECOptionBuilder.build(curves, ...)` | Empty input → empty chart (no series); for each `(curve, color)` pair exactly one rendered series; series color matches paired `HexColor`; P50/P95 annotations present at same loss values when quantiles non-empty; B/M axis label format preserved |
| `ChartHoverBridge.parseHoverSignal(v)` | `EChartsHoverBridge.parseMouseoverEvent(p)` | Valid node ID string → `Some(NodeId)`; invalid, missing, or null/undefined input → `None`; no side effects |
| `ChartHoverBridge.buildSelectionStore(id)` | `EChartsHoverBridge.buildHighlightAction(id, n)` | `Some(nodeId)` → a highlight action; `None` → a downplay action; the field the option builder writes as series name round-trips through the parse function |
| `DistributionSpecBuilder.build(...)` | `EChartsDistributionOptionBuilder.build(...)` | PDF mode: filled area series; CDF mode: y-axis domain `[0,1]`; anchor points (percentiles for expert, min/max for lognormal) present as `markLine` entries; dark theme colours preserved |

### Build gate policy

**Every step ends with a required gate before the next step begins:**
`sbt test` (for `modules/app`) must be green. A failing test is the implementer's
responsibility regardless of cause — do not proceed while red.

---

### Step E2-1 — `EChartsLECOptionBuilder` (pure function)

**File:** `modules/app/src/main/scala/app/chart/EChartsLECOptionBuilder.scala`

Pure function replacing `LECSpecBuilder`. Signature:

```scala
object EChartsLECOptionBuilder:
  def build(
    curves: Vector[(LECNodeCurve, HexColor)],
    width:  Int = 950,
    height: Int = 400
  ): js.Dynamic
```

Interpolation is hardcoded to `smooth: true, smoothMonotone: 'x'` (Decision E2-D1 = B).
No interpolation parameter.

The produced ECharts option covers:
- `xAxis` with loss-amount formatting (B/M suffix, matching current axis labels)
- `yAxis` as percentage with domain `[0, adaptiveCeiling]`
- One `series` entry per curve, `type: 'line'`, `smooth: true`, with `itemStyle.color`
  from the paired `HexColor`
- `legend` with node names
- `tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } }` (axis pointer must be
  enabled here for Phase E5's threshold pie event)
- `markLine` entries for P50/P95 quantile annotations (replacing current dashed rule layers)
- Emphasis/blur config for hover highlight (replaces Vega opacity condition)
- `dataZoom` for pan/zoom (new capability, not in current Vega chart)

**ADR FL1 compliance:** `formatLossValue(v: Double): String` and
`yAxisCeiling(curves: Vector[(LECNodeCurve, HexColor)]): Double` extracted as named
pure functions, not inlined in the option builder.

### Step E2-2 — `EChartsHoverBridge` (replaces `ChartHoverBridge`)

**File:** `modules/app/src/main/scala/app/state/EChartsHoverBridge.scala`

Public API matches the parts of `ChartHoverBridge` that `TreeDetailView` and
`AnalyzeView` consume:

```scala
final class EChartsHoverBridge:
  val hoveredCurveId: Var[Option[NodeId]] = Var(None)
  def attachToInstance(instance: EChartsInstance): Unit
  def detachFromInstance(instance: EChartsInstance): Unit
  def highlightNode(instance: EChartsInstance, nodeId: Option[NodeId]): Unit
```

`TreeDetailView` uses only `hoveredCurveId` — no change required there.  
`LECChartView` calls `attach`/`detach`/`highlight` — same pattern as current `attach`/`detach`/`push`.

ECharts-specific internals:
- `attachToInstance`: registers `instance.on("mouseover", handler)` and
  `instance.on("mouseout", handler)`. `mouseover` params carry `seriesName` (which is
  set to `NodeId.value` in the option builder).
- `detachFromInstance`: calls `instance.off("mouseover", handler)` and
  `instance.off("mouseout", handler)`.
- `highlightNode`: dispatches `{ type: 'highlight', seriesIndex: n }` / `downplay`.
- Guard flag against feedback loops: same `externallySet: Option[NodeId]` pattern.

Pure testable companions on the `EChartsHoverBridge` object:

```scala
object EChartsHoverBridge:
  def parseMouseoverEvent(params: js.Dynamic): Option[NodeId]
  def buildHighlightAction(nodeId: Option[NodeId], seriesCount: Int): js.Dynamic
```

### Step E2-3 — Tests

> **Write and run these specs before any view rewrite or deletion (E2-4, E2-5, E2-6).**
> Both new specs AND the existing `ChartHoverBridgeSpec` must be green before proceeding.
> Gate: `sbt test`.

**New files:**
- `modules/app/src/test/scala/app/chart/EChartsLECOptionBuilderSpec.scala`
- `modules/app/src/test/scala/app/state/EChartsHoverBridgeSpec.scala`

ZIO Test (Scala.js), same pattern as `ChartHoverBridgeSpec`.

`EChartsLECOptionBuilderSpec` test cases:
1. Empty curves → `emptyOption` (no series, no legend entries)
2. Single curve → one series entry; `itemStyle.color` matches input `HexColor`
3. Multi-curve → series array length matches input; colours correctly paired
4. Quantile marks → P50/P95 `markLine` entries present when quantiles non-empty
5. Adaptive y-ceiling → does not exceed 1.0

`EChartsHoverBridgeSpec` test cases (pure functions only — no `EChartsInstance`):
1. `parseMouseoverEvent`: valid `params.seriesName` (ULID) → `Some(NodeId)`
2. `parseMouseoverEvent`: params itself is `null`/`undefined` → `None`  ← covers `ChartHoverBridgeSpec` case 4
3. `parseMouseoverEvent`: params present but `seriesName` field missing/null → `None`
4. `parseMouseoverEvent`: params with invalid string (not a ULID) → `None`
5. `buildHighlightAction`: `Some(nodeId)` → action with `type: 'highlight'`
6. `buildHighlightAction`: `None` → action with `type: 'downplay'`
7. Round-trip / option-builder contract: construct mock params with `seriesName = nodeId.value`
   (the value the option builder writes per its series naming convention) →
   `parseMouseoverEvent` returns `Some(nodeId)`. This verifies the semantic contract
   between the option builder and the parser — the same invariant that
   `ChartHoverBridgeSpec`'s round-trip tested for Vega.

### Step E2-4 — Preserve + Rewrite `LECChartView`

**Sub-step E2-4a — Rename old implementation (zero logic changes)**

Rename `LECChartView.scala` → `VegaLECChartView.scala` and rename the object
`LECChartView` → `VegaLECChartView` inside the file. No other changes — the
`vegaEmbed` lifecycle, `ChartHoverBridge` parameter, and all helper functions remain
exactly as they are. This preserves the Vega implementation for use in the Phase E3.5
visual comparison gate. E2-4a and E2-4b are one atomic work unit; the build gate is
after E2-4b, not between them.

**Sub-step E2-4b — Write new `LECChartView`**

**File:** `modules/app/src/main/scala/app/views/LECChartView.scala` (new file)

Replace the `vegaEmbed` Promise lifecycle with ECharts synchronous init:

```
div(lec-chart-view)
  └── child <-- specSignal.map (idle/loading/failed/loaded discriminator)
       └── On Loaded(opts):
           div(lec-chart-container)
             onMountCallback:
               instance = echartsInit(el)
               instance.setOption(opts)
               bridge.attachToInstance(instance)
               // hover push subscription:
               bridge.hoveredCurveId.signal.changes → bridge.highlightNode(instance, _)
             onUnmountCallback:
               bridge.detachFromInstance(instance)
               instance.dispose()
```

Key improvement over current pattern: when the spec signal emits a new `Loaded(opts)`
while already mounted (e.g. a curve is added), call `instance.setOption(opts, notMerge = true)`
on the same instance instead of dispose-and-recreate. This eliminates the re-mount
overhead for incremental updates.

The `renderIdle` / `renderLoading` / `renderError` helper functions are unchanged in
structure (same CSS classes, same message text).

Gate: `sbt test` green before proceeding.

### Step E2-5 — Wire `AnalyzeView`

**File:** `modules/app/src/main/scala/app/views/AnalyzeView.scala`

Replace `val hoverBridge = new ChartHoverBridge()` with `val hoverBridge = new EChartsHoverBridge()`.

`EChartsHoverBridge` is an unrelated class — not a subtype of `ChartHoverBridge`. The
parameter types of `LECChartView.apply` and `TreeDetailView.apply` must be updated from
`ChartHoverBridge` to `EChartsHoverBridge`. Update the parameter types at both call sites
and their respective `apply` signatures before this step will compile.

Gate: `sbt test` green before proceeding.

### Step E2-6 — Delete `LECSpecBuilder` and `ChartHoverBridgeSpec`

> **`ChartHoverBridge.scala` is NOT deleted here. It is still needed by `VegaLECChartView`
> during the Phase E3.5 visual comparison gate. It will be deleted in E3.5-4.**

> **All new specs must be green before this step. Deleting `ChartHoverBridgeSpec` is only
> safe once `EChartsHoverBridgeSpec` covers the same behavioural surface (verified in E2-3).**

- Delete `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`
- Delete `modules/app/src/test/scala/app/state/ChartHoverBridgeSpec.scala`

Gate: `sbt test` green after deletion (only new tests remain; all must pass).

### Phase E2 — Integration verification (manual)

- [ ] LEC chart renders curves with correct colours after a query run.
- [ ] Hover over a curve in the chart → matching node highlighted in `TreeDetailView`.
- [ ] Hover over a node in `TreeDetailView` → matching curve highlighted in chart.
- [ ] Ctrl+click a node → user-selected set grows; colour changes to aqua palette.
- [ ] 13-cap error appears on the 14th user selection.
- [ ] P50/P95 markLine annotations visible on chart.
- [ ] `dataZoom` slider present and functional.
- [ ] Chart updates smoothly (no flicker) when query changes.

### Phase E2 — Files touched

| File | Change |
|------|--------|
| `app/chart/EChartsLECOptionBuilder.scala` | New (E2-1) |
| `app/state/EChartsHoverBridge.scala` | New (E2-2) |
| `app/test/…/EChartsLECOptionBuilderSpec.scala` | New (E2-3) |
| `app/test/…/EChartsHoverBridgeSpec.scala` | New (E2-3) |
| `app/views/VegaLECChartView.scala` | Renamed from `LECChartView.scala` (E2-4a); deleted in Phase E3.5 |
| `app/views/LECChartView.scala` | New ECharts implementation (E2-4b) |
| `app/views/AnalyzeView.scala` | `ChartHoverBridge` → `EChartsHoverBridge`; `LECChartView.apply` and `TreeDetailView.apply` parameter types updated (E2-5) |
| `app/chart/LECSpecBuilder.scala` | Deleted (E2-6) |
| `app/state/ChartHoverBridge.scala` | **Not deleted here** — deleted in Phase E3.5 |
| `app/test/…/ChartHoverBridgeSpec.scala` | Deleted (E2-6, after E2-3 spec passes) |

---

## Phase E3 — Distribution Chart Migration

**Prerequisite:** Phase E2 approved and complete.

**Goal:** Replace `DistributionSpecBuilder` + `DistributionChartView` with ECharts
equivalents. `DistributionChartState.specSignal` type stays `Signal[LoadState[js.Dynamic]]`.
No hover bridge involvement — the distribution chart is unidirectional.

### Step E3-1 — `EChartsDistributionOptionBuilder` (pure function)

**File:** `modules/app/src/main/scala/app/chart/EChartsDistributionOptionBuilder.scala`

```scala
object EChartsDistributionOptionBuilder:
  def build(
    response: DistributionPreviewResponse,
    viewMode: DistributionViewMode,
    draft:    Option[LeafDistributionDraft] = None,
    width:    Int = 950,
    height:   Int = 300
  ): js.Dynamic
```

For **PDF mode**: `type: 'line'` with `areaStyle` (filled area under curve), dark teal
`#4a8a8e` matching current colour. Anchor point annotations (percentiles/quantiles for
expert mode, min/max for lognormal) rendered as `markLine` vertical rules.

For **CDF mode**: `type: 'line'` with monotone smooth, same colour. `markLine` for
same anchor points. Y-axis `[0, 1]` with percentage format.

Dark theme config (background `transparent`, label/axis colours) mirrors existing
Vega config.

`anchorAnnotations(response, draft, viewMode): js.Array[js.Any]` extracted as a named
pure function (ADR FL1 compliance).

### Step E3-2 — Tests

> **Write and run this spec before the view rewrite or deletion (E3-3, E3-4).**
> Gate: `sbt test`.

**New file:** `modules/app/src/test/scala/app/chart/EChartsDistributionOptionBuilderSpec.scala`

ZIO Test (Scala.js).

Test cases:
1. Empty points → emptyOption
2. PDF mode → `series[0].type == 'line'` and `areaStyle` is set
3. CDF mode → `series[0].type == 'line'` and y-axis domain `[0, 1]`
4. Expert mode with percentile anchors → `markLine.data` contains anchor points
5. Lognormal with min/max → `markLine.data` contains bound markers

### Step E3-3 — Preserve + Rewrite `DistributionChartView`

**Sub-step E3-3a — Rename old implementation (zero logic changes)**

Rename `DistributionChartView.scala` → `VegaDistributionChartView.scala` and rename
the object `DistributionChartView` → `VegaDistributionChartView` inside the file.
No other changes. This preserves the Vega implementation for the Phase E3.5 visual
comparison gate. E3-3a and E3-3b are one atomic work unit; build gate is after E3-3b.

**Sub-step E3-3b — Write new `DistributionChartView`**

**File:** `modules/app/src/main/scala/app/views/DistributionChartView.scala` (new file)

Same synchronous ECharts init/dispose pattern from Phase E2. No hover bridge.
`instance.setOption(opts, notMerge = true)` on signal update (no re-init).
The `toggleEl` (PDF/CDF toggle buttons) and `coherenceCaptionSignal` caption are
unchanged — they are Laminar elements with no chart dependency.

The debounced fetch subscription in `onMountCallback` is unchanged.

Gate: `sbt test` green before proceeding.

### Step E3-4 — Delete `DistributionSpecBuilder`

Delete `modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala`.

Gate: `sbt test` green after deletion.

### Phase E3 — Integration verification (manual)

- [ ] Distribution preview renders for a lognormal leaf (PDF + CDF toggle).
- [ ] Distribution preview renders for an expert leaf with percentile anchors visible.
- [ ] Coherence echo caption appears correctly.
- [ ] Chart updates on debounced form input.

### Phase E3 — Files touched

| File | Change |
|------|--------|
| `app/chart/EChartsDistributionOptionBuilder.scala` | New (E3-1) |
| `app/test/…/EChartsDistributionOptionBuilderSpec.scala` | New (E3-2) |
| `app/views/VegaDistributionChartView.scala` | Renamed from `DistributionChartView.scala` (E3-3a); deleted in Phase E3.5 |
| `app/views/DistributionChartView.scala` | New ECharts implementation (E3-3b) |
| `app/chart/DistributionSpecBuilder.scala` | Deleted (E3-4, after E3-2 spec passes) |

---

## Phase E3.5 — Visual Comparison Gate

**Prerequisite:** Phases E2 and E3 approved and complete.

**Goal:** Render the new ECharts chart and the original Vega chart side-by-side for
visual sign-off before any Vega code is deleted. The new chart occupies the exact
original DOM position with all layout and resize behavior intact. The old chart is
rendered below it, unchanged, in an identical container. No changes are made to the
preserved Vega view files.

---

### Step E3.5-1 — Add comparison layout to `AnalyzeView`

**File:** `modules/app/src/main/scala/app/views/AnalyzeView.scala`

For each chart surface, render the new (ECharts) chart in its normal container — same
CSS classes, same enclosing div structure, same resize observers as today — then
render the old (Vega) chart immediately below in an identical container, with a
temporary comparison label. The old views receive the same data signals as the new ones.

```
// LEC surface
[new LECChartView — in the original container, no layout changes]
div(cls := "vega-comparison-panel")
  p("Vega original — visual comparison only (temporary)")
  VegaLECChartView(specSignal, new ChartHoverBridge())   // new bridge, not wired to tree

// Distribution surface  
[new DistributionChartView — in the original container, no layout changes]
div(cls := "vega-comparison-panel")
  p("Vega original — visual comparison only (temporary)")
  VegaDistributionChartView(response, viewMode, draft)   // unchanged implementation
```

**Constraints:**
- `VegaLECChartView` and `VegaDistributionChartView` are passed the same data signals
  as the new charts so both render the same data simultaneously.
- `VegaLECChartView` receives a fresh `new ChartHoverBridge()` not wired to
  `TreeDetailView` or `AnalyzeView`. The comparison is visual only; hover interaction
  in the Vega panel is not required.
- The `vega-comparison-panel` container must use the same CSS structure as the original
  chart container so both charts render at the same width and height.
- Zero changes to `VegaLECChartView.scala`, `VegaDistributionChartView.scala`,
  `ChartHoverBridge.scala`, or `DistributionChartState.scala`.

Gate: `sbt test` green. Both charts visible in browser.

### Step E3.5-2 — Visual sign-off checklist

- [ ] New LEC chart (ECharts): all curves render with correct colours
- [ ] Old LEC chart (Vega): renders identically to before the migration
- [ ] Curve colours, line shapes, and quantile annotations match visually between the two
- [ ] New distribution chart (ECharts): PDF and CDF mode render correctly
- [ ] Old distribution chart (Vega): renders identically to before the migration
- [ ] Anchor points (percentiles / min–max) match visually between the two
- [ ] Both charts update simultaneously when the same data changes
- [ ] New chart resize/responsive behaviour matches the original chart behaviour

Proceed to E3.5-3 only after all items are checked off.

### Step E3.5-3 — Remove comparison layout from `AnalyzeView`

**File:** `modules/app/src/main/scala/app/views/AnalyzeView.scala`

Remove the two `vega-comparison-panel` divs and their labels. The new ECharts charts
remain in their positions. `VegaLECChartView` and `VegaDistributionChartView` are no
longer instantiated anywhere.

Gate: `sbt test` green.

### Step E3.5-4 — Delete preserved Vega view files and `ChartHoverBridge`

- Delete `modules/app/src/main/scala/app/views/VegaLECChartView.scala`
- Delete `modules/app/src/main/scala/app/views/VegaDistributionChartView.scala`
- Delete `modules/app/src/main/scala/app/state/ChartHoverBridge.scala`

Gate: `sbt test` green.

### Phase E3.5 — Files touched

| File | Change |
|------|--------|
| `app/views/AnalyzeView.scala` | Add comparison layout (E3.5-1), remove it (E3.5-3) |
| `app/views/VegaLECChartView.scala` | Deleted (E3.5-4) |
| `app/views/VegaDistributionChartView.scala` | Deleted (E3.5-4) |
| `app/state/ChartHoverBridge.scala` | Deleted (E3.5-4) |

---

## Phase E4 — Vega Removal

**Prerequisite:** Phases E2, E3, and E3.5 approved and complete (both charts migrated and visually signed off).

**Goal:** Remove all Vega/Vega-Lite code and packages. After this phase, no Vega
code exists in the project.

### Step E4-1 — Remove NPM packages

**File:** `modules/app/package.json`

Remove `"vega"`, `"vega-embed"`, `"vega-lite"` from `dependencies`.  
Run `npm install` to update `package-lock.json`.

### Step E4-2 — Delete `VegaEmbed.scala`

Delete `modules/app/src/main/scala/app/facades/VegaEmbed.scala`.

### Step E4-3 — Verify no remaining Vega references

Grep for `vegaEmbed`, `EmbedResult`, `vega-embed`, `vega-lite`, `VegaEmbed` across
`modules/app/src/`. All results must be zero before proceeding.

### Phase E4 — Files touched

| File | Change |
|------|--------|
| `modules/app/package.json` | Remove Vega packages |
| `modules/app/package-lock.json` | Updated by `npm install` |
| `app/facades/VegaEmbed.scala` | Deleted |

---

## Phase E5 — Threshold-Linked Pie Chart

**Prerequisite:** Phase E4 approved and complete.

**Goal:** Add the interactive threshold decomposition view. The user moves the
crosshair on the LEC chart; a linked pie chart instantly shows each visible node's
contribution to P(loss > X) at the current threshold X. No server round-trip — the
pie is computed purely from the already-fetched `curveCache`.

---

### Decision Point E5-D1 — Pie focus selection: RESOLVED (Option B + drill-down)

**Decision:** Option B. The pie shows only direct children of a designated focus
portfolio. An explicit "focus" click target on a portfolio row in `TreeDetailView`
sets `focusPortfolioId: Var[Option[NodeId]]` in `LECChartState`. If no focus is set,
the pie shows an idle instruction.

#### Drill-down extension

**Behaviour (user-specified):** When the focus is on portfolio P, the pie shows P's
direct children as slices. If the user then expands a child portfolio C2 in
`TreeDetailView`, C2's slice is **replaced in place** by C2's own direct children.
Collapsing C2 in the tree re-aggregates the slice back to C2. This ties the pie
expansion state directly to `TreeViewState.expandedNodes` — no separate gesture.

**Implementation shape:** `ThresholdPieCalculator.compute` gains two extra parameters:

```scala
def compute(
  threshold:   Double,
  focusId:     NodeId,
  expandedIds: Set[NodeId],   // portfolios expanded in TreeDetailView
  tree:        RiskTree,      // to navigate parent-child relationships
  curves:      Map[NodeId, LECNodeCurve]
): Vector[(String, Double)]
```

Algorithm: for each direct child of `focusId`, if the child is a portfolio AND is in
`expandedIds`, substitute it with slices for each of its own direct children;
otherwise emit a single slice for the child. One level of expansion only.

**Curve fetching:** When `focusPortfolioId` changes, any direct children of the new
focus not already in `curveCache` are auto-fetched. When a child portfolio becomes
expanded, any of its direct children not in `curveCache` are auto-fetched. Both
are reactive triggers on signal changes.

**Implementation note — normalisation:** ECharts `pie` normalises all slices to 100%.
After replacing C2 with C2a + C2b, the proportions of the remaining sibling slices
(C1, C3) will shift because `P(C2 > X) ≠ P(C2a > X) + P(C2b > X)` in general (the
children are individual independent exceedance probabilities, not a partition of the
parent's value). This is expected and acceptable — the pie is a proportional comparison
of individual exceedance values, not a true probabilistic decomposition. A tooltip
showing the raw probability value alongside the percentage prevents misinterpretation.

**`TreeViewState` reference:** `LECChartState` already receives `selectedTree` from
`TreeViewState`. The `expandedNodes` signal is threaded through the same constructor
parameter pattern (or passed into `AnalyzeView` and combined there before computing
`pieDataSignal`).

---

### Decision Point E5-D2 — Pie panel placement: RESOLVED (Option C — in-chart overlay)

**Decision:** The pie lives **inside the same ECharts instance** as the LEC line chart,
positioned in the top-right corner of the canvas where LEC curves rarely reach (curves
cluster bottom-left). No extra DOM element, no split-pane layout change.

ECharts natively supports multiple coordinate systems in one instance. A `pie` series
with explicit `center` and `radius` coexists with the `line` series (which uses a
`grid` coordinate system). Example positioning:

```javascript
{
  type: 'pie',
  center: ['82%', '18%'],   // top-right quadrant
  radius: ['12%', '28%'],   // inner/outer radius (doughnut style)
  data: [...]
}
```

Both series share the same `tooltip`, the same `updateAxisPointer` event, and the
same `setOption` call. When no threshold is active, `pie.data` is set to `[]` to
hide the pie (no instance re-init needed).

**Why the original Option A implied less space:** Option A in this plan described
placing the pie in a separate sibling div below the LEC chart (a vertical split), which
would reduce the LEC chart's allocated height. That concern is entirely moot with the
in-chart overlay — the LEC chart container retains its full size and the pie floats
over the unused top-right area.

---

### Step E5-1 — Axis pointer + focus state in `LECChartState`

**File:** `modules/app/src/main/scala/app/state/LECChartState.scala`

Add three fields and extend the constructor:

```scala
// Constructor gains one new parameter:
final class LECChartState(
  keySignal:      StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree:   StrictSignal[LoadState[RiskTree]],
  expandedNodes:  StrictSignal[Set[NodeId]],    // ← NEW: from TreeViewState.expandedNodes.signal
  globalError:    Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId] = () => None
)

// New vars:
val axisPointerXVar: Var[Option[Double]] = Var(None)
val focusPortfolioId: Var[Option[NodeId]] = Var(None)
```

`axisPointerXVar` is written by `LECChartView` via a callback.
`focusPortfolioId` is written via a callback supplied by `AnalyzeView`. Per ADR-019,
`TreeDetailView` must not call `.set(...)` on a `Var` it does not own. The correct
pattern: `TreeDetailView.apply` gains a new callback parameter:
```scala
onFocusPortfolio: Option[NodeId] => Unit = _ => ()
```
The portfolio-row focus click target in `TreeDetailView` calls `onFocusPortfolio(Some(nodeId))`
or `onFocusPortfolio(None)` — never touches `focusPortfolioId` directly.
`AnalyzeView` supplies the callback: `onFocusPortfolio = treeViewState.chartState.focusPortfolioId.set`.
Cleared in `reset()`.

`TreeViewState` passes `expandedNodes.signal` into the `LECChartState` constructor:
```scala
val chartState: LECChartState = LECChartState(
  keySignal, selectedTreeId.signal, selectedTree.signal,
  expandedNodes.signal,   // ← added
  globalError, userIdAccessor
)
```

*Note: The `updateAxisPointer` event is registered in `LECChartView` (Phase E2 already
enables the axis pointer via `tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } }`).
The view calls `onAxisPointerX: Option[Double] => Unit` callback — supplied by
`AnalyzeView` — which writes to this Var.*

### Step E5-2 — `ThresholdPieCalculator` (pure function)

**File:** `modules/app/src/main/scala/app/chart/ThresholdPieCalculator.scala`

```scala
object ThresholdPieCalculator:
  def compute(
    threshold:   Double,
    focusId:     NodeId,
    expandedIds: Set[NodeId],   // portfolios expanded in TreeDetailView
    tree:        RiskTree,      // to navigate parent-child relationships
    curves:      Map[NodeId, LECNodeCurve]
  ): Vector[(String, Double)]
```

Returns `(nodeName, exceedanceProbability)` pairs for the pie. For each direct child
of `focusId`:
- If the child is a leaf OR is not in `expandedIds` → one slice: interpolate `threshold`
  against the child's curve.
- If the child is a portfolio AND is in `expandedIds` → substitute with one slice per
  direct child of that portfolio (one level of drill-down only).

Nodes with no curve in `curves` are silently dropped. Probability is linearly
interpolated from the two adjacent `LECPoint` entries that bracket `threshold`:

```
given points p1, p2 where p1.loss <= threshold <= p2.loss:
  prob = p1.prob + (threshold - p1.loss) / (p2.loss - p1.loss) * (p2.prob - p1.prob)
```

Edge cases:
- `threshold <= curves.head.loss` → use first point's probability
- `threshold >= curves.last.loss` → `0.0` (beyond curve extent)
- Single-point curve → use that point's probability

### Step E5-3 — Pie data signal in `LECChartState`

**File:** `modules/app/src/main/scala/app/state/LECChartState.scala`

Add derived signal (Decision E5-D1 = Option B + drill-down):

```scala
val pieDataSignal: Signal[Option[Vector[(String, Double)]]] =
  axisPointerXVar.signal
    .combineWith(curveCache.signal, focusPortfolioId.signal, expandedNodes, selectedTree.signal)
    .map {
      case (Some(x), LoadState.Loaded(curves), Some(focusId), expanded, LoadState.Loaded(tree)) =>
        Some(ThresholdPieCalculator.compute(x, focusId, expanded, tree, curves))
      case _ => None
    }
```

`expandedNodes: StrictSignal[Set[NodeId]]` is threaded into `LECChartState` from
`TreeViewState` (same constructor-parameter pattern used for `selectedTree`).

The pie series data in `EChartsThresholdPieView` is set to `[]` when
`pieDataSignal` emits `None` (crosshair not on chart, no focus, or curves loading).

### Step E5-4 — Pie update loop inside `LECChartView`

**File:** `modules/app/src/main/scala/app/views/LECChartView.scala`

No separate view component — the pie is a series inside the same ECharts instance
(Decision E5-D2: in-chart overlay). Inside `LECChartView.onMountCallback`, after
initialising the ECharts instance, subscribe to `pieDataSignal.changes`:

```scala
// In LECChartView, after echartsInit:
pieDataSignal.changes.foreach {
  case Some(slices) =>
    val pieData = slices.map { case (name, prob) =>
      js.Dynamic.literal("name" -> name, "value" -> prob)
    }
    instance.setOption(
      js.Dynamic.literal(
        "series" -> js.Array(js.Dynamic.literal(
          "type" -> "pie",
          "center" -> js.Array("82%", "18%"),
          "radius" -> js.Array("12%", "28%"),
          "data"   -> pieData.toJSArray
        ))
      ),
      false  // notMerge = false → partial update, preserves line series
    )
  case None =>
    // Clear the pie: empty data array hides all slices
    instance.setOption(
      js.Dynamic.literal(
        "series" -> js.Array(js.Dynamic.literal(
          "type" -> "pie",
          "data"   -> js.Array()
        ))
      ),
      false
    )
}(owner)
```

`pieDataSignal` is passed into `LECChartView.apply` as a new parameter alongside the
existing `specSignal` and `hoverBridge`.

**Idle state:** when `pieDataSignal` emits `None` (no focus set, no crosshair, or
curves loading), the pie series data is set to `[]` — the pie vanishes without any
DOM change or re-init.

**Initial state:** when first mounting, include the pie series with `data: []` in the
full initial `setOption` call (inside `EChartsLECOptionBuilder.build`) so ECharts knows
the pie series exists before the first partial update.

### Step E5-5 — Wire `AnalyzeView`

**File:** `modules/app/src/main/scala/app/views/AnalyzeView.scala`

- Pass `treeViewState.chartState.pieDataSignal` into `LECChartView.apply` as the new
  `pieDataSignal` parameter.
- The `updateAxisPointer` event handler is registered inside `LECChartView` and writes
  to `treeViewState.chartState.axisPointerXVar` via a callback parameter (same
  pattern as `hoverBridge`). `AnalyzeView` supplies this callback:
  ```scala
  onAxisPointerX = x => treeViewState.chartState.axisPointerXVar.set(x)
  ```
- Supply the `onFocusPortfolio` callback to `TreeDetailView` (ADR-019 fix from E5-1):
  ```scala
  onFocusPortfolio = treeViewState.chartState.focusPortfolioId.set
  ```
- No new DOM element or split-pane change is needed — the pie floats inside the existing
  LEC chart canvas.

### Step E5-6 — Tests

**New file:** `modules/app/src/test/scala/app/chart/ThresholdPieCalculatorSpec.scala`

ZIO Test (Scala.js). Pure function tests — no DOM or ECharts instance involved.

Test cases:
1. Threshold exactly at a known curve point → returns that point's probability
2. Threshold between two points → linearly interpolated value
3. Threshold below first point → first point's probability (clamp)
4. Threshold above last point → 0.0
5. Focus portfolio with two leaf children → vector length 2
6. Focus portfolio with one expanded child portfolio → slices for grandchildren, not the child
7. Expanded child portfolio with no curves for its children → silently dropped
8. Zero-probability slice (prob = 0.0) → included in output (user can see zero contribution)
9. Node not in curves map → silently dropped (not in focus children)

### Phase E5 — Integration verification (manual)

- [ ] Move cursor over LEC chart → crosshair tracks along curves.
- [ ] Pie updates continuously as crosshair moves.
- [ ] Pie slices labelled with node names and percentages.
- [ ] Pie shows empty / instruction message when crosshair not on chart.
- [ ] Cursor leaving chart area → pie reverts to idle state.

### Phase E5 — Files touched

| File | Change |
|------|--------|
| `app/state/LECChartState.scala` | Add `axisPointerXVar`, `focusPortfolioId`, `pieDataSignal`; add `expandedNodes: StrictSignal[Set[NodeId]]` constructor param |
| `app/state/TreeViewState.scala` | Pass `expandedNodes.signal` into `LECChartState` constructor |
| `app/chart/ThresholdPieCalculator.scala` | New |
| `app/chart/EChartsLECOptionBuilder.scala` | Add empty pie series to initial option (for partial-update seeding) |
| `app/views/LECChartView.scala` | Add `pieDataSignal` + `onAxisPointerX` params; register `updateAxisPointer` event; subscribe to `pieDataSignal` for partial setOption |
| `app/views/AnalyzeView.scala` | Pass `pieDataSignal` + `onAxisPointerX` callback into `LECChartView` |
| `app/views/TreeDetailView.scala` | Add `onFocusPortfolio: Option[NodeId] => Unit` callback parameter; add focus click target on portfolio rows → calls `onFocusPortfolio` (never touches `focusPortfolioId` directly) |
| `app/test/…/ThresholdPieCalculatorSpec.scala` | New |

---

## Phase E6 — Scenario Comparison (DEFERRED PENDING DESIGN)

**Status:** Deferred. No decisions required for Phases E1–E5.

Phase E6 cannot be scoped until Tier 3 work begins, because the backend foundation
does not exist.

#### What "scenario" means in this codebase

A scenario is an **Irmin branch** (IMPLEMENTATION-PLAN.md Phase 7 §2403, Tier 3).
Irmin branches work like Git branches — edits on a scenario branch do not affect the
`main` branch. Scenarios can be compared (diff LECs at key percentiles) and optionally
merged back.

`ScenarioService` is listed under **Backend Services (Not Implemented)** in the
implementation plan. It is blocked on Irmin branching support and
`IrminClient.watch`. `ScenarioComparator.compare(a, b)` (planned to diff LECs at
p50/p90/p95/p99 between two tree variants) is also not yet implemented.

The sensitivity analysis feature documented in TODO.md explicitly depends on the same
infrastructure: "Phase 7: Scenario Branching (§2403) — `ScenarioComparator.compare(a, b)`
diffs LECs at p50/p90/p95/p99; this infrastructure is reusable for sensitivity."

#### Why deferral is the right call

Phase E5 already delivers the high-value interactive feature (threshold-linked pie) on
top of the existing single-tree API. Phase E6 adds multi-tree overlay but requires:

1. Irmin branching (`IrminClient` extensions: `getHistory`, `revert`, branch management)
2. `ScenarioService` Tier 3 backend service
3. `ScenarioComparator` — cross-tree LEC diffing

None of these exist. Attempting to scope Phase E6 now would produce a plan against a
non-existent API surface. Phase E6 will be scoped as a standalone plan when Tier 3
work begins.

#### Files-by-phase entry

| Phase | New files | Modified files | Deleted files |
|-------|-----------|----------------|---------------|
| E6 | TBD (deferred) | TBD | — |

---

## Summary: Files by Phase

| Phase | New files | Modified files | Deleted files |
|-------|-----------|----------------|---------------|
| E1 | `ECharts.scala` | `package.json` | — |
| E2 | `VegaLECChartView.scala` (renamed), `EChartsLECOptionBuilder.scala`, `EChartsHoverBridge.scala`, new `LECChartView.scala`, 2 test specs | `AnalyzeView.scala`, `LECChartState.scala` | `LECSpecBuilder.scala`, `ChartHoverBridgeSpec.scala` |
| E3 | `VegaDistributionChartView.scala` (renamed), `EChartsDistributionOptionBuilder.scala`, new `DistributionChartView.scala`, 1 test spec | `DistributionChartState.scala` | `DistributionSpecBuilder.scala` |
| E3.5 | — | `AnalyzeView.scala` (comparison added then removed) | `VegaLECChartView.scala`, `VegaDistributionChartView.scala`, `ChartHoverBridge.scala` |
| E4 | — | `package.json`, `package-lock.json` | `VegaEmbed.scala` |
| E5 | `ThresholdPieCalculator.scala`, 1 test spec | `LECChartState.scala`, `TreeViewState.scala`, `EChartsLECOptionBuilder.scala`, `LECChartView.scala`, `AnalyzeView.scala`, `TreeDetailView.scala` | — |
| E6 | TBD | TBD | — |

No server-side files are touched in any phase.

---

## Approval Checkpoint

- [ ] ADR compliance verified at planning stage — **awaiting user approval**
- [x] Decision E1-D1 resolved (Option A: full bundle; Option B follow-up tracked in TODO)
- [x] Decision E2-D1 resolved (Option B: drop toggle, hardcode `smooth: true, smoothMonotone: 'x'`)
- [x] Decision E5-D1 resolved (Option B: focus portfolio with drill-down via `expandedNodes`)
- [x] Decision E5-D2 resolved (Option C: in-chart pie overlay in top-right corner)
- [x] Decision E6-D1 resolved (deferred — Irmin branching Tier 3 prerequisite, not implemented)
- [ ] User approves this plan before any implementation begins
