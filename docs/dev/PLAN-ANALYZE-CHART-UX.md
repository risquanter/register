# PLAN — Analyze LEC chart UX (toggles-left, responsive fill, zoom, fullscreen)

A distinct workstream from Phase E (history/time-travel): Analyze LEC chart
interaction polish. Design decisions are user-ruled (2026-07-28):

- **D1 = Option A** — move the Vega-Lite bound toggles to a left column via CSS
  and make the chart width responsive so it fills the panel. (Option C — native
  Laminar controls — deferred to `TODO.md` item 35.)
- **D2 = (i) + (ii)** on the shared prerequisite **(iii)**:
  - (iii) responsive `width`/`height: "container"` so the chart fills its box.
  - (i) in-place pan/zoom via a `bind: "scales"` interval selection.
  - (ii) an expand/fullscreen button on the chart.

Scope: the single/overlay Analyze chart surface only. The side-by-side panels
keep their fixed size and their toggles below — they are small, use pinned axes
for comparison (which interactive zoom would defeat), and a per-panel left
column would not fit.

## Signatures

### `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`

Two additive flags thread through `build` / `buildFromSeries` / `buildSpec` /
`emptySpec` (all default `false`, so the fixed-size side-by-side panels are
unchanged):

```scala
def build(
  curves: Vector[(LECNodeCurve, HexColor)],
  interpolation: String = "monotone",
  width: Int = 950, height: Int = 400,
  pinned: Option[PinnedAxes] = None,
  responsive: Boolean = false,   // width & height → "container"
  zoomable: Boolean = false      // add the bind:"scales" interval param
): js.Dynamic

def buildFromSeries(
  curves: Vector[(LECNodeCurve, HexColor, String)],
  interpolation: String = "monotone",
  width: Int = 950, height: Int = 400,
  pinned: Option[PinnedAxes] = None,
  responsive: Boolean = false,
  zoomable: Boolean = false
): js.Dynamic

private def buildSpec(curves, interpolation, width, height, pinned, responsive: Boolean, zoomable: Boolean): js.Dynamic
private def emptySpec(width: Int, height: Int, responsive: Boolean): js.Dynamic
```

Inside `buildSpec`/`emptySpec`, the size fields become:

```scala
"width"  -> (if responsive then ("container": js.Any) else (width: js.Any)),
"height" -> (if responsive then ("container": js.Any) else (height: js.Any)),
```

And, when `zoomable`, the point layer's `params` array (which already carries
the `hover` selection) gains the zoom param:

```scala
val pointParams = js.Array[js.Any](/* hover selection, as today */)
if zoomable then
  pointParams.push(js.Dynamic.literal("name" -> "grid", "select" -> js.Dynamic.literal("type" -> "interval", "encodings" -> js.Array("x", "y")), "bind" -> "scales"))
// ... pointLayer: "params" -> pointParams
```

The selection lives on a single layer, not the top-level `params`: a
`bind:"scales"` interval selection placed at the top of a layered spec is pushed
into every layer sharing the axes, declaring its `grid_x`/`grid_y` signals more
than once ("Duplicate signal name: grid_x"). Defined on one layer it is
generated once, and because the scales are shared across layers, pan/zoom still
applies to the whole chart.

`bind: "scales"` on an interval selection gives drag-to-pan + wheel-to-zoom on
the two continuous scales (x = loss, y = exceedance). Vega resets the view on
double-click. Zoom state is not added to `ChartParamStore.preservedParams`, so a
new selection/re-embed resets the zoom — acceptable (the data changed).

### Call sites — the single/overlay chart passes `responsive = true, zoomable = true`

- `modules/app/src/main/scala/app/state/LECChartState.scala` — `specSignal`'s
  `LECSpecBuilder.build(...)` gains `responsive = true, zoomable = true`.
- `modules/app/src/main/scala/app/views/AnalyzeView.scala` — `combinedSpecSignal`'s
  `LECSpecBuilder.buildFromSeries(...)` gains `responsive = true, zoomable = true`.
  `panelSpec` (side-by-side) is untouched — defaults keep fixed size, no zoom.

### `modules/app/src/main/scala/app/views/LECChartView.scala` — fullscreen button

A header row (the existing `h3` + a new button); the root `.lec-chart-view`
element is captured on mount and toggled via the Fullscreen API. With
`responsive`, the chart reflows to fill the fullscreen element (vega-embed's
`width:"container"` uses a ResizeObserver).

```scala
def apply(specSignal, hoverBridge, paramStore): HtmlElement =
  var rootRef: dom.Element = null                       // captured on mount
  val isFullscreen: Var[Boolean] = Var(false)
  div(
    cls := "lec-chart-view",
    onMountCallback(ctx => rootRef = ctx.thisNode.ref),
    // keep the button icon in sync with actual fullscreen state (Esc, etc.)
    com.raquo.airstream.web.DomEventStream[dom.Event](dom.document, "fullscreenchange", useCapture = false)
      --> { _ => isFullscreen.set(dom.document.fullscreenElement != null) },
    div(cls := "lec-chart-header",
      h3("LEC Chart"),
      button(
        cls := "chart-fullscreen-btn",
        tpe := "button",
        title <-- isFullscreen.signal.map(if _ then "Exit fullscreen" else "Fullscreen"),
        child <-- isFullscreen.signal.map(if _ then Icons.minimize("chart-icon") else Icons.maximize("chart-icon")),
        onClick --> { _ =>
          if dom.document.fullscreenElement == null then rootRef.requestFullscreen()
          else dom.document.exitFullscreen(): Unit
        }
      )
    ),
    div(cls := "lec-chart-content", /* … unchanged … */)
  )
```

(If `Icons.maximize`/`minimize` don't exist, add them to `Icons.scala` — listed
in the inventory — or fall back to a text glyph `⤢` / `⤡`.)

### `modules/app/styles/app.css`

- Header row: `.lec-chart-header { display: flex; align-items: center; justify-content: space-between; }` (h3 keeps its style; new `.chart-fullscreen-btn` styled like the app's icon buttons).
- Toggles-left + responsive fill on the single chart's embed:
  ```css
  .lec-chart-container .vega-embed { display: flex; flex-direction: row; align-items: flex-start; gap: var(--sp-3); width: 100%; height: 100%; }
  .lec-chart-container .vega-bindings { order: -1; flex: 0 0 auto; display: flex; flex-direction: column; gap: var(--sp-1); }
  .lec-chart-container .vega-embed > :not(.vega-bindings) { flex: 1 1 auto; min-width: 0; }
  ```
- Fullscreen surface: `.lec-chart-view:fullscreen { background: var(--background); padding: var(--sp-4); }` (+ ensure the content/container flex to fill).
- Scope the flex-row rule so it does NOT apply under `.analyze-lec-panel .lec-panel` (side-by-side panels keep their existing stacked layout).

## Open decisions

None — D1-A and D2 (i/ii/iii) are ruled; side-by-side stays as-is (stated
above, not a fresh choice).

## ADR alignment

- **ADR-019**: `LECChartView` stays a pure derived view; the only added state is
  a component-local `isFullscreen` `Var` (presentation-only, not cross-component)
  and a mount-captured element ref — no `.now()` in a render pipeline, no shared
  mutable state. Compliant.
- No API, DTO, endpoint, service, or domain-type change — SPA chart rendering
  only. No new dependency (Fullscreen API and `bind:"scales"` are
  browser/Vega-Lite built-ins already in use).

## Verification

```bash
sbt app/compile      # zero new warnings
sbt app/test         # unchanged suites (no new pure logic; the flags are wiring)
```

- Manual (localhost:18080, rebuilt frontend): toggles render in a left column and
  the single/overlay chart fills the freed width; wheel-zoom + drag-pan work on
  the main chart and double-click resets; the fullscreen button enlarges the
  chart to the screen and Esc restores it; the interpolation/annotation toggles
  still drive the chart in every state. Side-by-side panels unchanged (fixed
  size, toggles below, no zoom).
- CSS note: the exact flex/`width:"container"` interplay (and fullscreen
  fill) is verified and tuned in the manual pass — the spec/flags are the
  contract; pixel-level CSS may iterate there.

## File inventory

- modules/app/src/main/scala/app/chart/LECSpecBuilder.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/LECChartView.scala
- modules/app/src/main/scala/app/components/Icons.scala
- modules/app/styles/app.css

## Versioning

New user-facing SPA feature (zoom + fullscreen), no API change: PATCH on landing
(or MINOR if you'd rather mark the feature) — user's call. Mirror `APP_VERSION`
to `.env` and `.env.irmin`.
