package com.risquanter.register.http.requests

import zio.test.*
import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId, ValidationMessages}
import com.risquanter.register.domain.errors.ValidationErrorCode

object SeedVarIdGuardSpec extends ZIOSpecDefault:

  private def name(s: String): SafeName.SafeName =
    SafeName.fromString(s).toOption.get
  private def sid(v: Long): SeedVarId.SeedVarId =
    SeedVarId.fromLong(v).toOption.get

  def spec = suite("requireUniqueSeedVarIds")(
    test("empty input passes") {
      assertTrue(RiskTreeRequests.requireUniqueSeedVarIds(Seq.empty).toEither.isRight)
    },
    test("all-distinct ids pass") {
      val provided = Seq(
        name("Cyber Attack") -> sid(1L),
        name("Flood Risk")   -> sid(2L),
        name("Data Breach")  -> sid(3L)
      )
      assertTrue(RiskTreeRequests.requireUniqueSeedVarIds(provided).toEither.isRight)
    },
    test("one duplicate id yields a single DUPLICATE_VALUE error naming both leaves") {
      val provided = Seq(
        name("Cyber Attack") -> sid(5L),
        name("Flood Risk")   -> sid(5L),
        name("Data Breach")  -> sid(3L)
      )
      val errs = RiskTreeRequests.requireUniqueSeedVarIds(provided).toEither.swap.toOption.get
      assertTrue(
        errs.size == 1,
        errs.head.code == ValidationErrorCode.DUPLICATE_VALUE,
        errs.head.field == "request.seedVarIds",
        errs.head.message == ValidationMessages.seedVarIdInUse(5L, Seq("Cyber Attack", "Flood Risk"))
      )
    },
    test("two independent duplicate groups accumulate two errors") {
      val provided = Seq(
        name("A Risk") -> sid(5L),
        name("B Risk") -> sid(5L),
        name("C Risk") -> sid(9L),
        name("D Risk") -> sid(9L)
      )
      val errs = RiskTreeRequests.requireUniqueSeedVarIds(provided).toEither.swap.toOption.get
      assertTrue(
        errs.size == 2,
        errs.forall(_.code == ValidationErrorCode.DUPLICATE_VALUE),
        errs.map(_.message).toSet == Set(
          ValidationMessages.seedVarIdInUse(5L, Seq("A Risk", "B Risk")),
          ValidationMessages.seedVarIdInUse(9L, Seq("C Risk", "D Risk"))
        )
      )
    },
    test("three holders of one id are all named, sorted") {
      val provided = Seq(
        name("Zeta")  -> sid(7L),
        name("Alpha") -> sid(7L),
        name("Mid")   -> sid(7L)
      )
      val errs = RiskTreeRequests.requireUniqueSeedVarIds(provided).toEither.swap.toOption.get
      assertTrue(
        errs.size == 1,
        errs.head.message == ValidationMessages.seedVarIdInUse(7L, Seq("Alpha", "Mid", "Zeta"))
      )
    }
  )
