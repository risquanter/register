# PLAN-BRANCH-HISTORY

Plan code `BH`. Decisions in this document are labelled `BH-D-<n>`.

**Status.** Design. Opened on 2026-11-02 from the working session that
implemented `PLAN-ERROR-WIRE-BOUNDARY.md` §17 and §18. The design goes as far as
that session's findings and rulings reach. It is not yet implementation-grade:
§9 lists what the Plan Quality Gate still needs. No code may be written from
this document until that is closed and the plan is approved.

Anything else found about tree history while `PLAN-ERROR-WIRE-BOUNDARY.md` is
implemented is recorded here, not in that plan (`BH-D-1`).

---

## 1. What this plan is for

A tree's history is the row of stops on the history slider in the Analyze tab.
Each stop is one entry the server returns, and picking a stop shows the tree as
it was at that commit. Today the history of a branch that took part in a merge
is wrong in two ways.

- **Main's history mixes two branches.** After a scenario is merged, main's
  history shows main's own saves and the scenario's saves together, ordered by
  time. A user looking at main expects main's own line, with the fork and the
  merge marked on it.
- **Most merges leave no merge event.** A tree's history shows a "Merge" stop
  only when both branches saved that tree. In every other merge, the merge
  leaves no stop at all.

The plan gives each branch a history that follows its own line, and records the
fork and merge events on that line.

## 2. The vocabulary this plan uses

The glossary (`docs/user/TERMINOLOGY.md`) has no entries for branches, scenarios,
commits or history. The words below are used in the sense ADR-004a and ADR-007
give them.

- **Branch.** Main, or a scenario. In Irmin, a branch is a pointer to a head
  commit.
- **Commit.** One persisted state of the whole store. It carries a date, an
  author and a message.
- **Save.** One user action that writes a tree. A save produces one commit
  (ADR-004a §2).
- **Fork.** Creating a scenario at a commit of another branch.
- **Merge.** Bringing a scenario's changes into main.
- **The tree's `meta` contents.** The value at `risk-trees/{treeId}/meta`,
  decoded as `TreeMetadata`. Every save rewrites it with a new `updatedAt`.
- **A branch's line.** The commits a branch was moved through by its own saves,
  its fork and its merges. The commits that only reached it through a merge are
  not on its line.

## 3. What the code does today

Each fact below was read from the code or from Irmin 3.11's source, in the
builder image `local/irmin-builder:3.11-p1`.

### 3.1 The browser

`HistorySlider` (`modules/app/src/main/scala/app/components/HistorySlider.scala`)
draws one row of buttons, one per entry, in the order the server returns them.
The newest button means the live head. Every other button pins that entry's
commit hash.

`AnalyzeView` has one slider for the baseline and one per compare slot. Each
slider loads `getTreeHistory(treeId, branch)` through
`TreeHistoryState.loadHistory`, which never passes `n`. So `n` takes the
endpoint's default of 50, declared in `WorkspaceTreeEndpoints` as
`query[Int]("n").default(50)`. The number 50 was chosen by the project.

An entry is `TreeHistoryEntry(commitHash, operation, at)`. It carries no parents
and no branch.

A pinned read runs at `Revision.At(commit)`. `RiskTreeRepositoryIrmin` uses the
commit directly and does not check which branch it belongs to. So a stop from
any line can already be selected and shown.

### 3.2 The history read

`TreeHistoryServiceLive.history`
(`modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala`)
calls Irmin's `last_modified` on the tree's `meta` path. It asks for
`requested(limit)` commits, which is `2 × limit`. It removes duplicate hashes,
drops each merge's preparation commit, and keeps the newest `limit` entries,
oldest first.

`last_modified` (`irmin/store.ml`) keeps a heap of commits ordered by commit date,
newest first. For each commit it takes off the heap, it pushes **every** parent.
It lists a commit when that commit's contents at the path differ from every
parent's, compared byte for byte:

```ocaml
| Some x, Some y -> not (equal_contents x y)
```

It stops at `n` listed commits, and it returns them oldest first. Three
consequences follow.

1. After a merge, it walks both branches at once. That is why main's history
   mixes the two branches.
2. A commit that both parents of a merge commit can reach is listed twice, and
   both copies count toward `n`. So `2 × limit` does not guarantee `limit`
   entries.
3. A commit that changes nothing at the path is never listed, whatever its
   message says.

Irmin's GraphQL server has no query that lists a branch's last `n` commits. The
branch fields are `name`, `head`, `tree`, `last_modified` and `lcas`
(`irmin-graphql/server.ml`). `Store.history` exists in Irmin's OCaml API but is
not exposed over GraphQL.

### 3.3 What a commit records

`CommitMessage.render`
(`modules/server/src/main/scala/com/risquanter/register/infra/irmin/CommitMessage.scala`)
writes the workspace, the tree and the kind of write for a save:

```scala
case TreeWritten(wsId, treeId, kind) =>
  List(WorkspaceTag, wsId.value, TreeTag, treeId.value, kindSpelling(kind))
```

No message names a branch. A save on main and a save on a scenario carry the
same message. Only the two merge messages, `merge-prepare:<scenario>` and
`merge-scenario:<scenario>`, name a scenario.

### 3.4 The fork

`ScenarioServiceLive.create` calls `irmin.createBranchAt(branch, sourceHead)`.
That writes no commit. The store records no fork event.

The source can be main, another scenario (`ScenarioSource.ForkOf`) or a commit in
history (`ScenarioSource.AtCommit`). So the lowest common ancestor of main and
the scenario is not always where the scenario started.

### 3.5 The merge

`ScenarioMergeService.mergeOnStaging` writes three commits on a staging branch
for each tree whose `meta` both branches changed:

1. the copy of the scenario's `meta`;
2. Irmin's merge commit;
3. the merged `meta`.

Main then moves to the last commit in one compare-and-set. The merge does not
delete the scenario branch, so a scenario can be merged more than once.

Irmin's `Commit.merge_commit` (`irmin/commit.ml`) builds a merge commit with
`let parents = [ k1; k2 ]`. `k1` is the branch being merged in, which is the
scenario. `k2` is the target's head, which is main's.

Read from Irmin's source, two cases leave no "Merge" entry in a tree's history.
Neither case has a test.

- **Main has not moved since the fork.** `merge_commit` returns the scenario's
  head (`if equal_opt_keys old (Some k2) then Merge.ok k1`). No merge commit is
  created, and main moves to the scenario's head.
- **Main moved, but only the scenario saved this tree.** The merge commit's
  `meta` for this tree equals its scenario parent, so `last_modified` does not
  list it. No final write is made for this tree.

This contradicts ADR-004a §2, which says: "For a merge it shows only the last
commit."

### 3.6 `updatedAt`

`updatedAt` is written by `RiskTreeRepositoryIrmin` on create, update and
revert, and by the merge. Nothing reads it for display. `mergeMetadata` excludes
it from the comparison.

### 3.7 What a commit can carry

Irmin types a message as a plain `string` (`irmin/info.ml`:
`type message = string [@@deriving irmin]`), with no length limit. I found no
body-size limit in `graphql-cohttp`'s HTTP handling. `IrminQueries` passes the
message through `escapeGraphQLString`, the same path as the much larger node
JSON in `value`. The practical bound is cost: every listed commit returns its
message, so the size of a history response is the number of listed commits
times the message size.

Irmin records a commit that changes nothing when `allow_empty` is true.
`Store.update` skips a write only `if same_tree s.tree new_tree && (not
allow_empty) && s.head <> None`. The GraphQL `info` input accepts `allow_empty`.
`IrminInfoInput` in register does not pass it today.

## 4. Decisions ruled

1. **`BH-D-1` — RULED 2026-11-02.** Tree history gets this plan of its own.
   `PLAN-ERROR-WIRE-BOUNDARY.md` does not change the history behaviour it
   implemented, including the `2 × limit` request. Anything found about history
   while that plan is implemented is recorded here.
2. **`BH-D-2` — RULED 2026-11-02.** `limit` is the number of commits the history
   read asks Irmin for, counted before any filtering. The doubling goes, and no
   second request is made. A history can therefore hold fewer than `limit`
   entries.
3. **`BH-D-3` — RULED 2026-11-02.** Every commit message records the branch
   whose write produced it, in a structured text format. The other events are
   recorded the same way: the fork and the merge.
4. **`BH-D-4` — RULED 2026-11-02.** A fork writes one marker commit on the new
   scenario as its first commit. The marker names the scenario, the source
   branch, and the source commit hash. It rewrites the `meta` contents of every
   tree in the workspace with `updatedAt` set to the fork time, so
   `last_modified` lists it in every tree's history.
5. **`BH-D-5` — RULED 2026-11-02.** A merge always writes a final commit of its
   own on main's line. That commit rewrites the `meta` contents of every tree
   the merge changed, and its message names the scenario, the branch merged into,
   and the scenario commit hash that was merged. This covers the fast-forward
   case and the case where only the scenario saved the tree.
6. **`BH-D-6` — RULED 2026-11-02.** `updatedAt` means the last event that
   touched the tree: a save, a fork or a merge. It no longer means the last
   edit.
7. **`BH-D-7` — RULED 2026-11-02.** The history read stays one `last_modified`
   on the tree's `meta`, with `n = limit`. The branch recorded in each message
   decides which listed commits a view keeps:
   - **Main's history** keeps main's saves, the fork markers and the merge
     markers. A merged scenario's saves are dropped.
   - **A scenario's history** keeps main's commits up to the fork marker, then
     the scenario's commits, then main's commits after a merge marker that names
     one of this scenario's commits.
   - **A scenario merged more than once** has one merge marker for each merge.
     Each one names the scenario commit it took, and the view shows each one.

## 5. Consequences that follow from the rulings

- **The fork marker changes every tree's `meta` on the scenario.** Each tree's
  `meta` then differs from the fork point, including trees nobody edited there.
  The merge scan compares those `meta` values field by field without
  `updatedAt`, so they raise no conflict, but the scan does more work.
- **A merge takes the scenario's `meta` for a tree only the scenario touched.**
  Its fields equal the fork's, so no value a user sees changes.
- **The preparation commit stays.** It still carries the merge's own message
  and is still dropped by the history read.
- **The duplicate listing stays harmless.** The read keeps one entry per hash,
  as today.
- **Deleting a scenario still writes no commit.** It only removes the branch
  pointer. An unmerged scenario that is deleted is unreachable, and its line is
  gone from every history. That is the behaviour today.

## 6. Open decisions

1. **The message format.** The branch and the events go into the message in a
   structured format (`BH-D-3`). Two formats fit.
   - A JSON envelope with a derived codec, keyed by a `type` discriminator. Its
     fields follow CloudEvents, the Cloud Native Computing Foundation's
     specification for describing events: `type`, `source` (the branch),
     `subject` (the tree), `time`, a schema version, and the event's own data.
     Old readers ignore unknown fields.
   - Git trailers: `Key: value` lines after a first line of text. This is Git's
     own convention for structured commit messages, and it is built for people
     reading logs.

   My recommendation is the JSON envelope. Nobody reads these messages directly,
   and the codec then derives from the type (ADR-037), with
   `parse(render(m)) == Some(m)` as its law.
2. **Messages already in the store.** Commits written before this plan use the
   colon-separated format and name no branch. If a store that matters holds such
   commits, the current `CommitMessage.parse` becomes the reader for that format
   and maps those saves to "branch unknown". The view then needs a rule for an
   unknown branch. If no such store exists, the old format can be dropped. This
   needs a fact only the user holds.
3. **How the fork marker writes every tree's `meta` in one commit.** The GraphQL
   server has `set_tree`, `update_tree` and `set_all`. Which one writes several
   `meta` values in one commit has not been checked.
4. **A scenario forked from another scenario, or from a commit in history.** The
   marker names its source branch and commit. The scenario view's "before the
   fork" part then follows that source's line, which may itself be a scenario.
   The rule for how far back to follow has not been settled.
5. **The fork stop on the wire.** `HistoryOperation` has the cases `TreeWrite`,
   `Merge` and `Other`. Showing a fork stop needs a `Fork` case, which changes the
   history response's wire shape (ADR-038).
6. **A graph view in the slider.** Showing both lines at once needs each entry to
   carry its parents or its line, which is a wire change, and a component in
   place of the one-row slider. The user asked whether it is possible, and it has
   not been ruled in or out.

## 7. ADR alignment

- **ADR-004a §2.** It says a save produces one commit and a merge produces three.
  With `BH-D-4`, a fork produces one commit. With `BH-D-5`, a merge always
  produces its own last commit. The sentence "For a merge it shows only the last
  commit" becomes true in every case, where today it is false in two. The record
  needs an amendment, presented with the plan.
- **ADR-007.** It describes scenario creation as creating a branch. With
  `BH-D-4`, creation also writes a commit. Amendment needed.
- **ADR-036.** The message already carries the `WorkspaceId`, server-side only.
  The branch is recorded as `BranchChoice` (main or a scenario name), never as
  `BranchRef`, because `BranchRef` embeds the `WorkspaceId`. No message reaches
  the browser. Compliant.
- **ADR-037.** The event kinds are a closed set with a derived codec. Compliant.
- **ADR-038.** Open decision 5 changes a response's wire shape.
- **ADR-039.** A message read back from the store is refined when it is parsed,
  as `CommitMessage.parse` does today. Compliant.
- **ADR-032.** `meta` is not part of a node's hash projection, so the extra
  `meta` writes do not change any cache key. This premise has not been checked
  against the record.

## 8. Terminology impact

None. The glossary has no entries for branches, scenarios, commits or history
(§2).

## 9. What the Plan Quality Gate still needs

1. **Exact signatures.** The new `CommitMessage` cases, the fork marker write in
   `ScenarioServiceLive`, the merge's final write, the `allow_empty` field on the
   write path, and the view filter in `TreeHistoryServiceLive`. Each needs the
   family search from `.claude/rules/scala-algebraic-design.md`.
2. **File inventory.** The user creates `PLAN-BRANCH-HISTORY-INVENTORY.md` beside
   this document. The files the design reaches are `CommitMessage.scala`,
   `TreeHistoryService.scala`, `ScenarioServiceLive.scala`,
   `ScenarioMergeService.scala`, `IrminClient.scala`, `IrminClientLive.scala`,
   `IrminQueries.scala`, the `IrminInfoInput` model, `TreeHistoryResponse.scala`
   if open decision 5 adds `Fork`, and their tests.
3. **ADR alignment.** The two amendments in §7, written out.
4. **Open decisions.** §6, all six.
5. **Verification plan.** §10 lists the tests. The commands are the full suite:
   `sbt app/test`, `sbt 'commonJVM/test; server/test'`, the `register_it_`
   cleanup, then `sbt serverIt/test`.

## 10. Tests the design calls for

- `CommitMessageSpec`: the law `parse(render(m)) == Some(m)` for every case, with
  the branch, the fork marker and the merge marker.
- `TreeHistoryServiceSpec`: Irmin is asked for `limit` commits. A listing with a
  duplicate and a preparation commit returns fewer than `limit` entries.
- `TreeHistoryServiceSpec`: main's view of a listing that mixes main's and a
  scenario's saves keeps main's saves, the fork marker and the merge marker.
- `TreeHistoryServiceSpec`: a scenario's view keeps main up to the fork, the
  scenario's saves, and main after a merge of this scenario. A scenario merged
  twice shows both merges.
- `ScenarioMergeServiceItSpec`: a merge where main has not moved since the fork
  shows one "Merge" entry in main's history.
- `ScenarioMergeServiceItSpec`: a merge where only the scenario saved the tree
  shows one "Merge" entry in main's history.
- `ScenarioServiceLiveSpec` or an integration test: a fork shows one fork stop in
  every tree's history on the scenario.
