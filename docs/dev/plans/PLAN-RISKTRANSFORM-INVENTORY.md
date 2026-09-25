# File inventory — PLAN-RISKTRANSFORM.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


M1/M2 files (M3/M4 files are appended here when §7.5/§7.6 are approved):

- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskResultTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationApplication.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskResultTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/ResultTransformSpecSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationEntitySpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationApplicationSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeSeedVarIdSpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/WorkspaceStoragePaths.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`

- `modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCache.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ContentHashIndex.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/LeafSimResult.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/EvictionStrategy.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CacheTransparencySpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala`
- `build.sbt`

M1R adds (engine adoption + predicate targeting, §8.6):

- `modules/common/src/main/scala/com/risquanter/register/domain/data/TargetingPredicate.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/TargetingPredicateSpec.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
- `modules/common/src/main/scala/com/risquanter/register/common/FolSymbols.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/QueryRequest.scala`
- `modules/app/src/main/scala/app/state/AnalyzeQueryState.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/QueryResponseBuilder.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/QueryResponseBuilderSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`

§8.14 (`CachedResultResolver` rename + resolver-edge mitigation wiring) edits
these existing files (rename ripple + edge wiring); the three resolver files
themselves are the renamed bullets above (`CachedResultResolver.scala`,
`CachedResultResolverLive.scala`, `CachedResultResolverSpec.scala`), and
`QueryServiceLive.scala` / `CacheTransparencySpec.scala` are already listed:

- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/AggregateFreshnessAfterLeafMoveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`

§8.11 adds (bind-error → UNKNOWN_REFERENCE classification + vql 0.16.0 re-pin):

- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala`
- `project/plugins.sbt` (Scala.js toolchain bump — see §8.11 D1 below; not hook-gated)

§8.12 (retire `=`; add `eq`/`named`/`has_id`) adds **no new files** — it edits
`RiskTreeKnowledgeBase.scala`, `FolSymbols.scala`, `RiskTreeKnowledgeBaseSpec.scala`,
and `build.sbt`, all already listed above.

§8.13 (slice 2: `MitigationScopeResolver` + `ScopeOutcome`) adds **four new
server-only files** (the resolver contract, its live impl, the per-workspace
registry mirroring `CacheScope`, and the spec — none pre-existed; the M1/M2
inventory above holds only the *renamed* resolver `RiskResultResolver.scala` and
`MitigationStaleness.scala`, not these):

- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ScopeResolverScope.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CacheScope.scala` (boyscout: its doc comments carried plan-provenance references cleaned in the same pass as the new files)

§8.15 (slice 4: override staleness + mitigation persistence, Option A 2026-08-29)
adds **two files** — the staleness production file + spec and
`MitigationPersistenceItSpec` were already listed above;
`WorkspaceStoragePaths.scala` and `RiskTreeRepositoryIrmin.scala` are already
listed too. New to the inventory (mitigation merge-scan coverage):

- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala`

§7.5 (M3 analytics-VQL: selection argument + targeting predicates, 2026-09-10)
touches files already listed above (`MitigationApplication.scala`, `FolSymbols.scala`,
`RiskTreeKnowledgeBase.scala`, `QueryServiceLive.scala`, `Application.scala`,
`MitigationScopeResolverLive.scala`, `CachedResultResolver.scala`,
`CachedResultResolverLive.scala`, `RiskTreeRepositoryIrmin.scala`,
`RiskTreeRepositoryInMemory.scala`, `RiskTreeServiceLive.scala`,
`AnalyzeQueryState.scala`, `RiskTreeKnowledgeBaseSpec.scala`,
`BinderIntegrationSpec.scala`, `MitigationApplicationSpec.scala`,
`TargetingPredicateSpec.scala`, `CachedResultResolverSpec.scala`,
`MitigationStalenessSpec.scala`). New to the inventory for M3:

- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala`
- `modules/app/src/main/scala/app/views/AnalyzeView.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/QueryServiceLiveSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/QueryEndpointSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/DemoSimpleScriptSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/DemoEnterpriseScriptSpec.scala`

OD-5=D `getById` tuple ripple — specs that call the `RiskTreeRepository` trait
directly or stub it, adjusted with a mechanical `.map(_.map(_._1))` hash-discard
(assertions unchanged) or the stub's `override def getById` return-type update:

- `modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeReadConsistencySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemoryBranchSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala`

F1 `RiskTree.fromNodes` mandatory-`mitigations` ripple — the remaining
`commonJVM` call sites that relied on the removed default:

- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeBoundsSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/responses/SimulationResponseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala`
- `modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala`

F8 `RiskTreeService.getById` widening ripple — service callers and the sole
test stub of the widened trait; discard the hash at each boundary (mechanical
`.map(_.map(_._1))` for the `Task[Option[…]]` shape, or destructure in the
match arm for `ChangedNodesService`):

- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ChangedNodesService.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/ChangedNodesServiceSpec.scala`

Simulation parallelism cleanup — delete the never-wired `SimulationSemaphore`
and drive risk-node parallelism from config at the fork point. New to the
inventory (`Application.scala`, `RiskTreeServiceLive.scala`,
`CachedResultResolverLive.scala`, `RiskTreeServiceLiveSpec.scala`,
`AggregateFreshnessAfterLeafMoveSpec.scala`, `SeedStabilitySpec.scala`,
`RouteSecurityRegressionSpec.scala`, `RiskTreeControllerSpec.scala`,
`WorkspaceLifecycleControllerSpec.scala`, `HttpTestHarness.scala` and
`StubHttpTestHarness.scala` are already listed above):

- `modules/server/src/main/scala/com/risquanter/register/services/SimulationSemaphore.scala` (deleted)
- `modules/server/src/test/scala/com/risquanter/register/services/SimulationSemaphoreSpec.scala` (deleted)
- `modules/common/src/main/scala/com/risquanter/register/configs/SimulationConfig.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala`
- `modules/server/src/main/resources/application.conf`
- `modules/server/src/test/scala/com/risquanter/register/services/helper/SimulatorSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/configs/TestConfigs.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/DemoSpecSupport.scala`
- `modules/common/src/test/scala/com/risquanter/register/testutil/ConfigTestLoader.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/PreludeOrdUsageSpec.scala`

Monoid documentation accuracy — record on the type that the lawful structure is a
commutative monoid on each fixed-`nTrials` slice rather than on the whole type,
that the `Identity` instance's element belongs to the slice named by
`cfg.defaultNTrials`, and that the trial-count `require` is partiality in the same
sense the documented overflow is. Comment-only; no signature, type, or behaviour
change:


M4 slices 1–5 add (§7.6.13):

- `modules/common/src/main/scala/com/risquanter/register/http/requests/AnalysisRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/MitigationRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeUpdateRequest.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/responses/AnalysisResponses.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/responses/TreeStructureResponse.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceTreeEndpoints.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/requests/MitigationSelectionRequestSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/requests/MitigationRequestsSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeBoundsSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationEntitySpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/ScopeResolverScope.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/pipeline/InvalidationHandler.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala`
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/AggregateFreshnessAfterLeafMoveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RouteSecurityRegressionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/app/src/main/scala/app/state/LECChartState.scala`
- `modules/app/src/main/scala/app/state/TreeViewState.scala`
- `modules/app/src/test/scala/app/state/LECChartStateSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`

### Open decisions

This section records the M1 and M2 decision set. **The decisions that are open
now are in §7.6.12** — five gating the `ValuationResult` sub-slice ruled in §8.16,
and two gating M4 slice 6. Nothing below is open.

Status after the 2026-08-08 review session:

- **OD-1 — Plan staging.** ✅ RULED (Option A): M1+M2 implementation-grade now;
  M3/M4 elevated in §7.5/§7.6 before their builds.
- **OD-2 — D1 naming deviation.** ✅ RULED (Option A): `TransformSpec` →
  `ResultTransformSpec`; `TransformPipeline` unchanged. Consistency sweep done
  same day (this document's status header, D1 note, sequencing item;
  `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` B.8 intro and checklist).
- **OD-3 — Mitigation selection & comparison UX.** ✅ RULED (2026-08-09, user
  preference Option A + assistant selection confirmed against the
  copy-for-compare workflow): per-(mitigation, node) enablement via
  child-styled mitigation rows under scoped nodes + additive global tri-state
  control; M1 ships the generalized `MitigationSelection` /`ScopeRestriction`
  ADT (§7.1.5); display control = chart-level tri-mode Raw / Mitigated / Both
  per view and per slot (OD-3c ruled Option A — per-series eyes only if a
  concrete mixed-visibility need appears later). **Copy-for-compare (aligned
  semantics, generalized 2026-08-09):** available on the active (baseline)
  view AND on every comparand slot card — a button beside the slot's existing
  controls, styled like them. The gesture duplicates the source's complete
  state — branch, tree, commit pin, charted node selection, mitigation
  selection, display mode — into the next free comparand slot; source and
  copy then diverge independently (scrub the copy's pin, add a mitigation,
  …). Any slot can seed the next variant, so comparison chains build
  incrementally (baseline → fw → copy of fw + IDS). Use case: small
  comparisons — same risks, same mitigations, two versions back, plus one
  extra mitigation. Remaining build detail lands at §7.6 elevation. History
  of the decision below (kept as record):
  OPEN, refined 2026-08-08.
  Server side is settled for M2 (per-selection computation; every gesture
  already costs one `lec-multi` round-trip today). User's clarified proposal
  under review: a mitigation appears as a child-styled row **under each risk
  node its scope covers** (one mitigation, several appearances), and
  Ctrl+Click on such a row enables the mitigation **for that node only** —
  per-(mitigation, node) enablement, i.e. applying a mitigation restricted to
  a subset of its scope; ADDITIVELY, the originally designed global mechanism
  (enable/disable a mitigation across its whole scope, tri-state when
  partially enabled) is kept. Consequence if adopted: M1's
  `MitigationSelection` generalizes from a set of mitigation ids to a
  per-mitigation scope restriction (`Map[MitigationId, ScopeRestriction]`,
  `ScopeRestriction = FullScope | NodesOnly(Set[NodeId])`) — the ruling
  therefore fixes an M1 signature and must land before M1 freezes.
  Comparison model (refined with the user 2026-08-08): the Analyze view's
  **Compare slots** (`SlotCoordinate(branch, treeOverride, at)`; slots already
  support same-branch pairs for time-travel comparison) gain **mitigation
  selection as a slot dimension** — "copy-for-compare" duplicates the active
  view into the next free slot, where clicking mitigations sets that slot's
  selection; overlay/side-by-side then compares variants (raw vs fw vs fw+IDS
  = one slot each) with slot-keyed colours. This is phased **into M4** as a
  named work item (elevated at §7.6), not deferred. Display model (adopted
  2026-08-09): the response for a selection always carries BOTH the inherent and
  the selection's series per charted node; which series are drawn is client-side
  display state — so a **purely mitigated view** (residual-risk picture without
  the inherent series) is a display mode, not an API variant. Comparand slots
  with a selection display their variant curves only (baseline shows raw),
  keeping overlays free of duplicated raw curves. Open (OD-3c): the display
  control's granularity — chart-level tri-mode (Raw / Mitigated / Both) per
  view and slot, versus per-series visibility toggles, versus both. Scenario
  branches are NOT the comparison vehicle.
- **OD-4 — Semantic diff blindness.** ✅ RULED: backlog now, plus phase **M5**
  (§7.7) — problem space recorded, no design, planned only after M1–M4 land.
- **OD-5 — Selection default for existing read paths.** ✅ RULED (2026-08-08,
  Option A): `MitigationSelection.None` is the default on every existing read
  path — mitigation is strictly opt-in per request; no existing figure changes
  until a caller explicitly selects mitigations. §7.2.2's resolver defaults
  already encode this.
- **OD-6 — `staleOverrides` placement.** ✅ RULED (2026-08-09, Option B):
  relocated from `common`'s `MitigationApplication` (§7.1.5 as originally
  approved) to the server-side `MitigationStaleness` (§7.2.2a), original
  signature unchanged. Reasons, recorded for the decision trail:
  1. **Hash computation is JVM-only by prior decision.** `ContentHash` =
     `sha256(LeafSimContent.toJson)` via `java.security.MessageDigest` in
     `ContentHashIndex` — DD-14 (closed 2026-07-14 → "full JVM sha256"), whose
     load-bearing consistency argument is the **single-producer invariant**:
     no flow exists where two hash implementations must agree. Precision note:
     `MessageDigest` referenced from `common` (`CrossType.Pure`, one source
     tree) does compile under Scala.js and links as long as no JS code path
     reaches it — `WorkspaceKeyHash.fromSecret` (since relocated to server
     `WorkspaceKeyCrypto.hash`, which removed that instance) relied on exactly
     that unreachability pattern. A `common`-placed `staleOverrides` would have
     been the second such reachability-fragile site, breakable at link time by
     any future JS call; relocation removes the fragility instead of adding
     to it.
  2. **A second (JS/shared) hasher is ruled out**, not merely unchosen:
     cross-validation can pin the SHA-256 core but not the preimage bytes
     (platform-divergent `Double` rendering in the JSON preimage is silent,
     value-specific breakage), and a false "not stale" asserts frozen expert
     numbers against a changed base — reintroducing exactly the risk class
     DD-14 designed away.
  3. **`staleOverrides` is not algebra.** It participates in no law of the
     monoid action (resolution ignores staleness by design); it is a
     diagnostic predicate `Tree → Set[MitigationId]` comparing a stored
     fingerprint with a recomputed one. Moving it severs no algebraic
     structure; `MitigationApplication` keeps the complete action
     (`scoped`/`effectiveTree`/`resultTransformFor`/`applicationRecords`).
  4. **Its only caller is server-side by architecture.** Staleness is
     computed in HTTP handlers (ADR-030 orchestration boundary) and shipped
     as `staleMitigationIds` in payloads; the client renders, never computes.
     The rejected Option A (inject `hashOf: RiskLeaf => ContentHash` into a
     `common` signature) compiled fine but bought a shared capability with no
     caller on the second platform.

### Verification plan

Every phase lands only with the full suite green (no tier deferred):

```bash
sbt compile                          # zero warnings
sbt commonJVM/test
sbt server/test
sbt app/test                         # Scala.js (shared module codecs compile + run on JS)
sbt "serverIt/test"                  # integration (local/irmin-prod:3.11-p1)
# BATS fast gate after code changes:
#   run_bats tests/bats/suite-c-in-memory.bats   (register-dev skill invocation)
```

Tests added per phase are listed in §7.1.7 / §7.2.3; M3/M4 test plans arrive
with their elevation sections. Each phase closes with the doc-consistency
sweep (comments/docs touched by the change updated in the same pass) and its
PATCH bump; plan close = MINOR bump.
