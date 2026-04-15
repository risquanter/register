# Plan: Query Result Visualization — v2

**Status:** Draft  
**Scope:** Planning only — no code changes  
**Predecessor:** PLAN-QUERY-RESULT-VISUALIZATION.md (v3, fully implemented)  
**Date:** 2026-04-14

---

## 0 Motivation

Two UX issues surfaced during manual testing of the implemented v1/v3
plan:

1. **Node identity is opaque.** The query result "matching nodes" list
   shows raw ULID strings like `NodeId(01KP6W0Y48CMEX60GBM7A23C3E)`.
   The tree views show `"Cyber Attacks (expert, p=0.25)"` — name +
   cherry-picked params. Neither format is ideal: the user cannot
   cross-reference result nodes with tree nodes, and the tree label
   hides the stable identifier while showing only a partial param
   summary.

2. **Query error reporting is split across two visual containers.**
   Client-side parse errors appear inline in the query panel; server
   type-check errors appear in the separate `QueryResultCard`. This is
   inconsistent with the established error pattern in the Design view
   forms, where all domain errors stay inside their owning card.

This plan addresses both issues as independent work streams.

---

## 1 Work Stream A — Node Display Reform

### 1.1 Goal

Across **all** tree views, adopt a consistent node label format:

```
Name (id: ULID_VALUE)
```

Full node parameters are accessible via a **hover tooltip** that shows
a structured summary of all fields.

### 1.2 Current State

| View | Leaf label | Portfolio label |
|---|---|---|
| `TreeDetailView` (Analyze) | `"${name} (${distributionType}, p=${probability})"` | `"${name}"` |
| `TreePreview` (Design) | `"${name} (${distType}, p=${probability})"` | `"${name}"` |
| `QueryResultCard` (Analyze) | `nid.toString` — raw ULID | N/A |

**Problems:**

- The tree label shows an incomplete parameter subset (type + probability
  only). For expert leaves, percentiles and quantiles are hidden. For
  lognormal leaves, minLoss and maxLoss are hidden.
- The `QueryResultCard` matching-nodes list shows only raw ULID strings
  with no name. Cross-referencing with the tree requires the user to
  click-select each node to find it.
- The ULID is not shown anywhere in the tree views, making it impossible
  to map query result IDs to tree nodes.

### 1.3 Target State

#### 1.3.1 Tree label format (all views)

**Leaves:**
```
Cyber Attacks (id: 01KP6W0Y48CMEX60GBM7A23C3E)
```

**Portfolios:**
```
Operational Risk (id: 01KP6W0Y48CMEX60GBM7A23C3E)
```

Rationale: The ID is the stable, unique identifier. Distribution
parameters are secondary detail — they belong in the tooltip, not the
inline label. Showing the ID makes the matching-nodes list in
`QueryResultCard` immediately cross-referenceable.

#### 1.3.2 Hover tooltip (all views)

On hover, display a structured tooltip showing **all** node fields.

**Leaf tooltip example:**
```
Cyber Attacks
─────────────────────
ID:           01KP6W0Y48CMEX60GBM7A23C3E
Type:         expert
Probability:  0.25
Percentiles:  [0.10, 0.50, 0.90]
Quantiles:    [1000, 5000, 25000]
```

**Leaf tooltip (lognormal) example:**
```
Server Outage
─────────────────────
ID:           01KP6W0Y48CMEX60GBM7A23C3E
Type:         lognormal
Probability:  0.15
Min Loss:     1000
Max Loss:     50000
```

**Portfolio tooltip example:**
```
Operational Risk
─────────────────────
ID:           01KP6W0Y48CMEX60GBM7A23C3E
Children:     3
```

Implementation: Use the native `title` attribute for a zero-dependency
first pass. If richer styling is needed later, switch to a custom tooltip
component with `position: absolute` and `pointer-events: none`.

#### 1.3.3 QueryResultCard matching-nodes list

Replace raw `nid.toString` with `"${name} (id: ${shortId})"` where name
is resolved from the tree currently loaded in `TreeViewState`.

**Approach:** Pass the `Signal[LoadState[RiskTree]]` (or a derived
`Signal[Map[NodeId, RiskNode]]` lookup) into `QueryResultCard` so it
can resolve names. If resolution fails (tree not loaded), fall back to
the raw ULID.

### 1.4 Files to Change

| # | File | Change |
|---|---|---|
| A1 | `TreeDetailView.scala` | Update `nodeLabel()` → `"${name} (id: ${id})"`. Add `title` attribute to the `node-label` span with full tooltip text. |
| A2 | `TreePreview.scala` | Update `TreeNode.label` → `"${name} (id: ???)"`. Note: Design preview uses draft data (`LeafDraft`), which may not have a server-assigned ULID yet. **Decision needed:** skip ID in preview (drafts have no stable ID) or show a placeholder like `(draft)`. |
| A3 | `QueryResultCard.scala` | Accept a `Signal[Map[NodeId, RiskNode]]` parameter. Resolve node names in the matching-nodes list. |
| A4 | `AnalyzeView.scala` | Derive and pass the node-lookup signal to `QueryResultCard`. |

### 1.5 Design Decisions

**D1: TreePreview (Design view) — draft nodes have no ULID.**  
Draft nodes are client-side only (`PortfolioDraft`, `LeafDraft`) and
use string names as identifiers, not server-assigned ULIDs. Options:

- **(a) Skip ID in preview labels.** Show `"Cyber Attacks (draft)"` or
  just `"Cyber Attacks"` as today. Tooltip shows all draft params.
- **(b) Show client-generated temporary IDs.** Not useful to the user.

**Recommendation:** Option (a). The Design view preview is for tree
construction, not for cross-referencing with query results. The ID is
only meaningful after server persistence.

**D2: Tooltip implementation.**  
- **(a) Native `title` attribute.** Zero dependency, no CSS, works
  everywhere. Delayed appearance (~500ms), plain text only.
- **(b) Custom CSS tooltip.** Styled, instant, monospace. Requires a
  small component and CSS.

**Recommendation:** Start with (a). Upgrade to (b) if user feedback
demands richer formatting.

**D3: ID format in label — full ULID vs truncated.**
- **(a) Full ULID** — 26 chars, e.g. `01KP6W0Y48CMEX60GBM7A23C3E`.
  Unambiguous, directly copyable for API use.
- **(b) Truncated** — e.g. `01KP6W…3C3E` (first 6 + last 4).
  Shorter, but may collide visually in small trees.

**Recommendation:** (a) Full ULID. The monospace font and horizontal
scroll in the tree container handle long labels. The tooltip is always
available for copying. Truncation can be introduced later with CSS
`text-overflow: ellipsis` on the ID portion if needed.

### 1.6 Implementation Phases

**Phase A0: TreeDetailView + TreePreview label reform**
- Update `nodeLabel()` in `TreeDetailView` and `label` in `TreePreview`.
- Add `title` tooltip to the node-label span in both views.
- ~30 min.

**Phase A1: QueryResultCard name resolution**
- Derive `Signal[Map[NodeId, RiskNode]]` from `TreeViewState.selectedTree`.
- Thread it into `QueryResultCard`.
- Render `"${name} (id: ${shortId})"` in the matching-nodes list.
- ~30 min.

### 1.7 Test Strategy

- **Unit (common):** None — no new domain logic.
- **Unit (server):** None — server is unaffected.
- **Manual:** Verify all three views show the new label format. Hover
  each node type and confirm tooltip contains all fields.
- **Integration:** Existing `LECChartEndpointSpec` is unaffected (no
  label logic in the endpoint).

---

## 2 Work Stream B — Unified Query Error Reporting

### 2.1 Goal

Consolidate all query-domain errors (client-side parse errors **and**
server-side type-check/validation errors) to render **inline inside**
`div.analyze-query-panel`, using the established `span.form-error`
pattern from the Design view forms.

The `QueryResultCard` should **only** handle:
- `Idle` (placeholder)
- `Loading` (spinner)
- `Loaded(response)` (verdict + proportion + matching nodes)

Server errors that are **not** query-domain (network failures, 500s)
should route to the global `ErrorBanner`, consistent with how infra
errors are handled elsewhere.

### 2.2 Clarification — Status Display Stays As-Is

The current `QueryResultCard` rendering for `Loaded(response)` is
**excellent and stays unchanged**. Specifically:

- The "SATISFIED" / "NOT SATISFIED" badge with the `ParsedQuery` echo.
- The proportion bar and label.
- The matching-nodes list (to be enhanced in Work Stream A, but the
  **card layout and placement** stay as-is).

This work stream **only** touches the error path. The existing status
display (`badge-satisfied` / `badge-not-satisfied`, `proportion-bar`,
`query-echo`) is well placed and looks good.

### 2.3 Current State

| Error type | Current location | CSS | Font size |
|---|---|---|---|
| Client parse error | Inline in `div.analyze-query-panel`, between checkbox and Run button | `div.query-parse-error > span.form-error` | `--text-xs` |
| Server type-check error (e.g. "unknown function: p_95") | Separate `QueryResultCard` block below query panel | `div.query-result-error > p.error-message` | `--text-sm` |

**Inconsistency with Design view:** In `RiskLeafFormView` and
`PortfolioFormView`, **all** domain errors stay inside their form card:
- Field validation → inline `span.form-error` below the input.
- Server topology errors → `div.form-error` at the form bottom.
- Only infra errors go to `ErrorBanner`.

### 2.4 Target State

```
div.analyze-query-panel
├── h3("Query")
├── div.form-field (textarea)
├── div.form-field.query-instant-validate (checkbox)
├── child.maybe: div.query-parse-error > span.form-error    ← parse errors (unchanged)
├── child.maybe: div.query-server-error > span.form-error   ← server domain errors (NEW)
└── div.query-actions (Run button)
```

Both error slots use `span.form-error` at `--text-xs`, identical to
field errors in the Design view.

The `QueryResultCard` state machine simplifies to:

| `LoadState` | Renders |
|---|---|
| `Idle` | Placeholder (unchanged) |
| `Loading` | "Evaluating query…" (unchanged) |
| `Failed(msg)` | **Removed** — never reaches `QueryResultCard` |
| `Loaded(r)` | Verdict + proportion + matches (unchanged) |

### 2.5 Error Routing

Server responses need classification:

| HTTP status | Error category | Target |
|---|---|---|
| 400 with validation body (e.g. "type-checking failed") | Query domain error | Inline `query-server-error` in query panel |
| 422 with validation body | Query domain error | Inline `query-server-error` in query panel |
| 5xx / network timeout / connection refused | Infra error | `GlobalError` → `ErrorBanner` |
| 4xx other (403, 404, 409) | Infra/auth error | `GlobalError` → `ErrorBanner` |

**Implementation:** Add a `queryServerError: Var[Option[String]]` to
`AnalyzeQueryState`. In the `executeQuery()` method, differentiate
between domain failures (set `queryServerError`) and infra failures
(propagate to `GlobalError`). Clear `queryServerError` on each new
query execution.

### 2.6 Files to Change

| # | File | Change |
|---|---|---|
| B1 | `AnalyzeQueryState.scala` | Add `queryServerError: Var[Option[String]]`. Update `executeQuery()` to classify errors and route accordingly. Clear on new execution. |
| B2 | `AnalyzeView.scala` | Add a `child.maybe` for `queryServerError` below the parse-error slot (same `div > span.form-error` pattern). |
| B3 | `QueryResultCard.scala` | Remove `renderError` — the `Failed` branch becomes unreachable for domain errors. Optionally keep a safety-net rendering for unexpected states. |
| B4 | `app.css` | Add `.query-server-error` class with same spacing as `.query-parse-error`. |

### 2.7 Implementation Phases

**Phase B0: State + routing**
- Add `queryServerError` to `AnalyzeQueryState`.
- Refactor `executeQuery()` to classify failures.
- ~30 min.

**Phase B1: View wiring**
- Wire `queryServerError` into `AnalyzeView` as inline `form-error`.
- Simplify `QueryResultCard` (remove `Failed` rendering).
- Add `.query-server-error` CSS class.
- ~20 min.

### 2.8 Test Strategy

- **Unit (common):** None — no new domain logic.
- **Unit (server):** None — server endpoints unchanged.
- **Manual:** Trigger both error types and confirm:
  - Parse error: "Expected ',' before scope formula…" appears inline
    in the query panel, below the textarea.
  - Server error: "Query type-checking failed: unknown function: p_95"
    appears inline in the query panel, in the same visual slot.
  - Network error: appears in the global `ErrorBanner` at the top of
    the layout.
  - Successful query: `QueryResultCard` shows SATISFIED/NOT SATISFIED
    verdict, proportion bar, and matching nodes — unchanged.

---

## 3 Dependency Between Work Streams

A and B are independent. They may be implemented in either order or in
parallel. The only shared file is `AnalyzeView.scala`, but the changes
touch different sections (result card wiring vs error slot).

**Recommended order:** B first (smaller, immediate UX fix), then A.

---

## 4 Out of Scope

- Custom styled tooltip component (deferred to future iteration).
- Query language autocomplete / intellisense.
- Rich error messages with source position highlighting.
- Changes to the `QueryResponse` wire format.
