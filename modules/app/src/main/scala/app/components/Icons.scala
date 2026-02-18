package app.components

import com.raquo.laminar.api.L.{*, given}

/** Inline SVG icons from the Lucide icon set (https://lucide.dev).
  *
  * All icons are ISC-licensed, stroke-based, rendered on a 24×24 viewBox.
  * Each method returns a fresh `SvgElement` (Laminar SVG node) so it can be
  * embedded directly in the DOM tree without sharing references.
  *
  * Icons are grouped by usage context:
  *   - Navigation: sidebar section icons
  *   - Tree: node-type indicators for tree views
  *   - Action: buttons and interactive affordances
  *
  * Convention: every icon function accepts an optional `cls` parameter
  * for the CSS class name, defaulting to a sensible per-context value.
  * All icons inherit `stroke: currentColor` so they follow the parent's
  * text color — no hardcoded colours.
  */
object Icons:

  // ── Shared base attributes applied to every icon ──────────────
  private val baseAttrs: Seq[Modifier[SvgElement]] = Seq(
    svg.fill         := "none",
    svg.stroke       := "currentColor",
    svg.strokeWidth  := "2",
    svg.strokeLineCap  := "round",
    svg.strokeLineJoin := "round"
  )

  private def icon24(mods: Modifier[SvgElement]*): SvgElement =
    svg.svg(
      svg.viewBox := "0 0 24 24",
      baseAttrs,
      mods
    )

  // ── Navigation icons (18px in sidebar) ────────────────────────

  /** Lucide `file-pen-line` — used for the "Design" nav item. */
  def design(cls: String = "nav-icon"): SvgElement =
    icon24(
      svg.cls := cls,
      svg.path(svg.d := "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"),
      svg.path(svg.d := "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z")
    )

  /** Lucide `chart-spline` — used for the "Analyze" nav item. */
  def analyze(cls: String = "nav-icon"): SvgElement =
    icon24(
      svg.cls := cls,
      svg.path(svg.d := "M3 3v16a2 2 0 0 0 2 2h16"),
      svg.path(svg.d := "M7 16c.5-2 1.5-7 4-7 2 0 3.5 5 5.5 5 1.5 0 2.5-2 3.5-4")
    )

  // ── Tree node icons (16px in tree views) ──────────────────────

  /** Lucide `folder-tree` — tree root node. */
  def treeRoot(cls: String = "node-icon"): SvgElement =
    icon24(
      svg.cls := cls,
      svg.path(svg.d := "M20 10a1 1 0 0 0 1-1V6a1 1 0 0 0-1-1h-2.5a1 1 0 0 1-.8-.4l-.9-1.2A1 1 0 0 0 15 3h-2a1 1 0 0 0-1 1v5a1 1 0 0 0 1 1Z"),
      svg.path(svg.d := "M20 21a1 1 0 0 0 1-1v-3a1 1 0 0 0-1-1h-2.9a1 1 0 0 1-.88-.55l-.42-.85a1 1 0 0 0-.92-.6H13a1 1 0 0 0-1 1v5a1 1 0 0 0 1 1Z"),
      svg.path(svg.d := "M3 5a2 2 0 0 0 2 2h3"),
      svg.path(svg.d := "M3 3v13a2 2 0 0 0 2 2h3")
    )

  /** Lucide `folder-open` — portfolio (grouping) node. */
  def portfolio(cls: String = "node-icon"): SvgElement =
    icon24(
      svg.cls := cls,
      svg.path(svg.d := "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2")
    )

  /** Lucide `file-chart-line` — leaf (risk observation) node. */
  def leaf(cls: String = "node-icon"): SvgElement =
    icon24(
      svg.cls := cls,
      svg.path(svg.d := "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"),
      svg.path(svg.d := "M14 2v5a1 1 0 0 0 1 1h5"),
      svg.path(svg.d := "m16 13-3.5 3.5-2-2L8 17")
    )

  // ── Danago Systems signet (logo) ──────────────────────────────

  /** The Danago Systems two-chevron signet mark. */
  def signet(cls: String = "sidebar-signet"): SvgElement =
    svg.svg(
      svg.cls     := cls,
      svg.viewBox := "0 0 1920 1866.59",
      svg.xmlns   := "http://www.w3.org/2000/svg",
      svg.polygon(
        svg.fill   := "#10b981",
        svg.points := "0 349.12 0 933.29 348.23 933.29 932.66 349.12 1920 349.12 1920 0 349.12 0 0 349.12"
      ),
      svg.polygon(
        svg.fill   := "#10b981",
        svg.points := "987.34 1517.47 0 1517.47 0 1866.59 1570.88 1866.59 1920 1517.47 1920 933.29 1571.77 933.29 987.34 1517.47"
      )
    )
