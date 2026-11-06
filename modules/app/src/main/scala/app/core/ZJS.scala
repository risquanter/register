package app.core

import zio.*

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sttp.tapir.Endpoint

import app.state.LoadState
import app.state.SubmitState
import com.risquanter.register.http.errors.WireError

/** ZIO-JS bridge: extension methods for running ZIO effects in the Laminar/browser environment.
  *
  * Pattern adapted from cheleb/rockthejvm — provides:
  *   - `emitTo(bus)` — fork a ZIO and push its result into a Laminar EventBus
  *   - `runJs`       — fork a ZIO for side-effects only (fire-and-forget)
  *   - `toEventStream`— fork a ZIO and return the result as a Laminar EventStream
  *   - `endpoint(payload)` — turn a Tapir endpoint + payload into a `RIO[BackendClient, O]`
  *
  * All effects are forked on `Runtime.default` with `BackendClientLive.configuredLayer`
  * so callers never need to manually provide the ZIO environment.
  *
  * An optional `ErrorObserver` can be registered once at app startup to show a
  * condition of the session in the global banner and to clear it on success.
  */
object ZJS:

  /** Observer for cross-cutting error/success reporting (the global banner).
    *
    * Registered once at app startup via `registerErrorObserver`. Every ZIO effect
    * forked through `forkProvided` notifies this observer unless its caller opts
    * out with `runJsQuiet`.
    */
  trait ErrorObserver:
    /** Called when any forked effect fails. The observer decides whether the
      * failure reveals a condition the banner shows, and returns nothing
      * because setting that state is its whole job.
      */
    def onError(error: RequestFailure): Unit
    /** Called when any forked effect succeeds — used to auto-dismiss stale banners. */
    def onSuccess(): Unit

  private var errorObserver: Option[ErrorObserver] = None

  /** Register the global error observer. Called once from Main.scala at startup. */
  def registerErrorObserver(observer: ErrorObserver): Unit =
    errorObserver = Some(observer)

  /** Fork a ZIO on the default runtime with BackendClient provided.
    *
    * This is the single integration point between the ZIO and Laminar worlds.
    * All public extension methods delegate here to avoid duplicating the
    * Unsafe / Runtime / provide boilerplate.
    *
    * If an `ErrorObserver` is registered and `notifyObserver = true`,
    * every fork automatically:
    *  - reports errors via `onError` (the banner shows a session condition)
    *  - clears stale errors via `onSuccess` (auto-dismiss on recovery)
    */
  private def forkProvided[A](zio: ZIO[BackendClient, RequestFailure, A], notifyObserver: Boolean = true): Unit =
    Unsafe.unsafe { implicit unsafe =>
      val recorded = zio.tapError(f => ZIO.succeed(recordToConsole(f)))

      val withObserver =
        if notifyObserver then
          recorded
            .tapError(f => ZIO.succeed(errorObserver.foreach(_.onError(f))))
            .tap(_ => ZIO.succeed(errorObserver.foreach(_.onSuccess())))
        else recorded

      Runtime.default.unsafe.fork(
        withObserver
          .provide(BackendClientLive.configuredLayer)
      )
    }

  /** Write every failure to the browser console, whichever kind it is and
    * whether or not the banner shows it.
    *
    * A response body carries no field naming its own case — the status names it
    * — so this line is what connects a response seen in the network tab to the
    * case the browser classified it as. It is written here rather than in a
    * view because a view re-renders and would log the same failure again on
    * every render.
    *
    * Nothing is exposed that was not already visible: the body carrying the
    * trace is in the network tab of the same developer tools, and a `WireError`
    * holds no internal field by construction (ADR-036).
    */
  private def recordToConsole(failure: RequestFailure): Unit = failure match
    case RequestFailure.Wire(error) =>
      dom.console.warn(s"request failed: ${error.productPrefix} trace=${error.trace.value}")
    case RequestFailure.Transport(cause) =>
      dom.console.warn(s"request failed: Transport ${cause.safeMessage}")
    case RequestFailure.Undecodable(status) =>
      dom.console.warn(s"request failed: Undecodable status=${status.code}")


  // ---------------------------------------------------------------------------
  // Extensions on ZIO[BackendClient, E, A]
  // ---------------------------------------------------------------------------

  extension [A](zio: ZIO[BackendClient, RequestFailure, A])

    /** Fork the effect and emit its successful result into an EventBus.
      *
      * A failure is written to the browser console by `forkProvided`, so it is
      * not logged again here.
      */
    def emitTo(bus: EventBus[A]): Unit =
      forkProvided {
        zio.tap(a => ZIO.succeed(bus.emit(a)))
      }

    /** Fork the ZIO for its side-effects only (fire-and-forget). */
    def runJs: Unit =
      forkProvided(zio)

    /** Fork the ZIO for side-effects without notifying the global ErrorObserver.
      *
      * Use for ambient telemetry/probes that should not surface in the
      * global user-facing error banner.
      */
    def runJsQuiet: Unit =
      forkProvided(zio, notifyObserver = false)

    /** Fork the ZIO and return its result as a one-shot Laminar EventStream. */
    def toEventStream: EventStream[A] =
      val eventBus = EventBus[A]()
      emitTo(eventBus)
      eventBus.events

    /** Like `toEventStream`, but also emits on failure instead of staying
      * silent — needed by any caller that wants to `flatMapSwitch` a
      * sequence of requests into a single derived result: `flatMapSwitch`
      * only supersedes a stale request when the newer one's inner stream
      * actually emits *something*; a failed request that never emits would
      * leave the derived signal stuck showing the last state the stale
      * request reached before being superseded (e.g. "Loading" forever).
      *
      * `.tapError`/`.tap` here only observe the outcome — they don't alter
      * the success/failure channel — so `forkProvided`'s own error-observer
      * hook (global banner) still sees the exact same success/failure this
      * ZIO would have produced via plain `toEventStream`, which stays silent
      * on failure.
      */
    def toOutcomeEventStream: EventStream[Either[RequestFailure, A]] =
      val eventBus = EventBus[Either[RequestFailure, A]]()
      forkProvided {
        zio
          .tapError(f => ZIO.succeed(eventBus.emit(Left(f))))
          .tap(a => ZIO.succeed(eventBus.emit(Right(a))))
      }
      eventBus.events

  // ---------------------------------------------------------------------------
  // Extensions on ZIO + LoadState — DRY the tap/tapError/runJs lifecycle
  // ---------------------------------------------------------------------------

  extension [A](zio: ZIO[BackendClient, RequestFailure, A])

    /** Fork the effect and push its lifecycle into a `Var[LoadState[A]]`.
      *
      * Sets Loading → Loaded on success and Loading → Failed on a failure,
      * whatever the failure, because a load's result is shown where it is
      * displayed (ADR-019 decision 7).
      */
    def loadInto(target: Var[LoadState[A]]): Unit =
      target.set(LoadState.Loading)
      forkProvided {
        zio
          .tap(a => ZIO.succeed(target.set(LoadState.Loaded(a))))
          .tapError(f => ZIO.succeed(target.set(LoadState.Failed(f))))
      }

  // ---------------------------------------------------------------------------
  // Trigger → outcome request pipelines (supersede stale in-flight requests)
  // ---------------------------------------------------------------------------

  /** `flatMapSwitch` over a stream of "what should I be loading right now"
    * triggers — `None` resets to `idle`, `Some(request)` emits `loading` and
    * then `settle` applied to the request's result. Whenever the trigger
    * stream emits again (a new request, or a reset), Airstream drops the
    * subscription to whatever the previous trigger's request stream was
    * still doing, so a stale response can never overwrite a newer one.
    *
    * Known, deliberate gap shared by every user: superseding stops
    * *observing* the old request's stream, it does not cancel the underlying
    * ZIO fiber (`forkProvided` discards the fiber handle) — the abandoned
    * network call still completes server-side, its result just goes nowhere.
    *
    * @param settle Maps one emission of the request's stream (typically the
    *   `Either` from `toOutcomeEventStream`) to the outcome value.
    */
  def requestPipeline[A, O](
    triggers: EventStream[Option[() => EventStream[A]]],
    idle: O,
    loading: O,
    settle: A => O
  ): EventStream[O] =
    triggers.flatMapSwitch[O, EventStream, EventStream] {
      case None => EventStream.fromValue(idle, emitOnce = true)
      case Some(request) =>
        EventStream.fromValue(loading, emitOnce = true).mergeWith(request().map(settle))
    }

  /** `requestPipeline` specialized to the `LoadState` lifecycle: every failure
    * resolves to `Failed(failure)`, as in `loadInto`. The request streams are
    * expected to come from `toOutcomeEventStream`, which already notified the
    * global `ErrorObserver` — this pipeline only decides what the target
    * displays.
    */
  def loadStatePipeline[A](
    triggers: EventStream[Option[() => EventStream[Either[RequestFailure, A]]]]
  ): EventStream[LoadState[A]] =
    requestPipeline[Either[RequestFailure, A], LoadState[A]](
      triggers,
      idle    = LoadState.Idle,
      loading = LoadState.Loading,
      settle  = {
        case Right(a) => LoadState.Loaded(a)
        case Left(f)  => LoadState.Failed(f)
      }
    )

  // ---------------------------------------------------------------------------
  // Extensions on ZIO + SubmitState — DRY the submit lifecycle
  // ---------------------------------------------------------------------------

  extension [A](zio: ZIO[BackendClient, RequestFailure, A])

    /** Fork the ZIO and drive a `Var[SubmitState]` through its lifecycle.
      *
      * Sets Submitting → Success (via onSuccess callback) on success,
      * Submitting → Failed(failure) on error. Mirrors `loadInto` but for
      * the submit lifecycle where success requires caller-defined side-effects
      * (e.g. updating editing state, refreshing tree list).
      *
      * The submitting view shows every failure. The global observer is
      * notified as for every fork, and the banner takes only a failure that
      * reveals a condition of the session (`GlobalError.from`).
      */
    def submitInto(target: Var[SubmitState])(onSuccess: A => Unit): Unit =
      target.set(SubmitState.Submitting)
      forkProvided(
        zio
          .tap(a => ZIO.succeed(onSuccess(a)))
          .tapError(f => ZIO.succeed(target.set(SubmitState.Failed(f))))
      )

  // ---------------------------------------------------------------------------
  // Extension on unsecured Tapir endpoints
  // ---------------------------------------------------------------------------

  /** Turn an endpoint description + payload into `RIO[BackendClient, O]`.
    *
    * Usage:
    * {{{
    *   import app.core.ZJS.*
    *
    *   healthEndpoint(())           // RIO[BackendClient, ProbeResponse]
    *     .emitTo(healthBus)
    *
    *   createEndpoint(requestDto)   // RIO[BackendClient, (TreeLocation, TreeSummaryResponse)]
    *     .emitTo(responseBus)
    * }}}
    */
  extension [I, O](endpoint: Endpoint[Unit, I, WireError, O, Any])
    def apply(payload: I): ZIO[BackendClient, RequestFailure, O] =
      ZIO.service[BackendClient].flatMap(_.endpointRequestZIO(endpoint)(payload))
