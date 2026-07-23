package app.chart

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.LECNodeCurve

/** Pure tests for `CompareColorAssigner.pairForOverlay`. */
object CompareColorAssignerSpec extends ZIOSpecDefault:

  private val leaf1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val leaf2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  private def curve(id: NodeId): LECNodeCurve =
    LECNodeCurve(id, name = s"Leaf ${id.value}", curve = Vector.empty, quantiles = Map.empty, averageAnnualLoss = 0.0, probabilityOfNoLoss = 1.0)

  private def side(
    curves: Map[NodeId, LECNodeCurve],
    visible: Set[NodeId],
    palette: Vector[com.risquanter.register.domain.data.iron.HexColor.HexColor],
    label: String
  ) = CompareColorAssigner.OverlaySide(curves, visible, palette, label)

  def spec = suite("CompareColorAssigner.pairForOverlay")(

    test("a node selected on both branches yields two entries with distinct series ids") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "this"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "scenario-a")
      ))
      val seriesIds = paired.map(_._3)
      assertTrue(
        paired.length == 2,
        seriesIds.toSet == Set(s"${leaf1.value}@this", s"${leaf1.value}@scenario-a")
      )
    },

    test("each side's shade comes from that side's own palette family") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "this"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "scenario-a")
      ))
      val thisColor    = paired.find(_._3 == s"${leaf1.value}@this").get._2
      val compareColor = paired.find(_._3 == s"${leaf1.value}@scenario-a").get._2
      assertTrue(
        PaletteData.Aqua.contains(thisColor),
        PaletteData.Purple.contains(compareColor)
      )
    },

    test("three sides yield one entry per side with three distinct series ids and palettes") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "this"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "scenario-a"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Orange, "scenario-b")
      ))
      assertTrue(
        paired.length == 3,
        paired.map(_._3).toSet == Set(s"${leaf1.value}@this", s"${leaf1.value}@scenario-a", s"${leaf1.value}@scenario-b"),
        PaletteData.Orange.contains(paired.find(_._3 == s"${leaf1.value}@scenario-b").get._2)
      )
    },

    test("a node missing from a side's curves contributes only the sides it exists on") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "this"),
        side(Map.empty, Set(leaf1), PaletteData.Purple, "scenario-a")
      ))
      assertTrue(
        paired.length == 1,
        paired.head._3 == s"${leaf1.value}@this"
      )
    },

    test("each side contributes only its own selection (independent visible sets)") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf1), PaletteData.Aqua, "this"),
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf2), PaletteData.Purple, "scenario-a")
      ))
      assertTrue(
        paired.map(_._3).toSet == Set(s"${leaf1.value}@this", s"${leaf2.value}@scenario-a")
      )
    },

    test("a node not in a side's visible set is excluded even if present in both curve maps") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf1), PaletteData.Aqua, "this"),
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf1), PaletteData.Purple, "scenario-a")
      ))
      assertTrue(paired.forall(_._1.id == leaf1))
    },

    test("the same node always gets the same shade across repeated calls (deterministic)") {
      def run() = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "this")
      ))
      assertTrue(run().head._2 == run().head._2)
    }
  )
