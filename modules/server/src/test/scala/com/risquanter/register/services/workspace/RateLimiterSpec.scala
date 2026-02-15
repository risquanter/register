package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import scala.concurrent.duration.*
import com.risquanter.register.domain.errors.RateLimitExceeded

object RateLimiterSpec extends ZIOSpecDefault:

  private def mkLimiter(limit: Int) = RateLimiterLive.make(limit)

  override def spec = suite("RateLimiterLive security regressions")(
    test("under limit succeeds (A27)") {
      for
        limiter <- mkLimiter(2)
        _       <- limiter.checkCreate("127.0.0.1")
        _       <- limiter.checkCreate("127.0.0.1")
      yield assertCompletes
    },

    test("over limit fails with RateLimitExceeded (A27)") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate("127.0.0.1")
        exit    <- limiter.checkCreate("127.0.0.1").exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("window resets after one hour") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate("127.0.0.1")
        _       <- TestClock.adjust(2.hours)
        res     <- limiter.checkCreate("127.0.0.1").either
      yield assertTrue(res.isRight)
    }
  )
