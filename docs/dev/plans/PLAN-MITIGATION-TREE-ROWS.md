# Plan: mitigation rows behave as node rows, and the tree select comes first

**Status:** Implemented 2026-11-22 at version 0.26.1. `commonJVM/test`,
`server/test`, `app/test`, `serverIt/test` and BATS suite A are green. The
browser check in the verification plan is the user's and closes the plan.
**Date:** 2026-11-22.
**Plan code:** MTR. **Inventory:** `docs/dev/plans/PLAN-MITIGATION-TREE-ROWS-INVENTORY.md`,
which the user creates.

The change is confined to the `app` module (Scala.js frontend), its stylesheet,
its tests, and the user guide that describes the Analyze view. No endpoint,
DTO, wire shape or simulation figure changes.

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
