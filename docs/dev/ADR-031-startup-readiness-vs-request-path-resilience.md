# ADR-031: Startup Dependency Readiness vs Request-Path Resilience

**Status:** Accepted
**Date:** 2026-07-09
**Tags:** resilience, startup, lifecycle, zio-schedule, service-mesh

---

## Context

- A service mesh provides request-path resilience only for traffic it can observe; it cannot cover the window in which its own policies (NetworkPolicy, HBONE routes) are still being reconciled — a process booting inside that window sees an unreachable dependency
- A process that hard-fails on the first probe of a still-booting dependency converts a benign ordering race into a `CrashLoopBackOff`
- An unbounded wait masks genuine misconfiguration; only a bounded, fail-closed wait lets the orchestrator distinguish "slow" from "broken"
- Under jittered exponential backoff, an attempt count maps to a *random* total duration — a requirement stated in elapsed time can only be guaranteed by an elapsed-time bound
- A readiness probe returning `Boolean` destroys the failure cause exactly where an operator diagnosing a crash loop needs it

---

## Decision

### 1. The Boundary Test

One question routes every retry loop:

> Does the loop protect an **individual in-flight request** during serving, or does it gate the **process's lifecycle transition** on a dependency becoming reachable?

| Concern | Owner | Mechanism |
|---------|-------|-----------|
| Request retries, circuit breaking, per-request timeouts | Istio (ADR-012 §4) | `VirtualService`, `DestinationRule` |
| Startup dependency readiness | Application bootstrap | `StartupReadiness.awaitReady` |
| Liveness / restart policy | Kubernetes | probes, `restartPolicy` |

A startup readiness gate is permitted in Scala **iff** it is: **(a) bounded** by a total budget, **(b) fail-closed** — exits after the budget so the orchestrator restarts, **(c) boot-only** — runs once per process start, **(d) confined to lifecycle wiring** (`ZLayer` construction), never inside a request handler.

### 2. The Policy Is One Composed Schedule

Each clause of the requirement is one combinator; the composition *is* the specification:

```scala
def schedule(budget: Duration, backoffBase: Duration, backoffCap: Duration) =
  (Schedule.exponential(backoffBase).jittered   // growing, jittered gaps (no thundering herd)
    || Schedule.spaced(backoffCap))             // union = min delay: caps the gap
    && Schedule.upTo(budget)                    // intersection: stop once elapsed > budget
```

The bound is **elapsed time, not attempt count**: jitter makes `recurs(n)`'s total duration a random variable, while `upTo(budget)` transcribes "keep trying for ~45s, then die" exactly. Backoff internals (base, cap) are code constants; only the budget is configuration.

### 3. Not-Ready Is a Typed Failure, Not a Boolean

`retry` operates on the error channel. The probe keeps its typed error so the failure after the budget carries the real cause:

```scala
// Probe: ready = success, not-ready = typed error with cause
def healthCheck: IO[IrminError, Unit]

// Gate: no Boolean inspection, no re-manufactured exception
StartupReadiness.awaitReady(
  name           = s"irmin (${cfg.url})",
  probe          = client.healthCheck,
  attemptTimeout = cfg.healthCheckAttemptTimeout,  // hung probe = not-ready
  budget         = cfg.healthCheckBudget
)
```

### 4. Config Contract: Positive Durations

Readiness bounds are `Duration` fields (HOCON duration syntax, `WorkspaceConfig` precedent), validated positive at load:

```hocon
irmin {
  healthCheckAttemptTimeout = 5s    # per-attempt probe bound
  healthCheckAttemptTimeout = ${?IRMIN_HEALTHCHECK_ATTEMPT_TIMEOUT}
  healthCheckBudget = 45s           # total bounded wait, then fail closed
  healthCheckBudget = ${?IRMIN_HEALTHCHECK_BUDGET}
}
```

Size the budget above the platform's policy-reconciliation window (ArgoCD sync + mesh programming), not above the dependency's worst-case boot time.

---

## Code Smells

### ❌ Unbounded Startup Wait

```scala
// BAD: waits forever — orchestrator cannot distinguish slow from broken
probe.retry(Schedule.exponential(500.millis))

// GOOD: bounded and fail-closed — budget elapsed ⇒ process exits ⇒ restart
probe.retry(Schedule.exponential(500.millis).jittered && Schedule.upTo(budget))
```

### ❌ Boolean Probe with Cause Erasure

```scala
// BAD: cause destroyed, then a causeless error re-invented
def healthCheck: IO[IrminError, Boolean] =
  query.as(true).catchAll(_ => ZIO.succeed(false))
healthCheck.flatMap(ok => if ok then ZIO.unit else ZIO.fail(RuntimeException("returned false")))

// GOOD: error channel carries the real cause through retry to the final failure
def healthCheck: IO[IrminError, Unit] = query.unit
healthCheck.retry(policy)
```

### ❌ Attempt-Count Bound Under Jittered Backoff

```scala
// BAD: total wait is a random variable — cannot guarantee a time window
probe.retry(Schedule.exponential(500.millis).jittered && Schedule.recurs(12))

// GOOD: the time requirement is bounded in time
probe.retry(Schedule.exponential(500.millis).jittered && Schedule.upTo(45.seconds))
```

### ❌ Readiness Logic in a Request Handler

```scala
// BAD: request-path retry in Scala — this is the mesh's job (ADR-012 §4)
def getTree(id: TreeId) = repo.get(id).retry(Schedule.recurs(3))

// GOOD: handler fails fast; VirtualService owns request retries
def getTree(id: TreeId) = repo.get(id)
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `infra/StartupReadiness.scala` | `schedule` composition + `awaitReady` gate |
| `IrminClient.healthCheck` | Typed-error probe (`IO[IrminError, Unit]`) |
| `Application.irminHealthCheck` | Gate in `ZLayer` lifecycle wiring |
| `HttpTestHarness` (server-it) | Same gate — no duplicated retry logic |
| `IrminConfig` | Positive-validated `Duration` bounds |

## References

- [ADR-012 §4](./ADR-012.md) — request-path resilience delegated to Istio
- [ZIO Schedule](https://zio.dev/reference/schedule/) — composable retry policies
