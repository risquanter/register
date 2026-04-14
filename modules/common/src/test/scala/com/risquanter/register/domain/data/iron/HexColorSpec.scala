package com.risquanter.register.domain.data.iron

import zio.test.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

object HexColorSpec extends ZIOSpecDefault:

  import HexColor.HexColor

  def spec = suite("HexColor")(
    test("accepts valid lowercase hex colour") {
      val s: HexColorStr = "#aabb00".refineUnsafe
      val hc = HexColor(s)
      assertTrue(hc.value == s)
    },
    test("accepts valid uppercase hex colour") {
      val s: HexColorStr = "#AABBCC".refineUnsafe
      val hc = HexColor(s)
      assertTrue(hc.value.toString == "#AABBCC")
    },
    test("accepts mixed-case hex colour") {
      val s: HexColorStr = "#aAbBcC".refineUnsafe
      val hc = HexColor(s)
      assertTrue(hc.value.toString == "#aAbBcC")
    },
    test("rejects string without hash prefix") {
      val result: Either[String, HexColorStr] = "aabbcc".refineEither
      assertTrue(result.isLeft)
    },
    test("rejects string with wrong length (too short)") {
      val result: Either[String, HexColorStr] = "#aabb".refineEither
      assertTrue(result.isLeft)
    },
    test("rejects string with wrong length (too long)") {
      val result: Either[String, HexColorStr] = "#aabbccdd".refineEither
      assertTrue(result.isLeft)
    },
    test("rejects string with non-hex characters") {
      val result: Either[String, HexColorStr] = "#gghhii".refineEither
      assertTrue(result.isLeft)
    },
    test("rejects empty string") {
      val result: Either[String, HexColorStr] = "".refineEither
      assertTrue(result.isLeft)
    }
  )
