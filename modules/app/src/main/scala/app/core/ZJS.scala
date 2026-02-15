package app.core

import zio.*

import com.raquo.laminar.api.L.*
import sttp.tapir.Endpoint

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
  * An optional `ErrorObserver` can be registered once at app startup to surface
  * network/server errors in the global error banner and auto-dismiss on success.
  */
object ZJS:

  /** Observer for cross-cutting error/success reporting (e.g. global error banner).
    *
    * Registered once at app startup via `registerErrorObserver`. Every ZIO effect
    * forked through `forkProvided` automatically notifies this observer, giving
    * universal banner coverage with zero per-call-site wiring.
    */
  trait ErrorObserver:
    /** Called when any forked ZIO fails (network error, server error, etc.). */
    def onError(error: Throwable): Unit
    /** Called when any forked ZIO succeeds — used to auto-dismiss stale banners. */
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
    * If an `ErrorObserver` is registered, every fork automatically:
    *  - reports errors via `onError` (surfaces in global banner)
    *  - clears stale errors via `onSuccess` (auto-dismiss on recovery)
    */
  private def forkProvided[E <: Throwable, A](zio: ZIO[BackendClient, E, A]): Unit =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.fork(
        zio
          .tapError(e => ZIO.succeed(errorObserver.foreach(_.onError(e))))
          .tap(_ => ZIO.succeed(errorObserver.foreach(_.onSuccess())))
          .provide(BackendClientLive.configuredLayer)
      )
    }

  // ---------------------------------------------------------------------------
  // Extensions on ZIO[BackendClient, E, A]
  // ---------------------------------------------------------------------------

  extension [E <: Throwable, A](zio: ZIO[BackendClient, E, A])

    /** Fork the ZIO and emit its successful result into an EventBus.
      *
      * Errors are logged to the browser console via `Console.printLineError`.
      */
    def emitTo(bus: EventBus[A]): Unit =
      forkProvided {
        zio
          .tapError(e => Console.printLineError(e.getMessage()))
          .tap(a => ZIO.attempt(bus.emit(a)))
      }

    /** Fork the ZIO for its side-effects only (fire-and-forget). */
    def runJs: Unit =
      forkProvided(zio)

    /** Fork the ZIO and return its result as a one-shot Laminar EventStream. */
    def toEventStream: EventStream[A] =
      val eventBus = EventBus[A]()
      emitTo(eventBus)
      eventBus.events

  // ---------------------------------------------------------------------------
  // Extensions on ZIO + LoadState — DRY the tap/tapError/runJs lifecycle
  // ---------------------------------------------------------------------------

  import app.state.LoadState

  extension [E <: Throwable, A](zio: ZIO[BackendClient, E, A])

    /** Fork the ZIO and push its lifecycle into a `Var[LoadState[A]]`.
      *
      * Sets Loading → Loaded(a) on success, Loading → Failed(msg) on error.
      */
    def loadInto(target: Var[LoadState[A]]): Unit =
      target.set(LoadState.Loading)
      forkProvided {
        zio
          .tap(a => ZIO.succeed(target.set(LoadState.Loaded(a))))
          .tapError(e => ZIO.succeed(target.set(LoadState.Failed(e.getMessage()))))
      }

  /** Extension for ZIO effects returning `Option[B]` — unwraps into `LoadState[B]`. */
  extension [E <: Throwable, B](zio: ZIO[BackendClient, E, Option[B]])

    /** Fork the ZIO and push `Some(b) → Loaded(b)`, `None → Failed(noneMessage)`.
      *
      * Useful for endpoints that return `Option` (e.g. getById) where the
      * target `Var` holds the unwrapped type.
      */
    def loadOptionInto(target: Var[LoadState[B]], noneMessage: String): Unit =
      target.set(LoadState.Loading)
      forkProvided {
        zio
          .tap {
            case Some(b) => ZIO.succeed(target.set(LoadState.Loaded(b)))
            case None    => ZIO.succeed(target.set(LoadState.Failed(noneMessage)))
          }
          .tapError(e => ZIO.succeed(target.set(LoadState.Failed(e.getMessage()))))
      }

  // ---------------------------------------------------------------------------
  // Extensions on ZIO + SubmitState — DRY the submit lifecycle
  // ---------------------------------------------------------------------------

  import app.state.SubmitState

  extension [E <: Throwable, A](zio: ZIO[BackendClient, E, A])

    /** Fork the ZIO and drive a `Var[SubmitState]` through its lifecycle.
      *
      * Sets Submitting → Success (via onSuccess callback) on success,
      * Submitting → Failed(msg) on error. Mirrors `loadInto` but for
      * the submit lifecycle where success requires caller-defined side-effects
      * (e.g. updating editing state, refreshing tree list).
      */
    def submitInto(target: Var[SubmitState])(onSuccess: A => Unit): Unit =
      target.set(SubmitState.Submitting)
      forkProvided {
        zio
          .tap(a => ZIO.succeed(onSuccess(a)))
          .tapError(e => ZIO.succeed(target.set(SubmitState.Failed(e.getMessage()))))
      }

  // ---------------------------------------------------------------------------
  // Extension on unsecured Tapir endpoints
  // ---------------------------------------------------------------------------

  /** Turn an endpoint description + payload into `RIO[BackendClient, O]`.
    *
    * Usage:
    * {{{
    *   import app.core.ZJS.*
    *
    *   healthEndpoint(())           // RIO[BackendClient, Map[String, String]]
    *     .emitTo(healthBus)
    *
    *   createEndpoint(requestDto)   // RIO[BackendClient, SimulationResponse]
    *     .emitTo(responseBus)
    * }}}
    */
  extension [I, E <: Throwable, O](endpoint: Endpoint[Unit, I, E, O, Any])
    def apply(payload: I): RIO[BackendClient, O] =
      ZIO.service[BackendClient].flatMap(_.endpointRequestZIO(endpoint)(payload))
