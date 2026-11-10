# PLAN-UES-UNIFIED-ERROR-SHAPE

Plan code `UES`. Decisions in this document are labelled `UES-D-<n>`.

**Status.** Written 2026-11-09 at version 0.17.2. `UES-D-1` to `UES-D-15` are
ruled, `UES-D-16` is withdrawn and `UES-D-17` is ruled. No decision is open. Phase 0 steps 0.1 and 0.2 landed, at versions 0.18.1 and 0.18.2.
Phase 0 step 0.3 and Phase 1 steps 1.1 to 1.3 landed together, at version 0.18.3.
Phase 1 step 1.4 landed at version 0.18.4, which closes Phase 1. Phase 2 steps
2.1 and 2.5 landed together, at version 0.18.5. Phase 2 step 2.2 landed at
version 0.18.6, and step 2.3 at version 0.18.7. Phase 2 step 2.4 is next. The
file inventory is
`docs/dev/plans/PLAN-UES-UNIFIED-ERROR-SHAPE-INVENTORY.md`.

The supporting record `docs/dev/plans/PLAN-UES-UNIFIED-ERROR-SHAPE-ERROR-PATHS.md`
holds the sixty failure paths this plan eliminates, one row per path, with the
file and line each is built at today (its §3 to §7), the target shape (§8) and
each row's value under that shape (§9). This plan refers to rows by that
record's numbering and does not repeat the tables. The record is the plan's
checklist: the plan is done when every row's "Value as built" is a shape the
code no longer contains.

This plan takes over the unimplemented Phase 7 of `PLAN-ERROR-WIRE-BOUNDARY.md`
(plan code `EWB`), whose six steps closed the ADR-039 borders under a
`ValidationFailed(errors, zone)` design. That design is superseded by the shape
in §3, and Phase 7's signatures and tests are rewritten here under it. §11
states what was carried over and what was dropped.

---

## 1. What this plan is for

The server produces every failure as a value of `AppError`, a sealed trait with
four sub-traits and 32 leaves. The record's §10 states what the sixty rows show:
six zones feed the hierarchy and no row carries its zone as data; the same Irmin
outage answers 503 from one service and 500 from another; refused store content
is an outage in one row, a flattened string in ten, a defect in one and a
caller's conflict in another; Postgres has no outage case; a server fault is
logged at INFO when a translation is skipped; and three computations over stored
content are answered as the caller's fault.

Each of those is a local inconsistency, and fixing them one by one under the
present hierarchy adds a 33rd leaf each time. The plan replaces the hierarchy
with one shape, `Core[Z, P]` inside a seven-variant `Failure`, in which the
zone a value came from is a field, the list of refusals is a field, and
every derived value is one total function. Then it moves every row onto that
shape and deletes `AppError`.

Two things are fixed before the plan starts. The wire is `WireError`, nine
cases at nine statuses, with per-case codecs, and this plan changes no case and
no codec; what changes on the wire is listed in §5 and is the set of statuses
that move because a row is reclassified. The row numbering is the record's.

---

## 2. The method

**One row at a time, grouped by the leaf it builds.** A row is one construction
site with one shape. The unit of a step is the old leaf being deleted, because
deleting a leaf forces every site that builds it to change in one compile, so
the rows that build one leaf land together and the leaf's name is the step's
grep target. Two leaves are large: `RepositoryFailure` covers rows 39 to 48 and
52 to 55, and `ValidationFailed` covers rows 2 to 13, 42 to 47 and 53 to 58.
Those two are split by zone into several steps, and the leaf is deleted in the
group's last step (`UES-D-11`).

**The bridge.** Every service and repository signature is `IO[AppError, A]`
today, and a value of the new shape cannot travel through it. So Phase 0 adds
the new shape beside the old one and widens the one boundary that projects
failures onto the wire to accept both: `WireEither.wireEither` is declared over
`ZIO[R, AppError | Failure, A]`. From then on a signature on the path of a moved
row reads `IO[AppError | Failure, A]` until its last `AppError` producer is
gone, when it narrows to `IO[Failure, A]`. Scala 3 union types make the
widening a one-token change per signature and the compiler lists every site
(`UES-D-10`). The last phase deletes `AppError`, and the final grep target is
that name.

**What enters an aggregate is refined.** Every value a step reads back from a
store or a peer is refined to the type the domain declares for it before it is
held, and a value that does not refine is a `Rejected` at that peer's zone. No
step admits a raw primitive on the ground that the peer might hold content this
service did not write: the store holds only what this service wrote, and the
authorization service answers only about this service's own identifiers.

**Acceptance per step.** The touched modules compile. `commonJVM/test`,
`server/test`, `app/test` and `serverIt/test` are green. A grep for the step's
targets over `modules/` returns nothing. The step's behavioural test, stated in
§4, is present and green. **Acceptance per phase** adds the BATS suites, which
need the images rebuilt at the current version and so run once per phase
(register-dev skill, "When each tier runs").

**What a step writes.** The new value at the construction site, the signature
widening the compiler demands, the deletion of the old leaf and its arm in the
projection, the test, and the boyscout obligations on every touched file.
Nothing else: a step that finds a defect outside its rows reports it under the
plan's second carve-out and does not fix it in passing.

---

## 3. The shape

The signatures below are the plan's specification. They are placed in the
server module, as `UES-D-13` rules.

### 3.1 `Core`, `Failure`, `TrustZone`, `PeerZone`

```scala
package com.risquanter.register.domain.errors

import zio.prelude.NonEmptyList

/** Where a value came from, as the side of a trust boundary it was on.
  *
  * This is the field every failure carries and the wire never sees. A refusal at
  * `Client` is the caller's to fix; a refusal anywhere else is content a peer
  * returned, or this service's own, and answers as an internal fault (ADR-039
  * §4).
  */
enum TrustZone:
  /** The client, through its request's body, path, headers and query. */
  case Client

  /** The deployment: configuration, and the capabilities the wired backends
    * have. A value here is the operator's, not the caller's. */
  case Environment

  /** The service mesh, through the typed claim header it forwards. */
  case MeshHeader

  /** A peer this service calls, named by `PeerZone`. */
  case Peer(peer: PeerZone)

  /** This service's own computation over values already inside the region. */
  case Service

/** The peers this service calls and reads answers from. */
enum PeerZone:
  /** The Irmin store: a stored value, or a GraphQL response. */
  case Store

  /** The Postgres database: a row, or the driver. */
  case Database

  /** The SpiceDB authorization service: a check or listing response. */
  case Authorization

/** The fields every failure carries. `Z` is the zone type a variant admits and
  * `P` the problem type its list holds; a variant fixes both and adds nothing.
  *
  * `errors` is never empty: a failure with nothing refused is not a failure.
  * `diagnostics` is log-only and never reaches the wire.
  */
final case class Core[Z, P](
  zone:        Z,
  errors:      NonEmptyList[(ErrorLocation, P)],
  diagnostics: List[Diagnostic]
)

/** Every failure the server produces as a value. One variant per answerable
  * wire kind; `InternalFault` is not a variant but what a non-`Client` value
  * projects to.
  */
enum Failure:
  case Rejected    (core: Core[TrustZone,        FieldProblem])
  case Unanswered  (core: Core[PeerZone,         OutageMode])
  case NotFound    (core: Core[TrustZone,        NotFoundKind])
  case Conflict    (core: Core[TrustZone,        ConflictKind])
  case AccessDenied(core: Core[TrustZone,        DenialKind])
  case Expired     (core: Core[Client.type,      ExpiryDetail])
  case NotSupported(core: Core[Environment.type, Capability])
```

`Client.type` and `Environment.type` are the singleton types of the two cases,
so an `Expired` cannot name a store and a `NotSupported` cannot name a caller.
The names `Failure`, `Core`, `Service`, `Diagnostic` and `ExpiryDetail` are
final (`UES-D-14`).

The family search. `TrustZone`, `PeerZone`, `Failure`, `Diagnostic`,
`OutageMode` and `DenialKind` join the closed sets that say what a failure is,
the top-level enums in `domain/errors/`: `FieldProblem` in `ErrorLocation.scala`,
`MergeRefusal` in `MergeRefusal.scala`, `TreeLoadProblem` in
`TreeLoadProblem.scala`. The naming pattern is a noun for the thing classified.
`FailureOrigin` in `AppError.scala` is the existing set nearest `TrustZone`; it
has two cases, `Caller` and `Service`, and names who is attributable rather than
where a value came from, so `TrustZone` replaces it and `origin` becomes a
derived value (§3.4). `RepositoryType` and `WorkspaceStoreBackend` share the
spellings `Irmin` and `Postgres` and name which implementation is wired at
startup, not a zone. `Core` has no family: no generic record exists in the
codebase, and it is the first.

### 3.2 The payload enums

Four are reused unchanged from the wire: `NotFoundKind`, `ConflictKind` and
`Capability` in `http/errors/`, and `ExpiryCause` in `domain/data/Workspace.scala`.
Two are new, and one new record wraps an existing enum.

```scala
/** How a peer failed to answer. The cases are the four transport failures the
  * Irmin client distinguishes today, generalised to every peer.
  */
enum OutageMode:
  /** The connection was refused or dropped; carries no status. */
  case ConnectionRefused
  /** No answer within the client's deadline. */
  case Timeout(after: zio.Duration)
  /** The peer answered with a status that is not success. */
  case Status(code: sttp.model.StatusCode)
  /** The peer answered, and the answer was not the protocol's shape: an
    * undecodable body, or a GraphQL `errors` array. */
  case Protocol

/** Why access was denied. */
enum DenialKind:
  /** The identity header the route requires was absent. */
  case IdentityAbsent
  /** The authorization service answered that the permission is not held. */
  case PermissionNotHeld

/** A deadline a workspace has passed, with the two limits that define it. */
final case class ExpiryDetail(cause: ExpiryCause, lifetime: java.time.Duration, idleLimit: java.time.Duration)
```

The family search for `OutageMode`: the four cases restate `IrminUnavailable`,
`NetworkTimeout`, `IrminHttpError` and `IrminGraphQLError`, which are leaves of
`IrminError` in `AppError.scala` today, as one set over every peer. No existing
enum denotes an outage. `Timeout` carries the deadline `NetworkTimeout.duration`
carries today; `Status` carries `IrminHttpError.status`. For `DenialKind`: the
two cases restate `AccessDenied(reason: String)` and `AuthForbidden` from
`AppError.scala`, and the string is dropped because `WireError.AccessDenied`
carries only the trace (ADR-035 §3). For `ExpiryDetail`: it carries the three
values `WireError.WorkspaceExpired` carries beside the trace, and no existing
type holds them together.

New cases on existing enums, each with its family stated by the member beside it:

```scala
// ErrorLocation.scala
enum ErrorSubject:
  …
  /** A stored or submitted tree's own fields: its root id, its mitigations
    * list, its seed high-water mark. Carries the tree so a listing's
    * accumulated refusals name their trees apart (UES-D-5). */
  case Tree(treeId: TreeId)                 // beside ExistingNode(nodeId: NodeId)

enum FieldName:
  …
  case Ttl, IdleTimeout                     // beside WorkspaceKey; a workspace's two lifetimes

enum ExpectedInput:
  …
  case Json                                 // beside Declared; a stored value's text was not JSON
  case Interval                             // the Postgres interval text

enum ValueFormat:
  …
  case StoreSegment                         // beside CommitHash; an Irmin path segment

enum FieldProblem:
  …
  /** The query engine refused the query text. Wraps the wire's own payload so
    * the projection emits `RejectionDetail.Query(problem)` unchanged (UES-D-7). */
  case Query(problem: QueryProblem)        // beside Unreadable(expected: ExpectedInput)

// http/errors/QueryProblem.scala
enum QueryProblem:
  …
  /** The query names answer variables, the `(y)` suffix, and this service
    * evaluates Boolean queries only. */
  case AnswerVariablesNotSupported(variables: List[String])   // beside NotQuantifiable(message: String)

enum FieldProblem:
  …
  /** A node is a leaf on one side of a merge and a portfolio on the other. */
  case StoredNodeKindDiffers                // beside StoredNodeIsLeaf, StoredNodeIsPortfolio

// http/errors/NotFoundKind.scala
enum NotFoundKind:
  …
  /** A store listing named a value and the store does not hold it. */
  case StoredValueAbsent()                  // beside Unknown()
  /** A tree has nodes under it and no metadata. */
  case MetadataAbsent()
  /** A tree has metadata and no nodes. */
  case NodesAbsent()
  /** The branch exists and has no head commit. */
  case BranchHeadAbsent()

// http/errors/Capability.scala
enum Capability:
  …
  /** Reading a tree at a commit, which only a store with history supports. */
  case PinnedReads                          // beside Scenarios
```

`NotFoundKind` and `Capability` are wire enums with derived codecs, so the
published schema gains those cases (§5). No client is sent the four `NotFoundKind`
cases, because a `NotFound` at `Peer(Store)` projects to `InternalFault`. The
`StoredNode…` cases at `ErrorLocation.scala` lines 468 to 471 are refusals of a
tree update that names a stored node; `StoredNodeKindDiffers` is a refusal of
stored content on two merge sides, so it is a new case rather than one of them.

### 3.3 `Diagnostic`

```scala
/** One log-only entry on a failure. Each case fixes its log key and the type of
  * its value, so a site cannot log a value under a wrong key or log a value the
  * case does not admit (ADR-037). A confidential identifier may appear here,
  * because this reaches a server log and never the wire (ADR-036).
  */
enum Diagnostic:
  case WorkspaceRef(id: WorkspaceId)                              // workspace_id
  case TreeRef(id: TreeId)                                        // tree_id
  case RevisionRead(rev: Revision)                                // revision
  case ExpiredAt(at: java.time.Instant)                           // expired_at
  case ExpectedHead(hash: CommitHash)                             // expected_head
  case ActualHead(hash: Option[CommitHash])                       // actual_head; None when the branch has no head
  case Principal(user: UserId.Authenticated, permission: Permission, resource: ResourceRef)
                                                                  // user, permission, resource_type, resource_id
  case StoragePath(path: IrminPath)                               // storage_path
  case RawValue(text: String)                                     // raw_value: the segment, hash, interval or counter text that was refused
  case PeerMessage(text: String)                                  // peer_message: an exception's, driver's or fitter's own text
  case PeerBody(text: String)                                     // peer_body: an HTTP body this service could not read
  case GraphQlPath(path: List[String])                            // graphql_path

object Diagnostic:
  /** The log key and rendered value of one entry. Total over the enum. */
  def render(d: Diagnostic): List[(String, String)]
```

`RawValue`, `PeerMessage` and `PeerBody` hold `String` because
each is text a peer produced that this service refused or did not produce; the
raw primitive is the correct type for text whose only use is a log line
(ADR-035 §3 permits it there and nowhere else). `Principal` names three server
types, which is one of the two facts `UES-D-13` turns on. The list of cases is
derived from the record §9's diagnostics column; a row that needs an entry the
list lacks adds a case in its step, and that addition is listed in the step's
report.

### 3.4 What is derived, once

```scala
object Failure:
  extension (f: Failure)
    /** The zone, widened from each variant's `Z`. */
    def zone: TrustZone
    /** `Caller` for `Client`, `Service` for every other zone. Decides the log
      * level: `Caller` is INFO, `Service` is WARN (ADR-002). */
    def origin: FailureOrigin
    /** The server's own text for the log line. Never crosses to a client. */
    def logMessage: String
    /** The structured fields for the log line: every diagnostic rendered, plus
      * `trust_zone`. */
    def logAnnotations: Map[String, String]

  /** The one way a `Validation` becomes a failure. */
  def rejected(zone: TrustZone, errors: NonEmptyList[ValidationError], diagnostics: List[Diagnostic]): Failure
```

`FailureOrigin` is kept as the two-case enum that names a log level's cause,
and it is now derived from the zone rather than declared per leaf. `logMessage`
is one function: the variant's name, then each `(location, problem)` rendered
by `ErrorLocation.describe` and the problem enum's own text, joined by
`"; "`. `FieldProblem`'s text is `ValidationMessages.render`, which exists
today and gains an arm for `FieldProblem.Query` through `QueryProblem.describe`.
`OutageMode.describe` and `DenialKind.describe` are written with their enums.
The other payload enums have no server-side text today, because their only
sentences are the browser's, in `ErrorMessages.scala`. Each gains one function
in its companion, following `MergeRefusal.describe` in `MergeRefusal.scala`:

```scala
// http/errors/NotFoundKind.scala, in object NotFoundKind
/** The server's own sentence for a log line. Never reaches a client. */
def describe(kind: NotFoundKind): String

// http/errors/ConflictKind.scala, in object ConflictKind
/** The server's own sentence for a log line; `MergeRefused` delegates to
  * `MergeRefusal.describe`. Never reaches a client. */
def describe(kind: ConflictKind): String

// http/errors/Capability.scala, in object Capability
def describe(capability: Capability): String

// http/errors/QueryProblem.scala, in object QueryProblem
def describe(problem: QueryProblem): String

// Failure.scala, in object ExpiryDetail
def describe(detail: ExpiryDetail): String
```

```scala
// Failure.scala, beside WireError.fromAppError until the last phase deletes it
enum Failure:
  def toWire(trace: TraceId, scope: TreeScope): WireError
```

The projection is total over `(variant, zone)`. A `Client` value projects to
the wire case of its variant: `Rejected` to `ValidationRejected` or
`RequestRejected` by scope, with `RejectionDetail.Query` where the one problem is
`FieldProblem.Query`; `NotFound`, `Conflict`, `AccessDenied` and `Expired` to
their namesakes, carrying the problem half of the first element. `Unanswered`
projects to `DependencyUnavailable` from every peer. `NotSupported` projects to
`NotSupported` with its capability. Every other `(variant, zone)` projects to
`InternalFault`. `zone` and `diagnostics` are dropped on every arm, the trace is
added, and the list passes through. `MeshHeader` and `Peer(Authorization)` are
the two non-`Client` zones that still answer their own case: an `AccessDenied`
at either projects to `WireError.AccessDenied`, because the denial is the
answer the caller must act on even though the caller did not produce the value.

```scala
// WireEither.scala, from Phase 0; the union narrows to Failure in Phase 6
extension [R, A](effect: ZIO[R, AppError | Failure, A])
  def wireEither(scope: TreeScope)(using tracing: Tracing): ZIO[R, Nothing, Either[WireError, A]]

private def logProjectedFailure(error: AppError | Failure, trace: TraceId): UIO[Unit]
```

```scala
// ValidationExtensions.scala, moved from common to server in Phase 0.3, same package
extension [A](v: Validation[ValidationError, A])
  def toZIOValidation(zone: TrustZone): IO[Failure, A]
```

The zone has no default, so a new site cannot be written without choosing one.

### 3.5 Return values that are not failures

```scala
// infra/irmin/model/IrminWriteOutcome.scala
/** What a compare-and-set branch write did. A refusal is an answer, not a
  * failure: the caller holds the expected head and decides what the refusal
  * means for its own request (UES-D-4). */
enum IrminWriteOutcome:
  case Applied
  /** The branch's head at the moment of the refusal. */
  case Refused(head: CommitHash)

/** What a merge did. */
enum IrminMergeOutcome:
  case Merged(commit: IrminCommit)
  /** Irmin refused the merge; its own text is diagnostic only. */
  case Conflicted(reason: String)
```

The family: the Irmin client's models in `infra/irmin/model/`, where
`IrminCommit`, `IrminBranch`, `IrminSegment` and `IrminPath` follow the pattern
of an `Irmin` prefix on the thing modelled. `Conflicted.reason` is `String`
because it is Irmin's own sentence, used only as `Diagnostic.PeerMessage`.

```scala
// IrminClient.scala, Phase 3
def createBranchAt(branch: StoreBranch, at: CommitHash): IO[Failure, IrminWriteOutcome]
def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[Failure, IrminWriteOutcome]
def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[Failure, IrminWriteOutcome]
def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage): IO[Failure, IrminMergeOutcome]
```

The scenario services match on the outcome and build the caller's `Conflict`.
An unhandled outcome is an inexhaustive match and a compile error, which is what
replaces the three `InternalFault` arms that caught a skipped translation.

### 3.6 Signatures carried over from EWB Phase 7, rewritten

Stored values keep their validation structure (rows 42 to 47):

```scala
object RiskLeaf:
  def decodeValidated(json: String, subject: ErrorSubject): Either[NonEmptyList[ValidationError], RiskLeaf]
object RiskPortfolio:
  def decodeValidated(json: String, subject: ErrorSubject): Either[NonEmptyList[ValidationError], RiskPortfolio]
object Mitigation:
  def decodeValidated(json: String, subject: ErrorSubject): Either[NonEmptyList[ValidationError], Mitigation]
object TreeMetadata:
  def decodeValidated(json: String, subject: ErrorSubject): Either[NonEmptyList[ValidationError], TreeMetadata]
object RiskTreeRepositoryIrmin:
  def decodeStoredNode(json: String, nodeId: NodeId): Either[NonEmptyList[ValidationError], RiskNode]
```

Each reads the raw form with the derived raw codec and refines it through the
smart constructor, which accumulates. Text that is not the raw form's JSON yields
one `ValidationError(ErrorLocation.Whole, Unreadable(ExpectedInput.Json))`.
`decodeStoredNode` tries the raw leaf form first and the raw portfolio form
second, so a stored leaf with a bad probability is a leaf's refusal and not a
malformed portfolio. The `subject` parameters of `RiskLeaf.create`,
`RiskPortfolio.create` and `RiskPortfolio.createFromStrings` lose their
`ErrorSubject.Request` default; `RiskTree.fromNodes` reports tree-own fields at
`Field(Tree(id), f)` (`UES-D-5`). The repository's `decodeMeta`, `decodeNode`,
`decodeMitigation`, `rebuildTree` and `parseTreeId`, and the merge service's
`decodeVersions`, `duplicateNodeNames` and `refineListed`, fail with
`Failure.rejected(TrustZone.Peer(PeerZone.Store), errors, Nil)`.

The database classifies once, from a code (rows 12, 52 to 54):

```scala
// WorkspaceStorePostgres.scala
private def classify(err: Throwable): Failure
private def parseInterval(value: String, field: FieldName): IO[Failure, Duration]

object DurationString:
  def parseInterval(value: String, location: ErrorLocation): Either[NonEmptyList[ValidationError], Duration]
```

`classify` reads the driver's `SQLState`. `23505`, the SQL standard's
unique-violation state, on the seed-entity constraint is row 11's `Rejected` at
`Client`; `23505` on another constraint is `Conflict` at `Peer(Database)` with
`(Whole, ConflictKind.Rejected())`; a connection failure is `Unanswered` at
`Database` with the `OutageMode` the exception class decides, carrying
`PeerMessage(err.getMessage)`. Whether the driver exposes the constraint name is
confirmed when the step opens the file; if not, the step classifies on the state
alone and keeps the constraint distinction at the one call site that needs it.
`toRecord`'s seed-entity refusal and the two interval parses are `Rejected` at
`Peer(Database)`, located at `Field(Workspace, SeedEntityId | Ttl | IdleTimeout)`.

The Irmin client refines everything Irmin returns (rows 37, 39 to 41):

```scala
// infra/irmin/model/IrminCommit.scala
final case class IrminInfo(date: Instant, message: CommitMessage)
object IrminInfo:
  final case class Raw(date: String, message: String)
  object Raw:
    given JsonCodec[Raw] = DeriveJsonCodec.gen[Raw]
  def refine(raw: Raw): Either[NonEmptyList[ValidationError], IrminInfo]

final case class IrminCommit(hash: CommitHash, key: CommitHash, parents: List[CommitHash], info: IrminInfo)
object IrminCommit:
  final case class Raw(hash: String, key: String, parents: List[String], info: IrminInfo.Raw)
  object Raw:
    given JsonCodec[Raw] = DeriveJsonCodec.gen[Raw]
  def refine(raw: Raw): Either[NonEmptyList[ValidationError], IrminCommit]

final case class IrminBranch(name: StoreBranch, head: Option[IrminCommit])

// infra/irmin/model/IrminPath.scala
object IrminSegment:
  def from(text: String): Either[NonEmptyList[ValidationError], IrminSegment]

// infra/irmin/CommitMessage.scala
object CommitMessage:
  def parse(message: String): Either[NonEmptyList[ValidationError], CommitMessage]
  def historyOperation(message: CommitMessage): Option[HistoryOperation]

// domain/data/iron/OpaqueTypes.scala
object StoreBranch:
  def fromString(s: String, location: ErrorLocation): Either[List[ValidationError], StoreBranch]

// infra/irmin/IrminClient.scala
def branches: IO[IrminError | Failure, List[StoreBranch]]
```

Every GraphQL response class that holds a commit holds `IrminCommit.Raw`, and
the client refines on arrival, failing with `Rejected` at `Peer(Store)` and
`RawValue(hash)`, `RawValue(message)`: a hash with
`(Whole, WrongFormat(ValueFormat.CommitHash))`; a date that is not a whole
number with `(Whole, Unreadable(ExpectedInput.WholeNumber))`, and one outside an
`Instant`'s range with `(Whole, Unreadable(ExpectedInput.Declared))`; a message
that is not one of the three shapes this service writes with
`(Whole, Unreadable(ExpectedInput.Declared))`, and one whose identifier segments
do not refine with those segments' own refusals. `ExpectedInput.Declared`'s
sentence widens to "whatever type the reader declares for the value", so it
covers a decoder as well as an endpoint. `childPaths` fails the same way with
`WrongFormat(ValueFormat.StoreSegment)`, and a listed branch name that is not
main, a scenario branch or a merge's staging branch fails with
`WrongFormat(ValueFormat.BranchName)` and `RawValue(name)`. The queries select
neither `author` nor a listing's `hash` and `value`, because nothing reads them,
and `IrminContents` and `IrminInfoInput` go with them. The repository's
`resolveHead`, `TreeHistoryServiceLive.toEntry` and `ScenarioBranchOps` lose
their late `CommitHash.fromString`, `toEntry` loses its `toIso` and reads the
refined instant, and the `ZIO.die` at `ScenarioBranchOps.scala` lines 29 to 35
goes (row 41). `HistoryOperation.Other` goes: a commit whose message this
service did not write is refused where it is read, so no entry is ever built
for one. `IrminClient.get` keeps `Option[String]`, because the stored bytes'
parse is the domain decoder the caller runs on them in the same step.

The SpiceDB response parse refuses content (row 32, `UES-D-6`):

```scala
// auth/AuthorizationService.scala
def check[P <: Permission](user: UserId.Authenticated, permission: P, resource: ResourceRef): IO[Failure, Checked[P]]

// auth/AuthorizationServiceSpiceDB.scala
private enum SpiceDbObjectRef:
  case Resource(resourceType: ResourceType, id: SafeId.SafeId)
  case User(id: UserId.Authenticated)
private case class SpiceDbCheckRequest(resource: SpiceDbObjectRef, permission: Permission, subject: SpiceDbSubjectRef, consistency: Json)
private enum Permissionship(val wire: String):
  case Unspecified, NoPermission, HasPermission, ConditionalPermission
private case class SpiceDbCheckResponse(permissionship: String)
```

A request names its resource by `ResourceType` and `SafeId`, and its subject by
`UserId.Authenticated`; the encoders write the Zed spellings, which are the
declared-spelling case of ADR-037 §2. SpiceDB's object id is one of this
application's own identifiers on the way out and never arrives on the way in,
so no type for SpiceDB's own id grammar exists. The response's permissionship
is refined to the `Permissionship` enum, whose four spellings the SpiceDB v1
API fixes. An unparseable body is `Rejected` at `Peer(Authorization)` with
`(Whole, Unreadable(ExpectedInput.Json))` and `PeerMessage(parse text)`; a
permissionship outside the four is `Rejected` there with
`(Whole, Unreadable(ExpectedInput.Declared))` and `RawValue(text)`. Any answer
but `HasPermission` is `AuthForbidden`, because this service evaluates no
caveats. `listAccessible` and `ResourceId` go: the lookup had no caller, and
its one planned consumer, a listing of the workspaces a user owns, waits on a
Layer 1 decision the authorization plan records. When that decision is ruled
the lookup returns `List[WorkspaceId]`, server-side only.

The distribution fit moves to the write (row 56, `UES-D-8`):

```scala
// services/RiskTreeServiceLive.scala
private def fitCheck(leaf: RiskLeaf, subject: ErrorSubject): Validation[ValidationError, Unit]
```

It runs on every leaf a create or update adds or changes, as the last step of
that leaf's own statement validation, and accumulates with the other leaves'
and the tree's errors into one `Rejected` at `Client`, located at
`Field(NodeAtIndex(Leaves, i) | ExistingNode(id), DistributionType)` with
`MetalogFitFailed | LognormalFitFailed` and `PeerMessage(fitter text)`. The
fitters are JVM-only (`build.sbt` line 89), so the check cannot live in
`common`. `Simulator.scala` line 197 becomes a `require`, because the store
holds no tree written before the check.

---

## 4. Phases and steps

Each step names its rows, what it writes, its grep targets and its test. "Grep"
means `grep -rn <target> modules/` returning nothing after the step. Tests are
in the existing suite of the file touched unless a suite is named.

### Phase 0 — the shape and the bridge

No row moves. The phase adds §3.1 to §3.4 and widens the boundary.

0.1 `TrustZone`, `PeerZone`, `Core`, `Failure`, `OutageMode`, `DenialKind`,
`ExpiryDetail`, `Diagnostic` and the `Failure` companion, in new files under
the package `UES-D-13` places them in. `FieldProblem.Query`,
`FieldProblem.StoredNodeKindDiffers`, `ErrorSubject.Tree`, `FieldName.Ttl`,
`FieldName.IdleTimeout`, `ExpectedInput.Json`, `ExpectedInput.Interval`,
`ValueFormat.StoreSegment`, the four `NotFoundKind` cases and
`Capability.PinnedReads`, each with its `describe` or `render` sentence. Test: a
new `FailureSpec` pins that `origin` is `Caller` for `Client` and `Service` for
every other zone, that `logAnnotations` carries `trust_zone` and every
diagnostic's key, and that `logMessage` renders every element of a two-element
list.

0.2 `Failure.toWire` beside `WireError.fromAppError`. Test: `FailureSpec` gains
an exhaustive case-to-wire assertion over every `(variant, zone)` pair: `Client`
values to their own case, `Unanswered` from each peer to `DependencyUnavailable`,
`AccessDenied` at `MeshHeader` and `Peer(Authorization)` to `AccessDenied`, every
other pair to `InternalFault`; and that `Rejected` with a single
`FieldProblem.Query` projects to `RejectionDetail.Query`.

0.3 `WireEither` over `AppError | Failure`, `logProjectedFailure` over both,
`ValidationExtensions.toZIOValidation(zone)` returning `IO[Failure, A]`. The
file moves from `common` to `server` in the same package, because its result
type is a server type and its only callers are `RiskTreeServiceLive` and
`DistributionPreviewController`. Every
existing call of `toZIOValidation` passes `TrustZone.Client` and widens its
enclosing signature to `IO[AppError | Failure, A]`; this is the one step where
rows change type without changing zone, and it moves rows 2 to 11, 13, 56 to
58 onto `Rejected` at `Client` as a side effect. `extractErrorContext` in
`RiskTreeServiceLive` reads the first element of a `Rejected` list. Test:
`WireEitherSpec` pins one line per failure at the level its zone decides, for
one `AppError` and one `Failure`.

Grep: none. Acceptance: compile, the four tiers, BATS.

### Phase 1 — outages: `Unanswered`

Rows 30, 31, 33 to 36 and 52. Old leaves deleted: `IrminUnavailable`,
`NetworkTimeout`, `IrminHttpError`, `IrminGraphQLError`, `AuthServiceUnavailable`.

1.1 Rows 33 to 36. `IrminClientLive` builds `Unanswered` at `Store` with the
`OutageMode` of §3.2 and the diagnostics of record §9 (`PeerMessage`, `PeerBody`,
`GraphQlPath`). Every `IrminClient` signature becomes `IO[IrminError | Failure, A]`.
Both `handleIrmin` helpers are deleted, in `RiskTreeRepositoryIrmin.scala` and
`TreeHistoryService.scala`, so the outage answers 503 from every caller. The
three `RepositoryFailure` sites they protected stay until Phase 2. Grep:
`IrminUnavailable`, `NetworkTimeout`, `IrminHttpError`, `IrminGraphQLError`,
`handleIrmin`. Test: `RiskTreeRepositoryIrminSpec` and `TreeHistoryServiceSpec`
pin that a read whose Irmin call fails with `Unanswered` fails with that value
and not with `RepositoryFailure`; `IrminClientIntegrationSpec` pins a connection
refusal as `Unanswered(Core(Store, (Whole, ConnectionRefused), …))`.

1.2 Row 37. `IrminSegment.from` returns the refusal list; `childPaths` fails
with `Rejected` at `Peer(Store)`, `(Whole, WrongFormat(StoreSegment))`,
`RawValue(segment)`. Grouped here because the site builds `IrminUnavailable`.
Test: a unit test of `IrminSegment.from` pins the refusal.

1.3 Rows 30 and 31. `AuthorizationServiceSpiceDB` and `BootstrapProvisionerSpiceDB`
build `Unanswered` at `Authorization`; `AuthorizationService` and
`BootstrapProvisioner` signatures become `IO[AuthError | Failure, A]`. Grep:
`AuthServiceUnavailable`. Test: `AuthorizationServiceSpiceDBSpec` pins a refused
connection and a 500 as `Unanswered` with `ConnectionRefused` and `Status(500)`.

1.4 Row 52. `WorkspaceStorePostgres.classify` of §3.6 for the outage branch;
the `23505` branches land in Phase 2.4. Test: `WorkspaceStorePostgresSpec`
pins a closed connection as `Unanswered` at `Database`.

Acceptance: compile, the four tiers, BATS suite A.

### Phase 2 — refused and absent store, database and authorization content

Rows 12, 32, 38 to 48 and 53 to 55. Old leaves deleted: `RepositoryFailure`,
`TreeLoadFailure`, `TreeLoadProblem`. This phase is EWB Phase 7's steps 2 to 6
under the new shape.

2.1 Rows 39 to 41. `IrminCommit.Raw`, `IrminInfo.Raw`, both `refine`s,
`CommitMessage.parse`, `StoreBranch.fromString` and `IrminBranch.name` of §3.6;
`resolveHead`, `toEntry` and `ScenarioBranchOps` read the refined hash, `toEntry`
reads the refined message and instant, `ScenarioServiceLive.scenarioNameOf` reads
the refined branch. The `ZIO.die`, `toIso`, `HistoryOperation.Other`,
`IrminContents` and `IrminInfoInput` go. Test: `IrminClientIntegrationSpec` pins
a refined `CommitHash`, message and instant on a read commit and `BranchRef.Main`
in the listing; `IrminCommitSpec` pins a wrong-length hash as
`WrongFormat(ValueFormat.CommitHash)`, a date that is not a whole number, one
outside an instant's range, and the accumulation across hash, date and message;
`CommitMessageSpec` pins a foreign message, an unrefinable segment and a
segment spelled otherwise than `render` spells it; `OpaqueTypesSpec` pins
`StoreBranch.fromString` on main, a scenario branch, a staging branch and a
foreign name.

2.2 Rows 42 to 47 and 38. The five `decodeValidated` functions and
`decodeStoredNode` of §3.6; `ErrorSubject.Tree` used by `RiskTree.fromNodes`
and `TreeMetadata.decodeValidated`; the three `create` defaults removed. The
browser's tree builder keeps its own subjects, per UES-D-5. The repository's listing accumulates the failed trees' refusals into one
`Rejected` at `Peer(Store)` whose elements are located at `Field(Tree(treeId), …)`
and `Field(ExistingNode(id), …)`; `TreeLoadFailure` and the join at
`RiskTreeServiceLive.scala` line 96 go, and the merge guard of row 22 reads the
same list into its `Conflict`'s diagnostics. `ScenarioMergeService` line 113
reports `StoredNodeKindDiffers`. Grep: `TreeLoadFailure`, `TreeLoadProblem`,
`requestField(` outside `common`'s request path. Test:
`RiskTreeRepositoryIrminSpec` pins that a stored leaf written with two bad
fields fails with `Rejected` at `Peer(Store)` carrying both, each at
`ExistingNode` of that node; that a stored leaf with a bad probability is a
leaf's refusal; that a listing holding that tree fails with the accumulated list
naming the tree at `Tree(treeId)`. `ScenarioMergeServiceSpec` pins a refused
stored node in a merge preview as `Rejected` at `Peer(Store)`.

2.3 Row 48. 48a to 48c and 48e become `NotFound` at `Peer(Store)` with the four
new kinds and `StoragePath` where the record names it. 48d: `writeTree` takes
the `RiskTree` and `ensureRootPresent` is deleted, per `UES-D-15`; a `RiskTree`
holds its root by construction, so nothing is left to check. 48f:
`MergeStagingRef.forScenario` returns `MergeStagingRef`
directly, so the refinement of a composition of two refined values goes, and
with it `ValueFormat.StagingBranchName` and its message, ruled 2026-11-10.
`writeTree` returns `IO[IrminError | Failure, Unit]` until step 3.1 deletes
`IrminError`, because `IrminClient.setTree` still fails with it. Ruled
2026-11-10: in this plan, a signature may carry an existing error type in a
union with `Failure` until the step that deletes that type, without its own
echo. Test:
`RiskTreeRepositoryIrminSpec` pins metadata-absent and nodes-absent as
`NotFound` with their kinds.

2.4 Rows 12, 53 to 55. `classify`'s `23505` branches, `toRecord` and the two
`parseInterval` forms of §3.6. The row types `WorkspaceRow` and
`WorkspaceTreeRow` carry their identifier and key-hash columns as `String`, so
that `QuillMappings` keeps its encoders only and a column whose text does not
refine is one of `toRecord`'s accumulated refusals, `Rejected` at
`Peer(Database)`, rather than an exception the mapping throws into the outage
channel. Row 55a: `WorkspaceStoreLive` line 113 builds
`Rejected` at `Service`, `(Field(Workspace, SeedEntityId), OutOfRange(ValueRange.SeedEntityId))`,
`RawValue(counter)`. Row 55b: `RiskTreeRepositoryInMemory` line 32 builds row 4's
value at `Client`. Row 55c: line 52 builds `NotSupported`, `(Header(Branch), Capability.Scenarios)`.
`reason.contains("seed_entity_id")` goes. Grep: `RepositoryFailure`,
`reason.contains`. Test: `WorkspaceStorePostgresSpec` drives the real database:
two workspaces with one `seedEntityId` yield row 11's `Rejected` at `Client`; a
row whose interval text does not parse fails with `Rejected` at `Peer(Database)`
at `Field(Workspace, Ttl)`. `WorkspaceStoreSpec` pins row 55a.

2.5 Row 32. The SpiceDB request records, `Permissionship` and the parse arms of
§3.6; `listAccessible` and `ResourceId` go, with their unit and integration
tests. Test: `AuthorizationServiceSpiceDBSpec` pins an unparseable check
response as `Rejected` at `Peer(Authorization)` with `Unreadable(Json)`, a
permissionship outside the set as `Rejected` there with `Unreadable(Declared)`,
and a conditional permissionship as `AuthForbidden`.

Acceptance: compile, the four tiers, BATS suite A. `RepositoryFailure` is gone,
so `DomainError` has lost its string-carrying leaves.

### Phase 3 — conflicts and the compare-and-set outcomes

Rows 18 to 25 and 49 to 51. Old leaves deleted: `DataConflict`,
`ScenarioHeadStale`, `MergeConflict`, `MergeAlreadyRunning`, `MergeTargetMoved`,
`SimulationNotCached`, `VersionConflict`, `BranchAlreadyExists`, `BranchHeadStale`,
`IrminMergeConflict`, and with the last three the trait `IrminError`.

3.1 Rows 49 to 51. `IrminWriteOutcome`, `IrminMergeOutcome` and the four
client signatures of §3.5. `IrminClient` is now `IO[Failure, A]` throughout.
Grep: `BranchAlreadyExists`, `BranchHeadStale`, `IrminMergeConflict`,
`IrminError`. Test: `IrminClientIntegrationSpec` pins `createBranchAt` on an
existing branch as `Refused(head)` and `mergeCommit` of conflicting commits as
`Conflicted`.

3.2 Rows 18, 19, 23, 24. `ScenarioServiceLive` and `ScenarioMergeService` match
on the outcome and build `Conflict` at `Client` with `ConflictKind.Rejected()`,
`ScenarioHeadStale(name)` with `ExpectedHead` and `ActualHead` diagnostics,
`MergeAlreadyRunning(name)` and `MergeTargetMoved(name)`. Grep: `DataConflict`,
`ScenarioHeadStale(` as a constructor outside `ConflictKind`, `MergeAlreadyRunning(`
and `MergeTargetMoved(` likewise. Test: `ScenarioServiceLiveSpec` and
`ScenarioMergeServiceSpec` pin each outcome's `Conflict`.

3.3 Rows 20 to 22. `MergeConflict` becomes `Conflict` at `Client` with
`MergeRefused(name, refusal)`; row 21 carries `PeerMessage(Irmin's reason)`; row
22 carries the guard's accumulated refusals, already a `Rejected` list from
Phase 2.2, rendered into diagnostics. Grep: `MergeConflict`. Test:
`MergeConflictRuleSpec` and `ScenarioMergeServiceSpec` pin the three.

3.4 Row 25 and the dead `VersionConflict`. The `mapError` at
`QueryServiceLive.scala` line 113 sends every failure of `ensureCachedAll` to
`SimulationNotCached`, which the wire renders as 409 `SimulationRequired`. A
cache miss simulates, in `CachedResultResolverLive.rawLeafResult`, and nothing
records whether a tree was ever simulated, so no state of the service requires
a prior simulation and the 409 misreported the refusals of rows 56, 57, 58a and
58b. The `mapError` is deleted and each failure keeps its own row's
classification. `SimulationNotCached` then has no producer and is deleted, and
`ConflictKind.SimulationRequired` with it: its arm in `WireError.fromAppError`
(`WireError.scala` line 197) and its arm in `ErrorMessages.scala` (line 243)
go. `VersionConflict` has no producer and is deleted; `ConflictKind.StaleVersion()`
stays on the wire for `PLAN-TREE-WRITE-CONCURRENCY.md`, which declared it.
Grep: `SimulationNotCached`, `SimulationRequired`, `VersionConflict`. Test:
`QueryServiceLiveSpec` pins that an `ensureCachedAll` failure reaches the caller
as the resolver's own `Failure`.

Acceptance: compile, the four tiers, BATS suites A and C.

### Phase 4 — absence, expiry, denial, capability

Rows 9, 14 to 17 and 27 to 29. Old leaves deleted: `WorkspaceNotFound`,
`TreeNotInWorkspace`, `TreeAbsentAtRevision`, `WorkspaceExpired`, `AccessDenied`,
`AuthForbidden`, `ScenariosNotSupported`, and with `AuthForbidden` the trait
`AuthError`.

4.1 Rows 14 to 17. `WorkspaceStorePostgres`, `WorkspaceStoreLive`,
`WorkspaceStore`, both repositories, `RiskTreeServiceLive`, `QueryServiceLive`
and `WorkspaceTreeController` build `NotFound` at `Client` with the kinds and
diagnostics of record §9 rows 14, 16 and 17, and `Expired` for row 15 with
`ExpiryDetail(cause, ttl, idleTimeout)` and `WorkspaceRef`, `ExpiredAt`. The
projection's `BranchChoice.fromBranchRef` fold moves into the construction site,
because the site holds the `WorkspaceId` and the projection must not. Grep:
`WorkspaceNotFound`, `TreeNotInWorkspace`, `TreeAbsentAtRevision`,
`AppWorkspaceExpired`. Test: `WorkspaceStoreSpec`, `WorkspaceStorePostgresSpec`
and `RiskTreeReadConsistencySpec` pin the four; `GlobalErrorSpec` is unchanged
because the wire is.

4.2 Rows 28 and 29. `UserContextExtractor` builds `AccessDenied` at `MeshHeader`,
`(Header(UserId), DenialKind.IdentityAbsent)`; `AuthorizationServiceSpiceDB`
builds `AccessDenied` at `Peer(Authorization)`, `(Whole, PermissionNotHeld)`,
`Principal(user, permission, resource)`. `AuthorizationService`, its stubs and
`BootstrapProvisioner` are `IO[Failure, A]`. Grep: `AuthForbidden`,
`AppAccessDenied`, `AuthError`. Test: `UserContextExtractorSpec` and
`AuthorizationServiceSpiceDBSpec` pin each.

4.3 Rows 9, 27 and 55c. The two `NotSupported` services and the in-memory
repository build `NotSupported` at `Environment` with `Capability.Scenarios` and
`Capability.PinnedReads`; row 27 carries `PeerMessage(reason)` where today's
`reason` was the operator's sentence. Row 9 moves from 422/400 to 501 (§5).
Grep: `ScenariosNotSupported`, `RequiresIrminBackend`. Test:
`RiskTreeRepositoryInMemoryBranchSpec` pins a pinned read as `NotSupported`;
`ScenarioControllerSpec` pins 501 for row 27 as today.

Acceptance: compile, the four tiers, BATS suite C.

### Phase 5 — the client's refusals and the service's own checks

Rows 2 to 8, 10, 11, 13, 26 and 56 to 59. Old leaves deleted: `ValidationFailed`,
the seven `FolQueryFailure` leaves and the trait, and `SimulationFailure`.

5.1 Rows 2 to 8, 10, 11 and 13 already build `Rejected` at `Client` through
`toZIOValidation(TrustZone.Client)` since Phase 0.3. This step deletes
`ValidationFailed` and its arm, and narrows every signature whose only
`AppError` was `ValidationFailed`. Grep: `ValidationFailed`. Test: the existing
controller specs pin 422 and 400 unchanged.

5.2 Row 26. `FolQueryFailure.fromQueryError` becomes
`QueryProblem.fromQueryError(err: QueryError): Option[QueryProblem]`, `Some` for
the five engine refusals the caller can act on and `None` for the wiring faults
step 5.3 handles. The five, read in the engine's sibling checkout:
`ParseError` → `Unparseable(message, position)`. `ValidationError` →
`Unparseable(message, None)`, because the engine raises it from
`ParsedQuery.mk`'s two rules on the query's own variables, which
`VagueQueryParser.parse` catches and returns, and from a missing answer tuple,
which step 5.3's request-boundary check makes unreachable. `BindError` →
`UnknownReferences(messages)` when every detail is an unresolved node
reference and `TypeCheckFailed(messages)` otherwise, as today.
`UnknownConstantOrLiteralError` → `UnknownReferences(List(message))`; it is
reachable through a quoted literal in the mitigation slot, such as
`p95(x, "foo")`, because `RiskTreeKnowledgeBase` has no literal validator for
that sort, so the comment calling it unreachable is deleted.
`DomainNotFoundError` → `NotQuantifiable(message)`. `QueryController` and
`QueryServiceLive` build `Rejected` at `Client`, `(Whole,
FieldProblem.Query(problem))`. The projection emits `RejectionDetail.Query(problem)`
on a route with a tree, so the wire is unchanged (`UES-D-7`). Grep:
`FolParseFailure`, `FolUnknownReference`, `FolBindFailure`,
`FolDomainNotQuantifiable`. Test: `FolQueryFailureFromQueryErrorSpec` is
rewritten over the new function and pins `ValidationError` as `Unparseable`;
`QueryServiceLiveSpec` pins a parse failure as `Rejected` with `Query(Unparseable(…))`.

5.3 Row 59. 59a: `FolModelValidationFailure` becomes a `require` at
`QueryServiceLive.scala` line 125, where `FolModel(kb.catalog, kb.model)` pairs
the catalog with the runtime model. The knowledge base is built per query from
the tree, so there is no startup site; the catalog and the model come from one
`RiskTreeKnowledgeBase.schemaFor(tree)` call, which is this service's wiring.
59b: every cause `FolEvaluationFailure` is built from was read in the engine's
sibling checkout (`UES-D-17`). Two are the caller's. A query naming answer
variables, the `(y)` suffix `VagueQueryParser.parseAnswerVars` reads, is refused
today by the engine's `validateAnswerTuple`, because register passes
`answerTuple = Map.empty` at line 136 and has no answer-variable support;
`QueryRequest.resolve` becomes `Either[QueryProblem, ParsedQuery]` and answers
`QueryProblem.AnswerVariablesNotSupported(parsed.answerVars)` when that list is
non-empty, before any knowledge base is built. A query breaking
`ParsedQuery.mk`'s two variable rules is `Unparseable` through step 5.2. Every
other cause is a wiring fault: the dispatcher `Left`s in
`RiskTreeKnowledgeBase.scala` (lines 163, 479 and 488) become
`EvaluationError`, and a `typed_term` sort mismatch and `UnboundVariableError`
are ruled out by the binder. So `FolEvaluationFailure` is a defect answered by
`DefectCatchAllInterceptor`, and `Diagnostic.EnginePhase`, which would have
carried its `phase`, is not added. `RejectionDetail.Query`'s comment counting
`QueryProblem`'s cases is corrected, and `ErrorMessages.query` gains the arm.
Grep: `FolQueryFailure`, `FolModelValidationFailure`, `FolEvaluationFailure`,
`EnginePhase`. Test: `QueryServiceLiveSpec` pins the `require` on a model built
from a tree, and pins a query with `(y)` as `Rejected` with
`Query(AnswerVariablesNotSupported(List("y")))`; `ErrorMessagesSpec` pins the
arm's text.

5.4 Row 56. `fitCheck` of §3.6 in `RiskTreeServiceLive`; `Simulator.scala` line
197 becomes a `require`. Test:
`RiskTreeServiceLiveSpec` pins a create with an unfit metalog as `Rejected` at
`Client` at `Field(NodeAtIndex(Leaves, i), DistributionType)` accumulated with a
second leaf's error; `SimulatorSpec` pins that an unfit leaf at simulation is a
defect.

5.5 Rows 57 and 58. Row 57's four checks in `CachedResultResolverLive` stay and
build `Rejected` at `Service` with their present locations and problems
(`UES-D-15`). Row 58a builds `Rejected` at `Peer(Store)`,
`(Field(ExistingNode(id), AggregatedLoss), AggregatedLossOverflow)`; 58b stays
`Rejected` at `Client`. The resolver's `fromValidation` takes the zone, as
`toZIOValidation` does. Grep: none beyond `ValidationFailed` in 5.1. Test: the
resolver's suite pins row 57's and 58a's zones.

Acceptance: compile, the four tiers, BATS suites A and C.

### Phase 6 — `AppError` goes

6.1 `AppError`, `DomainError`, `SimulationFailure` and `FailureOrigin`'s per-leaf
declarations are deleted; `FailureOrigin` stays as the derived two-case enum.
`WireError.fromAppError` is deleted and `Failure.toWire` is the projection.
`WireEither` and every signature narrow to `Failure`. The narrowing collapses
each match on `AppError | Failure` that only reads the log text to `logMessage`
on `Failure`, so no function is added for it. Those matches are in
`Application.scala`, `RiskTreeService.scala`, `RiskTreeServiceLive.scala`,
`ScenarioService.scala`, `WorkspaceReaper.scala`, `HttpTestHarness.scala` and
`ScenarioControllerSpec.scala` (ruled 2026-11-10). `TreeScope.scala` and
`QueryRequest.scala` comments that name `AppError` are rewritten. Grep:
`AppError`, `fromAppError`, `DomainError`. Test: `WireErrorSpec`'s old exhaustive
assertion is deleted and `FailureSpec`'s Phase 0.2 one is the exhaustive assertion.

6.2 The records and documents. ADR-010 §1, ADR-002 §5 and ADR-035 §1 are
amended to the new shape (§7). `docs/dev/ARCHITECTURE.md`'s "DTO/Domain Model
Separation" section describes `Failure` and the projection. The glossary gains
the entry §8 proposes. `.claude/skills/adr-constraints/SKILL.md`'s ADR-039 ×
ADR-010 row names `Unanswered` and `Rejected`. The amendments to accepted
records wait on the user's go.

Acceptance: compile, the four tiers, BATS suites A, B and C. The plan closes on
this phase, which is a MINOR bump because §5's statuses are external API changes.

---

## 5. Observable wire changes

No `WireError` case or codec changes. These statuses move because a row is
reclassified, and each is a BATS assertion to update:

| Row | Today | Under this plan | Phase |
|---|---|---|---|
| 9 | 422/400 `RequestRejected` with `RequiresIrminBackend` | 501 `NotSupported(PinnedReads)` | 4.3 |
| 55c | 500 `InternalFault` | 501 `NotSupported(Scenarios)` | 2.4 |
| 32 | 503 `DependencyUnavailable` | 500 `InternalFault` | 2.5 |
| 33 to 36 via `RiskTreeRepositoryIrmin`, `TreeHistoryService` | 500 `InternalFault` | 503 `DependencyUnavailable` | 1.1 |
| 37 | 503 or 500 | 500 | 1.2 |
| 52, an outage | 500 | 503 | 1.4 |
| 52, a unique violation on another constraint | 500 | 500, now a `Conflict` in the log | 2.4 |
| 25 | 409 `SimulationRequired` | unreachable: the `mapError` is deleted and each `ensureCachedAll` failure keeps its own row's status | 3.4 |
| 26, a query breaking `ParsedQuery.mk`'s variable rules | 500 `InternalFault` | 422 `Query(Unparseable)` | 5.2 |
| 59b, a query naming answer variables | 500 `InternalFault` | 422 `Query(AnswerVariablesNotSupported)` | 5.3 |
| 56 | 422 at simulation | 422 at create and update; unreachable at simulation | 5.4 |
| 57 | 422 | 500, `Rejected` at `Service` | 5.5 |
| 58a | 422 | 500 | 5.5 |
| 49 to 51 untranslated | 500 at INFO | unreachable: a compile error | 3.1 |

The published schema gains the enum cases `FieldName.Ttl`, `FieldName.IdleTimeout`,
`ExpectedInput.Json`, `ExpectedInput.Interval`, `ValueFormat.StoreSegment`,
`FieldProblem.Query`, `FieldProblem.StoredNodeKindDiffers`, four `NotFoundKind`
cases, `Capability.PinnedReads` and `QueryProblem.AnswerVariablesNotSupported`.
No client is sent any but `Capability.PinnedReads`,
`QueryProblem.AnswerVariablesNotSupported` and `FieldProblem.Query`'s wire form,
which is `RejectionDetail.Query` unchanged. Four cases are removed:
`Other` from `HistoryOperation` in Phase 2.1, `StagingBranchName` from
`ValueFormat` in Phase 2.3, `RequiresIrminBackend` from `FieldProblem` in
Phase 4.3 and `SimulationRequired` from `ConflictKind` in Phase 3.4.
`StagingBranchName` had one producer, `MergeStagingRef.forScenario`, which no
longer refines, and no client can be sent it, because a staging branch name
embeds the `WorkspaceId` and is never accepted from a client. `ErrorMessages` loses the `SimulationRequired`
arm and gains the `AnswerVariablesNotSupported` arm.

---

## 6. Decisions

### UES-D-1 — one shape, not a type tree — RULED 2026-11-09

`Core[Z, P]` with `zone`, `errors` and `diagnostics`, inside a flat `Failure`
whose variants are the wire's answerable kinds; `InternalFault` is a projection,
not a variant. `AppError`, its four sub-traits and 32 leaves do not exist in
the shape. The refused alternatives were a shared parent trait with the zone on
each leaf, which kept the 32-way match and the per-leaf `origin`; and a single
record with every field, which admitted the cross-product's meaningless states
(`docs/user/TERMINOLOGY.md` Part 6). `Z` and `P` cut both sums per variant.

### UES-D-2 — the zones — RULED 2026-11-09

`TrustZone` is flat: `Client`, `Environment`, `MeshHeader`, `Peer(PeerZone)`,
`Service`; `PeerZone` is `Store`, `Database`, `Authorization`. The refused
alternative was a two-level `External | Internal` grouping, which added a level
no derived value reads.

### UES-D-3 — diagnostics are a typed closed set — RULED 2026-11-09

`List[Diagnostic]`, each case fixing its key and value type, replaces
`logAnnotations: Map[String, String]` (ADR-037). The refused alternative kept the
map, under which a site could log a value under a wrong key.

### UES-D-4 — compare-and-set outcomes are return values — RULED 2026-11-09

`IrminWriteOutcome` and `IrminMergeOutcome` are what the four client methods
return; the scenario services build the caller's `Conflict`. `Conflict` at
`Peer(Store)` has no producer. The refused alternative kept the three outcomes
as failures at `Peer(Store)` translated by the caller, which left a skipped
translation reachable as a 500 at INFO.

### UES-D-5 — a tree's own fields are located at `Tree(treeId)` — RULED 2026-11-09

`ErrorSubject.Tree(treeId)` is added. `RiskTree.fromNodes` reports its tree-own
fields there for a request and for a stored tree alike; `RiskLeaf.create`,
`RiskPortfolio.create` and `createFromStrings` lose their `ErrorSubject.Request`
default. Ruled in EWB as its decision 74, form A1, and restated here because
it is not yet implemented.

The browser's tree builder is outside this decision, ruled 2026-11-10. It keeps
its own subjects and is not aligned with the server's locations. The builder's
checks are a client-side aid that lets a user of the official tools submit
well-formed data, and it marks a refused input through its own signals. The
server's validation is the defensive layer, and its messages are written for a
caller using the interface directly. Neither side is relaxed or reshaped to
match the other. The earlier clause "the browser's tree builder reads
`Tree(id)`" is withdrawn. A new tree has no `TreeId` in the browser, because the
server assigns one on create. The server also reports a tree's name at the
request rather than at `Tree(id)`.

### UES-D-6 — the SpiceDB border is in scope — RULED 2026-11-09

An unparseable SpiceDB response is `Rejected` at `Peer(Authorization)`, not an
outage. Ruled in EWB as its decision 73 and restated here.

### UES-D-7 — a refused query is a `Rejected` carrying the wire's `QueryProblem` — RULED 2026-11-09

`FieldProblem.Query(problem: QueryProblem)`, and the projection emits
`RejectionDetail.Query(problem)` so the wire is unchanged. The refused
alternative, a `Rejected` whose location and problem restate the engine's
message in new `FieldProblem` cases, changed the wire for no gain.

### UES-D-8 — the distribution fit runs before the write — RULED 2026-11-09

Per leaf, as the last step of that leaf's own statement validation, accumulated
with the other errors, in `RiskTreeServiceLive` because the fitters are
JVM-only. At simulation the fit is a `require`: the store holds no tree written
before the check, so every stored leaf has passed it. The user dismissed the
counter-argument that a fitter library upgrade could make a stored leaf unfit:
testing upgrades is the operator's.

### UES-D-9 — the rule for this service's own checks — RULED 2026-11-09

A check that builds an aggregate, a type or an instance within one is a
`Rejected` at `Service`. A condition analysis says cannot hold at runtime is a
`require`, kept as defence in depth (ADR-033 §5), and its violation is a defect.
A `require` inside an aggregate's own type is first a prompt to ask what the
domain lacks. Applied in the record §9 to rows 48f, 55a and 59a; rows 48d and
57 are settled by `UES-D-15` instead.

### UES-D-10 — the bridge is a union type — RULED 2026-11-09

`IO[AppError | Failure, A]` on every signature on a moved row's path until its
last `AppError` producer is gone. The refused alternative was moving every row
in one change, which the record's sixty rows and 233 signatures made a change
no review could read.

### UES-D-11 — steps are grouped by the leaf they delete — RULED 2026-11-09

A step's rows are the rows that build one old leaf, so the leaf's name is the
grep target; the two large leaves are split by zone and deleted in the group's
last step. The refused alternative, one step per row regardless of leaf, forced
a leaf's deletion into whichever step happened to be last and left the
intermediate steps with no grep target.

### UES-D-12 — the observable wire changes — RULED 2026-11-09

The table in §5, as a consequence of the record §9's mapping, which the user
approved row by row. Each row's new status is the one its variant and zone
derive.

### UES-D-13 — where the shape lives — RULED 2026-11-09

**Ruled 2026-11-09: Option A, the server module.** Two consequences follow
from the ruling. The projection is `Failure.toWire(trace, scope)`, a method on
the source as ADR-038 §5 requires, because `common`'s `WireError` cannot name a
server type. `ValidationExtensions` moves to `server` in Phase 0.3. The option
text below is kept as presented.

**Goal.** `Failure` and `Diagnostic` need a module. Today `AppError` is in
`common`, cross-compiled, and the browser never reads it: `GlobalError.scala`
reads `WireError`. No `common` production code builds an `AppError`;
`ValidationExtensions` is the one `common` site that names one, and it is an
extension the server calls.

**Option A — the server module, package `com.risquanter.register.domain.errors`
under `modules/server`.** `Diagnostic.Principal` can name `Permission` and
`ResourceRef`, which are server types, and `StoragePath` can name `IrminPath`.
`WireError.fromFailure` moves to the server with it, and `ValidationExtensions`
moves too. You read a denial's log line: it carries the permission and resource
as typed fields rendered by `Diagnostic.render`. The cost is that the error
vocabulary is split across two modules: the wire enums in `common`, the failure
shape in `server`.

**Option B — `common`, beside `WireError`.** One module owns the whole error
vocabulary, as today. `Diagnostic.Principal` cannot name server types, so the
denial's diagnostics are `RawValue` strings for the permission and resource, and
`StoragePath` holds the path's text. You read the same log line: the same keys,
with values rendered to text at the construction site rather than by the enum.
The cost is three diagnostics typed as `String` where a type exists, which the
algebraic-design rule flags.

**Recommendation, the plan author's: Option A.** The browser has no use for
`Failure`, and three untyped diagnostics is the kind of widening the plan
exists to remove.

### UES-D-14 — the final names — RULED 2026-11-09

**Ruled 2026-11-09: the names below are final as written.**

`Failure`, `Core`, `TrustZone.Service`, `Diagnostic`, `ExpiryDetail`, the two
`DenialKind` cases and the four `NotFoundKind` cases are placeholders. The user
names them before Phase 0 writes them. The plan's grep targets are the old
leaves, so no step depends on the choice.

### UES-D-15 — whether the smells behind the `require`s are fixed in this plan — RULED 2026-11-09

**Ruled 2026-11-09, row 48d.** `writeTree` takes the `RiskTree` in place of its
`nodes` and `mitigations`, and `ensureRootPresent` is deleted rather than turned
into a `require`: a `RiskTree` has a node carrying its `rootId` by construction
(`RiskTree.fromNodes`, the `RootIdNotFound` predicate), so a caller handing the
write a root from one tree and nodes from another cannot be written. Phase 2.3
carries it. The signature:

```scala
private def writeTree(base: IrminPath, meta: TreeMetadata, tree: RiskTree, message: CommitMessage, branch: BranchRef): IO[Failure, Unit]
```

**Ruled 2026-11-09, row 57.** The resolver's four lookups at
`CachedResultResolverLive.scala` lines 120, 150, 155 and 185 stay as typed
refusals: neither the walk's representation nor the checks change, because a
`require` in their place saves nothing worth the work. They move onto the shape
like every other refusal, as `Rejected` at `Service`, since a miss is this
service's own computation disagreeing with its own index and not the caller's
input; that zone is the plan author's derivation from `UES-D-9`'s first clause
and is reversible. Row 48f's smell is fixed in its step because the fix removes
the check.

The refused alternatives were making the walk total, which changes the tree
representation the cache key is computed over, and four `require`s with the
refactor routed to the backlog.

### UES-D-16 — where the one-time store pass runs — WITHDRAWN 2026-11-09

**Withdrawn 2026-11-09.** The user stated that the store holds no tree written
before the fit check, so the pass has nothing to check and no home is needed.
The simulator's fit becomes a `require` in Phase 5.4. The option text below is
kept as presented.

**Goal.** `UES-D-8` makes the simulator's fit a `require` after a one-time pass
has asserted every stored leaf fits. The pass is an operation over every tree
in the store, and it needs a home.

**Option A — a startup check behind a flag.** `Application` runs the pass when
`REGISTER_VERIFY_STORED_FITS=true`, logs every unfit leaf at WARN, and exits
non-zero if any. The operator runs it once after upgrading. You set the flag,
start the server, read the log. The cost is a startup path that exists for one
run.

**Option B — a step in `PLAN-BRANCH-HISTORY.md` or a maintenance plan that owns
store-wide operations.** This plan ships the `Rejected` at `Peer(Store)` in the
simulator and leaves the `require` for that plan. The cost is that the row stays
typed until another plan lands, and `UES-D-8`'s last clause is deferred to work
not yet planned, which the global rules allow only as a prerequisite-gated
continuation.

**Recommendation, the plan author's: Option A**, so the plan closes whole.

### UES-D-17 — row 59b, after the check — RULED 2026-11-09

**Ruled 2026-11-09: Option B.** The check ran against the engine's sibling
checkout. Two causes are the caller's and the rest are wiring faults; step 5.3
states each. The caller's answer-variable case is refused at the request
boundary with `QueryProblem.AnswerVariablesNotSupported(variables)`, a
`QueryProblem` case and not a `FieldProblem` one, because `UES-D-7` puts every
query refusal in the wire's `QueryProblem`. Answer-variable support is not
built in this plan: the engine returns no answer set, so the enumeration would
be register's to build, and that is a feature with its own design. It is
recorded in `docs/dev/plans/PLAN-QAV-QUERY-ANSWER-VARIABLES.md`, written
2026-11-09 as a record of the current understanding, and ADR-028's appendix
keeps its sub-portfolio example with one sentence saying the service does not
yet serve it. Option A, the same refusal with the example removed, was not
chosen because the example is the only statement of the use case. The option
text below is kept as presented.

The step first reads every cause `FolEvaluationFailure` is built from in the
engine's sibling checkout, `/home/danago/projects/vague-quantifier-logic/core/src/main/scala/vql/`.
If every reachable cause is a bug, the row is a defect and no decision is
needed. If an evaluation can fail for a domain cause, the row is `Rejected` at
`Client` with a new `FieldProblem` case, and the case's name and location are
presented then. Ruled when the check has run.

Row 1, the decode-failure handler, is out of this plan's scope by the user's
ruling of 2026-11-09 and stays as it is.

---

## 7. ADR alignment

| ADR | What this plan touches | Status |
|---|---|---|
| ADR-010 (error handling) | §1's sealed hierarchy, §4's channel per layer, §5's HTTP mapping | **Amended.** §1 lists `AppError` with four sub-traits and five leaves; it will state `Failure`, `Core` and `TrustZone`. §4 and §5 hold unchanged in substance: the channel stays typed, the mapping stays a one-way projection, now `Failure.toWire`. The "String Matching on Errors" smell is what rows 12 and 39 to 48 violate and this plan closes. The amendment waits on the user's go. |
| ADR-002 (logging) | §5's typed hierarchy and `AppError.origin` | **Amended.** §5 shows `ValidationFailed(errors)` and names `AppError.origin` as the level's source; it will show `Failure.origin` derived from the zone. The level rule is unchanged. Waits on the user's go. |
| ADR-035 (error leakage) | §1's exhaustive projection | **Amended.** §1 says the match is over the 32 leaves and a new `AppError` case is a compile error; it will say the projection is total over `(variant, zone)` and a new variant or zone is a compile error. §2 and §3 hold: nothing new reaches the wire, `zone` and `diagnostics` are dropped on every arm, and `Diagnostic.RawValue` is the one place a peer's text is kept, which §3 permits in a log. Waits on the user's go. |
| ADR-039 (trust-boundary ingress) | every border row | **Complied with, and the record is amended.** §4 asks that a rejection name the boundary, the location and the list; `Core` carries `zone`, `errors` and nothing composed. §4 also says "The concrete error type is declared by the plan that implements this record", which is this plan. The record's former §5, which let a comment at a site admit a boundary value unrefined, is deleted: it let a site waive §1's rule on an unfalsifiable claim about foreign content, and every value that enters an aggregate is refined instead. §1 gains the sentence stating that. |
| ADR-024 (externalised authorization) | the enforcement-point interface | **Amended.** The interface listing and the implementation table name `check` alone; `listAccessible` is removed with no caller and the plan's L1.4 records when and in what shape a lookup returns. |
| ADR-033 (exception boundaries) | the `require`s of rows 48d, 48f, 59a and the simulator's fit (row 56) | Compliant. §5 admits a `require` "when the failing state is unreachable through validated entry points", which `UES-D-9` restates as the rule. |
| ADR-037 (constants and closed sets) | `Diagnostic`, `OutageMode`, `DenialKind`, every new enum case | Compliant: each closed set is an enum in its owner's file, no spelling is hand-written where the serialiser derives it, and `Diagnostic.render`'s keys are the one declared spelling because a log key is a contract with the log pipeline. |
| ADR-036 (confidential identifiers) | `Diagnostic.WorkspaceRef`, `BranchChoice.fromBranchRef` | Compliant. A `WorkspaceId` appears only in a diagnostic, which never crosses; the projection's fold that read a `WorkspaceId` moves to the construction site so the projection never holds one. |
| ADR-038 (response wire shapes) | the wire enums' new cases | Compliant. No response shape changes; the cases are declared on the enum that owns them with derived codecs. |
| ADR-004a and ADR-007 (persistence, branches) | the compare-and-set outcomes as return values | Compliant. The single-writer rule and the branch operations are unchanged; what changes is that a refused compare-and-set is an answer the caller reads rather than a failure it catches. |
| ADR-028 (query pane) | rows 26 and 59 | **Amended.** The appendix's sub-portfolio example says "Answer variable `y` projects matching portfolio IDs"; after Phase 5.3 the service answers 422 `AnswerVariablesNotSupported` to it, so the example gains one sentence saying the service does not yet serve answer variables and naming `PLAN-QAV-QUERY-ANSWER-VARIABLES.md`. The engine's rendered messages still reach the pane through `QueryProblem`. Waits on the user's go. |

`.claude/skills/adr-constraints/SKILL.md`'s Layer 3 row "ADR-039 × ADR-010"
names the two failures as "the peer is unreachable" and "the peer returned
content we refuse"; after Phase 1 it names them `Unanswered` and `Rejected`.

---

## 8. Terminology impact

`docs/user/TERMINOLOGY.md` §8.1 defines a trust boundary as "the line a value
may not cross without being checked". No entry is made stale. One entry is
missing: the plan's shape carries the side of a boundary a value came from, and
the glossary has no term for it. The note, for approval before the glossary is
edited:

```
TERMINOLOGY NOTE — approval needed before the glossary is edited

term:      8.1 Trust boundary
current:   "A **trust boundary** is the line a value may not cross without being
           checked."
motivated by: UES-D-2, which makes the side a value came from a field of every
           failure.
proposed:  add after the existing paragraph: "The region on either side of a
           boundary is a **trust zone**. A value's zone is the side it came
           from: the client, the deployment's environment, the mesh, a peer this
           service calls, or this service itself. Every failure the service
           records names the zone of the value that failed, and that zone is
           what decides whether the failure is the caller's to fix."
```

---

## 9. File inventory

The user creates `docs/dev/plans/PLAN-UES-UNIFIED-ERROR-SHAPE-INVENTORY.md`.
The paths below are what the phases need. Paths marked `new:` do not exist yet;
their directory follows `UES-D-13`.

**Created**

- `new: modules/server/src/main/scala/com/risquanter/register/domain/errors/Failure.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/domain/errors/TrustZone.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/domain/errors/Diagnostic.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/domain/errors/OutageMode.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/domain/errors/DenialKind.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminWriteOutcome.scala`
- `new: modules/server/src/main/scala/com/risquanter/register/domain/errors/ValidationExtensions.scala`
- `new: modules/server/src/test/scala/com/risquanter/register/domain/errors/FailureSpec.scala`

**Modified, `common`**

- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/TreeLoadProblem.scala` (deleted in Phase 2.2)
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationExtensions.scala` (deleted in Phase 0.3)
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/WireError.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/NotFoundKind.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/Capability.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/TreeScope.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/QueryProblem.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/ConflictKind.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/errors/RejectionDetail.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/QueryRequest.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/responses/TreeHistoryResponse.scala`
- `modules/common/src/test/scala/com/risquanter/register/http/errors/WireErrorSpec.scala`

**Modified, `server`**

- `modules/server/src/main/scala/com/risquanter/register/Application.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationServiceNoOp.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationServiceSpiceDB.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationServiceStub.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisioner.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerNoOp.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerSpiceDB.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerStub.scala`
- `modules/server/src/main/scala/com/risquanter/register/auth/UserContextExtractor.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WireEither.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/QueryController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala`
- `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceTreeController.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/CommitMessage.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/persistence/QuillMappings.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminCommit.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala`
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminPath.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala`
- `modules/server/src/main/scala/com/risquanter/register/repositories/model/TreeMetadata.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ChangedNodesService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioBranchOps.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeServiceNotSupported.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceNotSupported.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStoreLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala`

**Modified, `app`**

- `modules/app/src/main/scala/app/state/TreeBuilderState.scala`
- `modules/app/src/main/scala/app/state/GlobalError.scala` (the comment naming `AppError`)
- `modules/app/src/main/scala/app/views/ErrorMessages.scala`

**Tests reached.** `server`'s own tests are covered by the module's `src/main`
listing. `server-it` has no `src/main`, so these are named:

- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminMergeSemanticsSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/auth/AuthorizationServiceSpiceDBItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/http/HttpTestHarness.scala`
- `modules/app/src/test/scala/app/state/TreeBuilderStateSpec.scala`

**Documents**

- `docs/dev/decision-records/ADR-010.md`
- `docs/dev/decision-records/ADR-002.md`
- `docs/dev/decision-records/ADR-035-error-leakage-prevention.md`
- `docs/dev/decision-records/ADR-039-trust-boundary-ingress-validation.md`
- `docs/dev/decision-records/ADR-024-externalized-authorization-pep-pattern.md`
- `docs/dev/plans/AUTHORIZATION-PLAN.md` (the lookup's removal and the L1.4 decision it waits on)
- `.claude/rules/security.md` (the enforcement-point interface)
- `docs/dev/ARCHITECTURE.md`
- `docs/user/TERMINOLOGY.md`
- `.claude/skills/adr-constraints/SKILL.md`
- `tests/bats/suite-a-full-prod.bats` and `tests/bats/suite-c-in-memory.bats` (the §5 statuses)
- `build.sbt`, `.env`, `.env.irmin` (the version bump each landed step requires)

---

## 10. Verification plan

After every step:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm
sbt serverIt/test
grep -rn '<the step's targets>' modules/
```

Each `sbt` command ends in `[success]` with no failed test, and the grep prints
nothing. After every phase, with the images rebuilt at the current version one
after the other (register-dev skill, "Build the application images before
suites A and C, one at a time"):

```bash
docker compose -p register down -v --remove-orphans 2>/dev/null || true
run_bats tests/bats/suite-c-in-memory.bats
run_bats tests/bats/suite-a-full-prod.bats
```

Both end with every test `ok`. Phase 6 also runs suite B. The final grep, at
the plan's close:

```bash
grep -rn 'AppError\|fromAppError\|DomainError\|IrminError\|AuthError\|FolQueryFailure\|ValidationFailed\|RepositoryFailure' modules/
```

prints nothing.

The behavioural tests are the ones each step in §4 names. Three properties they
pin together: a refusal accumulates (a single-error assertion would pass against
a `.flatMap` rewrite, so every refusal test writes two bad fields); a zone other
than `Client` answers `InternalFault` and logs at WARN; and the wire is unchanged
for every row §5 does not list.

---

## 11. Relationship to PLAN-ERROR-WIRE-BOUNDARY

EWB's phases 1 to 6 landed and the plan is closed with Phase 7 removed. What
this plan carries over from Phase 7: the six steps' intent, which is Phase 1.1
to 1.2 and Phase 2 here; the signatures of §3.6, rewritten from
`ValidationFailed(errors, zone)` to `Failure`; the inventory additions, which
§9 lists; and EWB's decisions 73 and 74, restated
as `UES-D-6` and `UES-D-5`. What is dropped: `enum TrustZone(val origin)` with
four cases, `ValidationFailed(errors, zone)`, the clause keeping three
`RepositoryFailure` sites and `ensureRootPresent`, the clause keeping
`TreeLoadFailure` with `TreeLoadProblem` gaining no case, the `IrminError |
ValidationFailed` and `AuthError | ValidationFailed` unions, and EWB's decision
72, whose substance, one refusal type carrying the zone as data, survives as
`Failure.Rejected` and whose name does not. Also dropped: the two exemptions
EWB recorded under ADR-039's former §5, the unrefined branch listing and the
skipped SpiceDB lookup ids, because every value that enters an aggregate is
refined (§2).

EWB's `EWB-D-6` listed the observable wire changes of its own phases; §5 is this
plan's list. EWB's `EWB-P-1`, the one-way projection, and `EWB-P-2`, the
message-composition split, are principles this plan keeps: `Failure.toWire` is
one-way and total, and `logMessage` is composed on the server while the browser
composes what a user reads from the wire case.
