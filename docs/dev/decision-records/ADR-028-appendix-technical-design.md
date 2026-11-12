# ADR-028 Appendix: Technical Design — Query Pane Integration

**Parent ADR:** [ADR-028](ADR-028-vague-quantifier-query-pane.md) — Vague Quantifier Query Pane  
**Date:** 2026-03-14

---

## 1. FOL Terminology Reference

This section defines the FOL concepts used throughout the design. All
map directly to types in the vql-engine typed pipeline (`vql.typed`).

| Concept | Definition | Code type | Example |
|---|---|---|---|
| **Sort** | The kind of a value; quantifiable (a domain type) or not (a value type) | `TypeId`, declared as `TypeDecl.DomainType` / `TypeDecl.ValueType` | `Node`, `Mitigation` quantifiable; `Loss`, `Probability` not |
| **Catalog** | The declared sorts, constants, symbol signatures and per-sort literal validators | `TypeCatalog` | `p95 : (Node, Mitigation) → Loss` |
| **Term** | Expression denoting a value | `Term`, bound as `BoundTerm` | Variable `x`, literal `5000000`, function `p95(x, "inherent")` |
| **Formula** | Expression evaluating to true/false | `Formula[FOL]`, bound as `BoundFormula` | `gt_loss(p95(x, "inherent"), 5000000)`, `leaf(x)` |
| **Domain** | The elements of one quantifiable sort | `RuntimeModel.domains(sort): Set[Value]` | every `Value(Node, nodeId)` in the tree |
| **Dispatcher** | Decides each predicate and computes each function | `RuntimeDispatcher` (`MapDispatcher`) | `"p95" → unconditionalQuantile(results(sel)(id), 0.95)` |
| **Model** | Catalog paired with a runtime model, validated once | `FolModel` | The complete package for evaluating queries |

### Evaluation Trace

For query: `Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, "inherent"), 5000000))`

```
Bind against the catalog:
  x : Node                       ← inferred from leaf's signature
  p95(x, "inherent") : Loss      ← "inherent" is a Mitigation-sort constant
  5000000 : Loss                 ← Loss's literal validator parses it to 5000000L

Range: every d in domain(Node) for which the dispatcher says leaf(d)

For each d in the range, x ↦ d:
  1. p95(d, inherent)            → dispatcher: quantile of the inherent results = 7200000
  2. gt_loss(7200000, 5000000)   → dispatcher: 7200000 > 5000000 = true

Count satisfying / range size → proportion → check quantifier threshold
```

---

## 2. `RiskTreeKnowledgeBase` — KB Schema

### Structural Relations (from tree topology)

| Relation | Arity | Source | Example |
|---|---|---|---|
| `leaf(x)` | 1 | `RiskLeaf` nodes | `leaf(cyber)` |
| `portfolio(x)` | 1 | `RiskPortfolio` nodes | `portfolio(ops_risk)` |
| `child_of(x, y)` | 2 | Direct parent-child edges | `child_of(cyber, ops_risk)` |
| `descendant_of(x, y)` | 2 | Transitive closure (pre-computed) | `descendant_of(cyber, root)` |
| `leaf_descendant_of(x, y)` | 2 | Transitive, leaves only | `leaf_descendant_of(cyber, root)` |
| `eq(x, y)` | 2 | Node identity between two variables | `not eq(a, b)` |
| `named_risk(x, "name")` | 2 | Node pinned by name (`riskNameToId.get`, name-literal sort) | `named_risk(x, "IT Risk")` |
| `risk_id(x, "id")` | 2 | Node pinned by id (`NodeId.fromString`, id-literal sort) | `risk_id(x, "01BX…")` |

### Mitigation Relations (from tree mitigations + resolved scopes)

| Relation | Arity | Source | Example |
|---|---|---|---|
| `named_mitigation(m, "name")` | 2 | Mitigation pinned by name (`mitigationNameToId.get`, mitigation-name-literal sort) | `named_mitigation(m, "IT Risk mitigation")` |
| `mitigation_id(m, "id")` | 2 | Mitigation pinned by id (`MitigationId.fromString`, mitigation-id-literal sort) | `mitigation_id(m, "01H…")` |
| `mitigate(x, m)` | 2 | Node `x` is in mitigation `m`'s resolved scope | `mitigate(x, m)` |
| `mitigated(x)` | 1 | Node in the union of all resolved scopes | `mitigated(x)` |
| `unmitigated(x)` | 1 | Node in no resolved scope (complement of `mitigated`) | `unmitigated(x)` |

### Mitigation-Selection Constants (mitigation sort)

| Constant | Denotes |
|---|---|
| `inherent` | Mitigation-free (raw) valuation — `MitigationSelection.Inherent` |
| `residual` | All-applicable valuation — `MitigationSelection.Residual` |

A bound `∃m : mitigation` variable in a value function's selection slot denotes a
single-mitigation `Selected` valuation; `named_mitigation`/`mitigation_id`
constrain which mitigation.

### Simulation-Backed Functions (dispatcher functions)

The trailing `Mitigation`-sort argument selects the valuation the function reads:
the `inherent`/`residual` constants or a bound `∃m : mitigation` variable.

| Symbol | Arity | Computes | Return type |
|---|---|---|---|
| `p95(x, m)` | 2 | `LECGenerator.unconditionalQuantile(results(m)(x), 0.95)` | Long (Loss sort) |
| `p99(x, m)` | 2 | `LECGenerator.unconditionalQuantile(results(m)(x), 0.99)` | Long (Loss sort) |
| `lec(x, t, m)` | 3 | `results(m)(x).probOfExceedance(t)` | Double (Probability sort) |

A further value function is a new `ValueFunction` case, a catalog signature
and a dispatcher arm.

### Comparison Predicates (sort-tagged)

| Symbol | Arity | Sorts | Semantics |
|---|---|---|---|
| `gt_loss(a, b)` | 2 | `(Loss, Loss)` | `a > b` (Long) |
| `gt_prob(a, b)` | 2 | `(Probability, Probability)` | `a > b` (Double) |

Each comparison names the sort it compares, so the binder refuses a
comparison across sorts — `gt_loss(lec(x, 1000000, "inherent"), 0.05)`
compares a probability as a loss — before evaluation. A further comparison
follows the same naming pattern (`lt_loss`, `gte_loss`).

### Literal Resolution

A literal in a query resolves at bind time through the literal validator the
catalog declares for the sort the literal's position expects. `5000000` in a
`Loss` position parses through `s.toLongOption.filter(_ >= 0L)` to
`5000000L`. A quoted name in a `Node` position resolves through
`riskNameToId.get`. A literal no validator accepts is refused before
evaluation.

---

## 3. Tree Relations as Primitive Predicates

### Why Offer Transitive Relations as Predicates

FOL cannot express transitive closure finitely. The query
`descendant_of(x, portfolio_A)` would require:

```
child_of(x, A) ∨ ∃z.(child_of(z, A) ∧ child_of(x, z))
              ∨ ∃z1.∃z2.(child_of(z1, A) ∧ child_of(z2, z1) ∧ child_of(x, z2))
              ∨ ...   // unbounded for arbitrary depth
```

Instead, `RiskTreeKnowledgeBase` offers `descendant_of` and
`leaf_descendant_of` as primitive predicates, decided from the tree's
`TreeIndex` when the evaluator asks.

### Decision Procedure

```scala
SymbolName("descendant_of") -> { args =>
  for
    desc     <- args(0).extract[NodeId]
    ancestor <- args(1).extract[NodeId]
  // Walk desc's parent chain upward (O(depth)); isAncestor is reflexive,
  // so desc != ancestor keeps descendant_of strict.
  yield desc != ancestor && index.isAncestor(ancestor, desc)
},
SymbolName("leaf_descendant_of") -> { args =>
  for
    desc     <- args(0).extract[NodeId]
    ancestor <- args(1).extract[NodeId]
  yield desc != ancestor && index.isAncestor(ancestor, desc) && leafIdSet.contains(desc)
}
```

### Double-Counting Prevention

| Range predicate | What it iterates | Double-counting risk |
|---|---|---|
| `leaf(x)` | All leaves in tree | None — leaves are atomic units |
| `child_of(x, y)` | Direct children of y | None — single level |
| `leaf_descendant_of(x, y)` | All leaf descendants of y | None — leaves only |
| `descendant_of(x, y)` | All descendants including portfolios | **Yes** — portfolio P95 includes children's losses |

**Guidance:** Use `leaf(x)` or `leaf_descendant_of(x, y)` for loss-metric
queries. Use `descendant_of(x, y)` only for structural queries (e.g.
counting nodes, checking node types).

---

## 4. Scalability Analysis

### Model Size by Tree Size

For a tree with $N$ nodes, depth $d$ and $M$ mitigations, the runtime model
holds no materialised facts. It holds one domain element per node and per
mitigation, the tree's `TreeIndex`, the precomputed results per referenced
selection, and the resolved mitigation scopes:

| Part | Size |
|---|---|
| `Node` domain | $N$ values |
| `Mitigation` domain | $M$ values |
| Structural predicates | decided from `TreeIndex`; `child_of` reads a child list, `descendant_of` walks at most $d$ parents |
| Results | one `LossDistribution` per node per referenced selection |

### When Scale Becomes a Concern

A structural predicate costs at most $O(d)$ per call, so the model's size
grows with $N$ and $M$ alone. The quantity that grows with query shape is
the number of predicate calls: range extraction enumerates the whole `Node`
domain, and each nested quantifier multiplies the enumeration by its sort's
domain size.

### Query Evaluation Cost

Per query, the dominant costs are:

1. **Scope resolution** — `MitigationScopeResolver.resolve`, memoized per
   `(treeId, branch, commit)`
2. **Result precomputation** — `CachedResultResolver.ensureCachedAll` per
   referenced selection; content-addressed, so a cached leaf costs a lookup
3. **Range extraction** — every element of the quantified sort's domain is
   tested against the range formula; O(N × range-formula cost)
4. **Scope evaluation** — for each element in the range, the scope formula
   is evaluated; register evaluates with `SamplingParams.exact`, so every
   range element is visited
5. **Value function calls** — `unconditionalQuantile` or `probOfExceedance`
   per element

---

## 5. API Contract

### Endpoint

```
POST /w/{key}/risk-trees/{treeId}/query
```

### Request

```json
{
  "query": "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, \"inherent\"), 5000000))"
}
```

Single field. Thresholds are in the query syntax, not as separate
parameters.

### Response (200)

```json
{
  "satisfied": true,
  "proportion": 0.72,
  "rangeSize": 25,
  "sampleSize": 25,
  "satisfyingCount": 18,
  "satisfyingNodeIds": ["01HX…", "01HY…", "..."],
  "queryEcho": "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, \"inherent\"), 5000000))"
}
```

### Error Responses

A query the service refuses on its content answers 422 with
`WireError.ValidationRejected`, whose detail is `RejectionDetail.Query`
carrying one `QueryProblem`:

| `QueryProblem` case | Cause | `ValidationErrorCode` |
|---|---|---|
| `Unparseable` | The text does not parse, or its range formula breaks a variable rule. It carries one typed `ParseProblem` at its `QueryPosition` | `PARSE_ERROR` |
| `Unbound` | The text parses and does not bind against the tree's catalog. It carries every `BindProblem` the binder found | `UNKNOWN_REFERENCE` when every problem names a node the tree does not hold; `BIND_FAILED` otherwise |
| `AnswerVariablesNotSupported` | The query names answer variables | `NOT_SUPPORTED` |

The sentence the pane shows is rendered by this service from the typed fields
(ADR-035 §5).

A refusal answers 422 rather than 400 because a well-formed request body is
rejected on its content. Every other failure — a malformed body, an unknown
workspace or tree — takes the workspace endpoints' shared error shape
(ADR-010, ADR-035). Results are computed on demand, so no response asks the
caller to run a simulation first.

---

## 6. Server Components

### `RiskTreeKnowledgeBase`

Location: `server/.../foladapter/RiskTreeKnowledgeBase.scala`

`RiskTreeKnowledgeBase.schemaFor(tree)` builds the tree-derived `Schema`:
the `TypeCatalog` and the name-to-id maps its literal validators close over.
The class is constructed from that schema, the results per referenced
`MitigationSelection`, and the resolved mitigation scopes. It exposes
`catalog` and `model: RuntimeModel`, which the service pairs into a
`FolModel`.

### `QueryService`

Location: `server/.../services/QueryServiceLive.scala`

ZIO service. Single method:

```scala
def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, seedEntityId: SeedEntityId.SeedEntityId, branch: BranchRef)(using Checked[Authority]): IO[AppError | Failure, QueryResponse]
```

The text arrives already parsed: `QueryRequest.resolve` parsed it at the
request boundary. Steps:
1. Load the tree and the commit it resolved to
2. Resolve every mitigation's scope for that tree version
3. Build the schema and bind against its catalog to learn which selections
   the query references
4. Precompute one result map per referenced selection
5. Build `RiskTreeKnowledgeBase(schema, resultsBySelection, appliedScopes)`
6. Pair catalog and model as a `FolModel`; a mismatch is a wiring defect
7. Evaluate with `VagueSemantics.evaluateTyped`; a refusal becomes a
   `QueryProblem`
8. Map the output to `QueryResponse` via `QueryResponseBuilder.from`

`evaluateTyped` returns the satisfying elements as well as the estimate,
which is what `satisfyingNodeIds` in the response is read from.

### `QueryController`

Location: `server/.../http/controllers/QueryController.scala`

Wires endpoint to service. Auth check via `AuthorizationService`.

---

## 7. Frontend Components

### State Extensions (`AnalyzeQueryState`)

```
queryInput         : Var[String]
queryResult        : Var[LoadState[QueryResponse]]
satisfyingNodeIds  : Signal[Set[NodeId]]              ← derived from queryResult
isExecuting        : Signal[Boolean]                  ← derived from queryResult
```

### View Additions

| Component | Role |
|---|---|
| Query textarea (monospace) | Replaces text input, Ctrl+Enter to run |
| `QueryResultCard` | Satisfied badge, proportion bar, count, matching IDs |
| Tree highlight integration | `node-query-matched` CSS class on matching nodes |
| "View LEC" cross-link | Populates `chartNodeIds` with matching set |
| Syntax reference accordion | Operator table, available functions, example queries |

### ADR-019 Compliance

- **Pattern 1:** `QueryResultCard` is a composable function `Signal[LoadState[QueryResponse]] => HtmlElement`
- **Pattern 2:** `AnalyzeQueryState` passed as constructor parameter (existing pattern)
- **Pattern 4:** `QueryResultCard` derives display from signal, owns no state

---

## 8. Example Queries

### Tail-risk concentration

```
Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, "inherent"), 5000000))
```

"At least ⅔ of leaves have P95 above 5M." Range = all leaves.
Scope = simulation-backed function + numeric comparison.

### Sub-portfolio analysis (unary — returns node IDs)

```
Q[>=]^{2/3} x (leaf_descendant_of(x, y), gt_loss(p95(x, "inherent"), 5000000))(y)
```

"Which portfolios have at least ⅔ of their leaf descendants with P95
above 5M?" Answer variable `y` projects matching portfolio IDs. The service
does not serve answer variables yet: it answers this query with a 422 naming
`y` as an answer variable it does not support, and
`docs/dev/plans/PLAN-QAV-QUERY-ANSWER-VARIABLES.md` records the work that
would serve it.

### Exceedance screening

```
Q[<=]^{1/4} x (leaf(x), gt_prob(lec(x, 10000000, "inherent"), 0.05))
```

"At most ¼ of leaves have >5% probability of exceeding 10M."

### Distribution type balance

```
Q[~]^{1/2}[0.05] x (leaf(x), portfolio(z) /\ child_of(x, z))
```

"About half of all leaves are direct children of a portfolio (not
nested deeper)." Structural-only query, no simulation involvement.

---

## 9. Observability

### OTel Span

```
Span: query.evaluate
Attributes:
  query.text         = "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, \"inherent\"), 5000000))"
  query.range_size   = 25
  query.sample_size  = 25
  query.proportion   = 0.72
  query.satisfied    = true
  query.duration_ms  = 340
```

### Metrics

| Metric | Type | Labels |
|---|---|---|
| `query.evaluate.duration` | Histogram | `workspace`, `satisfied` |
| `query.evaluate.count` | Counter | `workspace`, `status` |
| `query.range.size` | Histogram | `workspace` |
