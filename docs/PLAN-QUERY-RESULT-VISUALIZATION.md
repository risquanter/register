# Plan: Query Result Visualization

**Status:** Draft v3 — server-resolves-colours design  
**Scope:** Planning only — no code changes  
**Predecessor:** TG-3 (frontend query pane), ADR-028  
**Revision history:**
- v1: Deferred dual-palette (single palette for MVP).
- v2: Client-explicit hex colours — client sends `(nodeId, hexColor)`.
- **v3 (current):** Client sends `(nodeId, paletteName)`. Server resolves
  colours by sorting curves within each palette group by p95 and assigning
  shades darkest → lightest. No extra round trips. Hex colours are
  typed via an Iron opaque `HexColor` in `OpaqueTypes.scala` (alongside
  all other opaque types). Overlap nodes get a third palette.

---

## 1 Goal

When a vague-quantifier query returns `satisfied = true`, the UI should:

1. **Render LEC curves** for the satisfying nodes on the chart area,
   coloured from the **green** CSS curve palette, ordered darkest →
   lightest by descending p95 loss.
2. **Highlight satisfying nodes** in the tree with a tasteful light green
   background.
3. **Auto-expand the tree** so highlighted nodes are visible without
   manual intervention.

Additionally, when the user Ctrl+clicks nodes manually:

4. **Render user-selected curves** from the **aqua** CSS curve palette,
   same p95-based darkness ordering, with a hard cap of 13 and a visible
   error when the user exceeds it.

When a node appears in **both** the query set and the user set:

5. **Render overlap curves** from a **third** CSS curve palette (purple),
   so the user can see at a glance which nodes are in both groups.

The client tells the server _which palette_ each node belongs to. The
server owns sorting and shade assignment — the client never needs
quantile values.

---

## 1b Prerequisites

The following changes must be applied **before** implementing this plan.
They can be performed as a separate commit or as the first step of the
implementation branch.

### P1: Rename `matchingNodeIds` → `satisfyingNodeIds`

**Motivation:** `QueryResponse` already carries `satisfyingCount: Int`.
The companion field `matchingNodeIds: List[NodeId]` should use the same
stem (`satisfying`) for consistency. The current name came from the
initial query implementation; the plan adopts the more precise name.

**Scope — all files referencing the field:**

| File (fully qualified) | Location | Change |
|---|---|---|
| `modules/common/src/main/scala/com/risquanter/register/http/responses/QueryResponse.scala` | Field declaration (line 12) | `matchingNodeIds` → `satisfyingNodeIds` |
| `modules/app/src/main/scala/app/state/AnalyzeQueryState.scala` | Derived signal + `resp.matchingNodeIds` read | `matchingNodeIds` → `satisfyingNodeIds` (signal name and DTO access) |
| `modules/app/src/main/scala/app/views/AnalyzeView.scala` | `resp.matchingNodeIds` reads (lines 16, 35, 84, 86) and `queryState.matchingNodeIds` (line 98) | `matchingNodeIds` → `satisfyingNodeIds` |
| `modules/app/src/main/scala/app/views/QueryResultCard.scala` | `r.matchingNodeIds` reads (lines 75, 81) | `matchingNodeIds` → `satisfyingNodeIds` |
| `modules/server/src/main/scala/com/risquanter/register/foladapter/QueryResponseBuilder.scala` | Field construction (line 51) | `matchingNodeIds` → `satisfyingNodeIds` |
| `modules/server/src/test/scala/com/risquanter/register/foladapter/QueryResponseBuilderSpec.scala` | Assertions (lines 77, 90, 100, 114) | `matchingNodeIds` → `satisfyingNodeIds` |

**Procedure:** A global find-and-replace of the exact token
`matchingNodeIds` → `satisfyingNodeIds` across `.scala` files is safe —
the token is unique to this field. Verify with `grep -r matchingNodeIds
modules/` afterwards.

**Wire compatibility:** This is a breaking JSON field rename
(`"matchingNodeIds"` → `"satisfyingNodeIds"`). Acceptable because the
query endpoint is internal, behind capability URLs, with no external
consumers. The client and server are deployed together.

---

## 2 Existing Infrastructure (Audit)

### 2.1 Query result data

`QueryResponse` carries `satisfyingNodeIds: List[NodeId]`. Client-side,
`AnalyzeQueryState.satisfyingNodeIds` derives `Signal[Set[NodeId]]`,
wired into `TreeDetailView` for highlighting.

> **Rename:** See §1b P1. The current codebase uses `matchingNodeIds`.
> This plan renames it to `satisfyingNodeIds` to align with
> `satisfyingCount` on the same DTO.

`QueryResponse` does **not** carry quantiles, but that's fine — in this
design the client never needs them. The server uses its own cached
quantiles for ordering.

### 2.2 LEC chart wiring (already implemented)

| Component | Role |
|---|---|
| `LECChartState.loadLECChart(nodeIds)` | POSTs `List[NodeId]` to `/lec-chart`, stores Vega-Lite JSON |
| `LECChartState.chartNodeIds: Var[Set[NodeId]]` | Which nodes are "chart-selected" |
| `AnalyzeView.viewLECForMatches()` | Sets `chartNodeIds` to `satisfyingNodeIds`, calls `loadLECChart` (T3.5 button) |
| `LECChartView` | Renders `lecChartSpec` signal via VegaEmbed |

The pipeline exists but sends a flat `List[NodeId]` with no palette info.

### 2.3 Tree highlighting (already implemented)

`TreeDetailView.renderNode` combines three signals into CSS classes:

```
isSelected      → "node-selected"        (single-click)
isChartSelected → "node-chart-selected"  (Ctrl+click / chart overlay)
isQueryMatched  → "node-query-matched"   (query match)
```

`node-query-matched` currently uses **aqua** (`--info` / `--info-surface`,
sourced from `--curve-aqua-400`).

### 2.4 Tree expand/collapse

`TreeViewState.expandedNodes: Var[Set[NodeId]]` controls visibility.
Children are in the DOM but hidden via
`display <-- isExpanded.map(if _ then "block" else "none")`.

### 2.5 TreeIndex (client-side, cross-compiled)

`TreeIndex` in `common` provides `ancestorPath`, `descendants`,
`children`, `parents`. Available on `RiskTree.index` after JSON decode.

### 2.6 No client-side LEC caching

`lecChartSpec` is overwritten on every `loadLECChart()`. Each call is a
fresh POST.

### 2.7 Chart colour pipeline — current state

**`LECChartSpecBuilder.generateMultiCurveSpec` (server-side):**

1. **Single hardcoded palette:** `themeColorsRisk` — 10 hex values
   (`#60b0f0`, `#F2A64A`, `#75B56A`, `#E1716A`, `#6df9ce`, `#515151`,
   `#838383`, `#f2a64a`, `#ab5c0c`, `#350c28`).

2. **Hash-based assignment:** `stableColor(id) = themeColorsRisk(id.hashCode.abs % 10)`.
   In plain English: take the node ID string → Java `hashCode` →
   `abs` → `mod 10` → pick that index from the palette. Same node
   always gets the same colour, but collisions are likely beyond ~7
   nodes (birthday paradox in 10 colours).

3. **No grouping, no per-curve colour.** The endpoint receives
   `List[NodeId]`. The builder receives `Vector[LECNodeCurve]` with
   4 fields: `id`, `name`, `curve`, `quantiles` — no colour.

4. Colours are injected into the Vega-Lite JSON at
   `encoding.color.scale.range`.

**Sort order:** Root curve first (head of input vector), rest
alphabetically by name. This sort is unrelated to risk magnitude.

### 2.8 Quantiles — available server-side, not client-side

| Location | Has quantiles? | Detail |
|---|---|---|
| Server `RiskResult` (cache) | **Yes** | Full outcome distribution; `LECGenerator.calculateQuantiles` produces `Map("p50" → …, "p90" → …, "p95" → …, "p99" → …)` |
| `LECNodeCurve.quantiles` | **Yes** | Populated during `getLECCurvesMulti` / `getLECChart` calls |
| `QueryResponse` | **No** | Just `List[NodeId]` |
| Client-side `RiskTree.nodes` | **No simulation stats** | `RiskLeaf` has user-supplied input params, not computed output |

**The server already has p95 for every simulated node** at the point where
it builds the chart spec. No extra round trip is needed — the server
sorts by p95 internally.

### 2.9 CSS curve palettes (8 × 13 shades + neutral)

Defined in `app.css` as custom properties. **Currently unused by the
chart builder.** Each palette has 13 shades ordered lightest → darkest:

| Palette | Variable prefix | Example darkest | Example lightest |
|---|---|---|---|
| green | `--curve-green-{25…975}` | `#03170b` | `#f1fdf5` |
| aqua | `--curve-aqua-{25…975}` | `#00121a` | `#f0fcff` |
| purple | `--curve-purple-{25…975}` | `#11011e` | `#faf5ff` |
| yellow | `--curve-yellow-{25…975}` | `#1c0e03` | `#fefdf0` |
| red | `--curve-red-{25…975}` | `#170304` | `#fff5f5` |
| pink | `--curve-pink-{25…975}` | `#1c020d` | `#fef6fa` |
| orange | `--curve-orange-{25…975}` | `#1d0b01` | `#fffbeb` |
| emerald | `--curve-emerald-{25…975}` | `#021411` | `#f2fbf7` |
| neutral | `--curve-neutral-{0…1000}` | `#04090a` | `#ffffff` |

### 2.10 ADR-018 type patterns — when wrapper vs opaque vs enum

**Examined existing types in the codebase:**

| Type | Pattern | Reason for that pattern |
|---|---|---|
| `TreeId`, `NodeId`, `WorkspaceId` | **Case class wrapper** around `SafeId.SafeId` | Two+ types share the *same* base constraint (`SafeIdStr` = ULID regex). Wrapper adds nominal identity so compiler prevents `TreeId` where `NodeId` is expected. |
| `SafeName`, `Email` | **Bare opaque** over Iron-refined string | Single concept using that constraint. No second type to confuse it with. Zero runtime cost. |
| `PRNGCounter` | **Bare opaque** over `Long` | Semantic tag on a primitive. No validation needed. |
| `WorkspaceKeySecret` | **`final class`** (not case class) | Secret credential: redacted `toString`, no `copy`/`unapply`. |
| `ValidationErrorCode` | **Scala 3 `enum`** with custom values | Closed, finite set of known codes. Serialized as string code. |
| `LossDistributionType` | **Scala 3 `enum`** | Closed set (`Leaf`, `Composite`). |
| `DistributionParams` | **Sealed trait** with case class variants | Open shape: each variant has different fields. |

**ADR-018's decision rule:** Case class wrappers exist *specifically
and only* when two+ domain concepts share the same Iron base constraint
and must not be interchanged. If there's only one concept, bare opaque.
If the set is closed and finite, enum.

### 2.11 Existing serialization patterns

**Request DTOs:** `final case class XxxRequest(...)` in `http/requests/`.
Companion: `given JsonCodec[T] = DeriveJsonCodec.gen`. Tapir schema
auto-derived via `import sttp.tapir.generic.auto.*`.

**Enum JSON codecs:** `ValidationErrorCode` uses manual
`JsonEncoder[String].contramap(_.code)` /
`JsonDecoder[String].mapOrFail(...)`. `LossDistributionType` has no
explicit codec (only used internally).

**Error handling:** `GlobalError.ValidationFailed(errors)` renders in
`ErrorBanner` (global, dismissible). Per-view inline via
`LoadState.Failed(msg)`.

---

## 3 Feature 1: Dual-Palette LEC Chart (Server-Side Colour Resolution)

### 3.1 Design principle: server resolves colours, client names palettes

**No extra round trips.** The client sends `(nodeId, paletteName)` pairs
in a single POST. The server:

1. Groups entries by palette name.
2. Within each group, sorts curves by **p95 descending** (server already
   has this from the simulation cache).
3. Assigns hex colours from the named palette: rank 0 (highest p95) →
   darkest shade, rank 12 (lowest) → lightest.
4. Builds the Vega-Lite spec with the resolved colours.

The client **never** needs quantile values. It only decides which palette
each node belongs to (based on whether the node is from a query result,
manual selection, or both).

### 3.2 New domain type: `CurvePalette` (Scala 3 enum)

Following the `ValidationErrorCode` / `LossDistributionType` pattern:

```scala
// Package: com.risquanter.register.domain.data
// File: CurvePalette.scala (new file in common, cross-compiled)

/** Named colour palette for LEC chart curves.
  *
  * Each variant maps to one of the 13-shade CSS curve palettes
  * defined in app.css. The server resolves the palette name to
  * concrete hex values when building the Vega-Lite spec.
  *
  * Serialized as lowercase string (e.g. "green", "aqua").
  */
enum CurvePalette:
  case Green    // --curve-palette-green   (query results)
  case Aqua     // --curve-palette-aqua    (manual selection)
  case Purple   // --curve-palette-purple  (overlap: both sets)

object CurvePalette:
  given JsonEncoder[CurvePalette] =
    JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[CurvePalette] =
    JsonDecoder[String].mapOrFail { s =>
      CurvePalette.values.find(_.toString.equalsIgnoreCase(s))
        .toRight(s"Unknown curve palette: '$s'. Valid: ${CurvePalette.values.map(_.toString.toLowerCase).mkString(", ")}")
    }
```

**Why an enum, not an opaque type or case class (D5 analysis):**

ADR-018's type decision tree:
- **Is the value set open or closed?** Closed — we have exactly 3 palettes
  (extensible to 8 if we add the remaining CSS palettes later, but still
  a known finite set).
- **Does it share a constraint with another type?** No — palette names are
  not ULIDs, not emails, not URLs. There is no base constraint to share.
- **Is it a refined primitive?** No — it's a fixed enumeration.

The ADR-018 case-class-wrapper pattern applies when "two+ types share the
same Iron base constraint" (e.g. `TreeId` vs `NodeId` both wrapping
`SafeId`). That situation does not exist here. An enum is the idiomatic
Scala 3 pattern for a closed set of named values — matching
`ValidationErrorCode` and `LossDistributionType` in the existing codebase.

**Extensibility:** Adding a 4th palette (e.g. `Orange` for a future
"mitigation" set) is a one-line enum variant + one entry in the palette
registry on the server. No Iron constraint changes, no smart constructor
changes.

### 3.3 SETTLED — D5: Type shape for palette identifier

**Decision:** Scala 3 `enum CurvePalette`. Not an opaque type (no Iron
constraint to apply), not a case class wrapper (no shared constraint to
disambiguate). The codebase precedent is `ValidationErrorCode` (enum
with custom serialization) and `LossDistributionType` (simple enum).

### 3.4 SETTLED — D6: Quantiles stay server-side

**Decision:** The client never obtains p95 values. The server already has
quantiles cached per node (via `RiskResult` in `TreeCacheManager`). When
it builds the chart spec, it uses quantiles to sort within each palette
group. This eliminates:
- The `QueryResponse` extension (v2 option A).
- The quantiles endpoint (v2 option B).
- The client-side quantile cache.
- Any extra round trip.

**One POST, one response, done.**

### 3.5 Is p95 a reasonable ordering metric?

**What p95 means:** The loss value $L$ such that
$P(\text{Loss} \geq L) = 0.05$. Equivalently, 95% of simulated outcomes
fall below this value. It is the industry-standard **Value-at-Risk at
95%** (VaR-95).

**Visual interpretation on an LEC chart:** On the X-axis (loss), the p95
point is where the curve crosses Y = 0.05 (5% exceedance). A curve with
higher p95 extends further to the right before reaching that 5% threshold
— it has a fatter tail.

**Does higher p95 mean "visually dominant"?**

At the tail (the part risk professionals care most about): **yes**. A
curve with higher p95 is the one that stretches further right. When two
curves are plotted together, the higher-p95 curve "envelopes" the other
on the right side.

There is a subtlety: two curves can **cross**. Curve A may have higher
p95 (fatter tail) but lower exceedance probability at small losses —
meaning curve B rises above curve A on the left side of the chart. This
happens when B has higher frequency of moderate losses but A has rarer,
larger losses.

**Example of crossing curves:**
| Metric | Curve A | Curve B |
|---|---|---|
| p95 | $50M | $30M |
| P(Loss ≥ $10M) | 15% | 25% |
| Visual | extends further right | rises higher at moderate losses |

Despite the crossing possibility, **p95 is still the right ordering
metric** because:
1. Risk professionals scan LEC charts right-to-left (tail risk first).
2. The darkest curves should be the ones that "stick out" to the right.
3. p95 directly measures how far right the curve extends before the 5%
   threshold.
4. It is already computed server-side at zero extra cost.
5. Alternative metrics (expected loss, p50) would highlight moderate-risk
   curves — less useful for tail-risk visualisation.

### 3.6 New request type: `LECChartRequest`

Following existing request DTO patterns:

```scala
// Package: com.risquanter.register.http.requests
// File: LECChartRequest.scala (new)

/** Request body for the LEC chart endpoint.
  *
  * Each entry specifies a node and which colour palette it belongs to.
  * The server resolves concrete hex colours by sorting nodes within each
  * palette group by p95 (descending) and assigning shades darkest →
  * lightest.
  *
  * @param curves Non-empty list of (nodeId, palette) entries.
  */
final case class LECChartRequest(
  curves: List[LECChartCurveEntry]
)
object LECChartRequest:
  given JsonCodec[LECChartRequest] = DeriveJsonCodec.gen

/** Single curve entry in a chart request.
  *
  * @param nodeId  The node whose LEC curve to render.
  * @param palette Which colour palette to use for this curve.
  */
final case class LECChartCurveEntry(
  nodeId: NodeId,
  palette: CurvePalette
)
object LECChartCurveEntry:
  given JsonCodec[LECChartCurveEntry] = DeriveJsonCodec.gen
```

**Naming rationale:**
- `LECChartRequest` parallels `QueryRequest`, `RiskTreeUpdateRequest`.
- `LECChartCurveEntry` parallels `RiskLeafDefinitionRequest` — a
  sub-structure of the request. "Entry" avoids collision with
  `LECNodeCurve` (the server-side curve data type).

**Wire format example:**
```json
{
  "curves": [
    { "nodeId": "01HX9ABCDEFGHIJKLMNOPQRST0", "palette": "green" },
    { "nodeId": "01HXA1234567890ABCDEFGHIJK", "palette": "green" },
    { "nodeId": "01HXBZZZZZZZZZZZZZZZZZZZZZZ", "palette": "aqua" },
    { "nodeId": "01HXCOVERLAPNODEIDEXAMPLE00", "palette": "purple" }
  ]
}
```

**Validation:**
- `curves` must be non-empty → `ValidationErrorCode.EMPTY_COLLECTION`.
- Each `nodeId` must exist in the tree → existing `lookupNodesInTree`.
- Each `palette` is validated at deserialization by `CurvePalette`'s
  `JsonDecoder` (unknown string → decode error).
- Duplicate `nodeId` → deduplicate silently (last wins), preserving
  the palette of the last occurrence.

### 3.7 Endpoint change

```scala
// Before:
.in(jsonBody[List[NodeId]].description("Array of node IDs to include in chart"))

// After:
.in(jsonBody[LECChartRequest].description("Curve entries with palette assignments"))
```

Breaking change to `/lec-chart`. Internal API behind capability URLs —
no external consumers.

### 3.8 Server-side flow: palette → p95-sorted → hex colours

The server's job _expands_ compared to v2 — it now does the sorting and
shade assignment that v2 pushed to the client.

#### Step 1 — Palette registry (new, in `LECChartSpecBuilder` or `common`)

**`HexColor` Iron opaque type** — added to `OpaqueTypes.scala` in
`common`, alongside `PRNGCounter`, `SafeName`, `SafeId`, etc. Single
concept, no second hex-colour kind to distinguish → bare opaque per
ADR-018 §"When to Apply":

```scala
// In common module: .../domain/data/iron/OpaqueTypes.scala (appended)

// CSS hex colour (#RRGGBB). Used by CurvePaletteRegistry and ColouredCurve
// for Vega-Lite chart spec colour assignment. Never serialized to the wire —
// .value extraction happens only at the Vega-Lite JSON edge.
type HexColorStr = String :| Match["^#[0-9a-fA-F]{6}$"]

object HexColor:
  opaque type HexColor = HexColorStr

  object HexColor:
    /** Construct from an already-refined HexColorStr (Iron proof required). */
    def apply(s: HexColorStr): HexColor = s

  extension (c: HexColor)
    /** Extract the raw `#rrggbb` string — only at the Vega-Lite JSON edge. */
    def value: HexColorStr = c
```

No JSON codecs needed — `HexColor` is never serialized to the wire.
`refineUnsafe` is safe for the registry literals (compile-time constants,
same pattern as `IronConstants`).

**`CurvePaletteRegistry`** — a `Map[CurvePalette, Vector[HexColor]]`
mapping palette names to 13 hex shades, **darkest first**:

```scala
import HexColor.HexColor

object CurvePaletteRegistry:
  /** 13 hex shades per palette, ordered darkest → lightest.
    * Sourced from app.css curve palette custom properties.
    * Index 0 = highest-risk (darkest), index 12 = lowest-risk (lightest).
    * `refineUnsafe` is safe: literals are compile-time constants.
    */
  private def hex(s: String): HexColor = HexColor(s.refineUnsafe[Match["^#[0-9a-fA-F]{6}$"]])

  val shades: Map[CurvePalette, Vector[HexColor]] = Map(
    CurvePalette.Green -> Vector(
      "#03170b", "#052914", "#0f3e21", "#145c2f", "#15803d",
      "#16a34a", "#22c55e", "#4ade80", "#86efac", "#bbf7d0",
      "#d5fbe2", "#e4fbec", "#f1fdf5"
    ).map(hex),
    CurvePalette.Aqua -> Vector(
      "#00121a", "#002533", "#003a52", "#005370", "#007299",
      "#0094bf", "#00b3e6", "#42c9ed", "#7bdaf3", "#aee9f8",
      "#d4f5fc", "#e0f9ff", "#f0fcff"
    ).map(hex),
    CurvePalette.Purple -> Vector(
      "#11011e", "#23023b", "#3e0f61", "#5a1094", "#7b0acd",
      "#9810fa", "#ad46ff", "#c27aff", "#dab2ff", "#e5ccff",
      "#ecdbff", "#f2e6ff", "#faf5ff"
    ).map(hex)
  )
```

#### Step 2 — Controller (`WorkspaceController.getLECChart`)

Receives `LECChartRequest`. Extracts a `Map[NodeId, CurvePalette]`:

```scala
val paletteMap: Map[NodeId, CurvePalette] =
  request.curves.map(e => e.nodeId -> e.palette).toMap
val nodeIds = paletteMap.keySet
```

Passes `nodeIds` + `paletteMap` to the service.

#### Step 3 — Service (`RiskTreeServiceLive.getLECChart`)

Signature change:

```scala
// Before:
def getLECChart(treeId: TreeId, nodeIds: Set[NodeId]): Task[String]

// After:
def getLECChart(treeId: TreeId, nodeIds: Set[NodeId],
                paletteMap: Map[NodeId, CurvePalette]): Task[String]
```

Implementation:

```scala
for
  nodeCurves <- getLECCurvesMulti(treeId, nodeIds)
  // nodeCurves: Map[NodeId, LECNodeCurve] — each has .quantiles with p95

  // Group by palette, sort each group by p95 desc, assign colours
  colouredCurves = assignPaletteColours(nodeCurves, paletteMap)

  spec = LECChartSpecBuilder.generateMultiCurveSpec(colouredCurves)
yield spec
```

Where `assignPaletteColours` is a **pure function** that returns a
server-local product type pairing each curve with its resolved hex
colour:

```scala
import HexColor.HexColor

/** Curve paired with its resolved hex colour — server-local, never serialized.
  *
  * Keeps the rendering concern (colour) separate from the domain DTO
  * (`LECNodeCurve`), so the cross-compiled wire type stays clean and the
  * `lec-multi` endpoint is unaffected. Follows ADR-001's boundary
  * separation: domain data carries domain fields; presentation metadata
  * lives in presentation-scoped types.
  *
  * @param curve    Domain curve data (id, name, points, quantiles).
  * @param hexColor Resolved hex colour (Iron-refined `HexColor`, not raw String).
  */
final case class ColouredCurve(curve: LECNodeCurve, hexColor: HexColor)
```

```scala
private def assignPaletteColours(
  curves: Map[NodeId, LECNodeCurve],
  paletteMap: Map[NodeId, CurvePalette]
): Vector[ColouredCurve] =
  // Group curves by palette
  val grouped: Map[CurvePalette, Vector[(NodeId, LECNodeCurve)]] =
    curves.toVector
      .map { case (nid, curve) => (paletteMap.getOrElse(nid, CurvePalette.Green), nid, curve) }
      .groupBy(_._1)
      .view.mapValues(_.map(t => (t._2, t._3))).toMap

  // Within each group: sort by p95 descending, assign shade by rank
  grouped.toVector.flatMap { case (palette, members) =>
    val shades = CurvePaletteRegistry.shades(palette)
    members
      .sortBy { case (_, c) => -c.quantiles.getOrElse("p95", 0.0) }
      .zipWithIndex
      .map { case ((_, curve), rank) =>
        ColouredCurve(curve, shades(rank min (shades.size - 1)))
      }
  }
```

This returns a flat `Vector[ColouredCurve]` — each entry pairs the
unmodified domain curve with a resolved hex colour. `LECNodeCurve`
itself is **not modified**.

#### Step 4 — Builder (`LECChartSpecBuilder.generateMultiCurveSpec`)

The existing signature changes from `Vector[LECNodeCurve]` to
`Vector[ColouredCurve]`. No overload — the legacy `themeColorsRisk`
palette and `stableColor` hash function are **removed** as part of
this plan (see "Legacy colour removal" below).

```scala
// Before:
def generateMultiCurveSpec(curves: Vector[LECNodeCurve], ...): String

// After:
def generateMultiCurveSpec(coloured: Vector[ColouredCurve], ...): String

// Inside — colour comes from the wrapper, not from the curve:
val colorRange = coloured.map(cc => str(cc.hexColor.value))
// Curve data accessed via cc.curve.id, cc.curve.name, etc.
// .value extraction happens ONLY here — at the Vega-Lite JSON edge.
```

**Sort order change:** The new signature **preserves input order**
(which is already p95-sorted per group by `assignPaletteColours`). The
old alphabetical sort is removed.

**`generateSpec` (single-curve convenience):** This method exists in the
builder and delegates to `generateMultiCurveSpec`. It has **zero
production callers** — only tests use it. It will be updated to accept
a `ColouredCurve` (or removed, with tests calling the multi-curve
method directly).

**Legacy colour removal:** The following artifacts in
`LECChartSpecBuilder` become dead code and are deleted:
- `themeColorsRisk: Vector[String]` — the 10-colour BCG-style palette (bare `String`, no type safety).
- `stableColor(id: String): String` — the `hashCode.abs % 10` function.

These are replaced entirely by `CurvePaletteRegistry` shades, which
provide 13 shades per palette (vs 10 total), p95-ranked ordering
(vs hash-based), and explicit palette grouping (vs single mixed bag).
The only production call site is `RiskTreeServiceLive.getLECChart`,
which this plan already changes to pass `ColouredCurve` vectors.

### 3.9 `LECNodeCurve` — unchanged; why `ColouredCurve` exists

`LECNodeCurve` is **not modified**.

#### Why not add `color: Option[String]` to `LECNodeCurve`?

The earlier v3 draft proposed adding `color: Option[String] = None` to
`LECNodeCurve`. The reasoning was simple: the server computes the hex
colour in `assignPaletteColours`, stamps it on the curve, and the
builder reads it back when constructing the Vega-Lite spec:

```
assignPaletteColours → stamps curve.color = Some("#hex")
  → builder reads curve.color → embeds in Vega-Lite JSON
```

This was a shortcut to thread the colour through the pipeline using
the same data structure. But it is a **DDD violation**:

1. **Domain vs presentation.** `LECNodeCurve` is a domain DTO —
   identity (id), name, curve points, quantiles. Colour is a rendering
   decision made by the service layer. It is not an intrinsic property
   of a curve.

2. **Wire type pollution.** `LECNodeCurve` lives in `common`
   (cross-compiled JVM + JS) and is serialized to the client by the
   `/lec-multi` endpoint (`Map[String, LECNodeCurve]`). Adding
   `color: Option[String]` would serialize `"color": null` for every
   curve on that endpoint — noise on the wire for a field the client
   never asked for.

3. **Stringly-typed.** `Option[String]` accepts any garbage. The hex
   colour is an internal invariant guaranteed by `CurvePaletteRegistry`,
   not a user-supplied value. This plan uses an Iron-refined opaque
   type `HexColor` (§3.8 step 1) to enforce the `#[0-9a-fA-F]{6}`
   constraint at compile time.

#### What is "the `lec-multi` path"?

There are **two** endpoints that touch `LECNodeCurve`:

| Endpoint | URL | Returns | Purpose |
|---|---|---|---|
| `lec-chart` | `POST .../lec-chart` | `String` (Vega-Lite JSON) | Render-ready chart spec. **This plan changes its request body** to `LECChartRequest`. The response is a JSON string, not `LECNodeCurve` objects. |
| `lec-multi` | `POST .../nodes/lec-multi` | `Map[String, LECNodeCurve]` | Raw curve data for inspection (points, quantiles). No chart spec. **This endpoint is unchanged by this plan.** |

The `lec-multi` endpoint is the reason `LECNodeCurve` must stay clean:
it serializes the type directly to the client. Adding a colour field
would leak presentation metadata into a data-inspection endpoint.

#### Why no colour field is needed anywhere on the client

The client never sees or handles hex colours. The flow is:
1. Client sends palette *names* in `LECChartRequest` (§3.6).
2. Server resolves names → hex colours internally (§3.8).
3. Server embeds hex colours directly into the Vega-Lite JSON spec.
4. Client receives the finished Vega-Lite JSON and renders it.

The hex colours exist **only** between `assignPaletteColours` and the
Vega-Lite JSON encoder, both server-side. The server-local
`ColouredCurve(curve: LECNodeCurve, hexColor: HexColor)` is the minimal
type that threads this data through that pipeline without touching any
shared or serialized type. `HexColor.value` (raw `String` extraction)
happens only at the Vega-Lite JSON edge in `generateMultiCurveSpec`.

### 3.10 Client-side flow

The client is dramatically simpler than v2 — no quantile cache, no
colour assignment, no palette constants.

**State:**

```scala
/** Nodes from the last query result (the "query set"). */
// Already exists: queryState.satisfyingNodeIds: Signal[Set[NodeId]]

/** Nodes Ctrl+clicked by the user (the "user set"). */
val userSelectedNodeIds: Var[Set[NodeId]] = Var(Set.empty)
```

**Building the request (pure function):**

```scala
def buildChartRequest(
  querySet: Set[NodeId],
  userSet: Set[NodeId]
): LECChartRequest =
  val overlap   = querySet intersect userSet
  val queryOnly = querySet -- overlap
  val userOnly  = userSet  -- overlap

  val entries =
    queryOnly.toList.map(id => LECChartCurveEntry(id, CurvePalette.Green)) ++
    userOnly.toList.map(id  => LECChartCurveEntry(id, CurvePalette.Aqua)) ++
    overlap.toList.map(id   => LECChartCurveEntry(id, CurvePalette.Purple))

  LECChartRequest(entries)
```

**Reactive trigger (idiomatic Laminar, ADR-019 P4):**

```scala
val chartRequest: Signal[Option[LECChartRequest]] =
  queryState.satisfyingNodeIds
    .combineWith(userSelectedNodeIds.signal)
    .map { (querySet, userSet) =>
      val allNodes = querySet ++ userSet
      if allNodes.isEmpty then None
      else Some(buildChartRequest(querySet, userSet))
    }

// Single subscription — fires on any change to either set
chartRequest.changes
  .collect { case Some(req) => req }
  .debounce(100)  // debounce rapid Ctrl+click bursts
  --> { req => loadLECChart(req) }
```

**Ctrl+click with >13 guard:**

```scala
/** Observer-based Ctrl+click handler (ADR-019 P2: events up).
  *
  * Instead of exposing a public `def toggleUserSelection(nodeId)` that
  * views call imperatively, `LECChartState` exposes a `WriteBus[NodeId]`.
  * The view wires Ctrl+click events into this bus. The mutation logic
  * (13-cap guard, set toggle) is encapsulated inside the bus's observer.
  *
  * This keeps TreeDetailView from calling methods on shared state
  * objects — it only emits events; the state owner handles them.
  */
private val userSelectionBus: EventBus[NodeId] = new EventBus[NodeId]
val userSelectionToggle: WriteBus[NodeId] = userSelectionBus.writer

// Internal observer — handles the toggle + cap logic
userSelectionBus.events --> { nodeId =>
  val current = userSelectedNodeIds.now()
  if current.contains(nodeId) then
    userSelectedNodeIds.update(_ - nodeId)
  else if current.size >= 13 then
    globalError.set(Some(GlobalError.ValidationFailed(List(
      ValidationError("chartSelection", ValidationErrorCode.CONSTRAINT_VIOLATION,
        "Maximum 13 user-selected curves. Remove a selection before adding more.")
    ))))
  else
    userSelectedNodeIds.update(_ + nodeId)
}

// In TreeDetailView.renderNode — wire Ctrl+click to the bus:
onClick.filter(ev => ev.ctrlKey || ev.metaKey)
  .preventDefault
  .mapTo(nodeId) --> state.userSelectionToggle
```

No `fetchQuantilesIfMissing` — the server handles quantile lookup.

### 3.11 SETTLED — D1: Auto-trigger LEC on query success

**Decision:** Auto-trigger via reactive signal. When `satisfyingNodeIds`
changes (new query result), `chartRequest` recomputes → POST fires.

### 3.12 SETTLED — D1c: Large result set handling

**Decision:**
- **Query set:** Top 13 by p95, silent truncation. Server caps each
  palette group at 13 shades. If > 13 query nodes are sent, the server
  sorts by p95 and renders only the top 13 in that palette. Remaining
  nodes are silently dropped from the chart (still highlighted in tree).
- **User set:** Hard cap of 13. Client-side guard (§3.10) rejects
  additional Ctrl+clicks with an error banner.
- **Tree highlighting:** No limit. All matched nodes are highlighted
  and auto-expanded regardless of chart cap.

### 3.13 Overlap: third palette (purple)

When a node appears in both the query set and the user set, the client
assigns `CurvePalette.Purple`. This gives visual confirmation of overlap
on the chart.

**The three groups are mutually exclusive** by construction:
`queryOnly ∩ userOnly = ∅`, `queryOnly ∩ overlap = ∅`,
`userOnly ∩ overlap = ∅`. Each node appears exactly once in the request
with exactly one palette.

**Capacity:** Theoretically up to 13 + 13 + 13 = 39 curves if overlap
existed simultaneously with full sets. In practice:
- overlap nodes are subtracted from both queryOnly and userOnly,
- so the total is `|queryOnly| + |userOnly| + |overlap|`
  = `|querySet ∪ userSet|` ≤ `|querySet| + |userSet|` ≤ 13 + 13 = 26.
- With the query silent-cap at 13, it's ≤ 26 in the absolute worst case.

### 3.14 SETTLED — D7: Ctrl+click interaction model (Option A — coexist independently)

#### Problem context

Today, `LECChartState` maintains a single `chartNodeIds: Var[Set[NodeId]]`
that serves **both** the query-triggered chart and manual Ctrl+click
selection. The two entry points:

1. **Query path** (`AnalyzeView.viewLECForMatches`): Overwrites
   `chartNodeIds` entirely with `satisfyingNodeIds`, then calls
   `loadLECChart`.
2. **Manual path** (`TreeDetailView.handleNodeClick` → Ctrl+click →
   `LECChartState.toggleChartSelection`): Adds/removes the clicked node
   (+ its direct children for portfolio nodes) within `chartNodeIds`,
   then calls `loadLECChart`.

Both paths write to the **same Var** and fire the **same endpoint**.
There is no persistent separation between "nodes the query found" and
"nodes the user picked". The v3 design introduces this separation by
splitting state into `satisfyingNodeIds` (query set, owned by
`AnalyzeQueryState`) and `userSelectedNodeIds` (manual set, new Var).

The question D7 decides is: **when the user Ctrl+clicks a node and a
query result is already displayed, what happens to the query set on the
chart?**

#### Relationship with existing code

| Code element | Current behaviour | Impact of D7 |
|---|---|---|
| `LECChartState.chartNodeIds` | Single flat `Set[NodeId]` for all sources | **Replaced** by two separate sources: `queryState.satisfyingNodeIds` + `userSelectedNodeIds`. The derived `chartRequest` signal merges them with palette tags. D7 decides whether the merge is a union (A), a replacement (B), or conditional (C). |
| `LECChartState.toggleChartSelection` | Toggle node in `chartNodeIds`, re-fetch chart | **Replaced** by `toggleUserSelection`. In A, this only touches `userSelectedNodeIds`. In B, it also clears query state. In C, it clears + offers restore. |
| `AnalyzeView.viewLECForMatches` | Overwrites `chartNodeIds` → calls `loadLECChart` | **Removed** as an imperative call. In v3, the reactive `chartRequest` signal fires when `satisfyingNodeIds` changes. But in D7-B, the query set might have been previously cleared by a Ctrl+click — the "View LEC" button (T3.5) may need to persist or be re-enabled. |
| `TreeDetailView.handleNodeClick` | Ctrl+click → `state.toggleChartSelection(nodeId)` | Rewired to `state.toggleUserSelection(nodeId)`. Unchanged across all D7 options — the click always mutates the user set. The difference is what happens to the *other* set. |
| `TreeDetailView` CSS: `node-chart-selected` | Aqua ring (`--curve-aqua-300`), driven by `chartNodeIds.contains(nodeId)` | In v3, this signal should reflect `userSelectedNodeIds` (the user's manual picks). Query-matched nodes already have `node-query-matched` (green). D7 does not affect this — all options use the same CSS signal. |
| `chartRequest` reactive signal (§3.10) | Does not exist yet | Created by v3. Combines `satisfyingNodeIds` + `userSelectedNodeIds`, tags overlaps with `Purple`. D7 determines whether `satisfyingNodeIds` is included in the combination or suppressed. |

#### Interplay with other settled decisions

| Decision | Interplay with D7 |
|---|---|
| **D1 (auto-trigger)** | All D7 options trigger the chart reactively when a query succeeds. The difference is what happens *after* — when the user starts Ctrl+clicking. In A, the auto-triggered chart persists alongside clicks. In B/C, the first click discards it. |
| **D1c (13-cap)** | In A: up to 13 green + 13 aqua + their purple overlap (≤ 26 total curves). In B: max 13 aqua at a time (query cleared). In C: same as B normally, same as A when restored. The cap per palette group is unaffected, but A has a higher total ceiling. |
| **D5 (enum CurvePalette)** | Three palette variants exist: Green, Aqua, Purple. In B, Purple and Green are only ever used during the brief auto-trigger phase before the first click. In A, all three are used simultaneously. Option B makes Green and Purple essentially transient values. |
| **D8 (overlap → purple)** | In A, overlap is meaningful — a node can be in both sets simultaneously, so Purple is used. In B, overlap is impossible (query set is cleared on first click). In C, overlap exists only while the restore toggle is active. If B is chosen, the Purple variant becomes unused and arguably D8 should be revisited. |
| **D2 (green tree highlight)** | In all options, the tree's `node-query-matched` highlighting persists until the next query (driven by `satisfyingNodeIds` which is owned by `AnalyzeQueryState`, not by chart state). The green highlights are unaffected by Ctrl+click in any D7 option — only the *chart* changes. |
| **D3/D4 (auto-expand)** | Unaffected. Expansion is additive and fires on query success. Ctrl+click never collapses the tree. |

#### Alternatives

---

**Option A: Coexist independently**

The query set (green) and user set (aqua) live in separate `Var`s. Each
is independently mutable. The `chartRequest` signal unions them with
overlap detection (purple). Ctrl+click only touches `userSelectedNodeIds`.
The query set persists on the chart until the user runs a new query
(which replaces the old `satisfyingNodeIds`).

State model:
```
chartRequest = f(satisfyingNodeIds, userSelectedNodeIds)
  where:
    overlap   = satisfyingNodeIds ∩ userSelectedNodeIds → Purple
    queryOnly = satisfyingNodeIds ∖ overlap             → Green
    userOnly  = userSelectedNodeIds ∖ overlap          → Aqua
```

Code impact: The `buildChartRequest` function (§3.10) is the complete
implementation. `toggleUserSelection` only mutates `userSelectedNodeIds`.
No conditional logic, no mode tracking, no extra UI.

| Pros | Cons |
|---|---|
| Cleanest separation of concerns — each `Var` owns one concept | Up to 26 curves on one chart when both sets are large |
| Three-palette design (D5, D8) is fully utilized — Green, Aqua, Purple all serve a purpose | More complex chart legend (three colour families) |
| User can compare their manual picks (aqua) against query results (green) side by side | User must run a new query to "clear" the green curves — no explicit dismiss |
| Overlap detection (Purple) gives instant visual feedback when the user Ctrl+clicks a query-matched node | If user Ctrl+clicks all 13 query-matched nodes, all turn Purple — is that useful or just confusing? |
| No new UI elements — the reactive signal handles everything | |
| `toggleUserSelection` is trivially simple — pure set toggle | |
| Consistent with the reactive `chartRequest` signal design (§3.10) — no imperative clearing | |

---

**Option B: Ctrl+click replaces all (wipe query set from chart)**

The first Ctrl+click after a query clears the query set from the chart.
From that point, only `userSelectedNodeIds` drives the chart. A new
query restores the query set (and clears the user set, or not — sub-
decision).

State model:
```
queryVisibleOnChart: Var[Boolean]  // starts true after query, set false on first click
chartRequest = f(
  if queryVisibleOnChart then satisfyingNodeIds else Set.empty,
  userSelectedNodeIds
)
```

Code impact: Requires a `queryVisibleOnChart: Var[Boolean]` flag. The
`toggleUserSelection` method must also set this flag to `false` on the
first mutation. The signal observer on `queryResult` must reset it to
`true` when a new query succeeds.

| Pros | Cons |
|---|---|
| Simple mental model: chart shows one thing at a time | Loses the query visualization on the first Ctrl+click — user cannot compare |
| Never more than 13 curves after the first click | Makes `CurvePalette.Green` and `CurvePalette.Purple` transient — only visible during the auto-trigger, before the first click; D8 (overlap → Purple) becomes nearly unused |
| Feels natural for users who think "I want to look at my own picks now" | If the user wants the query chart back, they must re-run the query (or see Option C) |
| Less visual clutter — only aqua curves after the switch | The `queryVisibleOnChart` flag is imperative state management, somewhat at odds with the reactive signal approach (§3.10, ADR-019 P4) |
| | The "View LEC for N matching nodes" button (T3.5) needs revisiting — it currently calls `viewLECForMatches()` which would need to re-enable the flag |

---

**Option C: Ctrl+click clears query, with "Restore query results" button**

Like B, but a toggle button appears below the chart allowing the user to
bring back the query set. When toggled on, the query set (Green) is
merged back in; when toggled off, only the user set (Aqua) is shown.

State model:
```
showQueryOnChart: Var[Boolean]  // starts true after query, toggled by button
chartRequest = f(
  if showQueryOnChart then satisfyingNodeIds else Set.empty,
  userSelectedNodeIds
)
```

Code impact: Same `Var[Boolean]` as B, but instead of being silently set
to `false` on Ctrl+click, it's controlled by an explicit toggle button.
The button needs rendering logic and conditional visibility (only shown
when `satisfyingNodeIds.nonEmpty`).

| Pros | Cons |
|---|---|
| User has explicit control over whether query results are visible | New UI element: button/toggle below the chart |
| Recoverable — no need to re-run the query to see its results | The toggle's state interacts with new queries (should a new query auto-enable it?), Ctrl+click (should a click auto-disable it?), and tree selection changes (should resetting the tree clear it?) — combinatorial state complexity |
| Fewer curves when toggle is off (same as B) | When toggle is on, same as A (up to 26 curves), so it doesn't eliminate clutter — just makes it opt-in |
| The toggle explicitly communicates "you can have both" | Toggle is redundant if the user rarely cares about the query set after clicking — extra UI for an uncommon interaction |
| Could be implemented later as an enhancement to either A or B | The "View LEC" button (T3.5) overlaps in purpose with this toggle — two buttons that affect chart content in different ways |

---

#### Relationship between options

```
A (coexist)
  └── Simplest code. Green always on chart until next query.
      If clutter is a problem → refine by adding C's toggle *later*.

B (replace)
  └── Simplest UX. One source at a time.
      If users miss query chart → add C's restore button *later*.

C (toggle)
  └── Most UI surface. Subsumes both A and B:
        toggle ON  = A's behaviour
        toggle OFF = B's behaviour
      But upfront complexity is highest.
```

Options A and B are both clean starting points. C is a superset but
costs more to build. A can evolve to C (add a hide button). B can
evolve to C (add a restore button). Starting from A preserves more
information on screen (both sets visible by default); starting from B
preserves simplicity.

#### Assessment

**The v3 architecture (separate `Var`s, reactive `chartRequest`,
three-palette enum) was designed with Option A in mind.** The
`buildChartRequest` function (§3.10) directly implements A's union +
overlap model. Choosing B or C requires adding conditional state
(`queryVisibleOnChart` / `showQueryOnChart`) that the reactive signal
must read — introducing imperative mode tracking into an otherwise
declarative pipeline.

Option A is also the only choice that gives D8 (overlap → Purple) a
permanent role. Under B, Purple appears only in the brief moment
between auto-trigger and first click. Under C, it depends on toggle
state.

The clutter concern (up to 26 curves) is mitigated by: (a) p95-based
ordering puts the most important curves darkest/most visible, (b) the
13-cap per group limits each palette, (c) in practice query+manual
sets partially overlap (reducing total), (d) if clutter proves a real
problem, adding a "hide query" toggle (evolving A into C) is
non-breaking.

**Recommendation:** Option A — coexist independently.

---

## 4 Feature 2: Light Green Highlighting for Matching Nodes

### 4.1 Current state

`node-query-matched` used **aqua** (`--info` / `--info-surface`,
sourced from `--curve-aqua-400`).

### 4.2 SETTLED — D2: Colour strategy

**Decision:** New `--query-match` / `--query-match-surface` token pair
from the green CSS curve palette:

```css
--query-match:         #86efac;   /* curve-green-300 */
--query-match-surface: rgba(134, 239, 172, 0.12);
```

Mirrors the border-left + translucent background pattern of
`node-chart-selected`.

### 4.3 CSS changes

- Add two custom property declarations.
- Update `node-query-matched` selectors to use new tokens.
- ~2 new declarations + ~4 selector updates.

---

## 5 Feature 3: Auto-Expand Tree to Show Matching Nodes

### 5.1 SETTLED — D3: Expansion strategy

**Decision:** Strategy A — bottom-up ancestor expansion, additive.

```
for each nodeId in satisfyingNodeIds:
    for each ancestorId in tree.index.ancestorPath(nodeId):
        add ancestorId to expandedNodes
```

Preserves the user's existing expand/collapse state. O(matchCount × depth).

### 5.2 SETTLED — D4: When to auto-expand

**Decision:** On every successful query.

### 5.3 Implementation sketch

```scala
def expandToRevealNodes(matchedIds: Set[NodeId]): Unit =
  treeViewState.selectedTree.now() match
    case LoadState.Loaded(tree) =>
      val ancestors = matchedIds.flatMap(tree.index.ancestorPath)
      treeViewState.expandedNodes.update(_ ++ ancestors)
    case _ => ()
```

---

## 6 Orchestration: Combined Trigger

Features 1 and 3 share the same trigger: a successful query with matches.

**Auto-expand** fires from a signal observer on `queryResult`:
```scala
queryState.queryResult.signal.changes --> {
  case LoadState.Loaded(resp) if resp.satisfied && resp.satisfyingNodeIds.nonEmpty =>
    expandToRevealNodes(resp.satisfyingNodeIds.toSet)   // Feature 3
  case _ => ()
}
```

**Auto-LEC** fires reactively via `chartRequest.changes` (§3.10) —
when `satisfyingNodeIds` updates, the derived `chartRequest` signal
recomputes and fires the POST.

Feature 2 (CSS colour) is independent.

---

## 7 End-to-End Walkthrough

### Happy path: query → green chart

**Step 1 — User submits query.**
`AnalyzeView.runQuery()` → `queryState.executeQuery()` → HTTP POST
`/w/{key}/risk-trees/{treeId}/query` with `QueryRequest("...")`.

**Step 2 — Server returns matches.**
`QueryResponse` with `satisfyingNodeIds: List[NodeId]` (renamed per §1b; same shape as today).

**Step 3 — Client processes result.**
1. `satisfyingNodeIds` signal updates → tree nodes highlighted green.
2. Signal observer fires `expandToRevealNodes()` → tree opens.
3. `chartRequest` signal recomputes → `LECChartRequest` with all matched
   node IDs tagged `CurvePalette.Green`.

**Step 4 — Client sends chart request.**
HTTP POST `/w/{key}/risk-trees/{treeId}/lec-chart` with body:
```json
{ "curves": [
    { "nodeId": "01HX9...", "palette": "green" },
    { "nodeId": "01HXA...", "palette": "green" }
  ] }
```

**Step 5 — Server builds coloured Vega-Lite spec.**
1. Controller extracts `paletteMap` + `nodeIds`.
2. Service calls `getLECCurvesMulti(treeId, nodeIds)` → gets curves with
   quantiles.
3. `assignPaletteColours`: groups by `Green`, sorts by p95 desc, assigns
   shades `#03170b` (darkest, highest p95) → `#f1fdf5` (lightest).
4. Builder reads `cc.hexColor.value` from each `ColouredCurve`, injects
   into Vega-Lite `encoding.color.scale.range`.
5. Returns Vega-Lite JSON.

**Step 6 — Client renders chart.**
`lecChartSpec.set(LoadState.Loaded(spec))` → VegaEmbed renders.

### Manual selection: Ctrl+click → aqua chart

**Step 7 — User Ctrl+clicks a node.**
1. `onClick` event fires → Ctrl+click filter → `nodeId` emitted into
   `state.userSelectionToggle` bus → internal observer checks cap
   (< 13), adds to `userSelectedNodeIds`.
2. `chartRequest` signal recomputes:
   - query nodes → `green`
   - user node → `aqua`
   - overlap → `purple`
3. POST fires with updated entries.
4. Server sorts each group independently by p95, assigns shades, builds spec.

---

## 8 Decision Summary

### Settled decisions

| # | Decision | Choice | Section |
|---|---|---|---|
| D1 | Auto-trigger LEC on query success | Yes — reactive via `chartRequest` signal | §3.11 |
| D1c | Large result set handling | Query: top 13 by p95 (silent). User: hard 13 with error banner. | §3.12 |
| D2 | Highlight colour strategy | New `--query-match` green token | §4.2 |
| D3 | Expansion strategy | Bottom-up ancestor, additive | §5.1 |
| D4 | When to auto-expand | Every successful query | §5.2 |
| D5 | Palette identifier type | Scala 3 `enum CurvePalette` (not opaque, not case class) | §3.3 |
| D6 | Where quantile sorting happens | Server-side only — no extra round trips | §3.4 |
| D8 | Overlap handling | Third palette (purple) for nodes in both sets | §3.13 |
| D7 | Ctrl+click interaction model | A — coexist independently | §3.14 |

### Open decisions

None — all decisions are settled.

---

## 9 Files Modified (Estimated)

> **Note:** The `app` module uses short package names (`app.state`,
> `app.views`), not the `com.risquanter.register.app` prefix used by
> `common` and `server`.

| File | Change | New? |
|---|---|---|
| `modules/common/src/main/scala/com/risquanter/register/domain/data/CurvePalette.scala` | New `CurvePalette` enum + JSON codecs | **New** |
| `modules/common/src/main/scala/com/risquanter/register/http/requests/LECChartRequest.scala` | New `LECChartRequest` + `LECChartCurveEntry` DTOs | **New** |
| `modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceEndpoints.scala` | Change `getWorkspaceLECChartEndpoint` body to `jsonBody[LECChartRequest]` | Modify |
| `modules/server/src/main/scala/com/risquanter/register/simulation/LECChartSpecBuilder.scala` | Replace `generateMultiCurveSpec` signature to accept `Vector[ColouredCurve]`. Add `CurvePaletteRegistry`. Delete `themeColorsRisk`, `stableColor`, and `generateSpec` (test-only convenience). | Modify |
| `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala` | Add `HexColorStr` constraint + `HexColor` opaque type (same file as all other opaques). No JSON codecs. | Modify |
| `modules/server/src/main/scala/com/risquanter/register/simulation/ColouredCurve.scala` | New server-local `ColouredCurve(curve: LECNodeCurve, hexColor: HexColor)` case class | **New** |
| `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala` | Add `paletteMap` param to `getLECChart` trait method | Modify |
| `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala` | Implement new `getLECChart` signature, add `assignPaletteColours` | Modify |
| `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceController.scala` | Destructure `LECChartRequest`, build `paletteMap` | Modify |
| `modules/app/src/main/scala/app/state/LECChartState.scala` | Add `userSelectedNodeIds`, `userSelectionToggle` bus, `buildChartRequest`, reactive `chartRequest` signal | Rework |
| `modules/app/src/main/scala/app/state/TreeViewState.scala` | Update convenience accessors for split chart state (see §9.1) | Modify |
| `modules/app/src/main/scala/app/views/AnalyzeView.scala` | Signal observer for auto-expand; reactive chart subscription; remove `viewLECForMatches()` | Modify |
| `modules/app/src/main/scala/app/views/TreeDetailView.scala` | Wire Ctrl+click to `userSelectionToggle` bus instead of `toggleChartSelection` | Modify |
| `modules/app/styles/app.css` | `--query-match` tokens + selector updates | Modify |

**Not modified:** `LECNodeCurve` (`LEC.scala`) — colour is a
presentation concern, kept on the server-local `ColouredCurve` wrapper.

**No new endpoints.** No `QueryResponse` change (rename is a
prerequisite — §1b). No quantiles endpoint. No client-side quantile
cache. `HexColor` is an Iron opaque type in `OpaqueTypes.scala` (§3.8
step 1) — the client never uses it (no JSON codecs, no wire presence).

**Estimated total:** ~160–190 lines of new/changed Scala, ~10 lines of
CSS, 3 new files.

### 9.1 TreeViewState delegation changes

`TreeViewState` owns `LECChartState` and currently exposes three
convenience accessors to views:

```scala
// Current — modules/app/src/main/scala/app/state/TreeViewState.scala
def chartNodeIds: StrictSignal[Set[NodeId]] = chartState.chartNodeIds.signal
def lecChartSpec: StrictSignal[LoadState[String]] = chartState.lecChartSpec.signal
def toggleChartSelection(nodeId: NodeId): Unit = chartState.toggleChartSelection(nodeId)
```

Under the new design, `LECChartState` splits the single `chartNodeIds`
into two sources (`satisfyingNodeIds` from `AnalyzeQueryState` +
`userSelectedNodeIds`). The question is: what should `TreeViewState`
expose?

**Consumer analysis — who reads what:**

| Consumer | Needs | Current access |
|---|---|---|
| `TreeDetailView.renderNode` → `isChartSelected` CSS class | A signal that is true when the node is in the **user** set (manual picks get the aqua ring `node-chart-selected`, from `--curve-aqua-300`) | `state.chartNodeIds.map(_.contains(nodeId))` |
| `TreeDetailView.renderNode` → `isQueryMatched` CSS class | A signal that is true when the node is in the **query** set (green highlight) | `queryMatchedNodes.map(_.contains(nodeId))` — already a separate parameter |
| `TreeDetailView.handleNodeClick` → Ctrl+click | A way to emit a node toggle event to `LECChartState` | `state.toggleChartSelection(nodeId)` |
| `AnalyzeView` → chart panel | The Vega-Lite spec signal | `treeViewState.lecChartSpec` |

**Key insight:** Tree highlighting already uses **two separate signals**:
`isChartSelected` (aqua ring, `--curve-aqua-300`) and `isQueryMatched` (green background).
These map exactly to `userSelectedNodeIds` and `satisfyingNodeIds`
respectively. The query signal is already piped via a separate
`queryMatchedNodes` parameter — it does not flow through
`TreeViewState.chartNodeIds`. Therefore:

- `chartNodeIds` → rename to `userSelectedNodeIds` and wire to
  `chartState.userSelectedNodeIds.signal`. This signal drives the
  `node-chart-selected` CSS class unchanged.
- `toggleChartSelection` → replace with `userSelectionToggle` forwarding
  to `chartState.userSelectionToggle` (`WriteBus[NodeId]`).
- `lecChartSpec` → unchanged.

**No composed signal is needed.** The union of both sets is computed
inside `LECChartState.buildChartRequest` for the chart POST, not for
CSS. Each CSS class reads from exactly one signal. `TreeViewState`
exposes them individually because the consumers need them individually.

---

## 10 Critical Evaluation & Improvement Suggestions

### 10.1 Strengths of this design

1. **Single round-trip.** Client sends `(nodeId, paletteName)`, server
   returns Vega-Lite. No intermediate quantile fetch, no client-side
   colour resolution. The simplest possible interaction.

2. **Server has all the data it needs.** Quantiles are already computed
   and cached when `getLECCurvesMulti` runs. The `assignPaletteColours`
   step reads from data that is already in memory — zero extra I/O.

3. **`CurvePalette` enum is the right type.** Follows the `ValidationErrorCode`
   pattern. Closed set, exhaustive match, lightweight serialisation. No
   over-engineering with Iron constraints on what is fundamentally a
   fixed vocabulary.

4. **Three-palette overlap is explicit.** The user can see at a glance
   which curves are query-only (green), manual-only (aqua), or both
   (purple). No guessing, no precedence rules.

5. **Client is dramatically simpler than v2.** No quantile cache, no
   palette constants, no `assignColours` function. `HexColor` (Iron
   opaque in `OpaqueTypes.scala`, §3.8 step 1) has no JSON codecs and
   no wire presence — the client only knows three words: `Green`,
   `Aqua`, `Purple`.

6. **p95-based ordering is domain-meaningful** (§3.5). Higher-risk
   curves get darker colours. This matches how risk professionals scan
   LEC charts — the fat tails should be visually prominent.

### 10.2 Risks and weaknesses

1. **Palette registry duplicates CSS values.** The 13-shade hex arrays
   in `CurvePaletteRegistry` must match `app.css`. This duplication is
   structurally unavoidable: Vega-Lite renders in a `<canvas>` element,
   not DOM, so it cannot resolve CSS custom properties (`var(--x)`) at
   runtime. The server must embed raw hex strings in the Vega-Lite JSON.
   **Mitigation:** Cross-reference comment in both files. Build-time
   codegen (CSS → Scala or Scala → CSS) deferred to §11.

2. **Up to 26 curves on one chart.** Visually busy with overlapping lines.
   **Mitigation:** p95 ordering ensures the most important curves are
   darkest (most visible). In practice, query + manual sets partially
   overlap, reducing total count. The 13-cap-per-group prevents the
   total from exceeding 26.

3. **Breaking change** to `/lec-chart` endpoint body.
   **Mitigation:** Internal API behind capability URLs. No external
   consumers. Integration tests need updating.

4. **Builder sort order change.** The current
   `generateMultiCurveSpec(Vector[LECNodeCurve])` sorts root first then
   alphabetically. The new signature `(Vector[ColouredCurve])` preserves
   input order (§3.8 step 4), which is p95-sorted per group. Old
   callers: `generateSpec` (single-curve convenience, test-only) is
   updated to wrap in `ColouredCurve`. No backward-compat concern.

5. **Server-side palette coupling.** The server must know about CSS
   palettes — arguably a presentation concern.
   **Mitigation:** The server already builds Vega-Lite specs (a
   presentation artefact). The palette registry is a natural extension
   of that responsibility. The `common` module is the right home for
   `CurvePalette` (enum) and `CurvePaletteRegistry` can live in
   `server` or `common`.

### 10.3 Improvement suggestions

#### 10.3.1 Palette registry in `common` (shared JVM+JS)

Define `CurvePaletteRegistry` in `common` alongside `CurvePalette`.
This makes the hex values available to both server (spec builder) and
client (e.g. for legend styling outside Vega-Lite).

| Pros | Cons |
|---|---|
| Single source of truth | Moves visual constants into domain module |
| Server and client share palette data | Minor conceptual leak |

**Assessment:** The `CurvePalette` enum already lives in `common`. Its
hex values are a natural companion. The alternative — duplicating in
`server` — is worse.

#### 10.3.2 Reactive chart loading via derived signal

The `chartRequest` signal (§3.10) with `debounce(100)` is the idiomatic
Laminar/FRP approach. It replaces all imperative `loadLECChart` call
sites with a single reactive subscription.

| Pros | Cons |
|---|---|
| One subscription, zero manual trigger wiring | 100ms debounce delay |
| Eliminates inconsistency between state and request | Implicit triggering — harder to debug |
| Natural ADR-019 P4 (derived signals) | |

**Assessment:** Recommended. The imperative calls in the current code
exist because there was no reactive source of chart entries. With
`chartRequest` as a signal, the reactive approach is cleaner.

#### 10.3.3 ~~Replace `themeColorsRisk` with registry fallback~~

**Incorporated into the main design (§3.8 step 4).** The legacy
`themeColorsRisk` (10-colour BCG-style palette) and `stableColor`
(hashCode-based assignment) are removed. All colour assignment flows
through `CurvePaletteRegistry` and `assignPaletteColours`. This
eliminates the dual-colour-system concern and the hash-collision risk
(13 shades per palette vs 10 total).

---

## 11 Out of Scope

- **Client-side LEC caching:** Separate concern (memoize spec by request hash).
- **Scroll-to-node:** Requires DOM `scrollIntoView()` — separate enhancement.
- **SSE-driven pre-warming:** `SSEEvent.LECUpdated` could trigger
  background simulation. Noted but out of scope.
- **Additional palette groups:** The design supports adding more
  `CurvePalette` variants (e.g. `Orange` for mitigated risks) with a
  one-line enum extension.
- **Build-time palette sync.** The `CurvePaletteRegistry` hex values
  (§3.8 step 1) duplicate `app.css` curve palette definitions. This
  duplication is structurally unavoidable: Vega-Lite renders in a
  `<canvas>`, not DOM, so it cannot resolve CSS custom properties at
  runtime. The server must embed raw hex strings in the Vega-Lite JSON.
  This is the same structural constraint that the (now-removed)
  `themeColorsRisk` array addressed. `CurvePaletteRegistry` replaces it.
  A future improvement could generate one from the other at build time
  (CSS → Scala codegen or Scala → CSS codegen), but is out of scope
  for this plan.

---

## 12 Testing Strategy

### 12.1 Pure function unit tests (JVM — `sbt test`)

All server-side pure functions are directly testable with standard
Scala specs.

| Function | Test class | Cases |
|---|---|---|
| `assignPaletteColours` | `AssignPaletteColoursSpec` | (1) Single palette group — sorted by p95 desc, shades assigned darkest→lightest. (2) Multiple palette groups — each group sorted independently. (3) Empty input → empty output. (4) More than 13 curves in one group → last shade re-used for overflow (rank clamped). (5) Missing p95 quantile → defaults to 0.0, sorts last. (6) All curves have the same p95 → stable ordering (no crash), arbitrary shade assignment. |
| `buildChartRequest` | `BuildChartRequestSpec` | (1) Disjoint sets → no overlap, correct palette tags. (2) Partial overlap → overlap nodes get `Purple`, remainder correct. (3) Full overlap → all `Purple`. (4) Empty query set → only `Aqua` entries. (5) Empty user set → only `Green` entries. (6) Both empty → empty entries list. |
| `CurvePalette` JSON | `CurvePaletteSpec` | (1) Encode → decode round-trip = identity for all variants. (2) Unknown string → Left with descriptive error. (3) Case-insensitive decode ("GREEN", "Green", "green" all succeed). |
| `LECChartRequest` JSON | `LECChartRequestSpec` | (1) Encode → decode round-trip. (2) Empty `curves` list accepted at codec level (validation is separate). |
| `HexColor` | `HexColorSpec` | (1) Valid `#rrggbb` accepted. (2) Invalid strings rejected (no `#`, wrong length, non-hex chars). (3) Case-insensitive (`#aaBBcc` accepted). |
| `CurvePaletteRegistry` | `CurvePaletteRegistrySpec` | (1) Every `CurvePalette` variant has an entry. (2) Each entry has exactly 13 `HexColor` values. (3) Compile-time safe — `refineUnsafe` on literals. |
| `generateMultiCurveSpec(Vector[ColouredCurve])` | `LECChartSpecBuilderSpec` | (1) Colour range in output JSON matches input `hexColor.value` strings. (2) Input order preserved (no re-sort). (3) All curve IDs appear in legend domain. (4) Existing tests that called `generateSpec(LECNodeCurve)` are updated to wrap in `ColouredCurve` with `HexColor` — verifies backward compat of the output JSON structure. |

### 12.2 Integration tests (server — `sbt server-it/test`)

The `/lec-chart` endpoint changes from `List[NodeId]` to
`LECChartRequest`. Existing integration tests in `server-it` must be
updated to send the new request body shape.

| Test | Change |
|---|---|
| Existing LEC chart happy-path test | Update request body from `["nodeId1", ...]` to `{"curves": [{"nodeId": "...", "palette": "green"}, ...]}`. Verify response is valid Vega-Lite JSON with colour range matching green palette shades. |
| New: palette grouping integration test | Send mixed `green` + `aqua` entries. Verify response JSON has both green and aqua hex values in `encoding.color.scale.range`. |

### 12.3 Frontend (manual — no JS test harness)

The `app` module currently lacks a ScalaJS test harness. Frontend
changes (`LECChartState`, `TreeViewState`, `AnalyzeView`,
`TreeDetailView`) are verified by manual testing:

1. Run a query with ≥ 2 matching nodes → verify green curves appear,
   tree nodes highlighted green, tree auto-expanded.
2. Ctrl+click 2 additional nodes → verify aqua curves added, green
   curves persist (D7-A coexist).
3. Ctrl+click a query-matched node → verify curve turns purple (overlap).
4. Ctrl+click 14th node → verify error banner appears, selection
   unchanged.
5. Run a new query → verify green curves update, aqua curves persist.
6. CSS: verify `node-query-matched` uses green (`--query-match`, from
   `--curve-green-300`), and `node-chart-selected` uses aqua ring
   (`--curve-aqua-300` / `rgba(123, 218, 243, 0.12)` surface).
