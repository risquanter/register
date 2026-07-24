package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.TestClock
import scala.concurrent.duration.*
import com.risquanter.register.domain.errors.RateLimitExceeded

object RateLimiterSpec extends ZIOSpecDefault:

  private def mkLimiter(limit: Int) = RateLimiterLive.make(limit)

  private val localhost = Some(ClientIp("127.0.0.1"))

  override def spec = suite("RateLimiterLive security regressions")(
    test("under limit succeeds (A27)") {
      for
        limiter <- mkLimiter(2)
        _       <- limiter.checkCreate(localhost)
        _       <- limiter.checkCreate(localhost)
      yield assertCompletes
    },

    test("over limit fails with RateLimitExceeded (A27)") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(localhost)
        exit    <- limiter.checkCreate(localhost).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    },

    test("window resets after one hour") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(localhost)
        _       <- TestClock.adjust(2.hours)
        res     <- limiter.checkCreate(localhost).either
      yield assertTrue(res.isRight)
    },

    test("unidentifiable sources (None) share one window — no header, no bypass") {
      for
        limiter <- mkLimiter(1)
        _       <- limiter.checkCreate(None)
        exit    <- limiter.checkCreate(None).exit
      yield assert(exit)(fails(isSubtype[RateLimitExceeded](anything)))
    }
  )
