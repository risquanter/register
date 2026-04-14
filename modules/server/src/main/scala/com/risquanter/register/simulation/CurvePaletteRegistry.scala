package com.risquanter.register.simulation

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

import com.risquanter.register.domain.data.CurvePalette
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.iron.HexColorStr

/** Maps each `CurvePalette` to 13 hex shades, ordered darkest → lightest.
  *
  * Sourced from `app.css` curve palette custom properties.
  * Index 0 = highest-risk (darkest), index 12 = lowest-risk (lightest).
  *
  * `refineUnsafe` is safe: literals are compile-time constants
  * (same pattern as `IronConstants`).
  */
object CurvePaletteRegistry:

  /** Refine a `#RRGGBB` literal to `HexColor`. Safe for compile-time constants. */
  private def hex(s: String): HexColor =
    HexColor(s.refineUnsafe[Match["^#[0-9a-fA-F]{6}$"]])

  val shades: Map[CurvePalette, Vector[HexColor]] = Map(
    CurvePalette.Green -> Vector(
      "#03170b", "#052914", "#0f3e21", "#145c2f", "#15803d",
      "#16a34a", "#22c55e", "#4ade80", "#86efac", "#bbf7d0",
      "#d5fbe2", "#e4fbec", "#f1fdf5"
    ).map(hex),
    CurvePalette.Aqua -> Vector(
      "#00121a", "#002533", "#003a52", "#005370", "#007299",
      "#0094bf", "#00b3e6", "#42c9ed", "#7bdaf3", "#aee9f8",
      "#d4f5fc", "#e0f9ff", "#f0fcff"
    ).map(hex),
    CurvePalette.Purple -> Vector(
      "#11011e", "#23023b", "#3e0f61", "#5a1094", "#7b0acd",
      "#9810fa", "#ad46ff", "#c27aff", "#dab2ff", "#e5ccff",
      "#ecdbff", "#f2e6ff", "#faf5ff"
    ).map(hex)
  )
