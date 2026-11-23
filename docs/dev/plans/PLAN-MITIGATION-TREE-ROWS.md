# Plan: mitigation rows behave as node rows, and the tree select comes first

**Status:** Implemented 2026-11-22 at version 0.26.1. `commonJVM/test`,
`server/test`, `app/test`, `serverIt/test` and BATS suite A are green. The
browser check in the verification plan is the user's and closes the plan.
The continuation section "A curve request states valuations, and the card
becomes a grid" is implemented. Its Part A landed at version 0.27.0. Its
Part B was implemented on 2026-11-23, with `commonJVM/test`, `server/test`,
`app/test` and `serverIt/test` green. BATS suites C and A and the browser check
of Part B's verification plan are the user's and close the plan.
**Date:** 2026-11-22.
**Plan code:** MTR. **Inventory:** `docs/dev/plans/PLAN-MITIGATION-TREE-ROWS-INVENTORY.md`,
which the user creates.

The change is confined to the `app` module (Scala.js frontend), its stylesheet,
its tests, and the user guide that describes the Analyze view. No endpoint,
DTO, wire shape or simulation figure changes. The continuation section at the
end is wider: its Part A changes the body and the answer of both analysis
endpoints, in the `common`, `server` and `app` modules.

---

## Objective

This section and the worked example after it record the plan as first
implemented. Part B of the continuation replaced the Ctrl+click and
Ctrl+Shift+click gestures, the coloured border on an applied mitigation row and
the checkboxes on the mitigation lines with a grid of boxes. The shared row
component, the row alignment, the hover highlight, the select order and the
`computeSeed` change described here still hold.

On the Analyze view a tree card lists, under each node a mitigation is scoped
at, one row per mitigation. Today that row is a separate `div` styled by
`.tree-mitigation-row`. It differs from a node row in four ways the user
reported:

1. It is indented by `padding-left` alone, while a node row is indented by an
   indent spacer plus a 16 px expand-arrow slot. So a mitigation row starts to
   the left of the node it belongs to.
2. It has no hover background.
3. It shows its state with a `☐`/`☑` glyph, and its click target is a small
   area.
4. Its Ctrl+click flips the mitigation, while a node row's Ctrl+click adds and
   Ctrl+Shift+click removes.

This plan draws a mitigation row with the same component, the same handler and
the same styles as a node row:

- **Ctrl+click** (Cmd+click on a Mac) applies the mitigation at that node. If
  the node is not on the chart, it is added, so the node's mitigated curve is
  drawn. This is how the row gets a curve colour.
- **Ctrl+Shift+click** removes the mitigation at that node. The node stays on
  the chart.
- **A plain click** does nothing. On a node row a plain click selects the node
  for its detail, and a mitigation has no detail.
- **An applied row** carries the coloured left border of its node's curve,
  drawn by the same function that draws a charted node's border. A row whose
  node is not on the chart has no curve, so it has no border.
- **Hovering a row** highlights the node's curves on the chart, as hovering the
  node does.
- **The row's icon and label** are both click targets, as on a node row.

Two further changes travel with it:

- In both kinds of tree card, the tree select comes before the branch select.
- `computeSeed` in `AnalyzeView.scala` branches with a `match` instead of an
  `if`/`else`.

The mitigation lines at the top of a card, with their checkboxes, are not
changed.

---

## Worked example — the gestures on one tree

These gestures were removed by Part B of the continuation, whose own worked
example under "What a card's state is" describes the grid.

The tree of `docs/user/MITIGATION-WALKTHROUGH.md`, on branch `with-controls`,
nothing charted, no mitigation applied.

| Action | Selection after it | Chart after it | Row `MFA and awareness training` under `Ransomware` |
|---|---|---|---|
| Ctrl+click that row | MFA at `Ransomware` only | `Ransomware`, inherent and mitigated | border in `Ransomware`'s curve colour |
| Ctrl+click it again | unchanged (already applied) | unchanged | unchanged |
| Ctrl+Shift+click it | none | `Ransomware`, inherent only | no border |
| Ctrl+Shift+click it again | unchanged (already removed) | unchanged | no border |
| Ctrl+click it, then Ctrl+Shift+click the `Ransomware` node row | MFA at `Ransomware` only | empty | no border, because no curve |

The second and fourth rows show the same no-op rule a node row has: an add on
something already added, and a remove on something already removed, change
nothing.

---

## ADR alignment

- **ADR-019** (frontend component architecture). Compliant. `TreeNodeRow`
  stays stateless. The selection stays owned by the card's caller and is
  changed only through `onSelectionEdit`. The chart selection is changed only
  through `userSelectionToggle`.
- **ADR-001** (validation). Compliant. Every new selection is built by
  `SelectionEdit`, which builds through `MitigationSelection.create`.
- **ADR-011** (imports). Compliant. No new import is local.
- **ADR-037** (constants and closed sets). Compliant. The three gestures are a
  closed set, so they become an `enum`, `RowGesture`. No new string literal is
  introduced except the icon's path data, whose origin is stated.

---

## Exact signatures

### `RowGesture` — new, private to `TreeDetailView`

Family search: `grep -rn "enum .*Gesture\|Gesture" modules/app/src/main/scala`
finds nothing. There is no existing family, and this is the first. It sits in
`TreeDetailView`, its only user, after the pattern of `MitigationPanel.StaleBadgeAction`,
an `enum` declared in the object that uses it.

```scala
/** What a click on a tree card row asks for. */
private enum RowGesture:
  /** Ctrl+click or Cmd+click. */
  case Add
  /** Ctrl+Shift+click or Cmd+Shift+click. */
  case Remove
  /** A click with neither key held. */
  case Plain

private object RowGesture:
  def of(ev: org.scalajs.dom.MouseEvent): RowGesture =
    (ev.ctrlKey || ev.metaKey, ev.shiftKey) match
      case (true, true)  => Remove
      case (true, false) => Add
      case (false, _)    => Plain
```

### `TreeDetailView` — one shared handler, one shared border rule, one shared row decoration

The node row's inline click handler becomes this function, and the mitigation
row calls it too. `add` and `remove` must each do nothing when there is
nothing to do, which is the no-op rule of the worked example.

```scala
/** The tree card's click rule for a node row and a mitigation row alike. A
  * Ctrl gesture adds or removes, unless the card mirrors the baseline, when it
  * shows the locked notice instead. A plain click runs `plain`. */
private def onRowGesture(
  ev:     org.scalajs.dom.MouseEvent,
  view:   NodeView,
  add:    () => Unit,
  remove: () => Unit,
  plain:  () => Unit
): Unit
```

The border rule takes the colour instead of the map, so a node row and a
mitigation row read the same rule. Its existing `if` on query matching becomes
part of the `match`.

```scala
/** The left border of a row: solid in the curve colour, wider while hovered;
  * dotted for a query match with no curve; none otherwise. */
private def borderStyleFor(
  colour:       Option[HexColor],
  queryMatched: Boolean,
  isHovered:    Boolean
): String
```

The decoration every row on the card carries, which both row kinds call:

```scala
/** A tree card row's border and hover: the border from `border`, and hovering
  * highlights the curves of `curveNode`. */
private def decorateRow(
  row:       HtmlElement,
  border:    Signal[String],
  curveNode: NodeId,
  view:      NodeView
): HtmlElement
```

The leaf-row spacer, which a mitigation row uses so its icon lines up with a
leaf's:

```scala
/** The empty expand-arrow slot of a row with no children. */
private def toggleSpacer(): HtmlElement
```

`renderMitigationRow` keeps its signature and is rewritten to build a
`TreeNodeRow` at `depth`, with `NodeKind.Mitigation`, the mitigation's name as
label, `toggleSpacer()` as prefix, and the stale badge as suffix:

```scala
private def renderMitigationRow(node: NodeId, mitigation: Mitigation, view: NodeView, depth: Int): HtmlElement
```

Its `add` applies the mitigation at `node` and charts `node` when it is not
charted. Its `remove` removes the mitigation at `node`. Its `plain` does
nothing. Its border is `borderStyleFor(colour, false, hovered)`, where `colour`
is the node's curve colour while the selection applies the mitigation at
`node`, and `None` otherwise.

### `SelectionEdit` — two idempotent edits

Family search: `SelectionEdit` in
`modules/app/src/main/scala/app/state/SelectionEdit.scala` already holds the
interface's selection edits, among them
`def toggleAt(selection: MitigationSelection.Enumerated, id: MitigationId, node: NodeId, scope: Set[NodeId])`.
The two new edits join it and reuse `toggleAt`'s rule. No other type denotes
this.

```scala
/** Apply `id` at `node` of its resolved scope. A selection that already
  * applies it there is returned unchanged. */
def applyAt(
  selection: MitigationSelection.Enumerated,
  id:        MitigationId,
  node:      NodeId,
  scope:     Set[NodeId]
): Validation[ValidationError, MitigationSelection.Enumerated]

/** Stop applying `id` at `node`. A selection that does not apply it there is
  * returned unchanged. */
def removeAt(
  selection: MitigationSelection.Enumerated,
  id:        MitigationId,
  node:      NodeId,
  scope:     Set[NodeId]
): Validation[ValidationError, MitigationSelection.Enumerated]
```

`toggleAt` keeps its signature, and the two new edits call it. Its only
production caller, `renderMitigationRow`, stops calling it.

### `TreeNodeRow.NodeKind` — one case added

Family search: `NodeKind` in
`modules/app/src/main/scala/app/components/TreeNodeRow.scala`, whose existing
member is `case Portfolio, Leaf`. The new case joins it. A mitigation is not a
risk node, but the enum chooses the row's icon, which is what a mitigation row
needs.

```scala
enum NodeKind:
  case Portfolio, Leaf, Mitigation
```

The icon `match` in `TreeNodeRow.apply` gains
`case NodeKind.Mitigation => Icons.mitigation("node-icon")`.

### `Icons.mitigation` — new

Family search: `Icons` in `modules/app/src/main/scala/app/components/Icons.scala`,
for example `def leaf(cls: String = "node-icon"): SvgElement`. The path data is
Lucide's `shield` icon (ISC licence, the set the file already draws from).

```scala
/** Lucide `shield` — mitigation row. */
def mitigation(cls: String = "node-icon"): SvgElement =
  icon24(
    svg.cls := cls,
    svg.path(svg.d := "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
  )
```

### `AnalyzeView.computeSeed` — the same rule as a `match`

The signature is unchanged. The body becomes a match on whether the baseline
holds a node, in the form `MitigationReadout.badgeAt` already uses:

```scala
NonEmptyChunk.fromIterableOption(baseline) match
  case Some(charted) => (None, charted.toList.filter(compareTreeNodeIds.contains).sortBy(_.value).take(cap))
  case None          => (activeRoot, activeRoot.filter(compareTreeNodeIds.contains).orElse(compareRoot).toList)
```

`AnalyzeView.scala` gains the import `zio.NonEmptyChunk`.

### The select order

In `TreeListView.apply`, both layouts place the tree select before
`leadingControl`. In `AnalyzeView.renderBranchPicker`, the
`compare-tree-select` comes before the `compare-branch-select` inside
`slot-select-pair`. No signature changes. The stylesheet rules for both pairs
give each select `flex: 1` and depend on no order.

### Stylesheet

`modules/app/styles/app.css` loses `.tree-mitigation-row` and
`.tree-mitigation-tick--on`. A mitigation row then uses `.tree-node-row`,
`.tree-detail-inline` and `.tree-node-row:hover`, as a node row does. No rule
is added.

---

## Open decisions

None. The gestures, the border and the plain click were ruled by the user in
the session that produced this plan. Charting the node on Ctrl+click is
derived from that ruling: an applied row carries its curve's colour, and only a
charted node has a curve.

---

## Terminology impact

None. `docs/user/TERMINOLOGY.md` defines a selection as "the set of mitigations
a valuation applies". The plan changes how the interface edits a selection, and
not what a selection is.

---

## Documents this plan makes stale

`docs/user/MITIGATION-WALKTHROUGH.md`, step 11: the parts "Open the workspace",
"Where each mitigation applies" and "Apply a mitigation at one node only"
describe the select order, the `☐`/`☑` glyph and the Ctrl+click flip. They are
rewritten in the implementing pass.

---

## Verification plan

Tests added or changed in `modules/app/src/test/scala/`:

- `app/state/SelectionEditSpec.scala`: `applyAt` on an unapplied mitigation
  applies it at that node. `applyAt` on an applied one returns the selection
  unchanged. `removeAt` mirrors both.
- `app/components/TreeNodeRowSpec.scala`: the test "enum has exactly two
  values" becomes "enum has exactly three values", because `Mitigation` is
  added, and a test checks that the three cases are distinct.
- `app/views/AnalyzeViewSeedSpec.scala`: unchanged. It already pins
  `computeSeed` and passes over the rewritten body.

Commands, each green:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
sbt serverIt/test
```

`serverIt/test` starts Irmin in Docker, so the register-dev skill's leaked-state
cleanup runs before it. BATS suite A runs at the end, and is the user's to run:

```bash
tests/bats/run-suites.sh A
```

A check in the browser on the walkthrough's data, which the user carries out:
on `with-controls`, every row of the worked example gives the stated result,
mitigation rows start to the right of their node, and hovering a mitigation row
shows the hover background.

Version: PATCH on landing, because shipped frontend code changes.

---

## Continuation — a curve request states valuations, and the card becomes a grid

**Status:** rulings recorded 2026-11-22. Part A is implemented at version
0.27.0, and its BATS run and browser check are the user's. Part B is specified
in full as of 2026-11-23 and has no open decision.

### The defect

A tree card holds one selection, and the curve request carries one selection.
The answer adds the inherent curve of every requested node whether or not it
was asked for, and it leaves out the second curve of a node the selection does
not reach. So a caller cannot ask for one node's inherent curve beside another
node's mitigated curve, and comparing two mitigations at one node takes two
requests whose curves share no loss axis. In the browser the same limit shows
as the Inherent / Mitigated / Both control, which applies to every node of a
card at once, and as the need for a second card to compare two mitigations.

### Rulings

- **MTR-D-6 (ruled 2026-11-22).** A curve request is a list of valuations.
  Each valuation states one selection and the nodes whose curves are wanted
  under it. The answer has one entry per valuation, in request order, and each
  entry repeats its selection. Nothing is added to what was asked and nothing
  asked for is left out.
- **MTR-D-7 (ruled 2026-11-22).** A selection on the wire is one of three
  alternatives: inherent, residual, or a map from mitigation to how far it
  reaches. The reach is the mitigation's whole resolved scope or a list of
  nodes. The residual selection becomes statable in a request. The two lists
  `fullScope` and `nodesOnly` go.
- **MTR-D-8 (ruled 2026-11-22).** The tree card becomes a grid. Rows are nodes
  and columns are selections. A tick in a cell draws that node's curve under
  that column's selection. Part B holds the design.
- **MTR-D-1 to MTR-D-5 are superseded by MTR-D-8.** They described a chart
  entry as a node paired with one of two curves, the gestures of a mitigation
  row, and the future of the Inherent / Mitigated / Both control. The grid
  replaces all three.
- Kept from the earlier discussion, ruled 2026-11-22: a node list naming a node
  outside a mitigation's resolved scope has no effect there and is not an
  error. A mitigation the tree does not hold fails the request.

### Why Part A comes first

The grid's state is built from the request types Part A introduces: a column is
a selection, and a tick is one node of one valuation. Part A also lands with
the card's present behaviour unchanged, so each part can be checked alone.

---

### Part A — the curve request states valuations

#### The request and the answer

The route, the `X-Branch` header and the query parameters `at`, `omitAbsent`
and `includeProvenance` are unchanged. The body and the answer change. Every
tag below is the case name of its alternative, which is how this project spells
a tagged value (ADR-037 §1).

```json
{ "valuations": [
    { "selection": { "kind": "Inherent" }, "nodeIds": ["<Enterprise>"] },
    { "selection": { "kind": "Residual" }, "nodeIds": ["<Cyber>"] },
    { "selection": { "kind": "Selected",
                     "mitigations": { "<MFA>": { "scope": "NodesOnly", "nodes": ["<Ransomware>"] },
                                      "<Insurance>": { "scope": "FullScope" } } },
      "nodeIds": ["<Cyber>", "<Ransomware>"] } ] }
```

```json
{ "valuations": [
    { "selection": { "kind": "Inherent" },
      "curves": { "<Enterprise>": { "curve": {}, "withMitigations": [] } } },
    { "selection": { "kind": "Residual" },
      "curves": { "<Cyber>": { "curve": {}, "withMitigations": ["<Insurance>", "<MFA>"] } } },
    { "selection": { "kind": "Selected", "mitigations": {} },
      "curves": { "<Cyber>": { "curve": {}, "withMitigations": ["<Insurance>", "<MFA>"] },
                  "<Ransomware>": { "curve": {}, "withMitigations": ["<MFA>"] } } } ] }
```

The exceedance read keeps its route and its answer. Its body is
`{ "selection": <selection> }` with the same three alternatives.

#### Rules

1. `selection` is required in a valuation and in the exceedance body. An absent
   `selection` is refused. One meaning has one spelling.
2. `"scope": "NodesOnly"` with an empty `nodes` list is refused.
3. `"kind": "Selected"` with an empty `mitigations` map is the inherent
   selection, as two empty lists were. The encoder never writes it.
4. A request states between 1 and 16 valuations. The bound is
   `LECCurvesMultiRequest.MaxValuations`, chosen by this project: each
   valuation whose selection is not inherent costs one mitigated fold of the
   tree.
5. Two valuations with equal selections are refused. Their nodes belong in one
   valuation.
6. Each valuation names at most `RiskTree.MaxNodes` nodes, and an empty
   `nodeIds` is refused, as today.
7. A selection names at most `RiskTree.MaxMitigations` mitigations, and one
   node list holds at most `RiskTree.MaxNodes` nodes, as today.
8. Every curve of one answer, across all its valuations, is sampled at the same
   loss values.
9. Every requested node present at the revision gets one curve per valuation
   that names it. A node the valuation's selection does not reach gets its
   curve with an empty `withMitigations`.
10. `withMitigations` is decided by scope, as today: it names every mitigation
    the valuation's selection applies whose resolved scope covers the node or a
    node below it.
11. With `omitAbsent`, a node absent at the revision is left out of each
    valuation's `curves`. Without it, every absent node of every valuation is
    refused together.
12. Every selected mitigation the tree does not hold, across all valuations, is
    refused together, in identifier order.

Rules 1 to 7 are checked by the decoders, so a refused body answers as any
unreadable body does (ADR-001 §2, ADR-040 §5). Rules 11 and 12 and the empty
`nodeIds` refusal are checked by the service, where the tree is loaded, as
today.

#### Exact signatures

**`ScopeRestriction`**, in
`modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationSelection.scala`.
Family search: the type exists in that file with the members `FullScope` and
`NodesOnly`. No type is added. `NodesOnly` gains its own smart constructor,
after the pattern of `RiskTree.requireNodeCount(count, location)`, which takes
the location of its refusal from the caller.

```scala
sealed trait ScopeRestriction

object ScopeRestriction:
  /** The key naming which case a restriction states. */
  final val ScopeField = "scope"

  case object FullScope extends ScopeRestriction

  /** Built only by `nodesOnly`, which bounds the set and never builds an empty one. */
  final case class NodesOnly private[ScopeRestriction] (ids: Set[NodeId]) extends ScopeRestriction

  /** The restriction to `ids`. An empty set is refused with
    * `FieldProblem.NodeIdsEmpty`, and a set above `RiskTree.MaxNodes` with
    * `FieldProblem.TooMany`, both at `location`. */
  def nodesOnly(ids: Set[NodeId], location: ErrorLocation): Validation[ValidationError, ScopeRestriction]

  given Equal[ScopeRestriction] = Equal.default
```

**`MitigationSelection`**, same file. `Inherent`, `Residual`, `Selected`,
`Enumerated` and `single` keep their definitions. `NodeMitigationEntry`, the
two-list `create`, and the codec and schema of `Enumerated` are removed.

```scala
object MitigationSelection:
  /** The key naming which case a selection states. */
  final val TagField = "kind"

  /** The selection `entries` states. An empty map is `Inherent`. More entries
    * than `RiskTree.MaxMitigations` are refused at
    * `ErrorLocation.requestField(FieldName.Mitigations)`. */
  def create(entries: Map[MitigationId, ScopeRestriction]): Validation[ValidationError, Enumerated]

  @jsonDiscriminator(ScopeRestriction.ScopeField)
  private enum RawScope:
    case FullScope
    case NodesOnly(nodes: List[NodeId])

  @jsonDiscriminator(TagField)
  private enum Raw:
    case Inherent
    case Residual
    case Selected(mitigations: Map[MitigationId, RawScope])

  private def fromRaw(raw: Raw): Validation[ValidationError, MitigationSelection]
  private def toRaw(selection: MitigationSelection): Raw

  given codec: JsonCodec[MitigationSelection]
  given schema: Schema[MitigationSelection]
```

`fromRaw` builds each `NodesOnly` through `ScopeRestriction.nodesOnly`, located
at `ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Nodes)`, then
calls `create`, and accumulates every refusal. `toRaw` is total over the three
cases and writes mitigations and nodes in identifier order. The codec decodes
through `fromRaw`, and the schema is derived from `Raw` and mapped through it
(ADR-038 §1). The comment on `Enumerated` becomes: a selection stated by naming
its mitigations, which is what the interface edits.

A compile-level premise, settled by the first compile of `commonJVM`: zio-json
and Tapir derive a codec and a schema for a private `enum` nested in an object.
The existing private `Raw` case classes in the same position derive.

**`IronTapirCodecs`**, in
`modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala`.
Family search: `given nodeIdKeyedMapSchema[V: Schema]: Schema[Map[NodeId, V]]`.
The new member follows it.

```scala
given mitigationIdKeyedMapSchema[V: Schema]: Schema[Map[MitigationId, V]] =
  Schema.schemaForMap[MitigationId, V](_.value)
```

**Three enum cases**, in
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`.
Family search: `enum BoundedCollection` with `case Nodes, Mitigations, Children,
PipelineSteps`; `enum FieldName` with `case Mitigations`; `enum FieldProblem`
with `case DuplicateMitigationId(id: MitigationId, occurrences: NonNegativeInt)`.
No existing case denotes a valuation or a repeated selection.

```scala
enum BoundedCollection:
  case Nodes, Mitigations, Children, PipelineSteps, Valuations

enum FieldName:
  // Analysis reads
  case Valuations

enum FieldProblem:
  case DuplicateSelection(occurrences: NonNegativeInt)
```

`FieldProblem.code` maps `DuplicateSelection` to
`ValidationErrorCode.DUPLICATE_VALUE`, as it maps `DuplicateMitigationId`.
`ValidationMessages` gains `"valuations"` for the collection and, for the
problem, the sentence
`s"duplicate selection — stated by $occurrences valuations; state it once and list every node under it."`,
in the form of the `DuplicateMitigationId` sentence.

**The requests**, in
`modules/common/src/main/scala/com/risquanter/register/http/requests/AnalysisRequests.scala`.
Family search: wire request types with a private constructor, a `create`, a
private `Raw` and a codec that runs `create`; the existing member is
`LECCurvesMultiRequest`. `CurveValuationRequest` joins it.

```scala
/** One valuation of a curve request: a selection, and the nodes whose curves
  * are wanted under it. */
final case class CurveValuationRequest private (selection: MitigationSelection, nodeIds: List[NodeId])

object CurveValuationRequest:
  /** A valuation names at most as many nodes as a tree can hold. */
  def create(selection: MitigationSelection, nodeIds: List[NodeId]): Validation[ValidationError, CurveValuationRequest]

  private case class Raw(selection: MitigationSelection, nodeIds: List[NodeId])
  given codec: JsonCodec[CurveValuationRequest]
  given schema: Schema[CurveValuationRequest]

/** Body of the multi-node curve read: the valuations to answer. */
final case class LECCurvesMultiRequest private (valuations: List[CurveValuationRequest])

object LECCurvesMultiRequest:
  /** The most valuations one request states. Chosen by this project: each
    * valuation whose selection is not inherent costs one mitigated fold. */
  val MaxValuations: NonNegativeInt = 16

  /** At least one valuation, at most `MaxValuations`, and no two with equal
    * selections. Every breach is reported together. */
  def create(valuations: List[CurveValuationRequest]): Validation[ValidationError, LECCurvesMultiRequest]

  private case class Raw(valuations: List[CurveValuationRequest])
  given codec: JsonCodec[LECCurvesMultiRequest]
  given schema: Schema[LECCurvesMultiRequest]

/** Body of the exceedance-probability read: the selection the figure is
  * computed under. */
final case class ProbOfExceedanceRequest(selection: MitigationSelection)

object ProbOfExceedanceRequest:
  given codec: JsonCodec[ProbOfExceedanceRequest] = DeriveJsonCodec.gen[ProbOfExceedanceRequest]
  given schema: Schema[ProbOfExceedanceRequest]   = Schema.derived[ProbOfExceedanceRequest]
```

`LECCurvesMultiRequest.create` refuses an empty list with
`FieldProblem.Required`, a list above the bound with `FieldProblem.TooMany`
naming `BoundedCollection.Valuations`, and a repeated selection with
`FieldProblem.DuplicateSelection`, all at
`ErrorLocation.requestField(FieldName.Valuations)`.

**The answer**, in
`modules/common/src/main/scala/com/risquanter/register/http/responses/LECCurvesResponse.scala`.
Family search: an entry type inside a list answer carries the `Response`
suffix, as `TreeSummaryResponse` does inside `TreeListResponse`.
`LECNodeSeries` and `ExceedanceSeries` are unchanged.

```scala
/** One valuation's answer: its selection, and the curve of each requested node
  * present at the revision read. */
final case class CurveValuationResponse(selection: MitigationSelection, curves: Map[NodeId, LECNodeSeries])

/** The valuations lec-multi answers, in request order. Every curve of every
  * valuation is sampled at the same loss ticks, so the set draws on one axis. */
final case class LECCurvesResponse(valuations: List[CurveValuationResponse])
```

Both derive their codec and schema.

**The service**, in
`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
and `RiskTreeServiceLive.scala`. `probOfExceedance` keeps its signature.

```scala
def getLECCurvesMulti(
  wsId: WorkspaceId,
  treeId: TreeId,
  valuations: List[CurveValuationRequest],
  seedEntityId: SeedEntityId.SeedEntityId,
  includeProvenance: Boolean,
  branch: BranchRef,
  at: Option[CommitHash],
  omitAbsent: Boolean = false
)(using Checked[Authority]): IO[Failure, List[CurveValuationResponse]]
```

Inside `RiskTreeServiceLive`:

```scala
/** Key of one curve in the shared tick-domain computation. A request holds no
  * two equal selections, so the selection identifies its valuation. */
private case class SeriesKey(selection: MitigationSelection, nodeId: NodeId)

/** Refuse every selected mitigation this tree version does not hold, across
  * all `selections`, together and in identifier order. */
private def requireKnownMitigations(tree: RiskTree, selections: List[MitigationSelection]): IO[Failure, Unit]
```

The method loads the tree once and applies rules 11 and 12 and the empty
`nodeIds` refusal. It resolves the mitigation scopes once when at least one
valuation is not inherent. It computes each valuation's results for its present
nodes, evaluates every curve in one `LECGenerator.generateCurvePointsMulti`
call, and answers in request order.

**The controller and the endpoint.** `WorkspaceAnalysisController.getLECCurvesMulti`
passes `body.valuations` and answers `LECCurvesResponse(valuations)`. The two
endpoint descriptions in `WorkspaceAnalysisEndpoints` are reworded: "under one
mitigation selection" becomes "under the selection the body states" for the
exceedance read and "for the valuations the body states" for the curve read. No
endpoint input or output type name changes.

#### The app in Part A — the card behaves as before

The card keeps its one selection and its Inherent / Mitigated / Both control
until Part B. Two functions carry the new request and answer.

`LECChartState.requestFor` keeps its signature. Its request states the inherent
valuation for the given nodes, and a second valuation under the card's
selection for the same nodes when that selection is not inherent.

```scala
/** Each node's curves from the answer's valuations, in the form the card
  * draws: the inherent valuation's curve first, then the other valuation's
  * curve where its `withMitigations` names a mitigation. */
def seriesByNode(response: LECCurvesResponse): Map[NodeId, List[LECNodeSeries]]
```

`loadCurves` maps the answer through `seriesByNode`, so `curveCache` keeps its
type and every reader of it is untouched.

`SelectionEdit` keeps every public signature. Its private `statement` helper
goes, and its private `build` calls `MitigationSelection.create(entries)`. A
node set is built by `ScopeRestriction.nodesOnly`, located at
`ErrorLocation.Field(ErrorSubject.Mitigation(id), FieldName.Nodes)`.

#### ADR alignment

Every record below was read in full on 2026-11-22.

- **ADR-040** (analysis read contract). Amended by this part; the amended text
  is in the next section.
- **ADR-038** (response shapes). Compliant. The selection declares its wire
  shape privately and decodes through its constructors (§1). The answer stays
  an object, and its map is keyed by `NodeId` (§3). The selection is a domain
  value with invariants and not an operation outcome, so §1 governs it and not
  §2; its private wire shape is a derived tagged enum.
- **ADR-037** (constants and closed sets). Compliant. Both tag keys are named
  constants read by the annotation. Every tag is its case name (§1), so no
  spelling is declared. `MaxValuations` is owned by the request type and states
  its origin (§3).
- **ADR-017** (tree API). Compliant. The bound is a named value in the
  companion of its owner and is refused through `ValidationUtil.requireAtMost`
  (§6). The two rows of its HTTP surface table are reworded.
- **ADR-001** (validation). Compliant. Each rule is checked at the first point
  that holds what it reads: the bounds and the shape in the decoder, the
  tree-dependent rules at the service's entry step (§2). Independent refusals
  accumulate.
- **ADR-039** (ingress). Compliant. The browser decodes the answer through the
  same constructors, so a decoded selection holds its bounds there too (§1).
- **ADR-010** (errors). Compliant. Every refusal is a `ValidationError` with a
  location and a problem of a closed set. Its §4 example of
  `getLECCurvesMulti` is brought to the new signature.
- **ADR-035** (error leakage). Compliant. The new problem carries a count and
  nothing of the caller's text.
- **ADR-036** (confidential identifiers). Compliant. The new shapes carry
  `NodeId` and `MitigationId`, which §4 states are client-facing, and no
  workspace identifier.
- **ADR-018** (nominal wrappers), **ADR-029** (injection). Compliant. No
  identifier type and no string-backed field is added; map keys are the
  refined `MitigationId`.
- **ADR-030** (authorization boundary). Compliant. The service method keeps
  `using Checked[Authority]`, and the controller's check is unchanged.
- **ADR-033** (exception boundaries). Compliant. No catch is added.
- **ADR-019** (frontend architecture). Compliant. Part A adds one pure function
  to a state object's companion and changes no component, no `Var` and no
  signal.
- **ADR-011** (imports). Compliant. Every new import is top-level and grouped
  by origin.

#### ADR-040 as amended

The implementing pass rewrites these parts of
`docs/dev/decision-records/ADR-040-analysis-read-contract.md`. §1 and §3 stand.

- **Context.** "A selection is two lists with bounds" becomes: a selection is a
  tagged value with bounds, and a curve request states several. The last
  bullet becomes: an answer holding several valuations of one node must say,
  per curve, which mitigations shaped it.
- **§2, retitled "Both Endpoints Are POST With a JSON Body Stating Selections".**
  A selection is one of `Inherent`, `Residual` and `Selected`, tagged by
  `kind`. `Selected` maps each mitigation to `FullScope` or `NodesOnly` with
  its nodes, tagged by `scope`. A selection is required. The curve body is a
  list of valuations, each a selection and its nodes, and no two valuations
  hold equal selections. The decoder runs `MitigationSelection.create` and
  `ScopeRestriction.nodesOnly`.
- **§4, retitled "One Curve Per Valuation and Node, One Meaning of
  `withMitigations`".** The curve endpoint answers one entry per valuation, in
  request order, each repeating its selection and mapping each requested node
  to one curve. Every curve of the answer shares one set of loss ticks. The
  exceedance endpoint answers one figure. `withMitigations` keeps its meaning.
- **§5.** Gains the valuation bound, `LECCurvesMultiRequest.MaxValuations`.
- **Code smells.** "A Separate Field for the Mitigation-Free Reading" is
  replaced by a smell for adding a curve the request did not state.
- **Implementation table.** Names `CurveValuationRequest`,
  `CurveValuationResponse` and `ScopeRestriction.nodesOnly`.

#### Terminology impact

One entry of `docs/user/TERMINOLOGY.md` is made stale by MTR-D-6, and it is
presented for approval before the glossary is edited.

- Term: 4.4, "Inherent risk and residual risk".
- Current: "It is what every read returns unless a mitigated valuation is asked
  for explicitly."
- Made stale by: rule 1 of this part. A curve read and an exceedance read state
  their selection in every request, so neither returns anything by default.
- Proposed: "A curve read or an exceedance read returns it when the request
  states the inherent selection."

#### Documents this part makes stale

Each is brought to the new request and answer in the implementing pass.

- `docs/dev/decision-records/ADR-040-analysis-read-contract.md`, as above.
- `docs/dev/decision-records/ADR-017-tree-api-design.md`, the two analysis rows
  of the HTTP surface table.
- `docs/dev/decision-records/ADR-010.md`, the `getLECCurvesMulti` line of §4.
- `.claude/skills/adr-constraints/SKILL.md`, the Layer 2 row of ADR-040.
- `docs/dev/ARCHITECTURE.md`, the paragraph on `lec-multi`.
- `docs/user/API-TUTORIAL.md`, the section on selections.
- `docs/user/MITIGATION-WALKTHROUGH.md`, the `figures` and `chance` helpers and
  steps 8 and 11.
- `examples/demo-mitigations-curl.sh` and `examples/demo-mitigations-httpie.sh`.
- `tests/perf/measure-simulation.sh`, the body of its curve request.
- `docs/dev/plans/PLAN-RISKTRANSFORM.md`, Decision 11, "one selection per
  request", which MTR-D-6 reverses. A marker goes at its ruling.

#### Verification plan

Tests changed or added:

- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationSelectionSpec.scala`:
  `create` over an empty map, one entry, and one entry above the bound;
  `nodesOnly` over an empty set and a set above the bound; the exact JSON of
  each of the three alternatives and of both restrictions; a body with an
  absent `kind` is refused; the law `decode(encode(s)) == s` for the three
  alternatives.
- `modules/common/src/test/scala/com/risquanter/register/http/requests/AnalysisRequestsSpec.scala`:
  an empty valuation list, 17 valuations, two equal selections and a node list
  above the bound are each refused; a valuation with no `selection` is refused;
  the exceedance body requires `selection` and accepts `Residual`.
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
  and `.../http/controllers/WorkspaceAnalysisControllerSpec.scala`: an answer
  holds exactly the requested curves; an unreached node has its curve and an
  empty `withMitigations`; `Residual` equals the selection of every mitigation
  at `FullScope`; curves of different valuations share their loss ticks; absent
  mitigations of two valuations are refused together; `omitAbsent` drops an
  absent node from each valuation; the answer keeps request order.
- `.../mitigation/MitigationApplicationSpec.scala` and
  `.../services/CascadeTestStubs.scala`: built through the new constructors,
  with every assertion kept.
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala`,
  `.../http/SeedReproducibilityItSpec.scala` and
  `.../services/MitigationPersistenceItSpec.scala`: sent and read in the new
  shapes, with every assertion kept.
- `modules/app/src/test/scala/app/state/LECChartStateSpec.scala`: `requestFor`
  states one valuation under the inherent selection and two under any other;
  `seriesByNode` puts the inherent curve first and leaves out the second curve
  of an unreached node.
- `modules/app/src/test/scala/app/state/SelectionEditSpec.scala`: built through
  the new constructors, with every assertion kept.

Commands, each green:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
sbt serverIt/test
```

The register-dev skill's leaked-state cleanup runs before `serverIt/test`.
Part A ends a phase, so BATS suites C and A run then, and are the user's to
run:

```bash
tests/bats/run-suites.sh C A
```

A check in the browser, which the user carries out: on the walkthrough's data
the card draws the same curves under Inherent, Mitigated and Both as before
Part A.

Version: MINOR on landing, 0.27.0, because the external API changes while the
version is below 1.0.0.

---

### Part B — the grid

A card holds one tree at one scenario and one point in history, as today. Its
body is one table.

```
Regional Bank Risk Register
                               Inherent  Residual    A     B    [+]
v Mitigations
    MFA                                    [x]      [x]   [ ]
    Cyber insurance                        [x]      [ ]   [x]
v Tree structure
    Enterprise                   [ ]       [ ]      [ ]   [ ]
      Cyber                      [x]       [x]      [x]   [x]
        - Cyber insurance                  [x]      [ ]   [x]
        Ransomware               [ ]       [ ]      [ ]   [ ]
          - MFA                            [x]      [x]   [ ]
```

- **Columns.** Inherent applies nothing and has no mitigation boxes. Residual
  applies everything, and its boxes are ticked and fixed. A user adds further
  columns and edits their boxes.
- **A node row's cell** draws that node's curve under the column's selection.
- **A mitigation line in the Mitigations section** switches the mitigation on
  across its whole scope in that column. A half-filled box shows it is on at
  some nodes only.
- **A mitigation row under a node** switches the mitigation on at that node in
  that column. It changes the selection and draws nothing.
- **The request.** Each column with at least one ticked node cell is one
  valuation.
- **The chart.** Colour stays the node's. The marker shape is the column's and
  is shown in the column header. Clicking a column title hides or shows that
  column's curves.
- **Removed.** The Inherent / Mitigated / Both control, the Ctrl+click and
  Ctrl+Shift+click gestures, and the coloured border on an applied mitigation
  row.
- **Unchanged.** Tree, scenario and history selection; compare cards and the
  mirror, which carry columns and ticks; the collapsible sections; the limit of
  13, which counts nodes with at least one tick; the query pane, which ticks
  the Inherent cells of the leaves it matches.
- **Built from** an HTML table, sticky header and name column, native
  checkboxes tinted by `accent-color`, the checkbox `indeterminate` state, and
  row and column hover. No JavaScript dependency is added.

#### The two items left open, as settled here

Both were derived on 2026-11-23 from the rulings above and from the code. Each
is reversible, and approving this section confirms them.

- **MTR-D-9 — a card holds at most eight columns.** Inherent, Residual and six
  the user adds. A column is told apart on the chart by its marker shape. Vega,
  the chart library, draws eight filled symbol shapes: `circle`, `square`,
  `cross`, `diamond`, `triangle-up`, `triangle-down`, `triangle-right` and
  `triangle-left`. All eight names are in the installed
  `modules/app/node_modules/vega-scenegraph`. Inherent keeps `circle` and
  Residual keeps `triangle-up`, the two markers the chart draws today. Eight is
  below `LECCurvesMultiRequest.MaxValuations`, which is 16.
- **MTR-D-10 — a compare card's identity is its place.** A place is a branch,
  an effective tree and a point in history. Today a card's selection is part of
  its identity, so two cards at one place under two selections both draw. A
  grid holds several selections in one card, so the selection leaves
  `SlotCoordinate`. Two cards at one place then show the same tree version, and
  the later one stays out of the chart until its place differs, as a duplicate
  does today.

Two consequences of the ruled design are stated here because each removes
something a user can see.

- **The two sections open and close without sliding.** The grid is one table,
  so that the Mitigations rows and the Tree structure rows share their columns.
  A row group of a table cannot animate its height. A click on a section title
  shows or hides the section's rows at once.
- **A mirrored card shows no notice on a click.** Its boxes are disabled, and
  a disabled box takes no click. Each carries the mirror sentence as its
  tooltip, and the header keeps the `mirroring baseline` hint.

#### What a card's state is

A card's grid is one value, `CurveGrid`, held by the card's `LECChartState`. It
replaces three things: the set of charted nodes (`userSelectedNodeIds`), the
card's one selection (`CompareState.baselineSelection` and
`SlotCoordinate.selection`), and the display mode (`CurveDisplay`).

Worked example, on the walkthrough's tree. The user adds column A, ticks MFA
on its Mitigations line, and ticks `Cyber` under Inherent and under A.

| Step | Grid after it | Request | Chart |
|---|---|---|---|
| add column A | columns Inherent, Residual, A; A applies nothing | none: no node is ticked | empty |
| tick MFA on A's Mitigations line | A applies MFA at `FullScope` | none | empty |
| tick `Cyber` under Inherent | Inherent holds `Cyber` | one valuation: Inherent, `[Cyber]` | `Cyber`, circles |
| tick `Cyber` under A | A holds `Cyber` | two valuations: Inherent and A's selection, each `[Cyber]` | `Cyber` twice, one colour, circles and squares |
| click A's title | A is hidden | unchanged | `Cyber`, circles |

Rules the pure functions below implement:

1. The query pane's matches count as ticks in the Inherent column. Such a cell
   is shown ticked and cannot be unticked while the query matches it. They do
   not count toward the limit of 13, as today.
2. A request holds one valuation per distinct selection among the columns that
   hold a tick. Two columns whose selections are equal share one valuation,
   which names the union of their nodes, because the server refuses a selection
   stated twice (Part A, rule 5). A column that applies nothing shares the
   Inherent valuation.
3. A user column's selection is sent without the mitigations the loaded tree
   does not hold, as today. While the tree is not loaded and a ticked column
   names a mitigation, nothing is fetched.
4. A hidden column is still requested. Hiding changes what is drawn and
   nothing else, as the card's eye does.
5. A tree switch empties the grid. A branch switch, a refresh and a rewind keep
   the columns and their selections. The first two clear the ticks, and a
   rewind keeps them. This is what each does to the card's selection and its
   charted nodes today.
6. A new user column takes the first unused letter, and applies nothing.
   Removing a column removes its selection, its ticks and its hidden mark.

#### Exact signatures

**`UserColumn` and `GridColumn`** replace `CurveVariant`, in
`modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`. Family search:
`enum CurveVariant(val markerShape: String)` with `case Inherent extends
CurveVariant("circle")` is the existing type for "which valuation a drawn curve
is". The new pair denotes the same thing over more cases, so it replaces that
type and no second type is added. The marker shape is a contract with Vega, so
it is declared. The label of a user column is its case name.

```scala
/** A column a user adds to a card's grid. `markerShape` is the Vega symbol
  * name, a contract with Vega. `glyph` is that shape as text, for the column
  * header. */
enum UserColumn(val markerShape: String, val glyph: String):
  case A extends UserColumn("square", "■")
  case B extends UserColumn("cross", "✚")
  case C extends UserColumn("triangle-down", "▼")
  case D extends UserColumn("triangle-right", "▶")
  case E extends UserColumn("triangle-left", "◀")
  case F extends UserColumn("diamond", "◆")

/** A column of a card's grid, and so the selection a drawn curve is under. */
enum GridColumn:
  case Inherent
  case Residual
  case User(column: UserColumn)

object GridColumn:
  /** Every column a grid can hold, in display order. */
  val all: List[GridColumn]
  def label(column: GridColumn): String        // "Inherent", "Residual", "A" … "F"
  def markerShape(column: GridColumn): String  // "circle", "triangle-up", then the user column's
  def glyph(column: GridColumn): String        // "●", "▲", then the user column's
```

`ChartSeries.variant: CurveVariant` becomes `column: GridColumn`.
`LECSpecBuilder.build` takes `curves: Vector[(LECNodeCurve, GridColumn, HexColor)]`.
`seriesIdOf(node: NodeId, column: GridColumn): String` gives the bare node id
for Inherent and the node id followed by `~` and the column's label otherwise.
`labelOf(curve: LECNodeCurve, column: GridColumn): String` gives the node's
name for Inherent and the name followed by the label in brackets otherwise. The
shape scale lists the columns of the series drawn, in `GridColumn.all` order,
under the legend title `Selection`. `ColorAssigner.pairWithColors` and
`CompareColorAssigner.OverlaySide.curves` carry `GridColumn` where they carry
`CurveVariant`.

**`CurveGrid`**, new, in `modules/app/src/main/scala/app/state/CurveGrid.scala`.
Family search: the app's pure state values with their edits beside them, as
`object SelectionEdit` in `modules/app/src/main/scala/app/state/SelectionEdit.scala`
holds the edits of a selection. No existing type holds columns and ticks.

```scala
/** One card's grid: the columns the user added with the selection each
  * states, the nodes ticked in each column, and the columns hidden from the
  * chart. Built from `empty` by the edits below, so a tick or a hidden mark
  * never names a user column the grid does not hold. */
final case class CurveGrid private (
  userColumns: Map[UserColumn, MitigationSelection.Enumerated],
  ticks:       Map[GridColumn, Set[NodeId]],
  hidden:      Set[GridColumn]
):
  /** Inherent, Residual, then the user columns held, in letter order. */
  def columns: List[GridColumn]

  /** The selection a user column states. A column not held applies nothing. */
  def userSelection(column: UserColumn): MitigationSelection.Enumerated

  def isTicked(column: GridColumn, node: NodeId): Boolean
  def isHidden(column: GridColumn): Boolean

  /** Every node ticked in at least one column. The limit counts these. */
  def tickedNodes: Set[NodeId]

  /** The grid with the first unused letter added, applying nothing. `None`
    * when all six are held. */
  def addColumn: Option[CurveGrid]

  def removeColumn(column: UserColumn): CurveGrid

  /** The grid with `edit` applied to a held column's selection. A column not
    * held is left as it is. */
  def editSelection(
    column: UserColumn,
    edit:   MitigationSelection.Enumerated => Validation[ValidationError, MitigationSelection.Enumerated]
  ): Validation[ValidationError, CurveGrid]

  /** The grid with `node` ticked in `column`. `None` when `node` holds no
    * tick yet and `cap` nodes already do. A column not held takes no tick. */
  def tick(column: GridColumn, node: NodeId, cap: Int): Option[CurveGrid]

  def untick(column: GridColumn, node: NodeId): CurveGrid
  def toggleHidden(column: GridColumn): CurveGrid

  /** The columns and their selections, with no node ticked. */
  def withoutTicks: CurveGrid

  /** The ticks kept to `nodes`. */
  def restrictedTo(nodes: Set[NodeId]): CurveGrid

  /** `nodes` added to the Inherent column's ticks, uncapped. */
  def withInherentTicks(nodes: Set[NodeId]): CurveGrid

  /** What the card asks for: each column holding a tick, with its selection
    * and its nodes. `query` counts as ticks in the Inherent column. */
  def requested(query: Set[NodeId]): Map[GridColumn, (MitigationSelection, Set[NodeId])]

object CurveGrid:
  /** Inherent and Residual, nothing ticked, nothing hidden. */
  val empty: CurveGrid
```

**`LECChartState`**, in `modules/app/src/main/scala/app/state/LECChartState.scala`.
The constructor loses `selectionAccessor` and `display`. `userSelectedNodeIds`,
`userSelectionToggle`, `setUserSelection`, `loadCurves(nodeIds)`, `readings`,
`seriesByNode` and the three-argument `requestFor` are removed.

```scala
final class LECChartState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree: StrictSignal[LoadState[RiskTree]],
  userIdAccessor: () => Option[UserId.Authenticated],
  branchAccessor: () => BranchChoice,
  atAccessor: () => Option[CommitHash],
  userPalette: Signal[Vector[HexColor]],
  origin: Signal[Option[String]]
)

/** The card's grid. Changed only through the edits below. */
val grid: StrictSignal[CurveGrid]

/** Each requested selection's curves, by node, from the `lec-multi` answer. */
val curveCache: Var[LoadState[Map[MitigationSelection, Map[NodeId, LECNodeSeries]]]]

/** What the next curve request states, emitted only when it changes. */
val requested: Signal[Map[GridColumn, (MitigationSelection, Set[NodeId])]]

/** The curves the card draws, per node, each with its column. */
val drawnCurves: Signal[LoadState[Map[NodeId, List[(GridColumn, LECNodeCurve)]]]]

/** Tick or untick one cell. A tick the limit refuses leaves the grid as it
  * is and emits on `selectionCapReached`. */
def setTick(column: GridColumn, node: NodeId, on: Boolean): Unit
def addColumn(): Unit
def removeColumn(column: UserColumn): Unit
def editSelection(
  column: UserColumn,
  edit:   MitigationSelection.Enumerated => Validation[ValidationError, MitigationSelection.Enumerated]
): Unit
def toggleHidden(column: GridColumn): Unit

/** Replace the grid whole, for the mirror and a copy's seed. Skips an equal one. */
def setGrid(grid: CurveGrid): Unit

/** Empty the grid, for a tree switch. */
def clearGrid(): Unit
```

`satisfyingNodeIds`, `visibleCurves`, `droppedSelections`, `nodeColorMap`,
`explicitColors`, `specSignal`, `selectionCapReached`, the colour actions,
`clearCurves` and `reloadCurrentCurves` keep their signatures. `visibleCurves`
is `grid.tickedNodes` with the query's matches. `reset` clears the ticks and
keeps the columns. `reloadCurrentCurves` builds its request from `requested`,
and clears the cache when nothing is requested.

```scala
object LECChartState:
  /** The most nodes a card may hold a tick for, chosen by this project. */
  val MaxTickedNodes: Int = 13

  /** `selection` without the mitigations not in `held`. `Inherent` and
    * `Residual` name none and pass unchanged. */
  def sentSelection(selection: MitigationSelection, held: Set[MitigationId]): Validation[ValidationError, MitigationSelection]

  /** The curve request for `requested`: one valuation per distinct sent
    * selection, naming the union of its columns' nodes in identifier order.
    * `None` when a requested selection names a mitigation and `held` is not
    * known. `Left` when a constructor refuses it. `requested` is not empty. */
  def requestFor(
    requested: Map[GridColumn, (MitigationSelection, Set[NodeId])],
    held:      Option[Set[MitigationId]]
  ): Option[Either[LocalProblem, LECCurvesMultiRequest]]

  /** The answer's valuations, keyed by the selection each repeats. */
  def curvesBySelection(response: LECCurvesResponse): Map[MitigationSelection, Map[NodeId, LECNodeSeries]]

  /** The curves to draw: for each column of `requested` not in `hidden`, the
    * curve of each of its nodes under its sent selection, where the answer
    * holds one. */
  def drawn(
    requested: Map[GridColumn, (MitigationSelection, Set[NodeId])],
    hidden:    Set[GridColumn],
    held:      Set[MitigationId],
    curves:    Map[MitigationSelection, Map[NodeId, LECNodeSeries]]
  ): Map[NodeId, List[(GridColumn, LECNodeCurve)]]

  def deriveDropped(
    selected: Set[NodeId],
    cache:    LoadState[Map[MitigationSelection, Map[NodeId, LECNodeSeries]]]
  ): Set[NodeId]
```

`MaxUserSelections` is renamed `MaxTickedNodes` and keeps its value.

**`TreeViewState`**, in `modules/app/src/main/scala/app/state/TreeViewState.scala`.
The constructor loses `selectionSignal` and `display`. `absentSelected` keeps
its type and names the mitigations of every user column the loaded tree does
not hold. The two subscriptions that read the selection read the grid instead:
a loaded structure and a rewind reload the curves when a ticked column names a
mitigation. `userSelectedNodeIds` and `userSelectionToggle` are removed, and
`curveCache` takes the new cache type. `Main.scala` drops the two arguments at
its three call sites.

**`CompareState.scala`**, in `modules/app/src/main/scala/app/state/CompareState.scala`.
`CurveDisplay`, `CompareState.baselineSelection`, `CompareState.baselineDisplay`,
`CompareSlotState.display`, `selectionSignal`, `setSelection` and
`SlotCoordinate.samePairAs` are removed. Callers of `samePairAs` call
`samePlaceAs`.

```scala
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId], at: Option[CommitHash]):
  def effectiveTree(activeTree: Option[TreeId]): Option[TreeId]
  def samePlaceAs(other: SlotCoordinate, activeTree: Option[TreeId]): Boolean
  def collidesWith(activeBranch: BranchChoice, activeTree: Option[TreeId], activeAt: Option[CommitHash]): Boolean

object SlotCoordinate:
  def activeTab(branch: BranchChoice, at: Option[CommitHash]): SlotCoordinate

/** A side's complete state as copy-for-compare duplicates it. */
final case class SideSnapshot(coordinate: SlotCoordinate, grid: CurveGrid)

// in CompareSlotState
/** The grid a copy carries until its tree loads. */
val pendingSeed: Var[Option[CurveGrid]]
```

`copyToNextFree` and `removeRow` keep their signatures and stop writing a
display mode.

**`AnalyzeView`**, in `modules/app/src/main/scala/app/views/AnalyzeView.scala`.
`renderDisplayToggle`, `displayLabel` and `readingsOf` are removed, with the
subscription that reset a card's selection on a tree switch, which becomes a
call of `clearGrid` on the baseline and on each slot following the active tree.
Each chart reads its side's `drawnCurves`. `SlotOverlayInput.curves` takes that
type and loses `display`. Each card's curve fetch is one subscription on
`requested`, debounced 100 ms as today, calling `reloadCurrentCurves`.

```scala
private[views] def engagedSlots(
  targets:      Vector[CompareTarget],
  activeBranch: BranchChoice,
  activeTreeId: Option[TreeId],
  activeAt:     Option[CommitHash]
): Vector[(Int, BranchChoice)]

/** The grid a copied slot takes: the source's, with its ticks kept to the
  * nodes the slot's tree holds. */
private[views] def copySeed(pending: CurveGrid, compareTreeNodeIds: Set[NodeId]): CurveGrid
```

`computeSeed`, `excludedBranchValues` and `excludedTreeOverrideValues` keep
their signatures. `computeSeed`'s nodes are ticked in the Inherent column,
through `setTick`. The mirror sets a slot's grid to the baseline's grid, with
the query's matches added to its Inherent column, kept to the nodes of the
slot's tree. The copy button's snapshot is built the same way.

**`TreeDetailView`**, in `modules/app/src/main/scala/app/views/TreeDetailView.scala`.
The card's body becomes one table. `selection`, `onSelectionEdit`, the
`SelectionUpdate` alias, `RowGesture`, `onRowGesture`, `chartNode`,
`unchartNode`, `collapsibleSection`, `renderNode` and `renderMitigationRow` are
removed. A plain click on a node row still selects the node, and a row with a
tick keeps its border in the node's curve colour.

```scala
def apply(
  state: TreeViewState,
  queryMatchedNodes: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
  hoverBridge: ChartHoverBridge = new ChartHoverBridge(),
  changedNodeIds: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
  selectionLocked: Signal[Boolean] = Val(false),
  pinnedAt: Signal[Option[TreeHistoryEntry]] = Val(None),
  structure: Signal[LoadState[TreeStructureResponse]],
  staleAction: MitigationPanel.StaleBadgeAction
): HtmlElement

/** The loaded card: one table whose columns are the grid's. */
private def renderGrid(response: TreeStructureResponse, view: NodeView, mitigationsOpen: Var[Boolean], structureOpen: Var[Boolean]): HtmlElement

/** The header row: the tree's name, one title per column, and the add button. */
private def headerRow(treeName: String, view: NodeView): HtmlElement

/** One column's title: its marker and label, a click on which hides or shows
  * its curves, and a remove button on a user column. */
private def columnHeader(column: GridColumn, view: NodeView): HtmlElement

/** A section's title row. A click shows or hides the section's rows. */
private def sectionRow(heading: String, open: Var[Boolean], view: NodeView): HtmlElement

/** One mitigation's line in the Mitigations section. */
private def mitigationLineRow(mitigation: Mitigation, view: NodeView, shown: Signal[Boolean]): HtmlElement

/** A node's row, the rows of the mitigations scoped at it, and its children's. */
private def nodeRows(
  node: RiskNode, childrenOf: Map[Option[NodeId], Seq[RiskNode]], view: NodeView,
  depth: Int, ancestors: List[NodeId], sectionOpen: Signal[Boolean]
): List[HtmlElement]

/** One mitigation scoped at `node`, as a row under it. */
private def mitigationRow(node: NodeId, mitigation: Mitigation, view: NodeView, depth: Int, shown: Signal[Boolean]): HtmlElement

/** A node row's cell: the box that draws `node`'s curve under `column`. */
private def tickCell(column: GridColumn, node: NodeId, view: NodeView): HtmlElement

/** A Mitigations line's cell: empty under Inherent, ticked and fixed under
  * Residual, and a three-state box under a user column. */
private def wholeScopeCell(column: GridColumn, id: MitigationId, view: NodeView): HtmlElement

/** A mitigation row's cell: empty under Inherent, ticked and fixed under
  * Residual, and a box applying the mitigation at `node` under a user column. */
private def atNodeCell(column: GridColumn, id: MitigationId, node: NodeId, scope: Set[NodeId], view: NodeView): HtmlElement
```

`NodeView` loses `selection`, `onSelectionEdit` and `lockedNoticeVisible`. A
row is shown while its section is open and every ancestor of its node is
expanded. The view holds one more local `Var`, the hovered column, for the
column hover. A user column's boxes edit through `SelectionEdit.toggleWhole`
and `SelectionEdit.toggleAt`.

**`MitigationPanel`**, in `modules/app/src/main/scala/app/components/MitigationPanel.scala`.
`apply` and `line` are removed. Their checkbox becomes `wholeScopeCell`.
`StaleBadgeAction` and `staleBadge` are unchanged.

```scala
/** A mitigation's name, stage, binding warning and stale badge, with its
  * predicate as tooltip: the name cell of its line in a card's grid. */
def nameCell(response: TreeStructureResponse, mitigation: Mitigation, staleAction: StaleBadgeAction): HtmlElement
```

**`SelectionEdit`**. `applyAt` and `removeAt` lose their only caller with the
gestures, and are removed. `toggleAt`, `toggleWhole`, `toggleOf`, `appliesAt`
and `present` are unchanged.

**Sentences.** In `modules/app/src/main/scala/app/views/BlockedMessages.scala`,
`selectionCapReached(cap)` becomes
`s"At most $cap nodes can be ticked. Untick a node before ticking another."`,
and a new `def columnsFull(columns: Int): String` gives
`s"A card holds at most $columns columns."`. In
`modules/app/src/main/scala/app/views/NoticeMessages.scala`, `SelectionMirrored`
becomes `"Ticks mirror the baseline — turn Mirror off to edit them."`, and a
new `val MatchedByQuery: String = "Matched by the query."` is the tooltip of a
cell rule 1 fixes.

**Stylesheet.** `modules/app/styles/app.css` loses the `.curve-display-*`
rules, `.mitigation-panel-lines`, `.mitigation-line`, `.tree-section-body`,
`.tree-section--collapsed`, `.tree-section-inner` and `.tree-detail-nodes`. It
gains rules for `.tree-grid`: a table whose header row and name column are
sticky, whose boxes take the card's palette colour through `accent-color`, and
whose hovered row and hovered column are tinted.

#### ADR alignment

- **ADR-019** (frontend component architecture), read in full on 2026-11-23.
  Compliant. The grid is state with no phases, so it is a value changed by pure
  edits and not a state machine (§6). `LECChartState` owns it and exposes it as
  a signal, and the view changes it only through that owner's methods (§2).
  What is drawn and what is requested are derived signals, and no second copy
  of either is stored (§4). `MitigationPanel.nameCell` and the cell builders
  create no `Var` (§5).
- **ADR-001** (validation). Compliant. A column's selection is built only by
  `SelectionEdit`, through `MitigationSelection.create`, and a request only by
  the constructors of Part A.
- **ADR-037** (constants and closed sets). Compliant. The columns are two
  enums, declared beside the chart type that uses them. A marker shape is
  declared because it is Vega's name. A user column's label is its case name.
  `MaxTickedNodes` keeps its stated origin, and the column bound is the size of
  the enum and is written nowhere else.
- **ADR-040** (analysis read contract). Compliant. One valuation per distinct
  selection satisfies §2, and the card reads each curve from the valuation
  that repeats its selection (§4).
- **ADR-038** (response shapes). Compliant. The card holds the decoded answer
  re-keyed by selection and relies only on what ADR-040 §4 states.
- **ADR-011** (imports). Compliant. Every new import is top-level.
- **ADR-033** (exception boundaries). Compliant. No catch is added.

#### Open decisions

None. MTR-D-9 and MTR-D-10 are settled above and confirmed by approval of this
section.

#### Terminology impact

None. `docs/user/TERMINOLOGY.md` 4.5 defines a selection as "the set of
mitigations a valuation applies". A column states one selection, and the
glossary describes no interface.

#### Documents Part B makes stale

- `docs/user/MITIGATION-WALKTHROUGH.md`, step 11.
- `README.md`, the paragraph "Display control" and the text around it that
  describes a card's mitigation lines and the Ctrl+click gestures.
- This plan's objective and worked example, which describe the gestures the
  grid removes.
- `docs/dev/plans/PLAN-RISKTRANSFORM-INVENTORY.md`, the OD-3 entry, whose
  display ruling the grid replaces. The user edits that file.

#### Inventory amendment

The user applies it with `.claude/bin/approve-inventory`. Already on the
inventory and edited again: `TreeDetailView.scala`, `AnalyzeView.scala`,
`SelectionEdit.scala`, `LECChartState.scala`, `BranchCard.scala` (its comment
names the gesture), `app.css`, `SelectionEditSpec.scala` and
`LECChartStateSpec.scala`. Added:

- `modules/app/src/main/scala/app/state/CurveGrid.scala`, new
- `modules/app/src/test/scala/app/state/CurveGridSpec.scala`, new
- `modules/app/src/main/scala/app/state/TreeViewState.scala`
- `modules/app/src/main/scala/app/state/CompareState.scala`
- `modules/app/src/main/scala/app/Main.scala`
- `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`
- `modules/app/src/main/scala/app/chart/ColorAssigner.scala`
- `modules/app/src/main/scala/app/chart/CompareColorAssigner.scala`
- `modules/app/src/main/scala/app/components/MitigationPanel.scala`
- `modules/app/src/main/scala/app/views/BlockedMessages.scala`
- `modules/app/src/main/scala/app/views/NoticeMessages.scala`
- `modules/app/src/test/scala/app/state/CompareStateSpec.scala`
- `modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala`
- `modules/app/src/test/scala/app/views/AnalyzeViewSeedSpec.scala`

#### Verification plan

Tests added:

- `modules/app/src/test/scala/app/state/CurveGridSpec.scala`: `empty` holds
  Inherent and Residual; `addColumn` takes the first unused letter and gives
  `None` at six; `removeColumn` drops the column's selection, ticks and hidden
  mark; `tick` is refused for a fourteenth node and accepted for a second
  column of a ticked node; a tick in a column not held changes nothing;
  `withoutTicks` keeps the columns; `restrictedTo` keeps only the given nodes;
  `requested` adds the query's matches to Inherent and leaves out a column
  with no tick.

Tests changed:

- `modules/app/src/test/scala/app/state/LECChartStateSpec.scala`: `requestFor`
  states one valuation per distinct sent selection, joins two columns with
  equal selections, joins a column applying nothing with Inherent, drops a
  mitigation not held, waits while the held set is unknown and a column names
  a mitigation, and reports a refused request as a failed form; `drawn` gives
  each node its curve per shown column and leaves out a hidden column;
  `curvesBySelection` keys the answer by selection. The tests of `readings`,
  `seriesByNode` and the Ctrl+click cap go with those functions, and the cap
  is pinned in `CurveGridSpec`.
- `modules/app/src/test/scala/app/state/CompareStateSpec.scala`: a coordinate
  is a place; `collidesWith` reads branch, tree and pin; a copy carries the
  source's grid as its pending seed. Three assertions are removed with what
  they pinned: that two selections at one place are two pairs (MTR-D-10), and
  that a copy and a removed row carry a display mode.
- `modules/app/src/test/scala/app/state/SelectionEditSpec.scala`: the tests of
  `applyAt` and `removeAt` are removed with those functions.
- `modules/app/src/test/scala/app/views/AnalyzeViewSeedSpec.scala`:
  `engagedSlots` without a selection, and `copySeed` over a grid.
- `modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala`: built
  with `GridColumn`, with every assertion kept.

Commands, each green:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
sbt serverIt/test
```

The register-dev skill's leaked-state cleanup runs before `serverIt/test`.
Part B ends the continuation, so BATS suites C and A run then, and are the
user's to run:

```bash
tests/bats/run-suites.sh C A
```

A check in the browser, which the user carries out on the walkthrough's data:
every row of the worked example above gives the stated request and chart; a
ninth column cannot be added; a fourteenth node cannot be ticked; the header
row and the name column stay in view while the table scrolls; a mirrored card's
boxes are disabled; and a copied card shows the source's columns and ticks.

Version: PATCH on landing, because shipped frontend code changes and no
external API does.
