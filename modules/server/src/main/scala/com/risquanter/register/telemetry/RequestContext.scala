package com.risquanter.register.telemetry

import zio.*
import java.util.UUID

/** Request context propagated through ZIO fiber
  * 
  * Contains correlation IDs for distributed tracing and logging.
  * Propagated via FiberRef to maintain context across async boundaries.
  * 
  * @param requestId Unique identifier for this request (UUID)
  * @param traceId Optional OpenTelemetry trace ID
  * @param spanId Optional OpenTelemetry span ID
  * @param userId Optional user identifier from authentication headers
  */
final case class RequestContext(
  requestId: String,
  traceId: Option[String] = None,
  spanId: Option[String] = None,
  userId: Option[String] = None
)

/** Type alias for the FiberRef service */
type RequestContextRef = FiberRef[Option[RequestContext]]

object RequestContext {
  
  /** Layer providing FiberRef for request context propagation
    * 
    * This is the referentially transparent way to create the FiberRef.
    * Use this layer in your application's layer composition.
    * 
    * Usage:
    * {{{
    * myEffect.provide(RequestContext.layer)
    * }}}
    */
  val layer: ULayer[RequestContextRef] =
    ZLayer.scoped(FiberRef.make[Option[RequestContext]](None))
  
  /** Generate new request context with random UUID
    * 
    * @return New RequestContext with generated requestId
    */
  def generate: UIO[RequestContext] = 
    ZIO.succeed(RequestContext(requestId = UUID.randomUUID().toString))
  
  /** Get current request context from FiberRef
    * 
    * Requires RequestContextRef in the environment.
    * 
    * @return Current context if set, None otherwise
    */
  def get: ZIO[RequestContextRef, Nothing, Option[RequestContext]] = 
    ZIO.serviceWithZIO[RequestContextRef](_.get)
  
  /** Set request context in FiberRef
    * 
    * Requires RequestContextRef in the environment.
    * 
    * @param ctx Context to set
    */
  def set(ctx: RequestContext): ZIO[RequestContextRef, Nothing, Unit] = 
    ZIO.serviceWithZIO[RequestContextRef](_.set(Some(ctx)))
  
  /** Run effect with request context
    * 
    * Sets context for the duration of the effect, automatically cleaning up.
    * Context is inherited by all child fibers.
    * 
    * Requires RequestContextRef in the environment.
    * 
    * @param ctx Context to use
    * @param effect Effect to run with context
    * @return Result of effect
    */
  def withContext[R, E, A](ctx: RequestContext)(effect: ZIO[R, E, A]): ZIO[R & RequestContextRef, E, A] =
    ZIO.serviceWithZIO[RequestContextRef] { ref =>
      ZIO.scoped {
        ref.locallyScoped(Some(ctx)) *> effect
      }
    }
  
  /** Run effect with generated request context
    * 
    * Convenience method that generates a new context and runs the effect with it.
    * 
    * Requires RequestContextRef in the environment.
    * 
    * @param effect Effect to run
    * @return Result of effect
    */
  def withGenerated[R, E, A](effect: ZIO[R, E, A]): ZIO[R & RequestContextRef, E, A] =
    generate.flatMap(ctx => withContext(ctx)(effect))
}
