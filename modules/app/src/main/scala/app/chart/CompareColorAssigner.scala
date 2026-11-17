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

  /** One branch's contribution to the Overlay chart. Each branch carries its
    * own visible set — each branch card is an independent Ctrl+click
    * surface, so the sides' selections need not agree.
    *
    * @param slotLabel Stable series-id suffix ("active"/"s1"/"s2"…) — the
    *   side's chart identity, unique even when two sides pin the same
    *   branch + tree to different commits.
    * @param displayLabel Human legend text for the side ("branch · tree";
    *   the baseline side appends " (active)").
    * @param overrides The side's explicit per-node colour picks
    *   (`LECChartState.explicitColors`) — they win over the family shade,
    *   exactly as `nodeColorMap` makes them win on the single-tree chart
    *   and the side-by-side panels.
    */
  final case class OverlaySide(
    curves:       Map[NodeId, List[(CurveVariant, LECNodeCurve)]],
    visible:      Set[NodeId],
    palette:      Vector[HexColor],
    slotLabel:    String,
    displayLabel: String,
    overrides:    Map[NodeId, HexColor]
  )

  /** For each side's visible nodes present in that side's curve map, emit
    * one series per reading, carrying the side's colour (explicit pick if
    * present, else the palette shade), a series id distinct from the same
    * node's entry on any other side, and the legend label. The id is the
    * node's reading id (`LECSpecBuilder.seriesIdOf`) followed by the side's
    * stable SLOT label, not its branch name — so two sides on the same
    * branch but different trees (cross-tree comparison) still get distinct
    * series (`NodeId` alone can't disambiguate several sides' curves for
    * the same node — see `LECSpecBuilder.buildFromSeries`). The legend
    * shows the node name over the side's `displayLabel` (two lines).
    *
    * A node selected on a side but missing from that side's curve map
    * (fetch not landed yet, or the node doesn't exist there) contributes
    * nothing for that side.
    */
  def pairForOverlay(sides: Vector[OverlaySide]): Vector[ChartSeries] =
    sides.flatMap { s =>
      s.visible.toVector.sortBy(_.value).flatMap { nid =>
        s.curves.getOrElse(nid, Nil).map { (variant, curve) =>
          ChartSeries(
            curve,
            variant,
            s.overrides.getOrElse(nid, ColorAssigner.shade(s.palette, nid)),
            s"${LECSpecBuilder.seriesIdOf(nid, variant)}@${s.slotLabel}",
            LECSpecBuilder.labelOf(curve, variant),
            Some(s.displayLabel)
          )
        }
      }
    }
