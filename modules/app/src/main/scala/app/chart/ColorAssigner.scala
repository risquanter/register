package app.chart

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.LECNodeCurve

/** Assigns hex colours to chart nodes based on their classification.
  *
  * Classification:
  *   - Overlap (in both query + user sets) → Purple palette
  *   - Query-only                          → Green palette
  *   - User-only                           → Aqua palette
  *
  * Shade selection uses hash-based rotation: `id.value.hashCode & 0x7fffffff % 8`.
  * This is deterministic (same node always gets the same shade) and avoids
  * the perceptual ranking issues of p95-sorted colour assignment.
  *
  * Manual overrides take precedence over automatic assignment.
  */
object ColorAssigner:

  /** Classify nodes and assign hex colours.
    *
    * @param queryNodes    Node IDs matched by the current query
    * @param userNodes     Node IDs manually selected by the user
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

    def shade(palette: Vector[HexColor], nodeId: NodeId): HexColor =
      palette((nodeId.value.hashCode & 0x7fffffff) % palette.size)

    val automatic: Map[NodeId, HexColor] =
      overlap.iterator.map(nid   => nid -> shade(purplePalette, nid)).toMap ++
      queryOnly.iterator.map(nid => nid -> shade(greenPalette, nid)).toMap ++
      userOnly.iterator.map(nid  => nid -> shade(aquaPalette, nid)).toMap

    automatic ++ overrides.view.filterKeys(automatic.contains)

  /** Select visible curves from cache and pair each with its assigned colour.
    *
    * Pure function bridging the cache + colour map into the
    * `Vector[(LECNodeCurve, HexColor)]` that `LECSpecBuilder.build` expects.
    * Nodes missing from either the cache or the colour map are silently
    * dropped (defensive against mid-update signal races in Laminar).
    *
    * @param allCurves Fetched curve data keyed by NodeId
    * @param visible   Set of node IDs that should appear in the chart
    * @param colorMap  Colour assignments (output of `assign`)
    * @return Paired curves sorted by NodeId for deterministic legend order
    */
  def pairWithColors(
    allCurves: Map[NodeId, LECNodeCurve],
    visible: Set[NodeId],
    colorMap: Map[NodeId, HexColor]
  ): Vector[(LECNodeCurve, HexColor)] =
    visible.toVector
      .sortBy(_.value)
      .flatMap { nid =>
        for
          curve <- allCurves.get(nid)
          color <- colorMap.get(nid)
        yield (curve, color)
      }
