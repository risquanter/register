package app.components

import com.raquo.laminar.api.L.{*, given}
import com.raquo.airstream.web.DomEventStream
import org.scalajs.dom

import app.chart.PaletteData
import app.state.BranchPaletteState
import com.risquanter.register.domain.data.iron.BranchChoice
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** A branch card's header swatch, made clickable: clicking it opens a
  * one-row popover of the 8 palette families to assign the branch's colour
  * identity (`BranchPaletteState`), plus "↺ Auto" to drop the assignment
  * and fall back to the surface's default family. Selecting either closes
  * the popover. A click anywhere outside the picker also closes it — the
  * component's own clicks stop propagating, so any click that reaches the
  * document was outside.
  *
  * The popover is viewport-positioned (`position: fixed`, coordinates read
  * off the swatch when clicked): the swatch sits inside a branch card whose
  * `overflow: hidden` (rounded-corner clipping) would cut off an
  * absolutely-positioned child — on a collapsed card, entirely. The left
  * edge is clamped so the popover stays on screen when the card sits near
  * the viewport's right edge. Any scroll also closes the popover — a fixed
  * position doesn't follow the card when content scrolls under it, so
  * dismissing is the consistent reaction (scroll is "interaction elsewhere",
  * like the outside click). The scroll listener is capture-phase: scroll
  * events don't bubble, so a bubble-phase document listener would never see
  * the inner tree containers' scrolling.
  *
  * Owns only the local popover-open flag; the assignment itself lives in
  * `BranchPaletteState` (ADR-019 Pattern 4). Clicks stop propagating — the
  * swatch sits inside the card header whose own click toggles collapse.
  */
object BranchPalettePicker:

  /** Popover width used for the right-edge clamp: 8 cells + gaps + padding,
    * kept a little generous rather than measured (the element doesn't exist
    * yet at the moment the position is computed). */
  private val PopoverWidthPx = 240.0

  /** @param choice           The branch this swatch stands for. A signal:
    *                         the active card keeps one element across branch
    *                         switches.
    * @param effectivePalette The family the branch currently renders with
    *                         (assignment or the surface's default) — the
    *                         swatch's own colour.
    */
  def apply(
    paletteState: BranchPaletteState,
    choice: Signal[BranchChoice],
    effectivePalette: Signal[Vector[HexColor]]
  ): HtmlElement =
    val open: Var[Boolean] = Var(false)
    val assignedName = paletteState.assignedNameFor(choice)
    // Viewport coordinates for the fixed-position popover, captured from the
    // swatch's bounding box on the click that opens it.
    val anchor: Var[(Double, Double)] = Var((0.0, 0.0))
    span(
      cls := "branch-palette-picker",
      documentEvents(_.onClick) --> { _ => if open.now() then open.set(false) },
      DomEventStream[dom.Event](dom.document, "scroll", useCapture = true) --> { _ =>
        if open.now() then open.set(false)
      },
      span(
        cls := "branch-card-swatch branch-palette-trigger",
        title := "Branch colour…",
        styleAttr <-- effectivePalette.map(f => s"background-color: ${PaletteData.familySwatch(f).value};"),
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
            cls := "branch-palette-popover",
            styleAttr <-- anchor.signal.map { (left, top) => s"left: ${left}px; top: ${top}px;" },
            onClick.stopPropagation --> { _ => () },
            div(
              cls := "branch-palette-cells",
              PaletteData.namedFamilies.map { (name, family) =>
                div(
                  cls := "swatch-cell",
                  cls("swatch-cell--active") <-- assignedName.map(_.contains(name)),
                  styleAttr := s"background-color: ${PaletteData.familySwatch(family).value};",
                  title := name,
                  onClick.stopPropagation.compose(_.sample(choice)) --> { c =>
                    paletteState.assign(c, name)
                    open.set(false)
                  }
                )
              }
            ),
            button(
              cls := "swatch-reset-btn",
              "↺ Auto",
              onClick.stopPropagation.compose(_.sample(choice)) --> { c =>
                paletteState.clearAssignment(c)
                open.set(false)
              }
            )
          ))
      }
    )
