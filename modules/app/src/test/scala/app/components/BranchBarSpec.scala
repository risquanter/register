package app.components

import zio.test.*

import app.components.BranchBar.CreateSource
import com.risquanter.register.domain.data.iron.{BranchChoice, ScenarioName}

/** Covers the "Create from main" / "Create from current" menu items
  * (renamed 2026-07-21 from "New scenario from main" / "Duplicate current").
  * The two are meant to collapse to the identical operation while on main —
  * this is a pure-function test (no Laminar/DOM harness needed, see
  * `docs/dev/TODO.md` item 25) proving that equivalence rather than relying
  * on it being "obvious" from the source.
  */
object BranchBarSpec extends ZIOSpecDefault:

  private val scenarioName = ScenarioName.fromString("stress-2026").toOption.get

  def spec = suite("BranchBar.forkTarget")(
    test("on main, Create-from-current forks from the same place as Create-from-main") {
      val current = BranchChoice.Main
      assertTrue(BranchBar.forkTarget(CreateSource.Current, current) == BranchBar.forkTarget(CreateSource.Main, current))
    },
    test("Create-from-main always forks from main regardless of current branch") {
      assertTrue(
        BranchBar.forkTarget(CreateSource.Main, BranchChoice.Main) == None,
        BranchBar.forkTarget(CreateSource.Main, BranchChoice.Scenario(scenarioName)) == None
      )
    },
    test("on a scenario branch, Create-from-current forks from that branch, not main") {
      val current = BranchChoice.Scenario(scenarioName)
      assertTrue(BranchBar.forkTarget(CreateSource.Current, current) == Some(scenarioName))
    }
  )
