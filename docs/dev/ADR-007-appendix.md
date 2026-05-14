# ADR-007 Appendix: Branch Mechanics & Commit Model

**Parent ADR:** [ADR-007-proposal](ADR-007-proposal.md) — Scenario Branching  
**Related:** [ADR-004a Appendix](ADR-004a-appendix.md) — Tree Terminology & Domain-to-Storage Mapping  
**Date:** 2026-03-12

---

## 1. BranchRef

`BranchRef` is a nominal wrapper (per ADR-018) around the Irmin branch name
string. It does not yet exist in the codebase; it is a new type to be
introduced.

```scala
case class BranchRef(value: String)
```

It is **not** a generated ID like `TreeId` or `NodeId` (which are ULIDs). It
is the human-readable Irmin branch name — a string like `"main"` or
`"scenarios/ws123/01HXYZ/high-cyber"`.

`BranchRef` identifies which line of history (which "version of reality") the
system reads from and writes to.

---

## 2. Every Edit Is a Commit

There is no separate "save" action. Irmin commits on every write. When a user
changes a parameter:

```
User changes cyber probability to 0.6
  → server calls irmin.set(
      path    = "risk-trees/{treeId}/nodes/{cyber-id}",
      value   = updatedJson,
      message = "risk-tree:{treeId}:update:{txn}:set-node:{cyber-id}"
    )
  → Irmin creates commit abc123 on the active branch
```

The active branch's HEAD pointer advances from the previous commit to
`abc123`. Every edit, no matter how small, is immediately persisted as
an immutable commit.

---

## 3. Normal Work vs. Scenario Creation

The edit operation is identical in both cases. The only difference is which
branch the commit lands on.

### Normal work (implicit `main`)

All edits accumulate on the default `main` branch. The user does not interact
with branch concepts.

```
                          edit cyber     edit hardware
main:  ──── c0 ──────────── c1 ──────────── c2 ────►
```

Each `cN` is an Irmin commit. The `main` branch pointer advances with each
edit.

### Scenario creation ("Save As")

Creating a scenario means creating a new Irmin branch that initially points
to the same commit as `main`. Subsequent edits on the scenario branch diverge
from `main`, while `main` remains unaffected.

```
Step 1 — Create scenario (fork):

main:      ──── c0 ──── c1 ──── c2
                                  ↑
scenario:                         └─ (points to same c2)

Step 2 — Edit on the scenario branch:

main:      ──── c0 ──── c1 ──── c2 ──────────────►  (unchanged)
                                  \
scenario:                          └── c3 ── c4 ──►  (diverged)
```

Commit `c3` (scenario edits cyber-risk) and `c4` (scenario edits another
node) exist only on the scenario branch. `main` still points at `c2`.

### In code

The repository call is the same in both cases:

```scala
// Normal edit (main):
irmin.set(path, value, message)                        // branch defaults to main

// Scenario edit:
irmin.set(path, value, message, branch = scenarioRef)  // explicit branch
```

The `branch: Option[BranchRef] = None` parameter (DD-1) controls the
destination. `None` means `main`; `Some(ref)` means the named scenario
branch. The repository logic, serialisation, and commit message format are
all unchanged.

---

## 4. Merge and the Per-Node Advantage

Because each risk node maps to a separate Irmin path (see
[ADR-004a Appendix §2](ADR-004a-appendix.md#2-domain-to-storage-mapping)),
Irmin's path-level merge resolution handles the common case automatically.

**Example — non-overlapping edits auto-resolve:**

```
main:      c2 ──── c5 (edit market-risk) ────────►
                \
scenario:        └── c3 (edit cyber-risk) ── c4 ──►
```

Merging the scenario back into `main`:
- `market-risk` changed only on `main` → take main's version (auto)
- `cyber-risk` changed only on the scenario → take scenario's version (auto)
- `hardware` unchanged on both → no action

Result: clean merge, no conflicts.

**Conflict case — same node edited on both sides:**

If both `main` and the scenario edited `cyber-risk`, Irmin detects a conflict
at that path. The system surfaces this as a `MergeConflict` error with
three-way diff information (base value from the LCA, main's value, scenario's
value), requiring user intervention.

The `merge_with_branch` GraphQL mutation handles this internally by finding
the lowest common ancestor (LCA) commit and performing a three-way diff per
path. The `lcas(commit:)` query on `Branch` enables pre-computing the LCA
for merge previews before the user commits to the merge.
