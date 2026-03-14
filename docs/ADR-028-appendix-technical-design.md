# ADR-028 Appendix: Technical Design — Query Pane Integration

**Parent ADR:** [ADR-028](ADR-028-vague-quantifier-query-pane.md) — Vague Quantifier Query Pane  
**Date:** 2026-03-14

---

## 1. FOL Terminology Reference

This section defines the FOL concepts used throughout the design. All
map directly to types in the vague-quantifier-logic codebase.

| Concept | Definition | Code type | Example |
|---|---|---|---|
| **Domain** ($D$) | Set of all things we can talk about | `Domain[Any]` | `{leaf_1, leaf_2, portfolio_A, 5000000}` |
| **Term** | Expression denoting a domain element | `Term` | Variable `x`, constant `leaf_1`, function `p95(x)` |
| **Formula** | Expression evaluating to true/false | `Formula[FOL]` | `>(p95(x), 5000000)`, `leaf(x)` |
| **Interpretation** ($I$) | Maps symbols to actual computations | `Interpretation[Any]` | `"p95" → calculateQuantiles(results(id))("P95")` |
| **Model** ($M$) | Domain + Interpretation | `Model[Any]` | The complete package for evaluating formulas |
| **Valuation** ($v$) | Assigns values to variables | `Valuation[Any]` | $\{x \mapsto \text{leaf\_3}\}$ |
| **Satisfaction** | $M, v \models \varphi$ — formula holds | `FOLSemantics.holds()` | `M, {x ↦ leaf_3} ⊨ >(p95(x), 5000000)` → true |

### Evaluation Trace

For query: `Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))`

```
For each element d in range {d | leaf(d) holds} :
  v = {x ↦ d}

  1. Evaluate term p95(x):
     x is variable → v(x) = d
     p95 is function → I("p95")(d) = 7200000       ← simulation code runs

  2. Evaluate term 5000000:
     5000000 is constant → I.getFunction("5000000")() = 5000000
                                                        ← numeric literal parse

  3. Evaluate atom >(7200000, 5000000):
     > is predicate → I(">")(7200000, 5000000) = true

Count satisfying / total → proportion → check quantifier threshold
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

### Simulation-Backed Functions (via `Interpretation` augmentation)

| Symbol | Arity | Computes | Return type |
|---|---|---|---|
| `p50(x)` | 1 | `calculateQuantiles(results(x))("P50")` | Long |
| `p90(x)` | 1 | `calculateQuantiles(results(x))("P90")` | Long |
| `p95(x)` | 1 | `calculateQuantiles(results(x))("P95")` | Long |
| `p99(x)` | 1 | `calculateQuantiles(results(x))("P99")` | Long |
| `lec(x, t)` | 2 | `results(x).probOfExceedance(t)` | Double |

### Comparison Predicates (via `Interpretation` augmentation)

| Symbol | Arity | Semantics |
|---|---|---|
| `>(a, b)` | 2 | `a.doubleValue > b.doubleValue` |
| `<(a, b)` | 2 | `a.doubleValue < b.doubleValue` |
| `>=(a, b)` | 2 | `a.doubleValue >= b.doubleValue` |
| `<=(a, b)` | 2 | `a.doubleValue <= b.doubleValue` |

### Numeric Literal Resolution

The augmented `Interpretation` overrides `getFunction` to parse numeric
strings as Long values at evaluation time. When the evaluator encounters
`Term.Const("5000000")`, it calls `getFunction("5000000")` which returns
`_ => 5000000L`. This reuses the existing pattern from
`FOLSemantics.integerModel`.

---

## 3. Tree Relation Materialisation

### Why Pre-Compute Transitive Relations

FOL cannot express transitive closure finitely. The query
`descendant_of(x, portfolio_A)` would require:

```
child_of(x, A) ∨ ∃z.(child_of(z, A) ∧ child_of(x, z))
              ∨ ∃z1.∃z2.(child_of(z1, A) ∧ child_of(z2, z1) ∧ child_of(x, z2))
              ∨ ...   // unbounded for arbitrary depth
```

Instead, `RiskTreeKnowledgeBase` walks the in-memory tree once and
materialises all ancestor-descendant pairs as flat facts.

### Construction Algorithm

```scala
private def allDescendants(nodeId: NodeId): Set[NodeId] =
  val children = tree.childrenOf(nodeId)
  children ++ children.flatMap(allDescendants)

// For each node, materialise descendant_of and leaf_descendant_of
for
  node <- tree.allNodes
  desc <- allDescendants(node.id)
do
  kb.addFact("descendant_of", desc, node.id)
  if tree.isLeaf(desc) then
    kb.addFact("leaf_descendant_of", desc, node.id)
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

### KB Size by Tree Size

For a tree with $N$ nodes, depth $d$, and branching factor $b$:

| Relation | Fact count | 1K nodes | 10K nodes |
|---|---|---|---|
| `leaf` | $\leq N$ | ≤ 1,000 | ≤ 10,000 |
| `portfolio` | $\leq N$ | ≤ 1,000 | ≤ 10,000 |
| `child_of` | $N - 1$ | 999 | 9,999 |
| `descendant_of` | $O(N \times d)$ balanced, $O(N^2/2)$ worst | ~5,000 | ~50,000 |
| `leaf_descendant_of` | $\leq$ `descendant_of` | ~3,000 | ~30,000 |

At 10K nodes with ~100K total facts: a few MB of `Set[RelationTuple]`,
builds in single-digit milliseconds. `Set.contains` lookups are O(1).

### When Scale Becomes a Concern

The `InMemoryKnowledgeSource` stores facts in `Set[RelationTuple]` and
resolves predicates via `source.contains(relation, tuple)`. This is a
full-set membership check and scales well to hundreds of thousands of
facts.

For trees exceeding ~50K nodes (where `descendant_of` produces millions
of facts), the KB construction time and memory become relevant. The
mitigation is an **indexed knowledge source** that replaces
`Set[RelationTuple]` with position-keyed maps:

```scala
class IndexedKnowledgeSource(kb: KnowledgeBase) extends KnowledgeSource:
  // Index: relation → position → value → matching tuples
  private val index: Map[String, Map[Int, Map[RelationValue, Set[RelationTuple]]]] =
    buildIndex(kb)

  def contains(relation: String, tuple: RelationTuple): Boolean =
    // O(1) lookup via first-position index
    index.get(relation)
      .flatMap(_.get(0))
      .flatMap(_.get(tuple(0)))
      .exists(_.contains(tuple))
```

The `KnowledgeSource` trait abstracts this — the evaluator calls the same
interface regardless of the backing implementation. At current scale
(≤1K nodes) this is premature; the note is recorded here for future
reference.

### Query Evaluation Cost

Per query, the dominant costs are:

1. **Simulation cache validation** — `resolver.ensureCached` checks
   `Ref`-based cache; O(1) per node if already cached
2. **KB construction** — tree traversal + fact insertion; O(N × d)
3. **Range extraction** — `RangeExtractor` queries the KB; O(range size)
4. **Scope evaluation** — for each element in range, evaluate formula
   against model; O(range size × formula complexity)
5. **Simulation function calls** — `calculateQuantiles` or
   `probOfExceedance` per element; O(nTrials) for TreeMap lookup

For a 1K-node tree with 500 leaves, a typical query evaluates ~500
scope formulas with one simulation function call each. At ~1ms per
`probOfExceedance` (TreeMap.rangeFrom on 100K entries), total scope
evaluation is ~500ms. KB construction is negligible (<10ms).

---

## 5. API Contract

### Endpoint

```
POST /w/{key}/risk-trees/{treeId}/query
```

### Request

```json
{
  "query": "Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))"
}
```

Single field. Thresholds are in the query syntax, not as separate
parameters.

### Response (200)

```json
{
  "satisfied": true,
  "actualProportion": 0.72,
  "rangeSize": 25,
  "sampleSize": 25,
  "satisfyingCount": 18,
  "matchingNodeIds": ["cyber", "hardware", "..."],
  "queryEcho": "Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))"
}
```

### Error Responses

| Status | Cause | Body |
|---|---|---|
| 400 | Parse error | `{ "error": "parse_error", "detail": "...", "position": 14 }` |
| 400 | Unknown symbol | `{ "error": "unknown_symbol", "symbol": "p96", "available": ["p50","p90","p95","p99","lec"] }` |
| 404 | Tree not found | Standard workspace 404 |
| 409 | Simulation not cached | `{ "error": "simulation_required", "detail": "Run simulation before querying" }` |

---

## 6. Server Components

### `RiskTreeKnowledgeBase`

Location: `server/.../query/RiskTreeKnowledgeBase.scala`

Constructed from `(RiskTree, Map[NodeId, LossDistribution])`. Owns KB
building + `Interpretation` augmentation. Single public method:
`toModel(): Model[Any]`. Also exposes `source: KnowledgeSource` for the
vague quantifier pipeline (range extraction needs the source, scope
evaluation needs the model).

### `QueryService`

Location: `server/.../services/QueryService.scala`

ZIO service. Single method:

```scala
def evaluate(key: WorkspaceKey, treeId: TreeId, query: String): Task[QueryResultDTO]
```

Steps:
1. Parse query string → `VagueQuery` (or fail 400)
2. Validate symbols against known schema (or fail 400)
3. Load tree from workspace
4. Ensure simulations cached → `Map[NodeId, LossDistribution]` (or fail 409)
5. Build `RiskTreeKnowledgeBase(tree, results)`
6. Call `RangeExtractor.extractRange` + `ScopeEvaluator.evaluateSample`
7. Apply quantifier check
8. Map to `QueryResultDTO`

Note: step 6 calls library components directly rather than
`VagueSemantics.holds()`, because `evaluateSample` returns the satisfying
element set (needed for `matchingNodeIds` in the response), whereas
`holds()` discards element identities.

### `QueryController`

Location: `server/.../http/controllers/QueryController.scala`

Wires endpoint to service. Auth check via `AuthorizationService`.

---

## 7. Frontend Components

### State Extensions (`AnalyzeQueryState`)

```
queryInput       : Var[String]                        ← exists
queryResult      : Var[LoadState[QueryResultDTO]]     ← new
matchingNodeIds  : Signal[Set[NodeId]]                ← derived from queryResult
isExecuting      : Signal[Boolean]                    ← derived from queryResult
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

- **Pattern 1:** `QueryResultCard` is a composable function `Signal[LoadState[QueryResultDTO]] => HtmlElement`
- **Pattern 2:** `AnalyzeQueryState` passed as constructor parameter (existing pattern)
- **Pattern 4:** `QueryResultCard` derives display from signal, owns no state

---

## 8. Example Queries

### Tail-risk concentration

```
Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))
```

"At least ⅔ of leaves have P95 above 5M." Range = all leaves.
Scope = simulation-backed function + numeric comparison.

### Sub-portfolio analysis (unary — returns node IDs)

```
Q[>=]^{2/3} x (leaf_descendant_of(x, y), >(p95(x), 5000000))(y)
```

"Which portfolios have at least ⅔ of their leaf descendants with P95
above 5M?" Answer variable `y` projects matching portfolio IDs.

### Exceedance screening

```
Q[<=]^{1/4} x (leaf(x), >(lec(x, 10000000), 0.05))
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
  query.text         = "Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))"
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
