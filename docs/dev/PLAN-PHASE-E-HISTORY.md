# PLAN — Milestone-2b Phase E: History / Time Travel (Scope 2)

Status: PRESENTED 2026-07-25 (amended 2026-07-25: RENAME inventory entries
split into old+new full-path bullets so the enforcement hook authorizes the
post-rename files; amended 2026-07-26: §8 re-specified against the landed
compare-UI redesign — history slider replaces the Tree|History tab, rulings
H1–H6, SPA sliced Analyze-first then Design), awaiting approval.
Prerequisites landed: `PLAN-C-REFACTOR.md` (Scope 1) and
`PLAN-COMPARE-UI-REDESIGN.md` (slot-card Analyze layout §8 now builds on).
All Phase E decisions are ruled (E1–E8 below, H1–H6 in §8).

**Slice 0 (backend, §1–§7) IMPLEMENTED 2026-07-27, uncommitted.** All modules
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
("X-Branch absent = main") or corrupt proposal-internal types. Slices E-A
(Analyze slider) and E-B (Design slider + fork/revert UI) remain.

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
`PLAN-C-REFACTOR.md`). Scope 2 builds on the commit-pinned internals it
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
- modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala (IT-surfaced bug: strip Irmin's trailing newline on commit messages so operation-suffix matching works)
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
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/DesignView.scala
- modules/app/src/main/scala/app/views/TreeBuilderView.scala
- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/views/RiskLeafFormView.scala
- modules/app/src/main/scala/app/views/PortfolioFormView.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/styles/app.css

App tests:
- modules/app/src/test/scala/app/state/ScenarioDiffStateSpec.scala (RENAME → ChangedNodesStateSpec.scala; old path, remove after rename)
- modules/app/src/test/scala/app/state/ChangedNodesStateSpec.scala (RENAME target)
- modules/app/src/test/scala/app/state/TreeHistoryStateSpec.scala (NEW: pure derivations)

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

Follow-up surfaced during Scope 2: `RiskTreeServiceLive` checks tree-name and
tree-ID uniqueness against main's head regardless of the branch being written.
Sequenced after Slice 0. Lands as PATCH `0.10.0 → 0.10.1` (`build.sbt` +
`APP_VERSION` mirrored to `.env` and `.env.irmin`).

Both decisions are ruled (2026-07-27, user): **semantics = Option A (per-branch
uniqueness); integration-test coverage = Option A (one HTTP-level IT over real
Irmin fork inheritance).**

## Goal

`create` and `update` check tree-ID and tree-name uniqueness against the head
of the branch being written, not against `Revision.Head(BranchRef.Main)`.

## Bug (verified in code)

In `RiskTreeServiceLive.scala`:

- `ensureUniqueTree(wsId, treeId, treeName, excludeId)` (line 82) calls
  `collectAllTrees(wsId)` (line 109), which calls
  `repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main))` — main's head,
  always.
- `create` (line 331) and `update` (line 357) are the only callers of
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
| `create` (:331) | `ensureUniqueTree(wsId, treeId, resolved.treeName)` | `ensureUniqueTree(wsId, treeId, resolved.treeName, branch)` |
| `update` (:357) | `ensureUniqueTree(wsId, id, resolved.treeName, excludeId = Some(id))` | `ensureUniqueTree(wsId, id, resolved.treeName, branch, excludeId = Some(id))` |
| `ensureUniqueTree` (:83) | `collectAllTrees(wsId)` | `collectAllTrees(wsId, branch)` |
| `collectAllTrees` body (:110) | `repo.getAllForWorkspace(wsId, Revision.Head(BranchRef.Main))` | `repo.getAllForWorkspace(wsId, Revision.Head(branch))` |

No other callers (verified by `grep -rn "ensureUniqueTree\|collectAllTrees" modules/`).

### Comment update (doc-consistency, same pass)

The comment above `collectAllTrees` (:106–108) becomes current-state:

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
`docs/scratch/milestone-2b-cache-and-decisions.md`, "Deferred: Phase D Option-2
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
with the uniqueness check — over the real service+repo+Irmin path. The spec
does not currently drive scenario endpoints, so this test adds that wiring.

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

Landing: PATCH bump `build.sbt` to `0.10.1`, mirror `APP_VERSION=0.10.1` into
`.env` and `.env.irmin`, flag plan completion.

## Continuation §C1 file inventory (additions to the Scope 2 inventory above)

All folded into the Scope 2 `## File inventory` section — the enforcement hook
reads only that heading. `RiskTreeServiceLive.scala`, `RiskTreeServiceLiveSpec.scala`,
and `build.sbt` were already listed there; `HttpApiIntegrationSpec.scala` was
added to the Server IT block for this continuation. `.env`/`.env.irmin` are
ungated (no bullet needed). No other files.
