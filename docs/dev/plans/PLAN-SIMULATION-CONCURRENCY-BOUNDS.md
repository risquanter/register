# Plan: bound concurrent leaf simulation with two nested limits

**Status:** Direction approved and every decision ruled. Implementation is
deliberately deferred; no source edit is authorized by this document until it is
approved as the session's governing plan. Two other plans land first — see
Sequencing. **Date:** 2026-09-13.
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
as requests. If one request puts 512 entries in the queue and another request
then adds 8, those 8 are served after the 512. Fairness between permits is not
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

Assumptions used throughout, chosen so the arithmetic divides evenly rather than
to be realistic:

- 8-core machine. ZIO's default executor therefore runs 8 worker threads.
- One uncached leaf simulation costs **20 ms** of CPU.
- `maxConcurrentLeafSimulations` (process-wide) = **8**.
- `maxConcurrentLeafSimulationsPerRequest` = **8**.
- Request **A**: a tree with **512 uncached leaves**, submitted at t = 0.
- Request **B**: a tree with **8 uncached leaves**, submitted at t = 1 ms.

### The conservation law that constrains every row

Total work is 520 leaves × 20 ms = 10 400 ms of CPU. Eight threads cannot retire
it in less than 10 400 / 8 = **1 300 ms**. Nothing below removes work or adds a
core, so **the last leaf finishes at 1 300 ms in every case**. No limit makes the
machine faster. What the limits change is the order in which the two requests are
served, and how much memory is live while they are.

Read the table with that in mind: a row where A finishes earlier is a row where B
finished later, never a row that found extra capacity.

| | A finishes | B finishes | All work drained | Peak simulations live |
|---|---|---|---|---|
| Today, no limit | 1 300 ms | 1 300 ms | 1 300 ms | 520 |
| Process-wide limit only | 1 280 ms | 1 300 ms | 1 300 ms | 8 |
| Both limits | 1 300 ms | **40 ms** | 1 300 ms | 8 |

### Case 1 — today, no limit

A forks 512 leaf fibers, all runnable at once, and B's 8 join them. ZIO fibers
yield cooperatively, so all 520 share the 8 threads roughly equally rather than
running in submission order. Each fiber receives 8/520 of a core, so each leaf's
20 ms of work takes 20 × 520 / 8 = **1 300 ms**, and every fiber finishes at
about the same moment — A's last leaf and B's eight alike.

That equality is not an artefact of the model; it is the pathology. B holds 8 of
520 equal shares. B's 160 ms of work would take **20 ms** on an idle machine (8
leaves across 8 threads), and sharing fairly with A stretches it to 1 300 ms — a
factor of 65, set by the size of *A's* tree rather than by anything about B.

Memory: 520 partially built trial maps are live simultaneously, 10 000 trials
each. That peak is the other thing this plan removes.

### Case 2 — process-wide limit only

All 520 fibers call `withPermit`. Eight acquire, the rest queue in submission
order, so all 512 of A's entries sit ahead of all 8 of B's. Permits free 8 at a
time every 20 ms, so the queue drains in waves of 8.

- A occupies waves 0 through 63 and finishes at 64 × 20 = **1 280 ms**.
- B is wave 64: its first leaf starts at 1 280 ms and B finishes at **1 300 ms**.
- Peak live: **8**.

A finished 20 ms earlier than in case 1 and B finished at the same moment.
Nothing was gained overall, and nothing could have been — first-in-first-out
serves A's work before B's where fair sharing interleaved them, and the total
drain time is fixed at 1 300 ms either way. **What this case buys is the memory
peak, 520 simulations down to 8.** That is a real and sufficient reason to want
it.

What it does not buy is B's latency. B waited 1 279 ms to start its first leaf
because 512 entries sat ahead of it in one shared queue. Fairness between permits
is not fairness between requests.

### Case 3 — both limits

A's fibers acquire A's own semaphore first. Eight get through and take all 8
process-wide permits. **A's other 504 fibers wait on A's own semaphore and are
not in the process-wide queue at all.**

B arrives at t = 1 ms. B's semaphore is its own and empty, so all 8 of B's fibers
pass it immediately and enter the process-wide queue — which holds zero waiters,
because A's overflow is parked elsewhere.

- The 8 permits free at t = 20 ms. B takes all eight, runs, and finishes at
  **40 ms** — against 1 300 ms in both earlier cases.
- A yields exactly that one wave and finishes at 65 × 20 = **1 300 ms**.
- Peak live: **8**.

The exchange is the design in one sentence: **A pays 20 ms — one wave out of 65 —
to cut B from 1 300 ms to 40 ms.** Against case 2, A is 20 ms slower. Against
today, A is not slower at all.

Now check the property the per-request limit exists for: **A was never
throttled.** Its own limit (8) equals the process-wide limit (8), so whenever
nobody else wants a permit A holds all eight and runs at full machine speed. The
per-request semaphore did not change A's rate; it changed only how many of A's
fibers were allowed to stand in the shared queue.

That is the design-correctness check, and the reason the two values default to
the same number: **the second limit is a queue-depth limit, not a concurrency
limit.** Setting the per-request one lower would genuinely throttle — at 4, A
would run 4 leaves at a time and finish at 512 / 4 × 20 = **2 560 ms** on an
otherwise idle machine, roughly double, with no gain to anyone.

### Case 4 — what the design deliberately does not solve

20 concurrent requests, each with a per-request limit of 8, against 8
process-wide permits. The process-wide queue can hold 20 × 8 = 160 waiters, so a
newcomer's first leaf waits up to 160 / 8 × 20 = **400 ms**.

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
    caches: ContentCacheRegistry,
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

The file inventory lives in its own document, `PLAN-SIMULATION-CONCURRENCY-BOUNDS-INVENTORY.md`,
which the approval hook reads and only the user writes.

---

## Decisions — all ruled

Every decision this plan opened is ruled. The options and their trade-offs are
kept because the reasoning behind a ruling is what makes it reviewable later.

### Decision 1 — how the per-request semaphore reaches `simulateLeaf`. RULED: A.

**An explicit parameter on the four private methods.** The per-request budget
travels as an ordinary argument rather than as ambient state.

The two rejected options and why. **A `FiberRef` set with `locally` around the
whole resolution** needs no signature changes and forked children inherit the
value, but the dependency is invisible in the types: any future code path that
forks outside the `locally` scope silently receives the default instead of the
request's budget, and that bug compiles and passes most tests. A concurrency
bound that fails silently is the worst shape available. **A resolver instance
constructed per request** makes the semaphore an ordinary field and avoids all
threading, but the service interface has to change from "a resolver" to
"something that makes a resolver", so every caller of `CachedResultResolver`
changes — much larger churn than the four parameters it avoids.

What A costs: four signatures grow by one parameter, in a file where they
already carry six. What it buys: the compiler checks it, and a reader of
`rawLeafResult` sees where the budget came from without leaving the file.

### Decision 2 — default values for the two limits. RULED: C.

**Both 4, and the rule relating them written into the operator table.** The
numbers match today's literal; the substance of this ruling is the rule, not the
value.

Rejected: **both 8** would let one request saturate a typical eight-core
machine, but eight leaves times eight trial fibers is sixty-four runnable
fibers, and the deployed container is limited to two CPUs and 256 MB — eight
concurrent ten-thousand-trial simulations is a real memory figure at that size.
**Both 4 without the rule** ships the same numbers while leaving an operator no
way to know how to change them coherently.

**Outstanding measurement, which is a task of this plan and not a decision.**
The tuning rule is phrased in terms of available cores, and
`Simulator.scala:19` reads `Runtime.availableProcessors()`. What that returns
inside a GraalVM native image running under a Docker CPU quota is unverified: if
it reports the host's core count rather than the quota, a rule phrased in cores
is wrong and must be phrased in the configured quota instead. **The rule text is
written after this is measured, not before.** Build the image, run it under the
compose CPU limit, print the value, and record it here:

| Environment | `availableProcessors()` | Configured quota |
|---|---|---|
| JVM, host | | n/a |
| Native image, `cpus: '2'` | | 2 |

### Decision 3 — whether to emit a saturation gauge now. RULED: A.

**Publish it now**, as a gauge beside the existing simulation instruments.
Adding a bound without a way to see it saturate makes the first question about
it — "is the bound what is making this slow?" — unanswerable. That is the same
reason the limiter is a trait at all.

**The gauge's shape is settled by existing convention, not open.**
`CachedResultResolverLive` already creates instruments from the injected
`Meter`, and four conventions are visible in how it does so: dotted lowercase
`subsystem.area.measurement` names with the unit as a suffix where it
disambiguates; a unit and description always supplied, `"1"` for a dimensionless
count; instruments created once at layer construction and passed into the
constructor rather than created per call; and attributes attached per recording
rather than baked into the instrument. The gauge follows all four:

```scala
val permitsAvailable     = "risk_result.simulation.permits_available"
val permitsAvailableUnit = "1"
val permitsAvailableDesc = "Leaf-simulation permits currently free"
```

with an attribute distinguishing the process-wide limiter from the per-request
one. No new dependency and no new layer: the `Meter` is already in this file's
layer requirements.

**Two things this ruling depends on, both routed.** The gauge needs a backend
that can be read over time, which `PLAN-TELEMETRY-EXPORT` supplied: the
application now wires `TelemetryLive.configured`, which defaults to the OTLP
exporter and sends to the collector, and the collector republishes metrics in
Prometheus format on port 8889. That prerequisite is met. And no decision record
governs metric naming; the convention above exists only as code. That gap is
recorded as TODO item 50 and is not a prerequisite for this plan.

## Sequencing

One plan still lands before this one. A second already has.

**`PLAN-TELEMETRY-EXPORT` — landed in 0.10.37, nothing to wait for.** Decision 3
publishes a saturation gauge, and Decision 2's tuning rule is written from what
that gauge shows. Reading it needs metrics to reach a backend, which that plan
delivered.

`PLAN-IRMIN-RECURSIVE-READ` and `PLAN-NGINX-WORKSPACE-ROUTING` share no files
with this plan and have no ordering relationship to it.

This plan's signatures name the renamed type:
`CachedResultResolverLive`'s layer requires `ContentCacheRegistry`, and its
cache lookups read `caches.forWorkspace(...)`.

---

## Reference markers — every site that points at this plan

While this plan is unimplemented, several comments and documents describe a
limit that does not exist yet and would mislead a reader who did not know a fix
was designed. Each such site therefore carries a pointer to this document.

This overrides the normal rule that comments never name planning documents. The
override is what makes the markers removable: they are all spelled the same way,
so one search finds every one of them, and removing them is a required step of
this plan rather than something a later reader has to notice.

**The marker.** Every site contains this literal string, followed by the plan's
path and one sentence saying what changes when the plan lands:

```
PLAN-REF(SIMULATION-CONCURRENCY-BOUNDS)
```

**Finding every site:**

```bash
grep -rn 'PLAN-REF(SIMULATION-CONCURRENCY-BOUNDS)' . \
  --exclude-dir=target --exclude-dir=node_modules --exclude-dir=.git
```

**The sites, and what each becomes when the plan lands:**

| Site | Marker says | On landing |
|---|---|---|
| `modules/common/src/main/scala/com/risquanter/register/configs/SimulationConfig.scala` | `maxConcurrentSimulations` is read by nothing | Marker deleted; the scaladoc describes the two fields that are now read |
| `docker-compose.yml` | `REGISTER_MAX_CONCURRENT_SIMULATIONS` controls nothing | Marker deleted; the variable is renamed and a second one added |
| `docs/user/DOCKER-DEVELOPMENT.md` | the operator table row controls nothing | Marker deleted; the row documents the two live variables and the tuning rule |
| `docs/dev/TODO.md` items 47 and 48 | the fan-out is unbounded, fix designed | Item 48 closed; item 47's cross-reference keeps the plan path without the marker |
| `docs/dev/plans/IMPLEMENTATION-PLAN.md` | the fan-out note | Marker deleted; the note states the bound as implemented |

A site added later must use the same marker, and must be added to this table in
the same change.

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

### Mandatory final step — remove every reference marker

This plan is not complete while any marker survives. The last step, after the
suite is green and before the plan is reported done:

1. Run the search from "Reference markers" above.
2. For each hit, apply the "On landing" column of that table — the marker line is
   deleted and the surrounding text is rewritten to describe the bound as it now
   is, with no reference to this document.
3. Re-run the search. **Zero hits is the acceptance condition.** A non-zero
   result means a comment still tells the reader to consult a plan for behaviour
   the code already has, which is exactly the state the normal comment rule
   exists to prevent.
4. Confirm the suite is still green after the comment edits.
