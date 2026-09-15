# ADR review, 2026-09-15 — issue register

Produced by a full fresh read of all 45 files in `docs/dev/decision-records/`,
followed by a consistency check of PLAN-RISKTRANSFORM's `ValuationResult`
material (§8.16, §7.6.12, §7.6.10) against those records and against the code.

This file is the durable record of what the review found. It is a working
register, not a plan: nothing here confers G3 coverage.

---

## Group A — straightforward fixes

Each has one correct answer. No ruling needed.

### A1 — `descendantProvenances` needs a third branch *(applied to the plan)*

`CachedResultResolverLive.scala:201` matches exhaustively over the sealed
`LossDistribution`. `build.sbt` promotes a non-exhaustive match to a compile
error. Adding `ValuationResult` breaks the build until the branch
`case v: ValuationResult => descendantProvenances(v.source)` is added.

Status: design and its correctness argument written into PLAN-RISKTRANSFORM
§8.16, subsection "The sealed hierarchy gains a third case". The elevation is
required to restate why the branch is correct.

### A2 — plan §7.6.12 decision 1 stated the Scala sealing rule wrongly *(fixed)*

It said a sealed subtype must be in "that file or that file's directory". Scala 3
permits it only in the same source file. Verified with the locally installed
Scala 3.6.4 compiler: `Cannot extend sealed class Base in a different source file`.

Status: corrected in place, with the note that sealing is load-bearing for A1.

### A3 — `applicationRecords` is not the producer of `applied` *(fixed)*

§8.16 ruling 5 implied `MitigationApplication.applicationRecords` supplies the
new `applied` field. It does not: it groups across the whole tree, emits one
record per mitigation with the union of every node touched, and does not filter
by mitigation stage. `applied` needs per-node, result-stage-only, precedence-
ordered records. A new function is required beside the existing one.

Status: ruling 5 rewritten to say so; `applicationRecords` keeps its shape.

### A4 — §8.16 ruling 5 carried a stale staleness claim *(fixed)*

It said `MitigationApplicationRecord`'s scaladoc wrongly claims the record travels
in responses. That scaladoc was already corrected at `Mitigation.scala:245-253`.

Status: sentence removed.

### A5 — §7.6.12 decision 5 was listed as open but is a factual check *(fixed)*

Every file the sub-slice touches is already a bullet in the inventory.
`LossDistributionSpec.scala` is not a bullet and needs none — it is under
`modules/common/src/test/`, which the hook authorises because the plan lists files
under `modules/common/src/main/`.

Status: answered in place; only decision 2's stub ripple stays conditional.

---

## Group B — settled by `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md`

The user ruled 2026-09-15 that this document is authoritative for the mitigation
valuation design, being newer and more thoroughly reviewed than the ADRs, provided
it is internally consistent. It was checked and it is: §8.2, §8.4, §8.5 and §12.4
all use `source` the same way, and the Part 4 worked example is arithmetically
consistent with the Part 8 type table.

### B1 — what `source` holds *(plan corrected)*

`source` is the value this node's own transform was applied to: at a portfolio the
combine of the **mitigated** children, at a leaf the cached raw result. It is not
the raw value at that node. §8.2: *"`source` is `(+) m(children)` — the value
before this node's own transform"*. §8.4's table gives `Servers` a `source` of 20
against a raw figure of 23.

Plan §8.16 said "the raw value at that node". Corrected.

### B2 — how a raw reading is obtained *(plan corrected)*

§8.5: *"There is no separate raw method. A raw reading is the same fold with
nothing scoped anywhere — the identity fed in at every node."* It is the same
method called with `MitigationSelection.Inherent`; it is not read off a mitigated
result's `source`.

Plan §8.16 ruling 3 said "recoverable as `source`". Corrected.

### B3 — one method, two calls *(plan clarified)*

What was ruled is one *method* — no separate `resolveRaw` / `resolveMitigated`.
That is not a ruling that one *call* returns both readings. Part 9: *"The read
path resolves twice — once with nothing selected and once with the caller's
selection — and feeds both into one curve generation so the two series share an
x-axis."* §7.6.5's two `ensureCachedAll` calls match the document exactly.

Whether a pair-threaded fold could collapse them to one call is recorded as a
consequence to weigh under §7.6.12 decision 3, with its real cost stated: two
effective trees and two content-hash indexes.

### B4 — one inaccuracy in the document itself

§8.2 justifies a `Validation`-returning smart constructor by saying `ScaleLosses`
can overflow. It cannot throw today; it saturates (see C1). The user's ruling on
C1 makes the document's claim true rather than requiring the document to change.

---

## Group C — ruled 2026-09-15

### C1 — `scaleLosses` saturates instead of failing *(ruled: fix inside M4)*

`RiskResultTransform.scala:164-170` computes `(loss * factor).toLong`. Narrowing
an out-of-range `Double` saturates at `Long.MaxValue` rather than throwing.
Verified: `9000000000000000000L * 5.0` narrows to `9223372036854775807`. An
over-scaled loss is presented as a real figure.

The other three transforms are safe: `applyDeductible` subtracts non-negative
values and floors at zero, `capLosses` takes a minimum, `insurancePolicy`
composes those two.

**Ruling.** Detect it and route it through `Validation`, inside this sub-slice,
with a test pinning the correct behaviour as a required deliverable. `scaleLosses`
throws `ArithmeticException` the way `TrialOutcomes.combine` already does;
`ValuationResult.create` converts it to a `ValidationError` the way
`RiskResultGroup.create` already does (ADR-033 §3). `RiskResultTransform` keeps
its total shape. The guard must not land before the factory exists.

Status: written into PLAN-RISKTRANSFORM §8.16, subsection "Constructing a
`ValuationResult`". Not implemented.

### C2 — `ValuationResult` has no construction gate *(ruled: `create` returning `Validation`)*

§8.16 declared a private primary constructor and no companion factory, so nothing
outside `LossDistribution.scala` could build one — including the resolver in
`server`, which is the only thing that needs to. ADR-001 §5 requires a smart
constructor as the sole gate. C1 supplies the failure it carries.

Status: written into the same subsection. Not implemented.

---

## Group D — needs ruling or review

**Routed 2026-09-15.** D1 and the two pre-existing provenance corrections (D3, D4)
are ruled into PLAN-RISKTRANSFORM slice 5. Everything else on this list — D2, D5,
D6, D7 and the wider ADR-003 question — is housekeeping and now lives as a task
list in `docs/dev/ADR-HOUSEKEEPING.md`, which carries the citation graph for each
record. Do not work these from here; work them from that list.

### D1 — ADR alignment table §7.6.10 is missing rows

Four entries need adding or upgrading before the sub-slice is elevated:

| ADR | What is wrong now | What it should say |
|---|---|---|
| ADR-003 | no row | Amended — the provenance derivation in §3 changes (A1), and the Implementation table's `includeProvenance` row is false (D3) |
| ADR-033 | no row | Amended — `ValuationResult.create` is a new named-exception conversion boundary; ADR-033's Implementation table already lists `LossDistribution.scala` |
| ADR-009 | "Compliant / unchanged" | Amended — §2 enumerates exactly two subtypes and the Implementation table names them; a third makes both stale |
| ADR-015 | "Compliant" | Amended if §7.6.12 decision 2 narrows the resolver return type, which ADR-015 writes out verbatim |

The table is a hand-edited structured block, so the rows are handed over as a
copy-pasteable amendment rather than edited directly.

### D2 — three ADRs describe a cache that no longer exists

`RiskResultCache` and `TreeCacheManager` appear in ADR-004b, ADR-005, ADR-014,
ADR-014-appendix-b and ADR-015. Neither type occurs in any `.scala` file.

ADR-005 and ADR-015 open with a 2026-07-18 note saying the layer is retired.
**ADR-014 and ADR-014-appendix-b carry no such note.** ADR-014 §1 publishes a
cache interface with `invalidate(nodeId)`, §3 describes ancestor-path
invalidation, and appendix B diagrams the whole invalidation flow. The live cache
is content-addressed and has no invalidation at all.

ADR-032 and ADR-028 both cite ADR-014 as a live reference. The `adr-constraints`
skill states that a file which exists is in force.

Open question: amend ADR-014 to describe the content-addressed cache, or mark it
superseded and let ADR-032 carry the design.

### D3 — ADR-003 claims an inert feature is implemented

Implementation table row: *"Optional provenance capture | ✅ Implemented
(`includeProvenance` flag)"*. The flag sets a tracing attribute and nothing else.
`CachedResultResolver`'s scaladoc says *"Recorded as a tracing attribute only"*
and `RiskTreeService.scala:94` says *"currently unused for this endpoint"*. No
production call site passes `true`.

Related standing item: removal of the dead `includeProvenance` parameter.

### D4 — two ADRs publish a provenance pattern production does not use

ADR-003 §3 and ADR-009 §5 both show
`group.children.collect { case r: RiskResult => r.nodeId -> r.provenances }`.
That node-to-records pairing occurs nowhere in `src/main`. The real derivation is
`descendantProvenances`, which returns a flat `List[NodeProvenance]` with no
pairing. The only occurrence of the ADR pattern is `ProvenanceSpec.scala:336-337`.

### D5 — two ADRs publish a resolver signature two generations old

ADR-005 and ADR-015 both write
`ensureCached(tree, nodeId, includeProvenance: Boolean = false): Task[RiskResult]`.
The live trait takes six parameters, including `seedEntityId`, `selection` and
`resolvedScopes`, and returns `Task[LossDistribution]`.

### D6 — ADR-008 and ADR-012 §4 give opposite instructions, both in force

ADR-008 §2 and §3 tell the reader to build a retry schedule and a circuit breaker
in Scala, with code. ADR-012 §4 is headed "No Application-Level Resilience Code"
and lists retries, circuit breaking, timeouts and rate limiting as delegated to
Istio, with "❌ Application-Level Circuit Breaker" as a code smell. ADR-031 carves
out the single permitted exception, the bounded startup readiness gate, and cites
ADR-012 §4 as the rule it excepts — never ADR-008.

ADR-008 also defines a competing error model, an enum called `RiskAppError`,
against the live sealed `AppError` hierarchy owned by ADR-010 and ADR-035.

ADR-008 carries no superseded note. This is the one item in the register that can
cause wrong code to be written by an agent correctly citing an in-force ADR.

Recommended: a superseded banner on ADR-008 pointing at ADR-012 §4, ADR-010 and
ADR-031. One paragraph, no dependants.

### D7 — the coming registry rename will stale two more ADR mentions

`PLAN-CACHE-REGISTRY-RENAME` renames `CacheScope` to `ContentCacheRegistry` and
`cacheFor` to `forWorkspace`. `CacheScope` is named in ADR-015's retirement
banner; `cacheFor` is named in ADR-005's code block. Two one-line edits belong in
that plan's documentation sweep.

---

## Verification notes

Two claims in this register were checked by compiling rather than by reading:

- Scala 3 refuses a sealed subtype in a sibling file (A2). Scala 3.6.4,
  `Cannot extend sealed class Base in a different source file`.
- `Double` to `Long` narrowing saturates (C1). `9000000000000000000L * 5.0`
  narrows to `9223372036854775807`.
