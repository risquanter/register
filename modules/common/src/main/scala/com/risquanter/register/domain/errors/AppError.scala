package com.risquanter.register.domain.errors

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.ValidationMessages
import vql.error.{BindErrorDetail, QueryError}

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
  * wire case, and they need not share an attribution. Reading the level from
  * the wire case would give one level to all of them.
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

sealed trait DomainError extends AppError

/** One refusal: where it is and what it is.
  *
  * The one internal error type that is also a wire type: it crosses inside
  * `WireError.ValidationRejected`, because a form needs to know which field
  * failed and why, and that is the whole content of this type. Both halves are
  * closed sets, so no sentence crosses: the browser renders one from the
  * problem and its values, and a log line renders its own (ADR-035).
  */
case class ValidationError(
  location: ErrorLocation,
  problem: FieldProblem
)

object ValidationError:
  given codec: JsonCodec[ValidationError] = DeriveJsonCodec.gen[ValidationError]

  /** The refusal as diagnostic text, for a log line or a decoder's flattened
    * message: the location, then the rendered sentence.
    *
    * {{{
    * describe(ValidationError(requestField(FieldName.Name), FieldProblem.Required))
    * // [request.Name] A value is required
    * }}}
    */
  def describe(error: ValidationError): String =
    s"[${ErrorLocation.describe(error.location)}] ${ValidationMessages.render(error.problem)}"

/** Validation failure with accumulated structured errors */
case class ValidationFailed(errors: List[ValidationError]) extends DomainError {
  override def logMessage: String = errors.map(ValidationError.describe).mkString("; ")
  override def origin: FailureOrigin = FailureOrigin.Caller
}

/** Simulation execution failure - wraps underlying cause with context */
case class SimulationFailure(simulationId: String, cause: Throwable) extends DomainError {
  override def logMessage: String = s"Simulation $simulationId failed: ${cause.getMessage}"
  override def origin: FailureOrigin = FailureOrigin.Service
}

/** Scenarios are unavailable in this deployment (repository.type=in-memory has no
  * Irmin to hold branches). A deliberate, expected condition — not a server
  * malfunction — so it gets its own type, which projects onto `NotSupported`
  * and answers 501 rather than an internal fault's 500. The reason stays in
  * the server's log: the wire case names the condition, and the browser
  * composes what a user reads from that case. `ScenarioServiceNotSupported`
  * fails every method with this.
  */
case class ScenariosNotSupported(reason: String) extends DomainError {
  override def logMessage: String = s"Scenarios not supported: $reason"
  override def origin: FailureOrigin = FailureOrigin.Caller
}

// ============================================================================
// FOL Query Errors (ADR-028: Vague Quantifier Query Evaluation)
// ============================================================================

/** Errors originating from vql-engine interaction or query preconditions.
  * The `Fol` prefix identifies types mapped from `vql.error.QueryError`
  * (the library's error algebra); the `Failure` suffix follows the
  * `SimulationFailure` naming convention.
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
    * join the set below to keep classifying as `FolUnknownReference`. Bound to
    * the catalog by the same drift-guard assertion as `NodeSortName`.
    */
  val NodeNameLiteralSortName: String = "NodeNameLiteral"

  /** Sorts whose unresolved quoted literal means "no such node", not a type
    * error: a bare node slot (`Node`) and the `named` name literal
    * (`NodeNameLiteral`). Both carry a node name that simply is not in the tree,
    * so a bind failure homogeneous in these sorts is `FolUnknownReference`. The
    * `has_id` id-literal sort is excluded: a malformed id is a genuine
    * `FolBindFailure`, and a well-formed-but-absent id parses and evaluates false.
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

  /** Maps from `vql.error.QueryError.DomainNotFoundError`.
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

  /** Centralised mapping from library `vql.error.QueryError` to register's
    * `FolQueryFailure` hierarchy. Used by both the controller (parse errors)
    * and the service layer (evaluation errors).
    */
  def fromQueryError(err: QueryError): FolQueryFailure =
    err match
      // ── Parse-phase errors → 422 ────────────────────────────────
      case e: QueryError.ParseError               => FolParseFailure(e.message, e.position)
      // ── Unknown constant/literal reference → 422 ────────────────
      // Unreachable for register (the binder folds this into BindError), kept correct.
      case e: QueryError.UnknownConstantOrLiteralError => FolUnknownReference(List(e.message))
      // ── Bind-phase errors → 422 ─────────────────────────────────
      // Every error an unresolved node reference → FolUnknownReference; any genuine
      // type error (arity, sort conflict, non-node constant) dominates → FolBindFailure.
      case e: QueryError.BindError                =>
        val allNodeUnresolved =
          e.details.nonEmpty && e.details.forall {
            case BindErrorDetail.UnparseableConstant(_, sortName, _, _) =>
              NodeReferenceSortNames.contains(sortName)
            case _                                                      => false
          }
        if allNodeUnresolved then FolUnknownReference(e.messages)
        else                      FolBindFailure(e.messages)
      // ── Domain-not-found → 422 (defensive fallback) ────────────
      case e: QueryError.DomainNotFoundError      =>
        FolDomainNotQuantifiable(
          s"Queries can only range over tree nodes (type 'Asset'). " +
          s"The type '${e.typeName}' cannot be enumerated. " +
          s"Available quantifiable types: ${e.availableTypes.mkString(", ")}")
      // ── Model-validation → 500 (wiring error) ──────────────────
      case e: QueryError.ModelValidationError     => FolModelValidationFailure(e.errors)
      // ── Evaluation-phase errors → 500 ───────────────────────────
      case e: QueryError.EvaluationError          => FolEvaluationFailure(e.message, e.phase)
      case e: QueryError.ValidationError          => FolEvaluationFailure(e.message, "validation")
      case e: QueryError.UnboundVariableError     => FolEvaluationFailure(e.message, "unbound_variable")
