# DONE — Milestone-2b Phase E: History / Time Travel (Scope 2)

Status: COMPLETE. Prerequisites landed: `DONE-PLAN-C-REFACTOR.md` (Scope 1) and
`DONE-PLAN-COMPARE-UI-REDESIGN.md` (slot-card Analyze layout §8 builds on). All
Phase E decisions are ruled (E1–E8 below, H1–H6 in §8).

**Landed and committed:** Slice 0 (backend §1–§7 — revision reads, X-Branch,
revert, history; commit `1a69dec`, 0.10.0), Slice E-A (Analyze history slider,
§8b), and continuations §C2 (live time-scrubbing + per-slot palette) and §C3
(compare-history 3a/3b; commit `30cc365`, 0.10.3).

**Landed (uncommitted):** §C1 — per-branch tree uniqueness (0.10.16). `create`
and `update` now check tree-ID and tree-name uniqueness against the head of the
branch being written; unit tests plus one HTTP-level fork-inheritance IT cover it.

**Carried out of this plan for its own design pass:** Slice E-B — Design-view
history (the §7 "Slice E-B — Design" slider, read-only pinned mode, and
fork/revert UI). It is not built here; it moved to `TODO.md` §43 to be
redesigned before implementation. The §7/§8 material below is its provisional
spec, kept for reference by that TODO item. Closing Phase E does not depend on
it.

**Slice 0 (backend, §1–§7) IMPLEMENTED 2026-07-27 (committed, `1a69dec`).** All modules
compile (`sbt compile` green); `server/test` and `app/test` pass. The 3 new IT
specs (`TreeRevertItSpec`, `PinnedReadAuthorizationItSpec`,
`IrminRevertSemanticsSpec`) are authored and compile (`serverIt/Test/compile`
green) — running them needs the `local/irmin-prod:3.11-p1` image (user-run).
Rulings applied this pass: repo revert Option A; `NodeDiff*`→`NodeChange*`
rename; `collectAllTrees` preserved on main (per-branch-uniqueness fix deferred
to a separate Fable→Opus follow-up); history/changed-nodes permission =
`ViewTree` (matches the controller's other tree reads, not the §2 `ViewWorkspace`
mention); SSE `CacheInvalidated.branch` typed `BranchChoice` and `nodeIds`
typed `List[NodeId]` (user-requested typing fixes). Version bumped to 0.10.0
(build.sbt + .env + .env.irmin). Doc sweep was surgical (ADR-032, ADR-004a
header) — historical records (TODO.md, plan docs, ADR-006/007 proposals) left
intact because a literal rename there would either falsify old semantics
("X-Branch absent = main") or corrupt proposal-internal types. Slice 0 has since
been committed by the user.

**Slice E-A (Analyze slider, §8b) IMPLEMENTED 2026-07-27 (committed).**
`commonJVM/test`, `server/test`, `app/test` all pass; `app/compile` warning-free.
Ordering needed no code (getHistory is oldest-first ancestry, pinned by
`RiskTreeRepositoryIrminSpec`). Where implementation refined §8b: `SlotCoordinate.at`
carries a `= None` default (fewer call-site edits, satisfies "default None");
`HistorySlider.selected` takes `Signal` not `StrictSignal` (only `.map` used, no
`.observe` at call sites); `TreeDetailView` keeps a strict `lockedNow` mirror of
the widened `Signal[Boolean]` lock so the click handler reads it synchronously;
the baseline `TreeHistoryState` is a new `AnalyzeView.apply` param (built in
`Main`); the H3 dropped-selections notice renders on the baseline chart surface
(comparand side-by-side notice deferred). One behaviour change to flag: the ✎
changed-node diff gate now fires for same-branch-different-`at` (past-vs-current
on one branch when a stop is rewound), not only cross-branch — a direct
consequence of `at` joining pair identity (decision 2). Slice E-B (Design slider
+ fork/revert UI) moved to `TODO.md` §43 for its own design pass.

## Rulings (2026-07-25, user)

- **E1** History entries are typed, parsed server-side; raw commit messages
  (which embed the WorkspaceId) never leave the server.
- **E2** Uniform revision model: reads address `(branch, optional commit
  pin)`; branch is one way to obtain a commit; inner reads are commit-keyed.
- **E3** Revert = ordinary forward write (read tree at target commit, write
  as one new `set_tree` commit). Native head-set `revert` mutation stays out
  of production; a probe test pins its head-set semantics.
- **E4** No revert precondition; last write wins like every tree write;
  explicit confirm in UI; superseded edits remain recoverable in history.
- **E5** Scenario create takes a REQUIRED discriminated `source`:
  `{"type":"branch","name":…}` | `{"type":"commit","hash":…}`; main is
  referenced as a branch; no implicit default; `forkOf` field replaced.
- **E6** Diff family renamed to changed-nodes (`ChangedNodesService`,
  `getChangedNodes`, `/changed-nodes`, `ChangedNodesResponse` /
  `NodeChangeEntry`, SPA `ChangedNodesState`).
- **E7** DD-8 wire amendment: the branch header is REQUIRED and renamed
  `X-Branch`; value `"main"` or a scenario name; absence → 400. Ripples:
  DD-22 SSE branch tag becomes explicit; internal
  `branch: BranchRef = BranchRef.Main` default parameters are removed.

## Fixed constraints (settled during decision review)

### Authorization of commit-pinned reads: path scoping, not commit provenance

All workspaces share one Irmin store, so any commit's tree physically
contains other workspaces' subtrees. Pinned reads MUST build their Irmin
paths from the authenticated workspace exactly as branch reads do (wsId
resolved server-side from `WorkspaceKeySecret`, never client input). The
server does NOT verify a commit lies on the active branch's history —
security-reviewed 2026-07-25: every commit's view of a workspace's subtree
equals a prior state produced by that workspace's own authenticated writes,
so membership verification adds no confidentiality and costs a history walk.
Pinned by test: `PinnedReadAuthorizationItSpec` (workspace A reads pinned to
a commit produced by workspace B's activity → only A's own data or
not-found).

### Commit-existence oracle constancy

"Commit not found" and "path absent at that commit" are indistinguishable:
both surface as the endpoint's ordinary not-found shape (`None` body for
structure, empty result otherwise) — mirrors the A13 precedent.

### Boundary validation

Every new wire input decodes through Iron at the Tapir boundary:
`CommitHash` (`^[a-f0-9]{40}$`, GraphQL-interpolation-safe — verified),
`BranchChoice.fromWireString`, `ScenarioName`. Invalid → 400 before any
handler.

### Vocabulary rule: branch vs scenario (settled 2026-07-25)

Operations where main is a legal operand (branch header, history, revert,
changed-nodes sides, fork source, pinned reads) speak **branch**
(workspace-relative name: `"main"` or scenario slug). Operations where main
is structurally excluded (scenario create/list/delete/merge) speak
**scenario**. The internal workspace-prefixed `BranchRef` never crosses the
client boundary. Corollary: `"main"` is reserved as a scenario name
(rejected 400 at the boundary; verified absent today — charset-only
validation).

### Problem class: non-atomic multi-query reads

Recorded here 2026-07-25; addressed by Scope 1 Task A (see
`DONE-PLAN-C-REFACTOR.md`). Scope 2 builds on the commit-pinned internals it
introduced.

### Functional note (by design)

Tree membership is checked against the CURRENT workspace record; a tree
deleted from the workspace is not viewable at historical commits. Deliberate.

---

## Design and signatures

### 1. Wire foundations (E7 + revision model)

```scala
// modules/common/.../http/endpoints/BaseEndpoint.scala
// REPLACES activeBranchHeader / activeBranchHeaderDescribed:
val branchHeader: EndpointInput[BranchChoice] =
  header[String]("X-Branch")
    .description("Target branch: \"main\" or a scenario name. Required.")
    .mapDecode(BranchChoice.fromWireString)(_.wireName)
// Required header: absence fails decoding → 400. Every endpoint currently
// using activeBranchHeader migrates (WorkspaceTreeEndpoints,
// WorkspaceAnalysisEndpoints, WorkspaceQueryEndpoints,
// WorkspaceLifecycleEndpoints).

// modules/common/.../domain/data/iron/OpaqueTypes.scala
// BranchChoice companion gains:
def fromWireString(s: String): sttp.tapir.DecodeResult[BranchChoice]
  // "main" → Main; otherwise ScenarioName.fromString → Scenario(name)
def wireName(choice: BranchChoice): String  // Main → "main"; Scenario(n) → n.value
// (exposed as an extension or method — exact Tapir DecodeResult plumbing
// finalized at implementation, semantics fixed here)

// ScenarioName.fromString additionally rejects the literal "main"
// (reserved; ValidationErrorCode.INVALID_FORMAT, message names the
// reservation).

// NEW, co-located with BranchRef/CommitHash (§11 co-location):
enum Revision:
  case Head(branch: BranchRef)
  case At(commit: CommitHash)
```

`Revision` is the service/repository read coordinate: `Head` is resolved to
a commit exactly once inside the Irmin repository (Scope 1 `resolveHead`);
`At` is used directly. The in-memory repository serves `Head` from current
state and fails `At` with `ValidationFailed` ("point-in-time reads require
the Irmin backend") — scenario/history UI is disabled on the in-memory
backend (DD-9), so the path is unreachable in normal operation.

```scala
// modules/server/.../http/controllers/ActiveBranch.scala
// resolve(wsId, choice) keeps its BranchChoice → BranchRef mapping; the
// Option-handling arm is deleted (header now required).
```

### 2. History endpoint (E1)

```scala
// modules/common/.../http/endpoints/WorkspaceTreeEndpoints.scala — NEW
val getTreeHistoryEndpoint =
  authedBaseEndpoint.tag("workspaces").name("getTreeHistory")
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "history")
    .get.in(branchHeader)
    .in(query[Int]("n").default(50).validate(Validator.min(1)))
    .out(jsonBody[TreeHistoryResponse])

// modules/common/.../http/responses/TreeHistoryResponse.scala — NEW file
enum HistoryOperation:          // string-encoded JSON codec
  case Create, Update, Delete, Merge, Revert, Other
final case class TreeHistoryEntry(commitHash: CommitHash, operation: HistoryOperation, at: String) // ISO-8601
final case class TreeHistoryResponse(entries: List[TreeHistoryEntry])

// modules/server/.../services/TreeHistoryService.scala — NEW (trait + Live + companion)
trait TreeHistoryService:
  def history(wsId: WorkspaceId, treeId: TreeId, branch: BranchRef, limit: PositiveInt)
    (using Checked[Permission]): Task[List[TreeHistoryEntry]]
// Live: irmin.getHistory(treeRoot(wsId,treeId), limit, branch); a private
// message parser maps the commit-message convention
// (workspace:{ws}:risk-tree:{id}:create|update|delete|revert,
//  workspace:{ws}:merge-scenario:{name}) to HistoryOperation; anything
// unrecognized → Other. Raw message and author never leave the service.
```

Permission: ViewWorkspace. Handler in `WorkspaceTreeController`.

### 3. Pinned reads (E2)

```scala
// Endpoints: structure, LEC-multi, prob-of-exceedance each gain
.in(query[Option[CommitHash]]("at"))
// Controllers build: val rev = at.fold(Revision.Head(branchRef))(Revision.At(_))

// modules/server/.../services/RiskTreeService.scala — reads take Revision;
// writes keep BranchRef; ALL `= BranchRef.Main` defaults removed (E7):
def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Permission]): Task[Option[RiskTree]]
def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision): Task[Map[NodeId, LECNodeCurve]]
def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long,
    seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision): Task[Double]
def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Permission]): Task[RiskTree]
def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Permission]): Task[RiskTree]
def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Permission]): Task[RiskTree]

// modules/server/.../repositories/RiskTreeRepository.scala — reads:
def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): Task[Option[RiskTree]]
def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): Task[List[Either[RepositoryFailure, RiskTree]]]
// writes keep branch: BranchRef (defaults removed).
// Irmin impl: Head → resolveHead → loadTreeAt; At → loadTreeAt (commit or
// path absent → None: oracle constancy). InMemory: At → ValidationFailed.
```

### 4. Changed-nodes endpoint (E6 rename + symmetric revisions)

```scala
// modules/common/.../http/endpoints/WorkspaceTreeEndpoints.scala —
// REPLACES getScenarioDiffEndpoint:
val getChangedNodesEndpoint =
  authedBaseEndpoint.tag("workspaces").name("getChangedNodes")
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "changed-nodes")
    .get                                     // NO branch header (symmetric explicit sides)
    .in(query[BranchChoice]("a")).in(query[Option[CommitHash]]("aAt"))
    .in(query[BranchChoice]("b")).in(query[Option[CommitHash]]("bAt"))
    .out(jsonBody[ChangedNodesResponse])
// BranchChoice query codec = same fromWireString mapping as the header.

// modules/common/.../http/responses/ScenarioDiffResponse.scala
//   → RENAMED FILE ChangedNodesResponse.scala:
//   ScenarioDiffResponse → ChangedNodesResponse, NodeDiffEntry →
//   NodeChangeEntry (field shapes copied verbatim; only names change).

// modules/server/.../services/ScenarioDiffService.scala
//   → RENAMED FILE ChangedNodesService.scala:
trait ChangedNodesService:
  def changedNodes(wsId: WorkspaceId, treeId: TreeId, a: Revision, b: Revision)
    (using Checked[Permission]): Task[ChangedNodesResult]   // ScenarioDiffResult renamed
// Live resolves each side once (repository), then the existing commit-keyed
// hash comparison. Same-tree constraint retained (node-ID lineage; the
// falsifier — an ID-preserving tree duplication feature — reopens the
// signature to two (tree, revision) coordinates).
```

Compare-to-current from history = `a = active branch (head)`,
`b = active branch, bAt = <hash>`.

### 5. Revert (E3/E4; granularity = E8, ruled: per-tree)

```scala
// modules/common/.../http/requests/RevertTreeRequest.scala — NEW file
final case class RevertTreeRequest(toCommit: CommitHash)

// modules/common/.../http/endpoints/WorkspaceTreeEndpoints.scala — NEW
val revertTreeEndpoint =
  authedBaseEndpoint.tag("workspaces").name("revertTree")
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "revert")
    .post.in(branchHeader).in(jsonBody[RevertTreeRequest])
    .out(jsonBody[SimulationResponse])       // a write, like update

// modules/server/.../services/RiskTreeService.scala — NEW
def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)
  (using Checked[Permission]): Task[RiskTree]
// Live: reads head (for invalidation), then repo.revert; absent target →
// NotFound. No precondition (E4). Invalidation via the shared write path:
// revertTree calls invalidationHandler.handleMutation(oldHead, reverted) — the
// same call update uses — and skips it when oldHead is None (revert recreates a
// deleted tree, which behaves like create: nothing cached to invalidate).
// Permission DesignWrite.

// modules/server/.../repositories/RiskTreeRepository.scala — NEW (ruled 2026-07-26, Option A)
def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): Task[RiskTree]
// Irmin: loadTreeAt(wsId, id, toCommit) — absent (commit or path) → NotFound
// (oracle constancy) — then writeTree with meta.copy(updatedAt = now,
// schemaVersion = CurrentSchemaVersion) and message
// workspace:{ws}:risk-tree:{id}:revert. Loading at the commit internally
// preserves the tree's original createdAt and handles reverting a tree that was
// deleted at head. One set_tree commit (DD-7), forward write only (E3), the
// tree's own subtree only (E8). InMemory: ValidationFailed (point-in-time reads
// require the Irmin backend), unreachable in normal operation (DD-9).
```

**E8 (RULED 2026-07-25: per-tree, option a).** Revert restores tree T's
subtree at the chosen commit; other trees are untouched. Rationale: history
is browsed per-tree, so a whole-branch revert would silently restore trees
the user never looked at (any commit carries the whole workspace state), and
the whole-workspace time-travel use case is already served non-destructively
by fork-at-commit (E5). Consistent with the established revert semantics
(DD-7: one action, one forward set_tree commit). The signatures above are
final. Reopens only if cross-tree references are ever introduced.

### 6. Scenario create source (E5) + name reservation

```scala
// modules/common/.../http/requests/ScenarioRequests.scala
sealed trait ScenarioSourceDto
object ScenarioSourceDto:
  final case class Branch(name: BranchChoice) extends ScenarioSourceDto  // "main" | scenario slug
  final case class Commit(hash: CommitHash) extends ScenarioSourceDto
// zio-json discriminated codec, discriminator "type": "branch" / "commit".
final case class CreateScenarioRequest(name: ScenarioName.ScenarioName, source: ScenarioSourceDto)
// forkOf REMOVED (pre-1.0 breaking change, one SPA consumer).

// modules/server/.../services/ScenarioService.scala
enum ScenarioSource:
  case Main
  case ForkOf(scenario: ScenarioName.ScenarioName)
  case AtCommit(commit: CommitHash)                          // NEW
// create signature unchanged; default argument removed (E7 ripple).
// Controller maps Branch(Main)→Main, Branch(Scenario(n))→ForkOf(n),
// Commit(h)→AtCommit(h).
// ScenarioServiceLive: AtCommit → irmin.getCommit verifies existence
// (absent → NotFound), then the existing CAS create at that hash.
```

### 7. SSE branch tag (DD-22 — implement, explicit from birth)

Finding (verified 2026-07-25): DD-22 (closed 2026-07-19, "branch tag in
event payload, lands in Phase B") was never implemented — `SSEEvent` has no
branch field and no SSE file mentions branches. Phase B shipped without it
(the over-notification it fixes is documented harmless). Scope 2 implements
it now, E7-consistent from birth: the tag is a REQUIRED field carrying
`"main"` or the scenario name — no optional-absent-means-main state ever
exists. Files: `SSEEvent.scala` (field on the invalidation event case
class), `InvalidationHandler.scala` (publisher supplies it), SPA consumer
filters events to the tab's branch.

### 8. SPA — history slider (re-specified 2026-07-26 against the landed compare-UI redesign)

The originally planned Tree | History right-panel tab (UI plan §7) is
replaced: the redesigned Analyze panel is a stack of self-contained slot
cards (baseline + comparands), so history browsing attaches to each slot as
a **history slider** instead of living in a separate panel. All rulings
below are user rulings, 2026-07-26.

- **H1 Slider model.** Each Analyze slot card gains a slider row under its
  picker row. The stops are the commits of the slot's (branch, effective
  tree) from `getTreeHistoryEndpoint` — discrete, index-spaced (one notch
  per commit, even spacing regardless of wall-clock gaps). Right edge =
  branch head ("latest"), left edge = oldest fetched commit ("initial").
  Every pick displays timestamp + short commit hash (handle tooltip/popup)
  — enough identifying data that the same point in time can be picked from
  either view's slider.
- **H2 Pinned reads.** `SlotCoordinate` (Scope 1) gains
  `at: Option[CommitHash]` — `None` = head (live, today's behaviour),
  `Some(hash)` = rewound. A rewound slot re-issues its structure and curve
  fetches with the `at` pin: the hierarchy re-renders from the pinned
  structure and the selected nodes' curves are the ones that existed at
  that commit.
- **H3 Missing nodes.** A selected node absent at the pinned commit is
  dropped from the chart (no greyed placeholder — there is no value to
  show). A transient notice lists the dropped selections ("not present at
  this point in time") so a vanished curve is explained, not silent.
- **H4 Read-only at rewind.** Any slider position other than head makes
  the slot read-only: selection editing locked (reuses the existing
  `selectionLocked` mechanism), pinned banner naming timestamp + hash. At
  the head the slot is live and behaves exactly as today.
- **H5 Both views get the slider; fork lives in Design.** Design gains the
  same slider under its tree selection. Rewinding pins DesignView
  read-only: tree detail + forms display the pinned state with all edit
  affordances disabled and the pinned banner shown. The **fork button**
  sits in Design next to the slider — greyed at head, active when rewound;
  it prompts for a `ScenarioName`, creates with `source = Commit(hash)`
  (E5), switches the tab's branch to the new scenario, and leaves the user
  editing in Design at exactly the inspected state. This supersedes UI-plan
  principle 4 (fork-in-Analyze): that placement existed only to avoid
  building a read-only Design mode, which now exists for value inspection
  in its own right.
- **H6 Revert unchanged.** Stays in Design: BranchBar scenario-menu item
  "↩ Revert this branch…" enabled; NEW `TreeRevertState` + NEW
  `RevertModal` (destructive confirm naming branch and target commit —
  MergeModal pattern).

Components/state:

- NEW `HistorySlider` component (replaces the previously planned
  `HistoryPanel`): renders the discrete stop row from a commit list, shows
  timestamp + hash per stop, emits the chosen `Option[CommitHash]`.
- NEW `TreeHistoryState` (unchanged from the original spec): history
  endpoint call + `LoadState`, per (tree, branch).
- Pinned view: `TreeViewState` and `LECChartState` fetches thread the `at`
  pin.
- Rename: `ScenarioDiffState` → `ChangedNodesState` (file rename + call
  shape for the new endpoint); `CompareState`/`AnalyzeView` gating from
  Scope 1 Task B adapts to the renamed state.
- `X-Branch` required: transparent to SPA call sites (they already pass
  `BranchChoice` through the shared endpoint definitions; the codec now
  always emits a value).
- Slider depth (trivial default taken): the slider covers the most recent
  `n` commits the history endpoint returns (default 50); deeper paging is
  deferred until a real tree exceeds it.

### 8a. SPA slicing (user-ruled 2026-07-26): Analyze first, then Design

Three slices, this plan:

- **Slice 0 — backend (§1–§7).** All wire/service/repository work. The only
  SPA-visible change is the required `X-Branch` header (transparent) and
  the `ScenarioDiffState`/create-request adaptations needed to keep the app
  compiling against the renamed/changed wire surface.
- **Slice E-A — Analyze.** `HistorySlider` + `TreeHistoryState` +
  `SlotCoordinate.at` + pinned slot fetches + read-only-at-rewind +
  missing-node notice. Compare-past-vs-current falls out of the slot model:
  baseline at head, comparand slot rewound. Fork is NOT reachable in this
  slice (it ships with Design in E-B); browse and compare are complete and
  self-contained.
- **Slice E-B — Design.** Design slider + read-only pinned mode (forms +
  tree detail + banner) + fork button (greyed at head) + revert
  (`TreeRevertState`, `RevertModal`, scenario-menu enablement).

### 8b. Slice E-A — implementation signatures

Verbatim signatures for the Analyze slider. Ordering is a solved property (no
server work): `getHistory` returns commits oldest-first in ancestry order,
pinned by `RiskTreeRepositoryIrminSpec` — so a stop list is used as-is,
`list.head` = oldest (left), `list.last` = newest (right, = head). Decisions
ruled: point-in-time `at` lives in the coordinate and participates in slot pair
identity (decision 2); no ordering implementation (decision 1 dissolved).

**New — `modules/app/src/main/scala/app/state/TreeHistoryState.scala`** (mirrors
`ChangedNodesState` exactly — EventBus trigger + `ZJS.loadStatePipeline` +
idempotency guard):

```scala
final class TreeHistoryState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends WorkspaceTreeEndpoints:
  val history: Var[LoadState[TreeHistoryResponse]] = Var(LoadState.Idle)
  private val historyTrigger =
    new EventBus[Option[() => EventStream[Either[Throwable, TreeHistoryResponse]]]]
  ZJS.loadStatePipeline(historyTrigger.events).foreach { v =>
    if history.now() != v then history.set(v)
  }(using unsafeWindowOwner)

  /** Fetch a tree's commit history on a branch (oldest-first). */
  def loadHistory(treeId: TreeId, branch: BranchChoice, n: Int = 50): Unit
  /** Supersede an in-flight fetch and clear. */
  def reset(): Unit

  /** Oldest-first commit stops for the slider; empty for any non-loaded state. */
  val commits: Signal[List[TreeHistoryEntry]] = history.signal.map(TreeHistoryState.deriveCommits)

object TreeHistoryState:
  /** Pure derivation, testable without a Laminar harness. */
  def deriveCommits(result: LoadState[TreeHistoryResponse]): List[TreeHistoryEntry] = result match
    case LoadState.Loaded(resp) => resp.entries
    case _                      => Nil
```

`loadHistory` calls `getTreeHistoryEndpoint((userIdAccessor(), key, treeId,
branch, n))` (input tuple: authed userId header + key path + treeId path +
`branchHeader` + `n` query).

**New — `modules/app/src/main/scala/app/components/HistorySlider.scala`**:

```scala
object HistorySlider:
  /** Discrete commit stops for one (tree, branch), index-spaced oldest→left,
    * newest→right. `commits` is oldest-first (as `getHistory` returns): the
    * rightmost stop is the branch head and pins `None` (live); every earlier
    * stop pins `Some(entry.commitHash)`. Each stop shows its timestamp + short
    * hash (tooltip). Emits the chosen pin via `onPick`. */
  def apply(
    commits: Signal[List[TreeHistoryEntry]],
    selected: StrictSignal[Option[CommitHash]],
    onPick: Option[CommitHash] => Unit
  ): HtmlElement
```

**Changed — `modules/app/src/main/scala/app/state/CompareState.scala`**:

```scala
// SlotCoordinate gains `at`; it participates in pair identity (decision 2), so
// a rewound slot on the baseline's tree is a DIFFERENT pair (both chart) rather
// than a collision. effectiveTree is unchanged.
final case class SlotCoordinate(branch: BranchChoice, treeOverride: Option[TreeId], at: Option[CommitHash]):
  def effectiveTree(activeTree: Option[TreeId]): Option[TreeId] = treeOverride.orElse(activeTree)
  def samePairAs(other: SlotCoordinate, activeTree: Option[TreeId]): Boolean =
    branch == other.branch && effectiveTree(activeTree) == other.effectiveTree(activeTree) && at == other.at
  def collidesWith(activeBranch: BranchChoice, activeTree: Option[TreeId]): Boolean =
    samePairAs(SlotCoordinate.activeTab(activeBranch), activeTree)

object SlotCoordinate:
  def activeTab(branch: BranchChoice): SlotCoordinate = SlotCoordinate(branch, None, None)

// CompareSlotState gains `atSignal`, derived from `target` exactly like
// `branchSignal` — so the slider writes `target` and the pin follows, and pair
// identity stays consistent within one transaction:
final class CompareSlotState:
  // ...existing target/hidden/mirror/branchSignal unchanged...
  val atSignal: Signal[Option[CommitHash]] = target.signal.map {
    case CompareTarget.Target(c) => c.at
    case CompareTarget.NotChosen => None
  }.distinct
  /** Slider writes the pin into the coordinate (no-op while NotChosen). */
  def setAt(at: Option[CommitHash]): Unit =
    target.update { case CompareTarget.Target(c) => CompareTarget.Target(c.copy(at = at)); case nc => nc }

// CompareSlot gains a per-slot history source for its slider:
final class CompareSlot(
  val state: CompareSlotState,
  val treeViewState: TreeViewState,
  val diffState: ChangedNodesState,
  val historyState: TreeHistoryState,   // NEW
  val palette: Signal[Vector[HexColor]]
)

// CompareState gains the baseline pin (the baseline is the active tab, not a slot):
final class CompareState:
  // ...existing layout/baselineHidden/slots/rows unchanged...
  val baselineAt: Var[Option[CommitHash]] = Var(None)
```

Every `NotChosen`/`activeTab` construction and the `CompareTarget.Target(SlotCoordinate(...))`
call sites gain the third `at` argument (default `None`).

**Changed — `modules/app/src/main/scala/app/state/TreeViewState.scala`** (add a
pin signal, mirroring `activeBranchSignal`):

```scala
final class TreeViewState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  treeListState: TreeListState,
  globalError: Var[Option[GlobalError]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None,
  activeBranchSignal: StrictSignal[BranchChoice] = Val(BranchChoice.Main),
  atSignal: StrictSignal[Option[CommitHash]] = Val(None),   // NEW
  userPalette: Signal[Vector[HexColor]] = Val(PaletteData.Aqua)
) extends WorkspaceTreeEndpoints:
  private def atAccessor(): Option[CommitHash] = atSignal.now()
  // NEW: a pin change re-fetches the pinned structure (same guard as branch change).
  atSignal.changes.foreach(_ => refreshSelectedTree())(using unsafeWindowOwner)
  // loadTreeStructure: the Option.empty[CommitHash] placeholder becomes atAccessor():
  //   getWorkspaceTreeStructureEndpoint((userIdAccessor(), key, id, branchAccessor(), atAccessor()))
  // chartState gains atAccessor:
  val chartState: LECChartState =
    LECChartState(keySignal, selectedTreeId.signal, selectedTree.signal, globalError,
                  userIdAccessor, branchAccessor, atAccessor, userPalette)
```

**Changed — `modules/app/src/main/scala/app/state/LECChartState.scala`**:

```scala
final class LECChartState(
  // ...existing params...,
  branchAccessor: () => BranchChoice = () => BranchChoice.Main,
  atAccessor: () => Option[CommitHash] = () => None,   // NEW (before userPalette)
  userPalette: Signal[Vector[HexColor]] = Val(PaletteData.Aqua)
):
  // loadCurves: the Option.empty[CommitHash] placeholder becomes atAccessor():
  //   getWorkspaceLECCurvesMultiEndpoint((userIdAccessor(), key, treeId, false, nodeIds, branchAccessor(), atAccessor()))

  /** Selected nodes with no curve in the current cache — i.e. selections that do
    * not exist at the pinned commit (H3). Empty at head. Drives the transient
    * "not present at this point in time" notice; the chart already omits them. */
  val droppedSelections: Signal[Set[NodeId]] =
    userSelectedNodeIds.signal.combineWith(curveCache.signal).map(LECChartState.deriveDropped)

object LECChartState:
  def deriveDropped(selected: Set[NodeId], cache: LoadState[Map[NodeId, LECNodeCurve]]): Set[NodeId] =
    cache match
      case LoadState.Loaded(m) => selected -- m.keySet
      case _                   => Set.empty
```

**Changed — `modules/app/src/main/scala/app/views/TreeDetailView.scala`** (pinned
read-only + banner):

```scala
def apply(
  state: TreeViewState,
  queryMatchedNodes: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
  hoverBridge: ChartHoverBridge = new ChartHoverBridge(),
  changedNodeIds: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
  selectionLocked: Signal[Boolean] = Val(false),        // widened from StrictSignal
  pinnedAt: Signal[Option[TreeHistoryEntry]] = Val(None) // NEW: pinned banner (timestamp + hash)
): HtmlElement
```

`pinnedAt = Some(entry)` renders the pinned banner (naming `entry.at` +
short `entry.commitHash`); the caller passes `selectionLocked = mirror ||
pinned` so rewind locks selection via the existing mechanism.

**Changed — `modules/app/src/main/scala/app/state/ChangedNodesState.scala`**
(wire the pins so compare = baseline@its-pin vs comparand@its-pin; today both
are hard-`None`):

```scala
def loadDiff(
  treeId: TreeId,
  activeBranch: BranchChoice, activeAt: Option[CommitHash],
  compareBranch: BranchChoice, compareAt: Option[CommitHash]
): Unit
// emits getChangedNodesEndpoint((userIdAccessor(), key, treeId,
//   activeBranch, activeAt, compareBranch, compareAt))
```

**Wiring — `modules/app/src/main/scala/app/views/AnalyzeView.scala`**:

- Construct one `TreeHistoryState` per `CompareSlot` (alongside its
  `treeViewState`/`diffState` at the existing build site) and one for the
  baseline; the slot's `TreeViewState` now receives `atSignal =
  state.atSignal`, the baseline's receives `atSignal = compareState.baselineAt.signal`.
- `renderComparandHead`: add a slider row after the `slot-card-picker` div —
  `HistorySlider(slot.historyState.commits, <slot at as StrictSignal>, slot.state.setAt)`;
  the baseline card gets the same row bound to `baselineAt`. A subscription
  (re)loads each `TreeHistoryState` when its (effective tree, branch) changes.
- `TreeDetailView` calls pass `selectionLocked = mirror || pinned` and
  `pinnedAt` resolved from the slot's `atSignal` against `historyState.commits`;
  render `LECChartState.droppedSelections` as the transient H3 notice.
- `engagedSlots`/`samePairAs` now key on `at` too (above), so a rewound slot on
  the baseline's tree engages instead of being deduped as a tab collision —
  this is the past-vs-current compare path, no extra code.

**New test — `modules/app/src/test/scala/app/state/TreeHistoryStateSpec.scala`**:
pure `TreeHistoryState.deriveCommits` (Loaded → entries; Idle/Loading/Failed →
Nil) and `LECChartState.deriveDropped` (selected minus loaded curve keys;
non-loaded → empty), styled like `ChangedNodesStateSpec` (no DOM/HTTP harness).

Open decisions (Slice E-A): none — decisions 1 and 2 ruled; ordering solved.

## File inventory (Scope 2)

Common:
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/BaseEndpoint.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceTreeEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceQueryEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceLifecycleEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/ScenarioEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/ScenarioRequests.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RevertTreeRequest.scala (NEW)
- modules/common/src/main/scala/com/risquanter/register/http/responses/TreeHistoryResponse.scala (NEW)
- modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioDiffResponse.scala (RENAME → ChangedNodesResponse.scala; old path, remove after rename)
- modules/common/src/main/scala/com/risquanter/register/http/responses/ChangedNodesResponse.scala (RENAME target)
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala (BranchChoice header/query codec if not co-located in OpaqueTypes)

Server:
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala (NEW)
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioDiffService.scala (RENAME → ChangedNodesService.scala; old path, remove after rename)
- modules/server/src/main/scala/com/risquanter/register/services/ChangedNodesService.scala (RENAME target)
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioService.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/ActiveBranch.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/ScenarioController.scala
- modules/server/src/main/scala/com/risquanter/register/http/HttpApi.scala
- modules/server/src/main/scala/com/risquanter/register/Application.scala
- modules/server/src/main/scala/com/risquanter/register/services/sse/SSEHub.scala
- modules/server/src/main/scala/com/risquanter/register/http/sse/SSEController.scala
- modules/server/src/main/scala/com/risquanter/register/http/sse/SSEEvent.scala
- modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/QueryController.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala
- modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala
- modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala

The `activeBranchHeader` consumer set above is complete (verified by grep
2026-07-25: WorkspaceTree/WorkspaceAnalysis/Query/WorkspaceLifecycle
controllers + ActiveBranch + RiskTreeService/QueryService). `QueryService`'s
tree reads thread `Revision` with the same pattern as `RiskTreeService` §3.
Standard protocol applies if anything else surfaces: stop, amend, wait.

Server tests:
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioServiceLiveSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/ScenarioControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/TreeHistoryServiceSpec.scala (NEW)
- modules/server/src/test/scala/com/risquanter/register/domain/BranchChoiceWireSpec.scala (NEW: fromWireString, "main" reservation)
- modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala (§C3: RiskTreeService stub gains omitAbsent param)

Server IT:
- modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala (NEW: E3 probe — native revert is a head-set)
- modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala (NEW: path-scoping probe)
- modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala (NEW: forward-commit revert — both states in history; SSE/invalidation fires)
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala (continuation §C1: per-branch tree-name uniqueness over real Irmin fork inheritance)

App:
- modules/app/src/main/scala/app/state/TreeHistoryState.scala (NEW)
- modules/app/src/main/scala/app/state/TreeRevertState.scala (NEW)
- modules/app/src/main/scala/app/state/ScenarioDiffState.scala (RENAME → ChangedNodesState.scala; old path, remove after rename)
- modules/app/src/main/scala/app/state/ChangedNodesState.scala (RENAME target)
- modules/app/src/main/scala/app/state/CompareState.scala
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/ScenarioState.scala
- modules/app/src/main/scala/app/components/HistorySlider.scala (NEW)
- modules/app/src/main/scala/app/components/RevertModal.scala (NEW)
- modules/app/src/main/scala/app/components/BranchBar.scala
- modules/app/src/main/scala/app/components/SlotPalettePicker.scala (NEW — §C2 per-slot palette)
- modules/app/src/main/scala/app/components/BranchPalettePicker.scala (DELETE — §C2 retires branch-keyed palette)
- modules/app/src/main/scala/app/state/BranchPaletteState.scala (DELETE — §C2 retires branch-keyed palette)
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/DesignView.scala
- modules/app/src/main/scala/app/views/TreeBuilderView.scala
- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/views/RiskLeafFormView.scala
- modules/app/src/main/scala/app/views/PortfolioFormView.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/src/main/scala/app/components/AppShell.scala (§C2 D-C2-1=B: drops the branchChip slot)
- modules/app/styles/app.css

App tests:
- modules/app/src/test/scala/app/state/ScenarioDiffStateSpec.scala (RENAME → ChangedNodesStateSpec.scala; old path, remove after rename)
- modules/app/src/test/scala/app/state/ChangedNodesStateSpec.scala (RENAME target)
- modules/app/src/test/scala/app/state/TreeHistoryStateSpec.scala (NEW: pure derivations)
- modules/app/src/test/scala/app/state/CompareStateSpec.scala (§C2: per-slot palette default/reset coverage; §C3: collidesWith activeAt)
- modules/app/src/test/scala/app/state/BranchPaletteStateSpec.scala (DELETE — §C2 retires branch-keyed palette)
- modules/app/src/test/scala/app/views/AnalyzeViewSeedSpec.scala (§C3: engagedSlots activeAt param)

Versioning:
- build.sbt (ThisBuild / version → 0.10.0 MINOR on landing — 0.8.x/0.9.0 were consumed by the compare rework; APP_VERSION mirrored to .env and .env.irmin, which are ungated and need no bullet)

Compile-fix ripple (mechanical adaptation forced by E5/E6/E7 — default removal,
diff→changed-nodes rename, X-Branch required, forkOf→source):

Server sources:
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceNotSupported.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala

Server tests:
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioDiffServiceSpec.scala (RENAME → ChangedNodesServiceSpec.scala; old path, remove after rename)
- modules/server/src/test/scala/com/risquanter/register/services/ChangedNodesServiceSpec.scala (RENAME target)
- modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemoryBranchSpec.scala
- modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeReadConsistencySpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerCascadeSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala
- modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala (handleMutation/handleTreeDeletion gain branch tag)
- modules/server/src/test/scala/com/risquanter/register/services/Item17RegressionSpec.scala (handleMutation gains branch tag)
- modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala (create gains branch)

Server-IT tests:
- modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala (E7 runtime ripple: send required X-Branch header)
- modules/server-it/src/test/scala/com/risquanter/register/http/QueryEndpointSpec.scala (E7 runtime ripple: send required X-Branch header)
- modules/server-it/src/test/scala/com/risquanter/register/http/DemoEnterpriseScriptSpec.scala (E7 runtime ripple: send required X-Branch header)
- modules/server-it/src/test/scala/com/risquanter/register/http/DemoSimpleScriptSpec.scala (E7 runtime ripple: send required X-Branch header)
- modules/server-it/src/test/scala/com/risquanter/register/http/support/DemoSpecSupport.scala (E7 runtime ripple: shared demo request builder sends required X-Branch header)

App (Slice-0 compile-fix):
- modules/app/src/main/scala/app/state/ScenarioMergeState.scala
- modules/app/src/main/scala/app/state/AnalyzeQueryState.scala
- modules/app/src/main/scala/app/state/ScenarioListState.scala
- modules/app/src/test/scala/app/state/BranchPaletteStateSpec.scala (E7 "main" reservation invalidates its scenario-named-main setup; option C — unsafe-construct for defense-in-depth)

Stale doc:
- modules/app/src/main/scala/app/chart/PaletteData.scala

Any further existing test/harness file the compile surfaces as broken by these
same signature changes is appended here as an exact bullet and fixed as a
mechanical compile-fix (no assertion changes; a real assertion change halts).


## ADR alignment

- Nominal Iron types at every boundary (`CommitHash`, `BranchChoice`,
  `Revision`, `ScenarioName`); validate once at the Tapir boundary; services
  receive validated types (ADR-001/018; adr-constraints).
- Two-hash relations (ADR-032) unchanged: changed-nodes compares DOMAIN
  content hashes (`ContentHashIndex`); the merge pre-check keeps byte-level
  Irmin equality. The rename does not merge the mechanisms.
- DD-10 flat errors: reuses `CommitNotFound` / existing validation errors;
  no new `AppError` subtype (no new exhaustive-match sites).
- DD-20 no-surface-without-consumer: every new endpoint has a named SPA
  consumer in §8.
- Credential rule (ADR-022) untouched; `WorkspaceKeySecret` handling
  unchanged.

## Open decisions

- None. E8 ruled 2026-07-25 (per-tree, see §5); E1–E7 ruled 2026-07-25;
  H1–H6 (slider model, missing-node handling, read-only rewind, slider in
  both views, fork in Design, revert placement) ruled 2026-07-26 (§8);
  slicing ruled 2026-07-26 (§8a).
- Trivial defaults taken: history page size `n=50` (also the slider depth);
  `HistoryOperation` vocabulary as listed; discriminator field name
  `"type"`.
- Changed-nodes rename extends to the service-internal `NodeDiffStatus` /
  `NodeDiff` → `NodeChangeStatus` / `NodeChange` (ruled 2026-07-26) for naming
  consistency with `ChangedNodesService` / `ChangedNodesResult`; doc references
  swept to match.
- `collectAllTrees` uniqueness quirk (the tree-uniqueness check reads main
  regardless of the write branch) is a pre-existing bug surfaced by removing
  the `getAllForWorkspace` default. Slice 0 PRESERVES it exactly
  (`Revision.Head(BranchRef.Main)`); the per-branch-uniqueness fix is a
  separate follow-up (Fable designs/verifies the plan → Opus implements →
  Opus high-complexity review), not part of this plan.

## Verification

- `sbt compile` (all modules; commonJS/commonJVM cross-build must both pass)
- `sbt 'commonJVM/test; server/test'`, `sbt app/test`
- `sbt serverIt/test` (needs `local/irmin-prod:3.11-p1`) — including the
  three NEW IT specs
- BATS suite C after server changes
- Manual (nginx stack): rewind a baseline slot's slider on main and on a
  scenario — tree re-renders at the pinned commit, timestamp + hash shown,
  slot read-only, back to right edge restores live behaviour; select a
  node, rewind past its creation — curve drops from the chart and the
  notice names it; compare past-vs-current — baseline at head, comparand
  slot rewound, both curves on one chart; Design slider — rewind pins the
  forms read-only with banner, fork button greys at head and activates
  when rewound; fork-from-rewind → new scenario at that state, editable in
  Design; revert with confirm → both states visible in the slider's stop
  list; `X-Branch` absent → 400 (curl check).
- Pass/fail reporting only.

## Versioning

MINOR on landing (external API changes: required renamed header, new
endpoints, changed request/response shapes): `0.9.x → 0.10.0`, mirrored to
`.env` and `.env.irmin`.

## Post-landing step (mandated 2026-07-25): branch-semantics API sweep

After Scope 2 lands, a REVIEW-ONLY pass over the full HTTP surface and
service layer, looking for what the revision-selector unification surfaced:
vocabulary inconsistencies against the branch-vs-scenario rule; any
remaining absent-means-main semantics; duplicated revision-resolution
logic; API or functional duplication between changed-nodes, compare reads,
and the merge preview's read path; parameters that became redundant.
Output: findings in decision-guide format (per the review-output rule); any
resulting work is planned separately — no code edits inside the sweep.

---

# Continuation §C1 — Tree uniqueness checked against the write branch

IMPLEMENTED (0.10.16, uncommitted). `RiskTreeServiceLive` now checks tree-name
and tree-ID uniqueness against the head of the branch being written.
`build.sbt` + `APP_VERSION` mirrored to `.env` and `.env.irmin`.

Both decisions were ruled (2026-07-27, user): **semantics = Option A (per-branch
uniqueness); integration-test coverage = Option A (one HTTP-level IT over real
Irmin fork inheritance).** The IT required real scenario support in the Irmin
test harness — see the file-inventory note at the end of this section.

## Goal

`create` and `update` check tree-ID and tree-name uniqueness against the head
of the branch being written, not against `Revision.Head(BranchRef.Main)`.

## Bug (verified in code)

In `RiskTreeServiceLive.scala`:

- `ensureUniqueTree(wsId, treeId, treeName, excludeId)` (line 85) calls
  `collectAllTrees(wsId)` (line 112), which calls
  `repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main))` — main's head,
  always.
- `create` (line 334) and `update` (line 360) are the only callers of
  `ensureUniqueTree`; `ensureUniqueTree` is the only caller of
  `collectAllTrees`. Both already receive the write target as `branch: BranchRef`
  and pass it to every repository write and to `update`'s own read.

Consequences:

- False block: a name used at main's head blocks creating that name on a
  scenario branch where it is free (the scenario deleted/renamed the inherited
  tree, or main gained the name after the fork).
- False allow: a duplicate name within a scenario branch passes the check,
  because the check read main's tree set, not the scenario's.
- The tree-ID arm is practically unaffected (server-generated ULIDs); the ID
  arm is kept as-is, only the revision it reads changes.

Fork semantics: a scenario branch is an Irmin fork of main; at fork time it
contains main's trees, so names held by main at the fork remain taken on the
scenario under a per-branch check. Per-branch and cross-branch checks diverge
only after the branches diverge.

## Signatures

Changed (private helpers in `RiskTreeServiceLive`):

```scala
// Before
private def ensureUniqueTree(wsId: WorkspaceId, treeId: TreeId, treeName: SafeName.SafeName, excludeId: Option[TreeId] = None): Task[Unit]
private def collectAllTrees(wsId: WorkspaceId): Task[List[RiskTree]]

// After
private def ensureUniqueTree(wsId: WorkspaceId, treeId: TreeId, treeName: SafeName.SafeName, branch: BranchRef, excludeId: Option[TreeId] = None): Task[Unit]
private def collectAllTrees(wsId: WorkspaceId, branch: BranchRef): Task[List[RiskTree]]
```

`branch` sits after the domain arguments (matching `create(wsId, riskTree,
branch)`) and before `excludeId` so the default stays last. `collectAllTrees`
takes `BranchRef`, not `Revision`: uniqueness is only ever checked at a branch
head, so the tighter type prevents passing a pinned commit. Inside, it calls
`repo.getAllForWorkspace(wsId, Revision.Head(branch))`.

No public signature changes: trait, repository, endpoints, and DTOs untouched.

### Call-site trace (complete — grep-verified)

| Caller | Current call | New call |
|---|---|---|
| `create` (:334) | `ensureUniqueTree(wsId, treeId, resolved.treeName)` | `ensureUniqueTree(wsId, treeId, resolved.treeName, branch)` |
| `update` (:360) | `ensureUniqueTree(wsId, id, resolved.treeName, excludeId = Some(id))` | `ensureUniqueTree(wsId, id, resolved.treeName, branch, excludeId = Some(id))` |
| `ensureUniqueTree` (:86) | `collectAllTrees(wsId)` | `collectAllTrees(wsId, branch)` |
| `collectAllTrees` body (:113) | `repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main))` | `repo.getAllForWorkspace(wsId, Revision.Head(branch))` |

No other callers (verified by `grep -rn "ensureUniqueTree\|collectAllTrees" modules/`).

### Comment update (doc-consistency, same pass)

The comment above `collectAllTrees` (:109–111) becomes current-state:

```scala
  // Trees at the head of the branch being written; uniqueness (tree ID and
  // name) is checked against exactly this set.
```

## Semantics — RULED Option A (per-branch uniqueness)

A name must be unique within the tree set at `Revision.Head(branch)` of the
branch being written; nothing else is consulted. Matches the branch model,
fixes both the false block and the false allow, minimal change (the four call
sites), consistent with `update`'s existing read of the write branch. Inherited
names stay blocked on a scenario automatically (the fork contains the same
trees).

The one gap it leaves — two branches can independently create same-name /
different-ULID trees, and a later merge brings both to main undetected (the
byte-level merge sees disjoint ULID paths) — is a merge-time concern no
write-time check can close. Captured as a to-be-planned Phase F requirement:
`docs/archive/milestone-2b-cache-and-decisions.md`, "Deferred: Phase D Option-2
conflict resolution", requirement 4.

(Rejected: Option B, workspace-wide uniqueness — it makes the false-block bug
policy, needs branch enumeration inside the service, and is still not atomic
across branches.)

## Test changes

Existing tests — verified impact:

- `RiskTreeControllerSpec.scala` "duplicate tree name is rejected" (:154): both
  creates target main, so the outcome is identical under per-branch semantics.
  No assertion changes; file not modified.
- `RiskTreeServiceLiveSpec.scala`: every existing create/update/read targets
  main; no existing assertion changes. The stub repository is reworked (below);
  existing tests run unchanged on top of it.

No assertion removed, weakened, or reframed (Decision Trigger #8 does not fire).

Stub repository rework in `RiskTreeServiceLiveSpec` (fixture change, ratified
here): map keyed by `(WorkspaceId, BranchRef, TreeId)`; reads pattern-match on
the revision:

```scala
override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): Task[Option[RiskTree]] =
  rev match
    case Revision.Head(branch) => ZIO.succeed(db.get((wsId, branch, id)))
    case Revision.At(_)        => ZIO.die(new UnsupportedOperationException("commit-pinned reads not exercised in this stub"))

override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): Task[List[Either[RepositoryFailure, RiskTree]]] =
  rev match
    case Revision.Head(branch) => ZIO.succeed(db.collect { case ((wid, b, _), tree) if wid == wsId && b == branch => Right(tree) }.toList)
    case Revision.At(_)        => ZIO.die(new UnsupportedOperationException("commit-pinned reads not exercised in this stub"))
```

Known fixture limitation (stated in a stub comment): a stub scenario branch
starts empty — it does not model Irmin's fork-time inheritance. The unit tests
therefore exercise post-divergence cases; fork inheritance is covered by the IT
below.

New unit tests (added to `RiskTreeServiceLiveSpec`, suite "Per-branch
uniqueness"):

1. "same tree name on two branches is allowed" — name N on main; name N on the
   scenario branch; both succeed. (False-block fix.)
2. "duplicate tree name within a scenario branch is rejected" — name N twice on
   the scenario branch; second fails with `ValidationFailed` where
   `field == "name" && code == ValidationErrorCode.DUPLICATE_VALUE`. (False-allow fix.)
3. "update rename to a name taken on the same branch is rejected" — trees A, B
   on main; rename B to A's name; `DUPLICATE_VALUE` on `"name"`.
4. "update rename to a name held only on another branch is allowed" — tree N on
   main; tree T on the scenario branch; rename T to N; succeeds.

## Integration test — RULED Option A

One HTTP-level test in `HttpApiIntegrationSpec.scala`: create tree N on main;
create a scenario; verify create N on the scenario is rejected while the
inherited tree exists (fork inheritance); delete it on the scenario; verify
create N then succeeds and a second N on the scenario is rejected. This asserts
the one behaviour the unit stub cannot model — fork-time inheritance composed
with the uniqueness check — over the real service+repo+Irmin path.

Driving scenario endpoints needed real scenario support in the Irmin test
harness, which previously wired `ScenarioServiceNotSupported` (501) because no
IT exercised that path. `HttpTestHarness` now builds a single backend layer
providing the tree repository plus `ScenarioServiceLive` and
`ScenarioMergeServiceLive` from one shared `IrminClient`; the in-memory server
keeps the `NotSupported` stubs (scenarios are an Irmin-branch feature).

## ADR alignment

- ADR-001: compliant — uniqueness is a service-level cross-entity invariant, not
  input re-validation; all parameters are already-validated nominal/refined types.
- ADR-010: compliant — `ensureUniqueTree` keeps accumulating ID-arm and name-arm
  errors into one `ValidationFailed`; only the revision read changes.
- ADR-018: compliant — `BranchRef` passed through end to end; no stringly-typed
  branch handling.
- Repository revision contract: compliant — removes the last hard-coded
  `Revision.Head(BranchRef.Main)` read in the service write path.
- Decision Triggers: #5 (behavior change) is the trigger this discharges; #1/#4
  do not fire (no endpoint/DTO/public-signature change); #8 does not fire.
- Code style: the stub's revision handling uses `match` on `Revision`.

## Non-goals (unchanged by this fix)

- check-then-write is not transactional even within one branch (pre-existing).
- `revertTree` performs no uniqueness check (pre-existing).
- Merge does not deduplicate tree names — the post-merge duplicate-name case is
  Phase F (requirement 4).

## Verification

```bash
sbt server/compile
sbt "server/testOnly *RiskTreeServiceLiveSpec"
sbt "server/testOnly *RiskTreeControllerSpec"
sbt 'commonJVM/test; server/test'
sbt "serverIt/test"   # Option A IT; needs local/irmin-prod:3.11-p1
```

Landing: next PATCH bump in `build.sbt` (`0.10.16` as of this amendment),
mirror the same `APP_VERSION` into `.env` and `.env.irmin`, flag plan
completion.

## Continuation §C1 file inventory (additions to the Scope 2 inventory above)

All folded into the Scope 2 `## File inventory` section — the enforcement hook
reads only that heading. `RiskTreeServiceLive.scala`, `RiskTreeServiceLiveSpec.scala`,
`build.sbt`, `HttpApiIntegrationSpec.scala`, and `HttpTestHarness.scala` are all
listed there. `HttpTestHarness.scala` was touched to wire real scenario support
for the Irmin path (see the integration-test note above) — the §C1 IT could not
otherwise create a scenario. `.env`/`.env.irmin` are ungated (no bullet needed).

# Continuation §C2 — Slice E-A manual-review fixes (history charting + per-slot palette)

Three defects found in the uncommitted E-A build during manual review
(2026-07-27). All three trace to two design decisions in E-A, not three
independent bugs. Both directions are user-ruled (2026-07-27).

## Goal

1. **Single-tree history charting works.** Moving the slider on a single tree
   currently calls `chartState.reset()` (wipes the selection and curves) and
   locks selection (`selectionLocked = baselineAt.isDefined`), showing the
   "mirrors the baseline" notice on a tree that has no mirror. Rewinding must
   instead re-read the structure AND the current selection's curves at the pin,
   keeping the selection, so the same curves scrub through time (absent nodes
   drop via the existing H3 notice and return on moving forward). Rewound cards
   stay fully interactive (Ctrl+click still selects); the pin is a read
   dimension, not a lock. This **reverses §8b's "rewind = read-only via
   selectionLocked"** — that call was wrong for the feature's primary use.
2. **Same-branch comparand no longer force-locked (2b).** The comparand lock
   `mirror || at.isDefined` locks a rewound slot even with Mirror off, and
   toggling Mirror cannot free it. Lock becomes `mirror` only.
3. **Per-slot palette (2a), Option A (ruled).** Curve colour is keyed by
   branch, so two sides of the same branch (main@head vs main@C2) resolve to
   one family and share one picker. Colour identity moves to the **slot**
   (baseline + each comparand pool slot), each independently pickable; two
   slots may deliberately hold the same family (no forced uniqueness). The
   branch-keyed `BranchPaletteState` + `BranchPalettePicker` are retired.

## Fix 1 + 2b — rewind = live time-scrubbing (no lock, no reset)

### `TreeViewState.scala`

Split the structure fetch out of `loadTreeStructure` so a pin change can
re-read structure without the tree-switch resets, and re-fetch the selection's
curves at the pin:

```scala
// Emit only the structure fetch at the current (branch, pin) — no chart/expand
// resets. Shared by loadTreeStructure (after its resets) and rewindReload.
private def emitStructureFetch(id: TreeId): Unit =
  keySignal.now() match
    case Some(key) =>
      treeTrigger.emit(Some(() =>
        getWorkspaceTreeStructureEndpoint((userIdAccessor(), key, id, branchAccessor(), atAccessor())).toOutcomeEventStream
      ))
    case None => ()

def loadTreeStructure(id: TreeId): Unit =
  expandedNodes.set(Set.empty)
  selectedNodeId.set(None)
  chartState.reset()
  emitStructureFetch(id)

// Pin change (history slider): re-read the selected tree at the new pin and
// re-fetch the current selection's curves there, WITHOUT clearing the
// selection — the same curves scrub through time.
private def rewindReload(): Unit =
  selectedTreeId.now().foreach(emitStructureFetch)
  chartState.reloadCurrentCurves()

// replaces the current `atSignal.changes.foreach(_ => refreshSelectedTree())`
atSignal.changes.foreach(_ => rewindReload())(using unsafeWindowOwner)
```

`refreshSelectedTree()` (used by the branch-change path and the refresh button)
is unchanged — those keep resetting the chart, which is correct for a genuine
tree/branch switch. Only the pin path changes.

### `LECChartState.scala`

```scala
/** Re-fetch the current visible selection's curves at the current pin — used
  * when only `at` changes (selection unchanged, curves must be re-read at the
  * new commit). Empty selection → clear. */
def reloadCurrentCurves(): Unit =
  (userSelectedNodeIds.now() ++ satisfyingNodeIds.now()).toList match
    case Nil => clearCurves()
    case ids => loadCurves(ids)
```

### `AnalyzeView.scala` — drop the at-based lock

- Baseline body: **remove** `selectionLocked = compareState.baselineAt.signal.map(_.isDefined)`
  (defaults to `Val(false)`); keep the `pinnedAt` banner.
- Comparand body: `selectionLocked = slot.state.mirror.signal` (drop
  `.combineWith(slot.state.atSignal)` and the `|| at.isDefined`).

### `TreeDetailView.scala` — neutral pin banner

Line 119: drop the `" — read-only"` suffix — the banner is an informational
"viewing a past commit" indicator, not a lock:

```scala
Some(div(cls := "pinned-banner", s"Viewing ${entry.at} · ${entry.commitHash.value.take(8)}"))
```

## Fix 2a — per-slot palette (Option A)

### `CompareState.scala`

```scala
object CompareState:
  // moved verbatim from Main (with its require) — CompareState owns slot identity.
  val slotDefaultPalettes: Vector[Vector[HexColor]] = Vector(
    PaletteData.Purple, PaletteData.Orange, PaletteData.Green,
    PaletteData.Yellow, PaletteData.Red, PaletteData.Pink, PaletteData.Emerald
  )
  require(slotDefaultPalettes.length == ComparedSlotCount, "one default palette family per compare slot")

final class CompareSlotState(val defaultPalette: Vector[HexColor]):
  // ... target / hidden / mirror / branchSignal / atSignal / setAt unchanged ...
  /** This slot's curve/tree/highlight colour family — slot-keyed, not branch:
    * two slots on the same branch stay distinct and independently pickable.
    * Defaults to the slot's family; the picker overwrites it. */
  val palette: Var[Vector[HexColor]] = Var(defaultPalette)

final class CompareSlot(                       // `palette` field REMOVED (now on state)
  val state: CompareSlotState,
  val treeViewState: TreeViewState,
  val diffState: ChangedNodesState,
  val historyState: TreeHistoryState
)

final class CompareState:
  // ...
  /** Baseline (active tab) colour family — slot-keyed like the comparands. */
  val baselinePalette: Var[Vector[HexColor]] = Var(PaletteData.Aqua)

  val slots: Vector[CompareSlotState] =
    CompareState.slotDefaultPalettes.map(new CompareSlotState(_))

  // removeRow also resets the freed slot's palette:
  //   slots(poolIdx).palette.set(slots(poolIdx).defaultPalette)
```

### New `SlotPalettePicker.scala` (ports the popover from `BranchPalettePicker`, slot-keyed, ADR-019 callbacks)

```scala
object SlotPalettePicker:
  /** Clickable swatch showing `current`'s family; opens a popover of the named
    * families + "↺ Auto". Emits the chosen family via `onSelect`, reset via
    * `onReset` (parent writes its own Var — ADR-019 Pattern 2). */
  def apply(
    current: Signal[Vector[HexColor]],
    onSelect: Vector[HexColor] => Unit,
    onReset: () => Unit
  ): HtmlElement
```

Active-cell highlight: `current.map(cur => PaletteData.namedFamilies.find(_._2 == cur).map(_._1))`.

### `AnalyzeView.scala` — repoint palette wiring

- Drop the `branchPaletteState: BranchPaletteState` param and its import.
- `activePalette` becomes `compareState.baselinePalette.signal` (drop the
  `paletteFor` derivation).
- `renderBaselineHead`: drop its `branchPaletteState` param; the picker becomes
  `SlotPalettePicker(compareState.baselinePalette.signal, compareState.baselinePalette.set, () => compareState.baselinePalette.set(PaletteData.Aqua))`.
- `renderComparandHead`: drop its `branchPaletteState` param; the picker becomes
  `SlotPalettePicker(slot.state.palette.signal, slot.state.palette.set, () => slot.state.palette.set(slot.state.defaultPalette))`.
- Overlay/panel reads of `slot.palette` → `slot.state.palette.signal`
  (`slotOverlayInputs`, `chartPanel(compareSlots(pi).state.palette.signal…)`).

### `Main.scala`

- Remove `val branchPaletteState` and the `compareSlotDefaultPalettes` vector +
  `require` (now in `CompareState`).
- Baseline `TreeViewState` `userPalette = analyzeCompareState.baselinePalette.signal`.
- Slot `TreeViewState` `userPalette = slotState.palette.signal`; build
  `new CompareSlot(state, treeViewState, diffState, historyState)` (no palette arg).
- Drop `branchPaletteState` from the `BranchBar.chipForSection` and `AnalyzeView`
  calls.

### `BranchBar.scala` + `AppShell.scala` + `Main.scala` — remove the topbar chip (D-C2-1 = B)

The topbar branch chip is redundant with each section's own in-place branch
control (Design's `BranchBar.toolbar`, Analyze's baseline-card `BranchBar.picker`)
and the two sections never share branch state, so it is removed entirely rather
than kept as a colourless label:
- `BranchBar.chipForSection` deleted (its only caller is `Main`); any import it
  alone used (`BranchPaletteState`, `Section`, `PaletteData`) is dropped if now
  unused.
- `AppShell.apply` drops the `branchChip: HtmlElement` param and its render slot.
- `Main` stops constructing the chip and drops the `branchChip = …` argument.

### Deletions

`BranchPaletteState.scala` and `BranchPalettePicker.scala` are removed — their
only callers (the two compare-card pickers and the chip) are repointed above.
`app.css`: `.branch-palette-*` / `.branch-chip-swatch` rules pruned; the
`.pinned-banner` "read-only" copy is unaffected (text lives in the view).

## Open decisions

**D-C2-1 — topbar branch chip. RULED B (2026-07-27): remove the chip entirely.**
It duplicated each section's own branch control, the two sections never share
branch state, and Option A left its colour swatch sourceless — so a text-only
chip would be pure duplication. Removed from `BranchBar` + `AppShell` + `Main`;
`.branch-chip*` CSS pruned.

No other open decisions — Fix 1/2b behaviour and Option A are user-ruled.

## ADR alignment

- **ADR-019** (Pattern 2 parent-owns-Vars; Pattern 4 child emits callbacks):
  per-slot `palette` Vars live on `CompareSlotState`/`CompareState` (parent
  state); `SlotPalettePicker` takes a `Signal` + `onSelect`/`onReset`
  callbacks, owning only its local popover-open flag — same shape as
  `ColorSwatchPicker`. `reloadCurrentCurves` and `rewindReload` are
  state-owned effects fired from the state's own `atSignal` subscription, not
  `.now()` reads in the render path. Compliant.
- **ADR-001**: no raw-primitive domain params introduced; palette is
  `Vector[HexColor]`. Compliant.
- No API-shape, endpoint, DTO, persistence, or auth change — pure SPA
  reactive-wiring + colour-identity change.

## Verification

```bash
sbt app/compile      # zero new warnings
sbt app/test         # TreeHistoryStateSpec unchanged; deriveDropped still covers H3
sbt 'commonJVM/test; server/test'   # untouched, regression guard
```

- No new pure derivation is introduced that isn't already covered
  (`reloadCurrentCurves`/`rewindReload` are imperative effect wrappers over
  the already-tested `loadCurves`/`deriveDropped`); if the palette active-cell
  match is extracted to a pure companion, add a case to `TreeHistoryStateSpec`.
- Manual re-test at `localhost:18080` with
  `examples/stage-history-slider-curl.sh`: (1) single tree — chart a node,
  scrub the slider, curves follow through time and the H3 notice fires only for
  the pre-C4 stops on Insider Threat; (2) two slots on main (head vs C2) —
  distinct colours, each picker independent, Mirror-off frees selection while
  rewound.

## §C2 file inventory (additions to the Scope 2 inventory above)

`SlotPalettePicker.scala` (NEW), `BranchPalettePicker.scala` (DELETE),
`BranchPaletteState.scala` (DELETE) added to the Scope 2 App block. All other
touched files (`TreeViewState`, `LECChartState`, `CompareState`, `AnalyzeView`,
`TreeDetailView`, `BranchBar`, `Main.scala`, `app.css`) were already listed
there. No `common`/`server` changes.

## Versioning

PATCH bump on landing (shipped SPA behaviour changed): `build.sbt` →
`0.10.2`, `APP_VERSION` mirrored to `.env` and `.env.irmin`. (§C1 lands at
whatever PATCH is next when it lands — see §C1's landing note.)

# Continuation §C3 — compare-history fixes (3a collision, 3b omitAbsent)

Two defects found in manual review of the §C2 build (2026-07-27). Both rulings
below are user-confirmed.

## Goal

1. **3a — present-vs-past on one tree engages.** With the baseline rewound to
   the past, a comparand set to the live head (`at = None`) of the same branch
   and tree currently disengages, because `collidesWith`/`activeTab` hardcode
   the active tab's `at = None` and so treat the comparand as a duplicate of an
   (assumed-at-head) baseline. Thread the baseline's real pin (`baselineAt`)
   into the active-tab coordinate.
2. **3b — rewind before a node existed drops it, not errors.** The multi-curve
   fetch fails the whole request if any node is absent from the pinned tree
   (`lookupNodesInTree`), so rewinding before a node's creation blanks the chart
   with a 400. Add an additive `omitAbsent` flag; when set, absent nodes are
   omitted (the H3 drop path) instead of failing.

## Rulings (2026-07-27, user)

- **3a**: single correct fix — active-tab coordinate carries `baselineAt`.
- **3b**: Option B — a **separate** `omitAbsent: Boolean` query param (default
  `false`), orthogonal to `at` (clean separation of concerns; `at` = which
  commit, `omitAbsent` = error semantics). The frontend sends `omitAbsent = true`
  on every curve fetch (never blank the chart on a missing curve); the server
  omits absent nodes and logs the count for observability. Existing strict
  behaviour and its callers are unchanged by the `false` default.

## Fix 3a — active-tab coordinate carries the baseline pin

### `CompareState.scala`

```scala
// collidesWith gains the active tab's current pin; samePairAs is unchanged
// (it already compares `at`).
def collidesWith(activeBranch: BranchChoice, activeTree: Option[TreeId], activeAt: Option[CommitHash]): Boolean =
  samePairAs(SlotCoordinate.activeTab(activeBranch, activeAt), activeTree)

object SlotCoordinate:
  /** The active tab as a coordinate: its branch, following the active tree, at
    * its current history pin (`baselineAt`) — so a comparand at a different
    * point in time is a distinct pair, not a collision. */
  def activeTab(branch: BranchChoice, at: Option[CommitHash]): SlotCoordinate =
    SlotCoordinate(branch, None, at)
```

### `AnalyzeView.scala`

- `engagedSlots(targets, activeBranch, activeTreeId, activeAt: Option[CommitHash])`
  and `engagedPoolSlots(rowTs, activeBranch, activeTid, activeAt)` gain
  `activeAt`, forwarded to `collidesWith`.
- Thread `compareState.baselineAt.signal` into the combines that must recompute
  engagement on a rewind: the three that call `engagedPoolSlots`
  (`combinedSpecSignal`, `sideBySideSpecs`, the `analyze-lec-panel` child) **and**
  the per-slot effective-tree loader (so a slot that was a same-head collision
  re-engages and loads its tree once the baseline moves off head).
- The per-slot active-branch reset subscription reads `compareState.baselineAt
  .now()` for its `collidesWith` check but is **not** re-triggered by it — a
  rewind must never clear a comparand's chosen target (it only reversibly
  engages/disengages).

## Fix 3b — additive `omitAbsent`

### `WorkspaceAnalysisEndpoints.scala` (common)

Add after the `at` query (sibling to the existing `includeProvenance` flag):

```scala
.in(query[Boolean]("omitAbsent").default(false)
  .description("When true, requested node IDs absent from the tree at this revision are omitted from the result instead of failing the request (point-in-time reads)."))
```

Endpoint input tuple becomes
`(userId, key, treeId, includeProvenance, nodeIds, branch, at, omitAbsent)`.

### `RiskTreeService.scala` + `RiskTreeServiceLive.scala`

```scala
def getLECCurvesMulti(
  wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId],
  seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean,
  rev: Revision, omitAbsent: Boolean = false
): Task[Map[NodeId, LECNodeCurve]]
```

`lookupNodesInTree` gains `omitAbsent`; it skips the missing-node failure when
true (the `nodes` map already contains present ids only):

```scala
private def lookupNodesInTree(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], rev: Revision, omitAbsent: Boolean): Task[(RiskTree, Map[NodeId, RiskNode])] =
  for
    tree    <- getTreeOrFail(wsId, treeId, rev)
    missing  = nodeIds.filterNot(tree.index.nodes.contains)
    _       <- if missing.isEmpty || omitAbsent then ZIO.unit
               else ZIO.fail(ValidationFailed(missing.toList.map(id => ValidationError(
                 field = "nodeIds", code = ValidationErrorCode.NOT_FOUND,
                 message = s"Node ${id.value} not found in tree ${tree.id}"))))
    nodes    = nodeIds.flatMap(id => tree.index.nodes.get(id).map(id -> _)).toMap
  yield (tree, nodes)
```

`getLECCurvesMulti` resolves only the present set and logs any omission (empty
INPUT list still fails `EMPTY_COLLECTION` — unchanged):

```scala
(tree, nodesMap) = treeWithNodes
presentIds       = nodesMap.keySet
_ <- ZIO.when(omitAbsent && presentIds.size < nodeIds.size)(
       ZIO.logInfo(s"lec-multi omitAbsent: ${nodeIds.size - presentIds.size} absent node(s) omitted at $rev"))
results <- resolver.ensureCachedAll(tree, presentIds, seedEntityId, includeProvenance)
```

### `WorkspaceAnalysisController.scala`

Match the extra tuple element and pass it through:

```scala
getWorkspaceLECCurvesMultiEndpoint.serverLogic { case (userId, key, treeId, includeProvenance, nodeIds, branch, at, omitAbsent) =>
  ...
  result <- riskTreeService.getLECCurvesMulti(ws.id, treeId, nodeIds.toSet, ws.seedEntityId, includeProvenance, rev, omitAbsent)
```

### `LECChartState.scala` (client)

`loadCurves` sends `omitAbsent = true` on every fetch:

```scala
getWorkspaceLECCurvesMultiEndpoint(
  (userIdAccessor(), key, treeId, false, nodeIds, branchAccessor(), atAccessor(), true)
)
```

`droppedSelections` is unchanged — with partial results the absent ids fall out
of `curveCache.keySet` and surface via the existing H3 notice, exactly as the
feature was designed.

## Open decisions

None — 3a is a single correct fix; 3b Option B + frontend always-`true` are ruled.

## ADR alignment

- **Security (OWASP API Security Top 10 2023, verified against the source).**
  `omitAbsent` returns a subset of the caller's own already-authorised tree.
  Node existence at a commit is authoritative and readable via
  `getWorkspaceTreeStructureEndpoint(at)`, so omission discloses nothing new,
  crosses no tenant boundary (API1 BOLA is cross-tenant only), is not an
  existence oracle, and is not over-exposure (API3 — it returns less, never
  more). `WorkspaceId` stays server-resolved, never accepted or echoed.
- **API4**: does not change the (already unbounded — pre-existing SHOULD-FIX)
  `nodeIds` body size; not made worse.
- **ADR-001**: `omitAbsent` is a genuine boolean flag, not a domain primitive —
  no Iron type. `Revision`/Iron types elsewhere unchanged.

## Verification

```bash
sbt 'commonJVM/test; server/test'    # + new omitAbsent server tests
sbt app/compile; sbt app/test        # 3a engagement + client wiring
sbt "serverIt/test"                  # endpoint default (omitAbsent=false) — existing lec-multi IT unaffected
```

- **Server tests (RiskTreeServiceLiveSpec, additive):** missing node with
  `omitAbsent = false` → 400 (strict guard intact); with `omitAbsent = true` →
  omitted, present nodes returned; all-absent with `true` → empty map, no
  failure; empty input still `EMPTY_COLLECTION`.
- **App tests:** update `engagedSlots`/`collidesWith` call sites in
  `AnalyzeViewSeedSpec` + `CompareStateSpec` for the new `activeAt`; add a case
  that a comparand at head engages against a baseline pinned to the past (3a).
- **Manual (localhost:18080, rebuilt frontend):** baseline rewound to the past +
  a comparand set to the present of the same tree → both chart (3a); baseline
  rewound before Insider Threat existed → the chart drops it with the H3 notice,
  no error (3b).

## §C3 file inventory (additions to the Scope 2 inventory above)

Added to the Scope 2 `## File inventory`: `AnalyzeViewSeedSpec.scala` (App
tests) and `CascadeTestStubs.scala` (Server tests — the `RiskTreeService` stub
double had to gain the `omitAbsent` param). Already listed:
`WorkspaceAnalysisEndpoints`, `RiskTreeService`, `RiskTreeServiceLive`,
`WorkspaceAnalysisController`, `RiskTreeServiceLiveSpec`, `CompareState`,
`AnalyzeView`, `LECChartState`, `CompareStateSpec`.

## Versioning

PATCH on landing (shipped behaviour changed, server + SPA): `build.sbt` →
`0.10.3` if landed after `0.10.2`, or folded into the still-uncommitted
`0.10.2` if landed together — user's call given the `build.sbt` WIP.
