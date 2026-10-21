# PLAN-ERROR-WIRE-BOUNDARY

Plan code `EWB`. Decisions in this document are labelled `EWB-D-<n>`.

**Status.** Thirty-eight of the thirty-nine decisions, `EWB-D-1` to `EWB-D-38`,
are ruled and recorded in §10. `EWB-D-39` is open and blocks Phase 3a alone.
Approval is in place: the token names this document and the file inventory exists
beside it. Phases 1, 1a, 1b, 2, 2b, 2c and 3 have landed, and `EWB-D-27`'s
projection change landed with them. What remains is Phase 3a, then Phase 3b, then
Phases 4 to 7.

Phase 3a and `EWB-P-3` were added on 2026-10-21. Phase 3 typed the location a
refusal carries; Phase 3a makes that location arrive from one declaration per
side instead of from each call site, and makes one total function decide where a
refusal is displayed. §15 holds its design. It runs before Phase 3b because it
reduces the number of `ValidationError` construction sites Phase 3b then has to
convert.

`EWB-D-21`'s location was revised on 2026-10-15 and `EWB-D-25` was added the same
day. Phase 2b is the phase both bear on, and both are implemented: the defect
catch-all sits in its own appended interceptor, and every Iron codec reports a
refusal as an invalid value. The path that revision required is in the file
inventory and in §12.

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

## 2. The three principles this plan applies

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

### EWB-P-3 — a refusal's location is declared once and routed once

Phase 3 gave a refusal a typed location. It did not say where that location
comes from, so today it comes from the call site: every validation call takes
the value and the location as two independent arguments, and nothing relates
them. The seven statements below are what this plan wants instead. They are
stated as properties of the finished system, not as steps; §15 holds the
mechanism and Phase 3a holds the work.

**1. A location has two components, supplied by two different authorities.**
`ErrorLocation.Field(subject, field)` already says this: "A subject and a field
vary independently — the same field can fail under any subject — so they are two
components rather than one spelled-out path." Which node a refusal is about is
supplied by whichever side produced it, and the two sides legitimately differ —
the browser names the node it is editing, the server names the row it was
iterating. What within the node failed is one vocabulary, `FieldName`, shared by
both because the module is cross-compiled.

**2. A value and the location a refusal about it names travel together.** From
the point a value is read to the point it is refined, the two are one argument.
No refinement takes a value and a location as two parameters, so no caller can
pair the probability value with the name field.

**3. The field a value belongs to is named once per side, where the value is
read.** The browser's located accessor and the server's request projection are
the two places, and no refinement call site names a field at all. The naming
itself is still a free assertion — nothing relates a `FieldName` case to the
value beside it — so one behavioural test per field pins it, which is the
project's own doctrine that an unenforced rule is not a rule.

**4. A request carries values; a response carries locations.** The request body
does not carry a location, because the server derives a better one than the
browser can supply: the browser's leaf form does not know its own position in a
submitted batch. The response does carry one, because there the location is the
server telling the browser where to display a refusal, and `ValidationError`
already serialises it.

**5. One mapping per form decides where a refusal is displayed, and it is
explicit about every location.** The per-form display enum is the set of display
targets the form owns, and it gains a case for the form's own line so that a
refusal about a combination of inputs has a named target rather than falling
through. No code path displays a refusal without passing through the mapping.

**6. A refusal no view claims goes to the banner, and that is a decision rather
than an absence.** The banner is not a target any form owns, so it is not a case
of a form's enum; it is what remains when no view's mapping claims a location.
The conflation it replaces is today's, where an absent result means both "no
control for that location" and "nobody decided".

**7. Nothing is discarded, because nothing is routed.** Each control selects the
refusals that belong to it from the refusals in force, and the banner selects
what no control claimed. No refusal is pushed into a per-field store that could
overwrite another, and none is filtered away on the way. A refusal naming three
fields shows three errors.

The guarantee these deliver, stated exactly: changing where the probability
field's errors appear is a change to one arm of one function, and refusals from
the browser's own check and from the server's check both reach that arm, because
both stamp the same `FieldName` case. What is **not** delivered is a single
declaration across both sides — the browser's probability is text on a 0–100
scale in a `Var` and the server's is a `Double` on a 0–1 scale in a request
field, so no one declaration can produce both located values. The reachable
ceiling is one declaration per side, both referring to one shared rule, down
from six independent assertions today.

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
  /** A request refused before it could be understood as content: a header,
    * query parameter or body the decoders rejected, or a request-level rule
    * that failed. Carries one entry per independent failure, so a form shows
    * every problem at once. Names no tree, because the paths that produce it
    * include tree creation and the routes that name no tree at all
    * (`EWB-D-28`). */
  case RequestRejected(errors: List[ValidationError], trace: TraceId)

  /** A well-formed request against a known tree whose content failed its own
    * rules — either the submitted fields or the submitted query. `tree` is
    * always present, which is what separates this case from `RequestRejected`
    * (`EWB-D-28`). `RejectionDetail` is at §3.5. */
  case ValidationRejected(tree: TreeId, detail: RejectionDetail, trace: TraceId)

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

  /** Refused. Every denial produces this one shape, so a denial this service
    * decided and one the authorization service decided are indistinguishable,
    * and neither discloses whether the resource exists (ADR-035 §2). An
    * authorization service that did not answer is not a denial and does not
    * reach here; it is a dependency outage (`EWB-D-27`). */
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
  given requestRejectedCodec:       JsonCodec[RequestRejected]       = DeriveJsonCodec.gen[RequestRejected]
  given validationRejectedCodec:    JsonCodec[ValidationRejected]    = DeriveJsonCodec.gen[ValidationRejected]
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
  case ScenarioHeadStale(scenario: ScenarioName.ScenarioName)

  /** A query arrived before the tree had simulation results. */
  case SimulationRequired(treeId: TreeId)

object ConflictKind:
  given codec: JsonCodec[ConflictKind] = DeriveJsonCodec.gen[ConflictKind]
```

`ScenarioHeadStale` carries the scenario alone and drops both of the internal
error's commit hashes. Neither is something the person can act on, because the
remedy is to reload the scenario either way, and the head the branch currently
carries would additionally help someone correlate activity in a workspace they do
not hold. A developer who needs to see which commit was expected has the trace
and the server log, where the internal error keeps both hashes.

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

### 3.5 `RejectionDetail` — the payload of a content rejection

A tree's submitted fields and a submitted query fail in the same category: the
request was well formed and its content broke its own rules. Both therefore
answer `422 Unprocessable Content`. They carry different structure, so the
status is shared by one wire case whose payload says which it was.

```scala
@jsonDiscriminator("kind")
enum RejectionDetail:
  /** Field-level failures, one entry per independent problem. */
  case Fields(errors: List[ValidationError])

  /** A query the engine refused. `QueryProblem` is at §3.1 and keeps all four
    * of its cases, including `Unparseable`'s character offset, which a flat
    * `ValidationError` triple has nowhere to carry. */
  case Query(problem: QueryProblem)

object RejectionDetail:
  given codec: JsonCodec[RejectionDetail] = DeriveJsonCodec.gen[RejectionDetail]

  /** Declared here rather than left to automatic derivation at the use site:
    * `jsonBody` needs a schema as well as a codec, and putting it in the
    * companion is what makes one derivation serve every endpoint that carries
    * this payload (ADR-001 §2).
    */
  given schema: Schema[RejectionDetail] = Schema.derived
```

The file imports `com.risquanter.register.http.codecs.IronTapirCodecs.given`
alongside the two above. `ValidationError` names Iron types inside its location,
and a JSON body needs a schema for each of them as well as a codec, which is how
`BaseEndpoint` already obtains them.

This is the shape `Conflict` already has. `Conflict(conflict: ConflictKind, trace)`
is one case at one status whose payload is a discriminated enum, and
`ConflictKind.MergeRefused(scenario, refusal: MergeRefusal)` nests a second union
inside it. `ValidationRejected(tree, detail)` with `RejectionDetail.Query(problem)`
is the same construction at the same depth, so it introduces no new pattern.

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
      oneOfVariant(BaseEndpoint.requestRejectedOut),
      oneOfVariant(BaseEndpoint.validationRejectedOut),
      oneOfVariant(StatusCode.Forbidden,           jsonBody[WireError.AccessDenied]),
      oneOfVariant(StatusCode.NotFound,            jsonBody[WireError.NotFound]),
      oneOfVariant(StatusCode.Gone,                jsonBody[WireError.WorkspaceExpired]),
      oneOfVariant(StatusCode.Conflict,            jsonBody[WireError.Conflict]),
      oneOfVariant(StatusCode.NotImplemented,      jsonBody[WireError.NotSupported]),
      oneOfVariant(StatusCode.ServiceUnavailable,  jsonBody[WireError.DependencyUnavailable]),
      oneOfVariant(BaseEndpoint.internalFaultOut)
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

**That second direction is why the one-to-one property is required rather than
tidy, and it is what decided `EWB-D-28`.** The browser selects the variant from
the status alone, so two cases at one status would leave one of them
undecodable — the second would never be reached. A field-level rejection and a
refused query both belong at `422`, so they are one case carrying a payload that
says which, rather than two cases competing for the status.

**There is no default variant.** `oneOfDefaultVariant` must be typed as the
parent enum, because it is the arm that accepts a status the list does not name,
so its body would have to decode as `WireError` itself — and under the per-case
bodies above no such body exists. `InternalFault` therefore takes an ordinary
`oneOfVariant` at `500`, and a status outside the nine is a Tapir decode failure
rather than a silently mistyped error.

**Why one status per case is required, not merely neat.** The browser's selection
is by status alone. Two variants sharing a status would make the first one listed
always win on decode, silently. That is the reason the internal conflict errors
collapse into one `Conflict` case with `ConflictKind` inside it rather than
becoming seven variants at `409`, and the reason a rejected tree body and a
refused query — both `422`, because both are content the server understood and
could not process — collapse into one `ValidationRejected` with `RejectionDetail`
inside it rather than becoming two variants at that status (`EWB-D-28`). A
request refused before it could be read as content has its own case at `400`.
Where several outcomes share a
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

**Renaming the internal types instead was measured and declined.** The four lines
above are the only place the two names are ever in scope together, because the
projection is a single total function in one file and no other expression holds an
`AppError` and a `WireError` at once. Renaming them — `OperationForbidden` and
`WorkspacePastDeadline` were the candidates — would touch 33 sites, 20 for
`WorkspaceExpired` and 13 for `AccessDenied`, to delete a device the records
already sanction. It would also break a correspondence that is informative rather
than confusing: one concept keeps one name across all three layers, so
`AppError.WorkspaceExpired` projects to `WireError.WorkspaceExpired` and is
displayed as `GlobalError.WorkspaceExpired`.

This is why `GlobalError.RequestRejected` is renamed in §7.1 and these two are
not. There the two names are in scope together in the browser's own code, the
rename costs four sites, and the old name was the less descriptive of the two.

**The tree a failure is about is supplied by the caller, not carried by the
failure.** An `AppError` has no tree, and adding one would mean 38 construction
sites across 18 files — including `Simulator.scala` and `CachedResultResolverLive.scala`,
where no tree identifier is in scope. The controller calling the projection has
the tree destructured in its own handler, so it states the scope at that call
site (`EWB-D-28`):

```scala
/** Which tree a failure is being projected on. It never crosses the wire: the
  * two wire cases each have a total field, and this is only the projection's
  * input. Declared in `common` because `fromAppError` is.
  */
enum TreeScope:
  case InTree(tree: TreeId)
  case NoTree
```

```scala
def fromAppError(e: AppError, trace: TraceId, scope: TreeScope): WireError =
  // Bound once so each arm reads `t` rather than repeating the parameter. The
  // trace is not a field of the error: it is read from the active span at the
  // boundary that calls this, which is the only place an effect exists to
  // read it. `scope` arrives the same way and for the same reason.
  val t = trace

  // A content rejection names its tree; a request-level one has none to name.
  def rejected(detail: RejectionDetail, fallback: List[ValidationError]): WireError =
    scope match
      case TreeScope.InTree(tree) => ValidationRejected(tree, detail, t)
      case TreeScope.NoTree       => RequestRejected(fallback, t)

  // A query runs only on a route carrying `path[TreeId]("treeId")`, so `NoTree`
  // cannot occur here. It is this service's defect rather than the caller's, so
  // it answers the opaque fault and the boundary log line records it.
  def queryRejected(problem: QueryProblem): WireError =
    scope match
      case TreeScope.InTree(tree) => ValidationRejected(tree, RejectionDetail.Query(problem), t)
      case TreeScope.NoTree       => InternalFault(t)

  e match
    // Content of a known tree, or a request that named none
    case ValidationFailed(errors)             => rejected(RejectionDetail.Fields(errors), errors)

    // Query refused
    case FolParseFailure(message, position)   => queryRejected(QueryProblem.Unparseable(message, position))
    case FolUnknownReference(messages)        => queryRejected(QueryProblem.UnknownReferences(messages))
    case FolBindFailure(errors)               => queryRejected(QueryProblem.TypeCheckFailed(errors))
    case FolDomainNotQuantifiable(message)    => queryRejected(QueryProblem.NotQuantifiable(message))

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

    // Refused. The internal reason is dropped from both, so a denial this
    // service decided and one the authorization service decided look identical
    // (ADR-035 §2).
    case AppAccessDenied(_)                   => AccessDenied(t)
    case AuthForbidden(_, _, _, _)            => AccessDenied(t)

    // Conflicts
    case DataConflict(_)                      => Conflict(ConflictKind.Rejected(), t)
    case VersionConflict(_, _, _)             => Conflict(ConflictKind.StaleVersion(), t)
    case MergeConflict(scenario, refusal)     => Conflict(ConflictKind.MergeRefused(scenario, refusal), t)
    case MergeAlreadyRunning(scenario)        => Conflict(ConflictKind.MergeAlreadyRunning(scenario), t)
    case MergeTargetMoved(scenario)           => Conflict(ConflictKind.MergeTargetMoved(scenario), t)
    case ScenarioHeadStale(scenario, _, _)    => Conflict(ConflictKind.ScenarioHeadStale(scenario), t)
    case SimulationNotCached(treeId)          => Conflict(ConflictKind.SimulationRequired(treeId), t)

    // Capability absent
    case ScenariosNotSupported(_)             => NotSupported(Capability.Scenarios, t)

    // Dependency did not answer. An unreachable authorization service is one of
    // these rather than a denial: the request still fails closed, and the
    // caller is told a service did not answer instead of that their access was
    // refused. Which dependency it was stays internal (ADR-035 §2).
    case IrminUnavailable(_)                  => DependencyUnavailable(t)
    case NetworkTimeout(_, _)                 => DependencyUnavailable(t)
    case IrminHttpError(_, _)                 => DependencyUnavailable(t)
    case IrminGraphQLError(_, _)              => DependencyUnavailable(t)
    case AuthServiceUnavailable(_, _)         => DependencyUnavailable(t)

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
    * is the identifier of the span the failing work ran under. `scope` is the
    * caller's statement of which tree the work was about, which the projection
    * needs and the failure does not carry (§5.1).
    */
  def wireEither(scope: TreeScope)(using tracing: Tracing): ZIO[R, Nothing, Either[WireError, A]] =
    effect
      .map(Right(_))
      .catchAll(error =>
        ActiveTrace.id.flatMap(trace =>
          logProjectedFailure(error, trace).as(Left(WireError.fromAppError(error, trace, scope)))
        )
      )
```

The `logProjectedFailure` call is what `EWB-D-26` requires of this method, so it
is part of the signature rather than an addition a later phase makes. Phase 3
adds the `scope` parameter to a method that already writes the line; an
implementation that took this block as a whole-body replacement would delete it.

The success side is `map` and the failure side is `catchAll`, rather than
`.either` followed by a match on the `Either` it produced. The match's success arm
would rewrap a value that was never unwrapped for any purpose —
`case Right(value) => ZIO.succeed(Right(value))` — which is the shape
`.claude/rules/scala-style.md` names under "Compose, do not orchestrate". The
trace reader is `ActiveTrace.id`, the one definition `EWB-D-24` rules, and the
`tracing` context parameter in scope supplies it.

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

The two rejection outputs are declared the same way, each at its own status:

```scala
val requestRejectedOut: EndpointOutput[WireError.RequestRejected] =
  statusCode(StatusCode.BadRequest).and(jsonBody[WireError.RequestRejected])

val validationRejectedOut: EndpointOutput[WireError.ValidationRejected] =
  statusCode(StatusCode.UnprocessableEntity).and(jsonBody[WireError.ValidationRejected])
```

The decode-failure handler sends neither. It runs before any handler, so it never
has a tree and always produces `RequestRejected` — but a rejection carries
whichever status the rejected input decides, so it sends a third declaration whose
status is a value rather than fixed:

```scala
val rejectionOut: EndpointOutput[(StatusCode, List[Header], WireError.RequestRejected)] =
  statusCode.and(headers).and(jsonBody[WireError.RequestRejected])
```

The headers travel with it because a status can require one.

**This third declaration is a known defect and not a licence.** A status it emits
that the `oneOf` does not name is a response shape outside the declared output,
which is what ADR-035's code-smell section forbids and what its §2 means by "no
case is undeclared". `400` is declared; whatever else
`DefaultDecodeFailureHandler.respond` computes is not. Closing it needs the real
set of statuses that path can produce, which is Tapir's rather than ours and is
measured by driving each failure kind through the handler. Until that is done the
gap stands recorded here rather than resolved.

All three live in a companion `object BaseEndpoint`, because `BaseEndpoint` is a
trait and a sender that is not an endpoint has no instance of it. Declaring them
together is what stops one body being described twice. The other seven variants
stay inline, because each is sent from one place only.

**A defect.** A ZIO defect is a failure ZIO treats as a bug rather than an
expected outcome, so it travels in the effect's cause rather than its typed error
channel. Nothing in this application converts one into a response, so
`ZioHttpInterpreter` folds the cause into
`Response.internalServerError(cause.squash.getMessage)` — a plain-text body
carrying the exception's own message. That is the disclosure ADR-035 forbids. It
is also why `EWB-D-15`'s reasoning needs this step: that ruling argued "a bug goes
to the defect channel, which is where `Throwable` belongs", which assumes the
defect channel is opaque at the wire, and it is not yet.

An interceptor of its own closes it, and **where that interceptor sits in the
stack decides whether the response it writes is usable.** Tapir states the
ordering rule in `CustomiseInterceptors`' own scaladoc: "The first interceptor in
the interceptor stack is the one which is called first on request, and processes
the resulting response as the last one." `prependInterceptor` puts an interceptor
at the front of that stack and `appendInterceptor` at the end, and the parameter
documentation for the end names this exact use — interceptors "called last on
request / first on response, e.g. handling decode failures, or providing
alternate responses".

So the catch-all is **appended**, which makes it the innermost request
interceptor: inside the CORS interceptor, inside the security headers, and
inside the span the request opened. The first of those three is the one that
would otherwise produce a broken response.

`CORSInterceptor` adds its headers to the response that comes back out of
whatever is below it:

```scala
next(request, endpoints).map {
  case Response(serverResponse, source) => Response(serverResponse.addHeaders(responseHeaders), source)
  case failure                          => failure
}
```

A response produced *above* that interceptor never passes through the
projection, so a catch-all placed outside CORS answers a browser with no
`Access-Control-Allow-Origin` header. The browser then reports a cross-origin
failure and never reads the body at all, which means the trace the `500` exists
to deliver is unreachable by the one client that needs it. The same argument
places the catch-all inside `SecurityHeadersInterceptor`, which decorates a
response the same way.

A Tapir `RequestInterceptor` is handed a `Responder`, which builds a response
from a declared endpoint output, so the catch-all emits `internalFaultOut`
rather than a hand-built body. New file
`modules/server/src/main/scala/com/risquanter/register/http/DefectCatchAllInterceptor.scala`:

```scala
/** Answers a failure that reached no endpoint's declared output, with the `500`
  * output the endpoints themselves declare.
  *
  * Appended rather than prepended, so it is the innermost request interceptor
  * and the CORS and security-header interceptors decorate its response on the
  * way out. A response built outside those two reaches a browser without
  * `Access-Control-Allow-Origin`, and the browser then rejects it before the
  * body — carrying the trace — can be read.
  *
  * Nothing about the failure crosses: the cause is logged under the same trace
  * the response carries, and the body holds the identifier alone (ADR-035). The
  * response is built from `BaseEndpoint.internalFaultOut`, so a failure answered
  * here and one projected by `wireEither` are the same shape to a client.
  */
object DefectCatchAllInterceptor:
  def interceptor(tracing: Tracing): RequestInterceptor[Task]

  private def answerDefect[B](
    responder: Responder[Task, B],
    request:   ServerRequest,
    trace:     TraceId,
    cause:     Cause[Throwable]
  ): Task[RequestResult[B]]
```

Its request handler catches the cause, and leaves an interruption alone:

```scala
ActiveTrace.id(using tracing).flatMap { trace =>
  next(request, endpoints).catchAllCause { cause =>
    if cause.isInterruptedOnly then ZIO.refailCause(cause)
    else answerDefect(responder, request, trace, cause)
  }
}
```

`catchAllCause` rather than `foldCauseZIO`, because the success arm would be
`success => ZIO.succeed(success)` — the identity, written out. A fold whose
success arm returns its argument unchanged is a catch.

**The interruption guard is part of the specification, not an implementation
detail.** A client that disconnects mid-request interrupts the fibre, and an
interruption reaches `catchAllCause` as a cause exactly as a defect does.
Answering it would build a `500` for a request that no longer has a caller, and
would record a normal cancellation as a server fault in the log.
`Cause.isInterruptedOnly` separates the two, and `ZIO.refailCause` lets the
interruption continue unchanged.

`RequestSpanInterceptor` keeps its single `ZIO.logAnnotate(...)(downstream)` and
acquires no second responsibility. Because it stays outside the catch-all, the
annotation it establishes is still in force when the catch-all logs the cause,
so an operator finds that cause under the same trace the user is quoting.

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
/** Answers a request the decoders rejected with `WireError.RequestRejected`, so
  * every failure this service sends has one declared shape. The handler runs
  * before any handler does, so it never has a tree to name and always answers
  * the request-level case (§5.1).
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

**The classification above is only true once the codecs report a refusal as a
refusal**, which is `EWB-D-25`. Tapir gives a codec two separate ways to fail:
`DecodeResult.Error(original, throwable)` means the raw bytes could not be read
at all, and `DecodeResult.InvalidValue(errors)` means a value was read and then
rejected by a constraint. All twelve Iron codecs in `IronTapirCodecs.scala` use
the first for the second situation — each one parses successfully with
`Codec.int`, `Codec.long` or `Codec.string` and then reports the refinement's
refusal as `DecodeResult.Error`. The parse step already handles genuinely
unreadable input itself, so every one of the twelve is a read-then-refuse
reported as could-not-be-read, and `toValidationError` answers `TYPE_MISMATCH`
where `INVALID_FORMAT` is the correct code.

Three definitions in `IronTapirCodecs.scala` fix all twelve at once:

```scala
/** Occupies the rejected-value slot of every refusal this file reports.
  *
  * Tapir's own renderer prints that slot — `invalidValueMessage` ends
  * "but got: ${ve.invalidValue}" — so a constant there is what keeps
  * client-submitted text out of a message no matter which renderer runs
  * (ADR-035).
  */
private val ClientInputRedaction: String = "[withheld]"

/** Occupies the validator slot, which Tapir requires and this file has nothing
  * to put in: an Iron refinement is not a `Validator`, and the refusal's text
  * travels in `customMessage` instead. Declared once and shared by all twelve
  * codecs, including those whose raw type is numeric, because the slot's type is
  * fixed by the validator rather than by the decoded value.
  */
private val NoTapirValidator: Validator.Custom[String] =
  Validator.Custom(_ => ValidationResult.Invalid())

/** One `DecodeResult.InvalidValue` for a value the refinement read and refused,
  * carrying each refusal's own sentence and none of the submitted value.
  */
private def refused(errs: List[ValidationError]): DecodeResult.InvalidValue =
  DecodeResult.InvalidValue(
    errs.map(e => TapirValidationError(NoTapirValidator, ClientInputRedaction, Nil, Some(e.message)))
  )
```

`ValidationError` here is this project's own type, and Tapir's identically-named
one is imported as `TapirValidationError`. That rename is required rather than
stylistic: the file opens with `import sttp.tapir.*`, so a bare `ValidationError`
in it resolves to Tapir's, while in the other 78 files that name it the bare
spelling means ours. An explicit import of our type wins over the wildcard and is
what keeps one name meaning one thing across the module.

Each of the twelve codecs then reads the same way — `ScenarioName` as the
reference:

```scala
given Codec[String, ScenarioName.ScenarioName, CodecFormat.TextPlain] =
  Codec.string.mapDecode[ScenarioName.ScenarioName](raw =>
    ScenarioName.fromString(raw).fold(refused, DecodeResult.Value(_))
  )(_.value)
```

The refusal's sentence now has somewhere to go, so the rendering function in
`WireDecodeFailureHandler` reads it instead of rendering the validator:

```scala
private def constraintBroken(errors: List[TapirValidationError[?]]): String =
  errors.flatMap(e => e.customMessage.orElse(e.validator.show)) match
    case Nil      => "is not valid"
    case messages => messages.mkString("; ")
```

`customMessage` is what `refused` populates, so for all twelve codecs the first
branch is the one that runs. The `validator.show` fallback is for a
`ValidationError` some other validator produced — a schema-level `MaxLength`
renders as `length<=30` — so a refusal from outside this file still renders
something true rather than nothing.

One property of that fallback is worth knowing rather than discovering later.
`Validator.show` renders a `Custom` validator as `showMessage.orElse(Some("custom"))`,
and `NoTapirValidator` carries no `showMessage`, so the fallback would render the
bare word `custom` for a refusal this file produced. No such refusal exists,
because `refused` is the only constructor and it always supplies
`customMessage` — the fallback is unreachable from here by construction, not by
discipline. It is recorded because a future change that constructs a
`TapirValidationError` without a message would meet it.

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

### 6.2 The resolve-failure log line names the workspace

`WorkspaceStoreLive.logResolveFailure` received the capability key and annotated
`"workspace_key" -> key.toString`, which renders as the same redacted constant
for every workspace, so the line identified nothing. The annotations become
`"workspace_id"`, `"reason"`, `"expiry_cause"` and `"expired_at"`, which is where
the question "why did this resolve fail" is answered. Naming the workspace is
permitted because a confidential internal identifier may appear in a server log
(ADR-036).

Every one of those values is on the failure itself. `WorkspaceExpired` carries
`wsId`, `cause` and `expiredAt`, because §6.1 has the store compute the deadline
where the record is in scope and put the result on the error. So the function
takes the error and needs no record:

```scala
private def logResolveFailure(error: AppError): UIO[Unit]
```

The workspace's creation instant is deliberately absent. It is an input to the
calculation §6.1 moved into the store, and `expiredAt` is that calculation's
answer, so a line carrying both would carry an ingredient beside its result. A
reader who needs the age has the `workspace.created` event, which names the same
`workspace_id`.

The annotation is `"expired_at"` and not `"expires_at"`, because the value is
whichever deadline ended this workspace, while `WorkspaceRecord.expiresAt` is the
absolute deadline alone — two different values whenever inactivity was the cause.
`"expiry_cause"` says which of the two deadlines it was, without which the
instant does not identify the deadline.

The not-found arm annotates `"reason"` alone. No workspace was found, so there is
no identifier to name.

**Superseded by `EWB-D-26`.** The store no longer logs a resolve failure at all.
Every caller reaches it through a request, so the projection onto the wire writes
the line instead, and it can attach the trace the store could not. The
annotations above moved onto the failures themselves, as `logAnnotations`.

### 6.7 One log line per failure that becomes a response

`wireEither` writes it, because that is the single point every controller failure
passes through and the point where the trace is in scope. The level is the
failure's own `origin`, which `EWB-D-26` states in full along with why the origin
is a member of `AppError` rather than a match on the wire case.

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

This is the largest mechanical item in the plan and the reason it is its own
phase rather than part of the boundary work.

**`ErrorLocation`**, new file
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`.

`EWB-D-30` ruled the shape: a location is a **subject** and a **field**, two
components that vary independently, rather than one spelled-out path. The same
field can fail under any subject, so a five-case enumeration that paired each
subject with its own field set repeated the field vocabulary once per subject.
Three cases carry it instead.

```scala
/** Where an error is. A closed set, because every location this system points
  * at already has a type — and because none of its cases can hold a value that
  * must not cross the client boundary (ADR-036): there is no case for a
  * `BranchRef` and none for an absolute storage path.
  */
@jsonDiscriminator("kind")
enum ErrorLocation:
  /** A field of some subject. */
  case Field(subject: ErrorSubject, field: FieldName)

  /** An HTTP request header. A closed set rather than a name, so no spelling
    * a client never supplied can reach the wire as free text. */
  case Header(header: RequestHeaderName)

  /** The failure is about the whole of whatever was being validated, with no
    * narrower location. A request body with no field to blame, or a tree built
    * from a store read or a merge, where no field of a request exists at all. */
  case Whole

object ErrorLocation:
  given codec: JsonCodec[ErrorLocation] = DeriveJsonCodec.gen[ErrorLocation]

  /** A field of the request body, which is the common case. */
  def requestField(field: FieldName): ErrorLocation = Field(ErrorSubject.Request, field)

  /** A field of a node of the tree. */
  def nodeField(nodeId: NodeId, field: FieldName): ErrorLocation =
    Field(ErrorSubject.ExistingNode(nodeId), field)
```

The `kind` discriminator is this plan's own rule for a payload carried inside a
body, stated in §3: the inner payload enumerations "are encoded as part of a
body, so each keeps its `kind` discriminator."

**`RequestHeaderName`**, declared in the same file, because `ErrorLocation` is
the type that owns the set (ADR-037 §1):

```scala
/** The request headers this application declares. Each spelling is declared
  * rather than derived from the case name, because the string is a contract
  * with HTTP rather than with the encoder, so a Scala rename must not reach it
  * (ADR-037 §2).
  */
@jsonDiscriminator("kind")
enum RequestHeaderName(val wireName: String):
  case UserId  extends RequestHeaderName("x-user-id")
  case Branch  extends RequestHeaderName("X-Branch")
  case IfMatch extends RequestHeaderName("If-Match")

object RequestHeaderName:
  given codec: JsonCodec[RequestHeaderName] = DeriveJsonCodec.gen[RequestHeaderName]
```

The set is closed by this application's own endpoint definitions, which declare
exactly three request headers: `x-user-id` on `authedBaseEndpoint` and on
`bootstrapWorkspaceEndpoint`, `X-Branch` on `BaseEndpoint.activeBranchHeader`,
and `If-Match` on the scenario delete endpoint. A client cannot widen it,
because Tapir reports a decode failure against a declared input and the name
therefore comes from the endpoint definition rather than from the request.

An enum is what closes this slot, not a refinement. A character-class
refinement would bound the alphabet of a name still written as free text; the
enum removes the name from the wire and leaves the serialised form derived from
a case this repository declares. With it, no `ErrorLocation` case carries free
text at all.

**The locations are enums, not a refined string.** An earlier draft of this
section bounded the location with an Iron refinement over a `String`. That was
wrong, and the reason it was wrong is visible in the consumers: the browser
routes an error to its form control by matching substrings of the path, and
falls through to a generic banner when none matches.

```scala
  private def fieldForShapeError(field: String): Option[RiskLeafField] =
    if field.contains("percentiles") then Some(RiskLeafField.Percentiles)
    else if field.contains("quantiles") then Some(RiskLeafField.Quantiles)
    else if field.contains("minLoss") then Some(RiskLeafField.MinLoss)
    else if field.contains("maxLoss") then Some(RiskLeafField.MaxLoss)
    else if field.contains("terms") then Some(RiskLeafField.Terms)
    else None
```

`RiskLeafField` and `PortfolioField` are already enums, so the location was the
one untyped link in a chain typed at both ends. A refinement bounds the
alphabet and leaves that function exactly as it is. An enum deletes it: each
form matches the locations it renders and sends the rest to its submit banner,
and the substring tests go. The set is fixed and known, so it is an enum in its
owner's companion (ADR-037 §1).

A typed location does **not** make a new case a compile error at the form. Both
routers end in a wildcard that answers the banner, which is what lets a form
render only the controls it has. A case added here reaches the banner until a
form is given an arm for it.

Both enums are declared in `ErrorLocation.scala`, because `ErrorLocation` is
the type that owns the sets.

```scala
/** Which submitted collection a node came from. A tree update submits four
  * collections, each indexed from zero independently, so a position alone
  * names four different rows. */
@jsonDiscriminator("kind")
enum SubmittedCollection:
  case Portfolios, Leaves, NewPortfolios, NewLeaves

/** What a failure is about. None of these cases can hold a value that must not
  * cross the client boundary (ADR-036). */
@jsonDiscriminator("kind")
enum ErrorSubject:
  case Request
  case DraftNode
  case NodeAtIndex(collection: SubmittedCollection, index: NonNegativeInt)
  case ExistingNode(nodeId: NodeId)
  case Mitigation(mitigationId: MitigationId)
  case Workspace

/** Which field or computed aspect failed. One vocabulary for every subject,
  * because a field that can fail on a submitted request can equally fail on a
  * stored node. `PercentileAt` and `QuantileAt` carry the element index as a
  * number rather than as `[3]` inside text; their index-less siblings are the
  * whole-collection failures, since a monotonicity violation names the
  * collection and not an element. */
@jsonDiscriminator("kind")
enum FieldName:
  case Id, Name, ParentId, ParentName, ChildIds, RootId, Nodes, Trees
  case SeedVarHighWater, Limit
  case Probability, SeedVarId, SeedEntityId
  case DistributionType, Terms, MinLoss, MaxLoss, Spec
  case Percentiles, Quantiles
  case PercentileAt(index: NonNegativeInt)
  case QuantileAt(index: NonNegativeInt)
  case ChildAt(index: NonNegativeInt)
  case Mitigations, Cap, Factor, OverrideParams, TargetingSource
  case Deductible, Threshold
  case Branch, Scenario, TreeId, At, Source, ToCommit, CommitHash, ContentHash
  case WorkspaceKey, WorkspaceKeyHash, UserId, Email, Url, Token, TraceId
  case AggregatedLoss, MitigatedResult
  case Unknown
```

`NonNegativeInt` is `Int :| GreaterEqual[0]` and already exists in
`OpaqueTypes.scala`. No upper bound is stated, because the index names an
element of a collection the request already bounds.

`ErrorSubject.nodeAtIndex`, `FieldName.percentileAt`, `FieldName.quantileAt`
and `FieldName.childAt` take an `Int` and fold a negative one onto the
collection-level case, because an index is produced by iterating a collection
and a negative one would mean the caller passed something that is not a
position.

`ExistingNode` covers a node the tree holds and a node a lookup asked for and
did not find. It carries no `TreeId`: the tree is named once on
`WireError.ValidationRejected`, and on the creation path no tree identifier
exists yet — the request body carries none and `ResolvedCreate` produces none,
so one here would be unsatisfiable (`EWB-D-28`).

**Where the node identifier goes.** Thirteen sites spell a node identifier into
the path today. The identifier moves into `ErrorSubject.ExistingNode` and what
remains is the field, so no location carries an identifier as text.

| Today | `subject` | `field` |
|---|---|---|
| `nodes[<id>].parentId` | `ExistingNode` | `ParentId` |
| `nodes[<id>].childIds` | `ExistingNode` | `ChildIds` |
| `riskLeaf.<id>.distributionType` | `ExistingNode` | `DistributionType` |
| `riskPortfolio.<id>.childIds` | `ExistingNode` | `ChildIds` |
| `riskPortfolio.<id>` | `ExistingNode` | `AggregatedLoss` |
| `riskLeaf.<id>` | `ExistingNode` | `ContentHash` |
| `mitigatedResult.<id>` | `ExistingNode` | `MitigatedResult` |

`riskLeaf` and `riskPortfolio` leave the location because the node's own type
says which it is. `mitigatedResult` moves from prefix to field, because it
names what failed rather than what it failed on. `AggregatedLoss` and
`ContentHash` are named here for the first time: each is taken from the message
beside it, which already says "aggregated loss overflow" and "no content hash".

The four fit-and-params cases an earlier draft listed — `MetalogFit`,
`LognormalFit`, `ExpertParams`, `LognormalParams` — are not in `FieldName`. A
fit failure reports at the collection the caller submitted, which is
`Percentiles` or `Quantiles`, and a parameter failure reports at `MinLoss`,
`MaxLoss` or `Terms`. Naming the fitter named the implementation rather than
the field a client could correct.

Because the field vocabulary is shared, one spelled path under two contexts
needs no second enumeration: `leaf.percentiles[3]` and `request.percentiles[3]`
become `Field(DraftNode, PercentileAt(3))` and
`Field(Request, PercentileAt(3))`.

**A missing case is corrected without asking.** This enumeration is derived
from the locations the code names today, and a first pass over thirty-odd cases
can miss one — a path assembled in a branch no sweep read, or a case only a
test reaches. Adding the case, and the arm it needs at every match, is
mechanical work and is done as part of the phase. It is not a deviation to
present, and it is not a decision. What stays a decision is a location that
needs a shape these four enums cannot express.

**No storage-path refinement.** A second refinement for workspace-relative
storage paths is not in this phase. Its only consumer would have been an
`ErrorLocation` case for a storage path, and no such case survives; the type
that holds such a path as a raw `String` is `MergeConflictPath`, which Phase 6
step 2 already opens (`EWB-D-24`).

**The decode-failure handler's mapping.** `WireDecodeFailureHandler.fieldName`
is replaced by `locationOf`, which returns an `ErrorLocation`:

```scala
  private def locationOf(input: EndpointInput[?]): ErrorLocation =
    headerNameOf(input).flatMap(RequestHeaderName.fromWireName) match
      case Some(header) => ErrorLocation.Header(header)
      case None         => ErrorLocation.Whole

  private def headerNameOf(input: EndpointInput[?]): Option[String] =
    input match
      case EndpointIO.Header(name, _, _)        => Some(name)
      case EndpointIO.FixedHeader(header, _, _) => Some(header.name)
      case _                                    => None
```

`RequestHeaderName.fromWireName` is what closes the set, matching without
regard to case because HTTP header names are case-insensitive while these
spellings are fixed text. A header this application declares reaches `Header`.
Everything else reaches `Whole`: a header Tapir pins itself (`Content-Type`
being the realistic one), a query parameter, a path segment, the body.

**The name is said once.** A declared header is named by its location, so the
message carries the reason alone. Every other input reports at `Whole`, where
the sentence is the only place the name can travel, so the message there opens
with `inputName(input)` — the header's own name if it had one, otherwise
Tapir's rendering of the input. Putting the name in both places would print
`X-Branch` twice in one refusal.

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
  case WireError.RequestRejected(errors, _)          => ValidationFailed(errors)
  case WireError.ValidationRejected(_, detail, _)    => detail match
    case RejectionDetail.Fields(errors)              => ValidationFailed(errors)
    case RejectionDetail.Query(problem)              => InputRejected(ErrorMessages.query(problem))
  case WireError.NotFound(_)                         => NotFound
  case WireError.WorkspaceExpired(c, life, idle, _)  => WorkspaceExpired(ErrorMessages.workspaceExpired(c, life, idle))
  case WireError.AccessDenied(t)                     => ServerError(ErrorMessages.Forbidden, Some(t))
  case WireError.Conflict(conflict, _)               => Conflict(ErrorMessages.conflict(conflict))
  case WireError.NotSupported(capability, _)         => ServerError(ErrorMessages.capabilityUnavailable(capability), None)
  case WireError.DependencyUnavailable(t)            => DependencyError(ErrorMessages.DependencyDown, Some(t))
  case WireError.InternalFault(t)                    => ServerError(ErrorMessages.InternalFault, Some(t))
```

**`GlobalError.RequestRejected` is renamed to `InputRejected`.** The display case
already exists at `GlobalError.scala:38` and is the arm a refused query takes; it
is renamed because `WireError.RequestRejected` now occupies the same name for a
different thing — the browser case carries one sentence for the user to act on,
the wire case carries a list of errors at `400`.

The wire name is the one that stays, because a wire case name is serialised and
appears in the generated contract, while `GlobalError` is the browser's own
presentation classification and nothing about it crosses. `InputRejected` also
names what the case is for better than the old name did: its siblings divide by
whose fault the display should imply, and this is the one where the problem is in
text the user typed, so it gets the warning treatment and carries no trace while
`ServerError` and `DependencyError` carry one and the fault icon.

Four sites, named by what each one is rather than by line, because the projection
arm moves whenever a case above it is edited: the case declaration in
`GlobalError.scala`, the arm in `GlobalError.fromWireError` that a refused query
takes, the render arm in `ErrorBanner.scala`, and the assertion in
`GlobalErrorSpec.scala`.

The existing code maps every query failure to `ServerError`, and that is wrong
rather than merely blunt: `ErrorBanner` renders `ServerError` with the
server-fault icon, so someone with a syntax error in a query would read a
server-fault presentation for their own typo.

That arm is reached more often than its inline handling suggests.
`toOutcomeEventStream` forks through `forkProvided` with the observer enabled,
so a query failure notifies the global observer **as well as** reaching
`AnalyzeQueryState`'s own slot. The banner shows it either way.

One arm passes `None` for the trace explicitly, because `EWB-D-11` forbids a
defaulted `Option`. That is the rule working rather than a cost it imposes. An
absent capability carries a trace on the wire, so `None` at the call site is
where the browser's decision not to show it becomes visible instead of being
hidden in a default.

Passing `None` withholds the identifier from the screen and not from the browser.
`ZJS.recordToConsole` writes the case and the trace of every failed request to
the browser console, applied by `tapError` on every forked effect, so a developer
reading a report about any failure has the identifier whether or not the banner
showed it. That is what makes this a presentation choice with no diagnostic cost.

**The browser displays it, and which cases show it is a presentation choice
rather than a data one.** The identifier is on every wire case, so every
`GlobalError` case can carry it. The rule deciding which show it is whether the
user can resolve the failure themselves.

A failure they cannot resolve carries the trace, because a reference is then the
only useful thing on screen: a dependency outage, an internal fault, and a
denial. A denial is in that set even though its message is actionable, because
the action is to ask someone else to grant access, and the person they ask needs
an identifier to look the request up. A user who believes a refusal is a mistake
otherwise has nothing to quote.

A failure where nothing about the request will ever succeed does not carry it. An
absent capability is the case: the deployment does not offer the feature, so no
trace lookup changes the answer and the identifier is noise beside a sentence
that already says so.

This rule replaces an earlier one that gave the trace to exactly two cases, a
dependency outage and an internal fault, on the ground that every other case
carries an actionable message. That reasoning treated "actionable" as meaning the
user acts alone, which a denial breaks. Ruled 2026-10-16.

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
`trace: Option[TraceId]`, and they are the only two display cases that do,
because they are the two that render it. The field is an `Option` because those
two cases are reached from wire cases on both sides of the rule above — a denial
and an internal fault both become `ServerError`, and only one of them supplies a
trace. `ErrorBanner` renders it in a muted line under the message, selectable so
it can be copied:

```scala
case GlobalError.ServerError(msg, trace) =>
  div(
    span(cls := "error-banner-message", msg),
    trace.map(t => div(cls := "error-banner-trace", t.value))
  )
```

`fromThrowable` is deleted and its one line inlined into `from`'s transport arm,
which reads `case RequestFailure.Transport(_) => NetworkError(ErrorMessages.TransportFailed)`.
A browser `fetch` failure is a `TypeError` rather than a `WireError`, so it still
becomes `NetworkError`; what goes is the indirection, not the behaviour.

Three things made the separate function worth removing. Its parameter was never
read, so the signature claimed the cause informed the classification when it did
not. It was public with one caller and no test. And the cause has a real consumer
already — `ZJS.recordToConsole` writes it to the browser console — so the
non-use here is a presentation decision, and `_` states it at the point a reader
asks rather than two screens away behind a function name. The inlined arm also
matches `ErrorMessages.viewFailed`, which already writes
`case RequestFailure.Transport(_) => TransportFailed` for the inline-display path.

The cause is not recoverable detail being thrown away. A failed cross-origin
check and a genuine network failure both surface as a `TypeError` with no
distinguishing field, because the Fetch specification returns an opaque network
error precisely so script cannot tell them apart, so there is no branch to write.

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
| `ScenarioHeadStale(scenario)` | the scenario changed since it was read; reload is the action |
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

**The decode failure stops being an exception, which is what makes the status
reachable.** `ResponseException`, the exception sttp throws, carries no response,
so there is nothing to read a status from once the rejection has been thrown.
`BackendClient` therefore builds its requests with `interpreter.toRequest`
instead, whose decoded body is a `DecodeResult`:

```scala
private def endpointRequest[I, E, O](
    endpoint: Endpoint[Unit, I, E, O, Any]
): I => Request[DecodeResult[Either[E, O]], Any] =
  val toRequest = interpreter.toRequest(endpoint, baseUrl)
  input => toRequest(input).header("Accept", "application/json")
```

The three outcomes are then one match over a response that exists, with the
status in scope:

```scala
backend
  .send(endpointRequest(endpoint)(payload))
  .mapError(RequestFailure.Transport(_))
  .flatMap { response =>
    response.body match
      case DecodeResult.Value(Right(output)) => ZIO.succeed(output)
      case DecodeResult.Value(Left(error))   => ZIO.fail(RequestFailure.Wire(error))
      case _: DecodeResult.Failure           => ZIO.fail(RequestFailure.Undecodable(response.code))
  }
```

`Transport` now means only what its sentence says: `send` itself did not
complete, so no response exists at all. A failure to decode is no longer an
exception being caught, which is the second thing this change buys.

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
| `FormSubmitUtil.scala` whole, with `routeErrors` (Phase 3a) | it pushed each refusal into a `Var` it did not own; each control now selects its own (§15.5a) |
| `submitFieldErrors`, `setSubmitFieldError`, `clearSubmitFieldError`, `withSubmitErrors` (Phase 3a) | the per-field store the push wrote into, which held one message per key |

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

*Then the log line*, from §6.2:

```scala
private def logResolveFailure(error: AppError): UIO[Unit]
```

taking the failure rather than the key, so the expiry arm annotates
`workspace_id`, `expiry_cause` and `expired_at` from the error that already
carries them. `TreeNotInWorkspace` needs nothing in this step: it holds its own
identifier and keeps it out of `logMessage` already, which landed with the change
this plan's starting point assumes.

**Phase 2b — the failures that bypass the boundary.** §5.3's two server-side
closures, and the client case that reports whatever survives them. Three steps,
in this order, because each one narrows what the next has to describe.

1. *The defect catch-all*, from `EWB-D-21`. `BaseEndpoint.internalFaultOut`
   declared once with the `oneOf` variant built from it; `ActiveTrace` replacing
   the two duplicated trace readers at their call sites (`EWB-D-24`);
   `DefectCatchAllInterceptor` holding `answerDefect`, the `catchAllCause` and the
   interruption guard, connected with `appendInterceptor` so it is the innermost
   request interceptor. `RequestSpanInterceptor` keeps only the span and the log
   annotation, and its scaladoc loses the claim that it is the outermost
   interceptor. A new `DefectCatchAllInterceptorSpec` pins three things: an
   endpoint whose logic dies answers `500` with a body carrying the trace and
   nothing else; that response carries the CORS header, which is the property the
   placement exists to secure and the one a prepended interceptor fails; and an
   interrupted request is not answered at all.
2. *The decode-failure handler*, from `EWB-D-23`, and the codec classification it
   depends on, from `EWB-D-25`. `WireDecodeFailureHandler` and its connection in
   `Application.startServer`; `refused`, `ClientInputRedaction` and
   `NoTapirValidator` in `IronTapirCodecs.scala` with all twelve codecs reporting
   through `refused`; `constraintBroken` reading the carried message. A test of
   `toValidationError` per rejection kind, a test per rejection kind that drives a
   real Iron codec rather than a hand-built `DecodeResult`, and one request-level
   test that a request missing a required header answers `400` with a
   `RequestRejected` body.
3. *The third request-failure case*, from `EWB-D-22`.
   `RequestFailure.Undecodable`, `BackendClient`'s split `mapError`, and the
   three match sites the compiler names. Last because the first two steps remove
   the two commonest reasons a reply does not decode, so the sentence this case
   renders is written against what actually remains: a response this service did
   not write.

After this phase every failure leaving endpoint logic has a declared shape,
which is what ADR-035's exhaustive-encoding rule asks for and what `§4`'s
`oneOf` cannot deliver on its own. The two exceptions are stated in `EWB-D-21`'s
amendment: a request matching no route answers `404` and one whose method does
not match answers `405`, both with a constant body, because neither reports a
failure of this service.

**Phase 2c — one log line per failed request.** `EWB-D-26`. `FailureOrigin` and
the two new `AppError` members, with `origin` declared on all thirty-one leaf
types and `logAnnotations` on the four that carry queryable fields; the log in
`wireEither`; and the deletion of `WorkspaceStoreLive.logResolveFailure` with its
two call sites and the `logSecurityWarning` helper left with no caller. Its tests
go in `WireEitherSpec`, which already drives the boundary inside a real span:
one per level, and one that the line carries the failure's annotations plus the
same trace the response names. The two store-spec cases that pinned the old
store-level line are removed, because the behaviour moved rather than going away.

**Phase 3 — typed locations and the content-rejection split.** `ErrorLocation`,
`ErrorSubject`, `FieldName`, `SubmittedCollection` and `RequestHeaderName`
(§6.4); `ValidationError.location`; the two wire cases
`RequestRejected` and `ValidationRejected` with `RejectionDetail` absorbing
`QueryRejected` (§3.5, `EWB-D-28`), and the `oneOf` statuses that go with them.
Every construction site of `ValidationError`, which is not confined to
`common`: the type is cross-compiled, so renaming the member breaks every module
at once. Fourteen files under `common/main` construct one, twenty under
`server/main` and two under `app/main`, with five more across the test sources.
The compiler enumerates them and `§13` states how that list is produced rather
than assembling it here. Three of the `app` files read the location rather than
building one — `RiskLeafFormView.scala`, `PortfolioFormView.scala` and
`FormSubmitUtil.scala` — and those are the sites where the substring matching in
`fieldForShapeError` and `formFieldFor` becomes an exhaustive match. `GlobalError.fromWireError`
loses its `QueryRejected` arm and reads `RejectionDetail` instead.

`TreeScope` and the third parameter on `fromAppError` (§5.1), the matching
parameter on `wireEither`, and the roughly twenty controller call sites that each
state `InTree(treeId)` or `NoTree`. `WireDecodeFailureHandler` builds
`RequestRejected` and returns an `ErrorLocation` from `fieldName`.

`ValidationError.message` stays an unrefined `String` through this phase.
`EWB-D-38` replaces it with `FieldProblem` as Phase 3b, so a refinement written
here would be removed by the next phase. It is not an injection surface in the
meantime, because the value is server-authored: `constraintBroken` builds it
from the refinement's own sentence with the submitted value dropped.
`BaseEndpoint` gains `requestRejectedOut` at `400` and moves
`validationRejectedOut` to `422`.

`GlobalError.RequestRejected` is renamed to `InputRejected` (§7.1), at the four
sites §7.1 names: the case declaration, the projection arm a refused query takes,
the render arm in `ErrorBanner.scala` and the assertion in
`GlobalErrorSpec.scala`. The internal `AccessDenied` and `WorkspaceExpired`
keep their names and their renamed imports, for the reason §5.1 records.

**Phase 3a — the located value and the single rooting.** `EWB-P-3`, designed in
§15. One new type, `Located[A]`, in `ErrorLocation.scala`, carrying a value with
the location a refusal about it names and a `map` that preserves the location
through a transformation; the twenty-six refinements in `ValidationUtil` changed
to take it; `Located.atCodec` and `Located.atConfig` replacing `codecLocation`
and the three copies of `configLocation`; located accessors and the form's
subject in both form states, with the leaf form's subject corrected on the edit
path; a `FormLevel` case added to `RiskLeafField` and `PortfolioField` and every
`case _ => None` arm of both mappings made explicit; the push inverted to a pull,
which deletes `FormSubmitUtil.scala` together with `submitFieldErrors` and its
three methods in `FormState.scala` and renames that trait's type parameter from
`F` to `Field`; and `GlobalError.ValidationFailed` delivered into the open
form's own state rather than flattened in `ErrorBanner`. Seventy-four refinement
call sites across seventeen files,
enumerated by the compiler once the signatures change, as §13 states. **Gated on
`EWB-D-39`**, which decides where a cross-field rule's choice of field lives;
nothing starts before it is ruled.

**Phase 3b — the refusal itself becomes a typed case.** `EWB-D-38`. `FieldProblem`
replaces `ValidationError.message: String`, declared in `ErrorLocation.scala`
beside the other closed sets a `ValidationError` is made of.
`ValidationErrorCode` is no longer stored on a `ValidationError` and is derived
by `FieldProblem.code`; `ValidationMessages` becomes the single `render`
function over the cases. 152 construction sites and 65 message templates, and a
change to the wire shape of a validation error, so ADR-017 and ADR-038 are both
re-reviewed for it. **Gated on Phase 3a being green**, because Phase 3a routes
every refinement through a rule declaration and so removes construction sites
this phase would otherwise convert, and because both phases rewrite the same
argument list. No work on it starts before then.

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

**Phase 7 — the storage trust boundary.** ADR-039 states that a value entering
process memory from outside is refined at the border, and that an ingress
rejection is a typed failure rather than a transport fault. The Irmin store and
the Postgres database are two such borders, and the error channel across both
collapses to a string today. Joining this plan rather than becoming a separate
one, for the reason Phase 6 gives: these are the error-path files this plan
already opens, and a boundary migrated halfway is worse than one not started.

It is last because Phase 3 gives it `ErrorLocation` and `ErrorSubject` to name a
rejected value with, and Phase 4's rename opens every file that names an
internal error. Phase 3's own pass over `RiskNode.scala` and `RiskTree.scala`
stays mechanical — `field` becomes `location` and nothing else — because the
decode change in step 3 below needs the repository side to move in the same
step.

Five steps.

1. **The ingress rejection becomes a type.** Added to `AppError.scala`:

```scala
/** Which border a rejected value crossed. */
enum TrustBoundary:
  case Store, Database, Authorization

/** Content an external system returned that this service refuses.
  *
  * Distinct from a transport failure at the same border: a peer that cannot be
  * reached is retried or reported as an outage, and content that does not
  * refine never succeeds on retry (ADR-039).
  */
case class IngressRejected(
  boundary: TrustBoundary,
  subject:  ErrorSubject,
  errors:   List[ValidationError]
) extends DomainError:
  override def logMessage: String =
    s"${boundary.toString} returned a value this service refuses: " +
      errors.map(e => s"[${ErrorLocation.describe(e.location)}] ${e.message}").mkString("; ")
  override def origin: FailureOrigin = FailureOrigin.Service
```

   `subject` reuses Phase 3's enum rather than naming a storage path, which is
   what keeps the "no storage-path refinement" ruling of §6.4 intact: a stored
   value is a node, a mitigation or a tree, and `ErrorSubject` already says so.
   `fromAppError` gains one arm projecting it to `InternalFault`, on the same
   grounds as `RepositoryFailure`: a store this service wrote is not the
   caller's doing and the caller can act on nothing.

2. **`handleIrmin` is deleted.** `IrminError` already extends `AppError`, and the
   repository's methods already declare `IO[AppError, A]`, so every call it
   wraps type-checks without it. The wrapper is therefore pure loss: it maps
   seven typed cases onto `RepositoryFailure(String)`, and §5.1's own table
   sends the Irmin cases to `DependencyUnavailable` at `503` while
   `RepositoryFailure` goes to `InternalFault` at `500`. An Irmin outage during
   a tree read is reported as an internal fault today for no reason other than
   this function. Deleting it and inlining its call sites restores the status
   the projection already specifies. File:
   `repositories/RiskTreeRepositoryIrmin.scala`.

3. **A stored value keeps its validation structure.** `RiskLeaf` and
   `RiskPortfolio` gain a decode that returns the accumulated errors, and the
   derived `JsonDecoder` is defined in terms of it so there is one parse rather
   than two:

```scala
object RiskLeaf:
  def decodeValidated(json: String): Either[List[ValidationError], RiskLeaf]

object RiskPortfolio:
  def decodeValidated(json: String): Either[List[ValidationError], RiskPortfolio]
```

   A string that is not the expected JSON shape yields one `ValidationError` at
   `ErrorLocation.Whole` with `INVALID_FORMAT`, so a parse failure and a
   refinement failure have one shape and the caller is not made to distinguish
   them. The flattening in the `given decoder` is what `zio-json`'s `mapOrFail`
   forces — its function returns `Either[String, A]` — so the storage path stops
   going through `JsonDecoder` and calls `decodeValidated` directly. The
   repository's `decodeNode`, `decodeMeta`, `decodeMitigation` and `rebuildTree`
   then fail with `IngressRejected` carrying the list, instead of interpolating
   it into a sentence. Files: `domain/data/RiskNode.scala`,
   `domain/data/RiskTree.scala`, `domain/data/Mitigation.scala`,
   `repositories/RiskTreeRepositoryIrmin.scala`.

4. **The database classifies once, from a code rather than from prose.**
   `WorkspaceStorePostgres.db` maps every driver failure to
   `RepositoryFailure(getMessage)`, and one caller then recovers a decision from
   that text with `reason.contains("seed_entity_id")`, dying on the arm that
   does not match. The classification moves into the helper and reads the
   driver's `SQLState`, which is a standard code and not a sentence:

```scala
private def classify(err: Throwable): AppError
```

   `23505` is the unique-violation state. Which unique constraint was violated
   decides whether the result is the duplicate-seed-entity rejection or a plain
   `RepositoryFailure`; whether the driver exposes the constraint name is
   confirmed when the step opens the file, and the fallback if it does not is to
   classify on the state alone and keep the constraint distinction at the one
   call site that needs it. File:
   `services/workspace/WorkspaceStorePostgres.scala`.

5. **The Irmin response model names its identifiers.** `IrminCommit.hash`,
   `.key` and `.parents` are `String` where `CommitHash` exists, and
   `TreeNode.hash` is the same value in the GraphQL response type. They become
   `CommitHash`, refined where the response is parsed, so the one late
   refinement in `resolveHead` becomes the general case rather than the
   exception. `TreeNode.path` becomes `IrminPath`, which `childPaths` already
   refines by hand.

   `IrminBranch.name` and `IrminClient.branches` stay `String`, and that is a
   recorded exemption under ADR-039 §5 rather than an omission: the store may
   hold branches this service did not create, so refining the listing to
   `BranchRef` would reject a foreign branch instead of ignoring it. The comment
   at the site says so. Files:
   `infra/irmin/model/IrminCommit.scala`,
   `infra/irmin/model/IrminResponses.scala`,
   `infra/irmin/IrminClient.scala`,
   `infra/irmin/IrminClientLive.scala`,
   `repositories/RiskTreeRepositoryIrmin.scala`.

The step order is smallest blast radius first: step 1 adds a type nothing yet
throws, step 2 is a deletion, and steps 3 and 5 change how values are parsed.
Step 4 is independent of the other four and could run at any point in the phase.

`VersionConflict`'s three fields are typed per `EWB-D-29` and that edit has
landed, so the phase does not carry it. No decision in this phase is open.

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

**Superseded in part by `EWB-D-27`.** The last sentence held when this was
ruled. An unreachable authorization service now answers as a dependency outage
rather than as a denial, so what the dropped reason makes indistinguishable is
one denial from another. Dropping it is still required, for that reason.

**Extended by `EWB-D-28`.** The list above names three changes because, when it
was ruled, `422` was reached only by a refused query. Splitting the rejection in
two put every content failure of a known tree at that status, so there is a
fourth: a request body refused on a tree-addressed route answers `422` where it
answered `400`. It follows from the same rule as the first — one status per wire
case — and is accepted on the same grounds. The creation path is unaffected,
because no tree exists to name there and the answer stays `400`.

*Cost:* three observable changes at once. There is one first-party consumer and no
external API contract, so the cost is one coordinated change in a repository you
own.

*Concretely:* a user types a query naming a node that does not exist. The browser
receives `422` with
`{"tree":"01ARZ…","detail":{"kind":"Query","problem":{"kind":"UnknownReferences","messages":[...]}},"trace":"…"}`
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
`GlobalError` then has one match with two arms: a call to `fromWireError`, and
the transport arm answering `NetworkError` directly.

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
open while §7.1 kept a transport arm in `GlobalError.from` for it.

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

### EWB-D-21 — where the defect catch-all lives — RULED 2026-10-14, location revised 2026-10-15

**Ruled: the catch-all emits the error output the endpoints already declare, from
an interceptor of its own, appended so that it is the innermost request
interceptor.** §5.3 states the shape.

**What was revised, and why.** The ruling of 2026-10-14 chose Option A below and
placed the catch-all inside `RequestSpanInterceptor`. The output it emits is
unchanged and the reasoning that chose a declared output over a hand-built body
stands. What was wrong is the premise that decided the location: Option A's first
pro asserted that `RequestSpanInterceptor` is the outermost interceptor, and it
is not. Two facts settle it, both read from Tapir 1.13.4 rather than inferred.

`CustomiseInterceptors.prependInterceptor` appends to the list it names:

```scala
def prependInterceptor(i: Interceptor[F]): CustomiseInterceptors[F, O] = copy(prependedInterceptors = prependedInterceptors :+ i)
```

and `ServerInterpreter.callInterceptors` applies the head of the stack *around*
the recursion over its tail, so the first element is the outermost:

```scala
case i: RequestInterceptor[F] =>
  i(responder, { ei => RequestHandler.from { (request, ses, _) => callInterceptors(is.tail, ei :: eisAcc, responder).apply(request, ses) } })
```

`Application.startServer` prepends `SecurityHeadersInterceptor` first and
`RequestSpanInterceptor` second, so the security headers are outermost and the
span interceptor is second. `corsInterceptor` is placed after the whole prepended
list, so the CORS interceptor runs **inside** the span interceptor — which means a
catch-all inside the span interceptor sits outside CORS, and §5.3 shows why that
makes its response unreadable to a browser.

The revision therefore changes the location and keeps everything else. The
interceptor's `Cons` below is also answered rather than accepted: the tracing
interceptor no longer acquires a second responsibility, because the catch-all is
no longer in it.

A ZIO defect escaping a controller is answered by `ZioHttpInterpreter` with
`Response.internalServerError(cause.squash.getMessage)`, so the exception's own
message becomes the body. That was the state when this decision was taken: there
was no `exceptionHandler`, no `decodeFailureHandler` and no `handleErrorCause`
anywhere in the server module or the integration harness, and
`Application.startServer` used `customiseInterceptors` only to prepend two
interceptors. All three are now set — see the amendment at the end of this
decision for the exception handler, which a typed failure needs in order to
reach the catch-all at all.

**Option A — the catch-all in `RequestSpanInterceptor`** (ruled 2026-10-14; its
location superseded by the revision above, its output kept). Tapir hands an
interceptor a `Responder`, which builds a response from a declared endpoint
output, so the interceptor answers with `BaseEndpoint.internalFaultOut` — the
same status and body the `oneOf` variant sends.

*Pros:* one place sees every request, and the span whose identifier the body
needs is open around it. Emitting a declared output is what keeps the catch-all
from being a second place that knows `InternalFault`'s wire shape. **The pro
originally written here claimed this interceptor is the outermost one, which is
false** — the revision above reads the two Tapir definitions that settle it — and
both surviving pros hold for an appended interceptor as well, which is why only
the location changed.
*Cons:* an interceptor named for tracing acquires a second responsibility, so its
name under-describes it. The revision removes this cost rather than accepting it.

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

**Amendment, 2026-10-15: the exception handler a typed failure needs.** The
catch-all as ruled above sees a defect and nothing else. Tapir's own
`DefaultExceptionHandler` is installed by default, it runs inside this
interceptor, and it answers every exception itself with `500` and the fixed
string `"Internal server error"` — no declared shape and no trace. So a
`Throwable` failure escaping endpoint logic never reached the catch-all.

`ExceptionInterceptor.onException` re-raises when a handler answers `None`:

```scala
handler(ExceptionContext(e, endpoint, request)).flatMap {
  case Some(output) => responder(request, output)
  case None         => monad.error(e)
}
```

So `DefectCatchAllInterceptor.exceptionHandler` answers `None` for the general
case and the failure passes outwards to the `catchAllCause`, which gives it the
declared output and the trace. Tapir's two special arms are delegated to it
rather than re-raised, because their status belongs to the request rather than to
this service: a body over its declared maximum is `413` and a malformed
multipart body is `400`, and answering either as `500` would be wrong. Neither is
reachable while no endpoint declares a stream or multipart body, and
`DecodeFailureStatusSurfaceSpec` is what keeps that true.

Replacing the handler outright was the alternative and is rejected: it discards
those two arms for no gain, since returning `None` for one case and delegating
the others costs the same code.

Two answers still come from Tapir and keep a constant body: `404` for a request
matching no route, and `405` from the reject handler. Neither reports a failure
of this service, so neither carries a trace worth delivering, and the catch-all's
documentation says so rather than claiming every response has a declared shape.

**The server log is redacted in the same change.** Tapir's default server log
renders the whole request line through `ServerRequest.showShort`, so a failure
reaching it would write `/w/<capability key>` to standard output at `ERROR` —
the sink the span name is already redacted to avoid. `showRequest` is pointed at
`RequestSpanInterceptor.spanName`, so both sinks read one definition and neither
can drift from the other. That member stops being `private[http]` for this
reason.

### EWB-D-22 — how the browser reports an answer it cannot decode — RULED 2026-10-14

**Ruled: Option A, a third `RequestFailure` case carrying the status.**

`BackendClient` maps every failure of `send` to `Transport`, whose sentence is
"The request did not reach the server. Nothing was changed." A decode rejection
also fails `send`, because the interpreter was built with
`toRequestThrowDecodeFailures`, so an answer the browser could not read was
reported as a request that never arrived.

Implementing this showed that the interpreter has to change as well, and §7.8
carries the correction: the exception sttp throws holds no response, so the
status the ruling asks for is unreachable once a decode failure has been thrown.
`toRequest` is used instead, which yields the failure as a value beside the
response it came from.

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

**Ruled: Option A, a `DecodeFailureHandler` producing the declared rejection
shape rather than Tapir's plain text.**

**Refined by `EWB-D-28`:** the case it produces is `RequestRejected` at `400`.
The handler runs before any handler, so it never has a tree, and the case that
carries one is now `ValidationRejected` at `422`. The option text below names
`ValidationRejected` because that was the single rejection case when this was
ruled; the decision itself — answer a rejected request with a declared typed
shape — is unchanged.

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
header returns
`{"errors":[{"location":{"kind":"RequestHeader","header":{"kind":"Branch"}},"code":"REQUIRED_FIELD",…}],"trace":"…"}`,
and the form shows the header name. That body is a `RequestRejected`, and the
`location` member is the typed `ErrorLocation` of §6.4, so this example states
the shape after Phase 3 rather than the `field` string the handler builds before
it.

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

### EWB-D-25 — how an Iron refinement reports a refusal — RULED 2026-10-15

**Ruled: Option C. The twelve Iron codecs report a refusal as
`DecodeResult.InvalidValue`, carrying the refinement's own sentence in
`customMessage` and a constant in the rejected-value slot.** §5.3 states the
three definitions and the codec shape.

**Goal.** `toValidationError` answers `INVALID_FORMAT` for a value that was read
and then refused, and `TYPE_MISMATCH` for one that could not be read. Every Iron
refinement produces the first situation and reports the second, so the code the
wire carries is wrong for the commonest client error on this API. Deciding how
the codecs report fixes the classification and decides what the message says.

**What the codecs do today.** Each of the twelve `given Codec` definitions in
`IronTapirCodecs.scala` parses with `Codec.int`, `Codec.long` or `Codec.string`
and then calls a smart constructor, reporting a refusal as
`DecodeResult.Error(raw, new IllegalArgumentException(errs.map(_.message).mkString("; ")))`.
The parse step handles unreadable input on its own, so reaching the smart
constructor already proves the value was readable. The twelve are the codecs for
`PositiveInt`, `NonNegativeInt`, `NonNegativeLong`, `SafeId`, `TreeId`, `NodeId`,
`WorkspaceKeySecret`, `UserId.Authenticated`, `ScenarioName`, `CommitHash`,
`BranchChoice` and `SeedEntityId`.

**Option A — leave the codecs and remap the code.** Change `toValidationError`
so `DecodeResult.Error` answers `INVALID_FORMAT`.

*Cost:* it relabels the genuinely-unreadable case too, so the distinction the two
codes exist to draw is lost rather than corrected. `TYPE_MISMATCH` would then
have no producer at all.

**Option B — switch to `InvalidValue` and keep the current renderer.** The
codecs report `InvalidValue`; `constraintBroken` goes on rendering
`e.validator.toString`.

*Cost:* the refinement's sentence is discarded. `ValidationMessages` holds the
useful text — "Scenario name must be at most 30 characters" — and the renderer
would print a validator's `toString` instead, so the classification would be
right and the message worse than today's.

**Option C — switch to `InvalidValue` carrying the sentence** (ruled). The
sentence travels in `customMessage`, a shared constant occupies the
rejected-value slot, and the renderer reads the message.

*Pros:* the code is correct and the message is the refinement's own sentence,
which is written for the person who submitted the value. The non-disclosure
becomes structural: Tapir's own renderer prints the rejected-value slot in every
branch — `invalidValueMessage` ends `but got: ${ve.invalidValue}` — so putting a
constant there means no renderer can echo client input, rather than relying on
ours continuing not to read the field. No `ValidationUtil` message interpolates
the submitted value, so passing the sentence through discloses nothing.
*Cons:* three new definitions in the codec file, and the renderer changes with
the codecs rather than being left alone.

*Concretely:* a client sends `X-Branch: a-branch-name-well-over-thirty-characters`.
Today the response says the header "could not be read", coded `TYPE_MISMATCH`.
After the change it says the header is at most 30 characters, coded
`INVALID_FORMAT`, and the submitted text appears nowhere in the body.

**Trade-off.** Whether correcting the classification is worth touching the codec
file and the renderer together. What settles it is that `DefaultDecodeFailureHandler.respond`
treats the two failure kinds identically where it matters — its header, body and
path-capture rules name both in one pattern — so the switch changes which code
the wire carries and nothing about whether a response is sent at all.

### EWB-D-26 — a failure that becomes a response is logged once, at the boundary — RULED 2026-10-15

**The problem.** Whether a failed request is logged at all depended on whether
some service had remembered to tap it. `WorkspaceStoreLive.resolveInternal`
logged a failed resolve and `resolve` did not, so the path every
workspace-scoped request actually takes — `resolve`, and the two trait methods
built on it — wrote nothing. Nothing detects such an omission, which is how it
arose: nobody decided the main entry point should be silent.

**What is ruled.** `wireEither` is already the one point every controller
failure passes through on its way to a response, and it already reads the trace.
It now also writes one line per failure. The level comes from a new member on
`AppError`:

```scala
enum FailureOrigin:
  case Caller
  case Service

sealed trait AppError:
  def logMessage: String
  def origin: FailureOrigin
  def logAnnotations: Map[String, String] = Map.empty
```

`origin` is abstract rather than defaulted, which is the whole point: a new
failure case does not compile until it says which it is, so request-failure
logging stops being something a contributor has to remember. An exhaustive match
at the boundary would have been weaker, because a wildcard arm satisfies it.

**Why the origin is read from the internal failure and not from the wire case.**
The wire case is deliberately coarser than the failure: several failures share
one case and they do not share an attribution. `InternalFault` carries both
`RepositoryFailure`, which is this service's own, and `BranchAlreadyExists`,
which is a caller's, so a level read from the wire case would give one level to
both. Reading it from the `AppError` keeps each failure at the level its own
attribution decides, whatever the response flattens.

**The levels.** `Caller` is `INFO` and `Service` is `WARNING`. `INFO` rather than
`DEBUG` because the deployment's log level defaults to `INFO`, so at `DEBUG`
these lines would be absent from a default deployment — which is the condition
this decision exists to end.

**What it replaces.** `WorkspaceStoreLive.logResolveFailure` is deleted with its
two call sites and the `logSecurityWarning` helper that had no other caller. Its
annotations are not lost: they move onto `WorkspaceExpired.logAnnotations` and
`TreeNotInWorkspace.logAnnotations`, where the boundary reads them and adds the
trace the store could never supply. The four lifecycle events the store logs on
create, evict, delete and rotate are untouched — they record operations that
succeeded, which no failure boundary sees.

**What it does not replace, and why that is not a shortfall.** The other failure
logging in the server module stays. Those sites log one of three things a
boundary cannot see: an anomaly on a request that succeeded, such as
`QueryServiceLive`'s mitigation-resolution drift, whose own comment says it "is
a no-op for that mitigation, not a request failure"; a failure that is
deliberately handled, such as the cascade deletes that log each item and then
`.ignore` it; or detail dropped before the failure propagates, such as
`QueryServiceLive`'s cache diagnosis logged by `tapError` and then replaced by
`mapError`. This decision makes request-failure logging exhaustive. It does not
consolidate logging that is not about a request failure, and that was never
available to it.

**A request the decoders rejected is logged at the handler, because the boundary
never sees it.** A decode failure is answered before any controller runs, so it
never reaches `wireEither` and the line above is never written for it. Without a
line of its own, a trace a caller quotes from a `400` would match nothing in the
log. `WireDecodeFailureHandler.logRejection` therefore writes one `INFO` line per
rejected request, naming the field, the message and the status answered. It does
not take a trace as an argument: the handler runs inside the span interceptor, so
the annotation carrying the trace is already applied to every line it writes. The
level is `INFO` for the same reason the `Caller` case is — a rejected request is
the service working correctly.

**An endpoint advertising an authentication challenge is logged as a defect of
this service.** Tapir computes `401` for a decode failure only where the endpoint
declares an authentication input. No endpoint here declares one, which
`DecodeFailureStatusSurfaceSpec` pins, so `logIfChallengeAdvertised` records a
configuration that should not exist rather than anything a caller did. That is
why it is `ERROR` while the rejection line beside it is `INFO`.

**Cost, accepted.** Every failed request now writes a line, so a client sending
invalid input produces log volume where it produced none. The level split is
what keeps that tolerable, and it is why `Caller` and `Service` are separated
rather than logged uniformly. Two sites double-log: the merge guard and the
Irmin client each log pre-conversion detail and then fail with a narrower error
the boundary also logs. Those pairs say different things and both are wanted.

**ADR-002 is amended by this decision.** Its code-smell section demands that log
levels be differentiated, which this satisfies; its one example put a validation
failure at `logWarning`. That example now shows a caller-caused failure at
`logInfo`, with the reason stated beside it.

### EWB-D-27 — an authorization-service outage answers as a dependency outage — RULED 2026-10-15

**The problem.** `AuthServiceUnavailable` projected onto `WireError.AccessDenied`,
so when the authorization service was unreachable every caller was told "You do
not have access to this. If you reached it from a link, the link may no longer be
valid." An unreachable Irmin answers `DependencyUnavailable`, whose sentence is
"A service this request needed did not answer. Nothing was changed. Try again in
a moment." The same class of fault therefore produced opposite guidance depending
on which dependency failed, and the authorization case told the user to stop
trying during an incident they should have waited out.

**Ruled: `AuthServiceUnavailable` projects onto `DependencyUnavailable`, and what
the caller sees must not say which dependency failed.**

Two reasons decide it. Failing closed is a property of the decision, not of the
wording — the request is refused either way, and no authorization decision is
assumed where none was obtained. And ADR-035 §3 bounds its own opaque-status rule
to the case this is not: "The rule binds exactly where the caller's entitlement is
in question." An outage is not a case where entitlement is in question; it is one
where it is unknown.

The second half of the ruling is what keeps the change from disclosing anything.
`DependencyUnavailable` carries only a trace, and the browser's sentence for it
names no dependency, so an authorization outage and a storage outage are the same
answer. A test pins that: the two must serialise identically, which is the
replacement for the test that pinned three refusal paths as identical.

**What this supersedes.** `EWB-D-6`'s Option C was costed on the premise that
"`AuthServiceUnavailable` must then map to the same value as `AuthForbidden`
anyway"; that premise no longer holds, and Option C was not taken in any case.
`EWB-D-26`'s argument for reading the log level from the internal failure rather
than the wire case used this pair as its example. The argument stands and its
example is now `InternalFault`, which carries both `RepositoryFailure`, this
service's own, and `BranchAlreadyExists`, a caller's.

**ADR-035 §3 is amended by this decision.** Its sentence "`AuthForbidden` and
`AuthServiceUnavailable` both project onto `AccessDenied` for the same reason: an
explicit deny and an unreachable authorization service are indistinguishable from
outside" is replaced by one stating that the two denial paths are
indistinguishable from each other, and that an outage is instead indistinguishable
from any other dependency outage.

### EWB-D-28 — the tree a content rejection is about, and where it is declared — RULED 2026-10-16

**Ruled: `ValidationRejected` carries `tree: TreeId` and answers `422`; a
request-level rejection becomes its own case `RequestRejected` at `400`;
`QueryRejected` is absorbed into `RejectionDetail`. `ErrorLocation.Node` carries
only `nodeId`.**

**Goal.** A node-located validation error named a node and not the tree it
belongs to, so a reader of the response had to recover the tree from the request.
The same gap was wider on the query side: `QueryProblem.UnknownReferences`
reports names that are "not in the tree" while naming no tree at all. Deciding
where the tree is declared decides `ErrorLocation`'s shape, so it blocked
Phase 3.

**Why the tree is not a field of the location.** Two reasons, and the second is
what settles it. `ErrorLocation` lives inside `ValidationError`, and
`QueryRejected` carries a `QueryProblem` rather than a list of `ValidationError`,
so a tree on the location could never reach the query case. And on the creation
path no tree identifier exists when those errors are produced:
`RiskTreeDefinitionRequest` is `(name, portfolios, leaves)` and `ResolvedCreate`
is `(treeName, nodes, leafOccurrenceAndShape, providedSeedVarIds, rootName)` —
neither carries one, node ids are minted during validation and the tree's is
minted later in the service. Whether the tree gets an identifier at all is what
the validation is deciding.

**Why two cases rather than one with an optional tree.** The paths that produce a
rejection divide cleanly. Tree creation, `POST /w/{key}/scenarios`,
`POST /workspaces`, `POST /distribution/preview` and the workspace rotate and
delete routes name no tree, and a decode failure on any of them is answered
before a handler runs. Every other producing endpoint carries
`path[TreeId]("treeId")`. So each case's field is total: `RequestRejected` never
has a tree and `ValidationRejected` always does, and neither carries an optional
field.

**Where the projection gets the tree: the call site.** `fromAppError` is a
function of the failure and the trace, and an `AppError` carries no tree. Three
sources were available and the call site was taken.

*The error value* would be compiler-enforced — a failure could not exist without
a tree where one is required — and costs 38 `ValidationFailed` construction sites
across 18 files, including 6 in `Simulator.scala` and 5 in
`CachedResultResolverLive.scala` where no tree identifier is in scope and one
would have to be threaded in. Those are the same sites, reached by the same
argument, that this decision already declines to touch for
`ErrorLocation.Node`.

*A fibre reference* read inside `wireEither` would mirror what `ActiveTrace.id`
already does for the trace, and was declined because the tree is not in the
trace's situation: a controller that forgets to set it silently answers `400`
where `422` was due, which is the failure mode `EWB-D-26` exists to end.

*The call site* is taken, because the tree is lexically in scope on the line that
calls the projection — every tree-scoped handler destructures `treeId` and ends
its comprehension with `.wireEither`. The scope is stated there, as a required
argument with two named cases so that nothing can be defaulted or dropped:

```scala
enum TreeScope:
  case InTree(tree: TreeId)
  case NoTree

def fromAppError(e: AppError, trace: TraceId, scope: TreeScope): WireError

extension [R, A](effect: ZIO[R, AppError, A])
  def wireEither(scope: TreeScope)(using tracing: Tracing): ZIO[R, Nothing, Either[WireError, A]]
```

`TreeScope` never crosses the wire — both wire cases have total fields, and this
is only the projection's input. `AppError` is unchanged: no split, no new case,
no added field. The residual weakness, stated rather than hidden, is that a wrong
`NoTree` at a tree-scoped call site compiles; it is a named, greppable choice
rather than a dropped argument, and §13 pins the statuses per route.

A query failure reaching the projection with `NoTree` cannot occur, because the
query route carries `path[TreeId]("treeId")`. It answers `InternalFault`, since a
projection called wrongly is this service's defect rather than the caller's, and
the boundary log line records it.

**Why `422`, and why the query case merges into it rather than sharing the
status.** A submitted tree body that parsed and failed its own rules is the same
category as a query that parsed and failed to type-check: both are content the
server understood and could not process, which is what `422` means. Two cases
cannot share it — §4 states that the browser selects the variant from the status
alone, so the second would never be decoded — so the two become one case whose
payload discriminates. `RejectionDetail` is at §3.5, and the construction is the
one `Conflict` already uses.

**What was rejected, and why.** Flattening `QueryProblem` into
`List[ValidationError]` was considered and declined: `Unparseable`'s
`position: Option[Int]` has nowhere to go in a `(location, code, message)`
triple, and the browser would have to choose a treatment by reading a generic
`ValidationErrorCode` or the message prose instead of matching four declared
cases. Retrofitting the pattern onto `MergeRefusal` was also declined, because
merge is workspace-scoped and genuinely multi-tree —
`DuplicateNames(perTree: Map[TreeId, List[SafeName]])` states which names
repeated in which tree, and a single tree field cannot carry that.

**Cost, accepted.** `QueryRejected` stops existing as a wire case, so the
browser's match over the nine cases changes and `GlobalError` reads
`RejectionDetail`. The case count and the one-status-per-case property both
survive: `ValidationRejected` splits in two and `QueryRejected` is absorbed, so
it is still nine cases at nine statuses.

---

### EWB-D-29 — `VersionConflict` is typed and kept — RULED 2026-10-19

`VersionConflict` had three `String` fields and no production constructor, which
is the same untyped-identifier defect Phase 7 step 5 removes elsewhere. The
question was whether to type it or delete it, and the answer turns on whether
the type marks a feature worth building. It does:
`PLAN-TREE-WRITE-CONCURRENCY.md` names it in its draft signatures, its file
inventory and its ADR-alignment table, and rules the direction — a
client-supplied version token that rejects a stale whole-tree write.

**Ruled: type the fields and keep the case.**

```scala
case class VersionConflict(treeId: TreeId, expected: CommitHash, actual: CommitHash) extends SimError
```

The first field names a tree rather than a node because the precondition is on
the branch head, which no single node owns. `PLAN-TREE-WRITE-CONCURRENCY.md`
raises the same point against its own draft and leaves it for its echo to
settle; this ruling settles it.

**The shape is an educated guess and is not a design ruling.** It follows from
where the check would sit — `RiskTreeServiceLive.update`, which holds a `TreeId`
and compares two commits — and nothing more. Whether a tree-level conflict needs
to name the node that diverged, whether the client needs both commits or only
the current head, and whether the token is a commit at all are decided by
`PLAN-TREE-WRITE-CONCURRENCY.md`'s own design work. This phase types the fields
so that plan inherits a correct type instead of a `String` triple; it does not
pre-empt that plan, and a change there overrides this shape without needing to
revisit this decision.

Deletion was the alternative and was rejected. Adding a leaf back to a sealed
hierarchy with exhaustive matching is a compile error at every match site, so
deleting the case moves that cost into the other plan — which currently assumes
the opposite, stating that reusing the existing case "avoids touching every
exhaustive `AppError` match".

---

### EWB-D-30 — how an error names the node it is about — RULED 2026-10-19

**Ruled: `ErrorSubject` names a node by its identifier where one exists, and by
its position within a named collection where one does not.**

A tree update request carries four collections — the portfolios already in the
tree, the leaves already in the tree, the portfolios being added, and the leaves
being added. Each is indexed from zero independently, so a position alone named
four different rows. The browser does not read the position today, but the tree
endpoints are a public contract and a client submitting a multi-node update has
no other way to map an error back to the row that caused it.

```scala
/** Which submitted collection a node came from. The four are indexed
  * independently from zero, so a position alone does not identify a row. */
@jsonDiscriminator("kind")
enum SubmittedCollection:
  case Portfolios, Leaves, NewPortfolios, NewLeaves

object SubmittedCollection:
  given codec: JsonCodec[SubmittedCollection] = DeriveJsonCodec.gen[SubmittedCollection]
```

```scala
  /** A node already in the tree, pointed at by its identifier. */
  case ExistingNode(nodeId: NodeId)

  /** A node pointed at by its position in a submitted list, because it has no
    * identifier yet or because its identifier is what failed to refine. */
  case NodeAtIndex(collection: SubmittedCollection, index: NonNegativeInt)
```

```scala
  def nodeAtIndex(collection: SubmittedCollection, index: Int): ErrorSubject =
    index.refineEither[GreaterEqual[0]].fold(_ => Request, NodeAtIndex(collection, _))
```

The two cases are named for how the node is pointed at rather than for whether
it was submitted, because an update submits existing nodes too. `ExistingNode`
is what `EWB-D-28` ruled carries only `nodeId`; this decision renames that case
and adds the positional one beside it.

The field checks — `refinePortfolioDefs`, `refineLeafDefs`,
`refineExistingPortfolios`, `refineExistingLeaves` — report at
`NodeAtIndex` even for a node that already exists, because those are the
functions refining the identifier and the identifier may be the value that
failed. `ExistingNode` appears only in the topology checks, which run after
refinement has succeeded.

The topology checks take the node's parent together with the subject naming it:

```scala
final case class SubmittedParent(subject: ErrorSubject, parent: ParentRef)
```

`requirePortfolioParents` therefore loses its type parameter and its
`parentOf` extractor, because every caller now maps its own collection into
`SubmittedParent` before calling.

---

### EWB-D-31 — the uniqueness checks collapse into one, reporting per duplicate — RULED 2026-10-19

**Ruled: the unification ruled on 2026-10-07 is carried into Phase 3 and done
here; one shared check reports one error per duplicated key, and the name rule
adopts that shape rather than keeping its combined sentence.**

`PLAN-TYPED-ERROR-STRUCTURE.md` ruled the collapse and sequenced it to land with
the typed error location, because the four methods it removes exist only to
supply a location string. That plan was superseded by this one, and §14 carried
three of its unlanded items across without this one. Phase 3 is the work that
introduces `ErrorLocation`, so the collapse happens here.

```scala
/** Fails when the submitted collection contains duplicates. Duplicates are
  * found by grouping on the key: a group larger than one is a duplication,
  * and each one becomes an error carrying its group.
  *
  * {{{
  * // four nodes named Fire, Flood, Fire, Fire
  * requireDistinctBy(nodes, _.name, toError)
  * // toError called once, with ("Fire", the three Fire nodes)
  * // Flood is in a group of one, so it is not a duplication
  * }}}
  */
def requireDistinctBy[A, K](
  elements: Seq[A],
  key: A => K,
  toError: (K, Seq[A]) => ValidationError
)(using Ordering[K]): Validation[ValidationError, Unit]
```

```scala
def requireDistinctNames(names: Seq[SafeName], location: ErrorLocation): Validation[ValidationError, Unit]

def requireDistinct(holders: Seq[(SafeName, SeedVarId)], location: ErrorLocation): Validation[ValidationError, Unit]
```

A per-group function rather than one over the whole list, so the non-empty
failure is built in one place and no caller can report some duplications and
leave the rest out. The two error codes stay distinct and are not a parameter of
the shared check: a repeated name is `AMBIGUOUS_REFERENCE`, because a child
names its parent by name and the reference then resolves to two nodes, while a
repeated seed identifier is `DUPLICATE_VALUE`, because nothing refers to a node
by its seed identifier.

The message carries the number of occurrences, so a user resolving one of three
repetitions sees the count fall rather than an unchanged sentence:

```scala
def duplicateNodeNames(name: String, occurrences: Int): String =
  s"duplicate node name: $name — used by $occurrences nodes; names must be unique within a tree"
```

Deleted: `SafeName.duplicates`, `RiskTree.requireDistinctNodeNames`,
`RiskTree.requireDistinctSeedVarIds`, `RiskTreeRequests.requireUniqueNames`
— whose call site keeps `.map(_ => allNames.toSet)` for the deduplicated set it
uses — and `RiskTreeRequests.requireUniqueSeedVarIds`.

Three assertions read one of the two name sentences and change when they become
one: `RiskTreeDefinitionRequestsSpec.scala:113`, `:251`, and
`TreeBuilderLogicSpec.scala:27`.

**The location both rules report at inside `RiskTree.fromNodes` — RULED
2026-10-19.** Both report at `ErrorLocation.Whole`, and the offending values are
named in the message.

The reason is what a location is for: it points at the input the caller can
change. No input of `fromNodes` is a field, and on three of its call sites there
is no request at all, so there is nothing for a narrower location to name. The
node identifiers the alternative would have carried do not close that gap
either, because a submission that fails has no persisted nodes — the identifiers
in it were generated for a tree that was never written, so the client cannot
resolve one and neither can a reader of the log.

Naming one holder of a duplicate group was the alternative. It was rejected on
the same ground: it names a node out of several where none is more at fault,
while supplying an identifier that resolves to nothing. Taking the location as a
parameter was the other, and the two call sites inside `RiskTree` itself — the
Tapir schema's decode direction and the JSON decoder — are `given` instances
with no caller context, so they would hardcode a default regardless.

Two facts about this function bear on the choice. It has six production call
sites, not two: a store read (`RiskTreeRepositoryIrmin.scala:171`), the create
and update request paths (`RiskTreeServiceLive.scala:331` and `:370`),
mitigation application (`MitigationApplication.scala:165`), and `RiskTree`'s own
Tapir schema and JSON decoder (`RiskTree.scala:71` and `:94`). And on the two
request paths the failure becomes `ValidationFailed`, a caller-origin error, so
its location reaches the client inside `WireError.ValidationRejected`; on the
other four it appears only in a server log. The update path is the designated
catcher for a new leaf supplying a seed identifier an existing leaf already
holds, so that one answers a client.

These two checks are a safety net on every path that reaches them. The request
boundary rejects duplicate names and duplicate seed identifiers before
`fromNodes` runs, so a duplicate arriving here means the server produced one.

---

### EWB-D-32 — an absent JSON value becomes a named case at the boundary — RULED 2026-10-19

**Ruled: where the absence of a request field has a meaning, that meaning is a
case of an enum, converted once where the body is decoded. `Option` appears in
the wire type and nowhere inward of it.**

An `Option` carried inward states that a value may be missing and leaves every
later layer to re-derive what missing meant. The two instances in the tree
request path:

```scala
/** A node's parent: the tree's root has none, every other node names one.
  *
  * {{{
  * ParentRef.Root                        // the top portfolio
  * ParentRef.Under(SafeName("IT"))       // a leaf sitting under "IT"
  * }}}
  */
enum ParentRef:
  case Root
  case Under(parent: SafeName.SafeName)
```

```scala
/** Where a leaf's seed identifier comes from. Absent in the request means the
  * server assigns one. */
enum SeedVarIdChoice:
  case ServerAssigns
  case Provided(seedVarId: SeedVarId.SeedVarId)
```

`refineParentName(raw: Option[String], location: ErrorLocation)` returns
`ParentRef` and is the single place the two representations meet; a JSON `null`
or a blank string becomes `ParentRef.Root` there. Worked through, for a request
body of

```json
"portfolios": [
  { "name": "IT",   "parentName": null },
  { "name": "Apps", "parentName": "IT" }
]
```

`refinePortfolioDefs` returns `Seq(("IT", ParentRef.Root), ("Apps", ParentRef.Under("IT")))`
where it returns `Seq(("IT", None), ("Apps", Some("IT")))` today, and
`refineExistingPortfolios` returns
`Seq((SafeId("01HF…A1"), "IT", ParentRef.Root), (SafeId("01HF…B2"), "Apps", ParentRef.Under("IT")))`.

The four return types become:

```scala
Seq[(SafeName.SafeName, ParentRef)]
Seq[(SafeName.SafeName, ParentRef, OccurrenceProbability, Distribution, SeedVarIdChoice)]
Seq[(SafeId.SafeId, SafeName.SafeName, ParentRef)]
Seq[(SafeId.SafeId, SafeName.SafeName, ParentRef, OccurrenceProbability, Distribution)]
```

**The sweep of every `Option` reaching this service from JSON**, so the rule is
applied once rather than per site. Six wire fields carry an absence that has a
name and take this treatment: `parentName` on
`RiskTreeDefinitionRequest.scala:43` and `:50` and on
`RiskTreeUpdateRequest.scala:64` and `:72`, and `seedVarId` on
`RiskTreeDefinitionRequest.scala:53` and `RiskTreeUpdateRequest.scala:75`. Both
`seedVarId` fields are additionally written `Option[Long] = None`, a defaulted
`Option`, which ADR-037 forbids outright. About twenty further `Option`
occurrences inside `RiskTreeRequests.scala` are these same two values carried
inward and disappear with them.

Two groups are identified and **not** in this plan's scope. The five `Option`
fields of `DistributionShapeRequest.scala:27-31` — `percentiles`, `quantiles`,
`terms`, `minLoss`, `maxLoss` — are a tagged union written as a flat record:
which of them must be present is decided by `distributionType`, and
`Distribution.create` enforces the combinations at runtime that a sealed set of
cases would make unrepresentable. Correcting it changes the request wire shape,
so it is governed by ADR-017 and ADR-038 and needs its own decision. The two
response fields `resolvedTerms` and `anchorCount` on
`DistributionPreviewResponse.scala:32-33` are the same question on the outgoing
side.

**Where this rule stops: `providedSeedVarIds` stays a `Map`.**
`ResolvedCreate` and `ResolvedUpdate` both carry
`providedSeedVarIds: Map[SafeName.SafeName, SeedVarId.SeedVarId]`, holding only
the leaves whose seed identifier the client supplied. That type does not change,
and the distinction is what keeps this decision from reaching it.

A `SeedVarIdChoice` on one leaf answers whether that leaf has an identifier.
Absence there means something specific — the server assigns one — and `Option`
does not say so, which is why the enum replaces it. The map answers which leaves
supplied one. A name that is not a key is not an encoded meaning but
non-membership, which a collection already expresses. Replacing the map with
something that names absence would require an entry per leaf saying it has none,
which is the `Option` this decision removes, copied into every row of a table
whose purpose is to list the rows that exist.

So the change reaches the line that builds the map and not the map itself. In
`RiskTreeUpdateRequest.scala`, `case (name, _, _, _, Some(seed))` becomes
`case (name, _, _, _, SeedVarIdChoice.Provided(seed))`, and the map it produces
is identical.

Worked through, with a create request whose three leaves are `Server outage`
carrying a client-supplied `seedVarId` of 42, `Flood`, and `Fire`. Resolution
produces `Map(SafeName("Server outage") -> SeedVarId(42))`, with the other two
absent. `SeedVarIdAssigner.assign` then receives that map together with every
leaf name and a high-water mark of 0, which is the value a tree with no prior
leaves starts from. It sorts the unassigned names so a rebuild of the same tree
allocates identically, and allocates above both the high-water mark and the
highest supplied identifier, returning
`Map("Server outage" -> 42, "Fire" -> 43, "Flood" -> 44)` and a new high-water
mark of 44.

From that point the map is total over the tree's leaves, and `buildNodes` reads
it with `Map.apply` at `RiskTreeServiceLive.scala:281`. That call is partial and
deliberately so: the response to a missing key is already chosen, stated at the
end of `buildNodes`, where `.orDie` carries the reason that a request resolved
and validated before that point cannot be missing a leaf, so an absence there is
a broken invariant rather than an outcome a caller can act on. This plan does
not change that.

---

### EWB-D-33 — the four submission checks return records carrying the subject — RULED 2026-10-19

**Ruled: `refinePortfolioDefs`, `refineLeafDefs`, `refineExistingPortfolios` and
`refineExistingLeaves` return records rather than tuples, and each record
carries the `ErrorSubject` that later checks use to name the node.**

The position of a node in a submitted collection is currently derived twice. The
submission check derives it to name its own field errors, discards it, and the
topology check then counts the sequence again with `zipWithIndex` to name a
parent error. Both are correct only because `collectAllWithIndex` returns its
results in submitted order and drops none, which nothing states and nothing
tests. A later change making it skip failed entries and continue would renumber
the survivors, and a parent error would name the wrong node with nothing
failing.

Carrying the subject forward removes the second derivation. It adds a component
to tuples already five and six wide, read by the resolvers as
`case (_, name, parent, _, _)`, so the tuples become records in the same step —
a seven-wide tuple read by position is a worse defect than the one being
removed.

```scala
/** A portfolio of the submitted body, refined, with the subject that names it
  * in any later error.
  *
  * {{{
  * // "portfolios": [ { "name": "IT", "parentName": null } ]
  * SubmittedPortfolio(ErrorSubject.nodeAtIndex(SubmittedCollection.Portfolios, 0), "IT", ParentRef.Root)
  * }}}
  */
final case class SubmittedPortfolio(
  subject: ErrorSubject,
  name: SafeName.SafeName,
  parent: ParentRef
)

final case class SubmittedLeaf(
  subject: ErrorSubject,
  name: SafeName.SafeName,
  parent: ParentRef,
  probability: OccurrenceProbability,
  distribution: Distribution,
  seedVarId: SeedVarIdChoice
)

final case class ExistingPortfolio(
  subject: ErrorSubject,
  id: SafeId.SafeId,
  name: SafeName.SafeName,
  parent: ParentRef
)

final case class ExistingLeaf(
  subject: ErrorSubject,
  id: SafeId.SafeId,
  name: SafeName.SafeName,
  parent: ParentRef,
  probability: OccurrenceProbability,
  distribution: Distribution
)
```

```scala
private[requests] def refinePortfolioDefs(
  portfolios: Seq[RiskPortfolioDefinitionRequest]
): Validation[ValidationError, Seq[SubmittedPortfolio]]

private[requests] def refineLeafDefs(
  leaves: Seq[RiskLeafDefinitionRequest]
): Validation[ValidationError, Seq[SubmittedLeaf]]

private[requests] def refineExistingPortfolios(
  portfolios: Seq[RiskPortfolioUpdateRequest]
): Validation[ValidationError, Seq[ExistingPortfolio]]

private[requests] def refineExistingLeaves(
  leaves: Seq[RiskLeafUpdateRequest]
): Validation[ValidationError, Seq[ExistingLeaf]]
```

Which subject each record carries, per `EWB-D-30`: a submitted record carries
`ErrorSubject.nodeAtIndex(collection, index)`, because a node being added has no
identifier; an existing record carries `ErrorSubject.ExistingNode(NodeId(id))`,
because by the point the record is built every field of that element has
refined and the identifier is therefore valid. The subject a field error used
while refining is still the positional one, since the identifier may be the
value that failed.

The topology call sites then read the subject instead of recomputing it:

```scala
// builds: SubmittedParent(NodeAtIndex(Portfolios, 1), ParentRef.Under("Typo"))
requirePortfolioParents(
  portfolios.map(p => SubmittedParent(p.subject, p.parent)),
  portfolioNames
)
```

One `.map` serves the create and the update path, because the difference between
a position and an identifier is already settled inside the record's subject.

---

### EWB-D-34 — where a distinctness rule lives, and what it is called — RULED 2026-10-19

**Ruled: the generic mechanism lives in `ValidationUtil`; a rule about a refined
domain type lives in that type's companion; a check whose values have no refined
type calls the generic mechanism directly. The companion-level methods are all
named `requireDistinct`.**

Only the merge scan is in that last case, and it reports a list of names rather
than a failure, so it calls `duplicateGroups`. Every rule ending in a
`ValidationError` goes through a companion — `EWB-D-36` refines the one
remaining caller that did not have a type to hang its rule on.

Five places in the main sources decide which values repeat, each with its own
grouping expression: `SafeName.duplicates`, the body of
`SeedVarId.requireDistinct`, `TreeBuilderLogic.preValidateTopology`, and two
inside `RiskTree.validateMitigations` — one over mitigation identifiers and one
over mitigation names. Four of the five end in a `ValidationError` and one ends
in a list of names that a merge preview reports.

`EWB-D-31` collapses the four. This decision says where the results live, so the
end state is not five rules in five idioms replaced by one mechanism in four
idioms.

| Where | What | Why there |
|---|---|---|
| `ValidationUtil` | `duplicateGroups`, `requireDistinctBy` | generic over the element and the key, no domain knowledge |
| `SafeName` companion | `requireDistinct(names, location)` | carries the name rule's error code and message |
| `SeedVarId` companion | `requireDistinct(holders, location)` | carries the seed rule's error code and message |
| `Mitigation` companion | `requireDistinctIds`, `requireDistinctNames` | the same, for the two mitigation rules |
| `TreeBuilderLogic` | calls `SafeName.requireDistinct` | `EWB-D-36` makes its names `SafeName`, so the name rule's own companion method is what it needs |

`EWB-D-31` writes the name-rule method as `requireDistinctNames`, and it is
`requireDistinct` instead. Read at a call site the pair was
`SafeName.requireDistinctNames(names, loc)` beside
`SeedVarId.requireDistinct(holders, loc)`, where the first repeats what its
companion already said. `Mitigation` keeps two names because it holds two rules
over one collection and `requireDistinct` alone could not say which.

The two parameter shapes stay different, and that is not an inconsistency. A
repeated name identifies its own holders, because the name is what the holders
are called, so listing the names is a complete report. A repeated seed
identifier identifies nothing, so the error carries the nodes holding it. The
same asymmetry is why `EWB-D-31` keeps the two error codes distinct.

---

### EWB-D-35 — one word for a parent reference, two types for the two trust levels — RULED 2026-10-19

**Ruled: the browser and the request path use the same case names for a node's
parent, and remain two types. `Under(parent)` on both sides.**

```scala
// modules/common — past the request boundary, so the payload has refined
enum ParentRef:
  case Root
  case Under(parent: SafeName.SafeName)

// modules/app — inside the form, so the payload is what the user is typing
enum ParentSelection:
  case Root
  case Under(parent: String)
  case Unset
```

`ParentSelection` already existed with the case spelled `Portfolio(name)`. The
rename to `Under(parent)` leaves one word for one relationship, so a reader
moving between the two sides has nothing to translate.

The payload type is what keeps them two types, not the naming. The form holds
text the user is still typing, and `FormMode`'s own note on `FieldSnapshot`
states why it must: the dirty check "has to fire on partially-typed or
currently-invalid input, which a refined type cannot represent". So the form's
case holds `String` and the request path's holds `SafeName`.

The third case is the other half. `Unset` means nothing has been chosen yet,
which a decoded request body cannot express — a body either names a parent or
does not. Carrying `Unset` inward would add a case that cannot occur to
`buildNodes`, `requireSingleRoot`, `requireLeafParents`, `requireNoCycles`,
`requireNonEmptyPortfolios` and the three checks in `TreeBuilderLogic`, each
then holding an arm nothing reaches. That is what `EWB-D-32` removed, in a new
shape.

Two alternatives were considered and are recorded as rejected. A single
three-case type used on both sides would put unrefined text in `ResolvedNode`
and give `refineParentName` nothing to produce, against ADR-001's
validate-once-at-the-boundary rule, as well as adding the unreachable case
above. A type generic in its payload would keep the refinement and avoid the
unreachable case, but the form's third state would then need either a nested
`Option` — the thing `EWB-D-32` removes — or a wrapper type, which is
`ParentSelection` again, so it ends with two types plus a type parameter on a
two-case type.

Two cases named `Portfolio` are **not** renamed: `FormTarget.Portfolio` and
`TreePreview`'s private `TreeNode.Portfolio`. Both say that a node is a
portfolio, which is the domain's own word for a node kind, and neither names a
parent.

---

### EWB-D-36 — the shared topology checker takes refined values — RULED 2026-10-19

**Ruled: `TreeBuilderLogic`'s two type aliases become the refined types.**

```scala
type Name = SafeName.SafeName
type Parent = ParentRef
```

`TreeBuilderLogic` is the pure topology validator shared by the browser and the
Java Virtual Machine tests: exactly one root, no cycles, every named parent
present, no name used twice. Its aliases were `String` and `Option[String]`,
which reads as a deliberate boundary for unrefined draft input and is not one.
All six production call sites hold already-refined drafts and widen them to
match, at `TreeBuilderState` lines 164, 180, 239, 273, 304 and 329, each in the
shape `p.name.value.toString -> p.parent.map(_.value.toString)`. The only
callers passing genuinely raw values are the fixtures in `TreeBuilderLogicSpec`.

The reason is that a parameter type is a claim about what a function must defend
against. Unrefined text claims the function may be handed anything, and this one
neither is nor would reject it — a malformed name passes its relationship checks
untouched, and the only thing preventing that today is the caller having refined
first, which is exactly the fact the signature denies. With the refined types
the signature and the behaviour agree.

The widening also cost something invisible: the same unwrapping repeated at six
sites, each an opportunity to unwrap differently, and each reading as plumbing
in review.

Consequences: the six call sites drop both conversions, and roughly twenty-five
fixture pairs in `TreeBuilderLogicSpec` written as `"Root" -> None` become
refined constructions. No assertion changes; `TreeBuilderStateSpec` already uses
the idiom for a refined name.

---

### EWB-D-37 — the leaf form composes its submission, and refinement happens once — RULED 2026-10-19

**Ruled: `RiskLeafFormState` gains a `toDraft` that accumulates every field
failure, replacing the tuple of options the view matches on today. The form's
own fields stay unrefined; everything past `toDraft` is refined.**

```scala
def toDraft: Validation[ValidationError, (SafeName.SafeName, ParentRef, OccurrenceProbability, Distribution)]
```

Today the leaf view matches three containers — a `Validation` for the
distribution shape, an `Option` for the probability, and a nested
`Option[Option[String]]` for the parent — and writes a literal "Parent is
required" into the parent field when the outer option is empty. The portfolio
form already composes the same work with `Validation.validateWith`. The nested
option exists because of that tuple: the outer layer is how the view learns the
parent is unset, and the inner is the parent-or-root shape `EWB-D-32` replaces.
So the nesting cannot be removed without deciding the submission shape.

After this, `parentDraft` and `refinedProbability` are deleted — the only
readers of both are the two submit handlers — and the parent's message comes
from `ParentSelection`, which is where the portfolio form's identical sentence
already lives.

**The display must not change, and one thing decides whether it does.** Every
failing field already has an inline slot, and `FormState.withSubmitErrors`
resolves a field's text as `reactive.orElse(submitted)`, so routing a
submit-time refusal into a slot whose reactive signal already carries the same
sentence changes nothing visible. The exception is the parent, the one field
with no reactive source. The leaf view has two routers that know different
fields — one for the five distribution fields and one for the name and the
parent — and sending every error through the first alone would leave the parent
error matching nothing and surfacing in the banner instead of under the select.
The two merge into one exhaustive match, which Phase 3 already rewrites. The
test that pins it: a parent left unset together with descending percentiles
shows two field errors and an empty banner.

**Why this and `EWB-D-36` are one change.** Each removes one of the two places
the refinement was undone or redone. This decision moves refinement to the form
boundary, so `TreeBuilderState.addLeaf` and `updateLeaf` take refined values and
stop refining; `EWB-D-36` lets those values reach the shared checker without
being widened back. Taken separately, either leaves a chain that refines early
and then discards it, or refines late and then carries it.

```
form Vars                    unrefined text        a Var holds keystrokes
  ↓  toDraft                 refines               the one boundary
addLeaf / addPortfolio       refined in            no refining of its own
LeafDraft / PortfolioDraft   refined fields
  ↓
preValidateTopology          refined in            no widening
```

Two helpers lose the callers that needed them. `TreeBuilderState`'s
`validateParentName` is called only from the two leaf methods and goes
entirely; `validateName` loses the same two call sites and stays for the two
that refine the tree's own name.

Both decisions also converge on one subject. The form's own refusals now come
from `toDraft`, and `EWB-D-31`'s consolidation already has `TreeBuilderLogic`
reporting at `ErrorSubject.DraftNode`, so every error the form can produce names
that subject and differs only in its field — which is what lets the two routers
merge. They cannot collide: a malformed name fails `toDraft` and the topology
check is never reached, while a well-formed but duplicated name passes
`toDraft` and only the topology error exists.

---

### EWB-D-38 — `ValidationError.message` becomes a typed case, as Phase 3b — RULED 2026-10-19

**Ruled: the free-text `message` is replaced by `FieldProblem`, a discriminated
enum whose cases carry the values their sentence names. It lands as Phase 3b,
after Phase 3 is green, and no work on it starts before then.**

`ValidationError` is the one internal error type that is also a wire type. Phase
3 closes two of its three components: the location becomes `ErrorLocation` and
the classification is already `ValidationErrorCode`. The third stays free text
generated by the server and displayed verbatim, so the server decides the
wording for an interface it cannot see, and the browser cannot shorten,
translate, or reformat a value inside it.

Two measurements decide the shape. Of the 35 declared `ValidationErrorCode`
cases, `ValidationError` uses 20; the other 15 serve the merge, query, access
and concurrency errors. And the usage is coarse: `CONSTRAINT_VIOLATION` appears
at 30 construction sites, `REQUIRED_FIELD` at 18, `INVALID_RANGE` at 15. Thirty
different refusals share one code, which is why the code cannot be rendered from
and the sentence has to carry the meaning.

The pattern is the one §3.5 already applies to the query half of a content
rejection. `RejectionDetail` has two cases, `Fields` and `Query`, and the query
one carries `QueryProblem` — a discriminated enum that classifies and carries
its values together, with no separate code beside it, which is what lets
`Unparseable` hold a character offset. `FieldProblem` is that type's sibling for
the field half.

```scala
/** Why one field was refused, with the values its sentence names. The sibling of
  * `QueryProblem`: the query half of a content rejection carries its structure in
  * one discriminated enum, and this is the field half.
  */
@jsonDiscriminator("kind")
enum FieldProblem:
  case Required
  case TooLong(limit: NonNegativeInt)
  case InvalidCharacters
  case DuplicateNodeName(name: SafeName.SafeName, occurrences: NonNegativeInt)
  case SeedVarIdInUse(seedVarId: SeedVarId.SeedVarId, holders: List[SafeName.SafeName])
  case SeedVarIdImmutable
  case ParentNotFound(parent: SafeName.SafeName)
  case ParentIsLeaf(parent: SafeName.SafeName)
  case PortfolioHasNoChildren(portfolios: List[SafeName.SafeName])

object FieldProblem:
  given codec: JsonCodec[FieldProblem] = DeriveJsonCodec.gen[FieldProblem]

  /** The coarse, filterable classification, derived from the case rather than
    * stored beside it — the same relation `WireError` has to its status. */
  def code(problem: FieldProblem): ValidationErrorCode = problem match
    case Required                  => ValidationErrorCode.REQUIRED_FIELD
    case TooLong(_)                => ValidationErrorCode.INVALID_LENGTH
    case InvalidCharacters         => ValidationErrorCode.INVALID_PATTERN
    case DuplicateNodeName(_, _)   => ValidationErrorCode.AMBIGUOUS_REFERENCE
    case SeedVarIdInUse(_, _)      => ValidationErrorCode.DUPLICATE_VALUE
    case SeedVarIdImmutable        => ValidationErrorCode.CONSTRAINT_VIOLATION
    case ParentNotFound(_)         => ValidationErrorCode.MISSING_REFERENCE
    case ParentIsLeaf(_)           => ValidationErrorCode.INVALID_NODE_TYPE
    case PortfolioHasNoChildren(_) => ValidationErrorCode.EMPTY_COLLECTION
```

```scala
case class ValidationError(
  location: ErrorLocation,
  problem: FieldProblem
)
```

`ValidationMessages` stops being a list of strings and becomes one total
function, `render(problem: FieldProblem): String`, which both the browser and a
server log line call.

**Where it is declared, and why that opens no new hierarchy.**
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`
already holds every closed set a `ValidationError` is made of — `ErrorSubject`,
`FieldName`, `SubmittedCollection`, `ErrorLocation` and `RequestHeaderName` —
so this is the third and last of them and the file needs no sibling. Not
`http/errors/` beside `QueryProblem`, even though it is that type's counterpart:
`QueryProblem` is wire-only, while `ValidationError` is internal and wire at
once, so its components live on the domain side and cross-compile with it. The
resulting structure adds no level; it fills the one slot still carrying prose.

**`ValidationErrorCode` stops being stored and becomes derived.** It keeps all
35 cases, because 15 serve the other error types, and the 20 that
`ValidationError` used are produced by `FieldProblem.code`. That removes the
30-way `CONSTRAINT_VIOLATION`: the case carries the meaning and the code stays
the coarse filter its own scaladoc describes.

**Splitting `DUPLICATE_VALUE` into per-subject codes is ruled against.**
`DUPLICATE_NODE_NAME` and `DUPLICATE_SEED_VAR_ID` beside
`FieldProblem.DuplicateNodeName` and `FieldProblem.SeedVarIdInUse` would be a
second distinction of the same thing, and the codes would be removed again by
this decision. So no new codes are added in Phase 3.

**Why it is a separate phase rather than part of Phase 3.** It is on theme and
is the direct next step, so G8 keeps it inside this plan rather than routing it
elsewhere. It changes the wire shape of a validation error, so ADR-017 and
ADR-038 both bear on it, and it reaches 152 `ValidationError` construction sites
and 65 message templates — the size of Phase 3 again. Phase 3 changes one
argument at each of those sites; this changes the argument's shape at all of
them, so interleaving the two would leave neither verifiable until both were
done.

---

### EWB-D-39 — where a cross-field rule's choice of field lives — OPEN

**What has to be decided.** A rule over two fields produces one refusal and has
to name one field for it. The minimum-and-maximum-loss rule does this today:

```scala
Validation.fail(ValidationError(at(FieldName.MinLoss), ValidationErrorCode.INVALID_LOGNORMAL_PARAMS, "minLoss must be < maxLoss"))
```

`Distribution.scala:100` — so "minLoss must be < maxLoss" is displayed on the
minimum control. That choice is not derivable from either input's own location,
so it is a third declaration beside the two fields' own rules, and there are two
places it can live. One per cross-field rule, so the decision is about where the
class of them lives rather than about this one.

**Option A — in the rule, in the shared module.** The rule stamps
`FieldName.MinLoss`, as it does now, and the display mapping follows whatever it
stamped.

*What the user sees.* You enter a minimum of 900 and a maximum of 100 and press
save. The browser's own check reports on the minimum field. You then defeat the
browser's check — an older tab, a replayed request — and the server's refusal
also reports on the minimum field, because it ran the same rule. The two can
never disagree.

*Cost.* Which control a message appears under is decided in `modules/common`,
beside the refinement, in a module that renders nothing and has no knowledge of
the form's layout. Moving the message to the maximum field for a usability
reason is an edit to shared validation code.

**Option B — in the display mapping, in the browser.** The rule names the
subject of the rule — the pair, so `FieldName.MinLoss` or a case standing for
the loss range — and the browser's location-to-destination function decides which
control shows it.

*What the user sees.* The same two cases as above produce the same display,
because both refusals carry the same location and both pass through the same
mapping. The difference appears when the decision changes: moving the message to
the maximum field is one arm of one browser function, and the server is not
touched or redeployed.

*Cost.* The browser can relocate a refusal the server has an opinion about,
which is a divergence that compiles. It also makes the mapping do two jobs:
"which control renders this field" and "which field should this rule's message
appear on".

**Trade-off only the user can weigh.** Option A keeps one authority for where a
refusal belongs and pays by putting a presentation choice in shared validation
code. Option B keeps presentation choices in the browser and pays by letting the
two sides' notion of where a message belongs drift.

**How many choices this actually governs.** `Distribution.create` holds nine
refusals that read more than one field: `Distribution.scala` lines 83, 85, 89,
91, 92, 93, 94, 100 and 101. Seven of them name the field the rule is about and
have no arbitrary choice to make — "Expert mode requires quantiles" names
`Quantiles` because the quantiles are what is absent. Two are genuinely
arbitrary: which of the two loss fields to blame when the minimum is not below
the maximum (line 100 blames the minimum), and which to blame on a
percentile-and-quantile length mismatch (line 91 blames neither and names
`DistributionType`).

So the decision governs two sites today, not nine. A third fact bears on it:
five of the nine name `FieldName.DistributionType`, and `inputFor` has no arm
for it, so they reach the banner today through the `case _ => None` fallback.
Under `EWB-P-3` statement 6 they need a destination named on purpose, and
form-level is the likely one rather than the application banner.

**My recommendation: Option A.** The condition it rests on is the count above —
two arbitrary choices, so the presentation decision sitting in `modules/common`
is two lines rather than a pattern. It also keeps the two sides unable to
disagree, which is the property `EWB-P-3` exists for. If cross-field rules
multiply, Option B becomes the better answer and the migration is one arm per
rule.

**Decision needed:** A or B.

---

## 11. ADR alignment

| ADR | What this plan touches | Status |
|---|---|---|
| ADR-010 (error handling strategy) | the hierarchy, the HTTP mapping, the code smells | **Compliant, and it closes a violation.** The "String Matching on Errors" code smell instructs pattern matching on types; `isWorkspaceSentinel` matches a string prefix on the browser side, and this plan deletes it. **The ADR is amended in two places:** §5 "Pure HTTP Error Mapping" is rewritten to describe a one-way projection onto `WireError` instead of `encode` and `decode`, and §1's hierarchy listing gains `TreeLoadFailure` and the `SimError` to `DomainError` rename. |
| ADR-035 (error leakage prevention) | the boundary, the encoders, the failure's own message member | **Compliant, and strengthened.** **Its naming amendment is made.** ADR-035 named the member as `getMessage` in its leakage-vector list, in its §3 heading, in §3's first sentence and in its ADR-036 cross-reference. `EWB-D-15` removes `extends Throwable`, so `AppError` has no `getMessage` and the member is `logMessage` (§6.1). All four now read `logMessage`, and §3's opening states why: the name carries the destination, and an `AppError` is plain data with no exception apparatus behind its text. The discipline the section states is unchanged and still binds. §2 "Typed Error Channel" quotes the `mapErrorOut` line and argues that `ErrorResponse` is the only type that can reach the wire; the argument holds more strongly for `WireError`, which has no field for a confidential value rather than relying on a builder dropping it. **§2 is amended** to quote the `oneOf` declaration and to state that the single projection is the auditable list of what is dropped. §1's exhaustiveness guarantee improves: the match is on 31 leaf types, not on 4 sub-traits. **§3 is also amended, and the earlier claim that it was unchanged was wrong.** Its second bullet reads "Resource-neutral errors collapse to an opaque status (a missing resource is a plain 404 regardless of variant)" — that constrains the status, not only the message, and `EWB-D-4` answers `410 Gone` for expiry. The bullet is narrowed to not-found and not-yours, which is the pair A13 was narrowed to on the same grounds; the `getMessage` sentence it opens with is unchanged and still binds. **Phase 2b closes two paths that bypassed §2 entirely:** a defect reached the wire as `cause.squash.getMessage` in a plain-text body, and a request the codecs rejected reached it as Tapir's own plain-text `400`. After `EWB-D-21` and `EWB-D-23` every response this service sends is a declared output, which is what §2's exhaustive-encoding rule asks for. |
| ADR-036 (confidential internal identifiers) | `TreeNotInWorkspace`, `WorkspaceExpired`, the log line | Compliant. No wire case declares a `WorkspaceId`, so the output half holds structurally rather than by discipline. The input half is untouched. §6.2's log annotation is explicitly permitted: a confidential identifier may appear in a server log. |
| ADR-022 (secret handling) | the three workspace errors | Compliant and unchanged. None carries a credential, so R1's `final class` rule has nothing to be violated at the container. |
| ADR-001 and ADR-017 (API design) | `BaseEndpoint`, the DTO convention, and in Phase 3a the refinement entry points | Compliant, and it removes an exception. The error output now follows the response-DTO convention the architecture document already declares complete. Phase 3a strengthens ADR-001's "smart constructors are the only valid construction path": making the two-argument refinements private to `ValidationUtil` means a refined value cannot be built without going through the one rule declared for its field, so the single entry point is enforced by visibility rather than by convention. No amendment is needed — the record already states the rule this makes true. |
| ADR-019 (frontend component architecture) | `GlobalError`, `ErrorBanner`, and in Phase 3a the form states and the per-form mapping | Compliant, and Phase 3a closes a smell — **with one staleness in the record to rule on.** Decision 7's two tiers are unchanged, and Phase 3a's reading of them is what decides §15.5: a control and the form's own line are both the inline tier the owning view renders, so both are cases of the form's display enum, and the banner is the unowned tier, so it is not. Routing before rendering is what makes decision 7's "an error belongs to exactly one tier" true, because today a refusal about a field the open form owns is rendered in the banner by `ErrorBanner.scala:42-43` regardless — the mirror of the record's own "Duplicating a Per-View Error into the Global Banner" smell. The classifier's input type changes and a `NotFound` display case is added. **The staleness:** the record's "Validation via `.now()` on Submit" smell gives a GOOD example that calls `ValidationUtil.refineName(v)` with one argument. Phase 3 already made that two arguments and Phase 3a makes it one again, of a different type — `ValidationUtil.refineName(input: Located[String])` — so the example has been wrong since Phase 3 landed and Phase 3a moves it again. The record is an accepted ADR, so this plan does not edit it: the amendment is presented and waits. Separately, that smell's heading reads as banning a snapshot read on submit while its example is an imperative check; the located submit accessor of §15.4 reads a snapshot to compose a submission and performs no check, which is what `toDraft` already does at `RiskLeafFormState.scala:428-431`. Two further points of the record bear on §15.5a and both are satisfied: "Signals Down, Callbacks Up" forbids mutating a `Var` you do not own, which is what `routeErrors` does to the view's `submitError` and what the inversion removes; and the "Self-Correcting Reactive Var" smell is why the form's own derived checks are not written into `serverErrors` but combined with it at the point of reading. |
| ADR-028 (vague quantifier query pane) | `QueryProblem` | Compliant. The engine's rendered messages still reach the pane. `FolModelValidationFailure` and `FolEvaluationFailure` stop reaching it, which matches the ADR's own description of them as wiring faults rather than query errors. |
| ADR-039 (trust-boundary ingress validation) | the Irmin and Postgres borders, the ingress rejection type | **Currently violated, fixed in Phase 7.** The record states that a value entering process memory from outside is refined at the border and that an ingress rejection is a typed failure distinct from a transport fault. Today `handleIrmin` collapses seven typed Irmin cases into `RepositoryFailure(String)`, the repository's four stored-value decoders interpolate an accumulated `ValidationError` list into a sentence, and `WorkspaceStorePostgres` recovers a branch from that sentence with `reason.contains`. Phase 7 closes all three and records the one exemption the record requires, on the branch listing. No amendment to the record is needed: it was written against these findings. |
| ADR-002 (observability) | the correlation identifier, the failure log line | **Currently violated, fixed here, and the record is amended.** A correlation identifier that never reaches a client cannot correlate anything; `EWB-D-8` decides the fix. The resolve-failure line annotated a redacted constant and so identified no workspace; `EWB-D-26` replaces it with one line per failed request, written where the trace is in scope, so every line carries the identifier the response carries. **The amendment:** the record's code-smell section demands differentiated log levels, which `EWB-D-26` satisfies by origin, but its one example put a validation failure at `logWarning`. That example now shows a caller-caused failure at `logInfo`. The section's decision is unchanged and binds more concretely than before. |

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

The defect catch-all's own interceptor (`EWB-D-21` as revised, Phase 2b step 1).
One path, and it is the only one in this plan that the approved inventory does not
already carry, because the earlier ruling put this code inside a file the
inventory names:

- `new: modules/server/src/main/scala/com/risquanter/register/http/DefectCatchAllInterceptor.scala`

Its spec, `DefectCatchAllInterceptorSpec.scala`, is reached by the standing
authorization for a module's own tests, as the fourteen telemetry suites above
are. Three further new specs are reached the same way:
`DecodeFailureStatusSurfaceSpec.scala` in `server`, which reads the endpoint list
from `HttpApi.endpointsZIO` and fails if any endpoint declares an input whose
rejection answers other than `400`; `IronTapirCodecsSpec.scala` in `common`; and
`ErrorMessagesSpec.scala` in `app`.

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
- `.env` and `.env.irmin` (the `APP_VERSION` mirror each bump requires, per §13)
- `docs/dev/ARCHITECTURE.md`
- `docs/dev/decision-records/ADR-010.md`
- `docs/dev/decision-records/ADR-035-error-leakage-prevention.md`
- `docs/dev/decision-records/ADR-037-in-code-constants.md` (written 2026-10-11; the phase amends the architecture document to match it)
- `.claude/skills/code-quality-review/SKILL.md`
- `.claude/skills/adr-constraints/SKILL.md` (the collision row for ADR-035 × ADR-036 × ADR-022)
- `docs/dev/plans/PLAN-RISKTRANSFORM.md` (repoint its four references at lines 9677, 9844, 9942, 9955)
- `docs/dev/plans/IMPLEMENTATION-PLAN.md` (repoint its three `TES-D-9` references)
- `docs/dev/decision-records/ADR-002.md` (the failure log level, amended by `EWB-D-26`)
- `docs/dev/decision-records/ADR-019-frontend-component-architecture.md`
- `docs/dev/decision-records/ADR-036-confidential-internal-identifiers.md` (the secret-versus-identifier split)
- `docs/user/TERMINOLOGY.md` (definitions for both terms and the trust boundaries between them)
- `docs/dev/plans/AUTHORIZATION-PLAN.md`

There is no `.github/` directory in this repository. The skills live only under
`.claude/skills/`, so the list above names one copy of each and not two.

The two global instruction lines that `EWB-D-10` produces are written. The user's
`~/.claude/CLAUDE.md` carries the boyscout section that names them, and the global
`boyscout` skill states each one with its test and its reason, as obligations 3
and 5. Neither is this plan's work any more.

**Added by Phase 7 — the storage trust boundary**

Five paths the approved inventory does not yet carry. All five exist.

- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminCommit.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala`

Three further integration suites read a commit hash as a `String` and are
reached if step 5's retyping touches them, which the compiler decides:
`IrminMergeSemanticsSpec.scala`, `IrminRevertSemanticsSpec.scala` and
`PinnedReadAuthorizationItSpec.scala`, all under
`modules/server-it/src/test/scala/`. They are named here rather than discovered
mid-phase, because `server-it` has no `src/main` and so carries no standing
authorization for its own tests.

Every other file Phase 7 edits is already named: `AppError.scala`,
`WireError.scala`, `RiskNode.scala`, `RiskTree.scala`, `Mitigation.scala`,
`RiskTreeRepositoryIrmin.scala`, `IrminClientLive.scala` and
`WorkspaceStorePostgres.scala`. `WorkspaceStorePostgresSpec.scala` is reached by
the standing authorization for a module's own tests, since `server`'s `src/main`
is listed.

**Added by Phase 3a — the located value and the routing destination**

Two paths the approved inventory does not yet carry. Both exist.

- `modules/app/src/main/scala/app/state/FormState.scala`
- `modules/server/src/main/scala/com/risquanter/register/configs/SimulationConfig.scala`

`FormState.scala` is where the type parameter is renamed to `Field`, where
`submitFieldErrors` and its three methods are deleted, and where the form's
subject is declared, per §15.4 and §15.5a; it is the shared form infrastructure
both form states extend, and the inventory carries both of those and not it.
`FormSubmitUtil.scala` is deleted whole and is already listed. `SimulationConfig.scala` holds the one
refinement call site among the configuration files that the inventory does not
already name, and it is reached by step 3's `Located.atConfig` conversion.

Every other file Phase 3a edits is already named: `ErrorLocation.scala`,
`ValidationUtil.scala`, `OpaqueTypes.scala`, `IronTapirCodecs.scala`,
`RiskNode.scala`, `RiskTreeRequests.scala`, `Distribution.scala`,
`Mitigation.scala`, `RiskLeafTransform.scala`, `ResultTransformSpec.scala`,
`Provenance.scala`, `SpiceDbConfig.scala`, `IrminConfig.scala`,
`TelemetryConfig.scala`, `Simulator.scala`, `WorkspaceTreeController.scala`,
`RiskLeafFormState.scala`, `PortfolioFormState.scala`, `TreeBuilderState.scala`,
`ParentSelection.scala`, `RiskLeafFormView.scala`, `PortfolioFormView.scala`,
`FormSubmitUtil.scala`, `FormInputs.scala`, `ErrorBanner.scala` and
`GlobalError.scala`. `ValidationUtilSpec.scala` and
`FormErrorRoutingSpec.scala` are reached by the standing authorization for a
module's own tests, since both `common`'s and `app`'s `src/main` are listed.
`FormErrorRoutingSpec` is the suite that already pins both forms' `inputFor`
mappings, so it is where the total-mapping assertions go rather than a new file.

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
5. **Both refusal paths produce identical bytes.** `AccessDenied(reason)` and
   `AuthForbidden(...)` must serialise to the same JSON and the same status, so
   a denial this service decided and one the authorization service decided are
   indistinguishable (ADR-035 §2). A second test pins the other half:
   `AuthServiceUnavailable(...)` and `IrminUnavailable(...)` must also serialise
   identically, so the answer to an outage does not disclose which dependency
   failed.
6. **A workspace absence and a tree-not-in-workspace produce identical bytes.**
   The same property, for the existence oracle.
7. **`fromAppError` honours the scope it is given.** `ValidationFailed` with
   `TreeScope.InTree(tree)` produces `ValidationRejected(tree, Fields(errors))`
   and with `TreeScope.NoTree` produces `RequestRejected(errors)`; a query
   failure with `NoTree` produces `InternalFault`. This pins the branch
   `EWB-D-28` introduced, which the compiler cannot check.
8. **Each declared header maps to its own `RequestHeaderName` case, and a
   header Tapir pins maps to `Request`.** One assertion per case over
   `fieldName`, plus one for `EndpointIO.FixedHeader`. The enum's own
   exhaustiveness does not reach this, because the mapping is from Tapir's
   input to the case and a wrong pairing compiles. The same test asserts each
   case's `wireName` against the literal the endpoint declares, so a rename of
   a case cannot move the header spelling (ADR-037 §2).
9. **The browser routes every location it can receive.** The two form mappings
   lose their `else None` fallback and match the location's cases, so the
   compiler is what proves the routing total. The test that remains is the one
   the compiler cannot write: for each form, that a location belonging to a
   different form is not routed to one of its controls. The substring matching
   in `fieldForShapeError` and `fieldForDraftError` goes, and with it the
   silent fall-through that showed a field error as a banner.

**Per-route statuses, in the controller specs.** `EWB-D-28`'s residual weakness is
that a wrong `TreeScope.NoTree` at a call site compiles, so each tree-scoped route
gets an assertion that a content failure answers `422`, and each request-level
route that a rejection answers `400`. That is what converts a silent downgrade
into a failing test.

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

**Phase 7, the storage trust boundary.** Four properties, each pinning one step.

In `RiskTreeRepositoryIrminSpec`, a read whose Irmin call fails with
`IrminUnavailable` fails with `IrminUnavailable` and not with
`RepositoryFailure`, which is step 2's whole content and is the property
`handleIrmin` silently removed. In the same suite, a stored node whose JSON
refines to nothing fails with `IngressRejected` carrying more than one
`ValidationError` when more than one field is bad — the accumulation is the
point, and a single-error assertion would pass against a `.flatMap` rewrite.

In `WorkspaceStorePostgresSpec`, inserting two workspaces with the same
`seedEntityId` yields the duplicate rejection, and the suite drives the real
database rather than a stubbed error, because the behaviour under test is the
driver's `SQLState` reaching `classify`.

In `WireErrorSpec`, `IngressRejected` projects to `InternalFault`, added to the
existing exhaustive case-to-wire assertion rather than as a separate test.

**Phase 3a, the located value.** The compiler produces the migration list, as in
Phase 1 and for the same reason. Changing the twenty-six refinements in
`ValidationUtil` to take `Located[A]` turns every caller into a type error naming
its own site, so step 2 of §15.8 starts by compiling and reading the list. No
site is found by grep and none is assembled by hand.

Four properties, each pinning one thing the design claims.

**One behavioural test per field, in `ValidationUtilSpec`.** For each located
accessor, refine a value the field must reject and assert the resulting
`ValidationError.location` equals `ErrorLocation.Field(subject, <the expected
field>)` for a subject supplied by the test. This is the only thing that catches
a wrong field name in an accessor, because nothing relates a `FieldName` case to
the value beside it. Asserting the accessor's own `FieldName` against itself
would prove nothing and is not the test; the assertion runs a real refusal
through the same path production uses.

**The location survives the transformation.** `Located.map` preserves the
location: a probability read as text, parsed and rescaled, and then refused,
reports at the field the text was read from. This is what makes the pair worth
being a type rather than two arguments, and it fails if any step rebuilds the
location instead of carrying it.

**The browser's own check and the server's check agree, in
`FormErrorRoutingSpec`.** One probability out of range, refined once through the
browser's located accessor and once through the server's request projection,
produces two `ValidationError`s whose locations differ only in their subject and
name the same `FieldName`. That is `EWB-P-3` statement 1 as an assertion, and it
fails if either side's accessor moves without the other.

**Every location has a named destination and nothing is discarded.** For every
`FieldName` case and both non-field `ErrorLocation` cases, each form's mapping
yields either a case of that form's display enum or the absent result, and no
arm reaches the absent result by falling through a wildcard. Separately, over
`errorsFor` and `unclaimed`: a refusal carrying three errors that all belong to
the form's own line yields three, two errors naming the same field yield two,
and the three together partition — every error is in exactly one reader's
selection. Those are the assertions that fail against today's
`unrouted.headOption` at `FormSubmitUtil.scala:35` and today's
`Map[F, String]` at `FormState.scala:31`, both of which this phase deletes.

**A server refusal reaches the control, not the banner.** The test posts a leaf
whose probability the browser's check would have caught, takes the server's
`ValidationRejected` through `GlobalError` and the form's mapping, and asserts
the probability control holds the message and the banner is empty. This is the
consumer §15.1 shows was never built, so it is the regression test for the whole
phase.

**Every phase.** All four tiers green: `commonJVM/test`, `server/test`,
`app/test`, `serverIt/test`. Leaked Docker containers, networks and volumes
matching `register_it_` are cleared before the integration tier runs, not only
after a failure.

**Version.** A PATCH bump per landed phase, mirrored from `build.sbt` into `.env`
and `.env.irmin`.

### The last step — the browser module's wildcard Laminar import

This runs after every other phase is green, because it touches more files than
the rest of the plan put together and touching them earlier would make each
earlier phase harder to read.

**What is there now.** Fifty-one files in `modules/app/src/main/scala` open with
`import com.raquo.laminar.api.L.{*, given}`. Laminar is the browser user
interface library this module is built on; `L` is the object holding its whole
public surface, the `*` imports every name in it, and the `given` imports its
implicit instances. The compiler reports the `given` selector as unused in
eleven of those files, all of which this plan touched.

**What was measured, and when.** On 2026-10-20 the selector was deleted from
`app/state/AnalyzeQueryState.scala` and `app/views/ErrorBanner.scala`, and
`sbt 'commonJS/clean; commonJS/compile; app/clean; app/Test/compile'` was run.
The module compiled and both warnings disappeared, so in those two files the
selector is genuinely unused. The measurement was then reverted. It settles
those two files and nothing else: another file may use a Laminar given, and
only its own compile says so.

**The step.** Replace the wildcard in every one of the fifty-one files with the
names that file actually uses — `div`, `span`, `onClick`, `child.text`,
`Signal`, and so on — keeping `given` only where removing it fails to compile.
The import is then specific rather than a wildcard, which is what ADR-011 asks
for and what stops a wildcard widening implicit scope in ways no reader can
see.

**Verification.** `sbt 'commonJS/clean; commonJS/compile; app/clean;
app/Test/compile'` compiles with no unused-symbol warning naming any file in
`modules/app/src/main`, and `sbt app/test` is green. The clean is required: an
incremental compile reports nothing for a module it did not rebuild, which
reads as zero warnings.

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

---

## 15. The located value and the single rooting — Phase 3a's design

`EWB-P-3` states the seven properties this section delivers. What follows is the
mechanism, the exact signatures, the measured scope, and the steps in order.

### 15.1 What is wrong today, in one place

Phase 3 landed a typed location and left its supply at the call site. Three
consequences, each verifiable in the code as it stands.

**The pairing of a field to a rule is asserted six times for one field.**
`ValidationUtil.refineOccurrenceProbability` is called from six production sites
across five files. Four of them write `FieldName.Probability` by hand —
`RiskNode.scala:178`, `RiskTreeRequests.scala:171`, `RiskTreeRequests.scala:215`
and, through the `at` helper, `RiskLeafFormState.scala:135` and
`RiskLeafFormState.scala:441`. The sixth, `IronTapirCodecs.scala:275`, passes
`codecLocation` instead, which is correct for a codec and is the reason a single
rule cannot simply hard-code one location. Nothing relates the function to the
field name, so any of those six could name any of `FieldName`'s forty-eight
cases and compile.

**The browser roots the same field twice, by two unrelated mechanisms.** The
live check computes a location and discards it:

```scala
  private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
    ValidationUtil.refineName(v, at(FieldName.Name)) match
      case Right(_) => None
      case Left(errors) => Some(errors.head.message)
  }
```

`RiskLeafFormState.scala:119-123` keeps only `errors.head.message`. The error
reaches the right input because `RiskLeafFormView.scala:276` wires that signal
to that input by hand. The submit check keeps the location and dispatches on it
through `inputFor`. So "the name input shows name errors" is stated twice, in
two files, by two mechanisms, and the two can diverge silently.

**Refusals are dropped on two paths, and in two different ways.** On the submit
path the mapping is consulted and then most of its answer is thrown away:

```scala
    submitError.set(unrouted.headOption.map(_.message))
```

`FormSubmitUtil.scala:35` shows the first refusal the mapping did not place and
discards the rest. On the global path the mapping is not consulted at all:

```scala
    case GlobalError.ValidationFailed(errors) =>
      Presentation("⚠", errors.map(_.message).mkString("; "), None, ErrorStyling)
```

`ErrorBanner.scala:42-43` joins every message into one sentence and drops every
location.

### 15.2 `Located[A]` — the value and its location as one thing

Declared in `ErrorLocation.scala`, beside the other closed sets a
`ValidationError` is made of, which is where `EWB-D-38` also puts `FieldProblem`.

`ErrorLocation` is an address: `Field(DraftNode, Probability)` names the
probability field of the node being edited. `Located[A]` is a value bound to
that address. The codebase has the first and not the second, which is why the
address has to be supplied a second time at whichever site consumes the value.

**Why the pair has to exist rather than the location being an argument.** The
value is transformed between being read and being refined. A probability is
typed as text on a 0 to 100 scale, parsed to a `Double`, divided by 100, and
only then refined against the closed interval 0 to 1. Three steps, and the
address has to survive all three without being restated. Carrying it through is
what `map` does, and that makes `Located` a Functor, which
`.claude/rules/scala-algebraic-design.md` requires to declare `map` before any
specialised transform is written.

```scala
/** A value together with the location a refusal about it names.
  *
  * The value is transformed between being read and being refined — a form
  * field's text is parsed and rescaled before the refinement sees it — and the
  * location has to survive that without being named again. `map` is what
  * carries it, so the field is named once, where the value is read.
  *
  * {{{
  * probabilityLocated            // Located("90", Field(DraftNode, Probability))
  *   .map(_.toDouble / 100.0)    // Located(0.9,  Field(DraftNode, Probability))
  * }}}
  */
case class Located[+A](value: A, location: ErrorLocation):
  def map[B](f: A => B): Located[B] = Located(f(value), location)

  /** A refusal about this value, at its own location. The location is not a
    * parameter, so a refusal about a located value cannot name a different
    * place than the value came from. */
  def reject(code: ValidationErrorCode, message: String): ValidationError =
    ValidationError(location, code, message)

object Located:
  /** A value a Tapir codec is decoding. A codec cannot name the input it
    * decodes — Tapir reports that from the endpoint definition — so the
    * location is the request as a whole. */
  def atCodec[A](value: A): Located[A] = Located(value, ErrorLocation.Whole)

  /** A configuration value, which no request and no control is behind. */
  def atConfig[A](value: A): Located[A] = Located(value, ErrorLocation.Whole)
```

`reject` is what the browser's parse failures need. Today they produce a bare
string with no location at all — `RiskLeafFormState.scala:131-132` yields
`Some(ValidationMessages.probabilityRequired)` and
`Some(ValidationMessages.probabilityNotANumber)`, neither of which is a
`ValidationError` and neither of which can be routed. Through `reject` they
become refusals at the field the value came from.

`atCodec` and `atConfig` replace four existing constants that say the same thing
in four places: `codecLocation` in `IronTapirCodecs.scala:47`, and
`configLocation` in `SpiceDbConfig.scala:97`, `IrminConfig.scala:39` and
`TelemetryConfig.scala:61`.

### 15.3 The refinements take the pair

No new type holds the rule. Each refinement in `ValidationUtil` changes its two
parameters for one:

```scala
  def refineOccurrenceProbability(input: Located[Double]): Either[List[ValidationError], OccurrenceProbability]

  def refineName(input: Located[String]): Either[List[ValidationError], SafeName.SafeName]
```

The body is otherwise unchanged: where it reads `location` today it reads
`input.location`, and where it reads `value` it reads `input.value`. There are
twenty-six such methods in `ValidationUtil`.

**What this closes.** The location stops being a parameter a caller supplies, so
the six sites §15.1 lists stop naming a field. The site that could pair a
probability with the name field is gone, because the call takes one argument and
that argument was built where the value was read.

**What it does not close.** The accessor that builds the pair still names the
field, and that naming is a free assertion — it is the one declaration per side
that `EWB-P-3` statement 3 describes, and it is pinned by a test per field
rather than by the compiler, per §13.

No separate rule type is introduced. A type holding a `FieldName` beside a
refinement adds a layer with nothing behind it: the pairing it would declare is
the same assertion the accessor already makes, so the type moves the assertion
rather than removing it. `code-quality-review`'s co-location table is explicit
about where a refining method lives — "Validation utility, or a method refining
a value | `ValidationUtil`, alongside the methods already there."

### 15.4 The browser's located accessors

No new type here either. Each form state keeps its `Var`, names its field once,
and gains two readers that refer to that name:

```scala
  /** The field a refusal about the probability input names. Declared once; the
    * two readers below refer to it and no other site on this side names it. */
  private val probabilityField: FieldName = FieldName.Probability

  /** The probability input's live value, located. The only reader the checks
    * use; `probabilityVar` is read raw by nothing but the input binding. */
  private def probabilityLocated: Signal[Located[String]] =
    probabilityVar.signal.combineWith(subject).map((v, s) =>
      Located(v, ErrorLocation.Field(s, probabilityField)))

  /** The probability input's current value, located, for the submit read. */
  private def probabilityLocatedNow: Located[String] =
    Located(probabilityVar.now(), ErrorLocation.Field(subjectVar.now(), probabilityField))
```

The named `val` is what makes "once per side" literal rather than approximate:
without it the two readers each name the field, which is two assertions per
field and the thing `EWB-P-3` statement 3 forbids. `FieldName.Probability`
appears in that one declaration and nowhere else on the browser side.

The live check then reads:

```scala
  private val probabilityErrorRaw: Signal[Option[ValidationError]] =
    probabilityLocated.map { input =>
      if input.value.isBlank then
        Some(input.reject(ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.probabilityRequired))
      else this.parseDouble(input.value) match
        case None => Some(input.reject(ValidationErrorCode.INVALID_FORMAT, ValidationMessages.probabilityNotANumber))
        case Some(pct) =>
          ValidationUtil.refineOccurrenceProbability(input.map(_ => RiskLeafFormState.pctToDomain(pct)))
            .fold(errors => Some(errors.head), _ => None)
    }
```

The signal's type changes from `Signal[Option[String]]` to
`Signal[Option[ValidationError]]`, which is the change that lets the live
channel reach the same routing the submit channel uses. Today it is a bare
string, so the location `at(FieldName.Probability)` builds at
`RiskLeafFormState.scala:135` is computed and thrown away.

**`.now()` stays, and that is correct.** `.claude/rules/laminar.md` forbids
`.now()` in a rendering pipeline, and a submit handler is an event rather than a
pipeline. The nine `.now()` calls in `RiskLeafFormState.scala` are all in
`buildDistribution` and `toDraft`, lines 388 to 431. What is wrong with them is
not that they read a snapshot but that they return a bare value with no field.

**The raw `Var` stays reachable, because the input element needs it.**
`FormInputs.textInput` takes `valueVar: Var[String]` at `FormInputs.scala:29` and
binds it two-way, so the `Var` cannot be hidden. The rule that holds instead is
narrower and sufficient: the input binding is the only reader of the raw value,
and every check and every submission reads a located accessor.

**The form gains its subject, as a `Var` rather than a derived signal.** A
form's refusals are all about one node:

```scala
  /** The node this form's refusals are about. Set when the form is opened:
    * `DraftNode` for a new leaf, `ExistingNode(id)` for an edit. */
  val subjectVar: Var[ErrorSubject] = Var(ErrorSubject.DraftNode)
  private def subject: StrictSignal[ErrorSubject] = subjectVar.signal
```

A `Var` because the submit accessor reads a snapshot, and `.now()` is available
on a `Var`'s own signal and not on a signal derived with `.map`. The precedent
is `WorkspaceState.scala:41`, `def keySignal: StrictSignal[Option[WorkspaceKeySecret]] = workspaceKey.signal`,
and every `.now()` call in `app/state` is on a `Var` or on that strict signal. A
subject derived from the form mode with `.map` would compile for the reactive
accessor and not for the submit one.

It is set at the call sites that open or clear the form, which is how
`parentVar` is already handled — `RiskLeafFormState.scala:41-46` records that it
is "not reset by `resetFields`" and is set explicitly by the view instead.

Today the form hard-codes `DraftNode` at
`RiskLeafFormState.scala:116-117` with a comment stating that every refusal it
produces is about a node with no identifier yet, which is false on the edit
path. That is invisible today because `inputFor` discards the subject; it becomes
a defect the moment the subject is read, so Phase 3a corrects it and the comment
with it.

`FormState` gains the subject, because a form's refusals are all about one node:

```scala
  /** The node this form's refusals are about. */
  def subject: Signal[ErrorSubject]
```

`RiskLeafFormState` implements it from the active form mode: a new leaf is
`ErrorSubject.DraftNode`, and an edit of an existing leaf is
`ErrorSubject.ExistingNode(nodeId)`. Today the form hard-codes `DraftNode` at
`RiskLeafFormState.scala:116-117` with a comment stating that every refusal it
produces is about a node with no identifier yet, which is false on the edit
path. That is invisible today because `inputFor` discards the subject; it becomes
a defect the moment the subject is read, so Phase 3a corrects it and the comment
with it.

### 15.5 The mapping keeps its shape; the display enums gain one case

No destination type is introduced, and the mapping stays
`ErrorLocation => Option[RiskLeafField]` in the leaf form and
`ErrorLocation => Option[PortfolioField]` in the portfolio form. What changes is
what `None` means.

**The type parameter gets a name.** `trait FormState[F]` at `FormState.scala:21`
is generic over the form's own field enum, and the single letter says nothing
about what may be passed. It becomes `trait FormState[Field]`, and every member
that mentions it follows. Three enums instantiate it: `RiskLeafField`,
`PortfolioField`, and `TreeBuilderField` at `TreeBuilderState.scala:27`.

**Why `None` is a bypass today.** It carries two meanings at once: "this form
has no control for that location", and "nobody decided where this goes". Five of
the nine multi-field refusals in `Distribution.create` name
`FieldName.DistributionType`, which `inputFor` has no arm for, so they reach the
banner through `case _ => None` rather than by a decision anyone made.

**What removes the conflation.** The per-form display enum is the set of display
targets the form owns, onto which several conceptual locations collapse — that is
why it exists and is not replaceable by `FieldName`, as §15.6 states. A form's
own line is such a target: it is a place inside the form where a message renders,
and the five `DistributionType` refusals all belong on it. So each per-form enum
gains one case for it:

```scala
enum RiskLeafField:
  case Name, Parent, Probability, Percentiles, Quantiles, MinLoss, MaxLoss, Terms

  /** The form's own line, for a refusal about a combination of its inputs
    * rather than about one of them. */
  case FormLevel

enum PortfolioField:
  case Name, Parent
  case FormLevel
```

The banner is not such a target. Nothing the form renders is the banner —
`ErrorBanner` renders above the layout, and ADR-019 decision 7 reserves it for
"failures no view owns". So the banner is not a case of a form's enum, and
`None` is left meaning one thing that is true and complete: **no display target
of this form.** The banner is where that goes, which is what decision 7 already
says it is for. Every `case _ => None` arm in both mappings becomes an explicit
arm, and what remains in `None` is there by decision.

What `None` does **not** mean is "the banner". It means no target of *this*
form, and which surface then renders the refusal depends on which view issued
the request. `TreeBuilderView` owns the tree submit and renders its failure on
its own line at `TreeBuilderView.scala:165`; the banner takes what no view
claims, which is decision 7's rule rather than a new one.

### 15.5a Each control selects its own refusals; nothing is routed into it

The mapping says where a refusal belongs. What writes it to the screen is a
separate question, and today it is answered by a push:

```scala
        case Some(field) =>
          form.setSubmitFieldError(field, err.message)
          true
```

`FormSubmitUtil.scala:30-32` — `routeErrors` walks the refusals and pushes each
one into the form's storage, returning `Unit`. Three defects follow from the
push and from nothing else.

The storage holds one message per key, so a second refusal about the same field
overwrites the first:

```scala
  private val submitFieldErrors: Var[Map[F, String]] = Var(Map.empty)
  def setSubmitFieldError(field: F, message: String): Unit =
    submitFieldErrors.update(_ + (field -> message))
```

`FormState.scala:31-37`. The refusals that reached no key are filtered out and
one is kept — `unrouted.headOption` at `FormSubmitUtil.scala:35`. And the
`Var` being pushed into is not the pusher's: `submitError` is declared in the
view at `RiskLeafFormView.scala:21` and handed to `FormSubmitUtil` to write,
which `.claude/rules/laminar.md` names outright — "Children notify parents via
`() => Unit` or `Observer[A]` callbacks — never by reading or mutating shared
mutable state directly."

**The inversion.** Every control in this form already pulls rather than being
pushed into:

```scala
        errorSignal = state.probabilityError,
```

`RiskLeafFormView.scala:287`. So the form holds the refusals that arrived and
each reader selects its own:

```scala
  /** Refusals that arrived from the server. The form's own checks are not held
    * here: they are derived from the input `Var`s, and writing a derived value
    * back into a `Var` is the self-correcting-reactive-`Var` defect ADR-019
    * names. */
  private val serverErrors: Var[List[ValidationError]] = Var(Nil)

  /** Every refusal for one input: this form's own check first, then the
    * server's for the same input. */
  def errorsFor(field: RiskLeafField, live: Signal[Option[ValidationError]]): Signal[List[ValidationError]] =
    live.combineWith(serverErrors.signal).map { (own, fromServer) =>
      own.toList ++ fromServer.filter(e => inputFor(e.location).contains(field))
    }

  /** The server's refusals no input of this form claimed. */
  def unclaimed: Signal[List[ValidationError]] =
    serverErrors.signal.map(_.filter(e => inputFor(e.location).isEmpty))
```

`inputFor` is consulted in those two methods and nowhere else, which is what
makes the mapping central: a control passes its own display key, and the
translation from the shared vocabulary happens inside `errorsFor`. `unclaimed`
is only expressible this way — asking whether any input claimed a location needs
the whole mapping in one place.

**What this deletes.** `FormSubmitUtil.scala` holds one object with one method,
so the file goes with `routeErrors`. `submitFieldErrors`, `setSubmitFieldError`,
`clearSubmitFieldError` and `withSubmitErrors` go from `FormState.scala`. The
seven `clearSubmitFieldError` calls in `RiskLeafFormView.scala` go: a stale
server refusal is cleared by `serverErrors.set(Nil)` on the next submit, and a
refusal about a field the user has since edited is filtered by the field's own
live check taking priority in `errorsFor`.

All three discards go with the push. Nothing is stored per key, so nothing
overwrites; nothing is filtered away, so no `headOption`; the banner selects
rather than receives, so no `mkString` at `ErrorBanner.scala:42-43`. And the
ownership violation goes, because the form writes only its own `Var` — no bus is
needed, since the thing a bus would have fixed was the `Var` being passed out.

`shouldShowError(field)` still gates `errorsFor`, so the blur-and-submit display
timing is unchanged, and the priority rule — the form's own check shown before
the server's — carries over from `withSubmitErrors` at `FormState.scala:62-67`.

`GlobalError.ValidationFailed` is delivered into the open form's `serverErrors`
rather than flattened in `ErrorBanner`, and the banner renders `unclaimed` as a
list.

### 15.6 The per-form display enum stays

`RiskLeafField` and `PortfolioField` are not replaced by `FieldName`, for two
reasons that are facts about the code rather than preferences.

A control can stand for several locations. `RiskLeafFormView.scala:502-503` maps
both `FieldName.Percentiles` and `FieldName.PercentileAt(index)` to
`RiskLeafField.Percentiles`, because the form edits the whole list as one
comma-separated input. `FieldName` cannot express that collapse; the per-form
enum is where it lives.

And `FieldName` has forty-eight cases while the leaf form renders eight.
`FormState[F]` keyed on the form's own enum is what stops `markTouched` being
handed a field the form has no control for.

That reason is what decides §15.5. The enum is the set of display targets this
form owns; the form's own line is one of those targets and the banner is not, so
the line becomes a case and the banner stays outside. The enum is modified
rather than a third concept being introduced, and the argument for that is the
enum's own definition rather than a preference for fewer types.

What the per-form enum does **not** do is decide that a minimum-over-maximum
refusal is reported on the maximum. That is the `FieldName` the rule stamps —
`FieldName.MinLoss` at `Distribution.scala:100` today — and the mapping only
follows it. Which of the two places that choice belongs in is `EWB-D-39`.

### 15.7 The measured scope

`ValidationUtil`'s refinements are called from seventy-four sites in seventeen
files under `src/main`, counted on 2026-10-21:

| Sites | File |
|---|---|
| 12 | `domain/data/iron/OpaqueTypes.scala` |
| 10 | `http/codecs/IronTapirCodecs.scala` |
| 10 | `domain/data/RiskNode.scala` |
| 7 | `http/requests/RiskTreeRequests.scala` |
| 6 | `domain/data/RiskLeafTransform.scala` |
| 6 | `domain/data/ResultTransformSpec.scala` |
| 6 | `app/state/RiskLeafFormState.scala` |
| 4 | `domain/data/Distribution.scala` |
| 3 | `domain/data/Provenance.scala` |
| 2 | `configs/SpiceDbConfig.scala` |
| 2 | `app/state/TreeBuilderState.scala` |
| 2 | `app/state/PortfolioFormState.scala` |
| 1 each | `services/helper/Simulator.scala`, `http/controllers/WorkspaceTreeController.scala`, `domain/data/Mitigation.scala`, `configs/SimulationConfig.scala`, `app/state/ParentSelection.scala` |

Not all seventy-four are field refusals. The codec sites take `atCodec`, the
four configuration sites take `atConfig`, and `OpaqueTypes.scala`'s twelve are
inside smart constructors that receive a location from their own caller. The
compiler enumerates the list once the two-argument forms are private, so §13
produces it rather than this table being maintained by hand.

### 15.8 The steps, in order

Each step ends with all four tiers green.

1. **`Located[A]` with `map`, `reject`, `atCodec` and `atConfig`** in
   `ErrorLocation.scala`, with no caller yet. Nothing compiles differently. The
   step exists on its own so one type is reviewable before seventy-four sites
   move.
2. **Change the twenty-six refinements in `ValidationUtil` to take
   `Located[A]`.** The compiler then names every caller, and that list is the
   migration list — nothing is found by grep. Convert them, including the ten
   codec sites to `atCodec` and the four configuration sites to `atConfig`,
   which deletes the four duplicated location constants.
3. **The form's subject and the located accessors** in both form states,
   including the edit-path subject correction of §15.4. The live checks move to
   `Signal[Option[ValidationError]]` and stop discarding their location.
4. **`FormLevel` added to both display enums, and both mappings made explicit.**
   Every `case _ => None` arm is replaced by named arms. `FormState`'s type
   parameter is renamed from `F` to `Field` in the same pass.
5. **The push inverted to a pull.** `serverErrors`, `errorsFor` and `unclaimed`
   in both form states; `FormSubmitUtil.scala` deleted; `submitFieldErrors`,
   `setSubmitFieldError`, `clearSubmitFieldError` and `withSubmitErrors` deleted
   from `FormState.scala`; the seven `clearSubmitFieldError` call sites in
   `RiskLeafFormView.scala` removed. `GlobalError.ValidationFailed` is delivered
   into the open form's `serverErrors`, and `ErrorBanner` renders `unclaimed` as
   a list.

Steps 1 and 2 are the shared module and do not touch the browser. Steps 3 to 5
are the browser and do not touch the shared module. The split is deliberate:
step 2 is where the scope is large and the compiler is doing the enumeration,
and it is verifiable on its own before any display behaviour changes.
