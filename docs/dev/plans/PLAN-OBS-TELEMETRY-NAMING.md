# PLAN-OBS-TELEMETRY-NAMING

Plan code `OBS`. Decisions in this document are labelled `OBS-D-<n>`.

**Status.** Written 2026-11-11 at version 0.18.19. Four decisions are open
(section 4). The plan authorizes nothing until they are ruled and the user
creates `docs/dev/plans/PLAN-OBS-TELEMETRY-NAMING-INVENTORY.md`, which does not
exist yet. The signatures in section 5 are written for the recommended option of
each decision. Section 5 states what changes under the other option.

**Origin.** ADR-028's appendix §9 specifies telemetry the query service does not
emit. Settling §9 raised the question of whether the project has one telemetry
naming convention. It has one for metrics and none for spans (section 2).
`docs/dev/TODO.md` item 50 records that no decision record states the metric
convention. This plan closes item 50.

## 1. What this plan is for

OpenTelemetry (OTel) is the observability standard the server exports to. It has
two signals this plan concerns. A **span** records one operation of one request:
a name, a duration, and attributes, which are key–value pairs. A **metric** is a
number aggregated across requests: a counter, or a histogram of a measured
value. Each recording of a metric carries labels, and every distinct combination
of label values is stored as its own time series.

The plan does three things:

- writes one naming convention for span names, span attribute keys, metric
  names and metric labels into a new decision record, ADR-040;
- brings every span and metric in `modules/server/src/main` to that convention;
- gives the query service the metrics ADR-028's appendix §9 specifies, and
  rewrites §9 to state what the service emits.

## 2. The current state

### 2.1 Metrics follow one pattern

Three services create metric instruments. Each declares the names in a private
`MetricNames` object in its companion, and creates the instruments once, when
its layer is built. `AuthorizationServiceSpiceDB.scala`:

```scala
private object MetricNames:
  val checkCounter     = "authz.check.total"
  val checkCounterUnit = "1"
  val checkCounterDesc = "Authorization check results"
  val checkLatency     = "authz.check.latency_ms"
  val checkLatencyUnit = "ms"
  val checkLatencyDesc = "SpiceDB authorization check latency in milliseconds"
```

The names are dotted and lowercase, with the unit as a suffix. Every label value
comes from a closed set, with one exception. `CachedResultResolverLive.scala`
labels its simulation metrics with the node's identifier:

```scala
val attrs = Attributes(Attribute.string("node_name", nodeId.value))
```

A tree holds any number of nodes, so this label creates one time series per node
ever simulated. Its key also says `name` while its value is an identifier.

### 2.2 Spans follow four patterns

| File | Span names | Pattern |
|---|---|---|
| `AuthorizationServiceSpiceDB.scala` | `spicedb.check` | dotted lowercase, named after the peer |
| `QueryServiceLive.scala` | `QueryService.evaluate` | Scala trait, then method |
| `RiskTreeServiceLive.scala` | `create`, `update`, `delete`, `revert`, `getById`, `probOfExceedance`, `getLECCurvesMulti` | bare operation label |
| `CachedResultResolverLive.scala` | `ensureCached`, `simulateLeaf` | bare method name |

`RequestSpanInterceptor.scala` names its server span `"<METHOD> <route family>"`.
That is the OTel semantic convention for an HTTP server span, and this plan does
not change it.

The bare names do not say which service recorded them. A trace search for
`create` cannot tell a tree creation from any other operation named `create`.

### 2.3 Attribute keys follow two patterns

`QueryServiceLive.scala` prefixes every key with `query.`:
`query.tree_id`, `query.selections`, `query.text`, `query.range_size`,
`query.satisfying_count`, `query.satisfied`, `query.proportion`.

The other three services use bare snake_case keys: `tree_id`, `node_id`,
`node_ids`, `node_count`, `threshold`, `include_provenance`, `cache_resolved`,
`exceedance_probability`, `results_resolved`, `curves_generated`, `n_trials`,
`permission`, `resource_type`, `resource_id`.

So one tree's identifier is recorded under `query.tree_id` in one span and
`tree_id` in another, and a search on either key misses the other spans.

### 2.4 What §9 specifies and what the query service emits

ADR-028's appendix §9 specifies a span `query.evaluate` with the attributes
`query.text`, `query.range_size`, `query.sample_size`, `query.proportion`,
`query.satisfied` and `query.duration_ms`. It also specifies three metrics:

| Metric | Type | Labels |
|---|---|---|
| `query.evaluate.duration` | Histogram | `workspace`, `satisfied` |
| `query.evaluate.count` | Counter | `workspace`, `status` |
| `query.range.size` | Histogram | `workspace` |

The service emits the span `QueryService.evaluate` and no metrics.
`query.sample_size` equals `query.range_size`, because the service always
evaluates with `SamplingParams.exact`. `query.duration_ms` repeats the span's own
duration. The `workspace` label would put the workspace identifier into an
exported metric, which ADR-036 forbids, and would create one series per
workspace.

## 3. ADR alignment

- **ADR-002 (logging strategy).** §1 assigns spans and metrics to OTel and is
  compliant. Its code-smell example writes `tracing.span("computeLEC", ...)` and
  `tracing.setAttribute("risk_tree.id", id)`, which the convention contradicts.
  This plan rewrites that example to the ADR-040 form, and §1 points to ADR-040.
- **ADR-028 appendix §9.** Rewritten by this plan (section 5.6).
- **ADR-036 (confidential internal identifiers).** Compliant. No span attribute
  or metric label carries a workspace, organization or team identifier.
  `AuthorizationServiceSpiceDB` keeps its match that records `authz.resource_id`
  only for a tree.
- **ADR-037 (in-code constants).** Compliant. Span names, attribute keys, metric
  names and label values are matched exactly by trace backends and dashboards,
  so under §2 their spellings are declared rather than derived from Scala
  names. A key used by several services lives in one shared object (§3,
  "shared").
- **ADR-016 (configuration).** No setting is added. A telemetry name is a
  contract, not a choice an operator makes.
- **ADR-040 (new).** Written by this plan. The `adr-constraints` skill gains its
  Layer 2 row and an entry in the Layer 1 activity for services, as its
  "Amending the corpus" section requires.

## 4. Open decisions

### OBS-D-1 — how a span is named

**Goal.** The four span-name patterns (section 2.2) become one. The name is what
a person types into a trace search, so it has to say which service recorded the
span and which operation it was.

**Option A — the service's metric prefix, then the operation, both in
snake_case.** The spans become `authz.check`, `query.evaluate`,
`risk_tree.create`, `risk_tree.get_lec_curves_multi`,
`risk_result.ensure_cached` and `risk_result.simulate_leaf`. A service's spans
and its metrics then begin with the same word.

- Pro: one search on `risk_tree.` finds both the spans and the metrics of the
  tree service.
- Pro: a Scala rename cannot rename a span, because no part of the name is a
  Scala identifier.
- Con: `RiskTreeServiceLive`'s operation labels are reused as the metric label
  `operation`, and they are camelCase today. Under this option they become
  snake_case too (`get_by_id`, `prob_of_exceedance`, `get_lec_curves_multi`),
  which changes three metric label values. No dashboard or alert in this
  repository reads them.
- Example: you open the trace of a slow `POST` on a tree. Under it you see
  `risk_tree.create`, then `risk_result.ensure_cached`, then several
  `risk_result.simulate_leaf`. You search the metrics for `risk_result.` and
  find the simulation histogram for the same service.

**Option B — the Scala trait name, then the method name.** The spans become
`AuthorizationService.check`, `QueryService.evaluate`, `RiskTreeService.create`,
`CachedResultResolver.ensureCached` and `CachedResultResolver.simulateLeaf`.

- Pro: the span name leads straight to the code: search the source for the
  trait and the method.
- Con: span names and metric names use different words for the same service
  (`CachedResultResolver` against `risk_result`).
- Con: the name reads as derived from code. A rename of the trait invites
  renaming the span, which silently breaks every saved trace query.
- Example: you see `CachedResultResolver.simulateLeaf` in a trace and open
  `CachedResultResolver.scala` directly. To find its duration metric you need to
  know that it is called `risk_result.simulation.duration_ms`.

**My recommendation: A.** Telemetry names are matched by systems outside the
code, and ADR-037 §2 keeps such spellings independent of Scala names. A also
gives spans and metrics one prefix per service. This holds as long as each
service keeps one metric prefix.

### OBS-D-2 — how a span attribute key is named

**Goal.** The two key patterns (section 2.3) become one, so that one concept has
one key in every span.

**Option A — bare snake_case keys everywhere.** The query keys lose their
prefix: `tree_id`, `selection_count`, `query_text`, `range_size`,
`satisfying_count`, `satisfied`, `proportion`. All other keys stay as they are.

- Pro: the smallest change. Three of four services already use it.
- Con: a bare key names no subject. `threshold`, `satisfied` and `proportion`
  mean something only next to the span name.
- Example: you search all spans where `tree_id = 01J…`. You get the query span,
  the tree-service span and the resolver span for that tree.

**Option B — each key is namespaced by the concept it describes, not by the span
that records it.** `tree.id` and `node.id` are the same key in every span.
Measurements of one operation take that operation's namespace.

| Today | Becomes |
|---|---|
| `tree_id`, `query.tree_id` | `tree.id` |
| `node_id` | `node.id` |
| `node_ids`, `node_count` | `node.ids`, `node.count` |
| `include_provenance` | `provenance.included` |
| `threshold`, `exceedance_probability`, `curves_generated` | `lec.threshold`, `lec.exceedance_probability`, `lec.curve_count` |
| `cache_resolved`, `results_resolved` | `cache.resolved`, `cache.result_count` |
| `n_trials` | `simulation.trials` |
| `permission`, `resource_type`, `resource_id` | `authz.permission`, `authz.resource_type`, `authz.resource_id` |
| `query.selections` | `query.selection_count` |
| `query.text`, `query.range_size`, `query.satisfying_count`, `query.satisfied`, `query.proportion` | unchanged |

- Pro: every key says what it describes, wherever it appears.
- Pro: this is the form OTel's own semantic conventions use
  (`http.request.method`).
- Con: every key in three services changes, and saved trace searches on the old
  keys stop matching.
- Example: you search all spans where `tree.id = 01J…`. You get the same three
  spans as under A. A span's attribute list also reads on its own:
  `lec.threshold = 5000000` needs no span name to be understood.

**My recommendation: B.** The key is the part of a trace that is read without
its span, in a search or an attribute list. This holds while no trace backend
in use has saved searches on the old keys. The repository holds none.

### OBS-D-3 — what replaces the `node_name` metric label

**Goal.** `risk_result.simulation.duration_ms` and
`risk_result.simulation.trials` carry `node_name`, set to the node's identifier
(section 2.1). One series per node grows without bound. The label value does
carry information: which node was simulated.

**Option A — replace it with the leaf's distribution, a closed set.** The label
becomes `distribution`, with the value `metalog` or `lognormal` read from the
leaf's `DistributionShape`. Per-node timing stays available on the
`risk_result.simulate_leaf` span, which records `node.id`.

- Pro: the metric answers a question no span answers in aggregate: whether
  metalog sampling is slower than lognormal sampling.
- Pro: two series per metric, whatever the number of trees.
- Con: a dashboard cannot plot one node's duration over time. A trace search
  for that node's spans is needed instead.
- Example: the simulation duration histogram shows `metalog` with a 95th
  percentile of 400 ms and `lognormal` at 40 ms. To find the slowest node, you
  search spans named `risk_result.simulate_leaf` sorted by duration.

**Option B — remove the label.** The two metrics carry no labels.

- Pro: the smallest change.
- Con: the metric loses its one breakdown, and gains nothing in exchange.
- Example: the histogram shows one 95th percentile for all simulations.

**My recommendation: A.** It keeps a breakdown the label was there to give, at a
bounded cost. This holds while `DistributionShape` stays a closed set. Its
`sealed trait` makes adding a variant a compile error at the label's `match`.

### OBS-D-4 — how the emitted names are tested

**Goal.** No test reads back a span or a metric today.
`CachedResultResolverSpec.scala` says so:

```scala
// Telemetry is recorded through the layer this suite provides, which
// exports to the process's own output rather than to a collector this
// test can read back, so nothing asserts on the spans or metrics.
```

This plan's whole content is names that outside systems match exactly, so a
misspelling would pass every test.

**Option A — add `io.opentelemetry:opentelemetry-sdk-testing` at test scope.**
It provides `InMemorySpanExporter` and `InMemoryMetricReader`, which keep what
was recorded in memory for a test to read. A test layer builds the SDK with
them, the same way `TelemetryLive.scala` builds it with
`SdkMeterProvider.builder()`. Tests then assert each span name, attribute key,
metric name and label set.

- Pro: a renamed or misspelled name fails a test.
- Con: one new test dependency, which falls under the `supply-chain` skill.
- Unverified premise: that version 1.57.0 of the artifact exists and passes the
  14-day cooldown. It is the same release as the `opentelemetry-sdk` 1.57.0
  already in `build.sbt`, so it is very likely, but the check is the
  `supply-chain` skill's procedure and has not been run.
- Example: a later edit changes `"query.evaluate.total"` to
  `"query.evaluations.total"`. `sbt server/test` fails on the metric-name
  assertion.

**Option B — test only the pure functions.** Tests cover the functions that
compute a label value (`QueryOutcome.of`, the distribution label), and the
existing `assertCompletes` tests keep checking that recording does not fail.

- Pro: no new dependency.
- Con: the names themselves stay untested, which is the defect this plan exists
  to remove.
- Example: the same rename as above passes every test.

**My recommendation: A**, once the supply-chain check has run. Ranking is
provisional until then.

## 5. Target design

Written for A on OBS-D-1, B on OBS-D-2, A on OBS-D-3 and A on OBS-D-4.

### 5.1 ADR-040 — Telemetry naming

New file `docs/dev/decision-records/ADR-040-telemetry-naming.md`, in the
`ADR-00X.md` template. Its decision states:

1. **A span is named `<prefix>.<operation>`**, lowercase snake_case, where
   `<prefix>` is the service's metric prefix. The HTTP server span keeps the
   OTel convention `"<METHOD> <route>"`.
2. **A span attribute key is `<concept>.<property>`**, namespaced by what it
   describes. A key used by more than one service is declared once in
   `SpanAttributeKeys`.
3. **A metric is named `<prefix>.<area>.<measurement>`**, with the unit as a
   suffix where it disambiguates. Each instrument is declared with a unit and a
   description in the service's private `MetricNames` object, and created once
   when the layer is built.
4. **Every metric label value comes from a closed set**, declared as an enum
   with explicit spellings. An identifier is never a label value. It belongs on
   a span.
5. **No span attribute or metric label carries a workspace, organization or
   team identifier** (ADR-036).

Its implementation table lists the four services and `SpanAttributeKeys` as the
reference examples.

### 5.2 Shared attribute keys

New file
`modules/server/src/main/scala/com/risquanter/register/telemetry/SpanAttributeKeys.scala`.

Family search: the shared-constant family is `IronConstants` in
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`
(`val One: PositiveInt = 1`). ADR-037 §3 places a constant used by several types
in a shared object of its kind. No existing object holds telemetry keys. The
`telemetry` package holds `TelemetryLive` and `ActiveTrace`, so the object joins
that package.

```scala
package com.risquanter.register.telemetry

/** Span attribute keys used by more than one service (ADR-040). Trace searches
  * match these spellings exactly. */
object SpanAttributeKeys:
  val TreeId: String            = "tree.id"
  val NodeId: String            = "node.id"
  val ProvenanceIncluded: String = "provenance.included"
```

`String` is the type `Tracing.setAttribute` takes for its key. A key is not a
domain value, and no refined type denotes it.

A key used by one service only is a `private object SpanAttributes` in that
service's companion, beside its `MetricNames`.

### 5.3 Query service

`QueryServiceLive.scala`. The constructor gains the three instruments:

```scala
class QueryServiceLive private (
  repo:               RiskTreeRepository,
  resolver:           CachedResultResolver,
  scopeResolvers:     MitigationScopeResolverRegistry,
  tracing:            Tracing,
  evaluations:        Counter[Long],
  evaluationDuration: Histogram[Double],
  rangeSize:          Histogram[Double]
) extends QueryService
```

The outcome label is a closed set. Family search: `RiskTreeServiceLive.scala`'s
`private enum Operation(val label: String)` (`case Create extends
Operation("create")`) is the existing label enum, and its pattern is a declared
`label` per case. `AuthorizationServiceSpiceDB.scala`'s `result` label uses the
same three-way split as a `match` over strings (`"allowed"`, `"denied"`,
`"error"`). The new enum follows `Operation`'s pattern:

```scala
/** The outcome the evaluation counter and histogram label (ADR-040). */
private[services] enum QueryOutcome(val label: String):
  case Answered extends QueryOutcome("answered")
  case Refused  extends QueryOutcome("refused")
  case Failed   extends QueryOutcome("failed")

private[services] object QueryOutcome:
  /** A caller's refused query is `Refused`. A failure the service caused, a
    * defect and an interruption are `Failed`. */
  def of(exit: Exit[AppError | Failure, QueryResponse]): QueryOutcome
```

`of` reads `FailureOrigin`, which both `Failure.origin` and `AppError.origin`
already derive (ADR-002). It does not decode the `QueryProblem`, because the
caller's problem kind is in the response and the log line, and the label stays a
three-value set.

`private[services]` makes `of` reachable from `QueryServiceLiveSpec`, which is
in the same package.

The span and the metrics:

```scala
private def traced[A](name: String)(body: IO[AppError | Failure, A]): IO[AppError | Failure, A] =
  tracing.span(s"${MetricNames.Prefix}.$name", SpanKind.INTERNAL)(body)

private def recordEvaluation(outcome: QueryOutcome, durationMs: Long): UIO[Unit]

private def recordRangeSize(size: Int): UIO[Unit]
```

`evaluate` measures the time around its body and records on exit, so a defect
and an interruption are counted:

```scala
override def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, seedEntityId: SeedEntityId.SeedEntityId, branch: BranchRef)(using Checked[Authority]): IO[AppError | Failure, QueryResponse] =
  traced("evaluate") {
    Clock.currentTime(TimeUnit.MILLISECONDS).flatMap { start =>
      evaluateBody(wsId, treeId, parsed, seedEntityId, branch).onExit { exit =>
        Clock.currentTime(TimeUnit.MILLISECONDS)
          .flatMap(end => recordEvaluation(QueryOutcome.of(exit), end - start))
      }
    }
  }
```

`evaluateBody` is the current body of `evaluate`, unchanged except for the keys
and one `recordRangeSize(output.rangeElements.size)` after evaluation. Its
attribute keys become `tree.id`, `query.selection_count`, `query.text`,
`query.range_size`, `query.satisfying_count`, `query.satisfied` and
`query.proportion`.

The companion:

```scala
private object MetricNames:
  val Prefix                 = "query"
  val EvaluationCounter      = "query.evaluate.total"
  val EvaluationCounterUnit  = "1"
  val EvaluationCounterDesc  = "Query evaluations by outcome"
  val EvaluationDuration     = "query.evaluate.duration_ms"
  val EvaluationDurationUnit = "ms"
  val EvaluationDurationDesc = "Query evaluation duration in milliseconds"
  val RangeSize              = "query.evaluate.range_size"
  val RangeSizeUnit          = "1"
  val RangeSizeDesc          = "Number of elements in an answered query's range"

private object MetricLabels:
  val Outcome = "outcome"

val layer: ZLayer[RiskTreeRepository & CachedResultResolver & MitigationScopeResolverRegistry & Tracing & Meter, Throwable, QueryService]
```

The error type widens from `Nothing` to `Throwable`, matching the three layers
that already create instruments.

The units follow the existing instruments: `"1"` for a count, `"ms"` for a
duration. The range-size histogram has no label, because only an answered query
has a range.

`Meter` is already provided by `TelemetryLive.configured` in `Application.scala`
and in every test harness that builds `QueryServiceLive.layer`, so no wiring
changes. Every harness builds it inside a layer whose error type is already
`Throwable`.

### 5.4 Tree service

`RiskTreeServiceLive.scala`. The labels become snake_case and the span name gains
the prefix:

```scala
private enum Operation(val label: String):
  case Create            extends Operation("create")
  case Update            extends Operation("update")
  case Delete            extends Operation("delete")
  case Revert            extends Operation("revert")
  case GetById           extends Operation("get_by_id")
  case ProbOfExceedance  extends Operation("prob_of_exceedance")
  case GetLECCurvesMulti extends Operation("get_lec_curves_multi")

private def traced[A](operation: Operation)(body: IO[AppError | Failure, A]): IO[AppError | Failure, A] =
  tracing.span(s"${MetricNames.Prefix}.${operation.label}", SpanKind.INTERNAL)(body).tapBoth(...)
```

`MetricNames` gains `val Prefix = "risk_tree"`. Attribute keys follow the OBS-D-2
table, using `SpanAttributeKeys` for `tree.id`, `node.id` and
`provenance.included`, and a private `SpanAttributes` object for the `lec.`,
`cache.` and `node.` keys.

### 5.5 Resolver and authorization service

`CachedResultResolverLive.scala`. The spans become `risk_result.ensure_cached`
and `risk_result.simulate_leaf`, and the keys follow the OBS-D-2 table. The
simulation metrics take the distribution label:

```scala
/** The distribution the simulation metrics label (ADR-040). */
private enum DistributionLabel(val label: String):
  case Metalog   extends DistributionLabel("metalog")
  case Lognormal extends DistributionLabel("lognormal")

private object DistributionLabel:
  def of(shape: DistributionShape): DistributionLabel = shape match
    case _: DistributionShape.Metalog   => Metalog
    case _: DistributionShape.Lognormal => Lognormal

private def recordSimulationMetrics(shape: DistributionShape, nTrials: PositiveInt, durationMs: Long): UIO[Unit]
```

Family search: no existing enum in `modules/server` labels a distribution.
`DistributionType` in `OpaqueTypes.scala`
(`type DistributionType = String :| DistributionTypeConstraint`) holds
`"Metalog"` and `"Lognormal"`, but its spelling is a wire contract, and ADR-037
§2 keeps a second contract's spelling independent of it. The enum follows
`Operation`'s pattern.

The call site passes `leaf.shape` where it passes `leaf.id` today.

`AuthorizationServiceSpiceDB.scala`. The span becomes `authz.check`, keeping
`SpanKind.CLIENT`. The keys become `authz.permission`, `authz.resource_type` and
`authz.resource_id`. The match that records `authz.resource_id` only for a tree
is kept. `MetricNames` gains `val Prefix = "authz"`.

### 5.6 ADR-028 appendix §9

§9 is replaced by:

````markdown
## 9. Observability

The query service records one span and three metrics, named under ADR-040.

### Span

```
Span: query.evaluate
Attributes:
  tree.id                = "01J…"
  query.selection_count  = 1
  query.text             = "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, \"inherent\"), 5000000))"
  query.range_size       = 25
  query.satisfying_count = 18
  query.satisfied        = true
  query.proportion       = 0.72
```

The span's own duration is the evaluation time. The range is always enumerated
in full, so the number of elements sampled equals `query.range_size`.

### Metrics

| Metric | Type | Labels |
|---|---|---|
| `query.evaluate.total` | Counter | `outcome` |
| `query.evaluate.duration_ms` | Histogram | `outcome` |
| `query.evaluate.range_size` | Histogram | none |

`outcome` is `answered`, `refused` for a query the caller must change, or
`failed` for a failure the service caused. No metric carries a workspace label
(ADR-036).
````

### 5.7 Under the other options

- **OBS-D-1 B.** Span names are the trait and method (section 4). The
  `Operation` labels stay camelCase. `MetricNames.Prefix` is not added.
- **OBS-D-2 A.** Keys are bare (section 4). `SpanAttributeKeys` holds
  `tree_id`, `node_id` and `include_provenance`.
- **OBS-D-3 B.** `DistributionLabel` is not added, and `recordSimulationMetrics`
  records with `Attributes.empty`.
- **OBS-D-4 B.** No test layer and no `build.sbt` change. Section 7's
  name-assertion tests are replaced by tests of `QueryOutcome.of` and
  `DistributionLabel.of`.

## 6. File inventory

The inventory document is `docs/dev/plans/PLAN-OBS-TELEMETRY-NAMING-INVENTORY.md`.
It does not exist yet, and the user creates it. Under the recommended options it
names `build.sbt`, `Application.scala` (its layer comment), the four services,
`SpanAttributeKeys.scala` (new), the test telemetry layer
`modules/server/src/test/scala/com/risquanter/register/telemetry/InMemoryTelemetry.scala`
(new), and the four services' specs.

Documents edited without the gate: ADR-002, ADR-028's appendix, ADR-040 (new),
`.claude/skills/adr-constraints/SKILL.md`, and `docs/dev/TODO.md` (item 50
removed).

Not touched: `RequestSpanInterceptor.scala`, whose span follows the OTel HTTP
convention, and the three test harnesses that build `QueryServiceLive.layer`,
which already provide a `Meter`.

## 7. Verification

New tests, all in `sbt server/test`:

- `QueryServiceLiveSpec`: an answered query records span `query.evaluate` with
  every key in section 5.6, and records `query.evaluate.total` once with
  `outcome = answered`. A query naming an unknown node records
  `outcome = refused`. A tree the branch lacks records `outcome = failed`.
- `QueryServiceLiveSpec`: `QueryOutcome.of` maps a success, a `Client`-zone
  failure, a `Service`-zone failure and a defect to the four expected cases.
- `RiskTreeServiceLiveSpec`: `create` records span `risk_tree.create` with key
  `tree.id`, and `risk_tree.operations` with `operation = create`.
- `CachedResultResolverSpec`: a metalog leaf records the simulation histogram
  with `distribution = metalog`, and span `risk_result.simulate_leaf` with
  `node.id`. The comment saying nothing asserts on telemetry is removed.
- `AuthorizationServiceSpiceDBSpec`: a check records span `authz.check` with
  `authz.permission`, and no `authz.resource_id` for a workspace resource.

Commands, each expected to pass:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm
sbt serverIt/test
```

BATS suites A and C run at the end of the plan, by the user, per the
`register-dev` skill. No BATS test reads a telemetry name.

## 8. Terminology impact

None. `docs/user/TERMINOLOGY.md` defines no telemetry term.
