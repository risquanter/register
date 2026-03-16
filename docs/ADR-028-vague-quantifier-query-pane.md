# ADR-028: Vague Quantifier Query Pane

**Status:** Accepted (awaiting implementation)  
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
- A standalone library (**vague-quantifier-logic**) implements first-order
  logic with vague quantifiers (Fermüller, Hofer & Ortiz, FQAS 2017); it
  provides a parser, an FOL evaluator, and a `KnowledgeSource` abstraction
  but has no web API
- The register's simulation engine produces `LossDistribution` results
  (outcomes, quantiles, exceedance probabilities) that must be queryable
  through the FOL evaluator without modifying the library's core types

---

## Decision

### 1. Server-Side Evaluation (Architecture)

The vague-quantifier-logic library is a JVM dependency of the `server`
module. Queries are evaluated server-side with direct access to the
simulation cache. Client-side (Scala.js) evaluation was ruled out due to
JVM-only dependencies (Commons Math), large data transfer requirements,
and the absence of Scala.js testing in the library.

### 2. Custom Interpretation via `RiskTreeKnowledgeBase` (Option X)

A register-side class `RiskTreeKnowledgeBase(tree, results)` builds the
FOL `Model` by:
1. Populating a standard `KnowledgeBase` with structural facts (tree
   topology, node types)
2. Calling `KnowledgeSourceModel.toModel()` to get the base interpretation
3. Augmenting the interpretation with simulation-backed **functions**
   (`p95`, `p50`, `lec`) and numeric **comparison predicates** (`>`, `<`,
   `>=`, `<=`)
4. Overriding `getFunction` to parse numeric literals in query strings

```scala
class RiskTreeKnowledgeBase(tree: RiskTree, results: Map[NodeId, LossDistribution]):

  private val kb = KnowledgeBase.builder
    .addRelation("leaf", 1).addRelation("portfolio", 1)
    .addRelation("child_of", 2).addRelation("descendant_of", 2)
    .addRelation("leaf_descendant_of", 2)
    // ... populate from tree ...
    .build()

  private val source = KnowledgeSource.fromKnowledgeBase(kb)

  def toModel(): Model[Any] =
    val base = KnowledgeSourceModel.toModel(source)
    val simFunctions = Map(
      "p95" -> { case List(id: String) =>
        calculateQuantiles(results(id))("P95").toLong },
      "lec" -> { case List(id: String, t: Long) =>
        results(id).probOfExceedance(t) }
    )
    val comparisons = Map(">" -> { case List(a: Number, b: Number) =>
      a.doubleValue > b.doubleValue }, /* <, >=, <= */)

    val augmented = new Interpretation[Any](
      base.interpretation.domain,
      base.interpretation.funcInterp ++ simFunctions,
      base.interpretation.predInterp ++ comparisons
    ) {
      override def getFunction(name: String): List[Any] => Any =
        funcInterp.getOrElse(name,
          if name.forall(_.isDigit) then _ => name.toLong
          else _ => throw Exception(s"Unknown: $name"))
    }
    Model(augmented)
```

**Key property:** Zero changes to the vague-quantifier-logic library.
Thresholds live in the query syntax (`>(p95(x), 5000000)`), making
queries self-describing.

### 3. Materialised Tree Relations (Transitive Closure)

FOL cannot express transitive closure. Instead, `RiskTreeKnowledgeBase`
pre-computes tree relationships during KB construction:

| Relation | Arity | Semantics |
|---|---|---|
| `child_of(x, y)` | 2 | x is a direct child of y |
| `descendant_of(x, y)` | 2 | x is any descendant of y (transitive) |
| `leaf_descendant_of(x, y)` | 2 | x is a leaf descendant of y |
| `leaf(x)` | 1 | x is a RiskLeaf |
| `portfolio(x)` | 1 | x is a RiskPortfolio |

This sidesteps the transitive closure limitation and prevents
double-counting: the query author chooses the appropriate range
predicate (`leaf(x)` for independent units, `child_of(x, y)` for direct
reports, `leaf_descendant_of(x, y)` for all leaf descendants).

### 4. Query Validation Before Evaluation

The `Model[Any]` layer is untyped — `Interpretation[Any]` does not
prevent a user from writing `>(leaf_1, leaf_2)`. The server validates
the parsed `VagueQuery` AST before evaluation:

- Function symbols (`p95`, `lec`) must have correct arity
- Comparison predicates must receive function-valued terms
- Range predicates must reference known relations
- Unknown symbols produce a 400 response listing available predicates

### 5. Model Built Per-Query from Current Tree State

`RiskTreeKnowledgeBase` is constructed at query time from the current
tree and cached simulation results. If the user reshapes the tree
between queries, the next query reflects the new structure. The server
enforces that simulations are current (`resolver.ensureCached`) before
building the model.

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
POST /query { "query": "Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))" }
```

### ❌ Modifying Library Traits for Register-Specific Concerns

```scala
// BAD: adding register-specific hook to library's KnowledgeSource
trait KnowledgeSource:
  def evaluate(relation: String, args: List[RelationValue]): Option[RelationValue]
```

```scala
// GOOD: augment interpretation in register server, library stays general
val augmented = new Interpretation[Any](domain, baseFuncs ++ simFuncs, basePreds ++ comparisons) { ... }
```

### ❌ Quantifying Over Mixed Domain Without Range Constraint

```scala
// BAD: nested ∀y over full domain (includes numeric literals) — runtime errors
"Q[>=]^{2/3} x (leaf(x), ∀y. >(p95(y), 5000000))"
// p95(5000000) → NoSuchElementException
```

```scala
// GOOD: range predicate constrains iteration to valid elements
"Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))"
// x bound by range predicate leaf(x) — only node IDs
```

### ❌ Querying All Nodes When Leaves Are Intended

```scala
// BAD: node(x) includes portfolios → double-counts aggregated losses
"Q[>=]^{1/2} x (node(x), >(p95(x), 5000000))"
```

```scala
// GOOD: leaf(x) gives independent, non-overlapping risk units
"Q[>=]^{1/2} x (leaf(x), >(p95(x), 5000000))"
// Or for sub-portfolio analysis:
"Q[>=]^{2/3} x (leaf_descendant_of(x, portfolio_A), >(p95(x), 5000000))"
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `server/.../query/RiskTreeKnowledgeBase` | KB construction + Model augmentation (DD-2, DD-3) |
| `server/.../services/QueryService` | Parse → validate → build model → evaluate → QueryResponse |
| `server/.../http/controllers/QueryController` | Endpoint wiring |
| `common/.../http/endpoints/WorkspaceEndpoints` | `POST /w/{key}/risk-trees/{treeId}/query` |
| `common/.../http/responses/QueryResponse` | Shared response type (JVM + JS) |
| `common/.../http/requests/QueryRequest` | Query request body |
| `app/.../views/AnalyzeView` | Query textarea, result card, tree highlights |

---

## Decision Context

The following alternatives were evaluated during design:

| Option | Description | Verdict |
|---|---|---|
| **Materialised Booleans** | Pre-compute `high_p95(x)` with thresholds in request params | Rejected: queries not self-describing, predicate proliferation |
| **Extend `RelationValue`** | Add `NumLong`/`NumDouble` to library's enum | Rejected: invasive library change, migration burden |
| **`KnowledgeSource.evaluate` hook** | Add computed-predicate method to library trait | Rejected: register-specific concern in general-purpose library |
| **Threshold-grid materialisation** | Pre-compute at fixed thresholds | Rejected: loses precision for arbitrary user thresholds |
| **Option X (chosen)** | Custom `Interpretation` augmenting base model | Accepted: zero library changes, self-describing queries, simulation functions wired directly |

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
- [ADR-014](ADR-014.md): RiskResult caching strategy
