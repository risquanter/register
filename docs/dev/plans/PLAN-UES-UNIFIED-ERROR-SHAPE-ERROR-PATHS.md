# Error paths — the current state, one row per path, and each row's target

The supporting record for `PLAN-UES-UNIFIED-ERROR-SHAPE.md`. §1 to §7 state
what the code does on 2026-11-09, at version 0.17.2, and nothing about what it
should do. Every row names the file and line the value is built at, so a row can
be re-checked against the code. §8 states the target shape and §9 each row's
value under it. The plan's phases refer to rows by this numbering, and the plan
is done when no row's "Value as built" remains in the code.

## 1. The `AppError` hierarchy, first level

`AppError` is the one sealed root of every failure the server produces as a
value. It is declared in
`modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
line 47. Three members are declared on the root:

- `logMessage: String` — the server's own text, for the log line. It never
  crosses to a client.
- `origin: FailureOrigin` — `Caller` or `Service`, abstract, so every leaf
  states its own. It decides the log level: `Caller` → INFO, `Service` → WARN
  (`WireEither.scala` lines 24–26).
- `logAnnotations: Map[String, String]` — structured fields for the log line,
  empty unless overridden.

The first level below the root is four sealed traits. None of the four adds a
member, and none of the four is matched on: `WireError.fromAppError` matches
the 32 leaves directly. Two of the four are used as a type in signatures.

| Sub-trait | Leaves | Used as a type | What the grouping is keyed on |
|---|---|---|---|
| `DomainError` | 16: `ValidationFailed`, `RepositoryFailure`, `TreeLoadFailure`, `SimulationFailure`, `DataConflict`, `AccessDenied`, `WorkspaceNotFound`, `WorkspaceExpired`, `TreeNotInWorkspace`, `TreeAbsentAtRevision`, `VersionConflict`, `MergeConflict`, `MergeAlreadyRunning`, `MergeTargetMoved`, `ScenarioHeadStale`, `ScenariosNotSupported` | nowhere | everything that is not one of the other three |
| `IrminError` | 7: `IrminUnavailable`, `IrminHttpError`, `IrminGraphQLError`, `NetworkTimeout`, `BranchAlreadyExists`, `BranchHeadStale`, `IrminMergeConflict` | `IrminClient`'s 20 method signatures, `IO[IrminError, A]`; the two `handleIrmin` helpers | the peer: Irmin produced it, whether a transport failure (`Service`) or a compare-and-set answer (`Caller`) |
| `AuthError` | 2: `AuthForbidden`, `AuthServiceUnavailable` | `AuthorizationService` and `BootstrapProvisioner` signatures, `IO[AuthError, A]` | the peer: SpiceDB produced it, whether a denial (`Caller`) or an outage (`Service`) |
| `FolQueryFailure` | 7: `FolParseFailure`, `FolUnknownReference`, `FolBindFailure`, `FolModelValidationFailure`, `FolDomainNotQuantifiable`, `FolEvaluationFailure`, `SimulationNotCached` | the return type of `FolQueryFailure.fromQueryError` | the peer: the query engine produced it, except `SimulationNotCached`, which is this service's precondition |

So the first level is keyed on a peer for three of the four traits and is the
remainder for the fourth. `origin` is per leaf and cuts across all four. Two
leaves are declared and never built: `SimulationFailure` and `VersionConflict`
have no construction site under `modules/server/src/main` or
`modules/common/src/main`.

Three things that carry failure information and are **not** `AppError`s:

- `ValidationError(location, problem)` — one refusal. It is the element of
  `ValidationFailed.errors` and of `WireError.RequestRejected.errors`, and it
  is a wire type.
- `WireError` — the projection of an `AppError` onto the wire, nine cases, one
  per status. The decode-failure handler builds a `WireError` directly with no
  `AppError` behind it.
- a ZIO defect — `ZIO.die`, `orDie`, a thrown exception. It carries no
  `AppError` and is answered by `DefectCatchAllInterceptor`.

## 2. How to read the tables

One row is one path: one place a failure is built, with one shape. Two paths
that build the same shape at the same place with different field values are
one row. The columns:

- **Path** — what happened, in ordinary words.
- **Built at** — the file and line where the value is constructed.
- **Value as built** — the error with its fields filled in with representative
  values.
- **AppError?** — yes if the value built is an `AppError`; no if it is a
  `ValidationError`, a `WireError` or a defect.
- **origin → level** — the leaf's `origin` and the log level `WireEither`
  derives from it.
- **Wire → status** — the `WireError` case `fromAppError` projects to and its
  status.

Status per wire case, from `BaseEndpoint.scala` lines 113–121: `RequestRejected`
400, `ValidationRejected` 422, `AccessDenied` 403, `NotFound` 404,
`WorkspaceExpired` 410, `Conflict` 409, `NotSupported` 501,
`DependencyUnavailable` 503, `InternalFault` 500. `ValidationFailed(errors)`
projects to `ValidationRejected` when the route carries a tree and to
`RequestRejected` otherwise (`WireError.scala` lines 135–138), written "422/400"
below.

The tables are grouped by the trust zone the failing value came from. The
glossary (`docs/user/TERMINOLOGY.md` §8.1) defines a trust boundary as "the
line a value may not cross without being checked" and has no entry for the
zone on either side of it; "zone" here means the side the value came from. No
field in the code carries the zone; it is reconstructed per row from the
construction site.

## 3. Zone: the client's request

| # | Path | Built at | Value as built | AppError? | origin → level | Wire → status |
|---|---|---|---|---|---|---|
| 1 | A codec refuses an input: absent, unreadable, or an Iron refinement refused it | `WireDecodeFailureHandler.scala:59-66` | no `AppError`. `ValidationError(Whole \| Header(Branch) \| QueryParameter(Limit), Required \| Unreadable(Declared) \| Unreadable(SingleValue) \| OutOfRange(…) \| DeclaredConstraintNotMet)` | no | none; the handler logs INFO itself | `RequestRejected(List(e))` → 400 |
| 2 | A smart constructor refuses the resolved request body (`RiskTreeDefinitionRequest.resolve`, `RiskTree.fromNodes`, `SeedVarIdAssigner`, `toShape`) | `RiskTreeServiceLive.scala:299-314`, `:328-363`; `DistributionPreviewController.scala:50` | `ValidationFailed(List(ValidationError(Field(NodeAtIndex(Leaves, 2), Probability), OutOfRange(ClosedUnitInterval)), ValidationError(Field(Request, RootId), RootIdNotFound(nodeId))))` | yes | Caller → INFO | `ValidationRejected(tree, Fields(errors))` / `RequestRejected(errors)` → 422/400 |
| 3 | A node id the request names is not in the tree | `RiskTreeServiceLive.scala:51`, `:63` | `ValidationFailed(List(ValidationError(Field(ExistingNode(nodeId), Id), NodeNotFound)))` | yes | Caller → INFO | `ValidationRejected` → 422 |
| 4 | Tree id or name already taken in the workspace | `RiskTreeServiceLive.scala:74-77` | `ValidationFailed(List(ValidationError(Field(Request, Id), TreeIdAlreadyExists(treeId))))`; same with `(Field(Request, Name), TreeNameAlreadyExists(name))` | yes | Caller → INFO | 422/400 |
| 5 | Empty node id set on a multi-node read | `RiskTreeServiceLive.scala:453` | `ValidationFailed(List(ValidationError(Field(Request, Nodes), NodeIdsEmpty)))` | yes | Caller → INFO | 422 |
| 6 | Scenario source cannot be resolved: main has no commit, fork source unknown, commit not in own history | `ScenarioServiceLive.scala:64-85` | `ValidationFailed(List(ValidationError(Field(Request, Source), WorkspaceHasNoContent \| ScenarioNotFound(scenario) \| CommitNotFound(commit))))` | yes | Caller → INFO | 400 |
| 7 | `X-Branch` names a scenario that does not exist | `WorkspaceLifecycleController.scala:114` | `ValidationFailed(List(ValidationError(Header(Branch), ScenarioNotFound(name))))` | yes | Caller → INFO | 400 |
| 8 | Merge of a scenario with no head | `ScenarioMergeService.scala:205` | `ValidationFailed(List(ValidationError(Field(Request, Scenario), ScenarioNotFound(name))))` | yes | Caller → INFO | 400 |
| 9 | Commit pin or revert on the in-memory backend | `RiskTreeRepositoryInMemory.scala:44`, `:94` | `ValidationFailed(List(ValidationError(Field(Request, At \| ToCommit), RequiresIrminBackend)))` | yes | Caller → INFO | 422/400 |
| 10 | Workspace tree ceiling reached | `WorkspaceStore.scala:152` | `ValidationFailed(List(ValidationError(Field(Workspace, Trees), MaxTreesReached(limit))))` | yes | Caller → INFO | 400 |
| 11 | Requested seed entity id already held (in-memory) | `WorkspaceStoreLive.scala:106` | `ValidationFailed(List(ValidationError(Field(Workspace, SeedEntityId), SeedEntityIdInUse(id))))` | yes | Caller → INFO | 400 |
| 12 | Requested seed entity id already held (Postgres): the driver's unique-violation text contains `seed_entity_id` | `WorkspaceStorePostgres.scala:80-83` | first `RepositoryFailure("ERROR: duplicate key … seed_entity_id …")`, then rewrapped to the same `ValidationFailed` as row 11, selected by `reason.contains("seed_entity_id")` | yes | Caller → INFO | 400 |
| 13 | Distribution preview parameters do not fit | `DistributionPreviewService.scala:70`, `:79` | `ValidationFailed(List(ValidationError(Whole, MetalogFitFailed \| LognormalFitFailed)))` | yes | Caller → INFO | 400 |
| 14 | Workspace key resolves to nothing | `WorkspaceStorePostgres.scala:236`; `WorkspaceStoreLive.scala:53` | `WorkspaceNotFound()`; annotations `event_type=workspace.resolve_failed, reason=not_found` | yes | Caller → INFO | `NotFound(Unknown())` → 404 |
| 15 | Workspace past a deadline | `WorkspaceStorePostgres.scala:231`; `WorkspaceStoreLive.scala:55` | `WorkspaceExpired(wsId, ttl=72h, idleTimeout=1h, cause=Idle, expiredAt)`; annotations `workspace_id, expiry_cause, expired_at` | yes | Caller → INFO | `WorkspaceExpired(Idle, 72h, 1h)` → 410 |
| 16 | Tree id not among the workspace's trees | `WorkspaceStore.scala:78` | `TreeNotInWorkspace(wsId, treeId)`; annotations `workspace_id, tree_id` | yes | Caller → INFO | `NotFound(Unknown())` → 404 |
| 17 | Tree held, but absent at the revision read | `RiskTreeServiceLive.scala:43`; `QueryServiceLive.scala:83`; `WorkspaceTreeController.scala:53`, `:67`; `RiskTreeRepositoryIrmin.scala:76`, `:241`; `RiskTreeRepositoryInMemory.scala:67`, `:84` | `TreeAbsentAtRevision(wsId, treeId, Head(branch))` or `(…, At(commit))`; annotations `workspace_id, tree_id, revision` | yes | Caller → INFO | `NotFound(TreeNotOnBranch(Scenario(name)))` / `NotFound(TreeNotAtCommit())` → 404 |
| 18 | Scenario name already exists (translated from Irmin's `BranchAlreadyExists`, row 49) | `ScenarioServiceLive.scala:31` | `DataConflict("Scenario 'x' already exists")` | yes | Caller → INFO | `Conflict(Rejected())` → 409 |
| 19 | Scenario delete with a stale `If-Match` (translated from `BranchHeadStale`, row 50) | `ScenarioServiceLive.scala:50` | `ScenarioHeadStale(name, expected, actual=Some(hash))` | yes | Caller → INFO | `Conflict(ScenarioHeadStale(name))` → 409 |
| 20 | Merge refused by the scan's verdict | `ScenarioMergeService.scala:209` | `MergeConflict(name, refusal)` | yes | Caller → INFO | `Conflict(MergeRefused(name, refusal))` → 409 |
| 21 | Merge refused by Irmin (translated from `IrminMergeConflict`, row 51) | `ScenarioMergeService.scala:278` | `MergeConflict(name, BranchesConflict())` | yes | Caller → INFO | 409 |
| 22 | Merged state fails a tree invariant; the guard reads every tree at the staged commit | `ScenarioMergeService.scala:320` | `MergeConflict(name, InvariantBroken(List(treeId)))`, built from `TreeLoadFailure`s (row 38); their text goes to a separate `logError` | yes | Caller → INFO | 409 |
| 23 | A merge of the scenario is already running (translated from `BranchAlreadyExists` on the staging branch) | `ScenarioMergeService.scala:260` | `MergeAlreadyRunning(name)` | yes | Caller → INFO | `Conflict(MergeAlreadyRunning(name))` → 409 |
| 24 | Main moved during the merge (translated from `BranchHeadStale`) | `ScenarioMergeService.scala:287` | `MergeTargetMoved(name)` | yes | Caller → INFO | 409 |
| 25 | Query on a tree with no cached simulation | `QueryServiceLive.scala:113` | `SimulationNotCached(treeId)`; annotation `tree_id` | yes | Caller → INFO | `Conflict(SimulationRequired(treeId))` → 409 |
| 26 | Query text refused by the engine | `QueryController.scala:53`; `QueryServiceLive.scala:127`, `:141` | `FolParseFailure(message, Some(14))`, `FolUnknownReference(List(msg))`, `FolBindFailure(List(msg))`, `FolDomainNotQuantifiable(message)` | yes | Caller → INFO | `ValidationRejected(tree, Query(Unparseable \| UnknownReferences \| TypeCheckFailed \| NotQuantifiable))` → 422 |
| 27 | Scenario operation on the in-memory deployment | `ScenarioServiceNotSupported.scala:22`, `:25`, `:29`; `ScenarioMergeServiceNotSupported.scala:18`, `:22` | `ScenariosNotSupported(reason)` | yes | Caller → INFO; the value is deployment configuration, not the caller's | `NotSupported(Scenarios)` → 501 |

## 4. Zone: the mesh claim header, and SpiceDB

| # | Path | Built at | Value as built | AppError? | origin → level | Wire → status |
|---|---|---|---|---|---|---|
| 28 | `x-user-id` absent where required | `UserContextExtractor.scala:106` | `AccessDenied("Missing x-user-id header — …")` | yes | Caller → INFO | `AccessDenied` → 403 |
| 29 | SpiceDB answers no permission | `AuthorizationServiceSpiceDB.scala:153` | `AuthForbidden(userId, permission="read", resourceType="workspace", resourceId)` | yes | Caller → INFO | `AccessDenied` → 403 |
| 30 | SpiceDB cannot be reached | `AuthorizationServiceSpiceDB.scala:143`, `:196`; `BootstrapProvisionerSpiceDB.scala:96` | `AuthServiceUnavailable("SpiceDB check request failed: Connection refused", Some(e))` | yes | Service → WARN | `DependencyUnavailable` → 503 |
| 31 | SpiceDB answers a non-2xx status | `AuthorizationServiceSpiceDB.scala:164`, `:203` | `AuthServiceUnavailable("SpiceDB check returned HTTP 500", None)` | yes | Service → WARN | 503 |
| 32 | SpiceDB's body does not parse | `AuthorizationServiceSpiceDB.scala:160`, `:201` | `AuthServiceUnavailable("SpiceDB check response parse error: …", None)` | yes | Service → WARN | 503 |

## 5. Zone: the Irmin store

Rows 33–37 are built in `IrminClientLive` and leave the client as an
`IrminError`. What happens to them next depends on the caller. `ScenarioServiceLive`
and `ScenarioMergeService` call the client directly, so the `IrminError` reaches
`fromAppError` and answers 503. `RiskTreeRepositoryIrmin` and `TreeHistoryService`
wrap every client call in `handleIrmin` (`RiskTreeRepositoryIrmin.scala:251`,
`TreeHistoryService.scala:52`), which replaces the value with
`RepositoryFailure(err.logMessage)`, so the same failure answers 500 there. "503
or 500" in a row means that split.

| # | Path | Built at | Value as built | AppError? | origin → level | Wire → status |
|---|---|---|---|---|---|---|
| 33 | Connection refused, or any other network exception | `IrminClientLive.scala:284`, `:288` | `IrminUnavailable("Connection refused")`, `IrminUnavailable("Network error: …")` | yes | Service → WARN | `DependencyUnavailable` → 503, or `RepositoryFailure("Irmin service unavailable: Connection refused")` → `InternalFault` → 500 |
| 34 | Timeout | `IrminClientLive.scala:285-287` | `NetworkTimeout("GraphQL request", 30 s)` | yes | Service → WARN | 503 or 500 |
| 35 | HTTP 503 from Irmin; another HTTP status; an undecodable response body | `IrminClientLive.scala:270`, `:272`, `:274` | `IrminUnavailable("Service returned 503: body")`, `IrminHttpError(status, body)`, `IrminHttpError(500, "Invalid response: …")` | yes | Service → WARN | 503 or 500 |
| 36 | GraphQL `errors` in the response | `IrminClientLive.scala:145`, `:305`, `:343`, `:347` | `IrminGraphQLError(List(msg), Some(List("main", "tree")))` | yes | Service → WARN | 503 or 500 |
| 37 | A listed child name is not a valid segment | `IrminClientLive.scala:317` | `IrminUnavailable(<segment message>)` — refused content reported as an outage | yes | Service → WARN | 503 or 500 |
| 38 | One tree in a listing fails to load | `RiskTreeRepositoryIrmin.scala:107-108` | `TreeLoadFailure(treeId, Empty)` or `TreeLoadFailure(treeId, Failed(cause: AppError))`; annotation `tree_id` | yes | Service → WARN | never reaches the wire as itself: `RiskTreeServiceLive.scala:96` joins the `logMessage`s into `RepositoryFailure(text)` → 500; the merge guard turns it into row 22 → 409 |
| 39 | Branch head hash does not refine | `RiskTreeRepositoryIrmin.scala:208` | `RepositoryFailure("Invalid branch head hash: [request] Wrong format CommitHash")` | yes | Service → WARN | `InternalFault` → 500 |
| 40 | Commit hash in history does not refine | `TreeHistoryService.scala:48` | `RepositoryFailure("Invalid commit hash in history: …")` | yes | Service → WARN | 500 |
| 41 | Commit hash does not refine, scenario path | `ScenarioBranchOps.scala:29-35` | no `AppError`: `ZIO.die(IllegalStateException)` | no | defect; `logErrorCause` in `DefectCatchAllInterceptor.scala:121` | `InternalFault` → 500 |
| 42 | Listed tree id does not refine | `RiskTreeRepositoryIrmin.scala:179`; `ScenarioMergeService.scala:564` | `RepositoryFailure("[request] Wrong format Ulid")`; `RepositoryFailure("Listed tree 'x' does not refine: …")` | yes | Service → WARN | 500 |
| 43 | Stored `meta` does not decode | `RiskTreeRepositoryIrmin.scala:129` | `RepositoryFailure("Decode meta failed: .rootId(missing)")` | yes | Service → WARN | 500 |
| 44 | Stored node does not decode | `RiskTreeRepositoryIrmin.scala:146`; `ScenarioMergeService.scala:472`, `:520` | `RepositoryFailure("Decode node <segment>: …")` | yes | Service → WARN | 500 |
| 45 | Stored mitigation does not decode | `RiskTreeRepositoryIrmin.scala:162` | `RepositoryFailure("Decode mitigation <segment>: …")` | yes | Service → WARN | 500 |
| 46 | Stored tree fails `RiskTree.fromNodes` | `RiskTreeRepositoryIrmin.scala:175` | `RepositoryFailure("[request.RootId] …; [node X.SeedVarId] …")` — the `ValidationError` list flattened to text, the locations still saying `request` | yes | Service → WARN | 500 |
| 47 | Node is a leaf on one side of a merge and a portfolio on the other | `ScenarioMergeService.scala:113` | `RepositoryFailure("node X is a leaf on one side …")` | yes | Service → WARN | 500 |
| 48 | Store layout disagrees with itself: node path listed but value absent; meta absent while nodes exist; no nodes; root not among nodes; main has no head; staging ref does not refine | `RiskTreeRepositoryIrmin.scala:138`, `:156`, `:166`, `:222`, `:246`; `ScenarioMergeService.scala:220`, `:256` | `RepositoryFailure("Missing node value at <path>")`, `("Metadata missing for tree X but nodes exist")`, `("No nodes found for tree X")`, `("Root X not found in provided nodes")`, `("main has no head")`, `("staging branch … failed refinement")` | yes | Service → WARN | 500 |
| 49 | Compare-and-set refused: branch exists | `IrminClientLive.scala:165` | `BranchAlreadyExists(branch)` | yes | Caller → INFO | translated to rows 18 and 23; untranslated: `InternalFault` → 500 at INFO |
| 50 | Compare-and-set refused: head moved | `IrminClientLive.scala:175`, `:185` | `BranchHeadStale(branch, expectedHead)` | yes | Caller → INFO | translated to rows 19 and 24; untranslated: 500 at INFO |
| 51 | Irmin reports a merge conflict | `IrminClientLive.scala:144` | `IrminMergeConflict(reason)` | yes | Caller → INFO | translated to row 21; untranslated: 500 at INFO |

## 6. Zone: Postgres, and the in-memory stores

| # | Path | Built at | Value as built | AppError? | origin → level | Wire → status |
|---|---|---|---|---|---|---|
| 52 | Any driver exception, including the database being down | `WorkspaceStorePostgres.scala:268` | `RepositoryFailure(e.getMessage)` | yes | Service → WARN | `InternalFault` → 500. An outage is 500 here, 503 for Irmin and SpiceDB |
| 53 | Row's `seed_entity_id` does not refine | `WorkspaceStorePostgres.scala:254` | `RepositoryFailure("Corrupt seed_entity_id 0: …")` | yes | Service → WARN | 500 |
| 54 | Row's interval text does not parse | `WorkspaceStorePostgres.scala:288` | `RepositoryFailure(<parse message>)` | yes | Service → WARN | 500 |
| 55 | In-memory stores: seed entity id counter exhausted; tree already exists; a branch other than main | `WorkspaceStoreLive.scala:113`; `RiskTreeRepositoryInMemory.scala:32`, `:52` | `RepositoryFailure(text)` | yes | Service → WARN | 500 |

## 7. Zone: this service's own computation over stored content

| # | Path | Built at | Value as built | AppError? | origin → level | Wire → status |
|---|---|---|---|---|---|---|
| 56 | Stored leaf's distribution does not fit at simulation time | `Simulator.scala:197` | `ValidationFailed(List(ValidationError(Field(ExistingNode(leafId), DistributionType), MetalogFitFailed)))`; the diagnosis goes to a separate INFO line | yes | Caller → INFO | `ValidationRejected` → 422 |
| 57 | Resolver re-checks index guarantees: node not in index, child not found, portfolio has no children, leaf has no content hash | `CachedResultResolverLive.scala:121`, `:151`, `:156`, `:186` | `ValidationFailed(List(ValidationError(Field(ExistingNode(nodeId), Id), NodeNotInIndex)))`, `(Field(ExistingNode(pid), ChildIds), ChildNotFound(cid))`, `(…, PortfolioHasNoChildren(Nil))`, `(Field(ExistingNode(lid), ContentHash), LeafUnreachable)` | yes | Caller → INFO | 422 |
| 58 | Aggregation overflow, or a mitigation refusal during the fold | `CachedResultResolverLive.scala:173` | `ValidationFailed(errs)` with `AggregatedLossOverflow`, `MitigationEliminatesRisk`, `MitigationLowersMinLossBelowFloor(…)` | yes | Caller → INFO | 422 |
| 59 | Engine model wiring or evaluation failure | `FolQueryFailure.fromQueryError`, `AppError.scala:655-659` | `FolModelValidationFailure(List(msg))`, `FolEvaluationFailure(message, phase)` | yes | Service → WARN | `InternalFault` → 500 |
| 60 | Any uncaught defect (`orDie`, `ZIO.die`, a thrown exception) | `DefectCatchAllInterceptor.scala:121-124` | no `AppError` | no | `logErrorCause` | `InternalFault` → 500 |

## 8. Target shape — current working version

One record, generic in the two things a variant fixes, and one flat sum whose
variants are the wire's answerable kinds. `AppError`, its four sub-traits and
its 32 leaves do not exist in this shape, and neither does `ValidationFailed`.
`InternalFault` is not a variant: it is what a non-client value projects to.

```scala
final case class Core[Z, P](
  zone:        Z,
  errors:      NonEmptyList[(ErrorLocation, P)],
  diagnostics: Diagnostics                       // log-only; type still open
)

enum Failure:
  case Rejected    (core: Core[TrustZone, FieldProblem])
  case Unanswered  (core: Core[PeerZone,  OutageMode])
  case NotFound    (core: Core[TrustZone, NotFoundKind])
  case Conflict    (core: Core[TrustZone, ConflictKind])
  case AccessDenied(core: Core[TrustZone, DenialKind])    // today a single case; the type keeps the slot
  case Expired     (core: Core[Client.type, ExpiryDetail])   // ExpiryDetail(cause, lifetime, idleLimit)
  case NotSupported(core: Core[Environment.type, Capability])
```

`Z` is the type of the `zone` field. `P` is the type of the problem half of each
`(location, problem)` element. A variant adds no field; it picks the two types.

| Variant | Z — the zones it can come from | P — the problems its list can hold |
|---|---|---|
| `Rejected` | `TrustZone`, any zone | `FieldProblem` |
| `Unanswered` | `PeerZone`: `Store`, `Database`, `Authorization` | `OutageMode`: refused, timeout, status, protocol |
| `NotFound` | `TrustZone` | `NotFoundKind` |
| `Conflict` | `TrustZone` | `ConflictKind` |
| `AccessDenied` | `TrustZone` | `DenialKind` |
| `Expired` | `Client.type`, one value | `ExpiryDetail(cause, lifetime, idleLimit)` |
| `NotSupported` | `Environment.type`, one value | `Capability` |

`TrustZone` has the cases `Client`, `Environment`, `MeshHeader`,
`Peer(PeerZone)` and one case for this service's own values, whose name is not
yet chosen, so `zone` is total over the sum. `NotFoundKind`,
`ConflictKind`, `Capability` and `ExpiryCause` are the payload enums the wire
declares today and are reused unchanged. `OutageMode` and `DenialKind` are new.

`P` cuts the problem sum per variant: a `ConflictKind` cannot appear in a
refusal list, an `OutageMode` cannot appear in a lookup miss. `Z` cuts the zone
sum: an outage cannot come from `Client`, an expiry cannot come from `Store`.
Where a variant keeps `TrustZone`, the open cells are meaningful: a `NotFound`
at `Store` is a value a listing promised and the store does not hold.

Every derived value — `zone`, `logMessage`, `logAnnotations`, `origin`, the
log level, the wire case and its status — is one total function over
`(variant, zone)`, written once on `Core` given a `describe: P => String`.
The projection to the wire drops `zone` and `diagnostics`, adds the trace, and
passes the list through.

Inside this service the rule is by kind of check. A check that builds an
aggregate, a type or an instance within one is a `Failure` at the service's
own zone. A condition analysis says cannot hold at runtime is a `require`,
kept as defence in depth, and its violation is a defect. A `require` inside an
aggregate's own type is first a prompt to ask what the domain lacks.

`Diagnostics` is a closed set of typed entries, `List[Diagnostic]`, where each
case fixes its log key and the type of its value, so a site cannot log a value
under a wrong key or log a value the case does not admit (ADR-037).

The Irmin compare-and-set outcomes are return values of `IrminClient`
(`Applied` or `Refused(head)`), not failures. The scenario services match on
the outcome and build the caller's `Conflict`; an unhandled outcome is an
inexhaustive match. `Conflict` at `Peer(Store)` therefore has no producer.

## 9. Each row under the target shape

The same sixty rows, each as a value of §8's shape. The columns are the three
fields of `Core` and the values derived from them. A case marked NEW does not
exist in the code today. A row marked `require` builds no `Failure`: the
condition is one analysis says cannot hold at runtime, and its violation is a
defect answered by `DefectCatchAllInterceptor`. Row 1 is parked and not mapped.

| # | Variant | `zone` | `errors` — `(location, P)` | `diagnostics` (log-only) | Derived: wire → status; origin |
|---|---|---|---|---|---|
| 1 | parked | | | | |
| 2 | `Rejected` | `Client` | as today, e.g. `(Field(NodeAtIndex(Leaves, 2), Probability), OutOfRange(ClosedUnitInterval))` | — | 422/400; Caller |
| 3 | `Rejected` | `Client` | `(Field(ExistingNode(nodeId), Id), NodeNotFound)`, one per missing node | — | 422; Caller |
| 4 | `Rejected` | `Client` | `(Field(Request, Id), TreeIdAlreadyExists(treeId))`, `(Field(Request, Name), TreeNameAlreadyExists(name))` | — | 422/400; Caller |
| 5 | `Rejected` | `Client` | `(Field(Request, Nodes), NodeIdsEmpty)` | — | 422; Caller |
| 6 | `Rejected` | `Client` | `(Field(Request, Source), WorkspaceHasNoContent \| ScenarioNotFound(s) \| CommitNotFound(c))` | — | 400; Caller |
| 7 | `Rejected` | `Client` | `(Header(Branch), ScenarioNotFound(name))` | — | 400; Caller |
| 8 | `Rejected` | `Client` | `(Field(Request, Scenario), ScenarioNotFound(name))` | — | 400; Caller |
| 9 | `NotSupported` | `Environment` | `(QueryParameter(At) \| Field(Request, ToCommit), Capability.<pinned reads> NEW)` | — | 501; Service |
| 10 | `Rejected` | `Client` | `(Field(Workspace, Trees), MaxTreesReached(limit))` | — | 400; Caller |
| 11 | `Rejected` | `Client` | `(Field(Workspace, SeedEntityId), SeedEntityIdInUse(id))` | — | 400; Caller |
| 12 | `Rejected` | `Client` | as row 11, built by `classify` on `SQLState 23505` | driver message | 400; Caller |
| 13 | `Rejected` | `Client` | `(Whole, MetalogFitFailed \| LognormalFitFailed)` | — | 400; Caller |
| 14 | `NotFound` | `Client` | `(Field(Request, WorkspaceKey), Unknown())` | `event_type`, `reason` | 404; Caller |
| 15 | `Expired` | `Client` | `(Field(Request, WorkspaceKey), ExpiryDetail(Idle, 72h, 1h))` | `workspace_id`, `expired_at` | 410; Caller |
| 16 | `NotFound` | `Client` | `(Field(Request, Id), Unknown())` | `workspace_id`, `tree_id` | 404; Caller |
| 17 | `NotFound` | `Client` | `(Header(Branch), TreeNotOnBranch(choice))` or `(QueryParameter(At), TreeNotAtCommit())` | `workspace_id`, `tree_id`, `revision` | 404; Caller |
| 18 | `Conflict` | `Client` | `(Field(Request, Name), ConflictKind.Rejected())` | — | 409; Caller |
| 19 | `Conflict` | `Client` | `(Header(IfMatch), ScenarioHeadStale(name))` | `expected`, `actual` | 409; Caller |
| 20 | `Conflict` | `Client` | `(Field(Request, Scenario), MergeRefused(name, refusal))` | — | 409; Caller |
| 21 | `Conflict` | `Client` | `(Field(Request, Scenario), MergeRefused(name, BranchesConflict()))` | Irmin's reason | 409; Caller |
| 22 | `Conflict` | `Client` | `(Field(Request, Scenario), MergeRefused(name, InvariantBroken(treeIds)))` | the failed trees' problems | 409; Caller |
| 23 | `Conflict` | `Client` | `(Field(Request, Scenario), MergeAlreadyRunning(name))` | — | 409; Caller |
| 24 | `Conflict` | `Client` | `(Field(Request, Scenario), MergeTargetMoved(name))` | — | 409; Caller |
| 25 | removed | | the `mapError` at `QueryServiceLive.scala:113` is deleted and each `ensureCachedAll` failure keeps its own row's classification (rows 56, 57, 58a, 58b), because a cache miss simulates and nothing records a prior simulation; `SimulationNotCached` and `ConflictKind.SimulationRequired` go with it | | each row's own |
| 26 | `Rejected` | `Client` | `(Whole, FieldProblem.Query(QueryProblem) NEW)`; the projection emits `RejectionDetail.Query(problem)`, so the wire is unchanged | — | 422; Caller |
| 27 | `NotSupported` | `Environment` | `(Whole, Capability.Scenarios)` | reason | 501; Service |
| 28 | `AccessDenied` | `MeshHeader` | `(Header(UserId), DenialKind.<header absent> NEW)` | — | 403; Caller |
| 29 | `AccessDenied` | `Peer(Authorization)` | `(Whole, DenialKind.<no permission> NEW)` | `user`, `permission`, `resource_type`, `resource_id` | 403; Caller |
| 30 | `Unanswered` | `Authorization` | `(Whole, OutageMode.ConnectionRefused)` | exception message | 503; Service |
| 31 | `Unanswered` | `Authorization` | `(Whole, OutageMode.Status(500))` | — | 503; Service |
| 32 | `Rejected` | `Peer(Authorization)` | `(Whole, Unreadable(ExpectedInput.Json NEW))` | parse message | 500; Service |
| 33 | `Unanswered` | `Store` | `(Whole, OutageMode.ConnectionRefused)` | exception message | 503 from every caller; Service |
| 34 | `Unanswered` | `Store` | `(Whole, OutageMode.Timeout(30 s))` | — | 503; Service |
| 35 | `Unanswered` | `Store` | `(Whole, OutageMode.Status(503 \| code))`, `(Whole, OutageMode.Protocol)` for an undecodable body | HTTP body | 503; Service |
| 36 | `Unanswered` | `Store` | `(Whole, OutageMode.Protocol)` | GraphQL messages, path | 503; Service |
| 37 | `Rejected` | `Peer(Store)` | `(Whole, WrongFormat(ValueFormat.StoreSegment NEW))` | the segment | 500; Service |
| 38 | dissolves | `Peer(Store)` | the failed trees' own rows, accumulated in one list at `Field(Tree(treeId), …)` (`ErrorSubject.Tree(treeId)` NEW); an empty tree is row 48 c | — | 500; Service. `TreeLoadFailure` and the join at `RiskTreeServiceLive.scala:96` go |
| 39 | `Rejected` | `Peer(Store)` | `(Whole, WrongFormat(CommitHash))`, refused in the client | the raw hash | 500; Service |
| 40 | `Rejected` | `Peer(Store)` | as row 39, from `getHistory` | the raw hash | 500; Service |
| 41 | gone | | the client returns a refined `CommitHash`, so nothing is left to refine | | |
| 42 | `Rejected` | `Peer(Store)` | `(Whole, WrongFormat(Ulid))` | the listed segment | 500; Service |
| 43 | `Rejected` | `Peer(Store)` | `(Field(Tree(treeId), RootId), Required)` and the other `meta` fields | — | 500; Service |
| 44 | `Rejected` | `Peer(Store)` | `(Field(ExistingNode(nodeId), Probability), OutOfRange(ClosedUnitInterval))` | — | 500; Service |
| 45 | `Rejected` | `Peer(Store)` | `(Whole, Unreadable(Json))` for text that is not the raw form's JSON, which includes a spec that does not decode; a refused field at `Field(Mitigation(mid), f)` | — | 500; Service |
| 46 | `Rejected` | `Peer(Store)` | `RiskTree.fromNodes`'s list, tree-own fields at `Field(Tree(treeId), f)` | — | 500; Service |
| 47 | `Rejected` | `Peer(Store)` | `(Field(ExistingNode(id), Id), FieldProblem.<node kind differs between merge sides> NEW)` | — | 500; Service |
| 48 a | `NotFound` | `Peer(Store)` | `(Field(ExistingNode(id), Id), NotFoundKind.<listed value absent> NEW)` | storage path | 500; Service |
| 48 b | `NotFound` | `Peer(Store)` | `(Field(Tree(treeId), Id), NotFoundKind.<metadata absent> NEW)` | — | 500; Service |
| 48 c | `NotFound` | `Peer(Store)` | `(Field(Tree(treeId), Nodes), NotFoundKind.<no nodes> NEW)` | — | 500; Service |
| 48 d | gone | | `writeTree` takes the `RiskTree`, whose root is among its nodes by construction, so `ensureRootPresent` has nothing to test and is deleted | | |
| 48 e | `NotFound` | `Peer(Store)` | `(Field(Request, Branch), NotFoundKind.<branch has no head> NEW)` | — | 500; Service |
| 48 f | `require`, then removed | | `MergeStagingRef.forScenario` composes two refined values and refines the composition. Smell: the composition is total and should return `MergeStagingRef` directly, which removes the check | | defect if violated |
| 49 | return value | | `createBranchAt` returns `Refused`; the service builds row 18 or 23 | — | the service's `Conflict` at `Client` |
| 50 | return value | | `deleteBranch` / `moveBranchTo` return `Refused(head)`; the service builds row 19 or 24 | — | the service's `Conflict` at `Client` |
| 51 | return value | | `mergeCommit` returns the refused merge; the service builds row 21 | Irmin's reason, on the service's `Conflict` | the service's `Conflict` at `Client` |
| 52 | `Unanswered` | `Database` | `(Whole, OutageMode from the exception)`; a `23505` on another constraint is `Conflict` at `Peer(Database)` with `(Whole, ConflictKind.Rejected())` | driver message | 503 for the outage, 500 for the conflict; Service |
| 53 | `Rejected` | `Peer(Database)` | `(Field(Workspace, SeedEntityId), OutOfRange(ValueRange.SeedEntityId))` | the raw value | 500; Service |
| 54 | `Rejected` | `Peer(Database)` | `(Field(Workspace, Ttl NEW \| IdleTimeout NEW), Unreadable(ExpectedInput.Interval NEW))` | the interval text | 500; Service |
| 55 a | `Rejected` | the service's own zone | `(Field(Workspace, SeedEntityId), OutOfRange(ValueRange.SeedEntityId))` — building an instance for a new workspace against the generator's eight-digit bound | the counter value | 500; Service. The Postgres sequence has the same bound and no check today |
| 55 b | `Rejected` | `Client` | `(Field(Request, Id), TreeIdAlreadyExists(treeId))`, as row 4 | — | 422/400; Caller |
| 55 c | `NotSupported` | `Environment` | `(Header(Branch), Capability.Scenarios)` | — | 501; Service |
| 56 | moves to create and update | `Client` | the fit runs in `RiskTreeServiceLive` before the write, on every leaf the request adds or changes, as the last step of that leaf's own statement validation and accumulated with the other leaves' and the tree's errors: `(Field(NodeAtIndex(Leaves, i) \| ExistingNode(id), DistributionType), MetalogFitFailed \| LognormalFitFailed)`. The fitters are JVM-only (`build.sbt` line 89), so the check cannot live in `common` | the fitter's message | 422; Caller. At simulation the fit is a `require`, because the store holds no tree written before the check |
| 57 | `Rejected` | `Service` | the four checks as today: `(Field(ExistingNode(nodeId), Id), NodeNotInIndex)`, `(Field(ExistingNode(pid), ChildIds), ChildNotFound(cid) \| PortfolioHasNoChildren(Nil))`, `(Field(ExistingNode(lid), ContentHash), LeafUnreachable)`. The walk and its checks are kept unchanged | — | 500; Service |
| 58 a | `Rejected` | `Peer(Store)` | `(Field(ExistingNode(id), AggregatedLoss), AggregatedLossOverflow)` | — | 500 (today 422); Service |
| 58 b | `Rejected` | `Client` | `(Field(Mitigation(mid), …), MitigationEliminatesRisk \| MitigationLowersMinLossBelowFloor(…))` | — | 422; Caller |
| 59 a | `require` per query | | the runtime model against the catalog is this service's wiring, both from one `schemaFor(tree)` call, checked at `QueryServiceLive.scala:125` where the knowledge base is built | | defect if violated |
| 59 b | `Rejected` for a query naming answer variables; a defect otherwise | `Client` | `(Whole, FieldProblem.Query(AnswerVariablesNotSupported(variables)))` at the request boundary, because the engine refuses an answer variable with no supplied value and register supplies none; `ParsedQuery.mk`'s two variable rules are `Unparseable` through row 26; the dispatcher `Left`s and the binder-excluded causes are defects | — | 422; Caller, or a defect |
| 60 | defect | | `DefectCatchAllInterceptor` answers it and logs the cause; a violated `require` lands here | | 500 |

What the shape needs that does not exist today, collected from the NEW marks:
`TrustZone.Environment`, `TrustZone.MeshHeader` and the case for this service's
own values; `ErrorSubject.Tree(treeId)`;
`FieldName.Ttl` and `FieldName.IdleTimeout`; `ExpectedInput.Json` and
`ExpectedInput.Interval`; `ValueFormat.StoreSegment`;
`FieldProblem.Query(QueryProblem)`, and one case for a node whose kind differs
between merge sides; four `NotFoundKind` cases for the store (listed value absent,
metadata absent, no nodes, branch has no head); one `Capability` case for
pinned reads; and the two enums `OutageMode` and `DenialKind`.

## 10. What the rows show

Six zones feed the hierarchy and no row carries its zone as data. The zone is
reconstructed from the type (`IrminError`, `AuthError`), from text
(`reason.contains("seed_entity_id")`, row 12), or not at all
(`RepositoryFailure(String)`, rows 39–48 and 52–55, covers Irmin, Postgres, the
in-memory stores and the service's own layout checks under one case).

The same Irmin transport failure answers 503 from one service and 500 from
another (rows 33–37). Refused store content is an outage in row 37, a flattened
string in rows 39–48, a defect in row 41, and a caller conflict in row 22.
Postgres has no outage case (row 52). A 500 is logged at INFO in rows 49–51
when the translation is skipped. Rows 56–58 are computations over stored
content answered as the caller's fault. Row 1 and rows 41 and 60 produce no
`AppError` at all.
