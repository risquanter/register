# PLAN-QAV-QUERY-ANSWER-VARIABLES

Plan code `QAV`. Decisions in this document are labelled `QAV-D-<n>`.

**Status.** Written 2026-11-09 at version 0.17.2 as a record of the current
understanding. It is not implementation-grade: no signature in it is final, no
decision is ruled, and there is no inventory document. It authorizes nothing.
It is elevated to an implementation-grade plan when the user asks for the
feature, and the user then creates `docs/dev/plans/PLAN-QAV-QUERY-ANSWER-VARIABLES-INVENTORY.md`.

**Origin.** `PLAN-UES-UNIFIED-ERROR-SHAPE.md`, decision `UES-D-17`, ruled
2026-11-09. That plan makes a query naming an answer variable a typed 422 and
records here what serving such a query would take.

## 1. What this plan is for

Register's query pane evaluates Boolean queries over one tree: a sentence that
is satisfied or not. The query language also has answer variables, which ask
which values make the sentence true. ADR-028's appendix promises that use:

> ### Sub-portfolio analysis (unary — returns node IDs)
> ```
> Q[>=]^{2/3} x (leaf_descendant_of(x, y), gt_loss(p95(x, "inherent"), 5000000))(y)
> ```
> "Which portfolios have at least ⅔ of their leaf descendants with P95
> above 5M?" Answer variable `y` projects matching portfolio IDs.

Nothing in register or in the engine produces that answer set. This plan is
where the feature is designed when it is wanted.

## 2. The current understanding

### 2.1 The query language

A vague-quantifier query has the form `Q[op]^{k/n} x (R(x, y), φ(x, y))(y)`.
`Q[op]^{k/n}` is the quantifier, such as "at least two thirds". `x` is the range
variable: the formula `R` picks the set of `x` values the proportion is counted
over, and `φ` is the condition counted. `(y)` is the optional list of answer
variables. A query with no answer variables is a Boolean query. A query with one
is a unary query. The engine's own document
(`/home/danago/projects/vague-quantifier-logic/docs/VagueQuantifiers.md`) states
the semantics "Given a knowledge base KB and answer tuple c", so an answer tuple
is an input to evaluation, one value per answer variable.

### 2.2 What the engine does with an answer variable

The engine is the first-party library `vql-engine`, read from its sibling
checkout `/home/danago/projects/vague-quantifier-logic/core/src/main/scala/vql/`.

The parser reads the suffix. `VagueQueryParser.parseAnswerVars` returns the
variable names, or `Nil` when there is no `(…)` suffix, and `ParsedQuery` keeps
them as `answerVars: List[String]`.

The constructor enforces two rules on the variables. `ParsedQuery.mk` throws
`QueryException(QueryError.ValidationError(…))` when `x` does not occur free in
the range formula, and when a free range variable other than `x` is not an
answer variable ("Range variables … must be subset of answer variables …").
`VagueQueryParser.parse` catches the exception and returns the error as a
`Left`.

Evaluation takes the answer tuple as input and refuses when a value is missing.
`TypedSemantics.evaluate(query, model, answerTuple: Map[String, Value] = Map.empty, …)`
first runs `validateAnswerTuple`:

```scala
// vql/typed/TypedSemantics.scala
query.answerVars.foldLeft[Either[QueryError, Env]](Right(answerTuple)) { (acc, boundVar) =>
  acc.flatMap { env =>
    env.get(boundVar.name) match
      case None =>
        Left(QueryError.ValidationError(
          message = s"Missing answer tuple value for '${boundVar.name}'",
          field = "answer_tuple", …))
      case Some(v) if v.sort == boundVar.sort => Right(env)
      case Some(v) => Left(…)
```

So the engine checks one tuple. It does not enumerate candidate tuples. Its
result type confirms this:

```scala
// vql/result/EvaluationOutput.scala
case class EvaluationOutput[D](
  result: VagueQueryResult,
  rangeElements: Set[D],
  satisfyingElements: Set[D])
```

`rangeElements` and `satisfyingElements` are sets of `x` values. No field holds
answer tuples. `VagueSemantics` offers `bindTyped`, `evaluateTyped` and
`satisfyingSet`, and none enumerates answer values.

The engine's README says "Queries with answer variables return answer sets".
Its code does not. That mismatch belongs to the `vague-quantifier-logic`
repository and is noted here once.

### 2.3 What register does today

`QueryServiceLive` evaluates with an empty tuple:

```scala
// modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala
output <- ZIO.fromEither(
            VagueSemantics.evaluateTyped(
              query = parsed,
              folModel = folModel,
              answerTuple = Map.empty,
              …
```

A query with `(y)` therefore parses, binds, and is refused by
`validateAnswerTuple`. Before `PLAN-UES-UNIFIED-ERROR-SHAPE.md` that refusal is
classified as an engine fault and answered 500. After its Phase 5.3,
`QueryRequest.resolve` returns `Either[QueryProblem, ParsedQuery]` and answers
`QueryProblem.AnswerVariablesNotSupported(variables: List[String])`, a 422,
whenever `parsed.answerVars` is non-empty. This plan replaces that refusal with
an answer.

### 2.4 What the knowledge base offers

`RiskTreeKnowledgeBase` is built per query from one tree
(`QueryServiceLive.scala`, `RiskTreeKnowledgeBase(schema, resultsBySelection, resolved.appliedScopes)`).
Its sorts are node sorts, and its predicates include tree structure such as
`leaf_descendant_of(x, y)`. The candidate values of an answer variable of a node
sort are the nodes of that sort in the tree, so the domain to enumerate is
finite and already materialised in the knowledge base. Simulation results are
keyed by content hash and cached (ADR-014), so enumerating `y` re-evaluates the
formula and runs no further simulation.

## 3. What would have to be built

This section is a sketch. Every signature is a placeholder to be written out
when the plan is elevated.

1. **Enumeration.** For a unary query, evaluate the Boolean query once per
   candidate value of `y` and collect the candidates whose evaluation is
   satisfied. Where this runs is `QAV-D-1`.
2. **A response that carries the answers.** Today's response carries one
   result, the range size and the satisfying count. A unary query's answer is
   a list of node identifiers, one per satisfying `y`. Whether the existing
   response gains an optional part or a second response shape is `QAV-D-2`.
   ADR-038 decides where that shape is declared.
3. **Arity.** The engine's document names Boolean and unary queries only.
   Whether register serves one answer variable or any number is `QAV-D-3`.
4. **The request boundary.** `QueryRequest.resolve` stops refusing a non-empty
   `answerVars`, and `QueryProblem.AnswerVariablesNotSupported` is removed from
   the wire with its `ErrorMessages` arm.
5. **Bounds.** The work is the tree's node count times one evaluation. ADR-017
   bounds the tree, so the enumeration is bounded by it. Whether a tighter
   bound is needed is part of `QAV-D-1`'s cost estimate.
6. **The pane.** The analyze pane renders the answer list, under ADR-019.
7. **ADR-028.** The appendix sentence added by `PLAN-UES-UNIFIED-ERROR-SHAPE.md`
   saying the service does not yet serve answer variables is removed.

## 4. Open decisions

None is ruled.

### QAV-D-1 — where the enumeration runs — OPEN

- **A) In register.** `QueryServiceLive` loops over the candidate nodes of `y`'s
  sort, calls `evaluateTyped` with `answerTuple = Map("y" -> node)` each time,
  and collects the satisfying nodes. No engine release is needed. The loop is
  register's code to bound and test.
- **B) In the engine.** `vql-engine` gains an entry point that takes a query and
  a candidate domain per answer variable and returns the satisfying tuples,
  matching its README. Register calls it once. This needs a `vql-engine`
  release and the supply-chain cooldown of ADR-020 before register can adopt
  it.

### QAV-D-2 — the response shape — OPEN

- **A)** The existing query response gains an optional list of answer node
  identifiers, present for a unary query and absent for a Boolean one.
- **B)** A second response shape for unary queries, chosen by the endpoint on
  the query's arity.

### QAV-D-3 — arity — OPEN

- **A)** One answer variable, matching the engine's "unary query" and the
  ADR-028 example.
- **B)** Any number, with the answer as tuples.

## 5. Prerequisites

- `PLAN-UES-UNIFIED-ERROR-SHAPE.md` Phase 5 landed, so `QueryRequest.resolve`
  and `QueryProblem.AnswerVariablesNotSupported` exist in the form this plan
  changes.
- For `QAV-D-1` Option B: a `vql-engine` release carrying the enumeration.

## 6. ADR alignment

Read when the plan is elevated. The records known to bear on it: ADR-028 and
its appendix (where evaluation runs, the promised example), ADR-029 (query text
is user input reaching a parser; no new parser is added), ADR-017 (tree size
bounds the enumeration), ADR-038 (the response shape and where it is declared),
ADR-019 (how the pane renders the answer), ADR-014 (no simulation is re-run by
the enumeration), ADR-020 (if `QAV-D-1` chooses the engine).

## 7. Terminology impact

`docs/user/TERMINOLOGY.md` has no entry for the query language, a range
variable or an answer variable. No entry is made stale. Whether the glossary
gains entries is decided when the plan is elevated.

## 8. File inventory

Not yet written. The files the sketch touches:
`modules/common/src/main/scala/com/risquanter/register/http/requests/QueryRequest.scala`,
`modules/common/src/main/scala/com/risquanter/register/http/errors/QueryProblem.scala`,
`modules/common/src/main/scala/com/risquanter/register/http/responses/` (the
query response, per `QAV-D-2`),
`modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`,
`modules/app/src/main/scala/app/views/ErrorMessages.scala`, the analyze pane
under `modules/app/src/main/scala/app/`, and
`docs/dev/decision-records/ADR-028-appendix-technical-design.md`.

## 9. Verification plan

Not yet written. It will pin: a unary query answers the satisfying node
identifiers and nothing else; a Boolean query's response is unchanged; a query
with more answer variables than `QAV-D-3` allows is refused with a typed 422;
the enumeration runs no simulation on a tree whose results are cached.
