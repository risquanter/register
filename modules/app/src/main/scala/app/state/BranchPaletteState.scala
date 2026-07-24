package app.state

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import zio.json.*

import app.chart.PaletteData
import com.risquanter.register.domain.data.iron.BranchChoice
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** User-assigned palette families per branch — the branch's colour identity
  * in the Compare overlay, the side-by-side panel and card headers, the
  * topbar branch chip, and the selected-node highlights of any chart bound
  * to that branch.
  *
  * Assignments are client-side only: nothing durable references a scenario
  * server-side, so the map lives in `localStorage`, keyed by branch name
  * across workspaces (a workspace is identified client-side only by its
  * secret key, which must not be written to storage). A branch with no
  * assignment falls back to the default its display slot carries (Aqua for
  * the tab's active branch, the slot families for compared branches), so
  * nothing changes visually until the user assigns.
  *
  * One instance app-wide (`Main`): both sections' surfaces read the same
  * assignment for the same branch.
  */
final class BranchPaletteState:

  /** Storage key (see [[BranchPaletteState.storageKeyOf]]) → family name
    * (see `PaletteData.namedFamilies`). */
  val assignments: Var[Map[String, String]] = Var(BranchPaletteState.loadFromStorage())

  assignments.signal.changes.foreach(BranchPaletteState.saveToStorage)(using unsafeWindowOwner)

  /** The family to colour `choice` with: its assignment when one names a
    * known family, `default` otherwise. */
  def paletteFor(choice: Signal[BranchChoice], default: Vector[HexColor]): Signal[Vector[HexColor]] =
    assignments.signal.combineWith(choice).map { (map, c) =>
      BranchPaletteState.resolve(map, c, default)
    }

  /** The assigned family name for `choice`, if any — drives the picker's
    * active-cell highlight (no assignment = the "Auto" state, no highlight). */
  def assignedNameFor(choice: Signal[BranchChoice]): Signal[Option[String]] =
    assignments.signal.combineWith(choice).map { (map, c) =>
      map.get(BranchPaletteState.storageKeyOf(c))
    }

  def assign(choice: BranchChoice, familyName: String): Unit =
    assignments.update(_ + (BranchPaletteState.storageKeyOf(choice) -> familyName))

  def clearAssignment(choice: BranchChoice): Unit =
    assignments.update(_ - BranchPaletteState.storageKeyOf(choice))

object BranchPaletteState:

  private val storageKey = "register.branch-palettes"

  /** Storage key for a branch. `":main"` cannot collide with a scenario
    * named "main": `ScenarioName`'s charset (`[a-z0-9][a-z0-9_-]*`) never
    * contains a colon. */
  def storageKeyOf(choice: BranchChoice): String = choice match
    case BranchChoice.Main           => ":main"
    case BranchChoice.Scenario(name) => name.value.toString

  /** Pure assignment lookup: an assignment naming an unknown family (e.g. a
    * family renamed after the assignment was stored) falls back to `default`
    * the same as no assignment at all. */
  def resolve(
    assignments: Map[String, String],
    choice: BranchChoice,
    default: Vector[HexColor]
  ): Vector[HexColor] =
    assignments.get(storageKeyOf(choice)).flatMap(PaletteData.familyByName).getOrElse(default)

  private def loadFromStorage(): Map[String, String] =
    try
      Option(dom.window.localStorage.getItem(storageKey))
        .flatMap(_.fromJson[Map[String, String]].toOption)
        .getOrElse(Map.empty)
    catch case _: Throwable => Map.empty

  private def saveToStorage(map: Map[String, String]): Unit =
    try dom.window.localStorage.setItem(storageKey, map.toJson)
    catch case _: Throwable => ()
