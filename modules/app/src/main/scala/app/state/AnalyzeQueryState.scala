package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.domain.errors.FolQueryFailure
import com.risquanter.register.domain.errors.FolQueryFailure.*
import com.risquanter.register.http.endpoints.WorkspaceQueryEndpoints
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
  * Extends `WorkspaceQueryEndpoints` for Tapir endpoint access via ZJS.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param selectedTreeId Signal for the currently selected tree ID.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  * @param branchAccessor Returns this tab's active branch (BranchChoice) — BranchBar.
  */
final class AnalyzeQueryState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  selectedTreeId: StrictSignal[Option[TreeId]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None,
  branchAccessor: () => BranchChoice = () => BranchChoice.Main
) extends WorkspaceQueryEndpoints:

  // ── Query input ───────────────────────────────────────────────
  // Default query for development — comment out the next line to start with an empty field
  private val defaultQuery = "Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x), 50))" //
  // private val defaultQuery = "" //
  val queryInput: Var[String] = Var(defaultQuery)

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
  //
  // `queryResult`/`queryServerError` stay public `Var`s — every existing
  // reader (`AnalyzeView`'s `.signal` bindings) is unaffected — but neither
  // is written directly by `executeQuery`/`resetResult` any more. Both are
  // driven from one place: `outcome`, an `EventStream[QueryOutcome]` built
  // with `flatMapSwitch` over `triggerBus`. Each new trigger (a fresh
  // `executeQuery()` call, or a `resetResult()`) makes `flatMapSwitch` drop
  // its subscription to whatever the *previous* trigger's request stream was
  // still doing — a response for a superseded query can therefore never
  // land after a newer one has already started, regardless of which
  // request's server round trip happens to finish first. This replaces an
  // earlier hand-rolled "is this response still relevant" guard: Airstream
  // already has a combinator for exactly this ("supersede stale in-flight
  // work when a new trigger fires"), so there's no bespoke bookkeeping left
  // to keep correct by hand.
  //
  // One known, deliberate gap: `flatMapSwitch` stops *observing* the
  // superseded request's stream, it does not cancel the underlying ZIO
  // fiber (`forkProvided`, see `ZJS.scala`, discards the fiber handle) — the
  // abandoned network call still runs to completion server-side, its result
  // just goes nowhere. Tracked separately (TODO.md).
  val queryResult: Var[LoadState[QueryResponse]] = Var(LoadState.Idle)

  /** Server-side domain error (400 query failures) for inline display.
    *
    * Populated when `executeQuery()` receives a 400-level `FolQueryFailure`
    * (parse, unknown-symbol, bind, domain-not-quantifiable). Rendered as
    * `span.form-error` in the query panel, parallel to `parseError`.
    *
    * Cleared on every new query execution. Infra-level errors (500, network)
    * bypass this channel and route to `GlobalError` / `ErrorBanner` instead.
    *
    * @see Plan v2 §2.5 — Error Routing table
    */
  val queryServerError: Var[Option[String]] = Var(None)

  /** Node IDs satisfying the last successful query (derived, read-only). */
  val satisfyingNodeIds: Signal[Set[NodeId]] =
    queryResult.signal.map {
      case LoadState.Loaded(resp) => resp.satisfyingNodeIds.toSet
      case _                     => Set.empty
    }

  /** True while a query is in flight. */
  val isExecuting: Signal[Boolean] =
    queryResult.signal.map {
      case LoadState.Loading => true
      case _                 => false
    }

  // ── Trigger → outcome pipeline ─────────────────────────────────

  /** One request for the `outcome` stream to (re)switch to. `Reset` is a
    * trigger like any other — modelling "clear the result" as a value this
    * pipeline emits, not as a side-channel `Var.set` outside it, is what
    * lets `flatMapSwitch` also supersede an in-flight query the moment the
    * tree changes (today's separate `resetResult()` caller in `AnalyzeView`
    * still exists, it just enqueues a trigger now instead of writing state
    * directly).
    */
  private enum Trigger:
    case Reset
    case Run(key: WorkspaceKeySecret, treeId: TreeId, text: String, branch: BranchChoice)

  /** Outcome of one trigger, folding `queryResult`/`queryServerError` into a
    * single value so both stay in lockstep as one thing switches to the
    * next — there is no window where one has caught up to a new trigger and
    * the other hasn't.
    */
  private enum Outcome:
    case Idle
    case Loading
    case Loaded(resp: QueryResponse)
    case DomainError(message: String)

  private val triggerBus: EventBus[Trigger] = new EventBus[Trigger]

  // Built on the shared `requestPipeline` (see ZJS) — the same
  // "one request, or reset, supersedes whatever the previous one was still
  // doing" skeleton `ScenarioDiffState` and `ScenarioListState` use via
  // `loadStatePipeline`. Only the settle mapping is local: it needs the
  // `DomainError` case (inline 400-level query failures), which the plain
  // `LoadState` specialization can't carry.
  private val outcome: EventStream[Outcome] =
    requestPipeline[Either[Throwable, QueryResponse], Outcome](
      triggerBus.events.map {
        case Trigger.Reset => None
        case Trigger.Run(key, treeId, text, branch) =>
          Some(() => queryWorkspaceTreeEndpoint((userIdAccessor(), key, treeId, QueryRequest(text), branch)).toOutcomeEventStream)
      },
      idle    = Outcome.Idle,
      loading = Outcome.Loading,
      settle  = {
        case Right(resp) => Outcome.Loaded(resp)
        case Left(e: FolQueryFailure) if isQueryDomainError(e) => Outcome.DomainError(e.getMessage)
        // Infra failure: ZJS.toOutcomeEventStream already notified the
        // global ErrorObserver via forkProvided's own hook (unchanged) —
        // this stream only needs to resolve back out of Loading.
        case Left(_) => Outcome.Idle
      }
    )

  // App-lifetime subscription (AnalyzeQueryState lives for the app lifetime,
  // like TreeBuilderState's isEditDirtyVar wiring) — the one and only writer
  // of queryResult/queryServerError.
  outcome.foreach {
    case Outcome.Idle =>
      queryResult.set(LoadState.Idle)
      queryServerError.set(None)
    case Outcome.Loading =>
      queryResult.set(LoadState.Loading)
      queryServerError.set(None)
    case Outcome.Loaded(resp) =>
      queryResult.set(LoadState.Loaded(resp))
    case Outcome.DomainError(message) =>
      queryResult.set(LoadState.Idle)
      queryServerError.set(Some(message))
  }(using unsafeWindowOwner)

  // ── Actions ───────────────────────────────────────────────────

  /** Clear the last query's server result (and any inline server error)
    * without touching the input text or client-side parse state.
    *
    * Call this when the selected tree changes: `satisfyingNodeIds` derives
    * from `queryResult`, and `AnalyzeView.chartNodeIds` folds that set into
    * the chart's node selection — without this reset, a previous tree's
    * matched node IDs would keep flowing into the newly selected tree's
    * chart (and, for Compare mode, into curve fetches against the new tree
    * using node IDs that may not even exist there). Also supersedes an
    * in-flight query for the *previous* tree, if one was still running.
    */
  def resetResult(): Unit =
    triggerBus.emit(Trigger.Reset)

  /** Fire a query against the backend.
    *
    * Sends `POST /w/{key}/risk-trees/{treeId}/query` with the current
    * `queryInput` text. Result lifecycle tracked in `queryResult`.
    * No-op if no workspace or tree is selected.
    *
    * Error routing (ADR-010 / Plan v2 §2.5):
    *   - 400 domain errors (parse/symbol/bind) → `queryServerError` (inline)
    *   - 5xx / network / other → `GlobalError` → `ErrorBanner`
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
              triggerBus.emit(Trigger.Run(key, treeId, queryText, branchAccessor()))
            case _ => () // No workspace or tree selected

  /** True for `FolQueryFailure` subtypes that represent user-facing query
    * domain errors (HTTP 400). These route to the inline `queryServerError`
    * slot. Infra-level failures (500, 409) propagate to `GlobalError` / `ErrorBanner`.
    */
  private def isQueryDomainError(e: FolQueryFailure): Boolean = e match
    case _: FolParseFailure          => true
    case _: FolUnknownSymbol         => true
    case _: FolUnknownReference      => true
    case _: FolBindFailure           => true
    case _: FolDomainNotQuantifiable => true
    case _: FolModelValidationFailure => false
    case _: FolEvaluationFailure      => false
    case _: SimulationNotCached       => false
