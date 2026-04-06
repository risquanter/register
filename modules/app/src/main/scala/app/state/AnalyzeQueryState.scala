package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.http.responses.QueryResponse
import fol.error.QueryError
import fol.parser.VagueQueryParser

/** Reactive state for the Analyze view query pane (ADR-028).
  *
  * Owns the query input lifecycle: raw text → client-side parse
  * validation → server-side evaluation → result display.
  *
  * Views receive this as a constructor argument (ADR-019 Pattern 2).
  * Extends `WorkspaceEndpoints` for Tapir endpoint access via ZJS.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param selectedTreeId Signal for the currently selected tree ID.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class AnalyzeQueryState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  userIdAccessor: () => Option[UserId] = () => None
) extends WorkspaceEndpoints:

  // ── Query input ───────────────────────────────────────────────
  val queryInput: Var[String] = Var("")

  // ── Client-side parse validation (T3.1b) ──────────────────────
  //
  // VagueQueryParser.parse() runs in-browser (fol-engine cross-compiled
  // to JS via common). Validation is triggered by:
  //   - Blur (always) — textarea loses focus
  //   - Run / Ctrl+Enter — executeQuery() validates before submitting
  //   - Keystroke debounce (300ms) — only when instantValidate is on
  //
  // None when not yet validated or input is empty, Left(err) on syntax
  // error, Right(parsed) on valid parse. The library error type is used
  // directly on the JS side — no mapping to FolQueryFailure needed
  // (that type exists only for HTTP dispatch on the server).

  /** When true, parse validation runs on every keystroke (debounced 300ms). */
  val instantValidate: Var[Boolean] = Var(false)

  val parseResult: Var[Option[Either[QueryError, fol.logic.ParsedQuery]]] = Var(None)

  /** Run the parser against the current input and update parseResult. */
  def validateNow(): Unit =
    val text = queryInput.now()
    parseResult.set(
      if text.trim.isEmpty then None
      else Some(VagueQueryParser.parse(text))
    )

  /** Syntax error message for display, None when not yet validated or valid. */
  val parseError: Signal[Option[String]] =
    parseResult.signal.map {
      case Some(Left(err)) => Some(err.formatted)
      case _               => None
    }

  /** True when the last validation passed. */
  val isParseValid: Signal[Boolean] =
    parseResult.signal.map {
      case Some(Right(_)) => true
      case _              => false
    }

  // ── Server-side evaluation result ─────────────────────────────
  val queryResult: Var[LoadState[QueryResponse]] = Var(LoadState.Idle)

  /** Node IDs matching the last successful query (derived, read-only). */
  val matchingNodeIds: Signal[Set[NodeId]] =
    queryResult.signal.map {
      case LoadState.Loaded(resp) => resp.matchingNodeIds.toSet
      case _                     => Set.empty
    }

  /** True while a query is in flight. */
  val isExecuting: Signal[Boolean] =
    queryResult.signal.map {
      case LoadState.Loading => true
      case _                 => false
    }

  // ── Actions ───────────────────────────────────────────────────

  /** Fire a query against the backend.
    *
    * Sends `POST /w/{key}/risk-trees/{treeId}/query` with the current
    * `queryInput` text. Result lifecycle tracked in `queryResult` via
    * `ZJS.loadInto`. No-op if no workspace or tree is selected.
    */
  def executeQuery(): Unit =
    val queryText = queryInput.now()
    if queryText.trim.isEmpty then ()
    else
      // Validate client-side first — update parseResult for UI feedback
      val parsed = VagueQueryParser.parse(queryText)
      parseResult.set(Some(parsed))
      parsed match
        case Left(_) => () // Parse error — displayed via parseResult, do not submit
        case Right(_) =>
          (keySignal.now(), selectedTreeId.now()) match
            case (Some(key), Some(treeId)) =>
              queryWorkspaceTreeEndpoint((userIdAccessor(), key, treeId, QueryRequest(queryText)))
                .loadInto(queryResult)
            case _ => () // No workspace or tree selected
