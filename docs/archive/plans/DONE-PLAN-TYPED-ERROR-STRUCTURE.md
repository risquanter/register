# PLAN-TYPED-ERROR-STRUCTURE — typed error locations, typed error values, typed load problems

**Plan code:** `TES`. Decisions in this document are labelled `TES-D-<n>`.

**Status:** implementation-grade. All ten decisions are ruled — TES-D-1 to
TES-D-3 on 2026-10-05, TES-D-4 on 2026-10-07, and TES-D-5 to TES-D-10 on
2026-10-10, when the workspace-error work was folded in. One field inside
TES-D-9 awaits a word from the user: whether the configured workspace lifetime
reaches the client in the expiry response or through `/config.json`. §4.8 is
written for the first and names the second, and nothing else in the plan depends
on it. Phase 0 (§2) has
landed, and the merge guard half of Defect 1 below was closed by the staging-
branch merge that `PLAN-RISKTRANSFORM.md` §8.19.9 landed; everything else from
§3 onward is unwritten. The dependencies this plan has on other plans are
recorded in §11 and none of them blocks a start.

**Why this is its own plan rather than a continuation of
`docs/dev/plans/PLAN-RISKTRANSFORM.md`:** it spans `commonJVM`, `commonJS`,
`server` and `app`, it changes the error response contract that every endpoint
shares, and it is not the risk-transform feature. The connection between the two
is a dependency, not a shared scope: the merge guard added by
`PLAN-RISKTRANSFORM.md` §8.19 is what exposed the defect this plan fixes, and
§3 of this plan is what closes it.

---

## 1. What this plan is for

Three defects in how failures are represented, all found in the code-quality
review of the scenario-merge guard on 2026-10-05. They share one root cause, so
they are fixed together.

**Defect 1 — repository-internal text reaches clients, and one reachable string
carries the `WorkspaceId`.** `ScenarioMergeServiceLive.guardStagedState` collects
the failure text of every tree that would not load and forwards it to the caller
in `MergeConflict.details`, a free-text `String`.
`ErrorResponse.makeMergeConflictResponse` copies that string into the response
body verbatim. One reachable message is
`RepositoryFailure(s"Missing node value at ${fullPath.value}")`, where
`fullPath` is built from
`WorkspaceStoragePaths.treeNodes(wsId, treeId)` and therefore reads
`workspaces/<wsId>/risk-trees/<treeId>/nodes/<nodeId>`. ADR-036 forbids a raw
`WorkspaceId` in any value returned to a client. The protection that exists for
every other storage failure —
`ErrorResponse.makeRepositoryFailureResponse`, which discards its `reason` and
returns a constant `"Internal server error"` — is bypassed because this encoder
does not use it.

**The merge guard half of Defect 1 is already closed.**
`ScenarioMergeServiceLive.guardStagedState` sends the repository's own text to
`ZIO.logError` and builds its `MergeConflict` from tree identifiers and a count,
so no repository text reaches that encoder from the guard any more. What remains
is the typing problem: `MergeConflict.details` is still a free-text `String`,
so the next producer to put repository text in it reaches the client the same
way. §3.1 closes that by replacing the field with a `MergeRefusal` value.

**Defect 2 — `ErrorDetail.field` carries two unrelated meanings.** Its own
scaladoc defines it as the JSON path to the problematic field, and ADR-010 §2
defines the same meaning for `ValidationError.field`, the domain type it is the
wire form of. The scenario errors use it instead as a tag naming what kind of
row this is, so that `ErrorResponse.decode` can rebuild a typed error. Two
lines read it that way:

```scala
47:  val scenario        = details.collectFirst { case d if d.field == "scenario" => d.message }
82:  val conflictDetails = details.collectFirst { case d if d.field == "conflict" => d.message }
```

Of the two, `"scenario"` is arguably not a deviation: a scenario name is a
request field. `"conflict"` names nothing in any request.

Two coarser dispatches on `details.headOption.map(_.field)` at lines 89 and 130
have the same character. The path meaning is load-bearing: three places in the
single-page application resolve the value to a form control —
`modules/app/src/main/scala/app/views/FormSubmitUtil.scala:31`,
`PortfolioFormView.scala:325` and `RiskLeafFormView.scala:505` — so a row
carrying `field = "scenario"` is fed into a form-field lookup that cannot match.

**Defect 3 — a typed failure is flattened into prose at the point it is
produced.** `RiskTreeRepositoryIrmin.rebuildTree` takes the accumulated
`List[ValidationError]` that `RiskTree.fromNodes` produces and renders it to a
string:

```scala
.left.map(errors => RepositoryFailure(errors.map(e => s"[${e.field}] ${e.message}").mkString("; ")))
```

Every caller that wants to know which invariant broke in which tree has to parse
that sentence back, and the merge guard is such a caller.

**The common root cause.** An error carries a `String` where it could carry the
values it was built from. Each individual site then has to remember what may and
may not go into that string, and one site did not. Replacing the strings with
types moves the rule from reviewer memory into the compiler.

**The rule this plan is written against**, recorded because it decided the
approach: no shortcut may justify another shortcut. The reconstruction in
`ErrorResponse.decode` is lossy today, and the only present consumer of a
reconstructed `MergeConflict` is a banner that renders a sentence. That the
consumer accepts a string is a consequence of the same shortcut and is not an
argument for keeping it. The structure is corrected, and the consumer is then
corrected too (§6).

---

## 2. Phase 0 — DONE

These landed on 2026-10-05 under `PLAN-RISKTRANSFORM.md`'s approval, before this
plan existed. They are recorded here because §3 and §4 build directly on them.

**DONE — `TreeLoadFailure` with a required `TreeId`.** A tree-scoped storage
failure is now its own type, and the id is a field rather than text inside the
reason. Shipped shape:

```scala
case class TreeLoadFailure(treeId: TreeId, reason: String) extends SimError:
  override def getMessage: String = s"Tree ${treeId.value} could not be loaded: $reason"
```

`RiskTreeRepository.getAllForWorkspace` narrowed its `Left` to it, which is what
makes the id required rather than optional — a failure about the listing itself
fails the effect instead of appearing as an entry:

```scala
  def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): Task[List[Either[TreeLoadFailure, RiskTree]]]
```

The id is attached in `RiskTreeRepositoryIrmin.getAllForWorkspace`, where it is
already bound, so the inner read helpers were not touched. `ErrorResponse`
gained the case the exhaustive `SimError` match demands, routing to the same
opaque 500 as `RepositoryFailure`. Six call sites were updated: the in-memory
repository, `RiskTreeServiceLive.collectAllTrees`, and four test stubs of the
trait.

**DONE — the undo path and its error type are gone.** The staging-branch merge
that `PLAN-RISKTRANSFORM.md` §8.19.9 landed never points main at an unvalidated
commit, so there is no undo to fail. `MergeUndoFailed` was removed from
`AppError.scala`, from `ErrorResponse.encode` and `decode`, from
`ValidationErrorCode`, and from the client's classification in
`modules/app/src/main/scala/app/state/GlobalError.scala`. Two types carrying only
a `ScenarioName` took its place: `MergeAlreadyRunning` and `MergeTargetMoved`.

**DONE — the merge guard no longer forwards repository text.**
`guardStagedState` logs the repository's own failure text and builds its
`MergeConflict` from tree identifiers and a count.

**NOT DONE, and this plan's §3 is where it is done:** the typing half of Defect 1
above. `MergeConflict.details` is still a free-text `String`, so the field
remains open to the next producer that puts repository text in it.

**Sequencing constraint.** `PLAN-RISKTRANSFORM.md` §8.19's working tree is
uncommitted and contains Defect 1. §3 of this plan closes it. The two land
together or §3 lands first; §8.19 is not committed on its own with the defect
open.

---

## 3. Phase 1 — typed merge refusals, closing the leak

### 3.1 The refusal reason becomes a closed set

New in `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`:

```scala
/** Why a scenario merge was refused. A closed set, so no construction site can
  * put text of its own into a client-facing error: every case carries only
  * values that already cross the client boundary.
  */
enum MergeRefusal:
  /** Storage paths changed on both branches. */
  case Conflicts(paths: List[MergeConflictEntry])
  /** The merged node set would repeat a name within one tree. */
  case DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])
  /** The merge committed, a tree then failed to load, and the merge was undone. */
  case InvariantBroken(perTree: Map[TreeId, List[ValidationError]])
  /** Irmin refused the merge because main moved while it ran. */
  case ConcurrentChange
```

`Conflicts` reuses `MergeConflictEntry`, which already exists in
`modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioMergeResponse.scala`
and is what `ScenarioController` builds for the merge preview. The alternative
considered and rejected was moving the server-side `MergeConflictPath` into
`common`, which would place a storage-shaped type in the shared module for no
gain.

`InvariantBroken` carries `List[ValidationError]` per tree rather than a count,
because that is the type `RiskTree.fromNodes` already produces and the type
Defect 3 currently destroys.

### 3.2 The two merge errors carry it

Changed in the same file:

```scala
case class MergeConflict(scenario: ScenarioName.ScenarioName, reason: MergeRefusal) extends SimError {
  override def getMessage: String = s"Merge conflict on scenario ${scenario.value}: ${MergeRefusal.describe(reason)}"
}

```

`MergeConflict` is the only error this plan retypes. The undo-failure type that
once sat beside it no longer exists: the staging-branch merge removed the undo
path, so `MergeRefusal` has one carrier rather than two.

The server's fallback sentence is derived once, in the companion, so the server
and the client cannot drift:

```scala
object MergeRefusal:
  /** The server's fallback sentence for a client with no template for the code.
    * The client composes its own text from the structure (§6); this is what
    * `JsonHttpError.message` carries.
    */
  def describe(reason: MergeRefusal): String = reason match
    case Conflicts(paths) =>
      s"${paths.size} conflicting path(s): ${paths.map(_.path).sorted.mkString(", ")}"
    case DuplicateNames(perTree) =>
      perTree.toList.sortBy(_._1.value)
        .map((treeId, names) => s"tree ${treeId.value}: ${names.map(_.value).sorted.mkString(", ")}")
        .mkString("merging would duplicate node name(s) — ", "; ", "")
    case InvariantBroken(perTree) =>
      s"the merged state breaks a tree invariant in ${perTree.size} tree(s) " +
      s"(${perTree.keys.map(_.value).toList.sorted.mkString(", ")}), so the merge was undone"
    case ConcurrentChange =>
      "merge was refused — main changed concurrently and now conflicts; re-run the preview and retry"

  def wireCode(reason: MergeRefusal): ValidationErrorCode = reason match
    case _: Conflicts       => ValidationErrorCode.MERGE_CONFLICT
    case _: DuplicateNames  => ValidationErrorCode.MERGE_DUPLICATE_NAMES
    case _: InvariantBroken => ValidationErrorCode.MERGE_INVARIANT_BROKEN
    case ConcurrentChange   => ValidationErrorCode.MERGE_CONCURRENT_CHANGE
```

### 3.3 New error codes

Added to
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationErrorCode.scala`,
beside the existing concurrency codes:

```scala
  case MERGE_DUPLICATE_NAMES extends ValidationErrorCode("MERGE_DUPLICATE_NAMES", "A merge would repeat a node name within one tree")
  case MERGE_INVARIANT_BROKEN extends ValidationErrorCode("MERGE_INVARIANT_BROKEN", "A merge produced a tree that breaks an invariant and was undone")
  case MERGE_CONCURRENT_CHANGE extends ValidationErrorCode("MERGE_CONCURRENT_CHANGE", "A merge was refused because the target branch moved")
```

`MERGE_CONFLICT` keeps its name and narrows to byte-level path conflicts.

The enum's decoder fails on a code it does not recognise:

```scala
  given decoder: JsonDecoder[ValidationErrorCode] =
    JsonDecoder[String].mapOrFail { code =>
      ValidationErrorCode.values.find(_.code == code)
        .toRight(s"Unknown validation error code: $code")
    }
```

So adding a code is a breaking change for a client older than the server. There
is one deployed client, built from this repository and shipped in the same
image, so no compatibility window applies.

### 3.4 The guard stops forwarding prose

Changed in
`modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala`.
The repository's text goes to the log and nowhere else:

```scala
  private def guardStagedState(
    wsId: WorkspaceId,
    scenario: ScenarioName.ScenarioName,
    staged: CommitHash
  ): Task[Unit] =
    repo.getAllForWorkspace(wsId, Revision.At(staged)).flatMap { loaded =>
      loaded.collect { case Left(failure) => failure } match
        case Nil      => ZIO.unit
        case failures =>
          val perTree = failures.map(f => f.treeId -> TreeLoadProblem.validationErrors(f.problem)).toMap
          ZIO.logError(
            s"merge guard rejected ${staged.value} for scenario ${scenario.value}: " +
            failures.map(f => s"${f.treeId.value}: ${TreeLoadProblem.describe(f.problem)}").mkString("; ")
          ) *> ZIO.fail(MergeConflict(scenario, MergeRefusal.InvariantBroken(perTree)))
    }
```

The guard has one refusal arm rather than three. It runs against the staging
branch, so main was never moved and there is nothing to restore; the two
undo-failure arms that once sat here are gone with the undo path. The
repository's own text is in the log line above and nowhere else.

`TreeLoadProblem.validationErrors` extracts the typed invariant errors from a
load problem and yields an empty list for the problems that are not invariant
violations; both helpers are specified in §4.3.

The other three construction sites stop building strings:

```scala
      _ <- ZIO.when(result.conflicts.nonEmpty)(ZIO.fail(MergeConflict(name,
             MergeRefusal.Conflicts(result.conflicts.map(p =>
               MergeConflictEntry(p.path, p.treeId.map(_.value), p.nodeId.map(_.value)))))))
      _ <- ZIO.when(result.duplicateNames.nonEmpty)(ZIO.fail(MergeConflict(name,
             MergeRefusal.DuplicateNames(result.duplicateNames))))
```

```scala
                        .catchSome { case IrminMergeConflict(_) =>
                          ZIO.fail(MergeConflict(name, MergeRefusal.ConcurrentChange))
                        }
```

`MergeScan.duplicateNames` changes type so the names stay refined until they are
rendered:

```scala
  private final case class MergeScan(
    conflicts: List[MergeConflictPath],
    duplicateNames: Map[TreeId, List[SafeName.SafeName]]
  )
```

and `duplicateNodeNames` returns that type, dropping the `.value` widening it
performs today.

The precedence between a byte-level conflict and a duplicate name is currently
written twice, as an `if`/`else` chain in `preview` and as two sequential
`ZIO.when` guards in `merge`. It is stated once:

```scala
  private object MergeScan:
    /** Conflicts are reported ahead of duplicate names, because a conflicting
      * path stops the merge before the merged node set exists. One definition,
      * read by both `preview` and `merge`.
      */
    def verdict(scan: MergeScan): Option[MergeRefusal] =
      if scan.conflicts.nonEmpty then
        Some(MergeRefusal.Conflicts(scan.conflicts.map(p =>
          MergeConflictEntry(p.path, p.treeId.map(_.value), p.nodeId.map(_.value)))))
      else if scan.duplicateNames.nonEmpty then Some(MergeRefusal.DuplicateNames(scan.duplicateNames))
      else None
```

### 3.5 The preview result reuses the refusal

`MergePreviewResult` currently carries a rendered string for the duplicate-name
case. It carries the refusal instead, so the preview and the merge refusal are
the same value:

```scala
enum MergePreviewResult:
  case Clean
  case Refused(reason: MergeRefusal)
  case ScenarioMissing
```

`ScenarioController.previewResponse` maps it to the wire DTO. The existing
`MergePreviewResponse` keeps its three fields and `duplicateNames` becomes
structured rather than a rendered line:

```scala
final case class MergePreviewResponse(
  status: String,
  conflicts: List[MergeConflictEntry],
  duplicateNames: List[DuplicateNameEntry] = Nil
)

/** One tree and the names the merged node set would repeat inside it. */
final case class DuplicateNameEntry(treeId: String, names: List[String])

object DuplicateNameEntry:
  given codec: JsonCodec[DuplicateNameEntry] = DeriveJsonCodec.gen[DuplicateNameEntry]
```

`treeId` and `names` are plain `String` here because this is a wire data
transfer object, where the refinement is applied by the codec on the way in and
the values are rendered on the way out; this mirrors `MergeConflictEntry`, whose
`treeId` and `nodeId` are already `Option[String]` for the same reason.

---

## 4. Phase 2 — typed locations, typed values, typed load problems

### 4.1 Two new Iron refinements

Added to
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`,
beside `SafeNameConstraint` and `BranchRefConstraint`.

```scala
// A path into a request body, as the form layer resolves it: lower-initial
// camelCase segments joined by dots, each optionally carrying an array index.
// Every value is a literal written in this codebase, so the refinement guards a
// programmer slip on the way out — and untrusted input on the way in, because
// `ErrorResponse.decode` refines what the server sent.
type FieldPathConstraint =
  Not[Blank] & MaxLength[200] &
  Match["^[a-z][a-zA-Z0-9]*(\\[[0-9]{1,3}\\])?(\\.[a-z][a-zA-Z0-9]*(\\[[0-9]{1,3}\\])?)*$"]
type FieldPathStr = String :| FieldPathConstraint

object FieldPath:
  opaque type FieldPath = FieldPathStr
  object FieldPath:
    def fromString(s: String, fieldPath: String = "field"): Either[List[ValidationError], FieldPath] = ???
  extension (p: FieldPath) def value: String = p
```

The alphabet is taken from the 29 distinct values the codebase passes as
`field` today. All of them match, with one exception handled by §4.2:
`X-Branch` is an HTTP header name, not a path. `MaxLength[200]` bounds a value
the application generates and no caller supplies, so it only has to be finite;
the longest real value is 25 characters. The three-digit index bound covers the
10 000-node tree ceiling's largest collection.

```scala
// A workspace-relative storage path. Anchored at `risk-trees`, so the absolute
// form, which begins `workspaces/<wsId>/`, cannot be refined: the ADR-036
// boundary is the type rather than a review habit.
type StoredPathConstraint =
  Not[Blank] & MaxLength[300] &
  Match["^risk-trees/[A-Za-z0-9_-]{1,64}(/meta|/(nodes|mitigations)/[A-Za-z0-9_-]{1,64})?$"]
type StoredPathStr = String :| StoredPathConstraint

object StoredPath:
  opaque type StoredPath = StoredPathStr
  object StoredPath:
    def fromString(s: String, fieldPath: String = "path"): Either[List[ValidationError], StoredPath] = ???
  extension (p: StoredPath) def value: String = p
```

The three admitted shapes are exactly the three
`MergeConflictPath.fromRelativePath` parses today. `MaxLength[300]` is the
longest shape with each segment at its own maximum, rounded up.

### 4.2 `ErrorLocation`

New file,
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala`:

```scala
/** Where an error is. A closed set, because every location this system points
  * at already has a type — and because none of its cases can hold a value that
  * must not cross the client boundary (ADR-036): there is no case for a
  * `BranchRef` and none for an absolute storage path.
  */
enum ErrorLocation:
  /** A path into the request body, as the form layer resolves it. */
  case RequestField(path: FieldPath.FieldPath)
  /** An HTTP request header, named by the header itself. */
  case RequestHeader(name: String)
  case Scenario(name: ScenarioName.ScenarioName)
  case Tree(id: TreeId)
  case Node(treeId: TreeId, nodeId: NodeId)
  case Commit(hash: CommitHash)
  case Branch(choice: BranchChoice)
  case StoredAt(path: StoredPath.StoredPath)
  /** The failure is about the request as a whole, with no narrower location. */
  case Request

object ErrorLocation:
  given codec: JsonCodec[ErrorLocation] = ???
```

`RequestHeader` exists because `X-Branch` is a real location that the field-path
alphabet correctly refuses. Its `name` is a plain `String` and not refined: the
set of headers this application names is three literals, and a refinement would
encode an HTTP grammar this plan has no other use for.

**`ValidationError` carries the same type (TES-D-1).** The domain validation
error and its wire form name a location with one vocabulary, so nothing maps
between them and no fallback is needed:

```scala
case class ValidationError(
  location: ErrorLocation,
  code: ValidationErrorCode,
  message: String
)
```

`message` stays. It is the server's rendered sentence for one field, authored in
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala`,
and it is genuinely free text. It crosses the wire inside
`ErrorValues.Text(message)`, which is the case §4.3 declares for exactly that,
so `ErrorResponse.decode` rebuilds `ValidationError(location, code, message)`
with nothing lost.

**Author-written paths are refined at compile time, not at runtime.** Iron's
`autoRefine` accepts a literal where a refined type is expected and rejects one
that does not satisfy the constraint. The mechanism is already used in this
repository: `RiskResultTransform.scaleLosses(0.8)` at
`modules/server/src/test/scala/com/risquanter/register/mitigation/RiskResultTransformSpec.scala:199`
passes a bare `0.8` where a `RetentionFactor` is required, and compiles only
because that file imports `io.github.iltotore.iron.autoRefine`. So a smart
constructor keeps writing its location as a literal:

```scala
  refineName(name, ErrorLocation.RequestField("root.name"))
```

and a malformed literal becomes a compile error rather than a runtime `Either`
the author has to unwrap. The acceptance half of that behaviour is verified from
the call site above; the rejection half is Iron's documented behaviour of the
same mechanism and has not been exercised here, so Phase 2's first task is a
deliberately malformed literal confirming the compile error.

The wire form tags every case, so no value is guessed:

```json
{ "kind": "requestField", "path": "root.children[0].minLoss" }
{ "kind": "requestHeader", "name": "X-Branch" }
{ "kind": "scenario",     "name": "q3-plan" }
{ "kind": "tree",         "id": "01HQ0000000000000000000001" }
{ "kind": "node",         "treeId": "01HQ0000000000000000000001", "nodeId": "01HR0000000000000000000002" }
{ "kind": "commit",       "hash": "3f2a9c0000000000000000000000000000000000" }
{ "kind": "storedAt",     "path": "risk-trees/01HQ0000000000000000000001/meta" }
{ "kind": "request" }
```

### 4.3 `ErrorValues` and `TreeLoadProblem`

New file,
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorValues.scala`:

```scala
/** The values a message needs, carried as data so the reader composes its own
  * sentence (§6) instead of printing one the producer wrote.
  */
enum ErrorValues:
  case Empty
  /** Genuinely free text, from outside this codebase. */
  case Text(value: String)
  case Names(names: List[SafeName.SafeName])
  case Range(min: String, max: String)
  case Expected(expected: String, actual: Option[String])
  case Invariants(errors: List[ValidationError])

object ErrorValues:
  given codec: JsonCodec[ErrorValues] = ???
```

`Range`'s bounds are `String` because the values they report come from
refinements over several numeric types — `PositiveLong`, `RetentionFactor`,
`OccurrenceProbability` — and a single numeric type would have to widen one of
them.

New in `AppError.scala`, replacing `TreeLoadFailure`'s `reason: String`:

```scala
/** Why one tree would not load. Every situation the repository can reach, so a
  * caller branches on the situation instead of reading a sentence.
  */
enum TreeLoadProblem:
  case MetaAndNodesMissing
  case MetaMissingButNodesExist
  case NoNodes
  case ValueMissing(part: StoredPart, at: StoredPath.StoredPath)
  case DecodeFailed(part: StoredPart, at: StoredPath.StoredPath, detail: DecoderMessage)
  case InvariantsViolated(errors: List[ValidationError])
  case StorageUnavailable(detail: BackendMessage)

/** Which stored artefact of a tree a problem is about. */
enum StoredPart:
  case Meta, Node, Mitigation

case class TreeLoadFailure(treeId: TreeId, problem: TreeLoadProblem) extends SimError:
  override def getMessage: String =
    s"Tree ${treeId.value} could not be loaded: ${TreeLoadProblem.describe(problem)}"

object TreeLoadProblem:
  /** The server's own sentence, for logs and for the fallback message. */
  def describe(problem: TreeLoadProblem): String = ???

  /** The typed invariant errors a problem carries, empty for the problems that
    * are not invariant violations. Used by the merge guard to report which
    * invariant broke in which tree.
    */
  def validationErrors(problem: TreeLoadProblem): List[ValidationError] = problem match
    case InvariantsViolated(errors) => errors
    case _                          => Nil
```

The two pieces of genuinely free text are wrapped where they enter, so they
travel as types and no site can mistake them for text this codebase wrote:

```scala
/** A JSON decoder's own error text, wrapped at the call to `fromJson`. */
opaque type DecoderMessage = String
object DecoderMessage:
  def apply(s: String): DecoderMessage = s
  extension (m: DecoderMessage) def value: String = m

/** The storage backend's own error text, wrapped where `IrminError` is mapped.
  * Never sent to a client: it can name an absolute path.
  */
opaque type BackendMessage = String
object BackendMessage:
  def apply(s: String): BackendMessage = s
  extension (m: BackendMessage) def value: String = m
```

The seven cases are the complete enumeration of what
`RiskTreeRepositoryIrmin`'s read path can produce, taken from its current
sites: `getAllForWorkspace`'s absent-tree arm, `loadTreeAt:226`,
`rebuildTree:170`, `readNodesAt:142`, `readMitigationsAt:160`,
`decodeMeta:133`, `decodeNode:150`, `decodeMitigation:166`, `rebuildTree:179`,
and `handleIrmin:267`.

### 4.4 `ErrorDomain` — the business domain is a closed set

RULED by the user on 2026-10-07: the domain is typed.

`ErrorDetail.domain` is a `String` carrying exactly five values. Four are
defaults on the response builders in `ErrorResponse.scala` — `"irmin"` on four
of them, `"query"` on seven, `"risk-trees"` on nine, `"scenarios"` on six — and
the fifth, `"workspaces"`, already has a named constant,
`ErrorResponse.WorkspaceDomain`, because `decode` compares against it.

That comparison is the evidence the field is a discriminator rather than free
text, and it is also a code-style defect in its own right — equality against a
named constant in order to pick a branch:

```scala
      case 404 =>
        val firstDomain = details.headOption.map(_.domain).getOrElse("")
        if firstDomain == WorkspaceDomain then
          RepositoryFailure(s"${RepositoryFailure.WorkspaceSentinelPrefix}not-found")
        else
          RepositoryFailure(s"Not found: $message")
```

The constant names one value of five, which is why it reads as a sufficient
measure and is not one. A new file,
`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDomain.scala`,
holds the type. It follows `ValidationErrorCode`'s shape, which is the
established form for a wire-facing closed set in this package — a
value-carrying enum with string codecs whose decoder fails on an unrecognised
value:

```scala
/** The part of the system an error row is about.
  *
  * A closed set. It is a discriminator, not prose: `decode` reads it to tell a
  * workspace 404 from an ordinary one, and a client may group rows by it. The
  * wire form is the string each case carries, unchanged from the five literals
  * the response builders used before.
  */
enum ErrorDomain(val wire: String):
  case RiskTrees  extends ErrorDomain("risk-trees")
  case Scenarios  extends ErrorDomain("scenarios")
  case Workspaces extends ErrorDomain("workspaces")
  case Query      extends ErrorDomain("query")
  case Irmin      extends ErrorDomain("irmin")

object ErrorDomain:
  given encoder: JsonEncoder[ErrorDomain] = JsonEncoder[String].contramap(_.wire)
  given decoder: JsonDecoder[ErrorDomain] = JsonDecoder[String].mapOrFail { s =>
    ErrorDomain.values.find(_.wire == s).toRight(s"Unknown error domain: $s")
  }
```

Its Tapir schema goes beside `BranchChoice`'s in
`modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala`:

```scala
  given Schema[ErrorDomain] = Schema.string.map[ErrorDomain](
    (s: String) => ErrorDomain.values.find(_.wire == s)
  )(_.wire)
```

`ErrorResponse.WorkspaceDomain` is deleted — `ErrorDomain.Workspaces` replaces
it — and `decode`'s 404 arm becomes a match on the domain rather than a string
comparison:

```scala
      case 404 =>
        details.headOption.map(_.domain) match
          case Some(ErrorDomain.Workspaces) =>
            RepositoryFailure(s"${RepositoryFailure.WorkspaceSentinelPrefix}not-found")
          case _ =>
            RepositoryFailure(s"Not found: $message")
```

The 27 response builders in `ErrorResponse.scala` take
`domain: ErrorDomain = ErrorDomain.<case>`, each keeping the value it had.

### 4.5 `ErrorDetail` restructured

`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDetail.scala`:

```scala
/** One item that went wrong, inside an HTTP error body.
  *
  * @param domain   The part of the system the row is about, as a closed set
  * @param location Where the error is, typed — never a tag naming the row's role
  * @param code     Machine-readable reason; the reader picks its template by this
  * @param values   The values that reason needs, as data
  * @param requestId Correlation id linking the error to one request
  */
final case class ErrorDetail(
  domain: ErrorDomain,
  location: ErrorLocation,
  code: ValidationErrorCode,
  values: ErrorValues,
  requestId: Option[String] = None
)
```

`message: String` leaves the row. `JsonHttpError.message` stays, as the
server's fallback sentence for a reader that has no template for a code it does
not recognise; §3.3 records that such a reader cannot decode the code at all
today, so the fallback covers the case where that decoder is relaxed later.

### 4.6 The encoders and `decode`

`ErrorResponse.encode` emits one row per item that went wrong. For a merge
refusal the rows are:

| Refusal | One row per | `location` | `values` |
|---|---|---|---|
| `Conflicts` | conflicting path | `Node` where both ids parsed, else `StoredAt` | `Empty` |
| `DuplicateNames` | tree | `Tree(treeId)` | `Names(names)` |
| `InvariantBroken` | tree | `Tree(treeId)` | `Invariants(errors)` |
| `ConcurrentChange` | the refusal | `Scenario(name)` | `Empty` |

Every refusal also emits one `Scenario(name)` row, which is what `decode` reads
to rebuild the typed error.

`decode` becomes non-lossy by construction: every value it needs is a tagged,
typed member, so `MergeRefusal.DuplicateNames` is rebuilt by reading rows rather
than by splitting a sentence on a separator. The four `d.field ==` comparisons
and the two `details.headOption.map(_.field)` dispatches are replaced by matches
on `code` and `location`.

`TreeLoadFailure` is not encoded as itself. It routes to
`makeRepositoryFailureResponse`, which discards its content and returns the
constant `"Internal server error"`, because the problem's text is
storage-internal and the tree id alone tells a client nothing it can act on.
That is the behaviour Phase 0 shipped and this plan keeps.

### 4.7 The workspace errors stop carrying the credential

The three workspace errors each hold a `WorkspaceKeySecret` that nothing reads.
`ErrorResponse.encode` routes all three to one opaque 404 that discards their
content, and the one server-side reader, `WorkspaceStoreLive.logResolveFailure`,
logs `key.toString`, which ADR-022 R3 renders as the constant
`WorkspaceKeySecret(***)`. So the credential travels through the error channel
and no site reads its value.

Carrying it defeats ADR-022 R1. That rule requires a credential to be a
`final class`, so the compiler generates no `copy`, no `unapply` and no product
serialisation for it, and `WorkspaceKeySecret` is such a class. Placing one
inside a `case class` returns all three for the container: `.copy()` on the
error yields a second error holding the same credential, `unapply` destructures
it out again, and a derived product codec would serialise it. The rule is
satisfied for the credential and defeated one level up.

The replacement shapes, in
`modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`:

```scala
/** A workspace key that resolved to no live workspace.
  *
  * Carries nothing: at the point this is constructed the lookup has already
  * failed, so no workspace identity exists to name. A `case class` with no
  * parameters rather than a `case object`, because `AppError` extends
  * `Throwable` — a singleton captures one stack trace when it initialises
  * instead of one per failure, and it would turn every
  * `case _: WorkspaceNotFound` match arm into a value match for no gain.
  */
case class WorkspaceNotFound() extends DomainError:
  override def getMessage: String = "Workspace not found"

/** A workspace past its absolute or idle timeout.
  *
  * `ttl` is the configured workspace lifetime, which the client states to the
  * user. The workspace identifier and the creation time are deliberately
  * absent: this is the one workspace error `ErrorResponse.decode` rebuilds
  * (TES-D-9), so every field it holds has to be a field the wire carries, and
  * the identifier must not cross that boundary (ADR-036). Both travel to the
  * server's log instead, annotated at the construction site.
  */
case class WorkspaceExpired(ttl: JDuration) extends DomainError:
  override def getMessage: String = s"Workspace expired (lifetime: $ttl)"

/** A tree the resolved workspace does not hold.
  *
  * `wsId` serves the server's logs on the same terms as `WorkspaceExpired.id`.
  * `treeId` stays in `getMessage`: a tree identifier is not confidential, and
  * it is the useful diagnostic.
  */
case class TreeNotInWorkspace(wsId: WorkspaceId, treeId: TreeId) extends DomainError:
  override def getMessage: String = s"Tree ${treeId.value} not found in workspace"
```

`DomainError` is `SimError` renamed under TES-D-3. `WorkspaceId` already lives
in `common`, in
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala`,
so no type changes module.

**The log line is where the diagnostics go, and it gains what the types shed.**
Both resolve-failure log lines annotate `"workspace_key" -> key.toString`
today, and that renders as the same redacted constant for every workspace, so
two failures are indistinguishable in the log. The expiry arm annotates
`"workspace_id" -> ws.id.value`, `"created_at" -> ws.createdAt.toString` and
`"expires_at" -> ws.expiresAt.toString` instead. `WorkspaceNotFound` has
nothing to annotate and keeps `"reason" -> "not_found"` alone, which is
correct — there is no workspace to name.

So each value sits where its reader is: the error type says what happened and
carries only what the client may hold, and the structured log says which
workspace and when.

The construction sites that change, each already holding what the new shape
needs:

| Site | Today | Becomes |
|---|---|---|
| `WorkspaceStoreLive.validateWorkspace` | `WorkspaceNotFound(key)` | `WorkspaceNotFound()` |
| `WorkspaceStoreLive.validateWorkspace` | `WorkspaceExpired(key, ws.createdAt, ws.ttl)` | `WorkspaceExpired(config.ttl)` |
| `WorkspaceStorePostgres.resolveInternal` | `WorkspaceNotFound(key)` | `WorkspaceNotFound()` |
| `WorkspaceStorePostgres.resolveInternal` | `WorkspaceExpired(key, ws.createdAt, ws.ttl)` | `WorkspaceExpired(config.ttl)` |
| `WorkspaceStore.resolveTreeWorkspace` | `TreeNotInWorkspace(key, treeId)` | `TreeNotInWorkspace(ws.id, treeId)` |

`resolveTreeWorkspace` binds `ws` from `resolve(key)` on the line above, so the
identifier is in scope with no extra read. Both stores hold `config`, so the
configured lifetime is in scope at each expiry arm.

**One point to settle when the lifetime question is.** `WorkspaceRecord` carries
a per-record `ttl` and `WorkspaceConfig` carries the configured one. The ruling
says the client states "whatever is configured", which is `config.ttl`, and the
table above reads it from there. If the per-record value is wanted instead —
they differ only for a record written under an earlier configuration — the arms
read `ws.ttl` and nothing else changes.

`WorkspaceKeySecret` leaves `AppError.scala`'s import list, which is the
mechanical check that no error carries a credential any more.

### 4.8 The expiry response and the client's two workarounds

TES-D-9 and TES-D-10 are ruled, so this section is specified apart from one
field: whether the configured lifetime reaches the client in the response or
from `/config.json`. Everything below is written for the response, and the
alternative is noted where it differs.

**What crosses the boundary, and what each side rebuilds.** Three errors go in
and two come out, which is A13's substance kept and its expiry coverage dropped:

| Server error | Status | Response carries | `decode` rebuilds |
|---|---|---|---|
| `WorkspaceNotFound()` | 404 | nothing beyond the code | `WorkspaceNotFound()` |
| `TreeNotInWorkspace(wsId, treeId)` | 404 | nothing beyond the code — the same body as not-found | `WorkspaceNotFound()` |
| `WorkspaceExpired(ttl)` | 404 | a distinct code, and the lifetime | `WorkspaceExpired(ttl)` |

The first two share one body, so a caller cannot learn whether a tree exists in
a workspace that is not theirs. That is what A13 was ruled for and it is intact.
The third is distinguishable, carries no creation time and no identifier, and is
rebuilt exactly — so the sentinel has nothing left to stand in for.

`ErrorResponse.WorkspaceSentinelPrefix` and
`RepositoryFailure.isWorkspaceSentinel` are deleted, together with the string
comparison in `decode`'s 404 arm. `GlobalError.fromAppError` matches
`WorkspaceNotFound` and `WorkspaceExpired` as the types they are, which is what
routes the informational banner instead of a prefix test on a storage failure's
text.

**`preValidate` narrows to the two errors that mean what it assumes.** The
wildcard becomes a match, so a network failure or a 500 leaves the key alone:

```scala
          .tapError {
            case _: WorkspaceNotFound | _: WorkspaceExpired =>
              zio.ZIO.succeed {
                workspaceKey.set(None)
                clearURL()
                onExpired()
              }
            case _ => zio.ZIO.unit
          }
```

A failure that is not a workspace-lifecycle outcome then falls through to the
ordinary global banner, which is what `runJsQuiet` was suppressing. The method's
comment is rewritten to say what the code now does: the two workspace errors
mean the key is dead, and nothing else does.

**The wording, owned once.** `ErrorTemplates` renders both, and the two copies
of today's sentence — in `Main.scala`'s `onExpired` callback and in
`GlobalError.fromAppError` — are replaced by one call. The expiry text names the
lifetime and not a date; the not-found text claims no cause:

| Error | Text |
|---|---|
| `WorkspaceExpired(ttl)` | "This workspace has expired. Workspaces are kept for <ttl>. Creating a new tree will start a fresh workspace." |
| `WorkspaceNotFound()` | "This workspace is no longer available. Creating a new tree will start a fresh workspace." |

`<ttl>` is rendered from the `JDuration` in whole hours where it divides evenly
and otherwise in hours and minutes; the helper lives beside `ErrorTemplates` and
takes the duration, so no call site formats it.

If the lifetime comes from `/config.json` instead, `WorkspaceExpired` carries no
field, `AppConfigState.ConfigPayload` gains one, and the frontend container's
entrypoint template and its environment wiring gain the value — three more files
and a value duplicated between the server's configuration and the container's.
The table above is otherwise unchanged.

**The defects this removes, recorded because each explains a shape above.**

**Workaround 1 — a sentinel string stands in for a type.** Because the opaque
404 discards every field, `decode` can rebuild none of the three errors, so it
fabricates a different one:

```scala
      case 404 =>
        val firstDomain = details.headOption.map(_.domain).getOrElse("")
        if firstDomain == WorkspaceDomain then
          RepositoryFailure(s"${RepositoryFailure.WorkspaceSentinelPrefix}not-found")
```

and the client recognises it by string prefix, through
`RepositoryFailure.isWorkspaceSentinel`. A workspace error therefore reaches the
client as a storage failure whose `reason` begins `"workspace:"`. This is
Defect 2 one layer along: a field carries a tag naming the row's role instead of
the value it declares.

**Workaround 2 — a transient failure destroys the user's capability URL.**
`WorkspaceState.preValidate` treats every failure as expiry:

```scala
          .tapError { _ =>
            zio.ZIO.succeed {
              workspaceKey.set(None)
              clearURL()
              onExpired()
            }
          }
```

The wildcard catches a network failure and a 500 as readily as a 404. The
workspace key is the only credential for that workspace, and the URL is the only
place the user holds it, so one failed pre-validation call discards it
permanently. The method's own comment asserts the opposite — "we KNOW this is a
pre-validation check, so failure means expired workspace" — which is true of the
call's intent and false of its error channel.

**The duplicated sentence.** The expiry message is written out twice, at the
`onExpired` callback in `modules/app/src/main/scala/app/Main.scala` and again in
`GlobalError.fromAppError`. Phase 4's `ErrorTemplates` owns it once, which is
where §6 already puts every other message.

---

## 5. Phase 3 — the repository stops building strings

`RiskTreeRepositoryIrmin`'s read path produces `TreeLoadProblem` values. The
fifteen tree-scoped `RepositoryFailure(...)` constructions inside it are
replaced; the helpers gain the `StoredPath` and `StoredPart` they report, which
they can build from the arguments they already receive.

`rebuildTree` stops flattening:

```scala
      ZIO.fromEither(
        RiskTree
          .fromNodes(meta.id, meta.name, nodes, meta.rootId, Some(meta.seedVarHighWater), mitigations.toList)
          .toEither
          .left.map(errors => TreeLoadFailure(meta.id, TreeLoadProblem.InvariantsViolated(errors)))
      )
```

`handleIrmin` wraps the backend's text:

```scala
  private def handleIrmin[A](effect: IO[IrminError, A]): Task[A] =
    effect.mapError(err =>
      RepositoryFailure(Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.toString)))
```

stays as it is for the calls that are not about one tree, and the tree-scoped
calls wrap into `TreeLoadProblem.StorageUnavailable(BackendMessage(...))`.

`RepositoryFailure` keeps its single `reason: String` field and its three
remaining jobs: a workspace-store failure, a failure about no single tree, and
the client-side reconstruction of an opaque server 500. Its 37 construction
sites outside the tree-scoped read path are untouched.

---

## 6. Phase 4 — the client composes its own messages

The single-page application receives the typed error, matches on it, and fills a
stored template. This is the half that makes Phase 2 worth doing: a scenario
merge failure reads differently from a field validation failure because the
structure says they are different, rather than because the server wrote two
different sentences.

New file, `modules/app/src/main/scala/app/state/ErrorTemplates.scala`:

```scala
/** Renders one error detail into display text, choosing a template by `code`
  * and filling it from `location` and `values`. The server's
  * `JsonHttpError.message` is the fallback for a code with no template here.
  */
object ErrorTemplates:
  def render(detail: ErrorDetail): String = ???

  /** Which banner an error belongs in. */
  def banner(error: AppError): BannerKind = ???

enum BannerKind:
  case Conflict, ServerFault, WorkspaceInfo, Validation
```

`GlobalError.fromAppError` keeps its exhaustive classification and delegates the
text to `ErrorTemplates`. The two banner cases named in the ruling that produced
this plan are the conflict banner and the server-fault banner; `WorkspaceInfo`
and `Validation` already exist as distinct routes in that file and are listed so
the enum is complete rather than partial.

The three form-field lookups change from reading a string to matching a
location:

```scala
      detail.location match
        case ErrorLocation.RequestField(path) => TreeBuilderLogic.formFieldFor(path.value).flatMap(fieldMapping)
        case _                                => None
```

`TreeBuilderLogic.formFieldFor` lives in
`modules/common/src/main/scala/com/risquanter/register/frontend/TreeBuilderLogic.scala`
and keeps its `String` parameter, because it maps a path to a form control and
has no other use for the refinement.

`MergeModal` renders the structured `duplicateNames` from §3.5 instead of a
server-rendered line, and its `case _ =>` fallback arm is replaced by an
exhaustive match on the preview status.

---

## 7. Decisions

All ten are ruled. TES-D-10 supersedes TES-D-6's field list and TES-D-9
supersedes TES-D-8's, in both cases narrowing what the error carries; the
superseded decisions are kept because the later ones are read against them.

### TES-D-1 — `ValidationError` carries `ErrorLocation` — RULED 2026-10-05

`ValidationError(field, code, message)` is the domain validation error, defined
in ADR-010 §2 and produced by every smart constructor in `common`. `ErrorDetail`
is its wire form. The question was whether both name a location with one type,
or whether `ValidationError` keeps `field: String` and the boundary maps it.

**Ruled: one type, on both.** The shape is in §4.2. The ruling was made on
principled typing rather than on the cost of changing call sites, which is what
the alternative's only real argument rested on.

Three reasons, in the order they decide it:

1. **One vocabulary, so nothing maps.** Keeping `field: String` on the domain
   side means `ErrorResponse` translates a string into an `ErrorLocation` at the
   wire, which is a second place where the meaning of a location is decided.
   That is the defect this plan exists to remove, reintroduced one layer down.
2. **No silent fallback.** A boundary mapping has to do something when a `field`
   string does not refine, and that something is a location quietly replaced by
   `ErrorLocation.Request`. A typed member has no such case.
3. **The refinement belongs where the value is written.** Every `field` value in
   the codebase is an author-written literal, so the check belongs at compile
   time. `autoRefine` provides exactly that, the call sites keep their literal
   form, and a malformed path stops the build instead of degrading a response.

The cost that was set aside: every `ValidationError` construction site in
`common` changes, and ADR-010 §2's definition changes with it. The count was not
measured before the ruling and does not affect it.

### TES-D-2 — an unparseable stored path dies — RULED 2026-10-05

`MergeConflictPath.fromRelativePath` has a fourth arm today that keeps an
unrecognised raw path and reports it with no coordinates. `StoredPath` cannot
hold such a value, by design, because the same refinement is what excludes the
absolute form.

**Option A — the row's location becomes `ErrorLocation.Request`.** The conflict
is still reported; the client learns a conflict exists but not where.

**Option B — the row is not emitted, and the path is logged.** The client's
conflict list is then incomplete, which for a preview means the user is shown
fewer conflicts than exist.

**Option C — the path shape is an invariant and an unparseable one dies.** Every
path in the store is written by `writeTree`, which composes exactly the three
shapes, so a fourth shape means the store has been written by something else.

**Ruled: Option C.** It is the only one that does not degrade a user-visible
list, and the premise it rests on is checkable: `writeTree` composes exactly the
three admitted shapes and is the only writer of these paths. **Phase 2 verifies
that premise before the refinement is added**, and if the store turns out to
hold a fourth shape, Option A is the fallback and the finding is reported rather
than absorbed. Option B is recorded because it is what the code would do by
accident if `StoredPath` were introduced without a decision.

### TES-D-3 — the `SimError` rename lands in this plan — RULED 2026-10-05

ADR-010 §1 defines `sealed trait SimError extends AppError` with the comment
`// domain / service failures`. The name reads as "simulation error" and the
family holds fifteen cases of which one, `SimulationFailure`, is about
simulation. `ErrorResponse.encodeSimError` is the method this plan rewrites, so
the misnomer is in the way.

There are 31 occurrences across five files: `AppError.scala`,
`ErrorResponse.scala`, `GlobalError.scala`,
`modules/app/src/test/scala/app/state/GlobalErrorSpec.scala` and
`modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceLive.scala`.

**Ruled: rename inside this plan.** `SimError` becomes `DomainError` and
`encodeSimError` becomes `encodeDomainError`. ADR-010 §1's hierarchy listing
changes with it, and so does the `adr-constraints` distillation.

The deciding factor is that `ErrorResponse.scala` is rewritten here either way,
so the rename costs almost nothing on top, while sequencing it separately means
rewriting the same method twice. The argument against — that it fixes a
different defect from the other three — is real but does not outweigh touching
one file once.

All five affected files are already in this plan's inventory, four directly and
`modules/app/src/test/scala/app/state/GlobalErrorSpec.scala` through the hook's
same-module test authorization, so the ruling adds no inventory entry.

### TES-D-4 — the preview status discriminator — RULED 2026-10-07

§6 states that `MergeModal`'s `case _ =>` fallback arm "is replaced by an
exhaustive match on the preview status". §3.5 keeps `status: String`. No match
over a `String` can be exhaustive, so the two sections ask for incompatible
things, and one of them has to move.

The defect §6 is aiming at is real and present. `MergeModal.renderPreview`
matches `"clean"`, `"missing-scenario"` and `"duplicate-names"` explicitly, then
falls through to a wildcard that renders the result as a conflict list — it
prints `result.conflicts.size` and iterates `result.conflicts`. A status value
this client does not know would therefore render as "0 path(s) changed on both
branches…" with an empty list. The merge button stays disabled, because
`mergeEnabled` separately tests `status == "clean"`, so the consequence is a
wrong message rather than an unsafe action.

Three ways to resolve it:

1. **Make `status` a closed type on the wire** and keep §6 as written. The
   server already gains `MergePreviewResult` with `Clean`, `Refused` and
   `ScenarioMissing` in §3.5, so this carries that shape across the boundary
   instead of flattening it to a string at the last step. Every consumer of
   `MergePreviewResponse` updates with it. The sibling response types that use
   the same string-plus-sidecar shape — `ScenarioDiffResponse` among them — stay
   on the older convention until a separate cleanup.
2. **Soften §6** to a total match with an explicit default arm, leaving
   `status: String`. One sentence of plan text changes and no wire change
   happens. The wildcard stays and so does the wrong message.
3. **Leave §3.5 and §6 as they are and route the whole convention elsewhere**,
   covering every response type of that shape at once. §6's sentence still has
   to be softened for this plan to be implementable, so this is option 2 plus a
   separate scheduled item.

**Ruled: option 1, reusing nothing because nothing fits.** The server's
`MergePreviewResult` cannot be the type: it lives in `ScenarioMergeService.scala`
under `modules/server`, which is not cross-compiled, so the Scala.js client
cannot see it. `ChangedNodesResponse.scala` records that same constraint for its
own sibling — "`modules/server`, service-layer only — not cross-compiled, hence
the `String` status here rather than sharing the domain enum directly" — so the
string was a consequence of the module boundary, not an oversight. No closed
type in `modules/common` carries these four outcomes.

A new one is added, co-located with the response in
`modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioMergeResponse.scala`
and following `ValidationErrorCode`'s shape: a value-carrying enum with string
codecs whose decoder fails on an unrecognised value. Its Tapir schema follows
`BranchChoice`'s in `IronTapirCodecs.scala`. The four wire strings stay as they
are, so no response body changes shape. Exact signatures:
`docs/dev/plans/PLAN-RISKTRANSFORM.md` §8.19.10, because that slice's own
`MergePreviewResponse` change is where the field is already being edited.

§3.5's `MergePreviewResponse` declaration takes `status: MergePreviewStatus` in
consequence, and §6's "exhaustive match on the preview status" becomes
achievable as written.

### TES-D-5 — the credential leaves all three workspace errors — RULED 2026-10-10

`WorkspaceNotFound`, `WorkspaceExpired` and `TreeNotInWorkspace` each hold a
`WorkspaceKeySecret`. No site reads its value: the encoder discards it and the
one log line that touches it prints the redacted `toString`.

**Ruled: it leaves all three.** The shapes are in §4.7. The deciding reason is
ADR-022 R1 — a credential is a `final class` precisely so that no `copy`,
`unapply` or product serialisation exists for it, and a `case class` wrapping
one returns all three for the wrapper. A field nothing reads, which weakens a
credential rule, has no argument for staying.

The alternative was to keep the field and rely on the redacted `toString`. That
defends one rendering, where removing the field defends the type.

This work lands in this plan rather than its own, because it changes the same
three files Phases 2 and 4 already rewrite — `AppError.scala`,
`ErrorResponse.scala` and the client's error classification — and a separate
plan would rewrite each of them twice.

### TES-D-6 — `WorkspaceExpired` carries the workspace identifier — RULED 2026-10-10

With the credential gone, an expiry failure would name nothing, and the two
resolve-failure log lines already annotate a value that is the same constant for
every workspace.

**Superseded in its field list by TES-D-10, which is where the shipped shape
is.** Its purpose — a resolve failure that names which workspace it was about —
is served by the log line TES-D-10 specifies, so what changed is where the
identifier is read and not whether it is available.

**Ruled: carry it.** `WorkspaceExpired(id: WorkspaceId, createdAt: Instant, ttl: JDuration)`.
The identifier is what makes the log line able to tell two failures apart. It
stays out of `getMessage` (ADR-035 §3) and off the wire (ADR-036); §4.7 states
both.

### TES-D-7 — `TreeNotInWorkspace` carries the workspace identifier — RULED 2026-10-10

**Ruled: carry it.** `TreeNotInWorkspace(wsId: WorkspaceId, treeId: TreeId)`.
`resolveTreeWorkspace` resolves the workspace on the line above the failure, so
the value is in scope and the alternative — naming the tree alone — would leave
a membership failure unable to say which workspace refused it.

`WorkspaceNotFound` takes no identifier, and that is a consequence rather than a
choice: the lookup that failed is what would have produced one.

### TES-D-8 — expiry gets its own response — RULED 2026-10-10

All three workspace errors encode to one opaque 404 that carries no field. The
question was whether expiry keeps sharing it.

**Ruled: expiry gets its own response, carrying `createdAt` and `ttl`.** A
workspace that expired is one the caller did hold, so telling them so discloses
nothing they did not already have, and withholding it is what forced both
workarounds in §4.8.

**This ruling contradicts an earlier one and TES-D-9 resolves that**; the shape
of what the client receives is open as TES-D-10. Until both are ruled, §4.8
records the defects and specifies no encoder arm.

### TES-D-9 — A13's constant response, now contradicted — RULED 2026-10-10

**Ruled: expiry is distinguishable, and the response carries no creation
time.** A caller who presented a key that was valid is told that it expired,
rather than being told the resource is absent. The response states the
configured lifetime so the client can say how long a workspace lasts, and it
does not state when this workspace expired.

**A13 is amended, not overridden silently.** Its scope narrows to the two cases
ADR-035 §3's purpose clause names — "not yours" and "does not exist" — and
expiry leaves it. `TreeNotInWorkspace` and `WorkspaceNotFound` therefore keep
sharing one opaque 404, which is A13's substance: a caller cannot learn whether
a tree exists but belongs to another workspace. What A13 loses is only its
coverage of expiry, and the facts below are why that costs nothing.

The disclosure this admits, measured rather than asserted. The workspace key is
128-bit `SecureRandom`, and A28 in the same index records that brute force is
"separately infeasible" for that reason, so only a caller who held the key can
present one. `WorkspaceBootstrapResponse` already returns `expiresAt` to
whoever creates a workspace and `WorkspaceRotateResponse` returns it again on
rotation, so the lifetime is already on the wire to the holder. The incremental
disclosure is one bit — expired rather than absent — to a caller who proved
possession.

Dropping the creation time is what separates this ruling from simply admitting
expiry wholesale: a user needs to know that their workspace expired and how long
workspaces live, and neither of those requires the moment it happened.

The original options and their reasoning are retained below, because A13's
amendment is read against them.

`docs/dev/plans/IMPLEMENTATION-PLAN.md`'s actionable-item index rules item A13:

> | A13 | Constant response for not-found vs expired | Low | W.3 | Collapse to single opaque 404 at HTTP layer |

and its risk table lists A13 together with A14 under "Timing side-channel
(not-found vs expired)", at Medium severity. A13 names expiry explicitly, so
TES-D-8 contradicts it directly.

ADR-035 §3 is the other source that bears on this, and it is ambiguous rather
than contradicted. Its bullet reads in full:

> - Resource-neutral errors collapse to an opaque status (a missing resource is
>   a plain 404 regardless of variant), so the message cannot distinguish "not
>   yours" from "does not exist"

The purpose clause names two cases and expiry is neither of them, while
"regardless of variant" reads as covering expiry as well. The ADR therefore
supports both readings and settles nothing here. A13 does name expiry, so the
conflict is with A13, and a conflict between two rulings is resolved by the
user rather than by this plan picking the reading that suits it.

What the disclosure actually amounts to, as the facts bear on every option
below. The workspace key is 128-bit `SecureRandom`, and A28 in the same index
records that brute force is "separately infeasible" for that reason, so only a
caller who held the key can present one. `WorkspaceBootstrapResponse` already
returns `expiresAt` to whoever creates a workspace, and `WorkspaceRotateResponse`
returns it again on rotation, so a holder already knows when their workspace
ends. The incremental disclosure of TES-D-8 is therefore one bit — expired
rather than absent — to a caller who proved possession.

**Option A — amend A13 to cover not-found and not-yours, and admit expiry.**
A13's scope becomes the two cases ADR-035 §3 names, and the expiry response
carries `createdAt` and `ttl` as TES-D-8 ruled. The client can say when the
workspace ended and how long workspaces last. Concretely: a user returning to a
bookmarked `/w/{key}` three days later reads "this workspace expired on 7
October; workspaces last 72 hours", instead of today's "your previous workspace
has expired" written for every 404 including ones that are not expiry at all.

**Option B — keep A13 as it stands, and withdraw TES-D-8.** Not-found and
expiry stay one response. The two workarounds in §4.8 are then fixed without
distinguishing them: `decode` returns a typed workspace-404 error instead of the
`RepositoryFailure` sentinel, and `preValidate` narrows its `tapError` to that
one error instead of every failure. Concretely: the same returning user reads
"this workspace is no longer available", and a network failure no longer
destroys their key — but the system still cannot tell them which of the two
happened, and the blue banner still says "expired" for a workspace that was
deleted or whose key was rotated away.

**Option C — distinguish expiry, withhold the figures.** A13 is amended as in
Option A, but the response carries only the fact of expiry, with no `createdAt`
and no `ttl`. The client gets the one bit it needs to choose its wording and the
deployment's configured lifetime is not restated in an error body. Concretely:
the returning user reads "this workspace has expired" and a user whose key was
rotated reads "this workspace is no longer available" — two correct sentences,
neither carrying a date. TES-D-8's `createdAt`/`ttl` are dropped.

**What was ruled is Option C with one field admitted**: expiry distinguishable
as in Option A, no creation time as in Option C, and the configured lifetime
carried so the client can state it. My recommendation had been Option A; the
ruling kept its distinguishability and dropped the figure that answers a
question no user asks.

**One field is pending the user's word, and §4.8 marks it.** The ruling says the
message "should display the expiry limit whatever is configured", and the
sentence that follows it — "so the response does not hold createdAt and no ttl"
— is incomplete in the original. The lifetime has to reach the client somehow,
and `/config.json` does not carry it: `AppConfigState.ConfigPayload` has
`scenariosEnabled`, `appVersion` and `debugUi` and nothing else. So either the
response carries the lifetime, which is one field and no new plumbing, or the
lifetime is added to `/config.json`, which touches the frontend container's
entrypoint template and its environment wiring as well. §4.8 is written for the
first and names the second.

### TES-D-10 — what `decode` rebuilds from a workspace 404 — RULED 2026-10-10

**Ruled: Option C — `WorkspaceExpired` carries no workspace identifier.** The
reason given is that the caller owns the workspace and so has no use for its
identifier. The identifier is annotated on the resolve-failure log line at the
construction site, where `ws` is already bound, so TES-D-6's purpose is served
and only the place the value is read changes.

**TES-D-6 is superseded in its field list and stands in its purpose.** It was
ruled so that two resolve failures could be told apart in the log, and they can
be: the log line annotates `"workspace_id" -> ws.id.value` whether or not the
error type carries it.

**TES-D-7 is unaffected, and the asymmetry has a reason.**
`TreeNotInWorkspace` keeps `wsId: WorkspaceId`. Under TES-D-9 that error still
shares the opaque 404 with `WorkspaceNotFound`, so `decode` never rebuilds it
and its identifier has no wire counterpart to reconcile. `WorkspaceExpired` is
the one error TES-D-9 made distinguishable, which is exactly why it is the one
whose fields must be reconstructible.

**The consequence for the type, which follows rather than being chosen.** A
field the wire does not carry cannot be rebuilt, so what the type holds is
settled by what TES-D-9 puts in the response and nothing else. With the
lifetime in the response the type is `WorkspaceExpired(ttl: JDuration)`; with
the lifetime sourced from `/config.json` instead it is `WorkspaceExpired()`.
That is the single open field §4.8 marks, and §4.7 carries both forms until it
is settled.

The original options and their reasoning are retained below.

`ErrorResponse.decode` turns a response back into an `AppError` so the client
can match on types rather than parse strings. It cannot rebuild
`WorkspaceExpired` as TES-D-6 shapes it, because that type carries a
`WorkspaceId` and ADR-036 forbids the identifier from crossing to the client in
either direction. This is the same gap that produced the sentinel in §4.8, and
it has to be closed deliberately rather than worked around a second time.

Reading the rows directly instead of rebuilding an error is not an option here:
`GlobalError.fromThrowable` takes a `Throwable`, `decode` must return one, and
§6 already commits `GlobalError.fromAppError` to keeping its exhaustive match.

**Option A — a second, client-facing error type.** The server keeps
`WorkspaceExpired(id, createdAt, ttl)` and `decode` returns a new sibling
carrying only what the wire holds. Each side's type then says exactly what that
side knows, and no field is optional. The cost: `ErrorResponse.encode` matches
exhaustively over the sealed hierarchy, so it needs an arm for a type the server
never produces — which is the shape of the `RepositoryFailure` sentinel this
plan is removing, reintroduced with a better name.

**Option B — `id` becomes `Option[WorkspaceId]`.** One type on both sides,
`None` after a decode. The cost: the absence of a value encodes "this error was
rebuilt on the client", which is a field carrying a tag about its own
provenance — Defect 2's shape, in a new place.

**Option C — `WorkspaceExpired` carries no identifier, and the construction
site logs it.** `WorkspaceExpired(createdAt: Instant, ttl: JDuration)` is one
type, produced by the server and rebuilt identically by the client, so `decode`
is a true inverse for this case. Both construction sites bind `ws` already, so
`"workspace_id" -> ws.id.value` is annotated there, and the log line gains
exactly what TES-D-6 was ruled for. The cost: it reverses TES-D-6, and a handler
downstream of the store could no longer name the workspace from the error alone.
No such handler exists today — the error travels store → controller → `.either`
→ `ErrorResponse`, and the controller does not log.

Option C was my recommendation and is what was ruled.

---

## 8. ADR alignment

| ADR | Bears on | Status |
|---|---|---|
| ADR-036 (confidential internal identifiers) | Defect 1, and `StoredPathConstraint` | Compliant, and it closes a live violation. The constraint's anchor at `risk-trees` makes the absolute form unrepresentable, so the boundary is enforced by the type rather than by review. `BackendMessage` carries on the type that its content may name an absolute path and must not be sent. |
| ADR-010 (typed errors) | `MergeRefusal`, `TreeLoadProblem`, `ErrorLocation`, `ErrorValues`, `ErrorDetail` | Compliant in direction — it replaces strings with typed values, which is the ADR's own principle. **The ADR is amended by this plan, in three places:** §1's hierarchy listing gains `TreeLoadFailure` and renames `SimError` to `DomainError` (TES-D-3); §2's `ValidationError` definition takes `location: ErrorLocation` in place of `field: String` (TES-D-1); and the error envelope — `ErrorResponse`, `JsonHttpError`, `ErrorDetail` — is specified in an ADR for the first time. The `adr-constraints` skill distillation is updated in the same pass, in `.github/skills/adr-constraints/SKILL.md` and its byte-identical mirror `.claude/skills/adr-constraints/SKILL.md`. |
| ADR-022 (secret handling) | The three workspace errors | **Compliant after §4.7, and it closes a live defect.** R1 makes a credential a `final class` so no `copy`, `unapply` or product serialisation exists for it; each of the three errors is a `case class` wrapping one, which returns all three for the wrapper. Removing the field is what satisfies R1 at the container, and the redacted `toString` that defends it today defends one rendering rather than the type. |
| ADR-035 (error leakage prevention) | The encoders, the opaque 500, and `getMessage` on the workspace errors | Compliant. `ErrorResponse` stays the only type reaching the wire, `encode` stays an exhaustive typed match, and `TreeLoadFailure` keeps routing to the constant "Internal server error". §3's `getMessage` discipline is why `TreeNotInWorkspace.wsId` is absent from its message while present as a field, and it is one of the two reasons `WorkspaceExpired` carries no identifier at all (TES-D-10). §3 is ambiguous about expiry and is quoted in full in TES-D-9 so the ambiguity is visible rather than resolved by this plan: its purpose clause names "not yours" and "does not exist", while its parenthetical says "regardless of variant". A13 is not ambiguous — it names expiry — so A13 is the ruling TES-D-8 contradicts, and TES-D-9 resolves that. |
| ADR-001 (validate once, at the boundary) | `FieldPath`, `StoredPath` | Compliant. Both are constructed through smart constructors returning `Either[List[ValidationError], _]`, and `decode` refines what it receives, which is the client's boundary. |
| ADR-018 (nominal wrappers for distinct ids) | `ErrorLocation`'s cases | Compliant. Each case names the typed id it carries rather than a `String`. |
| ADR-029 (user-input string types) | `FieldPathConstraint`, `StoredPathConstraint` | Compliant. Both carry a `Match` whitelist as narrow as their domain permits, not only a length bound. |
| ADR-033 (exception boundaries) | The guard's `ZIO.die`, TES-D-2 Option C | Compliant. Both put an unreachable-invariant failure on the effect channel the enclosing signature already has. |
| ADR-002 (observability) | The guard's log line | Compliant, and it fixes a gap: the repository's text is logged rather than discarded, which is where it answers why something failed. |

**New interaction for the `adr-constraints` "Known interactions" table**, because
it is stated in neither ADR alone:

> **ADR-036 × ADR-010.** A typed error channel is not by itself a boundary.
> `MergeConflict` carried a `String` built from a storage-layer message, so the
> error was typed and its content was not, and the `WorkspaceId` crossed the
> boundary inside a correctly-typed error. What closes it is that every payload
> an error carries is a type the client may hold; the error being typed is
> necessary and not sufficient.

---

## 9. File inventory

Listed in `docs/dev/plans/PLAN-TYPED-ERROR-STRUCTURE-INVENTORY.md`, beside this
document. It holds 57 paths, three of them marked as files this plan creates.

**Four paths are still missing.** The agent prints the amendment block and the
user pastes it into `.claude/protocol/pending` and runs
`.claude/bin/approve-inventory`.

| Path | Needed by | Why the inventory does not already name it |
|---|---|---|
| `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDomain.scala` | §4.4 | A file this plan creates |
| `modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala` | §4.7 | The trait's `resolveTreeWorkspace` is where `TreeNotInWorkspace` is constructed; the inventory names the two implementations but not the trait |
| `modules/app/src/main/scala/app/state/WorkspaceState.scala` | §4.8 | `preValidate`'s `tapError` is workaround 2; §4.8 narrows it to the two workspace errors |
| `modules/app/src/main/scala/app/Main.scala` | §4.8 | The `onExpired` callback holds the second copy of the expiry sentence |

`modules/app/src/main/scala/app/views/ErrorBanner.scala` is deliberately not
requested. §4.8 keeps `GlobalError.WorkspaceExpired(message: String)` as the
single informational case and changes only who composes the message, so the
banner's match arms are untouched.

One path depends on the open field in TES-D-9 and is therefore not requested
yet: if the configured lifetime is sourced from `/config.json` rather than from
the response, `modules/app/src/main/scala/app/state/AppConfigState.scala` is
needed as well. `containers/prod/Dockerfile.frontend-prod` would change in the
same case and needs no inventory entry, because the hook gates `modules/**` and
`build.sbt` only.

The approval hook gates `modules/**` and `build.sbt`. It also auto-authorizes
same-module unit-test edits when the plan lists any file under that module's
`src/main`, so `modules/common/src/test/**`, `modules/server/src/test/**` and
`modules/app/src/test/**` need no separate bullets. `modules/server-it` has no
`src/main`, so its specs are listed individually.

---

## 10. Verification plan

### Tests to add

**`modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala`** —
rewritten around the new shape:

- every `MergeRefusal` case encodes to 409 with one row per item, the right
  `code`, and a `Scenario` location row;
- every `MergeRefusal` case survives a full `encode` then `decode` round trip
  with its payload intact, which is the assertion that replaces today's
  partial non-lossiness test;
- no encoded response for any scenario error contains the workspace id, which
  today's version of this test already covers for all four scenario errors;
- an `ErrorDetail` whose `location` does not refine is rejected by the decoder
  rather than silently producing a wrong location.

**New, `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorLocationSpec.scala`** —
`FieldPath` accepts all 29 field values the codebase uses and rejects
`X-Branch`, a leading digit, an upper-initial segment, and a four-digit index;
`StoredPath` accepts the three shapes `writeTree` produces and **rejects
`workspaces/<wsId>/risk-trees/<treeId>/meta`**, which is the test that pins the
ADR-036 boundary to the type.

**New, `modules/server/src/test/scala/com/risquanter/register/domain/errors/TreeLoadProblemSpec.scala`** —
`validationErrors` returns the errors for `InvariantsViolated` and an empty list
for the other six cases.

**`modules/server/src/test/scala/com/risquanter/register/repositories/RiskTreeReadConsistencySpec.scala`** —
one case already asserts that `getAllForWorkspace` produces a
`TreeLoadFailure.reason` containing the raw workspace id, by listing a node at a
commit whose value is then absent at the same commit. It is the test that proves
Defect 1 reachable rather than theoretical. Phase 3 removes the `reason` field
it reads, so the assertion inverts: the failure's `TreeLoadProblem` carries a
`StoredPath`, and the test asserts that path does not contain the workspace id
and that the problem renders without it.

**`modules/server/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceSpec.scala`** —
the guard cases assert on the typed `MergeRefusal.InvariantBroken` map rather
than on message substrings; a new case asserts that the repository's own text
appears in no constructed error, by failing the fake repository with a
`StorageUnavailable(BackendMessage("workspaces/…"))` and checking the raised
error carries no such text.

**`modules/app/src/test/scala/app/state/GlobalErrorSpec.scala`** — each banner
kind is reached by its error family, and `ErrorTemplates.render` produces
different text for a merge refusal than for a field validation failure given the
same `JsonHttpError.message`, which is the assertion that pins Phase 4's point.

**New, `modules/common/src/test/scala/com/risquanter/register/domain/errors/WorkspaceErrorSpec.scala`** —
the assertions for §4.7, which are about what the three types do and do not
carry:

- `TreeNotInWorkspace.getMessage` contains the tree identifier and not the
  workspace identifier, which pins ADR-035 §3 against the field TES-D-7 keeps;
- the encoded response for each of the three contains neither the workspace
  identifier nor the key, which is the wire-side half and extends to workspace
  errors the assertion `ErrorResponseSpec` already makes for scenario errors;
- `WorkspaceNotFound()` and `TreeNotInWorkspace` encode to byte-identical
  bodies, which is the assertion that pins what A13 still covers after TES-D-9
  narrowed it;
- `WorkspaceExpired` encodes to a body that differs from those two, carries the
  configured lifetime, and carries no creation time;
- each of the three survives `encode` then `decode` as the type TES-D-10
  specifies — the two 404-sharing errors both rebuild as `WorkspaceNotFound()`
  and expiry rebuilds as itself with its lifetime intact. This is the assertion
  that replaces the sentinel test, and it is the one that would fail if a
  future field were added to a workspace error without a wire counterpart.

**`modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceStoreSpec.scala`
and `WorkspaceStorePostgresSpec.scala`** — resolving an expired workspace fails
with `WorkspaceExpired` carrying the configured lifetime, and resolving an
unknown key fails with `WorkspaceNotFound()`. Both specs already create, resolve
and expire a workspace, so each case is an assertion on an exit they already
produce.

**`modules/app/src/test/scala/app/state/WorkspaceStateSpec.scala`** — the
assertion for workaround 2, and the one with a user-visible consequence: a
failure that is not a workspace-lifecycle error leaves the workspace key in
place, while `WorkspaceNotFound` and `WorkspaceExpired` clear it. Without this,
nothing stops the wildcard returning.

---

## 11. What this plan depends on, and what depends on it

Three things connected this plan to the merge-guard work in
`docs/dev/plans/PLAN-RISKTRANSFORM.md` §8.19. None of them is shared scope; each
is a dependency in one direction, and each would otherwise be discovered during
implementation.

**The undo-failure type is gone, and this plan no longer waits on it.**
`PLAN-RISKTRANSFORM.md` §8.19.8 ruled M2-D9 on the shape that publishes a merge
to main only after the guard has passed, and §8.19.9 landed it. Main is never
pointed at an unvalidated commit, so there is no undo, no undo failure, and no
type to report one. §3.2 therefore specifies `MergeConflict` alone, and §3.4's
guard has a single failure arm. This dependency is discharged: Phase 1 is free
to start.

**Two readers of node-name uniqueness have to agree, and now do.** §3.1 declares
`MergeRefusal.DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])`,
filled from `ScenarioMergeService.duplicateNodeNames`, and
`MergeRefusal.InvariantBroken(perTree: Map[TreeId, List[ValidationError]])`,
filled from the errors `RiskTree.fromNodes` produces. The first predicts what
the second will reject, so a disagreement between them would make a refusal
contradict the invariant it anticipates. Both now read one definition,
`SafeName.duplicates`, ruled as M2-D10 in `PLAN-RISKTRANSFORM.md` §8.19.8 and
landed. This plan requires no change for it and may rely on the agreement.

**Collapsing the per-boundary uniqueness wrappers belongs here, in Phase 2.**
RULED by the user on 2026-10-07: unify after Phase 1, and the target is one
check over a collection taking its locator as a typed parameter rather than a
string.

The seed-identifier rule is **not** the model to copy, and the earlier claim
that it was is withdrawn. `SeedVarId.requireDistinct` is shared, which is one
level better than two inline implementations, but each boundary still carries a
wrapper method whose entire content is the field string:
`RiskTree.requireDistinctSeedVarIds` supplies `"nodes.seedVarId"` and
`RiskTreeRequests.requireUniqueSeedVarIds` supplies `"request.seedVarIds"`. The
name rule is now at exactly that level after M2-D10, with
`SafeName.duplicates` shared and two wrappers above it. Four wrapper methods
exist across the two rules, and none of them decides anything.

They exist because the locator is a bare `String` that has to be typed in at
the call site. TES-D-1 removes that: `ValidationError` takes
`location: ErrorLocation`, a closed set of cases, so the locator becomes a
value a caller names rather than spells. That is the "constant or enum" the
ruling asks for, and it is this plan's Phase 2 rather than something to invent
alongside it. So the collapse is designed against `ErrorLocation` and lands with
Phase 2.

The shape, stated as the design question Phase 2 resolves rather than as a
settled signature: one generic check over a collection, parameterised by the
locator and the error code. The two rules differ in one way that decides it —
the name rule reports the repeated values themselves, while the seed rule
reports each repeated identifier **together with the nodes holding it**
(`ValidationMessages.seedVarIdInUse(id, holders)`). A single function therefore
takes labelled elements, `Seq[(String, A)]`, where the name rule passes the name
as both label and value. Whether that is better than two functions — a detail
check and a labelled check — is settled when Phase 2 is written, with both
spelled out.

The reason the work is sequenced here rather than done with M2-D10 is cost.
Six assertions read one of the two name texts today:

- `ScenarioMergeServiceSpec.scala:248`, `:325` and
  `ScenarioMergeServiceItSpec.scala:289` reach the text through the guard, as
  `TreeLoadFailure.reason`. §10 already replaces those with assertions on the
  typed `MergeRefusal.InvariantBroken` map, so after Phase 1 they no longer read
  message text at all.
- `RiskTreeDefinitionRequestsSpec.scala:113`, `:251` and
  `TreeBuilderLogicSpec.scala:27` reach it through request validation and would
  change with the message.

So unifying before Phase 1 costs six assertion updates and unifying after it
costs three. The three that survive are the request-boundary ones, and they
change because the message text unifies, which is the one part of this that is
visible from outside.

The four wrappers that disappear are `RiskTree.requireDistinctNodeNames`,
`RiskTree.requireDistinctSeedVarIds`, `RiskTreeRequests.requireUniqueNames` and
`RiskTreeRequests.requireUniqueSeedVarIds`. `requireUniqueNames` also returns
the deduplicated `Set[SafeName]` its callers use, so its call site keeps that
`.map`; the check itself moves.

**`modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala`** —
the two guard cases assert the typed refusal, and one new case asserts that the
409 body of a guard-undone merge contains no `workspaces/` substring, against a
real Irmin.

### Commands that must be green

```bash
sbt compile                              # zero code warnings
sbt commonJVM/test
sbt server/test
sbt app/test
sbt "serverIt/test"                      # needs local/irmin-prod:3.11-p1
```

Before `serverIt/test`, clear leaked per-run Docker state — mandatory, not
crash recovery; the command is in the `register-dev` skill and filters on the
`register_it_` prefix across containers, networks and volumes.

Expected output from each test command is `0 tests failed`.

### On landing

A PATCH version bump, because shipped code changes: `build.sbt`'s
`ThisBuild / version` moves from its then-current value, and `APP_VERSION` is
mirrored into both `.env` and `.env.irmin`. The current value is `0.10.44`.

`docs/dev/TODO.md` item 54 is deleted. It recorded this work as a future item;
once this plan's scope is landed the item is spent, and nothing in it is being
deferred.

A13's two index rows in `docs/dev/plans/IMPLEMENTATION-PLAN.md` and the forward
pointer beside its rationale paragraph were amended on 2026-10-10, when TES-D-9
narrowed the item. The remaining passages in that document which describe the
collapsed 404 as shipped are accurate for the code as it stands, so they are
swept when §4.8 lands rather than ahead of it: the W.3 resolve-flow step that
routes `WorkspaceExpired` to the same 404 as not-found, the verification item
asserting the two are identical, and the frontend-classification section that
describes the sentinel. This is part of §4.8's landing, not a follow-up.
