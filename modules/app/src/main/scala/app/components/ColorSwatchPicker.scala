package app.components

import com.raquo.laminar.api.L.{*, given}

import app.chart.PaletteData
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Colour swatch picker for manual curve colour overrides (P5 §5.1).
  *
  * Renders an 8×8 grid (8 palettes × 8 shades) with a "↺ Auto" reset
  * button. Supports live hover-preview via `onPreview` / `onPreviewClear`,
  * debounced at 50ms (PD2(b)) to avoid rapid signal updates during fast
  * mouse movement across swatches.
  *
  * Pure view component — owns no state (ADR-019 Pattern 4).
  */
object ColorSwatchPicker:

  /** @param currentColor  The node's currently assigned colour.
    * @param onSelect       Called when a swatch is clicked (commit override).
    * @param onReset        Called when "↺ Auto" is clicked (clear override).
    * @param onPreview      Called on swatch hover, debounced 50ms (live preview).
    * @param onPreviewClear Called on swatch mouse-leave (revert preview).
    */
  def apply(
    currentColor: HexColor,
    onSelect: HexColor => Unit,
    onReset: () => Unit,
    onPreview: HexColor => Unit = _ => (),
    onPreviewClear: () => Unit = () => ()
  ): HtmlElement =
    // Debounced hover preview — prevents rapid signal churn during fast mouse movement (PD2(b))
    val previewBus = new EventBus[HexColor]

    div(
      cls := "color-swatch-picker",
      onClick.stopPropagation --> (_ => ()), // prevent click-outside from closing immediately
      previewBus.events.debounce(50) --> { hex => onPreview(hex) },
      div(
        cls := "swatch-grid",
        PaletteData.allFamilies.flatMap { palette =>
          palette.map { color =>
            val isActive = color == currentColor
            div(
              cls := (if isActive then "swatch-cell swatch-cell--active" else "swatch-cell"),
              styleAttr := s"background-color: ${color.value};",
              title := color.value,
              onClick --> { _ => onSelect(color) },
              onMouseEnter --> { _ => previewBus.emit(color) },
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
