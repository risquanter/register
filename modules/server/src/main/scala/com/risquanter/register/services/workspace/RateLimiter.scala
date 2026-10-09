package com.risquanter.register.services.workspace

import zio.*
import java.time.Instant
import com.risquanter.register.domain.errors.RateLimitExceeded
import com.risquanter.register.configs.WorkspaceConfig

/** Caller address used for rate-limiting. Nominal (ADR-018), deliberately NOT
  * format-refined: the value varies (IPv4, IPv6) and is only ever used as a map
  * key and a log annotation — never parsed, never interpolated into a query.
  *
  * The value is written by a proxy this deployment operates, never by the
  * caller: `RateLimiterLive.callerAddress` reads only the entry our own
  * outermost proxy appended to `X-Forwarded-For`.
  */
final case class ClientIp(value: String)

/** Rate limiter for the workspace bootstrap endpoint.
  *
  * Fixed window per caller address: at most `maxCreatesPerIpPerHour` workspace
  * creations per hour. Requests whose address cannot be determined share ONE
  * window, so an absent or too-short header cannot buy a fresh allowance.
  */
trait RateLimiter:
  /** Counts one workspace creation against the caller's address.
    *
    * Takes the raw `X-Forwarded-For` header rather than an address: which part
    * of it identifies the caller depends on how many proxies sit in front of
    * this process, which is configuration this service holds and the caller
    * does not.
    */
  def checkCreate(forwardedFor: Option[String]): IO[RateLimitExceeded, Unit]

final class RateLimiterLive private (
  ref: Ref[Map[Option[ClientIp], (Int, Instant)]],
  maxPerHour: Int,
  trustedProxyHops: Int
) extends RateLimiter:

  /** The caller's address as our own proxies recorded it.
    *
    * Each proxy in front of this process appends the address of its immediate
    * downstream peer to `X-Forwarded-For`. With `trustedProxyHops` of them the
    * outermost one's entry sits at index `size - trustedProxyHops`, and
    * everything to the left of it was written by the caller and is ignored. A
    * header shorter than that is an absent header or a wrong hop count, and
    * yields `None` — those requests share one window.
    */
  private def callerAddress(forwardedFor: Option[String]): Option[ClientIp] =
    forwardedFor
      .map(_.split(",").toList.map(_.trim).filter(_.nonEmpty))
      .flatMap(entries => entries.lift(entries.size - trustedProxyHops))
      .map(ClientIp.apply)

  override def checkCreate(forwardedFor: Option[String]): IO[RateLimitExceeded, Unit] =
    val ip      = callerAddress(forwardedFor)
    val ipLabel = ip.fold("unknown")(_.value)
    for
      now <- Clock.instant
      result <- ref.modify { state =>
        val oneHourAgo = now.minusSeconds(3600)
        // Drop windows that have lapsed, so the map holds only live ones. Linear
        // in the number of live windows, which is bounded because an entry can
        // only come from an address one of our own proxies observed.
        val live = state.filter((_, entry) => entry._2.isAfter(oneHourAgo))
        val (count, windowStart) = live.get(ip) match
          case Some((c, start)) => (c, start)
          case None             => (0, now)

        if count >= maxPerHour then
          // Reject: do NOT increment counter on rejection (no slot consumed)
          (Left(RateLimitExceeded(ipLabel, maxPerHour)), live)
        else
          // Accept: increment and persist
          (Right(()), live.updated(ip, (count + 1, windowStart)))
      }
      // Nested logAnnotate: this is the only occurrence in RateLimiter.
      // WorkspaceStoreLive uses a foldRight-based `logSecurity` helper, but
      // extracting a shared utility across the module boundary has low ROI
      // for a single call site.
      out <- ZIO.fromEither(result).tapError { _ =>
               ZIO.logAnnotate("event_type", "rate_limit.exceeded") {
                 ZIO.logAnnotate("ip", ipLabel) {
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
        ref <- Ref.make(Map.empty[Option[ClientIp], (Int, Instant)])
      yield RateLimiterLive(ref, cfg.maxCreatesPerIpPerHour, cfg.trustedProxyHops)
    }

  /** Create a limiter with explicit settings (for tests). */
  def make(maxPerHour: Int, trustedProxyHops: Int = 1): UIO[RateLimiter] =
    Ref.make(Map.empty[Option[ClientIp], (Int, Instant)])
      .map(ref => RateLimiterLive(ref, maxPerHour, trustedProxyHops))
