package app.chart

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.LECNodeCurve

/** Pure tests for `CompareColorAssigner.pairForOverlay`. Series ids are
  * suffixed with the stable SLOT label ("active"/"s1"/"s2"), not the branch
  * name, so two sides on the same branch but different trees still get
  * distinct series. */
object CompareColorAssignerSpec extends ZIOSpecDefault:

  private val leaf1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val leaf2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  private def curve(id: NodeId): LECNodeCurve =
    LECNodeCurve(id, name = s"Leaf ${id.value}", curve = Vector.empty, quantiles = Map.empty, averageAnnualLoss = 0.0, probabilityOfNoLoss = 1.0)

  private def side(
    curves: Map[NodeId, LECNodeCurve],
    visible: Set[NodeId],
    palette: Vector[com.risquanter.register.domain.data.iron.HexColor.HexColor],
    slotLabel: String
  ) = CompareColorAssigner.OverlaySide(curves, visible, palette, slotLabel)

  def spec = suite("CompareColorAssigner.pairForOverlay")(

    test("a node selected on both sides yields two entries with slot-label series ids") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "s1")
      ))
      val seriesIds = paired.map(_._3)
      assertTrue(
        paired.length == 2,
        seriesIds.toSet == Set(s"${leaf1.value}@active", s"${leaf1.value}@s1")
      )
    },

    test("two sides on the SAME node but different slots still get distinct series (same-branch cross-tree)") {
      // Same node id, same palette family (as a same-branch cross-tree pair
      // would resolve before de-collision) — the slot label is what keeps the
      // two series apart, not the branch or the colour.
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "s1"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "s2")
      ))
      assertTrue(paired.map(_._3).toSet == Set(s"${leaf1.value}@s1", s"${leaf1.value}@s2"))
    },

    test("cross-tree sides with disjoint nodes contribute the plain union, no pairing") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map(leaf2 -> curve(leaf2)), Set(leaf2), PaletteData.Purple, "s1")
      ))
      assertTrue(
        paired.length == 2,
        paired.map(_._3).toSet == Set(s"${leaf1.value}@active", s"${leaf2.value}@s1")
      )
    },

    test("each side's shade comes from that side's own palette family") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "s1")
      ))
      val thisColor    = paired.find(_._3 == s"${leaf1.value}@active").get._2
      val compareColor = paired.find(_._3 == s"${leaf1.value}@s1").get._2
      assertTrue(
        PaletteData.Aqua.contains(thisColor),
        PaletteData.Purple.contains(compareColor)
      )
    },

    test("three sides yield one entry per side with three distinct series ids and palettes") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Purple, "s1"),
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Orange, "s2")
      ))
      assertTrue(
        paired.length == 3,
        paired.map(_._3).toSet == Set(s"${leaf1.value}@active", s"${leaf1.value}@s1", s"${leaf1.value}@s2"),
        PaletteData.Orange.contains(paired.find(_._3 == s"${leaf1.value}@s2").get._2)
      )
    },

    test("a node missing from a side's curves contributes only the sides it exists on") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map.empty, Set(leaf1), PaletteData.Purple, "s1")
      ))
      assertTrue(
        paired.length == 1,
        paired.head._3 == s"${leaf1.value}@active"
      )
    },

    test("each side contributes only its own selection (independent visible sets)") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf2), PaletteData.Purple, "s1")
      ))
      assertTrue(
        paired.map(_._3).toSet == Set(s"${leaf1.value}@active", s"${leaf2.value}@s1")
      )
    },

    test("a node not in a side's visible set is excluded even if present in both curve maps") {
      val paired = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf1), PaletteData.Aqua, "active"),
        side(Map(leaf1 -> curve(leaf1), leaf2 -> curve(leaf2)), Set(leaf1), PaletteData.Purple, "s1")
      ))
      assertTrue(paired.forall(_._1.id == leaf1))
    },

    test("the same node always gets the same shade across repeated calls (deterministic)") {
      def run() = CompareColorAssigner.pairForOverlay(Vector(
        side(Map(leaf1 -> curve(leaf1)), Set(leaf1), PaletteData.Aqua, "active")
      ))
      assertTrue(run().head._2 == run().head._2)
    }
  )
