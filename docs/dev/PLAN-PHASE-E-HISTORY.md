# PLAN — Milestone-2b Phase E: History / Time Travel (Scope 2)

Status: PRESENTED 2026-07-25, awaiting approval. Prerequisite:
`PLAN-C-REFACTOR.md` (Scope 1) landed. All Phase E decisions are ruled
(E1–E7 below); one new decision surfaced during planning (E8, revert
granularity) is presented with a recommendation and needs a ruling at plan
approval.

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
// Live: load the tree at toCommit (Scope 1 internals) — absent → NotFound —
// then write it through the ordinary write path as ONE set_tree commit with
// message workspace:{ws}:risk-tree:{id}:revert. No precondition (E4).
// Invalidation + SSE fire via the shared write path. Permission DesignWrite.
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

### 8. SPA

- History tab: `AnalyzeView` right panel gains Tree | History tabs; NEW
  `TreeHistoryState` (endpoint call + `LoadState`), NEW `HistoryPanel`
  component (commit list; per-commit actions: View at this point / Compare
  to current / Create scenario from here, per §7 of the UI plan).
- Pinned view: `TreeViewState` and `LECChartState` fetches thread the `at`
  pin; read-only banner + suppressed edit affordances while pinned (client
  state).
- Compare-to-current: `SlotCoordinate` (Scope 1) gains
  `at: Option[CommitHash]`; the History panel's compare action fills a free
  slot with (active tree, active branch, pinned commit).
- Fork-from-history: prompt for `ScenarioName`, create with
  `source = Commit(hash)`, switch tab branch, navigate to Design
  (`ScenarioState` create call adapts to the new request shape).
- Revert: BranchBar scenario-menu item "↩ Revert this branch…" enabled;
  NEW `TreeRevertState` + NEW `RevertModal` (destructive confirm naming
  branch and target commit — MergeModal pattern).
- Rename: `ScenarioDiffState` → `ChangedNodesState` (file rename + call
  shape for the new endpoint); `CompareState`/`AnalyzeView` gating from
  Scope 1 Task B adapts to the renamed state.
- `X-Branch` required: transparent to SPA call sites (they already pass
  `BranchChoice` through the shared endpoint definitions; the codec now
  always emits a value).

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
- modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioDiffResponse.scala → ChangedNodesResponse.scala (RENAME)
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala (BranchChoice header/query codec if not co-located in OpaqueTypes)

Server:
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala (NEW)
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioDiffService.scala → ChangedNodesService.scala (RENAME)
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

Server IT:
- modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala (NEW: E3 probe — native revert is a head-set)
- modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala (NEW: path-scoping probe)
- modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala (NEW: forward-commit revert — both states in history; SSE/invalidation fires)
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala

App:
- modules/app/src/main/scala/app/state/TreeHistoryState.scala (NEW)
- modules/app/src/main/scala/app/state/TreeRevertState.scala (NEW)
- modules/app/src/main/scala/app/state/ScenarioDiffState.scala → ChangedNodesState.scala (RENAME)
- modules/app/src/main/scala/app/state/CompareState.scala
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/ScenarioState.scala
- modules/app/src/main/scala/app/components/HistoryPanel.scala (NEW)
- modules/app/src/main/scala/app/components/RevertModal.scala (NEW)
- modules/app/src/main/scala/app/components/BranchBar.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/styles/app.css

App tests:
- modules/app/src/test/scala/app/state/ScenarioDiffStateSpec.scala → ChangedNodesStateSpec.scala (RENAME)
- modules/app/src/test/scala/app/state/TreeHistoryStateSpec.scala (NEW: pure derivations)

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

- None. E8 ruled 2026-07-25 (per-tree, see §5); everything else was ruled
  E1–E7.
- Trivial defaults taken: history page size `n=50`; `HistoryOperation`
  vocabulary as listed; discriminator field name `"type"`.

## Verification

- `sbt compile` (all modules; commonJS/commonJVM cross-build must both pass)
- `sbt 'commonJVM/test; server/test'`, `sbt app/test`
- `sbt serverIt/test` (needs `local/irmin-prod:3.11-p1`) — including the
  three NEW IT specs
- BATS suite C after server changes
- Manual: history list on main and a scenario; view-at-point (tree + chart,
  read-only banner, back-to-current); compare-to-current via pinned slot;
  fork-from-history → lands in Design on the new scenario; revert with
  confirm → both states visible in history; `X-Branch` absent → 400
  (curl check).
- Pass/fail reporting only.

## Versioning

MINOR on landing (external API changes: required renamed header, new
endpoints, changed request/response shapes): `0.7.x → 0.8.0`, mirrored to
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
