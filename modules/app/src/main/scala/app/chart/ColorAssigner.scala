package app.chart

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.LECNodeCurve

/** Assigns hex colours to chart nodes based on their classification.
  *
  * Classification:
  *   - Overlap (matched by the query and ticked) → Purple palette
  *   - Query-only                                → Green palette
  *   - Ticked-only                               → Aqua palette
  *
  * Shade selection uses hash-based rotation: `id.value.hashCode & 0x7fffffff % 8`.
  * This is deterministic (same node always gets the same shade) and avoids
  * the perceptual ranking issues of p95-sorted colour assignment.
  *
  * Manual overrides take precedence over automatic assignment.
  */
object ColorAssigner:

  /** Deterministic hash-rotation shade pick — same node always gets the same
    * shade within a palette. Shared with `CompareColorAssigner`, which keys
    * by branch identity instead of query/user/overlap classification but
    * uses the same per-node rotation mechanism.
    */
  def shade(palette: Vector[HexColor], nodeId: NodeId): HexColor =
    palette((nodeId.value.hashCode & 0x7fffffff) % palette.size)

  /** Classify nodes and assign hex colours.
    *
    * @param queryNodes    Node IDs matched by the current query
    * @param userNodes     Node IDs the user ticked
    * @param overrides     Manual colour overrides (applied on top of automatic)
    * @param greenPalette  Shades for query-only nodes
    * @param aquaPalette   Shades for user-only nodes
    * @param purplePalette Shades for overlap nodes
    * @return Map from node ID to HexColor for every node in queryNodes ∪ userNodes
    */
  def assign(
    queryNodes: Set[NodeId],
    userNodes: Set[NodeId],
    overrides: Map[NodeId, HexColor] = Map.empty,
    greenPalette: Vector[HexColor] = PaletteData.Green,
    aquaPalette: Vector[HexColor] = PaletteData.Aqua,
    purplePalette: Vector[HexColor] = PaletteData.Purple
  ): Map[NodeId, HexColor] =
    val overlap   = queryNodes intersect userNodes
    val queryOnly = queryNodes -- overlap
    val userOnly  = userNodes -- overlap

    val automatic: Map[NodeId, HexColor] =
      overlap.iterator.map(nid   => nid -> shade(purplePalette, nid)).toMap ++
      queryOnly.iterator.map(nid => nid -> shade(greenPalette, nid)).toMap ++
      userOnly.iterator.map(nid  => nid -> shade(aquaPalette, nid)).toMap

    automatic ++ overrides.view.filterKeys(automatic.contains)

  /** Select the visible nodes' curves and pair each with the node's colour.
    *
    * Pure function bridging the curves + colour map into the triples
    * `LECSpecBuilder.build` expects. A node's curves share its colour, and
    * the column tells them apart. Nodes missing from either the curves or
    * the colour map are silently dropped (defensive against mid-update signal
    * races in Laminar).
    *
    * @param curves   Each node's curves to draw, each with its column
    * @param visible  Set of node IDs that should appear in the chart
    * @param colorMap Colour assignments (output of `assign`)
    * @return The curves sorted by NodeId for deterministic legend order
    */
  def pairWithColors(
    curves: Map[NodeId, List[(GridColumn, LECNodeCurve)]],
    visible: Set[NodeId],
    colorMap: Map[NodeId, HexColor]
  ): Vector[(LECNodeCurve, GridColumn, HexColor)] =
    visible.toVector
      .sortBy(_.value)
      .flatMap { nid =>
        for
          color           <- colorMap.get(nid).toVector
          (column, curve) <- curves.getOrElse(nid, Nil)
        yield (curve, column, color)
      }
