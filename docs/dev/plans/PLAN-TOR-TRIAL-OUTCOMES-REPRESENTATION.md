# Plan: replace the immutable map inside `TrialOutcomes` with a cheaper representation

**Status:** Placeholder. A possible optimization, recorded with what is known
on 2026-11-14. No decision in it is ruled. It is not implementation-grade under
the Plan Quality Gate in `CLAUDE.md`: it has no exact signatures, no inventory
document and no ADR review, so it authorizes no source edit.
**Date:** 2026-11-14.
**ADR reference:** ADR-009 (the `TrialOutcomes` monoid and its laws), ADR-014
(what is cached, and its claim about the cost of the portfolio fold), ADR-034
(mitigation transforms over `TrialOutcomes`).
**Related plan:** `docs/dev/plans/PLAN-PAR-PARALLEL-AGGREGATION.md`, which
addresses the same measured cost from the other side.

---

## Objective

Make combining two simulation results, and reading a curve from one, cost less
time and less memory, without changing any figure the server returns.

## Terms

- **Trial.** One run of the Monte Carlo simulation. Trials are numbered from 0.
- **Outcome.** The loss of one node in one trial. A trial in which the risk did
  not occur has no stored outcome, and its loss is 0.
- **`TrialOutcomes`.** The type that holds one node's outcomes together with the
  trial count. It is the value the result cache stores for a leaf, and the value
  a portfolio's total is computed in.
- **Combine.** The operation that adds two `TrialOutcomes` trial by trial. A
  portfolio's total is the combine of its children's totals (ADR-009).
- **Fold.** Combining a list of results into one by repeated combine.
- **Boxed.** Stored as a separate object with its own header, instead of as a
  bare number inside an array.

## What exists

`modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`
declares the type over an immutable Scala `Map`:

```scala
final case class TrialOutcomes private (nTrials: PositiveInt, outcomes: Map[TrialId, Loss])
```

`TrialId` is `Int` and `Loss` is `Long`. In a Scala `Map` every key and every
value is boxed. Combine builds a new map from the union of both key sets:

```scala
  def combine(a: TrialOutcomes, b: TrialOutcomes): TrialOutcomes = {
    require(a.nTrials == b.nTrials, s"Cannot merge outcomes with different trial counts: ${a.nTrials} vs ${b.nTrials}")
    val allTrialIds = a.outcomes.keySet ++ b.outcomes.keySet
    TrialOutcomes(
      a.nTrials,
      allTrialIds.iterator.map(t => t -> Math.addExact(a.outcomeOf(t), b.outcomeOf(t))).toMap
    )
  }
```

A curve is read through a second structure built from the map, and each curve
point sums every loss count above its threshold:

```scala
  lazy val outcomeCount: TreeMap[Loss, Int] =
    TreeMap.from(outcomes.values.groupMapReduce(x => x)(_ => 1)(_ + _))(using Ord[Loss].toScala)

  override def probOfExceedance(threshold: Loss): Probability =
    trials.shareOf(outcomeCount.rangeFrom(threshold).values.sum)
```

A curve has 100 points (`curveTickCount` in `application.conf`), so the tail is
summed 100 times per curve.

## What was measured

`tests/perf/measure-simulation.sh` on 2026-11-13 and 2026-11-14, against the
server container `local/register-server:0.19.0`, in-memory repository, one tree
of 500 leaves under 50 portfolios, 10,000 trials, occurrence probability 0.1,
medians of five repetitions, on a host with 32 processors:

| Request for the root's curve | Time |
|---|---|
| Nothing cached, every leaf simulated | 1.17 s |
| Every leaf cached, nothing simulated | 0.69 s |

So about 0.7 s of every request is work other than simulation. With every leaf
cached, that work is reading the tree, hashing, folding the portfolios and
building the curve.

**Not measured:** how the 0.7 s divides between those steps. This plan rests on
the fold and the curve being the larger part, which is an inference from reading
the code. The check that settles it is in "Prerequisite" below.

Memory, same load, five trees held in the cache:

| Server memory limit | Outcome |
|---|---|
| 256M | `OutOfMemoryError` in the fourth tree |
| 512M | Completes at the limit, about twice as slow |
| 1G | Completes, about 40% slower than unconstrained |
| 2G and above | Times no longer improve |

## Prerequisite — measure where the time goes

Before any decision here is ruled, record the duration of each step of one
cached request for the root of a 500-leaf tree: tree read, content hashing,
portfolio fold, curve generation, quantiles. The server already opens tracing
spans around the request and around each leaf simulation. Whether the existing
spans separate these steps, or spans have to be added, is not yet checked.

If the fold and the curve are not the larger part, this plan is dropped.

## Approach — an interface first, then a second implementation behind it

The work is in two steps, so that the old and the new representation can be
shown to be interchangeable.

**Step 1 — put the representation behind a method interface.** Every caller
reaches the outcomes through methods, and none reads the `Map` field. The
callers that read the field today are `LossDistribution` (`outcomes`,
`outcomeCount`), `LECGenerator` (`outcomes.values`), and
`LeafSimResult.approxSizeBytes` (`outcomes.outcomes.size`). The interface does
not need to be elaborate. A sketch of what it has to offer, which is not a ruled
signature:

```scala
// Sketch only. The exact members are TOR-D-2.
trait TrialOutcomes:
  def nTrials: PositiveInt
  def outcomeOf(trial: TrialId): Loss
  def trialIds: Set[TrialId]
  def mapLosses(f: Loss => Loss): TrialOutcomes
  def filterLosses(keep: Loss => Boolean): TrialOutcomes
  // plus what curve generation needs, so that it no longer reads a Map
```

This step changes no behaviour and is verified by the existing suites.

**Step 2 — add the new representation as a second implementation.** The
map-backed implementation stays in the test sources as the reference.

**What makes the two interchangeable.** Every operation on `TrialOutcomes` is a
pure function: its result depends only on its arguments, and it has no effect
besides returning that result. This is what Scala writing calls *referential
transparency*, meaning a call can be replaced by its result without changing the
program. It is the precondition for the comparison, and the property actually
tested is *observational equivalence*: for every input, both implementations
return results that no caller can tell apart. The test generates random
outcomes and checks, for each operation, that the new implementation's result
converted to a map equals the old implementation's result. The existing law
tests in `TrialOutcomesSpec.scala` (associativity, commutativity, identity) then
run against both.

## Open decisions

None is ruled, and no option is recommended. Each depends on the prerequisite
measurement.

**TOR-D-1 — which representation.** For scale: an outcome is 12 bytes of data,
4 for the trial and 8 for the loss. `LeafSimResult.approxSizeBytes` estimates
16 bytes per entry for today's map; the true figure for a boxed immutable map
is higher and has not been measured.

- **A. One array of losses indexed by trial.** `Array[Long]` of length
  `nTrials`, zero where the risk did not occur. Combine is one addition per
  trial with no lookup. Size is 8 bytes times the trial count whatever the
  occurrence probability: 80 KB at 10,000 trials, against 12 KB of data for a
  leaf that occurs in 10% of trials. A portfolio near the root has a loss in
  almost every trial, so there the array wastes nothing.
- **B. Two parallel arrays, sorted by trial.** `Array[Int]` of trials and
  `Array[Long]` of losses, holding only trials with a loss. Size is 12 bytes per
  outcome. Combine is a merge of two sorted sequences.
- **C. Both, chosen by how full the result is.** B for sparse leaves, A once
  more than a set share of trials have a loss. Two code paths to keep
  equivalent.
- **D. A primitive-keyed map from a library.** Removes boxing and keeps map
  semantics. It adds a dependency, which is a decision trigger and falls under
  the supply-chain rules.

**TOR-D-2 — the members of the interface.** In particular what curve generation
is given in place of `outcomes.values`, and whether `trialIds` survives as a
`Set`.

**TOR-D-3 — whether the curve computation changes in the same plan.** Sorting
the losses once and answering each curve point by binary search replaces 100
tail sums. It is independent of TOR-D-1 and could be its own plan.

**TOR-D-4 — whether the cache stores the new representation or converts on
read.** The cache holds `LeafSimResult`, which holds `TrialOutcomes`.

## What must not change

- Every figure on the wire: curve points, quantiles, average loss, probability
  of no loss, and query results.
- Overflow behaviour. `Math.addExact` throws `ArithmeticException` when a
  per-trial sum passes `Long.MaxValue`, and `PortfolioLosses.create` converts it
  to a refusal.
- The trial-count check in combine.
- The laws ADR-009 states, which license parallel child resolution.

## ADR alignment — to be completed when the plan is elevated

- **ADR-009.** The monoid instance and its law tests stay. A change of
  representation must keep them passing.
- **ADR-014 §3** says of the portfolio fold: "The fold is cheaper than the
  storage it would replace". The measurement above puts that in doubt at 500
  leaves. If this plan makes the fold cheap, the claim holds again and the
  record needs no change. If it does not, the record's claim is a finding to
  raise.
- **ADR-034.** Mitigation transforms use `mapLosses` and `filterLosses`.
- The full ADR review for a domain-type change has not been run.

## Verification — to be completed when the plan is elevated

- The equivalence test described above, and the existing law tests against both
  implementations.
- `tests/perf/measure-simulation.sh` before and after, same grid, with the two
  tables placed side by side in this document.
- The project's complete test suite.

## Not in this plan

- Caching portfolio results. ADR-014 §3 rules it out, and whether to revisit
  that depends on what this plan and `PLAN-PAR-PARALLEL-AGGREGATION.md` achieve.
- Any change to simulation itself.
