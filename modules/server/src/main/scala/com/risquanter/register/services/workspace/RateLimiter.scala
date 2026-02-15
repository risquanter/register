package com.risquanter.register.services.workspace

import zio.*
import java.time.Instant
import com.risquanter.register.domain.errors.RateLimitExceeded
import com.risquanter.register.configs.WorkspaceConfig

/** IP-based rate limiter for workspace bootstrap endpoint (A27).
  *
  * Fixed-window per IP: max creates per hour.
  */
trait RateLimiter:
  def checkCreate(ip: String): IO[RateLimitExceeded, Unit]

final class RateLimiterLive private (
  ref: Ref[Map[String, (Int, Instant)]],
  maxPerHour: Int
) extends RateLimiter:

  override def checkCreate(ip: String): IO[RateLimitExceeded, Unit] =
    for
      now <- Clock.instant
      result <- ref.modify { state =>
        val oneHourAgo = now.minusSeconds(3600)
        val (count, windowStart) = state.get(ip) match
          case Some((c, start)) if start.isAfter(oneHourAgo) => (c, start)
          case _                                              => (0, now)

        if count >= maxPerHour then
          // Reject: do NOT increment counter on rejection (no slot consumed)
          (Left(RateLimitExceeded(ip, maxPerHour)), state)
        else
          // Accept: increment and persist
          (Right(()), state.updated(ip, (count + 1, windowStart)))
      }
      // Nested logAnnotate: this is the only occurrence in RateLimiter.
      // WorkspaceStoreLive uses a foldRight-based `logSecurity` helper, but
      // extracting a shared utility across the module boundary has low ROI
      // for a single call site.
      // TODO: revisit if more structured logging sites appear in this file.
      out <- ZIO.fromEither(result).tapError { err =>
               ZIO.logAnnotate("event_type", "rate_limit.exceeded") {
                 ZIO.logAnnotate("ip", ip) {
                   ZIO.logAnnotate("limit", maxPerHour.toString) {
                     ZIO.logWarning("Rate limit exceeded")
                   }
                 }
               }
             }
    yield out

object RateLimiterLive:
  val layer: ZLayer[WorkspaceConfig, Nothing, RateLimiter] =
    ZLayer.fromZIO {
      for
        cfg <- ZIO.service[WorkspaceConfig]
        ref <- Ref.make(Map.empty[String, (Int, Instant)])
      yield RateLimiterLive(ref, cfg.maxCreatesPerIpPerHour)
    }

  def make(maxPerHour: Int): UIO[RateLimiter] =
    Ref.make(Map.empty[String, (Int, Instant)]).map(ref => RateLimiterLive(ref, maxPerHour))
