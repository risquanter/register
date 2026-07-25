# PLAN — Phase C machinery refactor ("C-refactor", Scope 1)

Status: PRESENTED 2026-07-25 (amended 2026-07-25: `## File inventory` heading
made hook-readable; three `IrminClient` test stubs added to Task A;
`loadTree` shared-helper scope clarified), awaiting approval. Scope 1 of the milestone-2b
close-out; companion (Scope 2, depends on this plan): `PLAN-PHASE-E-HISTORY.md`.
Decision context, rulings E1–E7, and fixed security constraints live in the
companion document.

Two self-contained, independently landable and revertable tasks. Disjoint
modules (A: server, B: app); either order; both precede Scope 2.

---

## Task A — read-path consistency (server, internal only)

### Problem

Recorded in `PLAN-PHASE-E-HISTORY.md` → "Problem class on record": a tree
load is several independent GraphQL queries (meta `get`, node `list`,
per-node `get`s), each dereferencing the mutable branch head independently.
A commit landing mid-load produces a torn read (meta from one commit, nodes
from another). Writes are atomic since DD-7; reads are not.

### Design

Resolve the branch head **once per load**, then perform every constituent
read pinned to that commit. Public repository signatures are unchanged;
the fix is contained inside the Irmin implementation. The in-memory
repository (no commit concept) is untouched.

The resolve-head-once logic lands in the shared private helper `loadTree`,
which today backs `getById` and `getTreeWithMeta` (the pre-write read in
`update` and `delete`). Routing all three through the same head-pinned
`loadTreeAt` gives them a consistent snapshot from one change instead of
duplicating the fix per caller. `getAllForWorkspace` cannot reuse the
per-tree helper as-is: it must resolve ONE head for the whole listing (the
tree enumeration plus every tree load), so it resolves the head itself and
calls `loadTreeAt` per tree.

Scope boundary: `update`/`delete` stay a non-atomic read-modify-write (no
compare-and-set on the following `set_tree`), so this task makes their read
consistent but adds no lost-update protection; write-side optimistic
concurrency is a separate concern, out of scope.

Behaviour preservation rule: a read on a nonexistent branch returns
`None`/empty today and MUST continue to do so — head resolution yielding
"branch absent" maps to the same result, never to a new error.

### Signatures

```scala
// modules/server/.../infra/irmin/IrminQueries.scala — NEW
/** List a subtree's entries as of a specific commit (commit(hash:).tree). */
def listTreeAtCommit(commitHash: CommitHash, path: IrminPath): String
// Body: same field selection as the existing listTree, rooted at
// commit(hash:){ tree { ... } } — selection copied verbatim at implementation.

// modules/server/.../infra/irmin/IrminClient.scala — NEW op (trait + companion accessor)
def listAtCommit(commit: CommitHash, path: IrminPath): IO[IrminError, List[IrminPath]]

// modules/server/.../infra/irmin/model/IrminResponses.scala — NEW envelope
// CommitTreeListResponse — mirrors the existing branch-list envelope rooted at
// commit.tree; exact field names copied from that envelope at implementation.

// modules/server/.../repositories/RiskTreeRepositoryIrmin.scala — private helpers;
// PUBLIC TRAIT SIGNATURES UNCHANGED
private def resolveHead(branch: BranchRef): Task[Option[CommitHash]]
  // via irmin.getBranch; None = branch absent (preserves today's semantics);
  // head hash refined through CommitHash.fromString (invalid → RepositoryFailure)
private def loadTreeAt(wsId: WorkspaceId, id: TreeId, at: CommitHash): Task[Option[TreeWithMeta]]
private def readNodesAt(prefix: IrminPath, at: CommitHash): Task[Seq[RiskNode]]
// loadTree (shared by getById, update, delete): resolveHead once → *At reads.
// getAllForWorkspace resolves ONE head for the whole listing (list + every tree).
```

Scope 2 later exposes `loadTreeAt` through a public commit-keyed read
(`Revision` model, see companion plan); this task deliberately builds the
internals it will wrap.

### Files (Task A)

Enumerated in the consolidated `## File inventory` section below (Task A
subgroup); that H2 section is the only one the enforcement hook reads.

### Tests (Task A)

- `RiskTreeReadConsistencySpec` (NEW, deterministic, no Docker): scripted
  stub `IrminClient` (implements the full trait, including the new
  `listAtCommit`) whose branch state advances between the meta read and the
  node reads. Pins: (1) the loaded tree is entirely pre-advance state
  (the torn read is impossible by construction); (2) exactly one head
  resolution per load; (3) nonexistent branch → `None` (behaviour
  preservation); (4) meta absent but nodes present at the resolved commit →
  `RepositoryFailure` (behaviour preservation of today's `loadTree`).
- `IrminClientIntegrationSpec`: `listAtCommit` returns the listing as of an
  older commit after the path has since changed.

---

## Task B — compare-slot coordinate generalization (app, client only)

### Design

A compare slot already bundles an independent source (own `TreeViewState`,
curve cache, palette family, changed-nodes state); its coordinate is
currently just a branch. Widen the coordinate to `(tree, branch)` so a slot
can point at a different tree — enabling cross-tree curve comparison — with
the revision pin (`at`) added by Scope 2 as a third component.

- **Identity vs value comparison rule** (settled): ✎ changed-node markers
  are requested only when the slot's tree equals the active tree (shared
  node lineage); cross-tree slots never call the changed-nodes endpoint —
  no lineage, no markers. Chart comparison is selection-driven and
  lineage-free: same-tree slots keep today's per-node ID pairing; cross-tree
  slots contribute the plain union of their selected curves as independent
  series.
- **Series ids** become slot-scoped (stable slot identity, not branch
  name). This supersedes the design basis of the deferred "compare-mode
  hover bridge" backlog item; hover behaviour itself is NOT changed in this
  task (overlay hover stays non-resolving exactly as today — no regression,
  backlog item stays open, retargeted).
- **Palette**: `BranchPaletteState` storage and assignment stay branch-keyed
  (user preference per branch; storage key format unchanged). New display-
  level rule: two slots resolving to the same family (now possible: same
  branch, different trees) — the later slot takes the first unassigned
  family for display only, never persisted. Pure helper + test.
- **Slot picker** gains a tree selector (options: the workspace's trees;
  default: the tab's active tree — today's behaviour).

### Signatures

```scala
// modules/app/.../state/CompareState.scala
// (amended 2026-07-25, user rulings: tree is an optional override that
//  follows the tab's active tree by default; the branch feed is DERIVED
//  from target — the hand-synced chosenBranch Var and its transaction-lag
//  guard in AnalyzeView are removed, not widened)
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId])
//   treeOverride: None = follow the tab's active tree (today's behaviour);
//                 Some(t) = pin the slot to tree t (cross-tree compare)
enum CompareTarget:
  case NotChosen
  case Target(coordinate: SlotCoordinate)
// extension toChoice: Option[BranchChoice] → toCoordinate: Option[SlotCoordinate]

// CompareSlotState: chosenBranch Var + its target.changes sync DELETED.
// The slot's branch feed becomes a derived signal made strict at the one
// seam that needs .now() (TreeViewState's StrictSignal param):
//   target.signal.map(branchOf).observe(unsafeWindowOwner)   // in Main
// The slot's effective tree is a combined signal (Option 1):
//   activeTreeId.combineWith(target.signal).map((active, t) =>
//     t.toCoordinate.flatMap(_.treeOverride).orElse(active))
// driving the existing selectTree/deselectTree on the slot's TreeViewState;
// the chosen == synced guard in AnalyzeView is deleted (its cause — the
// hand-sync lag — no longer exists).

// modules/app/.../chart/CompareColorAssigner.scala
final case class OverlaySide(curves: Map[NodeId, LECNodeCurve], palette: Vector[HexColor], slotLabel: String)
// (branchLabel → slotLabel; series id s"${nid.value}@${s.slotLabel}";
//  labels: "active", "s1", "s2" — stable slot identity)

// modules/app/.../state/BranchPaletteState.scala
// resolve(...) gains the slot-de-collision rule above (pure, display-only)
```

### Files (Task B)

Enumerated in the consolidated `## File inventory` section below (Task B
subgroup); that H2 section is the only one the enforcement hook reads.

### Tests (Task B)

- `CompareColorAssignerSpec`: slot-label series ids; cross-tree union (no
  pairing, one series per selected curve); same-tree pairing unchanged.
- `BranchPaletteStateSpec`: slot family de-collision (same branch on two
  slots → distinct display families; persistence untouched).
- `CompareStateSpec` (NEW): coordinate fallback; slot-identity stability
  under choose/clear (existing documented rule, now pinned).

---

## File inventory

The enforcement hook authorizes gated edits only from bullet lines in this
H2 section (up to the next `## ` heading). Approving the plan (token → this
document) authorizes every file below; Tasks A and B still land and revert
independently.

### Task A — read-path consistency (server)

- modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala
- modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala
- modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala
- modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala
- modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeReadConsistencySpec.scala (NEW)
- modules/server/src/test/scala/com/risquanter/register/http/controllers/ScenarioControllerSpec.scala (add listAtCommit to inline IrminClient stub)
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioServiceLiveSpec.scala (add listAtCommit to inline IrminClient stub)
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceSpec.scala (add listAtCommit to inline IrminClient stub)
- modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala (add listAtCommit case)

### Task B — compare-slot coordinate generalization (app)

- modules/app/src/main/scala/app/state/CompareState.scala
- modules/app/src/main/scala/app/state/BranchPaletteState.scala
- modules/app/src/main/scala/app/chart/CompareColorAssigner.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala
- modules/app/src/test/scala/app/state/BranchPaletteStateSpec.scala
- modules/app/src/test/scala/app/state/CompareStateSpec.scala (NEW)

### Versioning (both tasks)

- build.sbt (ThisBuild / version bump on landing: Task A PATCH, Task B MINOR; APP_VERSION mirrored to .env and .env.irmin, which are ungated and need no bullet)

## ADR alignment

- Nominal types only (`TreeId`, `BranchChoice`, `CommitHash`, `IrminPath`) —
  no raw `String`/primitive domain parameters anywhere, private helpers
  included (adr-constraints amendment 2026-07-24).
- No wire/API change in either task → no endpoint, DTO, or codec edits; the
  correct-by-construction boundary rules are untouched.
- DD-10 error model unchanged (behaviour-preservation rule above forbids
  new error paths on reads).

## Open decisions

None blocking. Trivial defaults taken: slot labels "active"/"s1"/"s2";
de-collision picks the first unassigned family in palette declaration order.

## Verification

- `sbt server/compile`, `sbt "server/testOnly *RiskTreeReadConsistencySpec"`,
  `sbt server/test` (Task A)
- `sbt "serverIt/testOnly *IrminClientIntegrationSpec"` (Task A; needs
  `local/irmin-prod:3.11-p1`)
- `sbt app/test` (Task B)
- Manual (Task B): compare flows of §6 unchanged for same-tree slots
  (overlay, side-by-side, cards, markers, colour picker); new: pick a
  different tree in a slot → union chart, no markers, distinct families.
- Pass/fail reporting only.

## Versioning

Task A: PATCH (bug fix — torn reads). Task B: MINOR (new user-visible
feature — cross-tree comparison). Bump on landing of each, mirrored to
`.env` and `.env.irmin`.

## Post-landing cleanup item — synchronous reads in the reactive layer

Queued 2026-07-25 (user ruling), from the Task B design review. Review/plan
work, executed after this plan lands — under its own plan document or folded
into the Phase E post-landing sweep (`TreeViewState` and siblings are in that
inventory). Not part of this plan's gated file inventory.

### Findings that motivated it

The app has ~131 `.now()` synchronous reads. They are two distinct
populations:

1. **Event-handler reads** (submit gathers form fields, dirty-checks,
   confirm dialogs): sanctioned Laminar usage, ergonomically right, keep.
   The `StrictSignal` interface convention serves this population well and
   is NOT under revision.
2. **Synchronous reads inside the reactive layer itself** — the actual
   cleanup target:
   - Accessor thunks passed across state-class boundaries
     (`branchAccessor = () => activeBranch.now()`; `TreeViewState` calling
     `.now()` internally, which is what forces its `StrictSignal` param).
     A thunk read escapes the Airstream transaction and can observe state
     mid-propagation.
   - `.now()` inside `.map` — `RiskLeafFormView.scala` documents a
     "combine-as-trigger, read-via-`.now()`-inside-map idiom" (also used by
     `draftSignal`), which is a synchronous read inside a derivation and
     sits in tension with ADR-019's own rule.
   - Hand-synced `Var`s bridging derived→strict (`chosenBranch`, removed by
     Task B).

Empirical cost already paid: the `chosen == synced` transaction-lag guard in
`AnalyzeView` exists only to compensate for hand-sync lag — a workaround for
a race the pattern itself creates. Task B deletes that guard along with the
hand-synced Var.

### Cleanup scope

- Replace cross-boundary accessor thunks with in-transaction sampling
  (`withCurrentValueOf` at the use site) or plain signal parameters.
- Fix the `.now()`-inside-`map` idiom sites (`RiskLeafFormView`,
  `DistributionChartState` and any others a sweep finds).
- Tighten ADR-019's rule from "never call `.now()` in a rendering pipeline"
  to also cover any signal derivation and any accessor passed across a
  state-class boundary — the drift happened inside the permitted space, so
  the rule needs to name it.
- Revisit `TreeViewState.activeBranchSignal: StrictSignal` specifically:
  after Task B its natural caller holds a derived branch, so accepting
  `Signal` and observing internally may fit better there. Per-seam decision,
  not a convention flip.

### Governing principle (user ruling 2026-07-25)

Choose the best engineering option in each case. New or revised
recommendations are welcome even in ADRs when a corner case needs one or a
past decision no longer holds. Architectural stance: fix the foundations;
when the cost of the fix is too big right now, schedule it — but schedule
it. Established patterns are never dogma; do not let a dogmatic reading of
an existing convention block a genuine improvement.
