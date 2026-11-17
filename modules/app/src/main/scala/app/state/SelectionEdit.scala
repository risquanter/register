package app.state

import zio.prelude.Validation

import com.risquanter.register.domain.data.{MitigationSelection, NodeMitigationEntry, ScopeRestriction}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.domain.errors.ValidationError

/** How much of one mitigation a selection applies. */
enum MitigationToggle:
  case Off, Partial, On

/** The interface's edits of a selection. Every result is built by
  * `MitigationSelection.create`, so a selection the interface holds keeps the
  * bounds a decoded one has.
  */
object SelectionEdit:

  private def entries(selection: MitigationSelection.Enumerated): Map[MitigationId, ScopeRestriction] =
    selection match
      case MitigationSelection.Inherent          => Map.empty
      case MitigationSelection.Selected(entries) => entries

  /** The two lists `create` takes for `entries`: the mitigations on across
    * their whole scope, and the per-node ticks. */
  private def statement(entries: Map[MitigationId, ScopeRestriction]): (List[MitigationId], List[NodeMitigationEntry]) =
    (
      entries.collect { case (id, ScopeRestriction.FullScope) => id }.toList,
      entries.toList.flatMap {
        case (id, ScopeRestriction.NodesOnly(nodes)) => nodes.toList.map(NodeMitigationEntry(_, id))
        case (_, ScopeRestriction.FullScope)         => Nil
      }
    )

  /** The selection `entries` states, through `create`. */
  private def build(entries: Map[MitigationId, ScopeRestriction]): Validation[ValidationError, MitigationSelection.Enumerated] =
    val (full, ticks) = statement(entries)
    MitigationSelection.create(full, ticks)

  def toggleOf(selection: MitigationSelection.Enumerated, id: MitigationId): MitigationToggle =
    entries(selection).get(id) match
      case None                                 => MitigationToggle.Off
      case Some(ScopeRestriction.NodesOnly(_))  => MitigationToggle.Partial
      case Some(ScopeRestriction.FullScope)     => MitigationToggle.On

  /** Whether the selection applies `id` at `node`, for a node of its scope. */
  def appliesAt(selection: MitigationSelection.Enumerated, id: MitigationId, node: NodeId): Boolean =
    entries(selection).get(id) match
      case None                                    => false
      case Some(ScopeRestriction.NodesOnly(nodes)) => nodes.contains(node)
      case Some(ScopeRestriction.FullScope)        => true

  /** Off or Partial becomes On; On becomes Off. */
  def toggleWhole(
    selection: MitigationSelection.Enumerated,
    id: MitigationId
  ): Validation[ValidationError, MitigationSelection.Enumerated] =
    val current = entries(selection)
    toggleOf(selection, id) match
      case MitigationToggle.On                           => build(current - id)
      case MitigationToggle.Off | MitigationToggle.Partial => build(current.updated(id, ScopeRestriction.FullScope))

  /** Flip the mitigation at one node of its resolved scope. A mitigation that
    * is on keeps applying at every other node of `scope`, and one left
    * applying at all of `scope` is on.
    *
    * {{{
    * toggleAt(On,                 m, n, Set(n, k))   // m at k only
    * toggleAt(m at k only,        m, n, Set(n, k))   // On
    * toggleAt(m at n only,        m, n, Set(n, k))   // Off
    * }}}
    */
  def toggleAt(
    selection: MitigationSelection.Enumerated,
    id: MitigationId,
    node: NodeId,
    scope: Set[NodeId]
  ): Validation[ValidationError, MitigationSelection.Enumerated] =
    val current = entries(selection)
    val applied: Set[NodeId] = current.get(id) match
      case None                                    => Set.empty
      case Some(ScopeRestriction.NodesOnly(nodes)) => nodes
      case Some(ScopeRestriction.FullScope)        => scope
    val flipped = if applied.contains(node) then applied - node else applied + node
    if flipped.isEmpty then build(current - id)
    else if flipped == scope then build(current.updated(id, ScopeRestriction.FullScope))
    else
      val (full, ticks) = statement(current - id)
      MitigationSelection.create(full, ticks ++ flipped.toList.map(NodeMitigationEntry(_, id)))

  /** The part of the selection naming mitigations in `held`, and the
    * identifiers dropped. */
  def present(
    selection: MitigationSelection.Enumerated,
    held: Set[MitigationId]
  ): Validation[ValidationError, (MitigationSelection.Enumerated, Set[MitigationId])] =
    val (kept, dropped) = entries(selection).partition((id, _) => held.contains(id))
    build(kept).map(_ -> dropped.keySet)
