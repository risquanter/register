# TODO

Open issues, observations, and investigation notes.
Items are descriptive — they document symptoms and current understanding,
not prescribed solutions.

---

## 1. "Will retry" banner text has no backing implementation

**Observed:** When a network error occurs (e.g. `TypeError: NetworkError when
attempting to fetch resource.`), the global error banner appends " — will retry"
to the message. No retry ever happens — the message is misleading.

**Current understanding:** `GlobalError.NetworkError` carries a `retryable: Boolean`
field. `GlobalError.fromThrowable` classifies Fetch API failures and
`java.io.IOException` as `retryable = true`. `ErrorBanner` renders the
" — will retry" hint when that flag is true. However, no code in `modules/app/`
acts on the flag — there is no `Schedule`, no exponential backoff, no automatic
re-dispatch. A search of the entire app source tree for `retry`, `Schedule.recurs`,
`Schedule.exponential`, and `retryN` found nothing relevant. The `TreeListView`
has a manual "Retry" button for its own `LoadState.Failed`, but that is a
separate, unrelated mechanism.

The `retryable` flag is currently a classification-only hint with no consumer.

---

## 2. Dual error display with inconsistent formatting on the same exception

**Observed:** When a query (or any `loadInto`-based API call) fails, the same
exception surfaces in two places simultaneously:

1. **Global error banner** (top of page) — via `ErrorObserver.onError` in
   `forkProvided` → `GlobalError.fromThrowable` → `ErrorBanner`. The message
   has browser prefixes stripped (`"TypeError: "`, `"Error: "`) and, for
   retryable errors, `" — will retry"` appended.

2. **Per-view inline error** (e.g. `QueryResultCard`, `LECChartView`) — via
   `LoadState.Failed(e.safeMessage)` set in `loadInto`'s `tapError`. The
   message is the raw `safeMessage` with no stripping or hints.

This produces two visually different error messages for the same failure.

**Current understanding:** The duplication is structural — `loadInto` sets the
per-view `LoadState.Failed` in its `tapError`, and then `forkProvided` fires
the global `ErrorObserver` as a separate concern. Both channels process the
same `Throwable` independently with different formatting pipelines
(`safeMessage` vs `GlobalError.fromThrowable` → `msg()` + hint).

This may be a hint at latent code / functionality duplication between the
per-view error path and the global error path. The two formatting pipelines
(`safeMessage` in `ZJS.loadInto` vs `GlobalError.msg()` in `fromThrowable`)
diverge silently — the global path strips prefixes and adds context; the
per-view path does not. Whether these should converge, or whether one channel
should suppress when the other is active, is an open design question.
