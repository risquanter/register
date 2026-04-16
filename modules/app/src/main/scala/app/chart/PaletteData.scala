package app.chart

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.iron.HexColorStr

/** Eight non-neutral palette families for LEC chart curve colouring.
  *
  * Each family provides 8 hex shades in light-to-dark order (200 → 900),
  * trimmed from the full Tailwind-derived 13-shade ramp (§2.7).
  *
  * Hash-based rotation selects the shade for each node within a family.
  * The three category families used by `ColorAssigner`:
  *   - Green  — query-matched nodes
  *   - Aqua   — user-selected nodes
  *   - Purple — nodes in both sets (overlap)
  *
  * All 8 families are available for the colour picker grid.
  */
object PaletteData:

  /** Refine a `#RRGGBB` literal to `HexColor`. Safe for compile-time constants. */
  private def hex(s: String): HexColor =
    HexColor(s.refineUnsafe[Match["^#[0-9a-fA-F]{6}$"]])

  val Green: Vector[HexColor] = Vector(
    "#bbf7d0", "#86efac", "#4ade80", "#22c55e",
    "#16a34a", "#15803d", "#145c2f", "#0f3e21"
  ).map(hex)

  val Aqua: Vector[HexColor] = Vector(
    "#aee9f8", "#7bdaf3", "#42c9ed", "#00b3e6",
    "#0094bf", "#007299", "#005370", "#003a52"
  ).map(hex)

  val Purple: Vector[HexColor] = Vector(
    "#e5ccff", "#dab2ff", "#c27aff", "#ad46ff",
    "#9810fa", "#7b0acd", "#5a1094", "#3e0f61"
  ).map(hex)

  val Yellow: Vector[HexColor] = Vector(
    "#fef08a", "#fde047", "#facc15", "#eab308",
    "#ca8a04", "#a16207", "#73430c", "#542f0d"
  ).map(hex)

  val Orange: Vector[HexColor] = Vector(
    "#fde68a", "#fcd34d", "#fbbf24", "#f59e0b",
    "#d97706", "#b45309", "#7e370c", "#56260b"
  ).map(hex)

  val Red: Vector[HexColor] = Vector(
    "#fecaca", "#fca5a5", "#f87171", "#ef4444",
    "#da2828", "#a81a1a", "#6e1111", "#4f0c0c"
  ).map(hex)

  val Pink: Vector[HexColor] = Vector(
    "#fbcfe8", "#f9a8d4", "#f472b6", "#ec4899",
    "#db2777", "#be185d", "#8e1546", "#5b112e"
  ).map(hex)

  val Emerald: Vector[HexColor] = Vector(
    "#a7f3d0", "#6ee7b7", "#34d399", "#10b981",
    "#0e8b63", "#0b5b46", "#093f34", "#072e27"
  ).map(hex)

  /** All 8 families in picker grid order (light-to-dark per row). */
  val allFamilies: Vector[Vector[HexColor]] = Vector(
    Green, Aqua, Purple, Yellow, Orange, Red, Pink, Emerald
  )
