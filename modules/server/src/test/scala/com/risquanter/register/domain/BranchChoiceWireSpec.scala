package com.risquanter.register.domain

import zio.*
import zio.test.*
import zio.json.*
import com.risquanter.register.domain.data.iron.{BranchChoice, ScenarioName}

/** Wire behaviour of `BranchChoice` (E7 explicit branch value) and the
  * `"main"` scenario-name reservation. */
object BranchChoiceWireSpec extends ZIOSpecDefault:

  def spec = suite("BranchChoice wire + \"main\" reservation")(

    test("ScenarioName.fromString rejects \"main\" (reserved for the main branch)") {
      assertTrue(ScenarioName.fromString("main").isLeft)
    },

    test("the reservation is case-insensitive (the slug lowercases)") {
      assertTrue(
        ScenarioName.fromString("MAIN").isLeft,
        ScenarioName.fromString("Main").isLeft
      )
    },

    test("an ordinary scenario name is accepted") {
      assertTrue(ScenarioName.fromString("cyber-risk").isRight)
    },

    test("BranchChoice JSON: \"main\" decodes to Main and encodes back") {
      assertTrue(
        "\"main\"".fromJson[BranchChoice] == Right(BranchChoice.Main),
        BranchChoice.Main.toJson == "\"main\""
      )
    },

    test("BranchChoice JSON: a scenario slug round-trips as Scenario") {
      val decoded = "\"cyber-risk\"".fromJson[BranchChoice]
      assertTrue(
        decoded.exists { case BranchChoice.Scenario(n) => n.value == "cyber-risk"; case _ => false },
        decoded.toOption.map(_.toJson).contains("\"cyber-risk\"")
      )
    },

    test("BranchChoice JSON: an invalid branch value (dots) is rejected") {
      assertTrue("\"scenarios.ws.x\"".fromJson[BranchChoice].isLeft)
    }
  )
