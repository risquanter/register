# PLAN — Aggregate consistency: guarded saves and workspace-level rules

Written 2026-11-01. **Status: design phase.** This plan records the gap, the
facts it rests on, and the decisions a design has to settle. It carries no
signatures and authorizes no source edit. The implementation sections are
written once the decisions in §5 are ruled.

## 1. Goal

Every rule the domain states about a tree or a workspace holds at the moment the
write that could break it lands, and not only at the moment it was checked.

## 2. Vocabulary used here

An **aggregate** is a group of domain objects loaded, changed and saved as one
unit. It is the boundary inside which a rule can be guaranteed. Domain-driven
design (DDD), the method the term comes from, guarantees a rule inside an
aggregate by three steps: load the aggregate, apply the change, and save only if
the stored version is still the one that was loaded. That last step is
**optimistic concurrency**: no lock is held, and a save that finds a newer
version is refused.

A rule that spans several aggregates cannot be guaranteed by any one of them.
DDD offers three answers: make the set an aggregate of its own, let the store
enforce the rule, or accept that the rule is checked after the fact.

**CQRS**, command query responsibility segregation, keeps the model used for
changes apart from the models used for reads. In DDD terms the aggregates are
the change model, and each read model is a projection built from what the
aggregates saved. CQRS does not by itself guarantee any rule. What it changes
is how small an aggregate may be: an aggregate holds only what its rules need,
because no screen reads it directly.

The glossary (`docs/user/TERMINOLOGY.md`) has no entry for aggregate, optimistic
concurrency or CQRS.

## 3. Facts, read from the code on 2026-11-01

1. **A tree save has no version check.** `RiskTreeRepositoryIrmin.writeTree`
   calls `irmin.setTree(base, entries, message, branch)`, and Irmin's `set_tree`
   takes no expected value or head. No tree endpoint carries `If-Match`. Two
   concurrent updates of one tree therefore end with the second overwriting the
   first. The builder's lock while a save is in flight covers one browser tab
   only.
2. **The tree-name rule is checked, then written, with nothing between.**
   `RiskTreeServiceLive.ensureUniqueTree` reads every tree on the branch through
   `collectAllTrees` and compares names. The write follows as a separate call.
   Two creates racing on one name can both pass.
3. **Tree names live in Irmin, per branch.** A tree's name is in its `meta`
   value at `risk-trees/{treeId}/meta` on each branch. A scenario can rename a
   tree, so uniqueness is per branch, which is how `ensureUniqueTree` checks it.
4. **Workspace membership lives in Postgres, and is already guarded.**
   `workspace_trees(workspace_id, tree_id)` lists a workspace's trees.
   `WorkspaceStorePostgres.addTree` takes `SELECT 1 FROM workspaces WHERE id = …
   FOR UPDATE`, reads the workspace's trees, and checks capacity in the same
   transaction. That is a workspace-level rule enforced by the store, and the one
   existing precedent for treating the workspace as a unit.
5. **Postgres and Irmin share no transaction.** A write to one and a write to the
   other can each succeed or fail alone.
6. **The main branch is one branch for every workspace.** `BranchRef.Main` is
   `BranchRef("main")`, and each workspace's trees sit under its own path prefix.
   A scenario branch belongs to one workspace, because its name embeds the
   workspace identifier (ADR-036). A guard on main's head would therefore
   serialise the writes of all workspaces.
7. **Irmin offers three guarded primitives.** The schema in
   `dev/irmin-schema.graphql` lists `test_and_set_branch(set, test, branch)`,
   which `IrminClientLive.moveBranchTo` already uses for the merge's publish. It
   also lists `test_and_set(set, test, path, branch)` and
   `merge_tree(old, value, path, branch)`, which the code does not use. Whether
   `test_and_set` can guard a whole tree's subtree, or only one value, has not
   been checked against Irmin's source.
8. **ADR-004a §2 makes ZIO the only writer to Irmin.** Races are therefore
   between fibers of the server process, and between server replicas if more
   than one runs.

## 4. The rules in scope

| Rule | Spans | Guarded today |
|---|---|---|
| A tree's own rules: `RiskTree.fromNodes`, and the stored-node rule of `PLAN-ERROR-WIRE-BOUNDARY.md` §18 | one tree | checked on the loaded tree; the save is unguarded (fact 1) |
| No two trees on a branch share a name | the workspace's trees on one branch | no (fact 2) |
| A workspace holds at most `REGISTER_WORKSPACE_MAX_TREES` trees | the workspace | yes, by a row lock in Postgres (fact 4) |

## 5. Decisions the design must settle

1. **`AC-D-1` — what version a tree save is checked against.**
   - *The version the server loaded in the same request.* It closes the race
     between two requests. It does not catch a user saving over an edit made in
     another tab after their own load.
   - *A version the browser sends back with `If-Match`.* It closes both. It
     changes the update endpoint's request, and the browser has to hold the
     version it loaded.
   - The two compose: the browser's version is checked, and the server's load is
     the same version.
2. **`AC-D-2` — how the tree-name rule is guarded.**
   - *A workspace aggregate.* The workspace owns the set of its tree names per
     branch. A create, rename or delete changes that aggregate and saves it under
     optimistic concurrency. Fact 4 is the precedent for its membership part.
   - *The store enforces it.* For example a unique constraint on
     (workspace, branch, name). Names live in Irmin (fact 3), so this means a
     second copy of each name in Postgres, kept in step across two stores that
     share no transaction (fact 5).
   - *Checked after the fact.* A duplicate is detected and reported, not
     prevented.
3. **`AC-D-3` — where a workspace aggregate's state lives and how its save is
   guarded**, if `AC-D-2` chooses it.
   - *In Postgres*, beside `workspace_trees`, under the same row lock as
     `addTree`. The tree itself is still written to Irmin, so the two writes need
     an order and a recovery for the second failing (fact 5).
   - *In Irmin*, as one value per workspace and branch, guarded with
     `test_and_set` (fact 7). The aggregate and the trees are then in one store,
     but on main every workspace's guard sits on the same branch (fact 6).
4. **`AC-D-4` — how fine a guard on main is.** A head check on main serialises
   every workspace (fact 6). A per-path check with `test_and_set` would not, if
   it can guard what a save writes (fact 7, unverified). Moving each workspace's
   main to its own branch would change ADR-004a's layout.
5. **`AC-D-5` — whether CQRS is adopted, and for what.** It is orthogonal to the
   gap: it guarantees nothing by itself. What it would contribute is a small
   workspace aggregate, holding identifiers and names only, with the tree list
   and the history read from projections. The code already has read paths of
   that shape: the simulation cache (ADR-014), the server-sent events of
   ADR-004a §3, and the history read of `TreeHistoryService`. Adopting it means
   naming those as projections and stating what rebuilds each.

No decision is ruled yet.

## 6. Records that bear on the design

ADR-004a (storage layout, single writer, one commit per user action), ADR-007
(branches), ADR-017 (whole-tree submission and identity), ADR-021 (the
workspace and its store), ADR-001 (validation placement), ADR-010 (a refused
save as a typed failure), ADR-032 (which hash identifies a version), ADR-036
(identifiers in branch names), ADR-039 (store reads refined on arrival) and
ADR-014 (the cache as a read model). Each is reviewed in full when the design is
written.

## 7. Terminology impact

To be stated with the design. `aggregate` would be a new glossary entry if the
workspace aggregate is adopted.

## 8. Verification

Written with the design. It must include a concurrency test per guarded rule:
two writes racing on one tree, and two creates racing on one name, each ending
with exactly one success.
