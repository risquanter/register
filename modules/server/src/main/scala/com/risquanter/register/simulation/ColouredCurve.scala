package com.risquanter.register.simulation

import com.risquanter.register.domain.data.{CurvePalette, LECNodeCurve}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.iron.NodeId

/** Curve paired with its resolved hex colour — server-local, never serialized.
  *
  * Keeps the rendering concern (colour) separate from the domain DTO
  * (`LECNodeCurve`), so the cross-compiled wire type stays clean and the
  * `lec-multi` endpoint is unaffected. Follows ADR-001's boundary
  * separation: domain data carries domain fields; presentation metadata
  * lives in presentation-scoped types.
  *
  * @param curve    Domain curve data (id, name, points, quantiles).
  * @param hexColor Resolved hex colour (Iron-refined `HexColor`, not raw String).
  */
final case class ColouredCurve(curve: LECNodeCurve, hexColor: HexColor)

object ColouredCurve:

  /** Assign palette colours to curves, sorted by p95 descending within each palette group.
    *
    * Groups curves by their palette (from `paletteMap`, defaulting to `Green`),
    * sorts each group by p95 quantile descending (highest-risk → darkest shade),
    * and pairs each curve with its resolved `HexColor` from `CurvePaletteRegistry`.
    *
    * If a group has more members than available shades (13), excess curves
    * receive the lightest shade (index 12).
    *
    * @param curves     Map of node ID to domain curve data.
    * @param paletteMap Map of node ID to requested palette. Missing entries default to Green.
    * @return Flat vector of `ColouredCurve`, palette-grouped and p95-sorted.
    */
  def assignPaletteColours(
    curves: Map[NodeId, LECNodeCurve],
    paletteMap: Map[NodeId, CurvePalette]
  ): Vector[ColouredCurve] =
    // Group curves by palette
    val grouped: Map[CurvePalette, Vector[(NodeId, LECNodeCurve)]] =
      curves.toVector
        .map { case (nid, curve) => (paletteMap.getOrElse(nid, CurvePalette.Green), nid, curve) }
        .groupBy(_._1)
        .view.mapValues(_.map(t => (t._2, t._3))).toMap

    // Within each group: sort by p95 descending, assign shade by rank
    grouped.toVector.flatMap { case (palette, members) =>
      val shades = CurvePaletteRegistry.shades(palette)
      members
        .sortBy { case (_, c) => -c.quantiles.getOrElse("p95", 0.0) }
        .zipWithIndex
        .map { case ((_, curve), rank) =>
          ColouredCurve(curve, shades(rank min (shades.size - 1)))
        }
    }
