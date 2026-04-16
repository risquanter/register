package app.components

import com.raquo.laminar.api.L.{*, given}

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.iron.HexColorStr

/** Colour swatch picker for manual curve colour overrides (P5 §5.1).
  *
  * Renders an 8×8 grid (8 palettes × 8 shades) with a "↺ Auto" reset
  * button. Supports live hover-preview via `onPreview` / `onPreviewClear`.
  *
  * Pure view component — owns no state (ADR-019 Pattern 4).
  */
object ColorSwatchPicker:

  /** Refine a `#RRGGBB` literal to `HexColor`. Safe for compile-time constants. */
  private def hex(s: String): HexColor =
    HexColor(s.refineUnsafe[Match["^#[0-9a-fA-F]{6}$"]])

  /** 8 palette families × 8 trimmed shades for the picker grid.
    * Trimming: drop 25, 50, 100 (too light) and 950, 975 (too dark).
    * Retain: 200, 300, 400, 500, 600, 700, 800, 900 (§2.7).
    */
  val pickerPalettes: Vector[Vector[HexColor]] = Vector(
    // Green
    Vector("#bbf7d0", "#86efac", "#4ade80", "#22c55e", "#16a34a", "#15803d", "#145c2f", "#0f3e21").map(hex),
    // Aqua
    Vector("#aee9f8", "#7bdaf3", "#42c9ed", "#00b3e6", "#0094bf", "#007299", "#005370", "#003a52").map(hex),
    // Purple
    Vector("#e5ccff", "#dab2ff", "#c27aff", "#ad46ff", "#9810fa", "#7b0acd", "#5a1094", "#3e0f61").map(hex),
    // Yellow
    Vector("#fef08a", "#fde047", "#facc15", "#eab308", "#ca8a04", "#a16207", "#73430c", "#542f0d").map(hex),
    // Orange
    Vector("#fde68a", "#fcd34d", "#fbbf24", "#f59e0b", "#d97706", "#b45309", "#7e370c", "#56260b").map(hex),
    // Red
    Vector("#fecaca", "#fca5a5", "#f87171", "#ef4444", "#da2828", "#a81a1a", "#6e1111", "#4f0c0c").map(hex),
    // Pink
    Vector("#fbcfe8", "#f9a8d4", "#f472b6", "#ec4899", "#db2777", "#be185d", "#8e1546", "#5b112e").map(hex),
    // Emerald
    Vector("#a7f3d0", "#6ee7b7", "#34d399", "#10b981", "#0e8b63", "#0b5b46", "#093f34", "#072e27").map(hex)
  )

  /** @param currentColor  The node's currently assigned colour.
    * @param onSelect       Called when a swatch is clicked (commit override).
    * @param onReset        Called when "↺ Auto" is clicked (clear override).
    * @param onPreview      Called on swatch hover with debounce (live preview).
    * @param onPreviewClear Called on swatch mouse-leave (revert preview).
    */
  def apply(
    currentColor: HexColor,
    onSelect: HexColor => Unit,
    onReset: () => Unit,
    onPreview: HexColor => Unit = _ => (),
    onPreviewClear: () => Unit = () => ()
  ): HtmlElement =
    div(
      cls := "color-swatch-picker",
      onClick.stopPropagation --> (_ => ()), // prevent click-outside from closing immediately
      div(
        cls := "swatch-grid",
        pickerPalettes.flatMap { palette =>
          palette.map { color =>
            val isActive = color == currentColor
            div(
              cls := (if isActive then "swatch-cell swatch-cell--active" else "swatch-cell"),
              styleAttr := s"background-color: ${color.value};",
              title := color.value,
              onClick --> { _ => onSelect(color) },
              onMouseEnter --> { _ => onPreview(color) },
              onMouseLeave --> { _ => onPreviewClear() }
            )
          }
        }
      ),
      button(
        cls := "swatch-reset-btn",
        "↺ Auto",
        onClick --> { _ => onReset() }
      )
    )
