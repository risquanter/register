# ADR-028: Vague Quantifier Query Pane

**Status:** Accepted (implemented)  
**Date:** 2026-03-14  
**Tags:** query-language, simulation, fol, integration

---

## Context

- Analysts need to ask **proportional screening questions** across risk
  trees: "do at least ⅔ of leaves have P95 above 5M?", "which portfolios
  have most children with extreme tail risk?"
- These questions require iterating over nodes, computing per-node metrics
  (quantiles, exceedance probabilities), and checking a proportional
  threshold — they cannot be answered by a single LEC fetch
- A standalone library (**vql-engine**, repo `risquanter/vql-engine`) implements first-order
  logic with vague quantifiers (Fermüller, Hofer & Ortiz, FQAS 2017). It
  provides a parser and a many-sorted typed pipeline: a consumer declares its
  sorts and symbols in a `TypeCatalog`, backs them with a `RuntimeModel`, and
  evaluates through `VagueSemantics.evaluateTyped`. It has no web API
- The register's simulation engine produces `LossDistribution` results
  (outcomes, quantiles, exceedance probabilities) that must be queryable
  through the evaluator, and the library must stay free of any register
  concept so that it remains a general-purpose engine

---

## Decision

### 1. Server-Side Evaluation (Architecture)

Queries are evaluated in the `server` module, with direct access to the
simulation cache. The library is cross-built for the JVM and Scala.js, and
the `common` module parses query text with it on both sides
(`QueryRequest.resolve`). Evaluation stays on the server because it reads
every referenced node's simulation results, which the browser does not hold
and which are too large to transfer per query.

### 2. A Register-Side Schema and Dispatcher via `RiskTreeKnowledgeBase`

register declares its query language to the library and backs it with its
own data. The library holds no register concept. `RiskTreeKnowledgeBase`
does both halves:

1. `RiskTreeKnowledgeBase.schemaFor(tree)` builds the `TypeCatalog`: the
   sorts (`Node` and `Mitigation` are quantifiable, `Loss`, `Probability`
   and the four reference-literal sorts are not), the `inherent`/`residual`
   constants, the signatures of the value functions `p95`, `p99` and `lec`,
   the signatures of the predicates, and one literal validator per sort.
   A quoted node or mitigation reference resolves through its sort's
   validator at bind time, so a name or id is never interpolated into
   anything.
2. The class builds the `RuntimeModel`: one domain of `Value`s per
   quantifiable sort, and a `MapDispatcher` that decides each predicate and
   computes each value function. Structural predicates read the tree's
   `TreeIndex`. Value functions read the precomputed results for the
   selection their trailing `Mitigation` argument names. Mitigation
   predicates read the resolved scopes. Every argument is read with
   `extract[A]`, never cast.

```scala
val catalog: TypeCatalog = TypeCatalog.unsafe(
  types = Set(TypeDecl.DomainType(nodeSort), TypeDecl.ValueType(lossSort), /* … */),
  constants = ValuationBasis.values.map(_.symbol -> mitigationSort).toMap,
  functions = Map(
    SymbolName(ValueFunction.P95.symbol) -> FunctionSig(List(nodeSort, mitigationSort), lossSort) /* … */),
  predicates = Map(
    SymbolName("gt_loss") -> PredicateSig(List(lossSort, lossSort)) /* … */),
  literalValidators = Map(
    nodeSort -> ((s: String) => riskNameToId.get(s)),
    lossSort -> ((s: String) => s.toLongOption.filter(_ >= 0L)) /* … */)
)

val dispatcher: MapDispatcher = MapDispatcher(
  functions = Map(SymbolName(ValueFunction.P95.symbol) -> { args =>
    for
      id  <- args(0).extract[NodeId]
      sel <- selectionOf(args(1))
      rs  <- resultsFor(sel)
      r   <- lookupResult(rs, id, ValueFunction.P95)
    yield percentile(r, QuantileLevel.P95.probability)
  } /* … */),
  predicates = Map(SymbolName("gt_loss") -> { args =>
    for a <- args(0).extract[Long]; b <- args(1).extract[Long] yield a > b
  } /* … */)
)
```

**Key property:** the library knows nothing about risks, trees or
simulations. Everything register-specific is a catalog entry or a dispatcher
arm in register. Thresholds live in the query syntax
(`gt_loss(p95(x, "inherent"), 5000000)`), making queries self-describing.

### 3. Tree Relations as Primitive Predicates (Transitive Closure)

FOL cannot express transitive closure. Instead, `RiskTreeKnowledgeBase`
offers the tree's relationships as primitive predicates, each decided from
the `TreeIndex` when the evaluator asks. `descendant_of(x, y)` walks `x`'s
parent chain upward, which costs the tree's depth:

| Relation | Arity | Semantics |
|---|---|---|
| `child_of(x, y)` | 2 | x is a direct child of y |
| `descendant_of(x, y)` | 2 | x is any descendant of y (transitive) |
| `leaf_descendant_of(x, y)` | 2 | x is a leaf descendant of y |
| `leaf(x)` | 1 | x is a RiskLeaf |
| `portfolio(x)` | 1 | x is a RiskPortfolio |
| `eq(x, y)` | 2 | x and y are the same node (node identity between two variables) |
| `named_risk(x, "name")` | 2 | x is the node with that name (name resolved to its id at bind time) |
| `risk_id(x, "id")` | 2 | x is the node with that id (id parsed at bind time) |
| `named_mitigation(m, "name")` | 2 | m is the mitigation with that name (name resolved to its id at bind time) |
| `mitigation_id(m, "id")` | 2 | m is the mitigation with that id (id parsed at bind time) |
| `mitigate(x, m)` | 2 | node x is in mitigation m's resolved scope |
| `mitigated(x)` | 1 | x is in the union of all resolved mitigation scopes |
| `unmitigated(x)` | 1 | x is in no resolved mitigation scope (complement of `mitigated`) |

This sidesteps the transitive closure limitation and prevents
double-counting: the query author chooses the appropriate range
predicate (`leaf(x)` for independent units, `child_of(x, y)` for direct
reports, `leaf_descendant_of(x, y)` for all leaf descendants).

### 4. Query Validation Before Evaluation

A query is checked twice before any value is computed, and both checks run
in the library against register's declarations:

- **At the request boundary.** `QueryRequest.resolve` parses the text with
  `VagueQueryParser.parse`. A parse failure, and a query naming answer
  variables, are refused there.
- **At bind time.** `VagueSemantics.evaluateTyped` binds the parsed query
  against the catalog before it evaluates. Every symbol must be declared,
  with the right arity and argument sorts, so `gt_loss(lec(x, 1000000, "inherent"), 0.05)`
  — a probability compared as a loss — is refused before evaluation.
  A quoted node reference no validator accepts is refused as an unknown
  reference. Quantifying over a value sort such as `Loss` is refused as a
  type error.

Each refusal answers 422 with a structured `QueryProblem` naming its kind.

### 5. Model Built Per-Query from Current Tree State

`RiskTreeKnowledgeBase` is constructed at query time from the current
tree, the per-selection simulation results, and the resolved mitigation
scopes. If the user reshapes the tree between queries, the next query
reflects the new structure.

The per-query construction is cheap wiring, not the work: the KB's two
inputs are memoized upstream per tree version.

- **Per-selection results** come from `CachedResultResolver.ensureCachedAll`,
  which is content-addressed — a query is bound once against the catalog to
  discover which `MitigationSelection`s it references (`inherent`, `residual`,
  or a bound `∃m : mitigation`), and one result map is precomputed per
  selection. Identical effective trees share cache entries by content address.
- **Resolved mitigation scopes** come from `MitigationScopeResolver`, memoized
  per `(treeId, branch, commit)` — the commit being the storage-relation
  revision the repository's `getById` reports (ADR-032 §3).

A query that uses no value function precomputes no results.

---

## Code Smells

### ❌ Pre-Computed Boolean Predicates with Side-Channel Thresholds

```scala
// BAD: threshold hidden in request params, query is not self-describing
POST /query { "query": "Q[>=]^{2/3} x (leaf(x), high_p95(x))",
              "predicateParams": { "p95Threshold": 5000000 } }
// Two users with different thresholds see different results for same query text
```

```scala
// GOOD: threshold in query syntax — self-describing, auditable
POST /query { "query": "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x, \"inherent\"), 5000000))" }
```

### ❌ Putting a Register Concept into the Library

```scala
// BAD: a register-specific symbol or type inside vql-engine
object VagueSemantics:
  def p95(node: NodeId, results: Map[NodeId, LossDistribution]): Long = ???
```

```scala
// GOOD: register declares the symbol and backs it with a dispatcher arm
functions = Map(SymbolName(ValueFunction.P95.symbol) -> FunctionSig(List(nodeSort, mitigationSort), lossSort))
SymbolName(ValueFunction.P95.symbol) -> { args => /* extract, look up, compute */ }
```

### ❌ Reading a Dispatcher Argument by Cast

```scala
// BAD: the carrier is assumed; a wrong sort surfaces as ClassCastException
SymbolName("gt_loss") -> { args => Right(args(0).raw.asInstanceOf[Long] > args(1).raw.asInstanceOf[Long]) }
```

```scala
// GOOD: extract[A] returns a typed failure the evaluator reports
SymbolName("gt_loss") -> { args =>
  for a <- args(0).extract[Long]; b <- args(1).extract[Long] yield a > b }
```

### ❌ Querying All Nodes When Leaves Are Intended

```scala
// BAD: node(x) includes portfolios → double-counts aggregated losses
"Q[>=]^{1/2} x (node(x), gt_loss(p95(x, \"inherent\"), 5000000))"
```

```scala
// GOOD: leaf(x) gives independent, non-overlapping risk units
"Q[>=]^{1/2} x (leaf(x), gt_loss(p95(x, \"inherent\"), 5000000))"
// Or for sub-portfolio analysis:
"Q[>=]^{2/3} x (leaf_descendant_of(x, portfolio_A), gt_loss(p95(x, \"inherent\"), 5000000))"
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `server/.../foladapter/RiskTreeKnowledgeBase` | `schemaFor` builds the catalog; the class builds the runtime model and dispatcher |
| `server/.../services/QueryServiceLive` | Load tree → resolve scopes → bind for referenced selections → precompute results → build model → `evaluateTyped` |
| `server/.../foladapter/QueryResponseBuilder` | Evaluation output → `QueryResponse` |
| `server/.../http/controllers/QueryController` | Endpoint wiring |
| `common/.../http/endpoints/WorkspaceQueryEndpoints` | `POST /w/{key}/risk-trees/{treeId}/query` |
| `common/.../http/requests/QueryRequest` | Query request body; `resolve` parses it at the boundary |
| `common/.../http/responses/QueryResponse` | Shared response type (JVM + JS) |
| `common/.../http/errors/QueryProblem` | The structured refusal a 422 carries |
| `app/.../views/AnalyzeView` | Query textarea, result card, tree highlights |

---

## Decision Context

The following alternatives were evaluated during design:

| Option | Description | Verdict |
|---|---|---|
| **Materialised Booleans** | Pre-compute `high_p95(x)` with thresholds in request params | Rejected: queries not self-describing, predicate proliferation |
| **Register symbols in the library** | Add risk-specific functions or a computed-predicate hook to vql-engine | Rejected: register-specific concern in a general-purpose library |
| **Threshold-grid materialisation** | Pre-compute at fixed thresholds | Rejected: loses precision for arbitrary user thresholds |
| **Register-side schema and dispatcher (chosen)** | `TypeCatalog` and `MapDispatcher` declared in register | Accepted: the library stays general, queries are self-describing and sort-checked, simulation functions are wired directly |

See [ADR-028 Appendix](ADR-028-appendix-technical-design.md) for
detailed component design and scalability analysis.

---

## References

- Fermüller, C.G., Hofer, M. & Ortiz, M. (2017). *Querying with Vague
  Quantifiers Using Probabilistic Semantics.* FQAS 2017, Springer LNAI
  10333, pp. 15–29.
  [DOI](https://doi.org/10.1007/978-3-319-59692-1_2)
- [ADR-019](ADR-019-frontend-component-architecture.md): Frontend
  component patterns (composable functions, state via params)
- [ADR-009](ADR-009.md): Simulation engine design
- [ADR-014](ADR-014.md): Simulation result caching and render-time curve generation
