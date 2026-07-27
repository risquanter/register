package app.components

import com.raquo.laminar.api.L.{*, given}
import com.raquo.airstream.web.DomEventStream
import org.scalajs.dom

import app.chart.PaletteData
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** A slot card's header swatch, made clickable: clicking it opens a one-row
  * popover of the palette families to set this slot's colour, plus "↺ Auto"
  * to reset to the slot's default family. Colour identity is per slot, not
  * per branch — two slots may deliberately hold the same family.
  *
  * The popover is viewport-positioned (`position: fixed`, coordinates read off
  * the swatch when clicked): the swatch sits inside a slot card whose
  * `overflow: hidden` (rounded-corner clipping) would cut off an
  * absolutely-positioned child — on a collapsed card, entirely. The left edge
  * is clamped so the popover stays on screen near the viewport's right edge.
  * Any scroll also closes the popover — a fixed position doesn't follow the
  * card when content scrolls under it, so dismissing is the consistent
  * reaction. The scroll listener is capture-phase: scroll events don't bubble,
  * so a bubble-phase document listener would never see inner containers
  * scrolling.
  *
  * Owns only the local popover-open flag; the palette itself lives in the
  * caller's `Var` (ADR-019 Pattern 2 — this child emits via callbacks). Clicks
  * stop propagating — the swatch sits inside the card header whose own click
  * toggles collapse.
  */
object SlotPalettePicker:

  /** Popover width used for the right-edge clamp: 8 cells + gaps + padding,
    * kept a little generous rather than measured (the element doesn't exist
    * yet at the moment the position is computed). */
  private val PopoverWidthPx = 240.0

  /** @param current  The family this slot currently renders with — the
    *                 swatch's own colour and the active-cell highlight.
    * @param onSelect Emitted with the chosen family when a cell is clicked.
    * @param onReset  Emitted when "↺ Auto" is clicked (caller restores its
    *                 slot default). */
  def apply(
    current: Signal[Vector[HexColor]],
    onSelect: Vector[HexColor] => Unit,
    onReset: () => Unit
  ): HtmlElement =
    val open: Var[Boolean] = Var(false)
    // The family name currently in effect, for the active-cell highlight —
    // None when the current palette matches no named family.
    val activeName: Signal[Option[String]] =
      current.map(cur => PaletteData.namedFamilies.collectFirst { case (n, f) if f == cur => n })
    // Viewport coordinates for the fixed-position popover, captured from the
    // swatch's bounding box on the click that opens it.
    val anchor: Var[(Double, Double)] = Var((0.0, 0.0))
    span(
      cls := "slot-palette-picker",
      documentEvents(_.onClick) --> { _ => if open.now() then open.set(false) },
      DomEventStream[dom.Event](dom.document, "scroll", useCapture = true) --> { _ =>
        if open.now() then open.set(false)
      },
      span(
        cls := "color-swatch slot-palette-trigger",
        title := "Slot colour…",
        styleAttr <-- current.map(f => s"background-color: ${PaletteData.familySwatch(f).value};"),
        inContext { trigger =>
          onClick.stopPropagation --> { _ =>
            val r = trigger.ref.getBoundingClientRect()
            val left = math.max(0.0, math.min(r.left, dom.window.innerWidth - PopoverWidthPx))
            anchor.set((left, r.bottom + 4))
            open.update(!_)
          }
        }
      ),
      child.maybe <-- open.signal.map {
        case false => None
        case true  =>
          Some(div(
            cls := "slot-palette-popover",
            styleAttr <-- anchor.signal.map { (left, top) => s"left: ${left}px; top: ${top}px;" },
            onClick.stopPropagation --> { _ => () },
            div(
              cls := "slot-palette-cells",
              PaletteData.namedFamilies.map { (name, family) =>
                div(
                  cls := "swatch-cell",
                  cls("swatch-cell--active") <-- activeName.map(_.contains(name)),
                  styleAttr := s"background-color: ${PaletteData.familySwatch(family).value};",
                  title := name,
                  onClick.stopPropagation --> { _ =>
                    onSelect(family)
                    open.set(false)
                  }
                )
              }
            ),
            button(
              cls := "swatch-reset-btn",
              "↺ Auto",
              onClick.stopPropagation --> { _ =>
                onReset()
                open.set(false)
              }
            )
          ))
      }
    )
