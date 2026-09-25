# Fix note: ADR-008 citation sweep

**Status:** Awaiting approval  
**Kind:** Review-driven hygiene fix — comments only, no behaviour change  
**Inventory:** `docs/dev/plans/PLAN-ADR-008-CITATION-SWEEP-INVENTORY.md`

---

## Why

`ADR-008-proposal.md` is deleted. Its retry schedule and circuit breaker
contradicted ADR-012 §4, its degraded mode and SSE reconnection were never
built, its `RiskAppError` enum is superseded by the sealed `AppError` hierarchy,
and the two-tier error-display pattern it gestured at is now ADR-019 Pattern 7.

Six comments across five files still cite it. Each is repointed at the record
that owns the statement it sits next to. Two of them also carry plan-phase
references (`Option A`, `Phase I.a`, `Phase B`), which the comment-style rule
forbids regardless of this sweep, so they go in the same pass.

Nothing executable changes. No signature, type, endpoint, DTO or serialization
shape is touched.

---

## Exact edits

### 1. `modules/common/.../domain/errors/AppError.scala:103`

```scala
// Infrastructure Errors (ADR-008: Error Handling & Resilience)
```
becomes
```scala
// Infrastructure Errors (ADR-010: Error Handling Strategy)
```

### 2. `modules/common/.../domain/errors/ErrorResponse.scala:192`

```scala
  /** Exhaustive match on IrminError — compiler-enforced coverage (ADR-008).
    * BranchAlreadyExists/BranchHeadStale (Phase B CAS results) are expected
```
becomes
```scala
  /** Exhaustive match on IrminError — compiler-enforced coverage (ADR-035).
    * BranchAlreadyExists and BranchHeadStale, the compare-and-set outcomes,
    * are expected
```

The exhaustiveness guarantee is ADR-035's, not ADR-010's, and `Phase B` is a
plan reference the comment rule bans.

### 3. `modules/common/.../domain/errors/ErrorResponse.scala:280`

```scala
  // ── Infrastructure Error Responses (ADR-008) ───────────────────────────────
```
becomes
```scala
  // ── Infrastructure Error Responses (ADR-010) ───────────────────────────────
```

### 4. `modules/common/.../domain/errors/ErrorResponseSpec.scala:132`

```scala
      // Infrastructure errors (ADR-008)
```
becomes
```scala
      // Infrastructure errors (ADR-010)
```

### 5. `modules/app/.../state/GlobalError.scala:14` and `:23`

```scala
  * Per-view errors should NOT be duplicated here — the ErrorBanner supplements,
  * it does not replace, the existing inline error display (ADR-008 / Option A).
```
becomes
```scala
  * Per-view errors are not duplicated here — the ErrorBanner supplements the
  * inline error display, it does not replace it (ADR-019 Pattern 7).
```

and

```scala
  * @see ADR-008 (error handling & resilience)
  * @see ADR-010 (accepted error handling strategy)
```
becomes
```scala
  * @see ADR-019 Pattern 7 (two-tier error presentation)
  * @see ADR-010 (error handling strategy)
```

### 6. `modules/app/.../views/ErrorBanner.scala:11` and `:19`

```scala
  * This is the "safety net" component from Phase I.a / Option A:
  * it handles errors that have no per-view handler (e.g. health-check
  * failure, future workspace auth errors, SSE disconnection).
```
becomes
```scala
  * It handles errors that have no per-view handler — a failed health check,
  * an expired workspace, a dropped SSE connection.
```

and

```scala
  * @see ADR-008 / ADR-010 for error handling strategy
```
becomes
```scala
  * @see ADR-019 Pattern 7 / ADR-010 for error handling strategy
```

---

## Verification

The diff contains no executable code, so the bar is that every touched module
still compiles:

```bash
sbt 'commonJVM/compile; commonJVM/Test/compile; app/compile'
```

Then confirm nothing still cites the deleted record:

```bash
grep -rn "ADR-008" --include=*.scala modules/
```

---

## ADR alignment

- **ADR-019** — the pattern the frontend comments now cite, added as Pattern 7.
- **ADR-010** — owns the `AppError` hierarchy the infrastructure-error comments sit beside.
- **ADR-035** — owns the compiler-enforced exhaustiveness the `ErrorResponse` comment claims.
- **Comment style** — plan-phase references removed, no new provenance introduced.

## Open decisions

None.
