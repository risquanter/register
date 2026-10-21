# PLAN — Tree write concurrency (optimistic concurrency control on the tree PUT)

**Status: NEEDS REVIEW — not yet implementation-grade, does not confer G3 plan
coverage.** No source edit authorized. Before any code this document must be
elevated (exact signatures, `## File inventory` with full paths, completed ADR
alignment, verification plan) and explicitly approved — see "Elevation gate".

**Origin:** complex-tier review finding R1, recorded in `PLAN-RISKTRANSFORM.md`
§7.6.1. Split into its own plan because it is a different feature from the
mitigation workstream: it predates mitigations and applies equally to node
content, tree name and `seedVarHighWater`.

**Companion:** `PLAN-TREE-WRITE-CONCURRENCY-APPENDIX-WRITE-SHAPE.md` — the write
shape analysis and the four options behind §2.0.

**The error boundary was rewritten after this document was written, and the
error types this plan relies on must be re-read before its design work starts.**
`PLAN-ERROR-WIRE-BOUNDARY.md` replaced the error path: internal failures are
projected one-way onto a closed `WireError` type whose case decides the HTTP
status through a Tapir `oneOf`, the generic `ErrorResponse` envelope and its
`decode` reconstruction are deleted, `ValidationError` carries a typed
`ErrorLocation` instead of a `field: String`, and `VersionConflict`'s own fields
are now `TreeId` and two `CommitHash` values. Everything in this document that
names an error type, a status mapping or a client-side classification states the
shape as it is now rather than as it was. That rewrite is itself still in
progress, so further changes are likely: re-read `AppError.scala`,
`WireError.scala` and `GlobalError.scala` against this plan before ruling its
open decisions, and treat any mismatch as this document being stale rather than
the code deviating.

---

## 1. Problem

`RiskTreeServiceLive.update` reads the tree, computes a complete replacement
from the request, and calls `repo.update(wsId, id, _ => riskTree, branch)`. The
repository re-reads the tree and then discards that read, because the function
it is handed is a constant rather than a transform of its argument. Nothing in
the path compares versions. Two writes to the same tree therefore resolve
last-writer-wins, and the loser's edit is destroyed with no error to either
party.

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
  still lost. This is the shape the review's Option A sketched, and on its own
  it does not solve the reported problem.
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
| `VersionConflict(treeId: TreeId, expected: CommitHash, actual: CommitHash)` | `AppError.scala` | Exists, `extends SimError`, no production constructor |
| HTTP 409 mapping | `WireError.fromAppError` projects it to `Conflict(ConflictKind.StaleVersion())` | Exists |
| SPA handling | `GlobalError.fromWireError` classifies `WireError.Conflict` | Exists |
| `CommitHash` as a wire value | `?at=` query param, `RevertTreeRequest.toCommit`, `TreeHistoryEntry.commitHash`, `ScenarioSummaryResponse.head` | Exists |
| The read that yields the version | `RiskTreeService.getById` returns `(RiskTree, CommitHash)` | Exists |
| Branch-pointer compare-and-set | `IrminQueries.testAndSetBranch`, `IrminClient.deleteBranch`/`createBranchAt` | Exists |
| A client-supplied version token on a write | `deleteScenarioEndpoint`'s `If-Match` header, carrying the head read from `listScenarios` | Exists, for scenario deletion only |
| Write-to-a-staging-branch then publish under one compare-and-set | `ScenarioMergeService` | Exists, for the merge only |

**What does not exist:** `IrminQueries.setTree` takes no precondition, and
`IrminClient.setTree` has no compare-and-set parameter. Irmin's mutation surface
as used here is `set_value`, `set_tree`, `remove`, `merge_with_branch`,
`revert`, `test_and_set_branch`. There is no conditional tree write.

### 1.3 Two insights that bear on the decisions below

**This mechanism already exists once, on a different operation, and it chose the
header.** Scenario deletion carries a client-supplied version token:
`deleteScenarioEndpoint` declares `.in(header[CommitHash]("If-Match"))`, the
client obtains that commit from `ScenarioSummaryResponse.head` in the
`listScenarios` **response body**, and `ScenarioState` echoes it back. So the
project has a working round trip in which a commit leaves in a body and returns
in a header, and `ScenarioHeadStale(scenario, expected, actual)` is the failure.
Decision 1 and Decision 5 are both argued below partly on the premise that the
header route is unavailable; that premise needs rechecking against this
precedent before either is ruled.

**The staging-branch machinery Decision 2 Option B would need now exists.**
`ScenarioMergeService` assembles a merge on its own staging branch and publishes
it to the target under one `test_and_set_branch`, with the staging reference
typed separately from `BranchRef` so a staging branch cannot be passed where a
scenario branch is expected. Decision 2 disfavours Option B partly on the cost
of "temp-branch naming, lifecycle and cleanup on failure"; that cost has since
been paid once and the pattern can be followed rather than invented.

---

## 2. Decisions

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

### 2.1 Open decisions — none ruled

#### Decision 1 — How is the expected version carried to the server?

**Goal:** choose the transport for the client's version token on the tree PUT.

**Option A — HTTP `If-Match` header carrying the commit hash as an ETag**, with
`GET` responses carrying `ETag`.
*Pros:* this is what conditional requests are for; HTTP already defines
412/428 semantics; caches and proxies understand it; no DTO change.
*Cons:* Tapir header plumbing on every tree read and write; the ETag must be
threaded through the SPA's fetch layer, which does not read response headers
today; `412 Precondition Failed` and the existing `409` mapping must be
reconciled.

**Option B — a required field on `RiskTreeUpdateRequest`** (e.g.
`expectedRevision: CommitHash`).
*Pros:* stays inside machinery the project already has — `CommitHash` is already
a wire type with a Tapir codec; the existing `VersionConflict` → 409 mapping and
the SPA's existing handler work unchanged; validated by the same smart
constructor as every other field.
*Cons:* re-implements conditional requests in the body rather than using the
HTTP mechanism; a new required field is a breaking DTO change.

**Option C — an optional field, enforcing only when present.**
*Pros:* no breaking change; incremental client adoption.
*Cons:* the protection is opt-in, so the default stays unsafe. A client that
omits it silently keeps today's lost-update behaviour, which is the bug.

**My recommendation: Option B.** It reuses the error type, the status mapping
and the SPA handler that already exist, so the change is confined to the DTO and
the write path. Option A is the more correct use of HTTP, but it buys
correctness the project cannot currently spend — nothing in the SPA reads
response headers — in exchange for a materially larger change. Option C is
rejected: an opt-in guard against silent data loss leaves the default wrong.

**This recommendation rests on a premise that has since changed and must be
rechecked before it is ruled.** It argues against Option A on the cost of
sending the token in a header, and scenario deletion now does exactly that:
`deleteScenarioEndpoint` takes `.in(header[CommitHash]("If-Match"))` and the SPA
supplies it. The Tapir plumbing for a `CommitHash` request header therefore
exists and is in use, which removes Option A's stated cost on the
client-to-server leg entirely. The server-to-client leg is measured under
Decision 5, and the measurement does not support a cost difference there
either.

#### Decision 2 — How is the precondition enforced against Irmin?

**Goal:** decide the write mechanism, given that `set_tree` has no precondition.

**Option A — check-then-act.** Resolve the branch head; if it differs from the
client's expected revision, fail with `VersionConflict`; otherwise `set_tree`
unconditionally.
*Pros:* small, uses only existing primitives, no new Irmin surface. Closes W2
entirely, which is the wide window.
*Cons:* leaves W1 open — a residual race between the head check and the write,
bounded by one request. Two genuinely simultaneous writers can still lose an
edit.

**Option B — true compare-and-swap via a scratch branch.** Fork a temporary
branch at the expected head, `set_tree` onto it, then
`test_and_set_branch(target, test = expectedHead, set = newCommit)`.
*Pros:* an actual atomic CAS; closes W1 and W2.
*Cons:* substantially more machinery — temp-branch naming, lifecycle and
cleanup on failure; interaction with DD-5's invariant that scenario-shaped
branches are only created by `ScenarioService.create`; more Irmin round trips
per write. Needs its own verification that an abandoned scratch branch cannot
accumulate.

**Option C — serialize writes per tree** with an in-process lock keyed on
`(wsId, treeId, branch)`.
*Pros:* closes W1 without Irmin changes.
*Cons:* does not close W2 at all, and is unsound across more than one server
instance. Only meaningful combined with Option A.

Under the §2.0 ruling, Option B here is further disfavoured: its scratch-branch
machinery is also what appendix Option D would require, and the ruling does not
take that route.

**My recommendation: Option A, with Option C's per-tree serialization as a
follow-on only if W1 is ever observed.** The reasoning is proportionality: W2 is
the window that produces the reported failure and Option A closes it completely;
W1 requires true simultaneity on the same tree and, once W2 is closed, its
remaining exposure is one request's duration. Option B's cost is concentrated in
exactly the area — branch lifecycle — where this codebase already carries
documented invariants that a scratch branch would complicate.

#### Decision 3 — Which write paths carry the precondition?

`update` is the one the finding names. Also candidates: `delete` (deleting a
tree someone else just edited) and `revertTree` (whose docstring already says
"no precondition (last write wins)" — an explicit prior ruling).

**Recommendation:** `update` only in this plan, leaving `revertTree`'s existing
explicit ruling intact and treating `delete` as a separate question. Flagged so
the choice is deliberate rather than an omission.

#### Decision 4 — What does the SPA do on a 409?

Options: surface the conflict and require a manual reload; auto-reload and
replay the user's edit onto the new head; show a diff. `GlobalError` already
maps `VersionConflict` to `Conflict`, so something is displayed today.

**Recommendation:** deferred to the implementation section once Decisions 1–3
are ruled; the minimum is an explicit, non-generic message telling the user the
tree changed underneath them and their edit was not applied.

---

#### Decision 5 — How does the client learn the commit it read?

Numbered last to keep the existing references stable; logically it precedes
Decision 1, because a client-supplied token requires the client to have one.

`RiskTreeService.getById` returning `(RiskTree, CommitHash)` carries the revision
to the service layer only. The wire does not carry it: `getWorkspaceTreeStructureEndpoint`
declares `.out(jsonBody[Option[RiskTree]])`, and `TreeBuilderState.loadFromTree`
takes a bare `RiskTree`. The read that populates the editor has no revision in it.

**Option A — widen the `/structure` response** to carry the tree and its commit.
*Pros:* one JSON type change; the SPA's existing decode path handles it;
symmetric with a token travelling in the request body (Decision 1 Option B).
*Cons:* changes a response shape existing consumers depend on; puts transport
metadata inside a domain payload.

**Option B — emit the commit as an HTTP `ETag` response header.**
*Pros:* the correct HTTP mechanism and the natural pair to `If-Match`
(Decision 1 Option A); the response body stays a pure domain object.
*Cons:* the SPA's fetch layer does not read response headers today.

**Option C — the client fetches the head separately** via the history endpoint.
*Cons:* rejected. The head can advance between the structure read and the history
read, so the client would pin a commit it never read — reintroducing the
resolve-then-reload race that OD-5=D closed at the repository, one layer up.

**My recommendation:** rule this together with Decision 1 — Option A here if
Decision 1 lands on the body field, Option B here if it lands on `If-Match`.
Mixing them buys both sets of plumbing and neither's coherence.

**A fourth option exists and is already in production on the scenario path:
a commit in the response body, echoed back in a request header.**
`ScenarioSummaryResponse.head` carries the commit out in a JSON body and
`deleteScenarioEndpoint`'s `If-Match` takes it back in a header. That mixes the
two mechanisms, which the paragraph above argues against, so "mixing them" is
not automatically incoherent and the recommendation should be reconsidered with
that precedent in view.

#### The cost of Option A against Option B, measured

Both options change the same three files, which are all the places the structure
endpoint is named:

```
modules/common/.../http/endpoints/WorkspaceTreeEndpoints.scala:38    the declaration
modules/server/.../http/controllers/WorkspaceTreeController.scala:55  the handler
modules/app/.../state/TreeViewState.scala:199                         the caller
```

The kind of cost is one-time engineering work and the compiler finds all of it.
The declaration gains one line, the handler returns a pair instead of a value,
and the caller binds a pair instead of a value. There is no runtime cost either
way, because a header and a body field are both bytes in the same response.

The quoting an `ETag` needs is already written.
`IronTapirCodecs.scala`'s `Codec[String, CommitHash, CodecFormat.TextPlain]`
strips quotes on decode and adds them on encode, and its own comment states that
the one definition serves both the server and the generated sttp client — so the
same codec would serve a response header without modification.

`BackendClient.endpointRequestZIO[I, O]` is generic in `O` and reads only
`response.body`, so nothing in the browser's call layer changes either. It does
not need to read a header, because the output type is decoded for it.

**The premise this measurement rests on is unverified:** that Tapir's sttp client
interpreter decodes a declared output header into `O` rather than discarding it.
Tapir is a third-party library with no local checkout, so its source was not
read. The single check that settles it is to declare
`.out(header[CommitHash]("ETag"))` on one endpoint and compile — the output type
either becomes a tuple or it does not.

**Three things differ between the options, and none of them is plumbing cost.**
An output header is a pattern this codebase has no instance of, so the first one
establishes a convention rather than following one. A precondition failure in
HTTP is `412 Precondition Failed` while this project answers conflicts with
`409`, so the status must be chosen. And putting the commit in a response body
puts transport metadata inside a domain payload, which ADR-038 governs.

**Ruled direction for this question, 2026-10-19: verify the `ETag` route, and if
it works with Tapir, adopt it rather than the body field.** Run the check above
first. If a declared output header decodes into `O`, then the `ETag` and
`If-Match` pairing is the route to take, and taking it means three further pieces
of work rather than one:

1. Establish the precedent deliberately. The first output header in the codebase
   is a convention, so it is declared and documented as one rather than added as
   a one-off.
2. Consider moving the existing scenario path onto it. `ScenarioSummaryResponse`
   carries `head` in a body today, and leaving it there while the tree path uses
   an `ETag` is the half-migrated state the project's own convention rule warns
   against. Whether that move belongs in this plan or its own is part of this
   plan's elevation.
3. Resolve the records. ADR-038 decides response shapes and does not currently
   contemplate a response header carrying transport metadata; ADR-017 describes
   the tree API's request shape. Whichever of them the chosen route contradicts
   is amended in the same plan, not afterwards.

If the check shows Tapir discards a declared output header, Decision 5 falls back
to Option A and this ruling lapses.

## 3. Signatures (draft — subject to the decisions above; a Signature Echo follows approval)

Written against Decision 1 = B and Decision 2 = A.

```scala
// modules/common/.../http/requests/RiskTreeRequests.scala
final case class RiskTreeUpdateRequest(
  name:            String,
  portfolios:      Seq[RiskPortfolioUpdateRequest],
  leaves:          Seq[RiskLeafUpdateRequest],
  newPortfolios:   Seq[RiskPortfolioDefinitionRequest],
  newLeaves:       Seq[RiskLeafDefinitionRequest],
  expectedRevision: CommitHash          // NEW — the commit the client read
)

// modules/server/.../repositories/RiskTreeRepository.scala
/** Replaces the tree, refusing the write when the branch head is not
  * `expectedRevision`. */
def update(
  wsId: WorkspaceId,
  id: TreeId,
  op: RiskTree => RiskTree,
  branch: BranchRef,
  expectedRevision: CommitHash
): IO[AppError, RiskTree]

// modules/server/.../services/RiskTreeServiceLive.scala — update()
(oldTree, head) <- getTreeOrFail(wsId, id, Revision.Head(branch))
_ <- ZIO.when(head != req.expectedRevision)(
       ZIO.fail(VersionConflict(id, req.expectedRevision, head)))
```

Reusing the existing `VersionConflict` rather than adding a variant avoids
touching every exhaustive `AppError` match, which is a compile error at every
match site when a leaf is added to the sealed hierarchy.

**`VersionConflict`'s fields are already typed, and that typing is a guess this
plan validates rather than inherits.** The case reads
`VersionConflict(treeId: TreeId, expected: CommitHash, actual: CommitHash)`.
It was typed by `PLAN-ERROR-WIRE-BOUNDARY.md`'s `EWB-D-29`, which removed three
`String` fields because untyped identifiers are a defect that plan was closing
across the board. The field names and types were derived from where the check
would sit — this plan's own service-layer snippet above — and from nothing else.
Three things that ruling deliberately did not decide, and that belong here:
whether a tree-level conflict should also name the node that diverged; whether
the client needs both commits or only the current head; and whether the token is
a commit hash at all. A different answer here overrides the shape without
reopening that ruling.

One further point the echo must settle: `op: RiskTree => RiskTree` remains a
constant function at its only call site. Whether to keep the higher-order shape
or change the contract to take a tree is a live question, since the type
currently promises something no caller uses.

---

## 4. File inventory

- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/WireError.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala`
- `modules/app/src/main/scala/app/state/GlobalError.scala`
- `modules/app/src/main/scala/app/state/TreeBuilderState.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/errors/WireErrorSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/TreeWriteConcurrencyItSpec.scala` (new)

The SPA files are provisional pending Decision 4. The exact set of app-side
files must be re-derived by grep before approval, per the discovery-first
inventory practice.

This list is stated against the error path as it now is: the status mapping
lives in `WireError.fromAppError` and is pinned by `WireErrorSpec`, so those are
the files a new 409 touches. It must still be re-derived before approval,
because `PLAN-ERROR-WIRE-BOUNDARY.md` is not finished.

---

## 5. ADR alignment

| ADR | Bearing | Alignment |
|---|---|---|
| 001 (Iron/smart constructors) | `expectedRevision` | Compliant: `CommitHash` is an existing refined type with a Tapir codec; no raw `String` in any signature |
| 004a (persistence) | Write path | Compliant under Decision 2 = A: still one atomic `set_tree`; the precondition is a read before it. Under Decision 2 = B this needs re-review — a scratch branch is new storage behaviour |
| 007 (branching/merge) | Decision 2 = B only | Under Option B, scratch-branch creation must not collide with DD-5's rule that scenario-shaped branches are created only by `ScenarioService.create` |
| 010 (errors) | `VersionConflict` | Compliant: typed error channel, no exceptions |
| 017 (+NOTES) (tree API) | The PUT DTO | **Amendment required**: ADR-017 describes the update DTO's buckets; a required `expectedRevision` is a new element of that contract and belongs in the ADR |
| 030 (authz orchestration) | Controller | Compliant: no change to `Checked[Permission]` propagation |
| 032 (equality relations) | The token | Compliant: `expectedRevision` is a storage-side commit identifier, not a domain content hash. The two must not be conflated — see the R2 correction |
| 035 (error leakage) | 409 body | Compliant, and structurally rather than by discipline: `WireError.Conflict` carries a `ConflictKind` drawn from a closed set and no free text, so no internal value can reach the body through it. `VersionConflict` already has its projection arm; a **new** error variant would need one, and the exhaustive match would fail to compile without it |
| 036 (confidential identifiers) | The token | Compliant: `CommitHash` is already client-visible input and output; it is not a confidential identifier |
| 038 (response wire shapes) | Decision 5 Option A only | If the commit travels in the `/structure` response, that response gains a field and ADR-038 decides its shape: the two permitted response kinds, and no raw primitive where a refined type exists. `ScenarioSummaryResponse.head: CommitHash` is the precedent to follow |
| 039 (trust-boundary ingress) | `expectedRevision` on the way in | Compliant by construction: the token is a `CommitHash` refined by its Tapir codec at the request boundary, so the write path receives a validated value and never a `String` |

---

## 6. Verification plan

1. **Unit — `RiskTreeServiceLiveSpec`.** An update whose `expectedRevision`
   matches the head succeeds. An update whose `expectedRevision` is a different
   commit fails with `VersionConflict` and leaves the stored tree untouched.
2. **Unit — `WireErrorSpec`.** `VersionConflict` projects to
   `WireError.Conflict(ConflictKind.StaleVersion())`, which the `oneOf`
   declaration answers at 409. The assertion already exists as one row of that
   suite's exhaustive case-to-wire mapping; what this plan adds is the first
   production constructor behind it.
3. **Integration — new `TreeWriteConcurrencyItSpec`.** Against real Irmin:
   client A and client B both read at C0; A updates successfully; B's update
   with `expectedRevision = C0` is rejected; the tree at head still carries A's
   edit. This is the test that would have caught R1.
4. **Regression.** The existing mitigation carry-over tests must stay green —
   the version check runs before the carry-over, so a rejected update must not
   write mitigations either.
5. **Full suite.** `commonJVM/test`, `server/test`, `app/test`, `serverIt/test`,
   with the `register_it_` network cleanup before the Docker tier. Pass/fail
   only.

---

## 7. Elevation gate (before any implementation)

This document confers **no** G3 coverage. To build it:

0. Re-read the error path before anything else — see the remark at the top of
   this document. `PLAN-ERROR-WIRE-BOUNDARY.md` rewrote it and is unfinished, so
   every error type, status mapping and client classification named here is
   stated against a moving target. Check Decision 1's and Decision 5's premises
   against §1.3 in the same pass: both argue from costs that the scenario
   deletion path has since paid.
0a. Run the output-header check named under Decision 5 — declare
   `.out(header[CommitHash]("ETag"))` on one endpoint and compile. It decides
   Decision 5, and through it Decision 1, so it comes before either is ruled.
1. Rule Decisions 1–5.
2. Rewrite §3 against those rulings and present it as a Signature Echo,
   settling the two points §3 names: `VersionConflict`'s field shape, which
   `PLAN-ERROR-WIRE-BOUNDARY.md`'s `EWB-D-29` typed as a guess for this plan to
   validate, and whether
   `repo.update` keeps `op: RiskTree => RiskTree`.
3. Re-derive the `## File inventory` by grep, per the discovery-first practice —
   the app-side entries are provisional pending Decision 4.
4. Complete the ADR alignment (§5), including the ADR-017 amendment that a
   required `expectedRevision` implies.
5. Confirm the verification plan (§6) and run every tier green.
6. Present the elevated plan and obtain an accepted signal.

Appendix Option B is a second elevation, not part of this one. Its scope is the
diff and merge functions, conflict granularity, and the post-merge client
experience; none of that is designed yet.

## 8. Out of scope

- `revertTree`'s no-precondition semantics, which is an existing explicit
  ruling.
- Tree `delete`.
- Any merge or three-way reconciliation of conflicting edits. This plan makes a
  conflict visible and refuses the write; it does not resolve it.
- The `op: RiskTree => RiskTree` contract question, unless the Signature Echo
  folds it in.
