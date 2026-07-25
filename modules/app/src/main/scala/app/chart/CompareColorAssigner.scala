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

  /** One branch's contribution to the Overlay chart: its curve cache, its
    * card's own visible selection, its palette family, and its display
    * label. Each branch carries its own visible set — each branch card is an
    * independent Ctrl+click surface, so the sides' selections need not
    * agree.
    */
  final case class OverlaySide(
    curves:    Map[NodeId, LECNodeCurve],
    visible:   Set[NodeId],
    palette:   Vector[HexColor],
    slotLabel: String
  )

  /** For each side's visible nodes present in that side's curve map, emit
    * one entry carrying the side's palette shade and a series id distinct
    * from the same node's entry on any other side. The suffix is the side's
    * stable SLOT label ("active"/"s1"/"s2"), not its branch name — so two
    * sides on the same branch but different trees (cross-tree comparison)
    * still get distinct series (`NodeId` alone can't disambiguate several
    * sides' curves for the same node — see `LECSpecBuilder.buildFromSeries`).
    *
    * A node selected on a side but missing from that side's curve map
    * (fetch not landed yet, or the node doesn't exist there) contributes
    * nothing for that side.
    */
  def pairForOverlay(sides: Vector[OverlaySide]): Vector[(LECNodeCurve, HexColor, String)] =
    sides.flatMap { s =>
      s.visible.toVector.sortBy(_.value).flatMap { nid =>
        s.curves.get(nid).map(curve => (curve, ColorAssigner.shade(s.palette, nid), s"${nid.value}@${s.slotLabel}"))
      }
    }
