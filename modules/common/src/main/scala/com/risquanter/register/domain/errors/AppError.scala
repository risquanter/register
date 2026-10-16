package com.risquanter.register.domain.errors

import java.time.{Duration, Instant}
import scala.concurrent.duration.Duration as ScalaDuration
import sttp.model.StatusCode
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.ExpiryCause
import com.risquanter.register.domain.data.iron.{CommitHash, ScenarioName, StoreBranch, TreeId, WorkspaceId}

/** Who a failure is attributable to, which decides the level its log line is
  * written at (ADR-002).
  *
  * `Caller` is ordinary traffic: the caller sent something this service
  * correctly refused, so the line records what happened and nothing is wrong
  * here. `Service` is an operational signal: something this service depends on
  * failed, or it has a defect.
  *
  * The distinction is drawn on the internal failure rather than on the wire
  * case, because the wire is deliberately coarser: several failures share one
  * wire case, and they do not share an attribution. `WireError.InternalFault`
  * carries both `RepositoryFailure`, which is this service's own, and
  * `BranchAlreadyExists`, which is a caller's. Reading the level from the wire
  * case would give one level to both.
  */
enum FailureOrigin:
  case Caller
  case Service

/** Every failure the application produces as a value.
  *
  * Plain data, and deliberately not a `Throwable`. An exception is how the
  * platform reports a defect; these are expected outcomes a caller is meant to
  * handle. Keeping the two apart is what lets a signature name what can go
  * wrong: `IO[AppError, A]` states the closed set, where `Task[A]` would admit
  * anything and a widening would be invisible.
  *
  * `logMessage` is the server's own text and belongs in the log. The name says
  * where it goes: it never crosses to a client, because the browser composes
  * what a user reads from the wire case and its typed values, so no internal
  * reason can leak through a free-text field (ADR-035).
  *
  * The trace a failure ran under is not held here. It is read from the active
  * span at the boundary that projects onto the wire, which is the only place an
  * effect exists to read it.
  */
sealed trait AppError:
  def logMessage: String

  /** Who this failure is attributable to. Abstract rather than defaulted: a new
    * case cannot compile until it says which it is, so no failure reaches the
    * log at a level nobody chose.
    */
  def origin: FailureOrigin

  /** Structured fields for this failure's log line, queryable rather than
    * embedded in `logMessage`. Empty unless a case has values worth filtering
    * on. A confidential internal identifier may appear here, because this
    * reaches a server log and never the wire (ADR-036).
    */
  def logAnnotations: Map[String, String] = Map.empty

sealed trait SimError extends AppError
sealed trait IrminError extends AppError

/** Validation error with structured information.
  *
  * The one internal error type that is also a wire type: it crosses inside
  * `WireError.ValidationRejected`, because a form needs to know which field
  * failed and why, and that is the whole content of this type.
  */
case class ValidationError(
  field: String,
  code: ValidationErrorCode,
  message: String
)

object ValidationError:
  // JSON codec (companion object placement ensures implicit scope)
  given codec: JsonCodec[ValidationError] = DeriveJsonCodec.gen[ValidationError]

/** Validation failure with accumulated structured errors */
case class ValidationFailed(errors: List[ValidationError]) extends SimError {
  override def logMessage: String = errors.map(e => s"[${e.field}] ${e.message}").mkString("; ")
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Repository operation failure */
case class RepositoryFailure(reason: String) extends SimError:
  override def logMessage: String = reason
  override def origin: FailureOrigin = FailureOrigin.Service

/** One tree in a workspace listing could not be loaded.
  *
  * Separate from `RepositoryFailure`, which also covers failures that are about
  * no single tree — a workspace record, a branch head that will not refine, a
  * failure rebuilt from a wire that never carried an identity. Here the tree is
  * always known, so its id is a field rather than text inside `reason`, and
  * every caller can read it without parsing prose.
  */
case class TreeLoadFailure(treeId: TreeId, reason: String) extends SimError:
  override def logMessage: String = s"Tree ${treeId.value} could not be loaded: $reason"
  override def origin: FailureOrigin = FailureOrigin.Service
  override def logAnnotations: Map[String, String] = Map("tree_id" -> treeId.value)

/** Simulation execution failure - wraps underlying cause with context */
case class SimulationFailure(simulationId: String, cause: Throwable) extends SimError {
  override def logMessage: String = s"Simulation $simulationId failed: ${cause.getMessage}"
  override def origin: FailureOrigin = FailureOrigin.Service
}

/** Data conflict (e.g., duplicate key) */
case class DataConflict(reason: String) extends SimError:
  override def logMessage: String = reason
  override def origin: FailureOrigin = FailureOrigin.Caller

/** Authorization failure for disabled/forbidden operations */
case class AccessDenied(reason: String) extends SimError:
  override def logMessage: String = reason
  override def origin: FailureOrigin = FailureOrigin.Caller

// ============================================================================
// Workspace Errors — all three encode to one opaque 404, so a caller cannot
// tell which of them it was.
// ============================================================================

/** A workspace key that resolved to no live workspace.
  *
  * Carries nothing. At the point this is built the lookup has already failed,
  * so there is no workspace identity to name, and the capability key is not a
  * field: a credential is a `final class` so that no `copy`, `unapply` or
  * product serialisation exists for it, and a `case class` holding one returns
  * all three for the wrapper (ADR-022 R1).
  *
  * A parameterless `case class` rather than a `case object`, so that it stays
  * uniform with the hierarchy's other payload-free cases.
  */
case class WorkspaceNotFound() extends SimError {
  override def logMessage: String = "Workspace not found"
  override def origin: FailureOrigin = FailureOrigin.Caller

  /** Carries the resolve-failure marker even though there is nothing to name.
    *
    * A key that resolves to nothing is the only signal that one is being
    * replayed after it was rotated, deleted or guessed, and the marker is what
    * lets that line be selected alongside the workspace lifecycle events rather
    * than found by searching message text. There is no identifier to annotate:
    * the key did not resolve, so no workspace is known, and the key itself may
    * never be logged (ADR-022 R3).
    */
  override def logAnnotations: Map[String, String] = Map(
    "event_type" -> "workspace.resolve_failed",
    "reason"     -> "not_found"
  )
}

/** A workspace past its absolute or idle timeout.
  *
  * Carries what a diagnosis needs, because nothing reconstructs this type: the
  * browser receives a `WireError`, never one of these, and that wire case
  * declares no workspace identifier (ADR-036).
  *
  * `cause`, `ttl` and `idleTimeout` are what cross: a workspace has two
  * deadlines, so a browser told only a figure would state the wrong one half the
  * time. `expiredAt` stays internal — it is a fact about a single workspace, and
  * it is what makes a log line answer "how long ago" rather than only "how long
  * were they given".
  *
  * `logMessage` names none of them, which ADR-035 §3 requires: a log line reads
  * them as annotations, where they are queryable, rather than as text in a
  * sentence.
  */
case class WorkspaceExpired(
  wsId:        WorkspaceId,
  ttl:         Duration,
  idleTimeout: Duration,
  cause:       ExpiryCause,
  expiredAt:   Instant
) extends SimError {
  override def logMessage: String = "Workspace expired"
  override def origin: FailureOrigin = FailureOrigin.Caller
  override def logAnnotations: Map[String, String] = Map(
    "event_type"   -> "workspace.resolve_failed",
    "reason"       -> "expired",
    "workspace_id" -> wsId.value,
    "expiry_cause" -> cause.toString,
    "expired_at"   -> expiredAt.toString
  )
}

/** A tree the resolved workspace does not hold.
  *
  * `wsId` is a diagnostic for the server's own logs. It stays out of
  * `logMessage`, which may name no confidential internal identifier
  * (ADR-035 §3), and out of every response (ADR-036). The tree identifier is
  * not confidential and is the useful part of the message.
  */
case class TreeNotInWorkspace(wsId: WorkspaceId, treeId: TreeId) extends SimError {
  override def logMessage: String = s"Tree ${treeId.value} not found in workspace"
  override def origin: FailureOrigin = FailureOrigin.Caller
  override def logAnnotations: Map[String, String] = Map(
    "workspace_id" -> wsId.value,
    "tree_id"      -> treeId.value
  )
}

// ============================================================================
// Infrastructure Errors (ADR-010: Error Handling Strategy)
// ============================================================================

/** External service (Irmin) is unavailable - transient, retriable */
case class IrminUnavailable(reason: String) extends IrminError {
  override def logMessage: String = s"Irmin service unavailable: $reason"
  override def origin: FailureOrigin = FailureOrigin.Service
}

/** Irmin HTTP-layer error (non-503) */
case class IrminHttpError(status: StatusCode, body: String) extends IrminError {
  override def logMessage: String = s"HTTP ${status.code}: $body"
  override def origin: FailureOrigin = FailureOrigin.Service
}

/** GraphQL error surface from Irmin */
case class IrminGraphQLError(messages: List[String], path: Option[List[String]]) extends IrminError {
  override def logMessage: String = messages.mkString("; ")
  override def origin: FailureOrigin = FailureOrigin.Service
  def fieldPath: String = path.filter(_.nonEmpty).map(_.mkString(".")).getOrElse("graphql")
}

/** Network operation timed out - transient, retriable */
case class NetworkTimeout(operation: String, duration: ScalaDuration) extends IrminError {
  override def logMessage: String = s"Network timeout after ${duration.toMillis}ms during: $operation"
  override def origin: FailureOrigin = FailureOrigin.Service
}

/** Compare-and-set branch-create rejected: `test_and_set_branch`
  * with `test: null` failed because the branch already has a head. Not a
  * transport failure — Irmin answered correctly, the precondition (branch must
  * not yet exist) just didn't hold. Framed in IrminClient's own vocabulary
  * (branch), not scenario vocabulary: whether this means "scenario name taken"
  * is a judgment ScenarioService makes one layer up, the same way `IrminGraphQLError`
  * is translated into the domain-level `MergeConflict` below, not inside IrminClient.
  * Non-retriable as-is.
  *
  * Carries either branch kind, because the client builds it from whichever
  * branch it was asked to create. Which situation it means differs by kind, and
  * each caller translates it accordingly: a scenario branch means the name is
  * taken (`DataConflict`), a staging branch means a merge of that scenario is
  * already running (`MergeAlreadyRunning`). */
case class BranchAlreadyExists(branch: StoreBranch) extends IrminError {
  override def logMessage: String = s"Branch already exists: ${branch.name}"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Compare-and-set branch-delete rejected: `test_and_set_branch`
  * with `test: expectedHead` failed because the branch's actual head no longer
  * matches — concurrent modification since it was last read. Non-retriable
  * without re-reading the branch's current state first. Carries either branch
  * kind, for the reason given on `BranchAlreadyExists`. */
case class BranchHeadStale(branch: StoreBranch, expectedHead: CommitHash) extends IrminError {
  override def logMessage: String = s"Branch ${branch.name} head is not ${expectedHead.value}"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** A merge refused by the (patched) Irmin backend: the two branches hold
  * conflicting values for at least one path, so no merge commit was created
  * and the target branch's head is unchanged. Raised only by
  * `IrminClient.mergeBranch`, which recognises the patched resolver's
  * `"merge conflict: "` error prefix; `reason` is Irmin's conflict
  * description with that prefix stripped. Not retriable as-is — the
  * branches must first be brought into byte agreement (ADR-032). */
case class IrminMergeConflict(reason: String) extends IrminError {
  override def logMessage: String = s"Irmin merge conflict: $reason"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Optimistic locking conflict - client should refresh and retry */
case class VersionConflict(nodeId: String, expected: String, actual: String) extends SimError {
  override def logMessage: String = s"Version conflict on node $nodeId: expected $expected, found $actual"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Branch merge conflict — requires user intervention. Names the scenario,
  * never the Irmin `BranchRef`, which embeds the `WorkspaceId` (ADR-036). A
  * merge always targets a scenario, so main is not a reachable value here and
  * the scenario's own name is the precise type.
  *
  * `refusal` is a closed set rather than composed prose, so the remedy the
  * browser offers is chosen from the case and not parsed out of a sentence, and
  * no repository text can reach a client through it. The log line renders the
  * same value through `MergeRefusal.describe`.
  */
case class MergeConflict(scenario: ScenarioName.ScenarioName, refusal: MergeRefusal) extends SimError {
  override def logMessage: String =
    s"Merge conflict on scenario ${scenario.value}: ${MergeRefusal.describe(refusal)}"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** A merge of this scenario is already running.
  *
  * A merge assembles on a staging branch named from the scenario, so a second
  * concurrent merge of the same scenario collides on that name and is refused
  * here rather than running alongside the first. Nothing was written. Retriable
  * once the first merge finishes.
  */
case class MergeAlreadyRunning(scenario: ScenarioName.ScenarioName) extends SimError {
  override def logMessage: String =
    s"A merge of scenario ${scenario.value} is already running"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Main changed while this merge was being assembled, so the merge was not
  * published.
  *
  * The merge is assembled on a staging branch and published to main with one
  * compare-and-set against the head read before it started. A commit landing on
  * main in between makes that publish fail, which is what stops the merge from
  * absorbing the commit and then discarding it. Main is untouched; re-run the
  * preview and retry.
  */
case class MergeTargetMoved(scenario: ScenarioName.ScenarioName) extends SimError {
  override def logMessage: String =
    s"Main changed while merging scenario ${scenario.value}; re-run the preview and retry"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Scenario delete/duplicate rejected — the branch's head no longer matches
  * what the caller last observed (concurrent modification). Domain-level
  * translation of `BranchHeadStale`. Names the scenario for the same reason as
  * `MergeConflict` (ADR-036): a stale head is always a scenario's, so main is
  * not a reachable value. `actual` is `None` when the scenario no longer
  * exists at all.
  */
case class ScenarioHeadStale(scenario: ScenarioName.ScenarioName, expected: CommitHash, actual: Option[CommitHash]) extends SimError {
  override def logMessage: String =
    s"Scenario ${scenario.value} head is not ${expected.value}" +
    actual.fold(" (scenario no longer exists)")(a => s" (currently ${a.value})")
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Scenarios are unavailable in this deployment (repository.type=in-memory has no
  * Irmin to hold branches). A deliberate, expected condition — not a server
  * malfunction — so it gets its own type, which projects onto `NotSupported`
  * and answers 501 rather than `RepositoryFailure`'s 500. The reason stays in
  * the server's log: the wire case names the condition, and the browser
  * composes what a user reads from that case. `ScenarioServiceNotSupported`
  * fails every method with this.
  */
case class ScenariosNotSupported(reason: String) extends SimError {
  override def logMessage: String = s"Scenarios not supported: $reason"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

// ============================================================================
// Authorization Errors (ADR-024: Externalized Authorization / PEP Pattern)
// ============================================================================

sealed trait AuthError extends AppError

/** SpiceDB returned PERMISSIONSHIP_NO_PERMISSION — explicit deny.
  * HTTP layer maps this to 403 Forbidden.
  * userId is the JWT sub claim value (UUID string), safe for structured audit logs.
  *
  * @see ADR-024: Fail-Closed by Default
  */
case class AuthForbidden(
  userId:       String,
  permission:   String,
  resourceType: String,
  resourceId:   String
) extends AuthError:
  override def logMessage: String =
    s"Access denied: user=$userId permission=$permission resource=$resourceType:$resourceId"
  override def origin: FailureOrigin = FailureOrigin.Caller

/** SpiceDB unreachable, timed out, or returned an unexpected response.
  *
  * The request fails closed: no authorization decision was obtained, so none is
  * assumed. It projects onto `WireError.DependencyUnavailable`, the same answer
  * any other dependency outage produces, so the caller is told a service did
  * not answer rather than that their access was refused — and which dependency
  * it was stays internal (ADR-035 §2).
  *
  * @see ADR-024: Fail-Closed by Default
  */
case class AuthServiceUnavailable(reason: String, cause: Option[Throwable]) extends AuthError:
  override def logMessage: String = s"Authorization service unavailable: $reason"

  override def origin: FailureOrigin = FailureOrigin.Service

// ============================================================================
// FOL Query Errors (ADR-028: Vague Quantifier Query Evaluation)
// ============================================================================

/** Errors originating from fol-engine interaction or query preconditions.
  * The `Fol` prefix identifies types mapped from `vql.error.QueryError`
  * (the library's error algebra); the `Failure` suffix follows the
  * `RepositoryFailure` / `SimulationFailure` naming convention (D13).
  *
  * @see ADR-028 §4 — Query Validation Before Evaluation
  */
sealed trait FolQueryFailure extends AppError

object FolQueryFailure:
  /** Node-sort discriminator: the `TypeId.value` the engine crosses in
    * `BindErrorDetail.UnparseableConstant.sortName` when a quoted token failed
    * the node-sort literal validator. `RiskTreeKnowledgeBase.NodeSort` declares
    * the catalog's `TypeId("Node")`; a drift-guard assertion in
    * `RiskTreeKnowledgeBaseSpec` binds the two, matching the `FolSymbols`
    * mirror-plus-drift convention. The catalog is server-side and off `common`'s
    * dependency graph, so this literal cannot be derived from it here.
    */
  val NodeSortName: String = "Node"

  /** How much of a parser message reaches the log line.
    *
    * The parser's "unexpected tokens" message renders every remaining token, so
    * its length follows the submitted query, which is bounded only by the 8 MiB
    * request-body cap. One line per failed request is written synchronously to
    * standard output, so an unbounded message makes a request's log cost scale
    * with its body. 300 characters is chosen here as enough to identify where a
    * query stopped parsing while keeping the line a fixed cost.
    *
    * The full message still crosses to the caller who sent the query — it is
    * their own text coming back, and bounding the query itself is a separate
    * input-bound question.
    */
  val LoggedMessageLimit: Int = 300

  /** The `TypeId.value` of the `named` predicate's name-literal argument sort.
    * A `named(x, "Nonexistent")` whose literal fails the name validator crosses
    * this sort in `UnparseableConstant.sortName`, not `NodeSortName`, so it must
    * join the set below to keep classifying as `UNKNOWN_REFERENCE`. Bound to the
    * catalog by the same drift-guard assertion as `NodeSortName`.
    */
  val NodeNameLiteralSortName: String = "NodeNameLiteral"

  /** Sorts whose unresolved quoted literal means "no such node", not a type
    * error: a bare node slot (`Node`) and the `named` name literal
    * (`NodeNameLiteral`). Both carry a node name that simply is not in the tree,
    * so a bind failure homogeneous in these sorts is `UNKNOWN_REFERENCE`. The
    * `has_id` id-literal sort is excluded: a malformed id is a genuine
    * `BIND_FAILED`, and a well-formed-but-absent id parses and evaluates false.
    */
  val NodeReferenceSortNames: Set[String] = Set(NodeSortName, NodeNameLiteralSortName)
  /** Maps from `vql.error.QueryError.ParseError`.
    *
    * Syntactic parse failure — the query string is not well-formed FOL syntax.
    * The parser rejected the input before any semantic analysis.
    *
    * Example: `"Q[>=]^{2/3 x (leaf(x))"` — missing closing brace after
    * the quantifier ratio produces `ParseError("Expected '}' ...", pos=14)`.
    *
    * Projects onto `QueryRejected` with an `Unparseable` problem — HTTP 422.
    */
  final case class FolParseFailure(message: String, position: Option[Int])
    extends FolQueryFailure:
    /** The parser's message, cut to `LoggedMessageLimit`, because the message
      * for unexpected trailing tokens renders the whole remainder of the query.
      */
    override def logMessage: String =
      val bounded =
        if message.length <= FolQueryFailure.LoggedMessageLimit then message
        else s"${message.take(FolQueryFailure.LoggedMessageLimit)}… (message truncated)"
      position.fold(bounded)(p => s"$bounded (at position $p)")
    override def origin: FailureOrigin = FailureOrigin.Caller

  /** Maps from a bind-phase failure whose every error is an unresolved node
    * reference — a quoted constant (e.g. a node name) that is not present in the
    * `TypeCatalog` node domain. A user-level query error: the named node does not
    * exist in the tree. Also maps from `UnknownConstantOrLiteralError`.
    *
    * Carries the engine's rendered messages, one per unresolved reference, so a
    * query naming several missing nodes reports them all. Mirrors `FolBindFailure`
    * so the projection onto the wire carries the whole list rather than only
    * the first entry.
    *
    * Example: `leaf_descendant_of(x, "NoSuchNode")` where `"NoSuchNode"` is
    * not a registered node name.
    *
    * Projects onto `QueryRejected` with `UnknownReferences` — HTTP 422.
    */
  final case class FolUnknownReference(messages: List[String])
    extends FolQueryFailure:
    override def logMessage: String =
      s"Unknown reference(s): ${messages.mkString("; ")}"
    override def origin: FailureOrigin = FailureOrigin.Caller

  /** Maps from `vql.error.QueryError.BindError`.
    *
    * The query is syntactically valid and all symbols are known, but the
    * typed bind phase (type-checking variable sorts against the catalog)
    * detected one or more type errors. This covers arity mismatches,
    * sort conflicts, and quantification over non-domain types.
    *
    * Example: `"Q[>=]^{1/2} x:Loss (gt_loss(x, 5000))"` — the type
    * `Loss` is a `ValueType` (computed output) and cannot be quantified
    * over; only `DomainType` sorts like `Asset` have finite domains.
    * The bind phase rejects this with `TypeNotQuantifiable("Loss")`.
    *
    * Projects onto `QueryRejected` with `TypeCheckFailed` — HTTP 422.
    */
  final case class FolBindFailure(errors: List[String])
    extends FolQueryFailure:
    override def logMessage: String =
      s"Query type-checking failed: ${errors.mkString("; ")}"
    override def origin: FailureOrigin = FailureOrigin.Caller

  /** Maps from `vql.error.QueryError.ModelValidationError`.
    *
    * The `RuntimeModel` does not satisfy the `TypeCatalog` contract:
    * a declared function or predicate has no dispatcher implementation,
    * or a `DomainType` has no registered element set. This is an
    * infrastructure / wiring error in `RiskTreeKnowledgeBase`, not a
    * user query error.
    *
    * Example: Register declares `lec: (Asset, Loss) → Probability` in
    * the catalog but the `RuntimeDispatcher` omits `lec` from its
    * `functionSymbols` set. `FolModel(catalog, model)` validation
    * catches this before any query is evaluated.
    *
    * Projects onto `InternalFault`, which carries only a trace — HTTP 500.
    */
  final case class FolModelValidationFailure(errors: List[String])
    extends FolQueryFailure:
    override def logMessage: String =
      s"Runtime model validation failed: ${errors.mkString("; ")}"
    override def origin: FailureOrigin = FailureOrigin.Service

  /** Maps from `vql.error.QueryError.DomainNotFoundError` (D14).
    *
    * The query attempts to quantify over a type that has no registered
    * domain in the `RuntimeModel`. In a correctly wired system this is
    * unreachable (the bind phase rejects non-domain quantification via
    * `TypeNotQuantifiable`). Mapped as a defensive fallback.
    *
    * Example: If a catalog declared `Loss` as `DomainType` (incorrect)
    * but the `RuntimeModel` had no domain set for `Loss`, evaluation
    * would reach this error. In practice, `FolModel` validation catches
    * such gaps first.
    *
    * Projects onto `QueryRejected` with `NotQuantifiable` — HTTP 422.
    */
  final case class FolDomainNotQuantifiable(message: String)
    extends FolQueryFailure:
    override def logMessage: String = message
    override def origin: FailureOrigin = FailureOrigin.Caller

  /** Maps from `vql.error.QueryError.EvaluationError` / `ValidationError` /
    * `UnboundVariableError`.
    *
    * Catch-all for unexpected evaluation-phase failures that occur after
    * binding succeeds. These indicate internal errors (dispatcher bugs,
    * resource exhaustion) rather than user query mistakes.
    *
    * Example: The dispatcher's `evalFunction("p95", args)` throws because
    * simulation data is corrupted, surfacing as
    * `EvaluationError("Division by zero", phase="function:p95")`.
    *
    * Projects onto `InternalFault`, which carries only a trace — HTTP 500.
    */
  final case class FolEvaluationFailure(message: String, phase: String)
    extends FolQueryFailure:
    override def logMessage: String = s"Evaluation failed in $phase: $message"
    override def origin: FailureOrigin = FailureOrigin.Service

  /** Register precondition — no `Fol` prefix because no fol-engine involvement.
    *
    * Simulation results have not yet been computed for the requested tree.
    * The user must run a simulation before querying.
    *
    * Example: POST `/w/{key}/risk-trees/{treeId}/query` before any
    * simulation has been triggered for `treeId`.
    *
    * Projects onto `Conflict` with `SimulationRequired` — HTTP 409.
    */
  final case class SimulationNotCached(treeId: TreeId)
    extends FolQueryFailure:
    override def logMessage: String =
      s"Simulation not cached for tree ${treeId.value}"
    override def origin: FailureOrigin = FailureOrigin.Caller
    override def logAnnotations: Map[String, String] = Map("tree_id" -> treeId.value)

  /** Centralised mapping from library `vql.error.QueryError` to register's
    * `FolQueryFailure` hierarchy. Used by both the controller (parse errors)
    * and the service layer (evaluation errors).
    *
    */
  def fromQueryError(err: vql.error.QueryError): FolQueryFailure =
    import vql.error.QueryError as QE
    import vql.error.BindErrorDetail
    err match
      // ── Parse-phase errors → 400 ────────────────────────────────
      case e: QE.ParseError               => FolParseFailure(e.message, e.position)
      // ── Unknown constant/literal reference → 400 ────────────────
      // Unreachable for register (the binder folds this into BindError), kept correct.
      case e: QE.UnknownConstantOrLiteralError => FolUnknownReference(List(e.message))
      // ── Bind-phase errors → 400 ─────────────────────────────────
      // Every error an unresolved node reference → UNKNOWN_REFERENCE; any genuine
      // type error (arity, sort conflict, non-node constant) dominates → BIND_FAILED.
      case e: QE.BindError                =>
        val allNodeUnresolved =
          e.details.nonEmpty && e.details.forall {
            case BindErrorDetail.UnparseableConstant(_, sortName, _, _) =>
              NodeReferenceSortNames.contains(sortName)
            case _                                                      => false
          }
        if allNodeUnresolved then FolUnknownReference(e.messages)
        else                      FolBindFailure(e.messages)
      // ── Domain-not-found → 400 (D14, defensive fallback) ───────
      case e: QE.DomainNotFoundError      =>
        FolDomainNotQuantifiable(
          s"Queries can only range over tree nodes (type 'Asset'). " +
          s"The type '${e.typeName}' cannot be enumerated. " +
          s"Available quantifiable types: ${e.availableTypes.mkString(", ")}")
      // ── Model-validation → 500 (wiring error) ──────────────────
      case e: QE.ModelValidationError     => FolModelValidationFailure(e.errors)
      // ── Evaluation-phase errors → 500 ───────────────────────────
      case e: QE.EvaluationError          => FolEvaluationFailure(e.message, e.phase)
      case e: QE.ValidationError          => FolEvaluationFailure(e.message, "validation")
      case e: QE.UnboundVariableError     => FolEvaluationFailure(e.message, "unbound_variable")
