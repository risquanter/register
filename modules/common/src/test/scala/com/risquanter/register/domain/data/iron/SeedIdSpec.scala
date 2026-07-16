package com.risquanter.register.domain.data.iron

import zio.test.*
import zio.json.{JsonEncoder, JsonDecoder, EncoderOps, DecoderOps}
import com.risquanter.register.domain.errors.ValidationErrorCode

object SeedIdSpec extends ZIOSpecDefault:

  def spec = suite("Seed identity types")(
    suite("SeedEntityId range boundaries")(
      test("rejects 0 (reserved shared default)") {
        assertTrue(SeedEntityId.fromLong(0L).isLeft)
      },
      test("rejects negative values") {
        assertTrue(SeedEntityId.fromLong(-1L).isLeft)
      },
      test("accepts lower bound 1") {
        assertTrue(SeedEntityId.fromLong(1L).toOption.get.value == 1L)
      },
      test("accepts upper bound 99999999") {
        assertTrue(SeedEntityId.fromLong(99999999L).toOption.get.value == 99999999L)
      },
      test("rejects 100000000 (8-digit HDR budget)") {
        assertTrue(SeedEntityId.fromLong(100000000L).isLeft)
      }
    ),
    suite("SeedVarId range boundaries")(
      test("rejects 0") {
        assertTrue(SeedVarId.fromLong(0L).isLeft)
      },
      test("rejects negative values") {
        assertTrue(SeedVarId.fromLong(-1L).isLeft)
      },
      test("accepts lower bound 1") {
        assertTrue(SeedVarId.fromLong(1L).toOption.get.value == 1L)
      },
      test("accepts upper bound 49999999") {
        assertTrue(SeedVarId.fromLong(49999999L).toOption.get.value == 49999999L)
      },
      test("rejects 50000000 (stream doubling: 2k+1 must stay below 10^8)") {
        assertTrue(SeedVarId.fromLong(50000000L).isLeft)
      }
    ),
    suite("refinement errors")(
      test("refineSeedEntityId reports INVALID_RANGE with the given field path") {
        val errs = ValidationUtil.refineSeedEntityId(0L, "workspace.seedEntityId").swap.toOption.get
        assertTrue(
          errs.head.code == ValidationErrorCode.INVALID_RANGE,
          errs.head.field == "workspace.seedEntityId",
          errs.head.message == ValidationMessages.seedEntityIdOutOfRange
        )
      },
      test("refineSeedVarId reports INVALID_RANGE with the given field path") {
        val errs = ValidationUtil.refineSeedVarId(50000000L, "leaf.seedVarId").swap.toOption.get
        assertTrue(
          errs.head.code == ValidationErrorCode.INVALID_RANGE,
          errs.head.field == "leaf.seedVarId",
          errs.head.message == ValidationMessages.seedVarIdOutOfRange
        )
      }
    ),
    suite("JSON codecs")(
      test("SeedEntityId round-trips as a JSON number") {
        val id = SeedEntityId.fromLong(42L).toOption.get
        val json = id.toJson
        assertTrue(json == "42", json.fromJson[SeedEntityId.SeedEntityId] == Right(id))
      },
      test("SeedVarId round-trips as a JSON number") {
        val id = SeedVarId.fromLong(7L).toOption.get
        val json = id.toJson
        assertTrue(json == "7", json.fromJson[SeedVarId.SeedVarId] == Right(id))
      },
      test("SeedEntityId decode failure surfaces the range message") {
        val result = "100000000".fromJson[SeedEntityId.SeedEntityId]
        assertTrue(result.swap.toOption.get.contains(ValidationMessages.seedEntityIdOutOfRange))
      },
      test("SeedVarId decode failure surfaces the range message") {
        val result = "0".fromJson[SeedVarId.SeedVarId]
        assertTrue(result.swap.toOption.get.contains(ValidationMessages.seedVarIdOutOfRange))
      }
    )
  )
