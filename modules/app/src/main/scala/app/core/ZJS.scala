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
  */
object ZJS:

  /** Fork a ZIO on the default runtime with BackendClient provided.
    *
    * This is the single integration point between the ZIO and Laminar worlds.
    * All public extension methods delegate here to avoid duplicating the
    * Unsafe / Runtime / provide boilerplate.
    */
  private def forkProvided[E <: Throwable, A](zio: ZIO[BackendClient, E, A]): Unit =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.fork(
        zio.provide(BackendClientLive.configuredLayer)
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
