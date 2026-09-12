# Plan: bound concurrent leaf simulation with two nested limits

**Status:** Awaiting approval. **Date:** 2026-09-11.
**ADR reference:** ADR-015 (cached result resolution), ADR-002 (telemetry),
ADR-029 (resource limits / denial-of-service defence).

Single self-contained change, scoped to the `server` module plus one field
addition in `common`'s `SimulationConfig`. No wire-format change, no endpoint
change, no change to any simulation figure.

---

## Objective

`CachedResultResolverLive` resolves a portfolio's children with
`ZIO.foreachPar`, and recurses. Nothing limits the fan-out, so one request forks
one fiber per risk node in the subtree and starts one Monte Carlo simulation per
uncached leaf simultaneously. `SimulationConfig.maxConcurrentSimulations` was
meant to be that limit and is read by no production code.

This plan installs two limits, both acquired only around a single leaf
simulation:

1. A **process-wide limit** on how many leaf simulations run at once. This is
   what stops the server being overrun, regardless of tree shape, tree depth, or
   how many users submit work.
2. A **per-request limit** on how many of one request's leaves may wait in the
   process-wide queue at once. This is what stops one large request making every
   later request wait for it.

---

## Background — the concepts this plan uses

This section defines the terms the design rests on, so the worked example below
can be checked line by line.

### Semaphore

A **semaphore** is a counter of permits plus a queue of waiters. `withPermit(e)`
takes one permit if one is free and runs `e`; if none is free the calling fiber
is suspended and placed in the queue. When `e` finishes — normally, with an
error, or by interruption — the permit is returned and the fiber at the head of
the queue is resumed. ZIO's `Semaphore` releases in first-in-first-out order and
returns the permit through bracket semantics, so a failure cannot leak one.

A semaphore is the only structure here that gives a **shared** budget: the
parties contending for it all decrement one counter, so they can see each other.

### Why `ZIO.withParallelism` is not an alternative

`ZIO.withParallelism(n)` sets a value in a `FiberRef`, and a `FiberRef`'s value
is **inherited by fibers forked inside its scope**. In a non-recursive
`foreachPar` that gives a bound of `n`. In this resolver the traversal is
recursive, so each portfolio's `foreachPar` reads the same inherited `n` and
applies it to *its own* children. The effective bound is `n` per portfolio and
`n^depth` for the request:

| Tree depth | Children per portfolio | `withParallelism(4)` admits |
|---|---|---|
| 1 | 4 | 4 leaves |
| 2 | 4 | 16 leaves |
| 3 | 4 | 64 leaves |
| 4 | 4 | 256 leaves |

The number an operator sets therefore does not describe what the server does,
and the discrepancy grows with tree shape rather than with the setting. That
rules `withParallelism` out for **both** limits in this plan, including the
per-request one: a per-request limit whose real value is `n^depth` is not a
per-request limit. Both are semaphores.

### Head-of-line blocking

A single first-in-first-out queue serves its waiters fairly **as waiters**, not
as requests. If one request puts 500 entries in the queue and another request
then adds 3, those 3 are served after the 500. Fairness between permits is not
fairness between requests. This effect is called head-of-line blocking, and it
is the reason a process-wide limit alone is not the whole answer.

### The deadlock condition, and why permits go on leaves only

A deadlock needs a cycle of hold-and-wait: some party holds a resource while
waiting for a party that needs that same resource.

**The shape that deadlocks** — permits taken at *every* node. Take 2 permits and
a tree of root → P1, P2 → each with leaves:

1. Root acquires permit 1, then forks P1 and P2 and waits for both.
2. P1 acquires permit 2, then forks L1 and L2 and waits for both.
3. P2 asks for a permit. None free. P2 waits.
4. L1 asks for a permit. None free. L1 waits.

Root cannot finish until P2 finishes; P2 cannot start until a permit frees; the
only permit holders are Root and P1, and neither can finish. Nothing releases.

**The shape that cannot deadlock** — permits taken only around `simulateLeaf`. A
leaf simulation forks no child resolution and waits for no other fiber's
completion. Every permit holder therefore finishes in bounded time and releases.
With no permit holder waiting on another party, there is no cycle, so there is no
deadlock. This is why every limit in this plan is acquired at exactly one place.

**Two semaphores, one order.** A fiber takes the per-request permit first and the
process-wide permit second, and holds both only across `simulateLeaf`. No fiber
ever holds the process-wide permit while waiting for a per-request permit, so the
two-lock cycle cannot form. The nesting in the code *is* the ordering:
`requestPermits.withPermit(limiter.withPermit(simulateLeaf(...)))`.

---

## Worked example — the design-correctness check

Assumptions used throughout, chosen to be checkable rather than realistic:

- 8-core machine. ZIO's default executor therefore runs 8 worker threads.
- One uncached leaf simulation costs **20 ms** of CPU.
- `maxConcurrentLeafSimulations` (process-wide) = **8**.
- `maxConcurrentLeafSimulationsPerRequest` = **8**.
- Request **A**: a tree with **500 uncached leaves**, submitted at t = 0.
- Request **B**: a tree with **3 uncached leaves**, submitted at t = 1 ms.

### Case 1 — today, no limit

A forks 500 leaf fibers; all are runnable at once. B's 3 fibers join them. ZIO
schedules 503 runnable fibers across 8 threads, and its fibers yield
cooperatively, so all of them make progress together rather than in submission
order.

- Total work: 503 × 20 ms = 10 060 ms of CPU, spread over 8 threads → **1 258 ms
  wall clock**.
- Under fair sharing each fiber receives 8/503 of a core, so B's 20 ms of work
  takes 20 × 503 / 8 ≈ **1 258 ms**. B finishes when A does.
- Memory: 500 partially built trial maps are live simultaneously. At 10 000
  trials each, that is the peak this plan removes.

### Case 2 — process-wide limit only

A's 500 fibers call `withPermit`. 8 acquire; 492 queue. B's 3 fibers arrive and
take queue positions 493, 494, 495.

- The queue drains at 8 permits per 20 ms, i.e. one permit every 2.5 ms.
- B's first leaf waits 492 × 2.5 ms = **1 230 ms**, then runs for 20 ms.
- A finishes at 500 / 8 × 20 ms = **1 250 ms** — unchanged from Case 1, because
  the machine was already the constraint.
- Memory is now bounded at 8 simulations in flight. **This is the win.**
- B is still blocked for 1.23 s behind A. **This is what Case 3 fixes.**

### Case 3 — both limits

A's fibers acquire A's own semaphore first. 8 of them get through and go on to
take all 8 process-wide permits; **A's other 492 fibers wait on A's own
semaphore and are not in the process-wide queue at all.**

B arrives. B's semaphore is its own and empty, so all 3 of B's fibers pass it
immediately and enter the process-wide queue at positions 1, 2, 3.

- B's first leaf starts after one permit frees: **2.5 ms**. Its third starts at
  7.5 ms. B finishes at ≈ **27.5 ms**, against 1 230 ms in Case 2.
- A finishes at 500 / 8 × 20 ms = **1 250 ms** — *unchanged*. A is not slowed at
  all, because its own limit (8) equals the process-wide limit (8), so A can
  still occupy every permit whenever nobody else wants one.

This is the property to check when reviewing the design: **the per-request limit
does not throttle a request; it keeps the shared queue short.** Setting it below
the process-wide limit would throttle — with a per-request limit of 4, A would
finish at 500 / 4 × 20 = 2 500 ms, doubling its time on an otherwise idle
machine, for no gain. That is why the two values default to the same number, and
why the per-request one exists as a separate knob rather than as a fraction.

### Case 4 — what the design deliberately does not solve

20 concurrent requests, each with a per-request limit of 8, against 8
process-wide permits. The process-wide queue can hold 20 × 8 = 160 waiters, so a
newcomer's first leaf waits up to 160 × 2.5 ms = **400 ms**.

The wait therefore grows with the number of concurrent **requests**, not with the
size of the largest tree. That is the intended behaviour: waiting proportional to
real load is correct, waiting proportional to someone else's tree is not.
Bounding the wait further would need a scheduler that serves per-request queues
in rotation instead of one shared first-in-first-out queue. This plan does not
build that, and there is no evidence the product needs it.

### Interaction with trial parallelism — read this before choosing values

Each leaf simulation internally runs its loss phase across
`defaultTrialParallelism` fibers. The two settings **multiply**: 8 concurrent
leaves × 8 trial fibers = 64 runnable fibers on 8 cores. Neither value alone
describes the fiber count. The process-wide leaf limit is the outer factor and is
the one to keep modest.

---

## ADR alignment

- **ADR-015:** resolution semantics are untouched. The permit wraps
  `simulateLeaf`, which runs only on a cache miss, so a cache hit acquires
  nothing and the hit path keeps its current cost. Cached content, cache keys,
  and the identity-attached-at-the-edge rule are unchanged.
- **ADR-002:** `available` on the limiter exposes free permits, which makes
  saturation reportable next to the existing `risk_result.simulation.duration_ms`
  histogram. Whether to emit that gauge in this plan is open decision 3.
- **ADR-029:** this is the missing resource limit for the simulation path. The
  request-size limits already bound input; nothing currently bounds the compute a
  valid input can start.
- **ADR-010:** no new error type. A permit wait is a suspension, not a failure.
- **Correct-by-construction:** both limits are `PositiveInt` in
  `SimulationConfig`, validated at config load by the existing
  `positiveIntConfig` derivation.
- **`SimulationConfig` class invariant** ("Every field here is read by running
  code") is restored by this plan — it is violated today.

---

## Exact signatures

### New file — `LeafSimulationLimiter`

Placed in `services.cache` beside the only code that uses it.

```scala
package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.configs.SimulationConfig

trait LeafSimulationLimiter {
  def withPermit[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A]
  def available: UIO[Long]
}

object LeafSimulationLimiter {
  val layer: ZLayer[SimulationConfig, Nothing, LeafSimulationLimiter]
  def test(permits: Long): ULayer[LeafSimulationLimiter]
}
```

`Live` is a private final class wrapping one `Semaphore`, created once at layer
construction with `config.maxConcurrentLeafSimulations` permits. There are no
`ZIO.serviceWithZIO` accessor objects: the resolver holds the instance as a
constructor field and calls it directly, so an accessor would have no caller.

### `SimulationConfig` — one field renamed, one added

```scala
final case class SimulationConfig(
  defaultNTrials: PositiveInt,
  defaultTrialParallelism: PositiveInt,
  maxConcurrentLeafSimulations: PositiveInt,
  maxConcurrentLeafSimulationsPerRequest: PositiveInt,
  defaultSeed3: Long,
  defaultSeed4: Long
)
```

`maxConcurrentSimulations` is renamed to `maxConcurrentLeafSimulations`: it
bounds leaf simulations, not node resolutions — portfolios are never simulated,
only combined. Every construction site listed in the file inventory is updated.

### `application.conf`

```hocon
    maxConcurrentLeafSimulations = 4
    maxConcurrentLeafSimulations = ${?REGISTER_MAX_CONCURRENT_LEAF_SIMULATIONS}
    maxConcurrentLeafSimulationsPerRequest = 4
    maxConcurrentLeafSimulationsPerRequest = ${?REGISTER_MAX_CONCURRENT_LEAF_SIMULATIONS_PER_REQUEST}
```

`REGISTER_MAX_CONCURRENT_SIMULATIONS` is removed. It is read by nothing today, so
removing it cannot change any running deployment's behaviour.

### `CachedResultResolverLive` — one constructor field, one parameter threaded

```scala
final case class CachedResultResolverLive(
    cacheScope: CacheScope,
    config: SimulationConfig,
    tracing: Tracing,
    simulationDuration: Histogram[Double],
    trialsCounter: Counter[Long],
    limiter: LeafSimulationLimiter
) extends CachedResultResolver
```

`ensureCached` and `ensureCachedAll` keep their public signatures exactly. Each
creates the request's own semaphore as its first step:

```scala
requestPermits <- Semaphore.make(config.maxConcurrentLeafSimulationsPerRequest.toLong)
```

and passes it down. Four private methods gain one parameter each — all four are
private to this file, so no caller outside it is affected:

```scala
private def distributionForId(tree, hashes, cache, nodeId, seedEntityId, scoped,
                              requestPermits: Semaphore): Task[LossDistribution]
private def distributionOf(tree, hashes, cache, node, seedEntityId, scoped,
                           requestPermits: Semaphore): Task[LossDistribution]
private def rawLeafResult(hashes, cache, leaf, seedEntityId,
                          requestPermits: Semaphore): Task[RiskResult]
private def simulateLeaf(cache, key, leaf, seedEntityId,
                         requestPermits: Semaphore): Task[RiskResult]
```

The single acquisition point is the cache-miss branch of `rawLeafResult`:

```scala
case None =>
  requestPermits.withPermit(limiter.withPermit(simulateLeaf(cache, key, leaf, seedEntityId)))
```

The nesting encodes the acquisition order. The permit is taken **inside** the
`cached match`, so a cache hit never queues.

### `Application.scala`

`LeafSimulationLimiter.layer` is added to the layer list before
`CachedResultResolverLive.layer`, and `CachedResultResolverLive.layer`'s
environment type gains `& LeafSimulationLimiter`.

---

## File inventory

Production sources:

- `modules/server/src/main/scala/com/risquanter/register/services/cache/LeafSimulationLimiter.scala` (new)
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/resources/application.conf`
- `modules/common/src/main/scala/com/risquanter/register/configs/SimulationConfig.scala`

Test sources (every `SimulationConfig` construction site plus the new spec):

- `modules/server/src/test/scala/com/risquanter/register/services/cache/LeafSimulationLimiterSpec.scala` (new)
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/configs/TestConfigs.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/helper/SimulatorSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/PreludeOrdUsageSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/testutil/ConfigTestLoader.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/StubHttpTestHarness.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/DemoSpecSupport.scala`

Configuration and documentation:

- `docker-compose.yml`
- `docs/user/DOCKER-DEVELOPMENT.md`
- `docs/dev/TODO.md`
- `docs/dev/plans/IMPLEMENTATION-PLAN.md`
- `build.sbt`, `.env`, `.env.irmin` (version bump on landing)

---

## Open decisions

### Decision 1 — how the per-request semaphore reaches `simulateLeaf`

**Goal.** The semaphore is created once per public call and must be visible at
one point four call levels down. Pick how it travels.

**Option A — an explicit parameter on the four private methods.** Pros: visible
in every signature, checked by the compiler, and impossible to lose by forking in
the wrong place. Cons: four signatures grow by one parameter, in a file where
they already carry six. How it plays out: a reader of `rawLeafResult` sees where
the budget came from without leaving the file.

**Option B — a `FiberRef` set with `locally` around the whole resolution.** Pros:
no signature changes at all; forked children inherit the value automatically.
Cons: the dependency is invisible in the types, and any future code path that
forks outside the `locally` scope silently gets the default instead of the
request's budget — a bug that compiles and passes most tests. How it plays out: a
later refactor that moves resolution into a separately forked fiber quietly loses
the per-request bound.

**Option C — a resolver instance constructed per request.** Pros: the semaphore
becomes an ordinary field, no threading and no `FiberRef`. Cons: the service
interface must change from "a resolver" to "something that makes a resolver", so
every caller of `CachedResultResolver` changes; this is much larger churn than the
four private parameters it avoids.

**Recommendation (mine): Option A.** The threading is confined to one file, it is
checked by the compiler, and it makes the per-request budget a visible part of the
resolution context rather than ambient state. Option B's failure mode is silent,
which is the worst property a concurrency bound can have.

### Decision 2 — default values for the two limits

**Goal.** Pick the numbers shipped in `application.conf`.

**Option A — both 4** (keeps today's literal). Pros: no change in the number an
operator already sees; conservative against the trial-parallelism multiplication
(4 leaves × 8 trial fibers = 32 runnable fibers). Cons: on a machine with more
than 4 cores a single request cannot use them all.

**Option B — both 8** (matches `REGISTER_TRIAL_PARALLELISM`). Pros: a single
request can saturate a typical 8-core box. Cons: 8 × 8 = 64 runnable fibers, and
the container in `docker-compose.yml` is limited to 2 CPUs and 256 MB — 8
concurrent 10 000-trial simulations is a real memory figure there.

**Option C — process-wide 4, per-request 4, and document the tuning rule.** Same
numbers as Option A, with the relationship ("set both to the same value; raise the
process-wide one only with the core count") written into the operator table.

**Recommendation (mine): Option C.** The current default is 4 and the deployed
container has 2 CPUs, so 4 is already generous; the value of this decision is in
writing down the rule that keeps the two numbers coherent, not in the number.

### Decision 3 — whether to emit a saturation gauge now

**Goal.** `available` exists on the limiter. Decide whether this plan also
publishes it as a metric.

**Option A — expose it now**, as an observable gauge beside
`risk_result.simulation.duration_ms`. Pros: the first question a bound raises is
"is the bound what is making this slow", and only this number answers it. Cons:
an asynchronous gauge is a new telemetry shape in this codebase.

**Option B — ship the bound without the gauge.** Pros: smaller change. Cons: after
landing, a slow response cannot be attributed to queueing versus computation
without adding the metric anyway.

**Recommendation (mine): Option A.** Adding a bound without a way to see it
saturate means the first production question about it is unanswerable. This is
the same reason the limiter is a trait at all.

---

## Verification plan

Unit tests, `LeafSimulationLimiterSpec`:

- With 2 permits, 10 concurrently submitted effects that record entry and exit
  never show more than 2 inside at once.
- A permit is released when the held effect fails.
- A permit is released when the held effect is interrupted.
- `available` reports the free count while permits are held.

Unit tests, `CachedResultResolverSpec`:

- **Bound observed.** A tree of depth 3 whose leaves record concurrent entry into
  simulation, resolved with a 2-permit limiter, never shows more than 2
  simulations running at once. This is the test that fails today.
- **No deadlock.** The same deep tree resolves to completion with a **1-permit**
  limiter. One permit is the tightest possible setting and is the configuration
  that would deadlock if a permit were ever acquired at a portfolio, so this is
  the regression guard for the placement rule.
- **Cache hits do not queue.** With a 1-permit limiter and a pre-warmed cache, a
  tree of many leaves resolves without serialising — the permit is inside the
  miss branch.
- **Figures unchanged.** The same tree resolved with 1 permit and with many
  permits produces identical `TrialOutcomes`. This is the determinism claim: the
  bound changes scheduling only.
- **Per-request limit is per request.** Two resolutions running concurrently each
  reach their own per-request limit rather than sharing one budget.

Integration: the existing `serverIt` suites exercise the resolver through the HTTP
layer with a 2-permit configuration in `HttpTestHarness`; they must stay green
with no assertion changes.

Whole-suite green before reporting done: `commonJVM/test`, `server/test`,
`app/test`, `serverIt/test` (the last one after the `register_it_` network
cleanup).

Version: PATCH bump on landing, mirrored to `.env` and `.env.irmin`.
