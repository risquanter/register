package app.chart

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.LECNodeCurve

/** Pairs curve data for the Analyze Overlay comparison mode (milestone-2b
  * Phase C) — colour is branch identity (a fixed palette family per branch),
  * with per-node distinction as a shade within that family, mirroring
  * `ColorAssigner`'s existing hash-rotation-by-node mechanism but keyed by
  * branch instead of query/user/overlap classification.
  */
object CompareColorAssigner:

  private def shade(palette: Vector[HexColor], nodeId: NodeId): HexColor =
    palette((nodeId.value.hashCode & 0x7fffffff) % palette.size)

  /** For each visible node present in a branch's curve map, emit one entry
    * carrying that branch's palette shade and a series id distinct from the
    * same node's entry on the other branch (`NodeId` alone can't disambiguate
    * two branches' curves for the same node — see `LECSpecBuilder.buildFromSeries`).
    * A node missing from one branch's map (an Added/Removed node per the
    * diff) contributes only the side it exists on.
    */
  def pairForOverlay(
    thisBranchCurves:    Map[NodeId, LECNodeCurve],
    compareBranchCurves: Map[NodeId, LECNodeCurve],
    visible:             Set[NodeId],
    thisBranchLabel:     String,
    compareBranchLabel:  String
  ): Vector[(LECNodeCurve, HexColor, String)] =
    val thisSide = visible.toVector.sortBy(_.value).flatMap { nid =>
      thisBranchCurves.get(nid).map(curve => (curve, shade(PaletteData.Aqua, nid), s"${nid.value}@$thisBranchLabel"))
    }
    val compareSide = visible.toVector.sortBy(_.value).flatMap { nid =>
      compareBranchCurves.get(nid).map(curve => (curve, shade(PaletteData.Purple, nid), s"${nid.value}@$compareBranchLabel"))
    }
    thisSide ++ compareSide
