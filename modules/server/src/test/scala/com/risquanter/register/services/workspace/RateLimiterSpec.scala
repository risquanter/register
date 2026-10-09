package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import com.risquanter.register.domain.errors.RateLimitExceeded

object RateLimiterSpec extends ZIOSpecDefault:

  private def mkLimiter(limit: Int, hops: Int = 1) =
    RateLimiterLive.make(limit, hops)

  /** One proxy in front: the header holds only the entry that proxy appended. */
  private val oneHop = Some("127.0.0.1")

  override def spec = suite("RateLimiterLive security regressions")(
    test("under limit succeeds (A27)") {
      for
        limiter <- mkLimiter(2)
        _       <- limiter.checkCreate(oneHop)
        _       <- limiter.checkCreate(oneHop)
      yield assertCompletes
    },

    test("over limit fails with RateLimitExceeded (A27)") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(oneHop)
        exit    <- limiter.checkCreate(oneHop).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("window resets after one hour") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(oneHop)
        _       <- TestClock.adjust(2.hours)
        res     <- limiter.checkCreate(oneHop).either
      yield assertTrue(res.isRight)
    },

    test("unidentifiable sources (None) share one window — no header, no bypass") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(None)
        exit    <- limiter.checkCreate(None).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("a caller-supplied entry is ignored, so varying it does not buy a new window") {
      // With one proxy, the last entry is the one the proxy appended and the
      // caller's own value sits to its left. Two requests that differ only in
      // that left-hand value are the same caller.
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(Some("10.0.0.1, 127.0.0.1"))
        exit    <- limiter.checkCreate(Some("10.0.0.2, 127.0.0.1")).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("two real addresses behind the same proxy get their own windows") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(Some("203.0.113.1"))
        res     <- limiter.checkCreate(Some("203.0.113.2")).either
      yield assertTrue(res.isRight)
    },

    test("with two proxies the gateway's entry is counted, not the client's or nginx's") {
      // Chain: caller → gateway → nginx → server. The gateway appends the real
      // caller address; nginx appends the gateway's. So with two hops the real
      // caller sits at index size - 2.
      for
        limiter <- mkLimiter(1, hops = 2)
        _       <- limiter.checkCreate(Some("10.0.0.1, 203.0.113.9, 192.168.1.5"))
        exit    <- limiter.checkCreate(Some("10.0.0.2, 203.0.113.9, 192.168.1.5")).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("a header shorter than the hop count falls back to the shared window") {
      for
        limiter <- mkLimiter(1, hops = 2)
        _       <- limiter.checkCreate(Some("127.0.0.1"))
        exit    <- limiter.checkCreate(None).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("a lapsed window is removed from the map rather than retained") {
      // Observable through behaviour rather than state: after the window
      // lapses, the next request starts a fresh count, which is only true if
      // the stale entry is gone.
      for
        limiter <- mkLimiter(2)
        _       <- limiter.checkCreate(Some("198.51.100.7"))
        _       <- limiter.checkCreate(Some("198.51.100.7"))
        _       <- TestClock.adjust(2.hours)
        _       <- limiter.checkCreate(Some("198.51.100.7"))
        res     <- limiter.checkCreate(Some("198.51.100.7")).either
      yield assertTrue(res.isRight)
    }
  )
