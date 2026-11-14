# Plan: fold a portfolio's children in parallel

**Status:** Placeholder. A possible optimization, recorded with what is known
on 2026-11-14. No decision in it is ruled. It is not implementation-grade under
the Plan Quality Gate in `CLAUDE.md`: it has no exact signatures, no inventory
document and no ADR review, so it authorizes no source edit.
**Date:** 2026-11-14.
**ADR reference:** ADR-009 (the `TrialOutcomes` monoid, whose laws license
parallel evaluation), ADR-014 (leaf entries only; portfolios fold on read),
ADR-033 (exception boundaries, for the overflow conversion).
**Related plans:**
`docs/dev/plans/PLAN-TOR-TRIAL-OUTCOMES-REPRESENTATION.md`, which makes each
combine cheaper, and `docs/dev/plans/PLAN-SIMULATION-CONCURRENCY-BOUNDS.md`,
which bounds how many fibers one request may fork.

---

## Objective

Use more than one processor for combining the children of a single portfolio,
without changing any figure the server returns.

## Terms

- **Outcome.** The loss of one node in one trial of the Monte Carlo simulation.
- **`TrialOutcomes`.** The type holding one node's outcomes and the trial count.
- **Combine.** Adding two `TrialOutcomes` trial by trial.
- **Fold.** Combining a list of results into one by repeated combine. A
  portfolio's total is the fold of its children's totals.
- **Fiber.** ZIO's unit of concurrent work. Many fibers share the runtime's
  threads, of which there is one per processor.

## What exists

Aggregation has three levels, and two of them are already parallel.

**Trials inside one leaf run in parallel.** `Simulator.performTrials` splits the
loss sampling across fibers.

**The children of a portfolio are resolved in parallel.**
`modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`:

```scala
          // Children root disjoint subtrees and the aggregation is associative
          // and commutative, so evaluation order cannot change the figures.
          // foreachPar preserves list order (ADR-009 §4).
          childResults <- ZIO.foreachPar(portfolio.childIds.toList) { childId =>
```

So different leaves simulate side by side, and sibling portfolios fold side by
side.

**Combining one portfolio's children is sequential.**
`modules/server/src/main/scala/com/risquanter/register/services/cache/NodeLosses.scala`:

```scala
      children.map(_.trials).reduceOption(TrialOutcomes.combine) match {
```

`reduceOption` combines the first two children, then that result with the
third, and so on, on one fiber. A portfolio with 50 children performs 49
combines one after another, on one processor.

## What licenses the change

ADR-009 states: "Parallel and order-independent aggregation is safe only when
licensed by stated, tested algebraic laws — not by convention". `TrialOutcomes`
carries a `Commutative` instance, and `TrialOutcomesSpec.scala` tests the laws.

Associativity is the law this plan uses. It says `(a ⊕ b) ⊕ c` equals
`a ⊕ (b ⊕ c)`. So a list may be cut at any point, each part folded
independently, and the two results combined, with the same total as the
left-to-right fold.

The step was foreseen and not built. `docs/dev/plans/IMPLEMENTATION-PLAN.md`
records the parallel child resolution as done and adds that "parallel reduction
cannot change the figures". `private-docs/ARCHITECTURE_SCALABILITY.md` lists
"**Aggregation** - `reduce(combine)` creates intermediate maps" as a bottleneck
and sketches a `parallelReduce` that splits the list in half. No such function
exists under `modules/`.

## What was measured

`tests/perf/measure-simulation.sh` on 2026-11-13 and 2026-11-14, against the
server container `local/register-server:0.19.0`, in-memory repository, one tree
of 500 leaves under 50 portfolios, 10,000 trials, medians of five repetitions,
on a host with 32 processors:

| Request for the root's curve | Time |
|---|---|
| Nothing cached, every leaf simulated | 1.17 s |
| Every leaf cached, nothing simulated | 0.69 s |

In that tree each portfolio folds 10 leaves, and the 50 portfolios do so at the
same time. The root then combines the 50 portfolio totals in sequence.

**Not measured:** how much of the 0.7 s is the root's fold. This plan rests on
it being a large part, which is an inference from reading the code.

## Prerequisite — measure where the time goes

The same measurement `PLAN-TOR-TRIAL-OUTCOMES-REPRESENTATION.md` requires: the
duration of each step of one cached request for the root. In addition, the
duration of the root's fold alone.

If the sequential fold is not a large part of the request, this plan is dropped.

## Open decisions

None is ruled, and no option is recommended.

**PAR-D-1 — the shape of the parallel fold.**

- **A. Halve and recurse.** Split the children in two, fold each half on its own
  fiber, combine the two results. Fifty children take six rounds. This is the
  sketch in `private-docs/ARCHITECTURE_SCALABILITY.md`.
- **B. Fixed chunks.** Cut the children into a set number of chunks, fold each
  chunk left to right on its own fiber, then fold the chunk totals. Fewer forks
  than A, and the chunk count is a setting.
- **C. Do nothing.** If `PLAN-TOR-TRIAL-OUTCOMES-REPRESENTATION.md` makes one
  combine cheap enough, a sequential fold of 50 results may cost too little to
  be worth forking for.

**PAR-D-2 — the smallest list worth splitting.** Forking a fiber has a cost.
`Simulator.performTrials` already runs sequentially below 100 successful trials
for that reason. The threshold here is a number to be measured, and whether it
is a constant or a setting in `application.conf` follows the rule in
`.claude/rules/scala-style.md` on values that change behaviour.

**PAR-D-3 — order relative to the other two plans.** The gain from this plan
depends on what one combine costs, which
`PLAN-TOR-TRIAL-OUTCOMES-REPRESENTATION.md` changes. The forks this plan adds
count toward the fan-out `PLAN-SIMULATION-CONCURRENCY-BOUNDS.md` describes as
unbounded.

## What must not change

- Every figure on the wire. Associativity guarantees the total; the test must
  still show it.
- The order of `children` in `PortfolioLosses`, which provenance order depends
  on (ADR-009 §4). Only the totals are folded in a different grouping; the list
  of children is kept as given.
- Overflow behaviour. `Math.addExact` throws `ArithmeticException` when a
  per-trial sum passes `Long.MaxValue`, and `PortfolioLosses.create` converts
  that to a refusal. Losses are not negative, so a sum that overflows in one
  grouping overflows in every grouping. That every `Loss` reaching a fold is
  non-negative, including after a mitigation transform, has not been verified
  and is checked when the plan is elevated.
- The refusal of an empty child list and the trial-count check.

## ADR alignment — to be completed when the plan is elevated

- **ADR-009.** Compliant in intent: the change is the parallel aggregation its
  laws exist to license. Its "Unlicensed parallelism" entry requires the laws to
  be stated as instances and verified by property tests, which they are.
- **ADR-014 §3.** Unaffected: portfolios still fold on read.
- **ADR-033 §3.** The overflow exception is thrown on a forked fiber under this
  plan. How it reaches the conversion in `PortfolioLosses.create`, which today
  is a `try` around a pure expression, has to be designed.
- The full ADR review has not been run.

## Verification — to be completed when the plan is elevated

- A property test that the parallel fold of a generated list equals the
  sequential fold of the same list.
- The existing law tests in `TrialOutcomesSpec.scala`.
- `tests/perf/measure-simulation.sh` before and after, same grid, with the two
  tables placed side by side in this document.
- The project's complete test suite.

## Not in this plan

- Caching portfolio results, which ADR-014 §3 rules out.
- Changing the representation of outcomes, which is
  `PLAN-TOR-TRIAL-OUTCOMES-REPRESENTATION.md`.
- Bounding the number of fibers, which is
  `PLAN-SIMULATION-CONCURRENCY-BOUNDS.md`.
