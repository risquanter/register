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

## T1 — ADR-008 (Error Handling & Resilience)

**Why it is here.** §2 and §3 tell the reader to build a retry schedule and a
circuit breaker in Scala, with code. ADR-012 §4 forbids exactly that. ADR-031
carves out the one permitted exception and cites ADR-012 §4 as the rule it
excepts, never ADR-008. Both records are in force, because a file that exists is
in force. This is the only item in the review that can cause wrong code to be
written by an agent correctly citing an in-force ADR.

ADR-008 also defines a `RiskAppError` enum against the live sealed `AppError`
hierarchy owned by ADR-010 and ADR-035.

**User's reading.** Retries went to the Kubernetes infrastructure in
`register-infra`. ADR-008 should probably have been retired, at least §2 and §3.
`RiskAppError` → `AppError` is a reference update.

**Decision needed:** retire the whole record, or retire §2 and §3 and keep the
rest. Depends on whether anything in §1, §4, §5, §6 or the per-layer error tables
is live and not already said by ADR-010 / ADR-035 / ADR-031.

**Citations to sweep if it is retired or renumbered**

| Where | Kind |
|---|---|
| `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala` | code comment |
| `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala` | code comment |
| `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala` | test comment |
| `modules/app/src/main/scala/app/state/GlobalError.scala` | code comment |
| `modules/app/src/main/scala/app/views/ErrorBanner.scala` | code comment |
| `docs/dev/plans/IMPLEMENTATION-PLAN.md`, `docs/dev/plans/PLAN-RISKTRANSFORM.md` | doc |

Note: the five code references are under `modules/`, so that part of the sweep is
hook-gated even though this document is not.

---

## T2 — ADR-012 (Service Mesh Strategy)

**Why it is here.** Raised by the user alongside T1: if resilience now lives in
the `register-infra` Kubernetes configuration rather than in an Istio-centric
design, ADR-012's §4 may describe a delegation target that has moved. ADR-012 is
marked "Accepted (awaiting implementation)".

**Decision needed:** whether §4's delegation target is still accurate, and more
broadly whether ADR-012 describes the deployment as it is now. This one reaches
outside this repository, so it may need a look at `register-infra` before it can
be answered.

**Dependency:** T1's ruling should not be applied before this is at least scoped,
because T1's superseded banner will point at ADR-012 §4 as the governing rule.

**Citations:** eleven documents including ADR-021, ADR-022, ADR-023, ADR-024,
ADR-026, ADR-031, `THREAT-CATALOG-(INCOMPLETE).md`, `TODO.md` and three
authorization plans. Any renumbering is expensive; amendment in place is cheap.

---

## T3 — ADR-014 (RiskResult Caching Strategy) and ADR-014-appendix-b

**Why it is here.** ADR-014 §1 publishes a cache interface with
`invalidate(nodeId)`, §3 describes ancestor-path invalidation, and appendix B
diagrams the whole invalidation flow. `RiskResultCache` and `TreeCacheManager` do
not exist in any `.scala` file. The live cache is content-addressed and has no
invalidation at all. Unlike ADR-005 and ADR-015, neither file carries a
superseded note.

**User's reading.** The invalidation mechanism was retired and the cache was
renamed and redesigned; the reason is not recalled. If the record's focus is
solely invalidation, retire it. If not, review whether the remaining content
overlaps with the records that replaced it and remove only the dead part.

**First step, before any decision:** recover *why* invalidation was retired.
`docs/archive/milestone-2b-cache-and-decisions.md` holds DD-14 to DD-20 and DD-20
is referenced elsewhere as "content addressing needs no invalidation". That
archive is the place to read it from, and the answer belongs in whatever replaces
ADR-014 so the reason is not lost a second time.

**Content that may be live and is not obviously said elsewhere:** §1's argument
for caching outcomes rather than rendered curves, and §2's shared-tick-domain
rule, which ADR-014 is still cited for. ADR-032 covers the key projection but not
either of those.

**Decision needed:** retire, or amend to describe the content-addressed cache and
keep the two live arguments.

**Citations to sweep**

| Where | Kind |
|---|---|
| `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala` | code comment |
| ADR-004a-appendix, ADR-005, ADR-009, ADR-015, ADR-028, ADR-032 | decision records |
| PLAN-RISKTRANSFORM, PLAN-MONOID-RISKRESULT-AND-MITIGATION, PLAN-MITIGATION-ROI, IMPLEMENTATION-PLAN | plans |

ADR-032 and ADR-028 cite ADR-014 as a live reference, so whichever way this goes,
those two citations change meaning and must be revisited rather than left.

---

## T4 — ADR-003 (Provenance and Reproducibility)

*Recorded from the user's instruction "do the same approach also with ADR-03",
read as ADR-003. Confirm before acting if that was meant differently.*

**Why it is here.** Two statements are wrong:

- The Implementation table claims optional provenance capture is implemented via
  an `includeProvenance` flag. That flag sets a tracing attribute and nothing
  else, and no production caller passes `true`.
- §3 publishes `group.children.collect { case r: RiskResult => r.nodeId -> r.provenances }`
  as the provenance derivation. That pattern appears nowhere in `src/main`. The
  live derivation is `descendantProvenances` in `CachedResultResolverLive`, which
  returns a flat `List[NodeProvenance]` with no node-id pairing.

**Already covered elsewhere — do not duplicate.** PLAN-RISKTRANSFORM slice 5 was
ruled on 2026-09-15 to correct both of those statements as part of the
`ValuationResult` work, because they sit in the paragraphs that change anyway. So
T4 is **not** "fix those two"; it is the wider question the user asked for.

**Decision needed:** whether what remains of ADR-003 after slice 5 is a live
record. Its four-layer seed hierarchy and its distribution-parameter storage are
live and are not said anywhere else, so outright retirement looks unlikely — but
the split between what ADR-003 owns and what the seed-identity work
(`DONE-PLAN-SEED-IDENTITY.md`) superseded needs stating, since §1 already carries
one superseded-derivation note.

**Related open item.** There is no provenance API. `PLAN-PROVENANCE-ENDPOINT.md`
is marked "Approved — awaiting implementation", with a note that its response
shape needs revising because DD-19 moved provenance onto `RiskResult` only. Its
stated objective also includes removing the dead `includeProvenance` parameter.
Whether that plan is revived, re-scoped or dropped is a decision of its own and
is the reason ADR-003's Implementation table has been wrong for so long.

---

## T5 — ADR-005 and ADR-015: resolver signature drift

**Why it is here.** Both publish
`ensureCached(tree, nodeId, includeProvenance: Boolean = false): Task[RiskResult]`.
The live trait takes six parameters — adding `seedEntityId`, `selection` and
`resolvedScopes` — and returns `Task[LossDistribution]`.

**User's ruling:** update both to the live code where behaviour has not
fundamentally changed. *Mechanical*, with one caveat.

**Caveat.** The return type is about to move again. PLAN-RISKTRANSFORM §7.6.12
decision 2 was ruled on 2026-09-15 to narrow it to `ValuationResult`. Doing T5
before that lands means editing these two records twice. Sequence T5 after the
`ValuationResult` sub-slice, or accept the second edit.

Both records already carry 2026-07-18 banners saying the storage layer they sketch
is retired, so only the resolver signature blocks are in scope here.

---

## T6 — ADR-004b and ADR-005: retired cache types

**Why it is here.** Both name `RiskResultCache` and `TreeCacheManager` in
Implementation tables as though they exist. ADR-005 carries a banner; ADR-004b
does not. ADR-004b is the unadopted WebSocket variant, so its whole status is
worth confirming rather than only its cache rows.

**Mechanical** for the table rows once T3 settles what the replacement is called.

---

## T7 — the registry rename's documentation sweep

**Why it is here.** `PLAN-CACHE-REGISTRY-RENAME` renames `CacheScope` to
`ContentCacheRegistry` and `cacheFor` to `forWorkspace`. `CacheScope` is named in
ADR-015's retirement banner; `cacheFor` is named in ADR-005's code block.

**Mechanical**, and it belongs to that plan's own documentation sweep rather than
to this list. Recorded here so it is not lost if the sweep misses it.

---

## Sequencing

T2 before T1, because T1's banner points at ADR-012 §4.
T3 before T6, because T6's replacement wording depends on T3's outcome.
T5 after the `ValuationResult` sub-slice, or accept editing twice.
T4 after PLAN-RISKTRANSFORM slice 5, which already fixes the two statements.
T7 rides with the rename plan.
