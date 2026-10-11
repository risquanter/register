# PLAN-TELEMETRY-PLUMBING

Plan code `TP`. Decisions in this document are labelled `TP-D-<n>`.

**Status.** Deliberately postponed. Every decision here is open and is meant to
stay open until this plan is picked up as work of its own. Nothing in it is
authorized by the approval token for `PLAN-ERROR-WIRE-BOUNDARY.md`, and nothing
in it blocks that plan — that plan lays typed groundwork and marks what it cannot
fill, which is the whole of its telemetry scope.

**This plan is a research task before it is an implementation task, and its own
framing is in scope for revision.** The decisions below were written from one
repository's point of view, and this subject spans two. So when it is picked up:
re-examine whether each question is the right question, whether the options
listed are the real options, and whether the boundary this document draws between
application and infrastructure is where it belongs — not only which option to
pick. A decision framed wrongly in a cross-repository subject produces a correct
answer to the wrong question, and that is the specific failure to guard against
here. Treat everything below as a starting point for research rather than a
settled agenda.

**Scope boundary.** This plan owns register's own instrumentation: the identifiers
it carries, the spans it opens, what it exports. It does not own whether a
collector exists, whether the mesh emits spans, or what an endpoint resolves to in
a cluster — those are `register-infra`'s, tracked in that repository's
`docs/TODO.md` under the telemetry responsibility boundary recorded there on
2026-10-11. **Register targets what Docker Compose supports for local development
and treats that as sufficient.** The absence of cluster observability is not a
defect of this repository and is not a blocker for anything here.

**Why it is separate.** `PLAN-ERROR-WIRE-BOUNDARY.md` needs a correlation
identifier on every client-facing error, and it lays the typed groundwork for
one: `RequestContext` loses its raw `String` fields and its `Option`s, and the
values it cannot yet supply are filled from a named dummy constant. That is
deliberately the whole of its telemetry scope. Making those identifiers carry
real values means connecting them to the tracing layer, which is a different
subject with its own unanswered questions, and this plan owns it.

---

## 1. Current state

### 1.1 The tracing layer works and is not connected to the request context

`modules/server/src/main/scala/com/risquanter/register/telemetry/TracingLive.scala`
is 219 lines and complete in itself. It builds an OpenTelemetry resource,
provides a tracer either to standard output or over the OpenTelemetry Protocol
depending on `TelemetryConfig`, and exposes `span`, three `setAttribute`
overloads and `addEvent`.

`RequestContext` appears nowhere in it. The two files do not refer to each other
in either direction.

### 1.2 The request context holds one invented identifier and two empty fields

`modules/server/src/main/scala/com/risquanter/register/telemetry/RequestContext.scala`:

```scala
final case class RequestContext(
  requestId: String,
  traceId: Option[String] = None,
  spanId: Option[String] = None,
  userId: Option[String] = None
)
```

`RequestContext.generate` sets `requestId` to `UUID.randomUUID().toString` and
leaves the rest at their defaults. A search of the whole server module finds no
assignment to `traceId` or `spanId` anywhere: the only occurrences are the
declarations above and their scaladoc. So the service carries a locally invented
identifier that no tracing backend knows, beside two empty slots for the
identifiers that would resolve in one.

The context is propagated correctly — `layer` makes a `FiberRef`, `withContext`
uses `locallyScoped` so child fibers inherit it. The propagation is sound and the
payload is not.

### 1.3 What `PLAN-ERROR-WIRE-BOUNDARY.md` changes, and what it leaves

That plan retypes the record and removes the optionality, so a missing value
becomes a compile error rather than a silent `None`:

```scala
final case class RequestContext(
  requestId: RequestId,
  traceId:   TraceId,
  spanId:    SpanId,
  userId:    UserId
)
```

`UserId` already has an `Anonymous` case, so the absent user is expressed by a
value rather than by `Option`. `traceId` and `spanId` are filled from named dummy
constants, because nothing in the system can supply a real one until this plan
lands. Those two constants are the marker for the work below: every call site
that still passes one is a site this plan must reach.

### 1.4 The standing rule the foundation carries

The typed identifiers are the foundation, and a foundation decays unless touching
a file obliges you to build on it. So two permanent obligations accompany it,
written where the unused-import rule is written and binding the same way:

1. **Any file opened for any reason stops carrying an untyped telemetry
   identifier.** A `String` holding a trace identifier, a span identifier or a
   request identifier becomes `TraceId`, `SpanId` or `RequestId`. An
   `Option` standing in for "we do not have one" becomes the value that says so —
   `UserId.Anonymous` for an absent user, a named placeholder for an identifier
   the plumbing cannot yet supply. This is the same obligation as removing an
   unused import: done in the same pass, without asking, and listed in the
   turn's output.
2. **A placeholder is never introduced at a new site.** The placeholders exist to
   mark the sites this plan must reach, so adding one elsewhere destroys the only
   inventory of that work. A new site that needs a real identifier and cannot get
   one is a reason to escalate, not to reach for the placeholder.

The second rule is what keeps the first honest. Without it the typed structure
spreads while every value in it stays fake, which is the state the current
`traceId: Option[String] = None` is already in.

**Where the rules are written.** Both go in `~/.claude/CLAUDE.md` beside the
unused-import rule, which is the user's own file, so this plan proposes the
wording rather than writing it. The project-level statement goes in the
`adr-constraints` skill and its byte-identical mirror, because that is the file
read before new types are designed. A review criterion goes in the
`code-quality-review` skill and its mirror, beside the existing passes on
primitive typing — an untyped identifier or a newly introduced placeholder is a
finding there, not a matter of taste.

`TP-D-4` below settles whether the placeholder constants are deleted when this
plan completes, because once no site passes one their only remaining use is to
be asserted against.

### 1.5 The cross-repository half, which this plan does not own

Checked in `register-infra` at `d66b5a8` on 2026-10-11, recorded there in
`docs/TODO.md`, and owned there. Repeated here only because the decisions below
depend on it:

- The mesh configures no tracing, so no `traceparent` header reaches register
  and there is no incoming trace to join.
- No collector is deployed. The `observability` namespace is a reservation with
  nothing in it.

Neither is a defect of this repository. Both are items on `register-infra`'s
list, and the decisions below cannot be settled before those items are — which
is part of why this plan is postponed rather than ruled.

One item here is this repository's, and it is the only infrastructure-adjacent
thing this plan owns: `modules/server/src/main/resources/application.conf` sets
`register.telemetry.exporter = "otlp"` with
`otlpEndpoint = "http://localhost:4317"`, so a deployment exports spans to a port
where nothing listens unless an operator overrides it. Whether the shipped
default should instead be the console exporter — which `TracingLive` already
provides — is `TP-D-5`.

---

## 2. Open decisions

### TP-D-1 — where the trace identifier enters the request — OPEN

**Goal.** Decide what populates `traceId` and `spanId`, and at which boundary.

A browser request may arrive already carrying a `traceparent` header, because the
service sits behind a mesh that can start a trace of its own. Whether register
joins that trace or starts a new one determines whether a user-visible identifier
resolves to one span or to an orphan.

**Option A — adopt an incoming `traceparent`, generate one when absent.** A Tapir
interceptor reads the W3C Trace Context header, parses it into `TraceId` and
`SpanId`, and starts a new context only when the header is missing or malformed.

**Option B — always start a new trace in register.** Ignore any incoming header.

**Option C — read the identifiers from the tracing layer's active span.** Let
`TracingLive` own trace creation entirely and have `RequestContext` observe
whatever span is current, rather than parsing headers itself.

**Premise, now checked (§1.5): no header arrives.** The mesh configures no
tracing, so Option A and Option B produce identical behaviour today. They differ
only in what happens when the mesh is configured later: Option A joins that trace
automatically, Option B silently keeps starting its own and produces an orphan
span for every request. The choice is therefore about which default is correct
when the infrastructure changes, not about present behaviour, and Option A is
the one that needs no revisit.

### TP-D-2 — whether the identifier shown to a person is the trace or a request identifier — OPEN

**Goal.** Decide which value a client-facing error carries, now that
`RequestContext` can hold both.

`RequestId` is generated by register and is unique to one HTTP request.
`TraceId` is the W3C value and spans every service that handled it. A person
reporting a failure quotes one of them.

**Option A — the trace identifier.** It resolves in the collector and reaches
every span, including the Irmin call underneath the failure.

**Option B — the request identifier.** It resolves in register's own logs with no
tracing backend deployed, which is the state of a local stack.

**Option C — both, with the trace identifier primary.**

**Premise, now checked (§1.5): no collector is deployed, so the trace identifier
resolves nowhere today.** The request identifier does resolve, in register's own
log output, provided `TP-D-3` rules that it is annotated there.

That changes the shape of this decision. Option A is right for the system that
will exist and useless for the one that exists now. Option B is the reverse.
Option C is the only one that is useful at both points in time, and it costs one
extra field on a type that already carries both values — `RequestContext` holds
`requestId` and `traceId` either way, so Option C adds nothing to collect and
only a second field to the wire case.

`EWB-D-8` in `PLAN-ERROR-WIRE-BOUNDARY.md` already ruled that a `WireError`
carries the trace identifier. **That ruling stands and is not reopened by this
plan.** It is recorded here only so that whoever picks this plan up knows the
adjacent ruling exists and knows the fact that postdates it — that the trace
identifier resolves nowhere until the infrastructure items land. Whether a second
field is wanted alongside it is one of the framing questions this plan revisits
when it is worked, not a question for the error-boundary plan to answer.

### TP-D-3 — whether the identifier is logged as an annotation on every line — OPEN

**Goal.** Decide whether log lines carry the identifiers automatically.

ADR-002 governs observability. A correlation identifier that reaches a client but
appears on no log line correlates nothing, so this decision and `TP-D-2` are a
pair: whichever value a person quotes must be the value the logs carry.

**Option A — a `ZIO.logAnnotate` wrapper applied where the context is set**, so
every line inside a request carries the identifiers without each call site naming
them.

**Option B — annotate at the sites that matter**, which is the current practice
for workspace and tree identifiers.

### TP-D-4 — what becomes of the placeholder constants — OPEN

**Goal.** Decide whether `TelemetryPlaceholders` is deleted when this plan
completes, or kept as the thing tests assert against.

**Option A — delete them.** Once every site supplies a real identifier the
constants have no caller, and an unused public value is a liability: nothing
stops a future contributor reaching for one.

**Option B — keep them, visible only to tests.** The completeness test named in
§5 asserts that no site passes a placeholder, and that test needs the value to
compare against.

The tension is real and small: Option A removes the temptation, Option B keeps
the proof. A third shape — keep them in test scope only — gets both, and whether
`common`'s test configuration makes that clean is the thing to check before
ruling.

### TP-D-5 — the exporter endpoint that points at nothing — OPEN

**Goal.** Decide what `register.telemetry.otlpEndpoint` should be, given §1.5.

It defaults to `http://localhost:4317` with `exporter = "otlp"`, so every
deployment exports spans to a port on its own pod where nothing listens. A local
developer stack has nothing there either.

**Option A — deploy a collector into the `observability` namespace** that the
infrastructure repository already reserves, and point the endpoint at its
service. This is work in `register-infra`, not here.

**Option B — default the exporter to `"console"`** so a deployment without a
collector writes spans to its own logs, and let the OpenTelemetry Protocol
endpoint be set only where a collector exists. `TracingLive` already provides a
standard-output tracer, so the mechanism exists.

**Option C — leave it.** Spans are dropped silently, as today.

Option C is the current state and is the reason this is a decision rather than a
note: a configured exporter pointing at nothing is indistinguishable, from inside
the service, from a working one.

### TP-D-6 — what the span covers — OPEN

**Goal.** Decide the span boundary, which `TracingLive.span` exposes but nothing
calls.

Candidates are one span per HTTP request, one per controller method, or one per
service call with the request span as parent. The choice determines whether a
trace shows the Irmin round trip separately from the handler that made it.

---

## 3. Scope

This plan owns: connecting `TracingLive` to `RequestContext`, replacing both
dummy constants with real values, the interceptor or layer that establishes the
context per request, the log-annotation wrapper if `TP-D-3` rules for it, and the
span boundary.

This plan does not own: the typed identifier definitions or the removal of
`Option` from `RequestContext`. Those land in `PLAN-ERROR-WIRE-BOUNDARY.md`,
which is where the types are needed.

---

## 4. File inventory

Written once the decisions are ruled, because `TP-D-1` decides whether the work
touches a Tapir interceptor, the tracing layer, or both.

---

## 5. Verification plan

Written with the inventory. One property is already known to belong in it: no
call site may pass a dummy trace identifier once this plan has landed, and a test
asserting that is what proves the plan complete rather than partially applied.
