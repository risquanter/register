# PLAN — Analyze LEC chart UX (toggles-left, responsive fill, zoom, fullscreen)

Status: CLOSED — all sections and continuations landed and committed (0.10.5
through 0.10.13); every "Open decisions" subsection resolved to None. Working
tree clean.

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
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/src/main/scala/app/state/ChartParamStore.scala
- modules/app/src/main/scala/app/state/ChartHoverBridge.scala
- modules/app/src/main/scala/app/state/FormState.scala
- modules/app/src/main/scala/app/core/JsBoundary.scala
- modules/app/src/main/scala/app/components/LecChartControls.scala
- modules/app/src/main/scala/app/components/Icons.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/LECChartView.scala
- modules/app/src/test/scala/app/chart/LecChartParamsSpec.scala
- modules/app/src/test/scala/app/core/JsBoundarySpec.scala
- modules/app/src/main/scala/app/chart/VegaSpecShared.scala
- modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala
- modules/app/src/main/scala/app/chart/PaletteData.scala
- modules/app/src/main/scala/app/chart/CompareColorAssigner.scala
- modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala
- modules/app/src/main/scala/app/core/NumberFormat.scala
- modules/app/src/main/scala/app/state/RiskLeafFormState.scala
- modules/app/src/test/scala/app/core/NumberFormatSpec.scala
- modules/app/styles/app.css
- modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala
- modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala
- build.sbt

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
Takes signals + callbacks (not the store), matching the `SlotPalettePicker`
convention.

```scala
object LecChartControls:
  def apply(
    params: Signal[ChartParams],
    onSetInterpolation: Interpolation => Unit,
    onToggle: LecAnnotation => Unit
  ): HtmlElement
  // internally: select(controlled(value <-- params.map(_.interpolation.signalValue),
  //   onInput.mapToValue --> (s => onSetInterpolation(Interpolation.fromSignal(s)))),
  //   Interpolation.values.map(i => option(value := i.signalValue, i.label)))
  // and LecAnnotation.values.map(a => label(input(typ := "checkbox",
  //   controlled(checked <-- params.map(_.annotations.contains(a)),
  //     onInput.mapToChecked --> (_ => onToggle(a)))), span(a.label)))
// call site: LecChartControls(chartParams.signal, chartParams.setInterpolation, chartParams.toggleAnnotation)
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

---

## Continuation — ADR-033 implementation (JsBoundary helper, Try migrations, citation fixes)

Implements ADR-033 across the code the audit flagged. User rulings: MetalogDistribution
accepted as conform (documented in ADR-033's table); the `Try` sites migrate to
throw-free/named-catch forms; the JS-boundary catches route through the shared helper.

### Signatures

#### NEW `modules/app/src/main/scala/app/core/JsBoundary.scala`

```scala
package app.core

object JsBoundary:
  /** The one sanctioned `catch Throwable` site (ADR-033 §4): converts ANY
    * throwable — including Scala.js `UndefinedBehaviorError`, which `NonFatal`
    * and named types miss — into the total fallback. Use only at a
    * Scala.js ↔ JS interop edge. */
  inline def orElse[A](inline fallback: A)(inline body: A): A =
    try body catch case _: Throwable => fallback
```

#### `modules/app/src/main/scala/app/state/ChartHoverBridge.scala`

```scala
def parseHoverSignal(value: js.Dynamic): Option[NodeId] =
  JsBoundary.orElse(Option.empty[NodeId]) {
    val arr = value.asInstanceOf[js.Array[js.Dynamic]]
    if arr.length > 0 then
      val values = arr(0).values.asInstanceOf[js.Array[String]]
      if values.length > 0 then NodeId.fromString(values(0)).toOption else None
    else None
  }
```

Import `app.core.JsBoundary`; the "Catches `Throwable`, not `NonFatal`" scaladoc
paragraph is replaced by a one-line ADR-033 §4 citation (the helper carries the
explanation).

#### `modules/app/src/main/scala/app/chart/LecChartParams.scala` (`ChartParams.applyTo`)

```scala
def applyTo(view: js.Dynamic): Unit =
  JsBoundary.orElse(()) { view.signal("interpolate", interpolation.signalValue); () }
  LecAnnotation.values.foreach { a =>
    JsBoundary.orElse(()) { view.signal(a.signalName, annotations.contains(a)); () }
  }
  JsBoundary.orElse(()) { view.run(); () }
```

Same scaladoc treatment (per-signal-guard rationale stays; width rationale → ADR-033 §4).

#### `modules/app/src/main/scala/app/state/FormState.scala` — throw-free (ADR-033 §2)

```scala
protected def parseDouble(s: String): Option[Double] = s.trim.toDoubleOption
protected def parseLong(s: String): Option[Long]     = s.trim.toLongOption
```

(`scala.util.Try` import dropped. Semantics identical for every reachable input.)

#### `modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala` (`toIso`)

```scala
def toIso(date: String): String =
  date.trim.toLongOption.fold(date) { epoch =>
    try Instant.ofEpochSecond(epoch).toString
    catch case _: DateTimeException => date   // epoch outside Instant's range
  }
```

`import scala.util.Try` → `import java.time.DateTimeException`. Same pass-through
behaviour for non-numeric and out-of-range input.

#### `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala` (`buildDuration`)

```scala
private def buildDuration(days: String, hours: String, minutes: String, seconds: String, original: String): Either[String, Duration] =
  (days.toLongOption, hours.toLongOption, minutes.toLongOption) match
    case (Some(daysPart), Some(hoursPart), Some(minutesPart)) =>
      try
        val secondsPart = BigDecimal(seconds)
        val nanosPerSecond = BigDecimal(1000000000L)
        val wholeSeconds = secondsPart.setScale(0, BigDecimal.RoundingMode.DOWN).toLongExact
        val nanos = ((secondsPart - BigDecimal(wholeSeconds)) * nanosPerSecond)
          .setScale(0, BigDecimal.RoundingMode.HALF_UP)
          .toLongExact
        Right(
          Duration.ofDays(daysPart).plusHours(hoursPart).plusMinutes(minutesPart)
            .plusSeconds(wholeSeconds).plusNanos(nanos))
      catch case _: ArithmeticException | _: NumberFormatException =>
        Left(s"Invalid interval: $original")
    case _ => Left(s"Invalid interval: $original")
```

Named coverage: `toLongExact`/`Duration.plus*` → `ArithmeticException`;
`BigDecimal(seconds)` → `NumberFormatException` (regex-constrained input, kept for
sound coverage). Behaviour note: the `Left` message becomes the uniform
`"Invalid interval: $original"` instead of sometimes echoing an exception message —
same `Either` shape, internal config-parse consumer.

#### Stale ADR citations (comment-only, one line each)

- `modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala` — "(ADR-010 §3)" → "(ADR-033 §5)"
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala` — "invariant (ADR-010)" → "invariant (ADR-033 §5)"
- `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala` — "public API (ADR-010)" → "public API (ADR-033 §3)"

#### NEW test `modules/app/src/test/scala/app/core/JsBoundarySpec.scala`

Pure: body value passes through when nothing throws; fallback on a thrown
`RuntimeException`; fallback on a thrown `java.lang.Error` (demonstrates the
catch-all width that `NonFatal` would refuse). `TreeHistoryServiceSpec` gains
`toIso` cases: numeric epoch → ISO, non-numeric → pass-through, out-of-range
epoch → pass-through.

### ADR alignment

- **ADR-033**: this is its implementation — helper (§4), throw-free parsing (§2), named catches (§3). Compliant.
- **ADR-010**: error values/shapes unchanged except the `buildDuration` message noted above. Compliant.
- **ADR-001/ADR-011**: no new types, no new dependencies; imports adjusted per convention. Compliant.

### Open decisions

None — all five audit/sweep decisions ruled 2026-08-07.

### Verification

```bash
sbt 'commonJVM/test; server/test'   # common + server touched
sbt app/test
sbt serverIt/test                   # full tier — server touched
```

All green before done; leaked `register_it_` containers cleaned before serverIt.

### Versioning

Shipped code changes (refactor, no external API change): PATCH on landing, mirror
`APP_VERSION` to `.env` and `.env.irmin`.

### File inventory additions — merged into `## File inventory` upon approval

- modules/app/src/main/scala/app/core/JsBoundary.scala
- modules/app/src/main/scala/app/state/FormState.scala
- modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala
- modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala
- modules/app/src/test/scala/app/core/JsBoundarySpec.scala
- build.sbt

---

## Continuation — Chart-spec deduplication (shared theme, folded annotation layers, one percent formatter)

Retires the chart-code dedup items from the 2026-07-23 review backlog. Three
duplications, one root design: the Vega building blocks that both spec builders
need live once, in one shared object; the per-annotation layer explosion is
folded into datum-driven shared layers at the same time (same mechanism: data
carries what layers used to hard-code).

Already moot from that backlog list (deleted by the Option C rewrite): the six
`bind`-toggle param literals and `preservedParams`.

### Step 1 — NEW `modules/app/src/main/scala/app/chart/VegaSpecShared.scala`

```scala
package app.chart

import scala.scalajs.js

/** Shared Vega-Lite building blocks for every chart surface (LEC chart +
  * distribution preview): the dark theme, the loss-axis label formatting,
  * datum-driven vertical rule/label annotation layers, and the no-data spec. */
object VegaSpecShared:

  /** App dark-theme Vega config — font stack, label/title colours and sizes.
    * Single source of truth (was duplicated and drifted in
    * `DistributionSpecBuilder.darkConfig`). Content: the current
    * `LECSpecBuilder` config verbatim (font `'Geist', ui-sans-serif, ...`,
    * legend/axis label sizes 12/13, axis labelColor #c8ced0). */
  def darkConfig: js.Dynamic

  /** X-axis B/M `labelExpr` shared by all loss axes (was duplicated between
    * the LEC line layer's x-axis and `DistributionSpecBuilder.xEncoding`). */
  val lossAxisLabelExpr: String =
    "if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')"

  /** One vertical rule + its stacked text label, as data. `visibilityKey` is
    * the annotation-toggle signal name gating it (a `LecAnnotation.signalName`);
    * `None` = always visible (distribution anchors). */
  final case class VerticalRuleDatum(
    x: Double,
    labelLines: Seq[String],
    ruleColor: String,
    textColor: String,
    dashed: Boolean,
    visibilityKey: Option[String]
  )

  /** TWO layers — one rule layer + one text layer — covering ALL given data.
    * Replaces the per-annotation layer pairs (the layer-count fold: colour,
    * dash, label and toggle come from datum fields with `scale: null` /
    * conditions; visibility is one chained expr over `LecAnnotation.values`
    * mapping `datum.toggle` to its signal, defaulting to visible when the
    * datum has no toggle). Text styling params default to the LEC chart's
    * values; the distribution preview passes its own. */
  def verticalRuleLayers(
    data: Vector[VerticalRuleDatum],
    fontSize: Int = 13,
    textDx: Int = 4,
    textDy: Int = 4,
    baseline: String = "top"
  ): Seq[js.Dynamic]

  /** Text-only spec for the no-data state (was duplicated `emptySpec`s). */
  def emptyMessageSpec(
    width: Int,
    height: Int,
    responsive: Boolean,
    message: String,
    fontSize: Int = 11
  ): js.Dynamic
```

Datum fields on the folded layers: `x`, `label` (array → stacked lines),
`ruleColor`/`textColor` (via `scale: null`), `dashed` (strokeDash condition),
`toggle` (signal name or absent). The visibility expr is generated from
`LecAnnotation.values` — e.g.
`datum.toggle == 'showP90' ? (showP90 ? 1 : 0) : ... : 1` — so the enum stays
the single source of truth for toggle signals.

### Step 2 — NEW `modules/app/src/main/scala/app/core/NumberFormat.scala`

```scala
package app.core

object NumberFormat:
  /** A 0–1 domain value as its 0–100 percent string (no "%" suffix), rounded
    * HALF_UP to `decimals` places via BigDecimal (no floating-point noise),
    * trailing zeros stripped. Single source for percent display (form fields
    * and chart labels). */
  def percentValue(p: Double, decimals: Int): String =
    BigDecimal(p * 100.0)
      .setScale(decimals, scala.math.BigDecimal.RoundingMode.HALF_UP)
      .underlying.stripTrailingZeros.toPlainString
```

- `RiskLeafFormState.domainToDisplayPct(p, decimals)` keeps its name and
  callers (`FormMode` ×2) and delegates to `NumberFormat.percentValue`.
- `LECSpecBuilder.formatProbability(p)` becomes
  `s"${NumberFormat.percentValue(p, 0)}%"` — output identical (`math.round`
  and HALF_UP agree on non-negative input).

### Step 3 — `LECSpecBuilder.scala` (already in the file inventory)

- Config literal → `VegaSpecShared.darkConfig`; x-axis labelExpr →
  `VegaSpecShared.lossAxisLabelExpr`; `emptySpec` → `VegaSpecShared.emptyMessageSpec(width, height, responsive, "No data available")`.
- Delete private `verticalAnnotation`; the per-curve quantile/AAL loop now
  builds `Vector[VerticalRuleDatum]` (quantiles dashed, AAL solid, rule and
  text colour = the curve's hex, `visibilityKey = Some(ann.signalName)`) and
  pushes the TWO layers from `VegaSpecShared.verticalRuleLayers(...)`.
- Delete private `noLossStat`; replace with private `noLossLayer(rows: Vector[(String, String)]): js.Dynamic`
  — ONE text layer whose data rows carry `(label, color)` and a per-row pixel
  `y` (field with `scale: null`), gated by the NoLoss signal. LEC-only, so it
  stays private here rather than in `VegaSpecShared`.
- Layer count for a full chart: line + point + 2 rule/label layers + 1 no-loss
  layer = 5, was 2 + up-to-11 per curve (145 at the 13-curve cap).
- `formatLossValue` stays as-is (its tier-boundary rounding is a separate
  backlog item, not this scope).

### Step 4 — `DistributionSpecBuilder.scala`

- Delete the local `darkConfig` (the diverged copy) → `VegaSpecShared.darkConfig`.
  Drop the per-encoding `labelColor`/`titleColor` literals in `xEncoding` and
  the y-encodings so the shared config governs.
- Delete `ruleAnnotation`; anchors build `Vector[VerticalRuleDatum]`
  (`ruleColor = "#6a8a8e"`, `textColor = "#a0b0b0"`, `dashed = true`,
  `visibilityKey = None`) → `VegaSpecShared.verticalRuleLayers(data, fontSize = 11, textDx = 4, textDy = -6, baseline = "middle")`.
- `emptySpec` → `VegaSpecShared.emptyMessageSpec(width, height, responsive = false, message = "Enter distribution parameters to see a preview", fontSize = 14)`.
- `cdfAnchorDot` stays (no counterpart anywhere).

### Behaviour changes (explicit, part of this approval)

1. **Distribution preview adopts the shared theme**: app font stack, axis/legend
   label sizes 12/13, axis labelColor `#c8ced0` (brighter). The old local copy
   was drift — the LEC values are the deliberate readability improvements that
   never reached the preview chart. Visual-only; no data or interaction change.
2. **LEC annotation layers are folded**: rendered marks, colours, toggles, and
   labels are the same by construction (same values, moved from per-layer
   literals into datum fields); verified in the manual pass.
3. Everything else is output-identical (percent strings, empty-state specs,
   axis formatting).

### ADR alignment

- **ADR-019**: untouched — pure spec builders, no state, no `.now()`.
- **ADR-001 / Pass 0a**: the raw `String` colours/exprs are Vega-edge literals
  (third-party bridge exception); no domain value loses typing.
- No API/DTO/endpoint/service change; no new dependency. Both new objects have
  immediate call sites (no dead code).

### Open decisions

None — the behaviour changes above are stated consequences of unifying on the
current LEC theme. If the preview chart should instead keep its exact current
look, that is a parameterization of `darkConfig`, not a different structure.

### Verification

```bash
sbt app/compile   # zero new warnings
sbt app/test      # adds NumberFormatSpec; existing suites unchanged
sbt 'commonJVM/test; server/test'
sbt serverIt/test
```

Manual at localhost:18080 (rebuilt frontend): LEC chart pixel-comparable to
today (annotations per curve, toggles, hover, legend, zoom); distribution
preview (Design → leaf form) renders with the app font and brighter labels,
anchors and dots unchanged in position.

### Versioning

Refactor of shipped code, no external API change: PATCH on landing; mirror
`APP_VERSION` to `.env` and `.env.irmin`.

### File inventory additions — merged into `## File inventory` upon approval

- modules/app/src/main/scala/app/chart/VegaSpecShared.scala
- modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala
- modules/app/src/main/scala/app/core/NumberFormat.scala
- modules/app/src/main/scala/app/state/RiskLeafFormState.scala
- modules/app/src/test/scala/app/core/NumberFormatSpec.scala

---

## Continuation — Annotations follow hover (Option A) + informative overlay legend

Two user-ruled chart refinements (2026-08-08). Option A: hovering a curve keeps
its own statistics annotations (quantile/AAL rules, no-loss row) at full
opacity and dims every other curve's annotations to the same ~20% the lines
use; no hover → all full (today's look). Legend: overlay compare entries name
the side as "branch · tree" instead of the internal slot label ("active"/"s1").

### `modules/app/src/main/scala/app/chart/VegaSpecShared.scala`

```scala
final case class VerticalRuleDatum(
    x: Double,
    labelLines: Seq[String],
    ruleColor: String,
    textColor: String,
    dashed: Boolean,
    visibilityKey: Option[String],
    seriesId: Option[String]        // NEW: the chart series this annotation belongs
                                    // to (hover identity); None = not hover-linked
)

def verticalRuleLayers(
    data: Vector[VerticalRuleDatum],
    fontSize: Int = 13,
    textDx: Int = 4,
    textDy: Int = 4,
    baseline: String = "top",
    hoverParam: Option[String] = None  // NEW: Some("hover") = dim to 0.2 when
                                       // another series is hovered (Option A)
): Seq[js.Dynamic]
```

With `hoverParam = Some(p)`, the datums gain a `curveId` field (from
`seriesId`) and opacity moves from the mark-level expr to an encoding-level
condition list — first match wins, mirroring the line layer's own hover
conditions:

```
"opacity": {"condition": [
    {"test": "<toggle-off chain over LecAnnotation>", "value": 0},
    {"param": p, "empty": false, "value": 1},
    {"test": "length(data('<p>_store')) == 0", "value": 1}
  ], "value": 0.2}
```

With `hoverParam = None` (distribution preview): exactly today's toggle-only
mark expr — the preview spec has no hover store, so the hover conditions must
not be emitted there.

### `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`

```scala
def build(
  curves: Vector[(LECNodeCurve, HexColor)],
  width: Int = 950, height: Int = 400,
  pinned: Option[PinnedAxes] = None,
  responsive: Boolean = false, zoomable: Boolean = false
): js.Dynamic =
  buildFromSeries(curves.map { case (nc, c) => (nc, c, nc.id.value, nc.name) }, width, height, pinned, responsive, zoomable)

def buildFromSeries(
  curves: Vector[(LECNodeCurve, HexColor, String, String)],  // (curve, colour, series id, legend label)
  width: Int = 950, height: Int = 400,
  pinned: Option[PinnedAxes] = None,
  responsive: Boolean = false, zoomable: Boolean = false
): js.Dynamic
```

- The legend `labelExpr` maps series id → the given legend label verbatim
  (escaped); the current '@'-suffix parsing is deleted (the caller now says
  what the legend shows).
- Annotation datums carry `seriesId = Some(curveId)`;
  `verticalRuleLayers(..., hoverParam = Some("hover"))`.
- `private def noLossLayer(rows: Vector[(String, String, String)], fontSize: Int = 13): Option[js.Dynamic]`
  — rows become (label, colour, series id) with the same three-way condition.

### `modules/app/src/main/scala/app/chart/CompareColorAssigner.scala`

```scala
final case class OverlaySide(
    curves:       Map[NodeId, LECNodeCurve],
    visible:      Set[NodeId],
    palette:      Vector[HexColor],
    slotLabel:    String,   // stable series-id suffix ("active"/"s1"/…) — role unchanged
    displayLabel: String    // NEW: human legend text for the side ("<branch> · <tree>";
                            // the baseline side appends " (active)")
)

def pairForOverlay(sides: Vector[OverlaySide]): Vector[(LECNodeCurve, HexColor, String, String)]
// emits (curve, shade, s"${nid.value}@${s.slotLabel}", s"${curve.name} — ${s.displayLabel}")
```

### `modules/app/src/main/scala/app/views/AnalyzeView.scala`

- `val baselineTreeName: Signal[Option[String]] = treeViewState.selectedTree.signal.map { case LoadState.Loaded(t) => Some(t.name.value); case _ => None }`
  joined into `combinedSpecSignal`'s baseline combine; baseline side label:
  `s"${BranchBar.branchDisplayName(activeBranch)} · ${name}" + " (active)"`
  (branch only while the tree name hasn't loaded).
- `slotOverlayInputs` tuple gains the slot's loaded tree name
  (`slot.treeViewState.selectedTree` → `Option[String]`, same derivation);
  slot side label: `s"${BranchBar.branchDisplayName(coord.branch)} · ${name}"`
  with the branch read from the slot's existing `CompareTarget` coordinate.
- A rewound side's pin (`at`) is deliberately NOT in the legend — the card's
  pinned banner already shows it.

### Tests — `modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala`

Existing suite updated for the 4-tuple: every construction gains a
`displayLabel`; assertions on series ids unchanged; new cases assert the
legend-label element (`"<node> — <branch> · <tree>"`, baseline "(active)"
marker included exactly once).

### ADR alignment

- **ADR-019**: unchanged — pure builders and one derived Signal per side; no
  new Vars, no `.now()`.
- No API/DTO/endpoint/service change (SPA-internal signatures only); no new
  dependency.

### Open decisions

None — Option A and the "branch · tree" legend format are the 2026-08-08
rulings; the pinned-commit omission from the legend is stated above.

### Verification

```bash
sbt app/compile   # zero new warnings
sbt app/test      # CompareColorAssignerSpec updated; rest unchanged
sbt 'commonJVM/test; server/test'
sbt serverIt/test
```

Manual (localhost:18080, `./examples/stage-compare-slots.sh`): hover a curve →
its quantile/AAL rules and no-loss row stay full while other curves'
annotations dim with their lines; hover off → all full; toggles still hide
their annotation entirely in every hover state. Overlay compare legend reads
"Root — main · Compare Demo Tree A (active)" / "Root — alpha · Compare Demo
Tree A"; distribution preview unchanged.

### Versioning

User-visible chart behaviour refinement, no API change: PATCH on landing;
mirror `APP_VERSION` to `.env` and `.env.irmin`.

### File inventory additions — merged into `## File inventory` upon approval

- modules/app/src/main/scala/app/chart/CompareColorAssigner.scala
- modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala

---

## Continuation — statistic lines highlight like the curve (thickness parity)

User feedback 2026-08-08: with dark palette shades, the full-vs-20% opacity
difference on the thin (1px) statistic rules is hard to see — the hovered
curve's rules must gain the same thickness boost the hovered curve line gets.

### `modules/app/src/main/scala/app/chart/VegaSpecShared.scala` (already in the file inventory)

Inside `verticalRuleLayers`, hover-aware branch only (`hoverParam = Some(p)`) —
the rule layer's encoding gains a strokeWidth condition matching the line
layer's own hover boost (hovered series 3px, everything else the rules'
normal 1px):

```scala
val ruleWidth = js.Dynamic.literal(
  "condition" -> js.Array[js.Any](
    js.Dynamic.literal("param" -> p, "empty" -> false, "value" -> 3.0)
  ),
  "value" -> 1.0
)
ruleEncoding.updateDynamic("strokeWidth")(ruleWidth)
```

Text labels and the no-loss rows keep opacity-only treatment (text has no
stroke width; the thickness cue on the rule is the target of this change).
With `hoverParam = None` (distribution preview): no strokeWidth encoding —
unchanged.

### Open decisions

None — direct implementation of the 2026-08-08 ruling.

### Verification

`sbt app/compile` + all four tiers green; manual: hover a curve → its P95/AAL
rules render visibly thicker (3px) while other curves' rules stay thin and
fade; distribution preview anchors unchanged.

### Versioning

PATCH on landing; mirror `APP_VERSION` to `.env` and `.env.irmin`.

---

## Continuation — Overlay compare honours per-curve colour overrides

Bug (user repro 2026-08-08): on a comparand card, picking a single curve's
colour updates the tree-node highlight but never repaints the curve in the
Overlay chart; a later family change repaints everything with family shades.
Cause: the Overlay pairing colours every curve as `shade(family, node)` only —
`LECChartState.colorOverrides` (which `nodeColorMap` merges for the tree
highlights and the side-by-side panels) never reaches `OverlaySide`. The
side-by-side layout is NOT affected (panels read `nodeColorMap`).

Fix: expose the explicit per-node choices from `LECChartState` and give them
precedence over the family shade in the Overlay pairing — the same precedence
`nodeColorMap` applies, so single-tree, side-by-side, and overlay behave
identically: an explicit pick wins until cleared; family changes repaint only
non-overridden curves.

### `modules/app/src/main/scala/app/state/LECChartState.scala`

```scala
/** The user's explicit per-node colour choices — committed overrides plus
  * any live picker preview. Branch-family independent; the Overlay compare
  * pairing gives these precedence over the side's family shade, exactly as
  * `nodeColorMap` does for the single-tree chart and tree highlights. */
val explicitColors: Signal[Map[NodeId, HexColor]] =
  colorOverrides.signal.combineWith(previewOverride.signal).map {
    case (overrides, Some((nid, hex))) => overrides.updated(nid, hex)
    case (overrides, None)             => overrides
  }.distinct
```

### `modules/app/src/main/scala/app/chart/CompareColorAssigner.scala`

```scala
final case class OverlaySide(
    curves:       Map[NodeId, LECNodeCurve],
    visible:      Set[NodeId],
    palette:      Vector[HexColor],
    slotLabel:    String,
    displayLabel: String,
    overrides:    Map[NodeId, HexColor]   // NEW: explicit picks, win over the family shade
)
```

In `pairForOverlay`, the colour becomes
`s.overrides.getOrElse(nid, ColorAssigner.shade(s.palette, nid))`.

### `modules/app/src/main/scala/app/views/AnalyzeView.scala`

- `slotOverlayInputs` tuple gains `slot.treeViewState.chartState.explicitColors`.
- The baseline combine gains `treeViewState.chartState.explicitColors`; the
  baseline `OverlaySide` passes it too (fixes the same gap for the baseline's
  own overrides when Overlay compare is engaged).

### Tests — `modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala`

`side` helper gains `overrides: Map[NodeId, HexColor] = Map.empty`; new cases:
an overridden node renders the override (not the family shade); a
non-overridden node on the same side keeps the family shade.

### Open decisions

None — override-wins is the only behaviour consistent with what the
single-tree chart and side-by-side panels already do.

### Verification

All four tiers green; manual: on a comparand, pick a single curve colour →
the Overlay curve repaints immediately; change the family → only the
non-overridden curves adopt the new family; re-pick the single colour →
repaints again; side-by-side unchanged.

### Versioning

PATCH on landing; mirror `APP_VERSION` to `.env` and `.env.irmin`.

---

## Continuation — Legend Option 2: two-line entries, always qualified

Ruled 2026-08-08: every legend entry renders as two stacked lines — the node
name over its origin ("branch · tree", the tab's own side marked "(active)")
— in every mode, single-tree included, so the legend format never switches;
a pixel cap with "…" guards extreme names. One origin definition
(`TreeViewState.chartOrigin`) feeds the single chart, the side-by-side
panels, and the Overlay side labels (replacing AnalyzeView's local
`loadedTreeName`/`sideDisplayLabel` helpers).

### `modules/app/src/main/scala/app/state/TreeViewState.scala`

```scala
final class TreeViewState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  treeListState: TreeListState,
  globalError: Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None,
  activeBranchSignal: StrictSignal[BranchChoice] = Val(BranchChoice.Main),
  atSignal: StrictSignal[Option[CommitHash]] = Val(None),
  userPalette: Signal[Vector[HexColor]] = Val(PaletteData.Aqua),
  branchDisplay: Option[BranchChoice => String] = None   // NEW
) extends WorkspaceTreeEndpoints:

  /** Legend origin line for this view's chart series — "branch · tree name",
    * degrading to the branch alone while the tree name hasn't loaded; None
    * when no branchDisplay function is configured (legends stay one-line).
    * The display function is injected (Main passes
    * `BranchBar.branchDisplayName`) so this state layer doesn't import the
    * component layer. */
  val chartOrigin: Signal[Option[String]] =
    branchDisplay match
      case None => Val(None)
      case Some(display) =>
        activeBranchSignal.combineWith(selectedTree.signal).map { (b, t) =>
          val branch = display(b)
          Some(t match
            case LoadState.Loaded(tree) => s"$branch · ${tree.name.value}"
            case _                      => branch)
        }
```

`chartOrigin` is passed to the internal `LECChartState` as its `origin`.

### `modules/app/src/main/scala/app/state/LECChartState.scala`

Constructor gains `origin: Signal[Option[String]] = Val(None)` (after
`userPalette`); `specSignal`'s combine adds it and passes it to
`LECSpecBuilder.build(pairs, origin = o, ...)`.

### `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`

```scala
final case class ChartSeries(curve: LECNodeCurve, colour: HexColor, seriesId: String, label: String, origin: Option[String])

def build(
  curves: Vector[(LECNodeCurve, HexColor)],
  origin: Option[String] = None,   // NEW: second legend line for every series
  width: Int = 950, height: Int = 400,
  pinned: Option[PinnedAxes] = None,
  responsive: Boolean = false, zoomable: Boolean = false
): js.Dynamic =
  buildFromSeries(curves.map { case (nc, c) => ChartSeries(nc, c, nc.id.value, nc.name, origin) }, width, height, pinned, responsive, zoomable)
```

- The legend `labelExpr` returns an ARRAY per entry (Vega renders array text
  as stacked lines): `[label]` when `origin` is None, `[label, origin]`
  otherwise (both single-quote-escaped); fallback arm `[datum.value]`.
- `makeColorEncoding`'s legend literal gains `"labelLimit" -> 220` — the
  "…" cap for extreme names.

### `modules/app/src/main/scala/app/chart/CompareColorAssigner.scala`

`pairForOverlay` emits `ChartSeries(curve, colour, id, curve.name, Some(s.displayLabel))`
— the node name and the origin are separate lines now, no " — " concatenation.

### `modules/app/src/main/scala/app/views/AnalyzeView.scala`

- The baseline/slot origin derivations switch to the TreeViewStates'
  `chartOrigin` (in `slotOverlayInputs` and the baseline combine);
  `loadedTreeName` and `sideDisplayLabel` are deleted. Baseline display
  label: `origin.getOrElse(BranchBar.branchDisplayName(activeBranch)) + " (active)"`;
  slot: `origin.getOrElse(<branch name from the slot's coordinate>)`.
- `panelSpec` gains `origin: Option[String]` passed to `build`, supplied per
  side from the same `chartOrigin` signals (`slotPanelInputs` gains it) — the
  side-by-side panels' legends read identically to the overlay's.

### `modules/app/src/main/scala/app/Main.scala`

The three Analyze TreeViewStates (baseline + the slot pool) and the Design
TreeViewState gain `branchDisplay = Some(BranchBar.branchDisplayName)` — every
chart legend in the app is qualified, no mode-dependent format anywhere.

### Tests — `modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala`

The legend-label case asserts `label == curve name` and
`origin == Some(<side display label>)` separately; the "(active)"-exactly-once
assertion moves to the origins.

### ADR alignment

- **ADR-019**: `chartOrigin` is a derived Signal; the display function is
  injected so `app.state` does not import `app.components`. Compliant.
- No API/DTO/endpoint/service change; no new dependency.

### Open decisions

None — Option 2 is the ruling. One verification-gated risk, named: Vega
legend `labelExpr` returning an array is the multi-line mechanism; if the
manual pass shows the runtime not stacking array labels in legends, that is a
blocked-state escalation (fallback candidate: single-line join under the
`labelLimit` cap), not a silent substitution.

### Verification

`sbt app/compile` + all four tiers; manual: single tree → every legend entry
is two lines ("Leaf One" over "main · Compare Demo Tree A"); engage compare →
same format, comparand entries show their own branch · tree, "(active)" on
exactly one side's entries; side-by-side panel legends match; extreme-length
tree name → "…" truncation, no chart-area overlap.

### Versioning

User-visible chart change, no API change: PATCH on landing; mirror
`APP_VERSION` to `.env` and `.env.irmin`.

### File inventory additions — merged into `## File inventory` upon approval

- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/app/src/main/scala/app/Main.scala

---

## Continuation — review-NOTE fixes: shared hex refinement + named overlay-input type

The two NOTEs queued from the legend-continuation quality review, both ruled
"fix now"; the PaletteData inventory addition was accepted by the user in the
same ruling.

### `modules/app/src/main/scala/app/chart/PaletteData.scala`

`hex` widens from `private` to `private[chart]` — the single
`#RRGGBB`-literal refinement helper for the chart package:

```scala
private[chart] def hex(s: String): HexColor =
  HexColor(s.refineUnsafe[Match["^#[0-9a-fA-F]{6}$"]])
```

### `modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala`

Deletes its duplicated `hex` helper and the `io.github.iltotore.iron.*` /
`Match` imports that existed only for it; the two anchor constants become
`PaletteData.hex("#6a8a8e")` / `PaletteData.hex("#a0b0b0")`.

### `modules/app/src/main/scala/app/views/AnalyzeView.scala`

The `slotOverlayInputs` 6-tuple becomes a named view-local type:

```scala
private final case class SlotOverlayInput(
  curves:         LoadState[Map[NodeId, LECNodeCurve]],
  visible:        Set[NodeId],
  target:         CompareTarget,
  palette:        Vector[HexColor],
  origin:         Option[String],
  explicitColors: Map[NodeId, HexColor]
)
```

`slotOverlayInputs: Signal[Vector[SlotOverlayInput]]`; `combinedSpecSignal`'s
overlay branch reads named fields instead of positional destructures. No
behaviour change.

### ADR alignment

No API/DTO/endpoint/service change, no new dependency. Visibility widening is
package-internal; the case class is private to the view. ADR-019 layering
untouched.

### Open decisions

None — both fixes are ruled.

### Verification

`sbt app/compile` + all four tiers green (app, commonJVM, server, serverIt).

### Versioning

Step landed with shipped code changed: PATCH to 0.10.12; mirror `APP_VERSION`
to `.env` and `.env.irmin`.

### File inventory additions — merged into `## File inventory` upon approval (accepted with this ruling)

- modules/app/src/main/scala/app/chart/PaletteData.scala

---

## Continuation — preview parse-failure fix: toggle expression derived from the data (Option B)

Every Design-view distribution preview fails at spec-parse time
("Unrecognized signal name: showP90"). Cause: the shared annotation builder
(`VegaSpecShared.verticalRuleLayers`, shared since the chart-spec
deduplication) unconditionally emits a visibility expression whose text names
every `LecAnnotation` toggle signal, and Vega resolves every signal name in
an expression when it parses the spec — reachability is irrelevant. The LEC
chart declares those params; the preview has no toggles and declares none, so
its spec fails to compile. Ruled fix: Option B — build the expression from
the toggle keys actually present in the data, so the emitted spec references
exactly the signals the data demands.

### `modules/app/src/main/scala/app/chart/VegaSpecShared.scala`

The two chain builders become functions of the key set; the mark-opacity one
returns `Option` so "no toggled datum" emits no expression at all:

```scala
private def toggleOpacityExpr(toggleKeys: Vector[String]): Option[String]
private def toggleHiddenExpr(toggleKeys: Vector[String]): String   // "false" when empty
```

`verticalRuleLayers` computes `val toggleKeys = data.flatMap(_.visibilityKey).distinct`
and feeds both. The builder no longer references `LecAnnotation` at all — the
enum stays the single source of the signal names at the call sites that put
keys into data (`LECSpecBuilder`), and `VegaSpecShared` becomes genuinely
chart-neutral. The distribution preview's all-`None` data yields an empty key
set → no expression → the spec parses; the LEC chart's data carries the
toggle keys it uses → unchanged behaviour.

### ADR alignment

No API/DTO/endpoint/service change, no new dependency. Removes an
LEC-specific dependency from a shared module.

### Open decisions

None — Option B is the ruling.

### Verification

`sbt app/compile` + all four tiers; manual: Design view — every node's
distribution preview renders again (anchors visible, both PDF and CDF);
Analyze view — annotation toggles and hover dimming behave exactly as before.

### Versioning

Regression fix, shipped code changed: PATCH to 0.10.13; mirror `APP_VERSION`
to `.env` and `.env.irmin`.
