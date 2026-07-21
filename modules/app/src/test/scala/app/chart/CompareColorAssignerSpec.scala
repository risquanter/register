package app.chart

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.LECNodeCurve

/** Pure tests for `CompareColorAssigner.pairForOverlay` (milestone-2b
  * Phase C, Analyze Overlay compare mode).
  */
object CompareColorAssignerSpec extends ZIOSpecDefault:

  private val leaf1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val leaf2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  private def curve(id: NodeId): LECNodeCurve = LECNodeCurve(id, name = s"Leaf ${id.value}", curve = Vector.empty, quantiles = Map.empty)

  def spec = suite("CompareColorAssigner.pairForOverlay")(

    test("a node present on both branches yields two entries with distinct series ids") {
      val paired = CompareColorAssigner.pairForOverlay(
        thisBranchCurves    = Map(leaf1 -> curve(leaf1)),
        compareBranchCurves = Map(leaf1 -> curve(leaf1)),
        visible             = Set(leaf1),
        thisBranchLabel     = "this",
        compareBranchLabel  = "scenario-a"
      )
      val seriesIds = paired.map(_._3)
      assertTrue(
        paired.length == 2,
        seriesIds.toSet == Set(s"${leaf1.value}@this", s"${leaf1.value}@scenario-a")
      )
    },

    test("this branch's shade always comes from the Aqua palette, compare branch's from Purple") {
      val paired = CompareColorAssigner.pairForOverlay(
        thisBranchCurves    = Map(leaf1 -> curve(leaf1)),
        compareBranchCurves = Map(leaf1 -> curve(leaf1)),
        visible             = Set(leaf1),
        thisBranchLabel     = "this",
        compareBranchLabel  = "scenario-a"
      )
      val thisColor    = paired.find(_._3 == s"${leaf1.value}@this").get._2
      val compareColor = paired.find(_._3 == s"${leaf1.value}@scenario-a").get._2
      assertTrue(
        PaletteData.Aqua.contains(thisColor),
        PaletteData.Purple.contains(compareColor)
      )
    },

    test("a node missing from the compare branch's curves contributes only the this-branch entry") {
      val paired = CompareColorAssigner.pairForOverlay(
        thisBranchCurves    = Map(leaf1 -> curve(leaf1)),
        compareBranchCurves = Map.empty,
        visible             = Set(leaf1),
        thisBranchLabel     = "this",
        compareBranchLabel  = "scenario-a"
      )
      assertTrue(
        paired.length == 1,
        paired.head._3 == s"${leaf1.value}@this"
      )
    },

    test("a node not in the visible set is excluded even if present in both curve maps") {
      val paired = CompareColorAssigner.pairForOverlay(
        thisBranchCurves    = Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)),
        compareBranchCurves = Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)),
        visible             = Set(leaf1),
        thisBranchLabel     = "this",
        compareBranchLabel  = "scenario-a"
      )
      assertTrue(paired.forall(_._1.id == leaf1))
    },

    test("the same node always gets the same shade across repeated calls (deterministic)") {
      def run() = CompareColorAssigner.pairForOverlay(
        thisBranchCurves    = Map(leaf1 -> curve(leaf1)),
        compareBranchCurves = Map.empty,
        visible             = Set(leaf1),
        thisBranchLabel     = "this",
        compareBranchLabel  = "scenario-a"
      )
      assertTrue(run().head._2 == run().head._2)
    }
  )
