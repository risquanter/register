package com.risquanter.register.domain.data

import zio.test.*
import zio.json.*

object CurvePaletteSpec extends ZIOSpecDefault:

  def spec = suite("CurvePalette")(
    suite("JSON encode → decode round-trip")(
      test("Green round-trips") {
        val json = CurvePalette.Green.toJson
        val decoded = json.fromJson[CurvePalette]
        assertTrue(json == "\"green\"", decoded == Right(CurvePalette.Green))
      },
      test("Aqua round-trips") {
        val json = CurvePalette.Aqua.toJson
        val decoded = json.fromJson[CurvePalette]
        assertTrue(json == "\"aqua\"", decoded == Right(CurvePalette.Aqua))
      },
      test("Purple round-trips") {
        val json = CurvePalette.Purple.toJson
        val decoded = json.fromJson[CurvePalette]
        assertTrue(json == "\"purple\"", decoded == Right(CurvePalette.Purple))
      }
    ),
    suite("case-insensitive decode")(
      test("decodes uppercase GREEN") {
        assertTrue("\"GREEN\"".fromJson[CurvePalette] == Right(CurvePalette.Green))
      },
      test("decodes mixed-case Aqua") {
        assertTrue("\"Aqua\"".fromJson[CurvePalette] == Right(CurvePalette.Aqua))
      },
      test("decodes PURPLE") {
        assertTrue("\"PURPLE\"".fromJson[CurvePalette] == Right(CurvePalette.Purple))
      }
    ),
    suite("unknown string")(
      test("rejects unknown palette with descriptive error") {
        val result = "\"orange\"".fromJson[CurvePalette]
        assertTrue(
          result.isLeft,
          result.left.exists(_.contains("Unknown curve palette"))
        )
      },
      test("rejects empty string") {
        val result = "\"\"".fromJson[CurvePalette]
        assertTrue(result.isLeft)
      }
    )
  )
