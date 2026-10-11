# PLAN-ERROR-WIRE-BOUNDARY

Plan code `EWB`. Decisions in this document are labelled `EWB-D-<n>`.

**Status.** All ten decisions, `EWB-D-1` to `EWB-D-10`, are ruled and recorded in
§10. Nothing blocks implementation on a decision. What blocks it is approval: the
token must name this document and a file inventory must exist beside it, and
neither is in place.

`EWB-D-10` produces a new accepted decision record and a standing cleanup rule
whose text reaches beyond this plan; §10 states both and §12 lists the files.

**How this plan reasons.** Two sources of argument are excluded by ruling. What
the system does today is evidence of a defect, never a constraint on the fix —
a shape is not justified by the fact that the current client happens to accept
it. And implementation effort is not an input to a design decision; it belongs
in phase sequencing. Every decision below is argued on two grounds only:
whether it improves error handling for the person using the product, and
whether it agrees with the accepted decision records.

**Starting point.** This plan is written against the code as it stands on
2026-10-10, not against any earlier plan. One earlier change has landed and is
assumed present: the three workspace error types no longer carry a capability
credential (`WorkspaceNotFound()`, `WorkspaceExpired()`,
`TreeNotInWorkspace(wsId, treeId)`). §14 lists the items carried over from the
previous error-structure plan and the items deliberately dropped.

---

## 1. What this plan is for

The project has one convention for getting data from the server to the browser,
and the error path is the only place that does not follow it.

`docs/dev/ARCHITECTURE.md:142` states the convention under the heading
"DTO/Domain Model Separation", marked complete:

> - Response DTOs use `fromDomain()` factories

Every one of the seven files in
`modules/common/src/main/scala/com/risquanter/register/http/responses/` follows
it: a wire-only `final case class`, a codec derived with `DeriveJsonCodec.gen`,
and a one-way projection from the domain type.
`SimulationResponse.fromRiskTree` is the reference. There is no inverse
anywhere, and the browser holds the wire type directly —
`app/state/TreeViewState.scala:109` declares
`Signal[LoadState[List[SimulationResponse]]]`.

The error path instead round-trips. `ErrorResponse.decode` takes a status code
and a response body and tries to rebuild the server's own `AppError`. It is the
only reconstruction function in the repository, no accepted decision record
describes it, and three defects follow from it directly.

**Defect 1 — a string prefix stands in for a type.** The three workspace errors
all encode to one body that names none of them, so `decode` cannot tell which it
received and returns `RepositoryFailure("workspace:not-found")`.
`RepositoryFailure.isWorkspaceSentinel` then tests that prefix at four call
sites: `app/state/GlobalError.scala:100`, and `app/core/ZJS.scala` lines 148,
201 and 220. ADR-010 names this exact shape as a code smell under the heading
"String Matching on Errors", with the instruction to pattern match on types.

**Defect 2 — fields the wire does not carry are filled with invented values.**
`decode` returns `VersionConflict("unknown", "unknown", message)` and
`SimulationFailure("unknown", ...)` because those fields have no wire
counterpart. The placeholders then flow into client code as if they were data.

**Defect 3 — information the server holds is dropped, invisibly.** All 26
`make*Response` builders in `ErrorResponse.scala` take
`requestId: Option[String] = None`, and no call site anywhere in the repository
passes it. The server generates a correlation identifier per request in
`telemetry/RequestContext.scala:46`, and it has never reached a client. The
default is why: omitting the argument is silent, so nothing ever failed. The
same defaulting hides which fields each error gives up — the three workspace
arms at `ErrorResponse.scala:173-175` match with `_:` and bind nothing, so even
the tree identifier, which is not confidential, is discarded.

A fourth defect is on the client and is caused by the first.
`app/state/WorkspaceState.scala:98` reads `.tapError { _ => ... }`, a wildcard
that clears the stored capability key and reports expiry for **any** failure of
the startup listing call. A dropped connection or a server fault therefore
destroys the only copy of the user's workspace key. The wildcard exists because
the typed error the handler would need to match on does not survive the wire.

---

## 2. The two principles this plan applies

### EWB-P-1 — the projection is one-way

A wire error type is declared in the shared module, a codec is derived for it,
and one function projects the internal error onto it. No function goes the other
way. This is the response-DTO convention, applied to the error output.

The consequence that matters: the wire type has no field for a value that must
not cross, so a leak is structurally impossible rather than prevented by calling
the right builder. A `WorkspaceId` cannot appear in a response because no wire
case declares one.

### EWB-P-2 — the message-composition split

The server sends a machine-readable case plus the structured values that belong
to it. The browser composes the sentence the user reads. The split is by whether
the user can act on the failure:

- **Actionable** — a validation rejection, a conflict, workspace expiry, a query
  the engine refused. The wire case carries typed values, and the browser writes
  the sentence from them.
- **Opaque** — a repository fault, a storage fault, an authorization denial, a
  dependency outage. The wire case carries no payload, and the browser writes
  one fixed sentence per case. The server's own text goes to the log.

Three reasons, in descending weight for this codebase. Free text is a `String`,
so nothing prevents a future contributor interpolating an internal reason into
it, and that is the failure ADR-035 exists to prevent; a case drawn from a closed
set cannot carry an unauthorized payload. The wording belongs to the surface
that renders it, because only the browser knows whether it is drawing a banner,
an inline field error or a toast. And a sentence cannot be localised when it
arrives already written.

The browser already does this in exactly one place, and it is the one place the
product cares about: `app/state/GlobalError.scala:101-104` composes the
workspace-expiry banner text client-side from a signal that carries no text. The
mechanism it uses to detect the case is the string sentinel this plan deletes.

---

## 3. `WireError` — the type that crosses

New file
`modules/common/src/main/scala/com/risquanter/register/http/errors/WireError.scala`.
It is in `common`, so it is compiled once for the Java Virtual Machine and once
for Scala.js, and the browser holds the same type the server produced.

Nine cases, one per HTTP status, so no two cases share a status. §4 explains why
that one-to-one property is required rather than merely tidy.

```scala
package com.risquanter.register.http.errors

import java.time.Duration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.iron.{CommitHash, ScenarioName, TreeId}
import com.risquanter.register.domain.errors.ValidationError

/** The only error type that crosses to a client.
  *
  * Projected from `AppError` by `WireError.fromAppError`, which is the single
  * place a field is dropped. There is no inverse: a client deserialises this
  * type and uses it, exactly as it does with a response type in
  * `http/responses` (ADR-035).
  */
Every case carries `trace`, projected from `AppError.trace`, so a failure the
user sees names the trace it came from. It is last in every parameter list, so
the case's own content reads first.

```scala
@jsonDiscriminator("kind")
enum WireError:
  /** A request body the decoders rejected. Carries one entry per independent
    * failure, so a form shows every problem at once. */
  case ValidationRejected(errors: List[ValidationError], trace: TraceId)

  /** A well-formed request whose query the engine refused. */
  case QueryRejected(problem: QueryProblem, trace: TraceId)

  /** No such resource, or one the caller may not see. The two are one response
    * so that neither is an existence oracle. */
  case NotFound(trace: TraceId)

  /** A workspace past its absolute or idle timeout.
    *
    * `lifetime` is the configured workspace lifetime, so the browser can say
    * how long a workspace lasts without holding a second copy of the figure. It
    * is the server's `WorkspaceConfig.ttl` and nothing else.
    */
  case WorkspaceExpired(lifetime: Duration, trace: TraceId)

  /** Refused. Every denial produces this one shape: an explicit deny and an
    * unreachable authorization service must be indistinguishable, or an outage
    * is observable from outside (ADR-035 §2). */
  case AccessDenied(trace: TraceId)

  /** The request conflicts with current state. */
  case Conflict(conflict: ConflictKind, trace: TraceId)

  /** The deployment does not provide this capability. */
  case NotSupported(capability: Capability, trace: TraceId)

  /** A dependency this request needed did not answer. Which dependency, and how
    * it failed, stay internal (ADR-035 §2). */
  case DependencyUnavailable(trace: TraceId)

  /** An unexpected server-side fault. The reason is in the server log, against
    * this trace. */
  case InternalFault(trace: TraceId)

object WireError:
  given codec: JsonCodec[WireError] = DeriveJsonCodec.gen[WireError]
```

### 3.1 `QueryProblem` — the payload of a refused query

The engine's rendered messages are the useful content here and are written for
the person who typed the query, so this is an actionable case under EWB-P-2.

```scala
@jsonDiscriminator("kind")
enum QueryProblem:
  /** Syntax the parser rejected. `position` is the character offset the parser
    * reported, when it reported one. */
  case Unparseable(message: String, position: Option[Int])

  /** Names the query used that are not in the tree, one message each. */
  case UnknownReferences(messages: List[String])

  /** Type-check failures from the bind phase, one message each. */
  case TypeCheckFailed(messages: List[String])

  /** A quantifier ranging over a type with no enumerable domain. */
  case NotQuantifiable(message: String)

object QueryProblem:
  given codec: JsonCodec[QueryProblem] = DeriveJsonCodec.gen[QueryProblem]
```

Two query failures are **not** here. `FolModelValidationFailure` and
`FolEvaluationFailure` are faults in how the knowledge base is wired, not
problems with the query, so they project to `InternalFault()`.

### 3.2 `ConflictKind` — the payload of a conflict

Granularity is `EWB-D-7`, open. The set below is what the current browser code
demonstrably distinguishes, and the decision is whether the merge cases need
more.

```scala
@jsonDiscriminator("kind")
enum ConflictKind:
  /** A uniqueness or state conflict with no further structure. */
  case Rejected()

  /** The caller's expectation of a resource's version is stale. */
  case StaleVersion()

  /** Two branches hold conflicting values, so no merge commit was created. */
  case MergeRefused(scenario: ScenarioName.ScenarioName, details: String)

  /** A merge of this scenario is already running; retry once it finishes. */
  case MergeAlreadyRunning(scenario: ScenarioName.ScenarioName)

  /** Main moved while the merge was assembled; main is untouched. */
  case MergeTargetMoved(scenario: ScenarioName.ScenarioName)

  /** The scenario's head is not what the caller last observed. */
  case ScenarioHeadStale(scenario: ScenarioName.ScenarioName, expected: CommitHash)

  /** A query arrived before the tree had simulation results. */
  case SimulationRequired(treeId: TreeId)

object ConflictKind:
  given codec: JsonCodec[ConflictKind] = DeriveJsonCodec.gen[ConflictKind]
```

`ScenarioHeadStale` keeps `expected` and drops the internal error's `actual`. The
caller can act on what was expected; the head the branch currently carries is a
second fact that only helps someone correlate activity in a workspace they do
not hold.

### 3.3 `Capability` — a closed set, not prose

```scala
enum Capability:
  case Scenarios

object Capability:
  given codec: JsonCodec[Capability] = DeriveJsonCodec.gen[Capability]
```

Today `ScenariosNotSupported(reason)` sends operator-facing configuration text to
the browser. The browser needs to know only which feature is unavailable; the
reason belongs in the log. One case exists because scenarios are the only
capability a deployment can lack.

### 3.4 The one codec to confirm

`WorkspaceExpired` carries `java.time.Duration`. `Instant` already round-trips
through a derived codec in `WorkspaceResponse`, and the browser has `java.time`
via `scala-java-time`, declared in `build.sbt`. Whether zio-json derives a
`Duration` codec on both platforms is a verification step in §13, not an
assumption. If it does not, the field becomes `lifetimeSeconds: Long` and the
browser builds the `Duration`.

---

## 4. The endpoint declaration — one `oneOf`, one status per case

`modules/common/src/main/scala/com/risquanter/register/http/endpoints/BaseEndpoint.scala`
is the single place every endpoint's error output is declared. All nine endpoint
traits extend `BaseEndpoint` and build from `baseEndpoint` or
`authedBaseEndpoint`, so this one declaration reaches every endpoint.

Today, lines 15-18:

```scala
val baseEndpoint = endpoint
  .errorOut(statusCode and jsonBody[ErrorResponse])
  .mapErrorOut[Throwable](ErrorResponse.decode)(ErrorResponse.encode)
```

`statusCode` is a Tapir output carrying a status **value**, which is why the
error output type is the pair `(StatusCode, ErrorResponse)` and why `encode`
returns a tuple with the status chosen inside whichever builder ran.
`mapErrorOut` asks for a function in each direction, and that second direction is
`decode`.

After:

```scala
val baseEndpoint = endpoint
  .errorOut(
    oneOf[WireError](
      oneOfVariant(StatusCode.BadRequest,          jsonBody[WireError.ValidationRejected]),
      oneOfVariant(StatusCode.UnprocessableEntity, jsonBody[WireError.QueryRejected]),
      oneOfVariant(StatusCode.Forbidden,           jsonBody[WireError.AccessDenied]),
      oneOfVariant(StatusCode.NotFound,            jsonBody[WireError.NotFound]),
      oneOfVariant(StatusCode.Gone,                jsonBody[WireError.WorkspaceExpired]),
      oneOfVariant(StatusCode.Conflict,            jsonBody[WireError.Conflict]),
      oneOfVariant(StatusCode.NotImplemented,      jsonBody[WireError.NotSupported]),
      oneOfVariant(StatusCode.ServiceUnavailable,  jsonBody[WireError.DependencyUnavailable]),
      oneOfDefaultVariant(StatusCode.InternalServerError, jsonBody[WireError.InternalFault])
    )
  )
```

There is no `mapErrorOut`, because there is nothing to map: `WireError` is
already the type both sides want, exactly as `jsonBody[SimulationResponse]` needs
no mapping on the success side.

**How `oneOf` runs, in each direction.** On the server Tapir holds a `WireError`
value and tests it against each variant's body type, using the type tag the
compiler records; the first match decides which status is written and which shape
is serialised. In the browser Tapir reads the response's status code, selects the
variant declared for that status, and deserialises the body as that variant's
type. `oneOfDefaultVariant` handles a status the list does not name.

**Why one status per case is required, not merely neat.** The browser's selection
is by status alone. Two variants sharing a status would make the first one listed
always win on decode, silently. That is the reason `QueryRejected` gets
`422 Unprocessable Entity` rather than sharing `400`, and the reason the internal
conflict errors collapse into one `Conflict` case with `ConflictKind` inside it
rather than becoming seven variants at `409`. Where several outcomes share a
status they share a wire case, and the inner enumeration discriminates. The
status-to-case relation is therefore one-to-one by construction, and §13 pins it
with a test.

Three statuses change what a caller observes. That set is `EWB-D-6`, open, in
§10.

---

## 5. The projection — where a field is dropped

### 5.1 `WireError.fromAppError`

One total function, in `WireError.scala`. It is the only place in the system
where a field stops crossing, so the full set of drops is readable in one screen.
Each arm **binds what it keeps** and writes `_` only for what it deliberately
gives up — the current encoder's `case _: TreeNotInWorkspace` binds nothing and
so discards the tree identifier along with the workspace identifier.

```scala
def fromAppError(e: AppError): WireError =
  // The trace is a field of every error and of every wire case, so it is bound
  // once here rather than read in each arm.
  val t = e.trace

  e match
    // Request rejected
    case ValidationFailed(errors)             => ValidationRejected(errors, t)

    // Query refused
    case FolParseFailure(message, position)   => QueryRejected(QueryProblem.Unparseable(message, position), t)
    case FolUnknownReference(messages)        => QueryRejected(QueryProblem.UnknownReferences(messages), t)
    case FolBindFailure(errors)               => QueryRejected(QueryProblem.TypeCheckFailed(errors), t)
    case FolDomainNotQuantifiable(message)    => QueryRejected(QueryProblem.NotQuantifiable(message), t)

    // Absent, or not the caller's. One shape for both, so neither is an
    // existence oracle. The workspace identifier may not cross (ADR-036); the
    // tree identifier is dropped because it would distinguish the two cases.
    case WorkspaceNotFound()                  => NotFound(t)
    case TreeNotInWorkspace(_, _)             => NotFound(t)

    // Expired. The lifetime crosses; the workspace identifier may not
    // (ADR-036), and the moment this one expired is a fact about one workspace
    // the browser has no use for. The lifetime is what lets it say how long a
    // workspace lasts.
    case WorkspaceExpired(_, ttl, _)          => WorkspaceExpired(ttl, t)

    // Refused. The internal reason is dropped from all three: an explicit deny
    // and an unreachable authorization service must look identical (ADR-035 §2).
    case AccessDenied(_)                      => AccessDenied(t)
    case AuthForbidden(_, _, _, _)            => AccessDenied(t)
    case AuthServiceUnavailable(_, _)         => AccessDenied(t)

    // Conflicts
    case DataConflict(_)                      => Conflict(ConflictKind.Rejected(), t)
    case VersionConflict(_, _, _)             => Conflict(ConflictKind.StaleVersion(), t)
    case MergeConflict(scenario, details)     => Conflict(ConflictKind.MergeRefused(scenario, details), t)
    case MergeAlreadyRunning(scenario)        => Conflict(ConflictKind.MergeAlreadyRunning(scenario), t)
    case MergeTargetMoved(scenario)           => Conflict(ConflictKind.MergeTargetMoved(scenario), t)
    case ScenarioHeadStale(scenario, exp, _)  => Conflict(ConflictKind.ScenarioHeadStale(scenario, exp), t)
    case SimulationNotCached(treeId)          => Conflict(ConflictKind.SimulationRequired(treeId), t)

    // Capability absent
    case ScenariosNotSupported(_)             => NotSupported(Capability.Scenarios, t)

    // Dependency did not answer
    case IrminUnavailable(_)                  => DependencyUnavailable(t)
    case NetworkTimeout(_, _)                 => DependencyUnavailable(t)
    case IrminHttpError(_, _)                 => DependencyUnavailable(t)
    case IrminGraphQLError(_, _)              => DependencyUnavailable(t)

    // Faults. The three compare-and-set outcomes are translated into domain
    // errors by ScenarioService before they reach here; these arms are the net
    // if that translation is ever skipped, and a client can act on none of them.
    case BranchAlreadyExists(_)               => InternalFault(t)
    case BranchHeadStale(_, _)                => InternalFault(t)
    case IrminMergeConflict(_)                => InternalFault(t)
    case RepositoryFailure(_)                 => InternalFault(t)
    case TreeLoadFailure(_, _)                => InternalFault(t)
    case SimulationFailure(_, _)              => InternalFault(t)
    case FolModelValidationFailure(_)         => InternalFault(t)
    case FolEvaluationFailure(_, _)           => InternalFault(t)
```

All 31 leaf types of `AppError` appear: 15 under `SimError`, 7 under
`IrminError`, 2 under `AuthError` and 7 under `FolQueryFailure`. The match is on
the leaf types rather than on the four sub-traits, so
`-Wconf:msg=match may not be exhaustive:error` in `build.sbt` makes a new error
type a compile error here until an arm is written for it. That is stronger than
the current four-level dispatch through `encodeAppError`, `encodeSimError`,
`encodeAuthError`, `encodeIrminError` and `encodeFolQueryFailure`, which
guarantees coverage of the sub-traits only.

### 5.2 Where the projection runs

In the controllers, next to the success projection. The success side already
reads, at `WorkspaceTreeController.scala:101`:

```scala
result <- riskTreeService.update(ws.id, treeId, req, branch).map(SimulationResponse.fromRiskTree)
```

Every controller method ends `(for ... yield result).either`, because
`serverLogic` wants a function from the input to `F[Either[E, O]]`, where `E` is
the endpoint's error output type. There are 22 such sites: 7 in
`WorkspaceTreeController`, 6 in `WorkspaceLifecycleController`, 5 in
`ScenarioController`, 2 in `WorkspaceAnalysisController`, and 1 each in
`QueryController` and `DistributionPreviewController`.

Rather than write `.mapError(WireError.fromAppError)` 22 times, one extension
method keeps the projection in a single expression and makes each site a one-word
change. New file
`modules/server/src/main/scala/com/risquanter/register/http/controllers/WireEither.scala`:

```scala
extension [R, A](effect: ZIO[R, AppError, A])
  /** Project the typed domain failure onto the wire type and move it into the
    * `Either` that `serverLogic` expects.
    *
    * Attaches the trace of the request before projecting: this is the one place
    * the request context and the failure are both in scope.
    */
  def wireEither: ZIO[R & RequestContextRef, Nothing, Either[WireError, A]] =
    effect
      .mapError(e => e.attachTrace(currentTrace))
      .mapError(WireError.fromAppError)
      .either
```

`currentTrace` reads the trace from the request context, falling back to the
placeholder while nothing populates it. `PLAN-TELEMETRY-PLUMBING.md` replaces
that fallback with a real value; the signature here does not change when it does.

Each site then changes `.either` to `.wireEither`. `BaseController` is unchanged:
its effect type stays `Task`, because `serverLogic` carries the typed error in the
`Either` and not in the effect's error channel.

One thing to check rather than assume: this extension requires each
for-comprehension's inferred error type to be `AppError`, not `Throwable`. A site
that infers `Throwable` will not compile against it and needs its own narrowing.
§13 covers that as a compile step, and the compiler names any such site.

---

## 6. Changes to the internal hierarchy

### 6.1 The internal workspace errors carry what a debugger needs

The internal hierarchy exists for the server's own use, so it carries the
workspace identifier for diagnosis. `WireError` carries none, which is what
keeps ADR-036's output rule structural rather than a matter of discipline.

```scala
case class WorkspaceExpired(
  wsId:      WorkspaceId,
  ttl:       java.time.Duration,
  expiredAt: java.time.Instant
) extends DomainError:
  override def getMessage: String = "Workspace expired"

case class TreeNotInWorkspace(wsId: WorkspaceId, treeId: TreeId) extends DomainError:
  override def getMessage: String = s"Tree ${treeId.value} not found in workspace"
```

`ttl` is the configured lifetime, which is the same for every workspace.
`expiredAt` is when this one passed it, which is what makes a log line answer
"how long ago" rather than only "how long were they given". The two are
different facts and both are wanted for diagnosis.

All three are constructible because nothing reconstructs them: under EWB-P-1 the
browser receives a `WireError`, never one of these. The expiry arm of
`WorkspaceStoreLive.validateWorkspace` and of `WorkspaceStorePostgres` both hold
the record and the `WorkspaceConfig`, so every value is in scope where the
failure is built — `expiredAt` is `createdAt.plus(ttl)` for the absolute timeout
and `lastAccessedAt.plus(idleTimeout)` for the idle one, whichever the record
tripped.

`WorkspaceNotFound` takes no identifier, and that is structural rather than a
choice: the lookup that failed is the thing that would have produced one. There
is no workspace to name, because no workspace was found.

```scala
case class WorkspaceNotFound() extends DomainError:
  override def getMessage: String = "Workspace not found"
```

`getMessage` carries neither identifier, which ADR-035 §3 requires of a message
and which costs nothing: a log line reads these as annotations, where they are
queryable, rather than as text inside a sentence.

### 6.6 The correlation identifier — its type and its value

Two separate questions, and the current `RequestContext` answers both badly.

```scala
final case class RequestContext(
  requestId: String,
  traceId: Option[String] = None,
  spanId: Option[String] = None,
  userId: Option[String] = None
)
```

**The type.** Four raw `String` fields for four concepts with distinct valid-value
sets, which is what ADR-018 exists to forbid: semantically distinct identifiers
sharing an encoding get nominal wrappers, so the compiler can tell a trace
identifier from a span identifier. `requestId` is a UUID and `traceId` is a
32-character hexadecimal value, and today either is assignable to the other.

**The value.** `requestId` is a `UUID.randomUUID()` generated in
`RequestContext.generate`. `traceId` and `spanId` are declared and populated
nowhere in the codebase. So the service holds a locally invented identifier that
no tracing backend knows, alongside two empty fields for the identifiers it
would. The W3C Trace Context trace identifier is the value that joins a
user-visible failure to a span in the collector, and it is what the OpenTelemetry
exporters already configured in `build.sbt` emit.

**The shape.** Three nominal wrappers over Iron refinements, built the way
`WorkspaceId` and `TreeId` are built — a `case class` over a refined string, a
smart constructor, and codecs in the companion (ADR-018).

```scala
/** The W3C Trace Context trace identifier: 32 lowercase hexadecimal characters,
  * a length and alphabet fixed by that specification rather than chosen here.
  */
type TraceIdStr = String :| Match["^[0-9a-f]{32}$"]

/** The W3C Trace Context span identifier: 16 lowercase hexadecimal characters,
  * fixed by the same specification. Compiler-distinct from `TraceId`, so one
  * cannot be passed where the other is wanted (ADR-018).
  */
type SpanIdStr = String :| Match["^[0-9a-f]{16}$"]

/** Register's own per-request identifier: a UUID, as `RequestContext.generate`
  * produces today. Compiler-distinct from both trace identifiers.
  */
type RequestIdStr = String :| Match["^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"]

case class TraceId(toTraceIdStr: TraceIdStr):
  def value: String = toTraceIdStr

case class SpanId(toSpanIdStr: SpanIdStr):
  def value: String = toSpanIdStr

case class RequestId(toRequestIdStr: RequestIdStr):
  def value: String = toRequestIdStr
```

Each gets a `fromString` returning `Either[List[ValidationError], _]` and a
`JsonCodec`, matching the existing identifier types exactly.

**`RequestContext` loses every raw string and every `Option`.**

```scala
final case class RequestContext(
  requestId: RequestId,
  traceId:   TraceId,
  spanId:    SpanId,
  userId:    UserId
)
```

No default on any field, so a site that cannot supply one is a compile error
rather than a silent `None`. `userId` needs no `Option`: `UserId` is already a
sealed trait with an `Anonymous` case object, so the absent user is a value. That
is the existing workspace practice applied here rather than a new type invented
for it.

**What cannot be filled yet is filled from a named dummy.** No part of the system
can supply a real trace identifier until the tracing layer is connected, so
`RequestContext.generate` passes placeholders that say so:

```scala
def generate: UIO[RequestContext] =
  ZIO.succeed(RequestContext(
    requestId = RequestId(UUID.randomUUID().toString.refineUnsafe),
    traceId   = TelemetryPlaceholders.UnlinkedTrace,
    spanId    = TelemetryPlaceholders.UnlinkedSpan,
    userId    = UserId.Anonymous
  ))
```

The placeholders are held in the constants structure `EWB-D-10` rules, each with
scaladoc naming the plan that replaces it. They are deliberately recognisable
rather than plausible, so a real value is never confused with a placeholder, and
a search for either name is the complete list of sites the telemetry work must
reach.

**`AppError` carries the trace identifier, every `WireError` case projects it,
and the browser displays it.** The chain is one projection with no extra
parameter and no header: the identifier is a field of the error, so
`fromAppError` reads it from its single argument like every other field.

The trait carries it, so none of the 31 leaf types and none of their construction
sites change:

```scala
sealed trait AppError extends Throwable:
  /** The trace the failing work ran under.
    *
    * Assigned once at the boundary that projects the error, which is where the
    * request context is in scope. An error that reaches the boundary without one
    * reads as the placeholder, so an unattributed failure is findable rather
    * than indistinguishable.
    */
  final def trace: TraceId = assignedTrace

  private var assignedTrace: TraceId = TelemetryPlaceholders.UnlinkedTrace

  private[register] def attachTrace(t: TraceId): this.type =
    assignedTrace = t
    this
```

A single assignment at one boundary, on a type that is already mutable —
`Throwable` carries a stack trace and suppressed exceptions — and whose values
are single-use. The alternative, a `trace` field on each of the 31 case classes,
would put the identifier at every construction site in the services, which means
threading the request context through every one of them.

`§5.2`'s `wireEither` is that boundary: it is effectful, runs once per controller
method, and has the context in scope.

**Boundary.** This plan types the structure, removes the optionality, and marks
what it cannot fill. Connecting the identifiers to real spans is
`docs/dev/plans/PLAN-TELEMETRY-PLUMBING.md`, which holds the four open questions
that work depends on. The two files touched here are `RequestContext.scala` and
the identifier definitions; no other telemetry file is opened.

### 6.2 The resolve-failure log line gains the record

`WorkspaceStoreLive.logResolveFailure` currently receives the capability key and
annotates `"workspace_key" -> key.toString`, which renders as the same redacted
constant for every workspace, so the line identifies nothing. The expiry arm has
the record in scope. The annotation becomes `"workspace_id" -> ws.id.value`,
`"created_at"` and `"expires_at"`, which is where the question "why did this
resolve fail" is actually answered, and it is permitted because a confidential
internal identifier may appear in a server log (ADR-036).

This needs a signature change, since the function today receives only the key.
That is a Phase 2 item and its signature is stated in §9.

### 6.3 `SimError` becomes `DomainError`

The trait is named for simulation but holds validation, workspace, scenario and
merge errors. 31 occurrences across five files. Mechanical, and the compiler
finds every one.

### 6.4 `ValidationError.field` becomes a typed location

`ValidationError` crosses the wire inside `WireError.ValidationRejected`, so it is
the one internal type that is also a wire type, and its `field: String` is the
last free-text slot in a response.

```scala
case class ValidationError(
  location: ErrorLocation,
  code: ValidationErrorCode,
  message: String
)
```

`ErrorLocation` and the two Iron refinements it needs are specified in Phase 3,
§9. This is the largest mechanical item in the plan and the reason it is its own
phase rather than part of the boundary work.

### 6.5 Deletions from `AppError.scala`

`RepositoryFailure.WorkspaceSentinelPrefix` and
`RepositoryFailure.isWorkspaceSentinel` are removed with their four call sites.
The companion object of `RepositoryFailure` then has no members and is removed
too.

---

## 7. The client

### 7.1 `GlobalError` classifies the wire type

`app/state/GlobalError.scala` keeps its display cases. `fromAppError` becomes
`fromWireError`, matching nine wire cases instead of 31 internal ones, and the
compiler checks every case rather than only the four sub-traits.

```scala
def fromWireError(e: WireError): GlobalError = e match
  case WireError.ValidationRejected(errors, _)     => ValidationFailed(errors)
  case WireError.QueryRejected(problem, _)         => queryMessage(problem)
  case WireError.NotFound(_)                       => NotFound
  case WireError.WorkspaceExpired(lifetime, _)     => WorkspaceExpired(expiryMessage(lifetime))
  case WireError.AccessDenied(_)                   => ServerError(ErrorMessages.Forbidden)
  case WireError.Conflict(conflict, _)             => Conflict(conflictMessage(conflict))
  case WireError.NotSupported(capability, _)       => ServerError(capabilityMessage(capability))
  case WireError.DependencyUnavailable(t)          => DependencyError(ErrorMessages.DependencyDown, Some(t))
  case WireError.InternalFault(t)                  => ServerError(ErrorMessages.InternalFault, Some(t))
```

**The browser displays it, and which cases show it is a presentation choice
rather than a data one.** The identifier is on every wire case, so every
`GlobalError` case can carry it; the two that render it are the ones where the
user has nothing else to act on and a reference is the only useful thing on
screen — a dependency outage and an internal fault. The others carry an
actionable message of their own, and a trace identifier beside it is noise.

`GlobalError`'s cases therefore take `trace: Option[TraceId]` and `ErrorBanner`
renders it in a muted line under the message, selectable so it can be copied:

```scala
case GlobalError.ServerError(msg, trace) =>
  div(
    span(cls := "error-banner-message", msg),
    trace.map(t => div(cls := "error-banner-trace", t.value))
  )
```

`fromThrowable` stays, narrowed to its real job: a browser `fetch` failure is a
`TypeError` and is not a `WireError`, so it still becomes `NetworkError`.

The four private message-composing functions are the whole of EWB-P-2 on this
side. `expiryMessage` is the one that pays immediately — it states the configured
lifetime from the `Duration`, which no sentence written on the server could do
without the server owning the wording.

A `NotFound` display case is added. Today a workspace 404 and an expiry both
produce `WorkspaceExpired`, because the sentinel cannot tell them apart; with the
wire distinguishing them the browser can stop calling a missing workspace an
expired one.

### 7.2 The pre-validation wildcard is narrowed

`app/state/WorkspaceState.scala:98` currently clears the capability key on any
failure. It becomes a match, so only an answer that actually means the workspace
is gone discards the key:

```scala
.tapError {
  case WireError.WorkspaceExpired(_) | WireError.NotFound() =>
    zio.ZIO.succeed {
      workspaceKey.set(None)
      clearURL()
      onExpired()
    }
  case _ => zio.ZIO.unit
}
```

A dropped connection or a server fault then leaves the key where it is, and the
startup listing simply fails. This is the defect named fourth in §1, and it is
fixed by the boundary change rather than by a patch to the handler.

### 7.3 The duplicated sentence goes

The expiry text is written twice, identically: `app/Main.scala:117-118` and
`app/state/GlobalError.scala:103-104`. `Main.scala`'s `onExpired` callback stops
carrying a message, because `GlobalError` composes it from the lifetime.

### 7.4 `ZJS`

Lines 148, 201 and 220 test the sentinel. Each becomes a match on
`WireError.WorkspaceExpired` and `WireError.NotFound`. The helpers' error
parameter type changes from `Throwable` to `WireError`, and
`app/core/ThrowableExtensions.scala`'s `safeMessage` is reviewed: with the browser
composing its own text, reading a message off the error is no longer the normal
path.

### 7.5 What each conflict case renders

Today all seven render as one line of red text with no action. The cases make a
per-case treatment possible, and this is what each should be:

| Case | What the person is told, and what they can do |
|---|---|
| `MergeAlreadyRunning(scenario)` | the named scenario is mid-merge; the modal keeps itself open and re-checks, because waiting is the whole remedy |
| `MergeTargetMoved(scenario)` | main moved and nothing was written; the re-check button is the action, and it is already on screen |
| `MergeRefused(Conflicts(paths))` | the paths that changed on both branches, listed; the remedy is to edit either side to agree |
| `MergeRefused(DuplicateNames(perTree))` | the repeated names, per tree; the remedy is to rename one |
| `MergeRefused(InvariantBroken(perTree))` | the merge was undone and the tree is as it was; this is a fault to report, not a conflict to resolve, and it is the one case that should carry the trace identifier prominently |
| `ScenarioHeadStale(scenario, expected)` | the scenario changed since it was read; reload is the action |
| `StaleVersion()` / `Rejected()` | the existing generic line, which is correct for a conflict with no further structure |

### 7.6 The merge-in-progress path, concretely

Two of the cases above change behaviour and not only wording, and this is the
whole of that change. It is deliberately the simplest shape that stops the case
being swallowed, and it adds no timer, no polling and no new component.

Today every merge failure takes one route:

```scala
.tapError(e => ZIO.succeed(submit.set(MergeSubmitState.Failed(e.safeMessage))))
```

`MergeSubmitState` gains one case beside `Failed`:

```scala
enum MergeSubmitState:
  case Idle
  case Submitting
  case Failed(message: String)
  case AlreadyRunning          // new
```

and the handler separates that one arm:

```scala
.tapError {
  case WireError.Conflict(ConflictKind.MergeAlreadyRunning(_)) =>
    ZIO.succeed(submit.set(MergeSubmitState.AlreadyRunning))
  case e =>
    ZIO.succeed(submit.set(MergeSubmitState.Failed(messageFor(e))))
}
```

`MergeModal` renders the new state as an informational line rather than the red
error line, beside the Re-check button that is already on screen:

```scala
case MergeSubmitState.AlreadyRunning =>
  Some(div(cls := "merge-modal-status", MergeMessages.AlreadyRunning))
```

**So what the person sees:** the modal stays open, the line says a merge of this
scenario is already running and to re-check in a moment, and the Re-check button
they already have is the action. Nothing polls, nothing retries on its own, and
no state machine is added. The user presses Re-check when they choose to, which
is what the button is for.

**Why this and not something cleverer.** Automatic polling means choosing an
interval, cancelling it on close, and deciding what happens when it never
clears — a design with three new questions. The one-case separation has none,
and it removes the actual defect, which is that a self-resolving state was
presented as a failure with no remedy. If polling turns out to be wanted, it is
a later change on top of a state that already exists, rather than a reason to
leave the case swallowed now.

`InvariantBroken` is the second behavioural change. The merge was applied, a tree
then failed to load, and the merge was undone, so the tree is as it was. It is a
server fault that has already repaired itself, not a conflict for the user to
resolve. It renders with the trace identifier where the person can read and quote
it, and without a Re-check button, because re-checking is not the remedy.

### 7.7 Every user-facing sentence comes from one place

The browser composes its own text under EWB-P-2, and those sentences are named
values rather than literals at the rendering site, held the way `EWB-D-10` rules.
The templates that take a value — the workspace lifetime, a scenario name, a
count of conflicting paths — are functions of that value in the same structure,
so a sentence and its parameters cannot drift apart.

This is what makes the rule check itself: a rendering site that writes a literal
instead of calling the named template is visible in review, and the current
duplicate expiry sentence written at both `Main.scala:117` and
`GlobalError.scala:103` is the defect that convention prevents.

---

## 8. What is deleted

| Deleted | Why it existed |
|---|---|
| `ErrorResponse.decode` | the client rebuilt the internal type; it now deserialises the wire type |
| 26 `make*Response` builders | each hid a status choice and a defaulted `requestId` |
| the private `response()` builder | shared helper for the above |
| `ErrorResponse`, `JsonHttpError`, `ErrorDetail` | the generic envelope the wire cases replace |
| `ErrorResponse.WorkspaceDomain` | a string tag `decode` read to recognise workspace 404s |
| `RepositoryFailure.WorkspaceSentinelPrefix` | no case to signal, now that cases are cases |
| `RepositoryFailure.isWorkspaceSentinel` and its 4 call sites | string matching on errors, which ADR-010 names a code smell |
| `mapErrorOut` in `BaseEndpoint` | nothing left to map |
| `ErrorResponse.encode` and its 5 dispatch functions | replaced by one exhaustive `fromAppError` |

`ErrorResponseSpec.scala` is 576 lines and most of it asserts `decode(encode(x))`
round-trips. It is replaced rather than edited; §13 states what the new tests pin.

---

## 9. Phases

Each phase ends with the whole suite green: `commonJVM/test`, `server/test`,
`app/test` and `serverIt/test`.

**Phase 1 — the boundary.** `WireError`, `QueryProblem`, `ConflictKind`,
`Capability` and their codecs; `MergeRefusal` as `ConflictKind.MergeRefused`'s
payload and the closed preview status, both from `EWB-D-9`; the three typed
telemetry identifiers and the retyped `RequestContext` with its placeholder
constants, from `EWB-D-8`; `fromAppError`; the `oneOf` declaration in
`BaseEndpoint`; `wireEither` and the 22 call sites; `GlobalError.fromWireError`
with its message functions; the three `ZJS` sites; the pre-validation match; the
duplicated sentence; and every deletion in §8. Every string this phase writes is
held as `EWB-D-10` rules.

**Phase 1a — the frontend treatment.** `MergeSubmitState.AlreadyRunning` and the
separated handler arm; `MergeModal`'s exhaustive preview match with its wildcard
removed; the per-case conflict rendering in §7.5; the `InvariantBroken`
presentation. Separate from Phase 1 only so that the boundary can be verified
green before the interface changes on top of it; both land before the plan is
done.

**Phase 2 — the workspace errors and the log line.**
`WorkspaceExpired(wsId, ttl, expiredAt)`, `TreeNotInWorkspace`'s identifier, both
store construction sites, and the log annotation change:

```scala
private def logResolveFailure(outcome: Either[AppError, WorkspaceRecord]): UIO[Unit]
```

taking the outcome rather than the key, so the expiry arm can annotate
`workspace_id`, `created_at` and `expires_at` from the record.

**Phase 3 — typed locations.** `ErrorLocation`, its two Iron refinements, and
`ValidationError.location`. Every construction site of `ValidationError` in
`common`.

**Phase 4 — typed load problems and the rename.** `TreeLoadProblem` replacing
`TreeLoadFailure.reason: String`; `SimError` renamed to `DomainError`.

**Phase 5 — the constants rule and its foundations.** The `ValuationBasis` enum,
`FolSymbols` deriving its two entries from it, and the deletion of
`RiskTreeKnowledgeBase`'s duplicate pair. Then the rule itself: the new accepted
decision record, the `adr-constraints` distillation and its mirror, the
`code-quality-review` criterion and its mirror, and the proposed wording for the
two global instruction lines. The scattered use sites are reached by the standing
rule as later tasks open those files, not by this phase.

There is no telemetry-plumbing phase. Connecting the identifiers to real spans is
`docs/dev/plans/PLAN-TELEMETRY-PLUMBING.md`, which holds the four open questions
that work depends on. This plan types the structure and marks what it cannot
fill.

---

## 10. Decisions

### EWB-D-1 — a separate wire hierarchy with a one-way projection — RULED 2026-10-10

**Ruled: adopt it.** The internal hierarchy keeps every field it needs for logs
and server logic. A second hierarchy crosses the wire without the confidential
fields. The projection is one-way and `decode` is deleted.

### EWB-D-2 — the type is named `WireError` — RULED 2026-10-10

**Ruled: `WireError`.**

### EWB-D-3 — the status comes from Tapir's `oneOf` — RULED 2026-10-10

**Ruled: `oneOf`, one `oneOfVariant` per case.** The status is declared beside the
case it belongs to rather than chosen inside a builder, a case cannot be sent with
the wrong status, and the generated OpenAPI document gains a separate schema per
status instead of one envelope for all of them.

### EWB-D-4 — expiry answers `410 Gone` — RULED 2026-10-10

**Ruled: `410 Gone`.** It states that the resource existed and is permanently
unavailable, which is what an expired workspace is.

This also settles the sub-question raised when the status was proposed. A distinct
status is observable to anything watching traffic, not only to a holder of the
key, so it is a wider disclosure than a distinct response body. The earlier ruling
that expiry is distinguishable from absence accepted that disclosure, on the
measured ground that a caller reaching this point has already proved possession of
a 128-bit key. `410` adds no disclosure beyond what that ruling allows.

### EWB-D-5 — the message-composition split — RULED 2026-10-10

**Ruled: adopt it**, as stated in EWB-P-2. Actionable cases carry typed values and
the browser writes the sentence; opaque cases carry nothing and the browser writes
one fixed sentence per case.

### EWB-D-6 — the observable wire changes — RULED 2026-10-11

**Ruled: Option A, accept all three.** A refused query answers `422`, every
dependency failure answers `503`, and a refusal carries no reason text.

**Goal.** Three things a caller can observe change under §4, and they change
together because they are all consequences of one case-per-status mapping. Phase 1
declares the status set, so this settles before Phase 1 starts.

The changes: a refused query answers `422 Unprocessable Entity` where it answers
`400` today; a dependency failure answers `503` where it answers `502`, `503` or
`504` today depending on which dependency failed; and a refusal carries no reason
text where `AccessDenied(reason)` sends one today.

**Option A — accept all three.** Each follows from a rule this plan already
adopts. `422` is what keeps the status-to-case relation one-to-one. Collapsing
`502`, `503` and `504` stops the response naming which dependency failed and how,
which is internal. Dropping the refusal reason makes an explicit deny
indistinguishable from an unreachable authorization service, which ADR-035 §2
requires.

*Cost:* three observable changes at once. There is one first-party consumer and no
external API contract, so the cost is one coordinated change in a repository you
own.

*Concretely:* a user types a query naming a node that does not exist. The browser
receives `422` with
`{"kind":"QueryRejected","problem":{"kind":"UnknownReferences","messages":[...]}}`
and renders the engine's messages in the query pane, as it does today.

**Option B — keep today's statuses, using value matchers.** Tapir's
`oneOfVariantValueMatcher` selects a variant by an explicit predicate instead of by
type, which allows two cases at `400` and three distinct dependency statuses.

*Cost:* the browser still selects by status, so two cases at `400` need a predicate
on the deserialised body, which reintroduces exactly the body-sniffing this plan
removes. It also keeps `502` and `504`, which disclose which dependency failed.

*Concretely:* the same query failure returns `400`, and the browser must inspect
the body's `kind` field to decide whether it is holding a validation rejection or a
query rejection — a second dispatch after Tapir's.

**Option C — accept the statuses, keep the refusal reason.** `AccessDenied` carries
a closed-set reason so the browser can distinguish "you may not" from "this feature
is off".

*Cost:* `AuthServiceUnavailable` must then map to the same value as `AuthForbidden`
anyway, so the set has to be built so that an outage is not expressible. That is
achievable but it is a standing trap, and the distinction is already available
through `NotSupported`.

**My recommendation: Option A.** All three changes are consequences of rules
already ruled here, the consumer is first-party, and Option B pays for
compatibility with the defect this plan exists to remove.

### EWB-D-7 — how many conflict cases the browser needs — RULED 2026-10-11

**Ruled: Option A, the seven cases as written.**

Every one of the seven renders identically today, and that is a finding rather
than an argument against the ruling. `ScenarioMergeState.merge` ends
`.tapError(e => submit.set(MergeSubmitState.Failed(e.safeMessage)))`, and
`MergeModal` renders that state as one `div(cls := "merge-modal-error", msg)`
with no action offered. On the global banner path every conflict becomes
`GlobalError.Conflict(msg)`, which `ErrorBanner` draws with one icon and one
class. So the distinction between a merge already running, a moved target and a
stale head is carried entirely by the sentence the server wrote, and the
interface treats all three as the same red line.

The per-case treatment the ruling makes possible is specified in §7.5.

**Goal.** `ConflictKind` in §3.2 has seven cases. Four of them are merge and
scenario outcomes, and whether the browser needs them separately depends on what
the merge interface does with each. Phase 1 declares the type, so this settles
before Phase 1.

**Option A — the seven cases as written.** Each internal conflict a user can act on
differently gets its own case.

*Pros:* the browser can write a specific sentence and offer a specific action per
case — "retry once the running merge finishes" differs from "re-run the preview".
*Cons:* three of the four merge cases may render identically today, in which case
two of them are unused for now.

*Concretely:* a second merge of the same scenario is started while the first is in
flight. The browser receives `MergeAlreadyRunning(scenario)` and can say which
scenario and that waiting will clear it, rather than showing a generic conflict.

**Option B — one `MergeRefused` case carrying the scenario and a reason code.**
Collapse the four merge and scenario outcomes into one case with an inner reason
enumeration.

*Pros:* smaller surface; one rendering site.
*Cons:* the inner enumeration carries the same information under another name, so
it saves a case and not a decision.

**Option C — `Rejected()` and `StaleVersion()` only, merge outcomes deferred.**
Project every merge outcome to `Rejected()` for now.

*Pros:* smallest Phase 1.
*Cons:* it discards information the server has and the merge interface already
shows, and the scenario name is needed to say which merge failed.

**My recommendation: Option A.** The cases exist in the internal hierarchy because
they were found to be genuinely different outcomes, and the browser already treats
a merge refusal differently from a version conflict. Carrying a case the interface
renders generically today is cheaper than discarding a distinction and
rediscovering it.

### EWB-D-8 — the correlation identifier — RULED 2026-10-11

**Ruled: the trace identifier is a field of `AppError`, is projected onto every
`WireError` case, and the browser displays it.** The chain is one projection with
no second parameter and no response header. The typed identifiers and the
retyped `RequestContext` are in §6.6; the carrier on `AppError` and the
projection are in §5.1 and §6.6.

This ruling was given twice, because I reversed it in between on reasoning of my
own. It is settled and is not reopened.

**Goal.** Decide whether the request identifier reaches the browser, and therefore
whether a user-reported failure can be traced to a server log line.

The gap it closes is not that the field is unpopulated — that is a symptom. It
is that a person who sees a failure has nothing to quote, so a report of
"something went wrong" cannot be joined to the server's own record of it. That
is a product gap first and an observability gap second.

**Option A — every `WireError` case carries it.** Add `requestId: String` to each
case, with no default, so omission is a compile error.

*Pros:* a user can quote an identifier from the screen and it resolves to one log
line. The absence of a default is what guarantees it stays populated — the
defaulted parameter is precisely why the current field is dead.
*Cons:* it must reach `fromAppError`, which runs in the controller where the
request context is available but not currently threaded. That is real plumbing, not
a rename, and it touches all 22 sites.

*Concretely:* a user reports "it said something went wrong". The banner also shows
a short identifier, and one log query returns the fault that produced it.

**Option B — one wrapper case carrying it once.** `WireError` becomes a case class
with `requestId` and a `problem` field holding the nine cases.

*Pros:* the identifier is declared once.
*Cons:* it reintroduces an envelope, and the envelope is what this plan removes.
Every match on the browser gains a layer.

**Option C — do not send it.** Delete the dead field and leave correlation to the
server logs.

*Pros:* no plumbing.
*Cons:* a user-reported failure stays untraceable, which is the gap that exists
today.

**My recommendation: Option A.** A correlation identifier that is structurally
present and never populated is worse than none, because it reads as working. If
threading the request context into the projection turns out to need a design of its
own, that comes back as its own decision rather than being settled by quietly
accepting B.

### EWB-D-9 — the merge-refusal typing joins this plan — RULED 2026-10-11

**Ruled: Option A, fold it in.** The closed set that replaces free text in merge
errors, and the ruling that the merge-preview status stops being a `String` on
the wire, both land in this plan rather than in a separate document or in the
superseded one.

The reason it is the same work: `MergeRefusal` is a closed set replacing prose on
a client-facing error, which is `EWB-P-2`'s actionable side exactly. It becomes
the payload of `ConflictKind.MergeRefused`, so splitting it across two plans
would mean designing one set twice.

The preview status is the sharper half. `MergePreviewResponse.status` is a
`String`, written at four sites in `ScenarioController.toPreviewResponse` and
matched at three in `MergeModal.renderPreview` with a wildcard for the rest. No
match over a `String` is exhaustive, so a status the browser does not know
renders through the conflicts arm — it prints `result.conflicts.size` and
iterates an empty list, producing "0 path(s) changed on both branches" with
nothing under it. `mergeEnabled` separately tests `status == "clean"`, so the
consequence is a wrong message rather than an unsafe merge. The fix is a closed
type on the wire, which makes the browser's match exhaustive and the wildcard
unnecessary.

`PLAN-RISKTRANSFORM.md` cites the superseded plan for this work at lines 9677,
9844, 9942 and 9955. Those four references repoint here in the same pass.

### EWB-D-10 — how in-code constants are held — RULED 2026-10-11

**Ruled: Option A, colocated closed sets, with four refinements.**

**A value that is one of a fixed set becomes an `enum` in the companion of the
type that owns it, and its codec is derived.** Where the external spelling
differs from the case name, `@jsonHint` carries the spelling and
`@jsonDiscriminator` names the tag field. This is not a new convention: it is
already in use at `ScenarioRequests.scala:12-17`.

**Practices 1 and 2 are marked for cleanup where the string is only a JSON
spelling, and the test is what the string is a contract with.**

The codebase currently attaches a wire string to an enum in four different ways:
a `def toWire` with an encoder built by `contramap` over it
(`SSEEvent.scala:28-42`, `ChangedNodesService.scala:16`); a constructor parameter
read by hand-written codecs (`ValidationErrorCode`, 35 cases); `contramap(_.toString)`
with a hand-written decoder whose wildcard silently maps any unknown value to one
case (`HistoryOperation`, `TreeHistoryResponse.scala:15-23`); and
`@jsonDiscriminator` with `@jsonHint` over a derived codec
(`ScenarioRequests.scala:12-17`). Four mechanisms for one job, and the last is
the idiomatic one.

**Where the string exists only to be the JSON spelling of a case, the hand-written
mechanism is redundant and goes.** zio-json derives the codec from the case name,
and `@jsonHint` carries the spelling for the cases that must differ. The
`HistoryOperation` decoder is the clearest case: its `case _ => Other` arm turns
an unrecognised wire value into a legitimate one, which a derived decoder would
reject instead.

**Where the string is a contract with something other than the JSON encoder, an
explicit declaration is correct and stays.** `ValuationBasis.symbol` is the
example: `"inherent"` is a keyword of the query language that the engine matches
exactly, so it must not follow the case name — renaming `Inherent` must not
rename the keyword. That is the shape of Practice 2, and here it is right, which
is why the practice is marked for cleanup by test rather than by blanket rule.
`FolQueryFailure.NodeSortName` passes the same test for the same reason.

Each conversion is behaviour-preserving only if the derived spelling matches the
hand-written one, so each carries a round-trip test asserting the exact wire
string before and after. `ValidationErrorCode`'s second field, `description`, is
a separate question — it is documentation rather than wire data, and whether it
survives is settled when that type is converted.

**Practice 3 survives, scoped.** A genuine single constant — not one of a set —
stays a named `val` in the companion of the type that owns it, with scaladoc
stating where the value came from, and only where ownership is unambiguous. A
value used by more than one owner is not a Practice 3 constant; it is either a
closed set that has not been recognised as one, or it belongs to a type neither
site owns.

**Practice 4 is the cleanup target.** A bare literal at a use site is replaced by
one of the above.

**User-facing message templates are held the same way** — named values, and
functions of their parameters where they take one, so a sentence cannot drift
from the values it interpolates (§7.7).

**The boundary with configuration.** ADR-016 governs values an operator sets:
`application.conf`, environment overrides, a single source of truth for things
changeable without a release. This rule governs values compiled in. The test:
if an operator could reasonably want it different without a new build, it is
configuration; if changing it changes a contract or the logic, it is a constant.

**Where the eight existing named constants land.**

| Constant | Disposition |
|---|---|
| `RepositoryFailure.WorkspaceSentinelPrefix` | deleted by this plan — there is no case to signal once cases are cases |
| `ErrorResponse.WorkspaceDomain` | deleted by this plan with `ErrorDetail` |
| `FolQueryFailure.NodeSortName` | Practice 3 survives. Single owner, and its scaladoc already states that it mirrors a server-side catalog value a drift-guard test binds |
| `FolQueryFailure.NodeNameLiteralSortName` | Practice 3 survives, same reason |
| `CompareState.MaxBranches` | Practice 3 survives. Derived from `PaletteData.namedFamilies.size`, browser-local, single owner |
| `CompareState.ComparedSlotCount` | Practice 3 survives, derived from the above |
| `RiskTreeKnowledgeBase.InherentConst` | **not a Practice 3 constant** — see below |
| `RiskTreeKnowledgeBase.ResidualConst` | **not a Practice 3 constant** — see below |

So six of the eight are settled: two die with the envelope and four have one
clear owner. There is no category of genuinely cross-domain constant, and no
central constants file is created for one.

**The two that fail the ownership test, and the foundation this plan lays for
them.** `"inherent"` and `"residual"` are declared as named values in
`RiskTreeKnowledgeBase` and also written as bare literals inside
`FolSymbols.reservedNames`, a `Set[String]` in `common`. Two copies of the same
pair exist in two modules and nothing binds them. They are a closed set nobody
recognised as one: `RiskTreeKnowledgeBase`'s own scaladoc calls them "the two
aggregate-valuation constants", which is the definition of a two-member set.

The foundation is created in this plan, the same way the typed telemetry
identifiers are: the type exists and the central definitions point at it, while
the scattered use sites are left to the standing rule.

```scala
/** The two bases a mitigation valuation can be read against.
  *
  * `symbol` is the name the query engine binds, so it is declared rather than
  * derived from the case name: renaming a case must not silently rename a
  * keyword of the query language. Reserved as a query symbol name, because a
  * constant of this sort used in any other slot binds as a type error.
  */
enum ValuationBasis(val symbol: String):
  case Inherent extends ValuationBasis("inherent")
  case Residual extends ValuationBasis("residual")
```

No `@jsonHint`, and no codec at all. This set never crosses JSON: `"inherent"`
appears in query text the user types and in the engine's symbol catalog, not as a
field of any request or response. A codec would be dead code, and the annotation
that configures one would configure nothing.

`FolSymbols.reservedNames` takes its two entries from
`ValuationBasis.values` rather than restating them, and
`RiskTreeKnowledgeBase.InherentConst` and `ResidualConst` are deleted in favour
of the enum. That removes the duplication rather than adding a third copy, which
is the point of doing the central definitions now instead of later.

**What is deliberately not in scope.** The other 21 entries of
`reservedNames` are predicate and function names, and they are a different
thing: that set is a *manually maintained mirror* of the server-side catalog,
with a drift-guard test binding it, and its scaladoc explains why the mirror has
to live in `common` where the catalog is off the dependency graph. It is not a
scattered literal problem and this rule does not touch it.

The eight remaining use sites that compare against `"inherent"` or `"residual"`
as bare strings — in `QueryServiceLive`, `MitigationApplication` and six test
files — are not opened by this plan. They are what the standing rule below
reaches.

**The standing rule this produces.** Two obligations, both permanent, both
written where the import rule is written so they bind the same way:

1. **Any file opened for any reason has its bare literals converted** — a value
   that is one of a set becomes a case of that set's enum, a genuine single
   constant becomes a named `val` in its owner's companion, and a user-facing
   sentence becomes a named template. Exactly as unused imports are removed in
   the same pass, without asking and without raising it as a decision.
2. **A set recognised during such a pass is created, not noted.** Finding two or
   more literals that are members of one set means declaring the enum and
   pointing the central definitions at it, which is what this plan does for
   `ValuationBasis`. Leaving a finding in a document is what produced the
   duplication being fixed here.

Both go in `~/.claude/CLAUDE.md` beside the unused-import rule, which is the
user's own file; the wording is proposed when the phase lands rather than written
by this plan. The project-level statement of the same rule goes in the
`adr-constraints` skill and its mirror, because that is the file read before new
types are designed, and a review criterion goes in the `code-quality-review`
skill and its mirror beside the existing primitive-typing passes.

**What the ruling produces.** A new accepted decision record, because no existing
one covers in-code constants and ADR-016 is the neighbour rather than the owner.
The `adr-constraints` skill distillation updated in `.github/skills/` and its
byte-identical `.claude/skills/` mirror. A line in the global instructions beside
the unused-import rule, making the cleanup a per-file obligation on any task that
opens a file. And §3, §4, §5 and §7 of this plan written to hold their strings
this way.

**Scope limit, stated because the rule is open-ended by design.** This plan
applies the rule to the files in its own inventory, plus the central definition
of each set it declares — `WireError`'s statuses, the preview status,
`ErrorDomain`, and `ValuationBasis`. It does not open a file for the sake of
cleaning it. Every other occurrence is reached when some later task opens that
file for its own reasons, which is exactly how the unused-import rule drains a
backlog without a dedicated sweep.

### EWB-D-11 — a defaulted `Option` is forbidden — RULED 2026-10-11

**Ruled: `Option[T] = None` as a default is forbidden outright, in every module
and every domain.** `Option[T]` itself stays; the **default** goes. A caller that
has nothing to pass writes `None` at the call site, where it is visible.

**Why the default and not the `Option`.** The two express different things. An
`Option` says the value may legitimately be absent, which is often true. A
default says *omitting the argument is free*, which is what makes dropped
information invisible. This plan contains the proof: `ErrorDetail.requestId` is
`Option[String] = None`, every one of the 26 builders propagates the default, no
call site in the repository passes it, and nothing ever failed. Had the default
not existed, every builder would have had to name a value and the field would
have been populated or deliberately refused.

**It costs nothing on the wire, which is the objection to expect.** A derived
zio-json decoder does not need the default to tolerate a missing key: an
`Option[T]` field decodes to `None` when the key is absent. The codebase already
proves it — `WorkspaceBootstrapResponse.expiresAt: Option[Instant]` carries no
default, is derived with `DeriveJsonCodec.gen`, and round-trips today. So
removing a default does not make a JSON field required.

**Size and reach.** 80 occurrences across 27 files in main source. This plan
converts those in its own inventory: `ErrorDetail` and `JsonHttpError` are
deleted outright, `ErrorResponse`'s builders go with them, `RequestContext` loses
all three of its defaults under `EWB-D-8`, and `ScenarioMergeResponse`,
`WorkspaceStore`, `AppError`, `SSEEvent`, `ZJS` and `WorkspaceState` are already
listed. The remaining files are reached by the standing rule, the same way unused
imports are.

**The standing rule.** Any file opened for any reason has its defaulted `Option`s
removed in that pass, and no new one is introduced. It goes in
`~/.claude/CLAUDE.md` beside the unused-import rule, is stated in the
`adr-constraints` skill and its mirror, and becomes a review criterion in the
`code-quality-review` skill and its mirror — a defaulted `Option` is a finding
there, at the same severity as a raw primitive standing in for a refined type.

**One case to check rather than assume.** A default is also how a `case class`
stays source-compatible when a field is added. Nothing in this codebase depends
on that — there is one first-party consumer, compiled from the same tree — but
the conversion of each file confirms it rather than presuming it.

#### The options EWB-D-10 weighed, retained

**Goal.** Decide the standing rule for a named value that lives in code rather
than in configuration, apply it to this plan's scope, record it as an accepted
decision, and make it a standing cleanup obligation on every file a task
touches, the way unused imports already are.

It is open here because it governs how every string in §3, §4 and §5 is written:
the nine wire cases, their statuses, the preview status, and the four business
domains. Those sections are rewritten once it is ruled.

The options, the codebase's four existing practices, and the recommendation are
presented in full in the session output and are transcribed here when the
decision is ruled. What the ruling produces: a new accepted decision record, an
amendment to the `adr-constraints` skill and its mirror, and a line in the
global instructions beside the unused-import rule.

ADR-016 is the neighbour, not the owner. It governs operator-tunable
configuration — values in `application.conf`, overridable by environment
variable — and says nothing about a constant compiled into the code. The
boundary between the two is part of this decision.

---

## 11. ADR alignment

| ADR | What this plan touches | Status |
|---|---|---|
| ADR-010 (error handling strategy) | the hierarchy, the HTTP mapping, the code smells | **Compliant, and it closes a violation.** The "String Matching on Errors" code smell instructs pattern matching on types; `isWorkspaceSentinel` matches a string prefix on the browser side, and this plan deletes it. **The ADR is amended in two places:** §5 "Pure HTTP Error Mapping" is rewritten to describe a one-way projection onto `WireError` instead of `encode` and `decode`, and §1's hierarchy listing gains `TreeLoadFailure` and the `SimError` to `DomainError` rename. |
| ADR-035 (error leakage prevention) | the boundary, the encoders, `getMessage` | **Compliant, and strengthened.** §2 "Typed Error Channel" quotes the `mapErrorOut` line and argues that `ErrorResponse` is the only type that can reach the wire; the argument holds more strongly for `WireError`, which has no field for a confidential value rather than relying on a builder dropping it. **§2 is amended** to quote the `oneOf` declaration and to state that the single projection is the auditable list of what is dropped. §1's exhaustiveness guarantee improves: the match is on 31 leaf types, not on 4 sub-traits. **§3 is also amended, and the earlier claim that it was unchanged was wrong.** Its second bullet reads "Resource-neutral errors collapse to an opaque status (a missing resource is a plain 404 regardless of variant)" — that constrains the status, not only the message, and `EWB-D-4` answers `410 Gone` for expiry. The bullet is narrowed to not-found and not-yours, which is the pair A13 was narrowed to on the same grounds; the `getMessage` sentence it opens with is unchanged and still binds. |
| ADR-036 (confidential internal identifiers) | `TreeNotInWorkspace`, `WorkspaceExpired`, the log line | Compliant. No wire case declares a `WorkspaceId`, so the output half holds structurally rather than by discipline. The input half is untouched. §6.2's log annotation is explicitly permitted: a confidential identifier may appear in a server log. |
| ADR-022 (secret handling) | the three workspace errors | Compliant and unchanged. None carries a credential, so R1's `final class` rule has nothing to be violated at the container. |
| ADR-001 and ADR-017 (API design) | `BaseEndpoint`, the DTO convention | Compliant, and it removes an exception. The error output now follows the response-DTO convention the architecture document already declares complete. |
| ADR-019 (frontend component architecture) | `GlobalError`, `ErrorBanner` | Compliant. Pattern 7's two-tier error presentation is unchanged; the classifier's input type changes and a `NotFound` display case is added. |
| ADR-028 (vague quantifier query pane) | `QueryProblem` | Compliant. The engine's rendered messages still reach the pane. `FolModelValidationFailure` and `FolEvaluationFailure` stop reaching it, which matches the ADR's own description of them as wiring faults rather than query errors. |
| ADR-002 (observability) | the correlation identifier, the resolve log line | **Currently violated and fixed here.** A correlation identifier that never reaches a client cannot correlate anything; `EWB-D-8` decides the fix. §6.2 fixes the resolve-failure line, which annotates a redacted constant today and so identifies no workspace. |

`docs/dev/ARCHITECTURE.md`'s "DTO/Domain Model Separation" section gains the error
path. The `adr-constraints` skill distillation is updated in the same pass, in
`.github/skills/adr-constraints/SKILL.md` and its byte-identical mirror
`.claude/skills/adr-constraints/SKILL.md`.

---

## 12. File inventory

The user creates `docs/dev/plans/PLAN-ERROR-WIRE-BOUNDARY-INVENTORY.md`. The paths
below are what the phases need. Paths marked `new:` do not exist yet.

**Created**

- `new: modules/common/src/main/scala/com/risquanter/register/http/errors/WireError.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/http/errors/QueryProblem.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/http/errors/ConflictKind.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/http/errors/Capability.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/domain/errors/TreeLoadProblem.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/domain/errors/MergeRefusal.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/common/ValuationBasis.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/http/controllers/WireEither.scala`
- `new: modules/common/src/test/scala/com/risquanter/register/http/errors/WireErrorSpec.scala`

Telemetry identifiers and their placeholder constants (`EWB-D-8`). The
identifiers go beside the existing nominal wrappers rather than into a new file,
because `OpaqueTypes.scala` is where every other one lives:

- `new: modules/server/src/main/scala/com/risquanter/register/telemetry/TelemetryPlaceholders.scala`

Message templates and wire-string holders (`EWB-D-10`, §7.7):

- `new: modules/app/src/main/scala/app/views/ErrorMessages.scala`
- `new: modules/app/src/main/scala/app/components/MergeMessages.scala`

**Edited — shared module**

- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDetail.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/JsonHttpError.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationErrorCode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationExtensions.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/endpoints/BaseEndpoint.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioMergeResponse.scala`
- `modules/common/src/main/scala/com/risquanter/register/common/FolSymbols.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala`

**Edited — server**

- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/ScenarioController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/QueryController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStoreLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/telemetry/RequestContext.scala`
- `modules/server/src/test/scala/com/risquanter/register/telemetry/RequestContextSpec.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/ScenarioControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala`

**Edited — browser**

- `modules/app/src/main/scala/app/state/GlobalError.scala`
- `modules/app/src/main/scala/app/state/WorkspaceState.scala`
- `modules/app/src/main/scala/app/core/ZJS.scala`
- `modules/app/src/main/scala/app/core/ThrowableExtensions.scala`
- `modules/app/src/main/scala/app/Main.scala`
- `modules/app/src/main/scala/app/state/AnalyzeQueryState.scala`
- `modules/app/src/main/scala/app/state/LECChartState.scala`
- `modules/app/src/main/scala/app/state/ScenarioMergeState.scala`
- `modules/app/src/main/scala/app/state/ScenarioState.scala`
- `modules/app/src/main/scala/app/state/TreeViewState.scala`
- `modules/app/src/main/scala/app/views/ErrorBanner.scala`
- `modules/app/src/main/scala/app/components/MergeModal.scala`
- `modules/app/src/main/scala/app/components/BranchBar.scala`
- `modules/app/src/test/scala/app/state/GlobalErrorSpec.scala`
- `modules/app/src/test/scala/app/state/LECChartStateSpec.scala`

**Edited — integration tests**

- `modules/server-it/src/test/scala/com/risquanter/register/http/QueryEndpointSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/LognormalBoundPositivityItSpec.scala`

**Edited — build and documents**

- `build.sbt` (version bump only)
- `docs/dev/ARCHITECTURE.md`
- `docs/dev/decision-records/ADR-010.md`
- `docs/dev/decision-records/ADR-035-error-leakage-prevention.md`
- `docs/dev/decision-records/ADR-037-in-code-constants.md` (written 2026-10-11; the phase amends the skills and the architecture document to match it)
- `.github/skills/adr-constraints/SKILL.md`
- `.claude/skills/adr-constraints/SKILL.md`
- `.github/skills/code-quality-review/SKILL.md`
- `.claude/skills/code-quality-review/SKILL.md`
- `docs/dev/plans/PLAN-RISKTRANSFORM.md` (repoint its four references at lines 9677, 9844, 9942, 9955)
- `docs/dev/plans/IMPLEMENTATION-PLAN.md` (repoint its three `TES-D-9` references)

The global instruction line that `EWB-D-10` produces goes in
`~/.claude/CLAUDE.md`, which is the user's own file and is written by the user,
not by this plan. The wording is proposed when the phase lands.

---

## 13. Verification plan

**Compile first, deliberately.** Phase 1 changes one declaration in
`BaseEndpoint`, which changes every endpoint's error output type. `sbt compile`
then names every site that still fails with `AppError`, and that is the complete
list of `.either` sites to convert. The compiler produces the migration list; it
is not assembled by hand.

**New tests in `WireErrorSpec`:**

1. **The status-to-case relation is one-to-one.** For every `WireError` case,
   assert the variant list contains exactly one entry for it, and that no two
   entries share a status. This is the property §4 depends on, and a future case
   added at an existing status must fail here rather than silently shadow an
   earlier one.
2. **No wire case can carry a confidential identifier.** Build each case from a
   value whose internal counterpart holds a `WorkspaceId`, serialise it, and
   assert the JSON contains no 26-character Crockford base32 identifier. The
   stronger guarantee is structural — `WireError` declares no `WorkspaceId` field
   — and a reviewer checks that by reading one file.
3. **`fromAppError` is total and drops what §5.1 says it drops.** One assertion
   per internal leaf type, 31 in all, naming the wire case produced. Exhaustive
   matching makes a missing arm a compile error; these assertions pin the mapping,
   which the compiler cannot.
4. **Every case round-trips through its codec.** Encode and decode each case and
   assert equality. This is where the `java.time.Duration` codec question in §3.4
   is answered.
5. **Three refusal paths produce identical bytes.** `AccessDenied(reason)`,
   `AuthForbidden(...)` and `AuthServiceUnavailable(...)` must serialise to the
   same JSON and the same status, so an authorization outage is not observable
   from outside (ADR-035 §2).
6. **A workspace absence and a tree-not-in-workspace produce identical bytes.**
   The same property, for the existence oracle.

**Replacing `ErrorResponseSpec`.** Its round-trip assertions have no subject once
`decode` is gone. What survives in substance moves into `WireErrorSpec`: every
assertion that a given internal error reaches the wire with a given shape. The
assertions that exist only to prove a lossy rebuild preserved a type are deleted,
and that deletion is listed in the phase report rather than done quietly.

**Browser tests.** `GlobalErrorSpec` gains a case per wire case and an assertion
that the expiry message states the lifetime it was given. A new test pins the
narrowed pre-validation behaviour: a `DependencyUnavailable` during pre-validation
must leave the stored capability key untouched. That test fails against the
current wildcard, which is what makes it a regression test for the fourth defect
in §1.

**Every phase.** All four tiers green: `commonJVM/test`, `server/test`,
`app/test`, `serverIt/test`. Leaked Docker containers, networks and volumes
matching `register_it_` are cleared before the integration tier runs, not only
after a failure.

**Version.** A PATCH bump per landed phase, mirrored from `build.sbt` into `.env`
and `.env.irmin`.

---

## 14. Relationship to the previous error-structure plan

The previous plan, `PLAN-TYPED-ERROR-STRUCTURE.md`, is superseded. Its one landed
change is the starting point stated at the top of this document. Three of its
unlanded items are carried in because they are still correct and would otherwise
be lost: the typed validation location (§6.4, Phase 3), the typed tree-load
problem and the `SimError` rename (Phase 4), and the two browser defects at §7.2
and §7.3.

Two of its items are deliberately dropped, because this design removes their
purpose rather than postponing them.

`ErrorValues`, a union type for a structured value slot inside `ErrorDetail`, was
needed because the generic envelope had nowhere to put a typed value. Each wire
case now declares the fields it needs, so a general-purpose value union has no
subject.

`ErrorDomain`, a closed set for `ErrorDetail.domain`, was needed because that
field was a free-text business-domain tag that `decode` read in order to recognise
workspace responses. `ErrorDetail` is deleted and nothing reads a domain tag.
