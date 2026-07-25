# PLAN — Milestone-2b Phase E: History / Time Travel (DRAFT)

Status: DRAFT 2026-07-25 — decisions E1–E5 under discussion, not yet ruled.
This file collects constraints settled during the decision walk-through so
they are not lost; it becomes the implementation-grade plan (exact
signatures, file inventory, ADR alignment, verification plan) once the
rulings land. No code work is authorized by this document in its draft
state.

## Fixed constraints (settled during decision review, 2026-07-25)

### Authorization of commit-pinned reads: path scoping, not commit provenance

All workspaces share one Irmin store, so any commit's tree physically
contains other workspaces' subtrees. Reads pinned to a commit (`at`
parameter) MUST build their Irmin paths from the authenticated workspace
exactly as branch reads do — the workspace id is resolved server-side from
the presented `WorkspaceKeySecret`, never taken from client input. Under
that rule a client can only ever read its own subtree at any commit, and no
new authorization surface exists: the same path-scoping that protects
branch reads protects commit reads.

The server does NOT verify that a supplied commit hash lies on the active
branch's history. Rationale (security-reviewed 2026-07-25): every commit's
view of a workspace's subtree equals a prior state produced by that
workspace's own authenticated writes — writes are path-scoped the same way,
other workspaces' branches never modify this workspace's paths, and a
three-way merge preserves the newer own-path state — so commit-membership
verification adds no confidentiality and would cost a history walk per
read.

Verification requirement (checked, not assumed): a serverIt test in which
workspace A requests a read pinned to a commit produced by workspace B's
activity, asserting the response contains only A's own subtree state (or
not-found) — never B's data.

### Commit-existence oracle hardening

"Commit not found" and "path absent at that commit" MUST be
indistinguishable in the response (mirrors the A13 precedent: constant
response for not-found vs expired workspaces). Otherwise a workspace-key
holder could probe whether an arbitrary hash exists in the shared store.

### Boundary validation

The `at` pin decodes through `CommitHash.fromString` at the Tapir boundary
(Iron `^[a-f0-9]{40}$`); invalid input → 400 before any handler runs.
Verified 2026-07-25 against `OpaqueTypes.scala`: the refinement pins strict
lowercase hex, so interpolating the value into GraphQL query strings
carries no injection surface.

### Problem class on record: non-atomic multi-query reads (problem only — no
### prescription until the API shapes are settled)

A tree load is several independent GraphQL queries — metadata `get`, node
`list`, then per-node `get`s (`RiskTreeRepositoryIrmin.loadTree` /
`readNodes`) — and **each query dereferences the mutable branch head
independently**. A commit landing on the branch between those queries
produces a torn read: metadata from one commit's state, nodes from
another's. The same class applies to any multi-read operation on a moving
head: the diff service's two sides (`getById` twice, `zipPar`), and the
merge preview's per-path value reads. Writes have been atomic since DD-7
(`set_tree`, one commit per action); reads never received the equivalent
treatment. Phase E's history/pinned-read work touches exactly this read
path, which is why the problem is recorded here; the fix's shape is part of
the open API/signature decisions and is deliberately not prescribed in this
section.

### Functional note (by design, not a defect)

Tree membership is checked against the CURRENT workspace record
(`resolveTreeWorkspace`). A tree deleted from the workspace is therefore
not viewable at historical commits where it still existed. Recorded as a
deliberate consequence; revisit only if a "view deleted trees" requirement
appears.

## Scope separation (ruled 2026-07-25: self-contained, regression-testable chunks)

The work splits into two separately planned, separately approved scopes,
sequenced strictly:

**Scope 1 — Phase C machinery refactor ("C-refactor").** Own plan document:
`PLAN-C-REFACTOR.md`. Two self-contained tasks: (A) server read-path
consistency (the non-atomic multi-query read problem class above; internal
only, no wire changes); (B) compare-slot coordinate generalization
`branch → (tree, revision)` (client only, no server changes) — enables
cross-tree curve comparison and gives compare-to-current a slot semantics.

**Scope 2 — Phase E features.** History endpoint, pinned views,
comparison-related endpoint evolution, fork-at-commit, revert — built on
Scope 1. Contains all open decisions E1–E5; endpoint/DTO shapes are decided
here, not in Scope 1. Semantics inherited from Scope 1 Task B:
compare-to-current is a compare slot whose revision carries a pin — no
bespoke comparison wiring; the changed-nodes computation applies only to
same-tree-lineage slot pairs (identity comparison), while chart comparison
is selection-driven and lineage-free (value comparison).

Each scope gets its own plan document with its own file inventory,
signatures, and verification plan (G3); Scope 1's plan can be finalized
first while Scope 2's rulings complete.

## Open decisions (to be ruled; rulings will be recorded here)

- E1 — history endpoint response shape (typed entry vs sanitized string)
- E2 — commit-state read surface (revision-pin model `(branch,
  Option[CommitHash])` across structure / LEC / diff under discussion)
- E3 — revert vehicle (forward commit via `set_tree` vs native head-set)
- E4 — revert precondition (none vs If-Match)
- E5 — fork-at-commit source on scenario create
- E6 — diff service rename: RULED 2026-07-25 → the `ChangedNodesService`
  family (endpoint `getChangedNodes`, path `/changed-nodes`,
  `ChangedNodesResponse` / `NodeChangeEntry`, SPA `ChangedNodesState`).
  Rationale: the service is a per-node change report consumed as tree
  annotations, not a general comparison primitive; "Scenario" in the old
  name is also wrong once sides become revisions. Lands with Scope 2's
  contract change on the same endpoint (one wire migration).
