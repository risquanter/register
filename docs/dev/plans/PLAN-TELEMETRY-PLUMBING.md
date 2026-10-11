# PLAN-TELEMETRY-PLUMBING

Plan code `TP`. Decisions in this document are labelled `TP-D-<n>`.

**Status.** Deliberately postponed. Every decision is open and is meant to stay
open until this plan is picked up as work of its own. Nothing here is authorized
by the approval token for `PLAN-ERROR-WIRE-BOUNDARY.md`, and nothing here blocks
that plan.

**Scope: Scala code in this repository, and nothing else.** This plan makes the
application's own telemetry handling internally consistent. It does not concern
what consumes the output — no collector, no mesh configuration, no deployment
topology, no exporter destination. Those are `register-infra`'s, recorded in that
repository's `docs/TODO.md` under the telemetry responsibility boundary. This plan
neither waits on them nor argues about them.

**This plan is a research task before it is an implementation task, and its own
framing is in scope for revision.** When it is picked up: re-examine whether each
question below is the right question, and whether the options listed are the real
options — not only which option to pick. The current framing was written while
looking at one part of the code, and a decision framed wrongly produces a correct
answer to the wrong question.

---

## 1. The internal inconsistency

The application has **two request-context mechanisms. One works and one is dead,
and the dead one is the one the error boundary needs.**

### 1.1 The live mechanism: zio-opentelemetry `Tracing`

`TracingLive.span` wraps an effect in a span, and it is genuinely in use at five
sites:

- `QueryServiceLive.scala:40`
- `RiskTreeServiceLive.scala:227`
- `CachedResultResolverLive.scala:64` and `:212`
- `AuthorizationServiceSpiceDB.scala:99`

So spans are opened, nested, and carry attributes. The library propagates its own
context through the ZIO fiber.

### 1.2 The dead mechanism: `RequestContext`

```scala
final case class RequestContext(
  requestId: String,
  traceId: Option[String] = None,
  spanId: Option[String] = None,
  userId: Option[String] = None
)
```

It provides `layer`, `generate`, `get`, `set`, `withContext` and `withGenerated`.
**None of them is called anywhere in the server's main source.** The only
references are the declarations themselves and one test. So the `FiberRef` is
never populated, every field is permanently unset, and the type is dead code that
reads as working infrastructure.

### 1.3 Why that matters now

`PLAN-ERROR-WIRE-BOUNDARY.md` rules that every `WireError` carries a `TraceId`,
and it retypes `RequestContext` so no field can be silently absent — filling what
it cannot yet supply from named placeholder constants. That closes the type-level
defect and deliberately leaves the behavioural one: the value is a placeholder
because nothing in the application produces a real one into that structure.

The question this plan answers is therefore internal and has nothing to do with
what consumes the output: **the application opens spans through one mechanism and
would report identifiers through another.** Making them one thing is the work.

---

## 2. Open decisions

### TP-D-1 — does `RequestContext` survive at all — OPEN

**Goal.** Decide whether the application keeps a second context structure beside
the one the tracing library already propagates.

**Option A — delete `RequestContext` and read the identifiers from the active
span.** The library already tracks the current span through the fiber, so its
trace and span identifiers are derivable wherever an error is projected. One
mechanism, no synchronisation problem, and no structure that can hold a value
disagreeing with the span actually open.

To confirm before ruling: which accessor zio-opentelemetry's `Tracing` exposes for
the active span context, and whether it is reachable from the point where
`WireError.fromAppError` runs. The API has not been read; the option is stated as
a direction, not as a signature.

**Option B — keep `RequestContext` and populate it from the span.** It stays as
the application's own record, with its trace and span identifiers copied from the
active span when it is established.

Two facts can then disagree — the copied identifier and the span actually open —
and nothing detects it. It also keeps `requestId`, which is the one value the
library does not provide.

**Option C — keep `RequestContext` for `requestId` only.** It holds the
application's own per-request identifier and nothing else; trace and span
identifiers come from the library at the point of use.

The question under all three: is a separate per-request identifier worth having
when a trace identifier already exists per request? That is a framing question
and is part of what this plan revisits.

### TP-D-2 — what establishes the context per request — OPEN

**Goal.** Decide where a request acquires whatever `TP-D-1` leaves standing.

Candidates are a Tapir interceptor, a wrapper applied in `BaseController`, or the
existing `wireEither` extension that `PLAN-ERROR-WIRE-BOUNDARY.md` introduces —
which already runs once per controller method and already touches the error path.

The constraint that decides it: the identifier reported in an error must be the
identifier under which that request's work was traced. A mechanism that
establishes the context later than the first span is opened cannot satisfy that.

### TP-D-3 — how the error projection obtains the identifier — OPEN

**Goal.** Decide how `WireError.fromAppError` gets a `TraceId`.

It is a pure function today, which is why the error-boundary plan gives it a
placeholder rather than a real value. Making it carry a real one means either
threading the identifier in as a parameter, or making the projection an effect
that reads the context.

**Option A — pass it in.** `fromAppError(e, trace)`, called from `wireEither`,
which is already effectful and already per-request. The projection stays pure.

**Option B — make the projection effectful.** It reads the context itself.

Option A keeps the single auditable projection function pure, which is the
property that makes the dropped-field list reviewable in one screen. That is
worth weighing against the extra parameter at one call site.

### TP-D-4 — whether log lines carry the identifier — OPEN

**Goal.** Decide whether every log line inside a request carries the identifiers
automatically.

ADR-002 governs observability. An identifier that reaches a client but appears on
no log line correlates nothing within this application's own output, which is the
only correlation this plan is responsible for.

**Option A — `ZIO.logAnnotate` applied where the context is established**, so
every line inside the request carries it without each call site naming it.

**Option B — annotate at the sites that matter**, which is the current practice
for workspace and tree identifiers.

### TP-D-5 — what becomes of the placeholder constants — OPEN

**Goal.** Decide whether `TelemetryPlaceholders` is deleted when this plan
completes, or kept for tests to assert against.

**Option A — delete them.** Once every site supplies a real identifier they have
no caller, and an unused public value invites reuse.

**Option B — keep them in test scope only.** The completeness test in §4 needs a
value to compare against. Whether `common`'s test configuration makes that clean
is the thing to check before ruling.

### TP-D-6 — what a span covers — OPEN

**Goal.** Decide whether the five existing span sites are the right set.

They were added per service as those services were built, so the set is a
consequence of history rather than a design. A request currently has no span of
its own: the outermost span is whichever service method runs first, so two
sibling service calls in one request produce two unparented spans rather than two
children of one request span.

Whether that is worth a request-level span is the decision, and it is entirely a
question about this application's own trace shape.

---

## 3. Scope

**Owned here:** whether `RequestContext` survives and in what form, what
establishes it, how the error projection obtains an identifier, log annotation,
the span set, and deleting the placeholders.

**Not owned here, and not a blocker:** anything about what consumes the output.
The shipped value of `register.telemetry.otlpEndpoint`, whether a collector
exists, whether the mesh emits spans, deployment topology. Register's development
environment is Docker Compose as it currently stands, and this plan proposes no
change to it.

---

## 4. Verification plan

Written with the file inventory, once `TP-D-1` decides whether the work touches
`RequestContext`, the tracing layer, or both.

One property already belongs in it: **no site passes a placeholder identifier.**
That assertion is what distinguishes this plan from partially applied, because the
placeholders exist precisely to be the inventory of sites this plan must reach.

A second property follows from `TP-D-2`'s constraint: the identifier a `WireError`
carries equals the identifier of the span under which the failing work ran. A test
that opens a span, fails inside it, and compares the two is what pins that.

---

## 5. File inventory

Written once the decisions are ruled.
