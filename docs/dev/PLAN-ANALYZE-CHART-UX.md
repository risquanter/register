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
- modules/app/src/main/scala/app/chart/LecChartParams.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/ChartParamStore.scala
- modules/app/src/main/scala/app/components/LecChartControls.scala
- modules/app/src/main/scala/app/components/Icons.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/LECChartView.scala
- modules/app/src/test/scala/app/chart/LecChartParamsSpec.scala
- modules/app/styles/app.css

## Versioning

New user-facing SPA feature (zoom + fullscreen), no API change: PATCH on landing
(or MINOR if you'd rather mark the feature) — user's call. Mirror `APP_VERSION`
to `.env` and `.env.irmin`.

---

## Continuation — Option C: native Laminar toggle controls (replaces the CSS-reflow toggles-left)

### Why this supersedes the D1-A CSS reflow

vega-embed puts its `vega-embed` class **on the mount element itself**
(`div.lec-chart-container.vega-embed`) and renders the bound toggle inputs
(`.vega-bindings`) **inside** that same element — the element it measures for
`width:"container"`. So a CSS-only "toggles to the left column" cannot both move
the toggles and let the chart fill correctly: the toggles either don't move
(descendant selector never matched a single-element node) or they share the width
Vega is measuring. Option C removes the root cause: drop Vega's `bind` inputs and
render the toggles as native Laminar controls in our own left column, driving the
Vega view's signals directly. This also retires TODO item 35 (app-consistent
control styling).

### Design principles applied (reloaded skills)

- **ADR-019.** State Vars live in one owner (`ChartParamStore`); the control
  component is a pure derived view (Pattern 4) receiving `Signal`s + callbacks,
  owning no state. No `.now()` in any render pipeline — the Vega view is an
  imperative external object, updated only from lifecycle callbacks and `-->`
  observers (edges), mirroring the existing `var currentResult` edge pattern in
  `LECChartView`.
- **Pass 0a (domain typing).** The interpolation mode is a `String` with a fixed
  valid set → becomes `enum Interpolation`. The six annotation toggles become a
  closed `enum LecAnnotation` with an enabled-`Set`, not six parallel `Boolean`
  Vars.
- **Pass 0b / DRY.** The enums are the single source of truth for the signal
  names, labels, and defaults. `LECSpecBuilder` builds the signal declarations by
  iterating the enum values; the control panel renders by iterating the same
  values; `preservedParams` (a third copy of the name list) is deleted. Vega
  signal-name/value strings are used only at the `view.signal(...)` bridge (the
  third-party-bridge exception to within-domain adhesion).
- **JS-bridge isolation.** `ChartParams.applyTo(view)` is the one place raw
  signal names cross into Vega.

### New: `modules/app/src/main/scala/app/chart/LecChartParams.scala`

```scala
package app.chart

import scala.scalajs.js

enum Interpolation(val signalValue: String, val label: String):
  case Monotone  extends Interpolation("monotone", "Monotone")
  case Basis     extends Interpolation("basis", "Basis")
  case Linear    extends Interpolation("linear", "Linear")
  case StepAfter extends Interpolation("step-after", "Step after")

object Interpolation:
  val default: Interpolation = Monotone
  def fromSignal(s: String): Interpolation = values.find(_.signalValue == s).getOrElse(default)

/** A LEC annotation with its own show/hide toggle. `signalName` is the Vega
  * signal the spec's opacity `expr` reads; `defaultOn` is its initial state. */
enum LecAnnotation(val signalName: String, val label: String, val defaultOn: Boolean):
  case P90    extends LecAnnotation("showP90", "P90", false)
  case P95    extends LecAnnotation("showP95", "P95", true)
  case P99    extends LecAnnotation("showP99", "P99", false)
  case P995   extends LecAnnotation("showP995", "P99.5", false)
  case AAL    extends LecAnnotation("showAAL", "AAL", true)
  case NoLoss extends LecAnnotation("showNoLossProbability", "No-loss probability", true)

object LecAnnotation:
  val defaults: Set[LecAnnotation] = values.filter(_.defaultOn).toSet

/** The user's chart-control state, and the sole bridge that pushes it onto a
  * live Vega view. `toggle` is a pure method (unit-tested without a Var). */
final case class ChartParams(interpolation: Interpolation, annotations: Set[LecAnnotation]):
  def toggle(a: LecAnnotation): ChartParams =    // flip membership, pure
    copy(annotations = if annotations.contains(a) then annotations - a else annotations + a)
  def applyTo(view: js.Dynamic): Unit =
    try view.signal("interpolate", interpolation.signalValue) catch case _: Throwable => ()
    LecAnnotation.values.foreach { a =>
      try view.signal(a.signalName, annotations.contains(a)) catch case _: Throwable => ()
    }
    try { view.run(); () } catch case _: Throwable => ()

object ChartParams:
  val default: ChartParams = ChartParams(Interpolation.default, LecAnnotation.defaults)
```

### Rewrite: `modules/app/src/main/scala/app/state/ChartParamStore.scala`

State moves app-side; `capture`/`restore` (read-from / write-to the dying view)
are removed — the store is now the source of truth, not the Vega view.

```scala
final class ChartParamStore:
  private val state: Var[ChartParams] = Var(ChartParams.default)
  val signal: Signal[ChartParams] = state.signal
  def setInterpolation(i: Interpolation): Unit = state.update(_.copy(interpolation = i))
  def toggleAnnotation(a: LecAnnotation): Unit = state.update(_.toggle(a))
```

### New: `modules/app/src/main/scala/app/components/LecChartControls.scala`

Pure view (owns no state): a select + one checkbox per `LecAnnotation`,
rendered by iterating the enums. Styled with the app's own `form-*` classes.

```scala
object LecChartControls:
  def apply(params: Signal[ChartParams], store: ChartParamStore): HtmlElement
  // internally: select(controlled(value <-- params.map(_.interpolation.signalValue),
  //   onInput.mapToValue --> (s => store.setInterpolation(Interpolation.fromSignal(s)))),
  //   Interpolation.values.map(i => option(value := i.signalValue, i.label)))
  // and LecAnnotation.values.map(a => label(input(typ := "checkbox",
  //   controlled(checked <-- params.map(_.annotations.contains(a)),
  //     onInput.mapToChecked --> (_ => store.toggleAnnotation(a)))), span(a.label)))
```

### `LECSpecBuilder.scala`

- Remove the `interpolation: String` parameter from `build`/`buildFromSeries`
  (no caller passes it; the live value comes from `ChartParams.applyTo`). The
  spec declares the `interpolate` signal with `Interpolation.default.signalValue`.
- Build the toggle signal declarations by iterating `LecAnnotation.values`
  (`name` → `signalName`, `value` → `defaultOn`), **without** `bind`.
- Source the annotation layers' `toggleParam` and labels from `LecAnnotation`
  (single source of truth); the `quantiles` map key ("p90"…"p99.5") stays a
  local mapping to the enum case.
- Delete `preservedParams`. Keep the `grid` pan/zoom param on the point layer
  (unchanged).

### `LECChartView.scala`

- Content becomes chart-only (no `.vega-bindings` anywhere). Keep the header +
  fullscreen button + hover bridge.
- Replace `capture`/`restore` with: a mounted subscription
  `paramStore.signal --> { p => latestParams = p; currentResult.foreach(r => p.applyTo(r.view)) }`
  (edge, no `.now()`), plus `latestParams.applyTo(view)` in `onResult` so a
  freshly embedded view also gets the current values. `latestParams` is a
  component-local `var`, same edge pattern as `currentResult`.

### `AnalyzeView.scala`

- Render `LecChartControls(chartParams.signal, chartParams)` **once**, in a left
  column beside the whole chart area (so it applies in both single/overlay and
  side-by-side layouts, driving the one shared `chartParams`). The chart area
  (single surface or panel grid) sits to its right and fills the rest.
- `panelSpec` / the `build`/`buildFromSeries` call sites drop the (now removed)
  interpolation argument — none pass it today, so this is mechanical.
- Panels render no controls of their own; each panel's `LECChartView` reacts to
  the shared store via its subscription.

### `app.css`

- Replace the (non-matching) `.lec-chart-surface .vega-embed` toggles-left rules
  with a real flex row on the chart area: `LecChartControls` left column
  (auto width), chart right (`flex: 1`). Fix the single-chart vertical sizing so
  the bottom gap is gone. Style `.lec-chart-controls` with `form-*` variables.
  Keep the fullscreen/responsive rules.

### Tests — `modules/app/src/test/scala/app/chart/LecChartParamsSpec.scala`

Pure (no DOM/Vega): `ChartParams.default` matches the enum defaults;
`toggleAnnotation` adds then removes (idempotent round-trip); enum
`signalName`/`signalValue`/`label` mappings are stable; `LecAnnotation.defaults`
= the `defaultOn` set. (`applyTo` is a JS-view side effect, exercised in the
manual pass, not unit-tested.)

### ADR alignment

- **ADR-019:** compliant — single Var owner, pure control view with
  signals+callbacks, no `.now()` in render pipelines, Vega updated only at edges.
- No API/DTO/endpoint/service/domain change — SPA only. No new dependency.

### Open decisions

None — Option C is the ruled approach; the placement of the control column
(left of the whole chart area, shared across layouts) follows from "one chart's
worth of settings," not a fresh choice.

### Verification

`sbt app/compile` (zero new warnings) + `sbt app/test` (adds `LecChartParamsSpec`)
green; then the manual pass at localhost:18080: toggles render as app-styled
controls in a left column and drive the chart live (single, overlay, and every
side-by-side panel), the chart fills the freed width with no bottom gap, zoom +
fullscreen still work.
