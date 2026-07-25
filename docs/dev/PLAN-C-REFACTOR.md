# PLAN — Phase C machinery refactor ("C-refactor", Scope 1) (DRAFT)

Status: DRAFT 2026-07-25 — task inventory settled; exact signatures and file
inventories to be finalized before approval (G3). Decision context and fixed
constraints: `PLAN-PHASE-E-HISTORY.md`. No code work is authorized by this
document in its draft state.

Two self-contained, independently landable and revertable tasks. They touch
disjoint modules (A: server, B: app) and can land in either order; both
precede the Phase E feature scope.

## Task A — read-path consistency (server, internal only)

Problem: the non-atomic multi-query read class recorded in
`PLAN-PHASE-E-HISTORY.md` ("Problem class on record") — every read in a
multi-query operation (tree load: meta `get` + node `list` + per-node
`get`s; the two sides of a changed-nodes computation; merge-preview value
reads) dereferences the mutable branch head independently, so a concurrent
commit produces a torn read mixing two commits' states.

Scope: the tree read path (structure, LEC, changed-nodes service reads).
Other readers (merge preview byte reads, workspace reconciliation) migrate
opportunistically later, not here.

No wire contract changes, no new endpoints, no UI changes. The fix's
signature shape is finalized at plan approval (pending the Phase E
API-shape rulings it must stay consistent with).

Regression gate: every existing suite green; observable behaviour
identical; plus a new test pinning read consistency under a concurrent
write (the previously-untestable torn-read case).

## Task B — compare-slot coordinate generalization (app, client only)

Today a compare slot's coordinate is a branch (`CompareTarget` wrapping
`BranchChoice`); the slot already bundles everything else it needs as an
independent source: its own `TreeViewState` (tree + selection), curve
cache, palette family, and changed-nodes state. This task widens the slot
coordinate from `branch` to `(tree, revision)` so a slot can point at a
different tree (and, once Phase E lands, a pinned commit) — enabling
cross-tree curve comparison (e.g. an Operational Risk tree against a Brand
Damage tree) and giving Phase E's compare-to-current a slot semantics
instead of special wiring.

Items:

- Slot coordinate type: `branch` → `(tree, revision)`; revision = branch
  now, branch-or-pin after Phase E. Slot-identity stability rule unchanged.
- Slot picker UI: choose tree and branch, not only branch.
- Palette keying: currently by branch name (localStorage); key widens with
  the coordinate.
- Overlay series-id suffix: currently `@<branch>`; becomes slot-scoped.
  This supersedes the design basis of the open backlog item "compare-mode
  hover bridge" (parse/build branch-suffixed ids) — that fix must target
  slot-scoped ids, not branch-suffixed ones.
- Changed-nodes markers: requested only when both sides share tree lineage
  (same `TreeId`); cross-tree slots simply do not call the service — no
  lineage, no markers, by design (see the identity-vs-value comparison
  distinction in `PLAN-PHASE-E-HISTORY.md` context).
- Chart composition: same-tree slots keep today's ID pairing (two curves =
  two versions of one node, per-node shade matching, missing-side
  handling); cross-tree slots draw the plain union of selected curves as
  independent series — the pairing step is skipped, colour identifies the
  slot.

Server changes: none.

Regression gate: existing branch-compare behaviour byte-identical for
same-tree slots (pairing, palettes, markers, hover); `sbt app/test` green;
manual check of the §6 compare flows unchanged.

## Downstream plans that build on this design

- `PLAN-PHASE-E-HISTORY.md` (Scope 2): compare-to-current is a slot whose
  revision is pinned — not bespoke wiring; the changed-nodes endpoint's
  same-tree constraint is the lineage rule above.
- Backlog "compare-mode hover bridge" fix: retargeted to slot-scoped
  series ids (noted in the backlog entry).
