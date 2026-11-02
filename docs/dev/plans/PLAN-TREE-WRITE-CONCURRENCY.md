# PLAN — Tree write concurrency and seed allocation

A compare-and-set save with a client-held version token, one seed counter per
workspace, and a seed entity id per tree.

**Status: DRAFT — TWC-D-3, TWC-D-4, TWC-D-6, TWC-D-7 and TWC-D-11 to TWC-D-16
are unruled. This document confers no G3 plan coverage and authorizes no source
edit.** It becomes implementation-grade once those decisions are ruled, §3 is
presented as the Signature Echo, and the user has written
`PLAN-TREE-WRITE-CONCURRENCY-INVENTORY.md`.

**Origin.** Two findings. The lost update is complex-tier review finding R1,
recorded in `PLAN-RISKTRANSFORM.md` §7.6.1, and was split into this plan because
it predates mitigations. The seed collision is finding 1 of the complex-tier
review of `PLAN-ERROR-WIRE-BOUNDARY.md` §17 (coverage entry in
`docs/dev/reviews/REVIEW-main.md`), discussed on 2026-11-02. The two were merged
into one plan on 2026-11-03 because the seed work writes through the
compare-and-set save and both add a server-only branch kind.

**Companion:** `PLAN-TREE-WRITE-CONCURRENCY-APPENDIX-WRITE-SHAPE.md` — the write
shape analysis and the four options behind §2.0.

**Inventory:** `PLAN-TREE-WRITE-CONCURRENCY-INVENTORY.md`, beside this file. It
does not exist yet. The user creates it.

**Checked against the code on 2026-11-02.** Every file, type and line this plan
names was re-read on that date. The error path it relies on has landed:
`PLAN-ERROR-WIRE-BOUNDARY.md` §17 and §18 are implemented, and its remaining
phases (3b, 4 to 7) name neither `VersionConflict` nor `ConflictKind.StaleVersion`.

---

## 1. Problem

### 1.1 Terms

A **save** is a tree write: `RiskTreeRepositoryIrmin.create`, `update`, `delete`
or `revert`. Each is one `set_tree` commit replacing the tree's whole subtree.

A **seed** is a leaf's `seedVarId`: an integer in `[1, 50000000)` that selects
the leaf's two random-number streams. ADR-003 §1 derives them as `2 * seedVarId`
for occurrence and `2 * seedVarId + 1` for loss.

A **seed entity id** is `seedEntityId`: an integer in `[1, 100000000)` that
ADR-003 §1 passes to HDR as the entity coordinate. Today it belongs to the
workspace, so every tree in a workspace draws with the same value.

The **high-water mark** is `seedVarHighWater`, stored in each tree's `meta`
value. It is the largest seed the tree has ever handed out.

HDR (Hash-based Deterministic Random) is the generator in the `hdr-rng` library.
It maps a trial number and four integer coordinates to a uniform draw. ADR-003's
context states that its identifier axes are bounded at 10⁸.

### 1.2 The lost update

`RiskTreeServiceLive.update` reads the tree, computes a complete replacement
from the request, and calls `repo.update(wsId, id, _ => riskTree, branch)`
(`RiskTreeServiceLive.scala:382`). The repository re-reads the tree and then
discards that read, because the function it is handed is a constant rather than
a transform of its argument. Nothing in the path compares versions. Two writes to
the same tree therefore resolve last-writer-wins, and the loser's edit is
destroyed with no error to either party.

ADR-017 makes this sharper than it would otherwise be. The tree API is a
**whole-tree PUT**: every write is a full replacement, and omission means
delete. There is no partial update that could merge cleanly, so a writer
holding stale state necessarily overwrites everything it did not know about.

**Two distinct windows.** The fix depends on which window is being closed, and
they are not the same size.

**W1 — in-request overlap.** Clients A and B both have requests in flight. Both
services read the tree at commit C0 before either writes. Whichever repository
write lands second overwrites the first. Duration: the length of one request,
typically well under a second.

**W2 — stale client state.** Client B loaded the tree in its browser at C0.
Client A edits and commits C1. Client B, still holding the C0 view, submits a
PUT whose body enumerates every node at its C0 values. B's *server-side* read is
perfectly fresh — it reads C1 — but that does not help, because the values being
written come from the request body, not from the server's read. A's edit is
overwritten. Duration: as long as a browser tab stays open, so minutes to hours.

**W2 strictly contains the damage of W1 and is orders of magnitude wider.** A
purely server-internal check — compare the head at write time against the head
the service read at the start of the same request — closes W1 only. In the W2
scenario both values are C1 and the check passes, so the edit is still lost. A
client-supplied expected version closes both: the client sends the commit it
read, and the server refuses to write if the branch has moved since.

**Derivation:** optimistic concurrency control works by carrying a version token
across the full read-modify-write cycle and validating it at the write. The
cycle here begins when the *client* reads, not when the server does, because the
data being written was composed from the client's read. A token that enters the
cycle only at the server's read is validating a shorter cycle than the one that
actually exists. So the token must be client-supplied.

### 1.3 Two branches hand out the same seed

A save assigns each new leaf without a provided seed the next value above the
tree's high-water mark (`SeedVarIdAssigner.assign`). The mark is read from the
`meta` value on the branch being saved. A scenario is a fork of main, so after the
fork each branch holds its own copy of `meta`, and each copy counts upward on its
own.

Example. A tree's high-water mark is 5 when scenario S is forked. Main adds the
leaf "Fire" and gives it seed 6. S adds the leaf "Flood" and gives it seed 6. Both
copies of `meta` now hold 6. The merge's field rule sees two equal values and
reports no conflict, so the preview answers `Clean`. The guard after the merge then
finds seed 6 twice and refuses the merge with `InvariantBroken`. A seed never
changes once assigned (ADR-003 §2), so the user cannot resolve the refusal from the
dialog.

The collision is created when the seed is handed out. A merge can only detect it
or renumber it, so the fix belongs to allocation.

### 1.4 A tree cannot be recreated exactly in another existing workspace

Reproduction through the API (application programming interface, the HTTP
endpoints in `modules/common`) works by reading a tree, keeping its seeds, and
creating it again with every seed provided. `RiskLeafDefinitionRequest.seedVarId`
accepts them on create and on new leaves in an update. The get-tree endpoint
returns them.

The figures also depend on the seed entity id. `WorkspaceStore.create` accepts a
provided `seedEntityId`, and the bootstrap endpoint takes it as the query
parameter `seedEntityId`. A second workspace can therefore reproduce a first one's
figures only when it is created with the first one's value, and the store refuses a
value that another workspace in the same deployment holds (`seedEntityIdInUse`).
An existing workspace cannot take a copied tree at all, because all its trees draw
with its own value.

### 1.5 What already exists

Most of the machinery for the lost update is present and unused on this path:

| Piece | Where | State |
|---|---|---|
| `VersionConflict(treeId: TreeId, expected: CommitHash, actual: CommitHash)` | `AppError.scala:286` | Exists, `extends SimError`, no production constructor |
| HTTP 409 mapping | `WireError.fromAppError` projects it to `Conflict(ConflictKind.StaleVersion(), t)` (`WireError.scala:176`) | Exists |
| SPA (single-page application, the browser code in `modules/app`) message | `ErrorMessages.scala:40` renders `ConflictKind.StaleVersion()` as "This was edited elsewhere since you loaded it. Reload to see the current version." `GlobalError.fromWireError` returns `None` for every `WireError.Conflict`, so a conflict is shown where the request was made and never as a global error | Exists |
| `CommitHash` as a wire value | `?at=` query param, `RevertTreeRequest.toCommit`, `TreeHistoryEntry.commitHash`, `ScenarioSummaryResponse.head` | Exists |
| The read that yields the version | `RiskTreeService.getById` returns `Option[(RiskTree, CommitHash)]` | Exists, and `WorkspaceTreeController.getTreeStructure` drops the commit |
| Branch-pointer compare-and-set | `IrminClient.createBranchAt(StoreBranch, CommitHash)`, `deleteBranch(StoreBranch, CommitHash)`, `moveBranchTo(BranchRef, expectedHead, to)` | Exists |
| A client-supplied version token on a write | `deleteScenarioEndpoint`'s `.in(header[CommitHash]("If-Match"))` (`ScenarioEndpoints.scala:67`), carrying the head read from `ScenarioSummaryResponse.head`; `ScenarioState.scala:132` echoes it | Exists, for scenario deletion only |
| Write-to-a-staging-branch then publish under one compare-and-set | `ScenarioMergeService`, with `MergeStagingRef` and the `set` overload that takes it (`IrminClient.scala:49`) | Exists, for the merge only |

**What does not exist:** `IrminQueries.setTree` takes no precondition, and
`IrminClient.setTree` is declared over `BranchRef` alone. Irmin's mutation
surface as used here is `set_value`, `set_tree`, `remove`, `merge_with_branch`,
`revert`, `test_and_set_branch`. There is no conditional tree write, and no
per-workspace seed counter.

Irmin 3.11's own behaviour, read from `irmin` `store.ml` 795-838 and 907-1001 and
`irmin-graphql` `server.ml` 603-720 in the `local/irmin-builder:3.11-p1` image:
`set_tree` takes a snapshot of the head, replaces the subtree at its path, and
publishes with a compare-and-set on the head. When the head has moved it retries,
up to 13 times by default, writing the same subtree on top of the new head. A
concurrent write to another path therefore survives, and a concurrent write to the
same tree is replaced. The GraphQL surface also exposes `test_and_set` and
`test_set_and_get`, which write one value only if the value at the path is still
the one given, retrying internally while other paths change. Irmin's
`test_and_set_tree`, the same check for a whole subtree, exists in the store and is
not exposed through GraphQL. The `info` argument of every mutation carries
`retries`, `allow_empty` and `parents`; `retries: 0` stops the retry but tests
against the head the mutation itself snapshots, not against a head the caller
observed, so it closes neither window.

### 1.6 The output-header check, run

TWC-D-5's ruled direction of 2026-10-19 depended on one unverified premise:
that Tapir's sttp client interpreter decodes a declared output header into the
endpoint's output type rather than discarding it. Read on 2026-11-02 from the
`tapir-client_3-1.13.4` and `tapir-sttp-client_3-1.13.4` sources in the coursier
cache (Tapir has no sibling checkout):

```scala
// sttp/tapir/client/ClientOutputParams.scala:25
case EndpointIO.Header(name, codec, _) => codec.decode(meta.headers(name).toList)
```

`EndpointToSttpClient.scala:47-52` applies `clientOutputParams(output, body, meta)`
to every response and returns `DecodeResult[Either[E, O]]`. A declared output
header is therefore part of `O`, and `BackendClient.endpointRequestZIO[I, O]` in
the browser receives it without change. The premise holds, so the 2026-10-19
direction is in force: TWC-D-1 and TWC-D-5 are ruled below.

---

## 2. Decisions

Labels are `TWC-D-n`. TWC-D-1 to TWC-D-7 keep the order they were first written
in, because `PLAN-RISKTRANSFORM.md` §7.6.1 refers to this plan's "Decision 2" by
that number. TWC-D-8 to TWC-D-16 are the seed decisions, in the order the seed
plan listed them before the merge.

### 2.0 Write shape — RULED: Option A now, Option B next

The shape of the tree write is decided ahead of everything below, because it
determines how much of the rest is needed. Options and the supporting analysis
are in `PLAN-TREE-WRITE-CONCURRENCY-APPENDIX-WRITE-SHAPE.md`.

**Ruled:** keep the whole-tree PUT and add a client-supplied version token that
rejects a stale write (appendix Option A). Then move the base-revision branch
from reject to server-side diff-and-merge (appendix Option B), so that concurrent
edits to disjoint parts of a tree reconcile instead of conflicting.

Two properties make this sequencing safe rather than a staged compromise:

- The wire change is the same in both. Option A's client-supplied expected
  revision **is** Option B's base revision; only the server's behaviour when it
  differs from head changes. Shipping A does not produce work that B discards.
- Correctness is unaffected by the move. `RiskTree.fromNodes` is a pure predicate
  over a candidate node set, so validating a server-computed merge result gives
  the identical guarantee as validating a client-submitted one — the same
  function on the same type. See the appendix, §2.

Option B's design is not attempted yet: the diff and merge functions, conflict
granularity, and what the client is shown after a successful merge are all open,
and it needs its own elevation before implementation. Options C (`TreeOp`
operation algebra) and D (Irmin-native branch merge) are not adopted; the ruling
takes no position on Option C's intent history, which is a separate question.

### TWC-D-1 — How the expected version reaches the server — RULED: the `If-Match` header

Ruled 2026-10-19 in direction ("verify the `ETag` route, and if it works with
Tapir, adopt it rather than the body field"), and in force since the check in
§1.6 ran on 2026-11-02.

The PUT declares `.in(header[CommitHash]("If-Match"))`, the same declaration
`deleteScenarioEndpoint` already carries. `RiskTreeUpdateRequest` is unchanged.
The options that were weighed: a body field `expectedRevision: CommitHash`,
which reimplements a conditional request inside the DTO (data transfer object,
the request body's type); and an optional body
field, rejected because an opt-in guard against silent data loss leaves the
default wrong.

### TWC-D-2 — How the precondition is enforced against Irmin — RULED: a compare-and-set through a staging branch

Ruled 2026-11-02: the save uses a test-and-set approach. The user's words: "both
have it as their respective tasks with a 'test and set approach'", said of the
lost update and the seed work before they were merged into this plan.

The save forks a staging branch at the expected head, writes the tree there with
one `set_tree`, and publishes with `test_and_set_branch(target, test =
expectedHead, set = newCommit)`. The compare-and-set fails when the target moved,
and nothing was written to it. This closes W1 and W2 together. The pattern is the
merge's publish (`ScenarioMergeService`, `IrminClient.scala:155-169`), so the
branch lifecycle it needs has been built once already.

The options that were weighed: check-then-act (resolve the head, compare, then
`set_tree` unconditionally), which leaves W1 open because Irmin's own retry
rewrites the subtree on top of a moved head; and an in-process lock per tree,
which closes W1 on one server only and does not close W2.

### TWC-D-3 — Which write paths carry the precondition — OPEN

`update` is the one the finding names. Also candidates: `delete` (deleting a
tree someone else just edited) and `revert`, whose scaladoc in
`RiskTreeRepository.scala:26-27` reads "Reads the tree state at `toCommit` and
writes it forward with a `:revert` message; no precondition", an explicit prior
ruling. `create` writes a path that does not exist yet, and a concurrent write to
another path survives Irmin's retry (§1.5), so `create` needs no precondition.

**Option A — `update` only.** `revert` keeps its ruling; `delete` stays
last-writer-wins.
- What you would see: deleting a tree that someone saved a moment ago succeeds
  and discards their save.

**Option B — `update` and `delete`.** `delete` gains the `If-Match` header; the
delete button sends the `ETag` the tree view loaded.
- Pro: a delete never discards a save the deleter did not see.
- Con: a second endpoint, controller arm and browser call site change.
- What you would see: deleting a tree that someone saved a moment ago answers
  409, and the tree list reloads.

**My recommendation: A**, as the finding's scope, with B as the first
continuation section once A has landed. The `revert` ruling is not reopened
by either option.

### TWC-D-4 — What the SPA does on a 409 — OPEN

`ErrorMessages.StaleVersion` already says "This was edited elsewhere since you
loaded it. Reload to see the current version.", so the minimum, a non-generic
message, exists.

**Option A — show the message; the user reloads.** No browser logic beyond
today's.
- What you would see: the message under the save button, your draft still on
  screen, and a reload that discards it.

**Option B — reload the tree automatically and keep the draft.** The browser
re-reads the structure, takes the new `ETag`, and shows both the fresh tree and
the unsaved draft so the user can resubmit.
- Pro: the draft is not lost.
- Con: the draft was composed against a tree that no longer exists; resubmitting
  it overwrites the other person's edit, which is the very outcome the token
  refuses. The browser would have to show what changed, which is appendix Option
  B's design.
- What you would see: the message, then the tree redrawn, your edits still in
  the form.

**My recommendation: A.** Option B's value is realised only with the
diff-and-merge of appendix Option B, which is a later elevation.

### TWC-D-5 — How the client learns the commit it read — RULED: an `ETag` response header

Ruled with TWC-D-1. `getWorkspaceTreeStructureEndpoint` declares
`.out(header[Option[CommitHash]]("ETag"))`, absent when the body is `None`. The
PUT answers with the same header carrying the new head, so the browser can save
again without a reload. `IronTapirCodecs`' `Codec[String, CommitHash,
CodecFormat.TextPlain]` strips quotes on decode and adds them on encode, and the
same codec serves a header.

This is the first output header in the codebase, so it is declared as a
convention: §3.1 names the two endpoints, and `WorkspaceTreeEndpoints` carries a
scaladoc stating that a tree read answers with its head in `ETag` and a tree
write expects it back in `If-Match`.

The option that was weighed: widening the structure response body to carry the
commit. It puts transport metadata inside a domain payload, which ADR-038
governs, and mixes the body route with the header route the scenario path uses.

### TWC-D-6 — The status of a refused write — OPEN

HTTP answers a failed `If-Match` with `412 Precondition Failed`. The project
answers every conflict with `409`, and `WireError` selects its case by status
alone (`ConflictKind`'s scaladoc: "Every conflict answers `409`, and the browser
selects a wire case by status alone").

**Option A — 409 through `VersionConflict` → `ConflictKind.StaleVersion()`.**
Nothing on the wire changes; the mapping and the message exist.
- Con: a client that speaks HTTP conditional requests expects 412.
- What you would see: the existing message.

**Option B — 412 through a new `WireError` case.** A new status in the `oneOf`,
a new case in `WireError`, its projection arm, its browser classification and
its message.
- Pro: the status HTTP defines for the situation.
- Con: a wire change for a distinction no consumer of this API makes;
  `deleteScenarioEndpoint`'s `If-Match` already answers 409 through
  `ConflictKind.ScenarioHeadStale`, so 412 here would split the convention.
- What you would see: the same message under a different status.

**My recommendation: A.** The scenario path set the convention, and a split
status for the same situation is the half-migrated state the project's own
convention rule warns against.

### TWC-D-7 — Whether the scenario path moves onto `ETag` — OPEN

`ScenarioSummaryResponse.head` carries a commit out in a response body, and the
delete echoes it in `If-Match`. After TWC-D-5 the tree path carries the commit
out in a header. Two conventions for one round trip then exist.

**Option A — move it in this plan.** `listScenarios` is a list, so one `ETag`
cannot carry one head per scenario; the move would need a per-scenario read, or
the `head` field stays. The scenario list has no per-scenario read today.
- Con: a new endpoint or a changed list shape, outside this plan's findings.

**Option B — leave it, and record the difference.** A list response carries each
entry's head in the body because a header cannot; a single-resource read carries
its head in `ETag`. Both are stated in ADR-038's amendment (§5).
- Pro: no scope growth; the rule is one sentence and explains both.
- What you would see: nothing changes on the scenario path.

**My recommendation: B.** The difference is a property of lists against single
resources, not two conventions for one case.

### TWC-D-8 — The seed counter belongs to the workspace — RULED

Ruled 2026-11-02. Seeds are handed out from one counter per workspace. No branch
holds a copy of it, so a fork does not copy it and every branch of every tree in
the workspace draws new seeds from the same sequence.

Consequence for §1.3: main's "Fire" gets 6 and the scenario's "Flood" gets 7, so
the merge has nothing to refuse.

### TWC-D-9 — A save reserves its seeds in one atomic step — RULED

Derived from TWC-D-8 and the existing assignment rule, and explained to the user
on 2026-11-02. A save that needs k new seeds reserves them from the counter in
one atomic step, where k is the number of new leaves without a provided seed. The
step reads the counter value c, assigns the leaves the values above
`max(c, every provided seed in the save)` in sorted-name order, and moves the
counter to the largest value now in use. One atomic step per save, not one per
leaf.

A provided seed raises the counter in that same step, so a later save can never be
handed a seed that a provided leaf already holds.

A save that reserves seeds and then fails leaves a gap in the sequence. A gap is
harmless, because the rule is only that a seed is never handed out twice. A save
refused by TWC-D-2's compare-and-set is one such failure: the order inside a save
is reserve the seeds, build the tree, then write, so the refused save's seeds
become a gap.

### TWC-D-10 — A tree carries its own seed entity id — RULED in direction

Ruled in direction 2026-11-02. Each tree stores a `seedEntityId`. A create request
may provide one. Without it, the tree takes the workspace's `seedEntityId` at the
moment of creation and keeps that value. It never changes afterwards, for the same
reason a seed never changes: changing it changes every figure the tree produces.

Every simulation of the tree draws with the tree's value instead of the
workspace's. This closes §1.4: a tree read from one workspace can be created in
another existing workspace with its seeds and its seed entity id provided, and it
produces the same figures.

### TWC-D-11 — Where the workspace counter is stored — OPEN

**What has to be decided, and why now.** TWC-D-8 says the counter belongs to the
workspace and that no branch copies it. The counter must survive exactly as long
as the trees whose seeds it guards, or a fresh counter could hand out a seed a
stored tree still holds. Every signature in §3.4 that touches the counter depends
on where it lives.

**Option A — a value in Irmin on a branch only the server writes, changed with
Irmin's `test_and_set`.** The counter would live at one path per workspace on a
dedicated branch, so no scenario fork copies it.
- Pro: the counter lives in the same store as the trees, so a restore of the Irmin
  store restores both together and they cannot disagree.
- Pro: it works with either workspace store, in memory or Postgres.
- Pro: the branch kind is the one TWC-D-2's save introduces (§3.2), so the records
  are amended once for both.
- Con: a failed test comes back as a GraphQL error whose text is
  "Test-and-set failed: got …". Telling it apart from other failures without
  matching that text needs a re-read of the counter after any failure: a value
  different from `test` means another save moved it, and the reservation retries.
- What you would see: nothing in the browser. A store dump shows one extra branch
  with one small value per workspace.

**Option B — a field of the workspace in the workspace store.**
`WorkspaceStoreLive` changes it inside its existing `Ref.modify`, which already
hands out `seedEntityId` atomically. `WorkspaceStorePostgres` changes it with one
`UPDATE workspaces SET … RETURNING …`, which Postgres applies atomically per row.
- Pro: atomic primitives with typed results in both backends, and no error text to
  classify.
- Pro: no Irmin change.
- Con: the counter and the trees live in two stores. If the Postgres database is
  restored to a point older than the Irmin store, the counter is lower than seeds
  that trees already hold, and the next save can hand out a repeated seed. The
  per-tree distinctness check would still refuse a repeat inside one tree, and a
  repeat across two branches of one tree would again surface only at the merge.
- Con: a schema migration (`V3__…`) adds the column.
- What you would see: nothing in the browser. Two backends each gain one field.

**Option C — a value in Irmin on main, changed with `test_and_set`.**
- Pro: no new branch.
- Con: a scenario fork copies main, so every scenario carries a copy that nothing
  reads. The merge would merge that path too.
- Con: every reservation is a commit on main. That moves main's head, so a merge
  that is being assembled fails its publish with `MergeTargetMoved` whenever any
  save added a leaf in the meantime, and main's commit list gains one commit per
  such save.
- What you would see: more "main changed, re-run the preview" refusals on merges.

**My recommendation: A.** It keeps the counter and the trees in one store, so no
restore order can make them disagree. B's typed primitives are its real advantage,
and A's re-read after a failure gives the same answer without matching error text.
C pays A's costs and adds merge refusals.

### TWC-D-12 — What happens to the per-tree high-water mark — OPEN

**What has to be decided, and why now.** After TWC-D-8 the per-tree
`seedVarHighWater` no longer decides any assignment. It is stored in `meta`, it is
a field of `RiskTree`, `RiskTree.fromNodes` checks that it is at least the largest
seed in the tree, and the merge compares it as a field
(`FieldName.SeedVarHighWater`, `TreeMetadataFields.seedVarHighWater`, the browser
sentence in `MergeMessages`).

**Option A — keep it, as the largest seed this tree has ever held.**
- Use it gains: one stored number answers "has this tree ever held seed n" without
  reading history. Nothing in the server or the browser asks that question today.
- Con: the merge keeps comparing a field that two branches now raise to different
  values whenever both added leaves. The merge would report a conflict for that
  case, where after TWC-D-8 there is nothing wrong.
- What you would see: a merge of two branches that each added a leaf shows a
  conflict on "seed high-water" that no edit can resolve.

**Option B — remove it.** `meta` loses the field, `RiskTree` loses the field and
its check, and the merge loses the field and its browser sentence.
- Pro: removes a merge conflict that no longer means anything.
- Con: a persisted-format change (`meta` version 3) and a wire change
  (`TreeMetadataFields`, `FieldName`).
- What you would see: the merge dialog no longer mentions seeds.

No consumer reads the value for any purpose besides allocation and its own check.

**My recommendation: B.** Option A's remaining use has no caller, and keeping the
field keeps a merge conflict that TWC-D-8 made meaningless.

### TWC-D-13 — The distinctness scope of a provided seed — OPEN

**What has to be decided, and why now.** Today `SeedVarId.requireDistinct` refuses
two leaves with the same seed inside one tree. After TWC-D-8 server-assigned seeds
are distinct across the whole workspace. A provided seed is not: a create can
provide seed 6 while another tree in the workspace, or another branch of the same
tree, already holds 6.

**Option A — keep the check per tree.** ADR-003 §2 states it as "distinct per
tree".
- Pro: recreating a tree in the same workspace while the original still exists
  works, with every seed provided.
- Con: two branches of one tree can still each be given the same provided seed,
  and the merge refuses that (TWC-D-15 covers the preview side).
- What you would see: a copy of a tree in the same workspace works. A user who
  types the same seed on two branches is refused at the merge.

**Option B — check against every tree and every branch in the workspace.**
- Pro: a seed means one risk across the whole workspace.
- Con: the check reads every tree on every branch of the workspace on every save
  that provides a seed.
- Con: a recreation in the same workspace is refused while the original exists.
- What you would see: a copy in the same workspace is refused until the original
  is deleted.

**My recommendation: A.** Distinctness exists so that two leaves aggregated into
one portfolio never draw the same stream, which would make them perfectly
correlated. Every simulation and every query takes one `treeId`, so two trees are
never aggregated together, and a seed shared by two trees creates no correlation.
The one harmful repeat, two branches of one tree, is caught by TWC-D-15 before the
merge.

### TWC-D-14 — The persisted `meta` format — OPEN

**What has to be decided, and why now.** TWC-D-10 adds `seedEntityId` to `meta`,
and TWC-D-12 Option B removes `seedVarHighWater`. Either change makes the format
version 3. `TreeMetadata.CurrentSchemaVersion`'s scaladoc states the existing
practice: "There is no decode path for version 1: such a store is wiped rather
than migrated."

**Option A — no decode path for version 2. A store holding version 2 is wiped.**
- Pro: one decoder. It follows the existing practice for version 1.
- Pro: the workspace counter in TWC-D-11 then starts at 0 for every workspace,
  with no starting value to derive.
- Con: any tree stored today is lost on upgrade.

**Option B — decode version 2 and fill the new field.** The seed entity id comes
from the workspace, and the counter starts at the largest high-water mark of any
tree on any branch of the workspace.
- Pro: stored trees survive.
- Con: a second decoder, and a start-up or first-use step that reads every branch
  of a workspace to seed its counter.

**My recommendation: A**, under the condition that no store you keep holds trees
you need. The repository cannot settle that condition, and it is the deciding fact.

### TWC-D-15 — Whether the merge preview reports a repeated seed — OPEN

**What has to be decided, and why now.** After TWC-D-8 two branches can still hold
the same seed for two different leaves only through provided seeds. The merge
guard refuses that case with `InvariantBroken`, which names no field. This is the
remaining part of the complex review's finding 1.

**Option A — the scan reports it.** The pass that reports repeated node names
(`duplicateNodeNames`) already decodes every merged node. It also collects the
merged leaves' seeds and reports a repeat as a conflict on the tree, with a new
browser sentence saying which two risks share a seed.
- Pro: the preview predicts every refusal the guard can give.
- Con: more code in the scan, and a new wire case or field name for the conflict.
- What you would see: the preview names the two risks and tells you to remove one
  side's risk and add it again after the merge.

**Option B — leave it to the guard.**
- Pro: no change.
- Con: the preview can say `Clean` and the merge refuse, with no named cause.
- What you would see: the same dead end as §1.3, now only for seeds you typed
  yourself.

**My recommendation: A.**

### TWC-D-16 — How the create request carries a tree's seed entity id — OPEN

**What has to be decided, and why now.** TWC-D-10 lets a create provide the
tree's seed entity id. That is a wire change to the create endpoint and to the
bootstrap endpoint, which creates a workspace and its first tree in one call.

**Option A — an optional field on `RiskTreeDefinitionRequest`:**
`seedEntityId: Option[Long]`, refined at the codec boundary like
`RiskLeafDefinitionRequest.seedVarId`.
- Pro: it travels with the tree it describes, and the bootstrap endpoint gains it
  without change, because it already takes `RiskTreeDefinitionRequest` as its body.
- Con: the bootstrap endpoint then has two seed entity ids in one call: the query
  parameter for the workspace and the body field for its first tree.

**Option B — a query parameter on the create endpoint**, like the bootstrap
endpoint's `seedEntityId`.
- Pro: matches the bootstrap endpoint's existing spelling.
- Con: the value describes the tree in the body, yet travels outside it. The
  bootstrap endpoint would need a second, differently named parameter.

**My recommendation: A.**

---

## 3. Signatures

Draft, written against TWC-D-1, TWC-D-2, TWC-D-5, TWC-D-8, TWC-D-9 and TWC-D-10
as ruled, and against the recommendations for the rest: TWC-D-3 A, TWC-D-4 A,
TWC-D-6 A, TWC-D-7 B, TWC-D-11 A, TWC-D-12 B, TWC-D-13 A, TWC-D-14 A, TWC-D-15 A,
TWC-D-16 A. §3 is rewritten after the rulings and presented as the Signature
Echo, one step of §9 at a time.

### 3.1 The endpoints

```scala
// modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceTreeEndpoints.scala
val getWorkspaceTreeStructureEndpoint =
  authedBaseEndpoint
    …
    .out(jsonBody[Option[RiskTree]])
    .out(header[Option[CommitHash]]("ETag"))        // NEW — the head the body was read at

val updateWorkspaceTreeEndpoint =
  authedBaseEndpoint
    …
    .in(jsonBody[RiskTreeUpdateRequest])
    .in(branchHeader)
    .in(header[CommitHash]("If-Match"))              // NEW — the head the client read
    .out(jsonBody[SimulationResponse])
    .out(header[CommitHash]("ETag"))                 // NEW — the head after the write
```

`header` is Tapir's `sttp.tapir.header`. Family: Tapir endpoint declarations in
`WorkspaceTreeEndpoints.scala`; existing member `.in(query[Option[CommitHash]]("at")`
on the same endpoint. The `If-Match` declaration reuses `ScenarioEndpoints.scala:67`'s
spelling.

```scala
// modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala
final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest],
  seedEntityId: Option[Long]                        // NEW — TWC-D-16 A
)
```

Family: the create request DTOs in the same file; existing member
`RiskLeafDefinitionRequest.seedVarId: Option[Long]`, refined by
`refineSeedVarIdChoice`. The new field is refined by
`ValidationUtil.refineSeedEntityId` the same way.

### 3.2 The save

```scala
// modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
/** The staging branch of one tree save. Named from a fresh ULID (universally
  * unique lexicographically sortable identifier, the format of every id in
  * this codebase) per save, so
  * two saves of one tree never share a branch and an abandoned branch names
  * no later save. */
case class SaveStagingRef(toBranchRef: SaveStagingRefStr) extends StoreBranch:
  def name: String = toBranchRef
```

Family: the sealed `StoreBranch` in `OpaqueTypes.scala:488`, members `BranchRef`
(:491) and `MergeStagingRef` (:547). The naming pattern is a purpose word plus
`Ref`, with the branch name built by a companion method that embeds the
lowercased `WorkspaceId`, as `MergeStagingRef.prefix` does. The refinement
`SaveStagingRefStr` joins the Iron aliases in the same file beside
`MergeStagingRefStr`. §3.4 adds a fourth member, `SeedCounterRef`, in a later
step.

```scala
// modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala
/** The tree write a save makes on its own staging branch; the only `setTree`
  * that takes a staging reference, so a save can write there and nothing else
  * can. */
def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, staging: SaveStagingRef): IO[IrminError, IrminCommit]
```

Family: the `set` overload over `MergeStagingRef` at `IrminClient.scala:49`,
which ADR-036 §2 describes.

```scala
// modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala
/** Replaces the tree, writing only if `branch`'s head is still
  * `expectedHead`. Returns the tree and the head after the write. Fails with
  * `VersionConflict(id, expectedHead, actual)` when the head moved, and
  * nothing was written to `branch`. */
def update(wsId: WorkspaceId, id: TreeId, tree: RiskTree, branch: BranchRef, expectedHead: CommitHash): IO[AppError, (RiskTree, CommitHash)]
```

`op: RiskTree => RiskTree` becomes `tree: RiskTree`: the one caller passes a
constant function, so the higher-order shape promised a transform nothing used.
This is a signature-level change and is settled in the echo, not here.

The steps inside `RiskTreeRepositoryIrmin.update`:

1. `createBranchAt(staging, expectedHead)`. `BranchAlreadyExists` is unreachable,
   because the name carries a fresh ULID.
2. `setTree(basePath, entries, message, staging)` → the new commit.
3. `moveBranchTo(branch, expectedHead, newCommit)`. `BranchHeadStale` becomes
   `VersionConflict(id, expectedHead, actual)`, where `actual` is read from the
   branch after the failure.
4. `deleteBranch(staging, newCommit)`, run on every exit through `ensuring`, so
   a failed save leaves no branch behind. Commits stay reachable by hash, as the
   merge's staging cleanup already accepts.

```scala
// modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef, expectedHead: CommitHash)(using Checked[Permission]): IO[AppError, (RiskTree, CommitHash)]
```

`RiskTreeServiceLive.update` reads the tree at `Revision.Head(branch)` as today
and compares that read's commit with `expectedHead` before building the tree, so
a stale client is refused before any work, and the repository's compare-and-set
refuses the in-request race the early check cannot see. In §9 step 1 the
controller passes the head it read in the same request; in step 3 it passes the
`If-Match` value.

### 3.3 The controller and the browser

`WorkspaceTreeController.getTreeStructure` returns the pair `getById` already
yields instead of dropping the commit. `updateTree` takes the header and returns
`(SimulationResponse, CommitHash)`.

`TreeViewState.scala:196` binds the pair and keeps the head beside the tree.
`TreeBuilderState.loadFromTree(tree: RiskTree)` gains the head it was loaded at,
and `TreeBuilderView.scala:105` sends it. After a successful save the browser
replaces the held head with the `ETag` the PUT answered.

### 3.4 The workspace counter

Family: a server service trait with a live implementation and a layer, the family
of `WorkspaceStore` in
`modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala`.
The pure assignment it wraps is the existing `SeedVarIdAssigner.assign`, which it
reuses unchanged. No existing type denotes a per-workspace seed sequence.

```scala
// modules/server/src/main/scala/com/risquanter/register/services/workspace/SeedVarIdCounter.scala
package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId, WorkspaceId}
import com.risquanter.register.domain.errors.AppError

/** Hands out seeds from one sequence per workspace. A seed handed out once is
  * never handed out again in that workspace, on any branch. */
trait SeedVarIdCounter:
  /** Assigns a seed to every leaf in `leafNames` that `fixed` does not name, and
    * moves the workspace's counter past every value in the result, in one atomic
    * step. `fixed` holds the seeds that must not move: carried over from kept
    * leaves, and provided by the caller for new ones. */
  def assign(
    wsId: WorkspaceId,
    leafNames: Seq[SafeName.SafeName],
    fixed: Map[SafeName.SafeName, SeedVarId.SeedVarId]
  ): IO[AppError, Map[SafeName.SafeName, SeedVarId.SeedVarId]]
```

The step under TWC-D-11 A is: read the counter value c at the workspace's path,
run `SeedVarIdAssigner.assign(leafNames, fixed, c.value)`, then `test_and_set` the
path from c to the returned high-water mark. On failure, read the path again. A
value other than c means another save moved it, and the step repeats. A value
equal to c means the failure was something else, and it is returned. The number
of repeats is bounded by `SeedVarIdCounter.MaxAttempts = 8`, a value chosen here
and not measured.

The Irmin client gains the value compare-and-set:

```scala
// modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala
def testAndSet(path: IrminPath, test: Option[String], set: Option[String], message: String, branch: SeedCounterRef): IO[IrminError, Unit]
```

`SeedCounterRef` joins the sealed `StoreBranch` beside `BranchRef`,
`MergeStagingRef` and §3.2's `SaveStagingRef`. Its name embeds the `WorkspaceId`,
the same as `MergeStagingRef.prefix`, so ADR-036 §2's containment applies to it
unchanged. The records' amendment for server-only branches is made once, in §9
step 1, and step 2 adds this type's row. Its exact spelling is written in the
echo.

### 3.5 The tree's seed entity id

```scala
// modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala
final case class RiskTree private (
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex,
  seedEntityId: SeedEntityId.SeedEntityId,
  mitigations: Seq[Mitigation]
)

// modules/server/src/main/scala/com/risquanter/register/repositories/model/TreeMetadata.scala
final case class TreeMetadata(
  id: TreeId,
  name: SafeName.SafeName,
  rootId: NodeId,
  seedEntityId: SeedEntityId.SeedEntityId,
  schemaVersion: Int,
  createdAt: Instant,
  updatedAt: Instant
)
// CurrentSchemaVersion = 3
```

`RiskTree.fromNodes` replaces its `seedVarHighWater: Option[SeedVarId.SeedVarId]`
parameter with `seedEntityId: SeedEntityId.SeedEntityId`. The merge copies the
tree's `seedEntityId` from main, since both branches hold the same value and it
never changes. It is not a merge field.

### 3.6 Service signatures that change for the seed entity id

```scala
// modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
def create(wsId: WorkspaceId, workspaceSeedEntityId: SeedEntityId.SeedEntityId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Permission]): IO[AppError, RiskTree]
def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean, rev: Revision)(using Checked[Permission]): IO[AppError, Double]
def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean, rev: Revision, omitAbsent: Boolean)(using Checked[Permission]): IO[AppError, Map[NodeId, LECNodeCurve]]

// modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala
def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, branch: BranchRef)(using Checked[Permission]): IO[AppError, QueryResponse]

// modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala
// ensureCached and ensureCachedAll lose their seedEntityId parameter and read tree.seedEntityId.
```

`getLECCurvesMulti`'s `omitAbsent: Boolean = false` loses its default under the
boyscout rule against defaulted parameters on a signature already being changed.

### 3.7 The cache registry key

`ContentCacheRegistry` keys its caches by `SeedEntityId` today, and ADR-014 §4
states "One cache per workspace". After TWC-D-10 two workspaces can hold trees
with one seed entity id. Sharing a cache entry between them is correct, because
equal content and equal seed entity id give equal figures. A cache keyed by seed
entity id alone can no longer be released per workspace, though. A workspace's
trees may draw with another workspace's value, and releasing that value would
drop the other workspace's cache.

The key becomes the pair:

```scala
// modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCacheRegistry.scala
def forTree(wsId: WorkspaceId, seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache]
```

This changes the signatures of `PLAN-WORKSPACE-CACHE-RELEASE.md`, which is
approved in direction and not implemented. Its `ContentCacheRegistry.release`
would take the `WorkspaceId` and drop every key carrying it. That plan is not
edited by this one, and the change is presented to the user for a ruling (§11).

---

## 4. File inventory

The inventory is the user's document `PLAN-TREE-WRITE-CONCURRENCY-INVENTORY.md`.
Until it exists, nothing here is authorized. The path list depends on the open
decisions, so it is derived by search after they are ruled and handed to the
user as the two-part block, one block per step of §9. The files §3 names are its
core: `WorkspaceTreeEndpoints.scala`, `RiskTreeDefinitionRequest.scala`,
`OpaqueTypes.scala`, `RiskTree.scala`, `TreeMetadata.scala`, `IrminClient.scala`,
`IrminClientLive.scala`, `IrminQueries.scala`, `RiskTreeRepository.scala`,
`RiskTreeRepositoryIrmin.scala`, `RiskTreeRepositoryInMemory.scala`,
`RiskTreeService.scala`, `RiskTreeServiceLive.scala`, `QueryService.scala`,
`QueryServiceLive.scala`, `CachedResultResolver.scala`,
`CachedResultResolverLive.scala`, `ContentCacheRegistry.scala`,
`SeedVarIdCounter.scala` (new), `WorkspaceTreeController.scala`,
`WorkspaceLifecycleController.scala`, `QueryController.scala`,
`WorkspaceAnalysisController.scala`, `ScenarioMergeService.scala`,
`TreeViewState.scala`, `TreeBuilderState.scala`, `TreeBuilderView.scala`,
`MergeMessages.scala`, their specs, and a new
`modules/server-it/src/test/scala/com/risquanter/register/services/TreeWriteConcurrencyItSpec.scala`.

Not touched: `RiskTreeUpdateRequest.scala` and `AppError.scala`, because the
token travels in a header and `VersionConflict` exists with its projection;
`ErrorMessages.scala`, because the message exists; `revert`; `IrminQueries`'
merge queries and `ScenarioMergeService`'s compare-and-set publish.

---

## 5. ADR alignment

| ADR | Bearing | Alignment |
|---|---|---|
| ADR-001 | The token, the new request field | Compliant: `CommitHash` is refined by its Tapir codec at the request boundary; `seedEntityId` is refined by `ValidationUtil.refineSeedEntityId` |
| ADR-003 | Seed assignment, seed entity id | **Amended by this plan.** §1's `entityId = workspace.seedEntityId` becomes the tree's value. §2's assignment bullet ("numbering them consecutively from `RiskTree.seedVarHighWater + 1`") becomes the workspace counter. Its high-water bullet and "distinct per tree" bullet follow TWC-D-12 and TWC-D-13. The Reproduction table's row "The same model in a second workspace or deployment with seed IDs provided" gains the seed entity id. The two rows for rebuilding were amended on 2026-11-02 ahead of this plan |
| ADR-004a | Storage | **Amended**: the save's staging branch and the seed counter's branch join the layout beside the merge's staging branch, and §2's description of a write as one `set_tree` commit gains the fork-and-publish |
| ADR-007 | Branch kinds | **Amended**: server-only branches that are neither a scenario nor a merge staging branch — the save's, created and deleted inside one save, and the counter's, permanent. DD-5's rule that scenario-shaped branches are created only by `ScenarioService.create` is untouched, because both names have their own prefix |
| ADR-036 | Branch-name types | Compliant: `SaveStagingRef` and `SeedCounterRef` embed the `WorkspaceId`, join the sealed `StoreBranch`, and the only writes declared over them are the `setTree` and `testAndSet` overloads, so neither can reach a wire type (§2 of the record names the pattern) |
| ADR-010 | `VersionConflict`, counter failures | Compliant: typed error channel; the first production constructor of an existing case. Counter exhaustion reuses `SeedVarIdAssigner`'s `ValidationError`; repeats exhausted under contention fail with an `IrminError` |
| ADR-014 | Cache scope | **Amended by this plan.** §4 "One cache per workspace" becomes one cache per workspace and seed entity id (§3.7) |
| ADR-017 | The PUT, the create DTO | **Amended**: the PUT requires `If-Match`; the create request gains an optional field (TWC-D-16 A); the update DTO is unchanged |
| ADR-032 | The token, the hashed projection | Compliant: the token is a storage-side commit identifier, never a domain content hash. `seedVarId` stays in the projection; the seed entity id stays out of it and stays a cache scope |
| ADR-035 | 409 body | Compliant: `WireError.Conflict` carries a closed `ConflictKind` and no free text |
| ADR-030 | Controllers | Compliant: `Checked[Permission]` propagation unchanged |
| ADR-038 | Response shapes | **Amended**: a single-resource read may carry its head in an `ETag` response header, and a list response carries each entry's head in its body (TWC-D-7 B). The record does not mention headers today. The get-tree response gains `seedEntityId` through `RiskTree`'s derived codec, a refined type and no raw primitive |
| ADR-039 | `If-Match` and stored values on the way in | Compliant: the token is refined by the codec; the counter value read from Irmin is refined to `SeedVarId` on arrival, and a value that fails refinement is a refused peer content failure, not an unreachable store |
| ADR-011, ADR-037 | Every edited file | Compliant: `MaxAttempts` is a named constant in the companion of its owner |

---

## 6. Verification plan

1. **Unit — `RiskTreeServiceLiveSpec`.** An update whose expected head matches
   succeeds and returns the new head. An update whose expected head is an older
   commit fails with `VersionConflict` and leaves the stored tree untouched. A
   create without a seed entity id stores the workspace's. A create with one
   stores it. An update never changes it.
2. **Unit — `WireErrorSpec`.** `VersionConflict` projects to
   `WireError.Conflict(ConflictKind.StaleVersion(), …)`; the row exists.
3. **Unit — Tapir output header.** The structure endpoint's output type is
   `(Option[RiskTree], Option[CommitHash])`; the compile of
   `WorkspaceTreeEndpoints.scala` is the check.
4. **Unit — `SeedVarIdCounterSpec`** (new). Two concurrent `assign` calls on one
   workspace return disjoint seeds. A provided seed above the counter moves the
   counter past it. A gap left by a failed save is never filled.
5. **Unit — `ScenarioMergeServiceSpec`.** The §1.3 example: main and the
   scenario each add a leaf from the same fork, and the preview answers `Clean`
   and the merge succeeds. Under TWC-D-15 A: two provided equal seeds on the two
   branches make the preview report the conflict.
6. **Unit — `CachedResultResolverSpec`.** Two trees in one workspace with
   different seed entity ids and identical content get different figures.
7. **Integration — new `TreeWriteConcurrencyItSpec`**, against real Irmin.
   Two saves of one tree from the same head, run in parallel, each adding a
   leaf: one succeeds, the other fails with `VersionConflict`, the tree at head
   carries the winner's edit, no save staging branch remains, and after §9
   step 2 the two new leaves carry distinct seeds and the counter stands at or
   above both.
8. **Integration — `HttpApiIntegrationSpec`.** A structure read answers an
   `ETag`; a PUT without `If-Match` answers 400; a PUT with a stale `If-Match`
   answers 409.
9. **Integration — `SeedReproducibilityItSpec`.** A tree built over several
   saves, read back, and created in a second existing workspace with its seeds
   and seed entity id provided gives the same figures.
10. **Regression.** The existing mitigation carry-over tests stay green: the
    version check runs before the carry-over, so a rejected update writes no
    mitigations either.
11. **Full suite**, after the `register_it_` cleanup: `sbt 'commonJVM/test;
    server/test'`, `sbt app/test`, `sbt serverIt/test`. Expected: every tier
    passes.

---

## 7. Elevation gate (before any implementation)

1. Rule TWC-D-3, TWC-D-4, TWC-D-6, TWC-D-7 and TWC-D-11 to TWC-D-16.
2. Rewrite §3 against the rulings and present it as the Signature Echo, one
   step of §9 at a time, settling `repo.update`'s parameter shape and the
   spellings of `SaveStagingRef` and `SeedCounterRef` there.
3. Derive the file inventory by search and hand it over, one block per step.
4. Amend ADR-003, ADR-004a, ADR-007, ADR-014, ADR-017 and ADR-038 in the step
   that first touches each.
5. Run every tier green after each step.
6. Present the elevated plan and obtain an accepted signal.

Appendix Option B is a second elevation, not part of this one.

## 8. Out of scope

- `revert`'s no-precondition semantics, an existing explicit ruling.
- Any merge or three-way reconciliation of conflicting edits. This plan makes a
  conflict visible and refuses the write; it does not resolve it.
- The scenario list's `head` field (TWC-D-7 B keeps it).
- `PLAN-WORKSPACE-CACHE-RELEASE.md`'s signatures, which §3.7 affects and §11
  presents.

## 9. Steps and their order

The plan ships whole. Its work lands in three steps, each green before the next
starts.

1. **The compare-and-set save, server side.** `SaveStagingRef`, the `setTree`
   overload, `RiskTreeRepository.update` with `expectedHead`,
   `RiskTreeServiceLive.update` comparing the head it read. The controller
   passes the head it reads in the same request, so this step closes W1 on its
   own and changes no endpoint. `TreeWriteConcurrencyItSpec` lands here. This
   step amends ADR-004a, ADR-007 and ADR-036 for server-only branches once.
2. **The seeds.** `SeedCounterRef`, the counter, the per-tree seed entity id, the
   `meta` format change, the cache key, the merge preview's seed check. Its save
   path is the step-1 save. Its seed assertion joins `TreeWriteConcurrencyItSpec`.
   This step amends ADR-003 and ADR-014 and adds `SeedCounterRef`'s row to the
   records step 1 amended.
3. **The client token.** `ETag` on the reads, `If-Match` on the PUT, the browser
   changes. This closes W2. This step amends ADR-017 and ADR-038.

Step 1 precedes both others. Steps 2 and 3 do not depend on each other; step 2 is
listed first because the user ruled on 2026-11-02 that the compare-and-set save
is a prerequisite of the seed work, and that ruling is satisfied by step 1 alone.
The seed counter is reserved atomically on its own, so it would keep seeds
distinct without step 1; step 1 is what keeps both concurrent saves' leaves.

## 10. Terminology impact

None. `docs/user/TERMINOLOGY.md` has no entry for seed, seed entity id,
high-water mark or save. Its sentences about a leaf's random-number stream stay
true.

## 11. Relation to other plans

- `PLAN-RISKTRANSFORM.md` §7.6.1 R1 is the lost-update finding; it refers to
  this plan's "Decision 2", which is TWC-D-2.
- `PLAN-ERROR-WIRE-BOUNDARY.md` §17.2 names the merge field `SeedVarHighWater`.
  Under TWC-D-12 B that field is removed, and §17.2 is swept in step 2. The
  complex review's finding 1 is closed by TWC-D-8 for server-assigned seeds and
  by TWC-D-15 for provided ones.
- `PLAN-WORKSPACE-CACHE-RELEASE.md` is approved in direction and not
  implemented. §3.7 changes its `ContentCacheRegistry.release` signature to take
  the `WorkspaceId`. Whether that plan is amended to match is the user's ruling,
  presented with this plan's open decisions.
- `PLAN-PHASE-F-SEMANTIC-MERGE.md` and appendix Option B want the same
  domain-level diff-and-merge capability (appendix §5). Neither is designed here.
