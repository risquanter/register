package com.risquanter.register.domain

import zio.*
import zio.test.*
import zio.json.*
import com.risquanter.register.domain.data.iron.{BranchChoice, BranchRef, ScenarioName, WorkspaceId}
import com.risquanter.register.testutil.TestHelpers.safeId

/** Wire behaviour of `BranchChoice` (E7 explicit branch value), the `"main"`
  * scenario-name reservation, and the decomposition of an Irmin branch
  * reference back into its client-facing form. */
object BranchChoiceWireSpec extends ZIOSpecDefault:

  private val wsId: WorkspaceId      = WorkspaceId(safeId("branch-choice-ws"))
  private val otherWsId: WorkspaceId = WorkspaceId(safeId("branch-choice-other"))

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
    },

    test("fromBranchRef: the main branch yields Main") {
      assertTrue(BranchChoice.fromBranchRef(wsId, BranchRef.Main) == Right(BranchChoice.Main))
    },

    test("fromBranchRef inverts BranchRef.scenario for every name the refinement admits") {
      // Composing and decomposing rather than restating the prefix literal:
      // the two must agree character for character, and a divergence would be
      // silent if the test spelled the prefix itself.
      val names = List("a", "cyber-risk", "q3-2026", "with_underscore", "x" * 64)
      val checks = names.map { raw =>
        val name     = ScenarioName.fromString(raw).toOption.get
        val composed = BranchRef.scenario(wsId, name).toOption.get
        BranchChoice.fromBranchRef(wsId, composed) == Right(BranchChoice.Scenario(name))
      }
      assertTrue(checks.size == names.size, checks.forall(identity))
    },

    // BranchRefConstraint admits only "main" or "scenarios.<segment>.<segment>",
    // so these three cases exhaust the input space: main, this workspace's own
    // scenario, and another workspace's. There is no fourth shape to cover.
    test("fromBranchRef: a branch composed for another workspace fails rather than yielding a wrong Scenario") {
      val name     = ScenarioName.fromString("cyber-risk").toOption.get
      val foreign  = BranchRef.scenario(otherWsId, name).toOption.get
      val decoded  = BranchChoice.fromBranchRef(wsId, foreign)
      assertTrue(
        decoded.isLeft,
        // specifically not the right name with the wrong workspace
        decoded != Right(BranchChoice.Scenario(name))
      )
    }
  )
