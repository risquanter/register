package app.chart

import com.risquanter.register.domain.data.iron.NodeId

/** Assigns hex colours to chart nodes based on their classification.
  *
  * Classification:
  *   - Overlap (in both query + user sets) → Purple palette
  *   - Query-only                          → Green palette
  *   - User-only                           → Aqua palette
  *
  * Shade selection uses hash-based rotation: `id.value.hashCode.abs % 8`.
  * This is deterministic (same node always gets the same shade) and avoids
  * the perceptual ranking issues of p95-sorted colour assignment.
  *
  * Manual overrides take precedence over automatic assignment.
  */
object ColorAssigner:

  /** Classify nodes and assign hex colours.
    *
    * @param queryNodes  Node IDs matched by the current query
    * @param userNodes   Node IDs manually selected by the user
    * @param overrides   Manual colour overrides (hex strings, e.g. "#4ade80")
    * @return Map from node ID to hex colour string for every node in queryNodes ∪ userNodes
    */
  def assign(
    queryNodes: Set[NodeId],
    userNodes: Set[NodeId],
    overrides: Map[NodeId, String] = Map.empty
  ): Map[NodeId, String] =
    val overlap   = queryNodes intersect userNodes
    val queryOnly = queryNodes -- overlap
    val userOnly  = userNodes -- overlap

    def shade(palette: Vector[String], nodeId: NodeId): String =
      palette(nodeId.value.hashCode.abs % palette.size)

    val assigned: Map[NodeId, String] =
      overlap.iterator.map(nid   => nid -> shade(PaletteData.Purple, nid)).toMap ++
      queryOnly.iterator.map(nid => nid -> shade(PaletteData.Green, nid)).toMap ++
      userOnly.iterator.map(nid  => nid -> shade(PaletteData.Aqua, nid)).toMap

    // Overrides win
    assigned ++ overrides.view.filterKeys(assigned.contains)
