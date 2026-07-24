package app.state

import zio.test.*

import app.chart.PaletteData
import com.risquanter.register.domain.data.iron.{BranchChoice, ScenarioName}

/** Pure tests for `BranchPaletteState`'s assignment resolution and storage
  * keys (the class itself owns only browser-storage plumbing around these).
  */
object BranchPaletteStateSpec extends ZIOSpecDefault:

  private val scenarioA = ScenarioName.fromString("scenario-a").toOption.get
  private val mainNamed = ScenarioName.fromString("main").toOption.get

  def spec = suite("BranchPaletteState")(

    test("main and a scenario literally named 'main' get distinct storage keys") {
      assertTrue(
        BranchPaletteState.storageKeyOf(BranchChoice.Main)
          != BranchPaletteState.storageKeyOf(BranchChoice.Scenario(mainNamed))
      )
    },

    test("an assignment naming a known family resolves to that family") {
      val assignments = Map(BranchPaletteState.storageKeyOf(BranchChoice.Scenario(scenarioA)) -> "red")
      assertTrue(
        BranchPaletteState.resolve(assignments, BranchChoice.Scenario(scenarioA), PaletteData.Purple)
          == PaletteData.Red
      )
    },

    test("no assignment falls back to the given default") {
      assertTrue(
        BranchPaletteState.resolve(Map.empty, BranchChoice.Main, PaletteData.Aqua) == PaletteData.Aqua,
        BranchPaletteState.resolve(Map.empty, BranchChoice.Scenario(scenarioA), PaletteData.Orange)
          == PaletteData.Orange
      )
    },

    test("an assignment naming an unknown family falls back to the default") {
      val assignments = Map(BranchPaletteState.storageKeyOf(BranchChoice.Main) -> "no-such-family")
      assertTrue(
        BranchPaletteState.resolve(assignments, BranchChoice.Main, PaletteData.Aqua) == PaletteData.Aqua
      )
    },

    test("one branch's assignment does not leak onto another branch") {
      val assignments = Map(BranchPaletteState.storageKeyOf(BranchChoice.Main) -> "red")
      assertTrue(
        BranchPaletteState.resolve(assignments, BranchChoice.Scenario(scenarioA), PaletteData.Purple)
          == PaletteData.Purple
      )
    },

    test("every named family resolves through familyByName") {
      assertTrue(
        PaletteData.namedFamilies.forall { (name, family) =>
          PaletteData.familyByName(name).contains(family)
        },
        PaletteData.familyByName("no-such-family").isEmpty
      )
    }
  )
