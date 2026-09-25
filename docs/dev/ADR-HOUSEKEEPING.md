# ADR housekeeping — task list

Opened 2026-09-15 from the full ADR review. The findings themselves are in
[`docs/scratch/ADR-REVIEW-2026-09-15.md`](../scratch/ADR-REVIEW-2026-09-15.md).

**What this document is.** A task list for correcting the decision records, not a
plan. It is deliberately not plan-level: nothing here changes behaviour, nothing
here needs a file inventory, and no task here is gated by the approval hook. It
is housekeeping.

**How it is worked.** Every task that involves a real choice — retire a record or
amend it, where content moves to, whether behaviour actually changed — is put to
the user in `decision-guide` format and waits. Tasks marked *mechanical* are edits
with one correct answer and are done without asking. When a record is retired or
substantially rewritten, every document and comment citing it is swept in the
same pass; the citation lists below exist so that sweep is not re-derived.

**Guidance recorded from the user, 2026-09-15.** Retries were outsourced to the
Kubernetes infrastructure in the `register-infra` project. `AppError` superseded
`RiskAppError` and that part is a reference update rather than a design question.
A record whose sole subject has been retired should itself be retired; a record
with other live content should be reviewed for overlap with the records that
replaced it, and only the dead part removed.

---

## T1 — ADR-008 (Error Handling & Resilience) — DONE

**Outcome: retired. `ADR-008-proposal.md` is deleted.** Every part of it was
contradicted, unbuilt, superseded, or has a home elsewhere:

| Section | Disposition |
|---|---|
| §1 `RiskAppError` enum | Superseded by the sealed `AppError` hierarchy (ADR-010, ADR-035) |
| §2 retry schedule | Contradicted by ADR-012 §4; the only retry loop in server main is ADR-031's startup gate |
| §3 circuit breaker | Contradicted by ADR-012 §4, which names it as a code smell; none exists |
| §4 graceful degradation | Never built — no `ServiceStatus.Degraded`, no cache fallback |
| §5 SSE reconnection | Never built |
| §6 user-facing messages | The `UserMessage` ADT was never built; the live two-tier pattern it gestured at is now **ADR-019 Pattern 7** |

**The carve-out that made this look ambiguous.** Default delegation to the mesh
with one Scala exception is a closed loop between ADR-012 §4 and ADR-031, which
cites ADR-012 §4 as the rule it excepts. ADR-008 sat outside that loop and
stated the opposite unconditionally, which is why it was the one item in the
review able to make an agent write wrong code while correctly citing an in-force
record.

**Doc citations swept:** `IMPLEMENTATION-PLAN` — the Phase I.a goal now cites
ADR-019 Pattern 7 and two ADR-table rows are gone — and `PLAN-RISKTRANSFORM`,
whose no-bearing list entry is removed.

**Five code comments still cite ADR-008. All are under `modules/`, so they are
hook-gated and were not applied here; they dangle until swept.**

| File | Line | Should read |
|---|---|---|
| `common/.../domain/errors/AppError.scala` | 103 | `// Infrastructure Errors (ADR-010)` |
| `common/.../domain/errors/ErrorResponse.scala` | 192 | exhaustive-match comment cites ADR-035 |
| `common/.../domain/errors/ErrorResponse.scala` | 280 | `// ── Infrastructure Error Responses (ADR-010) ──` |
| `common/.../domain/errors/ErrorResponseSpec.scala` | 132 | `// Infrastructure errors (ADR-010)` |
| `app/.../state/GlobalError.scala` | 14, 23 | `@see ADR-019` Pattern 7 and ADR-010 |
| `app/.../views/ErrorBanner.scala` | 19 | `@see ADR-019 / ADR-010` |

`GlobalError.scala:14` and `ErrorBanner.scala:11` additionally carry `Option A`
and `Phase I.a` plan references, which the comment-style rule forbids
independently of this task and which the same sweep should remove.

---

## T2 — ADR-012 (Service Mesh Strategy) — DONE

**Checked against `register-infra`, not against recollection.** The Istio design
is real and present as manifests: `istio/ingress-gateway.yaml`,
`request-authentication.yaml`, `authorization-policy.yaml`,
`envoy-filter-strip-headers.yaml`, `peer-authentication.yaml`,
`opa/ext-authz-filter.yaml` and `network-policy/register.yaml`. Resilience did
not move away from Istio — `register-infra` *is* the Istio deployment.

**What was wrong was §4's mechanism names and the state it implied.** Routing is
expressed in the **Gateway API**, a `Gateway` plus an `HTTPRoute`, not in
`VirtualService` / `DestinationRule`. And no resilience policy is configured at
all: the `HTTPRoute` is a bare `backendRefs` to the frontend, no
`DestinationRule` exists, and the only timeout in the deployment is the 100 ms
`ext_authz` bound on the OPA filter, which guards the authorization call rather
than the application's own dependencies.

**Done in this pass:**

- §4 rewritten. The prohibition on application-level resilience is kept and
  strengthened — it holds whether or not the mesh configures a policy for a
  given call — the mechanism is corrected to Gateway API, and the absence of any
  retry, timeout or outlier-detection policy is stated plainly as the mesh's gap
  to close rather than left implied.
- Status changed from "Accepted (awaiting implementation)" to "Accepted".
- An Implementation Guidance table added, mapping each concern to its manifest
  path in `register-infra`, so the status claim is checkable rather than asserted.

No renumbering, so none of the eleven citing documents needed touching.

---

## T3 — ADR-014 — DONE

**Ruled by the user:** update, do not retire; delete the old appendix outright;
add a new appendix only if ADR-00X's size target needs one; and write the result
as a faithful description of the current state, carrying no account of the
change, the rework, or the previous design.

**What the code showed.** `RiskResultCache`, `TreeCacheManager` and
`IrminWatcher` occur in no `.scala` file, and the live cache exposes no
invalidation operation — `ContentCache`'s own scaladoc states that staleness is
structurally impossible because the key is recomputed from content on every
read. Two parts of the record were nonetheless live: the argument for caching
outcomes rather than rendered curves, and the render-time shared tick domain,
which is implemented in `LECGenerator.generateCurvePointsMulti` and is what both
surviving in-source ADR-014 citations point at.

**Done in this pass:**

- ADR-014 rewritten as "Simulation Result Caching and Render-Time Curve
  Generation", describing the content-addressed cache: outcomes not curves,
  content-hash keys with no invalidation, leaf entries only with portfolios
  folded on read, one cache per workspace holding identity-free values, and the
  render-time tick domain. 166 lines, inside ADR-00X's 100–200 target, so no
  appendix was created.
- `ADR-014-appendix.md` and `ADR-014-appendix-b.md` deleted. The live half of the
  performance appendix is carried by the rewritten Context and Decision 1.
- Citations swept: ADR-015's Related list (dangling appendix-b link removed),
  ADR-032, ADR-028 and ADR-009 references retitled, ADR-004a-appendix §2 rewritten
  from "O(depth) cache invalidation" to the SSE change-notification set it now
  describes, and IMPLEMENTATION-PLAN's ADR table row corrected to name
  `ContentCache`, `ContentHashIndex` and `ContentCacheRegistry`.
- The two in-source comments (`RiskTreeServiceLive`, `LECGenerator`) cite the
  render-time strategy and remain correct; no code changed.

**Not swept, and deliberately so:** PLAN-RISKTRANSFORM's ADR-014 mentions are
mostly the *vql-engine* ADR-014, which that plan already disambiguates at
line 1622.

---

## T4 — ADR-003 (Provenance and Reproducibility) — DONE, one follow-up

**Scope question, answered.** ADR-003 stays. No other decision record states the
seed hierarchy, the seed-identity rules or the distribution-parameter storage —
ADR-009, ADR-014, ADR-015 and ADR-032 consume those concepts rather than owning
them — so there was no record to move the live content into.

**What was done (2026-09-25).** The end state of the boundary-assigned
seed-identity work, until then readable only in an archived plan, is now stated
in ADR-003 as a decision taken, in the ADR-00X shape:

- New Decision 2, "Seed Identity Is Assigned Data, Stored on the Node": the two
  Iron ranges, boundary assignment in sorted-name order from
  `RiskTree.seedVarHighWater + 1`, high-water rather than highest-in-use,
  per-tree distinctness of `seedVarId` against per-deployment uniqueness of
  `seedEntityId`, immutability, and the rename-changes-nothing consequence.
- §1's superseded-derivation note and its pointer into the archived plan are
  gone; §1 states the derivation that is in force and nothing about what
  preceded it.
- Old §5 (Per-Node Provenance) is folded into Decision 4; its duplicate
  `NodeProvenance` block and its rationale lines are merged there. Five numbered
  decisions, within the ADR-00X target.
- A new "Reproduction" table states which cases reproduce and which do not,
  including the deliberate non-reproduction across workspaces.
- A new code smell, "Seed Derived from a Name or an Application Identifier",
  carries prescriptively what the deleted history note carried as narrative.
- Header: the `Updated:` field is dropped and `Date:` states when this form of
  the decision stands. Decision codes (DD-18, DD-19) are removed from the prose.
- `SimulationConfig`'s field names are corrected to `defaultNTrials`,
  `defaultTrialParallelism`, `defaultSeed3` and `defaultSeed4`.
  `maxConcurrentSimulations` is deliberately not listed: its own scaladoc
  records that nothing reads it.

**Follow-up, carried by PLAN-RISKTRANSFORM slice 5.** Two statements in ADR-003
are wrong and were left in place untouched, because slice 5 was ruled on
2026-09-15 to rewrite the paragraphs they sit in:

- The Implementation table's row "Optional provenance capture — ✅ Implemented
  (`includeProvenance` flag)". The flag sets a tracing attribute and nothing
  else, and no production caller passes `true`.
- Decision 4's published derivation
  `group.children.collect { case r: RiskResult => r.nodeId -> r.provenances }`.
  That pattern appears nowhere in `src/main`; the live derivation is
  `descendantProvenances` in `CachedResultResolverLive`, which returns a flat
  `List[NodeProvenance]` with no node-id pairing.

Both are recorded in PLAN-RISKTRANSFORM §7.6.9 and its §7.6.10 alignment row.
Nothing else in T4 is open.

**Found while checking, not fixed here.**

- `docs/dev/TODO.md:625` links `./DONE-PLAN-SEED-IDENTITY.md`, which resolves
  under `docs/dev/`. The file is at `docs/archive/DONE-PLAN-SEED-IDENTITY.md`.
- `modules/common/.../domain/data/iron/OpaqueTypes.scala:200` cites
  "PLAN-SEED-IDENTITY §5.1" in a comment. Plan references in comments are
  forbidden by the comment-style rule, and the file is hook-gated, so the edit
  needs an inventory that names it.

**Related open item — settled.** The user ruled: update the plan to current
reality wherever that does not contradict its intent, and an inventory may be
created. `PLAN-PROVENANCE-ENDPOINT.md` was rewritten in that pass. Its premise
was re-verified first and still holds exactly: `includeProvenance` is declared
on both analysis endpoints and threaded through controller, service and
resolver, and its only use at any site is `tracing.setAttribute`. No response
type carries provenance and no route serves it.

What the rewrite changed, all of it drift rather than intent: `NodeProvenance`
no longer carries `riskId`; the response shape is DD-19's attributed
`Map[NodeId, NodeProvenance]`; signatures gained `seedEntityId`, `rev`,
`selection`, `resolvedScopes` and `omitAbsent`; `lookupNodeInTree` now returns a
triple with the `CommitHash`; `TreeCacheManager` is gone from the test layer.
One defect was corrected rather than carried: the old Phase 2 read
`result.provenances` off a `LossDistribution`, which does not compile —
`provenances` is a member of `RiskResult` only — so the plan now specifies a
named `attributedProvenances` walk that keeps the node id.

One open decision is recorded in the plan: whether the endpoint should ever
accept a `MitigationSelection`. The `Map` shape is total only for the inherent
valuation, because a collapsed transformed portfolio carries its descendants'
records under its own id.

---

## T5 — ADR-015: resolver signature drift

**Why it is here.** It publishes
`ensureCached(tree, nodeId, includeProvenance: Boolean = false): Task[RiskResult]`.
The live trait takes six parameters — adding `seedEntityId`, `selection` and
`resolvedScopes` — and returns `Task[LossDistribution]`.

**User's ruling:** update it to the live code where behaviour has not
fundamentally changed. *Mechanical*, with one caveat.

**Caveat.** The return type is about to move again. PLAN-RISKTRANSFORM §7.6.12
decision 2 was ruled on 2026-09-15 to narrow it to `ValuationResult`. Doing T5
before that lands means editing the record twice. Sequence T5 after the
`ValuationResult` sub-slice, or accept the second edit.

ADR-005 carried the same drifted signature and was in this task's scope until it
was retired to `docs/archive/decision-records/`. An archived record is not
maintained against the code, so only ADR-015 remains in scope.

---

## T6 — ADR-004b and ADR-005: retired cache types — DONE

**ADR-004b** gained a header stating, in current-state terms, that it describes
an alternative the system does not implement — the adopted transport is
ADR-004a's unidirectional SSE — and that its purpose is to reserve the scope for
multi-user collaborative editing, which is the trigger that would bring it back.
Its Implementation table now carries a State column: `IrminClient` and
`ContentCache` exist; `WebSocketHub`, `PresenceHub` and `ConflictDetector` do
not.

**ADR-005**'s Implementation table claimed `RiskResultCache` and
`TreeCacheManager` were implemented. Both rows now say what is true — not built,
the cache is content-addressed, no invalidation operation exists — and
`InvalidationHandler`'s row states what it actually does, with its path
corrected to `services/pipeline/`. The code-block comment reading "Actual
implementation" above the unbuilt trait now says it is a sketch.

ADR-005's resolver signature block is **not** in scope here; that is T5.

---

## T7 — the registry rename's documentation sweep — DONE

**Done.** `PLAN-CACHE-REGISTRY-RENAME` landed; current-state docs and code name
`ContentCacheRegistry` (per-workspace `ContentCache` resolution) and
`MitigationScopeResolverRegistry` (per-workspace `MitigationScopeResolver`
resolution). ADR-005-proposal is untouched: it names the method on
`TreeCacheManager`, a proposal never adopted, not the type this plan renamed.

---

## T8 — ADR-036 (Confidential Internal Identifiers) — DONE 2026-09-15

**Why it was here.** Two passages of `PLAN-RISKTRANSFORM` asserted that node ids
are confidential internal identifiers under ADR-036 and that an access log
writing them to disk was a reason to redesign an endpoint. ADR-036 says neither
thing: it names `WorkspaceId` as its subject, shows `nodeId` as a client-safe
reporting field, and §3 explicitly permits a confidential identifier in server
logs.

**Settled by a security review**, which tested the claim against the code rather
than the ADR text alone. The finding: confinement is warranted only where
presenting an identifier makes the server widen its lookup. `WorkspaceId` has
that property, because `WorkspaceStore.resolveById` spans every workspace with no
capability check. A node id does not: every lookup of one runs against
`tree.index.nodes`, a map belonging to a single tree the caller already passed
`ws.trees.contains(treeId)` for, and no code path takes a bare node id and
searches across trees. A node id from another workspace is therefore
indistinguishable from one that was never issued — not by response shape, and not
by branch, because it is the same `None` from the same lookup.

**Done in this pass:**

- ADR-036 gained §4, "Identifiers Not Confined by This Record", naming `TreeId`,
  `NodeId` and `MitigationId` as client-facing, with the property that decides it
  and the worked A-and-B walkthrough.
- The Implementation table gained a row for the three.
- The References line claiming "`WorkspaceId`/`TreeId` stay internal" now says
  what ADR-021 actually decided: `TreeId` is never the credential, which is not
  the same as never appearing on the wire — ADR-021 §3's own endpoint design puts
  it in the path.
- Both wrong passages in `PLAN-RISKTRANSFORM` were corrected to reason from the
  selection payload's size, which is the argument that always held, and the
  ADR-036 alignment-table row now states the real compliance ground.

No code changed. No vulnerability was found.

---

## T9 — ADR-006 (Real-Time Collaboration) — DONE

**Why it was here.** Nothing in the record is built: `EventHub`, presence and
cursor tracking, version-vector conflict detection and the `RiskEvent` ADT occur
in no source file, and the SSE hub the system does have is notification-only and
single-writer.

**Ruled by the user:** the design was postponed, and the record is kept so that a
feature whose scope would overlap multi-user editing is found while it is still
being planned. It is used exactly that way — `PLAN-RISKTRANSFORM` runs an
explicit ADR sweep and records ADR-006 under "No bearing (checked, explicitly)".

**Done in this pass:** a header paragraph stating, in current-state terms, that
the record describes a design the system does not implement and that it holds a
scope boundary for planning. The record itself is retired to
`docs/archive/decision-records/` under T10; being kept and being in force are
separate things, and it is the first of the two.

**Left open, and small.** `PLAN-RISKTRANSFORM`'s ADR-alignment list carries a row
labelled "**ADR-006 / M2-D2**" whose content is about `ScopeOutcome` and
`ScopeResolutionFailure` being `enum`s with `toEither` and no `map`/`flatMap`.
That has no connection to real-time collaboration. M2-D2 is a genuine plan
decision (§8.8, ruled 2026-08-15) and the claim itself is sound; only the ADR
number is wrong. The obligation it answers is the working-protocol skill's
functional-composition checklist item "No ADT with a data-carrying success
variant lacks `map`/`flatMap`", so the row should cite that checklist rather
than an ADR. Relabelling it is mechanical; guessing which digit was intended is
not, so it is recorded rather than assumed.

---

## T10 — retired records move to `docs/archive/decision-records/` — DONE

**What changed.** Until now a decision record had two possible fates: stay in
`docs/dev/decision-records/`, where every file is in force whatever its Status
field says, or be deleted. `docs/archive/decision-records/` adds a third: a
record that is kept and readable but is not in force.

**Moved, with the state each carries:**

| Record | Status | Why it is not in force |
|---|---|---|
| `ADR-004b-proposal.md` | Retired — superseded by ADR-004a | The adopted transport is ADR-004a's unidirectional SSE. No `WebSocketHub`, `PresenceHub` or `ConflictDetector` exists |
| `ADR-005-proposal.md` | Retired — superseded by ADR-014 and ADR-015 | The per-node `RiskResultCache`/`TreeCacheManager` design exists in no source file. Caching is content-addressed with no invalidation (ADR-014); the cache-aside primitive is ADR-015's; `TreeIndex` navigation is ADR-001's |
| `ADR-006-proposal.md` | Retired — never adopted | `EventHub`, presence and cursor tracking, version-vector conflict detection and `RiskEvent` exist in no source file |

**Not moved, and why.** ADR-007 (scenario branching), ADR-016 (configuration
management) and ADR-025 (SPA routing) still read `Proposed` but describe
implemented behaviour, so they are in force and their Status fields are wrong
instead. ADR-015 describes a retired storage layer but its resolver decision
stands, which is T5.

**Two consequences to settle.**

1. ADR-004b and ADR-006 are kept specifically so a plan touching multi-user
   editing finds them at planning time. That works only if planning reads the
   archive folder as well as the live one. Nothing states that it does.
2. The `adr-constraints` skill says "All ADRs present in `docs/dev/` are live
   regardless of the Status field. Deletion is the only form of archival — a file
   that exists is in force." The archive folder makes the second sentence false.
   The skill is not edited here: it is the subject of the ADR-maintenance rule
   still to be specified.

---

## Sequencing

T5 after the `ValuationResult` sub-slice, or accept editing twice — the only
task still sequenced against other work.
T1, T2, T3, T4, T6, T7, T8, T9 and T10 are done. T4 leaves one follow-up, the two
wrong ADR-003 statements, which PLAN-RISKTRANSFORM slice 5 carries.
