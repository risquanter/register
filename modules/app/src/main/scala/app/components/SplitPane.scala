package app.components

import com.raquo.laminar.api.L.{*, given}

/**
 * CSS Flexbox split-pane layout component (Phase C).
 *
 * Provides horizontal (left | right) and vertical (top / bottom) splits
 * with configurable proportions. Pure layout — no state or backend dependency.
 */
object SplitPane:

  /** Side-by-side split: left | right */
  def horizontal(left: HtmlElement, right: HtmlElement, leftPercent: Int = 50): HtmlElement =
    div(
      cls := "split-pane split-horizontal",
      div(cls := "split-panel split-left", styleAttr := s"flex: 0 0 ${leftPercent}%", left),
      div(cls := "split-panel split-right", styleAttr := s"flex: 0 0 ${100 - leftPercent}%", right)
    )

  /** Stacked split: top / bottom */
  def vertical(top: HtmlElement, bottom: HtmlElement, topPercent: Int = 50): HtmlElement =
    div(
      cls := "split-pane split-vertical",
      div(cls := "split-panel split-top", styleAttr := s"flex: 0 0 ${topPercent}%", top),
      div(cls := "split-panel split-bottom", styleAttr := s"flex: 0 0 ${100 - topPercent}%", bottom)
    )
