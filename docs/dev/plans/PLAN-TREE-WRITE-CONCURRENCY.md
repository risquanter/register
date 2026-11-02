# PLAN — Tree write concurrency (a compare-and-set save with a client-held version token)

**Status: DRAFT — TWC-D-3, TWC-D-4, TWC-D-6 and TWC-D-7 are unruled. This
document confers no G3 plan coverage and authorizes no source edit.** It becomes
implementation-grade once those decisions are ruled, §3 is presented as the
Signature Echo, and the user has written `PLAN-TREE-WRITE-CONCURRENCY-INVENTORY.md`.

**Origin:** complex-tier review finding R1, recorded in `PLAN-RISKTRANSFORM.md`
§7.6.1. Split into its own plan because it is a different feature from the
mitigation workstream: it predates mitigations and applies equally to node
content, tree name and seeds.

**Companion:** `PLAN-TREE-WRITE-CONCURRENCY-APPENDIX-WRITE-SHAPE.md` — the write
shape analysis and the four options behind §2.0.

**Paired plan:** `PLAN-SEED-ALLOCATION.md`. Its seed counter needs the
compare-and-set save of this plan's step 1, and §9 sequences the two plans.

**Inventory:** `PLAN-TREE-WRITE-CONCURRENCY-INVENTORY.md`, beside this file. It
does not exist yet. The user creates it.

**Checked against the code on 2026-11-02.** Every file, type and line this plan
names was re-read on that date. The error path it relies on has landed:
`PLAN-ERROR-WIRE-BOUNDARY.md` §17 and §18 are implemented, and its remaining
phases (3b, 4 to 7) name neither `VersionConflict` nor `ConflictKind.StaleVersion`.

---

## 1. Problem

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

### 1.1 Two distinct windows

The fix depends on which window is being closed, and they are not the same size.

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

**W2 strictly contains the damage of W1 and is orders of magnitude wider.** This
matters because the two windows need different mechanisms:

- A purely **server-internal** check — compare the head at write time against
  the head the service read at the start of the same request — closes W1 only.
  In the W2 scenario both values are C1 and the check passes, so the edit is
  still lost.
- A **client-supplied expected version** closes both. The client sends the
  commit it read; the server refuses to write if the branch has moved since.

**Derivation:** optimistic concurrency control works by carrying a version token
across the full read-modify-write cycle and validating it at the write. The
cycle here begins when the *client* reads, not when the server does, because the
data being written was composed from the client's read. A token that enters the
cycle only at the server's read is validating a shorter cycle than the one that
actually exists. So the token must be client-supplied.

### 1.2 What already exists

Most of the machinery is present and unused on this path:

| Piece | Where | State |
|---|---|---|
| `VersionConflict(treeId: TreeId, expected: CommitHash, actual: CommitHash)` | `AppError.scala:286` | Exists, `extends SimError`, no production constructor |
| HTTP 409 mapping | `WireError.fromAppError` projects it to `Conflict(ConflictKind.StaleVersion(), t)` (`WireError.scala:176`) | Exists |
| SPA message | `ErrorMessages.scala:40` renders `ConflictKind.StaleVersion()` as "This was edited elsewhere since you loaded it. Reload to see the current version." `GlobalError.fromWireError` returns `None` for every `WireError.Conflict`, so a conflict is shown where the request was made and never as a global error | Exists |
| `CommitHash` as a wire value | `?at=` query param, `RevertTreeRequest.toCommit`, `TreeHistoryEntry.commitHash`, `ScenarioSummaryResponse.head` | Exists |
| The read that yields the version | `RiskTreeService.getById` returns `Option[(RiskTree, CommitHash)]` | Exists, and `WorkspaceTreeController.getTreeStructure` drops the commit |
| Branch-pointer compare-and-set | `IrminClient.createBranchAt(StoreBranch, CommitHash)`, `deleteBranch(StoreBranch, CommitHash)`, `moveBranchTo(BranchRef, expectedHead, to)` | Exists |
| A client-supplied version token on a write | `deleteScenarioEndpoint`'s `.in(header[CommitHash]("If-Match"))` (`ScenarioEndpoints.scala:67`), carrying the head read from `ScenarioSummaryResponse.head`; `ScenarioState.scala:132` echoes it | Exists, for scenario deletion only |
| Write-to-a-staging-branch then publish under one compare-and-set | `ScenarioMergeService`, with `MergeStagingRef` and the `set` overload that takes it (`IrminClient.scala:49`) | Exists, for the merge only |

**What does not exist:** `IrminQueries.setTree` takes no precondition, and
`IrminClient.setTree` is declared over `BranchRef` alone. Irmin's mutation
surface as used here is `set_value`, `set_tree`, `remove`, `merge_with_branch`,
`revert`, `test_and_set_branch`. There is no conditional tree write.

Irmin 3.11's own behaviour, read from `irmin` `store.ml` 795-838 and
`irmin-graphql` `server.ml` 603-720 in the `local/irmin-builder:3.11-p1` image:
`set_tree` takes a snapshot of the head, replaces the subtree at its path, and
publishes with a compare-and-set on the head. When the head has moved it retries,
up to 13 times by default, writing the same subtree on top of the new head. A
concurrent write to another path therefore survives, and a concurrent write to the
same tree is replaced. The GraphQL surface also exposes `test_and_set` and
`test_set_and_get`, which write one value only if the value at the path is still
the one given. Irmin's `test_and_set_tree`, the same check for a whole subtree,
exists in the store and is not exposed through GraphQL. The `info` argument of
every mutation carries `retries`, `allow_empty` and `parents`; `retries: 0` stops
the retry but tests against the head the mutation itself snapshots, not against a
head the caller observed, so it closes neither window.

### 1.3 The output-header check, run

Decision 5's ruled direction of 2026-10-19 depended on one unverified premise:
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

Labels are `TWC-D-n`. The numbering keeps the order the decisions were first
written in, because `PLAN-RISKTRANSFORM.md` §7.6.1 refers to this plan's
"Decision 2" by that number.

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
§1.3 ran on 2026-11-02.

The PUT declares `.in(header[CommitHash]("If-Match"))`, the same declaration
`deleteScenarioEndpoint` already carries. `RiskTreeUpdateRequest` is unchanged.
The options that were weighed: a body field `expectedRevision: CommitHash`,
which reimplements a conditional request inside the DTO; and an optional body
field, rejected because an opt-in guard against silent data loss leaves the
default wrong.

### TWC-D-2 — How the precondition is enforced against Irmin — RULED: a compare-and-set through a staging branch

Ruled 2026-11-02: the save uses a test-and-set approach. The user's words: "both
have it as their respective tasks with a 'test and set approach'", said of this
plan and `PLAN-SEED-ALLOCATION.md`.

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
tree someone else just edited) and `revertTree`, whose scaladoc in
`RiskTreeRepository.scala:26-27` reads "Reads the tree state at `toCommit` and
writes it forward with a `:revert` message; no precondition", an explicit prior
ruling. `create` writes a path that does not exist yet, and a concurrent write to
another path survives Irmin's retry (§1.2), so `create` needs no precondition.

**Option A — `update` only.** `revertTree` keeps its ruling; `delete` stays
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
continuation section once A has landed. The `revertTree` ruling is not reopened
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
convention: §3 names the two endpoints, and `WorkspaceTreeEndpoints` carries a
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
- Con: a new endpoint or a changed list shape, outside this plan's finding.

**Option B — leave it, and record the difference.** A list response carries each
entry's head in the body because a header cannot; a single-resource read carries
its head in `ETag`. Both are stated in ADR-038's amendment (§5).
- Pro: no scope growth; the rule is one sentence and explains both.
- What you would see: nothing changes on the scenario path.

**My recommendation: B.** The difference is a property of lists against single
resources, not two conventions for one case.

---

## 3. Signatures

Draft, written against TWC-D-1, TWC-D-2, TWC-D-5 as ruled and TWC-D-3 A,
TWC-D-4 A, TWC-D-6 A, TWC-D-7 B as recommended. It is rewritten after the rulings
and presented as the Signature Echo.

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

### 3.2 The save

```scala
// modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
/** The staging branch of one tree save. Named from a fresh ULID per save, so
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
`MergeStagingRefStr`. `PLAN-SEED-ALLOCATION.md` §4.1 adds a third member of this
family, `SeedCounterRef`; §9 sequences which lands first.

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
refuses the in-request race the early check cannot see.

### 3.3 The controller and the browser

`WorkspaceTreeController.getTreeStructure` returns the pair `getById` already
yields instead of dropping the commit. `updateTree` takes the header and returns
`(SimulationResponse, CommitHash)`.

`TreeViewState.scala:196` binds the pair and keeps the head beside the tree.
`TreeBuilderState.loadFromTree(tree: RiskTree)` gains the head it was loaded at,
and `TreeBuilderView.scala:105` sends it. After a successful save the browser
replaces the held head with the `ETag` the PUT answered.

---

## 4. File inventory

The inventory is the user's document `PLAN-TREE-WRITE-CONCURRENCY-INVENTORY.md`.
Until it exists, nothing here is authorized. The path list depends on TWC-D-3
and TWC-D-4, so it is derived by search after they are ruled and handed to the
user as the two-part block. The files §3 names are its core:
`WorkspaceTreeEndpoints.scala`, `OpaqueTypes.scala`, `IrminClient.scala`,
`IrminClientLive.scala`, `IrminQueries.scala`, `RiskTreeRepository.scala`,
`RiskTreeRepositoryIrmin.scala`, `RiskTreeRepositoryInMemory.scala`,
`RiskTreeService.scala`, `RiskTreeServiceLive.scala`,
`WorkspaceTreeController.scala`, `TreeViewState.scala`, `TreeBuilderState.scala`,
`TreeBuilderView.scala`, their specs, and a new
`modules/server-it/src/test/scala/com/risquanter/register/services/TreeWriteConcurrencyItSpec.scala`.

Not touched: `RiskTreeUpdateRequest.scala` and `AppError.scala`, because the
token travels in a header and `VersionConflict` exists with its projection;
`ErrorMessages.scala`, because the message exists; `revertTree`.

---

## 5. ADR alignment

| ADR | Bearing | Alignment |
|---|---|---|
| ADR-001 | The token | Compliant: `CommitHash` is refined by its Tapir codec at the request boundary; no raw `String` in any signature |
| ADR-004a | Storage | **Amended**: the save's staging branch joins the layout beside the merge's, and §2's description of a write as one `set_tree` commit gains the fork-and-publish |
| ADR-007 | Branch kinds | **Amended**: a save staging branch is a third server-only branch kind, created and deleted inside one save; DD-5's rule that scenario-shaped branches are created only by `ScenarioService.create` is untouched, because the staging name has its own prefix |
| ADR-036 | Branch-name types | Compliant: `SaveStagingRef` embeds the `WorkspaceId`, joins the sealed `StoreBranch`, and the only write declared over it is the `setTree` overload, so it cannot reach a wire type (§2 of the record names the pattern) |
| ADR-010 | `VersionConflict` | Compliant: typed error channel; the first production constructor of an existing case |
| ADR-017 | The PUT | **Amended**: the PUT requires `If-Match`; the DTO is unchanged |
| ADR-038 | Response shapes | **Amended**: a single-resource read may carry its head in an `ETag` response header, and a list response carries each entry's head in its body (TWC-D-7 B). The record does not mention headers today |
| ADR-032 | The token | Compliant: a storage-side commit identifier, never a domain content hash |
| ADR-035 | 409 body | Compliant: `WireError.Conflict` carries a closed `ConflictKind` and no free text |
| ADR-030 | Controller | Compliant: `Checked[Permission]` propagation unchanged |
| ADR-039 | `If-Match` on the way in | Compliant by construction: refined by the codec |
| ADR-011, ADR-037 | Every edited file | Compliant |

---

## 6. Verification plan

1. **Unit — `RiskTreeServiceLiveSpec`.** An update whose `If-Match` matches the
   head succeeds and returns the new head. An update whose `If-Match` is an
   older commit fails with `VersionConflict` and leaves the stored tree
   untouched.
2. **Unit — `WireErrorSpec`.** `VersionConflict` projects to
   `WireError.Conflict(ConflictKind.StaleVersion(), …)`; the row exists.
3. **Unit — Tapir output header.** The structure endpoint's output type is
   `(Option[RiskTree], Option[CommitHash])`; the compile of
   `WorkspaceTreeEndpoints.scala` is the check.
4. **Integration — new `TreeWriteConcurrencyItSpec`**, against real Irmin.
   Two saves of one tree from the same head, run in parallel: one succeeds, the
   other fails with `VersionConflict`, the tree at head carries the winner's
   edit, and no `save-staging.` branch remains. `PLAN-SEED-ALLOCATION.md` §7
   adds its seed assertion to this spec when it lands.
5. **Integration — `HttpApiIntegrationSpec`.** A structure read answers an
   `ETag`; a PUT without `If-Match` answers 400; a PUT with a stale `If-Match`
   answers 409.
6. **Regression.** The existing mitigation carry-over tests stay green: the
   version check runs before the carry-over, so a rejected update writes no
   mitigations either.
7. **Full suite**, after the `register_it_` cleanup: `sbt 'commonJVM/test;
   server/test'`, `sbt app/test`, `sbt serverIt/test`. Expected: every tier
   passes.

---

## 7. Elevation gate (before any implementation)

1. Rule TWC-D-3, TWC-D-4, TWC-D-6 and TWC-D-7.
2. Rewrite §3 against the rulings and present it as the Signature Echo,
   settling `repo.update`'s parameter shape there.
3. Derive the file inventory by search and hand it over.
4. Amend ADR-004a, ADR-007, ADR-017 and ADR-038 in the same plan.
5. Run every tier green.
6. Present the elevated plan and obtain an accepted signal.

Appendix Option B is a second elevation, not part of this one.

## 8. Out of scope

- `revertTree`'s no-precondition semantics, an existing explicit ruling.
- Any merge or three-way reconciliation of conflicting edits. This plan makes a
  conflict visible and refuses the write; it does not resolve it.
- The scenario list's `head` field (TWC-D-7 B keeps it).

## 9. Sequencing with `PLAN-SEED-ALLOCATION.md`

The two plans share one piece of work and one family of types. This section is
the single statement of their order; the seed plan points here.

**Shared work.** The compare-and-set save of TWC-D-2 (§3.2) is the save both
plans write through. The seed plan's counter is reserved atomically on its own,
so it does not need the save to be a compare-and-set for seeds to stay distinct.
The user ruled on 2026-11-02 that the compare-and-set save is nevertheless a
prerequisite of the seed plan, so it lands first.

**Shared family.** `SaveStagingRef` (this plan) and `SeedCounterRef` (seed plan,
SA-D-4 A) are both server-only members of `StoreBranch` that embed the
`WorkspaceId`. The first to land establishes the pattern and amends ADR-004a,
ADR-007 and ADR-036 for both: "a server-only branch, named with a purpose prefix
and the workspace, that no client-facing type can name". The second adds its row
to the amended records.

**Order.**

1. **This plan, step 1 — the compare-and-set save, server side.**
   `SaveStagingRef`, the `setTree` overload, `RiskTreeRepository.update` with
   `expectedHead`, `RiskTreeServiceLive.update` comparing the head it read. The
   controller passes the head it reads in the same request, so this step closes
   W1 on its own and changes no endpoint. `TreeWriteConcurrencyItSpec` lands
   here.
2. **The seed plan** — `SeedCounterRef`, the counter, the per-tree seed entity
   id, the `meta` format change. Its save path is the step-1 save. Its seed
   assertion joins `TreeWriteConcurrencyItSpec`.
3. **This plan, step 2 — the client token.** `ETag` on the reads, `If-Match` on
   the PUT, the browser changes. This closes W2.

Steps 2 and 3 do not depend on each other. Step 1 precedes both. Each plan's
inventory names only its own step's files; the shared files
(`RiskTreeRepositoryIrmin.scala`, `RiskTreeServiceLive.scala`, `OpaqueTypes.scala`,
`IrminClient.scala`, `IrminClientLive.scala`, `IrminQueries.scala`) appear in both
inventories, and whichever plan is approved at the time authorizes the edit.
