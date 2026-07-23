package app.chart

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.LECNodeCurve

/** Pairs curve data for the Analyze Overlay comparison mode — colour is
  * branch identity (a fixed palette family per branch), with per-node
  * distinction as a shade within that family, mirroring `ColorAssigner`'s
  * hash-rotation-by-node mechanism but keyed by branch instead of
  * query/user/overlap classification.
  */
object CompareColorAssigner:

  /** For each of a branch's own visible nodes present in that branch's curve
    * map, emit one entry carrying the branch's palette shade and a series id
    * distinct from the same node's entry on the other branch (`NodeId` alone
    * can't disambiguate two branches' curves for the same node — see
    * `LECSpecBuilder.buildFromSeries`).
    *
    * Each branch carries its own visible set — each branch card is an
    * independent Ctrl+click surface, so the two sides' selections need not
    * agree. A node selected on a branch but missing from that branch's
    * curve map (fetch not landed yet, or the node doesn't exist there)
    * contributes nothing for that side.
    */
  def pairForOverlay(
    thisBranchCurves:    Map[NodeId, LECNodeCurve],
    thisVisible:         Set[NodeId],
    compareBranchCurves: Map[NodeId, LECNodeCurve],
    compareVisible:      Set[NodeId],
    thisBranchLabel:     String,
    compareBranchLabel:  String
  ): Vector[(LECNodeCurve, HexColor, String)] =
    def side(
      curves:  Map[NodeId, LECNodeCurve],
      visible: Set[NodeId],
      palette: Vector[HexColor],
      label:   String
    ): Vector[(LECNodeCurve, HexColor, String)] =
      visible.toVector.sortBy(_.value).flatMap { nid =>
        curves.get(nid).map(curve => (curve, ColorAssigner.shade(palette, nid), s"${nid.value}@$label"))
      }
    side(thisBranchCurves, thisVisible, PaletteData.Aqua, thisBranchLabel) ++
      side(compareBranchCurves, compareVisible, PaletteData.Purple, compareBranchLabel)
