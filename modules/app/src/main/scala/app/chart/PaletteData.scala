package app.chart

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.iron.HexColorStr

/** Three palette families for LEC chart curve colouring.
  *
  * Each family provides 8 hex shades (mid-range, high-contrast subset trimmed
  * from the full Tailwind-derived 13-shade ramp). Index 0 is the primary shade;
  * hash-based rotation selects the shade for each node.
  *
  * Families:
  *   - Green  — query-matched nodes
  *   - Aqua   — user-selected nodes
  *   - Purple — nodes in both sets (overlap)
  */
object PaletteData:

  /** Refine a `#RRGGBB` literal to `HexColor`. Safe for compile-time constants. */
  private def hex(s: String): HexColor =
    HexColor(s.refineUnsafe[Match["^#[0-9a-fA-F]{6}$"]])

  val Green: Vector[HexColor] = Vector(
    "#15803d", "#16a34a", "#22c55e", "#4ade80",
    "#0f3e21", "#145c2f", "#86efac", "#bbf7d0"
  ).map(hex)

  val Aqua: Vector[HexColor] = Vector(
    "#007299", "#0094bf", "#00b3e6", "#42c9ed",
    "#003a52", "#005370", "#7bdaf3", "#aee9f8"
  ).map(hex)

  val Purple: Vector[HexColor] = Vector(
    "#7b0acd", "#9810fa", "#ad46ff", "#c27aff",
    "#3e0f61", "#5a1094", "#dab2ff", "#e5ccff"
  ).map(hex)
