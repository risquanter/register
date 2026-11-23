# Plan: mitigation rows behave as node rows, and the tree select comes first

**Status:** Implemented 2026-11-22 at version 0.26.1. `commonJVM/test`,
`server/test`, `app/test`, `serverIt/test` and BATS suite A are green. The
browser check in the verification plan is the user's and closes the plan.
The continuation section "A curve request states valuations, and the card
becomes a grid" is open. Its Part A is specified in full and has no open
decision. Its Part B has its design ruled and no signatures yet.
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

**Status:** rulings recorded 2026-11-22. Part A is specified in full and has
no open decision. Part B has its design ruled. Part B's exact signatures, its
inventory amendment and its verification plan are added to this section before
any Part B edit.

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

### Part B — the grid (design ruled, signatures to follow)

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

Open until Part B's signatures are written, and settled there: how many columns
a card may hold, which the count of marker shapes that stay distinct on the
chart bounds; and how a compare card's identity is stated once a card holds
several selections, which today's `SlotCoordinate.selection` decides.

### Documents Part B makes stale

- `docs/user/MITIGATION-WALKTHROUGH.md`, step 11.
- This plan's objective and worked example, which describe the gestures the
  grid removes.
- `docs/dev/plans/PLAN-RISKTRANSFORM-INVENTORY.md`, the OD-3 entry, whose
  display ruling the grid replaces. The user edits that file.
