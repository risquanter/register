# Manual Test Plan: Query Result Visualization

**Status:** Draft — aligned with PLAN-QUERY-RESULT-VISUALIZATION.md v3  
**Prerequisite:** All 6 implementation phases complete, application running locally  
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
| **Total** | **32** | | | |

**Sign-off:**

| Role | Name | Date | Result |
|------|------|------|--------|
| Tester | | | |
| Developer | | | |
