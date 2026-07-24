# NOTE — Irmin GraphQL `merge_with_commit`: state-dependent ancestry loss

**Status:** engineering note, not an ADR. Recorded 2026-07-24 so that any
future evaluation of `merge_with_commit` (an Irmin bump, a Phase E history
feature, any merge-by-commit use case) starts from what we already know.
This project's production code does **not** call `merge_with_commit`; the
scenario merge uses `merge_with_branch` (locally patched — see
`containers/builders/patches/` and ADR-020 §11/§12).

## The behaviour

`merge_with_commit(branch, from: <commit hash>, info)` merges a specific
commit into a branch. In the **fast-forward case** — the target branch has
not moved since the `from` commit's line forked, so the correct outcomes are
either "head := from-commit" (fast-forward) or a two-parent merge commit —
we observed **two different results on the same Irmin version** (3.11.0,
`local/irmin-prod` image family):

1. **On a store with prior history** (live probe, 2026-07-24): a **new
   commit** whose content incorporates the `from` commit's changes but whose
   parent list contains only the target's old head. The `from` commit is
   NOT recorded as a parent — its content arrives with no ancestry linking
   it, and the source branch remains historically diverged from the target.
2. **On a fresh store** (`IrminMergeSemanticsSpec` run, same day): a proper
   fast-forward — the returned head IS the `from` commit, ancestry intact.

The determinant was not identified. Known differences between the two
environments, any of which may or may not matter: the probe store carried
prior commits, a previously *failed* merge attempt, and branches created
via the `revert` mutation (head-set), while the spec uses a fresh store and
CAS branch creation (`test_and_set_branch`). Isolating the trigger means
digging through Irmin's internal merge machinery for a mutation we do not
use; abandoned deliberately.

## Why it matters (if the mutation is ever used)

Outcome 1 silently rewrites history: merged data appears with no record of
where it came from. Anything that reasons over ancestry breaks — `lca`-based
merge-base computation, "is X merged into Y" checks, a history/audit view.
It is likely an upstream bug or at best unspecified behaviour; **no upstream
issue exists** for it (checked 2026-07-24; irmin 3.11.0 is the latest
release and `src/irmin-graphql/server.ml` is identical on upstream `main`).

What **is** consistent about `merge_with_commit` across every observation:
- A conflicting merge fails with a GraphQL error and leaves the target head
  untouched (pinned in-tree: `IrminMergeSemanticsSpec`, "comparison:
  unpatched-style merge_with_commit surfaces the same conflict as an error").
- A merge of genuinely diverged branches produces a correct two-parent
  merge commit.

## How to test for it

`IrminMergeSemanticsSpec` contains a ready-made harness: the private
`mergeWithCommitRaw(from, message)` helper speaks raw GraphQL against the
spec's scoped Irmin container. The detection recipe:

1. Seed the target branch, fork a branch at its head, commit on the fork
   only (target unmoved).
2. Call `merge_with_commit(branch: "main", from: <fork head>)`.
3. Inspect the returned commit: **fast-forward** = its hash equals the fork
   head; **the anomaly** = a new hash whose `parents` do not contain the
   fork head.

Assertions of the form `mwcCommit.hash == forkHead.hash` are deliberately
NOT in the suite — they are state-dependent and would flake (the reason the
originally planned pin was removed; decision 2026-07-24, option A). To
re-probe after an Irmin bump, run the recipe on both a fresh store and a
store with history (prior branches, at least one failed merge) before
concluding anything.

## Raw probe transcript (unpatched 3.11, throwaway container, 2026-07-24)

Store state: main at `fe4d…` (after a refused conflicting merge attempt on
another branch); `scenario2` created via `revert` at `fe4d…`, one commit
adding path `other`. Then:

```
mutation { merge_with_commit(branch: "main", from: "<scenario2 head>",
           info: {message: "merge"}) { hash parents { hash } } }
→ { "hash": "837b3a64…", "parents": ["fe4d8d9f…"] }   # scenario2 head absent
```

Same-shape sequence on a fresh store fast-forwards instead. Note: the
GraphQL response serialises `parents` as plain hash strings even when
queried as a sub-selection.
