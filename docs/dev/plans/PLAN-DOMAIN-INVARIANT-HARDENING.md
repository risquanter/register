# PLAN — Domain-invariant hardening (PLAN-RISKTRANSFORM §9)

**Status:** elevated to implementation-grade, awaiting approval (not started).
**Lineage:** implements PLAN-RISKTRANSFORM.md §9 ("Domain-invariant hardening,
immediate follow-up to M1R"). §9 there is the design-stage sketch; this file is
its implementation-grade elevation, with its own file inventory and hook token,
per §9's own statement that it does not ride M1R's approval.
**Bump:** PATCH on landing (phase step, not a plan-family close) from the
then-current `build.sbt` version (0.10.28 at time of writing → 0.10.29); mirror
`APP_VERSION` into `.env` and `.env.irmin`.

---

## Pre-implementation gate — MANDATORY, blocks the first source edit

**Do not begin the Implementation section below until this gate is cleared and
reported.**

This plan edits ADR-001 (adds one new Decision pattern). TODO #36 (Option C)
restructures ADR-001 first — it renumbers the Decision sequence, extracts §8 and
two sections to `ADR-001-appendix.md`, consolidates the examples, and sweeps the
`§`-anchored citations. This plan's ADR-001 edit therefore targets the
**post-#36** document, and its new pattern takes the next sequential number in
the renumbered sequence.

Before the first source edit of this plan:

1. Confirm TODO #36 (ADR-001 Option C) has landed green.
2. Run a **consistency sweep** of #36's output: read the post-#36 ADR-001 and
   `ADR-001-appendix.md`; record the final Decision numbering; confirm every
   `§`-anchored citation listed in TODO #36 was re-pointed and still resolves.
3. **Report back** — state the post-#36 numbering, the number the new
   "Aggregate Constructors Are Private" pattern will take, and confirm the
   citations resolve. Halt (G6) for an accepted signal before editing source.

If #36 has not landed, this plan is blocked; do not start it.

---

## Goal

Close two pre-existing domain-invariant gaps and one transport gap surfaced by
M1R's bounds work. Neither was introduced by M1R.

1. `RiskTree` and `TreeIndex` are the only two aggregate types with **public
   primary constructors**. Every invariant their `fromNodes` smart constructors
   validate is bypassable via `apply` and `.copy`. `RiskResultGroup` is
   `private` but not `final`, inconsistent with the other aggregates.
2. Several tree-level collections have **no upper bound** — a construction path
   can build an arbitrarily large tree, and one unbounded field
   (`percentiles`/`quantiles`) can drive `terms` past the metalog fitter's hard
   cap of 20, which throws `IllegalArgumentException` past the boundary.
3. The HTTP request body-size limit is **implicit and wrong-sized**. zio-http
   3.7.4's `Server.Config.default` sets `requestStreaming = Disabled(102400)` —
   a 100 KiB cap. The server uses `Server.Config.default` unchanged, so the cap
   is undocumented, unconfigurable, and (if it binds through the tapir
   interpreter) rejects valid large trees; a maximal 10,000-node expert-leaf
   tree serializes to ≈ 4.94 MiB, far above 100 KiB.

Three levers implement the fixes. Lever 2 (Iron `MaxLength` collection types) is
**deferred** to TODO #46 by prior ruling; its runtime enforcement is provided
here by Lever 1's validators.

---

## Correction to the §9 sketch (spec-vs-code, surfaced at elevation)

PLAN-RISKTRANSFORM §9's motivation bullet 2 states "No HTTP request body-size
limit is configured (none found in `modules/server/src/main`)". That search
looked for an explicit setting and missed the zio-http default:
`Server.Config.default.requestStreaming = RequestStreaming.Disabled(102400)`
(verified against `zio-http_3-3.7.4.jar`). The server therefore already has an
implicit 100 KiB cap. This changes Lever 3's framing from "add a missing limit"
to "replace the implicit, wrong-sized, unconfigurable cap with an explicit,
documented, configurable one," and raises a latent-bug question:

> **Open question O-1 (verified at implementation, not a blocker to approval):**
> does the zio-http `requestStreaming` cap bind through the tapir
> `ZioHttpInterpreter` routes? If it does, valid trees above 100 KiB are
> rejected today (existing demo trees are far smaller, so no test would have
> caught it). The Lever 3 integration test (below) answers this directly and is
> the regression test either way.

§9's sketch text is design-stage, not an approved specification; this elevation
supersedes it. The doc-consistency sweep updates the §9 sketch to point here and
corrects the "no limit configured" wording.

---

## Levers and exact signatures

### Lever 1 — Close the aggregate constructors

Make `RiskTree` and `TreeIndex` primary constructors `private`, and add `final`
to `RiskResultGroup`. Verified: Scala 3 makes `.copy` private under a private
primary constructor (`E173`), so `fromNodes` / `fromNodesUnsafe` become the sole
gates at every site — `apply`, `.copy`, builders, merges.

`modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`
— add `private` (line 33); body unchanged:

```scala
final case class RiskTree private (
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex,
  seedVarHighWater: SeedVarId.SeedVarId,
  mitigations: Seq[Mitigation] = Nil
)
```

`modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala`
— add `private` (line 45); body unchanged:

```scala
final case class TreeIndex private (
    nodes: Map[NodeId, RiskNode],
    parents: Map[NodeId, NodeId],
    children: Map[NodeId, List[NodeId]]
)
```

`modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`
— add `final` (line 219):

```scala
final case class RiskResultGroup private (
  children: List[LossDistribution],
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes)
```

**Serialization is insulated** (verified): `RiskTree`'s tapir `Schema` and
zio-json codec derive over the separate `RiskTreeJson` DTO (public constructor)
and map through `fromNodes`; the private `RiskTree` constructor is used only
inside the companion (line 153). `TreeIndex` is not serialized. Both `fromNodes`
bodies and `TreeIndex.empty` construct from inside the companion, so they still
compile. The compile step is the verification; if a derivation unexpectedly
requires the public constructor, that is a Decision Trigger (halt), not a silent
switch to `Schema.any`.

**Construction-site reroute.** Direct positional constructions and `.copy` on
these types move to `fromNodesUnsafe` (index rebuilt from nodes; pass
`Some(t.seedVarHighWater)`):

- `RiskTree(...)` positional — test sites: `RiskTreeKnowledgeBaseSpec` (2),
  `BinderIntegrationSpec` (2), `MitigationStalenessSpec`,
  `MitigationScopeResolverSpec`, `RiskTreeRepositoryIrminSpec` (3),
  `TreeRevertItSpec` (2), `PinnedReadAuthorizationItSpec`,
  `IrminRevertSemanticsSpec` (2), `TreeBuilderStateSpec`.
- `t.copy(id = …)` — `WorkspaceLifecycleControllerSpec:65`,
  `RiskTreeControllerSpec:35` → `RiskTree.fromNodesUnsafe(id, t.name, t.nodes,
  t.rootId, Some(t.seedVarHighWater), t.mitigations)`.

### Lever "collection bounds" — runtime validators (Lever 2 deferred to #46)

Bound all currently-unbounded tree-level collections as runtime validators in
the owning smart constructor (ruled values: nodes ≤ 10 000; childIds ≤ 1 000;
percentiles/quantiles ≤ 20; mitigations = 1 000 and steps = 10 already set).

`RiskTree.scala` — new node-count and node-name-uniqueness validators, wired
into the first `validateWith` in `fromNodes` (alongside `TreeIndex.fromNodeSeq`,
`requireDistinctSeedVarIds`, `resolveSeedVarHighWater`):

```scala
private val MaxNodes = 10000

private def validateNodeCount(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] =
  if (nodes.sizeIs <= MaxNodes) Validation.succeed(())
  else Validation.fail(ValidationError(
    field = "nodes",
    code = ValidationErrorCode.CONSTRAINT_VIOLATION,
    message = s"too many nodes: ${nodes.size} exceeds the limit of $MaxNodes"
  ))

/** Node names are unique across the whole tree. The request boundary
  * (RiskTreeRequests.requireUniqueNames) enforces the same rule on the write
  * paths; folding it into fromNodes makes it hold for merges, store-loads, and
  * programmatic construction too (correct-by-construction layering, matching
  * requireDistinctSeedVarIds). */
private def requireDistinctNodeNames(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] = {
  val dups = nodes.groupBy(_.name.value).collect { case (n, ns) if ns.sizeIs > 1 => n }
  if (dups.isEmpty) Validation.succeed(())
  else Validation.fail(ValidationError(
    field = "nodes.name",
    code = ValidationErrorCode.AMBIGUOUS_REFERENCE,
    message = s"duplicate node name(s): ${dups.toList.sorted.mkString(", ")}"
  ))
}
```

This is the D4 node-name-uniqueness fold-in. It satisfies the forward reference
in `RiskTreeKnowledgeBase.scala:107,128` ("duplicate names are last-write-wins
until `RiskTree.fromNodes` enforces node-name uniqueness"); that comment is
updated to state the current rule (uniqueness enforced in `fromNodes`) — see the
doc-consistency sweep.

`RiskNode.scala` — `RiskPortfolio.create` childIds validation (line 553–562)
gains a count bound (keeping the existing non-empty rule, accumulated):

```scala
private val MaxChildren = 1000

// childIdsValidation becomes non-empty AND count-bounded, accumulated:
val arr = Option(childIds).getOrElse(Array.empty[NodeId])
val nonEmptyV = Validation.fromPredicateWith[ValidationError, Array[NodeId]](
  ValidationError(s"$fieldPrefix.childIds", ValidationErrorCode.REQUIRED_FIELD,
    "childIds array must not be empty"))(arr)(_.nonEmpty)
val countV = Validation.fromPredicateWith[ValidationError, Array[NodeId]](
  ValidationError(s"$fieldPrefix.childIds", ValidationErrorCode.CONSTRAINT_VIOLATION,
    s"too many children: ${arr.length} exceeds the limit of $MaxChildren"))(arr)(_.length <= MaxChildren)
val childIdsValidation = Validation.validateWith(nonEmptyV, countV)((_, _) => arr)
```

`createFromStrings` must route through `create` (verify at implementation) so
the bound applies once; if it constructs `new RiskPortfolio` directly, that is a
Decision Trigger (halt) — do not duplicate the bound silently.

`RiskNode.scala` — `validateExpertMode` (line 246–256) gains an upper bound on
the point count, in the equal-length branch:

```scala
private val MaxPercentiles = 20  // matches metalog MAX_TERMS

case (Some(p), Some(q)) if p.nonEmpty && q.nonEmpty =>
  Validation
    .fromPredicateWith[ValidationError, (Array[Double], Array[Double])](
      ValidationError(s"$fieldPrefix.distributionType", ValidationErrorCode.INVALID_COMBINATION,
        s"Expert mode: percentiles and quantiles must have same length (got ${p.length} vs ${q.length})")
    )((p, q)) { case (pa, qa) => pa.length == qa.length }
    .flatMap(_ => Validation.fromPredicateWith[ValidationError, Array[Double]](
      ValidationError(s"$fieldPrefix.percentiles", ValidationErrorCode.CONSTRAINT_VIOLATION,
        s"Expert mode: at most $MaxPercentiles percentile/quantile points (got ${p.length})")
    )(p)(_.length <= MaxPercentiles))
    .as((None, None))
```

Because `requireTermsWithinPercentiles` already enforces `terms ≤
percentiles.length`, bounding the point count at 20 transitively closes the
`terms > 20` metalog crash — no separate `terms` cap is needed. The
`validateModeFields` path (line 396, `OverrideDistributionParams`) calls the same
`validateExpertMode`, so it inherits the bound.

### Lever 3 — Explicit, configurable request body-size limit

`modules/server/src/main/scala/com/risquanter/register/configs/ServerConfig.scala`
— add `maxRequestBytes`:

```scala
final case class ServerConfig(
  host: String,
  port: Int,
  healthPort: Int,
  maxRequestBytes: Int
)
```

`modules/server/src/main/resources/application.conf` — add under `server` (8 MiB
default, env-overridable, matching the `healthPort` style):

```hocon
    maxRequestBytes = 8388608
    maxRequestBytes = ${?REGISTER_MAX_REQUEST_BYTES}
```

`modules/server/src/main/scala/com/risquanter/register/Application.scala` — the
`Server.Config` layer (line 269–273) sets the limit explicitly
(`zio.http.Server` is already in scope):

```scala
ZLayer.fromZIO(
  ZIO.service[ServerConfig].map(cfg =>
    Server.Config.default
      .binding(cfg.host, cfg.port)
      .requestStreaming(Server.RequestStreaming.Disabled(cfg.maxRequestBytes))
  )
) >>> Server.live,
```

`HealthProbeServer` (its own `Server.Config.default`) is unchanged: it serves
only `/health` and `/ready` with no request bodies, so the 100 KiB default is
correct there.

---

## ADR alignment

- **ADR-001 / ADR-010 (correct-by-construction).** Lever 1 + the validators
  strengthen these: aggregates are private-behind-smart-constructor uniformly;
  new bounds accumulate errors via `validateWith` (not `.flatMap`). This plan
  adds a new Decision pattern to ADR-001 — "Aggregate Constructors Are Private"
  — plus one BAD/GOOD Code Smell and Implementation rows, onto the post-#36
  conformant document (see the pre-implementation gate). Exact ADR-001 edit
  content:
  - Decision pattern (next sequential number): a domain aggregate is
    `final case class X private (…)` with a smart constructor `fromNodes`/`create`
    returning `Validation[ValidationError, X]`; `apply` and `.copy` are closed so
    the smart constructor is the sole gate. Reference aggregates:
    `RiskLeaf`, `RiskPortfolio`, `Mitigation`, `RiskResultGroup`, `RiskTree`,
    `TreeIndex`. Generalizes ADR-034 Decision 4 (`RiskResultGroup`) to all
    aggregates.
  - Code Smell: BAD `final case class RiskTree(...)` (public — `apply`/`.copy`
    bypass every invariant) / GOOD `final case class RiskTree private (...)` +
    `fromNodes`.
- **ADR-017 (tree API design).** Lever 3's resource limit is documented here
  (D3-A): add a resource-limits subsection stating the 8 MiB request body cap
  (env `REGISTER_MAX_REQUEST_BYTES`), the tree-level collection ceilings
  (nodes ≤ 10 000, childIds ≤ 1 000, percentiles/quantiles ≤ 20, mitigations
  ≤ 1 000), and that they are enforced at the boundary before allocation / in
  `fromNodes`.
- **ADR-029 (input/injection & DoS defence).** Lever 3 is the transport-layer
  DoS control; a body-size-limit row is added to ADR-029's boundary table
  (its home for boundary controls) — cross-referenced from ADR-017.
- **ADR-018 (nominal wrappers), ADR-022 (credential `final class`).** Unaffected;
  no ID or credential types change.
- **ADR-032 (two hash relations).** Node-name uniqueness in `fromNodes` (D4) is a
  structural invariant, independent of the content/blob hashes; no hash change.

No ADR deviation. The one ADR-001 numbering dependency is handled by the
pre-implementation gate (rides #36).

---

## Open decisions

**OD-1 — expert-mode point-count lower bound.** The metalog fitter also throws
for fewer than 2 points (`terms < 2`). `validateExpertMode` currently requires
only non-empty (≥ 1). Bounding the upper end at 20 does not close the lower-end
crash for a 1-point expert leaf.

- **Option A (recommended, mine):** raise the expert-mode minimum to 2 points in
  the same validator (`p.length >= 2`), completing the `[2, 20]` fit-validity
  range this phase already touches. A metalog needs ≥ 2 points to fit; < 2 is a
  crash today. Verify first that no existing valid data / request-layer check
  admits a 1-point expert leaf; if one exists, it is already crashing and this
  fixes it.
- **Option B:** leave the lower bound out of scope (the user's §9 ruling named
  only upper bounds); track the `terms < 2` crash as its own TODO.

This is the only open decision. It is raised, not silently decided, because it
adds a bound the §9 ruling did not name. Everything else is resolved:
body-size 8 MiB configurable (D1); percentiles/quantiles ≤ 20 (D2); resource-limit
docs → ADR-017 (D3); node-name uniqueness fold-in (D4); Lever 2 deferred → TODO
#46; `RiskResultGroup` `final` (Decision A); ADR-001 conformance → TODO #36
Option C (pre-implementation gate).

---

## Verification plan

New/updated tests:

- **Unit (commonJVM)** —
  - `RiskLeafSpec`: expert-mode > 20 points → `CONSTRAINT_VIOLATION`; boundary
    (20 → succeed, 21 → fail); OD-1 lower bound if Option A ruled.
  - `RiskPortfolioSpec`: childIds > 1 000 → `CONSTRAINT_VIOLATION`; 1 000 → succeed.
  - new `RiskTreeBoundsSpec`: nodes > 10 000 → `CONSTRAINT_VIOLATION`; duplicate
    node names → `AMBIGUOUS_REFERENCE`; both valid → succeed.
- **Scala.js (app)** — `TreeBuilderStateSpec` reroute compiles and stays green.
- **Server (server)** — rerouted construction/`.copy` sites compile and stay
  green; existing `RiskTreeKnowledgeBaseSpec` / `BinderIntegrationSpec` unaffected.
- **Integration (serverIt)** — new `RequestBodyLimitItSpec`: a request body just
  over `maxRequestBytes` → 413 (or connection-level rejection); just under → 200.
  Answers O-1 and is the Lever 3 regression test.

Commands (all must be green; report pass/fail only):

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
# clear leaked networks first, then:
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f; docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm
sbt "serverIt/test"        # needs local/irmin-prod:3.11-p1
```

Plus BATS suite-C fast gate after the change (`register-dev` skill).

---

## Doc-consistency sweep (part of landing)

- PLAN-RISKTRANSFORM.md §9: mark elevated → point here; correct the "no
  body-size limit configured" wording to the verified 100 KiB implicit default.
- `RiskTreeKnowledgeBase.scala:107,128`: update the forward-reference comment to
  state node-name uniqueness is enforced in `fromNodes` (no longer
  "last-write-wins until…").
- TODO #46: note the runtime bounds landed here (its deferral rationale already
  references them).
- ADR-017 / ADR-029 / ADR-001: the edits listed under ADR alignment.

---

## File inventory

- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`
- `modules/server/src/main/scala/com/risquanter/register/configs/ServerConfig.scala`
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/resources/application.conf`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskLeafSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskPortfolioSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskTreeBoundsSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationScopeResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/RequestBodyLimitItSpec.scala`
- `modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala`
- `build.sbt`

Docs edited (not hook-gated; listed for completeness):

- `docs/dev/decision-records/ADR-001.md` (new Decision pattern + Code Smell +
  Implementation rows, onto the post-#36 document)
- `docs/dev/decision-records/ADR-017-tree-api-design.md` (resource-limits subsection)
- `docs/dev/decision-records/ADR-029-input-injection-defence.md` (body-size row)
- `docs/dev/plans/PLAN-RISKTRANSFORM.md` (§9 → elevated pointer + wording fix)
- `docs/dev/TODO.md` (#46 status note)
