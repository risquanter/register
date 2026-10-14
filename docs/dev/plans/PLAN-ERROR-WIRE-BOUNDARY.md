# PLAN-ERROR-WIRE-BOUNDARY

Plan code `EWB`. Decisions in this document are labelled `EWB-D-<n>`.

**Status.** All twenty-four decisions, `EWB-D-1` to `EWB-D-24`, are ruled and
recorded in §10. Nothing blocks implementation on a decision. Approval is in
place: the token names this document and the file inventory exists beside it.
Phases 1, 1a and 1b have landed, and so has Phase 2's first item. What remains
is Phase 2's second item, Phase 2b, and Phases 3 to 6.

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
enum WireError:
  /** A request body the decoders rejected. Carries one entry per independent
    * failure, so a form shows every problem at once. */
  case ValidationRejected(errors: List[ValidationError], trace: TraceId)

  /** A well-formed request whose query the engine refused. */
  case QueryRejected(problem: QueryProblem, trace: TraceId)

  /** No such resource, or one the caller may not see. The two are one response
    * so that neither is an existence oracle. */
  case NotFound(trace: TraceId)

  /** A workspace past one of its two deadlines.
    *
    * Three values cross, and each has one job. `cause` is which deadline ended
    * this workspace, and it decides the sentence the browser writes. `lifetime`
    * is the absolute window, counted from creation, which use does not extend.
    * `idleLimit` is the idle window, counted from last use, which every use
    * pushes forward.
    *
    * Both windows cross on every expiry, not only the one that was hit, so the
    * response has one shape whatever the cause and the browser can state the
    * whole rule. They are deployment configuration, disclosed to a caller that
    * already holds the workspace's key.
    */
  case WorkspaceExpired(cause: ExpiryCause, lifetime: Duration, idleLimit: Duration, trace: TraceId)

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
  given validationRejectedCodec:    JsonCodec[ValidationRejected]    = DeriveJsonCodec.gen[ValidationRejected]
  given queryRejectedCodec:         JsonCodec[QueryRejected]         = DeriveJsonCodec.gen[QueryRejected]
  given notFoundCodec:              JsonCodec[NotFound]              = DeriveJsonCodec.gen[NotFound]
  given workspaceExpiredCodec:      JsonCodec[WorkspaceExpired]      = DeriveJsonCodec.gen[WorkspaceExpired]
  given accessDeniedCodec:          JsonCodec[AccessDenied]          = DeriveJsonCodec.gen[AccessDenied]
  given conflictCodec:              JsonCodec[Conflict]              = DeriveJsonCodec.gen[Conflict]
  given notSupportedCodec:          JsonCodec[NotSupported]          = DeriveJsonCodec.gen[NotSupported]
  given dependencyUnavailableCodec: JsonCodec[DependencyUnavailable] = DeriveJsonCodec.gen[DependencyUnavailable]
  given internalFaultCodec:         JsonCodec[InternalFault]         = DeriveJsonCodec.gen[InternalFault]
```

**One codec per case, and no discriminator on the enum itself.** §4 declares each
case as its own response body at its own status, so Tapir never encodes or
decodes the parent type and a `@jsonDiscriminator` on it would never appear in
any body. The status names the case; the body carries that case's own fields. The
inner payload enumerations below are the opposite situation — they are encoded as
part of a body, so each keeps its `kind` discriminator.

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

Granularity is `EWB-D-7`, ruled: the seven cases below. They are what the browser
can act on differently, and §7.5 states the treatment each one gets.

```scala
@jsonDiscriminator("kind")
enum ConflictKind:
  /** A uniqueness or state conflict with no further structure. */
  case Rejected()

  /** The caller's expectation of a resource's version is stale. */
  case StaleVersion()

  /** A merge the server refused, and why. `MergeRefusal` is the closed set of
    * reasons, from `EWB-D-9`. */
  case MergeRefused(scenario: ScenarioName.ScenarioName, refusal: MergeRefusal)

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

`WorkspaceExpired` carries two `java.time.Duration` values and an `ExpiryCause`.
`Instant` already round-trips through a derived codec in `WorkspaceResponse`, and
the browser has `java.time` via `scala-java-time`, declared in `build.sbt`.
Whether zio-json derives a `Duration` codec on both platforms is a verification
step in §13, not an assumption. The Java Virtual Machine side is confirmed —
`commonJVM` compiles with the derived codec — and the Scala.js side is confirmed
by `app/compile`. If a platform did not derive one, the two fields would become
`lifetimeSeconds: Long` and `idleLimitSeconds: Long` and the browser would build
the `Duration`.

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
      oneOfVariant(StatusCode.InternalServerError,  jsonBody[WireError.InternalFault])
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
type.

**There is no default variant.** `oneOfDefaultVariant` must be typed as the
parent enum, because it is the arm that accepts a status the list does not name,
so its body would have to decode as `WireError` itself — and under the per-case
bodies above no such body exists. `InternalFault` therefore takes an ordinary
`oneOfVariant` at `500`, and a status outside the nine is a Tapir decode failure
rather than a silently mistyped error.

**Why one status per case is required, not merely neat.** The browser's selection
is by status alone. Two variants sharing a status would make the first one listed
always win on decode, silently. That is the reason `QueryRejected` gets
`422 Unprocessable Entity` rather than sharing `400`, and the reason the internal
conflict errors collapse into one `Conflict` case with `ConflictKind` inside it
rather than becoming seven variants at `409`. Where several outcomes share a
status they share a wire case, and the inner enumeration discriminates. The
status-to-case relation is therefore one-to-one by construction, and §13 pins it
with a test.

Three statuses change what a caller observes. That set is `EWB-D-6`, ruled in
§10: all three changes are accepted.

---

## 5. The projection — where a field is dropped

### 5.1 `WireError.fromAppError`

One total function, in `WireError.scala`. It is the only place in the system
where a field stops crossing, so the full set of drops is readable in one screen.
Each arm **binds what it keeps** and writes `_` only for what it deliberately
gives up — the current encoder's `case _: TreeNotInWorkspace` binds nothing and
so discards the tree identifier along with the workspace identifier.

Two internal names collide with the wire case that carries them, `WorkspaceExpired`
and `AccessDenied`, and inside this file the wire case wins the name. The internal
ones are therefore imported renamed, as `AppWorkspaceExpired` and `AppAccessDenied`,
which is the one sanctioned use of a non-plain import (ADR-011 §2) and keeps each
arm reading left to right: internal name in, wire name out.

```scala
def fromAppError(e: AppError, trace: TraceId): WireError =
  // Bound once so each arm reads `t` rather than repeating the parameter. The
  // trace is not a field of the error: it is read from the active span at the
  // boundary that calls this, which is the only place an effect exists to
  // read it.
  val t = trace

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

    // Expired. The cause and both windows cross; the workspace identifier may
    // not (ADR-036), and the moment this one expired is a fact about one
    // workspace the browser has no use for. The cause is what lets the browser
    // name the deadline that actually ended this workspace.
    case AppWorkspaceExpired(_, ttl, idle, cause, _) => WorkspaceExpired(cause, ttl, idle, t)

    // Refused. The internal reason is dropped from all three: an explicit deny
    // and an unreachable authorization service must look identical (ADR-035 §2).
    case AppAccessDenied(_)                   => AccessDenied(t)
    case AuthForbidden(_, _, _, _)            => AccessDenied(t)
    case AuthServiceUnavailable(_, _)         => AccessDenied(t)

    // Conflicts
    case DataConflict(_)                      => Conflict(ConflictKind.Rejected(), t)
    case VersionConflict(_, _, _)             => Conflict(ConflictKind.StaleVersion(), t)
    case MergeConflict(scenario, refusal)     => Conflict(ConflictKind.MergeRefused(scenario, refusal), t)
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
    * Reads the trace from the active span, so the identifier a failure carries
    * is the identifier of the span the failing work ran under.
    */
  def wireEither(using tracing: Tracing): ZIO[R, Nothing, Either[WireError, A]] =
    effect.either.flatMap {
      case Right(value) => ZIO.succeed(Right(value))
      case Left(error)  => currentTraceId.map(trace => Left(WireError.fromAppError(error, trace)))
    }

private def currentTraceId(using tracing: Tracing): UIO[TraceId] =
  tracing.getCurrentSpanContextUnsafe.map { ctx =>
    TraceId.fromString(ctx.getTraceId).toOption.getOrElse(TelemetryPlaceholders.UnlinkedTrace)
  }
```

**Why `Tracing` arrives as a context parameter rather than in the environment.**
`serverLogic` fixes the environment. A controller builds
`ServerEndpoint[Any, Task]`, so the environment type is `Any` at every call site,
and an effect that requires anything in its environment cannot be handed to
`serverLogic` at all. A signature reading `ZIO[R & RequestContextRef, …]` is
therefore unimplementable here, whatever its intent. `Tracing` instead reaches
`wireEither` as a `using` parameter, supplied by one line in each controller —
`private given Tracing = tracing` — over a constructor field that the
controller's `makeZIO` reads from the environment once. That is the pattern every
other service in these controllers already follows. Seven controllers carry the
line: the six listed above and `SSEController`.

**Why the trace cannot be read when the error is constructed.**
`Tracing.getCurrentSpanContextUnsafe` returns `UIO[SpanContext]` — an effect —
because the OpenTelemetry context is held in a ZIO `FiberRef` rather than a
thread-local. That is what `OpenTelemetry.contextZIO` arranges in the telemetry
layer, and it is what makes the context survive the async boundaries of a
work-stealing executor. A failure value built deep inside a service is pure and
cannot perform an effect, so it cannot read the span. `wireEither` is effectful,
runs once per controller method, and is the first point after the failure where
an effect exists. That is why the trace is a parameter of the projection and not
a field of the error (`EWB-D-16`).

**What is read when no span is open.** OpenTelemetry returns its invalid span
context, whose trace identifier is 32 zero characters. That is exactly the value
`TelemetryPlaceholders.UnlinkedTrace` holds, so the placeholder is what the
mechanism yields rather than a fallback branch written to yield it. The
consequence for `§13` is stated there: inside a request there is a real
identifier, and the placeholder marks a failure that ran under no span.

Each site then changes `.either` to `.wireEither`. `BaseController` is unchanged:
its effect type stays `Task`, because `serverLogic` carries the typed error in the
`Either` and not in the effect's error channel.

The extension is declared on `ZIO[R, AppError, A]`, so every chain feeding it
must infer `AppError`. A single step returning `Task[A]` sets the whole
for-comprehension's error type to `Throwable`, because `Throwable` is the least
common supertype of the two, and the site then does not compile. `EWB-D-14` is
the narrowing that answers this, and `EWB-D-15` is what makes each widening
visible rather than silent.

### 5.3 The two failures that never reach a controller

`wireEither` projects a failure a controller produced. Two failures are answered
before a controller runs, or after one has already failed, so neither passes
through the projection, and today neither produces a `WireError` at all. Both are
closed here, because an error boundary a whole class of failure bypasses is not a
boundary (`EWB-D-21`, `EWB-D-23`).

**The output both of them emit is declared once.** The `InternalFault` variant's
status and body become a single named value in `BaseEndpoint`, and the `oneOf`
variant is built from it, so a catch-all cannot drift from the declaration the
endpoints publish:

```scala
/** The `500` output: the status and the body `WireError.InternalFault` is sent
  * with. Declared once because two separate things send it — the `oneOf`
  * variant below, and the catch-all that answers a defect, which never reaches
  * an endpoint's own output.
  */
val internalFaultOut: EndpointOutput[WireError.InternalFault] =
  statusCode(StatusCode.InternalServerError).and(jsonBody[WireError.InternalFault])
```

The variant then reads `oneOfVariant(internalFaultOut)` in place of
`oneOfVariant(StatusCode.InternalServerError, jsonBody[WireError.InternalFault])`.
The other eight variants are unchanged, because each is sent from one place only.

**A defect.** A ZIO defect is a failure ZIO treats as a bug rather than an
expected outcome, so it travels in the effect's cause rather than its typed error
channel. Nothing in this application converts one into a response, so
`ZioHttpInterpreter` folds the cause into
`Response.internalServerError(cause.squash.getMessage)` — a plain-text body
carrying the exception's own message. That is the disclosure ADR-035 forbids. It
is also why `EWB-D-15`'s reasoning needs this step: that ruling argued "a bug goes
to the defect channel, which is where `Throwable` belongs", which assumes the
defect channel is opaque at the wire, and it is not yet.

`RequestSpanInterceptor` closes it. It is already the outermost interceptor, so
one place sees every request, and it already holds the span whose identifier the
body needs. A Tapir `RequestInterceptor` is handed a `Responder`, which builds a
response from a declared endpoint output, so the catch-all emits
`internalFaultOut` rather than a hand-built body:

```scala
/** Answer a defect with the declared `500` output, carrying only the trace.
  *
  * A defect is a bug, so nothing about it may cross: the cause is logged
  * against the same trace and the body carries the identifier alone (ADR-035).
  * The response is built from `BaseEndpoint.internalFaultOut`, the same output
  * the endpoints declare, so a defect and a projected `InternalFault` are one
  * shape.
  */
private def answerDefect[B](
  responder: Responder[Task, B],
  request:   ServerRequest,
  trace:     TraceId,
  cause:     Cause[Throwable]
): Task[RequestResult[B]]
```

and the interceptor's body becomes, in place of its current single
`ZIO.logAnnotate(...)(downstream)`:

```scala
ZIO.logAnnotate(TraceAnnotation, trace.value)(
  downstream.foldCauseZIO(
    cause   => answerDefect(responder, request, trace, cause),
    success => ZIO.succeed(success)
  )
)
```

The log line is written inside the annotation, so an operator finds the cause
under the same trace the user is quoting. Nothing else about the interceptor
changes.

**A rejected request.** Validation happens in the Tapir codecs (ADR-001), so a
body, header or path segment an Iron smart constructor refuses is rejected before
a handler runs. Tapir's default decode-failure handler answers that with a
plain-text `400`. So the commonest client error on this API is the one error that
is not a `WireError` — while `WireError.ValidationRejected`'s own documentation,
"A request body the decoders rejected", describes exactly the case it never
receives.

A `DecodeFailureHandler` produces it instead. The handler is effectful in this
Tapir version, which is what lets it read the trace from the active span rather
than sending a failure with no identifier. New file
`modules/server/src/main/scala/com/risquanter/register/http/WireDecodeFailureHandler.scala`,
beside the two interceptors:

```scala
/** Answers a request the decoders rejected with `WireError.ValidationRejected`,
  * so every failure this service sends has one declared shape.
  *
  * `Tracing` is a parameter rather than an environment requirement because a
  * Tapir handler's effect type is `Task`, whose environment is `Any`.
  */
object WireDecodeFailureHandler:
  def handler(tracing: Tracing): DecodeFailureHandler[Task]

  /** One `ValidationError` per rejected input, naming the input and why it was
    * refused. The code comes from the existing set: an absent required input is
    * `REQUIRED_FIELD`, a value the codec could not read is `TYPE_MISMATCH`, and
    * a value it read and refused is `INVALID_FORMAT`.
    */
  private[http] def toValidationError(
    failure: DecodeResult.Failure,
    input:   EndpointInput[?]
  ): ValidationError
```

`toValidationError` is `private[http]` rather than private so the mapping is
testable without driving a request through the whole stack. It is the only part
of this file carrying a judgement, and no new error code is introduced:
`REQUIRED_FIELD`, `TYPE_MISMATCH` and `INVALID_FORMAT` are three existing cases of
`ValidationErrorCode`. The message is the decode failure's own description of what
it rejected, which names the input and the constraint and carries no internal
path.

Both are connected in `Application.startServer`, in the same
`customiseInterceptors` chain that already prepends the two interceptors:

```scala
serverOptions = ZioHttpServerOptions
  .customiseInterceptors[Any]
  .decodeFailureHandler(WireDecodeFailureHandler.handler(tracing))
  .prependInterceptor(SecurityHeadersInterceptor.interceptor)
  .prependInterceptor(RequestSpanInterceptor.interceptor(tracing))
  .corsInterceptor(CORSInterceptor.customOrThrow[Task](tapirCorsConfig))
  .options
```

**One trace reader, not three.** `currentTraceId` in `WireEither.scala` and
`currentTrace` in `RequestSpanInterceptor.scala` are the same four lines, and the
two additions above would be the third and fourth copy. The reader becomes one
definition, in a new file
`modules/server/src/main/scala/com/risquanter/register/telemetry/ActiveTrace.scala`,
which is where this module's other telemetry code lives:

```scala
/** The trace identifier of the active span.
  *
  * Reading it requires an effect because the OpenTelemetry context lives in a
  * fibre reference rather than a thread-local, which is what lets it survive
  * the async boundaries of a work-stealing executor. With no active span
  * OpenTelemetry returns its invalid span context, whose identifier is
  * thirty-two zeros, so work outside any span names an unlinked trace rather
  * than a plausible identifier that resolves to nothing.
  */
object ActiveTrace:
  def id(using tracing: Tracing): UIO[TraceId]
```

Both existing definitions are deleted and their call sites read `ActiveTrace.id`
(`EWB-D-24`). The interceptor and the decode-failure handler hold `Tracing` as an
ordinary parameter, so each supplies it explicitly: `ActiveTrace.id(using
tracing)`.

---

## 6. Changes to the internal hierarchy

### 6.1 The internal workspace errors carry what a debugger needs

The internal hierarchy exists for the server's own use, so it carries the
workspace identifier for diagnosis. `WireError` carries none, which is what
keeps ADR-036's output rule structural rather than a matter of discipline.

```scala
case class WorkspaceExpired(
  wsId:        WorkspaceId,
  ttl:         java.time.Duration,
  idleTimeout: java.time.Duration,
  cause:       ExpiryCause,
  expiredAt:   java.time.Instant
) extends DomainError:
  override def logMessage: String = "Workspace expired"

case class TreeNotInWorkspace(wsId: WorkspaceId, treeId: TreeId) extends DomainError:
  override def logMessage: String = s"Tree ${treeId.value} not found in workspace"
```

The member is named `logMessage` rather than `message` or `getMessage`, and the
name is load-bearing twice. It is not `getMessage`, because `AppError` is no
longer a `Throwable` and there is nothing to override (`EWB-D-15`). It is not
`message`, because three query-failure cases already carry a constructor
parameter of that name and the two would collide. `logMessage` also states where
the text goes: into the server's log, never to a client.

A workspace has two deadlines and either one ending it is enough, so the error
names which one did. `ttl` and `idleTimeout` are the two configured windows,
which are the same for every workspace in a deployment. `cause` is the deadline
this workspace actually passed. `expiredAt` is the instant it passed, which is
what makes a log line answer "how long ago" rather than only "how long were they
given".

All of them are constructible because nothing reconstructs them: under EWB-P-1
the browser receives a `WireError`, never one of these. The expiry arm of
`WorkspaceStoreLive.validateWorkspace` and of `WorkspaceStorePostgres` both hold
the record, so every value is in scope where the failure is built.
`WorkspaceRecord.expiry(now)` returns the deadline that has passed — its cause
and its instant together, the earlier one when both have passed — so the two
stores read one value rather than each deciding which window tripped:

```scala
enum ExpiryCause:
  case Lifetime     // createdAt + ttl; use does not postpone it
  case Inactivity   // lastAccessedAt + idleTimeout; every access pushes it forward

final case class Expiry(cause: ExpiryCause, at: java.time.Instant)

def expiry(now: java.time.Instant): Option[Expiry]
```

`ExpiryCause` lives in `common` beside `WorkspaceRecord`, because it crosses to
the client on the wire case above.

`WorkspaceNotFound` takes no identifier, and that is structural rather than a
choice: the lookup that failed is the thing that would have produced one. There
is no workspace to name, because no workspace was found.

```scala
case class WorkspaceNotFound() extends DomainError:
  override def logMessage: String = "Workspace not found"
```

`logMessage` carries neither identifier, which ADR-035 §3 requires of a message
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

**The shape — one nominal wrapper, not three.** `EWB-D-20` deletes
`RequestContext` entirely, and with it the two identifiers only that record held.
What survives is the one identifier that has readers:

```scala
/** The W3C Trace Context trace identifier: 32 lowercase hexadecimal characters,
  * a length and alphabet fixed by that specification rather than chosen here.
  */
type TraceIdConstraint = Match["^[0-9a-f]{32}$"]
type TraceIdStr = String :| TraceIdConstraint

case class TraceId(toTraceIdStr: TraceIdStr):
  def value: String = toTraceIdStr
```

It is a nominal wrapper rather than a bare refined string so that it cannot be
passed where another identifier of the same encoding is wanted (ADR-018), and it
gets a `fromString` returning `Either[List[ValidationError], TraceId]` plus JSON
codecs in its companion, matching every existing identifier type. It lives in
`OpaqueTypes.scala` beside the others rather than in a file of its own, because
that is where every other nominal wrapper lives.

`SpanId` and `RequestId` are **not** built. Each would have had exactly one
reader, the deleted record, so each would have been a type with no consumer —
which is the defect `EWB-D-8` was raised to remove, in a different shape.
`EWB-D-20` derives why the record goes.

**`RequestContext` is deleted rather than retyped**, together with
`RequestContextRef`, its layer, its five helper methods and `RequestContextSpec`.
Retyping it was this section's original answer, and `EWB-D-20` reverses it: the
record would have had no reader. Its four fields are accounted for without it.

The trace and the span were already held by ZIO on the request's own fibre.
`TelemetryLive` composes the tracing layer with `OpenTelemetry.contextZIO`, which
puts the OpenTelemetry context in a fibre reference, so
`tracing.getCurrentSpanContextUnsafe` returns both anywhere inside a request.
Copying them into a second fibre reference so that code could read the copy is
what the deletion avoids.

The trace reaches the log through `ZIO.logAnnotate`, which is ZIO's own fibre
reference for exactly this and which every logger already consults. The caller
reaches authorization through an ordinary parameter: `UserContextExtractor`
extracts the `UserId` per request and each controller passes it to
`authzService.check`, so it is in scope where it is used. Register's own request
identifier had no consumer at all and is not replaced.

**One placeholder stays, and what it means changes.** It was written to say "the
tracing layer is not connected". It now says "this work ran under no span", a
condition that still exists and always will: a startup task, the workspace
reaper's sweep, and any other effect outside a request belong to no trace.

```scala
object TelemetryPlaceholders:
  val UnlinkedTrace: TraceId = TraceId("00000000000000000000000000000000")
```

`UnlinkedSpan` goes with `SpanId`. Nothing reads a span identifier, so there is
no site that would need a placeholder for one.

The placeholder is held in the constants structure `EWB-D-10` rules. It is
deliberately recognisable rather than plausible — 32 zero characters — so a real
value is never mistaken for one. The zeros are not chosen here: they are what
OpenTelemetry's own invalid span context carries, which is why the value a
span-less read produces is already the placeholder, with no branch written to
produce it.

**`AppError` does not carry the trace.** The identifier is a parameter of the
projection, read from the active span at the boundary that projects. The reason
is mechanical and is derived in `§5.2`: reading the span context requires an
effect, because the OpenTelemetry context is held in a ZIO `FiberRef`, and a
failure value built inside a service is pure. There are 159 construction sites
across 19 files; making each one effectful, or threading a `TraceId` parameter
into all of them, is the cost that a parameter on one function avoids.

The value is identical either way, and that is what makes the cheaper option the
correct one rather than merely cheaper. A trace identifier is constant across a
request's whole span tree — only the span identifier differs between a parent
and its child — so a read at the boundary returns bit-for-bit what a read at
construction would have returned. Attaching it earlier buys no fidelity.
`EWB-D-16` records this and supersedes `EWB-D-8`'s carrier choice.

The trait therefore declares what every failure does carry, and nothing else:

```scala
sealed trait AppError:
  def logMessage: String
```

**Boundary.** None. Connecting the identifiers to real spans is this plan's work,
not a later plan's. `EWB-D-19` folds in what the retired
`PLAN-TELEMETRY-PLUMBING.md` would have done, and `§9`'s Phase 1b is where it
lands.

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
  case WireError.ValidationRejected(errors, _)      => ValidationFailed(errors)
  case WireError.QueryRejected(problem, _)           => RequestRejected(ErrorMessages.query(problem))
  case WireError.NotFound(_)                         => NotFound
  case WireError.WorkspaceExpired(c, life, idle, _)  => WorkspaceExpired(ErrorMessages.workspaceExpired(c, life, idle))
  case WireError.AccessDenied(_)                     => ServerError(ErrorMessages.Forbidden, None)
  case WireError.Conflict(conflict, _)               => Conflict(ErrorMessages.conflict(conflict))
  case WireError.NotSupported(capability, _)         => ServerError(ErrorMessages.capabilityUnavailable(capability), None)
  case WireError.DependencyUnavailable(t)            => DependencyError(ErrorMessages.DependencyDown, Some(t))
  case WireError.InternalFault(t)                    => ServerError(ErrorMessages.InternalFault, Some(t))
```

A `RequestRejected` display case is added, and it is the arm a refused query
takes. The existing code maps every query failure to `ServerError`, and that is
wrong rather than merely blunt: a refused query is a problem with text the user
typed, it answers `422`, and `ErrorBanner` renders `ServerError` with the
server-fault icon while reserving the warning icon for the user-input case. So
someone with a syntax error in a query would read a server-fault presentation
for their own typo. The case carries no trace, because the message names what to
correct.

That arm is reached more often than its inline handling suggests.
`toOutcomeEventStream` forks through `forkProvided` with the observer enabled,
so a query failure notifies the global observer **as well as** reaching
`AnalyzeQueryState`'s own slot. The banner shows it either way.

Two arms pass `None` for the trace explicitly, because `EWB-D-11` forbids a
defaulted `Option`. That is the rule working rather than a cost it imposes. A
denial and an absent capability both carry a trace on the wire, so `None` at the
call site is where the browser's decision not to show it becomes visible instead
of being hidden in a default.

**The browser displays it, and which cases show it is a presentation choice
rather than a data one.** The identifier is on every wire case, so every
`GlobalError` case can carry it; the two that render it are the ones where the
user has nothing else to act on and a reference is the only useful thing on
screen — a dependency outage and an internal fault. The others carry an
actionable message of their own, and a trace identifier beside it is noise.

**Every failure writes the identifier to the browser console, whichever case it
is.** The display choice above governs the banner, not the diagnostic record. One
line at the single point a wire error arrives, which is `ZJS` where
`fromWireError` is called:

```scala
dom.console.warn(s"request failed: ${e.productPrefix} trace=${e.trace.value}")
```

`productPrefix` is the case name. There is no `kind` field in the body to match
it against — §3 explains why the status names the case instead — so the console
line is what connects a response the developer sees in the network tab to the
case the browser classified it as. It goes in `ZJS` and not in a view, because a view
re-renders and would log the same failure again on every render.

Nothing is exposed that was not already visible: the response body carrying the
identifier is in the network tab of the same developer tools, and a `WireError`
holds no internal field by construction. It is the frontend's first log line —
there is no `console` call, `println` or `ZIO.log` anywhere in `modules/app`
today — and it adds no dependency, because scalajs-dom is already used for
`window` and `MouseEvent`.

`GlobalError.ServerError` and `GlobalError.DependencyError` therefore take
`trace: Option[TraceId]`, and they are the only two that do, because they are
the two that render it. `ErrorBanner` renders it in a muted line under the
message, selectable so it can be copied:

```scala
case GlobalError.ServerError(msg, trace) =>
  div(
    span(cls := "error-banner-message", msg),
    trace.map(t => div(cls := "error-banner-trace", t.value))
  )
```

`fromThrowable` stays, narrowed to its real job: a browser `fetch` failure is a
`TypeError` and is not a `WireError`, so it still becomes `NetworkError`.

Every sentence those arms name comes from `app/views/ErrorMessages.scala`, which
is where `EWB-D-10` and §7.7 put them, and together they are the whole of
`EWB-P-2` on this side. `ErrorMessages.workspaceExpired` is the one that pays
immediately — it states the configured lifetime from the `Duration`, which no
sentence written on the server could do without the server owning the wording.

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
  case WireError.WorkspaceExpired(_, _, _, _) | WireError.NotFound(_) =>
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

### 7.3 The duplicated sentence goes, and so does the callback

Two named templates describe one condition. `ErrorMessages.WorkspaceExpiredOnStartup`
is used by `Main.scala`'s `onExpired` callback and
`ErrorMessages.workspaceExpired(lifetime)` by `GlobalError`. Neither is a bare
literal, so the defect is not a hardcoded string: it is that one condition has
two sentences, which is the overlap the single-source rule exists to prevent
(`EWB-D-10`).

`onExpired` is deleted rather than retyped (`EWB-D-18`). It exists only so that a
pre-validation failure at startup can be reported quietly, and
`WorkspaceState.preValidate` moves from `runJsQuiet` to `runJs` so the ordinary
path reports it. `ErrorMessages.WorkspaceExpiredOnStartup` is then unreferenced
and is deleted with it, leaving `workspaceExpired(lifetime)` as the one sentence
for the condition — and the better one, because it states the lifetime the
workspace was given.

### 7.4 `ZJS`

Lines 148, 201 and 220 test the sentinel. Each becomes a match on
`WireError.WorkspaceExpired` and `WireError.NotFound`. The helpers' error
parameter type changes from `Throwable` to `RequestFailure` — the two-case type
`EWB-D-12` rules, whose `Wire` case carries the `WireError` — and
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
| `MergeRefused(InvariantBroken(trees))` | the merge was undone and the tree is as it was; this is a fault to report, not a conflict to resolve, and it is the one case that should carry the trace identifier prominently |
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

### 7.8 Why a view has nothing to show

`GlobalError` is the banner's input. The four inline display states are the
other half of ADR-019's two-tier presentation, and they are what a single view
reads to decide between a spinner, data and an error line. Each one carries its
failure as a composed `String` today: `LoadState.Failed`, `SubmitState.Failed`,
`ScenarioSubmitState.Failed` and `MergeSubmitState.Failed`.

That `String` is not a flattened `RequestFailure`, and this is the fact that
decides the shape. Five sites construct one of those states from a condition
that never involved a request: the local form's own validation errors at
`TreeBuilderView.scala:60` and `BranchBar.scala:261`, an absent workspace at
`TreeBuilderView.scala:80` and `ScenarioState.scala:88`, and an empty successful
response at `ZJS.scala:219`. So the state answers a wider question than
`RequestFailure` does. It answers "why does this view have nothing to show".

`EWB-D-13` rules one supertype and one new enumeration beside the
`RequestFailure` that already exists. All three live in one file,
`modules/app/src/main/scala/app/core/ViewFailure.scala`, because the supertype is
sealed and its cases must be declared where the compiler can see them all:

```scala
/** Why a view has nothing to show.
  *
  * Sealed, and extended directly by the two kinds of answer rather than
  * wrapping them in cases of its own. A `RequestFailure` is therefore already a
  * `ViewFailure`, so a request result becomes a display state with no
  * conversion and a pattern reaches a wire case without unwrapping a level.
  */
sealed trait ViewFailure

/** Why a request to the backend did not produce a result. */
enum RequestFailure extends ViewFailure:
  /** The server answered with one of its statuses and a typed body. */
  case Wire(error: WireError)

  /** The request never completed: no network, a DNS failure, a refused
    * connection, or a rejected cross-origin check.
    */
  case Transport(cause: Throwable)

  /** The server answered and the reply could not be read: a body no declared
    * variant decodes, or a status no variant claims. Carries the status,
    * because whether the request may have taken effect is the one thing the
    * user can act on, and the status is the only evidence of it.
    */
  case Undecodable(status: StatusCode)

/** A failure that never left the browser, so no request was made. */
enum LocalProblem extends ViewFailure:
  /** The form's own validation rejected the input before it was sent. */
  case FormInvalid(errors: List[ValidationError])

  /** The action needs a workspace and no key is held. */
  case NoActiveWorkspace
```

`RequestFailure` is restated here because it gains a supertype; its first two
cases are unchanged from `EWB-D-12`, and `BackendClient.endpointRequestZIO` keeps
declaring `IO[RequestFailure, O]`, so the narrower type still names exactly what
a request can produce. Moving it into this file is what lets the trait be sealed.

**The third case is `EWB-D-22`, and it exists because two situations were being
reported as one.** `BackendClient` maps every failure of `send` to `Transport`,
whose sentence is "The request did not reach the server. Nothing was changed."
But `send` also fails when the server *did* answer and the body did not decode:
`toRequestThrowDecodeFailures` makes a decode rejection throw, the throw becomes
a failed `Task`, and `.mapError(RequestFailure.Transport(_))` catches it. So a
server answer the browser could not read is reported to the user as a request
that never arrived, and the reassurance that nothing was changed is then false
exactly when a write may have landed.

`sttp`'s own `ResponseException` carries the response, so `BackendClient` reads
the status from it rather than inventing one:

```scala
.mapError {
  case e: ResponseException[?, ?] => RequestFailure.Undecodable(e.response.code)
  case other                      => RequestFailure.Transport(other)
}
```

The status is `sttp.model.StatusCode`, the typed value the client library already
holds, rather than an `Int` (ADR-038 §3). Three sites match on `RequestFailure`'s
cases and each gains one arm — `GlobalError.from`, `ErrorMessages.viewFailed` and
`ZJS.recordToConsole` — and the compiler names them, because an inexhaustive match
is a compile error in this build. `ZJS.settleLoad` already ends in a wildcard, so
an undecodable answer becomes the view's own error line like any other failure.

The new sentence is a named template beside `TransportFailed` (ADR-037), and
`TransportFailed` itself is unchanged: it keeps "Nothing was changed", which
becomes true of every failure it still describes once the undecodable case is
carried separately.

The four display states then carry `ViewFailure` in place of their `String`, and
each view composes its sentence where it renders. That is what unblocks §7.5:
a sentence frozen into the state at the moment a request failed cannot be
presented two ways, so the per-case conflict treatment and the trace line on
`InvariantBroken` have nowhere to read from until the state carries the case.

**One of those five sites is not a failure at all.** `ZJS.scala:219` maps a
`Right(None)` to `LoadState.Failed("Tree not found")`, which is a request that
succeeded and found nothing. The three views that read it confirm the reading:
`TreeDetailView.scala:153` renders a placeholder rather than an error, and
`DesignView.scala:145` and `AnalyzeView.scala:294` do the same. `LoadState`
gains an `Absent` case for it:

```scala
enum LoadState[+A]:
  case Idle
  case Loading
  case Absent                      // new — the request succeeded and found nothing
  case Loaded(data: A)
  case Failed(failure: ViewFailure)
```

That case also removes a live defect. Those three views detect the condition by
matching the message text as a literal, against a sentence written once at
`TreeViewState.scala:126` and nowhere declared. Renaming it breaks three views
with no compile error. It is the same code smell as
`RepositoryFailure.isWorkspaceSentinel`, which §8 deletes, and ADR-010 names it
as "String Matching on Errors".

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
| `RequestContext`, `RequestContextRef`, its layer and its 5 helpers | a second fibre reference holding a copy of what ZIO already carries (`EWB-D-20`) |
| `SpanId` and `RequestId`, with their two Tapir schemas | each had one reader, and that reader was the deleted record |
| `TelemetryPlaceholders.UnlinkedSpan` | nothing reads a span identifier, so nothing needs a placeholder for one |
| `ValidationExtensions.toZIOTask` | existed only to widen an `AppError` into a `Task`, which `EWB-D-15` forbids |
| `RiskTreeServiceLive.logIfUnexpected` | its non-`AppError` branch became unreachable, so it never logged |

`RequestContextSpec.scala` is deleted with the type it tests. Its assertions are
about fibre-reference mechanics — that a value set is read back, that a child
fibre inherits it, that the scope ends — and ZIO's own tests cover those. No
assertion about this application's behaviour is lost.

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
`BaseEndpoint`; `wireEither` and its call sites, which the compiler enumerates
rather than this plan; `GlobalError.fromWireError`
with its message functions; the three `ZJS` sites with the console line of §7.1;
the pre-validation match; the duplicated sentence and the `onExpired` deletion of
§7.3; and every deletion in §8. Every string this phase writes is
held as `EWB-D-10` rules.

Two changes to the error channel itself are in this phase, because `wireEither`
does not compile without them. `AppError` stops extending `Throwable`
(`EWB-D-15`), and the service and repository signatures narrow from `Task[A]` to
`IO[AppError, A]` (`EWB-D-14`). The narrowing reaches eight trait contracts and
their implementations; the compiler names every site, and `§13` states how that
list is produced rather than assembled by hand.

Two things moved into this phase from Phase 2, because the wire case cannot be
populated without them: `WorkspaceExpired`'s five internal fields with
`ExpiryCause` and `WorkspaceRecord.expiry` (§6.1), and both store construction
sites. What stays in Phase 2 is the log line.

**Phase 1a — the frontend treatment.** `MergeSubmitState.AlreadyRunning` and the
separated handler arm; `MergeModal`'s exhaustive preview match with its wildcard
removed; the per-case conflict rendering in §7.5; the `InvariantBroken`
presentation. Separate from Phase 1 only so that the boundary can be verified
green before the interface changes on top of it; both land before the plan is
done.

**Phase 1b — the span the boundary reads.** Phase 1 gives `wireEither` somewhere
to read a trace from; this phase makes a trace exist to be read. Four items, from
`EWB-D-19`:

1. `RequestSpanInterceptor`, a new Tapir request interceptor that opens one
   `SERVER` span per request and annotates the request's log lines with its
   trace identifier. It builds no request-context record — `EWB-D-20` deletes
   that type — so the span itself is the only thing it establishes.
   Its span name is the HTTP method plus the first path segment, with a
   workspace-scoped path's key segment replaced by a fixed placeholder: a span
   name is exported to the collector, and the capability key must never reach it
   (ADR-021).
2. Its connection in `Application.startServer`, beside
   `SecurityHeadersInterceptor.interceptor`.
3. The eighteen test files that name `TracingLive.console` or
   `MetricsLive.console` move to `TelemetryLive.configured`, so tests and
   production build the same single SDK instance. Three of them take tracing
   alone with no metrics layer, because they were given one during Phase 1's own
   work. `TracingLive.scala` and `MetricsLive.scala` are then unreferenced and
   are deleted, and `TelemetryLive`'s duplicated `console` and `otlp`
   construction collapses to one private builder parameterized by the exporter.
   `configured` becomes the object's only public member: once every test reads
   the exporter from configuration, nothing names a layer to pin one, so
   `console`, `otlp`, `tracingConsole` and `metricsConsole` have no callers and
   go with the duplication. `TestConfigs.telemetry.exporter` changes from `Otlp`
   to `Console`, because `configured` reads that field and the suites would
   otherwise start sending spans over gRPC to a collector that is not running
   during a test.
4. A test that the identifier a `WireError` carries equals the identifier of the
   span the failing work ran under, which is `§13`'s new property.

Item 3 comes before item 4 and that order is not arbitrary: while a test suite
builds its own tracing SDK separately from the metrics SDK, a test of the span
read is measuring a wiring that production does not use.

**Phase 2 — the display states, then the log line.** Two items, in this order.

*First, the inline display states*, from `EWB-D-13` and §7.8. `ViewFailure`,
`LocalProblem` and `LoadState.Absent`; the four display states retyped from
`String` to `ViewFailure`; the five local construction sites converted to a
`LocalProblem` case; the three literal `"Tree not found"` matches converted to
`LoadState.Absent`; and each view composing its sentence where it renders. This
is Phase 2's first step rather than Phase 1's work because Phase 1 lands the
boundary and proves it green, and retyping the display states reaches roughly
eleven view files that the boundary change does not touch.

It also settles what Phase 1a needs. §7.5's per-case conflict treatment and the
trace line on `InvariantBroken` both read the failure's case at render time, so
they cannot be written while the state holds a composed sentence.

*Then the log line.* `TreeNotInWorkspace`'s identifier and the log
annotation change:

```scala
private def logResolveFailure(outcome: Either[AppError, WorkspaceRecord]): UIO[Unit]
```

taking the outcome rather than the key, so the expiry arm can annotate
`workspace_id`, `created_at` and `expires_at` from the record.

**Phase 2b — the failures that bypass the boundary.** §5.3's two server-side
closures, and the client case that reports whatever survives them. Three steps,
in this order, because each one narrows what the next has to describe.

1. *The defect catch-all*, from `EWB-D-21`. `BaseEndpoint.internalFaultOut`
   declared once with the `oneOf` variant built from it; `ActiveTrace` replacing
   the two duplicated trace readers at their call sites (`EWB-D-24`);
   `answerDefect` and the `foldCauseZIO` in `RequestSpanInterceptor`. A new
   `RequestSpanInterceptorSpec` pins that an endpoint whose logic dies answers
   `500` with a body carrying the trace and nothing else.
2. *The decode-failure handler*, from `EWB-D-23`. `WireDecodeFailureHandler` and
   its connection in `Application.startServer`; a test of `toValidationError` per
   rejection kind, and one request-level test that a request missing a required
   header answers `400` with a `ValidationRejected` body.
3. *The third request-failure case*, from `EWB-D-22`.
   `RequestFailure.Undecodable`, `BackendClient`'s split `mapError`, and the
   three match sites the compiler names. Last because the first two steps remove
   the two commonest reasons a reply does not decode, so the sentence this case
   renders is written against what actually remains: a response this service did
   not write.

After this phase every failure this service sends has a declared shape, which is
what ADR-035's exhaustive-encoding rule asks for and what `§4`'s `oneOf` cannot
deliver on its own.

**Phase 3 — typed locations.** `ErrorLocation`, its two Iron refinements, and
`ValidationError.location`. Every construction site of `ValidationError` in
`common`.

**Phase 4 — typed load problems and the rename.** `TreeLoadProblem` replacing
`TreeLoadFailure.reason: String`; `SimError` renamed to `DomainError`.

**Phase 5 — the constants rule and its foundations.** The `ValuationBasis` enum,
`FolSymbols` deriving its two entries from it, and the deletion of
`RiskTreeKnowledgeBase`'s duplicate pair. Then what remains of the rule's own
delivery: the `code-quality-review` criterion and its mirror. The decision
record, its placement in the `adr-constraints` reference, the two global
instruction lines, and the project's own named-constants and defaulted-`Option`
rules in `.claude/rules/scala-style.md` all landed on 2026-10-11 and are no
longer this phase's work. The scattered use sites are reached by the standing
rule as later tasks open those files, not by this phase.

**Phase 6 — the response shapes.** ADR-038 defines the two shapes a response
may take and forbids a raw primitive where a refined type exists. Bringing the
non-conforming responses into it joins this plan rather than becoming a separate
one, ruled 2026-10-14.

The standing cleanup rule does not reach most of them on its own. Its trigger is
a file already open for another reason, and the response declarations in
`modules/common/src/main/scala/com/risquanter/register/http/responses/` and
`.../http/endpoints/` are opened by no other phase here. Phases 3 and 4 reach the
server-side halves — the services and controllers that build these responses —
because the rename in Phase 4 opens every file that names an internal error. The
declarations are deliberate work, and that is why they are a phase.

Five steps, smallest and least observable first, so each clause of ADR-038 is
exercised on a small surface before the largest one:

1. **The server-sent events.** `SSEEvent`'s hand-written `eventType`
   discriminator becomes a derived discriminated codec, and `NodeChangeType` and
   `ConnectionState` lose their `toWire`/`fromWire` pairs. First because it is
   the only item whose wire form no consumer reads yet: the events are
   server-only, not cross-compiled, and no test pins the serialized bytes. It
   also removes the trait-level encoder that encodes each payload as a quoted
   JSON string rather than an object, so the `data:` line stops being
   double-encoded. Files: `SSEEvent.scala`, `SSEController.scala`,
   `InvalidationHandler.scala`, `SSEHubSpec.scala`.

2. **The changed-node report, and the merge conflict entry.**
   `ChangedNodesResponse.status` is four values held as `String`; it becomes a
   sealed enum carrying the entries in its `Ok` case, and
   `NodeChangeEntry.nodeId` becomes `NodeId`. Second because it is the reference
   case for ADR-038's second clause on a cross-compiled response, and the
   browser already holds the type.

   `MergeConflictEntry(path: String, treeId: Option[String], nodeId: Option[String])`
   joins this step (`EWB-D-24`): `treeId` and `nodeId` become `Option[TreeId]`
   and `Option[NodeId]`, the same ADR-038 §3 case on the same kind of wire type,
   and leaving one raw pair behind is the half-migration that rule warns
   against. The same step turns around the one import that runs the wrong way:
   `MergeRefusal`, a domain error, imports `MergeConflictEntry` from
   `http/responses`, so a domain type depends on a response shape. Under
   ADR-038 §2 a refusal is an operation outcome and declares its own payload, so
   the entry is declared in `MergeRefusal`'s own file and
   `ScenarioMergeResponse` reads it from there — which reverses the dependency
   without duplicating the shape. Files: `ChangedNodesResponse.scala`,
   `WorkspaceTreeEndpoints.scala`, `ChangedNodesService.scala`,
   `WorkspaceTreeController.scala`, `ChangedNodesState.scala`,
   `ChangedNodesStateSpec.scala`, `MergeRefusal.scala`,
   `ScenarioMergeResponse.scala`, `ScenarioMergeService.scala`,
   `MergeMessages.scala`, `ScenarioMergeState.scala`.

3. **The history operation.** `HistoryOperation`'s hand-written string codec
   carries a `case _ => Other` arm, so an unrecognized wire value becomes a
   legitimate case instead of being rejected. Replacing it with a derived codec
   is a correctness fix and not only a typing one, which is why it comes before
   the two larger items. Files: `TreeHistoryResponse.scala`,
   `TreeHistoryService.scala`, `TreeHistoryServiceSpec.scala`,
   `TreeHistoryStateSpec.scala`.

4. **The distribution preview.** Four defects in one type: `Schema.any` erases
   the generated schema for the whole response, an `Array` field makes equality
   reference equality, `distributionType` is a two-value set held as `String`,
   and the file sits in `http/requests/` though it is a response. The file moves,
   which makes this the most disruptive single step, so it comes after the three
   that do not move anything. Files: `DistributionPreviewResponse.scala` (moved
   to `http/responses/`), `DistributionPreviewEndpoints.scala`,
   `DistributionPreviewService.scala`, `DistributionPreviewServiceSpec.scala`,
   `DistributionChartState.scala`, `DistributionSpecBuilder.scala`.

5. **The simulation response and the untyped bodies.** `SimulationResponse.name`
   is a `String` where `SafeName` exists, and its `quantiles` map holds a fixed
   key set as `Map[String, Double]`. The same step types the four bodies that are
   a bare primitive or map: probability of exceedance returns `Double` where
   `OccurrenceProbability` states the interval, the workspace reap count returns
   `Map[String, Int]`, and `/health` returns `Map[String, String]`. Last because
   `SimulationResponse` is the response four endpoints share, so it changes the
   most observable wire at once, and because it is the only step reaching the
   integration tier. Files: `SimulationResponse.scala`, `WorkspaceResponse.scala`,
   `RiskTreeEndpoints.scala`, `WorkspaceLifecycleEndpoints.scala`,
   `WorkspaceAnalysisEndpoints.scala`, `SystemEndpoints.scala`,
   `WorkspaceTreeController.scala`, `WorkspaceLifecycleController.scala`,
   `WorkspaceAnalysisController.scala`, `SimulationResponseSpec.scala`,
   `WorkspaceLifecycleControllerSpec.scala`, `HttpApiIntegrationSpec.scala`,
   `SeedReproducibilityItSpec.scala`, and the app readers `ZJS.scala`,
   `SubmitState.scala`, `ScenarioSubmitState.scala`, `TreeListState.scala`,
   `TreeViewState.scala`, `WorkspaceState.scala`, `TreeBuilderView.scala`,
   `TreeListView.scala`.

One thing to settle inside step 5 rather than in advance: `/health` is declared
twice, in `RiskTreeEndpoints` and again in `SystemEndpoints`, with the same path
and the same response type. Whether one of the two is unreferenced is decided by
reading the route lists when the step opens those files, and an unreferenced
declaration is deleted rather than typed.

Every step ends with the whole suite green, and the phase takes one PATCH bump at
its end, as the other phases do.

Phase 1b is the telemetry phase, and there is no separate telemetry plan.
`PLAN-TELEMETRY-PLUMBING.md` is retired: its four open questions are ruled in
`EWB-D-19` and its work is Phase 1b above. This plan types the structure and
connects it, so no part of it is left marked as unfillable.

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

### EWB-D-8 — the correlation identifier — RULED 2026-10-11, carrier superseded by `EWB-D-16`

**Ruled: every `WireError` case carries a trace identifier, and the browser
displays it.** That is Option A below and it stands. The typed identifiers and
the retyped `RequestContext` are in §6.6.

**Two parts of this ruling no longer hold, and both are marked here rather than
only at the top of the document.**

The sentence "the identifier is a field of the error, so `fromAppError` reads it
from its single argument" described how the identifier reaches the projection.
`EWB-D-16` reverses that: the identifier is a parameter of `fromAppError`, read
from the active span by `wireEither`. What reaches the browser is unchanged — the
same value, on the same wire cases — so the ruling's goal and its answer are
intact, and only the carrier is different. `§5.2` derives why.

The three typed identifiers this ruling introduced are now one. `EWB-D-20`
deletes `RequestContext`, which was the only reader `SpanId` and `RequestId`
would ever have had, so both go with it. `TraceId` keeps seven readers and
stays. The goal stated below — that a person seeing a failure has something to
quote which resolves to a log line — is still met in full, because the trace is
on the screen and on every log line the request wrote.

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
The `adr-constraints` skill updated in `.claude/skills/`, which is the only copy.
A line in the global instructions beside
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

### EWB-D-12 — what carries a failed request in the browser — RULED 2026-10-11, third case added by `EWB-D-22`

**Ruled: Option A.** A client-side failure type that is not a `Throwable`, in
`app/core`:

```scala
enum RequestFailure:
  /** The server answered with one of the nine statuses and a typed body. */
  case Wire(error: WireError)

  /** The request never completed: no network, DNS failure, connection refused,
    * or a rejected cross-origin check. The browser's `fetch` rejects with a
    * JavaScript `TypeError` here, so there is no status and no body. */
  case Transport(cause: Throwable)
```

`BackendClient.endpointRequestZIO` returns `IO[RequestFailure, O]`, the five
`ZJS` extensions lose their `E <: Throwable` bound, and the seven request buses
declared `Either[Throwable, X]` become `Either[RequestFailure, X]`.
`GlobalError` then has one match with two arms, which are exactly
`fromWireError` and the narrowed `fromThrowable`.

**Two cases here, three after `EWB-D-22`.** The reasoning below is about the
distinction between an answer and no answer, and it holds unchanged. What it
missed is that `Transport` was catching a third situation as well: the server
answered and the body did not decode. `EWB-D-22` adds `Undecodable(status:
StatusCode)` for it and §7.8 carries the declaration; `GlobalError.from` then has
three arms rather than two.

**Why a second type is needed at all.** Two different failures reach the
browser and only one of them is a `WireError`. A `WireError` exists only when
the server answered; a transport failure is the browser reporting that the call
never happened. §7.4 settles the first half by typing the helpers on the wire
error, and this decision names what carries the second half, which §7.4 left
open while §7.1 kept `fromThrowable` alive for it.

**What was rejected.** Three alternatives were weighed. They are written down
because the cheapest one is what a later reader reaches for again.

**Making `WireError` extend `Throwable`.** This costs the fewest files.
`BackendClient` would not change, and `Either`'s covariance would keep every
`Either[Throwable, X]` bus compiling. It was rejected because the `Throwable`
bound exists only to satisfy this client's own call chain rather than anything
ZIO requires: ZIO's error channel is an unconstrained type parameter, so
`IO[RequestFailure, O]` is ordinary.

The objection is not about where the type lives. `WireError` belongs in
`common` by definition, because both sides use it. The objection is about what
kind of thing it would become. Every other type that crosses the wire is plain
data. `Throwable` adds exception apparatus — `getMessage`, stack-trace capture,
suppression — to a value whose only job is to be decoded and matched on. A
constraint that exists only because of how this one client calls the backend
would then be attached permanently to a type the server also compiles.

**A client-local wrapper extending `Throwable`.** This has the same cost as the
option above. It additionally needs an unwrapping step at every match site: a
`final class` extending `Throwable` is not a `case class`, so it has no
generated `unapply`, and every site must read
`case f: WireFailure => f.error match { … }`.

**A sealed supertype in `common`, with `WireError` extending it directly.**
This is the closest alternative. It was raised and retracted on 2026-10-12. Its
benefit is real: it removes one construction call and one level of pattern
nesting, because a `WireError` would flow into `IO[RequestFailure, O]` as
itself.

Its structure is forced by one language rule. `sealed` requires every direct
subtype to be declared in the same source file, which is what lets the compiler
promise that a match is exhaustive. Exhaustivity is the mechanism this plan
exists to buy, so the supertype has to be `sealed`. A `sealed` supertype then
pulls both of its subtypes into one file. That file has to sit in `common`,
because `WireError` is there and `common` cannot see `app`. So the transport
case would be declared in `common` as well.

That placement is not the objection. A case that only one side of a shared
hierarchy produces is unremarkable. The objection is what the arrangement does
to `WireError`'s meaning. Today `WireError` means one thing: what the server
sent. `fromAppError` is total onto it, §4's `oneOf` enumerates it, and every
case carries an HTTP status. As a subtype it would also mean one half of a
browser-side union. The server's own error declaration would then be listing
the variants of a type that has a browser-transport sibling. The type stops
meaning one thing, and §4 is where that is read.

**What the chosen enum does not cost.** It adds no unwrapping step. An enum
case has a generated `unapply`, so a pattern nests in one expression:

```scala
case RequestFailure.Wire(WireError.WorkspaceExpired(cause, lifetime, idleLimit, _)) => …
```

The unwrapping cost named above belongs to the `Throwable` wrapper alone.

---

### EWB-D-13 — what carries a failure in an inline display state — RULED 2026-10-12, shape revised 2026-10-13

**Ruled: a sealed supertype, `ViewFailure`, that `RequestFailure` and
`LocalProblem` each extend directly.** The types are written out in §7.8. The
four inline display states carry `ViewFailure` in place of a composed `String`,
and each view composes its sentence where it renders. `LoadState` additionally
gains an `Absent` case, because one of the states reached today is a successful
request that found nothing rather than a failure.

Phase 1 keeps the `String` and fills it from one pure function, so the boundary
can land green before eleven view files are touched. Phase 2's first step is
this change. Both are in this plan, which is what `G8` requires of one epic.

**What was revised, and why it is not a reopening.** The ruling of 2026-10-12
chose a wrapping enumeration: `ViewFailure` with a `Request(failure:
RequestFailure)` case and a `Local(problem: LocalProblem)` case. The revision
keeps both constituent types exactly as they were and changes only how they
join: instead of each being wrapped in a case of a third enumeration, each
extends a sealed trait. The set of states representable is identical. What
changes is the depth a pattern reads at, and the cost paragraph below is what
the revision removes.

**Why the rejection below does not apply to the revised shape.** The argument
against a flat shape was that `RequestFailure` must not gain a `Local` case,
because `BackendClient.endpointRequestZIO` returns `IO[RequestFailure, O]` and
can never construct one. That argument is untouched, and the test it rests on is
whether a case names something a request can actually produce. `ViewFailure` is a
supertype of `RequestFailure` rather than a case list containing it, so the type
that names what a request can do still names exactly that, and the display states
name something wider by referring to the supertype.

`EWB-D-22` later adds a third case, `Undecodable`, and passes that test rather
than weakening it: an answer the browser cannot decode is something a request
produces, and `BackendClient` is the one place that constructs it. A `Local` case
would still be wrong here for the reason given below.

**Why not a third arm on `RequestFailure`.** This was the original proposal: a
`Local` case beside `Wire` and `Transport`, with the four display states typed
directly on `RequestFailure` and no second type at all.

It was rejected for the reason that decided `EWB-D-12` against a sealed
supertype in `common`. `BackendClient.endpointRequestZIO` returns
`IO[RequestFailure, O]`. It can produce `Wire` and it can produce `Transport`.
It can never produce `Local`, because a local precondition means no request was
issued. The function's error type would therefore declare a case the function
cannot construct, and every match on its result would carry an arm that is dead
by construction. The name goes false at the same moment: `RequestFailure` would
stop meaning "how a request failed" and start meaning "that, and also things
that are not requests". One type meaning one thing is the property both
decisions protect.

**Why not two parallel enumerations.** A display type repeating `Wire` and
`Transport` as its own cases, with a widening function from `RequestFailure`,
keeps every match one level deep. It duplicates both cases in two types, so a
tenth wire-failure mode would have to be added twice, and it needs a conversion
call at every site where a request result becomes a display state.

**The cost the revision removes.** Under the wrapping shape, a site that cares
about one specific wire case read three levels deep:

```scala
case ViewFailure.Request(RequestFailure.Wire(WireError.WorkspaceExpired(cause, lifetime, idleLimit, _))) => …
```

Under the sealed supertype the same site reads two:

```scala
case RequestFailure.Wire(WireError.WorkspaceExpired(cause, lifetime, idleLimit, _)) => …
```

The outer level is gone because `RequestFailure.Wire` is already a `ViewFailure`,
so no wrapper has to be unwrapped to reach it. Converting a request result into a
display state also stops needing a call: a `RequestFailure` is a `ViewFailure`
already.

### EWB-D-14 — service and repository signatures name their failures — RULED 2026-10-13

**Ruled: narrow them. Every service and repository signature returning `Task[A]`
becomes `IO[AppError, A]`.**

**Goal.** Decide whether the eight service and repository traits keep declaring
`Task[A]`, or declare the closed set of failures they can actually produce.

The two are different claims about the same method. `Task[A]` is an alias for
`ZIO[Any, Throwable, A]`, so it says "this can fail with anything a Java
exception can be". `IO[AppError, A]` is `ZIO[Any, AppError, A]`, and because
`AppError` is sealed it says "this fails with one of 31 named outcomes, and the
compiler knows which".

It is not a cosmetic choice, because `wireEither` is declared on
`ZIO[R, AppError, A]`. Scala infers a for-comprehension's error type as the least
common supertype of its steps, so one step returning `Task[A]` sets the whole
chain's error type to `Throwable` and the chain then cannot be passed to
`wireEither`. The widening is not local to the step that caused it.

**Option A — narrow the service and repository traits.** Eight trait contracts
change, and their implementations with them.

*Pros:* a signature states what can go wrong, which is the guarantee the whole
plan rests on. Every site that currently loses the type is named by the compiler
once, rather than discovered one failure at a time. A genuine defect — something
that is a bug rather than an expected outcome — has to be routed to ZIO's defect
channel explicitly, which is where it belonged already.
*Cons:* it reaches well beyond the files the boundary itself needs. Each raw
exception currently absorbed by `Task` must be classified: either it is an
expected outcome and gets an `AppError` case, or it is a bug and becomes a
defect. That classification is judgement, not mechanics.

*Concretely:* `queryService.evaluate` returns `Task[QueryResponse]` today. In
`QueryController` that one step makes the whole for-comprehension `Throwable`,
so `.wireEither` does not compile on it, and the error says the extension is not
a member of `ZIO[Any, Throwable, QueryResponse]` — which names the symptom and
not the cause.

**Option B — convert at each boundary instead.** Leave the traits as `Task` and
write `.refineToOrDie[AppError]` at each of the 23 controller sites.

*Pros:* the change stays inside the controllers.
*Cons:* it converts a type error into a runtime one. `refineToOrDie` turns an
unmatched failure into a defect at run time, so a widening that the compiler
could have named becomes a crash in production instead. It also repeats the
conversion 23 times, and a 24th site added later that forgets it compiles.

**Trade-off.** Correctness of the signatures against the size of the change.
Option A is larger now and is the only one of the two that makes the guarantee
hold at compile time.

### EWB-D-15 — `AppError` stops extending `Throwable` — RULED 2026-10-13

**Ruled: remove `extends Throwable`. It is done inside this plan, not deferred.**

**Goal.** Decide whether the sealed failure hierarchy continues to extend
`Throwable`.

**Why it is the root cause rather than a tidiness matter.** `Task[A]` fails with
`Throwable`. While `AppError` is a `Throwable`, a `Task`-returning step accepts
an `AppError` without complaint, so the widening of `EWB-D-14` is invisible at
the point it happens and surfaces only much later, as an error about a type that
no longer mentions `AppError`. Removing the supertype is what makes each widening
site name itself.

The two concepts are also distinct on their own terms. ZIO's error channel `E`
names expected failures a caller is meant to handle; a bug goes to the defect
channel, which is where `Throwable` belongs. An `AppError` is an expected outcome
by construction — that is why it is enumerated — so it is data, not an exception.

**Option A — remove it now, inside this plan.**

*Pros:* `EWB-D-14`'s work becomes compiler-enumerated instead of
search-enumerated. The hierarchy stops carrying exception apparatus it never
uses: stack-trace capture, `getCause`, suppressed exceptions.
*Cons:* every site that relied on the supertype must change in the same pass.
`orDie` is the main one: it requires `E <: Throwable`, so each use becomes
`orDieWith(f: E => Throwable)`. `getMessage` overrides on 31 leaves override
nothing any more and are renamed.

*Concretely:* `WorkspaceStorePostgres` calls `.orDie` on an effect whose error is
an `AppError`. After the removal that no longer compiles, and the file gains one
private function, `asDefect(e: AppError): Throwable`, used at its three such
sites. The conversion is now written down where it happens instead of being
implicit in a supertype.

**Option B — defer it to a follow-up plan.**

*Pros:* this plan stays closer to the wire boundary it is named for.
*Cons:* `EWB-D-14` then has no mechanical way to find its own sites, so the
narrowing would be done by searching. And the two changes have to land together
regardless: narrowing a signature to `IO[AppError, A]` while `AppError` is still
a `Throwable` leaves every `Task` call site compiling and silently widening, so
the deferral would ship the cost of the narrowing without its guarantee.

**Trade-off.** Plan scope against whether the narrowing can be verified at all.
`G8` also bears on it: the two are one change in two halves, and splitting them
would park the half that makes the other sound.

### EWB-D-16 — where the trace identifier is read — RULED 2026-10-13

**Ruled: it is a parameter of `fromAppError`, read from the active span by
`wireEither`.** This supersedes `EWB-D-8`'s carrier choice and nothing else about
that ruling. The derivation is in §5.2 and §6.6.

**Goal.** Decide whether the trace identifier enters a failure when the failure
is constructed, or is read at the boundary that projects it onto the wire.

The intent behind the question was that the identifier should be an ordinary
immutable field of `AppError`, set once at construction like any other — which is
the shape a reader would expect and the one that needs no explanation.

**Option A — a parameter at the boundary.** `fromAppError(e: AppError, trace:
TraceId)`, with `wireEither` reading the span.

*Pros:* one function gains one parameter, at one call site. No construction site
changes. `AppError` stays pure data with no field that only one consumer reads.
*Cons:* the identifier is not visible in the failure value itself, so a reader of
`AppError` cannot see that a trace is associated with it at all. The association
lives in the projection.

*Concretely:* a service fails with `RepositoryFailure("…")` deep inside a tree
read. The value carries no identifier. When it reaches the controller,
`wireEither` reads the active span, and the `WireError` the browser receives
carries that span's trace. The user sees the same string either way.

**Option B — an immutable field set at construction.** Every `AppError` leaf
takes a `TraceId`.

*Pros:* the identifier is part of the failure, which is the clearer model, and
`fromAppError` needs no second argument.
*Cons:* it cannot be built. Reading the span context requires an effect —
`Tracing.getCurrentSpanContextUnsafe` returns `UIO[SpanContext]`, because
`OpenTelemetry.contextZIO` holds the context in a ZIO `FiberRef` rather than a
thread-local. Construction sites are pure. So each of the 159 sites across 19
files would have to either become effectful or receive a `TraceId` threaded down
from the controller through every intervening signature.

**Option C — a mutable field assigned at the boundary.** What this plan
originally specified: a `private var` on the trait, with `attachTrace`.

*Pros:* keeps the identifier on the error, as Option B wants, without threading
it through construction.
*Cons:* it makes the hierarchy mutable, and it only worked at all because
`AppError extends Throwable` made mutability unremarkable. `EWB-D-15` removes
that supertype, so the justification goes with it. The existing `var` is in fact
evidence for this ruling rather than against it: the field had to be mutable
precisely because the value cannot be known at construction.

**Trade-off.** The model a reader would expect against what the mechanism allows.
What settles it is that **the value is identical either way.** A trace identifier
is constant across a request's entire span tree — only the span identifier
differs between a parent and its child — so reading it at the boundary returns
bit-for-bit what reading it at construction would have. Option B therefore costs
159 sites and buys no fidelity, which is what makes Option A correct rather than
merely cheaper.

### EWB-D-17 — how every `WireError` case exposes its trace — RULED 2026-10-13

**Ruled: an abstract member declared on the enum itself, not a `Traced`
supertype.**

**Goal.** Every `WireError` case carries a `TraceId` (`EWB-D-8`). Decide how code
reads it from a value whose case is not known.

**Option A — an abstract member on the enum.**

```scala
enum WireError:
  def trace: TraceId
```

Each case declares `trace` as a field, which implements the member.

*Pros:* one line, inside the type that already exists. Scala 3 permits an
abstract member on an `enum` and each case's field satisfies it — verified by
compiling it rather than assumed.
*Cons:* none identified.

**Option B — a `sealed trait Traced` that `WireError` extends.**

*Pros:* the capability is nameable and reusable if another type later needs it.
*Cons:* a second type with one implementor and one member, introduced for a
reuse that nothing requires. Every match site gains nothing and the file gains a
declaration.

**Trade-off.** There is effectively none, which is why this is recorded as a
ruling rather than presented as an open choice. It is written down because the
plan's earlier text named the `Traced` supertype, and a reader of that text would
otherwise implement it.

### EWB-D-18 — the startup expiry callback — RULED 2026-10-13

**Ruled: delete `onExpired`. `WorkspaceState.preValidate` moves from
`runJsQuiet` to `runJs`, and `ErrorMessages.WorkspaceExpiredOnStartup` is deleted
with it.** §7.3 states the change.

**Goal.** One condition — the held workspace key has expired — has two sentences
written for it. Decide whether the startup path keeps its own.

**Option A — delete the callback and its sentence.** Pre-validation at startup
reports through the ordinary error path.

*Pros:* one condition, one sentence, and it is the better sentence: it states the
lifetime the workspace was given. The duplicate cannot drift from the original,
because there is no duplicate. `runJsQuiet` exists to suppress reporting, and
suppressing the report is what required a separate sentence in the first place.
*Cons:* the failure is now reported through the banner at startup, where
previously it was silent. That is a visible behaviour change, and it is the
intended one.

*Concretely:* a user returns to a bookmarked workspace URL whose key expired
overnight. Today the startup check fails silently and the user sees an empty
view. After the change the banner states that the workspace expired and how long
it was given.

**Option B — keep the callback and point it at the shared sentence.**

*Pros:* startup stays silent, so nothing about the first paint changes.
*Cons:* it keeps a code path whose only purpose is to not report a failure the
user needs to know about. The second sentence goes, but the reason it existed
stays.

**Trade-off.** Whether a failed startup pre-validation is something the user
should be told about. Option A says yes.

### EWB-D-19 — the telemetry plumbing folds into this plan — RULED 2026-10-13

**Ruled: `PLAN-TELEMETRY-PLUMBING.md` is retired. All of its work is Phase 1b of
this plan, and no task-list item is left behind.** The user deletes that
document.

**Goal.** This plan reads a trace from the active span. Decide whether opening
that span is this plan's work or a separate plan's.

**What is already built, verified rather than assumed.** The telemetry stack is
present and live: `zio-opentelemetry` 3.1.13 is on the classpath, and
`Application` composes `TelemetryLive.configured`, so `Tracing` is a service the
controllers can already take. Five spans exist and are conventional — an
`INTERNAL` span per service method in `QueryServiceLive`, `RiskTreeServiceLive`
and twice in `CachedResultResolverLive`, and a `CLIENT` span for the outbound
SpiceDB call. What is missing is one `SERVER` span per request.

**The four questions the retired plan held, and their answers.** Its request
interceptor, its log annotation, the meaning of its placeholders, and how many
spans to add are all decided here: Phase 1b opens one `SERVER` span in a request
interceptor, annotates the request's log lines with the trace at the point the
context is established, keeps both placeholders with the changed meaning §6.6
states, and adds exactly that one span because the five existing ones already sit
at the right boundaries.

**Option A — fold it in.**

*Pros:* this plan's central claim becomes testable. Without a request span,
`wireEither` reads the invalid context on every request, so every wire failure
carries the placeholder and `§13`'s span property cannot be written. The
reasoning that produced these answers — why the context needs an effect to read,
why the environment cannot carry `Tracing` through `serverLogic`, why the
invalid context *is* the placeholder — is also in hand now, and a separate plan
would have to re-derive it from nothing.
*Cons:* the plan grows by a phase, and that phase includes moving fourteen test
suites onto the combined telemetry layer, which is unrelated to the wire boundary
except that a test of the span read is meaningless without it.

**Option B — keep the separate plan and leave this one reading placeholders.**

*Pros:* this plan's scope stays on the wire boundary.
*Cons:* it ships a correlation identifier that is structurally present and always
the placeholder — which is the exact defect `EWB-D-8` was raised to fix, in a new
form. `EWB-D-8`'s own recommendation names it: "A correlation identifier that is
structurally present and never populated is worse than none, because it reads as
working."

**Trade-off.** Plan size against shipping a feature in a state its own decision
record calls worse than absent. The hard-won reasoning is also a cost of
deferring that is easy to under-count: a later plan inherits the mechanical work
but not the derivation.

### EWB-D-20 — `RequestContext` is deleted, not populated — RULED 2026-10-13

**Ruled: the request interceptor applies a log annotation only.
`RequestContext`, `RequestContextRef`, its layer, its five helper methods and
`RequestContextSpec` are deleted, and `SpanId`, `RequestId`, their two Tapir
schemas and `TelemetryPlaceholders.UnlinkedSpan` go with them.** This supersedes
the retyping that `EWB-D-8` and §6.6 originally specified.

**Goal.** The interceptor of `EWB-D-19` opens a span and must make its trace
identifier reachable. Two mechanisms could carry it, and keeping both leaves one
of them with no reader. Deciding which the interceptor uses decides whether a
type survives.

**What the two mechanisms are.** A *fibre reference* is ZIO's state scoped to one
unit of work and inherited by anything it forks — its replacement for a
thread-local, and the reason a request's values survive the async boundaries a
work-stealing executor introduces. A *log annotation* is a particular fibre
reference that ZIO ships: `ZIO.logAnnotate(key, value)(effect)` sets
`FiberRef.currentLogAnnotations` for the duration of `effect`, and every logger
reads it without being asked.

**Both are idiomatic ZIO, so idiom does not decide this.** `RequestContext` is a
correct use of the pattern. The question is which has a reader.

**Option A — the log annotation only, and the record is deleted.**

*Pros:* one mechanism for one job, with a reader already wired up. It removes a
type that has **no production caller**: `RequestContext`'s only references
outside its own file are in its own test suite, and that suite tests
fibre-reference mechanics rather than any behaviour of this application. It also
stops a duplication that is worse than ordinary repetition — `TelemetryLive`
composes the tracing layer with `OpenTelemetry.contextZIO`, so the trace and the
span are *already* in a fibre reference, and populating the record would copy
them into a second one so that code could read the copy.
*Cons:* two of the three identifiers `EWB-D-8` typed lose their only reader and
are deleted, so part of that ruling's work is undone inside the same plan.
Register's own per-request identifier ceases to exist as a concept.

*Concretely:* a user quotes the trace from the banner. One log query returns
every line that request wrote, because the annotation was on all of them. No
`RequestContext` is consulted, because none exists.

**Option B — populate the record as well, passing the fibre reference in the way
`Tracing` is passed.**

*Pros:* all three typed identifiers keep a live reader, so `EWB-D-8` lands whole.
A later need for the request identifier — echoing it in a response header, say —
would have somewhere to read it from.
*Cons:* it populates a record nothing reads. That is the defect `EWB-D-8` exists
to remove, in a new shape, and that decision's own words rule on it: "A
correlation identifier that is structurally present and never populated is worse
than none, because it reads as working." It also cannot use the environment —
an interceptor's effect type is `Task`, so the reference would be a constructor
parameter, which is more wiring for the unread copy.

*Concretely:* the interceptor sets the record and annotates the log. The record
is correct throughout the request, and no line of application code consults it.

**Trade-off.** Whether `EWB-D-8` landing whole is worth keeping a populated
record with no reader. What settles it is that the record would hold a copy of
state ZIO already carries on the same fibre, so it is not merely unread but
redundant with a live source.

### EWB-D-21 — where the defect catch-all lives — RULED 2026-10-14

**Ruled: Option A, the catch-all in `RequestSpanInterceptor`, emitting the error
output the endpoints already declare.**

A ZIO defect escaping a controller is answered by `ZioHttpInterpreter` with
`Response.internalServerError(cause.squash.getMessage)`, so the exception's own
message becomes the body. Verified absent: there is no `exceptionHandler`, no
`decodeFailureHandler` and no `handleErrorCause` anywhere in the server module or
the integration harness, and `Application.startServer` uses
`customiseInterceptors` only to prepend two interceptors.

**Option A — the catch-all in `RequestSpanInterceptor`** (ruled). Tapir hands an
interceptor a `Responder`, which builds a response from a declared endpoint
output, so the interceptor answers with `BaseEndpoint.internalFaultOut` — the
same status and body the `oneOf` variant sends.

*Pros:* the interceptor is already outermost, so one place sees every request,
and it already holds the span whose identifier the body needs. Emitting a
declared output is what keeps the catch-all from being a second place that knows
`InternalFault`'s wire shape.
*Cons:* an interceptor named for tracing acquires a second responsibility, so its
name under-describes it.

*Concretely:* the database goes down mid-request. The caller receives `500` with
body `{"trace":"4bf92f…"}` and nothing else, and the operator finds `Connection
to postgres:5432 refused` in the server log under that same trace.

**Option B — a handler on the routes, via `Routes#handleErrorCause` after
`toHttp`.**

*Pros:* a single-purpose handler at the wiring site, obvious to anyone reading
`Application.startServer`, and it leaves the interceptor's name accurate.
*Cons:* it sits outside Tapir's model, so it has no endpoint context and no
access to a declared output. The body is hand-built, which is a second place
knowing `InternalFault`'s wire shape — the duplication ADR-038 removes elsewhere.

*Concretely:* the same `500` and the same body, assembled by hand, and a later
change to `InternalFault` updates the endpoint declaration and silently leaves
this one behind.

**Trade-off.** Whether a single-purpose location is worth a hand-built body.
What settles it is that the interceptor already holds the trace, and a `500`
without a trace is the one failure a user most needs to be able to quote. The
`internalFaultOut` value is what removes Option A's own version of the
duplication risk, so the ruling takes the location and keeps the declaration.

This also repairs `EWB-D-15`'s premise rather than reopening it. That ruling
argued a bug belongs in the defect channel, which assumed the defect channel is
opaque at the wire; this makes that true.

### EWB-D-22 — how the browser reports an answer it cannot decode — RULED 2026-10-14

**Ruled: Option A, a third `RequestFailure` case carrying the status.**

`BackendClient` maps every failure of `send` to `Transport`, whose sentence is
"The request did not reach the server. Nothing was changed." A decode rejection
also fails `send`, because the interpreter is built with
`toRequestThrowDecodeFailures`, so an answer the browser could not read is
reported as a request that never arrived.

**Option A — a third case, `Undecodable(status: StatusCode)`** (ruled).

*Pros:* the browser can say something true — the server answered with this
status and the reply could not be read. The two situations differ in exactly the
way a user acts on, which is whether their change may have landed.
*Cons:* it widens a type `EWB-D-12` ruled, so three match sites gain an arm and
that decision's text needs this amendment beside it.

*Concretely:* a workspace row is inserted and a follow-up write then fails. The
banner says the server answered and the reply was unreadable, instead of claiming
nothing was changed — which matters, because something was.

**Option B — reword `Transport` so the sentence is true of both.**

*Pros:* one sentence changes and no type changes, so no match site moves.
*Cons:* the wording has to drop "Nothing was changed" to stay true in the rare
case, and that is the reassurance the sentence exists to give in the common one.

*Concretely:* the banner reads "The request failed and the result is unknown.
Reload to see the current state." Correct in both cases, and needlessly alarming
in the one that happens most.

**Trade-off.** Whether one enum case and an amendment to `EWB-D-12` are worth
keeping a true reassurance in the common case. What settles it is that collapsing
the two costs the common case its accuracy to accommodate the rare one, which is
the wrong direction.

**The condition attached to the ruling is met.** It is one more case and nothing
else: `RequestFailure` is already a flat enum under `ViewFailure`; three sites
match on its cases — `GlobalError.from`, `ErrorMessages.viewFailed` and
`ZJS.recordToConsole` — and the compiler names all three, because an inexhaustive
match is a compile error in this build; and `ZJS.settleLoad` ends in a wildcard
that already covers it. No signature changes:
`BackendClient.endpointRequestZIO` keeps returning `IO[RequestFailure, O]`.

It is compliant with the plan and the records. The status is
`sttp.model.StatusCode`, a typed value the client library already carries, so
ADR-038 §3's rule against a raw primitive where a typed value exists is satisfied
rather than waived. The sentence is a named template beside the others, as
ADR-037 and `EWB-D-10` require. ADR-019's two-tier presentation is unchanged: the
banner reads the new case through `GlobalError`, and the inline view reads it
through `viewFailed`. `EWB-D-13`'s invariant — that `RequestFailure` names
exactly what a request can produce — holds, which is the test recorded there.

### EWB-D-23 — the decode-failure mapping — RULED 2026-10-14

**Ruled: Option A, a `DecodeFailureHandler` producing
`WireError.ValidationRejected`.**

Validation lives in the Tapir codecs (ADR-001), so a rejected body, header or
path segment is answered before a handler runs — by Tapir's default handler, as a
plain-text `400`.

**Option A — a `DecodeFailureHandler` that builds `ValidationRejected`** (ruled).
It is effectful in this Tapir version, so it reads `Tracing` and stamps a trace
like every other wire failure.

*Pros:* one error shape on the wire, and the browser renders the rejected input's
name with the renderer it already has for `ValidationRejected`.
*Cons:* someone must decide which decode failure becomes which
`ValidationErrorCode`, so the mapping is a small new specification.

*Concretely:* `PUT /w/{key}/risk-trees/{treeId}` sent without the `X-Branch`
header returns `{"errors":[{"field":"X-Branch","code":"REQUIRED_FIELD",…}],
"trace":"…"}`, and the form shows the field name.

**Option B — narrow `ValidationRejected`'s documentation** to service-layer
validation only, and accept the plain-text `400`.

*Pros:* no new code, and the documentation then describes what the type actually
carries instead of a case it never receives.
*Cons:* the API's commonest client error answers in a format no other error uses,
so the browser cannot decode it and falls into `EWB-D-22`'s path for an error the
service wrote deliberately.

*Concretely:* the same request returns `Invalid value for: header X-Branch
(missing)` as text, and the banner says the reply could not be read.

**Trade-off.** Whether a small mapping specification is worth one error shape on
the wire. What settles it is the user-visible consequence the ruling named: it is
the one case where a user would see an error shaped unlike every other. The
mapping is three cases drawn from the existing `ValidationErrorCode` set, so no
new closed set is introduced.

### EWB-D-24 — `MergeConflictEntry` and the duplicated trace reader join this plan — RULED 2026-10-14

**Ruled: both, in the phase each belongs to. Neither is a trade-off; both are
scope placements recorded so the plan ships whole (G8).**

`MergeConflictEntry(path: String, treeId: Option[String], nodeId: Option[String])`
holds two identifiers as raw strings where `TreeId` and `NodeId` exist, which is
ADR-038 §3's case, and `MergeRefusal` — a type in `domain/errors` — imports the
entry from `http/responses`, so a domain type depends on a response shape. Both
are answered in Phase 6 step 2, which already opens the merge-preview wire types:
the entry's identifiers are typed, and the entry is declared in `MergeRefusal`'s
own file under ADR-038 §2, which reverses the dependency.

`currentTraceId` in `WireEither.scala` and `currentTrace` in
`RequestSpanInterceptor.scala` are the same four lines, and §5.3 would add two
more copies. One `ActiveTrace.id` replaces all of them, in Phase 2b step 1,
because that step is where the third and fourth copies would otherwise be
written.

---

## 11. ADR alignment

| ADR | What this plan touches | Status |
|---|---|---|
| ADR-010 (error handling strategy) | the hierarchy, the HTTP mapping, the code smells | **Compliant, and it closes a violation.** The "String Matching on Errors" code smell instructs pattern matching on types; `isWorkspaceSentinel` matches a string prefix on the browser side, and this plan deletes it. **The ADR is amended in two places:** §5 "Pure HTTP Error Mapping" is rewritten to describe a one-way projection onto `WireError` instead of `encode` and `decode`, and §1's hierarchy listing gains `TreeLoadFailure` and the `SimError` to `DomainError` rename. |
| ADR-035 (error leakage prevention) | the boundary, the encoders, the failure's own message member | **Compliant, and strengthened.** **Its naming amendment is made.** ADR-035 named the member as `getMessage` in its leakage-vector list, in its §3 heading, in §3's first sentence and in its ADR-036 cross-reference. `EWB-D-15` removes `extends Throwable`, so `AppError` has no `getMessage` and the member is `logMessage` (§6.1). All four now read `logMessage`, and §3's opening states why: the name carries the destination, and an `AppError` is plain data with no exception apparatus behind its text. The discipline the section states is unchanged and still binds. §2 "Typed Error Channel" quotes the `mapErrorOut` line and argues that `ErrorResponse` is the only type that can reach the wire; the argument holds more strongly for `WireError`, which has no field for a confidential value rather than relying on a builder dropping it. **§2 is amended** to quote the `oneOf` declaration and to state that the single projection is the auditable list of what is dropped. §1's exhaustiveness guarantee improves: the match is on 31 leaf types, not on 4 sub-traits. **§3 is also amended, and the earlier claim that it was unchanged was wrong.** Its second bullet reads "Resource-neutral errors collapse to an opaque status (a missing resource is a plain 404 regardless of variant)" — that constrains the status, not only the message, and `EWB-D-4` answers `410 Gone` for expiry. The bullet is narrowed to not-found and not-yours, which is the pair A13 was narrowed to on the same grounds; the `getMessage` sentence it opens with is unchanged and still binds. **Phase 2b closes two paths that bypassed §2 entirely:** a defect reached the wire as `cause.squash.getMessage` in a plain-text body, and a request the codecs rejected reached it as Tapir's own plain-text `400`. After `EWB-D-21` and `EWB-D-23` every response this service sends is a declared output, which is what §2's exhaustive-encoding rule asks for. |
| ADR-036 (confidential internal identifiers) | `TreeNotInWorkspace`, `WorkspaceExpired`, the log line | Compliant. No wire case declares a `WorkspaceId`, so the output half holds structurally rather than by discipline. The input half is untouched. §6.2's log annotation is explicitly permitted: a confidential identifier may appear in a server log. |
| ADR-022 (secret handling) | the three workspace errors | Compliant and unchanged. None carries a credential, so R1's `final class` rule has nothing to be violated at the container. |
| ADR-001 and ADR-017 (API design) | `BaseEndpoint`, the DTO convention | Compliant, and it removes an exception. The error output now follows the response-DTO convention the architecture document already declares complete. |
| ADR-019 (frontend component architecture) | `GlobalError`, `ErrorBanner` | Compliant. Pattern 7's two-tier error presentation is unchanged; the classifier's input type changes and a `NotFound` display case is added. |
| ADR-028 (vague quantifier query pane) | `QueryProblem` | Compliant. The engine's rendered messages still reach the pane. `FolModelValidationFailure` and `FolEvaluationFailure` stop reaching it, which matches the ADR's own description of them as wiring faults rather than query errors. |
| ADR-002 (observability) | the correlation identifier, the resolve log line | **Currently violated and fixed here.** A correlation identifier that never reaches a client cannot correlate anything; `EWB-D-8` decides the fix. §6.2 fixes the resolve-failure line, which annotates a redacted constant today and so identifies no workspace. |

`docs/dev/ARCHITECTURE.md`'s "DTO/Domain Model Separation" section gains the error
path. The `adr-constraints` skill is updated in the same pass, in
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

`TraceId` and the `TelemetryPlaceholders` holder go into
`OpaqueTypes.scala` beside the existing nominal wrappers rather than into a file
of their own, because that is where every other one lives (`EWB-D-8`, narrowed
to one identifier by `EWB-D-20`). That file is listed under the shared module
above, so this phase creates no file for them.

Message templates and wire-string holders (`EWB-D-10`, §7.7):

- `new: modules/app/src/main/scala/app/views/ErrorMessages.scala`
- `new: modules/app/src/main/scala/app/components/MergeMessages.scala`

The inline display-state types (`EWB-D-13`, §7.8). One file, because the
supertype is sealed and its cases must be visible to the compiler together;
`RequestFailure` moves into it from `RequestFailure.scala`, which is then
deleted:

- `new: modules/app/src/main/scala/app/core/ViewFailure.scala`

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
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/sse/SSEController.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/http/controllers/ScenarioControllerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala`

The request span and the telemetry consolidation (`EWB-D-19`, Phase 1b). Four of
these are deleted rather than edited — `RequestContext.scala` and its spec by
`EWB-D-20`, `TracingLive.scala` and `MetricsLive.scala` once the suites move off
them — and the fourteen suites that compose them are reached by the standing
authorization for a module's own tests:

- `new: modules/server/src/main/scala/com/risquanter/register/http/RequestSpanInterceptor.scala`
- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/telemetry/TelemetryLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/telemetry/TracingLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/telemetry/MetricsLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/telemetry/RequestContext.scala`
- `modules/server/src/test/scala/com/risquanter/register/telemetry/RequestContextSpec.scala`

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
- `docs/dev/decision-records/ADR-037-in-code-constants.md` (written 2026-10-11; the phase amends the architecture document to match it)
- `.github/skills/code-quality-review/SKILL.md`
- `.claude/skills/code-quality-review/SKILL.md`
- `docs/dev/plans/PLAN-RISKTRANSFORM.md` (repoint its four references at lines 9677, 9844, 9942, 9955)
- `docs/dev/plans/IMPLEMENTATION-PLAN.md` (repoint its three `TES-D-9` references)

The two global instruction lines that `EWB-D-10` produces are written. The user's
`~/.claude/CLAUDE.md` carries the boyscout section that names them, and the global
`boyscout` skill states each one with its test and its reason, as obligations 3
and 5. Neither is this plan's work any more.

---

## 13. Verification plan

**Compile first, deliberately.** Phase 1 changes one declaration in
`BaseEndpoint`, which changes every endpoint's error output type. `sbt compile`
then names every site that still fails with `AppError`, and that is the complete
list of `.either` sites to convert. The compiler produces the migration list; it
is not assembled by hand.

The same mechanism produces the narrowing list, and it is the reason `EWB-D-15`
is in this plan rather than deferred. While `AppError extends Throwable`, a step
returning `Task[A]` absorbs an `AppError` silently and the widening leaves no
trace at the point it happens. Once the supertype is gone, each widening is a
type error naming its own site. Removing `extends Throwable` first and compiling
is therefore the step that enumerates `EWB-D-14`'s work.

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

**The span property, carried in from the retired telemetry plan.** One test, in
Phase 1b: *the identifier a `WireError` carries equals the identifier of the span
under which the failing work ran.* It is written by opening a span, failing an
effect inside it, passing the failure through `wireEither`, and asserting the
projected identifier against the span context's own. This is the property that
makes the identifier worth showing a user — without it the value is well-typed
and means nothing.

The retired plan also proposed asserting that *no site passes a placeholder
identifier*. That assertion is **not** carried in, because this design makes it
false on purpose: an effect outside any request reads the invalid span context
and so reads the placeholder, which is the correct answer for work that belongs
to no trace. The property that replaces it is narrower and true: **no site
inside a request yields a placeholder.** A request always has the Phase 1b
interceptor's span open around it, so a placeholder observed on a request path
is a defect in the interceptor's reach rather than an expected value.

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
