# The query knowledge base, and what assurance questions would need from it

**Status: exploratory scratch. Not a plan, not a decision record, not a
decision.** It records how the register's query knowledge base works today,
what data the life table model and the assurance additions would need, and
whether two example questions could be answered. It builds on two other scratch
notes: `docs/scratch/LIFE-TABLE-LEAVES-AND-REACHABILITY.md`, called the life
table note below, whose stage numbers it cites, and
`docs/scratch/GRC-QUICK-WINS.md`, called the GRC note. Every figure and every
criterion in the examples is invented for this note.

The findings about the code were read from the sources named with each one.
The engine is vql-engine. Its source was read from the sibling checkout
`/home/danago/projects/vague-quantifier-logic`, outside this repository.

`docs/user/TERMINOLOGY.md`, the project's glossary, has no entry for a sort, a
catalog, a dispatcher or a knowledge base. Each is defined where it first
appears.

---

## The three findings

1. The engine holds no knowledge base and has no way to edit one. It evaluates
   a query against a model the register builds and hands in. A new kind of
   thing to query is a change in the register and no change in the engine.
2. "How many requirements of a framework have a control that passes a
   criterion" becomes a query once a requirement is a kind of thing a query
   variable can range over.
3. The return on investment of a set of controls is a computed figure, and a
   query returns a proportion. So that question takes two steps: a formula
   picks the set of controls, and the valuation computes the figure for the
   set.

---

## How the knowledge base works today

### The query form

The glossary calls a query over a tree a screening query (4.5). The engine
evaluates one sentence shape. From
`core/src/main/scala/vql/parser/VagueQueryParser.scala` in the engine:

```
Q[op]^{k/n} x (R(x,y'), φ(x,y))(y₁, ..., yₘ)
```

`x` is the counted variable. The **range** `R` picks the population. The
**scope** `φ` is the property tested on each member of it. `Q[>=]^{3/4}` reads
"at least three quarters". The other two operators are `<=`, at most, and `~`,
about.

Range and scope are full first-order formulas. The engine's
`core/src/main/scala/parser/FormulaParser.scala` reads these connectives:

| Written | Meaning |
|---|---|
| `~p` | not |
| `p /\ q` | and |
| `p \/ q` | or |
| `p ==> q` | if p then q |
| `p <=> q` | p exactly when q |
| `exists m. p` | some m makes p true |
| `forall m. p` | every m makes p true |

An inner quantifier takes a dot between its variable and its body.

The answer holds a count as well as a yes or no. From
`modules/common/src/main/scala/com/risquanter/register/http/responses/QueryResponse.scala`:

```scala
final case class QueryResponse(
  satisfied: Boolean,
  proportion: Double,
  rangeSize: Int,
  sampleSize: Int,
  satisfyingCount: Int,
  satisfyingNodeIds: List[NodeId],
  queryEcho: String
)
```

So every query already answers "how many": `satisfyingCount` out of
`rangeSize`.

### Sorts, catalog, dispatcher

A **sort** is the kind of a value. The **catalog** declares the sorts, the
constants, and the signature of every function and predicate. The
**dispatcher** is the code that decides each predicate and computes each
function.

`modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
declares two sorts a variable can range over, `Node` and `Mitigation`, and
value sorts for a loss, a probability and the quoted literals. A variable's
sort is not written in the query. The engine infers it from the signatures of
the predicates the variable appears in.

The predicates the register offers today:

| Predicate | Decided from |
|---|---|
| `leaf(x)`, `portfolio(x)` | the kind of the node |
| `child_of(x, y)`, `descendant_of(x, y)`, `leaf_descendant_of(x, y)` | the tree's structure |
| `eq(x, y)` | node identity |
| `mitigate(x, m)`, `mitigated(x)`, `unmitigated(x)` | each mitigation's resolved scope |
| `mitigation_named(m, "…")`, `mitigation_id(m, "…")` | mitigation identity |
| `gt_loss(a, b)`, `gt_prob(a, b)` | a comparison of two values |

Three functions read the simulation: `p95(x, m)`, `p99(x, m)` and
`lec(x, t, m)`. The last one gives the chance that node `x` loses at least `t`.
The final slot names the selection the tree is valued with (glossary 4.5): the
constant `inherent`, the constant `residual`, or a bound mitigation variable.
The two constants are written quoted in a query, as in `p95(x, "residual")`. A
bound `m` means the tree valued with that one mitigation alone. The code maps
it to `MitigationSelection.single(id)`.

### The engine holds no knowledge base

The engine's input is two immutable values. From
`core/src/main/scala/vql/typed/FolModel.scala` and `RuntimeModel.scala` in the
engine:

```scala
case class FolModel private (catalog: TypeCatalog, model: RuntimeModel)

case class RuntimeModel(
  domains: Map[TypeId, Set[Value]],
  dispatcher: RuntimeDispatcher)
```

`domains` holds the members of each sort a variable can range over. The
dispatcher decides a predicate by calling the register's code:

```scala
def evalPredicate(name: SymbolName, args: List[Value]): Either[String, Boolean]
```

No fact is stored in the engine. When it needs to know whether
`mitigate(x, m)` holds, it asks the dispatcher, and the register's function
reads the mitigation's resolved scope. The engine's source has no operation
that adds, asserts or removes a fact.

The register builds the model from the tree for each query. So the knowledge
base is edited by editing the register's stored data, and a change is visible
to the next query. There is no second copy to keep in step.

### Four limits

- **No derivation.** The engine evaluates sentences over a fixed model. It has
  no rules. A derived fact is computed by the register's code and offered as a
  predicate, as `descendant_of` is. The rules of stage 8 and the argument
  graph of stage 10 would enter the same way.
- **No arithmetic.** The catalog has the two comparisons and nothing else. A
  difference or a ratio of two figures cannot be written in a query.
- **A count, never a sum.** The result is a proportion of one variable's
  population. No query returns a total or a mean.
- **The answer list holds node identifiers only.**
  `modules/server/src/main/scala/com/risquanter/register/foladapter/QueryResponseBuilder.scala`
  keeps values of the `Node` sort and drops the rest. Answer variables, the
  `(y)` after the query, are parsed and not served.
  `docs/dev/plans/PLAN-QAV-QUERY-ANSWER-VARIABLES.md` states: "Nothing in
  register or in the engine produces that answer set."

None of the four is in the engine's grammar. The first three are lifted by
declaring a predicate or a function in the register's catalog and writing its
code in the dispatcher. The fourth is a change to the register's response.

---

## The data each part needs

The data falls into three layers by where it lives. A leaf's likelihood is
computed before the tree exists, so most of the life table's data sits
upstream of the tree.

### Layer 1 — upstream of the tree: what the leaves are built from

| Part | Stage | Data | Shape of one record |
|---|---|---|---|
| Life table | 5, 7 | A vulnerability on an asset | asset, CVE, found date, fixed date |
| Daily scores | 2, 17 | The EPSS score per CVE per day | CVE, date, score |
| Asset facts | 8 | Ways in, connections, controls on a way | fact, from date, to date, source |
| Code-run statement | 9 | Whether the flawed code is run | image digest, CVE, yes or no, source |
| Arguments | 10 | Which facts attack which, and the ranking of sources | fact, attacked fact; source, rank |
| Derived days | 8 | For a row and a period, one of the three cases | row, period, case |

These are dated facts and rules over them. They feed the reasoning of stage 8
and the step that writes the leaves. They do not fit the query knowledge base
as it is. That knowledge base is built from one tree, and it has no dates.

### Layer 2 — beside the tree: what a figure rests on

| Part | Stage | Data | Shape of one record |
|---|---|---|---|
| Belief about a control | 15 | The judgement, as counts | control, scope, `a`, `b` |
| Belief about a rate | 17, 19 | The judgement, as counts | leaf or CVE, `k`, `t` |
| Test record | 14, 15 | One check of one control | control, scope, date, kind, passes, blocks, tester, evidence link |
| Own records | 17 | Exploitations seen, and the days of exposure | CVE or class, events, days |
| Finding | GRC note | A failed test and its follow-up | test, owner, due date, status |

The figure the register applies is derived from these. It is the mean of the
updated belief (stage 16). Today a person writes that figure into the
mitigation's spec.

### Layer 3 — on the tree: what the query knowledge base can see

| Part | Data | New in the catalog |
|---|---|---|
| Where a leaf came from | The asset, the CVE and the period | sorts `Asset` and `Vulnerability`; `on_asset(x, a)`, `from_vuln(x, v)` |
| Requirement | Framework and section | sort `Requirement`; `in_framework(r, "…")`, `meets(m, r)` |
| Test | The records of layer 2 | sort `Test`; `test_of(t, m)`, `passed(t)`; `weight(m)`, which gives `a + b` |
| Cost | The cost of a mitigation, in one of the two shapes of `docs/scratch/MITIGATION-ROI.md`: a yearly fee, or an upfront fee plus yearly maintenance | `cost(m)`, with a value sort for money spent |
| Owner, status, due date | The record fields of the GRC note | `owned(m)`, `implemented(m)` |

The names in the last column are invented for this note. Each is a declaration
in the register's catalog and a function in its dispatcher that reads the
stored records.

Three things need work in the register beyond those declarations:

- **Dates.** The knowledge base has no date sort. Either a date sort with a
  comparison predicate is added, or the dates stay upstream and the tree holds
  only their result, as the cut leaves of stage 6 do.
- **Where the records live.** The knowledge base is built from the tree.
  Requirements and test records are either stored with the tree or handed to
  the knowledge base as a second input.
- **The answer list.** A query that counts requirements returns a correct
  count today and an empty list of matches, because only node identifiers are
  kept.

### What making requirements queryable takes

Three pieces, all in the register:

1. **Storage and editing.** A place to keep requirements and the mapping from
   mitigations to them, and endpoints to change both. This is the assurance
   group of the GRC note.
2. **The catalog.** A `Requirement` sort and the signatures of `in_framework`
   and `meets`.
3. **The model.** The set of requirement identifiers as that sort's members,
   and two dispatcher functions that read the stored mapping.

---

## Question 1 — how many requirements have a control of a given effectiveness

**The criterion.** The loss exceedance curve gives, for a node and a loss, the
chance of losing at least that much (glossary 1.11). Take as the criterion: a
control is adequate if the tree's root, valued with that control alone, has at
most a 5% chance of losing 10 million or more.

**The query,** with the new sort and predicates in place:

```
Q[>=]^{3/4} r (in_framework(r, "ISO 27001:2022"),
               exists m. (meets(m, r) /\ ~gt_prob(lec(node_named("Estate"), 10000000, m), 0.05)))
```

Read: at least three quarters of the ISO 27001 requirements have some
mitigation that meets them and passes the criterion. `satisfyingCount` is the
number of such requirements and `rangeSize` is the number of requirements in
the framework. Each bound `m` costs one valuation of the tree with that
mitigation alone.

**Three variants.**

- **A criterion on the control's own targets,** in place of the root:

  ```
  forall x. (mitigate(x, m) ==> ~gt_prob(lec(x, 1000000, m), 0.02))
  ```

- **A relative criterion,** such as "cuts the chance by at least half". It
  needs a ratio, which a query cannot compute. One new function supplies it,
  for example `reduction(x, t, m)`, computed in the dispatcher from the two
  curves. The query compares its value with a threshold.
- **A criterion on the evidence,** such as "the control's figure rests on at
  least 50 observations", through `weight(m)`. Combined with the first
  criterion it counts the requirements whose controls are both effective and
  tested. That is the difference between assurance and evidence production
  that the GRC note is about.

**The gap analysis** is the same query with the scope negated: the
requirements for which no `m` satisfies `meets(m, r)`. Its answer is true only
if the requirement sort holds the whole framework, which is the catalogue
question in the GRC note.

---

## Question 2 — the return on investment of the controls that meet requirements

**The definition.** `docs/dev/plans/PLAN-MITIGATION-ROI.md` gives it:

> **ROSI (Return on Security Investment)** — `((ALE_before − ALE_after) − Cost) /
> Cost`, a one-year ratio.

ALE is the annual loss expectancy, the mean yearly loss.
`docs/scratch/MITIGATION-ROI.md` takes ROSI as a starting point and adds the
drop in the tail and a figure over several years. The two steps below are the
same for each of those figures.

**Why a query cannot return it.** ROSI is a number computed from two means and
a sum of costs. A query returns a proportion. So the question takes two steps.

1. **The set.** A formula with one free variable picks the mitigations:

   ```
   exists r. (in_framework(r, "ISO 27001:2022") /\ meets(m, r))
   ```

   The register already evaluates a formula of this kind to its exact
   satisfying set. `MitigationScopeResolver.scala` does it for every targeting
   condition, over nodes.
2. **The figure.** The tree is valued once with no mitigation, and once with
   that set as the selection (glossary 4.5). The mean loss of each is read off,
   and the costs of the set are added. The example reads the figures at the
   root. At which node a benefit is measured is the ROI plan's open Decision 5.

The order of the two steps matters for how the work is served.
`docs/scratch/MITIGATION-ROI.md`, section 10, separates queries whose needed
simulations are known before evaluation from those where a simulated figure
decides the next simulation. The set above is picked by `in_framework` and
`meets`, which read no simulated figure, so the second valuation is known
beforehand. A set picked by a criterion on simulated figures, such as the
controls that pass the criterion of question 1, is not: the selection of the
second valuation depends on the figures of the first. That is the second kind,
which that note records as not served today.

A worked case:

```
mean yearly loss, no mitigation                    4.0 million
mean yearly loss, with the controls in the set     2.5 million
yearly cost of those controls                      0.6 million
ROSI = ((4.0 − 2.5) − 0.6) / 0.6 = 1.5
```

**What is missing today.** A mitigation has no cost. The plan records
"`Mitigation(id, name, target, spec, precedence)` — **no cost field.**" The
shape of the cost field is that plan's open Decision 4. The mean loss exists as
`averageAnnualLoss`.

**Two cautions about the figure.**

- **The set's ROSI is not the sum of each control's.** Two controls on the
  same leaf overlap, so their savings do not add. The set is valued together.
- **A ROSI per requirement needs a choice.** A control that meets three
  requirements has one cost. Counting that cost under each requirement counts
  it three times. How to split it is a modelling choice, and nothing in the
  data decides it.

**What a query adds once ROSI exists.** With a function `rosi(x, m)` in the
catalog, selecting by return is a query: "at least half of the controls that
meet an ISO requirement return more than they cost".

---

## Open questions

1. **Where layer 1 lives.** The dated facts and the rules over them are not
   part of a tree. Are they a second store beside the register, or inside it?
   The life table note lists "The step that builds the tree" among what the
   register does not have.
2. **Rules.** The engine evaluates and does not derive. Stage 8's rules need
   negation and stage 10's graph needs the grounded labelling.
   `Argumentation-Engine.md` sketches a small engine for this in Scala. Does it
   run when the tree is built, or when a query is asked?
3. **A valuation per bound mitigation.** A query that binds `m` over many
   mitigations values the tree once for each. What is the cost for a tree with
   a few hundred mitigations?
4. **A counted variable that is not a node.** The catalog declares
   `Mitigation` as a sort a variable can range over. No query in this note was
   run. Whether a query whose counted variable is a mitigation runs end to end
   today is not checked.
5. **The answer list.** Does the response gain a list per sort, or does
   serving answer variables, which `PLAN-QAV-QUERY-ANSWER-VARIABLES.md`
   covers, replace it?
6. **Splitting a control's cost across requirements.** Which rule, if any, and
   who chooses it?
7. **Control or mitigation as the sort.** The queries above range `m` over
   mitigations, and `meets(m, r)` relates a mitigation to a requirement.
   `docs/dev/plans/PLAN-CONTROL-GROUPING.md` would put a control above the
   mitigation, and its open decision `CG-D-5` asks whether a selection can
   still reach a single member. If a control is what is selected, `m` ranges
   over controls and `meets` relates a control to a requirement.
8. **The unit of a loss.** The queries above write 10 million as `10000000`.
   The glossary says of a loss: "Figures are typically in millions, so 1 means
   one million." The code's comment on the `Loss` type says "Loss amount in
   base currency units (e.g., cents, pennies)". A threshold in a query, an
   appetite point and a cost all need the same unit as the losses they are
   compared with.
