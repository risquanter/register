package app.components

import com.raquo.laminar.api.L.{*, given}

/**
 * CSS Flexbox split-pane layout component (Phase C).
 *
 * Provides horizontal (left | right) and vertical (top / bottom) splits
 * with configurable proportions. Pure layout — no state or backend dependency.
 *
 * Scala sets only a `--split-size` CSS custom property per panel;
 * all layout rules (flex, overflow, borders) live in app.css.
 */
object SplitPane:

  /** Side-by-side split: left | right */
  def horizontal(left: HtmlElement, right: HtmlElement, leftPercent: Int = 50): HtmlElement =
    val rightPercent = 100 - leftPercent
    div(
      cls := "split-pane split-horizontal",
      div(
        cls := "split-panel split-left",
        styleAttr := s"flex: ${leftPercent} 1 0%;",
        left
      ),
      div(
        cls := "split-panel split-right",
        styleAttr := s"flex: ${rightPercent} 1 0%;",
        right
      )
    )

  /** Stacked split: top / bottom */
  def vertical(top: HtmlElement, bottom: HtmlElement, topPercent: Int = 50): HtmlElement =
    val bottomPercent = 100 - topPercent
    div(
      cls := "split-pane split-vertical",
      div(
        cls := "split-panel split-top",
        styleAttr := s"flex: ${topPercent} 1 0%;",
        top
      ),
      div(
        cls := "split-panel split-bottom",
        styleAttr := s"flex: ${bottomPercent} 1 0%;",
        bottom
      )
    )
