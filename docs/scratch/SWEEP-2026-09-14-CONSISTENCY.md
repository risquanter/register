# Consistency sweep — 2026-09-14

Scope: design and code against the ADRs, the TODO list, plan documents and the
rest of the documentation, plus a code review of the scope-resolution path down
to persistence. On any contradiction the current code decides and the document
is treated as stale.

Status: IN PROGRESS. Findings are appended section by section as they are
confirmed. Nothing here is a change; every entry is an observation awaiting a
ruling.

## Section index

- S0 — rename landed, token blocker
- S1 — scope resolution down to persistence (code review)
- S2 — ADRs against the code
- S3 — TODO.md items
- S4 — plan documents, cross-references and ordering
- S5 — remaining documentation

## S0 — rename landed, token blocker

DONE: `Item17RegressionSpec.scala` renamed to
`AggregateFreshnessAfterLeafMoveSpec.scala`; object, suite label, test name,
scaladoc, workspace label and tree name all rewritten with no backlog-item
number, no phase label and no decision date. Plan and TODO references updated.

BLOCKED: the three "TODO item 17" comments in
`modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala`
(lines 51, 155, 184) carry the same defect and are NOT yet fixed. The approval
token does not name PLAN-RISKTRANSFORM.md — the test file passed only through
the hook's same-module test escape hatch, which does not apply to production
source.

## S1 — scope resolution down to persistence (code review)

Files read in full: `MitigationScopeResolver.scala`, `MitigationScopeResolverLive.scala`,
`ScopeResolverScope.scala`, `MitigationStaleness.scala`, `CacheScope.scala`,
`CachedResultResolver.scala`, `CachedResultResolverLive.scala`,
`Mitigation.scala`, `MitigationApplication.scala`, `QueryServiceLive.scala`,
the mitigation parts of `RiskTree.scala`, `RiskTreeServiceLive.create/update`,
`RiskTreeRepositoryIrmin.scala`, `WorkspaceStoragePaths.scala`.

### S1-1 (design contradiction, high) — the planned override-anchor cross-check turns a diagnostic into a hard rejection

Current code: `MitigationStaleness.isStale` ends with
`case _ => true   // anchor gone / no longer a leaf -> stale (OD-8 Option A)`.
A mitigation whose `overrideAnchor` names a deleted node is therefore a legal
tree that reports itself stale.

Planned code (PLAN-RISKTRANSFORM 7.6.6, Decision 9):
`RiskTree.validateMitigations(mitigations, nodes)` gains a cross-check that the
override anchor names a present leaf. `fromNodes` is the decode path as well as
the construct path, so once that lands:
  - deleting an anchored leaf makes the whole tree PUT fail validation rather
    than marking one mitigation stale;
  - the `case _ => true` staleness branch becomes unreachable;
  - any tree already persisted with a dangling anchor becomes undecodable.
The third point is harmless only while no write path has ever created a
mitigation, which is true today and stops being true in the same plan.

Needs a ruling: either the cross-check is dropped and staleness stays the
mechanism, or the cross-check is kept and the update path must rewrite or drop
anchors when their leaf disappears.

### S1-2 (documentation contradiction, high) — the mitigation provenance layer has no home in the ruled response shape

`MitigationApplication.applicationRecords` and `MitigationApplicationRecord`
exist, are tested, and have NO production caller. The record's own scaladoc says
it is "stored beside the simulation provenance in responses".
PLAN-RISKTRANSFORM 7.4 still says "A response carries ... the
mitigation-provenance layer (`MitigationApplicationRecord`s) beside simulation
provenance". The ruled M4 response shape in 7.6.3 / 7.6.5 carries
`LECNodeSeries(curve, withMitigations: List[MitigationId])` and nothing else.
So the plan contradicts itself, and the code comment states a fact that is not
true and is not scheduled to become true.

### S1-3 (documentation contradiction, medium) — `MitigationStaleness`'s stated consumer does not exist

Its scaladoc: "The sole consumers are HTTP handlers that put
`staleMitigationIds` into read/update response payloads". No handler calls
`staleOverrides`; the only caller is its own spec. Slice 3 (7.6.7) is what makes
the sentence true. Until then the comment describes an intention.

### S1-4 (incorrect comment, medium) — the memo key is stated wrongly in three places

The memo is `Ref[Map[(TreeId, BranchRef), (CommitHash, ResolvedScopes)]]`: the
key is (treeId, branch) and the revision is a stored guard, not part of the key.
`MitigationScopeResolverLive`'s own scaladoc says this correctly ("Head-only").
Three other places say the key is the triple:
  - `MitigationScopeResolver.scala`, `ScopeResolutionContext` scaladoc
  - `ScopeResolverScope.scala`, trait scaladoc
  - `QueryServiceLive.scala`, step 2 comment
Slice 4 (7.6.8) changes the slot to capacity 2, so all four need rewriting then
anyway.

### S1-5 (comment style, medium, many sites) — plan and decision references in shipped comments

Banned by the comment-style rule, found in production source:
  - `MitigationScopeResolverLive.scala` — "Rationale and rejected alternatives:
    PLAN-RISKTRANSFORM 8.13 (memo write policy)"
  - `Mitigation.scala` — "(PLAN-RISKTRANSFORM OD-6)"; "D-4 provenance layer";
    "(DD-19 stays identity-free)"
  - `MitigationApplication.scala` — "(OD-3)", "(OD-3 ruling)", "OD-5",
    "the D-4 provenance layer", "(PLAN-RISKTRANSFORM OD-6)"
  - `MitigationStaleness.scala` — "(DD-16 projection)", "(OD-8 Option A)"
  - `CachedResultResolverLive.scala` — "(D3)", "ADR-034 Decision 1/4/F" (the
    ADR pointer is allowed, the decision-number shorthand is not)
  - `QueryServiceLive.scala` — "(OD-5=D)", "(OD-4=A)"
  - `RiskTreeRepositoryIrmin.scala` — "(DD-7)"
  - `InvalidationHandler.scala` — "TODO item 17" at lines 51, 155, 184
    (the S0 blocker)
This is one sweep, not several; it should be scheduled as a single pass.

### S1-6 (stale comment, medium) — `MitigationSelection` says it crosses the wire as a request parameter

`MitigationApplication.scala`: "Crosses the wire in M4 as a request parameter."
Decision 3 ruled a JSON request body, and the ruled body type is the two-list
`MitigationSelectionRequest`, not this domain type. The plan's own copy of this
comment block was corrected this session; the source file was not.

### S1-7 (dead parameter + false doc, medium) — `includeProvenance` does nothing

`CachedResultResolver.ensureCached`/`ensureCachedAll` take
`includeProvenance: Boolean = false`. `CachedResultResolverLive` writes it to a
tracing attribute and never reads it again; `simulateLeaf` comments say
"Always capture provenance (filter at service layer)". The scaladoc claims it
controls whether provenance is captured. Either the parameter goes or the
documentation does.

### S1-8 (correct-by-construction gap, medium) — `selection` and `resolvedScopes` are two parameters that must agree

Every resolver entry point takes them separately with independent defaults.
Passing `Selected(...)` with an empty `resolvedScopes` map is well-typed and
silently applies nothing — the exact failure the project's validate-once rule
exists to prevent. The two are only ever produced together (one from the
request, one from the resolver), so they could travel as one value.

### S1-9 (scalability, medium) — the query path fans out one full-tree simulation per mitigation

`MitigationSelectionScan.referenced` maps a bound `exists m : mitigation`
variable to `everyMitigation` — one `Selected` per `tree.mitigations` element,
bounded only by `MaxMitigations` = 1000. `QueryServiceLive` step 4 then runs
`ensureCachedAll(tree, allNodeIds, ...)` once per selection, sequentially
(`ZIO.foreach`). Worst case is 1000 whole-tree resolutions in one request. The
content cache absorbs repeated leaf content but not the per-selection
`effectiveTree` build and `ContentHashIndex.build`, both O(nodes). Check whether
TODO 48 (concurrency bounds) covers this path or only the resolver fan-out.

### S1-10 (low) — `ensureCached` rebuilds the whole content-hash index per call

`ensureCached` calls `ContentHashIndex.build(effective)` for a single node;
`ensureCachedAll` correctly hoists it. Any caller looping `ensureCached` over
nodes pays O(nodes) per node. The M4 read path should use `ensureCachedAll`.

### S1-11 (low) — silent drop in scope extraction

`MitigationScopeResolverLive.satisfyingIds` ends with
`values.flatMap(_.extract[NodeId].toOption)`: a value that fails extraction is
dropped and the outcome is still `Resolved`, so a partially-resolved scope is
indistinguishable from a fully-resolved one. The comment argues extraction
cannot fail for a create-validated predicate; if that holds, the failure arm
should say so loudly rather than silently shrink the scope.

### S1-12 (low) — per-workspace resolvers are never released

`ScopeResolverScopeLive.resolvers` grows one entry per workspace touched and
never shrinks, the same shape as `CacheScope`. `CacheScope` documents the
lifetime ("lingers until restart"); `ScopeResolverScope` does not. Check whether
PLAN-WORKSPACE-CACHE-RELEASE covers the scope resolver or only the content cache.

### S1-13 (stale plan inventory, low)

`modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTransform.scala`
is a bullet in PLAN-RISKTRANSFORM's `## File inventory` and does not exist in
the repository.

### Checked and found sound

- Irmin write path: `writeTree` sends meta + nodes + mitigations as one
  `setTree`, so the whole-subtree replace is atomic and omission-means-delete is
  uniform across nodes and mitigations.
- Irmin read path: `loadTreeAt` tolerates an absent mitigations prefix, and
  `rebuildTree` routes through `RiskTree.fromNodes`, so every tree invariant is
  re-checked on load.
- `MitigationApplication.scoped` intersects the selection's `NodesOnly` set with
  the resolved scope, so a selection can only restrict (design anchor A9 holds).
- `effectiveTree` carries `tree.mitigations` through `fromNodes`, so the
  param-stage rewrite cannot drop the mitigation collection.
- `CachedResultResolverLive` applies the result-stage transform after the cache
  read and never stores it, and folds already-mitigated children at a portfolio
  (design anchors A5 and A6 hold).
- `ScopeResolverScopeLive.resolverFor` and `CacheScopeLive.cacheFor` both use
  `Ref.modify` to pick a single winner under a first-access race.

## S2 — ADRs against the code

Method: read ADR-034 and ADR-017 in full against the mitigation and HTTP code;
then mechanically extract every backticked type name from every ADR and test it
against `modules/`. Dotted member names produce false positives (source calls
them unqualified), so only type- and file-level misses are reported below, each
verified by hand.

### S2-1 (contradiction, high) — ADR-034 promises drill-down through a mitigated portfolio; the code collapses it

ADR-034 Decision 4: "Drilling **decomposes** `mitigated(node)` into that layer
and the children's mitigated aggregate, so where each reduction happened stays
visible."

`CachedResultResolverLive`, portfolio arm: when a result-stage transform binds
at a portfolio, the `RiskResultGroup` (which carries the children) is replaced
by a flat `RiskResult` holding the transformed outcomes and the descendants'
provenances. The children are gone from that value, so nothing can be decomposed
out of it.

The code's own comment claims this follows from ADR-034 Decision 4, because
`RiskResultGroup`'s constructor pins the aggregate to `combine(children)` and so
cannot hold transformed outcomes. That argument is sound about non-mutation; it
does not deliver the visibility sentence.

The ruling that authorized the edge fold is PLAN-RISKTRANSFORM 8.14
("Portfolio-level result-stage transform — RULED: F, 2026-08-28"). It rules the
fold and explicitly leaves "the exact `CachedResultResolverLive` return shape
that carries the mitigated aggregate alongside the cached raw value" to the code
step's signature echo. So the collapse was decided at implementation time, not by
a ruling on this point.

Counter-argument worth weighing: drill-down may be preserved at the API level
rather than inside one response value, since the multi-node endpoint can be
asked for the children's own mitigated curves. If that is the intended reading,
ADR-034's sentence should say so.

Needs a ruling on direction. Untouched pending it.

### S2-2 (stale ADR, medium) — ADR-017's HTTP surface table is wrong in both directions

It lists `POST /w/{key}/risk-trees/{treeId}/invalidate/{nodeId}` — no such
endpoint exists anywhere in `modules/`.

It omits every workspace-scoped endpoint that is not tree CRUD:
`/structure` is listed, but `changed-nodes`, `history`, `revert`,
`nodes/{nodeId}/prob-of-exceedance`, `nodes/lec-multi`, `query`, the five
`scenarios` routes, `rotate`, and workspace delete are all absent.

The M4 elevation already schedules an ADR-017 amendment (7.6.9). That amendment
should fix the table as a whole, not only add the mitigation buckets.

### S2-3 (stale ADR, medium) — ADR-009 names a provenance field that was removed

ADR-009 refers to `NodeProvenance.riskId`. `Provenance.scala` has no such field;
provenance was made identity-free, and the only surviving mention of `riskId` in
the repository is in `ProvenanceSpec.scala`.

### S2-4 (stale ADR, low) — types and files named by ADRs that do not exist

- ADR-002 — `SimulationError` and `SimulationError.scala`: absent.
- ADR-018 — `TreeIdCodecs.scala`: absent.
- ADR-021 — `WorkspaceController`, `WorkspaceEndpoints`: absent; the live names
  are `WorkspaceLifecycleController` and `WorkspaceLifecycleEndpoints`.
- ADR-025 — `Router.scala`: absent under `modules/app`.
Each is a one-line correction, not a design question.

### S2-5 (stale pointer, low) — the ADR directory moved and two documents still point at the old one

ADRs live in `docs/dev/decision-records/`. Both of these say `docs/dev/`:
- `CLAUDE.md` line 88: "the ADRs themselves live in `docs/dev/` alongside
  `ARCHITECTURE.md`"
- `docs/dev/ARCHITECTURE.md` line 884: "every `docs/dev/ADR-*.md` file present
  is in force"
Also `.github/skills/register-dev/SKILL.md` line 221 and
`.github/skills/supply-chain/SKILL.md` line 8 (plus their `.claude/skills/`
mirrors) cite `docs/dev/ADR-020-supply-chain-security.md`.
The `docs/dev/ARCHITECTURE.md` line matters most: it is the rule that says an
ADR file's presence makes it binding, and it names a glob that now matches
nothing.

### Checked and found sound

- ADR-036 (confidential internal identifiers): the analysis endpoints take
  `WorkspaceKeySecret` in the path and a branch NAME in the `X-Branch` header,
  and compose the `BranchRef` server-side. The M4 elevation keeps `BranchRef`
  on internal service signatures only, so the `WorkspaceId` it embeds still
  never reaches the wire. `TreeId`, `NodeId` and `MitigationId` are not
  confidential under this ADR, so the M4 request and response bodies are
  compliant.
- ADR-017 Decision 1 (separate create and update DTOs): the elevation's
  `RiskTreeDefinitionRequest` gains `newMitigations` only, while
  `RiskTreeUpdateRequest` gains both `mitigations` and `newMitigations` — the
  four-bucket rule applied to a second collection.
- The elevation preserves the two existing query parameters on `lec-multi`,
  `omitAbsent` and `includeProvenance`, so replacing the request body with
  `LECCurvesMultiRequest` is not a silent behaviour regression.
- ADR-034's Implementation table rows all name live code.

### Noted, not a finding

`RiskTreeEndpoints.scala` defines a non-workspace-scoped `getAllEndpoint` at
`/risk-trees`. Nothing on the server routes it; the only consumer of that trait
is the app's `HealthState`, which uses `healthEndpoint`. It is a dead definition,
not a live unscoped route — but it is the kind of definition that becomes a real
exposure the moment someone adds it to a route list.

## S3 / S4 — TODO items, plan documents, ordering and dependencies

These two sections are reported together because every finding in them is about
the same relationship: which plan owns which file, and in what order.

### S3-1 (blocker, high) — four of the five ordered plans cannot authorize a single edit

The enforcement hook authorizes a gated edit only from a **bullet line** under a
plan's `## File inventory` heading, up to the next `## ` heading. Tested
mechanically against every plan document, counting bullets that name a
`modules/` path or `build.sbt`:

| Plan | `## File inventory` present | Authorizing bullets |
|---|---|---|
| PLAN-NGINX-WORKSPACE-ROUTING (order 1) | yes | 0 — touches no Scala, so this is correct |
| PLAN-CACHE-REGISTRY-RENAME (order 2) | yes | **0** |
| PLAN-WORKSPACE-CACHE-RELEASE (order 3) | yes | **0** |
| PLAN-TELEMETRY-EXPORT (order 4) | yes | **0** |
| PLAN-SIMULATION-CONCURRENCY-BOUNDS (order 5) | yes | 15 |
| PLAN-IRMIN-RECURSIVE-READ (independent) | yes | **0** |
| PLAN-SIGSTORE-VERIFICATION | yes | **0** |
| PLAN-RISKTRANSFORM | yes | 139 |

The five plans showing 0 write their inventories as tables or as bullets nested
under `### ` sub-headings that the hook's section scan still covers but whose
lines start with `|`, not `- `. Approving any of them would produce a denial on
the first edit. This is a format fix, not a design change, but it blocks the
entire approved landing order at step 2.

### S3-2 (ordering conflict, high) — M4 and the registry rename touch the same fifteen files, and the single ordering list does not mention M4

`docs/dev/TODO.md` opens with "Plan landing order — approved plans, in the order
they must be implemented" and calls itself "the single list". It names six
plans. `PLAN-RISKTRANSFORM` — the active workstream, and the largest — is not in
it.

Overlap between the M4 file inventory amendment (43 paths) and each ordered
plan, computed mechanically:

- **PLAN-CACHE-REGISTRY-RENAME — 15 shared files**, including all three
  scope-resolver sources (`MitigationScopeResolver.scala`,
  `MitigationScopeResolverLive.scala`, `ScopeResolverScope.scala`),
  `Application.scala`, `QueryServiceLive.scala`, and six test files.
- PLAN-WORKSPACE-CACHE-RELEASE — 5 shared files.
- PLAN-SIMULATION-CONCURRENCY-BOUNDS — 3 shared files.
- PLAN-TELEMETRY-EXPORT — 1 shared file (`Application.scala`).
- PLAN-IRMIN-RECURSIVE-READ — none.

The rename is not cosmetic for M4: it renames `ScopeResolverScope` to
`MitigationScopeResolverRegistry` and `resolverFor` to `forWorkspace`. M4 slice 1
adds a `scopeResolver: ScopeResolverScope` field to `RiskTreeServiceLive` and
slice 4 rewrites `MitigationScopeResolverLive`'s memo. So one of these holds:
  - M4 lands first, and the rename plan's ripple list grows by everything M4 adds;
  - the rename lands first, and every signature in 7.6.5 and 7.6.8 is written in
    names that will not exist by the time they are typed.
Neither is recorded anywhere. This needs a ruling before M4 slice 1 starts.

Related and smaller: PLAN-CACHE-REGISTRY-RENAME line 312 budgets "29
occurrences" in PLAN-RISKTRANSFORM.md. The elevation written this session added
more, so that count is stale whichever order is chosen.

### S3-3 (ownership gap, medium) — TODO 40 is implemented by M4 without being told

TODO 40, "Bound the multi-LEC endpoint's node-id list", is marked open and
proposes sharing one bounding mechanism with TODO 41 (screening-query input
bounds) rather than re-implementing per endpoint. M4 slice 1 adds
`MaxRequestedNodes = 10000` inside `LECCurvesMultiRequest`, which closes TODO 40
with a per-endpoint constant — the outcome TODO 40 asked to avoid.
Either M4 closes TODO 40 and TODO 40's shared-mechanism intent is withdrawn, or
the bound is built shared. Needs a ruling; it is cheap either way today and
expensive once both endpoints have their own constant.

### S3-4 (ownership gap, low) — TODO 49's ruling has an implementer it does not name

TODO 49 records "Ruled 2026-09-13: the slot holds two revisions, evicting the
least recently used" and describes the capacity-2 behaviour in the present
tense, including in its title. The code today is head-only: one revision per
(tree, branch) slot. The capacity-2 change lives in PLAN-RISKTRANSFORM 7.6.8
(M4 slice 4), which TODO 49 does not mention. A reader of TODO 49 alone would
believe the memo already holds two revisions.

### S3-5 (low) — TODO 17's deliverable was renamed

Line 926 named `Item17RegressionSpec`; updated to
`AggregateFreshnessAfterLeafMoveSpec` in this session's S0 work. Recorded here so
the change is visible, not as an open finding.

### Checked and found sound

- TODO 51 and PLAN-WORKSPACE-CACHE-RELEASE describe the code in post-rename
  vocabulary (`ContentCacheRegistry`, `MitigationScopeResolverRegistry`), which
  do not exist yet. Both documents state explicitly that they land after the
  rename, so this is deliberate, not drift. It does answer S1-12: the scope
  resolver registry IS covered by the cache-release plan, alongside the content
  cache.
- TODO 48's plan (`PLAN-SIMULATION-CONCURRENCY-BOUNDS`) is scoped to
  `CachedResultResolverLive`'s `ZIO.foreachPar` over portfolio children. It does
  NOT cover S1-9, the query path's one-full-tree-resolution-per-mitigation
  fan-out in `QueryServiceLive`. That is a separate, unrecorded path.
- TODO 52 (evaluate the bounded-by-construction preference, then codify eviction
  in an ADR) correctly names both TODO 49 and TODO 51 as cases the survey must
  weigh, and both of those point back at it.
- `PLAN-IRMIN-RECURSIVE-READ` shares no file with M4, so its "may land at any
  point" claim survives the M4 amendment.

## S5 — remaining documentation

### S5-1 (shipped behaviour contradicted by user documentation, high) — every documented query example fails

`RiskTreeKnowledgeBase.schemaFor` declares:

```
p95 : (Node, Mitigation) -> Loss
p99 : (Node, Mitigation) -> Loss
lec : (Node, Loss, Mitigation) -> Probability
```

The mitigation argument is part of the fixed signature, so a call without it is
rejected with an arity mismatch before evaluation. The frontend was updated —
`AnalyzeQueryState.defaultQuery` and `AnalyzeView`'s placeholder both read
`p95(x, "inherent")`. The documentation was not:

| Document | Examples with the old arity | Examples with the mitigation slot |
|---|---|---|
| `docs/user/VQL-QUERY-EXAMPLES.md` | 12 | 0 |
| `docs/user/API-TUTORIAL.md` | 5 | 0 |
| `docs/dev/decision-records/ADR-029-input-injection-defence.md` | 2 | 0 |
| `docs/dev/decision-records/ADR-028-vague-quantifier-query-pane.md` | 3 | some |
| `docs/dev/decision-records/ADR-028-appendix-technical-design.md` | 2 | most |

`VQL-QUERY-EXAMPLES.md` does not mention `inherent`, `residual` or mitigations
anywhere. So the two user-facing query documents contain seventeen examples that
cannot be run as written, and neither explains the argument that has to be
added. This is shipped M3 behaviour with no user documentation, not an M4 gap.

### S5-2 (stale architecture document, medium) — the mitigation subsystem is absent, and its only mention describes the superseded modelling

`docs/dev/ARCHITECTURE.md` line 626, in the scenario-analysis workflow:
"Scenario 2: Add mitigation (reduces maxLoss)". That is mitigation-as-a-leaf-edit,
which the mitigation entity explicitly replaced — a mitigation is tree-level
content, never baked into node parameters.

Beyond that line the document does not mention mitigations at all: no
`MitigationScopeResolver`, no `ScopeResolverScope`, no mitigation entity in the
domain model, and the layer listing at lines 107-108 shows
`CachedResultResolverLive.layer` and `CacheScope.layer` but not
`ScopeResolverScope.layer`, which `Application.scala` line 290 wires.

Also stale in the same document: line 365 shows
`CachedResultResolver.ensureCached(tree, nodeId)`; the real method takes
`seedEntityId`, `includeProvenance`, `selection` and `resolvedScopes` as well.

### S5-3 (documentation gap, medium) — no user-facing definition of inherent and residual

`docs/user/TERMINOLOGY.md` defines risk, loss, loss distribution, LEC curve and
the monoid structure. It does not define inherent risk or residual risk, though
both are now domain type names (`MitigationSelection.Inherent` / `.Residual`),
wire strings, and literals a user types into a query. A user who reads the fixed
version of S5-1's examples still has nothing telling them what `"inherent"`
means.

M4 slice 6 owns user documentation, so this belongs there — but S5-1 does not:
those examples are wrong today, for behaviour that shipped in M3.

### Checked and found sound

- No user document claims mitigations exist as an authoring feature, so nothing
  promises an interface M4 has not built.
- `docs/test/TESTING.md` no longer references the retired `/api/risk-trees`
  surface (TODO 20 closed that), and contains no endpoint paths that have since
  moved.

---

## Status: COMPLETE (2026-09-14)

All five sections done. 26 findings recorded. Nothing in this file has been
acted on except S0's rename, which was approved before the sweep began.

Ranked by what blocks work soonest:

1. S3-1 — four ordered plans cannot authorize an edit (format, mechanical)
2. S3-2 — M4 and the registry rename collide on 15 files, order unruled
3. S5-1 — 17 documented query examples fail against shipped behaviour
4. S1-1 — the planned override-anchor cross-check versus staleness semantics
5. S2-1 — ADR-034's drill-down promise versus the portfolio collapse
6. S1-2 — the mitigation provenance layer has no home in the ruled response
7. Everything else is documentation correction, mostly one line each.

---

# Follow-up research, 2026-09-14 (after rulings 1A / 2A / 3B)

## R1 — client readiness for a failing tree PUT (decision 4)

Traced `TreeBuilderView.handleSubmit` -> `ZJS.submitInto` -> `SubmitState`.

- **Draft survives a failure.** On failure only `submitState` is written;
  `portfoliosVar`, `leavesVar` and the tree name are untouched. The user keeps
  every edit and can resubmit. Consistent state: yes.
- **The error is one flat string.** `submitInto` sets
  `SubmitState.Failed(e.safeMessage)`; `ValidationFailed.getMessage` joins
  accumulated errors as `[field] message; [field] message`. `TreeBuilderView`
  renders it in a single `div.submit-error` under the form.
- **No structured routing exists.** `ValidationError` is
  `(field: String, code: ValidationErrorCode, message: String)` — it carries no
  node id and no mitigation id as data. `FormState`'s per-field errors come from
  client-side validation only; nothing attaches a server error to a field.
- **Nothing to attach it to.** There is no mitigation interface until slice 6.

So the affordance described in the ruling — "node X's mitigation Y: keep X or
update Y", retained until corrected — is not available today and is in no slice.

**How C differs from A, precisely.** Under A the PUT succeeds and staleness is a
persisted, addressable property of the saved tree, delivered on every structure
read as `staleMitigationIds` (slice 3) and renderable beside the mitigation row;
it survives reload and is actionable at leisure. Under C the PUT fails and the
information exists only as a transient string bound to one submission attempt:
nothing is persisted, a reload loses it, and the node deletion the user wanted
is also not saved.

**Correction to S1-1.** Its third consequence — a persisted tree with a dangling
anchor becoming undecodable — is answered by the plan and should be dropped.
7.6.6 states the check lands in the same slice as the buckets precisely because
no write path has ever created a mitigation, and once the check is in
`fromNodes` no later write can construct one either. The invariant is airtight
going forward. What remains of S1-1 is only the failure-mode question above.

**Noted, not a finding.** 7.6.6 makes omission of `mitigations` delete the
collection, and states that a PUT from the current browser would therefore clear
it until the interface slice lands. That window is internal to one
shipped-whole plan (G8), so it never reaches a user — provided slices 2 and 6
land together.

## R2 — the portfolio composition history (decision 5)

The recollection is correct. Four documents bear on it.

1. **ADR-009, "Code Smells", "Aggregate typed as a leaf":**
   > BAD: portfolio result indistinguishable from a simulated leaf; children lost
   > `childResults.reduce(combine).withNodeId(portfolio.id)`
   > GOOD: distinct aggregate type, children retained for drill-down
   > `RiskResultGroup(portfolio.id, childResults*)`

2. **PLAN-MONOID C.2, "Aggregate type vs `RiskResult` reuse — Leaf-as-aggregate
   smell":** names the same defect, found by audit 2026-06-18, and prescribes the
   same fix. Flagged as a behaviour change, trigger #5.

3. **IMPLEMENTATION-PLAN line 2555:** records the fix as **Done 2026-07-17** —
   "the resolver now builds portfolio entries as `RiskResultGroup(parentId,
   childResults*)`, which preserves child references for component-contribution
   drill-down."

4. **ADR-034, "Code Smells", "Mutating the aggregate to carry a mitigation":**
   bans `RiskResultGroup.withAggregate(nodeId, children, cappedOutcomes)`
   because it lets the aggregate differ from `combine(children)`. This is the
   withdrawn Option A of 8.14.

Together the two bans box the implementation in: children may not be lost
(ADR-009), and a transformed total may not be stuffed into a group whose
aggregate must equal `combine(children)` (ADR-034). The current code resolves
that squeeze by choosing to violate the first.

**The collapse is deliberate and test-pinned.** `CachedResultResolverSpec` has
`test("ResultStage cap on a portfolio: raw aggregate = sum(children); mitigated
= cap(sum), collapsed to a flat result")`, asserting
`mitRoot.isInstanceOf[RiskResult]` with the comment "A binding portfolio
transform cannot be a group -> flat RiskResult". So it was chosen at the
signature-echo step 8.14 delegated, not introduced by accident.

**Ground truth on the type.** `RiskResultGroup` is
`final case class RiskResultGroup private (children: List[LossDistribution],
nodeId, trialOutcomes)` with a private companion `apply`; the only public
builder is `create(nodeId, results*)`, which forces
`trialOutcomes = merge(results)`. So the invariant is enforced by construction
and a mitigated total genuinely cannot be a `RiskResultGroup` as written.

**A third shape is banned by neither document and has never been designed:** a
distinct composite that carries the children AND a transformed total, and is not
`RiskResultGroup`. ADR-034 bans only putting a transformed total into the type
whose contract is combine-of-children.

**What currently consumes children:** nothing in production.
`LossDistribution.flatten` (documented as "[aggregate, child1, child2, ...] for
chart rendering") has no production caller — only `LossDistributionSpec`. The
2026-07-14 archive note predicted exactly this: children were retained for "a UI
feature (drill-down) that is not yet implemented". So the collapse is invisible
to every consumer that exists today, and becomes visible the moment drill-down
is built.

## R3 — S2-1 RESOLVED by recovered history (2026-09-14)

The category-theory discussion behind the Option F ruling was recovered from
session history and written to
`docs/scratch/MITIGATION-PORTFOLIO-CATEGORY-THEORY.md`. It settles S2-1.

The derivation states explicitly: "the mitigated value of a capped portfolio is
**not** a `RiskResultGroup` claiming to be a sum. It is a plain
transformed-outcomes value ... The type system stays exactly as strict as it is
now." So the flat `RiskResult` in `CachedResultResolverLive`'s portfolio arm is
the derived and ruled outcome, not an implementation shortcut.

Consequences for the sweep's earlier entries:

- **S2-1 is not a code defect.** The code matches the ruling. What is wrong is
  ADR-034's Decision 4 wording, which reads as a promise about one response
  value when the derivation means the children are read separately.
- **ADR-009's "aggregate typed as a leaf" smell does not reach the mitigated
  value.** That smell governs the RAW fold, where `RiskResultGroup` is still
  built and still enforces aggregate = combine(children). The mitigated
  valuation is a different fold whose values are legitimately flat.
- **The recommendation of a third composite type is withdrawn.** The derivation
  says in terms that no domain-type change is needed, and adding one would
  reintroduce a composite whose aggregate is not the combine of its children —
  the shape the derivation was written to avoid.

Two gaps the recovery exposes, both outside the original sweep:

1. ADR-034 carries the two valuations, the non-homomorphism and the worked
   example, but not the conclusion about the mitigated value's TYPE. Adding one
   paragraph would have prevented S2-1 entirely.
2. The standing directive "from now on every assessment needs strict review
   from category theory perspective" was proposed for `~/.claude/CLAUDE.md` at
   the time and is still not there. It is a cross-project guardrail; only the
   user can place it.

---

# Action log — 2026-09-14, after the rulings

What each finding's ruling turned into. A finding not listed here was not acted
on and is still open.

## Acted on

- **1A / S3-1.** The four table-shaped inventories in PLAN-CACHE-REGISTRY-RENAME,
  PLAN-WORKSPACE-CACHE-RELEASE, PLAN-TELEMETRY-EXPORT and PLAN-IRMIN-RECURSIVE-READ
  are bullets under `## File inventory`, each keeping its description after an
  em dash. Re-counting with the hook's own rule: 21, 15, 5 and 7 authorizing
  bullets respectively, against 0 before. PLAN-NGINX-WORKSPACE-ROUTING stays at 0
  correctly — it touches no Scala. PLAN-SIGSTORE-VERIFICATION stays at 0 because
  it is not elevated and its inventory section says so.
- **2A / S3-2.** `docs/dev/TODO.md`'s landing order gains PLAN-RISKTRANSFORM as
  constraint 6, ordered after the registry rename with the fifteen shared files
  as the reason. PLAN-RISKTRANSFORM §7.6 gains the matching sequencing paragraph
  and states that §7.6.5's and §7.6.8's signatures are written in pre-rename
  vocabulary on purpose. PLAN-CACHE-REGISTRY-RENAME's stale "29 occurrences"
  budget is replaced — the counts were wrong for three of the five rows and drift
  every time those documents are edited, so the plan now says to grep for the
  four symbols instead of trusting a number.
- **3B / S5-1 / S5-3.** Every query example carries the mitigation argument.
  Scope was larger than the finding recorded: the 17 documented examples, plus
  2 in ADR-029, plus **60 calls across five scripts in `examples/`** — the same
  scripts the documentation tells a new user to run first. `"inherent"` is the
  faithful replacement everywhere, because no wire field for authoring a
  mitigation exists, so every tree these documents build has none and
  `"residual"` would return identical figures. VQL-QUERY-EXAMPLES.md gains a
  section explaining the argument; TERMINOLOGY.md gains Mitigation, Inherent Risk
  and Residual Risk entries and three summary rows. Verified mechanically: 86
  calls parsed, every one matching the declared signature, the single exception
  being the deliberate counter-example in the new section.
- **4A / S1-1.** PLAN-RISKTRANSFORM Decision 9 is re-ruled: a PUT that deletes an
  anchored leaf is accepted and the mitigation reports itself stale. The
  cross-check is gone from §7.6.6, from the slice table and from the verification
  plan, which now pins the opposite behaviour plus the two staleness cases.
- **Decision 5 / S2-1.** Both approved ADR-034 edits applied — Decision 4's
  drill-down sentence reworded, and a new Decision 6 stating that the mitigated
  value of a transformed node is flat by construction. **See the open item
  below: Decision 6's last paragraph was approved before Form 3 was ruled.**
- **S1-2.** Closed by the `ValuationResult` ruling. §7.4, the M4 milestone row,
  the worked example and the `MitigationApplicationRecord` scaladoc in §7.1 no
  longer promise the records on the wire; §8.16 gives them their home.
- **S2-2.** PLAN-RISKTRANSFORM §7.6.9 now instructs the ADR-017 amendment to fix
  the HTTP surface table as a whole, naming the phantom `invalidate` endpoint and
  all eleven omissions.
- **S2-3.** ADR-009 no longer names `NodeProvenance.riskId` in either place.
- **S2-4.** ADR-002's `SimulationError` corrected to the real `AppError`/`SimError`
  hierarchy and `AppError.scala`; ADR-018's phantom `TreeIdCodecs.scala` row
  removed and the JSON codecs attributed to `OpaqueTypes.scala`; ADR-021's
  `WorkspaceEndpoints`/`WorkspaceController` corrected to the four endpoint files
  and three controller files that exist, and `config/` to `configs/`.
  **ADR-025 needed no change** — its `Router.scala` is an Open Question about a
  future extraction and a reference marked superseded, not a claim the file
  exists. That entry in S2-4 was wrong.
- **S2-5.** Every live reference to `docs/dev/ADR-*` repointed at
  `docs/dev/decision-records/`, across CLAUDE.md, ARCHITECTURE.md, both skill
  files and their byte-identical mirrors, copilot-instructions.md,
  VERSION-UPGRADE-PROTOCOL.md, TODO.md, README.md, NOTES.md and two plans. Every
  repointed link resolves except `ADR-037`, which PLAN-RISKTRANSFORM creates.
  Archive documents were left alone.
- **S3-4.** TODO 49's title and ruling paragraph now say the memo holds one
  revision today and name §7.6.8 as where capacity 2 lands.
- **S5-2.** ARCHITECTURE.md's domain entity block was stale well beyond the
  finding: `RiskTree` was missing two fields, `RiskResult` had the wrong shape,
  and `LossDistribution` was described as a Metalog coefficient case class rather
  than the sealed supertype. All corrected against source, plus the mitigation
  entity, the two registries in the layer diagram and the service listing, the
  resolver signature, the endpoint path, and the superseded
  "mitigation reduces maxLoss" scenario. Two further stale items fixed while in
  the file: a hard-coded test count, and a test pyramid marking integration and
  end-to-end tiers "Future" when both ship.

## Still blocked on the approval token

The comment sweep is one pass and none of it is done. The token does not name
PLAN-RISKTRANSFORM.md — confirmed this session by attempting the edit: the file
**is** a bullet in that plan's `## File inventory`, so the denial can only mean
the token names a different plan. Blocked: S1-3, S1-4, S1-5, S1-6 and the three
`InvalidationHandler.scala` sites from S0. One site not in the original sweep
belongs to the same pass: `Application.scala` line 290 carries the milestone
label "M3 analytics VQL".

## Newly found, not previously recorded

- **The `examples/` scripts have no automated coverage.** No BATS suite and no
  test tier executes them, which is how 60 broken query calls survived the M3
  arity change. The scripts are the documented first step for a new user.
- **PLAN-CACHE-REGISTRY-RENAME's occurrence counts were wrong** for three of
  five rows even before this session's edits.

## Still open, no ruling

- **S3-3** — TODO 40's shared bounding mechanism versus M4's per-endpoint
  constant.
- **S1-7 to S1-12** — the remaining code-review findings.

---

# Second pass — 2026-09-14, after the token was repointed

## The comment sweep is done

The token now names `PLAN-RISKTRANSFORM.md`, and the whole production-comment
pass landed in one go: plan references, decision codes (`OD-n`, `DD-n`, `D-4`,
`D3`), milestone and phase labels, and the three `InvalidationHandler.scala`
sites. Eleven files, all of them bullets in that plan's inventory.

Two findings were corrected as part of it rather than left as comment hygiene:

- **S1-3** — `MitigationStaleness`'s scaladoc claimed HTTP handlers were its sole
  consumers. Nothing reads it yet; the comment now says so.
- **S1-4** — the memo key was stated as the (treeId, branch, revision) triple in
  three places. It is keyed by (treeId, branch), with the revision held in the
  entry as a validity guard. All three corrected.
- **S1-7** — `includeProvenance`'s scaladoc claimed it controls whether
  provenance is captured. It is written to a tracing attribute and never read.
  The documentation now states that. **The dead parameter itself is untouched:
  removing it changes a trait signature and needs a ruling.**

Four more files carry the same defect and are **not** in the plan's inventory, so
the hook denies them: `ContentCache.scala`, `ContentHashIndex.scala`,
`LeafSimResult.scala` and `EvictionStrategy.scala`, all under
`modules/server/.../services/cache/`. They need an inventory amendment.

## The example scripts were broken in a second way, and now have a test

Running them against a live server found a break the arity fix did not cover:
**`X-Branch` is a required header on every workspace-scoped endpoint, and none of
the four demo scripts sent it.** Every call after the bootstrap returned 400. The
`stage-*` scripts do send it, so only the demo scripts had drifted.

Worse, and the reason both breaks went unnoticed: **a rejected query printed as
`✘ NOT SATISFIED proportion=null`.** A tutorial reader saw a plausible negative
answer where the server had refused the query. The scripts now stop with the
server's message when a query carries no verdict.

`docs/user/API-TUTORIAL.md` had the same missing header on both of its
tree-summary commands; its documented `queryEcho` JSON was invalid (unescaped
inner quotes); and its description of that response claimed per-node P95/P99
statistics and curve points, where `SimulationResponse` carries a tree id, a
name, a quantile map and an optional curve string. All three corrected.

**Coverage now exists.** `tests/bats/suite-c-in-memory.bats` gains C17-C20, one
per demo script, asserting a clean exit and one evaluated proportion per query.
The httpie pair skips when `http` is absent, which it is in the BATS runner
image — adding it would be a dependency change under ADR-020.

The guard was checked against both regression classes before being kept: with the
mitigation argument removed it fails, and with the branch header removed it
fails. Its first version passed in both cases and was rewritten — counting
verdict lines was vacuous, because a rejected query printed one.
