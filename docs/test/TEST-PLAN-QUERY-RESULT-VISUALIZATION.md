# Manual Test Plan: Query Result Visualization

**Status:** Draft — aligned with PLAN-QUERY-RESULT-VISUALIZATION.md v4  
**Prerequisite:** All 6 implementation phases (P0–P5) complete, application running locally  
**Tester setup:** `sbt run` → open `http://localhost:8090` in browser  

---

## Prerequisites

Before starting, verify the test workspace has:

- [ ] At least one risk tree with **≥ 5 simulated leaf nodes**
- [ ] At least one risk tree with **≥ 14 simulated leaf nodes** (for cap tests)
- [ ] A query that returns `satisfied = true` with **2–5 matching nodes**
- [ ] A query that returns `satisfied = true` with **≥ 14 matching nodes** (for silent truncation test)
- [ ] A query that returns `satisfied = false` or no matches (for negative test)

If test data does not exist, create a risk tree and run simulations
before proceeding. See [TESTING.md](TESTING.md) for sample payloads.

---

## Notation

| Symbol | Meaning |
|--------|---------|
| **[F1]** | Feature 1: Dual-palette LEC chart |
| **[F2]** | Feature 2: Green tree highlighting |
| **[F3]** | Feature 3: Auto-expand tree |
| **[P0]** | Prerequisite: `matchingNodeIds` → `satisfyingNodeIds` rename |
| **[P3]** | Phase 3: Tree colour sync (inline borders from nodeColorMap) |
| **[P4]** | Phase 4: Bidirectional hover (chart ↔ tree) |
| **[P5]** | Phase 5: Colour picker + live preview |

---

## Section 1: Prerequisite — Field Rename [P0]

### T-P0.1 Wire format uses `satisfyingNodeIds`

1. Open browser DevTools → Network tab.
2. Run a query that returns matches.
3. Inspect the `/query` response JSON.
4. **Expected:** Field name is `"satisfyingNodeIds"`, not `"matchingNodeIds"`.

| Result | Pass / Fail | Notes |
|--------|-------------|-------|
| Field name correct | | |

---

## Section 2: Feature 2 — Green Tree Highlighting [F2]

### T-F2.1 Matching nodes highlighted green

1. Run a query returning 2–5 matching nodes.
2. Inspect the tree panel.
3. **Expected:** Each matching node row has:
   - A **green** left border (not blue).
   - A **translucent green** background.
4. **Expected:** Non-matching nodes have no green styling.

### T-F2.2 Green highlight uses correct CSS tokens

1. Right-click a highlighted node → Inspect Element.
2. Check computed styles.
3. **Expected:** `border-left-color` resolves to `#86efac` (`--query-match`).
4. **Expected:** `background-color` resolves to `rgba(134, 239, 172, 0.12)` (`--query-match-surface`).

### T-F2.3 Green highlight persists until next query

1. Run a query → nodes highlighted green.
2. Click around the tree (single-click, expand/collapse).
3. **Expected:** Green highlights persist — not cleared by navigation.
4. Run a **different** query → different matches.
5. **Expected:** Old green highlights removed, new ones applied.

### T-F2.4 No highlight on failed query

1. Run a query that returns `satisfied = false` or zero matches.
2. **Expected:** No green highlights appear. Any prior highlights from a
   previous query are cleared.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-F2.1 Green highlight visible | | |
| T-F2.2 CSS tokens correct | | |
| T-F2.3 Persists until next query | | |
| T-F2.4 No highlight on failure | | |

---

## Section 3: Feature 3 — Auto-Expand Tree [F3]

### T-F3.1 Tree auto-expands to reveal matches

1. Collapse the entire tree (close all nodes).
2. Run a query returning matches in deeply nested nodes.
3. **Expected:** Tree automatically expands to reveal every matching node.
   All ancestors of matching nodes are expanded.

### T-F3.2 Expansion is additive

1. Manually expand some branches.
2. Run a query whose matches are in **different** branches.
3. **Expected:** Previously expanded branches remain expanded.
   New ancestor paths also expand. No collapse occurs.

### T-F3.3 No expansion on zero matches

1. Collapse the tree.
2. Run a query returning zero matches.
3. **Expected:** Tree state unchanged — nothing expands.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-F3.1 Auto-expand on match | | |
| T-F3.2 Additive expansion | | |
| T-F3.3 No expansion on zero | | |

---

## Section 4: Feature 1 — Auto-Triggered Green LEC Chart [F1]

### T-F1.1 Query triggers green LEC chart automatically

1. Run a query returning 2–5 matching nodes.
2. **Expected:** LEC chart appears automatically (no button click needed).
3. **Expected:** All curves are shades of **green** — darkest for highest-risk
   node (highest p95), lightest for lowest-risk.

### T-F1.2 Chart legend shows node names

1. Inspect the rendered chart legend.
2. **Expected:** Each curve's legend entry shows the **node name**
   (not the raw node ID).

### T-F1.3 Green colour ordering reflects risk magnitude

1. Identify which node has the highest p95 loss (check node details or
   compare curve extent to the right on the X-axis).
2. **Expected:** That node's curve is the **darkest green**.
3. **Expected:** Curves progress from dark → light as p95 decreases.

### T-F1.4 Wire format sends palette names

1. Open browser DevTools → Network tab.
2. Run a query.
3. Inspect the `/lec-chart` POST request body.
4. **Expected:** JSON body has shape:
   ```json
   { "curves": [
       { "nodeId": "...", "palette": "green" },
       { "nodeId": "...", "palette": "green" }
   ]}
   ```
5. **Expected:** All entries have `"palette": "green"`.

### T-F1.5 Silent truncation for >13 query matches

1. Run a query returning ≥ 14 matching nodes.
2. **Expected:** Chart renders with exactly **13 green curves** (top 13 by p95).
3. **Expected:** No error message. All 14+ nodes are still highlighted
   green in the tree and all expanded — only the chart is capped.
4. Inspect the `/lec-chart` POST body.
5. **Expected:** Request sends all matching node IDs (no client-side truncation).
   The server does the truncation.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-F1.1 Auto-trigger on query | | |
| T-F1.2 Legend shows names | | |
| T-F1.3 Colour ordering by p95 | | |
| T-F1.4 Wire format correct | | |
| T-F1.5 Silent truncation >13 | | |

---

## Section 5: Manual Selection — Aqua Curves [F1]

### T-F1.6 Ctrl+click adds aqua curve

1. **Without** running a query first, Ctrl+click (or Cmd+click on macOS)
   a simulated leaf node.
2. **Expected:** LEC chart appears with one curve in an **aqua** shade.
3. Ctrl+click a second node.
4. **Expected:** Chart now shows two aqua curves, darkness ordered by p95.

### T-F1.7 Ctrl+click toggles selection off

1. Ctrl+click a node that is already selected (aqua ring visible).
2. **Expected:** Node is deselected. Its curve is removed from the chart.
   Remaining curves recoloured (may shift shades).

### T-F1.8 Hard cap at 13 user-selected nodes

1. Ctrl+click 13 different nodes (one by one).
2. **Expected:** Chart shows 13 aqua curves.
3. Ctrl+click a 14th node.
4. **Expected:** Error banner appears with message mentioning "Maximum 13
   user-selected curves". The 14th node is **not** added to the chart.
5. Dismiss the error banner.
6. Ctrl+click one of the existing 13 nodes to deselect it.
7. Ctrl+click the previously rejected node.
8. **Expected:** Node is now accepted. Chart shows 13 aqua curves again.

### T-F1.9 Blue ring CSS for user-selected nodes

1. Ctrl+click a node.
2. **Expected:** Node row shows the existing `node-chart-selected` blue
   ring styling (unchanged from pre-feature behaviour).

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-F1.6 Aqua curve on Ctrl+click | | |
| T-F1.7 Toggle off deselects | | |
| T-F1.8 Hard cap at 13 | | |
| T-F1.9 Blue ring CSS | | |

---

## Section 6: Coexistence — Green + Aqua + Purple [F1, D7-A]

### T-F1.10 Query green + manual aqua coexist

1. Run a query returning 3 matching nodes → green curves appear.
2. Ctrl+click 2 **non-matching** nodes.
3. **Expected:** Chart now shows:
   - 3 green curves (query matches)
   - 2 aqua curves (manual picks)
4. All 5 curves visible simultaneously with distinct colour families.

### T-F1.11 Overlap turns purple

1. With query results visible (green curves on chart), Ctrl+click a
   node that **is** in the query result set.
2. **Expected:** That node's curve changes from green to **purple**.
3. **Expected:** The node has **both** the green background highlight
   (query match) and the blue ring (chart-selected) in the tree.
4. Inspect the `/lec-chart` POST body.
5. **Expected:** The overlapping node's entry shows `"palette": "purple"`.

### T-F1.12 Deselecting overlap node restores green

1. Ctrl+click the purple/overlap node again to deselect it.
2. **Expected:** Node's curve returns to **green** (still in query set).
3. Node in tree: green highlight remains, blue ring removed.

### T-F1.13 New query replaces green set, aqua persists

1. Query result with green curves + some aqua manual picks visible.
2. Run a **different** query returning different nodes.
3. **Expected:** Green curves update to new matches. Aqua curves for
   manually selected nodes persist unchanged.
4. If a previously aqua-selected node is now in the new query set,
   its curve should turn purple (new overlap).

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-F1.10 Coexistence | | |
| T-F1.11 Overlap → purple | | |
| T-F1.12 Deselect overlap → green | | |
| T-F1.13 New query updates green | | |

---

## Section 7: Combined Behaviour [F1 + F2 + F3]

### T-ALL.1 Full happy path

1. Collapse the entire tree.
2. Run a query returning 3+ matches in nested branches.
3. **Expected — simultaneously:**
   - Tree auto-expands to reveal all matching nodes [F3]
   - Matching nodes have green highlight [F2]
   - LEC chart renders with green curves, correct p95 ordering [F1]
4. Ctrl+click 2 additional non-matching nodes.
5. **Expected:** Aqua curves added to chart alongside green.

### T-ALL.2 Second query resets correctly

1. After T-ALL.1, run a new query with different matches.
2. **Expected:**
   - Old green highlights removed, new ones applied [F2]
   - Tree expands further to reveal new matches (additive) [F3]
   - Green curves on chart update to new matches [F1]
   - Aqua curves from step 4 of T-ALL.1 persist [F1, D7-A]

### T-ALL.3 Empty state — no query, no selections

1. Open a tree without running a query or Ctrl+clicking.
2. **Expected:** No green highlights, no auto-expansion, no chart rendering.
   Chart area shows empty/placeholder state.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-ALL.1 Full happy path | | |
| T-ALL.2 Second query reset | | |
| T-ALL.3 Empty state | | |

---

## Section 8: Edge Cases

### T-EDGE.1 Single matching node

1. Run a query returning exactly 1 match.
2. **Expected:** One green curve on chart (darkest green shade). Tree
   expands to that node. Node highlighted green.

### T-EDGE.2 Root node matches

1. Run a query where the **root** (portfolio) node matches.
2. **Expected:** Root node highlighted green. No expansion needed (root
   always visible). Green curve rendered.

### T-EDGE.3 All nodes match

1. Run a query where every node in the tree matches.
2. **Expected:** All nodes highlighted green. Up to 13 green curves on
   chart (silent truncation). Full tree expanded.

### T-EDGE.4 Rapid Ctrl+click

1. Rapidly Ctrl+click 5 nodes in quick succession (< 500ms total).
2. **Expected:** All 5 end up selected. Chart renders once with all 5
   aqua curves (debounce batches the request). No duplicate or missing
   curves.

### T-EDGE.5 Ctrl+click on non-simulated node

1. Ctrl+click a node that has **not been simulated** (no LEC data).
2. **Expected:** Node is added to selection, but the server returns
   an error or the chart renders without that curve (graceful handling).
   No crash or blank chart.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-EDGE.1 Single match | | |
| T-EDGE.2 Root matches | | |
| T-EDGE.3 All nodes match | | |
| T-EDGE.4 Rapid Ctrl+click | | |
| T-EDGE.5 Non-simulated node | | |

---

## Section 9: Regression

### T-REG.1 Single-click selection unchanged

1. Single-click (no Ctrl) a node.
2. **Expected:** Node becomes selected (highlighted per existing
   `node-selected` styling). No chart interaction triggered.

### T-REG.2 Existing tree collapse/expand unchanged

1. Click expand/collapse arrows on tree nodes.
2. **Expected:** Normal expand/collapse behaviour. No interference from
   auto-expand logic (auto-expand only fires on query).

### T-REG.3 Chart renders correctly for pre-existing flows

1. If any legacy chart-triggering UI elements still exist (e.g. old
   "View LEC" button), verify they behave correctly or are removed.
2. **Expected:** No dead buttons, no stale references.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-REG.1 Single-click | | |
| T-REG.2 Collapse/expand | | |
| T-REG.3 Legacy UI | | |

---

## Section 10: Tree Colour Sync [P3]

### T-P3.1 Charted nodes show solid coloured left border

1. Ctrl+click 2–3 nodes to chart them.
2. Inspect the tree panel.
3. **Expected:** Each charted node row has a **solid** left border whose
   colour matches the curve colour assigned to it in the chart legend.
4. **Expected:** Border width is **3px**.

### T-P3.2 Query-matched-but-not-charted nodes show dotted neutral border

1. Run a query returning 4+ matching nodes.
2. Find a matching node that is **not** charted (no Ctrl+click).
3. **Expected:** That node has a **dotted** left border in `--neutral-700`
   colour — visually distinct from the solid coloured borders.
4. Ctrl+click that node to add it to the chart.
5. **Expected:** Dotted border changes to solid coloured border matching
   the newly assigned curve colour.

### T-P3.3 Border colour tracks automatic colour assignment

1. Chart 3 nodes Ctrl+click.
2. Note each node's border colour.
3. **Expected:** Colours are drawn from the 8-family palette (Green, Aqua,
   Purple, Yellow, Orange, Red, Pink, Emerald), shades 200–900.
4. DeCtrl+click one node, then chart a different node.
5. **Expected:** Remaining nodes keep their border colours. New node gets
   its own assigned colour.

### T-P3.4 Non-matching non-charted nodes have no colour border

1. Look at nodes that are neither query-matched nor charted.
2. **Expected:** No coloured or dotted left border (standard tree styling).

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-P3.1 Charted border matches curve | | |
| T-P3.2 Dotted neutral for uncharted match | | |
| T-P3.3 Colour tracks assignment | | |
| T-P3.4 No border for unrelated nodes | | |

---

## Section 11: Bidirectional Hover [P4]

### T-P4.1 Hover chart curve → tree border thickens

1. Chart 3+ nodes. Hover over one curve in the chart.
2. **Expected:** Hovered curve stays **fully opaque**. Other curves dim
   to reduced opacity (~0.2).
3. **Expected:** Corresponding node in the tree panel has its left border
   width **thickened** from 3px to 5px.
4. Move mouse away from the chart.
5. **Expected:** All curves return to full opacity. All tree borders
   revert to 3px.

### T-P4.2 Hover tree node → chart curve highlighted

1. Hover over a charted node row in the tree panel.
2. **Expected:** That node's curve in the chart stays opaque. Other curves
   dim to reduced opacity.
3. **Expected:** Node's tree border thickens to 5px.
4. Move mouse away from the node.
5. **Expected:** All curves return to full opacity. Border reverts.

### T-P4.3 No feedback loop on rapid hover

1. Rapidly move the mouse between 3+ chart curves in quick succession.
2. **Expected:** Smooth transitions, no flickering. Only the currently
   hovered curve is highlighted.
3. Open browser DevTools → Console.
4. **Expected:** No errors or warnings during rapid hovering.

### T-P4.4 Hover works across chart ↔ tree without sticking

1. Hover a chart curve (tree border thickens).
2. Without pausing, move the mouse directly to the tree panel and hover
   a **different** node.
3. **Expected:** First node's border reverts. Second node's border thickens.
   Chart correctly switches which curve is highlighted.

### T-P4.5 Hover on non-charted node is no-op

1. Hover over a node that is **not** charted (no curve in chart).
2. **Expected:** No chart dimming occurs. No errors.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-P4.1 Chart hover → tree thickens | | |
| T-P4.2 Tree hover → chart highlights | | |
| T-P4.3 No feedback loop | | |
| T-P4.4 Cross-panel hover | | |
| T-P4.5 Non-charted hover no-op | | |

---

## Section 12: Colour Picker + Live Preview [P5]

### T-P5.1 Swatch trigger appears for charted nodes

1. Chart 2+ nodes via Ctrl+click.
2. **Expected:** Each charted node row in the tree shows a small coloured
   **swatch square** icon (the colour picker trigger).
3. **Expected:** Non-charted nodes do **not** show a swatch trigger.

### T-P5.2 Click swatch opens picker popover

1. Click the swatch trigger for a charted node.
2. **Expected:** An 8×8 colour swatch grid appears as a popover/dropdown
   near the trigger. Grid contains samples from all 8 palette families.
3. **Expected:** The grid has a header/title row and an "↺ Auto" reset
   button.

### T-P5.3 Hover over swatches shows live preview

1. Open the picker for a node.
2. Hover over different colour swatches in the grid.
3. **Expected:** The node's chart curve colour changes to match the hovered
   swatch in **real time** (debounced ~50ms).
4. **Expected:** The node's tree border colour also updates to the
   previewed colour.

### T-P5.4 Click a swatch to confirm colour override

1. Open the picker and click a specific colour swatch.
2. **Expected:** Picker closes. The node's curve and tree border are now
   the selected colour.
3. Hover the chart, interact with tree — the override colour persists.
4. **Expected:** Override survives hover interactions and panel switches.

### T-P5.5 Escape key closes picker and reverts preview

1. Open the picker for a node.
2. Hover over a swatch (preview changes the curve colour).
3. Press the **Escape** key.
4. **Expected:** Picker closes. The node's curve reverts to its
   **previous** colour (auto-assigned or prior override). The preview
   colour is discarded.

### T-P5.6 Click outside closes picker and clears preview

1. Open the picker for a node.
2. Hover over a swatch (preview changes the curve colour).
3. Click **outside** the picker (anywhere else on the page).
4. **Expected:** Picker closes. The node's curve reverts to its previous
   colour — the hover preview is **not** committed.
5. **Expected:** No stale preview colour remains on the curve or border.

### T-P5.7 "↺ Auto" resets to automatic colour

1. Open the picker and select a custom colour override (per T-P5.4).
2. Reopen the picker for the same node.
3. Click the **"↺ Auto"** reset button.
4. **Expected:** Picker closes. The node's colour reverts to the
   automatically assigned colour (hash-based palette assignment).
5. **Expected:** The override is removed, not just masked.

### T-P5.8 One picker open at a time

1. Open the picker for node A.
2. Click the swatch trigger for a **different** node B.
3. **Expected:** Node A's picker closes. Node B's picker opens.
4. **Expected:** Any preview from node A is cleared/reverted.

### T-P5.9 Preview cleared on every close path

This test specifically verifies that the reactive preview cleanup (F-GP2)
works correctly across all close paths:

1. **Via Escape:**
   a. Open picker, hover a swatch (preview visible on curve).
   b. Press Escape.
   c. **Expected:** Curve reverts immediately. No stale preview.

2. **Via click-outside:**
   a. Open picker, hover a swatch (preview visible on curve).
   b. Click outside the picker.
   c. **Expected:** Curve reverts immediately. No stale preview.

3. **Via colour selection:**
   a. Open picker, hover swatch A (preview A visible).
   b. Click swatch B (different from A).
   c. **Expected:** Curve shows swatch B's colour (committed override).
      Preview A is not left behind.

4. **Via "↺ Auto" reset:**
   a. Open picker, hover a swatch (preview visible).
   b. Click "↺ Auto".
   c. **Expected:** Curve reverts to auto colour. No stale preview.

5. **Via switching node:**
   a. Open picker for node A, hover a swatch.
   b. Click picker trigger for node B.
   c. **Expected:** Node A's curve reverts. Node B's picker opens cleanly.

### T-P5.10 Preview debounce works correctly

1. Open the picker for a node.
2. Rapidly sweep the mouse across several swatches (< 500ms total).
3. **Expected:** The curve colour does not flicker rapidly. Due to 50ms
   debounce, intermediate colours are skipped. Final preview settles
   on the last hovered swatch's colour.

| Test | Pass / Fail | Notes |
|------|-------------|-------|
| T-P5.1 Swatch trigger visible | | |
| T-P5.2 Picker opens on click | | |
| T-P5.3 Live preview on hover | | |
| T-P5.4 Click confirms override | | |
| T-P5.5 Escape reverts preview | | |
| T-P5.6 Click-outside reverts | | |
| T-P5.7 Auto reset removes override | | |
| T-P5.8 One picker at a time | | |
| T-P5.9 Preview cleared all paths | | |
| T-P5.10 Debounce works | | |

---

## Summary

| Section | Tests | Pass | Fail | Blocked |
|---------|-------|------|------|---------|
| P0 — Rename | 1 | | | |
| F2 — Green highlight | 4 | | | |
| F3 — Auto-expand | 3 | | | |
| F1 — Green chart | 5 | | | |
| F1 — Aqua selection | 4 | | | |
| F1 — Coexistence | 4 | | | |
| Combined | 3 | | | |
| Edge cases | 5 | | | |
| Regression | 3 | | | |
| P3 — Tree colour sync | 4 | | | |
| P4 — Bidirectional hover | 5 | | | |
| P5 — Colour picker | 10 | | | |
| **Total** | **51** | | | |

**Sign-off:**

| Role | Name | Date | Result |
|------|------|------|--------|
| Tester | | | |
| Developer | | | |
